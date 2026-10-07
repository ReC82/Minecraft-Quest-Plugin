package com.lodygames.rpgquest.npc;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/**
 * Identité stable d'un PNJ, totalement indépendante de son nom personnalisé
 * affiché (purement cosmétique). Deux stratégies de stockage selon le type
 * d'entité, invisibles des appelants ({@code currentId}/{@code tag}/
 * {@code untag} ont le même contrat quel que soit le PNJ) :
 *
 * <ul>
 * <li><b>PNJ Citizens</b> (prioritaire quand Citizens est installé et actif) —
 * la liaison {@code NPC#getUniqueId()} ↔ id RPGQuest est persistée dans la
 * base SQLite de RPGQuest ({@link NpcBindingRepository}), jamais uniquement
 * sur l'entité Bukkit. C'est une nécessité, pas un choix : Citizens recrée
 * une <b>nouvelle</b> entité Bukkit éphémère à chaque (re)spawn (y compris
 * au redémarrage du serveur), donc tout ce qui est posé directement sur
 * cette entité (comme un {@link PersistentDataContainer}) est perdu dès le
 * prochain redémarrage — c'est exactement le bug initialement rapporté.
 * {@code NPC#getUniqueId()} est utilisé comme clé (garanti unique/stable par
 * Citizens lui-même) plutôt que {@code NPC#getId()} (dont la javadoc
 * CitizensAPI précise qu'il « n'est pas garanti unique entre sessions »).</li>
 * <li><b>Entité vanilla ordinaire</b> (Citizens absent, ou entité non gérée
 * par Citizens) — inchangé par rapport à l'implémentation d'origine : id
 * stocké dans le {@link PersistentDataContainer} de l'entité elle-même
 * ({@code rpgquest:npc_id}), qui survit nativement aux redémarrages car
 * cette entité-là n'est jamais recréée par un tiers.</li>
 * </ul>
 *
 * <p>Toute référence à un type Citizens (import {@code net.citizensnpcs.*})
 * est isolée dans {@link CitizensNpcBridge}, qui n'est instanciée — donc
 * jamais chargée par la JVM — que si Citizens est réellement présent et actif
 * (détecté ici via les seuls types Bukkit, jamais un type Citizens). C'est ce
 * qui permet à RPGQuest de fonctionner normalement sur un serveur sans
 * Citizens installé malgré la dépendance {@code compileOnly}.</p>
 */
public final class NpcIdentityService {

    private static final String NAMESPACE = "rpgquest";

    private final RPGQuestPlugin plugin;
    private final NpcIdRepository sequenceRepository;
    private final NpcBindingRepository citizensBindingRepository;
    private final NamespacedKey idKey;
    private final @Nullable CitizensNpcBridge citizensBridge;
    /**
     * Issue #165 — comportements du PLUGIN Citizens (LookClose, promenade). Pont distinct de
     * {@link CitizensNpcBridge} : ses types viennent de {@code citizens-main}, pas de
     * {@code citizensapi}. Si la build installée ne les fournit pas, ce pont seul échoue — le reste
     * de l'intégration PNJ continue de fonctionner.
     */
    private final @Nullable CitizensBehaviourBridge behaviourBridge;
    private final Map<UUID, String> citizensCache = new ConcurrentHashMap<>();

    public NpcIdentityService(RPGQuestPlugin plugin, NpcIdRepository sequenceRepository,
                               NpcBindingRepository citizensBindingRepository) {
        this.plugin = plugin;
        this.sequenceRepository = sequenceRepository;
        this.citizensBindingRepository = citizensBindingRepository;
        this.idKey = new NamespacedKey(plugin, "npc_id");
        this.citizensBridge = citizensActive(plugin) ? new CitizensNpcBridge() : null;
        this.behaviourBridge = this.citizensBridge == null ? null : newBehaviourBridge(plugin);

        if (citizensBridge != null) {
            citizensBindingRepository.loadAll().thenAccept(bindings ->
                    bindings.forEach(binding -> citizensCache.put(binding.citizensUuid(), binding.npcId())))
                    .exceptionally(error -> {
                        plugin.getSLF4JLogger().error("Impossible de charger les liaisons PNJ Citizens.", error);
                        return null;
                    });
        }
    }

    /** Vérifie via les seuls types Bukkit (Plugin/PluginManager) — jamais un type Citizens. */
    private static boolean citizensActive(RPGQuestPlugin plugin) {
        Plugin citizens = plugin.getServer().getPluginManager().getPlugin("Citizens");
        return citizens != null && citizens.isEnabled();
    }

    /** Vrai si Citizens est installé et actif : détermine si les écouteurs dédiés Citizens doivent être enregistrés. */
    public boolean citizensAvailable() {
        return citizensBridge != null;
    }

    /** Vrai si {@code entity} est un PNJ géré par Citizens (jamais vrai si Citizens n'est pas actif). */
    public boolean isCitizensNpc(Entity entity) {
        return citizensBridge != null && citizensBridge.isNpc(entity);
    }

    /** Identifiant stable actuellement porté par l'entité, ou vide si elle n'est pas marquée. */
    public Optional<String> currentId(Entity entity) {
        if (citizensBridge != null) {
            Optional<UUID> citizensUuid = citizensBridge.citizensUniqueId(entity);
            if (citizensUuid.isPresent()) {
                return Optional.ofNullable(citizensCache.get(citizensUuid.get()));
            }
        }
        return Optional.ofNullable(entity.getPersistentDataContainer().get(idKey, PersistentDataType.STRING));
    }

    /** Id numérique Citizens de l'entité, pour affichage admin uniquement (jamais utilisé comme clé). */
    public Optional<Integer> citizensNumericId(Entity entity) {
        return citizensBridge == null ? Optional.empty() : citizensBridge.resolve(entity).map(CitizensNpcBridge.Ref::citizensNumericId);
    }

    // ---- Roster Citizens + liaison (issue #81, phase 1) ---------------------------------------

    /**
     * PNJ Citizens du registre (jamais un scan d'entités Minecraft). Vide si Citizens est inactif.
     * <strong>À appeler sur le thread principal</strong> (API Citizens).
     */
    public List<CitizensNpc> citizensRoster() {
        if (citizensBridge == null) {
            return List.of();
        }
        List<CitizensNpc> roster = citizensBridge.roster();
        if (behaviourBridge == null) {
            // Comportements inconnus (build Citizens incompatible) — et non « désactivés ».
            return roster;
        }
        List<CitizensNpc> enriched = new java.util.ArrayList<>(roster.size());
        for (CitizensNpc npc : roster) {
            enriched.add(npc.withBehaviour(
                    behaviourBridge.readLookClose(npc.uuid()).orElse(null),
                    behaviourBridge.readWander(npc.uuid()).orElse(null)));
        }
        return enriched;
    }

    /** Un PNJ Citizens par son id numérique, ou vide. <strong>Thread principal</strong>. */
    public Optional<CitizensNpc> citizensByNumericId(int numericId) {
        return citizensBridge == null ? Optional.empty() : citizensBridge.byNumericId(numericId);
    }

    /**
     * Issue #165 — URL MineSkin acceptée. Volontairement <strong>stricte</strong> : on ne reçoit
     * jamais une commande libre, seulement un lien {@code https://minesk.in/<id>} tel que MineSkin
     * le fournit. Pas de query, pas de fragment, pas d'autre domaine — une URL arbitraire ferait
     * de cette action un téléchargeur générique côté serveur.
     */
    private static final java.util.regex.Pattern MINESKIN_URL =
            java.util.regex.Pattern.compile("^https://minesk\\.in/[A-Za-z0-9]{8,64}$");

    /** {@code true} si {@code url} est un lien MineSkin exploitable (validation côté serveur). */
    public static boolean isValidMineSkinUrl(String url) {
        return url != null && MINESKIN_URL.matcher(url.trim()).matches();
    }

    /**
     * Pseudo Minecraft accepté comme source de skin : {@code [A-Za-z0-9_]{3,16}}, le format des
     * comptes Minecraft. Citizens résout le pseudo lui-même ({@code /npc skin <pseudo>}) ; RPGQuest
     * ne fait <strong>aucun appel réseau</strong> et ne peut donc pas garantir que le compte existe.
     */
    private static final java.util.regex.Pattern MINECRAFT_NAME =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    /** {@code true} si {@code name} a la forme d'un pseudo Minecraft (validation de forme seule). */
    public static boolean isValidSkinPlayerName(String name) {
        return name != null && MINECRAFT_NAME.matcher(name.trim()).matches();
    }

    /**
     * Nom réellement affiché en jeu par le PNJ Citizens lié. <strong>Thread principal.</strong>
     *
     * <p>Distinct du nom de la <em>définition</em> RPGQuest ({@code npcs/<id>.yml}) : la définition
     * sert aux catalogues et au locuteur des dialogues, celui-ci est ce que les joueurs lisent
     * au-dessus du PNJ. Les deux peuvent légitimement différer.</p>
     */
    public Optional<String> citizensNameOf(UUID citizensUuid) {
        return citizensBridge == null ? Optional.empty() : citizensBridge.nameByUuid(citizensUuid);
    }

    /**
     * {@code true} si ce PNJ est de type {@code PLAYER}, donc porteur d'un skin dont l'apparence
     * dépend du nom quand aucun skin explicite n'est posé. {@code false} pour tout autre type
     * (villageois, zombie…), qui n'a pas de skin du tout. Vide = indéterminable.
     * <strong>Thread principal.</strong>
     */
    public Optional<Boolean> citizensIsPlayerType(UUID citizensUuid) {
        return citizensBridge == null ? Optional.empty()
                : citizensBridge.typeByUuid(citizensUuid)
                        .map(type -> type == org.bukkit.entity.EntityType.PLAYER);
    }

    /**
     * Déplace le PNJ Citizens d'UUID donné, sans le recréer ni le faire apparaître.
     * <strong>Thread principal.</strong>
     *
     * @return la position enregistrée après la tentative, pour que l'appelant puisse la comparer à
     *         celle demandée plutôt que de supposer un succès.
     */
    public Optional<org.bukkit.Location> moveCitizens(UUID citizensUuid, org.bukkit.Location target) {
        return citizensBridge == null ? Optional.empty() : citizensBridge.moveByUuid(citizensUuid, target);
    }

    /**
     * Instancie le pont des comportements. Le chargement de la classe touche des types de
     * {@code citizens-main} : si la build installée ne les fournit pas, on retient le motif et on
     * se déclare indisponible plutôt que d'empêcher le plugin de démarrer.
     */
    private static @Nullable CitizensBehaviourBridge newBehaviourBridge(RPGQuestPlugin plugin) {
        try {
            return new CitizensBehaviourBridge();
        } catch (LinkageError e) {
            plugin.getSLF4JLogger().warn("Build Citizens incompatible : « regarder les joueurs » et "
                    + "« promenade » resteront indisponibles dans le panel ({}). Le reste de "
                    + "l'intégration PNJ fonctionne normalement.", e.toString());
            return null;
        }
    }

    /** Issue #165 — état réel du trait {@code lookclose}. <strong>Thread principal.</strong> */
    public Optional<LookCloseState> lookCloseOf(UUID citizensUuid) {
        return behaviourBridge == null ? Optional.empty() : behaviourBridge.readLookClose(citizensUuid);
    }

    /** Issue #165 — état réel de la promenade. <strong>Thread principal.</strong> */
    public Optional<WanderState> wanderOf(UUID citizensUuid) {
        return behaviourBridge == null ? Optional.empty() : behaviourBridge.readWander(citizensUuid);
    }

    /**
     * Pose un état <strong>explicite</strong> pour « regarder les joueurs ». Jamais un toggle :
     * rejouer la requête ne doit pas inverser l'état. <strong>Thread principal.</strong>
     */
    public NpcBehaviourOutcome setLookClose(UUID citizensUuid, boolean enabled, @Nullable Double range) {
        return behaviourBridge == null ? behaviourUnavailable()
                : behaviourBridge.applyLookClose(citizensUuid, enabled, range);
    }

    /** Active la promenade sur une zone bornée autour de {@code anchor}. <strong>Thread principal.</strong> */
    public NpcBehaviourOutcome enableWander(UUID citizensUuid, org.bukkit.Location anchor,
                                            int xRange, int yRange, boolean confirmReplace) {
        return behaviourBridge == null ? behaviourUnavailable()
                : behaviourBridge.applyWanderEnable(citizensUuid, anchor, xRange, yRange, confirmReplace);
    }

    /** Désactive la promenade et revient au fournisseur neutre. <strong>Thread principal.</strong> */
    public NpcBehaviourOutcome disableWander(UUID citizensUuid) {
        return behaviourBridge == null ? behaviourUnavailable()
                : behaviourBridge.applyWanderDisable(citizensUuid);
    }

    private static NpcBehaviourOutcome behaviourUnavailable() {
        return new NpcBehaviourOutcome(false, "CITIZENS_INCOMPATIBLE",
                "Citizens est absent ou sa build n'expose pas ce comportement : rien n'a été modifié.");
    }

    /**
     * Issue #165 — renomme le PNJ Citizens lié à {@code npcId}. Ciblé par l'UUID issu de la
     * liaison persistée, jamais par le nom affiché. <strong>Thread principal.</strong>
     *
     * @return l'ancien nom, ou vide si aucun PNJ Citizens n'est lié / trouvé.
     */
    public Optional<String> renameCitizensFor(UUID citizensUuid, String newName) {
        return citizensBridge == null ? Optional.empty() : citizensBridge.renameByUuid(citizensUuid, newName);
    }

    /**
     * Issue #165 — applique un skin MineSkin au PNJ Citizens d'UUID donné.
     * <strong>Thread principal</strong> (sélection + commande Citizens atomiques).
     */
    public boolean applyCitizensSkin(UUID citizensUuid, String minesSkinUrl) {
        return citizensBridge != null && citizensBridge.applySkinUrlByUuid(citizensUuid, minesSkinUrl);
    }

    /**
     * Rattache explicitement l'apparence d'un PNJ au skin d'un <em>nom</em> donné.
     * <strong>Thread principal.</strong>
     *
     * <p>Utilisé pour <strong>reconduire</strong> l'apparence au travers d'un renommage, quand
     * aucune source explicite n'a jamais été enregistrée : avant le renommage, Citizens dérivait
     * déjà l'apparence de ce nom-là. Les joueurs voient donc exactement la même chose qu'avant —
     * aucun skin nouveau n'est appliqué.</p>
     */
    public boolean pinCitizensSkinToName(UUID citizensUuid, String skinName) {
        return citizensBridge != null && citizensBridge.applySkinNameByUuid(citizensUuid, skinName);
    }

    /**
     * Lie {@code npcId} au PNJ Citizens {@code ref}. Valide les collisions et l'idempotence via
     * {@link CitizensBindPlanner}, écrit de façon atomique ({@link NpcBindingRepository#insertIfAbsent}),
     * puis rafraîchit le cache pour que l'identification en jeu prenne effet sans redémarrage.
     * Ne réaffecte jamais silencieusement (pas de rebind dans cette phase).
     *
     * <p>Le {@code ref} doit être résolu <em>en amont</em> sur le thread principal
     * ({@link #citizensByNumericId}) — cette méthode ne touche que la base (async).</p>
     */
    public CompletableFuture<BindResult> bindCitizens(String npcId, CitizensNpc ref) {
        return citizensBindingRepository.loadAll().thenCompose(existing -> {
            CitizensBindPlanner.Plan plan =
                    CitizensBindPlanner.plan(npcId, ref.uuid(), ref.numericId(), existing);
            return switch (plan.action()) {
                case NOOP -> CompletableFuture.completedFuture(
                        new BindResult(true, "NOOP", plan.message(), ref.numericId()));
                case REJECT -> CompletableFuture.completedFuture(
                        new BindResult(false, plan.code(), plan.message(), ref.numericId()));
                case INSERT -> citizensBindingRepository.insertIfAbsent(ref.uuid(), ref.numericId(), npcId)
                        .thenApply(inserted -> {
                            if (inserted) {
                                citizensCache.put(ref.uuid(), npcId);
                                return new BindResult(true, "LINKED",
                                        "« " + npcId + " » lié à Citizens #" + ref.numericId()
                                                + " (« " + ref.name() + " »).", ref.numericId());
                            }
                            // Course rarissime : une liaison est apparue entre le loadAll et l'insert.
                            return new BindResult(false, "CITIZENS_TAKEN",
                                    "Citizens #" + ref.numericId() + " vient d'être lié par ailleurs.",
                                    ref.numericId());
                        });
            };
        }).exceptionally(error -> new BindResult(false, "ERROR",
                "Échec de la liaison : " + error.getClass().getSimpleName(), ref.numericId()));
    }

    /** @param code {@code LINKED} / {@code NOOP} / {@code CITIZENS_TAKEN} / {@code NPC_ID_TAKEN} / {@code ERROR} */
    public record BindResult(boolean ok, String code, String message, int citizensNumericId) {
    }

    // ---- Création physique + rollback (issue #81, phase 2) -------------------------------------

    /**
     * Crée un PNJ Citizens ({@code EntityType.PLAYER}) nommé {@code name} et le fait apparaître à
     * {@code location}, puis renvoie sa vue registre. Vide si Citizens est inactif ou si la
     * matérialisation a échoué (dans ce cas rien n'est laissé derrière).
     *
     * <p><strong>Thread principal obligatoire</strong> — l'appelant orchestre le hop. Cette méthode
     * ne persiste aucun binding : la liaison passe ensuite par {@link #bindCitizens}.</p>
     */
    public Optional<CitizensNpc> createCitizensNpc(String name, Location location) {
        return citizensBridge == null ? Optional.empty() : citizensBridge.createAndSpawn(name, location);
    }

    /**
     * Détruit définitivement un PNJ Citizens — <strong>réservé au rollback</strong> d'une création
     * qui vient d'échouer (jamais un PNJ préexistant : la double clé {@code numericId}+{@code uuid}
     * cible l'entité exacte créée par l'action). <strong>Thread principal obligatoire</strong>.
     */
    public boolean destroyCitizensNpc(int numericId, UUID uuid) {
        return citizensBridge != null && citizensBridge.destroyIfMatches(numericId, uuid);
    }

    /**
     * Vrai si {@code candidate} peut servir de fragment de {@link NamespacedKey} (mêmes règles que
     * Bukkit). Toujours vérifié **avant** tout appel à {@link #tag}, jamais après — c'est cette
     * construction non gardée d'une clé à partir d'un texte non validé qui provoquait le bug
     * historique décrit dans l'ancienne implémentation (nom affiché utilisé comme identifiant).
     */
    public static boolean isValidId(String candidate) {
        try {
            new NamespacedKey(NAMESPACE, candidate);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Forme canonique d'un identifiant auto-généré (secours quand aucun id explicite n'est fourni). */
    public static String generatedId(int sequence) {
        return "npc_" + sequence;
    }

    /**
     * Marque {@code entity} avec {@code requestedId} (déjà validé par {@link #isValidId}), ou un
     * identifiant auto-généré ({@link #generatedId}) si {@code requestedId} est {@code null}.
     * Idempotent : une entité déjà marquée conserve son id existant quel que soit
     * {@code requestedId} — un ré-étiquetage explicite doit d'abord passer par {@link #untag}.
     */
    public CompletableFuture<TagResult> tag(Entity entity, @Nullable String requestedId) {
        if (citizensBridge != null) {
            Optional<CitizensNpcBridge.Ref> ref = citizensBridge.resolve(entity);
            if (ref.isPresent()) {
                return tagCitizens(ref.get(), requestedId);
            }
        }
        return tagPlainEntity(entity, requestedId);
    }

    private CompletableFuture<TagResult> tagCitizens(CitizensNpcBridge.Ref ref, @Nullable String requestedId) {
        String existing = citizensCache.get(ref.citizensUuid());
        if (existing != null) {
            return CompletableFuture.completedFuture(new TagResult(false, existing));
        }
        if (requestedId != null) {
            return citizensBindingRepository.upsert(ref.citizensUuid(), ref.citizensNumericId(), requestedId)
                    .thenApply(ignored -> {
                        citizensCache.put(ref.citizensUuid(), requestedId);
                        return new TagResult(true, requestedId);
                    });
        }
        return sequenceRepository.allocateId().thenCompose(sequence -> {
            String npcId = generatedId(sequence);
            return citizensBindingRepository.upsert(ref.citizensUuid(), ref.citizensNumericId(), npcId)
                    .thenApply(ignored -> {
                        citizensCache.put(ref.citizensUuid(), npcId);
                        return new TagResult(true, npcId);
                    });
        });
    }

    private CompletableFuture<TagResult> tagPlainEntity(Entity entity, @Nullable String requestedId) {
        Optional<String> existing = currentId(entity);
        if (existing.isPresent()) {
            return CompletableFuture.completedFuture(new TagResult(false, existing.get()));
        }
        if (requestedId != null) {
            entity.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, requestedId);
            return CompletableFuture.completedFuture(new TagResult(true, requestedId));
        }

        CompletableFuture<TagResult> result = new CompletableFuture<>();
        sequenceRepository.allocateId()
                .thenAccept(sequence -> runOnMainThread(() -> {
                    String npcId = generatedId(sequence);
                    entity.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, npcId);
                    result.complete(new TagResult(true, npcId));
                }))
                .exceptionally(error -> {
                    result.completeExceptionally(error);
                    return null;
                });
        return result;
    }

    /** Retire l'identifiant de l'entité. Un id auto-généré n'est jamais réutilisé après un untag. */
    public CompletableFuture<Boolean> untag(Entity entity) {
        if (citizensBridge != null) {
            Optional<CitizensNpcBridge.Ref> ref = citizensBridge.resolve(entity);
            if (ref.isPresent()) {
                UUID citizensUuid = ref.get().citizensUuid();
                if (citizensCache.remove(citizensUuid) == null) {
                    return CompletableFuture.completedFuture(false);
                }
                return citizensBindingRepository.delete(citizensUuid).thenApply(ignored -> true);
            }
        }
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        if (!pdc.has(idKey, PersistentDataType.STRING)) {
            return CompletableFuture.completedFuture(false);
        }
        pdc.remove(idKey);
        return CompletableFuture.completedFuture(true);
    }

    private void runOnMainThread(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    /** @param created {@code false} si l'entité était déjà marquée (id existant renvoyé tel quel). */
    public record TagResult(boolean created, String npcId) {
    }
}
