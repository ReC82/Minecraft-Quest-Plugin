package com.lodygames.rpgquest.travel.model;

/**
 * Un portail simple entre deux mondes entiers : une zone d'activation cuboïde (même forme que
 * {@code ZoneDefinition}/{@code PortalDefinition}, délibérément dupliquée plutôt que réutilisée —
 * voir {@code PortalDefinition}, même rationale) dans un monde source, reliée directement au
 * <strong>spawn</strong> d'un monde destination (résolu dynamiquement à chaque activation via
 * {@code World#getSpawnLocation()}, jamais une position figée) — contrairement à
 * {@code PortalDefinition}, aucune canalisation, aucun coût, aucune condition de quête/niveau :
 * l'entrée dans la zone téléporte immédiatement (voir {@code travel.WorldPortalTeleportListener}).
 *
 * <p>Issue #565 (#228-P2) : {@link Details} porte le nom affiché, l'emplacement #213 lié
 * ({@code site}), l'état brouillon et les dimensions saisies. Un brouillon est <strong>toujours</strong>
 * {@code enabled = false} : tout code qui ne lit que {@code enabled} (dont un JAR antérieur) le
 * traite comme un portail désactivé, jamais comme un portail actif.</p>
 *
 * <p>Issue #566 (#228-P3) : {@code fixedPoint} est l'arrivée d'un portail {@link
 * DestinationStrategy#FIXED_POINT} (dans {@code destinationWorld}, {@code null} pour les autres
 * stratégies) ; {@code returnOf} désigne le portail dont celui-ci est le retour ({@code ""} sinon) —
 * un aller-retour reste toujours <strong>deux</strong> portails distincts.</p>
 */
public record WorldPortalDefinition(
        String id,
        String world,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        String destinationWorld,
        boolean enabled,
        DestinationStrategy destinationStrategy,
        Details details,
        FixedPoint fixedPoint,
        String returnOf
) {

    /**
     * Point d'arrivée fixe (issue #566) : position du joueur (pieds) et regard, dans le monde d'arrivée.
     *
     * @param yaw   regard horizontal, ramené dans [-180, 180[
     * @param pitch regard vertical, de -90 (ciel) à 90 (sol)
     */
    public record FixedPoint(double x, double y, double z, float yaw, float pitch) {

        public FixedPoint {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("le point fixe doit avoir des coordonnées numériques.");
            }
            if (pitch < -90f || pitch > 90f) {
                throw new IllegalArgumentException("le regard vertical (pitch) doit être entre -90 et 90.");
            }
            yaw = normalizeYaw(yaw);
        }

        public int blockX() {
            return (int) Math.floor(x);
        }

        public int blockY() {
            return (int) Math.floor(y);
        }

        public int blockZ() {
            return (int) Math.floor(z);
        }

        static float normalizeYaw(float yaw) {
            float y = yaw % 360f;
            if (y >= 180f) {
                y -= 360f;
            } else if (y < -180f) {
                y += 360f;
            }
            return y;
        }
    }

    /** État lisible : actif, désactivé, ou brouillon (jamais encore activé). */
    public enum State {
        DRAFT, ACTIVE, DISABLED
    }

    /**
     * Champs ajoutés par #565, tous facultatifs : un portail historique ({@code hub_to_wild},
     * {@code hub_to_claims}) a {@link #NONE} et son fichier reste identique à la réécriture.
     *
     * @param name   nom affiché ({@code ""} = déduit de la destination)
     * @param site   emplacement #213 dont la zone est dérivée ({@code ""} = zone min/max saisie)
     * @param draft  brouillon : créé depuis PlugAdmin, jamais activé
     * @param width  largeur saisie (0 = non renseignée), perpendiculaire à la face de l'emplacement
     * @param height hauteur saisie (0 = non renseignée)
     * @param depth  profondeur saisie (0 = non renseignée), le long de la face de l'emplacement
     */
    public record Details(String name, String site, boolean draft, int width, int height, int depth) {

        public static final Details NONE = new Details("", "", false, 0, 0, 0);

        public Details {
            name = name == null ? "" : name.strip();
            site = site == null ? "" : site.strip();
            if (width < 0 || height < 0 || depth < 0) {
                throw new IllegalArgumentException("les dimensions saisies doivent être positives.");
            }
        }

        public boolean hasSize() {
            return width > 0 && height > 0 && depth > 0;
        }
    }

    public WorldPortalDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id est obligatoire.");
        }
        if (!id.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException(
                    "id invalide : \"" + id + "\" (minuscules, chiffres, « _ » et « - » uniquement).");
        }
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world est obligatoire.");
        }
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("les bornes min doivent être inférieures ou égales aux bornes max.");
        }
        if (destinationWorld == null || destinationWorld.isBlank()) {
            throw new IllegalArgumentException("destinationWorld est obligatoire.");
        }
        if (destinationStrategy == null) {
            destinationStrategy = DestinationStrategy.WORLD_SPAWN;
        }
        details = details == null ? Details.NONE : details;
        if (details.draft() && enabled) {
            throw new IllegalArgumentException("un brouillon ne peut pas être actif.");
        }
        if (destinationStrategy == DestinationStrategy.FIXED_POINT && fixedPoint == null) {
            throw new IllegalArgumentException("une arrivée « point fixe » exige destination-point (x, y, z).");
        }
        if (destinationStrategy != DestinationStrategy.FIXED_POINT) {
            fixedPoint = null; // un point n'a de sens qu'avec FIXED_POINT : jamais d'état ambigu.
        }
        returnOf = returnOf == null ? "" : returnOf.strip();
        if (returnOf.equals(id)) {
            throw new IllegalArgumentException("un portail ne peut pas être son propre retour.");
        }
    }

    /** Sans destination fixe ni retour : conserve les appelants antérieurs à #566 inchangés. */
    public WorldPortalDefinition(String id, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                  String destinationWorld, boolean enabled, DestinationStrategy destinationStrategy,
                                  Details details) {
        this(id, world, minX, minY, minZ, maxX, maxY, maxZ, destinationWorld, enabled, destinationStrategy,
                details, null, "");
    }

    /** Sans {@link Details} : conserve les appelants antérieurs à #565 inchangés. */
    public WorldPortalDefinition(String id, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                  String destinationWorld, boolean enabled, DestinationStrategy destinationStrategy) {
        this(id, world, minX, minY, minZ, maxX, maxY, maxZ, destinationWorld, enabled, destinationStrategy,
                Details.NONE);
    }

    /** Équivalent à {@code destinationStrategy = DestinationStrategy.WORLD_SPAWN} — conserve les appelants existants inchangés. */
    public WorldPortalDefinition(String id, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                  String destinationWorld, boolean enabled) {
        this(id, world, minX, minY, minZ, maxX, maxY, maxZ, destinationWorld, enabled, DestinationStrategy.WORLD_SPAWN);
    }

    public State state() {
        if (enabled) {
            return State.ACTIVE;
        }
        return details.draft() ? State.DRAFT : State.DISABLED;
    }

    /** Activé ({@code true}, quitte l'état brouillon) ou désactivé ; le reste est conservé. */
    public WorldPortalDefinition withEnabled(boolean on) {
        Details d = on && details.draft()
                ? new Details(details.name(), details.site(), false, details.width(), details.height(), details.depth())
                : details;
        return new WorldPortalDefinition(id, world, minX, minY, minZ, maxX, maxY, maxZ, destinationWorld, on,
                destinationStrategy, d, fixedPoint, returnOf);
    }

    /** Même portail, zone et champs #565 remplacés ; l'id, la destination et l'état sont conservés. */
    public WorldPortalDefinition withZone(Zone zone, Details newDetails) {
        return new WorldPortalDefinition(id, world, zone.minX(), zone.minY(), zone.minZ(), zone.maxX(), zone.maxY(),
                zone.maxZ(), destinationWorld, enabled, destinationStrategy, newDetails, fixedPoint, returnOf);
    }

    /** Même portail, arrivée remplacée (issue #566) ; zone, état et lien de retour conservés. */
    public WorldPortalDefinition withDestination(String newWorld, DestinationStrategy strategy, FixedPoint point) {
        return new WorldPortalDefinition(id, world, minX, minY, minZ, maxX, maxY, maxZ, newWorld, enabled, strategy,
                details, point, returnOf);
    }

    /** Une zone d'activation cuboïde (bornes incluses), monde à part. */
    public record Zone(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        public Zone {
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("les bornes min doivent être inférieures ou égales aux bornes max.");
            }
        }
    }

    public Zone zone() {
        return new Zone(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean overlaps(WorldPortalDefinition other) {
        if (!world.equals(other.world)) {
            return false;
        }
        return minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }
}
