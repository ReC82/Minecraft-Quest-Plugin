package com.lodygames.rpgquest.city;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Règles du nom affiché et de l'identifiant stable d'une ville (issue #178).
 *
 * <p>Le nom se change librement ; l'identifiant est dérivé du nom <strong>une seule fois</strong>, à
 * la création, puis ne change plus jamais. Renommer « Bourg-Neuf » en « Port-Lily » garde donc
 * {@code ville_bourg_neuf}, et toutes les découvertes et quêtes qui le citent.</p>
 */
public final class CityName {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 40;
    private static final int MAX_ID_SLUG = 32;

    private CityName() {
    }

    /** Nom nettoyé si valide, sinon message d'erreur en français. */
    public static Checked check(String raw) {
        String name = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        if (name.length() < MIN_LENGTH) {
            return Checked.error("Le nom de la ville doit faire au moins " + MIN_LENGTH + " caractères.");
        }
        if (name.length() > MAX_LENGTH) {
            return Checked.error("Nom trop long (" + name.length() + " caractères, " + MAX_LENGTH
                    + " au plus).");
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            return Checked.error("Le nom contient un caractère de contrôle.");
        }
        return new Checked(name, null);
    }

    /**
     * Identifiant stable dérivé du nom : {@code ville_} + version ASCII en minuscules. Si
     * l'identifiant est déjà pris, un suffixe numérique est ajouté ({@code _2}, {@code _3}…).
     */
    public static String newId(String name, Predicate<String> taken) {
        String slug = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) {
            slug = "sans_nom";
        }
        if (slug.length() > MAX_ID_SLUG) {
            slug = slug.substring(0, MAX_ID_SLUG).replaceAll("_+$", "");
        }
        String base = "ville_" + slug;
        String candidate = base;
        for (int n = 2; taken.test(candidate); n++) {
            candidate = base + "_" + n;
        }
        return candidate;
    }

    /** Résultat de {@link #check}. */
    public record Checked(String name, String error) {

        static Checked error(String message) {
            return new Checked(null, message);
        }

        public boolean ok() {
            return error == null;
        }

        public Optional<String> errorMessage() {
            return Optional.ofNullable(error);
        }
    }
}
