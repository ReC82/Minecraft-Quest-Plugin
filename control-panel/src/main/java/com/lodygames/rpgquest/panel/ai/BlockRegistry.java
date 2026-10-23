package com.lodygames.rpgquest.panel.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Le registre des blocs de la version servie, côté panel (#747).
 *
 * <p>C'est le <strong>même fichier</strong> que celui du plugin ({@code building/blocks-1.21.11.txt},
 * généré depuis le rapport du serveur vanilla par {@code scripts/building/gen-block-catalog.py}),
 * copié dans le classpath du panel au build. Il sert à deux choses : donner à l'IA une palette
 * d'identifiants dont l'orthographe est garantie, et relever d'un coup tous les identifiants
 * inventés d'un plan. Le serveur reste juge : ce registre ne remplace aucun de ses contrôles.</p>
 */
public final class BlockRegistry {

    /** La version de Minecraft servie — celle du plugin ({@code BlockCatalog.VERSION}). */
    public static final String VERSION = "1.21.11";

    private static final Pattern ID = Pattern.compile("minecraft:[a-z0-9_]+");

    private static volatile BlockRegistry vanilla;

    private final Set<String> ids;

    BlockRegistry(Set<String> ids) {
        this.ids = Collections.unmodifiableSet(new LinkedHashSet<>(ids));
    }

    /** Le registre embarqué ; vide (donc muet) s'il manque au classpath, jamais une exception. */
    public static BlockRegistry vanilla() {
        BlockRegistry r = vanilla;
        if (r == null) {
            synchronized (BlockRegistry.class) {
                r = vanilla;
                if (r == null) {
                    r = load("/building/blocks-" + VERSION + ".txt");
                    vanilla = r;
                }
            }
        }
        return r;
    }

    static BlockRegistry load(String resource) {
        Set<String> out = new LinkedHashSet<>();
        try (InputStream in = BlockRegistry.class.getResourceAsStream(resource)) {
            if (in == null) {
                return new BlockRegistry(Set.of());
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int bar = line.indexOf('|');
                out.add("minecraft:" + (bar < 0 ? line : line.substring(0, bar)).trim());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new BlockRegistry(out);
    }

    public boolean available() {
        return !ids.isEmpty();
    }

    /** L'identifiant existe-t-il ? Accepte « minecraft:x », « x » et un état « x[...] ». */
    public boolean known(String id) {
        if (id == null) {
            return false;
        }
        String bare = id.trim();
        int bracket = bare.indexOf('[');
        if (bracket >= 0) {
            bare = bare.substring(0, bracket);
        }
        return ids.contains(bare.startsWith("minecraft:") ? bare : "minecraft:" + bare);
    }

    /** Les identifiants (« minecraft:x ») dont le nom complet répond au motif, dans l'ordre du registre. */
    public List<String> matching(Pattern pattern) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            if (pattern.matcher(id).matches()) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * Tous les identifiants d'un plan absents du registre, chacun avec les lignes qui l'emploient.
     * Muet si le registre n'est pas chargé : mieux vaut ne rien dire que tout déclarer inconnu.
     */
    public Map<String, List<Integer>> unknownIn(String planYaml) {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        if (!available() || planYaml == null) {
            return out;
        }
        String[] lines = planYaml.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = ID.matcher(lines[i]);
            while (m.find()) {
                if (!ids.contains(m.group())) {
                    out.computeIfAbsent(m.group(), k -> new ArrayList<>()).add(i + 1);
                }
            }
        }
        return out;
    }
}
