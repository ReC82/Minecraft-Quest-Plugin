package com.lodygames.rpgquest.economy.price;

/**
 * Nature d'un prix du catalogue {@code prices.yml} (issue #336). Trois tarifs distincts, jamais
 * déduits l'un de l'autre : un rachat n'est pas « la moitié du prix de vente », il est saisi.
 */
public enum PriceType {
    /** Le PNJ vend : le joueur paie {@code coins} et reçoit l'objet. */
    SELL_TO_PLAYER("Vente au joueur"),
    /** Le PNJ rachète : le joueur donne l'objet et reçoit {@code coins}. */
    BUY_FROM_PLAYER("Rachat au joueur"),
    /** Fabrication (artisan) : le joueur paie {@code coins} ET donne les ingrédients exacts, il reçoit l'objet. */
    CRAFT("Fabrication");

    private final String label;

    PriceType(String label) {
        this.label = label;
    }

    /** Libellé français pour PlugAdmin et les journaux. */
    public String label() {
        return label;
    }
}
