package com.lodygames.rpgquest.waypoint.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.waypoint.model.WaypointNameImportPreview.Rejection;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Issue #135, préparation du 23/10/2026 : aperçu d'import sur fixtures isolées, puis sur le lot
 * candidat réel ({@code docs/waypoints/}) contre la réserve bundlée — rien n'est chargé ni écrit.
 */
class WaypointNameImportPreviewTest {

    private static final Path PROPOSAL = Path.of("docs/waypoints/waypoint-names-proposition-lot1.txt");
    private static final Path RENAME_PLAN = Path.of("docs/waypoints/plan-renommage-avant-poste.md");

    private static WaypointNameCatalog reserveOf(String... names) {
        return WaypointNameCatalog.load(new ByteArrayInputStream(
                String.join("\n", names).getBytes(StandardCharsets.UTF_8)));
    }

    private static Set<String> normalized(String... names) {
        Set<String> out = new HashSet<>();
        for (String name : names) {
            out.add(WaypointNameCatalog.normalize(name));
        }
        return out;
    }

    @Test
    void classifiesEveryRejectionReasonAndCountsFreeNames() {
        WaypointNameCatalog reserve = reserveOf("Pierre de Rive", "Col du Loup", "Gué Clair");
        Set<String> assigned = normalized("Pierre de Rive", "Col du Loup", "Allée du Vent", "Avant-poste 1");

        var result = WaypointNameImportPreview.preview(List.of(
                "# commentaire ignoré",
                "",
                "  Anse   des Hérons ",
                "anse des herons",
                "PIERRE DE RIVE",
                "Allée du Vent",
                "Avant-poste 99",
                "<red>Piège",
                "X",
                "Dune Dorée"), reserve, assigned);

        assertEquals(List.of("Anse des Hérons", "Dune Dorée"), result.accepted());
        assertEquals(1, result.rejectedCount(Rejection.DUPLICATE_IN_FILE));
        assertEquals(1, result.rejectedCount(Rejection.ALREADY_IN_RESERVE));
        assertEquals(1, result.rejectedCount(Rejection.ALREADY_ASSIGNED));
        assertEquals(1, result.rejectedCount(Rejection.FALLBACK_NAME));
        assertEquals(2, result.rejectedCount(Rejection.INVALID_FORMAT));
        assertEquals(4, result.rejected().get(0).lineNumber(), "numéro de ligne du fichier, commentaires compris");
        assertEquals(3, result.reserveBefore());
        assertEquals(1, result.freeBefore(), "seul « Gué Clair » est libre");
        assertEquals(3, result.freeAfter());
        assertFalse(result.clearsLowStock());
    }

    @Test
    void previewNeverMutatesItsInputs() {
        WaypointNameCatalog reserve = reserveOf("Gué Clair");
        Set<String> assigned = new HashSet<>(normalized("Gué Clair"));
        WaypointNameImportPreview.preview(List.of("Dune Dorée"), reserve, assigned);
        assertEquals(normalized("Gué Clair"), assigned);
        assertEquals(1, reserve.size());
    }

    /** État DEV relevé le 23/10/2026 (travel.catalog) : réserve pleine + 1 renommé + 55 secours. */
    private static Set<String> devAssignedOn20261023() {
        Set<String> assigned = new HashSet<>();
        WaypointNameCatalog.loadBundled().reserve().forEach(n -> assigned.add(WaypointNameCatalog.normalize(n)));
        assigned.add(WaypointNameCatalog.normalize("Allée du Vent"));
        for (int n = 1; n <= 55; n++) {
            assigned.add(WaypointNameCatalog.normalize("Avant-poste " + n));
        }
        return assigned;
    }

    @Test
    void candidateBatchAddsSixHundredNewNamesOnTopOfTheExhaustedReserve() throws IOException {
        List<String> lines = Files.readAllLines(PROPOSAL, StandardCharsets.UTF_8);
        var result = WaypointNameImportPreview.preview(lines, WaypointNameCatalog.loadBundled(), devAssignedOn20261023());

        assertEquals(List.of(), result.rejected(), "aucun nom rejeté dans le lot candidat");
        assertEquals(600, result.accepted().size(), "600 noms NOUVEAUX, pas un catalogue total");
        assertEquals(220, result.reserveBefore());
        assertEquals(0, result.freeBefore());
        assertEquals(600, result.freeAfter());
        assertTrue(result.clearsLowStock());
    }

    @Test
    void proposedRenamePlanUsesDistinctAcceptedNamesForEveryFallbackWaypoint() throws IOException {
        Set<String> accepted = new HashSet<>();
        WaypointNameImportPreview.preview(Files.readAllLines(PROPOSAL, StandardCharsets.UTF_8),
                        WaypointNameCatalog.loadBundled(), devAssignedOn20261023())
                .accepted().forEach(n -> accepted.add(WaypointNameCatalog.normalize(n)));

        Pattern row = Pattern.compile("^\\| (\\d+) \\| Avant-poste (\\d+) \\| `(wp_[^`]+)` \\|.*\\| ([^|]+) \\|$");
        Set<String> proposed = new HashSet<>();
        Set<String> ids = new HashSet<>();
        int rows = 0;
        for (String line : Files.readAllLines(RENAME_PLAN, StandardCharsets.UTF_8)) {
            Matcher m = row.matcher(line);
            if (!m.matches()) {
                continue;
            }
            rows++;
            String name = m.group(4).trim();
            assertEquals(m.group(1), m.group(2), "ligne N = Avant-poste N");
            assertTrue(ids.add(m.group(3)), "identifiant en double : " + m.group(3));
            assertTrue(accepted.contains(WaypointNameCatalog.normalize(name)), "hors lot accepté : " + name);
            assertTrue(proposed.add(WaypointNameCatalog.normalize(name)), "nom proposé deux fois : " + name);
        }
        assertEquals(55, rows, "les 55 « Avant-poste » relevés le 23/10/2026");
    }
}
