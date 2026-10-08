package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.BuildingSite;
import com.lodygames.rpgquest.building.model.BuildingSiteAnchor;
import com.lodygames.rpgquest.building.model.Facing;
import com.lodygames.rpgquest.building.model.SiteStatus;
import com.lodygames.rpgquest.database.BuildingSiteRepository;
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
 * Les emplacements de construction : lecture immédiate, écriture persistée (issue #213).
 *
 * <h2>Où vit la vérité</h2>
 *
 * <p>Dans la base, toujours. Le cache mémoire de cette classe n'est qu'une copie, chargée au
 * démarrage et mise à jour après chaque écriture <em>réussie</em>. Il existe parce que les écrans et
 * le listener de clic doivent répondre sans attendre un aller-retour SQL ; il n'est jamais consulté
 * pour décider si une écriture a eu lieu.</p>
 *
 * <h2>Anti-doublon : deux protections, pour deux problèmes différents</h2>
 *
 * <ol>
 *   <li><strong>Le même bloc.</strong> Un emplacement existe déjà exactement là ? On ne crée pas le
 *       second : on renvoie celui qui existe. C'est la réponse au spam de clics, et c'est aussi la
 *       bonne réponse à un double clic légitime — l'administrateur voulait un emplacement ici, il en
 *       a un.</li>
 *   <li><strong>Le même geste, deux fois.</strong> Un clic droit Minecraft produit couramment deux
 *       événements rapprochés, et deux blocs voisins ne sont pas « le même bloc ». Une fenêtre
 *       d'anti-rebond par joueur ({@value #DEBOUNCE_MILLIS} ms) absorbe ce cas sans jamais empêcher
 *       une création volontaire : personne ne place deux emplacements distincts en moins d'une
 *       demi-seconde.</li>
 * </ol>
 *
 * <p><strong>Aucune règle de distance minimale n'est inventée.</strong> Deux emplacements à deux
 * blocs l'un de l'autre peuvent être parfaitement légitimes — une maison et son puits. Interdire
 * cela demanderait de connaître l'emprise des bâtiments, que ce lot ne connaît pas. La question
 * appartient au lot de placement, qui saura comparer de vraies emprises.</p>
 */
public final class BuildingSiteService {

    /** Fenêtre d'anti-rebond par joueur, en millisecondes. */
    public static final long DEBOUNCE_MILLIS = 500L;

    /** Issue de la tentative de création, pour que l'appelant sache quoi dire. */
    public enum CreateOutcome {
        /** Emplacement réellement créé. */
        CREATED,
        /** Un emplacement existait déjà exactement sur ce bloc : c'est lui qui est renvoyé. */
        ALREADY_THERE,
        /** Clic trop rapproché du précédent : rien n'a été fait, et il n'y a rien à dire. */
        DEBOUNCED,
        /**
         * La demande de création avait expiré (issue #227). Rien n'a été écrit, et il faut le dire :
         * c'est très différent de « vous n'avez rien demandé ».
         */
        EXPIRED,
        /** Le nom saisi est refusé (vide, ou trop long). Rien n'a été écrit (issue #227). */
        INVALID_NAME
    }

    /**
     * @param site  l'emplacement concerné — créé, ou celui qui existait déjà. {@code null} quand il
     *              n'y a rien à montrer ({@code DEBOUNCED}, {@code EXPIRED}, {@code INVALID_NAME})
     * @param error raison lisible d'un refus, ou {@code null}
     */
    public record CreateResult(CreateOutcome outcome, BuildingSite site, String error) {

        public CreateResult(CreateOutcome outcome, BuildingSite site) {
            this(outcome, site, null);
        }

        public boolean created() {
            return outcome == CreateOutcome.CREATED;
        }

        static CreateResult refused(CreateOutcome outcome, String error) {
            return new CreateResult(outcome, null, error);
        }
    }

    private final BuildingSiteRepository repository;
    private final Clock clock;
    /** Cache des emplacements, par id. Copie de la base, jamais la vérité. */
    private final Map<String, BuildingSite> sites = new ConcurrentHashMap<>();
    /** Dernier clic retenu par joueur, en millisecondes epoch — anti-rebond uniquement. */
    private final Map<String, Long> lastClickMillis = new ConcurrentHashMap<>();

    public BuildingSiteService(BuildingSiteRepository repository) {
        this(repository, Clock.systemUTC());
    }

    /** Variante à horloge injectée : l'anti-rebond et la date de création se testent sans attendre. */
    public BuildingSiteService(BuildingSiteRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Charge le cache depuis la base. À appeler au démarrage ; jusque-là, {@link #all()} répond une
     * liste vide, ce qui est honnête (on n'a pas encore lu) plutôt que faux.
     */
    public CompletableFuture<Integer> load() {
        return repository.loadAll().thenApply(loaded -> {
            sites.clear();
            loaded.forEach(site -> sites.put(site.id(), site));
            return loaded.size();
        });
    }

    /** Tous les emplacements, triés par identifiant — donc par ordre de création. */
    public List<BuildingSite> all() {
        List<BuildingSite> out = new ArrayList<>(sites.values());
        out.sort(Comparator.comparing(BuildingSite::id));
        return List.copyOf(out);
    }

    public Optional<BuildingSite> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sites.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    /** L'emplacement déjà présent exactement sur ce bloc, s'il y en a un. */
    public Optional<BuildingSite> at(String world, int x, int y, int z) {
        return sites.values().stream()
                .filter(site -> site.samePositionAs(world, x, y, z))
                .findFirst();
    }

    /**
     * Les emplacements situés sur un bloc <strong>immédiatement adjacent</strong> — les 26 voisins du
     * cube, l'ancre elle-même exclue (issue #227).
     *
     * <p>Sert à <em>avertir</em> avant confirmation, jamais à refuser : deux emplacements voisins
     * peuvent être parfaitement légitimes, et aucune règle de distance n'est inventée. Mais un
     * emplacement à un bloc du précédent est bien plus souvent un clic de travers qu'une intention,
     * et le dire avant d'écrire coûte une ligne de message.</p>
     */
    public List<BuildingSite> adjacentTo(String world, int x, int y, int z) {
        return sites.values().stream()
                .filter(site -> site.world().equals(world))
                .filter(site -> !site.samePositionAs(world, x, y, z))
                .filter(site -> Math.abs(site.x() - x) <= 1
                        && Math.abs(site.y() - y) <= 1
                        && Math.abs(site.z() - z) <= 1)
                .sorted(Comparator.comparing(BuildingSite::id))
                .toList();
    }

    /**
     * Confirme une demande en attente avec le nom saisi par le joueur (issue #227).
     *
     * <p><strong>C'est ici — et seulement ici — qu'une écriture a lieu.</strong> Le clic ne fait que
     * préparer ; tant que cette méthode n'est pas appelée, rien n'existe en base et aucun
     * identifiant n'est consommé. Annuler, fermer la fenêtre, se déconnecter ou laisser expirer sont
     * donc, littéralement, des non-événements.</p>
     *
     * <p>Tout est <strong>revalidé</strong> : l'expiration, le nom, et l'absence d'un emplacement
     * déjà posé sur cette ancre. Une demande ouverte il y a quarante secondes décrit un monde qui a
     * pu changer entre-temps — un autre administrateur a pu marquer le même bloc.</p>
     */
    public CompletableFuture<CreateResult> confirm(PendingBuildingSite pending, String rawName,
                                                   String createdBy) {
        if (pending == null) {
            return CompletableFuture.completedFuture(CreateResult.refused(CreateOutcome.EXPIRED,
                    "Aucune demande de création en cours, ou elle a expiré. Recliquez avec l'outil."));
        }
        if (pending.expiredAt(clock.instant())) {
            return CompletableFuture.completedFuture(CreateResult.refused(CreateOutcome.EXPIRED,
                    "Demande expirée : rien n'a été enregistré. Recliquez avec l'outil."));
        }
        BuildingSiteName.Checked checked = BuildingSiteName.check(rawName);
        if (!checked.ok()) {
            return CompletableFuture.completedFuture(
                    CreateResult.refused(CreateOutcome.INVALID_NAME, checked.error()));
        }
        return create(pending.world(), pending.anchor(), pending.facing(), checked.name(), createdBy);
    }

    /**
     * Écrit un emplacement. Point d'écriture unique, sans anti-rebond : celui-ci appartient au clic
     * (voir {@link #acceptClick}), pas à la confirmation — sinon un joueur qui valide très vite après
     * avoir cliqué verrait sa confirmation avalée en silence.
     */
    public CompletableFuture<CreateResult> create(String world, BuildingSiteAnchor anchor,
                                                  Facing facing, String name, String createdBy) {
        Optional<BuildingSite> existing = at(world, anchor.x(), anchor.y(), anchor.z());
        if (existing.isPresent()) {
            return CompletableFuture.completedFuture(
                    new CreateResult(CreateOutcome.ALREADY_THERE, existing.get()));
        }
        Instant now = clock.instant();
        return repository.allocateNumber().thenCompose(number -> {
            BuildingSite site = BuildingSite.created(idFor(number), world, anchor, facing,
                    name, createdBy, now);
            return repository.insert(site).thenApply(ignored -> {
                sites.put(site.id(), site);
                return new CreateResult(CreateOutcome.CREATED, site);
            });
        });
    }

    /**
     * Ce clic doit-il être pris en compte, ou suit-il de trop près le précédent du même joueur ?
     *
     * <p>Un clic droit Minecraft produit couramment deux événements rapprochés. Sans cette fenêtre,
     * le second rouvrirait la fenêtre de saisie par-dessus la première — ce qui, selon l'ordre des
     * événements de fermeture, annulerait la demande que le joueur est en train de nommer.</p>
     */
    public boolean acceptClick(String playerKey) {
        return playerKey == null || accept(playerKey);
    }

    /**
     * Renomme un emplacement. Un identifiant inconnu renvoie {@link Optional#empty()} sans rien
     * écrire ; un nom vide retombe sur le libellé par défaut plutôt que de laisser une ligne sans
     * nom à l'écran.
     */
    public CompletableFuture<Optional<BuildingSite>> rename(String id, String name) {
        return mutate(id, site -> site.withName(trimTo(name, BuildingSite.MAX_NAME_LENGTH)),
                updated -> repository.updateName(updated.id(), updated.name()));
    }

    public CompletableFuture<Optional<BuildingSite>> describe(String id, String description) {
        return mutate(id,
                site -> site.withDescription(
                        trimTo(description, BuildingSite.MAX_DESCRIPTION_LENGTH)),
                updated -> repository.updateDescription(updated.id(), updated.description()));
    }

    /** Corrige l'orientation. La position ne bouge pas : seule la façade change. */
    public CompletableFuture<Optional<BuildingSite>> reface(String id, Facing facing) {
        return mutate(id, site -> site.withFacing(facing),
                updated -> repository.updateFacing(updated.id(), updated.facing()));
    }

    /**
     * Change l'état d'un emplacement (lot « placement » de #213).
     *
     * <p>Appelé par {@link BuildingPlacementService} après un collage réussi, et après une
     * restauration réussie. Volontairement <strong>pas</strong> exposé comme action agent : l'état
     * est une conséquence d'un fait, pas un champ qu'on édite. Le laisser modifiable à la main
     * permettrait de déclarer « occupé » un emplacement vide, et réciproquement.</p>
     */
    public CompletableFuture<Optional<BuildingSite>> markStatus(String id, SiteStatus status) {
        return mutate(id, site -> site.withStatus(status),
                updated -> repository.updateStatus(updated.id(), updated.status()));
    }

    /**
     * Supprime un emplacement. <strong>Aucun bloc du monde n'est touché</strong> : un emplacement
     * n'est qu'un marqueur logique, et ce lot ne sait rien poser.
     *
     * <p><strong>Idempotent</strong> : un identifiant déjà absent renvoie {@code false} sans erreur.
     * Un double clic sur « Supprimer » aboutit donc au même état, et c'est volontaire.</p>
     */
    public CompletableFuture<Boolean> delete(String id) {
        String key = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return repository.delete(key).thenApply(rows -> {
            sites.remove(key);
            return rows > 0;
        });
    }

    /** Les mondes où au moins un emplacement existe, triés — source du filtre du Control Panel. */
    public List<String> worlds() {
        return sites.values().stream().map(BuildingSite::world).distinct().sorted().toList();
    }

    // ---- interne -------------------------------------------------------------------------------

    /**
     * Identifiant lisible et trié : {@code buildsite_0001}. Quatre chiffres suffisent largement, et
     * le format reste correct au-delà — {@code buildsite_12345} est simplement plus long, jamais
     * ambigu ni tronqué.
     */
    static String idFor(int number) {
        return String.format(Locale.ROOT, "buildsite_%04d", number);
    }

    /** Anti-rebond : accepte ce clic et retient l'instant, ou le refuse s'il suit de trop près. */
    private boolean accept(String playerKey) {
        long now = clock.millis();
        Long previous = lastClickMillis.get(playerKey);
        if (previous != null && now - previous < DEBOUNCE_MILLIS) {
            return false;
        }
        lastClickMillis.put(playerKey, now);
        return true;
    }

    private CompletableFuture<Optional<BuildingSite>> mutate(
            String id,
            java.util.function.UnaryOperator<BuildingSite> change,
            java.util.function.Function<BuildingSite, CompletableFuture<Integer>> persist) {
        Optional<BuildingSite> current = find(id);
        if (current.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        BuildingSite updated = change.apply(current.get());
        return persist.apply(updated).thenApply(rows -> {
            if (rows > 0) {
                sites.put(updated.id(), updated);
                return Optional.of(updated);
            }
            // La ligne a disparu entre la lecture et l'écriture : le cache mentait, on le corrige
            // plutôt que d'annoncer une modification qui n'a pas eu lieu.
            sites.remove(updated.id());
            return Optional.<BuildingSite>empty();
        });
    }

    private static String trimTo(String raw, int max) {
        String value = raw == null ? "" : raw.trim();
        return value.length() <= max ? value : value.substring(0, max).trim();
    }
}
