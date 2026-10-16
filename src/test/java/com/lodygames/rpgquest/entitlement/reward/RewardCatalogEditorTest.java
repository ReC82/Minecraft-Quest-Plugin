package com.lodygames.rpgquest.entitlement.reward;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Issue #474 — administration du catalogue entitlements.yml depuis PlugAdmin, sans YAML à la main. */
class RewardCatalogEditorTest {

    private static final String FILE = """
            # Catalogue du serveur : ce commentaire doit survivre.
            enabled: true
            rewards:
              gem:
                label: "Gemme rare"
                items:
                  - {item: DIAMOND, amount: 2}
              # la clé « book » garde son commentaire
              book:
                label: "Livre"
                items:
                  - {item: BOOK}
            tables:
              ogre_loot:
                label: "Butin de l'ogre"
                mode: single
                entries:
                  - {id: gem, reward: gem, chance: 25}
            """;

    @TempDir
    Path dir;
    private Path file;
    private final AtomicReference<RewardCatalog> applied = new AtomicReference<>(RewardCatalog.DISABLED);
    private final AtomicBoolean reloadRefuses = new AtomicBoolean();
    private RewardCatalogEditor editor;
    private RewardTableEditor tables;

    @BeforeEach
    void setUp() throws Exception {
        file = dir.resolve("entitlements.yml");
        Files.writeString(file, FILE);
        Supplier<RewardCatalogLoader.Result> reload = () -> {
            try {
                if (reloadRefuses.get()) {
                    reloadRefuses.set(false);
                    return new RewardCatalogLoader.Result(RewardCatalog.DISABLED, List.of("refusé pour le test"));
                }
                if (!Files.exists(file)) {
                    applied.set(RewardCatalog.DISABLED);
                    return new RewardCatalogLoader.Result(RewardCatalog.DISABLED, List.of());
                }
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(Files.readString(file));
                RewardCatalogLoader.Result result = RewardCatalogLoader.load(yaml);
                applied.set(result.catalog());
                return result;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        EntitlementsFile shared = new EntitlementsFile(file, reload, LoggerFactory.getLogger("test"));
        editor = new RewardCatalogEditor(shared);
        tables = new RewardTableEditor(shared);
    }

    private static RewardDefinition sword(String key, boolean enabled) {
        return new RewardDefinition(key, "Épée du héros",
                List.of(new RewardDefinition.Item("DIAMOND_SWORD", 1, Map.of("sharpness", 3, "unbreaking", 2), null),
                        new RewardDefinition.Item("POTION", 2, Map.of(), "strength")),
                null, enabled);
    }

    @Test
    void initializeCreatesADisabledEmptyCatalogThenTheWholeFlowWorksWithoutYaml() throws Exception {
        Files.delete(file);
        RewardCatalogEditor.Outcome init = editor.initialize();
        assertTrue(init.success(), init.message());
        assertEquals("INITIALIZED", init.code());
        assertEquals(RewardCatalogEditor.INITIAL_TEXT, Files.readString(file));
        assertFalse(applied.get().enabled(), "jamais activé à la création");
        assertTrue(applied.get().rewards().isEmpty() && applied.get().tables().isEmpty(), "aucune récompense inventée");

        assertTrue(editor.saveReward(sword("sword", true), null, true).success());
        assertTrue(tables.save(new RewardTable("chest", "Coffre", RewardTable.Mode.SINGLE, true,
                List.of(new RewardTable.Entry("sword", "sword", 5000))), null, true).success());
        assertFalse(applied.get().enabled(), "créer récompenses et tables n'active rien");
        assertTrue(editor.setCatalogEnabled(true).success());
        assertTrue(applied.get().enabled());
        assertEquals(sword("sword", true), applied.get().rewards().get("sword"));
        assertEquals(1, applied.get().tables().size());
    }

    @Test
    void initializeNeverTouchesAnExistingFileEvenAnInvalidOne() throws Exception {
        byte[] before = Files.readAllBytes(file);
        assertEquals("EXISTS", editor.initialize().code());
        assertArrayEquals(before, Files.readAllBytes(file));

        Files.writeString(file, "enabled: [broken");
        byte[] broken = Files.readAllBytes(file);
        assertEquals("EXISTS", editor.initialize().code());
        assertArrayEquals(broken, Files.readAllBytes(file));
    }

    @Test
    void aRefusedReloadAfterInitializeRemovesTheFile() throws Exception {
        Files.delete(file);
        reloadRefuses.set(true);
        assertEquals("ROLLED_BACK", editor.initialize().code());
        assertFalse(Files.exists(file));
    }

    @Test
    void aNewRewardIsWrittenAndEverythingElseIsKept() throws Exception {
        RewardCatalogEditor.Outcome outcome = editor.saveReward(sword("sword", true), null, true);
        assertTrue(outcome.success(), outcome.message());
        assertEquals("CREATED", outcome.code());
        RewardCatalog catalog = applied.get();
        assertEquals(List.of("gem", "book", "sword"), List.copyOf(catalog.rewards().keySet()));
        assertEquals(sword("sword", true), catalog.rewards().get("sword"));
        assertTrue(catalog.enabled());
        assertEquals(1, catalog.tables().size());
        String text = Files.readString(file);
        assertTrue(text.contains("ce commentaire doit survivre"), text);
        assertTrue(text.contains("garde son commentaire"), text);
    }

    @Test
    void editingKeepsTheOrderRefusesStaleFormsAndSkipsNoOps() throws Exception {
        RewardDefinition gem = reloadAndGet("gem");
        String version = gem.version();
        RewardDefinition changed = new RewardDefinition("gem", "Gemme très rare",
                List.of(new RewardDefinition.Item("EMERALD", 3)), new RewardDefinition.Perk("title", "GEM"), true);
        RewardCatalogEditor.Outcome outcome = editor.saveReward(changed, version, false);
        assertTrue(outcome.success(), outcome.message());
        assertEquals(List.of("gem", "book"), List.copyOf(applied.get().rewards().keySet()));
        assertEquals(changed, applied.get().rewards().get("gem"));

        assertEquals("STALE", editor.saveReward(sword("gem", true), version, false).code());
        assertEquals("UNCHANGED", editor.saveReward(changed, changed.version(), false).code());
        assertEquals("NOT_FOUND", editor.saveReward(sword("ghost", true), null, false).code());
        assertEquals("EXISTS", editor.saveReward(sword("book", true), null, true).code());
    }

    @Test
    void newKeysThatYamlWouldMisreadAreRefused() {
        assertEquals("INVALID", editor.saveReward(sword("off", true), null, true).code());
        assertEquals("INVALID", editor.saveReward(sword("gem.rare", true), null, true).code());
        assertEquals("INVALID", editor.duplicateReward("gem", "yes", null).code());
    }

    @Test
    void anInvalidRewardIsNeverWritten() throws Exception {
        byte[] before = Files.readAllBytes(file);
        RewardDefinition potionOnASword = new RewardDefinition("bad", "Mauvaise",
                List.of(new RewardDefinition.Item("DIAMOND_SWORD", 1, Map.of(), "strength")), null, true);
        RewardCatalogEditor.Outcome outcome = editor.saveReward(potionOnASword, null, true);
        assertEquals("INVALID", outcome.code());
        assertTrue(outcome.message().contains("potion"), outcome.message());
        assertEquals("INVALID", editor.saveReward(new RewardDefinition("void", "Rien", List.of(), null, true), null,
                true).code());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void aRefusedReloadPutsTheOldFileBackByteForByte() throws Exception {
        byte[] before = Files.readAllBytes(file);
        reloadRefuses.set(true);
        assertEquals("ROLLED_BACK", editor.saveReward(sword("sword", true), null, true).code());
        assertArrayEquals(before, Files.readAllBytes(file));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "aucun fichier temporaire laissé");
        }
    }

    @Test
    void aCopyIsCreatedDisabledAndTogglingKeepsTheContent() throws Exception {
        RewardCatalogEditor.Outcome copy = editor.duplicateReward("gem", "gem_v2", null);
        assertTrue(copy.success(), copy.message());
        RewardDefinition created = applied.get().rewards().get("gem_v2");
        assertFalse(created.enabled());
        assertEquals("Gemme rare (copie)", created.label());
        assertEquals(applied.get().rewards().get("gem").items(), created.items());
        assertEquals("EXISTS", editor.duplicateReward("gem", "book", null).code());
        assertEquals("NOT_FOUND", editor.duplicateReward("ghost", "x", null).code());

        assertTrue(editor.setRewardEnabled("gem", false).success());
        assertFalse(applied.get().rewards().get("gem").enabled());
        assertFalse(applied.get().promisable("gem"));
        assertEquals("UNCHANGED", editor.setRewardEnabled("gem", false).code());
        assertEquals(1, applied.get().tables().size(), "la table qui la cite reste valide");
        assertTrue(editor.setRewardEnabled("gem", true).success());
        assertTrue(applied.get().promisable("gem"));
    }

    @Test
    void theGlobalSwitchOnlyChangesEnabled() throws Exception {
        RewardCatalog before = reload();
        assertTrue(editor.setCatalogEnabled(false).success());
        assertFalse(applied.get().enabled());
        assertEquals(before.rewards(), applied.get().rewards());
        assertEquals(before.tables(), applied.get().tables());
        assertEquals("UNCHANGED", editor.setCatalogEnabled(false).code());
        assertTrue(Files.readString(file).contains("ce commentaire doit survivre"));
    }

    @Test
    void anAbsentOrInvalidCatalogIsNeverWrittenOver() throws Exception {
        Files.writeString(file, "enabled: true\nrewards:\n  gem:\n    label: \"\"\n");
        byte[] broken = Files.readAllBytes(file);
        assertEquals("CURRENT_INVALID", editor.saveReward(sword("sword", true), null, true).code());
        assertEquals("CURRENT_INVALID", editor.setCatalogEnabled(false).code());
        assertArrayEquals(broken, Files.readAllBytes(file));
        Files.delete(file);
        assertEquals("NO_CATALOG", editor.setCatalogEnabled(true).code());
        assertFalse(Files.exists(file));
    }

    @Test
    void aDisabledRewardIsNeverWonButTheDrawItselfIsUnchanged() {
        RewardTable always = new RewardTable("t", "T", RewardTable.Mode.INDEPENDENT, true,
                List.of(new RewardTable.Entry("a", "gem", RewardTable.FULL), new RewardTable.Entry("b", "book",
                        RewardTable.FULL)));
        UUID player = UUID.randomUUID();
        List<RewardTableDraw.Win> all = RewardTableDraw.wins(always, "quest:x", player, RewardSource.QUEST, "q",
                key -> key);
        List<RewardTableDraw.Win> some = RewardTableDraw.wins(always, "quest:x", player, RewardSource.QUEST, "q",
                key -> key, key -> !"gem".equals(key));
        assertEquals(2, all.size());
        assertEquals(List.of(all.get(1)), some);
    }

    @Test
    void theLoaderReadsTheRewardSwitch() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("rewards:\n  a:\n    label: A\n    enabled: false\n    items: [{item: DIAMOND}]\n"
                + "  b:\n    label: B\n    items: [{item: DIAMOND}]\n");
        RewardCatalog catalog = RewardCatalogLoader.load(yaml).catalog();
        assertFalse(catalog.rewards().get("a").enabled());
        assertTrue(catalog.rewards().get("b").enabled(), "absent = actif (fichiers existants inchangés)");
        assertTrue(catalog.promisable("inconnue"), "inconnue : le droit reste en attente avec l'erreur, comme avant");

        yaml.loadFromString("rewards:\n  a:\n    label: A\n    enabled: peut-etre\n    items: [{item: DIAMOND}]\n");
        assertFalse(RewardCatalogLoader.load(yaml).valid());
    }

    private RewardCatalog reload() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file));
        return RewardCatalogLoader.load(yaml).catalog();
    }

    private RewardDefinition reloadAndGet(String key) throws Exception {
        return reload().rewards().get(key);
    }
}
