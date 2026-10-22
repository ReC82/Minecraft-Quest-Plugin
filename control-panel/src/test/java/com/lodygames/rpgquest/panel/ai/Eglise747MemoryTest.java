package com.lodygames.rpgquest.panel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #747 — la mémoire des tentatives, la conformité au brief et la nature des reprises, éprouvées
 * sur la forme réelle de l'Église du hub (22/10/2026), sans aucun appel payant.
 */
class Eglise747MemoryTest {

    @TempDir
    Path tmp;

    private static String resource(String name) {
        try (InputStream in = Eglise747MemoryTest.class.getResourceAsStream("/ai/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String LANTERN = "Blocs sans appui — ils tomberaient ou se détacheraient à la pose : 2 bloc(s). "
            + "lantern en %s : lanterne posée sur le vide, et rien au-dessus pour la suspendre — posé par "
            + "l'instruction 11 (light minecraft:lantern de [5, 5, 5] à [13, 5, 5]).";
    private static final String DARK = "Zone non éclairée : la cellule %s est accessible mais se trouve à 13 blocs de "
            + "la source de lumière la plus proche, alors que la distance maximale est de 8.";
    private static final String OPEN = "Enveloppe ouverte en hauteur : %d cellule(s) franchissable(s) sur la surface "
            + "extérieure au-dessus de y=4, alors que 4 au plus sont tolérées.";

    private static String refusal(String... points) {
        StringBuilder sb = new StringBuilder("Plan refusé — contrat architectural non respecté (bâtiment habitable). "
                + "Le bâtiment produit par ce plan ne respecte pas le contrat architectural. Corrige PRÉCISÉMENT les "
                + "points suivants :\n");
        for (int i = 0; i < points.length; i++) {
            sb.append("  ").append(i + 1).append(". ").append(points[i]).append('\n');
        }
        return sb.append("Rien n'a été écrit : ni schematic, ni définition.").toString();
    }

    private static AttemptMemory.Attempt attempt(String id, List<String> codes, String diagnosis) {
        return new AttemptMemory.Attempt(id, 1, false, codes, AttemptMemory.issuesOf(codes, diagnosis));
    }

    /** La suite réelle, réduite : C/D/E (3 codes), F (2 : lanternes réparées), G (lanternes revenues), I (1 code). */
    private static AttemptMemory church() {
        List<String> three = List.of("UNSUPPORTED_BLOCK", "DARK_AREA", "ENVELOPE_OPEN");
        return AttemptMemory.of(List.of(
                attempt("6b19e53c", three, refusal(LANTERN.formatted("5/5/5"), DARK.formatted("6/1/18"),
                        OPEN.formatted(504))),
                attempt("79ac1657", three, refusal(LANTERN.formatted("6/12/5"), DARK.formatted("6/1/18"),
                        OPEN.formatted(504))),
                attempt("207e6e4f", List.of("DARK_AREA", "ENVELOPE_OPEN"), refusal(DARK.formatted("0/1/16"),
                        OPEN.formatted(504))),
                attempt("21b94ae2", three, refusal(LANTERN.formatted("5/11/5"), DARK.formatted("4/1/2"),
                        OPEN.formatted(504))),
                attempt("804a02d9", List.of("PLAN_INVALID"), "Plan refusé : Instruction 12 (STAIRCASE) : la volée "
                        + "sort du volume en x=1 y=5 z=-1.")));
    }

    @Test
    void diagnosesAreComparedByCodeLocationAndCauseNotByTheirWholeText() {
        List<AttemptMemory.Issue> a = church().attempts().get(0).issues();
        List<AttemptMemory.Issue> b = church().attempts().get(1).issues();

        assertEquals("UNSUPPORTED_BLOCK", a.get(0).code());
        assertEquals("5/5/5", a.get(0).location());
        assertEquals("6/12/5", b.get(0).location());
        // Même faute ailleurs : la cause, sans ses nombres, est identique.
        assertTrue(a.get(0).sameFault(b.get(0)), a.get(0) + " / " + b.get(0));
        assertEquals("instruction 12 (staircase)", church().attempts().get(4).issues().get(0).location());
    }

    @Test
    void theLanternsResolvedAt207e6e4fAndBrokenAgainAt21b94ae2AreARegression() {
        AttemptMemory memory = church();

        var regressions = memory.regressionsOf("21b94ae2");
        assertEquals(1, regressions.size(), regressions.toString());
        assertEquals("UNSUPPORTED_BLOCK", regressions.get(0).code());
        assertEquals("207e6e4f", regressions.get(0).resolvedBy().draftId());
        assertEquals("207e6e4f", memory.bestRefused().orElseThrow().draftId(), "le moins de codes");
    }

    @Test
    void anUnreadablePlanResolvesNothing() {
        AttemptMemory memory = church();

        // 804a02d9 ne s'est pas construit : il ne dit rien de l'enveloppe, de la lumière, des lanternes.
        assertFalse(memory.resolvedCodes().containsKey("ENVELOPE_OPEN"), memory.resolvedCodes().toString());
        assertFalse(memory.resolvedCodes().containsKey("UNSUPPORTED_BLOCK"), memory.resolvedCodes().toString());
        assertFalse(memory.resolvedCodes().containsKey("PLAN_INVALID"));
    }

    @Test
    void stagnationIsDetectedAfterThreeAttemptsWithoutBeatingTheBest() {
        AttemptMemory memory = church();

        // 207e6e4f (2 codes) n'a pas été battu depuis : 21b94ae2 (3) puis 804a02d9 (illisible) → 2.
        assertEquals(2, memory.sinceImprovement());
        assertFalse(memory.stagnating());
        AttemptMemory more = memory.with(attempt("8663160f", List.of("PLAN_INVALID"), "Plan refusé : x"));
        assertTrue(more.stagnating(), more.summary());
    }

    @Test
    void thePromptBlockIsBoundedAndSaysWhatMustNotComeBack() {
        AttemptMemory memory = church().with(attempt("8a2a9614", List.of("ENVELOPE_OPEN"), refusal(OPEN.formatted(72))));
        String block = memory.promptBlock("8a2a9614");

        assertTrue(block.contains("MÉMOIRE DES TENTATIVES"), block);
        assertTrue(block.contains("V-8a2a9614 (version transmise)"), block);
        assertTrue(block.contains("ERREURS DÉJÀ RÉSOLUES") && block.contains("UNSUPPORTED_BLOCK")
                && block.contains("DARK_AREA"), block);
        assertTrue(block.contains("RÉGRESSION : UNSUPPORTED_BLOCK (résolu en V-207e6e4f)"), block);
        assertTrue(block.contains("FAUTES QUI REVIENNENT") && block.contains("ENVELOPE_OPEN"), block);
        assertTrue(block.length() <= 4_100, "borné : " + block.length());
    }

    @Test
    void theMemoryIsReadFromTheProjectJournalOncePerVersion() {
        AiJournalStore store = new AiJournalStore(tmp.resolve("j.db").toString());
        String project = "p-747";
        store.append(BuildingAiJournal.SCOPE, project, validation("207e6e4f", List.of("DARK_AREA"), DARK.formatted("1/1/1")));
        store.append(BuildingAiJournal.SCOPE, project, validation("21b94ae2", List.of("UNSUPPORTED_BLOCK", "DARK_AREA"),
                refusal(LANTERN.formatted("5/11/5"), DARK.formatted("4/1/2"))));
        // Revérifiée au moment d'une amélioration : même version, même place.
        store.append(BuildingAiJournal.SCOPE, project, validation("207e6e4f", List.of("DARK_AREA"), DARK.formatted("1/1/1")));

        AttemptMemory memory = AttemptMemory.fromJournal(store.events(BuildingAiJournal.SCOPE, project));

        assertEquals(2, memory.attempts().size());
        assertEquals("207e6e4f", memory.attempts().get(0).draftId());
        assertEquals("5/11/5", memory.attempts().get(1).issues().get(0).location());
    }

    private static AiJournalStore.NewEvent validation(String draft, List<String> codes, String diagnosis) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", false);
        body.put("codes", codes);
        body.put("diagnosis", diagnosis);
        body.put("version", 1);
        return new AiJournalStore.NewEvent("VALIDATION", "", "", "VÉRIFICATION", 0, draft, "draft:" + draft,
                Instant.now(), -1, "owner", "Refusé", body);
    }

    // ---- Conformité au brief -------------------------------------------------------------------

    @Test
    void theChurchBriefRequirementsAreExtractedWithoutAnyChurchSpecificCode() {
        List<String> ids = BriefRequirements.extract(resource("eglise_747_brief.txt")).stream()
                .map(BriefRequirements.Requirement::id).toList();

        for (String expected : List.of("bell", "lectern", "stained_glass", "carpet", "lantern", "candle", "flowers",
                "benches", "tower", "altar", "dais", "aisle", "cross")) {
            assertTrue(ids.contains(expected), expected + " absent de " + ids);
        }
        // « Ne pas générer le personnage » : rien n'est exigé pour un PNJ.
        assertFalse(ids.contains("bed"), ids.toString());
    }

    @Test
    void theReplayedChurchPlanMissesTheBellAndTheBenches() {
        String plan = resource("eglise_747_8a2a9614.yml");
        // La palette réellement mesurée par le serveur DEV sur cette version (action 4ab3d4d4, 22/10/2026) :
        // le « clear » a effacé le grand vitrail rouge, les vitraux latéraux bleus et jaunes restent.
        Map<String, Object> palette = Map.of("minecraft:lectern", 3, "minecraft:lantern", 4,
                "minecraft:light_blue_stained_glass_pane", 16, "minecraft:yellow_stained_glass_pane", 16,
                "minecraft:white_concrete", 706, "minecraft:polished_blackstone_stairs", 361);

        BriefRequirements.Report report = BriefRequirements.evaluate(resource("eglise_747_brief.txt"), plan, palette);
        Map<String, BriefRequirements.Status> byId = new LinkedHashMap<>();
        report.statuses().forEach(s -> byId.put(s.requirement().id(), s));

        assertTrue(byId.get("bell").missing(), "aucune cloche");
        assertTrue(byId.get("benches").missing(), "les seuls escaliers sont ceux du toit");
        assertFalse(byId.get("lectern").missing(), "le pupitre est là");
        assertEquals(Boolean.TRUE, byId.get("stained_glass").inResult());
        // Une tour ne se prouve par aucun bloc : jamais déclarée présente ni manquante.
        assertFalse(byId.get("tower").missing());
        assertTrue(byId.get("tower").line().startsWith("◌"), byId.get("tower").line());
        assertFalse(report.complete());
        assertTrue(report.promptBlock().contains("✗ Cloche"), report.promptBlock());
    }

    @Test
    void anElementInThePlanButErasedFromTheCompiledResultIsMissing() {
        String plan = "steps:\n  - op: box\n    from: [1, 1, 1]\n    block: minecraft:bell\n";

        BriefRequirements.Report report = BriefRequirements.evaluate("Une cloche.", plan, Map.of("minecraft:stone", 9));

        BriefRequirements.Status bell = report.statuses().get(0);
        assertTrue(bell.inPlan());
        assertEquals(Boolean.FALSE, bell.inResult());
        assertTrue(bell.missing() && bell.line().contains("ABSENT du résultat compilé"), bell.line());
    }

    @Test
    void aNegatedElementIsNotRequired() {
        assertTrue(BriefRequirements.extract("Une petite maison sans lit ni coffre, avec un four.").stream()
                .map(BriefRequirements.Requirement::id).toList().equals(List.of("furnace")));
    }

    // ---- Les reprises --------------------------------------------------------------------------

    private static final BuildingPromptBuilder.Demand DEMAND = new BuildingPromptBuilder.Demand("p", "Église",
            "SOUTH", "Une église avec une cloche.", 19, 19, 23, "eglise");

    @Test
    void aRepairPreservesAndAnImprovementMayRethinkVolumesAndAddWhatTheBriefMisses() {
        String repair = BuildingPromptBuilder.correctionPrompt(DEMAND, "plan", "motif", "", 0, 0,
                BuildingPromptBuilder.Repair.of(BuildingPromptBuilder.RepairMode.REPAIR, "MÉMOIRE X", "CONFORMITÉ Y"));
        String improve = BuildingPromptBuilder.correctionPrompt(DEMAND, "plan", "motif", "", 0, 0,
                BuildingPromptBuilder.Repair.of(BuildingPromptBuilder.RepairMode.IMPROVE, "MÉMOIRE X", "CONFORMITÉ Y"));

        assertTrue(repair.contains("MODE : RÉPARATION TECHNIQUE") && repair.contains("CONSERVE tout ce qui est déjà juste"));
        assertTrue(improve.contains("MODE : AMÉLIORATION ARCHITECTURALE") && improve.contains("AJOUTER les éléments du"));
        assertFalse(improve.contains("MODE : RÉPARATION"));
        assertTrue(repair.contains("MÉMOIRE X") && repair.contains("CONFORMITÉ Y"));
        assertTrue(improve.contains("ne réintroduis aucune erreur déjà résolue"));
    }

    @Test
    void theContractTellsTheModelThatAClearErasesWhatCameBeforeAndThatRoofsDoNotStack() {
        String system = BuildingPromptBuilder.systemPrompt();

        assertTrue(system.contains("un « clear » posé après une porte"), "ordre des « clear »");
        assertTrue(system.contains("UN SEUL « gable » par corps de bâtiment"), "pas de toits empilés");
    }
}
