package com.lodygames.rpgquest.mob;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.mob.model.BossMode;
import com.lodygames.rpgquest.mob.model.MobAbilityType;
import com.lodygames.rpgquest.mob.model.MobCategory;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Issue #214 — modèle à remplir, vérification d'un fichier importé et écriture du seul profil
 * ciblé. MockBukkit : particules et sons ne se résolvent qu'avec un serveur (voir
 * {@code SpecialMobDefinitionParserTest}).
 */
class SpecialMobImportTest {

    @TempDir
    Path root;

    private Path mobs;
    private Path backups;
    private SpecialMobDefinitionStore store;

    private static final SpecialMobImport.References REFS = new SpecialMobImport.References(
            Set.of("wild", "world", "hub"), Set.of("plains", "desert", "swamp"),
            key -> key.equals(new NamespacedKey("rpgquest", "dent_de_loup")),
            key -> key.equals("badge_boss") || key.equals("table:coffre_boss"));

    /** Le cas de validation du propriétaire (#214), à ne jamais créer sur DEV. */
    private static final String TRAINING_ZOMBIE = """
            id: boss_zombie_entrainement
            category: BOSS
            enabled: true
            boss-mode: COMMUNITY
            community-weight: 1
            participation-rewards: []
            entity-type: ZOMBIE
            name: "Zombie d'entraînement"
            spawn-chance: 0.0
            worlds: [wild]
            biomes: []
            zones: []
            health: 20.0
            damage: 2.0
            speed: 0.20
            """;

    @BeforeEach
    void setUp() throws IOException {
        MockBukkit.mock();
        mobs = Files.createDirectories(root.resolve("mobs"));
        backups = root.resolve("mob-backups");
        store = new SpecialMobDefinitionStore(mobs);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static String filled(String template, String id, String name) {
        return template.replaceFirst("(?m)^id: \"\"$", "id: " + id)
                .replaceFirst("(?m)^name: \"\"$", "name: \"" + name + "\"");
    }

    /** Décommente toutes les lignes facultatives du modèle, capacités et butin compris. */
    private static String everythingUncommented(String template) {
        Set<String> keys = SpecialMobSchema.topLevelKeys();
        return template.lines().map(line -> {
            Matcher m = Pattern.compile("^# ([a-z-]+):( .*)?$").matcher(line);
            if (m.matches() && keys.contains(m.group(1))) {
                return line.substring(2);
            }
            if (line.startsWith("#   ")) {
                return line.substring(2);
            }
            return line;
        }).collect(Collectors.joining("\n"));
    }

    @Test
    void everyTemplateIsRefusedUntilFilledThenImportsAsIs() {
        for (var entry : SpecialMobSchema.templates().entrySet()) {
            SpecialMobImport.Check raw = SpecialMobImport.check(entry.getValue(), REFS, store);
            assertFalse(raw.valid(), entry.getKey());
            assertTrue(raw.errors().stream().anyMatch(e -> e.contains("« id »")), entry.getKey() + " " + raw.errors());

            String text = filled(entry.getValue(), "modele_" + entry.getKey().replace('-', '_'), "Essai");
            SpecialMobImport.Check c = SpecialMobImport.check(text, REFS, store);
            assertTrue(c.valid(), entry.getKey() + " : " + c.errors());
            assertFalse(c.exists());
        }
        SpecialMobImport.Check community = SpecialMobImport.check(
                filled(SpecialMobSchema.templates().get("boss-community"), "x", "X"), REFS, store);
        assertEquals(MobCategory.BOSS, community.definition().category());
        assertEquals(BossMode.COMMUNITY, community.definition().bossMode());
    }

    @Test
    void everyDocumentedFieldAndEveryAbilityOfTheTemplateIsAcceptedByTheParser() {
        String text = everythingUncommented(filled(SpecialMobSchema.templates().get("boss-community"),
                "tout_le_contrat", "Tout"))
                .replaceFirst("(?m)^entity-type: ZOMBIE$", "entity-type: CREEPER")
                .replace("rpgquest:mon_objet", "rpgquest:dent_de_loup");
        SpecialMobImport.Check c = SpecialMobImport.check(text, REFS, store);
        assertTrue(c.valid(), c.errors().toString());
        SpecialMobDefinition d = c.definition();
        assertEquals(Set.of(MobAbilityType.values()), d.abilities().stream().map(a -> a.type()).collect(Collectors.toSet()));
        assertEquals(2, d.drops().size());
        assertTrue(d.health() != null && d.scale() != null && d.creeperExplosionRadius() != null
                && d.particle() != null && d.sound() != null && d.maxPopulation() != null);
    }

    /** Le contrat ne peut pas devenir plus pauvre que le moteur : chaque clé lue par le parseur est décrite. */
    @Test
    void schemaDescribesEveryKeyTheRealParserReads() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/lodygames/rpgquest/mob/SpecialMobDefinitionParser.java"));
        Set<String> read = new HashSet<>();
        Matcher m = Pattern.compile("(?:section, |section\\.\\w+\\()\"([a-z][a-z-]*)\"").matcher(source);
        while (m.find()) {
            read.add(m.group(1));
        }
        // Issue #542 : les clés des capacités ne sont plus lues par le parseur mais par MobAbilityCatalog,
        // dont SpecialMobSchema.abilityKeys() est une vue directe (voir MobAbilityCatalogTest).
        Set<String> described = new HashSet<>(SpecialMobSchema.topLevelKeys());
        described.addAll(SpecialMobSchema.DROP_KEYS);
        assertTrue(read.size() > 25, "lecture du parseur : " + read);
        Set<String> missing = new HashSet<>(read);
        missing.removeAll(described);
        assertEquals(Set.of(), missing, "clés lues par le parseur mais absentes du modèle");
        Set<String> invented = new HashSet<>(described);
        invented.removeAll(read);
        // « abilities » et « drops » sont lues via getMapList(...) sur des variables : présentes dans le source.
        assertEquals(Set.of(), invented, "clés du modèle que le parseur ne lit pas");
    }

    @Test
    void trainingZombieIsANewValidCommunityBoss() {
        SpecialMobImport.Check c = SpecialMobImport.check(TRAINING_ZOMBIE, REFS, store);
        assertTrue(c.valid(), c.errors().toString());
        assertFalse(c.exists());
        assertEquals("rpgquest:boss_zombie_entrainement", c.definition().id().asString());
        assertEquals(20.0, c.definition().health());
        assertEquals(0.20, c.definition().speed());
        assertTrue(c.canonical().contains("name: \"Zombie d'entraînement\""));
        assertTrue(c.definition().abilities().isEmpty() && c.definition().participationRewards().isEmpty());
    }

    @Test
    void inventedPropertiesAreRefusedNotIgnored() {
        SpecialMobImport.Check c = SpecialMobImport.check(TRAINING_ZOMBIE + """
                fly-speed: 2
                abilities:
                  - type: ENRAGED
                    health-fraction: 0.3
                    speed-multiplier: 1.2
                    damage-multiplier: 1.2
                    roar: true
                drops:
                  - material: BONE
                    weight: 1
                    min-amount: 1
                    max-amount: 1
                    rarity: epic
                """, REFS, store);
        assertFalse(c.valid());
        assertTrue(c.errors().stream().anyMatch(e -> e.contains("« fly-speed »")), c.errors().toString());
        assertTrue(c.errors().stream().anyMatch(e -> e.contains("« roar »")));
        assertTrue(c.errors().stream().anyMatch(e -> e.contains("« rarity »")));
        assertEquals("", c.canonical());
    }

    @Test
    void unknownReferencesAreReadableErrors() {
        String text = TRAINING_ZOMBIE.replace("worlds: [wild]", "worlds: [nether_du_boss]")
                .replace("biomes: []", "biomes: [lune]")
                .replace("participation-rewards: []", "participation-rewards: [badge_boss, inconnue, table:coffre_boss]")
                + "drops:\n  - custom-item: rpgquest:objet_fantome\n    weight: 1\n    min-amount: 1\n    max-amount: 1\n";
        SpecialMobImport.Check c = SpecialMobImport.check(text, REFS, store);
        assertFalse(c.valid());
        String all = String.join("\n", c.errors());
        assertTrue(all.contains("nether_du_boss") && all.contains("lune") && all.contains("« inconnue »")
                && all.contains("objet_fantome"), all);
        assertFalse(all.contains("badge_boss") || all.contains("coffre_boss"), all);
    }

    @Test
    void unreadableYamlAndParserErrorsAreRefused() {
        assertFalse(SpecialMobImport.check("id: [x\n  name: : :", REFS, store).valid());
        SpecialMobImport.Check c = SpecialMobImport.check(TRAINING_ZOMBIE.replace("speed: 0.20", "speed: -1"), REFS, store);
        assertFalse(c.valid());
        assertTrue(c.errors().stream().anyMatch(e -> e.contains("« speed »")), c.errors().toString());
        assertFalse(SpecialMobImport.check("", REFS, store).valid());
        assertFalse(SpecialMobImport.check("x".repeat(SpecialMobImport.MAX_CHARS + 1), REFS, store).valid());
    }

    @Test
    void importCreatesOnlyTheTargetProfile() throws IOException {
        Files.writeString(mobs.resolve("claude_diag_boss.yml"), "id: claude_diag_boss\ncategory: BOSS\n"
                + "entity-type: ZOMBIE\nname: \"Diag\"\nspawn-chance: 0\n");
        byte[] diag = Files.readAllBytes(mobs.resolve("claude_diag_boss.yml"));
        SpecialMobImport.Check c = SpecialMobImport.check(TRAINING_ZOMBIE, REFS, store);
        SpecialMobDefinitionStore.Result r = store.importDefinition(c.definition(), false, "", backups);
        assertTrue(r.ok(), r.message());
        assertEquals("CREATED", r.code());
        assertEquals(c.canonical(), Files.readString(mobs.resolve("boss_zombie_entrainement.yml")));
        assertArrayEquals(diag, Files.readAllBytes(mobs.resolve("claude_diag_boss.yml")));
        assertFalse(Files.exists(backups));
    }

    @Test
    void collisionNeedsExplicitReplaceAndTheVerifiedFingerprint() throws IOException {
        SpecialMobImport.Check first = SpecialMobImport.check(TRAINING_ZOMBIE, REFS, store);
        assertTrue(store.importDefinition(first.definition(), false, "", backups).ok());
        Path file = mobs.resolve("boss_zombie_entrainement.yml");
        byte[] before = Files.readAllBytes(file);

        String changed = TRAINING_ZOMBIE.replace("health: 20.0", "health: 40.0");
        SpecialMobImport.Check c = SpecialMobImport.check(changed, REFS, store);
        assertTrue(c.valid() && c.exists());
        assertEquals("boss_zombie_entrainement.yml", c.existingFile());
        assertEquals(first.canonical(), c.existingCanonical());

        SpecialMobDefinitionStore.Result noConsent = store.importDefinition(c.definition(), false, c.existingSha(), backups);
        assertEquals("EXISTS", noConsent.code());
        assertArrayEquals(before, Files.readAllBytes(file));

        SpecialMobDefinitionStore.Result stale = store.importDefinition(c.definition(), true, "0".repeat(64), backups);
        assertEquals("CONFLICT", stale.code());
        assertArrayEquals(before, Files.readAllBytes(file));

        SpecialMobDefinitionStore.Result ok = store.importDefinition(c.definition(), true, c.existingSha(), backups);
        assertTrue(ok.ok(), ok.message());
        assertEquals("REPLACED", ok.code());
        assertEquals(40.0, store.find(c.definition().id()).orElseThrow().health());
        try (Stream<Path> saved = Files.walk(backups)) {
            List<Path> copies = saved.filter(Files::isRegularFile).toList();
            assertEquals(1, copies.size());
            assertArrayEquals(before, Files.readAllBytes(copies.get(0)), "ancienne version récupérable");
        }
        assertTrue(ok.message().contains("mob-backups/"));
    }

    @Test
    void aProfileThatAppearedOrVanishedSinceTheCheckIsAConflict() throws IOException {
        SpecialMobImport.Check c = SpecialMobImport.check(TRAINING_ZOMBIE, REFS, store);
        assertEquals("CONFLICT", store.importDefinition(c.definition(), true, "a".repeat(64), backups).code());
        assertFalse(Files.exists(mobs.resolve("boss_zombie_entrainement.yml")));
        // apparu entre la vérification (nouveau) et la confirmation : pas d'écrasement implicite
        Files.writeString(mobs.resolve("boss_zombie_entrainement.yml"), TRAINING_ZOMBIE.replace("20.0", "99.0"),
                StandardCharsets.UTF_8);
        assertEquals("EXISTS", store.importDefinition(c.definition(), false, "", backups).code());
        assertEquals(99.0, store.find(c.definition().id()).orElseThrow().health());
    }

    @Test
    void advancedAbilitiesSurviveExportAndReimportUnchanged() {
        String text = TRAINING_ZOMBIE + """
                abilities:
                  - type: SPLIT_ON_HIT
                    max-depth: 2
                    max-children-per-hit: 2
                    max-alive-per-parent: 3
                  - type: EXPLOSIVE_ON_ATTACK
                    power: 1.5
                    set-fire: true
                    trigger-range-blocks: 2.0
                """;
        SpecialMobImport.Check c = SpecialMobImport.check(text, REFS, store);
        assertTrue(c.valid(), c.errors().toString());
        assertTrue(store.importDefinition(c.definition(), false, "", backups).ok());
        String exported = SpecialMobDefinitionYaml.render(store.find(c.definition().id()).orElseThrow());
        SpecialMobImport.Check again = SpecialMobImport.check(exported, REFS, store);
        assertTrue(again.valid(), again.errors().toString());
        assertEquals(again.existingCanonical(), again.canonical(), "réimport d'un export = aucune différence");
        assertEquals(c.definition().abilities(), again.definition().abilities());
    }

    @Test
    void strongerExplosionOnANonCreeperIsAWarningNotARefusal() {
        SpecialMobImport.Check c = SpecialMobImport.check(TRAINING_ZOMBIE
                + "abilities:\n  - type: STRONGER_EXPLOSION\n    radius-multiplier: 2.0\n", REFS, store);
        assertTrue(c.valid());
        assertTrue(c.warnings().stream().anyMatch(w -> w.contains("STRONGER_EXPLOSION")));
        assertNull(c.existingFile());
    }
}
