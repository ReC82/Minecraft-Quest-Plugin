package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Vérifie que l'identité d'un PNJ est indépendante de son nom personnalisé
 * (cosmétique) — c'est le comportement dont l'absence provoquait
 * l'IllegalArgumentException historique dans DialogueNpcInteractListener.
 */
class NpcIdentityServiceTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World world;
    private DatabaseManager database;
    private NpcIdentityService service;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        world = server.addSimpleWorld("world");

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        service = new NpcIdentityService(plugin, new NpcIdRepository(database), new NpcBindingRepository(database));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void untaggedEntityHasNoCurrentId() {
        LivingEntity entity = spawnZombie();
        assertTrue(service.currentId(entity).isEmpty());
    }

    @Test
    void taggingWithExplicitIdStoresItImmediately() throws Exception {
        LivingEntity entity = spawnZombie();

        NpcIdentityService.TagResult result = service.tag(entity, "guard").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(result.created());
        assertEquals("guard", result.npcId());
        assertEquals("guard", service.currentId(entity).orElseThrow());
    }

    @Test
    void taggingWithoutExplicitIdGeneratesSequentialFallback() throws Exception {
        LivingEntity first = spawnZombie();
        LivingEntity second = spawnZombie();

        String firstId = await(service.tag(first, null)).npcId();
        String secondId = await(service.tag(second, null)).npcId();

        assertTrue(firstId.startsWith("npc_"));
        assertTrue(secondId.startsWith("npc_"));
        org.junit.jupiter.api.Assertions.assertNotEquals(firstId, secondId);
    }

    @Test
    void reTaggingAnAlreadyTaggedEntityKeepsTheExistingId() throws Exception {
        LivingEntity entity = spawnZombie();
        service.tag(entity, "guard").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        NpcIdentityService.TagResult second = service.tag(entity, "someone_else").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertFalse(second.created());
        assertEquals("guard", second.npcId());
        assertEquals("guard", service.currentId(entity).orElseThrow());
    }

    @Test
    void untagRemovesTheIdAndAllowsRetaggingWithADifferentId() throws Exception {
        LivingEntity entity = spawnZombie();
        service.tag(entity, "guard").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(service.untag(entity).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(service.currentId(entity).isEmpty());
        assertFalse(service.untag(entity).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        NpcIdentityService.TagResult retag = service.tag(entity, "merchant").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("merchant", retag.npcId());
    }

    @Test
    void renamingTheEntityDoesNotAffectItsStableId() throws Exception {
        LivingEntity entity = spawnZombie();
        service.tag(entity, "guard").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Un nom personnalisé arbitraire (majuscule, espace) aurait fait lever une exception dans
        // l'ancienne implémentation, qui le convertissait directement en NamespacedKey.
        entity.customName(Component.text("Guide du village"));

        assertEquals("guard", service.currentId(entity).orElseThrow());
    }

    @Test
    void citizensIsUnavailableWhenThePluginIsNotInstalled() {
        // Non-régression : sans Citizens installé (cas de cet environnement de test), le service doit
        // se comporter exactement comme avant son introduction — jamais tenter de charger un type
        // Citizens, jamais classer une entité vanilla comme PNJ Citizens.
        assertFalse(service.citizensAvailable());
        assertFalse(service.isCitizensNpc(spawnZombie()));
        assertTrue(service.citizensNumericId(spawnZombie()).isEmpty());
    }

    @Test
    void namesWithCharactersInvalidForANamespacedKeyAreRejected() {
        assertFalse(NpcIdentityService.isValidId("Guide"));
        assertFalse(NpcIdentityService.isValidId("guide du village"));
        assertTrue(NpcIdentityService.isValidId("guard"));
        assertTrue(NpcIdentityService.isValidId("woodcutter_bob"));
    }

    private LivingEntity spawnZombie() {
        return (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);
    }

    /**
     * L'allocation d'id auto-généré traverse le thread base de données puis revient sur le thread
     * principal via {@code Bukkit.getScheduler().runTask(...)} : contrairement au chemin id explicite
     * (déjà complet de façon synchrone), il faut faire avancer le scheduler MockBukkit pour que la
     * continuation s'exécute — un simple {@code future.get(...)} bloquerait indéfiniment ce thread,
     * qui est le seul capable de faire avancer ce même scheduler.
     */
    private NpcIdentityService.TagResult await(java.util.concurrent.CompletableFuture<NpcIdentityService.TagResult> future)
            throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
