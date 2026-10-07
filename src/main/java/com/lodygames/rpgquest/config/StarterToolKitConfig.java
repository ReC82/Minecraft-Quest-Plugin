package com.lodygames.rpgquest.config;

import java.util.List;
import java.util.Optional;

/**
 * Section {@code starter-tool-kit:} — kit de départ demandé explicitement au Guide (issue #26,
 * partie A), désormais organisé en <strong>paliers</strong> (issue #218) : une série de quêtes
 * améliore progressivement le kit que le joueur récupère après une mort.
 *
 * <p>Définition purement déclarative : les matériaux de chaque palier vivent dans {@code config.yml},
 * jamais dans le code d'un PNJ ni d'une quête (voir {@code player.StarterToolKitService}).</p>
 *
 * <p><strong>Compatibilité</strong> : un {@code config.yml} antérieur à #218 ne contient que
 * {@code items:} (le kit unique de #26). Il reste lu tel quel et devient le palier 1 — un serveur
 * déjà déployé ne perd donc rien et n'a aucune édition à faire. {@code ConfigFileCompleter} ajoutera
 * la section {@code tiers:} au prochain démarrage, et c'est elle qui prendra alors le relais.</p>
 */
public record StarterToolKitConfig(boolean enabled, List<StarterKitTier> tiers) {

    public StarterToolKitConfig {
        tiers = List.copyOf(tiers);
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("au moins un palier est nécessaire (le palier 1).");
        }
    }

    /** Palier le plus élevé réellement défini — plafond de toute progression. */
    public int maxLevel() {
        return tiers.stream().mapToInt(StarterKitTier::level).max().orElse(1);
    }

    public Optional<StarterKitTier> tier(int level) {
        return tiers.stream().filter(t -> t.level() == level).findFirst();
    }

    /**
     * Palier réellement applicable pour un joueur ayant débloqué {@code unlockedLevel} : le plus
     * haut palier défini dont le niveau est inférieur ou égal. Borne volontaire — une valeur
     * aberrante en base (palier retiré de la configuration, variable éditée à la main) donne le
     * meilleur palier existant, jamais une absence de kit.
     */
    public Optional<StarterKitTier> effectiveTier(int unlockedLevel) {
        return tiers.stream()
                .filter(t -> t.level() <= Math.max(1, unlockedLevel))
                .max((a, b) -> Integer.compare(a.level(), b.level()));
    }
}
