package com.lodygames.rpgquest.mob;

import com.lodygames.rpgquest.mob.ability.MobAbilityCatalog;
import com.lodygames.rpgquest.mob.ability.MobAbilityRules;
import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.MobAbilityType;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.resource.model.CustomItemDrop;
import com.lodygames.rpgquest.resource.model.ResourceDrop;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

/**
 * Issue #214 — vérification d'un fichier de profil rempli hors de PlugAdmin (à la main ou par une IA).
 *
 * <p>Rien n'est écrit ici. L'ordre suit ce qui protège le mieux : (1) YAML lisible ; (2) aucune
 * propriété inconnue — une IA qui invente {@code fly-speed} doit être arrêtée, pas ignorée en
 * silence ; (3) le parseur <em>réel</em> du moteur ({@link SpecialMobDefinitionParser}), avec toutes
 * ses erreurs ; (4) les références au serveur (mondes, biomes, objets custom, récompenses). Le texte
 * final est la forme canonique ({@link SpecialMobDefinitionYaml}) : c'est elle qui sera écrite, et
 * c'est elle que l'écran compare au profil existant.</p>
 */
public final class SpecialMobImport {

    /** Taille maximale d'un fichier de profil importé (caractères). */
    public static final int MAX_CHARS = 32_000;

    /**
     * Ce que le serveur connaît. Un {@code null} = non vérifiable ici (avertissement, jamais un refus
     * inventé).
     */
    public record References(Set<String> worlds, Set<String> biomes, Predicate<NamespacedKey> customItemExists,
                             Predicate<String> rewardExists) {
    }

    /**
     * @param errors          refus : rien ne peut être écrit tant qu'il y en a
     * @param warnings        à lire, n'empêchent pas l'import
     * @param canonical       texte qui sera écrit (vide si refusé)
     * @param existingFile    fichier actuel du même identifiant ({@code null} = nouveau profil)
     * @param existingCanonical profil actuel en forme canonique ({@code null} s'il ne se relit pas)
     * @param existingSha     empreinte du fichier actuel, à renvoyer à la confirmation
     */
    public record Check(List<String> errors, List<String> warnings, SpecialMobDefinition definition,
                        String canonical, String existingFile, String existingCanonical, String existingSha) {
        public boolean valid() {
            return errors.isEmpty() && definition != null;
        }

        public boolean exists() {
            return existingFile != null;
        }
    }

    private SpecialMobImport() {
    }

    public static Check check(String text, References refs, SpecialMobDefinitionStore store) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return refused("Le fichier est vide.");
        }
        if (text.length() > MAX_CHARS) {
            return refused("Fichier trop volumineux : " + text.length() + " caractères, " + MAX_CHARS + " au plus.");
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            return refused("Ce n'est pas un YAML lisible : " + firstLine(e.getMessage()));
        }
        unknownKeys(yaml, errors);

        SpecialMobDefinitionParser.ParseResult parsed = new SpecialMobDefinitionParser().parse("import", yaml);
        parsed.issues().forEach(issue -> errors.add(issue.message()));
        SpecialMobDefinition definition = parsed.definition();
        if (definition != null) {
            // Issue #542 : 5 capacités au plus, pas de doublon, rien « en développement » en tirage naturel.
            errors.addAll(MobAbilityRules.violations(definition));
            references(definition, refs, errors, warnings);
            compatibility(definition, warnings);
        }

        String existingFile = null;
        String existingCanonical = null;
        String existingSha = null;
        if (definition != null && store.hasFile(definition.id())) {
            existingFile = store.fileName(definition.id());
            existingSha = store.fileSha(definition.id());
            Optional<SpecialMobDefinition> current = store.find(definition.id());
            if (current.isPresent()) {
                existingCanonical = SpecialMobDefinitionYaml.render(current.get());
            } else {
                warnings.add("Le profil actuel « " + definition.id() + " » ne se relit pas : le remplacer le "
                        + "répare (l'ancien fichier est sauvegardé avant).");
            }
        }
        if (!errors.isEmpty()) {
            return new Check(List.copyOf(errors), List.copyOf(warnings), null, "", existingFile, existingCanonical,
                    existingSha);
        }
        return new Check(List.of(), List.copyOf(warnings), definition, SpecialMobDefinitionYaml.render(definition),
                existingFile, existingCanonical, existingSha);
    }

    private static Check refused(String error) {
        return new Check(List.of(error), List.of(), null, "", null, null, null);
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "erreur inconnue";
        }
        String[] lines = message.strip().split("\\R");
        return String.join(" ", java.util.Arrays.copyOf(lines, Math.min(lines.length, 3)));
    }

    /** Propriétés absentes du contrat : refusées, parce que le moteur les ignorerait en silence. */
    private static void unknownKeys(YamlConfiguration yaml, List<String> errors) {
        Set<String> known = SpecialMobSchema.topLevelKeys();
        for (String key : yaml.getKeys(false)) {
            if (!known.contains(key)) {
                errors.add("Propriété inconnue « " + key + " » : le moteur ne la lit pas. La retirer "
                        + "(clés valides : voir le modèle).");
            }
        }
        Map<MobAbilityType, Set<String>> abilityKeys = SpecialMobSchema.abilityKeys();
        List<Map<?, ?>> abilities = yaml.getMapList("abilities");
        for (int i = 0; i < abilities.size(); i++) {
            Map<?, ?> entry = abilities.get(i);
            MobAbilityType type = MobAbilityCatalog.typeOf(entry.get("type"));
            if (type == null) {
                continue; // le parseur dit déjà pourquoi
            }
            for (Object key : entry.keySet()) {
                if (!abilityKeys.get(type).contains(String.valueOf(key))) {
                    errors.add("abilities[" + i + "] (" + type + ") : propriété inconnue « " + key + " ».");
                }
            }
        }
        List<Map<?, ?>> drops = yaml.getMapList("drops");
        for (int i = 0; i < drops.size(); i++) {
            for (Object key : drops.get(i).keySet()) {
                if (!SpecialMobSchema.DROP_KEYS.contains(String.valueOf(key))) {
                    errors.add("drops[" + i + "] : propriété inconnue « " + key + " ».");
                }
            }
        }
    }

    private static void references(SpecialMobDefinition d, References refs, List<String> errors,
                                   List<String> warnings) {
        if (refs.worlds() == null) {
            warnings.add("Mondes non vérifiés (liste du serveur indisponible).");
        } else {
            for (String world : d.allowedWorlds()) {
                if (refs.worlds().stream().noneMatch(w -> w.equalsIgnoreCase(world))) {
                    errors.add("Monde inconnu sur ce serveur : « " + world + " » (mondes : "
                            + String.join(", ", refs.worlds()) + ").");
                }
            }
        }
        if (refs.biomes() == null) {
            warnings.add("Biomes non vérifiés (liste du serveur indisponible).");
        } else {
            for (String biome : d.allowedBiomes()) {
                if (!refs.biomes().contains(biome.toLowerCase(Locale.ROOT))) {
                    errors.add("Biome inconnu : « " + biome + " » (clé Minecraft en minuscules, ex. plains).");
                }
            }
        }
        if (!d.allowedZones().isEmpty()) {
            warnings.add("Zones non vérifiées à l'import : « " + String.join(", ", d.allowedZones())
                    + " » — une zone inexistante empêche simplement toute apparition.");
        }
        for (ResourceDrop drop : d.drops()) {
            if (drop instanceof CustomItemDrop custom) {
                if (refs.customItemExists() == null) {
                    warnings.add("Objet custom non vérifié : « " + custom.itemId() + " ».");
                } else if (!refs.customItemExists().test(custom.itemId())) {
                    errors.add("Objet custom inconnu dans drops : « " + custom.itemId() + " ».");
                }
            }
        }
        for (String reward : d.participationRewards()) {
            if (refs.rewardExists() == null) {
                warnings.add("Récompense non vérifiée (catalogue indisponible) : « " + reward + " ».");
            } else if (!refs.rewardExists().test(reward)) {
                errors.add("Récompense inconnue dans participation-rewards : « " + reward
                        + " » (ni clé du catalogue des récompenses, ni table existante).");
            }
        }
    }

    /** Combinaisons acceptées par le parseur mais sans effet : dites, jamais refusées. */
    private static void compatibility(SpecialMobDefinition d, List<String> warnings) {
        for (MobAbility ability : d.abilities()) {
            Set<EntityType> effectiveOn = MobAbilityCatalog.descriptor(ability.type()).effectiveOn();
            if (!effectiveOn.isEmpty() && !effectiveOn.contains(d.entityType())) {
                warnings.add(ability.type() + " n'a d'effet que sur " + effectiveOn + " : " + d.entityType()
                        + " n'est pas concerné.");
            }
        }
        if (d.category() == com.lodygames.rpgquest.mob.model.MobCategory.BOSS && d.spawnChance() > 0) {
            warnings.add("spawn-chance n'est pas utilisée par un BOSS (jamais tiré au hasard dans le Wild).");
        }
        if (d.speed() != null && d.speed() > 0.5) {
            warnings.add("Vitesse " + d.speed() + " : très rapide (zombie vanilla ≈ 0.23).");
        }
        if (d.category() == com.lodygames.rpgquest.mob.model.MobCategory.BOSS
                && d.bossMode() == com.lodygames.rpgquest.mob.model.BossMode.QUEST
                && !d.participationRewards().isEmpty()) {
            warnings.add("Les récompenses de participation ne sont remises que par un boss COMMUNITY.");
        }
    }
}
