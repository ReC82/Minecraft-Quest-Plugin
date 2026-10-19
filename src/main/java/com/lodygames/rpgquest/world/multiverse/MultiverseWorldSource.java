package com.lodygames.rpgquest.world.multiverse;

import com.lodygames.rpgquest.world.WorldOverview;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Lecture <strong>seule</strong> des mondes déclarés dans Multiverse-Core (issue #560, #331-W1).
 *
 * <p>Multiverse est une dépendance facultative ({@code softdepend}, {@code compileOnly}) : cette
 * classe ne référence aucun type Multiverse. Elle vérifie que le plugin est présent et actif, puis
 * délègue à {@link MultiverseApiReader}, seule classe qui touche l'API. Une build incompatible
 * ({@link LinkageError}) ou une erreur de lecture devient une indisponibilité motivée, jamais une
 * exception : la page Mondes retombe alors sur la lecture Bukkit.</p>
 *
 * <p>Aucune méthode d'écriture de Multiverse n'est appelée (ni création, ni chargement, ni
 * déchargement, ni réglage).</p>
 */
public final class MultiverseWorldSource implements Supplier<WorldOverview.ExternalSource> {

    static final String PLUGIN_NAME = "Multiverse-Core";

    /** À appeler sur le thread principal : Multiverse lit l'état Bukkit des mondes. */
    @Override
    public WorldOverview.ExternalSource get() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (plugin == null) {
            return WorldOverview.ExternalSource.unavailable("Multiverse-Core n'est pas installé");
        }
        if (!plugin.isEnabled()) {
            return WorldOverview.ExternalSource.unavailable("Multiverse-Core est installé mais désactivé");
        }
        String version = plugin.getPluginMeta().getVersion();
        try {
            return new WorldOverview.ExternalSource(true, version, "", MultiverseApiReader.worlds());
        } catch (LinkageError error) {
            return new WorldOverview.ExternalSource(false, version,
                    "Multiverse-Core " + version + " incompatible (" + error.getClass().getSimpleName() + ")",
                    java.util.List.of());
        } catch (RuntimeException error) {
            return new WorldOverview.ExternalSource(false, version,
                    "lecture Multiverse-Core impossible : " + error.getClass().getSimpleName(), java.util.List.of());
        }
    }
}
