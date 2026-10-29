package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.building.BuildingProject;
import com.lodygames.rpgquest.panel.building.BuildingSiteDirectory;
import com.lodygames.rpgquest.panel.building.CatalogState;
import com.lodygames.rpgquest.panel.building.ProjectUsage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #881 (retour du 29/10) — nom du projet, nom de l'emplacement d'origine et bâtiment réellement
 * posé ne sont jamais confondus : « Bibliothèque du Hub » vient de {@code buildsite_0009}, qui porte
 * {@code la_grande_librairie} v1.
 */
class WorkshopTrashPagesTest {

    private static BuildingProject project(String sourceSite) {
        return new BuildingProject("p1", "agent", "Bibliothèque du Hub", "brief", "bibliotheque_du_hub", "WEST",
                9, 9, 6, sourceSite, 1, false, "owner", Instant.EPOCH, "owner", Instant.EPOCH);
    }

    private static BuildingSiteDirectory survey(boolean occupied) {
        return BuildingSiteDirectory.from(Map.of(
                "sites", List.of(Map.of("id", "buildsite_0009", "name", "Bibliothèque du Hub", "world", "world_hub")),
                "placements", occupied ? List.of(Map.of("siteId", "buildsite_0009",
                        "buildingId", "la_grande_librairie", "buildingVersion", 1)) : List.of()));
    }

    @Test
    void theOriginSiteNamesWhatItReallyCarries() {
        var origin = WorkshopTrashPages.Origin.of(project("buildsite_0009"), survey(true));
        assertEquals("Bibliothèque du Hub", origin.siteName());
        assertEquals("la_grande_librairie", origin.occupiedBy());
        var usage = ProjectUsage.of(true, List.of(), List.of(), CatalogState.unknown(), "");
        String html = WorkshopTrashPages.usageLines(usage, origin);
        assertTrue(html.contains("Importé de l&#39;emplacement") || html.contains("Importé de l'emplacement"), html);
        assertTrue(html.contains("buildsite_0009") && html.contains("la_grande_librairie") && html.contains(" v1"), html);
        assertTrue(html.contains("Non utilisé"), "le projet lui-même n'est pas posé : " + html);
    }

    @Test
    void aFreeUnknownOrMissingOriginIsSaidSo() {
        assertTrue(WorkshopTrashPages.usageLines(ProjectUsage.of(true, List.of(), List.of(), CatalogState.unknown(), ""),
                WorkshopTrashPages.Origin.of(project("buildsite_0009"), survey(false))).contains("libre au dernier relevé"));
        assertTrue(WorkshopTrashPages.usageLines(ProjectUsage.of(false, List.of(), List.of(), CatalogState.unknown(), ""),
                WorkshopTrashPages.Origin.of(project("buildsite_0009"), BuildingSiteDirectory.unavailable()))
                .contains("contenu actuel inconnu"));
        var none = WorkshopTrashPages.Origin.of(project(""), survey(true));
        assertFalse(none.present());
        assertFalse(WorkshopTrashPages.usageLines(ProjectUsage.of(true, List.of(), List.of(), CatalogState.unknown(), ""),
                none).contains("Importé"));
    }
}
