package com.lodygames.rpgquest.city;

import com.lodygames.rpgquest.database.VillageCenterRepository;
import com.lodygames.rpgquest.travel.beacon.model.VillageCenter;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;

/**
 * Registre <strong>unique</strong> des villes (issue #178), qui sont les centres de village de #151.
 *
 * <p>Avant #178, {@code TravelBeaconService} tenait seul sa table des centres. La découverte, le
 * panel et le menu de voyage lisent désormais tous ce registre : il n'existe qu'une copie en mémoire,
 * et une ville renommée dans le panel l'est aussi dans le menu « Villages ».</p>
 *
 * <h2>Écriture d'abord, mémoire ensuite</h2>
 *
 * <p>Chaque mutation calcule la nouvelle fiche, l'écrit en base, puis seulement l'installe en mémoire
 * sur le thread principal et reconstruit l'index. Une écriture refusée par la base ne laisse donc
 * jamais une ville « fantôme » visible en jeu. Le futur renvoyé se termine <em>après</em>
 * l'installation : l'appelant qui relit le registre voit la nouvelle valeur.</p>
 *
 * <p>L'identifiant ne change jamais, quelle que soit la mutation (nom, taille, centre, état).</p>
 */
public final class CityRegistry {

    private final VillageCenterRepository repository;
    private final Executor mainThread;
    private final Logger logger;
    private final Clock clock;
    private final Map<String, VillageCenter> byId = new ConcurrentHashMap<>();
    /** Identifiants réservés par une création en cours d'écriture : deux créations ne prennent jamais le même. */
    private final Set<String> reserved = ConcurrentHashMap.newKeySet();
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    private final List<java.util.function.Consumer<String>> deleteListeners = new CopyOnWriteArrayList<>();
    private volatile CityIndex index = CityIndex.empty();
    private volatile boolean loaded;

    public CityRegistry(VillageCenterRepository repository, Executor mainThread, Logger logger) {
        this(repository, mainThread, logger, Clock.systemUTC());
    }

    public CityRegistry(VillageCenterRepository repository, Executor mainThread, Logger logger, Clock clock) {
        this.repository = repository;
        this.mainThread = mainThread;
        this.logger = logger;
        this.clock = clock;
    }

    /** Charge la table ; l'index est construit sur le thread principal. */
    public CompletableFuture<Integer> load() {
        return repository.loadAll().thenApplyAsync(list -> {
            byId.clear();
            for (VillageCenter city : list) {
                byId.put(city.id(), city);
            }
            loaded = true;
            rebuild();
            logger.info("Villes (centres de village) chargées : {}.", list.size());
            return list.size();
        }, mainThread);
    }

    public void clear() {
        byId.clear();
        reserved.clear();
        index = CityIndex.empty();
        loaded = false;
    }

    public boolean loaded() {
        return loaded;
    }

    /** Appelé sur le thread principal après chaque changement installé en mémoire. */
    public void onChange(Runnable listener) {
        changeListeners.add(listener);
    }

    /**
     * Appelé sur le thread principal quand une ville est supprimée, AVANT la reconstruction de
     * l'index : les découvertes en mémoire doivent partir avec celles de la base.
     */
    public void onDelete(java.util.function.Consumer<String> listener) {
        deleteListeners.add(listener);
    }

    public Optional<VillageCenter> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    /** Toutes les villes, actives ou non, triées par nom puis par identifiant. */
    public List<VillageCenter> all() {
        return byId.values().stream()
                .sorted(Comparator.comparing((VillageCenter c) -> c.name().toLowerCase(Locale.ROOT))
                        .thenComparing(VillageCenter::id))
                .toList();
    }

    public CityIndex index() {
        return index;
    }

    // ---- Mutations ---------------------------------------------------------------------------

    /**
     * Crée une ville au centre désigné, avec la distance par défaut. L'identifiant est dérivé du nom
     * et n'est réservé qu'ici : annuler avant ce point ne consomme rien.
     */
    public CompletableFuture<Result> create(String rawName, String world, double x, double y, double z,
                                            float yaw, float pitch) {
        CityName.Checked checked = CityName.check(rawName);
        if (!checked.ok()) {
            return CompletableFuture.completedFuture(Result.refused(checked.error()));
        }
        Optional<String> duplicate = duplicateName(checked.name(), null);
        if (duplicate.isPresent()) {
            return CompletableFuture.completedFuture(Result.refused(duplicate.get()));
        }
        String id;
        synchronized (reserved) {
            id = CityName.newId(checked.name(), candidate -> byId.containsKey(candidate)
                    || reserved.contains(candidate));
            reserved.add(id);
        }
        VillageCenter city = new VillageCenter(id, checked.name(), world, x, y, z, yaw, pitch, true,
                clock.instant(), CityPerimeter.DEFAULT_DISTANCE);
        String finalId = id;
        return persist(city).whenComplete((r, e) -> reserved.remove(finalId));
    }

    /**
     * Forme historique de #151 ({@code /rpgadmin travel village set|sethub}) : crée ou déplace un
     * centre sous un identifiant choisi. Une ville existante garde sa distance, son état et sa date.
     */
    public CompletableFuture<Result> upsertAt(String id, String name, String world, double x, double y,
                                              double z, float yaw, float pitch) {
        VillageCenter existing = byId.get(id);
        VillageCenter city = existing == null
                ? new VillageCenter(id, name, world, x, y, z, yaw, pitch, true, clock.instant(),
                        CityPerimeter.DEFAULT_DISTANCE)
                : existing.movedTo(world, x, y, z, yaw, pitch).withName(name).withActive(true);
        return persist(city);
    }

    public CompletableFuture<Result> rename(String id, String rawName) {
        CityName.Checked checked = CityName.check(rawName);
        if (!checked.ok()) {
            return CompletableFuture.completedFuture(Result.refused(checked.error()));
        }
        Optional<String> duplicate = duplicateName(checked.name(), id);
        if (duplicate.isPresent()) {
            return CompletableFuture.completedFuture(Result.refused(duplicate.get()));
        }
        return mutate(id, city -> city.withName(checked.name()));
    }

    public CompletableFuture<Result> resize(String id, int distance) {
        Optional<String> invalid = CityPerimeter.checkDistance(distance);
        if (invalid.isPresent()) {
            return CompletableFuture.completedFuture(Result.refused(invalid.get()));
        }
        return mutate(id, city -> city.withDetectionDistance(distance));
    }

    public CompletableFuture<Result> move(String id, String world, double x, double y, double z,
                                          float yaw, float pitch) {
        return mutate(id, city -> city.movedTo(world, x, y, z, yaw, pitch));
    }

    public CompletableFuture<Result> setActive(String id, boolean active) {
        return mutate(id, city -> city.withActive(active));
    }

    /** Supprime la ville et ses découvertes (voir {@link VillageCenterRepository#delete}). */
    public CompletableFuture<Result> delete(String id) {
        VillageCenter existing = byId.get(id);
        if (existing == null) {
            return CompletableFuture.completedFuture(Result.refused("Ville introuvable : " + id));
        }
        return repository.delete(id).thenApplyAsync(count -> {
            byId.remove(id);
            for (var listener : deleteListeners) {
                listener.accept(id);
            }
            rebuild();
            return Result.ok(existing);
        }, mainThread);
    }

    private CompletableFuture<Result> mutate(String id, UnaryOperator<VillageCenter> change) {
        VillageCenter existing = byId.get(id);
        if (existing == null) {
            return CompletableFuture.completedFuture(Result.refused("Ville introuvable : " + id));
        }
        return persist(change.apply(existing));
    }

    private CompletableFuture<Result> persist(VillageCenter city) {
        return repository.upsert(city).thenApplyAsync(ignored -> {
            byId.put(city.id(), city);
            rebuild();
            return Result.ok(city);
        }, mainThread).exceptionally(error -> {
            logger.error("Impossible d'enregistrer la ville {}", city.id(), error);
            return Result.refused("Écriture en base impossible : rien n'a été modifié.");
        });
    }

    private Optional<String> duplicateName(String name, String exceptId) {
        return byId.values().stream()
                .filter(c -> !c.id().equals(exceptId) && c.name().equalsIgnoreCase(name))
                .findFirst()
                .map(c -> "Une autre ville porte déjà ce nom (" + c.id() + ").");
    }

    private void rebuild() {
        index = CityIndex.of(byId.values());
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (RuntimeException error) {
                logger.error("Réaction à un changement de ville en échec", error);
            }
        }
    }

    /**
     * Résultat d'une mutation : la fiche installée, ou un refus lisible. Rien n'a été écrit en cas
     * de refus.
     */
    public record Result(VillageCenter city, String error) {

        static Result ok(VillageCenter city) {
            return new Result(city, null);
        }

        static Result refused(String error) {
            return new Result(null, error);
        }

        public boolean ok() {
            return error == null;
        }
    }
}
