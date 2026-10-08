package com.lodygames.rpgquest.panel.ai;

import com.lodygames.rpgquest.panel.json.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fournisseur Anthropic / Claude, via l'API Messages (issue #146).
 *
 * <p>Particularités réelles de cette API, par rapport aux deux autres : l'authentification passe par
 * l'en-tête {@code x-api-key} et non par {@code Authorization: Bearer}, une version d'API est
 * <strong>obligatoire</strong> ({@code anthropic-version}), les instructions système occupent un
 * champ {@code system} de premier niveau plutôt qu'un message de rôle {@code system}, et le texte
 * revient dans un tableau {@code content} de blocs typés dont il faut prendre ceux de type
 * {@code text}.</p>
 */
public final class AnthropicProvider extends HttpAiProvider {

    /**
     * Version d'API envoyée à chaque appel. Elle est épinglée exprès : Anthropic exige cet en-tête,
     * et laisser le fournisseur choisir reviendrait à accepter un changement de comportement sans
     * s'en apercevoir.
     */
    private static final String API_VERSION = "2023-06-01";

    @Override
    public String id() {
        return "anthropic";
    }

    @Override
    public String label() {
        return "Anthropic (Claude)";
    }

    @Override
    public String defaultModel() {
        return "claude-sonnet-4-5";
    }

    @Override
    public String defaultBaseUrl() {
        return "https://api.anthropic.com";
    }

    @Override
    public String keyHelp() {
        return "Clé créée dans la console Anthropic (console.anthropic.com), onglet « API Keys ». "
                + "Elle commence par « sk-ant- ».";
    }

    @Override
    protected String path(AiProviderSettings settings) {
        return "/v1/messages";
    }

    @Override
    protected Map<String, String> headers(AiProviderSettings settings) {
        return Map.of(
                "x-api-key", settings.apiKey(),
                "anthropic-version", API_VERSION);
    }

    @Override
    protected String body(Request request, AiProviderSettings settings) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", request.userPrompt());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model(settings));
        payload.put("max_tokens", Math.min(request.maxOutputTokens(), settings.maxOutputTokens()));
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            payload.put("system", request.systemPrompt());
        }
        payload.put("messages", List.of(message));
        return Json.write(payload);
    }

    @Override
    protected String extractText(Map<String, Object> response) {
        StringBuilder sb = new StringBuilder();
        for (Object block : listAt(response, "content")) {
            if ("text".equals(stringAt(block, "type"))) {
                sb.append(stringAt(block, "text"));
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    @Override
    protected int[] extractUsage(Map<String, Object> response) {
        Map<String, Object> usage = mapAt(response, "usage");
        return new int[] {intAt(usage, "input_tokens"), intAt(usage, "output_tokens")};
    }
}
