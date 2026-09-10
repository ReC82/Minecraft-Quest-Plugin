package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import com.lodygames.rpgquest.panel.agent.AgentIdentity;
import com.lodygames.rpgquest.panel.agent.AgentRegistry;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.authz.Permission;
import com.lodygames.rpgquest.panel.authz.PermissionService;
import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.json.Json;
import com.lodygames.rpgquest.panel.security.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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

    public AgentPages(AgentStore store, AgentRegistry registry, String defaultAgentId, PermissionService perms) {
        this.store = store;
        this.registry = registry;
        this.defaultAgentId = defaultAgentId;
        this.perms = perms;
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
        boolean canModerate = perms.can(session.role(), Permission.PLAYER_MODERATE);
        boolean canVarGet = perms.can(session.role(), Permission.ACTION_VARIABLE_GET);
        boolean canVarSet = perms.can(session.role(), Permission.ACTION_VARIABLE_SET);
        boolean canGive = perms.can(session.role(), Permission.ACTION_ITEM_GIVE);
        boolean canReset = perms.can(session.role(), Permission.ACTION_PLAYER_RESET);
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
                    canModerate, canVarGet, canVarSet, canGive, canReset));
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
                                             boolean canVarGet, boolean canVarSet, boolean canGive, boolean canReset) {
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
        for (String type : new String[] {"player.ban", "player.unban", "player.resetnew.confirm"}) {
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
        boolean canEditQuests = perms.can(session.role(), Permission.QUEST_CONTENT_WRITE);
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
        if (perms.can(session.role(), Permission.NPC_READ)) {
            questBar += compactRefresh(session, agentId, "npc.list", "PNJ", "btn-outline-secondary", "/quests");
        }
        sb.append(listCatbar("Catalogue", questBar));
        List<Object> catalog = latestDetails(agentId, "quest.list").map(d -> asList(d.get("quests"))).orElse(List.of());
        Map<String, String> questTitles = titleIndex(catalog, "id", "title");
        List<String> questIdList = catalog.stream().map(o -> str(asMap(o).get("id"))).toList();
        java.util.Set<String> knownQuestKeys = idKeySet(questIdList);
        java.util.Set<String> knownNpcKeys = npcKeySet(agentId);
        if (catalog.isEmpty()) {
            sb.append(Ui.empty("Aucun catalogue chargé — cliquer sur « Rafraîchir le catalogue »."));
        } else {
            sb.append(listControls("quests", "Rechercher une quête\u2026",
                    filterBtn("", "Toutes", true) + filterBtn("ok", "Sans alerte", false)
                            + filterBtn("warn", "À vérifier", false)));
            sb.append("<p class=\"count-note\" data-count-note data-noun=\"qu\u00eate\">" + catalog.size() + " qu\u00eate(s)</p>");
            sb.append("<div class=\"accordion npc-accordion\" id=\"quests-accordion\">");
            int qi = 0;
            for (Object o : catalog) {
                sb.append(renderQuestAccordionItem(asMap(o), qi++, questTitles, knownQuestKeys, knownNpcKeys, canEditQuests));
            }
            sb.append("</div>");
        }

        List<String> questIds = catalog.stream().map(o -> str(asMap(o).get("id"))).toList();
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
                                            boolean canEdit) {
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

        String ftext = Http.esc(id + " " + human + " " + category + " " + giverId + " " + giverName);
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
        sb.append(anyWarn ? "<span class=\"badge text-bg-warning\">à vérifier</span>"
                : "<span class=\"badge text-bg-success\">OK</span>");
        sb.append("</span></button></h3>");

        sb.append("<div id=\"").append(slug).append("\" class=\"accordion-collapse collapse\" ")
                .append("data-bs-parent=\"#quests-accordion\"><div class=\"accordion-body npc-detail\">");

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
        if (canEdit) {
            String eslug = editSlug(id);
            if (!eslug.isEmpty()) {
                sb.append(detailSection("target", "Actions"));
                sb.append("<div class=\"npc-actions d-flex flex-wrap gap-2\">");
                sb.append("<a class=\"btn btn-sm btn-outline-primary\" href=\"/quests/edit/").append(Http.esc(eslug))
                        .append("\">").append(Icons.icon("edit")).append("Modifier la quête</a>");
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
        boolean canEditStories = perms.can(session.role(), Permission.STORY_CONTENT_WRITE);
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
        List<Object> catalog = latestDetails(agentId, "story.list").map(d -> asList(d.get("stories"))).orElse(List.of());
        // Titres humains des quêtes composant les stories, si un quest.list a déjà été chargé (données
        // locales du panel — aucun appel agent supplémentaire).
        List<String> knownQuestIds = latestDetails(agentId, "quest.list").map(d -> asList(d.get("quests"))).orElse(List.of())
                .stream().map(o -> str(asMap(o).get("id"))).toList();
        Map<String, String> questTitles = titleIndex(
                latestDetails(agentId, "quest.list").map(d -> asList(d.get("quests"))).orElse(List.of()), "id", "title");
        java.util.Set<String> storyQuestKeys = knownQuestIds.isEmpty() ? null : idKeySet(knownQuestIds);
        if (catalog.isEmpty()) {
            sb.append(Ui.empty("Aucun catalogue chargé — cliquer sur « Stories »."));
        } else {
            sb.append(listControls("stories", "Rechercher une story\u2026",
                    filterBtn("", "Toutes", true) + filterBtn("ok", "Sans alerte", false)
                            + filterBtn("warn", "À vérifier", false)));
            sb.append("<p class=\"count-note\" data-count-note data-noun=\"story\">" + catalog.size() + " story(s)</p>");
            sb.append("<div class=\"accordion npc-accordion\" id=\"stories-accordion\">");
            int si = 0;
            for (Object o : catalog) {
                sb.append(renderStoryAccordionItem(asMap(o), si++, questTitles, storyQuestKeys, canEditStories));
            }
            sb.append("</div>");
        }

        List<String> storyIds = catalog.stream().map(o -> str(asMap(o).get("id"))).toList();
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
                                            java.util.Set<String> storyQuestKeys, boolean canEdit) {
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

        String ftext = Http.esc(id + " " + human);
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
        sb.append(anyWarn ? "<span class=\"badge text-bg-warning\">à vérifier</span>"
                : "<span class=\"badge text-bg-success\">OK</span>");
        sb.append("</span></button></h3>");

        sb.append("<div id=\"").append(slug).append("\" class=\"accordion-collapse collapse\" ")
                .append("data-bs-parent=\"#stories-accordion\"><div class=\"accordion-body npc-detail\">");

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
        if (canEdit) {
            String eslug = editSlug(id);
            if (!eslug.isEmpty()) {
                sb.append(detailSection("target", "Actions"));
                sb.append("<div class=\"npc-actions d-flex flex-wrap gap-2\">");
                sb.append("<a class=\"btn btn-sm btn-outline-primary\" href=\"/stories/edit/").append(Http.esc(eslug))
                        .append("\">").append(Icons.icon("edit")).append("Modifier la story</a>");
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
        boolean canWrite = perms.can(session.role(), Permission.NPC_WRITE);
        boolean canSetGiver = perms.can(session.role(), Permission.QUEST_GIVER_WRITE);
        boolean canLink = perms.can(session.role(), Permission.NPC_BIND_WRITE);
        boolean canSpawn = perms.can(session.role(), Permission.NPC_SPAWN_WRITE);
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
            sb.append(renderNpcAccordionItem(session, agentId, asMap(o), i++, questTitles, questIds,
                    dialogueOptions, citizensRoster, spawnWorlds, canWrite, canSetGiver, canLink, canSpawn));
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

    /** Options du select Dialogue : [id namespacé, libellé lisible], depuis le dernier {@code dialogue.list}. */
    private List<String[]> dialogueSelectOptions(String agentId) {
        List<String[]> out = new ArrayList<>();
        for (Object o : latestDetails(agentId, "dialogue.list").map(x -> asList(x.get("dialogues"))).orElse(List.of())) {
            Map<String, Object> dg = asMap(o);
            String id = str(dg.get("id"));
            if (id.isEmpty()) {
                continue;
            }
            String key = str(dg.get("key"));
            out.add(new String[] {id, MiniText.prettifyId(key.isEmpty() ? id : key)});
        }
        return out;
    }

    // ================================================================================
    //  PNJ — une ligne d'accordion : synthèse (bouton) + détail structuré (collapse)
    // ================================================================================

    private String renderNpcAccordionItem(Session session, String agentId, Map<String, Object> n, int idx,
                                          Map<String, String> questTitles, List<String> questIds,
                                          List<String[]> dialogueOptions, List<Object> citizensRoster,
                                          List<String> spawnWorlds, boolean canWrite, boolean canSetGiver,
                                          boolean canLink, boolean canSpawn) {
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
                    .append(" <span class=\"faint\">(pas encore chargé en jeu)</span></div></div></div>");
        } else {
            sb.append("<p class=\"muted npc-content-empty\">Aucun dialogue.</p>");
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
                    + giverForm(session, agentId, id, questIds) + "</div>"));
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

    /** Formulaire « Attribuer une quête (giver) » — select des quêtes connues. */
    private String giverForm(Session session, String agentId, String npcId, List<String> questIds) {
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">").append(Icons.icon("gift"))
                .append("Attribuer une quête</p>");
        if (questIds.isEmpty()) {
            return sb.append(Ui.empty("Charger d'abord le catalogue de quêtes (page Quêtes).")).toString();
        }
        sb.append(formStart(session, agentId, "quest.giver.set", "/npcs", ""));
        sb.append("<input type=\"hidden\" name=\"npc_id\" value=\"").append(Http.esc(npcId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"npc_ctx\" value=\"").append(Http.esc(npcId)).append("\">");
        sb.append("<label>Quête</label>").append(idSelect("quest_id", questIds, "rpgquest:woodcutters_request"));
        sb.append(mutationConsent("quest.giver.set", "",
                "La quête choisie sera donnée par « " + npcId + " ». Réversible en la réattribuant à un autre PNJ."));
        sb.append("<button class=\"btn\" type=\"submit\">Attribuer</button></form>");
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
        sb.append(Ui.pageHeader("dialogues", "Dialogues",
                "Lecture structurée des dialogues à embranchements (dialogues/<id>.yml) : nœuds, "
                        + "choix, actions et conditions typées, relations PNJ / quêtes, diagnostics.",
                docLink("dialogues", "Documentation : dialogues")));
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        boolean canWrite = perms.can(session.role(), Permission.DIALOGUE_WRITE);
        sb.append(agentPicker(agentId, "/dialogues", ""));

        sb.append("<h2>Catalogue</h2>");
        String dlgBar = compactRefresh(session, agentId, "dialogue.list", "Dialogues", "btn-outline-primary", "/dialogues");
        if (perms.can(session.role(), Permission.CONTENT_READ)) {
            dlgBar += compactRefresh(session, agentId, "quest.list", "Quêtes", "btn-outline-secondary", "/dialogues");
        }
        sb.append(listCatbar("Catalogue", dlgBar));

        Map<String, String> questTitles = titleIndex(
                latestDetails(agentId, "quest.list").map(x -> asList(x.get("quests"))).orElse(List.of()), "id", "title");

        if (canWrite) {
            sb.append(dialogueCreateBlock(session, agentId));
        }

        Optional<Map<String, Object>> details = latestDetails(agentId, "dialogue.list");
        if (details.isEmpty()) {
            sb.append(Ui.empty("Aucun catalogue chargé — cliquer sur « Dialogues »."));
            return sb.toString();
        }
        Map<String, Object> d = details.get();
        List<Object> dialogues = asList(d.get("dialogues"));
        List<Object> loadIssues = asList(d.get("loadIssues"));
        List<Object> missing = asList(d.get("declaredButMissing"));

        if (!loadIssues.isEmpty() || !missing.isEmpty()) {
            sb.append("<div class=\"dlg-global-diag\">");
            for (Object o : loadIssues) {
                Map<String, Object> m = asMap(o);
                sb.append(DiagnosticHelp.render("DIALOGUE_LOAD_ISSUE", "error", str(m.get("message")),
                        str(m.get("file")), str(m.get("message")), ""));
            }
            for (Object o : missing) {
                Map<String, Object> m = asMap(o);
                sb.append(DiagnosticHelp.render("DIALOGUE_DECLARED_MISSING", "error", "",
                        str(m.get("npcId")), str(m.get("dialogueId")), ""));
            }
            sb.append("</div>");
        }

        sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Résumé</span> ")
                .append(Http.esc(str(d.get("total")))).append(" dialogue(s) · ")
                .append(Http.esc(str(d.get("nodeTotal")))).append(" nœud(s) · ")
                .append(Http.esc(str(d.get("withWarnings")))).append(" avec avertissement · ")
                .append(loadIssues.size()).append(" fichier(s) rejeté(s)</p>");

        if (canWrite) {
            sb.append("<p class=\"form-text dlg-editnote\">Édition guidée disponible pour les nœuds, les textes et "
                    + "les choix simples. Les opérations avancées (conditions, actions de quête) restent limitées. ")
                    .append(docLink("dialogues", "En savoir plus sur les limites de l'éditeur")).append("</p>");
            sb.append("<details class=\"tech-detail\"><summary>Détails techniques de l'éditeur</summary>"
                    + "<p class=\"muted\">Chaque écriture réécrit le fichier <code>dialogues/&lt;id&gt;.yml</code> au "
                    + "<strong>format canonique</strong> du panel (les commentaires et la mise en forme d'origine ne "
                    + "sont pas conservés), puis le re-parse et le recharge. En cas d'échec, le contenu d'origine est "
                    + "restauré.</p></details>");
        }

        if (dialogues.isEmpty()) {
            sb.append(Ui.empty("dialogues", "Aucun dialogue chargé."));
        } else {
            sb.append(listControls("dialogues", "Rechercher un dialogue\u2026",
                    filterBtn("", "Tous", true) + filterBtn("linked", "Liés", false)
                            + filterBtn("unlinked", "Non liés", false) + filterBtn("warn", "À vérifier", false)));
            sb.append("<p class=\"count-note\" data-count-note data-noun=\"dialogue\">" + dialogues.size() + " dialogue(s)</p>");
            sb.append("<div class=\"accordion npc-accordion\" id=\"dialogues-accordion\">");
            int di = 0;
            for (Object o : dialogues) {
                sb.append(renderDialogueAccordionItem(asMap(o), di++, questTitles, session, agentId, canWrite));
            }
            sb.append("</div>");
        }
        return sb.toString();
    }

    /**
     * Une ligne d'accordion de dialogue : en-tête = synthèse (nom lisible, id technique, compteurs,
     * état), corps = sections repliées (Résumé / PNJ / Quêtes / Diagnostics / Graphe / Actions). Le
     * graphe des nœuds n'est PAS ouvert par défaut. Les avertissements du moteur sont rendus par
     * {@link DiagnosticHelp} (message humain, conséquence, action, lien doc précis).
     */
    private String renderDialogueAccordionItem(Map<String, Object> dg, int idx, Map<String, String> questTitles,
                                               Session session, String agentId, boolean canWrite) {
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

        boolean anyErr = warnings.stream().anyMatch(w -> "error".equals(str(asMap(w).get("severity"))));
        boolean anyWarn = !warnings.isEmpty();
        String cat = (linked.isEmpty() ? "unlinked" : "linked") + (anyWarn ? " warn" : "") + (anyErr ? " err" : "");
        String ftext = Http.esc(id + " " + key + " " + human + " "
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
        sb.append(dialogueHealthPill(warnings));
        sb.append("</span></button></h3>");

        sb.append("<div id=\"").append(slug).append("\" class=\"accordion-collapse collapse\" ")
                .append("data-bs-parent=\"#dialogues-accordion\"><div class=\"accordion-body npc-detail\">");

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
            sb.append(dialogueNodeCard(asMap(o), id, nodeIds, questTitles, session, agentId, canWrite));
        }
        sb.append("</div></details>");

        // ---- ACTIONS ----
        if (canWrite) {
            sb.append(detailSection("target", "Actions"));
            sb.append(dialogueNodeCreateForm(session, agentId, id));
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
        sb.append("<label>Texte (MiniMessage autorisé)</label><input type=\"text\" name=\"text\" value=\"")
                .append(Http.esc(text)).append("\" maxlength=\"512\">");
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
        sb.append("<label>Texte du nœud (MiniMessage autorisé)</label><input type=\"text\" name=\"text\" "
                + "maxlength=\"512\" placeholder=\"&lt;gray&gt;À bientôt.&lt;/gray&gt;\">");
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

    /** Noms français des couleurs MiniMessage de la palette du formulaire de dialogue (issue #118). */
    private static final Map<String, String> COLOR_LABELS_FR = Map.ofEntries(
            Map.entry("white", "Blanc"), Map.entry("gray", "Gris"), Map.entry("yellow", "Jaune"),
            Map.entry("gold", "Or"), Map.entry("green", "Vert"), Map.entry("dark_green", "Vert foncé"),
            Map.entry("aqua", "Cyan"), Map.entry("dark_aqua", "Cyan foncé"), Map.entry("blue", "Bleu"),
            Map.entry("dark_blue", "Bleu foncé"), Map.entry("red", "Rouge"), Map.entry("dark_red", "Rouge foncé"),
            Map.entry("light_purple", "Rose"), Map.entry("dark_purple", "Violet"));

    /**
     * Palette de couleurs MiniMessage (issue #118) : pastilles avec aperçu réel de la teinte, plus un
     * champ caché {@code text_color}. Le panel génère ensuite {@code <couleur>…</couleur>} côté
     * validation ; l'utilisateur n'écrit jamais de balise pour un cas simple. Progressif :
     * {@code panel.js} pilote la sélection et l'aperçu ; sans JS, la couleur par défaut s'applique et
     * le MiniMessage manuel reste possible.
     */
    private String colorPaletteField() {
        StringBuilder sb = new StringBuilder("<div class=\"mb-2 dlg-color\">");
        sb.append("<label class=\"form-label\">Couleur du texte</label>");
        sb.append("<input type=\"hidden\" name=\"text_color\" value=\"\" data-dlg-color>");
        sb.append("<div class=\"dlg-palette\" role=\"group\" aria-label=\"Couleur du texte\" data-dlg-palette>");
        sb.append("<button type=\"button\" class=\"dlg-swatch on\" data-color=\"\" style=\"--sw:")
                .append(nzHex(MiniText.colorHex("white"))).append("\" title=\"Par défaut\" aria-pressed=\"true\">A</button>");
        for (String c : AgentActionCatalog.PALETTE_COLORS) {
            String label = COLOR_LABELS_FR.getOrDefault(c, MiniText.prettifyId(c));
            sb.append("<button type=\"button\" class=\"dlg-swatch\" data-color=\"").append(Http.esc(c))
                    .append("\" style=\"--sw:").append(nzHex(MiniText.colorHex(c))).append("\" title=\"")
                    .append(Http.esc(label)).append("\" aria-pressed=\"false\">A</button>");
        }
        sb.append("</div>");
        sb.append("<div class=\"form-text\">Choisir une pastille — le panel génère le MiniMessage. "
                + "Pour un rendu avancé, écrire directement du MiniMessage dans le texte.</div>");
        return sb.append("</div>").toString();
    }

    private static String nzHex(String hex) {
        return hex == null || hex.isBlank() ? "#888888" : hex;
    }

    /**
     * Bloc « Nouveau dialogue » (issue #118) : action principale clairement identifiable — un bouton
     * primaire dans une barre dédiée qui déplie le formulaire, plus un accordéon discret.
     */
    private String dialogueCreateBlock(Session session, String agentId) {
        return "<div class=\"npc-catbar dlg-newbar\"><span class=\"npc-catbar-t\">Créer</span>"
                + "<button class=\"btn btn-sm btn-primary\" type=\"button\" data-bs-toggle=\"collapse\" "
                + "data-bs-target=\"#dlg-new\" aria-expanded=\"false\" aria-controls=\"dlg-new\">"
                + Icons.icon("plus") + "Nouveau dialogue</button></div>"
                + "<div class=\"collapse\" id=\"dlg-new\"><div class=\"card card-body npc-formcard\">"
                + dialogueCreateForm(session, agentId) + "</div></div>";
    }

    /** Formulaire de création d'un dialogue (id + locuteur + couleur + texte du nœud « start »). */
    private String dialogueCreateForm(Session session, String agentId) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p class=\"fs-h\">").append(Icons.icon("dialogues")).append("Nouveau dialogue</p>");
        sb.append("<form method=\"post\" action=\"/agents/action\" autocomplete=\"off\" class=\"actform\">");
        sb.append("<input type=\"hidden\" name=\"_csrf\" value=\"").append(Http.esc(session.csrfToken())).append("\">");
        sb.append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"type\" value=\"dialogue.definition.create\">");
        sb.append("<input type=\"hidden\" name=\"return\" value=\"/dialogues\">");

        sb.append("<div class=\"mb-2\"><label class=\"form-label\">ID du dialogue</label>"
                + "<input class=\"form-control\" type=\"text\" name=\"key\" autocomplete=\"off\" "
                + "pattern=\"[a-z0-9._-]{1,64}\" placeholder=\"Exemple : iron_specialist_intro\" required>"
                + "<div class=\"form-text\">Identifiant interne unique. Minuscules, chiffres, « . _ - ». "
                + "Il sera exposé comme <code>rpgquest:&lt;id&gt;</code>.</div></div>");

        sb.append("<div class=\"mb-2\"><label class=\"form-label\">Locuteur affiché</label>"
                + "<input class=\"form-control\" type=\"text\" name=\"speaker\" autocomplete=\"off\" maxlength=\"128\" "
                + "placeholder=\"Exemple : Robert\" required>"
                + "<div class=\"form-text\">Nom affiché devant la réplique. Le rattachement à un PNJ se fait "
                + "sur la fiche du PNJ (champ « Dialogue »), pas ici.</div></div>");

        sb.append(colorPaletteField());

        sb.append("<div class=\"mb-2\"><label class=\"form-label\">Texte du nœud de départ</label>"
                + "<input class=\"form-control\" type=\"text\" name=\"text\" autocomplete=\"off\" maxlength=\"512\" "
                + "placeholder=\"Exemple : Bonjour voyageur.\" data-dlg-text required>"
                + "<div class=\"form-text\">Réplique d'ouverture. Choisir une couleur ci-dessus, ou saisir du "
                + "MiniMessage (<code>&lt;yellow&gt;…&lt;/yellow&gt;</code>) pour un rendu avancé.</div></div>");

        sb.append("<p class=\"form-text\">Aperçu : <span class=\"dlg-preview\" data-dlg-preview>—</span> "
                + "<noscript>(activez JavaScript pour l'aperçu et la palette de couleurs)</noscript></p>");

        sb.append(mutationConsent("dialogue.definition.create", "",
                "Crée dialogues/<id>.yml avec un premier nœud de départ. Les choix et les actions "
                        + "s'ajoutent ensuite. Réversible (suppression du fichier)."));
        sb.append("<button class=\"btn btn-primary\" type=\"submit\">Créer le dialogue</button></form>");
        latestForPlayer(agentId, "dialogue.definition.create", "").ifPresent(row -> sb.append(resultLine("Dernière création", row)));
        return sb.toString();
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
     * Données de référence pour l'éditeur guidé #46 : id de quêtes / PNJ connus et mondes chargés,
     * pris du dernier relevé <em>réussi</em> de l'agent ({@code quest.list} / {@code npc.list} +
     * heartbeat). Si un relevé manque, la partie correspondante est marquée « inconnue » et la
     * validation dégrade ses contrôles en {@code INFO}.
     */
    public com.lodygames.rpgquest.panel.content.RefData referenceData(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return com.lodygames.rpgquest.panel.content.RefData.empty();
        }
        Optional<Map<String, Object>> questDet = latestDetails(agentId, "quest.list");
        Optional<Map<String, Object>> npcDet = latestDetails(agentId, "npc.list");
        List<String> quests = questDet.map(d -> asList(d.get("quests"))).orElse(List.of())
                .stream().map(o -> str(asMap(o).get("id"))).filter(s -> !s.isEmpty()).toList();
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
        List<String> worlds = loadedWorldNames(agentId);
        return new com.lodygames.rpgquest.panel.content.RefData(
                quests, npcs, worlds, questDet.isPresent(), npcDet.isPresent(), !worlds.isEmpty(), npcNames);
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
