package com.lodygames.rpgquest.world.multiverse;

import com.lodygames.rpgquest.world.settings.MultiverseSettingsPort;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Réglages Multiverse-Core d'un monde existant (issue #561). Comme {@link MultiverseWorldSource},
 * aucune référence à un type Multiverse ici : présence vérifiée, puis délégation à
 * {@link MultiverseApiSettings}. Une build incompatible ({@link LinkageError}) ou une erreur devient
 * « Multiverse illisible » (lecture, clé {@link MultiverseSettingsPort#ERROR_KEY}) ou une raison d'échec (écriture), jamais une exception.
 */
public final class MultiverseWorldSettings implements MultiverseSettingsPort {

    @Override
    public Optional<Map<String, String>> read(String world) {
        if (!usable()) {
            return Optional.empty();
        }
        try {
            return MultiverseApiSettings.read(world);
        } catch (LinkageError | RuntimeException error) {
            // Multiverse est là mais illisible : ne JAMAIS retomber sur l'API Bukkit pour un monde
            // qu'il gère peut-être (il réappliquerait ses valeurs au prochain chargement).
            return Optional.of(Map.of(ERROR_KEY, "Multiverse-Core illisible (" + error.getClass().getSimpleName() + ")"));
        }
    }

    @Override
    public String write(String world, String key, String value) {
        if (!usable()) {
            return "Multiverse-Core n'est pas installé ou pas actif";
        }
        try {
            return MultiverseApiSettings.write(world, key, value);
        } catch (LinkageError | RuntimeException error) {
            return "écriture Multiverse-Core impossible (" + error.getClass().getSimpleName() + ")";
        }
    }

    @Override
    public String save() {
        if (!usable()) {
            return "Multiverse-Core n'est pas installé ou pas actif";
        }
        try {
            return MultiverseApiSettings.save();
        } catch (LinkageError | RuntimeException error) {
            return "enregistrement Multiverse-Core impossible (" + error.getClass().getSimpleName() + ")";
        }
    }

    private static boolean usable() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(MultiverseWorldSource.PLUGIN_NAME);
        return plugin != null && plugin.isEnabled();
    }
}
