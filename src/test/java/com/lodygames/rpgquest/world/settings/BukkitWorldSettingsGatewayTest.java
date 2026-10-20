package com.lodygames.rpgquest.world.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Passerelle Bukkit des réglages (issue #561) sur des mondes MockBukkit isolés : lecture des
 * valeurs réelles, un seul chemin d'écriture, aucun effet sur un autre monde.
 */
class BukkitWorldSettingsGatewayTest {

    private ServerMock server;
    private World target;
    private World other;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        target = server.addSimpleWorld("terres");
        other = server.addSimpleWorld("autre");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private BukkitWorldSettingsGateway gateway(MultiverseSettingsPort mv) {
        return new BukkitWorldSettingsGateway(server, mv,
                () -> new BukkitWorldSettingsGateway.Roles("world_hub", "terres", "claims"));
    }

    @Test
    void readsRealValuesRulesAndRolesOfALoadedWorld() {
        target.setDifficulty(Difficulty.HARD);
        target.setGameRule(GameRules.KEEP_INVENTORY, true);
        var s = gateway(MultiverseSettingsPort.none()).read("terres").orElseThrow();
        assertFalse(s.multiverse());
        assertEquals(List.of("WILD"), s.roles());
        assertEquals("HARD", s.values().get("difficulty"));
        assertEquals("BUKKIT", s.writers().get("difficulty"));
        assertEquals("GAMERULE", s.writers().get("pvp"));
        assertTrue(s.unavailable().containsKey("animals"));
        assertTrue(s.unavailable().containsKey("gamemode"));
        assertEquals("true", s.rule("keep_inventory").orElseThrow().value());
        assertTrue(s.rules().stream().noneMatch(r -> r.name().contains(":")), "noms sans espace de noms");
        assertTrue(gateway(MultiverseSettingsPort.none()).read("absent").isEmpty(), "jamais chargé");
    }

    @Test
    void writesOnlyTheTargetWorld() {
        var gw = gateway(MultiverseSettingsPort.none());
        var s = gw.read("terres").orElseThrow();
        Map<String, String> req = Map.of("difficulty", "PEACEFUL".equals(s.values().get("difficulty")) ? "EASY" : "PEACEFUL",
                "rule.keep_inventory", "true", "time", "6000", "border_size", "2000", "border_center", "10,20",
                "spawn", "5,70,6,90");
        // Rôle WILD ici : la difficulté Paisible serait refusée, on prend Difficile.
        req = new java.util.HashMap<>(req);
        req.put("difficulty", "HARD");
        var plan = WorldSettingsPlanner.plan(s, req);
        Difficulty otherDifficulty = other.getDifficulty();
        for (var c : plan.changes()) {
            if (c.status() == WorldSettingsPlanner.Status.APPLY) {
                assertNull(gw.write("terres", c), c.key());
            }
        }
        assertNull(gw.persist("terres"), "rien à enregistrer côté Multiverse");
        var after = gw.read("terres").orElseThrow();
        assertEquals("HARD", after.values().get("difficulty"));
        assertEquals("true", after.rule("keep_inventory").orElseThrow().value());
        assertEquals("6000", after.values().get("time"));
        assertEquals("2000", after.values().get("border_size"));
        assertEquals("10,20", after.values().get("border_center"));
        // MockBukkit ne conserve pas l'orientation du point d'arrivée (Paper, si) : coordonnées seules ici.
        assertTrue(after.values().get("spawn").startsWith("5,70,6,"), after.values().get("spawn"));
        assertEquals(otherDifficulty, other.getDifficulty(), "l'autre monde n'a pas bougé");
        assertFalse(other.getGameRuleValue(GameRules.KEEP_INVENTORY));
        assertEquals(other.getWorldBorder().getSize(), server.getWorld("autre").getWorldBorder().getSize());
        assertTrue(other.getWorldBorder().getSize() > 2000);
    }

    @Test
    void multiverseWorldsAreWrittenThroughMultiverseOnlyAndSaved() {
        List<String> calls = new ArrayList<>();
        MultiverseSettingsPort mv = new MultiverseSettingsPort() {
            @Override
            public Optional<Map<String, String>> read(String world) {
                return "terres".equals(world) ? Optional.of(Map.of("difficulty", "EASY", "pvp", "true", "monsters",
                        "true", "animals", "true", "gamemode", "SURVIVAL", "spawn", "0,64,0,0", "allow_weather", "false"))
                        : Optional.empty();
            }

            @Override
            public String write(String world, String key, String value) {
                calls.add(world + ":" + key + "=" + value);
                return null;
            }

            @Override
            public String save() {
                calls.add("save");
                return null;
            }
        };
        var gw = gateway(mv);
        var s = gw.read("terres").orElseThrow();
        assertTrue(s.multiverse());
        assertEquals("MULTIVERSE", s.writers().get("pvp"));
        assertEquals("MULTIVERSE", s.writers().get("animals"));
        assertTrue(s.notes().stream().anyMatch(n -> n.contains("allow-weather")), s.notes().toString());
        Difficulty before = target.getDifficulty();
        var change = WorldSettingsPlanner.plan(s, Map.of("animals", "false")).changes().get(0);
        assertNull(gw.write("terres", change));
        assertNull(gw.persist("terres"));
        assertNull(gw.persist("terres"), "un seul enregistrement par application");
        assertEquals(List.of("terres:animals=false", "save"), calls);
        assertEquals(before, target.getDifficulty(), "aucune écriture Bukkit en parallèle");
    }

    @Test
    void unreadableMultiverseMakesItsSettingsUnavailableInsteadOfFallingBackToBukkit() {
        MultiverseSettingsPort broken = new MultiverseSettingsPort() {
            @Override
            public Optional<Map<String, String>> read(String world) {
                return Optional.of(Map.of(ERROR_KEY, "Multiverse-Core illisible (NoSuchMethodError)"));
            }

            @Override
            public String write(String world, String key, String value) {
                return "non";
            }

            @Override
            public String save() {
                return null;
            }
        };
        var s = gateway(broken).read("terres").orElseThrow();
        assertFalse(s.values().containsKey("difficulty"));
        assertTrue(s.unavailable().get("difficulty").contains("illisible"));
        assertTrue(s.values().containsKey("time"), "les réglages Bukkit restent disponibles");
    }

    @Test
    void playerPositionOnlyInsideThatWorld() {
        var player = server.addPlayer("Lody");
        player.teleport(new org.bukkit.Location(target, 12.7, 70, -3.2, 45f, 0f));
        assertEquals(Optional.of("12,70,-4,45"), gateway(MultiverseSettingsPort.none()).playerPosition("terres", "Lody"));
        assertTrue(gateway(MultiverseSettingsPort.none()).playerPosition("autre", "Lody").isEmpty());
    }

    // ---- Issue #81 : nom affiché ---------------------------------------------------------------

    @Test
    void displayNameOfAWorldOutsideMultiverseGoesToTheRpgQuestFileOnly(@org.junit.jupiter.api.io.TempDir
                                                                         java.nio.file.Path dir) {
        var names = new com.lodygames.rpgquest.world.WorldDisplayNames(dir.resolve("world-names.yml"), Runnable::run,
                org.slf4j.LoggerFactory.getLogger("test"));
        var gw = new BukkitWorldSettingsGateway(server, MultiverseSettingsPort.none(),
                () -> new BukkitWorldSettingsGateway.Roles("world_hub", "terres", "claims"), names);
        var s = gw.read("terres").orElseThrow();
        assertEquals("-", s.values().get("alias"));
        assertEquals("RPGQUEST", s.writers().get("alias"));
        var change = WorldSettingsPlanner.plan(s, Map.of("alias", "Terres sauvages")).changes().get(0);
        assertNull(gw.write("terres", change));
        assertNull(gw.persist("terres"), "aucun enregistrement Multiverse");
        assertEquals("Terres sauvages", gw.read("terres").orElseThrow().values().get("alias"), "relu");
        assertEquals("-", gw.read("autre").orElseThrow().values().get("alias"), "autre monde intact");
        assertEquals("terres", gw.read("terres").orElseThrow().world(), "identifiant technique inchangé");
        assertTrue(server.getWorld("terres") != null);

        var reloaded = new com.lodygames.rpgquest.world.WorldDisplayNames(dir.resolve("world-names.yml"),
                Runnable::run, org.slf4j.LoggerFactory.getLogger("test"));
        reloaded.load();
        assertEquals(Optional.of("Terres sauvages"), reloaded.get("TERRES"), "conservé au redémarrage");

        var reset = WorldSettingsPlanner.plan(gw.read("terres").orElseThrow(), Map.of("alias", "-")).changes().get(0);
        assertNull(gw.write("terres", reset));
        assertEquals("-", gw.read("terres").orElseThrow().values().get("alias"), "retour au nom par défaut");
    }

    @Test
    void displayNameOfAMultiverseWorldIsItsMultiverseAlias() {
        List<String> calls = new ArrayList<>();
        MultiverseSettingsPort mv = new MultiverseSettingsPort() {
            @Override
            public Optional<Map<String, String>> read(String world) {
                return Optional.of(Map.of("alias", "-", "difficulty", "EASY", "pvp", "true", "monsters", "true",
                        "animals", "true", "gamemode", "SURVIVAL", "spawn", "0,64,0,0", "allow_weather", "true"));
            }

            @Override
            public String write(String world, String key, String value) {
                calls.add(world + ":" + key + "=" + value);
                return null;
            }

            @Override
            public String save() {
                calls.add("save");
                return null;
            }
        };
        var gw = gateway(mv);
        var s = gw.read("terres").orElseThrow();
        assertEquals("MULTIVERSE", s.writers().get("alias"));
        var change = WorldSettingsPlanner.plan(s, Map.of("alias", "Village")).changes().get(0);
        assertNull(gw.write("terres", change));
        assertNull(gw.persist("terres"));
        assertEquals(List.of("terres:alias=Village", "save"), calls);
    }

    @Test
    void withoutNameFileTheDisplayNameOfANonMultiverseWorldIsUnavailable() {
        var s = gateway(MultiverseSettingsPort.none()).read("terres").orElseThrow();
        assertFalse(s.values().containsKey("alias"));
        assertTrue(s.unavailable().containsKey("alias"));
    }
}
