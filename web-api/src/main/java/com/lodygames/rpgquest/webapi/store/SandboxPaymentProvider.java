package com.lodygames.rpgquest.webapi.store;

import com.lodygames.rpgquest.webapi.json.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prestataire de paiement sandbox auto-hébergé (mission étape 22, points
 * 3/14 : "prestataire de paiement externe en mode test", "mode
 * sandbox/test du prestataire"). Aucune donnée de carte bancaire n'est
 * jamais demandée ni stockée (point 4) : la page {@code /store/pay/{id}}
 * ne propose que "Payer (sandbox)" / "Simuler un échec".
 *
 * <p>Reproduit fidèlement le flux d'un vrai fournisseur (Stripe Checkout,
 * PayPal...) en mode test : une session de paiement hébergée, puis une
 * notification asynchrone signée envoyée à {@code /store/webhook} —
 * exactement comme un vrai webhook externe, à ceci près que
 * l'émetteur est ce même processus plutôt qu'un serveur tiers (aucun accès
 * réseau externe ni compte prestataire n'est disponible dans cet
 * environnement). Voir docs/STORE.md pour le détail de cette décision et le
 * point d'extension {@link PaymentProvider} pour un futur fournisseur
 * réel.</p>
 */
public final class SandboxPaymentProvider implements PaymentProvider {

    private final String publicBaseUrl;
    private final String webhookSecret;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<String, String> sessionToOrderId = new ConcurrentHashMap<>();

    public SandboxPaymentProvider(String publicBaseUrl, String webhookSecret) {
        this.publicBaseUrl = publicBaseUrl;
        this.webhookSecret = webhookSecret;
    }

    @Override
    public String payPageUrl(String sessionId) {
        return publicBaseUrl + "/store/pay/" + sessionId;
    }

    @Override
    public void registerSession(String sessionId, String orderId) {
        sessionToOrderId.put(sessionId, orderId);
    }

    @Override
    public Optional<String> orderIdForSession(String sessionId) {
        return Optional.ofNullable(sessionToOrderId.get(sessionId));
    }

    @Override
    public CompletableFuture<Void> dispatchWebhook(Order order, boolean succeeded) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("orderId", order.id());
        payload.put("sessionId", order.providerSessionId());
        payload.put("status", succeeded ? "paid" : "failed");
        String body = Json.write(payload);
        String signature = WebhookSigner.sign(webhookSecret, body.getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(publicBaseUrl + "/store/webhook"))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-Store-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenApply(response -> null);
    }
}
