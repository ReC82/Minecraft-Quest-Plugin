package com.lodygames.rpgquest.webapi.privacy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Issue #55 — un compte soumis à consentement reste limité tant que celui-ci manque. */
class ParentalConsentStatusTest {

    @Test
    void onlyAnAbsentNeedOrAnApprovalUnlocksPersonalFeatures() {
        assertTrue(ParentalConsentStatus.NOT_REQUIRED.allowsPersonalFeatures());
        assertTrue(ParentalConsentStatus.APPROVED.allowsPersonalFeatures());
        assertFalse(ParentalConsentStatus.PENDING.allowsPersonalFeatures());
        assertFalse(ParentalConsentStatus.REVOKED.allowsPersonalFeatures());
        assertFalse(ParentalConsentStatus.EXPIRED.allowsPersonalFeatures());
    }

    @Test
    void consentCanBeGrantedRevokedAndRequestedAgain() {
        assertTrue(ParentalConsentStatus.PENDING.canBecome(ParentalConsentStatus.APPROVED));
        assertTrue(ParentalConsentStatus.PENDING.canBecome(ParentalConsentStatus.EXPIRED));
        assertTrue(ParentalConsentStatus.APPROVED.canBecome(ParentalConsentStatus.REVOKED));
        assertTrue(ParentalConsentStatus.REVOKED.canBecome(ParentalConsentStatus.PENDING));
        assertTrue(ParentalConsentStatus.EXPIRED.canBecome(ParentalConsentStatus.PENDING));
    }

    @Test
    void aRevokedOrExpiredConsentNeverSilentlyBecomesApproved() {
        assertFalse(ParentalConsentStatus.REVOKED.canBecome(ParentalConsentStatus.APPROVED));
        assertFalse(ParentalConsentStatus.EXPIRED.canBecome(ParentalConsentStatus.APPROVED));
        assertFalse(ParentalConsentStatus.NOT_REQUIRED.canBecome(ParentalConsentStatus.PENDING));
    }
}
