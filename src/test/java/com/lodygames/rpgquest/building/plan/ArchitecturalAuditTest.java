package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #94, lot A — l'audit accepte-t-il un bâtiment correct, et refuse-t-il pour la bonne raison ?
 *
 * <p>Un audit qui refuse tout serait aussi inutile qu'un audit qui accepte tout. Ces tests
 * construisent un bâtiment qui satisfait les sept critères, puis retirent <strong>un seul</strong>
 * élément à la fois pour vérifier que le refus désigne précisément ce qui manque.</p>
 */
class ArchitecturalAuditTest {

    /**
     * Une petite maison correcte : porte traversante à l'ouest, vitrages sur deux façades,
     * lanterne, enveloppe fermée, trois matériaux.
     *
     * <p>Toit <strong>plat</strong>, et ce n'est pas un raccourci : la primitive {@code gable}
     * actuelle ne remplit pas ses pignons, donc elle ne peut pas satisfaire le contrôle
     * d'enveloppe. C'est précisément ce que le lot B (#250) doit corriger, et le test
     * {@link #thecurrentGablePrimitiveCannotSatisfyTheEnvelopeCheck()} le fige.</p>
     */
    private static String house(String... extra) {
        StringBuilder sb = new StringBuilder("""
                id: petite_maison
                name: "Petite maison"
                front: WEST
                size:
                  x: 11
                  y: 7
                  z: 9
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [10, 0, 8]
                    block: minecraft:stone_bricks
                  - op: walls
                    from: [0, 1, 0]
                    to: [10, 4, 8]
                    block: minecraft:stone_bricks
                  - op: clear
                    from: [1, 1, 1]
                    to: [9, 4, 7]
                  - op: walls
                    from: [0, 4, 0]
                    to: [10, 4, 8]
                    block: minecraft:dark_oak_planks
                  - op: box
                    from: [0, 5, 0]
                    to: [10, 5, 8]
                    block: minecraft:dark_oak_planks
                """);
        for (String step : extra) {
            sb.append(step);
        }
        return sb.toString();
    }

    /** La porte : deux cellules vidées DANS le plan de la façade ouest, au niveau du sol. */
    private static final String DOOR = """
              - op: clear
                from: [0, 1, 4]
                to: [0, 2, 4]
            """;

    /** Des vitrages sur deux façades opposées. */
    private static final String WINDOWS = """
              - op: box
                from: [0, 3, 2]
                to: [0, 3, 2]
                block: minecraft:glass
              - op: box
                from: [10, 3, 6]
                to: [10, 3, 6]
                block: minecraft:glass_pane
            """;

    /** Une lanterne au centre : toutes les cellules praticables sont à moins de 8 blocs. */
    private static final String LIGHT = """
              - op: box
                from: [5, 4, 4]
                to: [5, 4, 4]
                block: minecraft:lantern
            """;

    private static ArchitecturalAudit.Report audit(String yaml) {
        List<String> problems = new ArrayList<>();
        BuildingPlan plan = BuildingPlanYaml.read(yaml, problems)
                .orElseThrow(() -> new AssertionError("plan illisible : " + problems));
        BuildingPlanExpander.Result expansion = BuildingPlanExpander.expand(plan);
        assertTrue(expansion.ok(), expansion.error());
        return ArchitecturalAudit.audit(expansion.blueprint(), plan.front(), plan.rules());
    }

    // ---- Le cas qui doit passer ---------------------------------------------------------------

    @Test
    @DisplayName("Une maison correcte est acceptée")
    void acorrectHouseIsAccepted() {
        ArchitecturalAudit.Report report = audit(house(DOOR, WINDOWS, LIGHT));

        assertFalse(report.refused(),
                "cette maison respecte les sept critères : " + report.blockingCodes());
        assertEquals("", report.refusalMessage());

        ArchitecturalAudit.Metrics m = report.metrics();
        assertEquals(2, m.glazedCells());
        assertEquals(2, m.glazedFacades(), "une façade à l'ouest, une à l'est");
        assertEquals(1, m.lightSources());
        assertEquals(0, m.openEnvelopeCellsHigh(), "enveloppe close au-dessus de la bande d'entrée");
    }

    // ---- Un seul élément retiré à la fois -----------------------------------------------------

    @Test
    @DisplayName("Sans porte : refus sur l'entrée, et sur rien d'autre")
    void withoutADoorOnlyTheEntranceIsRefused() {
        ArchitecturalAudit.Report report = audit(house(WINDOWS, LIGHT));

        assertEquals(List.of("NO_ENTRANCE"), report.blockingCodes());
    }

    @Test
    @DisplayName("Sans vitrage : refus sur les vitrages, et sur rien d'autre")
    void withoutGlazingOnlyTheWindowsAreRefused() {
        ArchitecturalAudit.Report report = audit(house(DOOR, LIGHT));

        assertEquals(List.of("GLAZING_MISSING"), report.blockingCodes());
    }

    @Test
    @DisplayName("Sans lumière : refus sur l'éclairage, et sur rien d'autre")
    void withoutLightOnlyTheLightingIsRefused() {
        ArchitecturalAudit.Report report = audit(house(DOOR, WINDOWS));

        assertEquals(List.of("NO_LIGHT"), report.blockingCodes());
    }

    @Test
    @DisplayName("Des vitrages regroupés sur une seule façade sont refusés — un côté éclairé ne suffit pas")
    void glazingOnASingleFacadeIsRefused() {
        String oneSided = """
              - op: box
                from: [0, 3, 2]
                to: [0, 3, 2]
                block: minecraft:glass
              - op: box
                from: [0, 3, 6]
                to: [0, 3, 6]
                block: minecraft:glass
            """;
        ArchitecturalAudit.Report report = audit(house(DOOR, oneSided, LIGHT));

        assertEquals(List.of("GLAZING_ONE_SIDED"), report.blockingCodes());
        assertTrue(report.blocking().get(0).message().contains("OUEST"));
    }

    /**
     * Une bougie décore, elle n'éclaire pas. Le registre connaît son niveau (3) et le seuil
     * d'utilité (10) : c'est le <strong>niveau</strong> qui décide, pas le fait que le mot
     * ressemble à une source de lumière.
     */
    @Test
    @DisplayName("Une bougie ne compte pas comme éclairage d'une salle")
    void acandleDoesNotCountAsRoomLighting() {
        String candle = """
              - op: box
                from: [5, 1, 4]
                to: [5, 1, 4]
                block: minecraft:candle
            """;
        ArchitecturalAudit.Report report = audit(house(DOOR, WINDOWS, candle));

        assertEquals(List.of("NO_LIGHT"), report.blockingCodes());
    }

    @Test
    @DisplayName("Une source trop lointaine est refusée, avec la cellule et la distance")
    void alightTooFarAwayIsRefusedWithTheExactCell() {
        // Bâtiment allongé, lanterne collée à une extrémité : l'autre bout dépasse les 8 blocs.
        String longHouse = """
                id: longue_salle
                name: "Longue salle"
                front: WEST
                size:
                  x: 30
                  y: 7
                  z: 7
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [29, 0, 6]
                    block: minecraft:stone_bricks
                  - op: walls
                    from: [0, 1, 0]
                    to: [29, 4, 6]
                    block: minecraft:stone_bricks
                  - op: clear
                    from: [1, 1, 1]
                    to: [28, 4, 5]
                  - op: walls
                    from: [0, 4, 0]
                    to: [29, 4, 6]
                    block: minecraft:dark_oak_planks
                  - op: box
                    from: [0, 5, 0]
                    to: [29, 5, 6]
                    block: minecraft:dark_oak_planks
                  - op: clear
                    from: [0, 1, 3]
                    to: [0, 2, 3]
                  - op: box
                    from: [0, 3, 1]
                    to: [0, 3, 1]
                    block: minecraft:glass
                  - op: box
                    from: [29, 3, 5]
                    to: [29, 3, 5]
                    block: minecraft:glass
                  - op: box
                    from: [2, 4, 3]
                    to: [2, 4, 3]
                    block: minecraft:lantern
                """;
        ArchitecturalAudit.Report report = audit(longHouse);

        assertEquals(List.of("DARK_AREA"), report.blockingCodes());
        String message = report.blocking().get(0).message();
        assertTrue(message.contains("blocs de la source"), message);
        assertTrue(message.contains("distance maximale est de 8"), message);
    }

    // ---- Niveaux et circulation ---------------------------------------------------------------

    /**
     * Deux niveaux reliés par une échelle. Ce test vaut surtout pour le lot B : il prouve que la
     * primitive {@code ladder} à venir sera reconnue <strong>sans toucher à l'audit</strong>,
     * parce que c'est {@link BlockSemantics} qui porte le sens de « grimpable ».
     */
    @Test
    @DisplayName("Un étage relié par une échelle est reconnu accessible")
    void anUpperFloorReachedByALadderIsAccepted() {
        String twoStoreys = """
                id: maison_etage
                name: "Maison à étage"
                front: WEST
                size:
                  x: 11
                  y: 11
                  z: 9
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [10, 0, 8]
                    block: minecraft:stone_bricks
                  - op: walls
                    from: [0, 1, 0]
                    to: [10, 8, 8]
                    block: minecraft:stone_bricks
                  - op: clear
                    from: [1, 1, 1]
                    to: [9, 8, 7]
                  - op: box
                    from: [1, 4, 1]
                    to: [9, 4, 7]
                    block: minecraft:dark_oak_planks
                  - op: clear
                    from: [8, 4, 6]
                    to: [8, 4, 6]
                  - op: box
                    from: [8, 1, 7]
                    to: [8, 4, 7]
                    block: minecraft:ladder
                  - op: walls
                    from: [0, 8, 0]
                    to: [10, 8, 8]
                    block: minecraft:dark_oak_planks
                  - op: box
                    from: [0, 9, 0]
                    to: [10, 9, 8]
                    block: minecraft:dark_oak_planks
                  - op: clear
                    from: [0, 1, 4]
                    to: [0, 2, 4]
                  - op: box
                    from: [0, 3, 2]
                    to: [0, 3, 2]
                    block: minecraft:glass
                  - op: box
                    from: [10, 6, 6]
                    to: [10, 6, 6]
                    block: minecraft:glass
                  - op: box
                    from: [5, 3, 4]
                    to: [5, 3, 4]
                    block: minecraft:lantern
                  - op: box
                    from: [5, 7, 4]
                    to: [5, 7, 4]
                    block: minecraft:lantern
                """;
        ArchitecturalAudit.Report report = audit(twoStoreys);

        assertFalse(report.blockingCodes().contains("LEVEL_UNREACHABLE"),
                "l'échelle doit relier les deux niveaux : " + report.blockingCodes());
        assertTrue(report.metrics().floors() >= 2, "deux niveaux attendus");
        assertEquals(report.metrics().floors(), report.metrics().reachableFloors(),
                "tous les niveaux doivent être atteignables");
    }

    // ---- Ce que la primitive actuelle ne peut PAS faire ---------------------------------------

    /**
     * Fige la dépendance au lot B (#250) : la primitive {@code gable} actuelle pose une seule
     * rangée par rang et ne remplit <strong>jamais</strong> ses pignons. Elle ne peut donc pas
     * satisfaire le contrôle d'enveloppe — et c'est exactement le défaut mesuré sur la V1,
     * 59 cellules vides sur 95 par face.
     *
     * <p>Quand le lot B corrigera {@code gable}, ce test devra être mis à jour : ce sera le signe
     * que la correction fonctionne.</p>
     */
    @Test
    @DisplayName("La primitive gable actuelle ne peut pas satisfaire le contrôle d'enveloppe")
    void thecurrentGablePrimitiveCannotSatisfyTheEnvelopeCheck() {
        // Maison identique, mais assez haute pour porter un toit : la maison de référence est
        // volontairement plafonnée à y=6, et un gable n'y entrerait pas.
        String withGable = """
                id: maison_toit
                name: "Maison à toit en pente"
                front: WEST
                size:
                  x: 11
                  y: 12
                  z: 9
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [10, 0, 8]
                    block: minecraft:stone_bricks
                  - op: walls
                    from: [0, 1, 0]
                    to: [10, 4, 8]
                    block: minecraft:stone_bricks
                  - op: clear
                    from: [1, 1, 1]
                    to: [9, 4, 7]
                  - op: box
                    from: [0, 5, 0]
                    to: [10, 5, 8]
                    block: minecraft:dark_oak_planks
                  - op: clear
                    from: [0, 1, 4]
                    to: [0, 2, 4]
                  - op: box
                    from: [0, 3, 2]
                    to: [0, 3, 2]
                    block: minecraft:glass
                  - op: box
                    from: [10, 3, 6]
                    to: [10, 3, 6]
                    block: minecraft:glass_pane
                  - op: box
                    from: [5, 4, 4]
                    to: [5, 4, 4]
                    block: minecraft:lantern
                  - op: gable
                    from: [0, 6, 0]
                    to: [10, 10, 8]
                    axis: x
                    block: minecraft:dark_oak_stairs
                """;
        ArchitecturalAudit.Report report = audit(withGable);

        assertTrue(report.blockingCodes().contains("ENVELOPE_OPEN"),
                "les pignons non remplis doivent être détectés : " + report.blockingCodes());
        assertTrue(report.metrics().openEnvelopeCellsHigh() > 0);
    }

    // ---- Profils par type de structure --------------------------------------------------------

    @Test
    @DisplayName("Une décoration n'est jamais jugée sur l'habitabilité")
    void adecorationIsNeverJudgedOnHabitability() {
        String fountain = """
                id: fontaine
                name: "Fontaine"
                kind: decoration
                front: NORTH
                size:
                  x: 5
                  y: 3
                  z: 5
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [4, 0, 4]
                    block: minecraft:stone_bricks
                  - op: walls
                    from: [0, 1, 0]
                    to: [4, 1, 4]
                    block: minecraft:chiseled_stone_bricks
                  - op: box
                    from: [2, 1, 2]
                    to: [2, 2, 2]
                    block: minecraft:quartz_pillar
                """;
        ArchitecturalAudit.Report report = audit(fountain);

        assertFalse(report.refused(), "une fontaine n'a ni porte, ni fenêtre : "
                + report.blockingCodes());
    }

    @Test
    @DisplayName("Une architecture de portail doit être franchissable, et c'est tout")
    void aportalArchitectureMustBeCrossableAndNothingElse() {
        String arch = """
                id: arche
                name: "Arche"
                kind: portal
                front: NORTH
                size:
                  x: 7
                  y: 6
                  z: 3
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [6, 4, 2]
                    block: minecraft:stone_bricks
                  - op: clear
                    from: [2, 1, 0]
                    to: [4, 3, 2]
                  - op: box
                    from: [0, 0, 0]
                    to: [6, 0, 2]
                    block: minecraft:polished_andesite
                """;
        ArchitecturalAudit.Report report = audit(arch);

        assertFalse(report.refused(), "l'arche est traversée de part en part : "
                + report.blockingCodes());
    }

    @Test
    @DisplayName("Une arche bouchée est refusée : un portail qu'on ne franchit pas n'est pas un portail")
    void asealedArchIsRefused() {
        String sealed = """
                id: arche_bouchee
                name: "Arche bouchée"
                kind: portal
                front: NORTH
                size:
                  x: 7
                  y: 6
                  z: 3
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [6, 4, 2]
                    block: minecraft:stone_bricks
                """;
        ArchitecturalAudit.Report report = audit(sealed);

        assertEquals(List.of("NO_PASSAGE"), report.blockingCodes());
    }

    // ---- Avertissements -----------------------------------------------------------------------

    /**
     * Un bloc sans rôle déclaré est traité en matière opaque — et c'est le comportement normal, pas
     * une anomalie : briques, planches et pierre taillée n'ont aucun rôle architectural. L'audit ne
     * doit donc rien en dire, mais il ne doit pas non plus leur prêter une qualité.
     */
    @Test
    @DisplayName("Un bloc sans rôle déclaré est traité en matière opaque, sans bruit")
    void ablockWithoutADeclaredRoleIsTreatedAsOpaqueMatter() {
        String odd = """
              - op: box
                from: [5, 1, 2]
                to: [5, 1, 2]
                block: minecraft:elven_marble
            """;
        ArchitecturalAudit.Report report = audit(house(DOOR, WINDOWS, LIGHT, odd));

        assertFalse(report.refused(), "un bloc inconnu n'est pas un motif de refus");
        assertFalse(BlockSemantics.hasDeclaredRole("minecraft:elven_marble"));
        assertFalse(BlockSemantics.glazing("minecraft:elven_marble"),
                "et surtout : aucune qualité ne lui est prêtée");
        assertFalse(BlockSemantics.lightSource("minecraft:elven_marble"));
        assertFalse(BlockSemantics.passable("minecraft:elven_marble"));
        // Un mur de briques n'a pas de rôle déclaré non plus, et c'est correct.
        assertFalse(BlockSemantics.hasDeclaredRole("minecraft:stone_bricks"));
        assertTrue(BlockSemantics.support("minecraft:stone_bricks"));
    }

    @Test
    @DisplayName("Une structure vide est refusée plutôt qu'auditée dans le vide")
    void anEmptyStructureIsRefused() {
        ArchitecturalAudit.Report report = ArchitecturalAudit.audit(null, null, null);

        assertEquals(List.of("EMPTY_STRUCTURE"), report.blockingCodes());
    }
}
