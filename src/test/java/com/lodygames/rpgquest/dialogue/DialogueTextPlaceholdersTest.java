package com.lodygames.rpgquest.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.lodygames.rpgquest.dialogue.model.DialogueChoice;
import com.lodygames.rpgquest.dialogue.model.DialogueNode;
import com.lodygames.rpgquest.quest.model.LocalizedText;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #24 : substitution des valeurs dynamiques dans le texte d'un nœud de dialogue. Aucun joueur
 * réel n'est nécessaire (les valeurs testées ignorent l'argument), donc aucun MockBukkit ici.
 */
class DialogueTextPlaceholdersTest {

    private static final DialogueTextPlaceholders PLACEHOLDERS = new DialogueTextPlaceholders(Map.of(
            "wild_conditions", context -> "Il fait nuit dans le Wild. Il pleut.",
            "percent", context -> "100 %",
            // Issue #123 : une valeur qui dépend du PNJ porteur du dialogue courant.
            "delivery_status", context -> "PNJ=" + context.npcId()));

    private static DialogueNode node(String text) {
        return new DialogueNode("n", "Garde", LocalizedText.of(text),
                List.of(new DialogueChoice(LocalizedText.of("Merci"), List.of(), List.of(), "greeting")));
    }

    @Test
    void aRegisteredPlaceholderIsReplacedByItsCurrentValue() {
        assertEquals("<white>Il fait nuit dans le Wild. Il pleut.</white>",
                PLACEHOLDERS.apply(null, null, node("<white>%wild_conditions%</white>")).text().base());
    }

    @Test
    void anUnknownPlaceholderIsLeftVisibleRatherThanErased() {
        assertEquals("Et %inconnu% ?", PLACEHOLDERS.apply(null, null, node("Et %inconnu% ?")).text().base());
    }

    @Test
    void aNodeWithoutAnyPlaceholderIsReturnedUntouched() {
        DialogueNode original = node("<white>Bonjour voyageur.</white>");

        assertSame(original, PLACEHOLDERS.apply(null, null, original), "aucune copie inutile");
    }

    @Test
    void noPlaceholderRegisteredMeansNoSubstitutionAtAll() {
        DialogueNode original = node("<white>%wild_conditions%</white>");

        assertSame(original, DialogueTextPlaceholders.none().apply(null, null, original));
    }

    /** Une valeur contenant elle-même un « % » ne doit jamais être re-substituée ni casser le rendu. */
    @Test
    void aValueContainingPercentSignsIsInsertedLiterally() {
        assertEquals("Charge : 100 % — %wild_conditions_bis%",
                PLACEHOLDERS.apply(null, null, node("Charge : %percent% — %wild_conditions_bis%")).text().base());
    }

    /** Issue #123 : une valeur peut dépendre du PNJ porteur du dialogue, déduit de sa clé. */
    @Test
    void aValueCanDependOnTheNpcCarryingTheCurrentDialogue() {
        assertEquals("PNJ=guard", PLACEHOLDERS
                .apply(null, new org.bukkit.NamespacedKey("rpgquest", "guard"), node("%delivery_status%"))
                .text().base());
        assertEquals("PNJ=null", PLACEHOLDERS.apply(null, null, node("%delivery_status%")).text().base(),
                "sans dialogue connu, aucun PNJ n'est deviné");
    }

    @Test
    void everyLocaleOfTheTextIsSubstituted() {
        DialogueNode multilingual = new DialogueNode("n", "Garde",
                new LocalizedText(Map.of(
                        LocalizedText.DEFAULT_KEY, "FR : %wild_conditions%",
                        "en", "EN : %wild_conditions%")),
                List.of(new DialogueChoice(LocalizedText.of("Merci"), List.of(), List.of(), "greeting")));

        DialogueNode applied = PLACEHOLDERS.apply(null, null, multilingual);

        assertEquals("FR : Il fait nuit dans le Wild. Il pleut.", applied.text().base());
        assertEquals("EN : Il fait nuit dans le Wild. Il pleut.", applied.text().forLocale("en"));
    }
}
