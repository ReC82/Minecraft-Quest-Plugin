package com.lodygames.rpgquest.world.multiverse;

import com.lodygames.rpgquest.world.create.MultiverseCreationPort;
import com.lodygames.rpgquest.world.create.WorldCreationPlanner;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Création de monde par Multiverse-Core (issue #562). Comme {@link MultiverseWorldSource}, aucune
 * référence à un type Multiverse ici : présence vérifiée, puis délégation à
 * {@link MultiverseApiCreator}. Une build incompatible ({@link LinkageError}) ou une erreur devient
 * une raison en français, jamais une exception.
 */
public final class MultiverseWorldCreator implements MultiverseCreationPort {

    @Override
    public String unavailableReason() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(MultiverseWorldSource.PLUGIN_NAME);
        if (plugin == null) {
            return "Multiverse-Core n'est pas installé";
        }
        if (!plugin.isEnabled()) {
            return "Multiverse-Core est installé mais désactivé";
        }
        try {
            MultiverseApiCreator.knownWorlds();
            return null;
        } catch (LinkageError | RuntimeException error) {
            return "Multiverse-Core illisible (" + error.getClass().getSimpleName() + ")";
        }
    }

    @Override
    public Set<String> knownWorlds() {
        try {
            return MultiverseApiCreator.knownWorlds();
        } catch (LinkageError | RuntimeException error) {
            return Set.of();
        }
    }

    @Override
    public String create(WorldCreationPlanner.Plan plan) {
        String reason = unavailableReason();
        if (reason != null) {
            return reason;
        }
        try {
            return MultiverseApiCreator.create(plan);
        } catch (LinkageError | RuntimeException error) {
            return "création Multiverse-Core impossible (" + error.getClass().getSimpleName() + ")";
        }
    }

    @Override
    public String disableKeepSpawnAndSave(String world) {
        try {
            return MultiverseApiCreator.disableKeepSpawnAndSave(world);
        } catch (LinkageError | RuntimeException error) {
            return "réglage Multiverse-Core impossible (" + error.getClass().getSimpleName() + ")";
        }
    }

    @Override
    public Optional<Map<String, String>> describe(String world) {
        try {
            return MultiverseApiCreator.describe(world);
        } catch (LinkageError | RuntimeException error) {
            return Optional.empty();
        }
    }
}
