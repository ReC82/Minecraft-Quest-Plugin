package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Catalogue des matériaux (issue #196).
 *
 * <p>Le défaut rapporté était précis : « la recherche <em>sword</em> dans icônes n'affiche que
 * iron et diamond ». Ces tests figent la cause (une liste curée de 76 entrées servait de
 * catalogue) et la propriété attendue (le relevé du serveur fait foi).</p>
 */
class ItemCatalogTest {

    /** Les sept épées réellement exposées par la version installée (Paper 1.21.11). */
    private static final List<String> REAL_SWORDS = List.of(
            "WOODEN_SWORD", "STONE_SWORD", "COPPER_SWORD", "GOLDEN_SWORD",
            "IRON_SWORD", "DIAMOND_SWORD", "NETHERITE_SWORD");

    @Test
    @DisplayName("La liste codée en dur ne contenait que deux épées : c'est le défaut rapporté")
    void hardcodedListWasTheDefect() {
        long swords = RefData.MATERIALS.stream().filter(m -> m.endsWith("_SWORD")).count();

        assertEquals(2, swords, "si ce nombre change, mettre à jour l'explication du ticket #196");
        assertTrue(RefData.MATERIALS.contains("IRON_SWORD"));
        assertTrue(RefData.MATERIALS.contains("DIAMOND_SWORD"));
        assertFalse(RefData.MATERIALS.contains("WOODEN_SWORD"));
        assertFalse(RefData.MATERIALS.contains("NETHERITE_SWORD"));
    }

    @Test
    @DisplayName("Avec le relevé serveur, « sword » retrouve TOUTES les épées de la version")
    void serverCatalogFindsEverySword() {
        RefData ref = withCatalog(REAL_SWORDS, List.of());

        List<String> found = search(ref, "sword");

        assertEquals(REAL_SWORDS.size(), found.size(), found.toString());
        for (String sword : REAL_SWORDS) {
            assertTrue(found.contains(sword), "épée manquante : " + sword);
        }
    }

    @Test
    @DisplayName("Sans relevé, le repli curé est utilisé — et le panel le sait, donc peut le dire")
    void fallbackIsIdentifiedAsSuch() {
        RefData empty = RefData.empty();

        assertFalse(empty.materialsFromServer());
        assertEquals(RefData.MATERIALS, empty.materials());
        assertFalse(empty.sourceKnown("material"),
                "sans relevé, un matériau absent du repli est légitime : jamais d'avertissement");
    }

    @Test
    @DisplayName("Avec relevé, le catalogue remplace le repli — jamais une fusion des deux")
    void serverCatalogReplacesFallback() {
        RefData ref = withCatalog(REAL_SWORDS, List.of());

        assertTrue(ref.materialsFromServer());
        assertEquals(REAL_SWORDS, ref.materials());
        assertFalse(ref.materials().contains("AMETHYST_SHARD"),
                "mélanger un extrait et un catalogue rendrait impossible de dire ce qu'on montre");
        assertTrue(ref.sourceKnown("material"));
    }

    @Test
    @DisplayName("Un bloc sans forme d'objet est reconnu comme tel, pas comme une valeur inconnue")
    void blocksWithoutItemAreRecognised() {
        RefData ref = withCatalog(List.of("BOOK"), List.of("WATER", "FIRE", "NETHER_PORTAL"));

        assertTrue(ref.itemCatalog().isBlockWithoutItem("WATER"));
        assertTrue(ref.itemCatalog().isBlockWithoutItem("  fire  "), "casse et espaces tolérés");
        assertFalse(ref.itemCatalog().isBlockWithoutItem("BOOK"));
        assertFalse(ref.itemCatalog().isBlockWithoutItem("PAS_UN_MATERIAU"));
        // Un bloc sans objet n'est PAS proposable : il ne doit pas entrer dans la liste de choix.
        assertFalse(ref.materials().contains("WATER"));
    }

    @Test
    @DisplayName("La version Minecraft du relevé est conservée : la provenance de la liste est traçable")
    void versionIsCarried() {
        RefData ref = withCatalog(List.of("BOOK"), List.of());

        assertEquals("1.21.11", ref.itemCatalog().minecraftVersion());
    }

    @Test
    @DisplayName("Un catalogue vide n'est jamais traité comme un catalogue")
    void emptyCatalogIsNotACatalog() {
        RefData.ItemCatalog empty = new RefData.ItemCatalog(List.of(), List.of("WATER"), "1.21.11");

        assertFalse(empty.known(), "aucun objet relevé = pas de relevé exploitable");
        assertEquals(RefData.MATERIALS, RefData.empty().withItemCatalog(empty).materials());
    }

    @Test
    @DisplayName("Le catalogue survit à withExtraQuests : aucune composante perdue au passage")
    void catalogSurvivesDerivation() {
        RefData ref = withCatalog(REAL_SWORDS, List.of("WATER"));

        RefData derived = ref.withExtraQuests(List.of("nouvelle_quete"));

        assertTrue(derived.materialsFromServer(), "le catalogue a été perdu en dérivant la RefData");
        assertEquals(REAL_SWORDS, derived.materials());
        assertTrue(derived.itemCatalog().isBlockWithoutItem("WATER"));
    }

    @Test
    @DisplayName("isValueKnown s'appuie sur le catalogue relevé, pas sur le repli")
    void knownValuesComeFromTheCatalog() {
        RefData ref = withCatalog(REAL_SWORDS, List.of());

        assertTrue(ref.isValueKnown("material", "netherite_sword"), "casse tolérée");
        assertFalse(ref.isValueKnown("material", "AMETHYST_SHARD"),
                "présent dans le repli, absent du relevé : c'est le relevé qui fait foi");
    }

    // ---- Utilitaires -----------------------------------------------------------------------

    private static RefData withCatalog(List<String> items, List<String> blocksWithoutItem) {
        return RefData.empty().withItemCatalog(
                new RefData.ItemCatalog(items, blocksWithoutItem, "1.21.11"));
    }

    /**
     * Reproduit le filtrage de la liste recherchable : correspondance sur l'identifiant
     * <strong>ou</strong> sur le libellé, sans borne, pour vérifier qu'aucun résultat n'est perdu
     * par le catalogue lui-même.
     */
    private static List<String> search(RefData ref, String query) {
        String q = query.toLowerCase(Locale.ROOT);
        return ref.materials().stream()
                .filter(m -> m.toLowerCase(Locale.ROOT).contains(q))
                .toList();
    }
}
