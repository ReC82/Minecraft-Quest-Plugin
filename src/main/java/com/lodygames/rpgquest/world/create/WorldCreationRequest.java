package com.lodygames.rpgquest.world.create;

import java.util.List;

/**
 * Demande de création d'un monde telle que saisie dans PlugAdmin (issue #562, #331-W3), avant
 * toute validation : les textes viennent tels quels du formulaire et sont normalisés par
 * {@link WorldCreationPlanner}.
 *
 * @param name        nom saisi ; l'identifiant du dossier en est déduit (minuscules, chiffres, {@code _}, {@code -})
 * @param environment {@code NORMAL}, {@code NETHER} ou {@code THE_END}
 * @param terrain     {@code NORMAL}, {@code FLAT}, {@code AMPLIFIED} ou {@code LARGE_BIOMES} (monde normal seulement)
 * @param seed        graine facultative : nombre, ou texte transformé comme le fait Minecraft
 * @param structures  générer villages, temples… (monde plat : villages et forteresses seulement)
 * @param flatPreset  préréglage de monde plat ({@link WorldCreationPlanner.FlatPreset}), facultatif
 * @param layers      couches du monde plat, du bas vers le haut ; vide = celles du préréglage
 * @param biome       biome unique du monde plat ({@code minecraft:plains}…) ; vide = celui du préréglage
 */
public record WorldCreationRequest(String name, String environment, String terrain, String seed,
                                   boolean structures, String flatPreset, List<Layer> layers, String biome) {

    /** Une couche de monde plat : un bloc et son épaisseur. */
    public record Layer(String block, int height) {
    }

    public WorldCreationRequest {
        layers = layers == null ? List.of() : List.copyOf(layers);
    }
}
