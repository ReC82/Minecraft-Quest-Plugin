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
    public ContentExportResult exportContent(String family, List<String> ids) {
        String yaml = "format: lodyquests-content-pack\nschemaVersion: 1\ncontent:\n"
                + "  quests: []\n  stories: []\n  dialogues: []\n  npcs: []\n";
        return new ContentExportResult(true, "OK", "lodyquests-content-pack", 1,
                family == null ? "all" : family, java.util.Map.of(), 0, yaml);
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
    public CompletableFuture<BuildingSiteCatalogView> buildingSites() {
        return CompletableFuture.completedFuture(
                new BuildingSiteCatalogView(List.of(), List.of(), 0, List.of()));
    }

    @Override
    public CompletableFuture<MutationResult> buildingSiteRename(String id, String name) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> buildingSiteDescribe(String id, String description) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> buildingSiteFacing(String id, String facing) {
        return unsupported();
    }

    @Override
    public CompletableFuture<DevContentFileText> contentDevRead(String kind, String slug) {
        return CompletableFuture.completedFuture(new DevContentFileText(
                kind == null ? "" : kind, slug == null ? "" : slug, false, false, "", ""));
    }

    @Override
    public CompletableFuture<DevContentStateView> contentDevState() {
        return CompletableFuture.completedFuture(
                new DevContentStateView(List.of(), java.util.Map.of(), "stub"));
    }

    @Override
    public CompletableFuture<ContentPublishResultView> contentPublish(String kind, String slug,
                                                                      String yaml,
                                                                      String expectedDevSha,
                                                                      String expectedId) {
        return CompletableFuture.completedFuture(new ContentPublishResultView(false, "STUB",
                "stub", kind, slug, "", "", "", "", false, "", false, "", "", 0, 0, false, "", ""));
    }

    @Override
    public CompletableFuture<ContentPublishResultView> contentPublishRollback(String kind,
                                                                              String slug,
                                                                              String backupPath,
                                                                              String expectedId) {
        return CompletableFuture.completedFuture(new ContentPublishResultView(false, "STUB",
                "stub", kind, slug, "", "", "", "", false, "", false, "", "", 0, 0, false, "", ""));
    }

    @Override
    public CompletableFuture<BuildingLibraryView> buildingLibrary() {
        return CompletableFuture.completedFuture(
                new BuildingLibraryView(List.of(), List.of(), false, "stub"));
    }

    @Override
    public CompletableFuture<BuildingPreviewView> buildingPlacementPreview(String siteId,
                                                                           String buildingId) {
        return CompletableFuture.completedFuture(new BuildingPreviewView(false, siteId, "", "", "",
                0, 0, 0, buildingId, "", 0, 0, 0, "", 0, 0, 0, 0, 0, 0, 0, 0L, -1L,
                List.of("stub"), List.of()));
    }

    @Override
    public CompletableFuture<MutationResult> buildingPlacementPlace(String siteId,
                                                                    String buildingId,
                                                                    String placedBy) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "STUB", "stub"));
    }

    @Override
    public CompletableFuture<MutationResult> buildingPlacementRollback(String siteId) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "STUB", "stub"));
    }

    @Override
    public CompletableFuture<BuildingRetargetView> buildingRetargetPreview(String siteId,
                                                                           String buildingId,
                                                                           String facing) {
        return CompletableFuture.completedFuture(new BuildingRetargetView(false, "ROTATE",
                siteId, "", "", "", "", 0, "", 0L, buildingId, "", 0, "", 0L, 0, 0, 0, -1L, false,
                "", false, List.of("stub"), List.of(), ""));
    }

    @Override
    public CompletableFuture<MutationResult> buildingReorient(String siteId, String facing,
                                                               String actor, String token) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "STUB", "stub"));
    }

    @Override
    public CompletableFuture<MutationResult> buildingReplace(String siteId, String buildingId,
                                                              String facing, String actor,
                                                              String token) {
        return CompletableFuture.completedFuture(MutationResult.of(false, "STUB", "stub"));
    }

    @Override
    public CompletableFuture<BuildingHistoryView> buildingHistory(String siteId) {
        return CompletableFuture.completedFuture(new BuildingHistoryView(siteId, List.of()));
    }

    @Override
    public CompletableFuture<MutationResult> buildingSiteDelete(String id) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> npcDefinitionDelete(String id, String expectDialogueId) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> npcCitizensUnlink(String npcId, int expectedCitizensId) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> npcCitizensDelete(String npcId, int expectedCitizensId) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> questGiverSet(String questId, String npcId) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MobCatalogsView> mobCatalogs() {
        return CompletableFuture.completedFuture(new MobCatalogsView(
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), "", java.util.List.of()));
    }

    @Override
    public CompletableFuture<MutationResult> contentDefinitionDelete(String kind, String id) {
        return unsupported();
    }

    @Override
    public CompletableFuture<ItemCatalogsView> itemCatalogs() {
        return CompletableFuture.completedFuture(new ItemCatalogsView(
                java.util.List.of(), java.util.List.of(), "", 0));
    }

    @Override
    public CompletableFuture<MutationResult> citizensRename(String npcId, String newName) {
        return unsupported();
    }

    @Override
    public CompletableFuture<CitizensProvisionResult> citizensProvision(
            String displayName, String skinValue, boolean skinByPlayerName,
            String world, Double x, Double y, Double z, Float yaw, Float pitch) {
        return CompletableFuture.completedFuture(
                CitizensProvisionResult.reject("UNSUPPORTED", "non implémenté dans ce stub"));
    }

    @Override
    public CompletableFuture<MutationResult> citizensMove(String npcId, String world,
                                                          double x, double y, double z,
                                                          float yaw, float pitch) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> citizensSkin(String npcId, String value, boolean byPlayerName) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> citizensLookClose(String npcId, boolean enabled, Double range) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> citizensWander(String npcId, boolean enabled,
                                                            String world, Double x, Double y, Double z,
                                                            int xRange, int yRange, boolean confirmReplace) {
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
    public CompletableFuture<MutationResult> dialogueChoiceUpdate(
            String dialogueId, String nodeId, int choiceIndex, String choiceText, String nextNodeId, boolean close,
            com.lodygames.rpgquest.dialogue.DialogueDefinitionEditor.QuestActionEdit questAction,
            com.lodygames.rpgquest.dialogue.DialogueDefinitionEditor.QuestConditionEdit questCondition) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> dialogueChoiceDelete(String dialogueId, String nodeId, int choiceIndex) {
        return unsupported();
    }

    @Override
    public CompletableFuture<ResetPreview> resetPreview(UUID playerId,
                                                        com.lodygames.rpgquest.player.PlayerResetService.ResetScope scope) {
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
    public CompletableFuture<MutationResult> resetConfirm(UUID playerId, String playerName,
                                                           com.lodygames.rpgquest.player.PlayerResetService.ResetScope scope) {
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

    @Override
    public CompletableFuture<TravelCatalogView> travelCatalog() {
        return CompletableFuture.completedFuture(
                new TravelCatalogView(List.of(), List.of(), 0, 0, 0, List.of(), 0L));
    }

    @Override
    public CompletableFuture<MobCatalogView> mobDefinitions() {
        return CompletableFuture.completedFuture(
                new MobCatalogView(List.of(), new MobSpawnSettingsView(true, 1.0, null), false, List.of()));
    }

    @Override
    public CompletableFuture<MutationResult> mobDefinitionCreate(String id, String category, boolean enabled,
            String entityType, String displayName, double spawnChance, List<String> worlds, List<String> biomes,
            List<String> zones, Double health, Double damage, Double speed, Double armor,
            Double knockbackResistance, Double scale, Double creeperExplosionRadius, String particle, String sound,
            Integer xpReward, Integer maxPopulation, Double enragedHealthFraction, Double enragedSpeedMultiplier,
            Double enragedDamageMultiplier, String summonEntityType, Integer summonAmount, Double summonChance,
            Integer summonCooldownSeconds, Integer summonMaxAlive) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> mobDefinitionUpdate(String id, String category, boolean enabled,
            String entityType, String displayName, double spawnChance, List<String> worlds, List<String> biomes,
            List<String> zones, Double health, Double damage, Double speed, Double armor,
            Double knockbackResistance, Double scale, Double creeperExplosionRadius, String particle, String sound,
            Integer xpReward, Integer maxPopulation, Double enragedHealthFraction, Double enragedSpeedMultiplier,
            Double enragedDamageMultiplier, String summonEntityType, Integer summonAmount, Double summonChance,
            Integer summonCooldownSeconds, Integer summonMaxAlive) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> mobDefinitionToggle(String id, boolean enabled) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> mobSpawnSettingsSet(boolean enabled, double chance, Integer maxSimultaneousSpecial) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> mobTestSpawn(String definitionId, String playerName) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> mobTestClear() {
        return unsupported();
    }

    @Override
    public CompletableFuture<AnnounceResult> announce(String message, String channel) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("non utilisé"));
    }

    @Override
    public CompletableFuture<ServerLogsView> serverLogs(long afterSequence, int limit) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("non utilisé"));
    }

    @Override
    public CompletableFuture<ContentReloadView> contentReload(java.util.List<String> families, boolean apply) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("non utilisé"));
    }

    @Override
    public CompletableFuture<MutationResult> setOperator(java.util.UUID playerId, String playerName, boolean op) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> sendToHub(java.util.UUID playerId, String playerName) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> kickPlayer(java.util.UUID playerId, String playerName, String reason) {
        return unsupported();
    }

    @Override
    public CompletableFuture<MutationResult> setWhitelisted(java.util.UUID playerId, String playerName,
                                                            boolean whitelisted) {
        return unsupported();
    }

    @Override
    public CompletableFuture<EconomyBalanceView> economyBalance(java.util.UUID playerId, int historyLimit) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("non utilisé"));
    }

    @Override
    public CompletableFuture<EconomyAdjustView> economyAdjust(java.util.UUID playerId, String playerName,
                                                              long amount, boolean credit, String reason) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("non utilisé"));
    }

    @Override
    public CompletableFuture<QuestRewardDebtsView> questRewardDebts(java.util.UUID playerId, int limit) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("non utilisé"));
    }

    @Override
    public CompletableFuture<QuestRewardRetryView> retryQuestRewardDebt(java.util.UUID playerId, String grantId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("non utilisé"));
    }

    @Override
    public CompletableFuture<MutationResult> settleQuestRewardDebt(java.util.UUID playerId, String grantId,
                                                                   String reason) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("non utilisé"));
    }

    @Override
    public CompletableFuture<McRightsView> mcRightsRead(java.util.UUID playerId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("non utilisé"));
    }

    @Override
    public CompletableFuture<McSyncView> mcGroupSync(String groupId, String displayName,
                                                     java.util.List<McNodeSpec> nodes) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("non utilisé"));
    }

    @Override
    public CompletableFuture<McSyncView> mcGroupDelete(String groupId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("non utilisé"));
    }

    @Override
    public CompletableFuture<McSyncView> mcRightsSync(java.util.UUID playerId, java.util.List<String> groupIds) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("non utilisé"));
    }
}
