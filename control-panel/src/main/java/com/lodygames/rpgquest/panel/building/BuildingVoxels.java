package com.lodygames.rpgquest.panel.building;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Le bâtiment bloc par bloc, tel que le plugin l'a calculé pour l'aperçu (issue #273).
 *
 * <p>Le panel ne relit jamais le plan pour dessiner : un aperçu tiré du texte montrerait ce que le
 * panel <em>croit</em> que le plan décrit. Cette grille vient de la même expansion que l'audit, dans
 * le résultat de {@code building.plan.preview}. Format : une couche par niveau (du sol vers le haut),
 * une chaîne par rangée {@code z}, un caractère par cellule {@code x} — {@code .} pour l'air, sinon le
 * symbole d'une entrée de la palette.</p>
 *
 * @param omittedReason pourquoi le plugin n'a pas transmis la grille (trop grande…), vide sinon
 */
public record BuildingVoxels(int sizeX, int sizeY, int sizeZ, Map<Character, Entry> palette,
                             List<List<String>> layers, String omittedReason) {

    /** Le caractère de l'air, comme dans les coupes. */
    public static final char EMPTY = '.';

    /**
     * Une entrée de palette.
     *
     * @param role  le rôle, dans le vocabulaire des coupes ({@code #}, {@code o}, {@code +}…)
     * @param shape {@code full}, {@code bottom} ou {@code top} (dalles)
     */
    public record Entry(char symbol, String block, char role, String shape) {
    }

    public BuildingVoxels {
        palette = Map.copyOf(palette == null ? Map.of() : palette);
        layers = layers == null ? List.of() : layers.stream().map(List::copyOf).toList();
        omittedReason = omittedReason == null ? "" : omittedReason;
    }

    public static BuildingVoxels none(String reason) {
        return new BuildingVoxels(0, 0, 0, Map.of(), List.of(), reason);
    }

    /** Une grille exploitable : au moins une couche, et toutes de la même forme. */
    public boolean present() {
        return sizeX > 0 && sizeY > 0 && sizeZ > 0;
    }

    /** L'entrée de la cellule, ou {@code null} pour l'air et hors de la grille. */
    public Entry at(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return null;
        }
        char symbol = layers.get(y).get(z).charAt(x);
        return symbol == EMPTY ? null : palette.get(symbol);
    }

    /**
     * Lit le champ {@code voxels} du résultat d'un aperçu. Une grille mal formée (couches de tailles
     * différentes, symbole inconnu) n'est pas dessinée à moitié : elle est refusée avec son motif.
     */
    public static BuildingVoxels from(Map<String, Object> details) {
        Object raw = details == null ? null : details.get("voxels");
        if (!(raw instanceof Map<?, ?> map)) {
            return none("Le serveur n'a pas transmis de vue en 3D : il faut une version plus récente du "
                    + "plugin.");
        }
        String reason = map.get("omittedReason") == null ? "" : String.valueOf(map.get("omittedReason"));
        Map<Character, Entry> palette = new HashMap<>();
        if (map.get("palette") instanceof List<?> entries) {
            for (Object item : entries) {
                if (item instanceof Map<?, ?> e) {
                    String symbol = text(e.get("symbol"));
                    String role = text(e.get("role"));
                    if (symbol.length() == 1 && role.length() == 1) {
                        palette.put(symbol.charAt(0), new Entry(symbol.charAt(0), text(e.get("block")),
                                role.charAt(0), text(e.get("shape"))));
                    }
                }
            }
        }
        List<List<String>> layers = new ArrayList<>();
        if (map.get("layers") instanceof List<?> rawLayers) {
            for (Object layer : rawLayers) {
                List<String> rows = new ArrayList<>();
                if (layer instanceof List<?> rawRows) {
                    rawRows.forEach(r -> rows.add(text(r)));
                }
                layers.add(rows);
            }
        }
        if (layers.isEmpty()) {
            return none(reason.isEmpty() ? "Aucune grille transmise." : reason);
        }
        int sizeZ = layers.get(0).size();
        int sizeX = sizeZ == 0 ? 0 : layers.get(0).get(0).length();
        for (List<String> rows : layers) {
            if (rows.size() != sizeZ) {
                return none("Grille reçue mal formée : couches de tailles différentes.");
            }
            for (String row : rows) {
                if (row.length() != sizeX) {
                    return none("Grille reçue mal formée : rangées de longueurs différentes.");
                }
                for (int i = 0; i < row.length(); i++) {
                    char c = row.charAt(i);
                    if (c != EMPTY && !palette.containsKey(c)) {
                        return none("Grille reçue mal formée : symbole « " + c + " » absent de la palette.");
                    }
                }
            }
        }
        if (sizeX == 0) {
            return none("Grille reçue vide.");
        }
        return new BuildingVoxels(sizeX, layers.size(), sizeZ, palette, layers, "");
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
