package com.lodygames.rpgquest.economy.merchant;

import com.lodygames.rpgquest.economy.merchant.model.MerchantCatalogType;
import com.lodygames.rpgquest.economy.merchant.model.TradeDirection;
import com.lodygames.rpgquest.economy.price.PriceType;

/**
 * Vue d'une vitrine de marchand, <b>du point de vue du joueur</b> (retour propriétaire du 21/10 sur
 * l'issue #17) : « Acheter » = le joueur paie et reçoit l'objet ; « Vendre » = le joueur donne
 * l'objet et reçoit des pièces. Un catalogue {@link MerchantCatalogType#MIXED} propose les deux en
 * onglets ; un catalogue d'un seul sens (ou un atelier) n'a qu'une vue.
 */
enum ShopTab {
    BUY("Acheter"),
    SELL("Vendre"),
    CRAFT("Fabriquer");

    private final String label;

    ShopTab(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }

    /** Vue unique d'un catalogue d'un seul sens ; {@code null} pour un catalogue mixte (onglets). */
    static ShopTab singleViewOf(MerchantCatalogType type) {
        return switch (type) {
            case SELL -> BUY;
            case BUY -> SELL;
            case CRAFT -> CRAFT;
            case MIXED -> null;
        };
    }

    /** Onglet d'une offre à prix fixé dans un catalogue mixte. */
    static ShopTab of(TradeDirection direction) {
        return direction == TradeDirection.BUY_FROM_PLAYER ? SELL : BUY;
    }

    /**
     * Onglet d'une offre liée au catalogue des prix dans un catalogue mixte : le joueur qui reçoit un
     * objet fabriqué paie lui aussi, il est donc dans « Acheter ». Prix inconnu ({@code null}) =
     * « Acheter », où l'offre reste visible comme indisponible.
     */
    static ShopTab of(PriceType type) {
        return type == PriceType.BUY_FROM_PLAYER ? SELL : BUY;
    }
}
