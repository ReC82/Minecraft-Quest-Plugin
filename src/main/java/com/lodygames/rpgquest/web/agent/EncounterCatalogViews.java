package com.lodygames.rpgquest.web.agent;

import com.lodygames.rpgquest.config.EncounterConfig;
import com.lodygames.rpgquest.encounter.EncounterLoadIssue;
import com.lodygames.rpgquest.encounter.EncounterService;
import com.lodygames.rpgquest.encounter.model.EncounterSite;
import com.lodygames.rpgquest.encounter.model.EncounterTemplate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Construit la vue {@code encounter.list} (issue #13 étape 2) depuis l'état en mémoire des PNJ
 * récurrents. Sans Bukkit : seule la présence Citizens est fournie par l'appelant, sur le thread
 * principal.
 */
final class EncounterCatalogViews {

    private EncounterCatalogViews() {
    }

    /** @param citizensExists vrai si l'entité Citizens existe encore ; {@code null} = Citizens absent. */
    static AgentActions.EncounterCatalogView build(EncounterService service, Predicate<UUID> citizensExists) {
        return build(service.config(), service.effectiveWorlds(), service.templates().templates(),
                service.templates().lastReport().issues(), service.sites(), service.recentEvents(), citizensExists);
    }

    static AgentActions.EncounterCatalogView build(EncounterConfig config, List<String> worlds,
                                                   List<EncounterTemplate> templates, List<EncounterLoadIssue> issues,
                                                   List<EncounterSite> sites, List<EncounterService.Event> events,
                                                   Predicate<UUID> citizensExists) {
        Map<String, Integer> sitesByTemplate = new HashMap<>();
        sites.forEach(s -> sitesByTemplate.merge(s.templateId(), 1, Integer::sum));
        List<AgentActions.EncounterTemplateSummary> templateRows = templates.stream()
                .map(t -> new AgentActions.EncounterTemplateSummary(t.id(), t.displayName(), t.npcId(), t.role(),
                        t.family(), t.biomes().stream().sorted().toList(), t.worlds(), t.weight(), t.maxSites(),
                        t.enabled(), t.skinUrl(), sitesByTemplate.getOrDefault(t.id(), 0)))
                .toList();
        Map<String, String> issueRows = new LinkedHashMap<>();
        issues.forEach(i -> issueRows.put(i.file(), i.message()));
        List<AgentActions.EncounterSiteSummary> siteRows = sites.stream()
                .map(s -> siteSummary(s, citizensExists))
                .toList();
        // Déjà du plus récent au plus ancien (EncounterService#record).
        List<AgentActions.EncounterEventSummary> eventRows = events.stream()
                .map(e -> new AgentActions.EncounterEventSummary(e.at().toString(), e.world(), e.instance(),
                        e.outcome(), e.detail()))
                .toList();
        return new AgentActions.EncounterCatalogView(true, config.enabled(), worlds, config.maxPerWorld(),
                templateRows, issueRows, siteRows, eventRows, System.currentTimeMillis());
    }

    private static AgentActions.EncounterSiteSummary siteSummary(EncounterSite s, Predicate<UUID> citizensExists) {
        Boolean present = !s.hasCitizens() ? Boolean.FALSE
                : citizensExists == null ? null : citizensExists.test(s.citizensUuid());
        return new AgentActions.EncounterSiteSummary(s.id(), s.world(), s.biomeKey(), s.biomeInstance(),
                s.x(), s.y(), s.z(), s.templateId(), s.npcId(), s.citizensId(),
                s.hasCitizens() ? s.citizensUuid().toString() : null, present, s.status().name(), s.lastError(),
                String.valueOf(s.createdAt()), String.valueOf(s.updatedAt()));
    }
}
