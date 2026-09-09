package com.lodygames.rpgquest.content.pack;

/**
 * Entrée « PNJ logique » d'un content pack (issue #108) — miroir déclaratif de
 * {@link com.lodygames.rpgquest.npc.model.NpcDefinition}. Indépendant de Citizens, du monde et de
 * toute position : uniquement l'identité éditoriale du PNJ et son dialogue associé
 * ({@code dialogue} = id de dialogue, relation préservée). {@code description} / {@code dialogue} /
 * {@code role} valent {@code null} quand ils ne sont pas déclarés.
 */
public record NpcPackEntry(String id, String displayName, String description, String dialogue,
                           String role, boolean enabled) {
}
