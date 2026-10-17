package com.lodygames.rpgquest.storage;

import java.util.regex.Pattern;

/**
 * Identifiant logique et stable d'un coffre personnel ({@code vault_01}, {@code vault_02}…), issue
 * #104. Indépendant de tout monde, Hub ou coordonnée : plusieurs coffres physiques de plusieurs Hubs
 * pourront présenter le même {@code VaultId} (#105) et donc le même contenu.
 */
public record VaultId(String value) {

    private static final Pattern FORMAT = Pattern.compile("[a-z0-9_]{1,32}");

    /** Premier coffre de chaque joueur, le seul exposé par le MVP. */
    public static final VaultId DEFAULT = new VaultId("vault_01");

    public VaultId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Identifiant de coffre invalide (attendu [a-z0-9_]{1,32}) : " + value);
        }
    }

    public static VaultId of(String value) {
        return new VaultId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
