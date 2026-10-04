package com.lodygames.rpgquest.waypoint.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Issue #167 : répartition du nom canonique sur les lignes d'un panneau -- sans Bukkit, pur,
 * testable en JUnit. Jamais de troncature au milieu d'un mot ; rejoindre toutes les lignes avec un
 * espace doit toujours reconstruire exactement le nom d'origine.
 */
class WaypointModelV1Test {

    @Test
    void shortNameFitsOnASingleLine() {
        List<String> lines = WaypointModelV1.wrapSignLines("Lac Gris");
        assertEquals(List.of("Lac Gris"), lines);
    }

    @Test
    void longerNameWrapsOnWordBoundariesNeverSplittingAWord() {
        List<String> lines = WaypointModelV1.wrapSignLines("Bruyère de Murmure");
        assertTrue(lines.size() >= 2, "un nom de 18 caractères doit se répartir sur plusieurs lignes : " + lines);
        for (String line : lines) {
            for (String word : line.split(" ")) {
                assertTrue("Bruyère de Murmure".contains(word), "mot coupé de façon ambiguë : " + word);
            }
        }
        assertEquals("Bruyère de Murmure", String.join(" ", lines),
                "rejoindre les lignes doit reconstruire exactement le nom d'origine");
    }

    @Test
    void neverProducesMoreThanFourLines() {
        List<String> lines = WaypointModelV1.wrapSignLines("Un Nom Extrêmement Long Avec Beaucoup De Mots Différents");
        assertTrue(lines.size() <= 4, "un panneau n'a que 4 lignes : " + lines);
    }

    @Test
    void emptyWordsAreNeverProducedByTheWrap() {
        for (String line : WaypointModelV1.wrapSignLines("Clair Matin")) {
            assertTrue(!line.isBlank(), "aucune ligne vide ne doit être générée");
        }
    }
}
