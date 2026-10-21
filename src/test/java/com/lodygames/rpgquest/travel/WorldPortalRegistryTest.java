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
    void anOverlappingFileAddedByHandIsSkippedAndTheLoadedPortalKeepsItsZone() {
        registry.create(portal("hub_to_wild", "world", "wild"));
        // « hub_to_other_manual.yml » passe AVANT « hub_to_wild.yml » par ordre alphabétique : sans
        // la priorité aux portails déjà chargés (#565), il volerait la zone de hub_to_wild.
        writeManualPortalFile("hub_to_other", "world", 0, 60, 0, 10, 70, 10, "other");
        registry.reload();

        assertEquals(1, registry.portalsContaining("world", 0, 65, 0).size());
        assertEquals("hub_to_wild", registry.portalAt("world", 0, 65, 0).orElseThrow().id());
        assertEquals(java.util.List.of("hub_to_other_manual.yml"),
                registry.skippedFiles().stream().map(PortalLoadIssue::file).toList());
        assertTrue(registry.skippedFiles().get(0).message().contains("hub_to_wild"));
    }

    @Test
    void portalsContainingReturnsEmptyOutsideAnyZone() {
        registry.create(portal("hub_to_wild", "world", "wild"));

        assertTrue(registry.portalsContaining("world", 1000, 65, 1000).isEmpty());
    }

    @Test
    void atStartupOverlappingFilesAreResolvedByFileNameOrder() throws Exception {
        writeManualPortalFile("hub_to_wild", "world", -5, 60, -5, 5, 70, 5, "wild");
        writeManualPortalFile("hub_to_other", "world", 0, 60, 0, 10, 70, 10, "other");

        WorldPortalRegistry fresh = new WorldPortalRegistry(tempDir.resolve("world-portals"), NOPLogger.NOP_LOGGER);
        fresh.start();

        assertEquals(1, fresh.portals().size(), "une zone ambiguë est refusée au chargement (#565)");
        assertEquals("hub_to_other", fresh.portals().get(0).id(), "premier fichier dans l'ordre alphabétique");
        assertEquals("hub_to_wild_manual.yml", fresh.skippedFiles().get(0).file());
    }

    @Test
    void reloadRejectsADuplicateIdFromAnotherFile() throws Exception {
        writeManualPortalFile("hub_to_wild", "world", -5, 60, -5, 5, 70, 5, "wild");
        registry.reload();
        // Même id, fichier différent, bornes différentes (donc pas un simple doublon inoffensif).
        Path duplicate = tempDir.resolve("world-portals/aa_duplicate.yml");
        var yaml = new YamlConfiguration();
        yaml.set("id", "hub_to_wild");
        yaml.set("world", "world");
        yaml.set("min.x", 100); yaml.set("min.y", 60); yaml.set("min.z", 100);
        yaml.set("max.x", 110); yaml.set("max.y", 70); yaml.set("max.z", 110);
        yaml.set("destination-world", "somewhere_else");
        yaml.save(duplicate.toFile());

        registry.reload();

        assertEquals(1, registry.portals().size());
        assertEquals("wild", registry.find("hub_to_wild").orElseThrow().destinationWorld(),
                "le portail déjà chargé garde la priorité");
        assertEquals("aa_duplicate.yml", registry.skippedFiles().get(0).file());
    }

    @Test
    void draftFieldsRoundTripAndADraftIsNeverEnabled() {
        WorldPortalDefinition draft = new WorldPortalDefinition("arche_test", "world", 0, 64, 0, 2, 67, 0, "wild",
                false, DestinationStrategy.RANDOM_SAFE,
                new WorldPortalDefinition.Details("Arche du test", "buildsite_0003", true, 3, 4, 1));
        assertEquals(WorldPortalRegistry.CreateOutcome.CREATED, registry.create(draft));

        WorldPortalRegistry fresh = new WorldPortalRegistry(tempDir.resolve("world-portals"), NOPLogger.NOP_LOGGER);
        fresh.start();
        WorldPortalDefinition read = fresh.find("arche_test").orElseThrow();
        assertEquals(draft, read);
        assertEquals(WorldPortalDefinition.State.DRAFT, read.state());
        assertFalse(read.enabled(), "un brouillon reste enabled: false — un ancien JAR ne le déclenche pas");
    }

    @Test
    void enablingADraftLeavesTheDraftState() {
        registry.create(new WorldPortalDefinition("arche_test", "world", 0, 64, 0, 2, 67, 0, "wild", false,
                DestinationStrategy.WORLD_SPAWN, new WorldPortalDefinition.Details("", "", true, 0, 0, 0)));

        registry.setEnabled("arche_test", true);

        assertEquals(WorldPortalDefinition.State.ACTIVE, registry.find("arche_test").orElseThrow().state());
    }

    @Test
    void togglingALegacyPortalRewritesNoNewKey() throws Exception {
        writeManualPortalFile("hub_to_wild", "world", -5, 60, -5, 5, 70, 5, "wild");
        registry.reload();

        registry.setEnabled("hub_to_wild", false);

        String text = Files.readString(tempDir.resolve("world-portals/hub_to_wild_manual.yml"));
        assertFalse(text.contains("draft") || text.contains("name:") || text.contains("site") || text.contains("size"),
                "un portail historique garde son format : " + text);
        assertFalse(Files.exists(tempDir.resolve("world-portals/hub_to_wild.yml")),
                "l'écriture vise le fichier d'origine, pas <id>.yml");
    }

    @Test
    void createRefusesAnIdWhoseFileExistsEvenIfItWasSkipped() throws Exception {
        Files.writeString(tempDir.resolve("world-portals/arche.yml"), "id: arche\nworld: world\n");
        registry.reload();

        assertEquals(WorldPortalRegistry.CreateOutcome.DUPLICATE_ID, registry.create(portal("arche", "world", "wild")));
    }

    @Test
    void saveRefusesAZoneThatOverlapsAnotherPortal() {
        registry.create(portal("hub_to_wild", "world", "wild"));
        registry.create(new WorldPortalDefinition("other", "world", 20, 60, 20, 22, 63, 20, "wild", false));
        WorldPortalDefinition moved = registry.find("other").orElseThrow()
                .withZone(new WorldPortalDefinition.Zone(0, 60, 0, 2, 63, 0), WorldPortalDefinition.Details.NONE);

        assertEquals(WorldPortalRegistry.SaveOutcome.OVERLAPS, registry.save(moved));
        assertEquals(20, registry.find("other").orElseThrow().minX());
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

    @Test
    void reloadReportsSourceFilesAndSkippedFilesWithoutChangingWhatIsLoaded() throws IOException {
        Path dir = tempDir.resolve("world-portals");
        Files.writeString(dir.resolve("a_hub_to_wild.yml"), String.join("\n", "id: hub_to_wild", "world: world_hub",
                "min: {x: 1, y: 2, z: 3}", "max: {x: 4, y: 5, z: 6}", "destination-world: wild"));
        Files.writeString(dir.resolve("broken.yml"), String.join("\n", "id: broken", "world: world_hub",
                "min: {x: 9, y: 2, z: 3}", "max: {x: 4, y: 5, z: 6}", "destination-world: wild"));
        Files.writeString(dir.resolve("no_dest.yml"), String.join("\n", "id: no_dest", "world: world_hub"));

        registry.reload();

        assertEquals(1, registry.portals().size(), "les fichiers invalides restent ignorés, comme avant");
        assertEquals(java.util.List.of("a_hub_to_wild.yml"), registry.sourceFiles());
        assertEquals(java.util.List.of("broken.yml", "no_dest.yml"),
                registry.skippedFiles().stream().map(PortalLoadIssue::file).toList());
        assertTrue(registry.skippedFiles().get(1).message().contains("destination-world"));
    }
}
