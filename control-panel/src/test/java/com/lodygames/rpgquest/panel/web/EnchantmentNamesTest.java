package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Issue #474 — noms français des enchantements, repli lisible sur l'identifiant. */
class EnchantmentNamesTest {

    @Test
    void vanillaEnchantmentsHaveTheirGameNameAndRomanLevel() {
        assertEquals("Tranchant", EnchantmentNames.french("sharpness"));
        assertEquals("Solidité", EnchantmentNames.french("minecraft:unbreaking"));
        assertEquals("Tranchant III", EnchantmentNames.label("sharpness", 3));
        assertEquals("Raccommodage I", EnchantmentNames.label("mending", 1));
        assertEquals("X", EnchantmentNames.level(10));
        assertEquals("12", EnchantmentNames.level(12));
    }

    @Test
    void anUnknownEnchantmentFallsBackToItsIdNeverToAnInventedName() {
        assertEquals("Lunge", EnchantmentNames.french("lunge"));
        assertEquals("Super Sharp", EnchantmentNames.french("super_sharp"));
    }
}
