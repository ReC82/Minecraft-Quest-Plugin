package com.lodygames.rpgquest.resource.model;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;

/**
 * Définition immuable d'un <b>type</b> de nœud de ressource (ex.
 * {@code crystal_ore}) — pas une instance placée dans le monde, voir {@code
 * resource.ResourceNodeService} pour les positions. « Correct par
 * construction », même discipline que {@code QuestDefinition}/{@code
 * CustomItemDefinition} : les invariants sont validés ici, pas seulement par
 * le parseur.
 */
public record ResourceNodeDefinition(
        NamespacedKey id,
        Material activeMaterial,
        Material depletedMaterial,
        List<Material> requiredTools,
        int respawnSeconds,
        List<ResourceDrop> drops
) {

    public ResourceNodeDefinition {
        if (activeMaterial == null) {
            throw new IllegalArgumentException("activeMaterial est obligatoire.");
        }
        if (depletedMaterial == null) {
            throw new IllegalArgumentException("depletedMaterial est obligatoire.");
        }
        if (activeMaterial == depletedMaterial) {
            throw new IllegalArgumentException(
                    "activeMaterial et depletedMaterial doivent être différents (sinon aucun indice visuel de récolte).");
        }
        if (respawnSeconds <= 0) {
            throw new IllegalArgumentException("respawnSeconds doit être strictement positif : " + respawnSeconds);
        }
        if (drops == null || drops.isEmpty()) {
            throw new IllegalArgumentException("drops doit contenir au moins une entrée.");
        }
        requiredTools = List.copyOf(requiredTools == null ? List.of() : requiredTools);
        drops = List.copyOf(drops);
    }

    /** {@code true} si n'importe quel outil (ou la main nue) permet de récolter ce nœud. */
    public boolean anyToolAllowed() {
        return requiredTools.isEmpty();
    }

    public int totalWeight() {
        return drops.stream().mapToInt(ResourceDrop::weight).sum();
    }
}
