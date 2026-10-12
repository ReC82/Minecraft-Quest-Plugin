package com.lodygames.rpgquest.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Complète {@code config.yml} sur disque au démarrage : ajoute les clés manquantes (sections
 * entières comme {@code claims}/{@code dialogue}/{@code progression}... apparues depuis, ou une
 * seule clé dans une section déjà présente) avec les valeurs par défaut actuelles du plugin, sans
 * jamais toucher une valeur déjà présente dans le fichier — mission « cohérence du config.yml » :
 * un {@code config.yml} historique (VeryGames) ne contenant que {@code debug}/{@code locale}/
 * {@code database}/{@code resource-pack} doit recevoir automatiquement toutes les sections plus
 * récentes plutôt que de dépendre silencieusement des valeurs par défaut en mémoire de {@link
 * ConfigValidator} (qui fonctionnent, mais laissent le fichier sur disque trompeur/incomplet pour
 * un administrateur).
 *
 * <p><b>Volontairement pas un framework de migration</b> : un seul passage, comparant le fichier
 * réel au {@code config.yml} embarqué dans le jar (source de vérité des valeurs par défaut, déjà
 * commentée) — aucune notion de migration séquentielle par version comme {@code
 * database.SchemaMigrator}. {@code config-version} n'est qu'un marqueur informatif, jamais utilisé
 * pour choisir un chemin de migration différent.</p>
 *
 * <p><b>Listes connues</b> ({@link #ADDITIVE_LIST_PATHS}) : une liste déjà présente (ex. {@code
 * dialogue.allowed-commands}) n'est <strong>jamais</strong> écrasée, mais reçoit en plus toute
 * entrée du gabarit qui lui manque encore, sans jamais dupliquer une entrée déjà présente. Une
 * liste totalement absente est simplement copiée telle quelle par la complétion générique de clés
 * manquantes ci-dessous — cette fusion additive ne s'applique qu'aux listes déjà partiellement
 * présentes.</p>
 */
public final class ConfigFileCompleter {

    /** Marqueur informatif uniquement (voir Javadoc de classe) — jamais utilisé pour brancher un chemin de migration. */
    static final int CURRENT_VERSION = 1;

    private static final List<String> ADDITIVE_LIST_PATHS = List.of("dialogue.allowed-commands");

    /**
     * Listes d'objets complétées <strong>par clé</strong> : un élément du gabarit dont la clé est
     * absente du fichier est ajouté ; un élément présent n'est jamais modifié (#218 : les paliers 3
     * à 5 du kit de départ arrivent sur un serveur qui n'avait que 1 et 2, sans toucher à ceux-ci).
     */
    private static final Map<String, String> KEYED_LIST_PATHS = Map.of("starter-tool-kit.tiers", "level");

    /**
     * Anciennes valeurs PAR DÉFAUT remplacées par la nouvelle valeur par défaut — seulement si le
     * fichier porte encore exactement l'ancienne valeur livrée. Une valeur choisie par un
     * administrateur (toute autre valeur) n'est jamais touchée.
     *
     * <p>#12, retour du 12/10/2026 : signal PNJ « trop haut et pas assez visible ». Les serveurs
     * existants portaient {@code 2.2} et {@code 20} parce que c'était le défaut, pas un choix.</p>
     */
    private static final List<RetiredDefault> RETIRED_DEFAULTS = List.of(
            new RetiredDefault("npc-hints.height-offset", 2.2, 0.6),
            new RetiredDefault("npc-hints.period-ticks", 20, 10));

    record RetiredDefault(String path, Object oldValue, Object newValue) {

        boolean matches(Object current) {
            if (current instanceof Number n && oldValue instanceof Number o) {
                return Double.compare(n.doubleValue(), o.doubleValue()) == 0;
            }
            return oldValue.equals(current);
        }
    }

    private ConfigFileCompleter() {
    }

    /**
     * @param configFile      {@code config.yml} déjà présent sur disque (voir {@code
     *                        JavaPlugin#saveDefaultConfig()}, appelé avant ceci — garantit son
     *                        existence à l'appel).
     * @param bundledTemplate {@code config.yml} embarqué dans le jar (source de vérité des
     *                        nouvelles clés/valeurs par défaut).
     * @return {@code true} si le fichier a été réécrit (donc rechargé nécessaire côté appelant).
     */
    public static boolean complete(Path configFile, InputStream bundledTemplate, Logger logger) {
        YamlConfiguration existing = YamlConfiguration.loadConfiguration(configFile.toFile());
        YamlConfiguration template;
        try (InputStreamReader reader = new InputStreamReader(bundledTemplate, StandardCharsets.UTF_8)) {
            template = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            logger.error("Impossible de lire le config.yml embarqué (complétion ignorée pour ce démarrage).", e);
            return false;
        }

        boolean changed = mergeMissingKeys(template, existing);
        changed |= mergeAdditiveLists(template, existing);
        changed |= mergeKeyedLists(template, existing, logger);
        changed |= replaceRetiredDefaults(existing, logger);
        if (existing.getInt("config-version", 0) != CURRENT_VERSION) {
            existing.set("config-version", CURRENT_VERSION);
            changed = true;
        }

        if (!changed) {
            return false; // idempotent : déjà à jour, aucune écriture.
        }

        backup(configFile, logger);
        try {
            existing.save(configFile.toFile());
            logger.info("config.yml complété automatiquement (nouvelles clés absentes ajoutées avec leurs valeurs "
                    + "par défaut actuelles, valeurs existantes conservées) — sauvegarde de l'ancien fichier : {}.bak",
                    configFile.getFileName());
        } catch (IOException e) {
            logger.error("Échec de l'écriture de config.yml complété — la configuration en mémoire reste "
                    + "celle du fichier non modifié.", e);
            return false;
        }
        return true;
    }

    /**
     * Copie récursivement toute clé du gabarit absente du fichier réel — jamais une clé déjà
     * présente, quelle que soit sa valeur. Une section entièrement absente est copiée d'un bloc
     * (valeurs imbriquées incluses) ; une section déjà partiellement présente est explorée
     * récursivement pour n'ajouter que ce qui lui manque encore.
     */
    private static boolean mergeMissingKeys(ConfigurationSection template, ConfigurationSection existing) {
        boolean changed = false;
        for (String key : template.getKeys(false)) {
            if (template.isConfigurationSection(key)) {
                if (existing.isConfigurationSection(key)) {
                    changed |= mergeMissingKeys(template.getConfigurationSection(key), existing.getConfigurationSection(key));
                } else if (!existing.isSet(key)) {
                    Map<String, Object> subtree = template.getConfigurationSection(key).getValues(true);
                    existing.createSection(key, subtree);
                    changed = true;
                }
                // Sinon : une valeur non-section existe déjà à ce chemin — ne jamais l'écraser.
            } else if (!existing.isSet(key)) {
                existing.set(key, template.get(key));
                changed = true;
            }
        }
        return changed;
    }

    private static boolean mergeAdditiveLists(YamlConfiguration template, YamlConfiguration existing) {
        boolean anyChanged = false;
        for (String path : ADDITIVE_LIST_PATHS) {
            if (!template.isList(path)) {
                continue;
            }
            List<String> merged = new ArrayList<>(existing.getStringList(path));
            boolean pathChanged = false;
            for (String entry : template.getStringList(path)) {
                if (!merged.contains(entry)) {
                    merged.add(entry);
                    pathChanged = true;
                }
            }
            if (pathChanged) {
                existing.set(path, merged);
                anyChanged = true;
            }
        }
        return anyChanged;
    }

    private static boolean mergeKeyedLists(YamlConfiguration template, YamlConfiguration existing, Logger logger) {
        boolean anyChanged = false;
        for (Map.Entry<String, String> entry : KEYED_LIST_PATHS.entrySet()) {
            String path = entry.getKey();
            String key = entry.getValue();
            if (!template.isList(path) || !existing.isList(path)) {
                continue; // liste absente : déjà copiée telle quelle par mergeMissingKeys
            }
            List<Map<?, ?>> merged = new ArrayList<>(existing.getMapList(path));
            java.util.Set<String> present = new java.util.HashSet<>();
            for (Map<?, ?> item : merged) {
                present.add(String.valueOf(item.get(key)));
            }
            boolean pathChanged = false;
            for (Map<?, ?> item : template.getMapList(path)) {
                String id = String.valueOf(item.get(key));
                if (item.get(key) != null && present.add(id)) {
                    merged.add(item);
                    pathChanged = true;
                    logger.info("config.yml : {} — élément « {} = {} » ajouté depuis les valeurs par défaut.",
                            path, key, id);
                }
            }
            if (pathChanged) {
                existing.set(path, merged);
                anyChanged = true;
            }
        }
        return anyChanged;
    }

    private static boolean replaceRetiredDefaults(YamlConfiguration existing, Logger logger) {
        boolean changed = false;
        for (RetiredDefault retired : RETIRED_DEFAULTS) {
            if (existing.isSet(retired.path()) && retired.matches(existing.get(retired.path()))) {
                existing.set(retired.path(), retired.newValue());
                logger.info("config.yml : {} portait l'ancienne valeur par défaut {} — remplacée par la "
                        + "nouvelle valeur par défaut {}.", retired.path(), retired.oldValue(), retired.newValue());
                changed = true;
            }
        }
        return changed;
    }

    private static void backup(Path configFile, Logger logger) {
        Path backupFile = configFile.resolveSibling(configFile.getFileName() + ".bak");
        try {
            Files.copy(configFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.warn("Impossible de sauvegarder {} avant complétion automatique (poursuite quand même).", configFile, e);
        }
    }
}
