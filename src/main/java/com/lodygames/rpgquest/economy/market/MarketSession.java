package com.lodygames.rpgquest.economy.market;

import com.lodygames.rpgquest.database.MarketListingRecord;
import java.util.List;

/**
 * État en mémoire (jamais persisté) de la page actuellement affichée à un
 * joueur. {@code pageListings} fige la correspondance slot → offre au
 * moment du rendu (id, vendeur, prix) : un clic est résolu contre cette
 * liste, jamais recalculé à la volée — même principe que
 * {@code JournalSession}/{@code MerchantSession}. Le prix ne peut jamais
 * changer après création d'une offre (pas de fonctionnalité d'édition), la
 * disponibilité réelle est revérifiée par {@code MarketRepository#claim}
 * au moment de l'achat, donc l'absence de re-consultation ici n'introduit
 * aucun risque de désynchronisation exploitable.
 */
record MarketSession(int page, List<MarketListingRecord> pageListings) {
}
