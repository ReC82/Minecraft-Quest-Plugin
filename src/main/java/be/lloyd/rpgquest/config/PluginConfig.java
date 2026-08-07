package be.lloyd.rpgquest.config;

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
        WebExportConfig webExport
) {
}
