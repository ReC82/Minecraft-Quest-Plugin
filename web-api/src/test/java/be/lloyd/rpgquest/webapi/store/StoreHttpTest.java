package be.lloyd.rpgquest.webapi.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.webapi.HttpServerBootstrap;
import be.lloyd.rpgquest.webapi.SnapshotStore;
import be.lloyd.rpgquest.webapi.WebApiConfig;
import be.lloyd.rpgquest.webapi.json.Json;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bout-en-bout de la boutique (mission étape 22) via un vrai serveur HTTP :
 * achat, webhook (rejeu, signature invalide), livraisons (accusé
 * idempotent), remboursement, historique, reprise après redémarrage.
 */
class StoreHttpTest {

    private static final String TOKEN = "server-token-abc";
    private static final String WEBHOOK_SECRET = "webhook-secret-xyz";

    @TempDir
    Path tempDir;

    private Path storeDbFile;
    private Path productsFile;
    private HttpClient client;
    private HttpServerBootstrap bootstrap;
    private StoreDatabase storeDatabase;
    private StoreService storeService;
    private int port;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        storeDbFile = tempDir.resolve("store.db");
        productsFile = tempDir.resolve("products.json");
        Files.writeString(productsFile, """
                [{"id": "small_backpack", "name": "Small Backpack", "priceCents": 199, "currency": "EUR"}]
                """, StandardCharsets.UTF_8);
        client = HttpClient.newHttpClient();
        startServer();
    }

    @AfterEach
    void tearDown() {
        if (bootstrap != null) {
            bootstrap.stop();
        }
        if (storeDatabase != null) {
            storeDatabase.shutdown();
        }
    }

    private void startServer() throws Exception {
        int freePort = findFreePort();
        port = freePort;
        baseUrl = "http://127.0.0.1:" + freePort;

        WebApiConfig config = new WebApiConfig(freePort, tempDir.resolve("snapshot.json"), 120, TOKEN, 1000,
                "RPGQuest Test", productsFile, storeDbFile, WEBHOOK_SECRET, baseUrl);

        storeDatabase = new StoreDatabase(storeDbFile);
        storeDatabase.initialize().get(5, TimeUnit.SECONDS);
        StoreRepository repository = new StoreRepository(storeDatabase);
        ProductCatalog catalog = ProductCatalog.load(productsFile);
        SandboxPaymentProvider paymentProvider = new SandboxPaymentProvider(baseUrl, WEBHOOK_SECRET);
        storeService = new StoreService(catalog, repository, paymentProvider, WEBHOOK_SECRET);

        SnapshotStore snapshotStore = new SnapshotStore(config.snapshotFile(), config.snapshotMaxAgeSeconds(), Logger.getLogger("store-test"));
        bootstrap = new HttpServerBootstrap(config, snapshotStore, storeService);
        bootstrap.start();
    }

    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ---- HTTP helpers -------------------------------------------------------------------------

    private HttpResponse<String> postForm(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postJson(String path, String token, String jsonBody) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postWebhook(String jsonBody, String signature) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/store/webhook"))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (signature != null) {
            builder.header("X-Store-Signature", signature);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postNoBody(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ---- Checkout -------------------------------------------------------------------------------

    @Test
    void checkoutWithUnknownProductIsRejected() throws Exception {
        HttpResponse<String> response = postForm("/store/checkout",
                "productId=does_not_exist&playerUuid=" + UUID.randomUUID());
        assertEquals(404, response.statusCode());
    }

    @Test
    void checkoutWithInvalidPlayerUuidIsRejected() throws Exception {
        HttpResponse<String> response = postForm("/store/checkout", "productId=small_backpack&playerUuid=not-a-uuid");
        assertEquals(400, response.statusCode());
    }

    @Test
    void checkoutRedirectsToSandboxPayPage() throws Exception {
        HttpClient noRedirectClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/store/checkout"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("productId=small_backpack&playerUuid=" + UUID.randomUUID()))
                .build();
        HttpResponse<String> response = noRedirectClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(302, response.statusCode());
        assertTrue(response.headers().firstValue("Location").orElseThrow().contains("/store/pay/"));
    }

    // ---- Achat complet -> livraison ---------------------------------------------------------------

    @Test
    void confirmedPaymentEnqueuesAGrantDeliveryVisibleToTheGameServer() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        String orderId = createOrderDirectly("small_backpack", playerUuid, "Steve");
        payOrder(orderId);

        HttpResponse<String> pending = get("/api/store/deliveries/pending", TOKEN);
        assertEquals(200, pending.statusCode());
        assertTrue(pending.body().contains(orderId));
        assertTrue(pending.body().contains("\"kind\":\"GRANT\""));
        assertTrue(pending.body().contains(playerUuid.toString()));
    }

    // ---- Webhook --------------------------------------------------------------------------------

    @Test
    void webhookWithInvalidSignatureIsRejected() throws Exception {
        String body = Json.write(Map.of("eventId", "evt-1", "orderId", "order-1", "status", "paid"));
        HttpResponse<String> response = postWebhook(body, "not-the-right-signature");
        assertEquals(401, response.statusCode());
    }

    @Test
    void webhookReplayIsIdempotentAndNeverDeliversTwice() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        String orderId = createOrderDirectly("small_backpack", playerUuid, "Alex");
        String eventId = "evt-" + UUID.randomUUID();
        String body = Json.write(Map.of("eventId", eventId, "orderId", orderId, "status", "paid"));
        String signature = WebhookSigner.sign(WEBHOOK_SECRET, body.getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> first = postWebhook(body, signature);
        assertEquals(200, first.statusCode());
        assertTrue(first.body().contains("processed"));

        HttpResponse<String> replay = postWebhook(body, signature);
        assertEquals(200, replay.statusCode());
        assertTrue(replay.body().contains("duplicate"));

        List<Delivery> deliveries = storeService.deliveriesForOrder(orderId).get(5, TimeUnit.SECONDS);
        assertEquals(1, deliveries.size());
    }

    // ---- Livraison idempotente --------------------------------------------------------------------

    @Test
    void acknowledgingTheSameDeliveryTwiceIsIgnoredWithoutError() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        String orderId = createOrderDirectly("small_backpack", playerUuid, "Carol");
        payOrder(orderId);
        Delivery delivery = awaitSingleDelivery(orderId);

        HttpResponse<String> firstAck = postJson("/api/store/deliveries/" + delivery.id() + "/ack", TOKEN,
                Json.write(Map.of("delivered", true)));
        assertEquals(200, firstAck.statusCode());
        assertTrue(firstAck.body().contains("recorded"));

        HttpResponse<String> secondAck = postJson("/api/store/deliveries/" + delivery.id() + "/ack", TOKEN,
                Json.write(Map.of("delivered", true)));
        assertEquals(200, secondAck.statusCode());
        assertTrue(secondAck.body().contains("already_acknowledged"));

        HttpResponse<String> pending = get("/api/store/deliveries/pending", TOKEN);
        assertFalse(pending.body().contains(delivery.id()));
    }

    // ---- Remboursement ----------------------------------------------------------------------------

    @Test
    void refundingAPaidOrderEnqueuesARevokeDelivery() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        String orderId = createOrderDirectly("small_backpack", playerUuid, "Dave");
        payOrder(orderId);
        Delivery grant = awaitSingleDelivery(orderId);
        storeService.acknowledgeDelivery(grant.id(), true, null).get(5, TimeUnit.SECONDS);

        HttpResponse<String> refund = postNoBody("/api/store/orders/" + orderId + "/refund", TOKEN);
        assertEquals(200, refund.statusCode());
        assertTrue(refund.body().contains("refunded"));

        List<Delivery> deliveries = awaitDeliveryCount(orderId, 2);
        assertTrue(deliveries.stream().anyMatch(d -> d.kind() == DeliveryKind.REVOKE));
    }

    @Test
    void refundingAnUnpaidOrderIsRejected() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        String orderId = createOrderDirectly("small_backpack", playerUuid, "Eve");

        HttpResponse<String> refund = postNoBody("/api/store/orders/" + orderId + "/refund", TOKEN);
        assertEquals(409, refund.statusCode());
    }

    // ---- Historique admin -------------------------------------------------------------------------

    @Test
    void orderHistoryCanBeFilteredByPlayer() throws Exception {
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        createOrderDirectly("small_backpack", playerA, "PlayerA");
        createOrderDirectly("small_backpack", playerB, "PlayerB");

        HttpResponse<String> response = get("/api/store/orders?playerUuid=" + playerA, TOKEN);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains(playerA.toString()));
        assertFalse(response.body().contains(playerB.toString()));
    }

    // ---- Reprise après redémarrage ------------------------------------------------------------------

    @Test
    void pendingDeliveriesSurviveAServerRestart() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        String orderId = createOrderDirectly("small_backpack", playerUuid, "Frank");
        payOrder(orderId);
        awaitSingleDelivery(orderId);

        // Simule un crash : le processus web-api s'arrête et redémarre, avec le même store.db.
        bootstrap.stop();
        storeDatabase.shutdown();
        startServer();

        HttpResponse<String> pending = get("/api/store/deliveries/pending", TOKEN);
        assertEquals(200, pending.statusCode());
        assertTrue(pending.body().contains(orderId));
    }

    // ---- Utilitaires de test ------------------------------------------------------------------------

    private String createOrderDirectly(String productId, UUID playerUuid, String playerName) throws Exception {
        CheckoutResult result = storeService.checkout(productId, playerUuid.toString(), playerName).get(5, TimeUnit.SECONDS);
        assertEquals(CheckoutResult.Outcome.CREATED, result.outcome());
        String sessionId = result.redirectUrl().substring(result.redirectUrl().lastIndexOf('/') + 1);
        return storeService.orderForSession(sessionId).get(5, TimeUnit.SECONDS).orElseThrow().id();
    }

    private void payOrder(String orderId) throws Exception {
        Order order = storeService.allOrders(50).get(5, TimeUnit.SECONDS).stream()
                .filter(o -> o.id().equals(orderId)).findFirst().orElseThrow();
        boolean dispatched = storeService.confirmPayment(order.providerSessionId()).get(5, TimeUnit.SECONDS);
        assertTrue(dispatched);
        awaitDeliveryCount(orderId, 1);
    }

    private Delivery awaitSingleDelivery(String orderId) throws Exception {
        return awaitDeliveryCount(orderId, 1).get(0);
    }

    private List<Delivery> awaitDeliveryCount(String orderId, int expectedCount) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        List<Delivery> deliveries;
        do {
            deliveries = storeService.deliveriesForOrder(orderId).get(5, TimeUnit.SECONDS);
            if (deliveries.size() >= expectedCount) {
                return deliveries;
            }
            Thread.sleep(20);
        } while (System.currentTimeMillis() < deadline);
        assertEquals(expectedCount, deliveries.size(), "Nombre de livraisons attendu non atteint pour " + orderId);
        return deliveries;
    }
}
