package com.lodygames.rpgquest.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Matrice de résolution des permissions de build par monde/Hub (issue #27) : chaque rôle courant
 * (builder-hub-0, builder Wild, admin monde) et la garantie qu'aucune permission de build
 * n'ouvre autre chose que sa propre zone.
 */
class BuildPermissionServiceTest {

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private BuildPermissionService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        service = TestBuildPermissions.standard(); // hub=world_hub, wild=wild, claims=claims
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerMock playerWith(String... permissions) {
        PlayerMock player = server.addPlayer();
        for (String permission : permissions) {
            player.addAttachment(plugin, permission, true);
        }
        return player;
    }

    @Test
    void aPlayerWithoutAnyPermissionCannotBuildAnywhere() {
        PlayerMock player = playerWith();

        assertFalse(service.mayBuild(player, "world_hub"));
        assertFalse(service.mayBuild(player, "wild"));
        assertFalse(service.mayBuild(player, "claims"));
        assertFalse(service.mayBuild(player, "some_other_world"));
    }

    @Test
    void builderHub0CanBuildOnlyInHub0() {
        PlayerMock player = playerWith(RpgQuestPermissions.buildHub("0"));

        assertTrue(service.mayBuild(player, "world_hub"), "le Hub 0 est autorisé");
        assertFalse(service.mayBuild(player, "wild"), "le Wild ne l'est pas");
        assertFalse(service.mayBuild(player, "claims"), "le monde des claims ne l'est pas");
    }

    @Test
    void builderWildCanBuildOnlyInTheWild() {
        PlayerMock player = playerWith(RpgQuestPermissions.BUILD_WILD);

        assertTrue(service.mayBuild(player, "wild"));
        assertFalse(service.mayBuild(player, "world_hub"));
        assertFalse(service.mayBuild(player, "claims"));
    }

    @Test
    void buildStarIsTheGlobalBuildUmbrella() {
        PlayerMock player = playerWith(RpgQuestPermissions.BUILD_ALL);

        assertTrue(service.mayBuild(player, "world_hub"));
        assertTrue(service.mayBuild(player, "wild"));
        assertTrue(service.mayBuild(player, "claims"));
        // rpgquest.build.* est le parapluie « construire partout » (juste sous rpgquest.admin.world) :
        // il autorise aussi un monde non répertorié — sans jamais accorder de bypass de claim.
        assertTrue(service.mayBuild(player, "some_other_world"));
    }

    @Test
    void adminWorldUmbrellaCanBuildEverywhereIncludingUnmanagedWorlds() {
        PlayerMock player = playerWith(RpgQuestPermissions.ADMIN_WORLD);

        assertTrue(service.mayBuild(player, "world_hub"));
        assertTrue(service.mayBuild(player, "wild"));
        assertTrue(service.mayBuild(player, "claims"));
        assertTrue(service.mayBuild(player, "some_other_world"));
    }

    @Test
    void buildHubStarGrantsAnyHubId() {
        BuildPermissionService withArena = TestBuildPermissions.withBuildAreas(Map.of("arena", "hub.arena"));
        PlayerMock player = playerWith(RpgQuestPermissions.BUILD_HUB_ALL);

        assertTrue(withArena.mayBuild(player, "arena"), "hub.* couvre l'id arena");
        assertTrue(withArena.mayBuild(player, "world_hub"), "hub.* couvre aussi le Hub 0 par défaut");
    }

    @Test
    void buildAreasMappingRoutesAWorldToItsHubNode() {
        BuildPermissionService withArena = TestBuildPermissions.withBuildAreas(Map.of("arena", "hub.arena"));
        PlayerMock onlyArena = playerWith(RpgQuestPermissions.buildHub("arena"));

        assertTrue(withArena.mayBuild(onlyArena, "arena"));
        assertFalse(withArena.mayBuild(onlyArena, "world_hub"), "hub.arena ne donne rien sur le Hub 0");
    }

    @Test
    void buildAreasMappingSupportsSpecializedWorldNode() {
        BuildPermissionService mapped = TestBuildPermissions.withBuildAreas(Map.of("build_world", "world.staging"));
        PlayerMock player = playerWith(RpgQuestPermissions.buildWorld("staging"));

        assertTrue(mapped.mayBuild(player, "build_world"));
        assertFalse(mapped.mayBuild(player, "wild"));
    }

    @Test
    void areaForFallsBackToConfiguredWorldNames() {
        assertEquals(BuildArea.Kind.HUB, service.areaFor("world_hub").kind());
        assertEquals("0", service.areaFor("world_hub").id());
        assertEquals(BuildArea.Kind.WILD, service.areaFor("wild").kind());
        assertEquals(BuildArea.Kind.WORLD, service.areaFor("claims").kind());
        assertEquals("claims", service.areaFor("claims").id());
        assertEquals(BuildArea.Kind.UNMANAGED, service.areaFor("random").kind());
    }

    @Test
    void nullPlayerOrNullWorldNeverBuilds() {
        assertFalse(service.mayBuild(null, "world_hub"));
        assertFalse(service.mayBuild(playerWith(RpgQuestPermissions.ADMIN_WORLD), (org.bukkit.World) null));
    }
}
