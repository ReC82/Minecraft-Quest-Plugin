package com.lodygames.rpgquest.discord.sync;

import java.time.Instant;

/**
 * Identifiants Discord (« snowflakes »), utilisés ici comme <strong>horloge</strong>.
 *
 * <p>Un snowflake encode sa date de création. Comparer deux identifiants de sujet revient donc à
 * comparer leurs dates, sans appel réseau. C'est ce qui permet la règle « <strong>aucun import
 * massif des anciens sujets</strong> » : le service note à sa première exécution un repère
 * temporel, et ne traite ensuite spontanément que les sujets créés après ce repère. Les sujets
 * antérieurs n'entrent que par une adoption explicite.</p>
 */
public final class Snowflake {

    /** Époque Discord : 1er janvier 2015, 00:00:00 UTC, en millisecondes Unix. */
    private static final long DISCORD_EPOCH_MILLIS = 1_420_070_400_000L;

    private Snowflake() {
    }

    /** Snowflake minimal correspondant à un instant donné. */
    public static String forInstant(Instant instant) {
        long millis = instant.toEpochMilli() - DISCORD_EPOCH_MILLIS;
        if (millis < 0) {
            millis = 0;
        }
        return Long.toUnsignedString(millis << 22);
    }

    /** Instant de création encodé dans un identifiant. */
    public static Instant instantOf(String snowflake) {
        long value = Long.parseUnsignedLong(snowflake);
        return Instant.ofEpochMilli((value >>> 22) + DISCORD_EPOCH_MILLIS);
    }

    /**
     * {@code a} est-il postérieur ou égal à {@code b} ? Comparaison <strong>non signée</strong> :
     * les snowflakes récents dépassent {@link Long#MAX_VALUE} si on les lit comme signés.
     */
    public static boolean atOrAfter(String a, String b) {
        return Long.compareUnsigned(Long.parseUnsignedLong(a), Long.parseUnsignedLong(b)) >= 0;
    }
}
