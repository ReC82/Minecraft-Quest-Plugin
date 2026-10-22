package com.lodygames.rpgquest.panel.ai;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.AuditLog;
import com.lodygames.rpgquest.panel.building.BuildingDesignRunStore;
import com.lodygames.rpgquest.panel.building.BuildingDesignRunStore.Pass;
import com.lodygames.rpgquest.panel.building.BuildingDesignRunStore.Run;
import com.lodygames.rpgquest.panel.building.BuildingPlanDraft;
import com.lodygames.rpgquest.panel.building.BuildingPlanDraftStore;
import com.lodygames.rpgquest.panel.building.BuildingPlanVerdict;
import com.lodygames.rpgquest.panel.building.BuildingPlanWorkbench;
import com.lodygames.rpgquest.panel.building.BuildingProject;
import com.lodygames.rpgquest.panel.building.BuildingProjectStore;
import com.lodygames.rpgquest.panel.building.PlanVerdictText;
import com.lodygames.rpgquest.panel.building.WorkshopAutopilot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * La <strong>conception automatique</strong> d'un bâtiment (issue #282) : plusieurs passes d'IA et
 * de moteur enchaînées, sous une enveloppe consentie, jusqu'à un plan conforme — puis embelli.
 *
 * <h2>Pourquoi</h2>
 *
 * <p>Le propriétaire : « Je préfère payer plusieurs appels pour obtenir une bibliothèque réussie
 * plutôt que multiplier les essais médiocres qui nécessitent mon intervention. » Le 12/10/2026,
 * chaque refus revenait à l'administrateur, qui recopiait l'erreur pour l'IA, payait, revérifiait —
 * trois fois pour la Bibliothèque du Hub, sans succès.</p>
 *
 * <h2>Les passes</h2>
 *
 * <ol>
 *   <li><strong>Conception</strong> (IA) si le projet n'a pas encore de plan ;</li>
 *   <li><strong>Vérification</strong> (serveur, gratuite) : audit et aperçu sur la vraie grille ;</li>
 *   <li><strong>Réparation</strong> (moteur, gratuite) : si le serveur sait corriger sans risque,
 *       il le fait — {@link WorkshopAutopilot} crée la version réparée, revérifiée ;</li>
 *   <li><strong>Correction</strong> (IA) avec le diagnostic exact du serveur, coordonnées et
 *       instruction fautive comprises ;</li>
 *   <li><strong>Critique architecturale</strong> (IA), une fois le plan conforme : beauté,
 *       façades, aménagement, fidélité au brief. Son plan est vérifié ; s'il régresse, la version
 *       conforme précédente reste le résultat.</li>
 * </ol>
 *
 * <h2>Les garde-fous — contre les boucles, pas contre la qualité</h2>
 *
 * <ul>
 *   <li><strong>une enveloppe consentie</strong>, bornée à {@value #MAX_CALLS_CEILING} appels :
 *       chaque appel émis est compté, même s'il échoue ;</li>
 *   <li><strong>aucune répétition mécanique</strong> : un plan déjà vu arrête la correction ; deux
 *       refus identiques de suite déclenchent une conception NEUVE qui reçoit les écueils déjà
 *       rencontrés, au lieu d'une troisième variante du même défaut ;</li>
 *   <li><strong>le serveur doit répondre</strong> : sans verdict dans le délai, on s'arrête plutôt
 *       que de payer à l'aveugle ;</li>
 *   <li><strong>tout est conservé</strong> : chaque passe est une version, chaque dépense est
 *       écrite au fil de l'eau ; un redémarrage du panel interrompt la session sans la relancer.</li>
 * </ul>
 */
public final class BuildingDesignPipeline {

    /** Borne dure d'une session : au-delà, c'est une dépense que personne ne surveille. */
    public static final int MAX_CALLS_CEILING = 12;

    private static final System.Logger LOG = System.getLogger(BuildingDesignPipeline.class.getName());
    private static final Pattern USAGE = Pattern.compile("(\\d+) entrée / (\\d+) sortie");

    /** Le niveau d'exigence choisi par l'administrateur, et l'enveloppe qui va avec. */
    public enum Quality {
        STANDARD("Standard", 4, 0, "un plan conforme, corrigé autant que nécessaire"),
        SOIGNE("Soigné", 8, 1, "un plan conforme, puis une passe de critique architecturale"),
        EXIGEANT("Exigeant", 12, 2, "deux passes de critique, pour les bâtiments vitrines");

        private final String label;
        private final int calls;
        private final int critiques;
        private final String description;

        Quality(String label, int calls, int critiques, String description) {
            this.label = label;
            this.calls = calls;
            this.critiques = critiques;
            this.description = description;
        }

        public String label() {
            return label;
        }

        /** Le plafond d'appels par défaut de ce niveau. */
        public int calls() {
            return calls;
        }

        public int critiques() {
            return critiques;
        }

        public String description() {
            return description;
        }

        public static Quality of(String raw) {
            for (Quality q : values()) {
                if (q.name().equalsIgnoreCase(raw == null ? "" : raw.trim())) {
                    return q;
                }
            }
            return SOIGNE;
        }
    }

    private final AiBuildingStudio studio;
    private final BuildingDesignRunStore runs;
    private final BuildingProjectStore projects;
    private final BuildingPlanDraftStore drafts;
    private final BuildingPlanWorkbench workbench;
    private final AgentStore agents;
    private final WorkshopAutopilot autopilot;
    private final AuditLog audit;
    private final com.lodygames.rpgquest.panel.building.ReferenceImageStore images;
    private final Duration verdictTimeout;
    private final ExecutorService executor;
    /** #289 : la conception détaillée ; sans elle, la session part du seul brief (forme #282). */
    private com.lodygames.rpgquest.panel.building.BuildingDesignDocStore designDocs;

    /** #739 : le journal IA du projet ; sans lui, rien n'est journalisé (tests anciens). */
    private BuildingAiJournal journal;

    /** Branche le journal IA (#739). */
    public BuildingDesignPipeline withJournal(BuildingAiJournal j) {
        this.journal = j;
        return this;
    }

    /** Branche la conception détaillée (#289). */
    public BuildingDesignPipeline withDesignDocs(com.lodygames.rpgquest.panel.building.BuildingDesignDocStore store) {
        this.designDocs = store;
        return this;
    }

    public BuildingDesignPipeline(AiBuildingStudio studio, BuildingDesignRunStore runs,
                                  BuildingProjectStore projects, BuildingPlanDraftStore drafts,
                                  BuildingPlanWorkbench workbench, AgentStore agents,
                                  WorkshopAutopilot autopilot, AuditLog audit,
                                  com.lodygames.rpgquest.panel.building.ReferenceImageStore images,
                                  Duration verdictTimeout) {
        this.studio = studio;
        this.runs = runs;
        this.projects = projects;
        this.drafts = drafts;
        this.workbench = workbench;
        this.agents = agents;
        this.autopilot = autopilot;
        this.audit = audit;
        this.images = images;
        this.verdictTimeout = verdictTimeout;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "atelier-conception");
            t.setDaemon(true);
            return t;
        });
    }

    /** Lance la session en arrière-plan. La réservation (jeton, enveloppe) est déjà faite. */
    public void launch(Run run) {
        executor.submit(() -> execute(run.id()));
    }

    /**
     * Arrêt du panel : la session en cours est interrompue, notée comme telle, et l'on attend
     * qu'elle ait fini d'écrire — sans cela, une écriture en base pouvait suivre l'arrêt (trouvé par
     * la CI : le répertoire temporaire d'un test était encore utilisé à sa suppression).
     */
    public void shutdown() {
        stopping = true;
        executor.shutdownNow();
        try {
            executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private volatile boolean stopping;

    /** Déroule une session, du début à la fin. Ne lève jamais. Public pour les tests. */
    public void execute(String runId) {
        try {
            Run run = runs.find(runId).orElseThrow();
            Optional<BuildingProject> project = projects.find(run.projectId());
            if (project.isEmpty()) {
                runs.finish(runId, BuildingDesignRunStore.Status.STOPPED, "Projet introuvable.", "");
                return;
            }
            new Session(run, project.get()).go();
        } catch (RuntimeException failure) {
            if (stopping) {
                runs.finish(runId, BuildingDesignRunStore.Status.INTERRUPTED, "Le panel s'est arrêté pendant "
                        + "la conception : rien n'a été relancé. Les versions déjà produites sont conservées.", "");
                journalEnd(runId);
                return;
            }
            LOG.log(System.Logger.Level.WARNING, "event=design_run_failed run=" + runId + " error="
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            runs.finish(runId, BuildingDesignRunStore.Status.STOPPED, "Arrêt sur une erreur interne : "
                    + failure.getClass().getSimpleName() + ". Les versions déjà produites sont conservées.",
                    "");
            journalEnd(runId);
        }
    }

    /** #739 : la fin d'une session, telle qu'elle est enregistrée, recopiée dans le journal IA. */
    private void journalEnd(String runId) {
        if (journal == null) {
            return;
        }
        runs.find(runId).ifPresent(r -> journal.runEnd(r.projectId(), r.id(), r.createdBy(), r.status().name(),
                r.outcome(), r.finalDraftId(), java.time.Duration.between(r.createdAt(), Instant.now()).toMillis(),
                r.spentCalls()));
    }

    /** Le verdict du serveur sur une version, et la réparation éventuelle du moteur. */
    private record Judgement(boolean reached, BuildingPlanVerdict verdict, String diagnosis,
                             BuildingPlanDraft repaired) {
    }

    /** Une session en cours : l'état de la boucle. */
    private final class Session {
        private final Run run;
        private final BuildingProject project;
        private BuildingPromptBuilder.Demand demand;
        private final Quality quality;
        private final Set<String> seenPlans = new HashSet<>();
        private final List<String> pitfalls = new ArrayList<>();
        private int spent;
        private int critiques;
        private BuildingPlanDraft best;
        /** #655 : la critique en cours a-t-elle déjà eu sa correction ? Une seule par critique. */
        private boolean critiqueFixed;
        /** #655 : empreintes des diagnostics déjà reçus — un diagnostic qui revient est stérile. */
        private final Set<String> seenDiagnoses = new HashSet<>();
        private boolean freshDesignDone;
        /** #739 : empreintes courtes des diagnostics de refus, dans l'ordre d'arrivée. */
        private final java.util.LinkedHashSet<String> diagnosisOrder = new java.util.LinkedHashSet<>();
        /** #739 : le dernier appel journalisé de la session, parent du suivant. */
        private String lastCallId = "";
        /** #283 : les pixels des références du projet, joints à chaque passe payante. */
        private final List<com.lodygames.rpgquest.panel.ai.AiProvider.Image> references;
        /** #747 : la mémoire structurée des tentatives — versions, erreurs résolues, régressions. */
        private AttemptMemory memory = AttemptMemory.empty();
        /** #747 : les verdicts déjà rendus dans cette session, par version : repartir d'une version sans la rejuger. */
        private final Map<String, Judgement> judged = new java.util.HashMap<>();
        /** #747 : une passe de complétude du brief a-t-elle déjà été accordée hors niveau de qualité ? */
        private boolean completenessDone;

        Session(Run run, BuildingProject project) {
            this.run = run;
            this.project = project;
            this.quality = Quality.of(run.quality());
            // #568 : pour une arche, le contrat de portail suit le brief de l'administrateur.
            this.demand = new BuildingPromptBuilder.Demand(project.id(), project.name(), project.front(),
                    project.briefForAi(), project.maxWidth(), project.maxDepth(), project.maxHeight(),
                    project.buildingId());
            this.spent = run.spentCalls();
            this.references = referencesOf(project.id());
            // « Repartir de zéro » n'hérite d'aucune erreur ; sinon, les versions jugées du projet sont la mémoire.
            if (!run.fresh()) {
                this.memory = AttemptMemory.fromVersions(project, drafts.forProject(run.agentId(), project.id()),
                        workbench);
            }
        }

        void go() {
            if (journal != null) {
                Map<String, Object> settings = new java.util.LinkedHashMap<>();
                settings.put("provider", run.providerId());
                settings.put("quality", run.quality());
                settings.put("maxCalls", run.maxCalls());
                settings.put("fresh", run.fresh());
                settings.put("rewriteDesign", run.rewriteDesign());
                settings.put("referenceImages", references.size());
                journal.runStart(project.id(), run.id(), run.createdBy(), settings);
            }
            // #283 : « repartir de zéro » n'hérite d'aucune version — ni plan, ni erreur.
            BuildingPlanDraft current = run.fresh() ? null
                    : drafts.forProject(run.agentId(), project.id()).stream()
                            .filter(BuildingPlanDraft::hasPlan).findFirst().orElse(null);
            // #289 : la conception détaillée d'abord, quand on part du brief — c'est elle qui fixe
            // pièces, palette, accès, mobilier et décoration ; le plan la traduit ensuite.
            if (!prepareDesign(current == null)) {
                return;
            }
            String lastCodes = null;
            int sameRefusals = 0;
            while (true) {
                if (current == null) {
                    if (!canSpend()) {
                        stop("Enveloppe épuisée avant le premier plan.");
                        return;
                    }
                    current = paid("CONCEPTION", (provider, d) -> studio.propose(provider, d, references),
                            BuildingPlanDraft.Origin.AI_PROPOSAL, "", "", List.of("Demande neuve : brief, "
                                    + "contraintes et conception en vigueur ; aucun plan ni diagnostic antérieur."));
                    if (current == null) {
                        return;
                    }
                }
                Judgement j = judge(current);
                if (!j.reached()) {
                    stop("Le serveur n'a pas rendu de verdict dans le délai : la session s'arrête plutôt "
                            + "que de payer à l'aveugle. Vérifiez que l'agent du serveur est en ligne.");
                    return;
                }
                if (j.repaired() != null) {
                    current = j.repaired();
                    j = judge(current);
                    if (!j.reached()) {
                        stop("Le serveur n'a pas jugé la version réparée dans le délai.");
                        return;
                    }
                }
                if (j.verdict().accepted()) {
                    BriefRequirements.Report compliance = compliance(current, j);
                    // #747 : une version conforme mais MOINS fidèle au brief que la précédente n'est pas
                    // promue : la géométrie ne remplace pas ce que le brief demande.
                    if (best != null && best != current && judged.containsKey(best.id())
                            && compliance.missing().size() > compliance(best, judged.get(best.id())).missing().size()) {
                        record(new Pass("NOTE", current.id(), current.version(), "", 0, 0, "Version "
                                + shortId(current.id()) + " conforme mais moins fidèle au brief que " + shortId(best.id())
                                + " (" + compliance.summary() + ") : elle n'est pas retenue."));
                        current = best;
                        j = judged.get(best.id());
                        compliance = compliance(current, j);
                    }
                    best = current;
                    boolean completeness = !compliance.complete() && !completenessDone;
                    if ((critiques < quality.critiques() || completeness) && canSpend()) {
                        if (critiques >= quality.critiques()) {
                            completenessDone = true;
                        }
                        critiques++;
                        critiqueFixed = false;
                        BuildingPlanDraft accepted = current;
                        String measured = metrics(j.verdict());
                        String remarks = String.join("\n", j.verdict().warnings());
                        String requirements = compliance.promptBlock();
                        String kind = completeness && critiques > quality.critiques() ? "COMPLÉTUDE DU BRIEF" : "CRITIQUE";
                        BuildingPlanDraft improved = paid(kind, (provider, d) -> studio.critique(
                                provider, d, accepted.plan(), measured, remarks, references, requirements),
                                BuildingPlanDraft.Origin.AI_CORRECTION, accepted.id(), "", List.of(
                                        "Plan conforme " + shortId(accepted.id()) + " transmis avec les mesures et "
                                                + "les remarques du serveur.",
                                        "Conformité au brief transmise : " + compliance.summary(),
                                        omitted("")));
                        if (improved == null) {
                            succeed();
                            return;
                        }
                        current = improved;
                        continue;
                    }
                    succeed();
                    return;
                }
                // ---- Refusé ----------------------------------------------------------------------
                pitfalls.add(PlanVerdictText.headline(j.verdict(), false) + " — "
                        + String.join(", ", j.verdict().codes()));
                if (best != null) {
                    // #655 : une critique a cassé un plan conforme. Elle a droit à UNE correction ; si
                    // celle-ci échoue aussi, on revient au conforme — le 20/10/2026, dix corrections
                    // payantes de suite ont poursuivi une critique ratée jusqu'à épuiser l'enveloppe.
                    if (critiqueFixed) {
                        record(new Pass("NOTE", best.id(), best.version(), "", 0, 0,
                                "La correction de l'amélioration est refusée elle aussi : retour à la "
                                        + "version conforme " + best.id().substring(0, Math.min(8,
                                        best.id().length())) + ", qui reste le résultat. Aucun appel de "
                                        + "plus pour cette amélioration."));
                        current = best;
                        continue;
                    }
                    critiqueFixed = true;
                    record(new Pass("NOTE", current.id(), current.version(), "", 0, 0,
                            "L'amélioration a été refusée ; une seule correction est tentée, la version "
                                    + "conforme précédente reste le résultat si elle échoue."));
                }
                if (!canSpend()) {
                    stop(best != null ? null : "Enveloppe épuisée : le dernier plan est encore refusé ("
                            + String.join(", ", j.verdict().codes()) + ").");
                    return;
                }
                // #747 : la stagnation est bornée — une conception neuve, puis l'arrêt, dit comme tel.
                boolean stagnating = best == null && memory.stagnating();
                if (stagnating && freshDesignDone) {
                    stop("Stagnation : " + memory.sinceImprovement() + " tentatives de suite sans faire mieux que "
                            + memory.bestRefused().map(AttemptMemory.Attempt::shortId).orElse("la meilleure version")
                            + ", malgré une conception neuve. La session s'arrête au lieu de payer la même réponse ; "
                            + "les versions produites restent consultables.");
                    return;
                }
                String codes = String.join(",", j.verdict().codes());
                sameRefusals = codes.equals(lastCodes) ? sameRefusals + 1 : 0;
                lastCodes = codes;
                BuildingPlanDraft refused = current;
                Judgement base = j;
                // #747 : un candidat qui réintroduit une erreur résolue n'est pas promu : la correction
                // suivante repart de la meilleure version refusée, et la raison est écrite.
                if (best == null) {
                    String candidate = current.id();
                    var regressions = memory.regressionsOf(candidate);
                    var baseline = memory.bestRefused().filter(b -> !b.draftId().equals(candidate));
                    if (!regressions.isEmpty() && baseline.isPresent()) {
                        Optional<BuildingPlanDraft> back = drafts.find(baseline.get().draftId());
                        Judgement again = back.map(d -> judged.containsKey(d.id()) ? judged.get(d.id()) : judge(d))
                                .orElse(null);
                        if (back.isPresent() && again != null && again.reached() && !again.verdict().accepted()) {
                            record(new Pass("NOTE", current.id(), current.version(), "", 0, 0, "Candidat "
                                    + shortId(current.id()) + " non promu : il réintroduit " + String.join(", ",
                                    regressions.stream().map(r -> r.code() + " (résolu en " + r.resolvedBy().shortId()
                                            + ")").toList()) + ". La correction suivante repart de "
                                    + baseline.get().shortId() + " (" + String.join(", ", baseline.get().codes()) + ")."));
                            refused = back.get();
                            base = again;
                        }
                    }
                }
                // #655 : un diagnostic déjà reçu — même texte, mêmes coordonnées — revient. Le corriger
                // une fois de plus, c'est payer la même réponse : on change d'approche une fois, puis
                // on s'arrête en le disant.
                boolean repeated = !seenDiagnoses.add(BuildingPlanDraftStore.sha256(
                        String.join(",", j.verdict().codes()) + "\n" + j.diagnosis()));
                if (repeated && best == null && freshDesignDone) {
                    stop("Le serveur renvoie un diagnostic déjà reçu malgré une conception neuve : l'IA "
                            + "tourne en rond (" + codes + "). La session s'arrête au lieu de payer la même "
                            + "réponse ; les versions produites restent consultables.");
                    return;
                }
                if ((sameRefusals >= 1 || repeated || stagnating) && best == null) {
                    freshDesignDone = true;
                    // Deux refus identiques de suite : corriger encore produirait une variante du même
                    // défaut. On repart du brief, avec les écueils déjà rencontrés.
                    sameRefusals = 0;
                    lastCodes = null;
                    BuildingPromptBuilder.Demand fresh = withPitfalls();
                    current = paid("CONCEPTION NEUVE", (provider, d) -> studio.propose(provider, fresh,
                            references),
                            BuildingPlanDraft.Origin.AI_PROPOSAL, "", "", List.of(
                                    "Conception neuve depuis le brief : le plan refusé " + shortId(refused.id())
                                            + " n'est PAS transmis.",
                                    pitfalls.size() + " écueil(s) ajouté(s) au brief, sous forme RÉSUMÉE (titre + "
                                            + "codes) : le texte exact des diagnostics n'est pas transmis.",
                                    omitted("")));
                } else {
                    BuildingPlanDraft from = refused;
                    String motive = base.diagnosis();
                    String measured = metrics(base.verdict());
                    String sent = fingerprint(base);
                    BriefRequirements.Report compliance = compliance(from, base);
                    BuildingPromptBuilder.RepairMode mode = memory.sinceImprovement() >= 2
                            || !memory.regressionsOf(current.id()).isEmpty() || !compliance.complete()
                            ? BuildingPromptBuilder.RepairMode.IMPROVE : BuildingPromptBuilder.RepairMode.REPAIR;
                    BuildingPromptBuilder.Repair repair = BuildingPromptBuilder.Repair.of(mode,
                            memory.promptBlock(from.id()), compliance.promptBlock());
                    current = paid("CORRECTION", (provider, d) -> studio.correct(provider, d,
                            from.plan(), motive, measured, references, repair),
                            BuildingPlanDraft.Origin.AI_CORRECTION, from.id(), sent, List.of(
                                    "Plan refusé " + shortId(from.id()) + " transmis avec le diagnostic exact "
                                            + "de sa vérification (empreinte " + sent + ") et ses mesures.",
                                    "Mémoire des tentatives transmise (résumé borné) : " + memory.summary(),
                                    "Conformité au brief : " + compliance.summary(),
                                    "Mode : " + mode.label() + ".",
                                    omitted(sent)));
                }
                if (current == null) {
                    return;
                }
            }
        }

        /**
         * La conception en vigueur, ou une conception neuve si l'on part du brief sans en avoir
         * (ou si l'on repart de zéro). {@code false} si la session doit s'arrêter.
         */
        private boolean prepareDesign(boolean fromBrief) {
            if (designDocs == null) {
                return true;
            }
            // #299 : « Repartir de zéro » repart du PLAN, pas de la conception. La version en vigueur
            // — souvent corrigée à la main — est conservée ; seule une demande explicite la réécrit.
            var current = run.rewriteDesign() ? java.util.Optional.<com.lodygames.rpgquest.panel.building
                    .BuildingDesignDocStore.Doc>empty() : designDocs.latest(project.id());
            String text = current.map(com.lodygames.rpgquest.panel.building.BuildingDesignDocStore.Doc::text)
                    .orElse("");
            current.ifPresent(doc -> runs.addPass(run.id(), new Pass("CONCEPTION EN VIGUEUR", "", doc.version(), "",
                    0, 0, "Conception version " + doc.version() + " (" + doc.origin().label().toLowerCase(
                            java.util.Locale.ROOT) + ") transmise à chaque passe, empreinte "
                            + shortSha(doc.text()) + "."), true));
            if (text.isEmpty() && fromBrief && canSpend()) {
                AiBuildingStudio.Proposal p = studio.design(run.providerId(), demand, references);
                spent++;
                int[] tokens = tokens(p.usage());
                audit.record(run.createdBy(), "building.design.write", "project=" + project.id() + " run="
                                + run.id() + " provider=" + p.providerId() + " model=" + p.model() + " usage="
                                + p.usage(), p.callOk() ? "OK" : "FAILED", p.callOk() ? "" : p.error(), rid());
                journalCall("CONCEPTION DÉTAILLÉE", "", "", List.of("Rédaction de la conception détaillée depuis "
                        + "le brief et les contraintes ; aucun plan ni diagnostic transmis."), p, "", 0);
                if (!p.callOk()) {
                    runs.addPass(run.id(), new Pass("CONCEPTION DÉTAILLÉE", "", 0, "", 0, 0,
                            "Appel échoué : " + p.error()), true);
                    stop("L'appel au fournisseur d'IA a échoué : " + p.error());
                    return false;
                }
                var saved = designDocs.save(project.id(), p.rawResponse(),
                        com.lodygames.rpgquest.panel.building.BuildingDesignDocStore.Origin.AI, p.providerId(),
                        p.model(), p.usage(), run.createdBy(), -1);
                runs.addPass(run.id(), new Pass("CONCEPTION DÉTAILLÉE", "", 0, nz(p.model()), tokens[0], tokens[1],
                        saved.doc().map(d -> "Conception rédigée (version " + d.version() + "), consultable dans "
                                + "« Conception détaillée ».").orElse(saved.refusal())), true);
                text = saved.doc().map(com.lodygames.rpgquest.panel.building.BuildingDesignDocStore.Doc::text)
                        .orElse("");
            }
            demand = demand.withDesign(text);
            return true;
        }

        private boolean canSpend() {
            return spent < Math.min(MAX_CALLS_CEILING, run.maxCalls());
        }

        /**
         * Un appel payant : compté avant tout examen, conservé comme version même s'il ne contient
         * aucun plan, puis vérifié. {@code null} si la session doit s'arrêter.
         */
        private BuildingPlanDraft paid(String kind,
                                       BiFunction<String, BuildingPromptBuilder.Demand,
                                               AiBuildingStudio.Proposal> call,
                                       BuildingPlanDraft.Origin origin, String parentId, String inputDiagnosis,
                                       List<String> context) {
            AiBuildingStudio.Proposal p = call.apply(run.providerId(), demand);
            spent++;
            int[] tokens = tokens(p.usage());
            audit.record(run.createdBy(), "building.plan.generate", "project=" + project.id()
                            + " run=" + run.id() + " pass=" + kind + " provider=" + p.providerId()
                            + " model=" + p.model() + " usage=" + p.usage(),
                    p.callOk() ? "OK" : "FAILED", p.callOk() ? (p.hasPlan() ? "plan proposé"
                            : "aucun plan exploitable") : p.error(), rid());
            if (!p.callOk()) {
                journalCall(kind, parentId, inputDiagnosis, context, p, "", 0);
                runs.addPass(run.id(), new Pass(kind, "", 0, "", 0, 0, "Appel échoué : " + p.error()), true);
                stop("L'appel au fournisseur d'IA a échoué : " + p.error());
                return null;
            }
            BuildingPlanDraft created = drafts.create(new BuildingPlanDraftStore.NewDraft(run.agentId(), "",
                    p.hasPlan() ? p.plan() : "", origin, parentId, "", p.providerId(), p.providerLabel(),
                    p.model(), p.usage(), p.rawResponse(), p.extractionNote(), demand.maxWidth(),
                    demand.maxDepth(), demand.maxHeight(), run.createdBy(), project.id()));
            projects.touch(project.id(), run.createdBy());
            journalCall(kind, parentId, inputDiagnosis, context, p, created.id(), created.version());
            // #289 : la critique dit d'abord ce qui manquait au brief et à la conception — c'est
            // gardé dans le journal, consultable, au lieu de se perdre avec la réponse brute.
            String findings = "CRITIQUE".equals(kind) ? BuildingPromptBuilder.critiqueFindings(p.rawResponse()) : "";
            runs.addPass(run.id(), new Pass(kind, created.id(), created.version(), nz(p.model()), tokens[0],
                    tokens[1], (p.hasPlan() ? "Nouvelle version proposée." : "Réponse sans plan exploitable.")
                            + (findings.isEmpty() ? "" : " " + findings)),
                    true);
            if (!p.hasPlan()) {
                stop("Le modèle a répondu sans plan exploitable ; sa réponse est conservée.");
                return null;
            }
            if (!seenPlans.add(BuildingPlanDraftStore.sha256(p.plan().trim()))) {
                stop("Le modèle a renvoyé un plan déjà essayé : la correction ne progresse plus.");
                return null;
            }
            return created;
        }

        /** Vérifie une version (si ce n'est déjà fait) et attend le verdict du serveur. */
        private Judgement judge(BuildingPlanDraft draft) {
            seenPlans.add(BuildingPlanDraftStore.sha256(draft.plan().trim()));
            var state = workbench.state(draft, BuildingPlanDraftStore.Check.AUDIT);
            if (!state.current()) {
                Optional<String> refused = autopilot.verify(draft, run.createdBy());
                if (refused.isPresent()) {
                    return new Judgement(false, BuildingPlanVerdict.none(), "", null);
                }
                state = workbench.state(draft, BuildingPlanDraftStore.Check.AUDIT);
            }
            Instant deadline = Instant.now().plus(verdictTimeout);
            while (state.status() != BuildingPlanWorkbench.Status.DONE && Instant.now().isBefore(deadline)) {
                sleep(500);
                state = workbench.state(draft, BuildingPlanDraftStore.Check.AUDIT);
            }
            if (state.status() != BuildingPlanWorkbench.Status.DONE) {
                record(new Pass("VÉRIFICATION", draft.id(), draft.version(), "", 0, 0,
                        "Pas de réponse du serveur."));
                return new Judgement(false, BuildingPlanVerdict.none(), "", null);
            }
            BuildingPlanVerdict verdict = BuildingPlanVerdict.from(state.details(),
                    state.row().map(AgentActionRow::resultMessage).orElse(""));
            String diagnosis = com.lodygames.rpgquest.panel.building.PlanDiagnosis.of(project, state);
            record(new Pass("VÉRIFICATION", draft.id(), draft.version(), "", 0, 0,
                    verdict.accepted() ? "Conforme." : PlanVerdictText.headline(verdict, false)
                            + " (" + String.join(", ", verdict.codes()) + ")"));
            if (journal != null) {
                journal.validation(project.id(), run.id(), run.createdBy(), draft.id(), draft.version(),
                        verdict.accepted(), verdict.codes(), PlanVerdictText.headline(verdict, false), diagnosis,
                        metrics(verdict), verdict.warnings(), state.row().map(AgentActionRow::id).orElse(""),
                        state.row().map(AgentActionRow::completedAt).orElse(null), "vérification de la session");
                if (!verdict.accepted()) {
                    diagnosisOrder.add(BuildingAiJournal.fingerprint(verdict.codes(), diagnosis));
                }
            }
            BuildingPlanDraft repaired = null;
            if (!verdict.accepted() && draft.origin() != BuildingPlanDraft.Origin.ENGINE_REPAIR
                    && WorkshopAutopilot.EngineRepair.from(state.details()).isPresent()) {
                String auditId = state.row().map(AgentActionRow::id).orElse("");
                repaired = drafts.repairedFrom(auditId).or(() -> autopilot.repairFrom(auditId))
                        .or(() -> drafts.repairedFrom(auditId)).orElse(null);
                if (repaired != null) {
                    record(new Pass("MOTEUR", repaired.id(), repaired.version(), "", 0, 0,
                            "Réparé par le moteur, sans appel à l'IA."));
                    if (journal != null) {
                        journal.note(project.id(), run.id(), run.createdBy(), "ENGINE_REPAIR", repaired.id(),
                                "Version " + shortId(repaired.id()) + " réparée par le moteur à partir de "
                                        + shortId(draft.id()) + ", sans appel à l'IA. " + nz(repaired.extractionNote()));
                    }
                }
            }
            Judgement judgement = new Judgement(true, verdict, diagnosis, repaired);
            judged.put(draft.id(), judgement);
            memory = memory.with(new AttemptMemory.Attempt(draft.id(), draft.version(), verdict.accepted(),
                    verdict.codes(), verdict.accepted() ? List.of() : AttemptMemory.issuesOf(verdict.codes(), diagnosis)));
            return judgement;
        }

        /** #747 : la conformité d'une version au brief, sur son plan et sur la palette mesurée par le serveur. */
        private BriefRequirements.Report compliance(BuildingPlanDraft draft, Judgement j) {
            Object palette = j == null ? null : j.verdict().metrics().get("palette");
            return BriefRequirements.evaluate(project.brief(), draft.plan(),
                    palette instanceof Map<?, ?> map ? castPalette(map) : Map.of());
        }

        private BuildingPromptBuilder.Demand withPitfalls() {
            String brief = demand.brief() + "\n\nÉCUEILS DES ESSAIS PRÉCÉDENTS — conçois autrement pour "
                    + "les éviter :\n- " + String.join("\n- ", pitfalls);
            return demand.withBrief(brief);
        }

        private void record(Pass pass) {
            runs.addPass(run.id(), pass, false);
            if (journal != null && "NOTE".equals(pass.kind())) {
                journal.note(project.id(), run.id(), run.createdBy(), "NOTE", pass.draftId(), pass.verdict());
            }
        }

        /** #739 : un appel de la session, dans le journal IA, chaîné au précédent. */
        private void journalCall(String kind, String inputDraftId, String inputDiagnosis, List<String> context,
                                 AiBuildingStudio.Proposal p, String producedDraftId, int producedVersion) {
            if (journal == null) {
                return;
            }
            lastCallId = journal.call(project.id(), new BuildingAiJournal.CallContext(run.id(), kind, spent,
                    run.createdBy(), inputDraftId, lastCallId, inputDiagnosis, context), p, producedDraftId,
                    producedVersion);
        }

        /** L'empreinte du diagnostic d'un verdict, comme le journal la calcule. */
        private String fingerprint(Judgement j) {
            return BuildingAiJournal.fingerprint(j.verdict().codes(), j.diagnosis());
        }

        /** Les diagnostics de refus déjà reçus dans la session et NON transmis par cet appel. */
        private String omitted(String transmitted) {
            List<String> others = diagnosisOrder.stream().filter(fp -> !fp.equals(transmitted)).toList();
            return others.isEmpty() ? "Aucun autre diagnostic de refus reçu dans cette session."
                    : "Diagnostics de refus antérieurs de la session NON transmis à cet appel : "
                            + String.join(", ", others) + ".";
        }

        private void succeed() {
            runs.finish(run.id(), BuildingDesignRunStore.Status.SUCCEEDED,
                    "Plan conforme en " + spent + " appel(s) à l'IA" + (critiques > 0
                            ? ", dont " + critiques + " passe(s) de critique architecturale." : ".")
                            + briefVerdict(),
                    best.id());
            journalEnd(run.id());
        }

        /**
         * #747 : ce que la version retenue réalise du brief. « Conforme » est un verdict TECHNIQUE ; un
         * bâtiment sans ce que le brief demande est dit incomplet, jamais réussi en silence.
         */
        private String briefVerdict() {
            if (best == null) {
                return "";
            }
            BriefRequirements.Report r = compliance(best, judged.get(best.id()));
            if (r.isEmpty()) {
                return "";
            }
            return r.complete() ? " Brief : " + r.summary() + "."
                    : " INCOMPLET vis-à-vis du brief — " + r.summary() + ".";
        }

        /** Arrêt : avec un plan conforme déjà obtenu, c'est un succès partiel qu'on dit comme tel. */
        private void stop(String why) {
            if (best != null) {
                runs.finish(run.id(), BuildingDesignRunStore.Status.SUCCEEDED, "Plan conforme obtenu ; "
                        + (why == null ? "l'enveloppe ne permettait pas d'autre passe." : why
                                + " La dernière version conforme est le résultat.") + briefVerdict(), best.id());
                journalEnd(run.id());
                return;
            }
            runs.finish(run.id(), BuildingDesignRunStore.Status.STOPPED, why, "");
            journalEnd(run.id());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castPalette(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /** Les images de référence du projet, en pixels, prêtes à joindre à un appel (#283). */
    private List<AiProvider.Image> referencesOf(String projectId) {
        List<AiProvider.Image> out = new ArrayList<>();
        if (images == null) {
            return out;
        }
        for (var info : images.list(projectId)) {
            images.data(projectId, info.id()).ifPresent(bytes -> out.add(new AiProvider.Image(info.mime(), bytes)));
        }
        return out;
    }

    /** Les mesures du serveur, en lignes pour le modèle. */
    private static String metrics(BuildingPlanVerdict verdict) {
        return BuildingCorrectionLoop.formatMetrics(verdict.metrics());
    }

    /** Jetons d'entrée et de sortie, lus dans le résumé du fournisseur ; 0 s'ils sont inconnus. */
    static int[] tokens(String usage) {
        Matcher m = USAGE.matcher(usage == null ? "" : usage);
        return m.find() ? new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))}
                : new int[] {0, 0};
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("conception interrompue");
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String shortId(String id) {
        return id == null ? "" : id.substring(0, Math.min(8, id.length()));
    }

    private static String rid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ---- L'enveloppe annoncée ------------------------------------------------------------------

    /**
     * Le coût estimé d'une session, annoncé AVANT le consentement.
     *
     * <p>Les tarifs sont ceux publiés par les fournisseurs pour les modèles connus, en dollars par
     * million de jetons ; un modèle inconnu n'a pas de prix inventé — l'écran dit alors « tarif
     * inconnu » et ne montre que les jetons. Les jetons par appel viennent des appels réels du
     * projet quand il y en a, sinon d'une hypothèse prudente.</p>
     */
    public record Envelope(int calls, int tokensIn, int tokensOut, double dollars, boolean priced,
                           String model) {

        public String label() {
            String tokens = String.format(Locale.FRANCE, "%,d jetons d'entrée et %,d de sortie au plus",
                    tokensIn, tokensOut);
            return calls + " appel(s) à l'IA au plus — " + tokens + (priced
                    ? String.format(Locale.FRANCE, ", soit environ %.2f $ au tarif public de %s", dollars, model)
                    : " (tarif de « " + model + " » inconnu du panel : seuls les jetons sont annoncés)");
        }
    }

    /** Tarifs publics connus ($ / million de jetons : entrée, sortie). */
    private static final Map<String, double[]> PRICES = Map.of(
            "gpt-4o-mini", new double[] {0.15, 0.60},
            "gpt-4o", new double[] {2.50, 10.00},
            "gpt-4.1-mini", new double[] {0.40, 1.60},
            "gpt-4.1", new double[] {2.00, 8.00});

    public static Envelope envelope(int calls, String model, int observedIn, int observedOut) {
        return envelope(calls, model, observedIn, observedOut, 0, 0);
    }

    /** Caractères par jeton, estimation PRUDENTE pour du français et du YAML (#292). */
    static final int CHARS_PER_TOKEN = 3;

    /** Jetons comptés par image de référence : le haut de la fourchette des fournisseurs. */
    static final int IMAGE_TOKENS = 1_600;

    /**
     * L'enveloppe, calculée sur le contenu RÉEL de la demande (#292) : le prompt système, la
     * description du projet — jusqu'à 12 000 caractères — et ses images. Une passe de correction
     * relit en plus le plan précédent et le diagnostic. L'ancienne estimation fixe (9 000 jetons
     * d'entrée par appel) sous-estimait une description longue : le consentement portait sur
     * moins que ce qui serait facturé.
     */
    public static Envelope envelope(int calls, String model, int observedIn, int observedOut,
                                    int briefChars, int images) {
        int perOut = Math.max(observedOut > 0 ? observedOut * 2 : 0, 4_000);
        int prompt = (BuildingPromptBuilder.systemPrompt().length() + 1_500 + Math.max(0, briefChars))
                / CHARS_PER_TOKEN + Math.max(0, images) * IMAGE_TOKENS;
        int perIn = Math.max(observedIn > 0 ? observedIn * 2 : 0, Math.max(9_000, prompt + perOut + 2_000));
        String m = model == null || model.isBlank() ? "gpt-4o" : model.trim().toLowerCase(Locale.ROOT);
        double[] price = null;
        String bestKey = "";
        for (var e : PRICES.entrySet()) {
            if (m.startsWith(e.getKey()) && e.getKey().length() > bestKey.length()) {
                bestKey = e.getKey();
                price = e.getValue();
            }
        }
        int in = calls * perIn;
        int out = calls * perOut;
        double dollars = price == null ? 0 : (in * price[0] + out * price[1]) / 1_000_000.0;
        return new Envelope(calls, in, out, dollars, price != null, m);
    }

    /** Empreinte courte d'un texte, pour tracer la conception utilisée sans la recopier (#299). */
    static String shortSha(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 6);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
