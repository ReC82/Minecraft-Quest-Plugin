package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.http.Http;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Issue #564 (#228-P1) — page « Portails » : la liste des portails (cartes) et la fiche d'un
 * portail, d'après le dernier relevé {@code portal.list}. La présentation reprend celle de la page
 * Mondes ({@link WorldPages}).
 *
 * <p>Issue #565 (#228-P2) — avec {@code PORTAL_EDIT} : créer un portail en <strong>brouillon</strong>
 * (il ne téléporte personne), le renommer, régler sa zone tant qu'il n'est pas actif, le supprimer ;
 * avec {@code PORTAL_ENABLE} : l'activer ou le désactiver. Chaque formulaire porte l'empreinte du
 * relevé (le serveur refuse si le portail a changé) et une case de confirmation ; un portail
 * essentiel (vers le Hub, le Wild ou les Claims) en demande une seconde.</p>
 *
 * <p>Issue #567 (#228-P4) — conditions d'accès (portail non actif, {@code PORTAL_EDIT}), simulation
 * AUTORISÉ / REFUSÉ pour un joueur connecté (lecture seule, {@code PORTAL_READ}) et « Téléporter pour
 * test » du seul personnage lié à la session ({@code PORTAL_TEST}, confirmation forte).</p>
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
                       String selected, String file, WorldNames names, Manage manage, Verify verify,
                       String returnOf, Access access) {

        public View {
            names = names == null ? WorldNames.DEFAULTS : names;
            manage = manage == null ? Manage.NONE : manage;
            returnOf = returnOf == null ? "" : returnOf;
            access = access == null ? Access.NONE : access;
        }

        public View(String agentId, Map<String, Object> details, String headerHtml, String releveHtml,
                    String selected, String file, WorldNames names, Manage manage, Verify verify, String returnOf) {
            this(agentId, details, headerHtml, releveHtml, selected, file, names, manage, verify, returnOf, null);
        }

        public View(String agentId, Map<String, Object> details, String headerHtml, String releveHtml,
                    String selected, String file, WorldNames names, Manage manage) {
            this(agentId, details, headerHtml, releveHtml, selected, file, names, manage, null, "");
        }

        public View(String agentId, Map<String, Object> details, String headerHtml, String releveHtml,
                    String selected, String file) {
            this(agentId, details, headerHtml, releveHtml, selected, file, WorldNames.DEFAULTS, Manage.NONE);
        }
    }

    /**
     * Issue #566 — dernier « Vérifier sans appliquer » réussi du portail ouvert.
     *
     * @param atHtml date du résultat (HTML sûr)
     */
    public record Verify(Map<String, Object> details, String atHtml) {
    }

    /**
     * Issue #567 — dernière simulation du portail ouvert et bloc « Téléporter pour test ».
     *
     * @param simulation    dernière simulation terminée ({@code null} = aucune)
     * @param onlinePlayers joueurs connectés du dernier relevé (suggestions)
     * @param testHtml      bloc « Téléporter pour test », déjà rendu (HTML sûr, peut être vide)
     */
    public record Access(Simulation simulation, List<String> onlinePlayers, String testHtml) {

        public static final Access NONE = new Access(null, List.of(), "");

        public Access {
            onlinePlayers = onlinePlayers == null ? List.of() : List.copyOf(onlinePlayers);
            testHtml = testHtml == null ? "" : testHtml;
        }
    }

    /**
     * @param details détails du résultat (vide si l'action a échoué avant l'évaluation)
     * @param message message du serveur
     * @param atHtml  date (HTML sûr)
     */
    public record Simulation(Map<String, Object> details, String message, boolean success, String atHtml) {
    }

    /** Un emplacement #213 du dernier relevé {@code building.site.list}. */
    public record SiteOption(String id, String name, String world, String position, String facing) {
    }

    /**
     * Droits de la session et données des formulaires (issue #565). {@link #NONE} : aucun formulaire.
     *
     * @param csrf jeton CSRF de la session
     */
    public record Manage(String csrf, boolean canEdit, boolean canEnable, List<SiteOption> sites) {

        public static final Manage NONE = new Manage("", false, false, List.of());

        public Manage {
            sites = sites == null ? List.of() : List.copyOf(sites);
        }

        boolean any() {
            return canEdit || canEnable;
        }
    }

    private PortalPages() {
    }

    public static String render(View v) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("portal", "Portails",
                "Tous les portails du serveur : où ils se trouvent, où ils mènent et les règles que le serveur "
                        + "applique avant de téléporter. Un nouveau portail naît en brouillon : il ne téléporte "
                        + "personne tant qu'il n'est pas activé.", ""));
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
        sb.append("<div class=\"p-scope\">").append(list(v.agentId(), portals, v.names()));
        if (v.manage().canEdit()) {
            sb.append(createForm(v));
        }
        sb.append("</div>");
        return sb.toString();
    }

    // ---- Liste ----------------------------------------------------------------------------------

    private static String list(String agentId, List<Map<String, Object>> portals, WorldNames names) {
        StringBuilder sb = new StringBuilder();
        long active = portals.stream().filter(p -> bool(p.get("enabled"))).count();
        long drafts = portals.stream().filter(p -> "DRAFT".equals(str(p.get("state")))).count();
        long legacy = portals.stream().filter(PortalPages::legacy).count();
        long withIssue = portals.stream().filter(PortalPages::hasProblem).count();
        String chips = AgentPages.filterBtn("", "Tous · " + portals.size(), true)
                + AgentPages.filterBtn("active", "Actifs · " + active, false, "Aucun portail actif")
                + AgentPages.filterBtn("draft", "Brouillons · " + drafts, false, "Aucun brouillon")
                + AgentPages.filterBtn("disabled", "Désactivés · " + (portals.size() - active - drafts), false,
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
            sb.append(card(agentId, p, names));
        }
        sb.append("</div>").append(AgentPages.filterEmpty("portals", "Aucun portail"));
        sb.append("<p class=\"muted small w-small-note\">Les portails sont lus tels quels depuis les fichiers du "
                + "serveur : leurs règles Wild et Claims, puis leurs conditions d'accès, sont vérifiées par le serveur à "
                + "chaque passage. Chaque fiche permet de simuler un passage sans téléporter personne.</p>");
        return sb.toString();
    }

    private static String card(String agentId, Map<String, Object> p, WorldNames names) {
        String id = str(p.get("id"));
        boolean enabled = bool(p.get("enabled"));
        List<String> cats = new ArrayList<>();
        cats.add(enabled ? "active" : "DRAFT".equals(str(p.get("state"))) ? "draft" : "disabled");
        if (legacy(p)) {
            cats.add("legacy");
        }
        if (hasProblem(p)) {
            cats.add("issue");
        }
        StringBuilder sb = new StringBuilder("<article class=\"w-card").append(enabled ? "" : " muted-card")
                .append("\" data-filter-item=\"portals\" data-filter-cat=\"").append(String.join(" ", cats))
                .append("\" data-filter-text=\"").append(Http.esc(id + " " + str(p.get("name")) + " "
                        + str(p.get("world")) + " " + str(p.get("destinationWorld")) + " "
                        + names.name(str(p.get("world"))) + " " + names.name(str(p.get("destinationWorld"))))).append("\">");
        sb.append("<div class=\"w-head\"><span class=\"w-ic ov\">").append(Icons.icon("portal"))
                .append("</span><div><div class=\"w-name\">").append(Http.esc(str(p.get("name"))))
                .append("</div><div class=\"w-sub\"><code>").append(Http.esc(id)).append("</code></div></div>")
                .append("<div class=\"w-pills\">").append(statePill(p)).append(legacyPill(p)).append("</div></div>");
        sb.append("<ul class=\"w-facts\"><li><span class=\"k\">Départ</span><span>")
                .append(worldName(names, str(p.get("world")))).append(" ").append(Http.esc(str(p.get("min"))))
                .append(" → ").append(Http.esc(str(p.get("max")))).append("</span></li>")
                .append("<li><span class=\"k\">Arrivée</span><span>").append(destinationHtml(p, names)).append(" · ")
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

    /**
     * Fiche d'un portail, en une seule colonne pleine largeur (retour propriétaire du 22/10 sur #566) :
     * résumé et diagnostic, puis les outils de test regroupés, puis un bloc par sujet — chaque
     * formulaire de modification y est replié. La numérotation suit l'ordre réel des blocs affichés.
     */
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
        StringBuilder sb = new StringBuilder("<div class=\"p-fiche\"><div class=\"btnrow mb-3\">").append(back)
                .append("</div>");
        sb.append("<div class=\"w-title\" role=\"heading\" aria-level=\"2\">").append(Http.esc(str(p.get("name"))))
                .append(" <code>").append(Http.esc(str(p.get("id")))).append("</code> ").append(statePill(p))
                .append(legacyPill(p)).append("</div>");
        int[] n = {0};
        java.util.function.Consumer<java.util.function.IntFunction<String>> add = block -> {
            String html = block.apply(n[0] + 1);
            if (!html.isEmpty()) {
                n[0]++;
                sb.append(html);
            }
        };
        add.accept(num -> summarySection(v, p, num));
        add.accept(num -> PortalForms.toolsSection(v, p, num));
        add.accept(num -> locationSection(v, p, num));
        add.accept(num -> PortalForms.constructionSection(v, p, num));
        add.accept(num -> arrivalSection(v, p, num));
        add.accept(num -> guardsSection(v, p, num));
        add.accept(num -> PortalForms.messagesSection(v, p, num));
        add.accept(num -> PortalForms.manageSection(v, p, num));
        sb.append(PortalForms.historySection(v, p));
        return sb.append("</div>").toString();
    }

    /** Titre numéroté d'un bloc de la fiche. */
    static String heading(String icon, int num, String title) {
        return "<h3 class=\"w-h\">" + Icons.icon(icon) + num + " · " + Http.esc(title) + "</h3>";
    }

    /** Bloc 1 : l'essentiel en une ligne de faits, l'état, le diagnostic et l'activation. */
    private static String summarySection(View v, Map<String, Object> p, int num) {
        StringBuilder sb = new StringBuilder("<section class=\"card w-sec\" id=\"portal-summary\">")
                .append(heading("diagnostics", num, "Résumé et diagnostic")).append("<div class=\"p-facts\">");
        sb.append(fact("État", statePill(p) + (bool(p.get("essential"))
                ? " <span class=\"pill pill--neutral\" title=\"Mène au Hub, au Wild ou aux Claims\">Essentiel</span>" : "")));
        sb.append(fact("Départ", worldName(v.names(), str(p.get("world"))) + "<br><span class=\"muted small\">"
                + Http.esc(str(p.get("min"))) + " → " + Http.esc(str(p.get("max"))) + "</span>"));
        String point = legacy(p) ? "" : str(p.get("destinationPoint"));
        sb.append(fact("Arrivée", destinationHtml(p, v.names()) + "<br><span class=\"muted small\">"
                + Http.esc(strategyLabel(str(p.get("strategy"))) + (point.isEmpty() ? "" : " · " + point)) + "</span>"));
        List<String> access = new ArrayList<>();
        maps(p.get("guards")).forEach(g -> access.add(guardShort(str(g.get("code")))));
        int conditions = maps(p.get("conditions")).size();
        if (conditions > 0) {
            access.add(conditions + " condition(s)");
        }
        sb.append(fact("Accès", access.isEmpty() ? "<span class=\"muted\">libre</span>"
                : Http.esc(String.join(" · ", access))));
        if (!legacy(p)) {
            String linked = str(map(p.get("construction")).get("linked"));
            sb.append(fact("Construction", linked.isEmpty() ? "<span class=\"muted\">aucune</span>"
                    : "<code>" + Http.esc(linked) + "</code>"));
        }
        List<String> returns = new ArrayList<>(list(p.get("returnedBy")).stream().map(r -> portalLink(v, r)).toList());
        if (!str(p.get("returnOf")).isEmpty()) {
            returns.add("retour de " + portalLink(v, str(p.get("returnOf"))));
        }
        if (!returns.isEmpty()) {
            sb.append(fact("Aller-retour", String.join(", ", returns)));
        }
        sb.append("</div>");
        if (v.verify() != null) {
            sb.append(PortalForms.verifyLine(v, p));
        }
        List<Map<String, Object>> issues = maps(p.get("issues"));
        if (issues.isEmpty()) {
            sb.append("<p class=\"muted\">Aucun problème détecté.</p>");
        }
        for (Map<String, Object> issue : issues) {
            sb.append(issueHtml(issue, str(p.get("id"))));
        }
        sb.append(PortalForms.activationBlock(v, p));
        return sb.append("</section>").toString();
    }

    private static String fact(String key, String valueHtml) {
        return "<div class=\"p-fact\"><span class=\"k\">" + Http.esc(key) + "</span><span class=\"v\">" + valueHtml
                + "</span></div>";
    }

    private static String locationSection(View v, Map<String, Object> p, int num) {
        String a = "?agent=" + enc(v.agentId());
        return "<section class=\"card w-sec\" id=\"portal-location\">" + heading("target", num, "Emplacement")
                + "<dl class=\"w-dl\">"
                + dlHtml("Monde de départ", worldHtml(v.names(), a, str(p.get("world")), str(p.get("worldRole")),
                        bool(p.get("worldLoaded"))))
                + dl("Coin minimum", str(p.get("min")))
                + dl("Coin maximum", str(p.get("max")))
                + dl("Taille (x × y × z)", str(p.get("size")) + " blocs")
                + siteHtml(v, p)
                + dlHtml("Fichier", legacy(p) ? "<code>portals/" + Http.esc(str(p.get("id"))) + ".yml</code>"
                        : "<code>world-portals/" + Http.esc(str(p.get("file"))) + "</code>")
                + "</dl><p class=\"field-help\">Zone d'activation telle que le serveur la lit.</p>"
                + PortalForms.zoneEditor(v, p) + "</section>";
    }

    private static String arrivalSection(View v, Map<String, Object> p, int num) {
        String a = "?agent=" + enc(v.agentId());
        StringBuilder sb = new StringBuilder("<section class=\"card w-sec\" id=\"portal-arrival\">")
                .append(heading("travel", num, "Téléportation et arrivée")).append("<dl class=\"w-dl\">")
                .append(dlHtml("Monde d'arrivée", str(p.get("destinationWorld")).isEmpty()
                        ? "<span class=\"muted\">inconnu</span>"
                        : worldHtml(v.names(), a, str(p.get("destinationWorld")), str(p.get("destinationRole")),
                        bool(p.get("destinationLoaded")))));
        if (!str(p.get("destinationId")).isEmpty()) {
            sb.append(dlHtml("Destination nommée", "<code>" + Http.esc(str(p.get("destinationId"))) + "</code>"));
        }
        sb.append(dl("Arrivée", strategyLabel(str(p.get("strategy")))));
        if (!legacy(p) && !str(p.get("destinationPoint")).isEmpty()) {
            sb.append(dl("Point d'arrivée", str(p.get("destinationPoint"))));
        }
        if (!str(p.get("returnOf")).isEmpty()) {
            sb.append(dlHtml("Retour de", portalLink(v, str(p.get("returnOf")))));
        }
        List<String> returnedBy = list(p.get("returnedBy"));
        if (!returnedBy.isEmpty()) {
            sb.append(dlHtml("Portail retour", String.join(", ", returnedBy.stream().map(r -> portalLink(v, r)).toList())));
        }
        sb.append("</dl>");
        sb.append(bullets(list(p.get("arrival"))));
        if (!list(p.get("trigger")).isEmpty()) {
            sb.append("<details class=\"p-more\"><summary>Déclenchement</summary>").append(bullets(list(p.get("trigger"))))
                    .append("</details>");
        }
        return sb.append(PortalForms.arrivalEditor(v, p)).append("</section>").toString();
    }

    private static String guardsSection(View v, Map<String, Object> p, int num) {
        StringBuilder sb = new StringBuilder("<section class=\"card w-sec\" id=\"portal-rules\">")
                .append(heading("lock", num, "Règles appliquées par le serveur"));
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
                sb.append("<details class=\"p-more\"><summary>Textes actuels montrés au joueur</summary>")
                        .append("<dl class=\"w-dl\">");
                for (Map<String, Object> t : texts) {
                    sb.append(dlHtml(str(t.get("label")), "« " + Http.esc(str(t.get("value"))) + " »"));
                }
                sb.append("</dl><p class=\"field-help\">Relus du serveur.</p></details>");
            }
        }
        if (!conditions.isEmpty()) {
            sb.append("<h4 class=\"w-h\">Conditions d'accès</h4><dl class=\"w-dl\">");
            for (Map<String, Object> c : conditions) {
                sb.append(dl(str(c.get("label")), str(c.get("value"))));
            }
            sb.append("</dl>");
            if ("WORLD_PORTAL".equals(str(p.get("kind")))) {
                sb.append("<p class=\"field-help\">Vérifiées à chaque passage, après les règles ci-dessus. Un joueur "
                        + "refusé reste sur place avec le message de la condition.</p>");
            }
        }
        return sb.append(PortalForms.accessEditor(v, p)).append("</section>").toString();
    }

    // ---- Libellés -------------------------------------------------------------------------------

    static String strategyLabel(String strategy) {
        return switch (strategy) {
            case "RANDOM_SAFE" -> "point sûr aléatoire";
            case "WORLD_SPAWN" -> "point d'arrivée du monde";
            case "CLAIM_HOME" -> "claim du joueur, sinon point sûr";
            case "FIXED_POINT" -> "point fixe";
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

    /** Nom affiché du monde (#81) puis son identifiant technique, inchangé. */
    private static String worldName(WorldNames names, String world) {
        String shown = names.name(world);
        return shown.equals(world) ? "<code>" + Http.esc(world) + "</code>"
                : Http.esc(shown) + " <code>" + Http.esc(world) + "</code>";
    }

    private static String worldHtml(WorldNames names, String agentQuery, String world, String role, boolean loaded) {
        StringBuilder sb = new StringBuilder("<a href=\"").append(WorldPages.PATH).append(agentQuery)
                .append("&amp;world=").append(enc(world)).append("\">").append(worldName(names, world))
                .append("</a>");
        if (!role.isEmpty()) {
            sb.append(" <span class=\"role ").append(Http.esc(role.toLowerCase(java.util.Locale.ROOT))).append("\">")
                    .append(Http.esc(roleLabel(role))).append("</span>");
        }
        sb.append(" ").append(loaded ? "<span class=\"pill ok\">Chargé</span>"
                : "<span class=\"pill pill--neutral\">Non chargé</span>");
        return sb.toString();
    }

    private static String destinationHtml(Map<String, Object> p, WorldNames names) {
        String world = str(p.get("destinationWorld"));
        if (world.isEmpty()) {
            return "<span class=\"muted\">inconnue</span>";
        }
        return worldName(names, world) + (Boolean.FALSE.equals(p.get("destinationLoaded"))
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

    static String statePill(Map<String, Object> p) {
        if (bool(p.get("enabled"))) {
            return "<span class=\"pill ok\">Actif</span>";
        }
        return "DRAFT".equals(str(p.get("state"))) ? "<span class=\"pill warn\">Brouillon</span>"
                : "<span class=\"pill pill--neutral\">Désactivé</span>";
    }

    private static String siteHtml(View v, Map<String, Object> p) {
        String site = str(p.get("site"));
        if (site.isEmpty()) {
            return "";
        }
        String name = str(p.get("siteName"));
        String size = num(p.get("width")) > 0 ? " · zone " + num(p.get("width")) + " × " + num(p.get("height"))
                + " × " + num(p.get("depth")) + " (largeur × hauteur × profondeur)" : "";
        return dlHtml("Emplacement", "<a href=\"/buildings/sites?agent=" + enc(v.agentId()) + "\">"
                + (name.isEmpty() ? "" : Http.esc(name) + " ") + "<code>" + Http.esc(site) + "</code></a>"
                + Http.esc(size));
    }

    private static String portalLink(View v, String id) {
        return "<a href=\"" + PATH + "?agent=" + enc(v.agentId()) + "&amp;portal=" + enc(id) + "\"><code>"
                + Http.esc(id) + "</code></a>";
    }

    private static String createForm(View v) {
        return PortalForms.createSection(v);
    }

    static int num(Object o) {
        try {
            return o instanceof Number n ? n.intValue() : Integer.parseInt(str(o));
        } catch (NumberFormatException e) {
            return 0;
        }
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

    static String dl(String key, String value) {
        return dlHtml(key, Http.esc(value));
    }

    static String dlHtml(String key, String valueHtml) {
        return "<div><dt>" + Http.esc(key) + "</dt><dd>" + valueHtml + "</dd></div>";
    }

    static String ficheHref(String agentId, Map<String, Object> p) {
        String file = str(p.get("file"));
        return PATH + "?agent=" + enc(agentId) + "&amp;portal=" + enc(str(p.get("id")))
                + (file.isEmpty() ? "" : "&amp;file=" + enc(file));
    }

    static String enc(String s) {
        return Http.esc(URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8));
    }

    static boolean bool(Object o) {
        return Boolean.TRUE.equals(o) || "true".equals(String.valueOf(o));
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    static List<Map<String, Object>> maps(Object o) {
        return o instanceof List<?> l ? l.stream().map(PortalPages::map).toList() : List.of();
    }

    private static List<String> list(Object o) {
        return o instanceof List<?> l ? l.stream().map(PortalPages::str).toList() : List.of();
    }
}
