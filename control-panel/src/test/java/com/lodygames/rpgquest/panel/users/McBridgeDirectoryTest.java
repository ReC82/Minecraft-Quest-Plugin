package com.lodygames.rpgquest.panel.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.authz.McRight;
import com.lodygames.rpgquest.panel.authz.PanelGroup;
import com.lodygames.rpgquest.panel.authz.Permission;
import com.lodygames.rpgquest.panel.authz.Role;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Liaison compte ↔ joueur et droits Minecraft par groupe (issue #200), sur une vraie base SQLite —
 * parce que deux des garanties sont des <strong>contraintes d'unicité en base</strong> et qu'un
 * double en mémoire ne les prouverait pas.
 */
class McBridgeDirectoryTest {

    @TempDir
    Path tmp;

    private McBridgeDirectory bridge;
    private McBridgeRepository repo;

    private static final String UUID_A = "11111111-1111-1111-1111-111111111111";
    private static final String UUID_B = "22222222-2222-2222-2222-222222222222";

    @BeforeEach
    void setUp() {
        repo = new McBridgeRepository(tmp.resolve("cp.db").toString());
        bridge = new McBridgeDirectory(repo);
    }

    private static PanelUser user(String name) {
        return new PanelUser(name + "-id", name, "hash", Role.READ_ONLY, true, Instant.now(), null);
    }

    // ---- Liaison -------------------------------------------------------------------------------

    @Test
    void anAdministratorLinksAnAccountToAPlayerByUuid() {
        PanelUser steve = user("steve");

        McBridgeDirectory.Outcome o = bridge.link(steve, UUID_A, "Steve", "owner");

        assertTrue(o.ok(), o.error());
        assertEquals(UUID_A, o.after().mcUuid());
        assertEquals("Steve", o.after().mcName());
        assertEquals("owner", o.after().linkedBy());
        assertEquals(UUID_A, bridge.linkOf(steve.id()).orElseThrow().mcUuid());
    }

    @Test
    void anOfflinePlayerCanBeLinkedBecauseOnlyTheUuidIsNeeded() {
        // Volontaire : préparer les droits d'un builder avant son arrivée doit être possible.
        PanelUser steve = user("steve");

        assertTrue(bridge.link(steve, UUID_A, null, "owner").ok());
        assertTrue(bridge.linkOf(steve.id()).orElseThrow().mcName() == null,
                "le pseudonyme est facultatif : il est informatif, pas une identité");
    }

    @Test
    void anInvalidUuidIsRefusedWithAReadableReason() {
        PanelUser steve = user("steve");

        for (String bad : new String[] {"", "   ", "Steve", "1234", UUID_A + "x"}) {
            McBridgeDirectory.Outcome o = bridge.link(steve, bad, null, "owner");
            assertFalse(o.ok(), "« " + bad + " » doit être refusé");
            assertTrue(o.error().contains("UUID"), o.error());
        }
        assertTrue(bridge.linkOf(steve.id()).isEmpty(), "aucune liaison partielle");
    }

    @Test
    void thesSamePlayerCannotBeLinkedToTwoAccounts() {
        // Conflit IMPOSSIBLE et pas seulement déconseillé : sans cette garantie, on ne saurait plus
        // quels droits appliquer à ce joueur.
        PanelUser steve = user("steve");
        PanelUser alex = user("alex");
        assertTrue(bridge.link(steve, UUID_A, "Steve", "owner").ok());

        McBridgeDirectory.Outcome o = bridge.link(alex, UUID_A, "Steve", "owner");

        assertFalse(o.ok());
        assertTrue(o.error().contains("déjà lié"), o.error());
        assertTrue(bridge.linkOf(alex.id()).isEmpty());
        assertEquals(UUID_A, bridge.linkOf(steve.id()).orElseThrow().mcUuid(), "l'autre liaison est intacte");
    }

    @Test
    void anAccountHasOnlyOnePlayerAndModifyingItReplacesTheLink() {
        PanelUser steve = user("steve");
        assertTrue(bridge.link(steve, UUID_A, "Steve", "owner").ok());

        McBridgeDirectory.Outcome o = bridge.link(steve, UUID_B, "Steve2", "owner");

        assertTrue(o.ok(), o.error());
        assertEquals(UUID_A, o.before().mcUuid(), "l'avant est conservé pour l'audit");
        assertEquals(UUID_B, o.after().mcUuid());
        assertEquals(1, bridge.links().size(), "une seule liaison par compte");
    }

    @Test
    void relinkingTheSamePlayerChangesNothingAndSaysSo() {
        PanelUser steve = user("steve");
        bridge.link(steve, UUID_A, "Steve", "owner");

        McBridgeDirectory.Outcome o = bridge.link(steve, UUID_A, "Steve", "owner");

        assertFalse(o.ok());
        assertTrue(o.error().contains("déjà lié à ce joueur"), o.error());
    }

    @Test
    void unlinkingRemovesTheLinkAndKeepsTheBeforeForAudit() {
        PanelUser steve = user("steve");
        bridge.link(steve, UUID_A, "Steve", "owner");

        McBridgeDirectory.Outcome o = bridge.unlink(steve);

        assertTrue(o.ok(), o.error());
        assertEquals(UUID_A, o.before().mcUuid());
        assertTrue(bridge.linkOf(steve.id()).isEmpty());
    }

    @Test
    void unlinkingAnUnlinkedAccountIsRefusedRatherThanSilentlyIgnored() {
        assertFalse(bridge.unlink(user("steve")).ok());
    }

    @Test
    void aFreedPlayerCanBeLinkedToAnotherAccount() {
        PanelUser steve = user("steve");
        PanelUser alex = user("alex");
        bridge.link(steve, UUID_A, "Steve", "owner");
        bridge.unlink(steve);

        assertTrue(bridge.link(alex, UUID_A, "Steve", "owner").ok());
    }

    // ---- Droits Minecraft d'un groupe ----------------------------------------------------------

    @Test
    void aGroupCarriesManagedRightsWithTheirWorld() {
        McBridgeDirectory.NodeOutcome o = bridge.setNodes("g1", Set.of(
                new McBridgeRepository.GroupNode("g1", "rpgquest.build.hub.world_hub", "world_hub"),
                new McBridgeRepository.GroupNode("g1", "rpgquest.admin.npc", "")));

        assertTrue(o.ok(), o.error());
        assertEquals(2, bridge.nodesOf("g1").size());
    }

    @Test
    void aBuildRightWithoutAWorldIsRefused() {
        // C'est le cœur de « par monde » : sans monde, autoriser le Hub autoriserait les claims.
        McBridgeDirectory.NodeOutcome o = bridge.setNodes("g1", Set.of(
                new McBridgeRepository.GroupNode("g1", "rpgquest.build.hub.world_hub", "")));

        assertFalse(o.ok());
        assertTrue(o.error().contains("exige un monde"), o.error());
        assertTrue(bridge.nodesOf("g1").isEmpty(), "rien n'est écrit partiellement");
    }

    @Test
    void theLegacyUmbrellaIsNotDistributableFromAGroup() {
        McBridgeDirectory.NodeOutcome o = bridge.setNodes("g1", Set.of(
                new McBridgeRepository.GroupNode("g1", "rpgquest.admin.world", "")));

        assertFalse(o.ok());
        assertTrue(o.error().contains("rpgquest.admin.world"), o.error());
        assertTrue(bridge.nodesOf("g1").isEmpty());
    }

    @Test
    void anUnknownOrExternalNodeIsRefused() {
        for (String node : new String[] {"citizens.npc.create", "essentials.fly", "rpgquest.money",
                "rpgquest.admin.debug", "n'importe.quoi"}) {
            McBridgeDirectory.NodeOutcome o = bridge.setNodes("g1", Set.of(
                    new McBridgeRepository.GroupNode("g1", node, "")));
            assertFalse(o.ok(), node);
        }
        assertTrue(bridge.nodesOf("g1").isEmpty());
    }

    @Test
    void replacingTheRightsOfAGroupIsExactAndNotAdditive() {
        bridge.setNodes("g1", Set.of(
                new McBridgeRepository.GroupNode("g1", "rpgquest.bypass.zone", ""),
                new McBridgeRepository.GroupNode("g1", "rpgquest.bypass.claim", "")));

        McBridgeDirectory.NodeOutcome o = bridge.setNodes("g1", Set.of(
                new McBridgeRepository.GroupNode("g1", "rpgquest.bypass.zone", "")));

        assertTrue(o.ok(), o.error());
        assertEquals(1, bridge.nodesOf("g1").size());
        assertEquals("rpgquest.bypass.zone", bridge.nodesOf("g1").get(0).node());
        assertEquals(2, o.before().size(), "l'avant est conservé pour l'audit");
    }

    @Test
    void emptyingAGroupRemovesAllItsRights() {
        bridge.setNodes("g1", Set.of(new McBridgeRepository.GroupNode("g1", "rpgquest.bypass.zone", "")));

        assertTrue(bridge.setNodes("g1", Set.of()).ok());

        assertTrue(bridge.nodesOf("g1").isEmpty());
    }

    @Test
    void theSameNodeInTwoWorldsAreTwoDistinctRights() {
        McBridgeDirectory.NodeOutcome o = bridge.setNodes("g1", Set.of(
                new McBridgeRepository.GroupNode("g1", "rpgquest.build.hub.world_hub", "world_hub"),
                new McBridgeRepository.GroupNode("g1", "rpgquest.build.hub.hub2", "hub2")));

        assertTrue(o.ok(), o.error());
        assertEquals(2, bridge.nodesOf("g1").size());
    }

    @Test
    void theDesiredStateOfAnAccountIsTheUnionOfItsGroups() {
        bridge.setNodes("g1", Set.of(
                new McBridgeRepository.GroupNode("g1", "rpgquest.build.hub.world_hub", "world_hub")));
        bridge.setNodes("g2", Set.of(
                new McBridgeRepository.GroupNode("g2", "rpgquest.admin.npc", "")));

        List<McBridgeRepository.GroupNode> desired = bridge.desiredFor(List.of(
                new PanelGroup("g1", "Builders", "", Set.of(Permission.DOCS_READ), Instant.now()),
                new PanelGroup("g2", "PNJ", "", Set.of(Permission.NPC_WRITE), Instant.now())));

        assertEquals(2, desired.size());
    }

    @Test
    void forgettingAGroupRemovesItsMinecraftRights() {
        bridge.setNodes("g1", Set.of(new McBridgeRepository.GroupNode("g1", "rpgquest.bypass.zone", "")));

        bridge.forgetGroup("g1");

        assertTrue(bridge.nodesOf("g1").isEmpty());
        assertTrue(repo.groupsWithNodes().isEmpty());
    }

    @Test
    void everythingSurvivesAReopeningOfTheDatabase() {
        PanelUser steve = user("steve");
        bridge.link(steve, UUID_A, "Steve", "owner");
        bridge.setNodes("g1", Set.of(
                new McBridgeRepository.GroupNode("g1", "rpgquest.build.hub.world_hub", "world_hub")));

        McBridgeDirectory reopened = new McBridgeDirectory(
                new McBridgeRepository(tmp.resolve("cp.db").toString()));

        assertEquals(UUID_A, reopened.linkOf(steve.id()).orElseThrow().mcUuid());
        assertEquals("world_hub", reopened.nodesOf("g1").get(0).world());
    }

    // ---- Catalogue des droits offerts ----------------------------------------------------------

    @Test
    void theCatalogueOffersOneBuildRightPerKnownWorldAndNothingInvented() {
        List<McRight.Definition> catalogue = McRight.catalogue(List.of("world_hub", "claims", ""));

        long hubRights = catalogue.stream()
                .filter(d -> d.node().startsWith(McRight.BUILD_HUB_PREFIX)
                        && !d.node().equals(McRight.BUILD_HUB_ALL))
                .count();
        assertEquals(2, hubRights, "un droit par monde non vide, jamais un monde deviné");
    }

    @Test
    void theCatalogueNeverOffersTheUmbrella() {
        assertFalse(McRight.isOffered("rpgquest.admin.world"));
        assertFalse(McRight.isOffered("rpgquest.admin.debug"));
        assertTrue(McRight.isOffered("rpgquest.build.hub.anything"));
        assertTrue(McRight.isOffered("rpgquest.bypass.claimworld"));
    }

    @Test
    void aBuildRightKnowsItNeedsAWorldAndTheOthersDoNot() {
        assertTrue(McRight.byNode("rpgquest.build.hub.world_hub").orElseThrow().family().worldRequired());
        assertFalse(McRight.byNode("rpgquest.bypass.zone").orElseThrow().family().worldRequired());
        assertFalse(McRight.byNode("rpgquest.admin.npc").orElseThrow().family().worldRequired());
    }

    @Test
    void anAlreadyGrantedHubRightStaysReadableEvenIfItsWorldIsUnknownNow() {
        // Si l'agent n'a pas répondu, les mondes connus sont vides — un droit déjà accordé ne doit
        // pas disparaître de l'écran pour autant.
        assertTrue(McRight.byNode("rpgquest.build.hub.un_monde_disparu").isPresent());
    }
}
