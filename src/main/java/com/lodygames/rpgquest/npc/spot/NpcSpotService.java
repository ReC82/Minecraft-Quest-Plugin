package com.lodygames.rpgquest.npc.spot;

import com.lodygames.rpgquest.database.NpcSpotRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les emplacements de PNJ : lecture immédiate, écriture persistée (issue #381).
 *
 * <p>Même principe que {@code BuildingSiteService} : la base est la vérité, le cache mémoire n'en
 * est qu'une copie mise à jour après chaque écriture <em>réussie</em>. Aucun type Bukkit : la
 * position du PNJ, l'accessibilité du monde et le déplacement physique appartiennent à l'appelant
 * ({@code BukkitAgentActions}) ; cette classe ne décide que de ce qui est réservé, et pour qui.</p>
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>Un emplacement par bloc : réserver deux fois le même bloc renvoie l'emplacement existant.</li>
 *   <li>Un PNJ n'est affecté qu'à un seul emplacement : l'affectation libère l'ancien dans la même
 *       transaction (voir {@link NpcSpotRepository#assign}).</li>
 *   <li>Un emplacement occupé ne se supprime pas : il faut d'abord le libérer.</li>
 * </ul>
 */
public final class NpcSpotService {

    /** Issue de la réservation d'un emplacement. */
    public enum CreateOutcome {
        CREATED,
        /** Un emplacement existait déjà sur ce bloc : c'est lui qui est renvoyé, rien n'est écrit. */
        ALREADY_THERE,
        INVALID
    }

    public record CreateResult(CreateOutcome outcome, NpcSpot spot, String error) {
        public boolean created() {
            return outcome == CreateOutcome.CREATED;
        }
    }

    public enum AssignOutcome {
        ASSIGNED,
        /** Le PNJ occupait déjà cet emplacement : rien à écrire. */
        ALREADY_ASSIGNED,
        /** Un autre PNJ occupe l'emplacement. */
        OCCUPIED,
        ABSENT
    }

    public enum ReleaseOutcome {
        RELEASED,
        ALREADY_FREE,
        ABSENT
    }

    public enum DeleteOutcome {
        DELETED,
        ABSENT,
        OCCUPIED
    }

    private final NpcSpotRepository repository;
    private final Clock clock;
    private final Map<String, NpcSpot> spots = new ConcurrentHashMap<>();

    public NpcSpotService(NpcSpotRepository repository) {
        this(repository, Clock.systemUTC());
    }

    public NpcSpotService(NpcSpotRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public CompletableFuture<Integer> load() {
        return repository.loadAll().thenApply(loaded -> {
            spots.clear();
            loaded.forEach(spot -> spots.put(spot.id(), spot));
            return loaded.size();
        });
    }

    /** Tous les emplacements, triés par identifiant — donc par ordre de réservation. */
    public List<NpcSpot> all() {
        List<NpcSpot> out = new ArrayList<>(spots.values());
        out.sort(Comparator.comparing(NpcSpot::id));
        return List.copyOf(out);
    }

    public Optional<NpcSpot> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(spots.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    /** L'emplacement auquel ce PNJ est affecté, s'il y en a un. */
    public Optional<NpcSpot> assignedTo(String npcId) {
        if (npcId == null || npcId.isBlank()) {
            return Optional.empty();
        }
        return spots.values().stream().filter(s -> npcId.equals(s.npcId())).findFirst();
    }

    /** L'emplacement déjà réservé sur ce bloc, s'il y en a un. */
    public Optional<NpcSpot> at(String world, double x, double y, double z) {
        return spots.values().stream().filter(s -> s.sameBlockAs(world, x, y, z)).findFirst();
    }

    /**
     * Réserve un emplacement. {@code npcId} non nul = capture de la position d'un PNJ : l'emplacement
     * naît occupé par lui (et il quitte son ancien emplacement, dans la même transaction).
     */
    public CompletableFuture<CreateResult> create(String world, double x, double y, double z, float yaw,
                                                  float pitch, String rawName, String createdBy,
                                                  String npcId) {
        String nameError = nameError(rawName);
        if (nameError != null) {
            return CompletableFuture.completedFuture(new CreateResult(CreateOutcome.INVALID, null, nameError));
        }
        if (world == null || world.isBlank() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return CompletableFuture.completedFuture(
                    new CreateResult(CreateOutcome.INVALID, null, "Position invalide."));
        }
        Optional<NpcSpot> existing = at(world, x, y, z);
        if (existing.isPresent()) {
            return CompletableFuture.completedFuture(
                    new CreateResult(CreateOutcome.ALREADY_THERE, existing.get(), null));
        }
        Instant now = clock.instant();
        return repository.allocateNumber().thenCompose(number -> {
            NpcSpot spot = new NpcSpot(idFor(number), rawName, world, x, y, z, yaw, pitch,
                    NpcSpot.DEFAULT_RADIUS, null, createdBy, now, now);
            return repository.insert(spot).thenCompose(ignored -> {
                spots.put(spot.id(), spot);
                if (npcId == null || npcId.isBlank()) {
                    return CompletableFuture.completedFuture(new CreateResult(CreateOutcome.CREATED, spot, null));
                }
                return assign(spot.id(), npcId).thenApply(outcome -> new CreateResult(CreateOutcome.CREATED,
                        find(spot.id()).orElse(spot), null));
            });
        });
    }

    /** Renomme. Un nom vide ou trop long est refusé : le panel et la commande valident déjà. */
    public CompletableFuture<Optional<NpcSpot>> rename(String id, String rawName) {
        String error = rawName == null || rawName.isBlank()
                ? "Un emplacement garde toujours un nom." : nameError(rawName);
        if (error != null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(error));
        }
        Optional<NpcSpot> current = find(id);
        if (current.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Instant now = clock.instant();
        NpcSpot updated = current.get().withName(rawName, now);
        return repository.updateName(updated.id(), updated.name(), now)
                .thenApply(rows -> commit(updated, rows));
    }

    /** Corrige l'orientation. La position ne bouge jamais. */
    public CompletableFuture<Optional<NpcSpot>> orient(String id, float yaw, float pitch) {
        Optional<NpcSpot> current = find(id);
        if (current.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Instant now = clock.instant();
        NpcSpot updated = current.get().withOrientation(yaw, pitch, now);
        return repository.updateOrientation(updated.id(), updated.yaw(), updated.pitch(), now)
                .thenApply(rows -> commit(updated, rows));
    }

    /** Affecte {@code npcId} à cet emplacement ; il quitte tout autre emplacement. */
    public CompletableFuture<AssignOutcome> assign(String spotId, String npcId) {
        Optional<NpcSpot> current = find(spotId);
        if (current.isEmpty()) {
            return CompletableFuture.completedFuture(AssignOutcome.ABSENT);
        }
        NpcSpot spot = current.get();
        if (npcId.equals(spot.npcId())) {
            return CompletableFuture.completedFuture(AssignOutcome.ALREADY_ASSIGNED);
        }
        if (!spot.free()) {
            return CompletableFuture.completedFuture(AssignOutcome.OCCUPIED);
        }
        Instant now = clock.instant();
        return repository.assign(spot.id(), npcId, now).thenApply(done -> {
            if (!done) {
                return AssignOutcome.OCCUPIED;
            }
            for (NpcSpot other : List.copyOf(spots.values())) {
                if (npcId.equals(other.npcId()) && !other.id().equals(spot.id())) {
                    spots.put(other.id(), other.withNpc(null, now));
                }
            }
            spots.put(spot.id(), spot.withNpc(npcId, now));
            return AssignOutcome.ASSIGNED;
        });
    }

    /** Libère l'emplacement, sans toucher au PNJ physique. Idempotent. */
    public CompletableFuture<ReleaseOutcome> release(String spotId) {
        Optional<NpcSpot> current = find(spotId);
        if (current.isEmpty()) {
            return CompletableFuture.completedFuture(ReleaseOutcome.ABSENT);
        }
        NpcSpot spot = current.get();
        if (spot.free()) {
            return CompletableFuture.completedFuture(ReleaseOutcome.ALREADY_FREE);
        }
        Instant now = clock.instant();
        return repository.release(spot.id(), spot.npcId(), now).thenApply(rows -> {
            spots.put(spot.id(), spot.withNpc(null, now));
            return rows > 0 ? ReleaseOutcome.RELEASED : ReleaseOutcome.ALREADY_FREE;
        });
    }

    /** Supprime un emplacement libre. Occupé = refus ; absent = sans erreur (rejeu inoffensif). */
    public CompletableFuture<DeleteOutcome> delete(String id) {
        Optional<NpcSpot> current = find(id);
        if (current.isEmpty()) {
            return CompletableFuture.completedFuture(DeleteOutcome.ABSENT);
        }
        if (!current.get().free()) {
            return CompletableFuture.completedFuture(DeleteOutcome.OCCUPIED);
        }
        String key = current.get().id();
        return repository.deleteFree(key).thenApply(rows -> {
            if (rows > 0) {
                spots.remove(key);
                return DeleteOutcome.DELETED;
            }
            // Occupé entre-temps par une autre écriture : le cache sera relu au prochain chargement,
            // mais on ne prétend pas avoir supprimé.
            return DeleteOutcome.OCCUPIED;
        });
    }

    /** Message d'erreur pour un nom, ou {@code null} s'il est acceptable (vide = nom par défaut). */
    public static String nameError(String rawName) {
        if (rawName != null && rawName.trim().length() > NpcSpot.MAX_NAME_LENGTH) {
            return "Nom trop long (" + NpcSpot.MAX_NAME_LENGTH + " caractères au plus).";
        }
        return null;
    }

    static String idFor(int number) {
        return String.format(Locale.ROOT, "npcspot_%04d", number);
    }

    private Optional<NpcSpot> commit(NpcSpot updated, int rows) {
        if (rows > 0) {
            spots.put(updated.id(), updated);
            return Optional.of(updated);
        }
        spots.remove(updated.id());
        return Optional.empty();
    }
}
