package com.lodygames.rpgquest.world.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Faux serveur pour les réglages de monde (issue #561) : aucun monde réel, aucune écriture disque. */
final class WorldSettingsFixtures {

    private WorldSettingsFixtures() {
    }

    /** Monde Bukkit sans Multiverse (comme {@code wild} sur DEV). */
    static Map<String, String> bukkitValues() {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("difficulty", "EASY");
        v.put("pvp", "true");
        v.put("monsters", "true");
        v.put("advance_time", "true");
        v.put("time", "1000");
        v.put("advance_weather", "true");
        v.put("weather", "CLEAR");
        v.put("border_center", "0,0");
        v.put("border_size", "59999968");
        v.put("spawn", "0,64,0,0");
        return v;
    }

    static Map<String, String> writers(boolean multiverse) {
        Map<String, String> w = new LinkedHashMap<>();
        String mv = WorldSettingsSnapshot.WRITER_MULTIVERSE;
        w.put("difficulty", multiverse ? mv : WorldSettingsSnapshot.WRITER_BUKKIT);
        w.put("pvp", multiverse ? mv : WorldSettingsSnapshot.WRITER_GAMERULE);
        w.put("monsters", multiverse ? mv : WorldSettingsSnapshot.WRITER_GAMERULE);
        if (multiverse) {
            w.put("animals", mv);
            w.put("gamemode", mv);
        }
        w.put("advance_time", WorldSettingsSnapshot.WRITER_GAMERULE);
        w.put("time", WorldSettingsSnapshot.WRITER_BUKKIT);
        w.put("advance_weather", WorldSettingsSnapshot.WRITER_GAMERULE);
        w.put("weather", WorldSettingsSnapshot.WRITER_BUKKIT);
        w.put("border_center", WorldSettingsSnapshot.WRITER_BUKKIT);
        w.put("border_size", WorldSettingsSnapshot.WRITER_BUKKIT);
        w.put("spawn", multiverse ? mv : WorldSettingsSnapshot.WRITER_BUKKIT);
        return w;
    }

    static List<WorldSettingsSnapshot.Rule> rules() {
        return List.of(new WorldSettingsSnapshot.Rule("advance_time", "BOOLEAN", "true"),
                new WorldSettingsSnapshot.Rule("keep_inventory", "BOOLEAN", "false"),
                new WorldSettingsSnapshot.Rule("pvp", "BOOLEAN", "true"),
                new WorldSettingsSnapshot.Rule("random_tick_speed", "INTEGER", "3"));
    }

    static WorldSettingsSnapshot snapshot(String world, List<String> roles, int players) {
        return new WorldSettingsSnapshot(world, false, roles, players, -64, 320, bukkitValues(), writers(false),
                Map.of("animals", "pas de Multiverse", "gamemode", "pas de Multiverse"), rules(), List.of());
    }

    static WorldSettingsSnapshot multiverseSnapshot(String world, int players) {
        Map<String, String> v = bukkitValues();
        v.put("animals", "true");
        v.put("gamemode", "SURVIVAL");
        return new WorldSettingsSnapshot(world, true, List.of(), players, -64, 320, v, writers(true), Map.of(),
                rules(), List.of());
    }

    /** Faux serveur : un monde, des valeurs modifiables, un journal des appels. */
    static final class FakeGateway implements WorldSettingsGateway {

        final String world;
        final List<String> roles;
        final Map<String, String> values = new LinkedHashMap<>(bukkitValues());
        final Map<String, String> rules = new LinkedHashMap<>();
        final List<String> writes = new ArrayList<>();
        int players;
        boolean loaded = true;
        int persists;
        /** Clé dont l'écriture échoue. */
        String failOn;
        /** Clé que le serveur « corrige » silencieusement (relu ≠ écrit). */
        String driftOn;
        String playerAt;

        FakeGateway(String world, List<String> roles) {
            this.world = world;
            this.roles = roles;
            rules().forEach(r -> rules.put(r.name(), r.value()));
        }

        @Override
        public Optional<WorldSettingsSnapshot> read(String name) {
            if (!loaded || !world.equals(name)) {
                return Optional.empty();
            }
            List<WorldSettingsSnapshot.Rule> r = new ArrayList<>();
            rules().forEach(x -> r.add(new WorldSettingsSnapshot.Rule(x.name(), x.type(), rules.get(x.name()))));
            return Optional.of(new WorldSettingsSnapshot(world, false, roles, players, -64, 320, values,
                    writers(false), Map.of("animals", "pas de Multiverse"), r, List.of()));
        }

        @Override
        public String write(String name, WorldSettingsPlanner.Change change) {
            writes.add(change.key() + "=" + change.proposed() + "@" + change.writer());
            if (change.key().equals(failOn)) {
                return "refus simulé";
            }
            String value = change.key().equals(driftOn) ? "drift" : change.proposed();
            if (change.key().startsWith("rule.")) {
                rules.put(change.key().substring(5), value);
            } else {
                values.put(change.key(), value);
            }
            return null;
        }

        @Override
        public String persist(String name) {
            persists++;
            return null;
        }

        @Override
        public Optional<String> playerPosition(String name, String player) {
            return "Lody".equals(player) && playerAt != null ? Optional.of(playerAt) : Optional.empty();
        }
    }
}
