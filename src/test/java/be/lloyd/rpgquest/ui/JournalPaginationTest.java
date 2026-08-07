package be.lloyd.rpgquest.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Arithmétique pure de pagination du journal : aucune dépendance Bukkit, aucun MockBukkit nécessaire. */
class JournalPaginationTest {

    @Test
    void zeroItemsStillYieldsOnePage() {
        assertEquals(1, JournalPagination.pageCount(0));
        assertTrue(JournalPagination.pageOf(List.of(), 0).isEmpty());
    }

    @Test
    void oneItemYieldsOnePage() {
        assertEquals(1, JournalPagination.pageCount(1));
    }

    @Test
    void exactlyOnePageWorthOfItemsYieldsOnePage() {
        // 45 == PAGE_SIZE : doit tenir exactement sur une seule page.
        assertEquals(1, JournalPagination.pageCount(45));
        List<Integer> items = IntStream.range(0, 45).boxed().toList();
        assertEquals(45, JournalPagination.pageOf(items, 0).size());
    }

    @Test
    void onePastAPageWorthOfItemsSpillsToASecondPage() {
        // 46 == PAGE_SIZE + 1 : doit nécessiter une deuxième page pour le dernier élément.
        assertEquals(2, JournalPagination.pageCount(46));
        List<Integer> items = IntStream.range(0, 46).boxed().toList();
        assertEquals(45, JournalPagination.pageOf(items, 0).size());
        assertEquals(1, JournalPagination.pageOf(items, 1).size());
    }

    @Test
    void clampPageKeepsRequestedPageWithinBounds() {
        assertEquals(0, JournalPagination.clampPage(-5, 2));
        assertEquals(1, JournalPagination.clampPage(99, 2));
        assertEquals(1, JournalPagination.clampPage(1, 2));
    }
}
