package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.http.Http;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Issue #637 — page « Claims » : tous les claims des joueurs (cartes filtrables, recherche par
 * pseudo, UUID, identifiant ou monde) et la fiche d'un claim, d'après le dernier relevé
 * {@code claim.list}. Fonctionne pour un propriétaire hors ligne : le relevé vient de la base.
 *
 * <p>Rien n'est créé, modifié, déplacé ni supprimé d'ici. Le seul geste est « M'y téléporter »,
 * dans la fiche, qui ne déplace que le personnage lié à la session — jamais le propriétaire —
 * après une confirmation explicite ; il n'est jamais déclenché à l'ouverture ni au rafraîchissement.</p>
 */
public final class ClaimPages {

    static final String PATH = "/claims";

    /**
     * @param details       dernier relevé réussi ({@code null} = jamais relevé)
     * @param headerHtml    sélecteur d'agent + barre « Rafraîchir » (HTML sûr)
     * @param releveHtml    date du relevé (HTML sûr, peut être vide)
     * @param selected      identifiant du claim dont afficher la fiche ({@code ""} = la liste)
     * @param owner         UUID d'un propriétaire pour ne lister que ses claims ({@code ""} = tous)
     * @param teleportBlock section « M'y téléporter » d'un claim (HTML sûr), construite par l'appelant
     */
    public record View(String agentId, Map<String, Object> details, String headerHtml, String releveHtml,
                       String selected, String owner, WorldNames names,
                       Function<Map<String, Object>, String> teleportBlock) {

        public View {
            names = names == null ? WorldNames.DEFAULTS : names;
            teleportBlock = teleportBlock == null ? c -> "" : teleportBlock;
        }
    }

    private ClaimPages() {
    }

    public static String render(View v) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("claim", "Claims",
                "Les terrains protégés des joueurs : à qui ils appartiennent, où ils sont et leur taille. "
                        + "Rien n'est créé, modifié, déplacé ni supprimé d'ici.", ""));
        sb.append(v.headerHtml());
        if (v.agentId().isEmpty()) {
            return sb.toString();
        }
        if (v.details() == null) {
            return sb.append(Ui.empty("claim", "Aucun relevé chargé — cliquer sur « Rafraîchir les claims »."))
                    .toString();
        }
        if (!v.releveHtml().isEmpty()) {
            sb.append("<p class=\"muted small\">Relevé du serveur : ").append(v.releveHtml()).append("</p>");
        }
        List<Map<String, Object>> claims = maps(v.details().get("claims"));
        sb.append("<div class=\"p-scope\">");
        sb.append(v.selected().isEmpty() ? list(v, claims) : fiche(v, claims));
        return sb.append("</div>").toString();
    }

    /** Claims d'un joueur dans un relevé (fiche joueur) — le principal d'abord. */
    static List<Map<String, Object>> ofOwner(Map<String, Object> details, String owner) {
        return maps(details.get("claims")).stream().filter(c -> str(c.get("owner")).equalsIgnoreCase(owner))
                .sorted((a, b) -> Boolean.compare(bool(b.get("main")), bool(a.get("main")))).toList();
    }

    // ---- Liste ----------------------------------------------------------------------------------

    private static String list(View v, List<Map<String, Object>> all) {
        StringBuilder sb = new StringBuilder();
        List<Map<String, Object>> claims = all;
        if (!v.owner().isEmpty()) {
            claims = ofOwner(v.details(), v.owner());
            String who = claims.isEmpty() ? v.owner() : ownerLabel(claims.get(0));
            sb.append("<div class=\"btnrow mb-3\"><a class=\"btn sm secondary\" href=\"").append(PATH).append("?agent=")
                    .append(enc(v.agentId())).append("\">").append(Icons.icon("back")).append("Tous les claims</a></div>");
            sb.append("<div class=\"w-title\" role=\"heading\" aria-level=\"2\">Claims de ").append(Http.esc(who))
                    .append("</div>");
            if (claims.isEmpty()) {
                return sb.append(Ui.empty("claim", "Ce joueur ne possède aucun claim dans le dernier relevé.")).toString();
            }
        }
        long main = claims.stream().filter(c -> bool(c.get("main"))).count();
        long unloaded = claims.stream().filter(c -> !bool(c.get("worldLoaded"))).count();
        long owners = claims.stream().map(c -> str(c.get("owner"))).distinct().count();
        String chips = AgentPages.filterBtn("", "Tous · " + claims.size(), true)
                + AgentPages.filterBtn("main", "Principaux · " + main, false, "Aucun claim principal")
                + AgentPages.filterBtn("other", "Autres · " + (claims.size() - main), false, "Aucun autre claim")
                + AgentPages.filterBtn("unloaded", "Monde non chargé · " + unloaded, false,
                        "Aucun claim dans un monde non chargé");
        sb.append(AgentPages.listControls("claims", "Rechercher un joueur, un UUID, un claim ou un monde", chips));
        sb.append("<p class=\"muted small\">").append(claims.size()).append(" claim(s), ").append(owners)
                .append(" propriétaire(s). Chaque joueur peut aussi être ouvert depuis la page <a href=\"/players?agent=")
                .append(enc(v.agentId())).append("\">Joueurs</a>.</p>");
        if (claims.isEmpty()) {
            return sb.append(Ui.empty("claim", "Aucun claim sur ce serveur.")).toString();
        }
        sb.append("<div class=\"w-grid\">");
        for (Map<String, Object> c : claims) {
            sb.append(card(v.agentId(), c, v.names()));
        }
        sb.append("</div>").append(AgentPages.filterEmpty("claims", "Aucun claim"));
        return sb.toString();
    }

    private static String card(String agentId, Map<String, Object> c, WorldNames names) {
        boolean main = bool(c.get("main"));
        String cats = (main ? "main" : "other") + (bool(c.get("worldLoaded")) ? "" : " unloaded");
        StringBuilder sb = new StringBuilder("<article class=\"w-card\" data-filter-item=\"claims\" data-filter-cat=\"")
                .append(cats).append("\" data-filter-text=\"").append(Http.esc(str(c.get("ownerName")) + " "
                        + str(c.get("owner")) + " " + str(c.get("id")) + " " + str(c.get("world")) + " "
                        + names.name(str(c.get("world"))))).append("\">");
        sb.append("<div class=\"w-head\"><span class=\"w-ic ov\">").append(Icons.icon("claim"))
                .append("</span><div><div class=\"w-name\">").append(Http.esc(ownerLabel(c)))
                .append("</div><div class=\"w-sub\"><code>").append(Http.esc(str(c.get("id")))).append("</code></div></div>")
                .append("<div class=\"w-pills\">").append(mainPill(c)).append("</div></div>");
        sb.append("<ul class=\"w-facts\"><li><span class=\"k\">Monde</span><span>")
                .append(worldName(names, str(c.get("world")))).append(bool(c.get("worldLoaded")) ? ""
                        : " <em>(non chargé)</em>").append("</span></li>")
                .append("<li><span class=\"k\">Centre</span><span>").append(Http.esc(str(c.get("center"))))
                .append(" <span class=\"muted\">(x, z)</span></span></li>")
                .append("<li><span class=\"k\">Taille</span><span>").append(Http.esc(size(c))).append(" · ")
                .append(Http.esc(tierLabel(str(c.get("tier"))))).append("</span></li></ul>");
        sb.append("<div class=\"w-foot\"><a class=\"btn sm secondary\" href=\"").append(ficheHref(agentId, c))
                .append("\">").append(Icons.icon("search")).append("Ouvrir la fiche</a></div></article>");
        return sb.toString();
    }

    // ---- Fiche ----------------------------------------------------------------------------------

    private static String fiche(View v, List<Map<String, Object>> claims) {
        String back = "<a class=\"btn sm secondary\" href=\"" + PATH + "?agent=" + enc(v.agentId()) + "\">"
                + Icons.icon("back") + "Tous les claims</a>";
        Map<String, Object> c = claims.stream().filter(x -> str(x.get("id")).equals(v.selected())).findFirst()
                .orElse(null);
        if (c == null) {
            return Ui.banner("warn", "Le claim <code>" + Http.esc(v.selected()) + "</code> n'apparaît pas dans le "
                    + "dernier relevé : il a peut-être été supprimé. Rafraîchir, ou revenir à la liste.")
                    + "<div class=\"btnrow\">" + back + "</div>";
        }
        String owner = str(c.get("owner"));
        String a = "?agent=" + enc(v.agentId());
        StringBuilder sb = new StringBuilder("<div class=\"btnrow mb-3\">").append(back)
                .append("<a class=\"btn sm secondary\" href=\"").append(PATH).append(a).append("&amp;owner=")
                .append(enc(owner)).append("\">").append(Icons.icon("claim")).append("Tous ses claims</a>")
                .append("<a class=\"btn sm secondary\" href=\"/players").append(a).append("&amp;player=").append(enc(owner))
                .append("\">").append(Icons.icon("players")).append("Fiche du joueur</a></div>");
        sb.append("<div class=\"w-title\" role=\"heading\" aria-level=\"2\">Claim de ").append(Http.esc(ownerLabel(c)))
                .append(" <code>").append(Http.esc(str(c.get("id")))).append("</code> ").append(mainPill(c))
                .append("</div>");
        sb.append("<div class=\"w-cols\"><div>");
        sb.append("<section class=\"card w-sec\"><h3 class=\"w-h\">").append(Icons.icon("players"))
                .append("Propriétaire</h3><dl class=\"w-dl\">")
                .append(dlHtml("Pseudo", str(c.get("ownerName")).isEmpty()
                        ? "<span class=\"muted\">inconnu du serveur</span>" : Http.esc(str(c.get("ownerName")))))
                .append(dlHtml("UUID", "<code>" + Http.esc(owner) + "</code>"))
                .append(dl("Claim principal", bool(c.get("main")) ? "oui — celui du portail des claims et de Jo"
                        : "non — un autre claim de ce joueur est principal"))
                .append(dl("Membres de confiance", str(c.get("members"))))
                .append(dl("Redstone publique", bool(c.get("publicRedstone")) ? "autorisée" : "non"))
                .append("</dl></section>");
        sb.append("<section class=\"card w-sec\"><h3 class=\"w-h\">").append(Icons.icon("target"))
                .append("Emplacement</h3><dl class=\"w-dl\">")
                .append(dlHtml("Monde", worldName(v.names(), str(c.get("world"))) + " "
                        + (bool(c.get("worldLoaded")) ? "<span class=\"pill ok\">Chargé</span>"
                        : "<span class=\"pill pill--neutral\">Non chargé</span>")))
                .append(dl("Centre (x, z)", str(c.get("center"))))
                .append(dl("Coin minimum (x, y, z)", str(c.get("min"))))
                .append(dl("Coin maximum (x, y, z)", str(c.get("max"))))
                .append(dl("Dimensions", size(c) + " · hauteur " + str(c.get("height")) + " blocs"))
                .append(dl("Palier", tierLabel(str(c.get("tier")))))
                .append(dl("Réservation (x, z)", str(c.get("reservedMin")) + " → " + str(c.get("reservedMax"))))
                .append("</dl><p class=\"field-help\">Le palier est déduit de la taille réelle du claim. La réservation "
                        + "est l'espace gardé libre autour pour les paliers suivants.</p></section>");
        sb.append("</div><div>").append(v.teleportBlock().apply(c)).append("</div></div>");
        return sb.toString();
    }

    // ---- Libellés -------------------------------------------------------------------------------

    static String ownerLabel(Map<String, Object> c) {
        String name = str(c.get("ownerName"));
        return name.isEmpty() ? "joueur " + shortUuid(str(c.get("owner"))) : name;
    }

    static String tierLabel(String tier) {
        return tier.startsWith("TIER_") ? "palier " + tier.substring(5) : "forme libre (sans palier)";
    }

    static String size(Map<String, Object> c) {
        return str(c.get("width")) + " × " + str(c.get("depth")) + " blocs";
    }

    private static String mainPill(Map<String, Object> c) {
        return bool(c.get("main")) ? "<span class=\"pill ok\">Principal</span>"
                : "<span class=\"pill pill--neutral\">Secondaire</span>";
    }

    private static String shortUuid(String uuid) {
        return uuid.length() > 8 ? uuid.substring(0, 8) + "…" : uuid;
    }

    // ---- Briques --------------------------------------------------------------------------------

    static String worldName(WorldNames names, String world) {
        String shown = names.name(world);
        return shown.equals(world) ? "<code>" + Http.esc(world) + "</code>"
                : Http.esc(shown) + " <code>" + Http.esc(world) + "</code>";
    }

    private static String dl(String key, String value) {
        return dlHtml(key, Http.esc(value));
    }

    private static String dlHtml(String key, String valueHtml) {
        return "<div><dt>" + Http.esc(key) + "</dt><dd>" + valueHtml + "</dd></div>";
    }

    static String ficheHref(String agentId, Map<String, Object> c) {
        return PATH + "?agent=" + enc(agentId) + "&amp;claim=" + enc(str(c.get("id")));
    }

    private static String enc(String s) {
        return Http.esc(URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8));
    }

    static boolean bool(Object o) {
        return Boolean.TRUE.equals(o) || "true".equals(String.valueOf(o));
    }

    static String str(Object o) {
        if (o instanceof Double d && d == Math.rint(d)) {
            return String.valueOf(d.longValue()); // nombre entier relu d'un JSON : jamais « 5.0 ».
        }
        return o == null ? "" : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    static List<Map<String, Object>> maps(Object o) {
        return o instanceof List<?> l ? l.stream().map(ClaimPages::map).toList() : List.of();
    }
}
