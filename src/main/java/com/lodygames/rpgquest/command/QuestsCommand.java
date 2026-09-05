package com.lodygames.rpgquest.command;

import com.lodygames.rpgquest.ui.QuestJournalService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** {@code /quests} : ouvre le journal de quêtes (menu paginé) pour le joueur qui l'exécute. */
public final class QuestsCommand implements CommandExecutor {

    private static final String PERMISSION = "rpgquest.quest";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final QuestJournalService journalService;

    public QuestsCommand(QuestJournalService journalService) {
        this.journalService = journalService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MM.deserialize("<red>Cette commande doit être exécutée par un joueur.</red>"));
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            sender.sendMessage(MM.deserialize(
                    "<red>Permission manquante :</red> <white><permission></white>",
                    Placeholder.unparsed("permission", PERMISSION)));
            return true;
        }
        journalService.open(player);
        return true;
    }
}
