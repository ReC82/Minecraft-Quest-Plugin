package com.lodygames.rpgquest.panel.building;

import java.util.List;
import java.util.Locale;

/**
 * Un bâtiment de la bibliothèque, tel que le panel l'affiche (issue #213, lot « placement »).
 *
 * <p>Aucun type WorldEdit : {@code schematic} est un nom de fichier, les dimensions et l'ancre sont
 * des entiers. Le panel ne sait même pas qu'un moteur de schematics existe — il sait seulement si
 * le fichier est là.</p>
 *
 * @param schematicPresent le fichier existe-t-il sur le serveur ? Une définition sans fichier reste
 *                         du contenu <strong>correct</strong> : c'est l'artefact qui manque. Le dire
 *                         explique pourquoi la pose est refusée ; le cacher laisserait l'écran
 *                         muet.
 */
public record BuildingDefinitionView(String id, String name, String description,
                                     int sizeX, int sizeY, int sizeZ,
                                     int anchorX, int anchorY, int anchorZ,
                                     String front, List<String> materials,
                                     String schematic, boolean schematicPresent, int version) {

    public BuildingDefinitionView {
        materials = List.copyOf(materials == null ? List.of() : materials);
    }

    /** Dimensions dans l'ordre où on les lit : largeur × profondeur × hauteur. */
    public String sizeLabel() {
        return sizeX + " × " + sizeZ + " × " + sizeY;
    }

    public String anchorLabel() {
        return anchorX + " / " + anchorY + " / " + anchorZ;
    }

    public long blockCount() {
        return (long) sizeX * sizeY * sizeZ;
    }

    public String frontLabel() {
        return switch (front == null ? "" : front.toUpperCase(Locale.ROOT)) {
            case "NORTH" -> "nord";
            case "EAST" -> "est";
            case "SOUTH" -> "sud";
            case "WEST" -> "ouest";
            default -> front == null ? "—" : front;
        };
    }

    public String materialsLabel() {
        return materials.isEmpty() ? "—"
                : String.join(", ", materials.stream().map(m -> m.replace('_', ' ')).toList());
    }
}
