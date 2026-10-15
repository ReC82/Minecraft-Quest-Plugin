package com.lodygames.rpgquest.npc;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Règle de {@code /rpgadmin npc tag <id>} (issue #66) : un id explicite doit figurer dans le
 * registre canonique ({@link NpcCatalogSources#canonicalIds}) — défini par un fichier
 * {@code npcs/*.yml} ou référencé par un dialogue ou une quête. Sinon il est refusé, avec la
 * suggestion la plus proche ({@code garde} → {@code guard}), sauf {@code --force} explicite pour
 * un id volontairement nouveau.
 *
 * <p>Pure (sans Bukkit), d'où les tests unitaires. L'id technique reste découplé du nom affiché :
 * rien ici ne lit le nom de l'entité.</p>
 */
public final class NpcTagIdPolicy {

    /** Drapeau de debug : accepte un id absent du registre canonique. */
    public static final String FORCE_FLAG = "--force";

    private NpcTagIdPolicy() {
    }

    /**
     * @param accepted   l'id peut être posé
     * @param known      l'id figure dans le registre canonique
     * @param suggestion id canonique proche quand l'id est inconnu, sinon {@code null}
     */
    public record Verdict(boolean accepted, boolean known, String suggestion) {
    }

    public static Verdict evaluate(String id, Collection<String> canonicalIds, boolean force) {
        Set<String> known = normalized(canonicalIds);
        String lower = id.toLowerCase(Locale.ROOT);
        if (known.contains(lower)) {
            return new Verdict(true, true, null);
        }
        return new Verdict(force, false, NpcCatalog.closestCanonical(lower, known));
    }

    /** Ids canoniques commençant par {@code prefix}, triés, pour la tab-complétion. */
    public static List<String> complete(String prefix, Collection<String> canonicalIds) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return normalized(canonicalIds).stream().filter(id -> id.startsWith(lower)).toList();
    }

    private static Set<String> normalized(Collection<String> ids) {
        Set<String> out = new TreeSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                out.add(id.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
