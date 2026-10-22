package com.lodygames.rpgquest.panel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #739 — la mise en forme des échanges pour le journal IA : rôles conservés, textes entiers,
 * pixels et secrets retirés, et chaque retrait signalé.
 */
class AiTraceTest {

    private static final String KEY = "sk-proj-ABCDEFGHIJKLMNOP1234";
    private static final String LONG = "Consignes système.\n".repeat(20);

    private static AiProviderSettings settings() {
        return new AiProviderSettings("x", true, KEY, "", "", 4000, 30);
    }

    private static AiProvider.Request request(AiProvider.Image image) {
        return new AiProvider.Request(LONG, "Construis une bibliothèque.\n" + "Détails. ".repeat(30), 4000,
                Duration.ofSeconds(5), List.of(image));
    }

    @Test
    void anthropicBodyKeepsRolesAndReplacesPixels() {
        AiProvider.Image image = new AiProvider.Image("image/png", new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9});
        List<String> notes = new ArrayList<>();
        String body = new AnthropicProvider().body(request(image), settings());
        AiTrace.Split split = AiTrace.split(body, "Q", List.of(image), KEY, "Requête", notes);

        assertFalse(split.skeleton().contains(image.base64()), "aucun base64 dans le journal");
        assertTrue(split.skeleton().contains("⟦image I1 : image/png, 9 octets, sha256 "));
        assertTrue(split.skeleton().contains("\"max_tokens\""), "les paramètres restent visibles");
        Map<String, String> roles = new LinkedHashMap<>();
        split.texts().forEach(t -> roles.put(t.path(), t.role()));
        assertEquals("system", roles.get("system"));
        assertEquals("user", roles.get("messages[0].content[1].text"));
        assertEquals(LONG, split.texts().get(0).content(), "texte reproduit en entier");
        assertTrue(notes.stream().anyMatch(n -> n.contains("pixels transmis")));
    }

    @Test
    void geminiSystemInstructionIsNamedAsSuch() {
        AiProvider.Image image = new AiProvider.Image("image/jpeg", new byte[] {9, 8, 7});
        String body = new GeminiProvider().body(request(image), settings());
        AiTrace.Split split = AiTrace.split(body, "Q", List.of(image), KEY, "Requête", new ArrayList<>());
        assertTrue(split.texts().stream().anyMatch(t -> t.role().equals("systemInstruction")));
        assertTrue(split.texts().stream().anyMatch(t -> t.role().equals("user")));
        assertFalse(split.skeleton().contains(image.base64()));
    }

    @Test
    void secretsAreMaskedAndEachMaskingIsListed() {
        List<String> notes = new ArrayList<>();
        Map<String, String> headers = AiTrace.headers(Map.of("Authorization", "Bearer " + KEY,
                "anthropic-version", "2023-06-01"), KEY, notes);
        assertEquals(AiTrace.MASK, headers.get("Authorization"));
        assertEquals("2023-06-01", headers.get("anthropic-version"), "un en-tête utile reste lisible");

        String masked = AiTrace.mask("clé " + KEY + " et autre sk-otherSECRET123456 et AIzaSyA1234567890123456789012345"
                + " et https://h/x?key=abc123&model=m", KEY, "Réponse", notes);
        assertFalse(masked.contains(KEY));
        assertFalse(masked.contains("sk-otherSECRET123456"));
        assertFalse(masked.contains("AIzaSyA"));
        assertFalse(masked.contains("abc123"));
        assertTrue(masked.contains("model=m"), "le reste de l'adresse est conservé");
        assertTrue(notes.size() >= 4, notes.toString());
        assertTrue(notes.stream().noneMatch(n -> n.contains(KEY)), "une note ne cite jamais la valeur");
    }

    @Test
    void aNonJsonBodyIsKeptWholeAsText() {
        AiTrace.Split split = AiTrace.split("<html>502 Bad Gateway</html>", "R", List.of(), KEY, "Réponse",
                new ArrayList<>());
        assertEquals("", split.skeleton());
        assertEquals("<html>502 Bad Gateway</html>", split.texts().get(0).content());
    }

    @Test
    void jsonNullsFromARealProviderAreKeptAndNeverThrow() {
        AiTrace.Split split = AiTrace.split("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\","
                + "\"refusal\":null},\"logprobs\":null,\"finish_reason\":\"stop\"}]}", "R", List.of(), KEY, "Réponse",
                new ArrayList<>());
        assertTrue(split.skeleton().contains("\"refusal\": null"), split.skeleton());
        assertTrue(split.skeleton().contains("\"logprobs\": null"));
    }
}
