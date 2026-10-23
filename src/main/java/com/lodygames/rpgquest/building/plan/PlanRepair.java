package com.lodygames.rpgquest.building.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * La réparation <strong>déterministe</strong> d'un plan, avant que l'administrateur ne voie le
 * résultat (issue #282).
 *
 * <h2>Pourquoi le moteur répare lui-même</h2>
 *
 * <p>Deux refus réels du 12/10/2026 ont la même forme : le serveur connaît la faute exacte, et
 * l'administrateur sert d'intermédiaire entre le validateur et l'IA pour la faire corriger — à ses
 * frais, avec le risque d'une nouvelle variante du même défaut.</p>
 * <ul>
 *   <li>« Maison du bibliothécaire » : un toit {@code gable} de y=5 à y=8 dans un volume haut de 8
 *       (y de 0 à 7). Un bloc de trop, plan refusé en entier ;</li>
 *   <li>« Bibliothèque du Hub » : une volée de 5 marches pour un plancher qui en exigeait 6, et une
 *       box de bibliothèques sur tout l'étage.</li>
 * </ul>
 *
 * <p>Ces fautes ont une correction <em>sûre</em>, que le moteur peut calculer et vérifier. Il le
 * fait, et ne laisse à l'IA que ce qui relève du goût.</p>
 *
 * <h2>Ce qui la rend sûre</h2>
 *
 * <ul>
 *   <li><strong>Un catalogue fermé</strong> de corrections, chacune décrite en français ;</li>
 *   <li><strong>chaque correction est rejouée</strong> par le vrai pipeline (validation,
 *       expansion, audit) et n'est gardée que si elle n'ajoute aucun refus — une réparation qui
 *       ouvrirait l'étage en bouchant la porte est jetée ;</li>
 *   <li><strong>le plan d'origine n'est jamais modifié</strong> : la réparation produit un plan
 *       neuf, que l'atelier enregistre comme une nouvelle version, réversible.</li>
 * </ul>
 */
public final class PlanRepair {

    private PlanRepair() {
    }

    /** Débordement maximal qu'on ramène dans le volume sans demander l'avis de personne. */
    static final int MAX_CLAMP = 3;

    /** Part minimale d'un plancher couverte par une box de meubles pour qu'on la creuse. */
    static final double DECLUTTER_SHARE = 0.40;

    /**
     * Les bornes du projet : l'atelier sait jusqu'où un bâtiment a le droit de grandir, le plan
     * ne le sait pas. 0 = pas de borne connue sur cet axe.
     *
     * @param maxWidth  limite en X (largeur)
     * @param maxDepth  limite en Z (longueur / profondeur), indépendante de X depuis #305
     * @param maxHeight limite en Y (hauteur)
     */
    public record Limits(int maxWidth, int maxDepth, int maxHeight) {

        /** Forme antérieure à #305 : une limite horizontale carrée, X = Z. */
        public Limits(int maxWidth, int maxHeight) {
            this(maxWidth, maxWidth, maxHeight);
        }

        public static Limits none() {
            return new Limits(0, 0, 0);
        }

        /**
         * Les axes que ce volume dépasse (#305), un libellé par axe, vide s'il tient. Un axe sans
         * borne ou une taille inconnue (0) ne dépasse jamais.
         */
        public List<String> exceeded(int sizeX, int sizeY, int sizeZ) {
            List<String> out = new ArrayList<>();
            if (maxWidth > 0 && sizeX > maxWidth) {
                out.add("size.x = " + sizeX + " pour une largeur maximale (X) de " + maxWidth);
            }
            if (maxDepth > 0 && sizeZ > maxDepth) {
                out.add("size.z = " + sizeZ + " pour une longueur maximale (Z) de " + maxDepth);
            }
            if (maxHeight > 0 && sizeY > maxHeight) {
                out.add("size.y = " + sizeY + " pour une hauteur maximale (Y) de " + maxHeight);
            }
            return out;
        }
    }

    /**
     * L'issue d'une réparation.
     *
     * @param plan    le plan réparé, ou {@code null} si rien n'a pu être corrigé
     * @param changes ce qui a été changé, une phrase par correction
     * @param before  les refus avant réparation ({@code PLAN_INVALID} compris)
     * @param after   les refus après — vide si le plan réparé est conforme
     */
    public record Outcome(BuildingPlan plan, List<String> changes, List<String> before,
                          List<String> after) {

        public Outcome {
            changes = List.copyOf(changes);
            before = List.copyOf(before);
            after = List.copyOf(after);
        }

        public boolean repaired() {
            return plan != null && !changes.isEmpty();
        }

        /** Le plan réparé passe-t-il entièrement ? */
        public boolean accepted() {
            return repaired() && after.isEmpty();
        }
    }

    /** Répare un plan lu sans validation. */
    public static Outcome repair(BuildingPlan raw, Limits limits) {
        return repair(raw, limits, List.of());
    }

    /**
     * Répare un plan lu par {@link BuildingPlanYaml#readForRepair} : les traductions déjà faites à
     * la lecture (façades cardinales, #655) sont des corrections à part entière, et le plan
     * d'origine était illisible — c'est donc depuis {@code PLAN_INVALID} qu'on juge le progrès.
     */
    public static Outcome repair(BuildingPlan raw, Limits limits, List<String> readNotes) {
        List<String> changes = new ArrayList<>(readNotes == null ? List.of() : readNotes);
        List<String> before = changes.isEmpty() ? codes(raw) : List.of("PLAN_INVALID");
        BuildingPlan plan = materials(raw, changes);
        plan = bounds(plan, limits == null ? Limits.none() : limits, changes);
        if (plan.validate().isPresent()) {
            // Rien de sûr à faire : la faute n'est pas dans le catalogue.
            return new Outcome(null, List.of(), before, before);
        }
        plan = wrongWall(plan, changes);
        plan = openings(plan, changes);
        plan = needlessEntrySteps(plan, changes);
        plan = circulation(plan, changes);
        plan = doorway(plan, changes);
        plan = entrance(plan, changes);
        plan = sunkFurniture(plan, changes);
        plan = lanterns(plan, changes);
        plan = darkAreas(plan, changes);
        plan = destructiveClears(plan, changes);
        List<String> after = codes(plan);
        if (changes.isEmpty() || worse(before, after) && !progressWhileInvalid(raw, plan, readNotes)) {
            return new Outcome(null, List.of(), before, before);
        }
        return new Outcome(plan, changes, before, after);
    }

    /**
     * Un plan qui reste illisible après réparation a-t-il tout de même progressé (#655) ? Oui si
     * la lecture stricte devient possible (façades traduites) ou si la première faute rendue par le
     * moteur n'est plus la même. Jeter ces corrections sûres, c'était renvoyer à l'IA — payante —
     * une faute que le moteur savait corriger, cachée derrière la suivante : la Boulangerie a payé
     * quatre corrections pour un toit à y=10, puis une fenêtre qui débordait du mur.
     */
    static boolean progressWhileInvalid(BuildingPlan raw, BuildingPlan repaired, List<String> readNotes) {
        if (readNotes != null && !readNotes.isEmpty()) {
            return true;
        }
        return !firstFault(raw).equals(firstFault(repaired));
    }

    private static String firstFault(BuildingPlan plan) {
        Optional<String> invalid = plan.validate();
        if (invalid.isPresent()) {
            return invalid.get();
        }
        BuildingPlanExpander.Result r = BuildingPlanExpander.expand(plan);
        return r.ok() ? "" : r.error();
    }

    /** Les refus bloquants d'un plan, tels que le pipeline réel les rend. */
    static List<String> codes(BuildingPlan plan) {
        if (plan.validate().isPresent()) {
            return List.of("PLAN_INVALID");
        }
        BuildingPlanExpander.Result r = BuildingPlanExpander.expand(plan);
        if (!r.ok()) {
            return List.of("PLAN_INVALID");
        }
        return ArchitecturalAudit.audit(r.blueprint(), plan.front(), plan.rules(),
                ArchitecturalAudit.Provenance.NONE, plan.anchorY()).blockingCodes();
    }

    /**
     * La réparation fait-elle apparaître un refus nouveau ? Un plan illisible qui devient lisible
     * n'est jamais « pire » ; et ouvrir un étage peut révéler qu'il est sombre ou encombré — ce
     * n'est pas une régression, c'est un défaut qu'on ne pouvait pas mesurer avant.
     */
    static boolean worse(List<String> before, List<String> after) {
        if (before.contains("PLAN_INVALID")) {
            return after.contains("PLAN_INVALID");
        }
        for (String code : after) {
            if (before.contains(code)) {
                continue;
            }
            if (before.contains("LEVEL_UNREACHABLE")
                    && (code.equals("LEVEL_CLUTTERED") || code.equals("DARK_AREA"))) {
                continue;
            }
            return true;
        }
        return false;
    }

    // ---- 0. Les identifiants de bloc (#655) ----------------------------------------------------

    /**
     * Remplace un identifiant de bloc inconnu par son pluriel, quand ce pluriel est un bloc réel.
     *
     * <p>« minecraft:brick » est l'<em>objet</em> brique ; le bloc est « minecraft:bricks ». Même
     * faute pour {@code stone_brick}, {@code oak_plank}, {@code nether_brick}. La table du registre
     * tranche : le singulier n'existe pas, le pluriel existe, il n'y a qu'un seul candidat — la
     * correction est sûre. Toute autre faute de nom reste un refus, avec ses suggestions.</p>
     */
    static BuildingPlan materials(BuildingPlan plan, List<String> changes) {
        Map<String, String> renamed = new java.util.LinkedHashMap<>();
        List<PlanStep> steps = new ArrayList<>();
        for (PlanStep step : plan.steps()) {
            String state = plural(step.state(), renamed);
            String fill = plural(step.fill(), renamed);
            steps.add(state.equals(step.state()) && fill.equals(step.fill()) ? step
                    : new PlanStep(step.kind(), step.box(), state, step.axis(), step.facade(),
                            step.numbers(), fill));
        }
        if (!renamed.isEmpty()) {
            renamed.forEach((from, to) -> changes.add("Bloc « " + from + " » inconnu en Minecraft "
                    + BlockCatalog.VERSION + " : remplacé par « " + to + " », le bloc de ce matériau."));
        }
        return gableStairs(renamed.isEmpty() ? plan : copy(plan, steps), changes);
    }

    /**
     * Un toit {@code gable} écrit avec un bloc PLEIN reçoit l'escalier du même matériau (#747) : le moteur
     * oriente des marches, et un bloc sans propriété « facing » est refusé par le registre — tout le plan
     * avec lui. Essai réel du 23/10/2026 (V-979a80e5) : « block: minecraft:polished_deepslate » sur deux
     * toits. Le candidat n'est retenu que s'il existe dans le registre et que c'est un escalier ; le
     * bloc plein devient le « fill » des pignons s'il n'y en avait pas.
     */
    static BuildingPlan gableStairs(BuildingPlan plan, List<String> changes) {
        List<PlanStep> steps = new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < plan.steps().size(); i++) {
            PlanStep step = plan.steps().get(i);
            String type = BlockStates.bareType(step.state());
            String stairs = step.kind() == PlanStep.Kind.GABLE && !type.endsWith("_stairs")
                    ? stairsOf(type) : null;
            if (stairs == null) {
                steps.add(step);
                continue;
            }
            String fill = step.fill() == null || step.fill().isBlank() ? type : step.fill();
            steps.add(new PlanStep(step.kind(), step.box(), stairs, step.axis(), step.facade(), step.numbers(), fill));
            changes.add("Instruction " + (i + 1) + " (gable) : « " + type + " » est un bloc plein, sans marches à "
                    + "orienter — toit en « " + stairs + " », le bloc plein garnit les pignons.");
            changed = true;
        }
        return changed ? copy(plan, steps) : plan;
    }

    /** L'escalier réel d'un matériau plein, ou {@code null} : quartz_block → quartz_stairs, bricks → brick_stairs. */
    static String stairsOf(String type) {
        List<String> candidates = new ArrayList<>();
        candidates.add(type + "_stairs");
        for (String[] suffix : new String[][] {{"_planks", "_stairs"}, {"_block", "_stairs"},
            {"_bricks", "_brick_stairs"}, {"_tiles", "_tile_stairs"}, {"bricks", "brick_stairs"}}) {
            if (type.endsWith(suffix[0])) {
                candidates.add(type.substring(0, type.length() - suffix[0].length()) + suffix[1]);
            }
        }
        for (String c : candidates) {
            if (BlockCatalog.vanilla().known(c) && BlockCatalog.vanilla().hasProperty(c, "facing")) {
                return c;
            }
        }
        return null;
    }

    private static String plural(String state, Map<String, String> renamed) {
        if (state == null || state.isBlank() || !BlockState.valid(state)
                || BlockCatalog.vanilla().known(state)) {
            return state == null ? "" : state;
        }
        String trimmed = state.trim();
        int bracket = trimmed.indexOf('[');
        String type = bracket < 0 ? trimmed : trimmed.substring(0, bracket);
        String candidate = type + "s";
        if (type.endsWith("s") || !BlockCatalog.vanilla().known(candidate)) {
            return state;
        }
        renamed.put(type, candidate);
        return candidate + (bracket < 0 ? "" : trimmed.substring(bracket));
    }

    // ---- 1. Le volume ---------------------------------------------------------------------------

    /**
     * Ramène dans le volume les zones qui en débordent de peu.
     *
     * <p>Agrandir le volume, quand le projet le permet, garde le toit entier ; sinon la zone est
     * coupée au volume. Pour un toit {@code gable}, c'est exactement ce que le moteur fait déjà
     * d'un toit plus haut que sa zone : il est tronqué en faîte plat, et l'enveloppe reste close —
     * l'audit qui suit le vérifie.</p>
     */
    static BuildingPlan bounds(BuildingPlan plan, Limits limits, List<String> changes) {
        int needX = plan.sizeX();
        int needY = plan.sizeY();
        int needZ = plan.sizeZ();
        for (PlanStep step : plan.steps()) {
            PlanBox b = step.box();
            needX = Math.max(needX, b.maxX() + 1);
            needY = Math.max(needY, b.maxY() + 1);
            needZ = Math.max(needZ, b.maxZ() + 1);
        }
        int sx = grow(plan.sizeX(), needX, limits.maxWidth());
        int sy = grow(plan.sizeY(), needY, limits.maxHeight());
        int sz = grow(plan.sizeZ(), needZ, limits.maxDepth());
        if (sx != plan.sizeX() || sy != plan.sizeY() || sz != plan.sizeZ()) {
            changes.add("Volume porté de " + plan.sizeX() + " × " + plan.sizeY() + " × "
                    + plan.sizeZ() + " à " + sx + " × " + sy + " × " + sz
                    + " pour contenir les instructions, dans les limites du projet.");
        }
        List<PlanStep> steps = new ArrayList<>();
        for (int i = 0; i < plan.steps().size(); i++) {
            PlanStep step = plan.steps().get(i);
            PlanBox b = step.box();
            Optional<PlanBox> lowered = b.within(sx, sy, sz) ? Optional.empty() : lowered(step, b, sx, sy, sz);
            if (lowered.isPresent()) {
                PlanBox down = lowered.get();
                steps.add(new PlanStep(step.kind(), down, step.state(), step.axis(), step.facade(),
                        step.numbers(), step.fill()));
                changes.add("Instruction " + (i + 1) + " (gable) : toit posé entièrement au-dessus du "
                        + "volume (zone " + b.label() + ", y de 0 à " + (sy - 1) + " seulement) — "
                        + "abaissé de " + (b.minY() - down.minY()) + " bloc(s) en " + down.label()
                        + " : il coiffe le haut des murs au lieu de flotter hors du volume.");
                continue;
            }
            if (b.within(sx, sy, sz) || !clampable(step, b, sx, sy, sz)) {
                steps.add(step);
                continue;
            }
            PlanBox clamped = PlanBox.of(Math.max(0, b.minX()), Math.max(0, b.minY()),
                    Math.max(0, b.minZ()), Math.min(sx - 1, b.maxX()), Math.min(sy - 1, b.maxY()),
                    Math.min(sz - 1, b.maxZ()));
            steps.add(new PlanStep(step.kind(), clamped, step.state(), step.axis(), step.facade(),
                    step.numbers(), step.fill()));
            changes.add("Instruction " + (i + 1) + " (" + step.kind().name().toLowerCase(
                    java.util.Locale.ROOT) + ") : zone " + b.label() + " ramenée à "
                    + clamped.label() + " — elle sortait du volume " + sx + " × " + sy + " × "
                    + sz + " (y de 0 à " + (sy - 1) + ")"
                    + (step.kind() == PlanStep.Kind.GABLE ? ", le toit est arasé d'autant." : "."));
        }
        return new BuildingPlan(plan.id(), plan.name(), sx, sy, sz, plan.front(),
                Math.min(plan.anchorX(), sx - 1), Math.min(plan.anchorY(), sy - 1),
                Math.min(plan.anchorZ(), sz - 1), steps, plan.kind());
    }

    /**
     * Un toit {@code gable} posé ENTIÈREMENT au-dessus du volume (Boulangerie de Central, #655 :
     * murs jusqu'à y=9, toit en y=10 dans un volume haut de 10) ne peut pas être « ramené » par
     * découpe : il disparaîtrait. On l'abaisse donc, d'au plus {@value #MAX_CLAMP} blocs, pour que
     * son sommet affleure le haut du volume. Seul le toit bouge, en x et z il reste où il était ;
     * l'audit qui suit dit si l'enveloppe est close.
     */
    private static Optional<PlanBox> lowered(PlanStep step, PlanBox b, int sx, int sy, int sz) {
        if (step.kind() != PlanStep.Kind.GABLE || b.minY() < sy) {
            return Optional.empty();
        }
        int drop = b.maxY() - (sy - 1);
        boolean footprint = b.minX() >= 0 && b.minZ() >= 0 && b.maxX() < sx && b.maxZ() < sz;
        if (drop > MAX_CLAMP || !footprint || b.minY() - drop < 1) {
            return Optional.empty();
        }
        return Optional.of(PlanBox.of(b.minX(), b.minY() - drop, b.minZ(), b.maxX(), b.maxY() - drop,
                b.maxZ()));
    }

    private static int grow(int size, int need, int limit) {
        return need > size && need <= limit ? need : size;
    }

    /** Une zone de volume qui déborde de peu, et pas une primitive repérée par un point. */
    private static boolean clampable(PlanStep step, PlanBox b, int sx, int sy, int sz) {
        boolean volume = switch (step.kind()) {
            case BOX, SHELL, WALLS, CLEAR, GABLE, PILLARS, LIGHT -> true;
            default -> false;
        };
        int over = Math.max(Math.max(-b.minX(), Math.max(-b.minY(), -b.minZ())),
                Math.max(b.maxX() - (sx - 1), Math.max(b.maxY() - (sy - 1), b.maxZ() - (sz - 1))));
        // Une zone qui sortirait ENTIÈREMENT du volume ne se « ramène » pas : elle disparaîtrait.
        boolean overlaps = b.maxX() >= 0 && b.maxY() >= 0 && b.maxZ() >= 0
                && b.minX() < sx && b.minY() < sy && b.minZ() < sz;
        return volume && over <= MAX_CLAMP && overlaps;
    }

    // ---- 2. Les ouvertures (#288–#292) ---------------------------------------------------------

    /** Décalage maximal d'une porte ou d'une fenêtre pour la ramener dans son mur. */
    static final int MAX_SHIFT = 3;

    /**
     * Ramène dans le mur construit une porte ou une fenêtre qui en déborde de peu — typiquement
     * une fenêtre posée sur l'angle du corps de bâtiment (Maison de Lily : vitrage avant en x=3,
     * angle du mur de x=3 à x=15). Le plus petit décalage qui fait accepter l'instruction par le
     * vrai expanseur est retenu ; au-delà de {@value #MAX_SHIFT} cellules, c'est une intention à
     * revoir, pas une faute de calcul.
     */
    static BuildingPlan openings(BuildingPlan plan, List<String> changes) {
        BuildingPlan current = plan;
        for (int guard = 0; guard < plan.steps().size(); guard++) {
            BuildingPlanExpander.Result r = BuildingPlanExpander.expand(current);
            int rank = failingOpening(r);
            if (rank < 1) {
                return current;
            }
            PlanStep step = current.steps().get(rank - 1);
            int offset = step.number("offset", step.kind() == PlanStep.Kind.WINDOW ? 1
                    : step.facade() == null ? 0 : step.facade().span(current.front(), current.sizeX(),
                            current.sizeZ()) / 2);
            BuildingPlan fixed = null;
            int chosen = 0;
            for (int d = 1; d <= MAX_SHIFT && fixed == null; d++) {
                for (int sign : new int[] {1, -1}) {
                    Map<String, Integer> numbers = new HashMap<>(step.numbers());
                    numbers.put("offset", offset + sign * d);
                    List<PlanStep> steps = new ArrayList<>(current.steps());
                    steps.set(rank - 1, new PlanStep(step.kind(), step.box(), step.state(), step.axis(),
                            step.facade(), numbers, step.fill()));
                    BuildingPlan candidate = copy(current, steps);
                    if (candidate.validate().isEmpty()
                            && failingOpening(BuildingPlanExpander.expand(candidate)) != rank) {
                        fixed = candidate;
                        chosen = offset + sign * d;
                        break;
                    }
                }
            }
            if (fixed == null) {
                return current;
            }
            changes.add(BuildingPlanExpander.describeStep(current, rank).substring(0, 1)
                    .toUpperCase(java.util.Locale.ROOT)
                    + BuildingPlanExpander.describeStep(current, rank).substring(1)
                    + " : offset " + offset + " → " + chosen + ", pour tomber dans le mur construit "
                    + "(angles exclus) au lieu d'en sortir.");
            current = fixed;
        }
        return current;
    }

    /** Écart maximal entre le « wall: » écrit et le mur réellement construit, pour le recaler. */
    static final int MAX_WALL_SHIFT = 2;

    private static final java.util.regex.Pattern WRONG_WALL = java.util.regex.Pattern.compile(
            "^Instruction (\\d+) \\((DOOR|WINDOW)\\) : .*vise « wall: (-?\\d+) », mais aucun mur construit.*\\(murs : ([^)]*)\\)",
            java.util.regex.Pattern.DOTALL);

    /**
     * Recale une porte ou une fenêtre dont le « wall: » manque d'une ou deux cellules le mur construit
     * de sa façade (#747). Église de test, V-979a80e5 (23/10/2026) : « wall: 15 » pour un mur en z=16 —
     * refus PLAN_INVALID, donc aucun autre contrôle exécuté, et un appel payant pour une cellule. Le mur
     * retenu est le plus proche parmi ceux que l'expanseur a listés, à {@value #MAX_WALL_SHIFT} cellules
     * au plus ; il n'est gardé que si l'expanseur accepte alors l'instruction.
     */
    static BuildingPlan wrongWall(BuildingPlan plan, List<String> changes) {
        BuildingPlan current = plan;
        for (int guard = 0; guard < plan.steps().size(); guard++) {
            BuildingPlanExpander.Result r = BuildingPlanExpander.expand(current);
            if (r.ok()) {
                return current;
            }
            var m = WRONG_WALL.matcher(r.error());
            if (!m.find()) {
                return current;
            }
            int rank = Integer.parseInt(m.group(1));
            int written = Integer.parseInt(m.group(3));
            Integer nearest = null;
            var wall = java.util.regex.Pattern.compile("[xz]=(-?\\d+)").matcher(m.group(4));
            while (wall.find()) {
                int w = Integer.parseInt(wall.group(1));
                if (Math.abs(w - written) <= MAX_WALL_SHIFT
                        && (nearest == null || Math.abs(w - written) < Math.abs(nearest - written))) {
                    nearest = w;
                }
            }
            if (nearest == null) {
                return current;
            }
            PlanStep step = current.steps().get(rank - 1);
            Map<String, Integer> numbers = new HashMap<>(step.numbers());
            numbers.put("wall", nearest);
            List<PlanStep> steps = new ArrayList<>(current.steps());
            steps.set(rank - 1, new PlanStep(step.kind(), step.box(), step.state(), step.axis(), step.facade(),
                    numbers, step.fill()));
            BuildingPlan candidate = copy(current, steps);
            BuildingPlanExpander.Result after = BuildingPlanExpander.expand(candidate);
            if (!candidate.validate().isEmpty()
                    || !after.ok() && after.error().startsWith("Instruction " + rank + " (")) {
                return current;
            }
            String described = BuildingPlanExpander.describeStep(current, rank);
            changes.add(described.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + described.substring(1)
                    + " : wall " + written + " → " + nearest + ", le mur réellement construit de cette façade.");
            current = candidate;
        }
        return current;
    }

    /**
     * Relève d'un bloc le mobilier posé DANS la couche supérieure d'un sol (#747). Essai réel du
     * 23/10/2026 (V-979a80e5) : sol « box » en y=0, puis tapis de l'allée et bancs en y=0 — ils
     * remplaçaient le sol au lieu de s'y poser (27 tapis « rien sous lui », un sol troué). Seuls les
     * blocs qui ne sont jamais un revêtement de sol voulu sont concernés (tapis, escaliers-assises,
     * bougies, fleurs et pots, pupitre) ; une « box » d'un bloc plein différent reste un motif de sol.
     * Le déplacement n'est gardé que si la couche du dessus était libre et qu'aucun refus nouveau
     * n'apparaît.
     */
    static BuildingPlan sunkFurniture(BuildingPlan plan, List<String> changes) {
        BuildingPlan current = plan;
        for (int i = 0; i < current.steps().size(); i++) {
            PlanStep step = current.steps().get(i);
            PlanBox b = step.box();
            if (step.kind() != PlanStep.Kind.BOX || b.minY() != b.maxY() || !furniture(step.state())) {
                continue;
            }
            int target = surface(current, i);
            if (target < 0) {
                continue;
            }
            List<PlanStep> steps = new ArrayList<>(current.steps());
            PlanBox lifted = new PlanBox(b.minX(), target, b.minZ(), b.maxX(), target, b.maxZ());
            steps.set(i, new PlanStep(step.kind(), lifted, step.state(), step.axis(), step.facade(), step.numbers(),
                    step.fill()));
            BuildingPlan candidate = copy(current, steps);
            if (!candidate.validate().isEmpty() || worse(codes(current), codes(candidate))) {
                continue;
            }
            String described = BuildingPlanExpander.describeStep(current, i + 1);
            changes.add(described.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + described.substring(1)
                    + " : posé DANS le sol (y=" + b.minY() + ") qu'il remplaçait — relevé sur le sol, en y="
                    + target + ".");
            current = candidate;
        }
        return current;
    }

    private static boolean furniture(String state) {
        String t = BlockStates.bareType(state);
        return t.endsWith("_carpet") || t.endsWith("_stairs") || t.endsWith("candle") || t.startsWith("minecraft:potted_")
                || t.equals("minecraft:flower_pot") || t.equals("minecraft:lectern");
    }

    /**
     * La couche où poser ce mobilier, sur le plan compilé SANS lui : il doit avoir remplacé de la
     * matière (toute sa zone était pleine à sa hauteur), et la première couche entièrement libre au-dessus,
     * à deux blocs au plus, doit reposer sur de la matière partout. {@code -1} sinon.
     */
    private static int surface(BuildingPlan plan, int rank) {
        List<PlanStep> without = new ArrayList<>(plan.steps());
        without.remove(rank);
        BuildingPlan rest = copy(plan, without);
        if (rest.validate().isPresent()) {
            return -1;
        }
        BuildingPlanExpander.Result r = BuildingPlanExpander.expand(rest);
        if (!r.ok()) {
            return -1;
        }
        java.util.Set<String> solid = new java.util.HashSet<>();
        for (var block : r.blueprint().blocks()) {
            if (!BlockSemantics.passable(block.state())) {
                solid.add(block.x() + "/" + block.y() + "/" + block.z());
            }
        }
        java.util.Set<String> any = new java.util.HashSet<>();
        r.blueprint().blocks().forEach(block -> any.add(block.x() + "/" + block.y() + "/" + block.z()));
        PlanBox b = plan.steps().get(rank).box();
        if (!layer(b, b.minY(), solid, true)) {
            return -1;
        }
        for (int t = b.minY() + 1; t <= b.minY() + 2 && t < plan.sizeY(); t++) {
            if (layer(b, t, any, false)) {
                return layer(b, t - 1, solid, true) ? t : -1;
            }
        }
        return -1;
    }

    /** Toute la zone de {@code b} à la hauteur {@code y} est dans {@code cells} (full) ou hors (!full). */
    private static boolean layer(PlanBox b, int y, java.util.Set<String> cells, boolean full) {
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                if (cells.contains(x + "/" + y + "/" + z) != full) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Retire des marches d'entrée ({@code staircase} repéré par façade) que l'expanseur refuse ET
     * qui ne montent nulle part : leur {@code floor} n'est pas au-dessus du terrain. Boulangerie de
     * Central (#655) : plancher affleurant le terrain (ancre y=1), « staircase facade: EAST floor: 1 »
     * plaqué contre le four en briques de la façade — refusé, et aucun dénivelé à franchir. Une
     * volée qui servait réellement (floor au-dessus du terrain) n'est jamais retirée : c'est
     * l'audit de l'entrée qui dira alors ce qui manque.
     */
    static BuildingPlan needlessEntrySteps(BuildingPlan plan, List<String> changes) {
        BuildingPlan current = plan;
        for (int guard = 0; guard < plan.steps().size(); guard++) {
            BuildingPlanExpander.Result r = BuildingPlanExpander.expand(current);
            if (r.ok()) {
                return current;
            }
            var m = java.util.regex.Pattern.compile("^Instruction (\\d+) \\(STAIRCASE\\) : ")
                    .matcher(r.error());
            if (!m.find()) {
                return current;
            }
            int rank = Integer.parseInt(m.group(1));
            PlanStep step = current.steps().get(rank - 1);
            if (step.facade() == null || step.number("floor", Integer.MAX_VALUE) > current.anchorY()) {
                return current;
            }
            String described = BuildingPlanExpander.describeStep(current, rank);
            List<PlanStep> steps = new ArrayList<>(current.steps());
            steps.remove(rank - 1);
            current = copy(current, steps);
            changes.add("Retrait de l'" + described + " : marches d'entrée refusées par le moteur, et "
                    + "inutiles — leur palier (floor " + step.number("floor", 0) + ") n'est pas au-dessus "
                    + "du terrain (ancre y=" + plan.anchorY() + "), le plancher est déjà de plain-pied.");
        }
        return current;
    }

    /** Le rang de la porte ou fenêtre que l'expanseur refuse faute de mur, ou 0. */
    private static int failingOpening(BuildingPlanExpander.Result r) {
        if (r.ok()) {
            return 0;
        }
        var m = java.util.regex.Pattern.compile("^Instruction (\\d+) \\((DOOR|WINDOW)\\) : .*"
                + "ne tombe dans aucun mur construit", java.util.regex.Pattern.DOTALL).matcher(r.error());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    // ---- 3. L'entrée manquante (#655) ----------------------------------------------------------

    /**
     * Perce une vraie porte dans la façade avant quand le bâtiment n'a AUCUNE entrée de ce côté.
     *
     * <p>Boulangerie de Central, 20/10/2026 : dix corrections payantes de suite ont rendu un plan
     * sans passage dans la façade EST (x=9), chaque fois avec la même consigne. Le moteur sait ce
     * qu'il faut : une instruction {@code door} sur {@code FRONT} — la primitive qui pose les deux
     * moitiés, l'orientation, la charnière et le dégagement intérieur. Il cherche, depuis le milieu
     * de la façade, la première position où l'audit trouve une entrée praticable, au niveau du
     * premier plancher, et ne garde la porte que si elle n'ajoute aucun autre refus. Le contrôle
     * {@code NO_ENTRANCE} n'est jamais assoupli : c'est l'audit complet qui juge la porte.</p>
     *
     * <p>Rien n'est fait si le plan a déjà une porte sur sa façade avant : ce qui la rend
     * impraticable est une intention à revoir, pas un oubli.</p>
     */
    static BuildingPlan doorway(BuildingPlan plan, List<String> changes) {
        Snapshot s = snapshot(plan);
        if (s == null || !s.report().blockingCodes().contains("NO_ENTRANCE")) {
            return plan;
        }
        for (PlanStep step : plan.steps()) {
            if (step.kind() == PlanStep.Kind.DOOR && step.facade() == Facade.FRONT) {
                return plan;
            }
        }
        List<String> before = s.report().blockingCodes();
        List<Circulation.Level> levels = Circulation.detect(AuditGrid.of(s.expansion().blueprint()));
        Set<Integer> heights = new LinkedHashSet<>();
        if (!levels.isEmpty()) {
            heights.add(levels.get(0).standY());
        }
        heights.add(1);
        String block = doorMaterial(plan);
        int span = Facade.FRONT.span(plan.front(), plan.sizeX(), plan.sizeZ());
        int center = span / 2;
        for (int d = 0; d <= span; d++) {
            for (int sign : d == 0 ? new int[] {1} : new int[] {-1, 1}) {
                int offset = center + sign * d;
                if (offset < 0 || offset >= span) {
                    continue;
                }
                for (int y : heights) {
                    Map<String, Integer> numbers = new HashMap<>();
                    numbers.put("offset", offset);
                    numbers.put("y", y);
                    List<PlanStep> steps = new ArrayList<>(plan.steps());
                    steps.add(new PlanStep(PlanStep.Kind.DOOR, PlanBox.at(0, 0, 0), block, "",
                            Facade.FRONT, numbers, ""));
                    BuildingPlan candidate = copy(plan, steps);
                    if (candidate.validate().isPresent()) {
                        continue;
                    }
                    List<String> after = codes(candidate);
                    if (after.contains("PLAN_INVALID") || after.contains("NO_ENTRANCE")
                            || worseThanNoEntrance(before, after)) {
                        continue;
                    }
                    changes.add("Aucune entrée dans la façade avant (" + plan.front().name() + ") : porte "
                            + BlockState.type(block) + " posée par le moteur (op: door, facade: FRONT, "
                            + "offset " + offset + ", y " + y + "), première position praticable depuis "
                            + "le milieu de la façade.");
                    return candidate;
                }
            }
        }
        return plan;
    }

    /**
     * Une entrée qui apparaît peut révéler une marche à sauter : c'est le refus suivant, qu'on ne
     * pouvait pas mesurer sans porte, et que {@link #entrance} traite juste après.
     */
    private static boolean worseThanNoEntrance(List<String> before, List<String> after) {
        List<String> rest = new ArrayList<>(after);
        rest.remove("ENTRANCE_STEP");
        return worse(before, rest);
    }

    private static String doorMaterial(BuildingPlan plan) {
        for (PlanStep step : plan.steps()) {
            String type = BlockStates.bareType(step.state());
            if (type.endsWith("_door") && !type.contains("trapdoor")) {
                return type;
            }
        }
        return "minecraft:oak_door";
    }

    // ---- 3 bis. Le seuil (#291) -----------------------------------------------------------------

    /**
     * Pose les marches d'entrée qui manquent, quand le seuil est au-dessus du terrain.
     *
     * <p>L'ancre d'un emplacement est la case libre au-dessus du sol : un plancher posé en y=0
     * dépasse donc le terrain d'un bloc, et l'on entrait en sautant (Maison du bibliothécaire,
     * #291). Devant chaque porte, et DANS le volume, le moteur pose une volée d'escaliers tournée
     * vers la porte — une marche par bloc de dénivelé, assise sur le matériau du seuil — puis
     * rejoue l'audit. S'il n'y a pas la place dans le volume, il ne déborde pas : le refus reste,
     * avec sa raison.</p>
     */
    static BuildingPlan entrance(BuildingPlan plan, List<String> changes) {
        Snapshot s = snapshot(plan);
        if (s == null || !s.report().blockingCodes().contains("ENTRANCE_STEP")) {
            return plan;
        }
        AuditGrid grid = AuditGrid.of(s.expansion().blueprint());
        int groundY = plan.anchorY();
        for (var e : grid.cells().entrySet()) {
            if (!Access.lowerDoor(e.getValue())) {
                continue;
            }
            int x = AuditGrid.keyX(e.getKey());
            int y = AuditGrid.keyY(e.getKey());
            int z = AuditGrid.keyZ(e.getKey());
            String facing = e.getValue().replaceAll(".*facing=([a-z]+).*", "$1");
            BlockStates.Dir out = switch (facing) {
                case "east" -> BlockStates.Dir.EAST;
                case "west" -> BlockStates.Dir.WEST;
                case "south" -> BlockStates.Dir.SOUTH;
                default -> BlockStates.Dir.NORTH;
            };
            // La porte regarde le dehors : on vérifie qu'il est bien de ce côté.
            if (!Access.interior(grid, x - out.dx(), y, z - out.dz())) {
                out = out.opposite();
                if (!Access.interior(grid, x - out.dx(), y, z - out.dz())) {
                    continue;
                }
            }
            int rise = y - groundY;
            if (rise < 1 || rise > 4) {
                continue;
            }
            String stairs = stairMaterial(plan);
            String base = BlockStates.bareTypeOr(grid.at(x, y - 1, z), "minecraft:stone_bricks");
            List<PlanStep> steps = new ArrayList<>(plan.steps());
            boolean fits = true;
            for (int k = 0; k < rise; k++) {
                int sx = x + out.dx() * (k + 1);
                int sz = z + out.dz() * (k + 1);
                int top = y - 1 - k;
                if (sx < 0 || sz < 0 || sx >= plan.sizeX() || sz >= plan.sizeZ() || top < 0) {
                    fits = false;
                    break;
                }
                if (top > groundY) {
                    steps.add(PlanStep.of(PlanStep.Kind.BOX,
                            PlanBox.of(sx, groundY, sz, sx, top - 1, sz), base));
                }
                steps.add(PlanStep.of(PlanStep.Kind.BOX, PlanBox.at(sx, top, sz),
                        BlockStates.stairs(stairs, out.opposite(), false)));
            }
            if (!fits) {
                // Pas la place de marches dans le volume (façade sur sa limite) : on n'en sort pas.
                // On enterre la fondation — l'ancre monte au niveau du seuil, le bâtiment se pose
                // d'autant plus bas, comme la hutte de test (« une fondation s'enfonce dans le
                // sol »). Rien ne bouge dans la grille ; seule la pose change.
                BuildingPlan sunk = new BuildingPlan(plan.id(), plan.name(), plan.sizeX(), plan.sizeY(),
                        plan.sizeZ(), plan.front(), plan.anchorX(), y, plan.anchorZ(), plan.steps(),
                        plan.kind());
                List<String> after = codes(sunk);
                if (sunk.validate().isEmpty() && !after.contains("ENTRANCE_STEP")
                        && !worse(s.report().blockingCodes(), after)) {
                    changes.add("Seuil de la porte " + x + "/" + y + "/" + z + " à " + rise
                            + " bloc(s) au-dessus du terrain, sans place pour des marches dans le "
                            + "volume : fondation enterrée — l'ancre passe de y=" + plan.anchorY()
                            + " à y=" + y + ", le bâtiment se pose " + rise + " bloc(s) plus bas et "
                            + "l'on entre de plain-pied.");
                    return sunk;
                }
                continue;
            }
            BuildingPlan withSteps = copy(plan, steps);
            List<String> before = s.report().blockingCodes();
            List<String> after = codes(withSteps);
            if (!after.contains("ENTRANCE_STEP") && !worse(before, after)) {
                changes.add("Seuil de la porte " + x + "/" + y + "/" + z + " à " + rise
                        + " bloc(s) au-dessus du terrain (y=" + groundY + ") : " + rise
                        + " marche(s) de " + BlockState.type(stairs) + " ajoutée(s) devant elle, dans le "
                        + "volume, tournées vers la porte.");
                return withSteps;
            }
        }
        return sunkFloor(plan, s.report().blockingCodes(), changes);
    }

    /**
     * Une ouverture SANS porte (un {@code clear} dans le mur, l'« entrée sans porte » d'une
     * boutique) au-dessus du terrain : il n'y a pas de bloc de porte pour orienter des marches,
     * mais la fondation peut s'enterrer de la même façon — l'ancre monte d'un ou deux blocs, le
     * plancher affleure le terrain (Boulangerie de Central, #655 : plancher en y=0, ancre en y=0,
     * « on n'entre qu'en sautant »). La grille ne change pas ; seule la pose change.
     */
    private static BuildingPlan sunkFloor(BuildingPlan plan, List<String> before, List<String> changes) {
        for (int rise = 1; rise <= 2; rise++) {
            int anchorY = plan.anchorY() + rise;
            if (anchorY >= plan.sizeY()) {
                break;
            }
            BuildingPlan sunk = new BuildingPlan(plan.id(), plan.name(), plan.sizeX(), plan.sizeY(),
                    plan.sizeZ(), plan.front(), plan.anchorX(), anchorY, plan.anchorZ(), plan.steps(),
                    plan.kind());
            if (sunk.validate().isPresent()) {
                continue;
            }
            List<String> after = codes(sunk);
            if (!after.contains("ENTRANCE_STEP") && !worse(before, after)) {
                changes.add("Entrée à " + rise + " bloc(s) au-dessus du terrain : fondation enterrée — "
                        + "l'ancre passe de y=" + plan.anchorY() + " à y=" + anchorY + ", le plancher "
                        + "affleure le terrain et l'on entre de plain-pied.");
                return sunk;
            }
        }
        return plan;
    }

    // ---- 4. Les lanternes flottantes (#288) ----------------------------------------------------

    /** Hauteur maximale d'une chaîne de suspension ajoutée par le moteur. */
    static final int MAX_CHAIN = 8;

    /**
     * Suspend à une chaîne les lanternes de « light » qui flottent, quand un plafond se trouve au
     * plus {@value #MAX_CHAIN} cellules au-dessus, à travers de l'air seulement. La lanterne reste
     * à sa place et devient suspendue : c'est le geste d'un bâtisseur, et la seule correction qui
     * ne déplace pas la lumière décidée par le plan. Sans plafond atteignable, rien n'est inventé :
     * le refus reste, avec la cellule.
     */
    static BuildingPlan lanterns(BuildingPlan plan, List<String> changes) {
        Snapshot s = snapshot(plan);
        if (s == null || !s.report().blockingCodes().contains("UNSUPPORTED_BLOCK")) {
            return plan;
        }
        AuditGrid grid = AuditGrid.of(s.expansion().blueprint());
        List<PlanStep> steps = new ArrayList<>(plan.steps());
        List<String> hung = new ArrayList<>();
        for (Stability.Problem p : Stability.unsupported(grid)) {
            int rank = s.expansion().provenance().stepAt(p.x(), p.y(), p.z());
            if (rank < 1 || plan.steps().get(rank - 1).kind() != PlanStep.Kind.LIGHT
                    || !BlockState.type(p.state()).endsWith("lantern")) {
                continue;
            }
            int top = -1;
            for (int y = p.y() + 1; y <= p.y() + MAX_CHAIN + 1 && y < plan.sizeY(); y++) {
                String at = grid.at(p.x(), y, p.z());
                if (at != null) {
                    top = Stability.holdsFromAbove(at) ? y : -1;
                    break;
                }
            }
            if (top <= p.y() + 1) {
                continue;
            }
            steps.add(PlanStep.of(PlanStep.Kind.BOX, PlanBox.of(p.x(), p.y() + 1, p.z(), p.x(), top - 1,
                    p.z()), "minecraft:iron_chain[axis=y,waterlogged=false]"));
            hung.add(p.x() + "/" + p.y() + "/" + p.z() + " (" + (top - 1 - p.y()) + " maillon(s))");
        }
        if (hung.isEmpty()) {
            return plan;
        }
        BuildingPlan chained = copy(plan, steps);
        if (worse(s.report().blockingCodes(), codes(chained))) {
            return plan;
        }
        changes.add("Lanternes flottantes suspendues à une chaîne jusqu'au plafond : "
                + String.join(", ", hung) + ".");
        return chained;
    }

    /** Lanternes ajoutées au plus par réparation : au-delà, c'est l'éclairage entier à repenser. */
    static final int MAX_ADDED_LIGHTS = 4;

    private static final java.util.regex.Pattern DARK_CELL = java.util.regex.Pattern.compile(
            "la cellule (\\d+)/(\\d+)/(\\d+) est accessible");

    /**
     * Éclaire une zone sombre mesurée par l'audit (#747) : une torche murale à hauteur de tête contre le
     * mur voisin de la cellule nommée, ou une lanterne suspendue sous un plafond proche.
     * Essai réel du 23/10/2026 : nef puis pied de tour refusés « zone non éclairée » à chaque correction
     * de l'IA, sans progrès. Rien n'est ajouté sans mur ni plafond qui porte, et chaque source n'est gardée
     * que si la zone sombre change et qu'aucun refus nouveau n'apparaît.
     */
    static BuildingPlan darkAreas(BuildingPlan plan, List<String> changes) {
        BuildingPlan current = plan;
        List<String> lit = new ArrayList<>();
        for (int n = 0; n < MAX_ADDED_LIGHTS; n++) {
            Snapshot s = snapshot(current);
            if (s == null) {
                break;
            }
            var dark = s.report().blocking().stream().filter(f -> f.code().equals("DARK_AREA")).findFirst();
            if (dark.isEmpty()) {
                break;
            }
            var m = DARK_CELL.matcher(dark.get().message());
            if (!m.find()) {
                break;
            }
            int x = Integer.parseInt(m.group(1));
            int y = Integer.parseInt(m.group(2));
            int z = Integer.parseInt(m.group(3));
            AuditGrid grid = AuditGrid.of(s.expansion().blueprint());
            List<PlanStep> steps = new ArrayList<>(current.steps());
            String where = light(grid, current, x, y, z, steps);
            if (where == null) {
                break;
            }
            BuildingPlan candidate = copy(current, steps);
            Snapshot after = snapshot(candidate);
            boolean moved = after != null && after.report().blocking().stream()
                    .noneMatch(f -> f.code().equals("DARK_AREA") && f.message().contains("la cellule " + x + "/" + y
                            + "/" + z + " "));
            if (!moved || worse(s.report().blockingCodes(), codes(candidate))) {
                break;
            }
            lit.add(where + " (pour " + x + "/" + y + "/" + z + ")");
            current = candidate;
        }
        if (!lit.isEmpty()) {
            changes.add("Zones sombres éclairées : " + String.join(", ", lit) + ".");
        }
        return current;
    }

    /**
     * La source ajoutée pour la cellule sombre {@code x/y/z} (où l'on se tient) : une torche murale à
     * hauteur de tête contre un mur plein voisin, sinon une lanterne suspendue sous un plafond proche
     * (deux blocs de hauteur libre gardés). Décrite, ou {@code null} si aucune ne tient.
     */
    private static String light(AuditGrid grid, BuildingPlan plan, int x, int y, int z, List<PlanStep> steps) {
        // Au plus près de la cellule (deux cellules autour, de ses pieds à un bloc au-dessus de sa tête) :
        // une case vide adossée à un mur plein. Un seuil de porte n'a pas de place pour une source.
        for (int r = 0; r <= 2; r++) {
            for (int sy = y + 1; sy <= y + 2 && sy < plan.sizeY(); sy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        int sx = x + dx;
                        int sz = z + dz;
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r || sx < 0 || sz < 0 || sx >= plan.sizeX()
                                || sz >= plan.sizeZ() || grid.at(sx, sy, sz) != null) {
                            continue;
                        }
                        for (BlockStates.Dir d : BlockStates.Dir.values()) {
                            if (Stability.holdsFromBelow(grid.at(sx + d.dx(), sy, sz + d.dz()))) {
                                steps.add(PlanStep.of(PlanStep.Kind.BOX, PlanBox.at(sx, sy, sz),
                                        BlockStates.wallTorch("minecraft:wall_torch", d.opposite())));
                                return "torche murale en " + sx + "/" + sy + "/" + sz;
                            }
                        }
                    }
                }
            }
        }
        for (int up = y + 1; up < plan.sizeY() && up <= y + 7; up++) {
            String at = grid.at(x, up, z);
            if (at != null) {
                if (!Stability.holdsFromAbove(at) || up < y + 3) {
                    return null;
                }
                steps.add(PlanStep.of(PlanStep.Kind.LIGHT, PlanBox.at(x, up - 1, z), "minecraft:lantern"));
                return "lanterne suspendue en " + x + "/" + (up - 1) + "/" + z;
            }
        }
        return null;
    }

    /**
     * #747 : un « clear » placé APRÈS des portes, fenêtres, lumières, lits ou échelles les efface. Le
     * moteur le remonte juste avant la première instruction d'architecture qu'il efface — seulement si
     * rien d'autre que de l'architecture ne les sépare : les volumes qu'il vidait sont alors
     * exactement les mêmes, seules les ouvertures et les lumières survivent. Jugé par l'audit complet.
     */
    static BuildingPlan destructiveClears(BuildingPlan plan, List<String> changes) {
        Snapshot s = snapshot(plan);
        if (s == null || s.expansion().provenance().erasures().isEmpty()) {
            return plan;
        }
        List<PlanStep> steps = new ArrayList<>(plan.steps());
        List<String> moved = new ArrayList<>();
        Map<Integer, Integer> firstErased = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
        for (BuildingPlanExpander.Erasure e : s.expansion().provenance().erasures()) {
            if (architecture(plan.steps().get(e.erasedRank() - 1).kind())) {
                firstErased.merge(e.clearRank(), e.erasedRank(), Math::min);
            }
        }
        // Du dernier « clear » au premier : déplacer l'un ne décale pas les rangs des précédents.
        for (var entry : firstErased.entrySet()) {
            int clear = entry.getKey() - 1;
            int target = entry.getValue() - 1;
            boolean onlyArchitecture = true;
            for (int i = target; i < clear; i++) {
                onlyArchitecture &= architecture(steps.get(i).kind());
            }
            if (!onlyArchitecture) {
                continue;
            }
            PlanStep step = steps.remove(clear);
            steps.add(target, step);
            moved.add("instruction " + (clear + 1) + " (clear) remontée avant l'instruction " + (target + 1));
        }
        if (moved.isEmpty()) {
            return plan;
        }
        BuildingPlan reordered = copy(plan, steps);
        if (worse(s.report().blockingCodes(), codes(reordered))) {
            return plan;
        }
        changes.add("« clear » destructifs replacés AVANT les portes, fenêtres et lumières qu'ils effaçaient : "
                + String.join(" ; ", moved) + ".");
        return reordered;
    }

    private static boolean architecture(PlanStep.Kind kind) {
        return kind == PlanStep.Kind.DOOR || kind == PlanStep.Kind.WINDOW || kind == PlanStep.Kind.LIGHT
                || kind == PlanStep.Kind.BED || kind == PlanStep.Kind.LADDER;
    }

    /** L'escalier du plan, pour que les marches d'entrée gardent son style. */
    private static String stairMaterial(BuildingPlan plan) {
        for (PlanStep step : plan.steps()) {
            String type = BlockStates.bareType(step.state());
            if (type.endsWith("_stairs")) {
                return type;
            }
        }
        return "minecraft:stone_brick_stairs";
    }

    // ---- 4. La circulation ----------------------------------------------------------------------

    private static BuildingPlan circulation(BuildingPlan plan, List<String> changes) {
        BuildingPlan current = plan;
        for (int round = 0; round < 3; round++) {
            Snapshot s = snapshot(current);
            if (s == null) {
                return current;
            }
            List<String> codes = s.report().blockingCodes();
            if (codes.contains("LEVEL_UNREACHABLE") && !s.report().staircases().isEmpty()) {
                List<PlanStep> steps = new ArrayList<>(current.steps());
                for (Staircase stair : s.report().staircases()) {
                    steps.add(step(stair));
                    changes.add("Ajout d'un " + stair.describe() + ", trémie et palier calculés "
                            + "par le moteur.");
                }
                BuildingPlan withStairs = copy(current, steps);
                if (worse(codes, codes(withStairs))) {
                    return current;
                }
                current = cleanup(withStairs, plan.steps().size(), changes);
                continue;
            }
            if (codes.contains("LEVEL_CLUTTERED")) {
                Optional<BuildingPlan> lighter = declutter(current, s, changes);
                if (lighter.isPresent()) {
                    current = lighter.get();
                    continue;
                }
            }
            return current;
        }
        return current;
    }

    private record Snapshot(BuildingPlanExpander.Result expansion, ArchitecturalAudit.Report report) {
    }

    private static Snapshot snapshot(BuildingPlan plan) {
        BuildingPlanExpander.Result r = BuildingPlanExpander.expand(plan);
        if (!r.ok()) {
            return null;
        }
        return new Snapshot(r, ArchitecturalAudit.audit(r.blueprint(), plan.front(), plan.rules(),
                r.provenance(), plan.anchorY()));
    }

    private static PlanStep step(Staircase stair) {
        Map<String, Integer> numbers = new HashMap<>();
        numbers.put("floor", stair.floor());
        if (stair.width() > 1) {
            numbers.put("width", stair.width());
        }
        return new PlanStep(PlanStep.Kind.STAIRCASE, PlanBox.at(stair.x(), stair.y(), stair.z()),
                stair.material(), stair.dir().value(), null, numbers, "");
    }

    private static BuildingPlan copy(BuildingPlan plan, List<PlanStep> steps) {
        return new BuildingPlan(plan.id(), plan.name(), plan.sizeX(), plan.sizeY(), plan.sizeZ(),
                plan.front(), plan.anchorX(), plan.anchorY(), plan.anchorZ(), steps, plan.kind());
    }

    /**
     * Retire les escaliers, échelles et trémies du plan d'origine qui ne mènent plus nulle part.
     *
     * <p>Une fois l'escalier du moteur en place, la volée trop courte du modèle n'est plus qu'un
     * escalier flottant, et sa trémie un trou dans le plancher où l'on tombe. Chaque retrait est
     * rejoué : il n'est gardé que s'il ne fait apparaître aucun refus.</p>
     */
    private static BuildingPlan cleanup(BuildingPlan plan, int originalSteps, List<String> changes) {
        Set<Integer> upperSlabs = new LinkedHashSet<>();
        Snapshot s = snapshot(plan);
        if (s == null) {
            return plan;
        }
        AuditGrid grid = AuditGrid.of(s.expansion().blueprint());
        List<Circulation.Level> levels = Circulation.detect(grid);
        for (int i = 1; i < levels.size(); i++) {
            upperSlabs.add(levels.get(i).slabY());
        }
        List<String> baseline = s.report().blockingCodes();
        BuildingPlan current = plan;
        for (int i = originalSteps - 1; i >= 0; i--) {
            PlanStep step = current.steps().get(i);
            boolean candidate = step.kind() == PlanStep.Kind.STAIR_RUN
                    || step.kind() == PlanStep.Kind.LADDER
                    || step.kind() == PlanStep.Kind.CLEAR && step.box().minY() == step.box().maxY()
                            && upperSlabs.contains(step.box().minY());
            if (!candidate) {
                continue;
            }
            List<PlanStep> steps = new ArrayList<>(current.steps());
            steps.remove(i);
            BuildingPlan without = copy(current, steps);
            List<String> after = codes(without);
            if (after.contains("PLAN_INVALID") || !baseline.containsAll(after)) {
                continue;
            }
            changes.add("Retrait de l'" + BuildingPlanExpander.describeStep(current, i + 1)
                    + (step.kind() == PlanStep.Kind.CLEAR
                            ? " : ce trou dans le plancher ne servait plus aucun escalier."
                            : " : il ne menait nulle part."));
            current = without;
            baseline = after;
        }
        return current;
    }

    /**
     * Creuse une box de meubles qui couvre un plancher : elle devient une rangée le long de son
     * pourtour ({@code walls}), même bloc, même zone. C'est la disposition d'une bibliothèque —
     * des étagères contre les murs, une salle au centre — et la seule correction de mobilier assez
     * mécanique pour ne pas trahir le brief.
     */
    private static Optional<BuildingPlan> declutter(BuildingPlan plan, Snapshot s,
                                                    List<String> changes) {
        AuditGrid grid = AuditGrid.of(s.expansion().blueprint());
        ArchitecturalAudit.Provenance provenance = s.expansion().provenance();
        List<String> before = s.report().blockingCodes();
        for (Circulation.Level level : Circulation.detect(grid)) {
            if (!s.report().clutteredLevels().contains(level.slabY())) {
                continue;
            }
            Map<Integer, Integer> byStep = new HashMap<>();
            for (long k : level.slab()) {
                int x = AuditGrid.keyX(k);
                int z = AuditGrid.keyZ(k);
                if (!grid.passable(x, level.standY(), z)) {
                    byStep.merge(provenance.stepAt(x, level.standY(), z), 1, Integer::sum);
                }
            }
            for (var e : byStep.entrySet()) {
                int rank = e.getKey();
                if (rank < 1 || e.getValue() < level.area() * DECLUTTER_SHARE) {
                    continue;
                }
                PlanStep step = plan.steps().get(rank - 1);
                if (step.kind() != PlanStep.Kind.BOX || !BlockSemantics.furnishing(step.state())
                        || step.box().sizeX() < 3 || step.box().sizeZ() < 3) {
                    continue;
                }
                List<PlanStep> steps = new ArrayList<>(plan.steps());
                steps.set(rank - 1, new PlanStep(PlanStep.Kind.WALLS, step.box(), step.state(),
                        step.axis(), step.facade(), step.numbers(), step.fill()));
                BuildingPlan lighter = copy(plan, steps);
                List<String> after = codes(lighter);
                if (after.size() < before.size() && before.containsAll(after)) {
                    changes.add(BuildingPlanExpander.describeStep(plan, rank).substring(0, 1)
                            .toUpperCase(java.util.Locale.ROOT)
                            + BuildingPlanExpander.describeStep(plan, rank).substring(1)
                            + " couvrait " + e.getValue() + " cellules sur " + level.area()
                            + " du plancher y=" + level.slabY() + " : transformée en rangée le "
                            + "long de son pourtour (op: walls), le centre de la salle est libre.");
                    return Optional.of(lighter);
                }
            }
        }
        return Optional.empty();
    }
}
