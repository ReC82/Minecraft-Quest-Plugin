package com.lodygames.rpgquest.travel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Sécurité d'une arrivée <strong>précise</strong> (issue #566, point fixe d'un portail) : mêmes règles
 * que {@link RandomSafeLocationFinder} (sol solide non dangereux, pieds et tête libres et non
 * dangereux), appliquées à une position donnée plutôt qu'à la colonne la plus haute — un point fixe
 * peut être sous un toit, dans une salle ou une grotte.
 *
 * <p>Si le point n'est pas sûr, {@link #findNear} essaie les positions voisines dans une boîte
 * <strong>bornée</strong> ({@value #HORIZONTAL_RADIUS} blocs autour, {@value #VERTICAL_RANGE} blocs
 * au-dessus et en dessous, {@link #MAX_CANDIDATES} positions au plus), la plus proche d'abord ; rien
 * de sûr dans la boîte = refus, jamais un repli lointain.</p>
 *
 * <p>Sans Bukkit actif : la lecture des blocs passe par {@link BlockView}, que l'appelant ne
 * construit que sur des chunks <em>déjà chargés</em> (aucun chargement ici).</p>
 */
public final class ArrivalSafety {

    public static final int HORIZONTAL_RADIUS = 2;
    public static final int VERTICAL_RANGE = 3;
    public static final int MAX_CANDIDATES = (2 * HORIZONTAL_RADIUS + 1) * (2 * HORIZONTAL_RADIUS + 1)
            * (2 * VERTICAL_RANGE + 1);

    /** Lecture des blocs autour de l'arrivée. */
    public interface BlockView {

        Material type(int x, int y, int z);

        /** Plus petit y constructible (inclus). */
        int minHeight();

        /** Plus grand y constructible (exclu). */
        int maxHeight();

        boolean insideBorder(int x, int z);
    }

    /** Résultat du contrôle d'une position (pieds du joueur). */
    public enum Verdict {
        SAFE("position sûre"),
        OUT_OF_HEIGHT("hors des hauteurs du monde"),
        OUTSIDE_BORDER("hors de la bordure du monde"),
        DANGEROUS("lave, feu, magma, cactus ou baies à côté des pieds"),
        NO_GROUND("aucun sol solide sous les pieds"),
        BLOCKED("pas la place pour le joueur (pieds ou tête dans un bloc)");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Une position d'arrivée : bloc des pieds. */
    public record Spot(int x, int y, int z) {
    }

    private ArrivalSafety() {
    }

    public static Verdict check(BlockView view, int x, int y, int z) {
        if (y - 1 < view.minHeight() || y + 1 >= view.maxHeight()) {
            return Verdict.OUT_OF_HEIGHT;
        }
        if (!view.insideBorder(x, z)) {
            return Verdict.OUTSIDE_BORDER;
        }
        Material ground = view.type(x, y - 1, z);
        Material feet = view.type(x, y, z);
        Material head = view.type(x, y + 1, z);
        if (RandomSafeLocationFinder.isDangerous(ground) || RandomSafeLocationFinder.isDangerous(feet)
                || RandomSafeLocationFinder.isDangerous(head)) {
            return Verdict.DANGEROUS;
        }
        if (!ground.isSolid()) {
            return Verdict.NO_GROUND;
        }
        if (feet.isSolid() || head.isSolid()) {
            return Verdict.BLOCKED;
        }
        return Verdict.SAFE;
    }

    /** Le point lui-même s'il est sûr, sinon la position sûre la plus proche dans la boîte bornée. */
    public static Optional<Spot> findNear(BlockView view, int x, int y, int z) {
        for (Spot candidate : candidates(x, y, z)) {
            if (check(view, candidate.x(), candidate.y(), candidate.z()) == Verdict.SAFE) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Positions essayées, la plus proche d'abord (le point lui-même en premier). */
    static List<Spot> candidates(int x, int y, int z) {
        List<Spot> out = new ArrayList<>(MAX_CANDIDATES);
        for (int dx = -HORIZONTAL_RADIUS; dx <= HORIZONTAL_RADIUS; dx++) {
            for (int dz = -HORIZONTAL_RADIUS; dz <= HORIZONTAL_RADIUS; dz++) {
                for (int dy = -VERTICAL_RANGE; dy <= VERTICAL_RANGE; dy++) {
                    out.add(new Spot(x + dx, y + dy, z + dz));
                }
            }
        }
        out.sort(Comparator.comparingInt((Spot s) -> distance(s, x, y, z))
                .thenComparingInt(s -> Math.abs(s.y() - y))
                .thenComparingInt(s -> -(s.y() - y))); // à distance égale : plutôt au-dessus (sortir d'un trou).
        return out;
    }

    private static int distance(Spot s, int x, int y, int z) {
        int dx = s.x() - x;
        int dy = s.y() - y;
        int dz = s.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Chunks (x, z en coordonnées de chunk) couverts par la boîte de recherche autour de (x, z). */
    public static List<long[]> chunksAround(int x, int z) {
        List<long[]> out = new ArrayList<>();
        for (int cx = (x - HORIZONTAL_RADIUS) >> 4; cx <= (x + HORIZONTAL_RADIUS) >> 4; cx++) {
            for (int cz = (z - HORIZONTAL_RADIUS) >> 4; cz <= (z + HORIZONTAL_RADIUS) >> 4; cz++) {
                out.add(new long[] {cx, cz});
            }
        }
        return out;
    }

    /**
     * Copie des blocs de la boîte de recherche autour de (x, y, z), ou vide si un de ses chunks n'est pas
     * chargé : la vérification sans appliquer ne charge jamais de chunk. À appeler sur le thread
     * principal ; la copie se lit ensuite depuis n'importe quel thread.
     */
    public static Optional<BlockView> snapshotIfLoaded(World world, int x, int y, int z) {
        for (long[] c : chunksAround(x, z)) {
            if (!world.isChunkLoaded((int) c[0], (int) c[1])) {
                return Optional.empty();
            }
        }
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        int fromY = Math.max(minY, y - VERTICAL_RANGE - 1);
        int toY = Math.min(maxY - 1, y + VERTICAL_RANGE + 1);
        int side = 2 * HORIZONTAL_RADIUS + 1;
        int height = Math.max(0, toY - fromY + 1);
        Material[] blocks = new Material[side * side * height];
        boolean[] border = new boolean[side * side];
        org.bukkit.WorldBorder wb = world.getWorldBorder();
        for (int dx = 0; dx < side; dx++) {
            for (int dz = 0; dz < side; dz++) {
                int bx = x - HORIZONTAL_RADIUS + dx;
                int bz = z - HORIZONTAL_RADIUS + dz;
                border[dx * side + dz] = wb.isInside(new org.bukkit.Location(world, bx + 0.5, 64, bz + 0.5));
                for (int dy = 0; dy < height; dy++) {
                    blocks[(dx * side + dz) * height + dy] = world.getBlockAt(bx, fromY + dy, bz).getType();
                }
            }
        }
        return Optional.of(new BlockView() {
            @Override
            public Material type(int bx, int by, int bz) {
                int dx = bx - (x - HORIZONTAL_RADIUS);
                int dz = bz - (z - HORIZONTAL_RADIUS);
                int dy = by - fromY;
                if (dx < 0 || dx >= side || dz < 0 || dz >= side || dy < 0 || dy >= height) {
                    throw new IllegalArgumentException("bloc hors de la copie : " + bx + "," + by + "," + bz);
                }
                return blocks[(dx * side + dz) * height + dy];
            }

            @Override
            public int minHeight() {
                return minY;
            }

            @Override
            public int maxHeight() {
                return maxY;
            }

            @Override
            public boolean insideBorder(int bx, int bz) {
                int dx = bx - (x - HORIZONTAL_RADIUS);
                int dz = bz - (z - HORIZONTAL_RADIUS);
                return dx >= 0 && dx < side && dz >= 0 && dz < side && border[dx * side + dz];
            }
        });
    }

    /** Vue directe d'un monde : à n'utiliser que sur des chunks déjà chargés (thread principal). */
    public static BlockView worldView(World world) {
        return new BlockView() {
            @Override
            public Material type(int x, int y, int z) {
                return world.getBlockAt(x, y, z).getType();
            }

            @Override
            public int minHeight() {
                return world.getMinHeight();
            }

            @Override
            public int maxHeight() {
                return world.getMaxHeight();
            }

            @Override
            public boolean insideBorder(int x, int z) {
                return world.getWorldBorder().isInside(new org.bukkit.Location(world, x + 0.5, 64, z + 0.5));
            }
        };
    }
}
