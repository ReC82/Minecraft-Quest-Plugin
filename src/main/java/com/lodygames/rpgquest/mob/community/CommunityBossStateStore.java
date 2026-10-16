package com.lodygames.rpgquest.mob.community;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Issue #175 — persiste {@link CommunityBossState} dans un fichier de données YAML
 * ({@code <plugin>/state/community-bosses.yml}). Aucune table ni migration : le ticket l'exclut, et
 * l'état est petit (au plus {@link CommunityBossSettings#MAX_MAX_ACTIVE} instances).
 *
 * <p>Écriture atomique (fichier temporaire + déplacement). Les sauvegardes sont numérotées :
 * {@link #save(CommunityBossState, long)} ignore un instantané plus ancien que le dernier écrit, si
 * bien que deux écritures asynchrones qui se doublent ne peuvent jamais réinstaller un état périmé.
 * Un fichier illisible n'est jamais écrasé en silence : il est conservé à côté ({@code .broken}).</p>
 */
public final class CommunityBossStateStore {

    private final Path file;
    private final Logger logger;
    private long lastWrittenRevision = -1;

    public CommunityBossStateStore(Path stateDirectory, Logger logger) {
        this.file = stateDirectory.resolve("community-bosses.yml");
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    public synchronized CommunityBossState load() {
        if (!Files.exists(file)) {
            return CommunityBossState.empty();
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file.toFile());
            Long nextDue = yaml.isSet("next-due") ? yaml.getLong("next-due") : null;
            List<CommunityBossInstance> instances = new ArrayList<>();
            for (Map<?, ?> raw : yaml.getMapList("instances")) {
                YamlConfiguration entry = new YamlConfiguration();
                raw.forEach((k, v) -> entry.set(String.valueOf(k), v));
                CommunityBossInstance instance = parseInstance(entry);
                if (instance != null) {
                    instances.add(instance);
                }
            }
            Set<UUID> retired = new LinkedHashSet<>();
            for (String raw : yaml.getStringList("retired")) {
                parseUuid(raw).ifPresent(retired::add);
            }
            return new CommunityBossState(nextDue, yaml.getLong("last-spawn", 0L), yaml.getLong("last-attempt", 0L),
                    yaml.getString("last-outcome", ""), instances, retired);
        } catch (Exception e) {
            logger.error("État des boss communautaires illisible ({}) : conservé en .broken, état vide utilisé.",
                    e.getMessage());
            try {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException copyError) {
                logger.error("Copie de sauvegarde de l'état illisible impossible : {}", copyError.getMessage());
            }
            return CommunityBossState.empty();
        }
    }

    /** Écrit {@code state} sauf si une révision plus récente a déjà été écrite. */
    public synchronized void save(CommunityBossState state, long revision) throws IOException {
        if (revision <= lastWrittenRevision) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        if (state.nextDueMillis() != null) {
            yaml.set("next-due", state.nextDueMillis());
        }
        yaml.set("last-spawn", state.lastSpawnMillis());
        yaml.set("last-attempt", state.lastAttemptMillis());
        yaml.set("last-outcome", state.lastOutcome());
        List<Map<String, Object>> instances = new ArrayList<>();
        for (CommunityBossInstance i : state.instances()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("entity", i.entityId().toString());
            m.put("profile", i.profileId());
            m.put("profile-name", i.profileName());
            m.put("world", i.world());
            m.put("x", i.x());
            m.put("y", i.y());
            m.put("z", i.z());
            m.put("biome", i.biome());
            if (i.waypointId() != null) {
                m.put("waypoint-id", i.waypointId());
                m.put("waypoint-name", i.waypointName());
                m.put("waypoint-distance", i.waypointDistance());
                m.put("waypoint-direction", i.waypointDirection());
            }
            m.put("spawned-at", i.spawnedAtMillis());
            m.put("test", i.test());
            instances.add(m);
        }
        yaml.set("instances", instances);
        yaml.set("retired", state.retired().stream().map(UUID::toString).sorted().toList());

        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("." + file.getFileName() + ".tmp");
        Files.writeString(tmp, "# État des boss communautaires (issue #175) — géré par le plugin, ne pas éditer.\n"
                + yaml.saveToString());
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        lastWrittenRevision = revision;
    }

    private CommunityBossInstance parseInstance(ConfigurationSection s) {
        var entityId = parseUuid(s.getString("entity"));
        if (entityId.isEmpty()) {
            logger.warn("Verrou de boss communautaire ignoré : identifiant d'entité invalide.");
            return null;
        }
        try {
            boolean hasWaypoint = s.isSet("waypoint-id");
            return new CommunityBossInstance(entityId.get(), s.getString("profile"), s.getString("profile-name"),
                    s.getString("world"), s.getInt("x"), s.getInt("y"), s.getInt("z"), s.getString("biome", ""),
                    hasWaypoint ? s.getString("waypoint-id") : null,
                    hasWaypoint ? s.getString("waypoint-name") : null,
                    s.getInt("waypoint-distance", 0), s.getString("waypoint-direction"),
                    s.getLong("spawned-at", 0L), s.getBoolean("test", false));
        } catch (IllegalArgumentException e) {
            logger.warn("Verrou de boss communautaire ignoré : {}", e.getMessage());
            return null;
        }
    }

    private static java.util.Optional<UUID> parseUuid(String raw) {
        if (raw == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(UUID.fromString(raw.trim()));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }
}
