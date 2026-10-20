package com.lodygames.rpgquest.panel.building;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * La façade d'entrée que la <strong>description</strong> d'un projet demande, comparée à celle du
 * sélecteur « Façade d'entrée » (issue #655).
 *
 * <h2>Le défaut</h2>
 *
 * <p>« Boulangerie de Central », 20/10/2026 : la description dit « Façade principale et entrée à
 * l'OUEST », le sélecteur dit EST. L'IA suivait la description et perçait l'ouest ; le serveur,
 * lui, cherche l'entrée sur la façade déclarée — l'est — et refusait {@code NO_ENTRANCE}. Douze
 * appels payants plus tard, aucun plan exploitable : chaque correction tirait dans un sens, l'audit
 * dans l'autre. Aucune correction ne pouvait réussir, et rien ne le disait.</p>
 *
 * <h2>Ce que fait cette classe</h2>
 *
 * <p>Elle ne tranche pas : elle <em>détecte</em>. Les gestes payants sont refusés, à coût nul,
 * tant que la contradiction existe ; l'administrateur corrige la description ou le sélecteur, et
 * c'est sa volonté qui fait foi. La lecture est volontairement étroite : seule une tournure
 * explicite — « entrée à l'ouest », « entrée principale au nord », « entrée côté sud », « façade
 * d'entrée à l'est » — compte. Une description qui cite plusieurs côtés pour l'entrée, ou aucun,
 * ne produit jamais de contradiction : un faux positif bloquerait un projet sain.</p>
 */
public final class BriefOrientation {

    private BriefOrientation() {
    }

    private static final Pattern ENTRANCE = Pattern.compile(
            "(?:entr[ée]e(?:\\s+principale)?|fa[çc]ade\\s+(?:principale|d['’]\\s*entr[ée]e)"
                    + "(?:\\s+et\\s+entr[ée]e)?)"
                    + "\\s+(?:est\\s+)?(?:situ[ée]e\\s+)?(?:à|a|au|sur|côté|cote|face\\s+à|plein)\\s+"
                    + "(?:la\\s+fa[çc]ade\\s+)?(?:l['’]\\s*|le\\s+)?(ouest|est|nord|sud)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** La direction demandée par la description, en vocabulaire du plan (NORTH, EAST…), si elle est unique. */
    public static Optional<String> requestedFront(String brief) {
        if (brief == null || brief.isBlank()) {
            return Optional.empty();
        }
        Set<String> found = new LinkedHashSet<>();
        Matcher m = ENTRANCE.matcher(brief);
        while (m.find()) {
            found.add(compass(m.group(1)));
        }
        return found.size() == 1 ? Optional.of(found.iterator().next()) : Optional.empty();
    }

    /**
     * La contradiction entre la description et le sélecteur, rédigée pour l'écran ; vide s'il n'y en
     * a pas, ou si la description ne dit rien de clair.
     */
    public static Optional<String> conflict(String brief, String front) {
        String declared = front == null ? "" : front.trim().toUpperCase(Locale.ROOT);
        return requestedFront(brief).filter(wanted -> !declared.isEmpty() && !wanted.equals(declared))
                .map(wanted -> "Votre description place l'entrée " + at(wanted)
                        + ", mais la façade d'entrée choisie dans le projet est " + label(declared)
                        + ". Le serveur vérifie l'entrée sur la façade choisie (" + label(declared)
                        + ") : tant que les deux se contredisent, l'IA ne peut pas réussir. Corrigez la "
                        + "façade d'entrée ou la description dans « Le bâtiment souhaité ».");
    }

    public static Optional<String> conflict(BuildingProject project) {
        return project == null ? Optional.empty() : conflict(project.brief(), project.front());
    }

    private static String compass(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "nord" -> "NORTH";
            case "sud" -> "SOUTH";
            case "est" -> "EAST";
            default -> "WEST";
        };
    }

    private static String at(String front) {
        return switch (front) {
            case "NORTH" -> "au NORD";
            case "SOUTH" -> "au SUD";
            case "EAST" -> "à l'EST";
            default -> "à l'OUEST";
        };
    }

    /** Le nom français d'une orientation du plan, en capitales comme dans les briefs. */
    public static String label(String front) {
        return switch (front) {
            case "NORTH" -> "NORD";
            case "SOUTH" -> "SUD";
            case "EAST" -> "EST";
            case "WEST" -> "OUEST";
            default -> front;
        };
    }
}
