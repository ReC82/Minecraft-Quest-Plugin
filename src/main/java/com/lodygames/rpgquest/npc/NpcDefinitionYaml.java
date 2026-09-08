package com.lodygames.rpgquest.npc;

import com.lodygames.rpgquest.npc.model.NpcDefinition;

/**
 * Rendu d'une {@link NpcDefinition} en texte YAML déterministe (un fichier par PNJ). Purement
 * fonctionnel — aucune dépendance Bukkit, aucun accès disque : {@code render()} est testable seul
 * et son résultat se re-parse à l'identique avec {@link NpcDefinitionParser}.
 *
 * <p>Le Control Panel n'envoie <strong>jamais</strong> de YAML brut : seuls des champs métier
 * validés arrivent ici, et c'est cette classe qui produit le fichier.</p>
 */
public final class NpcDefinitionYaml {

    private NpcDefinitionYaml() {
    }

    public static String render(NpcDefinition d) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Définition logique de PNJ RPGQuest — générée / éditée via le Control Panel.\n");
        sb.append("# Indépendante de Citizens : ce PNJ peut exister sans entité taguée en jeu.\n");
        sb.append("id: ").append(d.id()).append('\n');
        sb.append("display_name: ").append(quote(d.displayName())).append('\n');
        if (d.description() != null) {
            sb.append("description: ").append(quote(d.description())).append('\n');
        }
        if (d.dialogueId() != null) {
            sb.append("dialogue: ").append(d.dialogueId()).append('\n');
        }
        if (d.role() != null) {
            sb.append("role: ").append(d.role()).append('\n');
        }
        sb.append("enabled: ").append(d.enabled()).append('\n');
        return sb.toString();
    }

    /** Toujours entre guillemets doubles : le nom d'affichage peut contenir des espaces, du MiniMessage, « : »… */
    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
