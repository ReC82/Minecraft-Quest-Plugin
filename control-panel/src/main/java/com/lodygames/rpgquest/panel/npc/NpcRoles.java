package com.lodygames.rpgquest.panel.npc;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Issue #127 — le rôle métier d'un PNJ : un <strong>libellé affiché</strong>, pas une capacité.
 *
 * <p>Le champ {@code role} d'une définition PNJ ({@code npcs/*.yml}) n'a aucun effet en jeu : ce
 * que fait un PNJ (proposer des quêtes, parler, plus tard vendre) se règle ailleurs — une quête
 * désigne son donneur, un PNJ déclare son dialogue. Le rôle dit seulement « qui il est » :
 * Cartographe, Forgeron, Aubergiste… La liste n'est donc pas fermée : l'administrateur tape ce
 * qu'il veut, et ce registre ne fournit que des <em>suggestions</em> et leur libellé accentué.</p>
 *
 * <p>Le format stocké ne change pas (compatibilité des définitions existantes) : un identifiant
 * {@code [a-z0-9_-]{1,32}}, celui que le plugin valide au chargement. {@link #normalize} convertit
 * une saisie libre (« Téléporteur », « Maître d'armes ») vers cet identifiant ; {@link #label}
 * fait le chemin inverse pour l'affichage. Un identifiant inconnu s'affiche sous sa forme lisible.</p>
 *
 * <p>{@code quest_giver} reste reconnu : c'est l'ancienne valeur proposée par le formulaire. Il
 * s'affiche comme les autres rôles et ne donne pas plus de pouvoir qu'eux.</p>
 *
 * <p>Classe pure : aucun accès disque ni réseau.</p>
 */
public final class NpcRoles {

    /** Même règle que le plugin ({@code NpcDefinitionParser}) : le stockage ne bouge pas. */
    public static final Pattern ROLE_ID = Pattern.compile("[a-z0-9_-]{1,32}");

    public static final int MAX_LENGTH = 32;

    /** Une suggestion : identifiant stocké et libellé affiché. */
    public record Role(String id, String label) {
    }

    private static final Map<String, String> SUGGESTED = new LinkedHashMap<>();

    static {
        SUGGESTED.put("aubergiste", "Aubergiste");
        SUGGESTED.put("archiviste", "Archiviste");
        SUGGESTED.put("artisan", "Artisan");
        SUGGESTED.put("cartographe", "Cartographe");
        SUGGESTED.put("chasseur", "Chasseur");
        SUGGESTED.put("cuisinier", "Cuisinier");
        SUGGESTED.put("eclaireur", "Éclaireur");
        SUGGESTED.put("fermier", "Fermier");
        SUGGESTED.put("forgeron", "Forgeron");
        SUGGESTED.put("garde", "Garde");
        SUGGESTED.put("guerisseur", "Guérisseur");
        SUGGESTED.put("marchand", "Marchand");
        SUGGESTED.put("mineur", "Mineur");
        SUGGESTED.put("pecheur", "Pêcheur");
        SUGGESTED.put("teleporteur", "Téléporteur");
        SUGGESTED.put("voyageur", "Voyageur");
        SUGGESTED.put("quest_giver", "Donneur de quête");
    }

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SEPARATORS = Pattern.compile("[^a-z0-9_-]+");

    private NpcRoles() {
    }

    /** Les rôles proposés au formulaire, dans l'ordre d'affichage. */
    public static List<Role> suggestions() {
        return SUGGESTED.entrySet().stream().map(e -> new Role(e.getKey(), e.getValue())).toList();
    }

    /** Libellé affiché d'un identifiant stocké ; vide si aucun rôle. */
    public static String label(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        String key = id.trim().toLowerCase(Locale.ROOT);
        String known = SUGGESTED.get(key);
        if (known != null) {
            return known;
        }
        StringBuilder sb = new StringBuilder();
        for (String part : key.replace('-', '_').split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(sb.isEmpty() ? Character.toUpperCase(part.charAt(0)) + part.substring(1) : part);
        }
        return sb.toString();
    }

    /**
     * Saisie libre → identifiant stocké. Vide → {@code Optional.of("")} (aucun rôle). Un libellé
     * suggéré (« Téléporteur ») donne son identifiant ; sinon accents retirés, minuscules, tout
     * séparateur ramené à {@code _}. Vide après nettoyage ou plus de {@value #MAX_LENGTH}
     * caractères → {@code Optional.empty()} : la saisie est refusée, jamais tronquée en silence.
     */
    public static Optional<String> normalize(String input) {
        if (input == null || input.isBlank()) {
            return Optional.of("");
        }
        for (Map.Entry<String, String> e : SUGGESTED.entrySet()) {
            if (e.getValue().equalsIgnoreCase(input.trim())) {
                return Optional.of(e.getKey());
            }
        }
        String folded = MARKS.matcher(Normalizer.normalize(input.trim(), Normalizer.Form.NFD)).replaceAll("")
                .toLowerCase(Locale.ROOT);
        String slug = SEPARATORS.matcher(folded).replaceAll("_").replaceAll("_+", "_");
        slug = slug.replaceAll("^[_-]+|[_-]+$", "");
        if (slug.isEmpty() || !ROLE_ID.matcher(slug).matches()) {
            return Optional.empty();
        }
        return Optional.of(slug);
    }
}
