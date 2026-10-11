package com.lodygames.rpgquest.building.plan;

import com.lodygames.rpgquest.building.model.Facing;
import java.util.List;
import java.util.Locale;
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
 */
public record BuildingPlan(String id, String name, int sizeX, int sizeY, int sizeZ, Facing front,
                           int anchorX, int anchorY, int anchorZ, List<PlanStep> steps) {

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
        for (int i = 0; i < steps.size(); i++) {
            PlanStep step = steps.get(i);
            String where = "Instruction " + (i + 1) + " (" + step.kind() + ") : ";
            if (!step.box().within(sizeX, sizeY, sizeZ)) {
                return Optional.of(where + "la zone " + step.box().label()
                        + " sort du volume " + sizeX + " × " + sizeY + " × " + sizeZ + ".");
            }
            if (step.kind() == PlanStep.Kind.GABLE
                    && !step.axis().equals("x") && !step.axis().equals("z")) {
                return Optional.of(where + "axe de pente manquant ou inconnu : « " + step.axis()
                        + " ». Valeurs acceptées : « x » ou « z ».");
            }
            if (step.kind() != PlanStep.Kind.CLEAR && !BlockState.valid(step.state())) {
                return Optional.of(where + "état de bloc refusé : « " + step.state() + " ». "
                        + BlockState.RULE);
            }
        }
        return Optional.empty();
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
