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
        return read(yaml, problems, true);
    }

    /**
     * Lit un plan <strong>sans</strong> le valider (issue #282) : la forme est vérifiée — clés,
     * instructions connues, coordonnées entières — mais pas les bornes ni les règles. C'est ce
     * qui permet à {@link PlanRepair} de corriger un toit qui dépasse d'un bloc au lieu de
     * renvoyer le plan entier à l'IA. Ne jamais expanser un plan lu ainsi sans le valider.
     */
    public static Optional<BuildingPlan> readUnvalidated(String yaml, List<String> problems) {
        return read(yaml, problems, false, null);
    }

    /**
     * Lecture <strong>pour la réparation</strong> (issue #655) : comme {@link #readUnvalidated}, mais
     * une façade d'instruction écrite en point cardinal ({@code facade: EAST}) est traduite dans
     * le repère du bâtiment au lieu de faire refuser le plan.
     *
     * <p>La traduction est exacte, pas devinée : {@code front} fixe la façade avant, et
     * {@link Facade#direction} donne la direction réelle de chacune des quatre façades. Pour un
     * bâtiment {@code front: EAST}, « EAST » est FRONT, « SOUTH » est LEFT, « NORTH » est RIGHT.
     * Le 20/10/2026, la correction payante de l'IA avait traduit SOUTH en RIGHT : la fenêtre
     * changeait de mur. Le {@code front} du bâtiment, lui, n'est jamais touché.</p>
     *
     * @param notes reçoit une phrase par façade traduite — elle devient une correction annoncée
     */
    public static Optional<BuildingPlan> readForRepair(String yaml, List<String> problems,
                                                       List<String> notes) {
        return read(yaml, problems, false, notes);
    }

    private static Optional<BuildingPlan> read(String yaml, List<String> problems,
                                               boolean validate) {
        return read(yaml, problems, validate, null);
    }

    private static Optional<BuildingPlan> read(String yaml, List<String> problems,
                                               boolean validate, List<String> notes) {
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
        return read(config, problems, validate, notes);
    }

    /** Lit un plan depuis une section déjà analysée. */
    public static Optional<BuildingPlan> read(ConfigurationSection section, List<String> problems) {
        return read(section, problems, true);
    }

    private static Optional<BuildingPlan> read(ConfigurationSection section, List<String> problems,
                                               boolean validate) {
        return read(section, problems, validate, null);
    }

    private static Optional<BuildingPlan> read(ConfigurationSection section, List<String> problems,
                                               boolean validate, List<String> notes) {
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
            Optional<PlanStep> step = step(map, where, problems,
                    notes == null ? null : front.get(), notes);
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
        Optional<String> invalid = validate ? plan.validate() : Optional.empty();
        if (invalid.isPresent()) {
            problems.add(invalid.get());
            return Optional.empty();
        }
        return Optional.of(plan);
    }

    private static Optional<PlanStep> step(java.util.Map<?, ?> map, String where,
                                           List<String> problems, Facing front,
                                           List<String> notes) {
        Optional<PlanStep.Kind> kind = PlanStep.Kind.of(str(map.get("op")));
        if (kind.isEmpty()) {
            problems.add(where + "instruction inconnue : « " + str(map.get("op"))
                    + " ». Valeurs acceptées : box, shell, walls, clear, gable, door, window, "
                    + "light, stair_run, staircase, ladder, pillars, bed.");
            return Optional.empty();
        }
        PlanStep.Kind k = kind.get();

        // La façade, pour les primitives qui s'y rapportent (issue #94, lot C). Un mot inconnu est
        // REFUSÉ : retomber sur FRONT masquerait une faute de frappe, et le bâtiment sortirait avec
        // sa porte du mauvais côté sans que rien ne le signale.
        Facade facade = null;
        // La clé est « facade ». On tolère aussi « on », mais par un détour qu'il faut expliquer :
        // en YAML 1.1, « on » est un BOOLÉEN. Écrit comme clé, l'analyseur le transforme en
        // Boolean.TRUE, et « map.get("on") » renvoie null. Un plan disant « on: BACK » voyait donc
        // sa façade silencieusement ignorée, et sa porte sortait du mauvais côté. Attrapé par un
        // test. On lit donc les deux formes, et le prompt (lot F) emploiera « facade ».
        String onRaw = str(map.get("facade"));
        if (onRaw.isEmpty()) {
            onRaw = str(map.get("on"));
        }
        if (onRaw.isEmpty()) {
            onRaw = str(map.get(Boolean.TRUE));
        }
        if (!onRaw.isEmpty()) {
            Optional<Facade> parsed = Facade.of(onRaw);
            if (parsed.isEmpty() && front != null) {
                parsed = fromCompass(onRaw, front);
                if (parsed.isPresent()) {
                    Facade f = parsed.get();
                    notes.add(where.replace(" : ", "") + " (" + str(map.get("op")) + ") : « facade: "
                            + onRaw.trim() + " » est un point cardinal ; pour ce bâtiment « front: "
                            + front.name() + " », c'est la façade " + f.name() + " (" + f.label() + ").");
                }
            }
            if (parsed.isEmpty()) {
                problems.add(where + "façade inconnue : « " + onRaw
                        + " ». Valeurs acceptées : FRONT, BACK, LEFT, RIGHT.");
                return Optional.empty();
            }
            facade = parsed.get();
        }

        // Paramètres nommés. Une valeur non entière est refusée plutôt qu'arrondie en silence.
        java.util.Map<String, Integer> numbers = new java.util.LinkedHashMap<>();
        for (String key : new String[] {"offset", "y", "width", "height", "spacing", "count",
            "steps", "hinge", "floor", "wall"}) {
            Object raw = map.get(key);
            if (raw == null) {
                continue;
            }
            if (!(raw instanceof Number number)) {
                problems.add(where + "le paramètre « " + key + " » doit être un entier, reçu « "
                        + raw + " ».");
                return Optional.empty();
            }
            numbers.put(key, number.intValue());
        }

        String state = str(map.get("block"));
        // Les primitives sémantiques ont un matériau par défaut : exiger « block » pour une
        // lanterne obligerait le modèle à se souvenir de son identifiant exact.
        boolean needsBlock = k != PlanStep.Kind.CLEAR && !semantic(k);
        if (needsBlock && state.isEmpty()) {
            problems.add(where + "clé « block » absente — seules « clear » et les primitives "
                    + "sémantiques s'en passent.");
            return Optional.empty();
        }

        // Une zone n'est pas exigée des primitives repérées par façade : elles n'en ont pas besoin,
        // et en réclamer une rendrait la syntaxe inutilement lourde.
        PlanBox box;
        if (map.get("from") == null && byFacade(k)
                && (facade != null || k != PlanStep.Kind.STAIRCASE)) {
            box = PlanBox.at(0, 0, 0);
        } else {
            Optional<PlanBox> parsed = box(map, where, problems);
            if (parsed.isEmpty()) {
                return Optional.empty();
            }
            box = parsed.get();
        }

        // « axis » porte l'axe d'un toit (x/z) ou la direction de montée d'un escalier.
        // « direction » et « axis » remplissent le même champ, mais ne désignent pas la même chose
        // selon la primitive : l'axe du faîte pour « gable » (x ou z), la direction de montée pour
        // « stair_run » (un point cardinal). Un seul mot-clé pour deux vocabulaires est un piège —
        // un modèle écrivant « axis: x » sur un escalier voyait son plan refusé. « direction » est
        // donc le mot juste pour une montée, et « axis » reste accepté pour ne casser aucun plan
        // déjà écrit.
        String axis = str(map.get("direction"));
        if (axis.isEmpty()) {
            axis = str(map.get("axis"));
        }
        return Optional.of(new PlanStep(k, box, state, axis, facade,
                numbers, str(map.get("fill"))));
    }

    /** La façade relative qui regarde ce point cardinal, pour un bâtiment orienté {@code front}. */
    static Optional<Facade> fromCompass(String raw, Facing front) {
        Optional<Facing> compass = Facing.of(raw);
        if (compass.isEmpty()) {
            return Optional.empty();
        }
        BlockStates.Dir wanted = Facade.toDir(compass.get());
        for (Facade f : Facade.values()) {
            if (f.direction(front) == wanted) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    private static boolean semantic(PlanStep.Kind kind) {
        return PlanStep.semanticKind(kind);
    }

    /**
     * Ces primitives se repèrent par façade et décalage, pas par une zone. L'escalier ne l'est que
     * s'il porte une « facade » (marches d'entrée posées sur le terrain, #472).
     */
    private static boolean byFacade(PlanStep.Kind kind) {
        return kind == PlanStep.Kind.DOOR || kind == PlanStep.Kind.WINDOW
                || kind == PlanStep.Kind.STAIRCASE;
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
            problems.add(where + "clé « from » absente ou mal formée (attendu : [x, y, z])"
                    + countHint(map.get("from")));
            return Optional.empty();
        }
        int[] to = map.get("to") == null ? from : triplet(map.get("to"));
        if (to == null) {
            problems.add(where + "clé « to » mal formée (attendu : [x, y, z])" + countHint(map.get("to")));
            return Optional.empty();
        }
        return Optional.of(PlanBox.of(from[0], from[1], from[2], to[0], to[1], to[2]));
    }

    /**
     * Ce qui manque, quand le modèle a écrit une liste de mauvaise longueur (#263). Le cas réel :
     * une échelle « facade: BACK, from: [1, 1] » — des coordonnées de façade, deux nombres. Le refus
     * dit alors quoi écrire, au lieu de répéter la forme attendue.
     */
    private static String countHint(Object raw) {
        if (raw instanceof List<?> list && list.size() != 3) {
            return " : " + list.size() + " nombre(s) reçu(s), il en faut TROIS. Les coordonnées sont "
                    + "toujours locales et complètes, [x, y, z], même pour une échelle ou une "
                    + "lumière — « facade » ne remplace aucune coordonnée.";
        }
        return ".";
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

    // ---- Écriture (issue #282) ------------------------------------------------------------------

    /** L'ordre d'écriture des paramètres nommés : fixe, pour qu'un même plan s'écrive toujours pareil. */
    private static final String[] NUMBER_KEYS = {"offset", "y", "width", "height", "spacing",
        "count", "steps", "hinge", "floor", "wall"};

    /**
     * Écrit un plan dans la forme canonique que {@link #read} relit à l'identique.
     *
     * <p>Sert à rendre un plan réparé par le moteur ({@link PlanRepair}) : l'administrateur et
     * le modèle reçoivent un document complet, jamais un fragment à recoller. Les commentaires du
     * plan d'origine ne survivent pas — ils n'ont aucun effet sur la grille.</p>
     */
    public static String write(BuildingPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("id: ").append(plan.id()).append('\n');
        sb.append("name: ").append(quoted(plan.name())).append('\n');
        sb.append("kind: ").append(plan.kind().name().toLowerCase(java.util.Locale.ROOT)).append('\n');
        sb.append("front: ").append(plan.front().name()).append('\n');
        sb.append("size:\n  x: ").append(plan.sizeX()).append("\n  y: ").append(plan.sizeY())
                .append("\n  z: ").append(plan.sizeZ()).append('\n');
        sb.append("anchor:\n  x: ").append(plan.anchorX()).append("\n  y: ").append(plan.anchorY())
                .append("\n  z: ").append(plan.anchorZ()).append('\n');
        sb.append("steps:\n");
        for (PlanStep step : plan.steps()) {
            sb.append(writeStep(step, "  "));
        }
        return sb.toString();
    }

    /** Une instruction, en élément de la liste {@code steps}. */
    public static String writeStep(PlanStep step, String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append("- op: ").append(step.kind().name().toLowerCase(java.util.Locale.ROOT))
                .append('\n');
        String in = indent + "  ";
        if (step.facade() != null) {
            sb.append(in).append("facade: ").append(step.facade().name()).append('\n');
        }
        PlanBox b = step.box();
        if (!(byFacade(step.kind()) && step.facade() != null)) {
            sb.append(in).append("from: [").append(b.minX()).append(", ").append(b.minY())
                    .append(", ").append(b.minZ()).append("]\n");
            if (b.cells() > 1) {
                sb.append(in).append("to: [").append(b.maxX()).append(", ").append(b.maxY())
                        .append(", ").append(b.maxZ()).append("]\n");
            }
        }
        if (!step.axis().isEmpty()) {
            sb.append(in).append(step.kind() == PlanStep.Kind.GABLE ? "axis: " : "direction: ")
                    .append(step.axis()).append('\n');
        }
        for (String key : NUMBER_KEYS) {
            if (step.hasNumber(key)) {
                sb.append(in).append(key).append(": ").append(step.number(key, 0)).append('\n');
            }
        }
        if (!step.fill().isEmpty()) {
            sb.append(in).append("fill: ").append(scalar(step.fill())).append('\n');
        }
        if (!step.state().isEmpty()) {
            sb.append(in).append("block: ").append(scalar(step.state())).append('\n');
        }
        return sb.toString();
    }

    /** Un état de bloc : nu s'il est simple, entre guillemets s'il porte des propriétés. */
    private static String scalar(String value) {
        return value.matches("[a-z0-9_:.\\-/]+") ? value : quoted(value);
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
