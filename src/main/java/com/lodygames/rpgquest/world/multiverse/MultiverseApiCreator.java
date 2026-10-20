package com.lodygames.rpgquest.world.multiverse;

import com.lodygames.rpgquest.world.create.WorldCreationPlanner;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.core.utils.result.Attempt;
import org.mvplugins.multiverse.core.world.LoadedMultiverseWorld;
import org.mvplugins.multiverse.core.world.MultiverseWorld;
import org.mvplugins.multiverse.core.world.WorldManager;
import org.mvplugins.multiverse.core.world.options.CreateWorldOptions;
import org.mvplugins.multiverse.core.world.reasons.CreateFailureReason;
import org.mvplugins.multiverse.external.vavr.control.Try;

/**
 * La seule classe de RPGQuest qui <strong>crée</strong> un monde, via
 * {@code WorldManager#createWorld} de Multiverse-Core 5 (issue #562). Chargée uniquement après la
 * vérification de présence de {@link MultiverseWorldCreator}. Jamais d'import, de chargement d'un
 * monde existant, de déchargement, de régénération ni de suppression ; vérification de dossier
 * Multiverse toujours active ({@code doFolderCheck}) pour ne jamais réutiliser un dossier présent.
 */
final class MultiverseApiCreator {

    private MultiverseApiCreator() {
    }

    static Set<String> knownWorlds() {
        Set<String> out = new LinkedHashSet<>();
        for (MultiverseWorld w : manager().getWorlds()) {
            out.add(w.getName());
        }
        return out;
    }

    static String create(WorldCreationPlanner.Plan plan) {
        CreateWorldOptions options = CreateWorldOptions.worldName(plan.world())
                .environment(World.Environment.valueOf(plan.dimension().name()))
                .worldType(WorldType.valueOf(plan.terrain().name()))
                .generateStructures(plan.structures())
                .bonusChest(false)
                .useSpawnAdjust(true)
                .doFolderCheck(true);
        if (plan.seed() != null) {
            options.seed(plan.seed());
        }
        if (plan.generatorSettings() != null) {
            options.generatorSettings(plan.generatorSettings());
        }
        Attempt<LoadedMultiverseWorld, CreateFailureReason> attempt = manager().createWorld(options);
        if (attempt.isSuccess()) {
            return null;
        }
        return reason(attempt.getFailureReason()) + detail(attempt);
    }

    static String disableKeepSpawnAndSave(String name) {
        MultiverseWorld w = manager().getWorld(name).getOrNull();
        if (w == null) {
            return "Multiverse-Core ne connaît pas le monde « " + name + " »";
        }
        Try<Void> set = w.setKeepSpawnInMemory(false);
        Try<Void> saved = manager().saveWorldsConfig();
        if (set.isFailure()) {
            return message(set.getCause());
        }
        return saved.isSuccess() ? null : message(saved.getCause());
    }

    static Optional<Map<String, String>> describe(String name) {
        MultiverseWorld w = manager().getWorld(name).getOrNull();
        if (w == null) {
            return Optional.empty();
        }
        Map<String, String> out = new LinkedHashMap<>();
        if (w.getGeneratorSettings() != null) {
            out.put("generatorSettings", w.getGeneratorSettings());
        }
        out.put("keepSpawnLoaded", String.valueOf(w.isKeepSpawnInMemory()));
        out.put("autoLoad", String.valueOf(w.isAutoLoad()));
        return Optional.of(out);
    }

    private static String reason(CreateFailureReason reason) {
        if (reason == null) {
            return "raison inconnue";
        }
        return switch (reason) {
            case INVALID_WORLDNAME -> "nom de monde refusé par Multiverse-Core";
            case NAMESPACEDKEY_UNSUPPORTED -> "identifiant non pris en charge";
            case WORLD_EXIST_FOLDER -> "un dossier de ce nom existe déjà (rien n'a été écrasé)";
            case WORLD_EXIST_UNLOADED -> "Multiverse-Core connaît déjà ce monde (non chargé)";
            case WORLD_EXIST_LOADED -> "ce monde existe déjà et il est chargé";
            case WORLD_CREATOR_FAILED -> "le serveur n'a pas pu générer le monde";
        };
    }

    private static String detail(Attempt<?, ?> attempt) {
        try {
            String text = attempt.getFailureMessage() == null ? null : attempt.getFailureMessage().formatted();
            return text == null || text.isBlank() ? "" : " — " + text.replaceAll("§.", "").strip();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String message(Throwable cause) {
        if (cause == null) {
            return "raison inconnue";
        }
        String m = cause.getMessage();
        return m == null || m.isBlank() ? cause.getClass().getSimpleName() : m;
    }

    private static WorldManager manager() {
        if (!MultiverseCoreApi.isLoaded()) {
            throw new IllegalStateException("API Multiverse-Core pas encore prête");
        }
        return MultiverseCoreApi.get().getWorldManager();
    }
}
