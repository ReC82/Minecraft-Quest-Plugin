package com.lodygames.rpgquest.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lodygames.rpgquest.spawn.JoinSpawnPolicy.Decision;
import org.junit.jupiter.api.Test;

/**
 * Règle métier de l'issue #87 (fonction pure) : quand rediriger la connexion vers le spawn du
 * village, quand laisser Paper restaurer la position du joueur.
 */
class JoinSpawnPolicyTest {

    private static final String PRIMARY = "world";
    private static final String HUB = "world_hub";
    private static final String WILD = "wild";
    private static final String CLAIMS = "claims";

    // ---- Cas 1 : joueur existant dans le Wild → pas de téléport Hub ------------------------

    @Test
    void existingPlayerInWildIsNeverRedirected() {
        // hasPlayedBefore() fiable (true)
        assertEquals(Decision.KEEP_VANILLA_LOCATION,
                JoinSpawnPolicy.decide(true, true, WILD, PRIMARY, HUB));
        // hasPlayedBefore() défaillant (false) : le monde d'arrivée Wild prime tout de même — c'est
        // exactement la régression #87.
        assertEquals(Decision.KEEP_VANILLA_LOCATION,
                JoinSpawnPolicy.decide(true, false, WILD, PRIMARY, HUB));
    }

    // ---- Cas 2 : nouveau joueur → onboarding Hub conservé ---------------------------------

    @Test
    void brandNewPlayerLandingInPrimaryOrHubIsRedirected() {
        assertEquals(Decision.REDIRECT_TO_CONFIGURED_SPAWN,
                JoinSpawnPolicy.decide(true, false, PRIMARY, PRIMARY, HUB));
        assertEquals(Decision.REDIRECT_TO_CONFIGURED_SPAWN,
                JoinSpawnPolicy.decide(true, false, HUB, PRIMARY, HUB));
        // casse insensible
        assertEquals(Decision.REDIRECT_TO_CONFIGURED_SPAWN,
                JoinSpawnPolicy.decide(true, false, "WORLD_HUB", PRIMARY, HUB));
    }

    // ---- Cas 3 : resetnew (règle actuelle) → Hub si joueur traité comme nouveau -----------

    @Test
    void resetNewPlayerTreatedAsNewInHubIsRedirected() {
        // Un resetnew ne modifie pas hasPlayedBefore() ; s'il repasse par le Hub il est redirigé
        // vers le village comme un nouveau joueur — comportement d'onboarding inchangé.
        assertEquals(Decision.REDIRECT_TO_CONFIGURED_SPAWN,
                JoinSpawnPolicy.decide(true, false, HUB, PRIMARY, HUB));
    }

    // ---- Cas 4 : monde Wild manquant → repli Hub -----------------------------------------

    @Test
    void missingPreviousWorldFallsBackToHubViaPrimaryWorld() {
        // Quand le monde précédent n'est plus chargé, Paper renvoie lui-même le joueur au monde
        // principal : la politique redirige alors vers le village (= Hub). Aucun code spécifique
        // « monde introuvable » n'est nécessaire.
        assertEquals(Decision.REDIRECT_TO_CONFIGURED_SPAWN,
                JoinSpawnPolicy.decide(true, false, PRIMARY, PRIMARY, HUB));
    }

    // ---- Cas 5 : position Wild invalide → laissée à Paper (safe-spawn natif) --------------

    @Test
    void invalidWildPositionStillKeepsPlayerInWild() {
        // La politique ne connaît que le monde d'arrivée : une position dangereuse dans le Wild
        // reste gérée par le placement sûr natif de Paper/Multiverse (adjust-spawn), jamais par un
        // renvoi au Hub.
        assertEquals(Decision.KEEP_VANILLA_LOCATION,
                JoinSpawnPolicy.decide(true, false, WILD, PRIMARY, HUB));
    }

    // ---- Cas 6 : joueur dans le Hub / autres mondes → comportement cohérent ---------------

    @Test
    void playerInClaimsWorldIsNeverRedirected() {
        assertEquals(Decision.KEEP_VANILLA_LOCATION,
                JoinSpawnPolicy.decide(true, true, CLAIMS, PRIMARY, HUB));
        assertEquals(Decision.KEEP_VANILLA_LOCATION,
                JoinSpawnPolicy.decide(true, false, CLAIMS, PRIMARY, HUB));
    }

    @Test
    void nothingHappensWithoutAConfiguredSpawn() {
        assertEquals(Decision.KEEP_VANILLA_LOCATION,
                JoinSpawnPolicy.decide(false, false, PRIMARY, PRIMARY, HUB));
        assertEquals(Decision.KEEP_VANILLA_LOCATION,
                JoinSpawnPolicy.decide(false, false, HUB, PRIMARY, HUB));
    }

    @Test
    void unknownIncomingWorldIsLeftUntouched() {
        assertEquals(Decision.KEEP_VANILLA_LOCATION,
                JoinSpawnPolicy.decide(true, false, null, PRIMARY, HUB));
        assertEquals(Decision.KEEP_VANILLA_LOCATION,
                JoinSpawnPolicy.decide(true, false, "", PRIMARY, HUB));
    }

    @Test
    void hubNotConfiguredStillRedirectsFromPrimaryWorld() {
        assertEquals(Decision.REDIRECT_TO_CONFIGURED_SPAWN,
                JoinSpawnPolicy.decide(true, false, PRIMARY, PRIMARY, null));
        assertEquals(Decision.KEEP_VANILLA_LOCATION,
                JoinSpawnPolicy.decide(true, false, WILD, PRIMARY, null));
    }
}
