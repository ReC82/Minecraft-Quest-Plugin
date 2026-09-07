package com.lodygames.rpgquest.panel.bridge;

import com.lodygames.rpgquest.panel.config.Target;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Client du bridge d'administration du plugin (issue #37). Une seule route en V1 :
 * {@code GET <target.bridgeBaseUrl>/health}, authentifiée par {@code Authorization: Bearer <token>}.
 *
 * <p>Toute indisponibilité (connexion refusée, timeout, 401, réponse illisible) est convertie en
 * {@link BridgeException} avec un message <strong>affichable tel quel</strong> — jamais un HTTP 500
 * opaque côté panel, jamais une stacktrace ni un secret exposés.</p>
 */
public final class BridgeClient {

    private final HttpClient http;
    private final Duration requestTimeout;

    public BridgeClient() {
        this(Duration.ofSeconds(3), Duration.ofSeconds(4));
    }

    public BridgeClient(Duration connectTimeout, Duration requestTimeout) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.requestTimeout = requestTimeout;
    }

    public BridgeHealth health(Target target) {
        if (!target.bridgeUsable()) {
            throw new BridgeException("RPGQuest " + target.label()
                    + " : cible non configurée pour un accès live (bridge-url ou jeton manquant).");
        }
        String url = target.bridgeBaseUrl().replaceAll("/+$", "") + "/health";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + target.bridgeToken())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new BridgeException("RPGQuest " + target.label() + " injoignable : délai d'attente dépassé.", e);
        } catch (java.net.ConnectException e) {
            throw new BridgeException("RPGQuest " + target.label() + " injoignable : connexion refusée ("
                    + target.bridgeBaseUrl() + ").", e);
        } catch (java.io.IOException e) {
            throw new BridgeException("RPGQuest " + target.label() + " injoignable : " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BridgeException("RPGQuest " + target.label() + " : requête interrompue.", e);
        }

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new BridgeException("RPGQuest " + target.label()
                    + " : jeton du bridge refusé (HTTP " + response.statusCode() + "). Vérifier RPGQUEST_BRIDGE_TOKEN_"
                    + target.id().toUpperCase() + " et le jeton côté plugin.");
        }
        if (response.statusCode() != 200) {
            throw new BridgeException("RPGQuest " + target.label()
                    + " : le bridge a répondu HTTP " + response.statusCode() + ".");
        }
        try {
            return BridgeHealth.fromJson(response.body());
        } catch (RuntimeException e) {
            throw new BridgeException("RPGQuest " + target.label()
                    + " : réponse du bridge illisible (" + e.getMessage() + ").", e);
        }
    }
}
