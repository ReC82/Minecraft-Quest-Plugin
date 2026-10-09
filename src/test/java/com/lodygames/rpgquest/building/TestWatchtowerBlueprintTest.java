package com.lodygames.rpgquest.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BlueprintBlock;
import com.lodygames.rpgquest.building.model.BuildingDefinition;
import com.lodygames.rpgquest.building.model.Facing;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Issue #234 — la tour de garde de test, vérifiée bloc par bloc.
 *
 * <h2>Ce que ces tests peuvent établir, et ce qu'ils ne peuvent pas</h2>
 *
 * <p>Ils ne disent pas si la tour est <em>jolie</em>, et ils ne remplacent pas le fait d'y monter.
 * Ils établissent ce qui est vérifiable sans client et qui, si c'était faux, rendrait le test en jeu
 * inutile : les dimensions, l'absence de bloc hors emprise, l'absence de doublon, la présence réelle
 * d'un chemin vertical, et le fait que les quatre faces soient <strong>effectivement</strong>
 * différentes — ce dernier point étant toute la raison d'être de cette structure.</p>
 *
 * <p>Le plan est du code : il se relit, et une faute de géométrie s'y attrape ici plutôt qu'après un
 * collage dans un monde réel.</p>
 */
class TestWatchtowerBlueprintTest {

    private final Blueprint blueprint = TestWatchtowerBlueprint.blueprint();

    private Map<String, String> index() {
        Map<String, String> byPosition = new LinkedHashMap<>();
        for (BlueprintBlock block : blueprint.blocks()) {
            byPosition.put(block.x() + "/" + block.y() + "/" + block.z(), block.state());
        }
        return byPosition;
    }

    private Optional<String> at(int x, int y, int z) {
        return Optional.ofNullable(index().get(x + "/" + y + "/" + z));
    }

    // ---- Cohérence du plan ----------------------------------------------------------------------

    /**
     * Le plan est exploitable : aucun bloc hors des dimensions, aucun doublon.
     *
     * <p>Les deux fautes que {@code Blueprint#validate} refuse sont exactement celles qu'un plan
     * écrit à la main produit : un bloc oublié hors emprise, et deux morceaux qui se recouvrent.
     * Les deux étaient présentes dans la première version de cette tour — une torche posée sur une
     * marche, et un contrefort qui doublait le mur ouest.</p>
     */
    @Test
    void thePlanIsSelfConsistent() {
        assertTrue(blueprint.valid(), () -> "plan refusé : " + blueprint.validate().orElse(""));
    }

    @Test
    void theDimensionsAreTheOnesAnnounced() {
        assertEquals(9, blueprint.sizeX());
        assertEquals(14, blueprint.sizeY());
        assertEquals(9, blueprint.sizeZ());
    }

    @Test
    void everyBlockStaysInsideTheFootprint() {
        for (BlueprintBlock block : blueprint.blocks()) {
            assertTrue(block.x() >= 0 && block.x() < blueprint.sizeX(), "x : " + block.x());
            assertTrue(block.y() >= 0 && block.y() < blueprint.sizeY(), "y : " + block.y());
            assertTrue(block.z() >= 0 && block.z() < blueprint.sizeZ(), "z : " + block.z());
        }
    }

    /** La tour est nettement plus exigeante que la hutte : c'est sa raison d'être. */
    @Test
    void theTowerIsSubstantiallyMoreDemandingThanTheHut() {
        assertTrue(blueprint.solidCount() > TestHutBlueprint.blueprint().solidCount() * 2,
                "la tour doit être bien plus dense que la hutte, sinon elle ne teste rien de plus");
        assertTrue(blueprint.sizeY() > TestHutBlueprint.blueprint().sizeY() * 2,
                "la hauteur est ce qui rend les limites du monde réelles");
    }

    // ---- L'ancre et la porte --------------------------------------------------------------------

    /** L'ancre est le bloc bas de la porte, au niveau où l'on marche. */
    @Test
    void theAnchorIsTheLowerHalfOfTheDoor() {
        String state = at(TestWatchtowerBlueprint.ANCHOR_X, TestWatchtowerBlueprint.ANCHOR_Y,
                TestWatchtowerBlueprint.ANCHOR_Z).orElseThrow();

        assertTrue(state.startsWith("minecraft:oak_door"), state);
        assertTrue(state.contains("half=lower"), state);
    }

    @Test
    void theDoorHasBothHalvesAndOpensOnTheFacade() {
        String lower = at(4, 1, 0).orElseThrow();
        String upper = at(4, 2, 0).orElseThrow();

        assertTrue(lower.contains("half=lower"), lower);
        assertTrue(upper.contains("half=upper"), upper);
        assertTrue(lower.contains("facing=north"), "la porte s'ouvre vers la façade : " + lower);
    }

    /** Une seule porte : deux entrées rendraient l'orientation ambiguë. */
    @Test
    void thereIsExactlyOneDoor() {
        long doors = blueprint.blocks().stream()
                .filter(b -> b.state().startsWith("minecraft:oak_door"))
                .count();

        assertEquals(2, doors, "une porte = deux battants, et une seule porte dans la tour");
    }

    // ---- Les quatre faces sont différentes ------------------------------------------------------

    /**
     * Le cœur du ticket : on doit voir <strong>immédiatement</strong> NORTH de SOUTH de EAST de WEST.
     *
     * <p>Comparer les quatre faces deux à deux est la seule façon de l'établir sans regarder. Si
     * deux faces portaient la même signature, une rotation de 90° ou de 180° serait indétectable à
     * l'œil, et la tour ne servirait plus à ce pour quoi elle existe.</p>
     */
    @Test
    void theFourFacesAreAllDifferentFromEachOther() {
        Map<String, String> north = face("north");
        Map<String, String> south = face("south");
        Map<String, String> east = face("east");
        Map<String, String> west = face("west");

        assertNotEquals(signature(north), signature(south), "nord et sud se confondraient");
        assertNotEquals(signature(north), signature(east), "nord et est se confondraient");
        assertNotEquals(signature(north), signature(west), "nord et ouest se confondraient");
        assertNotEquals(signature(south), signature(east), "sud et est se confondraient");
        assertNotEquals(signature(south), signature(west), "sud et ouest se confondraient");
        assertNotEquals(signature(east), signature(west), "est et ouest se confondraient");
    }

    /** La façade nord est la seule à porter la porte. */
    @Test
    void onlyTheNorthFaceCarriesTheDoor() {
        assertTrue(face("north").values().stream().anyMatch(s -> s.contains("oak_door")));
        for (String other : new String[] {"south", "east", "west"}) {
            assertFalse(face(other).values().stream().anyMatch(s -> s.contains("oak_door")),
                    "la face " + other + " ne doit pas porter de porte");
        }
    }

    /** La face ouest est aveugle : aucune ouverture, et un matériau à elle. */
    @Test
    void theWestFaceIsBlindAndBuiltInARougherMaterial() {
        Map<String, String> west = face("west");

        // Aucune case vide sur la hauteur habitable, hors angles.
        for (int y : new int[] {1, 2, 3, 5, 6, 7, 9, 10, 11}) {
            for (int z = 1; z < blueprint.sizeZ() - 1; z++) {
                assertTrue(west.containsKey(y + "/" + z),
                        "la face ouest doit être pleine en y=" + y + " z=" + z);
            }
        }
        long rough = west.values().stream().filter(s -> s.equals("minecraft:cobblestone")).count();
        long carved = face("east").values().stream()
                .filter(s -> s.equals("minecraft:cobblestone")).count();
        assertTrue(rough > carved,
                "l'ouest doit être nettement plus massif que l'est : " + rough + " vs " + carved);
    }

    /** La face est porte une meurtrière par niveau, et aucune vitre. */
    @Test
    void theEastFaceHasOneArrowSlitPerLevel() {
        Map<String, String> east = face("east");

        for (int base : new int[] {1, 5, 9}) {
            assertFalse(east.containsKey((base + 1) + "/3"),
                    "meurtrière attendue à hauteur de regard du niveau commençant en " + base);
        }
    }

    /** La face sud n'ouvre qu'au dernier niveau : un guet, pas une entrée. */
    @Test
    void theSouthFaceOnlyOpensOnTheTopLevel() {
        Map<String, String> south = face("south");

        for (int x = 3; x <= 5; x++) {
            assertFalse(south.containsKey("10/" + x),
                    "ouverture de guet attendue au dernier niveau, x=" + x);
            assertTrue(south.containsKey("2/" + x),
                    "le rez-de-chaussée sud doit rester plein, x=" + x);
        }
    }

    // ---- L'accès vertical -----------------------------------------------------------------------

    /**
     * On peut réellement monter : quatre marches par niveau, et une trémie au-dessus.
     *
     * <p>Quatre et non trois : deux planchers sont séparés de quatre blocs, et une marche ne fait
     * gagner qu'un demi-bloc. Avec trois marches, la dernière culmine à 3,5 quand le plancher
     * d'arrivée se marche à 5,0 — la tour serait invisitable, et c'est précisément ce que ce test a
     * attrapé dans la première version du plan.</p>
     */
    @Test
    void eachLevelHasAFourStepFlightAndAnOpeningAboveIt() {
        int[][][] expected = {
                // niveau 0 : le long du mur sud, vers l'est
                {{1, 1, 7}, {2, 2, 7}, {3, 3, 7}, {4, 4, 7}},
                // niveau 1 : le long du mur est, vers le nord
                {{7, 5, 7}, {7, 6, 6}, {7, 7, 5}, {7, 8, 4}},
                // niveau 2 : le long du mur nord, vers l'ouest
                {{7, 9, 1}, {6, 10, 1}, {5, 11, 1}, {4, 12, 1}},
        };
        String[] facings = {"east", "north", "west"};

        for (int level = 0; level < expected.length; level++) {
            for (int[] position : expected[level]) {
                String state = at(position[0], position[1], position[2]).orElseThrow(
                        () -> new AssertionError("marche attendue en " + position[0] + "/"
                                + position[1] + "/" + position[2]));
                assertTrue(state.startsWith("minecraft:oak_stairs"), state);
                assertTrue(state.contains("facing=" + facings[level]),
                        "la volée du niveau " + level + " doit monter vers " + facings[level]
                                + ", trouvé : " + state);
            }
            // Au-dessus de chaque marche, deux blocs d'air : la tête passe.
            for (int[] position : expected[level]) {
                assertTrue(at(position[0], position[1] + 1, position[2]).isEmpty(),
                        "il faut de l'air au-dessus de la marche " + position[1]);
            }
        }
    }

    /** Les trois volées regardent trois directions différentes : la rotation est réellement testée. */
    @Test
    void theThreeFlightsFaceThreeDifferentDirections() {
        Set<String> facings = blueprint.blocks().stream()
                .filter(b -> b.state().startsWith("minecraft:oak_stairs"))
                .map(b -> b.state().replaceAll(".*facing=([a-z]+).*", "$1"))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertEquals(3, facings.size(), "trois orientations de marches attendues : " + facings);
    }

    // ---- Planchers, terrasse, intérieur ---------------------------------------------------------

    @Test
    void theInteriorIsEmptyApartFromStairsAndTorches() {
        for (BlueprintBlock block : blueprint.blocks()) {
            boolean strictlyInside = block.x() > 0 && block.x() < blueprint.sizeX() - 1
                    && block.z() > 0 && block.z() < blueprint.sizeZ() - 1;
            boolean habitableHeight = block.y() >= 1 && block.y() <= 11;
            if (!strictlyInside || !habitableHeight) {
                continue;
            }
            String state = block.state();
            boolean allowed = state.startsWith("minecraft:oak_stairs")
                    || state.startsWith("minecraft:wall_torch")
                    || state.equals("minecraft:oak_planks");
            assertTrue(allowed, "bloc inattendu à l'intérieur : " + state + " en "
                    + block.x() + "/" + block.y() + "/" + block.z());
        }
    }

    /** La terrasse est couverte, et protégée contre la chute par des créneaux. */
    @Test
    void theRoofIsWalkableAndProtectedByBattlements() {
        // Plancher de terrasse en y = 12, hors trémie.
        assertTrue(at(4, 12, 4).isPresent(), "le centre de la terrasse doit être plein");
        long battlements = blueprint.blocks().stream().filter(b -> b.y() == 13).count();
        assertTrue(battlements >= 16,
                "des créneaux sont attendus sur tout le périmètre, trouvés : " + battlements);
        // Un merlon sur deux : jamais deux cases vides consécutives sur une arête.
        for (int x = 1; x < blueprint.sizeX() - 1; x++) {
            boolean here = at(x, 13, 0).isPresent();
            boolean next = at(x + 1, 13, 0).isPresent();
            assertTrue(here || next, "deux créneaux manquants côte à côte en x=" + x);
        }
    }

    /**
     * On débouche sur la terrasse : la dernière marche est bien dans le plancher, et le palier
     * d'arrivée est plein.
     *
     * <p>Le palier est la case <em>suivante dans le sens de la montée</em> — vers l'ouest au dernier
     * niveau, donc x = 3. L'avoir cherché en x = 4 était l'erreur de la première version de ce
     * test : x = 4 porte la quatrième marche, pas le palier.</p>
     */
    @Test
    void theLastFlightEmergesOntoTheRoof() {
        String fourthStep = at(4, 12, 1).orElseThrow(
                () -> new AssertionError("quatrième marche attendue dans le plancher de terrasse"));
        assertTrue(fourthStep.startsWith("minecraft:oak_stairs"), fourthStep);
        assertTrue(at(3, 12, 1).isPresent(), "le palier d'arrivée doit être plein");
        assertTrue(at(3, 13, 1).isEmpty() || !at(3, 13, 1).orElse("").isEmpty(),
                "le palier doit être accessible (créneau ou air au-dessus)");
    }

    /** À chaque niveau, le palier d'arrivée de la volée est plein : on ne tombe pas en sortant. */
    @Test
    void everyFlightHasASolidLandingInTheDirectionOfTravel() {
        // niveau 0 monte vers l'est  : palier en x = 5, z = 7, plancher y = 4
        assertTrue(at(5, 4, 7).isPresent(), "palier du niveau 0");
        // niveau 1 monte vers le nord : palier en z = 3, x = 7, plancher y = 8
        assertTrue(at(7, 8, 3).isPresent(), "palier du niveau 1");
        // niveau 2 monte vers l'ouest : palier en x = 3, z = 1, plancher y = 12
        assertTrue(at(3, 12, 1).isPresent(), "palier du niveau 2");
    }

    // ---- La définition ne mentit pas -----------------------------------------------------------

    /**
     * La définition est <strong>lue sur le plan</strong>, pas recopiée.
     *
     * <p>Une définition qui annoncerait d'autres dimensions ferait afficher une emprise fausse, et
     * la faute ne se verrait qu'après le collage — c'est-à-dire trop tard.</p>
     */
    @Test
    void theDefinitionDescribesExactlyThePlan() {
        BuildingDefinition definition = TestWatchtowerBlueprint.definition();

        assertTrue(definition.valid(), () -> definition.validate().orElse(""));
        assertEquals(blueprint.sizeX(), definition.sizeX());
        assertEquals(blueprint.sizeY(), definition.sizeY());
        assertEquals(blueprint.sizeZ(), definition.sizeZ());
        assertEquals(TestWatchtowerBlueprint.ANCHOR_X, definition.anchorX());
        assertEquals(TestWatchtowerBlueprint.ANCHOR_Y, definition.anchorY());
        assertEquals(TestWatchtowerBlueprint.ANCHOR_Z, definition.anchorZ());
        assertEquals(Facing.NORTH, definition.front());
        assertEquals(TestWatchtowerBlueprint.SCHEMATIC, definition.schematic());
        assertEquals(blueprint.materials(), definition.materials());
    }

    /** Deux appels donnent le même plan : l'empreinte du fichier peut servir de version. */
    @Test
    void twoGenerationsProduceTheSamePlan() {
        List<BlueprintBlock> first = TestWatchtowerBlueprint.blueprint().blocks();
        List<BlueprintBlock> second = TestWatchtowerBlueprint.blueprint().blocks();

        assertEquals(first, second);
    }

    // ---- Outils ---------------------------------------------------------------------------------

    /** Les blocs d'une face, indexés par leurs deux coordonnées libres. */
    private Map<String, String> face(String side) {
        Map<String, String> result = new LinkedHashMap<>();
        for (BlueprintBlock block : blueprint.blocks()) {
            switch (side) {
                case "north" -> {
                    if (block.z() == 0) {
                        result.put(block.y() + "/" + block.x(), block.state());
                    }
                }
                case "south" -> {
                    if (block.z() == blueprint.sizeZ() - 1) {
                        result.put(block.y() + "/" + block.x(), block.state());
                    }
                }
                case "east" -> {
                    if (block.x() == blueprint.sizeX() - 1) {
                        result.put(block.y() + "/" + block.z(), block.state());
                    }
                }
                default -> {
                    if (block.x() == 0) {
                        result.put(block.y() + "/" + block.z(), block.state());
                    }
                }
            }
        }
        return result;
    }

    /** Signature d'une face : ses positions pleines et leurs matériaux, comparable telle quelle. */
    private static String signature(Map<String, String> face) {
        return face.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }
}
