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

    // ---- findAtColumn (colonne fixe, sans tirage aléatoire — voir claim.ClaimTeleportService) ------

    @Test
    void findAtColumnAcceptsASafeColumn() {
        setGround(5, 64, 7, Material.STONE);

        Optional<Location> found = RandomSafeLocationFinder.findAtColumn(world, 5, 7);

        assertTrue(found.isPresent());
        assertEquals(5, found.get().getBlockX());
        assertEquals(7, found.get().getBlockZ());
        assertEquals(65, found.get().getBlockY(), "le joueur doit apparaître un bloc au-dessus du sol");
    }

    @Test
    void findAtColumnRejectsLava() {
        setGround(5, 64, 7, Material.LAVA);

        assertTrue(RandomSafeLocationFinder.findAtColumn(world, 5, 7).isEmpty());
    }

    @Test
    void findAtColumnRejectsAnEmptyColumn() {
        // MockBukkit génère un monde superflat minimal (herbe par défaut, voir le test ci-dessous) :
        // colonne rendue explicitement vide (air) jusqu'au-dessus de ce sol par défaut.
        for (int y = world.getMinHeight(); y <= 70; y++) {
            world.getBlockAt(999, y, 999).setType(Material.AIR);
        }

        assertTrue(RandomSafeLocationFinder.findAtColumn(world, 999, 999).isEmpty());
    }

    @Test
    void findAtColumnRejectsOutsideTheWorldBorder() {
        world.getWorldBorder().setCenter(center);
        world.getWorldBorder().setSize(10);
        setGround(500, 64, 0, Material.STONE);

        assertTrue(RandomSafeLocationFinder.findAtColumn(world, 500, 0).isEmpty());
    }

    // ---- findAccessibleColumn (issue #153 : jamais une structure au sommet d'un arbre/surplomb) ---

    @Test
    void findAccessibleColumnAcceptsAnOrdinaryFlatColumn() {
        setGround(40, 64, 40, Material.STONE);
        for (int[] offset : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            setGround(40 + offset[0], 64, 40 + offset[1], Material.STONE);
        }

        Optional<Location> found = RandomSafeLocationFinder.findAccessibleColumn(world, 40, 40);

        assertTrue(found.isPresent(), "un sol plat ordinaire, entouré de voisins praticables, doit être accepté");
    }

    @Test
    void findAccessibleColumnRejectsLeafCanopyGround() {
        // Reproduit le cas réel #153 : un waypoint posé au sommet du feuillage d'un acacia.
        world.getBlockAt(50, 70, 50).setType(Material.ACACIA_LEAVES);
        world.getBlockAt(50, 71, 50).setType(Material.AIR);
        world.getBlockAt(50, 72, 50).setType(Material.AIR);

        assertTrue(RandomSafeLocationFinder.findAccessibleColumn(world, 50, 50).isEmpty(),
                "un sol de feuillage ne doit jamais être accepté comme ancre de structure");
    }

    @Test
    void findAccessibleColumnRejectsAnIsolatedColumnWithNoWalkableNeighbor() {
        // Colonne cible sûre en elle-même, mais ses 4 voisins sont totalement vides (surplomb/îlot) :
        // aucune approche à pied n'est possible sans tomber dans le vide.
        setGround(60, 64, 60, Material.STONE);
        for (int[] offset : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            for (int y = world.getMinHeight(); y <= 70; y++) {
                world.getBlockAt(60 + offset[0], y, 60 + offset[1]).setType(Material.AIR);
            }
        }

        assertTrue(RandomSafeLocationFinder.findAccessibleColumn(world, 60, 60).isEmpty(),
                "une colonne isolée sans voisin praticable (surplomb/îlot) doit être refusée");
    }

    /**
     * Issue #153 — le ticket décide de rejeter « feuillage/canopée, <strong>sommet de tronc
     * inaccessible</strong> ». Seul le feuillage l'était : une structure posée sur un rondin est
     * exactement aussi inatteignable à pied, et le Hub interdit la casse comme la pose.
     */
    @Test
    void findAccessibleColumnRejectsATrunkTop() {
        for (Material trunk : new Material[] {
                Material.OAK_LOG, Material.STRIPPED_BIRCH_LOG, Material.ACACIA_WOOD,
                Material.WARPED_STEM, Material.CRIMSON_HYPHAE, Material.MANGROVE_ROOTS}) {
            int x = 300 + trunk.ordinal() % 97;
            setGround(x, 70, 300, trunk);
            for (int[] offset : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                setGround(x + offset[0], 70, 300 + offset[1], Material.STONE);
            }

            assertTrue(RandomSafeLocationFinder.findAccessibleColumn(world, x, 300).isEmpty(),
                    () -> "un sommet de " + trunk + " ne doit jamais recevoir de structure");
        }
    }

    /** Un arbre à côté n'est pas un problème : c'est la SURFACE qu'on refuse, pas le voisinage. */
    @Test
    void findAccessibleColumnStillAcceptsNormalGroundNextToATree() {
        setGround(320, 64, 320, Material.GRASS_BLOCK);
        setGround(321, 64, 320, Material.OAK_LOG);   // un tronc juste à côté
        setGround(319, 64, 320, Material.STONE);     // et un vrai sol praticable de l'autre côté
        for (int[] offset : new int[][] {{0, 1}, {0, -1}}) {
            setGround(320 + offset[0], 64, 320 + offset[1], Material.STONE);
        }

        assertTrue(RandomSafeLocationFinder.findAccessibleColumn(world, 320, 320).isPresent(),
                "un sol normal au pied d'un arbre reste un emplacement valable");
    }

    /** Un tronc voisin ne peut pas servir d'approche à pied : il faut un vrai sol. */
    @Test
    void findAccessibleColumnRejectsAColumnWhoseOnlyNeighbourIsATrunk() {
        setGround(340, 64, 340, Material.STONE);
        for (int[] offset : new int[][] {{1, 0}, {0, 1}, {0, -1}}) {
            for (int y = world.getMinHeight(); y <= 70; y++) {
                world.getBlockAt(340 + offset[0], y, 340 + offset[1]).setType(Material.AIR);
            }
        }
        setGround(339, 64, 340, Material.JUNGLE_LOG); // seul voisin : un tronc

        assertTrue(RandomSafeLocationFinder.findAccessibleColumn(world, 340, 340).isEmpty(),
                "un sommet de tronc n'est pas une approche praticable");
    }

    @Test
    void findAccessibleColumnRejectsWaterAndLavaSurfaces() {
        for (Material fluid : new Material[] {Material.WATER, Material.LAVA}) {
            int x = 360 + fluid.ordinal() % 89;
            setGround(x, 64, 360, fluid);
            for (int[] offset : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                setGround(x + offset[0], 64, 360 + offset[1], Material.STONE);
            }

            assertTrue(RandomSafeLocationFinder.findAccessibleColumn(world, x, 360).isEmpty(),
                    () -> fluid + " ne doit jamais servir de sol à une structure");
        }
    }

    /**
     * Dégagement du corps : il manque deux blocs libres au-dessus du sol.
     *
     * <p>Constat de l'audit #153 du 2026-10-11 : ce contrôle ne peut mordre <strong>que</strong> à la
     * limite de hauteur du monde. Ailleurs il est structurellement inatteignable, parce que la
     * surface vient de {@code getHighestBlockYAt} — au-dessus du bloc le plus haut, les blocs des
     * pieds et de la tête sont libres par définition. Un plafond réel au-dessus du sol déplace
     * simplement la surface vers ce plafond, et c'est alors ce plafond qui est jugé.</p>
     */
    @Test
    void findAccessibleColumnRejectsAColumnPressedAgainstTheWorldCeiling() {
        int top = world.getMaxHeight() - 1;
        world.getBlockAt(390, top, 390).setType(Material.STONE);
        for (int[] offset : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            world.getBlockAt(390 + offset[0], top, 390 + offset[1]).setType(Material.STONE);
        }

        assertTrue(RandomSafeLocationFinder.findAccessibleColumn(world, 390, 390).isEmpty(),
                "collé au plafond du monde, il ne reste pas la place de tenir debout");
    }

    /** Pente raide : un écart de plus d'un bloc n'est pas une marche, c'est une falaise. */
    @Test
    void findAccessibleColumnRejectsNeighboursMoreThanOneBlockApart() {
        setGround(400, 64, 400, Material.STONE);
        for (int[] offset : new int[][] {{1, 0}, {0, 1}, {0, -1}}) {
            for (int y = world.getMinHeight(); y <= 80; y++) {
                world.getBlockAt(400 + offset[0], y, 400 + offset[1]).setType(Material.AIR);
            }
        }
        setGround(399, 68, 400, Material.STONE); // seul voisin, 4 blocs plus haut : falaise

        assertTrue(RandomSafeLocationFinder.findAccessibleColumn(world, 400, 400).isEmpty(),
                "un voisin à 4 blocs d'écart ne constitue pas une approche à pied");
    }

    @Test
    void findAccessibleColumnAcceptsAStepUpOrDownOfOneBlock() {
        // Un seul voisin praticable, à ±1 bloc de hauteur (marche normale) : doit suffire.
        setGround(70, 64, 70, Material.STONE);
        for (int[] offset : new int[][] {{1, 0}, {0, 1}, {0, -1}}) {
            for (int y = world.getMinHeight(); y <= 70; y++) {
                world.getBlockAt(70 + offset[0], y, 70 + offset[1]).setType(Material.AIR);
            }
        }
        setGround(69, 65, 70, Material.STONE); // voisin restant, un bloc plus haut : marche normale.

        assertTrue(RandomSafeLocationFinder.findAccessibleColumn(world, 70, 70).isPresent(),
                "un seul voisin praticable à un bloc d'écart doit suffire à valider l'accès");
    }

    /**
     * Issue #161 (diagnostic de latence) : la recherche renseigne le nombre de colonnes réellement
     * tirées et le temps passé à charger/générer des chunks, sans changer son résultat.
     */
    @Test
    void theSearchReportsItsAttemptsAndChunkLoadingTime() {
        setGround(2750, 64, 0, Material.STONE);
        RandomSafeLocationFinder finder = new RandomSafeLocationFinder(500, 5000, 20, new FixedDoubleRandom(0.0, 0.5));
        RandomSafeLocationFinder.SearchMetrics metrics = new RandomSafeLocationFinder.SearchMetrics();

        assertTrue(finder.find(world, center, metrics).isPresent());

        assertEquals(1, metrics.attempts(), "une seule colonne a suffi");
        assertTrue(metrics.chunkLoadNanos() > 0, "le temps de chargement de chunk doit être mesuré");
    }

    @Test
    void anExhaustedSearchReportsEveryAttempt() {
        // Bordure minuscule : aucun candidat n'est jamais valide, toutes les tentatives sont brûlées.
        world.getWorldBorder().setCenter(center);
        world.getWorldBorder().setSize(10);
        RandomSafeLocationFinder finder = new RandomSafeLocationFinder(500, 5000, 7);
        RandomSafeLocationFinder.SearchMetrics metrics = new RandomSafeLocationFinder.SearchMetrics();

        assertTrue(finder.find(world, center, metrics).isEmpty());

        assertEquals(7, metrics.attempts(), "toutes les tentatives configurées doivent être comptées");
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
