package com.lodygames.rpgquest.npc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Issue #325 — transforme un lien {@code https://minesk.in/<id>} en la texture signée qu'il
 * désigne, en lisant l'API publique de MineSkin ({@code api.mineskin.org/v2/skins/<id>}, sans clé,
 * gratuite).
 *
 * <p><strong>Pourquoi.</strong> Citizens ne sait pas lire un lien {@code minesk.in} : c'est une page
 * web, alors que {@code /npc skin --url} attend une image. Ce résolveur récupère ce que MineSkin a
 * déjà généré (valeur + signature) pour que le PNJ reçoive <em>exactement</em> ce skin.</p>
 *
 * <ul>
 *   <li>L'apparence par défaut est embarquée ({@link MineSkinTexture#DEFAULT}) : aucun appel réseau.</li>
 *   <li>Appel <strong>asynchrone</strong> (jamais sur le thread principal), délai borné.</li>
 *   <li>Seuls les liens au format strict de {@link NpcIdentityService#isValidMineSkinUrl} sont
 *       acceptés, et seul le domaine {@code api.mineskin.org} est interrogé : ce n'est pas un
 *       téléchargeur générique.</li>
 *   <li>Les textures obtenues sont gardées en mémoire (elles ne changent pas pour un même lien).</li>
 *   <li>Chaque échec a un motif exact ({@link Failure}) : jamais de remplacement silencieux.</li>
 * </ul>
 */
public class MineSkinResolver {

    /** Pourquoi un lien n'a pas pu être transformé en texture. */
    public enum Failure {
        /** Le lien n'a pas le format {@code https://minesk.in/<identifiant>}. */
        INVALID_LINK("Ce lien n'est pas un lien MineSkin (https://minesk.in/…)."),
        /** MineSkin ne connaît pas ce skin. */
        NOT_FOUND("MineSkin ne connaît pas ce skin : vérifiez le lien."),
        /** MineSkin n'a pas répondu, ou pas comme prévu. */
        UNREACHABLE("MineSkin ne répond pas pour le moment : réessayez dans un instant.");

        private final String message;

        Failure(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    /** Une texture, ou le motif exact de son absence. */
    public record Resolution(MineSkinTexture texture, Failure failure) {
        static Resolution ok(MineSkinTexture texture) {
            return new Resolution(texture, null);
        }

        static Resolution failed(Failure failure) {
            return new Resolution(null, failure);
        }

        public boolean ok() {
            return texture != null;
        }
    }

    private static final String API = "https://api.mineskin.org/v2/skins/";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern VALUE = Pattern.compile("\"value\"\\s*:\\s*\"([A-Za-z0-9+/=]+)\"");
    private static final Pattern SIGNATURE = Pattern.compile("\"signature\"\\s*:\\s*\"([A-Za-z0-9+/=]+)\"");

    private final Map<String, MineSkinTexture> cache = new ConcurrentHashMap<>();
    private final HttpClient http;

    public MineSkinResolver() {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    MineSkinResolver(HttpClient http) {
        this.http = http;
    }

    /** Texture du lien, résolue en tâche de fond. Ne s'exécute jamais sur le thread principal. */
    public CompletableFuture<Resolution> resolve(String mineSkinUrl) {
        if (!NpcIdentityService.isValidMineSkinUrl(mineSkinUrl)) {
            return CompletableFuture.completedFuture(Resolution.failed(Failure.INVALID_LINK));
        }
        String url = mineSkinUrl.trim();
        if (NpcIdentityService.DEFAULT_SKIN_URL.equals(url)) {
            return CompletableFuture.completedFuture(Resolution.ok(MineSkinTexture.DEFAULT));
        }
        String id = idOf(url);
        MineSkinTexture known = cache.get(id);
        if (known != null) {
            return CompletableFuture.completedFuture(Resolution.ok(known));
        }
        return fetch(id).thenApply(resolution -> {
            if (resolution.ok()) {
                cache.put(id, resolution.texture());
            }
            return resolution;
        });
    }

    /**
     * Empreinte de l'image d'un lien <strong>déjà résolu</strong> (ou de l'apparence par défaut),
     * sans appel réseau. Vide = inconnue pour l'instant, jamais « différente ».
     */
    public Optional<String> knownHash(String mineSkinUrl) {
        if (mineSkinUrl == null) {
            return Optional.empty();
        }
        String url = mineSkinUrl.trim();
        if (NpcIdentityService.DEFAULT_SKIN_URL.equals(url)) {
            return Optional.of(MineSkinTexture.DEFAULT_SKIN_HASH);
        }
        if (!NpcIdentityService.isValidMineSkinUrl(url)) {
            return Optional.empty();
        }
        MineSkinTexture known = cache.get(idOf(url));
        return known == null ? Optional.empty() : known.skinHash();
    }

    /** Lit la réponse JSON de l'API MineSkin. Pur : testable sans réseau. */
    static Resolution parse(int status, String body) {
        if (status == 404) {
            return Resolution.failed(Failure.NOT_FOUND);
        }
        if (status != 200 || body == null) {
            return Resolution.failed(Failure.UNREACHABLE);
        }
        Matcher value = VALUE.matcher(body);
        Matcher signature = SIGNATURE.matcher(body);
        if (!value.find() || !signature.find()) {
            return Resolution.failed(Failure.UNREACHABLE);
        }
        MineSkinTexture texture = new MineSkinTexture(value.group(1), signature.group(1));
        return texture.skinHash().isPresent() ? Resolution.ok(texture) : Resolution.failed(Failure.UNREACHABLE);
    }

    private CompletableFuture<Resolution> fetch(String id) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + id))
                .timeout(TIMEOUT)
                .header("User-Agent", "RPGQuest-Plugin")
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> parse(response.statusCode(), response.body()))
                    .exceptionally(err -> Resolution.failed(Failure.UNREACHABLE));
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(Resolution.failed(Failure.UNREACHABLE));
        }
    }

    private static String idOf(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }
}
