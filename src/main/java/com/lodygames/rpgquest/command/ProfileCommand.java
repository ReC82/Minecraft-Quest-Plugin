package com.lodygames.rpgquest.command;

import com.lodygames.rpgquest.player.PublicProfilePolicy;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.progression.model.SkillType;
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

    private com.lodygames.rpgquest.database.PlayerVariableRepository variables;
    private java.util.function.Supplier<PublicProfilePolicy.Visibility> visibilityDefault =
            () -> PublicProfilePolicy.Visibility.PRIVATE;

    public ProfileCommand(ProgressionService progression) {
        this.progression = progression;
    }

    /** Issue #55 — sans elle, « /profile web » répond que le réglage est indisponible. */
    public void setVariables(com.lodygames.rpgquest.database.PlayerVariableRepository variables) {
        this.variables = variables;
    }

    /** Issue #55 — {@code web-export.profile-visibility-default}, relu à chaque commande (reload). */
    public void setVisibilityDefault(java.util.function.Supplier<PublicProfilePolicy.Visibility> visibilityDefault) {
        this.visibilityDefault = visibilityDefault;
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

        if (args.length >= 1 && args[0].equalsIgnoreCase("web")) {
            return web(player, args.length >= 2 ? args[1] : "");
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

    /**
     * {@code /profile web [public|prive]} (issue #55) : afficher ou choisir la visibilité du profil
     * sur le portail web. Masqué, le joueur n'apparaît dans aucun classement ni profil public dès
     * le prochain export.
     */
    private boolean web(Player player, String choice) {
        if (variables == null) {
            player.sendMessage(MM.deserialize("<red>Ce réglage n'est pas disponible sur ce serveur.</red>"));
            return true;
        }
        String key = PublicProfilePolicy.HIDDEN_KEY;
        switch (choice.toLowerCase(java.util.Locale.ROOT)) {
            case "prive", "privé", "private" -> variables.set(player.getUniqueId(), key, PublicProfilePolicy.HIDDEN_VALUE)
                    .thenRun(() -> player.sendMessage(MM.deserialize("<green>Profil web masqué.</green> <gray>Tu "
                            + "n'apparaîtras plus dans les classements ni les profils publics du portail, dès sa "
                            + "prochaine mise à jour.</gray>")));
            case "public" -> variables.set(player.getUniqueId(), key, PublicProfilePolicy.PUBLIC_VALUE)
                    .thenRun(() -> player.sendMessage(MM.deserialize("<green>Profil web public.</green> <gray>Ton "
                            + "pseudo, l'XP de tes pistes et tes quêtes terminées peuvent apparaître sur le portail. "
                            + "« /profile web prive » pour revenir en arrière.</gray>")));
            default -> variables.get(player.getUniqueId(), key).thenAccept(value -> {
                boolean visible = PublicProfilePolicy.isVisible(value, visibilityDefault.get());
                player.sendMessage(MM.deserialize(statusMessage(visible, value.isEmpty())));
            });
        }
        return true;
    }

    static String statusMessage(boolean visible, boolean neverChosen) {
        String origin = neverChosen ? " <dark_gray>(réglage par défaut du serveur)</dark_gray>" : "";
        return visible
                ? "<gray>Ton profil web est</gray> <yellow>public</yellow>" + origin + " <gray>(pseudo, XP des pistes, "
                        + "quêtes terminées). « /profile web prive » pour le masquer.</gray>"
                : "<gray>Ton profil web est</gray> <yellow>masqué</yellow>" + origin + "<gray>. « /profile web public » "
                        + "pour le montrer.</gray>";
    }

    static String label(SkillType skill) {
        return skill.label();
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
