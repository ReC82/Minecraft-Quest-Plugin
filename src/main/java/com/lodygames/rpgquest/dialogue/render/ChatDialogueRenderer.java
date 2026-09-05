package com.lodygames.rpgquest.dialogue.render;

import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import com.lodygames.rpgquest.dialogue.model.DialogueNode;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

/**
 * Renderer de secours : dialogue affiché dans le chat, choix sous forme de
 * lignes numérotées cliquables ({@link ClickEvent#callback}) — fonctionne
 * avec n'importe quel client, aucune API expérimentale.
 *
 * <p>{@code speaker}/{@code text}/le libellé de chaque choix viennent du
 * YAML rédigé par l'administrateur — même niveau de confiance que
 * {@code title}/{@code description} d'une quête, jamais du texte joueur. Ils
 * sont donc désérialisés directement en MiniMessage ({@link MiniMessage#deserialize(String)})
 * puis complétés par une couleur/mise en forme par défaut via
 * {@link Component#colorIfAbsent} (qui ne touche jamais une couleur déjà
 * posée par l'auteur) — jamais via {@code Placeholder.unparsed}, qui
 * traiterait les balises comme du texte brut et les afficherait littéralement
 * au joueur (ex. {@code <white>...</white>} tel quel).</p>
 */
public final class ChatDialogueRenderer implements DialogueRenderer {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final DialogueChoiceHandler handler;

    public ChatDialogueRenderer(DialogueChoiceHandler handler) {
        this.handler = handler;
    }

    @Override
    public void render(Player player, DialogueDefinition dialogue, DialogueNode node, List<VisibleChoice> visibleChoices) {
        player.sendMessage(MM.deserialize(node.speaker())
                .colorIfAbsent(NamedTextColor.GOLD)
                .decorationIfAbsent(TextDecoration.BOLD, TextDecoration.State.TRUE));
        player.sendMessage(MM.deserialize(node.text().base()).colorIfAbsent(NamedTextColor.GRAY));

        for (VisibleChoice choice : visibleChoices) {
            Component number = Component.text((choice.index() + 1) + ". ", NamedTextColor.YELLOW);
            Component label = MM.deserialize(choice.label()).colorIfAbsent(NamedTextColor.WHITE);
            Component line = number.append(label);
            player.sendMessage(line.clickEvent(ClickEvent.callback(
                    audience -> handler.onChoiceSelected(player, dialogue.id(), node.id(), choice.index()))));
        }
    }
}
