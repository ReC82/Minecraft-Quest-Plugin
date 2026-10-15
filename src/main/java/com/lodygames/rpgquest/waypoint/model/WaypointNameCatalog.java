package com.lodygames.rpgquest.waypoint.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Réserve statique de noms d'affichage de waypoints (issues #133/#135) — un nom par waypoint,
 * humain et unique, distinct de son biome (métadonnée secondaire). Chargée depuis un fichier texte
 * bundlé (un nom par ligne), <strong>jamais un appel IA au runtime</strong> : la future issue #148
 * enrichira cette réserve en dehors de ce mécanisme, sans le modifier.
 *
 * <p>Sans état, sans Bukkit — testable en JUnit pur, même patron que
 * {@code waypoint.WaypointGenerationPlanner}.</p>
 */
public final class WaypointNameCatalog {

    private static final String BUNDLED_RESOURCE = "/waypoint-names.txt";
    private static final String FALLBACK_PREFIX = "Avant-poste ";

    private final List<String> reserve;

    private WaypointNameCatalog(List<String> reserve) {
        this.reserve = List.copyOf(reserve);
    }

    /**
     * Lit un nom par ligne ; lignes vides et commentaires ({@code #}) ignorés ; dédoublonnage
     * <strong>à l'import</strong> (espaces en début/fin, casse, accents — voir {@link #normalize}),
     * ne gardant que la première occurrence de chaque nom.
     */
    public static WaypointNameCatalog load(InputStream resource) {
        Set<String> seenNormalized = new LinkedHashSet<>();
        List<String> reserve = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String normalized = normalize(trimmed);
                if (seenNormalized.add(normalized)) {
                    reserve.add(trimmed);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de lire le catalogue de noms de waypoints", e);
        }
        return new WaypointNameCatalog(reserve);
    }

    public static WaypointNameCatalog loadBundled() {
        try (InputStream in = WaypointNameCatalog.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Catalogue de noms de waypoints introuvable dans le jar : " + BUNDLED_RESOURCE);
            }
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Nombre de noms distincts disponibles dans la réserve (après dédoublonnage à l'import). */
    public int size() {
        return reserve.size();
    }

    List<String> reserve() {
        return reserve;
    }

    /** Insensible à la casse et aux accents, espaces en début/fin ignorés — même règle que #135. */
    public static String normalize(String name) {
        String decomposed = Normalizer.normalize(name.trim(), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** Longueur maximale d'un nom saisi par un administrateur (issue #134) : 4 lignes de panneau. */
    public static final int MAX_DISPLAY_NAME_LENGTH = 40;

    /**
     * Lettres (accents compris) et chiffres, puis espaces, apostrophes, tirets et points (issue
     * #134). Aucun chevron ni balise : le nom est aussi inséré dans des gabarits MiniMessage du menu
     * de voyage, il ne doit jamais pouvoir y injecter de mise en forme.
     */
    private static final java.util.regex.Pattern DISPLAY_NAME =
            java.util.regex.Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} '’.\\-]*");

    /** Espaces de début/fin retirés, espaces intérieurs consécutifs réduits à un seul. */
    public static String cleanDisplayName(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    }

    /**
     * Règles d'un nom d'affichage saisi à la main (issue #134), sur un nom déjà passé par
     * {@link #cleanDisplayName}. L'unicité, elle, dépend de l'état du serveur : voir
     * {@code WaypointService#rename}.
     *
     * @return le motif du refus, ou vide si le nom est acceptable
     */
    public static java.util.Optional<String> validateDisplayName(String cleaned) {
        if (cleaned == null || cleaned.length() < 2) {
            return java.util.Optional.of("Le nom doit contenir au moins 2 caractères.");
        }
        if (cleaned.length() > MAX_DISPLAY_NAME_LENGTH) {
            return java.util.Optional.of("Le nom ne doit pas dépasser " + MAX_DISPLAY_NAME_LENGTH + " caractères.");
        }
        if (!DISPLAY_NAME.matcher(cleaned).matches()) {
            return java.util.Optional.of("Caractères autorisés : lettres, chiffres, espaces, apostrophes, "
                    + "tirets et points (commencer par une lettre ou un chiffre).");
        }
        return java.util.Optional.empty();
    }

    /**
     * Choisit et <strong>réserve immédiatement</strong> (ajoute à {@code usedNormalized}, qui doit
     * donc déjà contenir toutes les attributions existantes) un nom non encore utilisé, dans un
     * ordre mélangé déterministe pour {@code seed} — deux appels avec la même seed et le même état
     * de {@code used} choisissent toujours le même nom. Aucun doublon silencieux à l'attribution,
     * y compris pour deux générations synchrones consécutives (même thread Bukkit) partageant le
     * même ensemble {@code usedNormalized}. Réserve épuisée → nom de secours unique
     * ({@code "Avant-poste N"}), jamais un échec.
     */
    public String reserveName(long seed, Set<String> usedNormalized) {
        List<String> order = new ArrayList<>(reserve);
        Collections.shuffle(order, new Random(seed));
        for (String candidate : order) {
            if (usedNormalized.add(normalize(candidate))) {
                return candidate;
            }
        }
        int n = 1;
        while (true) {
            String fallback = FALLBACK_PREFIX + n;
            if (usedNormalized.add(normalize(fallback))) {
                return fallback;
            }
            n++;
        }
    }
}
