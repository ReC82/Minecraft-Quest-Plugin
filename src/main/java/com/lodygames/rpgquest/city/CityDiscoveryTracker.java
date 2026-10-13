package com.lodygames.rpgquest.city;

import com.lodygames.rpgquest.travel.beacon.model.VillageCenter;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * État de découverte des joueurs connectés (issue #178), <strong>sans Bukkit</strong>.
 *
 * <p>Pour chaque joueur, on retient les villes qu'il a déjà découvertes, relues en base à la
 * connexion. Tant que ce relevé n'est pas arrivé, le joueur n'est pas observé du tout : sinon une
 * ville déjà découverte pourrait être annoncée une seconde fois juste après la connexion.</p>
 *
 * <p>{@link #observe} <strong>réserve</strong> la ville au moment où il la renvoie. Deux événements
 * rapprochés (un pas puis une téléportation dans le même tick) ne peuvent donc pas produire deux
 * annonces. Si l'écriture en base échoue ensuite, {@link #release} rend la ville découvrable.</p>
 */
public final class CityDiscoveryTracker {

    private final Map<UUID, Set<String>> discoveredByPlayer = new ConcurrentHashMap<>();

    /** Installe le relevé de base d'un joueur : il devient observable. */
    public void loaded(UUID playerId, Collection<String> discovered) {
        Set<String> set = ConcurrentHashMap.newKeySet();
        set.addAll(discovered);
        discoveredByPlayer.put(playerId, set);
    }

    public boolean isLoaded(UUID playerId) {
        return discoveredByPlayer.containsKey(playerId);
    }

    public void forget(UUID playerId) {
        discoveredByPlayer.remove(playerId);
    }

    /**
     * La ville à découvrir pour un joueur situé au bloc ({@code x}, {@code z}), s'il y en a une :
     * la ville retenue par la règle de chevauchement, active, et jamais découverte.
     */
    public Optional<VillageCenter> observe(UUID playerId, CityIndex index, String world, int x, int z) {
        Set<String> discovered = discoveredByPlayer.get(playerId);
        if (discovered == null) {
            return Optional.empty();
        }
        Optional<VillageCenter> primary = index.primaryAt(world, x, z);
        if (primary.isEmpty() || !discovered.add(primary.get().id())) {
            return Optional.empty();
        }
        return primary;
    }

    /** Ville supprimée : ses découvertes quittent la mémoire de tous les joueurs. */
    public void forgetCity(String cityId) {
        for (Set<String> discovered : discoveredByPlayer.values()) {
            discovered.remove(cityId);
        }
    }

    /** Annule une réservation (écriture en base refusée) : la ville redevient découvrable. */
    public void release(UUID playerId, String cityId) {
        Set<String> discovered = discoveredByPlayer.get(playerId);
        if (discovered != null) {
            discovered.remove(cityId);
        }
    }

    /**
     * Nombre de villes découvertes par ce joueur parmi celles qui satisfont {@code counts} (par
     * exemple : « existe encore »), ou {@code -1} si son relevé n'est pas chargé.
     */
    public int count(UUID playerId, Predicate<String> counts) {
        Set<String> discovered = discoveredByPlayer.get(playerId);
        if (discovered == null) {
            return -1;
        }
        return (int) discovered.stream().filter(counts).count();
    }

    public boolean hasDiscovered(UUID playerId, String cityId) {
        Set<String> discovered = discoveredByPlayer.get(playerId);
        return discovered != null && discovered.contains(cityId);
    }
}
