package com.lodygames.rpgquest.web.push;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;

/**
 * Pousse {@code snapshot.json} au portail (issue #421) : {@code POST <base-url>/api/snapshot} avec
 * le jeton dédié. Le serveur Minecraft initie, AWS reçoit — même sens que l'agent PlugAdmin.
 *
 * <p>{@link #push} est <em>bloquant</em> et doit être appelé depuis le fil d'export
 * ({@code RPGQuest-WebExport}), jamais depuis le thread principal. Il ne lève jamais d'exception :
 * un échec est journalisé une fois (sans jeton ni contenu), puis le retour à la normale l'est aussi,
 * pour ne pas inonder la console toutes les 30 secondes pendant une panne.</p>
 */
public class SnapshotPushClient {

    /** Résultat d'un envoi, pour les tests et le journal. */
    public enum Outcome { STORED, REJECTED, UNAVAILABLE }

    private final SnapshotPushConfig config;
    private final HttpClient httpClient;
    private final Logger logger;
    private volatile boolean failing;

    public SnapshotPushClient(SnapshotPushConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Outcome push(String snapshotJson) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/snapshot"))
                .timeout(Duration.ofMillis(config.requestTimeoutMs()))
                .header("Authorization", "Bearer " + config.token())
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(snapshotJson, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 200) {
                recovered();
                return Outcome.STORED;
            }
            failed("le portail a répondu HTTP " + response.statusCode() + describe(response.statusCode()));
            return Outcome.REJECTED;
        } catch (IOException e) {
            failed("portail injoignable (" + e.getClass().getSimpleName() + ")");
            return Outcome.UNAVAILABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.UNAVAILABLE;
        }
    }

    private static String describe(int status) {
        return switch (status) {
            case 401 -> " : jeton refusé (vérifier token des deux côtés)";
            case 404 -> " : route absente (portail trop ancien ou nginx non à jour)";
            case 413 -> " : instantané trop gros (snapshot-ingest-max-bytes du portail)";
            case 400 -> " : instantané refusé comme invalide";
            default -> "";
        };
    }

    private void failed(String reason) {
        if (!failing) {
            failing = true;
            logger.warn("Envoi de l'instantané au portail {} impossible : {}. Nouvel essai à chaque export ; "
                    + "message suivant au rétablissement.", config.baseUrl(), reason);
        }
    }

    private void recovered() {
        if (failing) {
            failing = false;
            logger.info("Envoi de l'instantané au portail {} rétabli.", config.baseUrl());
        }
    }
}
