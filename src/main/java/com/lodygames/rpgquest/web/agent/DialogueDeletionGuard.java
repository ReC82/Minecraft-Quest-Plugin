package com.lodygames.rpgquest.web.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ce qui empêche de supprimer un dialogue <strong>sur le serveur</strong> (issue #248).
 *
 * <p>Le Control Panel fait son propre aperçu, mais sur un relevé qui peut dater. Le serveur
 * revérifie donc, au moment d'exécuter, sur ce qu'il a réellement chargé :</p>
 *
 * <ul>
 *   <li><strong>un PNJ logique</strong> lié au dialogue (déclaration {@code dialogue:} d'une
 *       définition, ou convention {@code rpgquest:<id du PNJ>}) : le PNJ resterait muet ;</li>
 *   <li><strong>un autre dialogue</strong> dont un choix fait {@code OPEN_DIALOGUE} vers lui :
 *       {@code DialogueLoader} refuserait ce dialogue au prochain rechargement, et la suppression
 *       d'un contenu en casserait un autre.</li>
 * </ul>
 *
 * <p>Les guides de Hub sont vérifiés à part, sur le disque, par
 * {@link com.lodygames.rpgquest.content.ContentDefinitionDeleter}. Classe pure : aucune
 * dépendance à Bukkit.</p>
 */
final class DialogueDeletionGuard {

    private DialogueDeletionGuard() {
    }

    /** Les références qui bloquent, en phrases lisibles ; vide = rien ne s'y oppose. */
    static List<String> blockers(String rawId, List<AgentActions.DialogueSummary> dialogues) {
        String wanted = key(rawId);
        List<String> out = new ArrayList<>();
        for (AgentActions.DialogueSummary d : dialogues) {
            if (key(d.id()).equals(wanted)) {
                if (!d.linkedNpcIds().isEmpty()) {
                    out.add("PNJ lié(s) : " + String.join(", ", d.linkedNpcIds()));
                }
                continue;
            }
            for (AgentActions.DialogueNodeSummary node : d.nodes()) {
                for (AgentActions.DialogueChoiceSummary choice : node.choices()) {
                    for (AgentActions.DialogueActionSummary action : choice.actions()) {
                        if ("OPEN_DIALOGUE".equals(action.kind()) && key(action.target()).equals(wanted)) {
                            out.add("ouvert par le dialogue " + d.id() + " (nœud « " + node.id() + " »)");
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Identifiant comparable : minuscules, sans le namespace {@code rpgquest:} implicite. */
    static String key(String raw) {
        if (raw == null) {
            return "";
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }
}
