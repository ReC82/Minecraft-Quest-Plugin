package com.lodygames.rpgquest.quest.model;

import java.util.List;

/**
 * Découvrir {@code amount} waypoints <strong>distincts</strong> (issue #185).
 *
 * <p>Le comptage repose sur la <strong>première découverte réelle</strong> du système waypoint
 * (interaction avec l'interacteur du waypoint, voir {@code waypoint.WaypointService#handleInteract}) :
 * passer à proximité, se téléporter ou recliquer un waypoint déjà connu ne compte jamais. L'identité
 * comptée est l'<strong>id stable</strong> du waypoint, jamais son nom ni son biome — deux waypoints
 * du même biome comptent donc séparément, et renommer ou déplacer un waypoint ne le fait pas compter
 * deux fois.</p>
 *
 * <p>La distinction des waypoints n'exige aucune table supplémentaire : une première découverte est,
 * par construction, unique par couple (joueur, waypoint) au niveau de la base. Un second clic ne
 * produit donc aucune notification, et le compteur ne peut pas monter deux fois pour le même
 * waypoint, même après une mort, une reconnexion ou un redémarrage.</p>
 */
public record DiscoverWaypointObjective(int amount, List<String> worlds, CountMode countMode)
        implements QuestObjective {

    /**
     * Règle de comptage, <strong>choisie explicitement</strong> à la création de la quête — le ticket
     * #185 interdit d'en imposer une silencieusement, et la règle retenue est annoncée au joueur dans
     * le libellé de l'objectif.
     */
    public enum CountMode {
        /**
         * Seules les découvertes faites <strong>pendant que l'objectif est actif</strong> comptent.
         * Conséquence assumée : sur une quête répétable, un joueur qui a déjà découvert tous les
         * waypoints accessibles ne peut pas valider un nouveau cycle — aucune découverte n'est jamais
         * supprimée ni réinitialisée pour rendre la quête rejouable.
         */
        NEW_ONLY,
        /**
         * Les découvertes <strong>déjà enregistrées</strong> avant l'acceptation comptent aussi. La
         * progression vaut alors le total des découvertes du joueur correspondant aux filtres, ce qui
         * la rend exacte après un redémarrage sans stocker d'instantané.
         */
        INCLUDE_EXISTING
    }

    public DiscoverWaypointObjective {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount doit être strictement positif : " + amount);
        }
        worlds = worlds == null ? List.of()
                : worlds.stream().filter(w -> w != null && !w.isBlank()).map(String::trim).toList();
        countMode = countMode == null ? CountMode.NEW_ONLY : countMode;
    }

    /**
     * {@code true} si une découverte faite dans {@code world} compte pour cet objectif. Une liste de
     * filtres vide vaut « tous les mondes » ; ce n'est jamais une supposition silencieuse, le libellé
     * affiché au joueur énonce toujours la portée retenue (voir {@code ui.ObjectiveLabels}).
     */
    public boolean matchesWorld(String world) {
        if (worlds.isEmpty()) {
            return true;
        }
        for (String candidate : worlds) {
            if (candidate.equalsIgnoreCase(world)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ObjectiveType type() {
        return ObjectiveType.DISCOVER_WAYPOINT;
    }
}
