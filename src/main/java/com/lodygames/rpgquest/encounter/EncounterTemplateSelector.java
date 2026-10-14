package com.lodygames.rpgquest.encounter;

import com.lodygames.rpgquest.encounter.model.EncounterTemplate;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.ToIntFunction;

/**
 * Choisit le modèle de PNJ récurrent d'une instance de biome (issue #13). Sans état, sans Bukkit.
 *
 * <p>Seuls les modèles <strong>actifs</strong>, autorisés dans ce monde, éligibles pour ce biome et
 * sous leur plafond propre concourent ; le tirage est pondéré par {@code weight} et
 * <strong>déterministe</strong> pour une graine donnée (dérivée de l'instance) : deux évaluations
 * de la même instance donnent le même modèle. Aucun candidat = aucune rencontre — jamais un modèle
 * d'un autre biome en remplacement.</p>
 */
public final class EncounterTemplateSelector {

    /**
     * @param sitesOfTemplate nombre d'occurrences existantes d'un modèle (pour {@code max_sites})
     */
    public Optional<EncounterTemplate> select(List<EncounterTemplate> templates, String world, String biomeKey,
                                              long seed, ToIntFunction<String> sitesOfTemplate) {
        List<EncounterTemplate> eligible = eligible(templates, world, biomeKey, sitesOfTemplate);
        if (eligible.isEmpty()) {
            return Optional.empty();
        }
        int total = eligible.stream().mapToInt(EncounterTemplate::weight).sum();
        int roll = new Random(seed).nextInt(total);
        for (EncounterTemplate template : eligible) {
            roll -= template.weight();
            if (roll < 0) {
                return Optional.of(template);
            }
        }
        return Optional.of(eligible.get(eligible.size() - 1));
    }

    /** Les modèles qui peuvent apparaître ici, dans l'ordre stable des fichiers. */
    public List<EncounterTemplate> eligible(List<EncounterTemplate> templates, String world, String biomeKey,
                                            ToIntFunction<String> sitesOfTemplate) {
        return templates.stream()
                .filter(EncounterTemplate::enabled)
                .filter(t -> t.allowsWorld(world))
                .filter(t -> t.allowsBiome(biomeKey))
                .filter(t -> t.maxSites() == 0 || sitesOfTemplate.applyAsInt(t.id()) < t.maxSites())
                .toList();
    }
}
