package com.lodygames.rpgquest.npc;

import java.util.UUID;

/**
 * Vue admin d'un PNJ Citizens issue du <strong>registre Citizens</strong> (jamais d'un scan
 * d'entités Minecraft) — issue #81.
 *
 * @param numericId {@code NPC#getId()} — affiché aux admins, utilisé par les commandes Citizens ;
 *                  non garanti stable entre sessions par Citizens, donc jamais utilisé comme clé.
 * @param uuid      {@code NPC#getUniqueId()} — clé de persistance stable ({@code npc_citizens_bindings}).
 * @param name      nom affiché Citizens (peut contenir des codes couleur).
 * @param spawned   le PNJ est-il actuellement matérialisé dans un monde.
 */
public record CitizensNpc(int numericId, UUID uuid, String name, boolean spawned) {
}
