package com.lodygames.rpgquest.waypoint.model;

/**
 * Identité <strong>persistante et stable</strong> d'une « instance de biome » (issue #124).
 *
 * <p>Le MVP n'a pas les moyens de calculer un vrai <em>blob</em> de biome contigu (un flood-fill
 * borné sur les chunks chargés serait coûteux et non déterministe). L'instance est donc approximée
 * par une <strong>tuile spatiale biome-typée</strong> :</p>
 *
 * <pre>instance = (world, biomeKey, regionX, regionZ)</pre>
 *
 * <p>où {@code regionX = floor(blockX / regionSize)} et {@code regionZ = floor(blockZ / regionSize)}.
 * Conséquences voulues :</p>
 * <ul>
 *   <li>deux forêts séparées de plus de {@code regionSize} blocs tombent forcément dans deux
 *       régions différentes → deux instances → deux waypoints (exigence « deux forêts éloignées
 *       produisent deux waypoints ») ;</li>
 *   <li>deux biomes différents dans la même région restent deux instances distinctes
 *       ({@code biomeKey} diffère) — jamais un simple {@code biomeType -> waypoint} ;</li>
 *   <li>calcul O(1), sans scan terrain, déterministe et identique après redémarrage (le biome
 *       d'une coordonnée est figé par la seed du monde).</li>
 * </ul>
 *
 * <p>Compromis documenté : une même grande forêt à cheval sur une frontière de région peut produire
 * jusqu'à deux waypoints (un par région où un joueur est entré). Ce n'est pas un bug — juste deux
 * repères pour une très grande zone — et c'est borné par {@code minimum-spacing}. Le vrai
 * découpage en blobs de biome est renvoyé à un ticket ultérieur (cf. #122).</p>
 *
 * <p>{@code biomeKey} est la clé namespacée du biome ({@code minecraft:forest}), pas une valeur
 * d'enum : {@code org.bukkit.block.Biome} n'est plus un enum stable en 1.21, la chaîne l'est.</p>
 */
public record BiomeInstanceKey(String world, String biomeKey, long regionX, long regionZ) {

    public BiomeInstanceKey {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world obligatoire");
        }
        if (biomeKey == null || biomeKey.isBlank()) {
            throw new IllegalArgumentException("biomeKey obligatoire");
        }
    }

    /**
     * Forme sérialisée stockée dans la colonne {@code waypoints.biome_instance}. Indépendante du
     * modèle de rendu : changer le rendu ne change jamais cette valeur. Une future identité
     * (flood-fill) pourra réécrire cette seule colonne sans migration de schéma.
     */
    public String serialize() {
        return biomeKey + "@" + regionX + "," + regionZ;
    }

    /** Identifiant technique du waypoint de cette instance — déterministe, jamais fonction du rendu. */
    public String waypointId() {
        return "wp_" + sanitize(world) + "_" + sanitize(stripNamespace(biomeKey)) + "_" + regionX + "_" + regionZ;
    }

    private static String stripNamespace(String key) {
        int colon = key.indexOf(':');
        return colon >= 0 ? key.substring(colon + 1) : key;
    }

    private static String sanitize(String raw) {
        return raw.replaceAll("[^A-Za-z0-9]", "_");
    }
}
