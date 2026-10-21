package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.BuildingDefinition;
import com.lodygames.rpgquest.building.model.BuildingPlacement;
import com.lodygames.rpgquest.building.model.BuildingSite;
import com.lodygames.rpgquest.building.model.Facing;
import com.lodygames.rpgquest.building.model.PortalPassage;
import com.lodygames.rpgquest.building.model.SiteStatus;
import com.lodygames.rpgquest.building.plan.StructureKind;
import com.lodygames.rpgquest.config.RandomSafeArrivalConfig;
import com.lodygames.rpgquest.travel.PortalBuildingLink.SiteBuilding;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.Context;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.CreateRequest;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.Result;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.ZoneRequest;
import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition.State;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition.Zone;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

/**
 * Issue #568 (#228-P5) : liaison portail ↔ arche posée, entièrement simulée — une copie isolée de
 * {@code world-portals/}, des poses fictives en mémoire ; aucun monde, aucun bloc, aucune pose réelle.
 */
class WorldPortalBuildingLinkTest {

    private static final PortalOverview.Roles ROLES = new PortalOverview.Roles("world_hub", "wild", "claims");
    private static final String SITE = "buildsite_0007";
    /** Arche 5 × 5 × 1, porte au nord, passage 3 de large × 4 de haut centré sur l'ancre (2, 0, 0). */
    private static final BuildingDefinition ARCH = arch("arche_test", new PortalPassage(1, 0, 0, 3, 3, 0));

    @TempDir
    Path tempDir;

    private Path dir;
    private WorldPortalRegistry registry;
    private PortalAuditLog audit;
    private WorldPortalAdminService service;
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
                Clock.fixed(Instant.parse("2026-10-21T05:00:00Z"), ZoneOffset.UTC));
        sites.put(SITE, new BuildingSite(SITE, "Arche du marché", "", "", "world_hub",
                700, 64, -650, Facing.NORTH, SiteStatus.EMPTY, "LoDyMcFly", Instant.EPOCH));
        assertTrue(service.create(new CreateRequest("arche_marche", "Arche du marché",
                ZoneRequest.fromSite(SITE, 3, 4, 1), "test_world", DestinationStrategy.WORLD_SPAWN),
                "claude", ctx()).ok());
    }

    private static BuildingDefinition arch(String id, PortalPassage passage) {
        return new BuildingDefinition(id, "Arche " + id, "", id + ".schem", 5, 5, 1, 2, 0, 0, Facing.NORTH,
                List.of("minecraft:stone_bricks"), 1, StructureKind.PORTAL, passage);
    }

    /** Une pose fictive de {@code definition} sur l'emplacement, à son ancre, avec cette rotation. */
    private void place(BuildingDefinition definition, int degrees) {
        var f = definition.footprintAt("world_hub", 700, 64, -650, degrees);
        placed.put(SITE, new SiteBuilding(new BuildingPlacement(SITE, definition.id(), 1, "", "world_hub",
                700, 64, -650, degrees, f.minX(), f.minY(), f.minZ(), f.maxX(), f.maxY(), f.maxZ(),
                "backup.schem", "claude", Instant.EPOCH), definition));
    }

    private Context ctx() {
        return new Context(Set.of("world_hub", "wild", "claims", "test_world"), ROLES, List.of(),
                id -> Optional.ofNullable(sites.get(id)),
                w -> Optional.of(WorldPortalAdminService.WorldBounds.OVERWORLD), q -> true,
                id -> Optional.ofNullable(placed.get(id)));
    }

    private WorldPortalDefinition portal() {
        return registry.find("arche_marche").orElseThrow();
    }

    private Result link() {
        return service.linkBuilding("arche_marche", WorldPortalAdminService.fingerprint(portal()), false, "claude",
                ctx());
    }

    private Result enable() {
        return service.setActive("arche_marche", true, WorldPortalAdminService.fingerprint(portal()), false,
                "claude", ctx());
    }

    @Test
    void linkingReadsThePassageAfterRotationAndNeverActivatesThePortal() throws Exception {
        place(ARCH, 90);

        Result r = link();

        assertTrue(r.ok(), r.message());
        WorldPortalDefinition p = portal();
        // À 90°, le passage (3 de large en X) devient 3 de large en Z, toujours centré sur l'ancre.
        assertEquals(new Zone(700, 64, -651, 700, 67, -649), p.zone());
        assertEquals("arche_test", p.details().building());
        assertEquals(State.DRAFT, p.state(), "lier une arche n'active jamais un portail");
        assertFalse(p.enabled());
        assertEquals("test_world", p.destinationWorld());
        assertEquals("LINK_BUILDING", audit.recent(1).get(0).operation());
        assertTrue(Files.readString(dir.resolve("arche_marche.yml")).contains("building: arche_test"));
        assertEquals(PortalBuildingLink.Status.OK, PortalBuildingLink.status(p, Optional.of(placed.get(SITE))));
    }

    @Test
    void theZoneMatchesThePassageForEveryRotation() {
        int[][] expected = {
            {699, 64, -650, 701, 67, -650}, {700, 64, -651, 700, 67, -649},
            {699, 64, -650, 701, 67, -650}, {700, 64, -651, 700, 67, -649},
        };
        for (int i = 0; i < 4; i++) {
            place(ARCH, i * 90);
            int[] e = expected[i];
            assertEquals(new Zone(e[0], e[1], e[2], e[3], e[4], e[5]),
                    PortalBuildingLink.derive(placed.get(SITE)).zone(), "rotation " + i * 90);
        }
    }

    @Test
    void linkIsRefusedWithoutAPortalArchAndChangesNothing() throws Exception {
        String before = Files.readString(dir.resolve("arche_marche.yml"));

        assertEquals("NO_BUILDING", link().code());
        placed.put(SITE, new SiteBuilding(placementOf("maison"), new BuildingDefinition("maison", "Maison", "",
                "maison.schem", 5, 5, 5, 2, 0, 0, Facing.NORTH, List.of(), 1)));
        assertEquals("NOT_A_PORTAL", link().code());
        place(arch("arche_nue", null), 0);
        assertEquals("NO_PASSAGE", link().code());
        placed.put(SITE, new SiteBuilding(placementOf("arche_retiree"), null));
        assertEquals("DEFINITION_MISSING", link().code());

        assertEquals(before, Files.readString(dir.resolve("arche_marche.yml")));
    }

    @Test
    void anActivePortalCannotBeRelinkedAndAPortalWithoutSiteCannotBeLinked() {
        place(ARCH, 0);
        assertTrue(link().ok());
        Result on = enable();
        assertTrue(on.ok(), on.message());

        assertEquals("ACTIVE_ZONE_LOCKED", link().code());

        assertTrue(service.create(new CreateRequest("sans_site", "", ZoneRequest.explicit("world_hub",
                new Zone(0, 64, 0, 1, 65, 0)), "test_world", DestinationStrategy.WORLD_SPAWN), "claude", ctx()).ok());
        Result r = service.linkBuilding("sans_site",
                WorldPortalAdminService.fingerprint(registry.find("sans_site").orElseThrow()), false, "claude", ctx());
        assertEquals("NO_SITE", r.code());
    }

    @Test
    void activationIsRefusedWhileTheLinkedArchIsReleasedReplacedOrTurned() {
        place(ARCH, 0);
        assertTrue(link().ok());

        placed.remove(SITE); // pose libérée (#234) : rollback du terrain
        assertEquals("BUILDING_LINK_BROKEN", enable().code());

        place(arch("autre_arche", new PortalPassage(0, 0, 0, 4, 2, 0)), 0); // remplacement par une autre arche
        assertEquals("BUILDING_LINK_BROKEN", enable().code());

        place(ARCH, 90); // même arche, réorientée
        assertEquals("BUILDING_LINK_BROKEN", enable().code());
        assertFalse(portal().enabled());
    }

    @Test
    void relinkingAfterAReplacementReadsTheNewPassageAndKeepsTheDestination() {
        place(ARCH, 0);
        assertTrue(link().ok());
        place(arch("grande_arche", new PortalPassage(0, 0, 0, 4, 2, 0)), 0);

        Result r = link();

        assertTrue(r.ok(), r.message());
        WorldPortalDefinition p = portal();
        assertEquals(new Zone(698, 64, -650, 702, 66, -650), p.zone());
        assertEquals("grande_arche", p.details().building());
        assertEquals("test_world", p.destinationWorld());
        assertEquals(DestinationStrategy.WORLD_SPAWN, p.destinationStrategy());
        assertEquals("UNCHANGED", link().code());
        assertTrue(enable().ok(), "activation séparée, une fois le lien relu");
    }

    @Test
    void unlinkingKeepsTheZoneAndTheArch() {
        place(ARCH, 90);
        assertTrue(link().ok());
        Zone linked = portal().zone();

        Result r = service.unlinkBuilding("arche_marche", WorldPortalAdminService.fingerprint(portal()), false,
                "claude", ctx());

        assertTrue(r.ok(), r.message());
        assertEquals("", portal().details().building());
        assertEquals(linked, portal().zone());
        assertTrue(placed.containsKey(SITE), "délier ne démolit rien");
        assertEquals("UNCHANGED", service.unlinkBuilding("arche_marche",
                WorldPortalAdminService.fingerprint(portal()), false, "claude", ctx()).code());
    }

    @Test
    void enteringAZoneByHandDropsTheLink() {
        place(ARCH, 0);
        assertTrue(link().ok());

        Result r = service.update("arche_marche", WorldPortalAdminService.fingerprint(portal()), null,
                ZoneRequest.fromSite(SITE, 3, 3, 1), false, "claude", ctx());

        assertTrue(r.ok(), r.message());
        assertEquals("", portal().details().building());
    }

    @Test
    void theVerifierAndThePortalListReportTheConstruction() {
        place(ARCH, 0);
        assertTrue(link().ok());
        WorldPortalVerifier verifier = new WorldPortalVerifier(registry);

        assertEquals(WorldPortalVerifier.Status.OK, buildingCheck(verifier).status());
        PortalOverview.Construction c = overview().admin().construction();
        assertEquals("arche_test", c.linked());
        assertEquals("OK", c.status());
        assertEquals("arche_test", c.placed());
        assertTrue(c.linkable());
        assertEquals("699,64,-650 → 701,67,-650", c.zone());

        placed.remove(SITE);
        assertEquals(WorldPortalVerifier.Status.BLOCKING, buildingCheck(verifier).status());
        PortalOverview.Entry e = overview();
        assertEquals("BUILDING_MISSING", e.admin().construction().status());
        assertTrue(e.issues().stream().anyMatch(i -> i.code().equals("PORTAL_BUILDING_LINK_BROKEN")));
    }

    @Test
    void aBuildingKeyWithoutSiteIsIgnoredOnLoad() throws Exception {
        Files.writeString(dir.resolve("orphelin.yml"), String.join("\n", "id: orphelin", "world: world_hub",
                "min: {x: 10, y: 64, z: 10}", "max: {x: 11, y: 65, z: 10}", "destination-world: wild",
                "enabled: false", "building: arche_test", ""));
        registry.reload();

        assertEquals("", registry.find("orphelin").orElseThrow().details().building());
    }

    private WorldPortalVerifier.Check buildingCheck(WorldPortalVerifier verifier) {
        return verifier.verify("arche_marche", ctx(), (w, x, z) -> Optional.empty()).checks().stream()
                .filter(c -> c.code().equals("BUILDING")).findFirst().orElseThrow();
    }

    private PortalOverview.Entry overview() {
        var in = new PortalOverview.Inputs(registry.portals(), List.of("arche_marche.yml"), List.of(), List.of(),
                List.of(), List.of(), Map.of("world_hub", "0, 64, 0", "test_world", "0, 64, 0"), ROLES,
                new RandomSafeArrivalConfig(500, 5000, 20), new PortalOverview.ClaimArrival(200, 3000, 64, 40), null,
                id -> Optional.ofNullable(sites.get(id)), List.of(), id -> Optional.ofNullable(placed.get(id)));
        return PortalOverview.build(in).portals().get(0);
    }

    private static BuildingPlacement placementOf(String buildingId) {
        return new BuildingPlacement(SITE, buildingId, 1, "", "world_hub", 700, 64, -650, 0,
                698, 64, -650, 702, 68, -646, "backup.schem", "claude", Instant.EPOCH);
    }
}
