package com.lodygames.rpgquest.web.link;

import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * {@code /lien <code>} (alias {@code /link}, issue #53) : prouve au portail que le joueur contrôle ce
 * compte Minecraft. L'UUID et le pseudo envoyés sont ceux de l'émetteur — aucun argument ne permet
 * d'en choisir un autre. La réponse au joueur ne révèle rien du compte web.
 *
 * <p>Le code n'est jamais journalisé.</p>
 */
public final class LinkCommand implements TabExecutor {

    public static final String PERMISSION = "rpgquest.portal.link";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PortalLinkConfig config;
    private final PortalLinkClient client;
    private final LinkAttemptGuard guard;
    private final Logger logger;

    public LinkCommand(PortalLinkConfig config, PortalLinkClient client, LinkAttemptGuard guard, Logger logger) {
        this.config = config;
        this.client = client;
        this.guard = guard;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MM.deserialize("<red>Cette commande doit être exécutée par un joueur.</red>"));
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(MM.deserialize("<red>Permission manquante.</red>"));
            return true;
        }
        if (!config.enabled() || client == null) {
            player.sendMessage(MM.deserialize("<red>La liaison avec le portail n'est pas activée sur ce serveur.</red>"));
            return true;
        }
        String code = args.length == 1 ? normalize(args[0]) : "";
        if (code.isEmpty()) {
            player.sendMessage(MM.deserialize("<gray>Utilisation :</gray> <white>/" + label + " <code></white> <gray>— le "
                    + "code s'obtient sur le portail, page « Mon compte ».</gray>"));
            return true;
        }
        if (!guard.tryStart(player.getUniqueId())) {
            player.sendMessage(MM.deserialize("<gray>Patiente quelques secondes avant de réessayer.</gray>"));
            return true;
        }
        player.sendMessage(MM.deserialize("<gray>Vérification du code auprès du portail…</gray>"));
        client.confirm(code, player.getUniqueId(), player.getName()).whenComplete((reply, error) -> {
            guard.finish(player.getUniqueId());
            LinkReply result = error != null || reply == null ? LinkReply.UNAVAILABLE : reply;
            logger.info("Liaison portail : {} pour {}.", result, player.getName());
            if (player.isOnline()) {
                player.sendMessage(MM.deserialize(result.playerMessage()));
            }
        });
        return true;
    }

    /** Majuscules, sans espaces ni tirets ; vide si le format ne peut pas être celui d'un code. */
    static String normalize(String raw) {
        String code = raw.replace("-", "").toUpperCase(Locale.ROOT);
        return code.matches("[A-Z0-9]{8}") ? code : "";
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
            String @NotNull [] args) {
        return List.of();
    }
}
