package com.lodygames.rpgquest.ui;

import com.lodygames.rpgquest.database.PeriodicQuestAssignmentRepository.Assignment;
import com.lodygames.rpgquest.quest.model.QuestPeriod;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.periodic.PeriodicQuestService.PeriodView;
import com.lodygames.rpgquest.quest.progress.CooldownText;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Issue #15 — contenu des onglets « Quêtes quotidiennes » / « Quêtes hebdomadaires » du journal :
 * uniquement les quêtes <strong>attribuées</strong> au joueur pour la période en cours (jamais la
 * réserve de modèles), dans l'ordre de l'attribution, avec l'échéance de la période.
 */
final class PeriodicJournalEntries {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private PeriodicJournalEntries() {
    }

    static List<NamespacedKey> questIds(PeriodView view) {
        return view.assignments().stream().map(Assignment::questId).map(NamespacedKey::fromString)
                .filter(Objects::nonNull).toList();
    }

    /** Ajoute à l'icône d'une quête périodique son statut pour la période et l'échéance. */
    static void decorate(ItemStack icon, QuestState state, PeriodView view, Instant now) {
        ItemMeta meta = icon.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(MM.deserialize(statusLine(state, view.period())));
        lore.add(renewalLine(view, now));
        meta.lore(lore);
        icon.setItemMeta(meta);
    }

    /** Onglet vide : aucune quête pour cette période (aucun modèle publié, ou nombre réglé à 0). */
    static ItemStack emptyIcon(PeriodView view, Instant now) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MM.deserialize("<gray>Aucune quête pour le moment</gray>"));
        meta.lore(List.of(renewalLine(view, now)));
        stack.setItemMeta(meta);
        return stack;
    }

    private static String statusLine(QuestState state, QuestPeriod period) {
        String scope = period == QuestPeriod.DAILY ? "aujourd'hui" : "cette semaine";
        return switch (state) {
            case COMPLETED -> "<green>✔ Terminée " + scope + "</green>";
            case READY_TO_TURN_IN -> "<gold>Réussie — libère de la place pour la récompense</gold>";
            case ACTIVE -> "<yellow>À faire " + scope + "</yellow>";
            case ABANDONED -> "<gray>Abandonnée " + scope + "</gray>";
            default -> "<gray>Pas encore commencée</gray>";
        };
    }

    private static Component renewalLine(PeriodView view, Instant now) {
        Duration remaining = Duration.between(now, view.nextReset());
        return MM.deserialize("<dark_gray>Nouvelles quêtes dans <time></dark_gray>",
                Placeholder.unparsed("time", CooldownText.format(remaining.isNegative() ? Duration.ZERO : remaining)));
    }
}
