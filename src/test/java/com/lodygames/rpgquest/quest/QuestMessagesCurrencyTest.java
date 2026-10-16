package com.lodygames.rpgquest.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** Issue #467 — l'ancien en-tête « suivi dans /quests » est remplacé, un texte personnalisé non. */
    @Test
    void theOldQuestsCommandHintIsReplacedOnlyWhenUntouched() {
        String old = "<green><bold>Quête commencée :</bold></green> <white><quest></white> "
                + "<gray>— à faire (suivi dans</gray> <white>/quests</white><gray>) :</gray>";
        assertTrue(QuestMessagesService.isReplacedDefault("quest.started-objectives-header", old));
        assertFalse(QuestMessagesService.isReplacedDefault("quest.started-objectives-header", old + " "));
        assertFalse(QuestMessagesService.isReplacedDefault("quest.started-title", old));
    }

    /** Issue #467 — aucun message joueur embarqué ne renvoie vers une commande de quête. */
    @Test
    void noBundledMessageSendsThePlayerToAQuestCommand() throws Exception {
        try (var in = getClass().getResourceAsStream("/messages.yml")) {
            var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            for (String key : yaml.getKeys(true)) {
                if (yaml.isString(key)) {
                    String value = yaml.getString(key);
                    assertFalse(value.contains("/quest"), () -> key + " : " + value);
                }
            }
        }
    }
}
