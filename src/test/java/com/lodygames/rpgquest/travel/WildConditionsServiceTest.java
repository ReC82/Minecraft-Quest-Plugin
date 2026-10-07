package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.travel.model.WildConditions;
import com.lodygames.rpgquest.travel.model.WildWeather;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Issue #24 : l'état rapporté au joueur vient du monde d'exploration <strong>réel</strong>
 * (jour/nuit + météo globale), et le lire ne modifie jamais ce monde.
 */
class WildConditionsServiceTest {

    private ServerMock server;
    private WorldMock wild;
    private WildConditionsService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        wild = server.addSimpleWorld("wild");
        service = new WildConditionsService(
                name -> Optional.ofNullable(server.getWorld(name)), () -> "wild");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void middayAndClearSkyAreReportedAsADayWithoutWeather() {
        wild.setTime(6_000L);
        wild.setStorm(false);
        wild.setThundering(false);

        WildConditions conditions = service.current().orElseThrow();

        assertTrue(conditions.daytime());
        assertEquals(WildWeather.CLEAR, conditions.weather());
        assertEquals("Il fait jour dans le Wild. Le temps est clair.", service.describe());
    }

    @Test
    void midnightIsReportedAsANight() {
        wild.setTime(18_000L);

        assertFalse(service.current().orElseThrow().daytime());
        assertTrue(service.describe().startsWith("Il fait nuit dans le Wild."));
    }

    @Test
    void rainIsReportedAsRain() {
        wild.setTime(18_000L);
        wild.setStorm(true);
        wild.setThundering(false);

        assertEquals(WildWeather.RAIN, service.current().orElseThrow().weather());
        assertEquals("Il fait nuit dans le Wild. Il pleut.", service.describe());
    }

    @Test
    void aThunderstormIsReportedAsAStormNeverAsPlainRain() {
        wild.setTime(6_000L);
        wild.setStorm(true);
        wild.setThundering(true);

        assertEquals(WildWeather.THUNDER, service.current().orElseThrow().weather());
        assertEquals("Il fait jour dans le Wild. Un orage est en cours.", service.describe());
    }

    /** Le jour/nuit vient de l'horloge seule : un orage de midi reste « il fait jour ». */
    @Test
    void weatherNeverChangesTheDayOrNightVerdict()  {
        wild.setTime(6_000L);
        wild.setThundering(true);

        assertTrue(service.current().orElseThrow().daytime(),
                "l'orage ne doit jamais transformer midi en nuit dans la phrase du Garde");
    }

    @Test
    void theNightBoundariesMatchTheVanillaClock() {
        wild.setTime(WildConditionsService.NIGHT_START - 1);
        assertTrue(service.current().orElseThrow().daytime(), "juste avant la nuit : encore le jour");

        wild.setTime(WildConditionsService.NIGHT_START);
        assertFalse(service.current().orElseThrow().daytime(), "début de la nuit");

        wild.setTime(WildConditionsService.NIGHT_END);
        assertFalse(service.current().orElseThrow().daytime(), "dernier tick de la nuit");

        wild.setTime(WildConditionsService.NIGHT_END + 1);
        assertTrue(service.current().orElseThrow().daytime(), "lever du jour");
    }

    @Test
    void readingTheConditionsNeverTouchesTimeOrWeather() {
        wild.setTime(18_000L);
        wild.setStorm(true);
        wild.setThundering(true);

        service.describe();
        service.current();

        assertEquals(18_000L, wild.getTime(), "l'heure du Wild ne doit jamais être modifiée");
        assertTrue(wild.hasStorm(), "la météo du Wild ne doit jamais être modifiée");
        assertTrue(wild.isThundering(), "l'orage du Wild ne doit jamais être interrompu");
    }

    @Test
    void anUnloadedWildIsNeverInvented() {
        WildConditionsService missing = new WildConditionsService(
                name -> Optional.empty(), () -> "wild");

        assertTrue(missing.current().isEmpty());
        assertEquals(WildConditionsService.UNKNOWN_DESCRIPTION, missing.describe());
    }

    @Test
    void anUnconfiguredWildWorldIsNeverLookedUp() {
        WildConditionsService unconfigured = new WildConditionsService(
                name -> {
                    throw new AssertionError("aucune recherche de monde ne doit avoir lieu sans nom configuré");
                },
                () -> "  ");

        assertTrue(unconfigured.current().isEmpty());
    }
}
