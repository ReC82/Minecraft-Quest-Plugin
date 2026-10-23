package com.lodygames.rpgquest.panel.ai;

import com.lodygames.rpgquest.panel.ai.AiJournalStore.NewEvent;
import com.lodygames.rpgquest.panel.building.BuildingProject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Le journal IA d'un projet de l'Atelier de bâtiments (issue #739) : ce que les appelants — la
 * conception automatique et les gestes manuels — écrivent dans {@link AiJournalStore}.
 *
 * <p>Pour chaque appel, deux événements liés par le même identifiant d'appel : la
 * <strong>requête</strong> (horodatée au départ réel, avec l'inventaire de son contexte) et la
 * <strong>réponse</strong> ou l'<strong>erreur</strong> (horodatée à l'arrivée, avec sa durée). Puis
 * la <strong>vérification</strong> de la version produite, avec le diagnostic exact du serveur et
 * son empreinte — celle-là même que la conception automatique compare pour repérer un diagnostic
 * déjà reçu (#655).</p>
 *
 * <p>Le journal ne doit jamais faire échouer ce qu'il observe : une écriture impossible est
 * signalée dans le journal système du panel, et le geste continue.</p>
 */
public final class BuildingAiJournal {

    /** Le scope des projets de l'Atelier dans {@link AiJournalStore}. */
    public static final String SCOPE = "building";

    private static final System.Logger LOG = System.getLogger(BuildingAiJournal.class.getName());

    private final AiJournalStore store;

    public BuildingAiJournal(AiJournalStore store) {
        this.store = store;
    }

    public AiJournalStore store() {
        return store;
    }

    /**
     * Le contexte d'un appel, connu de l'appelant seulement.
     *
     * @param operation      CONCEPTION, CORRECTION, CRITIQUE… préfixée de « MANUEL — » pour un geste
     * @param inputDraftId   la version que l'appel corrige ou améliore ; vide pour une demande neuve
     * @param parentCallId   l'appel précédent de la même session ; vide s'il n'y en a pas
     * @param inputDiagnosis empreinte du diagnostic transmis ; vide si aucun
     * @param context        ce que l'appelant sait du contexte transmis ou omis, phrase par phrase
     */
    public record CallContext(String runId, String operation, int attempt, String actor, String inputDraftId,
                              String parentCallId, String inputDiagnosis, List<String> context) {

        public CallContext {
            context = List.copyOf(context == null ? List.of() : context);
        }
    }

    /**
     * Un appel échoué (#747) : sa nature, la version qui reste active, et le contexte de reprise.
     *
     * @param keptDraftId la version source, inchangée et toujours active ; vide pour une demande neuve
     * @param gesture     le geste à rejouer : « correct » ou « propose »
     * @param mode        la nature de reprise demandée (« auto », « repair », « improve »)
     * @param notes       les consignes de l'administrateur, telles que saisies
     * @param retryOf     l'appel échoué que celui-ci reprenait déjà, s'il y en a un
     */
    public record FailedCall(AiCallFailure failure, String keptDraftId, String gesture, String mode, String notes,
                             String providerId, String retryOf) {

        public FailedCall {
            keptDraftId = nz(keptDraftId);
            gesture = nz(gesture);
            mode = nz(mode);
            notes = nz(notes);
            providerId = nz(providerId);
            retryOf = nz(retryOf);
        }

        Map<String, Object> body() {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("kind", failure.kind().name());
            b.put("label", failure.kind().label());
            b.put("detail", failure.detail());
            b.put("versionCreated", false);
            b.put("activeVersionKept", keptDraftId);
            Map<String, Object> retry = new LinkedHashMap<>();
            retry.put("gesture", gesture);
            retry.put("draftId", keptDraftId);
            retry.put("mode", mode);
            retry.put("notes", notes);
            retry.put("provider", providerId);
            b.put("retry", retry);
            b.put("retryOf", retryOf);
            return b;
        }
    }

    private static String shortId(String id) {
        return "V-" + id.substring(0, Math.min(8, id.length()));
    }

    /** Empreinte courte d'un diagnostic : codes et texte exact, comme la comparaison de #655. */
    public static String fingerprint(List<String> codes, String diagnosis) {
        return AiTrace.sha256(String.join(",", codes == null ? List.of() : codes) + "\n"
                + (diagnosis == null ? "" : diagnosis)).substring(0, 12);
    }

    /**
     * Journalise un appel : sa requête, puis sa réponse ou son échec. Rend l'identifiant d'appel.
     *
     * @param producedDraftId la version créée à partir de la réponse ; vide si aucune
     */
    public String call(String projectId, CallContext ctx, AiBuildingStudio.Proposal p, String producedDraftId,
                       int producedVersion) {
        return call(projectId, ctx, p, producedDraftId, producedVersion, null);
    }

    /**
     * Même chose pour un appel échoué (#747) : sa nature, la version restée active et de quoi le
     * reprendre à l'identique sont rangés dans l'événement de réponse — c'est ce que la page du projet
     * relit pour proposer « Réessayer avec le même contexte », et ce que le journal exporté montre.
     *
     * @param failure {@code null} si l'appel a produit une version
     */
    public String call(String projectId, CallContext ctx, AiBuildingStudio.Proposal p, String producedDraftId,
                       int producedVersion, FailedCall failure) {
        String callId = UUID.randomUUID().toString().substring(0, 8);
        try {
            AiProvider.Exchange x = p.exchange();
            Instant started = x == null ? Instant.now() : x.startedAt();
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("manifest", p.manifest());
            request.put("context", ctx.context());
            request.put("inputDiagnosis", nz(ctx.inputDiagnosis()));
            request.put("provider", nz(p.providerId()));
            if (x != null) {
                request.put("endpoint", x.endpoint());
                request.put("headers", x.headers());
                request.put("skeleton", x.requestSkeleton());
                request.put("texts", texts(x.requestTexts()));
                request.put("redactions", x.redactions());
            }
            if (x == null && failure != null) {
                request.put("failure", failure.body());
            }
            store.append(SCOPE, projectId, new NewEvent(x == null ? "CALL_NOT_SENT" : "REQUEST", callId, ctx.runId(),
                    ctx.operation(), ctx.attempt(), nz(ctx.inputDraftId()), parent(ctx), started, -1, ctx.actor(),
                    x == null ? "Appel non émis : " + nz(p.error()) : "Requête envoyée à " + nz(p.providerId()),
                    request));
            if (x == null) {
                return callId;
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("httpStatus", x.httpStatus());
            response.put("transportError", x.transportError());
            response.put("providerRequestId", x.providerRequestId());
            response.put("finishReason", x.finishReason());
            response.put("model", nz(p.model()));
            response.put("usage", nz(p.usage()));
            response.put("error", nz(p.error()));
            response.put("extractionNote", nz(p.extractionNote()));
            response.put("planExtracted", p.hasPlan());
            response.put("planSha256", p.hasPlan() ? AiTrace.sha256(p.plan().trim()).substring(0, 16) : "");
            response.put("producedVersion", producedVersion);
            response.put("skeleton", x.responseSkeleton());
            response.put("texts", texts(x.responseTexts()));
            String summary = failure != null ? "Appel échoué — " + failure.failure().kind().label()
                    + " ; aucune version créée" + (failure.keptDraftId().isEmpty() ? ""
                    : ", la version " + shortId(failure.keptDraftId()) + " reste la version active")
                    : p.callOk()
                    ? (p.hasPlan() ? "Réponse reçue, plan extrait" : "Réponse reçue, aucun plan exploitable")
                    : "Échec : " + p.error();
            if (failure != null) {
                response.put("failure", failure.body());
            }
            store.append(SCOPE, projectId, new NewEvent(p.callOk() ? "RESPONSE" : "CALL_ERROR", callId, ctx.runId(),
                    ctx.operation(), ctx.attempt(), nz(producedDraftId), "call:" + callId,
                    x.finishedAt() == null ? Instant.now() : x.finishedAt(), x.durationMillis(), ctx.actor(), summary,
                    response));
        } catch (RuntimeException broken) {
            warn(projectId, broken);
        }
        return callId;
    }

    /**
     * Le verdict du serveur sur une version : codes, diagnostic exact et empreinte.
     *
     * @param source d'où vient ce relevé (vérification de la session, ou relevé au moment d'une demande)
     */
    public void validation(String projectId, String runId, String actor, String draftId, int version,
                           boolean accepted, List<String> codes, String headline, String diagnosis, String metrics,
                           List<String> warnings, String auditActionId, Instant at, String source) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("accepted", accepted);
            body.put("codes", codes == null ? List.of() : codes);
            body.put("headline", nz(headline));
            body.put("diagnosis", nz(diagnosis));
            body.put("fingerprint", accepted ? "" : fingerprint(codes, diagnosis));
            body.put("metrics", nz(metrics));
            body.put("warnings", warnings == null ? List.of() : warnings);
            body.put("auditActionId", nz(auditActionId));
            body.put("version", version);
            body.put("source", nz(source));
            store.append(SCOPE, projectId, new NewEvent("VALIDATION", "", runId, "VÉRIFICATION", 0, draftId,
                    "draft:" + draftId, at == null ? Instant.now() : at, -1, actor,
                    accepted ? "Conforme" : "Refusé (" + String.join(", ", codes == null ? List.of() : codes) + ")",
                    body));
        } catch (RuntimeException failure) {
            warn(projectId, failure);
        }
    }

    /** Une note : réparation du moteur, retour à une version conforme, décision de la boucle… */
    public void note(String projectId, String runId, String actor, String kind, String draftId, String text) {
        try {
            store.append(SCOPE, projectId, new NewEvent(kind, "", runId, "", 0, nz(draftId),
                    draftId == null || draftId.isEmpty() ? "" : "draft:" + draftId, Instant.now(), -1, actor,
                    text, Map.of()));
        } catch (RuntimeException failure) {
            warn(projectId, failure);
        }
    }

    /** Début d'une conception automatique, avec l'enveloppe consentie. */
    public void runStart(String projectId, String runId, String actor, Map<String, Object> settings) {
        try {
            store.append(SCOPE, projectId, new NewEvent("RUN_START", "", runId, "CONCEPTION AUTOMATIQUE", 0, "", "",
                    Instant.now(), -1, actor, "Conception automatique démarrée", settings));
        } catch (RuntimeException failure) {
            warn(projectId, failure);
        }
    }

    /** Fin d'une conception automatique : statut, motif, version retenue, durée totale. */
    public void runEnd(String projectId, String runId, String actor, String status, String outcome,
                       String finalDraftId, long durationMs, int spentCalls) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", nz(status));
            body.put("outcome", nz(outcome));
            body.put("finalDraftId", nz(finalDraftId));
            body.put("spentCalls", spentCalls);
            store.append(SCOPE, projectId, new NewEvent("RUN_END", "", runId, "CONCEPTION AUTOMATIQUE", 0,
                    nz(finalDraftId), "", Instant.now(), durationMs, actor, nz(status) + " — " + nz(outcome), body));
        } catch (RuntimeException failure) {
            warn(projectId, failure);
        }
    }

    /** La fiche du projet a changé entre deux essais : brief, façade, emprise, type. */
    public void projectChanged(String projectId, String actor, BuildingProject before, BuildingProject after) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("before", fiche(before));
            body.put("after", fiche(after));
            body.put("briefAfter", after.brief());
            boolean briefChanged = !before.brief().equals(after.brief());
            store.append(SCOPE, projectId, new NewEvent("PROJECT_CHANGED", "", "", "FICHE DU PROJET", 0, "", "",
                    Instant.now(), -1, actor, briefChanged ? "Description (brief) et/ou contraintes modifiées"
                            : "Contraintes du formulaire modifiées", body));
        } catch (RuntimeException failure) {
            warn(projectId, failure);
        }
    }

    private static Map<String, Object> fiche(BuildingProject p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.name());
        m.put("front", p.front());
        m.put("maxWidth", p.maxWidth());
        m.put("maxDepth", p.maxDepth());
        m.put("maxHeight", p.maxHeight());
        m.put("buildingId", p.buildingId());
        m.put("kind", p.kind());
        m.put("briefChars", p.brief().length());
        m.put("briefSha256", AiTrace.sha256(p.brief().trim()).substring(0, 16));
        return m;
    }

    private static List<Object> texts(List<AiTrace.Text> texts) {
        List<Object> out = new ArrayList<>();
        for (AiTrace.Text t : texts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref", t.ref());
            m.put("path", t.path());
            m.put("role", t.role());
            m.put("content", t.content());
            out.add(m);
        }
        return out;
    }

    private static String parent(CallContext ctx) {
        List<String> parts = new ArrayList<>();
        if (ctx.inputDraftId() != null && !ctx.inputDraftId().isEmpty()) {
            parts.add("draft:" + ctx.inputDraftId());
        }
        if (ctx.parentCallId() != null && !ctx.parentCallId().isEmpty()) {
            parts.add("call:" + ctx.parentCallId());
        }
        return String.join(" ", parts);
    }

    private static void warn(String projectId, RuntimeException failure) {
        LOG.log(System.Logger.Level.WARNING, "event=ai_journal_write_failed project=" + projectId + " error="
                + failure.getClass().getSimpleName() + ": " + failure.getMessage());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
