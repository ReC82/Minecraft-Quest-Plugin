package be.lloyd.rpgquest.item.model;

/** Rareté d'un objet personnalisé ; porte sa propre couleur MiniMessage pour un affichage cohérent partout. */
public enum ItemRarity {
    COMMON("<white>"),
    UNCOMMON("<green>"),
    RARE("<aqua>"),
    EPIC("<light_purple>"),
    LEGENDARY("<gold>");

    private final String colorTag;

    ItemRarity(String colorTag) {
        this.colorTag = colorTag;
    }

    public String colorTag() {
        return colorTag;
    }
}
