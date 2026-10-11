package com.lodygames.rpgquest.building.plan;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BlueprintBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** Le résultat : un plan de blocs, ou le motif pour lequel il n'a pas pu être produit. */
    public record Result(Blueprint blueprint, String error, int solidCount, long touchedCells) {

        public boolean ok() {
            return blueprint != null;
        }

        public static Result failure(String error) {
            return new Result(null, error, 0, 0);
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

        Map<Long, String> cells = new LinkedHashMap<>();
        for (PlanStep step : plan.steps()) {
            apply(step, cells, plan);
        }

        List<BlueprintBlock> blocks = new ArrayList<>(cells.size());
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

        Blueprint blueprint = new Blueprint(plan.sizeX(), plan.sizeY(), plan.sizeZ(), blocks);
        var broken = blueprint.validate();
        if (broken.isPresent()) {
            // Ne devrait pas arriver — la carte interdit les doublons et la validation du plan
            // interdit les débordements. Si cela arrive quand même, on refuse plutôt que d'écrire.
            return Result.failure("Plan de blocs incohérent : " + broken.get());
        }
        return new Result(blueprint, "", blocks.size(), touched);
    }

    private static void apply(PlanStep step, Map<Long, String> cells, BuildingPlan plan) {
        PlanBox box = step.box();
        switch (step.kind()) {
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
            case DOOR -> door(step, cells, plan);
            case WINDOW -> window(step, cells, plan);
            case LIGHT -> light(step, cells);
            case STAIR_RUN -> stairRun(step, cells);
            case LADDER -> ladder(step, cells, plan);
            case PILLARS -> pillars(step, cells);
        }
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
    private static void door(PlanStep step, Map<Long, String> cells, BuildingPlan plan) {
        Facade facade = step.facade() == null ? Facade.FRONT : step.facade();
        int offset = step.number("offset", facade.span(plan.front(), plan.sizeX(), plan.sizeZ()) / 2);
        int y = step.number("y", 1);
        var at = facade.cell(plan.front(), plan.sizeX(), plan.sizeY(), plan.sizeZ(), offset, y);
        if (at.isEmpty()) {
            return;
        }
        int[] c = at.get();
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
    }

    /**
     * Une fenêtre : percement <strong>et</strong> vitrage, répétable.
     *
     * <p>Les connexions du panneau sont calculées depuis l'axe du mur — un panneau dont les quatre
     * connexions valent {@code false} apparaît comme un poteau isolé, le défaut visuel classique.
     * {@code count} et {@code spacing} permettent d'aligner une série de fenêtres en une seule
     * instruction.</p>
     */
    private static void window(PlanStep step, Map<Long, String> cells, BuildingPlan plan) {
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
        String state = BlockStates.pane(glass, wallAlongX);

        for (int n = 0; n < count; n++) {
            int base = offset + n * spacing;
            for (int w = 0; w < width; w++) {
                for (int h = 0; h < height; h++) {
                    int along = base + w;
                    if (along < 0 || along >= span) {
                        continue;
                    }
                    var at = facade.cell(plan.front(), plan.sizeX(), plan.sizeY(), plan.sizeZ(),
                            along, y + h);
                    at.ifPresent(c -> cells.put(key(c[0], c[1], c[2]), state));
                }
            }
        }
    }

    /**
     * Des sources de lumière réparties sur une grille.
     *
     * <p>{@code hanging} est décidé par le moteur selon qu'il y a un support au-dessus : une
     * lanterne suspendue sans plafond flotte, une lanterne posée sans sol tombe. Le modèle n'a pas
     * à y penser.</p>
     */
    private static void light(PlanStep step, Map<Long, String> cells) {
        PlanBox box = step.box();
        int spacing = Math.max(1, step.number("spacing", 5));
        String material = BlockStates.bareTypeOr(step.state(), "minecraft:lantern");
        boolean lantern = material.endsWith("lantern");
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    if ((x - box.minX()) % spacing != 0 || (z - box.minZ()) % spacing != 0) {
                        continue;
                    }
                    String above = cells.get(key(x, y + 1, z));
                    boolean hanging = above != null && !BlockSemantics.passable(above);
                    cells.put(key(x, y, z),
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
    private static void stairRun(PlanStep step, Map<Long, String> cells) {
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
    private static void ladder(PlanStep step, Map<Long, String> cells, BuildingPlan plan) {
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
    private static void pillars(PlanStep step, Map<Long, String> cells) {
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
    private static void gable(PlanStep step, Map<Long, String> cells) {
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
