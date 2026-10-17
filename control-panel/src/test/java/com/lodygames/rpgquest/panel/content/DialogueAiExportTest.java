package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #465 — exporter un dialogue EXISTANT pour une IA, puis le réimporter par l'import #109 :
 * même identifiant, source inchangée depuis l'export, références réelles, changements lisibles.
 *
 * <p>Le cas réel est le Brigand des bois ; il est copié dans un espace de travail jetable sous
 * l'identifiant {@code zz465_bandit} — le dialogue du dépôt n'est jamais touché.</p>
 */
class DialogueAiExportTest {

    private static final String SPIDERS = "rpgquest:encounter_bandit_spiders";
    private static final String WOLVES = "rpgquest:encounter_bandit_wolves";
    private static final String SLUG = "zz465_bandit";

    @TempDir
    Path tmp;

    private ContentWorkspace workspace;
    private final RefData ref = new RefData(List.of(SPIDERS, WOLVES), List.of(), List.of(), true, false, false);
    private final Set<String> catalogs = Set.of("brigands");

    @BeforeEach
    void setUp() throws Exception {
        Path root = tmp.resolve("content");
        for (String kind : ContentWorkspace.KINDS) {
            Files.createDirectories(root.resolve(kind));
        }
        workspace = new ContentWorkspace(root, tmp.resolve("backups"));
        Path real = Path.of("..", "src", "main", "resources", "dialogues", "encounter_bandit.yml");
        Assumptions.assumeTrue(Files.isRegularFile(real), "dialogue du brigand introuvable");
        String copy = Files.readString(real).replace("id: rpgquest:encounter_bandit\n", "id: rpgquest:" + SLUG + "\n");
        Files.writeString(root.resolve("dialogues/" + SLUG + ".yml"), copy);
    }

    private ContentWorkspace.ContentFile file() {
        return workspace.read("dialogues", SLUG).orElseThrow();
    }

    private DialogueAiExport.Result export() {
        var ctx = new DialogueAiExport.Context(
                List.of(new DialogueAiExport.CatalogInfo("brigands", "Quêtes des brigands des bois", true,
                        List.of(SPIDERS, WOLVES))),
                List.of(), q -> q.endsWith("spiders") ? "Les toiles du sous-bois" : "Les loups",
                List.of("Brigand des bois"));
        DialogueAiExport.Result r = DialogueAiExport.export(file(), ctx);
        assertTrue(r.ok(), r.problem());
        return r;
    }

    private ContentPackImport.Analysis reimport(String pack, ContentPackImport.Decision decision) {
        Map<String, ContentPackImport.Decision> d = decision == null ? Map.of()
                : Map.of("dialogues/" + SLUG, decision);
        return ContentPackImport.analyze(pack, workspace, ref, d, catalogs);
    }

    /** Lignes d'un choix telles qu'elles apparaissent dans le pack (choix d'un nœud : 14 espaces). */
    private static String block(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(" ".repeat(14)).append(l).append('\n');
        }
        return sb.toString();
    }

    private static ContentPackImport.Element only(ContentPackImport.Analysis a) {
        assertEquals(1, a.elements().size(), () -> "éléments: " + a.elements());
        return a.elements().get(0);
    }

    @Test
    void theExportCarriesTheDialogueItsDependenciesAndThePromptContext() {
        DialogueAiExport.Result r = export();

        assertTrue(r.pack().contains("baseElement: dialogues/" + SLUG), r.pack());
        assertTrue(r.pack().contains(file().sha256()), "empreinte de la source à l'export");
        assertEquals(List.of(SPIDERS, WOLVES), r.quests(), "quête citée + quêtes de la liste du PNJ");
        assertTrue(r.prompt().contains("ÉCRIS ICI"), "la demande reste à écrire par l'administrateur");
        assertTrue(r.prompt().contains("`brigands` « Quêtes des brigands des bois »"), r.prompt());
        assertTrue(r.prompt().contains(WOLVES + " (Les loups)"));
        assertTrue(r.prompt().contains("- OFFER_CATALOG_QUEST [catalog (facultatif)]"), "vocabulaire réel du moteur");
        assertTrue(r.prompt().contains("- QUEST_AVAILABLE [quest]"));
        assertTrue(r.prompt().endsWith("```\n") && r.prompt().contains(r.pack()), "le pack est dans le prompt");
    }

    /** Rendu tel quel par l'IA : aucune différence de sens — seules les notes « # » disparaissent. */
    @Test
    void anUntouchedPackHasNoSemanticChange() {
        var e = only(reimport(export().pack(), null));

        assertEquals(ContentPackImport.Status.CONFLICT, e.status(), "remplacement jamais implicite");
        var before = DialogueYaml.read(file().text()).draft();
        var after = DialogueYaml.read(e.yaml()).draft();
        assertTrue(DialogueSemanticDiff.compare(before, after).identical());
        assertTrue(e.diff().stream().anyMatch(l -> l.kind() == '-' && l.text().startsWith("#")),
                "les notes perdues sont visibles dans le diff de lignes");
    }

    /**
     * Le cas du ticket : passer de la quête fixe des araignées à la liste « brigands » — quatre
     * conditions retirées, l'offre remplacée par OFFER_CATALOG_QUEST, trois textes réécrits — en un
     * seul réimport, relu puis confirmé.
     */
    @Test
    void theBanditSwitchToACatalogIsReimportedAsOneReviewedReplacement() {
        String pack = export().pack()
                .replace(block("conditions:",
                              "  - type: QUEST_AVAILABLE",
                              "    quest: rpgquest:encounter_bandit_spiders",
                              "next: offer"),
                        block("actions:",
                              "  - type: OFFER_CATALOG_QUEST",
                              "    catalog: brigands"))
                .replace(block("conditions:",
                              "  - type: QUEST_STATE",
                              "    quest: rpgquest:encounter_bandit_spiders",
                              "    state: ACTIVE",
                              "next: in_progress"),
                        block("next: in_progress"))
                .replace(block("conditions:",
                              "  - type: QUEST_STATE",
                              "    quest: rpgquest:encounter_bandit_spiders",
                              "    state: COMPLETED",
                              "  - type: QUEST_AVAILABLE",
                              "    quest: rpgquest:encounter_bandit_spiders",
                              "    negate: true",
                              "next: resting"),
                        block("next: resting"))
                .replace("Les araignées ne vont pas se chasser toutes seules.", "Ta tâche t'attend.")
                .replace("Six suffiront ; reviens", "Reviens")
                .replace("Tu m'as déjà bien aidé.", "Merci pour ton aide.");
        assertFalse(pack.contains("state: ACTIVE"), "la transformation de test doit s'appliquer");

        var pending = only(reimport(pack, null));
        assertEquals(ContentPackImport.Status.CONFLICT, pending.status(), pending.diagnostics().toString());
        var diff = DialogueSemanticDiff.compare(DialogueYaml.read(file().text()).draft(),
                DialogueYaml.read(pending.yaml()).draft());
        assertEquals(4, diff.changes().stream().filter(c -> c.what().equals("Condition retirée")).count(),
                diff.changes().toString());
        assertTrue(diff.changes().stream().anyMatch(c -> c.what().equals("Effet ajouté")
                && c.after().equals("OFFER_CATALOG_QUEST catalog=brigands")));
        assertEquals(2, diff.changes().stream().filter(c -> c.what().equals("Texte")).count(),
                "in_progress et resting");

        var a = reimport(pack, ContentPackImport.Decision.REPLACE);
        assertTrue(a.importable(), a.toString());
        var applied = ContentPackImport.apply(a, workspace);
        assertTrue(applied.get(0).result().ok(), applied.toString());
        assertTrue(file().text().contains("catalog: brigands"));
        assertEquals(ContentPackImport.Status.UNCHANGED, only(reimport(pack, null)).status(),
                "relu après écriture : à jour, pas « source modifiée »");
    }

    @Test
    void aSourceChangedSinceTheExportIsRefusedInsteadOfOverwritten() throws Exception {
        String pack = export().pack().replace("Qui es-tu ?", "Tu es qui ?");
        Files.writeString(workspace.dirPath("dialogues").orElseThrow().resolve(SLUG + ".yml"),
                file().text().replace("Au revoir", "Salut"));

        var e = only(reimport(pack, ContentPackImport.Decision.REPLACE));

        assertEquals(ContentPackImport.Status.INVALID, e.status());
        assertTrue(e.reason().contains("a changé depuis l'export"), e.reason());
    }

    @Test
    void aChangedIdentifierIsRefusedAsAParallelDialogue() {
        String pack = export().pack().replace("id: rpgquest:" + SLUG, "id: rpgquest:zz465_bandit_v2");

        var a = reimport(pack, null);

        assertFalse(a.importable());
        assertTrue(a.envelope().stream().anyMatch(d -> d.message().contains("contenu parallèle")), a.envelope().toString());
    }

    @Test
    void anInventedQuestOrListIsAnErrorBeforeAnySave() {
        String pack = export().pack()
                .replace("quest: rpgquest:encounter_bandit_spiders\n" + " ".repeat(14) + "next: accepted",
                        "quest: rpgquest:invented_quest\n" + " ".repeat(14) + "next: accepted")
                .replace(block("conditions:",
                              "  - type: QUEST_AVAILABLE",
                              "    quest: rpgquest:encounter_bandit_spiders",
                              "next: offer"),
                        block("actions:",
                              "  - type: OFFER_CATALOG_QUEST",
                              "    catalog: pirates"));

        var e = only(reimport(pack, ContentPackImport.Decision.REPLACE));

        assertEquals(ContentPackImport.Status.INVALID, e.status());
        assertTrue(e.diagnostics().stream().anyMatch(d -> d.message().contains("invented_quest")), e.diagnostics().toString());
        assertTrue(e.diagnostics().stream().anyMatch(d -> d.message().contains("« pirates »")));
    }

    @Test
    void withoutSurveyReferencesAreSaidUnverifiedNotValid() {
        var draft = DialogueYaml.read(file().text()).draft();

        var out = DialogueReferenceCheck.check(draft, RefData.empty(), null);

        assertFalse(Diagnostic.hasError(out));
        assertTrue(out.stream().anyMatch(d -> d.level() == Diagnostic.Level.INFO && d.message().contains("quêtes")));
    }

    @Test
    void aDialogueTheEditorCannotReadIsNotExported() throws Exception {
        Files.writeString(workspace.dirPath("dialogues").orElseThrow().resolve(SLUG + ".yml"),
                "id: rpgquest:" + SLUG + "\nstart: a\nnodes: [a, b]\n");

        var r = DialogueAiExport.export(file(), new DialogueAiExport.Context(List.of(), List.of(), q -> q, List.of()));

        assertFalse(r.ok());
        assertTrue(r.problem().contains("sans rien perdre"));
    }
}
