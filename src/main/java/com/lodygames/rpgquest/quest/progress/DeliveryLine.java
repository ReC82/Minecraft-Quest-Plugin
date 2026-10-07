package com.lodygames.rpgquest.quest.progress;

import org.bukkit.Material;

/**
 * Une ligne de l'état de remise d'un objectif {@code DELIVER_ITEM_TO_NPC} (issue #123) :
 * « ce matériau, déjà remis {@code delivered} fois sur {@code required} ». Vue en lecture seule,
 * destinée au dialogue, au récapitulatif et aux tests — jamais un état mutable.
 *
 * @param material   matériau demandé
 * @param delivered  quantité déjà remise et acquise (survit à la mort, à la reconnexion et au
 *                   redémarrage, voir {@code DeliverItemToNpcObjective})
 * @param required   quantité totale demandée par l'objectif
 * @param justNow    quantité remise par <em>cette</em> interaction (0 pour un simple état)
 */
public record DeliveryLine(Material material, int delivered, int required, int justNow) {

    public DeliveryLine(Material material, int delivered, int required) {
        this(material, delivered, required, 0);
    }

    /** Ce qu'il reste à remettre — jamais négatif, même si un compteur dépassait la demande. */
    public int remaining() {
        return Math.max(0, required - delivered);
    }

    public boolean complete() {
        return delivered >= required;
    }
}
