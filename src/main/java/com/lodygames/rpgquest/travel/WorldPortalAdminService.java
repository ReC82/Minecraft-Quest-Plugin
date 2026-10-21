package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.building.model.BuildingSite;
import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.PortalDefinition;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition.Details;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition.State;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition.Zone;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Gestion des portails entre mondes depuis PlugAdmin (issue #565, #228-P2) : créer un
 * <strong>brouillon</strong>, renommer, régler la zone d'activation, activer, désactiver, supprimer.
 *
 * <p>Toutes les règles sont ici, sans Bukkit : l'appelant relève l'état du serveur dans un {@link
 * Context} (sur le thread principal) puis appelle une opération hors du thread principal ; seule
 * {@link WorldPortalRegistry} écrit, dans le même dossier {@code world-portals/} que {@code
 * /rpgadmin worldportal} et que le jeu lit — aucun second moteur.</p>
 *
 * <ul>
 *   <li>Un brouillon ne téléporte personne (il est écrit {@code enabled: false}).</li>
 *   <li>Chaque opération sur un portail existant exige son empreinte ({@link #fingerprint}) telle
 *       que la page l'a lue : un portail modifié entre-temps n'est jamais écrasé.</li>
 *   <li>Un portail <em>essentiel</em> (il mène au Hub, au Wild ou aux Claims, comme {@code
 *       hub_to_wild} et {@code hub_to_claims}) n'est modifié qu'avec une confirmation de plus.</li>
 *   <li>La zone d'un portail actif ne se modifie pas, et un portail actif ne se supprime pas :
 *       le désactiver d'abord.</li>
 *   <li>Supprimer un portail ne touche ni l'emplacement #213 ni un bâtiment posé.</li>
 *   <li>Issue #566 : l'arrivée (monde, stratégie, point fixe) d'un portail actif ne se modifie pas ;
 *       un point fixe est refusé hors des hauteurs du monde ou dans la zone d'un portail (boucle).
 *       Un portail retour est un second portail distinct, créé en brouillon, qui relie les deux
 *       mondes en sens inverse ({@code return-of}).</li>
 * </ul>
 */
public final class WorldPortalAdminService {

    public static final int MAX_NAME_LENGTH = 64;
    private static final String ID_PATTERN = "[a-z0-9_-]{3,48}";

    /**
     * État du serveur relevé avant une opération.
     *
     * @param loadedWorlds mondes chargés
     * @param roles        mondes Hub / Wild / Claims (portails essentiels)
     * @param zonePortals  portails de zone ({@code portals/}, ancien format) : une zone ne les recouvre pas
     * @param sites        emplacements #213 par id
     */
    public record Context(Set<String> loadedWorlds, PortalOverview.Roles roles, List<PortalDefinition> zonePortals,
                          Function<String, Optional<BuildingSite>> sites,
                          Function<String, Optional<WorldBounds>> bounds) {

        /** Sans hauteurs relevées : celles d'un monde normal (−64 à 320). */
        public Context(Set<String> loadedWorlds, PortalOverview.Roles roles, List<PortalDefinition> zonePortals,
                       Function<String, Optional<BuildingSite>> sites) {
            this(loadedWorlds, roles, zonePortals, sites, w -> Optional.of(WorldBounds.OVERWORLD));
        }
    }

    /** Hauteurs constructibles d'un monde chargé : {@code minY} inclus, {@code maxY} exclu. */
    public record WorldBounds(int minY, int maxY) {

        public static final WorldBounds OVERWORLD = new WorldBounds(-64, 320);
    }

    /**
     * Arrivée demandée (issue #566).
     *
     * @param point obligatoire pour {@link DestinationStrategy#FIXED_POINT}, ignoré sinon
     */
    public record DestinationRequest(String world, DestinationStrategy strategy, WorldPortalDefinition.FixedPoint point) {
    }

    /**
     * Zone demandée : depuis un emplacement ({@code site} + dimensions) ou en coordonnées ({@code
     * world} + {@code explicit}).
     */
    public record ZoneRequest(String site, int width, int height, int depth, String world, Zone explicit) {

        public static ZoneRequest fromSite(String site, int width, int height, int depth) {
            return new ZoneRequest(site, width, height, depth, null, null);
        }

        public static ZoneRequest explicit(String world, Zone zone) {
            return new ZoneRequest(null, 0, 0, 0, world, zone);
        }

        boolean bySite() {
            return site != null && !site.isBlank();
        }
    }

    /** @param returnOf portail dont celui-ci est le retour ({@code ""} : aucun) */
    public record CreateRequest(String id, String name, ZoneRequest zone, String destinationWorld,
                                DestinationStrategy strategy, WorldPortalDefinition.FixedPoint point,
                                String returnOf) {

        public CreateRequest(String id, String name, ZoneRequest zone, String destinationWorld,
                             DestinationStrategy strategy) {
            this(id, name, zone, destinationWorld, strategy, null, "");
        }
    }

    /** Résultat lisible. {@code code} : {@code CREATED}, {@code UPDATED}, {@code UNCHANGED}… ou la raison du refus. */
    public record Result(boolean ok, String code, String message, String portal, List<String> effects) {

        static Result refused(String code, String message) {
            return new Result(false, code, message, "", List.of("rien n'a été modifié"));
        }
    }

    /** Opération demandée par PlugAdmin, déjà lue et typée par l'agent. */
    public enum Kind {
        CREATE, UPDATE, ENABLE, DISABLE, DELETE
    }

    /**
     * @param name     {@code null} = nom inchangé (modification)
     * @param zone     {@code null} = zone inchangée (modification)
     * @param expected empreinte lue par la page (toutes les opérations sauf la création)
     * @param destination création : arrivée choisie ; modification : {@code null} = arrivée inchangée
     * @param returnOf création seulement : portail dont celui-ci est le retour
     */
    public record Operation(Kind kind, String id, String expected, String name, ZoneRequest zone,
                            DestinationRequest destination, String returnOf, boolean confirmEssential,
                            String actor) {

        public Operation(Kind kind, String id, String expected, String name, ZoneRequest zone,
                         String destinationWorld, DestinationStrategy strategy, boolean confirmEssential,
                         String actor) {
            this(kind, id, expected, name, zone, kind == Kind.CREATE
                    ? new DestinationRequest(destinationWorld, strategy, null) : null, "", confirmEssential, actor);
        }
    }

    /** Aiguille une {@link Operation} vers l'opération correspondante. */
    public Result apply(Operation op, Context ctx) {
        return switch (op.kind()) {
            case CREATE -> {
                DestinationRequest d = op.destination() == null
                        ? new DestinationRequest("", DestinationStrategy.WORLD_SPAWN, null) : op.destination();
                yield create(new CreateRequest(op.id(), op.name(), op.zone(), d.world(), d.strategy(), d.point(),
                        op.returnOf()), op.actor(), ctx);
            }
            case UPDATE -> update(op.id(), op.expected(), op.name(), op.zone(), op.destination(),
                    op.confirmEssential(), op.actor(), ctx);
            case ENABLE -> setActive(op.id(), true, op.expected(), op.confirmEssential(), op.actor(), ctx);
            case DISABLE -> setActive(op.id(), false, op.expected(), op.confirmEssential(), op.actor(), ctx);
            case DELETE -> delete(op.id(), op.expected(), op.confirmEssential(), op.actor(), ctx);
        };
    }

    private final WorldPortalRegistry registry;
    private final PortalAuditLog audit;
    private final Clock clock;

    public WorldPortalAdminService(WorldPortalRegistry registry, PortalAuditLog audit, Clock clock) {
        this.registry = registry;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- Opérations -----------------------------------------------------------------------------

    public synchronized Result create(CreateRequest req, String actor, Context ctx) {
        String id = req.id() == null ? "" : req.id().strip();
        if (!id.matches(ID_PATTERN)) {
            return Result.refused("INVALID_ID", "Identifiant invalide : 3 à 48 caractères, minuscules, chiffres, "
                    + "« _ » et « - ». Il ne changera plus ensuite.");
        }
        String nameError = checkName(req.name());
        if (nameError != null) {
            return Result.refused("INVALID_NAME", nameError);
        }
        if (registry.find(id).isPresent()) {
            return Result.refused("DUPLICATE_ID", "Un portail porte déjà l'identifiant « " + id + " ».");
        }
        Resolved zone = resolve(req.zone(), ctx, null);
        if (zone.error() != null) {
            return zone.error();
        }
        String dest = req.destinationWorld() == null ? "" : req.destinationWorld().strip();
        DestinationStrategy strategy = req.strategy() == null ? DestinationStrategy.WORLD_SPAWN : req.strategy();
        Result badDestination = checkDestination(id, dest, strategy, req.point(), ctx);
        if (badDestination != null) {
            return badDestination;
        }
        Details details = new Details(clean(req.name()), zone.site(), true, zone.width(), zone.height(), zone.depth());
        Zone z = zone.zone();
        String returnOf = req.returnOf() == null ? "" : req.returnOf().strip();
        WorldPortalDefinition draft = new WorldPortalDefinition(id, zone.world(), z.minX(), z.minY(), z.minZ(),
                z.maxX(), z.maxY(), z.maxZ(), dest, false, strategy, details,
                strategy == DestinationStrategy.FIXED_POINT ? req.point() : null, returnOf);
        Result overlap = overlapWithZonePortals(draft, ctx);
        if (overlap != null) {
            return overlap;
        }
        Result badReturn = checkReturn(draft);
        if (badReturn != null) {
            return badReturn;
        }
        return switch (registry.create(draft)) {
            case CREATED -> {
                record(actor, "CREATE_DRAFT", id, null, draft);
                yield new Result(true, "CREATED", "Brouillon « " + id + " » créé" + (returnOf.isEmpty() ? ""
                        : " (retour de « " + returnOf + " »)") + ". Il ne téléporte personne tant qu'il n'est pas "
                        + "activé.", id, List.of("fichier world-portals/" + id + ".yml créé",
                        returnOf.isEmpty() ? "aucun bloc, aucun emplacement ni aucun joueur touché"
                                : "portail « " + returnOf + " » inchangé ; aucun bloc ni joueur touché"));
            }
            case DUPLICATE_ID -> Result.refused("DUPLICATE_ID", "Un portail ou un fichier world-portals/" + id
                    + ".yml existe déjà.");
            case OVERLAPS -> Result.refused("OVERLAPS", "Cette zone recouvre celle d'un portail existant de ce monde.");
            case IO_ERROR -> Result.refused("IO_ERROR", "Écriture impossible : voir la console du serveur.");
        };
    }

    /** Sans changement d'arrivée : conserve les appelants antérieurs à #566. */
    public synchronized Result update(String id, String expected, String name, ZoneRequest zone,
                                      boolean confirmEssential, String actor, Context ctx) {
        return update(id, expected, name, zone, null, confirmEssential, actor, ctx);
    }

    /**
     * Renommer ({@code name} non {@code null}), changer la zone ({@code zone} non {@code null}) et/ou
     * l'arrivée ({@code destination} non {@code null}). Le monde de départ ne change pas ; ni la zone
     * ni l'arrivée d'un portail actif non plus.
     */
    public synchronized Result update(String id, String expected, String name, ZoneRequest zone,
                                      DestinationRequest destination, boolean confirmEssential, String actor,
                                      Context ctx) {
        Lookup found = lookup(id, expected, confirmEssential, ctx);
        if (found.error() != null) {
            return found.error();
        }
        WorldPortalDefinition current = found.portal();
        Details d = current.details();
        String newName = d.name();
        if (name != null) {
            String nameError = checkName(name);
            if (nameError != null) {
                return Result.refused("INVALID_NAME", nameError);
            }
            newName = clean(name);
        }
        WorldPortalDefinition updated = current.withZone(current.zone(),
                new Details(newName, d.site(), d.draft(), d.width(), d.height(), d.depth()));
        if (zone != null) {
            if (current.state() == State.ACTIVE) {
                return Result.refused("ACTIVE_ZONE_LOCKED", "Le portail est actif : le désactiver avant de "
                        + "déplacer ou de redimensionner sa zone.");
            }
            Resolved r = resolve(zone, ctx, current.world());
            if (r.error() != null) {
                return r.error();
            }
            updated = current.withZone(r.zone(), new Details(newName, r.site(), d.draft(), r.width(), r.height(),
                    r.depth()));
            Result overlap = overlapWithZonePortals(updated, ctx);
            if (overlap != null) {
                return overlap;
            }
        }
        if (destination != null) {
            if (current.state() == State.ACTIVE) {
                return Result.refused("ACTIVE_DESTINATION_LOCKED", "Le portail est actif : le désactiver avant de "
                        + "changer son arrivée.");
            }
            String dest = destination.world() == null ? "" : destination.world().strip();
            DestinationStrategy strategy = destination.strategy() == null
                    ? DestinationStrategy.WORLD_SPAWN : destination.strategy();
            Result bad = checkDestination(id, dest, strategy, destination.point(), ctx);
            if (bad != null) {
                return bad;
            }
            updated = updated.withDestination(dest, strategy,
                    strategy == DestinationStrategy.FIXED_POINT ? destination.point() : null);
            Result badReturn = checkReturn(updated);
            if (badReturn != null) {
                return badReturn;
            }
        }
        if (updated.equals(current)) {
            return new Result(true, "UNCHANGED", "Aucun changement à enregistrer.", id, List.of());
        }
        return save(current, updated, "UPDATE", actor, "Portail « " + id + " » enregistré.");
    }

    /** Activer ({@code on}) un brouillon ou un portail désactivé, ou désactiver un portail actif. */
    public synchronized Result setActive(String id, boolean on, String expected, boolean confirmEssential,
                                         String actor, Context ctx) {
        Lookup found = lookup(id, expected, confirmEssential, ctx);
        if (found.error() != null) {
            return found.error();
        }
        WorldPortalDefinition current = found.portal();
        if (current.enabled() == on) {
            return new Result(true, "UNCHANGED", on ? "Le portail est déjà actif." : "Le portail est déjà désactivé.",
                    id, List.of());
        }
        if (on) {
            if (!ctx.loadedWorlds().contains(current.world())) {
                return Result.refused("SOURCE_NOT_LOADED", "Le monde de départ « " + current.world()
                        + " » n'est pas chargé.");
            }
            if (!ctx.loadedWorlds().contains(current.destinationWorld())) {
                return Result.refused("DESTINATION_NOT_LOADED", "Le monde d'arrivée « " + current.destinationWorld()
                        + " » n'est pas chargé : le portail ne mènerait nulle part.");
            }
            String site = current.details().site();
            if (!site.isEmpty() && ctx.sites().apply(site).isEmpty()) {
                return Result.refused("SITE_MISSING", "L'emplacement « " + site + " » de ce portail n'existe plus.");
            }
            Result overlap = overlapWithZonePortals(current, ctx);
            if (overlap != null) {
                return overlap;
            }
            // #566 : mêmes règles d'arrivée qu'à l'enregistrement — le monde a pu changer depuis.
            Result badDestination = checkDestination(current.id(), current.destinationWorld(),
                    current.destinationStrategy(), current.fixedPoint(), ctx);
            if (badDestination != null) {
                return badDestination;
            }
        }
        WorldPortalDefinition updated = current.withEnabled(on);
        return save(current, updated, on ? "ENABLE" : "DISABLE", actor, on
                ? "Portail « " + id + " » activé : entrer dans sa zone téléporte désormais."
                : "Portail « " + id + " » désactivé : sa configuration est conservée.");
    }

    /** Supprime la configuration d'un portail non actif. L'emplacement et le bâtiment restent. */
    public synchronized Result delete(String id, String expected, boolean confirmEssential, String actor,
                                      Context ctx) {
        Lookup found = lookup(id, expected, confirmEssential, ctx);
        if (found.error() != null) {
            return found.error();
        }
        WorldPortalDefinition current = found.portal();
        if (current.state() == State.ACTIVE) {
            return Result.refused("ACTIVE_DELETE_REFUSED", "Le portail est actif : le désactiver avant de le supprimer.");
        }
        String file = registry.fileOf(id);
        if (!registry.delete(id)) {
            return Result.refused("IO_ERROR", "Suppression impossible : voir la console du serveur.");
        }
        record(actor, "DELETE", id, current, null);
        List<String> effects = new ArrayList<>();
        effects.add("fichier world-portals/" + file + " supprimé");
        effects.add(current.details().site().isEmpty() ? "aucun bloc du monde touché"
                : "emplacement « " + current.details().site() + " » et bâtiment éventuel conservés, aucun bloc touché");
        return new Result(true, "DELETED", "Portail « " + id + " » supprimé.", id, List.copyOf(effects));
    }

    // ---- Règles partagées -----------------------------------------------------------------------

    /**
     * Empreinte de l'état persistant d'un portail : la page la lit dans {@code portal.list} et la
     * renvoie avec chaque modification.
     */
    public static String fingerprint(WorldPortalDefinition p) {
        Details d = p.details();
        String canonical = String.join("|", p.id(), p.world(), p.minX() + "," + p.minY() + "," + p.minZ(),
                p.maxX() + "," + p.maxY() + "," + p.maxZ(), p.destinationWorld(), String.valueOf(p.enabled()),
                p.destinationStrategy().name(), d.name(), d.site(), String.valueOf(d.draft()),
                d.width() + "x" + d.height() + "x" + d.depth());
        // #566 : ajoutés seulement s'ils sont renseignés — l'empreinte d'un portail sans point fixe ni
        // retour reste celle d'avant (une page ouverte avant la mise à jour reste valable).
        if (p.fixedPoint() != null) {
            canonical += "|point:" + pointText(p.fixedPoint());
        }
        if (!p.returnOf().isEmpty()) {
            canonical += "|return-of:" + p.returnOf();
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Il mène au Hub, au Wild ou aux Claims : chaque modification demande une confirmation de plus. */
    public static boolean essential(WorldPortalDefinition p, PortalOverview.Roles roles) {
        return !PortalOverview.role(p.destinationWorld(), roles).isEmpty();
    }

    /** Résumé lisible d'un état, pour le journal. */
    static String summary(WorldPortalDefinition p) {
        if (p == null) {
            return "";
        }
        Details d = p.details();
        return "état " + p.state() + (d.name().isEmpty() ? "" : ", nom « " + d.name() + " »")
                + ", " + p.world() + " " + p.minX() + "," + p.minY() + "," + p.minZ() + " → "
                + p.maxX() + "," + p.maxY() + "," + p.maxZ()
                + (d.site().isEmpty() ? "" : ", emplacement " + d.site() + " " + d.width() + "×" + d.height() + "×"
                + d.depth())
                + ", vers " + p.destinationWorld() + " (" + p.destinationStrategy()
                + (p.fixedPoint() == null ? "" : " " + pointText(p.fixedPoint())) + ")"
                + (p.returnOf().isEmpty() ? "" : ", retour de " + p.returnOf());
    }

    /** {@code x, y, z, regard yaw/pitch}, sans perte : sert aussi à l'empreinte. */
    public static String pointText(WorldPortalDefinition.FixedPoint fp) {
        return fp.x() + ", " + fp.y() + ", " + fp.z() + " regard " + fp.yaw() + "/" + fp.pitch();
    }

    /**
     * Règles d'arrivée communes (création, modification, activation) : monde chargé, « claim du
     * joueur » vers les Claims seulement, point fixe présent, dans les hauteurs du monde et hors de
     * toute zone de portail (sinon le joueur serait renvoyé dès la fin du répit d'arrivée).
     */
    private Result checkDestination(String selfId, String dest, DestinationStrategy strategy,
                                    WorldPortalDefinition.FixedPoint point, Context ctx) {
        if (!ctx.loadedWorlds().contains(dest)) {
            return Result.refused("DESTINATION_NOT_LOADED", "Le monde d'arrivée « " + dest + " » n'est pas chargé : "
                    + "le portail ne le charge jamais lui-même.");
        }
        if (strategy == DestinationStrategy.CLAIM_HOME && !dest.equals(ctx.roles().claims())) {
            return Result.refused("CLAIM_HOME_OUTSIDE_CLAIMS", "« Claim du joueur » ne s'utilise que vers le monde "
                    + "des claims (" + ctx.roles().claims() + ").");
        }
        if (strategy != DestinationStrategy.FIXED_POINT) {
            return null;
        }
        if (point == null) {
            return Result.refused("POINT_REQUIRED", "Arrivée « point fixe » : saisir X, Y et Z.");
        }
        WorldBounds b = ctx.bounds().apply(dest).orElse(WorldBounds.OVERWORLD);
        if (point.blockY() - 1 < b.minY() || point.blockY() + 1 >= b.maxY()) {
            return Result.refused("POINT_OUT_OF_WORLD", "Le point fixe (Y " + point.blockY() + ") doit laisser un sol "
                    + "et la tête du joueur dans les hauteurs du monde « " + dest + " » (" + (b.minY() + 1) + " à "
                    + (b.maxY() - 2) + ").");
        }
        List<String> zones = portalsAt(dest, point.blockX(), point.blockY(), point.blockZ(), ctx);
        if (!zones.isEmpty()) {
            return Result.refused("POINT_INSIDE_PORTAL", "Le point fixe est dans la zone du portail "
                    + String.join(", ", zones) + " : le joueur y serait renvoyé aussitôt. Choisir un point à côté.");
        }
        return null;
    }

    /** Portails (entre mondes et de zone) dont la zone contient ce bloc ou le bloc de la tête. */
    List<String> portalsAt(String world, int x, int y, int z, Context ctx) {
        List<String> out = new ArrayList<>();
        for (WorldPortalDefinition other : registry.portalsInWorld(world)) {
            if (other.contains(world, x, y, z) || other.contains(world, x, y + 1, z)) {
                out.add("« " + other.id() + " »");
            }
        }
        for (PortalDefinition zone : ctx.zonePortals()) {
            if (world.equals(zone.world()) && x >= zone.minX() && x <= zone.maxX() && z >= zone.minZ()
                    && z <= zone.maxZ() && y + 1 >= zone.minY() && y <= zone.maxY()) {
                out.add("« " + zone.id() + " » (portail de zone)");
            }
        }
        return out;
    }

    /**
     * Un portail retour part du monde d'arrivée du portail aller et ramène à son monde de départ ;
     * un seul retour par portail.
     */
    private Result checkReturn(WorldPortalDefinition p) {
        if (p.returnOf().isEmpty()) {
            return null;
        }
        Optional<WorldPortalDefinition> target = registry.find(p.returnOf());
        if (target.isEmpty()) {
            return Result.refused("RETURN_TARGET_NOT_FOUND", "Aucun portail « " + p.returnOf() + " » : impossible "
                    + "d'en créer le retour.");
        }
        WorldPortalDefinition t = target.get();
        if (!p.world().equals(t.destinationWorld()) || !p.destinationWorld().equals(t.world())) {
            return Result.refused("RETURN_MISMATCH", "Le retour de « " + t.id() + " » doit partir de « "
                    + t.destinationWorld() + " » et mener à « " + t.world() + " ».");
        }
        for (WorldPortalDefinition other : registry.portals()) {
            if (!other.id().equals(p.id()) && other.returnOf().equals(t.id())) {
                return Result.refused("RETURN_EXISTS", "« " + t.id() + " » a déjà un portail retour : « "
                        + other.id() + " ».");
            }
        }
        return null;
    }

    private record Lookup(WorldPortalDefinition portal, Result error) {
    }

    private Lookup lookup(String id, String expected, boolean confirmEssential, Context ctx) {
        Optional<WorldPortalDefinition> found = registry.find(id == null ? "" : id.strip());
        if (found.isEmpty()) {
            return new Lookup(null, Result.refused("NOT_FOUND", "Aucun portail « " + id + " » chargé."));
        }
        WorldPortalDefinition p = found.get();
        if (expected == null || !expected.equals(fingerprint(p))) {
            return new Lookup(null, Result.refused("STALE", "Le portail a changé depuis l'affichage de la page : "
                    + "rafraîchir les portails puis recommencer."));
        }
        if (essential(p, ctx.roles()) && !confirmEssential) {
            return new Lookup(null, Result.refused("ESSENTIAL_CONFIRMATION_REQUIRED", "« " + p.id() + " » mène au "
                    + "Hub, au Wild ou aux Claims : cocher la confirmation « portail essentiel » pour le modifier."));
        }
        return new Lookup(p, null);
    }

    private record Resolved(String world, Zone zone, String site, int width, int height, int depth, Result error) {

        static Resolved failed(String code, String message) {
            return new Resolved(null, null, "", 0, 0, 0, Result.refused(code, message));
        }
    }

    /** {@code fixedWorld} non {@code null} : la zone doit rester dans ce monde (modification). */
    private Resolved resolve(ZoneRequest req, Context ctx, String fixedWorld) {
        if (req == null) {
            return Resolved.failed("ZONE_REQUIRED", "Zone d'activation manquante.");
        }
        if (req.bySite()) {
            String siteId = req.site().strip();
            Optional<BuildingSite> site = ctx.sites().apply(siteId);
            if (site.isEmpty()) {
                return Resolved.failed("SITE_NOT_FOUND", "Aucun emplacement « " + siteId + " ».");
            }
            BuildingSite s = site.get();
            if (fixedWorld != null && !fixedWorld.equals(s.world())) {
                return Resolved.failed("WORLD_CHANGE_REFUSED", "L'emplacement « " + siteId + " » est dans « "
                        + s.world() + " », pas dans le monde du portail (" + fixedWorld + ").");
            }
            if (!ctx.loadedWorlds().contains(s.world())) {
                return Resolved.failed("SOURCE_NOT_LOADED", "Le monde de l'emplacement « " + s.world()
                        + " » n'est pas chargé.");
            }
            try {
                Zone z = PortalZone.around(s.x(), s.y(), s.z(), s.facing(), req.width(), req.height(), req.depth());
                return new Resolved(s.world(), z, s.id(), req.width(), req.height(), req.depth(), null);
            } catch (IllegalArgumentException e) {
                return Resolved.failed("INVALID_SIZE", e.getMessage());
            }
        }
        String world = fixedWorld != null ? fixedWorld : req.world() == null ? "" : req.world().strip();
        if (req.explicit() == null) {
            return Resolved.failed("ZONE_REQUIRED", "Choisir un emplacement ou saisir les deux coins de la zone.");
        }
        if (!ctx.loadedWorlds().contains(world)) {
            return Resolved.failed("SOURCE_NOT_LOADED", "Le monde de départ « " + world + " » n'est pas chargé.");
        }
        String tooBig = PortalZone.checkExplicit(req.explicit());
        if (tooBig != null) {
            return Resolved.failed("INVALID_SIZE", tooBig);
        }
        return new Resolved(world, req.explicit(), "", 0, 0, 0, null);
    }

    private static Result overlapWithZonePortals(WorldPortalDefinition p, Context ctx) {
        for (PortalDefinition zone : ctx.zonePortals()) {
            if (p.world().equals(zone.world())
                    && p.minX() <= zone.maxX() && p.maxX() >= zone.minX()
                    && p.minY() <= zone.maxY() && p.maxY() >= zone.minY()
                    && p.minZ() <= zone.maxZ() && p.maxZ() >= zone.minZ()) {
                return Result.refused("OVERLAPS", "Cette zone recouvre celle du portail de zone « " + zone.id()
                        + " » (ancien format).");
            }
        }
        return null;
    }

    private Result save(WorldPortalDefinition before, WorldPortalDefinition after, String operation, String actor,
                        String message) {
        return switch (registry.save(after)) {
            case SAVED -> {
                record(actor, operation, after.id(), before, after);
                yield new Result(true, "UPDATED", message, after.id(),
                        List.of("fichier world-portals/" + registry.fileOf(after.id()) + " réécrit",
                                "aucun bloc, aucun emplacement ni aucun joueur touché"));
            }
            case NOT_FOUND -> Result.refused("NOT_FOUND", "Aucun portail « " + after.id() + " » chargé.");
            case OVERLAPS -> Result.refused("OVERLAPS", "Cette zone recouvre celle d'un autre portail de ce monde.");
            case IO_ERROR -> Result.refused("IO_ERROR", "Écriture impossible : voir la console du serveur.");
        };
    }

    private void record(String actor, String operation, String id, WorldPortalDefinition before,
                        WorldPortalDefinition after) {
        audit.append(new PortalAuditLog.Entry(Instant.now(clock).toString(),
                actor == null || actor.isBlank() ? "panel" : actor, operation, id, summary(before), summary(after)));
    }

    private static String checkName(String name) {
        String n = clean(name);
        if (n.length() > MAX_NAME_LENGTH) {
            return "Nom trop long : " + MAX_NAME_LENGTH + " caractères au plus.";
        }
        if (n.indexOf('<') >= 0 || n.indexOf('>') >= 0) {
            return "Le nom ne peut pas contenir « < » ni « > ».";
        }
        return null;
    }

    private static String clean(String name) {
        return name == null ? "" : name.strip();
    }
}
