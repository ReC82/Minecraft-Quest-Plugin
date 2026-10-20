package com.lodygames.rpgquest.world.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Journal des réglages de monde appliqués depuis PlugAdmin (issue #561) : qui, quand, quel monde,
 * et pour chaque réglage la valeur avant, la valeur demandée et la valeur relue après.
 *
 * <p>Fichier {@code plugins/RPGQuest/world-settings-audit.yml}, borné aux {@value #MAX_ENTRIES}
 * dernières applications. Lecture et écriture <strong>hors du thread principal</strong>
 * ({@code ioExecutor}) ; la mémoire garde la même liste pour l'affichage.</p>
 */
public final class WorldSettingsAuditLog {

    static final int MAX_ENTRIES = 300;

    /** Un réglage dans une application : avant → demandé → relu, et son verdict. */
    public record Line(String key, String before, String requested, String after, String outcome) {
    }

    /** Une application. {@code result} : {@code APPLIED}, {@code PARTIAL}, {@code FAILED}. */
    public record Entry(String at, String actor, String world, String result, List<Line> lines) {
    }

    private final Path file;
    private final Executor ioExecutor;
    private final Logger logger;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Object fileLock = new Object();

    public WorldSettingsAuditLog(Path file, Executor ioExecutor, Logger logger) {
        this.file = file;
        this.ioExecutor = ioExecutor;
        this.logger = logger;
    }

    /** Charge le fichier existant (hors thread principal). */
    public void load() {
        ioExecutor.execute(() -> {
            List<Entry> loaded = readFile();
            synchronized (entries) {
                entries.clear();
                loaded.forEach(entries::addLast);
            }
        });
    }

    /** Ajoute une entrée en mémoire puis réécrit le fichier hors du thread principal. */
    public void append(Entry entry) {
        List<Entry> copy;
        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > MAX_ENTRIES) {
                entries.removeFirst();
            }
            copy = List.copyOf(entries);
        }
        logger.info("Réglages du monde « {} » appliqués depuis PlugAdmin par {} : {} ({} réglage(s)).",
                entry.world(), entry.actor(), entry.result(), entry.lines().size());
        ioExecutor.execute(() -> writeFile(copy));
    }

    /** Dernières entrées d'un monde, la plus récente d'abord. */
    public List<Entry> recent(String world, int limit) {
        List<Entry> out = new ArrayList<>();
        synchronized (entries) {
            var it = entries.descendingIterator();
            while (it.hasNext() && out.size() < limit) {
                Entry e = it.next();
                if (e.world().equalsIgnoreCase(world)) {
                    out.add(e);
                }
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
                List<Line> lines = new ArrayList<>();
                if (raw.get("changes") instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> m) {
                            lines.add(new Line(s(m.get("key")), s(m.get("before")), s(m.get("requested")),
                                    s(m.get("after")), s(m.get("outcome"))));
                        }
                    }
                }
                out.add(new Entry(s(raw.get("at")), s(raw.get("actor")), s(raw.get("world")), s(raw.get("result")),
                        List.copyOf(lines)));
            }
            return out;
        }
    }

    private void writeFile(List<Entry> snapshot) {
        synchronized (fileLock) {
            try {
                Files.createDirectories(file.getParent());
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.options().setHeader(List.of("Réglages de monde appliqués depuis PlugAdmin (issue #561).",
                        "Fichier tenu par RPGQuest : ne pas modifier à la main."));
                List<Map<String, Object>> list = new ArrayList<>();
                for (Entry e : snapshot) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("at", e.at());
                    m.put("actor", e.actor());
                    m.put("world", e.world());
                    m.put("result", e.result());
                    List<Map<String, Object>> lines = new ArrayList<>();
                    for (Line l : e.lines()) {
                        Map<String, Object> lm = new LinkedHashMap<>();
                        lm.put("key", l.key());
                        lm.put("before", l.before());
                        lm.put("requested", l.requested());
                        lm.put("after", l.after());
                        lm.put("outcome", l.outcome());
                        lines.add(lm);
                    }
                    m.put("changes", lines);
                    list.add(m);
                }
                yaml.set("entries", list);
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                yaml.save(tmp.toFile());
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | RuntimeException e) {
                logger.error("Impossible d'écrire le journal des réglages de monde {}.", file, e);
            }
        }
    }

    private static String s(Object o) {
        return o == null ? "" : o instanceof ConfigurationSection ? "" : String.valueOf(o);
    }
}
