package be.lloyd.rpgquest.webapi.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Codec JSON minimal, sans dépendance externe (module {@code web-api}
 * complètement indépendant du plugin Paper, voir docs/WEB_API.md). Les
 * structures échangées sont toujours des types génériques : {@link Map}
 * (objet, clés {@link String}), {@link List} (tableau), {@link String},
 * {@link Long}/{@link Double} (nombre), {@link Boolean}, {@code null}.
 *
 * <p>{@link #parse(String)} relit le snapshot écrit par le plugin
 * ({@code be.lloyd.rpgquest.web.JsonWriter}, un sérialiseur indépendant côté
 * plugin) ; {@link #write(Object)} construit les réponses de l'API et les
 * pages du site.</p>
 */
public final class Json {

    private Json() {
    }

    // ---- Écriture --------------------------------------------------------------------------

    public static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        writeValue(builder, value);
        return builder.toString();
    }

    private static void writeValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String text) {
            writeString(builder, text);
        } else if (value instanceof Boolean bool) {
            builder.append(bool);
        } else if (value instanceof Number number) {
            builder.append(number);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(builder, map);
        } else if (value instanceof Iterable<?> iterable) {
            writeArray(builder, iterable);
        } else {
            throw new IllegalArgumentException("Type non sérialisable en JSON : " + value.getClass().getName());
        }
    }

    private static void writeObject(StringBuilder builder, Map<?, ?> map) {
        builder.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            writeString(builder, String.valueOf(entry.getKey()));
            builder.append(':');
            writeValue(builder, entry.getValue());
        }
        builder.append('}');
    }

    private static void writeArray(StringBuilder builder, Iterable<?> iterable) {
        builder.append('[');
        boolean first = true;
        for (Object item : iterable) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            writeValue(builder, item);
        }
        builder.append(']');
    }

    private static void writeString(StringBuilder builder, String text) {
        builder.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
    }

    // ---- Lecture --------------------------------------------------------------------------

    /** Analyse un document JSON complet. Lève {@link JsonParseException} sur tout contenu invalide ou tronqué. */
    public static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonParseException("Contenu inattendu après la valeur JSON.");
        }
        return value;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        char peek() {
            if (atEnd()) {
                throw new JsonParseException("JSON tronqué (fin de document inattendue).");
            }
            return text.charAt(pos);
        }

        void expect(char expected) {
            if (atEnd() || text.charAt(pos) != expected) {
                throw new JsonParseException("Caractère « " + expected + " » attendu à la position " + pos + ".");
            }
            pos++;
        }

        Object parseValue() {
            skipWhitespace();
            char c = peek();
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (!atEnd() && peek() == '}') {
                pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                result.put(key, value);
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    pos++;
                } else if (next == '}') {
                    pos++;
                    break;
                } else {
                    throw new JsonParseException("« , » ou « } » attendu à la position " + pos + ".");
                }
            }
            return result;
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (!atEnd() && peek() == ']') {
                pos++;
                return result;
            }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    pos++;
                } else if (next == ']') {
                    pos++;
                    break;
                } else {
                    throw new JsonParseException("« , » ou « ] » attendu à la position " + pos + ".");
                }
            }
            return result;
        }

        String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (true) {
                char c = peek();
                pos++;
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    char escape = peek();
                    pos++;
                    switch (escape) {
                        case '"' -> builder.append('"');
                        case '\\' -> builder.append('\\');
                        case '/' -> builder.append('/');
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'u' -> {
                            if (pos + 4 > text.length()) {
                                throw new JsonParseException("Échappement \\u tronqué à la position " + pos + ".");
                            }
                            String hex = text.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                builder.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                throw new JsonParseException("Échappement \\u invalide : " + hex);
                            }
                        }
                        default -> throw new JsonParseException("Échappement inconnu : \\" + escape);
                    }
                } else if (c < 0x20) {
                    throw new JsonParseException("Caractère de contrôle non échappé dans une chaîne.");
                } else {
                    builder.append(c);
                }
            }
            return builder.toString();
        }

        Boolean parseBoolean() {
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new JsonParseException("Littéral booléen invalide à la position " + pos + ".");
        }

        Object parseNull() {
            if (text.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new JsonParseException("Littéral invalide à la position " + pos + ".");
        }

        Number parseNumber() {
            int start = pos;
            if (!atEnd() && text.charAt(pos) == '-') {
                pos++;
            }
            boolean isFloating = false;
            while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (!atEnd() && text.charAt(pos) == '.') {
                isFloating = true;
                pos++;
                while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            if (!atEnd() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                isFloating = true;
                pos++;
                if (!atEnd() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            if (pos == start) {
                throw new JsonParseException("Valeur JSON invalide à la position " + pos + ".");
            }
            String raw = text.substring(start, pos);
            try {
                // Deux « return » distincts, jamais un ternaire double/long : JLS 15.25 (promotion
                // numérique binaire) transformerait silencieusement la branche long en double.
                if (isFloating) {
                    return Double.parseDouble(raw);
                }
                return Long.parseLong(raw);
            } catch (NumberFormatException e) {
                throw new JsonParseException("Nombre invalide : " + raw);
            }
        }
    }
}
