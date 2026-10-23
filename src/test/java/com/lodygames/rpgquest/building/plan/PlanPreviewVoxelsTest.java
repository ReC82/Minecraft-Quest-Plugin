package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BlueprintBlock;
import com.lodygames.rpgquest.building.model.Facing;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #273 — la grille de la vue en 3D est-elle exactement celle que le serveur construira ?
 *
 * <p>La vue du panel ne vaut que si chaque cellule qu'elle dessine est un bloc de la grille expansée,
 * et réciproquement. Ces tests le vérifient bloc par bloc, et vérifient qu'au-delà des bornes la
 * grille est <em>absente avec son motif</em>, jamais tronquée.</p>
 */
class PlanPreviewVoxelsTest {

    private static final String HOUSE = """
            id: maisonnette
            name: "Maisonnette"
            kind: habitable
            front: NORTH
            size:
              x: 5
              y: 5
              z: 5
            steps:
              - op: box
                from: [0, 0, 0]
                to: [4, 0, 4]
                block: minecraft:stone_bricks
              - op: walls
                from: [0, 1, 0]
                to: [4, 3, 4]
                block: minecraft:oak_planks
              - op: box
                from: [0, 4, 0]
                to: [4, 4, 4]
                block: minecraft:oak_planks
              - op: door
                facade: FRONT
                offset: 2
                block: minecraft:oak_door
              - op: window
                facade: LEFT
                offset: 2
                y: 2
                width: 1
                height: 1
                block: minecraft:glass_pane
              - op: light
                from: [2, 3, 2]
                to: [2, 3, 2]
                block: minecraft:lantern
            """;

    private static Blueprint expanded(String yaml) {
        List<String> problems = new ArrayList<>();
        BuildingPlan plan = BuildingPlanYaml.read(yaml, problems)
                .orElseThrow(() -> new AssertionError("plan refusé : " + problems));
        BuildingPlanExpander.Result expansion = BuildingPlanExpander.expand(plan);
        assertTrue(expansion.ok(), expansion.error());
        return expansion.blueprint();
    }

    private static Map<Character, PlanPreview.PaletteEntry> bySymbol(PlanPreview.Voxels voxels) {
        Map<Character, PlanPreview.PaletteEntry> out = new HashMap<>();
        voxels.palette().forEach(e -> out.put(e.symbol(), e));
        return out;
    }

    @Test
    @DisplayName("Chaque bloc de la grille expansée est dans la vue, à sa place, et rien d'autre")
    void theVoxelsAreExactlyTheExpandedGrid() {
        Blueprint blueprint = expanded(HOUSE);
        PlanPreview.Voxels voxels = PlanPreview.voxels(blueprint);
        assertTrue(voxels.present(), voxels.omittedReason());

        assertEquals(blueprint.sizeY(), voxels.layers().size(), "une couche par niveau");
        for (List<String> layer : voxels.layers()) {
            assertEquals(blueprint.sizeZ(), layer.size(), "une rangée par z");
            layer.forEach(row -> assertEquals(blueprint.sizeX(), row.length(), "un caractère par x"));
        }
        Map<Character, PlanPreview.PaletteEntry> palette = bySymbol(voxels);
        for (BlueprintBlock block : blueprint.blocks()) {
            char symbol = voxels.layers().get(block.y()).get(block.z()).charAt(block.x());
            assertTrue(palette.containsKey(symbol), "bloc absent de la vue : " + block);
            assertEquals(block.material(), palette.get(symbol).block(), block.toString());
        }
        long drawn = voxels.layers().stream().flatMap(List::stream)
                .mapToLong(row -> row.chars().filter(c -> c != PlanPreview.EMPTY).count()).sum();
        assertEquals(blueprint.blocks().size(), drawn, "aucune cellule inventée");
    }

    @Test
    @DisplayName("La palette porte le rôle de chaque bloc, dans le vocabulaire des coupes")
    void thePaletteCarriesTheRoleOfEachBlock() {
        Map<String, Character> roles = new HashMap<>();
        PlanPreview.voxels(expanded(HOUSE)).palette().forEach(e -> roles.put(e.block(), e.role()));

        assertEquals(PlanPreview.SOLID, roles.get("stone_bricks"));
        assertEquals(PlanPreview.GLAZING, roles.get("glass_pane"));
        assertEquals(PlanPreview.LIGHT, roles.get("lantern"));
        assertEquals(PlanPreview.PASSABLE, roles.get("oak_door"), "la porte se distingue du mur");
    }

    @Test
    @DisplayName("Une dalle est un demi-bloc, en bas ou en haut ; une double dalle, un cube")
    void slabsKeepTheirHalf() {
        Blueprint blueprint = new Blueprint(3, 1, 1, List.of(
                new BlueprintBlock(0, 0, 0, "minecraft:oak_slab[type=bottom]"),
                new BlueprintBlock(1, 0, 0, "minecraft:oak_slab[type=top]"),
                new BlueprintBlock(2, 0, 0, "minecraft:oak_slab[type=double]")));
        List<String> shapes = PlanPreview.voxels(blueprint).palette().stream()
                .map(PlanPreview.PaletteEntry::shape).toList();
        assertEquals(List.of("bottom", "top", "full"), shapes);
    }

    @Test
    @DisplayName("Trop grand : la grille est absente avec son motif, jamais tronquée")
    void aTooLargeBuildingIsOmittedWithItsReason() {
        PlanPreview.Voxels voxels = PlanPreview.voxels(new Blueprint(64, 64, 64,
                List.of(new BlueprintBlock(0, 0, 0, "minecraft:stone"))));
        assertFalse(voxels.present());
        assertTrue(voxels.omittedReason().contains("trop grand"), voxels.omittedReason());
    }

    @Test
    @DisplayName("Trop de types de blocs : absente avec son motif")
    void tooManyBlockTypesAreOmittedWithTheReason() {
        List<BlueprintBlock> blocks = new ArrayList<>();
        int types = PlanPreview.SYMBOLS.length() + 1;
        for (int x = 0; x < types; x++) {
            blocks.add(new BlueprintBlock(x, 0, 0, "minecraft:block_" + x));
        }
        PlanPreview.Voxels voxels = PlanPreview.voxels(new Blueprint(types, 1, 1, blocks));
        assertFalse(voxels.present());
        assertTrue(voxels.omittedReason().contains("types de blocs"), voxels.omittedReason());
    }

    @Test
    @DisplayName("Un bloc hors du volume : pas de vue, plutôt qu'un bâtiment amputé")
    void aBlockOutsideTheVolumeOmitsTheView() {
        PlanPreview.Voxels voxels = PlanPreview.voxels(new Blueprint(2, 2, 2,
                List.of(new BlueprintBlock(5, 0, 0, "minecraft:stone"))));
        assertFalse(voxels.present());
        assertTrue(voxels.omittedReason().contains("sortent du volume"), voxels.omittedReason());
    }

    @Test
    @DisplayName("L'aperçu complet transporte la grille")
    void thePreviewCarriesTheVoxels() {
        Blueprint blueprint = expanded(HOUSE);
        PlanPreview.Preview preview = PlanPreview.of(blueprint, Facing.NORTH, 2, 1, 0);
        assertTrue(preview.voxels().present());
        assertEquals(5, preview.voxels().layers().size());
    }

    @Test
    @DisplayName("#786 — la palette porte les états qui changent le dessin, pas les autres")
    void thePaletteCarriesTheDrawnStates() {
        Blueprint blueprint = new Blueprint(4, 1, 1, List.of(
                new BlueprintBlock(0, 0, 0, "minecraft:oak_stairs[facing=north,half=top,shape=inner_left,waterlogged=false]"),
                new BlueprintBlock(1, 0, 0, "minecraft:oak_stairs[facing=south,half=bottom,shape=straight]"),
                new BlueprintBlock(2, 0, 0, "minecraft:oak_fence[north=true,east=false,south=true,west=false]"),
                new BlueprintBlock(3, 0, 0, "minecraft:iron_chain[axis=x]")));
        PlanPreview.Voxels voxels = PlanPreview.voxels(blueprint);
        assertTrue(voxels.present(), voxels.omittedReason());
        List<String> states = voxels.palette().stream().map(PlanPreview.PaletteEntry::state).toList();
        assertEquals(List.of("facing=north,half=top,shape=inner_left", "facing=south,half=bottom,shape=straight",
                "", "axis=x"), states, "triées, sans waterlogged ni connexions");
        assertEquals(4, voxels.palette().size(), "deux marches tournées différemment : deux entrées");
    }

    @Test
    @DisplayName("#786 — trop de variantes d'état : la vue reste, sans les états")
    void tooManyStateVariantsFallBackToOneEntryPerMaterial() {
        List<BlueprintBlock> blocks = new ArrayList<>();
        String[] facings = {"north", "south", "east", "west"};
        String[] shapes = {"straight", "inner_left", "inner_right", "outer_left", "outer_right"};
        int x = 0;
        for (String material : List.of("oak", "spruce", "birch", "jungle", "acacia")) {
            for (String facing : facings) {
                for (String shape : shapes) {
                    blocks.add(new BlueprintBlock(x++, 0, 0, "minecraft:" + material + "_stairs[facing=" + facing
                            + ",half=bottom,shape=" + shape + "]"));
                }
            }
        }
        PlanPreview.Voxels voxels = PlanPreview.voxels(new Blueprint(x, 1, 1, blocks));
        assertTrue(voxels.present(), voxels.omittedReason());
        assertEquals(5, voxels.palette().size(), "100 variantes > " + PlanPreview.SYMBOLS.length() + " symboles");
        assertTrue(voxels.palette().stream().allMatch(e -> e.state().isEmpty()));
    }

    @Test
    @DisplayName("#786 — aucun symbole n'exige d'échappement JSON ni ne se confond avec l'air")
    void symbolsAreSafe() {
        String symbols = PlanPreview.SYMBOLS;
        assertFalse(symbols.contains("\"") || symbols.contains("\\") || symbols.indexOf(PlanPreview.EMPTY) >= 0);
        assertEquals(symbols.length(), symbols.chars().distinct().count(), "aucun doublon");
    }
}
