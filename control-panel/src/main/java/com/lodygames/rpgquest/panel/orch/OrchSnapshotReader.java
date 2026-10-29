package com.lodygames.rpgquest.panel.orch;

import com.lodygames.rpgquest.panel.json.Json;
import com.lodygames.rpgquest.panel.json.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

/**
 * Lit l'instantané publié par {@code lqorch} (issue #907), avec un cache sûr : le fichier n'est
 * relu et réanalysé que si sa date de modification ou sa taille a changé. Aucun appel réseau,
 * aucune écriture ; un fichier absent, illisible ou invalide donne un {@link Result} explicite,
 * jamais une exception ni une page vide.
 */
public final class OrchSnapshotReader {

    /** Garde-fou : l'instantané réel pèse quelques centaines de Ko. */
    static final long MAX_BYTES = 8L * 1024 * 1024;

    public enum State { LOADED, NOT_CONFIGURED, MISSING, UNREADABLE, INVALID }

    /** Résultat d'une lecture : l'instantané quand {@link State#LOADED}, sinon un motif lisible. */
    public record Result(State state, Optional<OrchSnapshot> snapshot, String problem) {
        static Result of(State state, String problem) {
            return new Result(state, Optional.empty(), problem);
        }
    }

    private final Path path;
    private Object cacheKey;
    private Result cached;

    /** @param path chemin de l'instantané ; {@code null} = vue non configurée */
    public OrchSnapshotReader(Path path) {
        this.path = path;
    }

    public Optional<Path> path() {
        return Optional.ofNullable(path);
    }

    public synchronized Result read() {
        if (path == null) {
            return Result.of(State.NOT_CONFIGURED, "Aucun chemin d'instantané configuré.");
        }
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            return remember(null, Result.of(State.MISSING, "Fichier absent : le superviseur n'a encore rien publié."));
        } catch (IOException | SecurityException e) {
            return remember(null, Result.of(State.UNREADABLE, "Fichier illisible (" + e.getClass().getSimpleName() + ")."));
        }
        Object key = attrs.lastModifiedTime().toMillis() + ":" + attrs.size();
        if (key.equals(cacheKey) && cached != null) {
            return cached;
        }
        if (attrs.size() > MAX_BYTES) {
            return remember(key, Result.of(State.INVALID, "Fichier trop volumineux (" + attrs.size() + " octets)."));
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            OrchSnapshot snapshot = OrchSnapshot.fromJson(Json.parseObject(text));
            return remember(key, new Result(State.LOADED, Optional.of(snapshot), ""));
        } catch (IOException | SecurityException e) {
            return remember(null, Result.of(State.UNREADABLE, "Fichier illisible (" + e.getClass().getSimpleName() + ")."));
        } catch (JsonParseException | IllegalArgumentException e) {
            return remember(key, Result.of(State.INVALID, "Contenu invalide : " + e.getMessage()));
        }
    }

    private Result remember(Object key, Result result) {
        cacheKey = key;
        cached = key == null ? null : result;
        return result;
    }
}
