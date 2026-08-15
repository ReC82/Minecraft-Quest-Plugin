package com.lodygames.rpgquest.travel;

import java.util.Optional;
import java.util.Random;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Cherche une position d'arrivée aléatoire « sûre » autour d'un centre (le spawn du monde
 * destination, en pratique), pour {@link com.lodygames.rpgquest.travel.model.DestinationStrategy#RANDOM_SAFE}.
 * Volontairement séparée de {@code WorldPortalTeleportListener} pour rester testable sans dépendre
 * de la téléportation elle-même ni de la génération de terrain réelle de Minecraft.
 *
 * <p>Chaque tentative tire un angle et une distance uniformes entre {@code minRadius} et {@code
 * maxRadius} autour du centre, charge la colonne de chunk visée à la demande (même choix
 * qu'un accès ponctuel naturel — voir {@code travel.PortalService#findSafeLocation}, jamais un
 * chargement forcé permanent), lit la hauteur de la plus haute colonne solide
 * ({@code World#getHighestBlockYAt}), puis vérifie que le sol est solide et non dangereux et que
 * les deux blocs au-dessus (pieds/tête) laissent la place au joueur. Une colonne d'eau, de vide ou
 * hors de la bordure du monde est rejetée sans lever d'exception — juste une tentative de plus,
 * jusqu'à {@code maxAttempts}.</p>
 */
public final class RandomSafeLocationFinder {

    private final int minRadius;
    private final int maxRadius;
    private final int maxAttempts;
    private final Random random;

    public RandomSafeLocationFinder(int minRadius, int maxRadius, int maxAttempts) {
        this(minRadius, maxRadius, maxAttempts, new Random());
    }

    /** Visible pour les tests : permet de contrôler le tirage aléatoire sans dépendre de sa graine. */
    RandomSafeLocationFinder(int minRadius, int maxRadius, int maxAttempts, Random random) {
        if (minRadius < 0) {
            throw new IllegalArgumentException("minRadius ne peut pas être négatif.");
        }
        if (maxRadius < minRadius) {
            throw new IllegalArgumentException("maxRadius doit être supérieur ou égal à minRadius.");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts doit être au moins 1.");
        }
        this.minRadius = minRadius;
        this.maxRadius = maxRadius;
        this.maxAttempts = maxAttempts;
        this.random = random;
    }

    /** {@code Optional.empty()} si aucune position sûre n'a été trouvée en {@code maxAttempts} tentatives — jamais de boucle infinie. */
    public Optional<Location> find(World world, Location center) {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double distance = minRadius + random.nextDouble() * (maxRadius - minRadius);
            int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);

            Location candidate = new Location(world, x + 0.5, 64, z + 0.5);
            if (!world.getWorldBorder().isInside(candidate)) {
                continue;
            }

            world.getChunkAt(x >> 4, z >> 4);
            int groundY = world.getHighestBlockYAt(x, z);
            if (groundY <= world.getMinHeight()) {
                continue; // colonne vide (vide, ou hors monde généré) : jamais une arrivée valide.
            }

            if (isSafeStandingSpot(world, x, groundY, z)) {
                return Optional.of(new Location(world, x + 0.5, groundY + 1, z + 0.5));
            }
        }
        return Optional.empty();
    }

    private boolean isSafeStandingSpot(World world, int x, int groundY, int z) {
        if (groundY + 2 >= world.getMaxHeight()) {
            return false;
        }
        Block ground = world.getBlockAt(x, groundY, z);
        Block feet = world.getBlockAt(x, groundY + 1, z);
        Block head = world.getBlockAt(x, groundY + 2, z);

        if (isDangerous(ground.getType()) || isDangerous(feet.getType()) || isDangerous(head.getType())) {
            return false;
        }
        if (!ground.getType().isSolid()) {
            return false; // pas de sol valide (AIR, eau, lave déjà exclue ci-dessus...) : jamais un point d'arrivée.
        }
        return !feet.getType().isSolid() && !head.getType().isSolid(); // assez de place pour le joueur.
    }

    private boolean isDangerous(Material type) {
        return type == Material.LAVA
                || type == Material.FIRE
                || type == Material.SOUL_FIRE
                || type == Material.MAGMA_BLOCK
                || type == Material.CACTUS
                || type == Material.CAMPFIRE
                || type == Material.SOUL_CAMPFIRE
                || type == Material.SWEET_BERRY_BUSH;
    }
}
