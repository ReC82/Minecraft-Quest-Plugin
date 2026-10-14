package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.config.EncounterConfig;
import com.lodygames.rpgquest.encounter.EncounterLoadIssue;
import com.lodygames.rpgquest.encounter.EncounterService;
import com.lodygames.rpgquest.encounter.model.EncounterSite;
import com.lodygames.rpgquest.encounter.model.EncounterTemplate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Actions {@code encounter.*} (issue #13 étape 2) : validation, délégation, vue du panel. */
class EncounterAgentActionsTest {

    private static final UUID PRESENT = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");
    private static final UUID GONE = UUID.fromString("00000000-0000-0000-0000-00000000bbbb");
    private static final String SITE = "enc_wild_forest_3_-2";

    private final List<String> calls = new ArrayList<>();

    private final StubAgentActions actions = new StubAgentActions() {
        @Override
        public CompletableFuture<EncounterCatalogView> encounterCatalog() {
            return CompletableFuture.completedFuture(sampleView());
        }

        @Override
        public CompletableFuture<MutationResult> encounterSiteAction(String operation, String siteId) {
            calls.add(operation + ":" + siteId);
            return CompletableFuture.completedFuture(MutationResult.of(true, operation.toUpperCase(), "ok"));
        }

        @Override
        public CompletableFuture<MutationResult> encounterTemplatesReload() {
            calls.add("reload");
            return CompletableFuture.completedFuture(new MutationResult(true, "LOADED_WITH_ISSUES", "2 modèle(s)",
                    List.of("bad.yml : biome manquant")));
        }

        @Override
        public Map<UUID, String> encounterCitizens() {
            return Map.of(PRESENT, SITE);
        }

        @Override
        public CompletableFuture<MutationResult> encounterTemplateSave(EncounterTemplate template, boolean create) {
            saved.add(template);
            calls.add((create ? "create:" : "update:") + template.id());
            return CompletableFuture.completedFuture(MutationResult.of(true, create ? "CREATED" : "UPDATED", "ok"));
        }

        @Override
        public CompletableFuture<MutationResult> encounterTemplateToggle(String templateId, boolean enabled) {
            calls.add("toggle:" + templateId + ":" + enabled);
            return CompletableFuture.completedFuture(MutationResult.of(true, "DISABLED", "ok"));
        }

        @Override
        public CompletableFuture<MutationResult> encounterTemplateDelete(String templateId, boolean acknowledgeSites) {
            calls.add("delete:" + templateId + ":" + acknowledgeSites);
            return CompletableFuture.completedFuture(MutationResult.of(true, "DELETED", "ok"));
        }
    };

    private final List<EncounterTemplate> saved = new ArrayList<>();

    private final AgentActionExecutor executor = new AgentActionExecutor(
            ref -> CompletableFuture.completedFuture(Optional.empty()),
            (uuid, key) -> CompletableFuture.completedFuture(Optional.empty()), actions);

    private AgentActionOutcome run(String type, Map<String, String> params) {
        return executor.execute(new AgentAction("a1", type, params)).join();
    }

    @Test
    void siteActionsAreDelegatedOnlyForAValidSiteId() {
        assertEquals(AgentActionOutcome.SUCCESS, run("encounter.retry", Map.of("siteId", SITE)).status());
        assertEquals(AgentActionOutcome.SUCCESS, run("encounter.disable", Map.of("siteId", SITE)).status());
        AgentActionOutcome removed = run("encounter.remove", Map.of("siteId", SITE));
        assertEquals(AgentActionOutcome.SUCCESS, removed.status());
        assertEquals(SITE, removed.details().get("siteId"));

        assertFalse(AgentActionOutcome.SUCCESS.equals(run("encounter.remove", Map.of()).status()));
        assertFalse(AgentActionOutcome.SUCCESS.equals(
                run("encounter.remove", Map.of("siteId", "wp_wild_forest_1_1")).status()),
                "un waypoint n'est jamais une occurrence de rencontre");
        assertFalse(AgentActionOutcome.SUCCESS.equals(
                run("encounter.retry", Map.of("siteId", "enc_../../x")).status()));
        assertEquals(List.of("retry:" + SITE, "disable:" + SITE, "remove:" + SITE), calls);
    }

    @Test
    void templateFormIsValidatedByTheFileParser() {
        AgentActionOutcome created = run("encounter.template.create", Map.of("id", "swamp_alchemist",
                "display_name", "Alchimiste des marais", "biomes", "swamp, minecraft:mangrove_swamp",
                "worlds", "wild", "weight", "15", "max_sites", "3", "role", "alchimiste", "enabled", "false"));

        assertEquals(AgentActionOutcome.SUCCESS, created.status(), created.message());
        assertEquals("swamp_alchemist", created.details().get("templateId"));
        EncounterTemplate t = saved.get(0);
        assertEquals("swamp_alchemist", t.npcId(), "npc_id = id par défaut, comme dans un fichier");
        assertEquals(Set.of("minecraft:swamp", "minecraft:mangrove_swamp"), t.biomes());
        assertEquals(List.of("wild"), t.worlds());
        assertEquals(15, t.weight());
        assertEquals(3, t.maxSites());
        assertFalse(t.enabled());

        assertEquals(AgentActionOutcome.SUCCESS, run("encounter.template.update", Map.of("id", "swamp_alchemist",
                "display_name", "Alchimiste", "biomes", "swamp")).status());
        assertEquals(List.of("create:swamp_alchemist", "update:swamp_alchemist"), calls);
    }

    @Test
    void invalidTemplateFormsNeverReachTheServer() {
        List<Map<String, String>> invalid = List.of(
                Map.of("id", "x", "display_name", "X"),
                Map.of("id", "../evil", "display_name", "X", "biomes", "forest"),
                Map.of("id", "x", "display_name", "<red>X", "biomes", "forest"),
                Map.of("id", "x", "display_name", "X", "biomes", "forest", "weight", "beaucoup"),
                Map.of("id", "x", "display_name", "X", "biomes", "forest", "weight", "0"),
                Map.of("id", "x", "display_name", "X", "biomes", "forest", "skin_url", "http://evil.example/s"),
                Map.of("id", "x", "display_name", "X", "biomes", "forest", "description", "a\nb"));
        for (Map<String, String> params : invalid) {
            AgentActionOutcome outcome = run("encounter.template.create", params);
            assertFalse(AgentActionOutcome.SUCCESS.equals(outcome.status()), params::toString);
        }
        assertTrue(saved.isEmpty());
    }

    @Test
    void toggleAndDeleteNeedExplicitParameters() {
        assertFalse(AgentActionOutcome.SUCCESS.equals(
                run("encounter.template.toggle", Map.of("id", "forest_bandit")).status()));
        assertEquals(AgentActionOutcome.SUCCESS,
                run("encounter.template.toggle", Map.of("id", "forest_bandit", "enabled", "false")).status());
        assertFalse(AgentActionOutcome.SUCCESS.equals(run("encounter.template.delete", Map.of()).status()));
        run("encounter.template.delete", Map.of("id", "forest_bandit"));
        run("encounter.template.delete", Map.of("id", "forest_bandit", "acknowledge_sites", "true"));
        assertEquals(List.of("toggle:forest_bandit:false", "delete:forest_bandit:false",
                "delete:forest_bandit:true"), calls, "l'accord sur les occurrences n'est jamais supposé");
    }

    @Test
    void reloadReportsRejectedFilesAsEffects() {
        AgentActionOutcome outcome = run("encounter.templates.reload", Map.of());
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals("LOADED_WITH_ISSUES", outcome.details().get("code"));
        assertEquals(List.of("bad.yml : biome manquant"), outcome.details().get("effects"));
    }

    @Test
    void unwiredServiceIsReportedAsUnavailableNotAsEmpty() {
        AgentActionExecutor bare = new AgentActionExecutor(ref -> CompletableFuture.completedFuture(Optional.empty()),
                (uuid, key) -> CompletableFuture.completedFuture(Optional.empty()), new StubAgentActions());
        AgentActionOutcome list = bare.execute(new AgentAction("b1", "encounter.list", Map.of())).join();
        assertEquals("UNAVAILABLE", list.value());
        AgentActionOutcome clear = bare.execute(new AgentAction("b2", "encounter.backoffs.clear", Map.of())).join();
        assertFalse(AgentActionOutcome.SUCCESS.equals(clear.status()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listCarriesTemplatesSitesCitizensPresenceAndEvents() {
        AgentActionOutcome outcome = run("encounter.list", Map.of());
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status(), outcome.message());
        List<Map<String, Object>> templates = (List<Map<String, Object>>) outcome.details().get("templates");
        assertEquals(1, templates.size());
        assertEquals(2, templates.get(0).get("sites"));
        assertEquals(List.of("minecraft:birch_forest", "minecraft:forest"), templates.get(0).get("biomes"));
        List<Map<String, Object>> sites = (List<Map<String, Object>>) outcome.details().get("sites");
        assertEquals(Boolean.TRUE, sites.get(0).get("citizensPresent"));
        assertEquals(Boolean.FALSE, sites.get(1).get("citizensPresent"), "PNJ enregistré mais disparu");
        assertEquals("DISABLED", sites.get(1).get("status"));
        assertEquals("APPLIED", sites.get(0).get("appearance"));
        assertNull(sites.get(1).get("appearance"), "rien tenté : null, pas « échec »");
        assertEquals(Map.of("bad.yml", "biome manquant"), outcome.details().get("issues"));
        assertEquals(List.of("wild"), outcome.details().get("worlds"));
        List<Map<String, Object>> events = (List<Map<String, Object>>) outcome.details().get("events");
        assertEquals("CREATED", events.get(0).get("outcome"));
    }

    @Test
    void citizensPresenceIsUnknownWithoutCitizens() {
        EncounterSite site = site(SITE, PRESENT, EncounterSite.Status.ACTIVE);
        AgentActions.EncounterCatalogView view = EncounterCatalogViews.build(EncounterConfig.defaults(),
                List.of("wild"), List.of(), List.of(), List.of(site), List.of(), null, id -> null);
        assertNull(view.sites().get(0).citizensPresent(), "inconnu, pas « absent »");
    }

    @Test
    @SuppressWarnings("unchecked")
    void citizensRosterMarksEncounterNpcs() {
        StubAgentActions roster = new StubAgentActions() {
            @Override
            public CompletableFuture<CitizensRosterView> citizensRoster() {
                return CompletableFuture.completedFuture(new CitizensRosterView(true, List.of(
                        new CitizensNpcSummary(7, PRESENT.toString(), "Brigand", null, true, true),
                        new CitizensNpcSummary(8, GONE.toString(), "Autre", null, true, true)), 2, 2, 0));
            }

            @Override
            public Map<UUID, String> encounterCitizens() {
                return Map.of(PRESENT, SITE);
            }
        };
        AgentActionExecutor ex = new AgentActionExecutor(ref -> CompletableFuture.completedFuture(Optional.empty()),
                (uuid, key) -> CompletableFuture.completedFuture(Optional.empty()), roster);
        AgentActionOutcome outcome = ex.execute(new AgentAction("r1", "npc.citizens.list", Map.of())).join();
        List<Map<String, Object>> rows = (List<Map<String, Object>>) outcome.details().get("citizens");
        assertEquals(SITE, rows.get(0).get("encounterSiteId"));
        assertTrue(rows.get(1).containsKey("encounterSiteId"));
        assertNull(rows.get(1).get("encounterSiteId"));
    }

    private static AgentActions.EncounterCatalogView sampleView() {
        EncounterTemplate bandit = new EncounterTemplate("forest_bandit", "Brigand", null, "encounter_bandit",
                "brigand", "forêt", Set.of("minecraft:forest", "minecraft:birch_forest"), List.of(), 10, 0, null,
                true);
        List<EncounterSite> sites = List.of(site(SITE, PRESENT, EncounterSite.Status.ACTIVE),
                site("enc_wild_forest_4_-2", GONE, EncounterSite.Status.DISABLED));
        List<EncounterService.Event> events = List.of(
                new EncounterService.Event(Instant.parse("2026-10-14T01:00:00Z"), "wild", "x", "CREATED", SITE));
        return EncounterCatalogViews.build(EncounterConfig.defaults(), List.of("wild"), List.of(bandit),
                List.of(new EncounterLoadIssue("bad.yml", "biome manquant")), sites, events, PRESENT::equals,
                id -> SITE.equals(id) ? "APPLIED" : null);
    }

    private static EncounterSite site(String id, UUID citizens, EncounterSite.Status status) {
        Instant at = Instant.parse("2026-10-14T00:00:00Z");
        return new EncounterSite(id, "wild", "wild:minecraft:forest:3:-2", "minecraft:forest", 3, -2,
                "forest_bandit", "encounter_bandit", 10, 70, -20, citizens, 5, status, "", at, at);
    }
}
