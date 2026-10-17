package com.lodygames.rpgquest.quest.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.HeartGrantRepository;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.database.WalletRepository;
import com.lodygames.rpgquest.economy.EconomyService;
import com.lodygames.rpgquest.health.HeartBonusService;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.EntityType;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Chaîne réelle de la récompense « Cœur supplémentaire » (issue #166) : vraie complétion de quête,
 * vrai {@link QuestProgressRepository} sur une vraie base SQLite, vrai {@link HeartBonusService}.
 *
 * <p>MockBukkit calcule la valeur de l'attribut mais n'affiche aucun HUD : ces tests prouvent la
 * vie maximale et l'idempotence, <strong>pas</strong> le rendu des cœurs à l'écran ni le
 * comportement réel du serveur à la réapparition (PENDING MANUAL VALIDATION).</p>
 */
class QuestHeartRewardIntegrationTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey FIRST = new NamespacedKey("rpgquest", "tc166_first");
    private static final NamespacedKey SECOND = new NamespacedKey("rpgquest", "tc166_second");
    private static final NamespacedKey DAILY = new NamespacedKey("rpgquest", "tc166_daily");
    private static final NamespacedKey CITY_A = new NamespacedKey("rpgquest", "tc166_city_a");
    private static final NamespacedKey CITY_A_BIS = new NamespacedKey("rpgquest", "tc166_city_a_bis");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private DatabaseManager database;
    private PlayerProfileRepository profileRepository;
    private HeartGrantRepository grants;
    private HeartBonusService hearts;
    private QuestProgressEngine engine;
    private final AtomicInteger cap = new AtomicInteger(0);

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        RPGQuestPlugin plugin = MockBukkit.load(RPGQuestPlugin.class);
        // Le plugin chargé a son propre HeartBonusService, branché sur sa propre base : à chaque
        // addPlayer() il relit 0 cœur (après ses migrations, en asynchrone) et, si cette relecture
        // arrive après la complétion du test, retire le bonus. Seuls les services du test doivent
        // agir sur le joueur (#506).
        HandlerList.unregisterAll(plugin);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profileRepository = new PlayerProfileRepository(database);
        grants = new HeartGrantRepository(database);

        Path questsDir = tempDir.resolve("quests");
        Files.createDirectories(questsDir);
        Files.writeString(questsDir.resolve("first.yml"), questYaml(FIRST, false, "amount: 1"));
        Files.writeString(questsDir.resolve("second.yml"), questYaml(SECOND, false, ""));
        Files.writeString(questsDir.resolve("daily.yml"), questYaml(DAILY, true, "amount: 1"));
        Files.writeString(questsDir.resolve("city_a.yml"), questYaml(CITY_A, false, "key: city:a"));
        Files.writeString(questsDir.resolve("city_a_bis.yml"), questYaml(CITY_A_BIS, false, "key: city:a"));

        YamlQuestEngine questEngine = new YamlQuestEngine(questsDir, plugin.getSLF4JLogger());
        questEngine.reload();
        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        NpcIdentityService npcIdentityService = new NpcIdentityService(
                plugin, new NpcIdRepository(database), new NpcBindingRepository(database));

        engine = new QuestProgressEngine(plugin, questEngine, new QuestProgressRepository(database),
                new PlayerVariableRepository(database), messagesService, npcIdentityService,
                new EconomyService(new WalletRepository(database)));
        hearts = new HeartBonusService(plugin, grants, messagesService, cap::get);
        engine.setHeartBonusService(hearts);
        engine.start();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void tenHeartsBecomeElevenThenTwelveAcrossTwoQuests() throws Exception {
        PlayerMock player = addPlayer();
        assertEquals(20.0, maxHealth(player));

        complete(player, FIRST);
        awaitMaxHealth(player, 22.0);
        assertTrue(awaitMessageContaining(player, "11 cœurs").contains("+1"));

        // Sans « amount » : 1 cœur par défaut.
        complete(player, SECOND);
        awaitMaxHealth(player, 24.0);
        assertEquals(2, grants.totalHearts(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void aRepeatableQuestGrantsItsHeartOnlyOnce() throws Exception {
        PlayerMock player = addPlayer();
        complete(player, DAILY);
        awaitMaxHealth(player, 22.0);

        complete(player, DAILY);
        awaitMessageContaining(player, "déjà reçu");
        assertEquals(22.0, maxHealth(player));
        assertEquals(1, grants.totalHearts(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void twoQuestsSharingAGrantKeyGiveASingleHeart() throws Exception {
        PlayerMock player = addPlayer();
        complete(player, CITY_A);
        awaitMaxHealth(player, 22.0);

        // Même ville, autre quête (Stan renommé, remplacé, épreuve rééditée) : aucun second cœur.
        complete(player, CITY_A_BIS);
        awaitMessageContaining(player, "déjà reçu");
        assertEquals(22.0, maxHealth(player));
    }

    @Test
    void otherMaxHealthSourcesArePreservedAndReapplyingNeverStacks() throws Exception {
        PlayerMock player = addPlayer();
        NamespacedKey foreign = new NamespacedKey("otherplugin", "amulet");
        player.getAttribute(Attribute.MAX_HEALTH).addModifier(new AttributeModifier(
                foreign, 4.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        assertEquals(24.0, maxHealth(player));

        complete(player, FIRST);
        awaitMaxHealth(player, 26.0);

        // Reconnexion (relecture en base) puis réapparition : remplacé, jamais additionné.
        hearts.refresh(player.getUniqueId());
        pump();
        hearts.onPostRespawn(new PlayerPostRespawnEvent(player, player.getLocation(), false, false, false,
                PlayerRespawnEvent.RespawnReason.DEATH));
        assertEquals(26.0, maxHealth(player));
        assertEquals(20.0, player.getAttribute(Attribute.MAX_HEALTH).getBaseValue(), "la base n'est jamais réécrite");
    }

    @Test
    void theBonusIsRestoredFromTheDatabaseAfterTheModifierWasLost() throws Exception {
        PlayerMock player = addPlayer();
        complete(player, FIRST);
        awaitMaxHealth(player, 22.0);

        // Simule une entité recréée sans le modificateur (redémarrage, nouveau serveur) : la base
        // fait foi et la connexion le rétablit.
        player.getAttribute(Attribute.MAX_HEALTH).removeModifier(HeartBonusService.MODIFIER_KEY);
        assertEquals(20.0, maxHealth(player));
        hearts.refresh(player.getUniqueId());
        awaitMaxHealth(player, 22.0);
    }

    @Test
    void onlyTheNewHealthIsFilledNotTheWholeBar() throws Exception {
        PlayerMock player = addPlayer();
        player.setHealth(10.0);

        complete(player, FIRST);
        awaitMaxHealth(player, 22.0);
        assertEquals(12.0, player.getHealth(), 1e-9);
    }

    @Test
    void heartsAreStrictlyPersonal() throws Exception {
        PlayerMock winner = addPlayer();
        PlayerMock other = addPlayer();

        complete(winner, FIRST);
        awaitMaxHealth(winner, 22.0);
        assertEquals(20.0, maxHealth(other));
        assertEquals(0, grants.totalHearts(other.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void aReachedCapKeepsTheRightButDoesNotApplyItUntilRaised() throws Exception {
        cap.set(1);
        PlayerMock player = addPlayer();
        complete(player, FIRST);
        awaitMaxHealth(player, 22.0);

        complete(player, SECOND);
        awaitMessageContaining(player, "plafond");
        assertEquals(22.0, maxHealth(player));
        assertEquals(2, grants.totalHearts(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        cap.set(0);
        hearts.refresh(player.getUniqueId());
        awaitMaxHealth(player, 24.0);
    }

    // ---- utilitaires ---------------------------------------------------------------------------

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    private void complete(PlayerMock player, NamespacedKey quest) throws Exception {
        engine.accept(player, quest).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.handleKillEntity(player, EntityType.ZOMBIE);
    }

    private static double maxHealth(PlayerMock player) {
        return player.getAttribute(Attribute.MAX_HEALTH).getValue();
    }

    private void pump() throws Exception {
        for (int i = 0; i < 20; i++) {
            server.getScheduler().performTicks(2);
            Thread.sleep(10);
        }
    }

    /** Écriture asynchrone puis retour au thread principal : on pompe ticks ET temps réel. */
    private void awaitMaxHealth(PlayerMock player, double expected) throws Exception {
        for (int i = 0; i < 200 && maxHealth(player) != expected; i++) {
            server.getScheduler().performTicks(2);
            Thread.sleep(10);
        }
        assertEquals(expected, maxHealth(player));
    }

    private String awaitMessageContaining(PlayerMock player, String needle) throws Exception {
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String message;
            while ((message = player.nextMessage()) != null) {
                seen.add(message);
                if (message.contains(needle)) {
                    return message;
                }
            }
            server.getScheduler().performTicks(2);
            Thread.sleep(10);
        }
        throw new AssertionError("aucun message contenant « " + needle + " » ; vus : " + seen);
    }

    private static String questYaml(NamespacedKey id, boolean repeatable, String heartOptions) {
        String options = heartOptions.isBlank() ? "" : "\n    " + heartOptions;
        return """
                id: %s
                title: "Quête du cœur"
                description: "Description"
                category: test
                repeatable: %s
                steps:
                  - id: kill_step
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1
                rewards:
                  - type: HEART%s
                """.formatted(id, repeatable, options);
    }
}
