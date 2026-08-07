package be.lloyd.rpgquest.backpack.model;

/**
 * Les trois paliers de backpack (mission étape 20, point 2). Le nombre de
 * lignes de chaque palier est configurable ({@code config.yml} →
 * {@code backpacks:}), jamais codé en dur ici — cet enum n'ordonne que les
 * paliers entre eux ({@link #ordinal()} sert de comparaison
 * upgrade/downgrade).
 */
public enum BackpackSize {
    SMALL,
    MEDIUM,
    LARGE
}
