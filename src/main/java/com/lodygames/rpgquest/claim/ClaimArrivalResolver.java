package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.config.ClaimConfig;
import com.lodygames.rpgquest.travel.RandomSafeLocationFinder;
import com.lodygames.rpgquest.travel.WorldPortalTeleportListener;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * L'arrivée dans le monde des claims par un World-Portal « CLAIM_HOME » (issues #73, #208).
 *
 * <h2>Le défaut</h2>
 *
 * <p>Le portail réel {@code hub_to_claims} était en {@code WORLD_SPAWN} : toute entrée — premier
 * passage, retour, joueur réinitialisé — tombait au même point. Après un reset (#208), le joueur
 * rentrait donc là où il avait déjà utilisé son Acte, devant ses anciennes constructions (le reset
 * supprime la protection, jamais les blocs), et y reposait son nouveau claim.</p>
 *
 * <h2>La règle</h2>
 *
 * <ul>
 *   <li><strong>Un claim principal dans ce monde</strong> : arrivée SUR ce claim, sur une colonne sûre
 *       — dérivée du claim persistant, donc juste après reconnexion ou redémarrage, et jamais un
 *       ancien claim supprimé (la source est {@link ClaimService}, rechargée après chaque reset) ;</li>
 *   <li><strong>aucun claim</strong> (première entrée, ou après reset) : position sûre aléatoire
 *       entre {@code claims.arrival.min-radius} et {@code max-radius} du spawn, à au moins
 *       {@code claims.arrival.spacing} blocs de tout claim existant — de quoi poser une réserve sans
 *       chevaucher personne, et jamais sur le terrain d'un autre.</li>
 * </ul>
 *
 * <p>Vide si rien ne convient : le portail retombe sur RANDOM_SAFE puis sur le spawn, en le
 * journalisant. Aucun claim, aucune donnée n'est écrit ici.</p>
 */
public final class ClaimArrivalResolver implements WorldPortalTeleportListener.ArrivalResolver {

    private static final int ATTEMPTS = 40;

    private final ClaimService claimService;
    private final Supplier<ClaimConfig> config;

    public ClaimArrivalResolver(ClaimService claimService, Supplier<ClaimConfig> config) {
        this.claimService = claimService;
        this.config = config;
    }

    @Override
    public Optional<Location> resolve(Player player, World world) {
        Optional<Claim> home = claimService.mainClaimOf(player.getUniqueId())
                .filter(claim -> claim.world().equals(world.getName()));
        if (home.isPresent()) {
            Optional<Location> onClaim = ClaimTeleportService.findSafeArrivalInClaim(world, home.get());
            if (onClaim.isPresent()) {
                return onClaim;
            }
        }
        ClaimConfig c = config.get();
        List<Claim> claims = claimService.claimsInWorld(world.getName());
        RandomSafeLocationFinder finder = new RandomSafeLocationFinder(c.arrivalMinRadius(), c.arrivalMaxRadius(),
                ATTEMPTS);
        return finder.find(world, world.getSpawnLocation(), new RandomSafeLocationFinder.SearchMetrics(),
                (x, z) -> farFromClaims(claims, x, z, c.arrivalSpacing()));
    }

    /**
     * La colonne est-elle à au moins {@code spacing} blocs (distance de Tchebychev) de tout claim —
     * mesurée depuis sa RÉSERVE (l'emprise promise aux paliers suivants), pas seulement sa partie
     * active : une arrivée ne doit pas se poser là où un voisin grandira.
     */
    static boolean farFromClaims(List<Claim> claims, int x, int z, int spacing) {
        for (Claim claim : claims) {
            int minX = Math.min(claim.minX(), claim.reservedMinX());
            int maxX = Math.max(claim.maxX(), claim.reservedMaxX());
            int minZ = Math.min(claim.minZ(), claim.reservedMinZ());
            int maxZ = Math.max(claim.maxZ(), claim.reservedMaxZ());
            int dx = Math.max(0, Math.max(minX - x, x - maxX));
            int dz = Math.max(0, Math.max(minZ - z, z - maxZ));
            if (Math.max(dx, dz) < spacing) {
                return false;
            }
        }
        return true;
    }
}
