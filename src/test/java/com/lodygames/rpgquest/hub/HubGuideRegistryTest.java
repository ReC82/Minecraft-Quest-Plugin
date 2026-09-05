package com.lodygames.rpgquest.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

/**
 * Issue #11 — vérifie que l'architecture du Guide n'est <strong>pas</strong> figée sur un seul Hub :
 * plusieurs fichiers {@code hub-guides/*.yml} coexistent et se résolvent indépendamment par monde et
 * par identifiant de Hub.
 */
class HubGuideRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesEachHubIndependentlyByWorldAndById() throws Exception {
        Path dir = tempDir.resolve("hub-guides");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("hub_depart.yml"), """
                hub-id: hub_depart
                worlds:
                  - world_hub
                guide-dialogue: rpgquest:guide
                specialty: "Les bases."
                referrals:
                  - role: "Quêtes"
                    npc: "le Libraire"
                    note: "Journal des quêtes."
                """);
        Files.writeString(dir.resolve("hub_desert.yml"), """
                hub-id: hub_desert
                worlds:
                  - world_hub_desert
                guide-dialogue: rpgquest:guide_desert
                specialty: "Le commerce des caravanes."
                referrals:
                  - role: "Caravanes"
                    npc: "Amina"
                    note: "Contrats de transport."
                """);

        HubGuideRegistry registry = new HubGuideRegistry(dir, NOPLogger.NOP_LOGGER);
        registry.reload();

        assertEquals(2, registry.all().size());

        HubGuideDefinition depart = registry.forWorld("world_hub").orElseThrow();
        HubGuideDefinition desert = registry.forWorld("world_hub_desert").orElseThrow();

        assertEquals("hub_depart", depart.hubId());
        assertEquals("hub_desert", desert.hubId());
        assertEquals(registry.forHub("hub_desert").orElseThrow(), desert);
        assertNotEquals(depart.guideDialogueId(), desert.guideDialogueId());
        assertNotEquals(depart.referrals(), desert.referrals());
        assertTrue(registry.forWorld("world_unknown").isEmpty());
    }

    @Test
    void startCopiesTheBundledExampleWhenTheFolderIsEmpty() {
        Path dir = tempDir.resolve("hub-guides");

        HubGuideRegistry registry = new HubGuideRegistry(dir, NOPLogger.NOP_LOGGER);
        registry.start();

        assertTrue(Files.exists(dir.resolve("hub_depart.yml")), "l'exemple hub_depart.yml doit être généré");
        assertFalse(registry.forHub("hub_depart").isEmpty(), "l'exemple généré doit être chargé");
        assertEquals("rpgquest", registry.forHub("hub_depart").orElseThrow().guideDialogueId().getNamespace());
    }
}
