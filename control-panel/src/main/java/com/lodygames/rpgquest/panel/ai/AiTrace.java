package com.lodygames.rpgquest.panel.ai;

import com.lodygames.rpgquest.panel.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mise en forme <strong>fidèle et expurgée</strong> d'un échange avec un fournisseur d'IA
 * (issue #739).
 *
 * <p>Un corps JSON de requête est illisible tel quel : les consignes et la demande y sont des
 * chaînes de dizaines de milliers de caractères échappées sur une seule ligne. On les sort donc du
 * corps, intactes, avec leur <em>rôle</em> (system, user, assistant…) et leur chemin JSON ; le corps
 * restant garde tous les paramètres réellement transmis, avec un renvoi à la place de chaque texte.
 * Rien n'est résumé ni tronqué.</p>
 *
 * <p>Ce qui est retiré, et toujours signalé dans la liste des masquages :</p>
 * <ul>
 *   <li>la clé API, où qu'elle apparaisse, et tout ce qui ressemble à une clé connue
 *       ({@code sk-…}, {@code AIza…}, {@code Bearer …}) ;</li>
 *   <li>les en-têtes d'authentification et les cookies (le nom reste, la valeur est masquée) ;</li>
 *   <li>les pixels des images de référence : leur type, leur taille et leur empreinte SHA-256
 *       restent, le base64 n'est pas recopié — il noierait le fichier sans rien apprendre.</li>
 * </ul>
 */
public final class AiTrace {

    /** Un texte sorti d'un corps JSON : où il était, quel rôle le portait, et son contenu entier. */
    public record Text(String ref, String path, String role, String content) {

        public Text {
            ref = ref == null ? "" : ref;
            path = path == null ? "" : path;
            role = role == null ? "" : role;
            content = content == null ? "" : content;
        }
    }

    /** Un corps mis en forme : les paramètres (squelette) et les textes sortis. */
    public record Split(String skeleton, List<Text> texts) {
    }

    /** Au-delà de cette longueur (ou avec un saut de ligne), une chaîne est sortie du squelette. */
    static final int INLINE_LIMIT = 160;

    public static final String MASK = "⟦MASQUÉ⟧";

    private static final Set<String> SENSITIVE_HEADERS = Set.of("authorization", "proxy-authorization",
            "x-api-key", "x-goog-api-key", "api-key", "cookie", "set-cookie");

    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("sk-[A-Za-z0-9_\\-]{10,}"),
            Pattern.compile("AIza[0-9A-Za-z_\\-]{20,}"),
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=\\-]{8,}"),
            Pattern.compile("(?i)([?&](?:key|api_key|apikey|token)=)[^&\\s\"]+"));

    private AiTrace() {
    }

    /**
     * Masque la clé et tout motif de secret connu dans un texte. Chaque masquage est ajouté à
     * {@code notes}, avec ce qui a été masqué — jamais la valeur.
     */
    public static String mask(String text, String apiKey, String where, List<String> notes) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String out = text;
        if (apiKey != null && apiKey.length() >= 6 && out.contains(apiKey)) {
            int n = count(out, apiKey);
            out = out.replace(apiKey, MASK);
            notes.add(where + " : clé API masquée (" + n + " occurrence" + (n > 1 ? "s" : "") + ").");
        }
        for (Pattern pattern : SECRET_PATTERNS) {
            Matcher m = pattern.matcher(out);
            int n = 0;
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                n++;
                String replacement = m.groupCount() >= 1 && m.group(1) != null ? m.group(1) + MASK : MASK;
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            if (n > 0) {
                m.appendTail(sb);
                out = sb.toString();
                notes.add(where + " : " + n + " valeur(s) ressemblant à un secret masquée(s) (motif "
                        + describe(pattern) + ").");
            }
        }
        return out;
    }

    /** Les en-têtes envoyés, valeurs d'authentification masquées. */
    public static Map<String, String> headers(Map<String, String> sent, String apiKey, List<String> notes) {
        Map<String, String> out = new LinkedHashMap<>();
        sent.forEach((name, value) -> {
            String v = value == null ? "" : value;
            boolean sensitive = SENSITIVE_HEADERS.contains(name.toLowerCase(Locale.ROOT))
                    || apiKey != null && apiKey.length() >= 6 && v.contains(apiKey);
            if (sensitive) {
                out.put(name, MASK);
                notes.add("En-tête « " + name + " » : valeur masquée (authentification).");
            } else {
                out.put(name, mask(v, apiKey, "En-tête « " + name + " »", notes));
            }
        });
        return out;
    }

    /**
     * Sort les longs textes d'un corps JSON, remplace les pixels des images, masque les secrets.
     *
     * @param prefix préfixe des renvois ({@code Q} pour la requête, {@code R} pour la réponse)
     * @param images images jointes à la requête : leur base64 est reconnu et remplacé
     */
    public static Split split(String body, String prefix, List<AiProvider.Image> images, String apiKey,
                              String where, List<String> notes) {
        String raw = body == null ? "" : body;
        Object tree;
        try {
            tree = Json.parse(raw);
        } catch (RuntimeException notJson) {
            tree = null;
        }
        List<Text> texts = new ArrayList<>();
        if (tree == null || !(tree instanceof Map<?, ?> || tree instanceof List<?>)) {
            // Corps non JSON (page d'erreur d'un mandataire, réponse coupée) : gardé en entier, comme texte.
            if (!raw.isEmpty()) {
                texts.add(new Text(prefix + "1", "(corps brut, non JSON)", "",
                        mask(raw, apiKey, where, notes)));
            }
            return new Split("", texts);
        }
        Map<String, String> pixels = new LinkedHashMap<>();
        int i = 0;
        for (AiProvider.Image image : images == null ? List.<AiProvider.Image>of() : images) {
            i++;
            byte[] data = image.data();
            pixels.put(image.base64(), "⟦image I" + i + " : " + image.mime() + ", " + data.length
                    + " octets, sha256 " + sha256(data).substring(0, 16) + " — pixels transmis, base64 non "
                    + "recopié⟧");
        }
        if (!pixels.isEmpty()) {
            notes.add(where + " : " + pixels.size() + " image(s) — pixels transmis au fournisseur, base64 "
                    + "remplacé dans le journal par type, taille et empreinte.");
        }
        Object skeleton = walk(tree, "", "", prefix, pixels, apiKey, where, notes, texts);
        return new Split(Json.writePretty(skeleton), texts);
    }

    @SuppressWarnings("unchecked")
    private static Object walk(Object node, String path, String role, String prefix, Map<String, String> pixels,
                               String apiKey, String where, List<String> notes, List<Text> texts) {
        if (node instanceof Map<?, ?> map) {
            Object ownRole = map.get("role");
            String r = ownRole instanceof String s && !s.isBlank() ? s : role;
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                // Hors de tout message, le nom de la clé dit le rôle : « system », « systemInstruction ».
                String childRole = r.isEmpty() && path.isEmpty() && key.toLowerCase(Locale.ROOT).startsWith("system")
                        ? key : r;
                out.put(key, walk(e.getValue(), path.isEmpty() ? key : path + "." + key, childRole, prefix,
                        pixels, apiKey, where, notes, texts));
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                out.add(walk(list.get(i), path + "[" + i + "]", role, prefix, pixels, apiKey, where, notes, texts));
            }
            return out;
        }
        if (node instanceof String s) {
            String pixel = pixels.get(s);
            if (pixel == null && s.startsWith("data:") && s.contains(";base64,")) {
                pixel = pixels.get(s.substring(s.indexOf(";base64,") + 8));
            }
            if (pixel != null) {
                return pixel;
            }
            String masked = mask(s, apiKey, where + " (" + path + ")", notes);
            if (masked.length() > INLINE_LIMIT || masked.indexOf('\n') >= 0) {
                String ref = prefix + (texts.size() + 1);
                texts.add(new Text(ref, path, role, masked));
                return "⟦texte " + ref + " : " + masked.length() + " caractères, reproduit en entier plus bas⟧";
            }
            return masked;
        }
        return node;
    }

    /** Empreinte SHA-256 hexadécimale. */
    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String sha256(String text) {
        return sha256((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    private static String describe(Pattern pattern) {
        String p = pattern.pattern();
        if (p.startsWith("sk-")) {
            return "« sk-… »";
        }
        if (p.startsWith("AIza")) {
            return "« AIza… »";
        }
        if (p.contains("bearer")) {
            return "« Bearer … »";
        }
        return "paramètre d'URL key/token";
    }
}
