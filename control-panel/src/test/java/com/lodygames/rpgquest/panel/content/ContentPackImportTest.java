package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #109 — import d'un content pack.
 *
 * <p>Ces tests suivent les critères d'acceptation du ticket, et surtout les deux qui comptent :
 * <strong>aucun contenu actif n'est écrasé à l'upload</strong> et <strong>une collision exige une
 * décision explicite</strong>. Plusieurs cas sont donc écrits « à l'envers » : ils vérifient que
 * rien n'a été écrit, pas qu'une écriture a réussi.</p>
 */
class ContentPackImportTest {

    @TempDir
    Path tmp;

    private ContentWorkspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        Path root = tmp.resolve("content");
        for (String kind : ContentWorkspace.KINDS) {
            Files.createDirectories(root.resolve(kind));
        }
        workspace = new ContentWorkspace(root, tmp.resolve("backups"));
    }

    private static String pack(String content) {
        return "format: " + ContentPackSchema.FORMAT + "\n"
                + "schemaVersion: " + ContentPackSchema.SCHEMA_VERSION + "\n"
                + "content:\n" + content;
    }

    private static final String ONE_QUEST = """
              quests:
                - id: rpgquest:mines
                  title: "Les mines"
                  description: "Descendre."
                  category: test
                  icon: LANTERN
                  steps:
                    - id: creuser
                      objectives:
                        - type: BREAK_BLOCK
                          material: STONE
                          amount: 10
                  rewards:
                    - type: EXPERIENCE
                      amount: 25
            """;

    private ContentPackImport.Analysis analyze(String yaml) {
        return ContentPackImport.analyze(yaml, workspace, RefData.empty(), Map.of());
    }

    private ContentPackImport.Analysis analyze(String yaml, Map<String, ContentPackImport.Decision> d) {
        return ContentPackImport.analyze(yaml, workspace, RefData.empty(), d);
    }

    private ContentPackImport.Element only(ContentPackImport.Analysis a) {
        assertEquals(1, a.elements().size(), () -> "éléments: " + a.elements());
        return a.elements().get(0);
    }

    // ---- Enveloppe -----------------------------------------------------------------------------

    @Test
    void aPackExportedByTheExporterIsAnalysedAsNewContent() {
        ContentPackImport.Analysis a = analyze(pack(ONE_QUEST));

        assertTrue(a.readable());
        assertEquals(ContentPackImport.Status.NEW, only(a).status());
        assertEquals("quests/mines", only(a).key());
        assertTrue(a.importable());
    }

    @Test
    void anUnknownFormatIsRefusedWithoutLookingAtTheContent() {
        ContentPackImport.Analysis a = analyze("format: autre-chose\nschemaVersion: 1\ncontent: {}\n");

        assertFalse(a.readable());
        assertFalse(a.importable());
        assertTrue(a.envelope().get(0).message().contains("Format inconnu"), a.envelope().toString());
        assertTrue(a.elements().isEmpty(), "rien n'est analysé si l'enveloppe est refusée");
    }

    @Test
    void aMissingFormatSaysItIsNotALodyQuestsPack() {
        ContentPackImport.Analysis a = analyze("schemaVersion: 1\ncontent: {}\n");

        assertTrue(a.envelope().get(0).message().contains("n'est pas un content pack"), a.envelope().toString());
    }

    /** Une version plus récente est refusée explicitement, jamais interprétée approximativement. */
    @Test
    void aNewerSchemaVersionIsRefusedExplicitly() {
        ContentPackImport.Analysis a = analyze("format: " + ContentPackSchema.FORMAT
                + "\nschemaVersion: 99\ncontent: {}\n");

        assertFalse(a.importable());
        String message = a.envelope().get(0).message();
        assertTrue(message.contains("plus récente"), message);
        assertTrue(message.contains(String.valueOf(ContentPackSchema.SCHEMA_VERSION)),
                "le refus doit nommer la version attendue");
    }

    /** Une version antérieure est refusée en nommant la version attendue : aucun migrateur n'existe. */
    @Test
    void anOlderSchemaVersionIsRefusedAndNamesTheExpectedVersion() {
        ContentPackImport.Analysis a = analyze("format: " + ContentPackSchema.FORMAT
                + "\nschemaVersion: 0\ncontent: {}\n");

        String message = a.envelope().get(0).message();
        assertTrue(message.contains("antérieure"), message);
        assertTrue(message.contains("migrateur"), message);
    }

    @Test
    void unreadableYamlIsRefusedWithoutThrowing() {
        ContentPackImport.Analysis a = analyze("ceci n'est pas: [un: pack\n");

        assertFalse(a.readable());
        assertFalse(a.importable());
    }

    @Test
    void anOversizedPackIsRefusedBeforeAnalysis() {
        String huge = pack(ONE_QUEST) + "# " + "x".repeat(ContentPackImport.MAX_BYTES);

        ContentPackImport.Analysis a = analyze(huge);

        assertFalse(a.readable());
        assertTrue(a.envelope().get(0).message().contains("trop volumineux"), a.envelope().toString());
    }

    /** Une famille inconnue est signalée et ignorée — jamais écrite, jamais passée sous silence. */
    @Test
    void anUnknownFamilyIsReportedAndIgnored() {
        ContentPackImport.Analysis a = analyze(pack(ONE_QUEST + "  recettes:\n    - id: pain\n"));

        assertTrue(a.envelope().stream().anyMatch(d -> d.message().contains("Famille inconnue")),
                a.envelope().toString());
        assertEquals(1, a.elements().size(), "seule la quête est analysée");
    }

    // ---- Aucun écrasement ----------------------------------------------------------------------

    /**
     * Le critère le plus important du ticket : analyser n'écrit rien. Le dossier reste vide après
     * une analyse complète et importable.
     */
    @Test
    void analysingNeverWritesAnything() throws Exception {
        ContentPackImport.Analysis a = analyze(pack(ONE_QUEST));

        assertTrue(a.importable());
        try (var files = Files.list(tmp.resolve("content/quests"))) {
            assertEquals(0, files.count(), "l'analyse ne doit créer aucun fichier");
        }
    }

    /** Un identifiant déjà présent et différent devient un CONFLIT, pas un remplacement. */
    @Test
    void anExistingIdBecomesAConflictThatBlocksTheImport() {
        write("quests", "mines", "id: rpgquest:mines\ntitle: \"Ancien titre\"\n");

        ContentPackImport.Analysis a = analyze(pack(ONE_QUEST));

        assertEquals(ContentPackImport.Status.CONFLICT, only(a).status());
        assertEquals(1, a.conflicts());
        assertFalse(a.importable(), "une collision non tranchée bloque l'import");
        assertTrue(only(a).reason().contains("existe déjà"), only(a).reason());
    }

    /** Et `apply` refuse de travailler : la confirmation ne peut pas contourner l'arbitrage. */
    @Test
    void applyRefusesAnAnalysisWithAPendingConflict() {
        write("quests", "mines", "id: rpgquest:mines\ntitle: \"Ancien titre\"\n");
        ContentPackImport.Analysis a = analyze(pack(ONE_QUEST));

        assertThrows(IllegalStateException.class, () -> ContentPackImport.apply(a, workspace));
    }

    @Test
    void chosingToSkipKeepsTheExistingContentUntouched() throws Exception {
        write("quests", "mines", "id: rpgquest:mines\ntitle: \"Ancien titre\"\n");
        ContentPackImport.Analysis a = analyze(pack(ONE_QUEST),
                Map.of("quests/mines", ContentPackImport.Decision.SKIP));

        assertEquals(ContentPackImport.Status.SKIPPED, only(a).status());
        assertEquals(0, a.writing());
        assertFalse(a.importable(), "un import qui n'écrit rien n'a pas à être confirmé");
        assertTrue(read("quests", "mines").contains("Ancien titre"), "contenu existant intact");
    }

    @Test
    void chosingToReplaceWritesTheCanonicalFormOnConfirmation() throws Exception {
        write("quests", "mines", "id: rpgquest:mines\ntitle: \"Ancien titre\"\n");
        ContentPackImport.Analysis a = analyze(pack(ONE_QUEST),
                Map.of("quests/mines", ContentPackImport.Decision.REPLACE));

        assertEquals(ContentPackImport.Status.MODIFIED, only(a).status());
        assertTrue(a.importable());

        List<ContentPackImport.WriteOutcome> out = ContentPackImport.apply(a, workspace);

        assertEquals(1, out.size());
        assertTrue(out.get(0).result().ok(), () -> String.valueOf(out.get(0).result().message()));
        String saved = read("quests", "mines");
        assertTrue(saved.contains("Les mines"), saved);
        assertFalse(saved.contains("Ancien titre"));
    }

    /**
     * Verrou optimiste : si le fichier change entre l'analyse et la confirmation, le remplacement
     * est refusé au lieu d'écraser le travail de quelqu'un d'autre.
     */
    @Test
    void aFileChangedBetweenAnalysisAndConfirmationIsRefusedNotOverwritten() throws Exception {
        write("quests", "mines", "id: rpgquest:mines\ntitle: \"Ancien titre\"\n");
        ContentPackImport.Analysis a = analyze(pack(ONE_QUEST),
                Map.of("quests/mines", ContentPackImport.Decision.REPLACE));

        // Quelqu'un d'autre enregistre entre-temps — en passant l'empreinte courante, comme le fait
        // l'éditeur. Le workspace refuse une réécriture sans empreinte, c'est voulu.
        rewrite("quests", "mines", "id: rpgquest:mines\ntitle: \"Modifié entre-temps\"\n");

        List<ContentPackImport.WriteOutcome> out = ContentPackImport.apply(a, workspace);

        assertFalse(out.get(0).result().ok());
        assertEquals("CONFLICT", out.get(0).result().code());
        assertTrue(read("quests", "mines").contains("Modifié entre-temps"), "le travail d'autrui est intact");
    }

    /** Un contenu déjà identique n'est pas réécrit : UNCHANGED, et rien à confirmer. */
    @Test
    void identicalContentIsReportedUnchangedAndNotRewritten() {
        ContentPackImport.Analysis first = analyze(pack(ONE_QUEST));
        ContentPackImport.apply(first, workspace);

        ContentPackImport.Analysis second = analyze(pack(ONE_QUEST));

        assertEquals(ContentPackImport.Status.UNCHANGED, only(second).status());
        assertEquals(0, second.writing());
        assertFalse(second.importable());
    }

    // ---- Validation ----------------------------------------------------------------------------

    @Test
    void anUnknownObjectiveTypeIsAnErrorBeforeAnySave() {
        ContentPackImport.Analysis a = analyze(pack("""
                  quests:
                    - id: rpgquest:bancale
                      title: "Bancale"
                      description: "x"
                      category: test
                      steps:
                        - id: etape
                          objectives:
                            - type: UTILISER_UN_OBJET
                              material: STONE
                              amount: 1
                """));

        assertEquals(ContentPackImport.Status.INVALID, only(a).status());
        assertFalse(a.importable());
        assertTrue(only(a).diagnostics().stream().anyMatch(d -> d.message().contains("UTILISER_UN_OBJET")),
                only(a).diagnostics().toString());
    }

    @Test
    void aMissingMandatoryFieldIsAnError() {
        ContentPackImport.Analysis a = analyze(pack("""
                  quests:
                    - id: rpgquest:sans_quantite
                      title: "Sans quantité"
                      description: "x"
                      category: test
                      steps:
                        - id: etape
                          objectives:
                            - type: KILL_ENTITY
                              entity: ZOMBIE
                """));

        assertEquals(ContentPackImport.Status.INVALID, only(a).status());
    }

    @Test
    void anElementWithoutAnIdIsRejected() {
        ContentPackImport.Analysis a = analyze(pack("  quests:\n    - title: \"Sans id\"\n"));

        assertEquals(ContentPackImport.Status.INVALID, only(a).status());
        assertTrue(only(a).reason().contains("id"), only(a).reason());
    }

    /** Un identifiant qui ne peut pas devenir un nom de fichier est refusé, pas assaini en douce. */
    @Test
    void anIdThatCannotBecomeAFileNameIsRejected() {
        ContentPackImport.Analysis a = analyze(pack("  quests:\n    - id: \"rpgquest:../../etc/passwd\"\n"));

        ContentPackImport.Element e = only(a);
        assertEquals(ContentPackImport.Status.INVALID, e.status());
        assertEquals("", e.slug(), "aucun slug n'est dérivé d'un identifiant inutilisable");
        assertTrue(e.reason().contains("inutilisable"), e.reason());
    }

    @Test
    void aDuplicateIdInsideThePackIsRejected() {
        ContentPackImport.Analysis a = analyze(pack(ONE_QUEST + ONE_QUEST.replace("  quests:\n", "")));

        assertEquals(2, a.elements().size());
        assertTrue(a.elements().stream().anyMatch(e -> e.status() == ContentPackImport.Status.INVALID
                && e.reason().contains("Doublon")), a.elements().toString());
        assertFalse(a.importable());
    }

    // ---- Références internes au pack -----------------------------------------------------------

    /**
     * Critère explicite du ticket : une story qui référence une quête du même pack ne doit pas être
     * signalée comme référence inconnue — au moment de la validation, cette quête n'existe pourtant
     * nulle part encore.
     */
    @Test
    void aStoryReferencingAQuestOfTheSamePackResolvesCorrectly() {
        ContentPackImport.Analysis a = analyze(pack(ONE_QUEST + """
                  stories:
                    - id: campagne
                      name: "Campagne"
                      quests:
                        - rpgquest:mines
                """));

        ContentPackImport.Element story = a.elements().stream()
                .filter(e -> "stories".equals(e.family())).findFirst().orElseThrow();
        assertEquals(ContentPackImport.Status.NEW, story.status());
        assertFalse(story.diagnostics().stream().anyMatch(d -> d.message().contains("inconnue")),
                story.diagnostics().toString());
        assertTrue(a.importable());
    }

    /** L'orthographe héritée {@code questIds} des packs exportés jusqu'au 2026-10-08 reste acceptée. */
    @Test
    void theLegacyQuestIdsSpellingIsStillAccepted() {
        ContentPackImport.Analysis a = analyze(pack(ONE_QUEST + """
                  stories:
                    - id: campagne
                      name: "Campagne"
                      questIds:
                        - rpgquest:mines
                """));

        ContentPackImport.Element story = a.elements().stream()
                .filter(e -> "stories".equals(e.family())).findFirst().orElseThrow();
        assertEquals(ContentPackImport.Status.NEW, story.status());
        assertTrue(story.yaml().contains("quests:"), "ré-émis avec la clé officielle : " + story.yaml());
        assertTrue(story.yaml().contains("rpgquest:mines"));
    }

    /** Les deux orthographes ensemble : impossible de savoir laquelle fait foi, donc refus. */
    @Test
    void bothStorySpellingsAtOnceIsAnError() {
        ContentPackImport.Analysis a = analyze(pack("""
                  stories:
                    - id: campagne
                      name: "Campagne"
                      quests: [rpgquest:a]
                      questIds: [rpgquest:b]
                """));

        assertEquals(ContentPackImport.Status.INVALID, only(a).status());
    }

    // ---- Familles non écrivables ---------------------------------------------------------------

    /** Les PNJ font partie du format mais ne sont pas éditables : laissés de côté AVEC leur motif. */
    @Test
    void npcsAreSkippedWithAnExplicitReasonRatherThanSilentlyDropped() {
        ContentPackImport.Analysis a = analyze(pack(ONE_QUEST + """
                  npcs:
                    - id: mineur
                      displayName: "Mineur"
                """));

        ContentPackImport.Element npc = a.elements().stream()
                .filter(e -> "npcs".equals(e.family())).findFirst().orElseThrow();
        assertEquals(ContentPackImport.Status.SKIPPED, npc.status());
        assertTrue(npc.reason().contains("pas éditable"), npc.reason());
        assertTrue(a.importable(), "la quête reste importable");
    }

    // ---- Diff ----------------------------------------------------------------------------------

    @Test
    void aModifiedElementCarriesALineDiff() {
        write("quests", "mines", "id: rpgquest:mines\ntitle: \"Ancien titre\"\n");

        ContentPackImport.Analysis a = analyze(pack(ONE_QUEST),
                Map.of("quests/mines", ContentPackImport.Decision.REPLACE));

        List<TextDiff.Line> diff = only(a).diff();
        assertFalse(diff.isEmpty(), "un élément modifié doit porter son diff");
        assertTrue(diff.stream().anyMatch(l -> l.kind() == '-' && l.text().contains("Ancien titre")));
        assertTrue(diff.stream().anyMatch(l -> l.kind() == '+' && l.text().contains("Les mines")));
    }

    @Test
    void aNewElementHasNoDiffToShow() {
        assertTrue(only(analyze(pack(ONE_QUEST))).diff().isEmpty());
    }

    // ---- Dépendances ---------------------------------------------------------------------------

    @Test
    void dependenciesAreClassifiedInPackOnServerOrMissing() {
        write("quests", "ailleurs", "id: rpgquest:ailleurs\ntitle: \"Ailleurs\"\n");
        RefData ref = new RefData(List.of("rpgquest:ailleurs"), List.of(), List.of(),
                true, false, false, Map.of(), Map.of(), Map.of(), Map.of());

        ContentPackImport.Analysis a = ContentPackImport.analyze(pack(ONE_QUEST) + """
                dependencies:
                  quests:
                    - rpgquest:mines
                    - rpgquest:ailleurs
                    - rpgquest:absente
                """, workspace, ref, Map.of());

        Map<String, ContentPackImport.DependencyState> byId = new java.util.LinkedHashMap<>();
        a.dependencies().forEach(d -> byId.put(d.id(), d.state()));
        assertEquals(ContentPackImport.DependencyState.IN_PACK, byId.get("rpgquest:mines"));
        assertEquals(ContentPackImport.DependencyState.ON_SERVER, byId.get("rpgquest:ailleurs"));
        assertEquals(ContentPackImport.DependencyState.MISSING, byId.get("rpgquest:absente"));
    }

    /** Une dépendance manquante n'empêche pas l'import : elle informe, elle ne bloque pas. */
    @Test
    void aMissingDependencyDoesNotBlockTheImport() {
        ContentPackImport.Analysis a = ContentPackImport.analyze(pack(ONE_QUEST) + """
                dependencies:
                  quests:
                    - rpgquest:absente
                """, workspace, RefData.empty(), Map.of());

        assertTrue(a.importable());
    }

    // ---- Métadonnées ---------------------------------------------------------------------------

    @Test
    void metadataIsReadForDisplayAndNeverDrivesADecision() {
        ContentPackImport.Analysis a = ContentPackImport.analyze(pack(ONE_QUEST) + """
                metadata:
                  title: "Les mines oubliées"
                  generator: "ChatGPT"
                """, workspace, RefData.empty(), Map.of());

        assertEquals("Les mines oubliées", a.metadata().get("title"));
        assertEquals("ChatGPT", a.metadata().get("generator"));
        assertTrue(a.importable(), "les métadonnées n'influent sur rien");
    }

    // ---- Relecture par les lecteurs réels ------------------------------------------------------

    /**
     * Ce qui est enregistré doit être relisible par le lecteur réel de sa famille — c'est la
     * garantie que le plugin saura le charger. Vérifié sur le fichier réellement écrit.
     */
    @Test
    void whatIsSavedCanBeReReadByTheRealReaders() throws Exception {
        ContentPackImport.Analysis a = analyze(pack(ONE_QUEST + """
                  stories:
                    - id: campagne
                      name: "Campagne"
                      quests:
                        - rpgquest:mines
                  dialogues:
                    - id: mineur
                      start: accueil
                      nodes:
                        accueil:
                          speaker: "Mineur"
                          text: "Bonjour."
                          choices:
                            - text: "Au revoir."
                              actions:
                                - type: CLOSE
                """));
        assertTrue(a.importable(), () -> a.elements().toString());
        ContentPackImport.apply(a, workspace);

        assertTrue(QuestYaml.read(read("quests", "mines")).ok(),
                () -> QuestYaml.read(read("quests", "mines")).problems().toString());
        assertTrue(StoryYaml.read(read("stories", "campagne")).ok());
        assertTrue(DialogueYaml.read(read("dialogues", "mineur")).ok(),
                () -> DialogueYaml.read(read("dialogues", "mineur")).problems().toString());
    }

    /** Les trois familles écrivables sont importées en un seul passage. */
    @Test
    void theThreeWritableFamiliesAreImportedTogether() {
        ContentPackImport.Analysis a = analyze(pack(ONE_QUEST + """
                  stories:
                    - id: campagne
                      name: "Campagne"
                      quests:
                        - rpgquest:mines
                  dialogues:
                    - id: mineur
                      start: accueil
                      nodes:
                        accueil:
                          speaker: "Mineur"
                          text: "Bonjour."
                """));

        assertEquals(3, a.writing());
        assertEquals(List.of("quests", "stories", "dialogues"),
                a.elements().stream().map(ContentPackImport.Element::family).toList());
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private void write(String kind, String slug, String yaml) {
        ContentWorkspace.WriteResult r = workspace.write(kind, slug, yaml, null);
        assertTrue(r.ok(), () -> "écriture de préparation échouée : " + r.message());
    }

    /** Réécrit un fichier existant comme le ferait l'éditeur : avec l'empreinte courante. */
    private void rewrite(String kind, String slug, String yaml) {
        String sha = workspace.read(kind, slug).orElseThrow().sha256();
        ContentWorkspace.WriteResult r = workspace.write(kind, slug, yaml, sha);
        assertTrue(r.ok(), () -> "réécriture de préparation échouée : " + r.message());
    }

    private String read(String kind, String slug) {
        return workspace.read(kind, slug).orElseThrow().text();
    }
}
