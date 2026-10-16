package com.lodygames.rpgquest.mob.quest;

import java.util.UUID;

/**
 * Issue #171 — boss de quête actif, possédé par UN joueur pour UN objectif précis. Persisté dans
 * {@code state/quest-bosses.yml} (aucune table, aucune migration) et rappelé par une PDC sur l'entité
 * ({@link QuestBossService#INSTANCE_KEY_NAME} = {@link #key()}).
 *
 * <p>L'identité métier est la clé {@code joueur | quête | étape | objectif} : tuer un autre boss du
 * même profil, ou celui d'un autre joueur, ne la touche jamais. Une seule instance par clé.</p>
 *
 * <p>La position est la <em>dernière connue</em> (rafraîchie tant que le chunk est chargé et au
 * déchargement) ; le waypoint et le biome sont ceux de l'apparition — l'indication donnée au joueur.</p>
 */
public record QuestBossInstance(UUID entityId, UUID owner, String questId, String stepId, int objectiveIndex,
                                String profileId, String profileName, String world, int x, int y, int z,
                                String biome, String waypointId, String waypointName, int waypointDistance,
                                String waypointDirection, long spawnedAtMillis) {

    public QuestBossInstance {
        if (entityId == null || owner == null || questId == null || questId.isBlank() || stepId == null
                || stepId.isBlank() || objectiveIndex < 0 || profileId == null || profileId.isBlank()
                || world == null || world.isBlank()) {
            throw new IllegalArgumentException("Instance de boss de quête incomplète.");
        }
        profileName = profileName == null || profileName.isBlank() ? profileId : profileName;
        biome = biome == null ? "" : biome;
    }

    /** Clé d'identité métier, stable : {@code <uuid joueur>|<quête>|<étape>|<index objectif>}. */
    public String key() {
        return key(owner, questId, stepId, objectiveIndex);
    }

    public static String key(UUID owner, String questId, String stepId, int objectiveIndex) {
        return owner + "|" + questId + "|" + stepId + "|" + objectiveIndex;
    }

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    public boolean inChunk(String worldName, int cx, int cz) {
        return world.equals(worldName) && chunkX() == cx && chunkZ() == cz;
    }

    public QuestBossInstance movedTo(String newWorld, int nx, int ny, int nz) {
        return new QuestBossInstance(entityId, owner, questId, stepId, objectiveIndex, profileId, profileName,
                newWorld, nx, ny, nz, biome, waypointId, waypointName, waypointDistance, waypointDirection,
                spawnedAtMillis);
    }
}
