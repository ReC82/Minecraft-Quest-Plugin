package com.lodygames.rpgquest.mob.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Issue #176 — la distance de participation est persistée avec les autres réglages des boss communautaires. */
class CommunityBossSettingsStoreTest {

    @TempDir
    Path mobs;

    private CommunityBossSettingsStore store() {
        return new CommunityBossSettingsStore(mobs, LoggerFactory.getLogger(CommunityBossSettingsStoreTest.class));
    }

    @Test
    void theParticipationRadiusIsSavedAndReadBack() throws Exception {
        store().save(new CommunityBossSettings(true, 20, 2, 64));

        CommunityBossSettings reread = store().current();
        assertEquals(new CommunityBossSettings(true, 20, 2, 64), reread);
        assertTrue(Files.readString(mobs.resolve("community-bosses.yml")).contains("participation-radius: 64"));
    }

    @Test
    void aFileWithoutRadiusGetsTheDefaultAndAnOutOfBoundsRadiusDoesNotPauseTheScheduler() throws Exception {
        Files.writeString(mobs.resolve("community-bosses.yml"), "enabled: true\ninterval-minutes: 30\nmax-active: 2\n");
        assertEquals(new CommunityBossSettings(true, 30, 2, 100), store().current());

        Files.writeString(mobs.resolve("community-bosses.yml"),
                "enabled: true\ninterval-minutes: 30\nmax-active: 2\nparticipation-radius: 9999\n");
        assertEquals(new CommunityBossSettings(true, 30, 2, 100), store().current());
    }
}
