package com.lodygames.rpgquest.panel.web;

import java.util.Locale;
import java.util.Map;

/**
 * Lecture <em>admin</em> des objectifs de quête <strong>structurés</strong> (#78).
 *
 * <p>Depuis l'évolution du protocole {@code quest.list}, chaque objectif d'étape arrive sous forme
 * {@code {kind, target, amount, raw}} (voir {@code AgentActions.ObjectiveSummary}). Ce helper en
 * tire un libellé français sans aucune regex sur une phrase métier : le verbe vient de
 * {@code kind}, le nom de la cible de {@link MinecraftNames} (matériaux / entités) ou de
 * {@link MiniText#prettifyId(String)} (PNJ, monde), la quantité de {@code amount}.</p>
 *
 * <p>Le jeton technique ({@code target}) reste disponible en secondaire pour l'admin
 * (« IDs techniques conservés », cohérent avec #74). Le repli sur les chaînes déjà formatées
 * ({@code QuestObjective.describe}) reste géré séparément par {@link MinecraftNames#humanizeTokens}
 * tant que l'agent déployé ne renvoie pas encore la structure.</p>
 */
public final class ObjectiveText {

    private ObjectiveText() {
    }

    /**
     * @param label     libellé lisible (jamais vide) — ex. « Tuer Araignée (x5) »
     * @param rawTarget jeton technique de la cible à garder consultable, ou {@code null} si le
     *                  {@code kind} n'a pas de cible pertinente
     */
    public record Objective(String label, String rawTarget) {
        boolean hasTarget() {
            return rawTarget != null && !rawTarget.isBlank();
        }
    }

    /** Construit le libellé depuis une ligne {@code objectiveDetails} du payload {@code quest.list}. */
    public static Objective fromSummary(Map<String, Object> summary) {
        String kind = str(summary.get("kind")).toUpperCase(Locale.ROOT);
        String target = str(summary.get("target"));
        int amount = asInt(summary.get("amount"));

        return switch (kind) {
            case "KILL_ENTITY" -> countable("Tuer", MinecraftNames.humanize(target), amount, target);
            case "COLLECT_ITEM" -> countable("Collecter", MinecraftNames.humanize(target), amount, target);
            case "CRAFT_ITEM" -> countable("Fabriquer", MinecraftNames.humanize(target), amount, target);
            case "BREAK_BLOCK" -> countable("Casser", MinecraftNames.humanize(target), amount, target);
            case "PLACE_BLOCK" -> countable("Placer", MinecraftNames.humanize(target), amount, target);
            case "TALK_TO_NPC" -> new Objective("Parler à " + prettyNpc(target), target);
            case "REACH_LOCATION" -> new Objective("Se rendre dans " + prettyNpc(target), target);
            default -> {
                String raw = str(summary.get("raw"));
                yield new Objective(raw.isEmpty()
                        ? MiniText.prettifyId(kind.isEmpty() ? "objectif" : kind)
                        : MinecraftNames.humanizeTokens(raw), null);
            }
        };
    }

    private static Objective countable(String verb, String name, int amount, String target) {
        String label = amount > 1 ? verb + " " + name + " (x" + amount + ")" : verb + " " + name;
        return new Objective(label, target);
    }

    private static String prettyNpc(String token) {
        return token == null || token.isBlank() ? "?" : MiniText.prettifyId(token);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int asInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
