package com.lodygames.rpgquest.npc;

import java.util.Optional;

/**
 * Décide si renommer un PNJ Citizens peut se faire <strong>sans changer son apparence</strong>.
 *
 * <p><strong>Le fait technique qui commande tout.</strong> Un PNJ Citizens de type {@code PLAYER}
 * sans skin explicite tire son apparence de son <em>nom</em> : le renommer la change donc par
 * effet de bord. Et l'artefact {@code citizensapi} — la seule dépendance Citizens de ce projet —
 * <strong>n'expose aucune API de skin</strong> : ni {@code SkinTrait}, ni clé de skin dans l'enum
 * public {@code NPC.Metadata} (vérifié sur {@code citizensapi:2.0.43}, 0 classe contenant
 * « Skin »). Il est donc impossible de <em>lire</em> le skin en place, et donc de le restituer —
 * sauf si c'est RPGQuest qui l'a posé et enregistré.</p>
 *
 * <p>D'où ces quatre issues. Rattacher l'ancien nom écraserait un éventuel skin explicite par une
 * texture dérivée d'un pseudo : un remplacement imprévisible. Depuis l'issue #325, un PNJ joueur
 * sans source connue reçoit donc l'<strong>apparence par défaut</strong>
 * ({@link NpcIdentityService#DEFAULT_SKIN_URL}), qui est enregistrée et donc stable aux renommages
 * suivants. Seul un type indéterminable reste refusé.</p>
 *
 * <p>Classe pure : aucun type Citizens, Bukkit ni I/O — entièrement testable.</p>
 */
public final class SkinPreservationPlanner {

    private SkinPreservationPlanner() {
    }

    public enum Decision {
        /** Pas de skin en jeu pour ce type de PNJ : le nom n'influence rien. */
        RENAME_FREELY,
        /** Source connue : la réappliquer d'abord, puis renommer. */
        REAPPLY_RECORDED_THEN_RENAME,
        /**
         * Type joueur et skin inconnu : poser l'apparence par défaut (issue #325), l'enregistrer,
         * puis renommer. Avant #325 c'était un refus — qui bloquait le propriétaire sans lui
         * proposer d'issue simple.
         */
        APPLY_DEFAULT_THEN_RENAME,
        /** Type indéterminable : on ne sait pas si l'apparence est en jeu. */
        REFUSE_TYPE_UNKNOWN
    }

    /** @param code code d'erreur de l'action agent, ou {@code null} si le renommage peut se faire. */
    public record Plan(Decision decision, String code, String message) {

        public boolean allowsRename() {
            return decision != Decision.REFUSE_TYPE_UNKNOWN;
        }
    }

    /**
     * @param isPlayerType      vrai/faux si le type du PNJ est connu, vide s'il est indéterminable
     * @param hasRecordedSource RPGQuest connaît-il la source d'apparence de ce PNJ
     */
    public static Plan plan(Optional<Boolean> isPlayerType, boolean hasRecordedSource) {
        if (isPlayerType == null || isPlayerType.isEmpty()) {
            return new Plan(Decision.REFUSE_TYPE_UNKNOWN, "NPC_TYPE_UNKNOWN",
                    "Type du PNJ Citizens indéterminable : impossible de savoir si son apparence "
                            + "dépend de son nom. Renommage refusé pour ne pas risquer de changer son skin.");
        }
        if (!isPlayerType.get()) {
            return new Plan(Decision.RENAME_FREELY, null,
                    "Ce PNJ n'est pas de type joueur : il n'a pas de skin, son apparence ne dépend "
                            + "donc pas de son nom.");
        }
        if (!hasRecordedSource) {
            return new Plan(Decision.APPLY_DEFAULT_THEN_RENAME, null,
                    "Apparence par défaut appliquée : ce PNJ n'avait pas d'apparence enregistrée.");
        }
        return new Plan(Decision.REAPPLY_RECORDED_THEN_RENAME, null,
                "Skin conservé : la source enregistrée est réappliquée avant le renommage.");
    }
}
