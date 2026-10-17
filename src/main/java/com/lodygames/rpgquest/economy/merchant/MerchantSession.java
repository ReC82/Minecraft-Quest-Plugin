package com.lodygames.rpgquest.economy.merchant;

import org.bukkit.NamespacedKey;

/**
 * État en mémoire (jamais persisté, comme {@code DialogueSession}/{@code
 * JournalSession}) du marchand actuellement ouvert par un joueur. Un clic
 * est toujours résolu contre ce marchand précis, pas recalculé à la volée :
 * un {@code /merchant reload} qui change les offres entre l'ouverture et le
 * clic ne peut donc jamais faire acheter au joueur « la mauvaise offre »
 * (l'offre à l'index cliqué est relue depuis le marchand actuel au moment du
 * clic, voir {@link MerchantTradeService}).
 *
 * <p>Issue #336 : {@code priceGeneration} est la génération du catalogue des
 * prix affichée ; si elle a changé au moment du clic, l'échange est refusé et
 * la vitrine réaffichée — le joueur paie toujours le prix qu'il voit.</p>
 */
record MerchantSession(NamespacedKey merchantId, long priceGeneration) {
}
