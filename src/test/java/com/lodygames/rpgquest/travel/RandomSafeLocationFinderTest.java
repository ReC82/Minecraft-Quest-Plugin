package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.Random;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Couvre uniquement la logique de recherche/sécurité (voir docs-site/worlds.html, section
 * « Portail Hub → wild »), séparée de la téléportation elle-même — le tirage aléatoire est
 * remplacé par une séquence fixe injectée ({@link FixedDoubleRandom}) pour rester déterministe,
 * sans dépendre de la génération de terrain réelle de Minecraft (non simulée par MockBukkit).
 */
class RandomSafeLocationFinderTest {

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World world;
    private Location center;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        world = server.addSimpleWorld("wild");
        center = new Location(world, 0, 64, 0);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void setGround(int x, int y, int z, Material groundType) {
        world.getBlockAt(x, y, z).setType(groundType);
        world.getBlockAt(x, y + 1, z).setType(Material.AIR);
        world.getBlockAt(x, y + 2, z).setType(Material.AIR);
    }

    @Test
    void acceptsASafePositionWithSolidGroundAndHeadroom() {
        // angle=0 (est), fraction de distance=0.5 -> distance = 500 + 0.5*(5000-500) = 2750
        setGround(2750, 64, 0, Material.STONE);
        RandomSafeLocationFinder finder = new RandomSafeLocationFinder(500, 5000, 20, new FixedDoubleRandom(0.0, 0.5));

        Optional<Location> found = finder.find(world, center);

        assertTrue(found.isPresent());
        assertEquals(2750, found.get().getBlockX());
        assertEquals(0, found.get().getBlockZ());
        assertEquals(65, found.get().getBlockY(), "le joueur doit apparaître un bloc au-dessus du sol");
    }

    @Test
    void resultIsAlwaysWithinTheConfiguredRadius() {
        int minRadius = 500;
        int maxRadius = 5000;
        for (double fraction : new double[] {0.0, 0.25, 0.5, 0.75, 0.999}) {
            double distance = minRadius + fraction * (maxRadius - minRadius);
            int x = (int) Math.round(distance); // angle=0
            setGround(x, 64, 0, Material.STONE);

            RandomSafeLocationFinder finder = new RandomSafeLocationFinder(minRadius, maxRadius, 20, new FixedDoubleRandom(0.0, fraction));
            Location found = finder.find(world, center).orElseThrow();

            double actualDistance = Math.hypot(found.getX() - center.getX(), found.getZ() - center.getZ());
            assertTrue(actualDistance >= minRadius - 1 && actualDistance <= maxRadius + 1,
                    "distance " + actualDistance + " hors de [" + minRadius + ", " + maxRadius + "] pour fraction=" + fraction);
        }
    }

    @Test
    void rejectsLavaGroundAndRetriesUntilASafeColumnIsFound() {
        // Première tentative (angle=0, fraction=0.2) : lave. Deuxième tentative (angle=0, fraction=0.6) : sûre.
        double unsafeDistance = 500 + 0.2 * (5000 - 500);
        double safeDistance = 500 + 0.6 * (5000 - 500);
        int unsafeX = (int) Math.round(unsafeDistance);
        int safeX = (int) Math.round(safeDistance);
        setGround(unsafeX, 64, 0, Material.LAVA);
        setGround(safeX, 64, 0, Material.STONE);

        RandomSafeLocationFinder finder = new RandomSafeLocationFinder(
                500, 5000, 20, new FixedDoubleRandom(0.0, 0.2, 0.0, 0.6));

        Location found = finder.find(world, center).orElseThrow();

        assertEquals(safeX, found.getBlockX());
    }

    @Test
    void rejectsGroundTooCloseToTheWorldHeightLimit() {
        // MockBukkit génère un monde superflat minimal (herbe par défaut) : la seule façon réaliste
        // de refuser une colonne par ailleurs sûre est de la placer trop près du plafond du monde.
        int x = 2000;
        int groundY = world.getMaxHeight() - 2; // groundY + 2 == maxHeight : jamais assez de place.
        world.getBlockAt(x, groundY, 0).setType(Material.STONE);
        double fraction = (double) (x - 500) / (5000 - 500);

        RandomSafeLocationFinder finder = new RandomSafeLocationFinder(500, 5000, 1, new FixedDoubleRandom(0.0, fraction));

        assertTrue(finder.find(world, center).isEmpty(), "trop proche du plafond du monde : aucune position trouvée");
    }

    @Test
    void respectsTheMaximumNumberOfAttemptsAndNeverLoopsForever() {
        // Même colonne dangereuse (lave) à chaque tentative (random fixe) : jamais de position sûre.
        world.getBlockAt(500, 4, 0).setType(Material.LAVA); // (500, 4) = angle 0, fraction 0.0 -> distance = minRadius
        int maxAttempts = 5;
        CountingRandom random = new CountingRandom();
        RandomSafeLocationFinder finder = new RandomSafeLocationFinder(500, 5000, maxAttempts, random);

        Optional<Location> found = finder.find(world, center);

        assertTrue(found.isEmpty());
        assertEquals(maxAttempts * 2, random.calls, "exactement 2 tirages (angle, distance) par tentative, jamais plus que maxAttempts");
    }

    @Test
    void fallsBackToEmptyWhenNoSafePositionExistsWithinTheRadius() {
        // Les deux seules colonnes jamais tirées (fractions 0.1 et 0.7) sont toutes deux dangereuses.
        int x1 = (int) Math.round(500 + 0.1 * (5000 - 500));
        int x2 = (int) Math.round(500 + 0.7 * (5000 - 500));
        world.getBlockAt(x1, 4, 0).setType(Material.LAVA);
        world.getBlockAt(x2, 4, 0).setType(Material.CACTUS);
        RandomSafeLocationFinder finder = new RandomSafeLocationFinder(
                500, 5000, 4, new FixedDoubleRandom(0.0, 0.1, 0.0, 0.7, 0.0, 0.1, 0.0, 0.7));

        assertTrue(finder.find(world, center).isEmpty());
    }

    @Test
    void candidatesOutsideTheWorldBorderAreRejected() {
        world.getWorldBorder().setCenter(center);
        world.getWorldBorder().setSize(100); // bien plus petit que minRadius=500 : tout candidat est hors bordure
        setGround(2750, 64, 0, Material.STONE); // serait sûr, mais hors bordure

        RandomSafeLocationFinder finder = new RandomSafeLocationFinder(500, 5000, 3, new FixedDoubleRandom(0.0, 0.5, 0.0, 0.5, 0.0, 0.5));

        assertTrue(finder.find(world, center).isEmpty(), "hors de la bordure du monde : jamais accepté, même sur un sol sûr");
    }

    /** Retourne une séquence fixe de valeurs à chaque appel de {@code nextDouble()} (0.0 au-delà de la séquence). */
    private static final class FixedDoubleRandom extends Random {
        private final Deque<Double> values;

        FixedDoubleRandom(double... values) {
            this.values = new ArrayDeque<>();
            for (double value : values) {
                this.values.add(value);
            }
        }

        @Override
        public double nextDouble() {
            Double next = values.poll();
            return next != null ? next : 0.0;
        }
    }

    private static final class CountingRandom extends Random {
        private int calls = 0;

        @Override
        public double nextDouble() {
            calls++;
            return 0.0;
        }
    }
}
