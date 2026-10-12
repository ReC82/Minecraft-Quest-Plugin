package com.lodygames.rpgquest.building.plan;

import java.util.Locale;
import java.util.Map;
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
 * @param facade façade visée par une primitive sémantique, ou {@code null} pour un placement en
 *               coordonnées absolues (issue #94, lot C)
 * @param numbers paramètres nommés des primitives sémantiques — {@code offset}, {@code y},
 *               {@code width}, {@code height}, {@code spacing}, {@code count}, {@code steps},
 *               {@code floor}.
 *               Portés dans une table plutôt qu'en champs dédiés : chaque primitive n'en lit que
 *               ce qui la concerne, et en ajouter une n'oblige pas à élargir ce record
 * @param fill  matériau de remplissage secondaire — les pignons pleins d'une toiture. Distinct de
 *              {@code state}, qui porte l'escalier des pentes : les deux ne sont pas le même bloc,
 *              et un escalier employé comme remplissage laisse des creux visibles
 */
public record PlanStep(Kind kind, PlanBox box, String state, String axis, Facade facade,
                       Map<String, Integer> numbers, String fill) {

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
         * Toit à deux pentes, <strong>pignons fermés</strong> (corrigé par le lot B).
         *
         * <p>La version du lot A ne posait qu'une rangée par rang : la couverture était correcte vue
         * du dessus, mais les deux triangles verticaux aux extrémités restaient béants — 59 cellules
         * vides sur 95 par face sur la V1. Elle remplit désormais les pignons et les contremarches.</p>
         */
        GABLE,

        // ---- Primitives sémantiques (issue #94, lot B) ------------------------------------------
        // Elles ont toutes le même objet : retirer au modèle les calculs qu'il rate. Il décrit
        // l'intention, le moteur calcule les états de bloc, les moitiés, les connexions et les
        // orientations.

        /**
         * Une vraie porte Minecraft : deux moitiés cohérentes, orientation vers l'extérieur,
         * charnière, et l'espace dégagé derrière pour qu'elle soit réellement traversable.
         */
        DOOR,
        /** Une fenêtre : percement du mur ET vitrage, connexions calculées selon l'axe du mur. */
        WINDOW,
        /** Des sources de lumière réparties, avec le bon support et la bonne propriété. */
        LIGHT,
        /**
         * Une volée d'escalier praticable : une marche par pas dans le sens de la montée, et la
         * <strong>trémie percée dans le plancher</strong> au débouché. C'est le défaut de la V1 :
         * son percement était un niveau au-dessus du plancher, qui restait donc plein.
         */
        STAIR_RUN,
        /** Une échelle adossée à un mur, orientée correctement (elle regarde à l'opposé du mur). */
        LADDER,
        /**
         * Des pilastres réguliers : du relief pour <strong>une</strong> instruction. C'est la
         * réponse au vrai goulot de la V1 — le modèle n'avait utilisé que 21 instructions sur 80
         * autorisées, faute de pouvoir dire « un pilastre tous les 4 blocs ».
         */
        PILLARS,
        /**
         * Un escalier ENTRE DEUX NIVEAUX (issue #282) : le modèle donne le pied, la direction et le
         * plancher à atteindre ; le moteur calcule le nombre de marches, la trémie, le départ et
         * le palier, et réserve ce volume contre le mobilier posé ensuite. Voir {@link Staircase}.
         */
        STAIRCASE,
        /**
         * Un lit (#288) : DEUX cellules, pied et tête, même couleur, même orientation. Le modèle
         * donne le pied et la direction de la tête ; le moteur pose la paire. Une « box » d'un bloc
         * de lit posait deux pieds côte à côte, que la première mise à jour physique détruisait —
         * le lit absent de la Maison du bibliothécaire.
         */
        BED;

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
        numbers = Map.copyOf(numbers == null ? Map.of() : numbers);
        fill = fill == null ? "" : fill.trim();
        // CLEAR n'a pas de bloc, et les primitives sémantiques ont un matériau par défaut : exiger
        // « block » pour une lanterne obligerait le modèle à se souvenir de son identifiant.
        if (kind != Kind.CLEAR && !semanticKind(kind) && state.isEmpty()) {
            throw new IllegalArgumentException(
                    "l'instruction « " + kind + " » exige un état de bloc");
        }
    }

    /** Cette sorte d'instruction est-elle une primitive sémantique (matériau par défaut) ? */
    public static boolean semanticKind(Kind kind) {
        return switch (kind) {
            case DOOR, WINDOW, LIGHT, STAIR_RUN, LADDER, PILLARS, STAIRCASE, BED -> true;
            default -> false;
        };
    }

    /** Instruction sans axe ni paramètre — la forme courante des volumes. */
    public static PlanStep of(Kind kind, PlanBox box, String state) {
        return new PlanStep(kind, box, state, "", null, Map.of(), "");
    }

    /** Instruction de volume avec axe, sans paramètre nommé. */
    public static PlanStep of(Kind kind, PlanBox box, String state, String axis) {
        return new PlanStep(kind, box, state, axis, null, Map.of(), "");
    }

    /** Un paramètre nommé, ou son défaut si la primitive ne l'exige pas. */
    public int number(String key, int fallback) {
        Integer value = numbers.get(key);
        return value == null ? fallback : value;
    }

    public boolean hasNumber(String key) {
        return numbers.containsKey(key);
    }

    /**
     * Cette instruction est-elle une primitive sémantique ?
     *
     * <p>Les primitives n'ont pas de zone au sens des volumes : elles se repèrent par façade et
     * décalage, ou par un point. Leur zone sert seulement de borne de sécurité.</p>
     */
    public boolean semantic() {
        return semanticKind(kind);
    }
}
