package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #211 — le pack JSON de référence « Tom reprend la route » : un PNJ logique, une story, trois
 * quêtes enchaînées et leurs dialogues, importés par le pipeline existant (aucun second import).
 */
class TomContentPackTest {

    @TempDir
    Path tmp;

    private ContentWorkspace workspace;
    private String pack;

    @BeforeEach
    void setUp() throws Exception {
        Path root = tmp.resolve("content");
        for (String kind : ContentWorkspace.KINDS) {
            Files.createDirectories(root.resolve(kind));
        }
        workspace = new ContentWorkspace(root, tmp.resolve("backups"));
        try (var in = getClass().getResourceAsStream("/tom_reprend_la_route.json")) {
            pack = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static long count(ContentPackImport.Analysis a, String family, ContentPackImport.Status status) {
        return a.elements().stream().filter(e -> e.family().equals(family) && e.status() == status).count();
    }

    @Test
    void theJsonPackIsAnalysedByTheRealReadersAndEverythingResolves() {
        ContentPackImport.Analysis a = ContentPackImport.analyze(pack, workspace, RefData.empty(), Map.of());
        assertTrue(a.readable(), a.envelope().toString());
        assertTrue(a.importable(), () -> a.elements().toString());
        assertEquals(3, count(a, "quests", ContentPackImport.Status.NEW));
        assertEquals(1, count(a, "stories", ContentPackImport.Status.NEW));
        assertEquals(1, count(a, "dialogues", ContentPackImport.Status.NEW));
        assertEquals(1, count(a, "npcs", ContentPackImport.Status.NEW), "Tom, PNJ logique inédit");
        for (ContentPackImport.Element e : a.elements()) {
            assertFalse(Diagnostic.hasError(e.diagnostics()), e.key() + " : " + e.diagnostics());
        }
    }

    @Test
    void confirmingWritesCanonicalSourceFilesAndPreparesTomsCreation() throws Exception {
        ContentPackImport.Analysis a = ContentPackImport.analyze(pack, workspace, RefData.empty(), Map.of());
        List<ContentPackImport.WriteOutcome> out = ContentPackImport.apply(a, workspace);
        assertTrue(out.stream().allMatch(o -> o.result() == null || o.result().ok()), out.toString());

        // Relus par les lecteurs réels, dans la forme canonique de l'éditeur.
        for (String quest : List.of("tom_chariot", "tom_bache", "tom_marchandises")) {
            assertTrue(QuestYaml.read(workspace.read("quests", quest).orElseThrow().text()).ok(), quest);
        }
        String bache = workspace.read("quests", "tom_bache").orElseThrow().text();
        assertTrue(bache.contains("rpgquest:tom_chariot"), "le déblocage suivant est conservé : " + bache);
        assertTrue(bache.contains("DELIVER_ITEM_TO_NPC") && bache.contains("LEATHER"), bache);
        assertTrue(StoryYaml.read(workspace.read("stories", "tom_reprend_la_route").orElseThrow().text()).ok());
        String dialogue = workspace.read("dialogues", "tom").orElseThrow().text();
        assertTrue(DialogueYaml.read(dialogue).ok(), () -> DialogueYaml.read(dialogue).problems().toString());
        assertTrue(dialogue.contains("DELIVER_QUEST_ITEMS") && dialogue.contains("HAS_PENDING_DELIVERY"), dialogue);

        // Tom n'est pas un fichier de la source : sa création part par l'action d'agent existante,
        // validée par le catalogue.
        List<Map<String, String>> creations = ContentPackImport.npcCreations(a, pack);
        assertEquals(1, creations.size());
        var v = AgentActionCatalog.validate("npc.definition.create", creations.get(0));
        assertTrue(v.valid(), v.error());
        assertEquals("tom", v.params().get("npc_id"));
        assertEquals("rpgquest:tom", v.params().get("dialogue_id"));

        // Réimport du même pack : rien à écrire, aucun doublon.
        ContentPackImport.Analysis again = ContentPackImport.analyze(pack, workspace,
                RefData.empty().plus(List.of(), List.of(), Map.of()), Map.of());
        assertEquals(0, count(again, "quests", ContentPackImport.Status.NEW));
        assertEquals(3, count(again, "quests", ContentPackImport.Status.UNCHANGED));
    }

    @Test
    void aTomAlreadyOnTheServerIsLeftUntouched() {
        RefData server = new RefData(List.of(), List.of("tom"), List.of(), false, true, false);
        ContentPackImport.Analysis a = ContentPackImport.analyze(pack, workspace, server, Map.of());
        ContentPackImport.Element tom = a.elements().stream().filter(e -> e.family().equals("npcs")).findFirst()
                .orElseThrow();
        assertEquals(ContentPackImport.Status.SKIPPED, tom.status());
        assertTrue(tom.reason().contains("existe déjà sur le serveur"), tom.reason());
        assertTrue(ContentPackImport.npcCreations(a, pack).isEmpty());
    }

    @Test
    void aFailedWriteLeavesNoPartialContent() throws Exception {
        ContentPackImport.Analysis a = ContentPackImport.analyze(pack, workspace, RefData.empty(), Map.of());
        // La story apparaît entre l'analyse et la confirmation : son écriture est refusée — les
        // quêtes déjà écrites par cet import doivent être défaites.
        Files.writeString(tmp.resolve("content").resolve("stories").resolve("tom_reprend_la_route.yml"),
                "id: tom_reprend_la_route\nname: \"autre\"\nquests: []\n");
        List<ContentPackImport.WriteOutcome> out = ContentPackImport.apply(a, workspace);

        assertTrue(out.stream().anyMatch(o -> o.result() != null && !o.result().ok()));
        for (String quest : List.of("tom_chariot", "tom_bache", "tom_marchandises")) {
            assertFalse(workspace.exists("quests", quest), quest + " défaite");
        }
        assertFalse(workspace.exists("dialogues", "tom"));
    }

    @Test
    void anUnreadableJsonIsRefusedReadably() {
        ContentPackImport.Analysis a = ContentPackImport.analyze("{ \"format\": ", workspace, RefData.empty(), Map.of());
        assertFalse(a.readable());
        assertTrue(a.envelope().get(0).message().startsWith("JSON illisible"), a.envelope().toString());
    }
}
