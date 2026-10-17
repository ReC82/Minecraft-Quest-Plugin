package com.lodygames.rpgquest.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Issue #467 — un bouton par ligne, aucun libellé coupé en silence. */
class DialogButtonLayoutTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /** Les libellés tronqués du signalement Lily, plus les plus longs des dialogues embarqués. */
    private static final List<String> LILY_LABELS = List.of(
            "Tu as l'air affamée. Je peux t'aider ?",
            "Commencer la quête : Un repas pour Lily",
            "Je t'apporte ce que tu m'as demandé",
            "Commencer la quête : Des fleurs pour Lily",
            "Commencer la quête : Sur la piste de Julius",
            "Tu vas mieux ? Je peux faire autre chose ?",
            "Au revoir, Lily");

    private static List<Component> components(List<String> labels) {
        return labels.stream().map(label -> MiniMessage.miniMessage().deserialize(label)).toList();
    }

    @Test
    void oneButtonPerRow() {
        assertEquals(1, DialogButtonLayout.COLUMNS);
    }

    /**
     * Issue #475 (retour du 17/10) : le corps n'est plus aligné sur la largeur des boutons — il
     * est plus large qu'eux, dans la borne du client (1..1024), et tient avec la zone défilante
     * (+20 px) et la marge du widget de texte dans 427 px d'interface (854×480, échelle auto).
     */
    @Test
    void theBodyIsWiderThanTheButtonsButFitsTheSmallestWindow() {
        assertTrue(DialogButtonLayout.BODY_WIDTH > DialogButtonLayout.MAX_WIDTH);
        assertTrue(DialogButtonLayout.BODY_WIDTH <= 1024, "borne de plain_message.width");
        assertTrue(DialogButtonLayout.BODY_WIDTH + 20 + 8 <= 427, "doit tenir dans 854×480 en échelle auto");
    }

    @Test
    void lilyLabelsAreShownInFullOnSharedWidthButtons() {
        DialogButtonLayout layout = DialogButtonLayout.of(components(LILY_LABELS));

        assertTrue(layout.width() >= DialogButtonLayout.MIN_WIDTH && layout.width() <= DialogButtonLayout.MAX_WIDTH);
        for (int i = 0; i < LILY_LABELS.size(); i++) {
            DialogButtonLayout.Button button = layout.buttons().get(i);
            assertFalse(button.shortened(), LILY_LABELS.get(i));
            assertEquals(LILY_LABELS.get(i), PLAIN.serialize(button.label()));
            assertNull(button.tooltip(), "aucune infobulle superflue");
            assertTrue(DialogButtonLayout.textWidth(LILY_LABELS.get(i), false) + DialogButtonLayout.PADDING
                    <= layout.width(), LILY_LABELS.get(i));
        }
        assertTrue(layout.fullLabelsBody().isEmpty());
    }

    @Test
    void shortLabelsKeepTheMinimumWidth() {
        assertEquals(DialogButtonLayout.MIN_WIDTH, DialogButtonLayout.of(components(List.of("Oui", "Non"))).width());
    }

    @Test
    void aTooLongLabelIsShortenedButItsFullTextStaysOnScreen() {
        String longLabel = "Je voudrais savoir où se trouvent exactement tes parents et pourquoi ils sont partis sans toi";
        DialogButtonLayout layout = DialogButtonLayout.of(List.of(
                Component.text(longLabel, NamedTextColor.YELLOW), Component.text("Au revoir")));

        assertEquals(DialogButtonLayout.MAX_WIDTH, layout.width());
        DialogButtonLayout.Button button = layout.buttons().get(0);
        assertTrue(button.shortened());
        String shown = PLAIN.serialize(button.label());
        assertTrue(shown.endsWith("…") && longLabel.startsWith(shown.substring(0, shown.length() - 1)), shown);
        assertTrue(DialogButtonLayout.textWidth(shown, false) + DialogButtonLayout.PADDING <= layout.width(), shown);
        assertEquals(NamedTextColor.YELLOW, button.label().color(), "le style du libellé est conservé");
        assertEquals(longLabel, PLAIN.serialize(button.tooltip()), "infobulle = texte complet");
        assertEquals(1, layout.fullLabelsBody().size());
        assertTrue(PLAIN.serialize(layout.fullLabelsBody().get(0)).contains(longLabel), "texte complet dans le corps");
        assertFalse(layout.buttons().get(1).shortened());
    }

    @Test
    void boldLabelsAreMeasuredWider() {
        String label = "Commencer la quête : Des fleurs pour Lily";
        assertTrue(DialogButtonLayout.isBold(MiniMessage.miniMessage().deserialize("<bold>" + label)));
        assertTrue(DialogButtonLayout.textWidth(label, true) > DialogButtonLayout.textWidth(label, false));
    }
}
