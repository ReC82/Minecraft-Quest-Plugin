package com.lodygames.rpgquest.webapi.privacy;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Politique d'âge des futurs comptes joueurs (issue #55), entièrement configurable : aucune règle
 * universelle n'est codée en dur.
 *
 * <p>Chaque région desservie a son seuil ({@code accounts.age-threshold.<REGION>=<âge>}). Sous le
 * seuil, {@code accounts.below-threshold} décide : consentement parental ou refus. Une région
 * inconnue ou non desservie suit {@code accounts.unknown-region}. Sans aucune région configurée,
 * les comptes restent <strong>fermés</strong> : rien ne s'ouvre avant la décision du propriétaire,
 * validée juridiquement (voir {@code docs/PRIVACY.md}).</p>
 *
 * <p>L'âge déclaré sert uniquement à calculer la décision ; seul le résultat est destiné à être
 * conservé.</p>
 */
public record AgePolicy(Map<String, Integer> thresholds, Outcome belowThreshold, Outcome unknownRegion) {

    public static final String THRESHOLD_PREFIX = "accounts.age-threshold.";

    /** Décision pour une demande de compte. */
    public enum Outcome {
        ALLOWED,
        PARENTAL_CONSENT_REQUIRED,
        REFUSED
    }

    public AgePolicy {
        thresholds = Map.copyOf(thresholds);
        if (belowThreshold == Outcome.ALLOWED || unknownRegion == Outcome.ALLOWED) {
            throw new IllegalArgumentException("Un âge sous le seuil ou une région inconnue ne peut pas être ALLOWED.");
        }
    }

    /** Politique fermée : aucune région desservie, aucun compte possible. */
    public static AgePolicy closed() {
        return new AgePolicy(Map.of(), Outcome.REFUSED, Outcome.REFUSED);
    }

    public boolean accountsOpen() {
        return !thresholds.isEmpty();
    }

    /** {@code region} : code ISO 3166-1 alpha-2 déclaré, vide si inconnu. */
    public Outcome evaluate(Optional<String> region, int declaredAge) {
        if (declaredAge < 0) {
            return Outcome.REFUSED;
        }
        Integer threshold = region.map(code -> thresholds.get(code.trim().toUpperCase(Locale.ROOT))).orElse(null);
        if (threshold == null) {
            return unknownRegion;
        }
        return declaredAge >= threshold ? Outcome.ALLOWED : belowThreshold;
    }

    /** Statut de consentement initial qui découle d'une décision. */
    public static Optional<ParentalConsentStatus> initialConsent(Outcome outcome) {
        return switch (outcome) {
            case ALLOWED -> Optional.of(ParentalConsentStatus.NOT_REQUIRED);
            case PARENTAL_CONSENT_REQUIRED -> Optional.of(ParentalConsentStatus.PENDING);
            case REFUSED -> Optional.empty();
        };
    }

    /** Lit la politique dans {@code web-api.properties} ; refuse toute valeur ambiguë. */
    public static AgePolicy fromProperties(Properties properties) {
        Map<String, Integer> thresholds = new TreeMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(THRESHOLD_PREFIX)) {
                continue;
            }
            String region = key.substring(THRESHOLD_PREFIX.length()).toUpperCase(Locale.ROOT);
            if (!region.matches("[A-Z]{2}")) {
                throw new IllegalStateException("« " + key + " » : la région doit être un code pays à deux lettres.");
            }
            String raw = properties.getProperty(key).trim();
            int age;
            try {
                age = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("« " + key + " » doit être un âge entier, valeur trouvée : " + raw);
            }
            if (age < 1 || age > 25) {
                throw new IllegalStateException("« " + key + " » doit être compris entre 1 et 25, valeur trouvée : " + age);
            }
            thresholds.put(region, age);
        }
        return new AgePolicy(thresholds,
                restrictive(properties, "accounts.below-threshold", Outcome.PARENTAL_CONSENT_REQUIRED),
                restrictive(properties, "accounts.unknown-region", Outcome.REFUSED));
    }

    private static Outcome restrictive(Properties properties, String key, Outcome defaultValue) {
        String raw = properties.getProperty(key);
        if (raw == null) {
            return defaultValue;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "parental-consent" -> Outcome.PARENTAL_CONSENT_REQUIRED;
            case "refuse" -> Outcome.REFUSED;
            default -> throw new IllegalStateException(
                    "« " + key + " » doit valoir parental-consent ou refuse, valeur trouvée : " + raw);
        };
    }
}
