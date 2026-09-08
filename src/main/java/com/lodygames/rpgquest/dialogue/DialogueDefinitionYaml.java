package com.lodygames.rpgquest.dialogue;

import com.lodygames.rpgquest.dialogue.model.DialogueDraft;

/**
 * Rendu d'un {@link DialogueDraft} en texte YAML <strong>déterministe</strong>, re-parsable à
 * l'identique par {@link DialogueDefinitionParser} — issue « V1 /dialogues ». Purement fonctionnel :
 * aucune dépendance Bukkit, aucun accès disque.
 *
 * <p>Le Control Panel n'envoie <strong>jamais</strong> de YAML brut : seuls des champs métier
 * validés arrivent dans le {@link DialogueDraft}, et c'est cette classe qui produit le fichier.</p>
 */
public final class DialogueDefinitionYaml {

    private DialogueDefinitionYaml() {
    }

    public static String render(DialogueDraft d) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Dialogue RPGQuest — généré via le Control Panel (squelette éditable).\n");
        sb.append("# L'édition fine (nœuds, choix conditionnels, actions) passera par l'éditeur dédié.\n");
        sb.append("id: ").append(d.id()).append('\n');
        sb.append("start: ").append(d.startNodeId()).append('\n');
        sb.append("nodes:\n");
        for (DialogueDraft.Node node : d.nodes()) {
            sb.append("  ").append(node.id()).append(":\n");
            sb.append("    speaker: ").append(quote(node.speaker())).append('\n');
            sb.append("    text: ").append(quote(node.text())).append('\n');
            sb.append("    choices:\n");
            for (DialogueDraft.Choice choice : node.choices()) {
                sb.append("      - text: ").append(quote(choice.text())).append('\n');
                if (choice.close()) {
                    sb.append("        actions:\n");
                    sb.append("          - type: CLOSE\n");
                } else if (choice.nextNodeId() != null && !choice.nextNodeId().isBlank()) {
                    sb.append("        next: ").append(choice.nextNodeId()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** Toujours entre guillemets doubles : le texte peut contenir des espaces, du MiniMessage, « : »… */
    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
