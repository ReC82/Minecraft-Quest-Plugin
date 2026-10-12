package com.lodygames.rpgquest.building.plan;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BlueprintBlock;
import com.lodygames.rpgquest.building.model.Facing;
import java.util.HashMap;
import java.util.Map;

/**
 * La grille expansée, plus les bornes réelles de la matière (issue #94, sortie de
 * {@link ArchitecturalAudit} par #282 pour être partagée avec {@link CirculationPlanner}).
 *
 * <p>Les bornes viennent des blocs posés, pas des dimensions déclarées : un plan peut annoncer
 * 19 × 19 et ne remplir que 17 × 17, et c'est la surface réelle qui porte les façades.</p>
 *
 * <p>Les bornes sont figées à la construction. Une simulation ({@link #with}) écrit dans une
 * copie et garde ces bornes : un escalier proposé ne doit jamais redéfinir l'enveloppe qu'il
 * s'interdit de toucher.</p>
 */
final class AuditGrid {

    private final Map<Long, String> cells;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    private AuditGrid(Map<Long, String> cells, int minX, int minY, int minZ,
                      int maxX, int maxY, int maxZ) {
        this.cells = cells;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    static AuditGrid of(Blueprint blueprint) {
        Map<Long, String> cells = new HashMap<>(blueprint.blocks().size() * 2);
        int nx = Integer.MAX_VALUE;
        int ny = Integer.MAX_VALUE;
        int nz = Integer.MAX_VALUE;
        int xx = Integer.MIN_VALUE;
        int xy = Integer.MIN_VALUE;
        int xz = Integer.MIN_VALUE;
        for (BlueprintBlock b : blueprint.blocks()) {
            cells.put(key(b.x(), b.y(), b.z()), b.state());
            nx = Math.min(nx, b.x());
            ny = Math.min(ny, b.y());
            nz = Math.min(nz, b.z());
            xx = Math.max(xx, b.x());
            xy = Math.max(xy, b.y());
            xz = Math.max(xz, b.z());
        }
        return new AuditGrid(cells, nx, ny, nz, xx, xy, xz);
    }

    /** Une copie modifiée par un escalier, pour simuler une réparation sans toucher l'original. */
    AuditGrid with(Staircase stair) {
        Map<Long, String> copy = new HashMap<>(cells);
        for (Staircase.Placement p : stair.placements()) {
            if (p.clears()) {
                copy.remove(key(p.x(), p.y(), p.z()));
            } else {
                copy.put(key(p.x(), p.y(), p.z()), p.state());
            }
        }
        return new AuditGrid(copy, minX, minY, minZ, maxX, maxY, maxZ);
    }

    Map<Long, String> cells() {
        return cells;
    }

    int minX() {
        return minX;
    }

    int minY() {
        return minY;
    }

    int minZ() {
        return minZ;
    }

    int maxX() {
        return maxX;
    }

    int maxY() {
        return maxY;
    }

    int maxZ() {
        return maxZ;
    }

    String at(int x, int y, int z) {
        return cells.get(key(x, y, z));
    }

    boolean passable(int x, int y, int z) {
        return BlockSemantics.passable(at(x, y, z));
    }

    /** Un bloc qui porte et qui n'est pas un meuble : un plancher, pas le dessus d'une étagère. */
    boolean structural(int x, int y, int z) {
        String state = at(x, y, z);
        return BlockSemantics.support(state) && !BlockSemantics.furnishing(state);
    }

    boolean inside(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** Colonne strictement intérieure : ni sur l'anneau des murs extérieurs, ni dehors. */
    boolean interior(int x, int z) {
        return x > minX && x < maxX && z > minZ && z < maxZ;
    }

    /** L'emprise intérieure a-t-elle au moins une colonne ? Faux pour un mur ou un poteau. */
    boolean hasInterior() {
        return maxX - minX >= 2 && maxZ - minZ >= 2;
    }

    /** La façade à laquelle appartient cette colonne, ou {@code null} si elle est intérieure. */
    String faceOf(int x, int z) {
        if (x == minX) {
            return "OUEST";
        }
        if (x == maxX) {
            return "EST";
        }
        if (z == minZ) {
            return "NORD";
        }
        if (z == maxZ) {
            return "SUD";
        }
        return null;
    }

    String facadeDescription(Facing front) {
        return switch (front == null ? Facing.NORTH : front) {
            case NORTH -> "z=" + minZ;
            case SOUTH -> "z=" + maxZ;
            case WEST -> "x=" + minX;
            case EAST -> "x=" + maxX;
        };
    }

    static long key(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
    }

    static int keyX(long k) {
        return (int) ((k >> 40) & 0xFFFFF) - 512;
    }

    static int keyY(long k) {
        return (int) ((k >> 20) & 0xFFFFF) - 512;
    }

    static int keyZ(long k) {
        return (int) (k & 0xFFFFF) - 512;
    }
}
