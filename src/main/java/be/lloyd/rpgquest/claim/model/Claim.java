package be.lloyd.rpgquest.claim.model;

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
        members = members == null ? Set.of() : Set.copyOf(members);
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

    public boolean overlaps(Claim other) {
        if (!world.equals(other.world)) {
            return false;
        }
        return minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
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
