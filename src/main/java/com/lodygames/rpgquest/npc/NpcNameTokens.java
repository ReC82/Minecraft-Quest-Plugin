package com.lodygames.rpgquest.npc;

import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Issue #475 — un PNJ cité dans un texte par son <strong>identifiant stable</strong>, jamais par son
 * nom écrit en dur : {@code %npc_name:guide%} rend le nom affiché <em>actuel</em> du PNJ
 * ({@code display_name} de {@code npcs/guide.yml}, balises retirées). Renommer le Guide en « Elias »
 * dans le Control Panel change donc aussitôt les descriptions de quête et les dialogues qui le
 * citent ainsi, sans republier ces contenus — même règle que les objectifs (#70) et que
 * {@code %city_name:…%} pour les villes (#180).
 *
 * <p>Le nom est inséré <em>échappé</em> dans un texte MiniMessage : un nom contenant {@code <}
 * reste du texte. PNJ inconnu ou sans nom : le jeton reste visible tel quel, jamais un nom inventé
 * ni un texte effacé en silence.</p>
 */
public final class NpcNameTokens {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern TOKEN = Pattern.compile("%npc_name:([a-z0-9_.-]+)%");

    private NpcNameTokens() {
    }

    /** Nom affiché de {@code npcId}, prêt à insérer dans du MiniMessage, ou {@code null} s'il est inconnu. */
    public static String escapedName(String npcId, Function<String, Optional<String>> names) {
        if (npcId == null || npcId.isBlank() || names == null) {
            return null;
        }
        return names.apply(npcId).filter(name -> !name.isBlank()).map(MM::escapeTags).orElse(null);
    }

    /** {@code raw} avec chaque {@code %npc_name:<id>%} connu remplacé ; {@code raw} lui-même s'il n'y en a aucun. */
    public static String resolve(String raw, Function<String, Optional<String>> names) {
        if (raw == null || !raw.contains("%npc_name:")) {
            return raw;
        }
        Matcher matcher = TOKEN.matcher(raw);
        StringBuilder out = new StringBuilder(raw.length());
        while (matcher.find()) {
            String name = escapedName(matcher.group(1), names);
            matcher.appendReplacement(out, Matcher.quoteReplacement(name == null ? matcher.group() : name));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
