package com.lodygames.rpgquest.economy.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.economy.merchant.model.MerchantDefinition;
import com.lodygames.rpgquest.economy.merchant.model.MerchantOffer;
import com.lodygames.rpgquest.economy.merchant.model.OfferItemKind;
import com.lodygames.rpgquest.economy.merchant.model.TradeDirection;
import com.lodygames.rpgquest.quest.model.QuestState;
import java.io.StringReader;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Purement structurel : {@code Material} est un enum résoluble sans serveur, pas besoin de MockBukkit (voir {@code DialogueDefinitionParserTest}). */
class MerchantDefinitionParserTest {

    private final MerchantDefinitionParser parser = new MerchantDefinitionParser();

    @Test
    void validFileParsesSuccessfully() {
        MerchantDefinitionParser.ParseResult result = parser.parse("valid.yml", load("""
                id: rpgquest:village_merchant
                title: "<gold>Marchand</gold>"

                offers:
                  - direction: SELL_TO_PLAYER
                    material: BREAD
                    quantity: 4
                    price: 3
                  - direction: BUY_FROM_PLAYER
                    custom-item: rpgquest:spider_fang
                    quantity: 4
                    price: 10
                    required-permission: rpgquest.vip
                    required-quest: rpgquest:first_steps
                    required-quest-state: COMPLETED
                    required-level: 5
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        MerchantDefinition merchant = result.merchant();
        assertEquals("rpgquest:village_merchant", merchant.id().toString());
        assertEquals(2, merchant.offers().size());

        MerchantOffer sell = merchant.offers().get(0);
        assertEquals(TradeDirection.SELL_TO_PLAYER, sell.direction());
        assertEquals(OfferItemKind.VANILLA, sell.itemKind());
        assertEquals(Material.BREAD, sell.vanillaMaterial());
        assertEquals(4, sell.quantity());
        assertEquals(3L, sell.price());

        MerchantOffer buy = merchant.offers().get(1);
        assertEquals(TradeDirection.BUY_FROM_PLAYER, buy.direction());
        assertEquals(OfferItemKind.CUSTOM, buy.itemKind());
        assertEquals("rpgquest:spider_fang", buy.customItemId().toString());
        assertEquals("rpgquest.vip", buy.requiredPermission());
        assertEquals("rpgquest:first_steps", buy.requiredQuestId().toString());
        assertEquals(QuestState.COMPLETED, buy.requiredQuestState());
        assertEquals(5, buy.requiredLevel());
    }

    @Test
    void questStateDefaultsToCompletedWhenOmitted() {
        MerchantDefinitionParser.ParseResult result = parser.parse("valid.yml", load(minimalMerchantWithOffer("""
                  - direction: SELL_TO_PLAYER
                    material: BREAD
                    quantity: 1
                    price: 1
                    required-quest: rpgquest:first_steps
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertEquals(QuestState.COMPLETED, result.merchant().offers().get(0).requiredQuestState());
    }

    @Test
    void missingRequiredFieldsAreAllReportedTogether() {
        MerchantDefinitionParser.ParseResult result = parser.parse("incomplete.yml", load("offers: []\n"));

        assertFalse(result.isSuccess());
        String combined = String.join(" | ", result.issues().stream().map(MerchantLoadIssue::message).toList());
        assertTrue(combined.contains("id"), combined);
        assertTrue(combined.contains("title"), combined);
        assertTrue(combined.contains("offers"), combined);
    }

    @Test
    void bothMaterialAndCustomItemIsRejected() {
        MerchantDefinitionParser.ParseResult result = parser.parse("bad.yml", load(minimalMerchantWithOffer("""
                  - direction: SELL_TO_PLAYER
                    material: BREAD
                    custom-item: rpgquest:spider_fang
                    quantity: 1
                    price: 1
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("exactement un")));
    }

    @Test
    void neitherMaterialNorCustomItemIsRejected() {
        MerchantDefinitionParser.ParseResult result = parser.parse("bad.yml", load(minimalMerchantWithOffer("""
                  - direction: SELL_TO_PLAYER
                    quantity: 1
                    price: 1
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("exactement un")));
    }

    @Test
    void unknownMaterialIsRejected() {
        MerchantDefinitionParser.ParseResult result = parser.parse("bad.yml", load(minimalMerchantWithOffer("""
                  - direction: SELL_TO_PLAYER
                    material: NOT_A_REAL_MATERIAL
                    quantity: 1
                    price: 1
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("matériau inconnu")));
    }

    @Test
    void negativePriceIsRejected() {
        MerchantDefinitionParser.ParseResult result = parser.parse("bad.yml", load(minimalMerchantWithOffer("""
                  - direction: SELL_TO_PLAYER
                    material: BREAD
                    quantity: 1
                    price: -5
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("négatif")));
    }

    @Test
    void zeroQuantityIsRejected() {
        MerchantDefinitionParser.ParseResult result = parser.parse("bad.yml", load(minimalMerchantWithOffer("""
                  - direction: SELL_TO_PLAYER
                    material: BREAD
                    quantity: 0
                    price: 1
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("strictement positif")));
    }

    @Test
    void unknownDirectionIsRejected() {
        MerchantDefinitionParser.ParseResult result = parser.parse("bad.yml", load(minimalMerchantWithOffer("""
                  - direction: NOT_A_REAL_DIRECTION
                    material: BREAD
                    quantity: 1
                    price: 1
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("direction")));
    }

    @Test
    void priceRefOfferTakesEverythingFromTheCatalog() {
        MerchantDefinitionParser.ParseResult result = parser.parse("linked.yml", load(minimalMerchantWithOffer("""
                  - price-ref: pain_x4
                    required-level: 2
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        MerchantOffer offer = result.merchant().offers().get(0);
        assertTrue(offer.linked());
        assertEquals("pain_x4", offer.priceRef());
        assertEquals(null, offer.direction());
        assertEquals(2, offer.requiredLevel());
    }

    @Test
    void priceRefMixedWithLocalFieldsIsRejected() {
        MerchantDefinitionParser.ParseResult result = parser.parse("mixed.yml", load(minimalMerchantWithOffer("""
                  - price-ref: pain_x4
                    price: 3
                  - price-ref: "Pain X4"
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("« price » interdit")));
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("« price-ref » invalide")));
    }

    private String minimalMerchantWithOffer(String offersYaml) {
        return """
                id: rpgquest:test
                title: "Marchand"
                offers:
                """ + offersYaml;
    }

    @Test
    void typeAndEnabledAreReadAndDefaultToMixedAndOpen() {
        MerchantDefinitionParser.ParseResult typed = parser.parse("rachat.yml", load("""
                id: rpgquest:rachat
                title: Rachat
                type: BUY
                enabled: false
                offers:
                  - direction: BUY_FROM_PLAYER
                    material: COBBLESTONE
                    quantity: 64
                    price: 6
                """));
        assertTrue(typed.isSuccess(), () -> "issues: " + typed.issues());
        assertEquals(com.lodygames.rpgquest.economy.merchant.model.MerchantCatalogType.BUY, typed.merchant().type());
        assertFalse(typed.merchant().enabled());

        MerchantDefinitionParser.ParseResult legacy = parser.parse("legacy.yml", load("""
                id: rpgquest:legacy
                title: Legacy
                offers:
                  - direction: SELL_TO_PLAYER
                    material: BREAD
                    quantity: 1
                    price: 1
                """));
        assertTrue(legacy.isSuccess());
        assertEquals(com.lodygames.rpgquest.economy.merchant.model.MerchantCatalogType.MIXED, legacy.merchant().type());
        assertTrue(legacy.merchant().enabled());
    }

    @Test
    void aBuyCatalogRefusesASaleOfferAndUnknownTypeOrEnabledAreRefused() {
        MerchantDefinitionParser.ParseResult wrongDirection = parser.parse("rachat.yml", load("""
                id: rpgquest:rachat
                title: Rachat
                type: BUY
                offers:
                  - direction: SELL_TO_PLAYER
                    material: BREAD
                    quantity: 1
                    price: 1
                """));
        assertFalse(wrongDirection.isSuccess());

        MerchantDefinitionParser.ParseResult bad = parser.parse("bad.yml", load("""
                id: rpgquest:bad
                title: Bad
                type: TROC
                enabled: peut-etre
                offers:
                  - direction: SELL_TO_PLAYER
                    material: BREAD
                    quantity: 1
                    price: 1
                """));
        assertFalse(bad.isSuccess());
        assertEquals(2, bad.issues().size(), () -> "issues: " + bad.issues());
    }

    /** Issue #18 : un atelier d'artisan (CRAFT) ne cite que des recettes, jamais une offre à prix écrit. */
    @Test
    void aCraftCatalogOnlyAcceptsCatalogRecipes() {
        MerchantDefinitionParser.ParseResult atelier = parser.parse("atelier.yml", load("""
                id: rpgquest:forge
                title: Forge
                type: CRAFT
                offers:
                  - price-ref: epee
                """));
        assertTrue(atelier.isSuccess(), () -> "issues: " + atelier.issues());
        assertEquals(com.lodygames.rpgquest.economy.merchant.model.MerchantCatalogType.CRAFT, atelier.merchant().type());

        MerchantDefinitionParser.ParseResult fixed = parser.parse("forge2.yml", load("""
                id: rpgquest:forge2
                title: Forge
                type: CRAFT
                offers:
                  - direction: SELL_TO_PLAYER
                    material: BREAD
                    quantity: 1
                    price: 1
                """));
        assertFalse(fixed.isSuccess());
        assertTrue(fixed.issues().toString().contains("recettes du catalogue des prix"), () -> "issues: " + fixed.issues());
        assertFalse(com.lodygames.rpgquest.economy.merchant.model.MerchantCatalogType.CRAFT
                .accepts(com.lodygames.rpgquest.economy.price.PriceType.SELL_TO_PLAYER));
        assertTrue(com.lodygames.rpgquest.economy.merchant.model.MerchantCatalogType.CRAFT
                .accepts(com.lodygames.rpgquest.economy.price.PriceType.CRAFT));
    }

    private ConfigurationSection load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }
}
