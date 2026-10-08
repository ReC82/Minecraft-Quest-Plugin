package com.lodygames.rpgquest.panel.npc;

import java.util.List;
import java.util.Optional;

/**
 * Conséquences d'une suppression de PNJ, calculées <strong>avant</strong> toute mutation
 * (issue #226, sur le modèle de #194).
 *
 * <p><strong>Pourquoi un plan séparé de son exécution.</strong> Un PNJ n'existe pas en un seul
 * endroit : une définition logique, une liaison, une entité Citizens, un dialogue, et des quêtes qui
 * le citent. Un bouton « Supprimer » unique devrait deviner lesquels l'opérateur veut faire
 * disparaître — et devinerait mal. Le plan énumère donc les couches réellement présentes, puis les
 * <em>opérations</em> réellement sûres, chacune avec ses propres blocages. L'écran montre ce plan,
 * et l'exécution n'applique que ce qui y figure.</p>
 *
 * <p><strong>Jamais de cascade.</strong> Aucune opération n'en déclenche une autre sans que
 * l'opérateur l'ait choisie, et <strong>aucune</strong> ne supprime un dialogue : un dialogue peut
 * être partagé, et de toute façon son auteur n'est pas forcément celui qui supprime le PNJ.</p>
 *
 * @param npcId           identifiant logique visé, normalisé
 * @param label           nom humain, pour que la confirmation parle d'un PNJ et pas d'un fichier
 * @param surveyAvailable un relevé {@code npc.list} existe-t-il ? Sinon <strong>rien</strong> n'est
 *                        proposé : « aucune dépendance » et « on n'a jamais demandé » ne sont pas la
 *                        même phrase, et devant un bouton de suppression la confusion est grave
 * @param npc             la fiche réelle, vide si le relevé ne connaît pas cet identifiant
 * @param layers          les couches, présentes ou non — on affiche aussi les absentes, parce que
 *                        « pas de Citizens lié » est une information utile avant de supprimer
 * @param operations      les opérations proposées, dans l'ordre du moins au plus destructeur
 * @param notes           ce qu'il faut savoir sans que cela bloque
 */
public record NpcDeletionPlan(String npcId, String label, boolean surveyAvailable,
                              Optional<NpcView> npc, List<Layer> layers,
                              List<Operation> operations, List<String> notes) {

    /** Les quatre opérations du ticket, du moins au plus destructeur. */
    public enum Op {
        /** A — la définition logique seule. Ne touche pas à Citizens. */
        DEFINITION_ONLY("npc.definition.delete"),
        /** B — retirer la liaison, laisser vivre le PNJ Citizens. */
        UNLINK_CITIZENS("npc.citizens.unlink"),
        /** C — détruire l'entité Citizens, garder la définition. */
        DELETE_CITIZENS("npc.citizens.delete"),
        /** D — définition + liaison + entité Citizens. Jamais le dialogue. */
        FULL_CLEANUP(null);

        private final String actionType;

        Op(String actionType) {
            this.actionType = actionType;
        }

        /** Action agent correspondante, {@code null} pour {@link #FULL_CLEANUP} qui en enchaîne deux. */
        public String actionType() {
            return actionType;
        }

        /** Cette opération touche-t-elle au PNJ Citizens ? Si oui, la confirmation l'exige. */
        public boolean touchesCitizens() {
            return this == UNLINK_CITIZENS || this == DELETE_CITIZENS || this == FULL_CLEANUP;
        }

        public static Optional<Op> of(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            for (Op op : values()) {
                if (op.name().equalsIgnoreCase(raw.trim())) {
                    return Optional.of(op);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Une couche d'existence du PNJ.
     *
     * @param present la couche existe-t-elle réellement
     * @param detail  ce qu'on en sait, en français
     */
    public record Layer(String name, boolean present, String detail) {
    }

    /**
     * Une opération proposée.
     *
     * @param blockers raisons de la refuser. Non vide ⇒ aucun formulaire de confirmation n'est rendu
     * @param effects  ce qui sera fait, et ce qui ne le sera pas — énuméré avant, pas découvert après
     * @param reversible l'opération peut-elle être défaite sans perte ? Sert à doser l'avertissement
     */
    public record Operation(Op op, String label, String description, List<String> blockers,
                            List<String> effects, boolean reversible) {

        public Operation {
            blockers = List.copyOf(blockers == null ? List.of() : blockers);
            effects = List.copyOf(effects == null ? List.of() : effects);
        }

        public boolean available() {
            return blockers.isEmpty();
        }
    }

    public NpcDeletionPlan {
        npcId = npcId == null ? "" : npcId;
        label = label == null ? "" : label;
        layers = List.copyOf(layers == null ? List.of() : layers);
        operations = List.copyOf(operations == null ? List.of() : operations);
        notes = List.copyOf(notes == null ? List.of() : notes);
    }

    public Optional<Operation> operation(Op op) {
        return operations.stream().filter(o -> o.op() == op).findFirst();
    }

    /** Y a-t-il au moins une opération exécutable ? Sinon la page n'offre que des explications. */
    public boolean anyAvailable() {
        return operations.stream().anyMatch(Operation::available);
    }

    /** Identifiant Citizens lié, {@code null} s'il n'y en a pas — la confirmation l'exige. */
    public Integer citizensNumericId() {
        return npc.map(NpcView::citizensNumericId).orElse(null);
    }
}
