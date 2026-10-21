package com.lodygames.rpgquest.world.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Faux serveur pour l'issue #563 : mondes chargés, connus de Multiverse, dossiers et « données » de
 * chaque monde (un compteur qui doit survivre au déchargement). Aucun monde réel n'est touché.
 */
final class WorldStateFixtures {

    private WorldStateFixtures() {
    }

    static final class FakeGateway implements WorldStateGateway {
        boolean multiverse = true;
        String main = "world";
        final Map<String, List<String>> roles = new LinkedHashMap<>(Map.of("hub", List.of("HUB"),
                "wild", List.of("WILD"), "claims", List.of("CLAIMS")));
        final Set<String> rpgquestManaged = new java.util.HashSet<>(Set.of("wild"));
        final Map<String, Integer> players = new LinkedHashMap<>();
        final Map<String, WorldStatePlanner.Known> known = new LinkedHashMap<>();
        final List<WorldStatePlanner.PortalTarget> portals = new ArrayList<>();
        /** Contenu des mondes : conservé tel quel par un déchargement (rien n'est supprimé). */
        final Map<String, String> data = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        String failNext;
        boolean ignoreAutoLoad;

        FakeGateway() {
            for (String w : List.of("world", "hub", "wild", "claims")) {
                players.put(w, 0);
            }
            known.put("hub", new WorldStatePlanner.Known("hub", true, true, "NORMAL"));
            known.put("claims", new WorldStatePlanner.Known("claims", true, true, "NORMAL"));
        }

        FakeGateway withLoaded(String name, int playerCount) {
            players.put(name, playerCount);
            known.put(name.toLowerCase(Locale.ROOT), new WorldStatePlanner.Known(name, true, true, "NORMAL"));
            data.put(name, "blocs de " + name);
            return this;
        }

        FakeGateway withUnloaded(String name, boolean autoLoad) {
            known.put(name.toLowerCase(Locale.ROOT), new WorldStatePlanner.Known(name, false, autoLoad, "NORMAL"));
            data.put(name, "blocs de " + name);
            return this;
        }

        @Override
        public WorldStatePlanner.Context context(List<WorldStatePlanner.Folder> folders) {
            return new WorldStatePlanner.Context(multiverse, multiverse ? null : "Multiverse-Core n'est pas installé",
                    main, roles, rpgquestManaged, players, multiverse ? known : Map.of(), folders, portals);
        }

        private String fail() {
            String f = failNext;
            failNext = null;
            return f;
        }

        @Override
        public String load(String world) {
            calls.add("load " + world);
            String f = fail();
            if (f != null) {
                return f;
            }
            WorldStatePlanner.Known k = known.get(world.toLowerCase(Locale.ROOT));
            known.put(world.toLowerCase(Locale.ROOT), new WorldStatePlanner.Known(world, true, k.autoLoad(), k.environment()));
            players.put(world, 0);
            return null;
        }

        @Override
        public String unload(String world) {
            calls.add("unload " + world);
            String f = fail();
            if (f != null) {
                return f;
            }
            WorldStatePlanner.Known k = known.get(world.toLowerCase(Locale.ROOT));
            known.put(world.toLowerCase(Locale.ROOT), new WorldStatePlanner.Known(world, false, k.autoLoad(), k.environment()));
            players.remove(world);
            return null;
        }

        @Override
        public String importFolder(String world, String environment) {
            calls.add("import " + world + " " + environment);
            String f = fail();
            if (f != null) {
                return f;
            }
            known.put(world.toLowerCase(Locale.ROOT), new WorldStatePlanner.Known(world, true, true, environment));
            players.put(world, 0);
            return null;
        }

        @Override
        public String setAutoLoad(String world, boolean autoLoad) {
            calls.add("autoload " + world + " " + autoLoad);
            if (ignoreAutoLoad) {
                return null;
            }
            WorldStatePlanner.Known k = known.get(world.toLowerCase(Locale.ROOT));
            known.put(world.toLowerCase(Locale.ROOT), new WorldStatePlanner.Known(world, k.loaded(), autoLoad, k.environment()));
            return null;
        }

        @Override
        public Optional<Reread> describe(String world) {
            WorldStatePlanner.Known k = known.get(world.toLowerCase(Locale.ROOT));
            boolean loaded = players.containsKey(world);
            if (k == null && !loaded) {
                return Optional.empty();
            }
            return Optional.of(new Reread(loaded, k != null, k == null ? null : k.autoLoad(),
                    k == null ? "NORMAL" : k.environment()));
        }
    }
}
