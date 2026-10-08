package com.lodygames.rpgquest.building.model;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Un bâtiment de la bibliothèque : ce qu'on peut poser, indépendamment de l'endroit où on le pose
 * (issue #213, lot « placement »).
 *
 * <h2>Déclaratif, versionnable, et sans aucun type WorldEdit</h2>
 *
 * <p>Une définition est du <strong>contenu</strong>, comme une quête ou un dialogue : elle vit dans
 * un fichier YAML relu au démarrage, pas dans une table. Elle ne change pas parce qu'un joueur
 * joue ; elle change parce qu'on la modifie. C'est l'inverse exact de
 * {@link BuildingPlacement}, qui est de l'état d'exécution et vit en base.</p>
 *
 * <p>Ce record n'expose <strong>aucun type WorldEdit</strong> : {@code schematic} est un simple nom
 * de fichier, les dimensions et l'ancre sont des entiers. WorldEdit est un moteur de collage
 * remplaçable, pas un vocabulaire du domaine — rien ici ne doit changer si on le remplace.</p>
 *
 * <h2>La convention d'ancre, énoncée explicitement</h2>
 *
 * <p>L'ancre est le <strong>centre de la porte, au niveau du sol</strong>, exprimée en coordonnées
 * locales du schematic. C'est le point qui coïncidera avec l'ancre de l'emplacement, et le point
 * fixe de la rotation.</p>
 *
 * <p>{@link #front()} déclare la direction que regarde la façade <em>quand la rotation est nulle</em>,
 * et elle doit être <strong>géométriquement vraie</strong> : la paroi qui porte la porte est celle
 * qui regarde réellement cette direction. Pour notre hutte, la porte est sur la paroi {@code z = 0},
 * qui regarde les {@code -Z}, donc le nord — d'où {@code front: NORTH} et une ancre en
 * {@code z = 0}. Déclarer {@code SOUTH} avec la même géométrie aurait introduit un demi-tour
 * implicite, c'est-à-dire exactement le genre d'offset caché que ce lot doit éviter.</p>
 */
public record BuildingDefinition(String id,
                                 String name,
                                 String description,
                                 String schematic,
                                 int sizeX,
                                 int sizeY,
                                 int sizeZ,
                                 int anchorX,
                                 int anchorY,
                                 int anchorZ,
                                 Facing front,
                                 List<String> materials,
                                 int version) {

    /** Même forme d'identifiant que le reste du contenu du projet. */
    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_]{0,63}");

    /** Un nom de fichier simple : aucun séparateur, donc aucune traversée de chemin possible. */
    public static final Pattern SCHEMATIC_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_]{0,63}\\.schem");

    public static final int MAX_SIZE = 256;

    public BuildingDefinition {
        description = description == null ? "" : description;
        materials = materials == null ? List.of() : List.copyOf(materials);
    }

    /**
     * Valide une définition relue d'un fichier.
     *
     * <p>La validation est <strong>séparée</strong> du constructeur et renvoie un motif en français
     * plutôt que de lever : un fichier de contenu invalide doit produire un message lisible dans la
     * bibliothèque du panel, pas une exception au démarrage qui empêcherait le plugin de charger le
     * reste.</p>
     *
     * @return le motif du refus, ou {@link Optional#empty()} si la définition est exploitable
     */
    public Optional<String> validate() {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            return Optional.of("Identifiant de bâtiment invalide : « " + id + " ».");
        }
        if (name == null || name.isBlank()) {
            return Optional.of("Le bâtiment « " + id + " » n'a pas de nom.");
        }
        if (schematic == null || !SCHEMATIC_PATTERN.matcher(schematic).matches()) {
            return Optional.of("Nom de fichier schematic invalide : « " + schematic + " ».");
        }
        if (front == null) {
            return Optional.of("Le bâtiment « " + id + " » n'a pas d'orientation de référence.");
        }
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            return Optional.of("Dimensions invalides pour « " + id + " » : " + sizeLabel() + ".");
        }
        if (sizeX > MAX_SIZE || sizeY > MAX_SIZE || sizeZ > MAX_SIZE) {
            return Optional.of("Dimensions trop grandes pour « " + id + " » : " + sizeLabel()
                    + " (maximum " + MAX_SIZE + " par axe).");
        }
        // Une ancre hors du schematic donnerait une emprise décalée du bâtiment réel — et le
        // décalage ne se verrait qu'en jeu, une fois la hutte posée à côté de son emplacement.
        if (anchorX < 0 || anchorX >= sizeX
                || anchorY < 0 || anchorY >= sizeY
                || anchorZ < 0 || anchorZ >= sizeZ) {
            return Optional.of("Ancre hors du bâtiment pour « " + id + " » : "
                    + anchorLabel() + " dans " + sizeLabel() + ".");
        }
        return Optional.empty();
    }

    public boolean valid() {
        return validate().isEmpty();
    }

    /** L'emprise qu'aurait ce bâtiment, posé à cette ancre, tourné de cet angle. */
    public BuildingFootprint footprintAt(String world, int anchorWorldX, int anchorWorldY,
                                         int anchorWorldZ, int degrees) {
        return BuildingFootprint.of(this, world, anchorWorldX, anchorWorldY, anchorWorldZ, degrees);
    }

    /** La rotation à appliquer pour que la façade regarde {@code target}. */
    public int rotationFor(Facing target) {
        return BuildingRotation.degreesBetween(front, target);
    }

    public String sizeLabel() {
        return sizeX + " × " + sizeZ + " × " + sizeY;
    }

    public String anchorLabel() {
        return anchorX + " / " + anchorY + " / " + anchorZ;
    }

    public long blockCount() {
        return (long) sizeX * sizeY * sizeZ;
    }

    /** Les matériaux, en minuscules et sans doublon, pour l'affichage de la bibliothèque. */
    public String materialsLabel() {
        return materials.stream()
                .map(m -> m.toLowerCase(Locale.ROOT).replace('_', ' '))
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse("—");
    }
}
