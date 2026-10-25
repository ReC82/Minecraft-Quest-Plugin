package com.lodygames.rpgquest.panel.building;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * L'identifiant d'une version de plan tel qu'un humain le lit, le copie et le recherche (#828) :
 * {@code V-} suivi des 8 premiers caractères de l'identifiant complet — la forme déjà employée par
 * les cartes d'échec et de stagnation (#747).
 *
 * <p>La recherche accepte l'identifiant court ({@code V-8776ad09}, avec ou sans {@code V-}) comme
 * l'identifiant complet, sans casse ; un préfixe partagé par plusieurs versions est signalé comme
 * ambigu plutôt que résolu au hasard.</p>
 */
public final class PlanVersionRef {

    /** Longueur affichée après {@code V-}. */
    public static final int SHORT = 8;
    /** En deçà, un préfixe désignerait trop de versions pour être une référence. */
    public static final int MIN_PREFIX = 4;

    private static final Pattern HEXISH = Pattern.compile("[0-9a-f-]{" + MIN_PREFIX + ",36}");

    /** Le résultat d'une recherche : la version trouvée, ou le motif en clair. */
    public record Lookup(Optional<BuildingPlanDraft> draft, String error) {

        static Lookup found(BuildingPlanDraft d) {
            return new Lookup(Optional.of(d), "");
        }

        static Lookup missing(String error) {
            return new Lookup(Optional.empty(), error);
        }
    }

    private PlanVersionRef() {
    }

    /** {@code V-} + les 8 premiers caractères de l'identifiant. */
    public static String label(String draftId) {
        String id = draftId == null ? "" : draftId;
        return "V-" + id.substring(0, Math.min(SHORT, id.length()));
    }

    /** La saisie ramenée à un préfixe d'identifiant ; vide si elle ne peut pas en être un. */
    public static Optional<String> normalize(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("v-")) {
            s = s.substring(2);
        }
        return HEXISH.matcher(s).matches() ? Optional.of(s) : Optional.empty();
    }

    /**
     * Cherche la version parmi {@code candidates} (identifiant complet ou préfixe unique).
     *
     * @param where où l'on a cherché, pour le message (« l'historique de ce projet »…)
     */
    public static Lookup resolve(String raw, List<BuildingPlanDraft> candidates, String where) {
        String shown = raw == null ? "" : raw.trim();
        Optional<String> prefix = normalize(raw);
        if (prefix.isEmpty()) {
            return Lookup.missing("« " + shown + " » n'est pas un identifiant de version : saisir par exemple "
                    + "V-8776ad09 ou l'identifiant complet.");
        }
        List<BuildingPlanDraft> hits = candidates.stream()
                .filter(d -> d.id().toLowerCase(Locale.ROOT).startsWith(prefix.get())).toList();
        if (hits.size() == 1) {
            return Lookup.found(hits.get(0));
        }
        if (hits.isEmpty()) {
            return Lookup.missing("Version « " + shown + " » introuvable dans " + where
                    + " : identifiant inexact, version retirée de la liste ou d'un autre projet.");
        }
        return Lookup.missing("« " + shown + " » désigne plusieurs versions dans " + where
                + " : saisir davantage de caractères.");
    }
}
