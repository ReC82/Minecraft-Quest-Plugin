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

    /**
     * Issue #165 — change le <strong>nom affiché en jeu</strong> d'un PNJ Citizens, ciblé par son
     * UUID (identité stable), jamais par son nom ni par une sélection globale.
     *
     * <p>Ne touche <strong>que</strong> le nom Citizens : l'id numérique, l'UUID, l'id logique
     * RPGQuest et toutes les liaisons quêtes/dialogues/stories sont ailleurs et restent intacts.
     * Renommer « Help » ne renomme donc jamais l'id logique {@code help}.</p>
     *
     * @return l'ancien nom si le PNJ existait, sinon vide (aucun autre PNJ n'est touché).
     */
    Optional<String> renameByUuid(UUID uuid, String newName) {
        NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(uuid);
        if (npc == null) {
            return Optional.empty();
        }
        String previous = npc.getName();
        npc.setName(newName);
        CitizensAPI.getNPCRegistry().saveToStore();
        return Optional.of(previous);
    }

    /**
     * Issue #165 — applique un skin à partir d'une URL MineSkin, via la commande structurée de
     * Citizens ({@code /npc skin --url …}).
     *
     * <p><strong>Pourquoi la commande et pas l'API.</strong> {@code SkinTrait} n'existe pas dans
     * l'artefact {@code citizensapi} auquel ce projet se limite délibérément (il vit dans
     * {@code citizens-main}) : l'utiliser imposerait soit une nouvelle dépendance, soit de la
     * réflexion, soit un couplage aux clés de métadonnées internes de Citizens. La commande
     * structurée est le chemin d'intégration officiel et c'est exactement celui que MineSkin
     * documente.</p>
     *
     * <p><strong>Ciblage sûr.</strong> Le danger d'une commande Citizens est qu'elle s'applique au
     * PNJ <em>sélectionné</em> par l'exécutant. On ne s'en remet donc jamais à une sélection
     * préexistante : on sélectionne explicitement le PNJ visé (par son UUID) pour la console juste
     * avant, et on désélectionne juste après, le tout dans le même passage sur le thread principal
     * — deux administrateurs simultanés ne peuvent pas s'intercaler entre les deux.</p>
     *
     * <p>Le téléchargement du skin est fait par Citizens lui-même, de façon asynchrone : cette
     * méthode ne bloque pas le thread principal et ne peut donc pas confirmer l'application
     * visuelle, seulement la bonne prise en compte de la demande.</p>
     */
    boolean applySkinUrlByUuid(UUID uuid, String minesSkinUrl) {
        NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(uuid);
        if (npc == null) {
            return false;
        }
        var console = org.bukkit.Bukkit.getConsoleSender();
        var selector = CitizensAPI.getDefaultNPCSelector();
        try {
            selector.select(console, npc);
            return org.bukkit.Bukkit.dispatchCommand(console, "npc skin --url " + minesSkinUrl);
        } finally {
            selector.deselect(console);
        }
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
