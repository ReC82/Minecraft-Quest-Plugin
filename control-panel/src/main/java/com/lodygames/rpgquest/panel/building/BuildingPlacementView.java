package com.lodygames.rpgquest.panel.building;

/**
 * Un bâtiment réellement posé, tel que le panel le lit (issue #213, lot « placement »).
 *
 * <p>Projection en types simples du relevé {@code building.site.list} : le panel ne partage aucune
 * classe avec le plugin, et surtout aucun type WorldEdit — l'emprise n'est que six entiers.</p>
 *
 * @param restorable      une sauvegarde du terrain existe-t-elle ? Sans elle, le bouton de
 *                        libération doit être <strong>absent</strong> plutôt que présent et voué à
 *                        échouer : remettre de l'air détruirait le terrain d'origine
 * @param buildingVersion version déclarée au moment de la pose (issue #234)
 * @param schematicSha    empreinte du fichier réellement collé, {@code ""} si inconnue
 * @param libraryVersion  version actuellement déclarée dans la bibliothèque
 * @param librarySha      empreinte actuelle du fichier de la bibliothèque
 * @param outdated        la définition a-t-elle changé depuis la pose ? Information, jamais action :
 *                        un bâtiment posé ne change pas parce que sa définition a changé
 * @param desiredRotation la rotation qu'aurait le bâtiment selon l'orientation ACTUELLE de
 *                        l'emplacement, {@code -1} si incalculable
 * @param diverges        l'intention de l'emplacement diffère-t-elle du fait posé ?
 * @param restoreSource   d'où viendrait le terrain si on libérait maintenant, en clair
 */
public record BuildingPlacementView(String siteId, String buildingId, String buildingName,
                                    String world, int anchorX, int anchorY, int anchorZ,
                                    int rotation,
                                    int minX, int minY, int minZ,
                                    int maxX, int maxY, int maxZ,
                                    String placedBy, String placedAt, boolean restorable,
                                    int buildingVersion, String schematicSha,
                                    int libraryVersion, String librarySha, boolean outdated,
                                    int desiredRotation, boolean diverges,
                                    String restoreSource) {

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

    /** Les douze premiers caractères de l'empreinte posée : assez pour comparer à l'œil. */
    public String shortSha() {
        return schematicSha == null || schematicSha.length() <= 12
                ? String.valueOf(schematicSha) : schematicSha.substring(0, 12);
    }

    /** Les douze premiers caractères de l'empreinte de la bibliothèque. */
    public String shortLibrarySha() {
        return librarySha == null || librarySha.length() <= 12
                ? String.valueOf(librarySha) : librarySha.substring(0, 12);
    }

    /** La version posée, lisible — « inconnue » plutôt que « 0 », qui ne veut rien dire. */
    public String versionLabel() {
        return buildingVersion <= 0 ? "inconnue" : "v" + buildingVersion;
    }

    public String libraryVersionLabel() {
        return libraryVersion <= 0 ? "inconnue" : "v" + libraryVersion;
    }

    /** La rotation souhaitée, ou vide si l'orientation de l'emplacement n'est pas exploitable. */
    public String desiredRotationLabel() {
        return desiredRotation < 0 ? "" : desiredRotation + "°";
    }
}
