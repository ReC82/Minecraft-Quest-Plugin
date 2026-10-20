package com.lodygames.rpgquest.world.multiverse;

import com.lodygames.rpgquest.world.WorldOverview;
import java.util.ArrayList;
import java.util.List;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.core.world.MultiverseWorld;

/**
 * L'unique classe de RPGQuest qui connaisse l'API Multiverse-Core 5 (issue #560). N'est chargée
 * qu'après la vérification de présence de {@link MultiverseWorldSource}. Uniquement des getters.
 */
final class MultiverseApiReader {

    private MultiverseApiReader() {
    }

    static List<WorldOverview.ExternalWorld> worlds() {
        if (!MultiverseCoreApi.isLoaded()) {
            throw new IllegalStateException("API Multiverse-Core pas encore prête");
        }
        List<WorldOverview.ExternalWorld> out = new ArrayList<>();
        for (MultiverseWorld w : MultiverseCoreApi.get().getWorldManager().getWorlds()) {
            out.add(new WorldOverview.ExternalWorld(w.getName(), w.isLoaded(),
                    w.getEnvironment() == null ? null : w.getEnvironment().name(),
                    w.getGenerator(), w.getGeneratorSettings(), w.getSeed(),
                    w.getDifficulty() == null ? null : w.getDifficulty().name(), w.getPvp(), w.isAutoLoad(),
                    alias(w)));
        }
        return out;
    }

    /**
     * Alias sans couleurs, {@code null} si aucun : {@code getColourlessAlias()} renvoie le nom du
     * monde quand l'alias est vide (relevé DEV du 21/10, #81), ce qui masquerait les défauts.
     */
    private static String alias(MultiverseWorld w) {
        String raw = w.getAlias();
        return raw == null || raw.isBlank() ? null : w.getColourlessAlias();
    }
}
