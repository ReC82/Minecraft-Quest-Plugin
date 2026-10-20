package com.lodygames.rpgquest.world.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Validation pure d'une demande de création de monde (issue #562) : refus en français, JSON plat. */
class WorldCreationPlannerTest {

    private static WorldCreationPlanner.Plan plan(WorldCreationRequest r) {
        return WorldCreationPlanner.plan(r, WorldCreationFixtures.context());
    }

    private static String errors(WorldCreationPlanner.Plan p) {
        return String.join(" | ", p.errors());
    }

    @Test
    void identifierIsDerivedFromTheTypedName() {
        assertEquals("terres_gelees", WorldCreationPlanner.identifier("  Terres gelées "));
        assertEquals("ile-du-nord_2", WorldCreationPlanner.identifier("Île-du-Nord !! 2"));
        assertEquals("", WorldCreationPlanner.identifier("  ¤¤ "));
    }

    @Test
    void normalWorldIsAccepted() {
        WorldCreationPlanner.Plan p = plan(WorldCreationFixtures.normal("Terres gelées"));
        assertTrue(p.ok(), errors(p));
        assertEquals("terres_gelees", p.world());
        assertNull(p.seed());
        assertNull(p.generatorSettings());
        assertTrue(p.summary().stream().anyMatch(s -> s.contains("Aucun rôle Hub / Wild / Claims")));
        assertEquals(24, p.fingerprint().length());
    }

    @Test
    void takenNamesAreRefusedWhateverTheirCase() {
        assertTrue(errors(plan(WorldCreationFixtures.normal("WILD"))).contains("monde essentiel"));
        assertTrue(errors(plan(WorldCreationFixtures.normal("world_the_end"))).contains("monde essentiel"));
        assertTrue(errors(plan(WorldCreationFixtures.normal("world_nether"))).contains("monde essentiel"));
        assertTrue(errors(plan(WorldCreationFixtures.normal("ancien"))).contains("Multiverse-Core connaît déjà"));
        assertTrue(errors(plan(WorldCreationFixtures.normal("terres_perdues"))).contains("dossier"));
        assertTrue(errors(plan(WorldCreationFixtures.normal("plugins"))).contains("dossier"));
        assertTrue(errors(plan(WorldCreationFixtures.normal("crash-reports"))).contains("réservé"));
        assertTrue(errors(plan(WorldCreationFixtures.normal("ab"))).contains("invalide"));
        assertTrue(errors(plan(WorldCreationFixtures.normal(""))).contains("manquant"));
    }

    @Test
    void netherAndEndRefuseOtherTerrains() {
        for (String terrain : List.of("FLAT", "AMPLIFIED", "LARGE_BIOMES")) {
            var p = plan(new WorldCreationRequest("enfer", "NETHER", terrain, "", false, "", List.of(), ""));
            assertFalse(p.ok());
            assertTrue(errors(p).contains("n'existe que pour un monde normal"), errors(p));
        }
        assertTrue(plan(new WorldCreationRequest("fin", "THE_END", "NORMAL", "", false, "", List.of(), "")).ok());
        assertFalse(plan(new WorldCreationRequest("fin", "OVERWORLD", "NORMAL", "", false, "", List.of(), "")).ok());
    }

    @Test
    void flatOptionsAreRefusedOutsideAFlatWorld() {
        var p = plan(new WorldCreationRequest("terres", "NORMAL", "AMPLIFIED", "", false, "NEIGE", List.of(), ""));
        assertTrue(errors(p).contains("qu'à un monde normal au terrain plat"), errors(p));
        var q = plan(new WorldCreationRequest("terres", "NORMAL", "NORMAL", "", false, "", List.of(),
                "minecraft:desert"));
        assertFalse(q.ok());
    }

    @Test
    void seedAcceptsNumbersAndTextLikeMinecraft() {
        var n = plan(new WorldCreationRequest("terres", "NORMAL", "NORMAL", "-123", false, "", List.of(), ""));
        assertEquals(-123L, n.seed());
        var t = plan(new WorldCreationRequest("terres", "NORMAL", "NORMAL", "glace", false, "", List.of(), ""));
        assertEquals((long) "glace".hashCode(), t.seed());
        assertTrue(t.seedLabel().contains("glace"));
        var big = plan(new WorldCreationRequest("terres", "NORMAL", "NORMAL", "99999999999999999999", false, "",
                List.of(), ""));
        assertTrue(errors(big).contains("hors limites"));
        assertNotEquals(n.fingerprint(), t.fingerprint());
    }

    @Test
    void flatPresetProducesVanillaGeneratorSettings() {
        var p = plan(new WorldCreationRequest("terres", "NORMAL", "FLAT", "", false, "NEIGE", List.of(), ""));
        assertTrue(p.ok(), errors(p));
        assertEquals("{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:dirt\","
                + "\"height\":2},{\"block\":\"minecraft:snow_block\",\"height\":1}],\"biome\":\"minecraft:snowy_plains\","
                + "\"features\":false,\"lakes\":false,\"structure_overrides\":[]}", p.generatorSettings());
    }

    @Test
    void customLayersAndBiomeOverrideThePreset() {
        var p = plan(new WorldCreationRequest("terres", "NORMAL", "FLAT", "", true, "CLASSIQUE",
                List.of(new WorldCreationRequest.Layer("bedrock", 1), new WorldCreationRequest.Layer("STONE", 10)),
                "desert"));
        assertTrue(p.ok(), errors(p));
        assertEquals("minecraft:desert", p.biome());
        assertTrue(p.generatorSettings().contains("{\"block\":\"minecraft:stone\",\"height\":10}"));
        assertTrue(p.generatorSettings().endsWith("\"structure_overrides\":[\"minecraft:villages\","
                + "\"minecraft:strongholds\"]}"), p.generatorSettings());
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("villages et des forteresses")));
    }

    @Test
    void unknownBlocksBiomesAndHeightsAreRefused() {
        var p = plan(new WorldCreationRequest("terres", "NORMAL", "FLAT", "", false, "", List.of(
                new WorldCreationRequest.Layer("minecraft:diamond_sword", 1),
                new WorldCreationRequest.Layer("dirt", 0),
                new WorldCreationRequest.Layer("stone", 384)), "minecraft:lune"));
        String e = errors(p);
        assertTrue(e.contains("Bloc inconnu du serveur : « minecraft:diamond_sword »"), e);
        assertTrue(e.contains("Épaisseur invalide"), e);
        assertTrue(e.contains("Épaisseur totale 385"), e);
        assertTrue(e.contains("Biome inconnu"), e);
        assertTrue(errors(plan(new WorldCreationRequest("terres", "NORMAL", "FLAT", "", false, "LUNE", List.of(), "")))
                .contains("Préréglage"));
    }

    @Test
    void creationIsImpossibleWithoutMultiverse() {
        var ctx = WorldCreationFixtures.context();
        var without = new WorldCreationPlanner.Context(false, "Multiverse-Core n'est pas installé", ctx.mainWorld(),
                ctx.loaded(), ctx.multiverseKnown(), ctx.folders(), ctx.essential(), ctx.blockValid(), ctx.biomeValid());
        var p = WorldCreationPlanner.plan(WorldCreationFixtures.normal("terres"), without);
        assertFalse(p.ok());
        assertTrue(errors(p).contains("Multiverse-Core n'est pas installé"), errors(p));
    }
}
