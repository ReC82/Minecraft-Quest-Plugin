package be.lloyd.rpgquest.quest;

import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Modèles de messages MiniMessage chargés depuis {@code messages.yml},
 * indépendant de {@code JavaPlugin} (voir {@link QuestMessagesService} pour
 * le chargement) : testable en JUnit pur.
 */
public final class QuestMessages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<String, String> templates;

    public QuestMessages(Map<String, String> templates) {
        this.templates = Map.copyOf(templates);
    }

    public Component format(String key, TagResolver... placeholders) {
        String template = templates.get(key);
        if (template == null) {
            return Component.text("[message manquant : " + key + "]");
        }
        return MM.deserialize(template, placeholders);
    }
}
