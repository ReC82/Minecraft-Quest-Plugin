package com.lodygames.rpgquest.world.create;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Journal des demandes de création de monde reçues de PlugAdmin (issue #562) : qui, quand, quel
 * monde, quel jeton, le verdict et les contrôles relus. Le jeton rend la création idempotente :
 * une demande déjà traitée n'est jamais rejouée, même après un redémarrage.
 *
 * <p>Fichier {@code plugins/RPGQuest/world-create-audit.yml}, borné aux {@value #MAX_ENTRIES}
 * dernières demandes, créé seulement à la première demande. Lecture et écriture
 * <strong>hors du thread principal</strong> ({@code ioExecutor}).</p>
 */
public final class WorldCreationAuditLog {

    static final int MAX_ENTRIES = 200;

    /** Une demande traitée. {@code checks} : contrôles relus, sous la forme « OK|ÉCART libellé : détail ». */
    public record Entry(String at, String actor, String token, String world, String result, String message,
                        List<String> checks) {

        public Entry {
            checks = checks == null ? List.of() : List.copyOf(checks);
        }
    }

    private final Path file;
    private final Executor ioExecutor;
    private final Logger logger;
    private final String subject;
    private final List<String> header;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Object fileLock = new Object();

    public WorldCreationAuditLog(Path file, Executor ioExecutor, Logger logger) {
        this(file, ioExecutor, logger, "Création", List.of("Créations de monde demandées depuis PlugAdmin (issue #562).",
                "Fichier tenu par RPGQuest : ne pas modifier à la main (le jeton empêche une double création)."));
    }

    /**
     * Même journal pour une autre famille d'opérations sur les mondes (issue #563 : chargement,
     * déchargement, import) : {@code subject} préfixe la ligne de log, {@code header} l'en-tête du fichier.
     */
    public WorldCreationAuditLog(Path file, Executor ioExecutor, Logger logger, String subject, List<String> header) {
        this.file = file;
        this.ioExecutor = ioExecutor;
        this.logger = logger;
        this.subject = subject;
        this.header = List.copyOf(header);
    }

    /** Charge le fichier existant (hors thread principal). */
    public void load() {
        ioExecutor.execute(() -> {
            List<Entry> loaded = readFile();
            synchronized (entries) {
                List<Entry> recent = new ArrayList<>(entries);
                entries.clear();
                loaded.forEach(entries::addLast);
                recent.forEach(entries::addLast);
            }
        });
    }

    public void append(Entry entry) {
        List<Entry> copy;
        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > MAX_ENTRIES) {
                entries.removeFirst();
            }
            copy = List.copyOf(entries);
        }
        logger.info("{} du monde « {} » demandée depuis PlugAdmin par {} (jeton {}) : {}.", subject, entry.world(),
                entry.actor(), entry.token(), entry.result());
        ioExecutor.execute(() -> writeFile(copy));
    }

    /** Demande déjà traitée avec ce jeton. */
    public Optional<Entry> byToken(String token) {
        synchronized (entries) {
            return entries.stream().filter(e -> e.token().equals(token)).reduce((a, b) -> b);
        }
    }

    /** Dernières demandes, la plus récente d'abord. */
    public List<Entry> recent(int limit) {
        List<Entry> out = new ArrayList<>();
        synchronized (entries) {
            var it = entries.descendingIterator();
            while (it.hasNext() && out.size() < limit) {
                out.add(it.next());
            }
        }
        return out;
    }

    private List<Entry> readFile() {
        if (!Files.exists(file)) {
            return List.of();
        }
        synchronized (fileLock) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
            List<Entry> out = new ArrayList<>();
            for (Map<?, ?> raw : yaml.getMapList("entries")) {
                List<String> checks = new ArrayList<>();
                if (raw.get("checks") instanceof List<?> list) {
                    list.forEach(o -> checks.add(String.valueOf(o)));
                }
                out.add(new Entry(s(raw.get("at")), s(raw.get("actor")), s(raw.get("token")), s(raw.get("world")),
                        s(raw.get("result")), s(raw.get("message")), checks));
            }
            return out;
        }
    }

    private void writeFile(List<Entry> snapshot) {
        synchronized (fileLock) {
            try {
                Files.createDirectories(file.getParent());
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.options().setHeader(header);
                List<Map<String, Object>> list = new ArrayList<>();
                for (Entry e : snapshot) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("at", e.at());
                    m.put("actor", e.actor());
                    m.put("token", e.token());
                    m.put("world", e.world());
                    m.put("result", e.result());
                    m.put("message", e.message());
                    m.put("checks", e.checks());
                    list.add(m);
                }
                yaml.set("entries", list);
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                yaml.save(tmp.toFile());
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | RuntimeException e) {
                logger.error("Impossible d'écrire le journal des opérations sur les mondes {}.", file, e);
            }
        }
    }

    private static String s(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
