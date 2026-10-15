package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Issue #414 — rendu pur des pages « Catalogues de quêtes ». */
class QuestCatalogPagesTest {

    private static final QuestCatalogPages.Ctx CTX = new QuestCatalogPages.Ctx("tok", "dev", true, "s1",
            "il y a 2 min", false, 0, "");

    @Test
    void anIdIsDerivedFromTheNameAndNeverReusesAnExistingOne() {
        assertEquals("quetes_de_la_foret", QuestCatalogPages.idFromName("Quêtes de la forêt !", Set.of()));
        assertEquals("quetes_de_la_foret_2", QuestCatalogPages.idFromName("Quêtes de la forêt", Set.of("quetes_de_la_foret")));
        assertEquals("catalogue", QuestCatalogPages.idFromName("★★", Set.of()));
        assertTrue(QuestCatalogPages.idFromName("a".repeat(80), Set.of()).length() <= 48);
    }

    @Test
    void oneStatusPerCatalogFromTheMostSeriousToTheMostNormal() {
        QuestCatalogPages.Template silent = new QuestCatalogPages.Template("t", "Brigand", "bandit", true,
                List.of("c"), false);
        QuestCatalogPages.Survey s = new QuestCatalogPages.Survey(true, List.of(), Map.of(), List.of(), List.of(),
                List.of(silent));
        QuestCatalogPages.Entry ok = new QuestCatalogPages.Entry("rpgquest:a", 10, true, true, "A", true, "", 1);
        QuestCatalogPages.Entry missing = new QuestCatalogPages.Entry("rpgquest:b", 10, true, false, "", false, "", 0);

        assertTrue(QuestCatalogPages.statusChip(s, catalog(false, List.of(ok), List.of())).contains("En pause"));
        assertTrue(QuestCatalogPages.statusChip(s, catalog(true, List.of(missing), List.of())).contains("Ne propose rien"));
        assertTrue(QuestCatalogPages.statusChip(s, catalog(true, List.of(ok, missing), List.of())).contains("À corriger"));
        assertTrue(QuestCatalogPages.statusChip(s, catalog(true, List.of(ok), List.of("t"))).contains("À corriger"),
                "PNJ associé dont le dialogue ne sait pas proposer");
        assertTrue(QuestCatalogPages.statusChip(s, catalog(true, List.of(ok), List.of())).contains("Aucun PNJ"));
    }

    @Test
    void withoutTemplatesFromTheServerThePnjChoiceIsHonestlyUnavailable() {
        QuestCatalogPages.Survey old = QuestCatalogPages.parse(Map.of("available", true, "catalogs", List.of()));
        assertNull(old.templates());
        String page = QuestCatalogPages.create(CTX, old);
        assertFalse(page.contains("assign_templates"), "aucun champ PNJ qui ne serait pas appliqué");
        assertTrue(page.contains("doit être mis à jour"));
    }

    @Test
    void duplicatingCopiesEntriesButNoPnj() {
        QuestCatalogPages.Entry paused = new QuestCatalogPages.Entry("rpgquest:a", 7, false, true, "A", true, "", 1);
        String page = QuestCatalogPages.duplicate(CTX, catalog(true, List.of(paused), List.of("t")));
        assertTrue(page.contains("name=\"entry_0_quest\" value=\"rpgquest:a\"")
                && page.contains("name=\"entry_0_weight\" value=\"7\""));
        assertFalse(page.contains("entry_0_enabled"), "une quête en pause reste en pause");
        assertFalse(page.contains("assign_templates") || page.contains("tpl_"), "aucun PNJ copié");
        assertTrue(page.contains("Contrats (copie)"));
    }

    @Test
    void theAvoidSettingIsSaidInPlainWords() {
        assertTrue(QuestCatalogPages.avoidText(0).startsWith("Aucune"));
        assertTrue(QuestCatalogPages.avoidText(3).contains("les 3 dernières quêtes terminées"));
    }

    private static QuestCatalogPages.Catalog catalog(boolean enabled, List<QuestCatalogPages.Entry> entries,
                                                     List<String> usedBy) {
        return new QuestCatalogPages.Catalog("c", "Contrats", "", enabled, 1, entries, usedBy, List.of());
    }
}
