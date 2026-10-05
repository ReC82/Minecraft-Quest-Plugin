package com.lodygames.rpgquest.discord.sync;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Assainissement du contenu écrit par les membres du forum (issue #202).
 *
 * <p><strong>Principe non négociable : le contenu utilisateur est une donnée, jamais une
 * commande.</strong> Il n'est jamais interprété, jamais exécuté, et jamais recopié tel quel dans
 * une structure qu'il pourrait détourner. Concrètement, quatre risques sont traités ici :</p>
 *
 * <ol>
 *   <li><strong>Évasion des marqueurs.</strong> Le corps de l'issue délimite la zone gérée par le
 *       service avec des commentaires HTML. Un membre qui écrirait {@code -->} dans son message
 *       pourrait fermer ce commentaire par anticipation, et donc forger ou casser la zone gérée.
 *       Les séquences {@code <!--} et {@code -->} sont donc retirées.</li>
 *   <li><strong>Notifications indésirables.</strong> Un {@code @pseudo} recopié dans une issue
 *       <em>notifie réellement</em> la personne sur GitHub. Les mentions sont neutralisées en les
 *       plaçant entre accents graves, forme que GitHub n'interprète plus.</li>
 *   <li><strong>Références croisées parasites.</strong> Un {@code #123} crée un lien vers un autre
 *       ticket et pollue son historique. Même neutralisation.</li>
 *   <li><strong>Contenu démesuré.</strong> La taille est bornée, avec une mention de troncature
 *       explicite et le renvoi vers le sujet Discord, qui reste la source complète.</li>
 * </ol>
 *
 * <p>Le texte est de plus rendu en <strong>bloc de citation</strong> : visuellement, on voit tout
 * de suite ce qui vient d'un membre et ce qui vient du projet, et un titre Markdown écrit par un
 * membre ne peut pas se faire passer pour une section du service.</p>
 */
public final class ContentSanitizer {

    /** Au-delà, l'issue devient illisible et le sujet Discord reste la source complète. */
    public static final int MAX_BODY_CHARS = 6_000;

    /** GitHub accepte des titres longs ; rester court garde le backlog lisible. */
    public static final int MAX_TITLE_CHARS = 180;

    private static final Pattern MENTION = Pattern.compile("@([A-Za-z0-9_.\\-]{1,64})");
    private static final Pattern ISSUE_REF = Pattern.compile("(?<![A-Za-z0-9`])#(\\d{1,7})\\b");
    private static final Pattern GH_REF = Pattern.compile("\\bGH-(\\d{1,7})\\b");

    private ContentSanitizer() {
    }

    /**
     * Titre d'issue à partir du titre du sujet : une seule ligne, sans caractère de contrôle,
     * borné. Un titre vide est remplacé par un libellé explicite plutôt que par du vide.
     */
    public static String title(String rawTitle) {
        String text = stripControl(rawTitle == null ? "" : rawTitle)
                .replaceAll("\\s+", " ")
                .strip();
        if (text.isEmpty()) {
            return "(sujet Discord sans titre)";
        }
        if (text.length() > MAX_TITLE_CHARS) {
            text = text.substring(0, MAX_TITLE_CHARS - 1).strip() + "…";
        }
        return text;
    }

    /**
     * Message initial rendu en bloc de citation, assaini et borné.
     *
     * @param threadUrl lien du sujet, cité dans la mention de troncature
     */
    public static String quotedBody(String rawBody, String threadUrl) {
        String text = defang(stripControl(rawBody == null ? "" : rawBody));
        if (text.isBlank()) {
            return "> *(message initial vide, ou illisible par le bot : vérifier la permission "
                    + "« Lire l'historique des messages » sur le salon)*";
        }
        boolean truncated = text.length() > MAX_BODY_CHARS;
        if (truncated) {
            text = text.substring(0, MAX_BODY_CHARS);
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            sb.append("> ").append(line).append('\n');
        }
        if (truncated) {
            sb.append(">\n> *(message tronqué à ").append(MAX_BODY_CHARS)
                    .append(" caractères — texte complet dans le sujet Discord : ")
                    .append(threadUrl).append(")*\n");
        }
        return sb.toString().stripTrailing();
    }

    /** Nom affiché d'un auteur : assaini et borné, mentions neutralisées. */
    public static String authorName(String rawName) {
        String text = defang(stripControl(rawName == null ? "" : rawName))
                .replaceAll("\\s+", " ")
                .strip();
        if (text.isEmpty()) {
            return "(auteur inconnu)";
        }
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }

    /**
     * Nom de fichier d'une pièce jointe : assaini, borné, et jamais utilisé comme chemin. Le
     * service ne télécharge rien — ce nom n'est qu'une étiquette affichée.
     */
    public static String fileName(String rawName) {
        String text = stripControl(rawName == null ? "" : rawName)
                .replace('\\', '/')
                .replaceAll("\\s+", " ")
                .strip();
        int slash = text.lastIndexOf('/');
        if (slash >= 0) {
            text = text.substring(slash + 1);
        }
        text = defang(text).replace("|", " ");
        if (text.isEmpty()) {
            return "(nom de fichier absent)";
        }
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }

    /**
     * Retire les séquences qui permettraient de sortir de la zone gérée du corps d'issue, puis
     * neutralise les mentions et les références de tickets.
     */
    static String defang(String text) {
        // Les DEUX délimiteurs sont réécrits sous une forme qui ne contient plus la séquence
        // d'origine. Remplacer « <!-- » par « (<!--) » ne servirait à rien : la séquence serait
        // toujours là, et un membre pourrait encore ouvrir un commentaire HTML qui masquerait la
        // suite de la zone gérée.
        String safe = text.replace("<!--", "(&lt;!--)").replace("-->", "(--&gt;)");
        safe = replaceAll(MENTION, safe, m -> "`@" + m.group(1) + "`");
        safe = replaceAll(ISSUE_REF, safe, m -> "`#" + m.group(1) + "`");
        safe = replaceAll(GH_REF, safe, m -> "`GH-" + m.group(1) + "`");
        return safe;
    }

    /** Retire les caractères de contrôle, en gardant les retours à la ligne et les tabulations. */
    private static String stripControl(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        String normalised = text.replace("\r\n", "\n").replace('\r', '\n');
        normalised.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\t' || !Character.isISOControl(cp)) {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }

    private static String replaceAll(Pattern pattern, String input,
                                     java.util.function.Function<Matcher, String> replacer) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            sb.append(input, last, matcher.start()).append(replacer.apply(matcher));
            last = matcher.end();
        }
        return sb.append(input.substring(last)).toString();
    }

    /** Taille lisible d'une pièce jointe, sans prétendre à une précision inutile. */
    public static String humanSize(long bytes) {
        if (bytes < 0) {
            return "taille inconnue";
        }
        if (bytes < 1024) {
            return bytes + " o";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f ko", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f Mo", bytes / (1024.0 * 1024.0));
    }
}
