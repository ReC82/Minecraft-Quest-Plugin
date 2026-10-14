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
