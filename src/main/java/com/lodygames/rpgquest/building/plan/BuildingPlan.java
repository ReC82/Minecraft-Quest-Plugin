package com.lodygames.rpgquest.building.plan;

import com.lodygames.rpgquest.building.model.Facing;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Un plan de bâtiment déclaratif, tel qu'une IA peut le produire (issue #94).
 *
 * <h2>Sa place dans la chaîne</h2>
 *
 * <pre>
 *   brief → IA → PLAN (ici) → BuildingPlanExpander → Blueprint → .schem → BuildingDefinition
 * </pre>
 *
 * <p>Le plan est la seule chose que l'IA écrit, et c'est volontairement la pièce la plus pauvre de
 * la chaîne : une taille, une orientation de référence, une ancre, et une liste finie
 * d'instructions au vocabulaire fermé. Il ne contient ni code, ni chemin de fichier, ni commande.
 * Tout ce qui pourrait mal tourner est donc une <em>donnée invalide</em>, refusée par
 * {@link #validate()}, jamais un comportement.</p>
 *
 * <p><strong>Le plan n'est pas un Blueprint.</strong> Le {@link com.lodygames.rpgquest.building.model.Blueprint}
 * est la liste des blocs, cellule par cellule ; le plan est l'intention qui la produit. Garder les
 * deux séparés permet de relire l'intention — « mur creux de 19 × 15, toit à deux pentes » — là où
 * une liste de 3 420 blocs ne se relit pas.</p>
 *
 * @param id     identifiant du bâtiment produit, forme {@code [a-z0-9_]{3,48}}
 * @param name   nom affiché
 * @param sizeX  dimensions du volume, ancré en (0,0,0)
 * @param front  orientation de référence : la façade telle que le plan la décrit. C'est elle que la
 *               rotation au placement compare à l'orientation de l'emplacement
 * @param anchorX ancre locale, c'est-à-dire le point du plan qui se posera sur l'ancre du site
 * @param steps  les instructions, <strong>dans l'ordre</strong> : une instruction postérieure
 *               recouvre une antérieure sur la même cellule
 * @param kind   ce que la structure prétend être (issue #94, lot A). Décide des règles qu'on a le
 *               droit de lui appliquer : exiger une porte et des fenêtres d'une fontaine n'aurait
 *               aucun sens. {@link StructureKind#HABITABLE} par défaut, parce que c'est le profil
 *               le plus exigeant et donc le défaut le plus sûr
 */
public record BuildingPlan(String id, String name, int sizeX, int sizeY, int sizeZ, Facing front,
                           int anchorX, int anchorY, int anchorZ, List<PlanStep> steps,
                           StructureKind kind) {

    /** Forme d'un identifiant de bâtiment — la même que la bibliothèque attend. */
    public static final Pattern ID = Pattern.compile("[a-z0-9_]{3,48}");

    /** Dimension maximale d'un côté. Un Hub n'a pas besoin de plus, et la borne protège la mémoire. */
    public static final int MAX_SIDE = 64;

    /**
     * Volume maximal en cellules.
     *
     * <p>Borne de <strong>coût</strong>, pas de goût : l'expansion construit une cellule par
     * position touchée, et le {@code .schem} les écrit toutes. 150 000 cellules (par exemple
     * 48 × 40 × 48) restent instantanées ; un cube de 64³ en ferait 262 144 et resterait
     * acceptable, mais la borne existe pour qu'un plan absurde soit refusé <em>avant</em> d'allouer
     * quoi que ce soit, et non découvert par un serveur qui rame.</p>
     */
    public static final long MAX_CELLS = 150_000;

    /** Nombre maximal d'instructions. Au-delà, c'est une liste de blocs déguisée. */
    public static final int MAX_STEPS = 400;

    public BuildingPlan {
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        name = name == null ? "" : name.trim();
        front = front == null ? Facing.NORTH : front;
        steps = List.copyOf(steps == null ? List.of() : steps);
        kind = kind == null ? StructureKind.HABITABLE : kind;
    }

    /** Constructeur de compatibilité : un plan sans type déclaré est un bâtiment habitable. */
    public BuildingPlan(String id, String name, int sizeX, int sizeY, int sizeZ, Facing front,
                        int anchorX, int anchorY, int anchorZ, List<PlanStep> steps) {
        this(id, name, sizeX, sizeY, sizeZ, front, anchorX, anchorY, anchorZ, steps,
                StructureKind.HABITABLE);
    }

    /** Les seuils d'audit applicables à ce plan, déduits de son type. */
    public ArchitecturalRules rules() {
        return ArchitecturalRules.forKind(kind);
    }

    /**
     * Vérifie que le plan est exploitable, et <strong>nomme la faute</strong>.
     *
     * <p>Chaque message désigne l'instruction fautive par son rang, parce que c'est la seule chose
     * qu'un administrateur puisse corriger ou renvoyer à l'IA. « Plan invalide » ne sert personne.</p>
     *
     * @return le motif du refus, ou vide si le plan est exploitable
     */
    public Optional<String> validate() {
        if (!ID.matcher(id).matches()) {
            return Optional.of("Identifiant de bâtiment invalide : « " + id
                    + " » (attendu : 3 à 48 caractères, minuscules, chiffres ou « _ »).");
        }
        if (name.isEmpty()) {
            return Optional.of("Le bâtiment doit porter un nom.");
        }
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            return Optional.of("Dimensions invalides : " + sizeX + " × " + sizeY + " × " + sizeZ
                    + " — les trois doivent être au moins 1.");
        }
        if (sizeX > MAX_SIDE || sizeY > MAX_SIDE || sizeZ > MAX_SIDE) {
            return Optional.of("Dimensions trop grandes : " + sizeX + " × " + sizeY + " × " + sizeZ
                    + " — " + MAX_SIDE + " blocs au plus par côté.");
        }
        long cells = (long) sizeX * sizeY * sizeZ;
        if (cells > MAX_CELLS) {
            return Optional.of("Volume trop grand : " + cells + " cellules pour un maximum de "
                    + MAX_CELLS + ".");
        }
        if (anchorX < 0 || anchorY < 0 || anchorZ < 0
                || anchorX >= sizeX || anchorY >= sizeY || anchorZ >= sizeZ) {
            return Optional.of("Ancre hors du volume : " + anchorX + "/" + anchorY + "/" + anchorZ
                    + " dans " + sizeX + " × " + sizeY + " × " + sizeZ + ".");
        }
        if (steps.isEmpty()) {
            return Optional.of("Le plan ne contient aucune instruction : il produirait un bâtiment "
                    + "entièrement vide.");
        }
        if (steps.size() > MAX_STEPS) {
            return Optional.of("Trop d'instructions : " + steps.size() + " pour un maximum de "
                    + MAX_STEPS + ".");
        }
        List<String> problems = new java.util.ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            PlanStep step = steps.get(i);
            String where = "Instruction " + (i + 1) + " (" + step.kind() + ") : ";
            if (!step.box().within(sizeX, sizeY, sizeZ)) {
                problems.add(where + "la zone " + step.box().label()
                        + " sort du volume " + sizeX + " × " + sizeY + " × " + sizeZ + ".");
                continue;
            }
            // Une primitive repérée par façade doit viser une cellule qui existe. Le décalage est
            // borné à la largeur de la façade : c'est là que le lot C supprime définitivement la
            // classe d'erreur qui avait scellé la V1 — le modèle ne calcule plus de coordonnée.
            if ((step.kind() == PlanStep.Kind.DOOR || step.kind() == PlanStep.Kind.WINDOW)
                    && step.facade() != null) {
                int span = step.facade().span(front, sizeX, sizeZ);
                int offset = step.number("offset", span / 2);
                if (offset < 0 || offset >= span) {
                    problems.add(where + "décalage " + offset + " hors de la façade "
                            + step.facade() + ", qui compte " + span + " cellules (0 à "
                            + (span - 1) + ").");
                    continue;
                }
            }
            // #263 : une échelle est une COLONNE. Avant, un « to » aux x/z différents de « from » était
            // ignoré sans un mot — l'échelle sortait ailleurs que là où le plan la décrivait.
            if (step.kind() == PlanStep.Kind.LADDER
                    && (step.box().minX() != step.box().maxX() || step.box().minZ() != step.box().maxZ())) {
                problems.add(where + "une échelle est verticale : « from » et « to » doivent "
                        + "avoir les mêmes x et z, et ne diffèrent que par y (zone reçue "
                        + step.box().label() + ").");
                continue;
            }
            if (step.kind() == PlanStep.Kind.STAIR_RUN) {
                String dir = step.axis();
                if (!dir.equals("north") && !dir.equals("south")
                        && !dir.equals("east") && !dir.equals("west")) {
                    problems.add(where + "direction de montée manquante ou inconnue : « "
                            + dir + " ». Écris « direction: » avec un point cardinal — north, "
                            + "south, east ou west — et non un axe.");
                    continue;
                }
            }
            // #282 : l'escalier entre niveaux — direction connue, plancher au-dessus du pied, et
            // toute la volée (départ et palier compris) dans le volume. Son rapport à la grille
            // (enveloppe, porte, vitrage, appuis) se vérifie à l'expansion, où la grille existe.
            if (step.kind() == PlanStep.Kind.STAIRCASE) {
                if (!step.hasNumber("floor")) {
                    problems.add(where + "clé « floor » absente : c'est le y du PLANCHER à "
                            + "atteindre (le bloc sur lequel on marche à l'étage). Le moteur en "
                            + "déduit le nombre de marches et la trémie.");
                    continue;
                }
                Optional<Staircase> stair = Staircase.of(step);
                if (stair.isEmpty()) {
                    problems.add(where + "direction de montée manquante ou inconnue : « "
                            + step.axis() + " ». Écris « direction: » avec un point cardinal — "
                            + "north, south, east ou west.");
                    continue;
                }
                Optional<String> refused = stair.get().refusal(sizeX, sizeY, sizeZ);
                if (refused.isPresent()) {
                    problems.add(where + refused.get());
                    continue;
                }
            }
            // #288 : un lit est un assemblage de deux cellules. Posé par une « box », il devient
            // deux pieds sans tête, que la physique détruit à la pose : on refuse en disant quoi
            // écrire à la place.
            if (step.kind() != PlanStep.Kind.BED && BlockState.type(step.state()).endsWith("_bed")) {
                problems.add(where + "un lit occupe DEUX cellules (pied et tête) : « "
                        + step.kind().name().toLowerCase(Locale.ROOT) + " » de « " + step.state()
                        + " » poserait des moitiés sans leur paire, détruites à la pose. Utilise "
                        + "« op: bed » avec « from » (le pied), « direction » (vers la tête) et "
                        + "« block » (le lit de la couleur voulue, ex. minecraft:pink_bed).");
                continue;
            }
            if (step.kind() == PlanStep.Kind.BED) {
                Optional<String> bad = Bed.of(step).map(b -> b.refusal(sizeX, sizeY, sizeZ))
                        .orElse(Optional.of("direction de la tête manquante ou inconnue : « "
                                + step.axis() + " ». Écris « direction: » north, south, east ou west."));
                if (bad.isPresent()) {
                    problems.add(where + bad.get());
                    continue;
                }
            }
            if (step.kind() == PlanStep.Kind.GABLE
                    && !step.axis().equals("x") && !step.axis().equals("z")) {
                problems.add(where + "axe de pente manquant ou inconnu : « " + step.axis()
                        + " ». Valeurs acceptées : « x » ou « z ».");
                continue;
            }
            if (!step.fill().isEmpty() && !BlockState.valid(step.fill())) {
                problems.add(where + "matériau de remplissage refusé : « " + step.fill()
                        + " ». " + BlockState.RULE);
                continue;
            }
            if (step.kind() != PlanStep.Kind.CLEAR && !step.state().isEmpty()
                    && !BlockState.valid(step.state())) {
                problems.add(where + "état de bloc refusé : « " + step.state() + " ». "
                        + BlockState.RULE);
                continue;
            }
            // Lot D (#252) : la forme ne suffit pas. « oak_stairs[face=bottom] » a la bonne
            // grammaire et une propriété qui n'existe pas — c'est « half ». On le refuse ici,
            // AVANT toute écriture de schematic.
            Optional<String> badProperty = BlockStateRules.refusal(step.state());
            if (badProperty.isPresent()) {
                problems.add(where + badProperty.get());
                continue;
            }
            Optional<String> badFill = BlockStateRules.refusal(step.fill());
            if (badFill.isPresent()) {
                problems.add(where + badFill.get());
                continue;
            }
        }
        problems.addAll(unknownBlocks());
        return problems.isEmpty() ? Optional.empty() : Optional.of(String.join("\n", problems));
    }

    /**
     * Les états de bloc absents du registre de la version servie (#288–#292), regroupés par état :
     * une ligne par bloc fautif, avec les instructions et les zones qui l'emploient. TOUTES les
     * fautes sont rendues d'un coup — découvrir la seconde au second enregistrement raté, c'est
     * précisément ce qui coûtait un appel payant de plus.
     */
    List<String> unknownBlocks() {
        Map<String, List<Integer>> byState = new java.util.LinkedHashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            PlanStep step = steps.get(i);
            for (String state : List.of(step.state(), step.fill())) {
                if (state.isEmpty() || step.kind() == PlanStep.Kind.CLEAR || !BlockState.valid(state)
                        || BlockStateRules.refusal(state).isPresent()) {
                    continue;
                }
                if (BlockCatalog.vanilla().refusal(state).isPresent()) {
                    byState.computeIfAbsent(state, k -> new java.util.ArrayList<>()).add(i + 1);
                }
            }
        }
        List<String> out = new java.util.ArrayList<>();
        for (var e : byState.entrySet()) {
            StringBuilder sb = new StringBuilder("Bloc inconnu — ")
                    .append(BlockCatalog.vanilla().refusal(e.getKey()).orElseThrow().replaceAll("\\.+$", ""))
                    .append(". Employé par ");
            List<Integer> ranks = e.getValue();
            for (int n = 0; n < ranks.size(); n++) {
                if (n == 4 && ranks.size() > 5) {
                    sb.append(" et ").append(ranks.size() - 4).append(" autre(s) instruction(s)");
                    break;
                }
                sb.append(n == 0 ? "" : " ; ").append(BuildingPlanExpander.describeStep(this, ranks.get(n)));
            }
            out.add(sb.append('.').toString());
        }
        return out;
    }

    public boolean valid() {
        return validate().isEmpty();
    }

    /** Les états de bloc cités par le plan, dans l'ordre de première apparition. */
    public List<String> materials() {
        java.util.Set<String> types = new java.util.LinkedHashSet<>();
        for (PlanStep step : steps) {
            if (step.kind() == PlanStep.Kind.CLEAR) {
                continue;
            }
            types.add(BlockState.type(step.state()));
        }
        return List.copyOf(types);
    }
}
