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
        assertTrue(QuestCatalogPages.avoidText(0).startsWith("Répétitions autorisées"));
        assertTrue(QuestCatalogPages.avoidText(3).contains("les 3 dernières quêtes finies"));
    }

    @Test
    void frequenciesHaveSimpleNamesAndOddWeightsAreShownAsTheyAre() {
        assertEquals("Rare", QuestCatalogPages.frequencyLabel(5));
        assertEquals("Normale", QuestCatalogPages.frequencyLabel(10));
        assertEquals("Souvent", QuestCatalogPages.frequencyLabel(20));
        assertEquals("Personnalisée (7)", QuestCatalogPages.frequencyLabel(7));
    }

    /** Retour propriétaire #414 : l'ordre n'est pas un ordre de proposition, le hasard est une question claire. */
    @Test
    void theFormSaysTheOrderOnlySortsAndAsksHowToPickTheNextQuest() {
        QuestCatalogPages.Entry a = new QuestCatalogPages.Entry("rpgquest:a", 10, true, true, "A", true, "", 1);
        QuestCatalogPages.Entry b = new QuestCatalogPages.Entry("rpgquest:b", 10, true, true, "B", true, "", 1);
        String page = QuestCatalogPages.edit(CTX, survey(), catalog(true, List.of(a, b), List.of()), false);
        assertTrue(page.contains("proposées au hasard") && page.contains("sert seulement à les ranger"));
        assertTrue(page.contains("Comment choisir la prochaine quête ?") && page.contains("data-qc-count=\"many\""));
        assertTrue(page.contains("value=\"equal\" checked"), "poids égaux : « même chance pour toutes »");
        assertTrue(page.contains("name=\"equal_weight\" value=\"10\""));
        assertTrue(page.contains("Peut-on proposer deux fois de suite la même quête ?")
                && page.contains("id=\"qc-avoid-recent-1\" name=\"avoid_recent\" value=\"1\" checked"));
        assertTrue(page.contains("Autoriser les PNJ à utiliser cette liste"));
        int technical = page.indexOf("Informations techniques");
        assertTrue(technical > page.indexOf("Comment choisir"), "identifiant et options expertes repliés en bas");
        assertTrue(page.indexOf("data-qc-move") > 0 && page.indexOf("qc-adv qc-item-adv") < page.indexOf("data-qc-move"),
                "monter / descendre seulement dans les options expertes");
        assertFalse(page.contains("<label for=\"qc-avoid\">Anti-répétition"), "plus de grille technique");
    }

    @Test
    void unevenWeightsOpenInSomeMoreOftenModeWithTheirValuesUntouched() {
        QuestCatalogPages.Entry often = new QuestCatalogPages.Entry("rpgquest:a", 20, true, true, "A", true, "", 1);
        QuestCatalogPages.Entry odd = new QuestCatalogPages.Entry("rpgquest:b", 7, true, true, "B", true, "", 1);
        QuestCatalogPages.Catalog c = new QuestCatalogPages.Catalog("c", "Contrats", "", true, 3,
                List.of(often, odd), List.of(), List.of());
        String page = QuestCatalogPages.edit(CTX, survey(), c, false);
        assertTrue(page.contains("value=\"weighted\" checked"));
        assertTrue(page.contains("<option value=\"20\" selected>Souvent</option>"));
        assertTrue(page.contains("<option value=\"7\" selected>Personnalisée (7)</option>"),
                "jamais arrondi à un niveau simple sans action de l'utilisateur");
        assertTrue(page.contains("value=\"more\" checked") && page.contains("<option value=\"3\" selected>3</option>"),
                "anti-répétition 3 conservée en réglage avancé");

        String sheet = QuestCatalogPages.detail(CTX, survey(), c);
        assertTrue(sheet.contains("certaines plus souvent") && sheet.contains("Souvent · environ 74 %")
                && sheet.contains("Personnalisée (7) · environ 26 %"));
        assertTrue(sheet.contains("Les PNJ peuvent utiliser cette liste : <strong>oui"));
    }

    @Test
    void theChoiceAdaptsToOneQuestOrNone() {
        QuestCatalogPages.Entry a = new QuestCatalogPages.Entry("rpgquest:a", 7, true, true, "A", true, "", 1);
        QuestCatalogPages.Catalog one = catalog(true, List.of(a), List.of());
        assertTrue(QuestCatalogPages.detail(CTX, survey(), one).contains("Une seule quête : c'est toujours elle"));
        String edit = QuestCatalogPages.edit(CTX, survey(), one, false);
        assertTrue(edit.contains("data-qc-count=\"one\"") && edit.contains("name=\"equal_weight\" value=\"7\""),
                "une seule quête : son poids est gardé");
        assertTrue(QuestCatalogPages.create(CTX, survey()).contains("data-qc-count=\"none\""));
        assertTrue(QuestCatalogPages.detail(CTX, survey(), catalog(true, List.of(), List.of()))
                .contains("Rien à choisir"));
    }

    private static QuestCatalogPages.Survey survey() {
        return new QuestCatalogPages.Survey(true, List.of(), Map.of(), List.of(), List.of(), List.of());
    }

    private static QuestCatalogPages.Catalog catalog(boolean enabled, List<QuestCatalogPages.Entry> entries,
                                                     List<String> usedBy) {
        return new QuestCatalogPages.Catalog("c", "Contrats", "", enabled, 1, entries, usedBy, List.of());
    }
}
