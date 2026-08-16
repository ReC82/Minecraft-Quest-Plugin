package com.lodygames.rpgquest.spawn;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;
import org.slf4j.Logger;

/**
 * Spawn unique et persistant du village central : un seul fichier
 * {@code plugins/RPGQuest/spawn.yml}, jamais de coordonnées codées en dur. Contrairement à
 * {@code travel.YamlDestinationRegistry} (plusieurs positions nommées, un fichier par id), il
 * n'existe ici qu'une seule position globale — pas de registre, un simple fichier plat à la
 * racine du dossier de données du plugin.
 *
 * <p>Tant qu'aucun administrateur n'a exécuté {@code /rpgadmin spawn set}, le fichier n'existe
 * pas, {@link #current()} reste vide, et le comportement vanilla s'applique intégralement
 * (spawn du monde pour un nouveau joueur, lit/ancre pour la réapparition) — voir
 * {@link SpawnPlayerListener}, qui ne fait jamais rien tant que {@link #resolve()} est vide.</p>
 */
public final class SpawnService implements PluginService {

    private final RPGQuestPlugin plugin;
    private final Path spawnFile;
    private final Logger logger;

    private volatile SpawnPoint current;

    public SpawnService(RPGQuestPlugin plugin, Path spawnFile, Logger logger) {
        this.plugin = plugin;
        this.spawnFile = spawnFile;
        this.logger = logger;
    }

    @Override
    public void start() {
        reload();
    }

    @Override
    public void stop() {
        // Rien à libérer : la position vit en mémoire, pas de ressource externe.
    }

    /** Recharge depuis le disque — vide (pas d'erreur) si le fichier n'existe pas encore. */
    public void reload() {
        if (!Files.exists(spawnFile)) {
            current = null;
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(spawnFile.toFile());
        String world = yaml.getString("world");
        if (world == null || world.isBlank()) {
            logger.warn("{} présent mais invalide (« world » manquant) : ignoré.", spawnFile);
            current = null;
            return;
        }
        current = new SpawnPoint(world, yaml.getDouble("x"), yaml.getDouble("y"), yaml.getDouble("z"),
                (float) yaml.getDouble("yaw"), (float) yaml.getDouble("pitch"));
    }

    public Optional<SpawnPoint> current() {
        return Optional.ofNullable(current);
    }

    /** Position vivante (monde résolu) du spawn actuel — vide si non défini, ou si son monde n'existe plus/pas encore chargé. */
    public Optional<Location> resolve() {
        SpawnPoint point = current;
        if (point == null) {
            return Optional.empty();
        }
        World world = plugin.getServer().getWorld(point.world());
        if (world == null) {
            return Optional.empty();
        }
        return Optional.of(new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch()));
    }

    /**
     * Capture {@code location} comme nouveau spawn (remplace l'ancien s'il existait) et persiste
     * immédiatement. {@code false} uniquement en cas d'échec d'écriture disque (voir la console).
     */
    public boolean set(Location location) {
        SpawnPoint point;
        try {
            point = SpawnPoint.of(location);
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            Files.createDirectories(spawnFile.getParent());
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("world", point.world());
            yaml.set("x", point.x());
            yaml.set("y", point.y());
            yaml.set("z", point.z());
            yaml.set("yaw", (double) point.yaw());
            yaml.set("pitch", (double) point.pitch());
            yaml.save(spawnFile.toFile());
        } catch (IOException e) {
            logger.error("Impossible d'écrire le spawn dans {}.", spawnFile, e);
            return false;
        }
        current = point;
        return true;
    }

    /** Écouteur Bukkit (connexion/réapparition) à enregistrer via {@code PlayerListenerService}. */
    public Listener listener() {
        return new SpawnPlayerListener(this);
    }

    /**
     * Nouveau joueur (jamais connecté auparavant) : sa position d'arrivée est redirigée vers le
     * spawn configuré via {@link PlayerSpawnLocationEvent} (l'événement Spigot historique, marqué
     * {@code @Deprecated(forRemoval)} par Paper au profit de {@code
     * io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent} — mais ce dernier fournit une
     * {@code PlayerConfigurationConnection} plutôt qu'un {@code Player}, encore marqué {@code
     * @ApiStatus.Experimental}, et surtout non simulé par MockBukkit dans cette version : rester
     * sur l'événement Spigot garde le comportement testable en JUnit, même stratégie que {@code
     * dialogue.render.PaperDialogRenderer} pour une API instable choisie en connaissance de
     * cause). Jamais un téléport après coup : le joueur apparaît directement au bon endroit.
     */
    @SuppressWarnings("removal")
    void handleFirstJoin(PlayerSpawnLocationEvent event) {
        resolve().ifPresent(target -> {
            // TODO(debug bug TP hub) : trace temporaire, à retirer une fois la cause confirmée.
            Location defaultSpawn = event.getSpawnLocation();
            logger.info("[TP-TRACE] player={} uuid={} source=SpawnService portal=none "
                            + "from={}:{},{},{} to={}:{},{},{} reason=first_join_spawn at={}",
                    event.getPlayer().getName(), event.getPlayer().getUniqueId(),
                    defaultSpawn.getWorld() != null ? defaultSpawn.getWorld().getName() : "?",
                    defaultSpawn.getBlockX(), defaultSpawn.getBlockY(), defaultSpawn.getBlockZ(),
                    target.getWorld() != null ? target.getWorld().getName() : "?",
                    target.getBlockX(), target.getBlockY(), target.getBlockZ(),
                    System.currentTimeMillis());
            event.setSpawnLocation(target);
        });
    }

    /** Réapparition après la mort : toujours redirigée vers le spawn configuré, lit/ancre inclus. */
    void handleRespawn(PlayerRespawnEvent event) {
        resolve().ifPresent(target -> {
            // TODO(debug bug TP hub) : trace temporaire, à retirer une fois la cause confirmée.
            Location defaultRespawn = event.getRespawnLocation();
            logger.info("[TP-TRACE] player={} uuid={} source=SpawnService portal=none "
                            + "from={}:{},{},{} to={}:{},{},{} reason=respawn_redirect at={}",
                    event.getPlayer().getName(), event.getPlayer().getUniqueId(),
                    defaultRespawn.getWorld() != null ? defaultRespawn.getWorld().getName() : "?",
                    defaultRespawn.getBlockX(), defaultRespawn.getBlockY(), defaultRespawn.getBlockZ(),
                    target.getWorld() != null ? target.getWorld().getName() : "?",
                    target.getBlockX(), target.getBlockY(), target.getBlockZ(),
                    System.currentTimeMillis());
            event.setRespawnLocation(target);
        });
    }
}
