package com.lodygames.rpgquest.economy.merchant.model;

import java.util.List;
import org.bukkit.NamespacedKey;

/**
 * Un marchand : un titre affiché (chaîne MiniMessage brute, même convention
 * que {@code CustomItemDefinition} — pas de table de traductions) et une
 * vitrine d'offres. Aucun lien direct à une entité PNJ : l'identification se
 * fait via l'id référencé par une action de dialogue {@code OPEN_MERCHANT},
 * pas par un nom d'entité (voir {@code docs/ARCHITECTURE.md}, section
 * {@code economy}).
 */
public record MerchantDefinition(NamespacedKey id, String title, List<MerchantOffer> offers) {

    public MerchantDefinition {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("« title » est obligatoire.");
        }
        offers = List.copyOf(offers);
        if (offers.isEmpty()) {
            throw new IllegalArgumentException("Un marchand doit proposer au moins une offre.");
        }
    }
}
