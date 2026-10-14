package com.lodygames.rpgquest.encounter.catalog;

import com.lodygames.rpgquest.encounter.model.EncounterTemplate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Catalogue de quêtes secondaires (issue #369) : une liste ordonnée de <strong>références</strong>
 * vers des quêtes existantes, attribuable à un ou plusieurs modèles de PNJ récurrents. Le catalogue
 * ne copie aucune quête — objectifs, prérequis, récompenses et délai restent ceux du fichier de
 * quête, seule source de vérité. Données pures, sans Bukkit.
 *
 * @param id          identifiant stable (fichier {@code quest-catalogs/<id>.yml})
 * @param displayName nom lisible pour l'administration
 * @param description texte libre facultatif, ou {@code null}
 * @param enabled     {@code false} = le catalogue ne propose plus rien (les quêtes acceptées continuent)
 * @param avoidRecent nombre de quêtes terminées le plus récemment que le tirage écarte tant qu'une
 *                    autre quête reste possible (anti-répétition immédiate)
 * @param entries     références de quêtes, dans l'ordre d'affichage ; une quête n'y figure qu'une fois
 */
public record QuestCatalog(String id, String displayName, String description, boolean enabled, int avoidRecent,
                           List<Entry> entries) {

    public static final int MAX_ENTRIES = 100;
    public static final int MAX_WEIGHT = 1000;
    public static final int MAX_AVOID_RECENT = 10;

    public QuestCatalog {
        if (id == null || !EncounterTemplate.ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("id de catalogue invalide : « " + id + " ».");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("display_name ne peut pas être vide (catalogue « " + id + " »).");
        }
        if (avoidRecent < 0 || avoidRecent > MAX_AVOID_RECENT) {
            throw new IllegalArgumentException("avoid_recent hors de [0, " + MAX_AVOID_RECENT + "] (catalogue « "
                    + id + " »).");
        }
        entries = List.copyOf(entries == null ? List.of() : entries);
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("au plus " + MAX_ENTRIES + " quêtes par catalogue (« " + id + " »).");
        }
        Set<String> seen = new HashSet<>();
        for (Entry entry : entries) {
            if (!seen.add(entry.questId())) {
                throw new IllegalArgumentException("quête « " + entry.questId() + " » présente deux fois (catalogue « "
                        + id + " »).");
            }
        }
    }

    /** Entrées activées, dans l'ordre du catalogue. */
    public List<Entry> activeEntries() {
        List<Entry> active = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.enabled()) {
                active.add(entry);
            }
        }
        return active;
    }

    /**
     * Une référence de quête.
     *
     * @param questId identifiant namespacé de la quête ({@code rpgquest:encounter_bandit_spiders})
     * @param weight  poids relatif du tirage (1–1000)
     * @param enabled {@code false} = gardée dans la liste mais jamais tirée
     */
    public record Entry(String questId, int weight, boolean enabled) {

        public Entry {
            if (questId == null || !QuestCatalogParser.QUEST_ID.matcher(questId).matches()) {
                throw new IllegalArgumentException("identifiant de quête invalide : « " + questId + " ».");
            }
            if (weight < 1 || weight > MAX_WEIGHT) {
                throw new IllegalArgumentException("poids hors de [1, " + MAX_WEIGHT + "] pour « " + questId + " ».");
            }
        }
    }
}
