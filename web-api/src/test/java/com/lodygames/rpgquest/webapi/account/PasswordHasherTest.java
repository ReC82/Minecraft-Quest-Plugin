package com.lodygames.rpgquest.webapi.account;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher(1_000);

    @Test
    void verifiesTheRightPasswordOnly() {
        String stored = hasher.hash("correct horse battery");

        assertTrue(stored.startsWith("pbkdf2-sha256$1000$"));
        assertFalse(stored.contains("correct horse battery"));
        assertTrue(hasher.verify("correct horse battery", stored));
        assertFalse(hasher.verify("correct horse batterY", stored));
    }

    @Test
    void saltsEveryHash() {
        assertNotEquals(hasher.hash("même mot de passe"), hasher.hash("même mot de passe"));
    }

    @Test
    void keepsVerifyingWithTheIterationCountStoredInTheHash() {
        String stored = new PasswordHasher(2_000).hash("motdepasse-solide");

        assertTrue(hasher.verify("motdepasse-solide", stored));
    }

    @Test
    void rejectsMalformedOrMissingHashes() {
        assertFalse(hasher.verify("x", null));
        assertFalse(hasher.verify("x", "plain-text"));
        assertFalse(hasher.verify("x", "pbkdf2-sha256$abc$AAAA$AAAA"));
        assertFalse(hasher.verify("x", "bcrypt$1$AAAA$AAAA"));
    }
}
