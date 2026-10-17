package com.lodygames.rpgquest.economy.merchant.model;

import com.lodygames.rpgquest.economy.price.PriceType;

/**
 * Nature d'un catalogue de marchand (issue #17). Un catalogue de <b>vente</b> ne fait que vendre (et
 * fabriquer) ; un catalogue de <b>rachat</b> ne fait que racheter les objets qu'il liste — jamais de
 * rachat générique. {@link #MIXED} est la forme historique (fichier sans {@code type}), qui mêle les
 * deux sens.
 */
public enum MerchantCatalogType {
    /** Vente au joueur (et fabrication par prix du catalogue). */
    SELL,
    /** Rachat auprès du joueur, limité aux objets listés. */
    BUY,
    /** Les deux (forme historique, valeur par défaut d'un fichier sans {@code type}). */
    MIXED;

    /** Vrai si une offre à prix fixé dans ce sens a sa place dans ce catalogue. */
    public boolean accepts(TradeDirection direction) {
        return switch (this) {
            case SELL -> direction == TradeDirection.SELL_TO_PLAYER;
            case BUY -> direction == TradeDirection.BUY_FROM_PLAYER;
            case MIXED -> true;
        };
    }

    /** Vrai si un prix du catalogue central de ce type peut s'appliquer dans ce catalogue. */
    public boolean accepts(PriceType type) {
        return switch (this) {
            case SELL -> type == PriceType.SELL_TO_PLAYER || type == PriceType.CRAFT;
            case BUY -> type == PriceType.BUY_FROM_PLAYER;
            case MIXED -> true;
        };
    }
}
