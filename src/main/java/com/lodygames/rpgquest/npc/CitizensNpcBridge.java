package com.lodygames.rpgquest.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.SpawnReason;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

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

    // ---- Création + rollback (issue #81, phase 2) --------------------------------------------

    /**
     * Crée un PNJ Citizens de type {@code PLAYER} nommé {@code name} et le fait apparaître à
     * {@code location}. <strong>Thread principal obligatoire</strong> (API Citizens + monde).
     * Renvoie vide si Citizens n'a pas pu le matérialiser — dans ce cas le PNJ à moitié créé est
     * détruit avant de rendre la main (aucun résidu).
     */
    Optional<CitizensNpc> createAndSpawn(String name, Location location) {
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, name);
        try {
            boolean spawned = npc.spawn(location, SpawnReason.CREATE);
            if (!spawned || !npc.isSpawned()) {
                npc.destroy();
                return Optional.empty();
            }
            CitizensAPI.getNPCRegistry().saveToStore();
            return Optional.of(toSummary(npc));
        } catch (RuntimeException e) {
            safeDestroy(npc);
            throw e;
        }
    }

    /**
     * Détruit définitivement un PNJ Citizens — <strong>réservé au rollback</strong> d'une création
     * qui vient d'échouer. La double clé ({@code uuid} + {@code numericId}) garantit qu'on ne
     * détruit que le PNJ visé, jamais un homonyme. Thread principal obligatoire.
     *
     * @return {@code true} si le PNJ correspondait et a été détruit.
     */
    boolean destroyIfMatches(int numericId, UUID uuid) {
        NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(uuid);
        if (npc == null || npc.getId() != numericId) {
            return false;
        }
        npc.destroy();
        CitizensAPI.getNPCRegistry().saveToStore();
        return true;
    }

    private static void safeDestroy(NPC npc) {
        try {
            npc.destroy();
        } catch (RuntimeException ignored) {
            // Rien de mieux à faire : l'exception d'origine est relancée par l'appelant.
        }
    }

    /**
     * @param citizensUuid      {@code NPC#getUniqueId()} — clé de persistance (stable entre redémarrages).
     * @param citizensNumericId {@code NPC#getId()} — id numérique affiché aux admins (celui utilisé par
     *                          les commandes Citizens elles-mêmes), non garanti stable par Citizens : jamais utilisé comme clé.
     */
    record Ref(UUID citizensUuid, int citizensNumericId) {
    }
}
