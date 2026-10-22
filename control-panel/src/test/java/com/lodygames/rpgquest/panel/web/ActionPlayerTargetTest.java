package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Retour propriétaire du 22/10 sur #93 : les actions qui ciblent un joueur sont toutes dans
 * « Joueurs » (source unique : le catalogue d'actions), et la cible s'affiche par le pseudo connu,
 * l'UUID restant en secondaire.
 */
class ActionPlayerTargetTest {

    private static final String UUID = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
    private static final Instant NOW = Instant.parse("2026-10-22T09:00:00Z");

    @Test
    void everyActionTargetingAPlayerIsInThePlayersFilter() {
        List<String> missing = new ArrayList<>();
        for (AgentActionCatalog.Spec spec : AgentActionCatalog.all()) {
            if (spec.needsPlayer() && !ActionView.Domain.PLAYERS.includes(spec.type())) {
                missing.add(spec.type());
            }
        }
        assertTrue(missing.isEmpty(), "actions ciblant un joueur hors de « Joueurs » : " + missing);
    }

    @Test
    void rightsGroupsClaimsAndPlayerActionsAreFiledUnderPlayers() {
        List<String> wrong = new ArrayList<>();
        for (AgentActionCatalog.Spec spec : AgentActionCatalog.all()) {
            String t = spec.type();
            boolean playerFamily = t.startsWith("player.") || t.startsWith("mc.") || t.startsWith("claim.");
            if (playerFamily && ActionView.Domain.of(t) != ActionView.Domain.PLAYERS) {
                wrong.add(t);
            }
        }
        assertTrue(wrong.isEmpty(), "badge autre que Joueurs : " + wrong);
        for (String t : List.of("mc.rights.read", "mc.rights.sync", "mc.group.sync", "mc.group.delete",
                "claim.list", "claim.teleport", "economy.balance", "entitlement.list")) {
            assertEquals(ActionView.Domain.PLAYERS, ActionView.Domain.of(t), t);
        }
    }

    @Test
    void aQuestActionOnAPlayerKeepsItsBadgeButIsFoundUnderPlayers() {
        assertEquals(ActionView.Domain.QUESTS, ActionView.Domain.of("quest.start"));
        assertTrue(ActionView.Domain.PLAYERS.includes("quest.start"));
        assertTrue(ActionView.Domain.QUESTS.includes("quest.start"));
        assertFalse(ActionView.Domain.PLAYERS.includes("quest.list"), "un relevé de catalogue n'est pas une action joueur");
        assertFalse(ActionView.Domain.NPC.includes("mc.rights.read"));
    }

    @Test
    void targetShowsTheKnownNameWithTheUuidAsSecondary() {
        PlayerNames names = names();
        ActionView.Target t = ActionView.target(row("mc.rights.read", Map.of("player", UUID)), names);
        assertEquals("LoDyMcFly", t.primary());
        assertEquals(UUID, t.uuid());
        assertTrue(t.html().contains(">LoDyMcFly</span>") && t.html().contains("pa-uuid\">" + UUID));
        assertTrue(t.compactHtml().contains("title=\"UUID " + UUID + "\""));
        assertFalse(t.compactHtml().contains("pa-uuid"));
    }

    @Test
    void unknownUuidOrTypedNameIsShownAsIs() {
        PlayerNames names = names();
        String other = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        assertEquals(new ActionView.Target(other, ""), ActionView.target(row("player.kick", Map.of("player", other)), names));
        assertEquals(new ActionView.Target("Steve", ""), ActionView.target(row("player.kick", Map.of("player", "Steve")), names));
        assertEquals(new ActionView.Target("guide", ""), ActionView.target(row("npc.list", Map.of("npc_id", "guide")), names));
        assertEquals(UUID, ActionView.target(row("mc.rights.read", Map.of("player", UUID))), "sans annuaire : UUID brut");
    }

    @Test
    void catalogWithoutNameIsIgnored() {
        PlayerNames names = PlayerNames.fromCatalogDetails(Map.of("players", List.of(
                Map.of("uuid", UUID, "name", "null"), Map.of("name", "SansUuid"))));
        assertTrue(names.nameOf(UUID).isEmpty());
        assertTrue(PlayerNames.fromCatalogDetails(null).nameOf(UUID).isEmpty());
    }

    @Test
    void historySearchesByNameFiltersPlayersAndShowsTheName() {
        List<AgentActionRow> rows = List.of(
                row("mc.rights.read", Map.of("player", UUID)),
                row("quest.list", Map.of()));
        String players = ActionsPages.list(rows, List.of(),
                ActionsPages.Query.parse(Map.of("domain", "players")), NOW, names());
        assertTrue(players.contains("Lire les droits Minecraft réellement portés par un joueur"));
        assertFalse(players.contains("Rafraîchir le catalogue de quêtes"));
        assertTrue(players.contains(">LoDyMcFly</span>"), "pseudo en cible principale");
        assertTrue(players.contains(UUID), "UUID toujours disponible");

        String byName = ActionsPages.list(rows, List.of(), ActionsPages.Query.parse(Map.of("q", "lodymc")), NOW, names());
        assertTrue(byName.contains("Lire les droits Minecraft"), "recherche par pseudo sur une action à UUID");
        String byUuid = ActionsPages.list(rows, List.of(), ActionsPages.Query.parse(Map.of("q", UUID)), NOW, names());
        assertTrue(byUuid.contains("Lire les droits Minecraft"), "recherche par UUID conservée");

        String failed = ActionsPages.list(rows, List.of(), ActionsPages.Query.parse(Map.of("status", "failed")), NOW, names());
        assertFalse(failed.contains("Lire les droits Minecraft"), "filtre statut conservé");
    }

    @Test
    void notificationAndToastUseTheName() {
        AgentActionRow a = row("mc.rights.read", Map.of("player", UUID));
        assertTrue(ActionView.notifItemHtml(a, NOW, names()).contains(">LoDyMcFly</span>"));
        assertTrue(ActionView.toastHtml(a, NOW, names()).contains("LoDyMcFly"));
        assertFalse(ActionView.toastHtml(a, NOW, names()).contains(UUID + " —"));
    }

    private static PlayerNames names() {
        return PlayerNames.fromCatalogDetails(Map.of("players", List.of(Map.of("uuid", UUID.toUpperCase(), "name", "LoDyMcFly"))));
    }

    private static AgentActionRow row(String type, Map<String, String> params) {
        return new AgentActionRow(type + "-id", "agent", type, params, AgentActionStatus.SUCCESS,
                NOW.minusSeconds(120), "owner", null, 0, NOW, "SUCCESS", "", "ok", null);
    }
}
