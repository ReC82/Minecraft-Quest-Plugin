package com.lodygames.rpgquest.panel.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void verifiesTheCorrectPassword() {
        String hash = hasher.hash("un mot de passe correct");
        assertTrue(hasher.verify("un mot de passe correct", hash));
        assertFalse(hasher.verify("un autre mot de passe", hash));
    }

    @Test
    void encodedFormatIsPbkdf2Sha256WithSaltAndIterations() {
        String hash = hasher.hash("x");
        String[] parts = hash.split("\\$");
        assertTrue(parts.length == 4 && parts[0].equals("pbkdf2_sha256"));
        assertTrue(Integer.parseInt(parts[1]) >= 100_000);
    }

    @Test
    void twoHashesOfTheSamePasswordDifferBecauseOfTheSalt() {
        assertNotEquals(hasher.hash("same"), hasher.hash("same"));
    }

    @Test
    void garbageEncodedValueNeverVerifies() {
        assertFalse(hasher.verify("x", null));
        assertFalse(hasher.verify("x", "not-a-hash"));
        assertFalse(hasher.verify("x", "pbkdf2_sha256$abc$def"));
    }
}
