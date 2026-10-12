package com.lodygames.rpgquest.panel.building;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.AuditLog;
import com.lodygames.rpgquest.panel.building.BuildingPlanDraftStore.Check;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Ce que l'Atelier fait <strong>seul</strong>, sans clic et sans appel payant (issue #282).
 *
 * <h2>Le défaut corrigé</h2>
 *
 * <p>Le 12/10/2026, la « Maison du bibliothécaire » a été refusée pour un toit qui dépassait d'un
 * bloc. Le serveur connaissait la faute exacte ; l'administrateur a dû la recopier dans le champ
 * de correction, payer un appel à l'IA, puis revérifier. Il servait d'intermédiaire entre le
 * validateur et le modèle.</p>
 *
 * <h2>Ce que fait ce service</h2>
 *
 * <ul>
 *   <li>{@link #verify} : vérification et aperçu d'une version, en un geste — c'est ce qui suit
 *       automatiquement chaque nouveau plan, qu'il vienne de l'IA ou du moteur ;</li>
 *   <li>{@link #onResult} : quand l'audit de la version COURANTE revient avec une réparation sûre
 *       calculée par le plugin, une nouvelle version « Réparé par le moteur » est créée et
 *       vérifiée à son tour. L'administrateur trouve, en rouvrant la page, un plan déjà corrigé.</li>
 * </ul>
 *
 * <h2>Garde-fous</h2>
 *
 * <ul>
 *   <li><strong>idempotent</strong> : un même audit ne produit jamais deux réparations (clé :
 *       l'identifiant de l'action d'audit) ;</li>
 *   <li><strong>pas de cascade</strong> : une version déjà réparée par le moteur n'est pas
 *       re-réparée — ce qui reste est le travail de l'IA ;</li>
 *   <li><strong>rien d'écrasé</strong> : la réparation est une version nouvelle, la version
 *       d'origine reste consultable et restaurable ;</li>
 *   <li>un audit <strong>périmé</strong> (le plan a changé depuis) n'est jamais exploité.</li>
 * </ul>
 */
public final class WorkshopAutopilot {

    /** Auteur des actions et versions créées sans clic : visible dans l'historique et l'audit. */
    public static final String AUTHOR = "atelier-auto";

    private static final System.Logger LOG = System.getLogger(WorkshopAutopilot.class.getName());

    private final AgentStore agents;
    private final BuildingPlanDraftStore drafts;
    private final AuditLog audit;

    public WorkshopAutopilot(AgentStore agents, BuildingPlanDraftStore drafts, AuditLog audit) {
        this.agents = agents;
        this.drafts = drafts;
        this.audit = audit;
    }

    /**
     * Vérification et aperçu d'une version, gratuits. Les bornes du projet accompagnent le plan :
     * le moteur peut alors agrandir le volume plutôt qu'araser un toit.
     *
     * @return le motif si la vérification n'a pas pu partir
     */
    public Optional<String> verify(BuildingPlanDraft draft, String by) {
        if (draft == null || !draft.hasPlan()) {
            return Optional.of("Aucun plan à vérifier.");
        }
        Optional<String> audited = enqueue(draft, Check.AUDIT, by);
        if (audited.isPresent()) {
            return audited;
        }
        // L'aperçu accompagne la vérification ; s'il ne peut pas partir, la vérification reste
        // valable — l'écran dira que l'aperçu manque.
        enqueue(draft, Check.PREVIEW, by);
        return Optional.empty();
    }

    private Optional<String> enqueue(BuildingPlanDraft draft, Check check, String by) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("plan", draft.plan());
        if (draft.maxWidth() > 0) {
            form.put("max_width", String.valueOf(draft.maxWidth()));
        }
        if (draft.maxHeight() > 0) {
            form.put("max_height", String.valueOf(draft.maxHeight()));
        }
        var v = AgentActionCatalog.validate(check.actionType(), form);
        if (!v.valid()) {
            return Optional.of(v.error());
        }
        String id = agents.createAction(draft.agentId(), check.actionType(), v.params(),
                by == null || by.isBlank() ? AUTHOR : by);
        drafts.link(draft.id(), id, check, draft.version());
        return Optional.empty();
    }

    /**
     * Un agent vient de rendre un résultat. Ne lève jamais : un échec ici ne doit pas faire
     * échouer la réception du résultat, qui est déjà enregistré.
     */
    public void onResult(String actionId) {
        try {
            repairFrom(actionId);
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "event=workshop_autopilot_failed action=" + actionId
                    + " error=" + failure.getClass().getSimpleName());
        }
    }

    /** La réparation créée, s'il y en a eu une — exposé pour les tests. */
    Optional<BuildingPlanDraft> repairFrom(String actionId) {
        Optional<BuildingPlanDraftStore.Link> link = drafts.linkOf(actionId)
                .filter(l -> l.check() == Check.AUDIT);
        if (link.isEmpty()) {
            return Optional.empty();
        }
        Optional<BuildingPlanDraft> parent = drafts.find(link.get().draftId())
                .filter(d -> !d.archived() && d.version() == link.get().version()
                        && d.origin() != BuildingPlanDraft.Origin.ENGINE_REPAIR);
        Optional<AgentActionRow> row = agents.action(actionId);
        if (parent.isEmpty() || row.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> details = BuildingPlanWorkbench.details(row.get()).orElse(Map.of());
        Optional<EngineRepair> repair = EngineRepair.from(details);
        if (repair.isEmpty()) {
            return Optional.empty();
        }
        Optional<BuildingPlanDraft> already = drafts.repairedFrom(actionId);
        if (already.isPresent()) {
            return Optional.empty();
        }
        BuildingPlanDraft d = parent.get();
        BuildingPlanDraft created = drafts.create(new BuildingPlanDraftStore.NewDraft(d.agentId(),
                d.siteId(), repair.get().plan(), BuildingPlanDraft.Origin.ENGINE_REPAIR, d.id(),
                actionId, "", "Moteur RPGQuest", "", "", "", repair.get().note(), d.maxWidth(),
                d.maxHeight(), AUTHOR, d.projectId()));
        String rid = UUID.randomUUID().toString().substring(0, 8);
        audit.record(AUTHOR, "building.plan.engine_repair", "draft=" + d.id() + " repaired=" + created.id()
                        + " audit=" + actionId, "OK",
                repair.get().changes().size() + " correction(s), aucun appel IA", rid);
        verify(created, AUTHOR);
        return Optional.of(created);
    }

    /**
     * La réparation telle que le plugin la décrit dans le détail d'un audit.
     *
     * @param accepted le plan réparé passe entièrement l'audit du plugin
     * @param codes    les refus qui restent après réparation
     */
    public record EngineRepair(String plan, List<String> changes, List<String> codes,
                               boolean accepted) {

        public EngineRepair {
            changes = List.copyOf(changes);
            codes = List.copyOf(codes);
        }

        public static Optional<EngineRepair> from(Map<String, Object> details) {
            if (details == null || !(details.get("repair") instanceof Map<?, ?> raw)) {
                return Optional.empty();
            }
            Object plan = raw.get("plan");
            if (plan == null || String.valueOf(plan).isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new EngineRepair(String.valueOf(plan), strings(raw.get("changes")),
                    strings(raw.get("codes")), Boolean.TRUE.equals(raw.get("accepted"))));
        }

        /** La note conservée avec la version : ce que le moteur a changé, une ligne par point. */
        public String note() {
            StringBuilder sb = new StringBuilder("Corrections du moteur, sans appel à l'IA :");
            for (String change : changes) {
                sb.append("\n- ").append(change);
            }
            if (!accepted && !codes.isEmpty()) {
                sb.append("\nReste à corriger : ").append(String.join(", ", codes)).append('.');
            }
            return sb.toString();
        }

        private static List<String> strings(Object raw) {
            List<String> out = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null && !String.valueOf(item).isBlank()) {
                        out.add(String.valueOf(item));
                    }
                }
            }
            return out;
        }
    }
}
