package com.lodygames.rpgquest.encounter.model;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Modèle de <strong>PNJ récurrent</strong> (issue #13) : un type de PNJ réutilisable qui peut
 * apparaître dans plusieurs instances de biome — un brigand dans chaque forêt explorée, un marchand
 * égaré dans chaque désert. Données pures, sans Bukkit ni Citizens.
 *
 * <p>« Récurrent » veut dire <em>réutilisable sur plusieurs instances</em>, jamais un spawn infini :
 * chaque instance de biome accueille au plus une rencontre (voir {@code EncounterSite}).</p>
 *
 * @param id          identifiant du modèle (fichier {@code encounters/<id>.yml})
 * @param displayName nom affiché au-dessus du PNJ (texte simple)
 * @param npcId       identité RPGQuest portée par chaque PNJ de ce modèle : son dialogue est
 *                    {@code rpgquest:<npcId>} et les objectifs « parler à » le visent. Partagée par
 *                    toutes les occurrences — c'est ce qui rend le dialogue et les quêtes communs
 * @param role        rôle lisible (brigand, marchand…), sans effet mécanique
 * @param family      famille de biomes lisible (forêt, désert…), sans effet mécanique : seuls
 *                    {@code biomes} décident de l'éligibilité
 * @param biomes      clés namespacées des biomes éligibles ({@code minecraft:forest})
 * @param worlds      mondes autorisés ; vide = tous les mondes autorisés par {@code encounters.worlds}
 * @param weight      poids du tirage quand plusieurs modèles conviennent à un même biome
 * @param maxSites    plafond d'occurrences de ce modèle ; {@code 0} = sans plafond propre
 * @param skinUrl     lien MineSkin de l'apparence, ou {@code null}
 * @param questCatalogs catalogues de quêtes secondaires proposés par les PNJ de ce modèle (issue
 *                    #369), dans l'ordre ; vide = aucun catalogue, le dialogue reste seul maître
 */
public record EncounterTemplate(String id, String displayName, String description, String npcId, String role,
                                String family, Set<String> biomes, List<String> worlds, int weight,
                                int maxSites, String skinUrl, boolean enabled, List<String> questCatalogs) {

    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9._-]{1,64}");
    public static final int MAX_WEIGHT = 1000;

    public EncounterTemplate {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("id de modèle invalide : « " + id + " ».");
        }
        if (npcId == null || !ID_PATTERN.matcher(npcId).matches()) {
            throw new IllegalArgumentException("npc_id invalide : « " + npcId + " » (modèle « " + id + " »).");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("display_name ne peut pas être vide (modèle « " + id + " »).");
        }
        if (biomes == null || biomes.isEmpty()) {
            throw new IllegalArgumentException("au moins un biome est obligatoire (modèle « " + id + " »).");
        }
        if (weight < 1 || weight > MAX_WEIGHT) {
            throw new IllegalArgumentException("weight hors de [1, " + MAX_WEIGHT + "] (modèle « " + id + " »).");
        }
        if (maxSites < 0) {
            throw new IllegalArgumentException("max_sites ne peut pas être négatif (modèle « " + id + " »).");
        }
        biomes = Set.copyOf(biomes);
        worlds = worlds == null ? List.of() : List.copyOf(worlds);
        questCatalogs = questCatalogs == null ? List.of() : List.copyOf(questCatalogs);
        for (String catalog : questCatalogs) {
            if (!ID_PATTERN.matcher(catalog).matches()) {
                throw new IllegalArgumentException("catalogue de quêtes invalide : « " + catalog + " » (modèle « "
                        + id + " »).");
            }
        }
    }

    /** Modèle sans catalogue de quêtes (forme antérieure à l'issue #369). */
    public EncounterTemplate(String id, String displayName, String description, String npcId, String role,
                             String family, Set<String> biomes, List<String> worlds, int weight,
                             int maxSites, String skinUrl, boolean enabled) {
        this(id, displayName, description, npcId, role, family, biomes, worlds, weight, maxSites, skinUrl, enabled,
                List.of());
    }

    /** Même modèle, actif ou non — les autres champs, catalogues compris, sont conservés. */
    public EncounterTemplate withEnabled(boolean value) {
        return new EncounterTemplate(id, displayName, description, npcId, role, family, biomes, worlds, weight,
                maxSites, skinUrl, value, questCatalogs);
    }

    /** Ce modèle peut-il apparaître dans {@code world} (déjà reconnu comme autorisé globalement) ? */
    public boolean allowsWorld(String world) {
        return worlds.isEmpty() || worlds.contains(world);
    }

    public boolean allowsBiome(String biomeKey) {
        return biomes.contains(biomeKey);
    }
}
