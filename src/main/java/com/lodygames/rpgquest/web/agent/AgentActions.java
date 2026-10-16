package com.lodygames.rpgquest.web.agent;

import com.lodygames.rpgquest.dialogue.DialogueDefinitionEditor;
import com.lodygames.rpgquest.player.PlayerResetService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Façade <strong>métier</strong> de l'agent PlugAdmin (issue #51 / outillage Control Panel) : toutes
 * les opérations que les actions whitelistées ({@link AgentActionType}) sont autorisées à déclencher,
 * exprimées en types simples (String / int / boolean / listes) — <strong>aucun type Bukkit</strong>,
 * pour que {@link AgentActionExecutor} reste testable sans serveur.
 *
 * <p>Chaque méthode réutilise un service métier existant du plugin (moteur de quête, service de
 * story, registre d'objets, service de reset joueur) — jamais une commande texte {@code /rpgadmin …},
 * jamais un {@code dispatchCommand}, jamais une écriture SQL directe. L'implémentation réelle est
 * {@link BukkitAgentActions} ; les tests fournissent une fausse façade.</p>
 *
 * <p>Discipline de résultat : une cible/donnée introuvable ou une précondition non remplie (joueur
 * hors ligne pour une mutation qui l'exige, id inconnu) est un échec <em>lisible</em>
 * ({@code ok=false} + message), jamais une exception qui remonte.</p>
 */
public interface AgentActions {

    // ---- Lectures -------------------------------------------------------------------------------

    /** Joueur connecté : identité + localisation grossière. */
    record PlayerSummary(String uuid, String name, String world, int x, int y, int z) {
    }

    CompletableFuture<List<PlayerSummary>> onlinePlayers();

    /**
     * Un joueur de l'annuaire (action {@code player.catalog}, issue #96) : <strong>tout</strong>
     * joueur déjà venu au moins une fois (données serveur Paper {@code OfflinePlayer}) + les
     * connectés. {@code uuid} est l'identité stable ; {@code name} le dernier pseudo connu
     * ({@code null} si Paper ne le connaît pas). Les instants sont en millisecondes epoch
     * ({@code null} si indisponibles). {@code world}/{@code x}/{@code y}/{@code z} ne sont
     * renseignés que pour un joueur en ligne.
     */
    record PlayerCatalogEntry(String uuid, String name, boolean online, boolean hasPlayedBefore,
                              Long firstPlayed, Long lastSeen, boolean banned, String banReason,
                              String world, Integer x, Integer y, Integer z,
                              boolean op, boolean whitelisted) {
    }

    /**
     * Annuaire complet des joueurs (en ligne + hors ligne ayant déjà rejoint). Tri / filtre /
     * pagination sont faits côté PlugAdmin ; l'agent renvoie tout, borné par {@code limit} (0 =
     * pas de borne) pour éviter un payload démesuré.
     */
    CompletableFuture<List<PlayerCatalogEntry>> playerCatalog(int limit);

    /**
     * Définition de quête présentée pour l'admin : titre lisible d'abord, id technique en second,
     * étapes/objectifs décrits en clair, récompenses résumées.
     *
     * <p>Protocole {@code quest.list} (#78) : les champs <em>legacy</em> {@code rewards} (chaînes
     * déjà formatées) et {@link QuestStepSummary#objectives()} restent présents pour l'agent
     * VeryGames déjà déployé ; les champs structurés {@code rewardDetails} /
     * {@link QuestStepSummary#objectiveDetails()} sont la source à privilégier côté panel.</p>
     *
     * <p>{@code giverId} / {@code giverName} (#75) : PNJ donneur, {@code null} si la quête n'en
     * déclare pas ({@code giver:} optionnel dans le YAML).</p>
     */
    record QuestSummary(String id, String title, String category, boolean repeatable,
                        List<String> prerequisites, List<QuestStepSummary> steps, List<String> rewards,
                        List<RewardSummary> rewardDetails, String giverId, String giverName,
                        long cooldownSeconds) {

        /** Sans délai de ré-acceptation (constructeur historique). */
        QuestSummary(String id, String title, String category, boolean repeatable, List<String> prerequisites,
                     List<QuestStepSummary> steps, List<String> rewards, List<RewardSummary> rewardDetails,
                     String giverId, String giverName) {
            this(id, title, category, repeatable, prerequisites, steps, rewards, rewardDetails, giverId, giverName, 0);
        }
    }

    record QuestStepSummary(String id, List<String> objectives, List<ObjectiveSummary> objectiveDetails) {
    }

    /**
     * Objectif d'étape <strong>structuré</strong> (#78) : le panel détermine type / cible /
     * quantité / identifiant technique sans regex.
     *
     * @param kind        nom de {@link com.lodygames.rpgquest.quest.model.ObjectiveType}
     *                    ({@code KILL_ENTITY}, {@code COLLECT_ITEM}, {@code CRAFT_ITEM},
     *                    {@code BREAK_BLOCK}, {@code PLACE_BLOCK}, {@code TALK_TO_NPC},
     *                    {@code REACH_LOCATION})
     * @param target      jeton technique : entité, matériau, id de PNJ, ou nom de monde
     * @param amount      quantité requise (1 pour les objectifs binaires)
     * @param raw         description héritée ({@code "Tuer SPIDER (x5)"}), conservée pour debug
     * @param npc         PNJ destinataire d'une remise {@code DELIVER_ITEM_TO_NPC} (issue #123),
     *                    {@code null} pour tous les autres types. Champ distinct de {@code target}
     *                    parce qu'une remise a DEUX cibles : l'objet compté et le PNJ qui le reçoit
     *                    — les écraser dans un seul champ rendrait le libellé du panel faux.
     * @param worlds      mondes où un {@code DISCOVER_WAYPOINT} compte (issue #185) ; liste vide =
     *                    tous les mondes, et jamais {@code null}
     * @param countMode   {@code NEW_ONLY} ou {@code INCLUDE_EXISTING} pour un
     *                    {@code DISCOVER_WAYPOINT} (issue #185), {@code null} pour les autres types
     */
    record ObjectiveSummary(String kind, String target, int amount, String raw, String npc,
                            List<String> worlds, String countMode) {

        public ObjectiveSummary {
            worlds = worlds == null ? List.of() : List.copyOf(worlds);
        }

        /** Objectif sans PNJ destinataire : tous les types sauf {@code DELIVER_ITEM_TO_NPC}. */
        ObjectiveSummary(String kind, String target, int amount, String raw) {
            this(kind, target, amount, raw, null, List.of(), null);
        }

        /**
         * Issue #123 : avec PNJ destinataire. {@code worlds}/{@code countMode} (issue #185) ne
         * concernent que {@code DISCOVER_WAYPOINT}.
         */
        ObjectiveSummary(String kind, String target, int amount, String raw, String npc) {
            this(kind, target, amount, raw, npc, List.of(), null);
        }
    }

    /**
     * Récompense de quête <strong>structurée</strong> (#78).
     *
     * @param kind     nom de {@link com.lodygames.rpgquest.quest.model.RewardType}
     *                 ({@code EXPERIENCE}, {@code ITEM}, {@code VARIABLE}, {@code COMMAND})
     * @param amount   quantité : XP pour {@code EXPERIENCE}, nombre d'objets pour {@code ITEM},
     *                 {@code 0} sinon
     * @param target   matériau pour {@code ITEM}, clé pour {@code VARIABLE}, {@code null} sinon
     * @param value    valeur pour {@code VARIABLE}, {@code null} sinon
     * @param command  commande console <strong>complète, jamais tronquée</strong> pour
     *                 {@code COMMAND}, {@code null} sinon
     * @param raw      description héritée, conservée pour debug
     */
    record RewardSummary(String kind, int amount, String target, String value, String command, String raw) {
    }

    List<QuestSummary> questDefinitions();

    /** État d'une quête pour un joueur : {@code NOT_STARTED} / {@code ACTIVE} / {@code COMPLETED} / … */
    record QuestPlayerState(String questId, String title, String state,
                            String currentStepId, List<ObjectiveState> objectives) {
    }

    /**
     * Progression d'un objectif de l'étape courante d'un joueur ({@code quest.player.status}).
     *
     * <p>Issue #79 : {@code detail} porte le type / la cible / la quantité structurés (même forme
     * que {@code quest.list}, #78) pour que le panel n'ait plus à reparser {@code description}.
     * {@code description} reste émis pendant la transition, pour un panel non migré.</p>
     *
     * @param detail objectif structuré, {@code null} si la définition n'a pas pu être rapprochée
     *               de la progression (quête rechargée entre-temps) — le panel retombe alors sur
     *               {@code description}
     */
    record ObjectiveState(String description, int current, int required, ObjectiveSummary detail) {

        /** Sans structure (constructeur historique). */
        ObjectiveState(String description, int current, int required) {
            this(description, current, required, null);
        }

        /** {@code COMPLETED} / {@code IN_PROGRESS} / {@code NOT_STARTED}, déduit des compteurs. */
        String state() {
            if (current >= required) {
                return "COMPLETED";
            }
            return current > 0 ? "IN_PROGRESS" : "NOT_STARTED";
        }
    }

    CompletableFuture<List<QuestPlayerState>> questStatus(UUID playerId);

    /** Définition de story : titre lisible + liste ordonnée des quêtes qui la composent. */
    record StorySummary(String id, String title, List<String> stepQuestIds) {
    }

    List<StorySummary> storyDefinitions();

    record StoryPlayerState(String storyId, String title, String state,
                            int currentStep, int totalSteps, String currentQuestId) {
    }

    CompletableFuture<List<StoryPlayerState>> storyStatus(UUID playerId);

    /** Objet personnalisé RPGQuest disponible au GIVE : nom lisible + id technique. */
    record ItemSummary(String id, String displayName, String type) {
    }

    List<ItemSummary> itemDefinitions();

    /**
     * Résultat d'un export {@code content.export} (issue #108) : un pack {@code lodyquests-content-pack}
     * sérialisé en YAML déterministe, plus des métadonnées non sensibles.
     *
     * @param ok            {@code false} = famille inconnue / erreur de construction (message lisible)
     * @param message       message humain (surtout utile si {@code !ok})
     * @param format        {@code lodyquests-content-pack}
     * @param schemaVersion version de schéma (1 pour cette phase)
     * @param family        {@code all} ou la famille exportée
     * @param counts        nombre d'éléments par famille (clés triées)
     * @param elements      total d'éléments exportés
     * @param yaml          le pack sérialisé — <strong>aucune donnée joueur / runtime / secret</strong>
     */
    record ContentExportResult(boolean ok, String message, String format, int schemaVersion, String family,
                               java.util.Map<String, Integer> counts, int elements, String yaml) {
        static ContentExportResult failure(String message) {
            return new ContentExportResult(false, message, null, 0, null, java.util.Map.of(), 0, null);
        }
    }

    /**
     * Construit un content pack (issue #108). {@code family} vaut {@code all} ou une clé de famille
     * ({@code quests|stories|dialogues|npcs}) ; {@code ids} restreint à une sélection (vide = toute
     * la famille ; ignoré si {@code family=all}). Lecture seule : lit l'état en mémoire des moteurs,
     * aucun accès disque, aucun effet de bord.
     */
    ContentExportResult exportContent(String family, List<String> ids);

    /**
     * Catalogue PNJ (action {@code npc.list}) — <strong>lecture seule</strong>. Distingue la
     * <strong>définition logique</strong> RPGQuest ({@code npcs/*.yml}, indépendante de Citizens et
     * du monde) du <strong>binding physique Citizens</strong> éventuel. Voir
     * {@link com.lodygames.rpgquest.npc.NpcCatalog} pour la dérivation (aucun accès au monde :
     * position/monde et PNJ Citizens non tagués sont hors périmètre).
     */
    record NpcSummary(String id, String displayName, boolean logicalDefinitionPresent,
                      boolean citizensBindingPresent, Integer citizensNumericId, int bindingCount,
                      boolean enabled, String description, String role, String definedDialogueId,
                      boolean hasDialogue, String dialogueId, int dialogueNodes, int dialogueChoices,
                      List<String> dialogueStartsQuests, List<String> questsGiven,
                      List<String> questsReferenced, List<String> questsDelivering,
                      List<String> sources, String state, List<NpcWarning> warnings) {
    }

    /** Anomalie de configuration d'un PNJ. {@code severity} ∈ {@code error|warning|info}. */
    record NpcWarning(String code, String severity, String message) {
    }

    // ---- Publication de contenu vers DEV (issue #47) -------------------------------------------

    /** Une ressource présente dans un dossier de contenu du serveur. */
    record DevContentFile(String kind, String slug, String sha256, long bytes) {
    }

    /**
     * Vue de {@code content.dev.state} : pour chaque famille publiable, les fichiers présents et
     * les identifiants <strong>réellement chargés</strong> par le moteur.
     *
     * <p>Les deux sont nécessaires et ne disent pas la même chose : un fichier peut être présent
     * sans être chargé (écrit mais jamais rechargé), et un identifiant peut être chargé alors que
     * son fichier a été modifié depuis. C'est cette différence qui produit les états « différent »
     * et « conflit ».</p>
     */
    record DevContentStateView(List<DevContentFile> files, Map<String, List<String>> runtimeIds,
                               String runtimeHash) {
    }

    /**
     * Compte rendu d'une publication ou d'un retour arrière.
     *
     * @param runtimeConfirmed le moteur voit-il réellement la ressource ? <strong>Seul</strong> ce
     *                         champ autorise le badge « Synchronisé » — ni la copie du fichier, ni
     *                         l'envoi de l'action, ni la demande de rechargement ne le valent
     */
    record ContentPublishResultView(boolean ok, String code, String message,
                                    String kind, String slug, String expectedId,
                                    String devShaBefore, String devShaAfter, String sourceSha,
                                    boolean created, String backupPath,
                                    boolean reloadApplied, String reloadCode, String reloadMessage,
                                    int loadedCount, int issueCount,
                                    boolean runtimeConfirmed, String runtimeHash,
                                    String verifiedAt, List<String> referenceErrors,
                                    List<String> preexistingReferenceErrors, List<String> reloadedFamilies,
                                    List<String> autoIncludedFamilies, List<String> requiredFamilies) {

        public ContentPublishResultView {
            referenceErrors = referenceErrors == null ? List.of() : List.copyOf(referenceErrors);
            preexistingReferenceErrors = preexistingReferenceErrors == null ? List.of()
                    : List.copyOf(preexistingReferenceErrors);
            reloadedFamilies = reloadedFamilies == null ? List.of() : List.copyOf(reloadedFamilies);
            autoIncludedFamilies = autoIncludedFamilies == null ? List.of() : List.copyOf(autoIncludedFamilies);
            requiredFamilies = requiredFamilies == null ? List.of() : List.copyOf(requiredFamilies);
        }

        /** Compte rendu sans détail de dépendances (refus amont, doubles de test). */
        public ContentPublishResultView(boolean ok, String code, String message, String kind, String slug,
                                        String expectedId, String devShaBefore, String devShaAfter,
                                        String sourceSha, boolean created, String backupPath,
                                        boolean reloadApplied, String reloadCode, String reloadMessage,
                                        int loadedCount, int issueCount, boolean runtimeConfirmed,
                                        String runtimeHash, String verifiedAt) {
            this(ok, code, message, kind, slug, expectedId, devShaBefore, devShaAfter, sourceSha, created,
                    backupPath, reloadApplied, reloadCode, reloadMessage, loadedCount, issueCount,
                    runtimeConfirmed, runtimeHash, verifiedAt, List.of(), List.of(), List.of(), List.of(),
                    List.of());
        }
    }

    /**
     * Le contenu d'un fichier de DEV.
     *
     * @param present le fichier existe-t-il ? Distinct d'un texte vide, qui serait un fichier vide
     * @param tooLarge le fichier existe mais dépasse la borne de lecture
     */
    record DevContentFileText(String kind, String slug, boolean present, boolean tooLarge,
                              String sha256, String text) {
    }

    /** Lit un seul fichier de DEV, pour pouvoir montrer la différence. Lecture seule. */
    CompletableFuture<DevContentFileText> contentDevRead(String kind, String slug);

    /** L'état DEV du contenu publiable. Lecture seule, n'écrit rien. */
    CompletableFuture<DevContentStateView> contentDevState();

    /**
     * Publie une ressource puis vérifie le runtime.
     *
     * @param expectedDevSha empreinte DEV attendue ({@code ""} = absente) — un désaccord est un
     *                       conflit, jamais un écrasement silencieux
     */
    CompletableFuture<ContentPublishResultView> contentPublish(String kind, String slug,
                                                               String yaml, String expectedDevSha,
                                                               String expectedId);

    /** Une ressource d'un lot à publier (issue #427). */
    record ContentPublishBatchItem(String kind, String slug, String yaml, String expectedDevSha,
                                   String expectedId) {
    }

    /** Le sort d'une ressource d'un lot, avec ce que le moteur lui reproche. */
    record ContentPublishBatchItemView(String kind, String slug, String expectedId, String status,
                                       boolean created, String devShaBefore, String devShaAfter,
                                       String sourceSha, String backupPath,
                                       boolean runtimeConfirmed, List<String> problems) {
        public ContentPublishBatchItemView {
            problems = problems == null ? List.of() : List.copyOf(problems);
        }
    }

    /** Compte rendu d'un lot : tout en jeu, ou rien — et pourquoi. */
    record ContentPublishBatchView(boolean ok, String code, String message, boolean dryRun,
                                   boolean filesRestored, List<ContentPublishBatchItemView> items,
                                   List<String> otherProblems, List<String> families,
                                   List<String> autoIncludedFamilies, String reloadCode,
                                   int loadedCount, String runtimeHash, String verifiedAt) {
        public ContentPublishBatchView {
            items = items == null ? List.of() : List.copyOf(items);
            otherProblems = otherProblems == null ? List.of() : List.copyOf(otherProblems);
            families = families == null ? List.of() : List.copyOf(families);
            autoIncludedFamilies = autoIncludedFamilies == null ? List.of()
                    : List.copyOf(autoIncludedFamilies);
        }
    }

    /**
     * Publie un lot en une seule opération (issue #427).
     *
     * @param dryRun vérification seule : rien ne reste écrit, rien n'est rechargé
     */
    CompletableFuture<ContentPublishBatchView> contentPublishBatch(
            List<ContentPublishBatchItem> items, boolean dryRun);

    /** Défait une publication : restauration si sauvegarde, retrait si la ressource était neuve. */
    CompletableFuture<ContentPublishResultView> contentPublishRollback(String kind, String slug,
                                                                       String backupPath,
                                                                       String expectedId);

    // ---- Emplacements de construction (issue #213) ---------------------------------------------

    /**
     * Un emplacement de construction, tel que {@code building.site.list} le renvoie.
     *
     * <p>Projection en types simples de {@code com.lodygames.rpgquest.building.model.BuildingSite} :
     * {@code facing} et {@code status} voyagent en texte, parce que le Control Panel ne partage
     * aucune énumération avec le plugin.</p>
     *
     * @param worldLoaded le monde est-il réellement chargé sur le serveur en ce moment ? Un
     *                    emplacement dans un monde déchargé reste parfaitement valide — il faut
     *                    simplement le dire plutôt que de laisser croire qu'il est inaccessible
     */
    record BuildingSiteSummary(String id, String name, String description, String aiBrief,
                               String world, int x, int y, int z, String facing, String status,
                               String createdBy, String createdAt, boolean worldLoaded) {
    }

    /**
     * Vue complète de {@code building.site.list} : les emplacements + les mondes qui en portent au
     * moins un (source du filtre du Control Panel, jamais recalculée côté panel).
     */
    record BuildingSiteCatalogView(List<BuildingSiteSummary> sites, List<String> worlds, int total,
                                   List<BuildingPlacementSummary> placements) {
    }

    // ---- Bibliothèque et placement (issue #213, lot « placement ») ------------------------------

    /**
     * Un bâtiment de la bibliothèque.
     *
     * <p>{@code schematicPresent} est séparé du reste pour une raison : une définition dont le
     * fichier manque reste du contenu <strong>correct</strong>, c'est l'artefact qui est absent. La
     * montrer en le disant explique pourquoi la pose est refusée ; la cacher laisserait une
     * bibliothèque vide sans explication.</p>
     */
    record BuildingDefinitionSummary(String id, String name, String description,
                                     int sizeX, int sizeY, int sizeZ,
                                     int anchorX, int anchorY, int anchorZ,
                                     String front, List<String> materials,
                                     String schematic, boolean schematicPresent, int version,
                                     boolean bundled) {

        /** Forme antérieure à #284 : sans l'indication « exemple embarqué ». */
        public BuildingDefinitionSummary(String id, String name, String description, int sizeX,
                                         int sizeY, int sizeZ, int anchorX, int anchorY, int anchorZ,
                                         String front, List<String> materials, String schematic,
                                         boolean schematicPresent, int version) {
            this(id, name, description, sizeX, sizeY, sizeZ, anchorX, anchorY, anchorZ, front,
                    materials, schematic, schematicPresent, version, false);
        }
    }

    /**
     * Vue de {@code building.definition.list}.
     *
     * @param problems       les fichiers refusés au dernier chargement, nommés
     * @param engineAvailable le moteur de schematics est-il exploitable ? Si non, la bibliothèque
     *                        reste consultable mais rien ne peut être posé — et {@code engineReason}
     *                        le dit
     */
    record BuildingLibraryView(List<BuildingDefinitionSummary> buildings, List<String> problems,
                               boolean engineAvailable, String engineReason) {
    }

    /**
     * Un bâtiment réellement posé.
     *
     * @param restorable       une sauvegarde du terrain existe-t-elle ? Sans elle, la libération est
     *                         refusée plutôt que tentée à l'aveugle
     * @param buildingVersion  version déclarée au moment de la pose (issue #234)
     * @param schematicSha     empreinte du fichier réellement collé, {@code ""} si inconnue
     * @param libraryVersion   version actuellement déclarée dans la bibliothèque
     * @param librarySha       empreinte actuelle du fichier de la bibliothèque
     * @param outdated         la définition a-t-elle changé depuis la pose ? Le bâtiment posé n'est
     *                         JAMAIS modifié d'office — c'est une information, pas une action
     * @param desiredRotation  la rotation qu'aurait le bâtiment selon l'orientation ACTUELLE de
     *                         l'emplacement, {@code -1} si incalculable
     * @param diverges         l'orientation souhaitée de l'emplacement diffère-t-elle de celle du
     *                         bâtiment posé ? Un emplacement est une intention, un placement est un
     *                         fait : changer l'intention ne déplace aucun bloc
     * @param restoreSource    d'où viendrait le terrain si on libérait maintenant, en clair
     */
    record BuildingPlacementSummary(String siteId, String buildingId, String buildingName,
                                    String world, int anchorX, int anchorY, int anchorZ,
                                    int rotation,
                                    int minX, int minY, int minZ,
                                    int maxX, int maxY, int maxZ,
                                    String placedBy, String placedAt, boolean restorable,
                                    int buildingVersion, String schematicSha,
                                    int libraryVersion, String librarySha, boolean outdated,
                                    int desiredRotation, boolean diverges,
                                    String restoreSource) {
    }

    /**
     * Vue d'un aperçu de réorientation ou de remplacement (issue #234).
     *
     * @param operation {@code ROTATE} ou {@code REPLACE} — le moteur exécute la même séquence, mais
     *                  l'écran et le journal ne racontent pas la même histoire
     * @param token     empreinte de tout ce dont dépend l'opération. Elle voyage avec le formulaire
     *                  et est revérifiée à la confirmation : un aperçu périmé est refusé au lieu
     *                  d'être appliqué
     */
    record BuildingRetargetView(boolean applicable, String operation,
                                String siteId, String siteName, String siteFacing,
                                String currentBuildingId, String currentBuildingName,
                                int currentRotation, String currentFootprint,
                                long currentBlockCount,
                                String targetBuildingId, String targetBuildingName,
                                int targetRotation, String targetFootprint,
                                long targetBlockCount,
                                int targetSizeX, int targetSizeY, int targetSizeZ,
                                long targetNonAirBlocks, boolean overlapping,
                                String restoreSource, boolean restorable,
                                List<String> refusals, List<String> warnings, String token) {
    }

    /** Une ligne du journal des opérations d'un emplacement (issue #234). */
    record BuildingHistoryLine(String operation, String operationLabel,
                               String buildingId, int buildingVersion, String shortSha,
                               int rotation, String footprint,
                               String actor, String at, boolean ok, String detail) {
    }

    /**
     * Vue de {@code building.placement.history} : les dernières opérations, de la plus récente à la
     * plus ancienne. Les <strong>échecs y figurent</strong> — un journal muet au moment du problème
     * ne sert à rien.
     */
    record BuildingHistoryView(String siteId, List<BuildingHistoryLine> lines) {
    }

    /**
     * Vue de {@code building.placement.preview} : ce que donnerait la pose, sans l'avoir faite.
     *
     * @param nonAirBlocks nombre de blocs non-air déjà présents, ou {@code -1} si le comptage n'a
     *                     pas pu être fait (monde ou chunk déchargé) — ce qui n'est pas zéro
     * @param refusals     <strong>tous</strong> les motifs de refus, pas seulement le premier
     * @param warnings     ce qui mérite d'être lu mais n'empêche pas de poser
     */
    record BuildingPreviewView(boolean placeable,
                               String siteId, String siteName, String siteFacing,
                               String world, int anchorX, int anchorY, int anchorZ,
                               String buildingId, String buildingName,
                               int sizeX, int sizeY, int sizeZ, String front,
                               int rotation,
                               int minX, int minY, int minZ,
                               int maxX, int maxY, int maxZ,
                               long blockCount, long nonAirBlocks,
                               List<String> refusals, List<String> warnings) {
    }

    /** La bibliothèque de bâtiments. Lecture seule. */
    CompletableFuture<BuildingLibraryView> buildingLibrary();

    /**
     * Calcule rotation et emprise. <strong>Aucune écriture</strong> : ni bloc, ni ligne en base.
     *
     * <p>Un aperçu n'est pas une réservation — tout est revérifié au moment de poser, parce que le
     * monde a pu changer entre l'écran et le clic.</p>
     */
    CompletableFuture<BuildingPreviewView> buildingPlacementPreview(String siteId,
                                                                    String buildingId);

    /**
     * Pose le bâtiment, après avoir sauvegardé la zone écrasée.
     *
     * <p>Un échec de collage laisse l'emplacement vide et n'inscrit aucun placement : il n'y a pas
     * de faux placement possible.</p>
     */
    CompletableFuture<MutationResult> buildingPlacementPlace(String siteId, String buildingId,
                                                             String placedBy);

    /**
     * Rend son terrain d'origine à l'emplacement et le libère (issue #234).
     *
     * <p>Refusé si aucune sauvegarde n'est associée : c'est la seule réponse honnête, puisque
     * remettre de l'air détruirait le terrain d'origine. Refusé aussi — et l'emplacement reste
     * {@code OCCUPIED} — si la restauration échoue : il n'y a jamais de faux {@code EMPTY}.</p>
     *
     * <p>Depuis #234, la source est la <strong>baseline originale</strong> quand elle existe, et non
     * la sauvegarde de la dernière opération. Pour un emplacement dont la pose est la seule
     * opération — le seul cas qui pouvait exister avant ce lot — les deux coïncident, donc le
     * comportement est inchangé pour tout ce qui est déjà en production.</p>
     */
    CompletableFuture<MutationResult> buildingPlacementRollback(String siteId);

    /**
     * Ce que donnerait une réorientation ou un remplacement. <strong>Aucune écriture.</strong>
     *
     * @param buildingId le bâtiment visé ; le bâtiment actuel pour une simple réorientation
     * @param facing     l'orientation souhaitée, {@code NORTH}/{@code EAST}/{@code SOUTH}/{@code WEST}
     */
    CompletableFuture<BuildingRetargetView> buildingRetargetPreview(String siteId,
                                                                    String buildingId,
                                                                    String facing);

    /**
     * Réoriente le bâtiment posé, en repartant de la définition et du terrain d'origine.
     *
     * <p><strong>Ce n'est pas une rotation des blocs en place.</strong> Le monde est ramené à son
     * terrain d'origine, puis le bâtiment est recollé dans la nouvelle orientation — stratégie
     * déterministe, qui donne le même résultat qu'une pose initiale dans cette orientation.</p>
     */
    CompletableFuture<MutationResult> buildingReorient(String siteId, String facing, String actor,
                                                        String token);

    /** Remplace le bâtiment posé par un autre, dans l'orientation demandée. */
    CompletableFuture<MutationResult> buildingReplace(String siteId, String buildingId,
                                                       String facing, String actor, String token);

    /** Le journal des opérations d'un emplacement. Lecture seule. */
    CompletableFuture<BuildingHistoryView> buildingHistory(String siteId);

    /**
     * Issue #246 — aperçu de la capture d'une construction posée. <strong>Aucune écriture.</strong>
     * Par défaut indisponible : seule la façade Bukkit sait lire le monde.
     */
    default CompletableFuture<BuildingCaptureView> buildingCapturePreview(BuildingCaptureRequest request) {
        return CompletableFuture.completedFuture(new BuildingCaptureView(false, Map.of(),
                List.of("Capture indisponible sur ce serveur."), List.of(), ""));
    }

    // ---- Villes (issue #178) ------------------------------------------------------------------

    /**
     * Une ville du relevé : fiche, limites calculées du carré (coins inclus), nombre de joueurs
     * l'ayant découverte et identifiants des villes dont le carré la chevauche.
     */
    record CitySummary(String id, String name, String world, double x, double y, double z,
                       int centerX, int centerZ, int distance, boolean active, String createdAt,
                       boolean worldLoaded, int minX, int maxX, int minZ, int maxZ, int width,
                       int discoveries, List<String> overlaps, CityAnchorsView anchors) {
    }

    /**
     * Issue #322 — PNJ central (identité, état relu en direct) et point d'arrivée de voyage.
     * {@code npcX/npcY/npcZ} et {@code arrival*} sont {@code null} quand ils n'existent pas.
     */
    record CityAnchorsView(String centralNpcId, String npcDisplayName, String npcStatus, String npcSeverity,
                           String npcMessage, String npcWorld, Double npcX, Double npcY, Double npcZ,
                           boolean npcLive, int npcDistance, String arrivalWorld, Double arrivalX,
                           Double arrivalY, Double arrivalZ, Float arrivalYaw, Float arrivalPitch,
                           String arrivalStatus, String arrivalSeverity, String arrivalMessage) {
    }

    /** Un déplacement de centre armé, en attente du clic d'outil du joueur. */
    record CityPendingMove(String player, String cityId, String requestedBy, String expiresAt) {
    }

    record CityCatalogView(boolean available, List<CitySummary> cities, List<CityPendingMove> pendingMoves,
                           int minDistance, int maxDistance, int defaultDistance) {
    }

    default CompletableFuture<CityCatalogView> cities() {
        return CompletableFuture.completedFuture(new CityCatalogView(false, List.of(), List.of(), 0, 0, 0));
    }

    default CompletableFuture<MutationResult> cityRename(String id, String name) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNSUPPORTED", "Villes indisponibles."));
    }

    default CompletableFuture<MutationResult> cityResize(String id, int distance) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNSUPPORTED", "Villes indisponibles."));
    }

    default CompletableFuture<MutationResult> citySetActive(String id, boolean active) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNSUPPORTED", "Villes indisponibles."));
    }

    default CompletableFuture<MutationResult> cityArmMove(String id, String playerName, String requestedBy) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNSUPPORTED", "Villes indisponibles."));
    }

    default CompletableFuture<MutationResult> cityShowPerimeter(String id, String playerName) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNSUPPORTED", "Villes indisponibles."));
    }

    default CompletableFuture<MutationResult> cityDelete(String id) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNSUPPORTED", "Villes indisponibles."));
    }

    /** Issue #322 — associe un PNJ central par son npc_id, ou le retire ({@code npcId} vide). */
    default CompletableFuture<MutationResult> cityLinkNpc(String id, String npcId) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNSUPPORTED", "Villes indisponibles."));
    }

    /** Issue #322 — l'arrivée de voyage devient la position actuelle (et l'orientation) d'un joueur connecté. */
    default CompletableFuture<MutationResult> citySetArrival(String id, String playerName) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNSUPPORTED", "Villes indisponibles."));
    }

    /** Issue #322 — retour à l'arrivée au centre. */
    default CompletableFuture<MutationResult> cityClearArrival(String id) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNSUPPORTED", "Villes indisponibles."));
    }

    // ---- Emplacements de PNJ (issue #381) --------------------------------------------------------

    /**
     * Un emplacement de PNJ tel que le panel l'affiche. {@code status} est l'état CONSTATÉ
     * ({@code FREE}, {@code OCCUPIED}, {@code INACCESSIBLE}, {@code CONFLICT}), jamais stocké.
     * {@code npcId}, {@code npcName} et {@code citizensId} sont {@code null} / {@code -1} quand
     * l'emplacement est libre ou le PNJ absent du monde. {@code notes} : dépendances à connaître
     * (ville dont ce PNJ est le PNJ central, emplacement dans une parcelle…), jamais bloquantes ici.
     */
    record NpcSpotSummary(String id, String name, String world, double x, double y, double z, float yaw,
                          float pitch, int radius, String npcId, String npcName, int citizensId,
                          String status, String statusMessage, String createdBy, String createdAt,
                          String updatedAt, List<String> notes) {
    }

    /**
     * {@code npcs} : PNJ logiques plaçables (id → nom affiché), pour les listes de choix du panel.
     * Les PNJ récurrents (#13) en sont exclus : leur position appartient à leur occurrence.
     */
    record NpcSpotCatalogView(boolean available, List<NpcSpotSummary> spots, List<String> worlds,
                              Map<String, String> npcs, long generatedAtEpochMs) {
    }

    default CompletableFuture<NpcSpotCatalogView> npcSpots() {
        return CompletableFuture.completedFuture(
                new NpcSpotCatalogView(false, List.of(), List.of(), Map.of(), System.currentTimeMillis()));
    }

    default CompletableFuture<MutationResult> npcSpotRename(String id, String name) {
        return npcSpotUnavailable();
    }

    /** Corrige l'orientation d'un emplacement ; si un PNJ l'occupe, il se tourne aussi. */
    default CompletableFuture<MutationResult> npcSpotOrient(String id, float yaw, float pitch) {
        return npcSpotUnavailable();
    }

    /** Libère l'emplacement sans toucher au PNJ physique. */
    default CompletableFuture<MutationResult> npcSpotRelease(String id) {
        return npcSpotUnavailable();
    }

    /** Supprime un emplacement libre ; refusé s'il est occupé. */
    default CompletableFuture<MutationResult> npcSpotDelete(String id) {
        return npcSpotUnavailable();
    }

    /**
     * Place un PNJ sur un emplacement : le déplace s'il existe déjà en jeu (même entité Citizens,
     * jamais un doublon), le fait apparaître sinon. Réservation, puis geste physique, puis
     * compensation de la réservation si le geste échoue.
     */
    default CompletableFuture<MutationResult> npcSpotPlace(String spotId, String npcId) {
        return npcSpotUnavailable();
    }

    /** Réserve un emplacement à la position actuelle d'un PNJ, qui en devient l'occupant. */
    default CompletableFuture<MutationResult> npcSpotCapture(String npcId, String name, String actor) {
        return npcSpotUnavailable();
    }

    private static CompletableFuture<MutationResult> npcSpotUnavailable() {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNAVAILABLE",
                "Emplacements de PNJ indisponibles sur ce serveur (plugin trop ancien ?)."));
    }

    /** Issue #246 — capture la construction posée comme nouveau bâtiment de bibliothèque. */
    default CompletableFuture<MutationResult> buildingCapture(BuildingCaptureRequest request,
                                                              String actor, String token) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNAVAILABLE",
                "Capture indisponible sur ce serveur."));
    }

    CompletableFuture<BuildingSiteCatalogView> buildingSites();

    /**
     * Renomme un emplacement — libellé humain uniquement. L'identifiant, la position et
     * l'orientation ne bougent pas.
     */
    CompletableFuture<MutationResult> buildingSiteRename(String id, String name);

    /** Remplace la note libre d'un emplacement. Une description vide est une valeur valide. */
    CompletableFuture<MutationResult> buildingSiteDescribe(String id, String description);

    /**
     * Issue #94 — remplace le <strong>brief architectural</strong> d'un emplacement.
     *
     * <p>Distinct de {@link #buildingSiteDescribe} : la note d'administrateur sert à se repérer
     * dans une liste, le brief sert à générer un bâtiment. Un brief vide est une valeur valide.
     * Rien n'est jamais tronqué : au-delà de la borne, l'action est refusée en le disant.</p>
     */
    CompletableFuture<MutationResult> buildingSiteBrief(String id, String brief);

    /**
     * Issue #94 — génère un bâtiment à partir d'un <strong>plan déclaratif</strong>.
     *
     * <p>Le plan arrive en YAML, tel que l'IA l'a écrit et que le panel l'a validé en forme. Il est
     * <strong>revalidé côté serveur</strong> — le panel n'est pas une source de confiance — puis
     * expansé par un moteur déterministe, écrit en {@code .schem} et déclaré dans la bibliothèque.
     * Un succès signifie que les trois artefacts existent réellement sur le disque.</p>
     *
     * @param replace autorise l'écrasement d'une définition portant le même identifiant
     */
    CompletableFuture<BuildingGenerationView> buildingGenerate(String planYaml, boolean replace);

    /**
     * #290 — publie une nouvelle version d'un bâtiment existant, à condition que la bibliothèque
     * porte encore la version vue par l'administrateur ({@code expectedVersion}, 0 = sans contrôle).
     */
    default CompletableFuture<BuildingGenerationView> buildingGenerate(String planYaml, boolean replace,
                                                                       int expectedVersion) {
        return buildingGenerate(planYaml, replace);
    }

    /**
     * Ce qu'une génération a réellement produit (issue #94) — jamais ce qui avait été demandé.
     *
     * @param blocks nombre de blocs de matière effectivement posés dans le plan
     */
    record BuildingGenerationView(boolean ok, String code, String message, String buildingId,
                                  String schematic, int sizeX, int sizeY, int sizeZ, int blocks,
                                  int version, boolean replaced, List<String> effects) {
        public BuildingGenerationView {
            effects = List.copyOf(effects == null ? List.of() : effects);
        }
    }

    /**
     * Juge un plan sans produire d'artefact (issue #94, lot F).
     *
     * <p>Le pendant en lecture seule de {@link #buildingGenerate(String, boolean)} : le plan est
     * lu, expansé et audité, et rien n'est écrit. C'est ce qui permet de corriger un plan plusieurs
     * fois de suite sans laisser derrière soi une succession de bâtiments ratés.</p>
     */
    CompletableFuture<BuildingPlanVerdictView> buildingPlanAudit(String planYaml);

    /**
     * Même audit, en donnant au moteur les bornes du projet (issue #282) : la réparation
     * déterministe peut alors agrandir le volume au lieu d'araser un toit. 0 = pas de borne connue.
     */
    default CompletableFuture<BuildingPlanVerdictView> buildingPlanAudit(String planYaml,
                                                                       int maxWidth,
                                                                       int maxHeight) {
        return buildingPlanAudit(planYaml);
    }

    /**
     * Le verdict d'un plan (issue #94, lot F), tel qu'il remonte au panel.
     *
     * <p>Les {@code codes} sont stables et destinés à l'écran et aux tests ; le {@code message} est
     * destiné à l'administrateur <em>et</em> au modèle ; les {@code metrics} portent les chiffres
     * mesurés sur la grille, qui sont l'argument le plus efficace de la boucle de correction — un
     * modèle convaincu d'avoir posé des fenêtres se ravise devant « vitrages : 0 ».</p>
     *
     * @param codes   codes bloquants, vide si le plan passe
     * @param metrics mesures brutes, {@code null} si le plan n'a pas pu être expansé
     */
    record BuildingPlanVerdictView(boolean ok, String code, String message, String buildingId,
                                   String kind, int sizeX, int sizeY, int sizeZ, int blocks,
                                   List<String> codes, List<String> warnings, boolean exists,
                                   Map<String, Object> metrics, Map<String, Object> repair) {
        public BuildingPlanVerdictView {
            codes = List.copyOf(codes == null ? List.of() : codes);
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
            metrics = Map.copyOf(metrics == null ? Map.of() : metrics);
            repair = Map.copyOf(repair == null ? Map.of() : repair);
        }

        public BuildingPlanVerdictView(boolean ok, String code, String message, String buildingId,
                                       String kind, int sizeX, int sizeY, int sizeZ, int blocks,
                                       List<String> codes, List<String> warnings, boolean exists,
                                       Map<String, Object> metrics) {
            this(ok, code, message, buildingId, kind, sizeX, sizeY, sizeZ, blocks, codes, warnings,
                    exists, metrics, Map.of());
        }
    }

    /**
     * Les coupes 2D du bâtiment que ce plan produirait (issue #94, lot E), avec son verdict.
     *
     * <p>N'écrit rien. Les coupes viennent de la même expansion que l'audit : le panel ne doit
     * jamais redessiner un plan lui-même, sous peine de montrer un aperçu qui ne correspond pas à
     * ce que le serveur construira.</p>
     */
    CompletableFuture<BuildingPlanPreviewView> buildingPlanPreview(String planYaml);

    /** Même aperçu, avec les bornes du projet pour la réparation déterministe (issue #282). */
    default CompletableFuture<BuildingPlanPreviewView> buildingPlanPreview(String planYaml,
                                                                         int maxWidth,
                                                                         int maxHeight) {
        return buildingPlanPreview(planYaml);
    }

    /**
     * Une coupe, telle qu'elle remonte au panel.
     *
     * @param axes  ce que parcourent colonnes et lignes, pour légender les bords de la grille
     * @param rows  la grille, une chaîne par ligne, toutes de même longueur
     */
    record BuildingSliceView(String id, String label, String axes, List<String> rows) {
        public BuildingSliceView {
            rows = List.copyOf(rows == null ? List.of() : rows);
        }
    }

    /**
     * L'aperçu complet, avec le verdict qui l'accompagne.
     *
     * <p>Le verdict voyage avec les coupes, et non dans un second appel : un aperçu séparé de ses
     * avertissements laisserait l'administrateur juger à l'œil un bâtiment dont le serveur sait
     * déjà quelque chose.</p>
     */
    record BuildingPlanPreviewView(boolean ok, String code, String message, String buildingId,
                                   String kind, int sizeX, int sizeY, int sizeZ, int blocks,
                                   int anchorX, int anchorY, int anchorZ, String front,
                                   List<String> codes, List<String> warnings,
                                   List<BuildingSliceView> plans,
                                   List<BuildingSliceView> elevations,
                                   BuildingVoxelsView voxels, Map<String, Object> repair) {
        public BuildingPlanPreviewView(boolean ok, String code, String message, String buildingId,
                                       String kind, int sizeX, int sizeY, int sizeZ, int blocks,
                                       int anchorX, int anchorY, int anchorZ, String front,
                                       List<String> codes, List<String> warnings,
                                       List<BuildingSliceView> plans,
                                       List<BuildingSliceView> elevations,
                                       BuildingVoxelsView voxels) {
            this(ok, code, message, buildingId, kind, sizeX, sizeY, sizeZ, blocks, anchorX, anchorY,
                    anchorZ, front, codes, warnings, plans, elevations, voxels, Map.of());
        }

        public BuildingPlanPreviewView(boolean ok, String code, String message, String buildingId,
                                       String kind, int sizeX, int sizeY, int sizeZ, int blocks,
                                       int anchorX, int anchorY, int anchorZ, String front,
                                       List<String> codes, List<String> warnings,
                                       List<BuildingSliceView> plans,
                                       List<BuildingSliceView> elevations) {
            this(ok, code, message, buildingId, kind, sizeX, sizeY, sizeZ, blocks, anchorX, anchorY,
                    anchorZ, front, codes, warnings, plans, elevations, null);
        }

        public BuildingPlanPreviewView {
            code = code == null ? "" : code;
            message = message == null ? "" : message;
            buildingId = buildingId == null ? "" : buildingId;
            kind = kind == null ? "" : kind;
            front = front == null ? "" : front;
            codes = List.copyOf(codes == null ? List.of() : codes);
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
            plans = List.copyOf(plans == null ? List.of() : plans);
            repair = Map.copyOf(repair == null ? Map.of() : repair);
            elevations = List.copyOf(elevations == null ? List.of() : elevations);
            voxels = voxels == null ? BuildingVoxelsView.omitted("") : voxels;
        }
    }

    /**
     * Le bâtiment bloc par bloc, pour la vue en 3D du panel (issue #273) : une palette, puis une
     * couche par niveau, une chaîne par rangée. Vide, avec un motif, quand il n'est pas transmis.
     *
     * @param palette chaque entrée : {@code symbol}, {@code block}, {@code role}, {@code shape}
     */
    record BuildingVoxelsView(List<Map<String, String>> palette, List<List<String>> layers,
                              String omittedReason) {
        public BuildingVoxelsView {
            palette = List.copyOf(palette == null ? List.of() : palette);
            layers = layers == null ? List.of() : layers.stream().map(List::copyOf).toList();
            omittedReason = omittedReason == null ? "" : omittedReason;
        }

        public static BuildingVoxelsView omitted(String reason) {
            return new BuildingVoxelsView(List.of(), List.of(), reason);
        }
    }

    /**
     * Corrige l'orientation cardinale d'un emplacement. <strong>Jamais la position</strong> : se
     * tromper de façade est courant, déplacer un point d'ancrage ne se fait pas depuis un écran.
     */
    CompletableFuture<MutationResult> buildingSiteFacing(String id, String facing);

    /**
     * Supprime le marqueur logique d'un emplacement. <strong>Aucun bloc du monde n'est touché.</strong>
     * Idempotent : un identifiant déjà absent réussit en disant qu'il n'y avait rien.
     */
    CompletableFuture<MutationResult> buildingSiteDelete(String id);

    /**
     * Vue complète renvoyée par {@code npc.list} : catalogue + registre canonique
     * ({@code definedIds} = ids ayant une définition logique ; {@code canonicalIds} = union avec
     * les ids encore seulement référencés, transition #66) + compteurs.
     */
    record NpcCatalogView(List<NpcSummary> npcs, List<String> canonicalIds, List<String> definedIds,
                          boolean citizensAvailable, int total, int withDefinition, int withoutDefinition,
                          int bound, int withWarnings) {
    }

    CompletableFuture<NpcCatalogView> npcDefinitions();

    /**
     * Crée une <strong>définition logique</strong> de PNJ ({@code npcs/<id>.yml}). Écriture
     * whitelistée et auditée ; jamais de YAML brut ni de chemin arbitraire ; échoue si l'id existe
     * déjà (pas d'écrasement silencieux).
     */
    CompletableFuture<MutationResult> npcDefinitionCreate(String id, String displayName, String dialogueId,
                                                          String role, boolean enabled);

    /** Modifie une définition PNJ existante ({@code displayName} / {@code dialogue} / {@code role} / {@code enabled}) — jamais l'id. */
    CompletableFuture<MutationResult> npcDefinitionUpdate(String id, String displayName, String dialogueId,
                                                         String role, boolean enabled);

    /**
     * Issue #226 — supprime la <strong>définition logique seule</strong>, après l'avoir sauvegardée
     * côté serveur.
     *
     * <p><strong>Jamais de cascade.</strong> Ni le PNJ Citizens, ni sa liaison, ni le dialogue ne
     * sont touchés : ce sont trois opérations distinctes, avec trois décisions distinctes.</p>
     *
     * <p><strong>Les dépendances sont revalidées ici</strong>, au moment d'exécuter, et pas
     * seulement affichées par l'écran qui a demandé. Un aperçu peut avoir dix minutes ; entre-temps
     * une quête a pu désigner ce PNJ comme donneur. Dans ce cas la suppression est refusée, en
     * disant lesquelles.</p>
     *
     * @param expectDialogueId dialogue que l'appelant croit lié, ou vide. S'il est fourni et ne
     *                         correspond pas, l'opération est refusée : c'est le signe que l'écran
     *                         décrivait un autre état du serveur
     */
    CompletableFuture<MutationResult> npcDefinitionDelete(String id, String expectDialogueId);

    /**
     * Issue #226 — retire la liaison RPGQuest ↔ Citizens, <strong>sans toucher au PNJ Citizens</strong>.
     *
     * @param expectedCitizensId identifiant numérique attendu ; une divergence refuse l'opération
     *                           plutôt que de délier une liaison apparue depuis l'affichage
     */
    CompletableFuture<MutationResult> npcCitizensUnlink(String npcId, int expectedCitizensId);

    /**
     * Issue #226 — détruit le PNJ Citizens <strong>physique</strong> et retire la liaison qui le
     * désignait. La définition logique et le dialogue restent intacts.
     *
     * @param expectedCitizensId identifiant numérique attendu. L'entité n'est détruite que si son
     *                           UUID <em>et</em> cet identifiant correspondent à la liaison réelle —
     *                           c'est ce qui rend impossible de détruire le mauvais PNJ
     */
    CompletableFuture<MutationResult> npcCitizensDelete(String npcId, int expectedCitizensId);

    /**
     * Pose le champ {@code giver:} d'une quête existante (édition texte minimale, commentaires
     * préservés). Exige que la quête <em>et</em> la définition logique du PNJ existent.
     */
    CompletableFuture<MutationResult> questGiverSet(String questId, String npcId);

    /**
     * Issue #165 — change le nom affiché en jeu du PNJ Citizens lié à {@code npcId}. L'identité
     * logique RPGQuest ({@code npcId}), l'UUID/l'id Citizens et toutes les liaisons
     * quêtes/dialogues/stories restent inchangés : seul le nom visible bouge.
     */
    /**
     * Issue #172/#196 — catalogues <strong>réels du serveur</strong> pour les listes déroulantes de
     * l'éditeur de mobs : types d'entité, particules, sons et biomes tels que la version installée
     * les expose, plus les mondes chargés. Le Control Panel ne peut pas dépendre de Bukkit : sans ce
     * relevé, ses champs restent du texte libre et l'administrateur doit devine les identifiants.
     */
    CompletableFuture<MobCatalogsView> mobCatalogs();

    /**
     * @param entityTypes  types d'entité vivants et réellement invocables
     * @param particles    particules disponibles
     * @param sounds       sons disponibles
     * @param biomes       biomes du registre réel
     * @param worlds       mondes actuellement chargés
     * @param wildWorld    monde Wild configuré (défaut pertinent pour « Mondes autorisés »)
     * @param colorableParticles particules acceptant une couleur (sous-ensemble de {@code particles})
     */
    record MobCatalogsView(List<String> entityTypes, List<String> particles, List<String> sounds,
                           List<String> biomes, List<String> worlds, String wildWorld,
                           List<String> colorableParticles) {
    }

    /**
     * Issue #196 — catalogue <strong>complet</strong> des matériaux de la version installée, pour
     * les icônes de quête et les récompenses d'objet.
     *
     * <p><strong>Pourquoi un relevé et pas une liste dans le panel.</strong> Le Control Panel
     * portait une liste curée d'environ 76 matériaux écrite à la main, qui ne contenait que
     * {@code IRON_SWORD} et {@code DIAMOND_SWORD} : chercher « sword » ne trouvait donc que deux
     * épées sur les sept que cette version expose. Une liste codée en dur ne peut pas suivre les
     * versions — et inventer des matériaux absents de la version installée serait pire que d'en
     * oublier.</p>
     */
    CompletableFuture<ItemCatalogsView> itemCatalogs();

    /**
     * Catalogue des matériaux, séparé selon ce que l'API garantit réellement.
     *
     * <p><strong>La distinction qui compte.</strong> Un matériau n'est utilisable comme icône
     * d'inventaire <em>ou</em> comme récompense que s'il peut exister en tant qu'objet
     * ({@code Material#isItem()}). Certains blocs n'ont aucune forme d'objet — l'eau, le feu, un
     * portail — et ne peuvent donc être ni l'un ni l'autre. Ils sont renvoyés à part pour que le
     * panel puisse <strong>expliquer</strong> le refus au lieu de les omettre silencieusement.</p>
     *
     * @param items             matériaux utilisables comme icône <strong>et</strong> comme
     *                          récompense ({@code isItem()}, hors formes historiques et hors air)
     * @param blocksWithoutItem blocs réels sans forme d'objet : ni icône, ni récompense possible
     * @param minecraftVersion  version Minecraft réellement installée, pour l'afficher au panel
     * @param legacyExcluded    nombre de constantes historiques ({@code LEGACY_*}) écartées
     */
    record ItemCatalogsView(List<String> items, List<String> blocksWithoutItem,
                            String minecraftVersion, int legacyExcluded) {
    }

    /**
     * Issue #194 — supprime la définition d'une quête ou d'une story <strong>sur le serveur</strong>,
     * avec sauvegarde horodatée du fichier, puis relit les définitions.
     *
     * <p>Sans cette opération, supprimer un contenu de la source éditable ne le retire pas du
     * serveur : le fichier déployé reste chargé et le contenu réapparaît au prochain
     * rafraîchissement du catalogue. C'était le symptôme rapporté.</p>
     *
     * <p>Ne touche <strong>aucune progression de joueur</strong> : la suppression est éditoriale.
     * Ne supprime rien d'autre — ni PNJ, ni dialogue, ni les quêtes qu'une story enchaînait.</p>
     *
     * @param kind {@code quests} ou {@code stories}
     * @param id   identifiant déclaré dans le fichier, avec ou sans le namespace {@code rpgquest:}
     */
    CompletableFuture<MutationResult> contentDefinitionDelete(String kind, String id);

    CompletableFuture<MutationResult> citizensRename(String npcId, String newName);

    /**
     * Issue #197 — change le <strong>nom du personnage</strong>, sur toutes les couches qui le
     * portent, en une seule opération.
     *
     * <p><strong>Pourquoi cette action existe.</strong> Jusqu'ici, renommer un PNJ demandait deux
     * gestes distincts : modifier la définition RPGQuest ({@code npcs/<id>.yml}), puis renommer le
     * PNJ Citizens. Rien ne les reliait, et un administrateur qui n'en faisait qu'un obtenait un
     * personnage dont le nom du catalogue et le nom affiché au-dessus de la tête divergeaient — ce
     * qui est arrivé au Guide renommé « Elias ». La différence entre ces deux noms est un détail
     * d'implémentation : elle n'a pas à être administrée.</p>
     *
     * <p><strong>Ordre délibéré.</strong> Citizens d'abord, la définition ensuite. Le renommage
     * Citizens est le seul des deux qui peut légitimement <em>refuser</em> (apparence dont la source
     * est inconnue, voir {@code SkinPreservationPlanner}). Le faire en premier garantit qu'un refus
     * laisse <strong>zéro couche modifiée</strong>, au lieu d'un nom logique déjà changé pour un
     * personnage qui s'appelle encore autrement en jeu.</p>
     *
     * <p><strong>Jamais de succès partiel déguisé.</strong> Les deux couches sont <em>relues</em>
     * après l'opération. Si l'une n'a pas suivi, le résultat est un échec qui nomme précisément
     * celle qui a changé et celle qui n'a pas changé.</p>
     *
     * <p><strong>Aucun texte de dialogue n'est réécrit.</strong> Le {@code speaker} d'un nœud est
     * une donnée d'auteur, parfois partagée entre plusieurs personnages : le renommage le
     * <em>signale</em> quand il diverge, il ne le remplace pas.</p>
     *
     * @param npcId identifiant logique, jamais modifié — c'est l'identité stable du personnage
     * @param name  nouveau nom affiché, pour le catalogue comme pour le jeu
     */
    CompletableFuture<MutationResult> npcIdentityRename(String npcId, String name);

    /**
     * Issue #165 — applique un skin à partir d'une URL MineSkin au PNJ Citizens lié à
     * {@code npcId}. L'URL est validée côté serveur ; le téléchargement est fait par Citizens de
     * façon asynchrone, donc le succès renvoyé signifie « demande acceptée et transmise », pas
     * « skin visuellement confirmé ».
     */
    /**
     * Déplace le PNJ Citizens lié à {@code npcId}, <strong>sans le recréer</strong> : identité
     * Citizens, identifiant RPGQuest, skin, traits et liaisons dialogues/quêtes sont conservés.
     *
     * <p>Un PNJ non matérialisé n'est jamais fait apparaître : seule sa position enregistrée
     * change, et le résultat le dit. La position obtenue est <strong>vérifiée</strong> après coup ;
     * en cas d'écart, l'action échoue en expliquant, plutôt que de rendre un succès trompeur.</p>
     */
    CompletableFuture<MutationResult> citizensMove(String npcId, String world,
                                                   double x, double y, double z, float yaw, float pitch);

    /**
     * Issue #325 — déplacement où l'orientation est <strong>optionnelle</strong> : {@code null}
     * garde celle du PNJ, au lieu de le remettre face au sud quand on ne change que sa position.
     * Si le PNJ se promène, le centre de sa zone suit la destination.
     */
    default CompletableFuture<MutationResult> citizensRelocate(String npcId, String world,
                                                               double x, double y, double z,
                                                               Float yaw, Float pitch) {
        return citizensMove(npcId, world, x, y, z, yaw == null ? 0f : yaw, pitch == null ? 0f : pitch);
    }

    /**
     * Applique un skin au PNJ Citizens lié. {@code byPlayerName} choisit la source : un pseudo
     * Minecraft résolu par Citizens, ou un lien MineSkin. Le <strong>nom en jeu n'est jamais
     * touché</strong>. La source appliquée est enregistrée, pour pouvoir être reconduite lors d'un
     * renommage ultérieur.
     */
    CompletableFuture<MutationResult> citizensSkin(String npcId, String value, boolean byPlayerName);

    /**
     * Issue #165 — « regarder les joueurs » (trait Citizens {@code lookclose}) sur le PNJ lié.
     *
     * <p>{@code enabled} est un état <strong>explicite</strong>, jamais une bascule : rejouer la
     * requête (double clic, retry réseau, action rejouée) ne peut pas inverser l'état obtenu.
     * {@code range} nul laisse la portée inchangée.</p>
     */
    CompletableFuture<MutationResult> citizensLookClose(String npcId, boolean enabled, Double range);

    /**
     * Issue #165 — promenade (fournisseur {@code wander} du trait Citizens {@code waypoints}).
     *
     * <p>Dans Citizens, un PNJ n'a qu'un seul fournisseur de parcours : activer la promenade sur un
     * PNJ qui patrouille <strong>détruirait</strong> sa patrouille. L'action refuse donc tant que
     * {@code confirmReplace} n'est pas vrai, en nommant ce qui serait perdu.</p>
     *
     * <p>À l'activation, {@code world}/{@code x}/{@code y}/{@code z} définissent l'<strong>ancre</strong>
     * de la zone — sans ancre, Citizens ne borne pas la promenade. {@code null} reprend la position
     * actuelle du PNJ. {@code xRange}/{@code yRange} sont des demi-côtés en blocs.</p>
     */
    CompletableFuture<MutationResult> citizensWander(String npcId, boolean enabled,
                                                     String world, Double x, Double y, Double z,
                                                     int xRange, int yRange, boolean confirmReplace);

    /**
     * Issue #325 — arrête la promenade et, si {@code returnHome}, ramène le PNJ au centre de sa
     * zone (destination sûre vérifiée ; sinon il reste où il est et le résultat le dit).
     */
    /**
     * Issue #335 — paramètres généraux. {@code values} : clé → valeur texte ; {@code defaults} :
     * valeurs par défaut ; {@code problem} : motif si {@code settings.yml} est illisible.
     */
    record GameSettingsView(boolean ok, String message, Map<String, String> values, Map<String, String> defaults,
                            String updatedAt, String updatedBy, String problem) {
    }

    default CompletableFuture<GameSettingsView> settingsGet() {
        return CompletableFuture.completedFuture(new GameSettingsView(false, "Paramètres indisponibles.",
                Map.of(), Map.of(), "", "", ""));
    }

    default CompletableFuture<GameSettingsView> settingsSet(Map<String, String> changes, String actor) {
        return settingsGet();
    }

    // ---- PNJ récurrents (actions encounter.*, issue #13 étape 2) -------------------------------

    /** Un modèle {@code encounters/<id>.yml} chargé, avec son nombre d'occurrences actuelles. */
    record EncounterTemplateSummary(String id, String displayName, String npcId, String role, String family,
                                    List<String> biomes, List<String> worlds, int weight, int maxSites,
                                    boolean enabled, String skinUrl, int sites, List<String> questCatalogs) {
    }

    /**
     * Une occurrence persistée. {@code citizensPresent} : l'entité Citizens enregistrée existe-t-elle
     * encore dans le registre Citizens ({@code null} = inconnu, Citizens absent). {@code appearance} :
     * état de l'apparence depuis le démarrage ({@code EncounterService#appearanceOf}), {@code null}
     * si rien n'a été tenté.
     */
    record EncounterSiteSummary(String id, String world, String biomeKey, String biomeInstance, int x, int y, int z,
                                String templateId, String npcId, int citizensId, String citizensUuid,
                                Boolean citizensPresent, String status, String lastError, String createdAt,
                                String updatedAt, String appearance) {
    }

    record EncounterEventSummary(String at, String world, String instance, String outcome, String detail) {
    }

    /** {@code issues} : fichier → motif, pour chaque modèle rejeté au dernier chargement. */
    record EncounterCatalogView(boolean available, boolean enabled, List<String> worlds, int maxPerWorld,
                                List<EncounterTemplateSummary> templates, Map<String, String> issues,
                                List<EncounterSiteSummary> sites, List<EncounterEventSummary> events,
                                long generatedAtEpochMs) {
    }

    default CompletableFuture<EncounterCatalogView> encounterCatalog() {
        return CompletableFuture.completedFuture(new EncounterCatalogView(false, false, List.of(), 0, List.of(),
                Map.of(), List.of(), List.of(), System.currentTimeMillis()));
    }

    // ---- Droits à récompense (issue #422) --------------------------------------------------------

    /** Une ligne de traçabilité d'un droit à récompense. Horodatages ISO-8601, vides si absents. */
    record RewardEntitlementSummary(String key, String playerUuid, String playerName, String source,
                                    String sourceRef, String rewardKey, String label, String status, int attempts,
                                    String lastError, String revokeReason, String createdAt, String updatedAt,
                                    String deliveredAt, String revokedAt) {
    }

    /** Une récompense du catalogue {@code entitlements.yml}, pour le formulaire de déclaration. */
    record RewardOption(String key, String label) {
    }

    /**
     * @param available {@code false} si le service n'est pas branché sur ce serveur
     * @param enabled   interrupteur {@code entitlements.yml}
     */
    record RewardEntitlementView(boolean available, boolean enabled, List<RewardEntitlementSummary> entitlements,
                                 List<RewardOption> rewards) {
    }

    /** {@code status} : {@code null} = tous. */
    default CompletableFuture<RewardEntitlementView> rewardEntitlements(UUID playerId, String status, int limit) {
        return CompletableFuture.completedFuture(new RewardEntitlementView(false, false, List.of(), List.of()));
    }

    /** Codes : {@code DECLARED}, {@code ALREADY_DECLARED}, {@code DISABLED}, {@code INVALID}, {@code UNAVAILABLE}. */
    default CompletableFuture<MutationResult> declareRewardEntitlement(UUID playerId, String rewardKey,
                                                                       String reference) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNAVAILABLE",
                "Droits à récompense indisponibles sur ce serveur."));
    }

    /** Codes : {@code REVOKED}, {@code NOT_PENDING}, {@code UNAVAILABLE}. */
    default CompletableFuture<MutationResult> revokeRewardEntitlement(String key, String reason) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNAVAILABLE",
                "Droits à récompense indisponibles sur ce serveur."));
    }

    // ---- Tables de récompenses aléatoires : éditeur PlugAdmin (issue #177, tranche 2) -----------

    /** Une récompense du catalogue, telle que l'éditeur de tables la propose ({@code summary} lisible). */
    record RewardCatalogEntryView(String key, String label, String summary) {
    }

    /**
     * État de {@code entitlements.yml} pour l'éditeur de tables -- action {@code reward.catalog.status}.
     *
     * @param fileExists le fichier existe sur le serveur (sinon aucune récompense, donc aucune table possible)
     * @param valid      le serveur accepte le fichier ; {@code errors} dit pourquoi sinon
     * @param enabled    interrupteur général : faux = aucun tirage, ni pour les quêtes ni pour les boss
     */
    record RewardCatalogView(boolean available, boolean fileExists, boolean valid, boolean enabled,
                             List<String> errors, List<RewardCatalogEntryView> rewards,
                             List<RewardTableView> tables) {
    }

    /** Une entrée saisie dans l'éditeur ; {@code chance} en centièmes de pour cent (1250 = 12,5 %). */
    record RewardTableEntryInput(String id, String rewardKey, int chance) {
    }

    default CompletableFuture<RewardCatalogView> rewardCatalogStatus() {
        return CompletableFuture.completedFuture(new RewardCatalogView(false, false, false, false, List.of(),
                List.of(), List.of()));
    }

    /**
     * Crée ({@code create}) ou remplace une table -- action {@code reward.table.save}. Codes :
     * {@code CREATED}, {@code UPDATED}, {@code UNCHANGED}, {@code EXISTS}, {@code NOT_FOUND},
     * {@code STALE}, {@code INVALID}, {@code NO_CATALOG}, {@code CURRENT_INVALID},
     * {@code ROLLED_BACK}, {@code ERROR}, {@code UNAVAILABLE}.
     *
     * @param expectedVersion version lue par le formulaire, {@code null} = pas de contrôle
     */
    default CompletableFuture<MutationResult> rewardTableSave(String id, String label, String mode, boolean enabled,
                                                              List<RewardTableEntryInput> entries,
                                                              String expectedVersion, boolean create) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNAVAILABLE",
                "Tables de récompenses indisponibles sur ce serveur."));
    }

    /** Copie désactivée d'une table -- action {@code reward.table.duplicate}. {@code newLabel} facultatif. */
    default CompletableFuture<MutationResult> rewardTableDuplicate(String sourceId, String newId, String newLabel) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNAVAILABLE",
                "Tables de récompenses indisponibles sur ce serveur."));
    }

    /** Active ou désactive une table -- action {@code reward.table.toggle}. */
    default CompletableFuture<MutationResult> rewardTableToggle(String id, boolean enabled) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNAVAILABLE",
                "Tables de récompenses indisponibles sur ce serveur."));
    }

    /** {@code operation} : {@code retry}, {@code disable} ou {@code remove}, sur la seule occurrence nommée. */
    default CompletableFuture<MutationResult> encounterSiteAction(String operation, String siteId) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNAVAILABLE",
                "PNJ récurrents indisponibles sur ce serveur."));
    }

    default CompletableFuture<MutationResult> encounterTemplatesReload() {
        return encounterSiteAction("reload", "");
    }

    default CompletableFuture<MutationResult> encounterBackoffsClear() {
        return encounterSiteAction("backoffs", "");
    }

    /**
     * Issue #13 étape 3 — écrit un modèle déjà validé ({@code create} : échoue s'il existe), puis
     * relit les modèles. Les effets portent les avertissements (biome inconnu, dialogue absent…).
     */
    default CompletableFuture<MutationResult> encounterTemplateSave(
            com.lodygames.rpgquest.encounter.model.EncounterTemplate template, boolean create) {
        return encounterSiteAction("template", "");
    }

    default CompletableFuture<MutationResult> encounterTemplateToggle(String templateId, boolean enabled) {
        return encounterSiteAction("template", "");
    }

    /** {@code acknowledgeSites} : l'administrateur accepte que les occurrences existantes restent en place. */
    default CompletableFuture<MutationResult> encounterTemplateDelete(String templateId, boolean acknowledgeSites) {
        return encounterSiteAction("template", "");
    }

    // ---- Catalogues de quêtes secondaires (actions quest.catalog.*, issue #369) -----------------

    /**
     * Une entrée de catalogue, enrichie de la quête qu'elle référence ({@code exists == false} :
     * quête absente — l'entrée est gardée, jamais proposée).
     */
    record QuestCatalogEntryView(String questId, int weight, boolean enabled, boolean exists, String title,
                                 String category, boolean repeatable, String cooldown, int steps) {
    }

    /** {@code usedBy} : modèles de PNJ récurrents qui référencent ce catalogue. */
    record QuestCatalogSummary(String id, String displayName, String description, boolean enabled, int avoidRecent,
                               List<QuestCatalogEntryView> entries, List<String> usedBy, List<String> warnings) {
    }

    /** Une quête chargée, pour la recherche par titre ou identifiant. */
    record QuestRef(String id, String title, String category) {
    }

    /**
     * Issue #414 — un modèle de PNJ récurrent, pour choisir depuis un catalogue qui le propose.
     * {@code offersCatalog} : le dialogue {@code rpgquest:<npcId>} contient une action
     * {@code OFFER_CATALOG_QUEST} — sans elle, l'association seule ne propose rien en jeu.
     */
    record QuestCatalogTemplateRef(String id, String displayName, String npcId, boolean enabled,
                                   List<String> questCatalogs, boolean offersCatalog) {
    }

    /** {@code warnings} : contrôles transverses (modèle → catalogue inconnu, dialogue sans offre…). */
    record QuestCatalogListView(boolean available, List<QuestCatalogSummary> catalogs, Map<String, String> issues,
                                List<QuestRef> quests, List<String> warnings, List<QuestCatalogTemplateRef> templates,
                                long generatedAtEpochMs) {
    }

    default CompletableFuture<QuestCatalogListView> questCatalogList() {
        return CompletableFuture.completedFuture(new QuestCatalogListView(false, List.of(), Map.of(), List.of(),
                List.of(), List.of(), System.currentTimeMillis()));
    }

    /**
     * Issue #414 — fixe exactement les modèles qui proposent un catalogue : ajouté aux modèles
     * nommés, retiré des autres. Refusé en entier si un modèle est inconnu ; les autres catalogues
     * d'un modèle ne sont jamais touchés.
     */
    default CompletableFuture<MutationResult> questCatalogAssign(String catalogId, Set<String> templateIds) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNAVAILABLE",
                "Catalogues de quêtes indisponibles sur ce serveur."));
    }

    /** Écrit un catalogue déjà validé ({@code create} : échoue s'il existe), puis relit les catalogues. */
    default CompletableFuture<MutationResult> questCatalogSave(
            com.lodygames.rpgquest.encounter.catalog.QuestCatalog catalog, boolean create) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNAVAILABLE",
                "Catalogues de quêtes indisponibles sur ce serveur."));
    }

    /**
     * Supprime un catalogue (sauvegarde d'abord). Refusé s'il est référencé par un modèle, sauf
     * {@code detach} : la référence est alors retirée de ces modèles. Aucune progression touchée.
     */
    default CompletableFuture<MutationResult> questCatalogDelete(String catalogId, boolean detach) {
        return questCatalogSave(null, false);
    }

    /**
     * Citizens UUID → identifiant d'occurrence, pour les PNJ de rencontre actuellement suivis : le
     * roster Citizens les distingue ainsi des PNJ « non rattachés ». Thread quelconque.
     */
    default Map<UUID, String> encounterCitizens() {
        return Map.of();
    }

    default CompletableFuture<MutationResult> citizensWanderStop(String npcId, boolean returnHome) {
        return citizensWander(npcId, false, null, null, null, null, 0, 0, false);
    }

    /**
     * Un PNJ Citizens du registre (action {@code npc.citizens.list}). {@code linkedNpcId} = id
     * logique RPGQuest déjà lié à ce PNJ, ou {@code null}. Aucune position/monde (registre seul).
     */
    /**
     * @param world        monde de la position connue, {@code null} si Citizens n'en a aucune.
     * @param liveLocation {@code true} = position de l'entité réellement présente ; {@code false} =
     *                     dernière position enregistrée pour un PNJ non apparu (ne prouve rien sur
     *                     sa présence actuelle).
     * @param shouldSpawn  intention persistante de Citizens (trait {@code Spawned}) — un PNJ peut
     *                     légitimement ne pas être matérialisé si aucun joueur n'est à portée.
     * @param chunkLoaded  le chunk de sa position est-il chargé.
     * @param lookCloseEnabled « regarder les joueurs » est-il actif ; {@code null} = <strong>inconnu</strong>
     *                     (build Citizens n'exposant pas le trait), jamais « désactivé ».
     * @param lookCloseRange portée du regard en blocs, {@code null} si inconnue.
     * @param wanderEnabled la promenade est-elle active ; {@code null} = inconnu.
     * @param wanderProvider fournisseur de parcours Citizens réellement en place ({@code linear},
     *                     {@code wander}, {@code guided}, ou celui d'un plugin tiers). C'est lui qui
     *                     dit ce qu'une activation de promenade écraserait.
     * @param wanderWaypoints nombre de points du fournisseur courant quand il en expose.
     * @param wanderWorld  <strong>ancre</strong> de la zone de promenade — distincte de la position
     *                     actuelle du PNJ, qu'un déplacement manuel ne change pas.
     * @param wanderXRange demi-côté horizontal de la zone, {@code wanderYRange} demi-hauteur, en blocs.
     * @param entityType   type d'entité enregistré par Citizens ({@code PLAYER}, {@code VILLAGER}…),
     *                     ou {@code null} si la build installée ne l'expose pas (issue #197). C'est
     *                     lui qui dit si la question du skin a un sens pour ce PNJ.
     * @param skinSourceKind forme de l'apparence <strong>que RPGQuest a lui-même appliquée et
     *                     enregistrée</strong> : {@code URL} (lien MineSkin) ou {@code NAME} (pseudo
     *                     Minecraft), ou {@code null} si RPGQuest n'en connaît aucune. Issue #197 :
     *                     {@code null} ne veut <strong>pas</strong> dire « ce PNJ n'a pas de skin » —
     *                     l'API publique de Citizens ne permet pas de lire le skin en place. Cela
     *                     veut dire « sa source est inconnue », donc non reproductible.
     * @param skinSourceValue valeur correspondante (lien ou pseudo), ou {@code null}.
     * @param skinMatchesSource issue #325 — l'image réellement portée par le PNJ est-elle celle de la
     *                     source enregistrée ? {@code null} = invérifiable (pas une source par lien,
     *                     ou lien pas encore relu), jamais « oui » supposé.
     */
    record CitizensNpcSummary(int numericId, String uuid, String name, String linkedNpcId,
                              boolean availableForBinding, boolean spawned,
                              String world, Double x, Double y, Double z, Float yaw, Float pitch,
                              boolean liveLocation, boolean shouldSpawn, boolean chunkLoaded,
                              Boolean lookCloseEnabled, Double lookCloseRange,
                              Boolean wanderEnabled, String wanderProvider, Integer wanderWaypoints,
                              String wanderWorld, Double wanderX, Double wanderY, Double wanderZ,
                              Integer wanderXRange, Integer wanderYRange,
                              String entityType, String skinSourceKind, String skinSourceValue,
                              Boolean skinMatchesSource) {

        /** Entrée sans position connue — les chemins qui n'en ont pas besoin, et les tests. */
        CitizensNpcSummary(int numericId, String uuid, String name, String linkedNpcId,
                           boolean availableForBinding, boolean spawned) {
            this(numericId, uuid, name, linkedNpcId, availableForBinding, spawned,
                    null, null, null, null, null, null, false, spawned, false,
                    null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null);
        }
    }

    record CitizensRosterView(boolean citizensAvailable, List<CitizensNpcSummary> citizens,
                              int total, int available, int linked) {
    }

    /**
     * Résultat d'un approvisionnement complet de PNJ : définition + apparition + liaison + skin.
     *
     * @param npcId      identifiant logique RPGQuest créé, ou {@code null} si rien n'a été créé
     * @param citizensId identifiant Citizens obtenu, ou {@code null}
     * @param world      position réellement retenue (jamais celle demandée si elle a été refusée)
     * @param skinNote   ce qui a été fait du skin, dit explicitement même en cas de non-application
     * @param rolledBack un échec a-t-il été nettoyé intégralement
     */
    record CitizensProvisionResult(boolean ok, String code, String message, String npcId, Integer citizensId,
                                   String displayName, String world, Double x, Double y, Double z,
                                   Float yaw, Float pitch, String skinNote, boolean rolledBack,
                                   List<String> effects) {
        static CitizensProvisionResult reject(String code, String message) {
            return new CitizensProvisionResult(false, code, message, null, null, null, null, null, null,
                    null, null, null, null, false, List.of());
        }
    }

    /**
     * Crée un PNJ de bout en bout depuis le panel : définition RPGQuest, apparition Citizens,
     * liaison, et skin optionnel — en réutilisant le parcours canonique existant, jamais un second
     * système. Position explicite si {@code world} est fourni, sinon recherche d'un emplacement
     * libre et sûr près du Guide du Hub.
     *
     * <p>Atomique du point de vue de l'appelant : tout échec nettoie <strong>uniquement</strong> ce
     * que cette tentative a créé.</p>
     */
    CompletableFuture<CitizensProvisionResult> citizensProvision(
            String displayName, String skinValue, boolean skinByPlayerName,
            String world, Double x, Double y, Double z, Float yaw, Float pitch);

    /**
     * Issue #325 — création avec l'identifiant retenu par le panel ({@code requestedId}, vide =
     * déduit du nom) et le dialogue initial à déclarer sur la définition ({@code dialogueId}, vide =
     * aucun). Un skin vide pose l'apparence par défaut. Les implémentations historiques ignorent les
     * deux nouveaux paramètres.
     */
    default CompletableFuture<CitizensProvisionResult> citizensProvision(
            String requestedId, String dialogueId, String displayName, String skinValue,
            boolean skinByPlayerName, String world, Double x, Double y, Double z, Float yaw, Float pitch) {
        return citizensProvision(displayName, skinValue, skinByPlayerName, world, x, y, z, yaw, pitch);
    }

    /** Catalogue Citizens <strong>physique</strong> (séparé du catalogue logique {@code npc.list}). */
    CompletableFuture<CitizensRosterView> citizensRoster();

    /**
     * Lie une définition PNJ existante à un PNJ Citizens existant (par son id numérique) — issue
     * #81, phase 1. Aucune création/suppression/rebind : collision Citizens ou {@code npc_id} déjà
     * lié → refus lisible ; liaison identique déjà présente → succès no-op.
     */
    CompletableFuture<MutationResult> citizensLink(String npcId, int citizensNumericId);

    /**
     * Résultat de {@code npc.citizens.create} (issue #81, phase 2). En cas d'échec de la liaison
     * <em>après</em> création du PNJ Citizens, {@code rolledBack} indique si le PNJ créé a bien été
     * détruit (aucun PNJ physique orphelin) — code {@code BIND_FAILED_ROLLED_BACK}.
     *
     * @param code {@code CREATED} / {@code CITIZENS_UNAVAILABLE} / {@code UNKNOWN_NPC} /
     *             {@code NPC_DISABLED} / {@code NPC_ALREADY_LINKED} / {@code UNKNOWN_WORLD} /
     *             {@code INVALID_POSITION} / {@code CREATE_FAILED} / {@code BIND_FAILED_ROLLED_BACK} /
     *             {@code ERROR}
     */
    record CitizensCreateResult(boolean ok, String code, String message, Integer citizensNumericId,
                                String npcId, List<String> effects, boolean rolledBack) {
        static CitizensCreateResult reject(String npcId, String code, String message) {
            return new CitizensCreateResult(false, code, message, null, npcId, List.of(), false);
        }
    }

    /**
     * Crée <strong>physiquement</strong> un PNJ Citizens à partir d'une {@code NpcDefinition}
     * existante (nom = {@code displayName}), puis le lie immédiatement à son {@code npc_id} — issue
     * #81, phase 2. Toutes les préconditions logiques (définition présente et {@code enabled}, pas
     * de binding préexistant, monde de la liste blanche RPGQuest, position bornée) sont vérifiées
     * <em>avant</em> toute création. Si la liaison échoue après création, le PNJ créé est détruit
     * (rollback applicatif — jamais un PNJ préexistant). Aucun rebind, aucune suppression générale.
     */
    CompletableFuture<CitizensCreateResult> citizensCreate(String npcId, String world,
                                                           double x, double y, double z,
                                                           float yaw, float pitch);

    // ---- Dialogues (action {@code dialogue.list}, lecture — V1 /dialogues) ---------------------

    /** Action de choix de dialogue, <strong>typée</strong> (jamais une simple chaîne). */
    record DialogueActionSummary(String kind, String target, String value, String raw) {
    }

    record DialogueConditionSummary(String kind, String target, String value, String raw, boolean negated) {
    }

    record DialogueChoiceSummary(String text, String nextNodeId, List<DialogueActionSummary> actions,
                                 List<DialogueConditionSummary> conditions) {
    }

    /** {@code start} = nœud de départ ; {@code reachable} = atteignable depuis {@code start} par les {@code next}. */
    record DialogueNodeSummary(String id, String speaker, String text, boolean start, boolean reachable,
                               List<DialogueChoiceSummary> choices) {
    }

    /** {@code warnings[].severity} ∈ {@code error} / {@code warning} / {@code info}. */
    record DialogueWarning(String code, String severity, String message) {
    }

    record DialogueSummary(String id, String key, String startNodeId, List<String> linkedNpcIds,
                           int nodeCount, int choiceCount, List<String> referencedQuestIds,
                           List<String> startsQuestIds, List<DialogueNodeSummary> nodes,
                           List<DialogueWarning> warnings) {
    }

    /** Un fichier de dialogue <em>rejeté</em> au chargement (n'est jamais devenu une définition). */
    record DialogueLoadIssueSummary(String file, String message) {
    }

    /** Une définition PNJ déclare un {@code dialogue:} qui n'est pas chargé. */
    record DialogueMissingDeclared(String npcId, String dialogueId) {
    }

    record DialogueCatalogView(List<DialogueSummary> dialogues, List<DialogueLoadIssueSummary> loadIssues,
                               List<DialogueMissingDeclared> declaredButMissing, int total, int withWarnings,
                               int nodeTotal) {
    }

    /** Catalogue de dialogues structuré (nœuds / choix / actions typées / relations PNJ+quêtes / warnings). */
    CompletableFuture<DialogueCatalogView> dialogueDefinitions();

    /**
     * Crée un <strong>squelette</strong> de dialogue minimal valide ({@code dialogues/<key>.yml} :
     * un nœud {@code start} avec un unique choix « fermer »). Écriture whitelistée et auditée ;
     * jamais de YAML brut ni de chemin arbitraire ; échoue si l'id existe déjà ; re-parsé après
     * écriture (fichier supprimé si le rechargement échoue). L'édition fine des nœuds/choix viendra
     * dans un futur éditeur (voir rapport).
     */
    CompletableFuture<MutationResult> dialogueDefinitionCreate(String key, String speaker, String text);

    // ---- Édition guidée d'un dialogue existant (issue #82) -------------------------------------
    //
    // Périmètre : locuteur/texte d'un nœud, nœud simple, choix (texte, cible, action de quête,
    // condition d'état de quête). Chaque écriture réécrit le fichier au format canonique du panel,
    // est re-parsée puis rechargée ; en cas d'échec le contenu d'origine est restauré. Jamais de
    // YAML brut, jamais de chemin — seulement des champs métier validés. Tout ce que l'appelant ne
    // nomme pas explicitement (autres actions, autres conditions, traductions) est reconduit.

    /** Modifie le locuteur et le texte d'un nœud existant (les choix sont conservés). */
    CompletableFuture<MutationResult> dialogueNodeUpdate(String dialogueId, String nodeId, String speaker, String text);

    /** Ajoute un nœud simple (locuteur + texte + un choix « fermer ») ; nœud orphelin assumé. */
    CompletableFuture<MutationResult> dialogueNodeCreate(String dialogueId, String nodeId, String speaker, String text);

    /** Ajoute un choix simple à un nœud : texte + (redirection vers un nœud existant OU fermeture). */
    CompletableFuture<MutationResult> dialogueChoiceAdd(String dialogueId, String nodeId, String choiceText,
                                                        String nextNodeId, boolean close);

    /**
     * Modifie un choix existant (repéré par son index dans le nœud) : texte, cible, et — si les
     * intentions le demandent — son action de quête et sa condition {@code QUEST_STATE}. Les autres
     * actions et conditions du choix sont reconduites à l'identique.
     *
     * @param questAction    intention sur l'action de quête ({@code KEEP} ne touche à rien)
     * @param questCondition intention sur la condition d'état de quête
     */
    CompletableFuture<MutationResult> dialogueChoiceUpdate(
            String dialogueId, String nodeId, int choiceIndex, String choiceText, String nextNodeId, boolean close,
            DialogueDefinitionEditor.QuestActionEdit questAction,
            DialogueDefinitionEditor.QuestConditionEdit questCondition);

    /** Supprime un choix simple (si le nœud garde au moins un choix). */
    CompletableFuture<MutationResult> dialogueChoiceDelete(String dialogueId, String nodeId, int choiceIndex);

    /** Aperçu (dry-run, aucune écriture) de ce qu'un reset supprimerait, pour la portée demandée. */
    record ResetPreviewLine(String label, int count, String detail) {
    }

    record ResetPreview(boolean online, List<ResetPreviewLine> lines) {
    }

    /**
     * @param scope portée annoncée (issue #235) — elle change ce que l'aperçu dit de l'inventaire,
     *              et c'est précisément l'information qui manquait avant ce ticket
     */
    CompletableFuture<ResetPreview> resetPreview(UUID playerId, PlayerResetService.ResetScope scope);

    // ---- Mutations -----------------------------------------------------------------------------

    /**
     * Résultat générique d'une mutation : {@code ok} + {@code code} stable (ex. {@code ACCEPTED},
     * {@code ALREADY_COMPLETED}, {@code OFFLINE}, {@code UNKNOWN_QUEST}) + message humain +
     * effets résumés (récompenses, quêtes complétées…).
     */
    /**
     * Retire un bâtiment de la bibliothèque (issue #284). Par défaut indisponible : seule la façade
     * Bukkit sait déplacer les fichiers du serveur.
     */
    default CompletableFuture<MutationResult> buildingDefinitionRetire(String id, String by) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNAVAILABLE",
                "Le retrait de bâtiments n'est pas disponible sur ce serveur."));
    }

    /**
     * Issue #246 — ce que l'administrateur demande pour une capture : l'emplacement source, un
     * ajustement borné des bornes face par face, et l'identité du nouveau bâtiment.
     */
    record BuildingCaptureRequest(String siteId, int west, int east, int down, int up, int north,
                                  int south, String newId, String name, String description,
                                  String category) {
    }

    /** Issue #246 — l'aperçu d'une capture, à plat pour le panel. */
    record BuildingCaptureView(boolean capturable, Map<String, Object> details, List<String> refusals,
                               List<String> warnings, String token) {
    }

    record MutationResult(boolean ok, String code, String message, List<String> effects) {
        public static MutationResult of(boolean ok, String code, String message) {
            return new MutationResult(ok, code, message, List.of());
        }
    }

    CompletableFuture<MutationResult> questStart(UUID playerId, String questId, boolean force);

    CompletableFuture<MutationResult> questComplete(UUID playerId, String questId);

    CompletableFuture<MutationResult> questReset(UUID playerId, String questId);

    CompletableFuture<MutationResult> storyAdvance(UUID playerId, String storyId);

    CompletableFuture<MutationResult> storyComplete(UUID playerId, String storyId);

    CompletableFuture<MutationResult> giveItem(UUID playerId, String itemId, int amount);

    /**
     * @param scope portée du reset (issue #235). {@code PROGRESSION} conserve l'inventaire,
     *              {@code NEW_PLAYER} le vide — deux actions distinctes côté panel, pour qu'un clic
     *              ne puisse pas se tromper d'intention
     */
    CompletableFuture<MutationResult> resetConfirm(UUID playerId, String playerName,
                                                   PlayerResetService.ResetScope scope);

    /** Écriture bas niveau d'une variable joueur (outil debug — confirmation panel + audit exigés). */
    CompletableFuture<MutationResult> variableSet(UUID playerId, String key, String value);

    /**
     * Bannit un joueur (issue #96) via l'API publique Paper ({@code BanList} de profil). Fonctionne
     * <strong>hors ligne</strong> ; si le joueur est connecté il est expulsé. {@code reason} est
     * stocké tel quel par le serveur (recommandé, jamais une commande). Idempotent : re-bannir un
     * joueur déjà banni renvoie {@code ok=true} code {@code ALREADY_BANNED}.
     */
    CompletableFuture<MutationResult> banPlayer(UUID playerId, String playerName, String reason);

    /** Lève le bannissement d'un joueur (issue #96). Idempotent : code {@code NOT_BANNED} si rien à faire. */
    CompletableFuture<MutationResult> unbanPlayer(UUID playerId, String playerName);

    // ---- Réseau de voyage (action {@code travel.catalog}, issue #152) — lecture seule ------------

    /**
     * Un waypoint <strong>persisté</strong> (table {@code waypoints}) — jamais une confirmation que
     * la structure physique est réellement présente/accessible dans le monde (voir
     * {@code waypoint.WaypointService#inaccessible()}/{@code #repair} côté `/rpgadmin`, hors
     * périmètre de cette simple consultation).
     */
    /**
     * {@code pairedBeaconId} : identifiant de la borne associée à ce waypoint ({@code null} =
     * aucune) -- retour joueur 2026-10-04 : afficher laquelle, pas seulement un booléen « apparié »
     * facilement confondu avec un statut de découverte joueur (voir Control Panel {@code /travel}).
     */
    record WaypointSummary(String id, String displayName, String world, String biomeKey, String biomeInstance,
                           int x, int y, int z, boolean active, int modelVersion, String pairedBeaconId,
                           String createdAt) {
    }

    /** Une borne <strong>persistée</strong> (table {@code travel_beacons}) — même réserve que {@link WaypointSummary}. */
    record BeaconSummary(String id, String world, int x, int y, int z, boolean active, int modelVersion,
                         boolean autoGenerated, String biomeInstance, String pairedWaypointId,
                         String createdAt) {
    }

    /**
     * Issue #156 — le réseau du <strong>Wild</strong>, qui n'est pas celui des bornes.
     *
     * <p>Trois réseaux coexistent et se confondent facilement : les <em>waypoints</em> (repères par
     * instance de biome, dans les deux mondes), les <em>bornes</em> (menu de voyage, Hub
     * <strong>uniquement</strong> par politique) et les <em>Waystones</em> (réseau de voyage du
     * Wild, sur grille). Un administrateur qui cherche pourquoi « on ne trouve plus de bornes » dans
     * le Wild doit pouvoir lire d'un coup d'œil qu'il n'y en a jamais eu, et que ce qui compte là-bas
     * est le nombre de Waystones <strong>découvertes</strong>.</p>
     *
     * @param cellSize   côté de la cellule de grille (blocs) qui porte au plus une Waystone
     * @param chance     probabilité qu'une cellule en contienne une
     * @param discovered nombre total de découvertes joueur, toutes Waystones confondues
     */
    record WaystoneNetworkSummary(String world, int total, int discovered, int cellSize,
                                  double chance, int minimumSpacing,
                                  int minX, int maxX, int minZ, int maxZ) {
    }

    /**
     * Issue #156 — une instance Hub qui a un waypoint mais pas de borne, <strong>avec la raison</strong>.
     * Le seul identifiant ne permettait pas d'agir : {@code attempts == 0} dénonce un appariement
     * jamais tenté, un compteur qui monte dénonce le terrain ou l'espacement, et
     * {@code nearestBeaconDistance} ({@code -1} = aucune autre borne dans ce monde) dit si
     * l'espacement minimal peut seulement être en cause.
     *
     * <p>{@code attempts} et {@code nextRetryEpochMs} viennent d'index en mémoire : un redémarrage du
     * serveur les remet à zéro. L'affichage doit le dire au lieu de laisser croire qu'aucun essai n'a
     * jamais eu lieu.</p>
     */
    record UnpairedHubInstance(String waypointId, String biomeInstance, String biomeKey, int x, int z,
                               int attempts, Long nextRetryEpochMs, boolean inProgress,
                               int nearestBeaconDistance) {
    }

    /**
     * Vue complète renvoyée par {@code travel.catalog} : listes séparées waypoints/bornes (issue
     * #152), relation waypoint↔borne par instance de biome (jamais une fusion de table), compteurs,
     * instances Hub avec waypoint mais sans borne et leur état d'appariement (issue #156, réutilise
     * {@code travel.beacon.TravelBeaconService#hubPairingGaps}), horodatage de génération — la
     * « fraîcheur » de cette vue, pas une confirmation que les structures existent physiquement à
     * l'instant où le panel l'affiche.
     */
    record TravelCatalogView(List<WaypointSummary> waypoints, List<BeaconSummary> beacons,
                             int waypointCount, int beaconCount, int autoGeneratedBeaconCount,
                             List<UnpairedHubInstance> unpairedHubInstances, long generatedAtEpochMs,
                             // Issue #156 — le référentiel que l'écran doit pouvoir citer sans
                             // l'inventer : le monde Hub, son spawn, la taille d'instance, et le
                             // réseau du Wild qui n'est pas celui des bornes.
                             String hubWorld, int hubSpawnX, int hubSpawnZ, boolean hubSpawnKnown,
                             int instanceRegionSize, int beaconPairMinSpacing,
                             int beaconPairMaxSpacing, int waypointMinimumSpacing,
                             boolean hubBeaconGenerationEnabled,
                             List<WaystoneNetworkSummary> waystoneNetworks) {
    }

    CompletableFuture<TravelCatalogView> travelCatalog();

    /**
     * Issue #156 — tente d'apparier une borne à <strong>une</strong> instance du Hub nommée par son
     * waypoint, et à elle seule.
     *
     * <p>L'appariement automatique n'est déclenché que par le déplacement d'un joueur dans
     * l'instance. Une instance traversée une seule fois — ou traversée avant un redémarrage, qui
     * remet à zéro les compteurs en mémoire — n'est donc plus jamais retentée : elle reste un manque
     * permanent que rien, dans le jeu, ne peut combler. C'est exactement ce rattrapage que cette
     * action couvre, sur une instance désignée.</p>
     *
     * <p>Ce qu'elle ne fait <strong>pas</strong>, par construction : aucun balayage du monde, aucun
     * rattrapage des autres instances, aucun chunk pré-généré, aucune modification de la densité,
     * des distances ou des probabilités. Elle réutilise la recherche d'emplacement existante
     * ({@code TravelBeaconService}), donc une borne posée ici est en tout point une borne
     * auto-générée normale — appariée à son instance, et non une borne « administrée » orpheline
     * comme en produisait la pose manuelle.</p>
     *
     * <p>Un échec est un résultat <em>attendu</em> et non une erreur : il peut n'exister aucun
     * emplacement accessible respectant l'espacement minimal autour de ce waypoint. Le message le
     * dit alors explicitement.</p>
     *
     * @param waypointId le waypoint du Hub dont l'instance doit recevoir une borne
     */
    CompletableFuture<MutationResult> pairHubBeacon(String waypointId);

    /**
     * Issue #134 — renomme le nom d'affichage d'un waypoint (voir
     * {@code waypoint.WaypointService#rename}) : unicité insensible à la casse et aux accents,
     * panneaux réécrits si le chunk est chargé, sinon à son prochain chargement. Codes :
     * {@code RENAMED}, {@code INVALID}, {@code NOT_FOUND}, {@code UNCHANGED}, {@code DUPLICATE},
     * {@code BUSY}, {@code FAILED}.
     */
    CompletableFuture<MutationResult> renameWaypoint(String waypointId, String displayName);

    // ---- Mobs spéciaux / boss (action {@code mob.list}, issue #169 lot 1) -----------------------

    /**
     * Profil de mob spécial présenté pour l'admin (action {@code mob.list}). {@code category} ∈
     * {@code SPECIAL|BOSS}. Les champs d'ability « premier lot » (Enragé / invocation de renforts)
     * sont exposés à plat -- {@code null} = capacité absente de ce profil -- pour que le formulaire
     * du panel reste un simple formulaire, jamais un éditeur générique de liste hétérogène (ce dernier
     * est laissé à une itération ultérieure de l'EPIC #169).
     */
    record MobProfileSummary(String id, String category, boolean enabled, String entityType, String displayName,
                             double spawnChance, List<String> worlds, List<String> biomes, List<String> zones,
                             Double health, Double damage, Double speed, Double armor,
                             Double knockbackResistance, Double scale, Double creeperExplosionRadius,
                             String particle, String sound, Integer xpReward, Integer maxPopulation,
                             int alivePopulation, Double enragedHealthFraction, Double enragedSpeedMultiplier,
                             Double enragedDamageMultiplier, String summonEntityType, Integer summonAmount,
                             Double summonChance, Integer summonCooldownSeconds, Integer summonMaxAlive,
                             List<String> abilitiesSummary, String bossMode, int communityWeight,
                             List<String> participationRewards) {
    }

    /** Throttle global du tirage aléatoire Wild (voir {@code mob.MobSpawnSettings}). */
    record MobSpawnSettingsView(boolean enabled, double chance, Integer maxSimultaneousSpecial) {
    }

    record MobCatalogView(List<MobProfileSummary> profiles, MobSpawnSettingsView spawnSettings,
                          boolean hasIssues, List<String> issues) {
    }

    CompletableFuture<MobCatalogView> mobDefinitions();

    /**
     * Crée/modifie un profil de mob spécial ({@code mobs/<id>.yml}) -- action {@code mob.definition.create}
     * / {@code mob.definition.update}. Jamais de YAML brut, jamais de chemin arbitraire ; échoue sans
     * écraser si l'id existe déjà (create) ou n'existe pas (update). Les 9 derniers paramètres portent
     * les deux capacités du premier lot ({@code null} = capacité absente) ; les autres champs
     * d'ability restent hors périmètre de ce formulaire (voir {@link MobProfileSummary}).
     */
    CompletableFuture<MutationResult> mobDefinitionCreate(String id, String category, boolean enabled,
            String entityType, String displayName, double spawnChance, List<String> worlds, List<String> biomes,
            List<String> zones, Double health, Double damage, Double speed, Double armor,
            Double knockbackResistance, Double scale, Double creeperExplosionRadius, String particle, String sound,
            Integer xpReward, Integer maxPopulation, Double enragedHealthFraction, Double enragedSpeedMultiplier,
            Double enragedDamageMultiplier, String summonEntityType, Integer summonAmount, Double summonChance,
            Integer summonCooldownSeconds, Integer summonMaxAlive, String bossMode, Integer communityWeight,
            List<String> participationRewards);

    CompletableFuture<MutationResult> mobDefinitionUpdate(String id, String category, boolean enabled,
            String entityType, String displayName, double spawnChance, List<String> worlds, List<String> biomes,
            List<String> zones, Double health, Double damage, Double speed, Double armor,
            Double knockbackResistance, Double scale, Double creeperExplosionRadius, String particle, String sound,
            Integer xpReward, Integer maxPopulation, Double enragedHealthFraction, Double enragedSpeedMultiplier,
            Double enragedDamageMultiplier, String summonEntityType, Integer summonAmount, Double summonChance,
            Integer summonCooldownSeconds, Integer summonMaxAlive, String bossMode, Integer communityWeight,
            List<String> participationRewards);

    /** Bascule {@code enabled} sans toucher au reste du profil -- action {@code mob.definition.toggle}. */
    CompletableFuture<MutationResult> mobDefinitionToggle(String id, boolean enabled);

    /** Règle le throttle global (voir {@code MobSpawnSettings}) -- action {@code mob.spawn-settings.set}. */
    CompletableFuture<MutationResult> mobSpawnSettingsSet(boolean enabled, double chance, Integer maxSimultaneousSpecial);

    /**
     * Fait apparaître une instance de test d'un profil à une position sûre à proximité immédiate
     * d'un joueur connecté <strong>déjà présent dans le monde Wild configuré</strong> -- action
     * {@code mob.test.spawn}. Marquée distinctement (PDC dédiée) pour que {@link #mobTestClear} ne
     * supprime jamais un mob ordinaire. Échoue (lisible) si le joueur est hors ligne, hors du Wild,
     * ou si aucune position sûre n'a été trouvée à proximité.
     */
    CompletableFuture<MutationResult> mobTestSpawn(String definitionId, String playerName);

    /** Supprime uniquement les instances de test encore vivantes (toutes définitions) -- {@code mob.test.clear}. */
    CompletableFuture<MutationResult> mobTestClear();

    // ---- Boss communautaires (issue #175) --------------------------------------------------------

    /** Boss communautaire actif, tel que présenté dans PlugAdmin ({@code loaded} = zone actuellement chargée). */
    record CommunityBossInstanceView(String entityId, String profileId, String profileName, String world, int x,
                                     int y, int z, String biome, String waypointName, int waypointDistance,
                                     String waypointDirection, long spawnedAtMillis, boolean test) {
    }

    /** Profil BOSS et son mode, pour la liste des profils éligibles. */
    record CommunityBossProfileView(String id, String displayName, boolean enabled, String bossMode,
                                    int communityWeight, List<String> worlds, List<String> biomes,
                                    List<String> participationRewards) {
    }

    /** Issue #176 : participant d'une victoire et son verdict ({@code reason} vide si éligible). */
    record CommunityBossParticipantView(String uuid, String name, boolean eligible, String reason) {
    }

    /**
     * Issue #176 : victoire récente (mémoire, depuis le dernier démarrage du serveur) ; {@code radius} =
     * distance de participation appliquée à cette victoire.
     */
    record CommunityBossDefeatView(String entityId, String profileId, String profileName, long defeatedAtMillis,
                                   String world, String biome, int radius, String killerName, boolean test,
                                   List<CommunityBossParticipantView> participants, List<String> rewardKeys,
                                   String rewardStatus, List<String> draws) {
    }

    /** {@code available=false} : plugin sans le service (ancienne version) -- jamais un état inventé. */
    record CommunityBossView(boolean available, boolean enabled, int intervalMinutes, int maxActive,
                             int participationRadius, Long nextDueMillis, long lastSpawnMillis, long lastAttemptMillis, String lastOutcome,
                             long nowMillis, List<CommunityBossInstanceView> instances,
                             List<CommunityBossProfileView> bossProfiles,
                             List<CommunityBossDefeatView> recentDefeats, List<RewardTableView> rewardTables,
                             boolean glow, int glowRadius, boolean surfaceOnly) {
    }

    /** Une entrée d'une table de tirage (#177) ; {@code chance} lisible (« 12,5 % »). */
    record RewardTableEntryView(String id, String rewardKey, String rewardLabel, boolean rewardKnown,
                                String chance, String chanceValue) {

        /** Forme antérieure à #177 tranche 2 : sans la valeur éditable ({@code 12.5}). */
        RewardTableEntryView(String id, String rewardKey, String rewardLabel, boolean rewardKnown, String chance) {
            this(id, rewardKey, rewardLabel, rewardKnown, chance, "");
        }
    }

    /**
     * Une table de récompenses aléatoires de {@code entitlements.yml} (#177), en lecture : mode,
     * version (empreinte figée dans chaque gain) et explication des chances réelles.
     */
    record RewardTableView(String id, String label, String mode, String modeLabel, boolean enabled, String version,
                           String totalChance, String explanation, List<RewardTableEntryView> entries) {
    }

    /** Lecture de l'état du scheduler -- action {@code boss.community.status}. */
    default CompletableFuture<CommunityBossView> communityBossStatus() {
        return CompletableFuture.completedFuture(new CommunityBossView(false, false, 0, 0, 0, null, 0, 0, "", 0,
                List.of(), List.of(), List.of(), List.of(), false, 0, false));
    }

    /**
     * Réglages globaux -- action {@code boss.community.settings.set}. {@code participationRadius}
     * {@code null} = distance de participation inchangée (#176) ; de même {@code glow},
     * {@code glowRadius} et {@code surfaceOnly} {@code null} = inchangés (#471, panel plus ancien).
     */
    default CompletableFuture<MutationResult> communityBossSettingsSet(boolean enabled, int intervalMinutes,
                                                                       int maxActive, Integer participationRadius,
                                                                       Boolean glow, Integer glowRadius,
                                                                       Boolean surfaceOnly) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNAVAILABLE",
                "Boss communautaires indisponibles sur ce serveur."));
    }

    /**
     * Apparition de test immédiate -- action {@code boss.community.test.spawn}. {@code profileId} et
     * {@code playerName} facultatifs ({@code null} = tirage pondéré / n'importe où dans le Wild, sans
     * joueur requis -- #471).
     */
    default CompletableFuture<MutationResult> communityBossTestSpawn(String profileId, String playerName) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNAVAILABLE",
                "Boss communautaires indisponibles sur ce serveur."));
    }

    /** Retire un boss ({@code entityId}) ou toutes les apparitions de test ({@code "tests"}) -- {@code boss.community.remove}. */
    default CompletableFuture<MutationResult> communityBossRemove(String target) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNAVAILABLE",
                "Boss communautaires indisponibles sur ce serveur."));
    }

    // ---- Exploitation serveur (issue #95) ------------------------------------------------------

    /**
     * Diffuse une annonce à tous les joueurs connectés ({@code server.announce}).
     *
     * <p>Le message est envoyé comme <strong>texte</strong> : il n'est ni exécuté comme commande,
     * ni interprété comme du MiniMessage. Un {@code <click:run_command:…>} glissé dans une annonce
     * ferait exécuter une commande à tous les joueurs qui cliquent — cette porte reste fermée.</p>
     *
     * @param channel {@code chat} / {@code actionbar} / {@code title} — les seuls canaux réellement
     *                supportés par l'API publique Paper/Adventure
     */
    CompletableFuture<AnnounceResult> announce(String message, String channel);

    /**
     * Résultat d'une annonce.
     *
     * @param code       {@code SENT} / {@code NO_PLAYERS} / {@code INVALID_MESSAGE} /
     *                   {@code INVALID_CHANNEL} / {@code ERROR}
     * @param recipients joueurs qui l'ont <strong>réellement</strong> reçue
     * @param online     joueurs connectés au moment de l'envoi. {@code 0} signifie « personne n'a
     *                   rien vu » : c'est dit comme tel, jamais présenté comme un succès trompeur
     */
    record AnnounceResult(boolean ok, String code, String message, String channel,
                          int recipients, int online) {
    }

    /**
     * Dernières lignes de console captées par le plugin ({@code server.logs.tail}) — lecture seule,
     * bornée, sans effet de bord.
     *
     * <p>Ce n'est <strong>pas</strong> le fichier de log de l'hébergeur : celui-ci n'est pas
     * atteignable (racine FTP = {@code plugins/}, remontée de dossier refusée par le serveur FTP,
     * mesuré). C'est la sortie Log4j du serveur telle que le plugin la capte, ce qui couvre vanilla,
     * Citizens, WorldEdit, Multiverse et RPGQuest.</p>
     *
     * @param afterSequence curseur de lecture ({@code 0} = début de consultation : on veut la fin
     *                      du tampon, pas son début)
     */
    CompletableFuture<ServerLogsView> serverLogs(long afterSequence, int limit);

    /**
     * @param lines         lignes, ordre chronologique
     * @param firstSequence plus ancienne séquence encore conservée
     * @param lastSequence  dernière séquence connue du serveur (curseur à renvoyer ensuite)
     * @param dropped       lignes évincées depuis le démarrage (tampon circulaire)
     * @param capacity      capacité du tampon
     * @param gap           {@code true} = des lignes manquent entre le curseur demandé et ce qui est
     *                      renvoyé ; l'interface doit le dire plutôt que simuler un flux continu
     * @param limitation    motif d'indisponibilité de la capture, ou {@code null} si elle fonctionne
     */
    record ServerLogsView(List<ServerLogLine> lines, long firstSequence, long lastSequence,
                          long dropped, int capacity, boolean gap, String limitation) {
    }

    record ServerLogLine(long sequence, long epochMillis, String level, String source, String message) {
    }

    // ---- Rechargement du contenu (issue #131) --------------------------------------------------

    /**
     * Valide ou applique un rechargement de contenu dans le runtime ({@code content.reload.preview}
     * / {@code content.reload}).
     *
     * <p>Jamais un {@code /reload} Bukkit. Le service central valide d'abord chaque famille en
     * dry-run, puis les références croisées du graphe candidat, et n'applique que si tout passe :
     * une erreur laisse donc l'ancien runtime valide en place, jamais un état partiellement
     * chargé.</p>
     *
     * @param families jetons de famille ({@code quests}, {@code stories}, {@code dialogues},
     *                 {@code npcs}, {@code items}, {@code mobs})
     * @param apply    {@code false} = aperçu seul, rien n'est modifié
     */
    CompletableFuture<ContentReloadView> contentReload(List<String> families, boolean apply);

    /**
     * @param applied        {@code true} = le runtime a réellement changé (jamais pour un aperçu)
     * @param code           {@code APPLIED} / {@code PREVIEW_OK} / {@code INVALID_CONTENT} /
     *                       {@code BROKEN_REFERENCES} / {@code BUSY} / {@code NOTHING_REQUESTED} /
     *                       {@code UNKNOWN_FAMILY} / {@code ERROR}
     * @param runtimeHash    empreinte du contenu chargé : permet au panel de <strong>constater</strong>
     *                       un changement au lieu de le supposer
     * @param restartRequired une partie de la demande ne peut pas être prise à chaud
     */
    record ContentReloadView(boolean applied, String code, String message,
                             List<ContentReloadFamilyView> families, List<String> referenceErrors,
                             List<String> suggestedFamilies, String runtimeHash,
                             long durationMillis, boolean restartRequired) {
    }

    /**
     * @param ids identifiants présents <strong>sur le disque du serveur</strong> et valides. Permet
     *     au panel de distinguer « jamais publié sur VeryGames » (absent d'ici : un rechargement n'y
     *     changerait rien, il faut un déploiement) de « publié mais pas encore chargé » (présent
     *     ici, absent du runtime : un rechargement suffit).
     */
    record ContentReloadFamilyView(String family, String label, int loaded, int issues,
                                   List<String> messages, List<String> ids) {
    }

    // ---- Administration de joueur (issue #210) -------------------------------------------------

    /**
     * Accorde ou retire le statut <strong>OP Minecraft</strong> ({@code player.op} /
     * {@code player.deop}).
     *
     * <p><strong>OP Minecraft n'est ni un rôle PlugAdmin, ni un droit de construction, ni un bypass
     * de gameplay.</strong> Cette opération ne touche qu'{@code OfflinePlayer#setOp} : aucun rôle
     * de panel n'est modifié, aucun bypass n'est activé. Fonctionne pour un joueur
     * <strong>hors ligne</strong> connu du serveur.</p>
     *
     * <p>Idempotente, et le résultat est <strong>relu</strong> ({@code isOp()}) après écriture : un
     * code {@code ALREADY_OP} / {@code NOT_OP} signale un no-op, jamais un faux succès.</p>
     */
    CompletableFuture<MutationResult> setOperator(UUID playerId, String playerName, boolean op);

    /**
     * Renvoie un joueur <strong>connecté</strong> à une position sûre du Hub ({@code player.send.hub}),
     * via le même mécanisme que la Pierre de retour et le filet de sécurité des claims — le spawn du
     * village résolu, jamais une coordonnée figée.
     *
     * <p>Préserve <strong>tout</strong> : inventaire, Acte de propriété, claim, progression. Aucun
     * reset, aucune récompense, aucune variable touchée. Refuse clairement si le joueur est hors
     * ligne ou si la destination ne se résout pas.</p>
     */
    CompletableFuture<MutationResult> sendToHub(UUID playerId, String playerName);

    /** Expulse un joueur <strong>connecté</strong> avec une raison affichée ({@code player.kick}). */
    CompletableFuture<MutationResult> kickPlayer(UUID playerId, String playerName, String reason);

    /**
     * Ajoute ou retire un joueur de la whitelist ({@code player.whitelist.add} /
     * {@code player.whitelist.remove}).
     *
     * <p>Le résultat précise si la whitelist est <strong>réellement appliquée</strong> par le
     * serveur : l'ajouter alors qu'elle est désactivée n'a aucun effet visible, et le dire vaut
     * mieux que laisser croire à une protection active.</p>
     */
    CompletableFuture<MutationResult> setWhitelisted(UUID playerId, String playerName, boolean whitelisted);
    // ---- Monnaie (issue #140) ------------------------------------------------------------------

    /**
     * Solde réel d'un joueur et ses dernières transactions ({@code economy.balance}) — lecture
     * seule.
     *
     * <p>Le portefeuille persistant est l'<strong>unique source de vérité</strong> du solde ; la
     * table des transactions en est le journal. Aucun objet d'inventaire n'est consulté ni
     * interprété comme de la monnaie.</p>
     */
    CompletableFuture<EconomyBalanceView> economyBalance(UUID playerId, int historyLimit);

    /**
     * @param balance solde courant
     * @param history dernières transactions, les plus récentes d'abord
     */
    record EconomyBalanceView(boolean ok, String message, long balance, List<EconomyLedgerView> history) {
    }

    /** {@code amount} est <strong>signé</strong> : négatif = débit, positif = crédit. */
    record EconomyLedgerView(String type, long amount, String context, String at) {
    }

    /**
     * Crédite ou débite un joueur depuis l'administration ({@code economy.credit} /
     * {@code economy.debit}).
     *
     * <p><strong>Un débit ne peut jamais rendre le solde négatif</strong> : si les fonds sont
     * insuffisants, rien n'est modifié et le résultat le dit ({@code INSUFFICIENT_FUNDS}) — ce
     * n'est pas une erreur technique mais un refus métier lisible.</p>
     *
     * <p>La <strong>raison est obligatoire</strong> et finit dans le journal des transactions :
     * sans elle, une création de monnaie serait indiscernable d'un gain de jeu six mois plus tard.
     * Le solde après opération est <strong>relu</strong> et renvoyé.</p>
     */
    CompletableFuture<EconomyAdjustView> economyAdjust(UUID playerId, String playerName, long amount,
                                                      boolean credit, String reason);

    /**
     * @param code            {@code CREDITED} / {@code DEBITED} / {@code INSUFFICIENT_FUNDS} /
     *                        {@code INVALID_AMOUNT} / {@code UNKNOWN_PLAYER} / {@code ERROR}
     * @param balanceBefore   solde avant l'opération
     * @param balanceAfter    solde <strong>relu</strong> après l'opération
     */
    record EconomyAdjustView(boolean ok, String code, String message,
                             long balanceBefore, long balanceAfter) {
    }

    // ---- Récompenses monétaires restées dues (issue #16, second lot) ---------------------------

    /**
     * Récompenses monétaires de quête <strong>encore dues</strong> pour un joueur
     * ({@code economy.debts}) — lecture seule.
     *
     * <p>Une dette naît avec la complétion, dans la même transaction qu'elle, et survit donc à un
     * crash ou à une panne SQL. Son montant est <strong>figé</strong> à ce moment : rééditer la
     * quête ensuite ne change pas ce qui est dû.</p>
     */
    CompletableFuture<QuestRewardDebtsView> questRewardDebts(UUID playerId, int limit);

    /**
     * @param ok      {@code false} seulement en cas d'échec technique de lecture
     * @param debts   dettes encore dues, les plus anciennes d'abord
     */
    record QuestRewardDebtsView(boolean ok, String message, List<QuestRewardDebtView> debts) {
    }

    /**
     * @param grantId   identité de paiement <strong>initiale</strong> — une reprise la réutilise
     * @param attempts  nombre d'échecs déjà enregistrés ({@code 0} = jamais échouée)
     * @param lastError dernier motif d'échec, ou {@code null}
     */
    record QuestRewardDebtView(String grantId, String questId, String questTitle, int occurrence,
                               int rewardIndex, long amount, int attempts, String lastError,
                               String createdAt) {
    }

    /**
     * Reprend le paiement d'une récompense due ({@code economy.debt.retry}).
     *
     * <p>Réutilise l'identité de paiement initiale et le montant enregistré : une reprise ne peut
     * donc <strong>jamais</strong> payer deux fois, ni payer un montant différent de ce qui était
     * dû. Codes : {@code PAID}, {@code ALREADY_PAID}, {@code ALREADY_SETTLED}, {@code UNKNOWN_DEBT},
     * {@code WRONG_PLAYER}, {@code ERROR}.</p>
     */
    CompletableFuture<QuestRewardRetryView> retryQuestRewardDebt(UUID playerId, String grantId);

    /**
     * @param balanceAfter solde <strong>relu</strong> après l'opération ({@code 0} si rien n'a été
     *                     crédité)
     */
    record QuestRewardRetryView(boolean ok, String code, String message, long amount, long balanceAfter) {
    }

    /**
     * Marque une récompense due comme <strong>réglée à la main</strong> ({@code economy.debt.settle}).
     *
     * <p>Ne touche <strong>pas</strong> au portefeuille, et c'est tout l'intérêt : l'administrateur
     * a déjà compensé le joueur comme il l'entendait, et cette opération empêche la même récompense
     * d'être payée une seconde fois par une reprise ultérieure. La raison est
     * <strong>obligatoire</strong> et conservée avec la ligne.</p>
     */
    CompletableFuture<MutationResult> settleQuestRewardDebt(UUID playerId, String grantId, String reason);

    // ---- Pont vers les droits Minecraft (issue #200) -------------------------------------------

    /**
     * État réel du pont pour un joueur ({@code mc.rights.read}) — lecture seule.
     *
     * <p>Renvoie la disponibilité de LuckPerms <strong>et</strong> les droits gérés réellement
     * portés, avec le groupe du pont d'où ils viennent. Si le pont est indisponible, c'est dit avec
     * son motif : jamais un état voulu présenté comme un état réel.</p>
     */
    CompletableFuture<McRightsView> mcRightsRead(UUID playerId);

    /**
     * @param bridgeAvailable {@code false} si LuckPerms est absent ou incompatible
     * @param reason          motif d'indisponibilité, ou {@code null}
     * @param effective       droits gérés réellement portés, chacun avec sa provenance
     */
    record McRightsView(boolean ok, String message, boolean bridgeAvailable, String reason,
                        List<String> effective) {
    }

    /**
     * Pousse la définition d'un groupe du pont ({@code mc.group.sync}) : ses droits gérés, avec
     * contexte de monde. Idempotente.
     *
     * @param nodes couples {@code nœud} / {@code monde} ({@code monde} vide = partout)
     */
    CompletableFuture<McSyncView> mcGroupSync(String groupId, String displayName, List<McNodeSpec> nodes);

    /** Un droit à écrire. {@code world} vide ou {@code null} = partout. */
    record McNodeSpec(String node, String world) {
    }

    /** Retire un groupe du pont ({@code mc.group.delete}). */
    CompletableFuture<McSyncView> mcGroupDelete(String groupId);

    /**
     * Fait correspondre les appartenances du pont d'un joueur à {@code groupIds}
     * ({@code mc.rights.sync}).
     */
    CompletableFuture<McSyncView> mcRightsSync(UUID playerId, List<String> groupIds);

    /**
     * @param added     ce qui a été ajouté par cet appel
     * @param removed   ce qui a été retiré
     * @param unchanged ce qui était déjà conforme — preuve d'idempotence
     * @param preserved droits et appartenances <strong>externes</strong> laissés intacts
     */
    record McSyncView(boolean ok, String message, List<String> added, List<String> removed,
                      List<String> unchanged, List<String> preserved) {
    }

}
