package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Libellés français des matériaux (issue #196).
 *
 * <p>Ce qui est vérifié ici n'est pas « la traduction est jolie » mais deux propriétés
 * utilisables : les familles composées sont traduites correctement (c'est ce qui rend la recherche
 * « épée » possible), et ce qui n'est pas traduisible tombe sur un repli <strong>visiblement
 * anglais</strong> plutôt que sur une invention.</p>
 */
class MaterialNamesTest {

    @Test
    @DisplayName("Les sept épées de la version réelle ont un libellé français correct")
    void swordsAreTranslated() {
        assertEquals("Épée en bois", MaterialNames.french("WOODEN_SWORD"));
        assertEquals("Épée en pierre", MaterialNames.french("STONE_SWORD"));
        assertEquals("Épée en cuivre", MaterialNames.french("COPPER_SWORD"));
        assertEquals("Épée en or", MaterialNames.french("GOLDEN_SWORD"));
        assertEquals("Épée en fer", MaterialNames.french("IRON_SWORD"));
        assertEquals("Épée en diamant", MaterialNames.french("DIAMOND_SWORD"));
        assertEquals("Épée en netherite", MaterialNames.french("NETHERITE_SWORD"));
    }

    @Test
    @DisplayName("« PICKAXE » n'est jamais confondu avec « AXE » : l'ordre des règles compte")
    void pickaxeIsNotAnAxe() {
        assertEquals("Pioche en diamant", MaterialNames.french("DIAMOND_PICKAXE"));
        assertEquals("Hache en diamant", MaterialNames.french("DIAMOND_AXE"));
    }

    @Test
    @DisplayName("Les armures, outils et pièces d'équipement suivent les mêmes règles")
    void equipmentFamilies() {
        assertEquals("Casque en fer", MaterialNames.french("IRON_HELMET"));
        assertEquals("Plastron en mailles", MaterialNames.french("CHAINMAIL_CHESTPLATE"));
        assertEquals("Jambières en cuir", MaterialNames.french("LEATHER_LEGGINGS"));
        assertEquals("Bottes en netherite", MaterialNames.french("NETHERITE_BOOTS"));
        assertEquals("Armure de cheval en or", MaterialNames.french("GOLDEN_HORSE_ARMOR"));
        assertEquals("Pelle en pierre", MaterialNames.french("STONE_SHOVEL"));
        assertEquals("Houe en bois", MaterialNames.french("WOODEN_HOE"));
    }

    @Test
    @DisplayName("Bois, minerais et construction : familles entières couvertes par composition")
    void blockFamilies() {
        assertEquals("Planches de chêne", MaterialNames.french("OAK_PLANKS"));
        assertEquals("Bûche de bouleau", MaterialNames.french("BIRCH_LOG"));
        assertEquals("Porte en sapin", MaterialNames.french("SPRUCE_DOOR"));
        assertEquals("Minerai de cuivre", MaterialNames.french("COPPER_ORE"));
        assertEquals("Lingot de fer", MaterialNames.french("IRON_INGOT"));
        assertEquals("Bloc de diamant", MaterialNames.french("DIAMOND_BLOCK"));
    }

    @Test
    @DisplayName("L'élision du français est appliquée : « Lingot d'or », jamais « Lingot de or »")
    void appliesFrenchElision() {
        assertEquals("Lingot d'or", MaterialNames.french("GOLD_INGOT"));
        assertEquals("Pépite d'or", MaterialNames.french("GOLD_NUGGET"));
        assertEquals("Minerai d'émeraude", MaterialNames.french("EMERALD_ORE"));
        assertEquals("Bloc d'améthyste", MaterialNames.french("AMETHYST_BLOCK"));
        assertEquals("Planches d'acacia", MaterialNames.french("ACACIA_PLANKS"));
        // Après « en », pas d'élision : « Pelle en or » est correct.
        assertEquals("Pelle en or", MaterialNames.french("GOLDEN_SHOVEL"));
    }

    @Test
    @DisplayName("La table nominative couvre les objets dont l'identifiant ne se décompose pas")
    void exactTableCoversIrregulars() {
        assertEquals("Bâton", MaterialNames.french("STICK"));
        assertEquals("Perle de l'Ender", MaterialNames.french("ENDER_PEARL"));
        assertEquals("Chair putréfiée", MaterialNames.french("ROTTEN_FLESH"));
        assertEquals("Pomme dorée enchantée", MaterialNames.french("ENCHANTED_GOLDEN_APPLE"));
    }

    @Test
    @DisplayName("Une matière inconnue donne un repli ANGLAIS visible, jamais une traduction inventée")
    void unknownMatterFallsBackToEnglish() {
        // « Épée en Prismarine Brick » serait du charabia : mieux vaut de l'anglais lisible.
        String label = MaterialNames.french("PRISMARINE_BRICK_STAIRS");

        assertFalse(label.startsWith("Escalier en"), label);
        assertEquals("Prismarine Brick Stairs", label);
    }

    @Test
    @DisplayName("Un identifiant totalement inconnu reste lisible, jamais vide ni nul")
    void unknownIdStaysReadable() {
        assertEquals("Sculk Catalyst", MaterialNames.french("SCULK_CATALYST"));
        assertEquals("Chiseled Bookshelf", MaterialNames.french("CHISELED_BOOKSHELF"));
        assertEquals("", MaterialNames.french(null));
        assertEquals("", MaterialNames.french("   "));
    }

    @Test
    @DisplayName("Un éclat d'améthyste est traduit par composition, pas par la table nominative")
    void shardFamilyIsComposed() {
        assertEquals("Éclat d'améthyste", MaterialNames.french("AMETHYST_SHARD"));
    }

    @Test
    @DisplayName("Les objets réservés au créatif sont signalés, mais jamais retirés du catalogue")
    void creativeOnlyIsFlagged() {
        assertTrue(MaterialNames.creativeOnly("COMMAND_BLOCK"));
        assertTrue(MaterialNames.creativeOnly("BEDROCK"));
        assertTrue(MaterialNames.creativeOnly("debug_stick"), "la casse ne doit pas compter");
        assertFalse(MaterialNames.creativeOnly("DIAMOND_SWORD"));
        assertFalse(MaterialNames.creativeOnly(null));
    }

    @Test
    @DisplayName("La casse et les espaces du champ de saisie sont tolérés")
    void inputIsNormalised() {
        assertEquals("Épée en diamant", MaterialNames.french("  diamond_sword  "));
    }
}
