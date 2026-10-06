package com.lodygames.rpgquest.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Provenance des droits gérés par le pont (issue #200).
 *
 * <p><strong>Le défaut que ces tests ferment.</strong> La première version du pont reconnaissait
 * « ses » droits à un <em>préfixe de nœud</em>. C'est faux : un administrateur peut accorder à la
 * main {@code rpgquest.build.hub.world_hub} dans le contexte {@code world=world_hub} — un nœud
 * <strong>identique</strong> à celui du pont, même contexte compris. Une synchronisation ne le
 * trouvant pas dans l'état voulu l'aurait supprimé, détruisant une décision externe sans signal.</p>
 *
 * <p>La provenance est donc <strong>structurelle</strong> : les droits gérés vivent dans des groupes
 * LuckPerms portant un préfixe réservé, et le pont ne manipule sur un utilisateur que des nœuds
 * d'<em>héritage</em> {@code group.rpgq-…}. Un droit externe n'est pas au même endroit, donc jamais
 * touché — par construction, et non par vigilance.</p>
 */
class BridgeProvenanceTest {

    // ---- Nommage des groupes du pont -----------------------------------------------------------

    @Test
    void aBridgeGroupNameDerivesFromTheStablePanelGroupId() {
        // L'identifiant et non le libellé : renommer un groupe dans le panel ne doit pas orpheliner
        // le groupe LuckPerms ni faire perdre ses droits à ses membres.
        String name = BridgeGroupNaming.groupNameFor("3f2a1b4c-5d6e-7f80-9123-456789abcdef");

        assertEquals("rpgq-3f2a1b4c5d6e7f809123456789abcdef", name);
        assertTrue(BridgeGroupNaming.isBridgeGroup(name));
    }

    @Test
    void aGroupWithoutTheReservedPrefixIsNeverOurs() {
        // Le point central : un groupe LuckPerms externe qui accorderait EXACTEMENT les mêmes
        // droits n'appartient pas au pont, donc n'est ni modifié ni retiré.
        for (String external : new String[] {"builders", "vip", "staff", "rpgquest-builders",
                "default", "", "RPGQ_builders"}) {
            assertFalse(BridgeGroupNaming.isBridgeGroup(external),
                    () -> "« " + external + " » ne doit pas être reconnu comme un groupe du pont");
        }
        assertFalse(BridgeGroupNaming.isBridgeGroup(null));
    }

    @Test
    void theReservedPrefixIsCaseInsensitiveOnRead() {
        // LuckPerms normalise les noms de groupe en minuscules ; rester tolérant à la lecture évite
        // de laisser un groupe du pont orphelin à cause d'une casse inattendue.
        assertTrue(BridgeGroupNaming.isBridgeGroup("RPGQ-abc"));
        assertTrue(BridgeGroupNaming.isBridgeGroup("rpgq-abc"));
    }

    // ---- Ce que le pont a le droit de toucher --------------------------------------------------

    @Test
    void onlyTheDeclaredFamiliesAreManaged() {
        assertTrue(ManagedNodePolicy.isManaged("rpgquest.build.hub.world_hub"));
        assertTrue(ManagedNodePolicy.isManaged("rpgquest.build.hub.*"));
        assertTrue(ManagedNodePolicy.isManaged("rpgquest.build.wild"));
        assertTrue(ManagedNodePolicy.isManaged("rpgquest.bypass.claim"));
        assertTrue(ManagedNodePolicy.isManaged("rpgquest.bypass.zone"));
        assertTrue(ManagedNodePolicy.isManaged("rpgquest.bypass.claimworld"));
        assertTrue(ManagedNodePolicy.isManaged("rpgquest.admin.command"));
        assertTrue(ManagedNodePolicy.isManaged("rpgquest.admin.npc"));
        assertTrue(ManagedNodePolicy.isManaged("rpgquest.admin.npc.tag"));
    }

    @Test
    void theLegacyUmbrellaIsNeverDistributableByTheBridge() {
        // Elle donne TOUT : la laisser cocher depuis un groupe serait une élévation de privilège
        // en un clic, et contredirait « ne jamais l'attribuer aux groupes builder ou éditeur PNJ ».
        assertFalse(ManagedNodePolicy.isManaged(RpgPermissions.LEGACY_ADMIN_WORLD));
        String reason = ManagedNodePolicy.refusalReason(RpgPermissions.LEGACY_ADMIN_WORLD);
        assertNotNull(reason);
        assertTrue(reason.contains("à la main"), reason);
    }

    @Test
    void lowLevelDebugWritesAreNeverDistributableEither() {
        assertFalse(ManagedNodePolicy.isManaged("rpgquest.admin.debug"));
        assertNotNull(ManagedNodePolicy.refusalReason("rpgquest.admin.debug"));
    }

    @Test
    void externalNodesAreRefusedWithAReadableReason() {
        for (String external : new String[] {"citizens.npc.create", "essentials.fly",
                "worldedit.wand", "rpgquest.money", "rpgquest.claim", "vip.kit"}) {
            assertFalse(ManagedNodePolicy.isManaged(external), external);
            String reason = ManagedNodePolicy.refusalReason(external);
            assertNotNull(reason, external);
            assertTrue(reason.contains("externe"), () -> external + " : " + reason);
        }
    }

    @Test
    void aManagedNodeHasNoRefusalReason() {
        assertNull(ManagedNodePolicy.refusalReason("rpgquest.build.hub.world_hub"));
    }

    @Test
    void anEmptyNodeIsRefused() {
        assertFalse(ManagedNodePolicy.isManaged(null));
        assertFalse(ManagedNodePolicy.isManaged("   "));
        assertNotNull(ManagedNodePolicy.refusalReason(null));
    }

    // ---- Forme d'un droit géré -----------------------------------------------------------------

    @Test
    void aManagedNodeNormalisesItsCaseAndBlankWorld() {
        ManagedNode global = new ManagedNode("  RPGQuest.Build.Wild  ", "   ");

        assertEquals("rpgquest.build.wild", global.node());
        assertTrue(global.isGlobal(), "un monde vide vaut « partout », jamais une chaîne vide");
        assertNull(global.world());
    }

    @Test
    void twoManagedNodesAreEqualOnlyIfTheirWorldMatchesToo() {
        ManagedNode hub = ManagedNode.inWorld("rpgquest.build.hub.world_hub", "world_hub");
        ManagedNode other = ManagedNode.inWorld("rpgquest.build.hub.world_hub", "claims");
        ManagedNode global = ManagedNode.global("rpgquest.build.hub.world_hub");

        // C'est ce qui rend la synchronisation par monde possible : le contexte fait partie de
        // l'identité du droit, sinon « construire dans le Hub » et « partout » se confondraient.
        assertFalse(hub.equals(other));
        assertFalse(hub.equals(global));
        assertEquals(hub, ManagedNode.inWorld("rpgquest.build.hub.world_hub", "world_hub"));
    }

    @Test
    void theDescriptionNamesTheWorldWhenThereIsOne() {
        assertEquals("rpgquest.build.hub.world_hub (monde world_hub)",
                ManagedNode.inWorld("rpgquest.build.hub.world_hub", "world_hub").describe());
        assertEquals("rpgquest.bypass.zone", ManagedNode.global("rpgquest.bypass.zone").describe());
    }

    // ---- Absence de LuckPerms : état réel, jamais un faux succès -------------------------------

    @Test
    void withoutLuckPermsTheBridgeSaysSoInsteadOfPretending() {
        // Au test, l'API est présente à la compilation mais aucune implémentation n'est chargée :
        // c'est exactement la situation d'un serveur sans LuckPerms.
        LuckPermsBridge bridge = new LuckPermsBridge(org.slf4j.LoggerFactory.getLogger("test"));

        LuckPermsBridge.Availability availability = bridge.availability();

        assertFalse(availability.available());
        assertNotNull(availability.reason());
        assertFalse(availability.reason().isBlank(), "un motif vide n'aiderait personne");
    }

    @Test
    void everyWriteFailsLoudlyWhenLuckPermsIsAbsent() throws Exception {
        LuckPermsBridge bridge = new LuckPermsBridge(org.slf4j.LoggerFactory.getLogger("test"));
        java.util.UUID player = java.util.UUID.randomUUID();

        LuckPermsBridge.SyncResult users = bridge.syncUserGroups(player, java.util.Set.of("g1")).get();
        LuckPermsBridge.SyncResult group = bridge.syncGroupDefinition("g1", "Builders",
                java.util.Set.of(ManagedNode.inWorld("rpgquest.build.hub.world_hub", "world_hub"))).get();
        LuckPermsBridge.SyncResult deleted = bridge.deleteGroup("g1").get();

        // Aucun faux succès : l'administrateur doit savoir que rien n'a été appliqué.
        for (LuckPermsBridge.SyncResult result : List.of(users, group, deleted)) {
            assertFalse(result.ok());
            assertNotNull(result.message());
            assertTrue(result.added().isEmpty());
            assertTrue(result.removed().isEmpty());
        }
    }

    @Test
    void aRefusedNodeNeverReachesLuckPerms() throws Exception {
        LuckPermsBridge bridge = new LuckPermsBridge(org.slf4j.LoggerFactory.getLogger("test"));

        LuckPermsBridge.SyncResult result = bridge.syncGroupDefinition("g1", "Pouvoirs",
                java.util.Set.of(ManagedNode.global(RpgPermissions.LEGACY_ADMIN_WORLD))).get();

        assertFalse(result.ok());
        assertTrue(result.removed().isEmpty());
    }
}
