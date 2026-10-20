package com.lodygames.rpgquest.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.world.WorldOverview.Entry;
import com.lodygames.rpgquest.world.WorldOverview.ExternalSource;
import com.lodygames.rpgquest.world.WorldOverview.ExternalWorld;
import com.lodygames.rpgquest.world.WorldOverview.LoadedWorld;
import com.lodygames.rpgquest.world.WorldOverview.Manager;
import com.lodygames.rpgquest.world.WorldOverview.MobLink;
import com.lodygames.rpgquest.world.WorldOverview.Place;
import com.lodygames.rpgquest.world.WorldOverview.PortalLink;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Page Mondes en lecture seule (issue #560) : la configuration de DEV relevée le 18/10 — six mondes,
 * {@code wild} géré par RPGQuest, le reste par Multiverse ou le serveur.
 */
class WorldOverviewTest {

    private static LoadedWorld loaded(String name, String env) {
        return new LoadedWorld(name, env, "NORMAL", 42L, true, 0, "EASY", true, false, 0, 64, 0, null);
    }

    private static ExternalWorld mv(String name, boolean loaded) {
        return new ExternalWorld(name, loaded, "NORMAL", "", "", 42L, "EASY", true, true);
    }

    private static final List<LoadedWorld> DEV_LOADED = List.of(loaded("world", "NORMAL"),
            loaded("world_nether", "NETHER"), loaded("world_the_end", "THE_END"), loaded("world_hub", "NORMAL"),
            loaded("claims", "NORMAL"), loaded("wild", "NORMAL"));

    private static final List<PortalLink> DEV_PORTALS = List.of(
            new PortalLink("hub_to_wild", "world_hub", "wild", true, "RANDOM_SAFE"),
            new PortalLink("hub_to_claims", "world_hub", "claims", true, "CLAIM_HOME"));

    private static WorldOverview.Inputs inputs(List<LoadedWorld> loaded, ExternalSource mv, List<PortalLink> portals) {
        return new WorldOverview.Inputs(loaded, "world", mv, List.of("wild"),
                new WorldOverview.Roles("world_hub", "wild", "claims"), new WorldOverview.WaypointPolicy(true, true),
                portals, List.of(new Place("buildsite_0001", "Place du marché", "world_hub")),
                List.of(new Place("wp1", "Forêt", "wild"), new Place("wp2", "Plaine", "wild")),
                List.of(new MobLink("rpgquest:ogre", "Ogre", Set.of("wild")), new MobLink("rpgquest:any", "Tous", Set.of())),
                List.of(new Place("spot1", "Comptoir", "world_hub")),
                List.of(new Place("city1", "Julius", "wild")), 0);
    }

    private static ExternalSource devMultiverse() {
        return new ExternalSource(true, "5.7.3", "", List.of(mv("world", true), mv("world_nether", true),
                mv("world_the_end", true), mv("world_hub", true), mv("claims", true)));
    }

    private static Entry find(WorldOverview.View view, String name) {
        return view.worlds().stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void devSixWorldsWithManagersRolesAndEssentials() {
        var view = WorldOverview.build(inputs(DEV_LOADED, devMultiverse(), DEV_PORTALS));
        assertEquals(6, view.worlds().size());
        assertEquals("world", view.worlds().get(0).name(), "monde principal en tête");
        assertEquals(Manager.RPGQUEST, find(view, "wild").manager());
        assertEquals(Manager.MULTIVERSE, find(view, "world_hub").manager());
        assertEquals(Manager.MULTIVERSE, find(view, "claims").manager());
        assertEquals(Manager.SERVER, find(view, "world").manager());
        assertEquals(Manager.SERVER, find(view, "world_nether").manager());
        assertEquals(List.of("HUB"), find(view, "world_hub").roles());
        assertTrue(find(view, "world").essential());
        assertTrue(find(view, "wild").essential());
        assertFalse(find(view, "world_the_end").essential());
        assertTrue(find(view, "world_the_end").roles().isEmpty(), "aucun rôle par défaut (Q7)");
        assertTrue(view.issues().isEmpty());
        assertTrue(view.worlds().stream().allMatch(e -> e.issues().isEmpty()));
    }

    @Test
    void linkedElementsAreAttachedToTheirWorld() {
        var view = WorldOverview.build(inputs(DEV_LOADED, devMultiverse(), DEV_PORTALS));
        var hub = find(view, "world_hub").links();
        assertEquals(2, hub.portalsFrom().size());
        assertEquals(1, hub.sites().size());
        assertEquals(1, hub.npcSpots().size());
        assertTrue(hub.autoWaypoints(), "hub-enabled étend les waypoints au Hub");
        var wild = find(view, "wild").links();
        assertEquals("hub_to_wild", wild.portalsTo().get(0).id());
        assertEquals(2, wild.waypoints());
        assertEquals(List.of("rpgquest:ogre"), wild.mobs().stream().map(MobLink::id).toList());
        assertEquals(1, wild.cities().size());
        assertFalse(find(view, "claims").links().autoWaypoints(), "claims exclu des waypoints");
    }

    @Test
    void worksWithoutMultiverseFromBukkitAlone() {
        var view = WorldOverview.build(inputs(DEV_LOADED, ExternalSource.unavailable("Multiverse-Core n'est pas installé"),
                DEV_PORTALS));
        assertEquals(6, view.worlds().size());
        assertEquals("WORLD_MULTIVERSE_ABSENT", view.issues().get(0).code());
        assertEquals(Manager.SERVER, find(view, "world_hub").manager());
        assertFalse(find(view, "world_hub").multiverseKnown());
        assertNull(find(view, "world_hub").autoLoad());
    }

    @Test
    void unloadedRoleWorldAndActivePortalTowardsItAreReported() {
        List<LoadedWorld> withoutClaims = DEV_LOADED.stream().filter(w -> !w.name().equals("claims")).toList();
        var mvSource = new ExternalSource(true, "5.7.3", "", List.of(mv("world_hub", true), mv("claims", false)));
        var view = WorldOverview.build(inputs(withoutClaims, mvSource, DEV_PORTALS));
        Entry claims = find(view, "claims");
        assertFalse(claims.loaded());
        assertNull(claims.players());
        assertEquals("NORMAL", claims.environment(), "relu dans Multiverse même déchargé");
        var codes = claims.issues().stream().map(WorldOverview.Issue::code).toList();
        assertTrue(codes.contains("WORLD_ROLE_NOT_LOADED"), codes.toString());
        assertTrue(codes.contains("WORLD_PORTAL_TO_UNLOADED"), codes.toString());
        assertTrue(codes.contains("WORLD_AUTOLOAD_NOT_LOADED"), codes.toString());
    }

    @Test
    void unknownRoleWorldAppearsAsUnknownManager() {
        var in = new WorldOverview.Inputs(List.of(loaded("world", "NORMAL")), "world", ExternalSource.unavailable("x"),
                List.of(), new WorldOverview.Roles("", "wild_typo", ""), new WorldOverview.WaypointPolicy(true, false),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0);
        var view = WorldOverview.build(in);
        Entry typo = find(view, "wild_typo");
        assertEquals(Manager.UNKNOWN, typo.manager());
        assertNull(typo.seed());
        assertTrue(typo.essential());
    }

    @Test
    void worldDeclaredByBothManagersIsFlagged() {
        var mvSource = new ExternalSource(true, "5.7.3", "", List.of(mv("wild", true)));
        var view = WorldOverview.build(inputs(DEV_LOADED, mvSource, DEV_PORTALS));
        assertEquals(Manager.RPGQUEST, find(view, "wild").manager());
        assertEquals("WORLD_TWO_MANAGERS", find(view, "wild").issues().get(0).code());
    }

    // ---- Issue #81 : nom affiché ---------------------------------------------------------------

    @Test
    void aliasComesFromMultiverseWhenItKnowsTheWorldElseFromRpgQuestNames() {
        ExternalSource mvSource = new ExternalSource(true, "5.7.3", "", List.of(mv("world", true),
                new ExternalWorld("world_hub", true, "NORMAL", "", "", 42L, "EASY", true, true, "Village des Lody"),
                new ExternalWorld("claims", true, "NORMAL", "", "", 42L, "EASY", true, true, "")));
        var base = inputs(DEV_LOADED, mvSource, DEV_PORTALS);
        var in = new WorldOverview.Inputs(base.loaded(), base.mainWorld(), base.multiverse(), base.rpgquestManaged(),
                base.roles(), base.waypoints(), base.portals(), base.sites(), base.waypointPlaces(), base.mobs(),
                base.npcSpots(), base.cities(), base.onlinePlayers(),
                java.util.Map.of("wild", "Terres sauvages", "world_hub", "ignoré : Multiverse gère ce monde"));
        var view = WorldOverview.build(in);
        assertEquals("Village des Lody", find(view, "world_hub").alias());
        assertEquals("Terres sauvages", find(view, "wild").alias());
        assertEquals("", find(view, "claims").alias(), "aucun alias : PlugAdmin prend son défaut");
        assertEquals("wild", find(view, "wild").name(), "identifiant technique inchangé");
    }
}
