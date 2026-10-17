package com.lodygames.rpgquest.economy.merchant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.economy.merchant.model.MerchantCatalogType;
import com.lodygames.rpgquest.economy.merchant.model.MerchantDefinition;
import com.lodygames.rpgquest.economy.merchant.model.MerchantOffer;
import com.lodygames.rpgquest.economy.merchant.model.OfferItemKind;
import com.lodygames.rpgquest.economy.merchant.model.TradeDirection;
import com.lodygames.rpgquest.quest.model.QuestState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Issue #17 : édition sûre des catalogues de marchands depuis PlugAdmin (sans serveur). */
class MerchantCatalogEditorTest {

    private static final NamespacedKey SHOP = new NamespacedKey("rpgquest", "boutique");
    private static final String SHOP_YAML = """
            # Commentaire du propriétaire, à conserver.
            id: rpgquest:boutique
            title: "<gold>Boutique</gold>"
            offers:
              - direction: SELL_TO_PLAYER
                material: BREAD
                quantity: 4
                price: 3
            """;

    @TempDir
    Path dir;

    private YamlMerchantRegistry registry;
    private MerchantCatalogEditor editor;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(dir.resolve("boutique.yml"), SHOP_YAML);
        registry = new YamlMerchantRegistry(dir, LoggerFactory.getLogger("test"));
        registry.reload();
        editor = new MerchantCatalogEditor(registry, LoggerFactory.getLogger("test"));
    }

    private static MerchantOffer sell(Material material, int quantity, long price) {
        return new MerchantOffer(TradeDirection.SELL_TO_PLAYER, OfferItemKind.VANILLA, material, null, quantity, price,
                null, null, null, null);
    }

    private static MerchantOffer buy(Material material, int quantity, long price) {
        return new MerchantOffer(TradeDirection.BUY_FROM_PLAYER, OfferItemKind.VANILLA, material, null, quantity, price,
                null, null, null, null);
    }

    @Test
    void createsABuyCatalogThatTheRegistryLoadsImmediately() throws Exception {
        NamespacedKey id = new NamespacedKey("rpgquest", "comptoir");
        MerchantDefinition wanted = new MerchantDefinition(id, "Comptoir", List.of(buy(Material.COBBLESTONE, 64, 6),
                MerchantOffer.linked("pain", null, new NamespacedKey("rpgquest", "first_steps"), null, 3)),
                MerchantCatalogType.BUY, true);

        MerchantCatalogEditor.Outcome outcome = editor.save(wanted, null, true);

        assertTrue(outcome.success(), outcome.message());
        assertEquals("CREATED", outcome.code());
        assertEquals(wanted, registry.find(id).orElseThrow());
        assertEquals("comptoir.yml", registry.fileOf(id).orElseThrow());
        assertEquals(QuestState.COMPLETED, registry.find(id).orElseThrow().offers().get(1).requiredQuestState());
    }

    @Test
    void creatingAnExistingIdIsRefusedWithoutTouchingTheFile() throws Exception {
        byte[] before = Files.readAllBytes(dir.resolve("boutique.yml"));

        MerchantCatalogEditor.Outcome outcome = editor.save(new MerchantDefinition(SHOP, "Autre",
                List.of(sell(Material.TORCH, 1, 1))), null, true);

        assertFalse(outcome.success());
        assertEquals("EXISTS", outcome.code());
        assertArrayEquals(before, Files.readAllBytes(dir.resolve("boutique.yml")));
    }

    @Test
    void updateKeepsHeaderCommentsAndAStaleVersionWritesNothing() throws Exception {
        String version = editor.version(SHOP);
        MerchantDefinition wanted = new MerchantDefinition(SHOP, "<gold>Boutique</gold>",
                List.of(sell(Material.BREAD, 4, 5), sell(Material.TORCH, 8, 5)), MerchantCatalogType.SELL, true);

        MerchantCatalogEditor.Outcome updated = editor.save(wanted, version, false);

        assertTrue(updated.success(), updated.message());
        assertEquals(wanted, registry.find(SHOP).orElseThrow());
        assertTrue(Files.readString(dir.resolve("boutique.yml")).contains("Commentaire du propriétaire"));

        byte[] after = Files.readAllBytes(dir.resolve("boutique.yml"));
        MerchantCatalogEditor.Outcome stale = editor.save(new MerchantDefinition(SHOP, "X",
                List.of(sell(Material.BREAD, 1, 1))), version, false);
        assertEquals("STALE", stale.code());
        assertArrayEquals(after, Files.readAllBytes(dir.resolve("boutique.yml")));
    }

    @Test
    void unchangedSaveWritesNothing() throws Exception {
        byte[] before = Files.readAllBytes(dir.resolve("boutique.yml"));

        MerchantCatalogEditor.Outcome outcome = editor.save(registry.find(SHOP).orElseThrow(), editor.version(SHOP),
                false);

        assertTrue(outcome.success());
        assertEquals("UNCHANGED", outcome.code());
        assertArrayEquals(before, Files.readAllBytes(dir.resolve("boutique.yml")));
    }

    @Test
    void duplicateIsClosedAndToggleOpensIt() {
        MerchantCatalogEditor.Outcome copy = editor.duplicate(SHOP, "boutique_hiver", null);

        assertTrue(copy.success(), copy.message());
        NamespacedKey copyId = new NamespacedKey("rpgquest", "boutique_hiver");
        MerchantDefinition loaded = registry.find(copyId).orElseThrow();
        assertFalse(loaded.enabled());
        assertEquals(registry.find(SHOP).orElseThrow().offers(), loaded.offers());

        MerchantCatalogEditor.Outcome opened = editor.setEnabled(copyId, true);
        assertTrue(opened.success(), opened.message());
        assertTrue(registry.find(copyId).orElseThrow().enabled());
        assertEquals("UNCHANGED", editor.setEnabled(copyId, true).code());
    }

    @Test
    void deleteIsRefusedWhileADialogueUsesItThenRemovesTheFile() {
        MerchantCatalogEditor.Outcome inUse = editor.delete(SHOP, null, List.of("dialogue « rpgquest:guard »"));
        assertEquals("IN_USE", inUse.code());
        assertTrue(Files.exists(dir.resolve("boutique.yml")));

        MerchantCatalogEditor.Outcome deleted = editor.delete(SHOP, editor.version(SHOP), List.of());
        assertTrue(deleted.success(), deleted.message());
        assertFalse(Files.exists(dir.resolve("boutique.yml")));
        assertTrue(registry.find(SHOP).isEmpty());
    }

    @Test
    void aBundledExampleIsNeverDeleted() throws Exception {
        Files.writeString(dir.resolve("village_merchant.yml"), """
                id: rpgquest:village_merchant
                title: Village
                offers:
                  - direction: SELL_TO_PLAYER
                    material: BREAD
                    quantity: 1
                    price: 1
                """);
        registry.reload();

        MerchantCatalogEditor.Outcome outcome = editor.delete(new NamespacedKey("rpgquest", "village_merchant"), null,
                List.of());

        assertEquals("IN_USE", outcome.code());
        assertTrue(Files.exists(dir.resolve("village_merchant.yml")));
    }

    @Test
    void aWriteTheRegistryRejectsIsRolledBack() throws Exception {
        // Un fichier apparu sans rechargement porte déjà l'id visé par la copie : au rechargement, les
        // deux sont rejetés (id dupliqué) — l'éditeur retire son fichier et ne touche à rien d'autre.
        String other = """
                id: rpgquest:copie
                title: Autre
                offers:
                  - direction: SELL_TO_PLAYER
                    material: BREAD
                    quantity: 1
                    price: 1
                """;
        Files.writeString(dir.resolve("zz_autre.yml"), other);

        MerchantCatalogEditor.Outcome outcome = editor.duplicate(SHOP, "copie", null);

        assertFalse(outcome.success());
        assertEquals("ROLLED_BACK", outcome.code(), outcome.message());
        assertFalse(Files.exists(dir.resolve("copie.yml")));
        assertEquals(other, Files.readString(dir.resolve("zz_autre.yml")));
        assertTrue(registry.find(SHOP).isPresent());
    }
}
