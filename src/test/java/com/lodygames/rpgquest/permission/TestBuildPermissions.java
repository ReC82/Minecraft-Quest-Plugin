package com.lodygames.rpgquest.permission;

import com.lodygames.rpgquest.config.ClaimConfig;
import com.lodygames.rpgquest.config.HubConfig;
import com.lodygames.rpgquest.config.PermissionsConfig;
import com.lodygames.rpgquest.config.TravelConfig;
import java.util.Map;

/**
 * Fabrique de {@link BuildPermissionService} pour les tests de listeners : une seule ligne au lieu
 * des quatre {@code Supplier} de configuration. Les valeurs par défaut correspondent aux mondes
 * utilisés dans les tests existants ({@code world_hub}, {@code wild}, {@code claims}).
 */
public final class TestBuildPermissions {

    private TestBuildPermissions() {
    }

    public static BuildPermissionService standard() {
        return withWorlds("world_hub", "wild", "claims");
    }

    public static BuildPermissionService withWorlds(String hubWorld, String wildWorld, String claimsWorld) {
        return new BuildPermissionService(
                PermissionsConfig::empty,
                () -> new HubConfig(hubWorld),
                () -> new TravelConfig(wildWorld, null, null),
                () -> new ClaimConfig(64, 384, 3, 16, claimsWorld, true));
    }

    public static BuildPermissionService withBuildAreas(Map<String, String> buildAreas) {
        PermissionsConfig config = new PermissionsConfig(buildAreas);
        return new BuildPermissionService(
                () -> config,
                () -> new HubConfig("world_hub"),
                () -> new TravelConfig("wild", null, null),
                () -> new ClaimConfig(64, 384, 3, 16, "claims", true));
    }
}
