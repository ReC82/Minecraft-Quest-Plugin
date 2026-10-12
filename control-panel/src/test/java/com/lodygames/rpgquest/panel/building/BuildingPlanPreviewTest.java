package com.lodygames.rpgquest.panel.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #94, lot E — la projection de l'aperçu côté panel.
 *
 * <p>Le panel ne calcule aucune géométrie : il relit ce que le plugin a mesuré. Ces tests portent
 * donc sur la fidélité de la relecture, et surtout sur ce qui se passe quand le détail est
 * incomplet.</p>
 */
class BuildingPlanPreviewTest {

    private static Map<String, Object> slice(String id, String label, List<String> rows) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("id", id);
        one.put("label", label);
        one.put("axes", "x → droite");
        one.put("rows", rows);
        return one;
    }

    private static Map<String, Object> details() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("code", "ARCHITECTURE_REFUSED");
        d.put("message", "Plan refusé.");
        d.put("buildingId", "hub_library");
        d.put("kind", "bâtiment habitable");
        d.put("sizeX", 19);
        d.put("sizeY", 12);
        d.put("sizeZ", 15);
        d.put("blocks", 2368);
        d.put("anchorX", 9);
        d.put("anchorY", 0);
        d.put("anchorZ", 7);
        d.put("front", "WEST");
        d.put("codes", List.of("NO_ENTRANCE", "GLAZING_MISSING"));
        d.put("warnings", List.of("Matériau dominant."));
        d.put("plans", List.of(slice("plan-y0", "Niveau y = 0", List.of("###", "###", "###"))));
        d.put("elevations", List.of(
                slice("elevation-front", "Façade avant — entrée", List.of("###", "###", "###")),
                slice("elevation-back", "Façade arrière", List.of("###", "#o#", "###"))));
        return d;
    }

    @Test
    @DisplayName("L'aperçu est projeté entièrement : coupes, dimensions, ancre, orientation")
    void thepreviewIsProjectedInFull() {
        BuildingPlanPreview preview = BuildingPlanPreview.from(details());

        assertTrue(preview.available());
        assertFalse(preview.accepted());
        assertTrue(preview.hasSlices());
        assertEquals(1, preview.plans().size());
        assertEquals(2, preview.elevations().size());
        assertEquals("WEST", preview.front());
        assertEquals(2368, preview.blocks());
        assertEquals(List.of("NO_ENTRANCE", "GLAZING_MISSING"), preview.codes());
        assertEquals("###\n#o#\n###", preview.elevations().get(1).text());
        assertEquals(3, preview.elevations().get(1).width());
    }

    /**
     * L'aperçu doit remonter <strong>même pour un plan refusé</strong> : c'est le cas où il sert le
     * plus, puisqu'il montre pourquoi.
     */
    @Test
    @DisplayName("Un plan refusé a quand même ses coupes")
    void arefusedPlanStillHasItsSlices() {
        BuildingPlanPreview preview = BuildingPlanPreview.from(details());

        assertFalse(preview.accepted());
        assertTrue(preview.hasSlices(), "sinon on ne verrait jamais pourquoi un plan est refusé");
    }

    @Test
    @DisplayName("Une ligne vide d'une coupe est conservée : la supprimer décalerait le dessin")
    void anemptyRowOfASliceIsKept() {
        Map<String, Object> d = new LinkedHashMap<>(details());
        d.put("plans", List.of(slice("plan-y0", "Niveau y = 0",
                Arrays.asList("###", "", null, "###"))));

        BuildingPlanPreview preview = BuildingPlanPreview.from(d);

        assertEquals(4, preview.plans().get(0).rows().size(),
                "quatre lignes, dont deux vides : " + preview.plans().get(0).rows());
        assertEquals("###\n\n\n###", preview.plans().get(0).text());
    }

    /** Les libellés, eux, se nettoient : un code vide serait un défaut fantôme à l'écran. */
    @Test
    @DisplayName("Un code vide ne devient pas un défaut fantôme")
    void anemptyCodeDoesNotBecomeAPhantomDefect() {
        Map<String, Object> d = new LinkedHashMap<>(details());
        d.put("codes", Arrays.asList("NO_ENTRANCE", "", null, "   "));

        assertEquals(List.of("NO_ENTRANCE"), BuildingPlanPreview.from(d).codes());
    }

    @Test
    @DisplayName("Aucun aperçu demandé ne se lit pas comme un bâtiment sans défaut")
    void nnoPreviewRequestedDoesNotReadAsAFlawlessBuilding() {
        for (BuildingPlanPreview none : List.of(BuildingPlanPreview.none(),
                BuildingPlanPreview.from(null), BuildingPlanPreview.from(Map.of()))) {
            assertFalse(none.available());
            assertFalse(none.accepted());
            assertFalse(none.hasSlices());
        }
    }

    @Test
    @DisplayName("Un détail sans coupes est signalé comme tel, sans grille inventée")
    void adetailWithoutSlicesIsReportedAsSuch() {
        BuildingPlanPreview preview = BuildingPlanPreview.from(Map.of("code", "PLAN_INVALID"));

        assertTrue(preview.available(), "le serveur a répondu");
        assertFalse(preview.hasSlices(), "un plan illisible n'a aucune coupe");
        assertEquals(0, preview.sizeX());
    }

    /**
     * L'ancre est la question que pose forcément quiconque pose un bâtiment. Le libellé doit dire
     * laquelle des deux conventions s'applique, plutôt que de lâcher trois nombres.
     */
    @Test
    @DisplayName("Le libellé de l'ancre distingue le centre par défaut d'une ancre explicite")
    void theanchorLabelDistinguishesTheDefaultCentreFromAnExplicitAnchor() {
        Map<String, Object> centred = new LinkedHashMap<>(details());
        centred.put("sizeX", 19);
        centred.put("sizeZ", 15);
        centred.put("anchorX", 9);
        centred.put("anchorY", 0);
        centred.put("anchorZ", 7);
        assertTrue(BuildingPlanPreview.from(centred).anchorLabel().contains("centre de la base"),
                BuildingPlanPreview.from(centred).anchorLabel());

        Map<String, Object> atDoor = new LinkedHashMap<>(centred);
        atDoor.put("anchorX", 0);
        atDoor.put("anchorZ", 7);
        String label = BuildingPlanPreview.from(atDoor).anchorLabel();
        assertTrue(label.contains("explicite"), label);
        assertTrue(label.contains("x = 0"), label);
    }

    /** #269 : pour une largeur paire, l'ancre par défaut du plugin est sizeX / 2, pas (sizeX - 1) / 2. */
    @Test
    @DisplayName("Largeur paire : l'ancre par défaut du plugin est reconnue comme telle")
    void anEvenWidthDefaultAnchorIsNotMistakenForAnExplicitOne() {
        Map<String, Object> even = new LinkedHashMap<>(details());
        even.put("sizeX", 20);
        even.put("sizeZ", 16);
        even.put("anchorX", 10);
        even.put("anchorY", 0);
        even.put("anchorZ", 8);
        String label = BuildingPlanPreview.from(even).anchorLabel();
        assertTrue(label.contains("centre de la base"), label);
    }

    @Test
    @DisplayName("La légende couvre tous les caractères que le plugin peut produire")
    void thelegendCoversEveryCharacterThePluginCanProduce() {
        List<String> symbols = new ArrayList<>();
        for (var entry : BuildingPlanPreview.LEGEND) {
            symbols.add(entry.getKey());
        }
        // Les huit rôles de PlanPreview. Un caractère rendu sans légende serait indéchiffrable, et
        // c'est exactement le genre d'oubli qu'aucun test d'affichage ne rattraperait.
        assertEquals(List.of("#", "o", "+", "/", "H", "*", "f", "."), symbols);
    }

    @Test
    @DisplayName("Un entier arrivé en texte est lu comme un entier")
    void anintegerArrivingAsTextIsReadAsAnInteger() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("code", "OK");
        d.put("blocks", "2368");
        d.put("sizeY", "12");

        BuildingPlanPreview preview = BuildingPlanPreview.from(d);

        assertEquals(2368, preview.blocks());
        assertEquals(12, preview.sizeY());
    }
}
