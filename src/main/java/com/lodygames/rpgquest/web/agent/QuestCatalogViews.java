package com.lodygames.rpgquest.web.agent;

import com.lodygames.rpgquest.encounter.EncounterLoadIssue;
import com.lodygames.rpgquest.encounter.catalog.QuestCatalog;
import com.lodygames.rpgquest.encounter.model.EncounterTemplate;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.progress.CooldownText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Construit la vue {@code quest.catalog.list} (issue #369) et ses contrôles de validité. Sans
 * Bukkit : tout est passé par l'appelant, donc testable tel quel.
 */
final class QuestCatalogViews {

    private QuestCatalogViews() {
    }

    /**
     * @param dialogueOffersCatalog vrai si le dialogue {@code rpgquest:<npcId>} contient au moins une
     *                              action {@code OFFER_CATALOG_QUEST}
     */
    static AgentActions.QuestCatalogListView build(List<QuestCatalog> catalogs, List<EncounterLoadIssue> issues,
                                                   List<QuestDefinition> quests, List<EncounterTemplate> templates,
                                                   Predicate<String> dialogueOffersCatalog) {
        Map<String, QuestDefinition> byId = new LinkedHashMap<>();
        quests.forEach(q -> byId.put(q.id().toString(), q));
        Set<String> catalogIds = catalogs.stream().map(QuestCatalog::id).collect(Collectors.toSet());

        List<AgentActions.QuestCatalogSummary> rows = new ArrayList<>();
        for (QuestCatalog c : catalogs) {
            List<String> usedBy = templates.stream().filter(t -> t.questCatalogs().contains(c.id()))
                    .map(EncounterTemplate::id).toList();
            List<AgentActions.QuestCatalogEntryView> entries = c.entries().stream()
                    .map(e -> entryView(e, byId.get(e.questId()))).toList();
            rows.add(new AgentActions.QuestCatalogSummary(c.id(), c.displayName(), c.description(), c.enabled(),
                    c.avoidRecent(), entries, usedBy, catalogWarnings(c, entries, usedBy)));
        }

        List<String> warnings = new ArrayList<>();
        for (EncounterTemplate t : templates) {
            for (String ref : t.questCatalogs()) {
                if (!catalogIds.contains(ref)) {
                    warnings.add("⚠ Le modèle « " + t.id() + " » référence un catalogue inconnu « " + ref
                            + " » : il est ignoré.");
                }
            }
            if (!t.questCatalogs().isEmpty() && !dialogueOffersCatalog.test(t.npcId())) {
                warnings.add("⚠ Le modèle « " + t.id() + " » a des catalogues, mais le dialogue rpgquest:"
                        + t.npcId() + " n'a aucune action OFFER_CATALOG_QUEST : ses PNJ ne les proposeront pas.");
            }
            if (t.enabled() && t.questCatalogs().isEmpty()) {
                warnings.add("ℹ Le modèle actif « " + t.id() + " » n'a aucun catalogue : ses PNJ ne proposent que "
                        + "les quêtes écrites dans leur dialogue.");
            }
        }
        Map<String, String> issueRows = new LinkedHashMap<>();
        issues.forEach(i -> issueRows.put(i.file(), i.message()));
        List<AgentActions.QuestRef> refs = quests.stream()
                .sorted(Comparator.comparing(q -> q.id().toString()))
                .map(q -> new AgentActions.QuestRef(q.id().toString(), plain(q.title().base()), q.category()))
                .toList();
        return new AgentActions.QuestCatalogListView(true, rows, issueRows, refs, warnings,
                System.currentTimeMillis());
    }

    private static AgentActions.QuestCatalogEntryView entryView(QuestCatalog.Entry e, QuestDefinition q) {
        if (q == null) {
            return new AgentActions.QuestCatalogEntryView(e.questId(), e.weight(), e.enabled(), false, null, null,
                    false, null, 0);
        }
        String cooldown = q.repeatable() && !q.cooldown().isZero() ? CooldownText.format(q.cooldown()) : null;
        return new AgentActions.QuestCatalogEntryView(e.questId(), e.weight(), e.enabled(), true,
                plain(q.title().base()), q.category(), q.repeatable(), cooldown, q.steps().size());
    }

    private static List<String> catalogWarnings(QuestCatalog c, List<AgentActions.QuestCatalogEntryView> entries,
                                                List<String> usedBy) {
        List<String> warnings = new ArrayList<>();
        if (!c.enabled()) {
            warnings.add("Catalogue désactivé : il ne propose rien.");
        }
        if (entries.stream().noneMatch(e -> e.enabled() && e.exists())) {
            warnings.add("⚠ Aucune quête active et existante : ce catalogue ne proposera rien.");
        }
        entries.stream().filter(e -> !e.exists()).forEach(e -> warnings.add("⚠ Quête absente « " + e.questId()
                + " » : jamais proposée (publiez-la ou retirez-la du catalogue)."));
        entries.stream().filter(e -> e.exists() && !e.repeatable()).forEach(e -> warnings.add("ℹ « "
                + e.questId() + " » n'est pas renouvelable : chaque joueur ne la recevra qu'une fois."));
        if (usedBy.isEmpty()) {
            warnings.add("ℹ Attribué à aucun modèle de PNJ récurrent.");
        }
        return List.copyOf(warnings);
    }

    private static String plain(String miniMessage) {
        if (miniMessage == null) {
            return "";
        }
        try {
            return PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(miniMessage));
        } catch (RuntimeException e) {
            return miniMessage;
        }
    }
}
