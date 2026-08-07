package be.lloyd.rpgquest.command;

import be.lloyd.rpgquest.progression.ProgressionService;
import be.lloyd.rpgquest.progression.model.SkillType;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /skills} ({@code rpgquest.progression}) — détail par compétence
 * (XP dans le niveau courant / XP requise pour le suivant), et
 * {@code /skills admin grant|set <joueur> <compétence> <montant>}
 * ({@code rpgquest.admin}, outil de test — mission point 8) même
 * conception que {@code /money admin give|take|set}.
 */
public final class SkillsCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "rpgquest.progression";
    private static final String ADMIN_PERMISSION = "rpgquest.admin";
    private static final List<String> TOP_LEVEL_SUBCOMMANDS = List.of("admin");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("grant", "set");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ProgressionService progression;

    public SkillsCommand(ProgressionService progression) {
        this.progression = progression;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String @NotNull [] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            handleAdmin(sender, args);
            return true;
        }
        handleDetail(sender);
        return true;
    }

    private void handleDetail(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MM.deserialize("<red>Cette commande doit être exécutée par un joueur.</red>"));
            return;
        }
        if (!hasPermission(sender, PERMISSION)) {
            return;
        }

        player.sendMessage(MM.deserialize("<gold>=== Compétences de <name> ===</gold>",
                Placeholder.unparsed("name", player.getName())));
        for (SkillType skill : SkillType.values()) {
            long totalXp = progression.totalXp(player.getUniqueId(), skill);
            int level = progression.curve().levelForTotalXp(totalXp);
            long xpIntoLevel = progression.curve().xpIntoCurrentLevel(totalXp);
            long xpToNext = progression.curve().xpToNextLevel(level);
            String progressText = xpToNext <= 0
                    ? "<gray>(niveau maximal)</gray>"
                    : "<gray>(<into>/<next> XP)</gray>";
            player.sendMessage(MM.deserialize(
                    "<white><skill></white> <gray>: niveau</gray> <yellow><level></yellow> " + progressText,
                    Placeholder.unparsed("skill", ProfileCommand.label(skill)),
                    Placeholder.unparsed("level", String.valueOf(level)),
                    Placeholder.unparsed("into", String.valueOf(xpIntoLevel)),
                    Placeholder.unparsed("next", String.valueOf(xpToNext))));
        }
    }

    // ---- Admin ----------------------------------------------------------------------------

    private void handleAdmin(CommandSender sender, String[] args) {
        if (!hasPermission(sender, ADMIN_PERMISSION)) {
            return;
        }
        if (args.length < 5) {
            sendUsage(sender);
            return;
        }
        Player target = resolveTarget(sender, args[2]);
        if (target == null) {
            return;
        }
        SkillType skill = parseSkill(sender, args[3]);
        if (skill == null) {
            return;
        }
        Long amount = parseAmount(sender, args[4]);
        if (amount == null) {
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "grant" -> progression.awardXp(target.getUniqueId(), skill, amount, "admin_grant", "admin:" + sender.getName() + ":" + System.nanoTime())
                    .thenAccept(result -> sender.sendMessage(MM.deserialize(
                            "<green><amount> XP (<skill>) accordée(s) à</green> <white><player></white> <gray>(<outcome>)</gray>",
                            Placeholder.unparsed("amount", String.valueOf(amount)), Placeholder.unparsed("skill", ProfileCommand.label(skill)),
                            Placeholder.unparsed("player", target.getName()), Placeholder.unparsed("outcome", result.outcome().name()))));
            case "set" -> progression.setTotalXp(target.getUniqueId(), skill, amount)
                    .thenRun(() -> sender.sendMessage(MM.deserialize(
                            "<green>XP totale (<skill>) de <player> fixée à</green> <white><amount></white>",
                            Placeholder.unparsed("skill", ProfileCommand.label(skill)),
                            Placeholder.unparsed("player", target.getName()), Placeholder.unparsed("amount", String.valueOf(amount)))));
            default -> sendUsage(sender);
        }
    }

    private @Nullable Player resolveTarget(CommandSender sender, String name) {
        Player target = sender.getServer().getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(MM.deserialize(
                    "<red>Joueur introuvable ou hors-ligne :</red> <white><name></white>", Placeholder.unparsed("name", name)));
        }
        return target;
    }

    private @Nullable SkillType parseSkill(CommandSender sender, String raw) {
        try {
            return SkillType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MM.deserialize(
                    "<red>Compétence invalide :</red> <white><skill></white>", Placeholder.unparsed("skill", raw)));
            return null;
        }
    }

    private @Nullable Long parseAmount(CommandSender sender, String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value < 0) {
                sender.sendMessage(MM.deserialize("<red>Montant invalide (ne peut pas être négatif).</red>"));
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            sender.sendMessage(MM.deserialize(
                    "<red>Montant invalide (nombre attendu) :</red> <white><amount></white>", Placeholder.unparsed("amount", raw)));
            return null;
        }
    }

    private boolean hasPermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage(MM.deserialize(
                "<red>Permission manquante :</red> <white><permission></white>", Placeholder.unparsed("permission", permission)));
        return false;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<yellow>/skills</yellow> <gray>- détail de tes compétences</gray>"));
        sender.sendMessage(MM.deserialize(
                "<yellow>/skills admin grant|set <joueur> <compétence> <montant></yellow> <gray>- (rpgquest.admin)</gray>"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            return TOP_LEVEL_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return ADMIN_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return sender.getServer().getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin")) {
            String prefix = args[3].toLowerCase(Locale.ROOT);
            return java.util.Arrays.stream(SkillType.values()).map(s -> s.name().toLowerCase(Locale.ROOT))
                    .filter(name -> name.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
