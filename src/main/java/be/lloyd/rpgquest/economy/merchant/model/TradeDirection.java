package be.lloyd.rpgquest.economy.merchant.model;

/** Sens d'une offre de marchand, du point de vue du marchand. */
public enum TradeDirection {
    /** Le marchand vend au joueur : le joueur paie et reçoit l'objet. */
    SELL_TO_PLAYER,
    /** Le marchand achète au joueur : le joueur donne l'objet et reçoit le paiement. */
    BUY_FROM_PLAYER
}
