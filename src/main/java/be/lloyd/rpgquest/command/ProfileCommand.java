package be.lloyd.rpgquest.command;

import be.lloyd.rpgquest.progression.ProgressionService;
import be.lloyd.rpgquest.progression.model.SkillType;
import java.util.List;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /profile} ({@code rpgquest.progression}) — résumé compact d'une
 * ligne par compétence RPG (mission étape 19, point 8). Distinct de
 * {@code /rpgquest profile [joueur]} (identité/compte, étape 1) : ici
 * exclusivement le profil de progression de l'exécutant, comme
 * {@code /quest progress} pour les quêtes.
 */
public final class ProfileCommand implements CommandExecutor {

    private static final String PERMISSION = "rpgquest.progression";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ProgressionService progression;

    public ProfileCommand(ProgressionService progression) {
        this.progression = progression;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MM.deserialize("<red>Cette commande doit être exécutée par un joueur.</red>"));
            return true;
        }
        if (!hasPermission(sender)) {
            return true;
        }

        player.sendMessage(MM.deserialize("<gold>=== Profil RPG de <name> ===</gold>",
                Placeholder.unparsed("name", player.getName())));
        for (SkillType skill : SkillType.values()) {
            int level = progression.level(player.getUniqueId(), skill);
            player.sendMessage(MM.deserialize(
                    "<white><skill></white> <gray>: niveau</gray> <yellow><level></yellow>",
                    Placeholder.unparsed("skill", label(skill)), Placeholder.unparsed("level", String.valueOf(level))));
        }
        return true;
    }

    static String label(SkillType skill) {
        return switch (skill) {
            case GLOBAL -> "Global";
            case COMBAT -> "Combat";
            case MINING -> "Minage";
            case FARMING -> "Agriculture";
            case FISHING -> "Pêche";
            case EXPLORATION -> "Exploration";
        };
    }

    private boolean hasPermission(CommandSender sender) {
        if (sender.hasPermission(PERMISSION)) {
            return true;
        }
        sender.sendMessage(MM.deserialize(
                "<red>Permission manquante :</red> <white><permission></white>", Placeholder.unparsed("permission", PERMISSION)));
        return false;
    }
}
