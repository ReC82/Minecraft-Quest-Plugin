package com.lodygames.rpgquest.panel.content;

import java.util.List;

/**
 * Un constat de validation d'un contenu éditable (#46). Trois niveaux, sémantique de B7 :
 * <ul>
 *   <li>{@link Level#ERROR} — bloque l'enregistrement ;</li>
 *   <li>{@link Level#WARNING} — enregistrement possible après confirmation explicite ;</li>
 *   <li>{@link Level#INFO} — information, n'empêche rien.</li>
 * </ul>
 *
 * @param level niveau
 * @param field champ ou section concerné (ex. {@code "title"}, {@code "steps[0].objectives[1]"}) ; peut être vide
 * @param message message lisible en français
 */
public record Diagnostic(Level level, String field, String message) {

    public enum Level { ERROR, WARNING, INFO }

    public static Diagnostic error(String field, String message) {
        return new Diagnostic(Level.ERROR, field, message);
    }

    public static Diagnostic warning(String field, String message) {
        return new Diagnostic(Level.WARNING, field, message);
    }

    public static Diagnostic info(String field, String message) {
        return new Diagnostic(Level.INFO, field, message);
    }

    public static boolean hasError(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(d -> d.level() == Level.ERROR);
    }

    public static boolean hasWarning(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(d -> d.level() == Level.WARNING);
    }
}
