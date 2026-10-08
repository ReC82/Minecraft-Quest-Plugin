package com.lodygames.rpgquest.panel.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #213 — la projection du relevé {@code building.site.list}, côté panel.
 *
 * <p>Les cas construits ici reprennent la forme exacte du payload de l'agent, y compris ses petites
 * laideurs de transport (la chaîne {@code "null"}, un entier arrivé en texte) : c'est à ça que sert
 * une projection, et c'est là qu'elle doit être vérifiée.</p>
 */
class BuildingSiteDirectoryTest {

    private static Map<String, Object> site(String id, String world, int x, int y, int z,
                                            String facing) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", "Taverne du village");
        m.put("description", "");
        m.put("world", world);
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        m.put("facing", facing);
        m.put("status", "EMPTY");
        m.put("createdBy", "Lody");
        m.put("createdAt", "2026-10-08T18:00:00Z");
        m.put("worldLoaded", true);
        return m;
    }

    private static BuildingSiteDirectory directory(List<Object> sites, List<String> worlds) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sites", sites);
        details.put("worlds", worlds);
        details.put("total", sites.size());
        return BuildingSiteDirectory.from(details);
    }

    // ---- Projection ----------------------------------------------------------------------------

    @Test
    void theRealPayloadProjectsEveryField() {
        BuildingSiteDirectory dir = directory(
                List.of(site("buildsite_0001", "world_hub", 712, 67, -702, "WEST")),
                List.of("world_hub"));

        assertTrue(dir.available());
        assertEquals(1, dir.total());
        BuildingSiteView view = dir.find("buildsite_0001").orElseThrow();
        assertEquals("Taverne du village", view.name());
        assertEquals("world_hub", view.world());
        assertEquals(712, view.x());
        assertEquals(67, view.y());
        assertEquals(-702, view.z());
        assertEquals("WEST", view.facing());
        assertEquals("712 / 67 / -702", view.positionLabel());
        assertEquals("ouest", view.facingLabel());
        assertEquals("vide", view.statusLabel());
        assertTrue(view.hasAuthor());
        assertEquals("Lody", view.createdBy());
        assertTrue(view.worldLoaded());
    }

    @Test
    void theFourFacingsHaveAFrenchLabel() {
        assertEquals("nord", view("NORTH").facingLabel());
        assertEquals("est", view("EAST").facingLabel());
        assertEquals("sud", view("SOUTH").facingLabel());
        assertEquals("ouest", view("WEST").facingLabel());
        assertEquals(List.of("NORTH", "EAST", "SOUTH", "WEST"), BuildingSiteView.FACINGS);
    }

    /** Un état écrit par une version plus récente s'affiche, il ne casse rien. */
    @Test
    void anUnknownFacingOrStatusIsShownRatherThanHidden() {
        BuildingSiteView odd = new BuildingSiteView("buildsite_0001", "x", "", "w", 0, 0, 0,
                "UPSIDE_DOWN", "BUILT_IN_A_FUTURE_VERSION", "", "", true);

        assertEquals("upside_down", odd.facingLabel());
        assertEquals("built_in_a_future_version", odd.statusLabel());
    }

    @Test
    void theFrenchDateIsFormattedAndABadDateIsShownRaw() {
        assertEquals("08/10/2026 18:00 UTC",
                view("WEST").createdAtLabel());
        BuildingSiteView broken = new BuildingSiteView("buildsite_0001", "x", "", "w", 0, 0, 0,
                "NORTH", "EMPTY", "", "pas une date", true);
        assertEquals("pas une date", broken.createdAtLabel());
        BuildingSiteView missing = new BuildingSiteView("buildsite_0001", "x", "", "w", 0, 0, 0,
                "NORTH", "EMPTY", "", "", true);
        assertEquals("inconnue", missing.createdAtLabel());
    }

    /** L'auteur peut légitimement être inconnu, et on ne l'invente jamais. */
    @Test
    void anUnknownAuthorIsReportedAsSuch() {
        Map<String, Object> m = site("buildsite_0001", "world_hub", 0, 64, 0, "NORTH");
        m.put("createdBy", "null");

        BuildingSiteView view = directory(List.of(m), List.of("world_hub"))
                .find("buildsite_0001").orElseThrow();

        assertFalse(view.hasAuthor());
        assertEquals("", view.createdBy());
    }

    @Test
    void anIntegerArrivingAsTextIsStillRead() {
        Map<String, Object> m = site("buildsite_0001", "world_hub", 0, 0, 0, "NORTH");
        m.put("x", "712");
        m.put("y", "67");
        m.put("z", "-702");

        BuildingSiteView view = directory(List.of(m), List.of("world_hub"))
                .find("buildsite_0001").orElseThrow();

        assertEquals(712, view.x());
        assertEquals(67, view.y());
        assertEquals(-702, view.z());
    }

    @Test
    void aRowWithoutAnIdIsSkippedRatherThanShownEmpty() {
        Map<String, Object> broken = site("", "world_hub", 0, 64, 0, "NORTH");

        BuildingSiteDirectory dir = directory(
                List.of(broken, site("buildsite_0002", "world_hub", 1, 64, 0, "NORTH")),
                List.of("world_hub"));

        assertEquals(1, dir.total());
        assertEquals("buildsite_0002", dir.sites().get(0).id());
    }

    // ---- Filtres et recherche ------------------------------------------------------------------

    @Test
    void filteringByWorldKeepsOnlyThatWorld() {
        BuildingSiteDirectory dir = directory(List.of(
                site("buildsite_0001", "world_hub", 0, 64, 0, "NORTH"),
                site("buildsite_0002", "claims", 0, 64, 0, "NORTH"),
                site("buildsite_0003", "world_hub", 1, 64, 0, "NORTH")),
                List.of("claims", "world_hub"));

        assertEquals(2, dir.inWorld("world_hub").size());
        assertEquals(1, dir.inWorld("claims").size());
        assertEquals(3, dir.inWorld("").size(), "pas de filtre = tout");
        assertEquals(3, dir.inWorld(null).size());
        assertEquals(0, dir.inWorld("inconnu").size());
    }

    /** Les mondes viennent du serveur, pas d'un recalcul local : lui seul sait ce qu'il porte. */
    @Test
    void theWorldListComesFromTheServerAndIsSorted() {
        BuildingSiteDirectory dir = directory(
                List.of(site("buildsite_0001", "world_hub", 0, 64, 0, "NORTH")),
                List.of("world_hub", "claims"));

        assertEquals(List.of("claims", "world_hub"), dir.worlds());
    }

    @Test
    void theSearchTextCoversIdNameWorldAndPosition() {
        String text = view("WEST").searchText();

        assertTrue(text.contains("buildsite_0001"));
        assertTrue(text.contains("taverne"));
        assertTrue(text.contains("world_hub"));
        assertTrue(text.contains("712"));
        assertTrue(text.contains("-702"));
        assertTrue(text.contains("ouest"));
    }

    // ---- Monde déchargé ------------------------------------------------------------------------

    /** Un monde déchargé n'invalide pas l'emplacement : c'est une information, pas une anomalie. */
    @Test
    void sitesInUnloadedWorldsAreListedSeparately() {
        Map<String, Object> unloaded = site("buildsite_0002", "monde_archive", 0, 64, 0, "NORTH");
        unloaded.put("worldLoaded", false);

        BuildingSiteDirectory dir = directory(List.of(
                site("buildsite_0001", "world_hub", 0, 64, 0, "NORTH"), unloaded),
                List.of("monde_archive", "world_hub"));

        assertEquals(1, dir.inUnloadedWorlds().size());
        assertEquals("buildsite_0002", dir.inUnloadedWorlds().get(0).id());
        assertEquals(2, dir.total(), "il reste compté : il existe");
    }

    // ---- Absence de relevé ---------------------------------------------------------------------

    /**
     * « Aucun emplacement » et « on n'a pas encore demandé » se ressemblent à l'écran, et la
     * confusion ferait croire à une perte de données après un redémarrage.
     */
    @Test
    void withoutASurveyTheDirectoryIsExplicitlyUnavailable() {
        assertFalse(BuildingSiteDirectory.from(null).available());
        assertTrue(BuildingSiteDirectory.from(null).sites().isEmpty());
        assertFalse(BuildingSiteDirectory.unavailable().available());
        // Un relevé présent mais vide EST une information : zéro emplacement.
        assertTrue(directory(List.of(), List.of()).available());
        assertEquals(0, directory(List.of(), List.of()).total());
    }

    @Test
    void findIsCaseInsensitiveAndSafeOnBlanks() {
        BuildingSiteDirectory dir = directory(
                List.of(site("buildsite_0001", "world_hub", 0, 64, 0, "NORTH")),
                List.of("world_hub"));

        assertTrue(dir.find("BUILDSITE_0001").isPresent());
        assertTrue(dir.find(" buildsite_0001 ").isPresent());
        assertTrue(dir.find("").isEmpty());
        assertTrue(dir.find(null).isEmpty());
        assertTrue(dir.find("buildsite_9999").isEmpty());
    }

    private static BuildingSiteView view(String facing) {
        return directory(List.of(site("buildsite_0001", "world_hub", 712, 67, -702, facing)),
                List.of("world_hub")).find("buildsite_0001").orElseThrow();
    }
}
