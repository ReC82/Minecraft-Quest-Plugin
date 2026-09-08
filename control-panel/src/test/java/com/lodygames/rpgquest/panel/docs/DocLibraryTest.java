package com.lodygames.rpgquest.panel.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DocLibraryTest {

    private final DocLibrary lib = DocLibrary.load();

    @Test
    void bundledDocsLoadWithTitleAndValidSlug() {
        assertFalse(lib.isEmpty(), "au moins une fiche livrée");
        Set<String> slugs = new HashSet<>();
        for (DocPage p : lib.all()) {
            assertTrue(p.slug().matches("[a-z0-9-]{1,64}"), "slug sûr : " + p.slug());
            assertTrue(p.title() != null && !p.title().isBlank(), "titre : " + p.slug());
            assertTrue(p.category() != null && !p.category().isBlank());
            assertTrue(slugs.add(p.slug()), "slug unique : " + p.slug());
        }
    }

    @Test
    void thePrioritySheetsExist() {
        assertTrue(lib.bySlug("pnj-citizens").isPresent(), "fiche PNJ prioritaire");
        assertTrue(lib.bySlug("joueurs-reset").isPresent());
        assertTrue(lib.bySlug("quetes").isPresent());
        assertTrue(lib.bySlug("verygames-deploiement").isPresent());
    }

    @Test
    void unknownSlugYieldsEmptyNeverThrows() {
        assertTrue(lib.bySlug("does-not-exist").isEmpty());
        assertTrue(lib.bySlug(null).isEmpty());
        assertTrue(lib.bySlug("../secret").isEmpty());
        assertTrue(lib.bySlug("../../etc/passwd").isEmpty());
    }

    @Test
    void pnjSheetMentionsTheRealCommands() {
        DocPage pnj = lib.bySlug("pnj-citizens").orElseThrow();
        assertTrue(pnj.markdown().contains("/rpgadmin npc tag"), "commande réelle de tag");
        assertTrue(pnj.markdown().contains("/npc create"), "commande Citizens réelle");
        assertTrue(pnj.markdown().contains("/npc skin"), "skin par pseudo");
        assertTrue(pnj.commands().stream().anyMatch(c -> c.contains("/rpgadmin npc tag guard")));
    }

    @Test
    void verygamesSheetCarriesTheAutoRebootRule() {
        DocPage vg = lib.bySlug("verygames-deploiement").orElseThrow();
        assertTrue(vg.markdown().contains("10 auto-reboot in less than 30 minutes"), "règle anti-boucle");
        assertTrue(vg.markdown().contains("panel VeryGames"), "que faire : relance manuelle");
    }

    @Test
    void categoriesGroupPages() {
        var byCat = lib.byCategory();
        assertFalse(byCat.isEmpty());
        assertTrue(byCat.values().stream().flatMap(java.util.List::stream).count() == lib.all().size());
    }
}
