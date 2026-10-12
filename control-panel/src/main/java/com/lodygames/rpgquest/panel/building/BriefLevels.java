package com.lodygames.rpgquest.panel.building;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Le nombre de niveaux qu'un brief exige, confronté à ce que l'audit a mesuré (issue #256).
 *
 * <h2>Le défaut</h2>
 *
 * <p>Le brief de la Bibliothèque du Hub dit « Bâtiment sur deux étages ». L'audit du plugin a
 * mesuré {@code floors: 1, reachableFloors: 1} et conclu « conforme ». Il n'avait pas tort sur son
 * propre contrat : il ne connaît pas le brief, et ne contrôle l'accès aux niveaux que s'il y en a
 * plusieurs. Un étage <em>absent</em> passait donc en silence, exactement comme une porte absente
 * passait avant la V2.</p>
 *
 * <h2>Pourquoi un avertissement, et pas un refus</h2>
 *
 * <p>Ce qu'on lit ici est du français libre. « Deux étages » se dit couramment d'un bâtiment à deux
 * niveaux, mais au sens strict désigne un R+2. Un refus fondé sur une lecture incertaine bloquerait
 * à tort ; un avertissement fait voir l'écart et laisse décider. On retient donc toujours
 * l'interprétation la <strong>plus basse</strong> : « N étages » = au moins N niveaux. Si même ce
 * minimum n'est pas atteint, l'écart est certain.</p>
 *
 * <p>La lecture est volontairement étroite : un nombre explicitement accolé à « étage(s) » ou
 * « niveau(x) », la notation « R+N », ou « à étage » / « avec un étage ». Rien d'autre — « au
 * premier étage, un comptoir » ne dit pas combien le bâtiment en compte, et n'est pas lu.</p>
 */
public final class BriefLevels {

    private static final Map<String, Integer> WORDS = Map.ofEntries(
            Map.entry("un", 1), Map.entry("une", 1), Map.entry("deux", 2), Map.entry("trois", 3),
            Map.entry("quatre", 4), Map.entry("cinq", 5), Map.entry("six", 6));

    /** « sur deux étages », « 3 niveaux » — le nombre accolé au mot, au pluriel. */
    private static final Pattern COUNTED = Pattern.compile(
            "\\b(\\d{1,2}|un|une|deux|trois|quatre|cinq|six)\\s+(?:etages|niveaux)\\b");
    /** « R+1 », « r + 2 ». */
    private static final Pattern R_PLUS = Pattern.compile("\\br\\s*\\+\\s*(\\d{1,2})\\b");
    /** « à étage », « avec un étage », « avec étage » : au moins un niveau au-dessus du sol. */
    private static final Pattern WITH_UPPER = Pattern.compile(
            "\\b(?:a|avec|comportant)\\s+(?:un\\s+)?etage\\b");

    private BriefLevels() {
    }

    /** Le nombre minimal de niveaux que le brief exige, s'il en dit un clairement. */
    public static OptionalInt expected(String brief) {
        if (brief == null || brief.isBlank()) {
            return OptionalInt.empty();
        }
        String text = normalise(brief);
        int best = 0;
        Matcher counted = COUNTED.matcher(text);
        while (counted.find()) {
            best = Math.max(best, number(counted.group(1)));
        }
        Matcher rPlus = R_PLUS.matcher(text);
        while (rPlus.find()) {
            best = Math.max(best, Integer.parseInt(rPlus.group(1)) + 1);
        }
        if (WITH_UPPER.matcher(text).find()) {
            best = Math.max(best, 2);
        }
        return best >= 1 && best <= 20 ? OptionalInt.of(best) : OptionalInt.empty();
    }

    /**
     * L'écart entre le brief et la mesure, en une phrase, ou rien.
     *
     * @param metrics les mesures de l'audit ({@code floors}, {@code reachableFloors})
     */
    public static Optional<String> divergence(String brief, Map<String, Object> metrics) {
        OptionalInt wanted = expected(brief);
        if (wanted.isEmpty() || metrics == null || !metrics.containsKey("floors")) {
            return Optional.empty();
        }
        int floors = intOf(metrics.get("floors"));
        int reachable = metrics.containsKey("reachableFloors")
                ? intOf(metrics.get("reachableFloors")) : floors;
        int need = wanted.getAsInt();
        if (floors >= need && reachable >= need) {
            return Optional.empty();
        }
        // #265 : en français courant — ni « brief », ni « grille », ni « audit ».
        if (floors < need) {
            return Optional.of("Votre description demande " + need + " niveaux (rez-de-chaussée "
                    + "compris), mais le plan n'en construit que " + floors + ". Il manque un étage "
                    + "complet, relié au rez-de-chaussée par un escalier ou une échelle.");
        }
        return Optional.of("Votre description demande " + need + " niveaux : le plan en construit "
                + floors + ", mais seulement " + reachable + " accessible(s) depuis le rez-de-chaussée. "
                + "Il manque un escalier ou une échelle.");
    }

    private static int number(String raw) {
        Integer word = WORDS.get(raw);
        if (word != null) {
            return word;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private static int intOf(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : (int) Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /** Minuscules sans accents, apostrophes typographiques ramenées à la droite. */
    private static String normalise(String text) {
        String flat = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return flat.toLowerCase(Locale.ROOT).replace('’', '\'');
    }
}
