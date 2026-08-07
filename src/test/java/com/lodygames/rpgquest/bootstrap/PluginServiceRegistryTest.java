package com.lodygames.rpgquest.bootstrap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

class PluginServiceRegistryTest {

    private final List<String> events = new ArrayList<>();

    @Test
    void startsInCallOrderAndStopsInReverseOrder() {
        PluginServiceRegistry registry = new PluginServiceRegistry(NOPLogger.NOP_LOGGER);

        registry.start(recordingService("a"));
        registry.start(recordingService("b"));
        registry.start(recordingService("c"));
        registry.stopAll();

        assertEquals(List.of("start:a", "start:b", "start:c", "stop:c", "stop:b", "stop:a"), events);
    }

    @Test
    void rollsBackAlreadyStartedServicesWhenOneFailsToStart() {
        PluginServiceRegistry registry = new PluginServiceRegistry(NOPLogger.NOP_LOGGER);
        registry.start(recordingService("a"));

        PluginService failing = new PluginService() {
            @Override
            public void start() {
                throw new IllegalStateException("boom");
            }

            @Override
            public void stop() {
                events.add("stop:failing");
            }
        };

        assertThrows(IllegalStateException.class, () -> registry.start(failing));
        // "a" a été arrêté par le rollback ; "failing" n'a jamais démarré donc jamais arrêté.
        assertEquals(List.of("start:a", "stop:a"), events);
    }

    @Test
    void stopAllContinuesEvenIfOneServiceFailsToStop() {
        PluginServiceRegistry registry = new PluginServiceRegistry(NOPLogger.NOP_LOGGER);
        registry.start(new PluginService() {
            @Override
            public void start() {
                events.add("start:broken");
            }

            @Override
            public void stop() {
                throw new IllegalStateException("stop failure");
            }
        });
        registry.start(recordingService("b"));

        assertDoesNotThrow(registry::stopAll);
        assertEquals(List.of("start:broken", "start:b", "stop:b"), events);
    }

    private PluginService recordingService(String id) {
        return new PluginService() {
            @Override
            public void start() {
                events.add("start:" + id);
            }

            @Override
            public void stop() {
                events.add("stop:" + id);
            }
        };
    }
}
