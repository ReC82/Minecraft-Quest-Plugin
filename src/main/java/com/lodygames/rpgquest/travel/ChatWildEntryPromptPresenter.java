package com.lodygames.rpgquest.travel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

/**
 * Repli stable de {@link WildEntryPromptPresenter} : l'avertissement est envoyé dans le chat, les
 * boutons sous forme de libellés cliquables ({@link ClickEvent#callback}) — compatible avec
 * n'importe quel client, aucune API expérimentale, jamais un titre plein écran.
 *
 * <p>Chaque callback est à <strong>usage unique</strong> (valeur par défaut d'Adventure) : un
 * double-clic rapide ne déclenche jamais deux départs. Ignorer le message revient à annuler —
 * rigoureusement rien n'est exécuté sans clic.</p>
 */
public final class ChatWildEntryPromptPresenter implements WildEntryPromptPresenter {

    @Override
    public void present(Player player, WildEntryPrompt prompt) {
        player.sendMessage(prompt.title()
                .colorIfAbsent(NamedTextColor.GOLD)
                .decorationIfAbsent(TextDecoration.BOLD, TextDecoration.State.TRUE));
        prompt.body().forEach(line -> player.sendMessage(line.colorIfAbsent(NamedTextColor.GRAY)));

        Component buttons = Component.empty();
        boolean first = true;
        for (WildEntryPromptButton button : prompt.buttons()) {
            if (!first) {
                buttons = buttons.append(Component.text("  "));
            }
            first = false;
            buttons = buttons.append(Component.text("[", NamedTextColor.DARK_GRAY)
                    .append(button.label().colorIfAbsent(NamedTextColor.WHITE))
                    .append(Component.text("]", NamedTextColor.DARK_GRAY))
                    .clickEvent(ClickEvent.callback(audience -> button.action().run())));
        }
        player.sendMessage(buttons);
    }
}
