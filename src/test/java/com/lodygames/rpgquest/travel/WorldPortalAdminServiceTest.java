package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.BuildingSite;
import com.lodygames.rpgquest.building.model.Facing;
import com.lodygames.rpgquest.building.model.SiteStatus;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.Context;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.CreateRequest;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.Result;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.ZoneRequest;
import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.PortalDefinition;
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
 * Issue #565 : toutes les opérations sur une copie isolée de {@code world-portals/} — aucun
 * serveur, aucun portail réel. Les deux portails historiques de DEV sont reproduits à
 * l'identique pour prouver qu'ils ne bougent pas sans confirmation.
 */
class WorldPortalAdminServiceTest {

    private static final PortalOverview.Roles ROLES = new PortalOverview.Roles("world_hub", "wild", "claims");
    private static final String HUB_TO_WILD = String.join("\n", "id: hub_to_wild", "world: world_hub",
            "min: {x: 737, y: 66, z: -673}", "max: {x: 740, y: 69, z: -673}", "destination-world: wild",
            "enabled: true", "destination-strategy: RANDOM_SAFE", "");
    private static final String HUB_TO_CLAIMS = String.join("\n", "id: hub_to_claims", "world: world_hub",
            "min: {x: 730, y: 66, z: -676}", "max: {x: 732, y: 69, z: -676}", "destination-world: claims",
            "enabled: true", "destination-strategy: CLAIM_HOME", "");

    @TempDir
    Path tempDir;

    private Path dir;
    private WorldPortalRegistry registry;
    private PortalAuditLog audit;
    private WorldPortalAdminService service;
    private final Map<String, BuildingSite> sites = new HashMap<>();
    private List<PortalDefinition> zonePortals = List.of();

    @BeforeEach
    void setUp() throws Exception {
        dir = tempDir.resolve("world-portals");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("hub_to_wild.yml"), HUB_TO_WILD);
        Files.writeString(dir.resolve("hub_to_claims.yml"), HUB_TO_CLAIMS);
        registry = new WorldPortalRegistry(dir, NOPLogger.NOP_LOGGER);
        registry.start();
        audit = new PortalAuditLog(tempDir.resolve("portal-audit.yml"), Runnable::run, NOPLogger.NOP_LOGGER);
        service = new WorldPortalAdminService(registry, audit,
                Clock.fixed(Instant.parse("2026-10-21T05:00:00Z"), ZoneOffset.UTC));
        sites.put("buildsite_0007", new BuildingSite("buildsite_0007", "Arche du marché", "", "", "world_hub",
                700, 64, -650, Facing.NORTH, SiteStatus.EMPTY, "LoDyMcFly", Instant.EPOCH));
    }

    private Context ctx() {
        return new Context(Set.of("world_hub", "wild", "claims", "test_world"), ROLES, zonePortals,
                id -> Optional.ofNullable(sites.get(id)));
    }

    private Result createDraft() {
        return service.create(new CreateRequest("arche_marche", "Arche du marché",
                ZoneRequest.fromSite("buildsite_0007", 3, 4, 1), "test_world", DestinationStrategy.WORLD_SPAWN),
                "claude", ctx());
    }

    private String fp(String id) {
        return WorldPortalAdminService.fingerprint(registry.find(id).orElseThrow());
    }

    @Test
    void aDraftIsCreatedFromASiteWithAnOrientedZoneAndTeleportsNobody() {
        Result r = createDraft();

        assertTrue(r.ok(), r.message());
        WorldPortalDefinition p = registry.find("arche_marche").orElseThrow();
        assertEquals(State.DRAFT, p.state());
        assertFalse(p.enabled());
        assertEquals(new Zone(699, 64, -650, 701, 67, -650), p.zone());
        assertEquals("buildsite_0007", p.details().site());
        assertEquals("Arche du marché", p.details().name());
        assertTrue(registry.portalAt("world_hub", 700, 65, -650).filter(WorldPortalDefinition::enabled).isEmpty(),
                "le listener ne retient que les portails enabled : un brouillon ne téléporte personne");
        assertEquals("CREATE_DRAFT", audit.recent(1).get(0).operation());
        assertEquals("", audit.recent(1).get(0).before());
    }

    @Test
    void creationRefusesAZoneOverlappingAnExistingPortalAndWritesNothing() throws Exception {
        Result r = service.create(new CreateRequest("doublon", "", ZoneRequest.explicit("world_hub",
                new Zone(738, 66, -673, 739, 67, -673)), "wild", DestinationStrategy.WORLD_SPAWN), "claude", ctx());

        assertFalse(r.ok());
        assertEquals("OVERLAPS", r.code());
        assertFalse(Files.exists(dir.resolve("doublon.yml")));
        assertTrue(audit.recent(5).isEmpty());
    }

    @Test
    void creationRefusesAZoneOverlappingALegacyZonePortal() {
        zonePortals = List.of(new PortalDefinition("old_zone", "world_hub", 690, 60, -660, 710, 70, -640, null,
                0, 0, null, null, null, null, null));

        assertEquals("OVERLAPS", createDraft().code());
    }

    @Test
    void creationChecksIdNameWorldsAndStrategy() {
        Context c = ctx();
        ZoneRequest zone = ZoneRequest.fromSite("buildsite_0007", 3, 4, 1);
        assertEquals("INVALID_ID", service.create(new CreateRequest("A B", "", zone, "wild", null), "x", c).code());
        assertEquals("INVALID_NAME", service.create(new CreateRequest("abc", "<red>x", zone, "wild", null), "x", c).code());
        assertEquals("DUPLICATE_ID", service.create(new CreateRequest("hub_to_wild", "", zone, "wild", null), "x", c).code());
        assertEquals("DESTINATION_NOT_LOADED",
                service.create(new CreateRequest("abc", "", zone, "nowhere", null), "x", c).code());
        assertEquals("SITE_NOT_FOUND", service.create(new CreateRequest("abc", "",
                ZoneRequest.fromSite("buildsite_0099", 3, 4, 1), "wild", null), "x", c).code());
        assertEquals("INVALID_SIZE", service.create(new CreateRequest("abc", "",
                ZoneRequest.fromSite("buildsite_0007", 40, 4, 1), "wild", null), "x", c).code());
        assertEquals("CLAIM_HOME_OUTSIDE_CLAIMS",
                service.create(new CreateRequest("abc", "", zone, "wild", DestinationStrategy.CLAIM_HOME), "x", c).code());
    }

    @Test
    void activationAndDeactivationKeepTheConfiguration() {
        createDraft();
        Result on = service.setActive("arche_marche", true, fp("arche_marche"), false, "claude", ctx());
        assertTrue(on.ok(), on.message());
        assertEquals(State.ACTIVE, registry.find("arche_marche").orElseThrow().state());

        Result off = service.setActive("arche_marche", false, fp("arche_marche"), false, "claude", ctx());
        assertTrue(off.ok(), off.message());
        WorldPortalDefinition p = registry.find("arche_marche").orElseThrow();
        assertEquals(State.DISABLED, p.state());
        assertEquals("Arche du marché", p.details().name());
        assertEquals(new Zone(699, 64, -650, 701, 67, -650), p.zone());
        assertEquals(List.of("DISABLE", "ENABLE", "CREATE_DRAFT"),
                audit.recent(5).stream().map(PortalAuditLog.Entry::operation).toList());
    }

    @Test
    void activationRefusesAnUnloadedDestinationOrAMissingSite() {
        createDraft();
        Context noDest = new Context(Set.of("world_hub"), ROLES, List.of(), id -> Optional.ofNullable(sites.get(id)));
        assertEquals("DESTINATION_NOT_LOADED",
                service.setActive("arche_marche", true, fp("arche_marche"), false, "x", noDest).code());

        sites.clear();
        assertEquals("SITE_MISSING", service.setActive("arche_marche", true, fp("arche_marche"), false, "x", ctx()).code());
        assertEquals(State.DRAFT, registry.find("arche_marche").orElseThrow().state());
    }

    @Test
    void aStaleFingerprintIsRefused() {
        createDraft();
        String old = fp("arche_marche");
        service.update("arche_marche", old, "Nouveau nom", null, false, "x", ctx());

        Result r = service.setActive("arche_marche", true, old, false, "x", ctx());

        assertEquals("STALE", r.code());
        assertEquals(State.DRAFT, registry.find("arche_marche").orElseThrow().state());
    }

    @Test
    void essentialPortalsAreUntouchedWithoutTheExtraConfirmation() throws Exception {
        for (String id : List.of("hub_to_wild", "hub_to_claims")) {
            String f = fp(id);
            assertEquals("ESSENTIAL_CONFIRMATION_REQUIRED", service.setActive(id, false, f, false, "x", ctx()).code());
            assertEquals("ESSENTIAL_CONFIRMATION_REQUIRED", service.update(id, f, "Nom", null, false, "x", ctx()).code());
            assertEquals("ESSENTIAL_CONFIRMATION_REQUIRED", service.delete(id, f, false, "x", ctx()).code());
        }
        assertEquals(HUB_TO_WILD, Files.readString(dir.resolve("hub_to_wild.yml")));
        assertEquals(HUB_TO_CLAIMS, Files.readString(dir.resolve("hub_to_claims.yml")));
        assertTrue(audit.recent(5).isEmpty());
    }

    @Test
    void renamingAnEssentialPortalWithConfirmationKeepsItsZoneAndState() {
        Result r = service.update("hub_to_wild", fp("hub_to_wild"), "Vers le Wild", null, true, "x", ctx());

        assertTrue(r.ok(), r.message());
        WorldPortalDefinition p = registry.find("hub_to_wild").orElseThrow();
        assertEquals("Vers le Wild", p.details().name());
        assertEquals(new Zone(737, 66, -673, 740, 69, -673), p.zone());
        assertEquals(State.ACTIVE, p.state());
        assertEquals(DestinationStrategy.RANDOM_SAFE, p.destinationStrategy());
    }

    @Test
    void theZoneOfAnActivePortalIsLockedAndAnActivePortalIsNotDeleted() {
        ZoneRequest moved = ZoneRequest.explicit(null, new Zone(0, 64, 0, 2, 67, 0));
        assertEquals("ACTIVE_ZONE_LOCKED",
                service.update("hub_to_wild", fp("hub_to_wild"), null, moved, true, "x", ctx()).code());
        assertEquals("ACTIVE_DELETE_REFUSED", service.delete("hub_to_wild", fp("hub_to_wild"), true, "x", ctx()).code());
    }

    @Test
    void theZoneOfADraftCanBeResizedButNotMovedToAnotherWorld() {
        createDraft();
        Result r = service.update("arche_marche", fp("arche_marche"), null,
                ZoneRequest.fromSite("buildsite_0007", 5, 5, 2), false, "x", ctx());
        assertTrue(r.ok(), r.message());
        assertEquals(new Zone(698, 64, -650, 702, 68, -649), registry.find("arche_marche").orElseThrow().zone());

        sites.put("buildsite_0008", new BuildingSite("buildsite_0008", "Ailleurs", "", "", "wild", 0, 64, 0,
                Facing.EAST, SiteStatus.EMPTY, "", Instant.EPOCH));
        assertEquals("WORLD_CHANGE_REFUSED", service.update("arche_marche", fp("arche_marche"), null,
                ZoneRequest.fromSite("buildsite_0008", 3, 4, 1), false, "x", ctx()).code());
    }

    @Test
    void deletingADraftRemovesOnlyItsFile() throws Exception {
        createDraft();
        Result r = service.delete("arche_marche", fp("arche_marche"), false, "x", ctx());

        assertTrue(r.ok(), r.message());
        assertTrue(registry.find("arche_marche").isEmpty());
        assertFalse(Files.exists(dir.resolve("arche_marche.yml")));
        assertTrue(sites.containsKey("buildsite_0007"));
        assertTrue(r.effects().stream().anyMatch(e -> e.contains("buildsite_0007")));
        assertEquals(2, registry.portals().size(), "hub_to_wild et hub_to_claims restent chargés");
    }

    @Test
    void anUnchangedUpdateWritesNothing() {
        createDraft();
        Result r = service.update("arche_marche", fp("arche_marche"), "Arche du marché", null, false, "x", ctx());

        assertEquals("UNCHANGED", r.code());
        assertEquals(1, audit.recent(5).size());
    }

    // ---- Conditions d'accès (issue #567) ---------------------------------------------------------

    private static final org.bukkit.NamespacedKey CRYSTAL = new org.bukkit.NamespacedKey("rpgquest", "crystal_hunt");

    private static com.lodygames.rpgquest.travel.model.PortalAccess sampleAccess() {
        return new com.lodygames.rpgquest.travel.model.PortalAccess(5, "<red>Niveau 5.</red>", List.of(
                new com.lodygames.rpgquest.travel.model.PortalAccess.Rule(
                        new com.lodygames.rpgquest.dialogue.model.QuestStateCondition(CRYSTAL,
                                com.lodygames.rpgquest.quest.model.QuestState.COMPLETED), "<red>Termine la chasse.</red>"),
                new com.lodygames.rpgquest.travel.model.PortalAccess.Rule(
                        new com.lodygames.rpgquest.dialogue.model.NegatedCondition(
                                new com.lodygames.rpgquest.dialogue.model.HasMainClaimCondition()), "")), 45, "");
    }

    private Context ctxWithQuests() {
        return new Context(Set.of("world_hub", "wild", "claims", "test_world"), ROLES, zonePortals,
                id -> Optional.ofNullable(sites.get(id)), w -> Optional.of(WorldPortalAdminService.WorldBounds.OVERWORLD),
                CRYSTAL::equals);
    }

    @Test
    void accessConditionsAreSavedOnADraftAndSurviveAReload() throws Exception {
        createDraft();
        Result r = service.setAccess("arche_marche", fp("arche_marche"), sampleAccess(), false, "claude",
                ctxWithQuests());

        assertTrue(r.ok(), r.message());
        registry.reload();
        assertEquals(sampleAccess(), registry.find("arche_marche").orElseThrow().access());
        String yaml = Files.readString(dir.resolve("arche_marche.yml"));
        assertTrue(yaml.contains("access:") && yaml.contains("required-level: 5") && yaml.contains("negate: true"),
                yaml);
        assertEquals(State.DRAFT, registry.find("arche_marche").orElseThrow().state(), "rien n'est activé");
    }

    @Test
    void removingEveryConditionRestoresTheFileWithoutAccessSection() throws Exception {
        createDraft();
        String before = Files.readString(dir.resolve("arche_marche.yml"));
        String fingerprintBefore = fp("arche_marche");
        service.setAccess("arche_marche", fingerprintBefore, sampleAccess(), false, "claude", ctxWithQuests());
        assertFalse(fp("arche_marche").equals(fingerprintBefore), "l'empreinte change avec les conditions");

        Result r = service.setAccess("arche_marche", fp("arche_marche"),
                com.lodygames.rpgquest.travel.model.PortalAccess.NONE, false, "claude", ctxWithQuests());

        assertTrue(r.ok(), r.message());
        assertEquals(before, Files.readString(dir.resolve("arche_marche.yml")));
        assertEquals(fingerprintBefore, fp("arche_marche"));
    }

    @Test
    void theAccessOfAnActivePortalIsLockedAndEssentialPortalsNeedConfirmation() throws Exception {
        String before = Files.readString(dir.resolve("hub_to_wild.yml"));

        Result unconfirmed = service.setAccess("hub_to_wild", fp("hub_to_wild"), sampleAccess(), false, "claude",
                ctxWithQuests());
        Result active = service.setAccess("hub_to_wild", fp("hub_to_wild"), sampleAccess(), true, "claude",
                ctxWithQuests());

        assertEquals("ESSENTIAL_CONFIRMATION_REQUIRED", unconfirmed.code());
        assertEquals("ACTIVE_ACCESS_LOCKED", active.code());
        assertEquals(before, Files.readString(dir.resolve("hub_to_wild.yml")), "hub_to_wild intact");
    }

    @Test
    void anUnknownQuestOrABadlyTypedVariableIsRefused() {
        createDraft();
        var unknownQuest = new com.lodygames.rpgquest.travel.model.PortalAccess(null, "", List.of(
                new com.lodygames.rpgquest.travel.model.PortalAccess.Rule(
                        new com.lodygames.rpgquest.dialogue.model.QuestStateCondition(
                                new org.bukkit.NamespacedKey("rpgquest", "inconnue"),
                                com.lodygames.rpgquest.quest.model.QuestState.ACTIVE), "")), 0, "");
        var badVariable = new com.lodygames.rpgquest.travel.model.PortalAccess(null, "", List.of(
                new com.lodygames.rpgquest.travel.model.PortalAccess.Rule(
                        new com.lodygames.rpgquest.dialogue.model.VariableEqualsCondition("CLAIM_TIER_1", "peut-être"),
                        "")), 0, "");

        assertEquals("UNKNOWN_QUEST", service.setAccess("arche_marche", fp("arche_marche"), unknownQuest, false,
                "claude", ctxWithQuests()).code());
        assertEquals("INVALID_VARIABLE", service.setAccess("arche_marche", fp("arche_marche"), badVariable, false,
                "claude", ctxWithQuests()).code());
        assertTrue(registry.find("arche_marche").orElseThrow().access().isEmpty());
    }

    @Test
    void anUnreadableAccessSectionKeepsThePortalUnloaded() throws Exception {
        Files.writeString(dir.resolve("arche.yml"), String.join("\n", "id: arche", "world: world_hub",
                "min: {x: 0, y: 60, z: 0}", "max: {x: 2, y: 63, z: 0}", "destination-world: wild", "enabled: true",
                "access:", "  conditions:", "    - type: HAS_INVENTORY_SPACE", "      slots: 2", ""));

        registry.reload();

        assertTrue(registry.find("arche").isEmpty(), "jamais chargé sans ses conditions");
        assertTrue(registry.skippedFiles().stream().anyMatch(i -> i.toString().contains("arche.yml")));
    }

    // ---- Messages et avertissement (issue #569) ----------------------------

    private static com.lodygames.rpgquest.travel.model.PortalMessages sampleMessages() {
        return new com.lodygames.rpgquest.travel.model.PortalMessages("<green>Bienvenue au marché.</green>", "",
                "", "<red>Le marché est fermé.</red>", "", "<red>Pas encore.</red>",
                new com.lodygames.rpgquest.travel.model.PortalMessages.Warning(
                        com.lodygames.rpgquest.travel.model.PortalMessages.Mode.ALWAYS, "<gold>Marché</gold>",
                        "<white>Foule dense.</white>", "", "", "", "", "", false));
    }

    @Test
    void messagesAreSavedOnADraftAndSurviveAReload() throws Exception {
        createDraft();
        Result r = service.setMessages("arche_marche", fp("arche_marche"), sampleMessages(), false, "claude", ctx());

        assertTrue(r.ok(), r.message());
        registry.reload();
        assertEquals(sampleMessages(), registry.find("arche_marche").orElseThrow().messages());
        String yaml = Files.readString(dir.resolve("arche_marche.yml"));
        assertTrue(yaml.contains("messages:") && yaml.contains("welcome:") && yaml.contains("mode: ALWAYS")
                && yaml.contains("allow-hide: false") && !yaml.contains("success:"), yaml);
        assertEquals(State.DRAFT, registry.find("arche_marche").orElseThrow().state(), "rien n'est activé");
        assertTrue(audit.recent(5).stream().anyMatch(e -> e.operation().equals("MESSAGES")
                && e.after().contains("bienvenue") && !e.after().contains("Foule")),
                "le journal nomme les champs, jamais leur contenu");
    }

    @Test
    void restoringTheDefaultsRestoresTheFileAndTheFingerprint() throws Exception {
        createDraft();
        String before = Files.readString(dir.resolve("arche_marche.yml"));
        String fingerprintBefore = fp("arche_marche");
        service.setMessages("arche_marche", fingerprintBefore, sampleMessages(), false, "claude", ctx());
        assertFalse(fp("arche_marche").equals(fingerprintBefore), "l'empreinte change avec les messages");

        Result r = service.setMessages("arche_marche", fp("arche_marche"),
                com.lodygames.rpgquest.travel.model.PortalMessages.NONE, false, "claude", ctx());

        assertTrue(r.ok(), r.message());
        assertEquals(before, Files.readString(dir.resolve("arche_marche.yml")));
        assertEquals(fingerprintBefore, fp("arche_marche"));
    }

    @Test
    void theMessagesOfAnActivePortalAreLockedAndHubToWildIsUntouched() throws Exception {
        String before = Files.readString(dir.resolve("hub_to_wild.yml"));
        String fingerprintBefore = fp("hub_to_wild");

        Result unconfirmed = service.setMessages("hub_to_wild", fp("hub_to_wild"), sampleMessages(), false, "claude",
                ctx());
        Result active = service.setMessages("hub_to_wild", fp("hub_to_wild"), sampleMessages(), true, "claude", ctx());

        assertEquals("ESSENTIAL_CONFIRMATION_REQUIRED", unconfirmed.code());
        assertEquals("ACTIVE_MESSAGES_LOCKED", active.code());
        assertEquals(before, Files.readString(dir.resolve("hub_to_wild.yml")), "hub_to_wild intact");
        assertTrue(registry.find("hub_to_wild").orElseThrow().messages().isEmpty(), "textes par défaut");
        assertEquals(fingerprintBefore, fp("hub_to_wild"));
    }

    @Test
    void anUnreadableMessagesSectionKeepsThePortalUnloaded() throws Exception {
        Files.writeString(dir.resolve("arche.yml"), String.join("\n", "id: arche", "world: world_hub",
                "min: {x: 0, y: 60, z: 0}", "max: {x: 2, y: 63, z: 0}", "destination-world: wild", "enabled: true",
                "warning:", "  mode: PARFOIS", ""));

        registry.reload();

        assertTrue(registry.find("arche").isEmpty(), "jamais chargé avec un avertissement illisible");
    }
}
