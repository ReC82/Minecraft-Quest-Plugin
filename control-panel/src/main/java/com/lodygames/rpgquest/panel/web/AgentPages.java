package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import com.lodygames.rpgquest.panel.agent.AgentIdentity;
import com.lodygames.rpgquest.panel.agent.AgentRegistry;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.authz.Permission;
import com.lodygames.rpgquest.panel.authz.PermissionService;
import com.lodygames.rpgquest.panel.dialogue.MinecraftTextWidth;
import com.lodygames.rpgquest.panel.content.QuestDraft;
import com.lodygames.rpgquest.panel.content.QuestYaml;
import com.lodygames.rpgquest.panel.content.SourceCatalog;
import com.lodygames.rpgquest.panel.content.StoryDraft;
import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.json.Json;
import com.lodygames.rpgquest.panel.npc.NpcAppearance;
import com.lodygames.rpgquest.panel.npc.NpcCreationPlanner;
import com.lodygames.rpgquest.panel.npc.NpcDisplayNames;
import com.lodygames.rpgquest.panel.npc.NpcRoles;
import com.lodygames.rpgquest.panel.security.Session;
import com.lodygames.rpgquest.panel.travel.TravelNetworkDiagnostic;
import java.time.Instant;
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
    /**
     * Projets de l'Atelier de création (issue #268) : la fiche d'un emplacement dit si sa description
     * de bâtiment a été reprise, et dans quel projet. {@code null} hors panel complet (tests unitaires
     * de rendu) : la fiche propose alors simplement la reprise.
     */
    private com.lodygames.rpgquest.panel.building.BuildingProjectStore workshopProjects;

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

    /** Branche les projets de l'Atelier (issue #268) ; appelé une fois par {@code PanelApp}. */
    public void useWorkshopProjects(com.lodygames.rpgquest.panel.building.BuildingProjectStore projects) {
        this.workshopProjects = projects;
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
        if (d.cooldown != null && !d.cooldown.isBlank()) {
            m.put("cooldown", d.cooldown.trim());
        }
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

    /**
     * Convertit un objectif de la <strong>source</strong> (brouillon relu depuis le YAML) dans la
     * même forme que le relevé runtime de l'agent, pour que {@link ObjectiveText} rende les deux
     * origines identiquement.
     *
     * <p>{@code npc} est émis <strong>en plus</strong> de {@code target} : une remise
     * ({@code DELIVER_ITEM_TO_NPC}, issue #123) a deux cibles — l'objet compté et le PNJ qui le
     * reçoit — et {@code target} ne peut en porter qu'une. L'oublier ici affichait « Rapporter Cuir
     * (x4) à ? » pour une quête « Source uniquement » dont le YAML contenait pourtant
     * {@code npc: guard} : le défaut était dans ce convertisseur, jamais dans la donnée.</p>
     */
    private static Map<String, Object> objectiveDetail(Map<String, String> obj) {
        Map<String, Object> o = new LinkedHashMap<>();
        String kind = obj.getOrDefault("kind", "").trim().toUpperCase(Locale.ROOT);
        o.put("kind", kind);
        o.put("target", firstNonBlank(obj.get("entity"), obj.get("material"), obj.get("npc"), obj.get("world")));
        o.put("amount", intOr(obj.get("amount"), 0));
        o.put("npc", nz(obj.get("npc")));
        // Issue #185 : la clé YAML « count-mode » est normalisée en « countMode », la même que le
        // relevé runtime — sinon la même quête s'afficherait différemment selon son origine, exactement
        // le défaut corrigé pour le PNJ destinataire de #123.
        o.put("worlds", nz(obj.get("worlds")));
        o.put("countMode", nz(obj.get("count-mode")));
        // Issue #178 : la ville précise d'un DISCOVER_CITY (identifiant stable), même clé que le relevé.
        if ("DISCOVER_CITY".equals(kind)) {
            o.put("target", nz(obj.get("city")));
        }
        // Issue #171 : le profil de boss d'un KILL_QUEST_BOSS, même clé que le relevé.
        if ("KILL_QUEST_BOSS".equals(kind)) {
            o.put("target", nz(obj.get("mob")));
        }
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

    /** Ce que la session peut faire sur la fiche d'un joueur (issue #328). */
    private record PlayerRights(boolean moderate, boolean varGet, boolean varSet, boolean give, boolean reset,
                                boolean op, boolean ecoRead, boolean ecoWrite, boolean mcRights) {
        boolean tools() {
            return varSet || give;
        }
    }

    /** Les cinq onglets de la fiche, dans l'ordre affiché. */
    private static final List<String[]> PLAYER_TABS = List.of(
            new String[] {"apercu", "Vue d'ensemble"},
            new String[] {"progression", "Progression"},
            new String[] {"economie", "Économie"},
            new String[] {"moderation", "Permissions et modération"},
            new String[] {"outils", "Outils avancés"});

    /**
     * Issue #328 — l'annuaire des joueurs, ou la fiche d'un joueur ({@code ?player=}). La fiche est
     * une page à part, avec ses onglets : plus aucune fiche ne se déroule au milieu de la liste.
     * Après une action, le panel revient ici avec {@code player=} et l'onglet ({@code focus=tab-…}).
     */
    public String players(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        if (agent.isEmpty()) {
            return Ui.pageHeader("players", "Joueurs", "", "") + noAgent();
        }
        String agentId = agent.get().id();
        PlayerRights rights = new PlayerRights(
                perms.can(session.effective(), Permission.PLAYER_MODERATE),
                perms.can(session.effective(), Permission.ACTION_VARIABLE_GET),
                perms.can(session.effective(), Permission.ACTION_VARIABLE_SET),
                perms.can(session.effective(), Permission.ACTION_ITEM_GIVE),
                // Issue #235 : l'un OU l'autre ouvre la section ; chaque bloc revérifie son droit.
                perms.can(session.effective(), Permission.ACTION_PLAYER_RESET)
                        || perms.can(session.effective(), Permission.ACTION_PLAYER_RESET_FULL),
                perms.can(session.effective(), Permission.PLAYER_OP_WRITE),
                perms.can(session.effective(), Permission.ECONOMY_READ),
                perms.can(session.effective(), Permission.ECONOMY_WRITE),
                perms.can(session.effective(), Permission.USER_MANAGE));
        Optional<Map<String, Object>> catalog = latestDetails(agentId, "player.catalog");
        String focusPlayer = cleanPlayer(q.get("player"));
        List<PlayerCatalog.Entry> all = catalog.map(c -> PlayerCatalog.parse(c.get("players"))).orElse(List.of());
        if (!focusPlayer.isEmpty()) {
            Optional<PlayerCatalog.Entry> found = all.stream()
                    .filter(e -> focusPlayer.equalsIgnoreCase(e.uuid()) || focusPlayer.equalsIgnoreCase(e.displayName()))
                    .findFirst();
            if (found.isPresent()) {
                String tab = q.getOrDefault("tab", "");
                String focus = cleanFocus(q.get("focus"));
                if (tab.isEmpty() && focus.startsWith("tab-")) {
                    tab = focus.substring(4);
                }
                return playerSheet(session, agentId, found.get(), tab, rights);
            }
        }
        return playerDirectory(session, agentId, q, catalog, all, focusPlayer);
    }

    private String playerDirectory(Session session, String agentId, Map<String, String> q,
                                   Optional<Map<String, Object>> catalog, List<PlayerCatalog.Entry> all,
                                   String missingPlayer) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("players", "Joueurs",
                "Les joueurs connectés et ceux déjà venus. Cliquez sur un joueur pour ouvrir sa fiche.",
                docLink("joueurs-admin", "Aide")));
        sb.append(agentPicker(agentId, "/players", ""));
        sb.append("<div class=\"npc-catbar\">")
                .append(compactRefresh(session, agentId, "player.catalog", "Actualiser", "btn-outline-primary", "/players"))
                .append("</div>");
        if (catalog.isEmpty()) {
            sb.append(Ui.empty("players", "La liste des joueurs n'est pas encore chargée : cliquez sur « Actualiser »."));
            return sb.toString();
        }
        if (!missingPlayer.isEmpty()) {
            sb.append(Ui.banner("warn", "Ce joueur n'apparaît pas dans la liste chargée. Actualisez, puis "
                    + "rouvrez sa fiche."));
        }
        String query = q.getOrDefault("q", "").trim();
        PlayerCatalog.Filter filter = PlayerCatalog.Filter.of(q.get("filter"));
        PlayerCatalog.Sort sort = PlayerCatalog.Sort.of(q.get("sort"));
        PlayerCatalog.Page view = PlayerCatalog.view(all, query, filter, sort, parsePageParam(q.get("page")),
                PlayerCatalog.DEFAULT_PAGE_SIZE);

        if (Boolean.TRUE.equals(catalog.get().get("truncated"))) {
            sb.append(Ui.banner("warn", "La liste est incomplète (trop de joueurs) : affinez la recherche."));
        }
        sb.append("<p class=\"npc-summary\">").append(view.total()).append(" joueur(s) · ")
                .append(view.online()).append(" en ligne · ").append(view.offline()).append(" hors ligne · ")
                .append(view.banned()).append(" banni(s)</p>");
        sb.append(playersControls(agentId, query, filter, sort));
        sb.append("<p class=\"count-note\" data-noun=\"joueur\">").append(view.matched())
                .append(view.matched() > 1 ? " joueurs" : " joueur")
                .append(view.matched() != view.total() ? " (sur " + view.total() + ")" : "").append("</p>");
        if (view.entries().isEmpty()) {
            sb.append(Ui.empty("players", "Aucun joueur ne correspond à cette recherche."));
            return sb.toString();
        }
        // Le retour depuis une fiche ramène à cette liste, recherche et filtre compris (panel.js).
        sb.append("<table class=\"table pl-table\" data-players-list><thead><tr><th>Joueur</th><th>État</th>")
                .append("<th>Dernière connexion</th><th>Monde</th></tr></thead><tbody>");
        for (PlayerCatalog.Entry e : view.entries()) {
            String href = "/players?agent=" + Http.esc(agentId) + "&amp;player=" + Http.esc(e.uuid());
            sb.append("<tr data-href=\"").append(href.replace("&amp;", "&")).append("\">");
            sb.append("<td data-label=\"Joueur\"><a class=\"pl-name\" href=\"").append(href).append("\">")
                    .append(Http.esc(e.displayName())).append("</a></td>");
            sb.append("<td data-label=\"État\">").append(playerStateChips(e)).append("</td>");
            sb.append("<td data-label=\"Dernière connexion\">").append(e.online() ? "connecté maintenant"
                    : localTime(e.lastSeen(), true)).append("</td>");
            sb.append("<td data-label=\"Monde\">").append(e.online() && e.world() != null ? Http.esc(e.world())
                    : "<span class=\"muted\">—</span>").append("</td></tr>");
        }
        sb.append("</tbody></table>");
        sb.append(playersPager(agentId, query, filter, sort, view));
        return sb.toString();
    }

    private static String playerStateChips(PlayerCatalog.Entry e) {
        StringBuilder sb = new StringBuilder(e.online()
                ? "<span class=\"dlg-chip tone-ok\">En ligne</span>"
                : "<span class=\"dlg-chip tone-neutral\">Hors ligne</span>");
        if (e.banned()) {
            sb.append(" <span class=\"dlg-chip tone-err\">Banni</span>");
        }
        return sb.toString();
    }

    /**
     * Une date affichée à l'heure locale du navigateur ({@code panel.js}), avec la date absolue en
     * infobulle. Sans JavaScript : la date en UTC, dite comme telle.
     */
    private static String localTime(Long millis, boolean relative) {
        if (millis == null || millis <= 0) {
            return "<span class=\"muted\">inconnue</span>";
        }
        String iso = java.time.Instant.ofEpochMilli(millis).toString();
        return "<time datetime=\"" + iso + "\" data-local-time" + (relative ? " data-relative" : "")
                + " title=\"" + Http.esc(plainWhen(millis)) + "\">"
                + Http.esc(relative ? relWhen(millis) : plainWhen(millis)) + "</time>";
    }

    // ---- Fiche joueur ---------------------------------------------------------------------

    private String playerSheet(Session session, String agentId, PlayerCatalog.Entry e, String rawTab,
                               PlayerRights r) {
        String uuid = e.uuid();
        String name = e.displayName();
        String tab = PLAYER_TABS.stream().map(t -> t[0]).filter(t -> t.equals(rawTab)).findFirst().orElse("apercu");
        StringBuilder sb = new StringBuilder();
        sb.append("<p class=\"pl-back\"><a href=\"/players?agent=").append(Http.esc(agentId))
                .append("\" data-players-back>").append(Icons.icon("back")).append("Joueurs</a></p>");

        // En-tête : qui, où, depuis quand.
        sb.append("<header class=\"card pl-head\"><div class=\"pl-head-main\"><h1 class=\"pl-title\">")
                .append(Http.esc(name)).append("</h1><div class=\"pl-chips\">").append(playerStateChips(e));
        if (e.op()) {
            sb.append(" <span class=\"dlg-chip tone-warn\">OP Minecraft</span>");
        }
        sb.append("</div></div><dl class=\"pl-facts\">");
        sb.append("<div><dt>Première connexion</dt><dd>").append(localTime(e.firstPlayed(), false)).append("</dd></div>");
        sb.append("<div><dt>Dernière connexion</dt><dd>").append(e.online() ? "connecté maintenant"
                : localTime(e.lastSeen(), false)).append("</dd></div>");
        if (e.online()) {
            sb.append("<div><dt>Position</dt><dd>").append(e.world() == null ? "<span class=\"muted\">inconnue</span>"
                    : Http.esc(e.world()) + (e.x() == null ? "" : " · " + e.x() + " / " + e.y() + " / " + e.z()))
                    .append("</dd></div>");
        }
        sb.append("<div class=\"pl-uuid\"><dt>UUID</dt><dd><code>").append(Http.esc(uuid))
                .append("</code> <button class=\"btn secondary sm\" type=\"button\" data-copy=\"").append(Http.esc(uuid))
                .append("\">").append(Icons.icon("copy")).append("Copier</button></dd></div>");
        sb.append("</dl></header>");

        sb.append(playerFeedback(agentId, uuid));

        sb.append("<nav class=\"pl-tabs\" aria-label=\"Sections de la fiche\">");
        for (String[] t : PLAYER_TABS) {
            sb.append("<a href=\"/players?agent=").append(Http.esc(agentId)).append("&amp;player=").append(Http.esc(uuid))
                    .append("&amp;tab=").append(t[0]).append("\"").append(t[0].equals(tab) ? " aria-current=\"page\"" : "")
                    .append(">").append(Http.esc(t[1])).append("</a>");
        }
        sb.append("</nav>");

        String slug = "pl-" + uuid.replaceAll("[^0-9a-fA-F]", "");
        String body = switch (tab) {
            case "progression" -> playerProgressionTab(session, agentId, e, slug, r);
            case "economie" -> playerEconomyTab(session, agentId, e, slug, r);
            case "moderation" -> playerModerationTab(session, agentId, e, slug, r);
            case "outils" -> playerToolsTab(session, agentId, e, slug, r);
            default -> playerOverviewTab(agentId, e);
        };
        // Après une action, on revient sur cet onglet : chaque formulaire porte l'onglet courant.
        body = body.replace("<input type=\"hidden\" name=\"return\" value=\"/players\">",
                "<input type=\"hidden\" name=\"return\" value=\"/players\"><input type=\"hidden\" name=\"focus\" value=\"tab-"
                        + tab + "\">");
        sb.append("<section class=\"pl-tab\" data-one-form>").append(body).append("</section>");
        return sb.toString();
    }

    /** Types d'action qui concernent un joueur : leur dernier résultat est le retour affiché. */
    private static final java.util.Set<String> PLAYER_ACTION_TYPES = java.util.Set.of(
            "player.ban", "player.unban", "player.kick", "player.send.hub", "player.op", "player.deop",
            "player.whitelist.add", "player.whitelist.remove", "player.variable.set", "player.item.give",
            "player.resetnew.confirm", "player.resetfull.confirm", "economy.credit", "economy.debit",
            "economy.debt.retry", "economy.debt.settle", "mc.rights.sync");

    /**
     * Un seul emplacement de retour : la dernière action faite sur CE joueur, datée. Jamais
     * l'empilement de « Dernière action » contradictoires de l'ancienne fiche ; une action en cours
     * est dite en cours, jamais réussie d'avance.
     */
    private String playerFeedback(String agentId, String uuid) {
        for (AgentActionRow row : store.recentActions(agentId, 200)) {
            if (!PLAYER_ACTION_TYPES.contains(row.type())
                    || !uuid.equalsIgnoreCase(row.params().getOrDefault("player", ""))) {
                continue;
            }
            Instant when = row.completedAt() != null ? row.completedAt() : row.createdAt();
            if (when != null && when.isBefore(Instant.now().minus(java.time.Duration.ofDays(1)))) {
                return "";
            }
            boolean pending = !row.status().terminal();
            boolean ok = row.status() == AgentActionStatus.SUCCESS;
            String tone = pending ? "info" : ok ? "ok" : "err";
            String text = pending ? "en cours…" : row.resultMessage() == null ? row.status().name() : row.resultMessage();
            return "<div class=\"dlg-live tone-" + tone + " pl-feedback\" role=\"status\"><span><strong>"
                    + Http.esc(AgentActionCatalog.spec(row.type()).map(AgentActionCatalog.Spec::label).orElse(row.type())) + "</strong> — "
                    + Http.esc(MiniText.prettifyTokens(text)) + " — " + when(when) + "</span>"
                    + "<a class=\"btn secondary sm\" href=\"/actions/" + Http.esc(row.id()) + "\">Détail</a></div>";
        }
        return "";
    }

    private String playerOverviewTab(String agentId, PlayerCatalog.Entry e) {
        String uuid = e.uuid();
        StringBuilder sb = new StringBuilder("<div class=\"pl-grid\">");
        sb.append("<div class=\"card pl-card\"><h2>Activité</h2><p>").append(e.online()
                ? "Connecté maintenant" + (e.world() == null ? "." : ", dans " + Http.esc(e.world()) + ".")
                : "Hors ligne. Dernière venue : " + localTime(e.lastSeen(), true) + ".").append("</p></div>");
        sb.append("<div class=\"card pl-card\"><h2>RPGQuest</h2><div class=\"d-flex flex-wrap gap-2\">")
                .append("<a class=\"btn secondary sm\" href=\"/quests?agent=").append(Http.esc(agentId)).append("&amp;player=")
                .append(Http.esc(uuid)).append("\">").append(Icons.icon("quests")).append("Ses quêtes</a>")
                .append("<a class=\"btn secondary sm\" href=\"/stories?agent=").append(Http.esc(agentId)).append("&amp;player=")
                .append(Http.esc(uuid)).append("\">").append(Icons.icon("stories")).append("Ses stories</a>")
                .append("<a class=\"btn secondary sm\" href=\"/actions?domain=players&amp;q=").append(Http.esc(shorten(uuid, 8)))
                .append("\">").append(Icons.icon("history")).append("Historique</a></div></div>");
        sb.append("<div class=\"card pl-card\"><h2>Modération</h2><ul class=\"pl-facts-list\">")
                .append("<li>").append(e.banned() ? "<strong>Banni</strong>" + (e.banReason() == null ? ""
                        : " — " + Http.esc(e.banReason())) : "Pas banni").append("</li>")
                .append("<li>").append(e.op() ? "<strong>OP Minecraft</strong>" : "Pas OP Minecraft").append("</li>")
                .append("<li>").append(e.whitelisted() ? "Inscrit sur la whitelist" : "Pas sur la whitelist")
                .append("</li></ul><a href=\"/players?agent=").append(Http.esc(agentId)).append("&amp;player=")
                .append(Http.esc(uuid)).append("&amp;tab=moderation\">Permissions et modération</a></div>");
        sb.append("</div>");
        return sb.toString();
    }

    private String playerProgressionTab(Session session, String agentId, PlayerCatalog.Entry e, String slug,
                                        PlayerRights r) {
        String uuid = e.uuid();
        String name = e.displayName();
        StringBuilder sb = new StringBuilder("<div class=\"card pl-card\"><h2>Quêtes et stories</h2>")
                .append("<div class=\"d-flex flex-wrap gap-2\">")
                .append("<a class=\"btn secondary sm\" href=\"/quests?agent=").append(Http.esc(agentId)).append("&amp;player=")
                .append(Http.esc(uuid)).append("\">").append(Icons.icon("quests")).append("Ses quêtes</a>")
                .append("<a class=\"btn secondary sm\" href=\"/stories?agent=").append(Http.esc(agentId)).append("&amp;player=")
                .append(Http.esc(uuid)).append("\">").append(Icons.icon("stories")).append("Ses stories</a>")
                .append("<a class=\"btn secondary sm\" href=\"/actions?domain=players&amp;q=").append(Http.esc(shorten(uuid, 8)))
                .append("\">").append(Icons.icon("history")).append("Historique</a></div></div>");
        List<String[]> toggles = new ArrayList<>();
        StringBuilder forms = new StringBuilder();
        if (r.varGet()) {
            toggles.add(new String[] {slug + "-f-var", "Lire une variable", "eye", "secondary sm"});
            forms.append(actionCollapse(slug + "-f-var", "<div class=\"card card-body npc-formcard\">"
                    + playerVariableRead(session, agentId, uuid) + "</div>"));
        }
        if (r.reset()) {
            toggles.add(new String[] {slug + "-f-reset1", "Remettre à zéro la progression", "trash", "secondary sm npc-danger"});
            forms.append(actionCollapse(slug + "-f-reset1", "<div class=\"card card-body npc-formcard\">"
                    + playerResetBlock(session, agentId, uuid, name, false) + "</div>"));
            toggles.add(new String[] {slug + "-f-reset2", "Repartir comme un nouveau joueur", "trash", "secondary sm npc-danger"});
            forms.append(actionCollapse(slug + "-f-reset2", "<div class=\"card card-body npc-formcard\">"
                    + playerResetBlock(session, agentId, uuid, name, true) + "</div>"));
        }
        sb.append(actionBlock("Variables et remises à zéro", toggles, forms));
        return sb.toString();
    }

    private String playerEconomyTab(Session session, String agentId, PlayerCatalog.Entry e, String slug,
                                    PlayerRights r) {
        if (!r.ecoRead()) {
            return "<p class=\"muted\">Votre rôle ne permet pas de voir la monnaie des joueurs.</p>";
        }
        String uuid = e.uuid();
        String name = e.displayName();
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"card pl-card\">").append(playerEconomyTools(session, agentId, uuid, name, false))
                .append("</div>");
        List<String[]> toggles = new ArrayList<>();
        StringBuilder forms = new StringBuilder();
        if (r.ecoWrite()) {
            toggles.add(new String[] {slug + "-f-credit", "Créditer", "plus", "secondary sm"});
            forms.append(actionCollapse(slug + "-f-credit", "<div class=\"card card-body npc-formcard\">"
                    + playerMoneyForm(session, agentId, uuid, name, true) + "</div>"));
            toggles.add(new String[] {slug + "-f-debit", "Débiter", "money", "secondary sm"});
            forms.append(actionCollapse(slug + "-f-debit", "<div class=\"card card-body npc-formcard\">"
                    + playerMoneyForm(session, agentId, uuid, name, false) + "</div>"));
        }
        toggles.add(new String[] {slug + "-f-debt", "Récompenses en attente", "gift", "secondary sm"});
        forms.append(actionCollapse(slug + "-f-debt", "<div class=\"card card-body npc-formcard\">"
                + playerRewardDebtTools(session, agentId, uuid, name, r.ecoWrite()) + "</div>"));
        toggles.add(new String[] {slug + "-f-entitlements", "Droits à récompense", "gift", "secondary sm"});
        forms.append(actionCollapse(slug + "-f-entitlements", "<div class=\"card card-body npc-formcard\">"
                + playerEntitlementTools(session, agentId, uuid, name, r.ecoWrite()) + "</div>"));
        sb.append(actionBlock("Actions", toggles, forms));
        return sb.toString();
    }

    private String playerModerationTab(Session session, String agentId, PlayerCatalog.Entry e, String slug,
                                       PlayerRights r) {
        String uuid = e.uuid();
        String name = e.displayName();
        StringBuilder sb = new StringBuilder("<div class=\"card pl-card\"><h2>État actuel</h2><dl class=\"npc-dl\">");
        dlRow(sb, "Bannissement", e.banned() ? "<strong>actif</strong>" + (e.banReason() == null ? ""
                : " — " + Http.esc(e.banReason())) : "aucun");
        dlRow(sb, "OP Minecraft", e.op() ? "<strong>oui</strong>" : "non");
        dlRow(sb, "Whitelist", e.whitelisted() ? "inscrit" : "pas inscrit");
        dlRow(sb, "Droits de construction", "<span class=\"muted\">gérés par les groupes PlugAdmin du compte lié "
                + "à ce joueur (page « Utilisateurs »)</span>");
        sb.append("</dl>");
        sb.append("<p class=\"form-text\">OP Minecraft, rôle PlugAdmin et droits de construction sont trois choses "
                + "distinctes : changer l'une ne change pas les autres. La whitelist n'empêche quelqu'un d'entrer que "
                + "si elle est activée sur le serveur — le résultat d'un ajout le précise.</p>");
        if (r.mcRights()) {
            sb.append(readForm(session, agentId, "mc.rights.read", "/players", uuid, "Lire ses droits en jeu"));
            latestForPlayer(agentId, "mc.rights.read", uuid).ifPresent(row -> sb.append(readResult("Droits en jeu", row)));
        }
        sb.append("</div>");

        List<String[]> toggles = new ArrayList<>();
        StringBuilder forms = new StringBuilder();
        if (r.moderate()) {
            if (e.banned()) {
                toggles.add(new String[] {slug + "-f-unban", "Débannir", "check", "secondary sm"});
                forms.append(actionCollapse(slug + "-f-unban", "<div class=\"card card-body npc-formcard\">"
                        + playerUnbanForm(session, agentId, uuid, name) + "</div>"));
            } else {
                toggles.add(new String[] {slug + "-f-ban", "Bannir", "lock", "secondary sm npc-danger"});
                forms.append(actionCollapse(slug + "-f-ban", "<div class=\"card card-body npc-formcard\">"
                        + playerBanForm(session, agentId, uuid, name) + "</div>"));
            }
            if (e.online()) {
                toggles.add(new String[] {slug + "-f-kick", "Expulser", "open", "secondary sm"});
                forms.append(actionCollapse(slug + "-f-kick", "<div class=\"card card-body npc-formcard\">"
                        + playerKickForm(session, agentId, uuid, name, true) + "</div>"));
                toggles.add(new String[] {slug + "-f-rescue", "Renvoyer au Hub", "travel", "secondary sm"});
                forms.append(actionCollapse(slug + "-f-rescue", "<div class=\"card card-body npc-formcard\">"
                        + playerRescueForm(session, agentId, uuid, name, true) + "</div>"));
            }
            toggles.add(new String[] {slug + "-f-wl", e.whitelisted() ? "Retirer de la whitelist" : "Inscrire sur la whitelist",
                    "users", "secondary sm"});
            forms.append(actionCollapse(slug + "-f-wl", "<div class=\"card card-body npc-formcard\">"
                    + playerWhitelistForm(session, agentId, uuid, name, e.whitelisted()) + "</div>"));
        }
        if (r.op()) {
            toggles.add(new String[] {slug + "-f-op", e.op() ? "Retirer OP" : "Accorder OP", "admin", "secondary sm npc-danger"});
            forms.append(actionCollapse(slug + "-f-op", "<div class=\"card card-body npc-formcard\">"
                    + playerOpForm(session, agentId, uuid, name, e.op()) + "</div>"));
        }
        sb.append(actionBlock("Actions", toggles, forms));
        if (r.moderate() && !e.online()) {
            sb.append("<p class=\"form-text\">« Expulser » et « Renvoyer au Hub » ne concernent qu'un joueur "
                    + "connecté : ils réapparaissent ici dès sa prochaine connexion. « Bannir » fonctionne hors ligne.</p>");
        }
        return sb.toString();
    }

    private String playerToolsTab(Session session, String agentId, PlayerCatalog.Entry e, String slug, PlayerRights r) {
        String uuid = e.uuid();
        String name = e.displayName();
        List<String[]> toggles = new ArrayList<>();
        StringBuilder forms = new StringBuilder();
        if (r.varSet()) {
            toggles.add(new String[] {slug + "-f-varset", "Écrire une variable", "edit", "secondary sm"});
            forms.append(actionCollapse(slug + "-f-varset", "<div class=\"card card-body npc-formcard\">"
                    + playerVariableWrite(session, agentId, uuid) + "</div>"));
        }
        if (r.give() && e.online()) {
            List<Object> knownItems = latestDetails(agentId, "item.list").map(d -> asList(d.get("items"))).orElse(List.of());
            toggles.add(new String[] {slug + "-f-give", "Donner un objet", "gift", "secondary sm"});
            forms.append(actionCollapse(slug + "-f-give", "<div class=\"card card-body npc-formcard\">"
                    + playerGiveForm(session, agentId, uuid, name, knownItems) + "</div>"));
        }
        StringBuilder sb = new StringBuilder();
        if (!r.tools()) {
            return "<p class=\"muted\">Votre rôle ne donne accès à aucun outil avancé.</p>";
        }
        sb.append("<p class=\"form-text\">Outils de test : ils agissent directement, sans rejouer de progression.</p>");
        sb.append(actionBlock("Outils", toggles, forms));
        if (r.give() && !e.online()) {
            sb.append("<p class=\"form-text\">« Donner un objet » n'est possible que si le joueur est connecté.</p>");
        }
        return sb.toString();
    }

    /** Une rangée de boutons ; un seul formulaire ouvert à la fois (data-one-form sur l'onglet). */
    private static String actionBlock(String title, List<String[]> toggles, StringBuilder forms) {
        if (toggles.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<div class=\"card pl-card\"><h2>").append(Http.esc(title))
                .append("</h2><div class=\"npc-actions d-flex flex-wrap gap-2\">");
        for (String[] t : toggles) {
            sb.append(actionToggle(t[0], t[1], t[2], t[3]));
        }
        return sb.append("</div>").append(forms).append("</div>").toString();
    }

    /** Un résultat de lecture (solde, droits, aperçu…), daté. */
    private String readResult(String label, AgentActionRow row) {
        String line = resultLine(label, row);
        String mark = Http.esc(label) + " :</span>";
        int i = line.indexOf(mark);
        return i < 0 ? line : line.substring(0, i) + Http.esc(label) + " du " + when(at(row)) + " :</span>"
                + line.substring(i + mark.length());
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
        StringBuilder sb = new StringBuilder("<h2>Porte-monnaie</h2>");
        sb.append("<p class=\"form-text\">Le solde vient du porte-monnaie enregistré du joueur : c'est la seule "
                + "source de vérité. Aucun objet d'inventaire n'est compté.</p>");
        sb.append(formStart(session, agentId, "economy.balance", "/players", uuid));
        sb.append("<input type=\"hidden\" name=\"history\" value=\"20\">");
        sb.append("<button class=\"btn secondary sm\" type=\"submit\">")
                .append(Icons.icon("refresh")).append("Lire le solde et le journal</button></form>");
        latestForPlayer(agentId, "economy.balance", uuid)
                .ifPresent(row -> sb.append(readResult("Solde", row)));
        return sb.toString();
    }

    /**
     * Créditer ou débiter (issue #140) : montant entier, raison obligatoire (elle part au journal),
     * confirmation. Un débit ne rend jamais le solde négatif ; un crédit ne règle aucune dette.
     */
    private String playerMoneyForm(Session session, String agentId, String uuid, String name, boolean credit) {
        String type = credit ? "economy.credit" : "economy.debit";
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">").append(credit ? "Créditer " : "Débiter ")
                .append(Http.esc(name)).append("</p>");
        sb.append("<p class=\"form-text\">").append(credit
                ? "Crée de la monnaie, tracée avec sa raison. Ce n'est pas le règlement d'une récompense en "
                        + "attente : après une compensation, marquez-la « réglée à la main »."
                : "Si le solde ne suffit pas, rien n'est retiré et le résultat le dit.").append("</p>");
        sb.append(formStart(session, agentId, type, "/players", uuid));
        sb.append("<label class=\"form-label\">Montant (1 à ").append(AgentActionCatalog.MAX_ECONOMY_AMOUNT).append(")</label>");
        sb.append("<input class=\"form-control mb-2\" type=\"number\" min=\"1\" max=\"")
                .append(AgentActionCatalog.MAX_ECONOMY_AMOUNT).append("\" name=\"amount\" required inputmode=\"numeric\">");
        sb.append("<label class=\"form-label\">Raison (obligatoire)</label>");
        sb.append("<input class=\"form-control mb-2\" name=\"reason\" maxlength=\"200\" required>");
        sb.append(confirmBox(credit ? "Je confirme la création de monnaie pour « " + name + " »."
                : "Je confirme le retrait de monnaie à « " + name + " »."));
        sb.append("<button class=\"btn\" type=\"submit\">").append(credit ? "Créditer" : "Débiter").append("</button></form>");
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
        survey.ifPresent(row -> sb.append(readResult("Dernier relevé", row)));
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
                sb.append("<td><strong>").append(asLong(debt.get("amount"))).append("</strong> ").append(Http.esc(RewardText.currency())).append("</td>");
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

        return sb.toString();
    }

    /**
     * Droits à récompense (issue #422, docs/REWARD_ENTITLEMENTS.md) : ce que le joueur a gagné
     * (événement, concours, déclaration manuelle) et ce qu'il en est advenu. Le plugin seul remet,
     * une fois, à la connexion du joueur et inventaire libre ; le panel ne fait que déclarer,
     * consulter et révoquer un droit encore en attente.
     */
    private String playerEntitlementTools(Session session, String agentId, String uuid, String name,
                                          boolean canWrite) {
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">").append(Icons.icon("gift"))
                .append("Droits à récompense — ").append(Http.esc(name)).append("</p>");
        sb.append("<p class=\"muted\">Un droit est remis <strong>une seule fois</strong>, par le serveur, "
                + "quand le joueur est connecté et a la place dans son inventaire. Tant qu'il ne l'a pas, "
                + "le droit reste <em>en attente</em> : rien n'est jeté au sol, rien n'est perdu.</p>");

        sb.append(formStart(session, agentId, "entitlement.list", "/players", uuid));
        sb.append("<input type=\"hidden\" name=\"limit\" value=\"50\">");
        sb.append("<button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\">")
                .append(Icons.icon("refresh")).append("Lire les droits du joueur</button></form>");

        Optional<AgentActionRow> survey = latestForPlayer(agentId, "entitlement.list", uuid);
        survey.ifPresent(row -> sb.append(readResult("Dernier relevé", row)));
        Map<String, Object> details = survey.flatMap(this::detailsOf).orElse(Map.of());
        List<Object> rows = asList(details.get("entitlements"));
        List<Object> rewards = asList(details.get("rewards"));
        boolean enabled = Boolean.TRUE.equals(details.get("enabled"));

        if (survey.isEmpty()) {
            sb.append(Ui.banner("info", "Aucun relevé encore demandé pour ce joueur : le bouton ci-dessus "
                    + "interroge le serveur."));
        } else {
            if (Boolean.FALSE.equals(details.get("available"))) {
                sb.append(Ui.banner("warn", "Ce serveur ne gère pas encore les droits à récompense."));
            } else if (!enabled) {
                sb.append(Ui.banner("info", "Fonction désactivée sur ce serveur (<code>entitlements.yml</code> : "
                        + "<code>enabled: false</code>) : aucune remise, aucune nouvelle déclaration."));
            }
            sb.append(rows.isEmpty() ? Ui.banner("ok", "Aucun droit à récompense pour ce joueur.")
                    : entitlementTable(session, agentId, uuid, name, rows, canWrite));
        }
        if (canWrite && enabled) {
            sb.append(entitlementDeclareForm(session, agentId, uuid, name, rewards));
        }
        return sb.toString();
    }

    private String entitlementTable(Session session, String agentId, String uuid, String name, List<Object> rows,
                                    boolean canWrite) {
        StringBuilder sb = new StringBuilder("<div class=\"table-responsive\"><table class=\"table table-sm "
                + "align-middle\"><thead><tr><th>Récompense</th><th>Origine</th><th>État</th></tr></thead><tbody>");
        for (Object raw : rows) {
            Map<String, Object> e = asMap(raw);
            String status = str(e.get("status"));
            sb.append("<tr><td>").append(Http.esc(str(e.get("label"))))
                    .append("<br><span class=\"muted\">").append(Http.esc(str(e.get("rewardKey")))).append("</span></td>");
            sb.append("<td>").append(switch (str(e.get("source"))) {
                        case "EVENT" -> "Événement ";
                        case "BOSS" -> "Boss communautaire — ";
                        default -> "Déclaré à la main — ";
                    })
                    .append(Http.esc(str(e.get("sourceRef"))))
                    .append("<br><span class=\"muted\">").append(when(instant(str(e.get("createdAt"))))).append("</span></td>");
            sb.append("<td>").append(entitlementState(e, status)).append("</td></tr>");
            if (canWrite && "PENDING".equals(status)) {
                sb.append("<tr><td colspan=\"3\">").append(entitlementRevokeForm(session, agentId, uuid, name,
                        str(e.get("key")))).append("</td></tr>");
            }
        }
        return sb.append("</tbody></table></div>").toString();
    }

    /** État RÉEL : une attente avec échecs le dit, un droit remis ou révoqué donne sa date et son motif. */
    private static String entitlementState(Map<String, Object> e, String status) {
        return switch (status) {
            case "DELIVERED" -> "<span class=\"badge text-bg-success\">Remis</span><br><span class=\"muted\">"
                    + when(instant(str(e.get("deliveredAt")))) + "</span>";
            case "REVOKED" -> "<span class=\"badge text-bg-secondary\">Révoqué</span><br><span class=\"muted\">"
                    + Http.esc(str(e.get("revokeReason"))) + "</span>";
            default -> {
                long attempts = asLong(e.get("attempts"));
                String error = str(e.get("lastError"));
                String badge = attempts == 0 && error.isBlank()
                        ? "<span class=\"badge text-bg-secondary\">En attente</span>"
                        : "<span class=\"badge text-bg-warning\">En attente — " + attempts + " essai(s)</span>";
                yield error.isBlank() ? badge : badge + "<br><span class=\"muted\">" + Http.esc(error) + "</span>";
            }
        };
    }

    private static Instant instant(String iso) {
        try {
            return iso.isBlank() ? null : Instant.parse(iso);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private String entitlementRevokeForm(Session session, String agentId, String uuid, String name, String key) {
        StringBuilder sb = new StringBuilder(formStart(session, agentId, "entitlement.revoke", "/players", uuid));
        sb.append("<input type=\"hidden\" name=\"key\" value=\"").append(Http.esc(key)).append("\">");
        sb.append("<label class=\"form-label\">Raison de la révocation (obligatoire)</label>");
        sb.append("<input class=\"form-control mb-2\" name=\"reason\" maxlength=\"200\" required>");
        sb.append(confirmBox("Je confirme que « " + name + " » ne doit pas recevoir cette récompense."));
        sb.append("<button class=\"btn btn-sm btn-outline-warning\" type=\"submit\">Révoquer ce droit</button></form>");
        return sb.toString();
    }

    private String entitlementDeclareForm(Session session, String agentId, String uuid, String name,
                                          List<Object> rewards) {
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">Déclarer un droit</p>");
        sb.append("<p class=\"muted\">La <strong>référence</strong> identifie le gain (ex. "
                + "<code>concours-noel-2026</code>) : la renvoyer pour le même joueur ne crée rien de plus.</p>");
        sb.append(formStart(session, agentId, "entitlement.declare", "/players", uuid));
        sb.append("<label class=\"form-label\">Récompense (catalogue <code>entitlements.yml</code>)</label>");
        if (rewards.isEmpty()) {
            sb.append("<input class=\"form-control mb-2\" name=\"reward\" maxlength=\"64\" required>");
        } else {
            sb.append("<select class=\"form-select mb-2\" name=\"reward\" required>");
            for (Object raw : rewards) {
                Map<String, Object> reward = asMap(raw);
                sb.append("<option value=\"").append(Http.esc(str(reward.get("key")))).append("\">")
                        .append(Http.esc(str(reward.get("label")))).append(" (").append(Http.esc(str(reward.get("key"))))
                        .append(")</option>");
            }
            sb.append("</select>");
        }
        sb.append("<label class=\"form-label\">Référence (obligatoire)</label>");
        sb.append("<input class=\"form-control mb-2\" name=\"reference\" maxlength=\"64\" required "
                + "pattern=\"[A-Za-z0-9_.\\-]{1,64}\">");
        sb.append(confirmBox("Je confirme que « " + name + " » a gagné cette récompense."));
        sb.append("<button class=\"btn btn-sm\" type=\"submit\">Déclarer le droit</button></form>");
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
        // Issue #210 : la cible et l'action en clair, une seule case à cocher, aucun texte à retaper.
        sb.append("<p>Joueur ciblé : <strong>").append(Http.esc(name)).append("</strong> <span class=\"muted\">(UUID ")
                .append(Http.esc(uuid)).append(")</span><br>Action : <strong>")
                .append(currentlyOp ? "retirer le statut OP" : "accorder le statut OP").append("</strong></p>");
        sb.append(formStart(session, agentId, type, "/players", uuid));
        sb.append("<label class=\"form-label\">Raison (facultative, conservée dans le journal d'audit)</label>");
        sb.append("<input class=\"form-control mb-2\" name=\"reason\" maxlength=\"200\">");
        sb.append(confirmBox(currentlyOp ? "Je confirme le retrait d'OP à « " + name + " »."
                : "Je confirme l'élévation OP de « " + name + " »."));
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

    private String playerVariableRead(Session session, String agentId, String uuid) {
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">Lire une variable</p>");
        sb.append("<p class=\"form-text\">Fonctionne aussi hors ligne. Lecture seule.</p>");
        sb.append(formStart(session, agentId, "player.variable.get", "/players", uuid));
        sb.append("<label class=\"form-label\">Nom de la variable</label><input class=\"form-control\" list=\"varkeys-")
                .append(Http.esc(uuid)).append("\" name=\"key\" value=\"CLAIM_TIER_1\" autocomplete=\"off\">");
        sb.append(variableKeys(uuid));
        sb.append("<button class=\"btn sm\" type=\"submit\">Lire</button></form>");
        sb.append(variableKnownValue(agentId, uuid));
        return sb.toString();
    }

    /**
     * Issue #319 — la dernière valeur RELUE sur le serveur pour ce joueur (lecture, ou relecture
     * après écriture), avec sa clé et sa date. « absente » n'est jamais confondue avec « false ».
     */
    private String variableKnownValue(String agentId, String uuid) {
        AgentActionRow best = null;
        for (AgentActionRow r : store.recentActions(agentId, 300)) {
            boolean variable = "player.variable.get".equals(r.type()) || "player.variable.set".equals(r.type());
            if (variable && uuid.equalsIgnoreCase(r.params().getOrDefault("player", "")) && r.status().terminal()
                    && detailsOf(r).map(d -> d.containsKey("present")).orElse(false)) {
                best = r;
                break;
            }
        }
        if (best == null) {
            return "<p class=\"form-text\">Aucune valeur relue pour ce joueur : « Lire » interroge le serveur.</p>";
        }
        Map<String, Object> d = detailsOf(best).orElse(Map.of());
        String key = str(d.get("key"));
        boolean present = Boolean.TRUE.equals(d.get("present"));
        String value = present ? cleanNull(str(d.get("value"))) : "";
        if (present && value.isEmpty()) {
            value = cleanNull(best.resultValue());
        }
        return "<div class=\"pl-varstate\"><span class=\"pl-varstate-k\">Valeur actuelle sur le serveur</span> <code>"
                + Http.esc(key) + "</code> = " + (present ? "<strong>" + Http.esc(value) + "</strong>"
                        : "<em>absente</em> (jamais posée)")
                + " <span class=\"muted\">— relue le " + when(at(best)) + "</span></div>";
    }

    private String playerVariableWrite(Session session, String agentId, String uuid) {
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">Écrire une variable</p>");
        sb.append(Ui.banner("warn", "Outil de test : écrire une variable ne rejoue pas la progression qui "
                + "l'aurait posée (aucune récompense, aucune étape)."));
        sb.append(formStart(session, agentId, "player.variable.set", "/players", uuid));
        sb.append("<label class=\"form-label\">Nom de la variable</label><input class=\"form-control\" list=\"varkeys-")
                .append(Http.esc(uuid)).append("-w\" name=\"key\" value=\"CLAIM_TIER_1\" autocomplete=\"off\">");
        sb.append(variableKeys(uuid + "-w"));
        sb.append("<label class=\"form-label\">Nouvelle valeur</label><input class=\"form-control\" type=\"text\" "
                + "name=\"value\" value=\"\" required autocomplete=\"off\" placeholder=\"valeur à écrire\">");
        sb.append("<p class=\"form-text\">CLAIM_TIER_1, CLAIM_TIER_2… : <code>true</code> ou <code>false</code>. "
                + "STARTER_KIT_TIER : un nombre entier (1, 2, 3…). Toute autre valeur est refusée pour ces clés.</p>");
        sb.append(confirmBox("Je comprends que c'est un outil de test."));
        sb.append("<button class=\"btn sm\" type=\"submit\">Écrire puis relire</button></form>");
        sb.append(variableKnownValue(agentId, uuid));
        return sb.toString();
    }

    private static String variableKeys(String id) {
        StringBuilder sb = new StringBuilder("<datalist id=\"varkeys-").append(Http.esc(id)).append("\">");
        for (String k : AgentActionCatalog.KNOWN_VARIABLE_KEYS) {
            sb.append("<option value=\"").append(Http.esc(k)).append("\">");
        }
        return sb.append("</datalist>").toString();
    }

    /** Donner un objet du catalogue (1 à 64) — joueur connecté uniquement, avec confirmation. */
    private String playerGiveForm(Session session, String agentId, String uuid, String name, List<Object> knownItems) {
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">Donner un objet à ").append(Http.esc(name)).append("</p>");
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
                        .append(Http.esc(dn.isEmpty() ? str(it.get("id")) : dn)).append("</option>");
            }
            sb.append("</select>");
        }
        sb.append("<label class=\"form-label\">Quantité (1 à 64)</label>"
                + "<input class=\"form-control\" type=\"number\" name=\"amount\" value=\"1\" min=\"1\" max=\"64\" inputmode=\"numeric\">");
        sb.append(confirmBox("Je confirme la remise de l'objet à « " + name + " »."));
        sb.append("<button class=\"btn sm\" type=\"submit\">Donner</button></form>");
        return sb.toString();
    }

    /**
     * Un bloc de reset. {@code full} choisit la portée : progression RPGQuest seule (inventaire
     * conservé) ou reset « nouveau joueur complet » (inventaire vidé).
     */
    private String playerResetBlock(Session session, String agentId, String uuid, String name, boolean full) {
        String previewType = full ? "player.resetfull.preview" : "player.resetnew.preview";
        String confirmType = full ? "player.resetfull.confirm" : "player.resetnew.confirm";
        Permission needed = full ? Permission.ACTION_PLAYER_RESET_FULL : Permission.ACTION_PLAYER_RESET;

        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">").append(Icons.icon("trash"))
                .append(full ? "Repartir comme un nouveau joueur — vide l'inventaire"
                        : "Remettre à zéro la progression — garde l'inventaire")
                .append("</p><p class=\"form-text\">Fonctionne aussi hors ligne : la progression est remise à zéro "
                        + "tout de suite, le nettoyage de l'inventaire se fait à sa prochaine connexion. Faites "
                        + "d'abord l'aperçu : il n'écrit rien.</p>");

        // Ce qui est CONSERVÉ d'abord : c'est la question que l'administrateur se pose, et celle à
        // laquelle l'ancien écran ne répondait pas.
        sb.append("<dl class=\"npc-dl\">");
        dlRow(sb, "Conserve", full
                ? "le profil et l'UUID, l'économie, les backpacks, les annonces de marché, les blocs "
                        + "construits et les Waystones globales"
                : "<strong>l'inventaire Minecraft et l'équipement</strong>, le profil et l'UUID, "
                        + "l'économie, les backpacks, les annonces de marché, les blocs construits");
        dlRow(sb, "Réinitialise", "les quêtes (actives, progression, terminées, quête suivie), les "
                + "stories, les variables et unlocks dont CLAIM_TIER_1, la progression RPG, les "
                + "découvertes de Waystones, les cooldowns, le claim principal (données de "
                + "protection — les blocs restent), le <strong>droit au kit de départ</strong> et le "
                + "<strong>palier de kit (retour au palier 1)</strong>");
        dlRow(sb, "Inventaire", full
                ? "<strong>TOUT est vidé</strong> : inventaire, armure, main secondaire, curseur et "
                        + "coffre de l'Ender"
                : "seuls les objets RPGQuest (reconnus par PDC) sont retirés");
        sb.append("</dl>");

        if (!full) {
            // L'avertissement que #235 exige mot pour mot : le droit revient, les objets restent.
            sb.append(Ui.banner("warning", "Le droit au kit de départ est rétabli, mais un kit déjà "
                    + "reçu reste physiquement dans l'inventaire : ce joueur pourra donc demander un "
                    + "second kit. Les outils du kit sont des objets vanilla, indiscernables de ceux "
                    + "qu'il a fabriqués — c'est pourquoi ils ne sont pas retirés « intelligemment ». "
                    + "Pour un état réellement neuf, utilisez le reset complet ci-dessous."));
        } else {
            sb.append(Ui.banner("error", "Vider l'inventaire d'un joueur ne se défait pas. Réservé "
                    + "aux tests d'onboarding : à n'utiliser sur un vrai joueur qu'à sa demande."));
        }

        // Issue #317 : le dernier reset CONNU de cette portée, daté et attribué — jamais une bannière
        // de succès intemporelle prise pour la confirmation de ce qu'on est en train de faire.
        Optional<AgentActionRow> lastReset = latestForPlayer(agentId, confirmType, uuid);
        sb.append("<p class=\"form-text pl-history\">").append(lastReset.map(r -> "Dernière remise à zéro connue de cette "
                + "portée : " + when(at(r)) + (r.createdBy() == null ? "" : " par " + Http.esc(r.createdBy())) + " — "
                + (r.status().terminal() ? Http.esc(MiniText.prettifyTokens(r.resultMessage() == null ? r.status().name()
                        : r.resultMessage())) : "en cours…")).orElse("Aucune remise à zéro de cette portée connue pour ce joueur."))
                .append("</p>");
        sb.append(readForm(session, agentId, previewType, "/players", uuid, "Aperçu (aucune écriture)"));
        latestForPlayer(agentId, previewType, uuid).ifPresent(row -> {
            sb.append(readResult("Aperçu", row));
            sb.append("<p class=\"form-text\">Un aperçu ne modifie rien : il décrit ce qui serait fait maintenant.</p>");
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

        if (!perms.can(session.effective(), needed)) {
            sb.append("<p class=\"field-help\">Vous n'avez pas le droit d'exécuter ce reset. L'aperçu "
                    + "ci-dessus reste disponible.</p>");
            return sb.toString();
        }

        sb.append("<div class=\"danger-zone\"><div class=\"dz-title\">⚠ Action irréversible</div>");
        sb.append(formStart(session, agentId, confirmType, "/players", uuid));
        sb.append(confirmBox(full
                ? "Je confirme la remise à zéro de la progression de « " + name + " » ET le vidage "
                        + "de son inventaire, de son équipement et de son coffre de l'Ender."
                : "Je confirme la remise à zéro de la progression RPGQuest de « " + name
                        + " » (son inventaire est conservé)."));
        sb.append("<button class=\"btn btn-danger\" type=\"submit\">")
                .append(full ? "Repartir comme un nouveau joueur (vide l'inventaire)"
                        : "Remettre à zéro la progression (garde l'inventaire)")
                .append("</button></form>");
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
                        + (perms.can(session.effective(), Permission.NPC_READ)
                                ? "<a class=\"btn secondary\" href=\"/quests/catalogs\">" + Icons.icon("book")
                                        + "Catalogues de quêtes</a>" : "")
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
        // Issue #47 : l'état DEV du contenu. En orange tant qu'il manque, parce que sans lui chaque
        // fiche doit répondre « état inconnu » — ce qui est honnête mais inutilisable.
        boolean devKnown = devContentIndex(agentId).available();
        if (perms.can(session.effective(), Permission.CONTENT_READ)) {
            questBar += compactRefresh(session, agentId, "content.dev.state", "État DEV",
                    devKnown ? "btn-outline-secondary" : "btn-warning", "/quests");
        }
        sb.append(listCatbar("Catalogue", questBar));
        if (!devKnown) {
            sb.append(Ui.banner("warn", "L'état du contenu sur le serveur DEV n'est pas encore "
                    + "relevé : chaque fiche affiche « état inconnu » et ne peut pas proposer de "
                    + "publier. Cliquer sur <strong>« État DEV »</strong> pour le relever."));
        }
        if (!itemsLoaded) {
            sb.append(Ui.banner("warn", "Le catalogue des objets Minecraft n'est pas encore chargé : "
                    + "les listes <strong>Icône</strong> et <strong>Récompense d'objet</strong> de "
                    + "l'éditeur se limitent à une courte liste de dépannage (76 entrées, dont deux "
                    + "épées seulement). Cliquer sur <strong>« Objets Minecraft »</strong> pour "
                    + "récupérer la liste complète depuis le serveur."));
        }
        List<Object> runtimeQuests = latestDetails(agentId, "quest.list").map(d -> asList(d.get("quests"))).orElse(List.of());
        List<MergedRow> merged = mergeQuestRows(runtimeQuests);
        // Issue #241 : la quête « ouverte » (fiche dépliée, actions qui la ciblent) survit à la
        // sélection d'un joueur et aux actions, par le paramètre « focus ».
        java.util.Set<String> runtimeKeys = new java.util.LinkedHashSet<>();
        runtimeQuests.forEach(o -> runtimeKeys.add(plainKey(str(asMap(o).get("id")))));
        QuestPlayerContext questCtx = new QuestPlayerContext(player, cleanFocus(q.get("focus")), runtimeKeys);
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
            // Issue #13 : second groupe de puces, combiné en ET avec le premier — les quêtes
            // secondaires renouvelables des PNJ récurrents (« Rencontres ») d'un côté, le reste de l'autre.
            sb.append("<div class=\"npc-filters\" data-filter-chips=\"quests\" aria-label=\"Type de quête\">")
                    .append(filterBtn("", "Tous types", true))
                    .append(filterBtn("main", "Principales", false))
                    .append(filterBtn("side", "Secondaires / Rencontres", false))
                    .append("</div>");
            sb.append("<p class=\"count-note\" data-count-note data-noun=\"qu\u00eate\">" + merged.size() + " qu\u00eate(s)</p>");
            sb.append("<div class=\"accordion npc-accordion\" id=\"quests-accordion\">");
            int qi = 0;
            for (MergedRow mr : merged) {
                sb.append(renderQuestAccordionItem(mr.data(), qi++, questTitles, knownQuestKeys, knownNpcKeys,
                        canEditQuests, canDeleteContent, mr.state(), session, agentId,
                        devContentIndex(agentId), questCtx));
            }
            sb.append("</div>");
        }

        // Les actions admin s'ex\u00e9cutent contre le runtime : on ne propose que les qu\u00eates r\u00e9ellement
        // charg\u00e9es par le serveur (une qu\u00eate \u00ab source uniquement \u00bb \u00e9chouerait c\u00f4t\u00e9 agent).
        List<String> questIds = runtimeQuests.stream().map(o -> str(asMap(o).get("id"))).toList();
        java.util.function.Function<String, String> cityNames = cityDirectory(agentId)::nameOf;
        sb.append(playerStatusSection(session, agentId, player, "/quests", "quest.player.status", "quests",
                r -> renderQuestPlayerRow(r, cityNames), questCtx.focus()));

        if (!player.isEmpty()) {
            // Issue #241 : sélecteur GÉNÉRIQUE, pour agir sur une autre quête que celle ouverte. Il
            // part de la quête ouverte s'il y en a une, et sinon d'aucune — jamais de la première
            // de la liste, qui faisait apparaître « crystal_hunt » sans rapport avec la fiche.
            sb.append("<h3>Actions admin sur une autre quête</h3>");
            sb.append("<p class=\"muted\">Pour la quête d'une fiche, utiliser ses boutons « … cette quête » "
                    + "(section Actions de la fiche). Ce sélecteur sert à choisir volontairement une autre "
                    + "quête.</p>");
            sb.append(questActionForm(session, agentId, player, "quest.start", "Démarrer", questIds, true, questCtx.focus()));
            sb.append(questActionForm(session, agentId, player, "quest.complete", "Compléter (récompenses incluses)", questIds, false, questCtx.focus()));
            sb.append(questActionForm(session, agentId, player, "quest.reset", "Réinitialiser (n'annule pas les récompenses déjà données)", questIds, false, questCtx.focus()));
        }

        return sb.toString();
    }

    /**
     * Issue #13 : quête secondaire de rencontre = catégorie {@code encounter}, ou donneur
     * {@code encounter_*} (npc_id des modèles de PNJ récurrents de biome).
     */
    static boolean isEncounterQuest(String category, String giverId) {
        String giver = giverId == null ? "" : giverId.toLowerCase(java.util.Locale.ROOT);
        giver = giver.substring(giver.indexOf(':') + 1);
        return "encounter".equalsIgnoreCase(category == null ? "" : category.trim()) || giver.startsWith("encounter_");
    }

    /** Délai de ré-acceptation lisible : relevé serveur ({@code cooldownSeconds}) sinon valeur source. */
    static String questCooldownLabel(Map<String, Object> qd) {
        Object seconds = qd.get("cooldownSeconds");
        if (seconds instanceof Number n && n.longValue() > 0) {
            long total = n.longValue();
            if (total < 60) {
                return total + " s";
            }
            long d = total / 86400;
            long h = (total % 86400) / 3600;
            long min = (total % 3600) / 60;
            String out = (d > 0 ? d + " j " : "") + (h > 0 ? h + " h " : "") + (min > 0 ? min + " min" : "");
            return out.trim();
        }
        return str(qd.get("cooldown"));
    }

    /**
     * Une ligne d'accordion de quête : en-tête = synthèse (titre humain, id technique, badges), corps
     * = sections repliées (Général / Donneur / Prérequis / Objectifs / Récompenses / Diagnostics /
     * Actions). Les diagnostics de référence (prérequis inconnu, donneur sans fiche) sont calculés
     * ici, côté panel, et rendus par {@link DiagnosticHelp}.
     */
    private String renderQuestAccordionItem(Map<String, Object> qd, int idx, Map<String, String> questTitles,
                                            java.util.Set<String> knownQuestKeys, java.util.Set<String> knownNpcKeys,
                                            boolean canEdit, boolean canDelete, CatalogState state,
                                            Session session, String agentId,
                                            com.lodygames.rpgquest.panel.publish.DevContentIndex devIndex,
                                            QuestPlayerContext ctx) {
        String id = str(qd.get("id"));
        boolean focused = !ctx.focus().isEmpty() && plainKey(id).equals(plainKey(ctx.focus()));
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
        // Issue #13 : un donneur encounter_* est le npc_id d'un modèle de PNJ récurrent — il n'a pas de
        // fiche dans npc.list (ses occurrences vivent dans le registre des rencontres), ce n'est pas une panne.
        if (!giverId.isEmpty() && knownNpcKeys != null && !isEncounterQuest("", giverId)
                && !knownNpcKeys.contains(giverId.toLowerCase(java.util.Locale.ROOT))) {
            diags.add(new String[] {"QUEST_GIVER_UNKNOWN", giverId});
        }
        boolean anyWarn = !diags.isEmpty();

        String ftext = Http.esc(id + " " + human + " " + category + " " + giverId + " " + giverName
                + " " + catalogStateKeywords(state));
        StringBuilder sb = new StringBuilder();
        boolean encounter = isEncounterQuest(category, giverId);
        sb.append("<div class=\"accordion-item npc-item\" data-filter-item=\"quests\" data-filter-cat=\"")
                .append(anyWarn ? "warn" : "ok").append(encounter ? " side" : " main")
                .append("\" data-filter-text=\"").append(ftext)
                .append("\" data-res-id=\"").append(Http.esc(id)).append("\">");
        sb.append("<h3 class=\"accordion-header\">");
        sb.append("<button class=\"accordion-button").append(focused ? "" : " collapsed")
                .append(" npc-head\" type=\"button\" data-bs-toggle=\"collapse\" data-bs-target=\"#")
                .append(slug).append("\" aria-expanded=\"").append(focused).append("\" aria-controls=\"")
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
        // Le badge comptait les ÉTAPES en les appelant « objectifs » : une quête à une étape et
        // quatre remises annonçait « 1 objectif ». Les deux nombres sont désormais distincts et
        // nommés correctement, le compte d'objectifs étant la somme réelle sur toutes les étapes.
        int objectiveCount = 0;
        for (Object stObj : steps) {
            Map<String, Object> st = asMap(stObj);
            List<Object> structured = asList(st.get("objectiveDetails"));
            objectiveCount += structured.isEmpty() ? asList(st.get("objectives")).size() : structured.size();
        }
        sb.append("<span class=\"badge text-bg-secondary\">").append(steps.size())
                .append(steps.size() > 1 ? " étapes" : " étape").append("</span>");
        if (objectiveCount > 0) {
            sb.append("<span class=\"badge text-bg-secondary\">").append(objectiveCount)
                    .append(objectiveCount > 1 ? " objectifs" : " objectif").append("</span>");
        }
        int rc = !rewardDetails.isEmpty() ? rewardDetails.size() : rewards.size();
        if (rc > 0) {
            sb.append("<span class=\"badge text-bg-secondary\">").append(rc)
                    .append(rc > 1 ? " récompenses" : " récompense").append("</span>");
        }
        if (Boolean.TRUE.equals(qd.get("repeatable"))) {
            sb.append("<span class=\"badge text-bg-secondary\">répétable</span>");
        }
        String cooldown = questCooldownLabel(qd);
        if (!cooldown.isEmpty()) {
            sb.append("<span class=\"badge text-bg-secondary\">recharge ").append(Http.esc(cooldown)).append("</span>");
        }
        if (encounter) {
            sb.append("<span class=\"badge text-bg-info\">rencontre</span>");
        }
        sb.append(catalogStateBadge(state, "quête"));
        sb.append(anyWarn ? "<span class=\"badge text-bg-warning\">à vérifier</span>"
                : "<span class=\"badge text-bg-success\">OK</span>");
        sb.append("</span></button></h3>");

        sb.append("<div id=\"").append(slug).append("\" class=\"accordion-collapse collapse")
                .append(focused ? " show" : "").append("\" ")
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
        if (!cooldown.isEmpty()) {
            dlRow(sb, "Délai avant de la refaire", Http.esc(cooldown));
        }
        if (encounter) {
            dlRow(sb, "Type", "Secondaire — donnée par un PNJ récurrent de biome (rencontre)");
        }
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
                        ObjectiveText.Objective o = ObjectiveText.fromSummary(asMap(od),
                                cityDirectory(agentId)::nameOf);
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

        // ---- PUBLICATION SUR DEV (issue #47) ----
        // L'identifiant « nu » sert de nom de fichier ; l'identifiant déclaré (avec son préfixe)
        // est celui que le moteur doit porter, et c'est lui qu'on vérifie après rechargement.
        String questSlug = QuestYaml.plainId(id);
        if (!questSlug.isEmpty()) {
            sb.append(publishSection(session, agentId, "quests", questSlug, id, devIndex,
                    "/quests"));
        }

        // ---- ACTIONS ----
        sb.append(questPlayerActions(session, agentId, id, title, ctx));
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

    static String renderQuestPlayerRow(Map<String, Object> r,
                                       java.util.function.Function<String, String> cityNames) {
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
                // Issue #79 : libellé tiré de la structure (FR), l'identifiant technique en second.
                ObjectiveText.Objective o = ObjectiveText.fromStatus(ob, cityNames);
                sb.append(Http.esc(o.label()));
                if (o.hasTarget()) {
                    sb.append(Ui.rawValue(o.rawTarget()));
                }
                sb.append(" — <strong>")
                        .append(Http.esc(str(ob.get("current")))).append("/").append(Http.esc(str(ob.get("required"))))
                        .append("</strong>");
                if ("COMPLETED".equals(str(ob.get("state")))) {
                    sb.append(" <span class=\"badge text-bg-success\">fait</span>");
                }
            }
        } else {
            sb.append("—");
        }
        sb.append("</td></tr>");
        return sb.toString();
    }

    /**
     * Issue #241 — le contexte « joueur sélectionné / quête ouverte » de la page des quêtes.
     *
     * @param focus       identifiant de la quête ouverte, ou vide
     * @param runtimeKeys quêtes chargées par le serveur (clé sans namespace) : seules celles-là
     *                    acceptent une action sur un joueur
     */
    record QuestPlayerContext(String player, String focus, java.util.Set<String> runtimeKeys) {
    }

    /** Un identifiant de quête acceptable comme contexte de page, sinon vide. */
    static String cleanFocus(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return v.matches("[a-z0-9_.:-]{1,80}") ? v : "";
    }

    /**
     * Issue #241 — les actions admin de la fiche d'une quête, sur CETTE quête : Démarrer,
     * Compléter, Réinitialiser, avec son titre et son identifiant sous les yeux. Aucune logique
     * nouvelle : ce sont les mêmes actions agent, avec la quête fixée par la fiche.
     */
    private String questPlayerActions(Session session, String agentId, String questId, String title,
                                      QuestPlayerContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(detailSection("target", "Agir sur un joueur"));
        if (!ctx.runtimeKeys().contains(plainKey(questId))) {
            sb.append("<p class=\"muted\">Cette quête n'est pas chargée par le serveur DEV : la publier et "
                    + "la recharger avant de la démarrer pour un joueur.</p>");
            return sb.toString();
        }
        if (ctx.player().isEmpty()) {
            sb.append("<p class=\"muted\">Choisir d'abord un joueur pour démarrer, compléter ou "
                    + "réinitialiser cette quête. <a href=\"/quests?agent=").append(Http.esc(agentId))
                    .append("&amp;focus=").append(Http.esc(questId)).append("#etat-joueur\">Sélectionner un joueur"
                            + "</a> — la fiche restera ouverte.</p>");
            return sb.toString();
        }
        sb.append("<p>Joueur : <strong>").append(Http.esc(ctx.player())).append("</strong> · quête : ")
                .append(MiniText.html(title)).append(" <code class=\"tid\">").append(Http.esc(questId))
                .append("</code></p><div class=\"npc-actions d-flex flex-wrap gap-2\">");
        sb.append(thisQuestForm(session, agentId, ctx.player(), "quest.start", "Démarrer cette quête",
                questId, true));
        sb.append(thisQuestForm(session, agentId, ctx.player(), "quest.complete",
                "Compléter cette quête (récompenses incluses)", questId, false));
        sb.append(thisQuestForm(session, agentId, ctx.player(), "quest.reset",
                "Réinitialiser cette quête", questId, false));
        sb.append("</div>");
        for (String type : List.of("quest.start", "quest.complete", "quest.reset")) {
            latestForPlayer(agentId, type, ctx.player())
                    .filter(row -> plainKey(row.params().getOrDefault("quest_id", "")).equals(plainKey(questId)))
                    .ifPresent(row -> sb.append(resultLine("Dernier résultat", row)));
        }
        return sb.toString();
    }

    private String thisQuestForm(Session session, String agentId, String player, String type, String label,
                                 String questId, boolean withForce) {
        if (!canRun(session, type)) {
            return "";
        }
        StringBuilder sb = new StringBuilder(formStart(session, agentId, type, "/quests", player));
        sb.append("<input type=\"hidden\" name=\"quest_id\" value=\"").append(Http.esc(questId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"focus\" value=\"").append(Http.esc(questId)).append("\">");
        if (withForce) {
            sb.append("<label class=\"inline\"><input type=\"checkbox\" name=\"force\" value=\"true\"> ignorer les prérequis</label>");
        }
        if (AgentActionCatalog.spec(type).map(AgentActionCatalog.Spec::mutation).orElse(false)) {
            sb.append(confirmBox("Confirmer « " + label + " » pour " + player + "."));
        }
        sb.append("<button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">").append(Http.esc(label))
                .append("</button></form>");
        return sb.toString();
    }

    /** Le rôle porte-t-il toutes les permissions qu'exige ce type d'action ? */
    private boolean canRun(Session session, String type) {
        return AgentActionCatalog.spec(type)
                .map(spec -> spec.requiredPermissions().stream().allMatch(p -> perms.can(session.effective(), p)))
                .orElse(false);
    }

    private String questActionForm(Session session, String agentId, String player, String type, String label,
                                   List<String> questIds, boolean withForce, String focus) {
        StringBuilder sb = new StringBuilder();
        sb.append(formStart(session, agentId, type, "/quests", player));
        if (!focus.isEmpty()) {
            sb.append("<input type=\"hidden\" name=\"focus\" value=\"").append(Http.esc(focus)).append("\">");
        }
        sb.append("<label>Quête</label>").append(questSelect(questIds, focus));
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
                + compactRefresh(session, agentId, "quest.list", "Quêtes", "btn-outline-secondary", "/stories")
                + compactRefresh(session, agentId, "content.dev.state", "État DEV",
                        devContentIndex(agentId).available() ? "btn-outline-secondary" : "btn-warning",
                        "/stories");
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
                    canEditStories, canDeleteStories, mr.state(), session, agentId,
                    devContentIndex(agentId)));
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
                                            boolean canDelete, CatalogState state,
                                            Session session, String agentId,
                                            com.lodygames.rpgquest.panel.publish.DevContentIndex devIndex) {
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

        // ---- PUBLICATION SUR DEV (issue #47) ----
        String storySlug = plainKey(id);
        if (!storySlug.isEmpty()) {
            sb.append(publishSection(session, agentId, "stories", storySlug, id, devIndex,
                    "/stories"));
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
    // ================================================================================
    //  Emplacements de construction (issue #213)
    // ================================================================================

    /**
     * Issue #213 — les emplacements de construction du serveur, projetés en types raisonnables.
     *
     * <p>Lecture du dernier relevé {@code building.site.list} <strong>réussi</strong> uniquement :
     * aucune requête n'est déclenchée. Sans relevé, l'annuaire est explicitement indisponible, et
     * l'écran dit « cliquez sur Rafraîchir » — une liste vide se lirait comme « aucun
     * emplacement », ce qui est une affirmation qu'on n'a pas le droit de faire.</p>
     */
    public com.lodygames.rpgquest.panel.building.BuildingSiteDirectory buildingSiteDirectory(
            String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return com.lodygames.rpgquest.panel.building.BuildingSiteDirectory.unavailable();
        }
        return latestDetails(agentId, "building.site.list")
                .map(com.lodygames.rpgquest.panel.building.BuildingSiteDirectory::from)
                .orElseGet(com.lodygames.rpgquest.panel.building.BuildingSiteDirectory::unavailable);
    }

    /**
     * Page {@code /buildings/sites} : la liste des emplacements, et la fiche de chacun.
     *
     * <p><strong>Pas de bouton « Créer ».</strong> Un emplacement est défini par une position
     * désignée du doigt en jeu ; un formulaire web devrait inventer des coordonnées. La page le dit
     * en tête, avec la commande qui donne l'outil — c'est la question que se posera forcément
     * quiconque arrive ici la première fois.</p>
     */
    /**
     * La bibliothèque, depuis le dernier relevé {@code building.definition.list} réussi.
     *
     * <p>Même règle que pour les emplacements : sans relevé, la bibliothèque est explicitement
     * indisponible, et l'écran dit « cliquez sur Rafraîchir ». Afficher une liste vide reviendrait à
     * affirmer qu'aucun bâtiment n'existe, ce qu'on ne sait pas.</p>
     */
    public com.lodygames.rpgquest.panel.building.BuildingLibraryDirectory buildingLibraryDirectory(
            String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return com.lodygames.rpgquest.panel.building.BuildingLibraryDirectory.unavailable();
        }
        return latestDetails(agentId, "building.definition.list")
                .map(com.lodygames.rpgquest.panel.building.BuildingLibraryDirectory::from)
                .orElseGet(com.lodygames.rpgquest.panel.building.BuildingLibraryDirectory::unavailable);
    }

    /** Le dernier aperçu de pose calculé. Il ne vaut que pour l'emplacement qu'il nomme. */
    public com.lodygames.rpgquest.panel.building.BuildingPlacementPreview buildingPlacementPreview(
            String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return com.lodygames.rpgquest.panel.building.BuildingPlacementPreview.unavailable();
        }
        return latestDetails(agentId, "building.placement.preview")
                .map(com.lodygames.rpgquest.panel.building.BuildingPlacementPreview::from)
                .orElseGet(com.lodygames.rpgquest.panel.building.BuildingPlacementPreview::unavailable);
    }

    /** Le dernier aperçu de transformation calculé. Il ne vaut que pour l'emplacement qu'il nomme. */
    public com.lodygames.rpgquest.panel.building.BuildingRetargetPreview buildingRetargetPreview(
            String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return com.lodygames.rpgquest.panel.building.BuildingRetargetPreview.unavailable();
        }
        return latestDetails(agentId, "building.placement.retarget.preview")
                .map(com.lodygames.rpgquest.panel.building.BuildingRetargetPreview::from)
                .orElseGet(com.lodygames.rpgquest.panel.building.BuildingRetargetPreview::unavailable);
    }

    /**
     * Le dernier journal d'opérations relevé, pour cet emplacement seulement.
     *
     * <p>Un journal relevé pour un autre emplacement n'est pas affiché : des opérations attribuées
     * au mauvais emplacement seraient pires qu'une absence d'historique.</p>
     */
    private List<Map<String, Object>> buildingHistoryFor(String agentId, String siteId) {
        return latestDetails(agentId, "building.placement.history")
                .filter(d -> siteId.equals(str(d.get("site_id"))))
                .map(d -> asList(d.get("lines")).stream().map(AgentPages::asMap).toList())
                .orElseGet(List::of);
    }

    /**
     * Page {@code /buildings/library} : ce qu'on peut poser.
     *
     * <p><strong>Lecture seule, volontairement.</strong> Pas de téléversement, pas d'éditeur, pas de
     * génération : une définition de bâtiment est du contenu déclaratif versionné, qui se modifie
     * dans son fichier YAML et se relit au démarrage. Un formulaire web qui l'écrirait contournerait
     * la revue — et c'est précisément ce qu'on ne veut pas pour quelque chose qui écrase des blocs.
     * </p>
     *
     * <p>Une seule exception, étroite (issue #284) : <em>retirer</em> un bâtiment du catalogue. Le
     * plugin refuse un bâtiment posé ou un exemple embarqué, et archive les fichiers au lieu de les
     * effacer — le retrait reste réversible sur le serveur.</p>
     */
    public String buildingLibrary(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("world", "Bibliothèque de bâtiments",
                "Les bâtiments prêts à être posés, autant de fois que vous voulez. Pour en créer un "
                        + "nouveau, passez par l'Atelier de création.", ""));
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();

        sb.append(agentPicker(agentId, "/buildings/library", ""));
        sb.append("<div class=\"npc-catbar\"><span class=\"npc-catbar-t\">Bibliothèque</span>");
        sb.append(compactRefresh(session, agentId, "building.definition.list", "Rafraîchir",
                "btn-outline-primary", "/buildings/library"));
        if (perms.can(session.effective(), Permission.AI_USE)) {
            sb.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"").append(WorkshopPages.BASE)
                    .append("\">").append(Icons.icon("wrench")).append("Créer un bâtiment</a>");
        }
        sb.append("</div>");

        var library = buildingLibraryDirectory(agentId);
        if (!library.available()) {
            sb.append(Ui.empty("world", "Aucun relevé chargé — cliquer sur « Rafraîchir »."));
            return sb.toString();
        }
        if (!library.engineAvailable()) {
            sb.append(Ui.banner("warning", "Moteur de schematics indisponible : "
                    + (library.engineReason().isEmpty() ? "motif non précisé."
                            : library.engineReason())
                    + " La bibliothèque reste consultable, mais rien ne peut être posé."));
        }
        for (String problem : library.problems()) {
            sb.append(Ui.banner("error", "Fichier refusé — " + problem));
        }
        if (library.total() == 0) {
            sb.append(Ui.empty("world", "Aucun bâtiment en bibliothèque."));
            return sb.toString();
        }

        sb.append("<div class=\"accordion npc-accordion\" id=\"building-accordion\">");
        int index = 0;
        boolean canPlace = library.engineAvailable()
                && perms.can(session.effective(), Permission.BUILDING_PLACE);
        boolean canRetire = perms.can(session.effective(), Permission.BUILDING_DELETE);
        var sites = buildingSiteDirectory(agentId);
        for (var building : library.buildings()) {
            sb.append(buildingDefinitionItem(building, index++, canPlace,
                    workshopZone(session, agentId, building)
                            + (canRetire ? retireZone(session, agentId, building, sites) : "")));
        }
        sb.append("</div>");
        sb.append("<div class=\"card\"><div class=\"card-body\">");
        sb.append("<h3 class=\"h6\">Comment poser un bâtiment</h3>");
        sb.append("<p class=\"field-help\">Ouvrez un bâtiment ci-dessus, puis <strong>Placer sur un "
                + "emplacement</strong> : choisissez un emplacement libre, vérifiez l'emprise et "
                + "l'orientation annoncées, puis confirmez. Rien n'est construit dans le monde avant "
                + "cette confirmation.</p>");
        sb.append("</div></div>");
        return sb.toString();
    }

    private String buildingDefinitionItem(
            com.lodygames.rpgquest.panel.building.BuildingDefinitionView building, int index,
            boolean canPlace) {
        return buildingDefinitionItem(building, index, canPlace, "");
    }

    /**
     * « Retirer de la bibliothèque » (issue #284) : ce qui sera retiré, ce qui reste, et pourquoi
     * c'est refusé quand ça l'est. Le retrait ne concerne QUE le catalogue — ni un emplacement, ni une
     * version d'Atelier — et le texte le distingue explicitement.
     */
    /**
     * Le projet de l'Atelier qui produit ce bâtiment, et « Reprendre la conception » (#290). Le lien
     * est l'identifiant de bibliothèque — affiché, pour qu'aucune homonymie entre un nom de projet,
     * un nom d'emplacement et un nom de bâtiment ne prête à confusion.
     */
    private String workshopZone(Session session, String agentId,
                                com.lodygames.rpgquest.panel.building.BuildingDefinitionView building) {
        if (workshopProjects == null || building.bundled()) {
            return "";
        }
        var projects = workshopProjects.list(agentId).stream()
                .filter(p -> !p.archived() && p.buildingId().equals(building.id())).toList();
        StringBuilder sb = new StringBuilder("<div class=\"field-help\">");
        if (projects.isEmpty()) {
            return sb.append("Aucun projet de l'Atelier ne produit <code class=\"tid\">")
                    .append(Http.esc(building.id())).append("</code>.</div>").toString();
        }
        for (var p : projects) {
            sb.append("<p>Produit par le projet « <a href=\"").append(WorkshopPages.BASE).append("/project?id=")
                    .append(Http.esc(p.id())).append("\">").append(Http.esc(p.name())).append("</a> » de l'Atelier "
                            + "(identifiant <code class=\"tid\">").append(Http.esc(p.buildingId())).append("</code>).</p>");
            if (perms.can(session.effective(), Permission.BUILDING_WRITE)) {
                sb.append("<form method=\"post\" action=\"").append(WorkshopPages.BASE)
                        .append("/project\" class=\"inline\"><input type=\"hidden\" name=\"_csrf\" value=\"")
                        .append(Http.esc(session.csrfToken())).append("\"><input type=\"hidden\" name=\"_action\" "
                                + "value=\"resume\"><input type=\"hidden\" name=\"id\" value=\"")
                        .append(Http.esc(p.id())).append("\"><button class=\"btn btn-sm btn-outline-primary\" "
                                + "type=\"submit\">").append(Icons.icon("edit"))
                        .append("Reprendre la conception</button></form>");
            }
        }
        return sb.append("</div>").toString();
    }

    private String retireZone(Session session, String agentId,
                              com.lodygames.rpgquest.panel.building.BuildingDefinitionView building,
                              com.lodygames.rpgquest.panel.building.BuildingSiteDirectory sites) {
        StringBuilder sb = new StringBuilder("<div class=\"danger-zone\">");
        sb.append(detailSection("trash", "Retirer de la bibliothèque"));
        List<String> usedOn = sites.sites().stream().map(st -> sites.placementFor(st.id()))
                .flatMap(Optional::stream).filter(p -> p.buildingId().equals(building.id()))
                .map(p -> p.siteId()).toList();
        List<String> projects = workshopProjects == null ? List.of()
                : workshopProjects.list(agentId).stream()
                        .filter(p -> !p.archived() && p.buildingId().equals(building.id()))
                        .map(p -> "« " + p.name() + " » (" + p.buildingId() + ")").toList();
        if (building.bundled()) {
            sb.append("<p class=\"field-help\">Exemple fourni avec le plugin : il est recréé à chaque "
                    + "démarrage du serveur, il ne peut donc pas être retiré.</p></div>");
            return sb.toString();
        }
        if (!usedOn.isEmpty()) {
            sb.append(Ui.banner("warning", "<strong>Posé sur " + Http.esc(String.join(", ", usedOn))
                    + ".</strong> Retirez-le d'abord de chaque emplacement (« Retirer le bâtiment et restaurer "
                    + "le terrain ») : un bâtiment posé ne peut pas quitter la bibliothèque."));
            return sb.append("</div>").toString();
        }
        sb.append("<p>Retire <strong>").append(Http.esc(building.name())).append("</strong> (version ")
                .append(building.version()).append(", fichier <code class=\"tid\">")
                .append(Http.esc(building.schematic())).append("</code>) du catalogue : il ne pourra plus être "
                        + "posé. Il n'est posé nulle part.</p><ul class=\"field-help\">"
                        + "<li>Les fichiers sont <strong>archivés</strong> sur le serveur, pas effacés : un "
                        + "retour en arrière reste possible.</li>"
                        + "<li>Il ne réapparaîtra pas au redémarrage.</li>"
                        + "<li>L'historique des poses et les sauvegardes de terrain sont conservés.</li>"
                        + "<li>Ce n'est ni la suppression d'un emplacement, ni celle d'une version de l'Atelier.</li>");
        if (!projects.isEmpty()) {
            sb.append("<li>Projet(s) de l'Atelier qui produisent ce bâtiment : ")
                    .append(Http.esc(String.join(", ", projects)))
                    .append(" — ils sont conservés ; « Réenregistrer dans la bibliothèque » depuis le projet le "
                            + "recréera, et « Reprendre la conception » reste possible.</li>");
        }
        sb.append("</ul>");
        sb.append(formStart(session, agentId, "building.definition.retire", "/buildings/library", ""));
        sb.append("<input type=\"hidden\" name=\"id\" value=\"").append(Http.esc(building.id())).append("\">");
        sb.append(confirmBox("Je retire « " + building.name() + " » de la bibliothèque."));
        sb.append("<button class=\"btn btn-sm btn-outline-danger\" type=\"submit\">").append(Icons.icon("trash"))
                .append("Retirer de la bibliothèque</button></form>");
        return sb.append("</div>").toString();
    }

    private String buildingDefinitionItem(
            com.lodygames.rpgquest.panel.building.BuildingDefinitionView building, int index,
            boolean canPlace, String retireZone) {
        String slug = "building-" + index + "-" + building.id().replaceAll("[^a-z0-9_-]", "-");
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"accordion-item npc-item\">");
        sb.append("<h3 class=\"accordion-header\">");
        sb.append("<button class=\"accordion-button collapsed npc-head\" type=\"button\" ")
                .append("data-bs-toggle=\"collapse\" data-bs-target=\"#").append(slug)
                .append("\" aria-expanded=\"false\" aria-controls=\"").append(slug).append("\">");
        sb.append("<span class=\"npc-head-main\"><span class=\"npc-name\">")
                .append(Http.esc(building.name())).append("</span>");
        sb.append("<code class=\"tid npc-id\">").append(Http.esc(building.id())).append("</code>");
        sb.append("<span class=\"npc-loc\">").append(Http.esc(building.sizeLabel()))
                .append("</span>");
        sb.append("</span><span class=\"npc-head-badges\">");
        sb.append("<span class=\"badge text-bg-secondary\">façade ")
                .append(Http.esc(building.frontLabel())).append("</span>");
        sb.append(building.schematicPresent()
                ? "<span class=\"badge text-bg-success\">schematic présent</span>"
                : "<span class=\"badge text-bg-danger\">schematic absent</span>");
        sb.append("</span></button></h3>");

        sb.append("<div id=\"").append(slug)
                .append("\" class=\"accordion-collapse collapse\" ")
                .append("data-bs-parent=\"#building-accordion\"><div class=\"accordion-body npc-detail\">");
        sb.append(detailSection("world", "Bâtiment"));
        sb.append("<dl class=\"npc-dl\">");
        dlRow(sb, "Identifiant", Ui.id(building.id()));
        dlRow(sb, "Nom", Http.esc(building.name()));
        dlRow(sb, "Dimensions", "<code class=\"tid\">" + Http.esc(building.sizeLabel())
                + "</code> <span class=\"muted\">(largeur × profondeur × hauteur)</span>");
        dlRow(sb, "Volume", building.blockCount() + " blocs");
        dlRow(sb, "Ancre", Http.esc(building.anchorMeaning()) + " <code class=\"tid\">"
                + Http.esc(building.anchorLabel()) + "</code>");
        dlRow(sb, "Façade de référence", Http.esc(building.frontLabel())
                + " <code class=\"tid\">" + Http.esc(building.front()) + "</code>");
        dlRow(sb, "Schematic", "<code class=\"tid\">" + Http.esc(building.schematic()) + "</code>"
                + (building.schematicPresent() ? ""
                        : " <span class=\"badge text-bg-danger\">absent</span>"));
        dlRow(sb, "Matériaux", Http.esc(building.materialsLabel()));
        dlRow(sb, "Version", String.valueOf(building.version()));
        dlRow(sb, "Description", building.description().isEmpty()
                ? "<span class=\"muted\">aucune</span>" : Http.esc(building.description()));
        sb.append("</dl>");
        if (!building.schematicPresent()) {
            sb.append("<p class=\"field-help\">La définition est correcte, c'est le "
                    + "<strong>fichier</strong> qui manque. Il est produit par le plugin au "
                    + "démarrage ; relancez le serveur ou utilisez "
                    + "<code>/rpgadmin building generate</code>.</p>");
        }
        if (canPlace && building.schematicPresent()) {
            sb.append("<div class=\"btnrow\"><a class=\"btn btn-sm btn-primary\" href=\"/buildings/sites?place=")
                    .append(java.net.URLEncoder.encode(building.id(), java.nio.charset.StandardCharsets.UTF_8))
                    .append("\">").append(Icons.icon("world")).append("Placer sur un emplacement</a></div>");
        }
        sb.append("<p class=\"field-help\">Un bâtiment de la bibliothèque ne se modifie pas : pour "
                + "le changer, créez-en une nouvelle version dans l'Atelier de création. Les "
                + "bâtiments déjà posés ne sont pas touchés.</p>");
        sb.append(retireZone);
        sb.append("</div></div></div>");
        return sb.toString();
    }

    public String buildingSites(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("world", "Emplacements de construction",
                "Les lieux marqués en jeu où poser un bâtiment de la bibliothèque. Pour concevoir un "
                        + "nouveau bâtiment, passez par l'Atelier de création.", ""));
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        boolean canWrite = perms.can(session.effective(), Permission.BUILDING_WRITE);
        boolean canDelete = perms.can(session.effective(), Permission.BUILDING_DELETE);
        boolean canPlace = perms.can(session.effective(), Permission.BUILDING_PLACE);
        boolean canRollback = perms.can(session.effective(), Permission.BUILDING_ROLLBACK);
        // Bibliothèque et dernier aperçu : lus ici une seule fois, puis passés à chaque fiche.
        var library = buildingLibraryDirectory(agentId);
        var preview = buildingPlacementPreview(agentId);

        sb.append(agentPicker(agentId, "/buildings/sites", ""));
        sb.append("<div class=\"npc-catbar\"><span class=\"npc-catbar-t\">Catalogue</span>");
        sb.append(compactRefresh(session, agentId, "building.site.list", "Rafraîchir",
                "btn-outline-primary", "/buildings/sites"));
        sb.append("</div>");

        var directory = buildingSiteDirectory(agentId);
        if (!directory.available()) {
            sb.append(Ui.empty("world",
                    "Aucun relevé chargé — cliquer sur « Rafraîchir »."));
            sb.append(howToCreateCard());
            return sb.toString();
        }
        if (directory.total() == 0) {
            sb.append(Ui.empty("world", "Aucun emplacement de construction pour le moment."));
            sb.append(howToCreateCard());
            return sb.toString();
        }

        String worldFilter = str(q.get("world")).trim();
        var shown = directory.inWorld(worldFilter);
        // Issue #268 — arrivée depuis la Bibliothèque (« Placer sur un emplacement ») : le bâtiment
        // choisi est présélectionné sur chaque emplacement libre. Seul un bâtiment connu compte.
        var wanted = library.find(str(q.get("place")).trim());
        if (wanted.isPresent()) {
            long free = shown.stream().filter(site -> directory.placementFor(site.id()).isEmpty()
                    && !"OCCUPIED".equals(site.status())).count();
            sb.append(Ui.banner("info", "Poser « " + Http.esc(wanted.get().name()) + " » : ouvrez "
                    + "la fiche d'un emplacement <strong>libre</strong> (" + free + " sur cette page), "
                    + "le bâtiment y est déjà choisi. Rien n'est construit avant votre confirmation."));
        }
        String wantedId = wanted.map(
                com.lodygames.rpgquest.panel.building.BuildingDefinitionView::id).orElse("");

        sb.append("<p class=\"npc-summary\">").append(directory.total())
                .append(" emplacement(s)");
        if (!worldFilter.isEmpty()) {
            sb.append(" &middot; filtre : <code>").append(Http.esc(worldFilter)).append("</code>");
        }
        sb.append("</p>");
        sb.append(worldFilterBar(directory.worlds(), worldFilter));

        var unloaded = directory.inUnloadedWorlds();
        if (!unloaded.isEmpty()) {
            sb.append(Ui.banner("info", unloaded.size() + " emplacement(s) se trouvent dans un "
                    + "monde que le serveur n'a <strong>pas chargé</strong> en ce moment. Ils "
                    + "restent valides — ils ne sont simplement pas visitables tant que le monde "
                    + "n'est pas chargé."));
        }

        sb.append(Ui.searchToolbar("buildsites",
                "Rechercher un emplacement (identifiant, nom, monde, position…)", ""));
        sb.append("<div class=\"accordion npc-accordion\" id=\"buildsite-accordion\">");
        int index = 0;
        for (var site : shown) {
            sb.append(buildingSiteItem(session, agentId, site, index++, canWrite, canDelete,
                    canPlace, canRollback, directory, library, preview, wantedId));
        }
        sb.append("</div>");
        if (shown.isEmpty()) {
            sb.append(Ui.empty("world", "Aucun emplacement dans ce monde."));
        }
        sb.append(howToCreateCard());
        return sb.toString();
    }

    /** Les puces de filtre par monde. Un lien par monde : aucun script, et l'URL est partageable. */
    private String worldFilterBar(List<String> worlds, String active) {
        if (worlds.size() <= 1) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<div class=\"btnrow\">");
        sb.append("<a class=\"btn btn-sm ")
                .append(active.isEmpty() ? "btn-primary" : "btn-outline-secondary")
                .append("\" href=\"/buildings/sites\">Tous les mondes</a>");
        for (String world : worlds) {
            sb.append("<a class=\"btn btn-sm ")
                    .append(world.equals(active) ? "btn-primary" : "btn-outline-secondary")
                    .append("\" href=\"/buildings/sites?world=")
                    .append(java.net.URLEncoder.encode(world,
                            java.nio.charset.StandardCharsets.UTF_8))
                    .append("\">")
                    .append(Http.esc(world)).append("</a>");
        }
        return sb.append("</div>").toString();
    }

    /**
     * Comment on crée un emplacement — c'est-à-dire pas ici. Cette carte existe parce que l'absence
     * de bouton « Créer » est un choix, et qu'un choix non expliqué se lit comme un oubli.
     */
    private String howToCreateCard() {
        return "<section class=\"card\">"
                + Ui.sectionTitle("docs", "Créer un emplacement : en jeu, pas ici")
                + "<p class=\"muted\">Un emplacement est un point d'ancrage dans le monde. On le "
                + "désigne du doigt, on ne le tape pas au clavier : il n'y a donc volontairement "
                + "aucun bouton « Créer » sur cette page.</p>"
                + "<ol class=\"muted\">"
                + "<li>En jeu : <code>/rpgadmin buildsite tool</code> pour recevoir l'outil.</li>"
                + "<li>Se placer, <strong>regarder dans la direction</strong> que devra avoir la "
                + "façade, puis <strong>clic droit</strong> sur le bloc visé.</li>"
                + "<li>L'emplacement est créé sur la <strong>case libre contre la face "
                + "cliquée</strong> — cliquer le dessus du sol ancre juste au-dessus, là où "
                + "reposera le bâtiment.</li>"
                + "<li>Revenir ici, <strong>Rafraîchir</strong>, puis renommer la fiche.</li>"
                + "</ol>"
                + "<p class=\"field-help\">L'identifiant (<code>buildsite_0001</code>) est "
                + "attribué par le serveur, ne change jamais et n'est jamais réattribué — c'est lui "
                + "qu'un futur bâtiment citera. Le nom, lui, se change librement.</p>"
                + "</section>";
    }

    /** Une ligne d'accordion : synthèse en bouton, détail et formulaires dans le replié. */
    private String buildingSiteItem(Session session, String agentId,
                                    com.lodygames.rpgquest.panel.building.BuildingSiteView site,
                                    int index, boolean canWrite, boolean canDelete,
                                    boolean canPlace, boolean canRollback,
                                    com.lodygames.rpgquest.panel.building.BuildingSiteDirectory directory,
                                    com.lodygames.rpgquest.panel.building.BuildingLibraryDirectory library,
                                    com.lodygames.rpgquest.panel.building.BuildingPlacementPreview preview,
                                    String wantedId) {
        String slug = "buildsite-" + index + "-" + site.id().replaceAll("[^a-z0-9_-]", "-");
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"accordion-item npc-item\" data-filter-item=\"buildsites\" ")
                .append("data-filter-text=\"").append(Http.esc(site.searchText())).append("\">");
        sb.append("<h3 class=\"accordion-header\">");
        sb.append("<button class=\"accordion-button collapsed npc-head\" type=\"button\" ")
                .append("data-bs-toggle=\"collapse\" data-bs-target=\"#").append(slug)
                .append("\" aria-expanded=\"false\" aria-controls=\"").append(slug).append("\">");
        sb.append("<span class=\"npc-head-main\"><span class=\"npc-name\">")
                .append(Http.esc(site.name())).append("</span>");
        sb.append("<code class=\"tid npc-id\">").append(Http.esc(site.id())).append("</code>");
        sb.append("<span class=\"npc-loc\">").append(Icons.icon("world"))
                .append(Http.esc(site.world())).append(' ')
                .append(Http.esc(site.positionLabel())).append("</span>");
        sb.append("</span><span class=\"npc-head-badges\">");
        sb.append("<span class=\"badge text-bg-secondary\">")
                .append(Http.esc(site.facingLabel())).append("</span>");
        sb.append("<span class=\"badge text-bg-light text-dark\">")
                .append(Http.esc(site.statusLabel())).append("</span>");
        if (!site.worldLoaded()) {
            sb.append("<span class=\"badge text-bg-warning\">monde non chargé</span>");
        }
        sb.append("</span></button></h3>");

        sb.append("<div id=\"").append(slug)
                .append("\" class=\"accordion-collapse collapse\" ")
                .append("data-bs-parent=\"#buildsite-accordion\"><div class=\"accordion-body npc-detail\">");

        sb.append(detailSection("world", "Emplacement"));
        sb.append("<dl class=\"npc-dl\">");
        dlRow(sb, "Identifiant", Ui.id(site.id()));
        dlRow(sb, "Nom", Http.esc(site.name()));
        dlRow(sb, "Monde", Http.esc(site.world()) + (site.worldLoaded() ? ""
                : " <span class=\"badge text-bg-warning\">non chargé</span>"));
        dlRow(sb, "Position", "<code class=\"tid\">" + Http.esc(site.positionLabel()) + "</code>");
        dlRow(sb, "Orientation", Http.esc(site.facingLabel())
                + " <code class=\"tid\">" + Http.esc(site.facing()) + "</code>");
        dlRow(sb, "État", Http.esc(site.statusLabel()));
        dlRow(sb, "Créé le", Http.esc(site.createdAtLabel()));
        dlRow(sb, "Créé par", site.hasAuthor() ? Http.esc(site.createdBy())
                : "<span class=\"muted\">inconnu</span>");
        dlRow(sb, "Description du lieu", site.description().isEmpty()
                ? "<span class=\"muted\">aucune</span>" : Http.esc(site.description()));
        sb.append("</dl>");
        sb.append(legacyBuildingBrief(session, agentId, site));

        sb.append(buildingPlacementSection(session, agentId, site, directory, library, preview,
                canPlace, canRollback, wantedId));

        if (canWrite) {
            sb.append(detailSection("edit", "Modifier la fiche"));
            sb.append(buildingSiteRenameForm(session, agentId, site));
            sb.append(buildingSiteDescribeForm(session, agentId, site));
            sb.append(buildingSiteFacingForm(session, agentId, site));
        }
        if (canDelete) {
            sb.append(buildingSiteDeleteZone(session, agentId, site,
                    directory.placementFor(site.id()).isPresent() || "OCCUPIED".equals(site.status())));
        }
        sb.append("</div></div></div>");
        return sb.toString();
    }

    /**
     * Ce que l'emplacement porte, ou comment lui affecter un bâtiment (issue #213, lot
     * « placement »).
     *
     * <p>Trois états, et un seul écrit dans le monde :</p>
     * <ol>
     *   <li><strong>occupé</strong> — on montre ce qui est posé, et le retour arrière si une
     *       sauvegarde existe ;</li>
     *   <li><strong>vide, aucun aperçu</strong> — on propose de choisir un bâtiment. Ce choix
     *       déclenche un aperçu, qui n'écrit rien ;</li>
     *   <li><strong>vide, aperçu calculé pour CET emplacement</strong> — on montre l'emprise, la
     *       rotation et les avertissements, et seulement alors le bouton de pose.</li>
     * </ol>
     *
     * <p>L'aperçu est volontairement une étape séparée : l'emprise est la seule information qui dise
     * ce qui va être écrasé, et la demander après la pose n'aurait aucun intérêt.</p>
     */
    /**
     * Ce qu'un emplacement OCCUPÉ porte, et ce qu'on peut en faire (issue #234).
     *
     * <h2>Un emplacement est une intention, un placement est un fait</h2>
     *
     * <p>C'est le principe fondamental du ticket, et il se voit ici : l'orientation
     * <em>souhaitée</em> de l'emplacement et l'orientation <em>du bâtiment posé</em> sont deux
     * lignes distinctes. Quand elles divergent, on le dit — et on ne touche à <strong>aucun
     * bloc</strong>. Tourner une structure parce qu'un champ a changé serait une mutation implicite
     * du monde.</p>
     *
     * <h2>Quatre actions, et une seule laisse l'emplacement vide</h2>
     *
     * <p>Aperçu (n'écrit rien) → Réorienter → Remplacer → Libérer. Les trois premières laissent un
     * bâtiment derrière elles ; la dernière rend le terrain d'origine. C'est pour cela qu'elle est
     * séparée visuellement et gouvernée par sa propre permission.</p>
     */
    private String buildingOccupiedSection(
            Session session, String agentId,
            com.lodygames.rpgquest.panel.building.BuildingSiteView site,
            com.lodygames.rpgquest.panel.building.BuildingPlacementView placed,
            com.lodygames.rpgquest.panel.building.BuildingLibraryDirectory library,
            boolean canPlace, boolean canRollback) {
        StringBuilder sb = new StringBuilder();
        sb.append(detailSection("world", "Bâtiment posé"));
        sb.append("<dl class=\"npc-dl\">");
        dlRow(sb, "Bâtiment", Http.esc(placed.buildingName()) + " " + Ui.id(placed.buildingId()));
        dlRow(sb, "Version posée", "<code class=\"tid\">" + Http.esc(placed.versionLabel())
                + "</code>" + (placed.schematicSha().isEmpty() ? ""
                        : " <span class=\"muted\">empreinte " + Http.esc(placed.shortSha())
                                + "</span>"));
        // L'intention et le fait, l'un sous l'autre : c'est la lecture que le ticket demande.
        dlRow(sb, "Orientation souhaitée du site", Http.esc(site.facingLabel())
                + (placed.desiredRotationLabel().isEmpty() ? ""
                        : " <code class=\"tid\">" + Http.esc(placed.desiredRotationLabel())
                                + "</code>"));
        dlRow(sb, "Orientation du bâtiment posé", "<code class=\"tid\">" + placed.rotation()
                + "°</code>");
        dlRow(sb, "Emprise", "<code class=\"tid\">" + Http.esc(placed.footprintLabel())
                + "</code>");
        dlRow(sb, "Emprise (dimensions)", "<code class=\"tid\">"
                + Http.esc(placed.sizeLabel()) + "</code> — " + placed.blockCount() + " blocs");
        dlRow(sb, "Ancre", "<code class=\"tid\">" + Http.esc(placed.anchorLabel()) + "</code>");
        dlRow(sb, "Posé le", Http.esc(placed.placedAt()));
        dlRow(sb, "Posé par", placed.placedBy().isEmpty()
                ? "<span class=\"muted\">inconnu</span>" : Http.esc(placed.placedBy()));
        dlRow(sb, "Terrain d'origine", placed.restoreSource().isEmpty()
                ? "<span class=\"muted\">inconnu</span>" : Http.esc(placed.restoreSource()));
        sb.append("</dl>");

        if (placed.diverges()) {
            sb.append(Ui.banner("warning", "L'orientation de l'emplacement a changé après le "
                    + "placement. Le bâtiment physique n'a pas été modifié : l'emplacement veut "
                    + site.facingLabel() + " (" + placed.desiredRotationLabel() + "), le bâtiment "
                    + "posé est à " + placed.rotation() + "°. Utilisez « Réorienter » pour aligner "
                    + "les deux — rien ne bougera avant."));
        }
        if (placed.outdated()) {
            sb.append(Ui.banner("info", "Une version plus récente de « " + placed.buildingName()
                    + " » existe dans la bibliothèque (" + placed.libraryVersionLabel()
                    + (placed.librarySha().isEmpty() ? "" : ", empreinte "
                            + placed.shortLibrarySha())
                    + "). Le bâtiment posé n'est jamais modifié tout seul : utilisez "
                    + "« Remplacer » par le même bâtiment pour appliquer la nouvelle version."));
        }

        // Issue #239 / #265 — la libération en PREMIER, sous un nom qui dit ce qu'elle fait. Elle était
        // rendue, mais au cinquième rang de quinze sections, sous « Réorienter », et confondue avec
        // « Supprimer l'emplacement » en bas de fiche.
        sb.append(buildingReleaseBlock(session, agentId, site, placed, canRollback));

        // ---- Transformations : aperçu d'abord, confirmation ensuite ---------------------------
        var preview = buildingRetargetPreview(agentId);
        boolean mine = preview.concerns(site.id());
        if (mine) {
            sb.append(buildingRetargetPreviewBlock(preview));
        }

        if (canPlace) {
            sb.append(detailSection("target", "Réorienter ou remplacer"));
            sb.append("<p class=\"field-help\">La réorientation ne tourne <strong>pas</strong> les "
                    + "blocs déjà posés : le terrain d'origine est rendu, puis le bâtiment est "
                    + "recollé dans la nouvelle orientation. Le résultat est donc identique à une "
                    + "pose initiale dans cette orientation — et c'est ce qui le rend "
                    + "reproductible.</p>");
            sb.append(buildingRetargetForm(session, agentId, site, placed, library));

            if (mine && preview.applicable()) {
                sb.append(buildingRetargetConfirmForm(session, agentId, site, preview));
            } else if (mine) {
                sb.append("<p class=\"field-help\">Cet aperçu est refusé : rien n'est proposé tant "
                        + "que les motifs ci-dessus ne sont pas levés.</p>");
            } else {
                sb.append("<p class=\"field-help\">Calculez un aperçu pour voir la nouvelle "
                        + "emprise. Le bouton de confirmation n'apparaît qu'ensuite.</p>");
            }
        }

        sb.append(buildingCaptureSection(session, agentId, site, placed));
        sb.append(buildingHistoryBlock(session, agentId, site.id()));
        return sb.toString();
    }

    private static final String[][] CAPTURE_SIDES = {{"west", "Ouest (−X)"}, {"east", "Est (+X)"},
            {"north", "Nord (−Z)"}, {"south", "Sud (+Z)"}, {"down", "Bas (−Y)"}, {"up", "Haut (+Y)"}};

    /**
     * Issue #246 — « Capturer la construction actuelle » : l'état réel du monde, retouches en jeu
     * comprises, devient un NOUVEAU bâtiment de la bibliothèque. Aperçu d'abord (lecture seule),
     * confirmation ensuite, avec le jeton de l'aperçu. La politique (air, conteneurs, entités) est
     * écrite avant le bouton, pas découverte après.
     */
    private String buildingCaptureSection(Session session, String agentId,
                                          com.lodygames.rpgquest.panel.building.BuildingSiteView site,
                                          com.lodygames.rpgquest.panel.building.BuildingPlacementView placed) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div id=\"capturer-").append(Http.esc(site.id())).append("\">");
        sb.append(detailSection("save", "Capturer la construction actuelle"));
        sb.append("<p class=\"field-help\">Enregistre les blocs <strong>réellement présents</strong> dans "
                + "l'emprise de « ").append(Http.esc(placed.buildingName())).append(" » — retouches faites en "
                + "jeu comprises — comme un <strong>nouveau</strong> bâtiment de la bibliothèque. Aucun bloc "
                + "n'est modifié, le bâtiment d'origine et ce placement restent inchangés, aucun fichier "
                + "n'est écrasé.</p>");
        sb.append("<ul class=\"field-help\"><li><strong>Air</strong> : capturé — posée ailleurs, la "
                + "variante remplace toute son emprise, comme tout bâtiment.</li>"
                + "<li><strong>Conteneurs</strong> (coffres, tonneaux, fours, pupitres…) : capturés "
                + "<strong>vides</strong> ; leur contenu n'est jamais exporté.</li>"
                + "<li><strong>Entités</strong> (cadres, tableaux, supports d'armure, animaux) : "
                + "<strong>non capturées</strong> — elles sont listées dans l'aperçu.</li>"
                + "<li>Enseignes, bannières, têtes et pots de fleurs : capturés avec leur contenu.</li></ul>");
        if (!perms.can(session.effective(), Permission.BUILDING_WRITE)) {
            sb.append(Ui.banner("info", "Votre rôle ne permet pas d'ajouter un bâtiment à la bibliothèque."));
            return sb.append("</div>").toString();
        }
        Map<String, Object> preview = latestDetails(agentId, "building.placement.capture.preview")
                .filter(d -> site.id().equals(str(d.get("site_id"))))
                .orElse(Map.of());
        Map<String, Object> margins = asMap(preview.get("margins"));

        sb.append(formStart(session, agentId, "building.placement.capture.preview", "/buildings/sites", ""));
        sb.append("<input type=\"hidden\" name=\"id\" value=\"").append(Http.esc(site.id())).append("\">");
        captureField(sb, site.id(), "new_id", "Identifiant du nouveau bâtiment",
                str(preview.getOrDefault("new_id", placed.buildingId() + "_capture")),
                "minuscules, chiffres et « _ » — jamais un identifiant existant", "required pattern=\"[a-z0-9][a-z0-9_]{0,63}\"");
        captureField(sb, site.id(), "name", "Nom", str(preview.getOrDefault("name", placed.buildingName() + " (retouchée)")),
                "", "required maxlength=\"64\"");
        captureField(sb, site.id(), "description", "Description", str(preview.getOrDefault("description", "")),
                "", "maxlength=\"500\"");
        captureField(sb, site.id(), "category", "Catégorie", str(preview.getOrDefault("category", "")),
                "facultative, ex. « maisons »", "maxlength=\"32\"");
        sb.append("<p class=\"field-help\">Bornes : l'emprise posée <code class=\"tid\">")
                .append(Http.esc(placed.footprintLabel())).append("</code>, rotation comprise. Si des "
                        + "décorations débordent (porche, jardinière…), agrandissez la zone face par face "
                        + "(−16 à +16 blocs) ; une valeur négative la réduit. Une zone qui touche un autre "
                        + "bâtiment posé est refusée.</p>");
        sb.append("<div class=\"btnrow\">");
        for (String[] side : CAPTURE_SIDES) {
            Object value = margins.getOrDefault(side[0], 0);
            sb.append("<label class=\"inline\">").append(side[1]).append(" <input type=\"number\" name=\"margin_")
                    .append(side[0]).append("\" min=\"-16\" max=\"16\" step=\"1\" value=\"")
                    .append(Http.esc(String.valueOf(value))).append("\" style=\"width:5em\"></label>");
        }
        sb.append("</div>");
        sb.append(mutationConsent("building.placement.capture.preview", "", null));
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">")
                .append(Icons.icon("search")).append("Calculer l'aperçu de la capture</button></div></form>");

        if (!preview.isEmpty()) {
            sb.append(buildingCapturePreviewBlock(session, agentId, site, preview));
        }
        return sb.append("</div>").toString();
    }

    private static void captureField(StringBuilder sb, String siteId, String name, String label, String value,
                                     String help, String attributes) {
        String fid = "capture-" + name + "-" + siteId;
        sb.append("<div class=\"field\"><label for=\"").append(Http.esc(fid)).append("\">").append(label)
                .append("</label><input id=\"").append(Http.esc(fid)).append("\" type=\"text\" name=\"")
                .append(name).append("\" value=\"").append(Http.esc(value)).append("\" ").append(attributes)
                .append(" autocomplete=\"off\" spellcheck=\"false\">");
        if (!help.isEmpty()) {
            sb.append("<p class=\"field-help\">").append(Http.esc(help)).append("</p>");
        }
        sb.append("</div>");
    }

    /** L'aperçu de la capture et, seulement s'il est accepté, le bouton qui capture. */
    private String buildingCapturePreviewBlock(Session session, String agentId,
                                               com.lodygames.rpgquest.panel.building.BuildingSiteView site,
                                               Map<String, Object> preview) {
        StringBuilder sb = new StringBuilder();
        sb.append(detailSection("search", "Aperçu de la capture"));
        sb.append("<dl class=\"npc-dl\">");
        dlRow(sb, "Source", Http.esc(str(preview.get("source_building_name"))) + " "
                + Ui.id(str(preview.get("source_building_id"))) + " <span class=\"muted\">version "
                + Http.esc(str(preview.get("source_building_version"))) + ", posé à "
                + Http.esc(str(preview.get("source_rotation"))) + "°</span>");
        dlRow(sb, "Nouveau bâtiment", Http.esc(str(preview.get("name"))) + " " + Ui.id(str(preview.get("new_id"))));
        dlRow(sb, "Bornes capturées", "<code class=\"tid\">" + Http.esc(str(preview.get("region")))
                + "</code> <span class=\"muted\">(" + Http.esc(str(preview.get("world"))) + ")</span>");
        dlRow(sb, "Dimensions", "<code class=\"tid\">" + Http.esc(str(preview.get("size"))) + "</code> — "
                + Http.esc(str(preview.get("volume"))) + " blocs");
        dlRow(sb, "Ancre locale", "<code class=\"tid\">" + Http.esc(str(preview.get("local_anchor"))) + "</code>");
        dlRow(sb, "Façade de référence", Http.esc(str(preview.get("front"))));
        if (preview.containsKey("non_air")) {
            dlRow(sb, "Blocs non-air", Http.esc(str(preview.get("non_air"))));
            dlRow(sb, "Conteneurs (capturés vides)", Http.esc(str(preview.get("containers")))
                    + " <span class=\"muted\">dont " + Http.esc(str(preview.get("filled_containers")))
                    + " non vide(s)</span>");
            StringBuilder entities = new StringBuilder();
            asMap(preview.get("entity_types")).forEach((type, count) -> entities.append(entities.isEmpty() ? "" : ", ")
                    .append(Http.esc(str(count))).append(" × ").append(Http.esc(type)));
            dlRow(sb, "Entités non capturées", entities.isEmpty() ? "aucune" : entities.toString());
            dlRow(sb, "Matériaux principaux", Http.esc(String.join(", ",
                    asList(preview.get("materials")).stream().map(AgentPages::str).toList())));
        }
        sb.append("</dl>");
        for (Object refusal : asList(preview.get("refusals"))) {
            sb.append(Ui.banner("error", Http.esc(str(refusal))));
        }
        for (Object warning : asList(preview.get("warnings"))) {
            sb.append(Ui.banner("warning", Http.esc(str(warning))));
        }
        if (!Boolean.TRUE.equals(preview.get("capturable"))) {
            sb.append("<p class=\"field-help\">Capture refusée : corrigez les motifs ci-dessus puis "
                    + "recalculez l'aperçu. Rien n'a été écrit.</p>");
            return sb.toString();
        }
        sb.append("<div class=\"danger-zone\"><div class=\"dz-title\">Capturer — ajoute un bâtiment à la "
                + "bibliothèque</div>");
        sb.append("<p class=\"field-help\">Le monde n'est pas modifié. Le nouveau bâtiment pourra ensuite "
                + "être posé sur un <strong>autre</strong> emplacement, par « Poser un bâtiment » sur sa fiche.</p>");
        sb.append(formStart(session, agentId, "building.placement.capture", "/buildings/sites", ""));
        sb.append("<input type=\"hidden\" name=\"id\" value=\"").append(Http.esc(site.id())).append("\">");
        for (String key : new String[] {"new_id", "name", "description", "category", "token"}) {
            sb.append("<input type=\"hidden\" name=\"").append(key).append("\" value=\"")
                    .append(Http.esc(str(preview.get(key)))).append("\">");
        }
        Map<String, Object> margins = asMap(preview.get("margins"));
        for (String[] side : CAPTURE_SIDES) {
            sb.append("<input type=\"hidden\" name=\"margin_").append(side[0]).append("\" value=\"")
                    .append(Http.esc(String.valueOf(margins.getOrDefault(side[0], 0)))).append("\">");
        }
        sb.append(mutationConsent("building.placement.capture", "Capturer l'état actuel de « " + site.name()
                + " » comme « " + str(preview.get("name")) + " » (conteneurs vides, entités non capturées)", null));
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-primary\" type=\"submit\">")
                .append(Icons.icon("save")).append("Capturer la construction actuelle</button></div></form></div>");
        return sb.toString();
    }

    /**
     * Issue #239 / #265 — « Retirer le bâtiment et restaurer le terrain ».
     *
     * <p>Ce n'est pas « Supprimer l'emplacement » : le repère reste, et le terrain revient tel qu'il
     * était avant la PREMIÈRE pose. Quand l'action est impossible, la raison est dite — un bouton
     * qui disparaît sans un mot ressemble à une panne.</p>
     */
    private String buildingReleaseBlock(Session session, String agentId,
                                        com.lodygames.rpgquest.panel.building.BuildingSiteView site,
                                        com.lodygames.rpgquest.panel.building.BuildingPlacementView placed,
                                        boolean canRollback) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"danger-zone\" id=\"retirer-").append(Http.esc(site.id())).append("\">");
        sb.append("<div class=\"dz-title\">").append(Icons.icon("warning"))
                .append("Retirer le bâtiment et restaurer le terrain</div>");
        sb.append("<p>Le bâtiment « ").append(Http.esc(placed.buildingName()))
                .append(" » est retiré et le terrain est remis <strong>tel qu'il était avant sa première "
                        + "pose</strong>. L'emplacement « ").append(Http.esc(site.name()))
                .append(" » est <strong>conservé</strong> : il redevient libre pour une autre "
                        + "construction.</p>");
        sb.append("<p class=\"field-help\"><strong>Attention :</strong> tout ce qui a été construit "
                + "à la main dans cette zone depuis la pose est aussi effacé. À ne pas confondre avec "
                + "« Supprimer l'emplacement », qui retire seulement le repère sans toucher aux blocs.</p>");
        if (!placed.restoreSource().isEmpty()) {
            sb.append("<p class=\"field-help\">Terrain rendu : ").append(Http.esc(placed.restoreSource()))
                    .append(".</p>");
        }
        if (!placed.restorable()) {
            sb.append(Ui.banner("warning", "<strong>Indisponible pour cet emplacement.</strong> Aucune "
                    + "sauvegarde du terrain d'avant la pose n'existe : retirer le bâtiment détruirait le "
                    + "terrain d'origine au lieu de le rendre. Rien n'est proposé, pour ne rien perdre."));
        } else if (!canRollback) {
            sb.append(Ui.banner("info", "Votre rôle ne permet pas de retirer un bâtiment posé : cette "
                    + "action écrit dans le monde. Un administrateur peut la faire depuis cette fiche."));
        } else {
            sb.append(formStart(session, agentId, "building.placement.rollback", "/buildings/sites", ""));
            sb.append("<input type=\"hidden\" name=\"id\" value=\"").append(Http.esc(site.id())).append("\">");
            String fid = "confirm-site-" + site.id();
            sb.append("<div class=\"field\"><label for=\"").append(Http.esc(fid))
                    .append("\">Pour confirmer, tapez l'identifiant de l'emplacement : <code>")
                    .append(Http.esc(site.id())).append("</code></label><input id=\"").append(Http.esc(fid))
                    .append("\" type=\"text\" name=\"confirm_site\" autocomplete=\"off\" required "
                            + "spellcheck=\"false\"></div>");
            sb.append(mutationConsent("building.placement.rollback",
                    "Retirer « " + placed.buildingName() + " » et restaurer le terrain de « "
                            + site.name() + " » (le repère est conservé)", null));
            sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-danger\" type=\"submit\">")
                    .append(Icons.icon("warning")).append("Retirer le bâtiment et restaurer le terrain")
                    .append("</button></div></form>");
        }
        return sb.append("</div>").toString();
    }

    /** L'aperçu d'une transformation : les deux emprises côte à côte, et rien d'écrit. */
    private String buildingRetargetPreviewBlock(
            com.lodygames.rpgquest.panel.building.BuildingRetargetPreview preview) {
        StringBuilder sb = new StringBuilder();
        sb.append(detailSection("search", "Aperçu — " + preview.operationLabel()));
        sb.append("<dl class=\"npc-dl\">");
        dlRow(sb, "Bâtiment actuel", Http.esc(preview.currentBuildingName()) + " "
                + Ui.id(preview.currentBuildingId()) + " <code class=\"tid\">"
                + preview.currentRotation() + "°</code>");
        dlRow(sb, "Ancienne emprise", "<code class=\"tid\">"
                + Http.esc(preview.currentFootprint()) + "</code> — "
                + preview.currentBlockCount() + " blocs");
        dlRow(sb, "Nouveau bâtiment", Http.esc(preview.targetBuildingName()) + " "
                + Ui.id(preview.targetBuildingId()) + " <code class=\"tid\">"
                + preview.targetRotation() + "°</code>");
        dlRow(sb, "Nouvelle emprise", "<code class=\"tid\">"
                + Http.esc(preview.targetFootprint()) + "</code> — "
                + preview.targetBlockCount() + " blocs");
        dlRow(sb, "Dimensions visées", "<code class=\"tid\">"
                + Http.esc(preview.targetSize()) + "</code>");
        dlRow(sb, "Recouvrement des emprises", preview.overlapping()
                ? "oui — une partie de la zone est commune aux deux"
                : "non — les deux emprises sont disjointes");
        dlRow(sb, "Blocs non-air dans la nouvelle emprise", Http.esc(preview.nonAirLabel()));
        dlRow(sb, "Terrain d'origine", Http.esc(preview.restoreSource()));
        sb.append("</dl>");
        for (String refusal : preview.refusals()) {
            sb.append(Ui.banner("error", refusal));
        }
        for (String warning : preview.warnings()) {
            sb.append(Ui.banner("warning", warning));
        }
        return sb.toString();
    }

    /** Le formulaire d'aperçu : choisir un bâtiment et une orientation. N'écrit rien. */
    private String buildingRetargetForm(
            Session session, String agentId,
            com.lodygames.rpgquest.panel.building.BuildingSiteView site,
            com.lodygames.rpgquest.panel.building.BuildingPlacementView placed,
            com.lodygames.rpgquest.panel.building.BuildingLibraryDirectory library) {
        StringBuilder sb = new StringBuilder();
        sb.append(formStart(session, agentId, "building.placement.retarget.preview",
                "/buildings/sites", ""));
        sb.append("<input type=\"hidden\" name=\"id\" value=\"")
                .append(Http.esc(site.id())).append("\">");
        sb.append("<label class=\"form-label\">Bâtiment</label>");
        sb.append("<select class=\"form-select\" name=\"building\">");
        for (var definition : library.buildings()) {
            sb.append("<option value=\"").append(Http.esc(definition.id())).append("\"")
                    .append(definition.id().equals(placed.buildingId()) ? " selected" : "")
                    .append('>').append(Http.esc(definition.name())).append("  ·  ")
                    .append(Http.esc(definition.sizeLabel()))
                    .append(definition.id().equals(placed.buildingId()) ? "  (actuel)" : "")
                    .append("</option>");
        }
        sb.append("</select>");
        sb.append("<label class=\"form-label\">Orientation visée</label>");
        sb.append("<select class=\"form-select\" name=\"facing\">");
        for (String[] option : new String[][] {{"NORTH", "Nord"}, {"EAST", "Est"},
                {"SOUTH", "Sud"}, {"WEST", "Ouest"}}) {
            sb.append("<option value=\"").append(option[0]).append("\"")
                    .append(option[0].equals(site.facing()) ? " selected" : "")
                    .append('>').append(option[1])
                    .append(option[0].equals(site.facing()) ? "  (orientation du site)" : "")
                    .append("</option>");
        }
        sb.append("</select>");
        sb.append(mutationConsent("building.placement.retarget.preview", "", null));
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-primary\" "
                + "type=\"submit\">").append(Icons.icon("search"))
                .append("Calculer l'aperçu</button></div></form>");
        return sb.toString();
    }

    /**
     * La confirmation, et elle n'existe qu'<strong>après</strong> un aperçu applicable.
     *
     * <p>Le jeton de l'aperçu voyage avec elle : si l'emplacement, le bâtiment posé ou la définition
     * visée ont changé entre-temps, le serveur refuse au lieu d'appliquer une décision périmée.</p>
     */
    private String buildingRetargetConfirmForm(
            Session session, String agentId,
            com.lodygames.rpgquest.panel.building.BuildingSiteView site,
            com.lodygames.rpgquest.panel.building.BuildingRetargetPreview preview) {
        String type = preview.replacement()
                ? "building.placement.replace" : "building.placement.reorient";
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"danger-zone\"><div class=\"dz-title\">⚠ ")
                .append(preview.operationLabel()).append(" — écrit dans le monde</div>");
        sb.append("<p class=\"field-help\">Le terrain d'origine est rendu, puis « ")
                .append(Http.esc(preview.targetBuildingName()))
                .append(" » est collé à ").append(preview.targetRotation())
                .append("°. Si le collage échoue, l'ancien bâtiment est remis en place et "
                        + "l'opération n'a pas eu lieu.</p>");
        sb.append(formStart(session, agentId, type, "/buildings/sites", ""));
        sb.append("<input type=\"hidden\" name=\"id\" value=\"")
                .append(Http.esc(site.id())).append("\">");
        sb.append("<input type=\"hidden\" name=\"building\" value=\"")
                .append(Http.esc(preview.targetBuildingId())).append("\">");
        sb.append("<input type=\"hidden\" name=\"facing\" value=\"")
                .append(Http.esc(preview.requestedFacing())).append("\">");
        sb.append("<input type=\"hidden\" name=\"token\" value=\"")
                .append(Http.esc(preview.token())).append("\">");
        sb.append(mutationConsent(type, "", null));
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-danger\" "
                + "type=\"submit\">").append(Icons.icon("world"))
                .append(preview.replacement() ? "Remplacer le bâtiment" : "Réorienter le bâtiment")
                .append("</button></div></form></div>");
        return sb.toString();
    }

    /**
     * Le journal des opérations de l'emplacement (issue #234).
     *
     * <p>Relevé à la demande : l'historique n'est pas une information dont on a besoin à chaque
     * ouverture de fiche, et le charger d'office ajouterait une action à chaque affichage. Les
     * <strong>échecs y figurent</strong> — un journal muet au moment du problème ne sert à rien.</p>
     */
    private String buildingHistoryBlock(Session session, String agentId, String siteId) {
        StringBuilder sb = new StringBuilder();
        sb.append(detailSection("history", "Journal des opérations"));
        List<Map<String, Object>> lines = buildingHistoryFor(agentId, siteId);
        if (lines.isEmpty()) {
            sb.append("<p class=\"field-help\">Aucun relevé pour cet emplacement. Le journal "
                    + "répond à une seule question : « qu'est-ce qui a été posé ici, dans quel "
                    + "ordre, et est-ce que ça a marché ? »</p>");
        } else {
            sb.append("<div class=\"tablewrap\">");
            sb.append(Ui.tableOpen("Quand", "Opération", "Bâtiment", "Rotation", "Emprise",
                    "Par", "Résultat"));
            for (Map<String, Object> line : lines) {
                boolean ok = Boolean.TRUE.equals(line.get("ok"));
                sb.append("<tr><td class=\"muted\">").append(Http.esc(str(line.get("at"))))
                        .append("</td><td>").append(Http.esc(str(line.get("operation_label"))))
                        .append("</td><td>").append(Http.esc(str(line.get("building_id"))));
                String sha = str(line.get("sha"));
                if (!sha.isEmpty()) {
                    sb.append(" <span class=\"muted\">").append(Http.esc(sha)).append("</span>");
                }
                sb.append("</td><td><code class=\"tid\">").append(Http.esc(str(line.get("rotation"))))
                        .append("°</code></td><td><code class=\"tid\">")
                        .append(Http.esc(str(line.get("footprint"))))
                        .append("</code></td><td>").append(Http.esc(str(line.get("actor"))))
                        .append("</td><td><span class=\"badge text-bg-")
                        .append(ok ? "success\">réussie" : "danger\">échec")
                        .append("</span>");
                String detail = str(line.get("detail"));
                if (!detail.isEmpty()) {
                    sb.append("<br><span class=\"muted\">").append(Http.esc(detail))
                            .append("</span>");
                }
                sb.append("</td></tr>");
            }
            sb.append(Ui.tableClose()).append("</div>");
        }
        sb.append(formStart(session, agentId, "building.placement.history",
                "/buildings/sites", ""));
        sb.append("<input type=\"hidden\" name=\"id\" value=\"")
                .append(Http.esc(siteId)).append("\">");
        sb.append(mutationConsent("building.placement.history", "", null));
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-secondary\" "
                + "type=\"submit\">").append(Icons.icon("history"))
                .append("Relever le journal</button></div></form>");
        return sb.toString();
    }

    private String buildingPlacementSection(
            Session session, String agentId,
            com.lodygames.rpgquest.panel.building.BuildingSiteView site,
            com.lodygames.rpgquest.panel.building.BuildingSiteDirectory directory,
            com.lodygames.rpgquest.panel.building.BuildingLibraryDirectory library,
            com.lodygames.rpgquest.panel.building.BuildingPlacementPreview preview,
            boolean canPlace, boolean canRollback, String wantedId) {
        StringBuilder sb = new StringBuilder();
        var placement = directory.placementFor(site.id());

        if (placement.isPresent()) {
            sb.append(buildingOccupiedSection(session, agentId, site, placement.get(), library,
                    canPlace, canRollback));
            return sb.toString();
        }

        // Issue #239 — un emplacement OCCUPÉ ne retombe JAMAIS sur la branche « vide ».
        //
        // C'est le défaut constaté en jeu : le relevé ne portait pas les placements, donc
        // placementFor() restait vide et la fiche proposait un aperçu de pose — pire, un aperçu
        // PÉRIMÉ, calculé avant la pose — avec son bouton « Placer dans le monde ». Un geste de
        // plus et l'on posait un second bâtiment sur un emplacement déjà occupé.
        //
        // L'état du site est une donnée du serveur, indépendante de la présence du placement dans
        // le relevé : s'il dit « occupé », on le croit, et on dit honnêtement qu'il manque le
        // détail plutôt que d'offrir une action que cet état interdit.
        if (site.occupied()) {
            sb.append(detailSection("world", "Bâtiment posé"));
            sb.append(Ui.banner("warn", "Cet emplacement est <strong>occupé</strong>, mais le "
                    + "dernier relevé ne porte pas le détail du bâtiment posé. Cliquer sur "
                    + "« Rafraîchir » ci-dessus ; si l'information reste absente, le plugin du "
                    + "serveur est antérieur au correctif qui transmet les bâtiments posés "
                    + "(issue #239) et il faut déployer un JAR à jour."));
            sb.append("<dl class=\"npc-dl\">");
            dlRow(sb, "Orientation souhaitée du site", Http.esc(site.facingLabel())
                    + " <code class=\"tid\">" + Http.esc(site.facing()) + "</code>");
            dlRow(sb, "Orientation réellement posée",
                    "<span class=\"muted\">inconnue — absente du relevé</span>");
            sb.append("</dl>");
            sb.append("<p class=\"field-help\">Aucune pose n'est proposée sur un emplacement "
                    + "occupé : il faudrait d'abord le libérer. Les blocs déjà posés ne sont "
                    + "jamais modifiés par un changement d'orientation de l'emplacement.</p>");
            return sb.toString();
        }

        // ---- Emplacement vide -----------------------------------------------------------------
        sb.append(detailSection("world", "Bâtiment"));
        if (!library.available()) {
            sb.append("<p class=\"field-help\">Bibliothèque non chargée : ouvrez "
                    + "<strong>Bâtiments → Bibliothèque</strong> et cliquez sur « Rafraîchir ».</p>");
            return sb.toString();
        }
        if (!library.engineAvailable()) {
            sb.append(Ui.banner("warning", "Moteur de schematics indisponible : "
                    + (library.engineReason().isEmpty() ? "motif non précisé."
                            : library.engineReason())
                    + " Aucun bâtiment ne peut être posé."));
            return sb.toString();
        }
        var placeable = library.placeable();
        if (placeable.isEmpty()) {
            sb.append("<p class=\"field-help\">Aucun bâtiment posable : la bibliothèque est vide, "
                    + "ou aucun schematic n'est présent sur le serveur.</p>");
            return sb.toString();
        }
        if (!site.worldLoaded()) {
            sb.append(Ui.banner("warning", "Le monde « " + Http.esc(site.world())
                    + " » n'est pas chargé : rien ne peut être posé tant qu'il dort."));
        }

        // Issue #239 — un aperçu n'est valable que pour l'emplacement ET l'orientation pour
        // lesquels il a été calculé. L'aperçu capture l'orientation du site au moment du calcul
        // (« siteFacing ») et en déduit la rotation ; si le site a tourné depuis, cette rotation est
        // fausse. L'écran affichait alors le libellé courant du site collé à l'orientation capturée
        // — d'où le « sud NORTH » rapporté en jeu — et proposait tout de même de poser.
        boolean previewStale = preview.available()
                && !preview.siteFacing().isEmpty()
                && !preview.siteFacing().equalsIgnoreCase(site.facing());
        boolean previewMatches = preview.available()
                && preview.siteId().equalsIgnoreCase(site.id())
                && !previewStale;
        if (previewStale && preview.siteId().equalsIgnoreCase(site.id())) {
            sb.append(Ui.banner("info", "L'orientation de cet emplacement a changé depuis le "
                    + "dernier aperçu (calculé pour <code>" + Http.esc(preview.siteFacing())
                    + "</code>, l'emplacement est maintenant <code>" + Http.esc(site.facing())
                    + "</code>). L'aperçu est donc écarté : sa rotation ne vaut plus. Choisir à "
                    + "nouveau un bâtiment pour en recalculer un."));
        }

        if (canPlace) {
            sb.append("<p class=\"field-help\">Choisir un bâtiment calcule la rotation et "
                    + "l'emprise. <strong>Rien n'est écrit dans le monde à cette étape.</strong></p>");
            sb.append(formStart(session, agentId, "building.placement.preview",
                    "/buildings/sites", ""));
            sb.append("<input type=\"hidden\" name=\"id\" value=\"")
                    .append(Http.esc(site.id())).append("\">");
            sb.append("<div class=\"field\"><label>Bâtiment</label><select name=\"building\">");
            for (var building : placeable) {
                boolean selected = previewMatches
                        ? preview.buildingId().equalsIgnoreCase(building.id())
                        : building.id().equals(wantedId);
                sb.append("<option value=\"").append(Http.esc(building.id())).append('"')
                        .append(selected ? " selected" : "").append('>')
                        .append(Http.esc(building.name())).append(" — ")
                        .append(Http.esc(building.sizeLabel()))
                        .append(" — façade ").append(Http.esc(building.frontLabel()))
                        .append("</option>");
            }
            sb.append("</select><p class=\"field-help\">La façade du bâtiment sera tournée vers "
                            + "l'orientation de l'emplacement (<strong>")
                    .append(Http.esc(site.facingLabel()))
                    .append("</strong>).</p></div>");
            sb.append(mutationConsent("building.placement.preview", "", null));
            sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-primary\" "
                    + "type=\"submit\">").append(Icons.icon("world"))
                    .append("Choisir un bâtiment</button></div></form>");
        } else {
            sb.append("<p class=\"field-help\">Vous n'avez pas le droit de poser un bâtiment.</p>");
        }

        if (previewMatches) {
            sb.append(buildingPreviewCard(session, agentId, site, preview, canPlace));
        }
        return sb.toString();
    }

    /** L'aperçu : l'emprise exacte, la rotation, et ce qui empêche ou mérite attention. */
    private String buildingPreviewCard(
            Session session, String agentId,
            com.lodygames.rpgquest.panel.building.BuildingSiteView site,
            com.lodygames.rpgquest.panel.building.BuildingPlacementPreview preview,
            boolean canPlace) {
        StringBuilder sb = new StringBuilder();
        sb.append(detailSection("world", "Aperçu de la pose"));
        sb.append("<dl class=\"npc-dl\">");
        dlRow(sb, "Bâtiment", Http.esc(preview.buildingName()) + " "
                + Ui.id(preview.buildingId()));
        dlRow(sb, "Dimensions du bâtiment", "<code class=\"tid\">"
                + Http.esc(preview.sizeLabel()) + "</code> — façade de référence "
                + Http.esc(preview.front()));
        // Issue #239 : les deux valeurs étaient simplement juxtaposées, ce qui donnait « sud NORTH »
        // dès qu'elles divergeaient. On nomme désormais ce qu'on lit : l'orientation pour laquelle
        // l'aperçu a été CALCULÉ, et celle de l'emplacement AUJOURD'HUI.
        dlRow(sb, "Orientation de l'aperçu", "<code class=\"tid\">"
                + Http.esc(preview.siteFacing()) + "</code> <span class=\"muted\">— orientation de "
                + "l'emplacement au moment du calcul</span>");
        if (!preview.siteFacing().equalsIgnoreCase(site.facing())) {
            dlRow(sb, "Orientation actuelle du site", Http.esc(site.facingLabel())
                    + " <code class=\"tid\">" + Http.esc(site.facing()) + "</code>"
                    + " <span class=\"badge text-bg-warning\">a changé depuis l'aperçu</span>");
        }
        dlRow(sb, "Rotation appliquée", "<code class=\"tid\">" + preview.rotation()
                + "°</code> <span class=\"muted\">pour amener la façade sur "
                + Http.esc(site.facingLabel()) + "</span>");
        dlRow(sb, "Ancre", "<code class=\"tid\">" + Http.esc(preview.anchorLabel()) + "</code>");
        dlRow(sb, "Emprise calculée", "<code class=\"tid\">"
                + Http.esc(preview.footprintLabel()) + "</code>");
        dlRow(sb, "Emprise (dimensions)", "<code class=\"tid\">"
                + Http.esc(preview.footprintSizeLabel()) + "</code> — "
                + preview.blockCount() + " blocs"
                + (preview.rotation() == 90 || preview.rotation() == 270
                        ? " <span class=\"muted\">(largeur et profondeur échangées par la "
                                + "rotation)</span>"
                        : ""));
        dlRow(sb, "Blocs non-air sur place", preview.counted()
                ? String.valueOf(preview.nonAirBlocks())
                : "<span class=\"muted\">non calculé (monde ou chunk déchargé)</span>");
        sb.append("</dl>");

        for (String warning : preview.warnings()) {
            sb.append(Ui.banner("warning", Http.esc(warning)));
        }
        for (String refusal : preview.refusals()) {
            sb.append(Ui.banner("error", Http.esc(refusal)));
        }

        if (!preview.placeable()) {
            sb.append("<p class=\"field-help\">La pose est refusée tant que les points ci-dessus "
                    + "ne sont pas réglés.</p>");
            return sb.toString();
        }
        if (!canPlace) {
            return sb.toString();
        }
        sb.append("<p class=\"field-help\">La zone est <strong>sauvegardée avant</strong> le "
                + "collage, ce qui rend le retour arrière possible. Les blocs de l'emprise "
                + "ci-dessus seront remplacés.</p>");
        sb.append(formStart(session, agentId, "building.placement.place", "/buildings/sites", ""));
        sb.append("<input type=\"hidden\" name=\"id\" value=\"")
                .append(Http.esc(site.id())).append("\">");
        sb.append("<input type=\"hidden\" name=\"building\" value=\"")
                .append(Http.esc(preview.buildingId())).append("\">");
        sb.append(mutationConsent("building.placement.place", "", null));
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-danger\" type=\"submit\">")
                .append(Icons.icon("world")).append("Placer dans le monde</button></div></form>");
        return sb.toString();
    }

    private String buildingSiteRenameForm(Session session, String agentId,
                                          com.lodygames.rpgquest.panel.building.BuildingSiteView site) {
        return formStart(session, agentId, "building.site.rename", "/buildings/sites", "")
                + "<input type=\"hidden\" name=\"id\" value=\"" + Http.esc(site.id()) + "\">"
                + "<div class=\"field\"><label>Nom</label>"
                + "<input type=\"text\" name=\"name\" maxlength=\"64\" value=\""
                + Http.esc(site.name()) + "\" required>"
                + "<p class=\"field-help\">Libellé humain. L'identifiant "
                + "<code>" + Http.esc(site.id()) + "</code> ne change jamais.</p></div>"
                + mutationConsent("building.site.rename", "", null)
                + "<div class=\"btnrow\"><button class=\"btn btn-sm\" type=\"submit\">"
                + Icons.icon("save") + "Renommer</button></div></form>";
    }

    /**
     * Note courte d'administrateur (issue #213), <strong>sans</strong> {@code maxlength}.
     *
     * <p>C'est la correction centrale de ce lot. L'attribut {@code maxlength="500"} faisait
     * exactement ce que le ticket interdit : il coupait un collage au caractère 500,
     * <strong>silencieusement</strong>, sans message ni compteur — le navigateur n'en produit
     * aucun. L'administrateur voyait son brief s'arrêter au milieu d'un mot et n'avait aucun moyen
     * de savoir pourquoi. Le champ accepte donc maintenant la frappe complète, affiche un compteur
     * vivant, et c'est le <em>serveur</em> qui refuse au-delà de la borne, en disant de combien on
     * dépasse. Mieux vaut un refus lisible qu'une coupure muette.</p>
     */
    private String buildingSiteDescribeForm(Session session, String agentId,
                                            com.lodygames.rpgquest.panel.building.BuildingSiteView site) {
        return formStart(session, agentId, "building.site.describe", "/buildings/sites", "")
                + "<input type=\"hidden\" name=\"id\" value=\"" + Http.esc(site.id()) + "\">"
                + "<div class=\"field\"><label for=\"desc-" + Http.esc(site.id())
                + "\">Description du lieu</label>"
                + "<textarea id=\"desc-" + Http.esc(site.id()) + "\" name=\"description\" rows=\"2\" "
                + "data-charcount=\"500\" "
                + "placeholder=\"Ex. : clairière au nord du Hub, au bord de la rivière.\">"
                + Http.esc(site.description()) + "</textarea>"
                + charCounter("desc-" + site.id(), site.description().length(), 500)
                + "<p class=\"field-help\">Où se trouve ce lieu, à quoi il sert : une ou deux "
                + "phrases pour le retrouver. Le bâtiment à construire, lui, se décrit dans "
                + "l'<a href=\"/buildings/workshop\">Atelier de création</a>. Vider le champ "
                + "efface la description.</p></div>"
                + mutationConsent("building.site.describe", "", null)
                + "<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-primary\" "
                + "type=\"submit\">" + Icons.icon("save") + "Enregistrer la description"
                + "</button></div></form>";
    }

    /**
     * La description de bâtiment rédigée sur l'emplacement avant l'Atelier (issues #94, #268).
     *
     * <p>Elle n'est ni vidée ni supprimée côté serveur : une migration qui efface du texte décide à
     * la place de l'administrateur. Elle reste lisible, repliée et <strong>en lecture seule</strong>,
     * avec ce qu'elle est devenue : reprise dans un projet (lien direct), ou à reprendre. Plus aucune
     * génération ne part d'un emplacement.</p>
     */
    private String legacyBuildingBrief(Session session, String agentId,
                                       com.lodygames.rpgquest.panel.building.BuildingSiteView site) {
        if (!site.hasAiBrief()) {
            return "";
        }
        var project = workshopProjects == null ? Optional.<com.lodygames.rpgquest.panel.building.BuildingProject>empty()
                : workshopProjects.importedFrom(agentId, site.id());
        StringBuilder sb = new StringBuilder("<div class=\"alert alert-info npc-alert\">")
                .append(Icons.icon("info")).append("<div>");
        if (project.isPresent()) {
            sb.append("La description rédigée autrefois sur cet EMPLACEMENT a été reprise dans le projet « <a href=\"")
                    .append(WorkshopPages.BASE).append("/project?id=").append(Http.esc(project.get().id()))
                    .append("\">").append(Http.esc(project.get().name())).append("</a> » de l'Atelier "
                            + "de création. C'est là qu'elle se modifie désormais. Cette mention concerne la "
                            + "description de l'emplacement, pas le bâtiment qui y est posé.");
        } else {
            sb.append("Avant l'Atelier de création, le bâtiment à construire se décrivait ici. Cette "
                    + "description est conservée telle quelle ; pour en faire un bâtiment, reprenez-la "
                    + "dans l'Atelier (l'emplacement n'est pas modifié).");
            if (perms.can(session.effective(), Permission.AI_USE)) {
                sb.append("<form method=\"post\" action=\"").append(WorkshopPages.BASE)
                        .append("/import\" class=\"inline\"><input type=\"hidden\" name=\"_csrf\" value=\"")
                        .append(Http.esc(session.csrfToken())).append("\"><input type=\"hidden\" "
                                + "name=\"site\" value=\"").append(Http.esc(site.id()))
                        .append("\"> <button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">")
                        .append(Icons.icon("wrench")).append("Reprendre dans l'Atelier</button></form>");
            }
        }
        sb.append("<details><summary class=\"muted\">Lire la description (")
                .append(site.aiBrief().length()).append(" caractères)</summary><p class=\"brief-text\">")
                .append(Http.esc(site.aiBrief())).append("</p></details></div></div>");
        return sb.toString();
    }

    /**
     * Compteur de caractères d'un champ long (issue #94).
     *
     * <p>La valeur initiale est rendue <strong>par le serveur</strong> : le compteur est juste même
     * si {@code panel.js} n'a pas encore tourné, ou pas du tout. {@code panel.js} ne fait ensuite
     * que le tenir à jour pendant la frappe. Aucun JS en ligne — la CSP du panel l'interdit.</p>
     */
    private static String charCounter(String fieldId, int current, int max) {
        boolean over = current > max;
        return "<p class=\"field-help charcount" + (over ? " charcount-over" : "")
                + "\" data-charcount-for=\"" + Http.esc(fieldId) + "\" data-charcount-max=\"" + max
                + "\"><span class=\"cc-n\">" + current + "</span> / " + max + " caractères"
                + (over ? " — <strong>dépassement de " + (current - max)
                        + "</strong> : l'enregistrement sera refusé." : "") + "</p>";
    }

    /**
     * Correction de l'orientation. Elle est éditable parce que se tromper de façade au moment du
     * clic est banal — alors que la <strong>position</strong>, elle, ne s'édite pas depuis un écran :
     * on retourne la désigner en jeu.
     */
    private String buildingSiteFacingForm(Session session, String agentId,
                                          com.lodygames.rpgquest.panel.building.BuildingSiteView site) {
        StringBuilder sb = new StringBuilder(
                formStart(session, agentId, "building.site.facing", "/buildings/sites", ""));
        sb.append("<input type=\"hidden\" name=\"id\" value=\"").append(Http.esc(site.id()))
                .append("\">");
        sb.append("<div class=\"field\"><label>Orientation</label><select name=\"facing\">");
        for (String facing : com.lodygames.rpgquest.panel.building.BuildingSiteView.FACINGS) {
            sb.append("<option value=\"").append(facing).append('"')
                    .append(facing.equals(site.facing()) ? " selected" : "").append('>')
                    .append(Http.esc(facing)).append("</option>");
        }
        sb.append("</select><p class=\"field-help\">Direction de la façade. La position "
                + "<code>").append(Http.esc(site.positionLabel()))
                .append("</code> ne change pas : pour la corriger, il faut re-marquer "
                        + "l'emplacement en jeu.</p></div>");
        sb.append(mutationConsent("building.site.facing", "", null));
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-primary\" "
                + "type=\"submit\">").append(Icons.icon("save"))
                .append("Changer l'orientation</button></div></form>");
        return sb.toString();
    }

    /**
     * Suppression. Case à cocher explicite (l'action est déclarée sensible dans le catalogue), et la
     * phrase répond à la seule question qui compte : non, aucun bloc du monde n'est touché.
     */
    private String buildingSiteDeleteZone(Session session, String agentId,
                                          com.lodygames.rpgquest.panel.building.BuildingSiteView site,
                                          boolean occupied) {
        String head = "<div class=\"danger-zone\"><div class=\"dz-title\">"
                + Icons.icon("warning") + "Supprimer l'emplacement (le repère seulement)</div>"
                + "<p class=\"muted\">Retire ce repère de la liste des emplacements. "
                + "<strong>Aucun bloc du monde n'est modifié.</strong> L'identifiant <code>"
                + Http.esc(site.id()) + "</code> ne sera jamais réattribué.</p>";
        // Issue #239 : le plugin accepte de supprimer le repère d'un emplacement OCCUPÉ — le bâtiment
        // resterait alors dans le monde sans plus aucun moyen de restaurer le terrain depuis PlugAdmin.
        if (occupied) {
            return head + Ui.banner("info", "Un bâtiment est encore posé ici. Retirez-le d'abord avec "
                    + "« Retirer le bâtiment et restaurer le terrain » : supprimer le repère maintenant "
                    + "laisserait le bâtiment dans le monde, sans plus aucun moyen de rendre le terrain.")
                    + "</div>";
        }
        return head + ""
                + formStart(session, agentId, "building.site.delete", "/buildings/sites", "")
                + "<input type=\"hidden\" name=\"id\" value=\"" + Http.esc(site.id()) + "\">"
                + confirmBox("Supprimer l'emplacement « " + site.name() + " » (" + site.id() + ")")
                + "<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-danger\" "
                + "type=\"submit\">" + Icons.icon("warning") + "Supprimer l'emplacement"
                + "</button></div></form></div>";
    }

    // ---- Villes (issue #178) ---------------------------------------------------------------

    /** Le dernier relevé {@code city.list} réussi ; indisponible sans relevé. */
    public com.lodygames.rpgquest.panel.city.CityDirectory cityDirectory(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return com.lodygames.rpgquest.panel.city.CityDirectory.unavailable();
        }
        return latestDetails(agentId, "city.list")
                .map(com.lodygames.rpgquest.panel.city.CityDirectory::from)
                .orElseGet(com.lodygames.rpgquest.panel.city.CityDirectory::unavailable);
    }

    /**
     * Page {@code /cities} (issue #178). Une ville <strong>est</strong> un centre de village (#151) :
     * la même fiche sert au menu de voyage « Villages » et à la découverte en jeu.
     *
     * <p>Pas de bouton « Créer » : le centre se désigne en jeu avec l'outil de ville, comme un
     * emplacement de construction. Tout le reste — nom, taille, état, déplacement, suppression — se
     * fait ici.</p>
     */
    public String cities(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("world", "Villes",
                "Les villes définies en jeu avec l'outil de ville. Un joueur qui entre pour la première "
                        + "fois dans le carré d'une ville active voit « Bienvenue à … » et la découvre.", ""));
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        boolean canWrite = perms.can(session.effective(), Permission.CITY_WRITE);
        boolean canDelete = perms.can(session.effective(), Permission.CITY_DELETE);

        sb.append(agentPicker(agentId, "/cities", ""));
        sb.append("<div class=\"npc-catbar\"><span class=\"npc-catbar-t\">Catalogue</span>");
        sb.append(compactRefresh(session, agentId, "city.list", "Rafraîchir les villes",
                "btn-outline-primary", "/cities"));
        if (canWrite) {
            sb.append(compactRefresh(session, agentId, "player.list", "Joueurs connectés",
                    "btn-outline-secondary", "/cities"));
            sb.append(compactRefresh(session, agentId, "npc.list", "PNJ", "btn-outline-secondary", "/cities"));
        }
        sb.append("</div>");

        var directory = cityDirectory(agentId);
        if (!directory.available()) {
            sb.append(Ui.empty("world", "Aucun relevé chargé — cliquer sur « Rafraîchir les villes »."));
            sb.append(cityHowToCard(directory));
            return sb.toString();
        }
        if (directory.cities().isEmpty()) {
            sb.append(Ui.empty("world", "Aucune ville pour le moment."));
            sb.append(cityHowToCard(directory));
            return sb.toString();
        }
        for (var move : directory.pendingMoves()) {
            sb.append(Ui.banner("info", "Déplacement en attente : le prochain clic droit de <strong>"
                    + Http.esc(move.player()) + "</strong> avec l'outil de ville deviendra le nouveau centre "
                    + "de <strong>" + Http.esc(directory.nameOf(move.cityId())) + "</strong>. Rafraîchissez "
                    + "après le clic pour relire le résultat."));
        }

        String worldFilter = str(q.get("world")).trim();
        var shown = directory.cities().stream()
                .filter(c -> worldFilter.isEmpty() || c.world().equals(worldFilter)).toList();
        sb.append("<p class=\"npc-summary\">").append(directory.cities().size()).append(" ville(s)");
        long inactive = directory.cities().stream().filter(c -> !c.active()).count();
        if (inactive > 0) {
            sb.append(" &middot; ").append(inactive).append(" inactive(s)");
        }
        if (!worldFilter.isEmpty()) {
            sb.append(" &middot; monde : <code>").append(Http.esc(worldFilter)).append("</code>");
        }
        sb.append("</p>");
        if (directory.worlds().size() > 1) {
            sb.append("<div class=\"btnrow\"><a class=\"btn btn-sm ")
                    .append(worldFilter.isEmpty() ? "btn-primary" : "btn-outline-secondary")
                    .append("\" href=\"/cities\">Tous les mondes</a>");
            for (String world : directory.worlds()) {
                sb.append("<a class=\"btn btn-sm ")
                        .append(world.equals(worldFilter) ? "btn-primary" : "btn-outline-secondary")
                        .append("\" href=\"/cities?world=")
                        .append(java.net.URLEncoder.encode(world, java.nio.charset.StandardCharsets.UTF_8))
                        .append("\">").append(Http.esc(world)).append("</a>");
            }
            sb.append("</div>");
        }
        sb.append(Ui.searchToolbar("cities", "Rechercher une ville (nom, monde, identifiant)…", ""));

        List<String> onlinePlayers = latestDetails(agentId, "player.list")
                .map(x -> asList(x.get("players"))).orElse(List.of())
                .stream().map(o -> str(asMap(o).get("name"))).filter(s -> !s.isEmpty()).toList();
        List<com.lodygames.rpgquest.panel.content.SourceCatalog.QuestSource> sourceQuests = sourceCatalog.quests();
        Map<String, String> npcChoices = cityNpcChoices(agentId);
        if (canWrite) {
            sb.append(cityNpcDatalist(npcChoices));
        }
        sb.append("<div class=\"accordion npc-accordion\" id=\"city-accordion\">");
        int index = 0;
        for (var city : shown) {
            sb.append(cityItem(session, agentId, city, index++, directory, canWrite, canDelete,
                    onlinePlayers, questsCiting(city.id(), sourceQuests), npcChoices));
        }
        sb.append("</div>");
        sb.append(cityHowToCard(directory));
        return sb.toString();
    }

    /** Quêtes de la source dont un objectif DISCOVER_CITY désigne cette ville précise. */
    private static List<String> questsCiting(String cityId,
            List<com.lodygames.rpgquest.panel.content.SourceCatalog.QuestSource> quests) {
        List<String> out = new ArrayList<>();
        for (var qs : quests) {
            boolean cites = qs.draft().steps.stream().flatMap(s -> s.objectives.stream())
                    .anyMatch(o -> "DISCOVER_CITY".equalsIgnoreCase(str(o.get("type")))
                            && cityId.equals(str(o.get("city")).trim()));
            if (cites) {
                String title = qs.draft().title == null ? "" : MiniText.plain(qs.draft().title).trim();
                out.add(title.isEmpty() ? qs.plainId() : title + " (" + qs.plainId() + ")");
            }
        }
        return out;
    }

    private String cityItem(Session session, String agentId, com.lodygames.rpgquest.panel.city.CityView city,
                            int index, com.lodygames.rpgquest.panel.city.CityDirectory directory,
                            boolean canWrite, boolean canDelete, List<String> onlinePlayers,
                            List<String> citingQuests, Map<String, String> npcChoices) {
        String slug = "city-" + index + "-" + city.id().replaceAll("[^a-z0-9_-]", "-");
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"accordion-item npc-item\" data-filter-item=\"cities\" data-filter-text=\"")
                .append(Http.esc(city.searchText())).append("\">");
        sb.append("<h3 class=\"accordion-header\"><button class=\"accordion-button collapsed npc-head\" "
                + "type=\"button\" data-bs-toggle=\"collapse\" data-bs-target=\"#").append(slug)
                .append("\" aria-expanded=\"false\" aria-controls=\"").append(slug).append("\">");
        sb.append("<span class=\"npc-head-main\"><span class=\"npc-name\">").append(Http.esc(city.name()))
                .append("</span><code class=\"tid npc-id\">").append(Http.esc(city.id())).append("</code>")
                .append("<span class=\"npc-loc\">").append(Icons.icon("world")).append(Http.esc(city.world()))
                .append(' ').append(Http.esc(city.centerLabel())).append("</span></span>");
        sb.append("<span class=\"npc-head-badges\">");
        sb.append(city.active() ? "<span class=\"badge text-bg-success\">active</span>"
                : "<span class=\"badge text-bg-secondary\">inactive</span>");
        sb.append("<span class=\"badge text-bg-light text-dark\">").append(city.distance())
                .append(" blocs · ").append(Http.esc(city.sizeLabel())).append("</span>");
        if (!city.overlaps().isEmpty()) {
            sb.append("<span class=\"badge text-bg-warning\">chevauchement</span>");
        }
        if (!city.worldLoaded()) {
            sb.append("<span class=\"badge text-bg-warning\">monde non chargé</span>");
        }
        var anchors = city.anchors();
        if (anchors.hasCentralNpc()) {
            sb.append("<span class=\"badge ").append(severityBadge(anchors.npcSeverity())).append("\">PNJ ")
                    .append(Http.esc(anchors.npcLabel())).append("</span>");
        }
        if (anchors.hasArrival()) {
            sb.append("<span class=\"badge text-bg-light text-dark\">arrivée distincte</span>");
        }
        sb.append("</span></button></h3>");
        sb.append("<div id=\"").append(slug).append("\" class=\"accordion-collapse collapse\" "
                + "data-bs-parent=\"#city-accordion\"><div class=\"accordion-body npc-detail\">");

        sb.append(detailSection("world", "Ville"));
        sb.append("<dl class=\"npc-dl\">");
        dlRow(sb, "Nom", Http.esc(city.name()));
        dlRow(sb, "Identifiant", Ui.id(city.id()) + " <span class=\"muted\">— ne change jamais, même "
                + "après un renommage ou un déplacement</span>");
        dlRow(sb, "Monde", Http.esc(city.world()));
        dlRow(sb, "Centre", "<code class=\"tid\">" + Http.esc(city.centerLabel()) + "</code>");
        dlRow(sb, "Périmètre", "<strong>" + Http.esc(city.ruleSentence()) + "</strong>");
        dlRow(sb, "Dimensions", Http.esc(city.sizeLabel()) + " <span class=\"muted\">(largeur totale "
                + "= 2 × " + city.distance() + " + 1, le bloc central compte)</span>");
        dlRow(sb, "Limites (coins inclus)", "<code class=\"tid\">" + Http.esc(city.boundsLabel()) + "</code>");
        dlRow(sb, "Altitude", "<span class=\"muted\">ignorée : toute la hauteur du monde compte</span>");
        dlRow(sb, "État", city.active() ? "Active — découvrable, et proposée dans le menu de voyage « Villages »"
                : "Inactive — ni découverte, ni menu de voyage");
        dlRow(sb, "Découvertes", city.discoveries() + " joueur(s)");
        dlRow(sb, "Créée le", Http.esc(city.createdAtLabel()));
        if (!city.overlaps().isEmpty()) {
            List<String> names = city.overlaps().stream().map(directory::nameOf).toList();
            dlRow(sb, "Chevauche", Http.esc(String.join(", ", names)) + "<br><span class=\"muted\">Dans la "
                    + "zone commune, c'est la ville dont le centre est le plus proche qui est annoncée et "
                    + "découverte (à égalité : la plus petite, puis l'identifiant). Un seul titre à la fois.</span>");
        }
        if (!citingQuests.isEmpty()) {
            dlRow(sb, "Quêtes qui la citent", Http.esc(String.join(", ", citingQuests)));
        }
        sb.append("</dl>");
        sb.append(cityPerimeterSvg(city, directory));
        sb.append(cityAnchorsSection(session, agentId, city, canWrite, onlinePlayers, npcChoices));

        if (canWrite) {
            sb.append(detailSection("edit", "Modifier"));
            sb.append(formStart(session, agentId, "city.rename", "/cities", "")
                    + "<input type=\"hidden\" name=\"id\" value=\"" + Http.esc(city.id()) + "\">"
                    + "<div class=\"field\"><label>Nom affiché</label><input type=\"text\" name=\"name\" "
                    + "minlength=\"2\" maxlength=\"40\" required value=\"" + Http.esc(city.name()) + "\">"
                    + "<p class=\"field-help\">C'est le nom du titre « Bienvenue à … » et du menu de voyage. "
                    + "Les découvertes et les quêtes suivent l'identifiant, pas le nom : rien n'est perdu.</p></div>"
                    + mutationConsent("city.rename", "", null)
                    + "<div class=\"btnrow\"><button class=\"btn btn-sm\" type=\"submit\">" + Icons.icon("save")
                    + "Renommer</button></div></form>");
            sb.append(formStart(session, agentId, "city.resize", "/cities", "")
                    + "<input type=\"hidden\" name=\"id\" value=\"" + Http.esc(city.id()) + "\">"
                    + "<div class=\"field\"><label>Distance de détection (blocs dans chaque direction)</label>"
                    + "<input type=\"number\" name=\"distance\" required min=\"" + directory.minDistance()
                    + "\" max=\"" + directory.maxDistance() + "\" value=\"" + city.distance() + "\">"
                    + "<p class=\"field-help\">La ville s'étend de cette distance vers le nord, le sud, l'est et "
                    + "l'ouest depuis son centre : c'est un <strong>carré</strong>, pas un cercle, coins compris. "
                    + "50 donne un carré de 101 × 101 blocs ; 100 donne 201 × 201. Entre "
                    + directory.minDistance() + " et " + directory.maxDistance() + " (défaut "
                    + directory.defaultDistance() + ").</p></div>"
                    + mutationConsent("city.resize", "", null)
                    + "<div class=\"btnrow\"><button class=\"btn btn-sm\" type=\"submit\">" + Icons.icon("save")
                    + "Appliquer la taille</button></div></form>");
            sb.append(formStart(session, agentId, "city.active", "/cities", "")
                    + "<input type=\"hidden\" name=\"id\" value=\"" + Http.esc(city.id()) + "\">"
                    + "<input type=\"hidden\" name=\"active\" value=\"" + !city.active() + "\">"
                    + mutationConsent("city.active", "", null)
                    + "<p class=\"field-help\">" + (city.active()
                            ? "Désactiver : plus aucune découverte ni entrée dans le menu de voyage. Les "
                                    + "découvertes déjà faites sont conservées et comptent toujours pour les quêtes."
                            : "Activer : la ville redevient découvrable et apparaît dans le menu « Villages ».")
                    + "</p><div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\">"
                    + (city.active() ? "Désactiver la ville" : "Activer la ville") + "</button></div></form>");

            sb.append(detailSection("target", "En jeu"));
            sb.append(cityPlayerForm(session, agentId, "city.move.arm", city, onlinePlayers,
                    "Préparer le déplacement du centre",
                    "Le joueur choisi (connecté, avec la permission rpgquest.admin.city) reçoit un message ; "
                            + "son prochain clic droit avec l'outil de ville devient le nouveau centre. Valable "
                            + "3 minutes. Rafraîchissez ensuite cette page : le nouveau centre et les nouvelles "
                            + "limites s'affichent ci-dessus."));
            sb.append(cityPlayerForm(session, agentId, "city.perimeter.show", city, onlinePlayers,
                    "Montrer le périmètre en jeu",
                    "Dessine le contour du carré en particules orange (colonnes aux coins) au seul joueur "
                            + "choisi, pendant 15 secondes. Il doit être dans le monde " + city.world()
                            + ". Rien n'est modifié."));
        }
        if (canDelete) {
            sb.append(cityDeleteZone(session, agentId, city, citingQuests));
        }
        sb.append("</div></div></div>");
        return sb.toString();
    }

    /**
     * Issue #322 — PNJ définis connus du dernier relevé {@code npc.list} (identifiant → nom affiché).
     * Sans relevé, la carte vide : le champ reste une saisie libre et l'aide le dit.
     */
    private Map<String, String> cityNpcChoices(String agentId) {
        Map<String, String> out = new java.util.TreeMap<>();
        latestDetails(agentId, "npc.list").ifPresent(d -> {
            for (Object o : asList(d.get("npcs"))) {
                Map<String, Object> m = asMap(o);
                String id = str(m.get("id"));
                if (!id.isEmpty() && !"false".equals(str(m.get("logicalDefinitionPresent")))) {
                    out.put(id, str(m.get("displayName")));
                }
            }
        });
        return out;
    }

    private static String cityNpcDatalist(Map<String, String> npcChoices) {
        StringBuilder sb = new StringBuilder("<datalist id=\"dl-city-npc\">");
        npcChoices.forEach((id, name) -> {
            sb.append("<option value=\"").append(Http.esc(id)).append("\"");
            if (!name.isEmpty() && !name.equals(id)) {
                sb.append(" label=\"").append(Http.esc(MiniText.plain(name))).append("\"");
            }
            sb.append(">");
        });
        return sb.append("</datalist>").toString();
    }

    private static String severityBadge(String severity) {
        return switch (severity) {
            case "OK" -> "text-bg-success";
            case "WARNING" -> "text-bg-warning";
            case "ERROR" -> "text-bg-danger";
            default -> "text-bg-light text-dark";
        };
    }

    private static String severityBanner(String severity) {
        return switch (severity) {
            case "OK" -> "success";
            case "WARNING" -> "warn";
            case "ERROR" -> "error";
            default -> "info";
        };
    }

    /**
     * Issue #322 — PNJ central (identité stable, état relu par le plugin) et point d'arrivée du menu
     * « Villages ». La ville ne suit jamais son PNJ : le diagnostic dit où il est, l'admin décide.
     */
    private String cityAnchorsSection(Session session, String agentId,
                                      com.lodygames.rpgquest.panel.city.CityView city, boolean canWrite,
                                      List<String> onlinePlayers, Map<String, String> npcChoices) {
        var a = city.anchors();
        StringBuilder sb = new StringBuilder(detailSection("users", "PNJ central et arrivée"));
        sb.append("<dl class=\"npc-dl\">");
        dlRow(sb, "PNJ central", Http.esc(MiniText.plain(a.npcLabel())));
        if (a.hasCentralNpc() && !a.npcPositionLabel().isEmpty()) {
            dlRow(sb, "Position du PNJ", "<code class=\"tid\">" + Http.esc(a.npcPositionLabel()) + "</code>");
        }
        dlRow(sb, "Arrivée du voyage", "<code class=\"tid\">" + Http.esc(a.arrivalLabel()) + "</code>");
        sb.append("</dl>");
        if (a.hasCentralNpc() && !a.npcMessage().isEmpty()) {
            sb.append(Ui.banner(severityBanner(a.npcSeverity()), Http.esc(a.npcMessage())));
        }
        if (!a.arrivalMessage().isEmpty() && !"SET".equals(a.arrivalStatus())) {
            sb.append(Ui.banner(severityBanner(a.arrivalSeverity()), Http.esc(a.arrivalMessage())));
        }
        if (!canWrite) {
            return sb.toString();
        }
        String help = npcChoices.isEmpty()
                ? "Aucun relevé des PNJ : saisissez l'identifiant stable (ex. <code>guide</code>) ou "
                        + "rafraîchissez la page PNJ pour obtenir une liste recherchable."
                : "Recherchez par identifiant ou par nom. Seul l'identifiant stable est enregistré : le PNJ "
                        + "peut être renommé sans casser le lien. Le centre de détection ne change pas.";
        sb.append(formStart(session, agentId, "city.npc.link", "/cities", "")
                + "<input type=\"hidden\" name=\"id\" value=\"" + Http.esc(city.id()) + "\">"
                + catalogCombo("city-npc-" + city.id().replaceAll("[^A-Za-z0-9_-]", "-"), "npc", "npc",
                        "PNJ central (laisser vide pour le retirer)", a.centralNpcId(), "dl-city-npc", false, help)
                + mutationConsent("city.npc.link", "", null)
                + "<div class=\"btnrow\"><button class=\"btn btn-sm\" type=\"submit\">" + Icons.icon("save")
                + "Enregistrer le PNJ central</button></div></form>");
        sb.append(cityPlayerForm(session, agentId, "city.arrival.set", city, onlinePlayers,
                "Relever l'arrivée sur ce joueur",
                "L'arrivée du menu « Villages » devient la position et l'orientation actuelles du joueur choisi "
                        + "(connecté, dans le monde " + city.world() + ", debout sur un sol plein : sinon c'est "
                        + "refusé). Le centre de détection et le périmètre ne changent pas."));
        if (a.hasArrival()) {
            sb.append(formStart(session, agentId, "city.arrival.clear", "/cities", "")
                    + "<input type=\"hidden\" name=\"id\" value=\"" + Http.esc(city.id()) + "\">"
                    + mutationConsent("city.arrival.clear", "", null)
                    + "<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\">"
                    + "Revenir à l'arrivée au centre</button></div></form>");
        }
        return sb.toString();
    }

    private String cityPlayerForm(Session session, String agentId, String type,
                                  com.lodygames.rpgquest.panel.city.CityView city, List<String> onlinePlayers,
                                  String button, String help) {
        StringBuilder sb = new StringBuilder(formStart(session, agentId, type, "/cities", ""));
        sb.append("<input type=\"hidden\" name=\"id\" value=\"").append(Http.esc(city.id())).append("\">");
        sb.append("<div class=\"field\"><label>Joueur connecté</label>");
        if (onlinePlayers.isEmpty()) {
            sb.append("<input type=\"text\" name=\"player\" required maxlength=\"16\" placeholder=\"Pseudo exact\">");
        } else {
            sb.append("<select name=\"player\" required>");
            for (String p : onlinePlayers) {
                sb.append("<option value=\"").append(Http.esc(p)).append("\">").append(Http.esc(p)).append("</option>");
            }
            sb.append("</select>");
        }
        sb.append("<p class=\"field-help\">").append(Http.esc(help)).append("</p></div>");
        sb.append(mutationConsent(type, "", null));
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">")
                .append(Icons.icon("target")).append(Http.esc(button)).append("</button></div></form>");
        return sb.toString();
    }

    private String cityDeleteZone(Session session, String agentId, com.lodygames.rpgquest.panel.city.CityView city,
                                  List<String> citingQuests) {
        StringBuilder sb = new StringBuilder("<div class=\"danger-zone\"><div class=\"dz-title\">")
                .append(Icons.icon("warning")).append("Supprimer la ville</div>");
        sb.append("<p class=\"muted\">Conséquences, toutes définitives :</p><ul class=\"muted\">");
        sb.append("<li>la ville disparaît de cette page et du menu de voyage « Villages » ;</li>");
        sb.append("<li><strong>ses ").append(city.discoveries()).append(" découverte(s) sont effacées</strong> : "
                + "elles ne compteront plus pour aucune quête, et une ville recréée plus tard (même sous ce "
                + "nom) repartira sans aucune découverte ;</li>");
        if (citingQuests.isEmpty()) {
            sb.append("<li>aucune quête de la source ne la cite précisément ;</li>");
        } else {
            sb.append("<li>ces quêtes la citent et <strong>ne pourront plus progresser</strong> : ")
                    .append(Http.esc(String.join(", ", citingQuests))).append(" ;</li>");
        }
        sb.append("<li>aucun bloc, aucun PNJ et aucun bâtiment n'est touché.</li></ul>");
        sb.append("<p class=\"muted\">Pour la retirer temporairement sans rien perdre, préférez "
                + "<strong>Désactiver la ville</strong>.</p>");
        sb.append(formStart(session, agentId, "city.delete", "/cities", ""))
                .append("<input type=\"hidden\" name=\"id\" value=\"").append(Http.esc(city.id())).append("\">")
                .append(confirmBox("Supprimer définitivement « " + city.name() + " » (" + city.id()
                        + ") et ses découvertes"))
                .append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-danger\" type=\"submit\">")
                .append(Icons.icon("warning")).append("Supprimer la ville</button></div></form></div>");
        return sb.toString();
    }

    /**
     * Aperçu du carré, sans script (CSP) : la ville, son centre, et les villes du même monde qui la
     * chevauchent, à l'échelle. Nord en haut (Z croissant vers le bas, comme la carte F3).
     */
    private static String cityPerimeterSvg(com.lodygames.rpgquest.panel.city.CityView city,
                                           com.lodygames.rpgquest.panel.city.CityDirectory directory) {
        List<com.lodygames.rpgquest.panel.city.CityView> others = city.overlaps().stream()
                .map(directory::find).flatMap(Optional::stream).toList();
        int minX = city.minX();
        int maxX = city.maxX() + 1;
        int minZ = city.minZ();
        int maxZ = city.maxZ() + 1;
        for (var o : others) {
            minX = Math.min(minX, o.minX());
            maxX = Math.max(maxX, o.maxX() + 1);
            minZ = Math.min(minZ, o.minZ());
            maxZ = Math.max(maxZ, o.maxZ() + 1);
        }
        int pad = Math.max(4, (maxX - minX) / 12);
        minX -= pad;
        minZ -= pad;
        maxX += pad;
        maxZ += pad;
        int w = maxX - minX;
        int h = maxZ - minZ;
        StringBuilder sb = new StringBuilder("<figure class=\"city-map\"><svg viewBox=\"0 0 ")
                .append(w).append(' ').append(h).append("\" width=\"240\" height=\"")
                .append(Math.max(60, 240 * h / Math.max(1, w)))
                .append("\" role=\"img\" aria-label=\"Aperçu du périmètre de ").append(Http.esc(city.name()))
                .append("\" style=\"background:#f6f8fa;border:1px solid #d0d7de;max-width:100%\">");
        for (var o : others) {
            sb.append("<rect x=\"").append(o.minX() - minX).append("\" y=\"").append(o.minZ() - minZ)
                    .append("\" width=\"").append(o.width()).append("\" height=\"").append(o.width())
                    .append("\" fill=\"#f0ad4e\" fill-opacity=\"0.25\" stroke=\"#c77c02\" stroke-dasharray=\"3 2\" "
                            + "vector-effect=\"non-scaling-stroke\"><title>").append(Http.esc(o.name()))
                    .append("</title></rect>");
        }
        sb.append("<rect x=\"").append(city.minX() - minX).append("\" y=\"").append(city.minZ() - minZ)
                .append("\" width=\"").append(city.width()).append("\" height=\"").append(city.width())
                .append("\" fill=\"#2da44e\" fill-opacity=\"0.2\" stroke=\"#1a7f37\" stroke-width=\"2\" "
                        + "vector-effect=\"non-scaling-stroke\"><title>").append(Http.esc(city.name()))
                .append("</title></rect>");
        double r = Math.max(1.5, w / 80.0);
        sb.append("<circle cx=\"").append(city.centerX() - minX + 0.5).append("\" cy=\"")
                .append(city.centerZ() - minZ + 0.5).append("\" r=\"").append(r).append("\" fill=\"#1a7f37\"/>");
        sb.append("</svg><figcaption class=\"muted\">").append(Http.esc(city.sizeLabel()))
                .append(" · nord en haut").append(others.isEmpty() ? "" : " · en orange : villes qui chevauchent")
                .append("</figcaption></figure>");
        return sb.toString();
    }

    private static String cityHowToCard(com.lodygames.rpgquest.panel.city.CityDirectory directory) {
        return "<section class=\"card\">"
                + Ui.sectionTitle("docs", "Créer une ville : en jeu, avec l'outil de ville")
                + "<ol class=\"muted\">"
                + "<li>En jeu : <code>/rpgadmin ville outil</code> (permission <code>rpgquest.admin.city</code>) "
                + "pour recevoir la <strong>pelle en or « Outil de ville »</strong>.</li>"
                + "<li>Se placer au cœur de la future ville et faire <strong>clic droit</strong> sur un bloc : "
                + "ce bloc devient le centre. Un seul clic, pas de second coin.</li>"
                + "<li>Taper le nom dans la fenêtre (enclume) puis cliquer le résultat. Fermer la fenêtre annule.</li>"
                + "<li>Revenir ici et cliquer <strong>Rafraîchir les villes</strong> : la ville est déjà active, "
                + "avec " + directory.defaultDistance() + " blocs dans chaque direction.</li>"
                + "</ol>"
                + "<p class=\"field-help\">Aucun bâtiment ni PNJ n'est posé : le centre est un repère "
                + "géographique. Il sert aussi de point d'arrivée du menu de voyage « Villages ».</p>"
                + "</section>";
    }

    // ================================================================================
    //  Voyages — issue #412 : accueil à onglets (Lieux, Bornes, À vérifier, Avancé), rendu dans
    //  TravelPages. Lecture via travel.catalog ; écritures waypoint.rename (#134) et
    //  travel.beacon.pair (#156), inchangées. Le diagnostic #156/#236 reste ici, sous « Avancé ».
    // ================================================================================

    public String travel(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder(TravelPages.header());
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        sb.append(agentPicker(agentId, TravelPages.PAGE, ""));

        // Après une action, on revient sur l'onglet d'où elle est partie (« tab-<onglet> »), ou sur
        // la fiche du lieu renommé (son identifiant), comme /players.
        String focus = cleanFocus(q.get("focus"));
        TravelPages.Tab tab = focus.startsWith("tab-") ? TravelPages.Tab.of(focus.substring(4))
                : TravelPages.Tab.of(q.getOrDefault("tab", ""));
        String refresh = compactRefresh(session, agentId, "travel.catalog", "Actualiser", "secondary sm",
                TravelPages.PAGE).replace("</form>", tab == TravelPages.Tab.PLACES ? "</form>"
                : "<input type=\"hidden\" name=\"focus\" value=\"tab-" + tab.wire + "\"></form>");

        Optional<AgentActionRow> surveyRow = store.latestSuccessfulActionOfType(agentId, "travel.catalog");
        Optional<Map<String, Object>> latest = surveyRow.flatMap(this::detailsOf);
        if (latest.isEmpty()) {
            return sb.append(Ui.empty("travel", "Rien n'a encore été relevé sur le serveur : clique sur « Actualiser »."))
                    .append("<div class=\"npc-catbar\">").append(refresh).append("</div>").toString();
        }
        Map<String, Object> d = latest.get();
        TravelPages.Survey survey = TravelPages.parse(d);
        Instant now = Instant.now();
        long generatedAt = d.get("generatedAtEpochMs") instanceof Number n ? n.longValue() : 0L;
        Instant surveyedAt = generatedAt > 0 ? Instant.ofEpochMilli(generatedAt)
                : surveyRow.map(r -> r.completedAt() != null ? r.completedAt() : r.createdAt()).orElse(now);
        List<AgentActionRow> recent = store.recentActions(agentId, 100);
        java.util.Set<String> pendingRenames = new java.util.HashSet<>();
        boolean refreshing = false;
        for (AgentActionRow a : recent) {
            if (a.status().terminal()) {
                continue;
            }
            if ("waypoint.rename".equals(a.type())) {
                pendingRenames.add(a.params().getOrDefault("waypointId", "").toLowerCase(Locale.ROOT));
            } else if ("travel.catalog".equals(a.type())) {
                refreshing = true;
            }
        }
        com.lodygames.rpgquest.panel.agent.AgentLiveness live = com.lodygames.rpgquest.panel.agent.AgentLiveness.of(
                store.latestHeartbeat(agentId),
                com.lodygames.rpgquest.panel.agent.AgentLiveness.Thresholds.defaults(), now);
        TravelPages.Ctx ctx = new TravelPages.Ctx(session.csrfToken(), agentId,
                perms.can(session.effective(), Permission.WAYPOINT_WRITE),
                perms.can(session.effective(), Permission.TRAVEL_PAIR_WRITE),
                com.lodygames.rpgquest.panel.agent.AgentLiveness.ageHuman(surveyedAt, now),
                java.time.Duration.between(surveyedAt, now).toMinutes() >= TravelPages.STALE_AFTER_MINUTES,
                refreshing, live == com.lodygames.rpgquest.panel.agent.AgentLiveness.OFFLINE,
                pendingRenames, refresh);

        if (!focus.isEmpty() && !focus.startsWith("tab-")) {
            TravelPages.Place place = survey.place(focus);
            sb.append(TravelPages.top(ctx, survey, TravelPages.Tab.PLACES));
            return sb.append(place == null ? TravelPages.placeNotFound(ctx, focus)
                    : TravelPages.placeSheet(ctx, survey, place)).toString();
        }
        sb.append(TravelPages.top(ctx, survey, tab));
        String body = switch (tab) {
            case BEACONS -> TravelPages.beacons(ctx, survey, parsePageParam(q.get("bp")));
            case CHECK -> TravelPages.check(ctx, survey, now.toEpochMilli());
            case ADVANCED -> TravelPages.advanced(travelAdvanced(session, agentId, d, survey, q));
            case PLACES -> TravelPages.places(ctx, survey, new TravelPages.PlaceQuery(q.getOrDefault("q", "").trim(),
                    TravelPages.Kind.of(q.getOrDefault("kind", "")), "recent".equals(q.get("sort")),
                    parsePageParam(q.get("p"))));
        };
        return sb.append("<section class=\"tv-tab\">").append(body).append("</section>").toString();
    }

    /** Issue #412 — message de refus d'un renommage (nom pris ou inchangé), ou {@code null}. */
    public String waypointRenameConflict(String agentId, String waypointId, String displayName) {
        return latestDetails(agentId, "travel.catalog").map(TravelPages::parse)
                .map(s -> TravelPages.renameConflict(s, waypointId, displayName)).orElse(null);
    }

    /**
     * Onglet « Avancé » : le diagnostic des issues #156/#236, tel quel, puis les tableaux techniques
     * repliés. Il porte toujours sur le réseau complet — aucune recherche de l'onglet Lieux ne s'y
     * applique, donc aucune moyenne n'est jamais calculée sur un sous-ensemble.
     */
    private String travelAdvanced(Session session, String agentId, Map<String, Object> d, TravelPages.Survey survey,
                                  Map<String, String> q) {
        TravelNetworkDiagnostic diag = survey.diag();
        StringBuilder sb = new StringBuilder();
        sb.append(travelHubDiagnostic(diag));
        sb.append(travelCoverage(diag));
        sb.append(travelBeaconSheets(diag));
        sb.append(travelInstances(session, agentId, diag,
                TravelNetworkDiagnostic.InstanceFilter.ofWire(q.get("hi")), q.getOrDefault("hq", "").trim()));
        sb.append(travelWildNetwork(diag));
        List<Object> unpaired = asList(d.get("unpairedHubInstances"));
        sb.append(Ui.sectionTitle("travel", "Appariements manquants dans le Hub (" + unpaired.size() + ")"));
        sb.append(renderUnpairedTable(unpaired));
        List<Object> waypoints = asList(d.get("waypoints"));
        List<Object> beacons = asList(d.get("beacons"));
        sb.append("<details class=\"qc-card qc-advanced\" id=\"tableaux-techniques\"")
                .append(q.containsKey("wp") || q.containsKey("tb") ? " open" : "").append("><summary>Tableaux techniques — ")
                .append(waypoints.size()).append(" waypoint(s) · ").append(Http.esc(str(d.get("beaconCount"))))
                .append(" borne(s) (").append(Http.esc(str(d.get("autoGeneratedBeaconCount"))))
                .append(" auto-générée(s))</summary>");
        sb.append(Ui.sectionTitle("travel", "Waypoints (" + waypoints.size() + ")"));
        sb.append(renderWaypointTable(agentId, waypoints, parsePageParam(q.get("wp"))));
        sb.append(Ui.sectionTitle("travel", "Bornes (" + beacons.size() + ")"));
        sb.append(renderBeaconTable(agentId, beacons, parsePageParam(q.get("tb"))));
        return sb.append("</details>").toString();
    }

    /**
     * Issue #156 — « RÉSEAU HUB — DIAGNOSTIC » : les chiffres qui répondent à « pourquoi si peu de
     * bornes ? » avant toute liste.
     *
     * <p>Le premier fait à établir est que les bornes n'existent que dans le Hub : tant que l'écran
     * ne le disait pas, chercher une borne dans le Wild semblait un manque de densité alors que
     * c'est une politique.</p>
     */
    private String travelHubDiagnostic(TravelNetworkDiagnostic diag) {
        if (!diag.available()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.sectionTitle("travel", "Réseau Hub — diagnostic"));
        if (!diag.referenceKnown()) {
            // Le panel se déploie avant le plugin : ce relevé peut précéder la version qui envoie le
            // référentiel du réseau. Le dire, plutôt que d'affirmer qu'aucun Hub n'est configuré.
            return sb.append(Ui.banner("info", "Ce relevé date d'une version du plugin antérieure au "
                    + "diagnostic du réseau : cliquer sur « Rafraîchir » pour en obtenir un complet.")).toString();
        }
        if (diag.hubWorld().isEmpty()) {
            return sb.append(Ui.banner("warn", "Aucun monde Hub configuré : aucune borne ne peut "
                    + "être générée. Les bornes n'existent que dans le Hub.")).toString();
        }
        sb.append("<p class=\"muted\">Les bornes de voyage existent <strong>uniquement</strong> dans "
                + "le monde Hub <code>").append(Http.esc(diag.hubWorld())).append("</code>, par "
                + "politique du projet — il n'y en a jamais eu dans le Wild, et aucun réglage de "
                + "densité n'en créera. Le réseau de voyage du Wild est celui des Waystones, décrit "
                + "plus bas.</p>");
        if (!diag.hubBeaconGenerationEnabled()) {
            sb.append(Ui.banner("err", "La génération automatique de bornes du Hub est "
                    + "<strong>désactivée</strong> dans la configuration : aucune nouvelle borne "
                    + "n'apparaîtra, quel que soit le nombre d'instances visitées."));
        }

        sb.append("<div class=\"cards\">");
        sb.append(Ui.statCard("travel", String.valueOf(diag.hubWaypoints()),
                "instances du Hub (waypoints)", "", ""));
        sb.append(Ui.statCard("travel", String.valueOf(diag.hubBeacons()), "bornes du Hub", "", ""));
        sb.append(Ui.statCard("travel", String.valueOf(diag.completePairs()), "paires complètes",
                diag.completePairs() == diag.hubWaypoints() ? "ok" : "", ""));
        sb.append(Ui.statCard("warning", String.valueOf(diag.missingPairs()), "instances sans borne",
                diag.missingPairs() == 0 ? "ok" : "warn", ""));
        sb.append(Ui.statCard("warning", String.valueOf(diag.orphanBeacons()), "bornes orphelines",
                diag.orphanBeacons() == 0 ? "ok" : "err", ""));
        sb.append("</div>");

        // La cause mécanique, dite une fois pour toutes : sans elle, un administrateur conclut à un
        // défaut de génération là où il n'y a qu'un déclencheur jamais passé.
        sb.append("<p>Une borne n'est cherchée <strong>que</strong> lorsqu'un joueur se déplace dans "
                + "une instance de biome du Hub qui n'en a pas encore. Il n'existe aucun balayage du "
                + "monde, aucune tâche de fond, aucune pré-génération : le nombre de bornes mesure "
                + "donc <strong>l'exploration réelle du Hub</strong>, pas une densité configurée.</p>");
        sb.append("<p class=\"muted\">Une instance de biome = (monde, type de biome, tuile de ")
                .append(diag.instanceRegionSize()).append(" blocs). Une borne est posée entre ")
                .append(diag.pairMinSpacing()).append(" et ").append(diag.pairMaxSpacing())
                .append(" blocs de son waypoint, et jamais à moins de ")
                .append(diag.waypointMinimumSpacing()).append(" blocs d'une autre borne.</p>");

        if (!diag.lastBeaconCreatedAt().isEmpty()) {
            sb.append(Ui.metaLine("Dernière borne créée",
                    "<code>" + Http.esc(diag.lastBeaconCreatedAt()) + "</code>"));
        }
        if (!diag.lastInstanceCreatedAt().isEmpty()) {
            sb.append(Ui.metaLine("Dernière instance découverte",
                    "<code>" + Http.esc(diag.lastInstanceCreatedAt()) + "</code>"));
        }
        if (diag.otherWorldWaypoints() > 0) {
            sb.append(Ui.metaLine("Waypoints hors du Hub",
                    diag.otherWorldWaypoints() + " — sans borne par politique, voir les Waystones"));
        }

        if (diag.hubComplete()) {
            sb.append(Ui.banner("ok", "Chaque instance du Hub ayant un waypoint a sa borne, et "
                    + "aucune borne n'est orpheline."));
        }
        for (TravelNetworkDiagnostic.BeaconRow row : diag.anomalies()) {
            sb.append(Ui.banner("warn", "Borne <code>" + Http.esc(row.id()) + "</code> : "
                    + Http.esc(row.anomaly()) + "."));
        }
        return sb.toString();
    }

    /**
     * Issue #156 — « COUVERTURE » : la géographie du réseau, pour que « il y a peu de bornes » puisse
     * être confronté à « sur quelle étendue ».
     *
     * <p>Une distance non calculable est affichée comme telle : avec une seule borne, il n'existe pas
     * de distance entre bornes, et afficher {@code 0} laisserait croire à des bornes superposées.</p>
     */
    private String travelCoverage(TravelNetworkDiagnostic diag) {
        if (!diag.available() || !diag.referenceKnown() || diag.hubBeacons() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.sectionTitle("travel", "Couverture du Hub"));
        sb.append(Ui.tableOpen("Mesure", "Min", "Moyenne", "Médiane", "Max"));
        sb.append(statsRow("Distance à la borne la plus proche", diag.neighbourDistance()));
        sb.append(statsRow(diag.spawnKnown() ? "Distance au spawn du Hub"
                : "Distance au spawn (spawn inconnu)", diag.spawnDistance()));
        sb.append(Ui.tableClose());

        TravelNetworkDiagnostic.Extent e = diag.extent();
        if (e.known()) {
            sb.append(Ui.metaLine("Étendue couverte",
                    "X de <code>" + e.minX() + "</code> à <code>" + e.maxX() + "</code> ("
                            + e.width() + " blocs) · Z de <code>" + e.minZ() + "</code> à <code>"
                            + e.maxZ() + "</code> (" + e.depth() + " blocs)"));
            long per = diag.areaPerBeacon();
            if (per > 0) {
                sb.append(Ui.metaLine("Densité observée",
                        "environ une borne pour " + formatArea(per)
                                + " <span class=\"muted\">— mesure de l'explor&eacute; seulement, "
                                + "jamais une densit&eacute; configur&eacute;e</span>"));
            }
        }
        if (diag.spawnKnown()) {
            sb.append(Ui.metaLine("Spawn du Hub",
                    "<code>" + diag.spawnX() + ", " + diag.spawnZ() + "</code>"));
        }
        return sb.toString();
    }

    private String statsRow(String label, TravelNetworkDiagnostic.Stats stats) {
        if (!stats.known()) {
            return "<tr><td>" + Http.esc(label) + "</td><td colspan=\"4\" class=\"muted\">"
                    + "non calculable (moins de deux points)</td></tr>";
        }
        return "<tr><td>" + Http.esc(label) + "</td><td>" + stats.min() + " blocs</td><td>"
                + stats.mean() + " blocs</td><td>" + stats.median() + " blocs</td><td>"
                + stats.max() + " blocs</td></tr>";
    }

    /** Aire lisible : les blocs² deviennent illisibles au-delà du millier. */
    private static String formatArea(long squareBlocks) {
        if (squareBlocks < 1_000_000L) {
            return squareBlocks + " blocs²";
        }
        return String.format(Locale.FRANCE, "%.2f km²", squareBlocks / 1_000_000.0);
    }

    /**
     * Issue #156 — une fiche <strong>ouvrable</strong> par borne du Hub.
     *
     * <p>Le tableau paginé répond à « lesquelles existent » ; il ne répond pas à « celle-ci est-elle
     * normale ». La fiche rassemble ce qu'il fallait jusqu'ici recouper à la main entre trois
     * tableaux : l'instance servie, la distance à son waypoint confrontée à l'anneau configuré, la
     * distance au spawn, la borne voisine et l'âge.</p>
     *
     * <p>Sans JavaScript : {@code <details>} natif, comme le reste du panel (la CSP interdit le
     * script en ligne).</p>
     */
    private String travelBeaconSheets(TravelNetworkDiagnostic diag) {
        if (!diag.available() || !diag.referenceKnown() || diag.beacons().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.sectionTitle("travel", "Bornes du Hub — fiche par borne ("
                + diag.beacons().size() + ")"));
        sb.append("<p class=\"muted\">Ordre de création : la première ligne est la plus ancienne "
                + "borne du réseau.</p>");
        for (TravelNetworkDiagnostic.BeaconRow row : diag.beacons()) {
            sb.append("<details class=\"npc-registry\"><summary>")
                    .append(row.hasAnomaly() ? Icons.icon("warning") : Icons.icon("travel"))
                    .append("<code>").append(Http.esc(row.id())).append("</code> — ")
                    .append(Http.esc(row.biomeKey().isEmpty() ? "sans instance" : row.biomeKey()))
                    .append(" <span class=\"muted\">").append(row.x()).append(", ").append(row.y())
                    .append(", ").append(row.z()).append("</span>");
            if (row.hasAnomaly()) {
                sb.append(" <span class=\"badge text-bg-warning\">anomalie</span>");
            }
            sb.append("</summary><div class=\"mt-2\">");
            if (row.hasAnomaly()) {
                sb.append(Ui.banner("warn", Http.esc(row.anomaly())));
            }
            sb.append(Ui.metaLine("Instance servie", row.biomeInstance().isEmpty()
                    ? "<span class=\"badge text-bg-warning\">aucune</span> <span class=\"muted\">"
                            + "— borne posée à la main : elle n'est l'équipement d'aucune instance, "
                            + "et ne compte donc dans aucune paire</span>"
                    : "<code>" + Http.esc(row.biomeInstance()) + "</code>"));
            sb.append(Ui.metaLine("Waypoint apparié", row.pairedWaypointId().isEmpty()
                    ? "<span class=\"muted\">aucun</span>"
                    : "<code>" + Http.esc(row.pairedWaypointId()) + "</code>"));
            sb.append(Ui.metaLine("Distance à son waypoint", row.waypointDistance() < 0
                    ? "<span class=\"muted\">non calculable</span>"
                    : row.waypointDistance() + " blocs <span class=\"muted\">(anneau configuré : "
                            + diag.pairMinSpacing() + "–" + diag.pairMaxSpacing() + ")</span>"));
            sb.append(Ui.metaLine("Distance au spawn", row.spawnDistance() < 0
                    ? "<span class=\"muted\">spawn du Hub inconnu</span>"
                    : row.spawnDistance() + " blocs"));
            sb.append(Ui.metaLine("Borne la plus proche", row.nearestBeaconDistance() < 0
                    ? "<span class=\"muted\">elle est seule dans ce monde</span>"
                    : row.nearestBeaconDistance() + " blocs"));
            sb.append(Ui.metaLine("Origine", row.autoGenerated()
                    ? "auto-générée par l'exploration" : "posée par un administrateur"));
            sb.append(Ui.metaLine("Active", row.active() ? "oui"
                    : "<span class=\"badge text-bg-warning\">non — son bouton est inerte</span>"));
            sb.append(Ui.metaLine("Créée le", "<code>" + Http.esc(row.createdAt()) + "</code>"));
            sb.append("</div></details>");
        }
        return sb.toString();
    }

    /**
     * Issue #156 — « INSTANCES CONNUES DU HUB » : la vue qui fait apparaître le manque comme un état,
     * et non comme une absence de ligne.
     *
     * <p>C'est aussi d'ici que part le rattrapage ciblé : une instance nommée, un essai. Le bouton
     * n'apparaît qu'avec {@link Permission#TRAVEL_PAIR_WRITE}, parce qu'il pose des blocs réels.</p>
     */
    private String travelInstances(Session session, String agentId, TravelNetworkDiagnostic diag,
                                    TravelNetworkDiagnostic.InstanceFilter filter, String search) {
        if (!diag.available() || !diag.referenceKnown() || diag.instances().isEmpty()) {
            return "";
        }
        boolean canPair = perms.can(session.effective(), Permission.TRAVEL_PAIR_WRITE);
        List<TravelNetworkDiagnostic.InstanceRow> shown = diag.instancesMatching(filter, search);
        StringBuilder sb = new StringBuilder();
        // Issue #236 : le compteur montre TOUJOURS le total du réseau à côté du nombre affiché —
        // un « (6) » seul se lirait « le Hub n'a que 6 instances ».
        // Ancre : les liens du filtre y reviennent, pour ne pas renvoyer l'écran en haut de page à
        // chaque clic — sur téléphone, la liste est loin sous les statistiques.
        sb.append("<div id=\"instances-hub\"></div>");
        sb.append(Ui.sectionTitle("travel", "Instances connues du Hub (" + shown.size() + "/"
                + diag.instances().size() + ")"));
        sb.append("<p class=\"muted\">Une ligne = une instance de biome où un joueur est réellement "
                + "passé. Une instance jamais traversée n'existe nulle part : elle n'est pas un "
                + "manque, elle n'est pas encore découverte.</p>");
        sb.append(travelInstanceFilterBar(agentId, diag, filter, search));
        if (shown.isEmpty()) {
            return sb.append(Ui.empty("travel", "Aucune instance ne correspond à ce filtre. Les "
                    + "statistiques de couverture ci-dessus portent sur le réseau complet, pas sur "
                    + "cette sélection.")).toString();
        }
        for (TravelNetworkDiagnostic.InstanceRow row : shown) {
            sb.append("<details class=\"npc-registry\"><summary>")
                    .append(row.complete() ? Icons.icon("travel") : Icons.icon("warning"))
                    .append(Http.esc(row.biomeKey())).append(" <span class=\"muted\">")
                    .append(row.x()).append(", ").append(row.z()).append("</span> ")
                    .append(row.complete()
                            ? "<span class=\"badge text-bg-success\">borne présente</span>"
                            : "<span class=\"badge text-bg-warning\">sans borne</span>")
                    .append("</summary><div class=\"mt-2\">");
            sb.append(Ui.metaLine("Instance", "<code>" + Http.esc(row.biomeInstance()) + "</code>"));
            sb.append(Ui.metaLine("Waypoint", "<code>" + Http.esc(row.waypointId()) + "</code>"));
            sb.append(Ui.metaLine("Borne", row.beaconId() == null || row.beaconId().isEmpty()
                    ? "<span class=\"badge text-bg-warning\">aucune</span>"
                    : "<code>" + Http.esc(row.beaconId()) + "</code>"));
            sb.append(Ui.metaLine("Découverte le", "<code>" + Http.esc(row.createdAt()) + "</code>"));
            if (!row.complete()) {
                sb.append(Ui.metaLine("Cause", Http.esc(row.reason())));
                sb.append(Ui.metaLine("Essais depuis le dernier démarrage", String.valueOf(row.attempts())));
                sb.append(Ui.metaLine("Borne la plus proche", row.nearestBeaconDistance() < 0
                        ? "<span class=\"muted\">aucune dans ce monde</span>"
                        : row.nearestBeaconDistance() + " blocs"));
                if (row.nextRetryEpochMs() != null
                        && row.nextRetryEpochMs() > System.currentTimeMillis()) {
                    sb.append(Ui.metaLine("Prochain essai automatique",
                            "dans " + ((row.nextRetryEpochMs() - System.currentTimeMillis()) / 1000)
                                    + " s, si un joueur retraverse l'instance"));
                }
                sb.append(travelPairForm(session, agentId, row, canPair));
            }
            sb.append("</div></details>");
        }
        return sb.toString();
    }

    /**
     * Issue #236 — filtrer la liste des instances du Hub, sans toucher aux statistiques.
     *
     * <p>Trois liens GET et un champ de recherche natif : la CSP interdit le script en ligne, et un
     * lien reste de toute façon la bonne primitive ici — il est partageable, il survit à un
     * rafraîchissement, et il fonctionne au clavier comme au doigt.</p>
     *
     * <p>Les paramètres sont volontairement nommés {@code hi}/{@code hq}, distincts du {@code q} et
     * du {@code world} de la page : ces derniers masquent tout le diagnostic, parce qu'ils filtrent
     * les tableaux dont les statistiques sont tirées. Celui-ci ne filtre qu'une liste, donc il ne
     * masque rien et les métriques restent celles du réseau complet.</p>
     */
    private String travelInstanceFilterBar(String agentId, TravelNetworkDiagnostic diag,
                                            TravelNetworkDiagnostic.InstanceFilter active, String search) {
        String base = "/travel?agent=" + Http.esc(agentId) + "&tab=avance";
        String keepSearch = search.isEmpty() ? "" : "&hq=" + Http.esc(search);
        StringBuilder sb = new StringBuilder("<div class=\"npc-catbar\">");
        sb.append("<span class=\"npc-catbar-t\">Afficher</span>");
        for (TravelNetworkDiagnostic.InstanceFilter f : TravelNetworkDiagnostic.InstanceFilter.values()) {
            int count = diag.instancesMatching(f, search).size();
            boolean on = f == active;
            sb.append("<a class=\"btn btn-sm ")
                    .append(on ? "btn-primary" : "btn-outline-secondary")
                    .append("\" href=\"").append(base).append("&hi=").append(f.wire())
                    .append(keepSearch).append("#instances-hub\"")
                    .append(on ? " aria-current=\"true\"" : "")
                    .append(">").append(Http.esc(f.label())).append(" <span class=\"badge text-bg-light\">")
                    .append(count).append("</span></a>");
        }
        sb.append("</div>");
        // Recherche : un formulaire GET, donc l'URL obtenue est partageable et rejouable.
        sb.append("<form method=\"get\" action=\"/travel\" class=\"npc-searchbar\">");
        sb.append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"tab\" value=\"avance\">");
        sb.append("<input type=\"hidden\" name=\"hi\" value=\"").append(active.wire()).append("\">");
        sb.append("<input type=\"search\" name=\"hq\" value=\"").append(Http.esc(search))
                .append("\" class=\"form-control form-control-sm\" "
                        + "placeholder=\"Biome, instance, waypoint, borne ou coordonnées (161,27)\">");
        sb.append("<button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\">")
                .append(Icons.icon("search")).append("Filtrer</button>");
        if (!search.isEmpty()) {
            sb.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"").append(base)
                    .append("&hi=").append(active.wire()).append("#instances-hub\">Effacer</a>");
        }
        sb.append("</form>");
        return sb.toString();
    }

    /**
     * Le rattrapage ciblé d'<strong>une</strong> instance (issue #156).
     *
     * <p>Le texte dit exactement la portée du geste, parce que c'est précisément ce qu'un bouton
     * nommé « Réparer » laisserait deviner : cette instance, un essai, rien d'autre. Aucun
     * rattrapage global n'est proposé ici — ce serait une décision d'équilibrage, pas d'exploitation.</p>
     */
    private String travelPairForm(Session session, String agentId,
                                   TravelNetworkDiagnostic.InstanceRow row, boolean canPair) {
        if (!canPair) {
            return "<p class=\"muted\">Le rattrapage d'une instance demande la permission "
                    + "« apparier une borne ».</p>";
        }
        return "<div class=\"mt-2\"><p>Poser maintenant la borne de cette instance, sans attendre "
                + "qu'un joueur la retraverse. L'emplacement est cherché entre les mêmes distances "
                + "que d'habitude, en respectant l'espacement minimal : <strong>cette instance "
                + "seulement</strong>, aucun balayage du monde, aucune autre borne, aucune densité "
                + "modifiée. S'il n'existe aucun emplacement valable, rien n'est posé et la raison "
                + "est affichée.</p>"
                + formStart(session, agentId, "travel.beacon.pair", "/travel", "")
                + "<input type=\"hidden\" name=\"focus\" value=\"tab-avance\">"
                + "<input type=\"hidden\" name=\"waypointId\" value=\"" + Http.esc(row.waypointId()) + "\">"
                + confirmBox("Apparier une borne à l'instance " + row.biomeInstance())
                + "<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-primary\" "
                + "type=\"submit\">" + Icons.icon("travel") + "Apparier cette instance"
                + "</button></div></form></div>";
    }

    /**
     * Issue #156 — le réseau du <strong>Wild</strong>, qui n'est pas celui des bornes.
     *
     * <p>C'est la section qui empêche la confusion de recommencer : chercher des bornes dans le Wild
     * ne donnera jamais rien, et ce qui compte là-bas est le nombre de Waystones
     * <strong>découvertes</strong> — une Waystone que personne n'a trouvée n'offre aucun voyage.</p>
     */
    private String travelWildNetwork(TravelNetworkDiagnostic diag) {
        if (!diag.available() || !diag.referenceKnown() || diag.waystoneNetworks().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.sectionTitle("travel", "Réseau du Wild — Waystones"));
        sb.append("<p class=\"muted\">Le Wild n'a <strong>aucune</strong> borne, par politique. Son "
                + "réseau de voyage est celui des Waystones, réparties sur une grille : au plus une "
                + "par cellule, et seulement avec une certaine probabilité. Une Waystone ne sert à "
                + "voyager qu'une fois <strong>découverte</strong> par un joueur.</p>");
        for (TravelNetworkDiagnostic.WaystoneRow row : diag.waystoneNetworks()) {
            sb.append("<details class=\"npc-registry\" open><summary>").append(Icons.icon("travel"))
                    .append("<code>").append(Http.esc(row.world())).append("</code> — ")
                    .append(row.total()).append(" Waystone(s)");
            if (row.discoveryKnown()) {
                sb.append(", ").append(row.discovered()).append(" découverte(s)");
            }
            sb.append("</summary><div class=\"mt-2\">");
            if (row.discoveryKnown() && row.noneDiscovered() && row.total() > 0) {
                sb.append(Ui.banner("warn", "Aucune de ces Waystones n'a jamais été découverte : le "
                        + "réseau de voyage du Wild est, en pratique, inexistant pour les joueurs."));
            }
            sb.append(Ui.metaLine("Grille", "une cellule de " + row.cellSize() + " blocs de côté, "
                    + "avec " + Http.esc(TravelNetworkDiagnostic.percent(row.chance()))
                    + " de chance d'en porter une"));
            long expected = (long) row.cellSize() * row.cellSize();
            if (expected > 0 && row.chance() > 0) {
                sb.append(Ui.metaLine("Densité théorique", "environ une Waystone pour "
                        + formatArea(Math.round(expected / row.chance()))));
            }
            sb.append(Ui.metaLine("Espacement minimal", row.minimumSpacing() + " blocs"));
            if (row.extent().known()) {
                sb.append(Ui.metaLine("Étendue connue",
                        "X de <code>" + row.extent().minX() + "</code> à <code>"
                                + row.extent().maxX() + "</code> · Z de <code>"
                                + row.extent().minZ() + "</code> à <code>" + row.extent().maxZ()
                                + "</code>"));
            }
            sb.append("</div></details>");
        }
        return sb.toString();
    }

    /**
     * Issue #156 — pourquoi ces instances n'ont pas de borne. Sans la cause, la liste ne permettait
     * que de constater un manque. « Jamais tenté » désigne le déclencheur, « candidats refusés »
     * désigne le terrain ou l'espacement minimal.
     */
    private String renderUnpairedTable(List<Object> rows) {
        if (rows.isEmpty()) {
            return Ui.empty("travel", "Chaque instance de biome du Hub ayant un waypoint a sa borne.");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<p class=\"muted\">Les compteurs d'essai vivent en mémoire : un redémarrage du serveur "
                + "les remet à zéro, « jamais tenté » peut donc signifier « plus depuis le redémarrage ». "
                + "L'appariement est tenté quand un joueur traverse l'instance, jamais par un balayage du monde.</p>");
        sb.append(Ui.tableOpen("Waypoint", "Biome", "X", "Z", "Essais", "Borne la plus proche", "État"));
        for (Object o : rows) {
            Map<String, Object> m = asMap(o);
            int attempts = m.get("attempts") instanceof Number n ? n.intValue() : 0;
            int nearest = m.get("nearestBeaconDistance") instanceof Number n ? n.intValue() : -1;
            String state;
            if (Boolean.TRUE.equals(m.get("inProgress"))) {
                state = "<span class=\"badge text-bg-info\">appariement en cours</span>";
            } else if (attempts == 0) {
                state = "<span class=\"badge text-bg-secondary\">jamais tenté</span>";
            } else {
                state = "<span class=\"badge text-bg-warning\">candidats refusés</span>";
            }
            Object nextRetry = m.get("nextRetryEpochMs");
            if (nextRetry instanceof Number n && n.longValue() > System.currentTimeMillis()) {
                state += " <span class=\"muted\">· nouvel essai dans "
                        + ((n.longValue() - System.currentTimeMillis()) / 1000) + " s</span>";
            }
            sb.append("<tr><td><code>").append(Http.esc(str(m.get("waypointId")))).append("</code></td><td>")
                    .append(Http.esc(str(m.get("biomeKey")))).append("</td><td>")
                    .append(Http.esc(str(m.get("x")))).append("</td><td>")
                    .append(Http.esc(str(m.get("z")))).append("</td><td>")
                    .append(attempts).append("</td><td>")
                    .append(nearest < 0 ? "<span class=\"muted\">aucune</span>" : nearest + " blocs")
                    .append("</td><td>").append(state).append("</td></tr>");
        }
        sb.append(Ui.tableClose());
        return sb.toString();
    }

    /** Tableau technique des waypoints (onglet « Avancé ») ; le renommage vit dans la fiche du lieu (#412). */
    private String renderWaypointTable(String agentId, List<Object> rows, int page) {
        if (rows.isEmpty()) {
            return Ui.empty("travel", "Aucun waypoint.");
        }
        int pageCount = Math.max(1, (int) Math.ceil(rows.size() / (double) TRAVEL_PAGE_SIZE));
        int clamped = Math.max(1, Math.min(page, pageCount));
        List<Object> pageItems = rows.subList(Math.min(rows.size(), (clamped - 1) * TRAVEL_PAGE_SIZE),
                Math.min(rows.size(), clamped * TRAVEL_PAGE_SIZE));

        StringBuilder sb = new StringBuilder();
        // Retour joueur 2026-10-04 : « Borne associée » montre LAQUELLE est associée (son id), jamais
        // seulement oui/non, et ce n'est pas un statut de découverte joueur.
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
        sb.append(travelPager(agentId, "wp", clamped, pageCount));
        return sb.toString();
    }

    private String renderBeaconTable(String agentId, List<Object> rows, int page) {
        if (rows.isEmpty()) {
            return Ui.empty("travel", "Aucune borne.");
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
        sb.append(travelPager(agentId, "tb", clamped, pageCount));
        return sb.toString();
    }

    private String travelPager(String agentId, String pageParam, int page, int pageCount) {
        if (pageCount <= 1) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<nav class=\"pager\">");
        String base = "/travel?agent=" + Http.esc(agentId) + "&amp;tab=avance";
        if (page > 1) {
            sb.append("<a class=\"btn secondary sm\" href=\"").append(base).append("&amp;").append(pageParam)
                    .append("=").append(page - 1).append("#tableaux-techniques\">Précédent</a>");
        }
        sb.append("<span class=\"muted\"> Page ").append(page).append(" / ").append(pageCount).append(" </span>");
        if (page < pageCount) {
            sb.append("<a class=\"btn secondary sm\" href=\"").append(base).append("&amp;").append(pageParam)
                    .append("=").append(page + 1).append("#tableaux-techniques\">Suivant</a>");
        }
        sb.append("</nav>");
        return sb.toString();
    }

    // ================================================================================
    //  PNJ — issue #325 : un seul parcours de création, une fiche courte, des formulaires simples.
    //
    //  Règle de rédaction de toute cette zone : le parcours normal parle du personnage (son nom,
    //  son apparence, où il est, ce qu'il fait, ce qu'il dit). Les couches techniques — définition,
    //  liaison, registre Citizens, relevés — n'apparaissent que dans « Informations avancées » et
    //  sur la page de diagnostic. Les protections du moteur, elles, sont toutes conservées : elles
    //  ne sont simplement plus expliquées sous chaque bouton.
    // ================================================================================

    /** Libellé lisible d'un monde. Le nom technique reste visible entre parenthèses. */
    static String worldLabel(String world) {
        String w = world == null ? "" : world.trim();
        String human = switch (w.toLowerCase(Locale.ROOT)) {
            case "world_hub", "hub" -> "Village";
            case "claims" -> "Terrains des joueurs";
            case "wild" -> "Monde sauvage";
            case "world" -> "Monde principal";
            case "world_nether" -> "Nether";
            case "world_the_end" -> "End";
            default -> "";
        };
        return human.isEmpty() ? w : human + " (" + w + ")";
    }

    public String npcs(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("npc", "PNJ", "Les personnages de votre serveur.",
                docLink("pnj-citizens", "Aide : les PNJ")));
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        NpcRights rights = npcRights(session);
        List<String> spawnWorlds = loadedWorldNames(agentId);
        sb.append(agentPicker(agentId, "/npcs", ""));

        // ---- Barre d'outils : une seule actualisation, un seul bouton de création --------------
        sb.append("<div class=\"npc-catbar\">");
        sb.append(npcRefreshAllForm(session, agentId, "/npcs"));
        if (rights.create()) {
            sb.append("<button class=\"btn sm\" type=\"button\" data-bs-toggle=\"collapse\" "
                    + "data-bs-target=\"#npc-create\" aria-expanded=\"false\" aria-controls=\"npc-create\">")
                    .append(Icons.icon("plus")).append("Créer un PNJ</button>");
        }
        sb.append("<a class=\"btn secondary sm ms-auto\" href=\"/npcs/spots\">")
                .append(Icons.icon("travel")).append("Emplacements</a>");
        sb.append("<a class=\"btn secondary sm\" href=\"/npcs/encounters\">")
                .append(Icons.icon("travel")).append("PNJ récurrents</a>");
        sb.append("<a class=\"btn secondary sm\" href=\"/npcs/diagnostic\">")
                .append(Icons.icon("wrench")).append("Diagnostic</a>");
        sb.append("</div>");

        Optional<Map<String, Object>> details = latestDetails(agentId, "npc.list");
        Optional<Map<String, Object>> citizensCat = latestDetails(agentId, "npc.citizens.list");
        List<Object> citizensRoster = citizensCat.map(x -> asList(x.get("citizens"))).orElse(List.of());
        List<Object> npcs = details.map(d -> asList(d.get("npcs"))).orElse(List.of());
        NpcDisplayNames names = NpcDisplayNames.fromSurvey(npcs, citizensRoster);

        if (rights.create()) {
            sb.append("<div class=\"collapse\" id=\"npc-create\"><div class=\"card card-body npc-formcard\">")
                    .append(npcCreateForm(session, agentId, spawnWorlds, names)).append("</div></div>");
        }

        // Créations commencées mais pas terminées : ce qui est fait, ce qui reste, et la reprise.
        sb.append(npcPendingCreations(session, agentId, npcs, rights));

        if (details.isEmpty()) {
            sb.append(Ui.empty("npc", "La liste n'est pas encore chargée : cliquez sur « Actualiser les PNJ »."));
            return sb.toString();
        }
        if (!Boolean.TRUE.equals(details.get().get("citizensAvailable"))) {
            sb.append(Ui.banner("warning", "Le serveur de jeu ne gère pas les PNJ en ce moment (Citizens est "
                    + "inactif) : la liste est incomplète et les PNJ ne peuvent pas être modifiés."));
        }

        List<Map<String, Object>> freeCitizens = new ArrayList<>();
        for (Object o : citizensRoster) {
            Map<String, Object> c = asMap(o);
            String linked = cleanNull(str(c.get("linkedNpcId")));
            // Issue #13 : un PNJ récurrent n'a pas de liaison classique, mais il est géré par
            // RPGQuest — il se trouve sur la page « PNJ récurrents », pas dans les PNJ à rattacher.
            if (linked.isEmpty() && cleanNull(str(c.get("encounterSiteId"))).isEmpty()) {
                freeCitizens.add(c);
            }
        }

        Map<String, String> questTitles = titleIndex(
                latestDetails(agentId, "quest.list").map(x -> asList(x.get("quests"))).orElse(List.of()), "id", "title");
        com.lodygames.rpgquest.panel.content.RefData giverRef = referenceData(agentId);
        Map<String, String> sourceDialogueSlugs = new java.util.HashMap<>();
        for (SourceCatalog.DialogueSource ds : sourceCatalog.dialogues()) {
            sourceDialogueSlugs.put(ds.plainId(), ds.slug());
        }
        Map<String, Integer> dialogueHolders = new java.util.HashMap<>();
        for (Object o : npcs) {
            String dlg = dialoguePlainKey(cleanNull(str(asMap(o).get("dialogueId"))));
            if (!dlg.isEmpty()) {
                dialogueHolders.merge(dlg, 1, Integer::sum);
            }
        }

        // Les lignes affichées : une par personnage. Une entrée qui n'existe que parce qu'une quête
        // ou un dialogue cite un identifiant inconnu n'est pas un personnage : elle relève du
        // diagnostic, pas de la liste.
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object o : npcs) {
            Map<String, Object> n = asMap(o);
            if (Boolean.TRUE.equals(n.get("logicalDefinitionPresent"))
                    || Boolean.TRUE.equals(n.get("citizensBindingPresent"))) {
                rows.add(n);
            }
        }
        int total = rows.size() + freeCitizens.size();
        if (total == 0) {
            sb.append(Ui.empty("npc", "Aucun PNJ pour l'instant. Cliquez sur « Créer un PNJ »."));
            return sb.toString();
        }

        StringBuilder items = new StringBuilder();
        int toFix = 0;
        int i = 0;
        for (Map<String, Object> n : rows) {
            NpcCard card = npcCard(agentId, n, citizensRoster, names);
            if (!card.problems().isEmpty()) {
                toFix++;
            }
            items.append(renderNpcItem(session, agentId, card, i++, questTitles, giverRef,
                    spawnWorlds, rights, sourceDialogueSlugs, dialogueHolders, names));
        }
        int fci = 0;
        for (Map<String, Object> c : freeCitizens) {
            toFix++;
            items.append(renderUnattachedItem(session, agentId, c, fci++, rights));
        }

        sb.append("<p class=\"npc-summary\"><strong>").append(total).append("</strong> PNJ");
        if (toFix > 0) {
            sb.append(" · <span class=\"text-warning-emphasis\">").append(toFix)
                    .append(toFix == 1 ? " à vérifier" : " à vérifier").append("</span>");
        }
        sb.append("</p>");
        sb.append(listControls("npcs", "Rechercher un PNJ…",
                toFix > 0 ? filterBtn("", "Tous", true) + filterBtn("todo", "À vérifier", false) : ""));
        sb.append("<p class=\"count-note\" data-count-note data-noun=\"PNJ\">").append(total).append(" PNJ</p>");
        sb.append("<div class=\"accordion npc-accordion\" id=\"npc-accordion\">").append(items).append("</div>");
        return sb.toString();
    }

    /** Les droits utiles à la page, calculés une fois. */
    private record NpcRights(boolean write, boolean bind, boolean spawn, boolean giver, boolean dialogue,
                             boolean publish, boolean delete) {
        /** « Créer un PNJ » produit une définition, un personnage, une liaison et un dialogue publié. */
        boolean create() {
            return write && bind && spawn && dialogue && publish;
        }

        /** « Rattacher à RPGQuest » : une définition et une liaison. */
        boolean attach() {
            return write && bind;
        }
    }

    private NpcRights npcRights(Session session) {
        return new NpcRights(perms.can(session.effective(), Permission.NPC_WRITE),
                perms.can(session.effective(), Permission.NPC_BIND_WRITE),
                perms.can(session.effective(), Permission.NPC_SPAWN_WRITE),
                perms.can(session.effective(), Permission.QUEST_GIVER_WRITE),
                perms.can(session.effective(), Permission.DIALOGUE_WRITE),
                perms.can(session.effective(), Permission.CONTENT_PUBLISH),
                perms.can(session.effective(), Permission.NPC_DELETE));
    }

    /** « Actualiser les PNJ » : un seul bouton, qui relit le catalogue RPGQuest ET les PNJ en jeu. */
    private String npcRefreshAllForm(Session session, String agentId, String returnPath) {
        return "<form method=\"post\" action=\"/npcs/refresh\" class=\"d-inline\">"
                + "<input type=\"hidden\" name=\"_csrf\" value=\"" + Http.esc(session.csrfToken()) + "\">"
                + "<input type=\"hidden\" name=\"agent\" value=\"" + Http.esc(agentId) + "\">"
                + "<input type=\"hidden\" name=\"return\" value=\"" + Http.esc(returnPath) + "\">"
                + "<button class=\"btn secondary sm\" type=\"submit\">"
                + Icons.icon("refresh") + "Actualiser les PNJ</button></form>";
    }

    // ---- Le modèle d'une fiche ----------------------------------------------------------------

    /** Un problème qui demande une action, dit simplement, avec le formulaire qui le règle. */
    private record NpcProblem(String text, String fixTarget, String fixLabel) {
    }

    /** Tout ce qu'une fiche affiche, calculé une seule fois. */
    private record NpcCard(String id, String name, String nameHtml, String inGameName, boolean hasDefinition,
                           boolean inWorld, boolean enabled, String numeric, Map<String, Object> citizens,
                           NpcAppearance appearance, Map<String, Object> row, List<NpcProblem> problems,
                           List<String> nameSharedWith) {
    }

    private NpcCard npcCard(String agentId, Map<String, Object> n, List<Object> citizensRoster,
                            NpcDisplayNames names) {
        String id = str(n.get("id"));
        String slug = npcSlug(id);
        String displayName = cleanNull(str(n.get("displayName")));
        boolean hasDefinition = Boolean.TRUE.equals(n.get("logicalDefinitionPresent"));
        boolean inWorld = Boolean.TRUE.equals(n.get("citizensBindingPresent"));
        boolean enabled = Boolean.TRUE.equals(n.get("enabled"));
        String numeric = cleanNull(str(n.get("citizensNumericId")));
        Map<String, Object> c = citizensRowOf(citizensRoster, id, numeric);
        String inGame = c == null ? "" : cleanName(c.get("name"));
        String name = !displayName.isEmpty() ? MiniText.plain(displayName)
                : !inGame.isEmpty() ? MiniText.plain(inGame) : MiniText.prettifyId(id);
        String nameHtml = !displayName.isEmpty() ? MiniText.html(displayName)
                : !inGame.isEmpty() ? MiniText.html(inGame) : Http.esc(MiniText.prettifyId(id));
        NpcAppearance appearance = appearanceOf(agentId, id, c, inWorld);

        List<NpcProblem> problems = new ArrayList<>();
        if (hasDefinition && inWorld && !inGame.isEmpty() && !displayName.isEmpty()
                && !MiniText.plain(inGame).equals(MiniText.plain(displayName))) {
            problems.add(new NpcProblem("Le nom affiché en jeu est différent : « " + MiniText.plain(inGame)
                    + " ». Enregistrez le nom pour les remettre d'accord.", slug + "-f-name", "Modifier le nom"));
        }
        // Issue #125 : un nom partagé n'est pas une erreur, mais il faut le voir avant de choisir
        // une fiche par son nom. Les identifiants techniques, eux, restent distincts.
        List<String> nameSharedWith = names.othersSharing(id);
        if (!nameSharedWith.isEmpty()) {
            problems.add(new NpcProblem("Nom affiché utilisé par plusieurs PNJ : « " + name + " » est aussi porté par "
                    + String.join(", ", nameSharedWith) + " (identifiant de celui-ci : " + id + ").",
                    slug + "-f-name", "Modifier le nom"));
        }
        if (hasDefinition && !inWorld && enabled) {
            problems.add(new NpcProblem("Ce PNJ n'est pas encore dans le monde.", slug + "-f-spawn",
                    "Le faire apparaître"));
        }
        if (inWorld && !hasDefinition) {
            problems.add(new NpcProblem("Ce PNJ n'est pas complètement rattaché à RPGQuest.", slug + "-f-attach",
                    "Terminer le rattachement"));
        }
        if (appearance.reason() == NpcAppearance.Reason.NOT_WORN) {
            problems.add(new NpcProblem("Il ne porte pas son apparence en jeu.", slug + "-f-skin",
                    "Réessayer"));
        } else if (appearance.status() == NpcAppearance.Status.ERROR) {
            problems.add(new NpcProblem("La dernière apparence n'a pas pu être appliquée.", slug + "-f-skin",
                    "Choisir une apparence"));
        }
        for (Object w : asList(n.get("warnings"))) {
            Map<String, Object> wm = asMap(w);
            String code = str(wm.get("code"));
            switch (code) {
                case "DIALOGUE_MISSING" -> problems.add(new NpcProblem(
                        "Son dialogue est introuvable sur le serveur : il ne répond pas quand on lui parle.",
                        slug + "-f-talk", "Dialogue et quêtes"));
                case "DUPLICATE_BINDING" -> problems.add(new NpcProblem(
                        "Plusieurs personnages du jeu portent son identité. Voir le diagnostic.", null, null));
                case "DUPLICATE_DEFINITION" -> problems.add(new NpcProblem(
                        "Il est déclaré deux fois. Voir le diagnostic.", null, null));
                default -> {
                    // NOT_LINKED, DISABLED, GIVER_NO_DIALOGUE, NO_DEFINITION… : déjà dits ci-dessus en
                    // termes de personnage, ou purement informatifs — la page de diagnostic les liste.
                }
            }
        }
        return new NpcCard(id, name, nameHtml, inGame, hasDefinition, inWorld, enabled, numeric, c, appearance,
                n, problems, nameSharedWith);
    }

    private static String npcSlug(String id) {
        return "npc-" + id.replaceAll("[^a-z0-9_-]", "-");
    }

    /** Position courte : « Village · 12 / 64 / -30 ». */
    private static String npcPlace(Map<String, Object> c) {
        if (c == null || cleanNull(str(c.get("world"))).isEmpty()) {
            return "";
        }
        String world = str(c.get("world"));
        String label = worldLabel(world);
        int paren = label.indexOf(" (");
        return (paren > 0 ? label.substring(0, paren) : label) + " · " + coord(c.get("x")) + " / "
                + coord(c.get("y")) + " / " + coord(c.get("z"));
    }

    // ---- Une ligne de la liste ----------------------------------------------------------------

    private String renderNpcItem(Session session, String agentId, NpcCard card, int idx,
                                 Map<String, String> questTitles,
                                 com.lodygames.rpgquest.panel.content.RefData giverRef,
                                 List<String> spawnWorlds, NpcRights rights,
                                 Map<String, String> sourceDialogueSlugs, Map<String, Integer> dialogueHolders,
                                 NpcDisplayNames names) {
        String id = card.id();
        String slug = npcSlug(id);
        String collapseId = slug + "-body";
        String cat = card.problems().isEmpty() ? "" : "todo";
        String roleText = card.hasDefinition() ? roleLabel(cleanNull(str(card.row().get("role")))) : "";
        String ftext = Http.esc(card.name() + " " + MiniText.plain(card.inGameName()) + " " + id + " " + roleText);

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"accordion-item npc-item\" data-filter-item=\"npcs\" data-filter-cat=\"")
                .append(cat).append("\" data-filter-text=\"").append(ftext)
                .append("\" data-res-id=\"").append(Http.esc(id)).append("\">");
        sb.append("<h3 class=\"accordion-header\"><button class=\"accordion-button collapsed npc-head\" "
                + "type=\"button\" data-bs-toggle=\"collapse\" data-bs-target=\"#").append(collapseId)
                .append("\" aria-expanded=\"false\" aria-controls=\"").append(collapseId).append("\">");
        sb.append("<span class=\"npc-head-main\"><span class=\"npc-name\">").append(card.nameHtml()).append("</span>");
        if (!roleText.isEmpty()) {
            sb.append("<span class=\"npc-role\">").append(Http.esc(roleText)).append("</span>");
        }
        String place = npcPlace(card.citizens());
        if (!place.isEmpty()) {
            sb.append("<span class=\"npc-loc\">").append(Icons.icon("world")).append(Http.esc(place)).append("</span>");
        }
        sb.append("</span><span class=\"npc-head-badges\">");
        if (!card.problems().isEmpty()) {
            sb.append("<span class=\"badge text-bg-warning\">").append(Icons.icon("warning")).append("à vérifier</span>");
        }
        if (!card.nameSharedWith().isEmpty()) {
            sb.append("<span class=\"badge text-bg-info\" data-dup-name title=\"Même nom que ")
                    .append(Http.esc(String.join(", ", card.nameSharedWith()))).append("\">nom en double</span>");
        }
        if (card.hasDefinition() && !card.enabled()) {
            sb.append("<span class=\"badge text-bg-secondary\">désactivé</span>");
        }
        sb.append("</span></button></h3>");

        sb.append("<div id=\"").append(collapseId).append("\" class=\"accordion-collapse collapse\" ")
                .append("data-bs-parent=\"#npc-accordion\"><div class=\"accordion-body npc-detail\" data-one-form>");

        // ---- En-tête de fiche : nom, apparence, emplacement, état utile ----
        sb.append("<dl class=\"npc-dl npc-summary-dl\">");
        dlRow(sb, "Nom", card.nameHtml());
        if (!roleText.isEmpty()) {
            dlRow(sb, "Rôle", Http.esc(roleText));
        }
        if (card.inWorld()) {
            dlRow(sb, "Apparence", Http.esc(capitalize(card.appearance().label())));
            dlRow(sb, "Emplacement", place.isEmpty() ? "<span class=\"muted\">inconnu</span>" : Http.esc(place));
            String state = npcActivity(card.citizens());
            if (!state.isEmpty()) {
                dlRow(sb, "Activité", Http.esc(state));
            }
        } else {
            dlRow(sb, "Emplacement", "<span class=\"muted\">pas encore dans le monde</span>");
        }
        sb.append("</dl>");

        // ---- Ce qui demande une action ----
        for (NpcProblem p : card.problems()) {
            sb.append("<div class=\"alert alert-warning npc-alert\">").append(Icons.icon("warning"))
                    .append("<div>").append(Http.esc(p.text()));
            if (p.fixTarget() != null) {
                sb.append(" <button class=\"btn secondary sm ms-1\" type=\"button\" data-bs-toggle=\"collapse\" "
                        + "data-bs-target=\"#").append(p.fixTarget()).append("\" aria-controls=\"").append(p.fixTarget())
                        .append("\">").append(Http.esc(p.fixLabel())).append("</button>");
            } else {
                sb.append(" <a href=\"/npcs/diagnostic\">Diagnostic</a>");
            }
            sb.append("</div></div>");
        }

        // ---- Les actions : un bouton par section, un seul formulaire ouvert à la fois ----
        List<String[]> toggles = new ArrayList<>(); // {target, label, icon}
        StringBuilder forms = new StringBuilder();
        if (rights.write() && rights.bind() && (card.hasDefinition() || card.inWorld())) {
            toggles.add(new String[] {slug + "-f-name", "Modifier le nom", "edit"});
            forms.append(npcFormBox(slug + "-f-name", npcRenameForm(session, agentId, card, names)));
        }
        if (rights.bind() && card.inWorld()) {
            toggles.add(new String[] {slug + "-f-skin", "Apparence", "npc"});
            forms.append(npcFormBox(slug + "-f-skin", npcSkinForm(session, agentId, card)));
        }
        if (rights.spawn() && card.inWorld()) {
            toggles.add(new String[] {slug + "-f-move", "Déplacer", "world"});
            forms.append(npcFormBox(slug + "-f-move", npcMoveForm(session, agentId, card, spawnWorlds)));
            toggles.add(new String[] {slug + "-f-wander", "Promenade", "world"});
            forms.append(npcFormBox(slug + "-f-wander", npcWanderForm(session, agentId, card)));
        }
        if (rights.bind() && card.inWorld()) {
            toggles.add(new String[] {slug + "-f-look", "Regarder les joueurs", "eye"});
            forms.append(npcFormBox(slug + "-f-look", npcLookForm(session, agentId, card)));
        }
        if (rights.spawn() && card.hasDefinition() && !card.inWorld() && card.enabled()) {
            toggles.add(new String[] {slug + "-f-spawn", "Faire apparaître", "plus"});
            forms.append(npcFormBox(slug + "-f-spawn", npcSpawnForm(session, agentId, card, spawnWorlds)));
        }
        if (rights.attach() && card.inWorld() && !card.hasDefinition()) {
            forms.append(npcFormBox(slug + "-f-attach", npcFinishAttachForm(session, agentId, card)));
        }
        toggles.add(new String[] {slug + "-f-talk", "Dialogue et quêtes", "book"});
        forms.append(npcFormBox(slug + "-f-talk", npcTalkSection(session, agentId, card, questTitles, giverRef,
                rights, sourceDialogueSlugs, dialogueHolders)));

        sb.append("<div class=\"npc-actions d-flex flex-wrap gap-2\">");
        for (String[] t : toggles) {
            sb.append(actionToggle(t[0], t[1], t[2], "secondary sm"));
        }
        if (rights.delete()) {
            sb.append("<a class=\"btn secondary sm npc-danger\" href=\"/npcs/delete?npc=")
                    .append(Http.esc(id)).append("\">").append(Icons.icon("warning")).append("Supprimer</a>");
        }
        sb.append("</div>");
        sb.append(forms);

        // ---- Informations avancées : l'identifiant et la plomberie, repliés ----
        sb.append(npcAdvanced(session, agentId, card, rights));

        sb.append("</div></div></div>");
        return sb.toString();
    }

    /** Un formulaire d'action, replié. Tous ceux d'une fiche partagent un même groupe (un seul ouvert). */
    private static String npcFormBox(String id, String inner) {
        return "<div class=\"collapse npc-form-collapse\" id=\"" + id + "\"><div class=\"card card-body npc-formcard\">"
                + inner + "</div></div>";
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** « Se promène · regarde les joueurs », ou vide s'il ne fait rien de particulier. */
    private static String npcActivity(Map<String, Object> c) {
        if (c == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (Boolean.TRUE.equals(c.get("wanderEnabled"))) {
            parts.add("se promène");
        }
        if (Boolean.TRUE.equals(c.get("lookCloseEnabled"))) {
            parts.add("regarde les joueurs");
        }
        if (!Boolean.TRUE.equals(c.get("spawned")) && Boolean.FALSE.equals(c.get("shouldSpawn"))) {
            parts.add("caché en jeu");
        }
        return capitalize(String.join(" · ", parts));
    }

    /** Hidden {@code npc_id} + {@code npc_ctx} (garde de contexte #89) et la fiche à rouvrir. */
    private static String npcHidden(String npcId) {
        return "<input type=\"hidden\" name=\"npc_id\" value=\"" + Http.esc(npcId) + "\">"
                + "<input type=\"hidden\" name=\"npc_ctx\" value=\"" + Http.esc(npcId) + "\">"
                + "<input type=\"hidden\" name=\"focus\" value=\"" + Http.esc(npcId) + "\">";
    }

    /** Résultat de la dernière action de ce type pour CE PNJ, en une ligne courte et datée. */
    private String npcLastResult(String agentId, String type, String npcId) {
        for (com.lodygames.rpgquest.panel.agent.AgentActionRow row : store.recentActions(agentId, 200)) {
            if (!type.equals(row.type()) || !npcId.equalsIgnoreCase(row.params().getOrDefault("npc_id", ""))) {
                continue;
            }
            String when = row.completedAt() != null ? ActionView.relativeTime(row.completedAt(), Instant.now())
                    : ActionView.relativeTime(row.createdAt(), Instant.now());
            String text = !row.status().terminal() ? "En cours…"
                    : row.resultMessage() == null ? row.status().name() : row.resultMessage();
            return "<p class=\"resline\">" + Ui.actionStatus(row.status()) + " "
                    + Http.esc(MiniText.prettifyTokens(text)) + " <span class=\"faint\">" + Http.esc(when)
                    + "</span></p>";
        }
        return "";
    }

    // ---- Modifier le nom ----------------------------------------------------------------------

    private String npcRenameForm(Session session, String agentId, NpcCard card, NpcDisplayNames names) {
        String uid = npcSlug(card.id()) + "-name-in";
        String current = MiniText.plain(card.hasDefinition() ? str(card.row().get("displayName")) : card.inGameName());
        if ("null".equals(current)) {
            current = "";
        }
        StringBuilder sb = new StringBuilder(formStart(session, agentId, "npc.identity.rename", "/npcs", "")
                .replaceFirst("<form ", "<form data-submit-once "));
        sb.append(npcHidden(card.id()));
        sb.append("<label for=\"").append(uid).append("\">Nom</label>");
        sb.append("<div class=\"input-counter\"><input class=\"form-control\" type=\"text\" id=\"").append(uid)
                .append("\" name=\"name\" maxlength=\"").append(NpcCreationPlanner.MAX_NAME)
                .append("\" required autocomplete=\"off\" data-charcount")
                .append(nameTakenAttrs(uid, names, card.id()))
                .append(" value=\"").append(Http.esc(current)).append("\">")
                .append(charCounter(uid, current.length())).append("</div>");
        sb.append(nameTakenNote(uid, names.holdersOf(current, card.id())));
        if (card.appearance().renameAppliesDefault()) {
            sb.append("<p class=\"form-text\">Il prendra l'apparence par défaut, sauf si vous lui en "
                    + "choisissez une autre avant.</p>");
        } else if (card.appearance().blocksRename()) {
            sb.append("<p class=\"form-text\">Actualisez les PNJ avant de le renommer.</p>");
        }
        sb.append(mutationConsent("npc.identity.rename", "Confirmer le nouveau nom", null));
        sb.append("<button class=\"btn\" type=\"submit\" data-busy-label=\"Enregistrement…\">")
                .append("Enregistrer le nom</button></form>");
        sb.append(npcLastResult(agentId, "npc.identity.rename", card.id()));
        return sb.toString();
    }

    /**
     * Issue #125 — l'avertissement « ce nom est déjà porté », avant d'enregistrer. Le champ porte
     * l'index des noms ({@code nom comparé → identifiants}) ; panel.js ({@code data-name-taken})
     * compare la saisie et affiche la note. Jamais un refus : deux PNJ peuvent s'appeler pareil.
     *
     * @param exceptId le PNJ qu'on renomme — son propre nom ne le concerne pas ; vide à la création
     */
    private static String nameTakenAttrs(String fieldId, NpcDisplayNames names, String exceptId) {
        Map<String, Object> index = new LinkedHashMap<>();
        names.index().forEach((key, ids) -> {
            List<String> others = ids.stream().filter(i -> !i.equalsIgnoreCase(exceptId)).toList();
            if (!others.isEmpty()) {
                index.put(key, others);
            }
        });
        return " data-name-taken=\"" + Http.esc(Json.write(index)) + "\" data-name-taken-note=\""
                + fieldId + "-taken\"";
    }

    /** La note, rendue par le serveur quand le nom actuel est déjà partagé, cachée sinon. */
    private static String nameTakenNote(String fieldId, List<String> holders) {
        return "<p class=\"form-text text-warning-emphasis\" id=\"" + fieldId + "-taken\" role=\"status\""
                + (holders.isEmpty() ? " hidden" : "") + ">" + Icons.icon("warning")
                + "<span class=\"name-taken-text\">Nom affiché déjà utilisé par : "
                + Http.esc(String.join(", ", holders)) + ".</span> Vous pouvez enregistrer quand même : "
                + "les identifiants restent distincts, mais les deux PNJ seront difficiles à distinguer.</p>";
    }

    /** Compteur « 0/48 » mis à jour pendant la saisie (composant {@code data-charcount} de panel.js). */
    private static String charCounter(String fieldId, int initial) {
        return "<span class=\"char-count\" data-charcount-for=\"" + fieldId + "\" data-charcount-max=\""
                + NpcCreationPlanner.MAX_NAME + "\"><span class=\"cc-n\">" + initial + "</span>/"
                + NpcCreationPlanner.MAX_NAME + "</span>";
    }

    // ---- Apparence ----------------------------------------------------------------------------

    private String npcSkinForm(Session session, String agentId, NpcCard card) {
        String uid = npcSlug(card.id()) + "-skin";
        StringBuilder sb = new StringBuilder();
        sb.append("<p class=\"form-text\">").append(Http.esc(card.appearance().explanation())).append("</p>");
        if (card.appearance().reason() == NpcAppearance.Reason.NON_PLAYER) {
            return sb.toString();
        }
        String recorded = card.citizens() == null ? ""
                : "URL".equalsIgnoreCase(str(card.citizens().get("skinSourceKind")))
                        ? cleanNull(str(card.citizens().get("skinSourceValue"))) : "";
        if (card.appearance().status() == NpcAppearance.Status.ERROR && recorded.startsWith("https://")) {
            // Issue #325 : réessayer la même apparence en un clic, sans recopier le lien.
            sb.append(formStart(session, agentId, "npc.citizens.skin", "/npcs", "")
                    .replaceFirst("<form ", "<form data-submit-once class=\"npc-retry\" "));
            sb.append(npcHidden(card.id()));
            sb.append("<input type=\"hidden\" name=\"skin_source\" value=\"url\">");
            sb.append("<input type=\"hidden\" name=\"skin_url\" value=\"").append(Http.esc(recorded)).append("\">");
            sb.append(mutationConsent("npc.citizens.skin", "Confirmer l'apparence", null));
            sb.append("<button class=\"btn\" type=\"submit\" data-busy-label=\"Application…\">Réessayer</button></form>");
            sb.append("<p class=\"form-text\">Ou choisissez une autre apparence :</p>");
        }
        sb.append(formStart(session, agentId, "npc.citizens.skin", "/npcs", "")
                .replaceFirst("<form ", "<form data-submit-once "));
        sb.append(npcHidden(card.id()));
        sb.append(skinChoiceFields(uid, false));
        sb.append(mutationConsent("npc.citizens.skin", "Confirmer l'apparence", null));
        sb.append("<button class=\"btn\" type=\"submit\" data-busy-label=\"Envoi…\">Appliquer</button></form>");
        sb.append(npcLastResult(agentId, "npc.citizens.skin", card.id()));
        return sb.toString();
    }

    /**
     * Le choix d'apparence, partagé par la création et la fiche : « Lien MineSkin » par défaut, et
     * un seul champ visible — celui de la source choisie ({@code data-switch} dans panel.js).
     *
     * @param withDefault {@code true} à la création : la première option est l'apparence par défaut
     */
    private static String skinChoiceFields(String uid, boolean withDefault) {
        StringBuilder sb = new StringBuilder();
        sb.append("<label for=\"").append(uid).append("-src\">").append(withDefault ? "Apparence" : "Source")
                .append("</label>");
        sb.append("<select class=\"form-select\" id=\"").append(uid).append("-src\" name=\"skin_source\" ")
                .append("data-switch=\"").append(uid).append("\">");
        if (withDefault) {
            sb.append("<option value=\"default\" selected>Apparence par défaut</option>");
        }
        sb.append("<option value=\"url\"").append(withDefault ? "" : " selected").append(">Lien MineSkin</option>")
                .append("<option value=\"player\">Pseudo Minecraft</option></select>");
        sb.append("<div data-switch-for=\"").append(uid).append("\" data-switch-value=\"url\"")
                .append(withDefault ? " hidden" : "").append(">");
        sb.append("<label for=\"").append(uid).append("-url\">Lien du skin</label>");
        sb.append("<input class=\"form-control\" id=\"").append(uid).append("-url\" type=\"text\" name=\"skin_url\" ")
                .append("inputmode=\"url\" autocomplete=\"off\" placeholder=\"https://minesk.in/…\"")
                .append(withDefault ? " disabled" : " required").append(">");
        sb.append("<p class=\"form-text\"><a href=\"").append(AgentActionCatalog.MINESKIN_CATALOG_URL)
                .append("\" target=\"_blank\" rel=\"noopener noreferrer\">Catalogue MineSkin</a> — copiez le lien "
                        + "d'un skin.</p>");
        sb.append("</div>");
        sb.append("<div data-switch-for=\"").append(uid).append("\" data-switch-value=\"player\" hidden>");
        sb.append("<label for=\"").append(uid).append("-player\">Pseudo Minecraft</label>");
        sb.append("<input class=\"form-control\" id=\"").append(uid).append("-player\" type=\"text\" ")
                .append("name=\"skin_player\" maxlength=\"16\" pattern=\"[A-Za-z0-9_]{3,16}\" autocomplete=\"off\" disabled>");
        sb.append("<p class=\"form-text\">Le PNJ prend le skin de ce joueur.</p>");
        sb.append("</div>");
        return sb.toString();
    }

    // ---- Déplacer -----------------------------------------------------------------------------

    private String npcMoveForm(Session session, String agentId, NpcCard card, List<String> spawnWorlds) {
        String uid = npcSlug(card.id()) + "-move";
        Map<String, Object> c = card.citizens();
        String curWorld = c == null ? "" : cleanNull(str(c.get("world")));
        StringBuilder sb = new StringBuilder(formStart(session, agentId, "npc.citizens.move", "/npcs", "")
                .replaceFirst("<form ", "<form data-submit-once "));
        sb.append(npcHidden(card.id()));
        sb.append(worldSelect(uid, curWorld, spawnWorlds, false));
        sb.append(xyzFields(uid, c, true));
        sb.append("<div class=\"form-grid2\">");
        sb.append("<div><label for=\"").append(uid).append("-facing\">Regarde vers</label>")
                .append("<select class=\"form-select\" id=\"").append(uid).append("-facing\" name=\"facing\">")
                .append("<option value=\"\" selected>Sans changement</option>")
                .append("<option value=\"north\">Le nord</option><option value=\"south\">Le sud</option>")
                .append("<option value=\"east\">L'est</option><option value=\"west\">L'ouest</option>")
                .append("</select></div>");
        sb.append("<div><label for=\"").append(uid).append("-tilt\">Tête</label>")
                .append("<select class=\"form-select\" id=\"").append(uid).append("-tilt\" name=\"tilt\">")
                .append("<option value=\"\" selected>Sans changement</option>")
                .append("<option value=\"level\">Horizontale</option><option value=\"up\">Vers le haut</option>")
                .append("<option value=\"down\">Vers le bas</option></select></div>");
        sb.append("</div>");
        sb.append("<details class=\"npc-more\"><summary>Angles précis</summary><div class=\"form-grid2\">");
        sb.append("<div><label for=\"").append(uid).append("-yaw\">Rotation (°)</label>")
                .append("<input class=\"form-control\" id=\"").append(uid)
                .append("-yaw\" type=\"number\" step=\"1\" min=\"-180\" max=\"360\" name=\"yaw\"></div>");
        sb.append("<div><label for=\"").append(uid).append("-pitch\">Inclinaison (°)</label>")
                .append("<input class=\"form-control\" id=\"").append(uid)
                .append("-pitch\" type=\"number\" step=\"1\" min=\"-90\" max=\"90\" name=\"pitch\"></div>");
        sb.append("</div><p class=\"form-text\">Rotation : 0 = sud, 90 = ouest, 180 = nord, -90 = est. "
                + "Inclinaison : -90 = haut, 90 = bas.</p></details>");
        sb.append(mutationConsent("npc.citizens.move", "Confirmer le déplacement", null));
        sb.append("<button class=\"btn\" type=\"submit\" data-busy-label=\"Déplacement…\">Déplacer</button>");
        sb.append("</form>");
        sb.append(npcLastResult(agentId, "npc.citizens.move", card.id()));
        return sb.toString();
    }

    /** Liste des mondes, libellés lisibles. {@code withAuto} ajoute « près du Guide » en tête. */
    private static String worldSelect(String uid, String current, List<String> worlds, boolean required) {
        StringBuilder sb = new StringBuilder("<label for=\"").append(uid).append("-world\">Monde</label>");
        if (worlds.isEmpty() && current.isEmpty()) {
            return sb.append("<input class=\"form-control\" id=\"").append(uid)
                    .append("-world\" type=\"text\" name=\"world\" pattern=\"[A-Za-z0-9_./-]{1,64}\"")
                    .append(required ? " required" : "").append(">").toString();
        }
        sb.append("<select class=\"form-select\" id=\"").append(uid).append("-world\" name=\"world\"")
                .append(required ? " required" : "").append(">");
        boolean known = false;
        for (String w : worlds) {
            boolean sel = w.equalsIgnoreCase(current);
            known |= sel;
            sb.append("<option value=\"").append(Http.esc(w)).append("\"").append(sel ? " selected" : "")
                    .append(">").append(Http.esc(worldLabel(w))).append("</option>");
        }
        if (!known && !current.isEmpty()) {
            sb.append("<option value=\"").append(Http.esc(current)).append("\" selected>")
                    .append(Http.esc(worldLabel(current))).append("</option>");
        }
        return sb.append("</select>").toString();
    }

    /** X / Y / Z, préremplis depuis la position connue si {@code prefill}. */
    private static String xyzFields(String uid, Map<String, Object> c, boolean prefill) {
        StringBuilder sb = new StringBuilder("<div class=\"form-grid3\">");
        for (String[] axis : new String[][] {{"x", "X"}, {"y", "Y"}, {"z", "Z"}}) {
            String value = prefill && c != null ? coord(c.get(axis[0])) : "";
            sb.append("<div><label for=\"").append(uid).append("-").append(axis[0]).append("\">")
                    .append(axis[1]).append("</label><input class=\"form-control\" id=\"").append(uid).append("-")
                    .append(axis[0]).append("\" type=\"number\" step=\"0.5\" name=\"").append(axis[0])
                    .append("\" value=\"").append(Http.esc(value)).append("\" required></div>");
        }
        return sb.append("</div>").toString();
    }

    // ---- Promenade ----------------------------------------------------------------------------

    private String npcWanderForm(Session session, String agentId, NpcCard card) {
        String uid = npcSlug(card.id()) + "-wander";
        Map<String, Object> c = card.citizens();
        boolean active = c != null && Boolean.TRUE.equals(c.get("wanderEnabled"));
        StringBuilder sb = new StringBuilder();
        if (active) {
            sb.append("<p class=\"form-text\">Le PNJ se promène autour de sa position de départ.</p>");
            sb.append(formStart(session, agentId, "npc.citizens.wander", "/npcs", "")
                    .replaceFirst("<form ", "<form data-submit-once "));
            sb.append(npcHidden(card.id()));
            sb.append("<input type=\"hidden\" name=\"enabled\" value=\"false\">")
                    .append("<input type=\"hidden\" name=\"return_home\" value=\"true\">")
                    .append("<input type=\"hidden\" name=\"confirm\" value=\"true\">");
            sb.append("<button class=\"btn\" type=\"submit\" data-busy-label=\"Retour…\">")
                    .append("Arrêter la promenade et revenir au départ</button></form>");
            sb.append(npcLastResult(agentId, "npc.citizens.wander", card.id()));
            return sb.toString();
        }
        String provider = c == null ? "" : cleanNull(str(c.get("wanderProvider")));
        String points = c == null ? "" : cleanNull(str(c.get("wanderWaypoints")));
        boolean conflict = (!provider.isEmpty() && !"linear".equalsIgnoreCase(provider))
                || (!points.isEmpty() && !"0".equals(points));
        String xr = c == null ? "" : cleanNull(str(c.get("wanderXRange")));
        String yr = c == null ? "" : cleanNull(str(c.get("wanderYRange")));
        int xRange = parseIntOr(xr, 12);
        int yRange = parseIntOr(yr, 2);
        // 25 × 3 est la valeur que Citizens rapporte pour un PNJ jamais configuré : trop large pour
        // une place de village, on propose 12 × 2.
        if (xRange > 64 || xRange < 1 || (xRange == 25 && yRange == 3)) {
            xRange = 12;
            yRange = 2;
        }
        if (yRange > 32 || yRange < 0) {
            yRange = 2;
        }

        sb.append(formStart(session, agentId, "npc.citizens.wander", "/npcs", "")
                .replaceFirst("<form ", "<form data-submit-once "));
        sb.append(npcHidden(card.id()));
        sb.append("<input type=\"hidden\" name=\"enabled\" value=\"true\">");
        sb.append("<label class=\"inline npc-check\"><input type=\"checkbox\" "
                + "name=\"confirm\" value=\"true\" required> <span>Activer la promenade</span></label>");
        sb.append("<label for=\"").append(uid).append("-xr\">Rayon (blocs)</label>")
                .append("<input class=\"form-control\" id=\"").append(uid).append("-xr\" type=\"number\" ")
                .append("step=\"1\" min=\"1\" max=\"64\" name=\"x_range\" value=\"").append(xRange).append("\" required>")
                .append("<p class=\"form-text\">Distance maximale autour de sa position de départ.</p>");
        sb.append("<label for=\"").append(uid).append("-yr\">Amplitude verticale (blocs)</label>")
                .append("<input class=\"form-control\" id=\"").append(uid).append("-yr\" type=\"number\" ")
                .append("step=\"1\" min=\"0\" max=\"32\" name=\"y_range\" value=\"").append(yRange).append("\" required>")
                .append("<p class=\"form-text\">Écart de hauteur autorisé autour de sa position de départ.</p>");
        if (conflict) {
            sb.append("<label class=\"inline npc-check\"><input type=\"checkbox\" "
                    + "name=\"confirm_replace\" value=\"true\" required> <span>Remplacer le trajet qu'il suit "
                    + "déjà</span></label>");
        }
        sb.append("<button class=\"btn\" type=\"submit\" data-busy-label=\"Envoi…\">Confirmer</button>");
        sb.append("</form>");
        sb.append(npcLastResult(agentId, "npc.citizens.wander", card.id()));
        return sb.toString();
    }

    private static int parseIntOr(String raw, int fallback) {
        try {
            return (int) Math.round(Double.parseDouble(raw));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // ---- Regarder les joueurs -----------------------------------------------------------------

    private String npcLookForm(Session session, String agentId, NpcCard card) {
        String uid = npcSlug(card.id()) + "-look";
        Map<String, Object> c = card.citizens();
        boolean on = c != null && Boolean.TRUE.equals(c.get("lookCloseEnabled"));
        String range = c == null ? "" : coord(c.get("lookCloseRange"));
        int r = parseIntOr(range, 10);
        if (r < 1 || r > 64) {
            r = Math.max(1, Math.min(64, r));
        }
        StringBuilder sb = new StringBuilder(formStart(session, agentId, "npc.citizens.lookclose", "/npcs", "")
                .replaceFirst("<form ", "<form data-submit-once "));
        sb.append(npcHidden(card.id()));
        // L'état est porté par la case : cochée = « true », décochée = « false ». Recliquer
        // « Confirmer » réenregistre le même état, jamais l'inverse.
        sb.append("<input type=\"hidden\" name=\"state_form\" value=\"1\">");
        sb.append("<label class=\"inline npc-check\"><input type=\"checkbox\" "
                + "name=\"state\" value=\"on\"").append(on ? " checked" : "").append("> <span>Regarder les joueurs</span></label>");
        sb.append("<label for=\"").append(uid).append("-range\">Portée (blocs)</label>")
                .append("<input class=\"form-control\" id=\"").append(uid).append("-range\" type=\"number\" ")
                .append("step=\"1\" min=\"1\" max=\"64\" name=\"range\" value=\"").append(r).append("\" required>");
        sb.append("<p class=\"form-text\">Le PNJ regarde le joueur le plus proche dans cette distance.</p>");
        sb.append(mutationConsent("npc.citizens.lookclose", "Confirmer", null));
        sb.append("<button class=\"btn\" type=\"submit\" data-busy-label=\"Envoi…\">Confirmer</button></form>");
        sb.append(npcLastResult(agentId, "npc.citizens.lookclose", card.id()));
        return sb.toString();
    }

    // ---- Faire apparaître (définition sans personnage en jeu) ---------------------------------

    private String npcSpawnForm(Session session, String agentId, NpcCard card, List<String> spawnWorlds) {
        String uid = npcSlug(card.id()) + "-spawn";
        StringBuilder sb = new StringBuilder(formStart(session, agentId, "npc.citizens.create", "/npcs", "")
                .replaceFirst("<form ", "<form data-submit-once "));
        sb.append(npcHidden(card.id()));
        sb.append(worldSelect(uid, "", spawnWorlds, true));
        sb.append(xyzFields(uid, null, false));
        sb.append(mutationConsent("npc.citizens.create", "Confirmer l'apparition", null));
        sb.append("<button class=\"btn\" type=\"submit\" data-busy-label=\"Envoi…\">Faire apparaître</button>");
        sb.append("</form>");
        sb.append(npcLastResult(agentId, "npc.citizens.create", card.id()));
        return sb.toString();
    }

    /** PNJ en jeu, lié, mais sans définition : on crée seulement la définition manquante. */
    private String npcFinishAttachForm(Session session, String agentId, NpcCard card) {
        StringBuilder sb = new StringBuilder(formStart(session, agentId, "npc.definition.create", "/npcs", "")
                .replaceFirst("<form ", "<form data-submit-once "));
        sb.append(npcHidden(card.id()));
        sb.append("<input type=\"hidden\" name=\"display_name\" value=\"")
                .append(Http.esc(card.inGameName().isEmpty() ? card.name() : card.inGameName())).append("\">");
        sb.append("<input type=\"hidden\" name=\"enabled\" value=\"true\">");
        sb.append("<p class=\"form-text\">Ses dialogues et ses quêtes sont conservés.</p>");
        sb.append(mutationConsent("npc.definition.create", "Confirmer", null));
        sb.append("<button class=\"btn\" type=\"submit\">Terminer le rattachement</button></form>");
        return sb.toString();
    }

    // ---- Dialogue et quêtes -------------------------------------------------------------------

    private String npcTalkSection(Session session, String agentId, NpcCard card, Map<String, String> questTitles,
                                  com.lodygames.rpgquest.panel.content.RefData giverRef, NpcRights rights,
                                  Map<String, String> sourceDialogueSlugs, Map<String, Integer> dialogueHolders) {
        Map<String, Object> n = card.row();
        String id = card.id();
        String dialogueId = cleanNull(str(n.get("dialogueId")));
        String declared = cleanNull(str(n.get("definedDialogueId")));
        String shown = !dialogueId.isEmpty() ? dialogueId : declared;
        String plain = dialoguePlainKey(shown);
        StringBuilder sb = new StringBuilder();
        sb.append("<dl class=\"npc-dl\">");
        if (shown.isEmpty()) {
            dlRow(sb, "Dialogue", "<span class=\"muted\">aucun — il ne dit rien quand on lui parle</span>");
        } else {
            int holders = dialogueHolders.getOrDefault(plain, 1);
            dlRow(sb, "Dialogue", Http.esc(MiniText.prettifyId(plain))
                    + (holders > 1 ? " <span class=\"badge text-bg-secondary\">partagé avec "
                            + (holders - 1) + " autre(s) PNJ</span>" : "")
                    + (dialogueId.isEmpty() ? " <span class=\"faint\">(pas encore sur le serveur)</span>" : ""));
        }
        List<Object> given = asList(n.get("questsGiven"));
        if (!given.isEmpty()) {
            dlRow(sb, "Quêtes proposées", npcQuestLinks(given, questTitles));
        }
        List<Object> starts = asList(n.get("dialogueStartsQuests"));
        if (!starts.isEmpty()) {
            dlRow(sb, "Son dialogue lance", npcQuestLinks(starts, questTitles));
        }
        List<Object> referenced = asList(n.get("questsReferenced"));
        if (!referenced.isEmpty()) {
            dlRow(sb, "Quêtes où il faut lui parler", npcQuestLinks(referenced, questTitles));
        }
        sb.append("</dl>");

        sb.append("<div class=\"d-flex flex-wrap gap-2\">");
        if (!plain.isEmpty() && sourceDialogueSlugs.containsKey(plain) && rights.dialogue()) {
            sb.append("<a class=\"btn sm\" href=\"/dialogues/edit/")
                    .append(Http.esc(sourceDialogueSlugs.get(plain))).append("?npc=").append(Http.esc(id)).append("\">")
                    .append(Icons.icon("edit")).append("Modifier le dialogue</a>");
        } else if (!plain.isEmpty()) {
            sb.append("<a class=\"btn secondary sm\" href=\"/dialogues/edit/")
                    .append(Http.esc(plain)).append("\">").append(Icons.icon("open")).append("Voir le dialogue</a>");
        } else if (rights.dialogue()) {
            sb.append("<a class=\"btn sm\" href=\"/dialogues/new?npc=").append(Http.esc(id))
                    .append("\">").append(Icons.icon("plus")).append("Créer son dialogue</a>");
        }
        if (perms.can(session.effective(), Permission.QUEST_CONTENT_WRITE) && card.hasDefinition()) {
            sb.append("<a class=\"btn secondary sm\" href=\"/quests/new?giver=").append(Http.esc(id))
                    .append("\">").append(Icons.icon("gift")).append("Créer une quête qu'il propose</a>");
        }
        sb.append("</div>");
        if (rights.giver() && card.hasDefinition()) {
            sb.append("<details class=\"npc-more\"><summary>Lui faire proposer une quête existante</summary>")
                    .append(giverForm(session, agentId, id, giverRef, npcSlug(id))).append("</details>");
        }
        return sb.toString();
    }

    /** Quêtes en liens titrés, sans identifiant technique (fiche PNJ, #325). */
    private static String npcQuestLinks(List<Object> ids, Map<String, String> titles) {
        List<String> out = new ArrayList<>();
        for (Object o : ids) {
            String id = str(o);
            String plain = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            String title = titles.getOrDefault(id, titles.getOrDefault("rpgquest:" + plain, ""));
            out.add("<a href=\"/quests?q=" + Http.esc(plain) + "\">"
                    + (title.isEmpty() ? Http.esc(MiniText.prettifyId(plain)) : MiniText.html(title)) + "</a>");
        }
        return String.join(" · ", out);
    }

    /**
     * « Lui faire proposer une quête existante ». La liste est cherchable par titre ou identifiant,
     * alimentée par les quêtes de la source et du serveur (#164). Le serveur refuse une quête qu'il
     * n'a pas encore chargée : on le dit en une phrase, avec le chemin qui marche.
     */
    private String giverForm(Session session, String agentId, String npcId,
                             com.lodygames.rpgquest.panel.content.RefData ref, String domId) {
        List<String> quests = ref == null ? List.of() : ref.quests();
        if (quests.isEmpty()) {
            return Ui.empty("Aucune quête pour l'instant.");
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
        StringBuilder sb = new StringBuilder(formStart(session, agentId, "quest.giver.set", "/npcs", ""));
        sb.append(npcHidden(npcId));
        sb.append("<label for=\"giver-q-").append(domId).append("\">Quête</label>");
        sb.append(dl);
        sb.append("<div class=\"combo\" data-combo><input class=\"combo-input form-control\" type=\"text\" ")
                .append("id=\"giver-q-").append(domId).append("\" name=\"quest_id\" list=\"").append(dlId)
                .append("\" autocomplete=\"off\" role=\"combobox\" aria-expanded=\"false\" ")
                .append("aria-autocomplete=\"list\" placeholder=\"Titre ou identifiant…\" ")
                .append("pattern=\"[A-Za-z0-9_.:/-]{1,96}\" required></div>");
        if (anySourceOnly) {
            sb.append("<p class=\"form-text\">Une quête pas encore publiée se choisit plutôt dans "
                    + "<a href=\"/quests\">l'éditeur de quête</a> (champ « PNJ donneur »).</p>");
        }
        sb.append(mutationConsent("quest.giver.set", "Confirmer", null));
        sb.append("<button class=\"btn sm\" type=\"submit\">Proposer cette quête</button></form>");
        return sb.toString();
    }

    // ---- Informations avancées ----------------------------------------------------------------

    private String npcAdvanced(Session session, String agentId, NpcCard card, NpcRights rights) {
        Map<String, Object> n = card.row();
        StringBuilder sb = new StringBuilder("<details class=\"npc-more npc-advanced\"><summary>Informations avancées</summary>");
        sb.append("<dl class=\"npc-dl\">");
        dlRow(sb, "Identifiant", Ui.id(card.id()));
        if (card.inWorld()) {
            dlRow(sb, "Personnage Citizens", card.numeric().isEmpty() ? "lié" : "#" + Http.esc(card.numeric()));
            String type = card.citizens() == null ? "" : cleanNull(str(card.citizens().get("entityType")));
            if (!type.isEmpty()) {
                dlRow(sb, "Type", Http.esc(MinecraftNames.humanize(type)));
            }
            if (!card.appearance().sourceLabel().isEmpty()) {
                dlRow(sb, "Source de l'apparence", Http.esc(card.appearance().sourceLabel()));
            }
            Map<String, Object> c = card.citizens();
            if (c != null && Boolean.TRUE.equals(c.get("wanderEnabled")) && !cleanNull(str(c.get("wanderWorld"))).isEmpty()) {
                dlRow(sb, "Départ de la promenade", Http.esc(worldLabel(str(c.get("wanderWorld")))) + " · "
                        + coord(c.get("wanderX")) + " / " + coord(c.get("wanderY")) + " / " + coord(c.get("wanderZ"))
                        + " · rayon " + Http.esc(cleanNull(str(c.get("wanderXRange")))));
            }
        }
        String desc = cleanNull(str(n.get("description")));
        if (!desc.isEmpty()) {
            dlRow(sb, "Description", Http.esc(desc));
        }
        sb.append("</dl>");
        sb.append("<p class=\"form-text\"><a href=\"/docs/pnj-citizens#peut-on-changer-lidentifiant-dun-pnj\">"
                + "Peut-on changer l'identifiant d'un PNJ ?</a></p>");
        if (rights.write() && card.hasDefinition()) {
            String slug = npcSlug(card.id());
            sb.append(actionToggle(slug + "-f-settings", "Réglages avancés", "wrench", "secondary sm"));
            sb.append(npcFormBox(slug + "-f-settings", npcSettingsForm(session, agentId, card)));
        }
        return sb.append("</details>").toString();
    }

    /** Réglages rares : dialogue déclaré, rôle, actif. Le nom ne se change pas ici. */
    private String npcSettingsForm(Session session, String agentId, NpcCard card) {
        Map<String, Object> n = card.row();
        String uid = npcSlug(card.id()) + "-set";
        StringBuilder sb = new StringBuilder(formStart(session, agentId, "npc.definition.update", "/npcs", ""));
        sb.append(npcHidden(card.id()));
        sb.append("<input type=\"hidden\" name=\"display_name\" value=\"")
                .append(Http.esc(cleanNull(str(n.get("displayName"))))).append("\">");
        sb.append("<label for=\"").append(uid).append("-dlg\">Dialogue</label>")
                .append(dlgSelect(uid + "-dlg", cleanNull(str(n.get("definedDialogueId"))),
                        dialogueSelectOptions(agentId)));
        sb.append(npcRoleField(uid, cleanNull(str(n.get("role")))));
        sb.append("<label class=\"inline npc-check\"><input type=\"checkbox\" "
                + "name=\"enabled\" value=\"true\"").append(card.enabled() ? " checked" : "")
                .append("> <span>PNJ actif</span></label>");
        sb.append(mutationConsent("npc.definition.update", "Confirmer", null));
        sb.append("<button class=\"btn sm\" type=\"submit\">Enregistrer</button></form>");
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
                    .append(Http.esc(MiniText.prettifyId(current))).append("</option>");
        }
        return sb.append("</select>").toString();
    }

    /** Libellé affiché du rôle métier (issue #127) : « Cartographe », « Donneur de quête »… */
    private static String roleLabel(String role) {
        return NpcRoles.label(role);
    }

    /**
     * Issue #127 — rôle métier en saisie libre, avec des suggestions. Le texte dit clairement que
     * c'est un libellé : les quêtes proposées et le dialogue se règlent ailleurs sur la fiche.
     */
    static String npcRoleField(String uid, String currentRole) {
        String listId = uid + "-roles";
        StringBuilder sb = new StringBuilder();
        sb.append("<label for=\"").append(uid).append("-role\">Rôle affiché</label>")
                .append("<input class=\"form-control\" type=\"text\" id=\"").append(uid)
                .append("-role\" name=\"role\" maxlength=\"").append(NpcRoles.MAX_LENGTH)
                .append("\" placeholder=\"Cartographe, Forgeron… (vide = aucun)\" list=\"").append(listId)
                .append("\" value=\"").append(Http.esc(roleLabel(currentRole))).append("\">");
        sb.append("<datalist id=\"").append(listId).append("\">");
        for (NpcRoles.Role r : NpcRoles.suggestions()) {
            sb.append("<option value=\"").append(Http.esc(r.label())).append("\"></option>");
        }
        sb.append("</datalist>");
        sb.append("<p class=\"form-text\">Libellé seulement : il dit qui est ce PNJ, il ne lui donne "
                + "aucun pouvoir. Les quêtes qu'il propose et son dialogue se règlent dans les autres "
                + "sections de la fiche.</p>");
        return sb.toString();
    }

    // ---- Personnage du jeu pas encore rattaché ------------------------------------------------

    private String renderUnattachedItem(Session session, String agentId, Map<String, Object> c, int idx,
                                        NpcRights rights) {
        String numeric = str(c.get("numericId"));
        String digits = numeric.replaceAll("[^0-9]", "");
        String rawName = cleanName(c.get("name"));
        String label = rawName.isEmpty() ? "PNJ sans nom #" + numeric : MiniText.plain(rawName);
        String slug = "fc-" + idx + "-" + digits;
        String place = npcPlace(c);
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"accordion-item npc-item\" data-filter-item=\"npcs\" data-filter-cat=\"todo\" "
                + "data-filter-text=\"").append(Http.esc(label + " " + numeric)).append("\" data-res-id=\"citizens-")
                .append(Http.esc(digits)).append("\">");
        sb.append("<h3 class=\"accordion-header\"><button class=\"accordion-button collapsed npc-head\" type=\"button\" "
                + "data-bs-toggle=\"collapse\" data-bs-target=\"#").append(slug).append("\" aria-expanded=\"false\" "
                + "aria-controls=\"").append(slug).append("\">");
        sb.append("<span class=\"npc-head-main\"><span class=\"npc-name\">")
                .append(rawName.isEmpty() ? Http.esc(label) : MiniText.html(rawName)).append("</span>");
        if (!place.isEmpty()) {
            sb.append("<span class=\"npc-loc\">").append(Icons.icon("world")).append(Http.esc(place)).append("</span>");
        }
        sb.append("</span><span class=\"npc-head-badges\"><span class=\"badge text-bg-warning\">à rattacher</span>"
                + "</span></button></h3>");
        sb.append("<div id=\"").append(slug).append("\" class=\"accordion-collapse collapse\" "
                + "data-bs-parent=\"#npc-accordion\"><div class=\"accordion-body npc-detail\">");
        sb.append("<p>Ce personnage est dans le jeu, mais RPGQuest ne le gère pas encore : on ne peut ni le "
                + "modifier ici, ni lui donner un dialogue.</p>");
        if (rights.attach()) {
            sb.append("<form method=\"post\" action=\"/npcs/attach\" data-submit-once class=\"actform\">")
                    .append("<input type=\"hidden\" name=\"_csrf\" value=\"").append(Http.esc(session.csrfToken())).append("\">")
                    .append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId)).append("\">")
                    .append("<input type=\"hidden\" name=\"citizens_id\" value=\"").append(Http.esc(digits)).append("\">")
                    .append("<p class=\"form-text\">Le personnage n'est pas recréé. Ses dialogues et quêtes sont conservés.</p>")
                    .append("<button class=\"btn\" type=\"submit\" data-busy-label=\"Rattachement…\">")
                    .append(Icons.icon("link")).append("Rattacher à RPGQuest</button></form>");
        }
        sb.append("<details class=\"npc-more npc-advanced\"><summary>Informations avancées</summary><dl class=\"npc-dl\">");
        dlRow(sb, "Personnage Citizens", "#" + Http.esc(numeric));
        String type = cleanNull(str(c.get("entityType")));
        if (!type.isEmpty()) {
            dlRow(sb, "Type", Http.esc(MinecraftNames.humanize(type)));
        }
        dlRow(sb, "Identifiant proposé", Ui.id(citizensNameToId(rawName, digits)));
        sb.append("</dl></details>");
        sb.append("</div></div></div>");
        return sb.toString();
    }

    /**
     * Id RPGQuest proposé pour un PNJ Citizens rattaché : nom en jeu normalisé (minuscules, sans
     * accent, {@code [a-z0-9._-]}), repli {@code citizens_<id numérique>}. C'est aussi ce qui
     * retrouve un dialogue {@code rpgquest:<id>} déjà écrit pour ce personnage.
     */
    static String citizensNameToId(String name, String numericDigits) {
        String base = java.text.Normalizer.normalize(MiniText.plain(name == null ? "" : name),
                        java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "_")
                .replaceAll("(^[._-]+|[._-]+$)", "");
        if (base.length() > 64) {
            base = base.substring(0, 64);
        }
        return base.isEmpty() ? "citizens_" + (numericDigits.isEmpty() ? "0" : numericDigits) : base;
    }

    // ---- Créer un PNJ -------------------------------------------------------------------------

    /**
     * Le formulaire de création : nom, emplacement, apparence. Un jeton de demande unique par
     * affichage fait qu'un double clic ou un renvoi du formulaire ne crée jamais deux PNJ.
     */
    private String npcCreateForm(Session session, String agentId, List<String> spawnWorlds, NpcDisplayNames names) {
        String uid = "npc-new";
        String token = java.util.UUID.randomUUID().toString().replace("-", "");
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"/npcs/create\" data-submit-once "
                + "autocomplete=\"off\" class=\"actform npc-create-form\">");
        sb.append("<input type=\"hidden\" name=\"_csrf\" value=\"").append(Http.esc(session.csrfToken())).append("\">");
        sb.append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"request_token\" value=\"").append(token).append("\">");
        sb.append("<label for=\"").append(uid).append("-name\">Nom</label>");
        sb.append("<div class=\"input-counter\"><input class=\"form-control\" id=\"").append(uid)
                .append("-name\" type=\"text\" name=\"display_name\" maxlength=\"").append(NpcCreationPlanner.MAX_NAME)
                .append("\" required data-charcount placeholder=\"Lucien\"")
                .append(nameTakenAttrs(uid + "-name", names, "")).append(">")
                .append(charCounter(uid + "-name", 0)).append("</div>");
        sb.append(nameTakenNote(uid + "-name", List.of()));

        sb.append("<label for=\"").append(uid).append("-place\">Emplacement</label>");
        sb.append("<select class=\"form-select\" id=\"").append(uid).append("-place\" name=\"place\" data-switch=\"")
                .append(uid).append("-place\"><option value=\"auto\" selected>À côté du Guide, au village</option>")
                .append("<option value=\"here\">À une position précise</option></select>");
        sb.append("<div data-switch-for=\"").append(uid).append("-place\" data-switch-value=\"here\" hidden>");
        sb.append(worldSelect(uid, "", spawnWorlds, true).replace("name=\"world\"", "name=\"world\" disabled"));
        sb.append(xyzFields(uid, null, false).replace("required", "required disabled"));
        sb.append("<p class=\"form-text\">Astuce : en jeu, la touche F3 affiche votre position.</p>");
        sb.append("</div>");

        sb.append(skinChoiceFields(uid + "-skin", true));

        sb.append("<div class=\"btnrow\"><button class=\"btn\" type=\"submit\" "
                + "data-busy-label=\"Création…\">").append(Icons.icon("save")).append("Créer le PNJ</button></div>");
        sb.append("</form>");
        return sb.toString();
    }

    /**
     * Les créations demandées récemment qui ne sont pas (encore) terminées : ce qui est fait, ce qui
     * reste, et le bouton « Reprendre », qui ne renvoie que les étapes manquantes.
     */
    private String npcPendingCreations(Session session, String agentId, List<Object> npcs, NpcRights rights) {
        java.util.Set<String> linked = new java.util.HashSet<>();
        for (Object o : npcs) {
            Map<String, Object> n = asMap(o);
            if (Boolean.TRUE.equals(n.get("citizensBindingPresent")) && Boolean.TRUE.equals(n.get("logicalDefinitionPresent"))) {
                linked.add(str(n.get("id")).toLowerCase(Locale.ROOT));
            }
        }
        StringBuilder sb = new StringBuilder();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant horizon = now.minus(java.time.Duration.ofDays(2));
        Optional<Instant> survey = surveyInstant(agentId, "npc.list");
        List<com.lodygames.rpgquest.panel.agent.AgentActionRow> recent = store.recentActions(agentId, 200);
        for (com.lodygames.rpgquest.panel.agent.AgentActionRow row : recent) {
            if (!"npc.citizens.provision".equals(row.type())) {
                continue;
            }
            String npcId = row.params().getOrDefault("npc_id", "");
            if (npcId.isEmpty() || !seen.add(npcId) || linked.contains(npcId)
                    || (row.createdAt() != null && row.createdAt().isBefore(horizon))
                    || deletedAfter(recent, npcId, row.createdAt())) {
                continue;
            }
            Instant finished = row.completedAt() != null ? row.completedAt() : row.createdAt();
            if (row.status() == com.lodygames.rpgquest.panel.agent.AgentActionStatus.SUCCESS
                    && survey.isPresent() && finished != null && survey.get().isAfter(finished)) {
                // Créé, puis la liste a été relue sans lui : il a été supprimé depuis. Un ancien
                // succès ne doit jamais laisser croire qu'il existe encore.
                continue;
            }
            String name = row.params().getOrDefault("display_name", npcId);
            boolean running = !row.status().terminal();
            boolean dialogueInSource = sourceCatalog.dialogues().stream().anyMatch(d -> d.plainId().equals(npcId));
            sb.append("<div class=\"alert ").append(running ? "alert-info" : "alert-warning").append(" npc-alert\">")
                    .append(Icons.icon(running ? "history" : "warning")).append("<div>");
            if (running) {
                sb.append("<strong>Création de « ").append(Http.esc(MiniText.plain(name)))
                        .append(" » en cours…</strong> La liste se met à jour toute seule.");
            } else if (row.status() == com.lodygames.rpgquest.panel.agent.AgentActionStatus.SUCCESS) {
                sb.append("<strong>« ").append(Http.esc(MiniText.plain(name)))
                        .append(" » a été créé</strong> ").append(Http.esc(ActionView.relativeTime(finished, now)))
                        .append(". Il apparaît dans la liste dès qu'elle est actualisée.");
            } else {
                sb.append("<strong>La création de « ").append(Http.esc(MiniText.plain(name)))
                        .append(" » n'est pas terminée.</strong>");
                sb.append("<ul class=\"npc-steps\">");
                sb.append(step(dialogueInSource, "Dialogue « Salut ! » préparé"));
                sb.append(step(false, "Personnage dans le monde"));
                sb.append("</ul>");
                if (row.resultMessage() != null && !row.resultMessage().isBlank()) {
                    sb.append("<p class=\"form-text mb-1\">").append(Http.esc(MiniText.prettifyTokens(row.resultMessage())))
                            .append("</p>");
                }
                if (rights.create()) {
                    sb.append("<form method=\"post\" action=\"/npcs/create\" data-submit-once class=\"d-inline\">")
                            .append("<input type=\"hidden\" name=\"_csrf\" value=\"").append(Http.esc(session.csrfToken())).append("\">")
                            .append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId)).append("\">")
                            .append("<input type=\"hidden\" name=\"resume\" value=\"").append(Http.esc(npcId)).append("\">")
                            .append("<input type=\"hidden\" name=\"request_token\" value=\"")
                            .append(java.util.UUID.randomUUID().toString().replace("-", "")).append("\">")
                            .append("<button class=\"btn sm\" type=\"submit\">Reprendre</button></form>");
                }
            }
            sb.append("</div></div>");
        }
        return sb.toString();
    }

    /** Le PNJ a-t-il été supprimé (fiche ou personnage) après cette création ? */
    private static boolean deletedAfter(List<com.lodygames.rpgquest.panel.agent.AgentActionRow> rows, String npcId,
                                        Instant created) {
        for (com.lodygames.rpgquest.panel.agent.AgentActionRow r : rows) {
            boolean delete = "npc.citizens.delete".equals(r.type()) || "npc.definition.delete".equals(r.type());
            if (delete && npcId.equalsIgnoreCase(r.params().getOrDefault("npc_id", ""))
                    && (created == null || r.createdAt() == null || !r.createdAt().isBefore(created))) {
                return true;
            }
        }
        return false;
    }

    private static String step(boolean done, String label) {
        return "<li class=\"" + (done ? "done" : "todo") + "\">" + Icons.icon(done ? "check" : "warning")
                + Http.esc(label) + "</li>";
    }

    /**
     * Identifiants déjà pris, toutes sources confondues : définitions, identifiants cités par le
     * contenu, PNJ en jeu, dialogues du serveur et de la source. Sert à choisir {@code lucien_2}.
     */
    public java.util.Set<String> npcTakenIds(String agentId) {
        java.util.Set<String> out = new java.util.HashSet<>();
        latestDetails(agentId, "npc.list").ifPresent(d -> {
            for (Object o : asList(d.get("definedIds"))) {
                out.add(str(o));
            }
            for (Object o : asList(d.get("canonicalIds"))) {
                out.add(str(o));
            }
            for (Object o : asList(d.get("npcs"))) {
                out.add(str(asMap(o).get("id")));
            }
        });
        latestDetails(agentId, "npc.citizens.list").ifPresent(d -> {
            for (Object o : asList(d.get("citizens"))) {
                String linked = cleanNull(str(asMap(o).get("linkedNpcId")));
                if (!linked.isEmpty()) {
                    out.add(linked);
                }
            }
        });
        for (Object o : latestDetails(agentId, "dialogue.list").map(x -> asList(x.get("dialogues"))).orElse(List.of())) {
            out.add(dialoguePlainKey(str(asMap(o).get("id"))));
        }
        for (SourceCatalog.DialogueSource ds : sourceCatalog.dialogues()) {
            out.add(ds.plainId());
        }
        out.remove("");
        return out;
    }

    /** Un dialogue de ce nom existe-t-il, dans la source ou sur le serveur ? */
    public boolean dialogueKnown(String agentId, String plainId) {
        for (SourceCatalog.DialogueSource ds : sourceCatalog.dialogues()) {
            if (ds.plainId().equals(plainId)) {
                return true;
            }
        }
        for (Object o : latestDetails(agentId, "dialogue.list").map(x -> asList(x.get("dialogues"))).orElse(List.of())) {
            if (dialoguePlainKey(str(asMap(o).get("id"))).equals(plainId)) {
                return true;
            }
        }
        return false;
    }

    /** {@code true} si le catalogue {@code npc.list} a déjà été relevé (sinon on ne peut rien conclure). */
    public boolean npcCatalogLoaded(String agentId) {
        return latestDetails(agentId, "npc.list").isPresent();
    }

    /** La ligne du relevé Citizens pour un identifiant numérique, ou vide. */
    public Optional<Map<String, Object>> citizensRowByNumeric(String agentId, String numericId) {
        for (Object o : latestDetails(agentId, "npc.citizens.list").map(x -> asList(x.get("citizens"))).orElse(List.of())) {
            Map<String, Object> c = asMap(o);
            if (numericId.equals(str(c.get("numericId")))) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /** La dernière création demandée avec ce jeton, s'il y en a une (anti-doublon). */
    public Optional<com.lodygames.rpgquest.panel.agent.AgentActionRow> npcCreationByToken(String agentId, String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        for (com.lodygames.rpgquest.panel.agent.AgentActionRow row : store.recentActions(agentId, 200)) {
            if (("npc.citizens.provision".equals(row.type()) || "npc.citizens.link".equals(row.type()))
                    && token.equals(row.params().get("request_token"))) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    /** La dernière création demandée pour cet identifiant (reprise). */
    public Optional<com.lodygames.rpgquest.panel.agent.AgentActionRow> npcLastProvision(String agentId, String npcId) {
        for (com.lodygames.rpgquest.panel.agent.AgentActionRow row : store.recentActions(agentId, 200)) {
            if ("npc.citizens.provision".equals(row.type()) && npcId.equals(row.params().get("npc_id"))) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    // ---- Page de diagnostic -------------------------------------------------------------------

    /**
     * {@code /npcs/diagnostic} — tout ce que la page principale ne montre plus : les compteurs, le
     * registre des identifiants, le rapprochement avec les PNJ du jeu et les messages bruts du
     * moteur. Utile pour comprendre un problème signalé « à vérifier », pas pour l'usage courant.
     */
    // ================================================================================
    //  PNJ récurrents — issue #13 étape 2 : modèles, occurrences par monde, journal récent.
    //  Lecture via encounter.list ; chaque action vise UNE occurrence nommée, jamais un lot.
    // ================================================================================

    public String npcEncounters(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("npc", "PNJ récurrents",
                "Les personnages qui apparaissent d'eux-mêmes dans les biomes explorés.",
                "<a class=\"btn secondary\" href=\"/npcs\">" + Icons.icon("back") + "Retour aux PNJ</a>"
                        + "<a class=\"btn secondary\" href=\"/quests/catalogs\">" + Icons.icon("book")
                        + "Catalogues de quêtes</a>"));
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        boolean canSpawn = perms.can(session.effective(), Permission.NPC_SPAWN_WRITE);
        boolean canWrite = perms.can(session.effective(), Permission.NPC_WRITE);
        sb.append(agentPicker(agentId, "/npcs/encounters", ""));
        sb.append("<div class=\"npc-catbar\">")
                .append(compactRefresh(session, agentId, "encounter.list", "Actualiser", "btn-outline-primary",
                        "/npcs/encounters"));
        if (canWrite) {
            sb.append(compactAction(session, agentId, "encounter.templates.reload", "Relire les modèles"))
                    .append(compactAction(session, agentId, "encounter.backoffs.clear", "Effacer les délais"));
        }
        sb.append("</div>");
        sb.append("<p class=\"muted\">Une rencontre apparaît la première fois qu'un joueur entre dans une zone "
                + "de biome (même découpage que les waypoints), au plus une par zone. Rien n'est généré au "
                + "démarrage. Un modèle créé ou modifié ici est pris en compte aussitôt pour les prochaines "
                + "apparitions ; les PNJ déjà en place ne changent pas.</p>");

        Optional<Map<String, Object>> latest = latestDetails(agentId, "encounter.list");
        if (latest.isEmpty()) {
            return sb.append(Ui.empty("npc", "Rien n'a encore été relevé : cliquez sur « Actualiser ».")).toString();
        }
        Map<String, Object> d = latest.get();
        if (!Boolean.TRUE.equals(d.get("available"))) {
            return sb.append(Ui.banner("warning", "Le serveur de jeu ne propose pas les PNJ récurrents "
                    + "(version du plugin trop ancienne ou service arrêté).")).toString();
        }
        sb.append(encounterOverview(agentId, d));
        sb.append(encounterTemplates(session, agentId, d, canWrite,
                perms.can(session.effective(), Permission.NPC_DELETE)));
        sb.append(encounterSites(session, agentId, d, canSpawn));
        sb.append(encounterEvents(d));
        return sb.toString();
    }

    // ================================================================================
    //  Emplacements de PNJ — issue #381. Marqués en jeu (outil « Emplacements PNJ »),
    //  lus via npc.spot.list ; placer / déplacer / orienter / libérer / supprimer passent
    //  chacun par UNE action nommée, jamais par des coordonnées tapées.
    // ================================================================================

    private static final String SPOT_PAGE = "/npcs/spots";

    public String npcSpots(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("npc", "Emplacements PNJ",
                "Les positions réservées en jeu, où placer et déplacer les PNJ sans taper de coordonnées.",
                "<a class=\"btn secondary\" href=\"/npcs\">" + Icons.icon("back") + "Retour aux PNJ</a>"));
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        boolean canSpawn = perms.can(session.effective(), Permission.NPC_SPAWN_WRITE);
        boolean canDelete = perms.can(session.effective(), Permission.NPC_DELETE);
        sb.append(agentPicker(agentId, SPOT_PAGE, ""));
        sb.append("<div class=\"npc-catbar\">")
                .append(compactRefresh(session, agentId, "npc.spot.list", "Actualiser", "btn-outline-primary",
                        SPOT_PAGE))
                .append("</div>");
        sb.append("<p class=\"muted\">Pour créer un emplacement : en jeu, prenez l'outil avec "
                + "<code>/rpgadmin npc spot tool</code>, visez le bloc où le PNJ doit se tenir et faites "
                + "<strong>clic droit</strong> (permission <code>rpgquest.admin.npc.spot</code>). Il apparaît ici "
                + "après « Actualiser », avec un nom provisoire à renommer ci-dessous. Retapez la commande pour "
                + "ranger l'outil. Placer un PNJ le déplace s'il existe déjà en jeu — la même entité, jamais une "
                + "copie — ou le fait apparaître sinon ; ses dialogues, quêtes et apparence ne changent pas.</p>");
        Optional<Map<String, Object>> latest = latestDetails(agentId, "npc.spot.list");
        if (latest.isEmpty()) {
            return sb.append(Ui.empty("npc", "Rien n'a encore été relevé : cliquez sur « Actualiser ».")).toString();
        }
        Map<String, Object> d = latest.get();
        if (!Boolean.TRUE.equals(d.get("available"))) {
            return sb.append(Ui.banner("warning", "Le serveur de jeu ne propose pas les emplacements de PNJ "
                    + "(version du plugin trop ancienne ou service arrêté).")).toString();
        }
        surveyAge(agentId, "npc.spot.list").ifPresent(age ->
                sb.append("<p class=\"form-text\">Relevé : ").append(Http.esc(age)).append("</p>"));
        Map<String, String> npcs = new java.util.TreeMap<>();
        // Noms affichés en MiniMessage côté plugin : texte brut dans les listes de choix.
        asMap(d.get("npcs")).forEach((id, name) -> npcs.put(id, MiniText.plain(str(name))));
        List<Map<String, Object>> spots = asList(d.get("spots")).stream().map(AgentPages::asMap).toList();
        sb.append(spotOverview(spots));
        sb.append(spotFilters(agentId, q, spots));
        List<Map<String, Object>> shown = spotFilter(spots, q);
        String preselect = cleanNull(q.getOrDefault("npc", "")).toLowerCase(java.util.Locale.ROOT);
        sb.append(spotList(session, agentId, shown, npcs, canSpawn, canDelete, preselect, spots.size()));
        if (canSpawn) {
            sb.append(spotCaptureForm(session, agentId, npcs, preselect));
        }
        return sb.toString();
    }

    private static final java.util.List<String> SPOT_STATUSES = java.util.List.of("FREE", "OCCUPIED", "CONFLICT",
            "INACCESSIBLE");

    private static String spotStatusLabel(String status) {
        return switch (status) {
            case "FREE" -> "libre";
            case "OCCUPIED" -> "occupé";
            case "CONFLICT" -> "conflit";
            case "INACCESSIBLE" -> "inaccessible";
            default -> status.toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static String spotStatusBadge(String status) {
        String tone = switch (status) {
            case "FREE" -> "text-bg-success";
            case "OCCUPIED" -> "text-bg-primary";
            case "CONFLICT" -> "text-bg-warning";
            default -> "text-bg-secondary";
        };
        return "<span class=\"badge " + tone + "\">" + Http.esc(spotStatusLabel(status)) + "</span>";
    }

    private static String spotOverview(List<Map<String, Object>> spots) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        SPOT_STATUSES.forEach(st -> counts.put(st, 0));
        spots.forEach(sp -> counts.merge(str(sp.get("status")), 1, Integer::sum));
        StringBuilder sb = new StringBuilder("<p>").append(spots.size()).append(" emplacement(s) : ");
        List<String> parts = new ArrayList<>();
        counts.forEach((st, n) -> parts.add(spotStatusBadge(st) + " " + n));
        return sb.append(String.join(" · ", parts)).append("</p>").toString();
    }

    /** Filtres GET (monde, état, recherche) : aucune écriture, simple lecture du relevé. */
    private static String spotFilters(String agentId, Map<String, String> q, List<Map<String, Object>> spots) {
        String world = cleanNull(q.getOrDefault("world", ""));
        String status = cleanNull(q.getOrDefault("status", ""));
        String text = cleanNull(q.getOrDefault("q", ""));
        StringBuilder sb = new StringBuilder("<form method=\"get\" action=\"").append(SPOT_PAGE)
                .append("\" class=\"spot-filters form-grid3\">")
                .append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId)).append("\">")
                .append("<div><label for=\"spot-q\">Recherche</label><input class=\"form-control\" id=\"spot-q\" "
                        + "type=\"search\" name=\"q\" value=\"").append(Http.esc(text))
                .append("\" placeholder=\"nom, identifiant, PNJ…\"></div>")
                .append("<div><label for=\"spot-world\">Monde</label><select class=\"form-select\" id=\"spot-world\" "
                        + "name=\"world\"><option value=\"\">Tous</option>");
        spots.stream().map(sp -> str(sp.get("world"))).distinct().sorted().forEach(w -> sb.append("<option value=\"")
                .append(Http.esc(w)).append("\"").append(w.equals(world) ? " selected" : "").append(">")
                .append(Http.esc(worldLabel(w))).append("</option>"));
        sb.append("</select></div><div><label for=\"spot-status\">État</label><select class=\"form-select\" "
                + "id=\"spot-status\" name=\"status\"><option value=\"\">Tous</option>");
        SPOT_STATUSES.forEach(st -> sb.append("<option value=\"").append(st).append("\"")
                .append(st.equals(status) ? " selected" : "").append(">").append(Http.esc(spotStatusLabel(st)))
                .append("</option>"));
        return sb.append("</select></div><div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-secondary\" "
                + "type=\"submit\">Filtrer</button></div></form>").toString();
    }

    static List<Map<String, Object>> spotFilter(List<Map<String, Object>> spots, Map<String, String> q) {
        String world = cleanNull(q.getOrDefault("world", ""));
        String status = cleanNull(q.getOrDefault("status", ""));
        String text = cleanNull(q.getOrDefault("q", "")).trim().toLowerCase(java.util.Locale.ROOT);
        return spots.stream()
                .filter(sp -> world.isEmpty() || world.equals(str(sp.get("world"))))
                .filter(sp -> status.isEmpty() || status.equals(str(sp.get("status"))))
                .filter(sp -> text.isEmpty() || String.join(" ", str(sp.get("id")), str(sp.get("name")),
                                cleanNull(str(sp.get("npcId"))), cleanNull(str(sp.get("npcName"))))
                        .toLowerCase(java.util.Locale.ROOT).contains(text))
                .toList();
    }

    private String spotList(Session session, String agentId, List<Map<String, Object>> shown, Map<String, String> npcs,
                            boolean canSpawn, boolean canDelete, String preselect, int total) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">")
                .append(Ui.sectionTitle("travel", "Emplacements"));
        if (total == 0) {
            return sb.append("<p class=\"muted\">Aucun emplacement réservé pour l'instant.</p></section>").toString();
        }
        if (shown.isEmpty()) {
            return sb.append("<p class=\"muted\">Aucun emplacement ne correspond aux filtres.</p></section>").toString();
        }
        sb.append("<div class=\"enc-list spot-list\"><div class=\"enc-head\" aria-hidden=\"true\"><span>Emplacement</span>"
                + "<span>Position</span><span>PNJ</span><span>État</span><span></span></div>");
        int i = 0;
        for (Map<String, Object> sp : shown) {
            sb.append(spotRow(session, agentId, sp, npcs, canSpawn, canDelete, preselect, i++));
        }
        return sb.append("</div></section>").toString();
    }

    private String spotRow(Session session, String agentId, Map<String, Object> sp, Map<String, String> npcs,
                           boolean canSpawn, boolean canDelete, String preselect, int index) {
        String id = str(sp.get("id"));
        String name = str(sp.get("name"));
        String status = str(sp.get("status"));
        String npcId = cleanNull(str(sp.get("npcId")));
        String npcName = MiniText.plain(cleanNull(str(sp.get("npcName"))));
        int citizensId = intOr(sp.get("citizensId"), -1);
        String pos = coord(sp.get("x")) + " " + coord(sp.get("y")) + " " + coord(sp.get("z"));
        String npcHtml = npcId.isEmpty() ? "<span class=\"muted\">aucun</span>"
                : (npcName.isEmpty() ? "" : Http.esc(npcName) + "<br>") + Ui.id(npcId)
                + (citizensId >= 0 ? " <small class=\"muted\">Citizens #" + citizensId + "</small>" : "");
        StringBuilder notes = new StringBuilder();
        for (Object n : asList(sp.get("notes"))) {
            notes.append("<br><small class=\"text-warning-emphasis\">").append(Http.esc(str(n))).append("</small>");
        }
        StringBuilder sb = new StringBuilder("<div class=\"enc-row\" id=\"spot-").append(Http.esc(id)).append("\">")
                .append(encounterCell("Emplacement", "enc-main", "<strong>" + Http.esc(name) + "</strong><br>"
                        + Ui.id(id) + "<br><small class=\"muted\">par " + Http.esc(cleanNull(str(sp.get("createdBy"))))
                        + "</small>"))
                .append(encounterCell("Position", "", Http.esc(worldLabel(str(sp.get("world")))) + "<br><code>"
                        + Http.esc(pos) + "</code><br><small class=\"muted\">regard " + Http.esc(directionLabel(sp.get("yaw")))
                        + " · <code>/tp @s " + Http.esc(pos) + "</code></small>"))
                .append(encounterCell("PNJ", "", npcHtml))
                .append(encounterCell("État", "", spotStatusBadge(status) + "<br><small class=\"muted\">"
                        + Http.esc(str(sp.get("statusMessage"))) + "</small>" + notes))
                .append("<div class=\"enc-c enc-act\">");
        if (!canSpawn && !canDelete) {
            return sb.append("<span class=\"muted\">lecture seule</span></div></div>").toString();
        }
        String target = "spot-act-" + index;
        sb.append(actionToggle(target, "Gérer", "wrench", "btn-outline-secondary")).append("</div>")
                .append("<div class=\"collapse enc-panel\" id=\"").append(target).append("\"><div class=\"enc-site-acts\">");
        if (canSpawn) {
            sb.append(spotPlaceForm(session, agentId, id, name, npcId, npcs, preselect));
            sb.append(spotRenameForm(session, agentId, id, name));
            sb.append(spotOrientForm(session, agentId, id));
            if (!npcId.isEmpty()) {
                sb.append(spotIdForm(session, agentId, "npc.spot.release", id, "Libérer (le PNJ ne bouge pas)",
                        "btn-outline-secondary", null));
            }
        }
        if (canDelete && npcId.isEmpty()) {
            sb.append(spotIdForm(session, agentId, "npc.spot.delete", id, "Supprimer l'emplacement",
                    "btn-outline-danger", "Supprimer « " + name + " » (aucun PNJ n'est touché)"));
        }
        return sb.append("</div></div></div>").toString();
    }

    private String spotPlaceForm(Session session, String agentId, String id, String name, String occupant,
                                 Map<String, String> npcs, String preselect) {
        String uid = "spot-place-" + id;
        String chosen = !occupant.isEmpty() ? occupant : preselect;
        StringBuilder sb = new StringBuilder(formStart(session, agentId, "npc.spot.place", SPOT_PAGE, "")
                .replaceFirst("<form ", "<form data-submit-once "))
                .append("<input type=\"hidden\" name=\"spot_id\" value=\"").append(Http.esc(id)).append("\">")
                .append("<label for=\"").append(uid).append("\">PNJ à placer ici</label>")
                .append("<select class=\"form-select\" id=\"").append(uid).append("\" name=\"npc_id\" required>")
                .append("<option value=\"\">— choisir —</option>");
        npcs.forEach((npcId, label) -> sb.append("<option value=\"").append(Http.esc(npcId)).append("\"")
                .append(npcId.equals(chosen) ? " selected" : "").append(">").append(Http.esc(label)).append(" (")
                .append(Http.esc(npcId)).append(")</option>"));
        return sb.append("</select>")
                .append(confirmBox("Placer le PNJ choisi sur « " + name + " » : déplacé s'il existe en jeu, créé sinon"))
                .append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-primary\" type=\"submit\">")
                .append(Icons.icon("travel")).append(occupant.isEmpty() ? "Placer / téléporter ici" : "Replacer ici")
                .append("</button></div></form>").toString();
    }

    private String spotRenameForm(Session session, String agentId, String id, String name) {
        String uid = "spot-name-" + id;
        return formStart(session, agentId, "npc.spot.rename", SPOT_PAGE, "")
                + "<input type=\"hidden\" name=\"spot_id\" value=\"" + Http.esc(id) + "\">"
                + "<label for=\"" + uid + "\">Nom</label><input class=\"form-control\" id=\"" + uid
                + "\" name=\"name\" required maxlength=\"" + AgentActionCatalog.MAX_NPC_SPOT_NAME + "\" value=\""
                + Http.esc(name) + "\"><div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-primary\" "
                + "type=\"submit\">Renommer</button></div></form>";
    }

    private String spotOrientForm(Session session, String agentId, String id) {
        String uid = "spot-facing-" + id;
        return formStart(session, agentId, "npc.spot.orient", SPOT_PAGE, "")
                + "<input type=\"hidden\" name=\"spot_id\" value=\"" + Http.esc(id) + "\">"
                + "<label for=\"" + uid + "\">Regarde vers</label><select class=\"form-select\" id=\"" + uid
                + "\" name=\"facing\" required><option value=\"\">— choisir —</option>"
                + "<option value=\"north\">Le nord</option><option value=\"south\">Le sud</option>"
                + "<option value=\"east\">L'est</option><option value=\"west\">L'ouest</option></select>"
                + "<small class=\"form-text\">Le PNJ qui occupe l'emplacement se tourne aussi.</small>"
                + "<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">"
                + "Orienter</button></div></form>";
    }

    private String spotIdForm(Session session, String agentId, String type, String id, String label, String btnClass,
                              String confirm) {
        return formStart(session, agentId, type, SPOT_PAGE, "")
                + "<input type=\"hidden\" name=\"spot_id\" value=\"" + Http.esc(id) + "\">"
                + (confirm == null ? "" : confirmBox(confirm))
                + "<div class=\"btnrow\"><button class=\"btn btn-sm " + btnClass + "\" type=\"submit\">"
                + Http.esc(label) + "</button></div></form>";
    }

    private String spotCaptureForm(Session session, String agentId, Map<String, String> npcs, String preselect) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">")
                .append(Ui.sectionTitle("plus", "Réserver la position actuelle d'un PNJ"))
                .append("<p class=\"muted\">Le PNJ ne bouge pas : sa position et son orientation actuelles deviennent "
                        + "un emplacement qu'il occupe, réutilisable ensuite pour le replacer ou en placer un autre.</p>")
                .append(formStart(session, agentId, "npc.spot.capture", SPOT_PAGE, ""))
                .append("<div class=\"form-grid2\"><div><label for=\"spot-cap-npc\">PNJ</label>")
                .append("<select class=\"form-select\" id=\"spot-cap-npc\" name=\"npc_id\" required>")
                .append("<option value=\"\">— choisir —</option>");
        npcs.forEach((npcId, label) -> sb.append("<option value=\"").append(Http.esc(npcId)).append("\"")
                .append(npcId.equals(preselect) ? " selected" : "").append(">").append(Http.esc(label)).append(" (")
                .append(Http.esc(npcId)).append(")</option>"));
        return sb.append("</select></div><div><label for=\"spot-cap-name\">Nom (facultatif)</label>")
                .append("<input class=\"form-control\" id=\"spot-cap-name\" name=\"name\" maxlength=\"")
                .append(AgentActionCatalog.MAX_NPC_SPOT_NAME).append("\" placeholder=\"nom du PNJ par défaut\"></div></div>")
                .append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">")
                .append("Réserver sa position</button></div></form></section>").toString();
    }

    /** Regard Minecraft (yaw) en mots : 0 = sud, 90 = ouest, 180 = nord, −90 = est. */
    static String directionLabel(Object yawValue) {
        double yaw;
        try {
            yaw = Double.parseDouble(str(yawValue));
        } catch (NumberFormatException e) {
            return "inconnu";
        }
        double normalized = ((yaw % 360) + 360) % 360;
        String[] names = {"sud", "sud-ouest", "ouest", "nord-ouest", "nord", "nord-est", "est", "sud-est"};
        return names[(int) Math.round(normalized / 45.0) % 8] + " (" + Math.round(yaw) + "°)";
    }

    // ================================================================================
    //  Catalogues de quêtes secondaires — issue #369, parcours refondu par #414 (rendu dans
    //  QuestCatalogPages). Lecture via quest.catalog.list ;
    //  écriture par quest.catalog.create / update / delete (champs, jamais de YAML).
    // ================================================================================

    public String questCatalogs(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        if (agent.isEmpty()) {
            return Ui.pageHeader("book", "Catalogues de quêtes",
                    "Regroupe plusieurs quêtes et choisis quels PNJ peuvent les proposer aux joueurs.", "")
                    + noAgent();
        }
        String agentId = agent.get().id();
        boolean canWrite = perms.can(session.effective(), Permission.QUEST_CONTENT_WRITE);
        Optional<AgentActionRow> surveyRow = store.latestSuccessfulActionOfType(agentId, "quest.catalog.list");
        Optional<Map<String, Object>> latest = surveyRow.flatMap(this::detailsOf);
        String refresh = compactRefresh(session, agentId, "quest.catalog.list", "Actualiser", "secondary sm",
                QuestCatalogPages.PAGE);
        StringBuilder sb = new StringBuilder(agentPicker(agentId, QuestCatalogPages.PAGE, ""));
        if (latest.isEmpty()) {
            return sb.append(Ui.pageHeader("book", "Catalogues de quêtes",
                    "Regroupe plusieurs quêtes et choisis quels PNJ peuvent les proposer aux joueurs.", ""))
                    .append(Ui.empty("quests", "Rien n'a encore été relevé sur le serveur : cliquez sur « Actualiser »."))
                    .append("<div class=\"npc-catbar\">").append(refresh).append("</div>").toString();
        }
        QuestCatalogPages.Survey survey = QuestCatalogPages.parse(latest.get());
        if (!survey.available()) {
            return sb.append(Ui.banner("warning", "Le serveur de jeu ne propose pas les catalogues de quêtes "
                    + "(version du plugin trop ancienne ou service arrêté).")).toString();
        }
        Instant surveyedAt = surveyRow.map(r -> r.completedAt() != null ? r.completedAt() : r.createdAt())
                .orElse(Instant.now());
        int pending = (int) store.recentActions(agentId, 100).stream()
                .filter(a -> a.type().startsWith("quest.catalog.") && !"quest.catalog.list".equals(a.type())
                        && !a.status().terminal()).count();
        QuestCatalogPages.Ctx ctx = new QuestCatalogPages.Ctx(session.csrfToken(), agentId, canWrite,
                surveyRow.map(AgentActionRow::id).orElse(""),
                com.lodygames.rpgquest.panel.agent.AgentLiveness.ageHuman(surveyedAt, Instant.now()),
                java.time.Duration.between(surveyedAt, Instant.now()).toMinutes() >= QuestCatalogPages.STALE_AFTER_MINUTES,
                pending, refresh);

        String focus = cleanFocus(q.get("focus"));
        String mode = q.getOrDefault("mode", "");
        if (canWrite && "1".equals(q.get("new"))) {
            return sb.append(QuestCatalogPages.create(ctx, survey)).toString();
        }
        if (focus.isEmpty()) {
            return sb.append(QuestCatalogPages.home(ctx, survey)).toString();
        }
        QuestCatalogPages.Catalog catalog = survey.find(focus);
        if (catalog == null) {
            return sb.append(QuestCatalogPages.notFound(ctx, focus)).toString();
        }
        if (canWrite) {
            switch (mode) {
                case "edit" -> {
                    return sb.append(QuestCatalogPages.edit(ctx, survey, catalog, "1".equals(q.get("add")))).toString();
                }
                case "copy" -> {
                    return sb.append(QuestCatalogPages.duplicate(ctx, catalog)).toString();
                }
                case "delete" -> {
                    return sb.append(QuestCatalogPages.delete(ctx, survey, catalog)).toString();
                }
                default -> {
                }
            }
        }
        return sb.append(QuestCatalogPages.detail(ctx, survey, catalog)).toString();
    }

    /** Identifiants des catalogues du dernier relevé — pour calculer celui d'un nouveau catalogue (#414). */
    public java.util.Set<String> questCatalogIds(String agentId) {
        return latestDetails(agentId, "quest.catalog.list").map(QuestCatalogPages::parse)
                .map(s -> s.catalogs().stream().map(QuestCatalogPages.Catalog::id)
                        .collect(java.util.stream.Collectors.toSet()))
                .orElse(java.util.Set.of());
    }

    /**
     * Issue #414 — garde-fou contre les modifications perdues : un formulaire de catalogue est
     * construit sur un relevé ({@code based_on} = identifiant de cette action). Si une autre écriture
     * sur CE catalogue a été demandée après ce relevé, le formulaire est périmé et rien n'est envoyé.
     * L'ordre est celui du journal (création puis insertion) : l'horodatage seul, à la seconde, ne
     * départagerait pas deux actions de la même seconde.
     *
     * @return le message de refus, ou {@code null} si l'écriture peut partir
     */
    public String questCatalogConflict(String agentId, String catalogId, String basedOn) {
        if (basedOn == null || basedOn.isBlank()) {
            return null;
        }
        for (AgentActionRow a : store.recentActions(agentId, 300)) {
            if (a.id().equals(basedOn.trim())) {
                return null;
            }
            if (a.type().startsWith("quest.catalog.") && !"quest.catalog.list".equals(a.type())
                    && catalogId.equals(a.params().get("id"))) {
                return "Ce catalogue a été modifié depuis l'ouverture de la page (par " + a.createdBy()
                        + ") : rien n'a été envoyé. Rechargez la page pour repartir de la dernière version.";
            }
        }
        return null;
    }

    private String compactAction(Session session, String agentId, String type, String label) {
        return formStart(session, agentId, type, "/npcs/encounters", "").replace("class=\"actform\"",
                "class=\"d-inline\"") + "<button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\">"
                + Http.esc(label) + "</button></form>";
    }

    private String encounterOverview(String agentId, Map<String, Object> d) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">")
                .append(Ui.sectionTitle("diagnostics", "Vue d'ensemble")).append("<dl class=\"npc-dl\">");
        boolean enabled = Boolean.TRUE.equals(d.get("enabled"));
        dlRow(sb, "Apparitions", enabled ? "activées"
                : "<span class=\"badge text-bg-warning\">désactivées</span> (<code>encounters.enabled</code>)");
        List<String> worlds = asList(d.get("worlds")).stream().map(AgentPages::str).toList();
        dlRow(sb, "Mondes concernés", worlds.isEmpty() ? "<span class=\"badge text-bg-warning\">aucun</span>"
                : Http.esc(String.join(", ", worlds.stream().map(AgentPages::worldLabel).toList())));
        dlRow(sb, "Plafond par monde", Http.esc(str(d.get("maxPerWorld"))));
        dlRow(sb, "Modèles chargés", Http.esc(String.valueOf(asList(d.get("templates")).size())));
        dlRow(sb, "Occurrences", Http.esc(String.valueOf(asList(d.get("sites")).size())));
        surveyAge(agentId, "encounter.list").ifPresent(age -> dlRow(sb, "Relevé", Http.esc(age)));
        sb.append("</dl>");
        Map<String, Object> issues = asMap(d.get("issues"));
        if (!issues.isEmpty()) {
            StringBuilder list = new StringBuilder("Fichier(s) de modèle refusé(s) au dernier chargement :<ul>");
            issues.forEach((file, msg) -> list.append("<li><code>").append(Http.esc(file)).append("</code> : ")
                    .append(Http.esc(str(msg))).append("</li>"));
            sb.append(Ui.banner("warning", list.append("</ul>").toString()));
        }
        return sb.append("</section>").toString();
    }

    private String encounterTemplates(Session session, String agentId, Map<String, Object> d, boolean canWrite,
                                      boolean canDelete) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">").append(Ui.sectionTitle("npc", "Modèles"));
        if (canWrite) {
            sb.append("<div class=\"npc-catbar\">").append(actionToggle("enc-tpl-new", "Nouveau modèle", "plus",
                    "btn-outline-primary")).append("</div>")
                    .append(actionCollapse("enc-tpl-new", "<div class=\"card card-body npc-formcard\">"
                            + encounterTemplateForm(session, agentId, Map.of(), true, "enc-tpl-new-f")
                            + "</div>"));
        }
        List<Object> templates = asList(d.get("templates"));
        if (templates.isEmpty()) {
            return sb.append("<p class=\"muted\">Aucun modèle chargé.</p></section>").toString();
        }
        // Issue #360 : liste en grille plutôt qu'un tableau à 8 colonnes — le panneau « Gérer » se déplie
        // sur toute la largeur sous sa ligne au lieu d'allonger une cellule étroite, et chaque ligne
        // devient une carte sur petit écran.
        if (templates.size() >= ENCOUNTER_FILTER_THRESHOLD) {
            sb.append(encounterFilter("enc-tpl-list", "Filtrer les modèles (nom, PNJ, biome…)"));
        }
        sb.append("<div class=\"enc-list enc-tpl\" id=\"enc-tpl-list\"><div class=\"enc-head\" aria-hidden=\"true\">"
                + "<span>Modèle</span><span>PNJ</span><span>Rôle / famille</span><span>Biomes</span>"
                + "<span>Mondes</span><span>Poids</span><span title=\"Occurrences\">Apparus</span><span>Statut</span><span></span></div>");
        int i = 0;
        for (Object o : templates) {
            Map<String, Object> t = asMap(o);
            boolean enabled = Boolean.TRUE.equals(t.get("enabled"));
            String max = "0".equals(str(t.get("maxSites"))) ? "" : " / " + str(t.get("maxSites"));
            List<String> worlds = asList(t.get("worlds")).stream().map(AgentPages::str).toList();
            List<String> biomes = asList(t.get("biomes")).stream().map(b -> str(b).replace("minecraft:", "")).toList();
            String family = cleanNull(str(t.get("family")));
            String role = cleanNull(str(t.get("role")));
            String target = "enc-tpl-" + i++;
            sb.append("<div class=\"enc-row\" data-filter-item=\"enc-tpl-list\" data-filter-text=\"")
                    .append(Http.esc(String.join(" ",
                            str(t.get("displayName")), str(t.get("id")), str(t.get("npcId")), role, family,
                            String.join(" ", biomes)).toLowerCase(java.util.Locale.ROOT))).append("\">")
                    .append(encounterCell("Modèle", "enc-main", MiniText.html(str(t.get("displayName"))) + " "
                            + Ui.id(str(t.get("id"))) + (cleanNull(str(t.get("skinUrl"))).isEmpty() ? ""
                            : " <span class=\"badge text-bg-info\">apparence</span>")))
                    .append(encounterCell("PNJ", "", Ui.id(str(t.get("npcId")))))
                    .append(encounterCell("Rôle / famille", "", Http.esc(role)
                            + (family.isEmpty() ? "" : (role.isEmpty() ? "" : " · ") + Http.esc(family))))
                    .append(encounterCell("Biomes", "enc-wide", encounterBiomes(family, biomes)))
                    .append(encounterCell("Mondes", "", worlds.isEmpty() ? "tous" : Http.esc(String.join(", ", worlds))))
                    .append(encounterCell("Poids", "", Http.esc(str(t.get("weight")))))
                    .append(encounterCell("Occurrences", "", Http.esc(str(t.get("sites")) + max)))
                    .append(encounterCell("Statut", "", enabled ? "<span class=\"badge text-bg-success\">actif</span>"
                            : "<span class=\"badge text-bg-secondary\">désactivé</span>"))
                    .append("<div class=\"enc-c enc-act\">")
                    .append(canWrite || canDelete ? actionToggle(target, "Gérer", "wrench", "btn-outline-secondary")
                            : "<span class=\"muted\">lecture seule</span>")
                    .append("</div>");
            if (canWrite || canDelete) {
                sb.append("<div class=\"collapse enc-panel\" id=\"").append(target).append("\">")
                        .append(encounterTemplateActions(session, agentId, t, canWrite, canDelete, target))
                        .append("</div>");
            }
            sb.append("</div>");
        }
        return sb.append("</div></section>").toString();
    }

    /** À partir de ce nombre d'éléments, une liste de PNJ récurrents reçoit un champ de filtre. */
    static final int ENCOUNTER_FILTER_THRESHOLD = 8;

    private static String encounterFilter(String listId, String placeholder) {
        return "<input class=\"form-control enc-filter\" type=\"search\" data-filter-input=\"" + listId
                + "\" placeholder=\"" + Http.esc(placeholder) + "\" aria-label=\"" + Http.esc(placeholder) + "\">";
    }

    /** Une cellule de la grille : l'étiquette n'est affichée qu'en vue carte (petit écran). */
    private static String encounterCell(String label, String cls, String html) {
        return "<div class=\"enc-c" + (cls.isEmpty() ? "" : " " + cls) + "\"><span class=\"enc-k\">"
                + Http.esc(label) + "</span>" + html + "</div>";
    }

    /** Résumé « Forêts — 5 biomes », détail replié : une longue liste ne gonfle plus la ligne. */
    static String encounterBiomes(String family, List<String> biomes) {
        if (biomes.isEmpty()) {
            return "<span class=\"badge text-bg-warning\">aucun</span>";
        }
        String count = biomes.size() + (biomes.size() > 1 ? " biomes" : " biome");
        String label = family.isEmpty() ? count : familyLabel(family) + " — " + count;
        return "<details class=\"enc-biomes\"><summary>" + Http.esc(label) + "</summary><small>"
                + Http.esc(String.join(", ", biomes)) + "</small></details>";
    }

    /** Libellé lisible d'une famille de biomes ; une famille inconnue est affichée telle quelle. */
    static String familyLabel(String family) {
        String key = family.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (key) {
            case "forest", "forêt", "foret", "forests", "forêts" -> "Forêts";
            case "desert", "désert", "deserts", "déserts" -> "Déserts";
            case "mountain", "mountains", "montagne", "montagnes" -> "Montagnes";
            case "swamp", "swamps", "marais" -> "Marais";
            case "plains", "plaine", "plaines" -> "Plaines";
            case "jungle", "jungles" -> "Jungles";
            case "taiga", "taïga", "taigas" -> "Taïgas";
            case "snow", "snowy", "neige", "enneigé" -> "Terres enneigées";
            case "ocean", "oceans", "océan", "océans" -> "Océans";
            case "savanna", "savane", "savanes" -> "Savanes";
            case "badlands", "mesa" -> "Badlands";
            case "beach", "plage", "plages" -> "Plages";
            case "cave", "caves", "grotte", "grottes" -> "Grottes";
            default -> Character.toUpperCase(family.trim().charAt(0)) + family.trim().substring(1);
        };
    }

    private String encounterTemplateActions(Session session, String agentId, Map<String, Object> t, boolean canWrite,
                                            boolean canDelete, String target) {
        String id = str(t.get("id"));
        StringBuilder inner = new StringBuilder();
        if (canWrite) {
            boolean enabled = Boolean.TRUE.equals(t.get("enabled"));
            inner.append(formStart(session, agentId, "encounter.template.toggle", "/npcs/encounters", ""))
                    .append("<input type=\"hidden\" name=\"id\" value=\"").append(Http.esc(id)).append("\">")
                    .append("<input type=\"hidden\" name=\"enabled\" value=\"").append(!enabled).append("\">")
                    .append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\">")
                    .append(enabled ? "Désactiver le modèle" : "Activer le modèle").append("</button></div></form>")
                    .append("<p class=\"form-text\">Désactivé : plus aucune nouvelle rencontre ; celles déjà "
                            + "apparues restent en place.</p>");
            inner.append("<details class=\"npc-registry\"><summary>Modifier</summary>")
                    .append(encounterTemplateForm(session, agentId, t, false, target + "-e")).append("</details>");
            Map<String, Object> copy = new LinkedHashMap<>(t);
            copy.put("id", "");
            copy.put("displayName", str(t.get("displayName")) + " (copie)");
            inner.append("<details class=\"npc-registry\"><summary>Dupliquer</summary>")
                    .append(encounterTemplateForm(session, agentId, copy, true, target + "-d")).append("</details>");
        }
        if (canDelete) {
            int sites = parseIntOr(str(t.get("sites")), 0);
            inner.append("<details class=\"npc-registry\"><summary>Supprimer</summary>")
                    .append(formStart(session, agentId, "encounter.template.delete", "/npcs/encounters", ""))
                    .append("<input type=\"hidden\" name=\"id\" value=\"").append(Http.esc(id)).append("\">")
                    .append("<p class=\"form-text\">Le fichier est sauvegardé dans "
                            + "<code>plugins/RPGQuest/encounter-backups/</code> avant suppression.</p>");
            if (sites > 0) {
                inner.append(Ui.banner("warning", sites + " occurrence(s) de ce modèle existent. Elles resteront "
                        + "en place avec leur PNJ et leur dialogue ; retirez-les d'abord si elles doivent disparaître."))
                        .append("<label class=\"inline confirm\"><input type=\"checkbox\" name=\"acknowledge_sites\" "
                                + "value=\"true\" required> Laisser ces ").append(sites)
                        .append(" occurrence(s) en place</label>");
            }
            inner.append(confirmBox("Supprimer le modèle " + id))
                    .append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-danger\" type=\"submit\">"
                            + "Supprimer</button></div></form></details>");
        }
        return inner.toString();
    }

    /**
     * Formulaire d'un modèle de PNJ récurrent. {@code create} : identifiant saisi (vide pour un
     * nouveau modèle ou une copie) ; sinon l'identifiant est fixe — c'est le nom du fichier.
     * Les réglages rares (mondes, poids, plafond, apparence, description) sont repliés.
     */
    private String encounterTemplateForm(Session session, String agentId, Map<String, Object> t, boolean create,
                                         String uid) {
        String id = cleanNull(str(t.get("id")));
        StringBuilder sb = new StringBuilder(formStart(session, agentId,
                create ? "encounter.template.create" : "encounter.template.update", "/npcs/encounters", ""));
        if (create) {
            sb.append(encounterField(uid, "id", "Identifiant du modèle", id, "swamp_alchemist", 64, true,
                    "Minuscules, chiffres, « . _ - ». Devient le nom du fichier ; ne change plus ensuite."));
        } else {
            sb.append("<input type=\"hidden\" name=\"id\" value=\"").append(Http.esc(id)).append("\">");
        }
        sb.append(encounterField(uid, "display_name", "Nom affiché", cleanNull(str(t.get("displayName"))),
                "Alchimiste des marais", 64, true, "Texte simple, au-dessus du PNJ."));
        sb.append(encounterField(uid, "npc_id", "Identité du PNJ (npc_id)", cleanNull(str(t.get("npcId"))),
                "vide = identifiant du modèle", 64, false,
                "Son dialogue est rpgquest:<npc_id>, partagé par toutes ses rencontres. Une modification ne "
                        + "s'applique qu'aux nouvelles rencontres."));
        sb.append(encounterField(uid, "role", "Rôle", cleanNull(str(t.get("role"))), "alchimiste", 32, false, null));
        sb.append(encounterField(uid, "family", "Famille de biomes", cleanNull(str(t.get("family"))), "swamp", 32,
                false, "Libellé seulement : ce sont les biomes ci-dessous qui décident."));
        String biomes = String.join("\n", asList(t.get("biomes")).stream().map(AgentPages::str).toList());
        sb.append("<label for=\"").append(uid).append("-biomes\">Biomes éligibles</label>")
                .append("<textarea class=\"form-control\" id=\"").append(uid)
                .append("-biomes\" name=\"biomes\" rows=\"3\" required placeholder=\"swamp&#10;mangrove_swamp\">")
                .append(Http.esc(biomes)).append("</textarea><p class=\"form-text\">Un par ligne (ou séparés par "
                        + "des virgules) ; « forest » vaut « minecraft:forest ».</p>");
        sb.append(encounterField(uid, "quest_catalogs", "Catalogues de quêtes", String.join(", ",
                        asList(t.get("questCatalogs")).stream().map(AgentPages::str).toList()),
                "bandit_contracts, quetes_communes", 256, false,
                "Identifiants des catalogues proposés par ces PNJ (page Quêtes › Catalogues), séparés par des "
                        + "virgules. Dans leur dialogue, une réponse doit « Proposer une quête au hasard dans une liste ». "
                        + "S'applique aussitôt à toutes les rencontres, existantes comprises."));
        boolean enabled = !t.containsKey("enabled") || Boolean.TRUE.equals(t.get("enabled"));
        sb.append("<div class=\"form-check form-switch\"><input class=\"form-check-input\" type=\"checkbox\" "
                + "role=\"switch\" id=\"").append(uid).append("-en\" name=\"enabled\" value=\"true\"")
                .append(enabled ? " checked" : "").append("><label class=\"form-check-label\" for=\"").append(uid)
                .append("-en\">Actif (de nouvelles rencontres peuvent apparaître)</label></div>");
        sb.append("<details class=\"npc-registry\"><summary>Paramètres avancés</summary>");
        sb.append(encounterField(uid, "description", "Description", cleanNull(str(t.get("description"))), "", 256,
                false, "Pour les administrateurs, sans effet en jeu."));
        sb.append(encounterField(uid, "worlds", "Mondes", String.join(", ",
                        asList(t.get("worlds")).stream().map(AgentPages::str).toList()), "vide = tous", 256, false,
                "Restreint aux mondes nommés, parmi ceux déjà autorisés (Hub et claims toujours exclus)."));
        sb.append(encounterField(uid, "weight", "Poids du tirage", cleanNull(str(t.get("weight"))), "10", 4, false,
                "1 à 1000, quand plusieurs modèles acceptent le même biome."));
        sb.append(encounterField(uid, "max_sites", "Plafond d'occurrences", cleanNull(str(t.get("maxSites"))), "0",
                6, false, "0 = pas de plafond propre."));
        sb.append(encounterField(uid, "skin_url", "Apparence (lien MineSkin)", cleanNull(str(t.get("skinUrl"))),
                "https://minesk.in/…", 120, false, null));
        sb.append("</details>");
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm\" type=\"submit\">").append(Icons.icon("save"))
                .append(create ? "Créer le modèle" : "Enregistrer").append("</button></div></form>");
        return sb.toString();
    }

    private static String encounterField(String uid, String name, String label, String value, String placeholder,
                                         int max, boolean required, String help) {
        String fid = uid + "-" + name.replace('_', '-');
        return "<label for=\"" + fid + "\">" + Http.esc(label) + "</label><input class=\"form-control\" type=\"text\" "
                + "id=\"" + fid + "\" name=\"" + name + "\" maxlength=\"" + max + "\" value=\"" + Http.esc(value)
                + "\" placeholder=\"" + Http.esc(placeholder) + "\"" + (required ? " required" : "") + ">"
                + (help == null ? "" : "<p class=\"form-text\">" + Http.esc(help) + "</p>");
    }

    private String encounterSites(Session session, String agentId, Map<String, Object> d, boolean canSpawn) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">")
                .append(Ui.sectionTitle("travel", "Occurrences par monde"));
        List<Object> sites = asList(d.get("sites"));
        if (sites.isEmpty()) {
            return sb.append("<p class=\"muted\">Aucune rencontre n'est encore apparue : elles naissent quand un "
                    + "joueur explore une nouvelle zone de biome.</p></section>").toString();
        }
        Map<String, List<Map<String, Object>>> byWorld = new java.util.TreeMap<>();
        for (Object o : sites) {
            Map<String, Object> s = asMap(o);
            byWorld.computeIfAbsent(str(s.get("world")), w -> new ArrayList<>()).add(s);
        }
        int i = 0;
        int worldIndex = 0;
        for (Map.Entry<String, List<Map<String, Object>>> world : byWorld.entrySet()) {
            sb.append("<h3 class=\"h6 mt-3\">").append(Http.esc(worldLabel(world.getKey()))).append(" — ")
                    .append(world.getValue().size()).append("</h3>");
            String listId = "enc-sites-" + worldIndex++;
            if (world.getValue().size() >= ENCOUNTER_FILTER_THRESHOLD) {
                sb.append(encounterFilter(listId, "Filtrer les occurrences (identifiant, modèle, biome…)"));
            }
            sb.append("<div class=\"enc-list enc-sites\" id=\"").append(listId).append("\">")
                    .append("<div class=\"enc-head\" aria-hidden=\"true\"><span>Occurrence</span><span>Modèle</span>"
                            + "<span>Position</span><span>PNJ en jeu</span><span>État</span><span></span></div>");
            for (Map<String, Object> s : world.getValue()) {
                sb.append(encounterSiteRow(session, agentId, s, canSpawn, i++, listId));
            }
            sb.append("</div>");
        }
        return sb.append("</section>").toString();
    }

    private String encounterSiteRow(Session session, String agentId, Map<String, Object> s, boolean canSpawn,
                                    int index, String listId) {
        String id = str(s.get("id"));
        String pos = str(s.get("x")) + " " + str(s.get("y")) + " " + str(s.get("z"));
        boolean active = "ACTIVE".equals(str(s.get("status")));
        Object present = s.get("citizensPresent");
        String citizens = cleanNull(str(s.get("citizensUuid"))).isEmpty()
                ? "<span class=\"badge text-bg-warning\">aucun</span>"
                : "#" + Http.esc(str(s.get("citizensId"))) + " " + (Boolean.TRUE.equals(present)
                        ? "<span class=\"badge text-bg-success\">présent</span>"
                        : Boolean.FALSE.equals(present) ? "<span class=\"badge text-bg-danger\">disparu</span>"
                        : "<span class=\"badge text-bg-secondary\">inconnu</span>");
        citizens += encounterAppearance(cleanNull(str(s.get("appearance"))));
        String error = cleanNull(str(s.get("lastError")));
        String biome = str(s.get("biomeKey")).replace("minecraft:", "");
        StringBuilder sb = new StringBuilder("<div class=\"enc-row\" data-filter-item=\"").append(listId)
                .append("\" data-filter-text=\"")
                .append(Http.esc(String.join(" ", id, str(s.get("templateId")), biome).toLowerCase(java.util.Locale.ROOT)))
                .append("\">")
                .append(encounterCell("Occurrence", "enc-main", Ui.id(id) + "<br><small class=\"muted\">"
                        + Http.esc(biome) + "</small>"))
                .append(encounterCell("Modèle", "", Ui.id(str(s.get("templateId")))))
                .append(encounterCell("Position", "", "<code>" + Http.esc(pos) + "</code><br><small class=\"muted\">"
                        + "Localiser : <code>/tp @s " + Http.esc(pos) + "</code></small>"))
                .append(encounterCell("PNJ en jeu", "", citizens))
                .append(encounterCell("État", "", (active ? "<span class=\"badge text-bg-success\">active</span>"
                        : "<span class=\"badge text-bg-secondary\">désactivée</span>")
                        + (error.isEmpty() ? "" : "<br><small class=\"text-danger-emphasis\">" + Http.esc(error)
                        + "</small>")))
                .append("<div class=\"enc-c enc-act\">");
        if (!canSpawn) {
            return sb.append("<span class=\"muted\">lecture seule</span></div></div>").toString();
        }
        String target = "enc-act-" + index;
        sb.append(actionToggle(target, "Gérer", "wrench", "btn-outline-secondary")).append("</div>")
                .append("<div class=\"collapse enc-panel\" id=\"").append(target).append("\"><div class=\"enc-site-acts\">")
                .append(encounterSiteForm(session, agentId, "encounter.retry", id,
                        active ? "Retenter maintenant" : "Réactiver",
                        "Retenter l'apparition de " + id, "btn-outline-primary"));
        if (active) {
            sb.append(encounterSiteForm(session, agentId, "encounter.disable", id, "Désactiver",
                    "Désactiver " + id + " (son PNJ est retiré)", "btn-outline-warning"));
        }
        sb.append(encounterSiteForm(session, agentId, "encounter.remove", id, "Retirer",
                "Retirer " + id + " : PNJ et occurrence supprimés, la zone pourra en accueillir une nouvelle",
                "btn-outline-danger"));
        return sb.append("</div></div></div>").toString();
    }

    /** État de l'apparence relevé par le serveur depuis son démarrage (vide = rien tenté depuis). */
    private static String encounterAppearance(String state) {
        if (state.isEmpty()) {
            return "";
        }
        String label = switch (state) {
            case "APPLIED" -> "apparence du modèle";
            case "DEFAULT" -> "apparence par défaut";
            case "PENDING" -> "apparence en cours";
            case "NOT_PLAYER" -> "pas de skin (PNJ non joueur)";
            default -> state.startsWith("FAILED") ? "apparence en échec" + state.substring("FAILED".length()) : state;
        };
        return "<br><small class=\"" + (state.startsWith("FAILED") ? "text-danger-emphasis" : "muted") + "\">"
                + Http.esc(label) + "</small>";
    }

    private String encounterSiteForm(Session session, String agentId, String type, String siteId, String label,
                                     String confirm, String btnClass) {
        return formStart(session, agentId, type, "/npcs/encounters", "")
                + "<input type=\"hidden\" name=\"siteId\" value=\"" + Http.esc(siteId) + "\">"
                + confirmBox(confirm)
                + "<div class=\"btnrow\"><button class=\"btn btn-sm " + btnClass + "\" type=\"submit\">"
                + Http.esc(label) + "</button></div></form>";
    }

    private String encounterEvents(Map<String, Object> d) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">")
                .append(Ui.sectionTitle("diagnostics", "Journal récent"));
        List<Object> events = asList(d.get("events"));
        if (events.isEmpty()) {
            return sb.append("<p class=\"muted\">Aucun événement depuis le dernier démarrage du serveur.</p></section>")
                    .toString();
        }
        sb.append("<p class=\"muted\">En mémoire seulement : vidé à chaque redémarrage du serveur.</p>");
        sb.append(Ui.tableOpen("Quand", "Monde", "Résultat", "Détail"));
        for (Object o : events) {
            Map<String, Object> e = asMap(o);
            sb.append("<tr><td><small>").append(Http.esc(str(e.get("at")))).append("</small></td><td>")
                    .append(Http.esc(str(e.get("world")))).append("</td><td>").append(Ui.badge(str(e.get("outcome"))))
                    .append("</td><td><small>").append(Http.esc(str(e.get("detail")))).append("</small></td></tr>");
        }
        return sb.append(Ui.tableClose()).append("</section>").toString();
    }

    public String npcDiagnostic(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("wrench", "Diagnostic des PNJ",
                "Pour comprendre un PNJ signalé « à vérifier » : comment RPGQuest et le jeu se répondent.",
                "<a class=\"btn secondary\" href=\"/npcs\">" + Icons.icon("back") + "Retour aux PNJ</a>"));
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        sb.append("<div class=\"npc-catbar\">").append(npcRefreshAllForm(session, agentId, "/npcs/diagnostic"))
                .append("</div>");
        sb.append("<section class=\"card\">").append(Ui.sectionTitle("docs", "À quoi sert cette page"))
                .append("<p class=\"muted\">Un PNJ existe à deux endroits : dans RPGQuest (sa fiche : nom, "
                        + "dialogue, quêtes) et dans le jeu (le personnage géré par Citizens). Cette page montre "
                        + "comment les deux se correspondent, et les messages techniques du serveur. "
                        + "<a href=\"/docs/pnj-depannage\">Fiche de dépannage</a>.</p></section>");

        Optional<Map<String, Object>> details = latestDetails(agentId, "npc.list");
        Optional<Map<String, Object>> citizensCat = latestDetails(agentId, "npc.citizens.list");
        if (details.isEmpty()) {
            return sb.append(Ui.empty("npc", "Rien n'a encore été relevé : cliquez sur « Actualiser les PNJ ».")).toString();
        }
        Map<String, Object> d = details.get();
        sb.append("<section class=\"card\">").append(Ui.sectionTitle("diagnostics", "Compteurs")).append("<dl class=\"npc-dl\">");
        dlRow(sb, "Entrées du catalogue", Http.esc(str(d.get("total"))));
        dlRow(sb, "Avec fiche RPGQuest", Http.esc(str(d.get("withDefinition"))));
        dlRow(sb, "Sans fiche RPGQuest", Http.esc(str(d.get("withoutDefinition"))));
        dlRow(sb, "Liés à un personnage du jeu", Http.esc(str(d.get("bound"))));
        dlRow(sb, "Avec un message", Http.esc(str(d.get("withWarnings"))));
        citizensCat.ifPresent(cc -> {
            dlRow(sb, "Personnages Citizens", Http.esc(str(cc.get("total"))));
            dlRow(sb, "… dont non rattachés", Http.esc(str(cc.get("available"))));
        });
        surveyAge(agentId, "npc.list").ifPresent(age -> dlRow(sb, "Relevé du catalogue", Http.esc(age)));
        surveyAge(agentId, "npc.citizens.list").ifPresent(age -> dlRow(sb, "Relevé des personnages", Http.esc(age)));
        sb.append("</dl></section>");

        sb.append("<section class=\"card\">").append(Ui.sectionTitle("warning", "Messages du serveur"));
        boolean any = false;
        for (Object o : asList(d.get("npcs"))) {
            Map<String, Object> n = asMap(o);
            List<Object> warnings = asList(n.get("warnings"));
            if (warnings.isEmpty()) {
                continue;
            }
            any = true;
            String id = str(n.get("id"));
            String name = cleanNull(str(n.get("displayName")));
            sb.append("<p class=\"npc-section\">").append(Icons.icon("npc"))
                    .append(Http.esc(name.isEmpty() ? id : MiniText.plain(name))).append(" ")
                    .append(Ui.id(id)).append(" ").append(npcStateBadge(str(n.get("state")))).append("</p>");
            for (Object w : warnings) {
                Map<String, Object> wm = asMap(w);
                sb.append(DiagnosticHelp.render(str(wm.get("code")), str(wm.get("severity")), str(wm.get("message")),
                        name.isEmpty() ? id : MiniText.plain(name), "", ""));
            }
        }
        if (!any) {
            sb.append("<p class=\"muted\">").append(Icons.icon("check")).append("Aucun message.</p>");
        }
        sb.append("</section>");

        List<Object> roster = citizensCat.map(x -> asList(x.get("citizens"))).orElse(List.of());
        sb.append(npcDuplicateNamesSection(NpcDisplayNames.fromSurvey(asList(d.get("npcs")), roster)));

        sb.append("<section class=\"card\">").append(Ui.sectionTitle("link", "Rapprochement avec le jeu"));
        if (roster.isEmpty()) {
            sb.append("<p class=\"muted\">Aucun personnage Citizens relevé.</p>");
        } else {
            sb.append(Ui.tableOpen("Citizens", "Nom en jeu", "Fiche RPGQuest", "Présent"));
            for (Object o : roster) {
                Map<String, Object> c = asMap(o);
                String linked = cleanNull(str(c.get("linkedNpcId")));
                String encounter = cleanNull(str(c.get("encounterSiteId")));
                sb.append("<tr><td>#").append(Http.esc(str(c.get("numericId")))).append("</td><td>")
                        .append(MiniText.html(cleanName(c.get("name")))).append("</td><td>")
                        .append(!linked.isEmpty() ? Ui.id(linked)
                                : !encounter.isEmpty() ? "<span class=\"badge text-bg-info\">PNJ récurrent</span> "
                                        + Ui.id(encounter)
                                : "<span class=\"badge text-bg-warning\">aucune</span>")
                        .append("</td><td>").append(Boolean.TRUE.equals(c.get("spawned")) ? "oui" : "non")
                        .append("</td></tr>");
            }
            sb.append(Ui.tableClose());
        }
        sb.append("</section>");

        sb.append("<section class=\"card\">").append(Ui.sectionTitle("docs", "Registre des identifiants"))
                .append("<p class=\"muted\">Identifiants déclarés par une fiche, puis tous ceux que le contenu "
                        + "cite. Un identifiant cité sans fiche est une référence à corriger.</p>");
        sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Fiches</span> ");
        for (Object c : asList(d.get("definedIds"))) {
            sb.append(Ui.id(str(c))).append(' ');
        }
        sb.append("</p><p class=\"meta-line\"><span class=\"meta-k\">Cités</span> ");
        for (Object c : asList(d.get("canonicalIds"))) {
            sb.append(Ui.id(str(c))).append(' ');
        }
        sb.append("</p></section>");
        return sb.toString();
    }

    /**
     * Issue #125 — les noms affichés portés par plusieurs PNJ, avec les identifiants en conflit.
     * Un avertissement : la collision d'identifiant, elle, arrive du moteur dans « Messages du serveur ».
     */
    private static String npcDuplicateNamesSection(NpcDisplayNames names) {
        StringBuilder sb = new StringBuilder("<section class=\"card\" id=\"npc-dup-names\">")
                .append(Ui.sectionTitle("npc", "Noms affichés en double"));
        List<NpcDisplayNames.Group> groups = names.duplicates();
        if (groups.isEmpty()) {
            return sb.append("<p class=\"muted\">").append(Icons.icon("check"))
                    .append("Chaque PNJ porte un nom différent.</p></section>").toString();
        }
        for (NpcDisplayNames.Group g : groups) {
            StringBuilder ids = new StringBuilder();
            for (String id : g.ids()) {
                ids.append(Ui.id(id)).append(' ');
            }
            sb.append("<p class=\"npc-section\">").append(Icons.icon("npc")).append(Http.esc(g.name()))
                    .append(" ").append(ids).append("</p>");
            sb.append(DiagnosticHelp.render("DUPLICATE_DISPLAY_NAME", "warning", "", g.name(),
                    String.join(", ", g.ids()), ""));
        }
        return sb.append("</section>").toString();
    }

    private static String npcStateBadge(String state) {
        String s = state == null ? "" : state;
        return switch (s) {
            case "LINKED" -> Ui.pill("lié", "success", "✓");
            case "NOT_LINKED" -> Ui.pill("pas dans le monde", "pending", "○");
            case "DISABLED" -> Ui.pill("désactivé", "expired", "⧖");
            case "CITIZENS_ORPHAN" -> Ui.pill("sans fiche", "failed", "✕");
            case "CITIZENS_ONLY" -> Ui.pill("Citizens seul", "pending", "○");
            case "UNDEFINED_REFERENCE" -> Ui.pill("cité sans fiche", "failed", "✕");
            case "BROKEN" -> Ui.pill("cassé", "failed", "✕");
            default -> Ui.badge(s.isEmpty() ? "?" : s);
        };
    }

    // ---- Outils partagés (aussi utilisés par /quests, /stories, /dialogues…) ------------------

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
     * (issue #145).
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

    private static String detailSection(String icon, String title) {
        return "<p class=\"npc-section\">" + Icons.icon(icon) + Http.esc(title) + "</p>";
    }

    private static void dlRow(StringBuilder sb, String k, String vHtml) {
        sb.append("<div class=\"npc-dl-row\"><dt>").append(Http.esc(k)).append("</dt><dd>").append(vHtml).append("</dd></div>");
    }

    private static String actionToggle(String targetId, String label, String icon, String btnClass) {
        return "<button class=\"btn " + (btnClass.startsWith("btn-") ? "btn-sm " : "") + btnClass + "\" type=\"button\" data-bs-toggle=\"collapse\" "
                + "data-bs-target=\"#" + targetId + "\" aria-expanded=\"false\" aria-controls=\"" + targetId + "\">"
                + Icons.icon(icon) + Http.esc(label) + "</button>";
    }

    private static String actionCollapse(String id, String inner) {
        return "<div class=\"collapse npc-form-collapse\" id=\"" + id + "\">" + inner + "</div>";
    }

    /** La ligne du relevé Citizens correspondant à ce PNJ, ou {@code null}. */
    private static Map<String, Object> citizensRowOf(List<Object> citizensRoster, String npcId, String numericId) {
        for (Object o : citizensRoster) {
            Map<String, Object> c = asMap(o);
            if (str(c.get("linkedNpcId")).equalsIgnoreCase(npcId)) {
                return c;
            }
        }
        for (Object o : citizensRoster) {
            Map<String, Object> c = asMap(o);
            if (numericId != null && !numericId.isEmpty() && numericId.equals(str(c.get("numericId")))) {
                return c;
            }
        }
        return null;
    }

    /** Coordonnée arrondie au dixième, ou vide si absente. */
    private static String coord(Object raw) {
        String v = str(raw);
        if (v.isEmpty() || "null".equals(v)) {
            return "";
        }
        try {
            return String.format(java.util.Locale.ROOT, "%.1f", Double.parseDouble(v));
        } catch (NumberFormatException e) {
            return Http.esc(v);
        }
    }

    private static String cleanName(Object raw) {
        String name = str(raw);
        return "null".equals(name) ? "" : name;
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

    private static String cleanNull(String value) {
        return value == null || "null".equals(value) ? "" : value;
    }

    // ================================================================================
    //  Dialogues (V1 lecture + squelette de création — base d'un futur éditeur)
    // ================================================================================

    // ---- Issue #335 : paramètres généraux -----------------------------------------------------

    /** Issue #335 — le nom de la monnaie relu sur le serveur, pour les pages qui affichent de l'argent. */
    public void refreshCurrency(String agentId) {
        if (agentId == null) {
            return;
        }
        AgentActionRow best = null;
        for (String type : List.of("settings.get", "settings.set")) {
            Optional<AgentActionRow> row = store.latestSuccessfulActionOfType(agentId, type);
            if (row.isPresent() && (best == null || (row.get().completedAt() != null && best.completedAt() != null
                    && row.get().completedAt().isAfter(best.completedAt())))) {
                best = row.get();
            }
        }
        if (best != null) {
            detailsOf(best).map(d -> str(asMap(d.get("values")).get("currency_name"))).ifPresent(RewardText::useCurrency);
        }
    }

    /**
     * Ce que le panel sait des paramètres généraux : la lecture ({@code settings.get}) ou l'écriture
     * relue ({@code settings.set}) réussie la plus récente, plus l'état d'un enregistrement en cours ou
     * refusé depuis.
     */
    public SettingsPages.State settingsState(String agentId) {
        Map<String, String> values = Map.of();
        Map<String, String> defaults = Map.of();
        Instant readAt = null;
        String updatedAt = "";
        String updatedBy = "";
        String problem = "";
        boolean pending = false;
        String lastError = "";
        if (agentId != null) {
            boolean sawNewerFailure = false;
            for (AgentActionRow row : store.recentActions(agentId, 200)) {
                if (!"settings.get".equals(row.type()) && !"settings.set".equals(row.type())) {
                    continue;
                }
                if (!row.status().terminal()) {
                    pending |= "settings.set".equals(row.type());
                    continue;
                }
                Optional<Map<String, Object>> d = detailsOf(row);
                if (row.status() != AgentActionStatus.SUCCESS) {
                    if ("settings.set".equals(row.type()) && !sawNewerFailure && readAt == null) {
                        lastError = row.resultMessage() == null ? "" : row.resultMessage();
                        sawNewerFailure = true;
                    }
                    if (d.isEmpty()) {
                        continue;
                    }
                }
                if (d.isPresent() && !asMap(d.get().get("values")).isEmpty()) {
                    values = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> e : asMap(d.get().get("values")).entrySet()) {
                        values.put(e.getKey(), str(e.getValue()));
                    }
                    defaults = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> e : asMap(d.get().get("defaults")).entrySet()) {
                        defaults.put(e.getKey(), str(e.getValue()));
                    }
                    readAt = row.completedAt() != null ? row.completedAt() : row.createdAt();
                    updatedAt = cleanNull(str(d.get().get("updatedAt")));
                    updatedBy = cleanNull(str(d.get().get("updatedBy")));
                    problem = cleanNull(str(d.get().get("problem")));
                    break;
                }
            }
        }
        if (!values.isEmpty()) {
            RewardText.useCurrency(values.get("currency_name"));
        }
        return new SettingsPages.State(values, defaults, readAt, updatedAt, updatedBy, problem, pending, lastError);
    }

    // ---- Issue #332 : dialogues, version simple — données préparées pour DialoguePages ------

    /** Personnages, quêtes et état « en jeu » dont les pages de dialogue ont besoin. */
    public DialoguePages.Ctx dialogueCtx(String agentId) {
        List<DialoguePages.Npc> npcs = new ArrayList<>();
        Optional<Map<String, Object>> npcDet = agentId == null ? Optional.empty() : latestDetails(agentId, "npc.list");
        for (Object o : npcDet.map(d -> asList(d.get("npcs"))).orElse(List.of())) {
            Map<String, Object> n = asMap(o);
            String id = str(n.get("id"));
            if (id.isEmpty() || !Boolean.TRUE.equals(n.get("logicalDefinitionPresent"))) {
                continue;
            }
            String defined = cleanNull(str(n.get("definedDialogueId")));
            String effective = defined.isEmpty() ? cleanNull(str(n.get("dialogueId"))) : defined;
            String name = cleanNull(str(n.get("displayName")));
            npcs.add(new DialoguePages.Npc(id, name.isEmpty() ? id : name, dialoguePlainKey(effective)));
        }
        com.lodygames.rpgquest.panel.content.RefData ref = referenceData(agentId);
        List<DialoguePages.Quest> quests = new ArrayList<>();
        java.util.Set<String> seenQuests = new java.util.HashSet<>();
        for (String q : ref.quests()) {
            if (seenQuests.add(QuestYaml.plainId(q))) {
                quests.add(new DialoguePages.Quest(q.contains(":") ? q : "rpgquest:" + q, ref.questLabel(q)));
            }
        }
        java.util.Set<String> serverKeys = new java.util.LinkedHashSet<>();
        if (agentId != null) {
            for (Object o : latestDetails(agentId, "dialogue.list").map(x -> asList(x.get("dialogues"))).orElse(List.of())) {
                Map<String, Object> m = asMap(o);
                String key = dialoguePlainKey(str(m.get("id")));
                serverKeys.add(key.isEmpty() ? dialoguePlainKey(str(m.get("key"))) : key);
            }
        }
        // #430 : les listes de quêtes et les modèles de PNJ récurrents, depuis le relevé des catalogues.
        List<DialoguePages.Catalog> catalogs = new ArrayList<>();
        List<DialoguePages.Model> models = new ArrayList<>();
        Optional<Map<String, Object>> catDet = agentId == null ? Optional.empty()
                : latestDetails(agentId, "quest.catalog.list");
        QuestCatalogPages.Survey survey = catDet.map(QuestCatalogPages::parse).orElse(null);
        if (survey != null && survey.available()) {
            for (QuestCatalogPages.Catalog c : survey.catalogs()) {
                catalogs.add(new DialoguePages.Catalog(c.id(), c.name(), c.enabled(),
                        c.entries().stream().filter(QuestCatalogPages.Entry::offered)
                                .map(QuestCatalogPages.Entry::quest).toList(), c.usedBy()));
            }
            for (QuestCatalogPages.Template t : survey.templates() == null ? List.<QuestCatalogPages.Template>of()
                    : survey.templates()) {
                models.add(new DialoguePages.Model(t.id(), t.name(), t.npcId(), t.enabled(), t.catalogs()));
            }
        }
        // #17 : les catalogues de marchands, pour l'action « Ouvrir un marchand ».
        List<DialoguePages.Merchant> merchants = new ArrayList<>();
        if (agentId != null) {
            latestDetails(agentId, "merchant.catalog.status").filter(x -> Boolean.TRUE.equals(x.get("available")))
                    .ifPresent(x -> asList(x.get("merchants")).forEach(o -> {
                        Map<String, Object> m = asMap(o);
                        merchants.add(new DialoguePages.Merchant(str(m.get("id")), str(m.get("title")),
                                str(m.get("type")), !Boolean.FALSE.equals(m.get("enabled"))));
                    }));
        }
        return new DialoguePages.Ctx(agentId == null ? "" : agentId, List.copyOf(npcs), List.copyOf(quests),
                java.util.Set.copyOf(serverKeys), npcDet.isPresent(), slug -> dialogueLive(agentId, slug),
                List.copyOf(catalogs), List.copyOf(models), survey != null && survey.available(),
                List.copyOf(merchants));
    }

    /**
     * Un dialogue qui n'existe que sur le serveur, reconstitué depuis le dernier relevé pour être
     * <strong>lu</strong> dans l'éditeur (jamais enregistré : il n'a pas de fichier dans PlugAdmin).
     */
    public Optional<com.lodygames.rpgquest.panel.content.DialogueDraft> serverDialogueDraft(String agentId, String slug) {
        for (Object o : latestDetails(agentId, "dialogue.list").map(x -> asList(x.get("dialogues"))).orElse(List.of())) {
            Map<String, Object> m = asMap(o);
            String key = dialoguePlainKey(str(m.get("id")));
            if (!slug.equals(key.isEmpty() ? dialoguePlainKey(str(m.get("key"))) : key)) {
                continue;
            }
            var d = new com.lodygames.rpgquest.panel.content.DialogueDraft();
            d.id = slug;
            d.start = str(m.get("startNodeId"));
            for (Object no : asList(m.get("nodes"))) {
                Map<String, Object> nm = asMap(no);
                var node = new com.lodygames.rpgquest.panel.content.DialogueDraft.Node(str(nm.get("id")));
                node.speaker = str(nm.get("speaker"));
                node.text = str(nm.get("text"));
                for (Object co : asList(nm.get("choices"))) {
                    Map<String, Object> cm = asMap(co);
                    var c = new com.lodygames.rpgquest.panel.content.DialogueDraft.Choice();
                    c.text = str(cm.get("text"));
                    c.next = cleanNull(str(cm.get("nextNodeId")));
                    for (Object ao : asList(cm.get("actions"))) {
                        c.actions.add(runtimeEntry(asMap(ao), false));
                    }
                    for (Object qo : asList(cm.get("conditions"))) {
                        c.conditions.add(runtimeEntry(asMap(qo), true));
                    }
                    c.close = c.actions.stream().anyMatch(a -> "CLOSE".equals(a.get("type")));
                    node.choices.add(c);
                }
                d.nodes.add(node);
            }
            return Optional.of(d);
        }
        return Optional.empty();
    }

    private static Map<String, String> runtimeEntry(Map<String, Object> m, boolean condition) {
        Map<String, String> e = new LinkedHashMap<>();
        String kind = str(m.get("kind"));
        e.put("type", kind);
        String target = cleanNull(str(m.get("target")));
        if (!target.isEmpty()) {
            e.put(kind.contains("QUEST") ? "quest" : "target", target);
        }
        String value = cleanNull(str(m.get("value")));
        if (!value.isEmpty()) {
            e.put("QUEST_STATE".equals(kind) ? "state" : "value", value);
        }
        if (condition && Boolean.TRUE.equals(m.get("negated"))) {
            e.put("negate", "true");
        }
        return e;
    }

    /** Les dialogues de la liste : ceux de la source, puis ceux qui ne sont que sur le serveur. */
    public List<DialoguePages.Row> dialogueRows(DialoguePages.Ctx ctx) {
        List<DialoguePages.Row> rows = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (SourceCatalog.DialogueSource ds : sourceCatalog.dialogues()) {
            if (seen.add(ds.slug())) {
                rows.add(new DialoguePages.Row(ds.slug(), ds.draft(), ds.parseOk()));
            }
        }
        for (String key : ctx.serverKeys()) {
            if (!key.isEmpty() && seen.add(key)) {
                rows.add(new DialoguePages.Row(key, null, true));
            }
        }
        rows.sort((a, b) -> DialoguePages.title(a.key(), a.draft(), ctx.users(a.key()))
                .compareToIgnoreCase(DialoguePages.title(b.key(), b.draft(), ctx.users(b.key()))));
        return rows;
    }

    /**
     * Où en est le dialogue enregistré par rapport au jeu (issue #332). Même vérité que la section
     * « Publication sur DEV » ({@link #publishSection}) : relevé DEV réconcilié avec la dernière
     * publication. Un envoi en cours n'est jamais un succès, et un échec ne compte que pour la
     * version enregistrée qu'il concernait.
     */
    public DialoguePages.LiveState dialogueLive(String agentId, String slug) {
        String sourceSha = sourceCatalog.sha("dialogues", slug).orElse("");
        if (agentId == null || agentId.isBlank()) {
            return new DialoguePages.LiveState(DialoguePages.Live.UNKNOWN, "", sourceSha, "", null);
        }
        var devIndex = devContentIndex(agentId);
        Optional<Map<String, Object>> lastPublish = lastPublishOf(agentId, "dialogues", slug);
        String lastDevSha = lastPublish.map(d -> str(d.get("devShaAfter"))).orElse("");
        boolean stale = readingIsStale(agentId, "dialogues", slug);
        String expectedDev = stale && !lastDevSha.isEmpty() ? lastDevSha : devIndex.devSha("dialogues", slug);

        for (AgentActionRow row : store.recentActions(agentId, 200)) {
            if (!"content.publish".equals(row.type()) || !"dialogues".equals(row.params().get("kind"))
                    || !slug.equals(row.params().get("id"))) {
                continue;
            }
            Instant when = row.completedAt() != null ? row.completedAt() : row.createdAt();
            if (!row.status().terminal()) {
                return new DialoguePages.LiveState(DialoguePages.Live.PUBLISHING, "", sourceSha, expectedDev, when);
            }
            Optional<Map<String, Object>> details = detailsOf(row);
            boolean ok = row.status() == com.lodygames.rpgquest.panel.agent.AgentActionStatus.SUCCESS
                    && details.map(d -> Boolean.TRUE.equals(d.get("ok"))).orElse(false);
            String forSource = row.params().getOrDefault("expected_source_sha", "");
            if (!ok && (forSource.isEmpty() || forSource.equals(sourceSha))) {
                String msg = row.resultMessage() == null ? "" : MiniText.prettifyTokens(row.resultMessage());
                return new DialoguePages.LiveState(DialoguePages.Live.FAILED,
                        msg.length() > 240 ? msg.substring(0, 239) + "…" : msg, sourceSha,
                        expectedDev, when);
            }
            break;
        }
        if (sourceSha.isEmpty()) {
            return new DialoguePages.LiveState(devIndex.available() || !latestDetails(agentId, "dialogue.list").isEmpty()
                    ? DialoguePages.Live.SERVER_ONLY : DialoguePages.Live.UNKNOWN, "", "", expectedDev, null);
        }
        var readState = devIndex.stateOf("dialogues", slug, "rpgquest:" + slug, sourceSha, sourceCatalog.available(),
                lastDevSha);
        var state = com.lodygames.rpgquest.panel.publish.PublishState.reconciled(readState, stale,
                lastPublish.map(d -> Boolean.TRUE.equals(d.get("ok"))).orElse(false),
                lastPublish.map(d -> Boolean.TRUE.equals(d.get("runtimeConfirmed"))).orElse(false),
                lastDevSha, sourceSha);
        Instant when = lastPublishAt(agentId, "dialogues", slug).orElse(null);
        return switch (state) {
            case SYNCED -> new DialoguePages.LiveState(DialoguePages.Live.UP_TO_DATE, "", sourceSha, expectedDev, when);
            case SOURCE_ONLY, DIFFERENT -> new DialoguePages.LiveState(DialoguePages.Live.NOT_LIVE, "", sourceSha,
                    expectedDev, when);
            case NOT_LOADED -> new DialoguePages.LiveState(DialoguePages.Live.FAILED,
                    "le serveur a reçu le dialogue mais ne l'a pas chargé", sourceSha, expectedDev, when);
            case CONFLICT -> new DialoguePages.LiveState(DialoguePages.Live.CONFLICT, "", sourceSha, expectedDev, when);
            case RUNTIME_ONLY -> new DialoguePages.LiveState(DialoguePages.Live.SERVER_ONLY, "", sourceSha, expectedDev, when);
            case UNKNOWN -> new DialoguePages.LiveState(DialoguePages.Live.UNKNOWN, "", sourceSha, expectedDev, when);
        };
    }

    public String dialogues(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder();
        boolean canWrite = perms.can(session.effective(), Permission.DIALOGUE_WRITE);
        sb.append(Ui.pageHeader("dialogues", "Dialogues — détails techniques",
                "Relevé du serveur, diagnostics, publication détaillée et édition directe des "
                        + "dialogues chargés en jeu. Pour modifier un dialogue, utilisez plutôt la page "
                        + "« Dialogues ».",
                "<a class=\"btn secondary\" href=\"/dialogues\">" + Icons.icon("back") + "Dialogues</a>"
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
            dlgBar += compactRefresh(session, agentId, "content.dev.state", "État DEV",
                    devContentIndex(agentId).available() ? "btn-outline-secondary" : "btn-warning",
                    "/dialogues");
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
            sb.append("<p class=\"form-text dlg-editnote\">Édition guidée des nœuds, des textes et des choix des "
                    + "dialogues chargés par le serveur — y compris le démarrage de quête et la condition d'état "
                    + "d'un choix. Les actions et conditions que cette page n'expose pas sont conservées à "
                    + "l'identique et listées sous le choix concerné. ")
                    .append(docLink("dialogues", "En savoir plus sur les limites de l'éditeur")).append("</p>");
            sb.append("<details class=\"tech-detail\"><summary>Détails techniques de l'éditeur</summary>"
                    + "<p class=\"muted\">La création écrit <code>dialogues/&lt;id&gt;.yml</code> dans la source au "
                    + "<strong>format canonique</strong> du panel (les commentaires et la mise en forme d'origine ne "
                    + "sont pas conservés). L'édition guidée d'un dialogue déjà chargé le re-parse et le recharge ; "
                    + "en cas d'échec, le contenu d'origine est restauré. Chaque enregistrement repart du fichier "
                    + "réel : une propriété absente du formulaire n'est jamais effacée.</p></details>");
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
                sb.append(renderDialogueAccordionItem(mr.data(), di++, questTitles, session, agentId,
                        canWrite, mr.state(), devContentIndex(agentId)));
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
                                               Session session, String agentId, boolean canWrite, CatalogState state,
                                               com.lodygames.rpgquest.panel.publish.DevContentIndex devIndex) {
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

        // ---- PUBLICATION SUR DEV (issue #47) ----

        // La publication copie le fichier source TEL QUEL : elle ne passe jamais par MiniYaml,

        // donc elle ne peut pas normaliser un scalaire replié au passage. C'est l'éditeur guidé

        // qui réécrit le YAML, et c'est lui qui porte cette dette — pas le transfert.

        String dialogueSlug = plainKey(key.isEmpty() ? id : key);

        if (!dialogueSlug.isEmpty()) {

            sb.append(publishSection(session, agentId, "dialogues", dialogueSlug,

                    id.isEmpty() ? dialogueSlug : id, devIndex, "/dialogues"));

        }


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
        // Issue #248 — lien vers l'APERÇU, jamais une suppression directe : rien ne se supprime sans
        // avoir vu les dépendances (source ET serveur) et retapé l'identifiant. Proposé aussi pour un
        // dialogue « hors source » : c'est précisément le cas qu'on ne pouvait pas nettoyer.
        if (!dialogueSlug.isEmpty() && session != null
                && perms.can(session.effective(), Permission.CONTENT_DELETE)) {
            if (!canWrite) {
                sb.append(detailSection("target", "Actions"));
            }
            sb.append("<div class=\"npc-actions d-flex flex-wrap gap-2\">")
                    .append("<a class=\"btn btn-sm btn-outline-danger\" href=\"/dialogues/delete?slug=")
                    .append(Http.esc(dialogueSlug)).append("\">").append(Icons.icon("trash"))
                    .append("Supprimer le dialogue…</a></div>");
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
                    sb.append(dialogueChoiceEditForms(session, agentId, dialogueId, nodeId, i, cm, next, nodeIds,
                            questTitles));
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
        sb.append(choiceLabelWidthHint(""));
        sb.append("<label>Nœud cible</label>").append(nodeTargetSelect(nodeIds, ""));
        sb.append("<label class=\"inline\"><input type=\"checkbox\" name=\"close\" value=\"true\"> "
                + "ou : ce choix termine le dialogue (laisser « nœud cible » vide)</label>");
        sb.append(mutationConsent("dialogue.choice.add", "",
                "Ajoute un choix simple (sans condition ni action de quête). Réversible : le choix peut être supprimé."));
        sb.append("<button class=\"btn\" type=\"submit\">Ajouter le choix</button></form>");
        return sb.append("</details>").toString();
    }

    /**
     * Sous un choix : son formulaire d'édition complet. Depuis l'issue #82, un choix portant une
     * action de quête ou une condition d'état n'est plus un cul-de-sac — son texte et sa cible sont
     * modifiables, son action {@code START_QUEST} / {@code ADVANCE_QUEST} / {@code TURN_IN_QUEST} et
     * sa condition {@code QUEST_STATE} s'éditent par des sélecteurs, et <strong>tout le reste est
     * reconduit à l'identique par le moteur</strong> (le formulaire ne porte pas ces propriétés : il
     * ne peut donc pas les écraser). Les propriétés non éditables sont listées explicitement.
     *
     * <p>La suppression reste réservée aux choix sans condition ni action autre que « fermer » :
     * supprimer un choix riche effacerait aussi ses effets de jeu, ce qui doit rester un geste
     * délibéré (retirer d'abord l'action et la condition).</p>
     */
    private String dialogueChoiceEditForms(Session session, String agentId, String dialogueId, String nodeId,
                                           int index, Map<String, Object> choice, String next, List<String> nodeIds,
                                           Map<String, String> questTitles) {
        String sourceText = str(choice.get("text")); // source MiniMessage brute (jamais échappée deux fois)
        boolean simple = choiceIsSimple(choice);
        List<Map<String, Object>> questActions = questActionsOf(choice);
        List<Map<String, Object>> stateConditions = questStateConditionsOf(choice);
        List<String> preserved = preservedChoiceProperties(choice, questTitles);
        // Plus d'une action de quête (ou d'une condition d'état) sur le même choix : le formulaire ne
        // saurait en représenter qu'une, donc il n'y touche pas du tout (« keep ») — jamais de
        // réduction silencieuse de deux effets à un seul.
        boolean actionEditable = questActions.size() <= 1;
        boolean conditionEditable = stateConditions.size() <= 1;

        StringBuilder sb = new StringBuilder("<details class=\"dlg-edit dlg-edit-choice\"><summary>"
                + (simple ? "Modifier / supprimer" : "Modifier ce choix") + "</summary>");
        sb.append(formStart(session, agentId, "dialogue.choice.update", "/dialogues", ""));
        sb.append("<input type=\"hidden\" name=\"dialogue_id\" value=\"").append(Http.esc(dialogueId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"node_id\" value=\"").append(Http.esc(nodeId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"choice_index\" value=\"").append(index).append("\">");

        // Issue #195 : le composant partagé — couleur au clic, styles, aperçu — et sa règle
        // « un texte multi-styles n'est jamais aplati sans geste explicite ».
        sb.append(StyleField.render("choice_text",
                "dlg-choice-" + Http.esc(nodeId).replaceAll("[^a-zA-Z0-9_-]", "-") + "-" + index,
                "Texte du choix", sourceText, true,
                "Libellé du bouton proposé au joueur. Choisir couleur et styles ci-dessus — aucun "
                + "code à écrire."));

        sb.append(choiceLabelWidthHint(sourceText));
        sb.append("<label>Nœud cible</label>").append(nodeTargetSelect(nodeIds, next));
        sb.append("<label class=\"inline\"><input type=\"checkbox\" name=\"close\" value=\"true\"")
                .append(next.isEmpty() ? " checked" : "").append("> ce choix termine le dialogue</label>");

        // ---- Action de quête ----
        if (actionEditable) {
            String kind = questActions.isEmpty() ? "none" : str(questActions.get(0).get("kind")).toLowerCase(Locale.ROOT);
            String target = questActions.isEmpty() ? "" : str(questActions.get(0).get("target"));
            sb.append("<label>Action de quête</label>");
            sb.append("<select name=\"quest_action\">");
            sb.append(option("none", kind, "Aucune"));
            sb.append(option("start_quest", kind, "Démarrer la quête"));
            sb.append(option("advance_quest", kind, "Faire avancer la quête"));
            sb.append(option("turn_in_quest", kind, "Rendre la quête"));
            sb.append("</select>");
            sb.append("<label>Quête concernée</label>").append(questSelect("quest_id", target, questTitles));
        } else {
            sb.append("<input type=\"hidden\" name=\"quest_action\" value=\"keep\">");
            sb.append("<p class=\"faint\" style=\"font-size:12px\">").append(questActions.size())
                    .append(" actions de quête sur ce choix : conservées telles quelles, non modifiables ici "
                            + "(l'éditeur n'en représente qu'une).</p>");
        }

        // ---- Condition d'état de quête ----
        if (conditionEditable) {
            Map<String, Object> cond = stateConditions.isEmpty() ? Map.of() : stateConditions.get(0);
            String state = stateConditions.isEmpty() ? "none" : str(cond.get("value")).toLowerCase(Locale.ROOT);
            String target = str(cond.get("target"));
            sb.append("<label>Condition — état de la quête</label>");
            sb.append("<select name=\"quest_condition\">");
            sb.append(option("none", state, "Aucune condition"));
            for (String s : QUEST_STATES) {
                sb.append(option(s.toLowerCase(Locale.ROOT), state, questStateLabel(s)));
            }
            sb.append("</select>");
            sb.append("<label>Quête de la condition</label>").append(questSelect("condition_quest_id", target, questTitles));
            sb.append("<label class=\"inline\"><input type=\"checkbox\" name=\"condition_negate\" value=\"true\"")
                    .append(Boolean.TRUE.equals(cond.get("negated")) ? " checked" : "")
                    .append("> inverser la condition (« sauf si »)</label>");
        } else {
            sb.append("<input type=\"hidden\" name=\"quest_condition\" value=\"keep\">");
            sb.append("<p class=\"faint\" style=\"font-size:12px\">").append(stateConditions.size())
                    .append(" conditions d'état de quête sur ce choix : conservées telles quelles, non modifiables "
                            + "ici (l'éditeur n'en représente qu'une).</p>");
        }

        if (!preserved.isEmpty()) {
            sb.append("<p class=\"faint dlg-kept\" style=\"font-size:12px\">").append(Icons.icon("check"))
                    .append("Conservé à l'identique par l'enregistrement (non éditable depuis cette page) : ");
            sb.append(Http.esc(String.join(" · ", preserved))).append(".</p>");
        }

        sb.append(mutationConsent("dialogue.choice.update", "",
                "Met à jour le texte, la cible, l'action de quête et la condition d'état de ce choix. Les autres "
                        + "actions et conditions sont conservées à l'identique. Modification réversible."));
        sb.append("<button class=\"btn\" type=\"submit\">Enregistrer le choix</button></form>");

        if (simple) {
            sb.append(formStart(session, agentId, "dialogue.choice.delete", "/dialogues", ""));
            sb.append("<input type=\"hidden\" name=\"dialogue_id\" value=\"").append(Http.esc(dialogueId)).append("\">");
            sb.append("<input type=\"hidden\" name=\"node_id\" value=\"").append(Http.esc(nodeId)).append("\">");
            sb.append("<input type=\"hidden\" name=\"choice_index\" value=\"").append(index).append("\">");
            sb.append(mutationConsent("dialogue.choice.delete",
                    "Supprimer définitivement ce choix (impossible si c'est le dernier choix du nœud).", ""));
            sb.append("<button class=\"btn secondary\" type=\"submit\">Supprimer ce choix</button></form>");
        } else {
            sb.append("<p class=\"faint\" style=\"font-size:12px\">Suppression indisponible tant que ce choix porte "
                    + "une action ou une condition : les retirer ci-dessus d'abord, pour que leur perte soit un "
                    + "geste explicite.</p>");
        }
        return sb.append("</details>").toString();
    }

    /** États de quête proposés par la condition {@code QUEST_STATE} (ordre de {@code QuestState}). */
    private static final List<String> QUEST_STATES = List.of(
            "NOT_STARTED", "ACTIVE", "READY_TO_TURN_IN", "COMPLETED", "FAILED", "ABANDONED");

    private static String questStateLabel(String state) {
        return switch (state) {
            case "NOT_STARTED" -> "Pas encore commencée";
            case "ACTIVE" -> "En cours";
            case "READY_TO_TURN_IN" -> "Prête à rendre";
            case "COMPLETED" -> "Terminée";
            case "FAILED" -> "Échouée";
            case "ABANDONED" -> "Abandonnée";
            default -> state;
        };
    }

    /**
     * {@code <select>} de quête : les quêtes du dernier relevé, plus — toujours — la quête
     * actuellement référencée même si le serveur ne la connaît pas (sinon l'enregistrement
     * remplacerait une référence inconnue par une autre).
     */
    private static String questSelect(String name, String selected, Map<String, String> questTitles) {
        StringBuilder sb = new StringBuilder("<select name=\"" + Http.esc(name) + "\">");
        sb.append("<option value=\"\"").append(selected.isEmpty() ? " selected" : "")
                .append(">— (choisir une quête) —</option>");
        boolean known = false;
        for (Map.Entry<String, String> e : questTitles.entrySet()) {
            String id = e.getKey();
            boolean on = id.equalsIgnoreCase(selected);
            known |= on;
            String title = e.getValue() == null || e.getValue().isBlank()
                    ? MiniText.prettifyId(id) : MiniText.plain(e.getValue());
            sb.append("<option value=\"").append(Http.esc(id)).append("\"").append(on ? " selected" : "")
                    .append(">").append(Http.esc(title)).append(" (").append(Http.esc(id)).append(")</option>");
        }
        if (!known && !selected.isEmpty()) {
            sb.append("<option value=\"").append(Http.esc(selected)).append("\" selected>")
                    .append(Http.esc(MiniText.prettifyId(selected))).append(" (").append(Http.esc(selected))
                    .append(" — inconnue du serveur)</option>");
        }
        return sb.append("</select>").toString();
    }

    /** Actions {@code START_QUEST} / {@code ADVANCE_QUEST} / {@code TURN_IN_QUEST} portées par un choix. */
    private static List<Map<String, Object>> questActionsOf(Map<String, Object> choice) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object a : asList(choice.get("actions"))) {
            Map<String, Object> am = asMap(a);
            String kind = str(am.get("kind"));
            if ("START_QUEST".equals(kind) || "ADVANCE_QUEST".equals(kind) || "TURN_IN_QUEST".equals(kind)) {
                out.add(am);
            }
        }
        return out;
    }

    private static List<Map<String, Object>> questStateConditionsOf(Map<String, Object> choice) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object c : asList(choice.get("conditions"))) {
            Map<String, Object> cm = asMap(c);
            if ("QUEST_STATE".equals(str(cm.get("kind")))) {
                out.add(cm);
            }
        }
        return out;
    }

    /**
     * Libellés des propriétés d'un choix que le formulaire ne porte pas et que le moteur reconduit
     * telles quelles : à afficher pour que la limite de l'éditeur soit lue, jamais devinée.
     */
    private static List<String> preservedChoiceProperties(Map<String, Object> choice, Map<String, String> questTitles) {
        List<String> out = new ArrayList<>();
        for (Object a : asList(choice.get("actions"))) {
            Map<String, Object> am = asMap(a);
            String kind = str(am.get("kind"));
            if ("CLOSE".equals(kind) || "START_QUEST".equals(kind) || "ADVANCE_QUEST".equals(kind)
                    || "TURN_IN_QUEST".equals(kind)) {
                continue;
            }
            out.add("action " + dialogueEffectLabel(kind, str(am.get("target")), str(am.get("value")), questTitles));
        }
        for (Object c : asList(choice.get("conditions"))) {
            Map<String, Object> cm = asMap(c);
            String kind = str(cm.get("kind"));
            if ("QUEST_STATE".equals(kind)) {
                continue;
            }
            out.add("condition " + (Boolean.TRUE.equals(cm.get("negated")) ? "non " : "")
                    + dialogueEffectLabel(kind, str(cm.get("target")), str(cm.get("value")), questTitles));
        }
        return out;
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
            case "OFFER_CATALOG_QUEST" -> t.isEmpty() ? "propose une quête de ses catalogues"
                    : "propose une quête du catalogue " + t;
            case "CLOSE" -> "ferme";
            case "NO_MAIN_CLAIM" -> "sans claim";
            case "HAS_MAIN_CLAIM" -> "a un claim";
            case "HAS_INVENTORY_SPACE" -> value + " place(s) libre(s) dans l'inventaire";
            case "QUEST_AVAILABLE" -> {
                String title = questTitles.get(t.toLowerCase(java.util.Locale.ROOT));
                yield "quête « " + (title != null ? MiniText.plain(title) : MiniText.prettifyId(t)) + " » acceptable";
            }
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
        return playerStatusSection(session, agentId, player, page, type, listKey, renderer, "");
    }

    /** @param focus contenu ouvert à conserver en sélectionnant le joueur (issue #241), ou vide */
    private String playerStatusSection(Session session, String agentId, String player, String page,
                                       String type, String listKey, RowRenderer renderer, String focus) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2 id=\"etat-joueur\">État d'un joueur</h2>");
        sb.append("<form method=\"get\" action=\"").append(page).append("\">")
                .append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId)).append("\">")
                .append(focus == null || focus.isEmpty() ? ""
                        : "<input type=\"hidden\" name=\"focus\" value=\"" + Http.esc(focus) + "\">")
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

    /**
     * Issue #238 — avertit qu'un libellé de choix risque d'être tronqué en jeu.
     *
     * <p>Mesuré en <strong>pixels</strong> et non en caractères, parce que c'est ce que fait
     * Minecraft : {@code i} avance de 2 px, {@code m} de 6. Le seuil est la largeur réellement
     * configurée pour les boutons de dialogue, pas un nombre choisi au hasard — voir
     * {@link MinecraftTextWidth}.</p>
     *
     * <p><strong>Non bloquant, et c'est voulu.</strong> Un libellé long reste parfois le bon choix ;
     * l'écran informe, il ne décide pas. Le compteur s'affiche aussi quand tout va bien, pour que
     * l'auteur apprenne la contrainte au lieu de la découvrir en jeu.</p>
     */
    private String choiceLabelWidthHint(String label) {
        String plain = MinecraftTextWidth.plain(label == null ? "" : label);
        if (plain.isEmpty()) {
            return "<p class=\"muted\">Le bouton fait " + MinecraftTextWidth.BUTTON_WIDTH
                    + " px de large en jeu, soit environ 25 caractères. Au-delà, Minecraft coupe la "
                    + "fin du libellé : mettre le texte long dans le <strong>corps</strong> du nœud, "
                    + "et garder une action courte sur le bouton.</p>";
        }
        int px = MinecraftTextWidth.pixels(plain);
        StringBuilder sb = new StringBuilder("<p class=\"muted\">Longueur : <strong>")
                .append(plain.length()).append(" caractères</strong> — ")
                .append(px).append(" px sur ").append(MinecraftTextWidth.BUTTON_WIDTH)
                .append(" disponibles.</p>");
        if (px > MinecraftTextWidth.BUTTON_WIDTH) {
            sb.append(Ui.banner("warn", "⚠ Ce libellé risque d'être tronqué dans Minecraft. "
                    + "Déplacer l'explication dans le corps du nœud et ne garder que l'action sur le "
                    + "bouton — par exemple « Prouver ma valeur » plutôt que « Je veux prouver ma "
                    + "valeur pour obtenir un bout de terrain »."));
        }
        return sb.toString();
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

    /**
     * Issue #241 — sélecteur de quête sans choix implicite : la quête ouverte si elle est chargée,
     * sinon une option vide obligatoire. Le navigateur ne peut donc plus soumettre « la première de
     * la liste » sans qu'on l'ait choisie.
     */
    private static String questSelect(List<String> ids, String focus) {
        if (ids.isEmpty()) {
            return "<input type=\"text\" name=\"quest_id\" placeholder=\"rpgquest:…\" required>";
        }
        String selected = ids.stream().filter(id -> plainKey(id).equals(plainKey(focus))).findFirst().orElse("");
        StringBuilder sb = new StringBuilder("<select name=\"quest_id\" required>");
        sb.append("<option value=\"\"").append(selected.isEmpty() ? " selected" : "")
                .append(">— choisir une quête —</option>");
        for (String id : ids) {
            sb.append("<option value=\"").append(Http.esc(id)).append("\"")
                    .append(id.equals(selected) ? " selected" : "").append(">").append(Http.esc(id)).append("</option>");
        }
        return sb.append("</select>").toString();
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

    /**
     * Âge lisible du dernier relevé réussi d'un type, ou vide s'il n'y en a jamais eu.
     *
     * <p>Une position de PNJ n'a de sens qu'avec sa fraîcheur : un PNJ a pu être déplacé depuis.
     * On affiche donc toujours « relevé il y a … » à côté des coordonnées, plutôt que de les
     * présenter comme un état courant.</p>
     */
    private Optional<String> surveyAge(String agentId, String type) {
        return store.latestSuccessfulActionOfType(agentId, type)
                .map(row -> com.lodygames.rpgquest.panel.agent.AgentLiveness.ageHuman(
                        row.completedAt() != null ? row.completedAt() : row.createdAt(), Instant.now()));
    }

    /** Instant du dernier relevé réussi d'un type, pour comparer une donnée à sa fraîcheur. */
    private Optional<Instant> surveyInstant(String agentId, String type) {
        return store.latestSuccessfulActionOfType(agentId, type)
                .map(row -> row.completedAt() != null ? row.completedAt() : row.createdAt());
    }

    /**
     * Ce que le journal d'actions permet d'affirmer de la dernière application d'apparence sur
     * <strong>ce</strong> PNJ (issue #197).
     *
     * <p>Citizens résout un skin de façon asynchrone : une action réussie veut dire « demande
     * transmise », pas « skin visible ». Tant que le relevé Citizens est plus ancien que la
     * demande, la seule chose honnête à dire est « en cours ».</p>
     *
     * <p><strong>Limite assumée, et c'est pourquoi elle est écrite ici.</strong> On ne consulte que
     * la <em>dernière</em> action du type — comme {@code latestForPlayer} le fait pour les joueurs.
     * Si l'apparence d'un autre PNJ a été modifiée depuis, celle-ci est rapportée comme inconnue
     * ({@code NONE}), et l'état retombe sur ce que dit le relevé. C'est un défaut de fraîcheur
     * d'affichage, jamais une affirmation fausse : un statut {@code NONE} n'invente rien.</p>
     */
    private NpcAppearance.LastAttempt lastAppearanceAttempt(String agentId, String npcId) {
        Optional<com.lodygames.rpgquest.panel.agent.AgentActionRow> last =
                store.latestActionOfType(agentId, "npc.citizens.skin");
        if (last.isEmpty()) {
            return NpcAppearance.LastAttempt.NONE;
        }
        com.lodygames.rpgquest.panel.agent.AgentActionRow row = last.get();
        if (!npcId.equalsIgnoreCase(row.params().getOrDefault("npc_id", ""))) {
            return NpcAppearance.LastAttempt.NONE;
        }
        if (row.status() == com.lodygames.rpgquest.panel.agent.AgentActionStatus.FAILED
                || row.status() == com.lodygames.rpgquest.panel.agent.AgentActionStatus.REJECTED) {
            return NpcAppearance.LastAttempt.FAILED;
        }
        if (!row.status().terminal()) {
            // Encore en vol : l'agent ne l'a pas (encore) exécutée.
            return NpcAppearance.LastAttempt.PENDING;
        }
        if (row.status() != com.lodygames.rpgquest.panel.agent.AgentActionStatus.SUCCESS) {
            return NpcAppearance.LastAttempt.NONE;
        }
        Instant done = row.completedAt() != null ? row.completedAt() : row.createdAt();
        Optional<Instant> survey = surveyInstant(agentId, "npc.citizens.list");
        // Relevé postérieur à la demande : il porte déjà la source enregistrée, plus rien n'est
        // « en cours ». Relevé antérieur (ou absent) : on ne peut pas encore le confirmer.
        return survey.isPresent() && done != null && survey.get().isAfter(done)
                ? NpcAppearance.LastAttempt.NONE : NpcAppearance.LastAttempt.PENDING;
    }

    /** État d'apparence d'un PNJ, déduit du relevé Citizens et du journal d'actions (issue #197). */
    private NpcAppearance appearanceOf(String agentId, String npcId, Map<String, Object> citizensRow,
                                       boolean boundCitizens) {
        if (!boundCitizens || citizensRow == null) {
            return NpcAppearance.of(false, "", "", "", NpcAppearance.LastAttempt.NONE);
        }
        Object worn = citizensRow.get("skinMatchesSource");
        return NpcAppearance.of(true, str(citizensRow.get("entityType")),
                str(citizensRow.get("skinSourceKind")), str(citizensRow.get("skinSourceValue")),
                worn instanceof Boolean b ? b : null, lastAppearanceAttempt(agentId, npcId));
    }

    // ================================================================================
    //  Changements en attente (issue #47, lot B)
    // ================================================================================

    /**
     * Construit la liste des ressources des trois familles fichier, avec leur état (issue #47).
     *
     * <p>Les deux sens sont parcourus : ce que porte la source, et ce que porte DEV. Une ressource
     * présente seulement sur le serveur est donc listée elle aussi (« Hors source »), en lecture
     * seule — la cacher laisserait croire qu'elle n'existe pas.</p>
     */
    public com.lodygames.rpgquest.panel.publish.PendingChanges pendingChanges(String agentId) {
        var devIndex = devContentIndex(agentId);
        List<com.lodygames.rpgquest.panel.publish.PendingChanges.Row> rows = new ArrayList<>();
        boolean sourceKnown = sourceCatalog.available();

        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (var qs : sourceCatalog.quests()) {
            rows.add(pendingRow(devIndex, agentId, "quests", qs.slug(),
                    qs.draft() == null ? "" : qs.draft().title,
                    "rpgquest:" + qs.plainId(), sourceKnown));
            seen.add("quests/" + qs.slug());
        }
        for (var ss : sourceCatalog.stories()) {
            rows.add(pendingRow(devIndex, agentId, "stories", ss.slug(),
                    ss.draft() == null ? "" : ss.draft().name,
                    ss.plainId(), sourceKnown));
            seen.add("stories/" + ss.slug());
        }
        for (var ds : sourceCatalog.dialogues()) {
            rows.add(pendingRow(devIndex, agentId, "dialogues", ds.slug(), "",
                    "rpgquest:" + ds.plainId(), sourceKnown));
            seen.add("dialogues/" + ds.slug());
        }
        // Ce que DEV porte et que la source ignore.
        if (devIndex.available()) {
            for (String kind : com.lodygames.rpgquest.panel.publish.PendingChanges.KINDS) {
                for (String slug : devIndex.devSlugs(kind)) {
                    if (seen.add(kind + "/" + slug)) {
                        rows.add(pendingRow(devIndex, agentId, kind, slug, "",
                                "rpgquest:" + slug, sourceKnown));
                    }
                }
            }
        }
        return com.lodygames.rpgquest.panel.publish.PendingChanges.from(rows);
    }

    private com.lodygames.rpgquest.panel.publish.PendingChanges.Row pendingRow(
            com.lodygames.rpgquest.panel.publish.DevContentIndex devIndex, String agentId,
            String kind, String slug, String name, String declaredId, boolean sourceKnown) {
        String sourceSha = sourceCatalog.sha(kind, slug).orElse("");
        Optional<Map<String, Object>> last = lastPublishOf(agentId, kind, slug);
        String lastDevSha = last.map(d -> str(d.get("devShaAfter"))).orElse("");
        var readState = devIndex.stateOf(kind, slug, declaredId, sourceSha, sourceKnown,
                lastDevSha);
        var state = com.lodygames.rpgquest.panel.publish.PublishState.reconciled(readState,
                readingIsStale(agentId, kind, slug),
                last.map(d -> Boolean.TRUE.equals(d.get("ok"))).orElse(false),
                last.map(d -> Boolean.TRUE.equals(d.get("runtimeConfirmed"))).orElse(false),
                lastDevSha, sourceSha);
        return new com.lodygames.rpgquest.panel.publish.PendingChanges.Row(kind, slug, declaredId,
                name, state, sourceSha, devIndex.devSha(kind, slug),
                devIndex.runtimeLoaded(kind, declaredId),
                last.map(d -> str(d.get("verifiedAt"))).orElse(""));
    }

    /**
     * Page {@code /content/pending} : tout ce qui demande une décision, et de quoi la prendre.
     *
     * <p><strong>Aucun bouton « Publier tout ».</strong> Chaque ressource doit être cochée
     * explicitement : une publication groupée écrit plusieurs fichiers sur le serveur, et un bouton
     * qui le ferait d'un clic sur un écran qu'on n'a pas lu serait exactement le geste qu'on
     * regrette.</p>
     */
    public String contentPending(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("deploy", "Changements en attente",
                "Les quêtes, dialogues et stories dont la source et le serveur DEV ne concordent "
                        + "pas encore.", ""));
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        boolean canPublish = perms.can(session.effective(), Permission.CONTENT_PUBLISH);

        sb.append(agentPicker(agentId, "/content/pending", ""));
        String bar = compactRefresh(session, agentId, "content.dev.state", "État DEV",
                devContentIndex(agentId).available() ? "btn-outline-primary" : "btn-warning",
                "/content/pending");
        bar += compactRefresh(session, agentId, "quest.list", "Quêtes",
                "btn-outline-secondary", "/content/pending");
        bar += compactRefresh(session, agentId, "story.list", "Stories",
                "btn-outline-secondary", "/content/pending");
        bar += compactRefresh(session, agentId, "dialogue.list", "Dialogues",
                "btn-outline-secondary", "/content/pending");
        sb.append(listCatbar("Relevés", bar));

        if (!devContentIndex(agentId).available()) {
            sb.append(Ui.banner("warn", "L'état du contenu sur DEV n'est pas encore relevé : "
                    + "sans lui, aucune comparaison n'est possible et toutes les ressources "
                    + "apparaîtraient en « état inconnu ». Cliquer sur <strong>« État DEV »</strong>."));
        }
        if (!sourceCatalog.available()) {
            sb.append(Ui.banner("warn", "Aucun espace de travail source configuré : le panel ne "
                    + "peut pas comparer."));
        }

        // Compte rendu du dernier lot soumis, transmis par la redirection.
        String batch = q.getOrDefault("batch", "");
        if (!batch.isEmpty()) {
            sb.append(Ui.banner(batch.contains("refusée") ? "warning" : "success",
                    Http.esc(batch)));
        }
        sb.append(recentPublishOutcomes(agentId));

        var all = pendingChanges(agentId);
        // Issue #427 — la sélection soumise revient avec la page (Vérifier, refus, erreur) : rien
        // n'est décoché derrière le dos de l'utilisateur.
        java.util.Set<String> pick = keySet(cleanPack(q.get("pick")));
        // Issue #427 — vue « pack » : les ressources d'un import, publiées ensemble.
        String pack = cleanPack(q.get("pack"));
        if (!pack.isEmpty()) {
            sb.append(packPublishPanel(session, agentId, pack, pick, all, canPublish));
            return sb.toString();
        }
        String fKind = q.getOrDefault("kind", "");
        String fState = q.getOrDefault("state", "");
        String search = q.getOrDefault("qs", "");
        var shown = all.filter(fKind, fState, search);

        sb.append(pendingFilterBar(agentId, all, fKind, fState, search));

        if (all.isEmpty()) {
            sb.append(Ui.empty("check", "Tout est synchronisé : aucune ressource ne demande de "
                    + "décision."));
            return sb.toString();
        }
        if (shown.isEmpty()) {
            sb.append(Ui.empty("search", "Aucune ressource ne correspond à ce filtre."));
            return sb.toString();
        }

        if (all.attentionCount() > 0) {
            sb.append(Ui.banner("warn", all.attentionCount() + " ressource(s) demandent une "
                    + "attention particulière (conflit, ou présente sur DEV sans être chargée). "
                    + "Elles ne peuvent pas être publiées en lot : ouvrez leur fiche pour voir les "
                    + "différences d'abord."));
        }

        sb.append(pendingTable(session, agentId, shown, canPublish, "", pick));
        return sb.toString();
    }

    /** Les clés d'un filtre déjà nettoyé par {@link #cleanPack}, dans l'ordre. */
    private static java.util.Set<String> keySet(String cleaned) {
        return cleaned.isEmpty() ? java.util.Set.of()
                : new java.util.LinkedHashSet<>(List.of(cleaned.split(",")));
    }

    /** Au-delà, un filtre de pack est refusé : miroir de la borne d'un lot. */
    private static final int MAX_PACK_KEYS = com.lodygames.rpgquest.panel.agent.AgentActionCatalog.MAX_PUBLISH_BATCH;
    private static final java.util.regex.Pattern PACK_KEY =
            java.util.regex.Pattern.compile("(quests|stories|dialogues)/[a-z0-9][a-z0-9_-]{0,63}");

    /**
     * Le filtre « pack » (issue #427), nettoyé : des clés {@code famille/id} séparées par des
     * virgules, chacune revalidée. Vide si la saisie n'en est pas un — jamais un fragment d'URL.
     */
    static String cleanPack(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        java.util.Set<String> keys = new java.util.TreeSet<>();
        for (String part : raw.split(",")) {
            String key = part.trim().toLowerCase(java.util.Locale.ROOT);
            if (!PACK_KEY.matcher(key).matches()) {
                return "";
            }
            keys.add(key);
        }
        return keys.size() > MAX_PACK_KEYS ? "" : String.join(",", keys);
    }

    /**
     * « Mettre l'histoire en jeu » (issue #427) : les ressources d'un pack, pré-cochées, publiées
     * en <strong>une</strong> opération. L'utilisateur n'a ni à choisir ni à ordonner : le moteur
     * juge l'ensemble d'un bloc.
     */
    private String packPublishPanel(Session session, String agentId, String pack,
                                    java.util.Set<String> pick,
                                    com.lodygames.rpgquest.panel.publish.PendingChanges all,
                                    boolean canPublish) {
        java.util.Set<String> keys = keySet(pack);
        var mine = all.rows().stream().filter(r -> keys.contains(r.key())).toList();
        java.util.Set<String> pending = new java.util.HashSet<>();
        mine.forEach(r -> pending.add(r.key()));
        long upToDate = keys.stream().filter(k -> !pending.contains(k)).count();
        long blocked = mine.stream().filter(r -> !r.selectable()).count();

        StringBuilder sb = new StringBuilder();
        sb.append("<section class=\"card pack-publish\">");
        sb.append(Ui.sectionTitle("deploy", "Mettre l'histoire en jeu sur DEV"));
        sb.append("<p>Ce pack contient <strong>").append(keys.size()).append(" ressource(s)</strong> : ")
                .append(mine.size()).append(" à mettre en jeu, ").append(upToDate)
                .append(" déjà à jour sur DEV <span class=\"badge text-bg-")
                .append(mine.isEmpty() ? "success" : "secondary").append("\">").append(upToDate)
                .append('/').append(keys.size()).append(" en jeu</span>.</p>");
        sb.append(packLastBatch(agentId, keys, mine.isEmpty()));
        sb.append("<ol class=\"pack-steps\"><li><strong>Vérifier</strong> : le serveur juge le pack"
                + " entier, sans rien mettre en jeu.</li><li><strong>Mettre en jeu</strong> : tout"
                + " est publié et rechargé <em>ensemble</em>. Si une seule ressource est refusée,"
                + " rien ne change en jeu et chaque problème est expliqué ci-dessus.</li></ol>");
        if (blocked > 0) {
            sb.append(Ui.banner("warn", blocked + " ressource(s) du pack ont été modifiées sur DEV"
                    + " depuis l'analyse (conflit) : ouvrez leur fiche pour comparer avant de"
                    + " publier. Le reste du pack peut être vérifié, mais il manquera ces ressources."));
        }
        sb.append("</section>");
        if (mine.isEmpty()) {
            sb.append(Ui.empty("check", "Tout le pack est déjà à jour et chargé sur DEV."));
            return sb.toString();
        }
        // À l'ouverture, tout ce qui reste à mettre en jeu est coché ; ensuite, c'est la sélection
        // soumise qui revient — y compris une ressource volontairement décochée.
        java.util.Set<String> checked = new java.util.LinkedHashSet<>();
        mine.stream().filter(r -> r.selectable() && (pick.isEmpty() || pick.contains(r.key())))
                .forEach(r -> checked.add(r.key()));
        List<String> leftOut = mine.stream().filter(r -> r.selectable() && !checked.contains(r.key()))
                .map(com.lodygames.rpgquest.panel.publish.PendingChanges.Row::slug).toList();
        if (!leftOut.isEmpty()) {
            sb.append(Ui.banner("warn", "Non cochée(s) : <strong>" + Http.esc(String.join(", ", leftOut))
                    + "</strong>. Elles ne seront ni vérifiées ni mises en jeu avec le reste : le pack"
                    + " sera incomplet. Si une ressource cochée en dépend, le serveur refusera le lot"
                    + " et dira laquelle manque. Recochez-les pour publier l'histoire entière."));
        }
        sb.append(pendingTable(session, agentId,
                com.lodygames.rpgquest.panel.publish.PendingChanges.from(mine), canPublish, pack, checked));
        return sb.toString();
    }

    /**
     * Où en est le dernier lot de CE pack (issue #427) : en cours, vérifié, mis en jeu ou refusé —
     * pour que l'étape suivante soit évidente sans relire l'historique. Tant que le serveur n'a pas
     * répondu, la page se recharge seule (pas de script : la CSP l'interdit).
     */
    private String packLastBatch(String agentId, java.util.Set<String> keys, boolean allInGame) {
        for (AgentActionRow row : store.recentActions(agentId, 60)) {
            if (!"content.publish.batch".equals(row.type()) || !batchWithin(row, keys)) {
                continue;
            }
            boolean dryRun = "true".equals(row.params().get("dry_run"));
            if (row.resultStatus() == null || row.resultStatus().isBlank()) {
                return "<meta http-equiv=\"refresh\" content=\"4\">" + Ui.banner("info",
                        (dryRun ? "Vérification" : "Mise en jeu") + " en cours sur DEV… Cette page se"
                                + " met à jour toute seule dans quelques secondes.");
            }
            Optional<Map<String, Object>> details = detailsOf(row);
            boolean ok = details.map(d -> Boolean.TRUE.equals(d.get("ok"))).orElse(false);
            String count = row.params().getOrDefault("count", "?");
            if (dryRun && ok) {
                return Ui.banner("success", "<strong>Vérification réussie</strong> : les " + Http.esc(count)
                        + " ressource(s) cochées sont acceptées par le serveur. Rien n'a encore été mis"
                        + " en jeu. Étape suivante : cocher « Je confirme », puis <strong>« Mettre"
                        + " l'histoire en jeu »</strong>.");
            }
            if (ok && !allInGame) {
                // Un relevé DEV plus récent contredit la mise en jeu : c'est lui qui dit vrai.
                return Ui.banner("warning", "La dernière mise en jeu de ce pack avait réussi, mais le"
                        + " relevé DEV plus récent montre encore des ressources à mettre en jeu"
                        + " (ci-dessous). Vérifier puis mettre en jeu à nouveau.");
            }
            if (ok) {
                return Ui.banner("success", "<strong>Histoire mise en jeu</strong> : " + Http.esc(count)
                        + " ressource(s) publiées et chargées ensemble. Cliquer « État DEV » pour"
                        + " consolider le relevé.");
            }
            return Ui.banner("warning", (dryRun ? "<strong>Vérification refusée</strong>"
                    : "<strong>Rien n'a été mis en jeu</strong>") + " : chaque problème est expliqué"
                    + " dans « Dernières publications » ci-dessus. Corriger, puis vérifier à nouveau.");
        }
        return "";
    }

    /** Le lot ne touche-t-il que des ressources de ce pack ? */
    private static boolean batchWithin(AgentActionRow row, java.util.Set<String> keys) {
        int count;
        try {
            count = Integer.parseInt(row.params().getOrDefault("count", "0"));
        } catch (NumberFormatException e) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            if (!keys.contains(row.params().get("kind_" + i) + "/" + row.params().get("id_" + i))) {
                return false;
            }
        }
        return count > 0;
    }

    /**
     * L'issue RÉELLE des dernières publications, ressource par ressource (issue #47).
     *
     * <p>C'est ce qui remplace un « publication terminée » global. Une demande de publication est
     * asynchrone : elle part, puis le serveur la traite. Afficher un succès au moment de la
     * soumission serait exactement le raccourci que ce ticket interdit. On montre donc l'état de
     * chaque action : en attente, réussie, ou refusée avec son motif.</p>
     */
    private String recentPublishOutcomes(String agentId) {
        List<AgentActionRow> rows = new ArrayList<>();
        for (AgentActionRow row : store.recentActions(agentId, 60)) {
            if ("content.publish".equals(row.type())
                    || "content.publish.rollback".equals(row.type())
                    || "content.publish.batch".equals(row.type())) {
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            return "";
        }
        rows.sort(java.util.Comparator.comparing(AgentActionRow::createdAt).reversed());
        List<AgentActionRow> shown = rows.subList(0, Math.min(10, rows.size()));

        StringBuilder sb = new StringBuilder();
        sb.append("<details class=\"publish-outcomes\" open><summary class=\"publish-diff-sum\">")
                .append("Dernières publications <span class=\"muted\">(")
                .append(shown.size()).append(")</span></summary>");
        sb.append("<ul class=\"outcome-list\">");
        for (AgentActionRow row : shown) {
            if ("content.publish.batch".equals(row.type())) {
                sb.append(batchOutcome(row));
                continue;
            }
            String slug = row.params().getOrDefault("id", "?");
            String kind = row.params().getOrDefault("kind", "");
            Optional<Map<String, Object>> details = detailsOf(row);
            String code = details.map(d -> str(d.get("code"))).orElse("");
            boolean ok = details.map(d -> Boolean.TRUE.equals(d.get("ok"))).orElse(false);
            boolean confirmed = details.map(d -> Boolean.TRUE.equals(d.get("runtimeConfirmed")))
                    .orElse(false);

            String mark;
            String tone;
            String label;
            if (row.resultStatus() == null || row.resultStatus().isBlank()) {
                mark = "○";
                tone = "muted";
                label = "en attente";
            } else if (ok && confirmed) {
                mark = "✓";
                tone = "di-add";
                label = "Synchronisé" + (code.isEmpty() ? "" : " (" + code + ")");
            } else if (ok) {
                mark = "✓";
                tone = "di-ctx";
                label = code.isEmpty() ? "appliqué" : code;
            } else {
                mark = "✗";
                tone = "di-del";
                label = code.isEmpty() ? "refusé" : code;
            }
            sb.append("<li><span class=\"").append(tone).append("\">").append(mark).append(' ')
                    .append(Http.esc(slug));
            if (!kind.isEmpty()) {
                sb.append(" <span class=\"muted\">(").append(Http.esc(kind)).append(")</span>");
            }
            sb.append(" — ").append(Http.esc(label)).append("</span>");
            String message = details.map(d -> str(d.get("message"))).orElse("");
            if (!ok && !message.isEmpty()) {
                sb.append("<div class=\"field-help\">").append(Http.esc(message)).append("</div>");
            }
            sb.append("</li>");
        }
        sb.append("</ul>");
        sb.append("<p class=\"field-help\">« ○ » = demande partie, pas encore traitée par le "
                + "serveur. Rafraîchir « État DEV » pour consolider.</p>");
        sb.append("</details>");
        return sb.toString();
    }

    /**
     * L'issue d'un lot (issue #427) : un verdict d'ensemble, puis chaque ressource avec ce que le
     * moteur lui reproche — quoi, où, pourquoi et comment corriger, en français.
     */
    private String batchOutcome(AgentActionRow row) {
        Optional<Map<String, Object>> details = detailsOf(row);
        int count = 0;
        try {
            count = Integer.parseInt(row.params().getOrDefault("count", "0"));
        } catch (NumberFormatException ignored) {
            // compté à 0 : l'affichage reste possible
        }
        boolean dryRun = "true".equals(row.params().get("dry_run"));
        String code = details.map(d -> str(d.get("code"))).orElse("");
        boolean ok = details.map(d -> Boolean.TRUE.equals(d.get("ok"))).orElse(false);
        boolean pendingRow = row.resultStatus() == null || row.resultStatus().isBlank();

        String tone = pendingRow ? "muted" : ok ? "di-add" : "di-del";
        String mark = pendingRow ? "○" : ok ? "✓" : "✗";
        String verdict = pendingRow ? "en attente du serveur"
                : switch (code) {
                    case "PUBLISHED" -> "tout est en jeu";
                    case "ALREADY_PUBLISHED" -> "déjà en jeu, rien à faire";
                    case "CHECKED" -> "vérification réussie, rien n'a été mis en jeu";
                    case "ROLLBACK_INCOMPLETE" -> "ÉCHEC — remise en l'état incomplète, voir ci-dessous";
                    default -> (dryRun ? "vérification refusée" : "rien n'a été mis en jeu")
                            + (code.isEmpty() ? "" : " (" + code + ")");
                };
        StringBuilder sb = new StringBuilder("<li class=\"batch-outcome\"><span class=\"")
                .append(tone).append("\">").append(mark).append(' ')
                .append(dryRun ? "Vérification" : "Publication").append(" d'un lot de ")
                .append(count).append(" ressource(s) — ").append(Http.esc(verdict)).append("</span>");
        if (details.isEmpty()) {
            return sb.append("</li>").toString();
        }
        String message = str(details.get().get("message"));
        if (!message.isEmpty()) {
            sb.append("<div class=\"field-help\">").append(Http.esc(message)).append("</div>");
        }
        sb.append("<ul class=\"outcome-list batch-items\">");
        for (Map<String, Object> item : listOfMaps(details.get().get("items"))) {
            String status = str(item.get("status"));
            boolean good = Set.of("CREATED", "UPDATED", "UNCHANGED", "CHECKED").contains(status) && ok;
            sb.append("<li><span class=\"").append(good ? "di-add" : status.equals("RESTORED")
                    || status.equals("UNCHANGED") ? "di-ctx" : "di-del").append("\">")
                    .append(Http.esc(kindLabel(str(item.get("kind"))))).append(' ')
                    .append("<strong>").append(Http.esc(str(item.get("slug")))).append("</strong> — ")
                    .append(Http.esc(batchStatusLabel(status, dryRun))).append("</span>");
            for (String problem : stringList(item.get("problems"))) {
                sb.append("<div class=\"field-help\">").append(Http.esc(humanProblem(problem)))
                        .append("</div>");
            }
            sb.append("</li>");
        }
        sb.append("</ul>");
        List<String> others = stringList(details.get().get("otherProblems"));
        if (!others.isEmpty()) {
            sb.append("<div class=\"field-help\"><strong>Problèmes hors de ce lot</strong>, qui"
                    + " bloquent quand même (fichier déjà présent sur DEV, ou référence vers une"
                    + " ressource absente) :</div><ul class=\"outcome-list\">");
            for (String other : others) {
                sb.append("<li class=\"field-help\">").append(Http.esc(humanProblem(other)))
                        .append("</li>");
            }
            sb.append("</ul>");
        }
        return sb.append("</li>").toString();
    }

    private static String batchStatusLabel(String status, boolean dryRun) {
        return switch (status) {
            case "CREATED" -> "créée et chargée en jeu";
            case "UPDATED" -> "mise à jour et chargée en jeu";
            case "UNCHANGED" -> dryRun ? "identique sur DEV" : "déjà identique sur DEV";
            case "CHECKED" -> "acceptée par le moteur";
            case "RESTORED" -> "non publiée (remise comme avant à cause d'une autre ressource)";
            case "REJECTED_BY_ENGINE" -> "refusée par le moteur";
            case "NOT_LOADED" -> "absente du moteur après rechargement";
            case "CONFLICT" -> "modifiée sur DEV depuis l'analyse : ouvrir sa fiche pour comparer";
            case "INVALID" -> "refusée avant tout envoi";
            default -> status.isEmpty() ? "?" : status;
        };
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "quests" -> "Quête";
            case "stories" -> "Story";
            case "dialogues" -> "Dialogue";
            default -> kind;
        };
    }

    /**
     * Un diagnostic du moteur, lisible : {@code "quests/lily_fleurs.yml : motif"} devient
     * {@code "Fichier quests/lily_fleurs.yml — motif"}. Le motif lui-même est déjà en français.
     */
    static String humanProblem(String raw) {
        int sep = raw.indexOf(" : ");
        if (sep <= 0) {
            return raw;
        }
        return "Fichier " + raw.substring(0, sep) + " — " + raw.substring(sep + 3);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        }
        return out;
    }

    /** Filtres : des liens, donc partageables et sans JavaScript. */
    private String pendingFilterBar(String agentId,
                                    com.lodygames.rpgquest.panel.publish.PendingChanges all,
                                    String fKind, String fState, String search) {
        StringBuilder sb = new StringBuilder("<div class=\"filterbar\">");
        sb.append("<div class=\"filterrow\">");
        sb.append(pendingFilterLink(agentId, "", fState, search, fKind.isEmpty(),
                "Tous", all.total()));
        for (String kind : com.lodygames.rpgquest.panel.publish.PendingChanges.KINDS) {
            long n = all.countOf(kind);
            if (n > 0) {
                sb.append(pendingFilterLink(agentId, kind, fState, search, kind.equals(fKind),
                        switch (kind) {
                            case "quests" -> "Quêtes";
                            case "stories" -> "Stories";
                            default -> "Dialogues";
                        }, n));
            }
        }
        sb.append("</div><div class=\"filterrow\">");
        sb.append(pendingStateLink(agentId, fKind, "", search, fState.isEmpty(),
                "Tous états", all.total()));
        for (var state : new com.lodygames.rpgquest.panel.publish.PublishState[] {
                com.lodygames.rpgquest.panel.publish.PublishState.CONFLICT,
                com.lodygames.rpgquest.panel.publish.PublishState.NOT_LOADED,
                com.lodygames.rpgquest.panel.publish.PublishState.DIFFERENT,
                com.lodygames.rpgquest.panel.publish.PublishState.SOURCE_ONLY,
                com.lodygames.rpgquest.panel.publish.PublishState.RUNTIME_ONLY,
                com.lodygames.rpgquest.panel.publish.PublishState.UNKNOWN}) {
            long n = all.countOf(state);
            if (n > 0) {
                sb.append(pendingStateLink(agentId, fKind,
                        state.code().toLowerCase(java.util.Locale.ROOT), search,
                        state.code().equalsIgnoreCase(fState), state.label(), n));
            }
        }
        sb.append("</div>");
        // Recherche : un GET, donc l'URL reste partageable et il n'y a pas une ligne de JS.
        sb.append("<form method=\"get\" action=\"/content/pending\" class=\"filtersearch\">");
        sb.append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId))
                .append("\">");
        if (!fKind.isEmpty()) {
            sb.append("<input type=\"hidden\" name=\"kind\" value=\"").append(Http.esc(fKind))
                    .append("\">");
        }
        if (!fState.isEmpty()) {
            sb.append("<input type=\"hidden\" name=\"state\" value=\"").append(Http.esc(fState))
                    .append("\">");
        }
        sb.append("<input type=\"search\" name=\"qs\" placeholder=\"Chercher un identifiant…\" "
                + "value=\"").append(Http.esc(search)).append("\">");
        sb.append("<button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\">")
                .append(Icons.icon("search")).append("Chercher</button>");
        if (!search.isEmpty()) {
            sb.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"")
                    .append(Http.esc(pendingUrl(agentId, fKind, fState, ""))).append("\">Effacer</a>");
        }
        sb.append("</form></div>");
        return sb.toString();
    }

    private String pendingFilterLink(String agentId, String kind, String state, String search,
                                     boolean active, String label, long count) {
        return "<a class=\"btn btn-sm " + (active ? "btn-primary" : "btn-outline-secondary")
                + "\" href=\"" + Http.esc(pendingUrl(agentId, kind, state, search)) + "\">"
                + Http.esc(label) + " <span class=\"badge text-bg-light text-dark\">" + count
                + "</span></a>";
    }

    private String pendingStateLink(String agentId, String kind, String state, String search,
                                    boolean active, String label, long count) {
        return pendingFilterLink(agentId, kind, state, search, active, label, count);
    }

    private static String pendingUrl(String agentId, String kind, String state, String search) {
        StringBuilder sb = new StringBuilder("/content/pending?agent=")
                .append(java.net.URLEncoder.encode(agentId, java.nio.charset.StandardCharsets.UTF_8));
        if (kind != null && !kind.isEmpty()) {
            sb.append("&kind=").append(java.net.URLEncoder.encode(kind,
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        if (state != null && !state.isEmpty()) {
            sb.append("&state=").append(java.net.URLEncoder.encode(state,
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        if (search != null && !search.isEmpty()) {
            sb.append("&qs=").append(java.net.URLEncoder.encode(search,
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * Le tableau, et le formulaire de publication groupée.
     *
     * <p>Chaque ligne porte <strong>ses propres empreintes</strong> en champs cachés : la
     * publication groupée n'est que l'orchestration de publications individuelles, chacune avec les
     * protections habituelles. Rien de global ne les contourne.</p>
     *
     * <p>Les noms de champs sont uniques par ligne ({@code sel_<famille>/<id>}) parce que le parseur
     * de formulaire du panel écrase les valeurs répétées : un {@code name="sel"} multiple ne
     * livrerait qu'une seule case cochée.</p>
     */
    private String pendingTable(Session session, String agentId,
                                com.lodygames.rpgquest.panel.publish.PendingChanges shown,
                                boolean canPublish, String pack, java.util.Set<String> checked) {
        StringBuilder sb = new StringBuilder();
        if (canPublish) {
            sb.append("<form method=\"post\" action=\"/content/pending/publish\">");
            if (!pack.isEmpty()) {
                sb.append("<input type=\"hidden\" name=\"pack\" value=\"")
                        .append(Http.esc(pack)).append("\">");
            }
            sb.append("<input type=\"hidden\" name=\"_csrf\" value=\"")
                    .append(Http.esc(session.csrfToken())).append("\">");
            sb.append("<input type=\"hidden\" name=\"agent\" value=\"")
                    .append(Http.esc(agentId)).append("\">");
        }
        sb.append("<div class=\"tablewrap\"><table class=\"table pending-table\">");
        sb.append("<thead><tr>");
        if (canPublish) {
            sb.append("<th></th>");
        }
        sb.append("<th>Type</th><th>Ressource</th><th>État</th><th>Source</th><th>DEV</th>")
                .append("<th>Chargée</th><th>Dernière vérif.</th><th></th></tr></thead><tbody>");

        for (var row : shown.rows()) {
            sb.append("<tr>");
            if (canPublish) {
                sb.append("<td class=\"pending-pick\">");
                if (row.selectable()) {
                    sb.append("<input type=\"checkbox\" name=\"sel_").append(Http.esc(row.key()))
                            .append("\" value=\"1\"").append(checked.contains(row.key()) ? " checked" : "")
                            .append(" aria-label=\"Sélectionner ")
                            .append(Http.esc(row.slug())).append("\">");
                    sb.append("<input type=\"hidden\" name=\"src_").append(Http.esc(row.key()))
                            .append("\" value=\"").append(Http.esc(row.sourceSha())).append("\">");
                    sb.append("<input type=\"hidden\" name=\"dev_").append(Http.esc(row.key()))
                            .append("\" value=\"").append(Http.esc(row.devSha())).append("\">");
                } else {
                    sb.append("<span class=\"muted\" title=\"Non publiable en lot\">—</span>");
                }
                sb.append("</td>");
            }
            sb.append("<td data-label=\"Type\">").append(Http.esc(row.kindLabel())).append("</td>");
            sb.append("<td data-label=\"Ressource\"><strong>").append(Http.esc(row.name())).append("</strong>")
                    .append(Ui.id(row.slug())).append("</td>");
            sb.append("<td data-label=\"État\"><span class=\"badge text-bg-")
                    .append(toneClass(row.state().tone())).append("\">")
                    .append(Http.esc(row.state().label())).append("</span></td>");
            sb.append("<td data-label=\"Source\"><code class=\"tid\">").append(Http.esc(row.shortSource()))
                    .append("</code></td>");
            sb.append("<td data-label=\"DEV\"><code class=\"tid\">").append(Http.esc(row.shortDev()))
                    .append("</code></td>");
            sb.append("<td data-label=\"Chargée\">").append(row.runtimeLoaded() ? "oui"
                    : "<span class=\"muted\">non</span>").append("</td>");
            sb.append("<td data-label=\"Dernière vérif.\" class=\"muted\">").append(row.verifiedAt().isEmpty() ? "—"
                    : Http.esc(row.verifiedAt())).append("</td>");
            sb.append("<td class=\"pending-open\"><a class=\"btn btn-sm btn-outline-secondary\" href=\"")
                    .append(Http.esc(pendingFicheUrl(agentId, row.kind()))).append("\">Ouvrir</a></td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");

        if (canPublish) {
            // Issue #427 : la sélection part en UN lot. Le moteur la juge d'un bloc — l'ordre ne
            // compte plus, et un refus ne laisse rien à moitié en jeu.
            sb.append("<p class=\"field-help\">Les ressources cochées sont publiées "
                    + "<strong>ensemble</strong>, en une seule opération : sauvegardes, un seul "
                    + "rechargement, puis vérification de chacune par le moteur. Si une seule est "
                    + "refusée, <strong>rien ne change en jeu</strong> et les fichiers DEV sont "
                    + "remis comme avant. « Vérifier » fait juger la sélection sans rien mettre en "
                    + "jeu et ne demande aucune confirmation.</p>");
            sb.append(mutationConsent("content.publish.batch",
                    "Je confirme : envoyer les ressources cochées sur DEV", null));
            // « formnovalidate » : vérifier n'exige pas la case de confirmation, qui ne concerne que
            // la mise en jeu (le serveur la revérifie pour la seule publication).
            sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-primary\" "
                    + "type=\"submit\" name=\"mode\" value=\"check\" formnovalidate>").append(Icons.icon("check"))
                    .append("Vérifier</button>");
            sb.append("<button class=\"btn btn-sm btn-primary\" type=\"submit\" name=\"mode\" "
                    + "value=\"publish\">").append(Icons.icon("deploy"))
                    .append(pack.isEmpty() ? "Publier la sélection" : "Mettre l'histoire en jeu")
                    .append("</button></div></form>");
        } else {
            sb.append("<p class=\"field-help\">Vous n'avez pas le droit de publier sur DEV : "
                    + "cette page reste consultable.</p>");
        }
        return sb.toString();
    }

    private static String pendingFicheUrl(String agentId, String kind) {
        String page = switch (kind) {
            case "stories" -> "/stories";
            case "dialogues" -> "/dialogues";
            default -> "/quests";
        };
        return page + "?agent=" + java.net.URLEncoder.encode(agentId,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Le bloc « Voir les différences » (issue #47).
     *
     * <h2>Pourquoi un bouton et pas un affichage systématique</h2>
     *
     * <p>Comparer exige de <strong>lire le fichier sur le serveur</strong>, donc une action agent.
     * La faire partir pour chaque ressource de chaque page coûterait un relevé par ligne de
     * catalogue, pour une information que l'administrateur ne regarde qu'au moment de décider.
     * Le relevé d'état, lui, ne transporte que des empreintes — assez pour <em>détecter</em> l'écart,
     * pas pour le <em>montrer</em>.</p>
     *
     * <p>Sens de lecture : {@code +} est ce que la publication ajouterait, {@code -} ce qu'elle
     * retirerait. C'est la seule question posée devant ce bouton.</p>
     */
    private String publishDiffBlock(Session session, String agentId, String kind, String slug,
                                    String sourceText, String returnPath) {
        StringBuilder sb = new StringBuilder();
        Optional<Map<String, Object>> read = devFileRead(agentId, kind, slug);

        sb.append(formStart(session, agentId, "content.dev.read", returnPath, ""));
        sb.append("<input type=\"hidden\" name=\"kind\" value=\"").append(Http.esc(kind))
                .append("\"><input type=\"hidden\" name=\"id\" value=\"").append(Http.esc(slug))
                .append("\">");
        sb.append(mutationConsent("content.dev.read", "", null));
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-secondary\" "
                + "type=\"submit\">").append(Icons.icon("docs"))
                .append(read.isPresent() ? "Recalculer les différences" : "Voir les différences")
                .append("</button></div></form>");

        if (read.isEmpty()) {
            return sb.toString();
        }

        Map<String, Object> details = read.get();
        boolean present = Boolean.TRUE.equals(details.get("present"));
        boolean tooLarge = Boolean.TRUE.equals(details.get("tooLarge"));
        String devText = str(details.get("text"));

        com.lodygames.rpgquest.panel.publish.PublishDiff diff;
        if (tooLarge) {
            diff = com.lodygames.rpgquest.panel.publish.PublishDiff.unavailable(
                    "Le fichier est présent sur DEV mais trop volumineux pour être comparé ici.");
        } else {
            diff = com.lodygames.rpgquest.panel.publish.PublishDiff.between(
                    present ? devText : null, sourceText);
        }

        sb.append("<details class=\"publish-diff\" open><summary class=\"publish-diff-sum\">")
                .append("Différences <span class=\"muted\">(DEV → source)</span></summary>");
        sb.append("<p class=\"publish-diff-meta\">").append(Http.esc(diff.summary()));
        if (!diff.note().isEmpty() && diff.comparable()) {
            sb.append(" — ").append(Http.esc(diff.note()));
        }
        sb.append("</p>");

        if (!diff.comparable()) {
            sb.append("<p class=\"field-help\">").append(Http.esc(diff.note())).append("</p>");
        } else if (diff.identical()) {
            sb.append("<p class=\"field-help\">Les deux fichiers sont identiques ligne pour "
                    + "ligne.</p>");
        } else {
            sb.append("<div class=\"codeblock diff diff-publish\"><pre>");
            for (var line : diff.lines()) {
                String cls = switch (line.kind()) {
                    case ADDED -> "di-add";
                    case REMOVED -> "di-del";
                    case GAP -> "di-gap";
                    default -> "di-ctx";
                };
                sb.append("<span class=\"").append(cls).append("\">");
                // Gouttière : numéro DEV puis numéro source, vides quand la ligne n'existe pas d'un
                // côté. C'est ce qui permet de retrouver la ligne dans le fichier réel.
                sb.append("<span class=\"di-num\">")
                        .append(gutter(line.devLine())).append(gutter(line.sourceLine()))
                        .append("</span>");
                sb.append(Http.esc(line.sign())).append(' ')
                        // Http.esc : le YAML n'est JAMAIS interprété comme du HTML.
                        .append(Http.esc(line.text()));
                sb.append("</span>\n");
            }
            sb.append("</pre></div>");
            sb.append("<p class=\"field-help\">Les colonnes sont les numéros de ligne côté DEV "
                    + "puis côté source. <code>+</code> sera ajouté par la publication, "
                    + "<code>-</code> en sera retiré.</p>");
        }
        sb.append("</details>");
        return sb.toString();
    }

    /** Un numéro de ligne sur 4 colonnes, ou des espaces si la ligne n'existe pas de ce côté. */
    private static String gutter(int line) {
        String value = line <= 0 ? "" : String.valueOf(line);
        return String.format("%4s ", value);
    }

    /**
     * La section « Publication sur DEV » d'une fiche de contenu (issue #47).
     *
     * <h2>Enregistrer et publier sont deux gestes, et l'écran doit le montrer</h2>
     *
     * <p>C'est le cœur de l'UX du ticket. L'état est calculé par
     * {@link com.lodygames.rpgquest.panel.publish.PublishState}, partagé par toutes les familles :
     * une seule fonction, donc un seul vocabulaire, donc aucune page qui dirait « Synchronisé » là
     * où une autre dirait « Différent ».</p>
     *
     * <p>Le formulaire n'envoie <strong>ni chemin ni contenu</strong> : seulement la famille,
     * l'identifiant et les deux empreintes vues à l'écran. Le panel lit la source lui-même, et le
     * serveur résout le fichier depuis sa liste blanche.</p>
     */
    private String publishSection(Session session, String agentId, String kind, String slug,
                                  String declaredId,
                                  com.lodygames.rpgquest.panel.publish.DevContentIndex devIndex,
                                  String returnPath) {
        boolean canPublish = perms.can(session.effective(), Permission.CONTENT_PUBLISH);
        boolean canRollback = perms.can(session.effective(), Permission.CONTENT_ROLLBACK);
        String sourceSha = sourceCatalog.sha(kind, slug).orElse("");
        Optional<Map<String, Object>> lastPublish = lastPublishOf(agentId, kind, slug);
        String lastDevSha = lastPublish.map(d -> str(d.get("devShaAfter"))).orElse("");
        String backupPath = lastPublish.map(d -> str(d.get("backupPath"))).orElse("");

        var readState = devIndex.stateOf(kind, slug, declaredId, sourceSha,
                sourceCatalog.available(), lastDevSha);
        // Lot C : si le relevé est antérieur à une publication réussie qui a CONFIRMÉ le runtime,
        // c'est la publication qui dit vrai — elle a relu le moteur, le relevé non.
        boolean stale = readingIsStale(agentId, kind, slug);
        var state = com.lodygames.rpgquest.panel.publish.PublishState.reconciled(readState, stale,
                lastPublish.map(d -> Boolean.TRUE.equals(d.get("ok"))).orElse(false),
                lastPublish.map(d -> Boolean.TRUE.equals(d.get("runtimeConfirmed"))).orElse(false),
                lastDevSha, sourceSha);
        String devSha = devIndex.devSha(kind, slug);

        StringBuilder sb = new StringBuilder();
        sb.append(detailSection("deploy", "Publication sur DEV"));
        sb.append("<p class=\"publish-state tone-").append(state.tone()).append("\">");
        sb.append("<span class=\"badge text-bg-").append(toneClass(state.tone())).append("\">")
                .append(Http.esc(state.label())).append("</span> ");
        sb.append(Http.esc(state.explanation())).append("</p>");

        sb.append("<dl class=\"npc-dl publish-dl\">");
        dlRow(sb, "Source", sourceSha.isEmpty()
                ? "<span class=\"muted\">absente</span>"
                : "<code class=\"tid\">" + Http.esc(shortSha(sourceSha)) + "</code>");
        dlRow(sb, "Serveur DEV", devIndex.available()
                ? (devSha.isEmpty() ? "<span class=\"muted\">absente</span>"
                        : "<code class=\"tid\">" + Http.esc(shortSha(devSha)) + "</code>")
                : "<span class=\"muted\">relevé non chargé</span>");
        dlRow(sb, "Chargée par le moteur", devIndex.available()
                ? (devIndex.runtimeLoaded(kind, declaredId.isEmpty() ? slug : declaredId)
                        ? "oui" : "non")
                : "<span class=\"muted\">inconnu</span>");
        if (lastPublish.isPresent()) {
            dlRow(sb, "Dernière publication",
                    Http.esc(str(lastPublish.get().get("verifiedAt"))) + " — "
                            + Http.esc(str(lastPublish.get().get("code"))));
        }
        sb.append("</dl>");
        sb.append(publishReloadDiagnostics(lastPublishAttemptOf(agentId, kind, slug)));

        if (stale) {
            sb.append("<p class=\"field-help\">").append(Icons.icon("deploy"))
                    .append("<strong>Actualisation en cours :</strong> le relevé affiché est "
                            + "antérieur à la dernière publication. L'état ci-dessus vient du "
                            + "compte rendu de cette publication, qui a relu le moteur. "
                            + "« État DEV » consolidera l'affichage.</p>");
        }

        if (!devIndex.available()) {
            sb.append("<p class=\"field-help\">Relevez l'état du serveur pour savoir où en est "
                    + "cette ressource.</p>");
            sb.append(compactRefresh(session, agentId, "content.dev.state",
                    "Analyser l'état DEV", "btn-outline-primary", returnPath));
            return sb.toString();
        }

        if (sourceSha.isEmpty()) {
            sb.append("<p class=\"field-help\">Cette ressource n'existe pas dans la source "
                    + "éditable : il n'y a rien à publier depuis le panel.</p>");
            return sb.toString();
        }

        boolean conflict = state == com.lodygames.rpgquest.panel.publish.PublishState.CONFLICT;
        if (conflict) {
            sb.append(Ui.banner("error", "Le fichier DEV a été modifié hors du panel. "
                    + "Publier écraserait cette modification — regardez les différences avant de "
                    + "décider."));
        }

        // Comparaison : proposée dès qu'il y a quelque chose à comparer. Inutile pour une ressource
        // absente de DEV — tout le fichier serait « ajouté », ce qui n'apprend rien.
        boolean comparable = !devSha.isEmpty();
        if (comparable) {
            sb.append(publishDiffBlock(session, agentId, kind, slug,
                    sourceCatalog.text(kind, slug).orElse(""), returnPath));
        }

        // En CONFLIT, on n'offre PAS de remplacement aveugle : publier n'est proposé qu'une fois la
        // différence réellement consultée POUR LE CONTENU DEV COURANT. Avoir regardé une version
        // antérieure ne compte pas — c'est justement ce qui a changé.
        boolean sawCurrentDev = devFileRead(agentId, kind, slug)
                .map(d -> devSha.equals(str(d.get("sha256"))))
                .orElse(false);
        if (conflict && !sawCurrentDev) {
            sb.append("<p class=\"field-help\">Consultez les différences ci-dessus avant de "
                    + "pouvoir republier : le bouton n'apparaît qu'ensuite, pour qu'aucun "
                    + "remplacement ne se fasse à l'aveugle.</p>");
            sb.append(compactRefresh(session, agentId, "content.dev.state", "Rafraîchir l'état",
                    "btn-outline-secondary", returnPath));
        }

        if (state.publishable() || (conflict && sawCurrentDev)) {
            if (!canPublish) {
                sb.append("<p class=\"field-help\">Vous n'avez pas le droit de publier sur "
                        + "DEV.</p>");
            } else {
                sb.append("<p class=\"field-help\">").append(conflict
                        ? "Vous avez consulté la version DEV ci-dessus. Publier la "
                                + "<strong>remplacera</strong> par la source, et la modification "
                                + "faite hors du panel sera perdue — une sauvegarde en sera prise."
                        : "Publier copie <strong>ce seul fichier</strong> sur le serveur, recharge "
                                + "la famille concernée, puis vérifie que le moteur la voit. "
                                + "Aucun build, aucun redémarrage.")
                        .append("</p>");
                sb.append(formStart(session, agentId, "content.publish", returnPath, ""));
                sb.append("<input type=\"hidden\" name=\"kind\" value=\"")
                        .append(Http.esc(kind)).append("\">");
                sb.append("<input type=\"hidden\" name=\"id\" value=\"")
                        .append(Http.esc(slug)).append("\">");
                // Les deux empreintes VUES À L'ÉCRAN voyagent avec le formulaire : si l'une des deux
                // a changé entre-temps, la publication est refusée au lieu d'écraser.
                sb.append("<input type=\"hidden\" name=\"expected_source_sha\" value=\"")
                        .append(Http.esc(sourceSha)).append("\">");
                sb.append("<input type=\"hidden\" name=\"expected_dev_sha\" value=\"")
                        .append(Http.esc(devSha)).append("\">");
                sb.append(mutationConsent("content.publish", "", null));
                sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm ")
                        .append(conflict ? "btn-danger" : "btn-primary")
                        .append("\" type=\"submit\">").append(Icons.icon("deploy"))
                        .append(conflict ? "Écraser la version DEV"
                                : devSha.isEmpty() ? "Publier sur DEV" : "Republier sur DEV")
                        .append("</button></div></form>");
            }
        }

        // Retour arrière : seulement si la dernière opération réussie était une PUBLICATION.
        //
        // Après une restauration ou un retrait, il n'y a plus rien à défaire : DEV porte déjà la
        // version sauvegardée. Proposer quand même « Restaurer » reposerait le même contenu, donc
        // ne ferait rien — un bouton qui ne fait rien est un bouton qui ment.
        String lastCode = lastPublish.map(d -> str(d.get("code"))).orElse("");
        boolean lastWasPublication = !lastCode.isEmpty()
                && !"RESTORED".equals(lastCode) && !"WITHDRAWN".equals(lastCode)
                && !"ALREADY_ABSENT".equals(lastCode);
        if (lastPublish.isPresent() && lastWasPublication && canRollback && !devSha.isEmpty()) {
            boolean restorable = !backupPath.isEmpty();
            sb.append(detailSection("warning", restorable
                    ? "Restaurer la version précédente" : "Retirer de DEV"));
            sb.append("<p class=\"field-help\">").append(restorable
                    ? "Repose la version sauvegardée avant la dernière publication, puis recharge."
                    : "Cette ressource a été <strong>créée</strong> par la publication : il n'y a "
                            + "pas de version précédente. Le retour arrière la retire du serveur.")
                    .append("</p>");
            sb.append(formStart(session, agentId, "content.publish.rollback", returnPath, ""));
            sb.append("<input type=\"hidden\" name=\"kind\" value=\"")
                    .append(Http.esc(kind)).append("\">");
            sb.append("<input type=\"hidden\" name=\"id\" value=\"")
                    .append(Http.esc(slug)).append("\">");
            if (restorable) {
                sb.append("<input type=\"hidden\" name=\"backup\" value=\"")
                        .append(Http.esc(backupPath)).append("\">");
            }
            sb.append(mutationConsent("content.publish.rollback", "", null));
            sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-danger\" "
                    + "type=\"submit\">").append(Icons.icon("warning"))
                    .append(restorable ? "Restaurer la version précédente" : "Retirer de DEV")
                    .append("</button></div></form>");
        }
        return sb.toString();
    }

    /**
     * L'identifiant « nu », sans préfixe de namespace — c'est aussi le nom de fichier (issue #47).
     *
     * <p>Le panel raisonne en slugs, le moteur en {@code rpgquest:<id>}. Un seul endroit fait la
     * conversion, donc une seule façon de la faire.</p>
     */
    private static String plainKey(String id) {
        String clean = id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT);
        int colon = clean.indexOf(':');
        return colon < 0 ? clean : clean.substring(colon + 1);
    }

    /** Les douze premiers caractères d'une empreinte : assez pour comparer à l'œil. */
    private static String shortSha(String sha) {
        return sha == null || sha.length() <= 12 ? String.valueOf(sha) : sha.substring(0, 12);
    }

    private static String toneClass(String tone) {
        return switch (tone) {
            case "ok" -> "success";
            case "warn" -> "warning";
            case "err" -> "danger";
            default -> "secondary";
        };
    }

    /**
     * L'état du contenu sur DEV, depuis le dernier relevé {@code content.dev.state} réussi
     * (issue #47).
     *
     * <p>Sans relevé, l'index est indisponible et toutes les ressources tombent en « état inconnu ».
     * C'est volontaire : affirmer « Source uniquement » sans avoir interrogé le serveur serait
     * exactement le genre de certitude non fondée que #47 corrige.</p>
     */
    public com.lodygames.rpgquest.panel.publish.DevContentIndex devContentIndex(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return com.lodygames.rpgquest.panel.publish.DevContentIndex.unavailable();
        }
        return latestDetails(agentId, "content.dev.state")
                .map(com.lodygames.rpgquest.panel.publish.DevContentIndex::from)
                .orElseGet(com.lodygames.rpgquest.panel.publish.DevContentIndex::unavailable);
    }

    /**
     * Quand le dernier relevé DEV a-t-il été <strong>terminé</strong> ? (issue #47, lot C)
     *
     * <p>Sert à savoir si l'état affiché est antérieur à une publication plus récente. Sans cette
     * comparaison, l'administrateur voit « Source uniquement » juste après avoir publié avec
     * succès — ce qui ressemble à un échec alors que c'est seulement un relevé en retard.</p>
     */
    private Optional<java.time.Instant> devReadingAt(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }
        return store.latestSuccessfulActionOfType(agentId, "content.dev.state")
                .map(AgentActionRow::completedAt)
                .filter(java.util.Objects::nonNull);
    }

    /** L'instant d'achèvement de la dernière opération de publication sur cette ressource. */
    private Optional<java.time.Instant> lastPublishAt(String agentId, String kind, String slug) {
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }
        for (AgentActionRow row : store.recentActions(agentId, 200)) {
            if (batchPublicationOf(row, kind, slug).isPresent() && row.completedAt() != null) {
                return Optional.of(row.completedAt());
            }
            boolean op = "content.publish".equals(row.type())
                    || "content.publish.rollback".equals(row.type());
            if (!op || !kind.equals(row.params().get("kind"))
                    || !slug.equals(row.params().get("id"))) {
                continue;
            }
            Optional<Map<String, Object>> details = detailsOf(row);
            if (details.isPresent() && Boolean.TRUE.equals(details.get().get("ok"))
                    && row.completedAt() != null) {
                return Optional.of(row.completedAt());
            }
        }
        return Optional.empty();
    }

    /**
     * Le relevé est-il plus ancien que la dernière opération réussie sur cette ressource ?
     */
    private boolean readingIsStale(String agentId, String kind, String slug) {
        Optional<java.time.Instant> reading = devReadingAt(agentId);
        Optional<java.time.Instant> op = lastPublishAt(agentId, kind, slug);
        return reading.isPresent() && op.isPresent() && op.get().isAfter(reading.get());
    }

    /**
     * Le contenu DEV relu pour <strong>cette</strong> ressource, s'il a été demandé (issue #47).
     *
     * <p>Filtré sur {@code kind} et {@code id} : un relevé fait pour une autre ressource ne doit
     * surtout pas servir de base à une comparaison — on afficherait le diff du voisin.</p>
     */
    public Optional<Map<String, Object>> devFileRead(String agentId, String kind, String slug) {
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }
        for (AgentActionRow row : store.recentActions(agentId, 200)) {
            if (!"content.dev.read".equals(row.type())) {
                continue;
            }
            if (!kind.equals(row.params().get("kind")) || !slug.equals(row.params().get("id"))) {
                continue;
            }
            Optional<Map<String, Object>> details = detailsOf(row);
            if (details.isPresent()) {
                return details;
            }
        }
        return Optional.empty();
    }

    /**
     * Le compte rendu de la dernière opération réussie <strong>sur cette ressource</strong> —
     * publication <em>ou</em> retour arrière.
     *
     * <p>C'est de là que viennent le chemin de sauvegarde (donc la possibilité d'un retour arrière)
     * et l'empreinte DEV que nous avions laissée — celle qui permet de distinguer « différent parce
     * que je n'ai pas publié » de « différent parce que quelqu'un d'autre a touché au fichier ».</p>
     */
    /**
     * La dernière tentative de publication traitée de cette ressource, <strong>réussie ou non</strong>
     * (issue #371) : c'est elle qui dit pourquoi le moteur a refusé, alors que
     * {@link #lastPublishOf} ne retient que les succès, seuls à faire référence pour l'empreinte.
     */
    private Optional<Map<String, Object>> lastPublishAttemptOf(String agentId, String kind, String slug) {
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }
        for (AgentActionRow row : store.recentActions(agentId, 200)) {
            if ("content.publish".equals(row.type()) && kind.equals(row.params().get("kind"))
                    && slug.equals(row.params().get("id"))) {
                Optional<Map<String, Object>> details = detailsOf(row);
                if (details.isPresent()) {
                    return details;
                }
            }
        }
        return Optional.empty();
    }

    public Optional<Map<String, Object>> lastPublishOf(String agentId, String kind, String slug) {
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }
        for (AgentActionRow row : store.recentActions(agentId, 200)) {
            // Une publication ET un retour arrière laissent tous deux une empreinte connue sur DEV.
            //
            // Ne regarder que les publications était un vrai défaut, constaté sur le serveur réel :
            // après une restauration, DEV ne portait ni la source ni la dernière publication, donc
            // l'état affiché devenait « Conflit » — c'est-à-dire « quelqu'un d'autre a touché au
            // fichier », alors que c'était NOUS, à la demande de l'utilisateur. Un retour arrière
            // doit laisser « Différent », qui est la vérité : DEV porte volontairement une version
            // antérieure.
            // Issue #427 : une ressource publiée DANS un lot est une publication comme une autre.
            Optional<Map<String, Object>> fromBatch = batchPublicationOf(row, kind, slug);
            if (fromBatch.isPresent()) {
                return fromBatch;
            }
            boolean publication = "content.publish".equals(row.type());
            boolean rollback = "content.publish.rollback".equals(row.type());
            if (!publication && !rollback) {
                continue;
            }
            if (!kind.equals(row.params().get("kind")) || !slug.equals(row.params().get("id"))) {
                continue;
            }
            Optional<Map<String, Object>> details = detailsOf(row);
            // Seule une opération RÉUSSIE fait référence : un conflit n'a rien écrit, donc sa
            // sauvegarde (inexistante) ne doit pas apparaître comme restaurable.
            if (details.isPresent() && Boolean.TRUE.equals(details.get().get("ok"))) {
                return details;
            }
        }
        return Optional.empty();
    }

    /**
     * La part d'un lot publié (issue #427) qui concerne CETTE ressource, au format d'une
     * publication individuelle ({@code ok}, {@code code}, {@code devShaAfter},
     * {@code runtimeConfirmed}, {@code verifiedAt}, {@code backupPath}). Vide pour une simple
     * vérification, un lot refusé ou une ressource absente du lot : sans cela, une ressource mise
     * en jeu par « Mettre l'histoire en jeu » restait affichée « à publier » jusqu'au relevé suivant.
     */
    private Optional<Map<String, Object>> batchPublicationOf(AgentActionRow row, String kind,
                                                            String slug) {
        if (!"content.publish.batch".equals(row.type()) || "true".equals(row.params().get("dry_run"))) {
            return Optional.empty();
        }
        Optional<Map<String, Object>> details = detailsOf(row);
        if (details.isEmpty() || !Boolean.TRUE.equals(details.get().get("ok"))) {
            return Optional.empty();
        }
        for (Map<String, Object> item : listOfMaps(details.get().get("items"))) {
            if (kind.equals(str(item.get("kind"))) && slug.equals(str(item.get("slug")))
                    && Set.of("CREATED", "UPDATED", "UNCHANGED").contains(str(item.get("status")))) {
                Map<String, Object> view = new java.util.LinkedHashMap<>(item);
                view.put("ok", true);
                view.put("code", str(details.get().get("code")));
                view.put("verifiedAt", str(details.get().get("verifiedAt")));
                return Optional.of(view);
            }
        }
        return Optional.empty();
    }

    private Optional<Map<String, Object>> latestDetails(String agentId, String type) {
        // Dernière action RÉUSSIE du type : une action « Rafraîchir » plus récente encore en cours
        // (ou en échec) ne doit pas vider le catalogue déjà chargé (issue affichage /stories).
        return store.latestSuccessfulActionOfType(agentId, type)
                .flatMap(this::detailsOf);
    }

    private Optional<AgentActionRow> latestForPlayer(String agentId, String type, String player) {
        // Issue #317 : la dernière action de CE type pour CE joueur. Se contenter de la dernière du
        // type (puis filtrer) cachait le résultat d'un joueur dès qu'un autre en avait un plus récent.
        for (AgentActionRow r : store.recentActions(agentId, 300)) {
            if (type.equals(r.type()) && (player.isEmpty()
                    || player.equalsIgnoreCase(r.params().getOrDefault("player", "")))) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /** Une date à l'heure locale du navigateur, suivie de son ancienneté : « 13 oct., 18:22 (il y a 3 h) ». */
    private static String when(Instant at) {
        if (at == null) {
            return "<span class=\"muted\">date inconnue</span>";
        }
        String utc = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
                .withZone(java.time.ZoneOffset.UTC).format(at);
        return "<time datetime=\"" + at + "\" data-local-time>" + utc + "</time> <span class=\"muted\">("
                + Http.esc(ActionView.relativeTime(at, Instant.now())) + ")</span>";
    }

    private static Instant at(AgentActionRow row) {
        return row.completedAt() != null ? row.completedAt() : row.createdAt();
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
        // Issue #178 : villes du dernier relevé « city.list » (identifiant → nom affiché).
        var cityDir = cityDirectory(agentId);
        Map<String, String> cityNames = new java.util.LinkedHashMap<>();
        cityDir.cities().forEach(c -> cityNames.put(c.id(), c.name()));
        // Issue #177 : tables de récompenses du dernier relevé « reward.catalog.status ».
        Optional<Map<String, Object>> rewardDet = latestDetails(agentId, "reward.catalog.status")
                .filter(x -> Boolean.TRUE.equals(x.get("available")));
        Map<String, String> rewardTableNames = new java.util.LinkedHashMap<>();
        rewardDet.ifPresent(x -> asList(x.get("tables")).forEach(o -> {
            Map<String, Object> t = asMap(o);
            rewardTableNames.put(str(t.get("id")), str(t.get("label"))
                    + (Boolean.TRUE.equals(t.get("enabled")) ? ""
                    : com.lodygames.rpgquest.panel.content.RefData.DISABLED_TABLE_SUFFIX));
        }));
        return new com.lodygames.rpgquest.panel.content.RefData(
                quests, npcs, worlds, questsKnown, npcDet.isPresent(), !worlds.isEmpty(),
                npcNames, questNames, questOrigins, questPrereqs, itemCatalog(agentId),
                cityNames, cityDir.available(), rewardTableNames, rewardDet.isPresent());
    }

    /**
     * Issues #225 / #226 — le catalogue PNJ du serveur, projeté en types raisonnables.
     *
     * <p>Lecture du dernier relevé {@code npc.list} <strong>réussi</strong> uniquement : aucune
     * requête n'est déclenchée. Sans relevé, l'annuaire est explicitement indisponible, et
     * l'appelant doit refuser de conclure plutôt que de prendre une liste vide pour une absence de
     * dépendances — ce qui, devant un bouton « Supprimer », n'est pas la même chose du tout.</p>
     */
    public com.lodygames.rpgquest.panel.npc.NpcDirectory npcDirectory(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return com.lodygames.rpgquest.panel.npc.NpcDirectory.unavailable();
        }
        return latestDetails(agentId, "npc.list")
                .map(com.lodygames.rpgquest.panel.npc.NpcDirectory::from)
                .orElseGet(com.lodygames.rpgquest.panel.npc.NpcDirectory::unavailable);
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
    /**
     * Issue #248 — les dialogues réellement chargés par le serveur, d'après le dernier
     * {@code dialogue.list} réussi, <strong>avec sa date</strong> : c'est elle qui dit si l'on peut
     * conclure à l'absence de référence.
     */
    public com.lodygames.rpgquest.panel.content.RuntimeDialogueInventory runtimeDialogueInventory(
            String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return com.lodygames.rpgquest.panel.content.RuntimeDialogueInventory.unavailable();
        }
        return store.latestSuccessfulActionOfType(agentId, "dialogue.list")
                .flatMap(row -> detailsOf(row).map(details ->
                        com.lodygames.rpgquest.panel.content.RuntimeDialogueInventory.from(details,
                                row.completedAt())))
                .orElseGet(com.lodygames.rpgquest.panel.content.RuntimeDialogueInventory::unavailable);
    }

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

    // ---- Mobs spéciaux / boss (issue #169, lot 1 ; refonte de la présentation 18/10) -----------

    /** Issue #169 : trois vues distinctes, une adresse chacune (retour d'action compris). */
    static final String MOBS_PATH = "/mobs";
    static final String MOBS_COMMUNITY_PATH = "/mobs/community";
    static final String MOBS_SPAWNS_PATH = "/mobs/spawns";

    /** Vue « Profils » : catalogue, recherche, actions sur un profil, instances de test. */
    public String mobs(Session session, Map<String, String> q) {
        return mobsPage(session, q, MOBS_PATH);
    }

    /** Vue « Boss communautaires » : état, apparition de test, boss actifs, victoires, réglages. */
    public String mobsCommunity(Session session, Map<String, String> q) {
        return mobsPage(session, q, MOBS_COMMUNITY_PATH);
    }

    /** Vue « Apparitions des mobs spéciaux » : tirage aléatoire du Wild et profils concernés. */
    public String mobsSpawns(Session session, Map<String, String> q) {
        return mobsPage(session, q, MOBS_SPAWNS_PATH);
    }

    private String mobsPage(Session session, Map<String, String> q, String view) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("mob", "Mobs spéciaux & boss",
                "Créer, modifier et tester des créatures du Wild, sans YAML ni commande.", ""));
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        boolean canWrite = perms.can(session.effective(), Permission.MOB_WRITE);
        boolean canTest = perms.can(session.effective(), Permission.MOB_TEST_SPAWN);
        sb.append(agentPicker(agentId, view, ""));
        Optional<Map<String, Object>> mobList = latestDetails(agentId, "mob.list");
        Optional<Map<String, Object>> community = latestDetails(agentId, "boss.community.status");
        sb.append(mobsSummary(mobList, community));
        // Les profils rejetés restent visibles quelle que soit la vue : jamais masqués par la navigation.
        mobList.filter(d -> Boolean.TRUE.equals(d.get("hasIssues"))).ifPresent(d -> {
            StringBuilder issues = new StringBuilder();
            for (Object o : asList(d.get("issues"))) {
                issues.append("<div>").append(Http.esc(str(o))).append("</div>");
            }
            sb.append(Ui.banner("warning", "<strong>Profils rejetés au chargement :</strong>" + issues));
        });
        sb.append(mobsTabs(agentId, view));
        List<String> onlinePlayers = latestDetails(agentId, "player.list")
                .map(x -> asList(x.get("players"))).orElse(List.of())
                .stream().map(o -> str(asMap(o).get("name"))).filter(n -> !n.isEmpty()).toList();
        switch (view) {
            case MOBS_COMMUNITY_PATH -> sb.append(communityBossSection(session, agentId, canWrite, canTest,
                    onlinePlayers, community));
            case MOBS_SPAWNS_PATH -> sb.append(mobSpawnsView(session, agentId, canWrite, mobList));
            default -> sb.append(mobProfilesView(session, agentId, canWrite, canTest, onlinePlayers, mobList));
        }
        return sb.toString();
    }

    /** Résumé compact des états, d'après les derniers relevés (jamais un chiffre inventé). */
    private static String mobsSummary(Optional<Map<String, Object>> mobList, Optional<Map<String, Object>> community) {
        StringBuilder sb = new StringBuilder("<div class=\"cards mob-summary\">");
        if (mobList.isPresent()) {
            List<Map<String, Object>> profiles = asList(mobList.get().get("profiles")).stream().map(AgentPages::asMap)
                    .toList();
            long bosses = profiles.stream().filter(p -> "BOSS".equals(str(p.get("category")))).count();
            long disabled = profiles.stream().filter(p -> !Boolean.TRUE.equals(p.get("enabled"))).count();
            sb.append(Ui.statCard("mob", String.valueOf(profiles.size()), "profils",
                    "", Http.esc(bosses + " boss · " + (profiles.size() - bosses) + " spéciaux"
                            + (disabled > 0 ? " · " + disabled + " désactivé(s)" : ""))));
            Map<String, Object> s = asMap(mobList.get().get("spawnSettings"));
            boolean on = Boolean.TRUE.equals(s.get("enabled"));
            sb.append(Ui.statCard("target", on ? "actif" : "en pause", "tirage du Wild", on ? "ok" : "warn",
                    Http.esc("chance globale " + str(s.get("chance")))));
            Object tests = mobList.get().get("testInstances");
            sb.append(Ui.statCard("target", tests == null ? "?" : str(tests), "instances de test",
                    tests != null && asLong(tests) > 0 ? "warn" : "",
                    tests == null ? "plugin antérieur : nombre inconnu" : "en zone chargée"));
        } else {
            sb.append(Ui.statCard("mob", "—", "profils", "", "relevé pas encore chargé"));
        }
        if (community.isPresent() && Boolean.TRUE.equals(community.get().get("available"))) {
            Map<String, Object> c = community.get();
            boolean on = Boolean.TRUE.equals(c.get("enabled"));
            sb.append(Ui.statCard("mob", asList(c.get("instances")).size() + " / " + str(c.get("maxActive")),
                    "boss communautaires actifs", "", on ? "apparitions automatiques actives"
                            : "apparitions automatiques en pause"));
        }
        return sb.append("</div>").toString();
    }

    private static String mobsTabs(String agentId, String active) {
        StringBuilder sb = new StringBuilder("<ul class=\"nav nav-pills mob-tabs\">");
        String[][] tabs = {{MOBS_PATH, "Profils"}, {MOBS_COMMUNITY_PATH, "Boss communautaires"},
                {MOBS_SPAWNS_PATH, "Apparitions des mobs spéciaux"}};
        for (String[] t : tabs) {
            boolean on = t[0].equals(active);
            sb.append("<li class=\"nav-item\"><a class=\"nav-link").append(on ? " active\" aria-current=\"page" : "")
                    .append("\" href=\"").append(t[0]).append("?agent=").append(Http.esc(agentId)).append("\">")
                    .append(Http.esc(t[1])).append("</a></li>");
        }
        return sb.append("</ul>").toString();
    }

    /** Bloc repliable des explications longues : lues à la demande, jamais en travers du parcours. */
    private static String mobFold(String title, String bodyHtml) {
        return "<details class=\"mob-fold\"><summary>" + Icons.icon("info") + Http.esc(title) + "</summary>"
                + "<div class=\"mob-fold-b\">" + bodyHtml + "</div></details>";
    }

    private String mobProfilesView(Session session, String agentId, boolean canWrite, boolean canTest,
                                   List<String> onlinePlayers, Optional<Map<String, Object>> details) {
        StringBuilder sb = new StringBuilder();
        // Issue #172 : catalogues réels du serveur, émis une fois (formulaires de création et d'édition).
        sb.append(mobCatalogDatalists(agentId));
        sb.append(bossRewardDatalist(agentId));
        boolean catalogsLoaded = latestDetails(agentId, "mob.catalogs").isPresent();
        if (!catalogsLoaded) {
            sb.append(Ui.banner("warn", "Les listes Minecraft (entités, particules, sons, biomes) ne sont pas "
                    + "encore chargées : les champs correspondants restent en saisie libre. Cliquer sur "
                    + "<strong>« Listes Minecraft »</strong> pour les récupérer depuis le serveur."));
        }
        // Hiérarchie : action principale, secondaires, puis relevés discrets à droite.
        sb.append("<div class=\"mob-toolbar\"><div class=\"mob-toolbar-main\">");
        if (canWrite) {
            sb.append("<button class=\"btn sm\" type=\"button\" data-bs-toggle=\"collapse\" "
                    + "data-bs-target=\"#mob-new-def\" aria-expanded=\"false\" aria-controls=\"mob-new-def\">")
                    .append(Icons.icon("plus")).append("Nouveau profil</button>");
            // Issue #214 : modèle complet, import d'un fichier rempli (par une IA ou à la main), génération.
            sb.append("<a class=\"btn secondary sm\" href=\"/mobs/import?agent=").append(Http.esc(agentId))
                    .append("\">").append(Icons.icon("export")).append("Modèles, IA et import</a>");
        }
        sb.append("</div><div class=\"mob-toolbar-side\">");
        sb.append(compactRefresh(session, agentId, "mob.list", "Rafraîchir", "secondary sm", MOBS_PATH));
        sb.append(compactRefresh(session, agentId, "mob.catalogs", "Listes Minecraft",
                catalogsLoaded ? "secondary sm" : "btn-warning", MOBS_PATH));
        if (perms.can(session.effective(), Permission.ECONOMY_READ)) {
            // Issue #177 : alimente la liste « Récompenses de participation » (récompenses + tirages).
            sb.append(compactRefresh(session, agentId, "reward.catalog.status", "Récompenses",
                    latestDetails(agentId, "reward.catalog.status").isPresent() ? "secondary sm"
                            : "btn-warning", MOBS_PATH));
        }
        sb.append("</div></div>");
        if (canWrite) {
            sb.append("<div class=\"collapse\" id=\"mob-new-def\"><div class=\"card card-body npc-formcard\">");
            sb.append(MobImportPages.shortcuts(agentId));
            sb.append(mobDefForm(session, agentId, null, "mob-new-def"));
            sb.append("</div></div>");
        }
        if (details.isEmpty()) {
            return sb.append(Ui.empty("mob", "Aucun catalogue chargé — cliquer sur « Rafraîchir ».")).toString();
        }
        Map<String, Object> d = details.get();
        List<Object> profiles = asList(d.get("profiles"));
        if (profiles.isEmpty()) {
            sb.append(Ui.empty("mob", "Aucun profil : créer le premier avec « Nouveau profil »."));
        } else {
            // Issue #172 : recherche nom/ID + filtres, même composant que les autres catalogues du panel.
            sb.append(listControls("mobs", "Rechercher un profil (nom ou identifiant)…",
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
        }
        sb.append(mobFold("Comment lire les compteurs « en jeu »", "<p><strong>En jeu</strong> : créatures de ce "
                + "profil repérées par le serveur <strong>depuis son dernier démarrage</strong> (apparues, ou "
                + "retrouvées quand leur zone s'est chargée) et pas encore mortes. Une créature restée dans une zone "
                + "que personne n'a rechargée depuis le redémarrage n'y figure pas encore : elle existe toujours et "
                + "sera comptée dès que sa zone se chargera.</p><p><strong>Boss communautaire actif</strong> : lu dans "
                + "l'état enregistré des boss communautaires, zones non chargées comprises. C'est pourquoi un boss "
                + "peut être « actif » alors que le profil affiche « 0 en jeu » : il attend dans une zone non "
                + "chargée.</p><p><strong>Plafond</strong> d'un profil : il limite les apparitions naturelles ; une "
                + "apparition de test ne le respecte pas, et peut donc le dépasser.</p>"));
        if (canTest) {
            sb.append(mobTestClearCard(session, agentId, d, profiles));
        }
        return sb.toString();
    }

    /**
     * Issue #169 : le nettoyage dit sa portée <strong>avant</strong> d'être envoyé — combien, de quels
     * profils, ce qu'il ne touche pas — et ne part qu'avec la case cochée (le navigateur l'exige,
     * le panel et le plugin le revérifient). Rien à supprimer : pas de bouton.
     */
    private String mobTestClearCard(Session session, String agentId, Map<String, Object> d, List<Object> profiles) {
        StringBuilder sb = new StringBuilder("<div class=\"mob-danger-zone\" id=\"mob-test-clear\">");
        sb.append("<p class=\"npc-fs-h\">Instances de test</p>");
        Object total = d.get("testInstances");
        StringBuilder perProfile = new StringBuilder();
        for (Object o : profiles) {
            Map<String, Object> p = asMap(o);
            long n = asLong(p.get("testPopulation"));
            if (n > 0) {
                perProfile.append(perProfile.isEmpty() ? "" : ", ")
                        .append(Http.esc(MiniText.plain(str(p.get("displayName"))))).append(" × ").append(n);
            }
        }
        String keeps = "Les mobs spéciaux apparus naturellement, les boss réels et les boss communautaires de test "
                + "(retirés depuis l'onglet « Boss communautaires ») ne sont jamais touchés.";
        if (total != null && asLong(total) == 0) {
            return sb.append("<p class=\"muted mb-0\">Aucune instance de test en jeu au dernier relevé "
                    + "(« Rafraîchir » pour le mettre à jour).</p></div>").toString();
        }
        String scope = total == null
                ? "Nombre inconnu : le plugin installé ne le transmet pas encore. Toutes les instances de test "
                        + "des zones chargées seront supprimées."
                : asLong(total) + " instance(s) de test en zone chargée au dernier relevé : " + perProfile + ".";
        sb.append("<p class=\"mb-1\">").append(total == null ? Http.esc(scope) : scope).append("</p>")
                .append("<p class=\"muted small\">").append(keeps)
                .append(" Une instance dans une zone non chargée reste en place.</p>");
        sb.append("<form method=\"post\" action=\"/agents/action\" class=\"mob-danger-form\">")
                .append(hiddenActionFields(session, agentId, "mob.test.clear", MOBS_PATH))
                .append(confirmBox(total == null ? "Je confirme : supprimer les instances de test"
                        : "Je confirme : supprimer ces " + asLong(total) + " instance(s) de test"))
                .append("<button class=\"btn danger sm\" type=\"submit\">").append(Icons.icon("trash"))
                .append("Nettoyer les instances de test</button></form>");
        return sb.append("</div>").toString();
    }

    /** Vue « Apparitions des mobs spéciaux » : interrupteur global, chance, plafond, profils tirés. */
    private String mobSpawnsView(Session session, String agentId, boolean canWrite,
                                 Optional<Map<String, Object>> details) {
        StringBuilder sb = new StringBuilder("<div class=\"mob-toolbar\"><div class=\"mob-toolbar-main\"></div>"
                + "<div class=\"mob-toolbar-side\">")
                .append(compactRefresh(session, agentId, "mob.list", "Rafraîchir", "secondary sm", MOBS_SPAWNS_PATH))
                .append("</div></div>");
        if (details.isEmpty()) {
            return sb.append(Ui.empty("mob", "Aucun relevé chargé — cliquer sur « Rafraîchir ».")).toString();
        }
        Map<String, Object> d = details.get();
        sb.append("<div class=\"mob-cols\"><div class=\"mob-col\">");
        sb.append("<p class=\"npc-fs-h\">Tirage aléatoire du Wild</p>");
        sb.append(spawnSettingsForm(session, agentId, asMap(d.get("spawnSettings")), canWrite));
        sb.append(mobFold("Comment fonctionne le tirage", "<p>« Chance globale » filtre d'abord <strong>chaque"
                + "</strong> apparition naturelle éligible, avant même d'examiner les profils. Seuls les profils dont "
                + "le tirage individuel (leur « chance d'apparition ») réussit ensuite participent ; si plusieurs "
                + "réussissent en même temps, un tirage pondéré (poids = leur propre chance) choisit lequel "
                + "s'applique.</p><p>Les profils <strong>Boss</strong> n'entrent jamais dans ce tirage : un boss de "
                + "quête apparaît sur demande, un boss communautaire par son propre calendrier (onglet « Boss "
                + "communautaires »).</p>"));
        sb.append("</div><div class=\"mob-col\">");
        sb.append("<p class=\"npc-fs-h\">Profils tirés au hasard</p>");
        List<Map<String, Object>> specials = asList(d.get("profiles")).stream().map(AgentPages::asMap)
                .filter(p -> !"BOSS".equals(str(p.get("category")))).toList();
        if (specials.isEmpty()) {
            sb.append(Ui.empty("mob", "Aucun profil « Mob spécial » : rien n'est tiré au hasard."));
        } else {
            sb.append(Ui.tableOpen("Profil", "Chance", "Plafond", "En jeu"));
            for (Map<String, Object> p : specials) {
                boolean enabled = Boolean.TRUE.equals(p.get("enabled"));
                String max = str(p.get("maxPopulation"));
                sb.append("<tr><td>").append(Http.esc(MiniText.plain(str(p.get("displayName")))))
                        .append(enabled ? "" : " <span class=\"badge text-bg-secondary\">désactivé</span>")
                        .append("</td><td>").append(Http.esc(str(p.get("spawnChance")))).append("</td><td>")
                        .append(max.isBlank() || "null".equals(max) ? "aucun" : Http.esc(max)).append("</td><td>")
                        .append(Http.esc(str(p.get("alivePopulation")))).append("</td></tr>");
            }
            sb.append(Ui.tableClose());
            sb.append("<p class=\"muted small\">Chance, plafond et zones se règlent dans la fiche du profil "
                    + "(onglet « Profils » → Modifier).</p>");
        }
        sb.append("</div></div>");
        return sb.toString();
    }

    /** Issue #214 — derniers relevés utiles à l'import de profils de mob. */
    public com.lodygames.rpgquest.panel.mob.MobImportContext mobImportContext(String agentId) {
        return com.lodygames.rpgquest.panel.mob.MobImportContext.of(
                latestDetails(agentId, "mob.catalogs").orElse(null),
                latestDetails(agentId, "mob.list").orElse(null),
                latestDetails(agentId, "reward.catalog.status").orElse(null));
    }

    /** Issue #214 — détails d'une action précise (vérification d'un fichier importé). */
    public Optional<Map<String, Object>> actionDetails(AgentActionRow row) {
        return detailsOf(row);
    }

    // ---- Boss communautaires (issue #175) --------------------------------------------------------

    /**
     * Issue #175 — vue « Boss communautaires » : état du scheduler, apparition de test, boss actifs
     * (lieu, biome, waypoint) et retrait avec confirmation, victoires, puis réglages et explications
     * repliés (#169). Rien n'est inventé : sans relevé {@code boss.community.status}, la vue invite à le charger.
     */
    private String communityBossSection(Session session, String agentId, boolean canWrite, boolean canTest,
                                        List<String> onlinePlayers, Optional<Map<String, Object>> loaded) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"mob-toolbar\"><div class=\"mob-toolbar-main\"><span class=\"npc-catbar-t\">"
                        + "Boss communautaires du Wild</span></div><div class=\"mob-toolbar-side\">")
                .append(compactRefresh(session, agentId, "boss.community.status", "Rafraîchir l'état",
                        "secondary sm", MOBS_COMMUNITY_PATH))
                .append("</div></div>");
        if (loaded.isEmpty()) {
            return sb.append(Ui.empty("mob", "État pas encore chargé — cliquer sur « Rafraîchir l'état ».")).toString();
        }
        Map<String, Object> d = loaded.get();
        if (!Boolean.TRUE.equals(d.get("available"))) {
            return sb.append(Ui.banner("warn", "Le plugin installé ne connaît pas encore les boss communautaires "
                    + "(version antérieure).")).toString();
        }
        boolean enabled = Boolean.TRUE.equals(d.get("enabled"));
        long now = asLong(d.get("now"));
        List<Object> instances = asList(d.get("instances"));
        List<Object> profiles = asList(d.get("profiles"));
        long communityProfiles = profiles.stream().map(AgentPages::asMap)
                .filter(p -> "COMMUNITY".equals(str(p.get("bossMode"))) && Boolean.TRUE.equals(p.get("enabled")))
                .count();

        sb.append("<div class=\"mob-cols\"><div class=\"mob-col\">");
        sb.append("<div class=\"card card-body mb-3\">");
        sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Apparitions automatiques</span> ")
                .append(enabled ? "<span class=\"badge text-bg-success\">actives</span>"
                        : "<span class=\"badge text-bg-secondary\">en pause</span>").append("</p>");
        sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Boss actifs</span> ")
                .append(instances.size()).append(" / ").append(Http.esc(str(d.get("maxActive"))))
                .append(" <span class=\"muted small\">(état enregistré, zones non chargées comprises)</span></p>");
        Object nextDue = d.get("nextDue");
        sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Prochain essai</span> ")
                .append(!enabled ? "aucun (en pause)" : nextDue == null ? "pas encore planifié"
                        : Http.esc(relative(asLong(nextDue) - now)))
                .append("</p>");
        long lastSpawn = asLong(d.get("lastSpawn"));
        sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Dernière apparition automatique</span> ")
                .append(lastSpawn <= 0 ? "jamais" : Http.esc(relative(lastSpawn - now))).append("</p>");
        String lastOutcome = str(d.get("lastOutcome"));
        if (!lastOutcome.isBlank()) {
            sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Dernier essai</span> ")
                    .append(Http.esc(lastOutcome)).append("</p>");
        }
        if (communityProfiles == 0) {
            sb.append(Ui.banner("warn", "Aucun profil n'est encore un <strong>boss communautaire</strong> : "
                    + "onglet « Profils », ouvrir un profil <strong>Boss</strong>, « Modifier », puis choisir le "
                    + "type « Boss communautaire »."));
        }
        sb.append("</div>");
        if (canTest) {
            sb.append(communityBossTestForm(session, agentId, profiles, onlinePlayers));
        }
        sb.append("</div><div class=\"mob-col\">");
        sb.append("<p class=\"npc-fs-h\">Boss actifs en ce moment</p>");
        if (instances.isEmpty()) {
            sb.append(Ui.empty("mob", "Aucun boss communautaire actif."));
        } else {
            sb.append("<div class=\"list-group mb-3\">");
            for (Object o : instances) {
                sb.append(communityBossInstanceItem(session, agentId, asMap(o), now, canTest));
            }
            sb.append("</div>");
        }
        if (canTest && instances.stream().map(AgentPages::asMap).anyMatch(i -> Boolean.TRUE.equals(i.get("test")))) {
            long tests = instances.stream().map(AgentPages::asMap).filter(i -> Boolean.TRUE.equals(i.get("test"))).count();
            sb.append("<div class=\"mob-danger-zone\">").append(communityBossRemoveForm(session, agentId, "tests",
                    "Retirer toutes les apparitions de test",
                    "Je confirme : retirer les " + tests + " boss de test (sans récompense, les boss réels restent)"))
                    .append("</div>");
        }
        sb.append(communityBossDefeats(asList(d.get("recentDefeats")), now));
        sb.append("</div></div>");

        if (canWrite) {
            sb.append("<details class=\"mob-fold\"><summary>").append(Icons.icon("wrench"))
                    .append("Réglages des apparitions automatiques</summary><div class=\"mob-fold-b\">")
                    .append(communityBossSettingsForm(session, agentId, d)).append("</div></details>");
        }
        sb.append(rewardTablesSection(d.get("rewardTables")));
        sb.append(mobFold("Comment fonctionnent les boss communautaires", "<p>Un boss communautaire apparaît "
                + "<strong>tout seul</strong> dans le Wild, <strong>même si tous les joueurs sont au Hub ou dans leurs "
                + "Claims</strong> : le serveur choisit un endroit sûr à 40–96 blocs d'un waypoint du Wild (ou d'un "
                + "joueur qui s'y trouve), jamais sur un joueur, ni dans le Hub, les Claims, une ville ou une zone "
                + "protégée. Sans aucun waypoint dans le Wild, rien n'apparaît et la raison s'affiche dans « Dernier "
                + "essai ».</p><p>Tout le serveur est prévenu (nom, biome, waypoint le plus proche) et les joueurs qui "
                + "se connectent plus tard reçoivent un rappel. À sa mort, il lâche les objets de son profil et chaque "
                + "joueur qui l'a touché depuis sa dernière mort, encore vivant, dans le même monde et assez près de "
                + "lui (distance réglable, 100 blocs par défaut), reçoit les <strong>récompenses de participation"
                + "</strong> du profil (remise sûre, jamais deux fois).</p>"));
        return sb.toString();
    }

    // ---- Prix et coûts : catalogue central prices.yml (issue #336) -------------------------

    private static final String PRICES_PATH = "/prices";
    /** Lignes d'ingrédients proposées : celles du prix, plus deux vides (neuf au plus). */
    private static final int PRICE_INGREDIENT_BLANK_ROWS = 2;
    private static final List<String[]> PRICE_TYPES = List.of(
            new String[] {"SELL_TO_PLAYER", "Le PNJ vend", "le joueur paie et reçoit l'objet"},
            new String[] {"BUY_FROM_PLAYER", "Le PNJ rachète", "le joueur donne l'objet et reçoit les pièces"},
            new String[] {"CRAFT", "Le PNJ fabrique", "le joueur donne les ingrédients et/ou paie, il reçoit l'objet"});

    /**
     * Page {@code /prices} (issue #336) : tous les tarifs du jeu au même endroit, sans YAML — ce
     * qu'un PNJ vend, rachète ou fabrique, pour combien de pièces et contre quels ingrédients. Tout
     * passe par les actions agent {@code price.*}, validées par le serveur avant écriture (montants,
     * objets, boucles d'enrichissement).
     */
    public String prices(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("money", "Prix et coûts",
                "Les tarifs des marchands et artisans, réunis en un seul catalogue : vente au joueur, rachat au "
                        + "joueur et fabrication (pièces + objets exacts). Aucun prix n'est jamais calculé ni mis par "
                        + "défaut : une offre sans prix actif est affichée « indisponible » en jeu.", ""));
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        boolean canWrite = perms.can(session.effective(), Permission.ECONOMY_WRITE);
        sb.append(agentPicker(agentId, PRICES_PATH, ""));
        Optional<Map<String, Object>> loaded = latestDetails(agentId, "price.catalog.status");
        Map<String, Object> d = loaded.orElse(Map.of());
        sb.append("<div class=\"npc-catbar\"><span class=\"npc-catbar-t\">Catalogue des prix</span>")
                .append(compactRefresh(session, agentId, "price.catalog.status", "Rafraîchir",
                        loaded.isPresent() ? "btn-outline-primary" : "btn-warning", PRICES_PATH))
                .append("</div>");
        if (loaded.isEmpty()) {
            return sb.append(Ui.empty("money", "État pas encore chargé — cliquer sur « Rafraîchir ».")).toString();
        }
        if (!Boolean.TRUE.equals(d.get("available"))) {
            return sb.append(Ui.banner("warn", "Le plugin installé ne connaît pas encore le catalogue des prix "
                    + "(version antérieure).")).toString();
        }
        sb.append(priceCatalogBanner(d));
        boolean writable = canWrite && Boolean.TRUE.equals(d.get("valid"));
        List<Object> prices = asList(d.get("prices"));
        sb.append(priceItemDatalist(agentId, d));
        sb.append("<div class=\"npc-catbar mt-3\"><span class=\"npc-catbar-t\">Prix (").append(prices.size())
                .append(")</span>");
        if (writable) {
            sb.append("<button class=\"btn btn-sm btn-primary\" type=\"button\" data-bs-toggle=\"collapse\" "
                    + "data-bs-target=\"#pr-new\" aria-expanded=\"false\" aria-controls=\"pr-new\">")
                    .append(Icons.icon("plus")).append("Nouveau prix</button>");
        }
        sb.append("</div><p class=\"muted small\">Un prix <strong>désactivé</strong> rend indisponibles les offres qui "
                + "le citent (rien ne s'échange, aucun solde ni objet n'est touché). Pas de suppression : désactiver "
                + "suffit et ne casse aucun marchand. Les prix marqués <em>démonstration</em> sont des valeurs d'essai "
                + "à revoir avant d'ouvrir l'économie aux joueurs.</p>");
        if (writable) {
            sb.append("<div class=\"collapse\" id=\"pr-new\"><div class=\"card card-body npc-formcard\">")
                    .append("<p class=\"npc-fs-h\">Nouveau prix</p>")
                    .append(priceForm(session, agentId, null, "pr-new", str(d.get("currency"))))
                    .append("</div></div>");
        }
        if (prices.isEmpty()) {
            sb.append(Ui.empty("money", writable ? "Aucun prix — « Nouveau prix » pour en créer un."
                    : "Aucun prix défini."));
        }
        for (String[] type : PRICE_TYPES) {
            List<Map<String, Object>> ofType = prices.stream().map(AgentPages::asMap)
                    .filter(p -> type[0].equals(str(p.get("type")))).toList();
            if (ofType.isEmpty()) {
                continue;
            }
            sb.append(Ui.sectionTitle("money", type[1] + " (" + ofType.size() + ")"));
            for (Map<String, Object> p : ofType) {
                sb.append(priceCard(session, agentId, p, writable, str(d.get("currency"))));
            }
        }
        sb.append(priceMerchantsSection(asList(d.get("merchants"))));
        return sb.toString();
    }

    /** Fichier absent, refusé, avertissements : chaque cas dit ce qu'il implique en jeu. */
    private static String priceCatalogBanner(Map<String, Object> d) {
        StringBuilder sb = new StringBuilder();
        if (!Boolean.TRUE.equals(d.get("fileExists"))) {
            sb.append(Ui.banner("info", "<strong>Aucun prix défini sur ce serveur</strong> (<code>prices.yml</code> "
                    + "absent). Le premier prix enregistré ici crée le fichier. Les marchands à prix fixé dans leur "
                    + "propre fichier continuent de fonctionner comme avant."));
        } else if (!Boolean.TRUE.equals(d.get("valid"))) {
            StringBuilder errors = new StringBuilder("<ul class=\"mb-0\">");
            asList(d.get("errors")).stream().limit(5)
                    .forEach(e -> errors.append("<li>").append(Http.esc(str(e))).append("</li>"));
            sb.append(Ui.banner("err", "<strong>Le serveur refuse <code>prices.yml</code></strong> : aucun prix du "
                    + "catalogue ne s'applique (toutes les offres liées sont indisponibles) et rien ne peut être "
                    + "enregistré ici tant qu'il n'est pas corrigé." + errors + "</ul>"));
        }
        List<Object> warnings = asList(d.get("warnings"));
        if (!warnings.isEmpty()) {
            StringBuilder list = new StringBuilder("<ul class=\"mb-0\">");
            warnings.stream().limit(10).forEach(w -> list.append("<li>").append(Http.esc(str(w))).append("</li>"));
            sb.append(Ui.banner("warn", "<strong>À vérifier</strong> (le catalogue s'applique quand même) :"
                    + list + "</ul>"));
        }
        return sb.toString();
    }

    /** Objets Minecraft (nom français) et RPGQuest connus du serveur. */
    private String priceItemDatalist(String agentId, Map<String, Object> d) {
        StringBuilder sb = new StringBuilder("<datalist id=\"dl-price-items\">");
        for (Object custom : asList(d.get("customItems"))) {
            sb.append("<option value=\"").append(Http.esc(str(custom))).append("\" label=\"Objet RPGQuest\">");
        }
        for (String material : referenceData(agentId).materials()) {
            sb.append("<option value=\"").append(Http.esc(material)).append("\" label=\"")
                    .append(Http.esc(MaterialNames.french(material))).append("\">");
        }
        return sb.append("</datalist>").toString();
    }

    private static String priceItemText(Object itemId, Object amount) {
        String id = str(itemId);
        String name = id.indexOf(':') >= 0 ? id : MaterialNames.french(id) + " (" + id + ")";
        return str(amount) + " × " + name;
    }

    private static String priceTypeLabel(String type) {
        return PRICE_TYPES.stream().filter(t -> t[0].equals(type)).map(t -> t[1]).findFirst().orElse(type);
    }

    private String priceCard(Session session, String agentId, Map<String, Object> p, boolean canWrite,
                             String currency) {
        String key = str(p.get("key"));
        String type = str(p.get("type"));
        boolean enabled = !Boolean.FALSE.equals(p.get("enabled"));
        String uid = "pr-" + key.replaceAll("[^a-z0-9_-]", "_");
        String coins = str(p.get("coins")) + " " + Http.esc(currency.isBlank() ? "pièces" : currency);
        List<Object> usages = asList(p.get("usages"));
        StringBuilder sb = new StringBuilder("<div class=\"card card-body mb-2\" id=\"").append(uid).append("\">");
        sb.append("<div><strong>").append(Http.esc(str(p.get("label")))).append("</strong> <code>")
                .append(Http.esc(key)).append("</code> ")
                .append(enabled ? "<span class=\"badge text-bg-success\">actif</span>"
                        : "<span class=\"badge text-bg-secondary\">désactivé</span>")
                .append(Boolean.TRUE.equals(p.get("demo")) ? " <span class=\"badge text-bg-warning\">démonstration</span>"
                        : "")
                .append("</div><div>");
        switch (type) {
            case "SELL_TO_PLAYER" -> sb.append("Le joueur paie <strong>").append(coins).append("</strong> et reçoit ")
                    .append(Http.esc(priceItemText(p.get("item"), p.get("amount")))).append('.');
            case "BUY_FROM_PLAYER" -> sb.append("Le joueur donne ")
                    .append(Http.esc(priceItemText(p.get("item"), p.get("amount")))).append(" et reçoit <strong>")
                    .append(coins).append("</strong>.");
            default -> {
                List<String> parts = new ArrayList<>();
                asList(p.get("ingredients")).forEach(o -> parts.add(priceItemText(asMap(o).get("item"),
                        asMap(o).get("amount"))));
                sb.append("Le joueur donne ").append(Http.esc(String.join(" + ", parts))).append(" et paie <strong>")
                        .append(coins).append("</strong> ; il reçoit ")
                        .append(Http.esc(priceItemText(p.get("item"), p.get("amount")))).append('.');
            }
        }
        sb.append("</div><div class=\"muted small\">").append(usages.isEmpty()
                ? "Cité par aucun marchand pour l'instant."
                : "Cité par : " + Http.esc(String.join(", ", usages.stream().map(AgentPages::str).toList())) + ".")
                .append("</div>");
        if (canWrite) {
            sb.append("<div class=\"d-flex flex-wrap gap-2 mt-2\">")
                    .append("<button class=\"btn secondary sm\" type=\"button\" data-bs-toggle=\"collapse\" "
                            + "data-bs-target=\"#").append(uid).append("-edit\" aria-expanded=\"false\">")
                    .append(Icons.icon("edit")).append("Modifier</button>")
                    .append("<form method=\"post\" action=\"/agents/action\" class=\"d-inline\">")
                    .append(priceHiddenFields(session, agentId, "price.toggle"))
                    .append("<input type=\"hidden\" name=\"price_key\" value=\"").append(Http.esc(key)).append("\">")
                    .append("<input type=\"hidden\" name=\"enabled\" value=\"").append(!enabled).append("\">")
                    .append("<button class=\"btn sm").append(enabled ? " secondary" : "").append("\" type=\"submit\">")
                    .append(enabled ? "Désactiver" : "Activer").append("</button></form>")
                    .append("<button class=\"btn secondary sm\" type=\"button\" data-bs-toggle=\"collapse\" "
                            + "data-bs-target=\"#").append(uid).append("-dup\" aria-expanded=\"false\">")
                    .append(Icons.icon("plus")).append("Dupliquer</button></div>");
            if (enabled && !usages.isEmpty()) {
                sb.append("<div class=\"form-text\">Désactiver : les offres de ces marchands deviendront "
                        + "« indisponibles » en jeu.</div>");
            }
            sb.append("<div class=\"collapse mt-2\" id=\"").append(uid).append("-dup\">")
                    .append("<form method=\"post\" action=\"/agents/action\" class=\"npc-def-form\">")
                    .append(priceHiddenFields(session, agentId, "price.duplicate"))
                    .append("<input type=\"hidden\" name=\"price_key\" value=\"").append(Http.esc(key)).append("\">")
                    .append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid)
                    .append("-dup-id\">Identifiant de la copie</label><input class=\"form-control\" id=\"").append(uid)
                    .append("-dup-id\" name=\"new_key\" required maxlength=\"64\" pattern=\"[a-z0-9_\\-]{1,64}\" value=\"")
                    .append(Http.esc((key.length() > 58 ? key.substring(0, 58) : key) + "_copie")).append("\"></div>")
                    .append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid)
                    .append("-dup-l\">Nom de la copie (facultatif)</label><input class=\"form-control\" id=\"")
                    .append(uid).append("-dup-l\" name=\"new_label\" maxlength=\"120\"></div>")
                    .append("<div class=\"form-text mb-2\">La copie est créée <strong>désactivée</strong> : la "
                            + "modifier, puis l'activer.</div>")
                    .append("<button class=\"btn sm\" type=\"submit\">Créer la copie</button></form></div>")
                    .append("<div class=\"collapse mt-2\" id=\"").append(uid).append("-edit\"><div class=\"card "
                            + "card-body npc-formcard\">").append(priceForm(session, agentId, p, uid + "-f", currency))
                    .append("</div></div>");
        }
        return sb.append("</div>").toString();
    }

    /** Formulaire de prix, création ({@code existing == null}) ou modification. */
    private String priceForm(Session session, String agentId, Map<String, Object> existing, String uid,
                             String currency) {
        boolean create = existing == null;
        String type = create ? "SELL_TO_PLAYER" : str(existing.get("type"));
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"/agents/action\" class=\"npc-def-form\">");
        sb.append(priceHiddenFields(session, agentId, "price.save"));
        sb.append("<input type=\"hidden\" name=\"create\" value=\"").append(create).append("\">");
        if (!create) {
            sb.append("<input type=\"hidden\" name=\"price_key\" value=\"").append(Http.esc(str(existing.get("key"))))
                    .append("\"><input type=\"hidden\" name=\"expected_version\" value=\"")
                    .append(Http.esc(str(existing.get("version")))).append("\">");
        }
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid).append("-l\">Nom du prix</label>")
                .append("<input class=\"form-control\" id=\"").append(uid).append("-l\" name=\"label\" required "
                        + "maxlength=\"120\" value=\"").append(Http.esc(create ? "" : str(existing.get("label"))))
                .append("\">").append(fieldHelp("Pour vous retrouver dans la liste (les joueurs voient l'objet et le "
                        + "montant).", "Pain du village", "aucun (obligatoire)", null)).append("</div>");
        if (create) {
            sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid).append("-id\">Identifiant "
                    + "(facultatif)</label><input class=\"form-control\" id=\"").append(uid).append("-id\" "
                    + "name=\"price_key\" maxlength=\"64\" pattern=\"[a-z0-9_\\-]{0,64}\">")
                    .append(fieldHelp("Ce qu'un marchand cite (<code>price-ref</code>). Vide : tiré du nom (« Pain du "
                            + "village » → <code>pain_du_village</code>). Ne change plus ensuite.", "pain_x4",
                            "tiré du nom", null)).append("</div>");
        }
        sb.append("<fieldset class=\"mb-2\"><legend class=\"form-label fs-6 mb-1\">Ce que fait le PNJ</legend>");
        for (String[] t : PRICE_TYPES) {
            String id = uid + "-t-" + t[0].toLowerCase(java.util.Locale.ROOT);
            sb.append("<div class=\"form-check\"><input class=\"form-check-input\" type=\"radio\" name=\"price_type\" id=\"")
                    .append(id).append("\" value=\"").append(t[0]).append('"').append(t[0].equals(type) ? " checked" : "")
                    .append(" required><label class=\"form-check-label\" for=\"").append(id).append("\"><strong>")
                    .append(t[1]).append("</strong> — ").append(t[2]).append("</label></div>");
        }
        sb.append("</fieldset>");
        String item = create ? "" : str(existing.get("item"));
        sb.append("<div class=\"row g-2 mb-2\"><div class=\"col-12 col-md-7\"><label class=\"form-label\" for=\"")
                .append(uid).append("-it\">Objet vendu, racheté ou fabriqué</label><input class=\"form-control\" id=\"")
                .append(uid).append("-it\" name=\"item\" required list=\"dl-price-items\" autocomplete=\"off\" value=\"")
                .append(Http.esc(item)).append("\" placeholder=\"Taper pour chercher…\">")
                .append(item.isEmpty() || item.indexOf(':') >= 0 ? ""
                        : "<div class=\"form-text mt-0\">" + Http.esc(MaterialNames.french(item)) + "</div>")
                .append("</div><div class=\"col-6 col-md-2\"><label class=\"form-label\" for=\"").append(uid)
                .append("-am\">Quantité</label><input class=\"form-control\" type=\"number\" min=\"1\" max=\"64\" id=\"")
                .append(uid).append("-am\" name=\"amount\" required value=\"")
                .append(Http.esc(create ? "1" : str(existing.get("amount")))).append("\"></div>")
                .append("<div class=\"col-6 col-md-3\"><label class=\"form-label\" for=\"").append(uid)
                .append("-co\">Pièces</label><input class=\"form-control\" type=\"number\" min=\"0\" max=\"1000000000\" "
                        + "step=\"1\" id=\"").append(uid).append("-co\" name=\"coins\" required value=\"")
                .append(Http.esc(create ? "" : str(existing.get("coins")))).append("\" placeholder=\"obligatoire\">")
                .append("</div></div><div class=\"form-text mb-2\">Pièces : payées par le joueur (vente, fabrication) "
                        + "ou reçues (rachat), en ").append(Http.esc(currency.isBlank() ? "pièces" : currency))
                .append(". Aucune valeur par défaut : écrire 0 seulement si c'est vraiment gratuit. Le serveur refuse "
                        + "un rachat plus cher que la vente du même objet (les joueurs gagneraient des pièces sans fin)."
                        + "</div>");
        List<Object> ingredients = create ? List.of() : asList(existing.get("ingredients"));
        int rows = Math.min(com.lodygames.rpgquest.panel.agent.PriceForm.MAX_INGREDIENTS,
                Math.max(2, ingredients.size() + PRICE_INGREDIENT_BLANK_ROWS));
        sb.append("<p class=\"form-label mb-1\">Ingrédients exacts — <em>fabrication seulement</em></p>"
                + "<div class=\"form-text mb-1\">Laisser vide pour une vente ou un rachat. Une fabrication "
                + "demande des ingrédients, des pièces, ou les deux (recette « pièces seules » : aucun ingrédient et un "
                + "montant). Les objets RPGQuest sont reconnus par leur identité réelle, jamais par leur nom. Vider l'objet pour retirer la ligne.</div>");
        for (int i = 0; i < rows; i++) {
            Map<String, Object> line = i < ingredients.size() ? asMap(ingredients.get(i)) : Map.of();
            sb.append("<div class=\"row g-2 mb-2\"><div class=\"col-8 col-md-7\"><input class=\"form-control\" "
                    + "type=\"text\" name=\"ing_id_").append(i).append("\" aria-label=\"Ingrédient ").append(i + 1)
                    .append("\" list=\"dl-price-items\" autocomplete=\"off\" placeholder=\"Ingrédient…\" value=\"")
                    .append(Http.esc(str(line.get("item")))).append("\"></div><div class=\"col-4 col-md-2\">")
                    .append("<input class=\"form-control\" type=\"number\" min=\"1\" max=\"64\" name=\"ing_amount_")
                    .append(i).append("\" aria-label=\"Quantité ingrédient ").append(i + 1).append("\" value=\"")
                    .append(Http.esc(line.isEmpty() ? "" : str(line.get("amount")))).append("\" placeholder=\"1\">")
                    .append("</div></div>");
        }
        boolean demo = create || Boolean.TRUE.equals(existing.get("demo"));
        boolean enabled = create || !Boolean.FALSE.equals(existing.get("enabled"));
        sb.append("<div class=\"form-check mb-1\"><input class=\"form-check-input\" type=\"checkbox\" id=\"").append(uid)
                .append("-demo\" name=\"demo\" value=\"true\"").append(demo ? " checked" : "")
                .append("><label class=\"form-check-label\" for=\"").append(uid).append("-demo\">Valeur de "
                        + "démonstration (à revoir avant d'ouvrir l'économie aux joueurs)</label></div>")
                .append("<div class=\"form-check form-switch mb-2\"><input class=\"form-check-input\" type=\"checkbox\" "
                        + "role=\"switch\" id=\"").append(uid).append("-en\" name=\"enabled\" value=\"true\"")
                .append(enabled ? " checked" : "").append("><label class=\"form-check-label\" for=\"").append(uid)
                .append("-en\">Prix actif (les offres qui le citent s'échangent)</label></div>");
        if (!create) {
            sb.append("<div class=\"form-text mb-2\">Actuellement : ").append(Http.esc(str(existing.get("summary"))))
                    .append(". Un joueur dont la vitrine est déjà ouverte devra la rouvrir : il ne paie jamais un prix "
                            + "qu'il n'a pas vu.</div>");
        }
        sb.append("<button class=\"btn btn-primary\" type=\"submit\">").append(Icons.icon("save"))
                .append(create ? "Créer le prix" : "Enregistrer").append("</button></form>");
        return sb.toString();
    }

    /** Marchands chargés : d'où vient le prix de chaque offre, et les offres liées sans prix actif. */
    private static String priceMerchantsSection(List<Object> merchants) {
        StringBuilder sb = new StringBuilder(Ui.sectionTitle("money", "Marchands (" + merchants.size() + ")"));
        sb.append("<p class=\"muted small\">Une offre de marchand prend son prix ici quand son fichier dit "
                + "<code>price-ref: &lt;identifiant&gt;</code> ; les autres offres gardent le prix écrit dans le "
                + "fichier du marchand. Les marchands eux-mêmes se modifient dans la page "
                + "<a href=\"/merchants\">Marchands</a>.</p>");
        if (merchants.isEmpty()) {
            return sb.append(Ui.empty("money", "Aucun marchand chargé sur ce serveur.")).toString();
        }
        sb.append("<div class=\"table-responsive\"><table class=\"table table-sm align-middle\"><thead><tr><th>Marchand"
                + "</th><th>Prix du catalogue</th><th>Prix dans son fichier</th></tr></thead><tbody>");
        for (Object o : merchants) {
            Map<String, Object> m = asMap(o);
            List<Object> unavailable = asList(m.get("unavailableRefs"));
            List<Object> refs = asList(m.get("priceRefs"));
            sb.append("<tr><td><strong>").append(Http.esc(str(m.get("title")))).append("</strong><br><code class=\"small\">")
                    .append(Http.esc(str(m.get("id")))).append("</code></td><td>");
            if (refs.isEmpty()) {
                sb.append("<span class=\"muted\">aucun</span>");
            } else {
                sb.append(Http.esc(String.join(", ", refs.stream().map(AgentPages::str).toList())));
            }
            if (!unavailable.isEmpty()) {
                sb.append("<div class=\"text-danger small\">Indisponible en jeu (prix absent ou désactivé) : ")
                        .append(Http.esc(String.join(", ", unavailable.stream().map(AgentPages::str).toList())))
                        .append("</div>");
            }
            sb.append("</td><td>").append(Http.esc(str(m.get("inlineOffers")))).append(" offre(s)</td></tr>");
        }
        return sb.append("</tbody></table></div>").toString();
    }

    private static String priceHiddenFields(Session session, String agentId, String type) {
        return "<input type=\"hidden\" name=\"_csrf\" value=\"" + Http.esc(session.csrfToken()) + "\">"
                + "<input type=\"hidden\" name=\"agent\" value=\"" + Http.esc(agentId) + "\">"
                + "<input type=\"hidden\" name=\"type\" value=\"" + Http.esc(type) + "\">"
                + "<input type=\"hidden\" name=\"return\" value=\"" + PRICES_PATH + "\">";
    }

    // ---- Marchands : catalogues merchants/*.yml (issue #17) ---------------------------------

    private static final String MERCHANTS_PATH = "/merchants";
    /** Lignes d'offre vides proposées sous les offres existantes. */
    private static final int MERCHANT_BLANK_ROWS = 3;
    private static final List<String[]> MERCHANT_TYPES = List.of(
            new String[] {"SELL", "Boutique", "le PNJ vend (et fabrique) : « Ouvrir la boutique »"},
            new String[] {"BUY", "Rachat", "le PNJ rachète uniquement les objets listés : « Vendre des objets »"},
            new String[] {"MIXED", "Vente et rachat", "les deux dans la même vitrine"},
            new String[] {"CRAFT", "Atelier d'artisan", "le PNJ fabrique des objets selon les recettes (nature "
                    + "« Fabrication ») de la page Prix et coûts : ingrédients, pièces, ou les deux"});

    /**
     * Page {@code /merchants} (issue #17) : les catalogues de marchands sans YAML — ce que chaque
     * boutique vend, ce que chaque comptoir rachète, et quels dialogues les ouvrent. Un catalogue est
     * indépendant des PNJ : on le relie par l'action de dialogue « Ouvrir un marchand », et plusieurs
     * PNJ peuvent partager le même. Tout passe par les actions agent {@code merchant.*}.
     */
    public String merchants(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("money", "Marchands",
                "Les catalogues des marchands : une boutique vend, un comptoir de rachat n'achète que les objets "
                        + "qu'il liste (jamais de rachat automatique de tout l'inventaire), un atelier d'artisan "
                        + "fabrique selon ses recettes. Un catalogue se relie à un ou plusieurs PNJ par l'action de dialogue « Ouvrir un marchand ». En jeu, le joueur "
                        + "voit les objets, les prix et son solde.", ""));
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        boolean canWrite = perms.can(session.effective(), Permission.ECONOMY_WRITE);
        sb.append(agentPicker(agentId, MERCHANTS_PATH, ""));
        Optional<Map<String, Object>> loaded = latestDetails(agentId, "merchant.catalog.status");
        Map<String, Object> d = loaded.orElse(Map.of());
        sb.append("<div class=\"npc-catbar\"><span class=\"npc-catbar-t\">Catalogues de marchands</span>")
                .append(compactRefresh(session, agentId, "merchant.catalog.status", "Rafraîchir",
                        loaded.isPresent() ? "btn-outline-primary" : "btn-warning", MERCHANTS_PATH))
                .append("</div>");
        if (loaded.isEmpty()) {
            return sb.append(Ui.empty("money", "État pas encore chargé — cliquer sur « Rafraîchir ».")).toString();
        }
        if (!Boolean.TRUE.equals(d.get("available"))) {
            return sb.append(Ui.banner("warn", "Le plugin installé ne sait pas encore éditer les marchands "
                    + "(version antérieure).")).toString();
        }
        List<Object> issues = asList(d.get("issues"));
        if (!issues.isEmpty()) {
            StringBuilder list = new StringBuilder("<ul class=\"mb-0\">");
            issues.stream().limit(10).forEach(i -> list.append("<li>").append(Http.esc(str(i))).append("</li>"));
            sb.append(Ui.banner("err", "<strong>Fichiers de marchands refusés par le serveur</strong> (ces "
                    + "marchands n'existent pas en jeu ; les corriger sur le serveur) :" + list + "</ul>"));
        }
        String currency = str(d.get("currency"));
        Map<String, String> priceSummaries = new java.util.HashMap<>();
        asList(d.get("prices")).forEach(o -> priceSummaries.put(str(asMap(o).get("key")), str(asMap(o).get("summary"))));
        sb.append(merchantDatalists(agentId, d));
        List<Map<String, Object>> merchants = asList(d.get("merchants")).stream().map(AgentPages::asMap).toList();
        sb.append("<div class=\"npc-catbar mt-3\"><span class=\"npc-catbar-t\">Marchands (").append(merchants.size())
                .append(")</span>");
        if (canWrite) {
            sb.append("<button class=\"btn btn-sm btn-primary\" type=\"button\" data-bs-toggle=\"collapse\" "
                    + "data-bs-target=\"#mc-new\" aria-expanded=\"false\" aria-controls=\"mc-new\">")
                    .append(Icons.icon("plus")).append("Nouveau marchand</button>");
        }
        sb.append("</div><p class=\"muted small\">Un marchand <strong>fermé</strong> n'échange plus rien : le "
                + "dialogue répond « boutique fermée ». Les montants se saisissent ici ou viennent de la page "
                + "<a href=\"/prices\">Prix et coûts</a> (prix partagé entre plusieurs marchands). Aucun prix n'est "
                + "définitif : ce sont des valeurs de réglage, à revoir avant d'ouvrir l'économie aux joueurs.</p>");
        if (canWrite) {
            sb.append("<div class=\"collapse\" id=\"mc-new\"><div class=\"card card-body npc-formcard\">")
                    .append("<p class=\"npc-fs-h\">Nouveau marchand</p>")
                    .append(merchantForm(session, agentId, null, "mc-new", currency))
                    .append("</div></div>");
        }
        if (merchants.isEmpty()) {
            sb.append(Ui.empty("money", canWrite ? "Aucun marchand — « Nouveau marchand » pour en créer un."
                    : "Aucun marchand chargé sur ce serveur."));
        }
        for (String[] type : MERCHANT_TYPES) {
            List<Map<String, Object>> ofType = merchants.stream().filter(m -> type[0].equals(str(m.get("type"))))
                    .toList();
            if (ofType.isEmpty()) {
                continue;
            }
            sb.append(Ui.sectionTitle("money", type[1] + " (" + ofType.size() + ")"));
            for (Map<String, Object> m : ofType) {
                sb.append(merchantCard(session, agentId, m, canWrite, currency, priceSummaries));
            }
        }
        return sb.toString();
    }

    /** Objets (Minecraft + RPGQuest), prix du catalogue central et quêtes, pour les champs de saisie. */
    private String merchantDatalists(String agentId, Map<String, Object> d) {
        StringBuilder sb = new StringBuilder("<datalist id=\"dl-merchant-items\">");
        for (Object custom : asList(d.get("customItems"))) {
            sb.append("<option value=\"").append(Http.esc(str(custom))).append("\" label=\"Objet RPGQuest\">");
        }
        var ref = referenceData(agentId);
        for (String material : ref.materials()) {
            sb.append("<option value=\"").append(Http.esc(material)).append("\" label=\"")
                    .append(Http.esc(MaterialNames.french(material))).append("\">");
        }
        sb.append("</datalist><datalist id=\"dl-merchant-prices\">");
        for (Object o : asList(d.get("prices"))) {
            Map<String, Object> p = asMap(o);
            sb.append("<option value=\"").append(Http.esc(str(p.get("key")))).append("\" label=\"")
                    .append(Http.esc(str(p.get("label")) + " — " + priceTypeLabel(str(p.get("type")))
                            + (Boolean.FALSE.equals(p.get("enabled")) ? " (désactivé)" : ""))).append("\">");
        }
        sb.append("</datalist><datalist id=\"dl-merchant-quests\">");
        for (String quest : ref.quests()) {
            sb.append("<option value=\"").append(Http.esc(quest)).append("\">");
        }
        return sb.append("</datalist>").toString();
    }

    private static String merchantTypeLabel(String type) {
        return MERCHANT_TYPES.stream().filter(t -> t[0].equals(type)).map(t -> t[1]).findFirst().orElse(type);
    }

    /** Une offre en une phrase, du point de vue du joueur ; une recette (#18) dit ce qu'elle demande. */
    private static String merchantOfferText(Map<String, Object> o, String currency, Map<String, String> summaries) {
        String coins = str(o.get("price")) + " " + (currency.isBlank() ? "pièces" : currency);
        String text;
        if (!str(o.get("priceRef")).isEmpty()) {
            String summary = summaries.getOrDefault(str(o.get("priceRef")), "");
            text = "Prix du catalogue « " + str(o.get("priceRef")) + " »" + (summary.isEmpty() ? "" : " : " + summary);
        } else if ("BUY_FROM_PLAYER".equals(str(o.get("direction")))) {
            text = "Rachète " + priceItemText(o.get("item"), o.get("quantity")) + " pour " + coins;
        } else {
            text = "Vend " + priceItemText(o.get("item"), o.get("quantity")) + " pour " + coins;
        }
        List<String> conditions = new ArrayList<>();
        if (!str(o.get("requiredQuest")).isEmpty()) {
            conditions.add("quête " + str(o.get("requiredQuest")) + " (" + str(o.get("requiredQuestState")) + ")");
        }
        if (!str(o.get("requiredLevel")).isEmpty()) {
            conditions.add("niveau RPG " + str(o.get("requiredLevel")));
        }
        if (!str(o.get("requiredPermission")).isEmpty()) {
            conditions.add("permission " + str(o.get("requiredPermission")));
        }
        return text + (conditions.isEmpty() ? "" : " — si " + String.join(", ", conditions));
    }

    private String merchantCard(Session session, String agentId, Map<String, Object> m, boolean canWrite,
                                String currency, Map<String, String> priceSummaries) {
        String id = str(m.get("id"));
        boolean enabled = !Boolean.FALSE.equals(m.get("enabled"));
        boolean bundled = Boolean.TRUE.equals(m.get("bundled"));
        String uid = "mc-" + id.replaceAll("[^a-z0-9_-]", "_");
        List<Object> usages = asList(m.get("usages"));
        List<Object> unavailable = asList(m.get("unavailableRefs"));
        List<Object> offers = asList(m.get("offers"));
        StringBuilder sb = new StringBuilder("<div class=\"card card-body mb-2\" id=\"").append(uid).append("\">");
        sb.append("<div><strong>").append(Http.esc(MiniText.plain(str(m.get("title"))))).append("</strong> <code>")
                .append(Http.esc(id)).append("</code> ")
                .append(enabled ? "<span class=\"badge text-bg-success\">ouvert</span>"
                        : "<span class=\"badge text-bg-secondary\">fermé</span>")
                .append(" <span class=\"badge text-bg-light\">").append(Http.esc(merchantTypeLabel(str(m.get("type")))))
                .append("</span>")
                .append(bundled ? " <span class=\"badge text-bg-info\">exemple fourni</span>" : "")
                .append("</div><ul class=\"mb-1\">");
        offers.stream().limit(12).forEach(o -> sb.append("<li>")
                .append(Http.esc(merchantOfferText(asMap(o), currency, priceSummaries))).append("</li>"));
        if (offers.size() > 12) {
            sb.append("<li class=\"muted\">… et ").append(offers.size() - 12).append(" autre(s)</li>");
        }
        sb.append("</ul>");
        if (!unavailable.isEmpty()) {
            sb.append("<div class=\"text-danger small\">Indisponible en jeu (prix absent, désactivé ou d'un autre type "
                    + "que ce marchand) : ").append(Http.esc(String.join(", ", unavailable.stream().map(AgentPages::str)
                    .toList()))).append("</div>");
        }
        sb.append("<div class=\"muted small\">").append(usages.isEmpty()
                ? "Aucun dialogue ne l'ouvre encore : ajouter l'action « Ouvrir un marchand » à un choix de dialogue."
                : "Ouvert par : " + Http.esc(String.join(", ", usages.stream().map(AgentPages::str).toList())) + ".")
                .append(" Fichier <code>").append(Http.esc(str(m.get("file")))).append("</code>.</div>");
        if (canWrite) {
            sb.append("<div class=\"d-flex flex-wrap gap-2 mt-2\">")
                    .append("<button class=\"btn secondary sm\" type=\"button\" data-bs-toggle=\"collapse\" "
                            + "data-bs-target=\"#").append(uid).append("-edit\" aria-expanded=\"false\">")
                    .append(Icons.icon("edit")).append("Modifier</button>")
                    .append("<form method=\"post\" action=\"/agents/action\" class=\"d-inline\">")
                    .append(merchantHiddenFields(session, agentId, "merchant.toggle"))
                    .append("<input type=\"hidden\" name=\"merchant_id\" value=\"").append(Http.esc(id)).append("\">")
                    .append("<input type=\"hidden\" name=\"enabled\" value=\"").append(!enabled).append("\">")
                    .append("<button class=\"btn sm").append(enabled ? " secondary" : "").append("\" type=\"submit\">")
                    .append(enabled ? "Fermer la boutique" : "Ouvrir la boutique").append("</button></form>")
                    .append("<button class=\"btn secondary sm\" type=\"button\" data-bs-toggle=\"collapse\" "
                            + "data-bs-target=\"#").append(uid).append("-dup\" aria-expanded=\"false\">")
                    .append(Icons.icon("plus")).append("Dupliquer</button>");
            if (!bundled && usages.isEmpty()) {
                sb.append("<button class=\"btn secondary sm\" type=\"button\" data-bs-toggle=\"collapse\" "
                        + "data-bs-target=\"#").append(uid).append("-del\" aria-expanded=\"false\">")
                        .append(Icons.icon("trash")).append("Supprimer</button>");
            }
            sb.append("</div>");
            if (bundled || !usages.isEmpty()) {
                sb.append("<div class=\"form-text\">Suppression impossible : ").append(bundled
                        ? "exemple fourni avec le plugin (il serait recréé au démarrage)."
                        : "des dialogues l'ouvrent encore (retirer d'abord leur action « Ouvrir un marchand »).")
                        .append(" Le fermer suffit à arrêter les échanges.</div>");
            }
            sb.append("<div class=\"collapse mt-2\" id=\"").append(uid).append("-dup\">")
                    .append("<form method=\"post\" action=\"/agents/action\" class=\"npc-def-form\">")
                    .append(merchantHiddenFields(session, agentId, "merchant.duplicate"))
                    .append("<input type=\"hidden\" name=\"merchant_id\" value=\"").append(Http.esc(id)).append("\">")
                    .append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid)
                    .append("-dup-id\">Identifiant de la copie</label><input class=\"form-control\" id=\"").append(uid)
                    .append("-dup-id\" name=\"new_key\" required maxlength=\"64\" pattern=\"[a-z0-9_\\-]{1,64}\" value=\"")
                    .append(Http.esc(merchantKey(id) + "_copie")).append("\"></div>")
                    .append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid)
                    .append("-dup-l\">Nom de la copie (facultatif)</label><input class=\"form-control\" id=\"")
                    .append(uid).append("-dup-l\" name=\"new_title\" maxlength=\"120\"></div>")
                    .append("<div class=\"form-text mb-2\">La copie est créée <strong>fermée</strong> : la modifier, "
                            + "puis l'ouvrir et la relier à un dialogue.</div>")
                    .append("<button class=\"btn sm\" type=\"submit\">Créer la copie</button></form></div>");
            if (!bundled && usages.isEmpty()) {
                sb.append("<div class=\"collapse mt-2\" id=\"").append(uid).append("-del\">")
                        .append("<form method=\"post\" action=\"/agents/action\" class=\"npc-def-form\">")
                        .append(merchantHiddenFields(session, agentId, "merchant.delete"))
                        .append("<input type=\"hidden\" name=\"merchant_id\" value=\"").append(Http.esc(id))
                        .append("\"><input type=\"hidden\" name=\"expected_version\" value=\"")
                        .append(Http.esc(str(m.get("version")))).append("\">")
                        .append("<label class=\"form-check mb-2\"><input class=\"form-check-input\" type=\"checkbox\" "
                                + "name=\"confirm\" value=\"true\" required> <span>Supprimer définitivement ce "
                                + "catalogue (aucun solde ni objet de joueur n'est touché)</span></label>")
                        .append("<button class=\"btn sm btn-danger\" type=\"submit\">Supprimer</button></form></div>");
            }
            sb.append("<div class=\"collapse mt-2\" id=\"").append(uid).append("-edit\"><div class=\"card "
                    + "card-body npc-formcard\">").append(merchantForm(session, agentId, m, uid + "-f", currency))
                    .append("</div></div>");
        }
        return sb.append("</div>").toString();
    }

    private static String merchantKey(String id) {
        String key = id.indexOf(':') >= 0 ? id.substring(id.indexOf(':') + 1) : id;
        key = key.replaceAll("[^a-z0-9_-]", "_");
        return key.length() > 58 ? key.substring(0, 58) : key;
    }

    /** Formulaire de marchand, création ({@code existing == null}) ou modification. */
    private String merchantForm(Session session, String agentId, Map<String, Object> existing, String uid,
                                String currency) {
        boolean create = existing == null;
        String type = create ? "SELL" : str(existing.get("type"));
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"/agents/action\" class=\"npc-def-form\">");
        sb.append(merchantHiddenFields(session, agentId, "merchant.save"));
        sb.append("<input type=\"hidden\" name=\"create\" value=\"").append(create).append("\">");
        if (!create) {
            sb.append("<input type=\"hidden\" name=\"merchant_id\" value=\"").append(Http.esc(str(existing.get("id"))))
                    .append("\"><input type=\"hidden\" name=\"expected_version\" value=\"")
                    .append(Http.esc(str(existing.get("version")))).append("\">");
        }
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid).append("-t\">Nom affiché</label>")
                .append("<input class=\"form-control\" id=\"").append(uid).append("-t\" name=\"title\" required "
                        + "maxlength=\"120\" value=\"").append(Http.esc(create ? "" : str(existing.get("title"))))
                .append("\">").append(fieldHelp("Titre de la vitrine en jeu. Les couleurs MiniMessage sont acceptées.",
                        "&lt;gold&gt;Boutique du village&lt;/gold&gt;", "aucun (obligatoire)", null)).append("</div>");
        if (create) {
            sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid).append("-id\">Identifiant "
                    + "(facultatif)</label><input class=\"form-control\" id=\"").append(uid).append("-id\" "
                    + "name=\"merchant_id\" maxlength=\"64\" pattern=\"[a-z0-9_\\-]{0,64}\">")
                    .append(fieldHelp("Ce que cite l'action de dialogue. Vide : tiré du nom. Ne change plus ensuite.",
                            "boutique_village", "tiré du nom", null)).append("</div>");
        }
        sb.append("<fieldset class=\"mb-2\"><legend class=\"form-label fs-6 mb-1\">Ce que fait ce marchand</legend>");
        for (String[] t : MERCHANT_TYPES) {
            String id = uid + "-ty-" + t[0].toLowerCase(java.util.Locale.ROOT);
            sb.append("<div class=\"form-check\"><input class=\"form-check-input\" type=\"radio\" name=\"catalog_type\" "
                    + "id=\"").append(id).append("\" value=\"").append(t[0]).append('"')
                    .append(t[0].equals(type) ? " checked" : "").append(" required><label class=\"form-check-label\" "
                    + "for=\"").append(id).append("\"><strong>").append(t[1]).append("</strong> — ").append(t[2])
                    .append("</label></div>");
        }
        sb.append("</fieldset>");
        List<Object> offers = create ? List.of() : asList(existing.get("offers"));
        int rows = Math.min(com.lodygames.rpgquest.panel.agent.MerchantForm.MAX_OFFERS,
                offers.size() + MERCHANT_BLANK_ROWS);
        sb.append("<p class=\"form-label mb-1\">Offres (dans l'ordre de la vitrine)</p><div class=\"form-text mb-2\">"
                + "Chaque ligne : <strong>soit</strong> un prix de la page Prix et coûts, <strong>soit</strong> un objet "
                + "avec sa quantité et son prix en ").append(Http.esc(currency.isBlank() ? "pièces" : currency))
                .append(". Le sens (vend / rachète) ne sert que pour un marchand « vente et rachat ». Un atelier "
                        + "d'artisan n'accepte que des recettes (prix de nature « Fabrication »). Une ligne vide est "
                        + "ignorée. Conditions facultatives : quête, niveau, permission.</div>");
        for (int i = 0; i < rows; i++) {
            sb.append(merchantOfferRow(uid, i, i < offers.size() ? asMap(offers.get(i)) : Map.of(), i < offers.size()));
        }
        boolean enabled = create || !Boolean.FALSE.equals(existing.get("enabled"));
        sb.append("<div class=\"form-check form-switch mb-2\"><input class=\"form-check-input\" type=\"checkbox\" "
                        + "role=\"switch\" id=\"").append(uid).append("-en\" name=\"enabled\" value=\"true\"")
                .append(enabled ? " checked" : "").append("><label class=\"form-check-label\" for=\"").append(uid)
                .append("-en\">Boutique ouverte (les dialogues l'ouvrent et les échanges se font)</label></div>");
        if (!create) {
            sb.append("<div class=\"form-text mb-2\">Un joueur dont la vitrine est ouverte la voit se mettre à jour à "
                    + "son prochain clic, sans rien échanger : il ne paie jamais une offre qu'il n'a pas vue.</div>");
        }
        sb.append("<button class=\"btn btn-primary\" type=\"submit\">").append(Icons.icon("save"))
                .append(create ? "Créer le marchand" : "Enregistrer").append("</button></form>");
        return sb.toString();
    }

    private static String merchantOfferRow(String uid, int i, Map<String, Object> o, boolean existing) {
        String p = uid + "-o" + i;
        String direction = str(o.get("direction"));
        String state = str(o.get("requiredQuestState"));
        StringBuilder sb = new StringBuilder("<fieldset class=\"border rounded p-2 mb-2\"><legend class=\"fs-6 mb-1\">")
                .append("Offre ").append(i + 1).append(existing ? "" : " (nouvelle)").append("</legend>");
        sb.append("<div class=\"row g-2\"><div class=\"col-12 col-md-4\"><label class=\"form-label small mb-0\" for=\"")
                .append(p).append("-ref\">Prix du catalogue</label><input class=\"form-control\" id=\"").append(p)
                .append("-ref\" name=\"of_ref_").append(i).append("\" list=\"dl-merchant-prices\" autocomplete=\"off\" "
                        + "maxlength=\"64\" value=\"").append(Http.esc(str(o.get("priceRef"))))
                .append("\" placeholder=\"ou objet + prix →\"></div>")
                .append("<div class=\"col-6 col-md-2\"><label class=\"form-label small mb-0\" for=\"").append(p)
                .append("-dir\">Sens</label><select class=\"form-select\" id=\"").append(p).append("-dir\" name=\"of_dir_")
                .append(i).append("\"><option value=\"SELL_TO_PLAYER\"")
                .append("BUY_FROM_PLAYER".equals(direction) ? "" : " selected").append(">Vend</option>")
                .append("<option value=\"BUY_FROM_PLAYER\"").append("BUY_FROM_PLAYER".equals(direction) ? " selected" : "")
                .append(">Rachète</option></select></div>")
                .append("<div class=\"col-6 col-md-3\"><label class=\"form-label small mb-0\" for=\"").append(p)
                .append("-it\">Objet</label><input class=\"form-control\" id=\"").append(p).append("-it\" name=\"of_item_")
                .append(i).append("\" list=\"dl-merchant-items\" autocomplete=\"off\" value=\"")
                .append(Http.esc(str(o.get("item")))).append("\" placeholder=\"Chercher…\"></div>")
                .append("<div class=\"col-6 col-md-1\"><label class=\"form-label small mb-0\" for=\"").append(p)
                .append("-q\">Qté</label><input class=\"form-control\" type=\"number\" min=\"1\" max=\"64\" id=\"")
                .append(p).append("-q\" name=\"of_qty_").append(i).append("\" value=\"")
                .append(Http.esc(str(o.get("item")).isEmpty() ? "" : str(o.get("quantity")))).append("\"></div>")
                .append("<div class=\"col-6 col-md-2\"><label class=\"form-label small mb-0\" for=\"").append(p)
                .append("-pr\">Prix</label><input class=\"form-control\" type=\"number\" min=\"0\" step=\"1\" id=\"")
                .append(p).append("-pr\" name=\"of_price_").append(i).append("\" value=\"")
                .append(Http.esc(str(o.get("item")).isEmpty() ? "" : str(o.get("price")))).append("\"></div></div>");
        sb.append("<div class=\"row g-2 mt-1\"><div class=\"col-12 col-md-4\"><label class=\"form-label small mb-0\" "
                        + "for=\"").append(p).append("-qu\">Quête requise</label><input class=\"form-control\" id=\"")
                .append(p).append("-qu\" name=\"of_quest_").append(i).append("\" list=\"dl-merchant-quests\" "
                        + "autocomplete=\"off\" value=\"").append(Http.esc(str(o.get("requiredQuest")))).append("\"></div>")
                .append("<div class=\"col-6 col-md-3\"><label class=\"form-label small mb-0\" for=\"").append(p)
                .append("-st\">État de la quête</label><select class=\"form-select\" id=\"").append(p)
                .append("-st\" name=\"of_state_").append(i).append("\">");
        for (String[] s : List.of(new String[] {"COMPLETED", "terminée"}, new String[] {"ACTIVE", "en cours"},
                new String[] {"READY_TO_TURN_IN", "à rendre"}, new String[] {"NOT_STARTED", "pas commencée"})) {
            sb.append("<option value=\"").append(s[0]).append('"').append(s[0].equals(state) ? " selected" : "")
                    .append('>').append(s[1]).append("</option>");
        }
        sb.append("</select></div><div class=\"col-6 col-md-2\"><label class=\"form-label small mb-0\" for=\"").append(p)
                .append("-lv\">Niveau RPG min.</label><input class=\"form-control\" type=\"number\" min=\"0\" id=\"").append(p)
                .append("-lv\" name=\"of_level_").append(i).append("\" value=\"")
                .append(Http.esc(str(o.get("requiredLevel")))).append("\"></div>")
                .append("<div class=\"col-12 col-md-3\"><label class=\"form-label small mb-0\" for=\"").append(p)
                .append("-pe\">Permission</label><input class=\"form-control\" id=\"").append(p)
                .append("-pe\" name=\"of_perm_").append(i).append("\" maxlength=\"100\" value=\"")
                .append(Http.esc(str(o.get("requiredPermission")))).append("\"></div></div>");
        if (existing) {
            sb.append("<label class=\"form-check mt-1\"><input class=\"form-check-input\" type=\"checkbox\" name=\"of_del_")
                    .append(i).append("\" value=\"true\"> <span>Retirer cette offre</span></label>");
        }
        return sb.append("</fieldset>").toString();
    }

    private static String merchantHiddenFields(Session session, String agentId, String type) {
        return "<input type=\"hidden\" name=\"_csrf\" value=\"" + Http.esc(session.csrfToken()) + "\">"
                + "<input type=\"hidden\" name=\"agent\" value=\"" + Http.esc(agentId) + "\">"
                + "<input type=\"hidden\" name=\"type\" value=\"" + Http.esc(type) + "\">"
                + "<input type=\"hidden\" name=\"return\" value=\"" + MERCHANTS_PATH + "\">";
    }

    // ---- Récompenses : catalogue (issue #474) et tables de tirage (issue #177) ---------------

    private static final String REWARDS_PATH = "/rewards";
    /** Lignes vides proposées sous les entrées existantes d'une table. */
    private static final int REWARD_TABLE_BLANK_ROWS = 3;
    private static final int REWARD_TABLE_MIN_ROWS = 5;

    /**
     * Page {@code /rewards} (issues #177, #474) : tout le catalogue {@code entitlements.yml} sans YAML —
     * création du fichier, interrupteur général, récompenses (créer, modifier, dupliquer, activer) et
     * tables de tirage. Tout passe par les actions agent {@code reward.*}, validées par le serveur
     * avant écriture.
     */
    public String rewardTables(Session session, Map<String, String> q) {
        Optional<AgentIdentity> agent = resolveAgent(q);
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("gift", "Récompenses",
                "Le catalogue des récompenses (objets, enchantements, potions…) et les tables de tirage au sort "
                        + "utilisées par les quêtes (récompense « Tirage au sort ») et les boss communautaires "
                        + "(récompenses de participation). Chaque gain est remis une seule fois, même si l'inventaire "
                        + "est plein ou si le joueur se déconnecte.", ""));
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        boolean canWrite = perms.can(session.effective(), Permission.ECONOMY_WRITE);
        sb.append(agentPicker(agentId, REWARDS_PATH, ""));
        Optional<Map<String, Object>> loaded = latestDetails(agentId, "reward.catalog.status");
        Map<String, Object> d = loaded.orElse(Map.of());
        sb.append("<div class=\"npc-catbar\"><span class=\"npc-catbar-t\">Catalogue</span>")
                .append(compactRefresh(session, agentId, "reward.catalog.status", "Rafraîchir",
                        loaded.isPresent() ? "btn-outline-primary" : "btn-warning", REWARDS_PATH))
                .append("</div>");
        sb.append(rewardPendingBanner(agentId));
        if (loaded.isEmpty()) {
            return sb.append(Ui.empty("gift", "État pas encore chargé — cliquer sur « Rafraîchir »."))
                    .toString();
        }
        if (!Boolean.TRUE.equals(d.get("available"))) {
            return sb.append(Ui.banner("warn", "Le plugin installé ne sait pas encore éditer les récompenses "
                    + "(version antérieure).")).toString();
        }
        sb.append(rewardCatalogBanner(d));
        if (!Boolean.TRUE.equals(d.get("fileExists"))) {
            return sb.append(rewardCatalogInitCard(session, agentId, d, canWrite)).toString();
        }
        if (!Boolean.TRUE.equals(d.get("valid"))) {
            return sb.toString();
        }
        boolean editorReady = d.containsKey("initialText"); // plugin #474 : catalogue éditable
        sb.append(rewardCatalogSwitchCard(session, agentId, d, canWrite && editorReady));
        List<Object> rewards = asList(d.get("rewards"));
        sb.append(rewardCatalogDatalist(rewards));
        if (editorReady) {
            sb.append(rewardItemDatalists(agentId, d));
        }
        Map<String, String> rewardNames = new java.util.LinkedHashMap<>();
        rewards.forEach(o -> rewardNames.put(str(asMap(o).get("key")), rewardDisplayName(asMap(o))));
        Map<String, String> draft = refusedTableForm(session, agentId, q);
        sb.append(rewardDefinitionsSection(session, agentId, d, rewards, canWrite && editorReady,
                refusedDefinitionForm(session, agentId, q)));
        boolean tablesWritable = !rewards.isEmpty();
        sb.append("<div class=\"npc-catbar mt-4\"><span class=\"npc-catbar-t\">Tables de tirage</span>");
        if (canWrite && tablesWritable) {
            sb.append("<button class=\"btn btn-sm btn-primary\" type=\"button\" data-bs-toggle=\"collapse\" "
                    + "data-bs-target=\"#rt-new\" aria-expanded=\"false\" aria-controls=\"rt-new\">")
                    .append(Icons.icon("plus")).append("Nouvelle table</button>");
        }
        sb.append("</div>");
        sb.append(rewardModesHelp());
        if (canWrite && tablesWritable) {
            Map<String, String> createDraft = draft != null && "true".equals(draft.get("create")) ? draft : null;
            sb.append("<div class=\"collapse").append(createDraft != null ? " show" : "")
                    .append("\" id=\"rt-new\"><div class=\"card card-body npc-formcard\">")
                    .append("<p class=\"npc-fs-h\">Nouvelle table</p>")
                    .append(rewardTableForm(session, agentId, null, "rt-new", rewardNames, createDraft))
                    .append("</div></div>");
        }
        List<Object> tables = asList(d.get("tables"));
        sb.append(Ui.sectionTitle("gift", "Tables (" + tables.size() + ")"));
        if (tables.isEmpty()) {
            sb.append(Ui.empty("gift", tablesWritable
                    ? "Aucune table pour l'instant — « Nouvelle table » pour en créer une."
                    : "Aucune table : créer d'abord une récompense, puis une table qui la fait gagner."));
        }
        for (Object o : tables) {
            Map<String, Object> t = asMap(o);
            boolean mine = draft != null && !"true".equals(draft.get("create"))
                    && str(t.get("id")).equals(draft.get("table_id"));
            sb.append(rewardTableCard(session, agentId, t, canWrite && tablesWritable, rewardNames, mine ? draft : null));
        }
        return sb.toString();
    }

    /** État du fichier : absent, refusé, vide — chaque cas dit ce qu'il implique pour les joueurs. */
    private static String rewardCatalogBanner(Map<String, Object> d) {
        if (!Boolean.TRUE.equals(d.get("fileExists"))) {
            return Ui.banner("warn", "<strong>Aucun catalogue de récompenses sur ce serveur</strong> "
                    + "(<code>entitlements.yml</code> absent) : aucune récompense n'existe, donc aucune table ne peut "
                    + "être créée. Les quêtes et boss qui demandent un tirage l'annoncent au joueur comme "
                    + "indisponible, et rien n'est promis.");
        }
        if (!Boolean.TRUE.equals(d.get("valid"))) {
            StringBuilder errors = new StringBuilder("<ul class=\"mb-0\">");
            asList(d.get("errors")).stream().limit(5)
                    .forEach(e -> errors.append("<li>").append(Http.esc(str(e))).append("</li>"));
            return Ui.banner("err", "<strong>Le serveur refuse <code>entitlements.yml</code></strong> : aucune "
                    + "récompense ni aucun tirage tant qu'il n'est pas corrigé, et rien ne peut être enregistré "
                    + "ici (pour ne pas aggraver la situation)." + errors + "</ul>");
        }
        if (asList(d.get("rewards")).isEmpty()) {
            return Ui.banner("warn", "Le catalogue ne contient <strong>aucune récompense</strong> : une table n'aurait "
                    + "rien à tirer. Commencer par « Nouvelle récompense » ci-dessous.");
        }
        return "";
    }

    /** Fichier absent : aperçu exact du fichier qui sera créé, confirmation explicite. */
    private static String rewardCatalogInitCard(Session session, String agentId, Map<String, Object> d,
                                                boolean canWrite) {
        String preview = str(d.get("initialText"));
        StringBuilder sb = new StringBuilder("<div class=\"card card-body mb-3\">")
                .append("<p class=\"npc-fs-h\">Créer le catalogue des récompenses</p>");
        if (preview.isBlank()) {
            return sb.append("<p class=\"mb-0\">Le plugin installé ne sait pas encore créer le catalogue depuis "
                    + "PlugAdmin : le mettre à jour.</p></div>").toString();
        }
        sb.append("<p>Le serveur créera exactement ce fichier : <strong>désactivé</strong>, sans aucune récompense ni "
                + "table. Rien ne change pour les joueurs tant que vous n'avez pas ajouté des récompenses puis activé "
                + "le catalogue, ici même.</p><pre class=\"small border rounded p-2\" style=\"white-space:pre-wrap\">")
                .append(Http.esc(preview)).append("</pre>");
        if (!canWrite) {
            return sb.append("<p class=\"muted small mb-0\">Création réservée aux comptes ayant le droit « Économie : "
                    + "écriture ».</p></div>").toString();
        }
        sb.append("<form method=\"post\" action=\"/agents/action\">")
                .append(rewardHiddenFields(session, agentId, "reward.catalog.init"))
                .append("<div class=\"form-check mb-2\"><input class=\"form-check-input\" type=\"checkbox\" "
                        + "name=\"confirm\" value=\"yes\" id=\"rc-init-ok\" required><label class=\"form-check-label\" "
                        + "for=\"rc-init-ok\">Je crée le catalogue vide et désactivé sur ce serveur.</label></div>")
                .append("<button class=\"btn btn-primary\" type=\"submit\">").append(Icons.icon("plus"))
                .append("Créer le catalogue</button></form>");
        return sb.append("</div>").toString();
    }

    /** Interrupteur général : état, conséquences pour les quêtes et les boss, confirmation. */
    private static String rewardCatalogSwitchCard(Session session, String agentId, Map<String, Object> d,
                                                  boolean canWrite) {
        boolean enabled = Boolean.TRUE.equals(d.get("enabled"));
        StringBuilder sb = new StringBuilder("<div class=\"card card-body mb-3\" id=\"rc-switch\">")
                .append("<p class=\"npc-fs-h\">Récompenses sur ce serveur : ")
                .append(enabled ? "<span class=\"badge text-bg-success\">activées</span>"
                        : "<span class=\"badge text-bg-secondary\">désactivées</span>").append("</p>");
        sb.append(enabled
                ? "<p class=\"mb-2\">Les quêtes et boss tirent leurs tables <strong>actives</strong> et déclarent leurs "
                + "récompenses ; les gains sont remis aux joueurs (une seule fois, même inventaire plein).</p>"
                : "<p class=\"mb-2\">Aucun tirage ni nouvelle récompense : une quête ou un boss qui en prévoit une "
                + "affiche « récompenses désactivées » et ne promet rien. Les gains déjà obtenus attendent la "
                + "réactivation (rien n'est perdu). Récompenses et tables se préparent ici en attendant.</p>");
        if (canWrite) {
            sb.append("<form method=\"post\" action=\"/agents/action\">")
                    .append(rewardHiddenFields(session, agentId, "reward.catalog.toggle"))
                    .append("<input type=\"hidden\" name=\"enabled\" value=\"").append(!enabled).append("\">")
                    .append("<div class=\"form-check mb-2\"><input class=\"form-check-input\" type=\"checkbox\" "
                            + "name=\"confirm\" value=\"yes\" id=\"rc-sw-ok\" required><label class=\"form-check-label\" "
                            + "for=\"rc-sw-ok\">")
                    .append(enabled ? "J'ai compris : plus aucun tirage ni nouvelle récompense pour les quêtes et boss."
                            : "J'ai compris : les quêtes et boss vont tirer leurs tables actives et remettre leurs "
                            + "récompenses.")
                    .append("</label></div><button class=\"btn").append(enabled ? " secondary" : " btn-primary")
                    .append("\" type=\"submit\">").append(enabled ? "Désactiver les récompenses" : "Activer les récompenses")
                    .append("</button></form>");
        }
        return sb.append("</div>").toString();
    }

    /** Liste recherchable des récompenses : valeur = clé du catalogue, libellé = nom + contenu. */
    private static String rewardCatalogDatalist(List<Object> rewards) {
        StringBuilder sb = new StringBuilder("<datalist id=\"dl-reward-catalog\">");
        for (Object o : rewards) {
            Map<String, Object> r = asMap(o);
            sb.append("<option value=\"").append(Http.esc(str(r.get("key")))).append("\" label=\"")
                    .append(Http.esc(rewardDisplayName(r))).append("\">");
        }
        return sb.append("</datalist>").toString();
    }

    /** Objets Minecraft (nom français) et RPGQuest, enchantements et potions connus du serveur. */
    private String rewardItemDatalists(String agentId, Map<String, Object> d) {
        StringBuilder sb = new StringBuilder("<datalist id=\"dl-reward-items\">");
        for (Object custom : asList(d.get("customItems"))) {
            sb.append("<option value=\"").append(Http.esc(str(custom))).append("\" label=\"Objet RPGQuest\">");
        }
        for (String material : referenceData(agentId).materials()) {
            sb.append("<option value=\"").append(Http.esc(material)).append("\" label=\"")
                    .append(Http.esc(MaterialNames.french(material))).append("\">");
        }
        sb.append("</datalist><datalist id=\"dl-reward-enchants\">");
        // #474 : nom français, niveau maximal, objets acceptés et exclusivités, lus par panel.js.
        for (Map<String, Object> e : enchantmentInfo(d)) {
            String key = str(e.get("key"));
            int max = intOf(e.get("maxLevel"));
            sb.append("<option value=\"").append(Http.esc(key)).append("\" label=\"")
                    .append(Http.esc(EnchantmentNames.french(key) + (max > 0 ? " (max " + EnchantmentNames.level(max) + ")"
                            : ""))).append("\" data-name=\"").append(Http.esc(EnchantmentNames.french(key)))
                    .append("\" data-max=\"").append(max).append("\" data-items=\"")
                    .append(Http.esc(String.join(",", asList(e.get("items")).stream().map(AgentPages::str).toList())))
                    .append("\" data-conflicts=\"")
                    .append(Http.esc(String.join(",", asList(e.get("conflicts")).stream().map(AgentPages::str).toList())))
                    .append("\">");
        }
        sb.append("</datalist><datalist id=\"dl-reward-potions\">");
        asList(d.get("potions")).forEach(p -> sb.append("<option value=\"").append(Http.esc(str(p))).append("\">"));
        return sb.append("</datalist>").toString();
    }

    /** Catalogue des récompenses : fiches éditables (#474), ou liste en lecture sans le droit d'écrire. */
    private String rewardDefinitionsSection(Session session, String agentId, Map<String, Object> d,
                                            List<Object> rewards, boolean canWrite, Map<String, String> draft) {
        boolean createDraft = draft != null && "true".equals(draft.get("create"));
        StringBuilder sb = new StringBuilder("<div class=\"npc-catbar mt-3\"><span class=\"npc-catbar-t\">")
                .append("Récompenses (").append(rewards.size()).append(")</span>");
        if (canWrite) {
            sb.append("<button class=\"btn btn-sm btn-primary\" type=\"button\" data-bs-toggle=\"collapse\" "
                    + "data-bs-target=\"#rd-new\" aria-expanded=\"false\" aria-controls=\"rd-new\">")
                    .append(Icons.icon("plus")).append("Nouvelle récompense</button>");
        }
        sb.append("</div><p class=\"muted small\">Ce qu'une table, un boss ou un événement peut faire gagner. Une "
                + "récompense <strong>désactivée</strong> n'est plus promise à personne (un tirage qui tombe dessus ne "
                + "donne rien, un boss ne la déclare plus) ; les gains déjà obtenus restent remis. Pas de suppression : "
                + "désactiver suffit et ne casse aucune référence.</p>");
        if (canWrite) {
            sb.append("<div class=\"collapse").append(createDraft ? " show" : "")
                    .append("\" id=\"rd-new\"><div class=\"card card-body npc-formcard\">")
                    .append("<p class=\"npc-fs-h\">Nouvelle récompense</p>")
                    .append(rewardDefinitionForm(session, agentId, null, "rd-new", d, createDraft ? draft : null))
                    .append("</div></div>");
        }
        if (rewards.isEmpty()) {
            return sb.append(Ui.empty("gift", canWrite ? "Aucune récompense — « Nouvelle récompense » pour en créer une."
                    : "Aucune récompense.")).toString();
        }
        for (Object o : rewards) {
            Map<String, Object> r = asMap(o);
            boolean mine = draft != null && !createDraft && str(r.get("key")).equals(draft.get("reward_key"));
            sb.append(rewardDefinitionCard(session, agentId, r, d, canWrite, mine ? draft : null));
        }
        return sb.toString();
    }

    private String rewardDefinitionCard(Session session, String agentId, Map<String, Object> r, Map<String, Object> d,
                                        boolean canWrite, Map<String, String> draft) {
        String key = str(r.get("key"));
        boolean enabled = !Boolean.FALSE.equals(r.get("enabled"));
        String uid = "rd-" + key.replaceAll("[^a-z0-9_-]", "_");
        List<Object> usages = asList(r.get("usages"));
        StringBuilder sb = new StringBuilder("<div class=\"card card-body mb-2\" id=\"").append(uid).append("\">");
        sb.append("<div><strong>").append(Http.esc(str(r.get("label")))).append("</strong> <code>").append(Http.esc(key))
                .append("</code> ").append(enabled ? "<span class=\"badge text-bg-success\">active</span>"
                        : "<span class=\"badge text-bg-secondary\">désactivée</span>")
                .append("</div><div>").append(Http.esc(rewardContentText(r))).append("</div>");
        sb.append("<div class=\"muted small\">").append(usages.isEmpty()
                ? "Utilisée par aucune table ni aucun boss pour l'instant."
                : "Utilisée par : " + Http.esc(String.join(", ", usages.stream().map(AgentPages::str).toList())) + ".")
                .append("</div>");
        if (canWrite) {
            sb.append("<div class=\"d-flex flex-wrap gap-2 mt-2\">");
            sb.append("<button class=\"btn secondary sm\" type=\"button\" data-bs-toggle=\"collapse\" "
                    + "data-bs-target=\"#").append(uid).append("-edit\" aria-expanded=\"false\">")
                    .append(Icons.icon("edit")).append("Modifier</button>");
            sb.append("<form method=\"post\" action=\"/agents/action\" class=\"d-inline\">")
                    .append(rewardHiddenFields(session, agentId, "reward.definition.toggle"))
                    .append("<input type=\"hidden\" name=\"reward_key\" value=\"").append(Http.esc(key)).append("\">")
                    .append("<input type=\"hidden\" name=\"enabled\" value=\"").append(!enabled).append("\">")
                    .append("<button class=\"btn sm").append(enabled ? " secondary" : "").append("\" type=\"submit\"")
                    .append(enabled && !usages.isEmpty() ? " title=\"Ces usages ne la donneront plus\"" : "")
                    .append(">").append(enabled ? "Désactiver" : "Activer").append("</button></form>");
            sb.append("<button class=\"btn secondary sm\" type=\"button\" data-bs-toggle=\"collapse\" "
                    + "data-bs-target=\"#").append(uid).append("-dup\" aria-expanded=\"false\">")
                    .append(Icons.icon("plus")).append("Dupliquer</button>");
            sb.append("</div>");
            if (enabled && !usages.isEmpty()) {
                sb.append("<div class=\"form-text\">Désactiver : ").append(usages.size())
                        .append(" usage(s) ci-dessus ne la donneront plus (aucune erreur, simplement rien à gagner).</div>");
            }
            sb.append("<div class=\"collapse mt-2\" id=\"").append(uid).append("-dup\">")
                    .append("<form method=\"post\" action=\"/agents/action\" class=\"npc-def-form\">")
                    .append(rewardHiddenFields(session, agentId, "reward.definition.duplicate"))
                    .append("<input type=\"hidden\" name=\"reward_key\" value=\"").append(Http.esc(key)).append("\">")
                    .append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid)
                    .append("-dup-id\">Identifiant de la copie</label><input class=\"form-control\" id=\"").append(uid)
                    .append("-dup-id\" name=\"new_key\" required maxlength=\"64\" pattern=\"[a-z0-9_\\-]{1,64}\" value=\"")
                    .append(Http.esc(rewardCopyKey(key))).append("\"></div>")
                    .append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid)
                    .append("-dup-l\">Nom de la copie (facultatif)</label><input class=\"form-control\" id=\"")
                    .append(uid).append("-dup-l\" name=\"new_label\" maxlength=\"120\"></div>")
                    .append("<div class=\"form-text mb-2\">La copie est créée <strong>désactivée</strong>, avec le même "
                            + "contenu : la modifier, puis l'activer.</div>")
                    .append("<button class=\"btn sm\" type=\"submit\">Créer la copie</button></form></div>");
            sb.append("<div class=\"collapse mt-2").append(draft != null ? " show" : "").append("\" id=\"").append(uid)
                    .append("-edit\"><div class=\"card card-body npc-formcard\">")
                    .append(rewardDefinitionForm(session, agentId, r, uid + "-f", d, draft))
                    .append("</div></div>");
        }
        return sb.append("</div>").toString();
    }

    private static String rewardCopyKey(String key) {
        String base = key.replace('.', '_');
        return (base.length() > 58 ? base.substring(0, 58) : base) + "_copie";
    }

    /** Lignes d'objets proposées : celles de la récompense, plus deux vides (neuf au plus). */
    private static final int REWARD_ITEM_BLANK_ROWS = 2;

    /**
     * Formulaire de récompense, création ({@code existing == null}) ou modification. {@code draft} = un
     * envoi refusé (champs du formulaire), réaffiché tel quel avec la raison (issue #474, comme les
     * tables en #177). Enchantements : sélecteur guidé de {@code panel.js} (nom français, niveau 1 à
     * max, compatibilités) qui écrit dans le champ texte {@code item_ench_<i>} ; sans JavaScript, ce
     * champ reste saisissable à la main et le serveur revalide tout.
     */
    private String rewardDefinitionForm(Session session, String agentId, Map<String, Object> existing, String uid,
                                        Map<String, Object> d, Map<String, String> draft) {
        boolean create = existing == null;
        Map<String, String> v = draft != null ? draft : definitionFormValues(existing);
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"/agents/action\" class=\"npc-def-form\" "
                + "data-reward-def-form data-submit-once>");
        if (draft != null) {
            sb.append("<div class=\"alert alert-danger py-2\" role=\"alert\" data-refused-form><strong>Refusé, rien "
                    + "n'a été enregistré :</strong> ").append(Http.esc(draft.getOrDefault("_error", "")))
                    .append("<br>Vos saisies sont conservées ci-dessous : corriger puis renvoyer.</div>");
        }
        sb.append(rewardHiddenFields(session, agentId, "reward.definition.save"));
        sb.append("<input type=\"hidden\" name=\"create\" value=\"").append(create).append("\">");
        if (!create) {
            // Version ACTUELLE de la récompense : le renvoi après un refus porte sur l'état réel.
            sb.append("<input type=\"hidden\" name=\"reward_key\" value=\"").append(Http.esc(str(existing.get("key"))))
                    .append("\"><input type=\"hidden\" name=\"expected_version\" value=\"")
                    .append(Http.esc(str(existing.get("version")))).append("\">");
        }
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid).append("-l\">Nom de la "
                + "récompense</label><input class=\"form-control\" id=\"").append(uid).append("-l\" name=\"label\" "
                + "required maxlength=\"120\" value=\"").append(Http.esc(v.getOrDefault("label", "")))
                .append("\">").append(fieldHelp("Nom montré au joueur quand il la reçoit (« Récompense reçue : … »).",
                        "Épée du héros", "aucun (obligatoire)", null)).append("</div>");
        if (create) {
            sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid).append("-id\">Identifiant "
                    + "(facultatif)</label><input class=\"form-control\" id=\"").append(uid).append("-id\" "
                    + "name=\"reward_key\" maxlength=\"64\" pattern=\"[a-z0-9_\\-]{0,64}\" value=\"")
                    .append(Http.esc(v.getOrDefault("reward_key", ""))).append("\">")
                    .append(fieldHelp("Ce que les tables et les boss utilisent pour la désigner. Vide : tiré du nom "
                            + "(« Épée du héros » → <code>epee_du_heros</code>). Ne change plus ensuite.",
                            "epee_du_heros", "tiré du nom", null)).append("</div>");
        }
        boolean enabled = "true".equals(v.get("enabled"));
        sb.append("<div class=\"form-check form-switch mb-2\"><input class=\"form-check-input\" type=\"checkbox\" "
                + "role=\"switch\" id=\"").append(uid).append("-en\" name=\"enabled\" value=\"true\"")
                .append(enabled ? " checked" : "").append("><label class=\"form-check-label\" for=\"").append(uid)
                .append("-en\">Récompense active (peut être gagnée)</label></div>");
        int filled = 0;
        for (int i = 0; i < com.lodygames.rpgquest.panel.agent.RewardDefinitionForm.MAX_ITEMS; i++) {
            if (!v.getOrDefault("item_id_" + i, "").isBlank() || !v.getOrDefault("item_ench_" + i, "").isBlank()
                    || !v.getOrDefault("item_potion_" + i, "").isBlank()) {
                filled = i + 1;
            }
        }
        int rows = Math.min(com.lodygames.rpgquest.panel.agent.RewardDefinitionForm.MAX_ITEMS,
                Math.max(3, filled + REWARD_ITEM_BLANK_ROWS));
        sb.append("<p class=\"form-label mb-1\">Objets remis (tous ensemble)</p><div class=\"form-text mb-1\">Taper pour "
                + "chercher un objet (nom français ou identifiant). Enchantements : taper un nom (« tranchant », "
                + "« solidité »…), choisir le niveau puis « Ajouter » — sur l'objet, ou <em>stockés</em> dans un livre "
                + "enchanté. Potion : seulement sur POTION, SPLASH_POTION, LINGERING_POTION ou TIPPED_ARROW. Vider "
                + "l'objet pour retirer la ligne.</div>");
        for (int i = 0; i < rows; i++) {
            sb.append(rewardItemRow(uid, i, v.getOrDefault("item_id_" + i, ""), v.getOrDefault("item_amount_" + i, ""),
                    v.getOrDefault("item_ench_" + i, ""), v.getOrDefault("item_potion_" + i, "")));
        }
        sb.append(enchantmentsHelp(d));
        String perkKey = v.getOrDefault("perk_key", "");
        sb.append("<details class=\"mb-2\"").append(perkKey.isEmpty() ? "" : " open").append("><summary>Avancé : "
                + "avantage persistant</summary><div class=\"form-text\">Un avantage est enregistré sur le joueur (clé + "
                + "palier) et lu par un système du jeu qui le connaît. Laisser vide si vous ne savez pas : seuls les "
                + "objets ci-dessus seront remis. Le sac à dos n'est pas accepté ici.</div><div class=\"row g-2 mt-1\">")
                .append("<div class=\"col-6\"><input class=\"form-control\" name=\"perk_key\" maxlength=\"64\" "
                        + "aria-label=\"Clé de l'avantage\" placeholder=\"clé\" value=\"").append(Http.esc(perkKey))
                .append("\"></div><div class=\"col-6\"><input class=\"form-control\" name=\"perk_tier\" maxlength=\"64\" "
                        + "aria-label=\"Palier de l'avantage\" placeholder=\"palier\" value=\"")
                .append(Http.esc(v.getOrDefault("perk_tier", ""))).append("\"></div></div></details>");
        if (!create) {
            sb.append("<div class=\"form-text mb-2\">Contenu actuel : ").append(Http.esc(rewardContentText(existing)))
                    .append(". Modifier change aussi ce que remettront les gains déjà obtenus et pas encore reçus.</div>");
        }
        sb.append("<button class=\"btn btn-primary\" type=\"submit\" data-submit-label=\"Envoi en cours…\">")
                .append(Icons.icon("save")).append(create ? "Créer la récompense" : "Enregistrer").append("</button></form>");
        return sb.toString();
    }

    /**
     * Une ligne d'objet : objet (nom français affiché sous le champ, issue #474), quantité,
     * enchantements (sélecteur guidé + champ texte soumis), potion.
     */
    private static String rewardItemRow(String uid, int i, String itemId, String amount, String ench, String potion) {
        String row = uid + "-i" + i;
        return "<div class=\"row g-2 mb-2 pb-2 border-bottom align-items-start\" data-reward-item-row>"
                + "<div class=\"col-12 col-md-5\"><input class=\"form-control\" type=\"text\" name=\"item_id_" + i
                + "\" aria-label=\"Objet " + (i + 1) + "\" value=\"" + Http.esc(itemId) + "\" list=\"dl-reward-items\" "
                + "autocomplete=\"off\" placeholder=\"Objet…\" data-reward-item>"
                + "<div class=\"form-text mt-0\" data-item-picked>" + itemPickedHtml(itemId) + "</div></div>"
                + "<div class=\"col-4 col-md-1\"><input class=\"form-control\" type=\"number\" min=\"1\" max=\"64\" "
                + "name=\"item_amount_" + i + "\" aria-label=\"Quantité objet " + (i + 1) + "\" value=\""
                + Http.esc(amount) + "\" placeholder=\"1\"></div>"
                + "<div class=\"col-8 col-md-4\" data-ench-box>"
                + "<input class=\"form-control\" type=\"text\" name=\"item_ench_" + i + "\" id=\"" + row + "-e\" "
                + "aria-label=\"Enchantements objet " + (i + 1) + "\" value=\"" + Http.esc(ench) + "\" "
                + "placeholder=\"Enchantements (sharpness 3)\" data-ench-store>"
                + "<div class=\"d-flex flex-wrap gap-1 mt-1\" data-ench-chips aria-live=\"polite\"></div>"
                + "<div class=\"ench-pick mt-1\" data-ench-pick-box hidden><div class=\"combo\" data-combo>"
                + "<input class=\"form-control form-control-sm combo-input\" type=\"text\" list=\"dl-reward-enchants\" "
                + "autocomplete=\"off\" role=\"combobox\" aria-expanded=\"false\" aria-autocomplete=\"list\" "
                + "aria-label=\"Chercher un enchantement pour l'objet " + (i + 1) + "\" "
                + "placeholder=\"Ajouter un enchantement…\" data-ench-pick></div>"
                + "<div class=\"d-flex gap-1 mt-1\"><select class=\"form-select form-select-sm\" aria-label=\"Niveau\" "
                + "data-ench-level disabled><option value=\"\">Niveau</option></select>"
                + "<button class=\"btn btn-sm secondary\" type=\"button\" data-ench-add disabled>Ajouter</button></div>"
                + "<div class=\"form-text mt-1\" data-ench-info aria-live=\"polite\"></div></div></div>"
                + "<div class=\"col-12 col-md-2\"><input class=\"form-control\" type=\"text\" name=\"item_potion_" + i
                + "\" aria-label=\"Potion objet " + (i + 1) + "\" value=\"" + Http.esc(potion) + "\" "
                + "list=\"dl-reward-potions\" autocomplete=\"off\" placeholder=\"Potion\"></div></div>";
    }

    /** Nom français d'un objet sous son champ, identifiant en complément (même rendu que panel.js). */
    static String itemPickedHtml(String itemId) {
        String id = itemId == null ? "" : itemId.trim();
        if (id.isEmpty()) {
            return "";
        }
        if (id.indexOf(':') >= 0) {
            return "<strong>Objet RPGQuest</strong> · <code>" + Http.esc(id) + "</code>";
        }
        return "<strong>" + Http.esc(MaterialNames.french(id.toUpperCase(java.util.Locale.ROOT))) + "</strong> · <code>"
                + Http.esc(id) + "</code>";
    }

    /** Une récompense chargée → champs du formulaire (même forme qu'un envoi). Création : par défaut. */
    private static Map<String, String> definitionFormValues(Map<String, Object> existing) {
        Map<String, String> v = new java.util.LinkedHashMap<>();
        if (existing == null) {
            v.put("enabled", "true");
            return v;
        }
        v.put("reward_key", str(existing.get("key")));
        v.put("label", str(existing.get("label")));
        if (!Boolean.FALSE.equals(existing.get("enabled"))) {
            v.put("enabled", "true");
        }
        List<Object> items = asList(existing.get("items"));
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = asMap(items.get(i));
            v.put("item_id_" + i, str(item.get("item")));
            v.put("item_amount_" + i, str(item.get("amount")));
            v.put("item_ench_" + i, str(item.get("enchantments")).replace(":", " ").replace(",", ", "));
            v.put("item_potion_" + i, str(item.get("potion")));
        }
        v.put("perk_key", str(existing.get("perkKey")));
        v.put("perk_tier", str(existing.get("perkTier")));
        return v;
    }

    /**
     * Liste des enchantements du serveur, en français, avec leur niveau maximal (issue #474). Remplace
     * l'ancienne liste brute d'identifiants anglais.
     */
    private static String enchantmentsHelp(Map<String, Object> d) {
        List<Map<String, Object>> info = enchantmentInfo(d);
        if (info.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<details class=\"mb-2 small\"><summary>Enchantements connus du serveur (")
                .append(info.size()).append(")</summary><ul class=\"mb-0 ench-help\">");
        for (Map<String, Object> e : info) {
            String key = str(e.get("key"));
            int max = intOf(e.get("maxLevel"));
            sb.append("<li><strong>").append(Http.esc(EnchantmentNames.french(key))).append("</strong> <code>")
                    .append(Http.esc(key)).append("</code>")
                    .append(max > 0 ? " · niveau " + (max == 1 ? "I seulement" : "I à " + EnchantmentNames.level(max)) : "")
                    .append("</li>");
        }
        return sb.append("</ul></details>").toString();
    }

    /**
     * Enchantements du dernier relevé : détaillés si le plugin les fournit ({@code enchantmentInfo},
     * #474), sinon la simple liste d'identifiants d'un plugin plus ancien (niveau inconnu).
     */
    static List<Map<String, Object>> enchantmentInfo(Map<String, Object> d) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Object> detailed = asList(d.get("enchantmentInfo"));
        if (!detailed.isEmpty()) {
            detailed.forEach(o -> out.add(asMap(o)));
            return out;
        }
        for (Object key : asList(d.get("enchantments"))) {
            out.add(Map.of("key", str(key)));
        }
        return out;
    }

    private static int intOf(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(str(o).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String rewardModesHelp() {
        return "<details class=\"card card-body mb-3\"><summary><strong>Comment fonctionnent les tirages ?</strong>"
                + "</summary><ul class=\"mb-1 mt-2\">"
                + "<li><strong>Une récompense au plus</strong> : le joueur gagne <em>une seule</em> des récompenses "
                + "de la table, ou rien. Les chances s'additionnent et ne peuvent pas dépasser 100 % ; ce qui manque "
                + "pour arriver à 100 % est la chance de ne rien gagner. Ex. : 10 % épée + 30 % pommes = 40 % de "
                + "gagner quelque chose, 60 % de rien.</li>"
                + "<li><strong>Chaque récompense tentée séparément</strong> : chaque ligne a sa propre chance, "
                + "tirée à part. Le joueur peut en gagner plusieurs, ou aucune, et le total peut dépasser 100 %.</li>"
                + "<li>Le tirage est fait <strong>une fois</strong> (fin de quête, mort du boss) puis figé : une "
                + "reconnexion ou un redémarrage ne le relancent jamais. Modifier une table change sa "
                + "<em>version</em> ; les gains déjà tirés gardent la leur.</li>"
                + "<li>Une table <strong>désactivée</strong>, ou des récompenses désactivées sur le serveur : "
                + "aucun tirage, et le joueur en est averti (même règle pour les quêtes et les boss).</li>"
                + "</ul></details>";
    }

    private String rewardTableCard(Session session, String agentId, Map<String, Object> t, boolean canWrite,
                                   Map<String, String> rewardNames, Map<String, String> draft) {
        String id = str(t.get("id"));
        boolean enabled = Boolean.TRUE.equals(t.get("enabled"));
        String uid = "rt-" + id.replaceAll("[^a-z0-9_-]", "");
        StringBuilder sb = new StringBuilder("<div class=\"card card-body mb-3\" id=\"").append(uid).append("\">");
        sb.append("<div><strong>").append(Http.esc(str(t.get("label")))).append("</strong> <code>")
                .append(Http.esc(id)).append("</code> <span class=\"badge text-bg-info\">")
                .append(Http.esc("single".equals(str(t.get("mode"))) ? "Une récompense au plus"
                        : "Chaque récompense tentée séparément")).append("</span> ")
                .append(enabled ? "<span class=\"badge text-bg-success\">active</span>"
                        : "<span class=\"badge text-bg-secondary\">désactivée</span>")
                .append(" <span class=\"muted small\">version ").append(Http.esc(str(t.get("version"))))
                .append(" · total ").append(Http.esc(str(t.get("totalChance")))).append("</span></div>");
        sb.append("<ul class=\"mb-1 mt-2\">");
        for (Object e : asList(t.get("entries"))) {
            Map<String, Object> entry = asMap(e);
            sb.append("<li><strong>").append(Http.esc(str(entry.get("chance")))).append("</strong> — ")
                    .append(Http.esc(str(entry.get("rewardLabel"))));
            if (!Boolean.TRUE.equals(entry.get("rewardKnown"))) {
                sb.append(" <span class=\"badge text-bg-danger\">récompense inconnue</span>");
            }
            sb.append("</li>");
        }
        sb.append("</ul><div class=\"muted small\">").append(Http.esc(str(t.get("explanation")))).append("</div>");
        List<Object> usages = asList(t.get("usages"));
        if (!usages.isEmpty()) {
            sb.append("<div class=\"small\">Tirée par : ")
                    .append(Http.esc(String.join(", ", usages.stream().map(AgentPages::str).toList()))).append(".</div>");
        }
        sb.append("<div class=\"muted small\">Utilisation : quête → récompense « Tirage au sort », table <code>")
                .append(Http.esc(id)).append("</code> ; boss → récompense de participation <code>table:")
                .append(Http.esc(id)).append("</code>.</div>");
        if (canWrite) {
            sb.append("<div class=\"d-flex flex-wrap gap-2 mt-2\">");
            sb.append("<button class=\"btn secondary sm\" type=\"button\" data-bs-toggle=\"collapse\" "
                    + "data-bs-target=\"#").append(uid).append("-edit\" aria-expanded=\"false\">")
                    .append(Icons.icon("edit")).append("Modifier</button>");
            sb.append("<form method=\"post\" action=\"/agents/action\" class=\"d-inline\">")
                    .append(rewardHiddenFields(session, agentId, "reward.table.toggle"))
                    .append("<input type=\"hidden\" name=\"table_id\" value=\"").append(Http.esc(id)).append("\">")
                    .append("<input type=\"hidden\" name=\"enabled\" value=\"").append(enabled ? "false" : "true")
                    .append("\"><button class=\"btn sm").append(enabled ? " secondary" : "")
                    .append("\" type=\"submit\">").append(enabled ? "Désactiver" : "Activer").append("</button></form>");
            sb.append("<button class=\"btn secondary sm\" type=\"button\" data-bs-toggle=\"collapse\" "
                    + "data-bs-target=\"#").append(uid).append("-dup\" aria-expanded=\"false\">")
                    .append(Icons.icon("plus")).append("Dupliquer</button>");
            sb.append("</div>");
            sb.append("<div class=\"collapse mt-2\" id=\"").append(uid).append("-dup\">")
                    .append("<form method=\"post\" action=\"/agents/action\" class=\"npc-def-form\">")
                    .append(rewardHiddenFields(session, agentId, "reward.table.duplicate"))
                    .append("<input type=\"hidden\" name=\"table_id\" value=\"").append(Http.esc(id)).append("\">")
                    .append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid)
                    .append("-dup-id\">Identifiant de la copie</label><input class=\"form-control\" id=\"").append(uid)
                    .append("-dup-id\" name=\"new_id\" required maxlength=\"32\" pattern=\"[a-z0-9_\\-]{1,32}\" value=\"")
                    .append(Http.esc(copyId(id))).append("\"></div>")
                    .append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid)
                    .append("-dup-l\">Nom de la copie (facultatif)</label><input class=\"form-control\" id=\"")
                    .append(uid).append("-dup-l\" name=\"new_label\" maxlength=\"120\"></div>")
                    .append("<div class=\"form-text mb-2\">La copie est créée <strong>désactivée</strong>, avec les "
                            + "mêmes récompenses et chances : la modifier, puis l'activer.</div>")
                    .append("<button class=\"btn sm\" type=\"submit\">Créer la copie</button></form></div>");
            sb.append("<div class=\"collapse mt-2").append(draft != null ? " show" : "").append("\" id=\"").append(uid)
                    .append("-edit\"><div class=\"card card-body npc-formcard\">")
                    .append(rewardTableForm(session, agentId, t, uid + "-f", rewardNames, draft)).append("</div></div>");
        }
        return sb.append("</div>").toString();
    }

    /** Types dont le formulaire refusé par le panel est réaffiché tel quel (issue #177). */
    public static final Set<String> KEEPS_REFUSED_FORM = Set.of("reward.table.save", "reward.definition.save");

    /** Fenêtre pendant laquelle un refus du plugin peut encore être réaffiché ({@code ?refused=}). */
    private static final java.time.Duration REFUSED_FORM_WINDOW = java.time.Duration.ofMinutes(30);

    /**
     * Le dernier envoi de table refusé, à réafficher (issue #177) : soit refusé par le panel avant la
     * file (gardé une fois dans la session), soit refusé par le plugin — {@code ?refused=<action>},
     * que {@code panel.js} ajoute quand le toast de l'action passe en échec. Seulement une action de
     * cet agent, de ce type, envoyée par cet utilisateur, terminée sans succès et récente.
     */
    Map<String, String> refusedTableForm(Session session, String agentId, Map<String, String> q) {
        return refusedForm(session, agentId, q, "reward.table.save",
                com.lodygames.rpgquest.panel.agent.RewardTableForm::formOf);
    }

    /** Même chose pour une récompense du catalogue (issue #474) : la saisie n'est jamais perdue. */
    Map<String, String> refusedDefinitionForm(Session session, String agentId, Map<String, String> q) {
        return refusedForm(session, agentId, q, "reward.definition.save",
                com.lodygames.rpgquest.panel.agent.RewardDefinitionForm::formOf);
    }

    private Map<String, String> refusedForm(Session session, String agentId, Map<String, String> q, String type,
                                            java.util.function.Function<Map<String, String>, Map<String, String>> formOf) {
        Optional<Map<String, String>> kept = session.takeRefusedForm(type);
        if (kept.isPresent()) {
            return kept.get();
        }
        String id = q.getOrDefault("refused", "").trim();
        if (id.isEmpty()) {
            return null;
        }
        return store.action(id)
                .filter(a -> agentId.equals(a.agentId()) && type.equals(a.type())
                        && session.username().equals(a.createdBy()) && a.status().terminal()
                        && a.status() != AgentActionStatus.SUCCESS
                        && a.createdAt().isAfter(java.time.Instant.now().minus(REFUSED_FORM_WINDOW)))
                .map(a -> {
                    Map<String, String> form = new java.util.LinkedHashMap<>(formOf.apply(a.params()));
                    form.put("_error", a.status() == AgentActionStatus.EXPIRED
                            ? "le serveur n'a pas répondu à temps."
                            : str(a.resultMessage()).isBlank() ? "refus du serveur." : str(a.resultMessage()));
                    return form;
                })
                .orElse(null);
    }

    /**
     * Nom d'une récompense du catalogue avec ce qu'elle donne, quantités comprises :
     * « Gemme rare — 3 × Diamant, 1 × Livre enchanté (sharpness 3) » (issue #177).
     */
    static String rewardDisplayName(Map<String, Object> r) {
        String what = rewardContentText(r);
        return str(r.get("label")) + (Boolean.FALSE.equals(r.get("enabled")) ? " (désactivée)" : "")
                + (what.isBlank() ? "" : " — " + what);
    }

    /**
     * Ce que donne une récompense, en français : « 3 × Diamant, 1 × Livre enchanté (Tranchant III) »
     * (issues #177, #474). Sans objet lisible : le résumé du serveur.
     */
    static String rewardContentText(Map<String, Object> r) {
        List<String> parts = new ArrayList<>();
        for (Object o : asList(r.get("items"))) {
            Map<String, Object> item = asMap(o);
            String itemId = str(item.get("item"));
            if (itemId.isBlank()) {
                continue;
            }
            String amount = str(item.get("amount"));
            String name = itemId.contains(":") ? itemId : MaterialNames.french(itemId);
            StringBuilder part = new StringBuilder(amount.isBlank() ? "1" : amount).append(" × ")
                    .append(name.isBlank() ? itemId : name);
            List<String> extras = new ArrayList<>();
            extras.addAll(enchantmentLabels(item.get("enchantments")));
            if (!str(item.get("potion")).isBlank()) {
                extras.add("potion " + str(item.get("potion")));
            }
            if (!extras.isEmpty()) {
                part.append(" (").append(String.join(", ", extras)).append(')');
            }
            parts.add(part.toString());
        }
        if (!str(r.get("perkKey")).isBlank()) {
            parts.add("avantage " + str(r.get("perkKey")) + (str(r.get("perkTier")).isBlank() ? ""
                    : " " + str(r.get("perkTier"))));
        }
        return parts.isEmpty() ? str(r.get("summary")) : String.join(", ", parts);
    }

    /** {@code {sharpness: 3}} ou {@code "sharpness:3,unbreaking:1"} → « Tranchant III », « Solidité I ». */
    static List<String> enchantmentLabels(Object ench) {
        List<String> labels = new ArrayList<>();
        if (ench instanceof Map<?, ?> map) {
            map.forEach((k, lvl) -> labels.add(enchantmentLabel(str(k), str(lvl))));
        } else {
            for (String part : str(ench).split(",")) {
                String[] kv = part.trim().split("[: ]+");
                if (kv.length == 2) {
                    labels.add(enchantmentLabel(kv[0], kv[1]));
                } else if (!part.isBlank()) {
                    labels.add(part.trim());
                }
            }
        }
        return labels;
    }

    private static String enchantmentLabel(String key, String level) {
        try {
            return EnchantmentNames.label(key, Integer.parseInt(level.trim()));
        } catch (NumberFormatException e) {
            return EnchantmentNames.french(key) + " " + level;
        }
    }

    /** Types dont une action en cours rend la page Récompenses momentanément en retard sur le serveur. */
    private static boolean rewardActionType(String type) {
        return type.startsWith("reward.") || "boss.community.status".equals(type);
    }

    /**
     * Opération en cours sur le catalogue (issue #474) : dit ce qui se passe et que la page se mettra à
     * jour seule. Chaque action suivie porte {@code data-pa-track-action} : {@code panel.js} recharge
     * la page dès que le serveur l'a confirmée (et que la file est au repos).
     */
    String rewardPendingBanner(String agentId) {
        List<AgentActionRow> open = store.recentActions(agentId, 50).stream()
                .filter(a -> !a.status().terminal() && rewardActionType(a.type())).toList();
        if (open.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<div class=\"alert alert-info py-2 d-flex flex-wrap align-items-center "
                + "gap-2\" role=\"status\" aria-live=\"polite\" data-reward-pending><span class=\"spinner-border "
                + "spinner-border-sm\" aria-hidden=\"true\"></span><span><strong>Opération en cours :</strong> ");
        List<String> labels = new ArrayList<>();
        for (AgentActionRow a : open) {
            labels.add(AgentActionCatalog.spec(a.type()).map(AgentActionCatalog.Spec::label).orElse(a.type()));
        }
        sb.append(Http.esc(String.join(", ", new java.util.LinkedHashSet<>(labels))))
                .append(". La page se met à jour toute seule dès la confirmation du serveur : inutile de renvoyer "
                        + "le formulaire.</span>");
        for (AgentActionRow a : open) {
            sb.append("<span hidden data-pa-track-action=\"").append(Http.esc(a.id())).append("\"></span>");
        }
        return sb.append("</div>").toString();
    }

    private static String copyId(String id) {
        String base = id.length() > 26 ? id.substring(0, 26) : id;
        return base + "_copie";
    }

    private static String rewardHiddenFields(Session session, String agentId, String type) {
        return "<input type=\"hidden\" name=\"_csrf\" value=\"" + Http.esc(session.csrfToken()) + "\">"
                + "<input type=\"hidden\" name=\"agent\" value=\"" + Http.esc(agentId) + "\">"
                + "<input type=\"hidden\" name=\"type\" value=\"" + Http.esc(type) + "\">"
                + "<input type=\"hidden\" name=\"return\" value=\"" + REWARDS_PATH + "\">"
                // #474 : jeton à usage unique, un double clic n'empile pas deux actions.
                + "<input type=\"hidden\" name=\"submit_token\" value=\"" + java.util.UUID.randomUUID() + "\">";
    }

    /**
     * Formulaire de table, création ({@code existing == null}) ou modification. {@code draft} = un
     * envoi refusé par le serveur (champs du formulaire) : il est réaffiché tel quel, avec le refus
     * (issue #177). {@code panel.js} grise le bouton tant que le formulaire est invalide et dit
     * pourquoi ; sans JavaScript, le bouton reste actif et le serveur revalide tout.
     */
    private String rewardTableForm(Session session, String agentId, Map<String, Object> existing, String uid,
                                   Map<String, String> rewardNames, Map<String, String> draft) {
        boolean create = existing == null;
        Map<String, String> v = draft != null ? draft : tableFormValues(existing);
        String mode = "independent".equals(v.get("mode")) ? "independent" : "single";
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"/agents/action\" class=\"npc-def-form\" "
                + "data-chance-form>");
        if (draft != null) {
            sb.append("<div class=\"alert alert-danger py-2\" role=\"alert\" data-refused-form><strong>Refusé, rien "
                    + "n'a été enregistré :</strong> ").append(Http.esc(draft.getOrDefault("_error", "")))
                    .append("<br>Vos saisies sont conservées ci-dessous : corriger puis renvoyer.</div>");
        }
        sb.append(rewardHiddenFields(session, agentId, "reward.table.save"));
        sb.append("<input type=\"hidden\" name=\"create\" value=\"").append(create).append("\">");
        if (!create) {
            // Version ACTUELLE de la table : le refus vient d'être montré, le renvoi porte sur l'état réel.
            sb.append("<input type=\"hidden\" name=\"table_id\" value=\"").append(Http.esc(str(existing.get("id"))))
                    .append("\"><input type=\"hidden\" name=\"expected_version\" value=\"")
                    .append(Http.esc(str(existing.get("version")))).append("\">");
        }
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid).append("-l\">Nom de la table</label>")
                .append("<input class=\"form-control\" id=\"").append(uid).append("-l\" name=\"label\" required "
                        + "maxlength=\"120\" data-rt-label value=\"").append(Http.esc(v.getOrDefault("label", "")))
                .append("\">").append(fieldHelp("Nom montré au joueur au moment du tirage.", "Butin de l'ogre",
                        "aucun (obligatoire)", null)).append("</div>");
        if (create) {
            sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid).append("-id\">Identifiant "
                    + "(facultatif)</label><input class=\"form-control\" id=\"").append(uid).append("-id\" "
                    + "name=\"table_id\" maxlength=\"32\" pattern=\"[a-z0-9_\\-]{0,32}\" data-rt-id value=\"")
                    .append(Http.esc(v.getOrDefault("table_id", ""))).append("\">")
                    .append(fieldHelp("Ce que les quêtes et boss utilisent pour désigner la table. Vide : tiré du nom "
                            + "(« Butin de l'ogre » → <code>butin_de_l_ogre</code>). Ne change plus ensuite.",
                            "butin_ogre", "tiré du nom", null)).append("</div>");
        }
        sb.append("<fieldset class=\"mb-2\"><legend class=\"form-label fs-6\">Comment la table est tirée</legend>")
                .append(modeRadio(uid, "single", mode, "Une récompense au plus",
                        "Le joueur gagne une seule récompense de la liste, ou rien. Total ≤ 100 %."))
                .append(modeRadio(uid, "independent", mode, "Chaque récompense tentée séparément",
                        "Chaque ligne est tirée à part : plusieurs gains possibles, ou aucun. Le total peut dépasser "
                                + "100 % (chaque chance reste entre 0,01 et 100 %)."))
                .append("</fieldset>");
        boolean enabled = "true".equals(v.get("enabled"));
        sb.append("<div class=\"form-check form-switch mb-2\"><input class=\"form-check-input\" type=\"checkbox\" "
                + "role=\"switch\" id=\"").append(uid).append("-en\" name=\"enabled\" value=\"true\"")
                .append(enabled ? " checked" : "").append("><label class=\"form-check-label\" for=\"").append(uid)
                .append("-en\">Table active (tirée par les quêtes et boss qui l'utilisent)</label></div>");
        int filled = 0;
        for (int i = 0; i < com.lodygames.rpgquest.panel.agent.RewardTableForm.MAX_ENTRIES; i++) {
            if (!v.getOrDefault("entry_reward_" + i, "").isBlank() || !v.getOrDefault("entry_chance_" + i, "").isBlank()) {
                filled = i + 1;
            }
        }
        int rows = Math.min(com.lodygames.rpgquest.panel.agent.RewardTableForm.MAX_ENTRIES,
                Math.max(REWARD_TABLE_MIN_ROWS, filled + REWARD_TABLE_BLANK_ROWS));
        sb.append("<p class=\"form-label mb-1\">Récompenses et chances</p>")
                .append("<div class=\"form-text mb-1\">Choisir une récompense du catalogue (taper pour chercher) et sa "
                        + "chance en %. Vider les deux champs d'une ligne pour la retirer.</div>");
        int total = 0;
        for (int i = 0; i < rows; i++) {
            String reward = v.getOrDefault("entry_reward_" + i, "");
            String chance = v.getOrDefault("entry_chance_" + i, "");
            Integer h = com.lodygames.rpgquest.panel.agent.RewardTableForm.hundredthsOf(chance);
            total += h == null ? 0 : h;
            sb.append("<div class=\"row g-2 mb-1 align-items-center\" data-chance-row>")
                    .append("<input type=\"hidden\" name=\"entry_id_").append(i).append("\" value=\"")
                    .append(Http.esc(v.getOrDefault("entry_id_" + i, ""))).append("\">")
                    .append("<div class=\"col-12 col-md-8\"><div class=\"combo\" data-combo><input class=\"form-control "
                            + "combo-input\" type=\"text\" name=\"entry_reward_").append(i)
                    .append("\" aria-label=\"Récompense ligne ").append(i + 1).append("\" value=\"")
                    .append(Http.esc(reward)).append("\" list=\"dl-reward-catalog\" data-rt-reward "
                            + "autocomplete=\"off\" role=\"combobox\" aria-expanded=\"false\" aria-autocomplete=\"list\" "
                            + "placeholder=\"Récompense…\"></div>")
                    .append("<div class=\"form-text mt-0\" data-rt-picked>").append(rewardPickedHtml(reward, rewardNames))
                    .append("</div></div>")
                    .append("<div class=\"col-8 col-md-3\"><div class=\"input-group\"><input class=\"form-control\" "
                            + "type=\"text\" inputmode=\"decimal\" name=\"entry_chance_").append(i)
                    .append("\" aria-label=\"Chance ligne ").append(i + 1).append(" en pour cent\" maxlength=\"6\" "
                            + "pattern=\"\\d{1,3}([.,]\\d{1,2})?\" data-chance value=\"").append(Http.esc(chance))
                    .append("\"><span class=\"input-group-text\">%</span></div></div></div>");
        }
        sb.append("<p class=\"mt-2 mb-2\" data-chance-total aria-live=\"polite\">")
                .append(Http.esc(chanceTotalText(total, mode))).append("</p>");
        sb.append("<div class=\"d-flex flex-wrap align-items-center gap-2\"><button class=\"btn btn-primary\" "
                + "type=\"submit\" data-rt-submit>").append(Icons.icon("save"))
                .append(create ? "Créer la table" : "Enregistrer").append("</button>")
                .append("<span class=\"small text-danger\" id=\"").append(uid).append("-why\" data-rt-why "
                        + "aria-live=\"polite\"></span></div></form>");
        return sb.toString();
    }

    /** Une table chargée → champs du formulaire (même forme qu'un envoi). Création : valeurs par défaut. */
    private static Map<String, String> tableFormValues(Map<String, Object> existing) {
        Map<String, String> v = new java.util.LinkedHashMap<>();
        if (existing == null) {
            v.put("mode", "single");
            v.put("enabled", "true");
            return v;
        }
        v.put("label", str(existing.get("label")));
        v.put("mode", str(existing.get("mode")));
        if (Boolean.TRUE.equals(existing.get("enabled"))) {
            v.put("enabled", "true");
        }
        List<Object> entries = asList(existing.get("entries"));
        for (int i = 0; i < entries.size(); i++) {
            Map<String, Object> entry = asMap(entries.get(i));
            v.put("entry_id_" + i, str(entry.get("id")));
            v.put("entry_reward_" + i, str(entry.get("rewardKey")));
            v.put("entry_chance_" + i, str(entry.get("chanceValue")).replace('.', ','));
        }
        return v;
    }

    /**
     * Ce qu'une ligne a choisi, lisible : nom et quantité d'abord, identifiant en complément
     * (issue #177). Même rendu que {@code panel.js} recalcule à la sélection.
     */
    static String rewardPickedHtml(String key, Map<String, String> rewardNames) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String name = rewardNames.get(key.trim().toLowerCase(java.util.Locale.ROOT));
        if (name == null) {
            return "<span class=\"text-danger\">Récompense absente du catalogue</span> · <code>" + Http.esc(key)
                    + "</code>";
        }
        return "<strong>" + Http.esc(name) + "</strong> · <code>" + Http.esc(key) + "</code>";
    }

    private static String modeRadio(String uid, String value, String current, String label, String help) {
        String id = uid + "-m-" + value;
        return "<div class=\"form-check\"><input class=\"form-check-input\" type=\"radio\" name=\"mode\" id=\"" + id
                + "\" value=\"" + value + "\" data-chance-mode" + (value.equals(current) ? " checked" : "") + " required>"
                + "<label class=\"form-check-label\" for=\"" + id + "\">" + Http.esc(label) + "</label>"
                + "<div class=\"form-text mt-0\">" + Http.esc(help) + "</div></div>";
    }

    /** Même texte que {@code panel.js} recalcule à la saisie. */
    static String chanceTotalText(int hundredths, String mode) {
        String total = com.lodygames.rpgquest.panel.agent.RewardTableForm.percentText(hundredths);
        if ("single".equals(mode)) {
            return hundredths > 10_000 ? "Total : " + total + " — trop élevé, 100 % au plus pour « une récompense au plus »."
                    : "Total : " + total + " · chance de ne rien gagner : "
                    + com.lodygames.rpgquest.panel.agent.RewardTableForm.percentText(10_000 - hundredths) + ".";
        }
        return "Total : " + total + " (chaque ligne est tirée à part ; le total peut dépasser 100 %).";
    }

    /**
     * Issue #177 : tables de récompenses aléatoires chargées depuis {@code entitlements.yml}, en
     * lecture — mode explicite, chance de chaque entrée et explication des chances réelles.
     */
    private String rewardTablesSection(Object raw) {
        if (raw == null) {
            return "";
        }
        List<Object> tables = asList(raw);
        // Issue #169 : un lien vers Récompenses, les tables en lecture repliées, aucune syntaxe de fichier.
        StringBuilder sb = new StringBuilder("<details class=\"mob-fold\"><summary>").append(Icons.icon("gift"))
                .append("Tables de récompenses aléatoires (").append(tables.size()).append(")</summary>")
                .append("<div class=\"mob-fold-b\">");
        sb.append("<p class=\"muted small\">Un boss les tire quand une entrée « Tirage : … » figure dans ses "
                + "récompenses de participation (fiche du profil → Modifier). Chaque gain est remis une seule fois. "
                + "Créer, modifier ou désactiver une table : <a href=\"/rewards\">Récompenses</a>.</p>");
        if (tables.isEmpty()) {
            return sb.append(Ui.empty("gift", "Aucune table chargée sur le serveur.")).append("</div></details>")
                    .toString();
        }
        sb.append("<div class=\"list-group mb-3\">");
        for (Object o : tables) {
            Map<String, Object> t = asMap(o);
            sb.append("<div class=\"list-group-item\"><div><strong>").append(Http.esc(str(t.get("label"))))
                    .append("</strong> <code>").append(Http.esc(str(t.get("id")))).append("</code> ")
                    .append("<span class=\"badge text-bg-info\">").append(Http.esc(str(t.get("modeLabel"))))
                    .append("</span>");
            if (!Boolean.TRUE.equals(t.get("enabled"))) {
                sb.append(" <span class=\"badge text-bg-secondary\">désactivée</span>");
            }
            sb.append(" <span class=\"muted small\">version ").append(Http.esc(str(t.get("version"))))
                    .append(" · total ").append(Http.esc(str(t.get("totalChance")))).append("</span></div>");
            sb.append("<ul class=\"mb-1\">");
            for (Object e : asList(t.get("entries"))) {
                Map<String, Object> entry = asMap(e);
                sb.append("<li><strong>").append(Http.esc(str(entry.get("chance")))).append("</strong> — ")
                        .append(Http.esc(str(entry.get("rewardLabel")))).append(" <span class=\"muted small\">(")
                        .append(Http.esc(str(entry.get("rewardKey")))).append(")</span>");
                if (!Boolean.TRUE.equals(entry.get("rewardKnown"))) {
                    sb.append(" <span class=\"badge text-bg-danger\">récompense inconnue</span>");
                }
                sb.append("</li>");
            }
            sb.append("</ul><div class=\"muted small\">").append(Http.esc(str(t.get("explanation"))))
                    .append("</div></div>");
        }
        return sb.append("</div></div></details>").toString();
    }

    /** Issue #176 : traçabilité des dernières victoires — participants, verdicts, droits déclarés. */
    private String communityBossDefeats(List<Object> defeats, long now) {
        StringBuilder sb = new StringBuilder("<p class=\"npc-fs-h mt-3\">Victoires récentes et participants</p>");
        if (defeats.isEmpty()) {
            return sb.append(Ui.empty("mob", "Aucun boss communautaire vaincu depuis le dernier démarrage du serveur."))
                    .toString();
        }
        sb.append("<p class=\"muted small\">Depuis le dernier démarrage du serveur (les droits déclarés, eux, restent "
                + "visibles pour toujours dans la fiche de chaque joueur → Monnaie → Droits à récompense).</p>");
        sb.append("<div class=\"list-group mb-3\">");
        for (Object raw : defeats) {
            Map<String, Object> d = asMap(raw);
            sb.append("<div class=\"list-group-item\"><div><strong>").append(Http.esc(str(d.get("profileName"))))
                    .append("</strong>");
            if (Boolean.TRUE.equals(d.get("test"))) {
                sb.append(" <span class=\"badge text-bg-warning\">test</span>");
            }
            sb.append(" <span class=\"muted\">vaincu ").append(Http.esc(relative(asLong(d.get("defeatedAt")) - now)));
            String killer = str(d.get("killerName"));
            if (!killer.isBlank()) {
                sb.append(" · dernier coup : ").append(Http.esc(killer));
            }
            sb.append(" · biome ").append(Http.esc(prettyBiomeName(str(d.get("biome")))));
            if (asLong(d.get("radius")) > 0) {
                sb.append(" · récompenses à ").append(asLong(d.get("radius"))).append(" blocs au plus");
            }
            sb.append("</span></div>");
            List<Object> participants = asList(d.get("participants"));
            if (participants.isEmpty()) {
                sb.append("<div class=\"muted\">Aucun joueur ne l'a touché.</div>");
            } else {
                sb.append("<ul class=\"mb-1\">");
                for (Object o : participants) {
                    Map<String, Object> p = asMap(o);
                    boolean eligible = Boolean.TRUE.equals(p.get("eligible"));
                    sb.append("<li>").append(Http.esc(str(p.get("name")))).append(" — ")
                            .append(eligible ? "<span class=\"badge text-bg-success\">éligible</span>"
                                    : "<span class=\"badge text-bg-secondary\">exclu</span> "
                                    + Http.esc(str(p.get("reason"))))
                            .append("</li>");
                }
                sb.append("</ul>");
            }
            List<Object> keys = asList(d.get("rewardKeys"));
            sb.append("<div class=\"muted small\">Récompenses du profil : ")
                    .append(keys.isEmpty() ? "aucune" : Http.esc(String.join(", ",
                            keys.stream().map(AgentPages::str).toList())))
                    .append(" · ").append(Http.esc(str(d.get("rewardStatus")))).append("</div>");
            // Issue #177 : résultat individuel de chaque tirage (table, version, gains).
            List<Object> draws = asList(d.get("draws"));
            if (!draws.isEmpty()) {
                sb.append("<div class=\"muted small\">Tirages : ").append(Http.esc(String.join(" ; ",
                        draws.stream().map(AgentPages::str).toList()))).append("</div>");
            }
            sb.append("</div>");
        }
        return sb.append("</div>").toString();
    }

    private String communityBossInstanceItem(Session session, String agentId, Map<String, Object> i, long now,
                                             boolean canTest) {
        StringBuilder sb = new StringBuilder("<div class=\"list-group-item\">");
        sb.append("<div><strong>").append(Http.esc(str(i.get("profileName")))).append("</strong>");
        if (Boolean.TRUE.equals(i.get("test"))) {
            sb.append(" <span class=\"badge text-bg-warning\">test</span>");
        }
        sb.append("</div>");
        String biome = prettyBiomeName(str(i.get("biome")));
        String waypoint = str(i.get("waypointName"));
        sb.append("<div class=\"muted\">Biome : ").append(Http.esc(biome)).append(" · ");
        if (waypoint.isBlank()) {
            sb.append("loin de tout waypoint");
        } else {
            String dir = str(i.get("waypointDirection"));
            sb.append(dir.isBlank() ? "tout près du waypoint " : "à ~" + Http.esc(str(i.get("waypointDistance")))
                    + " blocs au " + Http.esc(dir) + " du waypoint ").append("<strong>")
                    .append(Http.esc(waypoint)).append("</strong>");
        }
        sb.append("</div>");
        sb.append("<div class=\"muted small\">Dernière position connue : ").append(Http.esc(str(i.get("world"))))
                .append(" ").append(Http.esc(str(i.get("x")))).append(" ").append(Http.esc(str(i.get("y"))))
                .append(" ").append(Http.esc(str(i.get("z")))).append(" · apparu ")
                .append(Http.esc(relative(asLong(i.get("spawnedAt")) - now))).append("</div>");
        if (canTest) {
            sb.append(communityBossRemoveForm(session, agentId, str(i.get("entityId")), "Retirer ce boss",
                    "Je confirme : retirer ce boss (aucune récompense ne sera donnée)"));
        }
        sb.append("</div>");
        return sb.toString();
    }

    private String communityBossSettingsForm(Session session, String agentId, Map<String, Object> d) {
        boolean enabled = Boolean.TRUE.equals(d.get("enabled"));
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"/agents/action\" class=\"npc-def-form\">");
        sb.append(hiddenActionFields(session, agentId, "boss.community.settings.set"));
        sb.append("<input type=\"hidden\" name=\"confirm\" value=\"true\">");
        sb.append("<div class=\"npc-fs\"><p class=\"npc-fs-h\">Réglages</p>");
        sb.append("<div class=\"form-check form-switch\"><input class=\"form-check-input\" type=\"checkbox\" "
                + "role=\"switch\" id=\"cb-en\" name=\"enabled\" value=\"true\"").append(enabled ? " checked" : "")
                .append("><label class=\"form-check-label\" for=\"cb-en\">Apparitions automatiques actives</label></div>")
                .append(fieldHelp("Décoché, plus aucun boss n'apparaît tout seul, <strong>tout de suite</strong>. "
                        + "Les boss déjà présents restent en jeu. Le bouton d'apparition de test marche quand même.",
                        null, "désactivé", null));
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"cb-int\">Minutes entre deux essais</label>")
                .append("<input class=\"form-control\" id=\"cb-int\" type=\"number\" name=\"interval_minutes\" "
                        + "min=\"1\" max=\"1440\" required value=\"").append(Http.esc(str(d.get("intervalMinutes"))))
                .append("\">")
                .append(fieldHelp("Toutes les X minutes, le serveur essaie de faire apparaître un boss s'il en manque. "
                        + "Personne n'a besoin d'être dans le Wild. Si aucun endroit sûr n'est trouvé, il réessaie "
                        + "au tour suivant, sans rattrapage. Une valeur très courte est réservée aux tests sur DEV.",
                        "15", "15", null))
                .append("</div>");
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"cb-max\">Boss en même temps (maximum)</label>")
                .append("<input class=\"form-control\" id=\"cb-max\" type=\"number\" name=\"max_active\" "
                        + "min=\"1\" max=\"10\" required value=\"").append(Http.esc(str(d.get("maxActive"))))
                .append("\">")
                .append(fieldHelp("Tant que ce nombre de boss est en vie (même loin de tout joueur), aucun autre "
                        + "n'apparaît automatiquement.", "1", "1", null))
                .append("</div>");
        String radius = str(d.get("participationRadius"));
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"cb-rad\">Distance pour être récompensé "
                        + "(blocs)</label>")
                .append("<input class=\"form-control\" id=\"cb-rad\" type=\"number\" name=\"participation_radius\" "
                        + "min=\"5\" max=\"500\" required value=\"")
                .append(Http.esc(radius.isBlank() || "0".equals(radius) ? "100" : radius)).append("\">")
                .append(fieldHelp("À la mort du boss, un joueur qui l'a touché n'est récompensé que s'il est dans le "
                        + "même monde et à cette distance au plus de l'endroit où le boss est mort (distance en "
                        + "ligne droite, hauteur comprise). Le biome ne compte pas. Un joueur mort pendant le combat "
                        + "doit le toucher de nouveau après sa réapparition.", "100", "100", null))
                .append("</div>");
        boolean glow = !Boolean.FALSE.equals(d.get("glow"));
        sb.append("<div class=\"form-check form-switch\"><input class=\"form-check-input\" type=\"checkbox\" "
                + "role=\"switch\" id=\"cb-glow\" name=\"glow\" value=\"true\"").append(glow ? " checked" : "")
                .append("><label class=\"form-check-label\" for=\"cb-glow\">Contour lumineux près du boss</label></div>")
                .append(fieldHelp("Quand un joueur s'approche, le boss brille : on voit son contour même derrière un "
                        + "mur ou une colline. Il s'éteint quand plus personne n'est près. Il ne sert pas à le trouver "
                        + "de loin : le jeu n'affiche les monstres qu'à environ 48 blocs. De loin, c'est l'annonce "
                        + "(biome, waypoint, direction) qui guide.", "activé", "activé", null));
        String glowRadius = str(d.get("glowRadius"));
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"cb-glow-r\">Distance d'allumage du "
                        + "contour (blocs)</label>")
                .append("<input class=\"form-control\" id=\"cb-glow-r\" type=\"number\" name=\"glow_radius\" "
                        + "min=\"8\" max=\"128\" required value=\"")
                .append(Http.esc(glowRadius.isBlank() || "0".equals(glowRadius) ? "48" : glowRadius)).append("\">")
                .append(fieldHelp("48 = la distance où la barre de vie du boss apparaît. Au-delà de 48, le jeu ne "
                        + "montre en général plus le monstre : une valeur plus grande n'aide pas.", "48", "48", null))
                .append("</div>");
        boolean surface = !Boolean.FALSE.equals(d.get("surfaceOnly"));
        sb.append("<div class=\"form-check form-switch\"><input class=\"form-check-input\" type=\"checkbox\" "
                + "role=\"switch\" id=\"cb-surf\" name=\"surface_only\" value=\"true\"")
                .append(surface ? " checked" : "")
                .append("><label class=\"form-check-label\" for=\"cb-surf\">Apparition en surface uniquement</label></div>")
                .append(fieldHelp("Le boss n'apparaît que sur un sol naturel à ciel ouvert (herbe, terre, sable, "
                        + "pierre, neige tassée…), sur un terrain assez plat : jamais dans une grotte, sur un toit, "
                        + "un arbre, de la glace ou de la neige poudreuse, ni dans l'eau, la lave, au bord d'un ravin "
                        + "ou au fond d'un trou. Décoché : la règle d'avant (le plus haut bloc où l'on tient debout).",
                        "activé", "activé", null))
                .append("</div>");
        sb.append("<button class=\"btn btn-primary\" type=\"submit\">").append(Icons.icon("save"))
                .append("Enregistrer</button></form>");
        return sb.toString();
    }

    private String communityBossTestForm(Session session, String agentId, List<Object> profiles,
                                         List<String> onlinePlayers) {
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"/agents/action\" class=\"npc-def-form mt-4\">");
        sb.append(hiddenActionFields(session, agentId, "boss.community.test.spawn"));
        sb.append("<input type=\"hidden\" name=\"confirm\" value=\"true\">");
        sb.append("<div class=\"npc-fs\"><p class=\"npc-fs-h\">Apparition de test</p>");
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"cb-t-prof\">Boss</label>")
                .append("<select class=\"form-select\" id=\"cb-t-prof\" name=\"mob_id\">")
                .append("<option value=\"\">Au hasard (comme le serveur)</option>");
        for (Object o : profiles) {
            Map<String, Object> p = asMap(o);
            if ("COMMUNITY".equals(str(p.get("bossMode"))) && Boolean.TRUE.equals(p.get("enabled"))) {
                sb.append("<option value=\"").append(Http.esc(str(p.get("id")))).append("\">")
                        .append(Http.esc(MiniText.plain(str(p.get("displayName"))))).append("</option>");
            }
        }
        sb.append("</select></div>");
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"cb-t-pl\">Près de</label>")
                .append("<select class=\"form-select\" id=\"cb-t-pl\" name=\"player\">")
                .append("<option value=\"\">N'importe où dans le Wild (comme le serveur)</option>");
        for (String p : onlinePlayers) {
            sb.append("<option value=\"").append(Http.esc(p)).append("\">").append(Http.esc(p)).append("</option>");
        }
        sb.append("</select></div>");
        sb.append("<div class=\"form-text mb-2\">Fait apparaître un boss <strong>tout de suite</strong>, avec la "
                + "vraie annonce (marquée « test »). Ne change pas l'heure du prochain essai automatique et ne compte "
                + "pas dans le maximum. « N'importe où » : comme le serveur, à 40–96 blocs d'un waypoint du Wild, "
                + "même si personne n'y est. Avec un joueur choisi : à 40–96 blocs de lui ; il doit alors être "
                + "connecté et dans le Wild.</div>");
        sb.append("</div><button class=\"btn btn-outline-primary\" type=\"submit\">").append(Icons.icon("target"))
                .append("Apparition de test</button></form>");
        return sb.toString();
    }

    private String communityBossRemoveForm(Session session, String agentId, String target, String label,
                                           String confirmText) {
        return "<form method=\"post\" action=\"/agents/action\" class=\"mt-2\">"
                + hiddenActionFields(session, agentId, "boss.community.remove")
                + "<input type=\"hidden\" name=\"target\" value=\"" + Http.esc(target) + "\">"
                + confirmBox(confirmText)
                + " <button class=\"btn danger sm\" type=\"submit\">" + Http.esc(label) + "</button>"
                + "</form>";
    }

    /** Champs communs des formulaires de la vue « Boss communautaires » (retour sur cette vue). */
    private static String hiddenActionFields(Session session, String agentId, String type) {
        return hiddenActionFields(session, agentId, type, MOBS_COMMUNITY_PATH);
    }

    private static String hiddenActionFields(Session session, String agentId, String type, String returnPath) {
        return "<input type=\"hidden\" name=\"_csrf\" value=\"" + Http.esc(session.csrfToken()) + "\">"
                + "<input type=\"hidden\" name=\"agent\" value=\"" + Http.esc(agentId) + "\">"
                + "<input type=\"hidden\" name=\"type\" value=\"" + Http.esc(type) + "\">"
                + "<input type=\"hidden\" name=\"return\" value=\"" + Http.esc(returnPath) + "\">";
    }

    /** « dans 12 min » / « il y a 3 h » à partir d'un écart en millisecondes (horloge du serveur). */
    static String relative(long deltaMillis) {
        long minutes = Math.round(Math.abs(deltaMillis) / 60_000.0);
        String span = minutes < 1 ? "moins d'une minute" : minutes < 60 ? minutes + " min"
                : minutes < 48 * 60 ? (minutes / 60) + " h " + (minutes % 60 == 0 ? "" : (minutes % 60) + " min")
                : (minutes / (24 * 60)) + " jours";
        return (deltaMillis >= 0 ? "dans " : "il y a ") + span.strip();
    }

    private static String prettyBiomeName(String biomeKey) {
        if (biomeKey == null || biomeKey.isBlank()) {
            return "inconnu";
        }
        int colon = biomeKey.indexOf(':');
        return (colon >= 0 ? biomeKey.substring(colon + 1) : biomeKey).replace('_', ' ');
    }

    private String spawnSettingsForm(Session session, String agentId, Map<String, Object> settings, boolean canWrite) {
        boolean enabled = Boolean.TRUE.equals(settings.get("enabled"));
        String chance = str(settings.get("chance"));
        String max = str(settings.get("maxSimultaneousSpecial"));
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"/agents/action\" class=\"npc-def-form\">");
        sb.append("<input type=\"hidden\" name=\"_csrf\" value=\"").append(Http.esc(session.csrfToken())).append("\">");
        sb.append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"type\" value=\"mob.spawn-settings.set\">");
        sb.append("<input type=\"hidden\" name=\"return\" value=\"").append(MOBS_SPAWNS_PATH).append("\">");
        sb.append("<div class=\"npc-fs\">");
        sb.append("<div class=\"form-check form-switch\"><input class=\"form-check-input\" type=\"checkbox\" role=\"switch\" "
                + "id=\"mob-ss-en\" name=\"enabled\" value=\"true\"").append(enabled ? " checked" : "")
                .append(canWrite ? "" : " disabled").append("><label class=\"form-check-label\" for=\"mob-ss-en\">"
                + "Tirage aléatoire actif</label></div>")
                .append(fieldHelp("Interrupteur général. Décoché, <strong>aucun mob spécial n'apparaît plus "
                        + "naturellement</strong> dans le Wild, quels que soient les profils : rien n'est "
                        + "supprimé, le tirage est simplement suspendu. Les apparitions de test et les boss "
                        + "restent possibles.",
                        null, "activé", null));
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"mob-ss-chance\">Chance globale (0 à 1)</label>")
                .append("<input class=\"form-control\" id=\"mob-ss-chance\" type=\"number\" name=\"chance\" "
                        + "step=\"0.001\" min=\"0\" max=\"1\" value=\"").append(Http.esc(chance))
                .append("\"").append(canWrite ? " required" : " readonly").append(">")
                .append(fieldHelp("Filtre global évalué <strong>une seule fois par apparition naturelle</strong>, "
                        + "avant d'examiner les profils : 0.05 = seules 5 % des apparitions éligibles sont même "
                        + "candidates à une transformation. Baisser cette valeur raréfie tous les mobs spéciaux "
                        + "d'un coup, sans toucher aux profils. De 0 à 1 inclus — 0 revient à tout bloquer.",
                        "0.05", "1 — toute apparition éligible est examinée", null))
                .append("</div>");
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"mob-ss-max\">Plafond simultané (optionnel)</label>")
                .append("<input class=\"form-control\" id=\"mob-ss-max\" type=\"number\" name=\"max_simultaneous_special\" "
                        + "min=\"0\" value=\"").append("null".equals(max) ? "" : Http.esc(max)).append("\"")
                .append(canWrite ? "" : " readonly").append(">")
                .append(fieldHelp("Plafond du nombre total de mobs <strong>Spécial</strong> vivants en même "
                        + "temps, tous profils confondus. Au plafond, le tirage s'arrête jusqu'à ce que certains "
                        + "meurent. Les profils <strong>Boss</strong> ne sont jamais comptés ici. Nombre entier, "
                        + "0 ou plus.",
                        "20", "aucun", "aucun plafond global"))
                .append("</div>");
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
        sb.append(" <span class=\"muted ms-2\">").append(Http.esc(str(m.get("entityType")))).append(" · ")
                .append(Http.esc(mobCountsLabel(m))).append("</span>");
        sb.append("</button></h2>");
        sb.append("<div id=\"").append(collapseId).append("\" class=\"accordion-collapse collapse\" "
                + "aria-labelledby=\"").append(headingId).append("\" data-bs-parent=\"#mob-accordion\">");
        sb.append("<div class=\"accordion-body\">");

        sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Chance d'apparition</span> ")
                .append(boss ? "jamais tiré au hasard (boss)" : Http.esc(str(m.get("spawnChance")))).append("</p>");
        String max = str(m.get("maxPopulation"));
        if (!boss && !max.isBlank() && !"null".equals(max)) {
            sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Plafond naturel</span> ").append(Http.esc(max))
                    .append("</p>");
        }
        List<Object> abilities = asList(m.get("abilitiesSummary"));
        if (!abilities.isEmpty()) {
            sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Capacités</span> ").append(join(abilities)).append("</p>");
        }

        // Issue #169 : une action principale (Modifier), des secondaires discrètes, l'activation à part.
        sb.append("<div class=\"mob-actions\">");
        if (canWrite) {
            sb.append("<button class=\"btn sm\" type=\"button\" data-bs-toggle=\"collapse\" "
                    + "data-bs-target=\"#mob-edit-").append(index).append("\" aria-expanded=\"false\">")
                    .append(Icons.icon("edit")).append("Modifier</button>");
        }
        // Issue #214 : le formulaire n'édite pas tout (drops, capacités avancées) — le fichier complet, si.
        if (!str(m.get("yaml")).isBlank()) {
            sb.append("<a class=\"btn secondary sm\" href=\"/mobs/template?agent=").append(Http.esc(agentId))
                    .append("&amp;profile=").append(Http.esc(id)).append("\">").append(Icons.icon("export"))
                    .append("Exporter</a>");
            if (canWrite) {
                sb.append("<a class=\"btn secondary sm\" href=\"/mobs/import?agent=").append(Http.esc(agentId))
                        .append("&amp;from=").append(Http.esc(id)).append("\">").append(Icons.icon("edit"))
                        .append("Modifier avec une IA</a>");
            }
        }
        if (canWrite) {
            sb.append("<span class=\"mob-actions-sep\"></span>").append(toggleForm(session, agentId, id, enabled));
        }
        sb.append("</div>");
        if (!str(m.get("yaml")).isBlank()) {
            sb.append("<p class=\"field-help\">« Modifier » ne couvre pas le butin ni les capacités avancées : "
                    + "« Modifier avec une IA » les retouche, avec les différences à relire avant import.</p>");
        }
        if (canTest) {
            sb.append(testSpawnForm(session, agentId, id, displayName, onlinePlayers));
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

    /**
     * Issue #169 : chaque compteur dit son périmètre — « en jeu » (repérés depuis le démarrage), dont
     * les instances de test, et pour un boss communautaire l'état enregistré (zones non chargées
     * comprises). Un plugin antérieur ne transmet que le premier : on n'invente pas les autres.
     */
    static String mobCountsLabel(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder(str(m.get("alivePopulation"))).append(" en jeu");
        if (m.get("testPopulation") != null && asLong(m.get("testPopulation")) > 0) {
            sb.append(" (dont ").append(asLong(m.get("testPopulation"))).append(" de test)");
        }
        if (m.get("communityActive") != null && asLong(m.get("communityActive")) > 0) {
            sb.append(" · ").append(asLong(m.get("communityActive"))).append(" boss communautaire actif");
            if (asLong(m.get("communityActive")) > asLong(m.get("alivePopulation"))) {
                sb.append(" (zone non chargée)");
            }
        }
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
                + "<button class=\"btn secondary sm\" type=\"submit\">"
                + (enabled ? "Désactiver" : "Activer") + "</button></form>";
    }

    /** Issue #169 : l'apparition de test nomme le profil choisi et vit dans sa fiche, à part des réglages. */
    private String testSpawnForm(Session session, String agentId, String id, String displayName,
                                 List<String> onlinePlayers) {
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"/agents/action\" class=\"mob-test\">");
        sb.append(hiddenActionFields(session, agentId, "mob.test.spawn", MOBS_PATH));
        sb.append("<input type=\"hidden\" name=\"mob_id\" value=\"").append(Http.esc(id)).append("\">");
        sb.append("<input type=\"hidden\" name=\"confirm\" value=\"true\">");
        String fieldId = "mob-tp-" + Integer.toHexString(id.hashCode());
        sb.append("<p class=\"npc-fs-h\">Tester « ").append(Http.esc(displayName)).append(" »</p>");
        sb.append("<div class=\"mob-test-row\"><label class=\"form-label mb-0\" for=\"").append(fieldId)
                .append("\">Près de</label>");
        if (onlinePlayers.isEmpty()) {
            sb.append("<input type=\"text\" id=\"").append(fieldId).append("\" name=\"player\" "
                    + "class=\"form-control form-control-sm\" placeholder=\"Pseudo exact\" required>");
        } else {
            sb.append("<select id=\"").append(fieldId).append("\" name=\"player\" class=\"form-select form-select-sm\">");
            for (String p : onlinePlayers) {
                sb.append("<option value=\"").append(Http.esc(p)).append("\">").append(Http.esc(p)).append("</option>");
            }
            sb.append("</select>");
        }
        sb.append("<button class=\"btn secondary sm\" type=\"submit\">")
                .append(Icons.icon("target")).append("Faire apparaître une instance de test</button></div>");
        sb.append("<div class=\"form-text\">Une seule instance, à 3–10 blocs du joueur, qui doit être "
                + "<strong>connecté</strong> et <strong>dans le monde Wild</strong> (sinon l'action est refusée avec la "
                + "raison). Elle est marquée « test » : « Nettoyer les instances de test », en bas de page, la "
                + "supprime sans toucher aux mobs naturels.</div>");
        sb.append("</form>");
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
                            + "pattern=\"[a-z0-9._-]{1,64}\" required>")
                    .append(fieldHelp("Identifiant interne du profil, jamais vu par les joueurs. Sert de nom de "
                            + "fichier et de référence dans les commandes. Minuscules, chiffres et « . _ - », "
                            + "64 caractères au plus. <strong>Non modifiable après création.</strong>",
                            "swamp_king", "Obligatoire", null))
                    .append("</div>");
        }
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid).append("-cat\">Catégorie</label>")
                .append("<select class=\"form-select\" id=\"").append(uid).append("-cat\" name=\"category\">")
                .append(option("SPECIAL", str(existing == null ? null : existing.get("category")), "Spécial (tirage aléatoire Wild)"))
                .append(option("BOSS", str(existing == null ? null : existing.get("category")), "Boss (jamais tiré au hasard)"))
                .append("</select>")
                .append(fieldHelp("Décide si le profil entre dans le tirage aléatoire du Wild. "
                        + "<strong>Spécial</strong> : remplace parfois un mob qui apparaît naturellement. "
                        + "<strong>Boss</strong> : jamais tiré au hasard parmi les apparitions naturelles ; selon "
                        + "son « Type de boss », il apparaît sur demande (boss de quête, test) ou selon le calendrier "
                        + "des boss communautaires. Il reçoit en plus une barre de vie visible à 48 blocs et une "
                        + "aura de particules rafraîchie chaque seconde.",
                        "Spécial pour une variante de mob courante, Boss pour une rencontre unique",
                        "Spécial", null))
                .append("</div>");
        // Issue #175 : mode d'un profil Boss. Envoyé seulement pour la catégorie Boss.
        String bossMode = existing == null || str(existing.get("bossMode")).isBlank() ? "QUEST" : str(existing.get("bossMode"));
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid).append("-bm\">Type de boss</label>")
                .append("<select class=\"form-select\" id=\"").append(uid).append("-bm\" name=\"boss_mode\">")
                .append(option("QUEST", bossMode, "Boss de quête (apparaît seulement sur demande)"))
                .append(option("COMMUNITY", bossMode, "Boss communautaire (apparaît tout seul dans le Wild)"))
                .append("</select>")
                .append(fieldHelp("Utilisé seulement si la catégorie est <strong>Boss</strong>. "
                        + "<strong>Boss communautaire</strong> : le serveur le fait apparaître de temps en temps dans "
                        + "le Wild (réglages dans l'onglet « Boss communautaires »), dans les "
                        + "mondes et biomes choisis plus bas. <strong>Boss de quête</strong> : jamais automatique.",
                        "Boss communautaire pour un ogre que tout le serveur chasse", "Boss de quête", null))
                .append("</div>");
        String weight = existing == null || str(existing.get("communityWeight")).isBlank() ? "1" : str(existing.get("communityWeight"));
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid).append("-cw\">Poids "
                + "(boss communautaire)</label>")
                .append("<input class=\"form-control\" id=\"").append(uid).append("-cw\" type=\"number\" "
                        + "name=\"community_weight\" min=\"1\" max=\"1000\" value=\"").append(Http.esc(weight))
                .append("\">")
                .append(fieldHelp("Quand plusieurs boss communautaires sont possibles, un boss de poids 3 sort "
                        + "trois fois plus souvent qu'un boss de poids 1.", "3", "1", null))
                .append("</div>");
        // Issue #176 : récompenses de participation (clés de entitlements.yml), Boss seulement.
        String participationRewards = existing == null ? ""
                : String.join(", ", asList(existing.get("participationRewards")).stream().map(AgentPages::str).toList());
        // Issue #177 : liste recherchable (récompenses du catalogue + tirages « table:<id> ») ; le champ
        // soumis reste la liste séparée par des virgules, éditable telle quelle sans JavaScript.
        sb.append("<div class=\"mb-2 field multisel\" data-multisel=\"participation_rewards\" "
                        + "data-multisel-list=\"dl-boss-rewards\" data-multisel-exact data-multisel-add=\"Ajouter une "
                        + "récompense ou un tirage…\" data-multisel-separator=\",\" data-multisel-empty=\"Aucune "
                        + "récompense de participation (seulement les drops).\">")
                .append("<label class=\"form-label\" for=\"").append(uid).append("-pr\">Récompenses "
                        + "de participation (boss communautaire)</label>")
                .append("<textarea class=\"form-control\" id=\"").append(uid).append("-pr\" "
                        + "name=\"participation_rewards\" maxlength=\"340\" data-multisel-store>")
                .append(Http.esc(participationRewards)).append("</textarea>")
                .append(fieldHelp("Récompenses du catalogue (5 au plus), remises à <strong>chaque</strong> joueur qui "
                        + "a touché ce boss et qui, à sa mort, est vivant, dans le même monde et assez près de lui "
                        + "(distance réglée dans « Boss communautaires », 100 blocs par défaut ; un joueur mort pendant "
                        + "le combat doit le retoucher). Remise sûre : inventaire plein = la récompense attend, jamais "
                        + "deux fois. Vide = aucune récompense (seulement les drops). Une entrée « Tirage : … » "
                        + "fait un <strong>tirage au sort</strong> de cette table, une "
                        + "fois par joueur éligible (résultat individuel, figé) — tables gérées dans "
                        + "<a href=\"/rewards\">Récompenses</a>. Récompenses désactivées sur le serveur : "
                        + "rien n'est attribué et les joueurs en sont avertis.",
                        "boss_ogre, table:butin_ogre", "vide", null))
                .append("</div>");
        sb.append(catalogCombo(uid, "entity_type", "et", "Type d'entité Minecraft",
                str(existing == null ? null : existing.get("entityType")), "dl-mob-entity", true,
                fieldHelp("Créature vanilla servant de base : apparence, comportement et attributs de départ. "
                                + "<strong>Liste réelle du serveur</strong> — taper « zomb » filtre. Doit être une "
                                + "entité <strong>vivante</strong>. Les bases passives (PIG, CHICKEN…) sont "
                                + "autorisées : sans capacité agressive ajoutée, le profil reste "
                                + "<strong>aussi passif que la base vanilla</strong>, rien ne le rend hostile "
                                + "implicitement.",
                        "CREEPER (profil « Creeper Rouge »)", "Obligatoire", null)));
        // Issue #195 : couleur au clic + cases de style + aperçu. MiniMessage reste le format
        // stocké, mais il n'est plus nécessaire d'en écrire pour un usage courant.
        sb.append(StyleField.render("display_name", uid + "-name", "Nom affiché",
                str(existing == null ? null : existing.get("displayName")), true,
                "Nom affiché au-dessus du mob, toujours visible en jeu. Choisir une couleur et des "
                + "styles ci-dessus : aucun code à écrire. Un nom déjà écrit avec <strong>plusieurs "
                + "styles</strong> est conservé tel quel et n'est jamais simplifié sans action explicite. "
                + "128 caractères au plus, sur une seule ligne."
                + "<span class=\"fmeta\"><span><b>Exemple</b> Roi des Marais</span>"
                + "<span><b>Défaut</b> Obligatoire</span></span>"));
        sb.append("<div class=\"form-check form-switch\"><input class=\"form-check-input\" type=\"checkbox\" role=\"switch\" "
                + "id=\"").append(uid).append("-en\" name=\"enabled\" value=\"true\"")
                .append(!update || Boolean.TRUE.equals(existing.get("enabled")) ? " checked" : "")
                .append("><label class=\"form-check-label\" for=\"").append(uid).append("-en\">Profil actif</label></div>")
                .append(fieldHelp("Décoché, le profil est <strong>retiré du tirage aléatoire du Wild</strong> "
                        + "mais jamais supprimé : il reste modifiable et le bouton d'apparition de test "
                        + "continue de fonctionner. Un profil BOSS n'étant de toute façon jamais tiré au "
                        + "hasard, cette case ne change rien pour lui.",
                        null, "coché à la création", null));
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
                .append(Http.esc(str(existing == null ? "0.01" : existing.get("spawnChance")))).append("\" required>")
                .append(fieldHelp("Probabilité propre à ce profil, évaluée <strong>après</strong> la chance "
                        + "globale ci-dessus : 0.02 = 2 % des spawns retenus par le tirage global donnent ce "
                        + "mob. Si plusieurs profils réussissent en même temps, un tirage pondéré par cette "
                        + "valeur en choisit un. De 0 à 1 inclus. <strong>Ignorée pour un profil BOSS</strong>, "
                        + "jamais tiré au hasard.",
                        "0.02 (profil « Creeper Rouge »)",
                        "aucun côté moteur — obligatoire ; le panel propose 0.01 à la création", null))
                .append("</div>");
        sb.append(catalogMulti(uid, "worlds", "Mondes autorisés",
                existing == null ? null : existing.get("worlds"), "dl-mob-world",
                fieldHelp("Mondes où ce profil peut apparaître au tirage. Préférer cocher explicitement le "
                                + "Wild : ajouter le Hub ou un monde de claims doit rester un choix délibéré "
                                + "(leurs propres règles continuent de s'appliquer et peuvent annuler "
                                + "l'apparition). Liste réelle du serveur.",
                        "wild", "aucune restriction",
                        "tous les mondes où le tirage s'applique")));
        sb.append(catalogMulti(uid, "biomes", "Biomes autorisés",
                existing == null ? null : existing.get("biomes"), "dl-mob-biome",
                fieldHelp("Biomes où ce profil peut apparaître, comparés par nom au moment de l'apparition. "
                                + "Liste réelle du serveur.",
                        "swamp", "aucune restriction", "tous les biomes sont autorisés")));
        sb.append(csvField(uid, "zones", "Zones autorisées", existing,
                fieldHelp("Zones RPGQuest autorisées, par identifiant, séparées par des virgules. Une zone "
                                + "est un volume nommé défini par un administrateur avec <code>/rpgadmin zone</code> "
                                + "— à ne pas confondre avec un monde ni un biome. Les identifiants ne sont "
                                + "vérifiés qu'au moment de l'apparition : une zone supprimée depuis ne bloque pas "
                                + "l'enregistrement du profil.",
                        "marais_maudit", "aucune restriction",
                        "aucune restriction de zone")));
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid).append("-mp\">Population maximale simultanée</label>")
                .append("<input class=\"form-control\" id=\"").append(uid).append("-mp\" type=\"number\" min=\"0\" "
                        + "name=\"max_population\" value=\"").append(Http.esc(str(existing == null ? null : existing.get("maxPopulation"))))
                .append("\">")
                .append(fieldHelp("Nombre maximum de mobs issus de <strong>ce profil</strong> vivants en même "
                        + "temps. Au plafond, le tirage ignore ce profil jusqu'à ce que l'un d'eux meure. "
                        + "Nombre entier, 0 ou plus. Ce plafond s'ajoute au plafond global de la section "
                        + "« Tirage aléatoire » de la page.",
                        "8 (profil « Creeper Rouge »)", "aucun",
                        "aucun plafond propre à ce profil"))
                .append("</div>");
        sb.append("</div>");

        sb.append("<div class=\"npc-fs\"><p class=\"npc-fs-h\">Statistiques</p>");
        sb.append("<p class=\"form-text\">Chaque statistique laissée vide n'est <strong>pas touchée</strong> : "
                + "l'attribut vanilla du type d'entité choisi s'applique tel quel. Un champ rempli remplace "
                + "l'attribut de base au moment de l'apparition.</p>");
        sb.append(numField(uid, "health", "Vie max (points de vie)", existing, "0.01",
                fieldHelp("Points de vie maximum. La vie courante est fixée à ce maximum à l'apparition, "
                                + "donc le mob apparaît toujours en pleine vie. 2 points = 1 cœur.",
                        "40 (20 cœurs)", "aucun — la vie vanilla du type choisi",
                        "attribut de vie inchangé")));
        sb.append(numField(uid, "damage", "Dégâts d'attaque (points de vie)", existing, "0.01",
                fieldHelp("Dégâts infligés par une attaque au corps à corps. Sans effet sur un type qui "
                                + "n'attaque pas au contact (un squelette tire des flèches).",
                        "7", "aucun — les dégâts vanilla du type choisi",
                        "attribut de dégâts inchangé")));
        sb.append(numField(uid, "speed", "Vitesse de déplacement", existing, "0.01",
                fieldHelp("Attribut vanilla de vitesse de déplacement. À titre de repère, les profils fournis "
                                + "avec le plugin vont de 0,25 (Zombie Fissible) à 1,1 (Cochon Creeper).",
                        "1.0", "aucun — la vitesse vanilla du type choisi",
                        "attribut de vitesse inchangé")));
        sb.append(numField(uid, "armor", "Armure (points d'armure)", existing, "0.01",
                fieldHelp("Réduit les dégâts reçus, comme une armure portée. 20 correspond à une protection "
                                + "très élevée.",
                        "8", "aucun — l'armure vanilla du type choisi",
                        "attribut d'armure inchangé")));
        sb.append(numField(uid, "knockback_resistance", "Résistance au recul", existing, "0.01",
                fieldHelp("Part du recul annulée quand le mob est frappé. <strong>0 = projeté normalement, "
                                + "1 = totalement immobile face aux coups.</strong> Valeurs acceptées : de 0 à 1 "
                                + "inclus.",
                        "0.5", "aucun — la résistance vanilla du type choisi",
                        "attribut de recul inchangé")));
        sb.append(numField(uid, "scale", "Taille relative", existing, "0.01",
                fieldHelp("Multiplie la taille du modèle et sa boîte de collision. <strong>1 = taille "
                                + "normale</strong>, 2 = deux fois plus grand. Doit rester strictement supérieur à 0.",
                        "1.5", "aucun — la taille normale du type choisi",
                        "taille vanilla inchangée")));
        sb.append(numField(uid, "creeper_explosion_radius", "Rayon d'explosion (CREEPER uniquement)", existing, "0.1",
                fieldHelp("Portée de l'explosion, en blocs. <strong>Réservé au type CREEPER</strong> : sur tout "
                                + "autre type, le profil est refusé au chargement plutôt que l'option ignorée en "
                                + "silence. Le moteur n'en retient que la <strong>partie entière</strong> (4,7 → 4).",
                        "6", "aucun — le rayon d'explosion vanilla du creeper",
                        "rayon d'explosion vanilla")));
        sb.append(catalogCombo(uid, "particle", "pt", "Particule (optionnel)",
                str(existing == null ? null : existing.get("particle")), "dl-mob-particle", false,
                fieldHelp("Bouffée de particules émise <strong>une seule fois, à l'apparition</strong> du mob "
                                + "(10 particules au-dessus de lui) — c'est un effet d'entrée, pas une aura "
                                + "permanente. L'aura continue d'un BOSS est un effet fixe du moteur, "
                                + "indépendant de ce champ. Liste réelle du serveur : taper « flam » filtre.",
                        "FLAME (profil « Creeper Rouge »)", "aucune", "aucune particule à l'apparition")));
        sb.append(catalogCombo(uid, "sound", "sd", "Son (optionnel)",
                str(existing == null ? null : existing.get("sound")), "dl-mob-sound", false,
                fieldHelp("Son joué <strong>une seule fois, à l'apparition</strong>, à la position du mob et "
                                + "audible par tous les joueurs à portée. Liste réelle du serveur.",
                        "ENTITY_CREEPER_PRIMED (profil « Creeper Rouge »)", "aucun",
                        "aucun son à l'apparition")));
        sb.append(numField(uid, "xp_reward", "XP lâchée à la mort", existing, "1",
                fieldHelp("Points d'expérience lâchés quand le mob meurt, en remplacement de ceux du type "
                                + "vanilla. Nombre entier, 0 ou plus : <strong>0 signifie explicitement aucune "
                                + "XP</strong>, ce qui n'est pas la même chose que laisser le champ vide.",
                        "10 (profil « Creeper Rouge »)", "aucun — l'XP vanilla du type choisi",
                        "l'XP vanilla est lâchée, sans modification")));
        sb.append("</div>");

        // #190 : repliées par défaut (<details> natif, sans JS) pour raccourcir le formulaire par
        // défaut -- ouvertes automatiquement en modification si la capacité est déjà active.
        boolean hasEnraged = existing != null && existing.get("enragedHealthFraction") != null;
        sb.append("<details").append(hasEnraged ? " open" : "").append("><summary class=\"npc-fs-h\">Capacité : Enragé</summary>");
        sb.append("<div class=\"npc-fs\">");
        sb.append("<div class=\"form-check\"><input class=\"form-check-input\" type=\"checkbox\" id=\"").append(uid)
                .append("-rg-en\" name=\"enraged_enabled\" value=\"true\"").append(hasEnraged ? " checked" : "")
                .append("><label class=\"form-check-label\" for=\"").append(uid).append("-rg-en\">Activer cette capacité</label></div>");
        sb.append("<p class=\"form-text\">Sous un seuil de vie, le mob passe en rage : vitesse et dégâts "
                + "multipliés, une seule fois au franchissement du seuil (jamais cumulé coup après coup). "
                + "<strong>Les trois réglages ci-dessous sont obligatoires dès que la case est cochée</strong> — "
                + "le moteur n'a aucune valeur de repli, l'enregistrement est refusé s'il en manque un.</p>");
        sb.append(numField(uid, "enraged_health_fraction", "Seuil de vie déclencheur", existing, "0.01",
                fieldHelp("Fraction de la vie maximum sous laquelle la rage se déclenche : 0.3 = sous 30 % de "
                                + "vie. <strong>Strictement entre 0 et 1</strong> — ni 0 ni 1 ne sont acceptés.",
                        "0.3", "Obligatoire si la capacité est activée", null)));
        sb.append(numField(uid, "enraged_speed_multiplier", "Multiplicateur de vitesse en rage", existing, "0.1",
                fieldHelp("Multiplie la vitesse une fois enragé. 1 = vitesse inchangée, 1.5 = moitié plus "
                                + "rapide. Doit être strictement supérieur à 0.",
                        "1.5", "Obligatoire si la capacité est activée", null)));
        sb.append(numField(uid, "enraged_damage_multiplier", "Multiplicateur de dégâts en rage", existing, "0.1",
                fieldHelp("Multiplie les dégâts une fois enragé. 1 = dégâts inchangés, 2 = dégâts doublés. "
                                + "Doit être strictement supérieur à 0.",
                        "2", "Obligatoire si la capacité est activée", null)));
        sb.append("</div></details>");

        boolean hasSummon = existing != null && existing.get("summonEntityType") != null;
        sb.append("<details").append(hasSummon ? " open" : "")
                .append("><summary class=\"npc-fs-h\">Capacité : Invocation de renforts</summary>");
        sb.append("<div class=\"npc-fs\">");
        sb.append("<div class=\"form-check\"><input class=\"form-check-input\" type=\"checkbox\" id=\"").append(uid)
                .append("-sm-en\" name=\"summon_enabled\" value=\"true\"").append(hasSummon ? " checked" : "")
                .append("><label class=\"form-check-label\" for=\"").append(uid).append("-sm-en\">Activer cette capacité</label></div>");
        sb.append("<p class=\"form-text\">Quand le mob subit des dégâts <strong>effectifs</strong> (coup non "
                + "annulé, dégâts réellement supérieurs à 0), il peut invoquer des renforts. Jamais de "
                + "cascade : les renforts eux-mêmes n'invoquent rien. <strong>Les cinq réglages ci-dessous "
                + "sont obligatoires dès que la case est cochée</strong> — le moteur n'a aucune valeur de "
                + "repli, l'enregistrement est refusé s'il en manque un.</p>");
        String summonType = str(existing == null ? null : existing.get("summonEntityType"));
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid).append("-sm-t\">Type de renfort</label>")
                .append("<input class=\"form-control\" id=\"").append(uid).append("-sm-t\" type=\"text\" name=\"summon_entity_type\" "
                        + "value=\"").append(Http.esc("null".equals(summonType) ? "" : summonType))
                .append("\" list=\"dl-mob-entity\" autocomplete=\"off\">")
                .append(fieldHelp("Créature invoquée en renfort. Doit être une entité <strong>vivante</strong> "
                        + "(un projectile ou un wagonnet est refusé). Même liste que le type d'entité du profil.",
                        "ZOMBIE", "Obligatoire si la capacité est activée", null))
                .append("</div>");
        sb.append(numField(uid, "summon_amount", "Nombre de renforts par déclenchement", existing, "1",
                fieldHelp("Combien de renforts apparaissent à chaque invocation réussie. Nombre entier "
                                + "strictement supérieur à 0.",
                        "2", "Obligatoire si la capacité est activée", null)));
        sb.append(numField(uid, "summon_chance", "Probabilité par coup reçu", existing, "0.01",
                fieldHelp("Chance de déclencher l'invocation à chaque coup effectivement reçu : 0.25 = une "
                                + "fois sur quatre en moyenne. <strong>Supérieur à 0 et au plus 1</strong> — 0 est "
                                + "refusé (une capacité qui ne se déclenche jamais), 1 signifie à chaque coup.",
                        "0.25", "Obligatoire si la capacité est activée", null)));
        sb.append(numField(uid, "summon_cooldown_seconds", "Délai minimum entre deux invocations", existing, "1",
                fieldHelp("Temps d'attente en <strong>secondes</strong> avant qu'une nouvelle invocation soit "
                                + "possible, même si le tirage réussit. Nombre entier, 0 ou plus : "
                                + "<strong>0 = aucun délai</strong>, chaque coup peut invoquer.",
                        "10", "Obligatoire si la capacité est activée", null)));
        sb.append(numField(uid, "summon_max_alive", "Renforts vivants simultanés", existing, "1",
                fieldHelp("Plafond de renforts encore en vie <strong>pour ce mob précis</strong> : au plafond, "
                                + "plus aucune invocation jusqu'à ce que certains meurent. Nombre entier "
                                + "strictement supérieur à 0.",
                        "4", "Obligatoire si la capacité est activée", null)));
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
                + "\" placeholder=\"séparés par des virgules\">" + help + "</div>";
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
                    joined.append(", "); // même séparateur que data-multisel-separator (issue #461)
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

    /**
     * Issue #177 : récompenses du catalogue et tables de tirage ({@code table:<id>}) pour le champ
     * « Récompenses de participation », depuis le dernier relevé {@code reward.catalog.status}.
     */
    private String bossRewardDatalist(String agentId) {
        Map<String, Object> d = latestDetails(agentId, "reward.catalog.status").orElse(Map.of());
        StringBuilder sb = new StringBuilder("<datalist id=\"dl-boss-rewards\">");
        for (Object o : asList(d.get("rewards"))) {
            Map<String, Object> r = asMap(o);
            sb.append("<option value=\"").append(Http.esc(str(r.get("key")))).append("\" label=\"")
                    .append(Http.esc(str(r.get("label")))).append("\">");
        }
        for (Object o : asList(d.get("tables"))) {
            Map<String, Object> t = asMap(o);
            sb.append("<option value=\"table:").append(Http.esc(str(t.get("id")))).append("\" label=\"Tirage : ")
                    .append(Http.esc(str(t.get("label")) + (Boolean.TRUE.equals(t.get("enabled")) ? ""
                            : com.lodygames.rpgquest.panel.content.RefData.DISABLED_TABLE_SUFFIX)))
                    .append("\">");
        }
        return sb.append("</datalist>").toString();
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

    /**
     * Ce que le dernier rechargement de publication a dit des dépendances (issue #371) : les
     * références fautives exactes, les familles qu'il faut encore publier, celles rechargées d'office
     * parce que liées, et les références déjà cassées ailleurs (non bloquantes).
     */
    private String publishReloadDiagnostics(Optional<Map<String, Object>> lastPublish) {
        List<String> errors = stringList(lastPublish, "referenceErrors");
        List<String> required = stringList(lastPublish, "requiredFamilies");
        List<String> auto = stringList(lastPublish, "autoIncludedFamilies");
        List<String> preexisting = stringList(lastPublish, "preexistingReferenceErrors");
        StringBuilder sb = new StringBuilder();
        if (!errors.isEmpty()) {
            sb.append("<div class=\"field-help\"><strong>Références introuvables</strong> — rien n'a été "
                    + "rechargé :<ul>");
            errors.forEach(e -> sb.append("<li>").append(Http.esc(e)).append("</li>"));
            sb.append("</ul>");
            sb.append(required.isEmpty()
                    ? "Créez ou publiez d'abord la ressource manquante, puis republiez."
                    : "Publiez aussi : " + Http.esc(familyLabels(required)) + ", puis republiez.");
            sb.append("</div>");
        }
        if (!auto.isEmpty()) {
            sb.append("<p class=\"field-help\">Rechargé aussi, car lié : ")
                    .append(Http.esc(familyLabels(auto))).append(".</p>");
        }
        if (!preexisting.isEmpty()) {
            sb.append("<details class=\"field-help\"><summary>").append(preexisting.size())
                    .append(" référence(s) déjà cassée(s) ailleurs (non bloquant)</summary><ul>");
            preexisting.forEach(e -> sb.append("<li>").append(Http.esc(e)).append("</li>"));
            sb.append("</ul></details>");
        }
        return sb.toString();
    }

    private static String familyLabels(List<String> wires) {
        return String.join(", ", wires.stream().map(w -> switch (w) {
            case "items" -> "Objets";
            case "npcs" -> "PNJ";
            case "quests" -> "Quêtes";
            case "stories" -> "Stories";
            case "dialogues" -> "Dialogues";
            case "mobs" -> "Mobs spéciaux & boss";
            default -> w;
        }).toList());
    }

    private List<String> stringList(Optional<Map<String, Object>> details, String key) {
        return details.map(d -> asList(d.get(key)).stream().map(AgentPages::str)
                .filter(x -> !x.isEmpty()).toList()).orElse(List.of());
    }

    /**
     * Champ numérique d'un profil de mob. {@code help} est le bloc d'aide rendu <em>sous</em> le
     * champ (voir {@link #fieldHelp}) : jamais un placeholder ni une infobulle, qui sont invisibles
     * sur mobile et disparaissent dès la saisie.
     *
     * <p>À la modification, la valeur vient du profil existant ; à la création, le champ reste
     * <strong>vide</strong> quand le moteur n'a pas de défaut — un préremplissage inventé serait
     * enregistré tel quel, puisque {@code mob.definition.update} remplace le profil entier.</p>
     */
    private String numField(String uid, String name, String label, Map<String, Object> existing, String step,
                            String help) {
        String value = existing == null ? "" : str(existing.get(toCamel(name)));
        if ("null".equals(value)) {
            value = "";
        }
        return "<div class=\"mb-2\"><label class=\"form-label\" for=\"" + uid + "-" + name + "\">" + Http.esc(label) + "</label>"
                + "<input class=\"form-control\" id=\"" + uid + "-" + name + "\" type=\"number\" step=\"" + step + "\" "
                + "name=\"" + name + "\" value=\"" + Http.esc(value) + "\">"
                + (help == null ? "" : help) + "</div>";
    }

    /**
     * Bloc d'aide d'un champ de profil de mob : une phrase sur ce que le champ contrôle en jeu,
     * puis des méta-données courtes et étiquetées — exemple, défaut réellement appliqué par le
     * moteur, et comportement à vide pour un champ optionnel.
     *
     * <p>Les quatre parties sont séparées visuellement et se replient l'une sous l'autre sur
     * téléphone (voir {@code .fmeta} dans la feuille de style). Toute valeur annoncée ici est
     * <strong>relevée dans le code du plugin</strong> : quand il n'existe pas de défaut, on écrit
     * « Obligatoire » ou « Aucun » plutôt que d'en inventer un.</p>
     *
     * @param what    ce que le champ contrôle et son effet en jeu (HTML déjà sûr)
     * @param example exemple de valeur, distinct de ce qui est enregistré ; {@code null} = aucun
     * @param def     défaut appliqué par le moteur, ou « Obligatoire » / « Aucun »
     * @param empty   comportement quand le champ est laissé vide ; {@code null} pour un champ obligatoire
     */
    private static String fieldHelp(String what, String example, String def, String empty) {
        StringBuilder sb = new StringBuilder("<div class=\"form-text\">");
        sb.append(what);
        sb.append("<span class=\"fmeta\">");
        if (example != null) {
            sb.append("<span><b>Exemple</b> ").append(example).append("</span>");
        }
        sb.append("<span><b>Défaut</b> ").append(def).append("</span>");
        if (empty != null) {
            sb.append("<span><b>Si vide</b> ").append(empty).append("</span>");
        }
        return sb.append("</span></div>").toString();
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
