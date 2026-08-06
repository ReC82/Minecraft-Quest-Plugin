package be.lloyd.rpgquest.command;

import be.lloyd.rpgquest.dialogue.session.DialogueSessionEngine;
import java.util.List;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /dialogue open <joueur> <dialogueId>} (admin) déclenche un dialogue
 * à distance — utile pour les tests ou en l'absence de tout PNJ cliquable.
 * La sélection des choix eux-mêmes ne passe par aucune commande : le
 * renderer de secours ({@code ChatDialogueRenderer}) utilise
 * {@code ClickEvent.callback}, un mécanisme Adventure entièrement en
 * mémoire côté serveur (pas de round-trip commande, pas de texte joueur à
 * parser).
 */
public final class DialogueCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "rpgquest.admin";
    private static final String DEFAULT_NAMESPACE = "rpgquest";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final DialogueSessionEngine sessionEngine;

    public DialogueCommand(DialogueSessionEngine sessionEngine) {
        this.sessionEngine = sessionEngine;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String @NotNull [] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("open")) {
            handleOpen(sender, args);
            return true;
        }
        sendUsage(sender);
        return true;
    }

    private void handleOpen(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(MM.deserialize(
                    "<red>Permission manquante :</red> <white><permission></white>",
                    Placeholder.unparsed("permission", ADMIN_PERMISSION)));
            return;
        }
        if (args.length < 3) {
            sendUsage(sender);
            return;
        }

        Player target = sender.getServer().getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(MM.deserialize(
                    "<red>Joueur introuvable ou hors-ligne :</red> <white><name></white>",
                    Placeholder.unparsed("name", args[1])));
            return;
        }

        NamespacedKey dialogueId = args[2].contains(":")
                ? NamespacedKey.fromString(args[2])
                : new NamespacedKey(DEFAULT_NAMESPACE, args[2]);
        if (dialogueId == null) {
            sender.sendMessage(MM.deserialize(
                    "<red>Dialogue introuvable :</red> <white><id></white>",
                    Placeholder.unparsed("id", args[2])));
            return;
        }

        sessionEngine.open(target, dialogueId);
        sender.sendMessage(MM.deserialize(
                "<green>Dialogue ouvert pour</green> <white><name></white>",
                Placeholder.unparsed("name", target.getName())));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize(
                "<yellow>/dialogue open <joueur> <dialogueId></yellow> <gray>- ouvre un dialogue (rpgquest.admin)</gray>"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            return "open".startsWith(args[0].toLowerCase()) ? List.of("open") : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("open")) {
            String prefix = args[1].toLowerCase();
            return sender.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
