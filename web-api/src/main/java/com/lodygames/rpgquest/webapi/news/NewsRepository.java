package com.lodygames.rpgquest.webapi.news;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Actualités du portail (#56), une par fichier {@code <slug>.md} du dossier {@code news-dir}.
 *
 * <p>Indépendant de l'instantané Minecraft : un serveur de jeu arrêté ne fait pas disparaître les
 * actualités. Le dossier est relu au plus une fois par {@code refreshInterval}, jamais à chaque requête ;
 * un fichier invalide est ignoré (avertissement dans le journal, avec son nom et la raison, jamais son
 * contenu) sans empêcher les autres de s'afficher. Le portail ne fait que lire ce dossier.</p>
 */
public final class NewsRepository {

    static final int MAX_FILES = 500;
    static final long MAX_FILE_BYTES = 64 * 1024;
    private static final String EXTENSION = ".md";

    private final Path directory;
    private final Duration refreshInterval;
    private final Clock clock;
    private final Logger logger;
    private final Map<Path, String> reportedErrors = new HashMap<>();

    private List<NewsArticle> articles = List.of();
    private Instant lastScan;

    public NewsRepository(Path directory, Duration refreshInterval, Clock clock, Logger logger) {
        this.directory = directory;
        this.refreshInterval = refreshInterval;
        this.clock = clock;
        this.logger = logger;
    }

    /** Actualités visibles maintenant, la plus récente d'abord ; les brouillons et les dates futures sont exclus. */
    public List<NewsArticle> published() {
        Instant now = clock.instant();
        return all().stream().filter(article -> article.visibleAt(now)).toList();
    }

    /** Actualités visibles d'une catégorie, la plus récente d'abord. */
    public List<NewsArticle> published(NewsCategory category) {
        return published().stream().filter(article -> article.category() == category).toList();
    }

    /** Une actualité visible ; un brouillon ou un slug inconnu donnent la même absence. */
    public Optional<NewsArticle> find(String slug) {
        return published().stream().filter(article -> article.slug().equals(slug)).findFirst();
    }

    /** Nombre de fichiers valides chargés (brouillons compris), pour {@code /health}. */
    public int loadedCount() {
        return all().size();
    }

    private synchronized List<NewsArticle> all() {
        Instant now = clock.instant();
        if (lastScan == null || !now.isBefore(lastScan.plus(refreshInterval))) {
            articles = scan();
            lastScan = now;
        }
        return articles;
    }

    private List<NewsArticle> scan() {
        List<NewsArticle> loaded = new ArrayList<>();
        Map<Path, String> errors = new HashMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + EXTENSION)) {
            for (Path file : files) {
                if (loaded.size() + errors.size() >= MAX_FILES) {
                    errors.put(directory, "plus de " + MAX_FILES + " fichiers, les suivants sont ignorés");
                    break;
                }
                try {
                    loaded.add(read(file));
                } catch (NewsFormatException e) {
                    errors.put(file, e.getMessage());
                }
            }
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Dossier des actualités illisible : " + directory, e);
            return articles;
        }
        report(errors);
        loaded.sort(Comparator.comparing(NewsArticle::publishedAt).reversed().thenComparing(NewsArticle::slug));
        return List.copyOf(loaded);
    }

    private static NewsArticle read(Path file) throws NewsFormatException {
        String name = file.getFileName().toString();
        String slug = name.substring(0, name.length() - EXTENSION.length());
        try {
            if (!Files.isRegularFile(file)) {
                throw new NewsFormatException("n'est pas un fichier ordinaire");
            }
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw new NewsFormatException("fichier trop gros (" + MAX_FILE_BYTES / 1024 + " Ko au plus)");
            }
            return NewsParser.parse(slug, Files.readString(file, StandardCharsets.UTF_8));
        } catch (CharacterCodingException e) {
            throw new NewsFormatException("encodage invalide : enregistrer le fichier en UTF-8");
        } catch (IOException e) {
            throw new NewsFormatException("lecture impossible (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Une erreur n'est journalisée qu'à son apparition ou à son changement, pas à chaque relecture. */
    private void report(Map<Path, String> errors) {
        for (Map.Entry<Path, String> error : errors.entrySet()) {
            if (!error.getValue().equals(reportedErrors.get(error.getKey()))) {
                logger.warning("Actualité ignorée " + error.getKey().getFileName() + " : " + error.getValue());
            }
        }
        reportedErrors.clear();
        reportedErrors.putAll(errors);
    }
}
