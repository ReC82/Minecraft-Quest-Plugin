package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Issue #325 — l'apparence par défaut est bien l'image demandée, et la réponse MineSkin est lue. */
class MineSkinTextureTest {

    @Test
    void theEmbeddedDefaultIsTheRequestedSkin() {
        assertEquals(Optional.of(MineSkinTexture.DEFAULT_SKIN_HASH), MineSkinTexture.DEFAULT.skinHash());
        assertEquals("1a6007069767adc7a4243828df1f690c474e6bdf7fe8cc1f8f416a338cbe309f",
                MineSkinTexture.DEFAULT_SKIN_HASH);
    }

    @Test
    void theDefaultLinkNeedsNoNetwork() {
        MineSkinResolver resolver = new MineSkinResolver(null);
        MineSkinResolver.Resolution r = resolver.resolve(NpcIdentityService.DEFAULT_SKIN_URL).join();
        assertTrue(r.ok());
        assertEquals(MineSkinTexture.DEFAULT, r.texture());
        assertEquals(Optional.of(MineSkinTexture.DEFAULT_SKIN_HASH),
                resolver.knownHash(NpcIdentityService.DEFAULT_SKIN_URL));
    }

    @Test
    void anInvalidLinkIsRefusedWithoutAnyCall() {
        MineSkinResolver.Resolution r = new MineSkinResolver(null).resolve("https://example.com/x").join();
        assertFalse(r.ok());
        assertEquals(MineSkinResolver.Failure.INVALID_LINK, r.failure());
    }

    @Test
    void theApiAnswerIsReadIntoATexture() {
        String body = "{\"success\":true,\"skin\":{\"texture\":{\"data\":{\"value\":\""
                + MineSkinTexture.DEFAULT.value() + "\",\"signature\":\""
                + MineSkinTexture.DEFAULT.signature() + "\"}}}}";
        MineSkinResolver.Resolution r = MineSkinResolver.parse(200, body);
        assertTrue(r.ok());
        assertEquals(MineSkinTexture.DEFAULT, r.texture());
    }

    @Test
    void eachFailureHasItsOwnReason() {
        assertEquals(MineSkinResolver.Failure.NOT_FOUND, MineSkinResolver.parse(404, "{}").failure());
        assertEquals(MineSkinResolver.Failure.UNREACHABLE, MineSkinResolver.parse(500, "").failure());
        assertEquals(MineSkinResolver.Failure.UNREACHABLE,
                MineSkinResolver.parse(200, "<html>page</html>").failure());
    }

    @Test
    void anUnreadableTextureHasNoHash() {
        assertTrue(MineSkinTexture.hashOf("pas du base64 !").isEmpty());
        assertTrue(MineSkinTexture.hashOf(null).isEmpty());
    }
}
