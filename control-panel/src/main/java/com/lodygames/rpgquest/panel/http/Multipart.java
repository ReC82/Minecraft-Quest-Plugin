package com.lodygames.rpgquest.panel.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lecture d'un formulaire {@code multipart/form-data} (RFC 7578), pour l'envoi d'images de
 * référence (issue #283).
 *
 * <p>Le panel n'avait que des formulaires {@code application/x-www-form-urlencoded}. Un fichier ne
 * s'y transporte pas, et la politique de sécurité interdit tout script qui l'encoderait côté
 * navigateur : le corps est donc lu ici, <strong>en octets</strong> — une image passée par une
 * chaîne serait corrompue au premier octet non UTF-8.</p>
 *
 * <p>Volontairement minimal : une seule profondeur, pas de {@code multipart/mixed} imbriqué, taille
 * totale bornée par l'appelant. Ce qui n'est pas compris est refusé, jamais deviné.</p>
 */
public final class Multipart {

    private Multipart() {
    }

    private static final Pattern BOUNDARY = Pattern.compile("boundary=(?:\"([^\"]+)\"|([^;\\s]+))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME = Pattern.compile("[; ]name=\"([^\"]*)\"");
    private static final Pattern FILENAME = Pattern.compile("filename=\"([^\"]*)\"");

    /** Un fichier reçu : nom annoncé par le navigateur (jamais cru pour le type) et octets. */
    public record FilePart(String field, String fileName, byte[] data) {
    }

    /** Les champs texte et les fichiers d'un formulaire. */
    public record Form(Map<String, String> fields, List<FilePart> files) {

        public Optional<FilePart> file(String field) {
            return files.stream().filter(f -> f.field().equals(field)).findFirst();
        }
    }

    /**
     * Analyse un corps. Vide si l'en-tête ne déclare pas de frontière ou si le corps est mal formé.
     */
    public static Optional<Form> parse(String contentType, byte[] body) {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            return Optional.empty();
        }
        Matcher m = BOUNDARY.matcher(contentType);
        if (!m.find()) {
            return Optional.empty();
        }
        byte[] delimiter = ("--" + (m.group(1) != null ? m.group(1) : m.group(2)))
                .getBytes(StandardCharsets.ISO_8859_1);
        Map<String, String> fields = new LinkedHashMap<>();
        List<FilePart> files = new ArrayList<>();
        int at = indexOf(body, delimiter, 0);
        if (at < 0) {
            return Optional.empty();
        }
        while (true) {
            int afterDelimiter = at + delimiter.length;
            if (afterDelimiter + 1 < body.length && body[afterDelimiter] == '-' && body[afterDelimiter + 1] == '-') {
                break;
            }
            int headersStart = afterDelimiter + 2;
            int headersEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), headersStart);
            if (headersEnd < 0) {
                return Optional.empty();
            }
            int next = indexOf(body, delimiter, headersEnd + 4);
            if (next < 0) {
                return Optional.empty();
            }
            String headers = new String(body, headersStart, headersEnd - headersStart, StandardCharsets.UTF_8);
            int dataEnd = next - 2;
            byte[] data = new byte[Math.max(0, dataEnd - (headersEnd + 4))];
            System.arraycopy(body, headersEnd + 4, data, 0, data.length);
            Matcher name = NAME.matcher(headers);
            if (name.find()) {
                Matcher file = FILENAME.matcher(headers);
                if (file.find()) {
                    files.add(new FilePart(name.group(1), file.group(1), data));
                } else {
                    fields.put(name.group(1), new String(data, StandardCharsets.UTF_8));
                }
            }
            at = next;
        }
        return Optional.of(new Form(fields, files));
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
