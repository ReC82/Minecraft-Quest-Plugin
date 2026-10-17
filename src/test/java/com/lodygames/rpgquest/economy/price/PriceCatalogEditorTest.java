package com.lodygames.rpgquest.economy.price;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.economy.price.PriceDefinition.PriceItem;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class PriceCatalogEditorTest {

    @TempDir
    Path dir;

    private Path file;
    private PriceCatalogService service;

    @BeforeEach
    void setUp() {
        file = dir.resolve("prices.yml");
        service = new PriceCatalogService(file, LoggerFactory.getLogger("test"));
        service.start();
    }

    private static PriceDefinition sale(String key, String item, int amount, long coins) {
        return new PriceDefinition(key, "Vente " + key, PriceType.SELL_TO_PLAYER, new PriceItem(item, amount), coins,
                List.of(), true, true);
    }

    private static PriceDefinition buyBack(String key, String item, int amount, long coins) {
        return new PriceDefinition(key, "Rachat " + key, PriceType.BUY_FROM_PLAYER, new PriceItem(item, amount), coins,
                List.of(), false, true);
    }

    @Test
    void firstSaveCreatesTheFileAndAppliesThePrice() throws Exception {
        assertFalse(service.fileExists());
        long generation = service.generation();

        PriceCatalogEditor.Outcome outcome = service.editor().savePrice(sale("pain", "BREAD", 4, 3), null, true);

        assertTrue(outcome.success(), outcome.message());
        assertEquals("CREATED", outcome.code());
        assertTrue(service.fileExists());
        String text = Files.readString(file);
        assertTrue(text.startsWith("# Catalogue des prix RPGQuest"), text);
        assertTrue(text.contains("coins: 3"), text);
        assertEquals(3, service.catalog().usable("pain").orElseThrow().coins());
        assertTrue(service.generation() > generation, "un rechargement change la génération");
    }

    @Test
    void updateKeepsOtherPricesCommentsAndOrder() throws Exception {
        Files.writeString(file, """
                # commentaire du propriétaire
                prices:
                  a:
                    label: A
                    type: SELL_TO_PLAYER
                    item: BREAD
                    amount: 1
                    coins: 1
                  b:
                    label: B
                    type: SELL_TO_PLAYER
                    item: TORCH
                    amount: 8
                    coins: 5
                """);
        service.reloadNow();
        PriceDefinition a = service.catalog().prices().get("a");

        PriceCatalogEditor.Outcome outcome = service.editor().savePrice(
                new PriceDefinition("a", "A", PriceType.SELL_TO_PLAYER, new PriceItem("BREAD", 2), 4, List.of(), false,
                        true), a.version(), false);

        assertEquals("UPDATED", outcome.code(), outcome.message());
        String text = Files.readString(file);
        assertTrue(text.contains("# commentaire du propriétaire"), text);
        assertEquals(List.of("a", "b"), List.copyOf(service.catalog().prices().keySet()));
        assertEquals(5, service.catalog().prices().get("b").coins());
        assertEquals(4, service.catalog().prices().get("a").coins());
    }

    @Test
    void refusesStaleFormsUnknownKeysAndDuplicates() {
        service.editor().savePrice(sale("pain", "BREAD", 4, 3), null, true);
        PriceDefinition changed = sale("pain", "BREAD", 4, 5);

        assertEquals("STALE", service.editor().savePrice(changed, "00000000", false).code());
        assertEquals("NOT_FOUND", service.editor().savePrice(sale("autre", "BREAD", 1, 1), null, false).code());
        assertEquals("EXISTS", service.editor().savePrice(sale("pain", "BREAD", 1, 1), null, true).code());
        assertEquals("INVALID", service.editor().savePrice(sale("yes", "BREAD", 1, 1), null, true).code());
        PriceDefinition current = service.catalog().prices().get("pain");
        assertEquals("UNCHANGED", service.editor().savePrice(current, current.version(), false).code());
        assertEquals(3, service.catalog().prices().get("pain").coins());
    }

    @Test
    void refusesAnEnrichmentLoopWithoutWriting() throws Exception {
        service.editor().savePrice(sale("pain", "BREAD", 4, 4), null, true);
        byte[] before = Files.readAllBytes(file);

        PriceCatalogEditor.Outcome outcome = service.editor().savePrice(buyBack("rachat", "BREAD", 1, 2), null, true);

        assertFalse(outcome.success());
        assertEquals("INVALID", outcome.code());
        assertTrue(outcome.message().contains("Boucle d'enrichissement"), outcome.message());
        assertArrayEquals(before, Files.readAllBytes(file));
        assertTrue(service.catalog().usable("rachat").isEmpty());
    }

    @Test
    void neverWritesOverAFileTheServerRefuses() throws Exception {
        Files.writeString(file, "prices:\n  x:\n    label: X\n    type: SELL_TO_PLAYER\n    item: BREAD\n    amount: 1\n");
        service.reloadNow();
        assertFalse(service.lastErrors().isEmpty());
        byte[] before = Files.readAllBytes(file);

        PriceCatalogEditor.Outcome outcome = service.editor().savePrice(sale("pain", "BREAD", 1, 1), null, true);

        assertEquals("CURRENT_INVALID", outcome.code());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void duplicateIsDisabledAndToggleKeepsTheContent() {
        service.editor().savePrice(sale("pain", "BREAD", 4, 3), null, true);

        PriceCatalogEditor.Outcome copy = service.editor().duplicatePrice("pain", "pain_v2", null);
        assertEquals("DUPLICATED", copy.code(), copy.message());
        PriceDefinition v2 = service.catalog().prices().get("pain_v2");
        assertFalse(v2.enabled());
        assertEquals("Vente pain (copie)", v2.label());
        assertEquals(3, v2.coins());

        assertEquals("TOGGLED", service.editor().setPriceEnabled("pain", false).code());
        assertTrue(service.catalog().usable("pain").isEmpty());
        assertEquals(3, service.catalog().prices().get("pain").coins());
        assertEquals("UNCHANGED", service.editor().setPriceEnabled("pain", false).code());
        assertEquals("NOT_FOUND", service.editor().setPriceEnabled("absent", true).code());
    }

    @Test
    void restoresThePreviousFileWhenTheReloadDisagrees() throws Exception {
        String original = "prices:\n  a:\n    label: A\n    type: SELL_TO_PLAYER\n    item: BREAD\n    amount: 1\n    coins: 1\n";
        Files.writeString(file, original);
        PriceCatalogFile disagreeing = new PriceCatalogFile(file,
                () -> new PriceCatalogLoader.Result(PriceCatalog.EMPTY, List.of(), List.of()),
                LoggerFactory.getLogger("test"));

        PriceCatalogEditor.Outcome outcome = new PriceCatalogEditor(disagreeing)
                .savePrice(sale("b", "TORCH", 1, 1), null, true);

        assertEquals("ROLLED_BACK", outcome.code(), outcome.message());
        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
        try (var listing = Files.list(dir)) {
            assertEquals(1, listing.count(), "aucun fichier temporaire laissé");
        }
    }
}
