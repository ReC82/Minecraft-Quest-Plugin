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

        assertTrue(BridgeGroupNaming.isBridgeGroup(name));
        assertEquals(name, BridgeGroupNaming.groupNameFor("3f2a1b4c-5d6e-7f80-9123-456789abcdef"),
                "déterministe : le même identifiant donne toujours le même nom");
    }

    @Test
    void aRealPanelGroupIdProducesANameLuckPermsAccepts() {
        // CAS RÉEL constaté depuis le panel : le groupe « tc253_builder »
        // (8cb1178f-f6ae-4835-b495-d1004724f771) faisait échouer mc.group.sync sur
        // IllegalArgumentException. Cause mesurée : LuckPerms impose
        // MAX_GROUP_NAME_LENGTH = 36, et la première version concaténait le préfixe aux 32
        // caractères hexadécimaux de l'identifiant — soit 37. Le défaut était invisible avec la
        // sonde de validation, qui utilisait un identifiant court (« rpgq-tc253probe », 15).
        String name = BridgeGroupNaming.groupNameFor("8cb1178f-f6ae-4835-b495-d1004724f771");

        assertTrue(name.length() <= BridgeGroupNaming.MAX_LUCKPERMS_GROUP_NAME,
                () -> "nom trop long pour LuckPerms : " + name + " (" + name.length() + ")");
        assertNull(BridgeGroupNaming.refusalReason(name), name);
        assertTrue(BridgeGroupNaming.isBridgeGroup(name));
    }

    @Test
    void anyUuidShapedIdentifierFitsWithinTheLuckPermsLimit() {
        // Un seul identifiant qui passe ne prouverait rien : on vérifie la forme générale.
        for (int i = 0; i < 200; i++) {
            String name = BridgeGroupNaming.groupNameFor(java.util.UUID.randomUUID().toString());
            assertTrue(name.length() <= BridgeGroupNaming.MAX_LUCKPERMS_GROUP_NAME,
                    () -> "nom trop long : " + name);
            assertNull(BridgeGroupNaming.refusalReason(name));
        }
    }

    @Test
    void twoDifferentGroupsNeverShareAName() {
        // Une troncature de l'identifiant aurait collisionné sur deux identifiants de même préfixe ;
        // une empreinte répartit uniformément. Ce test ancre la propriété, pas la méthode.
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < 500; i++) {
            assertTrue(names.add(BridgeGroupNaming.groupNameFor(java.util.UUID.randomUUID().toString())),
                    "deux groupes distincts ne doivent jamais partager un nom LuckPerms");
        }
    }

    @Test
    void anOverlongNameIsRefusedWithAReadableReasonRatherThanAnException() {
        // C'est ce qui manquait : le panel affichait « IllegalArgumentException », ce qui ne dit
        // rien. Un motif explicite permet de comprendre sans lire les logs du serveur.
        String tooLong = "rpgq-" + "a".repeat(40);

        String reason = BridgeGroupNaming.refusalReason(tooLong);

        assertNotNull(reason);
        assertTrue(reason.contains("trop long"), reason);
        assertTrue(reason.contains("36"), () -> "le motif doit citer la limite réelle : " + reason);
    }

    @Test
    void aBlankNameIsRefusedToo() {
        assertNotNull(BridgeGroupNaming.refusalReason(null));
        assertNotNull(BridgeGroupNaming.refusalReason("   "));
    }

    @Test
    void aGroupWithoutAnyMemberOrPanelPermissionStillSyncsItsMinecraftRight() throws Exception {
        // Le groupe réel n'avait AUCUN membre et AUCUNE permission PlugAdmin : seul un droit
        // Minecraft. Rien dans ce chemin ne doit dépendre d'un membre — la définition du groupe
        // LuckPerms se pousse indépendamment de qui y appartient.
        LuckPermsBridge bridge = new LuckPermsBridge(org.slf4j.LoggerFactory.getLogger("test"));

        LuckPermsBridge.SyncResult result = bridge.syncGroupDefinition(
                "8cb1178f-f6ae-4835-b495-d1004724f771", "tc253_builder",
                java.util.Set.of(ManagedNode.inWorld("rpgquest.build.hub.world_hub", "world_hub"))).get();

        // Sans LuckPerms au test, l'issue est un échec HONNÊTE — mais pas celui du nom : le motif
        // doit être l'indisponibilité du pont, et non un refus de longueur.
        assertFalse(result.ok());
        assertFalse(result.message().contains("trop long"), result.message());
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
