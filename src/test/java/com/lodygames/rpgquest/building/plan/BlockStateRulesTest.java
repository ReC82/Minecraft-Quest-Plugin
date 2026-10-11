package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #94, lot D — les propriétés d'un état de bloc sont-elles vérifiées, et pas seulement sa
 * grammaire ?
 */
class BlockStateRulesTest {

    /** Le cas qui a motivé le lot : l'état exact écrit par le modèle dans le plan V1. */
    @Test
    @DisplayName("« oak_stairs[face=bottom] » est refusé : la propriété s'appelle « half »")
    void thev1InvalidStairStateIsRefused() {
        String bad = "minecraft:oak_stairs[face=bottom,shape=straight]";

        // La FORME est correcte — c'est précisément pour cela qu'il était passé.
        assertTrue(BlockState.valid(bad), "la grammaire est valide, et c'était le problème");

        assertFalse(BlockStateRules.check(bad).valid());
        String refusal = BlockStateRules.refusal(bad).orElseThrow();
        assertTrue(refusal.contains("face"), refusal);
        assertTrue(refusal.contains("half"), "le message doit nommer la bonne propriété : " + refusal);
    }

    @Test
    @DisplayName("Les états corrects des blueprints qui fonctionnent sont acceptés")
    void thestatesFromTheWorkingBlueprintsAreAccepted() {
        // Extraits tels quels de TestHutBlueprint et TestWatchtowerBlueprint.
        for (String good : new String[] {
            "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]",
            "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]",
            "minecraft:glass_pane[east=true,north=false,south=false,waterlogged=false,west=true]",
            "minecraft:oak_slab[type=bottom,waterlogged=false]",
            "minecraft:oak_log[axis=y]",
            "minecraft:wall_torch[facing=north]",
            "minecraft:lantern[hanging=true,waterlogged=false]",
            "minecraft:ladder[facing=west,waterlogged=false]",
            "minecraft:spruce_trapdoor[facing=east,half=top,open=false,powered=false,waterlogged=false]"}) {
            assertTrue(BlockStateRules.check(good).valid(),
                    "doit être accepté : " + good + " → " + BlockStateRules.refusal(good).orElse(""));
        }
    }

    @Test
    @DisplayName("Tout ce que le moteur fabrique lui-même est valide par construction")
    void everythingTheEngineBuildsIsValidByConstruction() {
        List<String> produced = new ArrayList<>();
        for (BlockStates.Dir d : BlockStates.Dir.values()) {
            produced.add(BlockStates.door("minecraft:oak_door", d, false, false));
            produced.add(BlockStates.door("minecraft:oak_door", d, true, true));
            produced.add(BlockStates.stairs("minecraft:oak_stairs", d, false));
            produced.add(BlockStates.stairs("minecraft:stone_brick_stairs", d, true));
            produced.add(BlockStates.wallTorch("minecraft:wall_torch", d));
            produced.add(BlockStates.ladder("minecraft:ladder", d));
            produced.add(BlockStates.trapdoor("minecraft:oak_trapdoor", d, true));
        }
        produced.add(BlockStates.pane("minecraft:glass_pane", true));
        produced.add(BlockStates.pane("minecraft:glass_pane", false));
        produced.add(BlockStates.slab("minecraft:oak_slab", false));
        produced.add(BlockStates.lantern("minecraft:lantern", true));
        produced.add(BlockStates.log("minecraft:oak_log", "x"));

        for (String state : produced) {
            assertTrue(BlockState.valid(state), "forme : " + state);
            assertTrue(BlockStateRules.check(state).valid(),
                    "propriétés : " + state + " → " + BlockStateRules.refusal(state).orElse(""));
        }
    }

    @Test
    @DisplayName("Une valeur hors domaine est refusée, et le message dit ce qui est accepté")
    void anOutOfRangeValueIsRefusedWithTheAcceptedSet() {
        String bad = "minecraft:oak_stairs[facing=up,half=bottom]";

        String refusal = BlockStateRules.refusal(bad).orElseThrow();
        assertTrue(refusal.contains("facing"), refusal);
        assertTrue(refusal.contains("north"), "les valeurs acceptées doivent être listées : " + refusal);
    }

    @Test
    @DisplayName("Une trappe n'est pas jugée avec les propriétés d'une porte")
    void atrapdoorIsNotJudgedWithDoorProperties() {
        // « half=top » est valide pour une trappe, invalide pour une porte (upper/lower).
        assertTrue(BlockStateRules.check(
                "minecraft:oak_trapdoor[facing=north,half=top]").valid());
        assertFalse(BlockStateRules.check(
                "minecraft:oak_door[facing=north,half=top]").valid(),
                "une porte n'a que upper/lower");
    }

    @Test
    @DisplayName("Un bloc d'une famille non décrite n'est pas refusé par ignorance")
    void ablockFromAnUndescribedFamilyIsNotRefusedOutOfIgnorance() {
        // Nous ne décrivons que les familles que le moteur écrit. Refuser l'inconnu interdirait les
        // milliers de blocs du jeu dont nous ne savons rien, alors que la faute observée porte sur
        // ce que nous produisons nous-mêmes.
        assertTrue(BlockStateRules.check("minecraft:campfire[lit=true,signal_fire=false]").valid());
        assertTrue(BlockStateRules.check("minecraft:stone_bricks").valid());
        assertTrue(BlockStateRules.check(null).valid());
    }

    @Test
    @DisplayName("Un plan contenant l'état invalide de la V1 est refusé AVANT toute écriture")
    void aplanWithTheV1InvalidStateIsRefusedBeforeAnyWrite() {
        String yaml = """
                id: essai_etat
                name: "Essai"
                kind: decoration
                front: NORTH
                size:
                  x: 4
                  y: 4
                  z: 4
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [3, 0, 3]
                    block: minecraft:stone_bricks
                  - op: box
                    from: [1, 1, 1]
                    to: [1, 1, 1]
                    block: "minecraft:oak_stairs[face=bottom,shape=straight]"
                """;
        List<String> problems = new ArrayList<>();

        assertTrue(BuildingPlanYaml.read(yaml, problems).isEmpty(), "le plan doit être refusé");
        assertTrue(problems.stream().anyMatch(p -> p.contains("face")),
                "le refus doit nommer la propriété fautive : " + problems);
    }
}
