package com.lodygames.rpgquest.stats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Administration des classements depuis PlugAdmin (issue #735) : créer, modifier, dupliquer,
 * changer le statut (brouillon, actif, en pause, archivé), l'ordre, la visibilité, la mise en avant
 * et le fuseau horaire. Tout est écrit dans {@code leaderboards.yml}, <strong>séparé</strong> de
 * {@code statistics.yml} (saisons, événements, exclusions) et de toute donnée de jeu.
 *
 * <p>Mêmes garanties que le catalogue des prix (#336) :</p>
 * <ol>
 *   <li>le fichier actuel doit être accepté par le serveur — jamais d'écriture par-dessus un fichier refusé ;</li>
 *   <li>le texte final est validé par le <strong>vrai</strong> chargeur, avec {@code statistics.yml}
 *       (identifiants uniques, événements cités) — sinon rien n'est écrit ;</li>
 *   <li>écriture atomique ; création sans jamais écraser un fichier apparu entre-temps ;</li>
 *   <li>rechargement immédiat ; s'il ne redonne pas exactement les classements attendus, l'ancien
 *       fichier est remis à l'octet près (ou retiré) et rechargé.</li>
 * </ol>
 *
 * <p>Tant que {@code leaderboards.yml} n'existe pas, le catalogue par défaut du JAR s'applique : le
 * premier enregistrement le recopie dans le fichier avec la modification demandée. Si
 * {@code statistics.yml} a sa propre liste {@code leaderboards:} (format #734), elle reste
 * prioritaire et PlugAdmin refuse d'écrire. Aucune suppression : un classement se met en pause ou
 * s'archive, ses résultats figés restent en base. Appelé hors du thread principal ; un verrou
 * sérialise les écritures.</p>
 */
public final class LeaderboardCatalogEditor {

    /** Résultat : {@code code} stable pour PlugAdmin, {@code message} en français. */
    public record Outcome(boolean success, String code, String message) {

        static Outcome refused(String code, String message) {
            return new Outcome(false, code, message);
        }
    }

    /** Une entrée telle qu'écrite dans le fichier, clés dans l'ordre canonique, et sa version. */
    public record Entry(Map<String, Object> fields, String version) {
        public String id() {
            return String.valueOf(fields.get("id"));
        }
    }

    /** Catalogue administrable : entrées (tous statuts), fuseau écrit, présence du fichier. */
    public record Catalog(List<Entry> entries, @Nullable String timezone, boolean fileExists) {
        public @Nullable Entry find(String id) {
            return entries.stream().filter(entry -> entry.id().equals(id)).findFirst().orElse(null);
        }
    }

    /** Ordre des clés écrites, une ligne lisible par classement. */
    static final List<String> KEYS = List.of("id", "name", "description", "category", "icon", "metric", "filter",
            "period", "event", "start", "end", "size", "status", "order", "visible", "featured");
    static final int MAX_BOARDS = 200;

    static final String HEADER = """
            # Classements du site public LodyQuests (leaderboards.yml), gérés depuis PlugAdmin → Classements (#735).
            # Séparé de statistics.yml (saisons, événements, exclusions) et de toute donnée de jeu. Aucune modification
            # à la main n'est nécessaire. Statuts : draft (en préparation), enabled (calculé, figé à chaque fin de
            # période, publié s'il est visible), paused (suspendu), archived (retiré ; ses résultats figés restent).
            # Rien n'est publié tant que web-export.enabled est faux, et seuls les profils visibles (#55) apparaissent.
            """;

    private final StatisticsService service;
    private final Supplier<Path> statisticsFile;
    private final Logger logger;

    public LeaderboardCatalogEditor(StatisticsService service, Logger logger) {
        this.service = service;
        this.statisticsFile = service::file;
        this.logger = logger;
    }

    /** Refus de lecture ou d'écriture, porté jusqu'à {@link Outcome}. */
    static final class Refusal extends Exception {
        final Outcome outcome;

        Refusal(String code, String message) {
            super(message, null, false, false);
            this.outcome = Outcome.refused(code, message);
        }
    }

    private record Current(byte @Nullable [] bytes, YamlConfiguration statistics, Catalog catalog) {
    }

    /** Catalogue actuel (lecture seule, pour PlugAdmin) ; {@code null} si les fichiers sont refusés. */
    public synchronized Catalog catalog() throws Refusal {
        return read().catalog();
    }

    // ---- Opérations ---------------------------------------------------------------------------

    /**
     * Crée ({@code create}, statut brouillon, en fin de liste) ou modifie un classement. Le statut et
     * l'ordre d'un classement existant ne changent pas ici : ils ont leurs propres gestes.
     *
     * @param wanted          champs du formulaire (id, name, description, category, icon, metric, filter,
     *                        period, event, start, end, size, visible, featured)
     * @param expectedVersion version lue par le formulaire ({@code null} = pas de contrôle)
     */
    public synchronized Outcome save(Map<String, Object> wanted, @Nullable String expectedVersion, boolean create) {
        String id = String.valueOf(wanted.get("id"));
        return mutate(current -> {
            List<Map<String, Object>> entries = fieldsOf(current.catalog());
            Entry existing = current.catalog().find(id);
            Map<String, Object> next = canonical(wanted);
            if (create) {
                if (existing != null) {
                    throw new Refusal("EXISTS", "Un classement « " + id + " » existe déjà : choisir un autre identifiant.");
                }
                if (entries.size() >= MAX_BOARDS) {
                    throw new Refusal("INVALID", MAX_BOARDS + " classements au plus.");
                }
                next.put("status", StatBoard.Status.DRAFT.wireName());
                next.put("order", nextOrder(entries));
                entries.add(canonical(next));
                return new Change(entries, current.catalog().timezone(), "CREATED", "Classement « " + next.get("name")
                        + " » créé en brouillon : le prévisualiser, puis l'activer. Rien n'est calculé ni publié avant.");
            }
            requireExisting(existing, id);
            checkVersion(existing, expectedVersion);
            next.put("status", existing.fields().get("status"));
            next.put("order", existing.fields().get("order"));
            Map<String, Object> merged = canonical(next);
            if (merged.equals(existing.fields())) {
                throw new Unchanged("Aucune modification : « " + next.get("name") + " » est déjà enregistré ainsi.");
            }
            entries.set(indexOf(entries, id), merged);
            return new Change(entries, current.catalog().timezone(), "UPDATED", "Classement « " + next.get("name")
                    + " » modifié." + (isActive(merged) ? " Le site le montre ainsi au prochain instantané." : ""));
        });
    }

    /** Copie {@code sourceId} sous {@code newId}, en brouillon, non mise en avant, en fin de liste. */
    public synchronized Outcome duplicate(String sourceId, String newId, @Nullable String newName) {
        return mutate(current -> {
            List<Map<String, Object>> entries = fieldsOf(current.catalog());
            Entry source = current.catalog().find(sourceId);
            requireExisting(source, sourceId);
            if (current.catalog().find(newId) != null) {
                throw new Refusal("EXISTS", "Un classement « " + newId + " » existe déjà : choisir un autre identifiant.");
            }
            Map<String, Object> copy = new LinkedHashMap<>(source.fields());
            String name = newName == null || newName.isBlank()
                    ? truncate(String.valueOf(source.fields().get("name")), StatisticsDefinitionLoader.LABEL_MAX - 8) + " (copie)"
                    : newName.trim();
            copy.put("id", newId);
            copy.put("name", name);
            copy.put("status", StatBoard.Status.DRAFT.wireName());
            copy.put("featured", false);
            copy.put("order", nextOrder(entries));
            entries.add(canonical(copy));
            return new Change(entries, current.catalog().timezone(), "DUPLICATED", "Copie « " + name
                    + " » créée en brouillon : la modifier, la prévisualiser, puis l'activer.");
        });
    }

    /** Change le statut ; le contenu ne bouge pas. Les résultats déjà figés restent en base. */
    public synchronized Outcome setStatus(String id, StatBoard.Status status) {
        return mutate(current -> {
            List<Map<String, Object>> entries = fieldsOf(current.catalog());
            Entry existing = current.catalog().find(id);
            requireExisting(existing, id);
            if (status.wireName().equals(existing.fields().get("status"))) {
                throw new Unchanged("« " + existing.fields().get("name") + " » est déjà " + statusLabel(status) + ".");
            }
            Map<String, Object> next = new LinkedHashMap<>(existing.fields());
            next.put("status", status.wireName());
            entries.set(indexOf(entries, id), canonical(next));
            return new Change(entries, current.catalog().timezone(), "STATUS", "« " + existing.fields().get("name")
                    + " » est maintenant " + statusLabel(status) + ". " + statusEffect(status));
        });
    }

    /** Mise en avant sur l'accueil ({@code featured}) ou visibilité sur le site ({@code visible}). */
    public synchronized Outcome setFlag(String id, String flag, boolean value) {
        if (!flag.equals("featured") && !flag.equals("visible")) {
            return Outcome.refused("INVALID", "Réglage inconnu « " + flag + " ».");
        }
        return mutate(current -> {
            List<Map<String, Object>> entries = fieldsOf(current.catalog());
            Entry existing = current.catalog().find(id);
            requireExisting(existing, id);
            if (Boolean.valueOf(value).equals(existing.fields().get(flag))) {
                throw new Unchanged("Rien à changer pour « " + existing.fields().get("name") + " ».");
            }
            Map<String, Object> next = new LinkedHashMap<>(existing.fields());
            next.put(flag, value);
            entries.set(indexOf(entries, id), canonical(next));
            String name = String.valueOf(existing.fields().get("name"));
            String message = flag.equals("featured")
                    ? (value ? "« " + name + " » est mis en avant sur l'accueil du site."
                    : "« " + name + " » n'est plus mis en avant sur l'accueil.")
                    : (value ? "« " + name + " » est de nouveau visible sur le site."
                    : "« " + name + " » est caché du site : il reste calculé et figé s'il est actif.");
            return new Change(entries, current.catalog().timezone(), "FLAG", message);
        });
    }

    /** Monte ({@code up}) ou descend un classement d'une place ; les ordres sont renumérotés 10, 20, 30… */
    public synchronized Outcome move(String id, boolean up) {
        return mutate(current -> {
            List<Map<String, Object>> entries = fieldsOf(current.catalog());
            entries.sort(java.util.Comparator.comparingInt(entry -> (Integer) entry.get("order")));
            int index = indexOf(entries, id);
            if (index < 0) {
                requireExisting(null, id);
            }
            int target = up ? index - 1 : index + 1;
            if (target < 0 || target >= entries.size()) {
                throw new Unchanged("Déjà " + (up ? "en tête" : "en dernier") + ".");
            }
            Map<String, Object> moved = entries.remove(index);
            entries.add(target, moved);
            for (int i = 0; i < entries.size(); i++) {
                Map<String, Object> next = new LinkedHashMap<>(entries.get(i));
                next.put("order", (i + 1) * 10);
                entries.set(i, canonical(next));
            }
            return new Change(entries, current.catalog().timezone(), "MOVED", "« " + moved.get("name") + " » "
                    + (up ? "monte" : "descend") + " d'une place.");
        });
    }

    /** Fuseau horaire des périodes de calendrier (jour, semaine, mois). */
    public synchronized Outcome setTimezone(String zoneId) {
        java.time.ZoneId zone;
        try {
            zone = java.time.ZoneId.of(zoneId);
        } catch (java.time.DateTimeException e) {
            return Outcome.refused("INVALID", "Fuseau horaire inconnu « " + zoneId + " » (ex. Europe/Brussels).");
        }
        return mutate(current -> {
            if (zone.getId().equals(current.catalog().timezone())) {
                throw new Unchanged("Le fuseau est déjà " + zone.getId() + ".");
            }
            return new Change(fieldsOf(current.catalog()), zone.getId(), "TIMEZONE", "Fuseau horaire : " + zone.getId()
                    + ". Les jours, semaines et mois des classements commencent désormais à minuit dans ce fuseau.");
        });
    }

    // ---- Lecture, validation, écriture ---------------------------------------------------------

    private record Change(List<Map<String, Object>> entries, @Nullable String timezone, String code, String message) {
    }

    private static final class Unchanged extends Exception {
        Unchanged(String message) {
            super(message, null, false, false);
        }
    }

    @FunctionalInterface
    private interface Mutation {
        Change apply(Current current) throws Refusal, Unchanged;
    }

    private Outcome mutate(Mutation mutation) {
        Current current;
        Change change;
        try {
            current = read();
            change = mutation.apply(current);
        } catch (Refusal refusal) {
            return refusal.outcome;
        } catch (Unchanged unchanged) {
            return new Outcome(true, "UNCHANGED", unchanged.getMessage());
        }
        return write(current, change);
    }

    private Current read() throws Refusal {
        Path statistics = statisticsFile.get();
        YamlConfiguration statisticsYaml = new YamlConfiguration();
        try {
            if (Files.isRegularFile(statistics)) {
                statisticsYaml.loadFromString(Files.readString(statistics));
            }
        } catch (IOException | InvalidConfigurationException e) {
            throw new Refusal("CURRENT_INVALID", "statistics.yml ne se relit pas : rien n'a été écrit.");
        }
        if (statisticsYaml.contains("leaderboards")) {
            throw new Refusal("STATISTICS_FILE", "Les classements sont définis à la main dans statistics.yml "
                    + "(« leaderboards: ») : ce fichier reste prioritaire et PlugAdmin n'y écrit pas. Retirer cette clé "
                    + "du fichier pour gérer les classements ici.");
        }
        Path file = service.boardsFile();
        if (!Files.isRegularFile(file)) {
            List<Entry> entries = new ArrayList<>();
            List<Map<?, ?>> bundled = StatisticsDefinitionLoader.bundledBoards();
            for (int i = 0; i < bundled.size(); i++) {
                entries.add(entry(withDefaults(bundled.get(i), i)));
            }
            return new Current(null, statisticsYaml, new Catalog(List.copyOf(entries), null, false));
        }
        byte[] bytes;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            bytes = Files.readAllBytes(file);
            yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            throw new Refusal("CURRENT_INVALID", "leaderboards.yml ne se relit pas (" + e.getMessage()
                    + ") : rien n'a été écrit. Le corriger d'abord.");
        }
        StatisticsDefinitionLoader.Result result = StatisticsDefinitionLoader.load(statisticsYaml, yaml);
        if (!result.valid()) {
            throw new Refusal("CURRENT_INVALID", "Le serveur refuse actuellement leaderboards.yml ("
                    + summary(result.errors()) + ") : rien n'a été écrit, pour ne pas aggraver la situation.");
        }
        List<Entry> entries = new ArrayList<>();
        List<Map<?, ?>> raw = yaml.getMapList("leaderboards");
        for (int i = 0; i < raw.size(); i++) {
            entries.add(entry(withDefaults(raw.get(i), i)));
        }
        String zone = yaml.contains("timezone") ? yaml.getString("timezone") : null;
        return new Current(bytes, statisticsYaml, new Catalog(List.copyOf(entries), zone, true));
    }

    private Outcome write(Current current, Change change) {
        YamlConfiguration out = new YamlConfiguration();
        if (change.timezone() != null) {
            out.set("timezone", change.timezone());
        }
        out.set("leaderboards", change.entries());
        String text = HEADER + out.saveToString();
        YamlConfiguration reread = new YamlConfiguration();
        try {
            reread.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            return Outcome.refused("INVALID", "Rendu YAML illisible, rien n'a été écrit : " + e.getMessage());
        }
        StatisticsDefinitionLoader.Result check = StatisticsDefinitionLoader.load(current.statistics(), reread);
        if (!check.valid()) {
            return Outcome.refused("INVALID", "Refusé par le serveur, rien n'a été écrit : " + summary(check.errors()));
        }
        Path file = service.boardsFile();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try {
            if (current.bytes() == null) {
                createAtomically(file, bytes);
            } else {
                if (!Arrays.equals(Files.readAllBytes(file), current.bytes())) {
                    return Outcome.refused("STALE", "leaderboards.yml a changé entre-temps : rien n'a été écrit. "
                            + "Recharger la page.");
                }
                writeAtomically(file, bytes);
            }
        } catch (FileAlreadyExistsException e) {
            return Outcome.refused("STALE", "Un leaderboards.yml est apparu entre-temps : il n'a pas été touché. "
                    + "Recharger la page.");
        } catch (IOException e) {
            return Outcome.refused("ERROR", "Écriture de leaderboards.yml impossible : " + e.getMessage());
        }
        StatisticsDefinitionLoader.Result applied = service.reloadNow();
        if (applied.valid() && applied.boardsSource() == StatisticsDefinitionLoader.BoardsSource.MANAGED_FILE
                && applied.definition().boards().equals(check.definition().boards())
                && applied.definition().zone().equals(check.definition().zone())) {
            logger.info("Classements : leaderboards.yml écrit depuis PlugAdmin ({}) — {} classement(s), {} actif(s).",
                    change.code(), applied.definition().boards().size(), applied.definition().activeBoards().size());
            return new Outcome(true, change.code(), change.message());
        }
        String why = applied.valid() ? "classements rechargés différents de ceux écrits" : summary(applied.errors());
        try {
            if (current.bytes() == null) {
                Files.deleteIfExists(file);
            } else {
                writeAtomically(file, current.bytes());
            }
        } catch (IOException e) {
            logger.error("Classements : restauration de {} IMPOSSIBLE après un rechargement refusé.", file, e);
            return Outcome.refused("ERROR", "Rechargement refusé (" + why + ") ET restauration impossible : "
                    + "vérifier leaderboards.yml sur le serveur.");
        }
        service.reloadNow();
        logger.warn("Classements : écriture ({}) annulée, ancien leaderboards.yml remis ({}).", change.code(), why);
        return Outcome.refused("ROLLED_BACK", "Le serveur n'a pas accepté le nouveau fichier (" + why + ") : "
                + (current.bytes() == null ? "le fichier a été retiré" : "l'ancienne version a été remise")
                + ", rien n'a changé.");
    }

    // ---- Forme canonique -----------------------------------------------------------------------

    /** Entrée complétée de ses valeurs implicites (statut, ordre, taille, visibilité). */
    private static Map<String, Object> withDefaults(Map<?, ?> raw, int index) {
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, value) -> map.put(String.valueOf(key), value));
        if (!map.containsKey("status")) {
            map.put("status", Boolean.FALSE.equals(map.get("enabled")) ? "paused" : "enabled");
        }
        map.remove("enabled");
        map.putIfAbsent("order", (index + 1) * 10);
        map.putIfAbsent("size", 10);
        map.putIfAbsent("visible", true);
        map.putIfAbsent("featured", false);
        map.putIfAbsent("period", "lifetime");
        return canonical(map);
    }

    /**
     * Clés connues dans l'ordre de {@link #KEYS}, valeurs vides retirées, filtre en listes triées,
     * dates en texte ISO : deux entrées égales s'écrivent à l'identique (et ont la même version).
     */
    static Map<String, Object> canonical(Map<String, ?> raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : KEYS) {
            Object value = raw.get(key);
            if (value == null || value instanceof String text && text.isBlank()) {
                continue;
            }
            if (key.equals("filter")) {
                if (!(value instanceof Map<?, ?> filter) || filter.isEmpty()) {
                    continue;
                }
                Map<String, Object> sorted = new java.util.TreeMap<>();
                filter.forEach((k, v) -> sorted.put(String.valueOf(k), (v instanceof List<?> list ? list : List.of(v))
                        .stream().map(item -> String.valueOf(item).trim()).distinct().sorted().toList()));
                value = new LinkedHashMap<>(sorted);
            } else if (value instanceof java.util.Date date) {
                value = date.toInstant().toString();
            } else if (value instanceof String text) {
                value = text.trim();
            }
            map.put(key, value);
        }
        return map;
    }

    private static Entry entry(Map<String, Object> fields) {
        return new Entry(java.util.Collections.unmodifiableMap(fields), version(fields));
    }

    static String version(Map<String, Object> fields) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(fields.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Map<String, Object>> fieldsOf(Catalog catalog) {
        List<Map<String, Object>> list = new ArrayList<>();
        catalog.entries().forEach(entry -> list.add(new LinkedHashMap<>(entry.fields())));
        return list;
    }

    private static int indexOf(List<Map<String, Object>> entries, String id) {
        for (int i = 0; i < entries.size(); i++) {
            if (id.equals(entries.get(i).get("id"))) {
                return i;
            }
        }
        return -1;
    }

    private static int nextOrder(List<Map<String, Object>> entries) {
        return entries.stream().mapToInt(entry -> entry.get("order") instanceof Integer order ? order : 0)
                .max().orElse(0) + 10;
    }

    private static boolean isActive(Map<String, Object> fields) {
        return StatBoard.Status.ENABLED.wireName().equals(fields.get("status"));
    }

    private static void requireExisting(@Nullable Entry entry, String id) throws Refusal {
        if (entry == null) {
            throw new Refusal("NOT_FOUND", "Classement « " + id + " » introuvable : recharger la page.");
        }
    }

    private static void checkVersion(Entry existing, @Nullable String expected) throws Refusal {
        if (expected != null && !expected.isBlank() && !existing.version().equals(expected)) {
            throw new Refusal("STALE", "Le classement « " + existing.id() + " » a changé depuis l'ouverture du "
                    + "formulaire : rien n'a été écrit. Recharger la page puis refaire la modification.");
        }
    }

    static String statusLabel(StatBoard.Status status) {
        return switch (status) {
            case DRAFT -> "en brouillon";
            case ENABLED -> "actif";
            case PAUSED -> "en pause";
            case ARCHIVED -> "archivé";
        };
    }

    private static String statusEffect(StatBoard.Status status) {
        return switch (status) {
            case DRAFT -> "Il n'est ni calculé ni publié.";
            case ENABLED -> "Il est calculé, figé à la fin de chaque période et publié s'il est visible "
                    + "(seulement quand l'export web est activé).";
            case PAUSED -> "Il n'est plus calculé ni publié ; ses réglages et ses résultats déjà figés sont conservés.";
            case ARCHIVED -> "Il est retiré du site ; ses résultats déjà figés restent en base.";
        };
    }

    private static String summary(List<String> errors) {
        List<String> shown = errors.subList(0, Math.min(3, errors.size()));
        return String.join(" ; ", shown) + (errors.size() > 3 ? " (+" + (errors.size() - 3) + ")" : "");
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max).trim();
    }

    private static void writeAtomically(Path file, byte[] bytes) throws IOException {
        Path temp = temp(file);
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Fichier complet d'abord, puis lien physique : échoue sans rien écraser si le nom est déjà pris. */
    private static void createAtomically(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = temp(file);
        try {
            Files.write(temp, bytes);
            try {
                Files.createLink(file, temp);
            } catch (UnsupportedOperationException e) {
                Files.move(temp, file);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Path temp(Path file) {
        return file.resolveSibling("." + file.getFileName() + ".tmp-" + UUID.randomUUID());
    }

    /** Statut lu d'un paramètre ({@code enabled}, {@code paused}…), insensible à la casse. */
    public static java.util.Optional<StatBoard.Status> parseStatus(String text) {
        return text == null ? java.util.Optional.empty() : StatBoard.Status.parse(text.trim().toLowerCase(Locale.ROOT));
    }
}
