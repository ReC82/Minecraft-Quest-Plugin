package com.lodygames.rpgquest.city;

import com.lodygames.rpgquest.npc.CitizensNpc;
import com.lodygames.rpgquest.npc.NpcPlacementPlanner;
import com.lodygames.rpgquest.travel.beacon.model.VillageCenter;

/**
 * Diagnostic du PNJ central et du point d'arrivée d'une ville (issue #322). Logique pure, sans
 * Bukkit : l'appelant relit lui-même la définition, la liaison Citizens et la position.
 *
 * <h2>Politique : la ville ne suit jamais son PNJ</h2>
 *
 * <p>Le centre de détection reste celui choisi avec l'outil de ville. Le PNJ central n'est qu'une
 * référence par identité stable : s'il bouge, est déchargé ou supprimé, la ville ne se déplace pas
 * et aucune position n'est inventée — le diagnostic le dit, et l'administrateur décide (déplacer le
 * centre, remettre le PNJ, ou le retirer).</p>
 */
public final class CityAnchors {

    /** Gravité affichée par le panel. */
    public enum Severity { OK, INFO, WARNING, ERROR }

    /**
     * État du PNJ central.
     *
     * @param distance distance horizontale au centre en blocs (Chebyshev, comme le périmètre), ou -1
     *                 si la position est inconnue ou dans un autre monde
     */
    public record NpcStatus(String code, Severity severity, String message, String world,
                            Double x, Double y, Double z, boolean live, int distance) {

        static NpcStatus of(String code, Severity severity, String message) {
            return new NpcStatus(code, severity, message, null, null, null, null, false, -1);
        }
    }

    /** État du point d'arrivée de voyage. */
    public record ArrivalStatus(String code, Severity severity, String message) {
    }

    private CityAnchors() {
    }

    /**
     * @param definitionPresent la définition logique {@code npcs/<id>.yml} existe
     * @param citizensActive    Citizens est installé et actif
     * @param linked            le npc_id est lié à un PNJ Citizens
     * @param npc               le PNJ Citizens lié, s'il existe encore dans le registre Citizens
     */
    public static NpcStatus npc(VillageCenter city, boolean definitionPresent, boolean citizensActive,
                                boolean linked, CitizensNpc npc) {
        if (!city.hasCentralNpc()) {
            return NpcStatus.of("NONE", Severity.INFO, "Aucun PNJ central : la ville n'est qu'un point choisi en jeu.");
        }
        String id = city.centralNpcId();
        if (!definitionPresent) {
            return NpcStatus.of("DEFINITION_MISSING", Severity.ERROR, "Le PNJ « " + id + " » n'existe plus "
                    + "(définition supprimée ou renommée). La ville garde son centre ; retirez ou remplacez "
                    + "le PNJ central.");
        }
        if (!citizensActive) {
            return NpcStatus.of("CITIZENS_UNAVAILABLE", Severity.INFO, "Citizens n'est pas actif : la position "
                    + "du PNJ « " + id + " » ne peut pas être relue. La ville garde son centre.");
        }
        if (!linked) {
            return NpcStatus.of("NOT_LINKED", Severity.WARNING, "Le PNJ « " + id + " » n'est lié à aucun PNJ "
                    + "Citizens : il n'est pas présent dans le monde. Liez-le depuis la page PNJ.");
        }
        if (npc == null) {
            return NpcStatus.of("CITIZENS_MISSING", Severity.ERROR, "Le PNJ Citizens lié à « " + id + " » a "
                    + "été supprimé. La ville garde son centre ; recréez le PNJ ou retirez-le de la ville.");
        }
        if (!npc.hasLocation()) {
            return NpcStatus.of("LOCATION_UNKNOWN", Severity.WARNING, "Citizens n'expose aucune position pour "
                    + "« " + id + " ». La ville garde son centre.");
        }
        String source = npc.liveLocation() ? "position actuelle"
                : "dernière position enregistrée (PNJ non chargé : aucun joueur à proximité)";
        if (!npc.world().equals(city.world())) {
            return new NpcStatus("OTHER_WORLD", Severity.WARNING, "« " + id + " » est dans le monde "
                    + npc.world() + ", pas dans celui de la ville (" + city.world() + ") — " + source + ".",
                    npc.world(), npc.x(), npc.y(), npc.z(), npc.liveLocation(), -1);
        }
        int distance = Math.max(Math.abs((int) Math.floor(npc.x()) - city.blockX()),
                Math.abs((int) Math.floor(npc.z()) - city.blockZ()));
        boolean inside = distance <= city.detectionDistance();
        String code = inside ? "INSIDE" : "OUTSIDE";
        Severity severity = inside ? Severity.OK : Severity.WARNING;
        String message = inside
                ? "« " + id + " » est dans la ville, à " + distance + " bloc(s) du centre — " + source + "."
                : "« " + id + " » a quitté la ville : il est à " + distance + " blocs du centre, au-delà de "
                        + "la distance de détection (" + city.detectionDistance() + ") — " + source
                        + ". La ville ne le suit pas : déplacez le centre ou le PNJ.";
        return new NpcStatus(code, severity, message, npc.world(), npc.x(), npc.y(), npc.z(),
                npc.liveLocation(), distance);
    }

    /** État du point d'arrivée : par défaut le centre, sinon dans ou hors du périmètre. */
    public static ArrivalStatus arrival(VillageCenter city) {
        VillageCenter.Arrival arrival = city.arrival();
        if (arrival == null) {
            return new ArrivalStatus("CENTER", Severity.INFO,
                    "Pas d'arrivée distincte : le menu « Villages » arrive au centre de la ville.");
        }
        int distance = Math.max(Math.abs((int) Math.floor(arrival.x()) - city.blockX()),
                Math.abs((int) Math.floor(arrival.z()) - city.blockZ()));
        if (distance > city.detectionDistance()) {
            return new ArrivalStatus("OUTSIDE", Severity.WARNING, "L'arrivée est à " + distance + " blocs du "
                    + "centre, hors de la ville : le voyageur ne la découvre qu'en y entrant.");
        }
        return new ArrivalStatus("SET", Severity.OK, "Le menu « Villages » arrive au point d'arrivée, à "
                + distance + " bloc(s) du centre.");
    }

    /**
     * Une arrivée sûre : un sol plein sous les pieds, deux blocs libres (pieds et tête), ni liquide
     * ni portail. Vérifié au moment où l'arrivée est relevée, sur le monde réel.
     *
     * @return {@code null} si la position est sûre, sinon la raison du refus
     */
    public static String unsafeArrival(NpcPlacementPlanner.Probe probe, int x, int y, int z) {
        NpcPlacementPlanner.BlockKind ground = probe.kindAt(x, y - 1, z);
        NpcPlacementPlanner.BlockKind feet = probe.kindAt(x, y, z);
        NpcPlacementPlanner.BlockKind head = probe.kindAt(x, y + 1, z);
        if (feet == NpcPlacementPlanner.BlockKind.PORTAL || head == NpcPlacementPlanner.BlockKind.PORTAL) {
            return "un portail occupe cet emplacement";
        }
        if (feet == NpcPlacementPlanner.BlockKind.LIQUID || head == NpcPlacementPlanner.BlockKind.LIQUID) {
            return "l'emplacement est dans un liquide";
        }
        if (feet != NpcPlacementPlanner.BlockKind.PASSABLE || head != NpcPlacementPlanner.BlockKind.PASSABLE) {
            return "il n'y a pas deux blocs libres pour se tenir debout";
        }
        if (ground != NpcPlacementPlanner.BlockKind.SOLID) {
            return "il n'y a pas de sol plein sous les pieds";
        }
        return null;
    }
}
