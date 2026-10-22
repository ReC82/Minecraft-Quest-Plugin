package com.lodygames.rpgquest.webapi.ingest;

import com.lodygames.rpgquest.webapi.SnapshotStore;
import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.AuthFilter;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.lodygames.rpgquest.webapi.http.MalformedRequestException;
import com.lodygames.rpgquest.webapi.json.Json;
import com.lodygames.rpgquest.webapi.json.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * {@code POST /api/snapshot} (#421) — reçoit l'instantané que le plugin écrit chez VeryGames et le
 * dépose, à l'identique, dans le {@code snapshot-file} que lit le portail.
 *
 * <p>Authentifiée par le jeton dédié {@link SnapshotIngestSettings#token()}. Avant toute écriture :
 * taille bornée (413), objet JSON avec {@code schemaVersion} pris en charge, {@code generatedAt}
 * daté et pas dans le futur, {@code server.online} booléen, et aucune clé {@code uuid} à aucun
 * niveau (règles #55 : l'export ne publie jamais d'UUID). Un corps refusé laisse le fichier
 * précédent intact ; un corps accepté remplace le fichier de façon atomique (temporaire + rename).
 * Le portail n'écrit rien d'autre et n'accède à aucune base du serveur de jeu.</p>
 *
 * <p>Un corps {@code "kind": "status"} est un <em>relevé d'état agrégé</em> (en ligne, nombre de
 * joueurs), envoyé par le plugin même quand l'export nominatif est désactivé : il est validé en
 * périmètre fermé et stocké à part, de façon idempotente, par {@link StatusReportIngest}. La réponse
 * indique {@code STORED}, {@code DUPLICATE} ou {@code OUT_OF_ORDER} (toujours 200).</p>
 */
public final class SnapshotIngestHandler implements ApiHandler {

    public static final String PATH = "/api/snapshot";
    /** Versions de {@code snapshot.json} que le portail sait afficher. */
    static final int MIN_SCHEMA_VERSION = 1;
    static final int MAX_SCHEMA_VERSION = 2;
    /** Tolérance de décalage d'horloge entre VeryGames et AWS. */
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final int MAX_DEPTH = 32;

    private final Path snapshotFile;
    private final int maxBytes;
    private final AuthFilter auth;
    private final Clock clock;
    private final StatusReportIngest statusReports;

    public SnapshotIngestHandler(Path snapshotFile, SnapshotIngestSettings settings, Clock clock) {
        this.snapshotFile = snapshotFile;
        this.maxBytes = settings.maxBytes();
        this.auth = new AuthFilter(settings.token());
        this.clock = clock;
        this.statusReports = new StatusReportIngest(SnapshotStore.heartbeatFile(snapshotFile), clock,
                Logger.getLogger("rpgquest-webapi"));
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        if (!PATH.equals(exchange.getRequestURI().getPath())) {
            HttpResponses.sendJson(exchange, 404, Map.of("error", "not_found", "message", "Route inconnue."));
            return 404;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            HttpResponses.sendJson(exchange, 405, Map.of("error", "method_not_allowed"));
            return 405;
        }
        if (!auth.isAuthorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
            HttpResponses.sendJson(exchange, 401, Map.of("error", "unauthorized"));
            return 401;
        }
        byte[] bytes = readBounded(exchange);
        if (bytes == null) {
            HttpResponses.sendJson(exchange, 413, Map.of("error", "payload_too_large", "maxBytes", maxBytes));
            return 413;
        }
        String json = new String(bytes, StandardCharsets.UTF_8);
        Map<?, ?> root = parseObject(json);
        if (root.containsKey("kind")) {
            if (!StatusReportIngest.KIND.equals(root.get("kind"))) {
                throw new MalformedRequestException("« kind » inconnu (attendu \"" + StatusReportIngest.KIND + "\").");
            }
            StatusReportIngest.Result result = statusReports.accept(StatusReportIngest.validate(root, clock.instant()));
            HttpResponses.sendJson(exchange, 200, Map.of("result", result.name()));
            return 200;
        }
        validate(root);
        writeAtomic(json);
        HttpResponses.sendJson(exchange, 200, Map.of("result", "STORED"));
        return 200;
    }

    /** Corps complet, ou {@code null} s'il dépasse {@link #maxBytes} (annoncé ou réel). */
    private byte[] readBounded(HttpExchange exchange) throws IOException {
        String declared = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declared != null) {
            try {
                if (Long.parseLong(declared.trim()) > maxBytes) {
                    return null;
                }
            } catch (NumberFormatException e) {
                throw new MalformedRequestException("Content-Length invalide.");
            }
        }
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(maxBytes + 1);
            return bytes.length > maxBytes ? null : bytes;
        }
    }

    private static Map<?, ?> parseObject(String json) {
        Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (JsonParseException e) {
            throw new MalformedRequestException("Corps JSON invalide.");
        }
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new MalformedRequestException("Objet JSON attendu.");
        }
        return root;
    }

    void validate(Map<?, ?> root) {
        if (!(root.get("schemaVersion") instanceof Number version)
                || version.intValue() < MIN_SCHEMA_VERSION || version.intValue() > MAX_SCHEMA_VERSION
                || version.doubleValue() != version.intValue()) {
            throw new MalformedRequestException("« schemaVersion » absent ou non pris en charge (attendu "
                    + MIN_SCHEMA_VERSION + " à " + MAX_SCHEMA_VERSION + ").");
        }
        Instant generatedAt;
        try {
            generatedAt = root.get("generatedAt") instanceof String text ? Instant.parse(text) : null;
        } catch (DateTimeParseException e) {
            generatedAt = null;
        }
        if (generatedAt == null) {
            throw new MalformedRequestException("« generatedAt » absent ou invalide.");
        }
        if (generatedAt.isAfter(clock.instant().plus(MAX_CLOCK_SKEW))) {
            throw new MalformedRequestException("« generatedAt » est dans le futur.");
        }
        if (!(root.get("server") instanceof Map<?, ?> server) || !(server.get("online") instanceof Boolean)) {
            throw new MalformedRequestException("« server.online » absent.");
        }
        rejectForbiddenKeys(root, 0);
    }

    /** Défense en profondeur (#55) : l'export ne contient jamais d'UUID, le portail refuse d'en stocker. */
    private static void rejectForbiddenKeys(Object node, int depth) {
        if (depth > MAX_DEPTH) {
            throw new MalformedRequestException("Instantané trop imbriqué.");
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT).contains("uuid")) {
                    throw new MalformedRequestException("Clé interdite dans l'instantané : identifiant de joueur.");
                }
                rejectForbiddenKeys(entry.getValue(), depth + 1);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                rejectForbiddenKeys(item, depth + 1);
            }
        }
    }

    private synchronized void writeAtomic(String json) throws IOException {
        Path absolute = snapshotFile.toAbsolutePath();
        Path directory = absolute.getParent();
        Files.createDirectories(directory);
        Path tmp = Files.createTempFile(directory, "snapshot", ".json.tmp");
        try {
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
