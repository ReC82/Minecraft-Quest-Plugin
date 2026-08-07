package be.lloyd.rpgquest.economy;

/** Valeur portée par {@code transactions.type} — purement informatif, jamais interprété par le code. */
public enum TransactionType {
    PAYMENT_SENT,
    PAYMENT_RECEIVED,
    MERCHANT_BUY,
    MERCHANT_SELL,
    MARKET_BUY,
    MARKET_SELL,
    PORTAL_USE,
    ADMIN_GRANT,
    ADMIN_TAKE,
    ADMIN_SET
}
