package com.lodygames.rpgquest.item;

import org.bukkit.NamespacedKey;

/**
 * Identifiants namespacés des objets personnalisés « permanents » du plugin — ceux qui ne doivent
 * jamais être perdus (voir {@link SoulboundItemService}) et sont (re)donnés gratuitement par un PNJ.
 * Une seule source de vérité pour ces id, plutôt qu'un {@code new NamespacedKey("rpgquest", ...)}
 * recopié dans chaque écouteur qui les manipule.
 */
public final class RpgItemKeys {

    /** Acte de propriété — pose le premier claim puis sert d'outil de visualisation (voir {@code claim.DeedClaimListener}). */
    public static final NamespacedKey ACTE_PROPRIETE = new NamespacedKey("rpgquest", "acte_propriete");

    /** Pierre de retour — voyage {@code claims} → Hub (voir {@code travel.ItemTravelService}). */
    public static final NamespacedKey PIERRE_RETOUR = new NamespacedKey("rpgquest", "pierre_retour");

    /** Journal des quêtes — clic droit ouvre le résumé compact des stories/quêtes actives (voir {@code ui.QuestJournalBookService}). */
    public static final NamespacedKey JOURNAL_QUETES = new NamespacedKey("rpgquest", "journal_quetes");

    /** Rune de rappel — voyage de secours {@code wild} → Hub, avec cooldown (voir {@code travel.ItemTravelService}). */
    public static final NamespacedKey RUNE_RAPPEL = new NamespacedKey("rpgquest", "rune_rappel");

    private RpgItemKeys() {
    }
}
