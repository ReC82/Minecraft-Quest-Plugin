package com.lodygames.rpgquest.building.plan;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BlueprintBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Transforme un {@link BuildingPlan} en {@link Blueprint}, de façon déterministe (issue #94).
 *
 * <h2>Déterminisme, et pourquoi il compte ici</h2>
 *
 * <p>Le même plan produit toujours exactement les mêmes blocs, dans le même ordre. Ce n'est pas une
 * élégance : l'empreinte du {@code .schem} sert de version au bâtiment posé (issue #234). Si
 * l'expansion variait d'une exécution à l'autre, chaque régénération ferait croire que la
 * définition a changé, et le panel annoncerait « bâtiment périmé » sans raison.</p>
 *
 * <p>Les cellules sont donc accumulées dans une {@link LinkedHashMap} puis <strong>réémises dans
 * un ordre fixe</strong> (y, puis z, puis x) — l'ordre d'écriture des instructions n'influence que
 * le <em>contenu</em> d'une cellule, jamais la position d'un bloc dans la liste.</p>
 *
 * <h2>Les instructions se recouvrent, dans l'ordre</h2>
 *
 * <p>Une instruction postérieure écrase une antérieure sur la même cellule, et {@code CLEAR}
 * efface. C'est ce qui rend le vocabulaire utilisable : on pose un volume plein, puis on y creuse
 * les pièces, puis on perce les fenêtres. Sans recouvrement, il faudrait décrire chaque mur en
 * quatre morceaux pour laisser la place d'une porte — et c'est précisément le genre de découpage
 * qu'un modèle de langue rate.</p>
 *
 * <p>C'est aussi ce qui garantit que le {@link Blueprint} produit est valide : il ne peut pas
 * contenir deux blocs à la même position, puisqu'une position est une clé.</p>
 */
public final class BuildingPlanExpander {

    private BuildingPlanExpander() {
    }

    /**
     * Le résultat : un plan de blocs, ou le motif pour lequel il n'a pas pu être produit.
     *
     * @param origins pour chaque cellule posée, le rang (à partir de 1) de l'instruction qui l'a
     *                écrite en dernier (issue #282). C'est ce qui permet à l'audit de dire « cette
     *                bibliothèque vient de l'instruction 17 » au lieu de « une cellule est
     *                pleine » — la différence entre un refus actionnable et un refus qu'on
     *                contourne par une autre variante du même bâtiment
     */
    public record Result(Blueprint blueprint, String error, int solidCount, long touchedCells,
                         Map<Long, Integer> origins, BuildingPlan plan) {

        public Result {
            origins = origins == null ? Map.of() : Map.copyOf(origins);
        }

        public Result(Blueprint blueprint, String error, int solidCount, long touchedCells) {
            this(blueprint, error, solidCount, touchedCells, Map.of(), null);
        }

        public boolean ok() {
            return blueprint != null;
        }

        public static Result failure(String error) {
            return new Result(null, error, 0, 0);
        }

        /** La provenance des cellules, dans la forme que l'audit consomme. */
        public ArchitecturalAudit.Provenance provenance() {
            if (plan == null) {
                return ArchitecturalAudit.Provenance.NONE;
            }
            return new ArchitecturalAudit.Provenance() {
                @Override
                public int stepAt(int x, int y, int z) {
                    if (x < 0 || y < 0 || z < 0) {
                        return 0;
                    }
                    return origins.getOrDefault(key(x, y, z), 0);
                }

                @Override
                public String describe(int rank) {
                    return describeStep(plan, rank);
                }
            };
        }
    }

    /**
     * Une instruction, désignée comme le modèle l'a écrite : rang, nature, matériau, zone.
     * « instruction 17 (box minecraft:bookshelf de [2, 7, 2] à [16, 7, 16]) ».
     */
    public static String describeStep(BuildingPlan plan, int rank) {
        if (plan == null || rank < 1 || rank > plan.steps().size()) {
            return "instruction inconnue";
        }
        PlanStep step = plan.steps().get(rank - 1);
        PlanBox b = step.box();
        StringBuilder sb = new StringBuilder("instruction ").append(rank).append(" (")
                .append(step.kind().name().toLowerCase(java.util.Locale.ROOT));
        if (!step.state().isEmpty()) {
            sb.append(' ').append(BlockStates.bareType(step.state()));
        }
        if (step.facade() != null) {
            sb.append(" façade ").append(step.facade());
        } else if (b.cells() == 1) {
            sb.append(" en [").append(b.minX()).append(", ").append(b.minY()).append(", ")
                    .append(b.minZ()).append(']');
        } else {
            sb.append(" de [").append(b.minX()).append(", ").append(b.minY()).append(", ")
                    .append(b.minZ()).append("] à [").append(b.maxX()).append(", ")
                    .append(b.maxY()).append(", ").append(b.maxZ()).append(']');
        }
        return sb.append(')').toString();
    }

    /**
     * Les cellules en cours d'expansion, avec leur provenance.
     *
     * <p>Une seule écriture possible, {@link #put}, et un seul effacement, {@link #remove} : la
     * provenance ne peut donc pas diverger du contenu.</p>
     */
    static final class Cells {
        private final Map<Long, String> states = new LinkedHashMap<>();
        private final Map<Long, Integer> origins = new HashMap<>();
        private int current;

        void put(long key, String state) {
            states.put(key, state);
            origins.put(key, current);
        }

        void remove(long key) {
            states.remove(key);
            origins.remove(key);
        }

        String get(long key) {
            return states.get(key);
        }
    }

    /**
     * Expanse le plan.
     *
     * <p>Le plan est <strong>revalidé ici</strong>, même s'il l'a déjà été par celui qui l'a lu.
     * Cette méthode est la dernière barrière avant l'écriture d'un fichier : s'y fier à un
     * contrôle fait ailleurs reviendrait à accepter qu'un futur appelant l'oublie.</p>
     */
    public static Result expand(BuildingPlan plan) {
        if (plan == null) {
            return Result.failure("Aucun plan.");
        }
        var invalid = plan.validate();
        if (invalid.isPresent()) {
            return Result.failure(invalid.get());
        }

        // Coût borné AVANT d'allouer : la somme des cellules touchées peut dépasser le volume,
        // puisque les instructions se recouvrent. On refuse plutôt que de faire ramer le serveur.
        long touched = 0;
        for (PlanStep step : plan.steps()) {
            touched += step.box().cells();
            if (touched > BuildingPlan.MAX_CELLS * 4) {
                return Result.failure("Plan trop coûteux : les instructions couvrent plus de "
                        + (BuildingPlan.MAX_CELLS * 4) + " cellules cumulées. "
                        + "Décrire le bâtiment avec moins de recouvrements.");
            }
        }

        Cells cells = new Cells();
        // #282 : les escaliers entre niveaux passent EN DERNIER. Leur volume — marches, hauteur
        // libre, trémie, départ, palier — est ainsi réservé : un meuble posé plus loin dans le
        // plan ne peut plus le reboucher. C'est exactement ce qui avait fermé l'étage du 3e plan
        // de la Bibliothèque du Hub, où une box de bibliothèques suivait l'escalier.
        for (int i = 0; i < plan.steps().size(); i++) {
            PlanStep step = plan.steps().get(i);
            if (step.kind() != PlanStep.Kind.STAIRCASE) {
                cells.current = i + 1;
                Optional<String> refused = apply(step, cells, plan);
                if (refused.isPresent()) {
                    return Result.failure("Instruction " + (i + 1) + " (" + step.kind() + ") : "
                            + refused.get());
                }
            }
        }
        for (int i = 0; i < plan.steps().size(); i++) {
            PlanStep step = plan.steps().get(i);
            if (step.kind() == PlanStep.Kind.STAIRCASE) {
                cells.current = i + 1;
                Optional<String> refused = staircase(step, cells);
                if (refused.isPresent()) {
                    return Result.failure("Instruction " + (i + 1) + " (STAIRCASE) : "
                            + refused.get());
                }
            }
        }

        settleLanterns(cells, plan);

        List<BlueprintBlock> blocks = new ArrayList<>(cells.states.size());
        for (int y = 0; y < plan.sizeY(); y++) {
            for (int z = 0; z < plan.sizeZ(); z++) {
                for (int x = 0; x < plan.sizeX(); x++) {
                    String state = cells.get(key(x, y, z));
                    if (state != null) {
                        blocks.add(new BlueprintBlock(x, y, z, state));
                    }
                }
            }
        }
        if (blocks.isEmpty()) {
            return Result.failure("Le plan ne pose aucun bloc : toutes ses instructions "
                    + "s'annulent ou sont des « clear ».");
        }

        Optional<String> unknown = unknownStates(blocks, cells, plan);
        if (unknown.isPresent()) {
            return Result.failure(unknown.get());
        }

        Blueprint blueprint = new Blueprint(plan.sizeX(), plan.sizeY(), plan.sizeZ(), blocks);
        var broken = blueprint.validate();
        if (broken.isPresent()) {
            // Ne devrait pas arriver — la carte interdit les doublons et la validation du plan
            // interdit les débordements. Si cela arrive quand même, on refuse plutôt que d'écrire.
            return Result.failure("Plan de blocs incohérent : " + broken.get());
        }
        return new Result(blueprint, "", blocks.size(), touched, cells.origins, plan);
    }

    /**
     * Dernière barrière avant le schematic (#288–#292) : chaque état <strong>réellement produit</strong>
     * — y compris ceux que les primitives calculent — doit exister dans le registre de la version
     * servie. Le plan a déjà été contrôlé type par type ; ce contrôle-ci attrape un état calculé
     * invalide (une porte posée avec un bloc qui n'est pas une porte), avec la cellule et
     * l'instruction qui l'ont produit, au lieu du {@code NoMatchException} de l'écriture.
     */
    private static Optional<String> unknownStates(List<BlueprintBlock> blocks, Cells cells,
                                                  BuildingPlan plan) {
        Map<String, BlueprintBlock> first = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (BlueprintBlock b : blocks) {
            first.putIfAbsent(b.state(), b);
            counts.merge(b.state(), 1, Integer::sum);
        }
        List<String> problems = new ArrayList<>();
        for (var e : first.entrySet()) {
            Optional<String> refusal = BlockCatalog.vanilla().refusal(e.getKey());
            if (refusal.isPresent()) {
                BlueprintBlock b = e.getValue();
                int rank = cells.origins.getOrDefault(key(b.x(), b.y(), b.z()), 0);
                problems.add("état « " + e.getKey() + " » produit par " + describeStep(plan, rank)
                        + " en " + b.x() + "/" + b.y() + "/" + b.z() + " (" + counts.get(e.getKey())
                        + " cellule(s)) : " + refusal.get() + ".");
            }
        }
        if (problems.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("États de bloc refusés par le registre Minecraft " + BlockCatalog.VERSION
                + " — rien n'a été écrit :\n- " + String.join("\n- ", problems));
    }

    private static Optional<String> apply(PlanStep step, Cells cells, BuildingPlan plan) {
        PlanBox box = step.box();
        switch (step.kind()) {
            case DOOR -> {
                return door(step, cells, plan);
            }
            case BED -> Bed.of(step).ifPresent(bed -> {
                cells.put(key(bed.x(), bed.y(), bed.z()), bed.foot());
                cells.put(key(bed.headX(), bed.y(), bed.headZ()), bed.head());
            });
            case WINDOW -> {
                return window(step, cells, plan);
            }
            case BOX -> forEach(box, (x, y, z) -> cells.put(key(x, y, z), step.state()));
            case SHELL -> forEach(box, (x, y, z) -> {
                if (box.onShell(x, y, z)) {
                    cells.put(key(x, y, z), step.state());
                }
            });
            case WALLS -> forEach(box, (x, y, z) -> {
                if (box.onWall(x, y, z)) {
                    cells.put(key(x, y, z), step.state());
                }
            });
            case CLEAR -> forEach(box, (x, y, z) -> cells.remove(key(x, y, z)));
            case GABLE -> gable(step, cells);
            case LIGHT -> light(step, cells);
            case STAIR_RUN -> stairRun(step, cells);
            case LADDER -> ladder(step, cells, plan);
            case PILLARS -> pillars(step, cells);
            // Appliqué à part, après toutes les autres instructions : voir expand().
            case STAIRCASE -> { }
            default -> { }
        }
        return Optional.empty();
    }

    /**
     * Un escalier entre deux niveaux (issue #282), appliqué après tout le reste.
     *
     * <p>Deux garde-fous, vérifiés AVANT d'écrire la moindre cellule :</p>
     * <ul>
     *   <li><strong>l'enveloppe est intouchable</strong> — mur extérieur, porte, vitrage. Une
     *       correction d'accès ne doit jamais détruire la façade : on refuse en nommant la
     *       cellule, plutôt que de percer en silence ;</li>
     *   <li><strong>le départ et le palier doivent porter</strong> — un sol sous le départ, le
     *       plancher sous le palier. Sinon la volée mène dans le vide, et c'est le plan qui se
     *       trompe de plancher.</li>
     * </ul>
     */
    private static Optional<String> staircase(PlanStep step, Cells cells) {
        Optional<Staircase> parsed = Staircase.of(step);
        if (parsed.isEmpty()) {
            return Optional.of("direction de montée inconnue.");
        }
        Staircase stair = parsed.get();
        int[] bounds = materialBounds(cells);
        for (Staircase.Placement p : stair.placements()) {
            boolean envelope = bounds != null && (p.x() <= bounds[0] || p.x() >= bounds[3]
                    || p.z() <= bounds[2] || p.z() >= bounds[5]);
            String there = cells.get(key(p.x(), p.y(), p.z()));
            if (envelope && there != null) {
                return Optional.of("l'escalier toucherait le mur extérieur en " + p.x() + "/"
                        + p.y() + "/" + p.z() + " (" + BlockStates.bareType(there) + "). Place-le "
                        + "entièrement à l'intérieur, à au moins une cellule des murs — départ et "
                        + "palier compris.");
            }
            if (there != null && (BlockSemantics.glazing(there) || there.contains("_door["))) {
                return Optional.of("l'escalier détruirait " + BlockStates.bareType(there) + " en "
                        + p.x() + "/" + p.y() + "/" + p.z() + ". Une porte ou une fenêtre n'est "
                        + "jamais sacrifiée à un escalier : déplace-le.");
            }
        }
        for (int[] s : stair.supports()) {
            String below = cells.get(key(s[0], s[1], s[2]));
            if (!BlockSemantics.support(below)) {
                boolean landing = s[1] == stair.floor();
                return Optional.of(landing
                        ? "le palier d'arrivée " + s[0] + "/" + (s[1] + 1) + "/" + s[2]
                                + " n'a pas de plancher sous lui en y=" + s[1] + ". « floor » doit "
                                + "être le y d'un plancher qui existe devant la dernière marche."
                        : "le départ " + s[0] + "/" + (s[1] + 1) + "/" + s[2] + " n'a pas de sol "
                                + "sous lui en y=" + s[1] + ". « from » est la première marche, "
                                + "posée SUR le sol du niveau de départ.");
            }
        }
        for (Staircase.Placement p : stair.placements()) {
            if (p.clears()) {
                cells.remove(key(p.x(), p.y(), p.z()));
            } else {
                cells.put(key(p.x(), p.y(), p.z()), p.state());
            }
        }
        return Optional.empty();
    }

    /**
     * Les lanternes de « light » choisissent leur appui sur la grille FINALE (#288).
     *
     * <p>« hanging » était décidé au moment de l'instruction. Or le toit, le plafond ou la table
     * viennent souvent après : sur la Maison de Lily, les lanternes de y=4 étaient posées avant le
     * toit, donc « posées » sur le vide, et se détachaient à la première mise à jour — ramassables.
     * Le moteur tranche maintenant une fois tout posé : suspendue s'il y a de quoi la tenir
     * au-dessus, posée s'il y a un appui dessous. Sans l'un ni l'autre, l'état reste tel quel et
     * l'audit de stabilité la refuse en la nommant — jamais retirée en silence.</p>
     */
    private static void settleLanterns(Cells cells, BuildingPlan plan) {
        for (var e : new ArrayList<>(cells.states.entrySet())) {
            int rank = cells.origins.getOrDefault(e.getKey(), 0);
            // Les lanternes de « light », et toute lanterne dont le plan n'a écrit que le type : le
            // modèle dit « une lanterne », le moteur décide comment elle tient. Un « hanging »
            // écrit explicitement est respecté — et jugé par l'audit.
            boolean computed = rank >= 1 && plan.steps().get(rank - 1).kind() == PlanStep.Kind.LIGHT
                    || !e.getValue().contains("[");
            if (!computed || !BlockCatalog.vanilla().hasProperty(e.getValue(), "hanging")) {
                continue;
            }
            long k = e.getKey();
            int x = (int) (k >>> 40);
            int y = (int) ((k >>> 20) & 0xFFFFF);
            int z = (int) (k & 0xFFFFF);
            boolean hang = Stability.holdsFromAbove(cells.get(key(x, y + 1, z)));
            boolean stand = Stability.holdsFromBelow(y > 0 ? cells.get(key(x, y - 1, z)) : null);
            if (hang || stand) {
                cells.states.put(k, BlockStates.lantern(BlockStates.bareType(e.getValue()), hang));
            }
        }
    }

    /** Les bornes de la matière déjà posée : {minX, minY, minZ, maxX, maxY, maxZ}, ou null. */
    private static int[] materialBounds(Cells cells) {
        if (cells.states.isEmpty()) {
            return null;
        }
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
            Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (long k : cells.states.keySet()) {
            int x = (int) (k >>> 40);
            int y = (int) ((k >>> 20) & 0xFFFFF);
            int z = (int) (k & 0xFFFFF);
            b[0] = Math.min(b[0], x);
            b[1] = Math.min(b[1], y);
            b[2] = Math.min(b[2], z);
            b[3] = Math.max(b[3], x);
            b[4] = Math.max(b[4], y);
            b[5] = Math.max(b[5], z);
        }
        return b;
    }

    // ---- Primitives sémantiques (issue #94, lot B) -------------------------------------------

    /**
     * Une vraie porte : deux moitiés cohérentes, et l'espace dégagé derrière.
     *
     * <p>Le modèle dit « une porte sur la façade avant, troisième cellule ». Le moteur calcule la
     * coordonnée, l'orientation, les deux moitiés, la charnière, et <strong>vide la cellule
     * intérieure</strong> pour que la porte soit réellement traversable. C'est exactement la série
     * de calculs que la V1 avait ratée.</p>
     */
    private static Optional<String> door(PlanStep step, Cells cells, BuildingPlan plan) {
        Facade facade = step.facade() == null ? Facade.FRONT : step.facade();
        int offset = step.number("offset", facade.span(plan.front(), plan.sizeX(), plan.sizeZ()) / 2);
        int y = step.number("y", 1);
        if (y < 0 || y + 1 >= plan.sizeY()) {
            return Optional.of("la porte en y=" + y + " sort du volume (hauteur " + plan.sizeY()
                    + ") : ses deux moitiés occupent y et y+1.");
        }
        Target target = target(step, plan, facade, offset, offset, y, y + 1, "la porte");
        if (target.refusal() != null) {
            return Optional.of(target.refusal());
        }
        int[] c = target.cell(offset, y);
        String wood = BlockStates.bareTypeOr(step.state(), "minecraft:oak_door");
        BlockStates.Dir outward = facade.direction(plan.front());
        boolean right = step.number("hinge", 0) != 0;

        cells.put(key(c[0], c[1], c[2]), BlockStates.door(wood, outward, false, right));
        cells.put(key(c[0], c[1] + 1, c[2]), BlockStates.door(wood, outward, true, right));

        // Dégager derrière, sur les deux hauteurs : une porte derrière un mur n'est pas une entrée.
        int[] in = facade.inwardStep(plan.front());
        for (int h = 0; h <= 1; h++) {
            cells.remove(key(c[0] + in[0], c[1] + h, c[2] + in[2]));
        }
        return Optional.empty();
    }

    /**
     * Le mur où percer une ouverture (#288–#292, Maison de Lily).
     *
     * <h2>Le défaut</h2>
     *
     * <p>Le plan déclarait un volume de 19 × 19 et construisait ses murs de [3,1,3] à [15,4,15]. La
     * façade était résolue sur la <em>limite du volume</em> : la porte s'est posée en z=18 et les
     * vitrages en x=0/18, z=0/18 — détachés, dans le jardin — et la maison est restée fermée.</p>
     *
     * <h2>La règle</h2>
     *
     * <p>Une ouverture se perce dans un <strong>corps de bâtiment</strong> : l'emprise d'une
     * instruction {@code walls}, {@code shell} ou {@code box} d'au moins 3 × 3, qui couvre les
     * hauteurs de l'ouverture (à défaut, tout corps). {@code offset} reste la coordonnée le long de
     * la façade, en coordonnées du plan (x pour une façade nord/sud, z pour est/ouest) ; seule la
     * <em>profondeur</em> est résolue — le mur extérieur du corps dont l'emprise contient
     * l'ouverture, angles exclus. Si plusieurs corps la contiennent, c'est le plus extérieur de ce
     * côté : la façade, par définition. Pour viser un autre mur, {@code wall: <coordonnée>} le
     * désigne explicitement ; un {@code wall} qui ne correspond à aucun corps est refusé.</p>
     *
     * <p>Un plan sans aucun corps (un portail, un mur seul) garde l'ancienne lecture : la limite du
     * volume. L'audit juge ensuite la grille, et refuse toute ouverture qui ne s'ouvre sur rien.</p>
     */
    private static Target target(PlanStep step, BuildingPlan plan, Facade facade, int alongFrom,
                                 int alongTo, int yFrom, int yTo, String what) {
        BlockStates.Dir dir = facade.direction(plan.front());
        boolean alongX = !dir.alongX();
        List<PlanBox> all = new ArrayList<>();
        for (PlanStep other : plan.steps()) {
            PlanBox b = other.box();
            boolean volume = other.kind() == PlanStep.Kind.WALLS || other.kind() == PlanStep.Kind.SHELL
                    || other.kind() == PlanStep.Kind.BOX;
            if (volume && b.maxX() - b.minX() >= 2 && b.maxZ() - b.minZ() >= 2) {
                all.add(b);
            }
        }
        if (all.isEmpty()) {
            int plane = switch (dir) {
                case NORTH, WEST -> 0;
                case SOUTH -> plan.sizeZ() - 1;
                case EAST -> plan.sizeX() - 1;
            };
            return new Target(plane, alongX, true, null);
        }
        List<PlanBox> covering = all.stream()
                .filter(b -> b.minY() <= yFrom && b.maxY() >= yTo).toList();
        List<PlanBox> pool = covering.isEmpty() ? all : covering;
        List<PlanBox> holding = new ArrayList<>();
        for (PlanBox b : pool) {
            int min = alongX ? b.minX() : b.minZ();
            int max = alongX ? b.maxX() : b.maxZ();
            if (alongFrom > min && alongTo < max) {
                holding.add(b);
            }
        }
        String axis = alongX ? "x" : "z";
        String cellsLabel = axis + "=" + alongFrom + (alongTo != alongFrom ? "…" + alongTo : "");
        if (holding.isEmpty()) {
            StringBuilder sb = new StringBuilder(what + " (" + cellsLabel + ", façade "
                    + facade + ") ne tombe dans aucun mur construit. Murs de cette façade : ");
            boolean first = true;
            for (PlanBox b : pool) {
                int min = alongX ? b.minX() : b.minZ();
                int max = alongX ? b.maxX() : b.maxZ();
                sb.append(first ? "" : " ; ").append(faceLabel(dir, b)).append(", ").append(axis)
                        .append(" de ").append(min + 1).append(" à ").append(max - 1)
                        .append(" (angles exclus)");
                first = false;
            }
            return Target.refused(sb.append(". « offset » est la coordonnée ").append(axis)
                    .append(" du plan : choisis-la dans cet intervalle.").toString());
        }
        if (step.hasNumber("wall")) {
            int wall = step.number("wall", 0);
            for (PlanBox b : holding) {
                if (face(dir, b) == wall) {
                    return new Target(wall, alongX, false, null);
                }
            }
            return Target.refused(what + " vise « wall: " + wall + " », mais aucun mur construit "
                    + "de la façade " + facade + " ne s'y trouve à " + cellsLabel + " (murs : "
                    + String.join(" ; ", holding.stream().map(b -> faceLabel(dir, b)).toList()) + ").");
        }
        int plane = face(dir, holding.get(0));
        for (PlanBox b : holding) {
            int f = face(dir, b);
            plane = (dir == BlockStates.Dir.SOUTH || dir == BlockStates.Dir.EAST)
                    ? Math.max(plane, f) : Math.min(plane, f);
        }
        return new Target(plane, alongX, false, null);
    }

    /** Le plan du mur d'un corps, côté {@code dir}. */
    private static int face(BlockStates.Dir dir, PlanBox b) {
        return switch (dir) {
            case NORTH -> b.minZ();
            case SOUTH -> b.maxZ();
            case WEST -> b.minX();
            case EAST -> b.maxX();
        };
    }

    private static String faceLabel(BlockStates.Dir dir, PlanBox b) {
        return (dir.alongX() ? "x=" : "z=") + face(dir, b);
    }

    /** Le mur résolu : sa coordonnée, et comment y placer une cellule ; ou le motif du refus. */
    private record Target(int plane, boolean alongX, boolean legacy, String refusal) {

        static Target refused(String why) {
            return new Target(0, false, false, why);
        }

        int[] cell(int along, int y) {
            return alongX ? new int[] {along, y, plane} : new int[] {plane, y, along};
        }
    }

    /**
     * Une fenêtre : percement <strong>et</strong> vitrage, répétable.
     *
     * <p>Les connexions du panneau sont calculées depuis l'axe du mur — un panneau dont les quatre
     * connexions valent {@code false} apparaît comme un poteau isolé, le défaut visuel classique.
     * {@code count} et {@code spacing} permettent d'aligner une série de fenêtres en une seule
     * instruction.</p>
     */
    private static Optional<String> window(PlanStep step, Cells cells, BuildingPlan plan) {
        Facade facade = step.facade() == null ? Facade.FRONT : step.facade();
        int span = facade.span(plan.front(), plan.sizeX(), plan.sizeZ());
        int offset = step.number("offset", 1);
        int y = step.number("y", 2);
        int width = Math.max(1, step.number("width", 1));
        int height = Math.max(1, step.number("height", 1));
        int count = Math.max(1, step.number("count", 1));
        int spacing = Math.max(width, step.number("spacing", width + 1));
        String glass = BlockStates.bareTypeOr(step.state(), "minecraft:glass_pane");
        BlockStates.Dir outward = facade.direction(plan.front());
        // Une façade qui regarde le nord ou le sud court selon X : son mur est « selon X ».
        boolean wallAlongX = !outward.alongX();
        // Un bloc de verre plein n'a pas de connexions : ne lui en inventer aucune.
        String state = BlockCatalog.vanilla().hasProperty(glass, "east")
                ? BlockStates.pane(glass, wallAlongX) : glass;
        if (y < 0 || y + height - 1 >= plan.sizeY()) {
            return Optional.of("la fenêtre de y=" + y + " à y=" + (y + height - 1)
                    + " sort du volume (hauteur " + plan.sizeY() + ").");
        }
        int last = offset + (count - 1) * spacing + width - 1;
        Target target = target(step, plan, facade, offset, last, y, y + height - 1,
                count > 1 ? "la série de " + count + " fenêtres" : "la fenêtre");
        if (target.refusal() != null) {
            return Optional.of(target.refusal());
        }

        for (int n = 0; n < count; n++) {
            int base = offset + n * spacing;
            for (int w = 0; w < width; w++) {
                for (int h = 0; h < height; h++) {
                    int along = base + w;
                    if (target.legacy() && (along < 0 || along >= span)) {
                        continue;
                    }
                    int[] c = target.cell(along, y + h);
                    cells.put(key(c[0], c[1], c[2]), state);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Des sources de lumière réparties sur une grille.
     *
     * <p>{@code hanging} est décidé par le moteur selon qu'il y a un support au-dessus : une
     * lanterne suspendue sans plafond flotte, une lanterne posée sans sol tombe. Le modèle n'a pas
     * à y penser.</p>
     */
    private static void light(PlanStep step, Cells cells) {
        PlanBox box = step.box();
        int spacing = Math.max(1, step.number("spacing", 5));
        String material = BlockStates.bareTypeOr(step.state(), "minecraft:lantern");
        // Par le registre et non par le nom : « sea_lantern » finit par « lantern » mais ne se
        // suspend pas — lui écrire « hanging » produisait un état refusé à l'écriture.
        boolean lantern = BlockCatalog.vanilla().hasProperty(material, "hanging");
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    // #288 : l'espacement vaut aussi en hauteur. Une zone de y=2 à y=8 posait une
                    // colonne de sept lanternes empilées — chacune sur une lanterne, donc sur rien.
                    if ((x - box.minX()) % spacing != 0 || (z - box.minZ()) % spacing != 0
                            || (y - box.minY()) % spacing != 0) {
                        continue;
                    }
                    // #301 : une source de lumière ne PERCE jamais un bloc plein. Visée dans un
                    // plafond (Maison de Lily : « light » à y=5, la couche du plancher de l'étage),
                    // elle remplaçait un bloc du plafond — lanterne encastrée dans un trou, puis
                    // suspendue par une longue chaîne à travers les combles jusqu'au toit. Elle se
                    // pose désormais juste DESSOUS, suspendue au plafond resté fermé ; sans case
                    // libre dessous, rien n'est posé.
                    int ly = y;
                    String target = cells.get(key(x, y, z));
                    if (target != null && !BlockSemantics.passable(target)) {
                        String below = y > 0 ? cells.get(key(x, y - 1, z)) : "";
                        if (y == 0 || (below != null && !BlockSemantics.passable(below))) {
                            continue;
                        }
                        ly = y - 1;
                    }
                    String above = cells.get(key(x, ly + 1, z));
                    boolean hanging = above != null && !BlockSemantics.passable(above);
                    cells.put(key(x, ly, z),
                            lantern ? BlockStates.lantern(material, hanging) : material);
                }
            }
        }
    }

    /**
     * Une volée d'escalier praticable, <strong>trémie comprise</strong>.
     *
     * <p>Reprise de la technique de {@code TestWatchtowerBlueprint} : une marche par pas, montant
     * d'un bloc à chaque fois, {@code facing} dans le sens de la montée. Et surtout, le moteur
     * <strong>perce le plancher</strong> au-dessus de chaque marche et du palier.</p>
     *
     * <p>C'est précisément ce que la V1 avait raté : son percement était en {@code y=4} alors que
     * le plancher était en {@code y=3}, qui restait donc plein — étage inaccessible.</p>
     */
    private static void stairRun(PlanStep step, Cells cells) {
        PlanBox box = step.box();
        BlockStates.Dir dir = BlockStates.Dir.NORTH;
        for (BlockStates.Dir candidate : BlockStates.Dir.values()) {
            if (candidate.value().equals(step.axis())) {
                dir = candidate;
            }
        }
        int steps = Math.max(1, step.number("steps", 4));
        String material = BlockStates.bareTypeOr(step.state(), "minecraft:oak_stairs");
        int x = box.minX();
        int y = box.minY();
        int z = box.minZ();
        for (int i = 0; i < steps; i++) {
            int sx = x + dir.dx() * i;
            int sz = z + dir.dz() * i;
            int sy = y + i;
            cells.put(key(sx, sy, sz), BlockStates.stairs(material, dir, false));
            // Deux blocs de hauteur libre au-dessus de chaque marche, sinon on se cogne.
            cells.remove(key(sx, sy + 1, sz));
            cells.remove(key(sx, sy + 2, sz));
        }
        // Le palier : la cellule après la dernière marche, et sa hauteur libre.
        int lx = x + dir.dx() * steps;
        int lz = z + dir.dz() * steps;
        int ly = y + steps;
        cells.remove(key(lx, ly, lz));
        cells.remove(key(lx, ly + 1, lz));
    }

    /** Une échelle adossée : elle regarde à l'opposé du mur qui la porte. */
    private static void ladder(PlanStep step, Cells cells, BuildingPlan plan) {
        PlanBox box = step.box();
        Facade facade = step.facade() == null ? Facade.BACK : step.facade();
        BlockStates.Dir looks = facade.direction(plan.front()).opposite();
        String material = BlockStates.bareTypeOr(step.state(), "minecraft:ladder");
        String state = BlockStates.ladder(material, looks);
        for (int y = box.minY(); y <= box.maxY(); y++) {
            cells.put(key(box.minX(), y, box.minZ()), state);
        }
        // Le débouché en haut doit être libre, sinon l'échelle ne mène à rien.
        cells.remove(key(box.minX(), box.maxY() + 1, box.minZ()));
    }

    /**
     * Des pilastres réguliers sur le pourtour — du relief pour <strong>une</strong> instruction.
     *
     * <p>C'est la réponse au vrai goulot mesuré sur la V1 : le modèle n'avait utilisé que 21
     * instructions sur 80 autorisées, non par paresse, mais parce que rien ne lui permettait de
     * dire « un pilastre tous les quatre blocs ».</p>
     */
    private static void pillars(PlanStep step, Cells cells) {
        PlanBox box = step.box();
        int spacing = Math.max(2, step.number("spacing", 4));
        String material = BlockStates.bareTypeOr(step.state(), "minecraft:stone_bricks");
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    if (!box.onWall(x, y, z)) {
                        continue;
                    }
                    boolean onX = z == box.minZ() || z == box.maxZ();
                    int along = onX ? x - box.minX() : z - box.minZ();
                    boolean corner = (x == box.minX() || x == box.maxX())
                            && (z == box.minZ() || z == box.maxZ());
                    if (corner || along % spacing == 0) {
                        cells.put(key(x, y, z), material);
                    }
                }
            }
        }
    }

    /**
     * Toit à deux pentes.
     *
     * <p>La pente monte d'un bloc par rang depuis chaque bord vers le milieu, sur l'axe donné. Les
     * marches reçoivent {@code facing} vers le bas de leur versant — c'est ce qui les fait
     * descendre vers l'extérieur, et c'est le détail qu'un plan écrit à la main rate le plus
     * souvent. Un toit plus haut que la zone est simplement tronqué à la zone : on ne sort jamais
     * du volume annoncé.</p>
     */
    private static void gable(PlanStep step, Cells cells) {
        PlanBox box = step.box();
        boolean alongX = step.axis().equals("x");
        int span = alongX ? box.sizeZ() : box.sizeX();
        int half = (span + 1) / 2;
        // Le matériau des pignons. S'il n'est pas précisé, on retombe sur l'escalier lui-même :
        // l'enveloppe est alors géométriquement fermée, mais un plan soigné précise « fill » pour
        // un pignon vraiment plein — un escalier employé en remplissage laisse des creux visibles.
        String fillState = BlockStates.bareTypeOr(step.fill(),
                BlockStates.bareTypeOr(step.state(), "minecraft:stone_bricks"));
        for (int i = 0; i < span; i++) {
            int fromEdge = Math.min(i, span - 1 - i);
            int y = box.minY() + Math.min(fromEdge, box.sizeY() - 1);
            boolean lowSide = i < half;
            BlockStates.Dir facing = alongX
                    ? (lowSide ? BlockStates.Dir.SOUTH : BlockStates.Dir.NORTH)
                    : (lowSide ? BlockStates.Dir.EAST : BlockStates.Dir.WEST);
            String state = BlockStates.stairs(
                    BlockStates.bareTypeOr(step.state(), "minecraft:oak_stairs"), facing, false);
            if (alongX) {
                int z = box.minZ() + i;
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    cells.put(key(x, y, z), state);
                }
                // LE CORRECTIF DU LOT B : remplir les deux triangles de pignon, aux extrémités de
                // l'arête. Sans cela on voit droit dans les combles — 59 cellules vides sur 95 par
                // face sur la V1, soit 62 % d'ouverture.
                for (int yy = box.minY(); yy < y; yy++) {
                    cells.put(key(box.minX(), yy, z), fillState);
                    cells.put(key(box.maxX(), yy, z), fillState);
                }
            } else {
                int x = box.minX() + i;
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    cells.put(key(x, y, z), state);
                }
                for (int yy = box.minY(); yy < y; yy++) {
                    cells.put(key(x, yy, box.minZ()), fillState);
                    cells.put(key(x, yy, box.maxZ()), fillState);
                }
            }
        }
    }

    private interface CellAction {
        void at(int x, int y, int z);
    }

    private static void forEach(PlanBox box, CellAction action) {
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    action.at(x, y, z);
                }
            }
        }
    }

    /** Clé de cellule. Les coordonnées sont bornées à 64 par le plan : 20 bits chacune suffisent. */
    private static long key(int x, int y, int z) {
        return ((long) x << 40) | ((long) y << 20) | z;
    }
}
