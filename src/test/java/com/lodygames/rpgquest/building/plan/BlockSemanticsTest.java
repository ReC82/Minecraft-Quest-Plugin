package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #94, lot A — la sémantique des blocs est-elle <strong>déclarée</strong>, et non devinée ?
 *
 * <p>Ces tests valent surtout comme garde-fou contre la régression la plus tentante : remplacer ce
 * registre par une recherche de sous-chaîne. Chaque cas ci-dessous est un endroit où un
 * {@code contains} se tromperait.</p>
 */
class BlockSemanticsTest {

    // ---- Ce qu'un contains() raterait ---------------------------------------------------------

    @Test
    @DisplayName("Les sources de lumière qui ne contiennent ni « torch » ni « lantern » sont reconnues")
    void lightSourcesWithoutObviousNamesAreRecognised() {
        for (String id : new String[] {"minecraft:glowstone", "minecraft:shroomlight",
            "minecraft:redstone_lamp", "minecraft:sea_lantern", "minecraft:campfire",
            "minecraft:end_rod", "minecraft:ochre_froglight"}) {
            assertTrue(BlockSemantics.lightSource(id),
                    id + " éclaire, et aucune recherche de « torch » ou « lantern » ne le dirait");
        }
    }

    @Test
    @DisplayName("Le NIVEAU de lumière décide, pas la ressemblance du nom")
    void thelightLevelDecidesNotTheName() {
        // Une bougie et un lichen lumineux portent bien le rôle LIGHT, mais sous le seuil utile :
        // ils décorent, ils n'éclairent pas une salle.
        assertTrue(BlockSemantics.has("minecraft:candle", BlockSemantics.Role.LIGHT));
        assertFalse(BlockSemantics.lightSource("minecraft:candle"),
                "une bougie à 3 ne doit pas compter comme éclairage");
        assertFalse(BlockSemantics.lightSource("minecraft:glow_lichen"));
        // Les variantes « soul » sont à 10, pile au seuil : elles comptent.
        assertTrue(BlockSemantics.lightSource("minecraft:soul_lantern"));
        assertTrue(BlockSemantics.lightSource("minecraft:soul_torch"));
    }

    @Test
    @DisplayName("« glass_pane » est reconnu, alors qu'il ne finit pas par « _glass_pane »")
    void glassPaneIsRecognisedDespiteTheGrammarTrap() {
        // Le piège qui a été attrapé par un test pendant l'écriture du lot : la famille par suffixe
        // « _glass_pane » ne couvre PAS le bloc nu « glass_pane ».
        assertTrue(BlockSemantics.glazing("minecraft:glass_pane"));
        assertTrue(BlockSemantics.glazing("minecraft:glass"));
        assertTrue(BlockSemantics.glazing("minecraft:tinted_glass"));
        // Et les familles couvrent bien les variantes colorées.
        assertTrue(BlockSemantics.glazing("minecraft:lime_stained_glass"));
        assertTrue(BlockSemantics.glazing("minecraft:black_stained_glass_pane"));
    }

    @Test
    @DisplayName("Un vitrage n'est PAS franchissable : une fenêtre ferme l'enveloppe")
    void glazingIsNotPassable() {
        assertFalse(BlockSemantics.passable("minecraft:glass"),
                "sinon toute fenêtre serait comptée comme un trou dans l'enveloppe");
        assertTrue(BlockSemantics.support("minecraft:glass"));
    }

    // ---- L'extensibilité du lot B -------------------------------------------------------------

    /**
     * Le test qui prouve la promesse faite au lot B (#250) : les blocs que ses primitives émettront
     * sont <strong>déjà</strong> reconnus. L'audit n'aura pas à changer.
     */
    @Test
    @DisplayName("Les blocs que produiront les primitives du lot B sont déjà reconnus")
    void theblocksTheLotBPrimitivesWillEmitAreAlreadyUnderstood() {
        // Une porte s'ouvre : elle est franchissable au sens architectural, donc le contrôle
        // d'entrée la verra comme un passage — exactement comme un percement brut.
        for (String door : new String[] {"minecraft:oak_door", "minecraft:dark_oak_door",
            "minecraft:iron_door", "minecraft:spruce_trapdoor", "minecraft:oak_fence_gate"}) {
            assertTrue(BlockSemantics.passable(door), door + " doit être franchissable");
        }
        // Les états de bloc ne gênent pas la lecture : le rôle vient du TYPE.
        assertTrue(BlockSemantics.passable("minecraft:oak_door[half=lower,facing=west,hinge=left]"));
        assertTrue(BlockSemantics.glazing("minecraft:glass_pane[north=true,south=true]"));
        assertTrue(BlockSemantics.lightSource("minecraft:lantern[hanging=true]"));
        // Grimpable : c'est ce qui rendra l'escalier/échelle du lot B visible par le contrôle de
        // circulation, sans toucher à l'audit.
        assertTrue(BlockSemantics.climbable("minecraft:ladder[facing=north]"));
        assertTrue(BlockSemantics.climbable("minecraft:scaffolding"));
    }

    // ---- Le défaut conservateur ---------------------------------------------------------------

    @Test
    @DisplayName("Un bloc sans rôle déclaré ne reçoit AUCUNE qualité")
    void ablockWithoutADeclaredRoleGetsNoQualityAtAll() {
        for (String ordinary : new String[] {"minecraft:stone_bricks", "minecraft:oak_planks",
            "minecraft:dark_oak_stairs", "minecraft:elven_marble", "minecraft:cobblestone"}) {
            assertFalse(BlockSemantics.glazing(ordinary), ordinary);
            assertFalse(BlockSemantics.lightSource(ordinary), ordinary);
            assertFalse(BlockSemantics.passable(ordinary), ordinary);
            assertFalse(BlockSemantics.climbable(ordinary), ordinary);
            assertTrue(BlockSemantics.support(ordinary), ordinary + " doit porter");
        }
        // « hasDeclaredRole » ne veut pas dire « ce bloc existe » : un mur de briques n'a pas de
        // rôle, et c'est correct.
        assertFalse(BlockSemantics.hasDeclaredRole("minecraft:stone_bricks"));
        assertTrue(BlockSemantics.hasDeclaredRole("minecraft:lantern"));
    }

    @Test
    @DisplayName("L'absence de bloc est de l'air, donc franchissable")
    void anAbsentBlockIsAirAndThereforePassable() {
        // Convention du Blueprint : seule la matière est listée.
        assertTrue(BlockSemantics.passable(null));
        assertFalse(BlockSemantics.support(null));
        assertFalse(BlockSemantics.glazing(null));
        assertFalse(BlockSemantics.lightSource(null));
    }

    @Test
    @DisplayName("Le mobilier est reconnu, pour juger si un intérieur est meublé — jamais s'il est beau")
    void furnishingIsRecognisedForDensityNeverForTaste() {
        assertTrue(BlockSemantics.furnishing("minecraft:bookshelf"));
        assertTrue(BlockSemantics.furnishing("minecraft:lectern"));
        assertTrue(BlockSemantics.furnishing("minecraft:barrel"));
        assertTrue(BlockSemantics.furnishing("minecraft:red_bed"));
        assertFalse(BlockSemantics.furnishing("minecraft:stone_bricks"));
    }
}
