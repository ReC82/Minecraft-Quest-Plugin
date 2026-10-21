package com.lodygames.rpgquest.building.plan;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BlueprintBlock;
import com.lodygames.rpgquest.building.model.Facing;
import com.lodygames.rpgquest.building.model.PortalPassage;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Déduit la zone de passage d'une arche générée (issue #568, #228-P5), sur la <strong>grille</strong>
 * — jamais sur les instructions du plan, comme l'audit architectural.
 *
 * <p>Règle, énoncée une fois : l'ancre est le centre du passage au niveau du sol (convention de
 * {@link com.lodygames.rpgquest.building.model.BuildingDefinition}) ; si l'ancre est dans la dalle du
 * sol (ancre par défaut d'un plan, {@code y = 0}), le passage commence juste au-dessus. Une colonne est
 * <em>traversante</em> si toutes ses cellules sont franchissables d'une face à l'autre de l'arche,
 * dans l'axe de la façade. Le passage part de la colonne de l'ancre (deux blocs de haut au moins),
 * s'élargit tant que les colonnes voisines restent traversantes sur ces deux hauteurs, puis monte tant
 * que toute la largeur reste traversante. Il couvre toute la profondeur de l'arche.</p>
 */
public final class PortalPassageFinder {

    /** Hauteur minimale d'un passage : un joueur fait deux blocs. */
    public static final int MIN_HEIGHT = 2;

    private PortalPassageFinder() {
    }

    /**
     * @return le passage, ou vide si la colonne de l'ancre n'est pas traversante sur deux blocs
     */
    public static Optional<PortalPassage> find(Blueprint blueprint, int anchorX, int anchorY, int anchorZ,
                                               Facing front) {
        Map<Long, String> cells = new HashMap<>(blueprint.blocks().size() * 2);
        for (BlueprintBlock b : blueprint.blocks()) {
            cells.put(key(b.x(), b.y(), b.z()), b.state());
        }
        boolean crossAlongZ = front == null || front == Facing.NORTH || front == Facing.SOUTH;
        int widthSize = crossAlongZ ? blueprint.sizeX() : blueprint.sizeZ();
        int crossSize = crossAlongZ ? blueprint.sizeZ() : blueprint.sizeX();
        int anchorW = crossAlongZ ? anchorX : anchorZ;
        Column column = (w, y) -> {
            if (w < 0 || w >= widthSize || y < 0 || y >= blueprint.sizeY()) {
                return false;
            }
            for (int c = 0; c < crossSize; c++) {
                int x = crossAlongZ ? w : c;
                int z = crossAlongZ ? c : w;
                if (!BlockSemantics.passable(cells.get(key(x, y, z)))) {
                    return false;
                }
            }
            return true;
        };
        // Ancre posée sur la dalle du sol (ancre par défaut d'un plan : y = 0) : on marche juste au-dessus.
        int ground = !column.through(anchorW, anchorY) && column.through(anchorW, anchorY + 1)
                ? anchorY + 1 : anchorY;
        if (ground + MIN_HEIGHT > blueprint.sizeY()
                || !column.through(anchorW, ground) || !column.through(anchorW, ground + 1)) {
            return Optional.empty();
        }
        int low = anchorW;
        while (column.through(low - 1, ground) && column.through(low - 1, ground + 1)) {
            low--;
        }
        int high = anchorW;
        while (column.through(high + 1, ground) && column.through(high + 1, ground + 1)) {
            high++;
        }
        int top = ground + 1;
        while (rowThrough(column, low, high, top + 1)) {
            top++;
        }
        return Optional.of(crossAlongZ
                ? new PortalPassage(low, ground, 0, high, top, crossSize - 1)
                : new PortalPassage(0, ground, low, crossSize - 1, top, high));
    }

    private static boolean rowThrough(Column column, int low, int high, int y) {
        for (int w = low; w <= high; w++) {
            if (!column.through(w, y)) {
                return false;
            }
        }
        return true;
    }

    @FunctionalInterface
    private interface Column {
        boolean through(int w, int y);
    }

    private static long key(int x, int y, int z) {
        return ((long) x << 40) | ((long) y << 20) | z;
    }
}
