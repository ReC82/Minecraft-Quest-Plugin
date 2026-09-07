package com.lodygames.rpgquest.panel.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Codec JSON minimal, sans dépendance externe. Structures génériques : {@link Map} (objet, clés
 * {@link String}), {@link List} (tableau), {@link String}, {@link Long}/{@link Double} (nombre),
 * {@link Boolean}, {@code null}.
 *
 * <p>{@link #parse(String)} lit les réponses du bridge d'administration du plugin ; {@link
 * #write(Object)} sert aux quelques réponses JSON du Control Panel lui-même. Volontairement
 * autonome (pas de module partagé) — même décision d'isolation que {@code web-api} et le plugin.</p>
 */
public final class Json {

    private Json() {
    }

    // ---- Écriture --------------------------------------------------------------------------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> writeString(sb, s);
            case Boolean b -> sb.append(b);
            case Number n -> sb.append(n);
            case Map<?, ?> map -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeString(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    writeValue(sb, e.getValue());
                }
                sb.append('}');
            }
            case Iterable<?> it -> {
                sb.append('[');
                boolean first = true;
                for (Object o : it) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeValue(sb, o);
                }
                sb.append(']');
            }
            default -> throw new IllegalArgumentException("Type non sérialisable : " + value.getClass());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---- Lecture ---------------------------------------------------------------------------

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new JsonParseException("Contenu inattendu après la valeur JSON à l'index " + p.pos);
        }
        return value;
    }

    /** Raccourci : {@code parse} en attendant un objet. Lève {@link JsonParseException} si ce n'en est pas un. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map<?, ?> map)) {
            throw new JsonParseException("Objet JSON attendu, reçu : " + (value == null ? "null" : value.getClass().getSimpleName()));
        }
        return (Map<String, Object>) map;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        Object readValue() {
            if (atEnd()) {
                throw new JsonParseException("Fin de contenu prématurée");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, readValue());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new JsonParseException("',' ou '}' attendu à l'index " + (pos - 1));
                }
            }
        }

        List<Object> readArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(readValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new JsonParseException("',' ou ']' attendu à l'index " + (pos - 1));
                }
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonParseException("Chaîne non terminée");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new JsonParseException("Échappement invalide \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Boolean readBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new JsonParseException("Booléen invalide à l'index " + pos);
        }

        Object readNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new JsonParseException("'null' attendu à l'index " + pos);
        }

        Number readNumber() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            String num = s.substring(start, pos);
            if (num.isEmpty()) {
                throw new JsonParseException("Nombre attendu à l'index " + start);
            }
            if (num.contains(".") || num.contains("e") || num.contains("E")) {
                return Double.parseDouble(num);
            }
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                return Double.parseDouble(num);
            }
        }

        private char peek() {
            if (atEnd()) {
                throw new JsonParseException("Fin de contenu prématurée");
            }
            return s.charAt(pos);
        }

        private char next() {
            if (atEnd()) {
                throw new JsonParseException("Fin de contenu prématurée");
            }
            return s.charAt(pos++);
        }

        private void expect(char c) {
            if (next() != c) {
                throw new JsonParseException("'" + c + "' attendu à l'index " + (pos - 1));
            }
        }
    }
}
