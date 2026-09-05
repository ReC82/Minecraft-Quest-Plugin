package com.lodygames.rpgquest.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * Couvre le second bug constaté en test réel : une clé de message manquante s'affichait
 * littéralement en jeu (ex. {@code "[message manquant : quest.started-subtitle]"}), énorme quand
 * {@link com.lodygames.rpgquest.quest.progress.QuestProgressEngine} l'utilise comme Title/Subtitle
 * plein écran. {@link QuestMessages#format} ne doit plus jamais renvoyer le nom technique de la clé,
 * quel que soit le canal d'affichage (Title/Subtitle/ActionBar/chat partagent la même méthode).
 */
class QuestMessagesTest {

    @Test
    void aMissingKeyNeverLeaksItsTechnicalNameToThePlayer() {
        QuestMessages messages = new QuestMessages(Map.of());

        String rendered = PlainTextComponentSerializer.plainText()
                .serialize(messages.format("quest.started-subtitle"));

        assertFalse(rendered.contains("quest.started-subtitle"),
                () -> "le nom technique de la clé ne doit jamais atteindre l'écran du joueur : " + rendered);
        assertFalse(rendered.toLowerCase(Locale.ROOT).contains("manquant"),
                () -> "aucune trace du mot technique \"manquant\" ne doit être visible : " + rendered);
    }

    @Test
    void aMissingKeyFallbackStaysShortEnoughForATitleOrSubtitle() {
        QuestMessages messages = new QuestMessages(Map.of());

        String rendered = PlainTextComponentSerializer.plainText()
                .serialize(messages.format("quest.started-subtitle"));

        assertTrue(rendered.length() <= 5,
                () -> "le texte de remplacement doit rester très court (utilisable en Title/Subtitle) : \""
                        + rendered + "\"");
    }

    @Test
    void anExistingKeyIsDeserializedNormally() {
        QuestMessages messages = new QuestMessages(Map.of("quest.started-subtitle", "<white><quest></white>"));

        String rendered = PlainTextComponentSerializer.plainText().serialize(
                messages.format("quest.started-subtitle", Placeholder.parsed("quest", "Premiers pas")));

        assertEquals("Premiers pas", rendered);
    }
}
