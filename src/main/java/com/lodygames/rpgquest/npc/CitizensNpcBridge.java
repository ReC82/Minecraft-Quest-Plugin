package com.lodygames.rpgquest.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Entity;

/**
 * Isole toute référence à un type Citizens (import {@code net.citizensnpcs.*}).
 * Cette classe n'est instanciée — et donc jamais chargée par la JVM — que si
 * le plugin Citizens est réellement présent et actif, vérifié en amont par
 * {@link NpcIdentityService} via les seuls types Bukkit (
 * {@code Server#getPluginManager()}, jamais un type Citizens). C'est cette
 * séparation qui permet à RPGQuest de démarrer normalement sur un serveur où
 * Citizens n'est pas installé, malgré la dépendance {@code compileOnly}
 * (sinon {@code NoClassDefFoundError} au chargement du plugin).
 */
final class CitizensNpcBridge {

    boolean isNpc(Entity entity) {
        return CitizensAPI.getNPCRegistry().isNPC(entity);
    }

    /** Identifiant Citizens garanti stable ({@code NPC#getUniqueId()}), ou vide si {@code entity} n'est pas un PNJ Citizens. */
    Optional<UUID> citizensUniqueId(Entity entity) {
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(entity);
        return npc == null ? Optional.empty() : Optional.of(npc.getUniqueId());
    }

    Optional<Ref> resolve(Entity entity) {
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(entity);
        return npc == null ? Optional.empty() : Optional.of(new Ref(npc.getUniqueId(), npc.getId()));
    }

    /**
     * Parcourt le <strong>registre Citizens</strong> (pas les entités Minecraft, aucun chargement
     * de monde/chunk). À appeler sur le thread principal (API Citizens).
     */
    List<CitizensNpc> roster() {
        List<CitizensNpc> out = new ArrayList<>();
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            out.add(toSummary(npc));
        }
        return out;
    }

    /** Un PNJ Citizens par son id numérique ({@code NPC#getId()}), ou vide s'il n'existe pas. Thread principal. */
    Optional<CitizensNpc> byNumericId(int numericId) {
        NPC npc = CitizensAPI.getNPCRegistry().getById(numericId);
        return npc == null ? Optional.empty() : Optional.of(toSummary(npc));
    }

    private static CitizensNpc toSummary(NPC npc) {
        return new CitizensNpc(npc.getId(), npc.getUniqueId(), npc.getName(), npc.isSpawned());
    }

    /**
     * @param citizensUuid      {@code NPC#getUniqueId()} — clé de persistance (stable entre redémarrages).
     * @param citizensNumericId {@code NPC#getId()} — id numérique affiché aux admins (celui utilisé par
     *                          les commandes Citizens elles-mêmes), non garanti stable par Citizens : jamais utilisé comme clé.
     */
    record Ref(UUID citizensUuid, int citizensNumericId) {
    }
}
