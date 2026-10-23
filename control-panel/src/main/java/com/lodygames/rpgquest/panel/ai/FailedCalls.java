package com.lodygames.rpgquest.panel.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Les appels échoués d'un projet, relus dans son journal IA (#747, retour du 23/10/2026).
 *
 * <p>Le journal est la seule trace d'un appel échoué : aucune version n'est créée. La page du projet
 * y relit le dernier échec pour proposer « Réessayer avec le même contexte », et une reprise y
 * vérifie — par les empreintes de son inventaire — qu'elle envoie bien le même contexte que l'appel
 * échoué.</p>
 */
public final class FailedCalls {

    /** Les pièces de contexte comparées entre l'appel échoué et sa reprise. */
    static final List<String> PIECES = List.of("brief", "design", "previousPlan", "refusal", "memory",
            "requirements", "palette");

    /**
     * Le dernier appel échoué, s'il est aussi le dernier appel du projet.
     *
     * @param keptDraftId la version restée active ; vide pour une demande neuve
     */
    public record LastFailure(String callId, Instant at, String operation, String kind, String label, String detail,
                              String keptDraftId, String gesture, String mode, String notes, String providerId) {
    }

    private FailedCalls() {
    }

    /** Le dernier appel du projet, s'il a échoué ; rien s'il a abouti ou s'il n'y en a aucun. */
    @SuppressWarnings("unchecked")
    public static Optional<LastFailure> last(List<AiJournalStore.Event> events) {
        AiJournalStore.Event outcome = null;
        for (AiJournalStore.Event e : events == null ? List.<AiJournalStore.Event>of() : events) {
            if (List.of("RESPONSE", "CALL_ERROR", "CALL_NOT_SENT").contains(e.kind())) {
                outcome = e;
            }
        }
        if (outcome == null || !(outcome.body().get("failure") instanceof Map<?, ?> raw)) {
            return Optional.empty();
        }
        Map<String, Object> f = (Map<String, Object>) raw;
        Map<String, Object> retry = f.get("retry") instanceof Map<?, ?> r ? (Map<String, Object>) r : Map.of();
        return Optional.of(new LastFailure(outcome.callId(), outcome.at(), outcome.operation(), str(f, "kind"),
                str(f, "label"), str(f, "detail"), str(f, "activeVersionKept"), str(retry, "gesture"),
                str(retry, "mode"), str(retry, "notes"), str(retry, "provider")));
    }

    /**
     * La reprise envoie-t-elle le même contexte que l'appel échoué ? Compare, pièce par pièce, les
     * empreintes de l'inventaire de la requête échouée et de la nouvelle, et la liste des images.
     */
    @SuppressWarnings("unchecked")
    public static String sameContext(List<AiJournalStore.Event> events, String failedCallId,
                                     Map<String, Object> manifest) {
        Map<String, Object> before = null;
        for (AiJournalStore.Event e : events == null ? List.<AiJournalStore.Event>of() : events) {
            if (Objects.equals(e.callId(), failedCallId) && ("REQUEST".equals(e.kind())
                    || "CALL_NOT_SENT".equals(e.kind())) && e.body().get("manifest") instanceof Map<?, ?> m) {
                before = (Map<String, Object>) m;
            }
        }
        if (before == null) {
            return "Reprise de l'appel échoué " + failedCallId + " : sa requête n'est plus dans le journal, "
                    + "contexte non comparé.";
        }
        Map<String, Object> after = manifest == null ? Map.of() : manifest;
        List<String> same = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (String piece : PIECES) {
            String a = sha(before.get(piece));
            String b = sha(after.get(piece));
            if (a.isEmpty() && b.isEmpty()) {
                continue;
            }
            (a.equals(b) ? same : changed).add(piece);
        }
        (Objects.equals(images(before), images(after)) ? same : changed).add("images");
        (Objects.equals(String.valueOf(before.get("mode")), String.valueOf(after.get("mode"))) ? same : changed)
                .add("mode");
        return "Reprise de l'appel échoué " + failedCallId + " avec le même contexte — identiques : "
                + (same.isEmpty() ? "aucune pièce" : String.join(", ", same))
                + (changed.isEmpty() ? "" : " ; DIFFÉRENTES depuis l'échec : " + String.join(", ", changed)) + ".";
    }

    private static String sha(Object piece) {
        return piece instanceof Map<?, ?> m && m.get("sha256") != null ? String.valueOf(m.get("sha256")) : "";
    }

    private static List<String> images(Map<String, Object> manifest) {
        List<String> out = new ArrayList<>();
        if (manifest.get("images") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add(String.valueOf(m.get("sha256")));
                }
            }
        }
        return out;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}
