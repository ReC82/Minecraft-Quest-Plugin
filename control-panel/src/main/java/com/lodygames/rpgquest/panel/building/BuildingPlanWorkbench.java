package com.lodygames.rpgquest.panel.building;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.building.BuildingPlanDraftStore.Check;
import com.lodygames.rpgquest.panel.building.BuildingPlanDraftStore.Link;
import com.lodygames.rpgquest.panel.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Ce qu'on sait d'un brouillon de plan à un instant donné (issue #256) : son dernier audit, son
 * dernier aperçu, sa dernière génération — <strong>pour sa version courante</strong>.
 *
 * <p>Avant ce ticket, la page affichait « la dernière action d'audit de l'agent », quelle qu'elle
 * soit. Un autre plan audité entre-temps prenait donc la place du verdict de celui qu'on regardait.
 * Ici, chaque verdict est lu par l'identifiant d'action rattaché au brouillon, avec la version sur
 * laquelle il porte.</p>
 */
public final class BuildingPlanWorkbench {

    /** Où en est un geste (audit, aperçu, génération) pour un brouillon. */
    public enum Status {
        /** Jamais lancé sur ce brouillon. */
        NONE,
        /** Envoyé, pas encore de réponse du serveur. */
        PENDING,
        /** Réponse reçue. */
        DONE
    }

    /**
     * L'état d'un geste.
     *
     * @param row     l'action concernée, si elle existe encore
     * @param version version du plan sur laquelle elle porte
     * @param stale   {@code true} si elle porte sur une version antérieure à la version courante :
     *                son verdict ne dit rien du plan affiché
     */
    public record CheckState(Status status, Optional<AgentActionRow> row, int version, boolean stale) {

        static CheckState none() {
            return new CheckState(Status.NONE, Optional.empty(), 0, false);
        }

        public boolean current() {
            return status != Status.NONE && !stale;
        }

        /** Le corps {@code details} du résultat, s'il y en a un. */
        public Map<String, Object> details() {
            return row.flatMap(BuildingPlanWorkbench::details).orElse(Map.of());
        }
    }

    private final AgentStore agents;
    private final BuildingPlanDraftStore drafts;

    public BuildingPlanWorkbench(AgentStore agents, BuildingPlanDraftStore drafts) {
        this.agents = agents;
        this.drafts = drafts;
    }

    public BuildingPlanDraftStore drafts() {
        return drafts;
    }

    /**
     * L'état d'un geste pour la version courante ; à défaut, le plus récent sur une version
     * antérieure, marqué comme périmé.
     */
    public CheckState state(BuildingPlanDraft draft, Check check) {
        List<Link> links = drafts.links(draft.id(), check);
        if (links.isEmpty()) {
            return CheckState.none();
        }
        Link chosen = links.stream().filter(l -> l.version() == draft.version()).findFirst()
                .orElse(links.get(0));
        Optional<AgentActionRow> row = agents.action(chosen.actionId());
        boolean stale = chosen.version() != draft.version();
        if (row.isEmpty()) {
            return CheckState.none();
        }
        Status status = row.get().status().terminal() ? Status.DONE : Status.PENDING;
        return new CheckState(status, row, chosen.version(), stale);
    }

    /**
     * Les actions récentes qui portent un plan et qu'aucun brouillon ne contient encore : ce sont
     * les plans qu'on peut importer sans rappeler le modèle. C'est l'historique GLOBAL de l'agent,
     * jamais celui d'un projet (#308).
     */
    public List<AgentActionRow> recoverable(String agentId, int limit) {
        return recoverable(agentId, "", limit);
    }

    /**
     * Idem, filtré (#308) : {@code query} est cherché, sans casse, dans l'identifiant d'action et
     * dans l'identifiant et le nom du bâtiment du plan. Vide = pas de filtre.
     */
    public List<AgentActionRow> recoverable(String agentId, String query, int limit) {
        List<AgentActionRow> out = new ArrayList<>();
        if (agentId == null || agentId.isBlank()) {
            return out;
        }
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (AgentActionRow row : agents.recentActions(agentId, 500)) {
            if (out.size() >= limit) {
                break;
            }
            Optional<String> plan = planOf(row);
            if (plan.isPresent() && matches(row, plan.get(), needle)
                    && drafts.linkOf(row.id()).isEmpty()
                    && drafts.recoveredFrom(row.id()).isEmpty()) {
                out.add(row);
            }
        }
        return out;
    }

    private static boolean matches(AgentActionRow row, String plan, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        if (row.id().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        PlanImportPreview.Summary summary = PlanImportPreview.summaryOf(plan);
        return summary.buildingId().toLowerCase(Locale.ROOT).contains(needle)
                || summary.name().toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Le plan transporté par une action d'audit, d'aperçu ou de génération. */
    public static Optional<String> planOf(AgentActionRow row) {
        if (row == null || Check.ofActionType(row.type()).isEmpty()) {
            return Optional.empty();
        }
        String plan = row.params().getOrDefault("plan", "");
        return plan.isBlank() ? Optional.empty() : Optional.of(plan);
    }

    /** Le {@code details} du résultat d'une action, s'il est lisible. */
    public static Optional<Map<String, Object>> details(AgentActionRow row) {
        if (row == null || row.resultJson() == null || row.resultJson().isBlank()) {
            return Optional.empty();
        }
        try {
            Object details = Json.parseObject(row.resultJson()).get("details");
            if (!(details instanceof Map<?, ?> map)) {
                return Optional.empty();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return Optional.of(out);
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }
}
