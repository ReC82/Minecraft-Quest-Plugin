package be.lloyd.rpgquest.config;

public record PluginConfig(
        boolean debug,
        String locale,
        String databaseFile,
        ResourcePackConfig resourcePack,
        DialogueConfig dialogue,
        JournalConfig journal
) {
}
