package com.lodygames.rpgquest.building.model;

import java.time.Instant;

/**
 * Un bâtiment réellement posé dans le monde (issue #213, lot « placement »).
 *
 * <h2>État d'exécution, donc en base</h2>
 *
 * <p>À l'inverse de {@link BuildingDefinition}, qui est du contenu déclaratif versionné, un
 * placement est un <strong>fait</strong> : « le 8 octobre à 22h, la hutte de test a été posée ici,
 * tournée de 90°, et voici la sauvegarde de ce qui s'y trouvait avant ». Cela vit en base, et rien
 * d'autre ne peut le reconstituer.</p>
 *
 * <p>Record <strong>pur</strong> : aucun type WorldEdit. {@code backupSchematic} est un simple nom
 * de fichier — l'adaptateur sait quoi en faire, le domaine n'a pas à le savoir.</p>
 *
 * @param siteId          l'emplacement occupé — un emplacement porte au plus un placement
 * @param buildingId      la définition posée
 * @param rotationDegrees la rotation réellement appliquée, dans {@code {0, 90, 180, 270}}
 * @param backupSchematic le fichier de sauvegarde de la zone écrasée, ou {@code null} si aucune
 *                        sauvegarde n'a pu être prise — et dans ce cas le retour arrière est refusé
 *                        plutôt que tenté à l'aveugle
 */
public record BuildingPlacement(String siteId,
                                String buildingId,
                                String world,
                                int anchorX,
                                int anchorY,
                                int anchorZ,
                                int rotationDegrees,
                                int minX, int minY, int minZ,
                                int maxX, int maxY, int maxZ,
                                String backupSchematic,
                                String placedBy,
                                Instant placedAt) {

    public static BuildingPlacement of(String siteId, BuildingDefinition definition,
                                       BuildingFootprint footprint,
                                       int anchorX, int anchorY, int anchorZ,
                                       int rotationDegrees, String backupSchematic,
                                       String placedBy, Instant placedAt) {
        return new BuildingPlacement(siteId, definition.id(), footprint.world(),
                anchorX, anchorY, anchorZ, BuildingRotation.normalize(rotationDegrees),
                footprint.minX(), footprint.minY(), footprint.minZ(),
                footprint.maxX(), footprint.maxY(), footprint.maxZ(),
                backupSchematic, placedBy == null ? "" : placedBy, placedAt);
    }

    public BuildingFootprint footprint() {
        return new BuildingFootprint(world, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Vrai si un retour arrière est possible.
     *
     * <p>Sans sauvegarde, il n'y a <strong>rien</strong> à restaurer : remettre de l'air dans
     * l'emprise détruirait le terrain qui s'y trouvait. Mieux vaut refuser et le dire.</p>
     */
    public boolean restorable() {
        return backupSchematic != null && !backupSchematic.isBlank();
    }

    public String anchorLabel() {
        return anchorX + " / " + anchorY + " / " + anchorZ;
    }

    public String rotationLabel() {
        return rotationDegrees + "°";
    }
}
