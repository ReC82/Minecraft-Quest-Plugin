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
 *
 * <p>Issue #336 : une offre <em>liée</em> ({@code priceRef} non nul) ne porte ni
 * sens, ni objet, ni quantité, ni prix — tout vient du prix {@code priceRef} du
 * catalogue {@code prices.yml}, résolu à l'affichage et au clic. Seules les
 * conditions d'accès restent propres à l'offre.</p>
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
        Integer requiredLevel,
        String priceRef
) {

    /** Offre au prix fixé dans le fichier du marchand (forme historique). */
    public MerchantOffer(TradeDirection direction, OfferItemKind itemKind, Material vanillaMaterial,
                         NamespacedKey customItemId, int quantity, long price, String requiredPermission,
                         NamespacedKey requiredQuestId, QuestState requiredQuestState, Integer requiredLevel) {
        this(direction, itemKind, vanillaMaterial, customItemId, quantity, price, requiredPermission,
                requiredQuestId, requiredQuestState, requiredLevel, null);
    }

    /** Offre liée au prix {@code priceRef} du catalogue (issue #336). */
    public static MerchantOffer linked(String priceRef, String requiredPermission, NamespacedKey requiredQuestId,
                                       QuestState requiredQuestState, Integer requiredLevel) {
        return new MerchantOffer(null, null, null, null, 0, 0, requiredPermission, requiredQuestId,
                requiredQuestState, requiredLevel, priceRef);
    }

    public MerchantOffer {
        if (priceRef != null) {
            if (priceRef.isBlank()) {
                throw new IllegalArgumentException("« price-ref » ne peut pas être vide si présent.");
            }
            if (direction != null || itemKind != null || quantity != 0 || price != 0) {
                throw new IllegalArgumentException("Une offre « price-ref » ne porte ni sens, ni objet, ni quantité, "
                        + "ni prix : tout vient du catalogue des prix.");
            }
        } else if (direction == null || itemKind == null) {
            throw new IllegalArgumentException("Offre sans sens ou sans objet.");
        } else if (quantity < 1) {
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

    /** Vrai si l'offre prend son prix dans le catalogue {@code prices.yml}. */
    public boolean linked() {
        return priceRef != null;
    }
}
