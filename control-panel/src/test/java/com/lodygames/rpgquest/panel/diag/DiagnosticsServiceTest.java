package com.lodygames.rpgquest.panel.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import com.lodygames.rpgquest.panel.agent.AgentLiveness;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.agent.HeartbeatRecord;
import com.lodygames.rpgquest.panel.docs.DocLibrary;
import com.lodygames.rpgquest.panel.docs.Markdown;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link DiagnosticsService} (issue #38) : agrégation multi-domaines, mapping vers un modèle unique,
 * tri par gravité, dédoublonnage, fraîcheur, et cohérence des liens de documentation.
 */
class DiagnosticsServiceTest {

    @TempDir
    Path tmp;

    private static final String AGENT = "rpgquest-dev";

    private AgentStore store() {
        return new AgentStore(tmp.resolve("cp.db").toString());
    }

    private void snapshot(AgentStore s, String type, String detailsJson) {
        String id = s.createAction(AGENT, type, java.util.Map.of(), "test");
        s.recordResult(id, AGENT, AgentActionStatus.SUCCESS, "1", "ok",
                "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"details\":" + detailsJson + "}",
                Instant.now());
    }

    private DiagnosticsService service(AgentStore s) {
        return new DiagnosticsService(s, AgentLiveness.Thresholds.defaults());
    }

    // ---- payloads réalistes (formes réellement produites par les actions agent) ---------------

    private static final String NPC_DETAILS = "{"
            + "\"citizensAvailable\":true,\"total\":2,\"withWarnings\":2,"
            + "\"definedIds\":[\"guard\"],\"canonicalIds\":[\"guard\",\"woodcutter_bob\"],"
            + "\"npcs\":["
            + "{\"id\":\"guide\",\"displayName\":null,\"warnings\":[{\"code\":\"BINDING_NO_DEFINITION\","
            + "\"severity\":\"error\",\"message\":\"tag sans definition\"}]},"
            + "{\"id\":\"guard\",\"displayName\":\"<yellow>Garde</yellow>\",\"warnings\":[{\"code\":\"NOT_LINKED\","
            + "\"severity\":\"info\",\"message\":\"pas de binding\"}]}"
            + "]}";

    private static final String DIALOGUE_DETAILS = "{"
            + "\"total\":1,\"withWarnings\":1,"
            + "\"loadIssues\":[{\"file\":\"broken.yml\",\"message\":\"nodes obligatoire\"}],"
            + "\"declaredButMissing\":[{\"npcId\":\"ghost\",\"dialogueId\":\"rpgquest:ghost\"}],"
            + "\"dialogues\":[{\"id\":\"rpgquest:guard\",\"key\":\"guard\","
            + "\"warnings\":[{\"code\":\"NODE_UNREACHABLE\",\"severity\":\"info\",\"message\":\"noeud orphelin\"}]}]}";

    private static final String QUEST_DETAILS = "{\"quests\":["
            + "{\"id\":\"rpgquest:crystal_hunt\",\"title\":\"<gold>La chasse</gold>\","
            + "\"prerequisites\":[\"rpgquest:first_steps\"],\"giverId\":\"guard\",\"steps\":[],\"rewards\":[]}]}";

    private static final String STORY_DETAILS = "{\"stories\":["
            + "{\"id\":\"main_story\",\"title\":\"Histoire principale\","
            + "\"stepQuestIds\":[\"rpgquest:crystal_hunt\",\"rpgquest:inexistante\"]}]}";

    // ---- tests --------------------------------------------------------------------------

    @Test
    void aggregatesEveryDomainIntoOneSortedModel() {
        AgentStore s = store();
        snapshot(s, "npc.list", NPC_DETAILS);
        snapshot(s, "dialogue.list", DIALOGUE_DETAILS);
        snapshot(s, "quest.list", QUEST_DETAILS);
        snapshot(s, "story.list", STORY_DETAILS);

        DiagnosticsReport r = service(s).collect(AGENT);
        assertTrue(r.anyDataLoaded());

        // domaines réellement présents
        assertTrue(r.domainsPresent().contains(Domain.PNJ));
        assertTrue(r.domainsPresent().contains(Domain.DIALOGUES));
        assertTrue(r.domainsPresent().contains(Domain.QUESTS));
        assertTrue(r.domainsPresent().contains(Domain.STORIES));

        // au moins un de chaque gravité
        assertTrue(r.errors() >= 2, "ERROR : BINDING_NO_DEFINITION + DIALOGUE_LOAD_ISSUE + DIALOGUE_DECLARED_MISSING");
        assertTrue(r.warnings() >= 2, "WARNING : QUEST_PREREQ_UNKNOWN + STORY_QUEST_UNKNOWN");
        assertTrue(r.infos() >= 1, "INFO : NOT_LINKED / NODE_UNREACHABLE");

        // tri : toutes les ERROR avant toutes les WARNING avant toutes les INFO
        int lastRank = -1;
        for (DiagnosticEntry e : r.entries()) {
            assertTrue(e.severity().rank() >= lastRank, "tri par gravité");
            lastRank = e.severity().rank();
        }

        // codes réellement couverts
        List<String> codes = r.entries().stream().map(DiagnosticEntry::code).toList();
        assertTrue(codes.contains("BINDING_NO_DEFINITION"));
        assertTrue(codes.contains("DIALOGUE_LOAD_ISSUE"));
        assertTrue(codes.contains("DIALOGUE_DECLARED_MISSING"));
        assertTrue(codes.contains("QUEST_PREREQ_UNKNOWN"));
        assertTrue(codes.contains("STORY_QUEST_UNKNOWN"));
    }

    @Test
    void everyEntryIsHumanFirstWithTechnicalCodeSecondary() {
        AgentStore s = store();
        snapshot(s, "npc.list", NPC_DETAILS);
        DiagnosticsReport r = service(s).collect(AGENT);

        DiagnosticEntry binding = r.entries().stream()
                .filter(e -> e.code().equals("BINDING_NO_DEFINITION")).findFirst().orElseThrow();
        assertEquals(Severity.ERROR, binding.severity());
        assertEquals(Domain.PNJ, binding.domain());
        assertEquals("Fiche RPGQuest manquante", binding.title());
        assertFalse(binding.title().equals(binding.code()), "le titre n'est jamais le code");
        assertFalse(binding.message().isBlank());
        assertFalse(binding.consequence().isBlank());
        assertFalse(binding.recommendedAction().isBlank());
        assertTrue(binding.docHref().startsWith("/docs/pnj-depannage#"));
        assertTrue(binding.resourceHref().contains("/npcs") && binding.resourceHref().contains("focus=guide"));
        assertTrue(binding.quickAction() != null && binding.quickAction().label().equals("Créer la définition"));
        // jargon interdit dans le message principal
        for (String banned : new String[] {"tagué", "binding", "namespaced"}) {
            assertFalse(binding.message().toLowerCase(java.util.Locale.ROOT).contains(banned), banned);
        }
        // fraîcheur renseignée (le relevé vient d'être enregistré)
        assertTrue(binding.observedAt() != null);
    }

    @Test
    void serverProviderFlagsAMissingHeartbeatAsError() {
        AgentStore s = store();
        DiagnosticsReport r = service(s).collect(AGENT);
        assertTrue(r.entries().stream().anyMatch(e -> e.code().equals("AGENT_NO_HEARTBEAT")
                && e.severity() == Severity.ERROR && e.domain() == Domain.AGENT));
    }

    @Test
    void serverProviderFlagsAnUnloadedEssentialWorld() {
        AgentStore s = store();
        s.saveHeartbeat(new HeartbeatRecord(AGENT, "dev", Instant.now(), null, "agent/v1", "RPGQuest",
                "7.7.7", "ONLINE", 0, 30, 10,
                "{\"hub\":{\"name\":\"world_hub\",\"loaded\":true},\"wild\":{\"name\":\"wild\",\"loaded\":false}}",
                "{}"));
        DiagnosticsReport r = service(s).collect(AGENT);
        assertTrue(r.entries().stream().anyMatch(e -> e.code().equals("WORLD_NOT_LOADED")
                && e.domain() == Domain.WORLDS && e.message().contains("wild")));
    }

    @Test
    void deduplicatesIdenticalCodePlusResource() {
        AgentStore s = store();
        // même warning répété deux fois sur le même PNJ dans le payload
        snapshot(s, "npc.list", "{\"citizensAvailable\":true,\"npcs\":[{\"id\":\"guide\",\"displayName\":null,"
                + "\"warnings\":[{\"code\":\"BINDING_NO_DEFINITION\",\"severity\":\"error\",\"message\":\"x\"},"
                + "{\"code\":\"BINDING_NO_DEFINITION\",\"severity\":\"error\",\"message\":\"x\"}]}]}");
        DiagnosticsReport r = service(s).collect(AGENT);
        long n = r.entries().stream().filter(e -> e.code().equals("BINDING_NO_DEFINITION")).count();
        assertEquals(1, n, "un seul diagnostic après dédoublonnage");
    }

    @Test
    void emptyWhenNoSnapshotAndNoHeartbeat() {
        // aucun agent -> anyDataLoaded false, mais une info « aucun agent configuré »
        DiagnosticsReport r = new DiagnosticsService(store(), AgentLiveness.Thresholds.defaults()).collect("");
        assertFalse(r.anyDataLoaded());
        assertTrue(r.entries().stream().anyMatch(e -> e.code().equals("AGENT_NOT_CONFIGURED")));
    }

    @Test
    void everyServerDocAnchorExistsInAWhitelistedSheet() throws Exception {
        AgentStore s = store();
        s.saveHeartbeat(new HeartbeatRecord(AGENT, "dev", Instant.now().minusSeconds(600), null, "agent/v1",
                "RPGQuest", "", "STARTING", -1, -1, -1,
                "{\"wild\":{\"name\":\"wild\",\"loaded\":false}}", "{}"));
        snapshot(s, "npc.list", "{\"citizensAvailable\":false,\"npcs\":[]}");

        DiagnosticsReport r = service(s).collect(AGENT);
        DocLibrary lib = DocLibrary.load();
        String index;
        try (InputStream in = getClass().getResourceAsStream("/docs/_index.txt")) {
            index = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int checked = 0;
        for (DiagnosticEntry e : r.entries()) {
            if (e.docHref().isBlank() || !e.docHref().contains("#")) {
                continue;
            }
            String slug = e.docHref().substring("/docs/".length(), e.docHref().indexOf('#'));
            String anchor = e.docHref().substring(e.docHref().indexOf('#') + 1);
            assertTrue(index.contains(slug + ".md"), slug + " listée dans _index.txt (" + e.code() + ")");
            String html = Markdown.render(lib.bySlug(slug).orElseThrow().markdown());
            assertTrue(html.contains("id=\"" + anchor + "\""),
                    e.code() + " -> " + e.docHref() + " : ancre absente de la fiche");
            checked++;
        }
        assertTrue(checked >= 3, "plusieurs diagnostics vérifiés (" + checked + ")");
    }
}
