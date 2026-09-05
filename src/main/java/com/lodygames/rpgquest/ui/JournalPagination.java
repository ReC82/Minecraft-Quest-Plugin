package com.lodygames.rpgquest.ui;

import java.util.List;

/**
 * Arithmétique de pagination du journal de quêtes, isolée de tout type
 * Bukkit : testable en JUnit pur, sans MockBukkit. {@link #PAGE_SIZE} (45)
 * correspond exactement au nombre de slots de contenu du menu (une ligne de
 * chrome + cinq lignes de contenu dans un inventaire de 54 slots).
 */
final class JournalPagination {

    static final int PAGE_SIZE = 45;

    private JournalPagination() {
    }

    /** Toujours au moins 1 : une liste vide affiche une page vide, pas « aucune page ». */
    static int pageCount(int totalItems) {
        return Math.max(1, (int) Math.ceil(totalItems / (double) PAGE_SIZE));
    }

    static int clampPage(int requestedPage, int pageCount) {
        return Math.max(0, Math.min(requestedPage, pageCount - 1));
    }

    static <T> List<T> pageOf(List<T> items, int page) {
        int from = Math.min(items.size(), page * PAGE_SIZE);
        int to = Math.min(items.size(), from + PAGE_SIZE);
        return items.subList(from, to);
    }
}
