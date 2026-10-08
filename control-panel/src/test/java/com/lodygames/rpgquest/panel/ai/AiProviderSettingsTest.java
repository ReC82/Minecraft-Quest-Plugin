package com.lodygames.rpgquest.panel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #146 — la clé API et son stockage. C'est la partie du lot où une erreur ne se rattrape pas :
 * une clé qui fuit est une clé à révoquer.
 */
class AiProviderSettingsTest {

    private static final String KEY = "sk-ant-api03-SECRET-VALUE-0123456789";

    @TempDir
    Path tmp;

    private AiSettingsStore store;

    @BeforeEach
    void setUp() {
        store = new AiSettingsStore(tmp.resolve("panel.db").toString());
    }

    // ---- Non-divulgation -----------------------------------------------------------------------

    /**
     * Le {@code toString} généré d'un record imprime tous ses composants : il suffirait d'un journal
     * de debug, d'un message d'exception ou d'un {@code assertEquals} raté pour que la clé parte
     * dans les logs. Il est donc redéfini, et ce test le verrouille.
     */
    @Test
    void toStringNeverContainsTheKey() {
        AiProviderSettings s = new AiProviderSettings("anthropic", true, KEY, "", "", 4000, 90);

        String printed = s.toString();

        assertFalse(printed.contains(KEY), printed);
        assertFalse(printed.contains("SECRET"), printed);
        assertTrue(printed.contains("présente"), printed);
    }

    @Test
    void theMaskedFormRevealsNoCharacterOfTheKey() {
        AiProviderSettings s = new AiProviderSettings("anthropic", true, KEY, "", "", 4000, 90);

        String masked = s.masked();

        assertFalse(masked.contains("SECRET"), masked);
        assertFalse(masked.contains("sk-ant"), masked);
        // Pas même la fin de la clé, contrairement à l'usage courant.
        assertFalse(masked.contains("6789"), masked);
        assertTrue(masked.contains(String.valueOf(KEY.length())), "la longueur, elle, est utile");
    }

    @Test
    void noKeyIsSaidPlainly() {
        assertEquals("aucune clé enregistrée", AiProviderSettings.empty("anthropic").masked());
        assertEquals("", AiProviderSettings.empty("anthropic").fingerprint());
    }

    /** L'empreinte distingue deux clés sans en révéler aucune — utile après une rotation. */
    @Test
    void theFingerprintDistinguishesKeysWithoutRevealingThem() {
        AiProviderSettings a = new AiProviderSettings("x", true, KEY, "", "", 4000, 90);
        AiProviderSettings b = new AiProviderSettings("x", true, KEY + "2", "", "", 4000, 90);

        assertEquals(8, a.fingerprint().length(), "courte et lisible");
        assertNotEquals(a.fingerprint(), b.fingerprint());
        assertFalse(KEY.contains(a.fingerprint()), "l'empreinte n'est pas un morceau de la clé");
        assertEquals(a.fingerprint(),
                new AiProviderSettings("x", false, KEY, "autre", "autre", 1, 1).fingerprint(),
                "elle ne dépend que de la clé");
    }

    @Test
    void withoutKeyStripsTheSecretButKeepsTheRest() {
        AiProviderSettings s = new AiProviderSettings("anthropic", true, KEY, "https://x", "m", 10, 20);

        AiProviderSettings stripped = s.withoutKey();

        assertFalse(stripped.hasKey());
        assertEquals("https://x", stripped.baseUrl());
        assertEquals("m", stripped.model());
        assertTrue(stripped.enabled());
    }

    // ---- Utilisabilité -------------------------------------------------------------------------

    @Test
    void aProviderIsUsableOnlyWhenEnabledAndKeyed() {
        assertFalse(new AiProviderSettings("x", false, KEY, "", "", 0, 0).usable(), "désactivé");
        assertFalse(new AiProviderSettings("x", true, "", "", "", 0, 0).usable(), "sans clé");
        assertTrue(new AiProviderSettings("x", true, KEY, "", "", 0, 0).usable());
    }

    @Test
    void absurdLimitsFallBackToDefaults() {
        AiProviderSettings s = new AiProviderSettings("x", true, KEY, "", "", 0, -5);

        assertEquals(AiProviderSettings.DEFAULT_MAX_OUTPUT_TOKENS, s.maxOutputTokens());
        assertEquals(AiProviderSettings.DEFAULT_TIMEOUT_SECONDS, s.timeoutSeconds());
    }

    // ---- Stockage ------------------------------------------------------------------------------

    @Test
    void anUnknownProviderReadsAsEmptyRatherThanFailing() {
        AiProviderSettings s = store.get("jamais-configure");

        assertFalse(s.hasKey());
        assertFalse(s.enabled());
    }

    @Test
    void savingThenReadingRoundTripsEveryField() {
        store.save(new AiProviderSettings("anthropic", true, KEY, "https://proxy.local",
                "claude-x", 1234, 42), "alice");

        AiProviderSettings s = store.get("anthropic");

        assertTrue(s.enabled());
        assertEquals(KEY, s.apiKey());
        assertEquals("https://proxy.local", s.baseUrl());
        assertEquals("claude-x", s.model());
        assertEquals(1234, s.maxOutputTokens());
        assertEquals(42, s.timeoutSeconds());
    }

    /**
     * Le formulaire ne réaffiche jamais la clé, donc un champ vide signifie « ne pas y toucher ».
     * L'interpréter comme « effacer » perdrait la clé au premier changement de modèle.
     */
    @Test
    void anEmptyKeyFieldKeepsTheStoredKey() {
        store.save(new AiProviderSettings("anthropic", true, KEY, "", "", 4000, 90), "alice");

        store.save(new AiProviderSettings("anthropic", true, "", "", "claude-y", 4000, 90), "bob");

        AiProviderSettings s = store.get("anthropic");
        assertEquals(KEY, s.apiKey(), "la clé doit avoir survécu");
        assertEquals("claude-y", s.model(), "et le reste avoir changé");
    }

    @Test
    void aNewKeyReplacesTheOldOne() {
        store.save(new AiProviderSettings("anthropic", true, KEY, "", "", 4000, 90), "alice");

        store.save(new AiProviderSettings("anthropic", true, "sk-nouvelle", "", "", 4000, 90), "alice");

        assertEquals("sk-nouvelle", store.get("anthropic").apiKey());
    }

    /** Effacer est un geste explicite, et désactive le fournisseur : sans clé il est inutilisable. */
    @Test
    void clearingTheKeyAlsoDisablesTheProvider() {
        store.save(new AiProviderSettings("anthropic", true, KEY, "https://x", "m", 4000, 90), "alice");

        store.clearKey("anthropic", "alice");

        AiProviderSettings s = store.get("anthropic");
        assertFalse(s.hasKey());
        assertFalse(s.enabled());
        assertEquals("https://x", s.baseUrl(), "le reste de la configuration est conservé");
        assertEquals("m", s.model());
    }

    @Test
    void theLastChangeIsTraceableWithoutTheKey() {
        store.save(new AiProviderSettings("anthropic", true, KEY, "", "", 4000, 90), "alice");

        String change = store.lastChange("anthropic");

        assertTrue(change.contains("alice"), change);
        assertFalse(change.contains(KEY));
        assertEquals("", store.lastChange("jamais-configure"));
    }

    @Test
    void providersAreIsolatedFromEachOther() {
        store.save(new AiProviderSettings("anthropic", true, KEY, "", "", 4000, 90), "alice");

        assertFalse(store.get("openai").hasKey());
        assertEquals(KEY, store.get("anthropic").apiKey());
    }
}
