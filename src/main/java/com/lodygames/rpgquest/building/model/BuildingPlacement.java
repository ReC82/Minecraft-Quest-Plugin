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
 * @param buildingVersion version <em>déclarée</em> de la définition au moment de la pose (issue
 *                        #234), {@code 0} pour un placement antérieur à cette mémorisation
 * @param schematicSha256 empreinte du fichier <strong>réellement collé</strong> (issue #234),
 *                        {@code ""} si inconnue. C'est elle qui est fiable : une version déclarée
 *                        peut être oubliée par qui édite le YAML, un contenu non. Un bâtiment posé
 *                        ne change JAMAIS parce que sa définition a changé — ces deux champs sont
 *                        ce qui permet de le dire à l'écran au lieu de le deviner
 * @param rotationDegrees la rotation réellement appliquée, dans {@code {0, 90, 180, 270}}
 * @param backupSchematic le fichier de sauvegarde de la zone écrasée, ou {@code null} si aucune
 *                        sauvegarde n'a pu être prise — et dans ce cas le retour arrière est refusé
 *                        plutôt que tenté à l'aveugle
 */
public record BuildingPlacement(String siteId,
                                String buildingId,
                                int buildingVersion,
                                String schematicSha256,
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
        return of(siteId, definition, "", footprint, anchorX, anchorY, anchorZ, rotationDegrees,
                backupSchematic, placedBy, placedAt);
    }

    /**
     * Variante qui mémorise l'empreinte du fichier réellement collé (issue #234).
     *
     * <p>La surcharge sans empreinte reste pour les appels où elle n'est pas connue — elle
     * enregistre alors {@code ""}, qui se lit « inconnue », et non une valeur inventée.</p>
     */
    public static BuildingPlacement of(String siteId, BuildingDefinition definition,
                                       String schematicSha256,
                                       BuildingFootprint footprint,
                                       int anchorX, int anchorY, int anchorZ,
                                       int rotationDegrees, String backupSchematic,
                                       String placedBy, Instant placedAt) {
        return new BuildingPlacement(siteId, definition.id(), definition.version(),
                schematicSha256 == null ? "" : schematicSha256, footprint.world(),
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

    /** Les douze premiers caractères de l'empreinte : assez pour comparer deux versions à l'œil. */
    public String shortSha() {
        return schematicSha256 == null || schematicSha256.length() <= 12
                ? String.valueOf(schematicSha256) : schematicSha256.substring(0, 12);
    }

    /**
     * Vrai si la définition de la bibliothèque a changé depuis la pose.
     *
     * <p>L'empreinte du fichier décide quand elle est connue des deux côtés : c'est le seul critère
     * qui ne dépend pas de la rigueur de celui qui édite le YAML. Sans empreinte, on retombe sur la
     * version déclarée. Si ni l'une ni l'autre n'est connue, on <strong>ne conclut pas</strong> —
     * annoncer « à jour » sans le savoir serait exactement le genre d'affirmation que ce projet
     * refuse.</p>
     *
     * @param libraryVersion version actuellement déclarée dans la bibliothèque
     * @param librarySha256  empreinte actuelle du fichier de la bibliothèque, {@code ""} si inconnue
     */
    public boolean outdatedAgainst(int libraryVersion, String librarySha256) {
        boolean shaKnown = schematicSha256 != null && !schematicSha256.isBlank()
                && librarySha256 != null && !librarySha256.isBlank();
        if (shaKnown) {
            return !schematicSha256.equals(librarySha256);
        }
        return buildingVersion > 0 && libraryVersion > 0 && libraryVersion != buildingVersion;
    }
}
