package com.lodygames.rpgquest.panel.ai;

import com.lodygames.rpgquest.panel.json.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fournisseur OpenAI, via l'API Chat Completions (issue #146).
 *
 * <p>Chat Completions est choisie plutôt que l'API Responses parce qu'elle est la plus largement
 * compatible : c'est la forme que réimplémentent la plupart des passerelles et des serveurs
 * compatibles (Azure OpenAI, mandataires d'entreprise, moteurs locaux). Avec une URL de base
 * surchargeable, ce même fournisseur couvre donc aussi ces déploiements, sans code supplémentaire.</p>
 *
 * <p>Particularités : authentification {@code Authorization: Bearer}, instructions système sous
 * forme de message de rôle {@code system}, et texte renvoyé dans
 * {@code choices[0].message.content}.</p>
 */
public final class OpenAiProvider extends HttpAiProvider {

    @Override
    public String id() {
        return "openai";
    }

    @Override
    public String label() {
        return "OpenAI (et API compatibles)";
    }

    @Override
    public String defaultModel() {
        return "gpt-4o";
    }

    @Override
    public String defaultBaseUrl() {
        return "https://api.openai.com";
    }

    @Override
    public String keyHelp() {
        return "Clé créée sur platform.openai.com, section « API keys ». Elle commence par « sk- ». "
                + "Pour une passerelle compatible (Azure, mandataire, moteur local), renseigner "
                + "aussi l'URL de base.";
    }

    @Override
    protected String path(AiProviderSettings settings) {
        return "/v1/chat/completions";
    }

    @Override
    protected Map<String, String> headers(AiProviderSettings settings) {
        return Map.of("Authorization", "Bearer " + settings.apiKey());
    }

    @Override
    protected String body(Request request, AiProviderSettings settings) {
        List<Object> messages = new java.util.ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            Map<String, Object> system = new LinkedHashMap<>();
            system.put("role", "system");
            system.put("content", request.systemPrompt());
            messages.add(system);
        }
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", request.userPrompt());
        messages.add(user);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model(settings));
        payload.put("messages", messages);
        payload.put("max_completion_tokens",
                Math.min(request.maxOutputTokens(), settings.maxOutputTokens()));
        return Json.write(payload);
    }

    @Override
    protected String extractText(Map<String, Object> response) {
        List<Object> choices = listAt(response, "choices");
        if (choices.isEmpty()) {
            return null;
        }
        String text = stringAt(mapAt(choices.get(0), "message"), "content");
        return text.isBlank() ? null : text;
    }

    @Override
    protected int[] extractUsage(Map<String, Object> response) {
        Map<String, Object> usage = mapAt(response, "usage");
        return new int[] {intAt(usage, "prompt_tokens"), intAt(usage, "completion_tokens")};
    }
}
