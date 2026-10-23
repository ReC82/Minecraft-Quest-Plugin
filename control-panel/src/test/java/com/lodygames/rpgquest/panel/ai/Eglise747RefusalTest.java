package com.lodygames.rpgquest.panel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * #747, retour du propriétaire du 23/10/2026 — les quatre scénarios demandés, sans appel payant :
 * réponse sans plan, erreur précoce, blocs inconnus multiples, candidat régressif.
 */
class Eglise747RefusalTest {

    private static final String CHURCH_BRIEF = "Construire une petite église blanche. Murs en béton blanc, "
            + "encadrements en quartz, toiture en ardoise des abîmes. Une tour-clocher avec une cloche. "
            + "Vitraux en verre coloré bleu et jaune. Bancs en bois, tapis rouge, un pupitre, des "
            + "lanternes, quelques bougies et deux compositions florales. Ne pas générer le personnage.";

    // ---- 1. Réponse sans plan ------------------------------------------------------------------

    /** La réponse réelle du 23/10/2026 à 10:29:42 (Église du hub, V-14206aa8). */
    @Test
    void theChurchRefusalIsATypedFailureNotAVersion() {
        AiProvider.Exchange x = exchange("stop", "{\"choices\":[{\"message\":{\"content\":\"I'm sorry, I can't "
                + "assist with that.\",\"refusal\":null},\"finish_reason\":\"stop\"}]}");
        AiBuildingStudio.Proposal p = new AiBuildingStudio.Proposal("openai", "OpenAI", "gpt-4o-2024-08-06",
                "11239 entrée / 9 sortie", "I'm sorry, I can't assist with that.", null, "note", null, x, Map.of());
        AiCallFailure f = AiCallFailure.of(p).orElseThrow();
        assertEquals(AiCallFailure.Kind.REFUSAL, f.kind());
        assertTrue(f.sentence().startsWith("Refus du modèle"), f.sentence());
    }

    @Test
    void eachUnusableAnswerHasItsOwnKind() {
        assertEquals(AiCallFailure.Kind.EMPTY, kind("", "stop", null));
        assertEquals(AiCallFailure.Kind.INVALID_FORMAT, kind("Voici une église magnifique avec un clocher et des "
                + "vitraux, construite en béton blanc.", "stop", null));
        assertEquals(AiCallFailure.Kind.TRUNCATED, kind("id: x\nsteps:\n  - op: box", "length", "id: x\nsteps:\n  - op: box"));
        assertEquals(AiCallFailure.Kind.TRUNCATED, kind("id: x\nsteps:", "max_tokens", "id: x\nsteps:"));
        assertEquals(AiCallFailure.Kind.API_ERROR, AiCallFailure.of(new AiBuildingStudio.Proposal("openai", "OpenAI",
                null, null, null, null, null, "HTTP 500")).orElseThrow().kind());
        assertEquals(AiCallFailure.Kind.REFUSAL, kind("Je ne peux pas vous aider avec cela.", "stop", null));
        AiProvider.Exchange apiRefusal = exchange("stop", "{\"message\":{\"refusal\":\"policy\"}}");
        assertEquals(AiCallFailure.Kind.REFUSAL, AiCallFailure.of(new AiBuildingStudio.Proposal("openai", "OpenAI",
                "m", "u", "Une longue réponse sans plan qui ne ressemble pas à un refus.", null, null, null,
                apiRefusal, Map.of())).orElseThrow().kind());
        assertTrue(AiCallFailure.of(new AiBuildingStudio.Proposal("openai", "OpenAI", "m", "u", "id: x\nsteps: []",
                "id: x\nsteps: []", null, null, exchange("stop", "{}"), Map.of())).isEmpty(), "un plan n'est pas un échec");
    }

    @Test
    void theJournalKeepsTheFailureAndTheRetryComparesTheContext() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("previousPlan", Map.of("sha256", "aaa"));
        manifest.put("refusal", Map.of("sha256", "bbb"));
        manifest.put("memory", Map.of("sha256", "ccc"));
        manifest.put("images", List.of(Map.of("sha256", "i1"), Map.of("sha256", "i2")));
        manifest.put("mode", "IMPROVE");
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("kind", "REFUSAL");
        failure.put("label", "Refus du modèle");
        failure.put("detail", "« I'm sorry »");
        failure.put("activeVersionKept", "1e5df166-e980");
        failure.put("retry", Map.of("gesture", "correct", "mode", "auto", "notes", "Garder la tour", "provider", "openai"));
        List<AiJournalStore.Event> events = List.of(
                event(1, "REQUEST", "c1", Map.of("manifest", manifest)),
                event(2, "RESPONSE", "c1", Map.of("failure", failure)));
        FailedCalls.LastFailure last = FailedCalls.last(events).orElseThrow();
        assertEquals("1e5df166-e980", last.keptDraftId());
        assertEquals("Garder la tour", last.notes());
        assertEquals("correct", last.gesture());

        String same = FailedCalls.sameContext(events, "c1", manifest);
        assertTrue(same.contains("identiques : previousPlan, refusal, memory, images, mode"), same);
        assertFalse(same.contains("DIFFÉRENTES"), same);
        Map<String, Object> changed = new LinkedHashMap<>(manifest);
        changed.put("memory", Map.of("sha256", "zzz"));
        assertTrue(FailedCalls.sameContext(events, "c1", changed).contains("DIFFÉRENTES depuis l'échec : memory"));

        // Un appel réussi ensuite efface l'échec de la page.
        List<AiJournalStore.Event> later = new java.util.ArrayList<>(events);
        later.add(event(3, "RESPONSE", "c2", Map.of("planExtracted", true)));
        assertTrue(FailedCalls.last(later).isEmpty());
    }

    // ---- 2. Erreur précoce : les autres contrôles ne sont pas exécutés -------------------------

    @Test
    void anEarlyStopNeverResolvesAnythingAndIsNeverTheBestVersion() {
        AttemptMemory.Attempt built = new AttemptMemory.Attempt("aaaaaaaa1", 1, false,
                List.of("ENVELOPE_OPEN", "DARK_AREA", "ROOF_MISSING"), List.of(), 0, List.of());
        AttemptMemory.Attempt early = new AttemptMemory.Attempt("bbbbbbbb2", 2, false, List.of("PLAN_INVALID"),
                List.of(), 0, List.of("minecraft:lion_bed"));
        AttemptMemory.Attempt empty = new AttemptMemory.Attempt("cccccccc3", 3, false, List.of("EMPTY_STRUCTURE"),
                List.of(), 0, List.of());
        AttemptMemory memory = AttemptMemory.of(List.of(built, early, empty));

        assertTrue(memory.resolvedCodes().isEmpty(), "rien n'est résolu par une version non construite");
        assertEquals(List.of("ENVELOPE_OPEN", "DARK_AREA", "ROOF_MISSING"), memory.unverifiedFor("bbbbbbbb2"));
        assertEquals(List.of("ENVELOPE_OPEN", "DARK_AREA", "ROOF_MISSING"), memory.unverifiedFor("cccccccc3"));
        assertEquals("aaaaaaaa1", memory.bestRefused().orElseThrow().draftId(),
                "1 code d'arrêt précoce ne bat pas 3 codes d'une version construite");
        String block = memory.promptBlock("bbbbbbbb2");
        assertTrue(block.contains("NON CONSTRUITE"), block);
        assertTrue(block.contains("NON VÉRIFIÉ sur la version transmise"), block);
        assertFalse(block.contains("ERREURS DÉJÀ RÉSOLUES"), block);
    }

    @Test
    void theBestVersionWeighsMissingRequirementsNotOnlyCodes() {
        AttemptMemory.Attempt fewCodesButIncomplete = new AttemptMemory.Attempt("aaaaaaaa1", 1, false,
                List.of("DARK_AREA"), List.of(), 5, List.of());
        AttemptMemory.Attempt twoCodesComplete = new AttemptMemory.Attempt("bbbbbbbb2", 2, false,
                List.of("DARK_AREA", "ROOF_MISSING"), List.of(), 0, List.of());
        assertEquals("bbbbbbbb2", AttemptMemory.of(List.of(fewCodesButIncomplete, twoCodesComplete)).bestRefused()
                .orElseThrow().draftId());
    }

    @Test
    void aMissingRequirementInAnUnbuiltVersionIsUnverifiedNotRealized() {
        BriefRequirements.Report report = BriefRequirements.evaluate("Une chapelle avec une cloche.",
                "steps:\n  - op: box\n    block: minecraft:bell", Map.of());
        assertTrue(report.statuses().get(0).unverified());
        assertTrue(report.summary().contains("0/1") && report.summary().contains("1 non vérifiée"), report.summary());
        assertTrue(report.statuses().get(0).line().startsWith("◐"), report.statuses().get(0).line());
        BriefRequirements.Report measured = BriefRequirements.evaluate("Une chapelle avec une cloche.",
                "steps:\n  - op: box\n    block: minecraft:bell", Map.of("minecraft:bell", 1));
        assertTrue(measured.summary().startsWith("1/1"), measured.summary());
    }

    // ---- 3. Plusieurs blocs inconnus : diagnostic groupé, palette réelle --------------------

    @Test
    void everyInventedIdentifierIsReportedTogether() {
        BlockRegistry registry = BlockRegistry.vanilla();
        assertTrue(registry.available(), "le registre du serveur est dans le classpath du panel");
        String plan = "steps:\n  - op: window\n    block: minecraft:stained_glass\n"
                + "  - op: box\n    block: minecraft:potted_yellow_flower\n"
                + "  - op: bed\n    block: minecraft:lion_bed\n"
                + "  - op: box\n    block: minecraft:white_concrete\n"
                + "  - op: box\n    block: minecraft:lion_bed\n";
        Map<String, List<Integer>> unknown = registry.unknownIn(plan);
        assertEquals(List.of("minecraft:stained_glass", "minecraft:potted_yellow_flower", "minecraft:lion_bed"),
                List.copyOf(unknown.keySet()));
        assertEquals(List.of(7, 11), unknown.get("minecraft:lion_bed"));
        assertTrue(registry.known("minecraft:lime_bed") && registry.known("minecraft:bell")
                && registry.known("white_concrete") && registry.known("minecraft:oak_stairs[facing=north]"));
    }

    @Test
    void theGeneratorReceivesAPaletteOfRealIdentifiersForWhatTheBriefNames() {
        String palette = BriefPalette.block(CHURCH_BRIEF);
        assertTrue(palette.startsWith("PALETTE D'IDENTIFIANTS VÉRIFIÉS"), palette);
        assertTrue(palette.contains("Cloche : minecraft:bell"), palette);
        assertTrue(palette.contains("minecraft:<couleur>_stained_glass_pane"), palette);
        assertTrue(palette.contains("minecraft:lectern") && palette.contains("minecraft:candle"), palette);
        assertTrue(palette.contains("minecraft:white_concrete") || palette.contains("minecraft:<couleur>_concrete"),
                palette);
        assertTrue(palette.contains("deepslate_tiles"), palette);
        assertTrue(palette.contains("« lion_bed » n'est pas « lime_bed »"), palette);
        assertTrue(palette.contains("ne limite rien"), "aucune restriction de l'architecture");
        assertFalse(palette.contains("Lit(s)"), "le brief ne demande aucun lit");
        // Tous les identifiants énumérés existent.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("minecraft:[a-z0-9_]+").matcher(palette);
        while (m.find()) {
            assertTrue(BlockRegistry.vanilla().known(m.group()), m.group());
        }
        BuildingPromptBuilder.Demand demand = new BuildingPromptBuilder.Demand("p", "Église", "SOUTH",
                CHURCH_BRIEF, 19, 19, 23, "eglise");
        assertTrue(BuildingPromptBuilder.userPrompt(demand).contains(palette.strip()), "la palette part avec la demande");
    }

    @Test
    void inventedIdentifiersAreRememberedAcrossVersions() {
        AttemptMemory memory = AttemptMemory.of(List.of(
                new AttemptMemory.Attempt("aaaaaaaa1", 1, false, List.of("PLAN_INVALID"), List.of(), 0,
                        List.of("minecraft:stained_glass")),
                new AttemptMemory.Attempt("bbbbbbbb2", 2, false, List.of("PLAN_INVALID"), List.of(), 0,
                        List.of("minecraft:potted_yellow_flower", "minecraft:lion_bed"))));
        String block = memory.promptBlock("bbbbbbbb2");
        assertTrue(block.contains("minecraft:stained_glass, minecraft:potted_yellow_flower, minecraft:lion_bed"), block);
        assertTrue(block.contains("ne les remplace pas par un nom voisin"), block);
        assertTrue(memory.lessonsBlock().contains("Identifiants INEXISTANTS"), memory.lessonsBlock());
    }

    // ---- 4. Candidat régressif ----------------------------------------------------------------

    @Test
    void aCandidateThatDropsRequirementsOrReintroducesAnErrorIsAVisibleRegression() {
        AttemptMemory.Attempt first = new AttemptMemory.Attempt("aaaaaaaa1", 1, false,
                List.of("UNSUPPORTED_BLOCK", "DARK_AREA"), List.of(), 1, List.of());
        AttemptMemory.Attempt fixed = new AttemptMemory.Attempt("bbbbbbbb2", 2, false, List.of("DARK_AREA"),
                List.of(), 0, List.of());
        AttemptMemory.Attempt regressive = new AttemptMemory.Attempt("cccccccc3", 3, false,
                List.of("UNSUPPORTED_BLOCK", "DARK_AREA"), List.of(), 2, List.of());
        AttemptMemory memory = AttemptMemory.of(List.of(first, fixed, regressive));
        assertEquals(1, memory.regressionsOf("cccccccc3").size());
        assertEquals("UNSUPPORTED_BLOCK", memory.regressionsOf("cccccccc3").get(0).code());
        assertEquals("bbbbbbbb2", memory.bestRefused().orElseThrow().draftId(), "le candidat régressif n'est pas promu");
        assertTrue(memory.promptBlock("cccccccc3").contains("RÉGRESSION"));
        assertTrue(memory.promptBlock("cccccccc3").contains("2 exigence(s) du brief manquante(s)"));

        // Conformité : la bougie présente avant, absente après.
        BriefRequirements.Report before = BriefRequirements.evaluate("Des bougies et une cloche.",
                "block: minecraft:candle\nblock: minecraft:bell", Map.of("minecraft:candle", 2, "minecraft:bell", 1));
        BriefRequirements.Report after = BriefRequirements.evaluate("Des bougies et une cloche.",
                "block: minecraft:bell", Map.of("minecraft:bell", 1));
        assertTrue(before.complete());
        assertEquals("Bougies", after.missing().get(0).requirement().label());
    }

    @Test
    void stagnationOffersLessonsForANewDesign() {
        List<AttemptMemory.Attempt> attempts = new java.util.ArrayList<>();
        attempts.add(new AttemptMemory.Attempt("best0000", 1, false, List.of("ROOF_MISSING"), List.of(), 0, List.of()));
        for (int i = 0; i < 4; i++) {
            attempts.add(new AttemptMemory.Attempt("worse00" + i, 2 + i, false, List.of("ROOF_MISSING", "DARK_AREA"),
                    List.of(), 0, List.of()));
        }
        AttemptMemory memory = AttemptMemory.of(attempts);
        assertTrue(memory.stagnating());
        assertEquals("best0000", memory.bestRefused().orElseThrow().draftId());
        String lessons = memory.lessonsBlock();
        assertTrue(lessons.contains("ne recopie aucun ancien plan"), lessons);
        assertTrue(lessons.contains("ROOF_MISSING") && lessons.contains("DARK_AREA"), lessons);
        BuildingPromptBuilder.Demand demand = new BuildingPromptBuilder.Demand("p", "Église", "SOUTH",
                CHURCH_BRIEF, 19, 19, 23, "eglise");
        String prompt = BuildingPromptBuilder.userPrompt(demand, lessons);
        assertTrue(prompt.indexOf("ENSEIGNEMENTS") < prompt.indexOf("Produis maintenant le plan YAML"), prompt);
    }

    // ---- outils ----------------------------------------------------------------------------------

    private static AiCallFailure.Kind kind(String text, String finish, String plan) {
        return AiCallFailure.of(new AiBuildingStudio.Proposal("openai", "OpenAI", "m", "u", text, plan, null, null,
                exchange(finish, "{}"), Map.of())).map(AiCallFailure::kind).orElse(null);
    }

    private static AiProvider.Exchange exchange(String finish, String skeleton) {
        return new AiProvider.Exchange("POST x", Map.of(), "{}", List.of(), 200, skeleton, List.of(), "id", finish,
                List.of(), Instant.now(), Instant.now(), "");
    }

    private static AiJournalStore.Event event(long seq, String kind, String callId, Map<String, Object> body) {
        return new AiJournalStore.Event(seq, "building", "p", seq, kind, callId, "", "MANUEL", 0, "", "",
                Instant.now(), -1, "lody", "", body);
    }
}
