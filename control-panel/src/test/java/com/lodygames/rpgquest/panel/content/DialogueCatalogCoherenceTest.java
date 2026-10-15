package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Issue #430 — ce qui reste lié à une quête précise dans un dialogue qui propose (ou devrait
 * proposer) une quête tirée d'une liste. Signalé, jamais réécrit.
 */
class DialogueCatalogCoherenceTest {

    private static final Map<String, List<String>> CATALOGS = Map.of(
            "brigands", List.of("rpgquest:encounter_bandit_spiders", "rpgquest:encounter_bandit_wolves"));

    private static String title(String q) {
        return switch (DialogueYaml.plainId(q)) {
            case "encounter_bandit_spiders" -> "Les toiles du sous-bois";
            case "encounter_bandit_wolves" -> "Les loups";
            default -> q;
        };
    }

    private static DialogueDraft bandit() throws Exception {
        Path p = Path.of("..", "src", "main", "resources", "dialogues", "encounter_bandit.yml");
        Assumptions.assumeTrue(Files.isRegularFile(p), "dialogue du brigand introuvable");
        DialogueYaml.ReadResult r = DialogueYaml.read(Files.readString(p));
        assertTrue(r.ok(), r.problems().toString());
        return r.draft();
    }

    @Test
    void theBanditDialogueListsEveryPlaceTiedToTheSpiderQuestBeforeSwitching() throws Exception {
        DialogueDraft d = bandit();
        String before = DialogueYaml.write(d);
        var report = DialogueCatalogCoherence.analyse(d, List.of("brigands"), CATALOGS,
                DialogueCatalogCoherenceTest::title);
        assertFalse(report.offersCatalog());
        // 3 réponses de l'accueil (4 conditions), le START_QUEST de l'offre, le titre dans le texte.
        assertEquals(6, report.findings().size(), report.findings().toString());
        assertTrue(report.findings().stream().anyMatch(f -> f.nodeId().equals("offer")
                && f.choice() == DialogueCatalogCoherence.TEXT));
        assertTrue(report.findings().stream().anyMatch(f -> f.nodeId().equals("offer") && f.choice() == 0
                && f.what().contains("Démarrer une quête")));
        assertEquals(before, DialogueYaml.write(d), "rien n'est modifié");
    }

    @Test
    void afterSwitchingTheRemainingFixedReferencesAreStillReported() throws Exception {
        DialogueDraft d = bandit();
        var offer = d.nodes.stream().filter(n -> n.id.equals("offer")).findFirst().orElseThrow();
        offer.choices.get(0).actions.clear();
        offer.choices.get(0).actions.add(new java.util.LinkedHashMap<>(Map.of("type", "OFFER_CATALOG_QUEST")));
        var report = DialogueCatalogCoherence.analyse(d, List.of("brigands"), CATALOGS,
                DialogueCatalogCoherenceTest::title);
        assertTrue(report.offersCatalog());
        assertEquals(5, report.findings().size(), "les conditions et le texte restent à revoir");
    }

    @Test
    void noListMeansNothingToSay() throws Exception {
        var report = DialogueCatalogCoherence.analyse(bandit(), List.of(), CATALOGS,
                DialogueCatalogCoherenceTest::title);
        assertTrue(report.empty());
    }

    @Test
    void aNamedCatalogAndWelcomeEntriesAreChecked() {
        DialogueDraft d = new DialogueDraft();
        d.id = "x";
        d.start = "a";
        DialogueDraft.Node a = new DialogueDraft.Node("a");
        a.text = "Bonjour";
        a.variants.add("Encore toi ? Les loups rôdent.");
        DialogueDraft.Choice c = new DialogueDraft.Choice();
        c.text = "Travail";
        c.actions.add(new java.util.LinkedHashMap<>(Map.of("type", "OFFER_CATALOG_QUEST", "catalog", "brigands")));
        a.choices.add(c);
        d.nodes.add(a);
        DialogueDraft.Entry e = new DialogueDraft.Entry();
        e.node = "a";
        e.conditions.add(new java.util.LinkedHashMap<>(Map.of("type", "QUEST_STATE",
                "quest", "rpgquest:encounter_bandit_wolves", "state", "COMPLETED")));
        d.entries.add(e);
        var report = DialogueCatalogCoherence.analyse(d, List.of(), CATALOGS, DialogueCatalogCoherenceTest::title);
        assertEquals(2, report.findings().size(), report.findings().toString());
        assertTrue(report.findings().stream().anyMatch(f -> f.choice() == DialogueCatalogCoherence.ENTRY));
        assertTrue(report.findings().stream().anyMatch(f -> f.choice() == DialogueCatalogCoherence.TEXT));
    }
}
