package com.lodygames.rpgquest.panel.ai;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Un appel à l'IA qui n'a rien rendu d'exploitable, et pourquoi (#747, retour du 23/10/2026).
 *
 * <h2>Pourquoi</h2>
 *
 * <p>Le 23/10/2026 à 10:29:42, « Améliorer avec l'IA » sur l'Église du hub a reçu « I'm sorry, I can't
 * assist with that. » (HTTP 200, {@code finish_reason=stop}, 9 jetons). Cette réponse est devenue la
 * version V-14206aa8 — une version sans plan —, la page est revenue à « Créer un plan » avec un
 * bandeau vert, et le clic suivant est parti en demande neuve : sans le dernier plan, son diagnostic
 * ni la mémoire des corrections.</p>
 *
 * <p>Une réponse inexploitable n'est donc <strong>jamais</strong> une version : c'est un appel échoué,
 * typé, journalisé, et la version source reste la version active.</p>
 *
 * @param detail ce que le fournisseur a dit ou ce qui manque, en une phrase
 */
public record AiCallFailure(Kind kind, String detail) {

    /** Les natures d'échec que l'administrateur doit pouvoir distinguer. */
    public enum Kind {
        REFUSAL("Refus du modèle", "le modèle a répondu par un refus au lieu d'un plan"),
        EMPTY("Réponse vide", "le modèle n'a renvoyé aucun texte"),
        INVALID_FORMAT("Format invalide", "le modèle a répondu sans plan YAML exploitable (aucune liste « steps: »)"),
        TRUNCATED("Réponse tronquée", "la réponse a été coupée par la limite de longueur : un plan coupé est "
                + "inutilisable"),
        API_ERROR("Erreur réseau ou API", "l'appel n'a pas abouti chez le fournisseur");

        private final String label;
        private final String explanation;

        Kind(String label, String explanation) {
            this.label = label;
            this.explanation = explanation;
        }

        public String label() {
            return label;
        }

        public String explanation() {
            return explanation;
        }
    }

    public AiCallFailure {
        detail = detail == null ? "" : detail.strip();
    }

    /** Fins de réponse qui signifient « coupée par la limite de jetons », selon les fournisseurs. */
    private static final java.util.Set<String> LENGTH = java.util.Set.of("length", "max_tokens", "max_output_tokens");

    /** Un refus explicite de l'API (champ « refusal » renseigné). */
    private static final Pattern API_REFUSAL = Pattern.compile("\"refusal\"\\s*:\\s*\"");

    /** Les formules de refus courantes, en anglais et en français. */
    private static final Pattern REFUSAL_TEXT = Pattern.compile(
            "(i'?m sorry|i am sorry|i can(no|')t (help|assist|comply)|i'?m (not able|unable) to|"
                    + "i (cannot|can't) (provide|create|do)|unable to (help|assist|comply)|"
                    + "je (suis désolé|ne peux pas)|désolé, je)");

    /** Au-delà, une réponse sans plan n'est plus un refus laconique mais du texte libre. */
    private static final int SHORT_REFUSAL_CHARS = 400;

    /**
     * L'échec d'un appel qui devait rendre un plan, ou rien si la réponse est exploitable.
     *
     * <p>Une réponse coupée par la limite de longueur est un échec même si un début de YAML a pu être
     * isolé : le serveur la refuserait, et la compter comme version ferait passer un plan amputé pour
     * une tentative.</p>
     */
    public static Optional<AiCallFailure> of(AiBuildingStudio.Proposal p) {
        if (p == null) {
            return Optional.of(new AiCallFailure(Kind.API_ERROR, "aucune réponse"));
        }
        AiProvider.Exchange x = p.exchange();
        String finish = x == null ? "" : x.finishReason().toLowerCase(Locale.ROOT);
        if (!p.callOk()) {
            // HTTP 200 sans texte : le fournisseur a bien répondu — refus de l'API (« refusal » renseigné,
            // contenu nul), coupure, ou réponse vide. Ce n'est pas une panne.
            if (x != null && x.httpStatus() == 200 && x.transportError().isEmpty()) {
                if (API_REFUSAL.matcher(x.responseSkeleton()).find()) {
                    return Optional.of(new AiCallFailure(Kind.REFUSAL, "refus signalé par l'API"));
                }
                if (LENGTH.contains(finish)) {
                    return Optional.of(new AiCallFailure(Kind.TRUNCATED, "fin de réponse « " + x.finishReason() + " »"));
                }
                return Optional.of(new AiCallFailure(Kind.EMPTY, finish.isEmpty() ? "" : "fin de réponse « " + finish + " »"));
            }
            return Optional.of(new AiCallFailure(Kind.API_ERROR, p.error()));
        }
        if (LENGTH.contains(finish)) {
            return Optional.of(new AiCallFailure(Kind.TRUNCATED, "fin de réponse « " + x.finishReason()
                    + " » (" + (p.usage() == null ? "jetons non relevés" : p.usage()) + ")"));
        }
        if (p.hasPlan()) {
            return Optional.empty();
        }
        String text = p.rawResponse() == null ? "" : p.rawResponse().strip();
        if (text.isEmpty()) {
            return Optional.of(new AiCallFailure(Kind.EMPTY, finish.isEmpty() ? "" : "fin de réponse « " + finish + " »"));
        }
        boolean apiRefusal = x != null && API_REFUSAL.matcher(x.responseSkeleton()).find();
        if (apiRefusal || text.length() <= SHORT_REFUSAL_CHARS
                && REFUSAL_TEXT.matcher(text.toLowerCase(Locale.ROOT)).find()) {
            return Optional.of(new AiCallFailure(Kind.REFUSAL, "« " + excerpt(text) + " »"));
        }
        return Optional.of(new AiCallFailure(Kind.INVALID_FORMAT, "« " + excerpt(text) + " »"));
    }

    /** Une phrase pour l'écran et le journal. */
    public String sentence() {
        return kind.label() + " — " + kind.explanation() + (detail.isEmpty() ? "" : " : " + detail) + ".";
    }

    private static String excerpt(String text) {
        String one = text.replaceAll("\\s+", " ");
        return one.length() > 200 ? one.substring(0, 200) + "…" : one;
    }
}
