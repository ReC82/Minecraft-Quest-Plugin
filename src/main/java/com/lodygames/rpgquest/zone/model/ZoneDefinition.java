package com.lodygames.rpgquest.zone.model;

/**
 * Une zone protégée cuboïde, correcte par construction (bornes min/max déjà
 * normalisées et validées). Les coordonnées sont inclusives des deux côtés
 * (un bloc pile sur {@code maxX} est considéré à l'intérieur).
 */
public record ZoneDefinition(
        String id,
        String world,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        ZoneFlags flags
) {

    public ZoneDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id est obligatoire.");
        }
        if (!id.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException(
                    "id invalide : \"" + id + "\" (minuscules, chiffres, « _ » et « - » uniquement).");
        }
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world est obligatoire.");
        }
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("les bornes min doivent être inférieures ou égales aux bornes max.");
        }
        if (flags == null) {
            throw new IllegalArgumentException("flags est obligatoire.");
        }
    }

    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean overlaps(ZoneDefinition other) {
        if (!world.equals(other.world)) {
            return false;
        }
        return minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }
}
