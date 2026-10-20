package com.lodygames.rpgquest.ui;

import com.lodygames.rpgquest.quest.model.QuestPeriod;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.periodic.PeriodicProgress;
import com.lodygames.rpgquest.quest.periodic.PeriodicQuestService.PeriodView;
import com.lodygames.rpgquest.quest.progress.CooldownText;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
 *
 * <p>Retour TC-350 : l'avancement se lit <strong>sans survol</strong>. Le titre de la fenêtre porte
 * le résumé (« Quotidiennes : 1/2 terminée »), et l'onglet est découpé en deux sections repérables à
 * l'œil — une rangée « À faire » ouverte par une vitre jaune, une rangée « Terminées » ouverte par
 * une vitre verte, dont les quêtes brillent. Les terminées restent consultables jusqu'à la
 * réinitialisation, et n'apparaissent jamais dans « Quêtes terminées » (quêtes classiques).</p>
 */
final class PeriodicJournalEntries {

    static final Material TO_DO_HEADER = Material.YELLOW_STAINED_GLASS_PANE;
    static final Material COMPLETED_HEADER = Material.LIME_STAINED_GLASS_PANE;

    private static final int COLUMNS = 9;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private PeriodicJournalEntries() {
    }

    /**
     * Correspondance index de contenu → quête de l'onglet : {@code null} pour un en-tête de section
     * ou une case vide. Un clic sur un {@code null} ne fait rien.
     */
    static List<NamespacedKey> layout(PeriodicProgress progress) {
        if (progress.total() == 0) {
            return List.of();
        }
        NamespacedKey[] cells = new NamespacedKey[JournalPagination.PAGE_SIZE];
        int nextRow = place(cells, 0, progress.toDo());
        int endRow = place(cells, nextRow, progress.completed());
        return Collections.unmodifiableList(Arrays.asList(Arrays.copyOf(cells, endRow * COLUMNS)));
    }

    /** Index de contenu de l'en-tête « Terminées » (celui de « À faire » est toujours 0). */
    static int completedHeaderIndex(PeriodicProgress progress) {
        return place(new NamespacedKey[JournalPagination.PAGE_SIZE], 0, progress.toDo()) * COLUMNS;
    }

    /** Pose la section à partir de la rangée {@code row} (colonne 0 = en-tête) ; rend la rangée suivante libre. */
    private static int place(NamespacedKey[] cells, int row, List<NamespacedKey> quests) {
        int perRow = COLUMNS - 1;
        for (int i = 0; i < quests.size(); i++) {
            cells[(row + i / perRow) * COLUMNS + 1 + i % perRow] = quests.get(i);
        }
        int rows = Math.max(1, (quests.size() + perRow - 1) / perRow);
        return row + rows;
    }

    static ItemStack toDoHeader(PeriodicProgress progress) {
        return header(TO_DO_HEADER, "<yellow><bold>À faire</bold> (<count>)</yellow>", progress.toDo().size(),
                progress.toDo().isEmpty() ? "<green>Tout est fait pour cette période !</green>"
                        : "<gray>Pas encore terminées : elles sont à droite.</gray>");
    }

    static ItemStack completedHeader(PeriodicProgress progress) {
        return header(COMPLETED_HEADER, "<green><bold>Terminées</bold> (<count>)</green>", progress.completed().size(),
                progress.completed().isEmpty() ? "<gray>Aucune pour le moment.</gray>"
                        : "<gray>Consultables jusqu'à la réinitialisation.</gray>");
    }

    private static ItemStack header(Material material, String title, int count, String hint) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MM.deserialize(title, Placeholder.unparsed("count", String.valueOf(count))));
        meta.lore(List.of(MM.deserialize(hint)));
        stack.setItemMeta(meta);
        return stack;
    }

    /** Remplace l'indicateur de page (inutile : un onglet périodique tient sur une page) par le résumé. */
    static ItemStack summaryIcon(PeriodicProgress progress, PeriodView view, Instant now) {
        ItemStack stack = new ItemStack(progress.period() == QuestPeriod.DAILY ? Material.CLOCK : Material.RECOVERY_COMPASS);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MM.deserialize("<white><summary></white>", Placeholder.unparsed("summary", progress.summary())));
        meta.lore(List.of(renewalLine(view, now)));
        stack.setItemMeta(meta);
        return stack;
    }

    /** Ajoute à l'icône d'une quête périodique son statut pour la période et l'échéance ; une terminée brille. */
    static void decorate(ItemStack icon, QuestState state, PeriodView view, Instant now) {
        ItemMeta meta = icon.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(MM.deserialize(statusLine(state, view.period())));
        lore.add(renewalLine(view, now));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(state == QuestState.COMPLETED);
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
