package com.lodygames.rpgquest.panel.building;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.building.BuildingPlanWorkbench.CheckState;
import com.lodygames.rpgquest.panel.building.BuildingPlanWorkbench.Status;
import java.util.Optional;
import java.util.Set;

/**
 * L'échec du dernier « Enregistrer dans la bibliothèque » de la version courante (#292).
 *
 * <p>La Maison de Lily affichait un bandeau vert « conforme » pendant que l'enregistrement avait
 * échoué ({@code SCHEMATIC_FAILED}, {@code NoMatchException} sur {@code minecraft:bed}) : le motif
 * n'était visible que dans l'historique des actions. L'Atelier lit maintenant le résultat lui-même
 * et dit quoi faire, selon la nature de l'échec :</p>
 *
 * <ul>
 *   <li><strong>contenu</strong> — le plan doit changer : le motif part de lui-même vers l'IA ;</li>
 *   <li><strong>conflit</strong> — un autre bâtiment porte cet identifiant ;</li>
 *   <li><strong>écriture</strong> — le plan avait passé ses contrôles, c'est le serveur (disque,
 *       WorldEdit, bibliothèque) qui n'a pas abouti : réessayer, sans rien demander à l'IA.</li>
 * </ul>
 *
 * @param code    le code exact du plugin ({@code SCHEMATIC_FAILED}, {@code PLAN_INVALID}…)
 * @param message le motif exact renvoyé par le serveur
 */
public record SaveFailure(String code, String message, Kind kind) {

    public enum Kind { CONTENT, CONFLICT, WRITE }

    private static final Set<String> CONTENT = Set.of("PLAN_INVALID", "ARCHITECTURE_REFUSED",
            "BLOCK_REJECTED", "DEFINITION_INVALID");

    /** Vide si la version courante n'a pas d'enregistrement en échec. */
    public static Optional<SaveFailure> of(CheckState generate) {
        if (generate == null || !generate.current() || generate.status() != Status.DONE) {
            return Optional.empty();
        }
        Optional<AgentActionRow> row = generate.row();
        if (row.isEmpty() || "SUCCESS".equals(row.get().resultStatus())) {
            return Optional.empty();
        }
        Object detailed = generate.details().get("message");
        String message = detailed instanceof String s && !s.isBlank() ? s
                : row.get().resultMessage() == null ? "" : row.get().resultMessage();
        Object detailedCode = generate.details().get("code");
        String code = detailedCode instanceof String s && !s.isBlank() ? s
                : row.get().resultValue() == null || row.get().resultValue().isBlank() ? "FAILED"
                        : row.get().resultValue();
        Kind kind = CONTENT.contains(code) ? Kind.CONTENT
                : "ALREADY_EXISTS".equals(code) ? Kind.CONFLICT : Kind.WRITE;
        return Optional.of(new SaveFailure(code, message, kind));
    }

    /** La ligne ajoutée au diagnostic envoyé à l'IA — seulement pour un échec de contenu. */
    public Optional<String> forCorrection() {
        return kind == Kind.CONTENT
                ? Optional.of("L'enregistrement de cette version a ÉCHOUÉ (" + code + ") : " + message)
                : Optional.empty();
    }
}
