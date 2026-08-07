package be.lloyd.rpgquest.economy.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.economy.merchant.model.MerchantDefinition;
import be.lloyd.rpgquest.economy.merchant.model.MerchantOffer;
import be.lloyd.rpgquest.economy.merchant.model.OfferItemKind;
import be.lloyd.rpgquest.economy.merchant.model.TradeDirection;
import be.lloyd.rpgquest.quest.model.QuestState;
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

    private String minimalMerchantWithOffer(String offersYaml) {
        return """
                id: rpgquest:test
                title: "Marchand"
                offers:
                """ + offersYaml;
    }

    private ConfigurationSection load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }
}
