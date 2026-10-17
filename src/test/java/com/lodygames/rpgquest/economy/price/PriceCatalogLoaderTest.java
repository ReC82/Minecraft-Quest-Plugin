package com.lodygames.rpgquest.economy.price;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.economy.price.PriceDefinition.PriceItem;
import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PriceCatalogLoaderTest {

    private static PriceCatalogLoader.Result load(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return PriceCatalogLoader.load(config);
    }

    private static void assertRefused(String yaml, String fragment) throws InvalidConfigurationException {
        PriceCatalogLoader.Result result = load(yaml);
        assertFalse(result.valid(), "devait être refusé : " + yaml);
        assertTrue(result.catalog().prices().isEmpty(), "un fichier refusé n'applique aucun prix");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains(fragment)),
                "erreur attendue « " + fragment + " », obtenu " + result.errors());
    }

    @Test
    void readsTheThreeKindsOfPrice() throws Exception {
        PriceCatalogLoader.Result result = load("""
                prices:
                  pain_x4:
                    label: Pain
                    type: SELL_TO_PLAYER
                    item: BREAD
                    amount: 4
                    coins: 3
                    demo: true
                  rachat_os:
                    label: Os
                    type: BUY_FROM_PLAYER
                    item: BONE
                    amount: 8
                    coins: 2
                  lame:
                    label: Lame forestière
                    type: CRAFT
                    item: rpgquest:forest_blade
                    amount: 1
                    coins: 50
                    ingredients:
                      - item: IRON_INGOT
                        amount: 3
                      - item: rpgquest:spider_fang
                        amount: 2
                    enabled: false
                """);
        assertTrue(result.valid(), result.errors().toString());
        PriceCatalog catalog = result.catalog();
        assertEquals(List.of("pain_x4", "rachat_os", "lame"), List.copyOf(catalog.prices().keySet()));
        PriceDefinition bread = catalog.prices().get("pain_x4");
        assertEquals(PriceType.SELL_TO_PLAYER, bread.type());
        assertEquals(new PriceItem("BREAD", 4), bread.item());
        assertEquals(3, bread.coins());
        assertTrue(bread.demo());
        assertTrue(bread.enabled());
        PriceDefinition blade = catalog.prices().get("lame");
        assertEquals(List.of(new PriceItem("IRON_INGOT", 3), new PriceItem("rpgquest:spider_fang", 2)),
                blade.ingredients());
        assertFalse(blade.enabled());
        assertTrue(catalog.usable("pain_x4").isPresent());
        assertTrue(catalog.usable("lame").isEmpty(), "désactivé = inutilisable");
        assertTrue(catalog.usable("absent").isEmpty(), "absent = aucun prix, jamais un défaut");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("démonstration")));
    }

    @Test
    void emptyOrMissingSectionIsAnEmptyCatalog() throws Exception {
        assertTrue(load("prices: {}\n").valid());
        assertTrue(load("# rien\n").catalog().prices().isEmpty());
    }

    @Test
    void neverInventsAPrice() throws Exception {
        assertRefused("""
                prices:
                  pain:
                    label: Pain
                    type: SELL_TO_PLAYER
                    item: BREAD
                    amount: 1
                """, "« coins » obligatoire");
        assertRefused("""
                prices:
                  pain:
                    label: Pain
                    type: SELL_TO_PLAYER
                    item: BREAD
                    amount: 1
                    coins: -1
                """, "entre 0 et");
        assertRefused("""
                prices:
                  pain:
                    label: Pain
                    type: SELL_TO_PLAYER
                    item: BREAD
                    amount: 1
                    coins: 2.5
                """, "nombre entier");
    }

    @Test
    void refusesBadItemsAndQuantities() throws Exception {
        String base = """
                prices:
                  x:
                    label: X
                    type: SELL_TO_PLAYER
                    item: %s
                    amount: %s
                    coins: 1
                """;
        assertRefused(base.formatted("NOT_A_MATERIAL", 1), "n'est pas un objet Minecraft");
        assertRefused(base.formatted("AIR", 1), "n'est pas un objet Minecraft");
        assertRefused(base.formatted("bread", 1), "invalide");
        assertRefused(base.formatted("BREAD", 0), "quantité");
        assertRefused(base.formatted("BREAD", 65), "quantité");
        assertRefused(base.replace("SELL_TO_PLAYER", "GIFT").formatted("BREAD", 1), "type « GIFT » inconnu");
        assertRefused("""
                prices:
                  off:
                    label: X
                    type: SELL_TO_PLAYER
                    item: BREAD
                    amount: 1
                    coins: 1
                """, "pas oui/non");
    }

    @Test
    void craftNeedsExactDistinctIngredientsAndSalesHaveNone() throws Exception {
        assertRefused("""
                prices:
                  c:
                    label: C
                    type: CRAFT
                    item: IRON_SWORD
                    amount: 1
                    coins: 5
                """, "au moins un ingrédient");
        assertRefused("""
                prices:
                  c:
                    label: C
                    type: CRAFT
                    item: IRON_SWORD
                    amount: 1
                    coins: 5
                    ingredients:
                      - {item: IRON_INGOT, amount: 1}
                      - {item: IRON_INGOT, amount: 1}
                """, "apparaît deux fois");
        assertRefused("""
                prices:
                  c:
                    label: C
                    type: CRAFT
                    item: IRON_SWORD
                    amount: 1
                    coins: 5
                    ingredients:
                      - {item: IRON_SWORD, amount: 1}
                """, "son propre ingrédient");
        assertRefused("""
                prices:
                  s:
                    label: S
                    type: SELL_TO_PLAYER
                    item: BREAD
                    amount: 1
                    coins: 5
                    ingredients:
                      - {item: WHEAT, amount: 3}
                """, "n'a de sens que pour une fabrication");
    }

    @Test
    void refusesABuyBackHigherThanTheSale() throws Exception {
        assertRefused("""
                prices:
                  vente:
                    label: Vente
                    type: SELL_TO_PLAYER
                    item: BREAD
                    amount: 4
                    coins: 3
                  rachat:
                    label: Rachat
                    type: BUY_FROM_PLAYER
                    item: BREAD
                    amount: 1
                    coins: 1
                """, "Boucle d'enrichissement");
    }

    @Test
    void equalOrLowerBuyBackAndDisabledPricesAreAccepted() throws Exception {
        String yaml = """
                prices:
                  vente:
                    label: Vente
                    type: SELL_TO_PLAYER
                    item: BREAD
                    amount: 4
                    coins: 4
                  rachat:
                    label: Rachat
                    type: BUY_FROM_PLAYER
                    item: BREAD
                    amount: 2
                    coins: %d
                    enabled: %s
                """;
        assertTrue(load(yaml.formatted(2, "true")).valid(), "même prix unitaire : aucun gain");
        assertTrue(load(yaml.formatted(5, "false")).valid(), "un prix désactivé ne compte pas");
        assertFalse(load(yaml.formatted(3, "true")).valid());
    }

    @Test
    void refusesACraftWhoseResultSellsBackForMoreThanItCosts() throws Exception {
        String yaml = """
                prices:
                  lingot:
                    label: Lingot
                    type: SELL_TO_PLAYER
                    item: IRON_INGOT
                    amount: 1
                    coins: 10
                  epee:
                    label: Épée
                    type: CRAFT
                    item: IRON_SWORD
                    amount: 1
                    coins: 5
                    ingredients:
                      - {item: IRON_INGOT, amount: 2}
                  rachat_epee:
                    label: Rachat épée
                    type: BUY_FROM_PLAYER
                    item: IRON_SWORD
                    amount: 1
                    coins: %d
                """;
        assertTrue(load(yaml.formatted(25)).valid(), "25 = coût exact (5 + 2 × 10)");
        assertRefused(yaml.formatted(26), "la fabrication « epee » coûte 25");
    }

    @Test
    void warnsAboutFreeItems() throws Exception {
        PriceCatalogLoader.Result result = load("""
                prices:
                  cadeau:
                    label: Cadeau
                    type: SELL_TO_PLAYER
                    item: BREAD
                    amount: 1
                    coins: 0
                """);
        assertTrue(result.valid());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("gratuitement")));
    }
}
