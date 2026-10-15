package com.lodygames.rpgquest.webapi.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.webapi.privacy.AgePolicy.Outcome;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Issue #55 — la politique d'âge vient de la configuration, jamais d'une constante universelle. */
class AgePolicyTest {

    private static Properties properties(String... pairs) {
        Properties properties = new Properties();
        for (int i = 0; i < pairs.length; i += 2) {
            properties.setProperty(pairs[i], pairs[i + 1]);
        }
        return properties;
    }

    @Test
    void withoutAnyRegionAccountsStayClosedForEveryone() {
        AgePolicy policy = AgePolicy.fromProperties(new Properties());

        assertFalse(policy.accountsOpen());
        assertEquals(Outcome.REFUSED, policy.evaluate(Optional.of("BE"), 40));
        assertEquals(Outcome.REFUSED, policy.evaluate(Optional.empty(), 40));
    }

    @Test
    void eachRegionHasItsOwnConfiguredThreshold() {
        AgePolicy policy = AgePolicy.fromProperties(properties(
                "accounts.age-threshold.BE", "13", "accounts.age-threshold.fr", "15"));

        assertTrue(policy.accountsOpen());
        assertEquals(Outcome.ALLOWED, policy.evaluate(Optional.of("be"), 13));
        assertEquals(Outcome.PARENTAL_CONSENT_REQUIRED, policy.evaluate(Optional.of("BE"), 12));
        assertEquals(Outcome.PARENTAL_CONSENT_REQUIRED, policy.evaluate(Optional.of("FR"), 14));
        assertEquals(Outcome.ALLOWED, policy.evaluate(Optional.of("FR"), 15));
    }

    @Test
    void anUnknownOrUnservedRegionIsRefusedByDefault() {
        AgePolicy policy = AgePolicy.fromProperties(properties("accounts.age-threshold.BE", "13"));

        assertEquals(Outcome.REFUSED, policy.evaluate(Optional.empty(), 30));
        assertEquals(Outcome.REFUSED, policy.evaluate(Optional.of("US"), 30));
    }

    @Test
    void theOwnerCanChooseRefusalBelowTheThresholdAndConsentForUnknownRegions() {
        AgePolicy policy = AgePolicy.fromProperties(properties("accounts.age-threshold.BE", "13",
                "accounts.below-threshold", "refuse", "accounts.unknown-region", "parental-consent"));

        assertEquals(Outcome.REFUSED, policy.evaluate(Optional.of("BE"), 12));
        assertEquals(Outcome.PARENTAL_CONSENT_REQUIRED, policy.evaluate(Optional.empty(), 30));
    }

    @Test
    void ambiguousValuesAreRejectedAtLoad() {
        assertThrows(IllegalStateException.class,
                () -> AgePolicy.fromProperties(properties("accounts.age-threshold.EUROPE", "16")));
        assertThrows(IllegalStateException.class,
                () -> AgePolicy.fromProperties(properties("accounts.age-threshold.BE", "treize")));
        assertThrows(IllegalStateException.class,
                () -> AgePolicy.fromProperties(properties("accounts.age-threshold.BE", "0")));
        assertThrows(IllegalStateException.class,
                () -> AgePolicy.fromProperties(properties("accounts.unknown-region", "allow")));
        assertThrows(IllegalArgumentException.class,
                () -> new AgePolicy(Map.of("BE", 13), Outcome.ALLOWED, Outcome.REFUSED));
    }

    @Test
    void aNegativeAgeIsNeverAccepted() {
        AgePolicy policy = AgePolicy.fromProperties(properties("accounts.age-threshold.BE", "13"));

        assertEquals(Outcome.REFUSED, policy.evaluate(Optional.of("BE"), -1));
    }

    @Test
    void theOutcomeDecidesTheInitialConsentStatus() {
        assertEquals(Optional.of(ParentalConsentStatus.NOT_REQUIRED), AgePolicy.initialConsent(Outcome.ALLOWED));
        assertEquals(Optional.of(ParentalConsentStatus.PENDING),
                AgePolicy.initialConsent(Outcome.PARENTAL_CONSENT_REQUIRED));
        assertEquals(Optional.empty(), AgePolicy.initialConsent(Outcome.REFUSED));
    }
}
