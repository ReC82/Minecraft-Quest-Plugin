package be.lloyd.rpgquest.economy.merchant;

import org.bukkit.NamespacedKey;

/**
 * État en mémoire (jamais persisté, comme {@code DialogueSession}/{@code
 * JournalSession}) du marchand actuellement ouvert par un joueur. Un clic
 * est toujours résolu contre ce marchand précis, pas recalculé à la volée :
 * un {@code /merchant reload} qui change les offres entre l'ouverture et le
 * clic ne peut donc jamais faire acheter au joueur « la mauvaise offre »
 * (l'offre à l'index cliqué est relue depuis le marchand actuel au moment du
 * clic, voir {@link MerchantTradeService}).
 */
record MerchantSession(NamespacedKey merchantId) {
}
