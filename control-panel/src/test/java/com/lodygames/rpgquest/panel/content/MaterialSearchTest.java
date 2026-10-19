package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #142 — recherche d'un matériau dans l'éditeur de quêtes : « pump » doit trouver
 * {@code PUMPKIN} dans l'icône comme dans les objectifs, et un objectif de bloc doit accepter les
 * blocs sans forme d'objet que le plugin compte réellement ({@code CARROTS}…).
 */
class MaterialSearchTest {

    /** Extrait fidèle du relevé {@code item.catalogs} de DEV (Minecraft 1.21.11). */
    private static final RefData SERVER = RefData.empty().withItemCatalog(new RefData.ItemCatalog(
            List.of("BOOK", "CARROT", "CARVED_PUMPKIN", "PUMPKIN", "PUMPKIN_PIE", "PUMPKIN_SEEDS",
                    "SPIDER_EYE", "STONE", "WHEAT"),
            List.of("ATTACHED_PUMPKIN_STEM", "CARROTS", "PUMPKIN_STEM", "WALL_TORCH", "WATER"),
            "1.21.11"));

    @Test
    @DisplayName("« pump », « Pump », « pumpk » proposent PUMPKIN, avec ou sans relevé")
    void pumpFindsPumpkinCaseInsensitive() {
        for (RefData ref : List.of(SERVER, RefData.empty())) {
            for (String query : List.of("pump", "Pump", "PUMPK", "pu")) {
                assertTrue(search(ref.options("icon"), query).contains("PUMPKIN"), query);
                assertTrue(search(ref.options("material"), query).contains("PUMPKIN"), query);
                assertTrue(search(ref.options("block"), query).contains("PUMPKIN"), query);
            }
        }
    }

    @Test
    @DisplayName("« spi » ne mêle jamais d'entité aux matériaux")
    void spiDoesNotLeakEntities() {
        List<String> found = search(SERVER.options("material"), "spi");

        assertEquals(List.of("SPIDER_EYE"), found);
        assertFalse(found.contains("SPIDER"));
        assertFalse(search(RefData.empty().options("icon"), "spi").contains("CAVE_SPIDER"));
    }

    @Test
    @DisplayName("Les blocs d'un objectif incluent les blocs sans forme d'objet, les objets non")
    void blockSourceAddsBlocksWithoutItem() {
        assertTrue(SERVER.options("block").contains("CARROTS"));
        assertTrue(SERVER.options("block").contains("PUMPKIN"));
        assertFalse(SERVER.options("material").contains("CARROTS"));
        assertTrue(SERVER.sourceKnown("block"));
        assertTrue(SERVER.isValueKnown("block", "carrots"));
        assertFalse(SERVER.isValueKnown("block", "PAS_UN_BLOC"));
        assertTrue(RefData.empty().options("block").contains("CARROTS"), "repli : cultures courantes");
        assertFalse(RefData.empty().sourceKnown("block"), "sans relevé : jamais d'avertissement");
    }

    @Test
    @DisplayName("Casser des carottes plantées (CARROTS) n'est plus refusé")
    void breakCarrotsIsAccepted() {
        List<Diagnostic> d = QuestValidator.validate(quest("BREAK_BLOCK", "CARROTS"), SERVER);

        assertTrue(d.stream().noneMatch(x -> x.level() != Diagnostic.Level.INFO), d.toString());
    }

    @Test
    @DisplayName("Collecter PUMPKIN ne déclenche plus « hors des valeurs connues »")
    void collectPumpkinIsKnown() {
        List<Diagnostic> d = QuestValidator.validate(quest("COLLECT_ITEM", "PUMPKIN"), SERVER);

        assertTrue(d.stream().noneMatch(x -> x.level() != Diagnostic.Level.INFO), d.toString());
    }

    @Test
    @DisplayName("Collecter CARROTS reste refusé : ce bloc ne peut jamais être un objet")
    void collectCarrotsStaysRejected() {
        List<Diagnostic> d = QuestValidator.validate(quest("COLLECT_ITEM", "CARROTS"), SERVER);

        assertTrue(d.stream().anyMatch(x -> x.level() == Diagnostic.Level.ERROR
                && x.message().contains("aucune forme d'objet")), d.toString());
    }

    @Test
    @DisplayName("Un bloc inventé reste signalé, en français")
    void unknownBlockIsWarned() {
        List<Diagnostic> d = QuestValidator.validate(quest("BREAK_BLOCK", "PAS_UN_BLOC"), SERVER);

        Diagnostic w = d.stream().filter(x -> x.level() == Diagnostic.Level.WARNING).findFirst().orElseThrow();
        assertTrue(w.message().contains("refusera un bloc"), w.message());
    }

    @Test
    @DisplayName("L'icône PUMPKIN est acceptée ; une icône inventée reste signalée")
    void pumpkinIconAccepted() {
        QuestDraft ok = quest("COLLECT_ITEM", "PUMPKIN");
        ok.icon = "pumpkin";
        assertTrue(QuestValidator.validate(ok, SERVER).stream().noneMatch(x -> "icon".equals(x.field())));

        QuestDraft bad = quest("COLLECT_ITEM", "PUMPKIN");
        bad.icon = "PUMPKINZ";
        assertTrue(QuestValidator.validate(bad, SERVER).stream()
                .anyMatch(x -> "icon".equals(x.field()) && x.level() == Diagnostic.Level.WARNING));
    }

    private static QuestDraft quest(String kind, String material) {
        QuestDraft d = new QuestDraft();
        d.id = "test_142";
        d.title = "Citrouilles";
        d.description = "Récolter des citrouilles.";
        d.category = "tutorial";
        d.icon = "BOOK";
        QuestDraft.Step step = new QuestDraft.Step("step1");
        Map<String, String> o = new LinkedHashMap<>();
        o.put("kind", kind);
        o.put("material", material);
        o.put("amount", "3");
        step.objectives.add(o);
        d.steps.add(step);
        return d;
    }

    /** Même filtre que la liste recherchable de panel.js : sous-chaîne, insensible à la casse. */
    private static List<String> search(List<String> options, String query) {
        String q = query.toLowerCase(Locale.ROOT);
        return options.stream().filter(m -> m.toLowerCase(Locale.ROOT).contains(q)).toList();
    }
}
