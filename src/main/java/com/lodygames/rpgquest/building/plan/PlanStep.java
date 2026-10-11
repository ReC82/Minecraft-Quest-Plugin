package com.lodygames.rpgquest.building.plan;

import java.util.Locale;
import java.util.Optional;

/**
 * Une instruction de plan de bâtiment (issue #94).
 *
 * <h2>Pourquoi des primitives, et pas une liste de blocs</h2>
 *
 * <p>Un bâtiment de 19 × 12 × 15 compte <strong>3 420 cellules</strong>. Demander à un modèle de
 * langue de les énumérer une par une serait coûteux, lent, et surtout fragile : une seule
 * coordonnée de travers dans un mur de 200 blocs produit un trou que personne ne relit. Les
 * primitives déplacent la géométrie du côté <em>déterministe</em> — le modèle dit « un mur creux
 * de là à là, en pierre », et {@link BuildingPlanExpander} le remplit exactement, toujours de la
 * même façon.</p>
 *
 * <p>C'est aussi ce que demande l'issue #94 : « éviter une sortie bloc par bloc gigantesque et
 * l'exécution de code arbitraire fourni par l'IA ». Un plan n'est pas du code : c'est une liste
 * finie d'instructions dont le vocabulaire est fermé, et tout mot inconnu est refusé.</p>
 *
 * @param kind  l'instruction
 * @param box   la zone visée, en coordonnées locales ; bornes incluses, ordre indifférent
 * @param state l'état de bloc Minecraft ({@code minecraft:oak_planks},
 *              {@code minecraft:oak_stairs[facing=north]}), vide pour {@link Kind#CLEAR}
 * @param axis  axe d'une pente de toit : {@code x} ou {@code z}, vide sinon
 */
public record PlanStep(Kind kind, PlanBox box, String state, String axis) {

    /** Le vocabulaire fermé des instructions. Tout autre mot est refusé au parsing. */
    public enum Kind {
        /** Remplit toute la zone. */
        BOX,
        /** Ne pose que l'enveloppe de la zone : les six faces, intérieur laissé vide. */
        SHELL,
        /** Ne pose que les quatre murs verticaux — ni sol, ni plafond. */
        WALLS,
        /** Vide la zone. Sert à percer portes, fenêtres et pièces dans un volume déjà posé. */
        CLEAR,
        /**
         * Toit à deux pentes. Les marches sont orientées automatiquement vers l'extérieur de la
         * pente : c'est exactement le genre de détail qu'un modèle de langue se trompe à faire à la
         * main, et qu'un générateur déterministe réussit toujours.
         */
        GABLE;

        public static Optional<Kind> of(String raw) {
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                return Optional.empty();
            }
        }
    }

    public PlanStep {
        if (kind == null) {
            throw new IllegalArgumentException("instruction obligatoire");
        }
        if (box == null) {
            throw new IllegalArgumentException("zone obligatoire");
        }
        state = state == null ? "" : state.trim();
        axis = axis == null ? "" : axis.trim().toLowerCase(Locale.ROOT);
        if (kind != Kind.CLEAR && state.isEmpty()) {
            throw new IllegalArgumentException(
                    "l'instruction « " + kind + " » exige un état de bloc");
        }
    }

    /** Instruction sans axe — la forme courante. */
    public static PlanStep of(Kind kind, PlanBox box, String state) {
        return new PlanStep(kind, box, state, "");
    }
}
