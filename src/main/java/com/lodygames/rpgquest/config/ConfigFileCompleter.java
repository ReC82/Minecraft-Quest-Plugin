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

    private static void backup(Path configFile, Logger logger) {
        Path backupFile = configFile.resolveSibling(configFile.getFileName() + ".bak");
        try {
            Files.copy(configFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.warn("Impossible de sauvegarder {} avant complétion automatique (poursuite quand même).", configFile, e);
        }
    }
}
