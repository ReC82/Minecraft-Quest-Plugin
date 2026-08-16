package com.lodygames.rpgquest.quest;

import com.lodygames.rpgquest.bootstrap.PluginService;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Charge {@code messages.yml} (copié depuis le jar au premier démarrage, jamais écrasé ensuite pour
 * préserver les personnalisations d'un admin). Cette non-réécriture a une conséquence directe : une
 * mise à jour du plugin qui ajoute de nouvelles clés (ex. {@code quest.completed-subtitle}) ne les
 * fait jamais apparaître sur un serveur dont le fichier existe déjà — {@link QuestMessages#format}
 * retombe alors sur un texte de remplacement discret (jamais le nom technique de la clé, voir ce
 * javadoc), avec un avertissement dans les logs serveur. {@link #reload()} corrige la cause en
 * fusionnant dans le fichier disque toute clé présente dans la ressource embarquée mais absente du
 * disque, sans jamais toucher une clé déjà personnalisée.
 */
public final class QuestMessagesService implements PluginService {

    private final JavaPlugin plugin;
    private volatile QuestMessages current;

    public QuestMessagesService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void start() {
        reload();
    }

    @Override
    public void stop() {
        // Rien à libérer : les messages vivent en mémoire, pas de ressource externe.
    }

    public void reload() {
        plugin.saveResource("messages.yml", false);
        File file = new File(plugin.getDataFolder(), "messages.yml");
        YamlConfiguration section = YamlConfiguration.loadConfiguration(file);
        if (mergeMissingKeys(section)) {
            try {
                section.save(file);
            } catch (IOException e) {
                plugin.getSLF4JLogger().error("Impossible de sauvegarder messages.yml après fusion des clés manquantes", e);
            }
        }
        current = new QuestMessages(flatten(section, ""));
    }

    /**
     * Ajoute à {@code disk} toute clé de la ressource embarquée {@code messages.yml} qu'il ne contient
     * pas encore (nouvelle clé apportée par une mise à jour du plugin). Ne modifie jamais une clé déjà
     * présente sur disque, personnalisée ou non.
     *
     * @return {@code true} si au moins une clé a été ajoutée (le fichier disque doit être sauvegardé)
     */
    private boolean mergeMissingKeys(YamlConfiguration disk) {
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in == null) {
                return false;
            }
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            boolean changed = false;
            for (Map.Entry<String, String> entry : flatten(bundled, "").entrySet()) {
                if (!disk.isSet(entry.getKey())) {
                    disk.set(entry.getKey(), entry.getValue());
                    changed = true;
                }
            }
            return changed;
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("Impossible de lire messages.yml embarqué pour la fusion des clés manquantes", e);
            return false;
        }
    }

    public QuestMessages current() {
        return current;
    }

    private Map<String, String> flatten(ConfigurationSection section, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (section.isConfigurationSection(key)) {
                result.putAll(flatten(section.getConfigurationSection(key), path));
            } else {
                result.put(path, section.getString(key));
            }
        }
        return result;
    }
}
