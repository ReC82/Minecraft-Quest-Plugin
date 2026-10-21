package com.lodygames.rpgquest.world.multiverse;

import com.lodygames.rpgquest.world.state.MultiverseStatePort;
import com.lodygames.rpgquest.world.state.WorldStatePlanner;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Chargement, déchargement et import par Multiverse-Core (issue #563). Comme
 * {@link MultiverseWorldCreator}, aucune référence à un type Multiverse ici : présence vérifiée,
 * puis délégation à {@link MultiverseApiState}. Une build incompatible ({@link LinkageError}) ou
 * une erreur devient une raison en français, jamais une exception.
 */
public final class MultiverseWorldState implements MultiverseStatePort {

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
            MultiverseApiState.knownWorlds();
            return null;
        } catch (LinkageError | RuntimeException error) {
            return "Multiverse-Core illisible (" + error.getClass().getSimpleName() + ")";
        }
    }

    @Override
    public Map<String, WorldStatePlanner.Known> knownWorlds() {
        try {
            return MultiverseApiState.knownWorlds();
        } catch (LinkageError | RuntimeException error) {
            return Map.of();
        }
    }

    @Override
    public String load(String world) {
        return guarded("chargement", () -> MultiverseApiState.load(world));
    }

    @Override
    public String unload(String world) {
        return guarded("déchargement", () -> MultiverseApiState.unload(world));
    }

    @Override
    public String importFolder(String world, String environment) {
        return guarded("import", () -> MultiverseApiState.importFolder(world, environment));
    }

    @Override
    public String setAutoLoad(String world, boolean autoLoad) {
        return guarded("réglage", () -> MultiverseApiState.setAutoLoad(world, autoLoad));
    }

    private String guarded(String what, Supplier<String> call) {
        String reason = unavailableReason();
        if (reason != null) {
            return reason;
        }
        try {
            return call.get();
        } catch (LinkageError | RuntimeException error) {
            return what + " Multiverse-Core impossible (" + error.getClass().getSimpleName() + ")";
        }
    }
}
