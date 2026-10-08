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
        String npc = str(summary.get("npc"));

        return switch (kind) {
            case "KILL_ENTITY" -> countable("Tuer", MinecraftNames.humanize(target), amount, target);
            case "COLLECT_ITEM" -> countable("Collecter", MinecraftNames.humanize(target), amount, target);
            case "CRAFT_ITEM" -> countable("Fabriquer", MinecraftNames.humanize(target), amount, target);
            case "SMELT_ITEM" -> countable("Cuire", MinecraftNames.humanize(target), amount, target);
            case "BREAK_BLOCK" -> countable("Casser", MinecraftNames.humanize(target), amount, target);
            case "PLACE_BLOCK" -> countable("Placer", MinecraftNames.humanize(target), amount, target);
            case "TALK_TO_NPC" -> new Objective("Parler à " + prettyNpc(target), target);
            // #123 : deux cibles — l'objet compté (« target ») et le PNJ qui le reçoit (« npc »).
            // Le libellé nomme les deux, sinon « Rapporter du cuir » ne dirait pas à qui.
            case "DELIVER_ITEM_TO_NPC" -> {
                String item = MinecraftNames.humanize(target);
                // Un destinataire réellement absent est NOMMÉ comme tel : « à ? » laissait croire à
                // un défaut d'affichage alors que la définition serait, elle, incomplète.
                String receiver = npc.isBlank() ? "(PNJ non défini)" : prettyNpc(npc);
                String label = "Rapporter " + item + (amount > 1 ? " (x" + amount + ")" : "")
                        + " à " + receiver;
                yield new Objective(label, target);
            }
            case "REACH_LOCATION" -> new Objective("Se rendre dans " + prettyNpc(target), target);
            // Issue #185 : la portée ET la règle de comptage apparaissent dans le résumé. Le ticket
            // interdit de les laisser implicites — elles changent ce que le joueur doit faire.
            case "DISCOVER_WAYPOINT" -> {
                String scope = worldScope(summary.get("worlds"));
                boolean cumulative = "INCLUDE_EXISTING".equalsIgnoreCase(str(summary.get("countMode")));
                yield new Objective("Découvrir " + amount + " waypoint(s) — " + scope
                        + (cumulative ? " — découvertes déjà acquises incluses" : " — nouvelles découvertes"),
                        null);
            }
            default -> {
                String raw = str(summary.get("raw"));
                yield new Objective(raw.isEmpty()
                        ? MiniText.prettifyId(kind.isEmpty() ? "objectif" : kind)
                        : MinecraftNames.humanizeTokens(raw), null);
            }
        };
    }

    /**
     * Portée affichée d'un {@code DISCOVER_WAYPOINT} (issue #185). La valeur arrive soit en liste
     * (relevé runtime de l'agent), soit en texte « a, b » (brouillon relu depuis le YAML) : les deux
     * origines doivent produire exactement le même libellé.
     */
    private static String worldScope(Object raw) {
        String joined;
        if (raw instanceof java.util.Collection<?> list) {
            joined = list.stream().map(x -> x == null ? "" : String.valueOf(x).trim())
                    .filter(x -> !x.isEmpty()).collect(java.util.stream.Collectors.joining(", "));
        } else {
            joined = str(raw).trim();
        }
        return joined.isEmpty() ? "tous mondes" : joined;
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
