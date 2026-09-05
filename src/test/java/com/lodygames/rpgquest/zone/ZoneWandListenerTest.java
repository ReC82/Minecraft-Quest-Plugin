package com.lodygames.rpgquest.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Couvre le bug constaté en test réel : {@code /rpgadmin zone wand} donnait auparavant une hache en
 * bois ({@code Material.WOODEN_AXE}), le même item type que la wand par défaut de WorldEdit
 * (config {@code wand-item}). WorldEdit reconnaît sa wand par matériau, pas par PDC, donc il traitait
 * aussi les clics faits avec l'outil RPGQuest — posant sa propre sélection et parfois annulant
 * l'événement avant que {@link ZoneWandListener} ne le voie. La sélection RPGQuest n'était alors
 * jamais enregistrée, et {@code /rpgadmin worldportal create} échouait avec « Sélectionnez d'abord
 * deux positions ». Voir le commentaire sur {@link ZoneSelectionService#createWandItem()}.
 */
@SuppressWarnings("deprecation")
// PlayerInteractEvent#isCancelled() est dépréciée côté API (sémantique ambiguë bloc/objet), même
// dépréciation déjà documentée pour ClaimProtectionListenerTest ; reste la seule façon de lire l'état
// d'annulation d'un événement construit à la main dans un test.
class ZoneWandListenerTest {

    private ServerMock server;
    private World world;
    private ZoneSelectionService selectionService;
    private ZoneWandListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        selectionService = new ZoneSelectionService();
        listener = new ZoneWandListener(selectionService);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void leftClickWithTheZoneWandRecordsPosition1() {
        PlayerMock player = server.addPlayer();
        Block block = world.getBlockAt(1, 64, 2);
        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.LEFT_CLICK_BLOCK, selectionService.createWandItem(), block, null, EquipmentSlot.HAND);

        listener.onInteract(event);

        assertTrue(event.isCancelled(), "l'outil ne doit jamais casser le bloc visé");
        var pos1 = selectionService.pos1(player.getUniqueId());
        assertTrue(pos1.isPresent());
        assertEquals(block.getLocation(), pos1.get());
    }

    @Test
    void rightClickWithTheZoneWandRecordsPosition2() {
        PlayerMock player = server.addPlayer();
        Block block = world.getBlockAt(5, 70, -3);
        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, selectionService.createWandItem(), block, null, EquipmentSlot.HAND);

        listener.onInteract(event);

        assertTrue(event.isCancelled());
        var pos2 = selectionService.pos2(player.getUniqueId());
        assertTrue(pos2.isPresent());
        assertEquals(block.getLocation(), pos2.get());
    }

    @Test
    void bothClicksTogetherLeaveASelectionUsableByWorldportalCreate() {
        // Précondition exacte vérifiée par RpgAdminCommand#handlePortalCreate avant de créer un
        // portail : pos1 et pos2 doivent toutes les deux être présentes pour le même joueur.
        PlayerMock player = server.addPlayer();
        Block first = world.getBlockAt(0, 64, 0);
        Block second = world.getBlockAt(10, 70, 10);
        ItemStack wand = selectionService.createWandItem();

        listener.onInteract(new PlayerInteractEvent(
                player, Action.LEFT_CLICK_BLOCK, wand, first, null, EquipmentSlot.HAND));
        listener.onInteract(new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, wand, second, null, EquipmentSlot.HAND));

        assertTrue(selectionService.pos1(player.getUniqueId()).isPresent());
        assertTrue(selectionService.pos2(player.getUniqueId()).isPresent());
    }

    @Test
    void aRegularItemIsNeverTreatedAsTheZoneWand() {
        PlayerMock player = server.addPlayer();
        Block block = world.getBlockAt(1, 64, 2);
        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.LEFT_CLICK_BLOCK, new ItemStack(Material.BLAZE_ROD), block, null, EquipmentSlot.HAND);

        listener.onInteract(event);

        assertFalse(event.isCancelled(), "un item du même matériau mais sans le PDC de la wand n'est pas l'outil RPGQuest");
        assertFalse(selectionService.pos1(player.getUniqueId()).isPresent());
    }

    @Test
    void anAlreadyCancelledInteractionStillRegistersTheSelection() {
        // Reproduit le cas racine du bug : un autre plugin (WorldEdit, une protection de zone, ...)
        // annule l'interaction avant nous. La wand RPGQuest doit rester utilisable dans ce cas —
        // c'est pourquoi ZoneWandListener écoute désormais avec ignoreCancelled = false.
        PlayerMock player = server.addPlayer();
        Block block = world.getBlockAt(1, 64, 2);
        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.LEFT_CLICK_BLOCK, selectionService.createWandItem(), block, null, EquipmentSlot.HAND);
        event.setCancelled(true);

        listener.onInteract(event);

        assertTrue(selectionService.pos1(player.getUniqueId()).isPresent());
    }

    @Test
    void createdWandItemIsNotAWoodenAxeToAvoidCollidingWithWorldEditsDefaultWand() {
        assertFalse(selectionService.createWandItem().getType() == Material.WOODEN_AXE,
                "WorldEdit reconnaît minecraft:wooden_axe comme sa propre wand par défaut (config wand-item)");
    }
}
