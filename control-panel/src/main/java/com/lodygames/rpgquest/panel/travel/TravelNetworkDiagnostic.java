package com.lodygames.rpgquest.panel.travel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Le diagnostic du réseau de voyage, calculé depuis un relevé {@code travel.catalog} (issue #156).
 *
 * <h2>Pourquoi le calcul vit ici, et pourquoi il est pur</h2>
 *
 * <p>La question posée est arithmétique — « combien de paires, combien d'orphelins, quelles
 * distances, quelle étendue » — et une arithmétique qui vit dans une page HTML ne se vérifie
 * jamais. Tout est donc calculé dans cette classe <strong>pure</strong> : aucun accès réseau, aucune
 * base, aucun type Bukkit. Chaque cas (réseau vide, une seule borne, coordonnées négatives, spawn
 * inconnu) est exécutable en test.</p>
 *
 * <h2>Trois réseaux, et les confondre est l'erreur à éviter</h2>
 *
 * <p>Un administrateur qui cherche « pourquoi je ne trouve plus de bornes » doit pouvoir lire trois
 * choses distinctes :</p>
 * <ul>
 *   <li>les <strong>waypoints</strong> — repères par instance de biome, dans le Hub <em>et</em> le
 *       Wild ;</li>
 *   <li>les <strong>bornes</strong> — accès au menu de voyage, <strong>Hub uniquement</strong> par
 *       politique : il n'y en a jamais eu dans le Wild, et aucun réglage de densité ne changera
 *       cela ;</li>
 *   <li>les <strong>Waystones</strong> — le réseau de voyage du Wild, sur grille, dont seul le
 *       nombre <em>découvert</em> compte : une Waystone que personne n'a découverte n'offre aucun
 *       voyage.</li>
 * </ul>
 *
 * <p>Les afficher ensemble est tout l'objet de ce diagnostic.</p>
 */
public record TravelNetworkDiagnostic(boolean available,
                                      boolean referenceKnown,
                                      String hubWorld,
                                      boolean spawnKnown, int spawnX, int spawnZ,
                                      int instanceRegionSize,
                                      int pairMinSpacing, int pairMaxSpacing,
                                      int waypointMinimumSpacing,
                                      boolean hubBeaconGenerationEnabled,
                                      int hubWaypoints, int hubBeacons,
                                      int completePairs, int missingPairs, int orphanBeacons,
                                      Stats neighbourDistance, Stats spawnDistance, Extent extent,
                                      List<BeaconRow> beacons, List<InstanceRow> instances,
                                      String lastInstanceCreatedAt, String lastBeaconCreatedAt,
                                      List<WaystoneRow> waystoneNetworks,
                                      int otherWorldWaypoints) {

    /** Aucun relevé : volontairement distinct d'un réseau réellement vide. */
    public static TravelNetworkDiagnostic unavailable() {
        return new TravelNetworkDiagnostic(false, false, "", false, 0, 0, 0, 0, 0, 0, false,
                0, 0, 0, 0, 0, Stats.unknown(), Stats.unknown(), Extent.unknown(),
                List.of(), List.of(), "", "", List.of(), 0);
    }

    /**
     * Statistiques d'une série de distances, en blocs.
     *
     * <p>{@code known == false} signifie « pas calculable » — moins de deux bornes, ou spawn
     * inconnu. Afficher {@code 0} dans ce cas annoncerait des bornes superposées.
     */
    public record Stats(boolean known, int min, int mean, int median, int max, int count) {

        public static Stats unknown() {
            return new Stats(false, 0, 0, 0, 0, 0);
        }

        /** Calcule les statistiques d'une série ; vide ou {@code null} → {@link #unknown()}. */
        public static Stats of(List<Integer> values) {
            if (values == null || values.isEmpty()) {
                return unknown();
            }
            List<Integer> sorted = new ArrayList<>(values);
            sorted.sort(Comparator.naturalOrder());
            long sum = 0;
            for (int value : sorted) {
                sum += value;
            }
            int size = sorted.size();
            int median = size % 2 == 1
                    ? sorted.get(size / 2)
                    : (int) Math.round((sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0);
            return new Stats(true, sorted.get(0), (int) Math.round((double) sum / size), median,
                    sorted.get(size - 1), size);
        }
    }

    /** Rectangle englobant une distribution. {@code known == false} si aucun point. */
    public record Extent(boolean known, int minX, int maxX, int minZ, int maxZ) {

        public static Extent unknown() {
            return new Extent(false, 0, 0, 0, 0);
        }

        public int width() {
            return known ? maxX - minX : 0;
        }

        public int depth() {
            return known ? maxZ - minZ : 0;
        }

        /** Aire du rectangle, en blocs². {@code 0} si inconnue — jamais une surface inventée. */
        public long area() {
            return known ? (long) width() * depth() : 0L;
        }
    }

    /**
     * Une borne, avec tout ce qui permet de la diagnostiquer sans ouvrir le jeu.
     *
     * <p>{@code waypointDistance} et {@code spawnDistance} valent {@code -1} quand ils ne sont pas
     * calculables (borne manuelle sans waypoint apparié, spawn inconnu) — « inconnu » et « zéro »
     * sont deux réponses différentes.</p>
     */
    public record BeaconRow(String id, String world, int x, int y, int z, boolean active,
                            boolean autoGenerated, String biomeInstance, String biomeKey,
                            String pairedWaypointId, int waypointDistance, int spawnDistance,
                            int nearestBeaconDistance, String createdAt, String anomaly) {

        public boolean hasAnomaly() {
            return anomaly != null && !anomaly.isEmpty();
        }
    }

    /**
     * Une instance de biome du Hub, et son état d'équipement.
     *
     * @param state   {@code COMPLETE} (waypoint + borne), {@code MISSING_BEACON}, ou
     *                {@code ORPHAN_BEACON} (borne sans waypoint)
     * @param attempts essais d'appariement depuis le dernier démarrage ; {@code 0} dénonce un
     *                 appariement <strong>jamais tenté</strong>, ce qui n'est pas un échec de terrain
     * @param reason  la cause en clair, telle que l'écran doit la lire
     */
    public record InstanceRow(String biomeInstance, String biomeKey, String waypointId,
                              String beaconId, String state, int x, int z,
                              int attempts, Long nextRetryEpochMs, boolean inProgress,
                              int nearestBeaconDistance, String createdAt, String reason) {

        public boolean complete() {
            return "COMPLETE".equals(state);
        }
    }

    /** Le réseau du Wild. {@code discovered == -1} = non relevé, pas « aucune découverte ». */
    public record WaystoneRow(String world, int total, int discovered, int cellSize, double chance,
                              int minimumSpacing, Extent extent) {

        /** Blocs² par Waystone existante, {@code 0} si incalculable. */
        public long areaPerWaystone() {
            return total <= 0 || !extent.known() ? 0L : extent.area() / total;
        }

        public boolean noneDiscovered() {
            return discovered == 0;
        }

        public boolean discoveryKnown() {
            return discovered >= 0;
        }
    }

    // ---- Construction depuis le relevé ----------------------------------------------------------

    /**
     * Construit le diagnostic depuis les détails d'un relevé {@code travel.catalog}.
     *
     * <p>Tolérant par principe : un champ absent donne « inconnu », jamais une exception. Un
     * diagnostic qui refuse de s'afficher parce qu'une clé manque ne diagnostique rien.</p>
     */
    public static TravelNetworkDiagnostic from(Map<String, Object> details) {
        if (details == null) {
            return unavailable();
        }
        // Le panel et le plugin se déploient séparément, le panel d'abord : pendant quelques minutes,
        // le dernier relevé peut venir d'une version du plugin qui n'envoyait pas encore le
        // référentiel. Sans cette distinction, l'écran annoncerait « aucun monde Hub configuré »
        // — une affirmation fausse — là où il doit dire « relevé trop ancien, rafraîchir ».
        boolean reference = details.containsKey("hubWorld");
        String hub = str(details.get("hubWorld"));
        boolean spawnKnown = bool(details.get("hubSpawnKnown"));
        int spawnX = intOr(details.get("hubSpawnX"));
        int spawnZ = intOr(details.get("hubSpawnZ"));

        List<Map<String, Object>> waypoints = rows(details.get("waypoints"));
        List<Map<String, Object>> beaconRows = rows(details.get("beacons"));
        List<Map<String, Object>> unpaired = rows(details.get("unpairedHubInstances"));

        // Index des waypoints par instance, pour relier une borne à SON waypoint sans supposer
        // l'ordre des listes.
        Map<String, Map<String, Object>> waypointByInstance = new LinkedHashMap<>();
        int otherWorlds = 0;
        for (Map<String, Object> w : waypoints) {
            if (hub.equals(str(w.get("world")))) {
                waypointByInstance.put(str(w.get("biomeInstance")), w);
            } else {
                otherWorlds++;
            }
        }

        List<Map<String, Object>> hubBeaconRows = new ArrayList<>();
        for (Map<String, Object> b : beaconRows) {
            if (hub.equals(str(b.get("world")))) {
                hubBeaconRows.add(b);
            }
        }

        List<BeaconRow> beacons = new ArrayList<>();
        List<Integer> neighbours = new ArrayList<>();
        List<Integer> spawnDistances = new ArrayList<>();
        int orphans = 0;
        for (Map<String, Object> b : hubBeaconRows) {
            int bx = intOr(b.get("x"));
            int bz = intOr(b.get("z"));
            String instance = str(b.get("biomeInstance"));
            boolean auto = bool(b.get("autoGenerated"));
            Map<String, Object> waypoint = waypointByInstance.get(instance);
            int wpDistance = waypoint == null ? -1
                    : distance(bx, bz, intOr(waypoint.get("x")), intOr(waypoint.get("z")));
            int spawnDistance = spawnKnown ? distance(bx, bz, spawnX, spawnZ) : -1;
            int nearest = nearestOther(hubBeaconRows, b);
            if (nearest >= 0) {
                neighbours.add(nearest);
            }
            if (spawnDistance >= 0) {
                spawnDistances.add(spawnDistance);
            }

            String anomaly = "";
            if (auto && waypoint == null) {
                anomaly = "borne auto-générée dont l'instance n'a plus de waypoint";
                orphans++;
            } else if (!bool(b.get("active"))) {
                anomaly = "borne désactivée : son bouton est inerte";
            }
            beacons.add(new BeaconRow(str(b.get("id")), str(b.get("world")), bx, intOr(b.get("y")),
                    bz, bool(b.get("active")), auto, instance, biomeOf(instance),
                    str(b.get("pairedWaypointId")), wpDistance, spawnDistance, nearest,
                    str(b.get("createdAt")), anomaly));
        }
        beacons.sort(Comparator.comparing(BeaconRow::createdAt));

        // Les instances : toutes celles qui ont un waypoint dans le Hub, plus leur état.
        Map<String, Map<String, Object>> gapByInstance = new LinkedHashMap<>();
        for (Map<String, Object> g : unpaired) {
            gapByInstance.put(str(g.get("biomeInstance")), g);
        }
        Map<String, String> beaconIdByInstance = new LinkedHashMap<>();
        for (Map<String, Object> b : hubBeaconRows) {
            if (bool(b.get("autoGenerated"))) {
                beaconIdByInstance.put(str(b.get("biomeInstance")), str(b.get("id")));
            }
        }

        List<InstanceRow> instances = new ArrayList<>();
        int complete = 0;
        for (Map.Entry<String, Map<String, Object>> entry : waypointByInstance.entrySet()) {
            String instance = entry.getKey();
            Map<String, Object> w = entry.getValue();
            String beaconId = beaconIdByInstance.get(instance);
            Map<String, Object> gap = gapByInstance.get(instance);
            boolean hasBeacon = beaconId != null && !beaconId.isEmpty();
            if (hasBeacon) {
                complete++;
            }
            int attempts = gap == null ? 0 : intOr(gap.get("attempts"));
            Long nextRetry = gap == null ? null : longOrNull(gap.get("nextRetryEpochMs"));
            boolean inProgress = gap != null && bool(gap.get("inProgress"));
            int nearest = gap == null ? -1 : intOr(gap.get("nearestBeaconDistance"));
            instances.add(new InstanceRow(instance, biomeOf(instance), str(w.get("id")), beaconId,
                    hasBeacon ? "COMPLETE" : "MISSING_BEACON",
                    intOr(w.get("x")), intOr(w.get("z")), attempts, nextRetry, inProgress, nearest,
                    str(w.get("createdAt")),
                    hasBeacon ? "" : reasonFor(attempts, nextRetry, inProgress)));
        }
        instances.sort(Comparator.comparing(InstanceRow::createdAt));

        List<Integer> xs = new ArrayList<>();
        List<Integer> zs = new ArrayList<>();
        for (BeaconRow row : beacons) {
            xs.add(row.x());
            zs.add(row.z());
        }
        Extent extent = xs.isEmpty() ? Extent.unknown()
                : new Extent(true, min(xs), max(xs), min(zs), max(zs));

        List<WaystoneRow> waystones = new ArrayList<>();
        for (Map<String, Object> n : rows(details.get("waystoneNetworks"))) {
            Extent wsExtent = new Extent(intOr(n.get("total")) > 0,
                    intOr(n.get("minX")), intOr(n.get("maxX")),
                    intOr(n.get("minZ")), intOr(n.get("maxZ")));
            waystones.add(new WaystoneRow(str(n.get("world")), intOr(n.get("total")),
                    n.get("discovered") == null ? -1 : intOr(n.get("discovered")),
                    intOr(n.get("cellSize")), doubleOr(n.get("chance")),
                    intOr(n.get("minimumSpacing")), wsExtent));
        }

        return new TravelNetworkDiagnostic(true, reference, hub, spawnKnown, spawnX, spawnZ,
                intOr(details.get("instanceRegionSize")),
                intOr(details.get("beaconPairMinSpacing")),
                intOr(details.get("beaconPairMaxSpacing")),
                intOr(details.get("waypointMinimumSpacing")),
                bool(details.get("hubBeaconGenerationEnabled")),
                waypointByInstance.size(), hubBeaconRows.size(),
                complete, unpaired.size(), orphans,
                Stats.of(neighbours), Stats.of(spawnDistances), extent,
                List.copyOf(beacons), List.copyOf(instances),
                lastOf(instances, InstanceRow::createdAt),
                lastOf(beacons, BeaconRow::createdAt),
                List.copyOf(waystones), otherWorlds);
    }

    /**
     * La cause d'un appariement manquant, en clair.
     *
     * <p>C'est la phrase qui transforme « il manque des bornes » en quelque chose d'actionnable :
     * {@code attempts == 0} dénonce un appariement <strong>jamais tenté</strong> — donc une instance
     * qu'aucun joueur n'a traversée depuis le dernier démarrage, et non un terrain impraticable.</p>
     */
    static String reasonFor(int attempts, Long nextRetryEpochMs, boolean inProgress) {
        if (inProgress) {
            return "appariement en cours";
        }
        if (attempts == 0) {
            return "jamais tenté depuis le dernier démarrage : une borne n'est cherchée que "
                    + "lorsqu'un joueur traverse l'instance";
        }
        if (nextRetryEpochMs != null && nextRetryEpochMs > System.currentTimeMillis()) {
            return attempts + " essai(s) sans emplacement valable — nouvel essai programmé";
        }
        return attempts + " essai(s) sans emplacement valable : terrain ou espacement minimal en cause";
    }

    /** Vrai si le réseau du Hub est complet : chaque instance a sa borne, et aucune n'est orpheline. */
    public boolean hubComplete() {
        return available && missingPairs == 0 && orphanBeacons == 0;
    }

    /** Les bornes portant une anomalie, pour les remonter en tête d'écran. */
    public List<BeaconRow> anomalies() {
        return beacons.stream().filter(BeaconRow::hasAnomaly).toList();
    }

    /** Les instances encore sans borne, dans l'ordre de création de leur waypoint. */
    public List<InstanceRow> missing() {
        return instances.stream().filter(row -> !row.complete()).toList();
    }

    /** Blocs² couverts par borne, {@code 0} si incalculable — jamais une densité inventée. */
    public long areaPerBeacon() {
        return hubBeacons <= 0 || !extent.known() ? 0L : extent.area() / hubBeacons;
    }

    // ---- Outils ---------------------------------------------------------------------------------

    private static int nearestOther(List<Map<String, Object>> all, Map<String, Object> self) {
        int best = -1;
        for (Map<String, Object> other : all) {
            if (other == self) {
                continue;
            }
            int d = distance(intOr(self.get("x")), intOr(self.get("z")),
                    intOr(other.get("x")), intOr(other.get("z")));
            if (best < 0 || d < best) {
                best = d;
            }
        }
        return best;
    }

    private static int distance(int x1, int z1, int x2, int z2) {
        double dx = (double) x1 - x2;
        double dz = (double) z1 - z2;
        return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
    }

    /** Le type de biome porté par une clé d'instance {@code minecraft:forest@2,-3}. */
    static String biomeOf(String biomeInstance) {
        if (biomeInstance == null || biomeInstance.isEmpty()) {
            return "";
        }
        int at = biomeInstance.indexOf('@');
        String biome = at < 0 ? biomeInstance : biomeInstance.substring(0, at);
        int colon = biome.indexOf(':');
        return colon < 0 ? biome : biome.substring(colon + 1);
    }

    private static <T> String lastOf(List<T> rows, java.util.function.Function<T, String> key) {
        String best = "";
        for (T row : rows) {
            String value = key.apply(row);
            if (value != null && value.compareTo(best) > 0) {
                best = value;
            }
        }
        return best;
    }

    private static int min(List<Integer> values) {
        int best = values.get(0);
        for (int value : values) {
            best = Math.min(best, value);
        }
        return best;
    }

    private static int max(List<Integer> values) {
        int best = values.get(0);
        for (int value : values) {
            best = Math.max(best, value);
        }
        return best;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add((Map<String, Object>) map);
                }
            }
        }
        return out;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b ? b : "true".equalsIgnoreCase(str(value));
    }

    private static int intOr(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(str(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Long longOrNull(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(str(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double doubleOr(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(str(value).trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    /** Les deux premières décimales d'une probabilité, pour l'affichage. */
    public static String percent(double chance) {
        return Math.round(chance * 100) + " %";
    }

    /** {@link OptionalInt} vide si la statistique n'est pas calculable — pour les appelants stricts. */
    public OptionalInt medianNeighbour() {
        return neighbourDistance.known() ? OptionalInt.of(neighbourDistance.median())
                : OptionalInt.empty();
    }
}
