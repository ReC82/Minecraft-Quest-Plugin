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
     * ne le sait pas.
     */
    public record Limits(int maxWidth, int maxHeight) {

        public static Limits none() {
            return new Limits(0, 0);
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
        List<String> changes = new ArrayList<>();
        List<String> before = codes(raw);
        BuildingPlan plan = bounds(raw, limits == null ? Limits.none() : limits, changes);
        if (plan.validate().isPresent()) {
            // Rien de sûr à faire : la faute n'est pas dans le catalogue.
            return new Outcome(null, List.of(), before, before);
        }
        plan = openings(plan, changes);
        plan = circulation(plan, changes);
        plan = entrance(plan, changes);
        List<String> after = codes(plan);
        if (changes.isEmpty() || worse(before, after)) {
            return new Outcome(null, List.of(), before, before);
        }
        return new Outcome(plan, changes, before, after);
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
        int sz = grow(plan.sizeZ(), needZ, limits.maxWidth());
        if (sx != plan.sizeX() || sy != plan.sizeY() || sz != plan.sizeZ()) {
            changes.add("Volume porté de " + plan.sizeX() + " × " + plan.sizeY() + " × "
                    + plan.sizeZ() + " à " + sx + " × " + sy + " × " + sz
                    + " pour contenir les instructions, dans les limites du projet.");
        }
        List<PlanStep> steps = new ArrayList<>();
        for (int i = 0; i < plan.steps().size(); i++) {
            PlanStep step = plan.steps().get(i);
            PlanBox b = step.box();
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

    /** Le rang de la porte ou fenêtre que l'expanseur refuse faute de mur, ou 0. */
    private static int failingOpening(BuildingPlanExpander.Result r) {
        if (r.ok()) {
            return 0;
        }
        var m = java.util.regex.Pattern.compile("^Instruction (\\d+) \\((DOOR|WINDOW)\\) : .*"
                + "ne tombe dans aucun mur construit", java.util.regex.Pattern.DOTALL).matcher(r.error());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    // ---- 3. L'entrée (#291) ---------------------------------------------------------------------

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
        return plan;
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
