package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.http.Http;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Issue #560 (#331-W1) — page « Mondes » : la liste des mondes (cartes) et la fiche d'un monde,
 * d'après le dernier relevé {@code world.list}.
 *
 * <p>Aucune création, aucun chargement, aucun déchargement ni aucune suppression n'est exposé, pas
 * même désactivé. Depuis l'issue #561, la fiche porte la section « Réglages »
 * ({@link WorldSettingsPages}) : relecture, aperçu sans écriture, puis application confirmée.</p>
 */
public final class WorldPages {

    static final String PATH = "/worlds";

    /**
     * @param agentId     agent ciblé
     * @param details     dernier relevé réussi ({@code null} = jamais relevé)
     * @param headerHtml  sélecteur d'agent + barre « Rafraîchir » (HTML sûr)
     * @param releveHtml  date du relevé (HTML sûr, peut être vide)
     * @param selected    monde dont afficher la fiche ({@code ""} = la liste)
     * @param settingsHtml section « Réglages » de la fiche (issue #561, HTML sûr, peut être vide)
     */
    public record View(String agentId, Map<String, Object> details, String headerHtml, String releveHtml,
                       String selected, String settingsHtml) {

        public View(String agentId, Map<String, Object> details, String headerHtml, String releveHtml, String selected) {
            this(agentId, details, headerHtml, releveHtml, selected, "");
        }
    }

    private WorldPages() {
    }

    public static String render(View v) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("world", "Mondes",
                "Tous les mondes du serveur, leur gestionnaire et ce qui en dépend. Un monde n'a aucun rôle "
                        + "(Hub, Wild, Claims) tant que la configuration ne le désigne pas. Rien n'est créé, chargé, "
                        + "déchargé ni supprimé d'ici ; les réglages d'un monde chargé se modifient dans sa fiche, après "
                        + "aperçu et confirmation.", ""));
        sb.append(v.headerHtml());
        if (v.agentId().isEmpty()) {
            return sb.toString();
        }
        if (v.details() == null) {
            return sb.append(Ui.empty("world", "Aucun relevé chargé — cliquer sur « Rafraîchir les mondes »."))
                    .toString();
        }
        if (!v.releveHtml().isEmpty()) {
            sb.append("<p class=\"muted small\">Relevé du serveur : ").append(v.releveHtml()).append("</p>");
        }
        List<Map<String, Object>> worlds = maps(v.details().get("worlds"));
        sb.append(onlineLine(v.details().get("onlinePlayers"), worlds));
        Map<String, Object> mv = map(v.details().get("multiverse"));
        if (!v.selected().isEmpty()) {
            return sb.append(fiche(v, worlds, mv)).toString();
        }
        for (Map<String, Object> issue : maps(v.details().get("issues"))) {
            sb.append(issueHtml(issue, "le serveur"));
        }
        sb.append(list(v.agentId(), worlds, mv));
        return sb.toString();
    }

    /**
     * Total des joueurs connectés, relevé au même instant que les compteurs par monde : si leur somme
     * en diffère, le relevé le dit au lieu d'afficher des « 0 » trompeurs. Absent (ancien plugin) → rien.
     */
    static String onlineLine(Object onlinePlayers, List<Map<String, Object>> worlds) {
        if (!(onlinePlayers instanceof Number total)) {
            return "";
        }
        int inWorlds = worlds.stream().map(w -> w.get("players")).filter(Number.class::isInstance)
                .mapToInt(n -> ((Number) n).intValue()).sum();
        StringBuilder sb = new StringBuilder("<p class=\"muted small\">")
                .append(total.intValue()).append(" joueur(s) connecté(s) sur le serveur à ce relevé");
        if (inWorlds != total.intValue()) {
            sb.append(" — <strong>attention</strong> : les mondes n'en comptent que ").append(inWorlds)
                    .append(". Rafraîchir les mondes ; si l'écart persiste, le signaler.");
        }
        return sb.append("</p>").toString();
    }

    // ---- Liste ----------------------------------------------------------------------------------

    private static String list(String agentId, List<Map<String, Object>> worlds, Map<String, Object> mv) {
        StringBuilder sb = new StringBuilder();
        long loaded = worlds.stream().filter(w -> bool(w.get("loaded"))).count();
        long withRole = worlds.stream().filter(w -> !list(w.get("roles")).isEmpty()).count();
        long essential = worlds.stream().filter(w -> bool(w.get("essential"))).count();
        long withIssue = worlds.stream().filter(WorldPages::hasProblem).count();
        String chips = AgentPages.filterBtn("", "Tous · " + worlds.size(), true)
                + AgentPages.filterBtn("loaded", "Chargés · " + loaded, false)
                + AgentPages.filterBtn("role", "Avec rôle · " + withRole, false)
                + AgentPages.filterBtn("essential", "Essentiels · " + essential, false)
                + AgentPages.filterBtn("issue", "Avec problème · " + withIssue, false);
        sb.append(AgentPages.listControls("worlds", "Rechercher un monde", chips));
        sb.append("<p class=\"muted small\">").append(Http.esc(multiverseLine(mv))).append("</p>");
        if (worlds.isEmpty()) {
            return sb.append(Ui.empty("world", "Aucun monde relevé.")).toString();
        }
        sb.append("<div class=\"w-grid\">");
        for (Map<String, Object> w : worlds) {
            sb.append(card(agentId, w));
        }
        sb.append("</div>");
        sb.append("<p class=\"muted small w-small-note\">« inconnu » s'affiche quand une donnée ne peut pas être "
                + "relue (par exemple la génération d'un monde non chargé que Multiverse ne décrit pas).</p>");
        return sb.toString();
    }

    private static String card(String agentId, Map<String, Object> w) {
        String name = str(w.get("name"));
        boolean loaded = bool(w.get("loaded"));
        List<String> cats = new ArrayList<>();
        cats.add(loaded ? "loaded" : "unloaded");
        if (!list(w.get("roles")).isEmpty()) {
            cats.add("role");
        }
        if (bool(w.get("essential"))) {
            cats.add("essential");
        }
        if (hasProblem(w)) {
            cats.add("issue");
        }
        StringBuilder sb = new StringBuilder("<article class=\"w-card").append(loaded ? "" : " muted-card")
                .append("\" data-filter-item=\"worlds\" data-filter-cat=\"").append(String.join(" ", cats))
                .append("\" data-filter-text=\"").append(Http.esc(name + " " + rolesText(w))).append("\">");
        sb.append("<div class=\"w-head\">").append(dimensionIcon(str(w.get("environment"))))
                .append("<div><div class=\"w-name\">").append(Http.esc(name)).append("</div><div class=\"w-sub\">")
                .append(Http.esc(generationLine(w))).append("</div></div><div class=\"w-pills\">")
                .append(loadedPill(loaded)).append(essentialPill(w)).append("</div></div>");
        sb.append("<div class=\"w-roles\">").append(rolesHtml(w)).append("<span class=\"mgr\">")
                .append(Http.esc(managerLabel(w))).append("</span></div>");
        sb.append("<ul class=\"w-facts\"><li><span class=\"k\">Joueurs</span><span>")
                .append(w.get("players") == null ? "—" : Http.esc(str(w.get("players"))))
                .append("</span></li><li><span class=\"k\">Lié à</span><span>")
                .append(Http.esc(linksSummary(map(w.get("links"))))).append("</span></li></ul>");
        List<Map<String, Object>> issues = maps(w.get("issues"));
        long problems = issues.stream().filter(i -> !"info".equals(str(i.get("severity")))).count();
        if (problems > 0) {
            sb.append("<p class=\"w-note w-note-err\">").append(Icons.icon("warning")).append(problems)
                    .append(" problème(s) — voir la fiche</p>");
        } else if (!issues.isEmpty()) {
            sb.append("<p class=\"w-note\">").append(Icons.icon("info")).append(issues.size())
                    .append(" information(s) — voir la fiche</p>");
        } else if (bool(w.get("main"))) {
            sb.append("<p class=\"w-note\">Monde principal du serveur : il ne peut jamais être déchargé.</p>");
        } else if ("RPGQUEST".equals(str(w.get("manager")))) {
            sb.append("<p class=\"w-note\">Créé par <code>/rpgadmin world create</code> : rechargé par RPGQuest, "
                    + "absent du fichier Multiverse.</p>");
        }
        sb.append("<div class=\"w-foot\"><a class=\"btn sm secondary\" href=\"").append(ficheHref(agentId, name))
                .append("\">").append(Icons.icon("search")).append("Ouvrir la fiche</a></div></article>");
        return sb.toString();
    }

    // ---- Fiche ----------------------------------------------------------------------------------

    private static String fiche(View v, List<Map<String, Object>> worlds, Map<String, Object> mv) {
        String back = "<a class=\"btn sm secondary\" href=\"" + PATH + "?agent=" + enc(v.agentId()) + "\">"
                + Icons.icon("back") + "Tous les mondes</a>";
        Map<String, Object> w = worlds.stream().filter(x -> str(x.get("name")).equals(v.selected())).findFirst()
                .orElse(null);
        if (w == null) {
            return Ui.banner("warn", "Le monde <code>" + Http.esc(v.selected()) + "</code> n'apparaît pas dans le "
                    + "dernier relevé. Rafraîchir, ou revenir à la liste.") + "<div class=\"btnrow\">" + back + "</div>";
        }
        String name = str(w.get("name"));
        StringBuilder sb = new StringBuilder("<div class=\"btnrow mb-3\">").append(back).append("</div>");
        sb.append("<div class=\"w-title\" role=\"heading\" aria-level=\"2\">").append(Http.esc(name)).append(" ").append(loadedPill(bool(w.get("loaded"))))
                .append(essentialPill(w)).append("</div>");
        sb.append("<div class=\"w-cols\"><div>");
        sb.append("<section class=\"card w-sec\"><h3 class=\"w-h\">").append(Icons.icon("lock"))
                .append("Fixé à la création</h3><dl class=\"w-dl\">")
                .append(dl("Dimension", dimensionLabel(str(w.get("environment")))))
                .append(dl("Génération", generationDetail(w)))
                .append(dlHtml("Graine", w.get("seed") == null ? unknown()
                        : "<code data-copy>" + Http.esc(str(w.get("seed"))) + "</code>"))
                .append(dl("Structures", yesNo(w.get("structures"), "générées", "désactivées")))
                .append(dl("Mode extrême", yesNo(w.get("hardcore"), "oui", "non")));
        if (w.get("generator") != null) {
            sb.append(dlHtml("Générateur", "<code>" + Http.esc(str(w.get("generator"))) + "</code>"));
        }
        if (w.get("generatorSettings") != null) {
            sb.append(dlHtml("Réglages du générateur", "<code>" + Http.esc(str(w.get("generatorSettings"))) + "</code>"));
        }
        sb.append("</dl></section>");
        sb.append("<section class=\"card w-sec\"><h3 class=\"w-h\">").append(Icons.icon("info"))
                .append("Réglages actuels</h3><dl class=\"w-dl\">")
                .append(dl("Difficulté", difficultyLabel(w.get("difficulty"))))
                .append(dl("PvP", yesNo(w.get("pvp"), "autorisé", "interdit")))
                .append(dl("Point d'arrivée", w.get("spawn") == null ? "inconnu" : str(w.get("spawn"))))
                .append(dl("Chargement au démarrage", autoLoadLabel(w)))
                .append("</dl><p class=\"field-help\">D'après la liste des mondes. Valeurs relues en détail et "
                        + "modification : section <a href=\"#").append(WorldSettingsPages.ANCHOR)
                .append("\">Réglages</a>.</p></section>");
        sb.append("</div><div>");
        sb.append(linksSection(v.agentId(), map(w.get("links"))));
        sb.append("<section class=\"card w-sec\"><h3 class=\"w-h\">").append(Icons.icon("users"))
                .append("Rôle LodyQuests</h3>").append(roleText(w)).append("</section>");
        sb.append("<section class=\"card w-sec\"><h3 class=\"w-h\">").append(Icons.icon("diagnostics"))
                .append("État</h3><p>").append(loadedPill(bool(w.get("loaded"))));
        if (w.get("players") != null) {
            sb.append(" · ").append(Http.esc(str(w.get("players")))).append(" joueur(s) présent(s)");
        }
        sb.append(" · ").append(Http.esc(managerLabel(w))).append("</p>");
        List<Map<String, Object>> issues = maps(w.get("issues"));
        if (issues.isEmpty()) {
            sb.append("<p class=\"muted\">Aucun problème détecté.</p>");
        }
        for (Map<String, Object> issue : issues) {
            sb.append(issueHtml(issue, name));
        }
        sb.append("<p class=\"field-help\">").append(Http.esc(multiverseLine(mv))).append("</p></section>");
        sb.append("</div></div>");
        sb.append(v.settingsHtml());
        return sb.toString();
    }

    private static String linksSection(String agentId, Map<String, Object> l) {
        String a = "?agent=" + enc(agentId);
        StringBuilder sb = new StringBuilder("<section class=\"card w-sec\"><h3 class=\"w-h\">")
                .append(Icons.icon("travel")).append("Éléments liés</h3><ul class=\"w-links\">");
        for (Map<String, Object> p : maps(l.get("portalsFrom"))) {
            sb.append(li("box-arrow-right", "Portail <strong>" + Http.esc(str(p.get("id"))) + "</strong> part d'ici vers <code>"
                    + Http.esc(str(p.get("destinationWorld"))) + "</code> · " + Http.esc(strategyLabel(str(p.get("strategy"))))
                    + portalState(p)));
        }
        for (Map<String, Object> p : maps(l.get("portalsTo"))) {
            sb.append(li("box-arrow-in-right", "Portail <strong>" + Http.esc(str(p.get("id"))) + "</strong> arrive ici depuis <code>"
                    + Http.esc(str(p.get("world"))) + "</code> · " + Http.esc(strategyLabel(str(p.get("strategy"))))
                    + portalState(p)));
        }
        if (maps(l.get("portalsFrom")).isEmpty() && maps(l.get("portalsTo")).isEmpty()) {
            sb.append(li("box-arrow-right", "Aucun portail entre mondes"));
        } else {
            sb.append(li("door-open", "Zones, arrivées et règles des portails : <a href=\"/portals" + a
                    + "\">Portails</a>"));
        }
        sb.append(li("geo-alt", placesText(maps(l.get("sites")), "emplacement(s) de bâtiment")
                + " · <a href=\"/buildings/sites" + a + "\">Emplacements</a>"));
        sb.append(li("signpost-split", Http.esc(str(l.get("waypoints"))) + " waypoint(s) · génération automatique : <strong>"
                + (bool(l.get("autoWaypoints")) ? "active" : "désactivée") + "</strong> · <a href=\"/travel" + a
                + "\">Voyages</a>"));
        sb.append(li("bug", placesText(maps(l.get("mobs")), "profil(s) de mob qui citent ce monde")
                + " · <a href=\"/mobs" + a + "\">Mobs</a>"));
        sb.append(li("person-badge", placesText(maps(l.get("npcSpots")), "emplacement(s) PNJ")
                + " · <a href=\"/npcs" + a + "\">PNJ</a>"));
        sb.append(li("buildings", placesText(maps(l.get("cities")), "ville(s)")
                + " · <a href=\"/cities" + a + "\">Villes</a>"));
        return sb.append("</ul></section>").toString();
    }

    // ---- Libellés -------------------------------------------------------------------------------

    static String dimensionLabel(String environment) {
        return switch (environment) {
            case "NORMAL" -> "Monde normal";
            case "NETHER" -> "Nether";
            case "THE_END" -> "End";
            case "CUSTOM" -> "Personnalisée";
            case "" -> "inconnue";
            default -> environment;
        };
    }

    private static String generationLine(Map<String, Object> w) {
        String env = str(w.get("environment"));
        return dimensionLabel(env) + " · génération " + switch (env) {
            case "NETHER" -> "Nether";
            case "THE_END" -> "End";
            default -> typeLabel(str(w.get("worldType")));
        };
    }

    private static String generationDetail(Map<String, Object> w) {
        String env = str(w.get("environment"));
        if ("NETHER".equals(env) || "THE_END".equals(env)) {
            return dimensionLabel(env);
        }
        return typeLabel(str(w.get("worldType")));
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case "NORMAL" -> "normale";
            case "FLAT" -> "plate";
            case "AMPLIFIED" -> "amplifiée";
            case "LARGE_BIOMES" -> "grands biomes";
            case "" -> "inconnue";
            default -> type.toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static String managerLabel(Map<String, Object> w) {
        return switch (str(w.get("manager"))) {
            case "MULTIVERSE" -> "géré par Multiverse";
            case "RPGQUEST" -> "géré par RPGQuest (worlds.yml)";
            case "SERVER" -> bool(w.get("main")) ? "géré par le serveur (monde principal)" : "géré par le serveur";
            default -> "gestionnaire inconnu";
        };
    }

    private static String difficultyLabel(Object raw) {
        return switch (str(raw)) {
            case "PEACEFUL" -> "Paisible";
            case "EASY" -> "Facile";
            case "NORMAL" -> "Normale";
            case "HARD" -> "Difficile";
            case "" -> "inconnue";
            default -> str(raw);
        };
    }

    private static String strategyLabel(String strategy) {
        return switch (strategy) {
            case "RANDOM_SAFE" -> "arrivée sur un point sûr aléatoire";
            case "WORLD_SPAWN" -> "arrivée au point d'arrivée du monde";
            case "CLAIM_HOME" -> "arrivée sur le claim du joueur";
            default -> strategy;
        };
    }

    private static String autoLoadLabel(Map<String, Object> w) {
        return switch (str(w.get("manager"))) {
            case "RPGQUEST" -> "oui (RPGQuest)";
            case "SERVER" -> bool(w.get("main")) || !bool(w.get("multiverseKnown")) ? "oui (serveur)"
                    : yesNo(w.get("autoLoad"), "oui (Multiverse)", "non (Multiverse)");
            case "MULTIVERSE" -> yesNo(w.get("autoLoad"), "oui (Multiverse)", "non (Multiverse)");
            default -> "inconnu";
        };
    }

    private static String multiverseLine(Map<String, Object> mv) {
        return bool(mv.get("available"))
                ? "Multiverse-Core " + str(mv.get("version")) + " lu : les mondes non chargés qu'il connaît sont listés."
                : "Multiverse-Core non lu (" + str(mv.get("reason")) + ") : seuls les mondes chargés sont listés.";
    }

    private static String rolesText(Map<String, Object> w) {
        return String.join(" ", list(w.get("roles")).stream().map(WorldPages::roleLabel).toList());
    }

    private static String rolesHtml(Map<String, Object> w) {
        List<String> roles = list(w.get("roles"));
        if (roles.isEmpty()) {
            return "<span class=\"role none\">Aucun rôle</span>";
        }
        StringBuilder sb = new StringBuilder();
        for (String r : roles) {
            sb.append("<span class=\"role ").append(Http.esc(r.toLowerCase(java.util.Locale.ROOT))).append("\">")
                    .append(Http.esc(roleLabel(r))).append("</span>");
        }
        return sb.toString();
    }

    private static String roleLabel(String role) {
        return switch (role) {
            case "HUB" -> "Hub";
            case "WILD" -> "Wild";
            case "CLAIMS" -> "Claims";
            default -> role;
        };
    }

    private static String roleText(Map<String, Object> w) {
        List<String> roles = list(w.get("roles"));
        if (roles.isEmpty()) {
            return "<p class=\"muted\">Aucun. Ce monde n'est ni le Hub, ni le Wild, ni Claims : aucune protection ni "
                    + "règle LodyQuests automatique ne s'y applique." + (bool(w.get("main"))
                    ? " Il reste <strong>essentiel</strong> : c'est le monde principal du serveur." : "") + "</p>";
        }
        return "<p>" + rolesHtml(w) + "</p><p class=\"muted\">Rôle fixé par la configuration "
                + "(<code>hub.world</code>, <code>travel.wild-world</code>, <code>claims.world</code>). Monde "
                + "<strong>essentiel</strong> : il ne doit être ni déchargé ni supprimé. Changer de monde Hub, Wild ou "
                + "Claims relève des paramètres du serveur, pas de cette page.</p>";
    }

    private static String linksSummary(Map<String, Object> l) {
        List<String> parts = new ArrayList<>();
        int from = maps(l.get("portalsFrom")).size();
        int to = maps(l.get("portalsTo")).size();
        if (from > 0) {
            parts.add("départ de " + from + " portail(s)");
        }
        if (to > 0) {
            parts.add("arrivée de " + String.join(", ", maps(l.get("portalsTo")).stream()
                    .map(p -> str(p.get("id"))).toList()));
        }
        addCount(parts, maps(l.get("sites")).size(), "emplacement(s)");
        addCount(parts, intOf(l.get("waypoints")), "waypoint(s)");
        addCount(parts, maps(l.get("mobs")).size(), "profil(s) de mob");
        addCount(parts, maps(l.get("npcSpots")).size(), "emplacement(s) PNJ");
        addCount(parts, maps(l.get("cities")).size(), "ville(s)");
        return parts.isEmpty() ? "—" : String.join(" · ", parts);
    }

    private static void addCount(List<String> parts, int n, String noun) {
        if (n > 0) {
            parts.add(n + " " + noun);
        }
    }

    // ---- Briques --------------------------------------------------------------------------------

    private static String issueHtml(Map<String, Object> issue, String subject) {
        String message = str(issue.get("message"));
        return DiagnosticHelp.render(str(issue.get("code")), str(issue.get("severity")), message, subject, message, "");
    }

    private static boolean hasProblem(Map<String, Object> w) {
        return maps(w.get("issues")).stream().anyMatch(i -> !"info".equals(str(i.get("severity"))));
    }

    private static String loadedPill(boolean loaded) {
        return loaded ? "<span class=\"pill ok\">Chargé</span>" : "<span class=\"pill pill--neutral\">Non chargé</span>";
    }

    private static String essentialPill(Map<String, Object> w) {
        return bool(w.get("essential")) ? " <span class=\"pill warn\" title=\"Monde principal ou monde à rôle : ni "
                + "déchargement ni suppression\">" + Icons.icon("lock") + "Essentiel</span>" : "";
    }

    private static String dimensionIcon(String environment) {
        return switch (environment) {
            case "NETHER" -> "<span class=\"w-ic ne\"><i class=\"bi bi-fire\" aria-hidden=\"true\"></i></span>";
            case "THE_END" -> "<span class=\"w-ic en\"><i class=\"bi bi-moon-stars\" aria-hidden=\"true\"></i></span>";
            default -> "<span class=\"w-ic ov\"><i class=\"bi bi-globe-europe-africa\" aria-hidden=\"true\"></i></span>";
        };
    }

    private static String portalState(Map<String, Object> p) {
        return bool(p.get("enabled")) ? "" : " · <em>désactivé</em>";
    }

    private static String placesText(List<Map<String, Object>> places, String noun) {
        if (places.isEmpty()) {
            return "0 " + noun;
        }
        List<String> names = places.stream().limit(5).map(p -> {
            // Noms de mob, waypoint ou ville : souvent stylés en MiniMessage → texte lisible, échappé.
            String name = MiniText.plain(str(p.get("name")));
            return Http.esc(name.isEmpty() ? str(p.get("id")) : name);
        }).toList();
        return places.size() + " " + noun + " : " + String.join(", ", names) + (places.size() > 5 ? "…" : "");
    }

    private static String li(String bi, String html) {
        return "<li><i class=\"bi bi-" + bi + "\" aria-hidden=\"true\"></i><span>" + html + "</span></li>";
    }

    private static String dl(String key, String value) {
        return dlHtml(key, Http.esc(value));
    }

    private static String dlHtml(String key, String valueHtml) {
        return "<div><dt>" + Http.esc(key) + "</dt><dd>" + valueHtml + "</dd></div>";
    }

    private static String unknown() {
        return "<span class=\"muted\">inconnue</span>";
    }

    private static String yesNo(Object raw, String yes, String no) {
        if (raw == null) {
            return "inconnu";
        }
        return bool(raw) ? yes : no;
    }

    static String ficheHref(String agentId, String world) {
        return PATH + "?agent=" + enc(agentId) + "&amp;world=" + enc(world);
    }

    private static String enc(String s) {
        return Http.esc(URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8));
    }

    private static boolean bool(Object o) {
        return Boolean.TRUE.equals(o) || "true".equals(String.valueOf(o));
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<Map<String, Object>> maps(Object o) {
        return o instanceof List<?> l ? l.stream().map(WorldPages::map).toList() : List.of();
    }

    private static List<String> list(Object o) {
        return o instanceof List<?> l ? l.stream().map(WorldPages::str).toList() : List.of();
    }
}
