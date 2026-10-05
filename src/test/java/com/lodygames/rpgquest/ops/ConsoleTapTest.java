package com.lodygames.rpgquest.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

/**
 * Issue #95 — ce test répond à la question qui a décidé la conception : <strong>la capture voit-elle
 * réellement les lignes des autres plugins ?</strong>
 *
 * <p>Un {@code Handler} {@code java.util.logging} posé sur {@code Bukkit.getLogger()} ne verrait que
 * les plugins journalisant via JUL — or Paper route {@code getSLF4JLogger()} directement vers
 * Log4j2, donc la quasi-totalité des lignes RPGQuest lui échapperait. Les tests ci-dessous
 * journalisent sous des noms de logger tiers ({@code Citizens}, {@code WorldEdit}) et vérifient
 * qu'ils atterrissent dans le tampon.</p>
 */
class ConsoleTapTest {

    private ConsoleTap tap;

    /**
     * Aligne la JVM de test sur la réalité d'un serveur Paper, dont le logger racine est à
     * {@code INFO}. Sans configuration, Log4j démarre à {@code ERROR} : les lignes {@code INFO}
     * seraient filtrées <strong>avant</strong> tout appender, et ces tests mesureraient la
     * configuration par défaut de Log4j plutôt que notre capture.
     */
    @BeforeEach
    void setUp() {
        Configurator.setRootLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        if (tap != null) {
            tap.uninstall();
            tap = null;
        }
        Configurator.setRootLevel(Level.ERROR);
    }

    private ServerLogBuffer install() {
        ServerLogBuffer buffer = new ServerLogBuffer(200);
        tap = new ConsoleTap(buffer);
        assertEquals(Optional.empty(), tap.install(NOPLogger.NOP_LOGGER),
                "Log4j est sur le classpath de test : l'installation doit réussir");
        return buffer;
    }

    @Test
    void linesFromAnyLoggerAreCaptured() {
        ServerLogBuffer buffer = install();

        LogManager.getLogger("Citizens").info("PNJ 42 chargé");
        LogManager.getLogger("WorldEdit").warn("Région trop grande");
        LogManager.getLogger("RPGQuest").error("Quête introuvable");

        List<ServerLogBuffer.Line> lines = buffer.tail(0, 50).lines();
        assertEquals(3, lines.size(), () -> "lignes captées : " + lines);
        assertEquals(List.of("Citizens", "WorldEdit", "RPGQuest"),
                lines.stream().map(ServerLogBuffer.Line::source).toList());
        assertEquals(List.of("INFO", "WARN", "ERROR"),
                lines.stream().map(ServerLogBuffer.Line::level).toList());
        assertEquals("PNJ 42 chargé", lines.get(0).message());
    }

    @Test
    void parameterizedMessagesAreFormatted() {
        ServerLogBuffer buffer = install();

        LogManager.getLogger("RPGQuest").info("joueur {} dans {}", "LoDyMcFly", "world_hub");

        // Stocker « joueur {} dans {} » rendrait la console illisible : le message doit être formaté.
        assertEquals("joueur LoDyMcFly dans world_hub", buffer.tail(0, 5).lines().get(0).message());
    }

    @Test
    void aThrowableIsSummarizedWithoutItsStackTrace() {
        ServerLogBuffer buffer = install();

        LogManager.getLogger("RPGQuest").error("échec SQL", new IllegalStateException("boom"));

        String message = buffer.tail(0, 5).lines().get(0).message();
        assertTrue(message.startsWith("échec SQL"), () -> message);
        assertTrue(message.contains("IllegalStateException"), () -> message);
        // Une trace complète ferait exploser le tampon et pourrait révéler des chemins internes.
        assertFalse(message.contains("\tat "), () -> message);
    }

    @Test
    void debugLinesAreNotCapturedAtInfoThreshold() {
        ServerLogBuffer buffer = install();

        LogManager.getLogger("Bruyant").debug("détail interne");
        LogManager.getLogger("Bruyant").info("visible");

        List<ServerLogBuffer.Line> lines = buffer.tail(0, 50).lines();
        assertEquals(1, lines.size(), () -> "le DEBUG de tous les plugins noierait le tampon : " + lines);
        assertEquals("visible", lines.get(0).message());
    }

    @Test
    void installingTwiceIsIdempotentAndDoesNotDuplicateLines() {
        ServerLogBuffer buffer = install();
        assertEquals(Optional.empty(), tap.install(NOPLogger.NOP_LOGGER));

        LogManager.getLogger("RPGQuest").info("une seule fois");

        assertEquals(1, buffer.tail(0, 50).lines().size(), "jamais deux appenders pour une ligne");
    }

    @Test
    void uninstallingStopsTheCaptureAndIsItselfIdempotent() {
        ServerLogBuffer buffer = install();
        LogManager.getLogger("RPGQuest").info("avant");

        tap.uninstall();
        tap.uninstall(); // un second appel ne doit rien casser (arrêt du plugin, /reload…)
        LogManager.getLogger("RPGQuest").info("après");

        List<ServerLogBuffer.Line> lines = buffer.tail(0, 50).lines();
        assertEquals(1, lines.size(), () -> "rien ne doit être capté après le détachement : " + lines);
        assertEquals("avant", lines.get(0).message());
    }

    @Test
    void aRestrictiveServerLogLevelIsReportedInsteadOfLookingLikeAnEmptyConsole() {
        Configurator.setRootLevel(Level.ERROR);
        ServerLogBuffer buffer = new ServerLogBuffer(50);
        tap = new ConsoleTap(buffer);

        Optional<String> limitation = tap.install(NOPLogger.NOP_LOGGER);

        // La capture marche, mais le serveur ne produit rien d'INFO : une console vide sans
        // explication ferait chercher une panne inexistante.
        assertTrue(limitation.isPresent(), "le niveau restrictif doit être signalé");
        assertTrue(limitation.get().contains("ERROR"), limitation.get());
        LogManager.getLogger("Silencieux").info("filtré par le serveur, pas par nous");
        assertTrue(buffer.tail(0, 10).lines().isEmpty());
    }

    @Test
    void theBufferStaysBoundedUnderAFloodOfLines() {
        ServerLogBuffer buffer = new ServerLogBuffer(ServerLogBuffer.MIN_CAPACITY);
        tap = new ConsoleTap(buffer);
        tap.install(NOPLogger.NOP_LOGGER);

        for (int i = 0; i < ServerLogBuffer.MIN_CAPACITY * 3; i++) {
            LogManager.getLogger("Flood").info("ligne {}", i);
        }

        ServerLogBuffer.Snapshot snapshot = buffer.tail(0, ServerLogBuffer.MAX_TAIL);
        assertEquals(ServerLogBuffer.MIN_CAPACITY, snapshot.lines().size());
        assertTrue(snapshot.dropped() > 0, "les lignes évincées doivent être comptées");
    }
}
