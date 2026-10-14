package com.lodygames.rpgquest.quest.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CooldownTextTest {

    @Test
    void formatsDaysHoursAndMinutesRoundedUp() {
        assertEquals("1 min", CooldownText.format(Duration.ofSeconds(5)));
        assertEquals("1 min", CooldownText.format(Duration.ZERO));
        assertEquals("2 min", CooldownText.format(Duration.ofSeconds(61)));
        assertEquals("3 h 20 min", CooldownText.format(Duration.ofMinutes(200)));
        assertEquals("20 h", CooldownText.format(Duration.ofHours(20)));
        assertEquals("1 j 4 h", CooldownText.format(Duration.ofHours(28)));
        assertEquals("2 j", CooldownText.format(Duration.ofDays(2)));
    }
}
