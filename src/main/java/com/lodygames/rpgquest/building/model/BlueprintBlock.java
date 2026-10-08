package com.lodygames.rpgquest.building.model;

/**
 * Un bloc d'un plan de bâtiment, en coordonnées locales (issue #213, lot « placement »).
 *
 * <h2>Pourquoi un état de bloc en texte</h2>
 *
 * <p>{@code state} est la forme canonique Minecraft, par exemple
 * {@code minecraft:oak_stairs[facing=north]}. C'est volontairement une <strong>chaîne</strong> et
 * non un type WorldEdit ou Bukkit : le plan de la hutte reste ainsi du code RPGQuest ordinaire,
 * relisible et testable sans serveur, et l'adaptateur est le seul à savoir traduire ces chaînes.</p>
 *
 * <p>C'est aussi la seule représentation qui survit à un changement de moteur : la notation
 * {@code bloc[propriété=valeur]} est celle du jeu, pas celle d'une bibliothèque.</p>
 */
public record BlueprintBlock(int x, int y, int z, String state) {

    public BlueprintBlock {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("état de bloc obligatoire");
        }
        if (x < 0 || y < 0 || z < 0) {
            throw new IllegalArgumentException(
                    "coordonnée locale négative : " + x + " / " + y + " / " + z);
        }
    }

    /** Le type seul, sans ses propriétés — pour la liste des matériaux principaux. */
    public String material() {
        int bracket = state.indexOf('[');
        String type = bracket < 0 ? state : state.substring(0, bracket);
        int colon = type.indexOf(':');
        return colon < 0 ? type : type.substring(colon + 1);
    }
}
