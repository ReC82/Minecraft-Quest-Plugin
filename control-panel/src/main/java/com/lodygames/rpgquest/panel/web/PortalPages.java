package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.http.Http;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Issue #564 (#228-P1) — page « Portails » en <strong>lecture seule</strong> : la liste des
 * portails (cartes) et la fiche d'un portail, d'après le dernier relevé {@code portal.list}.
 *
 * <p>Aucune action mutative n'est exposée : pas de création, de modification, d'activation ni de
 * test de téléportation, pas même désactivés. Le seul formulaire de la page est « Rafraîchir »,
 * qui relit le serveur. La présentation reprend celle de la page Mondes ({@link WorldPages}).</p>
 */
public final class PortalPages {

    static final String PATH = "/portals";

    /**
     * @param agentId    agent ciblé
     * @param details    dernier relevé réussi ({@code null} = jamais relevé)
     * @param headerHtml sélecteur d'agent + barre « Rafraîchir » (HTML sûr)
     * @param releveHtml date du relevé (HTML sûr, peut être vide)
     * @param selected   id du portail dont afficher la fiche ({@code ""} = la liste)
     * @param file       fichier du portail choisi, pour départager deux portails de même id
     */
    public record View(String agentId, Map<String, Object> details, String headerHtml, String releveHtml,
                       String selected, String file) {
    }

    private PortalPages() {
    }

    public static String render(View v) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("portal", "Portails",
                "Tous les portails du serveur : où ils se trouvent, où ils mènent et les règles que le serveur "
                        + "applique avant de téléporter. Page en lecture seule : rien n'est créé, modifié, activé "
                        + "ni téléporté d'ici.", ""));
        sb.append(v.headerHtml());
        if (v.agentId().isEmpty()) {
            return sb.toString();
        }
        if (v.details() == null) {
            return sb.append(Ui.empty("portal", "Aucun relevé chargé — cliquer sur « Rafraîchir les portails »."))
                    .toString();
        }
        if (!v.releveHtml().isEmpty()) {
            sb.append("<p class=\"muted small\">Relevé du serveur : ").append(v.releveHtml()).append("</p>");
        }
        List<Map<String, Object>> portals = maps(v.details().get("portals"));
        // .p-scope : césure aux espaces (les cartes de /worlds coupent n'importe où, utile pour des noms de monde).
        if (!v.selected().isEmpty()) {
            return sb.append("<div class=\"p-scope\">").append(fiche(v, portals)).append("</div>").toString();
        }
        for (Map<String, Object> issue : maps(v.details().get("issues"))) {
            sb.append(issueHtml(issue, "le serveur"));
        }
        sb.append("<div class=\"p-scope\">").append(list(v.agentId(), portals)).append("</div>");
        return sb.toString();
    }

    // ---- Liste ----------------------------------------------------------------------------------

    private static String list(String agentId, List<Map<String, Object>> portals) {
        StringBuilder sb = new StringBuilder();
        long active = portals.stream().filter(p -> bool(p.get("enabled"))).count();
        long legacy = portals.stream().filter(PortalPages::legacy).count();
        long withIssue = portals.stream().filter(PortalPages::hasProblem).count();
        String chips = AgentPages.filterBtn("", "Tous · " + portals.size(), true)
                + AgentPages.filterBtn("active", "Actifs · " + active, false, "Aucun portail actif")
                + AgentPages.filterBtn("disabled", "Désactivés · " + (portals.size() - active), false,
                        "Aucun portail désactivé")
                + AgentPages.filterBtn("legacy", "Ancien format · " + legacy, false, "Aucun portail à l'ancien format")
                + AgentPages.filterBtn("issue", "Avec problème · " + withIssue, false, "Aucun portail avec problème");
        sb.append(AgentPages.listControls("portals", "Rechercher un portail ou un monde", chips));
        sb.append("<p class=\"muted small\">Les mondes de départ et d'arrivée se consultent dans la page <a href=\"")
                .append(WorldPages.PATH).append("?agent=").append(enc(agentId)).append("\">Mondes</a>.</p>");
        if (portals.isEmpty()) {
            return sb.append(Ui.empty("portal", "Aucun portail relevé sur ce serveur.")).toString();
        }
        sb.append("<div class=\"w-grid\">");
        for (Map<String, Object> p : portals) {
            sb.append(card(agentId, p));
        }
        sb.append("</div>").append(AgentPages.filterEmpty("portals", "Aucun portail"));
        sb.append("<p class=\"muted small w-small-note\">Les portails actifs sont repris tels quels depuis les "
                + "fichiers du serveur : leurs règles Wild et Claims restent appliquées par le serveur. La création "
                + "et la modification feront l'objet d'un lot séparé.</p>");
        return sb.toString();
    }

    private static String card(String agentId, Map<String, Object> p) {
        String id = str(p.get("id"));
        boolean enabled = bool(p.get("enabled"));
        List<String> cats = new ArrayList<>();
        cats.add(enabled ? "active" : "disabled");
        if (legacy(p)) {
            cats.add("legacy");
        }
        if (hasProblem(p)) {
            cats.add("issue");
        }
        StringBuilder sb = new StringBuilder("<article class=\"w-card").append(enabled ? "" : " muted-card")
                .append("\" data-filter-item=\"portals\" data-filter-cat=\"").append(String.join(" ", cats))
                .append("\" data-filter-text=\"").append(Http.esc(id + " " + str(p.get("name")) + " "
                        + str(p.get("world")) + " " + str(p.get("destinationWorld")))).append("\">");
        sb.append("<div class=\"w-head\"><span class=\"w-ic ov\">").append(Icons.icon("portal"))
                .append("</span><div><div class=\"w-name\">").append(Http.esc(str(p.get("name"))))
                .append("</div><div class=\"w-sub\"><code>").append(Http.esc(id)).append("</code></div></div>")
                .append("<div class=\"w-pills\">").append(statePill(p)).append(legacyPill(p)).append("</div></div>");
        sb.append("<ul class=\"w-facts\"><li><span class=\"k\">Départ</span><span><code>")
                .append(Http.esc(str(p.get("world")))).append("</code> ").append(Http.esc(str(p.get("min"))))
                .append(" → ").append(Http.esc(str(p.get("max")))).append("</span></li>")
                .append("<li><span class=\"k\">Arrivée</span><span>").append(destinationHtml(p)).append(" · ")
                .append(Http.esc(strategyLabel(str(p.get("strategy"))))).append("</span></li></ul>");
        List<Map<String, Object>> guards = maps(p.get("guards"));
        List<Map<String, Object>> conditions = maps(p.get("conditions"));
        if (!guards.isEmpty() || !conditions.isEmpty()) {
            sb.append("<div class=\"w-roles\">");
            for (Map<String, Object> g : guards) {
                sb.append("<span class=\"role ").append(guardClass(str(g.get("code")))).append("\">")
                        .append(Http.esc(guardShort(str(g.get("code"))))).append("</span>");
            }
            for (Map<String, Object> c : conditions) {
                sb.append("<span class=\"role none\">").append(Http.esc(str(c.get("label")))).append("</span>");
            }
            sb.append("</div>");
        }
        List<Map<String, Object>> issues = maps(p.get("issues"));
        long problems = issues.stream().filter(i -> !"info".equals(str(i.get("severity")))).count();
        if (problems > 0) {
            sb.append("<p class=\"w-note w-note-err\">").append(Icons.icon("warning")).append(problems)
                    .append(" problème(s) — voir la fiche</p>");
        } else if (!issues.isEmpty()) {
            sb.append("<p class=\"w-note\">").append(Icons.icon("info")).append(issues.size())
                    .append(" information(s) — voir la fiche</p>");
        }
        sb.append("<div class=\"w-foot\"><a class=\"btn sm secondary\" href=\"").append(ficheHref(agentId, p))
                .append("\">").append(Icons.icon("search")).append("Ouvrir la fiche</a></div></article>");
        return sb.toString();
    }

    // ---- Fiche ----------------------------------------------------------------------------------

    private static String fiche(View v, List<Map<String, Object>> portals) {
        String back = "<a class=\"btn sm secondary\" href=\"" + PATH + "?agent=" + enc(v.agentId()) + "\">"
                + Icons.icon("back") + "Tous les portails</a>";
        Map<String, Object> p = portals.stream()
                .filter(x -> str(x.get("id")).equals(v.selected())
                        && (v.file().isEmpty() || str(x.get("file")).equals(v.file())))
                .findFirst().orElse(null);
        if (p == null) {
            return Ui.banner("warn", "Le portail <code>" + Http.esc(v.selected()) + "</code> n'apparaît pas dans le "
                    + "dernier relevé. Rafraîchir, ou revenir à la liste.") + "<div class=\"btnrow\">" + back + "</div>";
        }
        String a = "?agent=" + enc(v.agentId());
        StringBuilder sb = new StringBuilder("<div class=\"btnrow mb-3\">").append(back).append("</div>");
        sb.append("<div class=\"w-title\" role=\"heading\" aria-level=\"2\">").append(Http.esc(str(p.get("name"))))
                .append(" <code>").append(Http.esc(str(p.get("id")))).append("</code> ").append(statePill(p))
                .append(legacyPill(p)).append("</div>");
        sb.append("<div class=\"w-cols\"><div>");
        sb.append("<section class=\"card w-sec\"><h3 class=\"w-h\">").append(Icons.icon("target"))
                .append("1 · Emplacement</h3><dl class=\"w-dl\">")
                .append(dlHtml("Monde de départ", worldHtml(a, str(p.get("world")), str(p.get("worldRole")),
                        bool(p.get("worldLoaded")))))
                .append(dl("Coin minimum", str(p.get("min"))))
                .append(dl("Coin maximum", str(p.get("max"))))
                .append(dl("Taille (x × y × z)", str(p.get("size")) + " blocs"))
                .append(dlHtml("Fichier", legacy(p) ? "<code>portals/" + Http.esc(str(p.get("id"))) + ".yml</code>"
                        : "<code>world-portals/" + Http.esc(str(p.get("file"))) + "</code>"))
                .append("</dl><p class=\"field-help\">Zone d'activation telle que le serveur la lit. Aucune "
                        + "construction ni aucun emplacement n'est encore lié aux portails.</p></section>");
        sb.append("<section class=\"card w-sec\"><h3 class=\"w-h\">").append(Icons.icon("travel"))
                .append("2 · Téléportation</h3><dl class=\"w-dl\">")
                .append(dlHtml("Monde d'arrivée", str(p.get("destinationWorld")).isEmpty()
                        ? "<span class=\"muted\">inconnu</span>"
                        : worldHtml(a, str(p.get("destinationWorld")), str(p.get("destinationRole")),
                        bool(p.get("destinationLoaded")))));
        if (!str(p.get("destinationId")).isEmpty()) {
            sb.append(dlHtml("Destination nommée", "<code>" + Http.esc(str(p.get("destinationId"))) + "</code>"));
        }
        sb.append(dl("Arrivée", strategyLabel(str(p.get("strategy"))))).append("</dl>");
        sb.append(bullets(list(p.get("arrival"))));
        if (!list(p.get("trigger")).isEmpty()) {
            sb.append("<h4 class=\"w-h\">Déclenchement</h4>").append(bullets(list(p.get("trigger"))));
        }
        sb.append("</section>");
        sb.append("</div><div>");
        sb.append(guardsSection(p));
        sb.append("<section class=\"card w-sec\"><h3 class=\"w-h\">").append(Icons.icon("diagnostics"))
                .append("État</h3><p>").append(statePill(p)).append("</p>");
        List<Map<String, Object>> issues = maps(p.get("issues"));
        if (issues.isEmpty()) {
            sb.append("<p class=\"muted\">Aucun problème détecté.</p>");
        }
        for (Map<String, Object> issue : issues) {
            sb.append(issueHtml(issue, str(p.get("id"))));
        }
        sb.append("<p class=\"field-help\">Lecture seule. Créer, modifier, activer ou tester un portail fera l'objet "
                + "d'un lot séparé.</p></section>");
        sb.append("</div></div>");
        return sb.toString();
    }

    private static String guardsSection(Map<String, Object> p) {
        StringBuilder sb = new StringBuilder("<section class=\"card w-sec\"><h3 class=\"w-h\">")
                .append(Icons.icon("lock")).append("3 · Règles appliquées par le serveur</h3>");
        List<Map<String, Object>> guards = maps(p.get("guards"));
        List<Map<String, Object>> conditions = maps(p.get("conditions"));
        if (guards.isEmpty() && conditions.isEmpty()) {
            sb.append("<p class=\"muted\">Aucun garde ni aucune condition : tout joueur qui entre dans la zone est "
                    + "téléporté.</p>");
        }
        for (Map<String, Object> g : guards) {
            sb.append("<h4 class=\"w-h\"><span class=\"role ").append(guardClass(str(g.get("code")))).append("\">")
                    .append(Http.esc(guardShort(str(g.get("code"))))).append("</span>")
                    .append(Http.esc(str(g.get("title")))).append("</h4>").append(bullets(list(g.get("rules"))));
            List<Map<String, Object>> texts = maps(g.get("texts"));
            if (!texts.isEmpty()) {
                sb.append("<dl class=\"w-dl\">");
                for (Map<String, Object> t : texts) {
                    sb.append(dlHtml(str(t.get("label")), "« " + Http.esc(str(t.get("value"))) + " »"));
                }
                sb.append("</dl><p class=\"field-help\">Textes actuels montrés au joueur, relus du serveur.</p>");
            }
        }
        if (!conditions.isEmpty()) {
            sb.append("<h4 class=\"w-h\">Conditions d'accès</h4><dl class=\"w-dl\">");
            for (Map<String, Object> c : conditions) {
                sb.append(dl(str(c.get("label")), str(c.get("value"))));
            }
            sb.append("</dl>");
        }
        return sb.append("</section>").toString();
    }

    // ---- Libellés -------------------------------------------------------------------------------

    static String strategyLabel(String strategy) {
        return switch (strategy) {
            case "RANDOM_SAFE" -> "point sûr aléatoire";
            case "WORLD_SPAWN" -> "point d'arrivée du monde";
            case "CLAIM_HOME" -> "claim du joueur, sinon point sûr";
            case "FIXED_POINT" -> "point fixe nommé";
            default -> strategy;
        };
    }

    private static String guardShort(String code) {
        return switch (code) {
            case "WILD_WARNING" -> "Avertissement Wild";
            case "CLAIMS_ACCESS" -> "Accès Claims + retour";
            default -> code;
        };
    }

    private static String guardClass(String code) {
        return switch (code) {
            case "WILD_WARNING" -> "wild";
            case "CLAIMS_ACCESS" -> "claims";
            default -> "none";
        };
    }

    private static String roleLabel(String role) {
        return switch (role) {
            case "HUB" -> "Hub";
            case "WILD" -> "Wild";
            case "CLAIMS" -> "Claims";
            default -> role;
        };
    }

    // ---- Briques --------------------------------------------------------------------------------

    private static String worldHtml(String agentQuery, String world, String role, boolean loaded) {
        StringBuilder sb = new StringBuilder("<a href=\"").append(WorldPages.PATH).append(agentQuery)
                .append("&amp;world=").append(enc(world)).append("\"><code>").append(Http.esc(world))
                .append("</code></a>");
        if (!role.isEmpty()) {
            sb.append(" <span class=\"role ").append(Http.esc(role.toLowerCase(java.util.Locale.ROOT))).append("\">")
                    .append(Http.esc(roleLabel(role))).append("</span>");
        }
        sb.append(" ").append(loaded ? "<span class=\"pill ok\">Chargé</span>"
                : "<span class=\"pill pill--neutral\">Non chargé</span>");
        return sb.toString();
    }

    private static String destinationHtml(Map<String, Object> p) {
        String world = str(p.get("destinationWorld"));
        if (world.isEmpty()) {
            return "<span class=\"muted\">inconnue</span>";
        }
        return "<code>" + Http.esc(world) + "</code>" + (Boolean.FALSE.equals(p.get("destinationLoaded"))
                ? " <em>(non chargé)</em>" : "");
    }

    private static String issueHtml(Map<String, Object> issue, String subject) {
        String message = str(issue.get("message"));
        return DiagnosticHelp.render(str(issue.get("code")), str(issue.get("severity")), message, subject, message, "");
    }

    private static boolean hasProblem(Map<String, Object> p) {
        return maps(p.get("issues")).stream().anyMatch(i -> !"info".equals(str(i.get("severity"))));
    }

    private static boolean legacy(Map<String, Object> p) {
        return "ZONE_PORTAL".equals(str(p.get("kind")));
    }

    private static String statePill(Map<String, Object> p) {
        return bool(p.get("enabled")) ? "<span class=\"pill ok\">Actif</span>"
                : "<span class=\"pill pill--neutral\">Désactivé</span>";
    }

    private static String legacyPill(Map<String, Object> p) {
        return legacy(p) ? " <span class=\"pill warn\" title=\"Portail de zone (portals/, /rpgadmin portal)\">"
                + "Ancien format</span>" : "";
    }

    private static String bullets(List<String> lines) {
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<ul class=\"w-links\">");
        for (String line : lines) {
            sb.append("<li><i class=\"bi bi-dot\" aria-hidden=\"true\"></i><span>").append(Http.esc(line))
                    .append("</span></li>");
        }
        return sb.append("</ul>").toString();
    }

    private static String dl(String key, String value) {
        return dlHtml(key, Http.esc(value));
    }

    private static String dlHtml(String key, String valueHtml) {
        return "<div><dt>" + Http.esc(key) + "</dt><dd>" + valueHtml + "</dd></div>";
    }

    static String ficheHref(String agentId, Map<String, Object> p) {
        String file = str(p.get("file"));
        return PATH + "?agent=" + enc(agentId) + "&amp;portal=" + enc(str(p.get("id")))
                + (file.isEmpty() ? "" : "&amp;file=" + enc(file));
    }

    private static String enc(String s) {
        return Http.esc(URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8));
    }

    private static boolean bool(Object o) {
        return Boolean.TRUE.equals(o) || "true".equals(String.valueOf(o));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<Map<String, Object>> maps(Object o) {
        return o instanceof List<?> l ? l.stream().map(PortalPages::map).toList() : List.of();
    }

    private static List<String> list(Object o) {
        return o instanceof List<?> l ? l.stream().map(PortalPages::str).toList() : List.of();
    }
}
