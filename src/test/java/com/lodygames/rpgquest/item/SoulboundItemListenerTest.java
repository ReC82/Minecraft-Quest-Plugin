package com.lodygames.rpgquest.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Mission « système soulbound générique » : couvre {@link SoulboundItemService}/
 * {@link SoulboundItemListener} directement (évènements construits à la main puis passés aux
 * gestionnaires par réflexion, sans passer par le bus d'évènements réel — même patron que
 * l'ancien {@code ReturnStoneGuardListenerTest} qu'il remplace).
 */
class SoulboundItemListenerTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World world;
    private YamlCustomItemRegistry customItemRegistry;
    private SoulboundItemService service;
    private SoulboundItemListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        world = server.addSimpleWorld("world");

        customItemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        customItemRegistry.start();

        service = new SoulboundItemService(customItemRegistry);
        service.register(RpgItemKeys.PIERRE_RETOUR);
        service.register(RpgItemKeys.ACTE_PROPRIETE);
        service.register(RpgItemKeys.JOURNAL_QUETES);
        service.register(RpgItemKeys.RUNE_RAPPEL);
        listener = (SoulboundItemListener) service.listener();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerMock addPlayer() {
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 0.5, 61, 0.5));
        return player;
    }

    private ItemStack soulbound(NamespacedKey id) {
        return customItemRegistry.create(id, 1).orElseThrow();
    }

    private long countOf(PlayerMock player, NamespacedKey id) {
        return Arrays.stream(player.getInventory().getContents())
                .filter(Objects::nonNull)
                .filter(stack -> customItemRegistry.identify(stack).map(id::equals).orElse(false))
                .count();
    }

    @Test
    void droppingAnySoulboundItemIsCancelled() {
        PlayerMock player = addPlayer();
        for (NamespacedKey id : List.of(RpgItemKeys.PIERRE_RETOUR, RpgItemKeys.ACTE_PROPRIETE,
                RpgItemKeys.JOURNAL_QUETES, RpgItemKeys.RUNE_RAPPEL)) {
            Item dropped = world.dropItem(player.getLocation(), soulbound(id));
            PlayerDropItemEvent event = new PlayerDropItemEvent(player, dropped);
            listener.onDrop(event);
            assertTrue(event.isCancelled(), "un objet soulbound ne doit jamais pouvoir être jeté : " + id);
        }
    }

    @Test
    void droppingAnUnrelatedItemIsNeverCancelled() {
        PlayerMock player = addPlayer();
        Item dropped = world.dropItem(player.getLocation(), new ItemStack(Material.DIAMOND));
        PlayerDropItemEvent event = new PlayerDropItemEvent(player, dropped);
        listener.onDrop(event);
        assertFalse(event.isCancelled());
    }

    @Test
    void soulboundItemsAreRemovedFromDeathDropsAndRestoredOnRespawnWithoutDuplication() {
        PlayerMock player = addPlayer();
        List<ItemStack> drops = new ArrayList<>();
        drops.add(soulbound(RpgItemKeys.JOURNAL_QUETES));
        drops.add(soulbound(RpgItemKeys.RUNE_RAPPEL));
        drops.add(new ItemStack(Material.DIAMOND, 3));

        PlayerDeathEvent death = new PlayerDeathEvent(
                player, DamageSource.builder(DamageType.GENERIC).build(), drops, 0, Component.text("t"), false);
        listener.onDeath(death);

        assertFalse(death.getDrops().stream().anyMatch(customItemRegistry::isCustomItem),
                "aucun objet soulbound ne doit tomber au sol");
        assertTrue(death.getDrops().stream().anyMatch(s -> s.getType() == Material.DIAMOND));

        listener.onRespawn(new PlayerRespawnEvent(player, player.getLocation(), false, false, false,
                PlayerRespawnEvent.RespawnReason.DEATH));

        assertEquals(1, countOf(player, RpgItemKeys.JOURNAL_QUETES));
        assertEquals(1, countOf(player, RpgItemKeys.RUNE_RAPPEL));
    }

    @Test
    void respawningWithoutAPriorDeathNeverGrantsAnything() {
        PlayerMock player = addPlayer();
        player.getInventory().addItem(soulbound(RpgItemKeys.PIERRE_RETOUR));

        listener.onRespawn(new PlayerRespawnEvent(player, player.getLocation(), false, false, false,
                PlayerRespawnEvent.RespawnReason.DEATH));

        assertEquals(1, countOf(player, RpgItemKeys.PIERRE_RETOUR), "jamais de duplication sans mort préalable");
    }

    @Test
    void aDeathWithoutSoulboundItemsRestoresNothing() {
        PlayerMock player = addPlayer();
        List<ItemStack> drops = new ArrayList<>();
        drops.add(new ItemStack(Material.DIAMOND, 3));

        listener.onDeath(new PlayerDeathEvent(
                player, DamageSource.builder(DamageType.GENERIC).build(), drops, 0, Component.text("t"), false));
        listener.onRespawn(new PlayerRespawnEvent(player, player.getLocation(), false, false, false,
                PlayerRespawnEvent.RespawnReason.DEATH));

        assertEquals(0, countOf(player, RpgItemKeys.PIERRE_RETOUR));
    }
}
