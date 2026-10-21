package com.lodygames.rpgquest.travel;

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
import java.util.concurrent.Executor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Journal des modifications de portails faites depuis PlugAdmin (issue #565) : qui, quand, quelle
 * opération, et l'état du portail avant et après (résumé lisible).
 *
 * <p>Fichier {@code plugins/RPGQuest/portal-audit.yml}, créé à la première modification seulement,
 * borné aux {@value #MAX_ENTRIES} dernières entrées. Même conception que {@code
 * world.settings.WorldSettingsAuditLog} : lecture et écriture hors du thread principal.</p>
 */
public final class PortalAuditLog {

    static final int MAX_ENTRIES = 300;

    /** {@code before}/{@code after} : {@code ""} quand le portail n'existait pas / n'existe plus. */
    public record Entry(String at, String actor, String operation, String portal, String before, String after) {
    }

    private final Path file;
    private final Executor ioExecutor;
    private final Logger logger;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Object fileLock = new Object();

    public PortalAuditLog(Path file, Executor ioExecutor, Logger logger) {
        this.file = file;
        this.ioExecutor = ioExecutor;
        this.logger = logger;
    }

    public void load() {
        ioExecutor.execute(() -> {
            List<Entry> loaded = readFile();
            synchronized (entries) {
                entries.clear();
                loaded.forEach(entries::addLast);
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
        logger.info("Portail « {} » : {} depuis PlugAdmin par {}.", entry.portal(), entry.operation(), entry.actor());
        ioExecutor.execute(() -> writeFile(copy));
    }

    /** Dernières entrées, la plus récente d'abord. */
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
                out.add(new Entry(s(raw.get("at")), s(raw.get("actor")), s(raw.get("operation")),
                        s(raw.get("portal")), s(raw.get("before")), s(raw.get("after"))));
            }
            return out;
        }
    }

    private void writeFile(List<Entry> snapshot) {
        synchronized (fileLock) {
            try {
                Files.createDirectories(file.getParent());
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.options().setHeader(List.of("Portails modifiés depuis PlugAdmin (issue #565).",
                        "Fichier tenu par RPGQuest : ne pas modifier à la main."));
                List<Map<String, Object>> list = new ArrayList<>();
                for (Entry e : snapshot) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("at", e.at());
                    m.put("actor", e.actor());
                    m.put("operation", e.operation());
                    m.put("portal", e.portal());
                    m.put("before", e.before());
                    m.put("after", e.after());
                    list.add(m);
                }
                yaml.set("entries", list);
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                yaml.save(tmp.toFile());
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | RuntimeException e) {
                logger.error("Impossible d'écrire le journal des portails {}.", file, e);
            }
        }
    }

    private static String s(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
