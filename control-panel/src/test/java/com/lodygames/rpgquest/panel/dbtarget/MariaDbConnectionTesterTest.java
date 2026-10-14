package com.lodygames.rpgquest.panel.dbtarget;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Testeur de connexion réel (issue #42). */
class MariaDbConnectionTesterTest {

    @Test
    void unreachableTargetFailsQuicklyWithoutLeakingSecrets() {
        DbTargetSettings settings = new DbTargetSettings("127.0.0.1", 1, "rpgquest", "compte_test", "disable",
                true, false, "", "", "", false, "");
        long start = System.nanoTime();

        DbConnectionTester.Result result = new MariaDbConnectionTester(1_000, 2_000).test(settings, "hunter2-secret");

        assertFalse(result.ok());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000, "délai borné");
        assertFalse(result.summary().contains("hunter2-secret"));
        assertFalse(result.summary().contains("127.0.0.1"), result.summary());
        assertFalse(result.summary().contains("compte_test"), result.summary());
    }

    /**
     * Issue #43, contre un vrai MariaDB (optionnel) : le compte du panel ({@code RPGQUEST_PANEL_DB_*},
     * sans droit sur RPGQuest) passe le contrôle d'isolement ; le compte du plugin
     * ({@code RPGQUEST_DB_*}, dont la base contient {@code rpgquest_schema_migrations}) y échoue.
     */
    @Test
    void panelIsolationIsCheckedOnRealServer() {
        String panelHost = System.getenv("RPGQUEST_PANEL_DB_HOST");
        String pluginHost = System.getenv("RPGQUEST_DB_HOST");
        Assumptions.assumeTrue(panelHost != null && !panelHost.isBlank() && pluginHost != null
                && !pluginHost.isBlank(), "RPGQUEST_PANEL_DB_* / RPGQUEST_DB_* non définies");
        DbTargetSettings panel = new DbTargetSettings(panelHost, Integer.parseInt(System.getenv("RPGQUEST_PANEL_DB_PORT")),
                System.getenv("RPGQUEST_PANEL_DB_NAME"), System.getenv("RPGQUEST_PANEL_DB_USER"), "disable",
                true, false, "", "", "", false, "");
        DbTargetSettings plugin = new DbTargetSettings(pluginHost, Integer.parseInt(System.getenv("RPGQUEST_DB_PORT")),
                System.getenv("RPGQUEST_DB_NAME"), System.getenv("RPGQUEST_DB_USER"), "disable",
                true, false, "", "", "", false, "");

        DbConnectionTester.Result isolated = MariaDbConnectionTester.forPanel()
                .test(panel, System.getenv("RPGQUEST_PANEL_DB_PASSWORD"));
        DbConnectionTester.Result shared = MariaDbConnectionTester.forPanel()
                .test(plugin, System.getenv("RPGQUEST_DB_PASSWORD"));

        assertTrue(isolated.ok(), isolated.summary());
        assertTrue(isolated.summary().contains("isolement vérifié"), isolated.summary());
        assertFalse(shared.ok(), shared.summary());
        assertTrue(shared.summary().contains("Isolement non respecté"), shared.summary());
    }

    /** Contre un vrai MariaDB (optionnel, variables {@code RPGQUEST_DB_*}) : connexion réussie, sans fuite. */
    @Test
    void realServerIsReachedReadOnly() {
        String host = System.getenv("RPGQUEST_DB_HOST");
        Assumptions.assumeTrue(host != null && !host.isBlank(), "RPGQUEST_DB_* non définies");
        DbTargetSettings settings = new DbTargetSettings(host, Integer.parseInt(System.getenv("RPGQUEST_DB_PORT")),
                System.getenv("RPGQUEST_DB_NAME"), System.getenv("RPGQUEST_DB_USER"), "disable",
                true, false, "", "", "", false, "");
        String password = System.getenv("RPGQUEST_DB_PASSWORD");

        DbConnectionTester.Result result = new MariaDbConnectionTester().test(settings, password);

        assertTrue(result.ok(), result.summary());
        assertTrue(result.summary().contains("lecture seule"));
        assertFalse(result.summary().contains(password));
    }
}
