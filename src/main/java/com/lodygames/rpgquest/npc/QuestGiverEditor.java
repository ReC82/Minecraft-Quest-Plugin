package com.lodygames.rpgquest.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Édition <strong>minimale et non destructive</strong> du champ {@code giver:} d'un fichier YAML de
 * quête : transformation de texte pure (aucun parseur YAML complet, aucun accès disque), qui
 * préserve tout le reste du fichier — commentaires, ordre des clés, indentation, style de fin de
 * ligne.
 *
 * <p>C'est volontairement <strong>plus prudent</strong> qu'un {@code YamlConfiguration.save()}, qui
 * réécrirait tout le fichier et perdrait les commentaires. Seule la ligne racine {@code giver:} est
 * touchée : remplacée si présente, sinon insérée juste après la ligne {@code category:} (à défaut
 * après {@code id:}, à défaut en tête).</p>
 */
public final class QuestGiverEditor {

    private static final Pattern GIVER = Pattern.compile("^giver:\\s*.*$");
    private static final Pattern CATEGORY = Pattern.compile("^category:\\s*.*$");
    private static final Pattern ID = Pattern.compile("^id:\\s*.*$");

    private QuestGiverEditor() {
    }

    /**
     * @return le contenu avec {@code giver: <npcId>} posé (remplacé ou inséré). Idempotent :
     *         ré-appliquer avec le même {@code npcId} redonne le même texte.
     */
    public static String setGiver(String content, String npcId) {
        if (npcId == null || npcId.isBlank()) {
            throw new IllegalArgumentException("npcId ne peut pas être vide.");
        }
        String newline = content.contains("\r\n") ? "\r\n" : "\n";
        boolean trailingNewline = content.endsWith("\n") || content.endsWith("\r");
        List<String> lines = new ArrayList<>(List.of(content.split("\r\n|\r|\n", -1)));
        // split(-1) laisse un dernier élément vide si le fichier finissait par un saut de ligne.
        if (trailingNewline && !lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }

        String giverLine = "giver: " + npcId.trim();
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            if (GIVER.matcher(lines.get(i)).matches()) {
                lines.set(i, giverLine);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            int anchor = firstMatch(lines, CATEGORY);
            if (anchor < 0) {
                anchor = firstMatch(lines, ID);
            }
            lines.add(anchor + 1, giverLine); // anchor == -1 -> insertion en tête (index 0)
        }

        String joined = String.join(newline, lines);
        return trailingNewline ? joined + newline : joined;
    }

    private static int firstMatch(List<String> lines, Pattern pattern) {
        for (int i = 0; i < lines.size(); i++) {
            if (pattern.matcher(lines.get(i)).matches()) {
                return i;
            }
        }
        return -1;
    }
}
