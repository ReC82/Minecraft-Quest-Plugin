package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarketRepositoryTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final byte[] ITEM_DATA = "fake-item-bytes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private PlayerProfileRepository profiles;
    private MarketRepository market;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profiles = new PlayerProfileRepository(database);
        market = new MarketRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void createdListingAppearsInActiveListingsWithSellerName() throws Exception {
        UUID seller = createPlayer("Steve");

        long id = market.createListing(seller, ITEM_DATA, 42).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        List<MarketListingRecord> active = market.activeListings().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, active.size());
        MarketListingRecord listing = active.get(0);
        assertEquals(id, listing.id());
        assertEquals(seller, listing.sellerUuid());
        assertEquals("Steve", listing.sellerName().orElseThrow());
        assertEquals(42L, listing.price());
        assertEquals("ACTIVE", listing.status());
        assertArrayEquals(ITEM_DATA, listing.itemData());
    }

    @Test
    void myActiveListingsFiltersBySeller() throws Exception {
        UUID steve = createPlayer("Steve");
        UUID alex = createPlayer("Alex");
        market.createListing(steve, ITEM_DATA, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        market.createListing(alex, ITEM_DATA, 20).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        List<MarketListingRecord> mine = market.myActiveListings(steve).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(1, mine.size());
        assertEquals(steve, mine.get(0).sellerUuid());
    }

    @Test
    void claimSucceedsOnceAndRemovesFromActiveListings() throws Exception {
        UUID seller = createPlayer("Steve");
        UUID buyer = createPlayer("Alex");
        long id = market.createListing(seller, ITEM_DATA, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Optional<MarketListingRecord> claimed = market.claim(id, buyer).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(claimed.isPresent());
        assertEquals(10L, claimed.get().price());
        assertEquals(0, market.activeListings().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    @Test
    void claimingAlreadySoldListingFails() throws Exception {
        UUID seller = createPlayer("Steve");
        UUID firstBuyer = createPlayer("Alex");
        UUID secondBuyer = createPlayer("Notch");
        long id = market.createListing(seller, ITEM_DATA, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        market.claim(id, firstBuyer).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Optional<MarketListingRecord> secondClaim = market.claim(id, secondBuyer).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(secondClaim.isEmpty(), "une offre déjà réservée ne doit jamais être réservable deux fois");
    }

    @Test
    void claimingUnknownListingFails() throws Exception {
        UUID buyer = createPlayer("Alex");

        Optional<MarketListingRecord> claimed = market.claim(9999, buyer).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(claimed.isEmpty());
    }

    @Test
    void reactivateMakesAListingAvailableAgainAfterAFailedPurchase() throws Exception {
        UUID seller = createPlayer("Steve");
        UUID buyer = createPlayer("Alex");
        long id = market.createListing(seller, ITEM_DATA, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        market.claim(id, buyer).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        market.reactivate(id, buyer).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        List<MarketListingRecord> active = market.activeListings().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, active.size());
        assertEquals(id, active.get(0).id());
    }

    @Test
    void cancelBySellerSucceedsAndListingDisappearsFromActive() throws Exception {
        UUID seller = createPlayer("Steve");
        long id = market.createListing(seller, ITEM_DATA, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Optional<MarketListingRecord> cancelled = market.cancel(id, seller).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(cancelled.isPresent());
        assertArrayEquals(ITEM_DATA, cancelled.get().itemData());
        assertEquals(0, market.activeListings().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    @Test
    void cancelByNonOwnerFailsAndListingStaysActive() throws Exception {
        UUID seller = createPlayer("Steve");
        UUID stranger = createPlayer("Alex");
        long id = market.createListing(seller, ITEM_DATA, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Optional<MarketListingRecord> cancelled = market.cancel(id, stranger).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(cancelled.isEmpty());
        assertEquals(1, market.activeListings().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    @Test
    void cancelOfAlreadySoldListingFails() throws Exception {
        UUID seller = createPlayer("Steve");
        UUID buyer = createPlayer("Alex");
        long id = market.createListing(seller, ITEM_DATA, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        market.claim(id, buyer).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Optional<MarketListingRecord> cancelled = market.cancel(id, seller).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(cancelled.isEmpty(), "une offre déjà vendue ne peut plus être annulée");
    }

    private UUID createPlayer(String name) throws Exception {
        UUID uuid = UUID.randomUUID();
        profiles.findOrCreate(uuid, name).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return uuid;
    }
}
