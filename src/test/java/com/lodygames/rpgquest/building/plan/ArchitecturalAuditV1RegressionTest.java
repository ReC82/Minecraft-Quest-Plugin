package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #94, lot A — <strong>le test de régression</strong> : le plan V1 réellement produit par
 * l'IA pour la Bibliothèque du Hub.
 *
 * <h2>Ce plan n'est pas une invention</h2>
 *
 * <p>C'est le plan exact, recopié depuis le paramètre de l'action {@code building.generate} qui a
 * abouti en production DEV le 2026-10-11 — 21 instructions, 1 700 caractères. Il a produit
 * <strong>2 210 blocs</strong>, et le bâtiment posé sur {@code buildsite_0012} a été jugé
 * inacceptable en jeu : bloc rectangulaire sans relief, aucune porte visible, aucune fenêtre,
 * intérieur noir, toiture incohérente.</p>
 *
 * <p>Ce test fige ce que l'audit doit en dire. S'il passait un jour, cela voudrait dire que l'audit
 * a cessé de voir des défauts qui ont été <em>constatés en jeu</em>.</p>
 */
class ArchitecturalAuditV1RegressionTest {

    /** Le plan V1, à l'identique. */
    private static final String V1 = """
            id: bibliotheque_du_hub
            name: "Bibliothèque du Hub"
            front: WEST
            size:
              x: 19
              y: 14
              z: 19
            steps:
              - op: box
                from: [0, 0, 0]
                to: [18, 0, 18]
                block: minecraft:stone_bricks
              - op: walls
                from: [0, 1, 0]
                to: [18, 7, 18]
                block: minecraft:stone_bricks
              - op: clear
                from: [1, 1, 1]
                to: [17, 7, 17]
              - op: box
                from: [5, 1, 5]
                to: [13, 1, 13]
                block: minecraft:oak_planks
              - op: clear
                from: [5, 1, 9]
                to: [5, 2, 9]
              - op: box
                from: [1, 1, 1]
                to: [1, 1, 17]
                block: minecraft:bookshelf
              - op: box
                from: [17, 1, 1]
                to: [17, 1, 17]
                block: minecraft:bookshelf
              - op: box
                from: [1, 1, 1]
                to: [17, 1, 1]
                block: minecraft:bookshelf
              - op: shell
                from: [7, 0, 6]
                to: [11, 5, 6]
                block: minecraft:oak_planks
              - op: clear
                from: [9, 1, 6]
                to: [9, 2, 6]
              - op: box
                from: [1, 3, 1]
                to: [17, 3, 17]
                block: minecraft:dark_oak_planks
              - op: clear
                from: [8, 4, 8]
                to: [10, 4, 10]
              - op: box
                from: [0, 8, 0]
                to: [18, 8, 18]
                block: minecraft:stone_bricks
              - op: gable
                from: [0, 9, 0]
                to: [18, 13, 18]
                axis: x
                block: minecraft:dark_oak_stairs
              - op: clear
                from: [9, 9, 0]
                to: [9, 9, 18]
              - op: clear
                from: [8, 10, 0]
                to: [10, 10, 18]
              - op: clear
                from: [7, 11, 0]
                to: [11, 11, 18]
              - op: clear
                from: [6, 12, 0]
                to: [12, 12, 18]
              - op: gable
                from: [0, 9, 0]
                to: [18, 13, 18]
                axis: z
                block: minecraft:dark_oak_stairs
            """;

    private static ArchitecturalAudit.Report auditV1() {
        List<String> problems = new java.util.ArrayList<>();
        BuildingPlan plan = BuildingPlanYaml.read(V1, problems)
                .orElseThrow(() -> new AssertionError("le plan V1 doit rester lisible : " + problems));
        BuildingPlanExpander.Result expansion = BuildingPlanExpander.expand(plan);
        assertTrue(expansion.ok(), expansion.error());
        // Contrôle de fidélité : le serveur a rapporté 2 210 blocs pour ce plan. Si ce nombre
        // change, c'est l'expanseur qui a bougé, et tout le reste du test ne veut plus rien dire.
        assertEquals(2210, expansion.solidCount(),
                "l'expansion du plan V1 doit toujours produire les 2 210 blocs constatés en production");
        return ArchitecturalAudit.audit(expansion.blueprint(), plan.front(), plan.rules());
    }

    @Test
    @DisplayName("Le plan V1 est REFUSÉ, sur les cinq défauts démontrés en jeu")
    void thev1PlanIsRefusedOnTheFiveDemonstratedDefects() {
        ArchitecturalAudit.Report report = auditV1();

        assertTrue(report.refused(), "la V1 ne doit JAMAIS repasser en succès");

        List<String> codes = report.blockingCodes();
        for (String expected : List.of("NO_ENTRANCE", "GLAZING_MISSING", "NO_LIGHT",
                "ENVELOPE_OPEN", "LEVEL_UNREACHABLE")) {
            assertTrue(codes.contains(expected),
                    "défaut constaté en jeu non détecté : " + expected + " — codes obtenus " + codes);
        }
        assertEquals(5, codes.size(),
                "exactement les cinq défauts démontrés, ni plus ni moins : " + codes);
    }

    @Test
    @DisplayName("L'absence d'entrée est chiffrée, et dit où percer")
    void themissingEntranceIsQuantifiedAndSaysWhereToCut() {
        String message = messageOf(auditV1(), "NO_ENTRANCE");

        // La façade attendue est nommée, avec sa coordonnée : c'est exactement ce que le modèle a
        // raté en visant x=5.
        assertTrue(message.contains("OUEST"), message);
        assertTrue(message.contains("x=0"), message);
        // Et le compte réel, borné à la bande d'entrée : zéro cellule franchissable. Compter
        // toute la hauteur de la façade y mêlerait les 59 cellules ouvertes du pignon, et
        // laisserait croire au modèle qu'une ouverture existe déjà.
        assertTrue(message.contains("0 cellule(s) sur"), message);
        assertTrue(message.contains("bande de hauteur où une entrée peut se trouver"), message);
        assertTrue(message.contains("DANS LE PLAN DE LA FAÇADE"),
                "le message doit désigner l'erreur réelle du modèle : " + message);
    }

    @Test
    @DisplayName("Les pignons ouverts sont désignés comme tels, pas comme un toit troué")
    void theopenGablesAreNamedPreciselyRatherThanCalledALeakyRoof() {
        String message = messageOf(auditV1(), "ENVELOPE_OPEN");

        assertTrue(message.contains("PIGNONS"), message);
        assertTrue(message.contains("couvre par le dessus"),
                "la nuance mesurée compte : la couverture était bonne, les pignons non — " + message);
    }

    @Test
    @DisplayName("L'étage inaccessible explique la vraie erreur : la trémie n'était pas dans le plancher")
    void theunreachableFloorExplainsTheRealMistake() {
        String message = messageOf(auditV1(), "LEVEL_UNREACHABLE");

        assertTrue(message.contains("trémie"), message);
        assertTrue(message.contains("vider l'air au-dessus du plancher ne le troue pas"),
                "le modèle avait vidé y=4 alors que le plancher était en y=3 — " + message);
    }

    @Test
    @DisplayName("Les mesures de la V1 correspondent à ce qui a été constaté")
    void thev1MetricsMatchWhatWasMeasured() {
        ArchitecturalAudit.Metrics m = auditV1().metrics();

        assertEquals(2210, m.solidCells());
        assertEquals(0, m.glazedCells(), "aucun vitrage");
        assertEquals(0, m.lightSources(), "aucune source de lumière");
        assertEquals(49, m.furnishingCells(), "les 49 étagères comptent comme du mobilier");
        assertTrue(m.openEnvelopeCellsHigh() > 100,
                "les pignons ouverts représentent plus de 100 cellules : " + m.openEnvelopeCellsHigh());
        assertTrue(m.floors() >= 2, "deux niveaux praticables étaient prévus : " + m.floors());
        assertEquals(1, m.reachableFloors(), "un seul était atteignable");
        assertTrue(m.topMaterialShare() > 0.70,
                "un matériau dominait l'enveloppe : " + m.topMaterial() + " à " + m.topMaterialShare());
    }

    @Test
    @DisplayName("Le refus est rédigé pour être renvoyé au modèle, numéroté point par point")
    void therefusalIsWrittenToBeSentBackToTheModel() {
        String refusal = auditV1().refusalMessage();

        assertTrue(refusal.contains("Corrige PRÉCISÉMENT"), refusal);
        for (String n : List.of("1.", "2.", "3.", "4.", "5.")) {
            assertTrue(refusal.contains(n), "point " + n + " manquant dans : " + refusal);
        }
        assertFalse(refusal.isBlank());
    }

    /**
     * Le même plan, déclaré « décoration », n'est plus jugé sur l'habitabilité.
     *
     * <p>C'est l'exigence explicite du ticket : ne pas imposer les règles d'un bâtiment habitable
     * aux futures décorations (#244) et architectures de portail (#228).</p>
     */
    @Test
    @DisplayName("Déclaré « décoration », le même volume n'est plus refusé pour l'habitabilité")
    void thesameVolumeDeclaredAsDecorationIsNoLongerJudgedOnHabitability() {
        List<String> problems = new java.util.ArrayList<>();
        BuildingPlan plan = BuildingPlanYaml.read(V1.replace("front: WEST",
                "front: WEST\nkind: decoration"), problems).orElseThrow(() -> new AssertionError(problems));
        assertEquals(StructureKind.DECORATION, plan.kind());

        ArchitecturalAudit.Report report = ArchitecturalAudit.audit(
                BuildingPlanExpander.expand(plan).blueprint(), plan.front(), plan.rules());

        assertFalse(report.refused(),
                "une décoration n'a ni porte, ni fenêtre, ni étage : " + report.blockingCodes());
        // Et aucun des contrôles propres à l'habitabilité ne doit s'être appliqué, même en
        // avertissement : c'est l'exigence du ticket.
        List<String> codes = report.findings().stream().map(ArchitecturalFinding::code).toList();
        for (String habitableOnly : List.of("NO_ENTRANCE", "GLAZING_MISSING", "NO_LIGHT",
                "ENVELOPE_OPEN", "LEVEL_UNREACHABLE")) {
            assertFalse(codes.contains(habitableOnly),
                    habitableOnly + " ne doit pas s'appliquer à une décoration : " + codes);
        }
    }

    private static String messageOf(ArchitecturalAudit.Report report, String code) {
        return report.findings().stream()
                .filter(f -> f.code().equals(code))
                .map(ArchitecturalFinding::message)
                .findFirst()
                .orElseThrow(() -> new AssertionError("constat " + code + " absent : "
                        + report.blockingCodes()));
    }
}
