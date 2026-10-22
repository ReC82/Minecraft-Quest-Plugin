package com.lodygames.rpgquest.panel.ai;

import com.lodygames.rpgquest.panel.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Socle commun aux fournisseurs d'IA interrogés en HTTP (issue #146) : un seul endroit pour le
 * client, les délais, la conversion des pannes en échecs lisibles, et la lecture défensive du JSON.
 *
 * <p>Chaque fournisseur ne fournit plus que ce qui lui est <strong>réellement</strong> propre :
 * le chemin, les en-têtes, le corps de la requête, et l'endroit où trouver le texte dans la
 * réponse. C'est le seul découpage qui évite de recopier trois fois la gestion d'erreur — et donc
 * de l'oublier une fois sur trois.</p>
 *
 * <p><strong>Aucune journalisation.</strong> Cette classe n'écrit rien dans les logs : ni la clé
 * (évidemment), ni le corps des requêtes, qui contient le contenu éditorial et pourrait être
 * volumineux. Les erreurs remontent par {@link AiProvider.Result}, et c'est l'appelant qui décide
 * ce qu'il en affiche. Depuis #739, le résultat porte aussi la trace expurgée de l'échange
 * ({@link AiProvider.Exchange}) : c'est l'appelant qui la range dans le journal IA du projet.</p>
 */
abstract class HttpAiProvider implements AiProvider {

    /**
     * Un seul client pour tout le panel : il gère son pool de connexions. Le délai de connexion est
     * court (la panne réseau doit se voir vite), le délai de lecture vient des réglages du
     * fournisseur — une génération longue est normale.
     */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** Taille maximale de réponse lue, garde-fou contre une réponse anormale. */
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    /** Chemin appelé, relatif à l'URL de base. */
    protected abstract String path(AiProviderSettings settings);

    /** En-têtes propres au fournisseur, dont l'authentification. */
    protected abstract Map<String, String> headers(AiProviderSettings settings);

    /** Corps JSON de la requête, déjà sérialisé. */
    protected abstract String body(Request request, AiProviderSettings settings);

    /**
     * Extrait le texte généré d'une réponse réussie.
     *
     * @return le texte, ou {@code null} si la forme de la réponse n'est pas celle attendue — ce qui
     *         produit un échec lisible plutôt qu'un {@code null} qui remonterait silencieusement
     */
    protected abstract String extractText(Map<String, Object> response);

    /** Jetons consommés, s'ils sont rapportés. {@code null} accepté pour chaque composante. */
    protected abstract int[] extractUsage(Map<String, Object> response);

    /** Modèle réellement utilisé, tel que la réponse le rapporte ; repli sur le modèle demandé. */
    protected String extractModel(Map<String, Object> response, AiProviderSettings settings) {
        Object m = response.get("model");
        return m == null ? model(settings) : String.valueOf(m);
    }

    /** Modèle effectif : celui choisi par l'administrateur, sinon le défaut du fournisseur. */
    protected final String model(AiProviderSettings settings) {
        return settings.model().isEmpty() ? defaultModel() : settings.model();
    }

    /** URL de base effective, sans barre oblique finale. */
    protected final String baseUrl(AiProviderSettings settings) {
        String raw = settings.baseUrl().isEmpty() ? defaultBaseUrl() : settings.baseUrl();
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }

    @Override
    public Result testConnection(AiProviderSettings settings) {
        // Un vrai aller-retour, volontairement minuscule : « configuration enregistrée » ne prouve
        // rien, et c'est exactement ce que le bouton de test doit démentir ou confirmer.
        Request probe = new Request(
                "Réponds exactement OK, sans rien d'autre.",
                "OK", 16, Duration.ofSeconds(Math.min(30, settings.timeoutSeconds())));
        return generate(probe, settings);
    }

    /** Fin de génération rapportée par le fournisseur (« stop », « length »…) ; vide si absente. */
    protected String extractFinishReason(Map<String, Object> response) {
        return "";
    }

    /**
     * Ce qui a circulé pendant un appel, relevé au fil de l'eau puis expurgé (#739). Aucun champ
     * n'est jamais journalisé ici : la trace remonte à l'appelant dans {@link Result#exchange()}.
     */
    private final class Capture {
        private final Request request;
        private final AiProviderSettings settings;
        private final java.time.Instant started = java.time.Instant.now();
        private String endpoint = "";
        private Map<String, String> headers = Map.of();
        private String body = "";
        private int status = -1;
        private String raw = "";
        private String requestId = "";
        private String finish = "";
        private String transport = "";

        Capture(Request request, AiProviderSettings settings) {
            this.request = request;
            this.settings = settings;
        }

        Result attach(Result outcome) {
            Result result = outcome;
            if (body.isEmpty()) {
                return result; // rien n'est parti : pas d'échange à tracer
            }
            List<String> notes = new java.util.ArrayList<>();
            String key = settings.apiKey();
            if (result.error() != null) {
                // Un fournisseur peut recopier la clé dans son message d'erreur : elle ne s'affiche ni
                // à l'écran ni dans le journal.
                result = new Result(result.ok(), result.text(), AiTrace.mask(result.error(), key,
                        "Message d'erreur", notes), result.model(), result.inputTokens(), result.outputTokens());
            }
            Map<String, String> sentHeaders = AiTrace.headers(headers, key, notes);
            AiTrace.Split req = AiTrace.split(body, "Q", request.images(), key, "Requête", notes);
            AiTrace.Split res = AiTrace.split(raw, "R", List.of(), key, "Réponse", notes);
            return result.withExchange(new Exchange(AiTrace.mask(endpoint, key, "Adresse appelée", notes),
                    sentHeaders, req.skeleton(), req.texts(), status, res.skeleton(), res.texts(),
                    AiTrace.mask(requestId, key, "Identifiant fournisseur", notes), finish, notes, started,
                    java.time.Instant.now(), AiTrace.mask(transport, key, "Erreur réseau", notes)));
        }
    }

    @Override
    public Result generate(Request request, AiProviderSettings settings) {
        Capture capture = new Capture(request, settings);
        Result result = send(request, settings, capture);
        try {
            return capture.attach(result);
        } catch (RuntimeException traceFailure) {
            // La trace ne doit JAMAIS coûter la réponse : l'appel est payé, son résultat remonte, et le
            // journal dit que la mise en forme de l'échange a échoué au lieu de prétendre qu'il n'a pas eu lieu.
            List<String> notes = new java.util.ArrayList<>();
            String key = settings.apiKey();
            notes.add("Trace de l'échange impossible à mettre en forme ("
                    + traceFailure.getClass().getSimpleName() + ") : corps envoyé et reçu non conservés.");
            return new Result(result.ok(), result.text(), result.error() == null ? null
                    : AiTrace.mask(result.error(), key, "Message d'erreur", notes), result.model(),
                    result.inputTokens(), result.outputTokens(), new Exchange(
                    AiTrace.mask(capture.endpoint, key, "Adresse appelée", notes), Map.of(), "", List.of(),
                    capture.status, "", List.of(), "", capture.finish, notes, capture.started,
                    java.time.Instant.now(), capture.transport));
        }
    }

    private Result send(Request request, AiProviderSettings settings, Capture capture) {
        if (!settings.hasKey()) {
            return Result.failure("Aucune clé API enregistrée pour « " + label() + " ».");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl(settings) + path(settings));
        } catch (RuntimeException e) {
            return Result.failure("URL de base invalide : " + settings.baseUrl());
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !isLoopback(uri)) {
            // Une clé d'API ne part jamais en clair. Seule une boucle locale est tolérée, pour un
            // mandataire d'entreprise ou un bouchon de test.
            return Result.failure("L'URL de base doit être en HTTPS (ou une adresse locale) : "
                    + "une clé API ne doit jamais circuler en clair.");
        }

        String body = body(request, settings);
        Map<String, String> sent = new java.util.LinkedHashMap<>();
        sent.put("Content-Type", "application/json");
        sent.putAll(headers(settings));
        capture.endpoint = "POST " + uri;
        capture.headers = sent;
        capture.body = body;
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(request.timeout() == null
                        ? Duration.ofSeconds(settings.timeoutSeconds()) : request.timeout())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        sent.forEach(builder::header);

        HttpResponse<byte[]> response;
        try {
            response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.net.http.HttpTimeoutException e) {
            capture.transport = "délai dépassé";
            return Result.failure("Délai dépassé après " + settings.timeoutSeconds()
                    + " s. Rien n'a été enregistré.");
        } catch (IOException e) {
            capture.transport = rootMessage(e);
            return Result.failure("Appel impossible : " + rootMessage(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            capture.transport = "appel interrompu";
            return Result.failure("Appel interrompu.");
        }

        byte[] bytes = response.body() == null ? new byte[0] : response.body();
        capture.status = response.statusCode();
        capture.requestId = response.headers().firstValue("x-request-id")
                .or(() -> response.headers().firstValue("request-id")).orElse("");
        if (bytes.length > MAX_RESPONSE_BYTES) {
            capture.raw = "(réponse de " + bytes.length + " octets non conservée : au-delà de la limite de "
                    + MAX_RESPONSE_BYTES + " octets lue par le panel)";
            return Result.failure("Réponse anormalement volumineuse (" + bytes.length + " octets).");
        }
        String raw = new String(bytes, StandardCharsets.UTF_8);
        capture.raw = raw;

        if (response.statusCode() / 100 != 2) {
            return Result.failure(httpError(response.statusCode(), raw));
        }

        Map<String, Object> parsed;
        try {
            Object o = Json.parse(raw);
            if (!(o instanceof Map<?, ?> m)) {
                return Result.failure("Réponse inattendue du fournisseur (ce n'est pas un objet JSON).");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            parsed = cast;
        } catch (RuntimeException e) {
            return Result.failure("Réponse illisible du fournisseur (JSON invalide).");
        }
        String bodyId = stringAt(parsed, "id").isBlank() ? stringAt(parsed, "responseId") : stringAt(parsed, "id");
        if (!bodyId.isBlank()) {
            capture.requestId = capture.requestId.isBlank() ? bodyId : bodyId + " (requête " + capture.requestId + ")";
        }
        capture.finish = extractFinishReason(parsed);

        String text = extractText(parsed);
        if (text == null || text.isBlank()) {
            return Result.failure("Le fournisseur a répondu sans texte exploitable. "
                    + "Réessayer, ou vérifier le modèle configuré.");
        }
        int[] usage = extractUsage(parsed);
        return Result.success(text, extractModel(parsed, settings),
                usage != null && usage.length > 0 && usage[0] >= 0 ? usage[0] : null,
                usage != null && usage.length > 1 && usage[1] >= 0 ? usage[1] : null);
    }

    /**
     * Message d'erreur HTTP exploitable. Les causes les plus fréquentes sont nommées explicitement :
     * un « 401 » brut n'aide personne, alors que « clé refusée » dit quoi faire.
     */
    private String httpError(int status, String raw) {
        String detail = providerErrorMessage(raw);
        String hint = switch (status) {
            case 401, 403 -> "Clé API refusée par le fournisseur — vérifier la clé et ses droits.";
            case 404 -> "Point d'accès ou modèle introuvable — vérifier le modèle et l'URL de base.";
            case 429 -> "Quota ou limite de débit atteinte chez le fournisseur.";
            case 400 -> "Requête refusée par le fournisseur.";
            default -> status >= 500 ? "Panne côté fournisseur." : "Appel refusé.";
        };
        return "HTTP " + status + " — " + hint + (detail.isBlank() ? "" : " Détail : " + detail);
    }

    /**
     * Extrait un message d'erreur du corps, quelle que soit la forme : les trois fournisseurs
     * utilisent {@code error.message} ou une variante, et une réponse d'erreur peut aussi ne pas
     * être du JSON. Le texte est tronqué — il part à l'écran.
     */
    private String providerErrorMessage(String raw) {
        try {
            if (Json.parse(raw) instanceof Map<?, ?> m) {
                Object err = m.get("error");
                if (err instanceof Map<?, ?> em && em.get("message") != null) {
                    return truncate(String.valueOf(em.get("message")));
                }
                if (err != null) {
                    return truncate(String.valueOf(err));
                }
                if (m.get("message") != null) {
                    return truncate(String.valueOf(m.get("message")));
                }
            }
        } catch (RuntimeException ignored) {
            // Corps non JSON : on retombe sur le texte brut tronqué.
        }
        return truncate(raw);
    }

    private static String truncate(String s) {
        String flat = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m == null || m.isBlank() ? cur.getClass().getSimpleName() : m;
    }

    private static boolean isLoopback(URI uri) {
        String host = uri.getHost();
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    // ---- Helpers de lecture défensive, partagés par les implémentations -------------------------

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> mapAt(Object parent, String key) {
        if (parent instanceof Map<?, ?> m && m.get(key) instanceof Map<?, ?> child) {
            return (Map<String, Object>) child;
        }
        return Map.of();
    }

    protected static List<Object> listAt(Object parent, String key) {
        if (parent instanceof Map<?, ?> m && m.get(key) instanceof List<?> list) {
            return List.copyOf(list);
        }
        return List.of();
    }

    protected static String stringAt(Object parent, String key) {
        if (parent instanceof Map<?, ?> m && m.get(key) != null && !(m.get(key) instanceof Map<?, ?>)
                && !(m.get(key) instanceof List<?>)) {
            return String.valueOf(m.get(key));
        }
        return "";
    }

    protected static int intAt(Object parent, String key) {
        if (parent instanceof Map<?, ?> m && m.get(key) instanceof Number n) {
            return n.intValue();
        }
        return -1;
    }
}
