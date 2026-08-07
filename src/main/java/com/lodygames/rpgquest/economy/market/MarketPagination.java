package com.lodygames.rpgquest.economy.market;

import java.util.List;

/**
 * Arithmétique de pagination de la vitrine du marché, isolée de tout type
 * Bukkit — même conception que {@code ui.JournalPagination} (dupliquée
 * plutôt que rendue publique depuis {@code ui}, qui la garde
 * délibérément package-privée).
 */
final class MarketPagination {

    static final int PAGE_SIZE = 45;

    private MarketPagination() {
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
