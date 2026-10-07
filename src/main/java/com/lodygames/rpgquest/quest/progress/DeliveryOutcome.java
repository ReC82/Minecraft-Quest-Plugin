package com.lodygames.rpgquest.quest.progress;

import java.util.List;

/**
 * Résultat d'une tentative de remise d'objets à un PNJ (issue #123). Toujours renvoyé, y compris
 * quand rien n'a été remis : le dialogue doit pouvoir répondre précisément au joueur plutôt que de
 * rester muet.
 *
 * @param status  ce qui s'est réellement passé
 * @param lines   état de chaque objectif de remise de ce PNJ <strong>après</strong> l'opération,
 *                dans l'ordre de déclaration des objectifs ; {@link DeliveryLine#justNow()} porte
 *                ce qui vient d'être remis
 * @param allComplete  {@code true} si tous les objectifs de remise destinés à ce PNJ sont désormais
 *                     satisfaits — c'est ce que le dialogue regarde pour basculer vers son nœud de
 *                     fin, jamais une somme recalculée ailleurs
 */
public record DeliveryOutcome(Status status, List<DeliveryLine> lines, boolean allComplete) {

    public enum Status {
        /** Aucun objectif de remise actif pour ce PNJ (mauvais PNJ, ou aucune quête concernée). */
        NO_OBJECTIVE,
        /** Des objectifs existent mais tout est déjà remis : rien n'a été retiré. */
        ALREADY_COMPLETE,
        /** Le joueur ne possède aucun des matériaux encore nécessaires : rien n'a été retiré. */
        NOTHING_USEFUL,
        /** Au moins un objet a été réellement retiré et la progression a avancé d'autant. */
        DELIVERED,
        /**
         * Une remise de ce joueur est déjà en cours de traitement : celle-ci est ignorée, sans rien
         * retirer ni progresser. C'est la garde anti double-clic / spam / appel concurrent.
         */
        BUSY
    }

    public DeliveryOutcome {
        lines = List.copyOf(lines);
    }

    public static DeliveryOutcome of(Status status, List<DeliveryLine> lines, boolean allComplete) {
        return new DeliveryOutcome(status, lines, allComplete);
    }

    /** Ce qui vient d'être remis à l'instant, objectifs non concernés exclus. */
    public List<DeliveryLine> justDelivered() {
        return lines.stream().filter(line -> line.justNow() > 0).toList();
    }

    /** Ce qu'il reste à remettre, lignes déjà complètes exclues. */
    public List<DeliveryLine> stillMissing() {
        return lines.stream().filter(line -> !line.complete()).toList();
    }
}
