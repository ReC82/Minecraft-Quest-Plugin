package com.lodygames.rpgquest.config;

import com.lodygames.rpgquest.database.DatabaseSettings;

public record PluginConfig(
        boolean debug,
        String locale,
        DatabaseSettings database,
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
        HubConfig hub,
        TravelConfig travel
) {

    /**
     * Nom du fichier SQLite — raccourci de compatibilité vers {@code database().sqlite().file()},
     * conservé pour les quelques appelants historiques (affichage {@code /rpgquest version}, log de
     * config). Ne présume rien du moteur réellement actif : voir {@link #database()}.
     */
    public String databaseFile() {
        return database.sqlite().file();
    }
}
