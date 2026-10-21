package com.lodygames.rpgquest.world.multiverse;

import com.lodygames.rpgquest.world.state.WorldStatePlanner;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.World;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.core.utils.result.Attempt;
import org.mvplugins.multiverse.core.world.LoadedMultiverseWorld;
import org.mvplugins.multiverse.core.world.MultiverseWorld;
import org.mvplugins.multiverse.core.world.WorldManager;
import org.mvplugins.multiverse.core.world.options.ImportWorldOptions;
import org.mvplugins.multiverse.core.world.options.LoadWorldOptions;
import org.mvplugins.multiverse.core.world.options.UnloadWorldOptions;
import org.mvplugins.multiverse.core.world.reasons.ImportFailureReason;
import org.mvplugins.multiverse.core.world.reasons.LoadFailureReason;
import org.mvplugins.multiverse.core.world.reasons.UnloadFailureReason;
import org.mvplugins.multiverse.external.vavr.control.Try;

/**
 * La seule classe de RPGQuest qui <strong>charge, décharge ou importe</strong> un monde, via le
 * {@code WorldManager} de Multiverse-Core 5 (issue #563). Chargée uniquement après la vérification
 * de présence de {@link MultiverseWorldState}. Le déchargement enregistre toujours le monde et le
 * garde dans le fichier de Multiverse ; l'import garde la vérification de dossier active. Jamais de
 * suppression, de retrait, de régénération ni de clonage.
 */
final class MultiverseApiState {

    private MultiverseApiState() {
    }

    static Map<String, WorldStatePlanner.Known> knownWorlds() {
        Map<String, WorldStatePlanner.Known> out = new LinkedHashMap<>();
        for (MultiverseWorld w : manager().getWorlds()) {
            World.Environment env = w.getEnvironment();
            out.put(w.getName(), new WorldStatePlanner.Known(w.getName(), w.isLoaded(), w.isAutoLoad(),
                    env == null ? "" : env.name()));
        }
        return out;
    }

    static String load(String name) {
        MultiverseWorld w = manager().getUnloadedWorld(name).getOrNull();
        if (w == null) {
            return "Multiverse-Core ne connaît pas « " + name + " » comme monde non chargé";
        }
        Attempt<LoadedMultiverseWorld, LoadFailureReason> attempt = manager().loadWorld(LoadWorldOptions.world(w).doFolderCheck(true));
        return attempt.isSuccess() ? null : reason(attempt.getFailureReason()) + detail(attempt);
    }

    static String unload(String name) {
        LoadedMultiverseWorld w = manager().getLoadedWorld(name).getOrNull();
        if (w == null) {
            return "Multiverse-Core ne connaît pas « " + name + " » comme monde chargé";
        }
        Attempt<MultiverseWorld, UnloadFailureReason> attempt = manager().unloadWorld(
                UnloadWorldOptions.world(w).saveBukkitWorld(true).unloadBukkitWorld(true));
        return attempt.isSuccess() ? null : reason(attempt.getFailureReason()) + detail(attempt);
    }

    static String importFolder(String name, String environment) {
        Attempt<LoadedMultiverseWorld, ImportFailureReason> attempt = manager().importWorld(
                ImportWorldOptions.worldName(name).environment(World.Environment.valueOf(environment))
                        .useSpawnAdjust(true).doFolderCheck(true));
        return attempt.isSuccess() ? null : reason(attempt.getFailureReason()) + detail(attempt);
    }

    static String setAutoLoad(String name, boolean autoLoad) {
        MultiverseWorld w = manager().getWorld(name).getOrNull();
        if (w == null) {
            return "Multiverse-Core ne connaît pas le monde « " + name + " »";
        }
        Try<Void> set = w.setAutoLoad(autoLoad);
        if (set.isFailure()) {
            return message(set.getCause());
        }
        Try<Void> saved = manager().saveWorldsConfig();
        return saved.isSuccess() ? null : message(saved.getCause());
    }

    private static String reason(LoadFailureReason reason) {
        if (reason == null) {
            return "raison inconnue";
        }
        return switch (reason) {
            case WORLD_ALREADY_LOADING -> "ce monde est déjà en cours de chargement";
            case WORLD_NON_EXISTENT -> "Multiverse-Core ne connaît pas ce monde";
            case WORLD_EXIST_FOLDER -> "le dossier du monde est introuvable";
            case WORLD_FOLDER_INVALID -> "le dossier ne contient pas un monde valide";
            case WORLD_EXIST_LOADED -> "ce monde est déjà chargé";
            case BUKKIT_ENVIRONMENT_MISMATCH -> "la dimension enregistrée ne correspond pas au dossier";
            case BUKKIT_NAMESPACED_KEY_MISMATCH -> "l'identifiant enregistré ne correspond pas au dossier";
            case WORLD_CREATOR_FAILED -> "le serveur n'a pas pu charger le monde";
        };
    }

    private static String reason(UnloadFailureReason reason) {
        if (reason == null) {
            return "raison inconnue";
        }
        return switch (reason) {
            case WORLD_ALREADY_UNLOADING -> "ce monde est déjà en cours de déchargement";
            case WORLD_NON_EXISTENT -> "Multiverse-Core ne connaît pas ce monde";
            case WORLD_UNLOADED -> "ce monde est déjà déchargé";
            case BUKKIT_UNLOAD_FAILED -> "le serveur a refusé le déchargement (joueurs présents ou monde principal ?)";
        };
    }

    private static String reason(ImportFailureReason reason) {
        if (reason == null) {
            return "raison inconnue";
        }
        return switch (reason) {
            case INVALID_WORLDNAME -> "nom de monde refusé par Multiverse-Core";
            case NAMESPACEDKEY_UNSUPPORTED -> "identifiant non pris en charge";
            case WORLD_FOLDER_INVALID -> "le dossier ne contient pas un monde valide";
            case WORLD_EXIST_UNLOADED -> "Multiverse-Core connaît déjà ce monde (non chargé) : le charger";
            case WORLD_EXIST_LOADED -> "ce monde est déjà chargé";
            case BUKKIT_ENVIRONMENT_MISMATCH -> "la dimension choisie ne correspond pas au dossier";
            case WORLD_CREATOR_FAILED -> "le serveur n'a pas pu charger le monde";
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
