package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.Facing;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #94, lot E — les coupes 2D disent-elles la vérité sur la grille ?
 *
 * <p>Tout l'intérêt de cet aperçu est qu'il soit pris sur la <strong>grille expansée</strong> et pas
 * sur l'intention du plan. Ces tests le vérifient sur le cas qui compte : un bâtiment dont la porte
 * est déclarée mais mal placée doit apparaître <em>fermé</em>.</p>
 */
class PlanPreviewTest {

    private static PlanPreview.Preview previewOf(String yaml) {
        List<String> problems = new ArrayList<>();
        BuildingPlan plan = BuildingPlanYaml.read(yaml, problems)
                .orElseThrow(() -> new AssertionError("plan refusé : " + problems));
        BuildingPlanExpander.Result expansion = BuildingPlanExpander.expand(plan);
        assertTrue(expansion.ok(), expansion.error());
        return PlanPreview.of(expansion.blueprint(), plan.front(),
                plan.anchorX(), plan.anchorY(), plan.anchorZ());
    }

    /** Une petite maison correcte : porte avant, fenêtre à gauche, lanterne. */
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

    private static PlanPreview.Slice slice(List<PlanPreview.Slice> all, String id) {
        return all.stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("coupe absente : " + id + " parmi "
                        + all.stream().map(PlanPreview.Slice::id).toList()));
    }

    @Test
    @DisplayName("Le plan du rez-de-chaussée montre le sol plein, sur toute l'emprise")
    void thegroundPlanShowsTheFullFloor() {
        PlanPreview.Preview preview = previewOf(HOUSE);

        PlanPreview.Slice ground = slice(preview.plans(), "plan-y0");

        assertEquals(5, ground.width());
        assertEquals(5, ground.height());
        for (String row : ground.rows()) {
            assertEquals("#####", row, "le sol est plein : " + ground.rows());
        }
    }

    /**
     * Le test qui justifie tout le lot : la porte est visible <strong>sur la façade où elle est</strong>.
     */
    @Test
    @DisplayName("La porte apparaît sur la façade avant, et nulle part ailleurs")
    void thedoorAppearsOnTheFrontFacadeAndNowhereElse() {
        PlanPreview.Preview preview = previewOf(HOUSE);

        PlanPreview.Slice front = slice(preview.elevations(), "elevation-front");
        String text = front.text();
        assertTrue(text.indexOf(PlanPreview.PASSABLE) >= 0,
                "la porte doit se voir sur la façade avant :\n" + text);

        PlanPreview.Slice back = slice(preview.elevations(), "elevation-back");
        assertFalse(back.text().indexOf(PlanPreview.PASSABLE) >= 0,
                "aucune porte à l'arrière :\n" + back.text());
    }

    @Test
    @DisplayName("La fenêtre apparaît sur la façade gauche, en vitrage")
    void thewindowAppearsOnTheLeftFacadeAsGlazing() {
        PlanPreview.Preview preview = previewOf(HOUSE);

        assertTrue(slice(preview.elevations(), "elevation-left").text()
                .indexOf(PlanPreview.GLAZING) >= 0, "vitrage attendu à gauche");
        assertFalse(slice(preview.elevations(), "elevation-right").text()
                .indexOf(PlanPreview.GLAZING) >= 0, "aucun vitrage à droite");
    }

    @Test
    @DisplayName("La lanterne apparaît en source de lumière sur le plan de son niveau")
    void thelanternAppearsAsALightSourceOnItsLevelPlan() {
        PlanPreview.Preview preview = previewOf(HOUSE);

        assertTrue(slice(preview.plans(), "plan-y3").text().indexOf(PlanPreview.LIGHT) >= 0,
                "la lanterne est en y = 3");
    }

    /**
     * Le cas de la V1, en miniature : la porte est « déclarée » par un {@code clear} qui tombe au
     * mauvais endroit. L'aperçu doit montrer un bâtiment <strong>fermé</strong> — c'est précisément
     * ce qu'un aperçu dessiné depuis l'intention du plan n'aurait pas montré.
     */
    @Test
    @DisplayName("Un percement qui manque le mur donne un aperçu FERMÉ, pas une porte imaginaire")
    void apiercingThatMissesTheWallYieldsAClosedPreview() {
        String sealed = """
                id: scellee
                name: "Scellée"
                kind: decoration
                front: WEST
                size:
                  x: 5
                  y: 4
                  z: 5
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [4, 0, 4]
                    block: minecraft:stone_bricks
                  - op: walls
                    from: [0, 1, 0]
                    to: [4, 3, 4]
                    block: minecraft:stone_bricks
                  - op: clear
                    from: [2, 1, 2]
                    to: [2, 2, 2]
                """;

        PlanPreview.Preview preview = previewOf(sealed);

        // La façade avant est le plan x = 0 ; le « clear » visait x = 2, à l'intérieur.
        PlanPreview.Slice front = slice(preview.elevations(), "elevation-front");
        for (String row : front.rows()) {
            assertFalse(row.indexOf(PlanPreview.PASSABLE) >= 0,
                    "aucune ouverture ne doit apparaître :\n" + front.text());
        }
        assertTrue(front.text().indexOf(PlanPreview.SOLID) >= 0, "le mur, lui, se voit");
    }

    @Test
    @DisplayName("Les quatre façades sont rendues, et l'avant est désigné comme l'entrée")
    void thefourFacadesAreRenderedAndTheFrontIsNamedAsTheEntrance() {
        PlanPreview.Preview preview = previewOf(HOUSE);

        assertEquals(4, preview.elevations().size());
        assertTrue(slice(preview.elevations(), "elevation-front").label().contains("entrée"),
                slice(preview.elevations(), "elevation-front").label());
        // La façade avant d'un bâtiment orienté NORTH regarde bien le nord.
        assertTrue(slice(preview.elevations(), "elevation-front").label().contains("NORTH"),
                slice(preview.elevations(), "elevation-front").label());
    }

    /**
     * Une élévation projette ce que verrait un visiteur : le premier rôle rencontré en avançant.
     * Sans cela, une fenêtre serait masquée par le mur du fond et les quatre façades se
     * ressembleraient.
     */
    @Test
    @DisplayName("Une élévation montre le premier élément rencontré, pas le mur du fond")
    void anelevationShowsTheFirstElementMetNotTheBackWall() {
        PlanPreview.Preview preview = previewOf(HOUSE);
        PlanPreview.Slice left = slice(preview.elevations(), "elevation-left");

        long glazed = left.rows().stream()
                .mapToLong(r -> r.chars().filter(c -> c == PlanPreview.GLAZING).count()).sum();
        assertEquals(1, glazed,
                "exactement la cellule vitrée de cette façade, pas celle d'en face :\n"
                        + left.text());
    }

    @Test
    @DisplayName("Un niveau entièrement vide est omis, mais jamais le rez-de-chaussée")
    void anemptyLevelIsOmittedButNeverTheGroundFloor() {
        String tall = """
                id: socle
                name: "Socle"
                kind: decoration
                front: NORTH
                size:
                  x: 3
                  y: 6
                  z: 3
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [2, 0, 2]
                    block: minecraft:stone_bricks
                """;

        PlanPreview.Preview preview = previewOf(tall);

        assertEquals(1, preview.plans().size(),
                "cinq niveaux vides omis : " + preview.plans().stream()
                        .map(PlanPreview.Slice::id).toList());
        assertEquals("plan-y0", preview.plans().get(0).id());
    }

    @Test
    @DisplayName("L'ancre et les dimensions remontent telles que le plan les déclare")
    void theanchorAndDimensionsAreReportedAsDeclared() {
        PlanPreview.Preview preview = previewOf(HOUSE);

        assertEquals(5, preview.sizeX());
        assertEquals(5, preview.sizeY());
        assertEquals(5, preview.sizeZ());
        assertEquals("NORTH", preview.front());
        // Ancre non déclarée : le centre de la base, convention du projet.
        assertEquals(2, preview.anchorX());
        assertEquals(0, preview.anchorY());
        assertEquals(2, preview.anchorZ());
    }

    @Test
    @DisplayName("Toutes les lignes d'une coupe ont la même longueur : un dessin décalé mentirait")
    void allRowsOfASliceShareTheSameLength() {
        PlanPreview.Preview preview = previewOf(HOUSE);

        List<PlanPreview.Slice> all = new ArrayList<>(preview.plans());
        all.addAll(preview.elevations());
        for (PlanPreview.Slice slice : all) {
            for (String row : slice.rows()) {
                assertEquals(slice.width(), row.length(), "coupe " + slice.id());
            }
        }
    }

    @Test
    @DisplayName("Un escalier se distingue du mur, pour qu'un étage atteignable se constate")
    void astaircaseIsDistinguishableFromAWall() {
        String withStairs = """
                id: deux_niveaux
                name: "Deux niveaux"
                kind: decoration
                front: NORTH
                size:
                  x: 7
                  y: 7
                  z: 7
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [6, 0, 6]
                    block: minecraft:stone_bricks
                  - op: stair_run
                    from: [1, 1, 1]
                    steps: 3
                    direction: east
                    block: minecraft:oak_stairs
                """;

        PlanPreview.Preview preview = previewOf(withStairs);

        boolean seen = preview.plans().stream()
                .anyMatch(s -> s.text().indexOf(PlanPreview.STEP) >= 0);
        assertTrue(seen, "les marches doivent se distinguer : " + preview.plans().stream()
                .map(PlanPreview.Slice::text).toList());
    }

    @Test
    @DisplayName("Une grille sans aucun bloc n'invente rien")
    void anemptyGridInventsNothing() {
        PlanPreview.Preview preview = PlanPreview.of(
                new com.lodygames.rpgquest.building.model.Blueprint(3, 3, 3, List.of()),
                Facing.NORTH, 1, 0, 1);

        assertEquals(1, preview.plans().size(), "seul le rez-de-chaussée est conservé");
        assertFalse(preview.plans().get(0).hasContent());
        assertEquals(4, preview.elevations().size());
        for (PlanPreview.Slice elevation : preview.elevations()) {
            assertFalse(elevation.hasContent(), elevation.id());
        }
    }
}
