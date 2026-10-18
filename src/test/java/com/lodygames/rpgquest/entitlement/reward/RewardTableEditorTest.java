package com.lodygames.rpgquest.entitlement.reward;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Issue #177 tranche 2 — écriture sûre de la section tables de entitlements.yml. */
class RewardTableEditorTest {

    private static final String FILE = """
            # Catalogue du serveur : ce commentaire doit survivre.
            enabled: true
            rewards:
              gem:
                label: "Gemme rare"
                items:
                  - {item: DIAMOND, amount: 2}
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
                  - {id: book, reward: book, chance: 12.5}
            """;

    @TempDir
    Path dir;
    private Path file;
    private final AtomicReference<RewardCatalog> applied = new AtomicReference<>(RewardCatalog.DISABLED);
    private final AtomicBoolean reloadRefuses = new AtomicBoolean();
    private RewardTableEditor editor;

    @BeforeEach
    void setUp() throws Exception {
        file = dir.resolve("entitlements.yml");
        Files.writeString(file, FILE);
        Supplier<RewardCatalogLoader.Result> reload = () -> {
            try {
                if (reloadRefuses.get()) {
                    reloadRefuses.set(false); // seul le premier rechargement (après écriture) échoue
                    return new RewardCatalogLoader.Result(RewardCatalog.DISABLED, List.of("refusé pour le test"));
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
        editor = new RewardTableEditor(file, reload, LoggerFactory.getLogger("test"));
    }

    private static RewardTable table(String id, RewardTable.Mode mode, boolean enabled, RewardTable.Entry... entries) {
        return new RewardTable(id, "Coffre " + id, mode, enabled, List.of(entries));
    }

    @Test
    void aNewTableIsWrittenReloadedAndEverythingElseIsKept() throws Exception {
        RewardTable chest = table("chest", RewardTable.Mode.INDEPENDENT, true,
                new RewardTable.Entry("gem", "gem", 3333), new RewardTable.Entry("book", "book", 1));

        RewardTableEditor.Outcome outcome = editor.save(chest, null, true);

        assertTrue(outcome.success(), outcome.message());
        assertEquals("CREATED", outcome.code());
        assertEquals(chest, applied.get().findTable("chest").orElseThrow());
        assertEquals(List.of("ogre_loot", "chest"), List.copyOf(applied.get().tables().keySet()));
        assertEquals(2, applied.get().rewards().size());
        assertTrue(applied.get().enabled());
        String text = Files.readString(file);
        assertTrue(text.contains("ce commentaire doit survivre"), text);
        assertTrue(text.contains("chance: 33.33") && text.contains("chance: 0.01"), text);
    }

    @Test
    void anUpdateKeepsTheOrderAndAnnouncesTheNewVersion() {
        RewardTable before = editor.save(table("other", RewardTable.Mode.SINGLE, true,
                new RewardTable.Entry("gem", "gem", 100)), null, true).table();
        RewardTable ogre = new RewardTable("ogre_loot", "Butin de l'ogre", RewardTable.Mode.SINGLE, true,
                List.of(new RewardTable.Entry("gem", "gem", 5000)));
        String oldVersion = applied.get().findTable("ogre_loot").orElseThrow().version();

        RewardTableEditor.Outcome outcome = editor.save(ogre, oldVersion, false);

        assertTrue(outcome.success(), outcome.message());
        assertTrue(outcome.message().contains("v" + ogre.version()) && outcome.message().contains("v" + oldVersion),
                outcome.message());
        assertEquals(List.of("ogre_loot", "other"), List.copyOf(applied.get().tables().keySet()));
        assertEquals(before, applied.get().findTable("other").orElseThrow());
    }

    @Test
    void anInvalidTableIsRefusedWithoutTouchingTheFile() throws Exception {
        byte[] before = Files.readAllBytes(file);
        // Mode « single » : 60 % + 50 % > 100 %, refusé par le vrai chargeur.
        RewardTableEditor.Outcome tooMuch = editor.save(table("x", RewardTable.Mode.SINGLE, true,
                new RewardTable.Entry("a", "gem", 6000), new RewardTable.Entry("b", "book", 5000)), null, true);
        RewardTableEditor.Outcome unknown = editor.save(table("y", RewardTable.Mode.SINGLE, true,
                new RewardTable.Entry("a", "pas_au_catalogue", 100)), null, true);

        assertFalse(tooMuch.success());
        assertEquals("INVALID", tooMuch.code());
        assertTrue(tooMuch.message().contains("100 %"), tooMuch.message());
        assertEquals("INVALID", unknown.code());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void anIndependentTableMayExceed100Percent() {
        // Retour propriétaire du 18/10 : seul le mode « single » plafonne la somme à 100 %.
        RewardTableEditor.Outcome outcome = editor.save(table("many", RewardTable.Mode.INDEPENDENT, true,
                new RewardTable.Entry("a", "gem", 7000), new RewardTable.Entry("b", "book", 5000)), null, true);

        assertTrue(outcome.success(), outcome.message());
        assertEquals(12_000, applied.get().findTable("many").orElseThrow().totalChance());
    }

    @Test
    void aRefusedReloadPutsTheOldFileBackByteForByte() throws Exception {
        byte[] before = Files.readAllBytes(file);
        reloadRefuses.set(true);

        RewardTableEditor.Outcome outcome = editor.save(table("chest", RewardTable.Mode.SINGLE, true,
                new RewardTable.Entry("gem", "gem", 100)), null, true);

        assertFalse(outcome.success());
        assertEquals("ROLLED_BACK", outcome.code());
        assertArrayEquals(before, Files.readAllBytes(file));
        assertTrue(applied.get().findTable("chest").isEmpty());
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "aucun fichier temporaire laissé");
        }
    }

    @Test
    void aStaleFormIsRefusedInsteadOfOverwriting() throws Exception {
        byte[] before = Files.readAllBytes(file);
        RewardTableEditor.Outcome outcome = editor.save(new RewardTable("ogre_loot", "Butin", RewardTable.Mode.SINGLE,
                true, List.of(new RewardTable.Entry("gem", "gem", 100))), "deadbeef", false);
        assertEquals("STALE", outcome.code());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void createAndUpdateCheckWhetherTheTableExists() {
        RewardTable ogre = new RewardTable("ogre_loot", "Butin", RewardTable.Mode.SINGLE, true,
                List.of(new RewardTable.Entry("gem", "gem", 100)));
        assertEquals("EXISTS", editor.save(ogre, null, true).code());
        assertEquals("INVALID", editor.save(table("off", RewardTable.Mode.SINGLE, true,
                new RewardTable.Entry("gem", "gem", 100)), null, true).code());
        assertEquals("NOT_FOUND", editor.save(table("ghost", RewardTable.Mode.SINGLE, true,
                new RewardTable.Entry("gem", "gem", 100)), null, false).code());
    }

    @Test
    void anUnchangedSaveSucceedsWithoutWriting() throws Exception {
        byte[] before = Files.readAllBytes(file);
        RewardTable same = new RewardTable("ogre_loot", "Butin de l'ogre", RewardTable.Mode.SINGLE, true,
                List.of(new RewardTable.Entry("gem", "gem", 2500), new RewardTable.Entry("book", "book", 1250)));
        RewardTableEditor.Outcome outcome = editor.save(same, same.version(), false);
        assertTrue(outcome.success());
        assertEquals("UNCHANGED", outcome.code());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void aDuplicateIsADisabledCopyWithTheSameEntries() {
        RewardTableEditor.Outcome outcome = editor.duplicate("ogre_loot", "ogre_loot_v2", null);

        assertTrue(outcome.success(), outcome.message());
        RewardTable copy = applied.get().findTable("ogre_loot_v2").orElseThrow();
        RewardTable source = applied.get().findTable("ogre_loot").orElseThrow();
        assertFalse(copy.enabled());
        assertEquals("Butin de l'ogre (copie)", copy.label());
        assertEquals(source.entries(), copy.entries());
        assertEquals(source.version(), copy.version());
        assertEquals("EXISTS", editor.duplicate("ogre_loot", "ogre_loot_v2", null).code());
        assertEquals("NOT_FOUND", editor.duplicate("ghost", "ghost_v2", null).code());
    }

    @Test
    void toggleChangesOnlyTheSwitch() {
        String version = new RewardTable("ogre_loot", "Butin de l'ogre", RewardTable.Mode.SINGLE, true,
                List.of(new RewardTable.Entry("gem", "gem", 2500), new RewardTable.Entry("book", "book", 1250)))
                .version();
        RewardTableEditor.Outcome off = editor.setEnabled("ogre_loot", false);
        assertTrue(off.success(), off.message());
        RewardTable table = applied.get().findTable("ogre_loot").orElseThrow();
        assertFalse(table.enabled());
        assertEquals(version, table.version());
        assertEquals("UNCHANGED", editor.setEnabled("ogre_loot", false).code());
        assertTrue(editor.setEnabled("ogre_loot", true).success());
        assertTrue(applied.get().findTable("ogre_loot").orElseThrow().enabled());
    }

    @Test
    void anAbsentOrInvalidCatalogIsNeverWrittenOver() throws Exception {
        RewardTable chest = table("chest", RewardTable.Mode.SINGLE, true, new RewardTable.Entry("gem", "gem", 100));
        Files.writeString(file, "enabled: true\nrewards:\n  gem:\n    label: \"\"\n");
        byte[] broken = Files.readAllBytes(file);
        assertEquals("CURRENT_INVALID", editor.save(chest, null, true).code());
        assertArrayEquals(broken, Files.readAllBytes(file));

        Files.delete(file);
        assertEquals("NO_CATALOG", editor.save(chest, null, true).code());
        assertFalse(Files.exists(file));
    }

    @Test
    void aFileWithoutTablesGetsItsFirstTable() throws Exception {
        Files.writeString(file, "enabled: false\nrewards:\n  gem:\n    label: \"Gemme\"\n    items:\n      - {item: DIAMOND}\n");
        RewardTableEditor.Outcome outcome = editor.save(table("first", RewardTable.Mode.SINGLE, true,
                new RewardTable.Entry("gem", "gem", 10000)), null, true);
        assertTrue(outcome.success(), outcome.message());
        assertFalse(applied.get().enabled(), "l'interrupteur général n'est jamais touché");
        assertEquals(1, applied.get().tables().size());
    }

    @Test
    void chancesAreWrittenAsExactNumbers() {
        assertEquals(5, RewardTableEditor.chanceValue(500));
        assertEquals(12.5, RewardTableEditor.chanceValue(1250));
        assertEquals(0.01, RewardTableEditor.chanceValue(1));
        assertEquals(100, RewardTableEditor.chanceValue(RewardTable.FULL));
        for (int h = 1; h <= RewardTable.FULL; h++) {
            assertEquals(h, RewardCatalogLoader.chance(RewardTableEditor.chanceValue(h)), "chance " + h);
        }
    }
}
