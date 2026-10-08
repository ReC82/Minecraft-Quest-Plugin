package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.BuildingDefinition;
import com.lodygames.rpgquest.building.model.Facing;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Lecture d'une définition de bâtiment depuis un fichier YAML (issue #213, lot « placement »).
 *
 * <h2>Pourquoi un fichier, et pas une table</h2>
 *
 * <p>Une définition de bâtiment est du <strong>contenu</strong> : elle se relit, se compare entre
 * deux versions, se corrige dans un éditeur et se versionne avec le reste du dépôt. C'est le même
 * raisonnement que pour les quêtes, les dialogues et les types de nœuds de ressource — et c'est
 * l'exact opposé d'un {@code BuildingPlacement}, qui est un fait d'exécution et vit en base.</p>
 *
 * <p>La lecture prend une {@link ConfigurationSection} plutôt qu'un {@link java.nio.file.Path} : la
 * conversion est ainsi <strong>testable sans fichier</strong>, et les cas tordus (ancre absente,
 * orientation inconnue, dimensions à zéro) se vérifient en trois lignes.</p>
 */
public final class BuildingDefinitionYaml {

    private BuildingDefinitionYaml() {
    }

    /**
     * Convertit une section YAML en définition.
     *
     * <p>La <strong>forme</strong> est validée ici (clés présentes, types lisibles) ; la
     * <strong>cohérence</strong> l'est par {@link BuildingDefinition#validate()}. Séparer les deux
     * permet de dire « il manque la section size » plutôt que « dimensions invalides : 0 × 0 × 0 »,
     * qui ne désigne pas la faute.</p>
     *
     * @return la définition, ou {@link Optional#empty()} avec le motif déposé dans {@code problems}
     */
    public static Optional<BuildingDefinition> read(ConfigurationSection section,
                                                    List<String> problems) {
        if (section == null) {
            problems.add("Fichier vide ou illisible.");
            return Optional.empty();
        }
        String id = text(section, "id");
        if (id == null) {
            problems.add("Clé « id » absente.");
            return Optional.empty();
        }
        String name = text(section, "name");
        if (name == null) {
            problems.add("Clé « name » absente pour « " + id + " ».");
            return Optional.empty();
        }
        String schematic = text(section, "schematic");
        if (schematic == null) {
            problems.add("Clé « schematic » absente pour « " + id + " ».");
            return Optional.empty();
        }
        ConfigurationSection size = section.getConfigurationSection("size");
        if (size == null) {
            problems.add("Section « size » absente pour « " + id + " ».");
            return Optional.empty();
        }
        ConfigurationSection anchor = section.getConfigurationSection("anchor");
        if (anchor == null) {
            problems.add("Section « anchor » absente pour « " + id + " ».");
            return Optional.empty();
        }
        String frontRaw = text(section, "front");
        Optional<Facing> front = Facing.of(frontRaw);
        if (front.isEmpty()) {
            problems.add("Orientation de référence inconnue pour « " + id + " » : « "
                    + frontRaw + " ». Valeurs acceptées : NORTH, EAST, SOUTH, WEST.");
            return Optional.empty();
        }

        List<String> materials = new ArrayList<>();
        for (String raw : section.getStringList("materials")) {
            if (raw != null && !raw.isBlank()) {
                materials.add(raw.trim().toLowerCase(Locale.ROOT));
            }
        }

        BuildingDefinition definition = new BuildingDefinition(
                id.trim().toLowerCase(Locale.ROOT),
                name.trim(),
                section.getString("description", ""),
                schematic.trim().toLowerCase(Locale.ROOT),
                size.getInt("x"), size.getInt("y"), size.getInt("z"),
                anchor.getInt("x"), anchor.getInt("y"), anchor.getInt("z"),
                front.get(), materials,
                Math.max(1, section.getInt("version", 1)));

        Optional<String> invalid = definition.validate();
        if (invalid.isPresent()) {
            problems.add(invalid.get());
            return Optional.empty();
        }
        return Optional.of(definition);
    }

    private static String text(ConfigurationSection section, String key) {
        String value = section.getString(key);
        return value == null || value.isBlank() ? null : value;
    }
}
