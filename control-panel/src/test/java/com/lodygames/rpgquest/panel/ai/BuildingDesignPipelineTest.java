package com.lodygames.rpgquest.panel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.building.BuildingDesignRunStore;
import com.lodygames.rpgquest.panel.building.BuildingPlanDraft;
import com.lodygames.rpgquest.panel.building.BuildingPlanDraftStore;
import com.lodygames.rpgquest.panel.building.BuildingPlanWorkbench;
import com.lodygames.rpgquest.panel.building.BuildingProject;
import com.lodygames.rpgquest.panel.building.BuildingProjectStore;
import com.lodygames.rpgquest.panel.building.WorkshopAutopilot;
import com.lodygames.rpgquest.panel.json.Json;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #282 — la conception automatique, de bout en bout côté panel, <strong>sans un seul appel
 * payant</strong> : un faux fournisseur OpenAI rend des plans scriptés, et un faux agent juge les
 * plans par une règle fixe, en répondant par le même chemin que le vrai (résultat enregistré, puis
 * écouteur de l'Atelier). La qualité géométrique elle-même est éprouvée côté plugin, sur les plans
 * réels ; ici, c'est l'orchestration : budget, arrêts, réparation, critique, journal.
 *
 * <p>Règle du faux agent : un plan contenant {@code staircase} est conforme ; {@code overflow}
 * revient refusé AVEC une réparation du moteur ; {@code ugly-critique} est refusé ; tout le reste
 * est refusé pour « niveau inaccessible ».</p>
 */
class BuildingDesignPipelineTest {

    private static final String AGENT = "rpgquest-dev";

    @TempDir
    Path tmp;

    private HttpServer ai;
    private final Deque<String> scripted = new ArrayDeque<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private AgentStore agents;
    private BuildingPlanDraftStore drafts;
    private BuildingProjectStore projects;
    private BuildingDesignRunStore runs;
    private com.lodygames.rpgquest.panel.building.ReferenceImageStore images;
    private WorkshopAutopilot autopilot;
    private BuildingDesignPipeline pipeline;
    private BuildingProject project;
    private Thread fakeAgent;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile boolean agentOnline = true;

    @BeforeEach
    void setUp() throws Exception {
        ai = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ai.createContext("/v1/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String plan;
            synchronized (scripted) {
                plan = scripted.isEmpty() ? "id: vide" : scripted.poll();
            }
            Map<String, Object> message = Map.of("role", "assistant", "content", plan);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("model", "gpt-4o-2024-08-06");
            response.put("choices", List.of(Map.of("message", message)));
            response.put("usage", Map.of("prompt_tokens", 4400, "completion_tokens", 900));
            byte[] bytes = Json.write(response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        ai.start();
        String db = tmp.resolve("cp.db").toString();
        AiSettingsStore settings = new AiSettingsStore(db);
        settings.save(new AiProviderSettings("openai", true, "sk-test",
                "http://127.0.0.1:" + ai.getAddress().getPort(), "", 4000, 5), "test");
        AiBuildingStudio studio = new AiBuildingStudio(new AiProviderRegistry(), settings);
        agents = new AgentStore(db);
        drafts = new BuildingPlanDraftStore(db);
        projects = new BuildingProjectStore(db);
        runs = new BuildingDesignRunStore(db);
        images = new com.lodygames.rpgquest.panel.building.ReferenceImageStore(db);
        InMemoryAuditLog audit = new InMemoryAuditLog();
        autopilot = new WorkshopAutopilot(agents, drafts, audit);
        pipeline = new BuildingDesignPipeline(studio, runs, projects, drafts,
                new BuildingPlanWorkbench(agents, drafts), agents, autopilot, audit, images,
                Duration.ofSeconds(3));
        project = projects.create(AGENT, new BuildingProjectStore.Fields("Bibliothèque du Hub",
                "Bibliothèque médiévale de deux étages, escalier intérieur.", "bibliotheque_test", "WEST",
                19, 14), "", "owner");
        fakeAgent = new Thread(this::answerAudits, "faux-agent");
        fakeAgent.setDaemon(true);
        fakeAgent.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        running.set(false);
        fakeAgent.join(2000);
        pipeline.shutdown();
        ai.stop(0);
    }

    /** Le faux agent : juge chaque audit en attente selon la règle, comme le plugin le ferait. */
    private void answerAudits() {
        while (running.get()) {
            if (agentOnline) {
                for (AgentActionRow row : agents.recentActions(AGENT, 200)) {
                    if (!row.status().terminal() && row.type().equals("building.plan.audit")) {
                        judge(row);
                    }
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void judge(AgentActionRow row) {
        String plan = row.params().getOrDefault("plan", "");
        Map<String, Object> details = new LinkedHashMap<>();
        boolean ok = plan.contains("staircase") && !plan.contains("ugly-critique");
        details.put("code", ok ? "OK" : "ARCHITECTURE_REFUSED");
        details.put("codes", ok ? List.of() : List.of("LEVEL_UNREACHABLE"));
        details.put("message", ok ? "Plan conforme." : "Niveau inaccessible : la marche la plus haute "
                + "est en 9/5/5, sous le plancher (y=6). Diagnostic-"
                + (plan.contains("same-diag") ? "identique" : String.valueOf(plan.hashCode())));
        details.put("warnings", List.of());
        details.put("metrics", Map.of("floors", 2, "reachableFloors", ok ? 2 : 1));
        if (plan.contains("overflow")) {
            details.put("repair", Map.of("plan", plan.replace("overflow", "staircase repaired"),
                    "changes", List.of("Instruction 18 (gable) : zone ramenée au volume."),
                    "codes", List.of(), "accepted", true));
        }
        String status = ok ? "SUCCESS" : "FAILED";
        agents.recordResult(row.id(), AGENT, AgentActionStatus.valueOf(status), status,
                String.valueOf(details.get("message")), Json.write(Map.of("action_id", row.id(),
                        "status", status, "details", details)), Instant.now());
        // Comme AgentEndpoints : l'Atelier est prévenu de chaque résultat.
        autopilot.onResult(row.id());
    }

    private BuildingDesignRunStore.Run run(BuildingDesignPipeline.Quality quality, int maxCalls,
                                           String... plans) {
        synchronized (scripted) {
            scripted.addAll(List.of(plans));
        }
        BuildingDesignRunStore.Run r = runs.start(AGENT, project.id(), "token-" + System.nanoTime(),
                "openai", quality.name(), maxCalls, "owner").orElseThrow();
        pipeline.execute(r.id());
        return runs.find(r.id()).orElseThrow();
    }

    private static String plan(String marker) {
        return "id: bibliotheque_test\nname: \"Bibliothèque\"\nfront: WEST\nsize: {x: 19, y: 14, z: 19}\n"
                + "steps:\n  - op: box\n    from: [0, 0, 0]\n    to: [18, 0, 18]\n"
                + "    block: minecraft:stone_bricks\n# " + marker + "\n";
    }

    private List<String> kinds(BuildingDesignRunStore.Run r) {
        List<String> out = new ArrayList<>();
        r.passes().forEach(p -> out.add(p.kind()));
        return out;
    }

    @Test
    void aRefusedDesignIsCorrectedWithTheExactDiagnosisUntilItPasses() {
        var r = run(BuildingDesignPipeline.Quality.STANDARD, 4, plan("first"), plan("second staircase"));
        assertEquals(BuildingDesignRunStore.Status.SUCCEEDED, r.status(), r.outcome());
        assertEquals(2, r.spentCalls());
        assertEquals(List.of("CONCEPTION", "VÉRIFICATION", "CORRECTION", "VÉRIFICATION"), kinds(r));
        assertTrue(requests.get(1).contains("la marche la plus haute est en 9/5/5"),
                "la correction a reçu le diagnostic du serveur, sans intervention");
        assertEquals(8800, r.inputTokens(), "les jetons de chaque appel sont comptés");
        assertTrue(drafts.find(r.finalDraftId()).orElseThrow().plan().contains("second staircase"));
    }

    @Test
    void aSafeEngineRepairCostsNothingAndEndsTheSession() {
        var r = run(BuildingDesignPipeline.Quality.STANDARD, 4, plan("overflow"));
        assertEquals(BuildingDesignRunStore.Status.SUCCEEDED, r.status(), r.outcome());
        assertEquals(1, r.spentCalls(), "la réparation du moteur ne coûte aucun appel");
        assertTrue(kinds(r).contains("MOTEUR"), kinds(r).toString());
        BuildingPlanDraft fin = drafts.find(r.finalDraftId()).orElseThrow();
        assertEquals(BuildingPlanDraft.Origin.ENGINE_REPAIR, fin.origin());
        assertEquals(1, drafts.forProject(AGENT, project.id()).stream()
                .filter(d -> d.origin() == BuildingPlanDraft.Origin.ENGINE_REPAIR).count(),
                "une seule réparation, même si l'écouteur et la session la demandent tous deux");
    }

    @Test
    void aConformPlanGetsAnArchitecturalCritiqueAndTheImprovedVersionIsKept() {
        var r = run(BuildingDesignPipeline.Quality.SOIGNE, 8, plan("good staircase"),
                plan("good staircase with buttresses"));
        assertEquals(BuildingDesignRunStore.Status.SUCCEEDED, r.status(), r.outcome());
        assertEquals(2, r.spentCalls());
        assertTrue(kinds(r).contains("CRITIQUE"));
        // #289 : la critique compare au brief et à la conception, et commence par les omissions.
        assertTrue(requests.get(1).contains("OMISSIONS ET ÉCARTS"));
        assertTrue(drafts.find(r.finalDraftId()).orElseThrow().plan().contains("buttresses"));
    }

    @Test
    void aCritiqueThatBreaksThePlanNeverReplacesTheConformVersion() {
        var r = run(BuildingDesignPipeline.Quality.SOIGNE, 2, plan("good staircase"),
                plan("staircase ugly-critique"));
        assertEquals(BuildingDesignRunStore.Status.SUCCEEDED, r.status(), r.outcome());
        assertEquals(2, r.spentCalls());
        assertTrue(drafts.find(r.finalDraftId()).orElseThrow().plan().contains("# good staircase"),
                "la version conforme reste le résultat");
    }

    /**
     * #655 — la forme de la session réelle 6a91d328 (Boulangerie, « Exigeant », 12 appels) : un plan
     * conforme, puis une critique qui le casse, puis dix corrections de la critique jusqu'à épuiser
     * l'enveloppe. Désormais une critique a droit à UNE correction ; ensuite on revient au conforme.
     */
    @Test
    void aBrokenCritiqueGetsOneCorrectionThenTheConformVersionIsKeptWithoutSpendingTheEnvelope() {
        var r = run(BuildingDesignPipeline.Quality.EXIGEANT, 12, plan("good staircase"),
                plan("staircase ugly-critique 1"), plan("staircase ugly-critique 2"),
                plan("staircase ugly-critique 3"), plan("staircase ugly-critique 4"),
                plan("staircase ugly-critique 5"), plan("staircase ugly-critique 6"),
                plan("staircase ugly-critique 7"), plan("staircase ugly-critique 8"));
        assertEquals(BuildingDesignRunStore.Status.SUCCEEDED, r.status(), r.outcome());
        // conception + 2 × (critique + sa seule correction) = 5, et non 12.
        assertEquals(5, r.spentCalls(), kinds(r).toString());
        assertEquals(5, requests.size(), "aucun appel au-delà");
        assertEquals(2, kinds(r).stream().filter("CRITIQUE"::equals).count(), kinds(r).toString());
        assertEquals(2, kinds(r).stream().filter("CORRECTION"::equals).count(), kinds(r).toString());
        assertTrue(drafts.find(r.finalDraftId()).orElseThrow().plan().contains("# good staircase"),
                "la version conforme reste le résultat");
        assertTrue(r.passes().stream().anyMatch(p -> p.verdict().contains("retour à la version conforme")),
                "le retour au conforme est dit dans le journal");
    }

    /** #655 — un diagnostic déjà reçu revient après une conception neuve : la session s'arrête. */
    @Test
    void aDiagnosisThatComesBackAfterAFreshDesignStopsInsteadOfPayingTheSameAnswer() {
        var r = run(BuildingDesignPipeline.Quality.STANDARD, 4, plan("a same-diag"), plan("b same-diag"),
                plan("c same-diag"), plan("d staircase"));
        assertEquals(BuildingDesignRunStore.Status.STOPPED, r.status(), r.outcome());
        assertEquals(3, r.spentCalls(), kinds(r).toString());
        assertTrue(kinds(r).contains("CONCEPTION NEUVE"), kinds(r).toString());
        assertTrue(r.outcome().contains("tourne en rond"), r.outcome());
    }

    @Test
    void theConsentedEnvelopeIsNeverExceeded() {
        var r = run(BuildingDesignPipeline.Quality.EXIGEANT, 3, plan("a"), plan("b"), plan("c"),
                plan("d"), plan("e"));
        assertEquals(BuildingDesignRunStore.Status.STOPPED, r.status());
        assertEquals(3, r.spentCalls());
        assertEquals(3, requests.size(), "aucun appel au-delà de l'enveloppe");
        assertTrue(r.outcome().contains("Enveloppe épuisée"), r.outcome());
    }

    @Test
    void twoIdenticalRefusalsTriggerAFreshDesignThatKnowsThePitfalls() {
        var r = run(BuildingDesignPipeline.Quality.STANDARD, 4, plan("a"), plan("b"), plan("c staircase"));
        assertEquals(BuildingDesignRunStore.Status.SUCCEEDED, r.status(), r.outcome());
        assertTrue(kinds(r).contains("CONCEPTION NEUVE"), kinds(r).toString());
        assertTrue(requests.get(2).contains("ÉCUEILS DES ESSAIS PRÉCÉDENTS"),
                "la nouvelle conception reçoit les écueils, au lieu d'une variante du même défaut");
    }

    @Test
    void aPlanAlreadyTriedStopsTheSession() {
        var r = run(BuildingDesignPipeline.Quality.STANDARD, 4, plan("same"), plan("same"));
        assertEquals(BuildingDesignRunStore.Status.STOPPED, r.status());
        assertEquals(2, r.spentCalls());
        assertTrue(r.outcome().contains("déjà essayé"), r.outcome());
    }

    @Test
    void anUnresponsiveServerStopsTheSessionInsteadOfPayingBlind() {
        agentOnline = false;
        var r = run(BuildingDesignPipeline.Quality.STANDARD, 4, plan("a"), plan("b"));
        assertEquals(BuildingDesignRunStore.Status.STOPPED, r.status());
        assertEquals(1, r.spentCalls(), "pas de deuxième appel sans verdict");
        assertTrue(r.outcome().contains("verdict"), r.outcome());
    }

    @Test
    void aSessionCutByARestartIsMarkedInterruptedAndNeverResumedAlone() {
        BuildingDesignRunStore.Run r = runs.start(AGENT, project.id(), "orphan", "openai", "SOIGNE", 8,
                "owner").orElseThrow();
        assertEquals(1, runs.interruptOrphans());
        assertEquals(BuildingDesignRunStore.Status.INTERRUPTED, runs.find(r.id()).orElseThrow().status());
        assertTrue(runs.start(AGENT, project.id(), "orphan", "openai", "SOIGNE", 8, "owner").isEmpty(),
                "le même jeton ne relance jamais une session");
        assertTrue(requests.isEmpty());
    }

    /** #283 : les pixels des images de référence partent avec CHAQUE passe payante. */
    @Test
    void referenceImagesAreSentAsPixelsWithEveryPaidPass() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
        assertTrue(images.add(project.id(), "facade.png", png, "owner").image().isPresent());
        var r = run(BuildingDesignPipeline.Quality.STANDARD, 4, plan("first"), plan("second staircase"));
        assertEquals(BuildingDesignRunStore.Status.SUCCEEDED, r.status(), r.outcome());
        String b64 = java.util.Base64.getEncoder().encodeToString(png);
        for (String request : requests) {
            assertTrue(request.contains("data:image/png;base64," + b64), "les octets, pas une URL");
            assertTrue(request.contains("RÉFÉRENCES DE STYLE"), "et la consigne d'usage");
        }
    }

    /** #283 : « repartir de zéro » n'hérite ni du plan ni des erreurs de la version existante. */
    @Test
    void aFreshRunIgnoresTheExistingVersions() {
        BuildingPlanDraft old = drafts.create(new BuildingPlanDraftStore.NewDraft(AGENT, "", plan("old"),
                BuildingPlanDraft.Origin.AI_PROPOSAL, "", "", "openai", "OpenAI", "gpt-4o", "", "", "", 19, 19,
                14, "owner", project.id()));
        synchronized (scripted) {
            scripted.add(plan("brand new staircase"));
        }
        BuildingDesignRunStore.Run r = runs.start(AGENT, project.id(), "fresh-token", "openai", "STANDARD", 4,
                true, "owner").orElseThrow();
        pipeline.execute(r.id());
        r = runs.find(r.id()).orElseThrow();
        assertEquals(BuildingDesignRunStore.Status.SUCCEEDED, r.status(), r.outcome());
        assertEquals("CONCEPTION", r.passes().get(0).kind(), "une conception, pas une correction de l'ancien");
        assertFalse(requests.get(0).contains("# old"), "l'ancien plan n'est pas transmis");
        assertEquals("", drafts.find(r.finalDraftId()).orElseThrow().parentId());
        assertTrue(drafts.find(old.id()).isPresent(), "l'ancienne version est conservée");
    }

    @Test
    void theEnvelopeIsAnnouncedInTokensAndDollarsOnlyForKnownPrices() {
        var known = BuildingDesignPipeline.envelope(8, "gpt-4o-2024-08-06", 4400, 900);
        assertTrue(known.priced());
        // #292 : au moins le prompt réel (système, description, plan précédent, diagnostic) par appel.
        assertTrue(known.tokensIn() >= 8 * 9000, String.valueOf(known.tokensIn()));
        assertTrue(known.dollars() > 0.4 && known.dollars() < 0.6, String.valueOf(known.dollars()));
        var unknown = BuildingDesignPipeline.envelope(8, "some-new-model", 0, 0);
        assertFalse(unknown.priced());
        assertTrue(unknown.label().contains("tarif"), unknown.label());
    }

    /** #292 : l'enveloppe suit la description réelle et les images, au lieu d'un forfait fixe. */
    @org.junit.jupiter.api.Test
    void theEnvelopeGrowsWithTheRealDescriptionAndImages() {
        BuildingDesignPipeline.Envelope small = BuildingDesignPipeline.envelope(8, "gpt-4o", 0, 0, 500, 0);
        BuildingDesignPipeline.Envelope lily = BuildingDesignPipeline.envelope(8, "gpt-4o", 0, 0, 12_000, 4);
        org.junit.jupiter.api.Assertions.assertTrue(lily.tokensIn() > small.tokensIn(), lily + " / " + small);
        int perCall = lily.tokensIn() / 8;
        org.junit.jupiter.api.Assertions.assertTrue(perCall >= 12_000 / 3 + 4 * 1_600,
                "la description et les images sont comptées à chaque appel : " + perCall);
        org.junit.jupiter.api.Assertions.assertTrue(lily.dollars() > small.dollars());
    }

    /** #289 : partir du brief, c'est d'abord une conception détaillée, puis un plan qui la traduit. */
    @Test
    void aDetailedDesignIsWrittenFirstAndThePlanTranslatesIt() {
        var docs = new com.lodygames.rpgquest.panel.building.BuildingDesignDocStore(tmp.resolve("cp.db").toString());
        pipeline.withDesignDocs(docs);
        var r = run(BuildingDesignPipeline.Quality.STANDARD, 4,
                "1. PROGRAMME ET ESPACES\nSalle de lecture au nord, lit rose à l'étage.", plan("good staircase"));
        assertEquals(BuildingDesignRunStore.Status.SUCCEEDED, r.status(), r.outcome());
        assertEquals(2, r.spentCalls(), "la conception compte dans l'enveloppe consentie");
        assertEquals("CONCEPTION DÉTAILLÉE", kinds(r).get(0), kinds(r).toString());
        assertTrue(requests.get(0).contains("CORRESPONDANCE AVEC LE BRIEF"), "étape 1 : la conception");
        assertTrue(requests.get(1).contains("LA CONCEPTION DÉTAILLÉE À TRADUIRE"), "étape 2 : la traduction");
        assertTrue(requests.get(1).contains("lit rose"), "le texte de la conception part avec le plan");
        assertTrue(requests.get(1).contains("Bibliothèque médiévale de deux étages"), "le brief aussi");
        assertEquals(1, docs.latest(project.id()).orElseThrow().version(), "la conception est conservée");
    }

    /**
     * #299 : la Maison de Lily — conception corrigée à la main (version 2), puis « Repartir de
     * zéro » : l'IA réécrivait la conception (version 3) et la correction était perdue pour la
     * session. Repartir de zéro repart du PLAN ; la conception manuelle est transmise telle quelle.
     */
    @Test
    void aFreshRunKeepsTheManuallyCorrectedDesign() {
        var docs = new com.lodygames.rpgquest.panel.building.BuildingDesignDocStore(tmp.resolve("cp.db").toString());
        pipeline.withDesignDocs(docs);
        docs.save(project.id(), "Conception IA d'origine.", com.lodygames.rpgquest.panel.building.BuildingDesignDocStore.Origin.AI,
                "openai", "gpt-4o", "", "owner", -1);
        docs.save(project.id(), "Un seul rez-de-chaussee, sans etage.",
                com.lodygames.rpgquest.panel.building.BuildingDesignDocStore.Origin.MANUAL, null, null, null, "owner", 1);
        synchronized (scripted) {
            scripted.add(plan("good staircase"));
        }

        BuildingDesignRunStore.Run r = runs.start(AGENT, project.id(), "fresh-keep", "openai", "STANDARD", 4,
                true, "owner").orElseThrow();
        pipeline.execute(r.id());
        r = runs.find(r.id()).orElseThrow();

        assertEquals(2, docs.latest(project.id()).orElseThrow().version(), "aucune conception réécrite");
        assertEquals("CONCEPTION EN VIGUEUR", kinds(r).get(0), kinds(r).toString());
        assertTrue(r.passes().get(0).verdict().contains("version 2"), r.passes().get(0).verdict());
        assertTrue(requests.get(0).contains("Un seul rez-de-chaussee"), "la conception manuelle part avec le plan");
    }

    @Test
    void theDesignIsRewrittenOnlyOnExplicitRequest() {
        var docs = new com.lodygames.rpgquest.panel.building.BuildingDesignDocStore(tmp.resolve("cp.db").toString());
        pipeline.withDesignDocs(docs);
        docs.save(project.id(), "Un seul rez-de-chaussee, sans etage.",
                com.lodygames.rpgquest.panel.building.BuildingDesignDocStore.Origin.MANUAL, null, null, null, "owner", -1);
        synchronized (scripted) {
            scripted.add("1. PROGRAMME ET ESPACES\nNouvelle conception.");
            scripted.add(plan("good staircase"));
        }

        BuildingDesignRunStore.Run r = runs.start(AGENT, project.id(), "rewrite", "openai", "STANDARD", 4,
                true, true, "owner").orElseThrow();
        pipeline.execute(r.id());
        r = runs.find(r.id()).orElseThrow();

        assertTrue(r.rewriteDesign());
        assertEquals("CONCEPTION DÉTAILLÉE", kinds(r).get(0), kinds(r).toString());
        assertEquals(2, docs.latest(project.id()).orElseThrow().version(), "une NOUVELLE version, l'ancienne gardée");
        assertEquals(2, docs.history(project.id()).size());
    }
}
