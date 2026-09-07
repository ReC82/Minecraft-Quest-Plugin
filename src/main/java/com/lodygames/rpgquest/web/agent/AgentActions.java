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
     */
    record QuestSummary(String id, String title, String category, boolean repeatable,
                        List<String> prerequisites, List<QuestStepSummary> steps, List<String> rewards) {
    }

    record QuestStepSummary(String id, List<String> objectives) {
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
