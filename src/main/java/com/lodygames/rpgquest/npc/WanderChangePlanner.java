package com.lodygames.rpgquest.npc;

import java.util.Locale;

/**
 * Décide si activer ou désactiver la promenade d'un PNJ Citizens détruirait un comportement déjà
 * configuré — et, dans ce cas, exige une confirmation explicite plutôt que de l'écraser.
 *
 * <p>Issue #165. <strong>Pourquoi cette classe existe.</strong> Dans Citizens, la promenade n'est
 * pas une option indépendante : c'est le {@code WaypointProvider} courant du trait
 * {@code waypoints}. Un PNJ n'en a qu'un seul à la fois, et
 * {@code Waypoints#setWaypointProvider(String)} appelle {@code onRemove()} sur le précédent puis le
 * remplace — la patrouille qu'il contenait est perdue, sans avertissement. Activer la promenade sur
 * un PNJ patrouilleur est donc une <em>destruction</em> déguisée en réglage. On la refuse tant
 * qu'elle n'est pas explicitement confirmée.</p>
 *
 * <p>Logique pure et testable : aucune dépendance Citizens, aucun effet de bord. Le pont
 * {@link CitizensBehaviourBridge} lit l'état réel, demande la décision ici, puis l'applique.</p>
 */
public final class WanderChangePlanner {

    /** Nom du fournisseur de promenade dans Citizens 2.0.43. */
    public static final String WANDER = "wander";

    /**
     * Fournisseur par défaut de tout PNJ Citizens jamais configuré ({@code Waypoints} l'initialise
     * à {@code new LinearWaypointProvider()} / {@code "linear"}). Vide, il ne représente aucun
     * comportement : le remplacer ne perd rien.
     */
    public static final String LINEAR = "linear";

    private WanderChangePlanner() {
    }

    public enum Decision {
        /** Aucun comportement à perdre : on peut poser la promenade directement. */
        APPLY,
        /** La promenade est déjà en place : on ne fait que régler ancre, zone, pause. */
        RECONFIGURE,
        /** Un comportement existe et l'opérateur a confirmé son remplacement. */
        REPLACE,
        /** Un comportement existe et n'a pas été confirmé : on ne touche à rien. */
        REQUIRES_CONFIRMATION,
        /** La promenade est active : on la retire et on revient au défaut neutre. */
        DISABLE,
        /** La promenade n'est pas active : rien à désactiver, et surtout rien à écraser. */
        NOT_WANDER
    }

    /**
     * @param provider        {@code Waypoints#getCurrentProviderName()} ({@code null} si aucun).
     * @param waypointCount   nombre de points du fournisseur courant, {@code 0} s'il n'en expose pas.
     * @param confirmReplace  l'opérateur a-t-il explicitement confirmé l'écrasement.
     */
    public static Decision planEnable(String provider, int waypointCount, boolean confirmReplace) {
        String current = normalise(provider);
        if (WANDER.equals(current)) {
            // Déjà en promenade : régler l'ancre ou la zone n'écrase aucun autre comportement.
            // Et c'est volontairement idempotent : rejouer la demande ne bascule rien.
            return Decision.RECONFIGURE;
        }
        if (isNeutral(current, waypointCount)) {
            return Decision.APPLY;
        }
        return confirmReplace ? Decision.REPLACE : Decision.REQUIRES_CONFIRMATION;
    }

    /**
     * Désactiver ne doit jamais devenir une façon détournée d'effacer la patrouille d'autrui : si
     * le fournisseur courant n'est pas la promenade, on ne le remplace pas.
     */
    public static Decision planDisable(String provider) {
        return WANDER.equals(normalise(provider)) ? Decision.DISABLE : Decision.NOT_WANDER;
    }

    /**
     * État neutre = celui dans lequel Citizens laisse un PNJ qu'on n'a jamais configuré : aucun
     * fournisseur, ou le fournisseur linéaire par défaut sans aucun point. Tout le reste —
     * patrouille linéaire garnie, {@code guided}, fournisseur d'un plugin tiers — est un
     * comportement réel qu'on refuse d'écraser en silence.
     */
    private static boolean isNeutral(String provider, int waypointCount) {
        if (provider == null) {
            return true;
        }
        return LINEAR.equals(provider) && waypointCount <= 0;
    }

    /** Phrase à montrer à l'opérateur pour qu'il sache exactement ce qu'il s'apprête à perdre. */
    public static String conflictDescription(String provider, int waypointCount) {
        String current = normalise(provider);
        if (current == null) {
            return "aucun comportement enregistré";
        }
        if (LINEAR.equals(current)) {
            return waypointCount <= 0
                    ? "aucun comportement enregistré"
                    : "une patrouille linéaire de " + waypointCount + " point(s)";
        }
        if (WANDER.equals(current)) {
            return "une promenade déjà configurée";
        }
        if ("guided".equals(current)) {
            return waypointCount <= 0
                    ? "un parcours guidé"
                    : "un parcours guidé de " + waypointCount + " point(s)";
        }
        return "un comportement « " + current + " » fourni par un autre plugin";
    }

    private static String normalise(String provider) {
        if (provider == null) {
            return null;
        }
        String trimmed = provider.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }
}
