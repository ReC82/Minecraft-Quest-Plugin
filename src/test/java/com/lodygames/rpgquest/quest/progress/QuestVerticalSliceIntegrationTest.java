package com.lodygames.rpgquest.quest.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.quest.model.QuestState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Le « vertical slice » complet du moteur (quête → combat → récolte → fabrication → remise →
 * récompense) sur le plugin <b>réellement</b> démarré via {@code MockBukkit.load(RPGQuestPlugin.class)} :
 * objets personnalisés, commandes de récompense et droit au premier claim sont ceux du jar.
 *
 * <p>Issue #778 : ce test s'appuyait sur les quêtes embarquées {@code first_steps} et
 * {@code crystal_hunt}, retirées définitivement. Les deux quêtes sont désormais des
 * <em>fixtures</em> écrites dans le dossier de quêtes du plugin, avec exactement les mêmes
 * objectifs et récompenses : la couverture du moteur est identique, sans dépendre d'un contenu
 * de jeu. Le second test garde la règle de retrait : une progression vers une quête qui n'existe
 * plus est tolérée — ni erreur, ni récompense, ni purge de la ligne du joueur.</p>
 */
class QuestVerticalSliceIntegrationTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey INTRO = new NamespacedKey("rpgquest", "test_slice_intro");
    private static final NamespacedKey HUNT = new NamespacedKey("rpgquest", "test_slice_hunt");
    private static final NamespacedKey MINER_PICKAXE = new NamespacedKey("rpgquest", "miner_pickaxe");

    private static final String INTRO_YAML = """
            id: rpgquest:test_slice_intro
            title: "Intro de test"
            description: "Fixture de test"
            category: test
            repeatable: false
            steps:
              - id: kill_spiders
                objectives:
                  - type: KILL_ENTITY
                    entity: SPIDER
                    amount: 10
            rewards:
              - type: EXPERIENCE
                amount: 50
            """;

    private static final String HUNT_YAML = """
            id: rpgquest:test_slice_hunt
            title: "Chasse de test"
            description: "Fixture de test"
            category: test
            giver: guard
            repeatable: false
            prerequisites:
              - rpgquest:test_slice_intro
            steps:
              - id: hunt_spiders
                objectives:
                  - type: KILL_ENTITY
                    entity: SPIDER
                    amount: 5
              - id: gather_crystals
                objectives:
                  - type: COLLECT_ITEM
                    material: AMETHYST_SHARD
                    amount: 2
              - id: forge_blade
                objectives:
                  - type: CRAFT_ITEM
                    material: DIAMOND_SWORD
                    amount: 1
              - id: report_to_guard
                objectives:
                  - type: TALK_TO_NPC
                    npc: guard
            rewards:
              - type: EXPERIENCE
                amount: 100
              - type: COMMAND
                command: "customitem give %player% rpgquest:miner_pickaxe 1"
              - type: VARIABLE
                key: CLAIM_TIER_1
                value: "true"
            """;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private QuestProgressEngine engine;
    private YamlCustomItemRegistry itemRegistry;
    private Path questsDir;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        engine = plugin.bootstrap().questProgressEngine();
        itemRegistry = plugin.bootstrap().customItemRegistry();
        questsDir = plugin.getDataFolder().toPath().resolve("quests");
        Files.writeString(questsDir.resolve("test_slice_intro.yml"), INTRO_YAML);
        Files.writeString(questsDir.resolve("test_slice_hunt.yml"), HUNT_YAML);
        engine.reloadQuestDefinitions();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void theRetiredQuestsAreNeitherBundledNorSeeded() {
        assertFalse(Files.exists(questsDir.resolve("first_steps.yml")), "first_steps retirée (#778)");
        assertFalse(Files.exists(questsDir.resolve("crystal_hunt.yml")), "crystal_hunt retirée (#778)");
        assertTrue(plugin.bootstrap().questEngine().find(new NamespacedKey("rpgquest", "first_steps")).isEmpty());
        assertTrue(plugin.bootstrap().questEngine().find(new NamespacedKey("rpgquest", "crystal_hunt")).isEmpty());
        assertTrue(plugin.bootstrap().questEngine().find(new NamespacedKey("rpgquest", "woodcutters_request"))
                        .orElseThrow().prerequisites().isEmpty(),
                "Bob : woodcutters_request ne dépend plus d'aucune quête");
    }

    @Test
    void fullQuestCraftingLoopGrantsTheFinalReward() throws Exception {
        PlayerMock player = server.addPlayer();
        // Attend explicitement la fin du chargement asynchrone déclenché par PlayerJoinEvent
        // (QuestProgressConnectionListener -> loadForPlayer) avant d'interagir : sur un vrai
        // client, la latence réseau garantit naturellement cet ordre, pas ici où tout est local.
        engine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Prérequis non rempli : la seconde quête est refusée.
        assertEquals(AcceptOutcome.Result.MISSING_PREREQUISITES,
                engine.accept(player, HUNT).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).result());

        assertEquals(AcceptOutcome.Result.ACCEPTED,
                engine.accept(player, INTRO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).result());
        for (int i = 0; i < 10; i++) {
            engine.handleKillEntity(player, EntityType.SPIDER);
        }
        assertEquals(QuestState.COMPLETED, stateOf(player, INTRO));

        // Le prérequis est désormais satisfait.
        assertEquals(AcceptOutcome.Result.ACCEPTED,
                engine.accept(player, HUNT).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).result());
        for (int i = 0; i < 5; i++) {
            engine.handleKillEntity(player, EntityType.SPIDER);
        }
        engine.handleCollectItem(player, Material.AMETHYST_SHARD);
        engine.handleCollectItem(player, Material.AMETHYST_SHARD);
        engine.handleCraftItem(player, Material.DIAMOND_SWORD);

        // Dernier objectif -> remise automatique -> récompense.
        int itemsBefore = countItems(player, MINER_PICKAXE);
        engine.handleTalkToNpc(player, "guard");

        assertEquals(QuestState.COMPLETED, stateOf(player, HUNT));
        assertEquals(itemsBefore + 1, countItems(player, MINER_PICKAXE),
                "la récompense COMMAND doit donner exactement un miner_pickaxe");
        assertTrue(plugin.bootstrap().claimService().hasClaimTierOne(player.getUniqueId())
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "la récompense VARIABLE doit débloquer CLAIM_TIER_1");
    }

    /**
     * Issue #778 — un joueur avait une quête en cours quand elle a été retirée du serveur. Ni
     * erreur, ni récompense, ni purge : la ligne de progression reste telle quelle, et revient
     * exactement à l'identique si la quête était un jour restaurée depuis sa sauvegarde.
     */
    @Test
    void anActiveProgressOnARetiredQuestIsToleratedWithoutRewardOrPurge() throws Exception {
        PlayerMock player = server.addPlayer();
        engine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.accept(player, HUNT, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(QuestState.ACTIVE, stateOf(player, HUNT));

        // Retrait du fichier, rechargement, puis reconnexion du joueur.
        Files.delete(questsDir.resolve("test_slice_hunt.yml"));
        engine.reloadQuestDefinitions();
        engine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        int itemsBefore = countItems(player, MINER_PICKAXE);
        int xpBefore = player.getTotalExperience();
        for (int i = 0; i < 5; i++) {
            engine.handleKillEntity(player, EntityType.SPIDER);
        }
        engine.handleCollectItem(player, Material.AMETHYST_SHARD);
        engine.handleCraftItem(player, Material.DIAMOND_SWORD);
        engine.handleTalkToNpc(player, "guard");
        server.getScheduler().performTicks(20);

        assertEquals(itemsBefore, countItems(player, MINER_PICKAXE), "aucune récompense pour une quête retirée");
        assertEquals(xpBefore, player.getTotalExperience(), "aucune expérience pour une quête retirée");
        assertEquals(AcceptOutcome.Result.UNKNOWN_QUEST,
                engine.accept(player, HUNT).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).result(),
                "une quête retirée ne peut plus être acceptée");
        assertEquals(QuestState.ACTIVE, stateOf(player, HUNT),
                "la ligne de progression du joueur n'est jamais purgée");
    }

    private QuestState stateOf(PlayerMock player, NamespacedKey questId) throws Exception {
        return engine.stateOf(player.getUniqueId(), questId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private int countItems(PlayerMock player, NamespacedKey itemId) {
        int count = 0;
        for (var stack : player.getInventory().getContents()) {
            if (stack != null && itemRegistry.identify(stack).map(itemId::equals).orElse(false)) {
                count += stack.getAmount();
            }
        }
        return count;
    }
}
