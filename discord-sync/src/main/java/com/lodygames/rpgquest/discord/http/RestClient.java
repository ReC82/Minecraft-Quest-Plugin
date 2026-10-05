package com.lodygames.rpgquest.discord.http;

import com.lodygames.rpgquest.discord.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client REST commun à Discord et GitHub (issue #202) : <strong>bornes, backoff et respect des
 * limites de débit</strong>, sans aucune dépendance externe ({@code java.net.http} du JDK).
 *
 * <p><strong>Ce que cette classe garantit, et pourquoi.</strong></p>
 * <ul>
 *   <li><strong>Jamais de boucle de réessai infinie.</strong> Le nombre de tentatives est borné et
 *       l'attente croît (backoff exponentiel plafonné). Un service qui réessaie sans fin finit par
 *       être banni par l'API distante.</li>
 *   <li><strong>429 honoré à la lettre.</strong> Discord et GitHub indiquent le délai à respecter
 *       ({@code Retry-After}, {@code x-ratelimit-reset-after}) : on l'attend au lieu de deviner.</li>
 *   <li><strong>Seuls les échecs rejouables sont rejoués.</strong> 5xx, 429 et coupures réseau oui ;
 *       un 4xx de validation (403, 404, 422…) non — le rejouer ne changerait rien et masquerait
 *       l'erreur réelle.</li>
 *   <li><strong>Corps de réponse borné.</strong> Une réponse démesurée est tronquée pour la trace,
 *       jamais chargée sans limite en mémoire.</li>
 *   <li><strong>Aucun secret journalisé.</strong> Les en-têtes d'autorisation ne sont jamais
 *       rendus ; seules l'URL, la méthode et le code HTTP le sont.</li>
 * </ul>
 */
public final class RestClient {

    private static final Logger LOG = Logger.getLogger(RestClient.class.getName());

    /** Au-delà, le corps n'est plus conservé pour la trace (les réponses utiles sont petites). */
    private static final int MAX_LOGGED_BODY = 2_000;

    private static final int MAX_ATTEMPTS = 4;
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(2);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

    private final HttpClient http;
    private final String userAgent;
    private final Sleeper sleeper;

    /** Abstraction de l'attente : les tests n'attendent pas réellement. */
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    public RestClient(String userAgent) {
        this(userAgent, duration -> Thread.sleep(Math.max(0L, duration.toMillis())));
    }

    public RestClient(String userAgent, Sleeper sleeper) {
        this.userAgent = userAgent;
        this.sleeper = sleeper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Réponse brute d'un appel, déjà décodée quand c'est du JSON.
     *
     * @param status    code HTTP
     * @param body      corps brut (tronqué au-delà de {@link #MAX_LOGGED_BODY} pour la trace)
     * @param json      corps décodé si l'appel a renvoyé du JSON, sinon {@code null}
     * @param etag      en-tête {@code ETag}, utile aux requêtes conditionnelles GitHub
     */
    public record Result(int status, String body, Object json, String etag) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public boolean notModified() {
            return status == 304;
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> object() {
            return json instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }

        @SuppressWarnings("unchecked")
        public List<Object> array() {
            return json instanceof List<?> list ? (List<Object>) list : List.of();
        }

        /** Message d'erreur lisible, destiné au journal : jamais un secret, jamais un corps géant. */
        public String errorMessage() {
            String detail = body == null ? "" : body.strip();
            if (detail.length() > 300) {
                detail = detail.substring(0, 300) + "… (tronqué)";
            }
            Object message = object().get("message");
            if (message instanceof String s && !s.isBlank()) {
                return "HTTP " + status + " : " + s;
            }
            return detail.isEmpty() ? "HTTP " + status : "HTTP " + status + " : " + detail;
        }
    }

    /** Appel sans corps de requête. */
    public Result call(String method, URI uri, Map<String, String> headers) {
        return call(method, uri, headers, null);
    }

    /**
     * Exécute l'appel avec réessais bornés.
     *
     * @param bodyJson corps à sérialiser en JSON, ou {@code null} pour aucun corps
     * @throws RestException échec non rejouable, ou tentatives épuisées
     */
    public Result call(String method, URI uri, Map<String, String> headers, Object bodyJson) {
        Duration backoff = FIRST_BACKOFF;
        RestException last = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Result result;
            try {
                result = execute(method, uri, headers, bodyJson);
            } catch (IOException e) {
                // Coupure réseau : l'appel a peut-être abouti côté serveur. On le signale comme
                // ambigu, c'est l'appelant qui décidera (jamais de création rejouée à l'aveugle).
                last = new RestException("Appel " + method + " " + path(uri) + " interrompu : "
                        + e.getClass().getSimpleName() + " — " + e.getMessage(), 0, true);
                LOG.log(Level.WARNING, "{0} (tentative {1}/{2})",
                        new Object[] {last.getMessage(), attempt, MAX_ATTEMPTS});
                if (attempt == MAX_ATTEMPTS) {
                    throw last;
                }
                backoff = waitThenGrow(backoff);
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RestException("Appel " + method + " " + path(uri) + " interrompu à l'arrêt "
                        + "du service.", 0, true);
            }

            if (result.ok() || result.notModified()) {
                return result;
            }

            if (result.status() == 429 || result.status() >= 500) {
                Duration wait = retryAfter(result).orElse(backoff);
                if (attempt == MAX_ATTEMPTS) {
                    throw new RestException("Appel " + method + " " + path(uri)
                            + " en échec après " + MAX_ATTEMPTS + " tentatives : "
                            + result.errorMessage(), result.status(), true);
                }
                LOG.log(Level.WARNING, "{0} {1} -> {2} ; nouvelle tentative dans {3} s ({4}/{5})",
                        new Object[] {method, path(uri), result.status(), wait.toSeconds(),
                                attempt, MAX_ATTEMPTS});
                sleepQuietly(wait);
                backoff = grow(backoff);
                continue;
            }

            // 4xx de validation : rejouer ne changerait rien et masquerait la vraie cause.
            throw new RestException("Appel " + method + " " + path(uri) + " refusé : "
                    + result.errorMessage(), result.status(), false);
        }
        throw last == null
                ? new RestException("Appel " + method + " " + path(uri) + " en échec.", 0, true)
                : last;
    }

    private Result execute(String method, URI uri, Map<String, String> headers, Object bodyJson)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json");

        Map<String, String> all = new LinkedHashMap<>(headers == null ? Map.of() : headers);
        all.forEach(builder::header);

        if (bodyJson == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(Json.write(bodyJson)));
        }

        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String body = response.body() == null ? "" : response.body();
        Object json = null;
        if (!body.isBlank() && (body.charAt(0) == '{' || body.charAt(0) == '[')) {
            try {
                json = Json.parse(body);
            } catch (RuntimeException e) {
                json = null;  // corps non-JSON ou tronqué : on garde le texte brut pour la trace
            }
        }
        String etag = response.headers().firstValue("ETag").orElse(null);
        return new Result(response.statusCode(), truncate(body), json, etag);
    }

    /** Délai imposé par l'API distante, quand elle en indique un. */
    private static Optional<Duration> retryAfter(Result result) {
        Object after = result.object().get("retry_after");
        if (after instanceof Number n) {
            return Optional.of(Duration.ofMillis((long) (n.doubleValue() * 1000)));
        }
        return Optional.empty();
    }

    private Duration waitThenGrow(Duration backoff) {
        sleepQuietly(backoff);
        return grow(backoff);
    }

    private static Duration grow(Duration backoff) {
        Duration doubled = backoff.multipliedBy(2);
        return doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
    }

    private void sleepQuietly(Duration duration) {
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RestException("Attente interrompue à l'arrêt du service.", 0, true);
        }
    }

    private static String truncate(String body) {
        return body.length() <= MAX_LOGGED_BODY ? body : body.substring(0, MAX_LOGGED_BODY);
    }

    /** Chemin seul : une URL complète peut porter des paramètres, jamais un secret, mais restons sobres. */
    private static String path(URI uri) {
        return uri.getHost() + uri.getPath();
    }
}
