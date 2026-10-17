package com.lodygames.rpgquest.command;

import com.lodygames.rpgquest.config.HudMode;
import com.lodygames.rpgquest.ui.PlayerHudService;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /hud [sidebar|off]} ({@code rpgquest.hud}) — prototype #184 : chaque joueur affiche ou
 * masque le panneau latéral pièces / niveau RPG. Sans argument : bascule.
 */
public final class HudCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "rpgquest.hud";
    private static final List<String> MODES = List.of("sidebar", "off");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PlayerHudService hud;

    public HudCommand(PlayerHudService hud) {
        this.hud = hud;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MM.deserialize("<red>Cette commande doit être exécutée par un joueur.</red>"));
            return true;
        }
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(MM.deserialize("<red>Tu n'as pas la permission d'utiliser cette commande.</red>"));
            return true;
        }
        HudMode target;
        if (args.length == 0) {
            target = hud.mode(player) == HudMode.SIDEBAR ? HudMode.OFF : HudMode.SIDEBAR;
        } else if (MODES.contains(args[0].toLowerCase(Locale.ROOT))) {
            target = HudMode.valueOf(args[0].toUpperCase(Locale.ROOT));
        } else {
            sender.sendMessage(MM.deserialize("<yellow>Usage : /hud [sidebar|off]</yellow>"));
            return true;
        }
        if (!hud.setMode(player, target)) {
            sender.sendMessage(MM.deserialize(
                    "<red>Le panneau latéral est déjà utilisé par un autre affichage du serveur.</red>"));
            return true;
        }
        sender.sendMessage(MM.deserialize(target == HudMode.SIDEBAR
                ? "<green>Panneau pièces / niveau RPG affiché.</green> <gray>/hud off pour le masquer.</gray>"
                : "<gray>Panneau masqué. Les gains et dépenses restent signalés au-dessus de la barre d'action. /hud pour le réafficher.</gray>"));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, String @NotNull [] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return MODES.stream().filter(mode -> mode.startsWith(prefix)).toList();
    }
}
