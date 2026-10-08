package com.lodygames.rpgquest.panel.ai;

import com.lodygames.rpgquest.panel.json.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fournisseur Google Gemini, via {@code generateContent} (issue #146).
 *
 * <p>Particularités réelles, assez différentes des deux autres : l'authentification passe par
 * l'en-tête {@code x-goog-api-key}, le <strong>modèle fait partie du chemin</strong> et non du corps,
 * les instructions système vont dans {@code systemInstruction}, le contenu utilisateur dans
 * {@code contents[].parts[].text}, et le plafond de sortie s'appelle
 * {@code generationConfig.maxOutputTokens}.</p>
 *
 * <p>Le modèle étant dans l'URL, il est <strong>encodé</strong> avant concaténation : un nom de
 * modèle vient de la configuration, donc d'une saisie humaine, et ne doit pas pouvoir altérer le
 * chemin appelé.</p>
 */
public final class GeminiProvider extends HttpAiProvider {

    @Override
    public String id() {
        return "gemini";
    }

    @Override
    public String label() {
        return "Google Gemini";
    }

    @Override
    public String defaultModel() {
        return "gemini-2.5-flash";
    }

    @Override
    public String defaultBaseUrl() {
        return "https://generativelanguage.googleapis.com";
    }

    @Override
    public String keyHelp() {
        return "Clé créée dans Google AI Studio (aistudio.google.com), « Get API key ».";
    }

    @Override
    protected String path(AiProviderSettings settings) {
        // Le modèle fait partie du chemin : il est encodé, parce qu'il vient d'une saisie.
        String model = java.net.URLEncoder.encode(model(settings), java.nio.charset.StandardCharsets.UTF_8);
        return "/v1beta/models/" + model + ":generateContent";
    }

    @Override
    protected Map<String, String> headers(AiProviderSettings settings) {
        return Map.of("x-goog-api-key", settings.apiKey());
    }

    @Override
    protected String body(Request request, AiProviderSettings settings) {
        Map<String, Object> userPart = new LinkedHashMap<>();
        userPart.put("text", request.userPrompt());
        Map<String, Object> userContent = new LinkedHashMap<>();
        userContent.put("role", "user");
        userContent.put("parts", List.of(userPart));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("maxOutputTokens",
                Math.min(request.maxOutputTokens(), settings.maxOutputTokens()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contents", List.of(userContent));
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            Map<String, Object> systemPart = new LinkedHashMap<>();
            systemPart.put("text", request.systemPrompt());
            Map<String, Object> systemInstruction = new LinkedHashMap<>();
            systemInstruction.put("parts", List.of(systemPart));
            payload.put("systemInstruction", systemInstruction);
        }
        payload.put("generationConfig", generationConfig);
        return Json.write(payload);
    }

    @Override
    protected String extractText(Map<String, Object> response) {
        List<Object> candidates = listAt(response, "candidates");
        if (candidates.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Object part : listAt(mapAt(candidates.get(0), "content"), "parts")) {
            sb.append(stringAt(part, "text"));
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    @Override
    protected int[] extractUsage(Map<String, Object> response) {
        Map<String, Object> usage = mapAt(response, "usageMetadata");
        return new int[] {intAt(usage, "promptTokenCount"), intAt(usage, "candidatesTokenCount")};
    }

    /** Gemini ne renvoie pas de champ {@code model} de premier niveau : on garde celui demandé. */
    @Override
    protected String extractModel(Map<String, Object> response, AiProviderSettings settings) {
        String reported = stringAt(response, "modelVersion");
        return reported.isBlank() ? model(settings) : reported;
    }
}
