package com.lodygames.rpgquest.panel.building;

/**
 * Un bâtiment réellement posé, tel que le panel le lit (issue #213, lot « placement »).
 *
 * <p>Projection en types simples du relevé {@code building.site.list} : le panel ne partage aucune
 * classe avec le plugin, et surtout aucun type WorldEdit — l'emprise n'est que six entiers.</p>
 *
 * @param restorable une sauvegarde de la zone écrasée existe-t-elle ? Sans elle, le bouton de
 *                   retour arrière doit être <strong>absent</strong> plutôt que présent et voué à
 *                   échouer : remettre de l'air dans l'emprise détruirait le terrain d'origine
 */
public record BuildingPlacementView(String siteId, String buildingId, String buildingName,
                                    String world, int anchorX, int anchorY, int anchorZ,
                                    int rotation,
                                    int minX, int minY, int minZ,
                                    int maxX, int maxY, int maxZ,
                                    String placedBy, String placedAt, boolean restorable) {

    public String anchorLabel() {
        return anchorX + " / " + anchorY + " / " + anchorZ;
    }

    public String footprintLabel() {
        return minX + ".." + maxX + " / " + minY + ".." + maxY + " / " + minZ + ".." + maxZ;
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public long blockCount() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    public String sizeLabel() {
        return sizeX() + " × " + sizeZ() + " × " + sizeY();
    }
}
