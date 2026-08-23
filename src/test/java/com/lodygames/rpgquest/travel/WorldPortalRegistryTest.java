package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

/**
 * Couvre la persistance des portails simples (voir docs-site/worlds.html, section « Portail Hub →
 * wild »), même conception que {@code zone.ZoneRegistryTest}.
 */
class WorldPortalRegistryTest {

    @TempDir
    Path tempDir;

    private WorldPortalRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WorldPortalRegistry(tempDir.resolve("world-portals"), NOPLogger.NOP_LOGGER);
        registry.start();
    }

    private WorldPortalDefinition portal(String id, String world, String destinationWorld) {
        return new WorldPortalDefinition(id, world, -5, 60, -5, 5, 70, 5, destinationWorld, true);
    }

    @Test
    void startsEmptyWithNoPortalsOnDisk() {
        assertTrue(registry.portals().isEmpty());
        assertTrue(registry.find("hub_to_wild").isEmpty());
    }

    @Test
    void createPersistsAndReloadsThePortal() {
        assertEquals(WorldPortalRegistry.CreateOutcome.CREATED, registry.create(portal("hub_to_wild", "world", "wild")));

        WorldPortalDefinition found = registry.find("hub_to_wild").orElseThrow();
        assertEquals("world", found.world());
        assertEquals("wild", found.destinationWorld());
        assertTrue(found.enabled());
        assertEquals(DestinationStrategy.WORLD_SPAWN, found.destinationStrategy(), "stratégie par défaut sans argument explicite");
    }

    @Test
    void randomSafeStrategySurvivesARestartViaAFreshRegistryInstance() {
        WorldPortalDefinition randomPortal = new WorldPortalDefinition(
                "hub_to_wild", "world", -5, 60, -5, 5, 70, 5, "wild", true, DestinationStrategy.RANDOM_SAFE);
        registry.create(randomPortal);

        WorldPortalRegistry restarted = new WorldPortalRegistry(tempDir.resolve("world-portals"), NOPLogger.NOP_LOGGER);
        restarted.start();

        assertEquals(DestinationStrategy.RANDOM_SAFE, restarted.find("hub_to_wild").orElseThrow().destinationStrategy());
    }

    @Test
    void portalSurvivesARestartViaAFreshRegistryInstanceOnTheSameDirectory() {
        registry.create(portal("hub_to_wild", "world", "wild"));

        WorldPortalRegistry restarted = new WorldPortalRegistry(tempDir.resolve("world-portals"), NOPLogger.NOP_LOGGER);
        restarted.start();

        WorldPortalDefinition found = restarted.find("hub_to_wild").orElseThrow();
        assertEquals("wild", found.destinationWorld());
    }

    @Test
    void duplicateIdIsRejected() {
        registry.create(portal("hub_to_wild", "world", "wild"));

        assertEquals(WorldPortalRegistry.CreateOutcome.DUPLICATE_ID,
                registry.create(portal("hub_to_wild", "world", "wild")));
    }

    @Test
    void overlappingPortalInTheSameWorldIsRejected() {
        registry.create(portal("hub_to_wild", "world", "wild"));
        WorldPortalDefinition overlapping = new WorldPortalDefinition(
                "hub_to_other", "world", 0, 60, 0, 10, 70, 10, "other", true);

        assertEquals(WorldPortalRegistry.CreateOutcome.OVERLAPS, registry.create(overlapping));
    }

    @Test
    void nonOverlappingPortalInAnotherWorldIsAccepted() {
        registry.create(portal("hub_to_wild", "world", "wild"));

        assertEquals(WorldPortalRegistry.CreateOutcome.CREATED,
                registry.create(portal("elsewhere_to_wild", "elsewhere", "wild")));
    }

    @Test
    void portalAtFindsThePortalOnlyInTheCorrectWorldAndBounds() {
        registry.create(portal("hub_to_wild", "world", "wild"));

        assertTrue(registry.portalAt("world", 0, 65, 0).isPresent());
        assertTrue(registry.portalAt("world", -5, 60, -5).isPresent(), "borne min incluse");
        assertTrue(registry.portalAt("world", 5, 70, 5).isPresent(), "borne max incluse");
        assertFalse(registry.portalAt("world", 100, 65, 100).isPresent(), "hors bornes");
        assertFalse(registry.portalAt("wild", 0, 65, 0).isPresent(), "mêmes coordonnées, mauvais monde");
    }

    // ---- enable / disable ------------------------------------------------------------------------

    @Test
    void disableTurnsEnabledOffAndPersistsIt() {
        registry.create(portal("hub_to_wild", "world", "wild"));

        assertEquals(WorldPortalRegistry.SetEnabledOutcome.UPDATED, registry.setEnabled("hub_to_wild", false));
        assertFalse(registry.find("hub_to_wild").orElseThrow().enabled());

        WorldPortalRegistry restarted = new WorldPortalRegistry(tempDir.resolve("world-portals"), NOPLogger.NOP_LOGGER);
        restarted.start();
        assertFalse(restarted.find("hub_to_wild").orElseThrow().enabled(), "désactivation persistée après redémarrage");
    }

    @Test
    void enableTurnsEnabledBackOnAndPersistsIt() {
        registry.create(portal("hub_to_wild", "world", "wild"));
        registry.setEnabled("hub_to_wild", false);

        assertEquals(WorldPortalRegistry.SetEnabledOutcome.UPDATED, registry.setEnabled("hub_to_wild", true));
        assertTrue(registry.find("hub_to_wild").orElseThrow().enabled());
    }

    @Test
    void disablingAnAlreadyDisabledPortalIsReportedAsUnchanged() {
        registry.create(portal("hub_to_wild", "world", "wild"));
        registry.setEnabled("hub_to_wild", false);

        assertEquals(WorldPortalRegistry.SetEnabledOutcome.UNCHANGED, registry.setEnabled("hub_to_wild", false));
    }

    @Test
    void enablingAnAlreadyEnabledPortalIsReportedAsUnchanged() {
        registry.create(portal("hub_to_wild", "world", "wild")); // enabled=true dès la création

        assertEquals(WorldPortalRegistry.SetEnabledOutcome.UNCHANGED, registry.setEnabled("hub_to_wild", true));
    }

    @Test
    void setEnabledOnAnUnknownPortalIsReportedAsNotFound() {
        assertEquals(WorldPortalRegistry.SetEnabledOutcome.NOT_FOUND, registry.setEnabled("does_not_exist", false));
    }

    @Test
    void setEnabledKeepsTheRestOfTheConfigurationUnchanged() {
        registry.create(portal("hub_to_wild", "world", "wild"));

        registry.setEnabled("hub_to_wild", false);

        WorldPortalDefinition found = registry.find("hub_to_wild").orElseThrow();
        assertEquals("world", found.world());
        assertEquals("wild", found.destinationWorld());
        assertEquals(-5, found.minX());
        assertEquals(5, found.maxX());
    }

    // ---- delete ------------------------------------------------------------------------------------

    @Test
    void deleteRemovesThePortalFromMemoryAndFromDisk() {
        registry.create(portal("hub_to_wild", "world", "wild"));

        assertTrue(registry.delete("hub_to_wild"));
        assertTrue(registry.find("hub_to_wild").isEmpty());

        WorldPortalRegistry restarted = new WorldPortalRegistry(tempDir.resolve("world-portals"), NOPLogger.NOP_LOGGER);
        restarted.start();
        assertTrue(restarted.find("hub_to_wild").isEmpty(), "suppression persistée après redémarrage");
    }

    @Test
    void deletingAnUnknownPortalReturnsFalse() {
        assertFalse(registry.delete("does_not_exist"));
    }

    @Test
    void deleteNeverAffectsOtherPortals() {
        registry.create(portal("hub_to_wild", "world", "wild"));
        registry.create(portal("elsewhere_to_wild", "elsewhere", "wild"));

        registry.delete("hub_to_wild");

        assertTrue(registry.find("hub_to_wild").isEmpty());
        assertTrue(registry.find("elsewhere_to_wild").isPresent(), "un autre portail ne doit jamais être affecté");
    }

    @Test
    void corruptFileIsIgnoredRatherThanCrashingReload() throws Exception {
        Files.createDirectories(tempDir.resolve("world-portals"));
        Files.writeString(tempDir.resolve("world-portals/broken.yml"), "not: a valid portal\n");

        WorldPortalRegistry broken = new WorldPortalRegistry(tempDir.resolve("world-portals"), NOPLogger.NOP_LOGGER);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(broken::start);
        assertTrue(broken.portals().isEmpty());
    }

    // ---- portalsContaining (diagnostic) ------------------------------------------------------------

    @Test
    void portalsContainingReturnsEveryMatchNotJustTheFirst() {
        registry.create(portal("hub_to_wild", "world", "wild"));
        // create() rejette normalement les chevauchements (voir overlappingPortalInTheSameWorldIsRejected)
        // — cette seconde zone est donc introduite par un fichier ajouté à la main, exactement comme
        // le décrit le Javadoc de WorldPortalRegistry (voir le test suivant pour la preuve complète).
        writeManualPortalFile("hub_to_other", "world", 0, 60, 0, 10, 70, 10, "other");
        registry.reload();

        var matches = registry.portalsContaining("world", 0, 65, 0);

        assertEquals(2, matches.size(), "les deux zones superposées doivent apparaître, pas seulement la première");
    }

    @Test
    void portalsContainingReturnsEmptyOutsideAnyZone() {
        registry.create(portal("hub_to_wild", "world", "wild"));

        assertTrue(registry.portalsContaining("world", 1000, 65, 1000).isEmpty());
    }

    /**
     * TODO(debug bug TP hub) : preuve exécutable de l'anomalie documentée dans le Javadoc de la
     * classe — {@link #reload()} (donc aussi {@link #start()}) ne rejette ni les id dupliqués ni
     * les chevauchements entre fichiers, contrairement à {@code create()}. Un fichier ajouté ou
     * édité à la main dans {@code plugins/RPGQuest/world-portals/} peut donc faire coexister deux
     * zones actives superposées sans qu'aucune erreur ne soit jamais journalisée — exactement le
     * genre de configuration invisible que {@code /rpgadmin worldportal here} est censé révéler.
     * Ce test documente le comportement actuel tel quel (pas encore corrigé, mission : outils de
     * diagnostic d'abord) plutôt que de l'affirmer en prose seulement.
     */
    @Test
    void reloadNeverRejectsOverlappingZonesIntroducedByManuallyPlacedFiles() throws Exception {
        writeManualPortalFile("hub_to_wild", "world", -5, 60, -5, 5, 70, 5, "wild");
        writeManualPortalFile("hub_to_other", "world", 0, 60, 0, 10, 70, 10, "other");

        registry.reload(); // reload(), pas create() : c'est ce chemin qui n'a aucune validation croisée.

        assertEquals(2, registry.portals().size(), "aucun rejet de chevauchement lors d'un reload() (à la différence de create())");
        assertTrue(registry.portalAt("world", 0, 65, 0).isPresent(), "portalAt continue de ne renvoyer que la première zone trouvée");
        assertEquals(2, registry.portalsContaining("world", 0, 65, 0).size(), "les deux zones sont bien actives simultanément");
    }

    @Test
    void reloadNeverRejectsDuplicateIdsIntroducedByManuallyPlacedFiles() throws Exception {
        writeManualPortalFile("hub_to_wild", "world", -5, 60, -5, 5, 70, 5, "wild");
        // Même id, fichier différent, bornes différentes (donc pas un simple doublon inoffensif).
        Path duplicate = tempDir.resolve("world-portals/zz_duplicate.yml");
        var yaml = new YamlConfiguration();
        yaml.set("id", "hub_to_wild");
        yaml.set("world", "world");
        yaml.set("min.x", 100); yaml.set("min.y", 60); yaml.set("min.z", 100);
        yaml.set("max.x", 110); yaml.set("max.y", 70); yaml.set("max.z", 110);
        yaml.set("destination-world", "somewhere_else");
        yaml.save(duplicate.toFile());

        registry.reload();

        assertEquals(2, registry.portals().size(), "aucun rejet de doublon d'id lors d'un reload()");
    }

    private void writeManualPortalFile(String id, String world, int minX, int minY, int minZ,
                                        int maxX, int maxY, int maxZ, String destinationWorld) {
        try {
            Files.createDirectories(tempDir.resolve("world-portals"));
            Path target = tempDir.resolve("world-portals/" + id + "_manual.yml");
            var yaml = new YamlConfiguration();
            yaml.set("id", id);
            yaml.set("world", world);
            yaml.set("min.x", minX); yaml.set("min.y", minY); yaml.set("min.z", minZ);
            yaml.set("max.x", maxX); yaml.set("max.y", maxY); yaml.set("max.z", maxZ);
            yaml.set("destination-world", destinationWorld);
            yaml.save(target.toFile());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
