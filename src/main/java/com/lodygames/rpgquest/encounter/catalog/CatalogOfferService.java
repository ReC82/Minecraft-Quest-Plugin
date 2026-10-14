package com.lodygames.rpgquest.encounter.catalog;

import com.lodygames.rpgquest.encounter.model.EncounterTemplate;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bukkit.NamespacedKey;

/**
 * Relie les catalogues (issue #369) au moteur de quêtes : quels catalogues un PNJ propose, et
 * quelle quête offrir à ce joueur maintenant. Toutes les règles d'acceptation viennent de
 * {@link QuestProgressEngine#availability} ; le choix lui-même est {@link CatalogOfferSelector}.
 * Lecture seule : rien n'est écrit tant que le joueur n'a pas accepté.
 */
public final class CatalogOfferService {

    private final YamlQuestCatalogEngine catalogs;
    private final Supplier<List<EncounterTemplate>> templates;
    private final YamlQuestEngine quests;
    private final QuestProgressEngine progress;

    public CatalogOfferService(YamlQuestCatalogEngine catalogs, Supplier<List<EncounterTemplate>> templates,
                               YamlQuestEngine quests, QuestProgressEngine progress) {
        this.catalogs = catalogs;
        this.templates = templates;
        this.quests = quests;
        this.progress = progress;
    }

    /**
     * @param configured {@code false} si le PNJ n'a aucun catalogue actif (modèle sans
     *                   {@code quest_catalogs}, catalogue inconnu ou désactivé)
     * @param quest      quête de la décision ({@code OFFER} / {@code ACTIVE}), sinon {@code null}
     */
    public record Offer(boolean configured, CatalogOfferSelector.Decision decision, QuestDefinition quest) {
    }

    /** Catalogues actifs d'un PNJ : {@code explicitCatalog} s'il est donné, sinon ceux de ses modèles. */
    public List<QuestCatalog> catalogsFor(String npcId, String explicitCatalog) {
        Set<String> ids = new LinkedHashSet<>();
        if (explicitCatalog != null && !explicitCatalog.isBlank()) {
            ids.add(explicitCatalog);
        } else if (npcId != null) {
            for (EncounterTemplate t : templates.get()) {
                if (t.npcId().equals(npcId)) {
                    ids.addAll(t.questCatalogs());
                }
            }
        }
        List<QuestCatalog> found = new ArrayList<>();
        for (String id : ids) {
            catalogs.find(id).filter(QuestCatalog::enabled).ifPresent(found::add);
        }
        return found;
    }

    public CompletableFuture<Offer> offerFor(UUID player, String npcId, String explicitCatalog) {
        List<QuestCatalog> active = catalogsFor(npcId, explicitCatalog);
        if (active.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new Offer(false, CatalogOfferSelector.Decision.nothing(null), null));
        }
        List<CatalogOfferSelector.Candidate> candidates = candidates(active);
        int avoidRecent = active.stream().mapToInt(QuestCatalog::avoidRecent).max().orElse(0);
        Map<String, CompletableFuture<CatalogOfferSelector.QuestStatus>> checks = new LinkedHashMap<>();
        for (CatalogOfferSelector.Candidate c : candidates) {
            NamespacedKey key = NamespacedKey.fromString(c.questId());
            checks.put(c.questId(), key == null
                    ? CompletableFuture.completedFuture(
                            new CatalogOfferSelector.QuestStatus(CatalogOfferSelector.Status.UNAVAILABLE, null))
                    : progress.availability(player, key, false).thenApply(CatalogOfferService::statusOf));
        }
        CompletableFuture<Map<NamespacedKey, Instant>> completions = progress.completionTimes(player);
        List<CompletableFuture<?>> all = new ArrayList<>(checks.values());
        all.add(completions);
        String scope = npcId != null ? npcId : explicitCatalog;
        return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).thenApply(v -> {
            Map<String, CatalogOfferSelector.QuestStatus> statuses = new LinkedHashMap<>();
            checks.forEach((id, f) -> statuses.put(id, f.join()));
            Map<String, Instant> completedAt = new LinkedHashMap<>();
            completions.join().forEach((k, at) -> completedAt.put(k.toString(), at));
            CatalogOfferSelector.Decision decision = CatalogOfferSelector.decide(player, scope, candidates,
                    statuses, completedAt, avoidRecent);
            QuestDefinition quest = decision.questId() == null ? null
                    : quests.find(NamespacedKey.fromString(decision.questId())).orElse(null);
            return new Offer(true, decision, quest);
        });
    }

    /** Entrées actives de tous les catalogues, dans l'ordre ; une quête partagée garde son plus fort poids. */
    static List<CatalogOfferSelector.Candidate> candidates(List<QuestCatalog> active) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (QuestCatalog catalog : active) {
            for (QuestCatalog.Entry entry : catalog.activeEntries()) {
                weights.merge(entry.questId(), entry.weight(), Math::max);
            }
        }
        List<CatalogOfferSelector.Candidate> out = new ArrayList<>();
        weights.forEach((id, w) -> out.add(new CatalogOfferSelector.Candidate(id, w)));
        return out;
    }

    private static CatalogOfferSelector.QuestStatus statusOf(QuestProgressEngine.Availability a) {
        CatalogOfferSelector.Status s = switch (a.status()) {
            case AVAILABLE -> CatalogOfferSelector.Status.AVAILABLE;
            case ALREADY_ACTIVE -> CatalogOfferSelector.Status.ACTIVE;
            case ON_COOLDOWN -> CatalogOfferSelector.Status.ON_COOLDOWN;
            default -> CatalogOfferSelector.Status.UNAVAILABLE;
        };
        return new CatalogOfferSelector.QuestStatus(s, a.retryAfter());
    }
}
