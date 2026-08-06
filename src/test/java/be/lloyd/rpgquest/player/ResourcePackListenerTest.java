package be.lloyd.rpgquest.player;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

/**
 * L'envoi effectif du pack dépend de {@code Player#setResourcePack}, qui
 * nécessite un vrai/faux serveur complet ; ce test couvre le seul calcul pur
 * de la classe (conversion hexadécimale du SHA-1 déjà validé par {@code
 * ConfigValidator}), le reste étant exercé indirectement par {@code
 * ConfigValidatorTest} (parsing de {@code resource-pack.required}) et par
 * {@code RPGQuestPluginTest} (démarrage complet du plugin, écouteur inclus).
 */
class ResourcePackListenerTest {

    private final ResourcePackListener listener = new ResourcePackListener(null, NOPLogger.NOP_LOGGER);

    @Test
    void hexToBytesConvertsAFullSha1() {
        byte[] bytes = listener.hexToBytes("da39a3ee5e6b4b0d3255bfef95601890afd80709");
        assertEquals(20, bytes.length);
        assertArrayEquals(new byte[]{(byte) 0xda, 0x39, (byte) 0xa3, (byte) 0xee}, java.util.Arrays.copyOf(bytes, 4));
    }

    @Test
    void hexToBytesOfEmptyStringIsEmptyArray() {
        assertEquals(0, listener.hexToBytes("").length);
    }
}
