package com.lodygames.rpgquest.world.multiverse;

import com.lodygames.rpgquest.world.WorldDisplayNames;
import com.lodygames.rpgquest.world.settings.WorldSettingsPlanner;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.SpawnCategory;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.core.world.MultiverseWorld;
import org.mvplugins.multiverse.core.world.entity.EntitySpawnConfig;
import org.mvplugins.multiverse.external.vavr.control.Try;

/**
 * La seule classe de RPGQuest qui <strong>écrit</strong> des réglages via l'API Multiverse-Core 5
 * (issue #561). Chargée uniquement après la vérification de présence de
 * {@link MultiverseWorldSettings}. Jamais de création, chargement, déchargement, import ni
 * suppression de monde : seulement les réglages d'un monde existant que Multiverse connaît.
 */
final class MultiverseApiSettings {

    private MultiverseApiSettings() {
    }

    static Optional<Map<String, String>> read(String name) {
        MultiverseWorld w = world(name);
        if (w == null) {
            return Optional.empty();
        }
        Map<String, String> out = new LinkedHashMap<>();
        String alias = w.getAlias();
        out.put("alias", alias == null || alias.isBlank() ? WorldDisplayNames.NONE : alias);
        if (w.getDifficulty() != null) {
            out.put("difficulty", w.getDifficulty().name());
        }
        out.put("pvp", String.valueOf(w.getPvp()));
        EntitySpawnConfig spawning = w.getEntitySpawnConfig();
        if (spawning != null) {
            out.put("monsters", String.valueOf(spawning.getSpawnCategoryConfig(SpawnCategory.MONSTER).isSpawn()));
            out.put("animals", String.valueOf(spawning.getSpawnCategoryConfig(SpawnCategory.ANIMAL).isSpawn()));
        }
        if (w.getGameMode() != null) {
            out.put("gamemode", w.getGameMode().name());
        }
        Location spawn = w.getSpawnLocation();
        if (spawn != null) {
            out.put("spawn", WorldSettingsPlanner.spawnValue(spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getYaw()));
        }
        out.put("allow_weather", String.valueOf(w.isAllowWeather()));
        return Optional.of(out);
    }

    static String write(String name, String key, String value) {
        MultiverseWorld w = world(name);
        if (w == null) {
            return "Multiverse-Core ne connaît pas le monde « " + name + " »";
        }
        Try<Void> attempt = switch (key) {
            case "alias" -> w.setAlias(WorldDisplayNames.NONE.equals(value) ? "" : value);
            case "difficulty" -> w.setDifficulty(Difficulty.valueOf(value));
            case "pvp" -> w.setPvp(Boolean.parseBoolean(value));
            case "gamemode" -> w.setGameMode(GameMode.valueOf(value.toUpperCase(Locale.ROOT)));
            case "monsters" -> spawning(w, SpawnCategory.MONSTER, Boolean.parseBoolean(value));
            case "animals" -> spawning(w, SpawnCategory.ANIMAL, Boolean.parseBoolean(value));
            case "spawn" -> spawn(w, name, value);
            default -> null;
        };
        if (attempt == null) {
            return "réglage « " + key + " » non pris en charge par Multiverse-Core";
        }
        return attempt.isSuccess() ? null : "Multiverse-Core a refusé : " + reason(attempt.getCause());
    }

    static String save() {
        Try<Void> saved = MultiverseCoreApi.get().getWorldManager().saveWorldsConfig();
        return saved.isSuccess() ? null : reason(saved.getCause());
    }

    private static Try<Void> spawning(MultiverseWorld w, SpawnCategory category, boolean allowed) {
        EntitySpawnConfig config = w.getEntitySpawnConfig();
        Try<Void> set = config.getSpawnCategoryConfig(category).setSpawn(allowed);
        if (set.isFailure()) {
            return set;
        }
        // Réenregistre l'objet pour que Multiverse le sérialise, puis l'applique au monde chargé.
        Try<Void> stored = w.setEntitySpawnConfig(config);
        config.applyConfigToWorld();
        return stored;
    }

    private static Try<Void> spawn(MultiverseWorld w, String name, String value) {
        World bukkit = Bukkit.getWorld(name);
        if (bukkit == null) {
            return Try.failure(new IllegalStateException("monde non chargé"));
        }
        String[] p = value.split(",");
        Location location = new Location(bukkit, Long.parseLong(p[0]) + 0.5, Long.parseLong(p[1]),
                Long.parseLong(p[2]) + 0.5, Float.parseFloat(p[3]), 0f);
        return w.setSpawnLocation(location);
    }

    private static MultiverseWorld world(String name) {
        if (!MultiverseCoreApi.isLoaded()) {
            throw new IllegalStateException("API Multiverse-Core pas encore prête");
        }
        return MultiverseCoreApi.get().getWorldManager().getWorld(name).getOrNull();
    }

    private static String reason(Throwable cause) {
        if (cause == null) {
            return "raison inconnue";
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
