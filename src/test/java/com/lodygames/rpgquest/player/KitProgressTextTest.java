package com.lodygames.rpgquest.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.config.StarterKitTier;
import com.lodygames.rpgquest.config.StarterToolKitConfig;
import com.lodygames.rpgquest.quest.progress.DeliveryLine;
import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Issue #235 — ce que le Guide affiche de la progression du kit.
 *
 * <p>La classe testée est pure : chaque cas (palier intermédiaire, palier maximum, palier absent de
 * la configuration) est donc réellement exécuté, et non supposé. MockBukkit n'est présent que parce
 * que le nom traduit d'un matériau passe par l'API du serveur.</p>
 */
class KitProgressTextTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static StarterToolKitConfig twoTiers() {
        return new StarterToolKitConfig(true, List.of(
                new StarterKitTier(1, "Nouveau venu", List.of(Material.WOODEN_SWORD), null),
                new StarterKitTier(2, "Premiers pas dans le Wild",
                        List.of(Material.STONE_SWORD, Material.BREAD), "rpgquest:kit_tier2")));
    }

    // ---- Palier actuel --------------------------------------------------------------------------

    /** Le joueur doit lire son palier en jeu, sans PlugAdmin ni commande admin. */
    @Test
    void theCurrentTierShowsItsNumberAndItsName() {
        assertEquals("<aqua>Palier 1 — Nouveau venu</aqua>", KitProgressText.current(twoTiers(), 1));
        assertEquals("<aqua>Palier 2 — Premiers pas dans le Wild</aqua>",
                KitProgressText.current(twoTiers(), 2));
    }

    /**
     * Un palier débloqué qui n'existe plus en configuration affiche le palier <em>réellement
     * applicable</em>.
     *
     * <p>C'est le même que celui qui serait remis ({@code effectiveTier}) : l'écran et le contenu du
     * kit ne peuvent donc pas se contredire. Annoncer « Palier 7 » alors que le kit remis serait
     * celui du palier 2 serait pire qu'une absence d'information.</p>
     */
    @Test
    void anUnknownUnlockedTierFallsBackToTheApplicableOne() {
        assertEquals("<aqua>Palier 2 — Premiers pas dans le Wild</aqua>",
                KitProgressText.current(twoTiers(), 7));
    }

    @Test
    void aTierBelowOneIsTreatedAsTierOne() {
        assertEquals("<aqua>Palier 1 — Nouveau venu</aqua>", KitProgressText.current(twoTiers(), 0));
        assertEquals("<aqua>Palier 1 — Nouveau venu</aqua>", KitProgressText.current(twoTiers(), -3));
    }

    // ---- Palier suivant -------------------------------------------------------------------------

    @Test
    void theNextTierShowsItsNumberAndItsName() {
        assertEquals("<gold>Palier 2 — Premiers pas dans le Wild</gold>",
                KitProgressText.next(twoTiers(), 1));
    }

    /**
     * Au palier maximum, une phrase complète et non un tiret.
     *
     * <p>Le libellé qui précède ce marqueur vit dans le dialogue (« Prochaine amélioration : ») :
     * rendre {@code "—"} produirait une ligne qui ne veut rien dire. Une clause lisible permet de
     * garder un <strong>seul</strong> nœud de dialogue pour tous les paliers.</p>
     */
    @Test
    void atTheHighestTierTheAnswerIsASentenceNotADash() {
        String text = KitProgressText.next(twoTiers(), 2);

        assertTrue(text.contains("aucune"), text);
        assertTrue(text.contains("meilleur"), text);
        assertFalse(text.contains("Palier 3"), "aucun palier inexistant ne doit être annoncé");
    }

    @Test
    void aSingleTierConfigurationNeverPromisesAnUpgrade() {
        StarterToolKitConfig only = new StarterToolKitConfig(true, List.of(
                new StarterKitTier(1, "Nouveau venu", List.of(Material.WOODEN_SWORD), null)));

        assertTrue(KitProgressText.next(only, 1).contains("aucune"));
        assertTrue(KitProgressText.nextTier(only, 1).isEmpty());
    }

    @Test
    void theNextTierIsTheContiguousOneEvenFromAnAberrantValue() {
        assertEquals(2, KitProgressText.nextTier(twoTiers(), 1).orElseThrow().level());
        assertTrue(KitProgressText.nextTier(twoTiers(), 9).isEmpty(),
                "borné sur le palier applicable : pas de palier 10 inventé");
    }

    // ---- Matériaux attendus ---------------------------------------------------------------------

    /** La liste réutilise le rendu de {@code %delivery_status%} : même présentation partout. */
    @Test
    void requirementsListWhatIsDeliveredAndWhatIsMissing() {
        String text = KitProgressText.requirements(List.of(
                new DeliveryLine(Material.STICK, 1, 1),
                new DeliveryLine(Material.COBBLESTONE, 1, 2)));

        assertTrue(text.contains("✔"), "un matériau complet doit être marqué comme tel : " + text);
        assertTrue(text.contains("1/1"), text);
        assertTrue(text.contains("1/2"), text);
        assertTrue(text.contains("<lang:"), "les noms d'objets doivent rester traduits par le client");
    }

    @Test
    void noRequirementGivesAReadableAnswerRatherThanAnEmptyLine() {
        assertEquals("<gray>rien de particulier</gray>", KitProgressText.requirements(List.of()));
    }
}
