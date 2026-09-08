package com.lodygames.rpgquest.panel.docs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bibliothèque de fiches du centre de documentation (issue #49). Charge un ensemble
 * <strong>fermé</strong> de fichiers Markdown livrés avec le Control Panel
 * ({@code src/main/resources/docs/}), listés dans le manifeste {@code docs/_index.txt} — ce
 * manifeste <strong>est</strong> la liste blanche : aucun autre fichier n'est jamais exposé, aucun
 * chemin du navigateur n'est jamais ouvert. Les fiches sont indexées en mémoire au démarrage ;
 * Git reste la source de vérité (fichiers versionnés, jamais réécrits ni copiés en base).
 *
 * <p>Purement lecture de ressources + parsing (testable sans serveur HTTP).</p>
 */
public final class DocLibrary {

    /** Racine des ressources documentaires — jamais concaténée à une entrée du navigateur. */
    static final String ROOT = "/docs/";
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern MD_HEADING = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");
    private static final Pattern COMMANDISH =
            Pattern.compile("^\\s*(/[a-z][\\w-]*|\\./gradlew|scripts/[\\w./-]+|git\\s|sudo\\s|systemctl\\s|curl\\s|mvn\\s).*");

    private final Map<String, DocPage> bySlug = new LinkedHashMap<>();
    private final List<DocPage> ordered = new ArrayList<>();

    private DocLibrary() {
    }

    /** Charge la bibliothèque depuis les ressources du classpath. Idempotent — renvoie une instance prête. */
    public static DocLibrary load() {
        DocLibrary lib = new DocLibrary();
        for (String name : lib.manifest()) {
            DocPage page = lib.readPage(name);
            if (page != null && !lib.bySlug.containsKey(page.slug())) {
                lib.bySlug.put(page.slug(), page);
                lib.ordered.add(page);
            }
        }
        lib.ordered.sort(Comparator
                .comparing((DocPage p) -> p.category().toLowerCase(Locale.ROOT))
                .thenComparingInt(DocPage::order)
                .thenComparing(p -> p.title().toLowerCase(Locale.ROOT)));
        return lib;
    }

    /** Toutes les fiches, triées (catégorie, ordre, titre). */
    public List<DocPage> all() {
        return List.copyOf(ordered);
    }

    /** Résout un slug interne. Vide si inconnu — jamais d'accès disque, jamais d'exception. */
    public Optional<DocPage> bySlug(String slug) {
        if (slug == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(bySlug.get(slug.trim().toLowerCase(Locale.ROOT)));
    }

    /** Fiches groupées par catégorie, dans l'ordre d'affichage. */
    public Map<String, List<DocPage>> byCategory() {
        Map<String, List<DocPage>> map = new LinkedHashMap<>();
        for (DocPage p : ordered) {
            map.computeIfAbsent(p.category(), k -> new ArrayList<>()).add(p);
        }
        return map;
    }

    public boolean isEmpty() {
        return ordered.isEmpty();
    }

    // ---- chargement ----------------------------------------------------------------------

    private List<String> manifest() {
        List<String> names = new ArrayList<>();
        try (InputStream in = DocLibrary.class.getResourceAsStream(ROOT + "_index.txt")) {
            if (in == null) {
                return names;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String name = line.trim();
                    if (name.isEmpty() || name.startsWith("#")) {
                        continue;
                    }
                    // Garde-fou : uniquement un nom de fichier .md « plat », jamais de chemin.
                    if (name.endsWith(".md") && !name.contains("/") && !name.contains("\\") && !name.contains("..")) {
                        names.add(name);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture du manifeste de documentation impossible", e);
        }
        return names;
    }

    private DocPage readPage(String fileName) {
        String raw;
        try (InputStream in = DocLibrary.class.getResourceAsStream(ROOT + fileName)) {
            if (in == null) {
                return null;
            }
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture de la fiche « " + fileName + " » impossible", e);
        }

        String baseSlug = fileName.substring(0, fileName.length() - 3).toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(baseSlug).matches()) {
            return null; // nom de fichier hors convention : ignoré silencieusement
        }

        DocFrontMatter.Parsed parsed = DocFrontMatter.parse(raw);
        String body = parsed.body();

        String title = parsed.meta().title();
        if (title == null || title.isBlank()) {
            Matcher m = MD_HEADING.matcher(body);
            title = m.find() ? m.group(1).trim() : humanize(baseSlug);
        }
        String category = parsed.meta().category();
        if (category == null || category.isBlank()) {
            category = "Divers";
        }
        List<String> commands = extractCommands(body);
        return new DocPage(baseSlug, title, category, parsed.meta().tags(), parsed.meta().order(),
                body, "control-panel/src/main/resources" + ROOT + fileName, commands);
    }

    /** Commandes repérées : lignes des blocs {@code ```} + code inline ressemblant à une commande. */
    static List<String> extractCommands(String markdown) {
        List<String> commands = new ArrayList<>();
        String[] lines = markdown.split("\n", -1);
        boolean inFence = false;
        for (String line : lines) {
            String s = line.strip();
            if (s.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                if (!s.isEmpty() && !s.startsWith("#")) {
                    commands.add(s);
                }
            }
        }
        Matcher m = Pattern.compile("`([^`]+)`").matcher(markdown.replace("\n", " "));
        while (m.find()) {
            String c = m.group(1).strip();
            if (COMMANDISH.matcher(c).matches()) {
                commands.add(c);
            }
        }
        return commands;
    }

    private static String humanize(String slug) {
        String s = slug.replace('-', ' ').replace('_', ' ').trim();
        return s.isEmpty() ? slug : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
