package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.BuildingDefinition;
import com.lodygames.rpgquest.building.model.BuildingPlacement;
import com.lodygames.rpgquest.building.model.BuildingSite;
import com.lodygames.rpgquest.building.model.Facing;
import com.lodygames.rpgquest.building.model.PortalPassage;
import com.lodygames.rpgquest.building.model.SiteStatus;
import com.lodygames.rpgquest.building.plan.StructureKind;
import com.lodygames.rpgquest.travel.PortalBuildingLink.SiteBuilding;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.Context;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.CreateRequest;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.Result;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.WorldBounds;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.ZoneRequest;
import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.PortalAccess;
import com.lodygames.rpgquest.travel.model.PortalMessages;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition.FixedPoint;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition.State;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition.Zone;
import com.lodygames.rpgquest.world.create.WorldCreationPlanner;
import com.lodygames.rpgquest.world.create.WorldCreationRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

/**
 * Issue #570 (#228 / #331) : parcours de recette mondes/portails joué de bout en bout, entièrement
 * simulé — mêmes étapes, dans le même ordre, que la checklist propriétaire TC-398 de
 * {@code docs/MANUAL_TEST_PLAN.md}. Monde « créé » = un nom ajouté à un ensemble en mémoire, arches
 * = poses fictives, {@code world-portals/} = copie isolée ; aucun monde, bloc, portail ni joueur réel.
 */
class WorldPortalRecetteScenarioTest {

    private static final PortalOverview.Roles ROLES = new PortalOverview.Roles("world_hub", "wild", "claims");
    private static final String SITE = "buildsite_0042";
    private static final String PORTAL = "hub_to_recette";
    /** Arche 5 × 5 × 1 de la Bibliothèque, passage 3 de large × 4 de haut centré sur l'ancre. */
    private static final BuildingDefinition ARCH = arch("arche_bibliotheque", new PortalPassage(1, 0, 0, 3, 3, 0));
    /** Arche de remplacement : passage 5 de large × 3 de haut. */
    private static final BuildingDefinition WIDE_ARCH = arch("grande_arche", new PortalPassage(0, 0, 0, 4, 2, 0));
    private static final FixedPoint ARRIVAL = new FixedPoint(10.5, 70, -4.5, 180f, 0f);

    @TempDir
    Path tempDir;

    private Path dir;
    private WorldPortalRegistry registry;
    private PortalAuditLog audit;
    private WorldPortalAdminService service;
    private WorldPortalVerifier verifier;
    private final Set<String> loaded = new HashSet<>(Set.of("world", "world_hub", "wild", "claims"));
    private final Map<String, BuildingSite> sites = new HashMap<>();
    private final Map<String, SiteBuilding> placed = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        dir = tempDir.resolve("world-portals");
        Files.createDirectories(dir);
        registry = new WorldPortalRegistry(dir, NOPLogger.NOP_LOGGER);
        registry.start();
        audit = new PortalAuditLog(tempDir.resolve("portal-audit.yml"), Runnable::run, NOPLogger.NOP_LOGGER);
        service = new WorldPortalAdminService(registry, audit,
                Clock.fixed(Instant.parse("2026-10-21T22:00:00Z"), ZoneOffset.UTC));
        verifier = new WorldPortalVerifier(registry);
        sites.put(SITE, new BuildingSite(SITE, "Arche de la Bibliothèque", "", "", "world_hub",
                700, 64, -650, Facing.NORTH, SiteStatus.EMPTY, "LoDyMcFly", Instant.EPOCH));
    }

    private static BuildingDefinition arch(String id, PortalPassage passage) {
        return new BuildingDefinition(id, "Arche " + id, "", id + ".schem", 5, 5, 1, 2, 0, 0, Facing.NORTH,
                List.of("minecraft:stone_bricks"), 1, StructureKind.PORTAL, passage);
    }

    private void place(BuildingDefinition definition) {
        var f = definition.footprintAt("world_hub", 700, 64, -650, 0);
        placed.put(SITE, new SiteBuilding(new BuildingPlacement(SITE, definition.id(), 1, "", "world_hub",
                700, 64, -650, 0, f.minX(), f.minY(), f.minZ(), f.maxX(), f.maxY(), f.maxZ(),
                "backup.schem", "claude", Instant.EPOCH), definition));
    }

    private Context ctx() {
        return new Context(Set.copyOf(loaded), ROLES, List.of(), id -> Optional.ofNullable(sites.get(id)),
                w -> loaded.contains(w) ? Optional.of(WorldBounds.OVERWORLD) : Optional.empty(), q -> true,
                id -> Optional.ofNullable(placed.get(id)));
    }

    private WorldPortalDefinition portal() {
        return registry.find(PORTAL).orElseThrow();
    }

    private String fp() {
        return WorldPortalAdminService.fingerprint(portal());
    }

    private WorldPortalVerifier.Report verify() {
        return verifier.verify(PORTAL, ctx(), (w, x, z) -> Optional.empty());
    }

    private static WorldCreationPlanner.Context worldServer(Set<String> loadedWorlds) {
        return new WorldCreationPlanner.Context(true, null, "world", loadedWorlds, Set.of("world_hub", "claims"),
                loadedWorlds, Set.of("world", "world_hub", "wild", "claims"), b -> true, b -> true);
    }

    @Test
    void theWholeOwnerChecklistRunsInOrderWithoutTouchingTheArchOrTheSite() throws Exception {
        // 1. Monde de test : le plan est accepté, sans rôle Hub / Wild / Claims ; la « création » simulée le charge.
        WorldCreationPlanner.Plan plan = WorldCreationPlanner.plan(new WorldCreationRequest("Recette portails",
                "NORMAL", "NORMAL", "", true, "", List.of(), ""), worldServer(Set.copyOf(loaded)));
        assertTrue(plan.ok(), String.join(" | ", plan.errors()));
        String world = plan.world();
        assertEquals("recette_portails", world);
        loaded.add(world);

        // 2. Brouillon Hub → monde de test depuis l'emplacement #213, arrivée « point fixe ».
        Result created = service.create(new CreateRequest(PORTAL, "Vers la recette", ZoneRequest.fromSite(SITE, 3, 4, 1),
                world, DestinationStrategy.FIXED_POINT, ARRIVAL, ""), "claude", ctx());
        assertTrue(created.ok(), created.message());
        assertEquals(State.DRAFT, portal().state());
        assertFalse(WorldPortalAdminService.essential(portal(), ROLES), "un monde de test n'est pas essentiel");

        // 3. Arche de la Bibliothèque posée puis liée : la zone devient son passage, toujours en brouillon.
        assertEquals("NO_BUILDING", service.linkBuilding(PORTAL, fp(), false, "claude", ctx()).code());
        place(ARCH);
        Result linked = service.linkBuilding(PORTAL, fp(), false, "claude", ctx());
        assertTrue(linked.ok(), linked.message());
        assertEquals(new Zone(699, 64, -650, 701, 67, -650), portal().zone());
        assertEquals(State.DRAFT, portal().state(), "lier n'active jamais");

        // 4. Condition de niveau et avertissement « Toujours » : écrits dans le fichier, relus au rechargement.
        PortalAccess level = new PortalAccess(5, "", List.of(), 0, "");
        assertTrue(service.setAccess(PORTAL, fp(), level, false, "claude", ctx()).ok());
        PortalMessages warning = PortalMessages.NONE.withWarning(new PortalMessages.Warning(PortalMessages.Mode.ALWAYS,
                "Monde de recette", "Ce monde sert aux essais.", "", "", "", "", "", true));
        assertTrue(service.setMessages(PORTAL, fp(), warning, false, "claude", ctx()).ok());
        WorldPortalRegistry reread = new WorldPortalRegistry(dir, NOPLogger.NOP_LOGGER);
        reread.start();
        WorldPortalDefinition onDisk = reread.find(PORTAL).orElseThrow();
        assertEquals(Integer.valueOf(5), onDisk.access().requiredLevel());
        assertTrue(onDisk.messages().warns(false), "avertissement même hors Wild");
        assertEquals(ARRIVAL, onDisk.fixedPoint());
        assertEquals("arche_bibliotheque", onDisk.details().building());

        // 5. « Vérifier sans appliquer » : rien de bloquant, aucun chunk lu, aucune écriture.
        String beforeVerify = fp();
        WorldPortalVerifier.Report report = verify();
        assertNotEquals(WorldPortalVerifier.Status.BLOCKING, report.verdict(), report.checks().toString());
        assertEquals(beforeVerify, fp());

        // 6. Activation ; une page restée ouverte sur l'ancienne empreinte est refusée (double clic).
        String stale = fp();
        assertTrue(service.setActive(PORTAL, true, stale, false, "claude", ctx()).ok());
        assertEquals(State.ACTIVE, portal().state());
        assertEquals("STALE", service.setActive(PORTAL, false, stale, false, "claude", ctx()).code());
        assertEquals("ACTIVE_ACCESS_LOCKED", service.setAccess(PORTAL, fp(), PortalAccess.NONE, false, "claude",
                ctx()).code());
        assertEquals("ACTIVE_ZONE_LOCKED", service.linkBuilding(PORTAL, fp(), false, "claude", ctx()).code());
        assertEquals("ACTIVE_DELETE_REFUSED", service.delete(PORTAL, fp(), false, "claude", ctx()).code());

        // 7. Désactivation : configuration conservée.
        assertTrue(service.setActive(PORTAL, false, fp(), false, "claude", ctx()).ok());
        assertEquals(State.DISABLED, portal().state());
        assertEquals(Integer.valueOf(5), portal().access().requiredLevel());

        // 8. Remplacement d'arche : activation refusée tant que le lien n'est pas relu, puis nouveau passage.
        place(WIDE_ARCH);
        assertEquals("BUILDING_LINK_BROKEN", service.setActive(PORTAL, true, fp(), false, "claude", ctx()).code());
        assertTrue(service.linkBuilding(PORTAL, fp(), false, "claude", ctx()).ok());
        assertEquals(new Zone(698, 64, -650, 702, 66, -650), portal().zone());
        assertEquals(world, portal().destinationWorld(), "l'arrivée ne change pas");
        assertEquals(ARRIVAL, portal().fixedPoint());

        // 9. Déliaison puis suppression : l'arche et l'emplacement restent, aucun bloc touché.
        assertTrue(service.unlinkBuilding(PORTAL, fp(), false, "claude", ctx()).ok());
        Result deleted = service.delete(PORTAL, fp(), false, "claude", ctx());
        assertTrue(deleted.ok(), deleted.message());
        assertTrue(registry.find(PORTAL).isEmpty());
        assertFalse(Files.exists(dir.resolve(PORTAL + ".yml")));
        assertTrue(placed.containsKey(SITE), "supprimer le portail ne démolit pas l'arche");
        assertTrue(sites.containsKey(SITE), "ni l'emplacement");
        assertTrue(deleted.effects().stream().anyMatch(e -> e.contains("aucun bloc touché")), deleted.effects().toString());

        // Journal : chaque étape écrite, dans l'ordre ; les refus n'y figurent pas.
        List<String> operations = audit.recent(20).stream().map(PortalAuditLog.Entry::operation).toList().reversed();
        assertEquals(List.of("CREATE_DRAFT", "LINK_BUILDING", "ACCESS", "MESSAGES", "ENABLE", "DISABLE",
                "LINK_BUILDING", "UNLINK_BUILDING", "DELETE"), operations);
    }

    @Test
    void anUnloadedTestWorldBlocksActivationWithoutWritingAnything() throws Exception {
        loaded.add("recette_portails");
        assertTrue(service.create(new CreateRequest(PORTAL, "Vers la recette", ZoneRequest.fromSite(SITE, 3, 4, 1),
                "recette_portails", DestinationStrategy.WORLD_SPAWN), "claude", ctx()).ok());
        String file = Files.readString(dir.resolve(PORTAL + ".yml"));
        loaded.remove("recette_portails"); // monde déchargé entre-temps (W4)

        assertEquals("DESTINATION_NOT_LOADED", service.setActive(PORTAL, true, fp(), false, "claude", ctx()).code());
        assertEquals(WorldPortalVerifier.Status.BLOCKING, verify().verdict());
        assertEquals(file, Files.readString(dir.resolve(PORTAL + ".yml")));
        assertEquals(State.DRAFT, portal().state());
    }
}
