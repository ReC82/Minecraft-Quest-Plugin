package com.lodygames.rpgquest.panel.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #336 — formulaire « Prix et coûts » → paramètres {@code price.*}. */
class PriceFormTest {

    private static Map<String, String> form(String... kv) {
        Map<String, String> form = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            form.put(kv[i], kv[i + 1]);
        }
        return form;
    }

    @Test
    void normalisesASaleAndDerivesTheKeyFromTheName() {
        Map<String, String> params = new LinkedHashMap<>();
        assertNull(PriceForm.fill(form("create", "true", "label", "Pain du village", "price_type", "sell_to_player",
                "item", "minecraft:bread", "amount", "4", "coins", "3", "demo", "true", "enabled", "true"), params));
        assertEquals("pain_du_village", params.get("price_key"));
        assertEquals("SELL_TO_PLAYER", params.get("type"));
        assertEquals("BREAD", params.get("item"));
        assertEquals("3", params.get("coins"));
        assertEquals("true", params.get("demo"));
        assertTrue(!params.containsKey("ingredients"));
    }

    @Test
    void neverDefaultsTheCoins() {
        String error = PriceForm.fill(form("create", "true", "label", "Pain", "price_type", "SELL_TO_PLAYER",
                "item", "BREAD", "amount", "1", "coins", " "), new HashMap<>());
        assertTrue(error.contains("aucun prix n'est jamais mis par défaut"), error);
        assertTrue(PriceForm.fill(form("create", "true", "label", "Pain", "price_type", "SELL_TO_PLAYER",
                "item", "BREAD", "amount", "1", "coins", "-2"), new HashMap<>()).contains("entre 0"));
    }

    @Test
    void ingredientsBelongToCraftsOnly() {
        Map<String, String> params = new LinkedHashMap<>();
        assertNull(PriceForm.fill(form("create", "true", "label", "Épée", "price_type", "CRAFT", "item",
                "IRON_SWORD", "amount", "1", "coins", "0", "ing_id_0", "iron_ingot", "ing_amount_0", "2",
                "ing_id_2", "rpgquest:spider_fang", "ing_amount_2", ""), params));
        assertEquals("IRON_INGOT@2;rpgquest:spider_fang@1", params.get("ingredients"));
        assertTrue(PriceForm.fill(form("create", "true", "label", "Épée", "price_type", "CRAFT", "item",
                "IRON_SWORD", "amount", "1", "coins", "5"), new HashMap<>()).contains("au moins un ingrédient"));
        assertTrue(PriceForm.fill(form("create", "true", "label", "Pain", "price_type", "SELL_TO_PLAYER", "item",
                "BREAD", "amount", "1", "coins", "5", "ing_id_0", "WHEAT"), new HashMap<>())
                .contains("ne servent qu'à une fabrication"));
    }

    @Test
    void refusesBadKeysItemsAndAmounts() {
        assertTrue(PriceForm.fill(form("create", "true", "label", "x", "price_key", "off", "price_type", "CRAFT"),
                new HashMap<>()).contains("pas oui/non"));
        assertTrue(PriceForm.fill(form("create", "true", "label", "Pain", "price_type", "SELL_TO_PLAYER", "item",
                "pain blanc", "amount", "1", "coins", "1"), new HashMap<>()).contains("Objet"));
        assertTrue(PriceForm.fill(form("create", "true", "label", "Pain", "price_type", "SELL_TO_PLAYER", "item",
                "BREAD", "amount", "65", "coins", "1"), new HashMap<>()).contains("entre 1 et 64"));
        assertTrue(PriceForm.fill(form("create", "true", "label", "Pain", "price_type", "GIFT"), new HashMap<>())
                .contains("vendre, racheter ou fabriquer"));
    }

    @Test
    void duplicateAndToggle() {
        Map<String, String> params = new LinkedHashMap<>();
        assertNull(PriceForm.fillDuplicate(form("price_key", "pain", "new_key", "pain_hiver"), params));
        assertEquals("pain_hiver", params.get("new_key"));
        assertTrue(PriceForm.fillDuplicate(form("price_key", "pain", "new_key", "yes"), new HashMap<>()) != null);
        Map<String, String> toggle = new LinkedHashMap<>();
        assertNull(PriceForm.fillToggle(form("price_key", "pain", "enabled", "false"), toggle));
        assertEquals("false", toggle.get("enabled"));
    }
}
