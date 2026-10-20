package com.lodygames.rpgquest.panel.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Issue #543 — catalogue des capacités tel que le plugin le publie (copie vérifiée par le plugin). */
final class AbilityFixtures {

    private AbilityFixtures() {
    }

    static String catalogJson() {
        try (InputStream in = AbilityFixtures.class.getResourceAsStream("/fixtures/mob-ability-catalog.json")) {
            if (in == null) {
                throw new IllegalStateException("fixtures/mob-ability-catalog.json absent");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
