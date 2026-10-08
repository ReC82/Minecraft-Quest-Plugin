package com.lodygames.rpgquest.building.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Issue #213 — la règle d'ancrage « bloc cliqué + face → position finale ».
 *
 * <p>Le ticket demande que cette règle soit <em>précise</em>. Une règle qu'on ne peut pas exécuter
 * dans un test n'est pas précise : elle est seulement écrite. Ces tests la fixent, et surtout ils
 * fixent le cas qui compte — cliquer le dessus du sol donne la case <strong>au-dessus</strong>,
 * celle où reposera le bâtiment, et pas le bloc de terre.</p>
 */
class BuildingSiteAnchorTest {

    /** Le parcours normal : on clique le sol, l'ancre est la case libre juste au-dessus. */
    @Test
    void clickingTheTopOfTheGroundAnchorsOneBlockAbove() {
        BuildingSiteAnchor anchor = BuildingSiteAnchor.resolve(712, 66, -702, ClickedFace.UP);

        assertEquals(712, anchor.x());
        assertEquals(67, anchor.y(), "le bâtiment repose SUR le bloc cliqué, pas dedans");
        assertEquals(-702, anchor.z());
    }

    /** La même règle, sans cas particulier, sur les cinq autres faces. */
    @Test
    void theSameRuleAppliesToEveryFace() {
        assertEquals(new BuildingSiteAnchor(10, 19, 30),
                BuildingSiteAnchor.resolve(10, 20, 30, ClickedFace.DOWN));
        assertEquals(new BuildingSiteAnchor(10, 20, 29),
                BuildingSiteAnchor.resolve(10, 20, 30, ClickedFace.NORTH));
        assertEquals(new BuildingSiteAnchor(10, 20, 31),
                BuildingSiteAnchor.resolve(10, 20, 30, ClickedFace.SOUTH));
        assertEquals(new BuildingSiteAnchor(11, 20, 30),
                BuildingSiteAnchor.resolve(10, 20, 30, ClickedFace.EAST));
        assertEquals(new BuildingSiteAnchor(9, 20, 30),
                BuildingSiteAnchor.resolve(10, 20, 30, ClickedFace.WEST));
    }

    /** Pas de face exploitable : on ne déplace rien plutôt que de deviner. */
    @Test
    void anUnusableFaceLeavesTheClickedBlockUntouched() {
        assertEquals(new BuildingSiteAnchor(1, 2, 3),
                BuildingSiteAnchor.resolve(1, 2, 3, ClickedFace.SELF));
        assertEquals(new BuildingSiteAnchor(1, 2, 3),
                BuildingSiteAnchor.resolve(1, 2, 3, null));
    }

    /**
     * Les faces diagonales que {@code BlockFace} déclare n'arrivent jamais sur un clic de bloc —
     * mais si l'une arrivait, l'ancre doit rester le bloc cliqué, pas une position inventée.
     */
    @Test
    void anUnknownBukkitFaceFallsBackToTheClickedBlock() {
        assertEquals(ClickedFace.SELF, ClickedFace.of("NORTH_EAST"));
        assertEquals(ClickedFace.SELF, ClickedFace.of("n'importe quoi"));
        assertEquals(ClickedFace.SELF, ClickedFace.of(null));
        assertEquals(ClickedFace.SELF, ClickedFace.of(""));
        assertEquals(new BuildingSiteAnchor(5, 5, 5),
                BuildingSiteAnchor.resolve(5, 5, 5, ClickedFace.of("NORTH_WEST")));
    }

    @Test
    void bukkitFaceNamesAreReadCaseInsensitively() {
        assertEquals(ClickedFace.UP, ClickedFace.of("UP"));
        assertEquals(ClickedFace.UP, ClickedFace.of(" up "));
        assertEquals(ClickedFace.WEST, ClickedFace.of("west"));
    }

    /** Les coordonnées négatives et le Y profond sont la norme, pas un cas limite. */
    @Test
    void negativeAndDeepCoordinatesAreHandled() {
        assertEquals(new BuildingSiteAnchor(-1000, -63, -1000),
                BuildingSiteAnchor.resolve(-1000, -64, -1000, ClickedFace.UP));
        assertEquals(new BuildingSiteAnchor(0, 319, 0),
                BuildingSiteAnchor.resolve(0, 318, 0, ClickedFace.UP));
    }
}
