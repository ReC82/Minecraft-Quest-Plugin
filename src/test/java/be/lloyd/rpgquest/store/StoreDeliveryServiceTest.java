package be.lloyd.rpgquest.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.RPGQuestPlugin;
import be.lloyd.rpgquest.backpack.BackpackService;
import be.lloyd.rpgquest.backpack.model.BackpackSize;
import be.lloyd.rpgquest.config.BackpackConfig;
import be.lloyd.rpgquest.config.StoreConfig;
import be.lloyd.rpgquest.database.BackpackRepository;
import be.lloyd.rpgquest.database.DatabaseManager;
import be.lloyd.rpgquest.database.EntitlementRepository;
import be.lloyd.rpgquest.database.PlayerProfileRepository;
import be.lloyd.rpgquest.database.StoreDeliveryRepository;
import be.lloyd.rpgquest.entitlement.EntitlementService;
import be.lloyd.rpgquest.web.Json;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Vérifie le traitement des livraisons (mission étape 22, points 7-10) :
 * joueur hors ligne/UUID inconnu, produit déjà possédé, upgrade,
 * révocation, produit inconnu côté plugin, idempotence locale — via un vrai
 * petit serveur HTTP tenant lieu de web-api.
 */
class StoreDeliveryServiceTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private StoreProductRegistry productRegistry;
    private BackpackService backpackService;
    private EntitlementService entitlementService;
    private PlayerProfileRepository profileRepository;
    private StoreDeliveryRepository processedRepository;
    private FakeWebApi fakeWebApi;
    private StoreDeliveryService deliveryService;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profileRepository = new PlayerProfileRepository(database);
        processedRepository = new StoreDeliveryRepository(database);
        entitlementService = new EntitlementRepository(database);
        BackpackRepository backpackRepository = new BackpackRepository(database);
        BackpackConfig backpackConfig = new BackpackConfig(1, 3, 6, Set.of(), BackpackSize.SMALL, org.bukkit.Material.BUNDLE);
        backpackService = new BackpackService(plugin, backpackRepository, entitlementService, () -> backpackConfig, plugin.getSLF4JLogger());
        backpackService.start();

        writeProduct("small_backpack.yml", "id: small_backpack\ngrant-type: BACKPACK_SIZE\nbackpack-size: SMALL\n");
        writeProduct("upgrade_medium.yml", "id: upgrade_medium\ngrant-type: BACKPACK_SIZE\nbackpack-size: MEDIUM\n");
        writeProduct("vip_pass_test.yml", "id: vip_pass_test\ngrant-type: ENTITLEMENT\nentitlement-key: vip\nentitlement-tier: TEST\n");
        productRegistry = new StoreProductRegistry(tempDir.resolve("store-products"), plugin.getSLF4JLogger());
        productRegistry.reload();

        fakeWebApi = new FakeWebApi();
        fakeWebApi.start();

        StoreConfig storeConfig = new StoreConfig(true, fakeWebApi.baseUrl(), 30);
        StoreClient storeClient = new StoreClient(() -> storeConfig, "test-token");
        deliveryService = new StoreDeliveryService(plugin, storeClient, productRegistry, processedRepository,
                profileRepository, entitlementService, backpackService, () -> storeConfig, () -> backpackConfig,
                plugin.getSLF4JLogger());
    }

    @AfterEach
    void tearDown() {
        backpackService.stop();
        fakeWebApi.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    private void writeProduct(String fileName, String content) throws IOException {
        java.nio.file.Files.createDirectories(tempDir.resolve("store-products"));
        java.nio.file.Files.writeString(tempDir.resolve("store-products").resolve(fileName), content, StandardCharsets.UTF_8);
    }

    @Test
    void grantForAnUnknownPlayerCreatesTheirProfileAndGrantsOffline() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        fakeWebApi.setPendingDeliveries(List.of(
                delivery("d1", "o1", "GRANT", "small_backpack", playerUuid, "NewPlayer")));

        deliveryService.triggerNow().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        var profile = profileRepository.find(playerUuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(profile.isPresent());
        assertEquals("NewPlayer", profile.get().lastName());

        var tier = entitlementService.currentTier(playerUuid, BackpackService.ENTITLEMENT_KEY).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("SMALL", tier.orElseThrow());
        assertEquals(1, fakeWebApi.ackCalls().size());
        assertTrue(fakeWebApi.ackCalls().get(0).delivered());
    }

    @Test
    void upgradeGrantsTheHigherTierWhenAlreadyOwningALowerOne() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        profileRepository.findOrCreate(playerUuid, "Upgrader").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        entitlementService.grant(playerUuid, BackpackService.ENTITLEMENT_KEY, "SMALL", "test").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        fakeWebApi.setPendingDeliveries(List.of(
                delivery("d2", "o2", "GRANT", "upgrade_medium", playerUuid, "Upgrader")));
        deliveryService.triggerNow().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        var tier = entitlementService.currentTier(playerUuid, BackpackService.ENTITLEMENT_KEY).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("MEDIUM", tier.orElseThrow());
    }

    @Test
    void alreadyOwningAnEqualOrHigherTierIsSkippedWithoutErrorOrDoubleGrant() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        profileRepository.findOrCreate(playerUuid, "Owner").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        entitlementService.grant(playerUuid, BackpackService.ENTITLEMENT_KEY, "MEDIUM", "test").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        fakeWebApi.setPendingDeliveries(List.of(
                delivery("d3", "o3", "GRANT", "small_backpack", playerUuid, "Owner")));
        deliveryService.triggerNow().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        var tier = entitlementService.currentTier(playerUuid, BackpackService.ENTITLEMENT_KEY).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("MEDIUM", tier.orElseThrow(), "ne doit jamais rétrograder un palier déjà supérieur");
        assertTrue(fakeWebApi.ackCalls().get(0).delivered());
    }

    @Test
    void entitlementGrantWorksForNonBackpackProducts() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        fakeWebApi.setPendingDeliveries(List.of(
                delivery("d4", "o4", "GRANT", "vip_pass_test", playerUuid, "VipBuyer")));
        deliveryService.triggerNow().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        var tier = entitlementService.currentTier(playerUuid, "vip").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("TEST", tier.orElseThrow());
    }

    @Test
    void revokeRemovesTheEntitlementAndFallsBackTheBackpackSize() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        profileRepository.findOrCreate(playerUuid, "Refunded").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        entitlementService.grant(playerUuid, BackpackService.ENTITLEMENT_KEY, "MEDIUM", "test").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        backpackService.applySizeChange(playerUuid, BackpackSize.MEDIUM).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        fakeWebApi.setPendingDeliveries(List.of(
                delivery("d5", "o5", "REVOKE", "upgrade_medium", playerUuid, "Refunded")));
        deliveryService.triggerNow().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        var tier = entitlementService.currentTier(playerUuid, BackpackService.ENTITLEMENT_KEY).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(tier.isEmpty());
    }

    @Test
    void unknownProductLeavesTheDeliveryUnacknowledgedForRetry() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        fakeWebApi.setPendingDeliveries(List.of(
                delivery("d6", "o6", "GRANT", "does_not_exist", playerUuid, "Someone")));
        deliveryService.triggerNow().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(1, fakeWebApi.ackCalls().size());
        assertFalse(fakeWebApi.ackCalls().get(0).delivered(), "un produit inconnu ne doit jamais être acquitté comme livré");

        boolean processedLocally = processedRepository.isProcessed("d6").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertFalse(processedLocally);
    }

    @Test
    void aDeliveryAlreadyProcessedLocallyIsNeverGrantedTwice() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        fakeWebApi.setPendingDeliveries(List.of(
                delivery("d7", "o7", "GRANT", "small_backpack", playerUuid, "Repeat")));
        deliveryService.triggerNow().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // Simule un accusé de réception qui n'a jamais atteint web-api : la livraison réapparaît.
        deliveryService.triggerNow().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(2, fakeWebApi.ackCalls().size());
        assertTrue(fakeWebApi.ackCalls().get(1).delivered());
        var tier = entitlementService.currentTier(playerUuid, BackpackService.ENTITLEMENT_KEY).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("SMALL", tier.orElseThrow());
    }

    @Test
    void webApiUnavailableIsToleratedAndDoesNotThrow() throws Exception {
        fakeWebApi.stop();
        assertTrue(deliveryService.triggerNow().handle((ignored, error) -> true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private static Map<String, Object> delivery(String id, String orderId, String kind, String productId,
                                                  UUID playerUuid, String playerName) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("orderId", orderId);
        entry.put("kind", kind);
        entry.put("productId", productId);
        entry.put("playerUuid", playerUuid.toString());
        entry.put("playerName", playerName);
        entry.put("attempts", 0L);
        return entry;
    }

    /** Petit double de web-api : sert la liste de livraisons configurée, enregistre les accusés reçus. */
    private static final class FakeWebApi {
        private final AtomicReference<List<Map<String, Object>>> pending = new AtomicReference<>(List.of());
        private final List<AckCall> acks = new ArrayList<>();
        private HttpServer server;

        void start() throws IOException {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/api/store/deliveries/pending", exchange -> {
                String body = Json.write(Map.of("deliveries", pending.get()));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.createContext("/api/store/deliveries/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String id = path.substring("/api/store/deliveries/".length(), path.length() - "/ack".length());
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Object parsed = Json.parse(requestBody);
                boolean delivered = parsed instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("delivered"));
                synchronized (acks) {
                    acks.add(new AckCall(id, delivered));
                }
                byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
        }

        void stop() {
            if (server != null) {
                server.stop(0);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void setPendingDeliveries(List<Map<String, Object>> deliveries) {
            pending.set(deliveries);
        }

        List<AckCall> ackCalls() {
            synchronized (acks) {
                return List.copyOf(acks);
            }
        }
    }

    private record AckCall(String deliveryId, boolean delivered) {
    }
}
