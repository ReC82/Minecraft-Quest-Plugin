package com.lodygames.rpgquest.economy.merchant.model;

import com.lodygames.rpgquest.quest.model.QuestState;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;

/**
 * Une ligne de la vitrine d'un marchand : un lot ({@code quantity}) d'un
 * objet vanilla ou personnalisé, échangé pour {@code price} pièces, avec des
 * conditions d'accès optionnelles (permission/quête/niveau — toutes
 * cumulatives, une seule doit échouer pour masquer l'offre). Correct par
 * construction, même discipline que {@code CustomItemDefinition}/{@code
 * QuestDefinition} : les invariants sont vérifiés ici, pas seulement par le
 * parseur.
 */
public record MerchantOffer(
        TradeDirection direction,
        OfferItemKind itemKind,
        Material vanillaMaterial,
        NamespacedKey customItemId,
        int quantity,
        long price,
        String requiredPermission,
        NamespacedKey requiredQuestId,
        QuestState requiredQuestState,
        Integer requiredLevel
) {

    public MerchantOffer {
        if (quantity < 1) {
            throw new IllegalArgumentException("« quantity » doit être strictement positif : " + quantity);
        }
        if (price < 0) {
            throw new IllegalArgumentException("« price » ne peut pas être négatif : " + price);
        }
        if (itemKind == OfferItemKind.VANILLA && vanillaMaterial == null) {
            throw new IllegalArgumentException("Offre VANILLA sans matériau.");
        }
        if (itemKind == OfferItemKind.CUSTOM && customItemId == null) {
            throw new IllegalArgumentException("Offre CUSTOM sans id d'objet personnalisé.");
        }
        if (requiredPermission != null && requiredPermission.isBlank()) {
            throw new IllegalArgumentException("« required-permission » ne peut pas être vide si présent.");
        }
        if (requiredLevel != null && requiredLevel < 0) {
            throw new IllegalArgumentException("« required-level » ne peut pas être négatif : " + requiredLevel);
        }
        // Une quête référencée sans état explicite sous-entend « doit être terminée » (déblocage typique en RPG).
        if (requiredQuestId != null && requiredQuestState == null) {
            requiredQuestState = QuestState.COMPLETED;
        }
    }
}
