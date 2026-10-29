package com.lodygames.rpgquest.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.CaptureSurvey.ContainerSpot;
import com.lodygames.rpgquest.building.CaptureSurvey.ContentFate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #903 — ce que devient le contenu des blocs capturés, et comment l'aperçu le dit. */
class CaptureContentTest {

    @Test
    void booksCarriedByTheBlockStateStayVisibleAsDecor() {
        assertEquals(ContentFate.VISUAL_KEPT, CaptureContent.fate("chiseled_bookshelf", true, true, false));
        assertEquals(ContentFate.VISUAL_KEPT, CaptureContent.fate("lectern", true, true, false));
        assertEquals(ContentFate.EMPTY, CaptureContent.fate("chiseled_bookshelf", false, false, false));
        assertEquals(ContentFate.EMPTY, CaptureContent.fate("lectern", false, false, false));
    }

    @Test
    void itemsDrawnFromTheBlockDataAreLost() {
        assertEquals(ContentFate.VISUAL_LOST, CaptureContent.fate("spruce_shelf", true, false, false));
        assertEquals(ContentFate.VISUAL_LOST, CaptureContent.fate("pale_oak_shelf", true, false, false));
        assertEquals(ContentFate.VISUAL_LOST, CaptureContent.fate("soul_campfire", true, false, false));
        assertEquals(ContentFate.VISUAL_LOST, CaptureContent.fate("decorated_pot", false, false, true),
                "les motifs d'un pot sont des données du bloc");
        assertEquals(ContentFate.EMPTY, CaptureContent.fate("spruce_shelf", false, false, false),
                "une étagère vide ne perd rien");
    }

    @Test
    void hiddenContentIsDroppedWithoutVisualChange() {
        assertEquals(ContentFate.HIDDEN_DROPPED, CaptureContent.fate("chest", true, false, false));
        assertEquals(ContentFate.HIDDEN_DROPPED, CaptureContent.fate("decorated_pot", true, false, false));
        assertEquals(ContentFate.EMPTY, CaptureContent.fate("barrel", false, false, false));
    }

    @Test
    void namesAreFrenchWithARawFallback() {
        assertEquals("étagère en sapin", CaptureContent.blockName("spruce_shelf"));
        assertEquals("étagère en chêne noir", CaptureContent.blockName("dark_oak_shelf"));
        assertEquals("bibliothèque sculptée", CaptureContent.blockName("chiseled_bookshelf"));
        assertEquals("pupitre", CaptureContent.blockName("lectern"));
        assertEquals("boîte de Shulker", CaptureContent.blockName("red_shulker_box"));
        assertEquals("cadre", CaptureContent.entityName("item_frame"));
        assertEquals("tortue", CaptureContent.entityName("turtle"));
        assertEquals("sniffer", CaptureContent.entityName("sniffer"), "inconnu : identifiant brut");
    }

    /** Le relevé rapporté par le propriétaire sur La grande librairie, livres et objets supposés posés. */
    @Test
    void theLibraryPreviewSeparatesWhatStaysWhatGoesAndTheEntities() {
        List<ContainerSpot> spots = new java.util.ArrayList<>();
        for (int i = 0; i < 14; i++) {
            spots.add(new ContainerSpot(i, 0, 0, "chiseled_bookshelf", ContentFate.VISUAL_KEPT));
        }
        spots.add(new ContainerSpot(20, 0, 0, "lectern", ContentFate.VISUAL_KEPT));
        spots.add(new ContainerSpot(21, 0, 0, "lectern", ContentFate.EMPTY));
        for (int i = 0; i < 7; i++) {
            spots.add(new ContainerSpot(30 + i, 0, 0, "spruce_shelf", ContentFate.VISUAL_LOST));
        }
        CaptureSurvey survey = new CaptureSurvey(true, "", 898L, spots,
                Map.of("item_frame", 4, "turtle", 1), List.of());

        assertTrue(CaptureContent.headline(survey).startsWith("898 bloc(s) seront enregistrés à leur position"));
        List<CaptureContent.Group> groups = CaptureContent.groups(survey);
        assertEquals(List.of(ContentFate.VISUAL_LOST, ContentFate.VISUAL_KEPT, ContentFate.EMPTY),
                groups.stream().map(CaptureContent.Group::fate).toList(), "la perte d'abord");
        assertTrue(groups.get(0).text().startsWith("7 bloc(s) perdront les objets qu'ils montrent : 7 × étagère en sapin."),
                groups.get(0).text());
        assertTrue(groups.get(1).text().contains("14 × bibliothèque sculptée, 1 × pupitre"), groups.get(1).text());
        assertTrue(groups.get(1).text().contains("ne sont pas copiés"));
        assertEquals("J'ai compris : 7 bloc(s) perdront les objets qu'ils affichent (7 × étagère en sapin).",
                CaptureContent.acknowledgement(survey));
        assertTrue(CaptureContent.entityNote(survey).startsWith("4 × cadre, 1 × tortue."));
        groups.forEach(g -> assertTrue(!g.text().contains("spruce_shelf") && !g.text().contains("chiseled"),
                "aucun identifiant brut quand un nom français existe : " + g.text()));
    }
}
