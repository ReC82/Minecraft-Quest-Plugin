package com.lodygames.rpgquest.building.plan;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Contrôle de forme d'un état de bloc écrit par une IA (issue #94).
 *
 * <h2>Pourquoi un contrôle de forme, et ce qu'il ne prétend pas faire</h2>
 *
 * <p>Un état de bloc traverse le plan en <strong>texte</strong>
 * ({@code minecraft:oak_stairs[facing=north,half=bottom]}), puis il est confié à l'analyseur de
 * WorldEdit. Cette classe garantit une seule chose, mais elle la garantit : que la chaîne a bien
 * la <em>forme</em> d'un état de bloc, et rien d'autre. Pas de guillemet, pas d'espace, pas de
 * point-virgule, pas de barre oblique, pas de retour à la ligne — donc rien qui ressemble à une
 * commande, un chemin, ou une tentative d'injection dans ce qui lira la chaîne ensuite.</p>
 *
 * <p>Elle ne dit <strong>pas</strong> que le bloc existe. La liste des blocs d'une version de
 * Minecraft n'est pas connue ici : cette classe est pure, et le registre vit dans le serveur. Un
 * nom inventé par l'IA ({@code minecraft:elven_marble}) passe donc ce contrôle et sera refusé plus
 * loin, par le moteur — ce qui est le bon endroit, parce que lui seul sait. L'important est que ce
 * refus soit <em>visible</em> et non un bloc silencieusement remplacé par de l'air.</p>
 */
public final class BlockState {

    private BlockState() {
    }

    /** La règle, en français, pour l'afficher à côté du refus. */
    public static final String RULE = "Forme attendue : « minecraft:nom » ou "
            + "« minecraft:nom[propriete=valeur,...] », en minuscules, sans espace.";

    /** Longueur maximale — un état réel dépasse rarement 80 caractères. */
    private static final int MAX_LENGTH = 160;

    private static final Pattern TYPE = Pattern.compile("(minecraft:)?[a-z0-9_]{1,64}");
    private static final Pattern PROPERTY =
            Pattern.compile("[a-z0-9_]{1,32}=[a-z0-9_\\-]{1,32}");

    /**
     * La chaîne a-t-elle la forme d'un état de bloc ?
     *
     * <p>Le namespace est optionnel mais, s'il est présent, il ne peut être que
     * {@code minecraft:} : accepter un namespace arbitraire ouvrirait la porte aux blocs d'un mod,
     * alors que le ticket exige du <strong>vanilla</strong>.</p>
     */
    public static boolean valid(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            return false;
        }
        if (!value.equals(value.toLowerCase(Locale.ROOT))) {
            return false;
        }
        int bracket = value.indexOf('[');
        if (bracket < 0) {
            return TYPE.matcher(value).matches();
        }
        if (!value.endsWith("]")) {
            return false;
        }
        if (!TYPE.matcher(value.substring(0, bracket)).matches()) {
            return false;
        }
        String inside = value.substring(bracket + 1, value.length() - 1);
        if (inside.isEmpty()) {
            return false;
        }
        for (String property : inside.split(",", -1)) {
            if (!PROPERTY.matcher(property).matches()) {
                return false;
            }
        }
        return true;
    }

    /** Le type seul, sans namespace ni propriétés — pour la liste des matériaux. */
    public static String type(String raw) {
        String value = raw == null ? "" : raw.trim();
        int bracket = value.indexOf('[');
        String type = bracket < 0 ? value : value.substring(0, bracket);
        int colon = type.indexOf(':');
        return colon < 0 ? type : type.substring(colon + 1);
    }

    /**
     * Le même état, avec une propriété remplacée ou ajoutée.
     *
     * <p>Sert à l'expansion d'un toit : l'IA dit « marches en chêne », et le générateur décide
     * {@code facing=north} ou {@code facing=south} selon le versant. C'est exactement le détail
     * qu'un modèle de langue se trompe à faire à la main.</p>
     */
    public static String with(String raw, String property, String value) {
        String state = raw == null ? "" : raw.trim();
        int bracket = state.indexOf('[');
        if (bracket < 0) {
            return state + "[" + property + "=" + value + "]";
        }
        String type = state.substring(0, bracket);
        String inside = state.substring(bracket + 1, state.length() - 1);
        java.util.LinkedHashMap<String, String> properties = new java.util.LinkedHashMap<>();
        for (String pair : inside.split(",", -1)) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                properties.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        properties.put(property, value);
        StringBuilder sb = new StringBuilder(type).append('[');
        boolean first = true;
        for (var entry : properties.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }
}
