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
                null, null, null, null, null, null, mobRegistry, null, mobDefinitionStore, mobSpawnSettingsStore,
                () -> "wild",
                new ServerOpsService(plugin, new ServerLogBuffer(50), java.util.Optional::empty),
                null, java.util.Optional::empty, null, null);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private AgentActions.MutationResult create(String id, String entityType) throws Exception {
        return actions.mobDefinitionCreate(id, "SPECIAL", true, entityType, "Test " + entityType, 0.01,
                List.of(), List.of(), List.of(), null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null).get();
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
                null, null, null, null, null, null, null, null, null, null).get();
        assertTrue(updated.ok(), updated.message());
        assertEquals("UPDATED", updated.code());

        AgentActions.MutationResult toggled = actions.mobDefinitionToggle("swamp_king", false).get();
        assertTrue(toggled.ok(), toggled.message());
        mobRegistry.reload();
        assertTrue(mobRegistry.find(new NamespacedKey("rpgquest", "swamp_king"))
                .map(def -> !def.enabled()).orElse(false));
    }

    @Test
    void creatingTheSameIdTwiceFailsReadably() throws Exception {
        assertTrue(create("swamp_king", "ZOMBIE").ok());
        AgentActions.MutationResult second = create("swamp_king", "ZOMBIE");
        assertEquals(false, second.ok());
        assertEquals("EXISTS", second.code());
    }
}
