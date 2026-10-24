package com.lodygames.rpgquest.webapi;

import com.lodygames.rpgquest.webapi.json.Json;
import com.lodygames.rpgquest.webapi.json.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Relit {@code snapshot.json} (produit par le plugin, jamais data.db —
 * mission étape 21, point 2) et détecte le mode dégradé : fichier absent
 * ("serveur jamais démarré côté web") ou périmé (le plugin a cessé de
 * l'actualiser, ex. serveur Minecraft arrêté).
 *
 * <p>#421 : relit aussi {@code server-status.json}, voisin de l'instantané, où le portail dépose le
 * dernier <em>relevé d'état agrégé</em> reçu (en ligne, nombre de joueurs — aucune donnée de
 * joueur). Ce relevé arrive même quand l'export nominatif {@code web-export} est désactivé ; sa
 * fraîcheur se juge sur {@code receivedAt}, l'horloge du portail, pour ne pas dépendre de celle du
 * serveur de jeu.</p>
 *
 * <p>Le contenu est mis en cache en mémoire et relu uniquement si la date de
 * modification du fichier a changé, pour ne jamais faire de disque sur
 * chaque requête HTTP (mission point 4, transposée côté site).</p>
 */
public final class SnapshotStore {

    /** Nom du fichier du relevé d'état, toujours à côté de {@code snapshot-file}. */
    public static final String HEARTBEAT_FILE_NAME = "server-status.json";

    private final long maxAgeSeconds;
    private final long dataMaxAgeSeconds;
    private final JsonFileCache snapshot;
    private final JsonFileCache heartbeat;

    public SnapshotStore(Path snapshotFile, long maxAgeSeconds, Logger logger) {
        this(snapshotFile, maxAgeSeconds, maxAgeSeconds, logger);
    }

    /**
     * @param dataMaxAgeSeconds âge au-delà duquel les données de jeu d'un instantané complet ne sont
     *        plus affichées (#421 : instantané toutes les 15 min, relevé d'état toutes les 30 s)
     */
    public SnapshotStore(Path snapshotFile, long maxAgeSeconds, long dataMaxAgeSeconds, Logger logger) {
        this.maxAgeSeconds = maxAgeSeconds;
        this.dataMaxAgeSeconds = Math.max(maxAgeSeconds, dataMaxAgeSeconds);
        this.snapshot = new JsonFileCache(snapshotFile, "Snapshot", logger);
        this.heartbeat = new JsonFileCache(heartbeatFile(snapshotFile), "Relevé d'état", logger);
    }

    /** Emplacement du relevé d'état pour un {@code snapshot-file} donné (écrit par l'ingestion). */
    public static Path heartbeatFile(Path snapshotFile) {
        return snapshotFile.toAbsolutePath().resolveSibling(HEARTBEAT_FILE_NAME);
    }

    /** Snapshot courant, ou vide si jamais généré ou illisible (corruption traitée comme absente, jamais fatale). */
    public Optional<Map<String, Object>> current() {
        return snapshot.current();
    }

    /** Dernier relevé d'état agrégé reçu (#421), ou vide. */
    public Optional<Map<String, Object>> currentHeartbeat() {
        return heartbeat.current();
    }

    /** {@code true} si {@code snapshot} n'a plus été régénéré depuis plus de {@code maxAgeSeconds}. */
    public boolean isStale(Map<String, Object> snapshot) {
        return isOlderThanMaxAge(snapshot.get("generatedAt"));
    }

    /** {@code true} si le portail n'a plus reçu de relevé d'état depuis plus de {@code maxAgeSeconds}. */
    public boolean isHeartbeatStale(Map<String, Object> heartbeat) {
        return isOlderThanMaxAge(heartbeat.get("receivedAt"));
    }

    /** {@code true} si les données de jeu de {@code snapshot} sont trop anciennes pour être montrées. */
    public boolean isDataStale(Map<String, Object> snapshot) {
        return isOlderThan(snapshot.get("generatedAt"), dataMaxAgeSeconds);
    }

    private boolean isOlderThanMaxAge(Object raw) {
        return isOlderThan(raw, maxAgeSeconds);
    }

    private static boolean isOlderThan(Object raw, long seconds) {
        Instant at = instant(raw);
        return at == null || at.isBefore(Instant.now().minusSeconds(seconds));
    }

    /** Date ISO-8601 lue dans un instantané ou un relevé, ou {@code null}. */
    public static Instant instant(Object raw) {
        if (!(raw instanceof String text)) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Un objet JSON racine relu seulement quand la date de modification du fichier change. */
    private static final class JsonFileCache {

        private final Path file;
        private final String label;
        private final Logger logger;
        private FileTime lastLoadedModifiedTime;
        private Map<String, Object> cached;

        JsonFileCache(Path file, String label, Logger logger) {
            this.file = file;
            this.label = label;
            this.logger = logger;
        }

        synchronized Optional<Map<String, Object>> current() {
            try {
                if (!Files.exists(file)) {
                    cached = null;
                    return Optional.empty();
                }
                FileTime modified = Files.getLastModifiedTime(file);
                if (cached == null || !modified.equals(lastLoadedModifiedTime)) {
                    reload(modified);
                }
                return Optional.ofNullable(cached);
            } catch (IOException e) {
                logger.log(Level.WARNING, label + " illisible : " + file, e);
                cached = null;
                return Optional.empty();
            }
        }

        private void reload(FileTime modified) throws IOException {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            try {
                Object parsed = Json.parse(content);
                if (!(parsed instanceof Map<?, ?> map)) {
                    throw new JsonParseException(label + " : pas d'objet JSON racine.");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                cached = typed;
                lastLoadedModifiedTime = modified;
            } catch (JsonParseException e) {
                logger.log(Level.WARNING, label + " corrompu, traité comme absent : " + file, e);
                cached = null;
            }
        }
    }
}
