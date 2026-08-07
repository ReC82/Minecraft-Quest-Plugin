package be.lloyd.rpgquest.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.slf4j.helpers.NOPLogger;

/**
 * Comme {@code ItemDefinitionParserTest}, a besoin de MockBukkit : construire
 * un vrai {@link ItemStack} avec métadonnées (nom, lore, attributs,
 * enchantements, PersistentDataContainer) nécessite un {@code ItemFactory}
 * fourni par un serveur Bukkit vivant.
 */
class YamlCustomItemRegistryTest {

    private static final NamespacedKey FOREST_BLADE = new NamespacedKey("rpgquest", "forest_blade");
    private static final NamespacedKey MINER_PICKAXE = new NamespacedKey("rpgquest", "miner_pickaxe");
    private static final NamespacedKey SPIDER_FANG = new NamespacedKey("rpgquest", "spider_fang");
    private static final NamespacedKey REFINED_CRYSTAL = new NamespacedKey("rpgquest", "refined_crystal");

    @TempDir
    Path tempDir;

    private YamlCustomItemRegistry registry;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        registry = new YamlCustomItemRegistry(tempDir.resolve("items"), NOPLogger.NOP_LOGGER);
        registry.start();
    }

    @AfterEach
    void tearDown() {
        registry.stop();
        MockBukkit.unmock();
    }

    @Test
    void startGeneratesTheFourBundledExamplesWithoutErrors() {
        assertEquals(0, registry.lastReport().issues().size(), () -> "issues: " + registry.lastReport().issues());
        assertEquals(4, registry.items().size());
        assertTrue(registry.find(FOREST_BLADE).isPresent());
        assertTrue(registry.find(MINER_PICKAXE).isPresent());
        assertTrue(registry.find(SPIDER_FANG).isPresent());
        assertTrue(registry.find(REFINED_CRYSTAL).isPresent());
    }

    @Test
    void createAndIdentifyRoundTrip() {
        ItemStack stack = registry.create(FOREST_BLADE, 1).orElseThrow();

        assertTrue(registry.isCustomItem(stack));
        assertEquals(FOREST_BLADE, registry.identify(stack).orElseThrow());
        assertEquals(FOREST_BLADE, registry.resolve(stack).orElseThrow().id());
    }

    @Test
    void survivesSerializationRoundTrip() {
        ItemStack original = registry.create(REFINED_CRYSTAL, 3).orElseThrow();

        // Simule ce qu'une reconnexion/un redémarrage fait subir à un ItemStack : Bukkit le
        // sérialise (inventaire écrit sur disque) puis le désérialise (relecture).
        Map<String, Object> serialized = original.serialize();
        ItemStack roundTripped = ItemStack.deserialize(serialized);

        assertTrue(registry.isCustomItem(roundTripped));
        assertEquals(REFINED_CRYSTAL, registry.identify(roundTripped).orElseThrow());
        assertEquals(3, roundTripped.getAmount());
    }

    @Test
    void renamedVanillaItemIsNotRecognized() {
        ItemStack fake = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = fake.getItemMeta();
        MiniMessage mm = MiniMessage.miniMessage();
        meta.displayName(mm.deserialize("<green>Lame de la forêt</green>"));
        meta.lore(List.of(Component.text("Forgée par les esprits de la forêt.")));
        fake.setItemMeta(meta);

        assertFalse(registry.isCustomItem(fake), "un nom/lore identique ne suffit pas : seul le PDC fait foi");
        assertTrue(registry.identify(fake).isEmpty());
        assertTrue(registry.resolve(fake).isEmpty());
    }

    @Test
    void unknownIdIsRejected() {
        NamespacedKey unknown = new NamespacedKey("rpgquest", "does_not_exist");

        assertTrue(registry.find(unknown).isEmpty());
        assertTrue(registry.create(unknown, 1).isEmpty());
    }

    @Test
    void invalidQuantityIsRejected() {
        assertTrue(registry.create(SPIDER_FANG, 0).isEmpty());
        assertTrue(registry.create(SPIDER_FANG, -1).isEmpty());
        assertTrue(registry.create(SPIDER_FANG, 65).isEmpty(), "spider_fang plafonne à 64");
        assertTrue(registry.create(SPIDER_FANG, 64).isPresent());
    }

    @Test
    void metadataAndAttributesAreApplied() {
        ItemStack stack = registry.create(FOREST_BLADE, 1).orElseThrow();
        ItemMeta meta = stack.getItemMeta();

        assertTrue(meta.hasDisplayName());
        assertTrue(meta.hasLore());
        assertTrue(meta.hasAttributeModifiers());
        assertTrue(meta.hasEnchant(Enchantment.SHARPNESS));
        assertEquals(3, meta.getEnchantLevel(Enchantment.SHARPNESS));
        assertTrue(meta instanceof Damageable damageable && damageable.hasMaxDamage());
    }

    @Test
    void stackableItemAllowsMultipleAndDifferentCustomItemsNeverStack() {
        ItemStack fangA = registry.create(SPIDER_FANG, 10).orElseThrow();
        ItemStack fangB = registry.create(SPIDER_FANG, 5).orElseThrow();

        assertEquals(64, fangA.getMaxStackSize());
        assertTrue(fangA.isSimilar(fangB), "deux piles du même objet personnalisé doivent pouvoir fusionner");

        ItemStack crystal = registry.create(REFINED_CRYSTAL, 1).orElseThrow();
        assertFalse(fangA.isSimilar(crystal), "deux objets personnalisés différents ne doivent jamais fusionner");
    }

    @Test
    void nonStackableItemHasMaxStackSizeOfOneAndRejectsLargerAmounts() {
        ItemStack stack = registry.create(FOREST_BLADE, 1).orElseThrow();

        assertEquals(1, stack.getMaxStackSize());
        assertTrue(registry.create(FOREST_BLADE, 2).isEmpty(), "un objet non empilable ne peut pas être donné en quantité > 1");
    }

    @Test
    void reloadPicksUpNewAndRemovedDefinitions() throws Exception {
        Path itemsDir = tempDir.resolve("items");
        int before = registry.items().size();

        Files.writeString(itemsDir.resolve("extra.yml"), """
                id: rpgquest:extra_item
                type: RESOURCE
                material: STICK
                name: "Extra"
                """);
        registry.reload();
        assertEquals(before + 1, registry.items().size());

        Files.delete(itemsDir.resolve("extra.yml"));
        registry.reload();
        assertEquals(before, registry.items().size());
        assertTrue(registry.find(new NamespacedKey("rpgquest", "extra_item")).isEmpty());
    }
}
