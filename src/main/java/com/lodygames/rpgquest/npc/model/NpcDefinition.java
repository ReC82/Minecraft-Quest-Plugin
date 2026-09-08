package com.lodygames.rpgquest.npc.model;

import java.util.regex.Pattern;

/**
 * Définition <strong>logique</strong> d'un PNJ RPGQuest (V2 déclarative du système PNJ) :
 * totalement indépendante de Citizens, du monde et du binding physique. Elle peut exister sans
 * qu'aucune entité Citizens ne soit encore taguée — c'est le point de la V2.
 *
 * <p>Stockée un fichier par PNJ sous {@code plugins/RPGQuest/npcs/*.yml} (même pattern que
 * {@code quests/}, {@code dialogues/}, {@code stories/}…). Modèle volontairement minimal :
 * {@code id} + {@code displayName} + {@code dialogueId} + {@code enabled}, plus deux champs
 * facultatifs sans effet mécanique ({@code description}, {@code role}) pour l'admin.</p>
 *
 * <p>Aucune dépendance Bukkit : {@code dialogueId} est une chaîne brute (ex. {@code rpgquest:guard}),
 * validée par {@code NpcDefinitionParser}, jamais un {@code NamespacedKey}.</p>
 */
public record NpcDefinition(String id, String displayName, String description, String dialogueId,
                            String role, boolean enabled) {

    /** Fragment d'id RPGQuest : minuscules, chiffres, {@code . _ -} (mêmes règles qu'une clé Bukkit simple). */
    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9._-]{1,64}");

    public NpcDefinition {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("id de PNJ invalide : « " + id + " » (attendu : " + ID_PATTERN.pattern() + ").");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName ne peut pas être vide (PNJ « " + id + " »).");
        }
        description = blankToNull(description);
        dialogueId = blankToNull(dialogueId);
        role = blankToNull(role);
    }

    /** {@code true} si un id est un fragment de clé RPGQuest valide. */
    public static boolean isValidId(String candidate) {
        return candidate != null && ID_PATTERN.matcher(candidate).matches();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
