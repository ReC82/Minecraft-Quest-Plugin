package com.lodygames.rpgquest.webapi.privacy;

import java.util.Set;

/**
 * Consentement parental d'un compte joueur (issue #55), préparé pour les comptes de #53.
 *
 * <p>Seul le statut est conservé, jamais l'identité du parent montrée à d'autres joueurs. Tant
 * que le statut n'autorise pas les fonctions personnelles, le compte reste limité : pas de profil
 * public, pas de liaison Minecraft, pas d'inscription à un événement.</p>
 */
public enum ParentalConsentStatus {
    /** L'âge déclaré et la région n'exigent aucun consentement. */
    NOT_REQUIRED,
    /** Demande envoyée, pas encore de réponse : compte limité. */
    PENDING,
    APPROVED,
    /** Retiré par le parent ou le joueur : compte de nouveau limité, données publiques retirées. */
    REVOKED,
    /** Demande restée sans réponse dans le délai : compte limité. */
    EXPIRED;

    /** Fonctions personnelles (profil public, liaison Minecraft, événements) autorisées. */
    public boolean allowsPersonalFeatures() {
        return this == NOT_REQUIRED || this == APPROVED;
    }

    /** Transitions permises ; toute autre est refusée plutôt que devinée. */
    public boolean canBecome(ParentalConsentStatus next) {
        return switch (this) {
            case NOT_REQUIRED -> false;
            case PENDING -> Set.of(APPROVED, EXPIRED, REVOKED).contains(next);
            case APPROVED -> Set.of(REVOKED, EXPIRED).contains(next);
            case REVOKED, EXPIRED -> next == PENDING;
        };
    }
}
