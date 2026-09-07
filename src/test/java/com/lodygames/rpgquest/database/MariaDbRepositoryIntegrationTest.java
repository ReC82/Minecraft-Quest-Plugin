package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #41 — les repositories métier existants sur MariaDB : mêmes comportements que SQLite
 * (upsert, insert-ignore, transactions, clés générées). Ignoré si {@code RPGQUEST_DB_*} absent.
 */
class MariaDbRepositoryIntegrationTest {

    private DatabaseManager database;

    @BeforeAll
    static void requireMariaDb() {
        MariaDbTestSupport.assumeAvailable();
    }

    @BeforeEach
    void freshSchema() throws Exception {
        MariaDbTestSupport.wipe();
        database = new DatabaseManager(MariaDbTestSupport.engine());
        database.initialize().get(30, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.shutdown();
        }
    }

    private <T> T await(java.util.concurrent.CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private UUID newPlayer(String name) throws Exception {
        PlayerProfileRepository profiles = new PlayerProfileRepository(database);
        UUID uuid = UUID.randomUUID();
        await(profiles.findOrCreate(uuid, name));
        return uuid;
    }

    @Test
    void playerProfileCrud() throws Exception {
        PlayerProfileRepository profiles = new PlayerProfileRepository(database);
        UUID uuid = UUID.randomUUID();
        assertTrue(await(profiles.find(uuid)).isEmpty());

        PlayerProfile created = await(profiles.findOrCreate(uuid, "Rondoudou"));
        assertEquals("Rondoudou", created.lastName());
        // idempotent + mise à jour du nom
        PlayerProfile again = await(profiles.findOrCreate(uuid, "Rondoudou9000"));
        assertEquals(uuid, again.uuid());
        assertEquals("Rondoudou9000", await(profiles.find(uuid)).orElseThrow().lastName());
    }

    @Test
    void playerVariableUpsertBehavesLikeSqlite() throws Exception {
        UUID uuid = newPlayer("Var");
        PlayerVariableRepository variables = new PlayerVariableRepository(database);

        assertTrue(await(variables.get(uuid, "CLAIM_TIER_1")).isEmpty());
        await(variables.set(uuid, "CLAIM_TIER_1", "false"));
        assertEquals("false", await(variables.get(uuid, "CLAIM_TIER_1")).orElseThrow());
        // second set = UPDATE via ON DUPLICATE KEY, pas une 2e ligne
        await(variables.set(uuid, "CLAIM_TIER_1", "true"));
        assertEquals("true", await(variables.get(uuid, "CLAIM_TIER_1")).orElseThrow());
        assertEquals(1, await(variables.findAllForPlayer(uuid)).size());
        // clé sensible à la casse (collation utf8mb4_bin, comme SQLite)
        await(variables.set(uuid, "claim_tier_1", "x"));
        assertEquals(2, await(variables.findAllForPlayer(uuid)).size());
    }

    @Test
    void walletTransactionsAreAtomicAndConsistent() throws Exception {
        WalletRepository wallet = new WalletRepository(database);
        UUID a = newPlayer("A");
        UUID b = newPlayer("B");

        await(wallet.credit(a, 100, "TEST_GRANT", "seed"));
        assertEquals(100L, (long) await(wallet.balance(a)));

        assertFalse(await(wallet.debit(a, 500, "TEST_BUY", "too much")), "solde insuffisant -> refus");
        assertEquals(100L, (long) await(wallet.balance(a)), "solde inchangé après un débit refusé");

        assertTrue(await(wallet.pay(a, b, 60, "gift")));
        assertEquals(40L, (long) await(wallet.balance(a)));
        assertEquals(60L, (long) await(wallet.balance(b)));
    }

    @Test
    void marketRepositoryReturnsGeneratedKeys() throws Exception {
        MarketRepository market = new MarketRepository(database);
        UUID seller = newPlayer("Seller");
        byte[] item = {1, 2, 3, 4, 5};

        long id1 = await(market.createListing(seller, item, 25));
        long id2 = await(market.createListing(seller, new byte[] {9}, 30));
        assertTrue(id1 > 0 && id2 > 0);
        assertNotEquals(id1, id2, "clés auto-incrémentées distinctes");

        var listings = await(market.myActiveListings(seller));
        assertEquals(2, listings.size());
        assertArrayEquals(item, listings.stream().filter(l -> l.id() == id1).findFirst().orElseThrow().itemData(),
                "BLOB restitué à l'identique");
    }

    @Test
    void npcIdRepositoryAllocatesSequentialGeneratedIds() throws Exception {
        NpcIdRepository npcIds = new NpcIdRepository(database);
        int first = await(npcIds.allocateId());
        int second = await(npcIds.allocateId());
        assertTrue(first >= 1);
        assertEquals(first + 1, second);
    }

    @Test
    void questProgressUpsertBehavesLikeSqlite() throws Exception {
        UUID uuid = newPlayer("Quester");
        QuestProgressRepository quests = new QuestProgressRepository(database);
        org.bukkit.NamespacedKey quest = new org.bukkit.NamespacedKey("rpgquest", "first_steps");

        await(quests.upsertState(uuid, quest, com.lodygames.rpgquest.quest.model.QuestState.ACTIVE, "step_one"));
        assertEquals(com.lodygames.rpgquest.quest.model.QuestState.ACTIVE,
                await(quests.find(uuid, quest)).orElseThrow().state());

        await(quests.upsertState(uuid, quest, com.lodygames.rpgquest.quest.model.QuestState.COMPLETED, null));
        assertEquals(com.lodygames.rpgquest.quest.model.QuestState.COMPLETED,
                await(quests.find(uuid, quest)).orElseThrow().state());
        assertEquals(1, await(quests.findAll(uuid)).size());

        await(quests.setObjectiveProgress(uuid, quest, "step_one", 0, 3));
        assertEquals(3, await(quests.findObjectiveProgress(uuid, quest, "step_one")).get(0));
    }

    @Test
    void reconnectAfterAConnectionIsDroppedFromUnderThePool() throws Exception {
        PlayerVariableRepository variables = new PlayerVariableRepository(database);
        UUID uuid = newPlayer("Resilient");
        await(variables.set(uuid, "K", "1"));

        // simule une connexion cassée : on ferme brutalement la connexion empruntée le temps d'une
        // opération -> Hikari l'évince et en fournit une neuve à l'opération suivante.
        Boolean closedOk = await(database.execute(connection -> {
            connection.close();
            return connection.isClosed();
        }));
        assertTrue(closedOk);

        // l'opération suivante réussit sur une connexion fraîche du pool
        await(variables.set(uuid, "K", "2"));
        assertEquals("2", await(variables.get(uuid, "K")).orElseThrow());
    }

    @Test
    void invalidConfigurationIsRejectedCleanly() {
        DatabaseSettings.MySqlSettings bad = new DatabaseSettings.MySqlSettings(
                MariaDbTestSupport.settings().host(), MariaDbTestSupport.settings().port(),
                "no_such_db_" + System.nanoTime(), MariaDbTestSupport.settings().username(),
                "RPGQUEST_DB_PASSWORD", "disable", new DatabaseSettings.PoolSettings(1, 1, 3_000L, 60_000L, 0L));
        DatabaseManager broken = new DatabaseManager(new MySqlDatabaseEngine(bad, System::getenv));
        try {
            org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> broken.initialize().get(15, TimeUnit.SECONDS));
        } finally {
            broken.shutdown();
        }
    }
}
