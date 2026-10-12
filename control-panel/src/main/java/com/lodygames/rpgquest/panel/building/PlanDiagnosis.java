package com.lodygames.rpgquest.panel.building;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.building.BuildingPlanWorkbench.CheckState;
import com.lodygames.rpgquest.panel.building.BuildingPlanWorkbench.Status;
import java.util.ArrayList;
import java.util.List;

/**
 * Ce que le serveur a dit de la version courante d'un plan — refus, écart avec le brief,
 * avertissements — rédigé pour être transmis tel quel à l'IA (issue #282).
 *
 * <p>C'est le texte que « Améliorer avec l'IA » et la conception automatique envoient d'eux-mêmes :
 * l'administrateur n'a plus à recopier l'erreur du validateur pour le modèle.</p>
 */
public final class PlanDiagnosis {

    private PlanDiagnosis() {
    }

    /** Vide si la version n'a pas encore été vérifiée, ou si le serveur n'a rien à redire. */
    public static String of(BuildingProject project, CheckState audit) {
        BuildingPlanVerdict verdict = verdict(audit);
        List<String> lines = new ArrayList<>();
        if (verdict.available() && !verdict.accepted()) {
            lines.add(verdict.message());
        }
        if (verdict.available()) {
            BriefLevels.divergence(project.brief(), verdict.metrics()).ifPresent(lines::add);
            lines.addAll(verdict.warnings());
        }
        if (!verdict.available() && audit.current() && audit.status() == Status.DONE) {
            audit.row().map(AgentActionRow::resultMessage).filter(m -> m != null && !m.isBlank())
                    .ifPresent(lines::add);
        }
        return String.join("\n", lines).trim();
    }

    /** Le verdict de la version courante, avec le message de l'action en secours. */
    public static BuildingPlanVerdict verdict(CheckState audit) {
        return audit.current() && audit.status() == Status.DONE
                ? BuildingPlanVerdict.from(audit.details(),
                        audit.row().map(AgentActionRow::resultMessage).orElse(""))
                : BuildingPlanVerdict.none();
    }
}
