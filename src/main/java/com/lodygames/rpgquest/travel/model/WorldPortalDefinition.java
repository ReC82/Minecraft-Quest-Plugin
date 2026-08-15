package com.lodygames.rpgquest.travel.model;

/**
 * Un portail simple entre deux mondes entiers : une zone d'activation cuboïde (même forme que
 * {@code ZoneDefinition}/{@code PortalDefinition}, délibérément dupliquée plutôt que réutilisée —
 * voir {@code PortalDefinition}, même rationale) dans un monde source, reliée directement au
 * <strong>spawn</strong> d'un monde destination (résolu dynamiquement à chaque activation via
 * {@code World#getSpawnLocation()}, jamais une position figée) — contrairement à
 * {@code PortalDefinition}, aucune canalisation, aucun coût, aucune condition de quête/niveau :
 * l'entrée dans la zone téléporte immédiatement (voir {@code travel.WorldPortalTeleportListener}).
 */
public record WorldPortalDefinition(
        String id,
        String world,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        String destinationWorld,
        boolean enabled,
        DestinationStrategy destinationStrategy
) {

    public WorldPortalDefinition {
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
        if (destinationWorld == null || destinationWorld.isBlank()) {
            throw new IllegalArgumentException("destinationWorld est obligatoire.");
        }
        if (destinationStrategy == null) {
            destinationStrategy = DestinationStrategy.WORLD_SPAWN;
        }
    }

    /** Équivalent à {@code destinationStrategy = DestinationStrategy.WORLD_SPAWN} — conserve les appelants existants inchangés. */
    public WorldPortalDefinition(String id, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                  String destinationWorld, boolean enabled) {
        this(id, world, minX, minY, minZ, maxX, maxY, maxZ, destinationWorld, enabled, DestinationStrategy.WORLD_SPAWN);
    }

    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean overlaps(WorldPortalDefinition other) {
        if (!world.equals(other.world)) {
            return false;
        }
        return minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }
}
