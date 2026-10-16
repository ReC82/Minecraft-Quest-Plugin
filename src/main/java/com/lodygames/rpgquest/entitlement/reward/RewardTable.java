package com.lodygames.rpgquest.entitlement.reward;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Table de récompenses aléatoires partagée (issue #177, docs/REWARD_ENTITLEMENTS.md §10), section
 * {@code tables} de {@code entitlements.yml}. Chaque entrée désigne une récompense du catalogue : la
 * remise (objets, avantage, inventaire plein, au plus une fois) est celle des droits à récompense.
 *
 * @param mode    sémantique du tirage, toujours explicite (jamais déduite des pourcentages)
 * @param enabled table désactivée = aucun tirage, la référence est conservée
 */
public record RewardTable(String id, String label, Mode mode, boolean enabled, List<Entry> entries) {

    /** 100 % exprimés en centièmes de pour cent : les chances sont des entiers, sans arrondi flottant. */
    public static final int FULL = 10_000;

    public RewardTable {
        entries = List.copyOf(entries);
    }

    public enum Mode {
        /** Un tirage par entrée : plusieurs gains possibles, ou aucun ; la somme peut dépasser 100 %. */
        INDEPENDENT("independent", "Chances indépendantes"),
        /** Entrées exclusives : au plus un gain, somme ≤ 100 %, le reste = aucun gain. */
        SINGLE("single", "Une récompense maximum");

        private final String yaml;
        private final String label;

        Mode(String yaml, String label) {
            this.yaml = yaml;
            this.label = label;
        }

        public String yaml() {
            return yaml;
        }

        public String label() {
            return label;
        }

        public static Mode fromYaml(String raw) {
            for (Mode mode : values()) {
                if (mode.yaml.equalsIgnoreCase(raw)) {
                    return mode;
                }
            }
            return null;
        }
    }

    /**
     * @param chance chance en centièmes de pour cent ({@code 1250} = 12,5 %), de 1 à {@link #FULL}
     */
    public record Entry(String id, String rewardKey, int chance) {
    }

    /** Somme des chances, en centièmes de pour cent. */
    public int totalChance() {
        return entries.stream().mapToInt(Entry::chance).sum();
    }

    /**
     * Empreinte courte du contenu qui décide d'un tirage (mode, entrées, chances, récompenses) :
     * figée dans la trace de chaque gain, elle dit avec quelle version de la table il a été tiré.
     */
    public String version() {
        StringBuilder canonical = new StringBuilder(mode.yaml());
        for (Entry entry : entries) {
            canonical.append('|').append(entry.id()).append('=').append(entry.rewardKey()).append('@')
                    .append(entry.chance());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    /** « 12,5 % » : forme lisible d'une chance en centièmes de pour cent. */
    public static String percent(int chance) {
        String text = chance % 100 == 0 ? String.valueOf(chance / 100)
                : String.format(java.util.Locale.ROOT, "%d,%02d", chance / 100, chance % 100).replaceAll("0+$", "");
        return text + " %";
    }
}
