package com.lodygames.rpgquest.world.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.world.settings.WorldSettingsPlanner.Change;
import com.lodygames.rpgquest.world.settings.WorldSettingsPlanner.Status;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Aperçu des réglages d'un monde (issue #561) : normalisation, refus de protection, empreinte. */
class WorldSettingsPlannerTest {

    private static Change only(WorldSettingsSnapshot s, String key, String value) {
        var plan = WorldSettingsPlanner.plan(s, Map.of(key, value));
        assertEquals(1, plan.changes().size());
        return plan.changes().get(0);
    }

    private static WorldSettingsSnapshot plain() {
        return WorldSettingsFixtures.snapshot("terres", List.of(), 0);
    }

    @Test
    void showsCurrentProposedWriterAndEffect() {
        Change c = only(plain(), "difficulty", "hard");
        assertEquals(Status.APPLY, c.status());
        assertEquals("EASY", c.current());
        assertEquals("HARD", c.proposed());
        assertEquals("BUKKIT", c.writer());
        assertTrue(c.effect().contains("Difficulté"), c.effect());
    }

    @Test
    void sameValueIsUnchangedAndNeverWritten() {
        assertEquals(Status.UNCHANGED, only(plain(), "pvp", "true").status());
        assertEquals(Status.UNCHANGED, only(plain(), "spawn", "0, 64, 0").status(), "forme normalisée");
    }

    @Test
    void invalidValuesAreRefusedInFrench() {
        Change time = only(plain(), "time", "30000");
        assertEquals(Status.REFUSED, time.status());
        assertNull(time.proposed());
        assertTrue(time.reason().contains("23999"), time.reason());
        assertEquals(Status.REFUSED, only(plain(), "difficulty", "IMPOSSIBLE").status());
        assertEquals(Status.REFUSED, only(plain(), "border_size", "0").status());
        assertEquals(Status.REFUSED, only(plain(), "spawn", "0,400,0").status(), "au-dessus du monde");
        assertEquals(Status.REFUSED, only(plain(), "rule.random_tick_speed", "vite").status());
        assertEquals(Status.REFUSED, only(plain(), "unknown_key", "1").status());
    }

    @Test
    void unavailableSettingCarriesItsReason() {
        Change c = only(plain(), "animals", "false");
        assertEquals(Status.REFUSED, c.status());
        assertEquals("pas de Multiverse", c.reason());
    }

    @Test
    void gameRulesComeFromTheRuntimeListAndDedicatedOnesHaveASinglePath() {
        Change keep = only(plain(), "rule.keep_inventory", "true");
        assertEquals(Status.APPLY, keep.status());
        assertEquals("GAMERULE", keep.writer());
        assertEquals("4", only(plain(), "rule.random_tick_speed", "4").proposed());
        Change unknown = only(plain(), "rule.do_daylight_cycle", "false");
        assertEquals(Status.REFUSED, unknown.status(), "ancien nom absent du registre 1.21.11");
        Change pvpRule = only(plain(), "rule.pvp", "false");
        assertEquals(Status.REFUSED, pvpRule.status());
        assertTrue(pvpRule.reason().contains("un seul chemin"), pvpRule.reason());
    }

    @Test
    void hubKeepsPermanentDayAndWeatherAndItsProtections() {
        var hub = WorldSettingsFixtures.snapshot("world_hub", List.of("HUB"), 0);
        for (String key : List.of("advance_time", "time", "advance_weather", "weather")) {
            String value = switch (key) {
                case "time" -> "18000";
                case "weather" -> "RAIN";
                default -> "false";
            };
            Change c = only(hub, key, value);
            assertEquals(Status.REFUSED, c.status(), key);
            assertTrue(c.reason().contains("Hub"), c.reason());
        }
        var hubPvpOff = only(hub, "pvp", "false");
        assertEquals(Status.APPLY, hubPvpOff.status(), "désactiver ne contredit rien");
        var hubDifficulty = only(hub, "difficulty", "PEACEFUL");
        assertEquals(Status.APPLY, hubDifficulty.status());
    }

    @Test
    void hubAndClaimsRefusePvpAndMonstersBeingTurnedOn() {
        for (String role : List.of("HUB", "CLAIMS")) {
            var s = new WorldSettingsSnapshot("x", false, List.of(role), 0, -64, 320,
                    withValue(WorldSettingsFixtures.bukkitValues(), Map.of("pvp", "false", "monsters", "false")),
                    WorldSettingsFixtures.writers(false), Map.of(), WorldSettingsFixtures.rules(), List.of());
            assertEquals(Status.REFUSED, only(s, "pvp", "true").status(), role);
            assertEquals(Status.REFUSED, only(s, "monsters", "true").status(), role);
        }
        var claims = WorldSettingsFixtures.snapshot("claims", List.of("CLAIMS"), 0);
        assertEquals(Status.APPLY, only(claims, "time", "18000").status(), "claims garde un cycle normal");
    }

    @Test
    void wildRefusesPeacefulAndWarnsWithoutMonsters() {
        var wild = WorldSettingsFixtures.snapshot("wild", List.of("WILD"), 0);
        Change peaceful = only(wild, "difficulty", "PEACEFUL");
        assertEquals(Status.REFUSED, peaceful.status());
        assertTrue(peaceful.reason().contains("boss"), peaceful.reason());
        var plan = WorldSettingsPlanner.plan(wild, Map.of("monsters", "false"));
        assertEquals(Status.APPLY, plan.changes().get(0).status());
        assertEquals(1, plan.warnings().size());
    }

    @Test
    void gameModeIsRefusedWhilePlayersArePresent() {
        assertEquals(Status.REFUSED, only(WorldSettingsFixtures.multiverseSnapshot("t", 2), "gamemode", "ADVENTURE")
                .status());
        Change empty = only(WorldSettingsFixtures.multiverseSnapshot("t", 0), "gamemode", "adventure");
        assertEquals(Status.APPLY, empty.status());
        assertEquals("MULTIVERSE", empty.writer());
        assertTrue(empty.effect().contains("Multiverse-Core"), empty.effect());
    }

    @Test
    void borderMayNotLeaveTheSpawnOutside() {
        Map<String, String> req = new LinkedHashMap<>();
        req.put("border_center", "1000, 1000");
        req.put("border_size", "100");
        var plan = WorldSettingsPlanner.plan(plain(), req);
        assertTrue(plan.changes().stream().allMatch(c -> c.status() == Status.REFUSED), plan.toString());
        req.put("spawn", "1000,70,1000,90");
        var moved = WorldSettingsPlanner.plan(plain(), req);
        assertEquals(3, moved.applicable(), "arrivée déplacée dans la nouvelle bordure : " + moved);
        assertEquals("1000,70,1000,90", moved.changes().stream().filter(c -> c.key().equals("spawn")).findFirst()
                .orElseThrow().proposed());
    }

    @Test
    void fingerprintFollowsTheRealValues() {
        String a = WorldSettingsPlanner.fingerprint(plain());
        assertEquals(a, WorldSettingsPlanner.fingerprint(plain()));
        assertEquals(24, a.length());
        var changed = new WorldSettingsSnapshot("terres", false, List.of(), 0, -64, 320,
                withValue(WorldSettingsFixtures.bukkitValues(), Map.of("time", "1001")),
                WorldSettingsFixtures.writers(false), Map.of(), WorldSettingsFixtures.rules(), List.of());
        assertNotEquals(a, WorldSettingsPlanner.fingerprint(changed));
    }

    @Test
    void canonicalNumbersAndSpawn() {
        assertEquals("12", WorldSettingsPlanner.number(12.0));
        assertEquals("12.5", WorldSettingsPlanner.number(12.5));
        assertEquals("-3,64,10,-90", WorldSettingsPlanner.spawnValue(-2.5, 64.9, 10.2, 270));
        assertEquals("12h00", WorldSettingsPlanner.timeLabel(6000));
        assertEquals("00h00", WorldSettingsPlanner.timeLabel(18000));
    }

    private static Map<String, String> withValue(Map<String, String> base, Map<String, String> over) {
        Map<String, String> m = new LinkedHashMap<>(base);
        m.putAll(over);
        return m;
    }

    // ---- Issue #81 : nom affiché ---------------------------------------------------------------

    private static WorldSettingsSnapshot withAlias(String alias, String writer) {
        Map<String, String> values = new LinkedHashMap<>(WorldSettingsFixtures.bukkitValues());
        values.put("alias", alias);
        Map<String, String> writers = new LinkedHashMap<>(WorldSettingsFixtures.writers(false));
        writers.put("alias", writer);
        return new WorldSettingsSnapshot("wild", false, List.of("WILD"), 0, -64, 320, values, writers, Map.of(),
                WorldSettingsFixtures.rules(), List.of());
    }

    @Test
    void displayNameIsNormalizedAndKeepsTheTechnicalId() {
        Change c = only(withAlias("-", WorldSettingsSnapshot.WRITER_RPGQUEST), "alias", "  Terres   sauvages ");
        assertEquals(Status.APPLY, c.status());
        assertEquals("-", c.current(), "aucun nom personnalisé = défaut");
        assertEquals("Terres sauvages", c.proposed());
        assertEquals("RPGQUEST", c.writer());
        assertTrue(c.effect().contains("« wild » ne change pas"), c.effect());
        assertTrue(c.effect().contains("world-names.yml"), c.effect());
    }

    @Test
    void displayNameUnchangedResetAndInvalidValues() {
        WorldSettingsSnapshot named = withAlias("Bois", WorldSettingsSnapshot.WRITER_MULTIVERSE);
        assertEquals(Status.UNCHANGED, only(named, "alias", " Bois ").status());
        Change reset = only(named, "alias", "-");
        assertEquals(Status.APPLY, reset.status());
        assertEquals("-", reset.proposed());
        assertTrue(reset.effect().contains("nom par défaut"), reset.effect());
        assertTrue(reset.effect().contains("Multiverse-Core"), reset.effect());
        for (String bad : List.of("<red>Bois", "&cBois", "§cBois", "Bo\u0007is", "x".repeat(33))) {
            Change c = only(named, "alias", bad);
            assertEquals(Status.REFUSED, c.status(), bad);
            assertNull(c.proposed());
            assertTrue(c.reason().startsWith("Nom attendu"), c.reason());
        }
    }

    @Test
    void displayNameChangesTheFingerprint() {
        assertNotEquals(WorldSettingsPlanner.fingerprint(withAlias("-", "RPGQUEST")),
                WorldSettingsPlanner.fingerprint(withAlias("Bois", "RPGQUEST")));
    }
}
