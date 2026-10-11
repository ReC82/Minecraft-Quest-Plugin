package com.lodygames.rpgquest.npc;

/**
 * Issue #197 — le verdict d'un renommage coordonné, à partir des <strong>couches relues</strong>.
 *
 * <p><strong>Pourquoi une classe à part.</strong> Renommer un personnage touche deux endroits : la
 * définition RPGQuest et le PNJ Citizens. Quatre issues sont possibles, et une seule d'entre elles
 * est un succès complet. Décider laquelle annoncer est la partie où l'on peut mentir — en
 * arrondissant un demi-succès, exactement ce que l'issue interdit : « ne jamais prétendre au succès
 * complet si une seule couche a changé ». Cette décision est donc isolée ici, sans Citizens, sans
 * Bukkit et sans base, pour être vérifiable au test près.</p>
 *
 * <p><strong>Le verdict se construit sur une relecture, jamais sur un code de retour.</strong>
 * L'appelant relit le nom Citizens dans le registre et le nom de la définition dans le moteur
 * rechargé, puis dit ce qu'il a vu. Une écriture qui « réussit » sans que le nom ait changé reste
 * donc un échec ici.</p>
 */
public final class NpcRenameVerdict {

    private NpcRenameVerdict() {
    }

    /** L'état d'une couche après l'opération, tel que la relecture le montre. */
    public enum Layer {
        /** Cette couche n'existe pas pour ce personnage — il n'y avait rien à renommer. */
        ABSENT,
        /** Relue au nom demandé. */
        UPDATED,
        /** Toujours à l'ancien nom (ou illisible) : elle n'a pas suivi. */
        STALE
    }

    /**
     * Le verdict rendu à l'administrateur.
     *
     * @param ok      succès <strong>complet</strong> uniquement
     * @param code    code stable, exploitable par l'écran
     * @param message phrase française qui dit ce qui a changé et ce qui n'a pas changé
     */
    public record Verdict(boolean ok, String code, String message) {
    }

    /**
     * @param npcId      identifiant logique, jamais modifié
     * @param wanted     nom demandé
     * @param previous   nom précédent, pour que le message dise d'où l'on vient ; peut être vide
     * @param definition état de la définition RPGQuest après l'opération
     * @param citizens   état du PNJ Citizens après l'opération
     * @param citizensNameReadBack nom Citizens réellement relu, pour le nommer en cas d'écart ;
     *                   peut être vide (illisible)
     * @param definitionNameReadBack idem pour la définition
     * @param definitionFailure message d'échec de l'écriture de la définition, ou vide. Une
     *                   divergence sans cause connue reste une divergence : ce message ne sert
     *                   qu'à ajouter la raison quand elle existe
     */
    public static Verdict of(String npcId, String wanted, String previous,
                             Layer definition, Layer citizens,
                             String citizensNameReadBack, String definitionNameReadBack,
                             String definitionFailure) {
        String id = safe(npcId);
        String target = safe(wanted);
        String from = safe(previous);
        String origin = from.isEmpty() ? "" : "« " + from + " » → ";

        if (definition == Layer.ABSENT && citizens == Layer.ABSENT) {
            return new Verdict(false, "NOT_FOUND",
                    "« " + id + " » n'a ni définition RPGQuest ni PNJ Citizens lié : il n'y a aucun "
                            + "nom à changer.");
        }
        if (definition == Layer.UPDATED && citizens == Layer.UPDATED) {
            return new Verdict(true, "RENAMED",
                    "Nom du PNJ : " + origin + "« " + target + " ». Catalogue RPGQuest et nom "
                            + "affiché en jeu sont relus identiques. L'identifiant « " + id + " » et "
                            + "les liaisons quêtes / dialogues sont inchangés.");
        }
        if (definition == Layer.UPDATED && citizens == Layer.ABSENT) {
            return new Verdict(true, "RENAMED_DEFINITION_ONLY",
                    "Nom du PNJ : " + origin + "« " + target + " ». Aucun PNJ Citizens n'est lié à "
                            + "« " + id + " » : il n'y a pas de nom en jeu à synchroniser. "
                            + "L'identifiant « " + id + " » est inchangé.");
        }
        if (definition == Layer.ABSENT && citizens == Layer.UPDATED) {
            return new Verdict(true, "RENAMED_CITIZENS_ONLY",
                    "Nom affiché en jeu : " + origin + "« " + target + " ». Ce PNJ n'a pas de "
                            + "définition RPGQuest : seul son nom en jeu existe, il n'y a donc rien "
                            + "d'autre à synchroniser. Aucune définition n'a été créée à son insu.");
        }

        // Il reste les divergences. On nomme ce qui a changé et ce qui n'a pas changé, sans
        // arrondir, et on dit que reprendre l'opération est sans risque.
        StringBuilder why = new StringBuilder("Renommage incomplet — les deux couches ne portent "
                + "pas le même nom après l'opération. ");
        why.append("Nom en jeu (Citizens) : ").append(describe(citizens, citizensNameReadBack))
                .append(". ");
        why.append("Nom du catalogue RPGQuest : ")
                .append(describe(definition, definitionNameReadBack)).append(". ");
        if (definition == Layer.STALE && !safe(definitionFailure).isEmpty()) {
            why.append("La définition a refusé l'écriture : ").append(safe(definitionFailure))
                    .append(' ');
        }
        why.append("Relancer l'opération est sans risque : elle reprend la couche en retard et "
                + "laisse l'autre telle quelle.");
        return new Verdict(false,
                citizens == Layer.STALE ? "CITIZENS_NOT_RENAMED" : "DEFINITION_NOT_RENAMED",
                why.toString());
    }

    private static String describe(Layer layer, String readBack) {
        return switch (layer) {
            case ABSENT -> "cette couche n'existe pas pour ce personnage";
            case UPDATED -> (safe(readBack).isEmpty() ? "" : "« " + safe(readBack) + " » ") + "(à jour)";
            case STALE -> (safe(readBack).isEmpty() ? "illisible " : "« " + safe(readBack) + " » ")
                    + "(PAS à jour)";
        };
    }

    private static String safe(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
