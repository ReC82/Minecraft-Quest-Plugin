package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.http.Http;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pages « Catalogues de quêtes » (issue #414, refonte du parcours livré par #369).
 *
 * <p>Un écran = une intention : l'accueil liste les catalogues, la fiche se lit, et modifier,
 * dupliquer ou supprimer ouvrent chacun leur propre écran court. Le vocabulaire visible est celui
 * du joueur (« liste de quêtes », « PNJ qui la proposent ») ; identifiants, fréquences de tirage et
 * anti-répétition restent accessibles dans « Réglages avancés ».</p>
 *
 * <p>Aucune règle nouvelle : tout passe par les actions {@code quest.catalog.*} existantes, donc
 * par leur validation côté panel puis côté serveur, et par leurs permissions. Rendu pur (aucun
 * accès au magasin) : {@link AgentPages} rassemble le relevé et le contexte.</p>
 */
final class QuestCatalogPages {

    static final String PAGE = "/quests/catalogs";
    /** Au-delà, le relevé est signalé comme ancien et on invite à l'actualiser avant de modifier. */
    static final long STALE_AFTER_MINUTES = 30;
    /** Recherche affichée sur l'accueil à partir de ce nombre de catalogues. */
    static final int SEARCH_THRESHOLD = 6;
    private static final int DEFAULT_WEIGHT = 10;

    private QuestCatalogPages() {
    }

    // =====================================================================================
    //  Données : le relevé quest.catalog.list, lu une fois
    // =====================================================================================

    record Entry(String quest, int weight, boolean enabled, boolean exists, String title, boolean repeatable,
                 String cooldown, int steps) {
        String label() {
            return exists && !title.isBlank() ? title : MiniText.prettifyId(quest.replaceFirst("^rpgquest:", ""));
        }

        boolean offered() {
            return enabled && exists;
        }
    }

    record Catalog(String id, String name, String description, boolean enabled, int avoidRecent,
                   List<Entry> entries, List<String> usedBy, List<String> warnings) {
        long offeredCount() {
            return entries.stream().filter(Entry::offered).count();
        }

        boolean hasMissing() {
            return entries.stream().anyMatch(e -> !e.exists());
        }
    }

    record QuestRef(String id, String title, String category) {
        String label() {
            return title.isBlank() ? MiniText.prettifyId(id.replaceFirst("^rpgquest:", "")) : title;
        }
    }

    /** Un modèle de PNJ récurrent ; {@code offersCatalog} : son dialogue sait proposer une quête de catalogue. */
    record Template(String id, String name, String npcId, boolean enabled, List<String> catalogs,
                    boolean offersCatalog) {
    }

    /**
     * @param templates modèles de PNJ, ou {@code null} si le plugin ne les expose pas encore (avant
     *                  #414) : le choix des PNJ depuis cette page est alors indisponible
     */
    record Survey(boolean available, List<Catalog> catalogs, Map<String, String> issues, List<QuestRef> quests,
                  List<String> warnings, List<Template> templates) {
        Catalog find(String id) {
            return catalogs.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
        }

        Template template(String id) {
            return templates == null ? null
                    : templates.stream().filter(t -> t.id().equals(id)).findFirst().orElse(null);
        }

        String templateName(String id) {
            Template t = template(id);
            return t == null || t.name().isBlank() ? id : t.name();
        }

        boolean canAssign() {
            return templates != null;
        }
    }

    /**
     * Contexte d'affichage.
     *
     * @param basedOn     identifiant de l'action de relevé sur laquelle un formulaire est construit —
     *                    le panel refuse ensuite une écriture si le catalogue a changé depuis
     * @param surveyAge   âge lisible du relevé (« il y a 3 min »)
     * @param stale       relevé plus ancien que {@link #STALE_AFTER_MINUTES}
     * @param pending     nombre d'écritures quest.catalog.* encore en route vers le serveur
     * @param refreshHtml bouton « Actualiser » (formulaire d'action déjà sûr)
     */
    record Ctx(String csrf, String agentId, boolean canWrite, String basedOn, String surveyAge, boolean stale,
               int pending, String refreshHtml) {
    }

    @SuppressWarnings("unchecked")
    static Survey parse(Map<String, Object> d) {
        List<Catalog> catalogs = new ArrayList<>();
        for (Object o : list(d.get("catalogs"))) {
            Map<String, Object> c = map(o);
            List<Entry> entries = new ArrayList<>();
            for (Object eo : list(c.get("entries"))) {
                Map<String, Object> e = map(eo);
                entries.add(new Entry(str(e.get("quest")), integer(e.get("weight"), DEFAULT_WEIGHT),
                        Boolean.TRUE.equals(e.get("enabled")), Boolean.TRUE.equals(e.get("exists")),
                        MiniText.plain(str(e.get("title"))), Boolean.TRUE.equals(e.get("repeatable")),
                        str(e.get("cooldown")), integer(e.get("steps"), 0)));
            }
            catalogs.add(new Catalog(str(c.get("id")), str(c.get("displayName")), str(c.get("description")),
                    Boolean.TRUE.equals(c.get("enabled")), integer(c.get("avoidRecent"), 1), List.copyOf(entries),
                    strings(c.get("usedBy")), strings(c.get("warnings"))));
        }
        Map<String, String> issues = new LinkedHashMap<>();
        map(d.get("issues")).forEach((k, v) -> issues.put(k, str(v)));
        List<QuestRef> quests = new ArrayList<>();
        for (Object o : list(d.get("quests"))) {
            Map<String, Object> q = map(o);
            quests.add(new QuestRef(str(q.get("id")), MiniText.plain(str(q.get("title"))), str(q.get("category"))));
        }
        List<Template> templates = null;
        if (d.get("templates") instanceof List<?>) {
            templates = new ArrayList<>();
            for (Object o : list(d.get("templates"))) {
                Map<String, Object> t = map(o);
                templates.add(new Template(str(t.get("id")), MiniText.plain(str(t.get("displayName"))),
                        str(t.get("npcId")), Boolean.TRUE.equals(t.get("enabled")), strings(t.get("questCatalogs")),
                        Boolean.TRUE.equals(t.get("offersCatalog"))));
            }
            templates.sort(java.util.Comparator.comparing(t -> t.name().toLowerCase(Locale.ROOT)));
        }
        return new Survey(Boolean.TRUE.equals(d.get("available")), List.copyOf(catalogs), issues,
                List.copyOf(quests), strings(d.get("warnings")), templates == null ? null : List.copyOf(templates));
    }

    /**
     * Identifiant stable proposé pour un nouveau catalogue, à partir de son nom : « Quêtes de la
     * forêt » → {@code quetes_de_la_foret}. Jamais un identifiant existant : {@code _2}, {@code _3}…
     */
    static String idFromName(String name, Collection<String> existing) {
        String base = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.length() > 48) {
            base = base.substring(0, 48).replaceAll("_+$", "");
        }
        if (base.isEmpty()) {
            base = "catalogue";
        }
        Set<String> taken = Set.copyOf(existing);
        String id = base;
        for (int n = 2; taken.contains(id); n++) {
            id = base + "_" + n;
        }
        return id;
    }

    // =====================================================================================
    //  Accueil
    // =====================================================================================

    static String home(Ctx ctx, Survey s) {
        StringBuilder sb = new StringBuilder(header(ctx, true));
        sb.append(freshness(ctx));
        sb.append(globalProblems(s));
        if (s.catalogs().isEmpty()) {
            sb.append("<div class=\"empty qc-empty\">").append(Icons.icon("book"))
                    .append("<p><strong>Aucun catalogue pour l'instant.</strong></p>")
                    .append("<p>Un catalogue est une liste de quêtes. Des PNJ la proposent ensuite aux joueurs, "
                            + "une quête à la fois.</p>");
            if (ctx.canWrite()) {
                sb.append(Ui.primaryLink(url(ctx, "", "new"), "plus", "Créer mon premier catalogue"));
            }
            return sb.append("</div>").toString();
        }
        if (s.catalogs().size() >= SEARCH_THRESHOLD) {
            sb.append("<div class=\"npc-searchbar\"><input class=\"form-control npc-search\" type=\"search\" ")
                    .append("placeholder=\"Chercher un catalogue, une quête ou un PNJ…\" aria-label=\"Chercher\" ")
                    .append("data-filter-input=\"qc-catalogs\"></div>");
        }
        sb.append("<p class=\"count-note\" data-count-note data-noun=\"catalogue\">").append(s.catalogs().size())
                .append(s.catalogs().size() > 1 ? " catalogues" : " catalogue").append("</p>");
        sb.append("<ul class=\"qc-list\">");
        for (Catalog c : s.catalogs()) {
            sb.append(homeRow(ctx, s, c));
        }
        return sb.append("</ul>").toString();
    }

    private static String homeRow(Ctx ctx, Survey s, Catalog c) {
        List<String> users = c.usedBy().stream().map(s::templateName).toList();
        String search = (c.name() + " " + c.id() + " " + String.join(" ", users) + " "
                + c.entries().stream().map(Entry::label).collect(Collectors.joining(" "))).toLowerCase(Locale.ROOT);
        String href = url(ctx, c.id(), "");
        StringBuilder sb = new StringBuilder("<li class=\"qc-row\" data-filter-item=\"qc-catalogs\" data-filter-text=\"")
                .append(Http.esc(search)).append("\">");
        sb.append("<div class=\"qc-row-main\"><a class=\"qc-row-title\" href=\"").append(Http.esc(href)).append("\">")
                .append(Http.esc(c.name())).append("</a>");
        sb.append("<span class=\"qc-row-sub\">").append(Icons.icon("quests")).append(questCount(c.entries().size()))
                .append("</span>");
        sb.append("<span class=\"qc-row-sub\">").append(Icons.icon("npc")).append(users.isEmpty()
                ? "<span class=\"muted\">Aucun PNJ pour le moment</span>"
                : "Proposé par " + Http.esc(String.join(", ", users))).append("</span></div>");
        sb.append("<div class=\"qc-row-side\">").append(statusChip(s, c))
                .append("<a class=\"btn secondary sm\" href=\"").append(Http.esc(href)).append("\">Ouvrir</a></div>");
        return sb.append("</li>").toString();
    }

    /** Un seul état par catalogue, du plus grave au plus normal. */
    static String statusChip(Survey s, Catalog c) {
        if (!c.enabled()) {
            return chip("neutral", "En pause");
        }
        if (c.offeredCount() == 0) {
            return chip("err", "Ne propose rien");
        }
        if (c.hasMissing() || !silentUsers(s, c).isEmpty()) {
            return chip("warn", "À corriger");
        }
        if (c.usedBy().isEmpty()) {
            return chip("info", "Aucun PNJ");
        }
        return chip("ok", "Prêt");
    }

    /** Modèles qui ont ce catalogue mais dont le dialogue ne sait pas le proposer. */
    private static List<Template> silentUsers(Survey s, Catalog c) {
        if (s.templates() == null) {
            return List.of();
        }
        return c.usedBy().stream().map(s::template).filter(t -> t != null && !t.offersCatalog()).toList();
    }

    /**
     * Seuls les vrais problèmes remontent en tête de l'accueil : fichier illisible, PNJ pointant
     * vers une liste disparue. L'information normale (« pas encore de PNJ ») reste sur chaque ligne.
     */
    private static String globalProblems(Survey s) {
        List<String> items = new ArrayList<>();
        s.issues().forEach((file, msg) -> items.add("Le fichier <code>" + Http.esc(file)
                + "</code> n'a pas pu être lu par le serveur : " + Http.esc(msg)
                + ". Ce catalogue n'existe pas en jeu tant qu'il n'est pas corrigé."));
        if (s.templates() != null) {
            Set<String> ids = s.catalogs().stream().map(Catalog::id).collect(Collectors.toSet());
            for (Template t : s.templates()) {
                for (String ref : t.catalogs()) {
                    if (!ids.contains(ref)) {
                        items.add("Le PNJ « " + Http.esc(t.name()) + " » pointe vers une liste qui n'existe plus ("
                                + Ui.id(ref) + ") : il l'ignore. <a href=\"/npcs/encounters\">Corriger dans "
                                + "PNJ récurrents</a>.");
                    }
                }
            }
        } else if (!s.warnings().isEmpty()) {
            // Plugin antérieur à #414 : seuls ses messages bruts sont disponibles — repliés.
            return "<details class=\"qc-tech\"><summary>Contrôles techniques du serveur (" + s.warnings().size()
                    + ")</summary><ul>" + s.warnings().stream().map(w -> "<li>" + Http.esc(w) + "</li>")
                    .collect(Collectors.joining()) + "</ul></details>";
        }
        if (items.isEmpty()) {
            return "";
        }
        return Ui.banner("warn", "<div><strong>" + (items.size() > 1 ? items.size() + " points à corriger"
                : "Un point à corriger") + "</strong><ul class=\"qc-problems\">"
                + items.stream().map(i -> "<li>" + i + "</li>").collect(Collectors.joining()) + "</ul></div>");
    }

    // =====================================================================================
    //  Fiche
    // =====================================================================================

    static String detail(Ctx ctx, Survey s, Catalog c) {
        StringBuilder sb = new StringBuilder(backLink(ctx, "", "Tous les catalogues"));
        sb.append("<section class=\"qc-head\"><div class=\"qc-head-main\"><h1 class=\"qc-title\">")
                .append(Http.esc(c.name())).append("</h1>").append(statusChip(s, c)).append("</div>");
        sb.append("<p class=\"qc-lead\">Quand un joueur parle à un des PNJ ci-dessous, celui-ci lui propose "
                + "<strong>une</strong> quête de cette liste. Le joueur choisit d'accepter ou non.</p>");
        if (ctx.canWrite()) {
            sb.append("<div class=\"qc-actions\">")
                    .append(Ui.primaryLink(url(ctx, c.id(), "edit"), "edit", "Modifier"))
                    .append("<a class=\"btn secondary\" href=\"").append(Http.esc(url(ctx, c.id(), "edit") + "&add=1#qc-add"))
                    .append("\">").append(Icons.icon("plus")).append("Ajouter une quête</a></div>");
        }
        sb.append("</section>");
        sb.append(freshness(ctx));
        if (!c.description().isBlank()) {
            sb.append("<p class=\"muted qc-desc\">").append(Http.esc(c.description())).append("</p>");
        }
        sb.append(detailProblems(ctx, c));

        sb.append("<section class=\"qc-card\"><h2>").append(Icons.icon("quests")).append("Quêtes de la liste <span class=\"qc-count\">")
                .append(c.entries().size()).append("</span></h2>");
        if (c.entries().isEmpty()) {
            sb.append("<p class=\"muted\">Aucune quête pour l'instant.</p>");
        } else {
            sb.append("<ol class=\"qc-quests\">");
            for (Entry e : c.entries()) {
                sb.append("<li class=\"qc-quest").append(e.offered() ? "" : " is-off").append("\"><span class=\"qc-q-title\">")
                        .append(Http.esc(e.label())).append("</span><span class=\"qc-q-meta\">")
                        .append(Http.esc(entryMeta(e))).append("</span>");
                if (!e.exists()) {
                    sb.append(chip("err", "Quête introuvable"));
                } else if (!e.enabled()) {
                    sb.append(chip("neutral", "En pause"));
                }
                sb.append("</li>");
            }
            sb.append("</ol>");
        }
        sb.append("</section>");

        sb.append("<section class=\"qc-card\" id=\"qc-npcs\"><h2>").append(Icons.icon("npc"))
                .append("PNJ qui proposent ces quêtes</h2>");
        sb.append(usersBlock(ctx, s, c));
        sb.append("</section>");

        sb.append(advancedFacts(c));

        if (ctx.canWrite()) {
            sb.append("<section class=\"qc-zone\"><h2>Autres actions</h2><div class=\"qc-zone-row\">")
                    .append("<a class=\"btn secondary sm\" href=\"").append(Http.esc(url(ctx, c.id(), "copy")))
                    .append("\">").append(Icons.icon("copy")).append("Dupliquer</a>")
                    .append("<a class=\"btn secondary sm npc-danger\" href=\"").append(Http.esc(url(ctx, c.id(), "delete")))
                    .append("\">").append(Icons.icon("trash")).append("Supprimer…</a></div></section>");
        }
        return sb.toString();
    }

    /** Problèmes à résoudre sur cette fiche, chacun avec son chemin de résolution. */
    private static String detailProblems(Ctx ctx, Catalog c) {
        StringBuilder sb = new StringBuilder();
        if (!c.enabled()) {
            sb.append(Ui.banner("info", "Ce catalogue est <strong>en pause</strong> : aucun PNJ ne propose ses "
                    + "quêtes. Pour le réactiver : « Modifier », puis « Réglages avancés »."));
        } else if (c.offeredCount() == 0) {
            sb.append(Ui.banner("err", "Aucune quête ne peut être proposée : "
                    + (c.entries().isEmpty() ? "la liste est vide." : "elles sont toutes en pause ou introuvables.")
                    + (ctx.canWrite() ? " <a href=\"" + Http.esc(url(ctx, c.id(), "edit") + "&add=1#qc-add")
                    + "\">Ajouter une quête</a>" : "")));
        }
        List<Entry> missing = c.entries().stream().filter(e -> !e.exists()).toList();
        if (!missing.isEmpty()) {
            sb.append(Ui.banner("warn", (missing.size() > 1 ? missing.size() + " quêtes de la liste n'existent"
                    : "Une quête de la liste n'existe") + " plus sur le serveur (supprimée ou pas encore mise en jeu) : "
                    + "elle n'est jamais proposée. Retirez-la (« Modifier ») ou mettez-la en jeu depuis "
                    + "<a href=\"/quests\">Quêtes</a>."));
        }
        return sb.toString();
    }

    private static String usersBlock(Ctx ctx, Survey s, Catalog c) {
        StringBuilder sb = new StringBuilder();
        if (c.usedBy().isEmpty()) {
            sb.append("<p class=\"muted\">Aucun PNJ pour le moment : personne ne propose encore ces quêtes.</p>");
        } else {
            sb.append("<ul class=\"qc-npcs\">");
            for (String id : c.usedBy()) {
                Template t = s.template(id);
                sb.append("<li class=\"qc-npc\"><span class=\"qc-npc-name\">").append(Http.esc(s.templateName(id)))
                        .append("</span>");
                if (t != null && !t.enabled()) {
                    sb.append(chip("neutral", "N'apparaît plus"));
                }
                if (t != null && !t.offersCatalog()) {
                    sb.append(cannotOffer(t));
                } else if (t != null) {
                    sb.append(chip("ok", "Prêt"));
                }
                sb.append("</li>");
            }
            sb.append("</ul>");
        }
        if (!s.canAssign()) {
            sb.append("<p class=\"form-text\">Pour choisir les PNJ depuis cette page, le plugin du serveur doit "
                    + "être mis à jour. En attendant : <a href=\"/npcs/encounters\">PNJ récurrents</a>, champ "
                    + "« Catalogues de quêtes » du modèle.</p>");
        } else if (ctx.canWrite()) {
            sb.append("<a class=\"btn secondary sm\" href=\"").append(Http.esc(url(ctx, c.id(), "edit") + "#qc-npcs"))
                    .append("\">").append(Icons.icon("npc")).append("Choisir des PNJ</a>");
        }
        return sb.toString();
    }

    /** L'association seule ne suffit pas : on le dit, avec le chemin pour réparer. */
    private static String cannotOffer(Template t) {
        return "<span class=\"qc-npc-warn\">" + Icons.icon("warning") + "Ce PNJ ne sait pas encore proposer les "
                + "quêtes de cette liste. <a href=\"/dialogues/edit/" + Http.esc(t.npcId()) + "\">Ouvrir son "
                + "dialogue</a> et ajouter une réponse avec l'action « Proposer une quête du catalogue ».</span>";
    }

    private static String advancedFacts(Catalog c) {
        StringBuilder sb = new StringBuilder("<details class=\"qc-card qc-advanced\"><summary>Réglages avancés</summary>");
        sb.append("<dl class=\"qc-facts\"><dt>Identifiant</dt><dd>").append(Ui.id(c.id()))
                .append(" <span class=\"form-text\">fichier <code>quest-catalogs/").append(Http.esc(c.id()))
                .append(".yml</code></span></dd>");
        sb.append("<dt>Anti-répétition</dt><dd>").append(Http.esc(avoidText(c.avoidRecent()))).append("</dd>");
        sb.append("<dt>Choix de la quête</dt><dd>Chaque joueur reçoit sa propre proposition, qui ne change pas "
                + "jusqu'à sa prochaine quête terminée. Seules les quêtes qu'il peut réellement faire sont "
                + "proposées.</dd></dl>");
        long total = c.entries().stream().filter(Entry::offered).mapToLong(Entry::weight).sum();
        if (!c.entries().isEmpty()) {
            sb.append("<p class=\"qc-adv-h\">Fréquence de proposition</p><p class=\"form-text\">Plus le nombre est "
                    + "grand, plus la quête sort souvent : 20 sort deux fois plus que 10. La part indiquée vaut "
                    + "quand toutes les quêtes sont disponibles ; les quêtes déjà faites, en attente ou réservées "
                    + "sont écartées, donc la part réelle change d'un joueur à l'autre.</p><ul class=\"qc-weights\">");
            for (Entry e : c.entries()) {
                sb.append("<li><span>").append(Http.esc(e.label())).append(" ").append(Ui.id(e.quest()))
                        .append("</span><span class=\"qc-w\">").append(e.weight());
                if (e.offered() && total > 0) {
                    sb.append(" · environ ").append(Math.round(100.0 * e.weight() / total)).append(" %");
                }
                sb.append("</span></li>");
            }
            sb.append("</ul>");
        }
        if (!c.warnings().isEmpty()) {
            sb.append("<p class=\"qc-adv-h\">Contrôles du serveur</p><ul class=\"qc-tech-list\">");
            c.warnings().forEach(w -> sb.append("<li>").append(Http.esc(w)).append("</li>"));
            sb.append("</ul>");
        }
        return sb.append("</details>").toString();
    }

    static String avoidText(int n) {
        return n == 0 ? "Aucune : une quête terminée peut être reproposée aussitôt."
                : "Ne repropose pas " + (n == 1 ? "la dernière quête terminée" : "les " + n + " dernières quêtes terminées")
                + " par le joueur, tant qu'une autre est possible.";
    }

    private static String entryMeta(Entry e) {
        if (!e.exists()) {
            return "n'existe plus sur le serveur";
        }
        List<String> facts = new ArrayList<>();
        facts.add(e.steps() + (e.steps() > 1 ? " étapes" : " étape"));
        if (!e.repeatable()) {
            facts.add("une seule fois par joueur");
        } else if (!e.cooldown().isBlank()) {
            facts.add("à refaire après " + e.cooldown());
        } else {
            facts.add("à refaire à volonté");
        }
        return String.join(" · ", facts);
    }

    // =====================================================================================
    //  Création, modification : un même formulaire
    // =====================================================================================

    static String create(Ctx ctx, Survey s) {
        StringBuilder sb = new StringBuilder(backLink(ctx, "", "Tous les catalogues"));
        sb.append("<h1 class=\"qc-title\">Créer un catalogue</h1>");
        sb.append("<p class=\"qc-lead\">Donne-lui un nom, coche les quêtes, choisis les PNJ qui les proposeront. "
                + "Tu pourras tout changer ensuite.</p>");
        sb.append(freshness(ctx));
        return sb.append(form(ctx, s, null, true)).toString();
    }

    static String edit(Ctx ctx, Survey s, Catalog c, boolean openAdd) {
        StringBuilder sb = new StringBuilder(backLink(ctx, c.id(), "Retour à la fiche"));
        sb.append("<h1 class=\"qc-title\">Modifier « ").append(Http.esc(c.name())).append(" »</h1>");
        sb.append(freshness(ctx));
        return sb.append(form(ctx, s, c, openAdd)).toString();
    }

    private static String form(Ctx ctx, Survey s, Catalog c, boolean openAdd) {
        boolean create = c == null;
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"/agents/action\" class=\"qc-form\" ")
                .append("data-submit-once data-dirty-form data-qc-form>");
        sb.append(hidden("_csrf", ctx.csrf())).append(hidden("agent", ctx.agentId()))
                .append(hidden("type", create ? "quest.catalog.create" : "quest.catalog.update"))
                .append(hidden("return", PAGE)).append(hidden("focus", create ? "@id" : c.id()))
                .append(hidden("based_on", ctx.basedOn()));
        if (!create) {
            sb.append(hidden("id", c.id()));
        }

        sb.append("<section class=\"qc-card\"><h2><span class=\"qc-step\">1</span>Nom du catalogue</h2>")
                .append("<label class=\"visually-hidden\" for=\"qc-name\">Nom du catalogue</label>")
                .append("<input class=\"form-control\" id=\"qc-name\" name=\"display_name\" maxlength=\"64\" required ")
                .append("placeholder=\"Par exemple : Quêtes de la forêt\" value=\"")
                .append(Http.esc(create ? "" : c.name())).append("\" data-qc-name>")
                .append("<p class=\"form-text\">Visible seulement ici, pas par les joueurs.</p></section>");

        List<Entry> entries = create ? List.of() : c.entries();
        sb.append("<section class=\"qc-card\"><h2><span class=\"qc-step\">2</span>Quêtes de la liste</h2>");
        if (!entries.isEmpty()) {
            sb.append("<p class=\"form-text\">L'ordre sert seulement à t'y retrouver.</p>");
        }
        sb.append("<ol class=\"qc-items\" data-qc-items>");
        int n = 0;
        for (Entry e : entries) {
            sb.append(itemRow(n, e));
            n++;
        }
        sb.append("</ol>");
        if (entries.isEmpty() && !create) {
            sb.append("<p class=\"muted\">Aucune quête pour l'instant.</p>");
        }
        sb.append(addPanel(s, entries, n, create || openAdd));
        sb.append("</section>");

        sb.append(npcSection(s, c));
        sb.append(advancedForm(c));

        sb.append("<div class=\"qc-summary\" data-qc-summary aria-live=\"polite\"></div>");
        sb.append("<div class=\"dlg-savebar qc-savebar\"><span class=\"dlg-dirty\" data-dirty-note hidden>")
                .append(Icons.icon("warning")).append("Modifications non enregistrées</span>")
                .append("<span class=\"qc-live-note\">").append(Icons.icon("info"))
                .append("Actif en jeu dès l'enregistrement</span>")
                .append("<a class=\"btn secondary\" href=\"").append(Http.esc(create ? url(ctx, "", "") : url(ctx, c.id(), "")))
                .append("\">Annuler</a><button class=\"btn\" type=\"submit\" data-busy-label=\"Envoi…\">")
                .append(Icons.icon("save")).append(create ? "Créer le catalogue" : "Enregistrer").append("</button></div>");
        return sb.append("</form>").toString();
    }

    /** Une quête déjà dans la liste : monter, descendre, retirer ; fréquence et pause en avancé. */
    private static String itemRow(int n, Entry e) {
        String p = "entry_" + n + "_";
        StringBuilder sb = new StringBuilder("<li class=\"qc-item\" data-qc-item>");
        sb.append(hidden(p + "quest", e.quest())).append("<input type=\"hidden\" name=\"").append(p)
                .append("order\" value=\"").append((n + 1) * 10).append("\" data-qc-order>");
        sb.append("<div class=\"qc-item-main\"><span class=\"qc-q-title\" data-qc-title>").append(Http.esc(e.label()))
                .append("</span><span class=\"qc-q-meta\">").append(Http.esc(entryMeta(e))).append("</span>");
        if (!e.exists()) {
            sb.append(chip("err", "Quête introuvable"));
        }
        sb.append("<div class=\"qc-adv qc-item-adv\"><label>Fréquence <input class=\"form-control form-control-sm\" ")
                .append("type=\"number\" min=\"1\" max=\"1000\" name=\"").append(p).append("weight\" value=\"")
                .append(e.weight()).append("\"></label><label class=\"inline\"><input type=\"checkbox\" name=\"")
                .append(p).append("enabled\" value=\"true\"").append(e.enabled() ? " checked" : "")
                .append("> proposée (décoché = en pause)</label>").append(Ui.id(e.quest())).append("</div></div>");
        String t = Http.esc(e.label());
        sb.append("<div class=\"qc-item-btns\">")
                .append("<button class=\"btn secondary sm\" type=\"button\" data-qc-move=\"up\" aria-label=\"Monter « ")
                .append(t).append(" »\">").append(Icons.icon("up")).append("</button>")
                .append("<button class=\"btn secondary sm\" type=\"button\" data-qc-move=\"down\" aria-label=\"Descendre « ")
                .append(t).append(" »\">").append(Icons.icon("down")).append("</button>")
                .append("<label class=\"btn secondary sm qc-rm\"><input type=\"checkbox\" name=\"").append(p)
                .append("remove\" value=\"true\" data-qc-remove><span class=\"qc-rm-off\">Retirer</span>")
                .append("<span class=\"qc-rm-on\">Garder</span></label></div>");
        return sb.append("</li>").toString();
    }

    /**
     * « Ajouter une quête » : recherche par titre, une case par quête pas encore dans la liste.
     * Fonctionne sans JavaScript (cases cochées = ajoutées à l'enregistrement).
     */
    private static String addPanel(Survey s, List<Entry> entries, int first, boolean open) {
        Set<String> inList = entries.stream().map(Entry::quest).collect(Collectors.toSet());
        List<QuestRef> candidates = s.quests().stream().filter(q -> !inList.contains(q.id()))
                .sorted(java.util.Comparator.comparing(q -> q.label().toLowerCase(Locale.ROOT))).toList();
        Map<String, Integer> sameTitle = new HashMap<>();
        candidates.forEach(q -> sameTitle.merge(q.label(), 1, Integer::sum));
        StringBuilder sb = new StringBuilder("<details class=\"qc-add\" id=\"qc-add\"").append(open ? " open" : "")
                .append("><summary class=\"btn secondary\">").append(Icons.icon("plus")).append("Ajouter une quête</summary>");
        if (candidates.isEmpty()) {
            return sb.append("<p class=\"muted\">Toutes les quêtes du serveur sont déjà dans cette liste.</p></details>")
                    .toString();
        }
        sb.append("<input class=\"form-control qc-add-search\" type=\"search\" placeholder=\"Chercher une quête par son nom…\" ")
                .append("aria-label=\"Chercher une quête\" data-filter-input=\"qc-cands\">");
        sb.append("<p class=\"form-text\" data-qc-addcount>Coche les quêtes à ajouter.</p><div class=\"qc-cands\" data-qc-cands>");
        int n = first;
        for (QuestRef q : candidates) {
            String p = "entry_" + n + "_";
            String fid = "qc-cand-" + n;
            sb.append("<div class=\"qc-cand\" data-filter-item=\"qc-cands\" data-filter-text=\"")
                    .append(Http.esc((q.label() + " " + q.id()).toLowerCase(Locale.ROOT))).append("\">")
                    .append("<input type=\"checkbox\" id=\"").append(fid).append("\" name=\"").append(p)
                    .append("quest\" value=\"").append(Http.esc(q.id())).append("\" data-qc-cand>")
                    .append(hidden(p + "enabled", "true")).append(hidden(p + "order", String.valueOf(5000 + n - first)))
                    .append("<label for=\"").append(fid).append("\"><span class=\"qc-q-title\">").append(Http.esc(q.label()))
                    .append("</span>");
            if (sameTitle.getOrDefault(q.label(), 0) > 1) {
                sb.append(" ").append(Ui.id(q.id()));
            } else {
                sb.append("<span class=\"qc-adv\"> ").append(Ui.id(q.id())).append("</span>");
            }
            sb.append("</label></div>");
            n++;
        }
        return sb.append("</div></details>").toString();
    }

    private static String npcSection(Survey s, Catalog c) {
        StringBuilder sb = new StringBuilder("<section class=\"qc-card\" id=\"qc-npcs\"><h2><span class=\"qc-step\">3")
                .append("</span>PNJ qui proposent ces quêtes</h2>");
        if (!s.canAssign()) {
            return sb.append("<p class=\"form-text\">Le plugin du serveur doit être mis à jour pour choisir les PNJ "
                    + "ici. En attendant : <a href=\"/npcs/encounters\">PNJ récurrents</a>, champ « Catalogues de "
                    + "quêtes » du modèle.</p></section>").toString();
        }
        if (s.templates().isEmpty()) {
            return sb.append("<p class=\"muted\">Aucun PNJ récurrent n'existe encore. Crée-en un dans "
                    + "<a href=\"/npcs/encounters\">PNJ récurrents</a>, puis reviens ici.</p></section>").toString();
        }
        sb.append(hidden("assign_templates", "true"));
        sb.append("<p class=\"form-text\">Ce sont des « PNJ récurrents » : chaque fois qu'un PNJ de ce type apparaît "
                + "dans le monde, il propose ces quêtes. Facultatif.</p><div class=\"qc-picks\">");
        for (Template t : s.templates()) {
            boolean on = c != null && t.catalogs().contains(c.id());
            String fid = "qc-tpl-" + t.id().replaceAll("[^a-z0-9_-]", "-");
            sb.append("<div class=\"qc-pick\"><input type=\"checkbox\" id=\"").append(Http.esc(fid)).append("\" name=\"tpl_")
                    .append(Http.esc(t.id())).append("\" value=\"true\"").append(on ? " checked" : "")
                    .append(" data-qc-tpl data-name=\"").append(Http.esc(t.name())).append("\"><label for=\"")
                    .append(Http.esc(fid)).append("\"><span class=\"qc-npc-name\">").append(Http.esc(t.name()))
                    .append("</span>");
            if (!t.enabled()) {
                sb.append(chip("neutral", "N'apparaît plus"));
            }
            int others = (int) t.catalogs().stream().filter(x -> c == null || !x.equals(c.id())).count();
            if (others > 0) {
                sb.append("<span class=\"qc-q-meta\">propose déjà ").append(others)
                        .append(others > 1 ? " autres listes" : " autre liste").append("</span>");
            }
            sb.append("<span class=\"qc-adv\">").append(Ui.id(t.id())).append("</span></label>");
            if (!t.offersCatalog()) {
                sb.append(cannotOffer(t));
            }
            sb.append("</div>");
        }
        return sb.append("</div></section>").toString();
    }

    private static String advancedForm(Catalog c) {
        boolean create = c == null;
        int avoid = create ? 1 : c.avoidRecent();
        StringBuilder sb = new StringBuilder("<details class=\"qc-card qc-advanced\"><summary>Réglages avancés</summary>");
        sb.append("<label class=\"inline qc-adv-switch\"><input type=\"checkbox\" class=\"qc-adv-toggle\"> Afficher la "
                + "fréquence de chaque quête et les identifiants</label>");
        sb.append("<div class=\"qc-adv-grid\">");
        if (create) {
            sb.append("<div><label for=\"qc-id\">Identifiant</label><input class=\"form-control\" id=\"qc-id\" name=\"id\" ")
                    .append("maxlength=\"64\" pattern=\"[a-z0-9._\\-]{1,64}\" placeholder=\"calculé à partir du nom\">")
                    .append("<p class=\"form-text\">Laisse vide : il est calculé à partir du nom. Il ne change plus ensuite.</p></div>");
        } else {
            sb.append("<div><span class=\"qc-lbl\">Identifiant</span><p>").append(Ui.id(c.id()))
                    .append("</p><p class=\"form-text\">Ne change pas : les PNJ s'en servent pour retrouver la liste.</p></div>");
        }
        sb.append("<div><label for=\"qc-avoid\">Anti-répétition</label><select class=\"form-select\" id=\"qc-avoid\" ")
                .append("name=\"avoid_recent\">");
        for (int i = 0; i <= 10; i++) {
            sb.append("<option value=\"").append(i).append("\"").append(i == avoid ? " selected" : "").append(">")
                    .append(i == 0 ? "Aucune" : "Éviter " + (i == 1 ? "la dernière quête terminée" : "les " + i + " dernières"))
                    .append("</option>");
        }
        sb.append("</select><p class=\"form-text\">Une quête que le joueur vient de finir n'est pas reproposée tout de "
                + "suite, si une autre est possible.</p></div>");
        sb.append("<div><label for=\"qc-desc\">Note pour les administrateurs</label><input class=\"form-control\" ")
                .append("id=\"qc-desc\" name=\"description\" maxlength=\"256\" value=\"")
                .append(Http.esc(create ? "" : c.description())).append("\"></div>");
        boolean enabled = create || c.enabled();
        sb.append("<div><label class=\"inline\"><input type=\"checkbox\" name=\"enabled\" value=\"true\"")
                .append(enabled ? " checked" : "").append("> Catalogue actif</label><p class=\"form-text\">Décoché : en "
                        + "pause, aucun PNJ ne propose ses quêtes.</p></div>");
        return sb.append("</div></details>").toString();
    }

    // =====================================================================================
    //  Dupliquer, supprimer : écrans courts
    // =====================================================================================

    static String duplicate(Ctx ctx, Catalog c) {
        StringBuilder sb = new StringBuilder(backLink(ctx, c.id(), "Retour à la fiche"));
        sb.append("<h1 class=\"qc-title\">Dupliquer « ").append(Http.esc(c.name())).append(" »</h1>");
        sb.append("<form method=\"post\" action=\"/agents/action\" class=\"qc-card qc-short\" data-submit-once>");
        sb.append(hidden("_csrf", ctx.csrf())).append(hidden("agent", ctx.agentId()))
                .append(hidden("type", "quest.catalog.create")).append(hidden("return", PAGE))
                .append(hidden("focus", "@id")).append(hidden("avoid_recent", String.valueOf(c.avoidRecent())))
                .append(c.enabled() ? hidden("enabled", "true") : "")
                .append(c.description().isBlank() ? "" : hidden("description", c.description()));
        int n = 0;
        for (Entry e : c.entries()) {
            String p = "entry_" + n + "_";
            sb.append(hidden(p + "quest", e.quest())).append(hidden(p + "weight", String.valueOf(e.weight())))
                    .append(hidden(p + "order", String.valueOf((n + 1) * 10)))
                    .append(e.enabled() ? hidden(p + "enabled", "true") : "");
            n++;
        }
        sb.append("<p>La copie reprend les ").append(questCount(c.entries().size()))
                .append(" et les réglages. <strong>Aucun PNJ ne la propose</strong> au départ : tu les choisiras "
                        + "ensuite.</p>");
        sb.append("<label for=\"qc-copy-name\">Nom de la copie</label><input class=\"form-control\" id=\"qc-copy-name\" ")
                .append("name=\"display_name\" maxlength=\"64\" required value=\"")
                .append(Http.esc(copyName(c.name()))).append("\">");
        sb.append("<details class=\"qc-advanced-inline\"><summary>Identifiant (avancé)</summary><input class=\"form-control\" ")
                .append("name=\"id\" maxlength=\"64\" pattern=\"[a-z0-9._\\-]{1,64}\" placeholder=\"calculé à partir du nom\"></details>");
        sb.append("<div class=\"btnrow\"><button class=\"btn\" type=\"submit\" data-busy-label=\"Envoi…\">")
                .append(Icons.icon("copy")).append("Créer la copie</button><a class=\"btn secondary\" href=\"")
                .append(Http.esc(url(ctx, c.id(), ""))).append("\">Annuler</a></div></form>");
        return sb.toString();
    }

    static String copyName(String name) {
        String n = name + " (copie)";
        return n.length() > 64 ? n.substring(0, 64) : n;
    }

    static String delete(Ctx ctx, Survey s, Catalog c) {
        StringBuilder sb = new StringBuilder(backLink(ctx, c.id(), "Retour à la fiche"));
        sb.append("<h1 class=\"qc-title\">Supprimer « ").append(Http.esc(c.name())).append(" » ?</h1>");
        sb.append("<form method=\"post\" action=\"/agents/action\" class=\"qc-card qc-short qc-danger\" data-submit-once>");
        sb.append(hidden("_csrf", ctx.csrf())).append(hidden("agent", ctx.agentId()))
                .append(hidden("type", "quest.catalog.delete")).append(hidden("return", PAGE))
                .append(hidden("id", c.id())).append(hidden("based_on", ctx.basedOn()));
        sb.append("<p><strong>Ce qui va se passer :</strong></p><ul class=\"qc-conseq\">")
                .append("<li>La liste et ses ").append(questCount(c.entries().size()))
                .append(" ne seront plus proposées aux joueurs.</li>")
                .append("<li>Les quêtes elles-mêmes ne sont <strong>pas</strong> supprimées ; un joueur qui en a "
                        + "déjà accepté une la garde.</li>")
                .append("<li>Une sauvegarde du fichier est faite sur le serveur (<code>quest-catalog-backups/</code>).</li>");
        List<String> users = c.usedBy().stream().map(s::templateName).toList();
        if (!users.isEmpty()) {
            sb.append("<li>Elle sera retirée de ces PNJ : <strong>").append(Http.esc(String.join(", ", users)))
                    .append("</strong>.</li>");
        }
        sb.append("</ul>");
        if (!users.isEmpty()) {
            sb.append("<label class=\"inline confirm\"><input type=\"checkbox\" name=\"detach\" value=\"true\" required> "
                    + "Retirer aussi la liste de ces PNJ</label>");
        }
        sb.append("<label class=\"inline confirm\"><input type=\"checkbox\" name=\"confirm\" value=\"true\" required> ")
                .append("Je confirme la suppression de « ").append(Http.esc(c.name())).append(" »</label>");
        sb.append("<div class=\"btnrow\"><button class=\"btn danger\" type=\"submit\" data-busy-label=\"Envoi…\">")
                .append(Icons.icon("trash")).append("Supprimer définitivement</button><a class=\"btn secondary\" href=\"")
                .append(Http.esc(url(ctx, c.id(), ""))).append("\">Annuler</a></div></form>");
        return sb.toString();
    }

    /** Fiche demandée mais absente du relevé (juste créée, ou supprimée entre-temps). */
    static String notFound(Ctx ctx, String id) {
        return backLink(ctx, "", "Tous les catalogues") + Ui.banner("info", "Le catalogue " + Ui.id(id)
                + " n'apparaît pas dans le dernier relevé du serveur. "
                + (ctx.pending() > 0 ? "Un changement est en cours d'envoi : la page se mettra à jour toute seule."
                : "S'il vient d'être supprimé, c'est normal ; sinon, actualise la page."));
    }

    // =====================================================================================
    //  Éléments communs
    // =====================================================================================

    private static String header(Ctx ctx, boolean withCreate) {
        return Ui.pageHeader("book", "Catalogues de quêtes",
                "Regroupe plusieurs quêtes et choisis quels PNJ peuvent les proposer aux joueurs.",
                (withCreate && ctx.canWrite() ? Ui.primaryLink(url(ctx, "", "new"), "plus", "Créer un catalogue") : "")
                        + "<a class=\"btn secondary\" href=\"/npcs/encounters\">" + Icons.icon("npc") + "PNJ récurrents</a>");
    }

    /**
     * Fraîcheur du relevé : ces données viennent du serveur à un instant donné, jamais présentées
     * comme l'état instantané. Un envoi en cours est dit tel quel.
     */
    static String freshness(Ctx ctx) {
        StringBuilder sb = new StringBuilder("<div class=\"qc-fresh").append(ctx.stale() ? " is-stale" : "").append("\">");
        sb.append("<span>").append(Icons.icon(ctx.stale() ? "warning" : "clock"))
                .append(ctx.stale() ? "Ces informations datent de " : "Données du serveur relevées ")
                .append(Http.esc(ctx.surveyAge())).append(ctx.stale() ? " : actualise avant de modifier." : "")
                .append("</span>").append(ctx.refreshHtml()).append("</div>");
        if (ctx.pending() > 0) {
            sb.append(Ui.banner("info", "Un changement est en cours d'envoi au serveur. La page se mettra à jour "
                    + "toute seule quand il sera appliqué."));
        }
        return sb.toString();
    }

    private static String backLink(Ctx ctx, String id, String label) {
        return "<p class=\"pl-back\"><a href=\"" + Http.esc(url(ctx, id, "")) + "\">" + Icons.icon("back")
                + Http.esc(label) + "</a></p>";
    }

    /** {@code /quests/catalogs?agent=…[&focus=id][&mode=…]} ; mode {@code new} = création. */
    static String url(Ctx ctx, String id, String mode) {
        StringBuilder sb = new StringBuilder(PAGE).append("?agent=").append(enc(ctx.agentId()));
        if ("new".equals(mode)) {
            return sb.append("&new=1").toString();
        }
        if (id != null && !id.isEmpty()) {
            sb.append("&focus=").append(enc(id));
        }
        if (mode != null && !mode.isEmpty()) {
            sb.append("&mode=").append(enc(mode));
        }
        return sb.toString();
    }

    private static String questCount(int n) {
        return n + (n > 1 ? " quêtes" : " quête");
    }

    static String chip(String tone, String label) {
        return "<span class=\"dlg-chip tone-" + tone + "\">" + Http.esc(label) + "</span>";
    }

    private static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + Http.esc(name) + "\" value=\"" + Http.esc(value) + "\">";
    }

    private static String enc(String v) {
        return java.net.URLEncoder.encode(v == null ? "" : v, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static List<Object> list(Object o) {
        return o instanceof List<?> l ? new ArrayList<>(l) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Object o) {
        return o == null || "null".equals(String.valueOf(o)) ? "" : String.valueOf(o);
    }

    private static List<String> strings(Object o) {
        return list(o).stream().map(QuestCatalogPages::str).filter(v -> !v.isBlank()).toList();
    }

    private static int integer(Object o, int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(str(o).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
