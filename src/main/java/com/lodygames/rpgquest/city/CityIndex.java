package com.lodygames.rpgquest.city;

import com.lodygames.rpgquest.travel.beacon.model.VillageCenter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Index spatial immuable des villes <strong>actives</strong> (issue #178).
 *
 * <h2>Coût par déplacement</h2>
 *
 * <p>Les villes sont rangées par monde, puis dans une grille de cellules de {@link #CELL} blocs.
 * Chaque ville est inscrite dans toutes les cellules que son carré touche. Une recherche ne lit donc
 * qu'une cellule : le coût ne dépend pas du nombre total de villes, et un joueur hors de toute ville
 * ne coûte qu'une lecture de table. L'index est reconstruit à chaque mutation, rare, et jamais à
 * chaque pas.</p>
 *
 * <h2>Chevauchement : une seule ville « présente »</h2>
 *
 * <p>Si plusieurs carrés contiennent le même point, la ville retenue est, dans l'ordre :</p>
 * <ol>
 *   <li>celle dont le centre est le plus proche horizontalement (distance euclidienne en X/Z) ;</li>
 *   <li>à égalité, celle dont la distance de détection est la plus petite (la plus précise) ;</li>
 *   <li>à égalité encore, celle dont l'identifiant vient en premier dans l'ordre alphabétique.</li>
 * </ol>
 *
 * <p>Seule cette ville est découverte et annoncée, ce qui garantit un seul titre à la fois. Les
 * autres villes du chevauchement se découvrent en avançant vers leur propre centre : le centre d'une
 * ville est toujours le point où elle l'emporte, puisque sa distance y vaut zéro. Une égalité exacte
 * de centre et de distance est tranchée par l'identifiant.</p>
 */
public final class CityIndex {

    /** Côté d'une cellule de la grille, en blocs. */
    static final int CELL = 64;

    private static final CityIndex EMPTY = new CityIndex(List.of());

    private final Map<String, Map<Long, List<VillageCenter>>> cellsByWorld = new HashMap<>();
    private final List<VillageCenter> cities;

    private CityIndex(Collection<VillageCenter> all) {
        List<VillageCenter> active = new ArrayList<>();
        for (VillageCenter city : all) {
            if (!city.active()) {
                continue;
            }
            active.add(city);
            CityPerimeter.Bounds b = CityPerimeter.bounds(city);
            Map<Long, List<VillageCenter>> cells =
                    cellsByWorld.computeIfAbsent(city.world(), w -> new HashMap<>());
            for (int cx = Math.floorDiv(b.minX(), CELL); cx <= Math.floorDiv(b.maxX(), CELL); cx++) {
                for (int cz = Math.floorDiv(b.minZ(), CELL); cz <= Math.floorDiv(b.maxZ(), CELL); cz++) {
                    cells.computeIfAbsent(key(cx, cz), k -> new ArrayList<>()).add(city);
                }
            }
        }
        this.cities = List.copyOf(active);
    }

    public static CityIndex of(Collection<VillageCenter> all) {
        return all == null || all.isEmpty() ? EMPTY : new CityIndex(all);
    }

    public static CityIndex empty() {
        return EMPTY;
    }

    /** Toutes les villes actives contenant ce bloc, sans ordre particulier. */
    public List<VillageCenter> containing(String world, int x, int z) {
        Map<Long, List<VillageCenter>> cells = cellsByWorld.get(world);
        if (cells == null) {
            return List.of();
        }
        List<VillageCenter> cell = cells.get(key(Math.floorDiv(x, CELL), Math.floorDiv(z, CELL)));
        if (cell == null) {
            return List.of();
        }
        List<VillageCenter> found = new ArrayList<>(2);
        for (VillageCenter city : cell) {
            if (CityPerimeter.contains(city, world, x, z)) {
                found.add(city);
            }
        }
        return found;
    }

    /** La ville retenue pour ce bloc selon la règle de chevauchement (voir la classe). */
    public Optional<VillageCenter> primaryAt(String world, int x, int z) {
        List<VillageCenter> found = containing(world, x, z);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (found.size() == 1) {
            return Optional.of(found.get(0));
        }
        return found.stream().min(precedence(x, z));
    }

    /** Ordre de priorité entre villes qui se chevauchent au bloc ({@code x}, {@code z}). */
    static Comparator<VillageCenter> precedence(int x, int z) {
        return Comparator.<VillageCenter>comparingLong(c -> squaredDistance(c, x, z))
                .thenComparingInt(VillageCenter::detectionDistance)
                .thenComparing(VillageCenter::id);
    }

    public int size() {
        return cities.size();
    }

    private static long squaredDistance(VillageCenter city, int x, int z) {
        long dx = (long) x - city.blockX();
        long dz = (long) z - city.blockZ();
        return dx * dx + dz * dz;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }
}
