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
import java.util.List;
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
        sb.append("<h1>Joueurs</h1><p class=\"sub\">Sélectionner un joueur connecté, lire/écrire ses "
                + "variables, lui donner un objet, ou prévisualiser un reset « nouveau joueur ».</p>");
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        String player = cleanPlayer(q.get("player"));
        sb.append(agentPicker(agentId, "/players", player));

        // --- Roster ---
        sb.append("<h2>Joueurs connectés</h2>");
        sb.append(actionButton(session, agentId, "player.list", "/players", player,
                "Rafraîchir la liste", ""));
        Optional<Map<String, Object>> roster = latestDetails(agentId, "player.list");
        if (roster.isEmpty()) {
            sb.append(Ui.empty("Aucune liste chargée — cliquer sur « Rafraîchir la liste »."));
        } else {
            List<Object> rows = asList(roster.get().get("players"));
            if (rows.isEmpty()) {
                sb.append(Ui.empty("Aucun joueur connecté au dernier relevé."));
            } else {
                sb.append(Ui.tableOpen("Nom", "UUID", "Monde", "Position", ""));
                for (Object o : rows) {
                    Map<String, Object> r = asMap(o);
                    String name = str(r.get("name"));
                    sb.append("<tr><td><strong>").append(Http.esc(name)).append("</strong></td>")
                            .append("<td>").append(Ui.id(shorten(str(r.get("uuid")), 13), str(r.get("uuid")))).append("</td>")
                            .append("<td>").append(Http.esc(str(r.get("world")))).append("</td>")
                            .append("<td class=\"muted\">").append(Http.esc(str(r.get("x")) + " " + str(r.get("y")) + " " + str(r.get("z"))))
                            .append("</td>")
                            .append("<td><a href=\"/players?agent=").append(Http.esc(agentId)).append("&player=")
                            .append(Http.esc(name)).append("\">Sélectionner</a></td></tr>");
                }
                sb.append(Ui.tableClose());
            }
        }

        if (!player.isEmpty()) {
            sb.append(playerDetail(session, agentId, player));
        }

        sb.append(actionsPanel(agentId));
        return sb.toString();
    }

    private String playerDetail(Session session, String agentId, String player) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>Joueur sélectionné : <strong>").append(Http.esc(player)).append("</strong></h2>");

        // --- Variables ---
        sb.append("<h3>Variables</h3>");
        sb.append(formStart(session, agentId, "player.variable.get", "/players", player));
        sb.append("<label>Clé</label><input list=\"varkeys\" name=\"key\" value=\"CLAIM_TIER_1\">");
        sb.append("<datalist id=\"varkeys\">");
        for (String k : AgentActionCatalog.KNOWN_VARIABLE_KEYS) {
            sb.append("<option value=\"").append(Http.esc(k)).append("\">");
        }
        sb.append("</datalist>");
        sb.append("<button class=\"btn\" type=\"submit\">Lire</button></form>");
        latestForPlayer(agentId, "player.variable.get", player).ifPresent(row ->
                sb.append(resultLine("Dernière lecture", row)));

        sb.append("<details><summary class=\"muted\">Écrire une variable (outil debug bas niveau)</summary>");
        sb.append(formStart(session, agentId, "player.variable.set", "/players", player));
        sb.append("<label>Clé</label><input list=\"varkeys\" name=\"key\" value=\"CLAIM_TIER_1\">");
        sb.append("<label>Valeur</label><input type=\"text\" name=\"value\" value=\"true\">");
        sb.append(confirmBox("Je comprends que c'est un outil debug et que ça ne rejoue pas une progression."));
        sb.append("<button class=\"btn\" type=\"submit\">Écrire (debug)</button></form>");
        latestForPlayer(agentId, "player.variable.set", player).ifPresent(row ->
                sb.append(resultLine("Dernière écriture", row)));
        sb.append("</details>");

        // --- Give ---
        sb.append("<h3>Donner un objet</h3>");
        sb.append(actionButton(session, agentId, "item.list", "/players", player,
                "Rafraîchir la liste des objets", ""));
        List<Object> items = latestDetails(agentId, "item.list").map(d -> asList(d.get("items"))).orElse(List.of());
        sb.append(formStart(session, agentId, "player.item.give", "/players", player));
        sb.append("<label>Objet</label>");
        if (items.isEmpty()) {
            sb.append("<input type=\"text\" name=\"item_id\" placeholder=\"rpgquest:rune_rappel\">");
        } else {
            sb.append("<select name=\"item_id\">");
            for (Object o : items) {
                Map<String, Object> it = asMap(o);
                String dn = MiniText.plain(str(it.get("displayName")));
                sb.append("<option value=\"").append(Http.esc(str(it.get("id")))).append("\">")
                        .append(Http.esc(dn.isEmpty() ? str(it.get("id")) : dn))
                        .append("  ·  ").append(Http.esc(str(it.get("id"))))
                        .append("</option>");
            }
            sb.append("</select>");
        }
        sb.append("<label>Quantité (1–64)</label><input type=\"number\" name=\"amount\" value=\"1\" min=\"1\" max=\"64\">");
        sb.append(confirmBox("Confirmer la remise de l'objet à " + player + "."));
        sb.append("<button class=\"btn\" type=\"submit\">Donner</button></form>");
        latestForPlayer(agentId, "player.item.give", player).ifPresent(row ->
                sb.append(resultLine("Dernier give", row)));

        // --- Reset new player (action sensible) ---
        sb.append("<h3>Reset « nouveau joueur »</h3>");
        sb.append("<p class=\"sub\">L'aperçu ne modifie rien. La confirmation remet à zéro l'état RPGQuest "
                + "(quêtes, stories, variables/unlocks dont CLAIM_TIER_1, progression RPG, découvertes de "
                + "Waystones, cooldowns, claim principal + objets RPGQuest de l'inventaire). Ne touche jamais "
                + "le profil/UUID, les mondes, les autres joueurs.</p>");
        sb.append(readForm(session, agentId, "player.resetnew.preview", "/players", player,
                "Aperçu (aucune écriture)"));
        latestForPlayer(agentId, "player.resetnew.preview", player).ifPresent(row -> {
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
        sb.append("<details><summary>Confirmer le reset réel de ").append(Http.esc(player)).append("</summary>");
        sb.append(formStart(session, agentId, "player.resetnew.confirm", "/players", player));
        sb.append(confirmBox("Je confirme la remise à zéro complète de l'état RPGQuest de " + player + "."));
        sb.append("<button class=\"btn danger\" type=\"submit\">Reset « nouveau joueur »</button></form>");
        latestForPlayer(agentId, "player.resetnew.confirm", player).ifPresent(row ->
                sb.append(resultLine("Dernier reset", row)));
        sb.append("</details></div>");

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
        sb.append("<h1>Quêtes</h1><p class=\"sub\">Catalogue des quêtes, état d'un joueur, et raccourcis "
                + "d'administration (démarrer / compléter / réinitialiser) via les services métier existants.</p>");
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        String player = cleanPlayer(q.get("player"));
        sb.append(agentPicker(agentId, "/quests", player));

        sb.append("<h2>Catalogue</h2>");
        sb.append(actionButton(session, agentId, "quest.list", "/quests", player, "Rafraîchir le catalogue", ""));
        List<Object> catalog = latestDetails(agentId, "quest.list").map(d -> asList(d.get("quests"))).orElse(List.of());
        Map<String, String> questTitles = titleIndex(catalog, "id", "title");
        if (catalog.isEmpty()) {
            sb.append(Ui.empty("Aucun catalogue chargé — cliquer sur « Rafraîchir le catalogue »."));
        } else {
            for (Object o : catalog) {
                sb.append(renderQuestCard(asMap(o), questTitles));
            }
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

        sb.append(actionsPanel(agentId));
        return sb.toString();
    }

    /** Carte de quête lisible : titre humain d'abord, id technique discret, objectifs/récompenses en clair. */
    private String renderQuestCard(Map<String, Object> qd, Map<String, String> questTitles) {
        StringBuilder sb = new StringBuilder("<article class=\"entity-card\">");
        sb.append("<div class=\"entity-head\"><h3 class=\"entity-name\">")
                .append(MiniText.html(str(qd.get("title")))).append("</h3><div class=\"entity-meta\">");
        String category = str(qd.get("category"));
        if (!category.isEmpty()) {
            sb.append(Ui.badge(MiniText.prettifyId(category)));
        }
        if (Boolean.TRUE.equals(qd.get("repeatable"))) {
            sb.append(Ui.badge("répétable"));
        }
        sb.append(Ui.id(str(qd.get("id")))).append("</div></div>");

        // #75 : PNJ donneur, si la quête le déclare (giver: optionnel côté YAML).
        String giverId = str(qd.get("giverId"));
        if (!giverId.isEmpty()) {
            String giverName = str(qd.get("giverName"));
            String label = !giverName.isEmpty() && !"null".equals(giverName)
                    ? MiniText.html(giverName)
                    : Http.esc(MiniText.prettifyId(giverId));
            sb.append(Ui.metaLine("Donneur", label + " " + Ui.id(giverId)));
        }

        List<Object> prereq = asList(qd.get("prerequisites"));
        if (!prereq.isEmpty()) {
            sb.append(Ui.metaLine("Prérequis", referencedQuests(prereq, questTitles)));
        }
        List<Object> steps = asList(qd.get("steps"));
        if (!steps.isEmpty()) {
            sb.append("<ul class=\"obj-list\">");
            for (Object s : steps) {
                Map<String, Object> st = asMap(s);
                String stepId = str(st.get("id"));
                // #78 : objectifs structurés en priorité — plus aucune regex sur une phrase métier.
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
                    // Repli legacy (#76) : chaînes déjà formatées, noms FR par balayage de jetons.
                    String objectives = MinecraftNames.humanizeTokens(join(asList(st.get("objectives"))));
                    sb.append("<li><span class=\"obj-text\">").append(Http.esc(objectives)).append("</span>")
                            .append(Ui.id(stepId)).append("</li>");
                }
            }
            sb.append("</ul>");
        }
        // #78 : récompenses structurées en priorité ; repli sur les chaînes legacy (#77) sinon.
        List<Object> rewardDetails = asList(qd.get("rewardDetails"));
        List<Object> rewards = asList(qd.get("rewards"));
        if (!rewardDetails.isEmpty() || !rewards.isEmpty()) {
            sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Récompenses</span></p>");
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
        return sb.append("</article>").toString();
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
        sb.append("<h1>Stories</h1><p class=\"sub\">Suites ordonnées de quêtes. Avancer d'une étape ou "
                + "compléter toute la story — c'est le chemin rapide vers CLAIM_TIER_1 pour tester les claims.</p>");
        if (agent.isEmpty()) {
            return sb.append(noAgent()).toString();
        }
        String agentId = agent.get().id();
        String player = cleanPlayer(q.get("player"));
        sb.append(agentPicker(agentId, "/stories", player));

        sb.append("<h2>Catalogue</h2>");
        sb.append(actionButton(session, agentId, "story.list", "/stories", player, "Rafraîchir le catalogue", ""));
        List<Object> catalog = latestDetails(agentId, "story.list").map(d -> asList(d.get("stories"))).orElse(List.of());
        // Titres humains des quêtes composant les stories, si un quest.list a déjà été chargé (données
        // locales du panel — aucun appel agent supplémentaire).
        Map<String, String> questTitles = titleIndex(
                latestDetails(agentId, "quest.list").map(d -> asList(d.get("quests"))).orElse(List.of()), "id", "title");
        if (catalog.isEmpty()) {
            sb.append(Ui.empty("Aucun catalogue chargé — cliquer sur « Rafraîchir le catalogue »."));
        } else {
            for (Object o : catalog) {
                sb.append(renderStoryCard(asMap(o), questTitles));
            }
        }

        List<String> storyIds = catalog.stream().map(o -> str(asMap(o).get("id"))).toList();
        sb.append(playerStatusSection(session, agentId, player, "/stories", "story.player.status", "stories",
                this::renderStoryPlayerRow));

        if (!player.isEmpty()) {
            sb.append("<h3>Actions admin sur une story</h3>");
            sb.append(storyActionForm(session, agentId, player, "story.advance", "Avancer d'une étape", storyIds));
            sb.append(storyActionForm(session, agentId, player, "story.complete", "Compléter toute la story", storyIds));
        }

        sb.append(actionsPanel(agentId));
        return sb.toString();
    }

    /** Carte de story lisible : titre humain, id discret, nombre d'étapes, quêtes ordonnées. */
    private String renderStoryCard(Map<String, Object> sd, Map<String, String> questTitles) {
        List<Object> steps = asList(sd.get("stepQuestIds"));
        StringBuilder sb = new StringBuilder("<article class=\"entity-card\">");
        sb.append("<div class=\"entity-head\"><h3 class=\"entity-name\">")
                .append(MiniText.html(str(sd.get("title")))).append("</h3><div class=\"entity-meta\">")
                .append(Ui.badge(steps.size() + (steps.size() > 1 ? " étapes" : " étape")))
                .append(Ui.id(str(sd.get("id")))).append("</div></div>");
        if (!steps.isEmpty()) {
            sb.append("<ol class=\"step-list\">");
            int n = 1;
            for (Object qid : steps) {
                String id = str(qid);
                String title = questTitles.get(id);
                sb.append("<li><span class=\"step-n\">").append(n++).append("</span>")
                        .append("<span class=\"obj-text\">")
                        .append(title != null ? MiniText.html(title) : Http.esc(MiniText.prettifyId(id)))
                        .append("</span>").append(Ui.id(id)).append("</li>");
            }
            sb.append("</ol>");
        }
        return sb.append("</article>").toString();
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
        sb.append("<h1>PNJ</h1><p class=\"sub\">Un PNJ RPGQuest a une <strong>définition logique</strong> "
                + "(fichier <code>npcs/&lt;id&gt;.yml</code>, indépendante du monde et de Citizens) et un "
                + "<strong>binding Citizens</strong> éventuel. La définition peut exister avant même que "
                + "le PNJ ne soit tagué en jeu. Position et monde ne sont pas suivis ici.</p>");
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

        sb.append("<h2>Catalogue</h2>");
        sb.append(actionButton(session, agentId, "npc.list", "/npcs", "", "Rafraîchir le catalogue", ""));
        sb.append(actionButton(session, agentId, "npc.citizens.list", "/npcs", "", "Rafraîchir les PNJ Citizens", ""));

        Map<String, String> questTitles = titleIndex(
                latestDetails(agentId, "quest.list").map(x -> asList(x.get("quests"))).orElse(List.of()), "id", "title");
        List<String> questIds = latestDetails(agentId, "quest.list").map(x -> asList(x.get("quests"))).orElse(List.of())
                .stream().map(o -> str(asMap(o).get("id"))).filter(s -> !s.isEmpty()).toList();

        // Catalogue Citizens physique (séparé). Chargé s'il a déjà été rafraîchi.
        Optional<Map<String, Object>> citizensCat = latestDetails(agentId, "npc.citizens.list");
        List<Object> citizensRoster = citizensCat.map(x -> asList(x.get("citizens"))).orElse(List.of());

        Optional<Map<String, Object>> details = latestDetails(agentId, "npc.list");
        if (details.isEmpty()) {
            sb.append(Ui.empty("Aucun catalogue chargé — cliquer sur « Rafraîchir le catalogue »."));
            if (canWrite) {
                sb.append(createDefinitionForm(session, agentId, ""));
            }
            sb.append(actionsPanel(agentId));
            return sb.toString();
        }
        Map<String, Object> d = details.get();
        List<Object> npcs = asList(d.get("npcs"));

        if (!Boolean.TRUE.equals(d.get("citizensAvailable"))) {
            sb.append("<div class=\"banner info\">Citizens est inactif sur le serveur cible : les "
                    + "bindings ne peuvent pas être vérifiés (les définitions logiques restent gérables).</div>");
        }
        sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Résumé</span> ")
                .append(Http.esc(str(d.get("total")))).append(" PNJ · ")
                .append(Http.esc(str(d.get("withDefinition")))).append(" avec définition · ")
                .append(Http.esc(str(d.get("withoutDefinition")))).append(" sans définition · ")
                .append(Http.esc(str(d.get("bound")))).append(" liés Citizens · ")
                .append(Http.esc(str(d.get("withWarnings")))).append(" avec avertissement</p>");
        if (citizensCat.isPresent()) {
            sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Citizens</span> ")
                    .append(Http.esc(str(citizensCat.get().get("total")))).append(" PNJ Citizens · ")
                    .append(Http.esc(str(citizensCat.get().get("available")))).append(" libre(s) · ")
                    .append(Http.esc(str(citizensCat.get().get("linked")))).append(" déjà lié(s)</p>");
        } else {
            sb.append("<p class=\"faint\" style=\"font-size:12px\">Cliquer « Rafraîchir les PNJ Citizens » "
                    + "pour proposer une liaison sur les PNJ « à lier ».</p>");
        }

        if (canWrite) {
            sb.append(createDefinitionForm(session, agentId, ""));
        }

        if (npcs.isEmpty()) {
            sb.append(Ui.empty("Aucun PNJ RPGQuest connu (ni définition, ni binding, ni référence)."));
        } else {
            for (Object o : npcs) {
                sb.append(renderNpcCard(session, agentId, asMap(o), questTitles, questIds,
                        citizensRoster, spawnWorlds, canWrite, canSetGiver, canLink, canSpawn));
            }
        }

        List<Object> definedIds = asList(d.get("definedIds"));
        List<Object> canonical = asList(d.get("canonicalIds"));
        sb.append("<details><summary class=\"muted\">Registre canonique — ")
                .append(definedIds.size()).append(" définition(s), ").append(canonical.size())
                .append(" id(s) référencé(s) au total</summary>");
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
        sb.append("</p><p class=\"faint\" style=\"font-size:12px\">La liste <em>Définis</em> devient la "
                + "source de vérité des ids PNJ (préparation #66 — la validation de "
                + "<code>/rpgadmin npc tag</code> n'est pas encore câblée).</p></details>");

        sb.append(actionsPanel(agentId));
        return sb.toString();
    }

    /** Formulaire « Créer une définition PNJ » (id libre ou pré-rempli depuis une carte). */
    private String createDefinitionForm(Session session, String agentId, String prefillId) {
        StringBuilder sb = new StringBuilder("<details").append(prefillId.isEmpty() ? "" : " open")
                .append("><summary>").append(prefillId.isEmpty()
                        ? "Créer une définition PNJ" : "Créer la définition « " + Http.esc(prefillId) + " »")
                .append("</summary>");
        sb.append(formStart(session, agentId, "npc.definition.create", "/npcs", ""));
        sb.append("<label>ID technique</label><input type=\"text\" name=\"npc_id\" value=\"")
                .append(Http.esc(prefillId)).append("\" placeholder=\"woodcutter_bob\" pattern=\"[a-z0-9._-]{1,64}\">");
        sb.append("<label>Nom affiché</label><input type=\"text\" name=\"display_name\" placeholder=\"Bûcheron Bob\">");
        sb.append("<label>Dialogue (optionnel)</label><input type=\"text\" name=\"dialogue_id\" placeholder=\"rpgquest:woodcutter_bob\">");
        sb.append("<label>Rôle (optionnel)</label><input type=\"text\" name=\"role\" placeholder=\"quest_giver\">");
        sb.append("<label class=\"inline\"><input type=\"checkbox\" name=\"enabled\" value=\"true\" checked> actif</label>");
        sb.append(confirmBox("Créer la définition logique (fichier npcs/<id>.yml) — aucun PNJ Citizens créé."));
        sb.append("<button class=\"btn\" type=\"submit\">Créer la définition</button></form>");
        latestForPlayer(agentId, "npc.definition.create", "").ifPresent(row -> sb.append(resultLine("Dernière création", row)));
        return sb.append("</details>").toString();
    }

    /** Carte PNJ V2 : deux blocs (Définition RPGQuest / Binding Citizens), anomalies, relations, actions. */
    private String renderNpcCard(Session session, String agentId, Map<String, Object> n,
                                 Map<String, String> questTitles, List<String> questIds,
                                 List<Object> citizensRoster, List<String> spawnWorlds, boolean canWrite,
                                 boolean canSetGiver, boolean canLink, boolean canSpawn) {
        String id = str(n.get("id"));
        String displayName = str(n.get("displayName"));
        boolean hasName = !displayName.isEmpty() && !"null".equals(displayName);
        boolean hasDefinition = Boolean.TRUE.equals(n.get("logicalDefinitionPresent"));
        boolean boundCitizens = Boolean.TRUE.equals(n.get("citizensBindingPresent"));
        boolean enabled = Boolean.TRUE.equals(n.get("enabled"));
        String numeric = str(n.get("citizensNumericId"));
        String role = str(n.get("role"));
        String state = str(n.get("state"));

        StringBuilder sb = new StringBuilder("<article class=\"entity-card\">");
        sb.append("<div class=\"entity-head\"><h3 class=\"entity-name\">")
                .append(hasName ? MiniText.html(displayName) : Http.esc(MiniText.prettifyId(id)))
                .append("</h3><div class=\"entity-meta\">");
        sb.append(hasDefinition ? Ui.badge("définition") : Ui.pill("sans définition", "failed", "✕"));
        if (boundCitizens && !numeric.isEmpty() && !"null".equals(numeric)) {
            sb.append(Ui.badge("Citizens #" + numeric));
        } else if (boundCitizens) {
            sb.append(Ui.badge("Citizens"));
        } else {
            sb.append(Ui.pill("non lié", "pending", "!"));
        }
        if (hasDefinition && !enabled) {
            sb.append(Ui.pill("désactivé", "expired", "⧖"));
        }
        if (!role.isEmpty() && !"null".equals(role)) {
            sb.append(Ui.badge(MiniText.prettifyId(role)));
        }
        sb.append(npcStateBadge(state)).append(Ui.id(id)).append("</div></div>");

        List<Object> warnings = asList(n.get("warnings"));
        if (!warnings.isEmpty()) {
            sb.append("<ul class=\"obj-list\">");
            for (Object w : warnings) {
                Map<String, Object> wm = asMap(w);
                sb.append("<li>").append(Ui.severity(str(wm.get("severity"))))
                        .append("<span class=\"obj-text\">").append(Http.esc(str(wm.get("message"))))
                        .append("</span>").append(Ui.id(str(wm.get("code")))).append("</li>");
            }
            sb.append("</ul>");
        }

        // --- Bloc Définition RPGQuest ---
        sb.append("<p class=\"meta-line\" style=\"margin-top:10px\"><span class=\"meta-k\">Définition RPGQuest</span> ");
        if (!hasDefinition) {
            sb.append("<span class=\"muted\">aucune — à créer</span></p>");
        } else {
            sb.append(enabled ? "active" : "désactivée").append("</p>");
            String desc = str(n.get("description"));
            if (!desc.isEmpty() && !"null".equals(desc)) {
                sb.append(Ui.metaLine("Description", Http.esc(desc)));
            }
            String definedDialogue = str(n.get("definedDialogueId"));
            if (!definedDialogue.isEmpty() && !"null".equals(definedDialogue)) {
                sb.append(Ui.metaLine("Dialogue déclaré", Http.esc(MiniText.prettifyId(definedDialogue))
                        + " " + Ui.id(definedDialogue)));
            }
        }

        // --- Bloc Binding Citizens ---
        sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Binding Citizens</span> ");
        if (boundCitizens && !numeric.isEmpty() && !"null".equals(numeric)) {
            String citizensName = citizensNameFor(citizensRoster, numeric);
            sb.append("#").append(Http.esc(numeric));
            if (!citizensName.isEmpty()) {
                sb.append(" — ").append(MiniText.html(citizensName));
            }
            sb.append("</p><p class=\"faint\" style=\"font-size:12px\">Pour changer ce binding, une "
                    + "procédure de rebind sera ajoutée ultérieurement.</p>");
        } else if (boundCitizens) {
            sb.append("lié</p>");
        } else if (hasDefinition) {
            sb.append("<span class=\"muted\">aucun — définition prête, PNJ Citizens à lier</span></p>");
        } else {
            sb.append("<span class=\"muted\">aucun</span></p>");
        }

        // --- Relations ---
        String dialogueId = str(n.get("dialogueId"));
        if (!dialogueId.isEmpty() && !"null".equals(dialogueId)) {
            String detail = str(n.get("dialogueNodes")) + " nœud(s), " + str(n.get("dialogueChoices")) + " choix";
            sb.append(Ui.metaLine("Dialogue en jeu", Http.esc(MiniText.prettifyId(dialogueId)) + " " + Ui.id(dialogueId)
                    + " <span class=\"muted\">— " + Http.esc(detail) + "</span>"));
            List<Object> starts = asList(n.get("dialogueStartsQuests"));
            if (!starts.isEmpty()) {
                sb.append(Ui.metaLine("Le dialogue démarre", referencedQuests(starts, questTitles)));
            }
        } else if (hasDefinition) {
            sb.append(Ui.metaLine("Dialogue en jeu", "<span class=\"muted\">aucun dialogue rpgquest:"
                    + Http.esc(id) + "</span>"));
        }
        List<Object> given = asList(n.get("questsGiven"));
        if (!given.isEmpty()) {
            sb.append(Ui.metaLine("Donne", referencedQuests(given, questTitles)));
        }
        List<Object> referenced = asList(n.get("questsReferenced"));
        if (!referenced.isEmpty()) {
            sb.append(Ui.metaLine("Objectif « parler à »", referencedQuests(referenced, questTitles)));
        }

        // --- Actions (écriture) ---
        if (canWrite && !hasDefinition) {
            sb.append(createDefinitionForm(session, agentId, id));
        }
        if (canWrite && hasDefinition) {
            sb.append("<details><summary>Éditer la définition</summary>");
            sb.append(formStart(session, agentId, "npc.definition.update", "/npcs", ""));
            sb.append("<input type=\"hidden\" name=\"npc_id\" value=\"").append(Http.esc(id)).append("\">");
            sb.append("<label>Nom affiché</label><input type=\"text\" name=\"display_name\" value=\"")
                    .append(Http.esc(hasName ? displayName : "")).append("\">");
            sb.append("<label>Dialogue (vide = aucun)</label><input type=\"text\" name=\"dialogue_id\" value=\"")
                    .append(Http.esc(cleanNull(str(n.get("definedDialogueId"))))).append("\">");
            sb.append("<label>Rôle (vide = aucun)</label><input type=\"text\" name=\"role\" value=\"")
                    .append(Http.esc(cleanNull(role))).append("\">");
            sb.append("<label class=\"inline\"><input type=\"checkbox\" name=\"enabled\" value=\"true\"")
                    .append(enabled ? " checked" : "").append("> actif</label>");
            sb.append(confirmBox("Remplacer les champs de la définition « " + id + " » (id inchangé)."));
            sb.append("<button class=\"btn\" type=\"submit\">Enregistrer</button></form></details>");
        }
        if (canSetGiver && hasDefinition) {
            sb.append("<details><summary>Attribuer une quête (giver)</summary>");
            if (questIds.isEmpty()) {
                sb.append(Ui.empty("Charger d'abord le catalogue de quêtes (page Quêtes)."));
            } else {
                sb.append(formStart(session, agentId, "quest.giver.set", "/npcs", ""));
                sb.append("<input type=\"hidden\" name=\"npc_id\" value=\"").append(Http.esc(id)).append("\">");
                sb.append("<label>Quête</label>").append(idSelect("quest_id", questIds, "rpgquest:woodcutters_request"));
                sb.append(confirmBox("Poser giver: " + id + " sur la quête choisie (édition minimale du YAML)."));
                sb.append("<button class=\"btn\" type=\"submit\">Attribuer</button></form>");
            }
            sb.append("</details>");
        }
        // Liaison à un PNJ Citizens existant (#81 phase 1) — définition présente, pas encore liée.
        if (canLink && hasDefinition && !boundCitizens && enabled) {
            sb.append("<details><summary>Lier un PNJ Citizens existant</summary>");
            sb.append(citizensLinkForm(session, agentId, id, citizensRoster));
            sb.append("</details>");
        }
        // Créer physiquement le PNJ Citizens depuis la définition (#81 phase 2) — mêmes conditions.
        if (canSpawn && hasDefinition && !boundCitizens && enabled) {
            sb.append("<details><summary>Créer le PNJ Citizens</summary>");
            sb.append(citizensCreateForm(session, agentId, id, hasName ? displayName : MiniText.prettifyId(id),
                    spawnWorlds));
            sb.append("</details>");
        }
        return sb.append("</article>").toString();
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
        sb.append(confirmBox("Lier « " + npcId + " » au PNJ Citizens choisi (aucun spawn, aucun rebind)."));
        sb.append("<button class=\"btn\" type=\"submit\">Lier</button></form>");
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
            case "UNDEFINED_REFERENCE" -> Ui.pill("non défini", "failed", "✕");
            case "BROKEN" -> Ui.pill("cassé", "failed", "✕");
            default -> Ui.badge(s.isEmpty() ? "?" : s);
        };
    }

    private static String cleanNull(String value) {
        return value == null || "null".equals(value) ? "" : value;
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

    /** Tableau des actions récentes, pollable par {@code /assets/panel.js}. */
    private String actionsPanel(String agentId) {
        List<AgentActionRow> actions = store.recentActions(agentId, 20);
        long pending = actions.stream().filter(a -> !a.status().terminal()).count();
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"actions-panel\" data-actions-agent=\"").append(Http.esc(agentId))
                .append("\" data-actions-pending=\"").append(pending).append("\">");
        sb.append("<h2>Actions récentes</h2>");
        sb.append(Ui.tableOpen("Id", "Type", "Params", "Statut", "Livr.", "Résultat", "Créée"));
        if (actions.isEmpty()) {
            sb.append("<tr><td colspan=\"7\" class=\"muted\">Aucune action pour le moment.</td></tr>");
        } else {
            for (AgentActionRow a : actions) {
                sb.append("<tr><td>").append(Ui.id(shorten(a.id(), 8))).append("</td>")
                        .append("<td>").append(Ui.actionType(a.type())).append("</td>")
                        .append("<td class=\"muted\">").append(Http.esc(renderParams(a.params()))).append("</td>")
                        .append("<td>").append(Ui.actionStatus(a.status())).append("</td>")
                        .append("<td>").append(a.deliverCount()).append("</td>")
                        .append("<td>").append(Http.esc(renderResult(a))).append("</td>")
                        .append("<td class=\"muted\">").append(Http.esc(a.createdAt().toString())).append("</td></tr>");
            }
        }
        sb.append(Ui.tableClose()).append("<p class=\"muted poll-status\" hidden></p></div>");
        sb.append("<script src=\"/assets/panel.js\" defer></script>");
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

    // ---- Petits utilitaires ---------------------------------------------------------

    private String noAgent() {
        return "<div class=\"banner err\">Aucun agent RPGQuest configuré. Voir <code>docs/control-panel/AGENT.md</code>.</div>";
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

    private static String renderParams(Map<String, String> params) {
        if (params.isEmpty()) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : ", ").append(k).append('=').append(v));
        return sb.toString();
    }

    private static String renderResult(AgentActionRow a) {
        if (!a.status().terminal()) {
            return "—";
        }
        // Statut déjà affiché par la pastille : ici, valeur + message lisibles seulement.
        String value = a.resultValue() == null || a.resultValue().isBlank() ? "" : a.resultValue();
        String message = a.resultMessage() == null || a.resultMessage().isBlank() ? "" : a.resultMessage();
        String out = (value + (value.isEmpty() || message.isEmpty() ? "" : " · ") + message).trim();
        return out.isEmpty() ? "—" : MiniText.prettifyTokens(out);
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
