package com.lodygames.rpgquest.building.plan;

import com.lodygames.rpgquest.building.model.Facing;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Lecture d'un plan de bâtiment écrit en YAML (issue #94).
 *
 * <h2>Pourquoi du YAML, et pas du JSON</h2>
 *
 * <p>Parce que c'est déjà la langue du projet. Les quêtes, les dialogues, les stories, les
 * définitions de bâtiment et les types de nœuds de ressource sont en YAML ; l'atelier IA #146
 * demande déjà du YAML à ses modèles et dispose d'un extracteur éprouvé
 * ({@code AiYamlExtractor}). Choisir JSON ici imposerait un analyseur de plus au plugin, pour
 * décrire exactement la même chose.</p>
 *
 * <p>Comme {@code BuildingDefinitionYaml}, la lecture prend une {@link ConfigurationSection} :
 * elle se teste donc sans fichier, et les cas tordus — zone absente, instruction inconnue,
 * coordonnée manquante — se vérifient en trois lignes.</p>
 *
 * <h2>Ce que la lecture refuse</h2>
 *
 * <p>Tout ce qu'elle ne comprend pas. Une instruction hors vocabulaire, une zone incomplète, un
 * état de bloc mal formé : le plan est <strong>rejeté</strong>, avec le rang de l'instruction
 * fautive. Jamais d'instruction ignorée en silence — un mur manquant dans un bâtiment généré
 * serait découvert en jeu, bien trop tard.</p>
 */
public final class BuildingPlanYaml {

    private BuildingPlanYaml() {
    }

    /** Lit un plan depuis du YAML brut. Pratique pour un plan qui arrive en paramètre d'action. */
    public static Optional<BuildingPlan> read(String yaml, List<String> problems) {
        if (yaml == null || yaml.isBlank()) {
            problems.add("Plan vide.");
            return Optional.empty();
        }
        YamlConfiguration config;
        try {
            config = new YamlConfiguration();
            config.loadFromString(yaml);
        } catch (org.bukkit.configuration.InvalidConfigurationException | RuntimeException broken) {
            problems.add("YAML illisible : " + broken.getMessage());
            return Optional.empty();
        }
        return read(config, problems);
    }

    /** Lit un plan depuis une section déjà analysée. */
    public static Optional<BuildingPlan> read(ConfigurationSection section, List<String> problems) {
        if (section == null) {
            problems.add("Plan vide ou illisible.");
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
        ConfigurationSection size = section.getConfigurationSection("size");
        if (size == null) {
            problems.add("Section « size » absente pour « " + id + " ».");
            return Optional.empty();
        }
        String frontRaw = text(section, "front");
        Optional<Facing> front = Facing.of(frontRaw);
        if (front.isEmpty()) {
            problems.add("Orientation de référence inconnue pour « " + id + " » : « " + frontRaw
                    + " ». Valeurs acceptées : NORTH, EAST, SOUTH, WEST.");
            return Optional.empty();
        }

        // L'ancre est OPTIONNELLE, et son défaut est documenté dans le prompt : le centre de la
        // base. C'est la convention la plus naturelle pour poser un bâtiment sur un point marqué
        // au sol, et l'exiger de l'IA n'ajouterait qu'une occasion de se tromper.
        int sizeX = size.getInt("x");
        int sizeY = size.getInt("y");
        int sizeZ = size.getInt("z");
        ConfigurationSection anchor = section.getConfigurationSection("anchor");
        int anchorX = anchor == null ? Math.max(0, sizeX / 2) : anchor.getInt("x");
        int anchorY = anchor == null ? 0 : anchor.getInt("y");
        int anchorZ = anchor == null ? Math.max(0, sizeZ / 2) : anchor.getInt("z");

        List<?> rawSteps = section.getList("steps");
        if (rawSteps == null || rawSteps.isEmpty()) {
            problems.add("Section « steps » absente ou vide pour « " + id + " » : le plan ne "
                    + "décrirait aucun bloc.");
            return Optional.empty();
        }
        List<PlanStep> steps = new ArrayList<>();
        for (int i = 0; i < rawSteps.size(); i++) {
            String where = "Instruction " + (i + 1) + " : ";
            if (!(rawSteps.get(i) instanceof java.util.Map<?, ?> map)) {
                problems.add(where + "forme inattendue (une instruction est une liste de clés).");
                return Optional.empty();
            }
            Optional<PlanStep> step = step(map, where, problems);
            if (step.isEmpty()) {
                return Optional.empty();
            }
            steps.add(step.get());
        }

        // Type de structure (issue #94, lot A). Optionnel, et HABITABLE par défaut : un plan qui
        // ne dit rien est jugé comme un bâtiment, donc soumis aux règles les plus exigeantes. Un
        // mot inconnu est REFUSÉ plutôt qu'interprété — « decor » n'est pas « decoration », et
        // retomber silencieusement sur le défaut cacherait une faute de frappe.
        String kindRaw = text(section, "kind");
        StructureKind kind = StructureKind.HABITABLE;
        if (kindRaw != null) {
            Optional<StructureKind> parsed = StructureKind.of(kindRaw);
            if (parsed.isEmpty()) {
                problems.add("Type de structure inconnu pour « " + id + " » : « " + kindRaw
                        + " ». Valeurs acceptées : habitable, decoration, portal.");
                return Optional.empty();
            }
            kind = parsed.get();
        }

        BuildingPlan plan = new BuildingPlan(id, name, sizeX, sizeY, sizeZ, front.get(),
                anchorX, anchorY, anchorZ, steps, kind);
        Optional<String> invalid = plan.validate();
        if (invalid.isPresent()) {
            problems.add(invalid.get());
            return Optional.empty();
        }
        return Optional.of(plan);
    }

    private static Optional<PlanStep> step(java.util.Map<?, ?> map, String where,
                                           List<String> problems) {
        Optional<PlanStep.Kind> kind = PlanStep.Kind.of(str(map.get("op")));
        if (kind.isEmpty()) {
            problems.add(where + "instruction inconnue : « " + str(map.get("op"))
                    + " ». Valeurs acceptées : box, shell, walls, clear, gable.");
            return Optional.empty();
        }
        Optional<PlanBox> box = box(map, where, problems);
        if (box.isEmpty()) {
            return Optional.empty();
        }
        String state = str(map.get("block"));
        if (kind.get() != PlanStep.Kind.CLEAR && (state == null || state.isBlank())) {
            problems.add(where + "clé « block » absente — seule l'instruction « clear » s'en passe.");
            return Optional.empty();
        }
        return Optional.of(new PlanStep(kind.get(), box.get(),
                state == null ? "" : state, str(map.get("axis"))));
    }

    /**
     * La zone, en deux coins {@code from} / {@code to}.
     *
     * <p>{@code to} est optionnel : une instruction qui l'omet vise <strong>un seul bloc</strong>.
     * C'est ce qui rend les détails (une lanterne, un pupitre) écrivables en une ligne.</p>
     */
    private static Optional<PlanBox> box(java.util.Map<?, ?> map, String where,
                                         List<String> problems) {
        int[] from = triplet(map.get("from"));
        if (from == null) {
            problems.add(where + "clé « from » absente ou mal formée (attendu : [x, y, z]).");
            return Optional.empty();
        }
        int[] to = map.get("to") == null ? from : triplet(map.get("to"));
        if (to == null) {
            problems.add(where + "clé « to » mal formée (attendu : [x, y, z]).");
            return Optional.empty();
        }
        return Optional.of(PlanBox.of(from[0], from[1], from[2], to[0], to[1], to[2]));
    }

    /** Un triplet de coordonnées, tolérant sur le type numérique écrit par le modèle. */
    private static int[] triplet(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() != 3) {
            return null;
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!(list.get(i) instanceof Number number)) {
                return null;
            }
            out[i] = number.intValue();
        }
        return out;
    }

    private static String text(ConfigurationSection section, String key) {
        String value = section.getString(key);
        return value == null || value.isBlank() ? null : value;
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}
