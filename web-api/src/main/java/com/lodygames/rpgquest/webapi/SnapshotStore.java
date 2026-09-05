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
 * <p>Le contenu est mis en cache en mémoire et relu uniquement si la date de
 * modification du fichier a changé, pour ne jamais faire de disque sur
 * chaque requête HTTP (mission point 4, transposée côté site).</p>
 */
public final class SnapshotStore {

    private final Path snapshotFile;
    private final long maxAgeSeconds;
    private final Logger logger;

    private volatile FileTime lastLoadedModifiedTime;
    private volatile Map<String, Object> cached;

    public SnapshotStore(Path snapshotFile, long maxAgeSeconds, Logger logger) {
        this.snapshotFile = snapshotFile;
        this.maxAgeSeconds = maxAgeSeconds;
        this.logger = logger;
    }

    /** Snapshot courant, ou vide si jamais généré ou illisible (corruption traitée comme absente, jamais fatale). */
    public synchronized Optional<Map<String, Object>> current() {
        try {
            if (!Files.exists(snapshotFile)) {
                cached = null;
                return Optional.empty();
            }
            FileTime modified = Files.getLastModifiedTime(snapshotFile);
            if (cached == null || !modified.equals(lastLoadedModifiedTime)) {
                reload(modified);
            }
            return Optional.ofNullable(cached);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Snapshot illisible : " + snapshotFile, e);
            cached = null;
            return Optional.empty();
        }
    }

    private void reload(FileTime modified) throws IOException {
        String content = Files.readString(snapshotFile, StandardCharsets.UTF_8);
        try {
            Object parsed = Json.parse(content);
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new JsonParseException("Le snapshot ne contient pas un objet JSON racine.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            cached = typed;
            lastLoadedModifiedTime = modified;
        } catch (JsonParseException e) {
            logger.log(Level.WARNING, "Snapshot corrompu, traité comme absent : " + snapshotFile, e);
            cached = null;
        }
    }

    /** {@code true} si {@code snapshot} n'a plus été régénéré depuis plus de {@code maxAgeSeconds}. */
    public boolean isStale(Map<String, Object> snapshot) {
        Object raw = snapshot.get("generatedAt");
        if (!(raw instanceof String generatedAtText)) {
            return true;
        }
        try {
            Instant generatedAt = Instant.parse(generatedAtText);
            return generatedAt.isBefore(Instant.now().minusSeconds(maxAgeSeconds));
        } catch (DateTimeParseException e) {
            return true;
        }
    }
}
