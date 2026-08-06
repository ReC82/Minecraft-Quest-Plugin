package be.lloyd.rpgquest.ui;

import java.util.List;
import org.bukkit.NamespacedKey;

/**
 * État (en mémoire uniquement, comme les sessions de dialogue) d'un menu de
 * journal actuellement ouvert pour un joueur. {@code pageQuests} fige la
 * correspondance slot → quête au moment du rendu : un clic sur un slot de
 * contenu est résolu contre cette liste, jamais recalculé à la volée, pour
 * rester cohérent même si l'ensemble de quêtes change entre le rendu et le
 * clic (ex. {@code /quest admin reload}).
 */
record JournalSession(JournalTab tab, int page, List<NamespacedKey> pageQuests, NamespacedKey detailQuestId) {

    static JournalSession list(JournalTab tab, int page, List<NamespacedKey> pageQuests) {
        return new JournalSession(tab, page, pageQuests, null);
    }

    static JournalSession detail(JournalTab tab, int page, NamespacedKey questId) {
        return new JournalSession(tab, page, List.of(), questId);
    }

    boolean isDetail() {
        return detailQuestId != null;
    }
}
