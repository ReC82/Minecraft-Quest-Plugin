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
 *
 * <p>Issue #17 : un marchand est un <em>catalogue</em> réutilisable par plusieurs PNJ, de nature
 * {@link MerchantCatalogType} (vente, rachat, ou mixte historique), et peut être désactivé
 * ({@code enabled: false} : la boutique est fermée, rien ne s'échange).</p>
 */
public record MerchantDefinition(NamespacedKey id, String title, List<MerchantOffer> offers,
                                 MerchantCatalogType type, boolean enabled) {

    /** Forme historique : catalogue mixte, actif. */
    public MerchantDefinition(NamespacedKey id, String title, List<MerchantOffer> offers) {
        this(id, title, offers, MerchantCatalogType.MIXED, true);
    }

    public MerchantDefinition {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("« title » est obligatoire.");
        }
        if (type == null) {
            type = MerchantCatalogType.MIXED;
        }
        offers = List.copyOf(offers);
        if (offers.isEmpty()) {
            throw new IllegalArgumentException("Un marchand doit proposer au moins une offre.");
        }
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer offer = offers.get(i);
            if (!offer.linked() && !type.accepts(offer.direction())) {
                throw new IllegalArgumentException("offers[" + i + "] : « " + offer.direction() + " » interdit dans "
                        + "un catalogue de type " + type + (type == MerchantCatalogType.SELL
                        ? " (vente seulement)." : " (rachat seulement)."));
            }
        }
    }

    /** Même catalogue, sous un autre identifiant / titre / état (duplication, activation). */
    public MerchantDefinition with(NamespacedKey newId, String newTitle, boolean newEnabled) {
        return new MerchantDefinition(newId, newTitle, offers, type, newEnabled);
    }
}
