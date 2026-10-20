package com.lodygames.rpgquest.world.settings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

/**
 * Passerelle réelle des réglages de monde (issue #561) : API Bukkit/Paper 1.21.11 pour les mondes
 * que Multiverse-Core ne connaît pas (dont {@code wild}, géré par RPGQuest), Multiverse-Core pour
 * les réglages qu'il persiste sur les mondes qu'il gère. Un seul chemin d'écriture par réglage,
 * annoncé dans {@link WorldSettingsSnapshot#writers()} et repris tel quel à l'écriture.
 *
 * <p>Ne crée, ne charge, ne décharge, n'importe ni ne supprime jamais de monde : un monde non chargé
 * n'a pas de relevé, donc rien à écrire.</p>
 */
public final class BukkitWorldSettingsGateway implements WorldSettingsGateway {

    /** Noms des mondes à rôle, relus à chaque appel ({@code hub.world}, {@code travel.wild-world}, {@code claims.world}). */
    public record Roles(String hub, String wild, String claims) {
    }

    private final Server server;
    private final MultiverseSettingsPort multiverse;
    private final Supplier<Roles> roles;
    private final Set<String> multiverseDirty = new HashSet<>();

    public BukkitWorldSettingsGateway(Server server, MultiverseSettingsPort multiverse, Supplier<Roles> roles) {
        this.server = server;
        this.multiverse = multiverse;
        this.roles = roles;
    }

    @Override
    public Optional<WorldSettingsSnapshot> read(String name) {
        World world = server.getWorld(name);
        if (world == null) {
            return Optional.empty();
        }
        Optional<Map<String, String>> mvRead = multiverse.read(world.getName());
        String mvError = mvRead.map(m -> m.get(MultiverseSettingsPort.ERROR_KEY)).orElse(null);
        Map<String, String> mv = mvError == null ? mvRead.orElse(null) : null;
        boolean mvManaged = mvRead.isPresent();
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> writers = new LinkedHashMap<>();
        Map<String, String> unavailable = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();

        if (mvError != null) {
            for (String key : List.of("difficulty", "pvp", "monsters", "animals", "gamemode", "spawn")) {
                unavailable.put(key, mvError + " : réglage non modifiable tant que Multiverse ne répond pas.");
            }
        } else if (mv != null) {
            for (String key : List.of("difficulty", "pvp", "monsters", "animals", "gamemode", "spawn")) {
                if (mv.containsKey(key)) {
                    values.put(key, mv.get(key));
                    writers.put(key, WorldSettingsSnapshot.WRITER_MULTIVERSE);
                } else {
                    unavailable.put(key, "Valeur non lisible dans Multiverse-Core.");
                }
            }
            if (!world.getDifficulty().name().equals(mv.get("difficulty"))) {
                notes.add("Difficulté : Multiverse enregistre " + mv.get("difficulty") + " mais le monde tourne en "
                        + world.getDifficulty().name() + ".");
            }
            Boolean livePvp = ruleValue(world, "pvp");
            if (livePvp != null && !String.valueOf(livePvp).equals(mv.get("pvp"))) {
                notes.add("PvP : Multiverse enregistre " + mv.get("pvp") + " mais la règle du monde vaut " + livePvp + ".");
            }
            if ("false".equals(mv.get("allow_weather"))) {
                notes.add("Multiverse bloque les changements de météo de ce monde (allow-weather: false) : la météo "
                        + "proposée pourrait être annulée.");
            }
        } else {
            values.put("difficulty", world.getDifficulty().name());
            writers.put("difficulty", WorldSettingsSnapshot.WRITER_BUKKIT);
            putRule(world, "pvp", "pvp", values, writers, unavailable);
            putRule(world, "monsters", "spawn_monsters", values, writers, unavailable);
            unavailable.put("animals", "Ce monde n'est pas géré par Multiverse-Core, et aucune règle du serveur ne "
                    + "règle les animaux seuls (la règle spawn_mobs coupe toutes les créatures).");
            unavailable.put("gamemode", "Réglage propre à Multiverse-Core : ce monde n'y est pas déclaré.");
            Location spawn = world.getSpawnLocation();
            values.put("spawn", WorldSettingsPlanner.spawnValue(spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getYaw()));
            writers.put("spawn", WorldSettingsSnapshot.WRITER_BUKKIT);
        }
        putRule(world, "advance_time", "advance_time", values, writers, unavailable);
        putRule(world, "advance_weather", "advance_weather", values, writers, unavailable);
        values.put("time", String.valueOf(Math.floorMod(world.getTime(), 24_000L)));
        writers.put("time", WorldSettingsSnapshot.WRITER_BUKKIT);
        values.put("weather", world.isThundering() && world.hasStorm() ? "THUNDER" : world.hasStorm() ? "RAIN" : "CLEAR");
        writers.put("weather", WorldSettingsSnapshot.WRITER_BUKKIT);
        WorldBorder border = world.getWorldBorder();
        values.put("border_center", WorldSettingsPlanner.number(border.getCenter().getX()) + ","
                + WorldSettingsPlanner.number(border.getCenter().getZ()));
        writers.put("border_center", WorldSettingsSnapshot.WRITER_BUKKIT);
        values.put("border_size", WorldSettingsPlanner.number(border.getSize()));
        writers.put("border_size", WorldSettingsSnapshot.WRITER_BUKKIT);

        return Optional.of(new WorldSettingsSnapshot(world.getName(), mvManaged, rolesOf(world.getName()),
                world.getPlayerCount(), world.getMinHeight(), world.getMaxHeight(), values, writers, unavailable,
                rules(world), notes));
    }

    @Override
    public String write(String name, WorldSettingsPlanner.Change change) {
        World world = server.getWorld(name);
        if (world == null) {
            return "Le monde n'est plus chargé.";
        }
        String key = change.key();
        String value = change.proposed();
        try {
            if (WorldSettingsSnapshot.WRITER_MULTIVERSE.equals(change.writer())) {
                String error = multiverse.write(world.getName(), key, value);
                if (error == null) {
                    multiverseDirty.add(world.getName());
                }
                return error;
            }
            if (WorldSettingsSnapshot.WRITER_GAMERULE.equals(change.writer())) {
                String rule = key.startsWith(WorldSetting.RULE_PREFIX) ? key.substring(WorldSetting.RULE_PREFIX.length())
                        : WorldSetting.byKey(key).map(WorldSetting::dedicatedRule).orElse(null);
                return setRule(world, rule, value);
            }
            return writeBukkit(world, key, value);
        } catch (RuntimeException e) {
            return "Le serveur a refusé la valeur (" + e.getClass().getSimpleName() + ").";
        }
    }

    @Override
    public String persist(String name) {
        if (!multiverseDirty.remove(name)) {
            return null;
        }
        return multiverse.save();
    }

    @Override
    public Optional<String> playerPosition(String world, String playerName) {
        Player player = server.getPlayerExact(playerName);
        if (player == null || !player.getWorld().getName().equals(world)) {
            return Optional.empty();
        }
        Location l = player.getLocation();
        return Optional.of(WorldSettingsPlanner.spawnValue(l.getX(), l.getY(), l.getZ(), l.getYaw()));
    }

    private static String writeBukkit(World world, String key, String value) {
        switch (key) {
            case "difficulty" -> world.setDifficulty(Difficulty.valueOf(value));
            case "time" -> world.setTime(Long.parseLong(value));
            case "weather" -> {
                world.setStorm(!"CLEAR".equals(value));
                world.setThundering("THUNDER".equals(value));
            }
            case "border_center" -> {
                String[] c = value.split(",");
                world.getWorldBorder().setCenter(Double.parseDouble(c[0]), Double.parseDouble(c[1]));
            }
            case "border_size" -> world.getWorldBorder().setSize(Double.parseDouble(value));
            case "spawn" -> {
                String[] p = value.split(",");
                Location l = new Location(world, Long.parseLong(p[0]) + 0.5, Long.parseLong(p[1]),
                        Long.parseLong(p[2]) + 0.5, Float.parseFloat(p[3]), 0f);
                if (!world.setSpawnLocation(l)) {
                    return "Le serveur a refusé ce point d'arrivée.";
                }
            }
            default -> {
                return "Réglage « " + key + " » sans chemin d'écriture Bukkit.";
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String setRule(World world, String name, String value) {
        GameRule<?> rule = name == null ? null : Registry.GAME_RULE.get(NamespacedKey.minecraft(name));
        if (rule == null) {
            return "Règle de jeu « " + name + " » inconnue du serveur.";
        }
        boolean ok;
        if (rule.getType() == Boolean.class) {
            ok = world.setGameRule((GameRule<Boolean>) rule, Boolean.parseBoolean(value));
        } else if (rule.getType() == Integer.class) {
            ok = world.setGameRule((GameRule<Integer>) rule, Integer.parseInt(value));
        } else {
            return "Type de règle non pris en charge.";
        }
        return ok ? null : "Le serveur a refusé la valeur de la règle « " + name + " ».";
    }

    private static void putRule(World world, String key, String rule, Map<String, String> values,
                                Map<String, String> writers, Map<String, String> unavailable) {
        Boolean value = ruleValue(world, rule);
        if (value == null) {
            unavailable.put(key, "Règle « " + rule + " » absente de ce serveur.");
            return;
        }
        values.put(key, String.valueOf(value));
        writers.put(key, WorldSettingsSnapshot.WRITER_GAMERULE);
    }

    @SuppressWarnings("unchecked")
    private static Boolean ruleValue(World world, String name) {
        GameRule<?> rule = Registry.GAME_RULE.get(NamespacedKey.minecraft(name));
        if (rule == null || rule.getType() != Boolean.class) {
            return null;
        }
        try {
            return world.getGameRuleValue((GameRule<Boolean>) rule);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Toutes les règles booléennes et entières du registre du serveur, lues sur ce monde. */
    private static List<WorldSettingsSnapshot.Rule> rules(World world) {
        List<WorldSettingsSnapshot.Rule> out = new ArrayList<>();
        for (GameRule<?> rule : Registry.GAME_RULE) {
            String type = rule.getType() == Boolean.class ? "BOOLEAN" : rule.getType() == Integer.class ? "INTEGER" : null;
            if (type == null) {
                continue;
            }
            try {
                Object value = world.getGameRuleValue(rule);
                if (value != null) {
                    out.add(new WorldSettingsSnapshot.Rule(rule.getKey().getKey(), type, String.valueOf(value)));
                }
            } catch (RuntimeException e) {
                // Règle liée à une fonctionnalité désactivée : simplement absente du relevé.
            }
        }
        out.sort(java.util.Comparator.comparing(WorldSettingsSnapshot.Rule::name));
        return out;
    }

    private List<String> rolesOf(String name) {
        Roles r = roles.get();
        List<String> out = new ArrayList<>();
        if (name.equalsIgnoreCase(r.hub())) {
            out.add("HUB");
        }
        if (name.equalsIgnoreCase(r.wild())) {
            out.add("WILD");
        }
        if (name.equalsIgnoreCase(r.claims())) {
            out.add("CLAIMS");
        }
        return out;
    }
}
