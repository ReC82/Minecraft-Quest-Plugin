package be.lloyd.rpgquest.item.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.item.YamlCustomItemRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Item;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

class ToolBehaviorListenerTest {

    private static final NamespacedKey PLAIN_PICK = new NamespacedKey("rpgquest", "plain_pick");
    private static final NamespacedKey ORE_PICK = new NamespacedKey("rpgquest", "ore_pick");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private YamlCustomItemRegistry registry;
    private AtomicLong clock;
    private CooldownManager cooldowns;
    private ToolBehaviorListener listener;
    private Path itemsDir;
    private World world;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        itemsDir = tempDir.resolve("items");
        Files.createDirectories(itemsDir);
        world = server.addSimpleWorld("world");

        writeTool("plain_pick.yml", "plain_pick", """
                tool:
                  durability-cost: 3
                """);
        writeTool("ore_pick.yml", "ore_pick", """
                tool:
                  allowed-blocks:
                    - IRON_ORE
                  harvest-bonus-chance: 1.0
                  harvest-bonus-amount: 1
                  special-ability:
                    ability-id: "rush"
                    cooldown-seconds: 10
                    activation-message: "<aqua>Go</aqua>"
                """);

        registry = new YamlCustomItemRegistry(itemsDir, NOPLogger.NOP_LOGGER);
        registry.start();

        clock = new AtomicLong(0L);
        cooldowns = new CooldownManager(clock::get);
        listener = new ToolBehaviorListener(registry, cooldowns, () -> false, NOPLogger.NOP_LOGGER);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void durabilityCostOverridesVanillaDefault() {
        ItemStack pick = registry.create(PLAIN_PICK, 1).orElseThrow();
        PlayerItemDamageEvent event = itemDamageEvent(server.addPlayer(), pick, 1);

        listener.onItemDamage(event);

        assertEquals(3, event.getDamage(), "le coût configuré doit remplacer le coût vanilla (1)");
    }

    @Test
    void cancelledDamageEventIsIgnored() {
        ItemStack pick = registry.create(PLAIN_PICK, 1).orElseThrow();
        PlayerItemDamageEvent event = itemDamageEvent(server.addPlayer(), pick, 1);
        event.setCancelled(true);

        listener.onItemDamage(event);

        assertEquals(1, event.getDamage(), "un événement déjà annulé par un autre plugin ne doit jamais être modifié");
    }

    @Test
    void itemAlreadyNearMaxDurabilityStillAppliesConfiguredCostWithoutError() {
        ItemStack pick = registry.create(PLAIN_PICK, 1).orElseThrow();
        var damageable = (org.bukkit.inventory.meta.Damageable) pick.getItemMeta();
        int maxDamage = damageable.getMaxDamage();
        damageable.setDamage(maxDamage); // durabilité à zéro : l'objet est sur le point de se briser
        pick.setItemMeta((ItemMeta) damageable);

        PlayerItemDamageEvent event = itemDamageEvent(server.addPlayer(), pick, 1);
        listener.onItemDamage(event);

        assertEquals(3, event.getDamage(), "le coût configuré doit s'appliquer même quand l'objet est déjà à 0 durabilité restante");
    }

    @Test
    void forgedToolIsIgnored() {
        ItemStack fake = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta meta = fake.getItemMeta();
        meta.displayName(MiniMessage.miniMessage().deserialize("Test"));
        fake.setItemMeta(meta);

        PlayerItemDamageEvent event = itemDamageEvent(server.addPlayer(), fake, 1);
        listener.onItemDamage(event);

        assertEquals(1, event.getDamage(), "un objet vanilla renommé pour imiter l'outil ne doit jamais être reconnu");
    }

    @Test
    void harvestBonusAppliesOnAllowedBlock() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(registry.create(ORE_PICK, 1).orElseThrow());

        // "before" capturé après la création du drop de base (par blockDrop), pas avant : on isole
        // uniquement l'effet du bonus ajouté par le listener, pas le drop naturel lui-même.
        BlockDropItemEvent event = blockDrop(player, Material.IRON_ORE, new ItemStack(Material.RAW_IRON));
        int before = world.getEntitiesByClass(Item.class).size();
        listener.onBlockDropItem(event);

        int after = world.getEntitiesByClass(Item.class).size();
        assertEquals(before + 1, after, "bonus de récolte : un exemplaire supplémentaire doit être déposé");
    }

    @Test
    void harvestBonusDoesNotApplyOnDisallowedBlock() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(registry.create(ORE_PICK, 1).orElseThrow());

        BlockDropItemEvent event = blockDrop(player, Material.STONE, new ItemStack(Material.COBBLESTONE));
        int before = world.getEntitiesByClass(Item.class).size();
        listener.onBlockDropItem(event);

        int after = world.getEntitiesByClass(Item.class).size();
        assertEquals(before, after, "un bloc hors de « allowed-blocks » ne doit déclencher aucun bonus");
    }

    @Test
    void cancelledBlockDropEventIsIgnored() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(registry.create(ORE_PICK, 1).orElseThrow());

        BlockDropItemEvent event = blockDrop(player, Material.IRON_ORE, new ItemStack(Material.RAW_IRON));
        int before = world.getEntitiesByClass(Item.class).size();
        event.setCancelled(true);
        listener.onBlockDropItem(event);

        assertEquals(before, world.getEntitiesByClass(Item.class).size(),
                "un événement déjà annulé par un autre plugin ne doit jamais produire de bonus");
    }

    @Test
    void specialAbilityHasACooldown() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(registry.create(ORE_PICK, 1).orElseThrow());
        String abilityKey = ORE_PICK + ":rush";

        listener.onInteract(interact(player, EquipmentSlot.HAND));
        assertTrue(player.nextMessage() != null, "la capacité doit s'activer et envoyer un message la première fois");
        assertTrue(!cooldowns.isReady(player.getUniqueId(), abilityKey));

        listener.onInteract(interact(player, EquipmentSlot.HAND));
        assertNull(player.nextMessage(), "un second clic pendant le cooldown ne doit rien déclencher");
    }

    @Test
    void offHandInteractIsIgnored() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInOffHand(registry.create(ORE_PICK, 1).orElseThrow());
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK));

        listener.onInteract(interact(player, EquipmentSlot.OFF_HAND));

        assertNull(player.nextMessage(), "une capacité spéciale en main secondaire ne doit jamais s'activer");
    }

    @Test
    void reloadingConfigurationChangesDurabilityCost() throws Exception {
        ItemStack pick = registry.create(PLAIN_PICK, 1).orElseThrow();
        listener.onItemDamage(itemDamageEvent(server.addPlayer(), pick, 1));

        writeTool("plain_pick.yml", "plain_pick", """
                tool:
                  durability-cost: 7
                """);
        registry.reload();

        ItemStack reloadedPick = registry.create(PLAIN_PICK, 1).orElseThrow();
        PlayerItemDamageEvent event = itemDamageEvent(server.addPlayer(), reloadedPick, 1);
        listener.onItemDamage(event);

        assertEquals(7, event.getDamage(), "le nouveau coût doit s'appliquer sans recréer le listener");
    }

    private PlayerItemDamageEvent itemDamageEvent(org.bukkit.entity.Player player, ItemStack item, int damage) {
        return new PlayerItemDamageEvent(player, item, damage, damage);
    }

    private BlockDropItemEvent blockDrop(PlayerMock player, Material blockType, ItemStack dropStack) {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(blockType);
        BlockState state = block.getState();
        Item itemEntity = world.dropItem(new Location(world, 0.5, 64, 0.5), dropStack);
        return new BlockDropItemEvent(block, state, player, List.of(itemEntity));
    }

    private PlayerInteractEvent interact(PlayerMock player, EquipmentSlot hand) {
        ItemStack item = hand == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand() : player.getInventory().getItemInOffHand();
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, item, null, null, hand);
    }

    private void writeTool(String fileName, String id, String toolYaml) throws Exception {
        String yaml = """
                id: rpgquest:%s
                type: TOOL
                material: DIAMOND_PICKAXE
                name: "Test"
                stackable: false
                max-durability: 1500
                """.formatted(id) + toolYaml;
        Files.writeString(itemsDir.resolve(fileName), yaml);
    }
}
