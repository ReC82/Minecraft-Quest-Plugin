package com.lodygames.rpgquest.discord.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.discord.github.Issue;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Correspondance GitHub → statut (issue #202). GitHub fait autorité ; la table est documentée
 * dans {@link SyncStatus} et dans {@code docs/discord-sync/README.md}.
 */
class SyncStatusTest {

    private static Issue issue(String state, String reason, String... labels) {
        return new Issue(1, "t", "b", state, reason, List.of(labels), "https://x/1", "now");
    }

    @Test
    @DisplayName("Une issue ouverte sans étiquette de statut est « À trier »")
    void openDefaultsToTriage() {
        assertEquals(SyncStatus.TRIAGE, SyncStatus.of(issue("open", null)));
        assertEquals(SyncStatus.TRIAGE, SyncStatus.of(issue("open", null, "triage", "type:bug")));
    }

    @Test
    @DisplayName("Les étiquettes de statut sont reconnues, la plus avancée gagne")
    void openLabelsMapToProgress() {
        assertEquals(SyncStatus.IN_PROGRESS,
                SyncStatus.of(issue("open", null, SyncStatus.LABEL_IN_PROGRESS)));
        assertEquals(SyncStatus.NEEDS_TESTING,
                SyncStatus.of(issue("open", null, SyncStatus.LABEL_NEEDS_TESTING)));
        assertEquals(SyncStatus.NEEDS_TESTING, SyncStatus.of(issue("open", null,
                        SyncStatus.LABEL_IN_PROGRESS, SyncStatus.LABEL_NEEDS_TESTING)),
                "si les deux coexistent, c'est l'étape la plus avancée qui est annoncée");
    }

    @Test
    @DisplayName("« Fermée parce que faite » et « fermée parce qu'on ne la fera pas » sont distinctes")
    void closureIsHonest() {
        assertEquals(SyncStatus.RESOLVED, SyncStatus.of(issue("closed", "completed")));
        assertEquals(SyncStatus.DECLINED, SyncStatus.of(issue("closed", "not_planned")));
        assertEquals(SyncStatus.DUPLICATE, SyncStatus.of(issue("closed", "duplicate")));
        assertEquals(SyncStatus.CLOSED, SyncStatus.of(issue("closed", null)));

        assertNotEquals(SyncStatus.RESOLVED.label(), SyncStatus.DECLINED.label(),
                "confondre les deux ferait croire à un membre que sa demande est réglée");
    }

    @Test
    @DisplayName("La fermeture l'emporte sur une étiquette de progression restée posée")
    void closedBeatsLabels() {
        assertEquals(SyncStatus.RESOLVED,
                SyncStatus.of(issue("closed", "completed", SyncStatus.LABEL_IN_PROGRESS)));
    }

    @Test
    @DisplayName("« Résolu » dit explicitement que ce n'est pas forcément déployé")
    void resolvedDoesNotPromiseDeployment() {
        assertTrue(SyncStatus.RESOLVED.explanation().contains("en ligne"),
                SyncStatus.RESOLVED.explanation());
    }

    @Test
    @DisplayName("Chaque statut a un libellé et une explication non vides")
    void everyStatusIsExplained() {
        for (SyncStatus status : SyncStatus.values()) {
            assertTrue(status.label() != null && !status.label().isBlank(), status.name());
            assertTrue(status.explanation() != null && !status.explanation().isBlank(), status.name());
        }
    }
}
