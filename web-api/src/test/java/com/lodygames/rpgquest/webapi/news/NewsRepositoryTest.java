package com.lodygames.rpgquest.webapi.news;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.webapi.account.MutableClock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Dossier des actualités (#56) : brouillons et dates futures invisibles, fichier invalide isolé, cache. */
class NewsRepositoryTest {

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-20T12:00:00Z"));
    private final List<String> warnings = new ArrayList<>();
    private final Logger logger = Logger.getLogger("news-test-" + System.nanoTime());

    NewsRepositoryTest() {
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    private NewsRepository repository(Duration refresh) {
        return new NewsRepository(dir, refresh, clock, logger);
    }

    private void write(String slug, String date, String status) throws Exception {
        Files.writeString(dir.resolve(slug + ".md"), "title: " + slug + "\nsummary: Résumé\ndate: " + date
                + "\nstatus: " + status + "\ncategory: news\n---\nTexte de " + slug + "\n", StandardCharsets.UTF_8);
    }

    @Test
    void onlyPublishedArticlesWhoseDateIsReachedAreVisibleNewestFirst() throws Exception {
        write("ancienne", "2026-10-01T10:00:00Z", "published");
        write("recente", "2026-10-19T10:00:00+02:00", "published");
        write("brouillon", "2026-10-02T10:00:00Z", "draft");
        write("programmee", "2026-10-25T10:00:00Z", "published");
        NewsRepository news = repository(Duration.ZERO);

        assertEquals(List.of("recente", "ancienne"), news.published().stream().map(NewsArticle::slug).toList());
        assertTrue(news.find("brouillon").isEmpty(), "un brouillon répond comme un slug inconnu");
        assertTrue(news.find("programmee").isEmpty());
        assertEquals(4, news.loadedCount());

        clock.advance(Duration.ofDays(6));
        assertTrue(news.find("programmee").isPresent(), "visible dès sa date atteinte");
    }

    @Test
    void anInvalidFileIsSkippedAndReportedOnceWithoutItsContent() throws Exception {
        write("bonne", "2026-10-01T10:00:00Z", "published");
        Files.writeString(dir.resolve("cassee.md"), "title: Secret interne\n---\nmot-de-passe\n");
        Files.writeString(dir.resolve("ignoree.txt"), "pas une actualité");
        NewsRepository news = repository(Duration.ZERO);

        assertEquals(List.of("bonne"), news.published().stream().map(NewsArticle::slug).toList());
        news.published();
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("cassee.md"), warnings.toString());
        assertTrue(!warnings.get(0).contains("mot-de-passe") && !warnings.get(0).contains("Secret"), warnings.toString());
    }

    @Test
    void aMissingDirectoryMeansNoNews() {
        NewsRepository news = new NewsRepository(dir.resolve("absent"), Duration.ZERO, clock, logger);

        assertTrue(news.published().isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void theDirectoryIsRescannedOnlyAfterTheRefreshInterval() throws Exception {
        NewsRepository news = repository(Duration.ofSeconds(30));
        assertTrue(news.published().isEmpty());

        write("nouvelle", "2026-10-01T10:00:00Z", "published");
        assertTrue(news.published().isEmpty(), "pas de relecture du disque à chaque requête");

        clock.advance(Duration.ofSeconds(30));
        assertEquals(1, news.published().size());
    }
}
