package com.lodygames.rpgquest.dialogue;

import com.lodygames.rpgquest.dialogue.model.DialogueNode;
import com.lodygames.rpgquest.quest.model.LocalizedText;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.entity.Player;

/**
 * Substitution de valeurs <strong>dynamiques</strong> dans le texte d'un nœud de dialogue, au
 * moment où il est présenté au joueur (issue #24) : un administrateur écrit {@code
 * %wild_conditions%} dans le YAML, le moteur le remplace par l'état réel lu juste avant le rendu.
 * Même convention que {@code %player%} de {@code RUN_SAFE_COMMAND} — jamais une balise MiniMessage,
 * qui serait soit validée soit affichée littéralement par le parseur.
 *
 * <ul>
 *   <li>Seules les clés <em>enregistrées</em> sont remplacées ; un {@code %inconnu%} est laissé tel
 *       quel (une faute de frappe reste visible en jeu plutôt que d'effacer silencieusement du
 *       texte).</li>
 *   <li>Une seule passe ({@link Matcher#appendReplacement}) : une valeur qui contiendrait elle-même
 *       un {@code %...%} n'est jamais re-substituée, aucune récursion possible.</li>
 *   <li>Toutes les langues de {@link LocalizedText} sont traitées, pas seulement {@code default}.</li>
 *   <li>Un nœud sans aucun {@code %} est renvoyé <em>tel quel</em> (même instance) : coût nul pour
 *       l'écrasante majorité des dialogues.</li>
 * </ul>
 */
public final class DialogueTextPlaceholders {

    private static final Pattern TOKEN = Pattern.compile("%([a-z0-9_]+)%");

    private final Map<String, Function<Player, String>> values;

    public DialogueTextPlaceholders(Map<String, Function<Player, String>> values) {
        this.values = Map.copyOf(values);
    }

    /** Aucune substitution : comportement historique, utilisé tant que rien n'est câblé. */
    public static DialogueTextPlaceholders none() {
        return new DialogueTextPlaceholders(Map.of());
    }

    /**
     * Renvoie {@code node} enrichi de ses valeurs dynamiques, ou {@code node} lui-même si rien n'est
     * à substituer. Seul le <em>texte</em> du nœud est concerné : les libellés de choix restent
     * statiques (le moteur les indexe par position, un libellé dynamique n'apporterait rien ici).
     */
    public DialogueNode apply(Player player, DialogueNode node) {
        if (values.isEmpty()) {
            return node;
        }
        LocalizedText text = node.text();
        Map<String, String> substituted = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<String, String> entry : text.byLocale().entrySet()) {
            String resolved = apply(player, entry.getValue());
            changed |= !resolved.equals(entry.getValue());
            substituted.put(entry.getKey(), resolved);
        }
        return changed
                ? new DialogueNode(node.id(), node.speaker(), new LocalizedText(substituted), node.choices())
                : node;
    }

    /** Substitution sur un texte brut — exposée pour les tests et une réutilisation future. */
    public String apply(Player player, String raw) {
        if (raw == null || raw.indexOf('%') < 0) {
            return raw;
        }
        Matcher matcher = TOKEN.matcher(raw);
        StringBuilder out = new StringBuilder(raw.length());
        while (matcher.find()) {
            Function<Player, String> value = values.get(matcher.group(1));
            matcher.appendReplacement(out, value == null
                    ? Matcher.quoteReplacement(matcher.group())
                    : Matcher.quoteReplacement(value.apply(player)));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
