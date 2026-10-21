package com.lodygames.rpgquest.ui;

import java.util.List;
import org.bukkit.NamespacedKey;

/**
 * État (en mémoire uniquement, comme les sessions de dialogue) d'un menu de
 * journal actuellement ouvert pour un joueur. {@code pageQuests} fige la
 * correspondance slot → quête au moment du rendu : un clic sur un slot de
 * contenu est résolu contre cette liste, jamais recalculé à la volée, pour
 * rester cohérent même si l'ensemble de quêtes change entre le rendu et le
 * clic (ex. {@code /quest admin reload}).
 *
 * <p>{@code progression} : la page « Ma progression RPG » (issue #183) est ouverte ; {@code tab}
 * et {@code page} restent ceux de la liste d'où le joueur vient, pour que « Retour » y ramène.</p>
 */
record JournalSession(JournalTab tab, int page, List<NamespacedKey> pageQuests, NamespacedKey detailQuestId,
                      boolean progression) {

    static JournalSession list(JournalTab tab, int page, List<NamespacedKey> pageQuests) {
        return new JournalSession(tab, page, pageQuests, null, false);
    }

    static JournalSession detail(JournalTab tab, int page, NamespacedKey questId) {
        return new JournalSession(tab, page, List.of(), questId, false);
    }

    static JournalSession progression(JournalTab tab, int page) {
        return new JournalSession(tab, page, List.of(), null, true);
    }

    boolean isDetail() {
        return detailQuestId != null;
    }

    boolean isProgression() {
        return progression;
    }
}
