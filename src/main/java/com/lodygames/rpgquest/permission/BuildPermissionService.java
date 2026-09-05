package com.lodygames.rpgquest.permission;

import com.lodygames.rpgquest.config.ClaimConfig;
import com.lodygames.rpgquest.config.HubConfig;
import com.lodygames.rpgquest.config.PermissionsConfig;
import com.lodygames.rpgquest.config.TravelConfig;
import java.util.Locale;
import java.util.function.Supplier;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Décide si un joueur a le droit de <strong>construire</strong> (casser / poser / interagir de
 * façon protégée) dans un monde donné, sans jamais accorder le moindre contournement de claim
 * joueur (issue #27).
 *
 * <p>Ordre d'évaluation :</p>
 * <ol>
 *   <li>{@code rpgquest.admin.world} (parapluie admin) ou {@code rpgquest.build.*} → autorisé
 *       partout ;</li>
 *   <li>sinon, selon la {@link BuildArea} du monde :
 *     <ul>
 *       <li>{@link BuildArea.Kind#HUB} → {@code rpgquest.build.hub.*} ou
 *           {@code rpgquest.build.hub.<id>} ;</li>
 *       <li>{@link BuildArea.Kind#WILD} → {@code rpgquest.build.wild} ;</li>
 *       <li>{@link BuildArea.Kind#WORLD} → {@code rpgquest.build.world.<id>} ;</li>
 *       <li>{@link BuildArea.Kind#UNMANAGED} → aucune permission de build RPGQuest ne concerne ce
 *           monde (les protections propres à ce monde, s'il y en a, décident seules).</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>Toutes les dépendances sont des {@link Supplier} de configuration : le service reflète
 * immédiatement un {@code /rpgquest reload} sans être recréé, et reste trivial à instancier en
 * test.</p>
 */
public final class BuildPermissionService {

    private final Supplier<PermissionsConfig> permissionsConfig;
    private final Supplier<HubConfig> hubConfig;
    private final Supplier<TravelConfig> travelConfig;
    private final Supplier<ClaimConfig> claimConfig;

    public BuildPermissionService(Supplier<PermissionsConfig> permissionsConfig,
                                  Supplier<HubConfig> hubConfig,
                                  Supplier<TravelConfig> travelConfig,
                                  Supplier<ClaimConfig> claimConfig) {
        this.permissionsConfig = permissionsConfig;
        this.hubConfig = hubConfig;
        this.travelConfig = travelConfig;
        this.claimConfig = claimConfig;
    }

    /** {@code true} si {@code player} peut construire dans {@code world}. {@code null} monde → {@code false}. */
    public boolean mayBuild(Player player, World world) {
        return world != null && mayBuild(player, world.getName());
    }

    public boolean mayBuild(Player player, String worldName) {
        if (player == null) {
            return false;
        }
        if (player.hasPermission(RpgQuestPermissions.ADMIN_WORLD)
                || player.hasPermission(RpgQuestPermissions.BUILD_ALL)) {
            return true;
        }
        BuildArea area = areaFor(worldName);
        return switch (area.kind()) {
            case HUB -> player.hasPermission(RpgQuestPermissions.BUILD_HUB_ALL)
                    || player.hasPermission(RpgQuestPermissions.buildHub(area.id()));
            case WILD -> player.hasPermission(RpgQuestPermissions.BUILD_WILD);
            case WORLD -> player.hasPermission(RpgQuestPermissions.buildWorld(area.id()));
            case UNMANAGED -> false;
        };
    }

    /**
     * Catégorie de build d'un monde : entrée explicite de {@code permissions.build-areas} en
     * priorité, puis repli sur les noms de mondes déjà connus de {@code config.yml}.
     */
    public BuildArea areaFor(String worldName) {
        if (worldName == null) {
            return BuildArea.UNMANAGED;
        }
        String spec = permissionsConfig.get().buildAreas().get(worldName);
        if (spec != null) {
            return parseSpec(spec);
        }
        if (worldName.equals(hubConfig.get().world())) {
            return BuildArea.hub("0");
        }
        if (worldName.equals(travelConfig.get().wildWorld())) {
            return BuildArea.wild();
        }
        if (worldName.equals(claimConfig.get().world())) {
            return BuildArea.world("claims");
        }
        return BuildArea.UNMANAGED;
    }

    private static BuildArea parseSpec(String rawSpec) {
        String spec = rawSpec.trim().toLowerCase(Locale.ROOT);
        if (spec.equals("wild")) {
            return BuildArea.wild();
        }
        if (spec.startsWith("hub.") && spec.length() > 4) {
            return BuildArea.hub(spec.substring(4));
        }
        if (spec.startsWith("world.") && spec.length() > 6) {
            return BuildArea.world(spec.substring(6));
        }
        return BuildArea.world(spec);
    }
}
