package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Issue #475 — {@code %npc_name:<id>%} : nom affiché actuel d'un PNJ cité par son id stable. */
class NpcNameTokensTest {

    private final Map<String, String> names = new HashMap<>(Map.of("guide", "Guide"));
    private final Function<String, Optional<String>> resolver = id -> Optional.ofNullable(names.get(id));

    @Test
    void aQuestDescriptionFollowsTheRenameWithoutBeingRepublished() {
        String description = "<gray>Rapporte à %npc_name:guide% de quoi améliorer ton équipement.</gray>";

        assertEquals("<gray>Rapporte à Guide de quoi améliorer ton équipement.</gray>",
                NpcNameTokens.resolve(description, resolver));
        names.put("guide", "Elias");
        assertEquals("<gray>Rapporte à Elias de quoi améliorer ton équipement.</gray>",
                NpcNameTokens.resolve(description, resolver));
    }

    @Test
    void anUnknownNpcLeavesTheTokenVisible() {
        assertEquals("Va voir %npc_name:inconnu%.", NpcNameTokens.resolve("Va voir %npc_name:inconnu%.", resolver));
        assertNull(NpcNameTokens.escapedName("inconnu", resolver));
    }

    @Test
    void aTextWithoutTokenIsReturnedAsIs() {
        String text = "Le guide du village";
        assertSame(text, NpcNameTokens.resolve(text, resolver));
    }
}
