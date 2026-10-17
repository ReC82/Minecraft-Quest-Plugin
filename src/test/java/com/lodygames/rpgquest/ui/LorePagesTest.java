package com.lodygames.rpgquest.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Issue #475 (retour du 17/10) — pages d'infobulle de hauteur bornée, rien de perdu. */
class LorePagesTest {

    @Test
    void splitsInOrderWithoutLosingALine() {
        assertEquals(List.of(List.of(1, 2, 3), List.of(4, 5, 6), List.of(7)),
                LorePages.split(List.of(1, 2, 3, 4, 5, 6, 7), 3));
    }

    @Test
    void aShortListIsOnePageAndAnEmptyListNone() {
        assertEquals(List.of(List.of("a", "b")), LorePages.split(List.of("a", "b"), 10));
        assertEquals(List.of(), LorePages.split(List.of(), 10));
    }

    @Test
    void refusesANonPositiveHeight() {
        assertThrows(IllegalArgumentException.class, () -> LorePages.split(List.of(1), 0));
    }
}
