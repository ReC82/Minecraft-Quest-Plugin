package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import com.lodygames.rpgquest.panel.http.Http;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Vue lisible d'une action agent (issue #93) — <strong>pur</strong>, sans état. Sert aux toasts,
 * au centre de notifications et à la page {@code /actions}. La source de vérité reste la table
 * {@code agent_action} ({@link com.lodygames.rpgquest.panel.agent.AgentStore}) : rien n'est
 * dupliqué ici, seulement présenté.
 *
 * <ul>
 *   <li><b>Domaine</b> : dérivé du préfixe du type ({@code player.*} → Joueurs, {@code npc.*} →
 *       PNJ, …).</li>
 *   <li><b>Groupe de statut</b> : {@code SUCCESS} → Succès ; {@code PENDING}/{@code DELIVERED} →
 *       En cours ; {@code FAILED}/{@code REJECTED}/{@code EXPIRED} → Échec.</li>
 * </ul>
 */
public final class ActionView {

    private ActionView() {
    }

    /** Familles d'actions pour le filtre par domaine. {@code icon} = nom d'icône {@link Icons}. */
    public enum Domain {
        PLAYERS("players", "Joueurs", "players", "player."),
        NPC("npc", "PNJ", "npc", "npc."),
        QUESTS("quests", "Quêtes", "quests", "quest."),
        STORIES("stories", "Stories", "stories", "story."),
        DIALOGUES("dialogues", "Dialogues", "dialogues", "dialogue."),
        ITEMS("items", "Items", "gift", "item."),
        SERVER("server", "Serveur / Agents", "server", "server.", "agent."),
        OTHER("other", "Autres", "info", "");

        private final String slug;
        private final String label;
        private final String icon;
        private final String[] prefixes;

        Domain(String slug, String label, String icon, String... prefixes) {
            this.slug = slug;
            this.label = label;
            this.icon = icon;
            this.prefixes = prefixes;
        }

        public String slug() {
            return slug;
        }

        public String label() {
            return label;
        }

        public String icon() {
            return icon;
        }

        public static Domain of(String type) {
            String t = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
            for (Domain d : values()) {
                for (String p : d.prefixes) {
                    if (!p.isEmpty() && t.startsWith(p)) {
                        return d;
                    }
                }
            }
            return OTHER;
        }

        public static Optional<Domain> fromSlug(String slug) {
            if (slug == null || slug.isBlank() || slug.equals("all")) {
                return Optional.empty();
            }
            for (Domain d : values()) {
                if (d.slug.equals(slug)) {
                    return Optional.of(d);
                }
            }
            return Optional.empty();
        }
    }

    /** Regroupement UX des statuts techniques. */
    public enum Group {
        SUCCESS("success", "Succès"),
        PENDING("pending", "En cours"),
        FAILED("failed", "Échec");

        private final String slug;
        private final String label;

        Group(String slug, String label) {
            this.slug = slug;
            this.label = label;
        }

        public String slug() {
            return slug;
        }

        public String label() {
            return label;
        }

        public static Group of(AgentActionStatus status) {
            return switch (status) {
                case SUCCESS -> SUCCESS;
                case PENDING, DELIVERED -> PENDING;
                case FAILED, REJECTED, EXPIRED -> FAILED;
            };
        }

        public static Optional<Group> fromSlug(String slug) {
            if (slug == null || slug.isBlank() || slug.equals("all")) {
                return Optional.empty();
            }
            for (Group g : values()) {
                if (g.slug.equals(slug)) {
                    return Optional.of(g);
                }
            }
            return Optional.empty();
        }
    }

    // ---- libellés ----------------------------------------------------------------------

    /** Libellé humain de l'action (catalogue), repli sur une forme lisible du type technique. */
    public static String humanLabel(String type) {
        return AgentActionCatalog.spec(type)
                .map(AgentActionCatalog.Spec::label)
                .orElseGet(() -> prettifyType(type));
    }

    private static String prettifyType(String type) {
        if (type == null || type.isBlank()) {
            return "Action";
        }
        String s = type.replace('.', ' ').replace('_', ' ').trim();
        return s.isEmpty() ? type : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static final List<String> TARGET_KEYS = List.of(
            "player", "npc_id", "quest_id", "story_id", "dialogue_id", "citizens_id",
            "item_id", "node_id", "key", "display_name", "world");

    /** Cible la plus parlante d'une action (pseudo joueur, id de PNJ/quête/…), sinon vide. */
    public static String target(AgentActionRow a) {
        for (String k : TARGET_KEYS) {
            String v = a.params().get(k);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    /** Résultat synthétique lisible (valeur + message), plafonné. Vide si non terminal / rien. */
    public static String shortResult(AgentActionRow a) {
        String value = nz(a.resultValue());
        String message = nz(a.resultMessage());
        String out = (value + (value.isEmpty() || message.isEmpty() ? "" : " · ") + message).trim();
        if (out.isEmpty()) {
            return "";
        }
        out = MiniText.prettifyTokens(out);
        return out.length() > 140 ? out.substring(0, 139) + "…" : out;
    }

    /** Heure relative courte : « à l'instant », « il y a 3 min », « il y a 2 h », « il y a 4 j ». */
    public static String relativeTime(Instant when, Instant now) {
        if (when == null) {
            return "";
        }
        long sec = Math.max(0, Duration.between(when, now).getSeconds());
        if (sec < 45) {
            return "à l'instant";
        }
        if (sec < 3600) {
            return "il y a " + (sec / 60) + " min";
        }
        if (sec < 86400) {
            return "il y a " + (sec / 3600) + " h";
        }
        return "il y a " + (sec / 86400) + " j";
    }

    // ---- badges Bootstrap (icône + texte, jamais couleur seule) -----------------------

    /** Badge Bootstrap du statut réel, coloré selon le groupe. */
    public static String statusBadge(AgentActionStatus status) {
        Group g = Group.of(status);
        String cls = switch (g) {
            case SUCCESS -> "text-bg-success";
            case PENDING -> "text-bg-primary";
            case FAILED -> "text-bg-danger";
        };
        String icon = switch (g) {
            case SUCCESS -> "check";
            case PENDING -> "clock";
            case FAILED -> "error";
        };
        return "<span class=\"badge " + cls + " pa-status\">" + Icons.icon(icon)
                + "<span>" + Http.esc(statusLabel(status)) + "</span></span>";
    }

    public static String statusLabel(AgentActionStatus status) {
        return switch (status) {
            case PENDING -> "En attente";
            case DELIVERED -> "Transmise";
            case SUCCESS -> "Succès";
            case FAILED -> "Échec";
            case REJECTED -> "Refusée";
            case EXPIRED -> "Expirée";
        };
    }

    /** Étiquette de domaine avec icône (badge discret). */
    public static String domainBadge(Domain d) {
        return "<span class=\"badge text-bg-secondary pa-domain\">" + Icons.icon(d.icon())
                + "<span>" + Http.esc(d.label()) + "</span></span>";
    }

    /** Nom d'icône {@link Icons} du groupe (toasts / notifications). */
    public static String groupIcon(Group g) {
        return switch (g) {
            case SUCCESS -> "check";
            case PENDING -> "clock";
            case FAILED -> "error";
        };
    }

    /** Classe de couleur Bootstrap texte pour l'icône du groupe. */
    public static String groupColor(Group g) {
        return switch (g) {
            case SUCCESS -> "text-success";
            case PENDING -> "text-primary";
            case FAILED -> "text-danger";
        };
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String clamp(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    // ---- fragments rendus (partagés serveur / JSON) ----------------------------------

    /** Une ligne du centre de notifications. HTML déjà sûr (échappé). Résultat volontairement bref
     *  (le détail complet est sur {@code /actions}). */
    public static String notifItemHtml(AgentActionRow a, Instant now) {
        Domain d = Domain.of(a.type());
        Group g = Group.of(a.status());
        String tgt = target(a);
        String res = clamp(shortResult(a), 90);
        StringBuilder sb = new StringBuilder();
        sb.append("<a class=\"notif-item\" href=\"/actions/").append(Http.esc(a.id())).append("\">");
        sb.append("<span class=\"notif-ic\">").append(Icons.icon(d.icon())).append("</span>");
        sb.append("<span class=\"notif-body\">");
        sb.append("<span class=\"notif-t\">").append(Http.esc(d.label())).append(" · ")
                .append(Http.esc(humanLabel(a.type())));
        if (!tgt.isEmpty()) {
            sb.append(" <span class=\"notif-tgt\">").append(Http.esc(tgt)).append("</span>");
        }
        sb.append("</span>");
        sb.append("<span class=\"notif-meta\"><span class=\"notif-status ").append(groupColor(g)).append("\">")
                .append(Icons.icon(groupIcon(g))).append(Http.esc(statusLabel(a.status()))).append("</span>");
        sb.append(" · ").append(Http.esc(relativeTime(a.createdAt(), now)));
        if (!res.isEmpty()) {
            sb.append(" · <span class=\"notif-res\">").append(Http.esc(res)).append("</span>");
        }
        sb.append("</span></span></a>");
        return sb.toString();
    }

    /** Toast Bootstrap pour une action donnée. Déplacé et affiché par {@code panel.js}. */
    public static String toastHtml(AgentActionRow a, Instant now) {
        Group g = Group.of(a.status());
        boolean autohide = g == Group.SUCCESS;
        String tgt = target(a);
        String res = shortResult(a);
        String body = res.isEmpty()
                ? (tgt.isEmpty() ? "Action enregistrée." : Http.esc(tgt))
                : (tgt.isEmpty() ? Http.esc(res) : Http.esc(tgt) + " — " + Http.esc(res));
        if (g == Group.PENDING) {
            body = "En attente de confirmation de l'agent" + (tgt.isEmpty() ? "" : " — " + Http.esc(tgt)) + ".";
        }
        return "<div class=\"toast pa-toast\" role=\"status\" aria-live=\"polite\" aria-atomic=\"true\""
                + " data-toast-action=\"" + Http.esc(a.id()) + "\""
                + " data-toast-group=\"" + g.slug() + "\""
                + " data-bs-autohide=\"" + autohide + "\" data-bs-delay=\"6000\">"
                + "<div class=\"toast-header\">"
                + "<span class=\"toast-ic " + groupColor(g) + "\">" + Icons.icon(groupIcon(g)) + "</span>"
                + "<strong class=\"me-auto\" data-toast-title>" + Http.esc(humanLabel(a.type())) + "</strong>"
                + "<small class=\"text-body-secondary\" data-toast-time>" + Http.esc(relativeTime(a.createdAt(), now)) + "</small>"
                + "<button type=\"button\" class=\"btn-close\" data-bs-dismiss=\"toast\" aria-label=\"Fermer\"></button>"
                + "</div>"
                + "<div class=\"toast-body\"><span data-toast-body>" + body + "</span> "
                + "<a href=\"/actions/" + Http.esc(a.id()) + "\" class=\"toast-details\">Détails</a></div>"
                + "</div>";
    }

    /** Toast d'erreur immédiat (échec de validation, avant même la création d'une action). */
    public static String errorToastHtml(String message) {
        String msg = message == null || message.isBlank() ? "Requête refusée." : message;
        return "<div class=\"toast pa-toast\" role=\"alert\" aria-live=\"assertive\" aria-atomic=\"true\""
                + " data-toast-group=\"failed\" data-bs-autohide=\"false\">"
                + "<div class=\"toast-header\">"
                + "<span class=\"toast-ic text-danger\">" + Icons.icon("error") + "</span>"
                + "<strong class=\"me-auto\">Action refusée</strong>"
                + "<button type=\"button\" class=\"btn-close\" data-bs-dismiss=\"toast\" aria-label=\"Fermer\"></button>"
                + "</div>"
                + "<div class=\"toast-body\">" + Http.esc(msg) + "</div>"
                + "</div>";
    }
}
