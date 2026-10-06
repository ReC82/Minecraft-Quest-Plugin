package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import com.lodygames.rpgquest.panel.agent.AgentIdentity;
import com.lodygames.rpgquest.panel.agent.AgentRegistry;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.authz.Permission;
import com.lodygames.rpgquest.panel.authz.PermissionService;
import com.lodygames.rpgquest.panel.content.QuestDraft;
import com.lodygames.rpgquest.panel.content.QuestYaml;
import com.lodygames.rpgquest.panel.content.SourceCatalog;
import com.lodygames.rpgquest.panel.content.StoryDraft;
import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.json.Json;
import com.lodygames.rpgquest.panel.security.Session;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Rendu des pages métier du Control Panel (Joueurs / Quêtes / Stories) — outillage de
 * développement/test du plugin RPGQuest depuis navigateur et mobile.
 *
 * <p>Chaque page réutilise le <strong>canal agent</strong> (issue #51) : un formulaire crée une
 * action whitelistée ({@link AgentActionCatalog}) ; le résultat structuré revient de façon
 * asynchrone et est réaffiché ici (dernière action réussie de chaque type via
 * {@link AgentStore#latestActionOfType}), le tableau des actions se rafraîchissant tout seul via
 * {@code /assets/panel.js}. Toujours : <em>nom lisible</em> d'abord, id technique en second.</p>
 */
public final class AgentPages {

    private final AgentStore store;
    private final AgentRegistry registry;
    private final String defaultAgentId;
    private final PermissionService perms;
    private final SourceCatalog sourceCatalog;

    public AgentPages(AgentStore store, AgentRegistry registry, String defaultAgentId, PermissionService perms) {
        this(store, registry, defaultAgentId, perms, new SourceCatalog(null));
    }

    public AgentPages(AgentStore store, AgentRegistry registry, String defaultAgentId, PermissionService perms,
                      SourceCatalog sourceCatalog) {
        this.store = store;
        this.registry = registry;
        this.defaultAgentId = defaultAgentId;
        this.perms = perms;
        this.sourceCatalog = sourceCatalog;
    }

    /**
     * État d'une entrée du catalogue fusionné source + runtime (issue #144). Voir aussi le libellé
     * humain rendu dans l'en-tête d'accordion.
     */
    enum CatalogState {
        /** Présente à la fois dans la source éditable et dans le dernier relevé du serveur. */
        SYNCED,
        /** Enregistrée dans la source mais pas encore chargée par le serveur DEV. */
        SOURCE_ONLY,
        /** Chargée par le serveur mais absente de la source éditable du Control Panel. */
        RUNTIME_ONLY
    }

    /** Une entrée du catalogue fusionné : la donnée déjà mise en forme « comme un relevé runtime ». */
    private record MergedRow(Map<String, Object> data, CatalogState state) {
    }

    // ================================================================================
    //  Catalogue fusionné source + runtime (issue #144)
    // ================================================================================

    /**
     * Fusionne le dernier relevé {@code quest.list} du serveur avec les fichiers
     * {@code quests/*.yml} de la source éditable. Clé de fusion : l'id « nu ». Ordre : d'abord les
     * quêtes du relevé runtime (dans leur ordre), puis les quêtes « source uniquement » (triées par
     * slug). Chaque ligne porte un {@link CatalogState} explicite : jamais de fusion silencieuse qui
     * ferait croire qu'un contenu source est déjà actif en jeu.
     */
    private List<MergedRow> mergeQuestRows(List<Object> runtimeQuests) {
        Map<String, Map<String, Object>> runtimeById = new LinkedHashMap<>();
        for (Object o : runtimeQuests) {
            Map<String, Object> m = asMap(o);
            String key = QuestYaml.plainId(str(m.get("id")));
            if (!key.isEmpty()) {
                runtimeById.putIfAbsent(key, m);
            }
        }
        Map<String, SourceCatalog.QuestSource> sourceById = new LinkedHashMap<>();
        for (SourceCatalog.QuestSource qs : sourceCatalog.quests()) {
            if (!qs.plainId().isEmpty()) {
                sourceById.putIfAbsent(qs.plainId(), qs);
            }
        }
        // Sans espace de travail source configuré, aucune comparaison n'est possible : on n'affiche
        // aucun badge d'origine plutôt que de qualifier à tort toutes les quêtes de « hors source ».
        boolean srcKnown = sourceCatalog.available();
        List<MergedRow> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : runtimeById.entrySet()) {
            CatalogState st = !srcKnown ? CatalogState.SYNCED
                    : sourceById.containsKey(e.getKey()) ? CatalogState.SYNCED : CatalogState.RUNTIME_ONLY;
            out.add(new MergedRow(e.getValue(), st));
        }
        for (Map.Entry<String, SourceCatalog.QuestSource> e : sourceById.entrySet()) {
            if (!runtimeById.containsKey(e.getKey())) {
                out.add(new MergedRow(sourceQuestRow(e.getValue()), CatalogState.SOURCE_ONLY));
            }
        }
        return out;
    }

    /** Idem {@link #mergeQuestRows} pour {@code story.list} + {@code stories/*.yml}. */
    private List<MergedRow> mergeStoryRows(List<Object> runtimeStories) {
        Map<String, Map<String, Object>> runtimeById = new LinkedHashMap<>();
        for (Object o : runtimeStories) {
            Map<String, Object> m = asMap(o);
            String key = str(m.get("id")).trim().toLowerCase(Locale.ROOT);
            key = key.startsWith("rpgquest:") ? key.substring("rpgquest:".length()) : key;
            if (!key.isEmpty()) {
                runtimeById.putIfAbsent(key, m);
            }
        }
        Map<String, SourceCatalog.StorySource> sourceById = new LinkedHashMap<>();
        for (SourceCatalog.StorySource ss : sourceCatalog.stories()) {
            if (!ss.plainId().isEmpty()) {
                sourceById.putIfAbsent(ss.plainId(), ss);
            }
        }
        boolean srcKnown = sourceCatalog.available();
        List<MergedRow> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : runtimeById.entrySet()) {
            CatalogState st = !srcKnown ? CatalogState.SYNCED
                    : sourceById.containsKey(e.getKey()) ? CatalogState.SYNCED : CatalogState.RUNTIME_ONLY;
            out.add(new MergedRow(e.getValue(), st));
        }
        for (Map.Entry<String, SourceCatalog.StorySource> e : sourceById.entrySet()) {
            if (!runtimeById.containsKey(e.getKey())) {
                out.add(new MergedRow(sourceStoryRow(e.getValue()), CatalogState.SOURCE_ONLY));
            }
        }
        return out;
    }

    /** Projette une quête relue de la source dans la forme d'une ligne {@code quest.list} (structurée). */
    private static Map<String, Object> sourceQuestRow(SourceCatalog.QuestSource qs) {
        QuestDraft d = qs.draft();
        Map<String, Object> m = new LinkedHashMap<>();
        String plain = qs.plainId();
        m.put("id", "rpgquest:" + plain);
        m.put("title", d.title == null || d.title.isBlank() ? plain : d.title);
        m.put("category", d.category == null ? "" : d.category);
        m.put("repeatable", d.repeatable);
        m.put("giverId", d.giver == null ? "" : d.giver.trim());
        m.put("giverName", "");
        List<Object> prereq = new ArrayList<>();
        for (String p : d.prerequisites) {
            if (p != null && !p.isBlank()) {
                prereq.add("rpgquest:" + QuestYaml.plainId(p));
            }
        }
        m.put("prerequisites", prereq);
        List<Object> steps = new ArrayList<>();
        for (QuestDraft.Step s : d.steps) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", s.id == null ? "" : s.id);
            List<Object> od = new ArrayList<>();
            for (Map<String, String> obj : s.objectives) {
                od.add(objectiveDetail(obj));
            }
            sm.put("objectiveDetails", od);
            steps.add(sm);
        }
        m.put("steps", steps);
        List<Object> rd = new ArrayList<>();
        for (Map<String, String> r : d.rewards) {
            rd.add(rewardDetail(r));
        }
        m.put("rewardDetails", rd);
        m.put("rewards", List.of());
        return m;
    }

    /** Projette une story relue de la source dans la forme d'une ligne {@code story.list}. */
    private static Map<String, Object> sourceStoryRow(SourceCatalog.StorySource ss) {
        StoryDraft d = ss.draft();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ss.plainId());
        m.put("title", d.name == null || d.name.isBlank() ? ss.plainId() : d.name);
        List<Object> steps = new ArrayList<>();
        for (String q : d.questIds) {
            if (q != null && !q.isBlank()) {
                steps.add("rpgquest:" + QuestYaml.plainId(q));
            }
        }
        m.put("stepQuestIds", steps);
        return m;
    }

    private static Map<String, Object> objectiveDetail(Map<String, String> obj) {
        Map<String, Object> o = new LinkedHashMap<>();
        String kind = obj.getOrDefault("kind", "").trim().toUpperCase(Locale.ROOT);
        o.put("kind", kind);
        o.put("target", firstNonBlank(obj.get("entity"), obj.get("material"), obj.get("npc"), obj.get("world")));
        o.put("amount", intOr(obj.get("amount"), 0));
        o.put("raw", "");
        return o;
    }

    private static Map<String, Object> rewardDetail(Map<String, String> r) {
        Map<String, Object> o = new LinkedHashMap<>();
        String kind = r.getOrDefault("kind", "").trim().toUpperCase(Locale.ROOT);
        o.put("kind", kind);
        o.put("amount", intOr(r.get("amount"), 0));
        o.put("target", "VARIABLE".equals(kind) ? nz(r.get("key")) : nz(r.get("material")));
        o.put("value", nz(r.get("value")));
        o.put("command", nz(r.get("command")));
        o.put("raw", "");
        return o;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    /** Légende courte des origines, affichée une fois sous la barre du catalogue (issue #144). */
    /**
     * Action contextuelle de rechargement (issue #131), affichée <strong>uniquement</strong> quand
     * au moins une entrée est « Source uniquement ».
     *
     * <p>C'est le défaut que le ticket décrit : l'administrateur voyait bien « pas encore chargé en
     * jeu » mais n'avait <em>aucune</em> action, et devait devine qu'un redémarrage était
     * nécessaire. Le message distingue en plus les deux causes réelles, car un rechargement ne
     * répare que l'une des deux.</p>
     *
     * @param families familles à recharger conjointement (dépendances comprises)
     */
    private String reloadHint(Session session, String agentId, List<MergedRow> merged, String noun,
                              String... families) {
        if (agentId == null || !perms.can(session.effective(), Permission.ACTION_CONTENT_RELOAD)) {
            return "";
        }
        long sourceOnly = merged.stream().filter(row -> row.state() == CatalogState.SOURCE_ONLY).count();
        if (sourceOnly == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<div class=\"banner warn\">").append(Icons.icon("warning"));
        sb.append("<div><strong>").append(sourceOnly).append(" ").append(Http.esc(noun))
                .append(sourceOnly > 1 ? "s" : "").append(" pas encore chargé")
                .append(sourceOnly > 1 ? "s" : "").append(" en jeu.</strong> ");
        sb.append("Deux causes possibles, et une seule se répare ici : le fichier est "
                + "<strong>publié sur le serveur mais pas rechargé</strong> (le bouton ci-dessous "
                + "suffit), ou il n'a <strong>jamais été déployé</strong> depuis AWS (il faut alors "
                + "un déploiement — un rechargement n'y changerait rien). ");
        sb.append("L'<em>aperçu</em> lit le disque du serveur et tranche entre les deux.");
        sb.append("<form method=\"post\" action=\"/agents/action\" class=\"mt-2\">");
        sb.append("<input type=\"hidden\" name=\"_csrf\" value=\"").append(Http.esc(session.csrfToken()))
                .append("\">");
        sb.append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"families\" value=\"")
                .append(Http.esc(String.join(",", families))).append("\">");
        sb.append("<input type=\"hidden\" name=\"confirm\" value=\"true\">");
        sb.append("<input type=\"hidden\" name=\"return\" value=\"/ops\">");
        sb.append("<button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\" name=\"type\" "
                + "value=\"content.reload.preview\">Aperçu</button> ");
        sb.append("<button class=\"btn btn-sm btn-outline-primary\" type=\"submit\" name=\"type\" "
                + "value=\"content.reload\">Recharger en jeu</button>");
        sb.append("</form></div></div>");
        return sb.toString();
    }

    private static String catalogOriginLegend(String noun) {
        boolean f = "story".equals(noun);
        return "<p class=\"muted\" style=\"margin:.35rem 0 .1rem\">"
                + "Ce catalogue fusionne la <strong>source éditable</strong> et le dernier relevé du "
                + "<strong>serveur DEV</strong>. Le badge « Source uniquement » marque un" + (f ? "e " : " ") + noun
                + " enregistré" + (f ? "e" : "") + " dans la source mais pas encore chargé" + (f ? "e" : "")
                + " en jeu ; « Rafraîchir » interroge le serveur, tandis que la source est relue à chaque affichage.</p>";
    }

    /** Badge d'état court et humain dans l'en-tête d'accordion. Vide pour {@link CatalogState#SYNCED}. */
    private static String catalogStateBadge(CatalogState state, String noun) {
        return switch (state) {
            case SOURCE_ONLY -> "<span class=\"badge text-bg-info\" title=\"Cette " + noun + " est enregistrée dans "
                    + "la source mais n'est pas encore chargée par le serveur DEV.\">Source uniquement</span>";
            case RUNTIME_ONLY -> "<span class=\"badge text-bg-warning\" title=\"Cette " + noun + " est chargée par le "
                    + "serveur mais absente de la source éditable du Control Panel.\">Hors source</span>";
            case SYNCED -> "";
        };
    }

    /** Ligne d'explication en tête du corps d'accordion pour les états non synchronisés. */
    private static String catalogStateNote(CatalogState state, String noun) {
        return switch (state) {
            case SOURCE_ONLY -> "<p class=\"muted\">" + Icons.icon("history") + "Enregistrée dans la source. "
                    + "Elle sera prise en compte en jeu au prochain rechargement du contenu RPGQuest sur le serveur "
                    + "DEV — l'édition et l'activation en jeu restent deux étapes distinctes.</p>";
            case RUNTIME_ONLY -> "<p class=\"muted\">" + Icons.icon("warning") + "Chargée par le serveur mais "
                    + "introuvable dans la source éditable (fichier absent, supprimé ou renommé).</p>";
            case SYNCED -> "";
        };
    }

    /** Mots-clés ajoutés au texte de recherche pour retrouver une ligne par son état. */
    private static String catalogStateKeywords(CatalogState state) {
        return switch (state) {
            case SOURCE_ONLY -> "source uniquement non chargée en attente";
            case RUNTIME_ONLY -> "hors source runtime";
            case SYNCED -> "";
        };
    }

    // ================================================================================
    //  Joueurs
    // ================================================================================

    public String players(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("players", "Joueurs",
                "Annuaire des joueurs — connectés et déjà venus. Cliquez sur un joueur pour son détail "
                        + "et les actions disponibles (chaque action indique si elle fonctionne hors ligne).",
                docLink("joueurs-admin", "Documentation : gérer les joueurs")));
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        boolean canModerate = perms.can(session.effective(), Permission.PLAYER_MODERATE);
        boolean canVarGet = perms.can(session.effective(), Permission.ACTION_VARIABLE_GET);
        boolean canVarSet = perms.can(session.effective(), Permission.ACTION_VARIABLE_SET);
        boolean canGive = perms.can(session.effective(), Permission.ACTION_ITEM_GIVE);
        boolean canReset = perms.can(session.effective(), Permission.ACTION_PLAYER_RESET);
        // Issue #210 : permission DÉDIÉE, la plus restreinte du panel (OWNER uniquement).
        boolean canOp = perms.can(session.effective(), Permission.PLAYER_OP_WRITE);
        // Issue #140 : lire un solde et en créer sont deux gestes distincts.
        boolean canEcoRead = perms.can(session.effective(), Permission.ECONOMY_READ);
        boolean canEcoWrite = perms.can(session.effective(), Permission.ECONOMY_WRITE);
        String focus = cleanPlayer(q.get("player"));

        sb.append(agentPicker(agentId, "/players", ""));

        // ---- Toolbar compacte -------------------------------------------------------
        sb.append("<div class=\"npc-catbar\"><span class=\"npc-catbar-t\">Annuaire</span>");
        sb.append(compactRefresh(session, agentId, "player.catalog", "Actualiser", "btn-outline-primary", "/players"));
        sb.append("</div>");

        Optional<Map<String, Object>> catalog = latestDetails(agentId, "player.catalog");
        if (catalog.isEmpty()) {
            sb.append(Ui.empty("players", "Annuaire non chargé — cliquer sur « Actualiser »."));
            return sb.toString();
        }
        List<PlayerCatalog.Entry> all = PlayerCatalog.parse(catalog.get().get("players"));

        String query = q.getOrDefault("q", "").trim();
        PlayerCatalog.Filter filter = PlayerCatalog.Filter.of(q.get("filter"));
        PlayerCatalog.Sort sort = PlayerCatalog.Sort.of(q.get("sort"));
        int page = parsePageParam(q.get("page"));
        PlayerCatalog.Page view = PlayerCatalog.view(all, query, filter, sort, page, PlayerCatalog.DEFAULT_PAGE_SIZE);

        if (Boolean.TRUE.equals(catalog.get().get("truncated"))) {
            sb.append("<div class=\"alert alert-warning npc-alert\">").append(Icons.icon("warning"))
                    .append("<div>L'annuaire a été tronqué par l'agent (trop de joueurs). Affinez la recherche "
                            + "ou augmentez la limite côté serveur.</div></div>");
        }

        sb.append("<p class=\"npc-summary\">").append(view.total()).append(" joueur(s) connus · ")
                .append(view.online()).append(" en ligne · ").append(view.offline()).append(" hors ligne · ")
                .append(view.banned()).append(" banni(s)</p>");

        // ---- Recherche (GET) + filtres (liens) -------------------------------------
        sb.append(playersControls(agentId, query, filter, sort));
        sb.append("<p class=\"count-note\" data-noun=\"joueur\">").append(view.matched())
                .append(view.matched() > 1 ? " joueurs" : " joueur")
                .append(view.matched() != view.total() ? " (sur " + view.total() + ")" : "").append("</p>");

        if (view.entries().isEmpty()) {
            sb.append(Ui.empty("players", "Aucun joueur ne correspond à ce filtre / cette recherche."));
            return sb.toString();
        }

        List<Object> knownItems = latestDetails(agentId, "item.list").map(d -> asList(d.get("items"))).orElse(List.of());

        sb.append("<div class=\"accordion npc-accordion\" id=\"players-accordion\">");
        int idx = 0;
        for (PlayerCatalog.Entry e : view.entries()) {
            boolean open = !focus.isEmpty()
                    && (focus.equalsIgnoreCase(e.uuid()) || focus.equalsIgnoreCase(e.displayName()));
            sb.append(renderPlayerAccordionItem(session, agentId, e, idx++, open, knownItems,
                    canModerate, canVarGet, canVarSet, canGive, canReset, canOp,
                    canEcoRead, canEcoWrite));
        }
        sb.append("</div>");

        sb.append(playersPager(agentId, query, filter, sort, view));
        return sb.toString();
    }

    private static int parsePageParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Barre recherche (input-group Bootstrap, GET) + puces de filtre + tri, tout côté serveur. */
    private String playersControls(String agentId, String query, PlayerCatalog.Filter filter,
                                   PlayerCatalog.Sort sort) {
        String f = filter.name().toLowerCase(java.util.Locale.ROOT);
        String s = sort.name().toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder("<div class=\"npc-controls\">");
        sb.append("<form method=\"get\" action=\"/players\" class=\"input-group npc-search\">")
                .append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId)).append("\">")
                .append("<input type=\"hidden\" name=\"filter\" value=\"").append(Http.esc(f)).append("\">")
                .append("<input type=\"hidden\" name=\"sort\" value=\"").append(Http.esc(s)).append("\">")
                .append("<span class=\"input-group-text\">").append(Icons.icon("search")).append("</span>")
                .append("<input type=\"search\" class=\"form-control\" name=\"q\" value=\"").append(Http.esc(query))
                .append("\" placeholder=\"Rechercher un joueur (pseudo ou UUID)…\" aria-label=\"Rechercher un joueur\">")
                .append("<button class=\"btn btn-outline-secondary\" type=\"submit\">Rechercher</button>");
        if (!query.isEmpty()) {
            sb.append("<a class=\"btn btn-outline-secondary\" href=\"").append(playersUrl(agentId, "", f, s, 1))
                    .append("\">Effacer</a>");
        }
        sb.append("</form>");
        sb.append("<div class=\"npc-filters\">")
                .append(playersChip(agentId, query, "all", "Tous", s, filter == PlayerCatalog.Filter.ALL))
                .append(playersChip(agentId, query, "online", "En ligne", s, filter == PlayerCatalog.Filter.ONLINE))
                .append(playersChip(agentId, query, "offline", "Hors ligne", s, filter == PlayerCatalog.Filter.OFFLINE))
                .append(playersChip(agentId, query, "banned", "Bannis", s, filter == PlayerCatalog.Filter.BANNED))
                .append("<span class=\"npc-catbar-t\" style=\"margin-left:auto\">Tri</span>")
                .append(playersChip(agentId, query, f, "Plus récent", "recent", sort == PlayerCatalog.Sort.RECENT, true))
                .append(playersChip(agentId, query, f, "Nom A-Z", "name", sort == PlayerCatalog.Sort.NAME, true))
                .append("</div>");
        sb.append("</div>");
        return sb.toString();
    }

    private String playersChip(String agentId, String query, String filterVal, String label, String sortVal,
                               boolean on) {
        return playersChip(agentId, query, filterVal, label, sortVal, on, false);
    }

    private String playersChip(String agentId, String query, String filterVal, String label, String sortVal,
                               boolean on, boolean sortChip) {
        // sortChip : filterVal porte le filtre courant, sortVal la valeur de tri visée ; sinon
        // filterVal est le filtre visé et sortVal le tri courant. Dans les deux cas : filtre puis tri.
        String href = playersUrl(agentId, query, filterVal, sortVal, 1);
        return "<a class=\"pa-chip" + (on ? " on" : "") + "\" href=\"" + href + "\">" + Http.esc(label) + "</a>";
    }

    private static String playersUrl(String agentId, String query, String filter, String sort, int page) {
        StringBuilder sb = new StringBuilder("/players?agent=").append(Http.esc(agentId));
        if (query != null && !query.isBlank()) {
            sb.append("&q=").append(Http.esc(query));
        }
        if (filter != null && !filter.isBlank() && !"all".equals(filter)) {
            sb.append("&filter=").append(Http.esc(filter));
        }
        if (sort != null && !sort.isBlank() && !"recent".equals(sort)) {
            sb.append("&sort=").append(Http.esc(sort));
        }
        if (page > 1) {
            sb.append("&page=").append(page);
        }
        return sb.toString();
    }

    private String playersPager(String agentId, String query, PlayerCatalog.Filter filter, PlayerCatalog.Sort sort,
                                PlayerCatalog.Page view) {
        if (view.pageCount() <= 1) {
            return "";
        }
        String f = filter.name().toLowerCase(java.util.Locale.ROOT);
        String s = sort.name().toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder("<nav class=\"npc-controls\" aria-label=\"Pagination des joueurs\">");
        if (view.page() > 1) {
            sb.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"")
                    .append(playersUrl(agentId, query, f, s, view.page() - 1)).append("\">").append(Icons.icon("arrow-left"))
                    .append("Précédent</a>");
        }
        sb.append("<span class=\"muted\">Page ").append(view.page()).append(" / ").append(view.pageCount()).append("</span>");
        if (view.page() < view.pageCount()) {
            sb.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"")
                    .append(playersUrl(agentId, query, f, s, view.page() + 1)).append("\">Suivant")
                    .append(Icons.icon("arrow-up")).append("</a>");
        }
        return sb.append("</nav>").toString();
    }

    // ================================================================================
    //  Joueurs — une ligne d'accordion : synthèse + détail structuré
    // ================================================================================

    private String renderPlayerAccordionItem(Session session, String agentId, PlayerCatalog.Entry e, int idx,
                                             boolean open, List<Object> knownItems, boolean canModerate,
                                             boolean canVarGet, boolean canVarSet, boolean canGive,
                                             boolean canReset, boolean canOp,
                                             boolean canEcoRead, boolean canEcoWrite) {
        String uuid = e.uuid();
        String name = e.displayName();
        String slug = "pl-" + idx + "-" + uuid.replaceAll("[^0-9a-fA-F]", "").substring(0, Math.min(12, uuid.replaceAll("[^0-9a-fA-F]", "").length()));
        String cat = (e.online() ? "online" : "offline") + (e.banned() ? " banned" : "");

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"accordion-item npc-item\" data-filter-item=\"players\" data-filter-cat=\"")
                .append(cat).append("\" data-res-id=\"").append(Http.esc(uuid)).append("\">");
        sb.append("<h3 class=\"accordion-header\">");
        sb.append("<button class=\"accordion-button").append(open ? "" : " collapsed")
                .append(" npc-head\" type=\"button\" data-bs-toggle=\"collapse\" data-bs-target=\"#").append(slug)
                .append("\" aria-expanded=\"").append(open).append("\" aria-controls=\"").append(slug).append("\">");
        sb.append("<span class=\"npc-head-main\"><span class=\"npc-name\">").append(Http.esc(name)).append("</span>")
                .append("<span class=\"muted npc-id\">").append(playerWhenShort(e)).append("</span></span>");
        sb.append("<span class=\"npc-head-badges\">").append(onlineBadge(e.online()));
        if (e.banned()) {
            sb.append("<span class=\"badge text-bg-danger\">Banni</span>");
        }
        sb.append("</span></button></h3>");

        sb.append("<div id=\"").append(slug).append("\" class=\"accordion-collapse collapse").append(open ? " show" : "")
                .append("\" data-bs-parent=\"#players-accordion\"><div class=\"accordion-body npc-detail\">");

        // ---- IDENTITÉ ----
        sb.append(detailSection("players", "Identité"));
        sb.append("<dl class=\"npc-dl\">");
        dlRow(sb, "Pseudo", Http.esc(name));
        dlRow(sb, "UUID", Ui.id(uuid, uuid));
        dlRow(sb, "État", onlineBadge(e.online()) + (e.banned() ? " <span class=\"badge text-bg-danger\">Banni</span>" : ""));
        dlRow(sb, "Première connexion", playerWhen(e.firstPlayed()));
        dlRow(sb, "Dernière connexion", e.online() ? "maintenant (connecté)" : playerWhen(e.lastSeen()));
        sb.append("</dl>");

        // ---- ACTIVITÉ ----
        sb.append(detailSection("activity", "Activité"));
        sb.append("<dl class=\"npc-dl\">");
        if (e.online()) {
            dlRow(sb, "Monde", e.world() == null ? "<span class=\"muted\">?</span>" : Http.esc(e.world()));
            String pos = e.x() == null ? "<span class=\"muted\">?</span>"
                    : Http.esc(e.x() + " " + e.y() + " " + e.z());
            dlRow(sb, "Position", pos);
        } else {
            dlRow(sb, "Statut", "<span class=\"muted\">Hors ligne — dernière présence : "
                    + Http.esc(plainWhen(e.lastSeen())) + "</span>");
        }
        sb.append("</dl>");

        // ---- RPGQUEST (liens, jamais un dump) ----
        sb.append(detailSection("quests", "RPGQuest"));
        sb.append("<div class=\"npc-actions d-flex flex-wrap gap-2\">");
        sb.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"/quests?agent=").append(Http.esc(agentId))
                .append("&player=").append(Http.esc(uuid)).append("\">").append(Icons.icon("open")).append("Quêtes du joueur</a>");
        sb.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"/stories?agent=").append(Http.esc(agentId))
                .append("&player=").append(Http.esc(uuid)).append("\">").append(Icons.icon("open")).append("Stories du joueur</a>");
        sb.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"/actions?domain=players&q=")
                .append(Http.esc(shorten(uuid, 8))).append("\">").append(Icons.icon("history"))
                .append("Historique de ce joueur</a>");
        sb.append("</div>");

        // ---- DROITS ----
        sb.append(detailSection("shield-lock", "Droits"));
        sb.append("<dl class=\"npc-dl\">");
        dlRow(sb, "Droit de construction",
                "<span class=\"muted\">non géré par PlugAdmin</span>");
        sb.append("</dl>");
        sb.append("<p class=\"muted npc-content-empty\">Accorder un droit de construction persistant à un joueur "
                + "hors ligne nécessite un gestionnaire de permissions persistant (issue #27 + LuckPerms) — "
                + "voir la documentation. PlugAdmin ne pose jamais un faux interrupteur qui ne contrôle rien.</p>");

        // ---- MODÉRATION ----
        sb.append(detailSection("shield-lock", "Modération"));
        sb.append("<dl class=\"npc-dl\">");
        if (e.banned()) {
            dlRow(sb, "Bannissement", "<span class=\"badge text-bg-danger\">Actif</span>"
                    + (e.banReason() == null ? "" : " <span class=\"muted\">— " + Http.esc(e.banReason()) + "</span>"));
        } else {
            dlRow(sb, "Bannissement", "<span class=\"muted\">aucun</span>");
        }
        // Issue #210 : statut OP RÉEL, relu du serveur au dernier relevé. La mention « Minecraft »
        // est délibérée : ce n'est ni le rôle PlugAdmin, ni un droit de construction, ni un bypass.
        dlRow(sb, "OP Minecraft", e.op()
                ? "<span class=\"badge text-bg-warning\">Opérateur</span>"
                : "<span class=\"muted\">non</span>");
        dlRow(sb, "Whitelist", e.whitelisted()
                ? "<span class=\"badge text-bg-info\">Présent</span>"
                : "<span class=\"muted\">absent</span>");
        sb.append("</dl>");

        // ---- ACTIONS ----
        List<String[]> toggles = new ArrayList<>();
        StringBuilder forms = new StringBuilder();

        if (canModerate && !e.banned()) {
            toggles.add(new String[] {slug + "-f-ban", "Bannir", "shield-lock", "btn-danger"});
            forms.append(actionCollapse(slug + "-f-ban", "<div class=\"card card-body npc-formcard\">"
                    + playerBanForm(session, agentId, uuid, name) + "</div>"));
        }
        if (canModerate && e.banned()) {
            toggles.add(new String[] {slug + "-f-unban", "Débannir", "shield-lock", "btn-outline-primary"});
            forms.append(actionCollapse(slug + "-f-unban", "<div class=\"card card-body npc-formcard\">"
                    + playerUnbanForm(session, agentId, uuid, name) + "</div>"));
        }
        if (canVarGet || canVarSet || canGive) {
            toggles.add(new String[] {slug + "-f-tools", "Outils de test", "code-slash", "btn-outline-secondary"});
            forms.append(actionCollapse(slug + "-f-tools", "<div class=\"card card-body npc-formcard\">"
                    + playerTestTools(session, agentId, uuid, name, e.online(), knownItems, canVarGet, canVarSet, canGive)
                    + "</div>"));
        }
        // ---- Issue #140 : monnaie ----
        if (canEcoRead) {
            toggles.add(new String[] {slug + "-f-eco", "Monnaie", "box", "btn-outline-secondary"});
            forms.append(actionCollapse(slug + "-f-eco", "<div class=\"card card-body npc-formcard\">"
                    + playerEconomyTools(session, agentId, uuid, name, canEcoWrite) + "</div>"));
            toggles.add(new String[] {slug + "-f-debt", "Récompenses en attente", "money",
                    "btn-outline-secondary"});
            forms.append(actionCollapse(slug + "-f-debt", "<div class=\"card card-body npc-formcard\">"
                    + playerRewardDebtTools(session, agentId, uuid, name, canEcoWrite) + "</div>"));
        }
        // ---- Issue #210 : OP/DEOP, secours, expulsion, whitelist ----
        if (canOp) {
            String label = e.op() ? "Retirer OP" : "Accorder OP";
            toggles.add(new String[] {slug + "-f-op", label, "shield-lock", "btn-outline-danger"});
            forms.append(actionCollapse(slug + "-f-op", "<div class=\"card card-body npc-formcard\">"
                    + playerOpForm(session, agentId, uuid, name, e.op()) + "</div>"));
        }
        if (canModerate) {
            toggles.add(new String[] {slug + "-f-rescue", "Renvoyer au Hub", "travel",
                    e.online() ? "btn-outline-primary" : "btn-outline-secondary disabled"});
            forms.append(actionCollapse(slug + "-f-rescue", "<div class=\"card card-body npc-formcard\">"
                    + playerRescueForm(session, agentId, uuid, name, e.online()) + "</div>"));
            toggles.add(new String[] {slug + "-f-kick", "Expulser", "open",
                    e.online() ? "btn-outline-warning" : "btn-outline-secondary disabled"});
            forms.append(actionCollapse(slug + "-f-kick", "<div class=\"card card-body npc-formcard\">"
                    + playerKickForm(session, agentId, uuid, name, e.online()) + "</div>"));
            toggles.add(new String[] {slug + "-f-wl", e.whitelisted() ? "Retirer de la whitelist"
                    : "Ajouter à la whitelist", "users", "btn-outline-secondary"});
            forms.append(actionCollapse(slug + "-f-wl", "<div class=\"card card-body npc-formcard\">"
                    + playerWhitelistForm(session, agentId, uuid, name, e.whitelisted()) + "</div>"));
        }
        if (canReset) {
            toggles.add(new String[] {slug + "-f-reset", "Reset « nouveau joueur »", "trash", "btn-outline-danger"});
            forms.append(actionCollapse(slug + "-f-reset", "<div class=\"card card-body npc-formcard\">"
                    + playerResetTools(session, agentId, uuid, name) + "</div>"));
        }

        if (!toggles.isEmpty()) {
            sb.append(detailSection("target", "Actions"));
            sb.append("<div class=\"npc-actions d-flex flex-wrap gap-2\">");
            for (String[] t : toggles) {
                sb.append(actionToggle(t[0], t[1], t[2], t[3]));
            }
            sb.append("</div>");
        } else {
            sb.append("<p class=\"muted npc-content-empty\">Aucune action disponible avec votre rôle.</p>");
        }
        sb.append(forms);

        // Résultats récents pour ce joueur (compact — pas l'historique complet).
        for (String type : new String[] {"player.ban", "player.unban", "player.resetnew.confirm",
                "player.op", "player.deop", "player.send.hub", "player.kick",
                "player.whitelist.add", "player.whitelist.remove",
                "economy.balance", "economy.credit", "economy.debit",
                "economy.debts", "economy.debt.retry", "economy.debt.settle"}) {
            latestForPlayer(agentId, type, uuid).ifPresent(row -> sb.append(resultLine("Dernière action", row)));
        }

        sb.append("</div></div></div>");
        return sb.toString();
    }

    private static String onlineBadge(boolean online) {
        return online
                ? "<span class=\"badge text-bg-success\">● En ligne</span>"
                : "<span class=\"badge text-bg-secondary\">○ Hors ligne</span>";
    }

    /** « il y a 5 min » / « hier » / date courte. {@code null} → « jamais ». */
    private static String playerWhen(Long millis) {
        if (millis == null || millis <= 0) {
            return "<span class=\"muted\">inconnue</span>";
        }
        return "<span title=\"" + Http.esc(plainWhen(millis)) + "\">" + Http.esc(relWhen(millis)) + "</span>";
    }

    private static String playerWhenShort(PlayerCatalog.Entry e) {
        if (e.online()) {
            return "Dernière connexion : maintenant";
        }
        return "Dernière connexion : " + Http.esc(relWhen(e.lastSeen()));
    }

    private static String relWhen(Long millis) {
        if (millis == null || millis <= 0) {
            return "jamais";
        }
        long delta = System.currentTimeMillis() - millis;
        if (delta < 0) {
            delta = 0;
        }
        long min = delta / 60_000;
        if (min < 1) {
            return "à l'instant";
        }
        if (min < 60) {
            return "il y a " + min + " min";
        }
        long hours = min / 60;
        if (hours < 24) {
            return "il y a " + hours + " h";
        }
        long days = hours / 24;
        if (days < 30) {
            return "il y a " + days + " j";
        }
        return plainWhen(millis);
    }

    private static String plainWhen(Long millis) {
        if (millis == null || millis <= 0) {
            return "jamais";
        }
        return java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'"));
    }

    private String playerBanForm(Session session, String agentId, String uuid, String name) {
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">").append(Icons.icon("shield-lock"))
                .append("Bannir ").append(Http.esc(name)).append("</p>");
        sb.append("<p class=\"muted\">Ce joueur ne pourra plus rejoindre le serveur. Fonctionne "
                + "<strong>en ligne comme hors ligne</strong> ; s'il est connecté il est expulsé immédiatement.</p>");
        sb.append(formStart(session, agentId, "player.ban", "/players", uuid));
        sb.append("<label class=\"form-label\">Raison <span class=\"muted\">(obligatoire)</span></label>");
        sb.append("<input class=\"form-control\" type=\"text\" name=\"reason\" maxlength=\"256\" required "
                + "autocomplete=\"off\" placeholder=\"Ex. : comportement toxique répété\">");
        sb.append(confirmBox("Je confirme le bannissement de « " + name + " »."));
        sb.append("<button class=\"btn btn-danger\" type=\"submit\">Bannir le joueur</button></form>");
        return sb.toString();
    }

    /**
     * Formulaire OP/DEOP (issue #210). Trois garde-fous, et aucun n'est décoratif :
     * <ul>
     *   <li><strong>raison obligatoire</strong> — un audit sans motif est inexploitable ;</li>
     *   <li><strong>confirmation par l'identité</strong> — retaper le pseudo exact, pour qu'un clic
     *       sur la mauvaise fiche ne puisse pas élever le mauvais compte ;</li>
     *   <li>rappel écrit que <strong>seul OP Minecraft change</strong>.</li>
     * </ul>
     */
    /**
     * Outils monétaires (issue #140) : lire le solde réel et son journal, puis créditer ou débiter
     * avec une raison.
     *
     * <p><strong>Le portefeuille persistant est l'unique source de vérité du solde.</strong> Aucun
     * objet d'inventaire n'est consulté ni interprété comme de la monnaie, et rien ici ne convertit
     * quoi que ce soit.</p>
     *
     * <p>La raison est obligatoire parce qu'elle part dans le <em>journal des transactions</em> :
     * sans elle, une création administrative serait indiscernable d'un gain de jeu quelques mois
     * plus tard.</p>
     */
    private String playerEconomyTools(Session session, String agentId, String uuid, String name,
                                      boolean canWrite) {
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">").append(Icons.icon("box"))
                .append("Monnaie de ").append(Http.esc(name)).append("</p>");
        sb.append("<p class=\"muted\">Le solde vient du <strong>portefeuille persistant</strong>, "
                + "seule source de vérité. Aucun objet d'inventaire n'est compté comme de la "
                + "monnaie.</p>");

        // Lecture : relève le solde ET le journal récent.
        sb.append(formStart(session, agentId, "economy.balance", "/players", uuid));
        sb.append("<input type=\"hidden\" name=\"history\" value=\"20\">");
        sb.append("<button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\">")
                .append(Icons.icon("refresh")).append("Lire le solde et le journal</button></form>");
        latestForPlayer(agentId, "economy.balance", uuid)
                .ifPresent(row -> sb.append(resultLine("Dernier relevé", row)));

        if (!canWrite) {
            sb.append("<p class=\"muted\">Votre rôle permet de consulter le solde, pas de le "
                    + "modifier.</p>");
            return sb.toString();
        }

        for (boolean credit : new boolean[] {true, false}) {
            String type = credit ? "economy.credit" : "economy.debit";
            sb.append("<hr>");
            sb.append("<p class=\"fs-h\">").append(credit ? "Créditer" : "Débiter").append("</p>");
            if (credit) {
                sb.append("<p class=\"muted\">Un crédit <strong>crée de la monnaie</strong>. "
                        + "L'opération est tracée avec sa raison.</p>");
                sb.append("<p class=\"muted\">Ce n'est <strong>pas</strong> la même chose que "
                        + "reprendre une récompense de quête : un crédit manuel est une compensation "
                        + "libre et ne règle aucune dette. Si vous compensez une récompense non payée, "
                        + "marquez-la ensuite <strong>réglée à la main</strong> dans « Récompenses en "
                        + "attente », sinon elle resterait payable une seconde fois.</p>");
            } else {
                sb.append("<p class=\"muted\">Un débit ne peut <strong>jamais</strong> rendre le "
                        + "solde négatif : si les fonds sont insuffisants, rien n'est modifié et le "
                        + "résultat le dit.</p>");
            }
            sb.append(formStart(session, agentId, type, "/players", uuid));
            sb.append("<label class=\"form-label\">Montant (entier positif, max ")
                    .append(AgentActionCatalog.MAX_ECONOMY_AMOUNT).append(")</label>");
            sb.append("<input class=\"form-control mb-2\" type=\"number\" min=\"1\" max=\"")
                    .append(AgentActionCatalog.MAX_ECONOMY_AMOUNT)
                    .append("\" name=\"amount\" required>");
            sb.append("<label class=\"form-label\">Raison (obligatoire, enregistrée au journal)</label>");
            sb.append("<input class=\"form-control mb-2\" name=\"reason\" maxlength=\"200\" required>");
            sb.append(confirmBox(credit
                    ? "Je confirme la création de monnaie pour « " + name + " »."
                    : "Je confirme le retrait de monnaie à « " + name + " »."));
            sb.append("<button class=\"btn btn-sm ")
                    .append(credit ? "btn-outline-primary" : "btn-outline-warning")
                    .append("\" type=\"submit\">").append(credit ? "Créditer" : "Débiter")
                    .append("</button></form>");
            latestForPlayer(agentId, type, uuid)
                    .ifPresent(row -> sb.append(resultLine("Dernière opération", row)));
        }
        return sb.toString();
    }

    /**
     * Récompenses monétaires de quête <strong>restées dues</strong> (issue #16, second lot) : état
     * réel, motif d'échec, et deux actions qu'il ne faut surtout pas confondre.
     *
     * <p>La page explicite la différence, parce qu'elle décide de ce que touche l'argent :</p>
     * <ul>
     *   <li><strong>Reprendre</strong> paie exactement ce qui a été enregistré à la complétion, avec
     *       l'identité de paiement initiale. Aucun montant n'est saisissable, donc aucun ne peut
     *       être inventé, et un double paiement est impossible ;</li>
     *   <li><strong>Créditer manuellement</strong> (bloc « Monnaie » ci-dessus) est une
     *       compensation libre : elle ne règle <strong>pas</strong> la dette, qui resterait donc
     *       payable une seconde fois. Après une compensation, il faut marquer la récompense
     *       <strong>réglée à la main</strong> — c'est exactement ce que fait le second bouton.</li>
     * </ul>
     */
    private String playerRewardDebtTools(Session session, String agentId, String uuid, String name,
                                         boolean canWrite) {
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">").append(Icons.icon("money"))
                .append("Récompenses en attente — ").append(Http.esc(name)).append("</p>");
        sb.append("<p class=\"muted\">Une récompense monétaire de quête qui n'a pas pu être créditée "
                + "(arrêt du serveur, panne de base) reste <strong>enregistrée</strong> avec son "
                + "joueur, sa quête, son occurrence et son montant. Ce montant est celui <strong>figé "
                + "à la complétion</strong> : rééditer la quête ensuite ne change pas ce qui est dû.</p>");
        // Limite HISTORIQUE, dite franchement : avant ce mécanisme, une récompense perdue ne
        // laissait aucune trace. Rien ne permet de la reconstituer, et inventer un montant serait
        // pire que de ne rien faire.
        sb.append(debtHistoryNote());

        sb.append(formStart(session, agentId, "economy.debts", "/players", uuid));
        sb.append("<input type=\"hidden\" name=\"limit\" value=\"20\">");
        sb.append("<button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\">")
                .append(Icons.icon("refresh")).append("Lire les récompenses en attente</button></form>");

        Optional<AgentActionRow> survey = latestForPlayer(agentId, "economy.debts", uuid);
        survey.ifPresent(row -> sb.append(resultLine("Dernier relevé", row)));
        List<Object> debts = survey.flatMap(this::detailsOf)
                .map(details -> asList(details.get("debts"))).orElse(List.of());

        if (survey.isEmpty()) {
            sb.append(Ui.banner("info", "Aucun relevé encore demandé pour ce joueur : le bouton "
                    + "ci-dessus interroge le serveur."));
        } else if (debts.isEmpty()) {
            sb.append(Ui.banner("ok", "Aucune récompense monétaire en attente au dernier relevé."));
        } else {
            sb.append("<table class=\"table table-sm align-middle\"><thead><tr>"
                    + "<th>Quête</th><th>Occurrence</th><th>Montant</th><th>État</th></tr></thead><tbody>");
            for (Object raw : debts) {
                Map<String, Object> debt = asMap(raw);
                String grant = str(debt.get("grantId"));
                long attempts = asLong(debt.get("attempts"));
                String lastError = str(debt.get("lastError"));
                sb.append("<tr><td>").append(Http.esc(str(debt.get("questTitle"))))
                        .append("<br><span class=\"muted\">").append(Http.esc(str(debt.get("questId"))))
                        .append("</span></td>");
                sb.append("<td>n°").append(asLong(debt.get("occurrence")));
                sb.append("<span class=\"muted\"> / récompense ").append(asLong(debt.get("rewardIndex")) + 1)
                        .append("</span></td>");
                sb.append("<td><strong>").append(asLong(debt.get("amount"))).append("</strong> pièce(s)</td>");
                // État RÉEL : jamais « en attente » tout court quand il y a eu des échecs.
                if (attempts == 0) {
                    sb.append("<td><span class=\"badge text-bg-secondary\">En attente</span></td>");
                } else {
                    sb.append("<td><span class=\"badge text-bg-warning\">En échec (")
                            .append(attempts).append(" tentative(s))</span>");
                    if (!lastError.isBlank()) {
                        sb.append("<br><span class=\"muted\">").append(Http.esc(lastError)).append("</span>");
                    }
                    sb.append("</td>");
                }
                sb.append("</tr>");
                if (canWrite) {
                    sb.append("<tr><td colspan=\"4\">").append(debtActionForms(session, agentId, uuid, name, grant))
                            .append("</td></tr>");
                }
            }
            sb.append("</tbody></table>");
            if (!canWrite) {
                sb.append("<p class=\"muted\">Votre rôle permet de consulter les récompenses dues, "
                        + "pas de les régler.</p>");
            }
        }

        for (String type : new String[] {"economy.debt.retry", "economy.debt.settle"}) {
            latestForPlayer(agentId, type, uuid)
                    .ifPresent(row -> sb.append(resultLine("Dernière opération", row)));
        }
        return sb.toString();
    }

    /**
     * Limite historique assumée : les complétions antérieures à ce mécanisme n'ont laissé
     * <strong>aucune trace</strong> d'une récompense non payée. On le dit, au lieu de reconstituer
     * une dette à partir d'une définition de quête actuelle — ce qui inventerait un montant.
     */
    private static String debtHistoryNote() {
        return Ui.banner("info", "Seules les complétions <strong>postérieures à cette mise à jour</strong> "
                + "peuvent apparaître ici. Une récompense perdue avant n'a laissé aucune trace "
                + "exploitable : rien ne permet de la prouver, et aucun montant n'est deviné. Si un "
                + "joueur signale un gain manquant plus ancien, la seule voie est un crédit manuel "
                + "avec sa raison.");
    }

    /** Les deux gestes possibles sur une dette, côte à côte et explicitement distingués. */
    private String debtActionForms(Session session, String agentId, String uuid, String name, String grant) {
        StringBuilder sb = new StringBuilder("<div class=\"d-flex flex-wrap gap-3\">");

        sb.append("<div><p class=\"muted mb-1\"><strong>Reprendre</strong> : paie le montant "
                + "enregistré, avec l'identité initiale. Aucun montant à saisir — donc aucun à "
                + "inventer, et un second paiement est impossible.</p>");
        sb.append(formStart(session, agentId, "economy.debt.retry", "/players", uuid));
        sb.append("<input type=\"hidden\" name=\"grant\" value=\"").append(Http.esc(grant)).append("\">");
        sb.append(confirmBox("Je confirme la reprise du paiement pour « " + name + " »."));
        sb.append("<button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">")
                .append(Icons.icon("refresh")).append("Reprendre le paiement</button></form></div>");

        sb.append("<div><p class=\"muted mb-1\"><strong>Réglée à la main</strong> : à utiliser "
                + "<em>après</em> avoir compensé le joueur par un crédit manuel. Ne touche à "
                + "<strong>aucun</strong> solde — elle empêche seulement cette même récompense "
                + "d'être payée une seconde fois.</p>");
        sb.append(formStart(session, agentId, "economy.debt.settle", "/players", uuid));
        sb.append("<input type=\"hidden\" name=\"grant\" value=\"").append(Http.esc(grant)).append("\">");
        sb.append("<label class=\"form-label\">Raison (obligatoire, conservée avec la récompense)</label>");
        sb.append("<input class=\"form-control mb-2\" name=\"reason\" maxlength=\"200\" required>");
        sb.append(confirmBox("Je confirme que « " + name + " » a déjà été compensé autrement."));
        sb.append("<button class=\"btn btn-sm btn-outline-warning\" type=\"submit\">")
                .append(Icons.icon("check")).append("Marquer réglée à la main</button></form></div>");

        return sb.append("</div>").toString();
    }

    private String playerOpForm(Session session, String agentId, String uuid, String name, boolean currentlyOp) {
        String type = currentlyOp ? "player.deop" : "player.op";
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">").append(Icons.icon("shield-lock"))
                .append(currentlyOp ? "Retirer OP à " : "Accorder OP à ").append(Http.esc(name)).append("</p>");
        sb.append("<p class=\"muted\">OP Minecraft uniquement. Le rôle PlugAdmin, les droits de "
                + "construction par monde et le bypass de gameplay ne sont <strong>pas</strong> "
                + "modifiés par cette action.</p>");
        sb.append(formStart(session, agentId, type, "/players", uuid));
        sb.append("<label class=\"form-label\">Raison (obligatoire)</label>");
        sb.append("<input class=\"form-control mb-2\" name=\"reason\" maxlength=\"200\" required>");
        sb.append("<label class=\"form-label\">Retaper le pseudo exact pour confirmer la cible</label>");
        sb.append("<input class=\"form-control mb-2\" name=\"confirm_player\" autocomplete=\"off\" "
                + "placeholder=\"").append(Http.esc(name)).append("\" required>");
        sb.append(confirmBox(currentlyOp ? "Je confirme le retrait d'OP." : "Je confirme l'élévation OP."));
        sb.append("<button class=\"btn btn-outline-danger\" type=\"submit\">")
                .append(currentlyOp ? "Retirer OP" : "Accorder OP").append("</button></form>");
        return sb.toString();
    }

    /** Renvoi au Hub (issue #210) — joueur connecté uniquement, et on le dit si ce n'est pas le cas. */
    private String playerRescueForm(Session session, String agentId, String uuid, String name, boolean online) {
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">").append(Icons.icon("travel"))
                .append("Renvoyer ").append(Http.esc(name)).append(" au Hub</p>");
        if (!online) {
            return sb.append(Ui.banner("info", "Action impossible hors ligne : un renvoi au Hub "
                    + "déplace un joueur <strong>connecté</strong>. Elle redeviendra disponible dès "
                    + "sa prochaine connexion.")).toString();
        }
        sb.append("<p class=\"muted\">Position sûre du Hub, résolue par le même mécanisme que la "
                + "Pierre de retour. <strong>Inventaire, Acte de propriété, claim et progression sont "
                + "préservés</strong> — aucun reset, aucune récompense.</p>");
        sb.append(formStart(session, agentId, "player.send.hub", "/players", uuid));
        sb.append(confirmBox("Je confirme le renvoi de « " + name + " » au Hub."));
        sb.append("<button class=\"btn btn-outline-primary\" type=\"submit\">Renvoyer au Hub</button></form>");
        return sb.toString();
    }

    /** Expulsion avec raison (issue #210) — joueur connecté uniquement. */
    private String playerKickForm(Session session, String agentId, String uuid, String name, boolean online) {
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">").append(Icons.icon("open"))
                .append("Expulser ").append(Http.esc(name)).append("</p>");
        if (!online) {
            return sb.append(Ui.banner("info", "Action impossible hors ligne : il n'y a rien à "
                    + "expulser. Pour empêcher une reconnexion, utiliser « Bannir ».")).toString();
        }
        sb.append(formStart(session, agentId, "player.kick", "/players", uuid));
        sb.append("<label class=\"form-label\">Raison affichée au joueur (obligatoire)</label>");
        sb.append("<input class=\"form-control mb-2\" name=\"reason\" maxlength=\"200\" required>");
        sb.append("<p class=\"muted\">L'expulsion déconnecte le joueur ; elle ne l'empêche pas de "
                + "revenir. C'est « Bannir » qui l'en empêche.</p>");
        sb.append(confirmBox("Je confirme l'expulsion de « " + name + " »."));
        sb.append("<button class=\"btn btn-outline-warning\" type=\"submit\">Expulser</button></form>");
        return sb.toString();
    }

    /** Whitelist (issue #210) — fonctionne hors ligne, et le résultat dira si elle est appliquée. */
    private String playerWhitelistForm(Session session, String agentId, String uuid, String name,
                                       boolean whitelisted) {
        String type = whitelisted ? "player.whitelist.remove" : "player.whitelist.add";
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">").append(Icons.icon("users"))
                .append(whitelisted ? "Retirer " : "Ajouter ").append(Http.esc(name))
                .append(whitelisted ? " de la whitelist" : " à la whitelist").append("</p>");
        sb.append("<p class=\"muted\">Fonctionne même hors ligne. Le résultat précisera si la "
                + "whitelist est <strong>réellement appliquée</strong> par le serveur : l'ajouter "
                + "alors qu'elle est désactivée ne protège rien.</p>");
        sb.append(formStart(session, agentId, type, "/players", uuid));
        sb.append(confirmBox(whitelisted ? "Je confirme le retrait de la whitelist."
                : "Je confirme l'ajout à la whitelist."));
        sb.append("<button class=\"btn btn-outline-secondary\" type=\"submit\">")
                .append(whitelisted ? "Retirer" : "Ajouter").append("</button></form>");
        return sb.toString();
    }

    private String playerUnbanForm(Session session, String agentId, String uuid, String name) {
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">").append(Icons.icon("shield-lock"))
                .append("Débannir ").append(Http.esc(name)).append("</p>");
        sb.append(formStart(session, agentId, "player.unban", "/players", uuid));
        sb.append(confirmBox("Lever le bannissement de « " + name + " » — il pourra de nouveau se connecter."));
        sb.append("<button class=\"btn btn-outline-primary\" type=\"submit\">Débannir</button></form>");
        return sb.toString();
    }

    private String playerTestTools(Session session, String agentId, String uuid, String name, boolean online,
                                   List<Object> knownItems, boolean canVarGet, boolean canVarSet, boolean canGive) {
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">").append(Icons.icon("code-slash"))
                .append("Outils de test</p>");
        if (canVarGet) {
            sb.append("<p class=\"npc-fs-h\">Lire une variable <span class=\"badge text-bg-secondary\">hors ligne OK</span></p>");
            sb.append(formStart(session, agentId, "player.variable.get", "/players", uuid));
            sb.append("<label class=\"form-label\">Clé</label><input class=\"form-control\" list=\"varkeys-").append(Http.esc(uuid))
                    .append("\" name=\"key\" value=\"CLAIM_TIER_1\" autocomplete=\"off\">");
            sb.append("<datalist id=\"varkeys-").append(Http.esc(uuid)).append("\">");
            for (String k : AgentActionCatalog.KNOWN_VARIABLE_KEYS) {
                sb.append("<option value=\"").append(Http.esc(k)).append("\">");
            }
            sb.append("</datalist><button class=\"btn btn-sm\" type=\"submit\">Lire</button></form>");
            latestForPlayer(agentId, "player.variable.get", uuid).ifPresent(row ->
                    sb.append(resultLine("Dernière lecture", row)));
        }
        if (canVarSet) {
            sb.append("<p class=\"npc-fs-h\">Écrire une variable "
                    + "<span class=\"badge text-bg-secondary\">hors ligne OK</span> "
                    + "<span class=\"muted\">— outil debug, ne rejoue pas une progression</span></p>");
            sb.append(formStart(session, agentId, "player.variable.set", "/players", uuid));
            sb.append("<label class=\"form-label\">Clé</label><input class=\"form-control\" name=\"key\" "
                    + "value=\"CLAIM_TIER_1\" autocomplete=\"off\">");
            sb.append("<label class=\"form-label\">Valeur</label><input class=\"form-control\" type=\"text\" name=\"value\" value=\"true\">");
            sb.append(confirmBox("Je comprends que c'est un outil debug bas niveau."));
            sb.append("<button class=\"btn btn-sm\" type=\"submit\">Écrire (debug)</button></form>");
            latestForPlayer(agentId, "player.variable.set", uuid).ifPresent(row ->
                    sb.append(resultLine("Dernière écriture", row)));
        }
        if (canGive) {
            sb.append("<p class=\"npc-fs-h\">Donner un objet ");
            if (online) {
                sb.append("<span class=\"badge text-bg-warning\">en ligne uniquement</span></p>");
                sb.append(formStart(session, agentId, "player.item.give", "/players", uuid));
                sb.append("<label class=\"form-label\">Objet</label>");
                if (knownItems.isEmpty()) {
                    sb.append("<input class=\"form-control\" type=\"text\" name=\"item_id\" placeholder=\"rpgquest:rune_rappel\">");
                } else {
                    sb.append("<select class=\"form-select\" name=\"item_id\">");
                    for (Object o : knownItems) {
                        Map<String, Object> it = asMap(o);
                        String dn = MiniText.plain(str(it.get("displayName")));
                        sb.append("<option value=\"").append(Http.esc(str(it.get("id")))).append("\">")
                                .append(Http.esc(dn.isEmpty() ? str(it.get("id")) : dn)).append("  ·  ")
                                .append(Http.esc(str(it.get("id")))).append("</option>");
                    }
                    sb.append("</select>");
                }
                sb.append("<label class=\"form-label\">Quantité (1–64)</label>"
                        + "<input class=\"form-control\" type=\"number\" name=\"amount\" value=\"1\" min=\"1\" max=\"64\">");
                sb.append(confirmBox("Confirmer la remise de l'objet à « " + name + " »."));
                sb.append("<button class=\"btn btn-sm\" type=\"submit\">Donner</button></form>");
                latestForPlayer(agentId, "player.item.give", uuid).ifPresent(row ->
                        sb.append(resultLine("Dernier give", row)));
            } else {
                sb.append("<span class=\"badge text-bg-warning\">en ligne uniquement</span></p>");
                sb.append("<p class=\"muted npc-content-empty\">Indisponible : le joueur est hors ligne. "
                        + "Aucun mécanisme de livraison différée n'existe pour cette action.</p>");
            }
        }
        return sb.toString();
    }

    private String playerResetTools(Session session, String agentId, String uuid, String name) {
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">").append(Icons.icon("trash"))
                .append("Reset « nouveau joueur » <span class=\"badge text-bg-secondary\">hors ligne OK</span></p>");
        sb.append("<p class=\"muted\">L'aperçu ne modifie rien. La confirmation remet à zéro l'état RPGQuest "
                + "(quêtes, stories, variables/unlocks dont CLAIM_TIER_1, progression RPG, Waystones, cooldowns, "
                + "claim principal + objets RPGQuest de l'inventaire). Ne touche jamais le profil/UUID, les mondes, "
                + "les autres joueurs.</p>");
        sb.append(readForm(session, agentId, "player.resetnew.preview", "/players", uuid, "Aperçu (aucune écriture)"));
        latestForPlayer(agentId, "player.resetnew.preview", uuid).ifPresent(row -> {
            sb.append(resultLine("Aperçu", row));
            detailsOf(row).map(d -> asList(d.get("lines"))).ifPresent(lines -> {
                if (!lines.isEmpty()) {
                    sb.append(Ui.tableOpen("Catégorie", "Nombre", "Détail"));
                    for (Object o : lines) {
                        Map<String, Object> l = asMap(o);
                        sb.append("<tr><td>").append(Http.esc(str(l.get("label")))).append("</td><td>")
                                .append(Http.esc(str(l.get("count")))).append("</td><td class=\"muted\">")
                                .append(Http.esc(MiniText.prettifyTokens(str(l.get("detail"))))).append("</td></tr>");
                    }
                    sb.append(Ui.tableClose());
                }
            });
        });
        sb.append("<div class=\"danger-zone\"><div class=\"dz-title\">⚠ Action irréversible</div>");
        sb.append(formStart(session, agentId, "player.resetnew.confirm", "/players", uuid));
        sb.append(confirmBox("Je confirme la remise à zéro complète de l'état RPGQuest de « " + name + " »."));
        sb.append("<button class=\"btn btn-danger\" type=\"submit\">Reset « nouveau joueur »</button></form>");
        latestForPlayer(agentId, "player.resetnew.confirm", uuid).ifPresent(row ->
                sb.append(resultLine("Dernier reset", row)));
        sb.append("</div>");
        return sb.toString();
    }

    /** Bouton d'action en lecture seule : style discret (secondaire), pas de confirmation. */
    private String readForm(Session session, String agentId, String type, String page, String player, String label) {
        return "<form method=\"post\" action=\"/agents/action\" class=\"actform read\">"
                + "<input type=\"hidden\" name=\"_csrf\" value=\"" + Http.esc(session.csrfToken()) + "\">"
                + "<input type=\"hidden\" name=\"agent\" value=\"" + Http.esc(agentId) + "\">"
                + "<input type=\"hidden\" name=\"type\" value=\"" + Http.esc(type) + "\">"
                + "<input type=\"hidden\" name=\"return\" value=\"" + Http.esc(page) + "\">"
                + (player != null && !player.isEmpty()
                    ? "<input type=\"hidden\" name=\"player\" value=\"" + Http.esc(player) + "\">" : "")
                + "<button class=\"btn secondary\" type=\"submit\">" + Http.esc(label) + "</button></form>";
    }

    // ================================================================================
    //  Quêtes
    // ================================================================================

    public String quests(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder();
        boolean canEditQuests = perms.can(session.effective(), Permission.QUEST_CONTENT_WRITE);
        // Issue #194 : permission DÉDIÉE, distincte de l'écriture de contenu.
        boolean canDeleteContent = perms.can(session.effective(), Permission.CONTENT_DELETE);
        sb.append(Ui.pageHeader("quests", "Quêtes",
                "Catalogue des quêtes, état d'un joueur, et raccourcis d'administration "
                        + "(démarrer / compléter / réinitialiser).",
                (canEditQuests ? Ui.primaryLink("/quests/new", "plus", "Créer une quête") : "")
                        + docLink("quetes", "Documentation : quêtes / stories")));
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        String player = cleanPlayer(q.get("player"));
        sb.append(agentPicker(agentId, "/quests", player));

        sb.append("<h2>Catalogue</h2>");
        String questBar = compactRefresh(session, agentId, "quest.list", "Quêtes", "btn-outline-primary", "/quests");
        if (perms.can(session.effective(), Permission.NPC_READ)) {
            questBar += compactRefresh(session, agentId, "npc.list", "PNJ", "btn-outline-secondary", "/quests");
        }
        // Issue #196 : catalogue des objets de la version installée, utilisé par les icônes et les
        // récompenses de l'éditeur. En orange tant qu'il manque, parce que sans lui l'éditeur ne
        // propose qu'une courte liste de dépannage — et le disait mal.
        boolean itemsLoaded = itemCatalog(agentId).known();
        if (perms.can(session.effective(), Permission.CONTENT_READ)) {
            questBar += compactRefresh(session, agentId, "item.catalogs", "Objets Minecraft",
                    itemsLoaded ? "btn-outline-secondary" : "btn-warning", "/quests");
        }
        sb.append(listCatbar("Catalogue", questBar));
        if (!itemsLoaded) {
            sb.append(Ui.banner("warn", "Le catalogue des objets Minecraft n'est pas encore chargé : "
                    + "les listes <strong>Icône</strong> et <strong>Récompense d'objet</strong> de "
                    + "l'éditeur se limitent à une courte liste de dépannage (76 entrées, dont deux "
                    + "épées seulement). Cliquer sur <strong>« Objets Minecraft »</strong> pour "
                    + "récupérer la liste complète depuis le serveur."));
        }
        List<Object> runtimeQuests = latestDetails(agentId, "quest.list").map(d -> asList(d.get("quests"))).orElse(List.of());
        List<MergedRow> merged = mergeQuestRows(runtimeQuests);
        List<Object> rowData = merged.stream().map(m -> (Object) m.data()).toList();
        Map<String, String> questTitles = titleIndex(rowData, "id", "title");
        List<String> questIdList = rowData.stream().map(o -> str(asMap(o).get("id"))).toList();
        java.util.Set<String> knownQuestKeys = idKeySet(questIdList);
        java.util.Set<String> knownNpcKeys = npcKeySet(agentId);
        if (merged.isEmpty()) {
            sb.append(Ui.empty("Aucun catalogue chargé — aucune quête dans la source éditable ni dans le dernier "
                    + "relevé du serveur. Créer une quête, ou cliquer sur « Rafraîchir le catalogue »."));
        } else {
            if (sourceCatalog.available()) {
                sb.append(catalogOriginLegend("quête"));
            }
            // Quêtes et PNJ ensemble : une quête cite son donneur, donc recharger les quêtes
            // seules échouerait si le PNJ vient d'être créé.
            sb.append(reloadHint(session, agentId, merged, "quête", "npcs", "quests"));
            sb.append(listControls("quests", "Rechercher une quête\u2026",
                    filterBtn("", "Toutes", true) + filterBtn("ok", "Sans alerte", false)
                            + filterBtn("warn", "À vérifier", false)));
            sb.append("<p class=\"count-note\" data-count-note data-noun=\"qu\u00eate\">" + merged.size() + " qu\u00eate(s)</p>");
            sb.append("<div class=\"accordion npc-accordion\" id=\"quests-accordion\">");
            int qi = 0;
            for (MergedRow mr : merged) {
                sb.append(renderQuestAccordionItem(mr.data(), qi++, questTitles, knownQuestKeys, knownNpcKeys,
                        canEditQuests, canDeleteContent, mr.state()));
            }
            sb.append("</div>");
        }

        // Les actions admin s'ex\u00e9cutent contre le runtime : on ne propose que les qu\u00eates r\u00e9ellement
        // charg\u00e9es par le serveur (une qu\u00eate \u00ab source uniquement \u00bb \u00e9chouerait c\u00f4t\u00e9 agent).
        List<String> questIds = runtimeQuests.stream().map(o -> str(asMap(o).get("id"))).toList();
        sb.append(playerStatusSection(session, agentId, player, "/quests", "quest.player.status", "quests",
                this::renderQuestPlayerRow));

        if (!player.isEmpty()) {
            sb.append("<h3>Actions admin sur une quête</h3>");
            sb.append(questActionForm(session, agentId, player, "quest.start", "Démarrer", questIds, true));
            sb.append(questActionForm(session, agentId, player, "quest.complete", "Compléter (récompenses incluses)", questIds, false));
            sb.append(questActionForm(session, agentId, player, "quest.reset", "Réinitialiser (n'annule pas les récompenses déjà données)", questIds, false));
        }

        return sb.toString();
    }

    /**
     * Une ligne d'accordion de quête : en-tête = synthèse (titre humain, id technique, badges), corps
     * = sections repliées (Général / Donneur / Prérequis / Objectifs / Récompenses / Diagnostics /
     * Actions). Les diagnostics de référence (prérequis inconnu, donneur sans fiche) sont calculés
     * ici, côté panel, et rendus par {@link DiagnosticHelp}.
     */
    private String renderQuestAccordionItem(Map<String, Object> qd, int idx, Map<String, String> questTitles,
                                            java.util.Set<String> knownQuestKeys, java.util.Set<String> knownNpcKeys,
                                            boolean canEdit, boolean canDelete, CatalogState state) {
        String id = str(qd.get("id"));
        String title = str(qd.get("title"));
        String category = str(qd.get("category"));
        String giverId = str(qd.get("giverId"));
        String giverName = str(qd.get("giverName"));
        List<Object> prereq = asList(qd.get("prerequisites"));
        List<Object> steps = asList(qd.get("steps"));
        List<Object> rewardDetails = asList(qd.get("rewardDetails"));
        List<Object> rewards = asList(qd.get("rewards"));
        String slug = "q-" + idx + "-" + id.replaceAll("[^a-z0-9_-]", "-");
        String human = MiniText.plain(title);

        // ---- diagnostics de référence calculés côté panel : {code, détail} ----
        List<String[]> diags = new ArrayList<>();
        for (Object p : prereq) {
            String pid = str(p);
            if (!known(knownQuestKeys, pid)) {
                diags.add(new String[] {"QUEST_PREREQ_UNKNOWN", pid});
            }
        }
        if (!giverId.isEmpty() && knownNpcKeys != null
                && !knownNpcKeys.contains(giverId.toLowerCase(java.util.Locale.ROOT))) {
            diags.add(new String[] {"QUEST_GIVER_UNKNOWN", giverId});
        }
        boolean anyWarn = !diags.isEmpty();

        String ftext = Http.esc(id + " " + human + " " + category + " " + giverId + " " + giverName
                + " " + catalogStateKeywords(state));
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"accordion-item npc-item\" data-filter-item=\"quests\" data-filter-cat=\"")
                .append(anyWarn ? "warn" : "ok").append("\" data-filter-text=\"").append(ftext)
                .append("\" data-res-id=\"").append(Http.esc(id)).append("\">");
        sb.append("<h3 class=\"accordion-header\">");
        sb.append("<button class=\"accordion-button collapsed npc-head\" type=\"button\" data-bs-toggle=\"collapse\" "
                + "data-bs-target=\"#").append(slug).append("\" aria-expanded=\"false\" aria-controls=\"")
                .append(slug).append("\">");
        sb.append("<span class=\"npc-head-main\"><span class=\"npc-name\">").append(MiniText.html(title))
                .append("</span><code class=\"tid npc-id\">").append(Http.esc(id)).append("</code></span>");
        sb.append("<span class=\"npc-head-badges\">");
        if (!category.isEmpty()) {
            sb.append("<span class=\"badge text-bg-secondary\">")
                    .append(Http.esc(MiniText.prettifyId(category))).append("</span>");
        }
        if (!giverId.isEmpty()) {
            sb.append("<span class=\"badge text-bg-light text-dark\">Donneur</span>");
        }
        sb.append("<span class=\"badge text-bg-secondary\">").append(steps.size())
                .append(steps.size() > 1 ? " objectifs" : " objectif").append("</span>");
        int rc = !rewardDetails.isEmpty() ? rewardDetails.size() : rewards.size();
        if (rc > 0) {
            sb.append("<span class=\"badge text-bg-secondary\">").append(rc)
                    .append(rc > 1 ? " récompenses" : " récompense").append("</span>");
        }
        if (Boolean.TRUE.equals(qd.get("repeatable"))) {
            sb.append("<span class=\"badge text-bg-secondary\">répétable</span>");
        }
        sb.append(catalogStateBadge(state, "quête"));
        sb.append(anyWarn ? "<span class=\"badge text-bg-warning\">à vérifier</span>"
                : "<span class=\"badge text-bg-success\">OK</span>");
        sb.append("</span></button></h3>");

        sb.append("<div id=\"").append(slug).append("\" class=\"accordion-collapse collapse\" ")
                .append("data-bs-parent=\"#quests-accordion\"><div class=\"accordion-body npc-detail\">");
        sb.append(catalogStateNote(state, "quête"));

        // ---- GÉNÉRAL ----
        sb.append(detailSection("book", "Général"));
        sb.append("<dl class=\"npc-dl\">");
        dlRow(sb, "Titre", MiniText.html(title));
        dlRow(sb, "ID technique", Ui.id(id));
        if (!category.isEmpty()) {
            dlRow(sb, "Catégorie", Http.esc(MiniText.prettifyId(category))
                    + " <code class=\"tid\">" + Http.esc(category) + "</code>");
        }
        dlRow(sb, "Répétable", Boolean.TRUE.equals(qd.get("repeatable")) ? "oui" : "non");
        sb.append("</dl>");

        // ---- DONNEUR ----
        sb.append(detailSection("npc", "Donneur"));
        if (giverId.isEmpty()) {
            sb.append("<p class=\"muted npc-content-empty\">Aucun donneur de quête déclaré.</p>");
        } else {
            String label = !giverName.isEmpty() && !"null".equals(giverName)
                    ? MiniText.html(giverName) : Http.esc(MiniText.prettifyId(giverId));
            sb.append("<dl class=\"npc-dl\">");
            dlRow(sb, "PNJ", label + " " + Ui.id(giverId));
            sb.append("</dl>");
        }

        // ---- PRÉREQUIS ----
        if (!prereq.isEmpty()) {
            sb.append(detailSection("history", "Prérequis"));
            sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Quêtes requises</span> ")
                    .append(referencedQuests(prereq, questTitles)).append("</p>");
        }

        // ---- OBJECTIFS ----
        sb.append(detailSection("target", "Objectifs"));
        if (steps.isEmpty()) {
            sb.append("<p class=\"muted npc-content-empty\">Aucun objectif.</p>");
        } else {
            sb.append("<ul class=\"obj-list\">");
            for (Object stObj : steps) {
                Map<String, Object> st = asMap(stObj);
                String stepId = str(st.get("id"));
                List<Object> structured = asList(st.get("objectiveDetails"));
                if (!structured.isEmpty()) {
                    for (Object od : structured) {
                        ObjectiveText.Objective o = ObjectiveText.fromSummary(asMap(od));
                        sb.append("<li><span class=\"obj-text\">").append(Http.esc(o.label())).append("</span>");
                        if (o.hasTarget()) {
                            sb.append(Ui.rawValue(o.rawTarget()));
                        }
                        sb.append(Ui.id(stepId)).append("</li>");
                    }
                } else {
                    String objectives = MinecraftNames.humanizeTokens(join(asList(st.get("objectives"))));
                    sb.append("<li><span class=\"obj-text\">").append(Http.esc(objectives)).append("</span>")
                            .append(Ui.id(stepId)).append("</li>");
                }
            }
            sb.append("</ul>");
        }

        // ---- RÉCOMPENSES ----
        if (!rewardDetails.isEmpty() || !rewards.isEmpty()) {
            sb.append(detailSection("gift", "Récompenses"));
            sb.append("<ul class=\"reward-list\">");
            List<Object> source = !rewardDetails.isEmpty() ? rewardDetails : rewards;
            boolean structured = !rewardDetails.isEmpty();
            for (Object o : source) {
                RewardText.Reward rw = structured ? RewardText.fromSummary(asMap(o)) : RewardText.parse(str(o));
                sb.append("<li><span class=\"obj-text\">").append(Http.esc(rw.label())).append("</span>");
                if (rw.hasDetail()) {
                    sb.append(Ui.rawValue(rw.rawDetail()));
                }
                sb.append("</li>");
            }
            sb.append("</ul>");
        }

        // ---- DIAGNOSTICS ----
        sb.append(detailSection("warning", "Diagnostics"));
        if (diags.isEmpty()) {
            sb.append("<p class=\"muted npc-diag-ok\">").append(Icons.icon("check"))
                    .append("Aucune anomalie de référence détectée.</p>");
        } else {
            for (String[] dd : diags) {
                sb.append(DiagnosticHelp.render(dd[0], "warning", "", human, dd[1], ""));
            }
        }

        // ---- ACTIONS ----
        if (canEdit || canDelete) {
            String eslug = editSlug(id);
            if (!eslug.isEmpty()) {
                sb.append(detailSection("target", "Actions"));
                sb.append("<div class=\"npc-actions d-flex flex-wrap gap-2\">");
                if (canEdit) {
                    sb.append("<a class=\"btn btn-sm btn-outline-primary\" href=\"/quests/edit/")
                            .append(Http.esc(eslug)).append("\">").append(Icons.icon("edit"))
                            .append("Modifier la quête</a>");
                }
                if (canDelete) {
                    // Lien vers l'APERÇU, jamais une suppression directe : rien ne se supprime
                    // sans avoir vu les conséquences et retapé l'identifiant (#194).
                    sb.append("<a class=\"btn btn-sm btn-outline-danger\" href=\"/quests/delete?slug=")
                            .append(Http.esc(eslug)).append("\">").append(Icons.icon("trash"))
                            .append("Supprimer…</a>");
                }
                sb.append("</div>");
            }
        }

        sb.append("</div></div></div>");
        return sb.toString();
    }

    private String renderQuestPlayerRow(Map<String, Object> r) {
        StringBuilder sb = new StringBuilder();
        sb.append("<tr><td><div class=\"entity-name\" style=\"font-size:13.5px\">")
                .append(MiniText.html(str(r.get("title")))).append("</div>")
                .append(Ui.id(str(r.get("questId")))).append("</td>");
        sb.append("<td>").append(Ui.stateBadge(str(r.get("state")))).append("</td>");
        sb.append("<td class=\"muted\">");
        String step = str(r.get("currentStepId"));
        if (!step.isEmpty() && !"null".equals(step)) {
            List<Object> objectives = asList(r.get("objectives"));
            if (objectives.isEmpty()) {
                sb.append("étape ").append(Ui.id(step));
            }
            for (int i = 0; i < objectives.size(); i++) {
                Map<String, Object> ob = asMap(objectives.get(i));
                if (i > 0) {
                    sb.append("<br>");
                }
                sb.append(Http.esc(MinecraftNames.humanizeTokens(str(ob.get("description"))))).append(" — <strong>")
                        .append(Http.esc(str(ob.get("current")))).append("/").append(Http.esc(str(ob.get("required"))))
                        .append("</strong>");
            }
        } else {
            sb.append("—");
        }
        sb.append("</td></tr>");
        return sb.toString();
    }

    private String questActionForm(Session session, String agentId, String player, String type, String label,
                                   List<String> questIds, boolean withForce) {
        StringBuilder sb = new StringBuilder();
        sb.append(formStart(session, agentId, type, "/quests", player));
        sb.append("<label>Quête</label>").append(idSelect("quest_id", questIds, "rpgquest:crystal_hunt"));
        if (withForce) {
            sb.append("<label class=\"inline\"><input type=\"checkbox\" name=\"force\" value=\"true\"> ignorer les prérequis</label>");
        }
        if (AgentActionCatalog.spec(type).map(AgentActionCatalog.Spec::mutation).orElse(false)) {
            sb.append(confirmBox("Confirmer « " + label + " » pour " + player + "."));
        }
        sb.append("<button class=\"btn\" type=\"submit\">").append(Http.esc(label)).append("</button></form>");
        latestForPlayer(agentId, type, player).ifPresent(row -> sb.append(resultLine("Résultat", row)));
        return sb.toString();
    }

    // ================================================================================
    //  Stories
    // ================================================================================

    public String stories(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder();
        boolean canEditStories = perms.can(session.effective(), Permission.STORY_CONTENT_WRITE);
        boolean canDeleteStories = perms.can(session.effective(), Permission.CONTENT_DELETE);
        sb.append(Ui.pageHeader("stories", "Stories",
                "Suites ordonnées de quêtes. Avancer d'une étape ou compléter toute la story.",
                (canEditStories ? Ui.primaryLink("/stories/new", "plus", "Créer une story") : "")
                        + docLink("quetes", "Documentation : quêtes / stories")));
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        String player = cleanPlayer(q.get("player"));
        sb.append(agentPicker(agentId, "/stories", player));

        sb.append("<h2>Catalogue</h2>");
        String storyBar = compactRefresh(session, agentId, "story.list", "Stories", "btn-outline-primary", "/stories")
                + compactRefresh(session, agentId, "quest.list", "Quêtes", "btn-outline-secondary", "/stories");
        sb.append(listCatbar("Catalogue", storyBar));
        List<Object> runtimeStories = latestDetails(agentId, "story.list").map(d -> asList(d.get("stories"))).orElse(List.of());
        List<MergedRow> merged = mergeStoryRows(runtimeStories);
        // Titres humains des quêtes composant les stories, si un quest.list a déjà été chargé (données
        // locales du panel — aucun appel agent supplémentaire).
        List<Object> runtimeQuestDefs = latestDetails(agentId, "quest.list").map(d -> asList(d.get("quests"))).orElse(List.of());
        List<String> knownQuestIds = new ArrayList<>(runtimeQuestDefs.stream()
                .map(o -> str(asMap(o).get("id"))).filter(s -> !s.isEmpty()).toList());
        Map<String, String> questTitles = new LinkedHashMap<>(titleIndex(runtimeQuestDefs, "id", "title"));
        // Fusion des quêtes « source uniquement » : une quête tout juste enregistrée doit pouvoir
        // composer une story sans redémarrage Minecraft (issue #144). Aucun appel agent.
        for (SourceCatalog.QuestSource qs : sourceCatalog.quests()) {
            if (qs.plainId().isEmpty()) {
                continue;
            }
            String nsId = "rpgquest:" + qs.plainId();
            if (knownQuestIds.stream().noneMatch(k -> QuestYaml.plainId(k).equals(qs.plainId()))) {
                knownQuestIds.add(nsId);
            }
            String t = qs.draft().title == null || qs.draft().title.isBlank() ? qs.plainId() : qs.draft().title;
            questTitles.putIfAbsent(nsId, t);
        }
        java.util.Set<String> storyQuestKeys = knownQuestIds.isEmpty() ? null : idKeySet(knownQuestIds);
        if (merged.isEmpty()) {
            sb.append(Ui.empty("Aucun catalogue chargé — aucune story dans la source éditable ni dans le dernier "
                    + "relevé du serveur. Créer une story, ou cliquer sur « Stories »."));
        } else {
            if (sourceCatalog.available()) {
                sb.append(catalogOriginLegend("story"));
                // Une story cite des quêtes : les deux familles vont de pair.
                sb.append(reloadHint(session, agentId, merged, "story", "quests", "stories"));
            }
            sb.append(listControls("stories", "Rechercher une story\u2026",
                    filterBtn("", "Toutes", true) + filterBtn("ok", "Sans alerte", false)
                            + filterBtn("warn", "À vérifier", false)));
            sb.append("<p class=\"count-note\" data-count-note data-noun=\"story\">" + merged.size() + " story(s)</p>");
            sb.append("<div class=\"accordion npc-accordion\" id=\"stories-accordion\">");
            int si = 0;
            for (MergedRow mr : merged) {
                sb.append(renderStoryAccordionItem(mr.data(), si++, questTitles, storyQuestKeys,
                    canEditStories, canDeleteStories, mr.state()));
            }
            sb.append("</div>");
        }

        List<String> storyIds = runtimeStories.stream().map(o -> str(asMap(o).get("id"))).toList();
        sb.append(playerStatusSection(session, agentId, player, "/stories", "story.player.status", "stories",
                this::renderStoryPlayerRow));

        if (!player.isEmpty()) {
            sb.append("<h3>Actions admin sur une story</h3>");
            sb.append(storyActionForm(session, agentId, player, "story.advance", "Avancer d'une étape", storyIds));
            sb.append(storyActionForm(session, agentId, player, "story.complete", "Compléter toute la story", storyIds));
        }

        return sb.toString();
    }

    /**
     * Une ligne d'accordion de story : en-tête = synthèse (titre humain, id technique, nombre
     * d'étapes, état), corps = sections repliées (Identité / Chaîne de quêtes / Diagnostics /
     * Actions). Le diagnostic « quête inconnue dans la chaîne » est calculé côté panel dès qu'un
     * {@code quest.list} a été chargé.
     */
    private String renderStoryAccordionItem(Map<String, Object> sd, int idx, Map<String, String> questTitles,
                                            java.util.Set<String> storyQuestKeys, boolean canEdit,
                                            boolean canDelete, CatalogState state) {
        String id = str(sd.get("id"));
        String title = str(sd.get("title"));
        List<Object> steps = asList(sd.get("stepQuestIds"));
        String slug = "s-" + idx + "-" + id.replaceAll("[^a-z0-9_-]", "-");
        String human = MiniText.plain(title);

        List<String> unknownSteps = new ArrayList<>();
        if (storyQuestKeys != null) {
            for (Object qid : steps) {
                String sid = str(qid);
                if (!known(storyQuestKeys, sid)) {
                    unknownSteps.add(sid);
                }
            }
        }
        boolean anyWarn = !unknownSteps.isEmpty();

        String ftext = Http.esc(id + " " + human + " " + catalogStateKeywords(state));
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"accordion-item npc-item\" data-filter-item=\"stories\" data-filter-cat=\"")
                .append(anyWarn ? "warn" : "ok").append("\" data-filter-text=\"").append(ftext)
                .append("\" data-res-id=\"").append(Http.esc(id)).append("\">");
        sb.append("<h3 class=\"accordion-header\">");
        sb.append("<button class=\"accordion-button collapsed npc-head\" type=\"button\" data-bs-toggle=\"collapse\" "
                + "data-bs-target=\"#").append(slug).append("\" aria-expanded=\"false\" aria-controls=\"")
                .append(slug).append("\">");
        sb.append("<span class=\"npc-head-main\"><span class=\"npc-name\">").append(MiniText.html(title))
                .append("</span><code class=\"tid npc-id\">").append(Http.esc(id)).append("</code></span>");
        sb.append("<span class=\"npc-head-badges\">");
        sb.append("<span class=\"badge text-bg-secondary\">").append(steps.size())
                .append(steps.size() > 1 ? " quêtes" : " quête").append("</span>");
        sb.append(catalogStateBadge(state, "story"));
        sb.append(anyWarn ? "<span class=\"badge text-bg-warning\">à vérifier</span>"
                : "<span class=\"badge text-bg-success\">OK</span>");
        sb.append("</span></button></h3>");

        sb.append("<div id=\"").append(slug).append("\" class=\"accordion-collapse collapse\" ")
                .append("data-bs-parent=\"#stories-accordion\"><div class=\"accordion-body npc-detail\">");
        sb.append(catalogStateNote(state, "story"));

        // ---- IDENTITÉ ----
        sb.append(detailSection("book", "Identité"));
        sb.append("<dl class=\"npc-dl\">");
        dlRow(sb, "Titre", MiniText.html(title));
        dlRow(sb, "ID technique", Ui.id(id));
        dlRow(sb, "Étapes", String.valueOf(steps.size()));
        sb.append("</dl>");

        // ---- CHAÎNE DE QUÊTES ----
        sb.append(detailSection("target", "Chaîne de quêtes"));
        if (steps.isEmpty()) {
            sb.append("<p class=\"muted npc-content-empty\">Aucune quête dans la chaîne.</p>");
        } else {
            sb.append("<ol class=\"step-list\">");
            int n = 1;
            for (Object qid : steps) {
                String qidStr = str(qid);
                String qtitle = questTitles.get(qidStr);
                boolean unknown = unknownSteps.contains(qidStr);
                sb.append("<li><span class=\"step-n\">").append(n++).append("</span>")
                        .append("<span class=\"obj-text\">")
                        .append(qtitle != null ? MiniText.html(qtitle) : Http.esc(MiniText.prettifyId(qidStr)))
                        .append("</span>").append(Ui.id(qidStr));
                if (unknown) {
                    sb.append(" <span class=\"badge text-bg-warning\">inconnue</span>");
                }
                sb.append("</li>");
            }
            sb.append("</ol>");
        }

        // ---- DIAGNOSTICS ----
        sb.append(detailSection("warning", "Diagnostics"));
        if (!anyWarn) {
            sb.append("<p class=\"muted npc-diag-ok\">").append(Icons.icon("check"))
                    .append(storyQuestKeys == null
                            ? "Chaîne non vérifiée (charger le catalogue de quêtes pour contrôler les références)."
                            : "Aucune anomalie de référence détectée.").append("</p>");
        } else {
            for (String sid : unknownSteps) {
                sb.append(DiagnosticHelp.render("STORY_QUEST_UNKNOWN", "warning", "", human, sid, ""));
            }
        }

        // ---- ACTIONS ----
        if (canEdit || canDelete) {
            String eslug = editSlug(id);
            if (!eslug.isEmpty()) {
                sb.append(detailSection("target", "Actions"));
                sb.append("<div class=\"npc-actions d-flex flex-wrap gap-2\">");
                if (canEdit) {
                    sb.append("<a class=\"btn btn-sm btn-outline-primary\" href=\"/stories/edit/")
                            .append(Http.esc(eslug)).append("\">").append(Icons.icon("edit"))
                            .append("Modifier la story</a>");
                }
                if (canDelete) {
                    sb.append("<a class=\"btn btn-sm btn-outline-danger\" href=\"/stories/delete?slug=")
                            .append(Http.esc(eslug)).append("\">").append(Icons.icon("trash"))
                            .append("Supprimer…</a>");
                }
                sb.append("</div>");
            }
        }

        sb.append("</div></div></div>");
        return sb.toString();
    }

    private String renderStoryPlayerRow(Map<String, Object> r) {
        return "<tr><td><div class=\"entity-name\" style=\"font-size:13.5px\">"
                + MiniText.html(str(r.get("title"))) + "</div>" + Ui.id(str(r.get("storyId")))
                + "</td><td>" + Ui.stateBadge(str(r.get("state")))
                + "</td><td class=\"muted\">étape <strong>" + Http.esc(str(r.get("currentStep"))) + "/"
                + Http.esc(str(r.get("totalSteps"))) + "</strong><br>"
                + questRef(str(r.get("currentQuestId")), null) + "</td></tr>";
    }

    private String storyActionForm(Session session, String agentId, String player, String type, String label,
                                   List<String> storyIds) {
        StringBuilder sb = new StringBuilder();
        sb.append(formStart(session, agentId, type, "/stories", player));
        sb.append("<label>Story</label>").append(idSelect("story_id", storyIds, "main_story"));
        sb.append(confirmBox("Confirmer « " + label + " » pour " + player + "."));
        sb.append("<button class=\"btn\" type=\"submit\">").append(Http.esc(label)).append("</button></form>");
        latestForPlayer(agentId, type, player).ifPresent(row -> sb.append(resultLine("Résultat", row)));
        return sb.toString();
    }

    // ================================================================================
    //  PNJ (V2 déclarative — définition logique vs binding Citizens ; #66 / #75)
    // ================================================================================

    private static final int TRAVEL_PAGE_SIZE = 20;

    /**
     * Consultation lecture seule waypoints/bornes (issue #152) — réutilise {@code travel.catalog}
     * (lui-même des données déjà persistées/indexées côté plugin, jamais un scan monde). Aucun
     * éditeur ici (création/déplacement/désactivation restent une évolution ultérieure) : seule la
     * consultation, comme demandé explicitement par le ticket.
     *
     * <p><strong>Distinction volontaire</strong> : une ligne listée ici est une structure
     * <em>enregistrée</em> en base au moment du dernier relevé de l'agent — jamais une confirmation
     * qu'elle est physiquement présente/accessible dans le monde à l'instant où l'admin regarde
     * cette page (voir {@code /rpgadmin travel diagnose} côté serveur pour une vérification
     * d'accessibilité réelle, issue #153).</p>
     */
    public String travel(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("travel", "Réseau de voyage",
                "Waypoints et bornes réellement persistés par le serveur. Une ligne listée ici est "
                        + "une structure enregistrée en base au dernier relevé — pas une confirmation qu'elle "
                        + "est physiquement présente/accessible dans le monde en ce moment.", ""));
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();

        sb.append(agentPicker(agentId, "/travel", ""));
        sb.append("<div class=\"npc-catbar\"><span class=\"npc-catbar-t\">Catalogue</span>");
        sb.append(compactRefresh(session, agentId, "travel.catalog", "Rafraîchir", "btn-outline-primary", "/travel"));
        sb.append("</div>");

        Optional<Map<String, Object>> latest = latestDetails(agentId, "travel.catalog");
        if (latest.isEmpty()) {
            sb.append(Ui.empty("travel", "Aucun catalogue chargé — cliquer sur « Rafraîchir »."));
            return sb.toString();
        }
        Map<String, Object> d = latest.get();
        List<Object> waypoints = asList(d.get("waypoints"));
        List<Object> beacons = asList(d.get("beacons"));
        List<Object> unpaired = asList(d.get("unpairedHubWaypointIds"));
        long generatedAt = d.get("generatedAtEpochMs") instanceof Number n ? n.longValue() : 0L;

        sb.append("<p class=\"npc-summary\">")
                .append(Http.esc(str(d.get("waypointCount")))).append(" waypoint(s) · ")
                .append(Http.esc(str(d.get("beaconCount")))).append(" borne(s) (")
                .append(Http.esc(str(d.get("autoGeneratedBeaconCount")))).append(" auto-générée(s)) · ")
                .append(unpaired.size()).append(" waypoint(s) du Hub sans borne appariée</p>");
        sb.append("<p class=\"muted\">Donnée relevée ").append(Http.esc(freshnessLabel(generatedAt))).append(".</p>");

        String query = q.getOrDefault("q", "").trim().toLowerCase(java.util.Locale.ROOT);
        String worldFilter = q.getOrDefault("world", "").trim();

        java.util.LinkedHashSet<String> worlds = new java.util.LinkedHashSet<>();
        for (Object o : waypoints) {
            worlds.add(str(asMap(o).get("world")));
        }
        for (Object o : beacons) {
            worlds.add(str(asMap(o).get("world")));
        }
        sb.append(travelSearchAndWorldBar(agentId, query, worldFilter, worlds));

        List<Object> filteredWaypoints = filterTravelRows(waypoints, query, worldFilter);
        List<Object> filteredBeacons = filterTravelRows(beacons, query, worldFilter);

        sb.append(Ui.sectionTitle("travel", "Waypoints (" + filteredWaypoints.size() + "/" + waypoints.size() + ")"));
        sb.append(renderWaypointTable(agentId, query, worldFilter, filteredWaypoints, parsePageParam(q.get("wp"))));

        sb.append(Ui.sectionTitle("travel", "Bornes (" + filteredBeacons.size() + "/" + beacons.size() + ")"));
        sb.append(renderBeaconTable(agentId, query, worldFilter, filteredBeacons, parsePageParam(q.get("bp"))));

        return sb.toString();
    }

    private static String freshnessLabel(long epochMs) {
        if (epochMs <= 0) {
            return "à une date inconnue";
        }
        long ageSeconds = Math.max(0, (System.currentTimeMillis() - epochMs) / 1000);
        if (ageSeconds < 60) {
            return "il y a " + ageSeconds + " s";
        }
        if (ageSeconds < 3600) {
            return "il y a " + (ageSeconds / 60) + " min";
        }
        return "il y a " + (ageSeconds / 3600) + " h";
    }

    private String travelSearchAndWorldBar(String agentId, String query, String worldFilter, Set<String> worlds) {
        StringBuilder sb = new StringBuilder("<form method=\"get\" action=\"/travel\" class=\"npc-searchbar\">");
        sb.append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId)).append("\">");
        sb.append("<input type=\"search\" name=\"q\" value=\"").append(Http.esc(query))
                .append("\" placeholder=\"Rechercher (id, nom, biome)\" class=\"form-control form-control-sm\">");
        sb.append("<select name=\"world\" class=\"form-select form-select-sm\" onchange=\"this.form.submit()\">");
        sb.append("<option value=\"\"").append(worldFilter.isEmpty() ? " selected" : "").append(">Tous les mondes</option>");
        for (String w : worlds) {
            sb.append("<option value=\"").append(Http.esc(w)).append("\"")
                    .append(w.equals(worldFilter) ? " selected" : "").append(">").append(Http.esc(w)).append("</option>");
        }
        sb.append("</select>");
        sb.append("<button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\">")
                .append(Icons.icon("search")).append("Filtrer</button></form>");
        return sb.toString();
    }

    private List<Object> filterTravelRows(List<Object> rows, String query, String worldFilter) {
        if (query.isEmpty() && worldFilter.isEmpty()) {
            return rows;
        }
        List<Object> out = new ArrayList<>();
        for (Object o : rows) {
            Map<String, Object> m = asMap(o);
            if (!worldFilter.isEmpty() && !worldFilter.equals(str(m.get("world")))) {
                continue;
            }
            if (!query.isEmpty()) {
                String haystack = (str(m.get("id")) + " " + str(m.get("displayName")) + " " + str(m.get("biomeKey"))
                        + " " + str(m.get("biomeInstance")) + " " + str(m.get("world"))).toLowerCase(java.util.Locale.ROOT);
                if (!haystack.contains(query)) {
                    continue;
                }
            }
            out.add(o);
        }
        return out;
    }

    private String renderWaypointTable(String agentId, String query, String worldFilter, List<Object> rows, int page) {
        if (rows.isEmpty()) {
            return Ui.empty("travel", "Aucun waypoint ne correspond.");
        }
        int pageCount = Math.max(1, (int) Math.ceil(rows.size() / (double) TRAVEL_PAGE_SIZE));
        int clamped = Math.max(1, Math.min(page, pageCount));
        List<Object> pageItems = rows.subList(Math.min(rows.size(), (clamped - 1) * TRAVEL_PAGE_SIZE),
                Math.min(rows.size(), clamped * TRAVEL_PAGE_SIZE));

        StringBuilder sb = new StringBuilder();
        // Retour joueur 2026-10-04 : « Apparié » était incompréhensible et pouvait se confondre
        // avec un statut de découverte joueur -- ce n'en est pas un. « Borne associée » montre
        // maintenant LAQUELLE est associée (son id), jamais seulement oui/non.
        sb.append("<p class=\"muted\">« Borne associée » = la borne liée à ce waypoint pour le "
                + "voyage retour, pas une découverte par un joueur.</p>");
        sb.append(Ui.tableOpen("ID", "Nom", "Monde", "Biome", "Instance", "X", "Y", "Z", "Actif", "Borne associée"));
        for (Object o : pageItems) {
            Map<String, Object> m = asMap(o);
            String pairedBeaconId = str(m.get("pairedBeaconId"));
            boolean paired = !pairedBeaconId.isEmpty() && !"null".equals(pairedBeaconId);
            sb.append("<tr><td><code>").append(Http.esc(str(m.get("id")))).append("</code></td><td>")
                    .append(Http.esc(str(m.get("displayName")))).append("</td><td>")
                    .append(Http.esc(str(m.get("world")))).append("</td><td class=\"muted\">")
                    .append(Http.esc(str(m.get("biomeKey")))).append("</td><td class=\"muted\">")
                    .append(Http.esc(str(m.get("biomeInstance")))).append("</td><td>")
                    .append(Http.esc(str(m.get("x")))).append("</td><td>")
                    .append(Http.esc(str(m.get("y")))).append("</td><td>")
                    .append(Http.esc(str(m.get("z")))).append("</td><td>")
                    .append(Boolean.TRUE.equals(m.get("active")) ? "oui" : "non").append("</td><td>")
                    .append(paired ? "<code>" + Http.esc(pairedBeaconId) + "</code>"
                            : "<span class=\"badge text-bg-warning\">aucune</span>")
                    .append("</td></tr>");
        }
        sb.append(Ui.tableClose());
        sb.append(travelPager(agentId, query, worldFilter, "wp", clamped, pageCount));
        return sb.toString();
    }

    private String renderBeaconTable(String agentId, String query, String worldFilter, List<Object> rows, int page) {
        if (rows.isEmpty()) {
            return Ui.empty("travel", "Aucune borne ne correspond.");
        }
        int pageCount = Math.max(1, (int) Math.ceil(rows.size() / (double) TRAVEL_PAGE_SIZE));
        int clamped = Math.max(1, Math.min(page, pageCount));
        List<Object> pageItems = rows.subList(Math.min(rows.size(), (clamped - 1) * TRAVEL_PAGE_SIZE),
                Math.min(rows.size(), clamped * TRAVEL_PAGE_SIZE));

        StringBuilder sb = new StringBuilder();
        sb.append("<p class=\"muted\">« Waypoint associé » = le waypoint lié à cette borne, pas une "
                + "découverte par un joueur.</p>");
        sb.append(Ui.tableOpen("ID", "Monde", "X", "Y", "Z", "Actif", "Origine", "Waypoint associé"));
        for (Object o : pageItems) {
            Map<String, Object> m = asMap(o);
            boolean auto = Boolean.TRUE.equals(m.get("autoGenerated"));
            String pairedWaypoint = str(m.get("pairedWaypointId"));
            sb.append("<tr><td><code>").append(Http.esc(str(m.get("id")))).append("</code></td><td>")
                    .append(Http.esc(str(m.get("world")))).append("</td><td>")
                    .append(Http.esc(str(m.get("x")))).append("</td><td>")
                    .append(Http.esc(str(m.get("y")))).append("</td><td>")
                    .append(Http.esc(str(m.get("z")))).append("</td><td>")
                    .append(Boolean.TRUE.equals(m.get("active")) ? "oui" : "non").append("</td><td>")
                    .append(auto ? "<span class=\"badge text-bg-info\">auto (Hub)</span>"
                            : "<span class=\"badge text-bg-secondary\">administrée</span>")
                    .append("</td><td>")
                    .append(pairedWaypoint.isEmpty() || "null".equals(pairedWaypoint)
                            ? "<span class=\"badge text-bg-warning\">aucun</span>" : "<code>" + Http.esc(pairedWaypoint) + "</code>")
                    .append("</td></tr>");
        }
        sb.append(Ui.tableClose());
        sb.append(travelPager(agentId, query, worldFilter, "bp", clamped, pageCount));
        return sb.toString();
    }

    private String travelPager(String agentId, String query, String worldFilter, String pageParam, int page, int pageCount) {
        if (pageCount <= 1) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<nav class=\"pager\">");
        String base = "/travel?agent=" + Http.esc(agentId)
                + (query.isEmpty() ? "" : "&q=" + Http.esc(query))
                + (worldFilter.isEmpty() ? "" : "&world=" + Http.esc(worldFilter));
        if (page > 1) {
            sb.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"").append(base)
                    .append("&").append(pageParam).append("=").append(page - 1).append("\">Précédent</a>");
        }
        sb.append("<span class=\"muted\"> Page ").append(page).append(" / ").append(pageCount).append(" </span>");
        if (page < pageCount) {
            sb.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"").append(base)
                    .append("&").append(pageParam).append("=").append(page + 1).append("\">Suivant</a>");
        }
        sb.append("</nav>");
        return sb.toString();
    }

    public String npcs(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("npc", "PNJ",
                "Cliquez sur un PNJ pour voir son détail. Une définition logique (npcs/<id>.yml) "
                        + "peut exister avant que le PNJ ne soit tagué en jeu.",
                docLink("pnj-citizens", "Documentation : créer et configurer un PNJ")));
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        boolean canWrite = perms.can(session.effective(), Permission.NPC_WRITE);
        boolean canSetGiver = perms.can(session.effective(), Permission.QUEST_GIVER_WRITE);
        boolean canLink = perms.can(session.effective(), Permission.NPC_BIND_WRITE);
        boolean canSpawn = perms.can(session.effective(), Permission.NPC_SPAWN_WRITE);
        boolean canWriteDialogue = perms.can(session.effective(), Permission.DIALOGUE_WRITE);
        List<String> spawnWorlds = loadedWorldNames(agentId);
        sb.append(agentPicker(agentId, "/npcs", ""));

        // ---- Toolbar catalogue compacte -------------------------------------------------
        sb.append("<div class=\"npc-catbar\">");
        sb.append("<span class=\"npc-catbar-t\">Catalogue</span>");
        sb.append(compactRefresh(session, agentId, "npc.list", "Catalogue RPGQuest", "btn-outline-primary"));
        sb.append(compactRefresh(session, agentId, "npc.citizens.list", "Citizens", "btn-outline-secondary"));
        if (canWrite) {
            sb.append("<button class=\"btn btn-sm btn-primary\" type=\"button\" data-bs-toggle=\"collapse\" "
                    + "data-bs-target=\"#npc-new-def\" aria-expanded=\"false\" aria-controls=\"npc-new-def\">")
                    .append(Icons.icon("plus")).append("Nouvelle définition PNJ</button>");
        }
        sb.append("</div>");
        if (canWrite) {
            sb.append("<div class=\"collapse\" id=\"npc-new-def\"><div class=\"card card-body npc-formcard\">");
            sb.append(npcDefForm(session, agentId, "create", "", "", "", "", true,
                    dialogueSelectOptions(agentId), "npc-new-def"));
            sb.append("</div></div>");
        }

        Map<String, String> questTitles = titleIndex(
                latestDetails(agentId, "quest.list").map(x -> asList(x.get("quests"))).orElse(List.of()), "id", "title");
        List<String> questIds = latestDetails(agentId, "quest.list").map(x -> asList(x.get("quests"))).orElse(List.of())
                .stream().map(o -> str(asMap(o).get("id"))).filter(s -> !s.isEmpty()).toList();
        // Issue #164 : catalogue FUSIONNÉ source + runtime pour « Attribuer une quête ». Le relevé
        // runtime seul masquait toute quête fraîchement enregistrée dans la source, d'où la quête
        // « introuvable » signalée. Construit une seule fois pour toute la page.
        com.lodygames.rpgquest.panel.content.RefData giverRef = referenceData(agentId);
        List<String[]> dialogueOptions = dialogueSelectOptions(agentId);

        Optional<Map<String, Object>> citizensCat = latestDetails(agentId, "npc.citizens.list");
        List<Object> citizensRoster = citizensCat.map(x -> asList(x.get("citizens"))).orElse(List.of());

        // #101 : un PNJ Citizens réel qui n'a NI fiche RPGQuest NI liaison n'apparaît dans aucune
        // ligne de npc.list — ce dernier ne connaît que les définitions, les liaisons et les
        // références de contenu. On le raccroche ici depuis le registre Citizens pour qu'il soit
        // visible, cherchable et rattachable ; jamais masqué au seul motif qu'il n'est pas (encore)
        // intégré à RPGQuest. Clé d'identité = id numérique Citizens, jamais le nom affiché.
        List<Map<String, Object>> freeCitizens = new ArrayList<>();
        for (Object o : citizensRoster) {
            Map<String, Object> c = asMap(o);
            String linked = str(c.get("linkedNpcId"));
            if (linked.isEmpty() || "null".equals(linked)) {
                freeCitizens.add(c);
            }
        }

        Optional<Map<String, Object>> details = latestDetails(agentId, "npc.list");
        if (details.isEmpty()) {
            sb.append(Ui.empty("npc", "Aucun catalogue chargé — cliquer sur « Catalogue RPGQuest »."));
            return sb.toString();
        }
        Map<String, Object> d = details.get();
        List<Object> npcs = asList(d.get("npcs"));

        if (!Boolean.TRUE.equals(d.get("citizensAvailable"))) {
            sb.append("<div class=\"alert alert-info npc-alert\">").append(Icons.icon("info"))
                    .append("<div>Citizens est inactif sur le serveur cible : les bindings ne peuvent pas être "
                            + "vérifiés (les définitions logiques restent gérables).</div></div>");
        }

        // ---- Synthèse compacte (une ligne) --------------------------------------------
        sb.append("<p class=\"npc-summary\">")
                .append(Http.esc(str(d.get("total")))).append(" PNJ · ")
                .append(Http.esc(str(d.get("withDefinition")))).append(" avec définition · ")
                .append(Http.esc(str(d.get("withoutDefinition")))).append(" sans · ")
                .append(Http.esc(str(d.get("bound")))).append(" liés · ")
                .append(Http.esc(str(d.get("withWarnings")))).append(" avec alerte");
        if (citizensCat.isPresent()) {
            sb.append(" <span class=\"faint\">| Citizens : ")
                    .append(Http.esc(str(citizensCat.get().get("total")))).append(" · ")
                    .append(Http.esc(str(citizensCat.get().get("available")))).append(" libre(s) · ")
                    .append(Http.esc(str(citizensCat.get().get("linked")))).append(" lié(s)</span>");
        }
        sb.append("</p>");

        if (npcs.isEmpty() && freeCitizens.isEmpty()) {
            sb.append(Ui.empty("npc", "Aucun PNJ : ni fiche RPGQuest (définition, liaison, référence), "
                    + "ni PNJ Citizens dans le jeu."));
            return sb.toString();
        }

        // Fiches RPGQuest prêtes mais non liées : cibles proposées quand on veut rattacher un PNJ
        // Citizens libre à une fiche existante (liaison inverse, #101).
        List<String> definedUnlinkedIds = new ArrayList<>();
        for (Object o : npcs) {
            Map<String, Object> n = asMap(o);
            if (Boolean.TRUE.equals(n.get("logicalDefinitionPresent"))
                    && !Boolean.TRUE.equals(n.get("citizensBindingPresent"))
                    && Boolean.TRUE.equals(n.get("enabled"))) {
                definedUnlinkedIds.add(str(n.get("id")));
            }
        }

        // ---- Recherche + filtres (input-group Bootstrap) -----------------------------
        sb.append("<div class=\"npc-controls\">");
        sb.append("<div class=\"input-group npc-search\"><span class=\"input-group-text\">")
                .append(Icons.icon("search")).append("</span>")
                .append("<input type=\"search\" class=\"form-control\" data-filter-input=\"npcs\" "
                        + "placeholder=\"Rechercher un PNJ…\" aria-label=\"Rechercher un PNJ\"></div>");
        sb.append("<div class=\"npc-filters\" data-filter-chips=\"npcs\">")
                .append(filterBtn("", "Tous", true))
                .append(filterBtn("linked", "Liés", false))
                .append(filterBtn("unlinked", "Non liés", false))
                .append(filterBtn("warn", "Warnings", false))
                .append(filterBtn("err", "Erreurs", false))
                .append("</div>");
        sb.append("</div>");
        sb.append("<p class=\"count-note\" data-count-note data-noun=\"PNJ\">")
                .append(npcs.size() + freeCitizens.size()).append(" PNJ</p>");

        // ---- Liste = accordion (un seul PNJ ouvert à la fois) ----------------------
        sb.append("<div class=\"accordion npc-accordion\" id=\"npc-accordion\">");
        int i = 0;
        for (Object o : npcs) {
            sb.append(renderNpcAccordionItem(session, agentId, asMap(o), i++, questTitles, questIds, giverRef,
                    dialogueOptions, citizensRoster, spawnWorlds, canWrite, canSetGiver, canLink, canSpawn,
                    canWriteDialogue));
        }
        // #101 : PNJ Citizens présents en jeu mais sans fiche RPGQuest ni liaison.
        int fci = 0;
        for (Map<String, Object> c : freeCitizens) {
            sb.append(renderFreeCitizensAccordionItem(session, agentId, c, fci++, dialogueOptions,
                    definedUnlinkedIds, canWrite, canLink));
        }
        sb.append("</div>");

        // ---- Registre canonique (repli discret) -----------------------------------
        List<Object> definedIds = asList(d.get("definedIds"));
        List<Object> canonical = asList(d.get("canonicalIds"));
        sb.append("<details class=\"npc-registry\"><summary class=\"muted\">Registre canonique — ")
                .append(definedIds.size()).append(" définition(s), ").append(canonical.size())
                .append(" id(s) référencé(s)</summary>");
        sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Définis</span> ");
        if (definedIds.isEmpty()) {
            sb.append("<span class=\"muted\">aucun</span>");
        } else {
            for (Object c : definedIds) {
                sb.append(Ui.id(str(c))).append(' ');
            }
        }
        sb.append("</p><p class=\"meta-line\"><span class=\"meta-k\">Tous</span> ");
        for (Object c : canonical) {
            sb.append(Ui.id(str(c))).append(' ');
        }
        sb.append("</p></details>");

        return sb.toString();
    }

    /** Petit formulaire « rafraîchir » (bouton Bootstrap compact, plus de grande carte vide). */
    private String compactRefresh(Session session, String agentId, String type, String label, String btnClass) {
        return compactRefresh(session, agentId, type, label, btnClass, "/npcs");
    }

    /** Idem, en précisant la page de retour (toolbars compactes de /quests, /stories, /dialogues…). */
    private String compactRefresh(Session session, String agentId, String type, String label, String btnClass,
                                  String returnPath) {
        return "<form method=\"post\" action=\"/agents/action\" class=\"d-inline\">"
                + "<input type=\"hidden\" name=\"_csrf\" value=\"" + Http.esc(session.csrfToken()) + "\">"
                + "<input type=\"hidden\" name=\"agent\" value=\"" + Http.esc(agentId) + "\">"
                + "<input type=\"hidden\" name=\"type\" value=\"" + Http.esc(type) + "\">"
                + "<input type=\"hidden\" name=\"return\" value=\"" + Http.esc(returnPath) + "\">"
                + "<button class=\"btn btn-sm " + btnClass + "\" type=\"submit\">"
                + Icons.icon("refresh") + Http.esc(label) + "</button></form>";
    }

    /** Toolbar « catalogue » compacte, partagée : libellé discret + boutons de rafraîchissement. */
    private static String listCatbar(String title, String buttonsHtml) {
        return "<div class=\"npc-catbar\"><span class=\"npc-catbar-t\">" + Http.esc(title) + "</span>"
                + buttonsHtml + "</div>";
    }

    /**
     * Recherche (input-group Bootstrap) + puces de filtre alignées — même markup que /npcs, réutilisé
     * pour /quests, /stories, /dialogues. Le champ pilote {@code data-filter-input="<scope>"} ; les
     * cartes portent {@code data-filter-item="<scope>"} et {@code data-filter-cat}.
     */
    private static String listControls(String scope, String placeholder, String chipsHtml) {
        StringBuilder sb = new StringBuilder("<div class=\"npc-controls\">");
        sb.append("<div class=\"input-group npc-search\"><span class=\"input-group-text\">")
                .append(Icons.icon("search")).append("</span>")
                .append("<input type=\"search\" class=\"form-control\" data-filter-input=\"").append(Http.esc(scope))
                .append("\" placeholder=\"").append(Http.esc(placeholder))
                .append("\" aria-label=\"").append(Http.esc(placeholder)).append("\"></div>");
        if (chipsHtml != null && !chipsHtml.isBlank()) {
            sb.append("<div class=\"npc-filters\" data-filter-chips=\"").append(Http.esc(scope)).append("\">")
                    .append(chipsHtml).append("</div>");
        }
        return sb.append("</div>").toString();
    }

    /** Ensemble d'identifiants normalisés (minuscule, avec ET sans préfixe {@code rpgquest:}). */
    private static java.util.Set<String> idKeySet(List<String> ids) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String raw : ids) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String low = raw.toLowerCase(Locale.ROOT);
            out.add(low);
            int c = low.indexOf(':');
            out.add(c >= 0 ? low.substring(c + 1) : "rpgquest:" + low);
        }
        return out;
    }

    /** {@code true} si la référence est vide (rien à vérifier) ou présente dans l'ensemble connu. */
    private static boolean known(java.util.Set<String> keys, String ref) {
        if (ref == null || ref.isBlank() || "null".equals(ref)) {
            return true;
        }
        String low = ref.toLowerCase(Locale.ROOT);
        if (keys.contains(low)) {
            return true;
        }
        int c = low.indexOf(':');
        return keys.contains(c >= 0 ? low.substring(c + 1) : "rpgquest:" + low);
    }

    /**
     * Identifiants de PNJ connus (définitions + ids canoniques référencés) d'après le dernier
     * {@code npc.list}. {@code null} si {@code npc.list} n'a jamais été chargé : on ne peut alors
     * pas conclure qu'un donneur est inconnu.
     */
    private java.util.Set<String> npcKeySet(String agentId) {
        Optional<Map<String, Object>> d = latestDetails(agentId, "npc.list");
        if (d.isEmpty()) {
            return null;
        }
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Object o : asList(d.get().get("definedIds"))) {
            out.add(str(o).toLowerCase(Locale.ROOT));
        }
        for (Object o : asList(d.get().get("canonicalIds"))) {
            out.add(str(o).toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static String filterBtn(String value, String label, boolean on) {
        return "<button type=\"button\" class=\"pa-chip" + (on ? " on" : "") + "\" data-filter-chip=\""
                + Http.esc(value) + "\">" + Http.esc(label) + "</button>";
    }

    /**
     * Options du select Dialogue : [id namespacé, libellé lisible]. Fusionne le dernier
     * {@code dialogue.list} du serveur avec les dialogues de la <strong>source éditable</strong>
     * (issue #145) : un dialogue tout juste créé depuis {@code /dialogues/new} est immédiatement
     * sélectionnable sur la fiche d'un PNJ, sans redémarrage Minecraft.
     */
    private List<String[]> dialogueSelectOptions(String agentId) {
        java.util.LinkedHashMap<String, String[]> byId = new java.util.LinkedHashMap<>();
        for (Object o : latestDetails(agentId, "dialogue.list").map(x -> asList(x.get("dialogues"))).orElse(List.of())) {
            Map<String, Object> dg = asMap(o);
            String id = str(dg.get("id"));
            if (id.isEmpty()) {
                continue;
            }
            String key = str(dg.get("key"));
            byId.put(dialoguePlainKey(id), new String[] {id, MiniText.prettifyId(key.isEmpty() ? id : key)});
        }
        for (SourceCatalog.DialogueSource ds : sourceCatalog.dialogues()) {
            String plain = ds.plainId();
            if (!plain.isEmpty()) {
                byId.putIfAbsent(plain, new String[] {"rpgquest:" + plain, MiniText.prettifyId(plain)});
            }
        }
        return new ArrayList<>(byId.values());
    }

    // ================================================================================
    //  PNJ — une ligne d'accordion : synthèse (bouton) + détail structuré (collapse)
    // ================================================================================

    private String renderNpcAccordionItem(Session session, String agentId, Map<String, Object> n, int idx,
                                          Map<String, String> questTitles, List<String> questIds,
                                          com.lodygames.rpgquest.panel.content.RefData giverRef,
                                          List<String[]> dialogueOptions, List<Object> citizensRoster,
                                          List<String> spawnWorlds, boolean canWrite, boolean canSetGiver,
                                          boolean canLink, boolean canSpawn, boolean canWriteDialogue) {
        String id = str(n.get("id"));
        String displayName = str(n.get("displayName"));
        boolean hasName = !displayName.isEmpty() && !"null".equals(displayName);
        boolean hasDefinition = Boolean.TRUE.equals(n.get("logicalDefinitionPresent"));
        boolean boundCitizens = Boolean.TRUE.equals(n.get("citizensBindingPresent"));
        boolean enabled = Boolean.TRUE.equals(n.get("enabled"));
        String numeric = str(n.get("citizensNumericId"));
        boolean hasNumeric = !numeric.isEmpty() && !"null".equals(numeric);
        String role = cleanNull(str(n.get("role")));
        String state = str(n.get("state"));
        String definedDialogue = cleanNull(str(n.get("definedDialogueId")));
        String slug = "npc-" + idx + "-" + id.replaceAll("[^a-z0-9_-]", "-");

        List<Object> warnings = asList(n.get("warnings"));
        boolean anyErr = warnings.stream().anyMatch(w -> "error".equals(str(asMap(w).get("severity"))));
        boolean anyWarn = !warnings.isEmpty();
        String cat = (boundCitizens ? "linked" : "unlinked") + (anyWarn ? " warn" : "") + (anyErr ? " err" : "");
        String ftext = Http.esc((hasName ? MiniText.plain(displayName) : "") + " " + id + " " + role + " "
                + state + " " + numeric);

        String nameHtml = hasName ? MiniText.html(displayName) : Http.esc(MiniText.prettifyId(id));

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"accordion-item npc-item\" data-filter-item=\"npcs\" data-filter-cat=\"")
                .append(cat).append("\" data-filter-text=\"").append(ftext)
                .append("\" data-res-id=\"").append(Http.esc(id)).append("\">");
        sb.append("<h3 class=\"accordion-header\">");
        sb.append("<button class=\"accordion-button collapsed npc-head\" type=\"button\" data-bs-toggle=\"collapse\" "
                + "data-bs-target=\"#").append(slug).append("\" aria-expanded=\"false\" aria-controls=\"")
                .append(slug).append("\">");
        sb.append("<span class=\"npc-head-main\"><span class=\"npc-name\">").append(nameHtml).append("</span>")
                .append("<code class=\"tid npc-id\">").append(Http.esc(id)).append("</code></span>");
        sb.append("<span class=\"npc-head-badges\">");
        sb.append(hasDefinition
                ? "<span class=\"badge text-bg-secondary\">définition</span>"
                : "<span class=\"badge text-bg-danger\">sans définition</span>");
        if (boundCitizens) {
            sb.append("<span class=\"badge text-bg-secondary\">Citizens")
                    .append(hasNumeric ? " #" + Http.esc(numeric) : "").append("</span>");
        } else if (hasDefinition) {
            sb.append("<span class=\"badge text-bg-warning\">à lier</span>");
        }
        if ("CITIZENS_ORPHAN".equals(state)) {
            sb.append("<span class=\"badge text-bg-danger\">orphelin</span>");
        }
        if (hasDefinition && !enabled) {
            sb.append("<span class=\"badge text-bg-secondary\">désactivé</span>");
        }
        sb.append("</span></button></h3>");

        sb.append("<div id=\"").append(slug).append("\" class=\"accordion-collapse collapse\" ")
                .append("data-bs-parent=\"#npc-accordion\"><div class=\"accordion-body npc-detail\">");

        // ---- IDENTITÉ ----
        sb.append(detailSection("npc", "Identité"));
        sb.append("<dl class=\"npc-dl\">");
        dlRow(sb, "Nom affiché", nameHtml);
        dlRow(sb, "ID RPGQuest", Ui.id(id));
        dlRow(sb, "État", npcStateBadge(state));
        if (hasDefinition) {
            String desc = cleanNull(str(n.get("description")));
            if (!desc.isEmpty()) {
                dlRow(sb, "Description", Http.esc(desc));
            }
            dlRow(sb, "Rôle", role.isEmpty() ? "<span class=\"muted\">aucun</span>" : Http.esc(roleLabel(role)));
        }
        sb.append("</dl>");

        // ---- CITIZENS ----
        sb.append(detailSection("server", "Citizens"));
        sb.append("<dl class=\"npc-dl\">");
        if (boundCitizens && hasNumeric) {
            String cname = citizensNameFor(citizensRoster, numeric);
            dlRow(sb, "NPC", "#" + Http.esc(numeric) + (cname.isEmpty() ? "" : " — " + MiniText.html(cname)));
        } else if (boundCitizens) {
            dlRow(sb, "NPC", "lié");
        } else {
            dlRow(sb, "NPC", "<span class=\"muted\">aucun</span>");
        }
        dlRow(sb, "Binding", boundCitizens ? "<code class=\"tid\">" + Http.esc(id) + "</code>"
                : "<span class=\"muted\">aucun</span>");
        dlRow(sb, "État", npcStateBadge(state));
        sb.append("</dl>");

        // ---- CONTENU ----
        sb.append(detailSection("book", "Contenu"));
        sb.append("<div class=\"npc-content\">");
        String dialogueId = cleanNull(str(n.get("dialogueId")));
        if (!dialogueId.isEmpty()) {
            String nodes = str(n.get("dialogueNodes"));
            String choices = str(n.get("dialogueChoices"));
            sb.append("<div class=\"npc-content-row\"><div><span class=\"npc-ck\">Dialogue</span>"
                    + "<div class=\"npc-cv\">").append(Http.esc(MiniText.prettifyId(dialogueId)))
                    .append(" <code class=\"tid\">").append(Http.esc(dialogueId)).append("</code>")
                    .append("<div class=\"faint\">").append(Http.esc(nodes)).append(" nœud(s) · ")
                    .append(Http.esc(choices)).append(" choix</div></div></div>")
                    .append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"/dialogues?q=")
                    .append(Http.esc(dialogueId)).append("\">").append(Icons.icon("open")).append("Ouvrir le dialogue</a></div>");
            List<Object> starts = asList(n.get("dialogueStartsQuests"));
            if (!starts.isEmpty()) {
                sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Le dialogue démarre</span> ")
                        .append(referencedQuests(starts, questTitles)).append("</p>");
            }
        } else if (!definedDialogue.isEmpty()) {
            sb.append("<div class=\"npc-content-row\"><div><span class=\"npc-ck\">Dialogue déclaré</span>"
                    + "<div class=\"npc-cv\">").append(Http.esc(MiniText.prettifyId(definedDialogue)))
                    .append(" <code class=\"tid\">").append(Http.esc(definedDialogue)).append("</code>")
                    .append(" <span class=\"faint\">(pas encore chargé en jeu)</span></div></div>");
            if (canWriteDialogue) {
                sb.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"/dialogues/new?npc=")
                        .append(Http.esc(id)).append("\">").append(Icons.icon("plus"))
                        .append("Créer un dialogue pour ce PNJ</a>");
            }
            sb.append("</div>");
        } else {
            sb.append("<div class=\"npc-content-row\"><div><span class=\"npc-ck\">Dialogue</span>"
                    + "<div class=\"npc-cv\"><span class=\"muted\">Aucun dialogue.</span></div></div>");
            if (canWriteDialogue) {
                sb.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"/dialogues/new?npc=")
                        .append(Http.esc(id)).append("\">").append(Icons.icon("plus"))
                        .append("Créer un dialogue pour ce PNJ</a>");
            }
            sb.append("</div>");
        }
        List<Object> given = asList(n.get("questsGiven"));
        List<Object> referenced = asList(n.get("questsReferenced"));
        if (!given.isEmpty()) {
            sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Donne</span> ")
                    .append(referencedQuests(given, questTitles))
                    .append(" <a class=\"btn btn-sm btn-outline-secondary\" href=\"/quests\">")
                    .append(Icons.icon("open")).append("Quêtes</a></p>");
        }
        if (!referenced.isEmpty()) {
            sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Objectif « parler à »</span> ")
                    .append(referencedQuests(referenced, questTitles)).append("</p>");
        }
        if (!role.isEmpty()) {
            sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Rôle</span> ")
                    .append(Http.esc(roleLabel(role)))
                    .append(" <code class=\"tid\">").append(Http.esc(role)).append("</code></p>");
        }
        sb.append("</div>");

        // ---- DIAGNOSTICS (aide humaine + lien doc + action immédiate) ----
        // On mémorise les cibles de formulaire déjà proposées par un « Corriger maintenant » de
        // diagnostic : la section Actions ne réaffichera pas ces mêmes boutons (règle CTA #89 polish).
        java.util.Set<String> diagFixTargets = new java.util.HashSet<>();
        sb.append(detailSection("warning", "Diagnostics"));
        if (warnings.isEmpty()) {
            sb.append("<p class=\"muted npc-diag-ok\">").append(Icons.icon("check")).append("Aucune anomalie.</p>");
        } else {
            String subject = hasName ? MiniText.plain(displayName) : MiniText.prettifyId(id);
            for (Object w : warnings) {
                Map<String, Object> wm = asMap(w);
                String code = str(wm.get("code"));
                String[] fix = npcFixTarget(code, slug, canWrite, hasDefinition, canLink, boundCitizens, enabled);
                String fixBtn = "";
                if (fix != null) {
                    diagFixTargets.add(fix[0]);
                    fixBtn = "<button class=\"btn btn-sm btn-primary\" type=\"button\" data-bs-toggle=\"collapse\" "
                            + "data-bs-target=\"#" + fix[0] + "\" aria-controls=\"" + fix[0] + "\">"
                            + Icons.icon("wrench") + Http.esc(fix[1]) + "</button>";
                }
                sb.append(DiagnosticHelp.render(code, str(wm.get("severity")), str(wm.get("message")),
                        subject, "", fixBtn));
            }
        }

        // ---- ACTIONS ----
        // Les formulaires masqués (collapse) sont TOUJOURS rendus si la permission le permet : un
        // « Corriger maintenant » de diagnostic peut les cibler même quand la section Actions les masque.
        List<String[]> toggles = new ArrayList<>(); // {target, label, icon, btnClass}
        StringBuilder forms = new StringBuilder();
        if (canWrite && !hasDefinition) {
            toggles.add(new String[] {slug + "-f-create", "Créer la définition", "plus", "btn-primary"});
            forms.append(actionCollapse(slug + "-f-create", "<div class=\"card card-body npc-formcard\">"
                    + npcDefForm(session, agentId, "create", id, hasName ? displayName : MiniText.prettifyId(id),
                            "", "", true, dialogueOptions, slug + "-f-create")
                    + "</div>"));
        }
        if (canWrite && hasDefinition) {
            toggles.add(new String[] {slug + "-f-edit", "Modifier", "edit", "btn-outline-primary"});
            forms.append(actionCollapse(slug + "-f-edit", "<div class=\"card card-body npc-formcard\">"
                    + npcDefForm(session, agentId, "update", id, hasName ? displayName : "",
                            definedDialogue, role, enabled, dialogueOptions, slug + "-f-edit")
                    + "</div>"));
        }
        if (canSetGiver && hasDefinition) {
            toggles.add(new String[] {slug + "-f-giver", "Attribuer une quête", "gift", "btn-outline-secondary"});
            forms.append(actionCollapse(slug + "-f-giver", "<div class=\"card card-body npc-formcard\">"
                    + giverForm(session, agentId, id, giverRef, slug) + "</div>"));
        }
        // Issue #165 : nom en jeu et apparence. Ne demandent PAS de définition RPGQuest — un PNJ
        // Citizens « orphelin » (cas de Help) doit pouvoir être renommé et habillé. Seule condition
        // réelle : qu'un PNJ Citizens soit effectivement lié, puisque c'est lui qu'on modifie.
        if (canLink && boundCitizens) {
            toggles.add(new String[] {slug + "-f-look", "Nom en jeu & apparence", "edit", "btn-outline-secondary"});
            forms.append(actionCollapse(slug + "-f-look", "<div class=\"card card-body npc-formcard\">"
                    + citizensLookForm(session, agentId, id, hasName ? displayName : "") + "</div>"));
        }
        if (canLink && hasDefinition && !boundCitizens && enabled) {
            toggles.add(new String[] {slug + "-f-link", "Lier un PNJ Citizens", "link", "btn-outline-secondary"});
            forms.append(actionCollapse(slug + "-f-link", "<div class=\"card card-body npc-formcard\">"
                    + citizensLinkForm(session, agentId, id, citizensRoster) + "</div>"));
        }
        if (canSpawn && hasDefinition && !boundCitizens && enabled) {
            toggles.add(new String[] {slug + "-f-spawn", "Créer le PNJ Citizens", "server", "btn-outline-secondary"});
            forms.append(actionCollapse(slug + "-f-spawn", "<div class=\"card card-body npc-formcard\">"
                    + citizensCreateForm(session, agentId, id, hasName ? displayName : MiniText.prettifyId(id),
                            spawnWorlds) + "</div>"));
        }

        List<String[]> shownToggles = new ArrayList<>();
        for (String[] t : toggles) {
            if (!diagFixTargets.contains(t[0])) {
                shownToggles.add(t);
            }
        }
        if (!shownToggles.isEmpty()) {
            sb.append(detailSection("target", "Actions"));
            sb.append("<div class=\"npc-actions d-flex flex-wrap gap-2\">");
            for (String[] t : shownToggles) {
                sb.append(actionToggle(t[0], t[1], t[2], t[3]));
            }
            sb.append("</div>");
        }
        sb.append(forms);

        sb.append("</div></div></div>");
        return sb.toString();
    }

    // ================================================================================
    //  PNJ — ligne d'un PNJ Citizens présent en jeu mais SANS fiche RPGQuest ni liaison
    //  (#101). L'identité affichée vient du registre Citizens (id numérique + UUID + nom
    //  en jeu) ; jamais d'une fiche logique, qui n'existe pas ici.
    // ================================================================================

    private String renderFreeCitizensAccordionItem(Session session, String agentId, Map<String, Object> c,
                                                   int idx, List<String[]> dialogueOptions,
                                                   List<String> definedUnlinkedIds, boolean canWrite,
                                                   boolean canLink) {
        String numeric = str(c.get("numericId"));
        String digits = numeric.replaceAll("[^0-9]", "");
        String rawName = str(c.get("name"));
        boolean hasName = !rawName.isEmpty() && !"null".equals(rawName);
        String uuid = str(c.get("uuid"));
        boolean hasUuid = !uuid.isEmpty() && !"null".equals(uuid);
        boolean spawned = Boolean.TRUE.equals(c.get("spawned"));
        String label = hasName ? MiniText.plain(rawName) : "PNJ Citizens #" + numeric;
        String slug = "fc-" + idx + "-" + digits;
        String resId = "citizens-" + digits;

        // Recherche (#101 §14) : nom Citizens + id numérique + UUID + libellés d'état.
        String ftext = Http.esc(label + " citizens #" + numeric + " " + numeric + " " + uuid
                + " sans fiche rpgquest non lie libre");

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"accordion-item npc-item\" data-filter-item=\"npcs\" data-filter-cat=\"unlinked\" ")
                .append("data-filter-text=\"").append(ftext).append("\" data-res-id=\"").append(Http.esc(resId))
                .append("\">");
        sb.append("<h3 class=\"accordion-header\">");
        sb.append("<button class=\"accordion-button collapsed npc-head\" type=\"button\" data-bs-toggle=\"collapse\" "
                + "data-bs-target=\"#").append(slug).append("\" aria-expanded=\"false\" aria-controls=\"")
                .append(slug).append("\">");
        sb.append("<span class=\"npc-head-main\"><span class=\"npc-name\">")
                .append(hasName ? MiniText.html(rawName) : Http.esc(label))
                .append("</span><code class=\"tid npc-id\">Citizens #").append(Http.esc(numeric))
                .append("</code></span>");
        sb.append("<span class=\"npc-head-badges\">");
        sb.append("<span class=\"badge text-bg-danger\">sans fiche RPGQuest</span>");
        sb.append("<span class=\"badge text-bg-warning\">non lié</span>");
        sb.append("</span></button></h3>");

        sb.append("<div id=\"").append(slug).append("\" class=\"accordion-collapse collapse\" ")
                .append("data-bs-parent=\"#npc-accordion\"><div class=\"accordion-body npc-detail\">");

        // ---- IDENTITÉ CITIZENS ----
        sb.append(detailSection("server", "Identité Citizens"));
        sb.append("<dl class=\"npc-dl\">");
        dlRow(sb, "Nom en jeu", hasName ? MiniText.html(rawName) : "<span class=\"muted\">(sans nom)</span>");
        dlRow(sb, "Numéro Citizens", "#" + Http.esc(numeric));
        if (hasUuid) {
            dlRow(sb, "UUID Citizens", "<code class=\"tid\">" + Http.esc(uuid) + "</code>");
        }
        dlRow(sb, "Présent en jeu", spawned ? "oui" : "non (non spawné actuellement)");
        sb.append("</dl>");

        // ---- FICHE RPGQUEST (absente) ----
        sb.append(detailSection("npc", "Fiche RPGQuest"));
        sb.append("<dl class=\"npc-dl\">");
        dlRow(sb, "Fiche", "<span class=\"muted\">aucune</span>");
        dlRow(sb, "Liaison", "<span class=\"muted\">aucune</span>");
        dlRow(sb, "État", npcStateBadge("CITIZENS_ONLY"));
        sb.append("</dl>");
        sb.append("<p class=\"muted npc-content-empty\">Ce PNJ a été créé directement dans Citizens. "
                + "Il apparaît en jeu, mais RPGQuest ne gère ni ses dialogues, ni ses quêtes, ni son rôle "
                + "tant qu'aucune fiche ne lui est associée.</p>");

        // ---- DIAGNOSTICS ----
        sb.append(detailSection("warning", "Diagnostics"));
        sb.append(DiagnosticHelp.render("CITIZENS_ONLY", "info", "", label, "", ""));

        // ---- ACTIONS ----
        List<String[]> toggles = new ArrayList<>();
        StringBuilder forms = new StringBuilder();
        if (canWrite) {
            String defId = citizensNameToId(rawName, digits);
            toggles.add(new String[] {slug + "-f-create", "Créer une fiche RPGQuest", "plus", "btn-primary"});
            forms.append(actionCollapse(slug + "-f-create", "<div class=\"card card-body npc-formcard\">"
                    + npcDefForm(session, agentId, "create", defId, hasName ? rawName : MiniText.prettifyId(defId),
                            "", "", true, dialogueOptions, slug + "-f-create")
                    + "</div>"));
        }
        if (canLink && !definedUnlinkedIds.isEmpty()) {
            toggles.add(new String[] {slug + "-f-link", "Lier à une fiche existante", "link",
                    "btn-outline-secondary"});
            forms.append(actionCollapse(slug + "-f-link", "<div class=\"card card-body npc-formcard\">"
                    + citizensInverseLinkForm(session, agentId, numeric, label, definedUnlinkedIds) + "</div>"));
        }
        if (!toggles.isEmpty()) {
            sb.append(detailSection("target", "Actions"));
            sb.append("<div class=\"npc-actions d-flex flex-wrap gap-2\">");
            for (String[] t : toggles) {
                sb.append(actionToggle(t[0], t[1], t[2], t[3]));
            }
            sb.append("</div>");
        }
        sb.append(forms);

        sb.append("</div></div></div>");
        return sb.toString();
    }

    /**
     * Id RPGQuest proposé par défaut pour une fiche créée depuis un PNJ Citizens libre : nom en jeu
     * normalisé (minuscules, sans accent, {@code [a-z0-9._-]}), repli {@code citizens_<id numérique>}.
     */
    private static String citizensNameToId(String name, String numericDigits) {
        String base = java.text.Normalizer.normalize(name == null ? "" : name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "_")
                .replaceAll("(^[._-]+|[._-]+$)", "");
        if (base.length() > 64) {
            base = base.substring(0, 64);
        }
        return base.isEmpty() ? "citizens_" + (numericDigits.isEmpty() ? "0" : numericDigits) : base;
    }

    /**
     * Liaison inverse (#101) : le PNJ Citizens est fixé (id numérique), l'admin choisit la fiche
     * RPGQuest existante — prête et non liée — à lui associer. Réutilise l'action
     * {@code npc.citizens.link} (aucun spawn, aucun déplacement, aucun rebind).
     */
    private String citizensInverseLinkForm(Session session, String agentId, String numericId,
                                           String citizensLabel, List<String> definedUnlinkedIds) {
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">").append(Icons.icon("link"))
                .append("Lier « ").append(Http.esc(citizensLabel)).append(" » à une fiche RPGQuest</p>");
        sb.append(formStart(session, agentId, "npc.citizens.link", "/npcs", ""));
        sb.append("<input type=\"hidden\" name=\"citizens_id\" value=\"").append(Http.esc(numericId)).append("\">");
        sb.append("<label>Fiche RPGQuest</label>").append(idSelect("npc_id", definedUnlinkedIds, "woodcutter_bob"));
        sb.append("<p class=\"form-text\">Associe le PNJ Citizens #" + Http.esc(numericId) + " à la fiche RPGQuest "
                + "choisie. Le PNJ n'est ni créé, ni déplacé. L'association peut être défaite.</p>");
        sb.append(mutationConsent("npc.citizens.link", "", ""));
        sb.append("<button class=\"btn\" type=\"submit\">Associer cette fiche</button></form>");
        latestForPlayer(agentId, "npc.citizens.link", "").ifPresent(row -> sb.append(resultLine("Dernière liaison", row)));
        return sb.toString();
    }

    private static String detailSection(String icon, String title) {
        return "<p class=\"npc-section\">" + Icons.icon(icon) + Http.esc(title) + "</p>";
    }

    private static void dlRow(StringBuilder sb, String k, String vHtml) {
        sb.append("<div class=\"npc-dl-row\"><dt>").append(Http.esc(k)).append("</dt><dd>").append(vHtml).append("</dd></div>");
    }

    private static String actionToggle(String targetId, String label, String icon, String btnClass) {
        return "<button class=\"btn btn-sm " + btnClass + "\" type=\"button\" data-bs-toggle=\"collapse\" "
                + "data-bs-target=\"#" + targetId + "\" aria-expanded=\"false\" aria-controls=\"" + targetId + "\">"
                + Icons.icon(icon) + Http.esc(label) + "</button>";
    }

    private static String actionCollapse(String id, String inner) {
        return "<div class=\"collapse npc-form-collapse\" id=\"" + id + "\">" + inner + "</div>";
    }

    /** {@code quest_giver} → « Donneur de quête » ; sinon forme lisible de l'id. */
    private static String roleLabel(String role) {
        return "quest_giver".equals(role) ? "Donneur de quête" : MiniText.prettifyId(role);
    }

    /**
     * Cible du bouton « Corriger maintenant » d'un diagnostic PNJ : {@code {targetId, libellé}} du
     * formulaire d'action à déplier, ou {@code null} si aucune action immédiate n'est pertinente
     * (permission absente, précondition non remplie). Le libellé reprend celui de l'action pour que
     * la section Actions puisse dédupliquer.
     */
    private static String[] npcFixTarget(String code, String slug, boolean canWrite, boolean hasDefinition,
                                         boolean canLink, boolean boundCitizens, boolean enabled) {
        return switch (code == null ? "" : code) {
            case "BINDING_NO_DEFINITION", "NO_DEFINITION" ->
                    (!canWrite || hasDefinition) ? null
                            : new String[] {slug + "-f-create", "Créer la définition"};
            case "DIALOGUE_MISSING", "DISABLED", "GIVER_NO_DIALOGUE" ->
                    (!canWrite || !hasDefinition) ? null
                            : new String[] {slug + "-f-edit", "Modifier"};
            case "NOT_LINKED" ->
                    (!canLink || !hasDefinition || boundCitizens || !enabled) ? null
                            : new String[] {slug + "-f-link", "Lier un PNJ Citizens"};
            default -> null;
        };
    }

    /**
     * Formulaire « Créer / Modifier la définition RPGQuest » repensé (sections Bootstrap, labels
     * humains, selects sur sources connues). {@code mode} ∈ {@code create} / {@code update}.
     *
     * <p><strong>Bug de contexte (capture) corrigé</strong> : l'{@code npc_id} est un champ
     * <em>caché</em> non éditable pour un formulaire ouvert dans la fiche d'un PNJ précis, doublé
     * d'un {@code npc_ctx} caché revérifié côté serveur. Le formulaire porte {@code autocomplete=off}
     * et pré-remplit ses champs avec les valeurs du PNJ courant — jamais celles d'un autre.</p>
     */
    private String npcDefForm(Session session, String agentId, String mode, String npcId, String displayName,
                              String dialogueId, String role, boolean enabled, List<String[]> dialogueOptions,
                              String uid) {
        boolean update = "update".equals(mode);
        boolean contextual = !npcId.isEmpty();
        StringBuilder sb = new StringBuilder();
        sb.append("<p class=\"fs-h\">").append(Icons.icon("npc"))
                .append(update ? "Modifier la définition RPGQuest" : "Créer la définition RPGQuest").append("</p>");
        sb.append("<form method=\"post\" action=\"/agents/action\" autocomplete=\"off\" class=\"npc-def-form\">");
        sb.append("<input type=\"hidden\" name=\"_csrf\" value=\"").append(Http.esc(session.csrfToken())).append("\">");
        sb.append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"type\" value=\"npc.definition.")
                .append(update ? "update" : "create").append("\">");
        sb.append("<input type=\"hidden\" name=\"return\" value=\"/npcs\">");
        if (contextual) {
            sb.append("<input type=\"hidden\" name=\"npc_id\" value=\"").append(Http.esc(npcId)).append("\">");
            sb.append("<input type=\"hidden\" name=\"npc_ctx\" value=\"").append(Http.esc(npcId)).append("\">");
        }

        // -- Identité --
        sb.append("<div class=\"npc-fs\"><p class=\"npc-fs-h\">Identité</p>");
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid)
                .append("-name\">Nom du PNJ</label>")
                .append("<input class=\"form-control\" id=\"").append(uid).append("-name\" type=\"text\" name=\"display_name\" "
                        + "autocomplete=\"off\" maxlength=\"128\" placeholder=\"Exemple : Bob le bûcheron\" value=\"")
                .append(Http.esc(displayName)).append("\" required>")
                .append("<div class=\"form-text\">Nom affiché aux joueurs dans le jeu.</div></div>");
        if (contextual) {
            sb.append("<div class=\"mb-2\"><label class=\"form-label\">ID technique</label>"
                    + "<input class=\"form-control\" type=\"text\" value=\"").append(Http.esc(npcId))
                    .append("\" readonly><div class=\"form-text\">Identifiant interne utilisé par RPGQuest. Non modifiable ")
                    .append(update ? "." : "après création.").append("</div></div>");
        } else {
            sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid)
                    .append("-id\">ID technique</label>"
                    + "<input class=\"form-control\" id=\"").append(uid).append("-id\" type=\"text\" name=\"npc_id\" "
                    + "autocomplete=\"off\" pattern=\"[a-z0-9._-]{1,64}\" placeholder=\"Exemple : woodcutter_bob\" required>"
                    + "<div class=\"form-text\">Identifiant interne unique. Minuscules, chiffres, « . _ - ». "
                    + "Il ne pourra plus être modifié après création.</div></div>");
        }
        sb.append("</div>");

        // -- Contenu --
        sb.append("<div class=\"npc-fs\"><p class=\"npc-fs-h\">Contenu</p>");
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid)
                .append("-dlg\">Dialogue</label>").append(dlgSelect(uid + "-dlg", dialogueId, dialogueOptions))
                .append("<div class=\"form-text\">Dialogue déclenché quand un joueur interagit avec ce PNJ. "
                        + "Peut être ajouté plus tard.</div></div>");
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid)
                .append("-role\">Rôle</label><select class=\"form-select\" id=\"").append(uid)
                .append("-role\" name=\"role\"><option value=\"\">Aucun</option>")
                .append("<option value=\"quest_giver\"").append("quest_giver".equals(role) ? " selected" : "")
                .append(">Donneur de quête</option></select><div class=\"form-text\">Fonction particulière du PNJ. "
                        + "Laisser « Aucun » pour un PNJ standard.</div></div>");
        sb.append("</div>");

        // -- État --
        sb.append("<div class=\"npc-fs\"><p class=\"npc-fs-h\">État</p>");
        sb.append("<div class=\"form-check form-switch\"><input class=\"form-check-input\" type=\"checkbox\" role=\"switch\" ")
                .append("id=\"").append(uid).append("-en\" name=\"enabled\" value=\"true\"")
                .append(enabled ? " checked" : "").append("><label class=\"form-check-label\" for=\"").append(uid)
                .append("-en\">PNJ actif</label></div>")
                .append("<div class=\"form-text\">Si désactivé, la fiche reste enregistrée mais le PNJ n'est pas "
                        + "utilisable par le gameplay.</div></div>");

        // -- Consentement + boutons -- (issue #111 : plus de case à cocher cachée pour une édition réversible)
        sb.append(mutationConsent("npc.definition." + (update ? "update" : "create"), "",
                update
                        ? "Les champs nom, dialogue, rôle et état de « " + npcId + " » seront remplacés. "
                                + "L'identifiant technique ne change pas. Modification réversible."
                        : "La fiche RPGQuest sera créée (fichier npcs/<id>.yml). "
                                + "Aucun PNJ physique Citizens n'est créé à cette étape."));
        sb.append("<div class=\"d-flex flex-wrap gap-2 mt-2\">");
        sb.append("<button class=\"btn btn-outline-secondary\" type=\"button\" data-bs-toggle=\"collapse\" ")
                .append("data-bs-target=\"#").append(uid).append("\">Annuler</button>");
        sb.append("<button class=\"btn btn-primary\" type=\"submit\">")
                .append(update ? "Enregistrer" : "Créer la définition").append("</button>");
        sb.append("</div></form>");
        return sb.toString();
    }

    private static String dlgSelect(String selId, String current, List<String[]> options) {
        StringBuilder sb = new StringBuilder("<select class=\"form-select\" id=\"" + selId + "\" name=\"dialogue_id\">");
        sb.append("<option value=\"\">Aucun</option>");
        boolean matched = false;
        for (String[] o : options) {
            boolean sel = o[0].equalsIgnoreCase(current);
            matched = matched || sel;
            sb.append("<option value=\"").append(Http.esc(o[0])).append("\"").append(sel ? " selected" : "")
                    .append(">").append(Http.esc(o[1])).append("</option>");
        }
        if (!matched && current != null && !current.isBlank()) {
            sb.append("<option value=\"").append(Http.esc(current)).append("\" selected>")
                    .append(Http.esc(MiniText.prettifyId(current))).append(" (hors catalogue)</option>");
        }
        return sb.append("</select>").toString();
    }

    /**
     * Formulaire « Attribuer une quête (giver) ».
     *
     * <p><strong>Issue #164</strong> : la liste est désormais (a) <em>recherchable</em> par titre
     * <em>ou</em> par identifiant (composant {@code .combo} de {@code panel.js}, repli natif
     * {@code <input list>} sans JavaScript) et (b) alimentée par le catalogue <em>fusionné</em>
     * source + runtime — une quête tout juste enregistrée dans la source apparaît donc
     * immédiatement, sans redémarrage ni déploiement Minecraft, ce qui était le défaut signalé.</p>
     *
     * <p><strong>Sémantique assumée, pas masquée</strong> : {@code quest.giver.set} agit sur le
     * <em>serveur</em> (il écrit {@code giver:} dans le YAML côté serveur puis recharge le moteur)
     * et refuse une quête que le serveur n'a pas chargée ({@code UNKNOWN_QUEST}) — aucune référence
     * invalide n'est donc jamais enregistrée. Une quête « source uniquement » est affichée et
     * cherchable, avec le prérequis de déploiement énoncé explicitement et le chemin source
     * alternatif (champ « PNJ donneur » de l'éditeur de quête) proposé en lien direct.</p>
     */
    private String giverForm(Session session, String agentId, String npcId,
                             com.lodygames.rpgquest.panel.content.RefData ref, String domId) {
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">").append(Icons.icon("gift"))
                .append("Attribuer une quête</p>");
        List<String> quests = ref == null ? List.of() : ref.quests();
        if (quests.isEmpty()) {
            return sb.append(Ui.empty("Aucune quête connue : charger le catalogue (page Quêtes) "
                    + "ou créer une quête dans l'éditeur.")).toString();
        }
        String dlId = "dl-giver-" + domId;
        boolean anySourceOnly = false;
        StringBuilder dl = new StringBuilder("<datalist id=\"").append(dlId).append("\">");
        for (String q : quests) {
            String label = ref.questLabel(q);
            String origin = ref.questOriginLabel(q);
            if (com.lodygames.rpgquest.panel.content.RefData.ORIGIN_SOURCE.equals(ref.questOrigin(q))) {
                anySourceOnly = true;
            }
            dl.append("<option value=\"").append(Http.esc(q)).append("\"");
            if (!label.equals(q)) {
                dl.append(" label=\"").append(Http.esc(label)).append("\"");
            }
            if (!origin.isBlank()) {
                dl.append(" data-origin=\"").append(Http.esc(origin)).append("\"");
            }
            dl.append(">");
        }
        dl.append("</datalist>");

        sb.append(formStart(session, agentId, "quest.giver.set", "/npcs", ""));
        sb.append("<input type=\"hidden\" name=\"npc_id\" value=\"").append(Http.esc(npcId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"npc_ctx\" value=\"").append(Http.esc(npcId)).append("\">");
        sb.append("<label for=\"giver-q-").append(domId).append("\">Quête</label>");
        sb.append(dl);
        sb.append("<div class=\"combo\" data-combo><input class=\"combo-input\" type=\"text\" ")
                .append("id=\"giver-q-").append(domId).append("\" name=\"quest_id\" list=\"").append(dlId)
                .append("\" autocomplete=\"off\" role=\"combobox\" aria-expanded=\"false\" ")
                .append("aria-autocomplete=\"list\" placeholder=\"Chercher par titre ou identifiant…\" ")
                .append("pattern=\"[A-Za-z0-9_.:/-]{1,96}\" required></div>");
        sb.append("<p class=\"faint\" style=\"font-size:12px\">Chercher par titre (« Premiers pas ») "
                + "ou par identifiant (« first_steps »). L'état de chaque quête est indiqué dans la liste.</p>");
        if (anySourceOnly) {
            sb.append("<p class=\"faint\" style=\"font-size:12px\">Une quête <strong>« source "
                    + "uniquement »</strong> n'est pas encore chargée par le serveur : cette action la "
                    + "refusera (jamais de référence cassée enregistrée, jamais de déploiement "
                    + "silencieux). Deux chemins possibles — déployer puis réattribuer ici, ou "
                    + "renseigner directement le champ « PNJ donneur » dans "
                    + "<a href=\"/quests\">l'éditeur de quête</a>, qui écrit dans la source.</p>");
        }
        sb.append(mutationConsent("quest.giver.set", "",
                "La quête choisie sera donnée par « " + npcId + " ». Réversible en la réattribuant à un autre PNJ."));
        sb.append("<button class=\"btn\" type=\"submit\">Attribuer</button></form>");
        return sb.toString();
    }

    /**
     * Issue #165 — « Nom en jeu & apparence » d'un PNJ Citizens déjà lié. Deux opérations
     * <strong>distinctes et indépendantes</strong> (deux formulaires, deux boutons) : renommer et
     * appliquer un skin n'ont pas les mêmes effets ni les mêmes risques, les mélanger en un seul
     * bouton rendrait l'échec de l'une ambigu.
     *
     * <p>Trois garde-fous repris du ticket : on ne demande <em>que</em> le lien MineSkin (jamais
     * une commande libre), le PNJ est ciblé par son identité logique côté serveur (jamais par une
     * sélection Citizens globale), et l'identifiant logique RPGQuest n'est pas touché — renommer
     * « Help » ne renomme pas l'id {@code help}.</p>
     */
    private String citizensLookForm(Session session, String agentId, String npcId, String currentName) {
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">").append(Icons.icon("edit"))
                .append("Nom en jeu &amp; apparence</p>");
        sb.append("<p class=\"faint\" style=\"font-size:12px\">Ces deux opérations ne changent que "
                + "l'<strong>apparence en jeu</strong> du PNJ Citizens lié. L'identifiant logique "
                + "<code>").append(Http.esc(npcId)).append("</code>, l'identité Citizens et les liaisons "
                + "quêtes / dialogues / stories restent inchangés.</p>");

        // --- Nom en jeu ---
        sb.append(formStart(session, agentId, "npc.citizens.rename", "/npcs", ""));
        sb.append("<input type=\"hidden\" name=\"npc_id\" value=\"").append(Http.esc(npcId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"npc_ctx\" value=\"").append(Http.esc(npcId)).append("\">");
        sb.append("<label for=\"rename-").append(Http.esc(npcId)).append("\">Nom en jeu</label>");
        sb.append("<input type=\"text\" id=\"rename-").append(Http.esc(npcId)).append("\" name=\"name\" ")
                .append("maxlength=\"48\" required value=\"").append(Http.esc(currentName)).append("\">");
        sb.append("<p class=\"faint\" style=\"font-size:12px\">Nom affiché au-dessus du PNJ. Distinct du "
                + "nom de la définition RPGQuest et du locuteur des dialogues : ceux-ci ne sont "
                + "<strong>pas</strong> modifiés ici, pour ne jamais réécrire un texte partagé.</p>");
        sb.append(mutationConsent("npc.citizens.rename", "",
                "Le nom affiché en jeu du PNJ Citizens lié à « " + npcId + " » sera changé."));
        sb.append("<button class=\"btn\" type=\"submit\">Renommer</button></form>");

        // --- Skin MineSkin ---
        sb.append("<hr>");
        sb.append(formStart(session, agentId, "npc.citizens.skin", "/npcs", ""));
        sb.append("<input type=\"hidden\" name=\"npc_id\" value=\"").append(Http.esc(npcId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"npc_ctx\" value=\"").append(Http.esc(npcId)).append("\">");
        sb.append("<label for=\"skin-").append(Http.esc(npcId)).append("\">Lien MineSkin</label>");
        sb.append("<input type=\"url\" id=\"skin-").append(Http.esc(npcId)).append("\" name=\"skin_url\" ")
                .append("placeholder=\"https://minesk.in/…\" pattern=\"https://minesk\\.in/[A-Za-z0-9]{8,64}\" ")
                .append("required>");
        sb.append("<p class=\"faint\" style=\"font-size:12px\">Coller <strong>uniquement le lien</strong> "
                + "<code>https://minesk.in/…</code> donné par MineSkin — pas la commande "
                + "<code>/npc skin --url …</code>. Le lien est revalidé côté serveur. "
                + "S'applique aux PNJ de type joueur ; un type sans skin est refusé avec un message "
                + "explicite, et le skin précédent est conservé.</p>");
        sb.append(mutationConsent("npc.citizens.skin", "",
                "L'apparence du PNJ Citizens lié à « " + npcId + " » sera changée."));
        sb.append("<button class=\"btn\" type=\"submit\">Appliquer le skin</button>");
        sb.append("<p class=\"faint\" style=\"font-size:12px\">Le téléchargement est fait par Citizens de "
                + "façon asynchrone : un succès signifie « demande transmise ». Vérifier le rendu en "
                + "jeu (une reconnexion du client peut être nécessaire).</p>");
        sb.append("</form>");
        return sb.toString();
    }

    /**
     * Formulaire de spawn (#81 phase 2) : preview stricte (définition, nom, « aucun Citizens lié »),
     * monde en liste déroulante (mondes chargés annoncés par le heartbeat), coordonnées à saisir
     * (jamais devinées), confirmation obligatoire.
     */
    private String citizensCreateForm(Session session, String agentId, String npcId, String displayName,
                                      List<String> spawnWorlds) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"preview\"><p class=\"meta-line\"><span class=\"meta-k\">ID RPGQuest</span> ")
                .append(Ui.id(npcId)).append("</p>");
        sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Nom</span> ")
                .append(MiniText.html(displayName)).append("</p>");
        sb.append("<p class=\"faint\" style=\"font-size:12px\">Nom repris de la définition — non modifiable "
                + "ici. Aucun PNJ Citizens n'est actuellement lié à « ").append(Http.esc(npcId))
                .append(" ».</p></div>");

        sb.append(formStart(session, agentId, "npc.citizens.create", "/npcs", ""));
        sb.append("<input type=\"hidden\" name=\"npc_id\" value=\"").append(Http.esc(npcId)).append("\">");
        if (spawnWorlds.isEmpty()) {
            sb.append("<label>Monde</label><input type=\"text\" name=\"world\" placeholder=\"world_hub\" "
                    + "pattern=\"[A-Za-z0-9_./-]{1,64}\">");
            sb.append("<p class=\"faint\" style=\"font-size:12px\">Liste des mondes indisponible "
                    + "(heartbeat non reçu) — saisir un monde RPGQuest autorisé (hub / claims / exploration).</p>");
        } else {
            sb.append("<label>Monde</label><select name=\"world\">");
            for (String w : spawnWorlds) {
                sb.append("<option value=\"").append(Http.esc(w)).append("\">").append(Http.esc(w)).append("</option>");
            }
            sb.append("</select>");
        }
        sb.append("<div class=\"coord-row\">");
        sb.append("<label class=\"coord\">X<input type=\"text\" name=\"x\" inputmode=\"decimal\" placeholder=\"125.5\"></label>");
        sb.append("<label class=\"coord\">Y<input type=\"text\" name=\"y\" inputmode=\"decimal\" placeholder=\"64\"></label>");
        sb.append("<label class=\"coord\">Z<input type=\"text\" name=\"z\" inputmode=\"decimal\" placeholder=\"-82.5\"></label>");
        sb.append("<label class=\"coord\">Yaw<input type=\"text\" name=\"yaw\" inputmode=\"decimal\" placeholder=\"0\"></label>");
        sb.append("<label class=\"coord\">Pitch<input type=\"text\" name=\"pitch\" inputmode=\"decimal\" placeholder=\"0\"></label>");
        sb.append("</div>");
        sb.append(confirmBox("Créer le PNJ Citizens « " + npcId + " » à la position indiquée et le lier "
                + "immédiatement (rollback automatique si la liaison échoue)."));
        sb.append("<button class=\"btn\" type=\"submit\">Confirmer la création</button></form>");
        latestForPlayer(agentId, "npc.citizens.create", "").ifPresent(row -> sb.append(resultLine("Dernier spawn", row)));
        return sb.toString();
    }

    /** Noms des mondes annoncés <em>chargés</em> par le dernier heartbeat de l'agent (pour la liste de spawn). */
    private List<String> loadedWorldNames(String agentId) {
        return store.latestHeartbeat(agentId).map(hb -> {
            String worldsJson = hb.worldsJson();
            if (worldsJson == null || worldsJson.isBlank()) {
                return List.<String>of();
            }
            try {
                Map<String, Object> worlds = Json.parseObject(worldsJson);
                java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
                for (Object v : worlds.values()) {
                    Map<String, Object> w = asMap(v);
                    String name = str(w.get("name"));
                    if (!name.isEmpty() && !"null".equals(name) && Boolean.TRUE.equals(w.get("loaded"))) {
                        names.add(name);
                    }
                }
                return List.copyOf(names);
            } catch (RuntimeException e) {
                return List.<String>of();
            }
        }).orElse(List.of());
    }

    /** Formulaire de liaison : select des PNJ Citizens, seuls les libres sont sélectionnables. */
    private String citizensLinkForm(Session session, String agentId, String npcId, List<Object> citizensRoster) {
        if (citizensRoster.isEmpty()) {
            return Ui.empty("Cliquer « Rafraîchir les PNJ Citizens » en haut de page pour lister les PNJ disponibles.");
        }
        long free = citizensRoster.stream().filter(o -> Boolean.TRUE.equals(asMap(o).get("availableForBinding"))).count();
        if (free == 0) {
            return Ui.empty("Aucun PNJ Citizens libre — tous sont déjà liés à une définition.");
        }
        StringBuilder sb = new StringBuilder(formStart(session, agentId, "npc.citizens.link", "/npcs", ""));
        sb.append("<input type=\"hidden\" name=\"npc_id\" value=\"").append(Http.esc(npcId)).append("\">");
        sb.append("<label>PNJ Citizens</label><select name=\"citizens_id\">");
        for (Object o : citizensRoster) {
            Map<String, Object> c = asMap(o);
            String cid = str(c.get("numericId"));
            String cname = MiniText.plain(str(c.get("name")));
            boolean avail = Boolean.TRUE.equals(c.get("availableForBinding"));
            String linked = str(c.get("linkedNpcId"));
            sb.append("<option value=\"").append(Http.esc(cid)).append("\"").append(avail ? "" : " disabled")
                    .append(">#").append(Http.esc(cid)).append(" — ")
                    .append(Http.esc(cname.isEmpty() ? "(sans nom)" : cname));
            if (!avail && !linked.isEmpty() && !"null".equals(linked)) {
                sb.append("  ·  déjà lié à ").append(Http.esc(linked));
            }
            sb.append("</option>");
        }
        sb.append("</select>");
        sb.append("<p class=\"form-text\">Associe la fiche RPGQuest « " + Http.esc(npcId) + " » au PNJ Citizens "
                + "sélectionné. Le PNJ n'est ni créé, ni déplacé. L'association peut être défaite.</p>");
        sb.append(mutationConsent("npc.citizens.link", "", ""));
        sb.append("<button class=\"btn\" type=\"submit\">Associer ce PNJ Citizens</button></form>");
        latestForPlayer(agentId, "npc.citizens.link", "").ifPresent(row -> sb.append(resultLine("Dernière liaison", row)));
        return sb.toString();
    }

    private static String citizensNameFor(List<Object> roster, String numericId) {
        for (Object o : roster) {
            Map<String, Object> c = asMap(o);
            if (numericId.equals(str(c.get("numericId")))) {
                return str(c.get("name"));
            }
        }
        return "";
    }

    private static String npcStateBadge(String state) {
        String s = state == null ? "" : state;
        return switch (s) {
            case "LINKED" -> Ui.pill("lié", "success", "✓");
            case "NOT_LINKED" -> Ui.pill("à lier", "pending", "○");
            case "DISABLED" -> Ui.pill("désactivé", "expired", "⧖");
            case "CITIZENS_ORPHAN" -> Ui.pill("Citizens orphelin", "failed", "✕");
            case "CITIZENS_ONLY" -> Ui.pill("Citizens seul", "pending", "○");
            case "UNDEFINED_REFERENCE" -> Ui.pill("non défini", "failed", "✕");
            case "BROKEN" -> Ui.pill("cassé", "failed", "✕");
            default -> Ui.badge(s.isEmpty() ? "?" : s);
        };
    }

    private static String cleanNull(String value) {
        return value == null || "null".equals(value) ? "" : value;
    }

    // ================================================================================
    //  Dialogues (V1 lecture + squelette de création — base d'un futur éditeur)
    // ================================================================================

    public String dialogues(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder();
        boolean canWrite = perms.can(session.effective(), Permission.DIALOGUE_WRITE);
        sb.append(Ui.pageHeader("dialogues", "Dialogues",
                "Lecture structurée des dialogues à embranchements (dialogues/<id>.yml) : nœuds, "
                        + "choix, actions et conditions typées, relations PNJ / quêtes, diagnostics.",
                (canWrite ? Ui.primaryLink("/dialogues/new", "plus", "Créer un dialogue") : "")
                        + docLink("dialogues", "Documentation : dialogues")));
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        sb.append(agentPicker(agentId, "/dialogues", ""));

        sb.append("<h2>Catalogue</h2>");
        String dlgBar = compactRefresh(session, agentId, "dialogue.list", "Dialogues", "btn-outline-primary", "/dialogues");
        if (perms.can(session.effective(), Permission.CONTENT_READ)) {
            dlgBar += compactRefresh(session, agentId, "quest.list", "Quêtes", "btn-outline-secondary", "/dialogues");
        }
        if (perms.can(session.effective(), Permission.NPC_READ)) {
            dlgBar += compactRefresh(session, agentId, "npc.list", "PNJ", "btn-outline-secondary", "/dialogues");
        }
        sb.append(listCatbar("Catalogue", dlgBar));

        Map<String, String> questTitles = titleIndex(
                latestDetails(agentId, "quest.list").map(x -> asList(x.get("quests"))).orElse(List.of()), "id", "title");

        Optional<Map<String, Object>> details = latestDetails(agentId, "dialogue.list");
        // Issue #164 : si le relevé existe mais n'est pas relisible, on le DIT — sans quoi la page
        // se contentait d'afficher la source et laissait croire que le serveur n'a pas ces dialogues.
        unreadableReport(agentId, "dialogue.list")
                .ifPresent(msg -> sb.append(Ui.banner("warn", Http.esc(msg))));
        List<Object> runtimeDialogues = details.map(x -> asList(x.get("dialogues"))).orElse(List.of());
        List<Object> loadIssues = details.map(x -> asList(x.get("loadIssues"))).orElse(List.of());
        List<Object> missing = details.map(x -> asList(x.get("declaredButMissing"))).orElse(List.of());
        List<MergedRow> merged = mergeDialogueRows(agentId, runtimeDialogues);

        // Un dialogue « déclaré mais absent » côté serveur qui existe déjà dans la source éditable
        // n'est pas une erreur : il est en attente de rechargement (comme « Source uniquement »).
        java.util.Set<String> sourceKeys = new java.util.HashSet<>();
        for (SourceCatalog.DialogueSource ds : sourceCatalog.dialogues()) {
            if (!ds.plainId().isEmpty()) {
                sourceKeys.add(ds.plainId());
            }
        }

        if (!loadIssues.isEmpty() || !missing.isEmpty()) {
            sb.append("<div class=\"dlg-global-diag\">");
            for (Object o : loadIssues) {
                Map<String, Object> m = asMap(o);
                sb.append(DiagnosticHelp.render("DIALOGUE_LOAD_ISSUE", "error", str(m.get("message")),
                        str(m.get("file")), str(m.get("message")), ""));
            }
            for (Object o : missing) {
                Map<String, Object> m = asMap(o);
                boolean inSource = sourceKeys.contains(dialoguePlainKey(str(m.get("dialogueId"))));
                if (inSource) {
                    sb.append(DiagnosticHelp.render("DIALOGUE_DECLARED_MISSING", "info",
                            "Le dialogue « " + Http.esc(str(m.get("dialogueId"))) + " » est enregistré dans la source "
                                    + "mais pas encore chargé par le serveur DEV — rechargement en attente.",
                            str(m.get("npcId")), str(m.get("dialogueId")), ""));
                } else {
                    sb.append(DiagnosticHelp.render("DIALOGUE_DECLARED_MISSING", "error", "",
                            str(m.get("npcId")), str(m.get("dialogueId")), ""));
                }
            }
            sb.append("</div>");
        }

        if (details.isPresent()) {
            Map<String, Object> d = details.get();
            sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Résumé serveur</span> ")
                    .append(Http.esc(str(d.get("total")))).append(" dialogue(s) chargé(s) · ")
                    .append(Http.esc(str(d.get("nodeTotal")))).append(" nœud(s) · ")
                    .append(Http.esc(str(d.get("withWarnings")))).append(" avec avertissement · ")
                    .append(loadIssues.size()).append(" fichier(s) rejeté(s)</p>");
        }

        if (canWrite) {
            sb.append("<p class=\"form-text dlg-editnote\">Édition guidée disponible pour les nœuds, les textes et "
                    + "les choix simples des dialogues chargés par le serveur. Les opérations avancées (conditions, "
                    + "actions de quête) restent limitées. ")
                    .append(docLink("dialogues", "En savoir plus sur les limites de l'éditeur")).append("</p>");
            sb.append("<details class=\"tech-detail\"><summary>Détails techniques de l'éditeur</summary>"
                    + "<p class=\"muted\">La création écrit <code>dialogues/&lt;id&gt;.yml</code> dans la source au "
                    + "<strong>format canonique</strong> du panel (les commentaires et la mise en forme d'origine ne "
                    + "sont pas conservés). L'édition guidée d'un dialogue déjà chargé le re-parse et le recharge ; "
                    + "en cas d'échec, le contenu d'origine est restauré.</p></details>");
        }

        if (merged.isEmpty()) {
            sb.append(Ui.empty("Aucun catalogue chargé — aucun dialogue dans la source éditable ni dans le dernier "
                    + "relevé du serveur. Créer un dialogue, ou cliquer sur « Rafraîchir »."));
        } else {
            if (sourceCatalog.available()) {
                sb.append(catalogOriginLegend("dialogue"));
                // Un dialogue démarre des quêtes et est cité par des PNJ : trois familles liées.
                sb.append(reloadHint(session, agentId, merged, "dialogue", "npcs", "quests", "dialogues"));
            }
            sb.append(listControls("dialogues", "Rechercher un dialogue\u2026",
                    filterBtn("", "Tous", true) + filterBtn("linked", "Liés", false)
                            + filterBtn("unlinked", "Non liés", false) + filterBtn("warn", "À vérifier", false)));
            sb.append("<p class=\"count-note\" data-count-note data-noun=\"dialogue\">" + merged.size() + " dialogue(s)</p>");
            sb.append("<div class=\"accordion npc-accordion\" id=\"dialogues-accordion\">");
            int di = 0;
            for (MergedRow mr : merged) {
                sb.append(renderDialogueAccordionItem(mr.data(), di++, questTitles, session, agentId, canWrite, mr.state()));
            }
            sb.append("</div>");
        }
        return sb.toString();
    }

    /** Clé « nue » d'un dialogue (namespace {@code rpgquest:} retiré) — clé de fusion source ↔ runtime. */
    private static String dialoguePlainKey(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("rpgquest:") ? s.substring("rpgquest:".length()) : s;
    }

    /**
     * Fusionne le dernier relevé {@code dialogue.list} du serveur avec les fichiers
     * {@code dialogues/*.yml} de la source éditable (issue #145, même principe que {@link #mergeQuestRows}).
     * Clé de fusion : l'id « nu ». Ordre : dialogues runtime puis dialogues « source uniquement ».
     */
    private List<MergedRow> mergeDialogueRows(String agentId, List<Object> runtimeDialogues) {
        Map<String, Map<String, Object>> runtimeById = new LinkedHashMap<>();
        for (Object o : runtimeDialogues) {
            Map<String, Object> m = asMap(o);
            String key = dialoguePlainKey(str(m.get("id")));
            if (key.isEmpty()) {
                key = dialoguePlainKey(str(m.get("key")));
            }
            if (!key.isEmpty()) {
                runtimeById.putIfAbsent(key, m);
            }
        }
        Map<String, SourceCatalog.DialogueSource> sourceById = new LinkedHashMap<>();
        for (SourceCatalog.DialogueSource ds : sourceCatalog.dialogues()) {
            if (!ds.plainId().isEmpty()) {
                sourceById.putIfAbsent(ds.plainId(), ds);
            }
        }
        boolean srcKnown = sourceCatalog.available();
        List<MergedRow> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : runtimeById.entrySet()) {
            CatalogState st = !srcKnown ? CatalogState.SYNCED
                    : sourceById.containsKey(e.getKey()) ? CatalogState.SYNCED : CatalogState.RUNTIME_ONLY;
            out.add(new MergedRow(e.getValue(), st));
        }
        for (Map.Entry<String, SourceCatalog.DialogueSource> e : sourceById.entrySet()) {
            if (!runtimeById.containsKey(e.getKey())) {
                out.add(new MergedRow(sourceDialogueRow(agentId, e.getValue()), CatalogState.SOURCE_ONLY));
            }
        }
        return out;
    }

    /** Projette un dialogue relu de la source dans la forme d'une ligne {@code dialogue.list} structurée. */
    private Map<String, Object> sourceDialogueRow(String agentId, SourceCatalog.DialogueSource ds) {
        com.lodygames.rpgquest.panel.content.DialogueDraft d = ds.draft();
        String plain = ds.plainId();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "rpgquest:" + plain);
        m.put("key", plain);
        m.put("startNodeId", d.start == null ? "" : d.start);
        m.put("linkedNpcIds", npcIdsUsingDialogue(agentId, plain));
        m.put("nodeCount", d.nodes.size());
        m.put("choiceCount", d.choiceCount());
        m.put("referencedQuestIds", List.of());
        m.put("startsQuestIds", List.of());
        List<Object> warnings = new ArrayList<>();
        for (com.lodygames.rpgquest.panel.content.Diagnostic diag
                : com.lodygames.rpgquest.panel.content.DialogueValidator.validate(d)) {
            Map<String, Object> wm = new LinkedHashMap<>();
            wm.put("code", "DIALOGUE_SOURCE_ISSUE");
            wm.put("severity", diag.level() == com.lodygames.rpgquest.panel.content.Diagnostic.Level.ERROR
                    ? "error" : diag.level() == com.lodygames.rpgquest.panel.content.Diagnostic.Level.WARNING
                    ? "warning" : "info");
            wm.put("message", (diag.field() == null || diag.field().isBlank() ? "" : diag.field() + " : ") + diag.message());
            warnings.add(wm);
        }
        if (!ds.parseOk()) {
            Map<String, Object> wm = new LinkedHashMap<>();
            wm.put("code", "DIALOGUE_SOURCE_ISSUE");
            wm.put("severity", "warning");
            wm.put("message", "Le fichier source ne se relit pas entièrement — vérifier sa syntaxe.");
            warnings.add(wm);
        }
        m.put("warnings", warnings);
        List<Object> nodes = new ArrayList<>();
        for (com.lodygames.rpgquest.panel.content.DialogueDraft.Node n : d.nodes) {
            Map<String, Object> nm = new LinkedHashMap<>();
            nm.put("id", n.id);
            nm.put("speaker", n.speaker);
            nm.put("text", n.text);
            nm.put("start", n.id != null && n.id.equals(d.start));
            nm.put("reachable", true);
            List<Object> choices = new ArrayList<>();
            for (com.lodygames.rpgquest.panel.content.DialogueDraft.Choice c : n.choices) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("text", c.text);
                cm.put("nextNodeId", c.next == null ? "" : c.next);
                List<Object> acts = new ArrayList<>();
                if (c.close) {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("kind", "CLOSE");
                    a.put("target", "");
                    a.put("value", "");
                    a.put("raw", "CLOSE");
                    acts.add(a);
                }
                cm.put("actions", acts);
                cm.put("conditions", List.of());
                choices.add(cm);
            }
            nm.put("choices", choices);
            nodes.add(nm);
        }
        m.put("nodes", nodes);
        return m;
    }

    /** Ids de PNJ (dernier {@code npc.list}) dont la définition déclare ce dialogue (id nu). */
    private List<Object> npcIdsUsingDialogue(String agentId, String plainDialogueId) {
        List<Object> out = new ArrayList<>();
        for (Object o : latestDetails(agentId, "npc.list").map(d -> asList(d.get("npcs"))).orElse(List.of())) {
            Map<String, Object> n = asMap(o);
            if (dialoguePlainKey(str(n.get("definedDialogueId"))).equals(plainDialogueId)
                    || dialoguePlainKey(str(n.get("dialogueId"))).equals(plainDialogueId)) {
                out.add(str(n.get("id")));
            }
        }
        return out;
    }

    /**
     * Une ligne d'accordion de dialogue : en-tête = synthèse (nom lisible, id technique, compteurs,
     * état), corps = sections repliées (Résumé / PNJ / Quêtes / Diagnostics / Graphe / Actions). Le
     * graphe des nœuds n'est PAS ouvert par défaut. Les avertissements du moteur sont rendus par
     * {@link DiagnosticHelp} (message humain, conséquence, action, lien doc précis).
     */
    private String renderDialogueAccordionItem(Map<String, Object> dg, int idx, Map<String, String> questTitles,
                                               Session session, String agentId, boolean canWrite, CatalogState state) {
        String id = str(dg.get("id"));
        String key = str(dg.get("key"));
        String start = str(dg.get("startNodeId"));
        List<Object> linked = asList(dg.get("linkedNpcIds"));
        List<Object> warnings = asList(dg.get("warnings"));
        List<Object> nodes = asList(dg.get("nodes"));
        List<Object> refQuests = asList(dg.get("referencedQuestIds"));
        List<Object> startsQuests = asList(dg.get("startsQuestIds"));
        List<String> nodeIds = dialogueNodeIds(nodes);
        String slug = "dlg-" + idx + "-" + id.replaceAll("[^a-z0-9_-]", "-");
        String human = MiniText.prettifyId(key.isEmpty() ? id : key);
        boolean sourceOnly = state == CatalogState.SOURCE_ONLY;
        // L'édition guidée (nœuds / choix) passe par des actions agent qui ciblent un dialogue
        // *chargé* par le serveur : elle n'a pas de sens pour un dialogue « Source uniquement ».
        boolean canEdit = canWrite && !sourceOnly;

        boolean anyErr = warnings.stream().anyMatch(w -> "error".equals(str(asMap(w).get("severity"))));
        boolean anyWarn = !warnings.isEmpty();
        String cat = (linked.isEmpty() ? "unlinked" : "linked") + (anyWarn ? " warn" : "") + (anyErr ? " err" : "");
        String ftext = Http.esc(id + " " + key + " " + human + " " + catalogStateKeywords(state) + " "
                + String.join(" ", linked.stream().map(x -> str(x)).toList()));

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"accordion-item npc-item\" data-filter-item=\"dialogues\" data-filter-cat=\"")
                .append(cat).append("\" data-filter-text=\"").append(ftext)
                .append("\" data-res-id=\"").append(Http.esc(id)).append("\">");
        sb.append("<h3 class=\"accordion-header\">");
        sb.append("<button class=\"accordion-button collapsed npc-head\" type=\"button\" data-bs-toggle=\"collapse\" "
                + "data-bs-target=\"#").append(slug).append("\" aria-expanded=\"false\" aria-controls=\"")
                .append(slug).append("\">");
        sb.append("<span class=\"npc-head-main\"><span class=\"npc-name\">").append(Http.esc(human))
                .append("</span><code class=\"tid npc-id\">").append(Http.esc(id)).append("</code></span>");
        sb.append("<span class=\"npc-head-badges\">");
        sb.append("<span class=\"badge text-bg-secondary\">").append(Http.esc(str(dg.get("nodeCount"))))
                .append(" nœuds</span>");
        sb.append("<span class=\"badge text-bg-secondary\">").append(Http.esc(str(dg.get("choiceCount"))))
                .append(" choix</span>");
        if (linked.isEmpty()) {
            sb.append("<span class=\"badge text-bg-warning\">sans PNJ</span>");
        }
        String stateBadge = switch (state) {
            case SOURCE_ONLY -> "<span class=\"badge text-bg-info\" title=\"Ce dialogue est enregistré dans la "
                    + "source mais pas encore chargé par le serveur DEV.\">Source uniquement</span>";
            case RUNTIME_ONLY -> "<span class=\"badge text-bg-warning\" title=\"Ce dialogue est chargé par le "
                    + "serveur mais absent de la source éditable du Control Panel.\">Hors source</span>";
            case SYNCED -> "";
        };
        sb.append(stateBadge);
        sb.append(dialogueHealthPill(warnings));
        sb.append("</span></button></h3>");

        sb.append("<div id=\"").append(slug).append("\" class=\"accordion-collapse collapse\" ")
                .append("data-bs-parent=\"#dialogues-accordion\"><div class=\"accordion-body npc-detail\">");

        if (sourceOnly) {
            sb.append("<p class=\"muted\">").append(Icons.icon("history"))
                    .append("Dialogue enregistré dans la source mais pas encore chargé par le serveur DEV. "
                            + "Il sera pris en compte en jeu au prochain rechargement du contenu RPGQuest — "
                            + "l'édition et l'activation en jeu restent deux étapes distinctes.</p>");
        } else if (state == CatalogState.RUNTIME_ONLY) {
            sb.append("<p class=\"muted\">").append(Icons.icon("warning"))
                    .append("Chargé par le serveur mais introuvable dans la source éditable "
                            + "(fichier absent, supprimé ou renommé).</p>");
        }

        // ---- RÉSUMÉ ----
        sb.append(detailSection("book", "Résumé"));
        sb.append("<dl class=\"npc-dl\">");
        dlRow(sb, "Nom", Http.esc(human));
        dlRow(sb, "ID technique", Ui.id(id));
        dlRow(sb, "Nœud de départ", Ui.id(start));
        dlRow(sb, "Nœuds / choix", Http.esc(str(dg.get("nodeCount"))) + " / " + Http.esc(str(dg.get("choiceCount"))));
        sb.append("</dl>");

        // ---- PNJ ----
        sb.append(detailSection("npc", "PNJ"));
        if (linked.isEmpty()) {
            sb.append("<p class=\"muted npc-content-empty\">Aucun PNJ RPGQuest n'utilise ce dialogue.</p>");
        } else {
            sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Utilisé par</span> ");
            sb.append(linked.stream().map(n -> Ui.id(str(n))).reduce((a, b) -> a + " " + b).orElse(""));
            sb.append(" <a class=\"btn btn-sm btn-outline-secondary\" href=\"/npcs\">")
                    .append(Icons.icon("open")).append("PNJ</a></p>");
        }

        // ---- QUÊTES ----
        if (!startsQuests.isEmpty() || !refQuests.isEmpty()) {
            sb.append(detailSection("target", "Quêtes"));
            if (!startsQuests.isEmpty()) {
                sb.append(Ui.metaLine("Démarre", referencedQuests(startsQuests, questTitles)));
            }
            if (!refQuests.isEmpty()) {
                sb.append(Ui.metaLine("Référencées", referencedQuests(refQuests, questTitles)));
            }
        }

        // ---- DIAGNOSTICS ----
        sb.append(detailSection("warning", "Diagnostics"));
        if (warnings.isEmpty()) {
            sb.append("<p class=\"muted npc-diag-ok\">").append(Icons.icon("check")).append("Aucune anomalie.</p>");
        } else {
            List<Map<String, Object>> sorted = new ArrayList<>();
            for (Object w : warnings) {
                sorted.add(asMap(w));
            }
            sorted.sort(java.util.Comparator.comparingInt(m -> severityRank(str(m.get("severity")))));
            for (Map<String, Object> wm : sorted) {
                sb.append(DiagnosticHelp.render(str(wm.get("code")), str(wm.get("severity")),
                        str(wm.get("message")), human, "", ""));
            }
        }

        // ---- GRAPHE (replié par défaut) ----
        sb.append(detailSection("dialogues", "Graphe des nœuds"));
        sb.append("<details class=\"dlg-graph-wrap\"><summary>Afficher le graphe — ").append(nodes.size())
                .append(" nœud(s)</summary><div class=\"dlg-graph\">");
        for (Object o : nodes) {
            sb.append(dialogueNodeCard(asMap(o), id, nodeIds, questTitles, session, agentId, canEdit));
        }
        sb.append("</div></details>");

        // ---- ACTIONS ----
        if (canEdit) {
            sb.append(detailSection("target", "Actions"));
            sb.append(dialogueNodeCreateForm(session, agentId, id));
        } else if (canWrite && sourceOnly) {
            sb.append(detailSection("target", "Actions"));
            sb.append("<p class=\"muted\">L'édition guidée des nœuds et des choix sera disponible une fois ce "
                    + "dialogue chargé par le serveur DEV. En attendant, le fichier <code>dialogues/")
                    .append(Http.esc(key)).append(".yml</code> reste modifiable à la main dans la source.</p>");
        }

        sb.append("</div></div></div>");
        return sb.toString();
    }

    /** Pastille d'état global d'un dialogue à partir de la sévérité la plus haute de ses warnings. */
    private static String dialogueHealthPill(List<Object> warnings) {
        int worst = 3; // 1=error 2=warning 3=info/none
        for (Object w : warnings) {
            worst = Math.min(worst, severityRank(str(asMap(w).get("severity"))));
        }
        if (warnings.isEmpty()) {
            return Ui.pill("cohérent", "success", "✓");
        }
        return switch (worst) {
            case 1 -> Ui.pill(warnings.size() + " anomalie(s)", "failed", "✕");
            case 2 -> Ui.pill(warnings.size() + " avertissement(s)", "pending", "!");
            default -> Ui.pill(warnings.size() + " info(s)", "neutral", "i");
        };
    }

    private static int severityRank(String severity) {
        return switch (severity == null ? "" : severity.toLowerCase(java.util.Locale.ROOT)) {
            case "error" -> 1;
            case "warning" -> 2;
            default -> 3;
        };
    }

    /** Une carte nœud : identité + rôle, locuteur, texte, choix lisibles, et l'édition guidée. */
    private String dialogueNodeCard(Map<String, Object> n, String dialogueId, List<String> nodeIds,
                                    Map<String, String> questTitles, Session session, String agentId, boolean canWrite) {
        String nodeId = str(n.get("id"));
        boolean isStart = Boolean.TRUE.equals(n.get("start"));
        boolean reachable = Boolean.TRUE.equals(n.get("reachable"));
        List<Object> choices = asList(n.get("choices"));

        StringBuilder sb = new StringBuilder("<div class=\"dlg-node")
                .append(isStart ? " start" : "").append(reachable ? "" : " unreachable").append("\">");
        sb.append("<div class=\"dlg-node-head\">").append(Ui.id(nodeId));
        if (isStart) {
            sb.append(Ui.pill("départ", "success", "▶"));
        }
        if (!reachable) {
            sb.append(Ui.pill("inaccessible", "failed", "✕"));
        }
        sb.append("</div>");
        sb.append("<p class=\"dlg-speaker\">").append(Http.esc(str(n.get("speaker")))).append("</p>");
        sb.append("<p class=\"dlg-text\">").append(MiniText.html(str(n.get("text")))).append("</p>");

        if (!choices.isEmpty()) {
            sb.append("<ol class=\"dlg-choices\">");
            for (int i = 0; i < choices.size(); i++) {
                Map<String, Object> cm = asMap(choices.get(i));
                String next = str(cm.get("nextNodeId"));
                List<Object> acts = asList(cm.get("actions"));
                List<Object> conds = asList(cm.get("conditions"));
                sb.append("<li><span class=\"dlg-choice-text\">« ").append(MiniText.html(str(cm.get("text"))))
                        .append(" »</span>");
                if (!next.isEmpty()) {
                    sb.append(" <span class=\"dlg-arrow\">→ ").append(Http.esc(next)).append("</span>");
                } else if (choiceCloses(cm)) {
                    sb.append(" <span class=\"muted\">(ferme le dialogue)</span>");
                }
                for (Object a : acts) {
                    Map<String, Object> am = asMap(a);
                    if (!"CLOSE".equals(str(am.get("kind")))) {
                        sb.append(" <span class=\"dlg-fx\">").append(Http.esc(dialogueEffectLabel(str(am.get("kind")),
                                str(am.get("target")), str(am.get("value")), questTitles))).append("</span>");
                    }
                }
                for (Object c : conds) {
                    Map<String, Object> condm = asMap(c);
                    String label = (Boolean.TRUE.equals(condm.get("negated")) ? "non " : "")
                            + dialogueEffectLabel(str(condm.get("kind")), str(condm.get("target")),
                            str(condm.get("value")), questTitles);
                    sb.append(" <span class=\"dlg-cond\">si ").append(Http.esc(label)).append("</span>");
                }
                if (canWrite) {
                    sb.append(dialogueChoiceEditForms(session, agentId, dialogueId, nodeId, i, cm, next, nodeIds));
                }
                sb.append("</li>");
            }
            sb.append("</ol>");
        }

        if (canWrite) {
            sb.append("<div class=\"dlg-node-edit\">");
            sb.append(dialogueNodeUpdateForm(session, agentId, dialogueId, nodeId,
                    str(n.get("speaker")), str(n.get("text"))));
            sb.append(dialogueChoiceAddForm(session, agentId, dialogueId, nodeId, nodeIds));
            sb.append("</div>");
        }
        return sb.append("</div>").toString();
    }

    private static List<String> dialogueNodeIds(List<Object> nodes) {
        List<String> ids = new java.util.ArrayList<>();
        for (Object o : nodes) {
            String v = str(asMap(o).get("id"));
            if (!v.isEmpty()) {
                ids.add(v);
            }
        }
        return ids;
    }

    /** Un choix « simple » (éditable en phase 1) : aucune condition, aucune action hors {@code CLOSE}. */
    private static boolean choiceIsSimple(Map<String, Object> choice) {
        if (!asList(choice.get("conditions")).isEmpty()) {
            return false;
        }
        for (Object a : asList(choice.get("actions"))) {
            if (!"CLOSE".equals(str(asMap(a).get("kind")))) {
                return false;
            }
        }
        return true;
    }

    private static boolean choiceCloses(Map<String, Object> choice) {
        for (Object a : asList(choice.get("actions"))) {
            if ("CLOSE".equals(str(asMap(a).get("kind")))) {
                return true;
            }
        }
        return asList(choice.get("actions")).isEmpty() && str(choice.get("nextNodeId")).isEmpty();
    }

    /** {@code <select name="next_node_id">} des nœuds existants ; {@code selected} pré-sélectionné. */
    private static String nodeTargetSelect(List<String> nodeIds, String selected) {
        StringBuilder sb = new StringBuilder("<select name=\"next_node_id\">");
        sb.append("<option value=\"\"").append(selected.isEmpty() ? " selected" : "")
                .append(">— (choisir un nœud) —</option>");
        for (String nid : nodeIds) {
            sb.append("<option value=\"").append(Http.esc(nid)).append("\"")
                    .append(nid.equals(selected) ? " selected" : "").append(">").append(Http.esc(nid)).append("</option>");
        }
        return sb.append("</select>").toString();
    }

    private String dialogueNodeUpdateForm(Session session, String agentId, String dialogueId, String nodeId,
                                          String speaker, String text) {
        StringBuilder sb = new StringBuilder("<details class=\"dlg-edit\"><summary>Modifier ce nœud (locuteur / texte)</summary>");
        sb.append(formStart(session, agentId, "dialogue.node.update", "/dialogues", ""));
        sb.append("<input type=\"hidden\" name=\"dialogue_id\" value=\"").append(Http.esc(dialogueId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"node_id\" value=\"").append(Http.esc(nodeId)).append("\">");
        sb.append("<label>Locuteur</label><input type=\"text\" name=\"speaker\" value=\"")
                .append(Http.esc(speaker)).append("\" maxlength=\"128\">");
        // Issue #195 : même composant que les noms de mobs — couleur au clic, styles, aperçu.
        sb.append(StyleField.render("text", "dlg-node-" + Http.esc(nodeId).replaceAll("[^a-zA-Z0-9_-]", "-"),
                "Texte du nœud", text, false,
                "Réplique affichée au joueur. Choisir couleur et styles ci-dessus — aucun code à "
                + "écrire. Un texte déjà composé de <strong>plusieurs styles</strong> est conservé "
                + "tel quel et n'est jamais simplifié sans action explicite."));
        sb.append(mutationConsent("dialogue.node.update", "",
                "Met à jour le locuteur et le texte de ce nœud. Les choix du nœud sont conservés. Modification réversible."));
        sb.append("<button class=\"btn\" type=\"submit\">Enregistrer le nœud</button></form>");
        return sb.append("</details>").toString();
    }

    private String dialogueChoiceAddForm(Session session, String agentId, String dialogueId, String nodeId,
                                         List<String> nodeIds) {
        StringBuilder sb = new StringBuilder("<details class=\"dlg-edit\"><summary>Ajouter un choix à ce nœud</summary>");
        sb.append(formStart(session, agentId, "dialogue.choice.add", "/dialogues", ""));
        sb.append("<input type=\"hidden\" name=\"dialogue_id\" value=\"").append(Http.esc(dialogueId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"node_id\" value=\"").append(Http.esc(nodeId)).append("\">");
        sb.append("<label>Texte du choix</label><input type=\"text\" name=\"choice_text\" maxlength=\"512\" "
                + "placeholder=\"D'accord\">");
        sb.append("<label>Nœud cible</label>").append(nodeTargetSelect(nodeIds, ""));
        sb.append("<label class=\"inline\"><input type=\"checkbox\" name=\"close\" value=\"true\"> "
                + "ou : ce choix termine le dialogue (laisser « nœud cible » vide)</label>");
        sb.append(mutationConsent("dialogue.choice.add", "",
                "Ajoute un choix simple (sans condition ni action de quête). Réversible : le choix peut être supprimé."));
        sb.append("<button class=\"btn\" type=\"submit\">Ajouter le choix</button></form>");
        return sb.append("</details>").toString();
    }

    /** Sous un choix : édition/suppression si « simple », sinon une note « édition avancée à venir ». */
    private String dialogueChoiceEditForms(Session session, String agentId, String dialogueId, String nodeId,
                                           int index, Map<String, Object> choice, String next, List<String> nodeIds) {
        if (!choiceIsSimple(choice)) {
            return " <span class=\"muted dlg-adv\">— actions / conditions avancées : édition prévue dans une phase "
                    + "ultérieure</span>";
        }
        String sourceText = str(choice.get("text")); // source MiniMessage brute (jamais échappée deux fois)
        StringBuilder sb = new StringBuilder("<details class=\"dlg-edit dlg-edit-choice\"><summary>Modifier / supprimer</summary>");
        // Modifier
        sb.append(formStart(session, agentId, "dialogue.choice.update", "/dialogues", ""));
        sb.append("<input type=\"hidden\" name=\"dialogue_id\" value=\"").append(Http.esc(dialogueId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"node_id\" value=\"").append(Http.esc(nodeId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"choice_index\" value=\"").append(index).append("\">");
        sb.append("<label>Texte du choix</label><input type=\"text\" name=\"choice_text\" maxlength=\"512\" value=\"")
                .append(Http.esc(sourceText)).append("\">");
        sb.append("<label>Nœud cible</label>").append(nodeTargetSelect(nodeIds, next));
        sb.append("<label class=\"inline\"><input type=\"checkbox\" name=\"close\" value=\"true\"")
                .append(next.isEmpty() ? " checked" : "").append("> ce choix termine le dialogue</label>");
        sb.append(mutationConsent("dialogue.choice.update", "",
                "Met à jour le texte et la cible de ce choix. Modification réversible."));
        sb.append("<button class=\"btn\" type=\"submit\">Enregistrer le choix</button></form>");
        // Supprimer
        sb.append(formStart(session, agentId, "dialogue.choice.delete", "/dialogues", ""));
        sb.append("<input type=\"hidden\" name=\"dialogue_id\" value=\"").append(Http.esc(dialogueId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"node_id\" value=\"").append(Http.esc(nodeId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"choice_index\" value=\"").append(index).append("\">");
        sb.append(mutationConsent("dialogue.choice.delete",
                "Supprimer définitivement ce choix (impossible si c'est le dernier choix du nœud).", ""));
        sb.append("<button class=\"btn secondary\" type=\"submit\">Supprimer ce choix</button></form>");
        return sb.append("</details>").toString();
    }

    private String dialogueNodeCreateForm(Session session, String agentId, String dialogueId) {
        StringBuilder sb = new StringBuilder("<details class=\"dlg-edit dlg-add-node\"><summary>Ajouter un nœud à ce dialogue</summary>");
        sb.append("<p class=\"faint\" style=\"font-size:12px\">Crée un nœud simple avec un unique choix « fermer ». "
                + "Il n'est relié à aucun choix existant (nœud orphelin) : ajouter ensuite un choix « → " + Http.esc(
                MiniText.prettifyId(dialogueId)) + " » vers lui depuis un autre nœud.</p>");
        sb.append(formStart(session, agentId, "dialogue.node.create", "/dialogues", ""));
        sb.append("<input type=\"hidden\" name=\"dialogue_id\" value=\"").append(Http.esc(dialogueId)).append("\">");
        sb.append("<label>Id du nœud (minuscules, « _ - »)</label><input type=\"text\" name=\"node_id\" "
                + "pattern=\"[a-z0-9_][a-z0-9_-]{0,63}\" placeholder=\"farewell\">");
        sb.append("<label>Locuteur</label><input type=\"text\" name=\"speaker\" maxlength=\"128\" placeholder=\"Garde\">");
        sb.append(StyleField.render("text", "dlg-newnode-text", "Texte du nœud", "", false,
                "Réplique affichée au joueur. Choisir couleur et styles ci-dessus — aucun code à écrire."));
        sb.append(mutationConsent("dialogue.node.create", "",
                "Ajoute un nœud simple au dialogue. Réversible."));
        sb.append("<button class=\"btn\" type=\"submit\">Ajouter le nœud</button></form>");
        return sb.append("</details>").toString();
    }

    /** Libellé lisible d'une action/condition typée de dialogue (titre humain de quête si connu). */
    private static String dialogueEffectLabel(String kind, String target, String value, Map<String, String> questTitles) {
        String t = target == null ? "" : target;
        return switch (kind) {
            case "START_QUEST", "ADVANCE_QUEST", "TURN_IN_QUEST", "QUEST_STATE" -> {
                String title = questTitles.get(t.toLowerCase(java.util.Locale.ROOT));
                String verb = switch (kind) {
                    case "START_QUEST" -> "démarre";
                    case "ADVANCE_QUEST" -> "avance";
                    case "TURN_IN_QUEST" -> "rend";
                    default -> "quête";
                };
                String name = title != null ? MiniText.plain(title) : MiniText.prettifyId(t);
                yield "QUEST_STATE".equals(kind) ? "quête « " + name + " » = " + value : verb + " « " + name + " »";
            }
            case "GIVE_ITEM" -> "donne " + value + "× " + MiniText.prettifyId(t);
            case "TAKE_ITEM" -> "retire " + value + "× " + MiniText.prettifyId(t);
            case "SET_VARIABLE" -> "variable " + t + " = " + value;
            case "VARIABLE_EQUALS" -> "variable " + t + " = " + value;
            case "HAS_ITEM" -> "possède " + value + "× " + MiniText.prettifyId(t);
            case "HAS_PERMISSION" -> "permission " + t;
            case "LACKS_CUSTOM_ITEM" -> "n'a pas l'objet " + t;
            case "RUN_SAFE_COMMAND" -> "commande « " + t + " »";
            case "OPEN_DIALOGUE" -> "ouvre le dialogue " + t;
            case "OPEN_MERCHANT" -> "ouvre le marchand " + t;
            case "CLOSE" -> "ferme";
            case "NO_MAIN_CLAIM" -> "sans claim";
            case "HAS_MAIN_CLAIM" -> "a un claim";
            default -> kind;
        };
    }

    // ================================================================================
    //  Fragments partagés
    // ================================================================================

    private interface RowRenderer {
        String render(Map<String, Object> row);
    }

    private String playerStatusSection(Session session, String agentId, String player, String page,
                                       String type, String listKey, RowRenderer renderer) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>État d'un joueur</h2>");
        sb.append("<form method=\"get\" action=\"").append(page).append("\">")
                .append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId)).append("\">")
                .append("<label>Joueur (nom ou UUID)</label>")
                .append("<input type=\"text\" name=\"player\" value=\"").append(Http.esc(player))
                .append("\" placeholder=\"LoDyMcFly\">")
                .append("<button class=\"btn\" type=\"submit\">Sélectionner</button></form>");
        if (player.isEmpty()) {
            sb.append(Ui.empty("Sélectionner un joueur pour voir son état et agir dessus."));
            return sb.toString();
        }
        sb.append(readForm(session, agentId, type, page, player, "Rafraîchir l'état de " + player));
        Optional<AgentActionRow> row = latestForPlayer(agentId, type, player);
        if (row.isPresent() && row.get().status() == AgentActionStatus.SUCCESS) {
            List<Object> rows = detailsOf(row.get()).map(d -> asList(d.get(listKey))).orElse(List.of());
            if (rows.isEmpty()) {
                sb.append(Ui.empty("Aucun élément à afficher pour " + player + " au dernier relevé."));
            } else {
                sb.append(Ui.tableOpen("Élément", "État", "Détail"));
                for (Object o : rows) {
                    sb.append(renderer.render(asMap(o)));
                }
                sb.append(Ui.tableClose());
            }
        } else {
            sb.append(Ui.empty("Cliquer sur « Rafraîchir l'état » pour interroger le serveur."));
        }
        return sb.toString();
    }

    private String agentPicker(String activeAgentId, String page, String player) {
        if (registry.all().size() <= 1) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<form method=\"get\" action=\"").append(page)
                .append("\" class=\"agentpicker\"><label>Cible</label><select name=\"agent\" onchange=\"this.form.submit()\">");
        for (AgentIdentity a : registry.all()) {
            sb.append("<option value=\"").append(Http.esc(a.id())).append("\"")
                    .append(a.id().equals(activeAgentId) ? " selected" : "").append(">").append(Http.esc(a.id()))
                    .append("</option>");
        }
        sb.append("</select>");
        if (!player.isEmpty()) {
            sb.append("<input type=\"hidden\" name=\"player\" value=\"").append(Http.esc(player)).append("\">");
        }
        sb.append("<noscript><button class=\"btn\" type=\"submit\">Changer</button></noscript></form>");
        return sb.toString();
    }

    /** Formulaire minimal (juste un bouton) pour une action sans paramètre autre que « player ». */
    private String actionButton(Session session, String agentId, String type, String page, String player,
                                String label, String extraHidden) {
        return formStart(session, agentId, type, page, player) + extraHidden
                + "<button class=\"btn\" type=\"submit\">" + Http.esc(label) + "</button></form>";
    }

    private String formStart(Session session, String agentId, String type, String page, String player) {
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"/agents/action\" class=\"actform\">");
        sb.append("<input type=\"hidden\" name=\"_csrf\" value=\"").append(Http.esc(session.csrfToken())).append("\">");
        sb.append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"type\" value=\"").append(Http.esc(type)).append("\">");
        sb.append("<input type=\"hidden\" name=\"return\" value=\"").append(Http.esc(page)).append("\">");
        if (player != null && !player.isEmpty()) {
            sb.append("<input type=\"hidden\" name=\"player\" value=\"").append(Http.esc(player)).append("\">");
        }
        return sb.toString();
    }

    private String confirmBox(String text) {
        return "<label class=\"inline confirm\"><input type=\"checkbox\" name=\"confirm\" value=\"true\" required> "
                + Http.esc(text) + "</label>";
    }

    /**
     * Consentement d'une mutation (issues #111 / #113 / #118). Pour une action réellement sensible
     * ou difficilement réversible (bannissement, reset, spawn d'un PNJ physique, suppression), une
     * vraie case à cocher explicite. Pour une édition de contenu normale et réversible, aucune case
     * cachée : une phrase d'information et un {@code confirm} implicite — le bouton reste
     * l'engagement explicite. La distinction vient de {@link AgentActionCatalog.Spec#sensitive()},
     * jamais d'un choix local à l'écran.
     */
    private String mutationConsent(String type, String sensitiveCheckboxText, String reversibleNote) {
        boolean sensitive = AgentActionCatalog.spec(type).map(AgentActionCatalog.Spec::sensitive).orElse(true);
        if (sensitive) {
            return confirmBox(sensitiveCheckboxText);
        }
        return "<input type=\"hidden\" name=\"confirm\" value=\"true\">"
                + (reversibleNote == null || reversibleNote.isBlank() ? ""
                        : "<p class=\"form-text confirm-note\">" + Http.esc(reversibleNote) + "</p>");
    }

    private String idSelect(String name, List<String> ids, String placeholder) {
        if (ids.isEmpty()) {
            return "<input type=\"text\" name=\"" + name + "\" placeholder=\"" + Http.esc(placeholder) + "\">";
        }
        StringBuilder sb = new StringBuilder("<select name=\"").append(name).append("\">");
        for (String id : ids) {
            sb.append("<option value=\"").append(Http.esc(id)).append("\">").append(Http.esc(id)).append("</option>");
        }
        sb.append("</select>");
        return sb.toString();
    }

    private String resultLine(String label, AgentActionRow row) {
        String text = row.status().terminal()
                ? (row.resultMessage() == null ? row.status().name() : row.resultMessage())
                : "en cours…";
        StringBuilder sb = new StringBuilder("<p class=\"resline\">").append(Ui.actionStatus(row.status()))
                .append(" <span class=\"muted\">").append(Http.esc(label)).append(" :</span> ")
                .append(Http.esc(MiniText.prettifyTokens(text)));
        List<Object> effects = detailsOf(row).map(d -> asList(d.get("effects"))).orElse(List.of());
        if (!effects.isEmpty()) {
            sb.append("<br><span class=\"muted\">").append(Http.esc(MiniText.prettifyTokens(join(effects)))).append("</span>");
        }
        return sb.append("</p>").toString();
    }

    // ---- Accès données ----------------------------------------------------------------

    private Optional<AgentIdentity> resolveAgent(Map<String, String> q) {
        String requested = q.get("agent");
        if (requested != null && !requested.isBlank()) {
            Optional<AgentIdentity> byId = registry.byId(requested.trim());
            if (byId.isPresent()) {
                return byId;
            }
        }
        if (defaultAgentId != null) {
            Optional<AgentIdentity> def = registry.byId(defaultAgentId);
            if (def.isPresent()) {
                return def;
            }
        }
        return registry.all().stream().findFirst();
    }

    private Optional<Map<String, Object>> latestDetails(String agentId, String type) {
        // Dernière action RÉUSSIE du type : une action « Rafraîchir » plus récente encore en cours
        // (ou en échec) ne doit pas vider le catalogue déjà chargé (issue affichage /stories).
        return store.latestSuccessfulActionOfType(agentId, type)
                .flatMap(this::detailsOf);
    }

    private Optional<AgentActionRow> latestForPlayer(String agentId, String type, String player) {
        // On ne filtre pas au niveau SQL sur le joueur (paramètre non indexé) : la dernière action
        // du type suffit pour un usage interactif à un opérateur.
        return store.latestActionOfType(agentId, type)
                .filter(r -> player.isEmpty() || player.equalsIgnoreCase(r.params().getOrDefault("player", "")));
    }

    private Optional<Map<String, Object>> detailsOf(AgentActionRow row) {
        if (row.resultJson() == null || row.resultJson().isBlank()) {
            return Optional.empty();
        }
        try {
            Object details = Json.parseObject(row.resultJson()).get("details");
            return details instanceof Map<?, ?> m ? Optional.of(castMap(m)) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Issue #164 : distingue « aucun relevé » de « relevé présent mais inexploitable ». Sans cette
     * distinction, un résultat {@code SUCCESS} dont le corps n'est pas relisible (cas réel : corps
     * tronqué en plein JSON par l'ancienne borne de 20 000 caractères) produisait exactement le même
     * affichage qu'un catalogue vide — l'interface concluait donc à une absence de contenu alors que
     * le serveur en avait bel et bien déclaré. Renvoie un message prêt à afficher, ou vide si tout va
     * bien.
     */
    private Optional<String> unreadableReport(String agentId, String type) {
        Optional<AgentActionRow> row = store.latestSuccessfulActionOfType(agentId, type);
        if (row.isEmpty() || detailsOf(row.get()).isPresent()) {
            return Optional.empty();
        }
        String raw = row.get().resultJson();
        if (raw == null || raw.isBlank()) {
            return Optional.of("Le dernier relevé « " + type + " » a réussi mais n'a transmis aucun "
                    + "détail exploitable. Relancer le relevé.");
        }
        boolean declaredTruncated = raw.contains("\"truncated\":true");
        return Optional.of("Le dernier relevé « " + type + " » a réussi mais son contenu n'est pas "
                + "exploitable" + (declaredTruncated ? " (trop volumineux pour être conservé)" : "")
                + " : l'affichage ci-dessous peut donc être incomplet et ne prouve pas l'absence de "
                + "contenu côté serveur. Relancer le relevé pour rétablir les données.");
    }

    /**
     * Données de référence pour l'éditeur guidé #46 : id de quêtes / PNJ connus et mondes chargés,
     * pris du dernier relevé <em>réussi</em> de l'agent ({@code quest.list} / {@code npc.list} +
     * heartbeat). Si un relevé manque, la partie correspondante est marquée « inconnue » et la
     * validation dégrade ses contrôles en {@code INFO}.
     */
    /**
     * Champs actuels ({@code display_name} / {@code role} / {@code enabled}) de la définition d'un PNJ
     * au dernier {@code npc.list}, prêts pour un formulaire {@code npc.definition.update} (issue #145,
     * rattachement d'un dialogue). {@code Optional.empty()} si le PNJ est inconnu du relevé ou n'a pas
     * encore de définition logique ({@code npc.definition.update} échouerait côté serveur).
     */
    public Optional<Map<String, String>> npcDefinitionFields(String agentId, String npcId) {
        String target = npcId == null ? "" : npcId.trim().toLowerCase(Locale.ROOT);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        for (Object o : latestDetails(agentId, "npc.list").map(d -> asList(d.get("npcs"))).orElse(List.of())) {
            Map<String, Object> n = asMap(o);
            if (!target.equals(str(n.get("id")).toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!Boolean.TRUE.equals(n.get("logicalDefinitionPresent"))) {
                return Optional.empty();
            }
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("display_name", cleanNull(str(n.get("displayName"))));
            String role = cleanNull(str(n.get("role")));
            if (!role.isEmpty()) {
                fields.put("role", role);
            }
            fields.put("enabled", Boolean.TRUE.equals(n.get("enabled")) ? "true" : "false");
            return Optional.of(fields);
        }
        return Optional.empty();
    }

    /**
     * Dernier résultat d'un type d'action pour un joueur donné (issue #200) — exposé pour que
     * {@code PanelApp} affiche l'état RÉEL du pont sans dupliquer la lecture du journal d'actions.
     */
    public Optional<AgentActionRow> latestForPlayerPublic(String agentId, String type, String player) {
        return latestForPlayer(agentId, type, player);
    }

    /** Dernier résultat d'un type d'action, tous joueurs confondus (issue #200). */
    public Optional<AgentActionRow> latestOfTypePublic(String agentId, String type) {
        return store.latestActionOfType(agentId, type);
    }

    /** Ligne de résultat formatée, réutilisée par les blocs du pont (issue #200). */
    public String resultLinePublic(String label, AgentActionRow row) {
        return resultLine(label, row);
    }

    public com.lodygames.rpgquest.panel.content.RefData referenceData(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return com.lodygames.rpgquest.panel.content.RefData.empty();
        }
        Optional<Map<String, Object>> questDet = latestDetails(agentId, "quest.list");
        Optional<Map<String, Object>> npcDet = latestDetails(agentId, "npc.list");
        List<String> quests = new ArrayList<>(questDet.map(d -> asList(d.get("quests"))).orElse(List.of())
                .stream().map(o -> str(asMap(o).get("id"))).filter(s -> !s.isEmpty()).toList());
        List<String> npcs = npcDet.map(d -> asList(d.get("npcs"))).orElse(List.of())
                .stream().map(o -> str(asMap(o).get("id"))).filter(s -> !s.isEmpty()).toList();
        // Libellé humain -> id pour les listes déroulantes de l'éditeur (#46, §10 : « Garde / guard »).
        Map<String, String> npcNames = new java.util.LinkedHashMap<>();
        for (Object o : npcDet.map(d -> asList(d.get("npcs"))).orElse(List.of())) {
            Map<String, Object> m = asMap(o);
            String id = str(m.get("id"));
            String name = str(m.get("displayName"));
            if (!id.isEmpty() && !name.isEmpty()) {
                npcNames.putIfAbsent(id, name);
            }
        }
        // Titre humain -> id de quête pour la sélection recherchable de l'éditeur de story
        // (#46, §3/§5 : « Premiers pas / first_steps »). Source : le dernier « quest.list » réussi.
        Map<String, String> questNames = new java.util.LinkedHashMap<>();
        for (Object o : questDet.map(d -> asList(d.get("quests"))).orElse(List.of())) {
            Map<String, Object> m = asMap(o);
            String id = str(m.get("id"));
            String title = MiniText.plain(str(m.get("title"))).trim();
            if (!id.isEmpty() && !title.isEmpty()) {
                questNames.putIfAbsent(id, title);
            }
        }
        // Issue #163 : graphe des prérequis DÉJÀ connus, nécessaire pour refuser un cycle indirect
        // (A exige B, B exige A) dans l'éditeur. Relevé runtime d'abord — c'est la vérité de ce que
        // le serveur applique réellement ; la source le complète/l'écrase juste après, car c'est
        // elle qu'on est en train d'éditer.
        Map<String, List<String>> questPrereqs = new java.util.LinkedHashMap<>();
        for (Object o : questDet.map(d -> asList(d.get("quests"))).orElse(List.of())) {
            Map<String, Object> m = asMap(o);
            String id = QuestYaml.plainId(str(m.get("id")));
            if (id.isEmpty()) {
                continue;
            }
            List<String> pr = asList(m.get("prerequisites")).stream()
                    .map(x -> QuestYaml.plainId(str(x)))
                    .filter(s -> !s.isEmpty())
                    .toList();
            questPrereqs.put(id, pr);
        }
        // Origine de chaque quête (#163/#164) : « serveur uniquement » tant que la source ne la
        // contient pas ; complété ci-dessous pour les quêtes de la source.
        Map<String, String> questOrigins = new java.util.LinkedHashMap<>();
        for (String q : quests) {
            String plain = QuestYaml.plainId(q);
            if (!plain.isEmpty()) {
                questOrigins.put(plain, com.lodygames.rpgquest.panel.content.RefData.ORIGIN_RUNTIME);
            }
        }

        // Fusion des quêtes de la SOURCE éditable (issue #144) : une quête tout juste enregistrée
        // depuis /quests/new doit être immédiatement disponible dans les lookups d'édition (prérequis,
        // chaîne de story) sans redémarrage Minecraft. Id « nu » pour rester cohérent avec la
        // convention de RefData (comparaison via QuestYaml.plainId).
        List<SourceCatalog.QuestSource> sourceQuests = sourceCatalog.quests();
        for (SourceCatalog.QuestSource qs : sourceQuests) {
            String plain = qs.plainId();
            if (plain.isEmpty()) {
                continue;
            }
            if (quests.stream().noneMatch(q -> QuestYaml.plainId(q).equals(plain))) {
                quests.add(plain);
            }
            String title = qs.draft().title == null ? "" : MiniText.plain(qs.draft().title).trim();
            if (!title.isEmpty()) {
                questNames.putIfAbsent(plain, title);
            }
            questOrigins.put(plain, questOrigins.containsKey(plain)
                    ? com.lodygames.rpgquest.panel.content.RefData.ORIGIN_BOTH
                    : com.lodygames.rpgquest.panel.content.RefData.ORIGIN_SOURCE);
            // La source est la version en cours d'édition : ses prérequis prévalent sur le relevé
            // runtime, qui peut être antérieur au dernier enregistrement.
            if (qs.parseOk()) {
                questPrereqs.put(plain, qs.draft().prerequisites.stream()
                        .map(QuestYaml::plainId)
                        .filter(s -> !s.isEmpty())
                        .toList());
            }
        }
        boolean questsKnown = questDet.isPresent() || !sourceQuests.isEmpty();
        List<String> worlds = loadedWorldNames(agentId);
        return new com.lodygames.rpgquest.panel.content.RefData(
                quests, npcs, worlds, questsKnown, npcDet.isPresent(), !worlds.isEmpty(),
                npcNames, questNames, questOrigins, questPrereqs, itemCatalog(agentId));
    }

    /**
     * Issue #194 — le dernier relevé du serveur connaît-il ce contenu ?
     *
     * <p>Sert à décider s'il faut aussi demander une suppression côté serveur. Lecture du dernier
     * relevé réussi uniquement : aucune requête déclenchée, et surtout aucune supposition — un
     * relevé absent répond « non », ce qui est honnête (on ne sait pas), et la page le dit.</p>
     *
     * @param kind {@code quests} ou {@code stories}
     */
    public boolean runtimeKnowsContent(String agentId, String kind, String plainId) {
        String type = "quests".equals(kind) ? "quest.list" : "story.list";
        String listKey = "quests".equals(kind) ? "quests" : "stories";
        String wanted = QuestYaml.plainId(plainId == null ? "" : plainId.trim());
        if (wanted.isEmpty()) {
            return false;
        }
        return latestDetails(agentId, type)
                .map(d -> asList(d.get(listKey)))
                .orElse(List.of())
                .stream()
                .map(o -> QuestYaml.plainId(str(asMap(o).get("id"))))
                .anyMatch(wanted::equals);
    }

    /**
     * Issue #194 — un relevé du catalogue serveur a-t-il déjà été fait ?
     *
     * <p>Distinct de {@link #runtimeKnowsContent}. Sans relevé, « le serveur ne connaît pas ce
     * contenu » serait faux : on ne lui a jamais demandé. L'aperçu de suppression doit pouvoir le
     * dire, parce que l'absence de suppression côté serveur fait réapparaître le contenu.</p>
     */
    public boolean runtimeListingAvailable(String agentId, String kind) {
        return latestDetails(agentId, "quests".equals(kind) ? "quest.list" : "story.list").isPresent();
    }

    /**
     * Issue #196 — catalogue des matériaux de la version installée, lu du dernier relevé
     * {@code item.catalogs} réussi. Absent ⇒ catalogue vide, et c'est l'interface qui l'annonce :
     * on ne substitue jamais silencieusement un extrait au catalogue réel.
     */
    public com.lodygames.rpgquest.panel.content.RefData.ItemCatalog itemCatalog(String agentId) {
        Optional<Map<String, Object>> det = latestDetails(agentId, "item.catalogs");
        if (det.isEmpty()) {
            return com.lodygames.rpgquest.panel.content.RefData.ItemCatalog.empty();
        }
        return new com.lodygames.rpgquest.panel.content.RefData.ItemCatalog(
                stringList(det, "items"),
                stringList(det, "blocksWithoutItem"),
                str(det.get().get("minecraftVersion")));
    }

    /**
     * Comptes synthétiques pour les tuiles de la Home (issue #92, lot Bootstrap) — <strong>lecture
     * du dernier relevé agent uniquement</strong>, aucune requête déclenchée. Un champ à {@code -1}
     * = donnée non encore chargée.
     */
    public HomeSummary homeSummary(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return HomeSummary.UNKNOWN;
        }
        int players = countOf(agentId, "player.list", "players");
        Optional<Map<String, Object>> npcDet = latestDetails(agentId, "npc.list");
        int npc = npcDet.map(d -> asList(d.get("npcs")).size()).orElse(-1);
        int npcWarn = npcDet.map(d -> intOr(d.get("withWarnings"), 0)).orElse(-1);
        int quests = countOf(agentId, "quest.list", "quests");
        int stories = countOf(agentId, "story.list", "stories");
        Optional<Map<String, Object>> dlgDet = latestDetails(agentId, "dialogue.list");
        int dialogues = dlgDet.map(d -> asList(d.get("dialogues")).size()).orElse(-1);
        int dlgWarn = dlgDet.map(d -> intOr(d.get("withWarnings"), 0)
                + asList(d.get("loadIssues")).size()).orElse(-1);
        return new HomeSummary(players, npc, npcWarn, quests, stories, dialogues, dlgWarn);
    }

    /** @param n valeur, ou -1 si le relevé correspondant n'a jamais été chargé */
    public record HomeSummary(int playersOnline, int npcTotal, int npcWarnings, int quests,
                              int stories, int dialogues, int dialogueWarnings) {
        public static final HomeSummary UNKNOWN = new HomeSummary(-1, -1, -1, -1, -1, -1, -1);
    }

    private int countOf(String agentId, String type, String arrayKey) {
        return latestDetails(agentId, type).map(d -> asList(d.get(arrayKey)).size()).orElse(-1);
    }

    private static int intOr(Object v, int dflt) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    // ---- Petits utilitaires ---------------------------------------------------------

    private String noAgent() {
        return Ui.banner("err", "Aucun agent RPGQuest configuré. Voir <code>docs/control-panel/AGENT.md</code>.");
    }

    /**
     * Petit lien contextuel vers une fiche de documentation (icône livre, jamais un emoji). Ouvre un
     * nouvel onglet ({@code rel=noopener}) pour ne jamais perdre un formulaire en cours de saisie
     * (issue #111).
     */
    static String docLink(String slugOrQuery, String label) {
        String href = slugOrQuery.startsWith("q=") || slugOrQuery.contains("=")
                ? "/docs?" + slugOrQuery
                : ("dialogues".equals(slugOrQuery) ? "/docs?q=dialogue" : "/docs/" + slugOrQuery);
        return "<a class=\"doc-cm-link\" href=\"" + Http.esc(href) + "\" target=\"_blank\" rel=\"noopener\">"
                + Icons.icon("book") + Http.esc(label) + "</a>";
    }

    /** Id de quête/story → slug de fichier pour {@code /quests/edit/…} (vide si non représentable). */
    static String editSlug(String rawId) {
        String s = rawId == null ? "" : rawId.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("rpgquest:")) {
            s = s.substring("rpgquest:".length());
        }
        return s.matches("[a-z0-9][a-z0-9_-]{0,63}") ? s : "";
    }

    private static String cleanPlayer(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim();
        return t.matches("[A-Za-z0-9_\\-]{1,40}|[0-9a-fA-F\\-]{36}") ? t : "";
    }

    /** Index {@code id -> titre} à partir d'une liste de définitions (quêtes, stories). */
    private static Map<String, String> titleIndex(List<Object> defs, String idKey, String titleKey) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (Object o : defs) {
            Map<String, Object> d = asMap(o);
            String id = str(d.get(idKey));
            if (!id.isEmpty()) {
                map.put(id, str(d.get(titleKey)));
            }
        }
        return map;
    }

    /** Liste de quêtes référencées (prérequis…) : titre humain si connu, id technique discret. */
    private String referencedQuests(List<Object> ids, Map<String, String> titles) {
        StringBuilder sb = new StringBuilder();
        for (Object o : ids) {
            sb.append(sb.isEmpty() ? "" : " · ").append(questRef(str(o), titles));
        }
        return sb.isEmpty() ? "—" : sb.toString();
    }

    /** Référence unique à une quête : « Titre humain <id> », ou id prettifié si titre inconnu. */
    private String questRef(String id, Map<String, String> titles) {
        if (id == null || id.isBlank() || "null".equals(id)) {
            return "—";
        }
        String title = titles == null ? null : titles.get(id);
        String label = title != null && !title.isBlank() ? MiniText.html(title)
                : Http.esc(MiniText.prettifyId(id));
        return label + " " + Ui.id(id);
    }

    // ---- Mobs spéciaux / boss (issue #169, lot 1) ----------------------------------------------

    public String mobs(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("mob", "Mobs spéciaux & boss",
                "Profils appliqués aux spawns naturels du Wild (ou à une instance de test) — jamais de "
                        + "YAML ni de commande à écrire pour en créer ou en tester un.", ""));
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        boolean canWrite = perms.can(session.effective(), Permission.MOB_WRITE);
        boolean canTest = perms.can(session.effective(), Permission.MOB_TEST_SPAWN);
        sb.append(agentPicker(agentId, "/mobs", ""));

        // Issue #172 : catalogues réels du serveur, émis une fois pour toute la page (ils alimentent
        // le formulaire de création ET ceux d'édition).
        sb.append(mobCatalogDatalists(agentId));
        boolean catalogsLoaded = latestDetails(agentId, "mob.catalogs").isPresent();
        if (!catalogsLoaded) {
            sb.append(Ui.banner("warn", "Les catalogues Minecraft (entités, particules, sons, biomes) "
                    + "ne sont pas encore chargés : les champs correspondants restent en saisie libre. "
                    + "Cliquer sur <strong>« Catalogues Minecraft »</strong> ci-dessous pour les récupérer "
                    + "depuis le serveur."));
        }
        sb.append("<div class=\"npc-catbar\"><span class=\"npc-catbar-t\">Catalogue</span>");
        sb.append(compactRefresh(session, agentId, "mob.list", "Rafraîchir", "btn-outline-primary", "/mobs"));
        sb.append(compactRefresh(session, agentId, "mob.catalogs", "Catalogues Minecraft",
                catalogsLoaded ? "btn-outline-secondary" : "btn-warning", "/mobs"));
        if (canWrite) {
            sb.append("<button class=\"btn btn-sm btn-primary\" type=\"button\" data-bs-toggle=\"collapse\" "
                    + "data-bs-target=\"#mob-new-def\" aria-expanded=\"false\" aria-controls=\"mob-new-def\">")
                    .append(Icons.icon("plus")).append("Nouveau profil</button>");
        }
        if (canTest) {
            sb.append(compactRefresh(session, agentId, "mob.test.clear", "Nettoyer les instances de test",
                    "btn-outline-danger", "/mobs"));
        }
        sb.append("</div>");
        if (canWrite) {
            sb.append("<div class=\"collapse\" id=\"mob-new-def\"><div class=\"card card-body npc-formcard\">");
            sb.append(mobDefForm(session, agentId, null, "mob-new-def"));
            sb.append("</div></div>");
        }

        Optional<Map<String, Object>> details = latestDetails(agentId, "mob.list");
        if (details.isEmpty()) {
            sb.append(Ui.empty("mob", "Aucun catalogue chargé — cliquer sur « Rafraîchir »."));
            return sb.toString();
        }
        Map<String, Object> d = details.get();
        List<Object> profiles = asList(d.get("profiles"));
        Map<String, Object> spawnSettings = asMap(d.get("spawnSettings"));

        if (Boolean.TRUE.equals(d.get("hasIssues"))) {
            StringBuilder issues = new StringBuilder();
            for (Object o : asList(d.get("issues"))) {
                issues.append("<div>").append(Http.esc(str(o))).append("</div>");
            }
            sb.append(Ui.banner("warning", "<strong>Profils rejetés au chargement :</strong>" + issues));
        }

        sb.append(Ui.sectionTitle("mob", "Tirage aléatoire dans le Wild"));
        sb.append("<p class=\"muted\">« Chance globale » filtre d'abord <strong>chaque</strong> spawn naturel "
                + "éligible avant même d'examiner les profils ; seuls les profils dont le tirage individuel "
                + "réussit ensuite participent, et si plusieurs réussissent en même temps, un tirage pondéré "
                + "explicite (poids = leur propre chance) choisit lequel s'applique. Les profils BOSS n'entrent "
                + "jamais dans ce tirage.</p>");
        sb.append(spawnSettingsForm(session, agentId, spawnSettings, canWrite));

        sb.append(Ui.sectionTitle("mob", "Profils (" + profiles.size() + ")"));
        if (profiles.isEmpty()) {
            sb.append(Ui.empty("mob", "Aucun profil : créer le premier avec « Nouveau profil »."));
            return sb.toString();
        }
        List<String> onlinePlayers = latestDetails(agentId, "player.list")
                .map(x -> asList(x.get("players"))).orElse(List.of())
                .stream().map(o -> str(asMap(o).get("name"))).filter(s -> !s.isEmpty()).toList();
        // Issue #172 : recherche nom/ID + filtres Boss / Mob spécial / désactivés, comme les autres
        // catalogues du panel (même composant, même ergonomie).
        sb.append(listControls("mobs", "Rechercher un profil (nom ou identifiant)\u2026",
                filterBtn("", "Tous", true) + filterBtn("boss", "Boss", false)
                        + filterBtn("special", "Mob spécial", false)
                        + filterBtn("disabled", "Désactivés", false)));
        sb.append("<p class=\"count-note\" data-count-note data-noun=\"profil\">")
                .append(profiles.size()).append(" profil(s)</p>");
        sb.append("<div class=\"accordion npc-accordion\" id=\"mob-accordion\">");
        int i = 0;
        for (Object o : profiles) {
            sb.append(renderMobAccordionItem(session, agentId, asMap(o), i++, canWrite, canTest, onlinePlayers));
        }
        sb.append("</div>");
        return sb.toString();
    }

    private String spawnSettingsForm(Session session, String agentId, Map<String, Object> settings, boolean canWrite) {
        boolean enabled = Boolean.TRUE.equals(settings.get("enabled"));
        String chance = str(settings.get("chance"));
        String max = str(settings.get("maxSimultaneousSpecial"));
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"/agents/action\" class=\"npc-def-form\">");
        sb.append("<input type=\"hidden\" name=\"_csrf\" value=\"").append(Http.esc(session.csrfToken())).append("\">");
        sb.append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"type\" value=\"mob.spawn-settings.set\">");
        sb.append("<input type=\"hidden\" name=\"return\" value=\"/mobs\">");
        sb.append("<div class=\"npc-fs\">");
        sb.append("<div class=\"form-check form-switch\"><input class=\"form-check-input\" type=\"checkbox\" role=\"switch\" "
                + "id=\"mob-ss-en\" name=\"enabled\" value=\"true\"").append(enabled ? " checked" : "")
                .append(canWrite ? "" : " disabled").append("><label class=\"form-check-label\" for=\"mob-ss-en\">"
                + "Tirage aléatoire actif</label></div>");
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"mob-ss-chance\">Chance globale (0 à 1)</label>")
                .append("<input class=\"form-control\" id=\"mob-ss-chance\" type=\"number\" name=\"chance\" "
                        + "step=\"0.001\" min=\"0\" max=\"1\" value=\"").append(Http.esc(chance))
                .append("\"").append(canWrite ? " required" : " readonly").append(">")
                .append("<div class=\"form-text\">Ex. 0.05 = 5% des spawns naturels éligibles sont même "
                        + "considérés pour une transformation.</div></div>");
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"mob-ss-max\">Plafond simultané (optionnel)</label>")
                .append("<input class=\"form-control\" id=\"mob-ss-max\" type=\"number\" name=\"max_simultaneous_special\" "
                        + "min=\"0\" value=\"").append("null".equals(max) ? "" : Http.esc(max)).append("\"")
                .append(canWrite ? "" : " readonly").append(">")
                .append("<div class=\"form-text\">Nombre total de mobs SPECIAL vivants, toutes définitions "
                        + "confondues. Laisser vide = pas de plafond global.</div></div>");
        sb.append("</div>");
        if (canWrite) {
            sb.append("<button class=\"btn btn-primary\" type=\"submit\">")
                    .append(Icons.icon("save")).append("Enregistrer</button>");
        }
        sb.append("</form>");
        return sb.toString();
    }

    private String renderMobAccordionItem(Session session, String agentId, Map<String, Object> m, int index,
                                          boolean canWrite, boolean canTest, List<String> onlinePlayers) {
        String id = str(m.get("id"));
        String category = str(m.get("category"));
        boolean enabled = Boolean.TRUE.equals(m.get("enabled"));
        boolean boss = "BOSS".equals(category);
        String headingId = "mob-h-" + index;
        String collapseId = "mob-c-" + index;

        // Issue #172 : la carte porte de quoi être filtrée/cherchée (même mécanique que les autres
        // catalogues) — texte cherché = identifiant ET nom affiché, catégories = boss/special/état.
        String displayName = MiniText.plain(str(m.get("displayName")));
        StringBuilder sb = new StringBuilder("<div class=\"accordion-item\" data-filter-item=\"mobs\" "
                + "data-filter-cat=\"" + (boss ? "boss" : "special") + (enabled ? "" : " disabled") + "\" "
                + "data-filter-text=\"" + Http.esc((id + " " + displayName).toLowerCase(java.util.Locale.ROOT))
                + "\">");
        sb.append("<h2 class=\"accordion-header\" id=\"").append(headingId).append("\">")
                .append("<button class=\"accordion-button collapsed\" type=\"button\" data-bs-toggle=\"collapse\" "
                        + "data-bs-target=\"#").append(collapseId).append("\" aria-expanded=\"false\" aria-controls=\"")
                .append(collapseId).append("\">");
        sb.append(boss ? "<span class=\"badge text-bg-danger\">BOSS</span> " : "<span class=\"badge text-bg-info\">SPECIAL</span> ");
        sb.append(enabled ? "" : "<span class=\"badge text-bg-secondary\">désactivé</span> ");
        sb.append(Http.esc(MiniText.plain(str(m.get("displayName"))))).append(" <code class=\"ms-1\">")
                .append(Http.esc(id)).append("</code>");
        sb.append(" <span class=\"muted ms-2\">").append(Http.esc(str(m.get("entityType")))).append(" · vivants : ")
                .append(Http.esc(str(m.get("alivePopulation")))).append("</span>");
        sb.append("</button></h2>");
        sb.append("<div id=\"").append(collapseId).append("\" class=\"accordion-collapse collapse\" "
                + "aria-labelledby=\"").append(headingId).append("\" data-bs-parent=\"#mob-accordion\">");
        sb.append("<div class=\"accordion-body\">");

        sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Chance d'apparition</span> ")
                .append(Http.esc(str(m.get("spawnChance")))).append("</p>");
        List<Object> abilities = asList(m.get("abilitiesSummary"));
        if (!abilities.isEmpty()) {
            sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Capacités</span> ").append(join(abilities)).append("</p>");
        }

        if (canWrite) {
            sb.append("<button class=\"btn btn-sm btn-outline-secondary\" type=\"button\" data-bs-toggle=\"collapse\" "
                    + "data-bs-target=\"#mob-edit-").append(index).append("\" aria-expanded=\"false\">")
                    .append(Icons.icon("edit")).append("Modifier</button> ");
            sb.append(toggleForm(session, agentId, id, enabled));
        }
        if (canTest) {
            sb.append(' ').append(testSpawnForm(session, agentId, id, onlinePlayers));
        }

        if (canWrite) {
            sb.append("<div class=\"collapse mt-2\" id=\"mob-edit-").append(index)
                    .append("\"><div class=\"card card-body npc-formcard\">");
            sb.append(mobDefForm(session, agentId, m, "mob-edit-" + index));
            sb.append("</div></div>");
        }

        sb.append("</div></div></div>");
        return sb.toString();
    }

    private String toggleForm(Session session, String agentId, String id, boolean enabled) {
        return "<form method=\"post\" action=\"/agents/action\" class=\"d-inline\">"
                + "<input type=\"hidden\" name=\"_csrf\" value=\"" + Http.esc(session.csrfToken()) + "\">"
                + "<input type=\"hidden\" name=\"agent\" value=\"" + Http.esc(agentId) + "\">"
                + "<input type=\"hidden\" name=\"type\" value=\"mob.definition.toggle\">"
                + "<input type=\"hidden\" name=\"mob_id\" value=\"" + Http.esc(id) + "\">"
                + "<input type=\"hidden\" name=\"return\" value=\"/mobs\">"
                + "<input type=\"hidden\" name=\"enabled\" value=\"" + (enabled ? "false" : "true") + "\">"
                + "<button class=\"btn btn-sm " + (enabled ? "btn-outline-warning" : "btn-outline-success") + "\" type=\"submit\">"
                + (enabled ? "Désactiver" : "Activer") + "</button></form>";
    }

    private String testSpawnForm(Session session, String agentId, String id, List<String> onlinePlayers) {
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"/agents/action\" class=\"d-inline\">");
        sb.append("<input type=\"hidden\" name=\"_csrf\" value=\"").append(Http.esc(session.csrfToken())).append("\">");
        sb.append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"type\" value=\"mob.test.spawn\">");
        sb.append("<input type=\"hidden\" name=\"mob_id\" value=\"").append(Http.esc(id)).append("\">");
        sb.append("<input type=\"hidden\" name=\"return\" value=\"/mobs\">");
        sb.append("<input type=\"hidden\" name=\"confirm\" value=\"true\">");
        if (onlinePlayers.isEmpty()) {
            sb.append("<input type=\"text\" name=\"player\" class=\"form-control form-control-sm d-inline-block\" "
                    + "style=\"width:9rem\" placeholder=\"Pseudo exact\" required>");
        } else {
            sb.append("<select name=\"player\" class=\"form-select form-select-sm d-inline-block\" style=\"width:9rem\">");
            for (String p : onlinePlayers) {
                sb.append("<option value=\"").append(Http.esc(p)).append("\">").append(Http.esc(p)).append("</option>");
            }
            sb.append("</select>");
        }
        sb.append("<button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">")
                .append(Icons.icon("target")).append("Apparaître (test, Wild)</button></form>");
        return sb.toString();
    }

    /** Formulaire création/modification d'un profil. {@code existing == null} = création. */
    /**
     * Issue #172 — l'éditeur a longtemps affiché des champs de <strong>texte libre</strong> pour le
     * type d'entité (obligatoire !), la particule, le son, les mondes et les biomes : aucune liste,
     * aucune recherche, seulement un placeholder. L'administrateur devait donc connaître par cœur
     * l'identifiant vanilla exact — d'où « je n'arrive pas à créer un mob », alors que l'action
     * serveur fonctionnait (vérifié de bout en bout : le profil se créait bien quand les bons
     * identifiants étaient postés).
     *
     * <p>Désormais ces champs sont alimentés par les <strong>catalogues réels du serveur</strong>
     * (relevé {@code mob.catalogs}) : liste recherchable pour entité / particule / son,
     * multisélection pour mondes et biomes. Sans relevé disponible, on le dit et on retombe sur la
     * saisie libre plutôt que d'afficher une liste inventée.</p>
     */
    private String mobDefForm(Session session, String agentId, Map<String, Object> existing, String uid) {
        boolean update = existing != null;
        String id = update ? str(existing.get("id")) : "";
        StringBuilder sb = new StringBuilder();
        sb.append("<p class=\"fs-h\">").append(Icons.icon("mob"))
                .append(update ? "Modifier le profil" : "Créer un profil").append("</p>");
        // Issue #172 — « novalidate » volontaire. Deux sections de capacités sont repliées par
        // défaut et contiennent des champs numériques contraints : si l'un d'eux devient invalide,
        // le navigateur refuse de soumettre ET ne peut pas focaliser un champ caché dans un
        // <details> fermé. Résultat vu par l'administrateur : « le bouton ne fait rien », sans
        // aucun message. La validation métier complète existe déjà côté panel ET côté plugin
        // (défense en profondeur) : on laisse donc le serveur répondre, avec un message lisible,
        // plutôt que de dépendre d'une validation navigateur qui échoue en silence.
        sb.append("<form method=\"post\" action=\"/agents/action\" autocomplete=\"off\" novalidate "
                + "class=\"npc-def-form\">");
        sb.append("<input type=\"hidden\" name=\"_csrf\" value=\"").append(Http.esc(session.csrfToken())).append("\">");
        sb.append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"type\" value=\"mob.definition.")
                .append(update ? "update" : "create").append("\">");
        sb.append("<input type=\"hidden\" name=\"return\" value=\"/mobs\">");

        sb.append("<div class=\"npc-fs\"><p class=\"npc-fs-h\">Identité</p>");
        if (update) {
            sb.append("<input type=\"hidden\" name=\"mob_id\" value=\"").append(Http.esc(id)).append("\">");
            sb.append("<div class=\"mb-2\"><label class=\"form-label\">ID technique</label>"
                    + "<input class=\"form-control\" type=\"text\" value=\"").append(Http.esc(id))
                    .append("\" readonly></div>");
        } else {
            sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid).append("-id\">ID technique</label>")
                    .append("<input class=\"form-control\" id=\"").append(uid).append("-id\" type=\"text\" name=\"mob_id\" "
                            + "pattern=\"[a-z0-9._-]{1,64}\" placeholder=\"Exemple : swamp_king\" required>")
                    .append("<div class=\"form-text\">Minuscules, chiffres, « . _ - ». Non modifiable après création.</div></div>");
        }
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid).append("-cat\">Catégorie</label>")
                .append("<select class=\"form-select\" id=\"").append(uid).append("-cat\" name=\"category\">")
                .append(option("SPECIAL", str(existing == null ? null : existing.get("category")), "Spécial (tirage aléatoire Wild)"))
                .append(option("BOSS", str(existing == null ? null : existing.get("category")), "Boss (jamais tiré au hasard)"))
                .append("</select><div class=\"form-text\">BOSS : nom + particules colorées en continu + barre de vie, "
                        + "jamais dans le tirage automatique.</div></div>");
        sb.append(catalogCombo(uid, "entity_type", "et", "Type d'entité Minecraft",
                str(existing == null ? null : existing.get("entityType")), "dl-mob-entity", true,
                "Créature vanilla servant de base. <strong>Liste réelle du serveur</strong> : taper « zomb » "
                + "ou « ZOMBIE » filtre. Les bases passives (PIG, CHICKEN, FROG…) sont autorisées — sans "
                + "capacité agressive ajoutée, un tel profil reste <strong>aussi passif que la base "
                + "vanilla</strong> (rien ne le rend hostile implicitement)."));
        // Issue #195 : couleur au clic + cases de style + aperçu. MiniMessage reste le format
        // stocké, mais il n'est plus nécessaire d'en écrire pour un usage courant.
        sb.append(StyleField.render("display_name", uid + "-name", "Nom affiché",
                str(existing == null ? null : existing.get("displayName")), true,
                "Nom vu par les joueurs. Choisir une couleur et des styles ci-dessus : "
                + "aucun code à écrire. Un nom déjà écrit avec <strong>plusieurs styles</strong> "
                + "est conservé tel quel et n'est jamais simplifié sans action explicite."));
        sb.append("<div class=\"form-check form-switch\"><input class=\"form-check-input\" type=\"checkbox\" role=\"switch\" "
                + "id=\"").append(uid).append("-en\" name=\"enabled\" value=\"true\"")
                .append(!update || Boolean.TRUE.equals(existing.get("enabled")) ? " checked" : "")
                .append("><label class=\"form-check-label\" for=\"").append(uid).append("-en\">Profil actif</label></div>");
        sb.append("</div>");

        // #190 : bouton dupliqué tôt dans le formulaire -- le reste (tirage/statistiques/capacités)
        // a des valeurs par défaut raisonnables ou est optionnel ; un profil minimal peut être
        // enregistré sans faire défiler tout le formulaire jusqu'en bas.
        sb.append("<p class=\"form-text\">Les champs ci-dessous ont des valeurs par défaut : vous pouvez "
                + "enregistrer dès maintenant, ou continuer les réglages plus bas.</p>");
        sb.append("<button class=\"btn btn-primary\" type=\"submit\">")
                .append(Icons.icon("save")).append(update ? "Enregistrer" : "Créer").append("</button>");

        sb.append("<div class=\"npc-fs\"><p class=\"npc-fs-h\">Tirage aléatoire (Wild)</p>");
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid).append("-sc\">Chance individuelle (0 à 1)</label>")
                .append("<input class=\"form-control\" id=\"").append(uid).append("-sc\" type=\"number\" step=\"0.0001\" "
                        + "min=\"0\" max=\"1\" name=\"spawn_chance\" value=\"")
                .append(Http.esc(str(existing == null ? "0.01" : existing.get("spawnChance")))).append("\" required>"
                        + "<div class=\"form-text\">Ignorée pour un profil BOSS (jamais tiré au hasard).</div></div>");
        sb.append(catalogMulti(uid, "worlds", "Mondes autorisés",
                existing == null ? null : existing.get("worlds"), "dl-mob-world",
                "Mondes où ce profil peut apparaître. <strong>Vide = aucune restriction de monde</strong>, "
                + "donc tous les mondes où le tirage s'applique — à éviter : préférer cocher "
                + "explicitement le Wild. Ajouter le Hub ou un monde de claims doit rester un choix "
                + "délibéré (leurs propres règles continuent de s'appliquer et peuvent annuler "
                + "l'apparition)."));
        sb.append(catalogMulti(uid, "biomes", "Biomes autorisés",
                existing == null ? null : existing.get("biomes"), "dl-mob-biome",
                "Biomes où ce profil peut apparaître. <strong>Vide = tous les biomes.</strong> "
                + "Liste réelle du serveur."));
        sb.append(csvField(uid, "zones", "Zones autorisées", existing,
                "Zones protégées RPGQuest (/rpgadmin zone), par identifiant. Vide = aucune "
                + "restriction de zone. À ne pas confondre avec les mondes (ci-dessus) ni avec les "
                + "biomes : une zone est un cuboïde nommé défini par un administrateur."));
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid).append("-mp\">Population maximale simultanée</label>")
                .append("<input class=\"form-control\" id=\"").append(uid).append("-mp\" type=\"number\" min=\"0\" "
                        + "name=\"max_population\" value=\"").append(Http.esc(str(existing == null ? null : existing.get("maxPopulation"))))
                .append("\"><div class=\"form-text\">Laisser vide = pas de plafond pour ce profil.</div></div>");
        sb.append("</div>");

        sb.append("<div class=\"npc-fs\"><p class=\"npc-fs-h\">Statistiques (laisser vide = valeur vanilla)</p>");
        sb.append(numField(uid, "health", "Vie max", existing, "0.01", null));
        sb.append(numField(uid, "damage", "Dégâts", existing, "0.01", null));
        sb.append(numField(uid, "speed", "Vitesse", existing, "0.01", null));
        sb.append(numField(uid, "armor", "Armure", existing, "0.01", null));
        sb.append(numField(uid, "knockback_resistance", "Résistance au recul (0 à 1)", existing, "0.01", null));
        sb.append(numField(uid, "scale", "Taille relative (1 = normale)", existing, "0.01", null));
        sb.append(numField(uid, "creeper_explosion_radius", "Rayon d'explosion (CREEPER uniquement)", existing, "0.1", null));
        sb.append(catalogCombo(uid, "particle", "pt", "Particule (optionnel)",
                str(existing == null ? null : existing.get("particle")), "dl-mob-particle", false,
                "Effet visuel émis en continu par un BOSS. <strong>Vide = aucune particule.</strong> "
                + "Liste réelle du serveur. Une couleur n'est applicable que sur les types qui "
                + "l'acceptent (voir la mention « colorable » dans la liste) — pour les autres, "
                + "Minecraft ignore toute couleur, ce n'est pas un défaut du panel."));
        sb.append(catalogCombo(uid, "sound", "sd", "Son (optionnel)",
                str(existing == null ? null : existing.get("sound")), "dl-mob-sound", false,
                "Son joué à l'apparition. <strong>Vide = aucun son.</strong> Liste réelle du serveur."));
        sb.append(numField(uid, "xp_reward", "XP à la mort", existing, "1", null));
        sb.append("</div>");

        // #190 : repliées par défaut (<details> natif, sans JS) pour raccourcir le formulaire par
        // défaut -- ouvertes automatiquement en modification si la capacité est déjà active.
        boolean hasEnraged = existing != null && existing.get("enragedHealthFraction") != null;
        sb.append("<details").append(hasEnraged ? " open" : "").append("><summary class=\"npc-fs-h\">Capacité : Enragé</summary>");
        sb.append("<div class=\"npc-fs\">");
        sb.append("<div class=\"form-check\"><input class=\"form-check-input\" type=\"checkbox\" id=\"").append(uid)
                .append("-rg-en\" name=\"enraged_enabled\" value=\"true\"").append(hasEnraged ? " checked" : "")
                .append("><label class=\"form-check-label\" for=\"").append(uid).append("-rg-en\">Activer cette capacité</label></div>");
        sb.append(numField(uid, "enraged_health_fraction", "Seuil de vie (0 à 1, ex. 0.3 = sous 30%)", existing, "0.01", null));
        sb.append(numField(uid, "enraged_speed_multiplier", "Multiplicateur de vitesse", existing, "0.1", null));
        sb.append(numField(uid, "enraged_damage_multiplier", "Multiplicateur de dégâts", existing, "0.1", null));
        sb.append("</div></details>");

        boolean hasSummon = existing != null && existing.get("summonEntityType") != null;
        sb.append("<details").append(hasSummon ? " open" : "")
                .append("><summary class=\"npc-fs-h\">Capacité : Invocation de renforts</summary>");
        sb.append("<div class=\"npc-fs\">");
        sb.append("<div class=\"form-check\"><input class=\"form-check-input\" type=\"checkbox\" id=\"").append(uid)
                .append("-sm-en\" name=\"summon_enabled\" value=\"true\"").append(hasSummon ? " checked" : "")
                .append("><label class=\"form-check-label\" for=\"").append(uid).append("-sm-en\">Activer cette capacité</label></div>");
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid).append("-sm-t\">Type de renfort</label>")
                .append("<input class=\"form-control\" id=\"").append(uid).append("-sm-t\" type=\"text\" name=\"summon_entity_type\" "
                        + "value=\"").append(Http.esc(str(existing == null ? null : existing.get("summonEntityType"))))
                .append("\" placeholder=\"Exemple : ZOMBIE\"></div>");
        sb.append(numField(uid, "summon_amount", "Nombre invoqué par déclenchement", existing, "1", null));
        sb.append(numField(uid, "summon_chance", "Chance par coup reçu (0 à 1)", existing, "0.01", null));
        sb.append(numField(uid, "summon_cooldown_seconds", "Cooldown (secondes)", existing, "1", null));
        sb.append(numField(uid, "summon_max_alive", "Renforts vivants max", existing, "1", null));
        sb.append("<div class=\"form-text\">Ne se déclenche que sur des dégâts effectifs ; jamais de cascade "
                + "(les renforts eux-mêmes n'invoquent jamais).</div>");
        sb.append("</div></details>");

        sb.append("<button class=\"btn btn-primary\" type=\"submit\">")
                .append(Icons.icon("save")).append(update ? "Enregistrer" : "Créer").append("</button>");
        sb.append("</form>");
        return sb.toString();
    }

    private static String option(String value, String current, String label) {
        return "<option value=\"" + value + "\"" + (value.equals(current) ? " selected" : "") + ">" + label + "</option>";
    }

    private String csvField(String uid, String name, String label, Map<String, Object> existing, String help) {
        List<Object> current = existing == null ? List.of() : asList(existing.get(name));
        return "<div class=\"mb-2\"><label class=\"form-label\" for=\"" + uid + "-" + name + "\">" + Http.esc(label) + "</label>"
                + "<input class=\"form-control\" id=\"" + uid + "-" + name + "\" type=\"text\" name=\"" + name + "\" "
                + "value=\"" + Http.esc(join(current).equals("—") ? "" : String.join(",", current.stream().map(AgentPages::str).toList()))
                + "\" placeholder=\"séparés par des virgules\"><div class=\"form-text\">" + Http.esc(help) + "</div></div>";
    }


    /**
     * Issue #172 — champ à valeur unique adossé au catalogue réel du serveur, rendu recherchable par
     * le composant {@code .combo} de {@code panel.js}. Sans JavaScript, l'{@code <input list>} natif
     * reste utilisable ; sans relevé de catalogue, le champ reste une saisie libre (jamais une liste
     * inventée) et l'aide le signale.
     */
    private String catalogCombo(String uid, String name, String shortId, String label, String value,
                                String datalistId, boolean required, String help) {
        String id = uid + "-" + shortId;
        StringBuilder sb = new StringBuilder("<div class=\"mb-2\">");
        sb.append("<label class=\"form-label\" for=\"").append(id).append("\">").append(Http.esc(label))
                .append("</label>");
        sb.append("<div class=\"combo\" data-combo><input class=\"form-control combo-input\" id=\"").append(id)
                .append("\" type=\"text\" name=\"").append(Http.esc(name))
                .append("\" value=\"").append(Http.esc(value == null || "null".equals(value) ? "" : value))
                .append("\" list=\"").append(datalistId).append("\" autocomplete=\"off\" role=\"combobox\" ")
                .append("aria-expanded=\"false\" aria-autocomplete=\"list\"")
                .append(required ? " required" : "").append("></div>");
        sb.append("<div class=\"form-text\">").append(help).append("</div></div>");
        return sb.toString();
    }

    /**
     * Issue #172 — multisélection adossée au catalogue réel, via le composant {@code multisel} de
     * {@code panel.js} (déjà utilisé pour les prérequis de quête, #163). Le champ réellement soumis
     * reste la liste séparée par des virgules attendue par l'action : le contrat serveur ne change
     * pas, et sans JavaScript le champ reste éditable tel quel.
     */
    private String catalogMulti(String uid, String name, String label, Object existingValue,
                                String datalistId, String help) {
        String current = "";
        if (existingValue instanceof List<?> list) {
            StringBuilder joined = new StringBuilder();
            for (Object v : list) {
                if (!joined.isEmpty()) {
                    joined.append('\n');
                }
                joined.append(str(v));
            }
            current = joined.toString();
        } else if (existingValue != null && !"null".equals(str(existingValue))) {
            current = str(existingValue);
        }
        String id = uid + "-" + name;
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"mb-2 field multisel\" data-multisel=\"").append(Http.esc(name))
                .append("\" data-multisel-list=\"").append(datalistId)
                .append("\" data-multisel-add=\"Ajouter…\" data-multisel-separator=\",\" "
                        + "data-multisel-empty=\"Aucune restriction — tout est autorisé.\">");
        sb.append("<label class=\"form-label\" for=\"").append(id).append("\">").append(Http.esc(label))
                .append("</label>");
        sb.append("<textarea class=\"form-control\" id=\"").append(id).append("\" name=\"")
                .append(Http.esc(name)).append("\" data-multisel-store>").append(Http.esc(current))
                .append("</textarea>");
        sb.append("<div class=\"form-text\">").append(help).append("</div></div>");
        return sb.toString();
    }

    /** Datalists des catalogues réels, émises une fois par page {@code /mobs}. */
    private String mobCatalogDatalists(String agentId) {
        Optional<Map<String, Object>> det = latestDetails(agentId, "mob.catalogs");
        List<String> entities = stringList(det, "entityTypes");
        List<String> particles = stringList(det, "particles");
        List<String> colorable = stringList(det, "colorableParticles");
        List<String> sounds = stringList(det, "sounds");
        List<String> biomes = stringList(det, "biomes");
        List<String> worlds = stringList(det, "worlds");
        StringBuilder sb = new StringBuilder();
        sb.append(simpleDatalist("dl-mob-entity", entities, List.of()));
        sb.append(simpleDatalist("dl-mob-particle", particles, colorable));
        sb.append(simpleDatalist("dl-mob-sound", sounds, List.of()));
        sb.append(simpleDatalist("dl-mob-biome", biomes, List.of()));
        sb.append(simpleDatalist("dl-mob-world", worlds, List.of()));
        return sb.toString();
    }

    /** {@code labelled} : valeurs recevant la mention « colorable » (#195). */
    private static String simpleDatalist(String id, List<String> values, List<String> labelled) {
        StringBuilder sb = new StringBuilder("<datalist id=\"").append(id).append("\">");
        for (String v : values) {
            sb.append("<option value=\"").append(Http.esc(v)).append("\"");
            if (labelled.contains(v)) {
                sb.append(" label=\"colorable\"");
            }
            sb.append(">");
        }
        return sb.append("</datalist>").toString();
    }

    private List<String> stringList(Optional<Map<String, Object>> details, String key) {
        return details.map(d -> asList(d.get(key)).stream().map(AgentPages::str)
                .filter(x -> !x.isEmpty()).toList()).orElse(List.of());
    }

    private String numField(String uid, String name, String label, Map<String, Object> existing, String step, String unused) {
        String value = existing == null ? "" : str(existing.get(toCamel(name)));
        if ("null".equals(value)) {
            value = "";
        }
        return "<div class=\"mb-2\"><label class=\"form-label\" for=\"" + uid + "-" + name + "\">" + Http.esc(label) + "</label>"
                + "<input class=\"form-control\" id=\"" + uid + "-" + name + "\" type=\"number\" step=\"" + step + "\" "
                + "name=\"" + name + "\" value=\"" + Http.esc(value) + "\"></div>";
    }

    /** {@code creeper_explosion_radius} -> {@code creeperExplosionRadius} (clés JSON de {@code MobProfileSummary}). */
    private static String toCamel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upperNext = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upperNext = true;
                continue;
            }
            sb.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = false;
        }
        return sb.toString();
    }

    private static String join(List<Object> values) {
        StringBuilder sb = new StringBuilder();
        for (Object v : values) {
            sb.append(sb.isEmpty() ? "" : ", ").append(str(v));
        }
        return sb.isEmpty() ? "—" : sb.toString();
    }

    private static String shorten(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** Lecture numérique tolérante d'un détail de relevé : une valeur absente ou illisible vaut 0. */
    private static long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<Object> asList(Object value) {
        return value instanceof List<?> l ? List.copyOf(l) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
