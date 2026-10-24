package com.lodygames.rpgquest.webapi.ingest;

import com.lodygames.rpgquest.webapi.http.MalformedRequestException;
import com.lodygames.rpgquest.webapi.json.Json;
import com.lodygames.rpgquest.webapi.json.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Relevé d'état agrégé du serveur de jeu (#421, {@code "kind": "status"}), reçu sur
 * {@code POST /api/snapshot} et déposé dans {@code server-status.json}.
 *
 * <p><strong>Périmètre fermé</strong> : seules les clés {@link #ROOT_KEYS} et, sous {@code server},
 * {@link #SERVER_KEYS} sont admises — tout autre champ est refusé (400) avant écriture. Le relevé ne
 * peut donc transporter ni pseudo, ni UUID, ni coordonnée : uniquement « en ligne ? » et deux
 * compteurs, plus la charge facultative du serveur ({@code tps}, {@code mspt}).</p>
 *
 * <p><strong>Idempotence</strong> : le plugin numérote ses relevés ({@code sequence}) au sein d'un
 * démarrage ({@code bootId}). Un relevé déjà reçu ({@link Result#DUPLICATE}) ou dépassé
 * ({@link Result#OUT_OF_ORDER}, y compris un retard d'un démarrage précédent) est accusé 200 sans
 * rien écrire : un renvoi ne fait jamais reculer l'état. Rien n'est cumulé : le nombre de joueurs
 * est une valeur instantanée remplacée, jamais additionnée.</p>
 */
final class StatusReportIngest {

    /** Issue d'une réception, renvoyée telle quelle dans {@code result}. */
    enum Result { STORED, DUPLICATE, OUT_OF_ORDER }

    static final String KIND = "status";
    static final int SCHEMA_VERSION = 1;
    static final Set<String> ROOT_KEYS = Set.of("schemaVersion", "kind", "generatedAt", "bootId", "sequence", "server");
    static final Set<String> SERVER_KEYS = Set.of("online", "playerCount", "maxPlayers", "tps", "mspt");
    static final Pattern BOOT_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final int MAX_PLAYERS = 100_000;
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final double MAX_TPS = 100;
    private static final double MAX_MSPT = 60_000;

    private final Path file;
    private final Clock clock;
    private final Logger logger;

    StatusReportIngest(Path file, Clock clock, Logger logger) {
        this.file = file;
        this.clock = clock;
        this.logger = logger;
    }

    /** Relevé validé, prêt à comparer et à stocker. */
    record Report(String bootId, long sequence, Instant generatedAt, boolean online, int playerCount, int maxPlayers,
                  Double tps, Double mspt) {
    }

    static Report validate(Map<?, ?> root, Instant now) {
        for (Object key : root.keySet()) {
            if (!ROOT_KEYS.contains(String.valueOf(key))) {
                throw new MalformedRequestException("Relevé d'état : champ non autorisé « " + key + " ».");
            }
        }
        if (!(root.get("schemaVersion") instanceof Number version) || version.doubleValue() != SCHEMA_VERSION) {
            throw new MalformedRequestException("Relevé d'état : « schemaVersion » doit valoir " + SCHEMA_VERSION + ".");
        }
        if (!(root.get("bootId") instanceof String bootId) || !BOOT_ID.matcher(bootId).matches()) {
            throw new MalformedRequestException("Relevé d'état : « bootId » absent ou invalide.");
        }
        long sequence = wholeNumber(root.get("sequence"), "sequence", Long.MAX_VALUE);
        Instant generatedAt = instant(root.get("generatedAt"));
        if (generatedAt == null) {
            throw new MalformedRequestException("Relevé d'état : « generatedAt » absent ou invalide.");
        }
        if (generatedAt.isAfter(now.plus(MAX_CLOCK_SKEW))) {
            throw new MalformedRequestException("Relevé d'état : « generatedAt » est dans le futur.");
        }
        if (!(root.get("server") instanceof Map<?, ?> server) || !(server.get("online") instanceof Boolean online)) {
            throw new MalformedRequestException("Relevé d'état : « server.online » absent.");
        }
        for (Object key : server.keySet()) {
            if (!SERVER_KEYS.contains(String.valueOf(key))) {
                throw new MalformedRequestException("Relevé d'état : champ non autorisé « server." + key + " ».");
            }
        }
        int playerCount = (int) wholeNumber(server.get("playerCount"), "server.playerCount", MAX_PLAYERS);
        int maxPlayers = (int) wholeNumber(server.get("maxPlayers"), "server.maxPlayers", MAX_PLAYERS);
        // Charge du serveur (facultative) : de quoi mesurer l'effet de l'export sans lire les journaux VeryGames.
        Double tps = optionalDecimal(server.get("tps"), "server.tps", MAX_TPS);
        Double mspt = optionalDecimal(server.get("mspt"), "server.mspt", MAX_MSPT);
        return new Report(bootId, sequence, generatedAt, online, playerCount, maxPlayers, tps, mspt);
    }

    /** Compare au dernier relevé stocké et l'écrit s'il est plus récent. Sérialisé : un seul écrivain. */
    synchronized Result accept(Report report) throws IOException {
        Map<?, ?> previous = readPrevious();
        Result result = decide(previous, report);
        if (result != Result.STORED) {
            return result;
        }
        boolean newBoot = previous == null || !report.bootId().equals(previous.get("bootId"));
        boolean wasOnline = previous != null && previous.get("server") instanceof Map<?, ?> server
                && Boolean.TRUE.equals(server.get("online"));
        writeAtomic(Json.write(stored(report, clock.instant())));
        if (newBoot) {
            logger.info("Relevé d'état du serveur de jeu : nouveau démarrage reçu (" + (report.online() ? "en ligne" : "arrêt annoncé")
                    + ", " + report.playerCount() + "/" + report.maxPlayers() + " joueurs).");
        } else if (wasOnline && !report.online()) {
            logger.info("Relevé d'état du serveur de jeu : arrêt annoncé par le serveur.");
        }
        return Result.STORED;
    }

    static Result decide(Map<?, ?> previous, Report report) {
        return decide(previous, report.bootId(), report.sequence(), report.generatedAt());
    }

    /**
     * Règle commune au relevé d'état et à l'instantané complet : un envoi déjà reçu (même démarrage,
     * même numéro) est un doublon, un numéro inférieur ou un démarrage plus ancien est dépassé.
     */
    static Result decide(Map<?, ?> previous, String bootId, long sequence, Instant generatedAt) {
        if (previous == null) {
            return Result.STORED;
        }
        if (bootId.equals(previous.get("bootId"))) {
            long last = previous.get("sequence") instanceof Number n ? n.longValue() : -1L;
            if (sequence == last) {
                return Result.DUPLICATE;
            }
            return sequence > last ? Result.STORED : Result.OUT_OF_ORDER;
        }
        Instant lastGenerated = instant(previous.get("generatedAt"));
        return lastGenerated == null || !generatedAt.isBefore(lastGenerated)
                ? Result.STORED : Result.OUT_OF_ORDER;
    }

    static Map<String, Object> stored(Report report, Instant receivedAt) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("bootId", report.bootId());
        root.put("sequence", report.sequence());
        root.put("generatedAt", report.generatedAt().toString());
        root.put("receivedAt", receivedAt.toString());
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("online", report.online());
        server.put("playerCount", report.playerCount());
        server.put("maxPlayers", report.maxPlayers());
        if (report.tps() != null) {
            server.put("tps", report.tps());
        }
        if (report.mspt() != null) {
            server.put("mspt", report.mspt());
        }
        root.put("server", server);
        return root;
    }

    private Map<?, ?> readPrevious() throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> map ? map : null;
        } catch (JsonParseException e) {
            logger.warning("Relevé d'état précédent illisible, remplacé : " + file);
            return null;
        }
    }

    private void writeAtomic(String json) throws IOException {
        Path directory = file.getParent();
        Files.createDirectories(directory);
        Path tmp = Files.createTempFile(directory, "server-status", ".json.tmp");
        try {
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Double optionalDecimal(Object raw, String name, double max) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < 0 || number.doubleValue() > max) {
            throw new MalformedRequestException("Relevé d'état : « " + name + " » doit être un nombre entre 0 et " + max + ".");
        }
        return Math.round(number.doubleValue() * 100) / 100.0;
    }

    static long wholeNumber(Object raw, String name, long max) {
        if (!(raw instanceof Number number) || number.doubleValue() != Math.floor(number.doubleValue())
                || number.doubleValue() < 0 || number.doubleValue() > max) {
            throw new MalformedRequestException("Relevé d'état : « " + name + " » doit être un entier entre 0 et " + max + ".");
        }
        return number.longValue();
    }

    static Instant instant(Object raw) {
        try {
            return raw instanceof String text ? Instant.parse(text) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
