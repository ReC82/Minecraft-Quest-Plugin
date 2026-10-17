package com.lodygames.rpgquest.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.config.HudMode;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Issue #184 — textes du HUD pièces / niveau RPG. */
class HudTextTest {

    @Test
    void coinsLineShowsTheRealBalanceAndCurrency() {
        assertEquals("Bourse : 125 LodyCoins", plain(HudText.coinsLine(125L)));
    }

    @Test
    void coinsLineNeverInventsAZeroBeforeTheWalletIsRead() {
        assertEquals("Bourse : … LodyCoins", plain(HudText.coinsLine(null)));
    }

    @Test
    void levelLineNamesTheRpgLevel() {
        assertEquals("Niveau RPG : 4", plain(HudText.levelLine(4)));
    }

    @Test
    void walletChangeShowsSignedAmountAndBalance() {
        assertEquals("+25 LodyCoins · solde 150", plain(HudText.walletChange(25, 150)));
        assertEquals("-10 LodyCoins · solde 140", plain(HudText.walletChange(-10, 140)));
        assertTrue(plain(HudText.walletChange(0, 300)).startsWith("Solde ajusté : 300"));
    }

    @Test
    void storedModeParsingIgnoresUnknownValues() {
        assertEquals(Optional.of(HudMode.SIDEBAR), PlayerHudService.parse("sidebar"));
        assertEquals(Optional.of(HudMode.OFF), PlayerHudService.parse("OFF"));
        assertEquals(Optional.empty(), PlayerHudService.parse("bossbar"));
        assertEquals(Optional.empty(), PlayerHudService.parse(null));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
