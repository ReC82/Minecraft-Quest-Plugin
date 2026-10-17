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

    /** Issue #180 : une valeur à argument reçoit l'identifiant écrit après « : ». */
    @Test
    void aPlaceholderWithAnArgumentReceivesIt() {
        DialogueTextPlaceholders withCities = new DialogueTextPlaceholders(Map.of(), Map.of(
                "city_name", (context, id) -> id.equals("ville_nerana") ? "Nerana" : null));

        assertEquals("Julius vit à Nerana.",
                withCities.apply(null, null, node("Julius vit à %city_name:ville_nerana%.")).text().base());
    }

    /** Issue #180 : ville inconnue = jeton visible (une erreur de configuration se voit en jeu). */
    @Test
    void anUnresolvedArgumentLeavesTheTokenVisible() {
        DialogueTextPlaceholders withCities = new DialogueTextPlaceholders(Map.of(), Map.of(
                "city_name", (context, id) -> null));

        assertEquals("Ville : %city_name:ville_absente% / %inconnu:x%",
                withCities.apply(null, null, node("Ville : %city_name:ville_absente% / %inconnu:x%")).text().base());
    }

    /** Une clé simple n'est jamais résolue par une valeur à argument, et inversement. */
    @Test
    void plainAndArgumentValuesDoNotMix() {
        DialogueTextPlaceholders mixed = new DialogueTextPlaceholders(
                Map.of("city_name", context -> "SIMPLE"),
                Map.of("city_name", (context, id) -> "ARG:" + id));

        assertEquals("SIMPLE ARG:ville_a",
                mixed.apply(null, null, node("%city_name% %city_name:ville_a%")).text().base());
    }

    // ---- Issue #475 : nom du PNJ résolu depuis son id au rendu, locuteur compris ----------------

    /** Câblage de RPGQuestBootstrap, sur un registre de noms modifiable (= renommage + rechargement). */
    private static DialogueTextPlaceholders npcNames(Map<String, String> names) {
        java.util.function.Function<String, java.util.Optional<String>> resolver =
                id -> java.util.Optional.ofNullable(names.get(id));
        return new DialogueTextPlaceholders(
                Map.of("npc_name", context -> com.lodygames.rpgquest.npc.NpcNameTokens.escapedName(context.npcId(), resolver)),
                Map.of("npc_name", (context, id) -> com.lodygames.rpgquest.npc.NpcNameTokens.escapedName(id, resolver)));
    }

    @Test
    void theSpeakerAndTheTextFollowARenamedNpc() {
        Map<String, String> names = new java.util.HashMap<>(Map.of("guide", "Guide"));
        DialogueTextPlaceholders placeholders = npcNames(names);
        DialogueNode node = new DialogueNode("greeting", "%npc_name%", LocalizedText.of("Je suis %npc_name%, le guide du village."),
                List.of(new DialogueChoice(LocalizedText.of("Merci"), List.of(), List.of(), "greeting")));
        org.bukkit.NamespacedKey guide = new org.bukkit.NamespacedKey("rpgquest", "guide");

        names.put("guide", "Elias"); // renommé dans le panel, puis rechargé
        DialogueNode shown = placeholders.apply(null, guide, node);

        assertEquals("Elias", shown.speaker());
        assertEquals("Je suis Elias, le guide du village.", shown.text().base(),
                "le mot générique « guide » n'est jamais remplacé, seul le jeton l'est");
    }

    @Test
    void anotherNpcIsNamedByItsStableIdAndAnUnknownIdStaysVisible() {
        DialogueTextPlaceholders placeholders = npcNames(Map.of("guide", "Elias"));

        assertEquals("Ah, c'est Elias qui t'envoie ? %npc_name:fantome%",
                placeholders.apply(null, null, node("Ah, c'est %npc_name:guide% qui t'envoie ? %npc_name:fantome%"))
                        .text().base());
    }

    @Test
    void aNameIsInsertedAsTextNeverAsMiniMessageTags() {
        DialogueTextPlaceholders placeholders = npcNames(Map.of("guide", "<red>Elias"));

        assertEquals("\\<red>Elias", placeholders.apply(null, null, node("%npc_name:guide%")).text().base());
    }
}
