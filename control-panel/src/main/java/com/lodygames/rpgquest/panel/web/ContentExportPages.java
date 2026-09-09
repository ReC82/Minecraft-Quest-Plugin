package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.json.Json;
import java.util.List;
import java.util.Map;

/**
 * Page {@code /content/export} du Control Panel (issue #108) : export versionné du contenu
 * déclaratif LodyQuests en {@code lodyquests-content-pack}.
 *
 * <p>Aucune logique métier ici : chaque bouton enfile l'action agent <strong>lecture seule</strong>
 * {@code content.export} (via le point d'entrée générique {@code /agents/action}, mêmes validation /
 * CSRF / audit que les autres actions). Le pack revient dans le résultat de l'action ; le
 * téléchargement se fait ensuite via {@code GET /content/export/download?action=<id>}.</p>
 */
public final class ContentExportPages {

    /** Familles proposées à l'écran (miroir de {@code ContentFamily} côté plugin). */
    private static final List<String[]> FAMILIES = List.of(
            new String[] {"quests", "Quêtes"},
            new String[] {"stories", "Stories"},
            new String[] {"dialogues", "Dialogues"},
            new String[] {"npcs", "PNJ logiques"});

    private final AgentStore agentStore;
    private final String defaultAgentId;

    public ContentExportPages(AgentStore agentStore, String defaultAgentId) {
        this.agentStore = agentStore;
        this.defaultAgentId = defaultAgentId;
    }

    public String render(String toastActionId, String error) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("export", "Export de contenu",
                "Sauvegarder, archiver ou fournir à une IA le contenu déclaratif LodyQuests, "
                        + "dans un format public versionné (lodyquests-content-pack, schemaVersion 1).", ""));

        if (error != null && !error.isBlank()) {
            sb.append(Ui.banner("error", Http.esc(error)));
        }
        if (toastActionId != null && !toastActionId.isBlank()) {
            sb.append(Ui.banner("info",
                    "Export demandé (action <code>" + Http.esc(toastActionId.substring(0, Math.min(8, toastActionId.length())))
                            + "</code>). Le pack apparaît dans « Exports récents » ci-dessous dès que l'agent a répondu."));
        }

        if (defaultAgentId == null || defaultAgentId.isBlank()) {
            sb.append(Ui.banner("warning",
                    "Aucun agent RPGQuest configuré : l'export a besoin d'un agent en ligne pour lire le contenu du serveur."));
            return sb.toString();
        }

        sb.append(exportCard());
        sb.append(recentCard());
        sb.append(aboutCard());
        return sb.toString();
    }

    private String exportCard() {
        StringBuilder sb = new StringBuilder();
        sb.append("<section class=\"card\">");
        sb.append(Ui.sectionTitle("box", "Que veux-tu exporter ?"));

        // Tout le contenu
        sb.append("<div class=\"export-row\">");
        sb.append(form("all", null, "<strong>Exporter tout le contenu</strong>"
                + "<span class=\"muted\"> — quêtes, stories, dialogues et PNJ dans un seul paquet, avec leurs références.</span>",
                "Exporter tout"));
        sb.append("</div>");

        // Par famille
        sb.append("<p class=\"muted export-sub\">Ou une famille entière :</p><div class=\"export-fam\">");
        for (String[] fam : FAMILIES) {
            sb.append(form(fam[0], null, "Exporter les " + Http.esc(fam[1].toLowerCase()), "Exporter " + Http.esc(fam[1])));
        }
        sb.append("</div>");

        // Sélection / élément
        sb.append("<p class=\"muted export-sub\">Ou une sélection précise (un ou plusieurs identifiants d'une même famille) :</p>");
        sb.append("<form method=\"post\" action=\"/agents/action\" class=\"export-select\">");
        sb.append("%CSRF%");
        sb.append("<input type=\"hidden\" name=\"type\" value=\"content.export\">");
        sb.append("<input type=\"hidden\" name=\"return\" value=\"/content/export\">");
        sb.append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(defaultAgentId)).append("\">");
        sb.append("<label>Famille <select name=\"family\">");
        for (String[] fam : FAMILIES) {
            sb.append("<option value=\"").append(fam[0]).append("\">").append(Http.esc(fam[1])).append("</option>");
        }
        sb.append("</select></label>");
        sb.append("<label>Identifiants <input type=\"text\" name=\"ids\" placeholder=\"rpgquest:first_steps, rpgquest:crystal_hunt\" "
                + "autocomplete=\"off\" spellcheck=\"false\"></label>");
        sb.append("<button type=\"submit\" class=\"btn\">Exporter la sélection</button>");
        sb.append("<p class=\"muted\">Séparés par des virgules ou des espaces. Laisser vide = toute la famille.</p>");
        sb.append("</form>");

        sb.append("</section>");
        return sb.toString();
    }

    private String form(String family, String ids, String labelHtml, String buttonText) {
        StringBuilder sb = new StringBuilder();
        sb.append("<form method=\"post\" action=\"/agents/action\" class=\"export-btn-form\">");
        sb.append("%CSRF%");
        sb.append("<input type=\"hidden\" name=\"type\" value=\"content.export\">");
        sb.append("<input type=\"hidden\" name=\"return\" value=\"/content/export\">");
        sb.append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(defaultAgentId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"family\" value=\"").append(Http.esc(family)).append("\">");
        if (ids != null && !ids.isBlank()) {
            sb.append("<input type=\"hidden\" name=\"ids\" value=\"").append(Http.esc(ids)).append("\">");
        }
        sb.append("<span class=\"export-btn-label\">").append(labelHtml).append("</span>");
        sb.append("<button type=\"submit\" class=\"btn\">").append(Http.esc(buttonText)).append("</button>");
        sb.append("</form>");
        return sb.toString();
    }

    private String recentCard() {
        List<AgentActionRow> rows = agentStore.recentActions(defaultAgentId, 40).stream()
                .filter(r -> "content.export".equals(r.type()))
                .limit(12)
                .toList();
        StringBuilder sb = new StringBuilder();
        sb.append("<section class=\"card\">");
        sb.append(Ui.sectionTitle("history", "Exports récents"));
        if (rows.isEmpty()) {
            sb.append(Ui.empty("box", "Aucun export pour l'instant."));
            sb.append("</section>");
            return sb.toString();
        }
        sb.append(Ui.tableOpen("Demandé", "Portée", "État", "Éléments", "Taille", ""));
        for (AgentActionRow r : rows) {
            Map<String, Object> details = safeDetails(r.resultJson());
            String family = str(r.params().get("family"), "all");
            String scope = str(details.get("family"), family);
            String ids = str(r.params().get("ids"), "");
            if (!ids.isBlank()) {
                scope = scope + " (" + Http.esc(shorten(ids)) + ")";
            } else {
                scope = Http.esc(scope);
            }
            String elements = details.containsKey("elements") ? String.valueOf(((Number) details.get("elements")).longValue()) : "—";
            String size = details.containsKey("bytes")
                    ? (((Number) details.get("bytes")).longValue() / 1024 + 1) + " Kio" : "—";
            String dl = "SUCCESS".equalsIgnoreCase(r.status().name())
                    ? "<a class=\"btn btn-sm\" href=\"/content/export/download?action=" + Http.esc(r.id())
                            + "\">Télécharger</a>"
                    : "<span class=\"muted\">—</span>";
            sb.append("<tr><td>").append(Ui.id(shorten(r.id())))
                    .append("</td><td>").append(scope)
                    .append("</td><td>").append(Ui.actionStatus(r.status()))
                    .append("</td><td>").append(elements)
                    .append("</td><td>").append(size)
                    .append("</td><td>").append(dl).append("</td></tr>");
        }
        sb.append(Ui.tableClose());
        sb.append("</section>");
        return sb.toString();
    }

    private String aboutCard() {
        return "<section class=\"card\">"
                + Ui.sectionTitle("docs", "À propos du format")
                + "<p class=\"muted\">Le pack est un YAML déterministe : <code>format: lodyquests-content-pack</code>, "
                + "<code>schemaVersion: 1</code>, un bloc <code>metadata</code> informatif, puis <code>content:</code> "
                + "avec <code>quests</code> / <code>stories</code> / <code>dialogues</code> / <code>npcs</code>. "
                + "Aucune donnée joueur, aucun secret. Les références (story → quêtes, quête → PNJ donneur, "
                + "PNJ → dialogue…) sont conservées par identifiant. Détail : "
                + "<a href=\"/docs\">documentation</a> (fiche « Content pack »).</p>"
                + "<p class=\"muted\">Limite actuelle : un export dépassant ~56 Kio est refusé proprement "
                + "(transport agent) — exporter alors par famille ou par élément. Le découpage viendra avec l'import (#109).</p>"
                + "</section>";
    }

    private Map<String, Object> safeDetails(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = Json.parse(resultJson);
            if (parsed instanceof Map<?, ?> root && root.get("details") instanceof Map<?, ?> d) {
                @SuppressWarnings("unchecked")
                Map<String, Object> details = (Map<String, Object>) d;
                return details;
            }
        } catch (RuntimeException ignored) {
            // résultat illisible : la ligne reste affichée sans métadonnées.
        }
        return Map.of();
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static String shorten(String value) {
        String t = value == null ? "" : value.trim();
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }
}
