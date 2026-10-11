package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Noms lisibles FR des matériaux / mobs Minecraft (issue #76). */
class MinecraftNamesTest {

    @Test
    void knownTokensAreTranslatedToFrench() {
        assertEquals("Araignée", MinecraftNames.humanize("SPIDER"));
        assertEquals("Éclat d'améthyste", MinecraftNames.humanize("AMETHYST_SHARD"));
        assertEquals("Épée en diamant", MinecraftNames.humanize("DIAMOND_SWORD"));
        assertEquals("Zombie", MinecraftNames.humanize("ZOMBIE"));
        assertEquals("Bûche de chêne", MinecraftNames.humanize("OAK_LOG"));
        assertTrue(MinecraftNames.isKnown("SPIDER"));
        assertTrue(MinecraftNames.isKnown("minecraft:diamond_sword"));
    }

    @Test
    void unknownTokensFallBackToPrettifyWithoutBreaking() {
        assertEquals("Some Weird Block", MinecraftNames.humanize("SOME_WEIRD_BLOCK"));
        assertEquals(MiniText.prettifyId("rpgquest:miner_pickaxe"), MinecraftNames.humanize("rpgquest:miner_pickaxe"));
        assertFalse(MinecraftNames.isKnown("SOME_WEIRD_BLOCK"));
        assertEquals("", MinecraftNames.humanize(null));
        assertEquals("", MinecraftNames.humanize("  "));
    }

    @Test
    void humanizeTokensReplacesInsideFreeText() {
        assertEquals("Tuer Araignée (x5)", MinecraftNames.humanizeTokens("Tuer SPIDER (x5)"));
        assertEquals("Collecter Éclat d'améthyste (x2)", MinecraftNames.humanizeTokens("Collecter AMETHYST_SHARD (x2)"));
        assertEquals("Fabriquer Épée en diamant (x1)", MinecraftNames.humanizeTokens("Fabriquer DIAMOND_SWORD (x1)"));
        // minuscule (npc id) et sigle court laissés tels quels
        assertEquals("Parler à guard (x1)", MinecraftNames.humanizeTokens("Parler à guard (x1)"));
        assertEquals("+100 XP", MinecraftNames.humanizeTokens("+100 XP"));
        assertEquals("", MinecraftNames.humanizeTokens(null));
    }

    @Test
    void theTableInitialisesAndCoversTheCitizensEntityTypes() {
        // Garde-fou appris à mes dépens pendant #197 : la table est un Map.ofEntries, qui lève sur
        // une clé en double. L'exception part de l'initialisation statique de la classe, donc la
        // page PNJ répondait sans AUCUN corps — pas une erreur 500 lisible, une réponse vide. Un
        // test qui touche simplement la table attrape désormais la faute au premier build.
        assertEquals("Joueur", MinecraftNames.humanize("PLAYER"));
        assertEquals("Villageois", MinecraftNames.humanize("VILLAGER"));
        assertTrue(MinecraftNames.isKnown("PLAYER"), "le type qui porte un skin doit être nommé");
        // Issue #197 : un type non traduit doit rester lisible, jamais faire échouer l'affichage.
        assertEquals("Allay", MinecraftNames.humanize("ALLAY"));
    }
}
