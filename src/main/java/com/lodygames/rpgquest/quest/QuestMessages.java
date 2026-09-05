package com.lodygames.rpgquest.quest;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Modèles de messages MiniMessage chargés depuis {@code messages.yml},
 * indépendant de {@code JavaPlugin} (voir {@link QuestMessagesService} pour
 * le chargement) : testable en JUnit pur ({@link System.Logger} plutôt que le
 * logger du plugin, pour ne pas introduire de dépendance Bukkit ici).
 */
public final class QuestMessages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Logger LOGGER = System.getLogger(QuestMessages.class.getName());

    /**
     * Affiché au joueur à la place d'une clé manquante. {@code format} est utilisé aussi bien pour
     * un Title/Subtitle plein écran que pour l'ActionBar ou le chat (voir {@code QuestProgressEngine})
     * : le nom technique de la clé ne doit donc **jamais** apparaître ici, quel que soit l'appelant —
     * il part uniquement dans les logs serveur, ci-dessous.
     */
    private static final Component MISSING_TEMPLATE_FALLBACK = Component.text("—", NamedTextColor.GRAY);

    private final Map<String, String> templates;

    public QuestMessages(Map<String, String> templates) {
        this.templates = Map.copyOf(templates);
    }

    public Component format(String key, TagResolver... placeholders) {
        String template = templates.get(key);
        if (template == null) {
            LOGGER.log(Level.WARNING,
                    "Clé de message manquante dans messages.yml : \"{0}\" — un joueur voit un texte de "
                            + "remplacement discret à la place ; ajoute cette clé (ou supprime ton "
                            + "messages.yml personnalisé pour le régénérer) pour la corriger.",
                    key);
            return MISSING_TEMPLATE_FALLBACK;
        }
        return MM.deserialize(template, placeholders);
    }
}
