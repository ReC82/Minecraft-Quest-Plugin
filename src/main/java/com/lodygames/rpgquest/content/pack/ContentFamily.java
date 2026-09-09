package com.lodygames.rpgquest.content.pack;

import java.util.List;
import java.util.Optional;

/**
 * Familles de contenu déclaratif exportables (issue #108). L'ordre de l'énumération est l'ordre
 * canonique des sections {@code content:} du pack.
 *
 * <p>Ajouter une famille (recettes, objets, …) = ajouter une constante ici + un cas dans
 * {@code ContentPackMapper} / {@code ContentPackAssembler} / {@code ContentPackSerializer} — aucune
 * autre partie du pipeline ne connaît la liste en dur.</p>
 */
public enum ContentFamily {

    QUESTS("quests"),
    STORIES("stories"),
    DIALOGUES("dialogues"),
    NPCS("npcs");

    private final String wire;

    ContentFamily(String wire) {
        this.wire = wire;
    }

    /** Clé stable utilisée dans le manifest, les sections {@code content:} et le paramètre d'action. */
    public String wire() {
        return wire;
    }

    public static Optional<ContentFamily> fromWire(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        for (ContentFamily f : values()) {
            if (f.wire.equals(v)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    public static List<String> allWire() {
        return java.util.Arrays.stream(values()).map(ContentFamily::wire).toList();
    }
}
