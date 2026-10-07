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
 * @param world     monde de la position connue, ou {@code null} si Citizens n'en connaît aucune.
 * @param x         coordonnées de la position connue (0 si {@code world == null}).
 * @param yaw       orientation horizontale, {@code pitch} verticale (0 si {@code world == null}).
 * @param liveLocation {@code true} = position lue sur l'entité réellement présente en jeu ;
 *                  {@code false} = <strong>dernière position enregistrée</strong> par Citizens pour
 *                  un PNJ non apparu. La distinction compte : une position enregistrée peut être
 *                  ancienne, et ne prouve aucune présence actuelle.
 */
public record CitizensNpc(int numericId, UUID uuid, String name, boolean spawned,
                          String world, double x, double y, double z, float yaw, float pitch,
                          boolean liveLocation) {

    /**
     * Entrée de registre dont Citizens n'expose <strong>aucune</strong> position exploitable.
     * Utilisé par les chemins qui n'ont pas besoin de la position (liaison, spawn) et par les tests.
     */
    public CitizensNpc(int numericId, UUID uuid, String name, boolean spawned) {
        this(numericId, uuid, name, spawned, null, 0, 0, 0, 0f, 0f, false);
    }

    /** Vrai si Citizens expose une position exploitable pour ce PNJ. */
    public boolean hasLocation() {
        return world != null && !world.isBlank();
    }
}
