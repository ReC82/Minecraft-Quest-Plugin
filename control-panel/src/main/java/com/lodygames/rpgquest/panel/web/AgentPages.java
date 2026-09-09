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
                "Sélectionner un joueur connecté, lire/écrire ses variables, lui donner un objet, "
                        + "ou prévisualiser un reset « nouveau joueur ».",
                docLink("joueurs-reset", "Documentation : reset d'un joueur")));
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
        sb.append(actionButton(session, agentId, "quest.list", "/quests", player, "Rafraîchir le catalogue", ""));
        List<Object> catalog = latestDetails(agentId, "quest.list").map(d -> asList(d.get("quests"))).orElse(List.of());
        Map<String, String> questTitles = titleIndex(catalog, "id", "title");
        if (catalog.isEmpty()) {
            sb.append(Ui.empty("Aucun catalogue chargé — cliquer sur « Rafraîchir le catalogue »."));
        } else {
            sb.append(Ui.searchToolbar("quests", "Rechercher une quête\u2026", ""));
            sb.append("<p class=\"count-note\" data-count-note data-noun=\"qu\u00eate\">" + catalog.size() + " qu\u00eate(s)</p>");
            for (Object o : catalog) {
                sb.append(renderQuestCard(asMap(o), questTitles, canEditQuests));
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

        return sb.toString();
    }

    /** Carte de quête lisible : titre humain d'abord, id technique discret, objectifs/récompenses en clair. */
    private String renderQuestCard(Map<String, Object> qd, Map<String, String> questTitles, boolean canEdit) {
        String ft = Http.esc(str(qd.get("id")) + " " + com.lodygames.rpgquest.panel.web.MiniText.plain(str(qd.get("title")))
                + " " + str(qd.get("category")) + " " + str(qd.get("giverId")) + " " + str(qd.get("giverName")));
        StringBuilder sb = new StringBuilder("<article class=\"entity-card\" data-filter-item=\"quests\" data-filter-text=\"" + ft + "\">");
        sb.append("<div class=\"entity-head\"><h3 class=\"entity-name\">")
                .append(MiniText.html(str(qd.get("title")))).append("</h3><div class=\"entity-meta\">");
        if (canEdit) {
            String slug = editSlug(str(qd.get("id")));
            if (!slug.isEmpty()) {
                sb.append("<a class=\"doc-cm-link\" href=\"/quests/edit/").append(Http.esc(slug)).append("\">")
                        .append(Icons.icon("edit")).append("Modifier</a>");
            }
        }
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
        sb.append(actionButton(session, agentId, "story.list", "/stories", player, "Rafraîchir le catalogue", ""));
        List<Object> catalog = latestDetails(agentId, "story.list").map(d -> asList(d.get("stories"))).orElse(List.of());
        // Titres humains des quêtes composant les stories, si un quest.list a déjà été chargé (données
        // locales du panel — aucun appel agent supplémentaire).
        Map<String, String> questTitles = titleIndex(
                latestDetails(agentId, "quest.list").map(d -> asList(d.get("quests"))).orElse(List.of()), "id", "title");
        if (catalog.isEmpty()) {
            sb.append(Ui.empty("Aucun catalogue chargé — cliquer sur « Rafraîchir le catalogue »."));
        } else {
            sb.append(Ui.searchToolbar("stories", "Rechercher une story\u2026", ""));
            sb.append("<p class=\"count-note\" data-count-note data-noun=\"story\">" + catalog.size() + " story(s)</p>");
            for (Object o : catalog) {
                sb.append(renderStoryCard(asMap(o), questTitles, canEditStories));
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

        return sb.toString();
    }

    /** Carte de story lisible : titre humain, id discret, nombre d'étapes, quêtes ordonnées. */
    private String renderStoryCard(Map<String, Object> sd, Map<String, String> questTitles, boolean canEdit) {
        List<Object> steps = asList(sd.get("stepQuestIds"));
        String ft = Http.esc(str(sd.get("id")) + " " + MiniText.plain(str(sd.get("title"))));
        StringBuilder sb = new StringBuilder("<article class=\"entity-card\" data-filter-item=\"stories\" data-filter-text=\""
                + ft + "\">");
        sb.append("<div class=\"entity-head\"><h3 class=\"entity-name\">")
                .append(MiniText.html(str(sd.get("title")))).append("</h3><div class=\"entity-meta\">");
        if (canEdit) {
            String slug = editSlug(str(sd.get("id")));
            if (!slug.isEmpty()) {
                sb.append("<a class=\"doc-cm-link\" href=\"/stories/edit/").append(Http.esc(slug)).append("\">")
                        .append(Icons.icon("edit")).append("Modifier</a>");
            }
        }
        sb.append(Ui.badge(steps.size() + (steps.size() > 1 ? " étapes" : " étape")))
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

        if (npcs.isEmpty()) {
            sb.append(Ui.empty("npc", "Aucun PNJ RPGQuest connu (ni définition, ni binding, ni référence)."));
            return sb.toString();
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
        sb.append("<p class=\"count-note\" data-count-note data-noun=\"PNJ\">").append(npcs.size()).append(" PNJ</p>");

        // ---- Liste = accordion (un seul PNJ ouvert à la fois) ----------------------
        sb.append("<div class=\"accordion npc-accordion\" id=\"npc-accordion\">");
        int i = 0;
        for (Object o : npcs) {
            sb.append(renderNpcAccordionItem(session, agentId, asMap(o), i++, questTitles, questIds,
                    dialogueOptions, citizensRoster, spawnWorlds, canWrite, canSetGiver, canLink, canSpawn));
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
        return "<form method=\"post\" action=\"/agents/action\" class=\"d-inline\">"
                + "<input type=\"hidden\" name=\"_csrf\" value=\"" + Http.esc(session.csrfToken()) + "\">"
                + "<input type=\"hidden\" name=\"agent\" value=\"" + Http.esc(agentId) + "\">"
                + "<input type=\"hidden\" name=\"type\" value=\"" + Http.esc(type) + "\">"
                + "<input type=\"hidden\" name=\"return\" value=\"/npcs\">"
                + "<button class=\"btn btn-sm " + btnClass + "\" type=\"submit\">"
                + Icons.icon("refresh") + Http.esc(label) + "</button></form>";
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
                .append(cat).append("\" data-filter-text=\"").append(ftext).append("\">");
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

        // ---- DIAGNOSTICS ----
        sb.append(detailSection("warning", "Diagnostics"));
        if (warnings.isEmpty()) {
            sb.append("<p class=\"muted npc-diag-ok\">").append(Icons.icon("check")).append("Aucune anomalie.</p>");
        } else {
            for (Object w : warnings) {
                Map<String, Object> wm = asMap(w);
                sb.append(diagAlert(str(wm.get("severity")), str(wm.get("message")), str(wm.get("code"))));
            }
        }

        // ---- ACTIONS ----
        boolean anyAction = (canWrite) || (canSetGiver && hasDefinition)
                || (canLink && hasDefinition && !boundCitizens && enabled)
                || (canSpawn && hasDefinition && !boundCitizens && enabled);
        if (anyAction) {
            sb.append(detailSection("target", "Actions"));
            sb.append("<div class=\"npc-actions d-flex flex-wrap gap-2\">");
            if (canWrite && !hasDefinition) {
                sb.append(actionToggle(slug + "-f-create", "Créer la définition", "plus", "btn-primary"));
            }
            if (canWrite && hasDefinition) {
                sb.append(actionToggle(slug + "-f-edit", "Modifier", "edit", "btn-outline-primary"));
            }
            if (canSetGiver && hasDefinition) {
                sb.append(actionToggle(slug + "-f-giver", "Attribuer une quête", "gift", "btn-outline-secondary"));
            }
            if (canLink && hasDefinition && !boundCitizens && enabled) {
                sb.append(actionToggle(slug + "-f-link", "Lier un PNJ Citizens", "link", "btn-outline-secondary"));
            }
            if (canSpawn && hasDefinition && !boundCitizens && enabled) {
                sb.append(actionToggle(slug + "-f-spawn", "Créer le PNJ Citizens", "server", "btn-outline-secondary"));
            }
            sb.append("</div>");

            if (canWrite && !hasDefinition) {
                sb.append(actionCollapse(slug + "-f-create", "<div class=\"card card-body npc-formcard\">"
                        + npcDefForm(session, agentId, "create", id, hasName ? displayName : MiniText.prettifyId(id),
                                "", "", true, dialogueOptions, slug + "-f-create")
                        + "</div>"));
            }
            if (canWrite && hasDefinition) {
                sb.append(actionCollapse(slug + "-f-edit", "<div class=\"card card-body npc-formcard\">"
                        + npcDefForm(session, agentId, "update", id, hasName ? displayName : "",
                                definedDialogue, role, enabled, dialogueOptions, slug + "-f-edit")
                        + "</div>"));
            }
            if (canSetGiver && hasDefinition) {
                sb.append(actionCollapse(slug + "-f-giver", "<div class=\"card card-body npc-formcard\">"
                        + giverForm(session, agentId, id, questIds) + "</div>"));
            }
            if (canLink && hasDefinition && !boundCitizens && enabled) {
                sb.append(actionCollapse(slug + "-f-link", "<div class=\"card card-body npc-formcard\">"
                        + citizensLinkForm(session, agentId, id, citizensRoster) + "</div>"));
            }
            if (canSpawn && hasDefinition && !boundCitizens && enabled) {
                sb.append(actionCollapse(slug + "-f-spawn", "<div class=\"card card-body npc-formcard\">"
                        + citizensCreateForm(session, agentId, id, hasName ? displayName : MiniText.prettifyId(id),
                                spawnWorlds) + "</div>"));
            }
        }

        sb.append("</div></div></div>");
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

    private static String diagAlert(String severity, String message, String code) {
        String sev = severity == null ? "" : severity.toLowerCase(Locale.ROOT);
        String cls = switch (sev) {
            case "error" -> "alert-danger";
            case "warning" -> "alert-warning";
            default -> "alert-info";
        };
        String ic = switch (sev) {
            case "error" -> "error";
            case "warning" -> "warning";
            default -> "info";
        };
        return "<div class=\"alert " + cls + " npc-diag\">" + Icons.icon(ic)
                + "<div><div class=\"npc-diag-msg\">" + Http.esc(message) + "</div>"
                + "<div class=\"npc-diag-code\">ID diagnostic : <code class=\"tid\">" + Http.esc(code) + "</code></div>"
                + "</div></div>";
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
                        + "autocomplete=\"off\" maxlength=\"128\" value=\"").append(Http.esc(displayName)).append("\" required></div>");
        if (contextual) {
            sb.append("<div class=\"mb-2\"><label class=\"form-label\">ID technique</label>"
                    + "<input class=\"form-control\" type=\"text\" value=\"").append(Http.esc(npcId))
                    .append("\" readonly><div class=\"form-text\">Utilisé par RPGQuest. Non modifiable ")
                    .append(update ? "" : "après création").append(".</div></div>");
        } else {
            sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid)
                    .append("-id\">ID technique</label>"
                    + "<input class=\"form-control\" id=\"").append(uid).append("-id\" type=\"text\" name=\"npc_id\" "
                    + "autocomplete=\"off\" pattern=\"[a-z0-9._-]{1,64}\" placeholder=\"woodcutter_bob\" required>"
                    + "<div class=\"form-text\">Minuscules, chiffres, « . _ - ». À ne pas modifier après création.</div></div>");
        }
        sb.append("</div>");

        // -- Contenu --
        sb.append("<div class=\"npc-fs\"><p class=\"npc-fs-h\">Contenu</p>");
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid)
                .append("-dlg\">Dialogue</label>").append(dlgSelect(uid + "-dlg", dialogueId, dialogueOptions))
                .append("<div class=\"form-text\">Optionnel.</div></div>");
        sb.append("<div class=\"mb-2\"><label class=\"form-label\" for=\"").append(uid)
                .append("-role\">Rôle</label><select class=\"form-select\" id=\"").append(uid)
                .append("-role\" name=\"role\"><option value=\"\">Aucun</option>")
                .append("<option value=\"quest_giver\"").append("quest_giver".equals(role) ? " selected" : "")
                .append(">Donneur de quête</option></select><div class=\"form-text\">Optionnel.</div></div>");
        sb.append("</div>");

        // -- État --
        sb.append("<div class=\"npc-fs\"><p class=\"npc-fs-h\">État</p>");
        sb.append("<div class=\"form-check form-switch\"><input class=\"form-check-input\" type=\"checkbox\" role=\"switch\" ")
                .append("id=\"").append(uid).append("-en\" name=\"enabled\" value=\"true\"")
                .append(enabled ? " checked" : "").append("><label class=\"form-check-label\" for=\"").append(uid)
                .append("-en\">PNJ actif</label></div></div>");

        // -- Confirmation + boutons --
        sb.append("<div class=\"form-check npc-confirm\"><input class=\"form-check-input\" type=\"checkbox\" ")
                .append("id=\"").append(uid).append("-cf\" name=\"confirm\" value=\"true\" required>")
                .append("<label class=\"form-check-label\" for=\"").append(uid).append("-cf\">")
                .append(update
                        ? "Remplacer les champs de la définition « " + Http.esc(npcId) + " » (id inchangé)."
                        : "Créer la définition logique (fichier npcs/&lt;id&gt;.yml) — aucun PNJ Citizens créé.")
                .append("</label></div>");
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
        sb.append(confirmBox("Poser giver: " + npcId + " sur la quête choisie (édition minimale du YAML)."));
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
        sb.append(actionButton(session, agentId, "dialogue.list", "/dialogues", "", "Rafraîchir le catalogue", ""));

        Map<String, String> questTitles = titleIndex(
                latestDetails(agentId, "quest.list").map(x -> asList(x.get("quests"))).orElse(List.of()), "id", "title");

        Optional<Map<String, Object>> details = latestDetails(agentId, "dialogue.list");
        if (details.isEmpty()) {
            sb.append(Ui.empty("Aucun catalogue chargé — cliquer sur « Rafraîchir le catalogue »."));
            if (canWrite) {
                sb.append(dialogueCreateForm(session, agentId));
            }
            return sb.toString();
        }
        Map<String, Object> d = details.get();
        List<Object> dialogues = asList(d.get("dialogues"));
        List<Object> loadIssues = asList(d.get("loadIssues"));
        List<Object> missing = asList(d.get("declaredButMissing"));

        if (!loadIssues.isEmpty()) {
            sb.append("<div class=\"banner err\"><strong>").append(loadIssues.size())
                    .append(" fichier(s) de dialogue rejeté(s) au chargement</strong> — non listés comme dialogues :<ul>");
            for (Object o : loadIssues) {
                Map<String, Object> m = asMap(o);
                sb.append("<li>").append(Ui.id(str(m.get("file")))).append(" — ")
                        .append(Http.esc(str(m.get("message")))).append("</li>");
            }
            sb.append("</ul></div>");
        }
        if (!missing.isEmpty()) {
            sb.append("<div class=\"banner err\"><strong>Définitions PNJ pointant vers un dialogue absent :</strong><ul>");
            for (Object o : missing) {
                Map<String, Object> m = asMap(o);
                sb.append("<li>").append(Ui.id(str(m.get("npcId")))).append(" → ")
                        .append(Ui.id(str(m.get("dialogueId")))).append("</li>");
            }
            sb.append("</ul></div>");
        }

        sb.append("<p class=\"meta-line\"><span class=\"meta-k\">Résumé</span> ")
                .append(Http.esc(str(d.get("total")))).append(" dialogue(s) · ")
                .append(Http.esc(str(d.get("nodeTotal")))).append(" nœud(s) · ")
                .append(Http.esc(str(d.get("withWarnings")))).append(" avec avertissement · ")
                .append(loadIssues.size()).append(" fichier(s) rejeté(s)</p>");

        if (canWrite) {
            sb.append("<div class=\"banner info\">Édition guidée <strong>phase 1</strong> : locuteur / texte d'un nœud, "
                    + "ajout d'un nœud simple, ajout / modification / suppression d'un <strong>choix simple</strong> "
                    + "(sans condition ni action de quête). Chaque écriture réécrit le fichier au <strong>format "
                    + "canonique</strong> du panel (commentaires et mise en forme d'origine non conservés), puis le "
                    + "re-parse et le recharge — en cas d'échec le contenu d'origine est restauré.</div>");
            sb.append(dialogueCreateForm(session, agentId));
        }

        if (dialogues.isEmpty()) {
            sb.append(Ui.empty("dialogues", "Aucun dialogue chargé."));
        } else {
            sb.append(Ui.searchToolbar("dialogues", "Rechercher un dialogue\u2026", ""));
            sb.append("<p class=\"count-note\" data-count-note data-noun=\"dialogue\">" + dialogues.size() + " dialogue(s)</p>");
            for (Object o : dialogues) {
                sb.append(renderDialogueCard(asMap(o), questTitles, session, agentId, canWrite));
            }
        }
        return sb.toString();
    }

    /**
     * Carte dialogue en quatre blocs : en-tête (identité + compteurs + état) · résumé (départ,
     * PNJ, quêtes) · diagnostics hiérarchisés (erreur → attention → info) · graphe des nœuds
     * (cartes, départ accentué, inaccessibles marqués) avec l'édition guidée par nœud.
     */
    private String renderDialogueCard(Map<String, Object> dg, Map<String, String> questTitles,
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

        String ft = Http.esc(id + " " + key + " " + String.join(" ", linked.stream().map(x -> str(x)).toList()));
        StringBuilder sb = new StringBuilder("<article class=\"entity-card dlg-card\" data-filter-item=\"dialogues\" data-filter-text=\"" + ft + "\">");

        // ---- En-tête -------------------------------------------------------------------------
        sb.append("<div class=\"entity-head\"><h3 class=\"entity-name\">")
                .append(Http.esc(MiniText.prettifyId(key.isEmpty() ? id : key))).append("</h3><div class=\"entity-meta\">")
                .append(Ui.badge(str(dg.get("nodeCount")) + " nœud(s)"))
                .append(Ui.badge(str(dg.get("choiceCount")) + " choix"))
                .append(dialogueHealthPill(warnings))
                .append(Ui.id(id)).append("</div></div>");

        // ---- Résumé -------------------------------------------------------------------------
        sb.append("<div class=\"dlg-summary\">");
        sb.append(Ui.metaLine("Nœud de départ", Ui.id(start)));
        sb.append(Ui.metaLine("PNJ liés", linked.isEmpty()
                ? "<span class=\"muted\">aucun PNJ logique lié</span>"
                : linked.stream().map(n -> Ui.id(str(n))).reduce((a, b) -> a + " " + b).orElse("")));
        if (!startsQuests.isEmpty()) {
            sb.append(Ui.metaLine("Démarre les quêtes", referencedQuests(startsQuests, questTitles)));
        }
        if (!refQuests.isEmpty()) {
            sb.append(Ui.metaLine("Quêtes référencées", referencedQuests(refQuests, questTitles)));
        }
        sb.append("</div>");

        // ---- Diagnostics (triés erreur → attention → info) ---------------------------------
        sb.append(dialogueDiagnostics(warnings));

        // ---- Graphe -----------------------------------------------------------------------
        sb.append("<details open class=\"dlg-graph-wrap\"><summary>Graphe — ").append(nodes.size())
                .append(" nœud(s)</summary><div class=\"dlg-graph\">");
        for (Object o : nodes) {
            sb.append(dialogueNodeCard(asMap(o), id, nodeIds, questTitles, session, agentId, canWrite));
        }
        sb.append("</div></details>");

        // ---- Ajouter un nœud ------------------------------------------------------------------
        if (canWrite) {
            sb.append(dialogueNodeCreateForm(session, agentId, id));
        }
        return sb.append("</article>").toString();
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

    /** Bloc diagnostics : message humain d'abord, code technique discret, groupé par sévérité. */
    private String dialogueDiagnostics(List<Object> warnings) {
        if (warnings.isEmpty()) {
            return "";
        }
        List<Map<String, Object>> sorted = new java.util.ArrayList<>();
        for (Object w : warnings) {
            sorted.add(asMap(w));
        }
        sorted.sort(java.util.Comparator.comparingInt(m -> severityRank(str(m.get("severity")))));
        StringBuilder sb = new StringBuilder("<div class=\"dlg-diag\"><p class=\"dlg-diag-h\">Diagnostics</p><ul class=\"obj-list\">");
        for (Map<String, Object> wm : sorted) {
            sb.append("<li>").append(Ui.severity(str(wm.get("severity"))))
                    .append("<span class=\"obj-text\">").append(Http.esc(str(wm.get("message")))).append("</span>")
                    .append(Ui.id(str(wm.get("code")))).append("</li>");
        }
        return sb.append("</ul></div>").toString();
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
        sb.append(confirmBox("Réécrire le fichier au format canonique (les choix du nœud sont conservés)."));
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
        sb.append(confirmBox("Ajouter ce choix simple (sans condition ni action de quête)."));
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
        sb.append(confirmBox("Réécrire ce choix (format canonique)."));
        sb.append("<button class=\"btn\" type=\"submit\">Enregistrer le choix</button></form>");
        // Supprimer
        sb.append(formStart(session, agentId, "dialogue.choice.delete", "/dialogues", ""));
        sb.append("<input type=\"hidden\" name=\"dialogue_id\" value=\"").append(Http.esc(dialogueId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"node_id\" value=\"").append(Http.esc(nodeId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"choice_index\" value=\"").append(index).append("\">");
        sb.append(confirmBox("Supprimer ce choix (impossible si c'est le dernier choix du nœud)."));
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
        sb.append(confirmBox("Ajouter ce nœud (réécriture canonique du fichier)."));
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

    /** Formulaire de création d'un squelette de dialogue (id + locuteur + texte du nœud « start »). */
    private String dialogueCreateForm(Session session, String agentId) {
        StringBuilder sb = new StringBuilder("<details><summary>Créer un dialogue (squelette)</summary>");
        sb.append("<p class=\"faint\" style=\"font-size:12px\">Crée <code>dialogues/&lt;id&gt;.yml</code> avec un "
                + "unique nœud « start » et un choix « Au revoir » (fermeture). Aucun YAML brut ; refus si l'id "
                + "existe déjà ; re-parsé après écriture. Les nœuds / choix / actions s'ajouteront via l'éditeur.</p>");
        sb.append(formStart(session, agentId, "dialogue.definition.create", "/dialogues", ""));
        sb.append("<label>ID (clé, minuscules)</label><input type=\"text\" name=\"key\" placeholder=\"woodcutter_bob\" "
                + "pattern=\"[a-z0-9._-]{1,64}\">");
        sb.append("<label>Locuteur</label><input type=\"text\" name=\"speaker\" placeholder=\"Bûcheron Bob\">");
        sb.append("<label>Texte du nœud de départ (MiniMessage autorisé)</label>"
                + "<input type=\"text\" name=\"text\" placeholder=\"&lt;white&gt;Bonjour voyageur.&lt;/white&gt;\">");
        sb.append(confirmBox("Créer le dialogue « rpgquest:<id> » (squelette d'un nœud, aucune action de quête)."));
        sb.append("<button class=\"btn\" type=\"submit\">Créer le dialogue</button></form>");
        latestForPlayer(agentId, "dialogue.definition.create", "").ifPresent(row -> sb.append(resultLine("Dernière création", row)));
        return sb.append("</details>").toString();
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
        List<String> worlds = loadedWorldNames(agentId);
        return new com.lodygames.rpgquest.panel.content.RefData(
                quests, npcs, worlds, questDet.isPresent(), npcDet.isPresent(), !worlds.isEmpty());
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

    /** Petit lien contextuel vers une fiche de documentation (icône livre, jamais un emoji). */
    static String docLink(String slugOrQuery, String label) {
        String href = slugOrQuery.startsWith("q=") || slugOrQuery.contains("=")
                ? "/docs?" + slugOrQuery
                : ("dialogues".equals(slugOrQuery) ? "/docs?q=dialogue" : "/docs/" + slugOrQuery);
        return "<a class=\"doc-cm-link\" href=\"" + Http.esc(href) + "\">" + Icons.icon("book")
                + Http.esc(label) + "</a>";
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
