package com.lodygames.rpgquest.panel.building;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.building.BuildingPlanWorkbench.CheckState;
import com.lodygames.rpgquest.panel.building.BuildingPlanWorkbench.Status;
import java.util.List;

/**
 * Le statut de vérification d'une version de plan, tel que l'historique de l'Atelier l'affiche
 * (#828) : conforme, avertissements acceptables, bloqué — avec ses codes et une explication courte.
 *
 * <p>Comme {@link BuildingPlanVerdict}, il ne fait que relire le dernier audit rattaché à la
 * version ; il ne juge rien lui-même. Un audit fait sur un texte antérieur de la même version est
 * signalé ({@link #stale()}) plutôt que tu.</p>
 *
 * @param codes       les codes bloquants du serveur, vides si conforme ou non vérifié
 * @param explanation une phrase, en clair, du premier problème (ou de l'état)
 */
public record PlanVersionStatus(Kind kind, List<String> codes, String explanation, boolean stale,
                                int auditVersion) {

    /** Les états possibles, du meilleur au pire, puis l'absence de verdict. */
    public enum Kind {
        CONFORMING("Conforme", "success", "✓"),
        ACCEPTABLE("Avertissements acceptables", "pending", "!"),
        BLOCKED("Bloqué", "failed", "✕"),
        PENDING("Vérification en cours", "delivered", "…"),
        UNCHECKED("Non vérifiée", "neutral", "•");

        private final String label;
        private final String pill;
        private final String glyph;

        Kind(String label, String pill, String glyph) {
            this.label = label;
            this.pill = pill;
            this.glyph = glyph;
        }

        public String label() {
            return label;
        }

        /** La variante de pastille ({@code Ui.pill}). */
        public String pill() {
            return pill;
        }

        public String glyph() {
            return glyph;
        }
    }

    public PlanVersionStatus {
        codes = List.copyOf(codes == null ? List.of() : codes);
        explanation = explanation == null ? "" : explanation;
    }

    /** Lit le dernier audit d'une version, même s'il porte sur un texte antérieur (alors {@code stale}). */
    public static PlanVersionStatus of(CheckState audit) {
        if (audit == null || audit.status() == Status.NONE) {
            return new PlanVersionStatus(Kind.UNCHECKED, List.of(), "Aucune vérification demandée.", false, 0);
        }
        if (audit.status() == Status.PENDING) {
            return new PlanVersionStatus(Kind.PENDING, List.of(), "Le serveur n'a pas encore répondu.",
                    audit.stale(), audit.version());
        }
        BuildingPlanVerdict verdict = BuildingPlanVerdict.from(audit.details(),
                audit.row().map(AgentActionRow::resultMessage).orElse(""));
        if (!verdict.available()) {
            return new PlanVersionStatus(Kind.UNCHECKED, List.of(), "La vérification n'a rendu aucun verdict"
                    + audit.row().map(AgentActionRow::resultMessage).filter(m -> m != null && !m.isBlank())
                    .map(m -> " : " + m).orElse("."), audit.stale(), audit.version());
        }
        if (verdict.accepted()) {
            return new PlanVersionStatus(Kind.CONFORMING, List.of(), "Contrôles techniques réussis.",
                    audit.stale(), audit.version());
        }
        List<String> codes = verdict.codes().isEmpty() ? List.of(verdict.code()) : verdict.codes();
        Kind kind = AcceptableDefects.of(audit.details()).isEmpty() ? Kind.BLOCKED : Kind.ACCEPTABLE;
        String text = PlanVerdictText.blocking(verdict).get(0).text();
        if (kind == Kind.ACCEPTABLE) {
            text = text + " Enregistrable en acceptant ces défauts.";
        }
        return new PlanVersionStatus(kind, codes, text, audit.stale(), audit.version());
    }
}
