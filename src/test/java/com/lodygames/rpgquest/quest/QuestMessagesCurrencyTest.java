package com.lodygames.rpgquest.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.lodygames.rpgquest.economy.CurrencyName;
import java.util.Map;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Issue #335 — le nom de la monnaie dans les messages, et la mise à niveau des anciens défauts. */
class QuestMessagesCurrencyTest {

    @Test
    void currencyIsAvailableInEveryMessage() {
        CurrencyName.set("Écus");
        try {
            QuestMessages m = new QuestMessages(Map.of("k", "+3 <currency>"));
            assertEquals("+3 Écus", PlainTextComponentSerializer.plainText().serialize(m.format("k")));
        } finally {
            CurrencyName.set("LodyCoins");
        }
    }

    @Test
    void onlyAnUntouchedOldDefaultIsUpgraded() {
        String old = "<gold>Récompense de</gold> <white><quest></white> <gold>:</gold> "
                + "<yellow>+<amount> pièce(s)</yellow> <gray>— solde :</gray> <white><balance></white>";
        assertEquals(old.replace("pièce(s)", "<currency>"),
                QuestMessagesService.upgradeOf("quest.reward-money-credited", old));
        assertNull(QuestMessagesService.upgradeOf("quest.reward-money-credited", old + " !"), "texte personnalisé gardé");
        assertNull(QuestMessagesService.upgradeOf("quest.started-title", old));
    }
}
