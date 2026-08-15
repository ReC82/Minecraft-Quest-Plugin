package com.lodygames.rpgquest.config;

public record PluginConfig(
        boolean debug,
        String locale,
        String databaseFile,
        ResourcePackConfig resourcePack,
        DialogueConfig dialogue,
        JournalConfig journal,
        AdminFlattenConfig adminFlatten,
        ClaimConfig claims,
        ProgressionConfig progression,
        BackpackConfig backpacks,
        WebExportConfig webExport,
        StoreConfig store,
        ModCompatConfig clientMod,
        RandomSafeArrivalConfig randomSafeArrival,
        HubConfig hub
) {
}
