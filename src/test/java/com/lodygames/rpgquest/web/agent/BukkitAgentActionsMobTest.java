package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.mob.MobSpawnSettingsStore;
import com.lodygames.rpgquest.mob.SpecialMobDefinitionStore;
import com.lodygames.rpgquest.mob.SpecialMobRegistry;
import com.lodygames.rpgquest.ops.ServerLogBuffer;
import com.lodygames.rpgquest.ops.ServerOpsService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Teste {@link BukkitAgentActions#mobDefinitionCreate}/{@code Update}/{@code Toggle} contre une
 * <strong>vraie</strong> implémentation (jamais seulement {@code FakeAgentActions}, qui ne vérifie
 * que le dispatch de {@link AgentActionExecutor}, pas ce que fait réellement la façade) — issue
 * #190 : reproduit exactement le formulaire « Nouveau profil » du Control Panel pour vérifier que
 * la création fonctionne vraiment, y compris sur une base passive (cochon/poule/grenouille).
 */
class BukkitAgentActionsMobTest {

    @TempDir
    Path tempDir;

    private RPGQuestPlugin plugin;
    private BukkitAgentActions actions;
    private SpecialMobRegistry mobRegistry;
    private Path mobsDir;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        mobsDir = tempDir.resolve("mobs");
        Files.createDirectories(mobsDir);
        mobRegistry = new SpecialMobRegistry(mobsDir, plugin.getSLF4JLogger());
        mobRegistry.reload();
        SpecialMobDefinitionStore mobDefinitionStore = new SpecialMobDefinitionStore(mobsDir);
        MobSpawnSettingsStore mobSpawnSettingsStore = new MobSpawnSettingsStore(mobsDir, plugin.getSLF4JLogger());

        actions = new BukkitAgentActions(plugin, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, mobRegistry, null, mobDefinitionStore, mobSpawnSettingsStore,
                () -> "wild",
                () -> new com.lodygames.rpgquest.config.HubConfig("world_hub"),
                new ServerOpsService(plugin, new ServerLogBuffer(50), java.util.Optional::empty),
                null, null, java.util.Optional::empty, null, null, null, null, null, null);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private AgentActions.MutationResult create(String id, String entityType) throws Exception {
        return actions.mobDefinitionCreate(id, "SPECIAL", true, entityType, "Test " + entityType, 0.01,
                List.of(), List.of(), List.of(), null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null).get();
    }

    @Test
    void createsAMinimalProfileExactlyAsTheNewProfileFormSubmitsIt() throws Exception {
        AgentActions.MutationResult r = create("swamp_king", "ZOMBIE");
        assertTrue(r.ok(), r.message());
        assertEquals("CREATED", r.code());
        assertTrue(Files.exists(mobsDir.resolve("swamp_king.yml")));
        mobRegistry.reload();
        assertTrue(mobRegistry.find(new NamespacedKey("rpgquest", "swamp_king")).isPresent());
    }

    @Test
    void createsAProfileOnEachCompatiblePassiveBase() throws Exception {
        for (String entityType : new String[] {"PIG", "CHICKEN", "FROG"}) {
            String id = "passive_" + entityType.toLowerCase(Locale.ROOT);
            AgentActions.MutationResult r = create(id, entityType);
            assertTrue(r.ok(), entityType + " : " + r.message());
            assertEquals("CREATED", r.code());
        }
    }

    @Test
    void updateThenToggleOnAnExistingProfileBothSucceed() throws Exception {
        assertTrue(create("swamp_king", "ZOMBIE").ok());

        AgentActions.MutationResult updated = actions.mobDefinitionUpdate(
                "swamp_king", "SPECIAL", true, "ZOMBIE", "Roi des Marais (modifié)", 0.02,
                List.of(), List.of(), List.of(), null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null).get();
        assertTrue(updated.ok(), updated.message());
        assertEquals("UPDATED", updated.code());

        AgentActions.MutationResult toggled = actions.mobDefinitionToggle("swamp_king", false).get();
        assertTrue(toggled.ok(), toggled.message());
        mobRegistry.reload();
        assertTrue(mobRegistry.find(new NamespacedKey("rpgquest", "swamp_king"))
                .map(def -> !def.enabled()).orElse(false));
    }

    private AgentActions.MutationResult writeBoss(boolean create, List<String> participationRewards) throws Exception {
        return (create ? actions.mobDefinitionCreate("ogre", "BOSS", true, "ZOMBIE", "Ogre", 0.0,
                List.of(), List.of(), List.of(), null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, "COMMUNITY", 1, participationRewards)
                : actions.mobDefinitionUpdate("ogre", "BOSS", true, "ZOMBIE", "Ogre", 0.0,
                List.of(), List.of(), List.of(), null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, "COMMUNITY", 1, participationRewards)).get();
    }

    private List<String> ogreRewards() {
        mobRegistry.reload();
        return mobRegistry.find(new NamespacedKey("rpgquest", "ogre")).orElseThrow().participationRewards();
    }

    @Test
    void participationRewardsAreKeptWhenAnOlderPanelOmitsThemAndClearedWhenSentEmpty() throws Exception {
        assertTrue(writeBoss(true, List.of("boss_ogre")).ok());
        assertEquals(List.of("boss_ogre"), ogreRewards());

        assertTrue(writeBoss(false, null).ok(), "paramètre absent (ancien panel)");
        assertEquals(List.of("boss_ogre"), ogreRewards(), "conservées, comme la table de drops");

        assertTrue(writeBoss(false, List.of()).ok());
        assertTrue(ogreRewards().isEmpty(), "champ vidé = retirées");
    }

    @Test
    void creatingTheSameIdTwiceFailsReadably() throws Exception {
        assertTrue(create("swamp_king", "ZOMBIE").ok());
        AgentActions.MutationResult second = create("swamp_king", "ZOMBIE");
        assertEquals(false, second.ok());
        assertEquals("EXISTS", second.code());
    }

    // ---- Issue #461 -------------------------------------------------------------------------

    @Test
    void anEditKeepsAbilitiesAndDropsTheFormDoesNotExposeAndWritesBothBiomes() throws Exception {
        Files.writeString(mobsDir.resolve("splitting_zombie.yml"), """
                id: rpgquest:splitting_zombie
                category: BOSS
                enabled: false
                entity-type: ZOMBIE
                name: "Zombie Fissible"
                spawn-chance: 0.02
                abilities:
                  - type: SPLIT_ON_HIT
                    max-depth: 2
                    max-children-per-hit: 2
                    max-alive-per-parent: 2
                drops:
                  - material: ROTTEN_FLESH
                    weight: 100
                    min-amount: 1
                    max-amount: 2
                """);

        AgentActions.MutationResult r = actions.mobDefinitionUpdate("rpgquest:splitting_zombie", "BOSS", true,
                "ZOMBIE", "Zombie Fissible", 0.02, List.of("wild"), List.of("swamp", "plains"), List.of(),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, "COMMUNITY", 1, List.of()).get();

        assertTrue(r.ok(), r.message());
        mobRegistry.reload();
        var def = mobRegistry.find(new NamespacedKey("rpgquest", "splitting_zombie")).orElseThrow();
        assertEquals(java.util.Set.of("swamp", "plains"), def.allowedBiomes());
        assertEquals(1, def.abilities().size(), "Division conservée (non éditée par le formulaire)");
        assertTrue(def.abilities().get(0) instanceof com.lodygames.rpgquest.mob.model.SplitOnHitAbility);
        assertEquals(1, def.drops().size(), "drops conservés");
    }

    @Test
    void anEditOfAnUnreadableProfileIsRefusedAndTheFileIsNotTouched() throws Exception {
        String broken = "id: rpgquest:claude_diag_boss\ncategory: BOSS\nentity-type: WITHER_SKELETON\n"
                + "name: \"Diag\"\nspawn-chance: 0.01\nbiomes:\n  - swamp\r\nplains\nzones: []\n";
        Files.writeString(mobsDir.resolve("claude_diag_boss.yml"), broken);

        AgentActions.MutationResult r = actions.mobDefinitionUpdate("rpgquest:claude_diag_boss", "BOSS", true,
                "WITHER_SKELETON", "Diag", 0.01, List.of("wild"), List.of("swamp", "plains"), List.of(),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, "COMMUNITY", 1, List.of()).get();

        assertEquals(false, r.ok());
        assertEquals("CURRENT_UNREADABLE", r.code());
        assertEquals(broken, Files.readString(mobsDir.resolve("claude_diag_boss.yml")));
    }

    @Test
    void theRepairedDiagBossFileLoadsAndCanBeSavedUnchangedAgain() throws Exception {
        // Contenu exact remis sur DEV pour réparer « Diag Boss » (issue #461).
        Files.writeString(mobsDir.resolve("claude_diag_boss.yml"), """
                id: rpgquest:claude_diag_boss
                category: BOSS
                enabled: true
                boss-mode: COMMUNITY
                community-weight: 1
                entity-type: WITHER_SKELETON
                name: "<red>Diag Boss</red>"
                spawn-chance: 0.01
                worlds:
                  - "wild"
                biomes:
                  - "swamp"
                  - "plains"
                zones: []
                health: 200.0
                particle: DUST
                sound: ENTITY.WITHER.SPAWN
                """);
        mobRegistry.reload();
        var def = mobRegistry.find(new NamespacedKey("rpgquest", "claude_diag_boss")).orElseThrow();
        assertEquals(java.util.Set.of("swamp", "plains"), def.allowedBiomes());
        assertEquals(com.lodygames.rpgquest.mob.model.BossMode.COMMUNITY, def.bossMode());

        AgentActions.MutationResult r = actions.mobDefinitionUpdate("rpgquest:claude_diag_boss", "BOSS", true,
                "WITHER_SKELETON", "<red>Diag Boss</red>", 0.01, List.of("wild"), List.of("swamp", "plains"),
                List.of(), 200.0, null, null, null, null, null, null, "DUST", "ENTITY.WITHER.SPAWN", null, null,
                null, null, null, null, null, null, null, null, "COMMUNITY", 1, List.of()).get();
        assertTrue(r.ok(), r.message());
        mobRegistry.reload();
        assertEquals(def, mobRegistry.find(new NamespacedKey("rpgquest", "claude_diag_boss")).orElseThrow());
    }

    // ---- Issue #543 : liste structurée des cartes de capacités --------------------------------------

    private AgentActions.MutationResult writeWithAbilities(boolean create, List<java.util.Map<String, Object>> abilities)
            throws Exception {
        return actions.mobDefinitionWrite(create, "golem", "BOSS", true, "ZOMBIE", "Golem", 0.0,
                List.of(), List.of(), List.of(), null, null, null, null, null, null, null, null, null,
                null, null, 0.3, 1.3, 1.5, null, null, null, null, null, "QUEST", 1, List.of(), abilities).get();
    }

    private static java.util.Map<String, Object> card(String type, String... keyValues) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("type", type);
        for (int i = 0; i < keyValues.length; i += 2) {
            out.put(keyValues[i], keyValues[i + 1]);
        }
        return out;
    }

    @Test
    void structuredAbilitiesReplaceTheListInOrderAndIgnoreFlatFields() throws Exception {
        AgentActions.MutationResult r = writeWithAbilities(true, List.of(
                card("SUMMON_ON_DAMAGE", "summon-entity-type", "ZOMBIE", "amount", "2", "chance", "0.25",
                        "cooldown-seconds", "20", "max-alive", "4", "enabled", "false"),
                card("SPLIT_ON_HIT", "max-depth", "2", "max-children-per-hit", "2", "max-alive-per-parent", "2")));
        assertTrue(r.ok(), r.message());
        String yaml = Files.readString(mobsDir.resolve("golem.yml"));
        int summon = yaml.indexOf("type: SUMMON_ON_DAMAGE");
        int split = yaml.indexOf("type: SPLIT_ON_HIT");
        assertTrue(summon > 0 && split > summon, "ordre des cartes conservé :\n" + yaml);
        assertTrue(yaml.contains("enabled: false"), "désactivée, valeurs gardées :\n" + yaml);
        assertTrue(!yaml.contains("ENRAGED"), "les champs plats Enragé sont ignorés quand la liste est envoyée");

        assertTrue(writeWithAbilities(false, List.of()).ok());
        assertTrue(!Files.readString(mobsDir.resolve("golem.yml")).contains("abilities"), "liste vide = aucune capacité");
    }

    @Test
    void structuredAbilitiesRefusedLeaveTheFileUntouched() throws Exception {
        assertTrue(writeWithAbilities(true, List.of(card("ENRAGED", "health-fraction", "0.3",
                "speed-multiplier", "1.3", "damage-multiplier", "1.5"))).ok());
        String before = Files.readString(mobsDir.resolve("golem.yml"));

        AgentActions.MutationResult duplicate = writeWithAbilities(false, List.of(
                card("ENRAGED", "health-fraction", "0.3", "speed-multiplier", "1.3", "damage-multiplier", "1.5"),
                card("ENRAGED", "health-fraction", "0.5", "speed-multiplier", "1.3", "damage-multiplier", "1.5")));
        assertEquals("INVALID_ABILITIES", duplicate.code(), duplicate.message());
        assertTrue(duplicate.message().contains("en double"), duplicate.message());

        AgentActions.MutationResult outOfBounds = writeWithAbilities(false, List.of(
                card("ENRAGED", "health-fraction", "2", "speed-multiplier", "1.3", "damage-multiplier", "1.5")));
        assertEquals("INVALID_ABILITIES", outOfBounds.code());
        assertTrue(outOfBounds.message().contains("« Enragé »"), outOfBounds.message());

        assertEquals(before, Files.readString(mobsDir.resolve("golem.yml")), "rien n'a été écrit");
    }
}
