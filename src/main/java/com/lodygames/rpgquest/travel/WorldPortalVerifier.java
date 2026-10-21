package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.travel.model.PortalDefinition;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * « Vérifier sans appliquer » (issue #566, action {@code portal.verify}) : contrôle un portail entre
 * mondes enregistré — départ, zone, arrivée, sécurité de l'arrivée, lien de retour — <strong>sans
 * rien modifier</strong> : aucun fichier écrit, aucun monde ni chunk chargé, aucun joueur déplacé.
 *
 * <p>{@link Report#fingerprint} est l'empreinte du portail vérifié : la page sait si le résultat
 * précède une modification.</p>
 *
 * <p>La sécurité d'un point fixe n'est lue que si les chunks autour sont <em>déjà</em> chargés
 * ({@link Probe}) ; sinon le contrôle est « non vérifiable maintenant » et reste fait à chaque passage
 * par {@code WorldPortalTeleportListener}.</p>
 */
public final class WorldPortalVerifier {

    /** Statut d'un contrôle. */
    public enum Status {
        OK, WARNING, BLOCKING, UNKNOWN
    }

    public record Check(String code, Status status, String title, String message) {
    }

    /**
     * @param verdict {@code OK} (tout passe), {@code WARNING} (à regarder) ou {@code BLOCKING} (activation
     *                ou passage refusés en l'état)
     */
    public record Report(String portal, Status verdict, List<Check> checks, String fingerprint) {
    }

    /** Lecture du monde d'arrivée, sans chargement. */
    public interface Probe {

        /**
         * Vue des blocs autour du point fixe en (x, z), vide si un chunk n'était pas déjà chargé ou le
         * monde absent (voir {@code ArrivalSafety#snapshotIfLoaded}).
         */
        Optional<ArrivalSafety.BlockView> loadedView(String world, int x, int z);
    }

    private final WorldPortalRegistry registry;

    public WorldPortalVerifier(WorldPortalRegistry registry) {
        this.registry = registry;
    }

    public Report verify(String id, WorldPortalAdminService.Context ctx, Probe probe) {
        Optional<WorldPortalDefinition> found = registry.find(id == null ? "" : id.strip());
        if (found.isEmpty()) {
            return new Report(id, Status.BLOCKING, List.of(new Check("NOT_FOUND", Status.BLOCKING, "Portail",
                    "Aucun portail « " + id + " » chargé.")), "");
        }
        WorldPortalDefinition p = found.get();
        List<Check> checks = new ArrayList<>();
        checks.add(state(p));
        checks.add(source(p, ctx));
        checks.add(zone(p, ctx));
        checks.add(building(p, ctx));
        checks.add(destinationWorld(p, ctx));
        checks.addAll(arrival(p, ctx, probe));
        checks.add(returnLink(p));
        Status verdict = Status.OK;
        for (Check c : checks) {
            if (c.status() == Status.BLOCKING) {
                verdict = Status.BLOCKING;
            } else if (verdict == Status.OK && (c.status() == Status.WARNING || c.status() == Status.UNKNOWN)) {
                verdict = Status.WARNING;
            }
        }
        return new Report(p.id(), verdict, List.copyOf(checks), WorldPortalAdminService.fingerprint(p));
    }

    private static Check state(WorldPortalDefinition p) {
        return switch (p.state()) {
            case DRAFT -> new Check("STATE", Status.OK, "État", "Brouillon : il ne téléporte personne.");
            case DISABLED -> new Check("STATE", Status.OK, "État", "Désactivé : il ne téléporte personne.");
            case ACTIVE -> new Check("STATE", Status.OK, "État", "Actif : entrer dans sa zone téléporte.");
        };
    }

    private static Check source(WorldPortalDefinition p, WorldPortalAdminService.Context ctx) {
        if (!ctx.loadedWorlds().contains(p.world())) {
            return new Check("SOURCE", Status.BLOCKING, "Départ", "Le monde de départ « " + p.world()
                    + " » n'est pas chargé : personne ne peut atteindre ce portail, et il ne peut pas être activé.");
        }
        String site = p.details().site();
        if (!site.isEmpty() && ctx.sites().apply(site).isEmpty()) {
            return new Check("SOURCE", Status.BLOCKING, "Départ", "L'emplacement « " + site
                    + " » n'existe plus : le portail ne peut pas être activé.");
        }
        return new Check("SOURCE", Status.OK, "Départ", "Monde « " + p.world() + " » chargé"
                + (site.isEmpty() ? "." : ", emplacement « " + site + " » présent."));
    }

    private Check zone(WorldPortalDefinition p, WorldPortalAdminService.Context ctx) {
        String size = (p.maxX() - p.minX() + 1) + " × " + (p.maxY() - p.minY() + 1) + " × " + (p.maxZ() - p.minZ() + 1);
        for (WorldPortalDefinition other : registry.portalsInWorld(p.world())) {
            if (!other.id().equals(p.id()) && p.overlaps(other)) {
                return new Check("ZONE", Status.BLOCKING, "Zone d'activation", "Sa zone recouvre celle de « "
                        + other.id() + " ».");
            }
        }
        for (PortalDefinition zone : ctx.zonePortals()) {
            if (p.world().equals(zone.world()) && p.minX() <= zone.maxX() && p.maxX() >= zone.minX()
                    && p.minY() <= zone.maxY() && p.maxY() >= zone.minY()
                    && p.minZ() <= zone.maxZ() && p.maxZ() >= zone.minZ()) {
                return new Check("ZONE", Status.BLOCKING, "Zone d'activation", "Sa zone recouvre celle du portail "
                        + "de zone « " + zone.id() + " ».");
            }
        }
        return new Check("ZONE", Status.OK, "Zone d'activation", size + " blocs dans « " + p.world()
                + " », ne recouvre aucun autre portail.");
    }

    /**
     * Issue #568 : la construction liée. Sans lien, simple information ; lien rompu = bloquant (la zone ne
     * correspond plus à l'arche, l'activation est refusée).
     */
    private static Check building(WorldPortalDefinition p, WorldPortalAdminService.Context ctx) {
        WorldPortalDefinition.Details d = p.details();
        PortalBuildingLink.Status link = PortalBuildingLink.status(p,
                d.site().isEmpty() ? Optional.empty() : ctx.buildings().apply(d.site()));
        return switch (link) {
            case NONE -> new Check("BUILDING", Status.OK, "Construction", "Aucune construction liée : zone saisie.");
            case OK -> new Check("BUILDING", Status.OK, "Construction", "Arche « " + d.building() + " » posée sur « "
                    + d.site() + " » : la zone est exactement son passage.");
            default -> new Check("BUILDING", Status.BLOCKING, "Construction", PortalBuildingLink.describe(link, p));
        };
    }

    private static Check destinationWorld(WorldPortalDefinition p, WorldPortalAdminService.Context ctx) {
        if (!ctx.loadedWorlds().contains(p.destinationWorld())) {
            return new Check("DESTINATION_WORLD", Status.BLOCKING, "Monde d'arrivée", "« " + p.destinationWorld()
                    + " » n'est pas chargé : le passage est refusé avec un message (le portail ne charge jamais un "
                    + "monde), et l'activation est refusée.");
        }
        return new Check("DESTINATION_WORLD", Status.OK, "Monde d'arrivée", "« " + p.destinationWorld()
                + " » chargé.");
    }

    private List<Check> arrival(WorldPortalDefinition p, WorldPortalAdminService.Context ctx, Probe probe) {
        String dest = p.destinationWorld();
        return switch (p.destinationStrategy()) {
            case WORLD_SPAWN -> List.of(new Check("ARRIVAL", Status.OK, "Arrivée",
                    "Point d'arrivée du monde « " + dest + " », relu à chaque passage."));
            case RANDOM_SAFE -> List.of(new Check("ARRIVAL", Status.OK, "Arrivée",
                    "Point sûr aléatoire cherché à chaque passage (travel.random-safe-arrival) ; repli sur le point "
                            + "d'arrivée du monde."));
            case CLAIM_HOME -> List.of(dest.equals(ctx.roles().claims())
                    ? new Check("ARRIVAL", Status.OK, "Arrivée", "Claim du joueur, sinon point sûr loin des claims "
                            + "(claims.arrival), puis repli aléatoire et point d'arrivée du monde.")
                    : new Check("ARRIVAL", Status.BLOCKING, "Arrivée", "« Claim du joueur » vers « " + dest
                            + " », qui n'est pas le monde des claims (" + ctx.roles().claims() + ")."));
            case FIXED_POINT -> fixedPoint(p, ctx, probe);
        };
    }

    private List<Check> fixedPoint(WorldPortalDefinition p, WorldPortalAdminService.Context ctx, Probe probe) {
        WorldPortalDefinition.FixedPoint fp = p.fixedPoint();
        String dest = p.destinationWorld();
        String where = fp.blockX() + ", " + fp.blockY() + ", " + fp.blockZ();
        List<Check> out = new ArrayList<>();
        out.add(new Check("ARRIVAL", Status.OK, "Arrivée", "Point fixe dans « " + dest + " » : "
                + WorldPortalAdminService.pointText(fp) + "."));
        WorldPortalAdminService.WorldBounds b = ctx.bounds().apply(dest).orElse(WorldPortalAdminService.WorldBounds.OVERWORLD);
        if (fp.blockY() - 1 < b.minY() || fp.blockY() + 1 >= b.maxY()) {
            out.add(new Check("ARRIVAL_HEIGHT", Status.BLOCKING, "Hauteur du point", "Y " + fp.blockY()
                    + " hors des hauteurs du monde (" + (b.minY() + 1) + " à " + (b.maxY() - 2) + ")."));
            return out;
        }
        for (WorldPortalDefinition other : registry.portalsInWorld(dest)) {
            if (other.contains(dest, fp.blockX(), fp.blockY(), fp.blockZ())
                    || other.contains(dest, fp.blockX(), fp.blockY() + 1, fp.blockZ())) {
                out.add(new Check("ARRIVAL_LOOP", Status.BLOCKING, "Boucle", "Le point est dans la zone de « "
                        + other.id() + " » : le joueur y serait renvoyé aussitôt."));
                return out;
            }
        }
        if (!ctx.loadedWorlds().contains(dest)) {
            out.add(new Check("ARRIVAL_SAFETY", Status.UNKNOWN, "Sécurité de l'arrivée", "Non vérifiable : le monde "
                    + "n'est pas chargé."));
            return out;
        }
        Optional<ArrivalSafety.BlockView> view = probe.loadedView(dest, fp.blockX(), fp.blockZ());
        if (view.isEmpty()) {
            out.add(new Check("ARRIVAL_SAFETY", Status.UNKNOWN, "Sécurité de l'arrivée", "Non vérifiable maintenant : "
                    + "personne n'est près de " + where + ", ces chunks ne sont pas chargés et la vérification n'en "
                    + "charge jamais. Le contrôle est fait à chaque passage ; point non sûr = passage refusé."));
            return out;
        }
        ArrivalSafety.Verdict verdict = ArrivalSafety.check(view.get(), fp.blockX(), fp.blockY(), fp.blockZ());
        if (verdict == ArrivalSafety.Verdict.SAFE) {
            out.add(new Check("ARRIVAL_SAFETY", Status.OK, "Sécurité de l'arrivée", "Position sûre : sol solide, "
                    + "place pour le joueur, aucun bloc dangereux."));
            return out;
        }
        Optional<ArrivalSafety.Spot> near = ArrivalSafety.findNear(view.get(), fp.blockX(), fp.blockY(), fp.blockZ());
        out.add(near.isPresent()
                ? new Check("ARRIVAL_SAFETY", Status.WARNING, "Sécurité de l'arrivée", where + " : "
                        + verdict.label() + ". En l'état, le joueur arriverait au plus proche point sûr, "
                        + near.get().x() + ", " + near.get().y() + ", " + near.get().z() + " : corriger le point.")
                : new Check("ARRIVAL_SAFETY", Status.BLOCKING, "Sécurité de l'arrivée", where + " : " + verdict.label()
                        + ", et aucune position sûre à " + ArrivalSafety.HORIZONTAL_RADIUS + " blocs autour : le "
                        + "passage serait refusé."));
        return out;
    }

    private Check returnLink(WorldPortalDefinition p) {
        if (!p.returnOf().isEmpty()) {
            Optional<WorldPortalDefinition> target = registry.find(p.returnOf());
            if (target.isEmpty()) {
                return new Check("RETURN", Status.WARNING, "Aller-retour", "Retour de « " + p.returnOf()
                        + " », qui n'existe plus.");
            }
            WorldPortalDefinition t = target.get();
            boolean mirrors = p.world().equals(t.destinationWorld()) && p.destinationWorld().equals(t.world());
            return mirrors
                    ? new Check("RETURN", Status.OK, "Aller-retour", "Retour de « " + t.id() + " » ("
                            + t.world() + " ⇄ " + t.destinationWorld() + ").")
                    : new Check("RETURN", Status.WARNING, "Aller-retour", "Retour de « " + t.id() + " », mais « "
                            + t.id() + " » mène maintenant de « " + t.world() + " » à « " + t.destinationWorld()
                            + " » : les deux portails ne se répondent plus.");
        }
        List<String> returns = registry.portals().stream().filter(o -> o.returnOf().equals(p.id()))
                .map(WorldPortalDefinition::id).toList();
        return returns.isEmpty()
                ? new Check("RETURN", Status.OK, "Aller-retour", "Aucun portail retour (facultatif).")
                : new Check("RETURN", Status.OK, "Aller-retour", "Portail retour : « " + String.join(", ", returns)
                        + " ».");
    }
}
