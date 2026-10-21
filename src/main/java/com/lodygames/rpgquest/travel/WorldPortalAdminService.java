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
                          Function<String, Optional<BuildingSite>> sites) {
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

    public record CreateRequest(String id, String name, ZoneRequest zone, String destinationWorld,
                                DestinationStrategy strategy) {
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
     */
    public record Operation(Kind kind, String id, String expected, String name, ZoneRequest zone,
                            String destinationWorld, DestinationStrategy strategy, boolean confirmEssential,
                            String actor) {
    }

    /** Aiguille une {@link Operation} vers l'opération correspondante. */
    public Result apply(Operation op, Context ctx) {
        return switch (op.kind()) {
            case CREATE -> create(new CreateRequest(op.id(), op.name(), op.zone(), op.destinationWorld(),
                    op.strategy()), op.actor(), ctx);
            case UPDATE -> update(op.id(), op.expected(), op.name(), op.zone(), op.confirmEssential(), op.actor(), ctx);
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
        if (!ctx.loadedWorlds().contains(dest)) {
            return Result.refused("DESTINATION_NOT_LOADED", "Le monde d'arrivée « " + dest + " » n'est pas chargé.");
        }
        DestinationStrategy strategy = req.strategy() == null ? DestinationStrategy.WORLD_SPAWN : req.strategy();
        if (strategy == DestinationStrategy.CLAIM_HOME && !dest.equals(ctx.roles().claims())) {
            return Result.refused("CLAIM_HOME_OUTSIDE_CLAIMS", "« Claim du joueur » ne s'utilise que vers le monde "
                    + "des claims (" + ctx.roles().claims() + ").");
        }
        Details details = new Details(clean(req.name()), zone.site(), true, zone.width(), zone.height(), zone.depth());
        Zone z = zone.zone();
        WorldPortalDefinition draft = new WorldPortalDefinition(id, zone.world(), z.minX(), z.minY(), z.minZ(),
                z.maxX(), z.maxY(), z.maxZ(), dest, false, strategy, details);
        Result overlap = overlapWithZonePortals(draft, ctx);
        if (overlap != null) {
            return overlap;
        }
        return switch (registry.create(draft)) {
            case CREATED -> {
                record(actor, "CREATE_DRAFT", id, null, draft);
                yield new Result(true, "CREATED", "Brouillon « " + id + " » créé. Il ne téléporte personne tant "
                        + "qu'il n'est pas activé.", id, List.of("fichier world-portals/" + id + ".yml créé",
                        "aucun bloc, aucun emplacement ni aucun joueur touché"));
            }
            case DUPLICATE_ID -> Result.refused("DUPLICATE_ID", "Un portail ou un fichier world-portals/" + id
                    + ".yml existe déjà.");
            case OVERLAPS -> Result.refused("OVERLAPS", "Cette zone recouvre celle d'un portail existant de ce monde.");
            case IO_ERROR -> Result.refused("IO_ERROR", "Écriture impossible : voir la console du serveur.");
        };
    }

    /**
     * Renommer ({@code name} non {@code null}) et/ou changer la zone ({@code zone} non {@code null}).
     * Le monde de départ ne change pas ; la zone d'un portail actif non plus.
     */
    public synchronized Result update(String id, String expected, String name, ZoneRequest zone,
                                      boolean confirmEssential, String actor, Context ctx) {
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
                + ", vers " + p.destinationWorld() + " (" + p.destinationStrategy() + ")";
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
