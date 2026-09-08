package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
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

    /** Vivacité d'un agent (ONLINE / STALE / OFFLINE / UNKNOWN) — même langage visuel. */
    public static String liveness(String live) {
        String s = live == null || live.isBlank() ? "UNKNOWN" : live.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (s) {
            case "ONLINE" -> pill(s, "success", "✓");
            case "STALE" -> pill(s, "pending", "○");
            case "OFFLINE" -> pill(s, "failed", "✕");
            default -> pill(s, "neutral", "•");
        };
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

    /**
     * Pastille de sévérité d'une anomalie de configuration : {@code error} / {@code warning} /
     * {@code info} — glyphe + texte, jamais couleur seule. Réutilise les styles de pastille
     * existants (aucun CSS nouveau).
     */
    public static String severity(String level) {
        String l = level == null ? "" : level.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (l) {
            case "error" -> pill("ERREUR", "failed", "✕");
            case "warning" -> pill("ATTENTION", "pending", "!");
            default -> pill("INFO", "neutral", "i");
        };
    }

    /** Type d'action agent : libellé humain (via {@link AgentActionCatalog}) + fil technique copiable. */
    public static String actionType(String type) {
        String label = AgentActionCatalog.spec(type).map(AgentActionCatalog.Spec::label).orElse(type);
        return "<span class=\"act-type\">" + Http.esc(label) + "</span> " + id(type);
    }

    /** Identifiant technique en second plan : monospace, discret, cliquable pour copier. */
    public static String id(String id) {
        return id(id, id);
    }

    /**
     * Idem, avec un libellé abrégé affiché et la valeur complète copiée / en infobulle (UUID
     * tronqué…). Le clic (ou Entrée/Espace) copie {@code fullValue} — géré par {@code panel.js},
     * avec repli si l'API Clipboard est indisponible ; sans JS, l'infobulle reste consultable.
     */
    public static String id(String label, String fullValue) {
        String l = label == null ? "" : label;
        String f = fullValue == null ? l : fullValue;
        return "<code class=\"tid\" role=\"button\" tabindex=\"0\" data-copy=\"" + Http.esc(f)
                + "\" title=\"Cliquer pour copier : " + Http.esc(f) + "\">" + Http.esc(l) + "</code>";
    }

    /** Valeur technique longue (commande de récompense, chaîne libre) : monospace discret,
     *  retour à la ligne autorisé, cliquable pour copier. */
    public static String rawValue(String value) {
        String v = value == null ? "" : value;
        return "<code class=\"tid tid--wrap\" role=\"button\" tabindex=\"0\" data-copy=\"" + Http.esc(v)
                + "\" title=\"Cliquer pour copier\">" + Http.esc(v) + "</code>";
    }

    /** Ligne « Clé : valeur(HTML déjà sûr) » discrète sous un titre. */
    public static String metaLine(String key, String valueHtml) {
        return "<p class=\"meta-line\"><span class=\"meta-k\">" + Http.esc(key) + "</span> " + valueHtml + "</p>";
    }

    /** Message d'état vide (aucune donnée chargée, liste vide…). */
    public static String empty(String message) {
        return "<p class=\"empty\">" + Http.esc(message) + "</p>";
    }

    /** État vide avec icône (issue #92). */
    public static String empty(String iconName, String message) {
        return "<div class=\"empty\">" + Icons.icon(iconName) + Http.esc(message) + "</div>";
    }

    // ---- Composants de shell / page (refonte #92) --------------------------------------------

    /**
     * En-tête de page standard : icône + titre exact (jamais de balise entre {@code <h1>} et le
     * texte), sous-titre, et zone d'actions principales (HTML déjà sûr).
     */
    public static String pageHeader(String iconName, String title, String subtitle, String actionsHtml) {
        StringBuilder sb = new StringBuilder("<div class=\"pagehead\"><div class=\"ph-l\">");
        sb.append("<div class=\"ph-title\">").append(Icons.icon(iconName))
                .append("<h1>").append(Http.esc(title)).append("</h1></div>");
        if (subtitle != null && !subtitle.isBlank()) {
            sb.append("<p class=\"sub\">").append(Http.esc(subtitle)).append("</p>");
        }
        sb.append("</div>");
        if (actionsHtml != null && !actionsHtml.isBlank()) {
            sb.append("<div class=\"ph-actions\">").append(actionsHtml).append("</div>");
        }
        return sb.append("</div>").toString();
    }

    /** Titre de section avec icône. */
    public static String sectionTitle(String iconName, String label) {
        return "<p class=\"section-title\">" + Icons.icon(iconName) + Http.esc(label) + "</p>";
    }

    /**
     * Carte statistique : icône + valeur + libellé (+ ligne secondaire HTML sûre optionnelle).
     * {@code kind} ∈ {@code ""|ok|warn|err}.
     */
    public static String statCard(String iconName, String value, String label, String kind, String extraHtml) {
        String k = kind == null || kind.isBlank() ? "" : " " + kind;
        StringBuilder sb = new StringBuilder("<div class=\"stat").append(k).append("\">");
        sb.append("<span class=\"stat-ic\">").append(Icons.icon(iconName)).append("</span>");
        sb.append("<div class=\"stat-b\"><div class=\"stat-v\">").append(Http.esc(value)).append("</div>");
        sb.append("<div class=\"stat-k\">").append(Http.esc(label)).append("</div>");
        if (extraHtml != null && !extraHtml.isBlank()) {
            sb.append("<div class=\"stat-l\">").append(extraHtml).append("</div>");
        }
        return sb.append("</div></div>").toString();
    }

    /** Bannière avec icône selon le type ({@code ok|err|warn|info}). {@code bodyHtml} déjà sûr. */
    public static String banner(String type, String bodyHtml) {
        String t = switch (type == null ? "" : type) {
            case "ok", "err", "warn", "info" -> type;
            default -> "info";
        };
        String ic = switch (t) {
            case "ok" -> "check";
            case "err" -> "error";
            case "warn" -> "warning";
            default -> "info";
        };
        return "<div class=\"banner " + t + "\">" + Icons.icon(ic) + "<div>" + bodyHtml + "</div></div>";
    }

    /**
     * Barre d'outils recherche + filtres pour une liste de cartes. Le champ pilote
     * {@code data-filter-input="<scope>"} ; les puces {@code data-filter-chip}. Le filtrage est
     * fait par {@code panel.js} (progressif — sans JS la liste reste entièrement visible).
     *
     * @param scope        identifiant du groupe filtrable (les cartes portent {@code data-filter-item="<scope>"})
     * @param placeholder  texte du champ
     * @param chipsHtml    HTML des puces de filtre (peut être vide)
     */
    public static String searchToolbar(String scope, String placeholder, String chipsHtml) {
        StringBuilder sb = new StringBuilder("<div class=\"toolbar\">");
        sb.append("<div class=\"search\">").append(Icons.icon("search"))
                .append("<input type=\"search\" data-filter-input=\"").append(Http.esc(scope))
                .append("\" placeholder=\"").append(Http.esc(placeholder))
                .append("\" aria-label=\"").append(Http.esc(placeholder)).append("\"></div>");
        if (chipsHtml != null && !chipsHtml.isBlank()) {
            sb.append("<div class=\"chips\" data-filter-chips=\"").append(Http.esc(scope)).append("\">")
                    .append(chipsHtml).append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    /** Une puce de filtre par catégorie. {@code value} = valeur comparée à {@code data-filter-cat} des cartes. */
    public static String filterChip(String value, String label, boolean on) {
        return "<button type=\"button\" class=\"chip" + (on ? " on" : "") + "\" data-filter-chip=\""
                + Http.esc(value) + "\">" + Http.esc(label) + "</button>";
    }

    public static String filterChip(String value, String label) {
        return filterChip(value, label, false);
    }

    /** Petit compteur « N élément(s) » sous la barre d'outils. */
    public static String countNote(int n, String noun) {
        return "<p class=\"count-note\" data-count-note>" + n + " " + Http.esc(noun) + (n > 1 ? "s" : "") + "</p>";
    }

    /** Bouton-lien avec icône (action principale d'une page). */
    public static String primaryLink(String href, String iconName, String label) {
        return "<a class=\"btn\" href=\"" + Http.esc(href) + "\">" + Icons.icon(iconName) + Http.esc(label) + "</a>";
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
