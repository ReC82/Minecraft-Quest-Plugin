package be.lloyd.rpgquest.quest;

import be.lloyd.rpgquest.bootstrap.PluginService;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Charge {@code messages.yml} (copié depuis le jar au premier démarrage, jamais écrasé ensuite). */
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
        ConfigurationSection section = YamlConfiguration.loadConfiguration(file);
        current = new QuestMessages(flatten(section, ""));
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
