package com.lodygames.rpgquest.quest.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.database.WalletRepository;
import com.lodygames.rpgquest.economy.EconomyService;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Chaîne <strong>complète et réelle</strong> d'une récompense monétaire (issue #16) : un joueur
 * termine vraiment une quête, et c'est le vrai {@code EconomyService} au-dessus du vrai
 * {@code WalletRepository} et d'une vraie base SQLite qui est crédité.
 *
 * <p>Ces cas existent en plus de {@code QuestProgressEngineTest} (qui utilise un payeur
 * enregistreur) et de {@code WalletRepositoryTest} (qui vérifie l'idempotence en SQL), parce
 * qu'aucun des deux ne prouve que les morceaux sont correctement <em>branchés</em> : le moteur
 * pourrait demander le bon montant à un service jamais relié au portefeuille, et les deux suites
 * resteraient vertes.</p>
 */
class QuestMoneyRewardIntegrationTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey PAID_QUEST = new NamespacedKey("rpgquest", "tc250_paid");
    private static final NamespacedKey DAILY_QUEST = new NamespacedKey("rpgquest", "tc250_daily");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private PlayerProfileRepository profileRepository;
    private WalletRepository wallets;
    private QuestProgressEngine engine;
    private Path questsDir;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profileRepository = new PlayerProfileRepository(database);
        wallets = new WalletRepository(database);

        questsDir = tempDir.resolve("quests");
        Files.createDirectories(questsDir);
        Files.writeString(questsDir.resolve("paid.yml"), questYaml(PAID_QUEST.toString(), 250, false));
        Files.writeString(questsDir.resolve("daily.yml"), questYaml(DAILY_QUEST.toString(), 50, true));

        YamlQuestEngine questEngine = new YamlQuestEngine(questsDir, plugin.getSLF4JLogger());
        questEngine.reload();
        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        NpcIdentityService npcIdentityService = new NpcIdentityService(
                plugin, new NpcIdRepository(database), new NpcBindingRepository(database));

        engine = new QuestProgressEngine(plugin, questEngine, new QuestProgressRepository(database),
                new PlayerVariableRepository(database), messagesService, npcIdentityService,
                new EconomyService(wallets));
        engine.start();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void finishingAQuestReallyCreditsTheWalletOnceAndLeavesOneLedgerLine() throws Exception {
        PlayerMock player = addPlayer();
        engine.accept(player, PAID_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        engine.handleKillEntity(player, EntityType.ZOMBIE);

        awaitBalance(player.getUniqueId(), 250L);
        List<WalletRepository.LedgerEntry> history =
                wallets.history(player.getUniqueId(), 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, history.size(), () -> "une complétion = une ligne de journal : " + history);
        assertEquals("QUEST_REWARD", history.get(0).type());
        assertTrue(history.get(0).context().contains(PAID_QUEST.toString()),
                () -> "la ligne doit rester rattachable à la quête : " + history.get(0).context());
    }

    @Test
    void theConfirmationMessageQuotesTheBalanceActuallyStored() throws Exception {
        PlayerMock player = addPlayer();
        wallets.credit(player.getUniqueId(), 1000, "ADMIN_GRANT", "solde de départ")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.accept(player, PAID_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        engine.handleKillEntity(player, EntityType.ZOMBIE);

        awaitBalance(player.getUniqueId(), 1250L);
        // 1000 + 250 : le message doit citer le solde relu en base, pas le montant de la récompense.
        String message = awaitMessageContaining(player, "1250");
        assertTrue(message.contains("250"), () -> message);
        assertEquals(1250L, wallets.balance(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void aRepeatableQuestPaysOncePerCompletionAndNeverTwiceForTheSameOne() throws Exception {
        PlayerMock player = addPlayer();

        engine.accept(player, DAILY_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        awaitBalance(player.getUniqueId(), 50L);

        // Un événement de plus APRÈS la remise ne doit rien repayer : la quête n'est plus active.
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        server.getScheduler().performTicks(20);
        assertEquals(50L, wallets.balance(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // En revanche, la reprendre et la refinir est une nouvelle occasion, donc un nouveau gain.
        engine.accept(player, DAILY_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        awaitBalance(player.getUniqueId(), 100L);

        assertEquals(2, wallets.history(player.getUniqueId(), 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    @Test
    void theCreditSurvivesAReconnectionAndIsNotPaidAgain() throws Exception {
        PlayerMock player = addPlayer();
        UUID playerId = player.getUniqueId();
        engine.accept(player, PAID_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        awaitBalance(playerId, 250L);

        engine.unloadForPlayer(playerId);
        player.disconnect();
        engine.loadForPlayer(playerId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(20);

        // Le portefeuille est persistant : ni perte, ni second crédit au retour.
        assertEquals(250L, wallets.balance(playerId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, wallets.history(playerId, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    @Test
    void aForcedAdminCompletionPaysOnceAndASecondAttemptPaysNothing() throws Exception {
        PlayerMock player = addPlayer();
        engine.accept(player, PAID_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        engine.forceComplete(player, PAID_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        awaitBalance(player.getUniqueId(), 250L);

        // Deuxième « complétion forcée » d'une quête NON répétable : refusée en amont, donc aucun
        // crédit — c'est ce que l'action admin du panel annonce déjà (« aucune récompense
        // re-créditée »), et ce test l'ancre dans le solde réel plutôt que dans le message.
        engine.forceComplete(player, PAID_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(20);

        assertEquals(250L, wallets.balance(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, wallets.history(player.getUniqueId(), 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    // ---- utilitaires ---------------------------------------------------------------------------

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    /**
     * Attend que le solde atteigne la valeur voulue en pompant les ticks. Le crédit est asynchrone
     * et son message repasse par le thread principal : un nombre fixe de ticks rendrait ce test
     * vert au calme et rouge sous charge.
     */
    private void awaitBalance(UUID playerId, long expected) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (wallets.balance(playerId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == expected) {
                return;
            }
            server.getScheduler().performTicks(2);
            Thread.sleep(10);
        }
        assertEquals(expected, wallets.balance(playerId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    /**
     * Attend un message en pompant les ticks <strong>et</strong> en laissant passer du temps réel.
     * Les deux sont nécessaires : {@code performTicks} ne consomme aucun temps d'horloge, donc une
     * boucle qui ne fait que pomper peut s'épuiser entièrement avant que l'écriture asynchrone en
     * base soit terminée — et le test échouerait pour une raison qui n'a rien à voir avec le code.
     */
    private String awaitMessageContaining(PlayerMock player, String needle) throws Exception {
        List<String> seen = new java.util.ArrayList<>();
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

    private static String questYaml(String id, int amount, boolean repeatable) {
        return """
                id: %s
                title: "Quête payée"
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
                  - type: MONEY
                    amount: %d
                """.formatted(id, repeatable, amount);
    }

    @Test
    void twoMoneyRewardsOnTheSameCompletionAreBothPaid() throws Exception {
        // Mesure du défaut signalé : si les deux récompenses partagent la même identité de
        // paiement, la clé primaire rejette la seconde comme « déjà payée » et le joueur ne
        // reçoit que la première, SANS aucune erreur visible.
        Files.writeString(questsDir.resolve("double.yml"), """
                id: rpgquest:tc250_double
                title: "Deux récompenses"
                description: "Description"
                category: test
                repeatable: false
                steps:
                  - id: kill_step
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1
                rewards:
                  - type: MONEY
                    amount: 100
                  - type: MONEY
                    amount: 30
                """);
        engine.reloadQuestDefinitions();
        PlayerMock player = addPlayer();
        engine.accept(player, NamespacedKey.fromString("rpgquest:tc250_double"))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        engine.handleKillEntity(player, EntityType.ZOMBIE);

        awaitBalance(player.getUniqueId(), 130L);
        assertEquals(2, wallets.history(player.getUniqueId(), 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }
}
