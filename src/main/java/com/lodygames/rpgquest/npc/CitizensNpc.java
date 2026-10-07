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
 * @param shouldSpawn  intention <strong>persistante</strong> enregistrée par Citizens (trait
 *                  {@code Spawned}) : le PNJ doit-il être matérialisé dès qu'un joueur est à
 *                  portée. À ne pas confondre avec {@code spawned}, qui est l'état <em>transitoire</em>
 *                  du moment : sans joueur à proximité, Citizens dématérialise ses PNJ et
 *                  {@code spawned} vaut légitimement {@code false} alors que tout va bien.
 * @param chunkLoaded le chunk de la position connue est-il chargé — explique à lui seul la plupart
 *                  des cas où {@code spawned} est faux.
 * @param liveLocation {@code true} = position lue sur l'entité réellement présente en jeu ;
 *                  {@code false} = <strong>dernière position enregistrée</strong> par Citizens pour
 *                  un PNJ non apparu. La distinction compte : une position enregistrée peut être
 *                  ancienne, et ne prouve aucune présence actuelle.
 * @param lookClose état réel du trait {@code lookclose} (issue #165), ou {@code null} si la build
 *                  Citizens installée ne l'expose pas. {@code null} veut dire « inconnu », jamais
 *                  « désactivé » : le panel doit pouvoir faire la différence.
 * @param wander    état réel de la promenade (issue #165), ou {@code null} pour la même raison.
 */
public record CitizensNpc(int numericId, UUID uuid, String name, boolean spawned,
                          String world, double x, double y, double z, float yaw, float pitch,
                          boolean liveLocation, boolean shouldSpawn, boolean chunkLoaded,
                          LookCloseState lookClose, WanderState wander) {

    /**
     * Entrée de registre dont Citizens n'expose <strong>aucune</strong> position exploitable.
     * Utilisé par les chemins qui n'ont pas besoin de la position (liaison, spawn) et par les tests.
     */
    public CitizensNpc(int numericId, UUID uuid, String name, boolean spawned) {
        this(numericId, uuid, name, spawned, null, 0, 0, 0, 0f, 0f, false, spawned, false, null, null);
    }

    /** Même entrée, enrichie des comportements relus sur Citizens. */
    public CitizensNpc withBehaviour(LookCloseState look, WanderState walk) {
        return new CitizensNpc(numericId, uuid, name, spawned, world, x, y, z, yaw, pitch,
                liveLocation, shouldSpawn, chunkLoaded, look, walk);
    }

    /** Vrai si Citizens expose une position exploitable pour ce PNJ. */
    public boolean hasLocation() {
        return world != null && !world.isBlank();
    }
}
