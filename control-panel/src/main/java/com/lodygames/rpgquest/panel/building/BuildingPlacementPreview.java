package com.lodygames.rpgquest.panel.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * L'aperçu d'une pose, tel que le panel le lit (issue #213, lot « placement »).
 *
 * <p>Résultat d'un {@code building.placement.preview}, qui <strong>n'écrit rien</strong>. C'est ce
 * que l'administrateur lit avant de confirmer : l'emprise exacte, la rotation calculée, le nombre de
 * blocs, et la liste complète de ce qui empêche ou mérite attention.</p>
 *
 * @param nonAirBlocks nombre de blocs non-air déjà sur place, ou {@code -1} si le comptage n'a pas
 *                     pu être fait (monde ou chunk déchargé). {@code -1} n'est pas {@code 0} : ne
 *                     pas savoir et savoir que c'est vide sont deux choses différentes, et les
 *                     confondre ferait annoncer « zone libre » sans l'avoir vérifié
 */
public record BuildingPlacementPreview(boolean placeable,
                                       String siteId, String siteName, String siteFacing,
                                       String world, int anchorX, int anchorY, int anchorZ,
                                       String buildingId, String buildingName,
                                       int sizeX, int sizeY, int sizeZ, String front,
                                       int rotation,
                                       int minX, int minY, int minZ,
                                       int maxX, int maxY, int maxZ,
                                       long blockCount, long nonAirBlocks,
                                       List<String> refusals, List<String> warnings,
                                       boolean available) {

    public BuildingPlacementPreview {
        refusals = List.copyOf(refusals == null ? List.of() : refusals);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    public static BuildingPlacementPreview unavailable() {
        return new BuildingPlacementPreview(false, "", "", "", "", 0, 0, 0, "", "",
                0, 0, 0, "", 0, 0, 0, 0, 0, 0, 0, 0L, -1L, List.of(), List.of(), false);
    }

    /** Projette les détails d'un {@code building.placement.preview}. {@code null} ⇒ indisponible. */
    public static BuildingPlacementPreview from(Map<String, Object> details) {
        if (details == null) {
            return unavailable();
        }
        return new BuildingPlacementPreview(bool(details.get("placeable")),
                str(details.get("siteId")), str(details.get("siteName")),
                str(details.get("siteFacing")),
                str(details.get("world")),
                intOr(details.get("anchorX")), intOr(details.get("anchorY")),
                intOr(details.get("anchorZ")),
                str(details.get("buildingId")), str(details.get("buildingName")),
                intOr(details.get("sizeX")), intOr(details.get("sizeY")),
                intOr(details.get("sizeZ")), str(details.get("front")),
                intOr(details.get("rotation")),
                intOr(details.get("minX")), intOr(details.get("minY")), intOr(details.get("minZ")),
                intOr(details.get("maxX")), intOr(details.get("maxY")), intOr(details.get("maxZ")),
                longOr(details.get("blockCount")), longOr(details.get("nonAirBlocks")),
                strings(details.get("refusals")), strings(details.get("warnings")), true);
    }

    public String footprintLabel() {
        return minX + ".." + maxX + " / " + minY + ".." + maxY + " / " + minZ + ".." + maxZ;
    }

    public String anchorLabel() {
        return anchorX + " / " + anchorY + " / " + anchorZ;
    }

    public String sizeLabel() {
        return sizeX + " × " + sizeZ + " × " + sizeY;
    }

    /** L'emprise réelle : à 90° et 270°, la largeur et la profondeur s'échangent. */
    public String footprintSizeLabel() {
        return (maxX - minX + 1) + " × " + (maxZ - minZ + 1) + " × " + (maxY - minY + 1);
    }

    /** {@code true} quand le comptage des blocs non-air a réellement pu être fait. */
    public boolean counted() {
        return nonAirBlocks >= 0;
    }

    private static List<String> strings(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object value : list) {
                String text = str(value);
                if (!text.isEmpty()) {
                    out.add(text);
                }
            }
        }
        return out;
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private static boolean bool(Object raw) {
        return raw instanceof Boolean value ? value : Boolean.parseBoolean(str(raw));
    }

    private static int intOr(Object raw) {
        return (int) longOr(raw);
    }

    private static long longOr(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(str(raw));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
