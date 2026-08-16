package com.lodygames.rpgquest.story;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.StoryProgressRepository;
import com.lodygames.rpgquest.story.model.StoryDefinition;
import com.lodygames.rpgquest.story.model.StoryState;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;

/**
 * Orchestre la progression Story (démarrage/consultation/réinitialisation) — délibérément
 * indépendant du moteur de quête existant : aucune référence à {@code quest.progress} ici,
 * {@code questIds} d'une {@link StoryDefinition} n'est jamais résolu contre {@code
 * YamlQuestEngine}/{@code QuestProgressEngine}. Aucun cache mémoire : chaque opération lit/écrit
 * directement {@link StoryProgressRepository} (SQLite), un choix délibéré pour un outil
 * admin/debug peu fréquent — pas un chemin chaud comme {@code QuestProgressEngine}.
 */
public final class StoryService implements PluginService {

    private final StoryRegistry registry;
    private final StoryProgressRepository progressRepository;
    private final PlayerProfileRepository profileRepository;
    private final Logger logger;

    public StoryService(StoryRegistry registry, StoryProgressRepository progressRepository,
                         PlayerProfileRepository profileRepository, Logger logger) {
        this.registry = registry;
        this.progressRepository = progressRepository;
        this.profileRepository = profileRepository;
        this.logger = logger;
    }

    @Override
    public void start() {
        // Rien à démarrer : le chargement des définitions est StoryRegistry (service séparé), la
        // progression est lue/écrite à la demande, jamais préchargée.
    }

    @Override
    public void stop() {
        // Rien à libérer : aucun état mémoire, aucune ressource externe possédée ici.
    }

    public List<StoryDefinition> stories() {
        return registry.stories();
    }

    public record StoryInfo(StoryDefinition story, StoryState state) {
    }

    /** Un {@link StoryInfo} par story connue du registre, dans l'ordre chargé — {@code NOT_STARTED} si aucune ligne. */
    public CompletableFuture<List<StoryInfo>> info(UUID playerId) {
        return progressRepository.findAll(playerId).thenApply(states -> registry.stories().stream()
                .map(story -> new StoryInfo(story, states.getOrDefault(story.id(), StoryState.NOT_STARTED)))
                .toList());
    }

    public enum StartOutcome {
        STARTED, ALREADY_ACTIVE, ALREADY_COMPLETED, UNKNOWN_STORY
    }

    /**
     * Démarre {@code storyId} pour {@code playerId} — {@code playerName} sert uniquement à
     * créer/rafraîchir le profil joueur ({@code player_profiles}, contrainte de clé étrangère de
     * {@code story_progress}) si la cible est hors ligne et n'a pas encore de profil.
     */
    public CompletableFuture<StartOutcome> start(UUID playerId, String playerName, String storyId) {
        Optional<StoryDefinition> story = registry.find(storyId);
        if (story.isEmpty()) {
            return CompletableFuture.completedFuture(StartOutcome.UNKNOWN_STORY);
        }
        return progressRepository.find(playerId, storyId).thenCompose(existing -> {
            if (existing.isPresent() && existing.get() == StoryState.ACTIVE) {
                return CompletableFuture.completedFuture(StartOutcome.ALREADY_ACTIVE);
            }
            if (existing.isPresent() && existing.get() == StoryState.COMPLETED) {
                return CompletableFuture.completedFuture(StartOutcome.ALREADY_COMPLETED);
            }
            return profileRepository.findOrCreate(playerId, playerName)
                    .thenCompose(profile -> progressRepository.upsertState(playerId, storyId, StoryState.ACTIVE))
                    .thenApply(v -> {
                        logger.info("Story « {} » démarrée pour {} ({}).", storyId, playerName, playerId);
                        return StartOutcome.STARTED;
                    });
        });
    }

    /**
     * Réservé aux étapes futures (chapitres/déblocages) : personne n'appelle encore cette méthode,
     * mais l'état {@code COMPLETED} doit déjà être atteignable sans migration supplémentaire.
     * Contrairement à {@link #start}, ne crée jamais le profil joueur (pas de {@code playerName}) —
     * un futur appelant aura toujours affaire à un joueur déjà en ligne (donc déjà profilé via
     * {@code PlayerConnectionListener}), jamais une cible hors ligne comme les commandes admin.
     */
    public CompletableFuture<Void> markCompleted(UUID playerId, String storyId) {
        return progressRepository.upsertState(playerId, storyId, StoryState.COMPLETED)
                .thenRun(() -> logger.info("Story « {} » marquée terminée pour {}.", storyId, playerId));
    }

    public enum ResetOutcome {
        RESET_ONE, RESET_ALL, UNKNOWN_STORY
    }

    /**
     * {@code storyIdOrAll = "all"} (insensible à la casse) supprime toute progression Story du
     * joueur ; sinon cible une seule story, dont l'id doit exister dans le registre (évite de
     * supprimer silencieusement une ligne à cause d'une faute de frappe). Ne touche jamais à
     * {@code quest_progress}, à l'inventaire ni à l'économie — voir {@link StoryProgressRepository}.
     */
    public CompletableFuture<ResetOutcome> reset(UUID playerId, String storyIdOrAll) {
        if ("all".equals(storyIdOrAll.toLowerCase(Locale.ROOT))) {
            return progressRepository.deleteAllForPlayer(playerId).thenApply(v -> {
                logger.info("Toute la progression Story de {} a été réinitialisée.", playerId);
                return ResetOutcome.RESET_ALL;
            });
        }
        if (registry.find(storyIdOrAll).isEmpty()) {
            return CompletableFuture.completedFuture(ResetOutcome.UNKNOWN_STORY);
        }
        return progressRepository.deleteStory(playerId, storyIdOrAll).thenApply(v -> {
            logger.info("Story « {} » réinitialisée pour {}.", storyIdOrAll, playerId);
            return ResetOutcome.RESET_ONE;
        });
    }
}
