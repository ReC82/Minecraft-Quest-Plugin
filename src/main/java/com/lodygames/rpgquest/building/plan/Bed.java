package com.lodygames.rpgquest.building.plan;

import java.util.Optional;

/**
 * Un lit : pied, tête, couleur et orientation cohérents (#288).
 *
 * <p>Dans Minecraft, un lit est deux blocs. Le {@code facing} des deux moitiés est la direction
 * <strong>du pied vers la tête</strong>, et {@code part} distingue {@code foot} de {@code head}. Une
 * moitié dont la paire manque, ou dont la paire regarde ailleurs, est détruite par la première mise
 * à jour physique — c'est ainsi qu'une « box » de lit disparaissait à la pose.</p>
 *
 * @param x        le pied
 * @param dir      la direction de la tête, vue depuis le pied
 * @param material le type de lit, coloré ({@code minecraft:pink_bed})
 */
record Bed(int x, int y, int z, BlockStates.Dir dir, String material) {

    /** La couleur par défaut du jeu, quand le plan n'en donne pas. */
    static final String DEFAULT_MATERIAL = "minecraft:red_bed";

    static Optional<Bed> of(PlanStep step) {
        BlockStates.Dir dir = Staircase.direction(step.axis());
        if (dir == null) {
            return Optional.empty();
        }
        PlanBox box = step.box();
        return Optional.of(new Bed(box.minX(), box.minY(), box.minZ(), dir,
                BlockStates.bareTypeOr(step.state(), DEFAULT_MATERIAL)));
    }

    int headX() {
        return x + dir.dx();
    }

    int headZ() {
        return z + dir.dz();
    }

    String foot() {
        return state("foot");
    }

    String head() {
        return state("head");
    }

    private String state(String part) {
        return material + "[facing=" + dir.value() + ",occupied=false,part=" + part + "]";
    }

    /** Le motif d'un lit impossible, avant toute grille. */
    Optional<String> refusal(int sizeX, int sizeY, int sizeZ) {
        if (!BlockState.type(material).endsWith("_bed")) {
            return Optional.of("« " + material + " » n'est pas un lit : « block » doit être un lit "
                    + "coloré, par exemple minecraft:pink_bed.");
        }
        if (headX() < 0 || headZ() < 0 || headX() >= sizeX || headZ() >= sizeZ) {
            return Optional.of("la tête du lit (" + headX() + "/" + y + "/" + headZ() + ") sort du "
                    + "volume : le lit occupe le pied « from » ET la cellule suivante vers « "
                    + dir.value() + " ».");
        }
        return Optional.empty();
    }
}
