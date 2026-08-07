package be.lloyd.rpgquest.webapi.store;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Prestataire de paiement externe, en mode sandbox/test à ce stade (mission
 * étape 22, points 3/14). Une seule implémentation existe aujourd'hui —
 * {@link SandboxPaymentProvider}, une simulation auto-hébergée qui reproduit
 * fidèlement le flux d'un vrai PSP (session de paiement hébergée, webhook
 * signé HMAC) sans dépendre d'un compte/réseau externe indisponible dans cet
 * environnement. Voir docs/STORE.md pour la justification complète et le
 * point d'extension pour un futur fournisseur réel (Stripe/PayPal en mode
 * test) : il suffira d'implémenter cette même interface.
 */
public interface PaymentProvider {

    String payPageUrl(String sessionId);

    void registerSession(String sessionId, String orderId);

    Optional<String> orderIdForSession(String sessionId);

    /** Simule/déclenche la notification asynchrone du prestataire vers {@code /store/webhook}. */
    CompletableFuture<Void> dispatchWebhook(Order order, boolean succeeded);
}
