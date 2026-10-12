package com.lodygames.rpgquest.panel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.json.Json;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #283 — chaque fournisseur reçoit les PIXELS dans le format que son API documente, et une
 * demande sans image garde exactement l'ancienne forme.
 */
class ProviderImagePayloadTest {

    private static final AiProviderSettings SETTINGS =
            new AiProviderSettings("x", true, "k", "", "", 4000, 30);
    private static final AiProvider.Image IMG = new AiProvider.Image("image/png", new byte[] {1, 2, 3});
    private static final AiProvider.Request WITH = new AiProvider.Request("sys", "décris", 100,
            Duration.ofSeconds(5), List.of(IMG));
    private static final AiProvider.Request WITHOUT = new AiProvider.Request("sys", "décris", 100,
            Duration.ofSeconds(5));

    @Test
    void openAiGetsAnImageUrlPartWithADataUrl() {
        String body = new OpenAiProvider().body(WITH, SETTINGS);
        assertTrue(body.contains("\"type\":\"image_url\""), body);
        assertTrue(body.contains("data:image/png;base64,AQID"), body);
        assertTrue(body.contains("\"type\":\"text\""), body);
        assertFalse(new OpenAiProvider().body(WITHOUT, SETTINGS).contains("image_url"));
        Map<String, Object> plain = Json.parseObject(new OpenAiProvider().body(WITHOUT, SETTINGS));
        assertEquals("décris", ((Map<?, ?>) ((List<?>) plain.get("messages")).get(1)).get("content"),
                "sans image, le contenu reste une simple chaîne");
    }

    @Test
    void anthropicGetsABase64ImageBlockBeforeTheText() {
        String body = new AnthropicProvider().body(WITH, SETTINGS);
        assertTrue(body.contains("\"type\":\"image\""), body);
        assertTrue(body.contains("\"media_type\":\"image/png\""), body);
        assertTrue(body.contains("\"data\":\"AQID\""), body);
        assertTrue(body.indexOf("\"type\":\"image\"") < body.indexOf("\"type\":\"text\""));
    }

    @Test
    void geminiGetsInlineData() {
        String body = new GeminiProvider().body(WITH, SETTINGS);
        assertTrue(body.contains("\"inlineData\""), body);
        assertTrue(body.contains("\"mimeType\":\"image/png\""), body);
        assertTrue(body.contains("\"data\":\"AQID\""), body);
    }
}
