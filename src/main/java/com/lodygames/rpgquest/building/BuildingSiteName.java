package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.BuildingSite;

/**
 * La règle de nommage d'un emplacement, au moment de la confirmation (issue #227).
 *
 * <p>Fonction pure, parce que c'est la seule validation que le joueur rencontre en jeu et qu'elle
 * doit se comporter exactement comme celle du Control Panel. Un nom accepté dans la GUI enclume et
 * refusé par l'action agent serait la pire des deux.</p>
 *
 * <p><strong>Les accents et l'Unicode sont autorisés.</strong> C'est un libellé lu par des humains :
 * « Taverne du village » et « Forgeron d'Élénore » sont des noms parfaitement normaux. Seul
 * l'identifiant technique est contraint, et il n'est jamais dérivé du nom — c'est précisément ce qui
 * permet au nom d'être libre.</p>
 */
public final class BuildingSiteName {

    private BuildingSiteName() {
    }

    /**
     * Résultat de la validation.
     *
     * @param name  nom normalisé, utilisable tel quel ; vide en cas de refus
     * @param error raison du refus, en français, ou {@code null}
     */
    public record Checked(String name, String error) {

        public boolean ok() {
            return error == null;
        }
    }

    /**
     * Valide et normalise un nom saisi.
     *
     * <p>Le nom est <strong>obligatoire</strong> : contrairement à la création d'avant ce lot, il n'y
     * a plus de libellé par défaut à la création. Demander un nom est justement ce qui transforme un
     * clic de travers en non-événement — un missclick se referme sans rien laisser derrière.</p>
     *
     * <p>Un nom trop long est <strong>refusé</strong> et non tronqué. Dans le Control Panel, tronquer
     * est un compromis acceptable : l'utilisateur voit le résultat et peut corriger. Ici le joueur
     * tape dans une enclume et ne reverra pas forcément sa fiche : renommer en silence ce qu'il a
     * écrit serait une surprise, alors qu'un refus lisible se corrige tout de suite.</p>
     */
    public static Checked check(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            return new Checked("", "Le nom est obligatoire : écrivez-le dans l'enclume avant de "
                    + "valider.");
        }
        if (name.length() > BuildingSite.MAX_NAME_LENGTH) {
            return new Checked("", "Nom trop long (" + name.length() + " caractères) : "
                    + BuildingSite.MAX_NAME_LENGTH + " au maximum.");
        }
        return new Checked(name, null);
    }
}
