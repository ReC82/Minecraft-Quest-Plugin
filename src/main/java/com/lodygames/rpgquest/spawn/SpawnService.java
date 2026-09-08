package com.lodygames.rpgquest.spawn;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
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
    /** Nom du monde Hub configuré ({@code hub.world}) — un des deux mondes où un nouveau joueur apparaît (issue #87). */
    private final Supplier<String> hubWorldName;

    private volatile SpawnPoint current;

    public SpawnService(RPGQuestPlugin plugin, Path spawnFile, Logger logger, Supplier<String> hubWorldName) {
        this.plugin = plugin;
        this.spawnFile = spawnFile;
        this.logger = logger;
        this.hubWorldName = hubWorldName;
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
     * Décide, à chaque connexion, s'il faut rediriger la position d'arrivée vers le spawn du
     * village configuré ({@link PlayerSpawnLocationEvent} — l'événement Spigot historique, marqué
     * {@code @Deprecated(forRemoval)} par Paper au profit de {@code
     * io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent} — mais ce dernier fournit une
     * {@code PlayerConfigurationConnection} plutôt qu'un {@code Player}, encore marqué {@code
     * @ApiStatus.Experimental}, et surtout non simulé par MockBukkit dans cette version : rester
     * sur l'événement Spigot garde le comportement testable en JUnit, même stratégie que {@code
     * dialogue.render.PaperDialogRenderer}). Jamais un téléport après coup : le joueur apparaît
     * directement au bon endroit.
     *
     * <p>La règle est portée par {@link JoinSpawnPolicy} (fonction pure, testée séparément) : on ne
     * redirige que si un nouveau joueur ({@code !hasPlayedBefore()}, heuristique peu fiable) est
     * placé par Paper dans le <strong>monde principal</strong> ou le <strong>monde Hub</strong> —
     * jamais quand Paper restaure déjà le joueur dans un autre monde chargé (Wild, claims…), ce
     * qui trahit une session antérieure réelle. Corrige l'issue #87 (reconnexion depuis le Wild
     * renvoyée au Hub à tort).</p>
     */
    @SuppressWarnings("removal")
    void applyJoinSpawnPolicy(PlayerSpawnLocationEvent event) {
        Optional<Location> target = resolve();
        Location incoming = event.getSpawnLocation();
        String incomingWorld = incoming != null && incoming.getWorld() != null ? incoming.getWorld().getName() : null;
        List<World> worlds = plugin.getServer().getWorlds();
        String primaryWorld = worlds.isEmpty() ? null : worlds.get(0).getName();
        String hubWorld = hubWorldName == null ? null : hubWorldName.get();

        JoinSpawnPolicy.Decision decision = JoinSpawnPolicy.decide(
                target.isPresent(), event.getPlayer().hasPlayedBefore(), incomingWorld, primaryWorld, hubWorld);

        if (decision == JoinSpawnPolicy.Decision.REDIRECT_TO_CONFIGURED_SPAWN && target.isPresent()) {
            logger.info("join_restore player={} world={} action=REDIRECT_TO_VILLAGE_SPAWN",
                    event.getPlayer().getUniqueId(), incomingWorld);
            event.setSpawnLocation(target.get());
            return;
        }
        // Non spammy : une seule ligne par connexion, jamais de coordonnées en INFO.
        logger.info("join_restore player={} world={} action=KEEP_LAST_LOCATION",
                event.getPlayer().getUniqueId(), incomingWorld);
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
