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
}
