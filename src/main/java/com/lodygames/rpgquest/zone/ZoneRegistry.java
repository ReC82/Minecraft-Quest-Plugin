package com.lodygames.rpgquest.zone;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.zone.model.ZoneDefinition;
import com.lodygames.rpgquest.zone.model.ZoneFlags;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Charge/persiste les zones protégées depuis {@code plugins/RPGQuest/zones/}.
 * Même conception que {@code YamlCustomItemRegistry}/{@code
 * ResourceNodeRegistry} pour le chargement ; contrairement à ces registres
 * (types définis à la main), les zones sont aussi créées/supprimées à
 * l'exécution par {@code /rpgadmin zone create|delete} — chaque opération
 * écrit ou retire directement un fichier YAML puis recharge, donc le
 * fichier reste la seule source de vérité, jamais un état en mémoire
 * divergent.
 *
 * <p>{@link #zonesInWorld(String)} est indexé (une passe à chaque {@link
 * #reload()}, pas par événement) pour que la vérification de zone à chaque
 * événement Bukkit protégé reste bon marché : jamais de balayage des zones
 * d'autres mondes, jamais de recalcul par appel.</p>
 */
public final class ZoneRegistry implements PluginService {

    private static final String[] BUNDLED_EXAMPLES = {"central_village.yml"};

    private final Path zonesDirectory;
    private final Logger logger;
    private final ZoneLoader loader = new ZoneLoader();

    private volatile List<ZoneDefinition> zones = List.of();
    private volatile Map<String, List<ZoneDefinition>> byWorld = Map.of();
    private volatile ZoneLoadReport lastReport = new ZoneLoadReport(List.of(), List.of());

    public ZoneRegistry(Path zonesDirectory, Logger logger) {
        this.zonesDirectory = zonesDirectory;
        this.logger = logger;
    }

    @Override
    public void start() {
        ensureExamplesExist();
        reload();
    }

    @Override
    public void stop() {
        // Rien à libérer : les définitions vivent en mémoire, pas de ressource externe.
    }

    public ZoneLoadReport reload() {
        ZoneLoadReport report = loader.loadDirectory(zonesDirectory);
        this.zones = report.loaded();
        Map<String, List<ZoneDefinition>> index = new HashMap<>();
        for (ZoneDefinition zone : report.loaded()) {
            index.computeIfAbsent(zone.world(), w -> new java.util.ArrayList<>()).add(zone);
        }
        this.byWorld = Map.copyOf(index);
        this.lastReport = report;
        logReport("Chargement", report);
        return report;
    }

    public List<ZoneDefinition> zones() {
        return zones;
    }

    public List<ZoneDefinition> zonesInWorld(String world) {
        return byWorld.getOrDefault(world, List.of());
    }

    public Optional<ZoneDefinition> find(String id) {
        return zones.stream().filter(z -> z.id().equals(id)).findFirst();
    }

    /** La première zone (dans l'ordre chargé) contenant cette position, s'il y en a une. */
    public Optional<ZoneDefinition> zoneAt(String world, int x, int y, int z) {
        for (ZoneDefinition zone : zonesInWorld(world)) {
            if (zone.contains(world, x, y, z)) {
                return Optional.of(zone);
            }
        }
        return Optional.empty();
    }

    public ZoneLoadReport lastReport() {
        return lastReport;
    }

    public enum CreateOutcome {
        CREATED, DUPLICATE_ID, OVERLAPS, IO_ERROR
    }

    public CreateOutcome create(ZoneDefinition zone) {
        if (find(zone.id()).isPresent()) {
            return CreateOutcome.DUPLICATE_ID;
        }
        for (ZoneDefinition existing : zonesInWorld(zone.world())) {
            if (zone.overlaps(existing)) {
                return CreateOutcome.OVERLAPS;
            }
        }

        Path target = zonesDirectory.resolve(zone.id() + ".yml");
        try {
            Files.createDirectories(zonesDirectory);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("id", zone.id());
            yaml.set("world", zone.world());
            yaml.set("min.x", zone.minX());
            yaml.set("min.y", zone.minY());
            yaml.set("min.z", zone.minZ());
            yaml.set("max.x", zone.maxX());
            yaml.set("max.y", zone.maxY());
            yaml.set("max.z", zone.maxZ());
            ZoneFlags flags = zone.flags();
            yaml.set("flags.pvp", flags.allowPvp());
            yaml.set("flags.block-break", flags.allowBlockBreak());
            yaml.set("flags.block-place", flags.allowBlockPlace());
            yaml.set("flags.explosions", flags.allowExplosions());
            yaml.set("flags.fire", flags.allowFire());
            yaml.set("flags.lava", flags.allowLava());
            yaml.set("flags.pistons-across-border", flags.allowPistonsAcrossBorder());
            yaml.set("flags.hostile-spawn", flags.allowHostileSpawn());
            yaml.set("flags.hostile-damage", flags.allowHostileDamage());
            yaml.set("flags.environmental-damage", flags.allowEnvironmentalDamage());
            yaml.set("flags.npc-damage", flags.allowNpcDamage());
            yaml.set("flags.doors", flags.allowDoors());
            yaml.set("flags.buttons", flags.allowButtons());
            yaml.set("flags.levers", flags.allowLevers());
            yaml.set("flags.npc-interact", flags.allowNpcInteract());
            yaml.set("flags.public-containers", flags.allowPublicContainers());
            yaml.set("flags.force-day", flags.forceDay());
            yaml.save(target.toFile());
        } catch (IOException e) {
            logger.error("Impossible d'écrire la zone {} dans {}.", zone.id(), target, e);
            return CreateOutcome.IO_ERROR;
        }

        reload();
        return CreateOutcome.CREATED;
    }

    public boolean delete(String id) {
        Optional<ZoneDefinition> existing = find(id);
        if (existing.isEmpty()) {
            return false;
        }
        Path target = zonesDirectory.resolve(id + ".yml");
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            logger.error("Impossible de supprimer le fichier de zone {}.", target, e);
            return false;
        }
        reload();
        return true;
    }

    private void logReport(String action, ZoneLoadReport report) {
        logger.info("{} des zones protégées : {} chargée(s), {} erreur(s).",
                action, report.loaded().size(), report.issues().size());
        for (ZoneLoadIssue issue : report.issues()) {
            logger.warn("[{}] {}", issue.file(), issue.message());
        }
    }

    private void ensureExamplesExist() {
        try {
            Files.createDirectories(zonesDirectory);
        } catch (IOException e) {
            logger.error("Impossible de créer le dossier de zones {}.", zonesDirectory, e);
            return;
        }

        for (String example : BUNDLED_EXAMPLES) {
            Path target = zonesDirectory.resolve(example);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in = ZoneRegistry.class.getResourceAsStream("/zones/" + example)) {
                if (in == null) {
                    logger.warn("Zone d'exemple introuvable dans le jar : {}", example);
                    continue;
                }
                Files.copy(in, target);
            } catch (IOException e) {
                logger.error("Impossible de générer la zone d'exemple {}.", example, e);
            }
        }
    }
}
