package com.lodygames.rpgquest.npc;

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
     * @param citizensUuid      {@code NPC#getUniqueId()} — clé de persistance (stable entre redémarrages).
     * @param citizensNumericId {@code NPC#getId()} — id numérique affiché aux admins (celui utilisé par
     *                          les commandes Citizens elles-mêmes), non garanti stable par Citizens : jamais utilisé comme clé.
     */
    record Ref(UUID citizensUuid, int citizensNumericId) {
    }
}
