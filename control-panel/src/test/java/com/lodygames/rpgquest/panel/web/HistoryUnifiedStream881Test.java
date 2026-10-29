package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * #881 (retour propriétaire du 29/10) — l'onglet « Tous » de l'Historique réunit les actions agent et les
 * opérations du panel seul en un flux unique trié par date, sans doublon ; l'onglet « Opérations du
 * panel » ne garde qu'elles ; un filtre qui les écarte le dit. Fixtures pures, aucune base réelle.
 */
class HistoryUnifiedStream881Test {

    private static final Instant NOW = Instant.parse("2026-10-29T17:00:00Z");

    private static AgentActionRow action(String id, String type, AgentActionStatus status, int minutesAgo) {
        return new AgentActionRow(id + "-0000-0000", "agent", type, Map.of(), status,
                NOW.minusSeconds(60L * minutesAgo), "owner", null, 0, NOW, status.name(), "", "ok", null);
    }

    private static ActionsPages.PanelOperation op(String action, String result, int minutesAgo) {
        return ActionsPages.PanelOperation.of(NOW.minusSeconds(60L * minutesAgo), "owner", action, result,
                "project=p1 building=test_cabane_747d name=TEST #747d cabane", "", id -> Optional.empty());
    }

    private static final List<AgentActionRow> ACTIONS = List.of(
            action("aaaaaaaa", "player.list", AgentActionStatus.SUCCESS, 5),
            action("bbbbbbbb", "npc.list", AgentActionStatus.FAILED, 20),
            action("cccccccc", "quest.list", AgentActionStatus.PENDING, 40));
    private static final List<ActionsPages.PanelOperation> OPS = List.of(
            op("building.project.delete", "OK", 10),
            op("building.project.trash", "OK", 30));

    private static String page(String query) {
        Map<String, String> q = new java.util.HashMap<>();
        for (String kv : query.split("&")) {
            if (!kv.isEmpty()) {
                String[] p = kv.split("=", 2);
                q.put(p[0], p.length > 1 ? p[1] : "");
            }
        }
        return ActionsPages.list(ACTIONS, List.of(), OPS, ActionsPages.Query.parse(q), NOW, PlayerNames.EMPTY);
    }

    private static String table(String html) {
        int at = html.indexOf("actions-history");
        return html.substring(at, html.indexOf("</table>", at));
    }

    @Test
    void allMergesAgentActionsAndPanelOperationsByDateWithoutDuplicates() {
        String t = table(page(""));
        int player = t.indexOf("player.list");
        int delete = t.indexOf("building.project.delete");
        int npc = t.indexOf("npc.list");
        int trash = t.indexOf("building.project.trash");
        int quest = t.indexOf("quest.list");
        assertTrue(player > 0 && player < delete && delete < npc && npc < trash && trash < quest, t);
        assertEquals(1, t.split("Supprimé définitivement", -1).length - 1, "sans doublon");
        assertEquals(5, t.split("<tr class=\"act-row\"", -1).length - 1);
        assertTrue(page("").contains("5 entrées · dont 2 opérations du panel"));
    }

    @Test
    void panelTabListsOnlyPanelOperations() {
        String t = table(page("status=panel&domain=all"));
        assertTrue(t.contains("Supprimé définitivement") && t.contains("Mis à la corbeille"), t);
        assertFalse(t.contains("player.list") || t.contains("npc.list"), t);
        assertTrue(t.contains("TEST #747d cabane") && t.contains("(n'existe plus)"), "projet supprimé dit tel quel");
    }

    @Test
    void filtersNeverHidePanelOperationsSilently() {
        String success = page("status=success");
        assertTrue(table(success).contains("building.project.delete"), "une opération réussie est un succès");
        assertFalse(table(success).contains("npc.list"));

        String pending = page("status=pending");
        assertFalse(pending.contains("building.project.delete"));
        assertTrue(pending.contains("2 opérations du panel écartées par ce filtre")
                && pending.contains("status=panel&amp;domain=all"), pending);

        String npc = page("domain=npc");
        assertTrue(npc.contains("2 opérations du panel écartées"), npc);
        String buildings = page("domain=buildings");
        assertTrue(table(buildings).contains("building.project.trash") && !table(buildings).contains("npc.list"));
        assertTrue(page("").contains("Bâtiments / Atelier"), "puce de domaine présente");
    }

    @Test
    void searchCoversBothOrigins() {
        String t = table(page("q=cabane"));
        assertTrue(t.contains("building.project.delete") && !t.contains("player.list"), t);
        assertTrue(table(page("q=joueurs")).contains("player.list") || table(page("q=player")).contains("player.list"));
    }

    @Test
    void everyViewSharesTheSamePresentation() {
        for (String q : List.of("", "status=panel&domain=all", "status=success")) {
            String html = page(q);
            assertTrue(html.contains("<th>Date</th><th>Opération</th><th>Cible</th><th>Résultat</th><th>Acteur</th>")
                    && html.contains("d-md-none actions-cards"), q);
        }
        String refused = ActionsPages.list(ACTIONS, List.of(ActionsPages.Refusal.of(NOW, "owner",
                        "agent=a type=player.kick player=Steve", "confirmation manquante")), OPS,
                ActionsPages.Query.parse(Map.of("status", "refused")), NOW, PlayerNames.EMPTY);
        assertTrue(refused.contains("actions-history") && refused.contains("confirmation manquante")
                && refused.contains("pill--rejected"), refused);
        assertFalse(page("").contains("Toutes les actions envoyées aux agents"), "le sous-titre ne prétend plus tout");
    }
}
