package com.lodygames.rpgquest.mob.quest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Issue #171 — persiste les boss de quête actifs dans {@code <plugin>/state/quest-bosses.yml}, sur le
 * modèle de {@code mob.community.CommunityBossStateStore} : aucune table ni migration, écriture
 * atomique, révisions ordonnées (un instantané plus ancien n'écrase jamais un plus récent), fichier
 * illisible conservé en {@code .broken} plutôt qu'écrasé en silence.
 */
public final class QuestBossStateStore {

    /** Contenu persisté : instances actives + entités à supprimer dès que leur chunk se recharge. */
    public record State(List<QuestBossInstance> instances, Set<UUID> retired) {
        public State {
            instances = List.copyOf(instances);
            retired = Set.copyOf(retired);
        }

        public static State empty() {
            return new State(List.of(), Set.of());
        }
    }

    private final Path file;
    private final Logger logger;
    private long lastWrittenRevision = -1;

    public QuestBossStateStore(Path stateDirectory, Logger logger) {
        this.file = stateDirectory.resolve("quest-bosses.yml");
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    public synchronized State load() {
        if (!Files.exists(file)) {
            return State.empty();
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file.toFile());
            List<QuestBossInstance> instances = new ArrayList<>();
            for (Map<?, ?> raw : yaml.getMapList("instances")) {
                YamlConfiguration entry = new YamlConfiguration();
                raw.forEach((k, v) -> entry.set(String.valueOf(k), v));
                QuestBossInstance instance = parseInstance(entry);
                if (instance != null) {
                    instances.add(instance);
                }
            }
            Set<UUID> retired = new LinkedHashSet<>();
            for (String raw : yaml.getStringList("retired")) {
                parseUuid(raw).ifPresent(retired::add);
            }
            return new State(instances, retired);
        } catch (Exception e) {
            logger.error("État des boss de quête illisible ({}) : conservé en .broken, état vide utilisé.",
                    e.getMessage());
            try {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException copyError) {
                logger.error("Copie de sauvegarde de l'état illisible impossible : {}", copyError.getMessage());
            }
            return State.empty();
        }
    }

    /** Écrit {@code state} sauf si une révision plus récente a déjà été écrite. */
    public synchronized void save(State state, long revision) throws IOException {
        if (revision <= lastWrittenRevision) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> instances = new ArrayList<>();
        for (QuestBossInstance i : state.instances()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entity", i.entityId().toString());
            m.put("owner", i.owner().toString());
            m.put("quest", i.questId());
            m.put("step", i.stepId());
            m.put("objective", i.objectiveIndex());
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
            instances.add(m);
        }
        yaml.set("instances", instances);
        yaml.set("retired", state.retired().stream().map(UUID::toString).sorted().toList());

        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("." + file.getFileName() + ".tmp");
        Files.writeString(tmp, "# Boss de quête actifs (issue #171) — géré par le plugin, ne pas éditer.\n"
                + yaml.saveToString());
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        lastWrittenRevision = revision;
    }

    private QuestBossInstance parseInstance(ConfigurationSection s) {
        Optional<UUID> entityId = parseUuid(s.getString("entity"));
        Optional<UUID> owner = parseUuid(s.getString("owner"));
        if (entityId.isEmpty() || owner.isEmpty()) {
            logger.warn("Boss de quête ignoré à la lecture : identifiant d'entité ou de joueur invalide.");
            return null;
        }
        try {
            boolean hasWaypoint = s.isSet("waypoint-id");
            return new QuestBossInstance(entityId.get(), owner.get(), s.getString("quest"), s.getString("step"),
                    s.getInt("objective", -1), s.getString("profile"), s.getString("profile-name"),
                    s.getString("world"), s.getInt("x"), s.getInt("y"), s.getInt("z"), s.getString("biome", ""),
                    hasWaypoint ? s.getString("waypoint-id") : null,
                    hasWaypoint ? s.getString("waypoint-name") : null,
                    s.getInt("waypoint-distance", 0), s.getString("waypoint-direction"),
                    s.getLong("spawned-at", 0L));
        } catch (IllegalArgumentException e) {
            logger.warn("Boss de quête ignoré à la lecture : {}", e.getMessage());
            return null;
        }
    }

    private static Optional<UUID> parseUuid(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
