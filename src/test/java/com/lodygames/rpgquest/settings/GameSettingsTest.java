package com.lodygames.rpgquest.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.economy.CurrencyName;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Issue #335 — paramètres généraux : validation, écriture atomique relue, fichier illisible. */
class GameSettingsTest {

    @TempDir
    Path dir;

    @Test
    void theDefaultsAreLodyCoinsAndTheBetaWarningOn() {
        assertEquals("LodyCoins", GameSettings.DEFAULTS.currencyName());
        assertTrue(GameSettings.DEFAULTS.betaWarningEnabled());
    }

    @Test
    void oneInvalidValueChangesNothing() {
        GameSettings.Change c = GameSettings.DEFAULTS.with(Map.of("currency_name", "Écus", "beta_warning_enabled", "peut-être"));
        assertFalse(c.ok());
        assertEquals(GameSettings.DEFAULTS, c.settings(), "tout ou rien");
        assertFalse(GameSettings.DEFAULTS.with(Map.of("currency_name", "<red>X")).ok(), "pas de balise");
        assertFalse(GameSettings.DEFAULTS.with(Map.of("beta_warning_text", "<b>Bêta</b>")).ok());
        assertFalse(GameSettings.DEFAULTS.with(Map.of("bypass", "true")).ok(), "aucune clé inconnue");
    }

    @Test
    void theAdminBypassModeIsOfferedButLimitedToTheSessionByDefault() throws Exception {
        assertTrue(GameSettings.DEFAULTS.adminBypassAvailable());
        assertEquals(0, GameSettings.DEFAULTS.adminBypassMaxMinutes(), "0 = jusqu'à la déconnexion");
        assertFalse(GameSettings.DEFAULTS.with(Map.of("admin_bypass_max_minutes", "481")).ok());
        assertFalse(GameSettings.DEFAULTS.with(Map.of("admin_bypass_max_minutes", "-1")).ok());
        assertFalse(GameSettings.DEFAULTS.with(Map.of("admin_bypass_available", "oui")).ok());

        GameSettingsService service = new GameSettingsService(dir, LoggerFactory.getLogger("t"));
        service.start();
        assertTrue(service.update(Map.of("admin_bypass_available", "false", "admin_bypass_max_minutes", "45"),
                "owner").ok());
        GameSettingsService again = new GameSettingsService(dir, LoggerFactory.getLogger("t"));
        again.start();
        assertFalse(again.current().adminBypassAvailable());
        assertEquals(45, again.current().adminBypassMaxMinutes());
        assertTrue(Files.readString(dir.resolve("settings.yml")).contains("max-minutes: 45"));
    }

    @Test
    void savingWritesThenRereadsAndRecordsTheAuthor() throws Exception {
        GameSettingsService service = new GameSettingsService(dir, LoggerFactory.getLogger("t"));
        service.start();
        GameSettingsService.Saved saved = service.update(Map.of("currency_name", "Écus d'or",
                "beta_warning_enabled", "false"), "owner");
        assertTrue(saved.ok(), saved.message());
        assertEquals("Écus d'or", saved.settings().currencyName());
        assertFalse(saved.settings().betaWarningEnabled());
        assertEquals("owner", saved.updatedBy());
        assertEquals("Écus d'or", CurrencyName.get(), "l'affichage suit");
        assertEquals("12 Écus d'or", CurrencyName.amount(12));

        GameSettingsService again = new GameSettingsService(dir, LoggerFactory.getLogger("t"));
        again.start();
        assertEquals(saved.settings(), again.current(), "survit au redémarrage");
        assertFalse(Files.exists(dir.resolve("settings.yml.tmp")), "aucun fichier temporaire laissé");
        CurrencyName.set("LodyCoins");
    }

    @Test
    void anUnreadableFileIsNeverOverwrittenSilently() throws Exception {
        Files.writeString(dir.resolve("settings.yml"), "economy: [ : : ");
        GameSettingsService service = new GameSettingsService(dir, LoggerFactory.getLogger("t"));
        service.start();
        assertEquals(GameSettings.DEFAULTS, service.current());
        assertFalse(service.loadProblem().isEmpty());
        assertFalse(service.update(Map.of("currency_name", "Écus"), "owner").ok());
        assertEquals("economy: [ : : ", Files.readString(dir.resolve("settings.yml")), "fichier intact");
    }

    @Test
    void anInvalidValueOnDiskKeepsTheValidOnes() throws Exception {
        Files.writeString(dir.resolve("settings.yml"),
                "economy:\n  currency-name: \"<red>X\"\nlogin:\n  beta-warning:\n    enabled: false\n");
        GameSettingsService service = new GameSettingsService(dir, LoggerFactory.getLogger("t"));
        service.start();
        assertEquals("LodyCoins", service.current().currencyName());
        assertFalse(service.current().betaWarningEnabled(), "la valeur valide est gardée");
        assertFalse(service.loadProblem().isEmpty());
    }
}
