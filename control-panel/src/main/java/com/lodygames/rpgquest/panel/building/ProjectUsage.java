package com.lodygames.rpgquest.panel.building;

import java.util.ArrayList;
import java.util.List;

/**
 * L'usage en jeu d'un projet de l'Atelier, et si on peut le supprimer (issue #881).
 *
 * <p>Un projet de l'Atelier n'est pas le bâtiment de la bibliothèque, ni ses exemplaires posés : sa
 * suppression n'efface que ce que le panel garde de la conception (description, versions de plan,
 * conception détaillée, images). Elle reste pourtant refusée tant que son bâtiment vit encore côté
 * serveur — posé, cité par un portail, publié dans la bibliothèque — ou que le panel ne
 * peut pas l'affirmer : la seule présence en bibliothèque ne dit pas « utilisé », son absence ne dit
 * pas « non utilisé ».</p>
 *
 * @param usage    utilisé en jeu, non utilisé, ou à vérifier faute de relevé fiable
 * @param sites    emplacements où le bâtiment est posé, d'après le dernier relevé
 * @param portals  portails qui citent le bâtiment, d'après le dernier relevé
 * @param catalog  état du bâtiment dans la bibliothèque (#290)
 * @param busy     geste encore en cours sur le projet (vérification, enregistrement, conception), vide sinon
 */
public record ProjectUsage(Usage usage, List<String> sites, List<String> portals, CatalogState catalog,
                           String busy) {

    public enum Usage {
        USED("Utilisé en jeu"),
        UNUSED("Non utilisé"),
        UNCERTAIN("Utilisation à vérifier");

        private final String label;

        Usage(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public ProjectUsage {
        sites = List.copyOf(sites == null ? List.of() : sites);
        portals = List.copyOf(portals == null ? List.of() : portals);
        catalog = catalog == null ? CatalogState.unknown() : catalog;
        busy = busy == null ? "" : busy;
    }

    /**
     * @param sitesKnown un relevé des emplacements (et donc des bâtiments posés) a été lu
     */
    public static ProjectUsage of(boolean sitesKnown, List<String> sites, List<String> portals,
                                  CatalogState catalog, String busy) {
        CatalogState c = catalog == null ? CatalogState.unknown() : catalog;
        Usage usage;
        if ((sites != null && !sites.isEmpty()) || (portals != null && !portals.isEmpty())) {
            usage = Usage.USED;
        } else if (!sitesKnown || c.state() == CatalogState.State.ABSENT) {
            usage = Usage.UNCERTAIN;
        } else {
            usage = Usage.UNUSED;
        }
        return new ProjectUsage(usage, sites, portals, c, busy);
    }

    /** Jamais enregistré dans la bibliothèque : un brouillon, au sens de la liste. */
    public boolean draftOnly() {
        return catalog.state() == CatalogState.State.UNKNOWN;
    }

    public boolean deletable() {
        return blockers().isEmpty();
    }

    /** Pourquoi la suppression est refusée, dans les mots de l'administrateur ; vide = autorisée. */
    public List<String> blockers() {
        List<String> out = new ArrayList<>();
        if (!sites.isEmpty()) {
            out.add("Le bâtiment est posé sur " + sites.size() + " emplacement(s) : " + String.join(", ", sites)
                    + ". Retirez-le d'abord de chaque emplacement.");
        }
        if (!portals.isEmpty()) {
            out.add("Le bâtiment est lié au(x) portail(s) " + String.join(", ", portals)
                    + ". Déliez d'abord la construction du portail.");
        }
        if (usage == Usage.UNCERTAIN) {
            out.add(catalog.state() == CatalogState.State.ABSENT
                    ? "Le bâtiment a disparu du dernier relevé de la bibliothèque sans retrait connu : "
                            + "rafraîchissez la Bibliothèque et les Emplacements avant de supprimer."
                    : "Aucun relevé des emplacements n'est chargé : le panel ne peut pas affirmer que le "
                            + "bâtiment n'est posé nulle part. Rafraîchissez la page Emplacements.");
        }
        if (catalog.inLibrary()) {
            out.add("Le bâtiment est publié dans la bibliothèque. Supprimer le projet "
                    + "n'y toucherait pas : passez d'abord par la fiche Bibliothèque (« Supprimer ce bâtiment "
                    + "et toutes ses versions »).");
        }
        if (!busy.isEmpty()) {
            out.add(busy + " : attendez la fin avant de supprimer.");
        }
        return List.copyOf(out);
    }
}
