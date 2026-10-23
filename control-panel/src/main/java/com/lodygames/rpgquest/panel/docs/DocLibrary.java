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

    /** Fiche précédente dans l'ordre d'affichage (catégorie, ordre, titre), vide pour la première. */
    public Optional<DocPage> previous(DocPage page) {
        int at = ordered.indexOf(page);
        return at > 0 ? Optional.of(ordered.get(at - 1)) : Optional.empty();
    }

    /** Fiche suivante dans l'ordre d'affichage, vide pour la dernière. */
    public Optional<DocPage> next(DocPage page) {
        int at = ordered.indexOf(page);
        return at >= 0 && at + 1 < ordered.size() ? Optional.of(ordered.get(at + 1)) : Optional.empty();
    }

    /**
     * Fiches liées : celles déclarées dans {@code related} (ordre conservé, identifiants inconnus
     * ignorés), puis les autres fiches qui citent celle-ci dans leur {@code related}.
     */
    public List<DocPage> related(DocPage page) {
        Map<String, DocPage> out = new LinkedHashMap<>();
        for (String slug : page.related()) {
            bySlug(slug).filter(p -> !p.slug().equals(page.slug())).ifPresent(p -> out.put(p.slug(), p));
        }
        for (DocPage other : ordered) {
            if (!other.slug().equals(page.slug()) && other.related().contains(page.slug())) {
                out.putIfAbsent(other.slug(), other);
            }
        }
        return List.copyOf(out.values());
    }

    /**
     * Vérifie un lien du centre d'aide ({@code /docs/fiche}, {@code /docs/fiche#section},
     * {@code #section} relatif à {@code from}, ou {@code /docs?q=…}). Renvoie la raison s'il est
     * cassé, {@code null} s'il mène bien à une fiche (et à une section réellement rendue).
     * Les liens externes ({@code https://…}) ne sont pas vérifiés.
     */
    public String brokenLinkReason(DocPage from, String href) {
        if (href == null || href.startsWith("https://") || href.equals("/docs") || href.startsWith("/docs?")) {
            return null;
        }
        String target;
        String anchor;
        if (href.startsWith("#")) {
            target = from == null ? null : from.slug();
            anchor = href.substring(1);
        } else if (href.startsWith("/docs/")) {
            String rest = href.substring("/docs/".length());
            int hash = rest.indexOf('#');
            target = hash < 0 ? rest : rest.substring(0, hash);
            anchor = hash < 0 ? "" : rest.substring(hash + 1);
        } else {
            return "lien hors du centre d'aide";
        }
        Optional<DocPage> page = bySlug(target);
        if (page.isEmpty()) {
            return "fiche inconnue « " + target + " »";
        }
        if (!anchor.isEmpty() && Markdown.headings(page.get().markdown()).stream()
                .noneMatch(h -> h.id().equals(anchor))) {
            return "section « " + anchor + " » absente de la fiche " + target;
        }
        return null;
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
                body, "control-panel/src/main/resources" + ROOT + fileName, commands, parsed.meta().related());
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
