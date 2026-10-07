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

    /**
     * Vue admin d'un PNJ Citizens, position comprise.
     *
     * <p>Deux sources de position, volontairement distinguées : l'entité réellement présente quand
     * le PNJ est apparu, sinon {@code NPC#getStoredLocation()} — la <strong>dernière position
     * enregistrée</strong> par Citizens, qui ne prouve aucune présence. Aucun monde n'est chargé et
     * aucun chunk n'est forcé : on ne lit que ce que Citizens a déjà en mémoire.</p>
     */
    private static CitizensNpc toSummary(NPC npc) {
        boolean spawned = npc.isSpawned();
        // Intention persistante, distincte de la présence du moment : Citizens dématérialise ses
        // PNJ quand aucun joueur n'est à portée, donc « pas spawné » n'est pas une anomalie.
        boolean shouldSpawn = shouldSpawn(npc, spawned);
        Location location = null;
        boolean live = false;
        if (spawned) {
            Entity entity = npc.getEntity();
            if (entity != null) {
                location = entity.getLocation();
                live = true;
            }
        }
        if (location == null) {
            location = npc.getStoredLocation();
        }
        if (location == null || location.getWorld() == null) {
            return new CitizensNpc(npc.getId(), npc.getUniqueId(), npc.getName(), spawned,
                    null, 0, 0, 0, 0f, 0f, false, shouldSpawn, false);
        }
        // Chargement du chunk : lu SANS le charger (isChunkLoaded), car sonder un PNJ ne doit
        // jamais forcer de génération de terrain.
        boolean chunkLoaded = location.getWorld().isChunkLoaded(
                location.getBlockX() >> 4, location.getBlockZ() >> 4);
        return new CitizensNpc(npc.getId(), npc.getUniqueId(), npc.getName(), spawned,
                location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(), live, shouldSpawn, chunkLoaded);
    }

    /** Trait public {@code Spawned} : l'intention enregistrée. Repli sur l'état courant si absent. */
    private static boolean shouldSpawn(NPC npc, boolean spawned) {
        try {
            net.citizensnpcs.api.trait.trait.Spawned trait =
                    npc.getTraitNullable(net.citizensnpcs.api.trait.trait.Spawned.class);
            return trait == null ? spawned : trait.shouldSpawn();
        } catch (RuntimeException e) {
            return spawned;
        }
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

    /** Nom en jeu actuel du PNJ Citizens, lu dans le registre. <strong>Thread principal.</strong> */
    Optional<String> nameByUuid(UUID uuid) {
        NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(uuid);
        return npc == null ? Optional.empty() : Optional.ofNullable(npc.getName());
    }

    /**
     * Type d'entité du PNJ, lu par le trait public {@code MobType}.
     *
     * <p>Sert à savoir si l'apparence est <strong>pilotée par un skin</strong> : seul un PNJ de type
     * {@code PLAYER} porte un skin, et c'est le seul dont le nom influence l'apparence. Un PNJ
     * villageois ou zombie n'a pas de skin du tout — son renommage est donc sans risque.</p>
     */
    Optional<EntityType> typeByUuid(UUID uuid) {
        NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(uuid);
        if (npc == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(npc.getOrAddTrait(net.citizensnpcs.api.trait.trait.MobType.class))
                    .map(net.citizensnpcs.api.trait.trait.MobType::getType);
        } catch (RuntimeException e) {
            // Trait indisponible : on préfère « inconnu » à une supposition.
            return Optional.empty();
        }
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
     * Déplace un PNJ Citizens <strong>existant</strong> vers {@code target}, sans le recréer.
     *
     * <p>Utilise {@code NPC#teleport}, l'API publique prévue pour cela : l'identité Citizens
     * (UUID, id numérique), les traits, le skin et toutes les liaisons RPGQuest sont par
     * construction préservés — rien n'est détruit ni recréé.</p>
     *
     * <p>Un PNJ <strong>non matérialisé</strong> n'est jamais fait apparaître par ce chemin : on
     * demande le déplacement, puis on <em>vérifie</em> la position enregistrée. Si Citizens ne l'a
     * pas prise en compte, l'appelant le saura au lieu de croire à un succès.</p>
     *
     * @return la position enregistrée APRÈS la tentative, ou vide si le PNJ est introuvable.
     */
    Optional<Location> moveByUuid(UUID uuid, Location target) {
        NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(uuid);
        if (npc == null) {
            return Optional.empty();
        }
        npc.teleport(target, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        CitizensAPI.getNPCRegistry().saveToStore();
        Location after = npc.isSpawned() && npc.getEntity() != null
                ? npc.getEntity().getLocation()
                : npc.getStoredLocation();
        return Optional.ofNullable(after);
    }

    /**
     * Réapplique une apparence par la commande structurée {@code /npc skin <nom>}.
     *
     * <p>Sert uniquement à <strong>reconduire</strong> ce qui s'appliquait déjà : un PNJ Citizens
     * de type {@code PLAYER} sans skin explicite dérive son apparence de son nom, donc après un
     * renommage il faut rattacher explicitement l'ancien nom pour que les joueurs voient la même
     * chose qu'avant. Ce n'est jamais un skin neuf.</p>
     *
     * <p>Même discipline de ciblage que {@link #applySkinUrlByUuid} : on sélectionne explicitement
     * le PNJ visé pour la console juste avant et on désélectionne juste après, dans le même passage
     * sur le thread principal — jamais à la merci d'une sélection préexistante.</p>
     */
    boolean applySkinNameByUuid(UUID uuid, String skinName) {
        NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(uuid);
        if (npc == null) {
            return false;
        }
        var console = org.bukkit.Bukkit.getConsoleSender();
        var selector = CitizensAPI.getDefaultNPCSelector();
        try {
            selector.select(console, npc);
            return org.bukkit.Bukkit.dispatchCommand(console, "npc skin " + skinName);
        } finally {
            selector.deselect(console);
        }
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
