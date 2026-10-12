package com.lodygames.rpgquest.panel.building;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #283 — les images de référence : type vérifié sur les octets, bornes, effacement réel. */
class ReferenceImageStoreTest {

    @TempDir
    Path tmp;

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1};

    @Test
    void theTypeIsReadOnTheBytesNeverOnTheName() {
        ReferenceImageStore store = new ReferenceImageStore(tmp.resolve("cp.db").toString());
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        var refused = store.add("p1", "facade.png", html, "owner");
        assertTrue(refused.image().isEmpty());
        assertTrue(refused.refusal().contains("n'est pas une image"), refused.refusal());
        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8);
        assertTrue(store.add("p1", "logo.svg", svg, "owner").image().isEmpty(), "SVG = script possible");
        assertEquals("image/jpeg", store.add("p1", "photo.png", JPEG, "owner").image().orElseThrow().mime(),
                "un JPEG nommé .png reste un JPEG");
    }

    @Test
    void sizeCountAndDuplicatesAreBounded() {
        ReferenceImageStore store = new ReferenceImageStore(tmp.resolve("cp.db").toString());
        byte[] big = new byte[ReferenceImageStore.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertTrue(store.add("p1", "big.png", big, "owner").refusal().contains("trop lourde"));
        assertTrue(store.add("p1", "a.png", PNG, "owner").image().isPresent());
        assertTrue(store.add("p1", "a-bis.png", PNG, "owner").refusal().contains("déjà jointe"));
        for (int i = 1; i < ReferenceImageStore.MAX_IMAGES; i++) {
            byte[] other = PNG.clone();
            other = java.util.Arrays.copyOf(other, other.length + i);
            assertTrue(store.add("p1", "n" + i + ".png", other, "owner").image().isPresent());
        }
        byte[] fifth = java.util.Arrays.copyOf(PNG, PNG.length + 99);
        assertTrue(store.add("p1", "fifth.png", fifth, "owner").refusal().contains("déjà " + ReferenceImageStore.MAX_IMAGES));
        assertEquals(ReferenceImageStore.MAX_IMAGES, store.list("p1").size());
    }

    @Test
    void anImageBelongsToItsProjectAndRemovingErasesIt() {
        ReferenceImageStore store = new ReferenceImageStore(tmp.resolve("cp.db").toString());
        String id = store.add("p1", "a.png", PNG, "owner").image().orElseThrow().id();
        assertArrayEquals(PNG, store.data("p1", id).orElseThrow());
        assertTrue(store.data("p2", id).isEmpty(), "jamais servie pour un autre projet");
        assertFalse(store.remove("p2", id), "ni retirée depuis un autre projet");
        assertTrue(store.remove("p1", id));
        assertTrue(store.data("p1", id).isEmpty(), "effacée, pas masquée");
    }

    @Test
    void namesLoseTheirPathAndControlCharacters() {
        assertEquals("facade.png", ReferenceImageStore.cleanName("C:\\Users\\x\\facade.png"));
        assertEquals("evil.png", ReferenceImageStore.cleanName("../../evil\u0000<>\".png").replace("..", ""));
        assertEquals("image", ReferenceImageStore.cleanName(""));
    }
}
