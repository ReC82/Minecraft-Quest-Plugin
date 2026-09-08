package com.lodygames.rpgquest.web.agent;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Façade métier inerte pour les tests qui n'exercent pas les actions métier (ex. {@code AgentLoopTest},
 * centré sur le heartbeat / backoff / idempotence). Toutes les lectures renvoient du vide, toutes
 * les mutations renvoient un échec neutre — jamais d'exception.
 */
class StubAgentActions implements AgentActions {

    private static CompletableFuture<MutationResult> unsupported() {
        return CompletableFuture.completedFuture(MutationResult.of(false, "UNSUPPORTED", "non câblé (stub de test)"));
    }

    @Override
    public CompletableFuture<List<PlayerSummary>> onlinePlayers() {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public List<QuestSummary> questDefinitions() {
        return List.of();
    }

    @Override
    public CompletableFuture<List<QuestPlayerState>> questStatus(UUID playerId) {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public List<StorySummary> storyDefinitions() {
        return List.of();
    }

    @Override
    public CompletableFuture<List<StoryPlayerState>> storyStatus(UUID playerId) {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public List<ItemSummary> itemDefinitions() {
        return List.of();
    }

    @Override
    public CompletableFuture<NpcCatalogView> npcDefinitions() {
        return CompletableFuture.completedFuture(
                new NpcCatalogView(List.of(), List.of(), false, 0, 0, 0, 0));
    }

    @Override
    public CompletableFuture<ResetPreview> resetPreview(UUID playerId) {
        return CompletableFuture.completedFuture(new ResetPreview(false, List.of()));
    }

    @Override
    public CompletableFuture<MutationResult> questStart(UUID playerId, String questId, boolean force) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> questComplete(UUID playerId, String questId) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> questReset(UUID playerId, String questId) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> storyAdvance(UUID playerId, String storyId) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> storyComplete(UUID playerId, String storyId) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> giveItem(UUID playerId, String itemId, int amount) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> resetConfirm(UUID playerId, String playerName) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> variableSet(UUID playerId, String key, String value) {
        return unsupported();
    }
}
