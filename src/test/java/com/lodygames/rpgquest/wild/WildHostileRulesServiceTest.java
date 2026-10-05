package com.lodygames.rpgquest.wild;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.config.WildConfig;
import java.util.List;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Issue #168 — périmètre (mondes Wild uniquement) et immunité au soleil <strong>sans</strong>
 * immunité au feu/à la lave.
 *
 * <p>Ce que ce fichier <strong>ne</strong> prouve pas, et qui reste à valider en jeu : les
 * apparitions diurnes réelles et le ciblage effectif des araignées. MockBukkit ne simule ni le
 * spawn naturel, ni la ligne de vue, ni l'IA de ciblage — un test « vert » ici ne vaut donc jamais
 * validation de ces deux comportements (voir le plan de test manuel).</p>
 */
class WildHostileRulesServiceTest {

    private ServerMock server;
    private org.bukkit.plugin.Plugin plugin;
    private World wild;
    private World hub;
    private WildHostileRulesService service;

    private static WildConfig config(List<String> worlds, boolean sunImmunity) {
        return new WildConfig(worlds, sunImmunity, true,
                new WildConfig.DaylightSpawnConfig(true, 100, 6, 24, 48, 12, 120,
                        List.of(EntityType.ZOMBIE, EntityType.SKELETON)));
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        // Volontairement un plugin factice, PAS MockBukkit.load(RPGQuestPlugin) : charger le vrai
        // plugin démarrerait son bootstrap, qui enregistre lui aussi ce service avec la config par
        // défaut — deux exemplaires répondraient alors au même événement et le test ne prouverait
        // plus rien sur la configuration qu'il croit tester.
        plugin = MockBukkit.createMockPlugin("WildRulesTest");
        wild = server.addSimpleWorld("wild");
        hub = server.addSimpleWorld("world_hub");
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.stop();
        }
        MockBukkit.unmock();
    }

    private void startWith(WildConfig cfg) {
        service = new WildHostileRulesService((org.bukkit.plugin.java.JavaPlugin) plugin, () -> cfg, () -> "wild");
        service.start();
    }

    @Test
    void scopeCoversOnlyConfiguredWildWorlds() {
        startWith(config(List.of("wild"), true));

        assertTrue(service.isWildWorld(wild));
        assertFalse(service.isWildWorld(hub), "le Hub n'est jamais concerné");
        assertFalse(service.isWildWorld(null));
    }

    @Test
    void anEmptyWorldListFallsBackToTheSingleConfiguredWildWorld() {
        startWith(config(List.of(), true));

        assertTrue(service.isWildWorld(wild), "repli sur travel.wild-world");
        assertFalse(service.isWildWorld(hub));
    }

    @Test
    void severalWildWorldsAreSupported() {
        World second = server.addSimpleWorld("wild_nether");
        startWith(config(List.of("wild", "wild_nether"), true));

        assertTrue(service.isWildWorld(wild));
        assertTrue(service.isWildWorld(second));
        assertFalse(service.isWildWorld(hub));
    }

    @Test
    void sunlightCombustionIsCancelledForAMonsterInTheWild() {
        startWith(config(List.of("wild"), true));
        Zombie zombie = (Zombie) wild.spawnEntity(wild.getSpawnLocation(), EntityType.ZOMBIE);

        EntityCombustEvent event = new EntityCombustEvent(zombie, 8);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "l'embrasement par le soleil doit être annulé dans le Wild");
    }

    /** Le cœur du ticket : on distingue le soleil des autres causes de feu. */
    @Test
    void fireAndLavaCombustionStayNormalEvenInTheWild() {
        startWith(config(List.of("wild"), true));
        Zombie zombie = (Zombie) wild.spawnEntity(wild.getSpawnLocation(), EntityType.ZOMBIE);
        wild.getBlockAt(0, 60, 0).setType(org.bukkit.Material.LAVA);

        EntityCombustByBlockEvent byBlock =
                new EntityCombustByBlockEvent(wild.getBlockAt(0, 60, 0), zombie, 8);
        server.getPluginManager().callEvent(byBlock);
        assertFalse(byBlock.isCancelled(), "la lave/le feu doivent continuer d'enflammer normalement");

        Zombie attacker = (Zombie) wild.spawnEntity(wild.getSpawnLocation(), EntityType.ZOMBIE);
        EntityCombustByEntityEvent byEntity = new EntityCombustByEntityEvent(attacker, zombie, 8);
        server.getPluginManager().callEvent(byEntity);
        assertFalse(byEntity.isCancelled(), "un attaquant enflammé doit continuer d'enflammer");
    }

    @Test
    void sunlightCombustionIsUntouchedOutsideTheWild() {
        startWith(config(List.of("wild"), true));
        Zombie zombie = (Zombie) hub.spawnEntity(hub.getSpawnLocation(), EntityType.ZOMBIE);

        EntityCombustEvent event = new EntityCombustEvent(zombie, 8);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "hors Wild, les règles vanilla restent intactes");
    }

    @Test
    void sunImmunityCanBeTurnedOffByConfiguration() {
        startWith(config(List.of("wild"), false));
        Zombie zombie = (Zombie) wild.spawnEntity(wild.getSpawnLocation(), EntityType.ZOMBIE);

        EntityCombustEvent event = new EntityCombustEvent(zombie, 8);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "sun-immunity=false doit rendre le comportement vanilla");
    }

    @Test
    void nonMonsterEntitiesAreNeverProtectedFromTheSun() {
        startWith(config(List.of("wild"), true));
        org.bukkit.entity.Entity cow = wild.spawnEntity(wild.getSpawnLocation(), EntityType.COW);

        EntityCombustEvent event = new EntityCombustEvent(cow, 8);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "un animal passif ne relève pas de cette règle");
    }
}
