package com.lodygames.rpgquest.ops;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Tampon circulaire borné des lignes de console récentes (issue #95), lu par l'action agent
 * {@code server.logs.tail} et affiché par la page « Exploitation serveur » du Control Panel.
 *
 * <p><strong>Pourquoi un tampon en mémoire, et pas un fichier.</strong> Le fichier de log du serveur
 * n'est <em>pas</em> atteignable : la racine FTP VeryGames est le dossier {@code plugins/} et
 * l'hébergeur refuse toute remontée de dossier (mesuré : « Server denied you to change to the given
 * directory » sur {@code ../logs}). Le seul transport disponible est donc l'agent sortant déjà en
 * place — ce que le ticket demande explicitement de réutiliser plutôt que d'ouvrir un canal
 * parallèle ou un port entrant.</p>
 *
 * <p><strong>Garanties.</strong> Capacité finie (jamais de fuite mémoire), messages tronqués,
 * compteur de lignes perdues pour que l'interface puisse dire « il manque des lignes » au lieu de
 * faire croire à un flux continu, et numéros de séquence strictement croissants qui servent de
 * curseur au panel (pas d'horodatage comme clé : deux lignes peuvent partager la même
 * milliseconde).</p>
 *
 * <p>Aucune dépendance Bukkit ni Log4j : entièrement testable en JVM nue. L'alimentation vient de
 * {@link ConsoleTap}.</p>
 */
public final class ServerLogBuffer {

    /** Bornes de capacité : assez pour une console utile, jamais assez pour peser. */
    public static final int MIN_CAPACITY = 50;
    public static final int MAX_CAPACITY = 5_000;
    /** Au-delà, un message est tronqué : une ligne de log n'est pas un transport de données. */
    public static final int MAX_MESSAGE_CHARS = 400;
    /** Borne dure du nombre de lignes renvoyées en une fois (le payload agent est borné). */
    public static final int MAX_TAIL = 500;

    /**
     * Une ligne capturée.
     *
     * @param sequence     numéro strictement croissant, curseur stable pour le panel
     * @param epochMillis  horodatage de capture
     * @param level        {@code ERROR} / {@code WARN} / {@code INFO} / {@code DEBUG}
     * @param source       émetteur court (nom du logger), jamais un chemin ni un secret
     * @param message      texte, tronqué à {@link #MAX_MESSAGE_CHARS}
     */
    public record Line(long sequence, long epochMillis, String level, String source, String message) {
    }

    /**
     * Extrait renvoyé à l'appelant.
     *
     * @param lines         lignes demandées, dans l'ordre chronologique
     * @param firstSequence première séquence encore présente dans le tampon (0 si vide)
     * @param lastSequence  dernière séquence connue (0 si rien n'a jamais été capturé)
     * @param dropped       nombre total de lignes évincées depuis le démarrage
     * @param capacity      capacité effective du tampon
     * @param gap           {@code true} si le curseur demandé est antérieur aux lignes conservées :
     *                      des lignes manquent, et l'interface doit le dire
     */
    public record Snapshot(List<Line> lines, long firstSequence, long lastSequence, long dropped,
                           int capacity, boolean gap) {
    }

    private final int capacity;
    private final Deque<Line> lines;
    private long nextSequence = 1L;
    private long dropped;

    public ServerLogBuffer(int capacity) {
        this.capacity = Math.max(MIN_CAPACITY, Math.min(MAX_CAPACITY, capacity));
        this.lines = new ArrayDeque<>(this.capacity);
    }

    public int capacity() {
        return capacity;
    }

    /**
     * Ajoute une ligne. Appelé depuis <strong>n'importe quel thread</strong> (Log4j peut être
     * asynchrone) : la méthode est donc synchronisée et ne journalise jamais rien elle-même, pour
     * ne pas se rappeler en boucle.
     */
    public synchronized void append(long epochMillis, String level, String source, String message) {
        if (lines.size() >= capacity) {
            lines.removeFirst();
            dropped++;
        }
        lines.addLast(new Line(nextSequence++, epochMillis, normalizeLevel(level),
                shorten(source, 60, "?"), shorten(message, MAX_MESSAGE_CHARS, "")));
    }

    /**
     * Lignes suivant {@code afterSequence}, au plus {@code limit}.
     *
     * @param afterSequence {@code <= 0} = première consultation, on renvoie la fin du tampon ;
     *                      sinon, uniquement ce qui est strictement postérieur au curseur
     */
    public synchronized Snapshot tail(long afterSequence, int limit) {
        int max = Math.max(1, Math.min(MAX_TAIL, limit));
        long first = lines.isEmpty() ? 0L : lines.peekFirst().sequence();
        long last = nextSequence - 1;
        List<Line> out = new ArrayList<>();
        if (afterSequence <= 0) {
            // Première consultation : la fin du tampon, pas le début — on veut le récent.
            int skip = Math.max(0, lines.size() - max);
            int i = 0;
            for (Line line : lines) {
                if (i++ >= skip) {
                    out.add(line);
                }
            }
            return new Snapshot(List.copyOf(out), first, last, dropped, capacity, false);
        }
        for (Line line : lines) {
            if (line.sequence() > afterSequence) {
                out.add(line);
                if (out.size() >= max) {
                    break;
                }
            }
        }
        // Le curseur du panel pointe avant la plus ancienne ligne conservée : des lignes ont été
        // évincées entre deux consultations. Le dire est plus honnête qu'un flux d'apparence continue.
        boolean gap = first > 0 && afterSequence < first - 1;
        return new Snapshot(List.copyOf(out), first, last, dropped, capacity, gap);
    }

    /** Vide le tampon sans réinitialiser les séquences (un curseur de panel reste valide). */
    public synchronized void clear() {
        lines.clear();
    }

    private static String normalizeLevel(String raw) {
        String l = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (l) {
            case "FATAL", "SEVERE", "ERROR" -> "ERROR";
            case "WARN", "WARNING" -> "WARN";
            case "DEBUG", "FINE", "FINER", "FINEST", "TRACE" -> "DEBUG";
            default -> "INFO";
        };
    }

    private static String shorten(String raw, int max, String fallback) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        String oneLine = raw.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
