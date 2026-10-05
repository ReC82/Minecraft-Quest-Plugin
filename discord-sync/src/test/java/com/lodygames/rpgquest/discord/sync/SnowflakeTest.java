package com.lodygames.rpgquest.discord.sync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le repère temporel qui empêche l'import massif des anciens sujets (issue #202) repose sur la
 * comparaison d'identifiants Discord. Le piège est la <strong>comparaison signée</strong> : les
 * snowflakes actuels dépassent {@link Long#MAX_VALUE} si on les lit comme des entiers signés, ce
 * qui inverserait l'ordre et importerait exactement ce qu'on veut éviter.
 */
class SnowflakeTest {

    @Test
    @DisplayName("Un identifiant encode sa date de création, à la milliseconde près")
    void encodesItsCreationInstant() {
        Instant moment = Instant.parse("2026-10-05T12:00:00Z");

        Instant decoded = Snowflake.instantOf(Snowflake.forInstant(moment));

        assertTrue(Math.abs(decoded.toEpochMilli() - moment.toEpochMilli()) < 2, decoded.toString());
    }

    @Test
    @DisplayName("La comparaison est chronologique, dans les deux sens")
    void comparesChronologically() {
        String older = Snowflake.forInstant(Instant.parse("2026-01-01T00:00:00Z"));
        String newer = Snowflake.forInstant(Instant.parse("2026-10-05T00:00:00Z"));

        assertTrue(Snowflake.atOrAfter(newer, older));
        assertFalse(Snowflake.atOrAfter(older, newer));
        assertTrue(Snowflake.atOrAfter(newer, newer), "un identifiant est au niveau de lui-même");
    }

    @Test
    @DisplayName("La comparaison reste correcte sur un identifiant réel qui dépasse Long.MAX_VALUE "
            + "en lecture signée")
    void handlesUnsignedRange() {
        // Identifiant réel du serveur visé, et un identifiant bien plus ancien.
        String real = "1556300141164503190";
        String ancient = "81384788765712384";

        assertTrue(Snowflake.atOrAfter(real, ancient));
        assertFalse(Snowflake.atOrAfter(ancient, real));

        // Au-delà de 2^63 : la lecture signée donnerait un nombre négatif.
        String beyondSignedRange = "18446744073709551615";
        assertTrue(Snowflake.atOrAfter(beyondSignedRange, real),
                "une comparaison signée inverserait l'ordre ici");
    }

    @Test
    @DisplayName("Une date antérieure à l'époque Discord ne produit pas d'identifiant négatif")
    void clampsBeforeDiscordEpoch() {
        String clamped = Snowflake.forInstant(Instant.parse("2000-01-01T00:00:00Z"));

        assertTrue(Snowflake.atOrAfter(Snowflake.forInstant(Instant.now()), clamped));
        assertFalse(clamped.startsWith("-"), clamped);
    }
}
