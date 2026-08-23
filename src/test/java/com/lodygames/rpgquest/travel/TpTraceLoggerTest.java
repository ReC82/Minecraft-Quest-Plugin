package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

/**
 * TODO(debug bug TP hub) : {@link TpTraceLogger} est de l'instrumentation temporaire — ce test
 * garantit uniquement qu'aucun appelant (tous passent librement {@code null} pour les champs non
 * pertinents à un événement donné, ex. {@code inside} pour {@code channel_start}) ne provoque de
 * {@code NullPointerException} au formatage, jamais le contenu exact d'une ligne de log.
 */
class TpTraceLoggerTest {

    @Test
    void logNeverThrowsEvenWhenEveryOptionalFieldIsNull() {
        assertDoesNotThrow(() -> TpTraceLogger.log(NOPLogger.NOP_LOGGER, "player_join", UUID.randomUUID(), "Steve",
                null, "world", 0, 64, 0, null, null, null, null, null, null));
    }

    @Test
    void logNeverThrowsWithEveryFieldPopulated() {
        assertDoesNotThrow(() -> TpTraceLogger.log(NOPLogger.NOP_LOGGER, "portal_enter", UUID.randomUUID(), "Steve",
                "hub_to_wild", "world", 0, 64, 0, true, false, 40L, "3s", "world:1,2,3", "wild:4,5,6"));
    }
}
