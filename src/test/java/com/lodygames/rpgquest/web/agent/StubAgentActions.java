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
                new NpcCatalogView(List.of(), List.of(), List.of(), false, 0, 0, 0, 0, 0));
    }

    @Override
    public CompletableFuture<MutationResult> npcDefinitionCreate(String id, String displayName, String dialogueId,
                                                                 String role, boolean enabled) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> npcDefinitionUpdate(String id, String displayName, String dialogueId,
                                                                 String role, boolean enabled) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> questGiverSet(String questId, String npcId) {
        return unsupported();
    }

    @Override
    public CompletableFuture<CitizensRosterView> citizensRoster() {
        return CompletableFuture.completedFuture(new CitizensRosterView(false, List.of(), 0, 0, 0));
    }

    @Override
    public CompletableFuture<MutationResult> citizensLink(String npcId, int citizensNumericId) {
        return unsupported();
    }

    @Override
    public CompletableFuture<CitizensCreateResult> citizensCreate(String npcId, String world,
                                                                  double x, double y, double z,
                                                                  float yaw, float pitch) {
        return CompletableFuture.completedFuture(
                CitizensCreateResult.reject(npcId, "UNSUPPORTED", "non câblé (stub de test)"));
    }

    @Override
    public CompletableFuture<DialogueCatalogView> dialogueDefinitions() {
        return CompletableFuture.completedFuture(
                new DialogueCatalogView(List.of(), List.of(), List.of(), 0, 0, 0));
    }

    @Override
    public CompletableFuture<MutationResult> dialogueDefinitionCreate(String key, String speaker, String text) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> dialogueNodeUpdate(String dialogueId, String nodeId, String speaker,
                                                               String text) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> dialogueNodeCreate(String dialogueId, String nodeId, String speaker,
                                                               String text) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> dialogueChoiceAdd(String dialogueId, String nodeId, String choiceText,
                                                              String nextNodeId, boolean close) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> dialogueChoiceUpdate(String dialogueId, String nodeId, int choiceIndex,
                                                                 String choiceText, String nextNodeId, boolean close) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> dialogueChoiceDelete(String dialogueId, String nodeId, int choiceIndex) {
        return unsupported();
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

    @Override
    public CompletableFuture<List<PlayerCatalogEntry>> playerCatalog(int limit) {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletableFuture<MutationResult> banPlayer(UUID playerId, String playerName, String reason) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> unbanPlayer(UUID playerId, String playerName) {
        return unsupported();
    }
}
