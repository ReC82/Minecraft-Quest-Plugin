package com.lodygames.rpgquest.encounter.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Une <strong>occurrence</strong> de PNJ récurrent (issue #13) : le lieu stable d'une rencontre
 * dans une instance de biome, persistant et partagé par tous les joueurs. Au plus une par instance
 * ({@code (world, biomeInstance)} est unique en base).
 *
 * <ul>
 *   <li>{@code id} : identifiant déterministe de l'instance ({@link #idFor}) — jamais celui d'un
 *       waypoint, même si les deux partagent la même instance de biome ;</li>
 *   <li>{@code templateId}/{@code npcId} : modèle d'origine et identité RPGQuest du PNJ. L'identité
 *       est recopiée ici pour que le dialogue continue de répondre même si le modèle est renommé ou
 *       retiré ;</li>
 *   <li>{@code x}/{@code y}/{@code z} : position des pieds du PNJ ;</li>
 *   <li>{@code citizensUuid}/{@code citizensId} : l'entité Citizens actuelle ({@code null}/-1 tant
 *       qu'aucune n'existe) — remplacée, jamais multipliée, quand elle doit être réparée ;</li>
 *   <li>{@code status} : {@link Status#ACTIVE} ou {@link Status#DISABLED} (désactivée par un
 *       administrateur : PNJ retiré, l'instance ne génère plus rien) ;</li>
 *   <li>{@code lastError} : dernier diagnostic, vide si tout va bien.</li>
 * </ul>
 */
public record EncounterSite(String id, String world, String biomeInstance, String biomeKey, long regionX,
                            long regionZ, String templateId, String npcId, int x, int y, int z,
                            UUID citizensUuid, int citizensId, Status status, String lastError,
                            Instant createdAt, Instant updatedAt) {

    public enum Status { ACTIVE, DISABLED }

    public EncounterSite {
        lastError = lastError == null ? "" : lastError;
    }

    /** Identifiant d'occurrence d'une instance de biome : {@code enc_<monde>_<biome>_<rx>_<rz>}. */
    public static String idFor(String world, String biomeKey, long regionX, long regionZ) {
        int colon = biomeKey.indexOf(':');
        String biome = colon >= 0 ? biomeKey.substring(colon + 1) : biomeKey;
        return "enc_" + sanitize(world) + "_" + sanitize(biome) + "_" + regionX + "_" + regionZ;
    }

    public boolean active() {
        return status == Status.ACTIVE;
    }

    public boolean hasCitizens() {
        return citizensUuid != null;
    }

    public EncounterSite withCitizens(UUID uuid, int numericId, Instant at) {
        return new EncounterSite(id, world, biomeInstance, biomeKey, regionX, regionZ, templateId, npcId, x, y, z,
                uuid, uuid == null ? -1 : numericId, status, "", createdAt, at);
    }

    public EncounterSite withStatus(Status newStatus, Instant at) {
        return new EncounterSite(id, world, biomeInstance, biomeKey, regionX, regionZ, templateId, npcId, x, y, z,
                citizensUuid, citizensId, newStatus, lastError, createdAt, at);
    }

    public EncounterSite withError(String error, Instant at) {
        return new EncounterSite(id, world, biomeInstance, biomeKey, regionX, regionZ, templateId, npcId, x, y, z,
                citizensUuid, citizensId, status, error, createdAt, at);
    }

    private static String sanitize(String raw) {
        return raw.replaceAll("[^A-Za-z0-9]", "_");
    }
}
