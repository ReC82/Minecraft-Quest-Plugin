package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import com.lodygames.rpgquest.panel.agent.AgentIdentity;
import com.lodygames.rpgquest.panel.agent.AgentRegistry;
import com.lodygames.rpgquest.panel.agent.AgentStore;
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

    public AgentPages(AgentStore store, AgentRegistry registry, String defaultAgentId) {
        this.store = store;
        this.registry = registry;
        this.defaultAgentId = defaultAgentId;
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
            sb.append("<p class=\"muted\">Aucune liste chargée — cliquer sur « Rafraîchir la liste ».</p>");
        } else {
            List<Object> rows = asList(roster.get().get("players"));
            if (rows.isEmpty()) {
                sb.append("<p class=\"muted\">Aucun joueur connecté au dernier relevé.</p>");
            } else {
                sb.append("<table><thead><tr><th>Nom</th><th>UUID</th><th>Monde</th><th>Position</th><th></th></tr></thead><tbody>");
                for (Object o : rows) {
                    Map<String, Object> r = asMap(o);
                    String name = str(r.get("name"));
                    sb.append("<tr><td><strong>").append(Http.esc(name)).append("</strong></td>")
                            .append("<td class=\"muted\"><code title=\"").append(Http.esc(str(r.get("uuid")))).append("\">")
                            .append(Http.esc(shorten(str(r.get("uuid")), 8))).append("</code></td>")
                            .append("<td>").append(Http.esc(str(r.get("world")))).append("</td>")
                            .append("<td class=\"muted\">").append(Http.esc(str(r.get("x")) + " " + str(r.get("y")) + " " + str(r.get("z"))))
                            .append("</td>")
                            .append("<td><a href=\"/players?agent=").append(Http.esc(agentId)).append("&player=")
                            .append(Http.esc(name)).append("\">Sélectionner</a></td></tr>");
                }
                sb.append("</tbody></table>");
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
                sb.append("<option value=\"").append(Http.esc(str(it.get("id")))).append("\">")
                        .append(Http.esc(str(it.get("displayName")))).append(" — ").append(Http.esc(str(it.get("id"))))
                        .append("</option>");
            }
            sb.append("</select>");
        }
        sb.append("<label>Quantité (1–64)</label><input type=\"number\" name=\"amount\" value=\"1\" min=\"1\" max=\"64\">");
        sb.append(confirmBox("Confirmer la remise de l'objet à " + player + "."));
        sb.append("<button class=\"btn\" type=\"submit\">Donner</button></form>");
        latestForPlayer(agentId, "player.item.give", player).ifPresent(row ->
                sb.append(resultLine("Dernier give", row)));

        // --- Reset new player ---
        sb.append("<h3>Reset « nouveau joueur »</h3>");
        sb.append("<p class=\"muted\">L'aperçu ne modifie rien. La confirmation remet à zéro l'état RPGQuest "
                + "(quêtes, stories, variables/unlocks dont CLAIM_TIER_1, progression RPG, découvertes de "
                + "Waystones, cooldowns, claim principal + objets RPGQuest de l'inventaire). Ne touche jamais "
                + "le profil/UUID, les mondes, les autres joueurs.</p>");
        sb.append(actionButton(session, agentId, "player.resetnew.preview", "/players", player,
                "Aperçu (aucune écriture)", ""));
        latestForPlayer(agentId, "player.resetnew.preview", player).ifPresent(row -> {
            sb.append(resultLine("Aperçu", row));
            detailsOf(row).map(d -> asList(d.get("lines"))).ifPresent(lines -> {
                if (!lines.isEmpty()) {
                    sb.append("<table><thead><tr><th>Catégorie</th><th>Nombre</th><th>Détail</th></tr></thead><tbody>");
                    for (Object o : lines) {
                        Map<String, Object> l = asMap(o);
                        sb.append("<tr><td>").append(Http.esc(str(l.get("label")))).append("</td><td>")
                                .append(Http.esc(str(l.get("count")))).append("</td><td class=\"muted\">")
                                .append(Http.esc(str(l.get("detail")))).append("</td></tr>");
                    }
                    sb.append("</tbody></table>");
                }
            });
        });
        sb.append("<details><summary class=\"muted\">Confirmer le reset réel de ").append(Http.esc(player)).append("</summary>");
        sb.append(formStart(session, agentId, "player.resetnew.confirm", "/players", player));
        sb.append(confirmBox("Je confirme la remise à zéro complète de l'état RPGQuest de " + player + "."));
        sb.append("<button class=\"btn danger\" type=\"submit\">Reset « nouveau joueur »</button></form>");
        latestForPlayer(agentId, "player.resetnew.confirm", player).ifPresent(row ->
                sb.append(resultLine("Dernier reset", row)));
        sb.append("</details>");

        return sb.toString();
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
        if (catalog.isEmpty()) {
            sb.append("<p class=\"muted\">Aucun catalogue chargé.</p>");
        } else {
            for (Object o : catalog) {
                Map<String, Object> qd = asMap(o);
                sb.append("<div class=\"card\" style=\"margin:8px 0\">");
                sb.append("<div><strong>").append(Http.esc(str(qd.get("title")))).append("</strong> ")
                        .append("<code class=\"muted\">").append(Http.esc(str(qd.get("id")))).append("</code></div>");
                sb.append("<div class=\"muted\">catégorie : ").append(Http.esc(str(qd.get("category"))))
                        .append(Boolean.TRUE.equals(qd.get("repeatable")) ? " · répétable" : "").append("</div>");
                List<Object> prereq = asList(qd.get("prerequisites"));
                if (!prereq.isEmpty()) {
                    sb.append("<div class=\"muted\">prérequis : ").append(Http.esc(join(prereq))).append("</div>");
                }
                List<Object> steps = asList(qd.get("steps"));
                if (!steps.isEmpty()) {
                    sb.append("<ol>");
                    for (Object s : steps) {
                        Map<String, Object> st = asMap(s);
                        sb.append("<li>").append(Http.esc(join(asList(st.get("objectives")))))
                                .append(" <code class=\"muted\">").append(Http.esc(str(st.get("id")))).append("</code></li>");
                    }
                    sb.append("</ol>");
                }
                List<Object> rewards = asList(qd.get("rewards"));
                if (!rewards.isEmpty()) {
                    sb.append("<div class=\"muted\">récompenses : ").append(Http.esc(join(rewards))).append("</div>");
                }
                sb.append("</div>");
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

    private String renderQuestPlayerRow(Map<String, Object> r) {
        StringBuilder sb = new StringBuilder();
        sb.append("<tr><td><strong>").append(Http.esc(str(r.get("title")))).append("</strong><br>")
                .append("<code class=\"muted\">").append(Http.esc(str(r.get("questId")))).append("</code></td>");
        sb.append("<td>").append(statePill(str(r.get("state")))).append("</td>");
        sb.append("<td class=\"muted\">");
        String step = str(r.get("currentStepId"));
        if (!step.isEmpty() && !"null".equals(step)) {
            sb.append("étape : ").append(Http.esc(step));
            List<Object> objectives = asList(r.get("objectives"));
            for (Object o : objectives) {
                Map<String, Object> ob = asMap(o);
                sb.append("<br>").append(Http.esc(str(ob.get("description")))).append(" — ")
                        .append(Http.esc(str(ob.get("current")))).append("/").append(Http.esc(str(ob.get("required"))));
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
        if (catalog.isEmpty()) {
            sb.append("<p class=\"muted\">Aucun catalogue chargé.</p>");
        } else {
            for (Object o : catalog) {
                Map<String, Object> sd = asMap(o);
                sb.append("<div class=\"card\" style=\"margin:8px 0\"><div><strong>")
                        .append(Http.esc(str(sd.get("title")))).append("</strong> <code class=\"muted\">")
                        .append(Http.esc(str(sd.get("id")))).append("</code></div><ol>");
                for (Object qid : asList(sd.get("stepQuestIds"))) {
                    sb.append("<li><code class=\"muted\">").append(Http.esc(str(qid))).append("</code></li>");
                }
                sb.append("</ol></div>");
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

    private String renderStoryPlayerRow(Map<String, Object> r) {
        return "<tr><td><strong>" + Http.esc(str(r.get("title"))) + "</strong><br><code class=\"muted\">"
                + Http.esc(str(r.get("storyId"))) + "</code></td><td>" + statePill(str(r.get("state")))
                + "</td><td class=\"muted\">étape " + Http.esc(str(r.get("currentStep"))) + "/"
                + Http.esc(str(r.get("totalSteps"))) + " · " + Http.esc(str(r.get("currentQuestId"))) + "</td></tr>";
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
            sb.append("<p class=\"muted\">Sélectionner un joueur pour voir son état et agir dessus.</p>");
            return sb.toString();
        }
        sb.append(actionButton(session, agentId, type, page, player, "Rafraîchir l'état de " + player, ""));
        Optional<AgentActionRow> row = latestForPlayer(agentId, type, player);
        if (row.isPresent() && row.get().status() == AgentActionStatus.SUCCESS) {
            List<Object> rows = detailsOf(row.get()).map(d -> asList(d.get(listKey))).orElse(List.of());
            sb.append("<table><thead><tr><th>Élément</th><th>État</th><th>Détail</th></tr></thead><tbody>");
            for (Object o : rows) {
                sb.append(renderer.render(asMap(o)));
            }
            sb.append("</tbody></table>");
        } else {
            sb.append("<p class=\"muted\">Cliquer sur « Rafraîchir l'état » pour interroger le serveur.</p>");
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
        sb.append("<table><thead><tr><th>Id</th><th>Type</th><th>Params</th><th>Statut</th>"
                + "<th>Livraisons</th><th>Résultat</th><th>Créée</th></tr></thead><tbody>");
        if (actions.isEmpty()) {
            sb.append("<tr><td colspan=\"7\" class=\"muted\">Aucune action.</td></tr>");
        } else {
            for (AgentActionRow a : actions) {
                sb.append("<tr><td><code>").append(Http.esc(shorten(a.id(), 8))).append("</code></td>")
                        .append("<td>").append(Http.esc(a.type())).append("</td>")
                        .append("<td class=\"muted\">").append(Http.esc(renderParams(a.params()))).append("</td>")
                        .append("<td><span class=\"pill ").append(pill(a.status())).append("\">").append(a.status()).append("</span></td>")
                        .append("<td>").append(a.deliverCount()).append("</td>")
                        .append("<td>").append(Http.esc(renderResult(a))).append("</td>")
                        .append("<td class=\"muted\">").append(Http.esc(a.createdAt().toString())).append("</td></tr>");
            }
        }
        sb.append("</tbody></table><p class=\"muted poll-status\" hidden></p></div>");
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
        String cls = switch (row.status()) {
            case SUCCESS -> "ok";
            case PENDING, DELIVERED -> "warn";
            default -> "err";
        };
        String text = row.status().terminal()
                ? (row.resultMessage() == null ? row.status().name() : row.resultMessage())
                : "en cours…";
        StringBuilder sb = new StringBuilder("<p class=\"resline\"><span class=\"pill ").append(cls).append("\">")
                .append(row.status()).append("</span> <span class=\"muted\">").append(Http.esc(label)).append(" :</span> ")
                .append(Http.esc(text));
        List<Object> effects = detailsOf(row).map(d -> asList(d.get("effects"))).orElse(List.of());
        if (!effects.isEmpty()) {
            sb.append("<br><span class=\"muted\">").append(Http.esc(join(effects))).append("</span>");
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

    private static String statePill(String state) {
        String s = state == null ? "" : state;
        String cls = switch (s) {
            case "COMPLETED" -> "ok";
            case "ACTIVE", "READY_TO_TURN_IN" -> "warn";
            default -> "err";
        };
        return "<span class=\"pill " + cls + "\">" + Http.esc(s.isEmpty() ? "?" : s) + "</span>";
    }

    private static String pill(AgentActionStatus status) {
        return switch (status) {
            case SUCCESS -> "ok";
            case PENDING, DELIVERED -> "warn";
            case FAILED, REJECTED, EXPIRED -> "err";
        };
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
        String value = a.resultValue() == null ? "" : " = " + a.resultValue();
        String message = a.resultMessage() == null ? "" : " · " + a.resultMessage();
        return (a.resultStatus() == null ? a.status().name() : a.resultStatus()) + value + message;
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
