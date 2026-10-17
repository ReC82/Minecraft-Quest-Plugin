package com.lodygames.rpgquest.config;

import java.util.List;

/**
 * Section {@code personal-vaults:} (issue #106) — capacité de départ des coffres personnels.
 * Valeurs de démonstration : l'équilibrage (nombre de coffres, taille) n'est pas arbitré.
 *
 * @param starterVaults coffres ouverts à tout joueur sans déblocage ({@code vault_01} par défaut)
 * @param defaultSlots  taille d'un coffre sans extension, multiple de 9 entre 9 et 54
 */
public record PersonalVaultConfig(List<String> starterVaults, int defaultSlots) {

    public static final int MIN_SLOTS = 9;
    public static final int MAX_SLOTS = 54;

    public PersonalVaultConfig {
        starterVaults = List.copyOf(starterVaults);
    }

    public static PersonalVaultConfig defaults() {
        return new PersonalVaultConfig(List.of("vault_01"), 27);
    }

    /** Taille valide pour un coffre : multiple de 9 (une rangée) entre 9 et 54. */
    public static boolean isValidSlots(int slots) {
        return slots >= MIN_SLOTS && slots <= MAX_SLOTS && slots % 9 == 0;
    }
}
