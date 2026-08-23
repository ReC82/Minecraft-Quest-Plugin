package com.lodygames.rpgquest.claim.model;

import java.util.Set;
import java.util.UUID;

/**
 * Un claim de terrain : cuboïde protégé, correct par construction (même
 * discipline que {@code ZoneDefinition}/{@code PortalDefinition}), possédé
 * par un joueur (identifié par UUID, jamais par pseudo — les protections ne
 * doivent jamais dépendre d'un nom qui peut changer) et ouvert à des
 * membres de confiance en plus du propriétaire.
 *
 * <p>Aucun type Bukkit : {@code database.ClaimRepository} réutilise ce
 * modèle directement plutôt que de dupliquer un type « ligne de base de
 * données » séparé (contrairement à {@code database.MarketListingRecord}) —
 * {@code Claim} satisfait déjà la contrainte « testable sans MockBukkit »
 * que cette séparation protège habituellement.</p>
 */
public record Claim(
        String id,
        UUID owner,
        String world,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        int reservedMinX, int reservedMinY, int reservedMinZ,
        int reservedMaxX, int reservedMaxY, int reservedMaxZ,
        Set<UUID> members,
        ClaimFlags flags
) {

    public Claim {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id est obligatoire.");
        }
        if (!id.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException(
                    "id invalide : \"" + id + "\" (minuscules, chiffres, « _ » et « - » uniquement).");
        }
        if (owner == null) {
            throw new IllegalArgumentException("owner est obligatoire.");
        }
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world est obligatoire.");
        }
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("les bornes min doivent être inférieures ou égales aux bornes max.");
        }
        if (reservedMinX > reservedMaxX || reservedMinY > reservedMaxY || reservedMinZ > reservedMaxZ) {
            throw new IllegalArgumentException(
                    "les bornes min de réservation doivent être inférieures ou égales aux bornes max.");
        }
        if (reservedMinX > minX || reservedMinY > minY || reservedMinZ > minZ
                || reservedMaxX < maxX || reservedMaxY < maxY || reservedMaxZ < maxZ) {
            throw new IllegalArgumentException(
                    "le cuboïde de réservation doit toujours contenir le cuboïde actif du claim.");
        }
        members = members == null ? Set.of() : Set.copyOf(members);
        if (flags == null) {
            throw new IllegalArgumentException("flags est obligatoire.");
        }
    }

    /**
     * Compatibilité avec les claims sans modèle de réservation (ex. {@code /claim create} à la
     * baguette, sélection arbitraire) : la réservation vaut alors exactement le cuboïde actif —
     * aucun espace supplémentaire n'est mis de côté, comportement strictement identique à avant
     * l'introduction du modèle de réservation (voir {@code docs/CLAIMS.md}, « Modèle de palier »).
     */
    public Claim(String id, UUID owner, String world,
                 int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                 Set<UUID> members, ClaimFlags flags) {
        this(id, owner, world, minX, minY, minZ, maxX, maxY, maxZ,
                minX, minY, minZ, maxX, maxY, maxZ, members, flags);
    }

    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean overlaps(Claim other) {
        if (!world.equals(other.world)) {
            return false;
        }
        return minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    /**
     * Chevauchement des cuboïdes de <strong>réservation</strong> (jamais seulement le cuboïde
     * actif) : empêche un autre claim de s'installer dans l'espace mis de côté pour une future
     * extension de {@code other}, même si son cuboïde actif est aujourd'hui bien plus petit (mission
     * « réservation 100×100 » — voir docs/CLAIMS.md).
     */
    public boolean overlapsReservation(Claim other) {
        if (!world.equals(other.world)) {
            return false;
        }
        return reservedMinX <= other.reservedMaxX && reservedMaxX >= other.reservedMinX
                && reservedMinY <= other.reservedMaxY && reservedMaxY >= other.reservedMinY
                && reservedMinZ <= other.reservedMaxZ && reservedMaxZ >= other.reservedMinZ;
    }

    /** Propriétaire ou membre de confiance — jamais basé sur le pseudo, toujours sur l'UUID stocké. */
    public boolean isTrusted(UUID playerId) {
        return owner.equals(playerId) || members.contains(playerId);
    }

    public int width() {
        return maxX - minX + 1;
    }

    public int depth() {
        return maxZ - minZ + 1;
    }

    public int height() {
        return maxY - minY + 1;
    }
}
