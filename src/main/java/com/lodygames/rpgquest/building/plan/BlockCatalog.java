package com.lodygames.rpgquest.building.plan;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Le registre des blocs de la version servie : identifiants, propriétés et valeurs admises
 * (issues #288–#292).
 *
 * <h2>La faute qu'il supprime</h2>
 *
 * <p>Le plan de la Maison de Lily a passé l'audit — 780 blocs, « conforme » — puis l'écriture du
 * schematic a échoué sur {@code minecraft:bed} : ce bloc n'existe pas, seuls les lits colorés
 * existent ({@code minecraft:pink_bed}). Le plan contenait aussi {@code minecraft:stained_glass_pane},
 * tout aussi inexistant, qui aurait fait échouer l'essai suivant. {@link BlockState} ne contrôlait
 * que la <em>forme</em> du texte ; le registre vivait dans le serveur, consulté seulement au moment
 * d'écrire. Le verdict « conforme » était donc trompeur.</p>
 *
 * <h2>D'où vient la table</h2>
 *
 * <p>Du rapport que le serveur vanilla produit lui-même ({@code --reports}, {@code blocks.json}),
 * converti par {@code scripts/building/gen-block-catalog.py}. Rien n'est écrit à la main. WorldEdit,
 * sur Paper, construit son registre depuis celui du serveur : mêmes identifiants, mêmes propriétés.
 * Cette classe est pure — elle sert aux tests et à l'audit sans serveur — et le contrôle par le
 * vrai parseur WorldEdit reste fait, en plus, par {@code SchematicGateway#rejected} quand le plugin
 * tourne.</p>
 *
 * <p>Un bloc inconnu n'est <strong>jamais</strong> remplacé par un autre : la couleur d'un lit est
 * une intention, pas un détail technique. Le refus propose les identifiants réels les plus proches.</p>
 */
public final class BlockCatalog {

    /** Version de Minecraft dont la table est extraite — celle du serveur DEV (Paper 1.21.11). */
    public static final String VERSION = "1.21.11";

    private static final String RESOURCE = "/building/blocks-" + VERSION + ".txt";

    private static volatile BlockCatalog vanilla;

    /** Un bloc : ses propriétés (dans l'ordre du registre) et leurs valeurs admises. */
    public record Entry(String id, Map<String, List<String>> properties, Map<String, String> defaults) {
    }

    private final Map<String, Entry> blocks;

    private BlockCatalog(Map<String, Entry> blocks) {
        this.blocks = Collections.unmodifiableMap(blocks);
    }

    /** Le registre de la version servie, lu une fois depuis le JAR. */
    public static BlockCatalog vanilla() {
        BlockCatalog current = vanilla;
        if (current == null) {
            synchronized (BlockCatalog.class) {
                current = vanilla;
                if (current == null) {
                    current = load();
                    vanilla = current;
                }
            }
        }
        return current;
    }

    private static BlockCatalog load() {
        Map<String, Entry> blocks = new LinkedHashMap<>();
        try (InputStream in = BlockCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Registre des blocs absent du JAR : " + RESOURCE);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\|", -1);
                Map<String, List<String>> properties = new LinkedHashMap<>();
                if (!parts[1].isEmpty()) {
                    for (String property : parts[1].split(";")) {
                        int eq = property.indexOf('=');
                        properties.put(property.substring(0, eq),
                                List.of(property.substring(eq + 1).split(",")));
                    }
                }
                Map<String, String> defaults = new LinkedHashMap<>();
                if (parts.length > 2 && !parts[2].isEmpty()) {
                    for (String pair : parts[2].split(",")) {
                        int eq = pair.indexOf('=');
                        defaults.put(pair.substring(0, eq), pair.substring(eq + 1));
                    }
                }
                blocks.put(parts[0], new Entry(parts[0], Map.copyOf(properties), Map.copyOf(defaults)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Registre des blocs illisible : " + RESOURCE, e);
        }
        return new BlockCatalog(blocks);
    }

    /** Le nombre de blocs connus. */
    public int size() {
        return blocks.size();
    }

    /** Le bloc, désigné avec ou sans {@code minecraft:} et avec ou sans propriétés. */
    public Optional<Entry> find(String state) {
        return Optional.ofNullable(blocks.get(BlockState.type(state).toLowerCase(Locale.ROOT)));
    }

    public boolean known(String state) {
        return find(state).isPresent();
    }

    /** Ce bloc possède-t-il cette propriété ? Faux pour un bloc inconnu. */
    public boolean hasProperty(String state, String property) {
        return find(state).map(e -> e.properties().containsKey(property)).orElse(false);
    }

    /**
     * Le motif du refus de cet état, rédigé pour le modèle et pour l'écran ; vide s'il est valide.
     * La forme est supposée déjà contrôlée par {@link BlockState#valid}.
     */
    public Optional<String> refusal(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        String value = state.trim();
        Optional<Entry> entry = find(value);
        String type = BlockState.type(value);
        if (entry.isEmpty()) {
            return Optional.of("le bloc « minecraft:" + type + " » n'existe pas en Minecraft "
                    + VERSION + suggestion(type));
        }
        int bracket = value.indexOf('[');
        if (bracket < 0 || !value.endsWith("]")) {
            return Optional.empty();
        }
        Map<String, List<String>> properties = entry.get().properties();
        for (String pair : value.substring(bracket + 1, value.length() - 1).split(",", -1)) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = pair.substring(0, eq);
            String val = pair.substring(eq + 1);
            List<String> allowed = properties.get(key);
            if (allowed == null) {
                return Optional.of("la propriété « " + key + " » n'existe pas pour « minecraft:"
                        + type + " » (" + (properties.isEmpty() ? "ce bloc n'a aucune propriété"
                        : "propriétés : " + String.join(", ", properties.keySet())) + ")");
            }
            if (!allowed.contains(val)) {
                return Optional.of("« " + key + "=" + val + " » est invalide pour « minecraft:" + type
                        + " » (valeurs : " + String.join(", ", allowed) + ")");
            }
        }
        return Optional.empty();
    }

    /**
     * Les identifiants réels les plus proches. Les blocs qui n'existent qu'en couleurs — lits,
     * vitres teintées, laine, tapis, bougies — sont reconnus comme tels : « minecraft:bed » n'a pas
     * de voisin orthographique, il a seize variantes.
     */
    String suggestion(String type) {
        List<String> colored = blocks.keySet().stream()
                .filter(id -> id.endsWith("_" + type) && COLORS.contains(id.substring(0, id.length()
                        - type.length() - 1)))
                .toList();
        if (colored.size() >= 4) {
            return " : il n'existe qu'en version colorée, « minecraft:<couleur>_" + type + " » — par "
                    + "exemple minecraft:" + colored.stream().filter(id -> id.startsWith("white_"))
                    .findFirst().orElse(colored.get(0)) + " ou minecraft:pink_" + type
                    + ". Couleurs : " + String.join(", ", COLORS) + ". Choisis la couleur demandée "
                    + "par le brief : le moteur ne la devine pas.";
        }
        List<String> close = new ArrayList<>(blocks.keySet().stream()
                .filter(id -> id.contains(type) || type.contains(id) || distance(id, type) <= 2)
                .toList());
        if (close.isEmpty()) {
            return ". Utilise un identifiant du registre vanilla, sans l'inventer.";
        }
        close.sort(Comparator.comparingInt((String id) -> distance(id, type)).thenComparing(id -> id));
        return ". Identifiants proches : " + String.join(", ", close.subList(0, Math.min(6, close.size()))
                .stream().map(id -> "minecraft:" + id).toList()) + ".";
    }

    /** Les seize couleurs, dans l'ordre du jeu. */
    public static final List<String> COLORS = List.of("white", "orange", "magenta", "light_blue",
            "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown",
            "green", "red", "black");

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }
}
