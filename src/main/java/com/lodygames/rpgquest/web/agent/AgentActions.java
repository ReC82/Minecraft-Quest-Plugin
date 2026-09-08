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
}
