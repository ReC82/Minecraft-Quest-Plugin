package com.lodygames.rpgquest.waypoint.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Issues #133/#135 : couvre {@link WaypointNameCatalog} — dédoublonnage à l'import, attribution
 * sans doublon (y compris sous la même seed), nom de secours une fois la réserve épuisée. Sans
 * Bukkit, sans base — testable en JUnit pur, même patron que {@code WaypointGenerationPlannerTest}.
 */
class WaypointNameCatalogTest {

    private static WaypointNameCatalog fromLines(String... lines) {
        String text = String.join("\n", lines);
        return WaypointNameCatalog.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void duplicatesAtImportAreRejectedCaseAndAccentInsensitive() {
        WaypointNameCatalog catalog = fromLines("Rochebrune", "rochebrune", "ROCHEBRUNE", "Rochebrune ",
                "Clairval", "# un commentaire", "", "clairvâl");
        // "Rochebrune" a 4 doublons (casse/espaces), "clairvâl" est un doublon de "Clairval" une
        // fois l'accent retiré (NFD) : seules 2 entrées distinctes survivent, la première occurrence
        // de chacune ("Rochebrune", "Clairval") étant conservée.
        assertEquals(2, catalog.size(), "doublons internes au fichier dédoublonnés, commentaires/lignes vides ignorés");
    }

    @Test
    void loadBundledCatalogHasNoDuplicateAndIsReasonablyLarge() {
        WaypointNameCatalog catalog = WaypointNameCatalog.loadBundled();
        assertTrue(catalog.size() >= 100, "réserve bundlée attendue assez large : " + catalog.size());
        Set<String> normalized = new HashSet<>();
        for (String name : catalog.reserve()) {
            assertTrue(normalized.add(WaypointNameCatalog.normalize(name)),
                    "aucun doublon ne doit survivre au chargement : " + name);
        }
    }

    /**
     * Retour joueur 2026-10-04 : les anciens noms étaient deux mots concaténés sans séparateur
     * (ex. {@code Lacgivre}). La réserve bundlée doit désormais toujours contenir au moins un espace
     * (ex. {@code Lac de Givre}) -- jamais une régression vers la concaténation brute.
     */
    @Test
    void everyBundledNameIsReadableWithAtLeastOneWordSeparator() {
        WaypointNameCatalog catalog = WaypointNameCatalog.loadBundled();
        for (String name : catalog.reserve()) {
            assertTrue(name.contains(" "), "nom non lisible (aucun séparateur de mot) : " + name);
        }
    }

    @Test
    void reserveNameNeverReturnsAnAlreadyUsedName() {
        WaypointNameCatalog catalog = fromLines("Rochebrune", "Clairval", "Ventdoré");
        Set<String> used = new HashSet<>();
        String first = catalog.reserveName(1L, used);
        String second = catalog.reserveName(1L, used); // même seed : doit quand même éviter le doublon
        String third = catalog.reserveName(1L, used);
        assertFalse(first.equalsIgnoreCase(second));
        assertFalse(first.equalsIgnoreCase(third));
        assertFalse(second.equalsIgnoreCase(third));
        assertEquals(3, used.size());
    }

    @Test
    void sameSeedAndEmptyUsedSetAlwaysPicksTheSameFirstName() {
        WaypointNameCatalog catalog = fromLines("Rochebrune", "Clairval", "Ventdoré", "Brumepierre");
        String a = catalog.reserveName(42L, new HashSet<>());
        String b = catalog.reserveName(42L, new HashSet<>());
        assertEquals(a, b, "même seed, même état used (vide) -> même choix, déterministe");
    }

    @Test
    void exhaustedReserveFallsBackToAUniqueGeneratedName() {
        WaypointNameCatalog catalog = fromLines("Rochebrune", "Clairval");
        Set<String> used = new HashSet<>();
        String first = catalog.reserveName(7L, used);
        String second = catalog.reserveName(7L, used);
        String fallback1 = catalog.reserveName(7L, used);
        String fallback2 = catalog.reserveName(7L, used);
        assertTrue(Set.of(first, second).stream().noneMatch(n -> n.equals(fallback1)));
        assertFalse(fallback1.equalsIgnoreCase(fallback2), "deux noms de secours consécutifs restent distincts");
        assertTrue(fallback1.startsWith("Avant-poste"));
    }

    @Test
    void normalizeIsCaseAndAccentInsensitiveAndTrims() {
        assertEquals(WaypointNameCatalog.normalize(" Rochebrune "), WaypointNameCatalog.normalize("ROCHEBRUNE"));
        assertEquals(WaypointNameCatalog.normalize("Brumepierre"), WaypointNameCatalog.normalize("brumepierre"));
    }
}
