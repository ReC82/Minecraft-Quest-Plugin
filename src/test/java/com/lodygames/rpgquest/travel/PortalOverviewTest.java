package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.config.RandomSafeArrivalConfig;
import com.lodygames.rpgquest.travel.model.Destination;
import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.PortalDefinition;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Vue lecture seule des portails (issue #564) : données de DEV relues le 18/10, gardes et diagnostics. */
class PortalOverviewTest {

    private static final PortalOverview.Roles ROLES = new PortalOverview.Roles("world_hub", "wild", "claims");
    private static final Map<String, String> ALL_LOADED = Map.of("world_hub", "735, 66, -670", "wild", "0, 64, 0",
            "claims", "10, 70, 10");

    private static final WorldPortalDefinition HUB_TO_WILD = new WorldPortalDefinition("hub_to_wild", "world_hub",
            737, 66, -673, 740, 69, -673, "wild", true, DestinationStrategy.RANDOM_SAFE);
    private static final WorldPortalDefinition HUB_TO_CLAIMS = new WorldPortalDefinition("hub_to_claims", "world_hub",
            730, 66, -676, 732, 69, -676, "claims", true, DestinationStrategy.CLAIM_HOME);

    private static final PortalOverview.GuideNpc ELIAS = new PortalOverview.GuideNpc("guide", "Elias");

    private static PortalOverview.Inputs inputs(List<WorldPortalDefinition> portals, List<String> files,
                                                List<PortalLoadIssue> skipped, List<PortalDefinition> zones,
                                                List<Destination> destinations, Map<String, String> loaded) {
        return inputs(portals, files, skipped, zones, destinations, loaded, ELIAS);
    }

    private static PortalOverview.Inputs inputs(List<WorldPortalDefinition> portals, List<String> files,
                                                List<PortalLoadIssue> skipped, List<PortalDefinition> zones,
                                                List<Destination> destinations, Map<String, String> loaded,
                                                PortalOverview.GuideNpc guide) {
        return new PortalOverview.Inputs(portals, files, skipped, zones, List.of(), destinations, loaded, ROLES,
                new RandomSafeArrivalConfig(500, 5000, 20), new PortalOverview.ClaimArrival(200, 3000, 64, 40), guide);
    }

    private static PortalOverview.View dev() {
        return PortalOverview.build(inputs(List.of(HUB_TO_CLAIMS, HUB_TO_WILD),
                List.of("hub_to_claims.yml", "hub_to_wild.yml"), List.of(), List.of(), List.of(), ALL_LOADED));
    }

    private static String claimsEntryRule(PortalOverview.GuideNpc guide) {
        return PortalOverview.build(inputs(List.of(HUB_TO_CLAIMS), List.of("hub_to_claims.yml"), List.of(), List.of(),
                List.of(), ALL_LOADED, guide)).portals().get(0).guards().get(0).rules().get(0);
    }

    private static List<String> codes(PortalOverview.Entry e) {
        return e.issues().stream().map(PortalOverview.Issue::code).toList();
    }

    @Test
    void hubToWildShowsRealZoneRandomSafeParametersAndTheWildWarningTexts() {
        PortalOverview.Entry wild = dev().portals().get(1);
        assertEquals("hub_to_wild", wild.id());
        assertEquals("Portail vers le Wild", wild.name());
        assertEquals(PortalOverview.Kind.WORLD_PORTAL, wild.kind());
        assertEquals("world_hub", wild.world());
        assertEquals("HUB", wild.worldRole());
        assertEquals(737, wild.minX());
        assertEquals(-673, wild.maxZ());
        assertEquals("4 × 4 × 1", wild.size());
        assertEquals("RANDOM_SAFE", wild.strategy());
        assertTrue(wild.arrival().get(0).contains("entre 500 et 5000 blocs"), wild.arrival().toString());
        assertTrue(wild.arrival().get(1).contains("20 tentatives"));
        assertTrue(wild.arrival().get(1).contains("0, 64, 0"), "repli sur le point d'arrivée réel du Wild");
        assertEquals(1, wild.guards().size());
        PortalOverview.Guard guard = wild.guards().get(0);
        assertEquals("WILD_WARNING", guard.code());
        assertEquals(WildEntryWarningService.WARNING_TEXT, guard.texts().get(0).value(), "texte actuel, pas une copie");
        assertTrue(wild.issues().isEmpty(), wild.issues().toString());
    }

    @Test
    void hubToClaimsShowsClaimHomeArrivalAndTheClaimsGuardWithReturnStone() {
        PortalOverview.Entry claims = dev().portals().get(0);
        assertEquals("Portail vers les Claims", claims.name());
        assertEquals("CLAIM_HOME", claims.strategy());
        assertTrue(claims.arrival().get(1).contains("au moins 64 blocs de tout claim"), claims.arrival().toString());
        assertTrue(claims.arrival().get(1).contains("40 tentatives"));
        assertEquals(List.of("CLAIMS_ACCESS"), claims.guards().stream().map(PortalOverview.Guard::code).toList());
        assertTrue(claims.guards().get(0).rules().stream().anyMatch(r -> r.contains("Pierre de retour")));
        assertTrue(claims.guards().get(0).rules().stream().anyMatch(r -> r.contains("rpgquest.bypass.claimworld")));
        assertTrue(claims.issues().isEmpty(), claims.issues().toString());
    }

    @Test
    void claimsGuardNamesTheCurrentGuideNpcNeverTheOldGuideLabel() {
        String rule = claimsEntryRule(ELIAS);
        assertTrue(rule.contains("Elias (PNJ guide « guide »)"), rule);
        assertFalse(rule.contains("le Guide"), rule);
        assertTrue(claimsEntryRule(new PortalOverview.GuideNpc("guide", null))
                .contains("le guide du village (PNJ « guide », nom affiché introuvable)"));
        assertTrue(claimsEntryRule(new PortalOverview.GuideNpc("", null)).contains("aucun PNJ guide configuré"));
        assertTrue(claimsEntryRule(null).contains("aucun PNJ guide configuré"));
    }

    @Test
    void triggerSaysNoConditionOnThePortalButServerRulesStillApply() {
        PortalOverview.View view = dev();
        for (PortalOverview.Entry e : view.portals()) {
            String last = e.trigger().get(e.trigger().size() - 1);
            assertTrue(last.contains("configurée sur le portail lui-même"), last);
            assertTrue(last.contains("(bloc « Règles appliquées par le serveur ») s'appliquent quand même"), last);
        }
        var plain = new WorldPortalDefinition("hub_to_lobby", "world_hub", 0, 0, 0, 1, 1, 1, "lobby", true,
                DestinationStrategy.WORLD_SPAWN);
        PortalOverview.Entry e = PortalOverview.build(inputs(List.of(plain), List.of("hub_to_lobby.yml"), List.of(),
                List.of(), List.of(), Map.of("world_hub", "0, 0, 0", "lobby", "0, 0, 0"))).portals().get(0);
        assertTrue(e.trigger().get(2).contains("aucune règle du serveur ne s'applique"), e.trigger().get(2));
    }

    @Test
    void guardsFollowTheExactRuleOfTheGameGuards() {
        var toWildCase = new WorldPortalDefinition("odd", "world_hub", 0, 0, 0, 1, 1, 1, "Wild", true,
                DestinationStrategy.WORLD_SPAWN);
        var view = PortalOverview.build(inputs(List.of(toWildCase), List.of("odd.yml"), List.of(), List.of(),
                List.of(), Map.of("world_hub", "0, 0, 0", "Wild", "0, 0, 0")));
        assertTrue(view.portals().get(0).guards().isEmpty(),
                "le jeu compare les noms à l'identique : « Wild » ≠ « wild », aucun garde affiché");
    }

    @Test
    void unloadedDestinationIsAnErrorWhenEnabledAndUnloadedSourceAWarning() {
        var view = PortalOverview.build(inputs(List.of(HUB_TO_WILD), List.of("hub_to_wild.yml"), List.of(),
                List.of(), List.of(), Map.of("lobby", "0, 0, 0")));
        PortalOverview.Entry e = view.portals().get(0);
        assertEquals(List.of("PORTAL_SOURCE_NOT_LOADED", "PORTAL_DESTINATION_NOT_LOADED"), codes(e));
        assertEquals("error", e.issues().get(1).severity());
        assertTrue(e.arrival().get(1).contains("inconnu : monde non chargé"));
    }

    @Test
    void duplicateIdOverlapFileNameAndSkippedFilesAreReported() {
        var disabledTwin = new WorldPortalDefinition("hub_to_wild", "world_hub", 739, 66, -673, 745, 69, -673,
                "claims", false, DestinationStrategy.WORLD_SPAWN);
        var view = PortalOverview.build(inputs(List.of(HUB_TO_WILD, disabledTwin),
                List.of("hub_to_wild.yml", "zz_copie.yml"),
                List.of(new PortalLoadIssue("broken.yml", "« destination-world » manquant ou vide.")),
                List.of(), List.of(), ALL_LOADED));
        PortalOverview.Entry first = view.portals().get(0);
        PortalOverview.Entry twin = view.portals().get(1);
        assertTrue(codes(first).containsAll(List.of("PORTAL_DUPLICATE_ID", "PORTAL_OVERLAP")), codes(first).toString());
        assertTrue(first.issues().stream().filter(i -> i.code().equals("PORTAL_OVERLAP")).findFirst().orElseThrow()
                .message().contains("ne consulte que « hub_to_wild »"));
        assertTrue(codes(twin).containsAll(List.of("PORTAL_DISABLED", "PORTAL_FILE_NAME_MISMATCH",
                "PORTAL_DUPLICATE_ID", "PORTAL_OVERLAP")), codes(twin).toString());
        assertEquals("zz_copie.yml", twin.file());
        assertEquals(1, view.issues().size());
        assertEquals("PORTAL_FILE_SKIPPED", view.issues().get(0).code());
        assertTrue(view.issues().get(0).message().contains("world-portals/broken.yml"));
    }

    @Test
    void claimHomeOutsideTheClaimsWorldIsFlagged() {
        var odd = new WorldPortalDefinition("hub_to_wild_home", "world_hub", 0, 0, 0, 1, 1, 1, "wild", true,
                DestinationStrategy.CLAIM_HOME);
        var view = PortalOverview.build(inputs(List.of(odd), List.of("hub_to_wild_home.yml"), List.of(), List.of(),
                List.of(), ALL_LOADED));
        assertTrue(codes(view.portals().get(0)).contains("PORTAL_CLAIM_HOME_OUTSIDE_CLAIMS"));
    }

    @Test
    void zonePortalsAreListedAsLegacyWithConditionsAndDestination() {
        var zone = new PortalDefinition("vers_ville", "world_hub", 738, 66, -673, 738, 66, -673, "ville", 3, 30,
                "rpgquest.portal.ville", null, null, 10, 25L);
        var orphan = new PortalDefinition("orphelin", "world_hub", 0, 0, 0, 1, 1, 1, "absente", 3, 5,
                null, null, null, null, null);
        var view = PortalOverview.build(inputs(List.of(HUB_TO_WILD), List.of("hub_to_wild.yml"), List.of(),
                List.of(zone, orphan), List.of(new Destination("ville", "wild", 10.5, 70, -3.5, 90f, 0f)), ALL_LOADED));
        assertEquals(3, view.portals().size());
        PortalOverview.Entry z = view.portals().get(1);
        assertEquals(PortalOverview.Kind.ZONE_PORTAL, z.kind());
        assertEquals("wild", z.destinationWorld());
        assertEquals(Boolean.TRUE, z.destinationLoaded());
        assertTrue(z.destinationPoint().contains("10.5, 70.0, -3.5"), z.destinationPoint());
        assertEquals(List.of("Permission", "Niveau RPG minimum", "Coût", "Attente entre deux passages"),
                z.conditions().stream().map(PortalOverview.Condition::label).toList());
        assertTrue(z.guards().isEmpty(), "les gardes Wild/Claims ne concernent que les portails entre mondes");
        assertTrue(codes(z).contains("PORTAL_LEGACY_FORMAT"));
        assertTrue(codes(view.portals().get(0)).contains("PORTAL_OVERLAP_ENGINES"));
        PortalOverview.Entry o = view.portals().get(2);
        assertNull(o.destinationLoaded());
        assertTrue(codes(o).contains("PORTAL_ZONE_DESTINATION_UNKNOWN"));
        assertFalse(codes(o).contains("PORTAL_DESTINATION_NOT_LOADED"));
    }

    // ---- #565 : brouillons, nom stocké, emplacement, empreinte ------------------------------------

    @Test
    void theDevPortalsAreEssentialEditableAndCarryAFingerprint() {
        for (PortalOverview.Entry e : dev().portals()) {
            assertEquals("ACTIVE", e.admin().state());
            assertTrue(e.admin().essential(), e.id() + " mène au Wild ou aux Claims");
            assertTrue(e.admin().editable());
            assertEquals(24, e.admin().fingerprint().length());
        }
        assertEquals("Portail vers le Wild", dev().portals().get(1).name(), "nom toujours déduit sans nom stocké");
    }

    @Test
    void aDraftShowsItsStoredNameItsSiteAndADraftNotice() {
        WorldPortalDefinition draft = new WorldPortalDefinition("arche", "world_hub", 0, 64, 0, 2, 67, 0, "wild",
                false, DestinationStrategy.WORLD_SPAWN,
                new WorldPortalDefinition.Details("Arche du marché", "buildsite_0007", true, 3, 4, 1));
        var site = new com.lodygames.rpgquest.building.model.BuildingSite("buildsite_0007", "Place du marché", "", "",
                "world_hub", 1, 64, 0, com.lodygames.rpgquest.building.model.Facing.NORTH, null, "", java.time.Instant.EPOCH);
        var in = new PortalOverview.Inputs(List.of(draft), List.of("arche.yml"), List.of(), List.of(), List.of(),
                List.of(), ALL_LOADED, ROLES, new RandomSafeArrivalConfig(500, 5000, 20),
                new PortalOverview.ClaimArrival(200, 3000, 64, 40), ELIAS,
                id -> id.equals("buildsite_0007") ? java.util.Optional.of(site) : java.util.Optional.empty(), List.of());

        PortalOverview.Entry e = PortalOverview.build(in).portals().get(0);

        assertEquals("Arche du marché", e.name());
        assertEquals("DRAFT", e.admin().state());
        assertEquals("Place du marché", e.admin().siteName());
        assertTrue(codes(e).contains("PORTAL_DRAFT"));
        assertFalse(codes(e).contains("PORTAL_DISABLED"));
    }

    @Test
    void aPortalWhoseSiteDisappearedIsFlagged() {
        WorldPortalDefinition draft = new WorldPortalDefinition("arche", "world_hub", 0, 64, 0, 2, 67, 0, "wild",
                false, DestinationStrategy.WORLD_SPAWN,
                new WorldPortalDefinition.Details("", "buildsite_0042", false, 3, 4, 1));

        PortalOverview.Entry e = PortalOverview.build(inputs(List.of(draft), List.of("arche.yml"), List.of(),
                List.of(), List.of(), ALL_LOADED)).portals().get(0);

        assertTrue(codes(e).contains("PORTAL_SITE_MISSING"));
        assertTrue(codes(e).contains("PORTAL_DISABLED"));
    }
}
