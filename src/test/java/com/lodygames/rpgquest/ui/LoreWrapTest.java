package com.lodygames.rpgquest.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Issue #475 — la description du journal est repliée au lieu de déborder de l'écran. */
class LoreWrapTest {

    private static String plain(String miniMessage) {
        return PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(miniMessage));
    }

    @Test
    void aLongDescriptionIsSplitIntoLinesOfBoundedVisibleWidthWithoutLosingAWord() {
        String description = "<gray>Rapporte à Elias de quoi améliorer ton équipement de secours : un bâton et "
                + "trois pierres pour chaque outil en pierre (pioche et hache), cinq cuirs pour un casque, et "
                + "trois blés pour un pain de plus.</gray>";

        List<String> lines = LoreWrap.wrap(description, 40);

        assertTrue(lines.size() >= 4, () -> "obtenu : " + lines);
        lines.forEach(line -> assertTrue(plain(line).length() <= 40, () -> "trop large : " + plain(line)));
        assertEquals(plain(description), String.join(" ", lines.stream().map(LoreWrapTest::plain).toList()));
        lines.forEach(line -> assertTrue(line.startsWith("<gray>"), () -> "couleur rouverte : " + line));
    }

    @Test
    void anExplicitNewlineBreaksTheLineAndAShortTextStaysOnOne() {
        assertEquals(List.of("<gray>Un", "<gray>deux</gray>"), LoreWrap.wrap("<gray>Un<newline>deux</gray>", 40));
        assertEquals(List.of("Court."), LoreWrap.wrap("Court.", 40));
    }

    @Test
    void aWordLongerThanTheWidthIsKeptWhole() {
        assertEquals(List.of("abcdefghijkl", "mn"), LoreWrap.wrap("abcdefghijkl mn", 5));
    }
}
