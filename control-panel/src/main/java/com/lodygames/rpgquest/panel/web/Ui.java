package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import com.lodygames.rpgquest.panel.http.Http;

/**
 * Fragments HTML réutilisables du Control Panel : pastilles de statut, badges, identifiants
 * techniques « en second plan », états vides, encadrement de tableaux responsives.
 *
 * <p>Objectif UX (issue #74 + passe générale) : un libellé humain d'abord, l'information
 * technique discrète mais toujours lisible ; un vocabulaire visuel unique entre Dashboard,
 * Agents, Joueurs, Quêtes et Stories. Aucune dépendance : rendu chaîne, testable.</p>
 */
public final class Ui {

    private Ui() {
    }

    // ---- Statuts d'action agent -------------------------------------------------------------

    /**
     * Pastille normalisée pour un statut d'action agent. Jamais uniquement couleur : un glyphe
     * + le texte du statut restent présents.
     */
    public static String actionStatus(AgentActionStatus status) {
        String s = status == null ? "EXPIRED" : status.name();
        return pill(s, kindFor(s), glyphFor(s));
    }

    /** Variante prenant le nom brut (résultat renvoyé par l'agent). */
    public static String actionStatus(String rawStatus) {
        String s = rawStatus == null ? "" : rawStatus.trim().toUpperCase(java.util.Locale.ROOT);
        return pill(s.isEmpty() ? "?" : s, kindFor(s), glyphFor(s));
    }

    private static String kindFor(String s) {
        return switch (s) {
            case "SUCCESS", "COMPLETED" -> "success";
            case "PENDING" -> "pending";
            case "DELIVERED", "ACTIVE", "READY_TO_TURN_IN" -> "delivered";
            case "FAILED" -> "failed";
            case "REJECTED" -> "rejected";
            case "EXPIRED" -> "expired";
            default -> "neutral";
        };
    }

    private static String glyphFor(String s) {
        return switch (s) {
            case "SUCCESS", "COMPLETED" -> "✓";       // ✓
            case "PENDING" -> "○";                     // ○
            case "DELIVERED", "ACTIVE", "READY_TO_TURN_IN" -> "→"; // →
            case "FAILED" -> "✕";                      // ✕
            case "REJECTED" -> "⊘";                    // ⊘
            case "EXPIRED" -> "⧖";                     // ⧖
            default -> "•";                            // •
        };
    }

    /** État de quête/story (NOT_STARTED / ACTIVE / COMPLETED …) — même langage visuel. */
    public static String stateBadge(String state) {
        String s = state == null || state.isBlank() ? "?" : state.trim().toUpperCase(java.util.Locale.ROOT);
        return pill(s, kindFor(s), glyphFor(s));
    }

    // ---- Briques génériques ---------------------------------------------------------------

    /** Pastille : {@code kind} ∈ success|failed|rejected|pending|delivered|expired|neutral. */
    public static String pill(String label, String kind, String glyph) {
        return "<span class=\"pill pill--" + kind + "\">"
                + (glyph == null || glyph.isEmpty() ? "" : "<span class=\"pill-g\" aria-hidden=\"true\">" + glyph + "</span> ")
                + Http.esc(label) + "</span>";
    }

    /** Badge neutre discret (catégorie, « répétable », « 3 étapes »…). */
    public static String badge(String label) {
        return "<span class=\"badge\">" + Http.esc(label) + "</span>";
    }

    /** Identifiant technique en second plan : monospace, discret, valeur complète en infobulle. */
    public static String id(String id) {
        return id(id, id);
    }

    /** Idem, avec un libellé abrégé affiché et la valeur complète en infobulle (UUID tronqué…). */
    public static String id(String label, String fullValue) {
        String l = label == null ? "" : label;
        String f = fullValue == null ? l : fullValue;
        return "<code class=\"tid\" title=\"Identifiant technique : " + Http.esc(f) + "\">" + Http.esc(l) + "</code>";
    }

    /** Ligne « Clé : valeur(HTML déjà sûr) » discrète sous un titre. */
    public static String metaLine(String key, String valueHtml) {
        return "<p class=\"meta-line\"><span class=\"meta-k\">" + Http.esc(key) + "</span> " + valueHtml + "</p>";
    }

    /** Message d'état vide (aucune donnée chargée, liste vide…). */
    public static String empty(String message) {
        return "<p class=\"empty\">" + Http.esc(message) + "</p>";
    }

    /** Ouvre un conteneur qui rend un tableau scrollable horizontalement sur petit écran. */
    public static String tableOpen(String... headers) {
        StringBuilder sb = new StringBuilder("<div class=\"table-wrap\"><table><thead><tr>");
        for (String h : headers) {
            sb.append("<th>").append(Http.esc(h)).append("</th>");
        }
        return sb.append("</tr></thead><tbody>").toString();
    }

    public static String tableClose() {
        return "</tbody></table></div>";
    }
}
