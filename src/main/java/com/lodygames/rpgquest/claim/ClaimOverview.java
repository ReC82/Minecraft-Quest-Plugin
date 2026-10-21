package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.claim.model.ClaimTier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Issue #637 — relevé <strong>en lecture seule</strong> de tous les claims persistés, pour la page
 * « Claims » de PlugAdmin et la fiche joueur. Rien n'est inventé : chaque valeur vient du claim
 * chargé par {@link ClaimService} (la base), du serveur (monde chargé) ou de l'annuaire des joueurs
 * (pseudo, absent s'il est inconnu).
 *
 * <p>Le palier est <strong>déduit de la géométrie réelle</strong> du claim (carré dont le côté est
 * exactement celui d'un {@link ClaimTier}) — c'est la règle même de {@link
 * ClaimService#upgradeTier}. Un claim posé à la baguette, de forme libre, n'a pas de palier : la
 * valeur est alors vide, jamais devinée.</p>
 *
 * <p>Le claim « principal » est celui que {@link ClaimService#mainClaimOf} désigne — la même source
 * que le portail CLAIM_HOME, Jo et {@code /claim admin tp} — jamais une seconde règle.</p>
 */
public final class ClaimOverview {

    /**
     * @param ownerName pseudo connu du serveur, {@code null} s'il est inconnu (jamais inventé)
     * @param tier      palier déduit de la taille réelle, {@code null} si la forme n'en est pas un
     */
    public record Entry(String id, UUID owner, String ownerName, boolean main, String world, boolean worldLoaded,
                        boolean inClaimsWorld,
                        int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                        int reservedMinX, int reservedMinZ, int reservedMaxX, int reservedMaxZ,
                        int width, int depth, int height, int centerX, int centerZ,
                        String tier, int members, boolean publicRedstone) {
    }

    /**
     * @param available   {@code false} = le service des claims n'existe pas sur ce serveur (distinct
     *                    d'un relevé vide : « aucun claim »)
     * @param claimsWorld monde des claims configuré ({@code claims.world})
     */
    public record View(boolean available, String claimsWorld, List<Entry> claims, int owners) {

        public static View unavailable() {
            return new View(false, "", List.of(), 0);
        }
    }

    private ClaimOverview() {
    }

    public static View build(List<Claim> claims, Function<UUID, Optional<Claim>> mainClaimOf,
                             Function<UUID, String> ownerName, Predicate<String> worldLoaded, String claimsWorld) {
        List<Entry> entries = new ArrayList<>();
        Set<UUID> owners = new HashSet<>();
        for (Claim claim : claims) {
            owners.add(claim.owner());
            boolean main = mainClaimOf.apply(claim.owner()).map(m -> m.id().equals(claim.id())).orElse(false);
            entries.add(new Entry(claim.id(), claim.owner(), ownerName.apply(claim.owner()), main, claim.world(),
                    worldLoaded.test(claim.world()), claim.world().equals(claimsWorld),
                    claim.minX(), claim.minY(), claim.minZ(), claim.maxX(), claim.maxY(), claim.maxZ(),
                    claim.reservedMinX(), claim.reservedMinZ(), claim.reservedMaxX(), claim.reservedMaxZ(),
                    claim.width(), claim.depth(), claim.height(),
                    (claim.minX() + claim.maxX()) / 2, (claim.minZ() + claim.maxZ()) / 2,
                    tierOf(claim).map(Enum::name).orElse(null), claim.members().size(),
                    claim.flags().allowPublicRedstone()));
        }
        return new View(true, claimsWorld == null ? "" : claimsWorld, entries, owners.size());
    }

    /** Palier dont le côté actif est exactement celui du claim (carré), sinon vide. */
    static Optional<ClaimTier> tierOf(Claim claim) {
        if (claim.width() != claim.depth()) {
            return Optional.empty();
        }
        for (ClaimTier tier : ClaimTier.values()) {
            if (tier.activeSize() == claim.width()) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }
}
