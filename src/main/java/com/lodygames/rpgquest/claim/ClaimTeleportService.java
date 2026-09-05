package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.travel.RandomSafeLocationFinder;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Résout et exécute la téléportation d'un joueur vers le claim {@link ClaimService#mainClaimOf
 * "principal"} d'un propriétaire (mission « Jo : retourner à son claim » / commande admin de
 * diagnostic) — jamais de coordonnées codées en dur : toujours dérivé du claim persistant lui-même,
 * donc valide après reconnexion et redémarrage du serveur. Réutilise {@link
 * RandomSafeLocationFinder#findAtColumn} pour la sécurité de la position d'arrivée (suffocation,
 * chute, lave...), jamais une seconde implémentation parallèle de recherche de position sûre.
 *
 * <p>{@code traveler} (celui qui est réellement téléporté) et {@code claimOwner} (le propriétaire du
 * claim ciblé) sont volontairement des paramètres distincts : le dialogue de Jo téléporte le joueur
 * vers son propre claim ({@code traveler == claimOwner}), la commande admin de diagnostic (<code>/claim
 * admin tp</code>) téléporte l'admin vers le claim d'un <em>autre</em> joueur, potentiellement hors
 * ligne ({@code traveler != claimOwner}).</p>
 */
public final class ClaimTeleportService {

    public enum Outcome { TELEPORTED, NO_MAIN_CLAIM, WORLD_UNAVAILABLE, NO_SAFE_LOCATION }

    private final RPGQuestPlugin plugin;
    private final ClaimService claimService;

    public ClaimTeleportService(RPGQuestPlugin plugin, ClaimService claimService) {
        this.plugin = plugin;
        this.claimService = claimService;
    }

    /** Résout le claim principal de {@code claimOwner} via {@link ClaimService#mainClaimOf}, puis délègue à {@link #teleport(Player, Claim)}. */
    public Outcome teleport(Player traveler, UUID claimOwner) {
        return claimService.mainClaimOf(claimOwner)
                .map(claim -> teleport(traveler, claim))
                .orElse(Outcome.NO_MAIN_CLAIM);
    }

    /** Variante pour un claim déjà résolu (ex. affichage d'informations avant téléportation dans la commande admin). */
    public Outcome teleport(Player traveler, Claim claim) {
        World world = plugin.getServer().getWorld(claim.world());
        if (world == null) {
            return Outcome.WORLD_UNAVAILABLE;
        }
        Optional<Location> safe = findSafeArrivalInClaim(world, claim);
        if (safe.isEmpty()) {
            return Outcome.NO_SAFE_LOCATION;
        }
        traveler.teleportAsync(safe.get());
        return Outcome.TELEPORTED;
    }

    /**
     * Centre du claim d'abord (cas courant, rapide) ; à défaut (centre dangereux/obstrué), balaie
     * toutes les colonnes du cuboïde <strong>actif</strong> du claim — jamais au-delà, la
     * destination reste toujours strictement sur la propriété du joueur.
     */
    private Optional<Location> findSafeArrivalInClaim(World world, Claim claim) {
        int centerX = (claim.minX() + claim.maxX()) / 2;
        int centerZ = (claim.minZ() + claim.maxZ()) / 2;
        Optional<Location> atCenter = RandomSafeLocationFinder.findAtColumn(world, centerX, centerZ);
        if (atCenter.isPresent()) {
            return atCenter;
        }
        for (int x = claim.minX(); x <= claim.maxX(); x++) {
            for (int z = claim.minZ(); z <= claim.maxZ(); z++) {
                if (x == centerX && z == centerZ) {
                    continue; // déjà tenté ci-dessus.
                }
                Optional<Location> found = RandomSafeLocationFinder.findAtColumn(world, x, z);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }
}
