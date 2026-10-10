package com.lodygames.rpgquest.web.agent;

import com.lodygames.rpgquest.dialogue.DialogueDefinitionEditor;
import com.lodygames.rpgquest.player.PlayerResetService;
import java.util.List;
import java.util.Map;
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
                        List<RewardSummary> rewardDetails, String giverId, String giverName) {
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

    record ObjectiveState(String description, int current, int required) {
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
                                    String verifiedAt) {
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
    record BuildingSiteSummary(String id, String name, String description, String world,
                               int x, int y, int z, String facing, String status,
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
                                     String schematic, boolean schematicPresent, int version) {
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

    CompletableFuture<BuildingSiteCatalogView> buildingSites();

    /**
     * Renomme un emplacement — libellé humain uniquement. L'identifiant, la position et
     * l'orientation ne bougent pas.
     */
    CompletableFuture<MutationResult> buildingSiteRename(String id, String name);

    /** Remplace la note libre d'un emplacement. Une description vide est une valeur valide. */
    CompletableFuture<MutationResult> buildingSiteDescribe(String id, String description);

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
     */
    record CitizensNpcSummary(int numericId, String uuid, String name, String linkedNpcId,
                              boolean availableForBinding, boolean spawned,
                              String world, Double x, Double y, Double z, Float yaw, Float pitch,
                              boolean liveLocation, boolean shouldSpawn, boolean chunkLoaded,
                              Boolean lookCloseEnabled, Double lookCloseRange,
                              Boolean wanderEnabled, String wanderProvider, Integer wanderWaypoints,
                              String wanderWorld, Double wanderX, Double wanderY, Double wanderZ,
                              Integer wanderXRange, Integer wanderYRange) {

        /** Entrée sans position connue — les chemins qui n'en ont pas besoin, et les tests. */
        CitizensNpcSummary(int numericId, String uuid, String name, String linkedNpcId,
                           boolean availableForBinding, boolean spawned) {
            this(numericId, uuid, name, linkedNpcId, availableForBinding, spawned,
                    null, null, null, null, null, null, false, spawned, false,
                    null, null, null, null, null, null, null, null, null, null, null);
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
                             List<String> abilitiesSummary) {
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
            Integer summonCooldownSeconds, Integer summonMaxAlive);

    CompletableFuture<MutationResult> mobDefinitionUpdate(String id, String category, boolean enabled,
            String entityType, String displayName, double spawnChance, List<String> worlds, List<String> biomes,
            List<String> zones, Double health, Double damage, Double speed, Double armor,
            Double knockbackResistance, Double scale, Double creeperExplosionRadius, String particle, String sound,
            Integer xpReward, Integer maxPopulation, Double enragedHealthFraction, Double enragedSpeedMultiplier,
            Double enragedDamageMultiplier, String summonEntityType, Integer summonAmount, Double summonChance,
            Integer summonCooldownSeconds, Integer summonMaxAlive);

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
