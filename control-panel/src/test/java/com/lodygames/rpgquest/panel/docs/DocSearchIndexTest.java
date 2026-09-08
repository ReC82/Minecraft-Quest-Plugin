package com.lodygames.rpgquest.panel.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocSearchIndexTest {

    private final DocSearchIndex index = DocSearchIndex.build(DocLibrary.load());

    /** Recherches de l'énoncé #49 dont la fiche de tête est sans ambiguïté. */
    private static final Map<String, String> REQUIRED = Map.ofEntries(
            Map.entry("tag npc", "pnj-citizens"),
            Map.entry("skin", "pnj-citizens"),
            Map.entry("citizens", "pnj-citizens"),
            Map.entry("reset joueur", "joueurs-reset"),
            Map.entry("quest complete", "quetes"),
            Map.entry("story complete", "quetes"),
            Map.entry("verygames", "verygames-deploiement"),
            Map.entry("auto reboot", "verygames-deploiement"),
            Map.entry("claim", "claims"),
            Map.entry("wild", "wild"),
            Map.entry("rune rappel", "wild"));

    @Test
    void requiredQueriesReturnTheExpectedSheetOnTop() {
        for (Map.Entry<String, String> e : REQUIRED.entrySet()) {
            List<DocSearchIndex.Hit> hits = index.search(e.getKey());
            assertFalse(hits.isEmpty(), "« " + e.getKey() + " » ne renvoie rien");
            assertEquals(e.getValue(), hits.get(0).page().slug(),
                    "« " + e.getKey() + " » : 1er résultat attendu " + e.getValue()
                            + ", obtenu " + hits.get(0).page().slug());
        }
    }

    @Test
    void rollbackFindsADeploymentSheet() {
        // « rollback » seul s'applique légitimement à VeryGames ET à l'AWS Control Panel :
        // on exige seulement qu'une fiche de déploiement soit en tête.
        List<DocSearchIndex.Hit> hits = index.search("rollback");
        assertFalse(hits.isEmpty());
        String top = hits.get(0).page().slug();
        assertTrue(top.equals("verygames-deploiement") || top.equals("aws-control-panel-deploiement"),
                "rollback -> fiche de déploiement, obtenu " + top);
    }

    @Test
    void blankOrEmptyQueryReturnsNothing() {
        assertTrue(index.search("").isEmpty());
        assertTrue(index.search("   ").isEmpty());
        assertTrue(index.search(null).isEmpty());
    }

    @Test
    void allTermsMustBePresent() {
        assertFalse(index.search("npc").isEmpty());
        assertTrue(index.search("npc licorneinexistante").isEmpty());
    }

    @Test
    void hitsCarryASnippet() {
        DocSearchIndex.Hit hit = index.search("tag npc").get(0);
        assertFalse(hit.snippet().isBlank());
        assertTrue(hit.score() > 0);
    }

    @Test
    void searchIsCaseInsensitiveAndPrefixTolerant() {
        assertEquals("pnj-citizens", index.search("CITIZEN").get(0).page().slug());
        assertEquals("pnj-citizens", index.search("Tag NPC").get(0).page().slug());
    }
}
