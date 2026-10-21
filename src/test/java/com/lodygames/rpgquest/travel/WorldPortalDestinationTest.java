package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.travel.WorldPortalAdminService.Context;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.CreateRequest;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.DestinationRequest;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.Result;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.WorldBounds;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.ZoneRequest;
import com.lodygames.rpgquest.travel.WorldPortalVerifier.Report;
import com.lodygames.rpgquest.travel.WorldPortalVerifier.Status;
import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition.FixedPoint;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition.Zone;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.slf4j.helpers.NOPLogger;

/**
 * Issue #566 (#228-P3) : arrivée d'un portail (point fixe et regard, stratégies existantes), refus
 * (monde absent, point hors du monde ou dans un portail), portail retour distinct, et « Vérifier sans
 * appliquer » — sur une copie isolée de {@code world-portals/} et des blocs simulés. Aucun portail,
 * monde ni joueur réel.
 */
class WorldPortalDestinationTest {

    private static final PortalOverview.Roles ROLES = new PortalOverview.Roles("world_hub", "wild", "claims");
    private static final String HUB_TO_WILD = String.join("\n", "id: hub_to_wild", "world: world_hub",
            "min: {x: 737, y: 66, z: -673}", "max: {x: 740, y: 69, z: -673}", "destination-world: wild",
            "enabled: true", "destination-strategy: RANDOM_SAFE", "");
    private static final String HUB_TO_CLAIMS = String.join("\n", "id: hub_to_claims", "world: world_hub",
            "min: {x: 730, y: 66, z: -676}", "max: {x: 732, y: 69, z: -676}", "destination-world: claims",
            "enabled: true", "destination-strategy: CLAIM_HOME", "");
    private static final FixedPoint SQUARE = new FixedPoint(100.5, 64, -20.5, 90f, 10f);

    @TempDir
    Path tempDir;

    private Path dir;
    private WorldPortalRegistry registry;
    private PortalAuditLog audit;
    private WorldPortalAdminService service;
    private Set<String> loaded = Set.of("world_hub", "wild", "claims", "ville");

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock(); // Material#isSolid lit le registre des blocs.
        dir = tempDir.resolve("world-portals");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("hub_to_wild.yml"), HUB_TO_WILD);
        Files.writeString(dir.resolve("hub_to_claims.yml"), HUB_TO_CLAIMS);
        registry = new WorldPortalRegistry(dir, NOPLogger.NOP_LOGGER);
        registry.start();
        audit = new PortalAuditLog(tempDir.resolve("portal-audit.yml"), Runnable::run, NOPLogger.NOP_LOGGER);
        service = new WorldPortalAdminService(registry, audit,
                Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Context ctx() {
        return new Context(loaded, ROLES, List.of(), id -> Optional.empty(),
                w -> loaded.contains(w) ? Optional.of(new WorldBounds(-64, 320)) : Optional.empty());
    }

    private Result createToTown(String id, FixedPoint point) {
        return service.create(new CreateRequest(id, "Vers la ville", ZoneRequest.explicit("world_hub",
                new Zone(700, 64, -650, 702, 67, -650)), "ville", DestinationStrategy.FIXED_POINT, point, ""),
                "claude", ctx());
    }

    private String fp(String id) {
        return WorldPortalAdminService.fingerprint(registry.find(id).orElseThrow());
    }

    // ---- Création et lecture ---------------------------------------------------------------------

    @Test
    void aHubToNewWorldPortalIsConfiguredWithAFixedPointAndLookWithoutRecompiling() throws Exception {
        Result r = createToTown("hub_to_ville", SQUARE);

        assertTrue(r.ok(), r.message());
        String yaml = Files.readString(dir.resolve("hub_to_ville.yml"));
        assertTrue(yaml.contains("destination-strategy: FIXED_POINT"), yaml);
        assertTrue(yaml.contains("destination-point:"), yaml);
        WorldPortalRegistry reread = new WorldPortalRegistry(dir, NOPLogger.NOP_LOGGER);
        reread.start();
        WorldPortalDefinition p = reread.find("hub_to_ville").orElseThrow();
        assertEquals(DestinationStrategy.FIXED_POINT, p.destinationStrategy());
        assertEquals(SQUARE, p.fixedPoint());
        assertFalse(p.enabled(), "toujours créé en brouillon");
        assertTrue(audit.recent(1).get(0).after().contains("FIXED_POINT 100.5, 64.0, -20.5 regard 90.0/10.0"));
    }

    @Test
    void historicalPortalFilesAndFingerprintsAreUnchanged() throws Exception {
        WorldPortalDefinition wild = registry.find("hub_to_wild").orElseThrow();
        assertNull(wild.fixedPoint());
        assertEquals("", wild.returnOf());
        // Empreinte calculée comme avant #566 : une page ouverte avant la mise à jour reste valable.
        String canonical = String.join("|", "hub_to_wild", "world_hub", "737,66,-673", "740,69,-673", "wild",
                "true", "RANDOM_SAFE", "", "", "false", "0x0x0");
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        assertEquals(HexFormat.of().formatHex(hash, 0, 12), WorldPortalAdminService.fingerprint(wild));

        assertTrue(service.setActive("hub_to_wild", false, fp("hub_to_wild"), true, "claude", ctx()).ok());
        String rewritten = Files.readString(dir.resolve("hub_to_wild.yml"));
        assertFalse(rewritten.contains("destination-point"), rewritten);
        assertFalse(rewritten.contains("return-of"), rewritten);
    }

    @Test
    void aFixedPointFileWithoutCoordinatesIsSkippedAtLoad() throws Exception {
        Files.writeString(dir.resolve("broken.yml"), String.join("\n", "id: broken", "world: world_hub",
                "min: {x: 0, y: 64, z: 0}", "max: {x: 1, y: 65, z: 0}", "destination-world: ville",
                "enabled: false", "destination-strategy: FIXED_POINT", ""));
        registry.reload();

        assertTrue(registry.find("broken").isEmpty());
        assertTrue(registry.skippedFiles().stream().anyMatch(s -> s.file().equals("broken.yml")));
    }

    // ---- Refus ----------------------------------------------------------------------------------

    @Test
    void refusalsWriteNothing() throws Exception {
        assertEquals("POINT_REQUIRED", createToTown("sans_point", null).code());
        assertEquals("POINT_OUT_OF_WORLD", createToTown("trop_haut", new FixedPoint(0, 319, 0, 0, 0)).code());
        assertEquals("POINT_OUT_OF_WORLD", createToTown("trop_bas", new FixedPoint(0, -64, 0, 0, 0)).code());
        loaded = Set.of("world_hub", "wild", "claims");
        assertEquals("DESTINATION_NOT_LOADED", createToTown("monde_absent", SQUARE).code());
        try (var files = Files.list(dir)) {
            assertEquals(2, files.count(), "seuls les deux portails historiques");
        }
    }

    @Test
    void aFixedPointInsideAPortalZoneIsRefusedToAvoidALoop() {
        Result r = service.create(new CreateRequest("boucle", "", ZoneRequest.explicit("world_hub",
                new Zone(700, 64, -650, 702, 67, -650)), "world_hub", DestinationStrategy.FIXED_POINT,
                new FixedPoint(738, 66, -673, 0, 0), ""), "claude", ctx());

        assertEquals("POINT_INSIDE_PORTAL", r.code());
        assertTrue(r.message().contains("hub_to_wild"), r.message());
    }

    @Test
    void claimHomeStaysReservedToTheClaimsWorld() {
        Result r = service.create(new CreateRequest("faux_claim", "", ZoneRequest.explicit("world_hub",
                new Zone(700, 64, -650, 702, 67, -650)), "ville", DestinationStrategy.CLAIM_HOME, null, ""),
                "claude", ctx());
        assertEquals("CLAIM_HOME_OUTSIDE_CLAIMS", r.code());
    }

    // ---- Modification ---------------------------------------------------------------------------

    @Test
    void theArrivalOfADraftCanBeReplacedButNotThatOfAnActivePortal() {
        assertTrue(createToTown("hub_to_ville", SQUARE).ok());
        FixedPoint moved = new FixedPoint(120, 70, -30, -45f, 0f);

        Result r = service.update("hub_to_ville", fp("hub_to_ville"), null, null,
                new DestinationRequest("ville", DestinationStrategy.FIXED_POINT, moved), false, "claude", ctx());
        assertTrue(r.ok(), r.message());
        assertEquals(moved, registry.find("hub_to_ville").orElseThrow().fixedPoint());

        Result spawn = service.update("hub_to_ville", fp("hub_to_ville"), null, null,
                new DestinationRequest("ville", DestinationStrategy.WORLD_SPAWN, moved), false, "claude", ctx());
        assertTrue(spawn.ok(), spawn.message());
        assertNull(registry.find("hub_to_ville").orElseThrow().fixedPoint(), "pas de point sans « point fixe »");

        Result locked = service.update("hub_to_wild", fp("hub_to_wild"), null, null,
                new DestinationRequest("wild", DestinationStrategy.FIXED_POINT, SQUARE), true, "claude", ctx());
        assertEquals("ACTIVE_DESTINATION_LOCKED", locked.code());
        assertEquals(DestinationStrategy.RANDOM_SAFE, registry.find("hub_to_wild").orElseThrow().destinationStrategy());
    }

    @Test
    void anEssentialPortalArrivalNeedsTheExtraConfirmation() {
        String before = fp("hub_to_wild");
        assertTrue(service.setActive("hub_to_wild", false, before, true, "claude", ctx()).ok());

        Result r = service.update("hub_to_wild", fp("hub_to_wild"), null, null,
                new DestinationRequest("wild", DestinationStrategy.WORLD_SPAWN, null), false, "claude", ctx());
        assertEquals("ESSENTIAL_CONFIRMATION_REQUIRED", r.code());
    }

    @Test
    void activationRechecksTheArrivalAgainstTheCurrentWorlds() {
        assertTrue(createToTown("hub_to_ville", SQUARE).ok());
        loaded = Set.of("world_hub", "wild", "claims");

        Result r = service.setActive("hub_to_ville", true, fp("hub_to_ville"), false, "claude", ctx());
        assertEquals("DESTINATION_NOT_LOADED", r.code());
        assertFalse(registry.find("hub_to_ville").orElseThrow().enabled());
    }

    // ---- Aller-retour ---------------------------------------------------------------------------

    @Test
    void theReturnIsASecondDistinctDraftThatMirrorsTheWorlds() {
        assertTrue(createToTown("hub_to_ville", SQUARE).ok());
        String outbound = fp("hub_to_ville");

        Result r = service.create(new CreateRequest("ville_to_hub", "", ZoneRequest.explicit("ville",
                new Zone(90, 64, -10, 92, 67, -10)), "world_hub", DestinationStrategy.FIXED_POINT,
                new FixedPoint(705, 64, -655, 180f, 0f), "hub_to_ville"), "claude", ctx());

        assertTrue(r.ok(), r.message());
        WorldPortalDefinition back = registry.find("ville_to_hub").orElseThrow();
        assertEquals("hub_to_ville", back.returnOf());
        assertFalse(back.enabled());
        assertEquals(outbound, fp("hub_to_ville"), "l'aller n'est pas modifié");
    }

    @Test
    void aReturnThatDoesNotMirrorOrDuplicatesIsRefused() {
        assertTrue(createToTown("hub_to_ville", SQUARE).ok());
        Result wrongWorld = service.create(new CreateRequest("retour_faux", "", ZoneRequest.explicit("wild",
                new Zone(0, 64, 0, 1, 65, 0)), "world_hub", DestinationStrategy.WORLD_SPAWN, null, "hub_to_ville"),
                "claude", ctx());
        assertEquals("RETURN_MISMATCH", wrongWorld.code());

        Result unknown = service.create(new CreateRequest("retour_rien", "", ZoneRequest.explicit("ville",
                new Zone(0, 64, 0, 1, 65, 0)), "world_hub", DestinationStrategy.WORLD_SPAWN, null, "inconnu"),
                "claude", ctx());
        assertEquals("RETURN_TARGET_NOT_FOUND", unknown.code());

        assertTrue(service.create(new CreateRequest("retour_1", "", ZoneRequest.explicit("ville",
                new Zone(0, 64, 0, 1, 65, 0)), "world_hub", DestinationStrategy.WORLD_SPAWN, null, "hub_to_ville"),
                "claude", ctx()).ok());
        Result twice = service.create(new CreateRequest("retour_2", "", ZoneRequest.explicit("ville",
                new Zone(10, 64, 0, 11, 65, 0)), "world_hub", DestinationStrategy.WORLD_SPAWN, null, "hub_to_ville"),
                "claude", ctx());
        assertEquals("RETURN_EXISTS", twice.code());
    }

    // ---- Vérifier sans appliquer ---------------------------------------------------------------

    private Report verify(String id, Optional<ArrivalSafety.BlockView> view) {
        return new WorldPortalVerifier(registry).verify(id, ctx(), (w, x, z) -> view);
    }

    @Test
    void verificationOfASafeFixedPointIsOkAndChangesNothing() throws Exception {
        assertTrue(createToTown("hub_to_ville", SQUARE).ok());
        String before = Files.readString(dir.resolve("hub_to_ville.yml"));

        Report r = verify("hub_to_ville", Optional.of(new ArrivalSafetyTest.FakeView()));

        assertEquals(Status.OK, r.verdict(), r.checks().toString());
        assertEquals(fp("hub_to_ville"), r.fingerprint());
        assertEquals(before, Files.readString(dir.resolve("hub_to_ville.yml")));
        assertEquals(1, audit.recent(10).size(), "seule la création est journalisée");
    }

    @Test
    void verificationNeverLoadsChunksAndSaysWhenItCannotTell() {
        assertTrue(createToTown("hub_to_ville", SQUARE).ok());

        Report r = verify("hub_to_ville", Optional.empty());

        assertEquals(Status.WARNING, r.verdict());
        assertTrue(r.checks().stream().anyMatch(c -> c.code().equals("ARRIVAL_SAFETY") && c.status() == Status.UNKNOWN));
    }

    @Test
    void verificationReportsAnAdjustedOrAnImpossibleArrival() {
        assertTrue(createToTown("hub_to_ville", SQUARE).ok());
        ArrivalSafetyTest.FakeView lavaUnderPoint = new ArrivalSafetyTest.FakeView().set(100, 63, -21, Material.LAVA);
        Report adjusted = verify("hub_to_ville", Optional.of(lavaUnderPoint));
        assertEquals(Status.WARNING, adjusted.verdict());

        ArrivalSafetyTest.FakeView lavaLake = new ArrivalSafetyTest.FakeView();
        for (int x = 97; x <= 103; x++) {
            for (int z = -24; z <= -18; z++) {
                for (int y = 60; y <= 68; y++) {
                    lavaLake.set(x, y, z, Material.LAVA);
                }
            }
        }
        Report impossible = verify("hub_to_ville", Optional.of(lavaLake));
        assertEquals(Status.BLOCKING, impossible.verdict());
    }

    @Test
    void verificationFlagsAMissingWorldAndAReturnThatNoLongerMirrors() throws Exception {
        assertTrue(createToTown("hub_to_ville", SQUARE).ok());
        assertTrue(service.create(new CreateRequest("ville_to_hub", "", ZoneRequest.explicit("ville",
                new Zone(0, 64, 0, 1, 65, 0)), "world_hub", DestinationStrategy.WORLD_SPAWN, null, "hub_to_ville"),
                "claude", ctx()).ok());
        assertTrue(service.update("hub_to_ville", fp("hub_to_ville"), null, null,
                new DestinationRequest("wild", DestinationStrategy.WORLD_SPAWN, null), true, "claude", ctx()).ok(),
                "l'aller peut changer ; c'est le retour qui le signale");

        Report back = verify("ville_to_hub", Optional.empty());
        assertTrue(back.checks().stream().anyMatch(c -> c.code().equals("RETURN") && c.status() == Status.WARNING));

        loaded = Set.of("world_hub", "wild", "claims");
        Report missing = verify("ville_to_hub", Optional.empty());
        assertEquals(Status.BLOCKING, missing.verdict());
        assertTrue(missing.checks().stream().anyMatch(c -> c.code().equals("SOURCE") && c.status() == Status.BLOCKING));
    }

    @Test
    void historicalStrategiesAreVerifiedWithoutReadingAnyBlock() {
        Report wild = verify("hub_to_wild", Optional.empty());
        Report claims = verify("hub_to_claims", Optional.empty());
        assertEquals(Status.OK, wild.verdict(), wild.checks().toString());
        assertEquals(Status.OK, claims.verdict(), claims.checks().toString());
    }
}
