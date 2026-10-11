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
            apply(step, cells);
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

    private static void apply(PlanStep step, Map<Long, String> cells) {
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
        for (int i = 0; i < span; i++) {
            // Rang depuis le bord le plus proche : 0 au bord, puis 1, 2… vers le faîte.
            int fromEdge = Math.min(i, span - 1 - i);
            int y = box.minY() + Math.min(fromEdge, box.sizeY() - 1);
            boolean lowSide = i < half;
            String facing = alongX ? (lowSide ? "south" : "north") : (lowSide ? "east" : "west");
            String state = BlockState.with(step.state(), "facing", facing);
            if (alongX) {
                int z = box.minZ() + i;
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    cells.put(key(x, y, z), state);
                }
            } else {
                int x = box.minX() + i;
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    cells.put(key(x, y, z), state);
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
