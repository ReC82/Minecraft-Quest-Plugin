package com.lodygames.rpgquest.webapi.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecureTokensTest {

    @Test
    void linkCodesUseOnlyTheUnambiguousAlphabet() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String code = SecureTokens.newLinkCode();
            assertEquals(SecureTokens.CODE_LENGTH, code.length());
            assertTrue(code.chars().allMatch(c -> SecureTokens.CODE_ALPHABET.indexOf(c) >= 0), code);
            seen.add(code);
        }
        assertTrue(seen.size() > 495, "codes aléatoires attendus");
    }

    @Test
    void normalizesWhatAPlayerTypes() {
        assertEquals("ABCD2345", SecureTokens.normalizeCode("abcd-2345"));
        assertEquals("ABCD2345", SecureTokens.normalizeCode(" abcd 2345 "));
        assertEquals("", SecureTokens.normalizeCode("ABCD234"));
        assertEquals("", SecureTokens.normalizeCode("ABCD2340"), "0 n'est pas dans l'alphabet");
        assertEquals("", SecureTokens.normalizeCode(null));
    }

    @Test
    void tokensAreLongAndDistinct() {
        String a = SecureTokens.newToken();
        assertTrue(a.length() >= 43);
        assertNotEquals(a, SecureTokens.newToken());
        assertEquals(64, SecureTokens.sha256(a).length());
    }
}
