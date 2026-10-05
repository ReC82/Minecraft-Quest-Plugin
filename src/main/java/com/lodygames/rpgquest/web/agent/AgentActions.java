package com.lodygames.rpgquest.web.agent;

import java.util.List;
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
                              String world, Integer x, Integer y, Integer z) {
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
     */
    record ObjectiveSummary(String kind, String target, int amount, String raw) {
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
                      List<String> questsReferenced, List<String> sources, String state,
                      List<NpcWarning> warnings) {
    }

    /** Anomalie de configuration d'un PNJ. {@code severity} ∈ {@code error|warning|info}. */
    record NpcWarning(String code, String severity, String message) {
    }

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
    CompletableFuture<MutationResult> citizensSkin(String npcId, String minesSkinUrl);

    /**
     * Un PNJ Citizens du registre (action {@code npc.citizens.list}). {@code linkedNpcId} = id
     * logique RPGQuest déjà lié à ce PNJ, ou {@code null}. Aucune position/monde (registre seul).
     */
    record CitizensNpcSummary(int numericId, String uuid, String name, String linkedNpcId,
                              boolean availableForBinding, boolean spawned) {
    }

    record CitizensRosterView(boolean citizensAvailable, List<CitizensNpcSummary> citizens,
                              int total, int available, int linked) {
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

    // ---- Édition guidée d'un dialogue existant (issue #82 phase 1) -----------------------------
    //
    // Périmètre volontairement restreint : locuteur/texte d'un nœud, nœud simple, choix simple
    // (sans condition, sans action autre que « fermer »). Chaque écriture réécrit le fichier au
    // format canonique du panel, est re-parsée puis rechargée ; en cas d'échec le contenu d'origine
    // est restauré. Jamais de YAML brut, jamais de chemin — seulement des champs métier validés.

    /** Modifie le locuteur et le texte d'un nœud existant (les choix sont conservés). */
    CompletableFuture<MutationResult> dialogueNodeUpdate(String dialogueId, String nodeId, String speaker, String text);

    /** Ajoute un nœud simple (locuteur + texte + un choix « fermer ») ; nœud orphelin assumé. */
    CompletableFuture<MutationResult> dialogueNodeCreate(String dialogueId, String nodeId, String speaker, String text);

    /** Ajoute un choix simple à un nœud : texte + (redirection vers un nœud existant OU fermeture). */
    CompletableFuture<MutationResult> dialogueChoiceAdd(String dialogueId, String nodeId, String choiceText,
                                                        String nextNodeId, boolean close);

    /** Modifie le texte et la cible d'un choix simple existant (repéré par son index dans le nœud). */
    CompletableFuture<MutationResult> dialogueChoiceUpdate(String dialogueId, String nodeId, int choiceIndex,
                                                           String choiceText, String nextNodeId, boolean close);

    /** Supprime un choix simple (si le nœud garde au moins un choix). */
    CompletableFuture<MutationResult> dialogueChoiceDelete(String dialogueId, String nodeId, int choiceIndex);

    /** Aperçu (dry-run, aucune écriture) de ce qu'un reset « nouveau joueur » supprimerait. */
    record ResetPreviewLine(String label, int count, String detail) {
    }

    record ResetPreview(boolean online, List<ResetPreviewLine> lines) {
    }

    CompletableFuture<ResetPreview> resetPreview(UUID playerId);

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

    CompletableFuture<MutationResult> resetConfirm(UUID playerId, String playerName);

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
                           int x, int y, int z, boolean active, int modelVersion, String pairedBeaconId) {
    }

    /** Une borne <strong>persistée</strong> (table {@code travel_beacons}) — même réserve que {@link WaypointSummary}. */
    record BeaconSummary(String id, String world, int x, int y, int z, boolean active, int modelVersion,
                         boolean autoGenerated, String biomeInstance, String pairedWaypointId) {
    }

    /**
     * Vue complète renvoyée par {@code travel.catalog} : listes séparées waypoints/bornes (issue
     * #152), relation waypoint↔borne par instance de biome (jamais une fusion de table), compteurs,
     * instances Hub avec waypoint mais sans borne (réutilise
     * {@code travel.beacon.TravelBeaconService#hubWaypointsWithoutBeacon}), horodatage de
     * génération — la « fraîcheur » de cette vue, pas une confirmation que les structures existent
     * physiquement à l'instant où le panel l'affiche.
     */
    record TravelCatalogView(List<WaypointSummary> waypoints, List<BeaconSummary> beacons,
                             int waypointCount, int beaconCount, int autoGeneratedBeaconCount,
                             List<String> unpairedHubWaypointIds, long generatedAtEpochMs) {
    }

    CompletableFuture<TravelCatalogView> travelCatalog();

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
}
