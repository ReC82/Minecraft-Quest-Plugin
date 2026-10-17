package com.lodygames.rpgquest.storage;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Branche {@code /rpgadmin vault …} (issue #105) — liaison des coffres physiques des Hubs aux
 * coffres personnels. Déléguée par {@code admin.RpgAdminCommand} (droit : ombrelle
 * {@code rpgquest.admin.world}, comme les autres branches non découpées). Le bloc visé (5 blocs au
 * plus) est la seule entrée : aucune coordonnée codée en dur, aucun accès au contenu d'un joueur.
 */
public final class VaultAdminCommand {

    public static final List<String> SUBCOMMANDS = List.of("bind", "unbind", "info", "list");
    private static final int TARGET_RANGE = 5;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final VaultAccessPointService service;

    public VaultAdminCommand(VaultAccessPointService service) {
        this.service = service;
    }

    /** {@code args[0]} vaut {@code vault}. */
    public void handle(Player player, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "bind" -> bind(player, args);
            case "unbind" -> unbind(player, args);
            case "info" -> info(player);
            case "list" -> list(player);
            default -> sendUsage(player);
        }
    }

    public List<String> tabComplete(String[] args) {
        if (args.length == 2) {
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("bind")) {
            return service.all().stream().map(p -> p.vaultId().value()).distinct()
                    .filter(id -> id.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

    public static void sendUsage(Player player) {
        player.sendMessage(MM.deserialize("<yellow>/rpgadmin vault bind [vault_id]</yellow> <gray>- lie le coffre visé "
                + "au coffre personnel (défaut vault_01)</gray>"));
        player.sendMessage(MM.deserialize("<yellow>/rpgadmin vault unbind [x y z]</yellow> <gray>- délie le coffre "
                + "visé (ou cette position) ; le contenu des joueurs est conservé</gray>"));
        player.sendMessage(MM.deserialize("<yellow>/rpgadmin vault info</yellow> <gray>- liaison du bloc visé</gray>"));
        player.sendMessage(MM.deserialize("<yellow>/rpgadmin vault list</yellow> <gray>- tous les points d'accès</gray>"));
    }

    private void bind(Player player, String[] args) {
        VaultId vaultId;
        try {
            vaultId = args.length > 2 ? VaultId.of(args[2].toLowerCase(Locale.ROOT)) : VaultId.DEFAULT;
        } catch (IllegalArgumentException invalid) {
            player.sendMessage(MM.deserialize("<red>Identifiant invalide : lettres minuscules, chiffres et _ "
                    + "(32 caractères au plus), par exemple vault_01.</red>"));
            return;
        }
        Optional<Block> target = target(player);
        if (target.isEmpty()) {
            return;
        }
        Block block = target.get();
        service.bind(block, vaultId, player.getUniqueId()).thenAccept(outcome -> player.sendMessage(MM.deserialize(
                switch (outcome) {
                    case BOUND -> "<green>Coffre lié à <white><vault></white> : chaque joueur y trouvera son propre "
                            + "coffre personnel <white><vault></white>.</green> <gray><pos></gray>";
                    case REBOUND -> "<green>Coffre relié à <white><vault></white>.</green> <gray>Aucun contenu "
                            + "de joueur n'a été déplacé. <pos></gray>";
                    case ALREADY_BOUND -> "<yellow>Ce coffre est déjà lié à <white><vault></white>.</yellow>";
                    case UNSUPPORTED_BLOCK -> "<red>Bloc non pris en charge : vise un coffre, un coffre piégé, "
                            + "un tonneau ou un coffre de l'Ender.</red>";
                    case NOT_EMPTY -> "<red>Ce coffre contient des objets : vide-le d'abord (son contenu "
                            + "physique ne serait plus jamais visible).</red>";
                    case FORBIDDEN_WORLD -> "<red>Interdit dans ce monde : les coffres personnels ne s'ouvrent "
                            + "pas depuis le Wild ni le monde des claims.</red>";
                    case NOT_LOADED -> "<red>Les points d'accès ne sont pas encore chargés ; réessaie dans un "
                            + "instant.</red>";
                    case FAILED -> "<red>Échec d'écriture en base ; rien n'a changé (voir la console).</red>";
                },
                Placeholder.unparsed("vault", vaultId.value()), Placeholder.unparsed("pos", describe(block)))));
    }

    private void unbind(Player player, String[] args) {
        Block block;
        if (args.length >= 5) {
            try {
                block = player.getWorld().getBlockAt(Integer.parseInt(args[2]), Integer.parseInt(args[3]),
                        Integer.parseInt(args[4]));
            } catch (NumberFormatException invalid) {
                player.sendMessage(MM.deserialize("<red>Coordonnées invalides : /rpgadmin vault unbind x y z</red>"));
                return;
            }
        } else {
            Optional<Block> target = target(player);
            if (target.isEmpty()) {
                return;
            }
            block = target.get();
        }
        service.unbind(block).thenAccept(outcome -> player.sendMessage(MM.deserialize(
                switch (outcome) {
                    case UNBOUND -> "<green>Point d'accès délié <gray><pos></gray>. Le contenu des joueurs est "
                            + "conservé et reste accessible depuis les autres points liés.</green>";
                    case NOT_BOUND -> "<yellow>Aucun point d'accès à cette position <gray><pos></gray>.</yellow>";
                    case FAILED -> "<red>Échec d'écriture en base ; rien n'a changé (voir la console).</red>";
                },
                Placeholder.unparsed("pos", describe(block)))));
    }

    private void info(Player player) {
        target(player).ifPresent(block -> player.sendMessage(service.vaultAt(block)
                .map(vaultId -> MM.deserialize("<green>Point d'accès vers <white><vault></white></green> <gray><pos></gray>",
                        Placeholder.unparsed("vault", vaultId.value()), Placeholder.unparsed("pos", describe(block))))
                .orElseGet(() -> MM.deserialize("<gray>Bloc non lié (comportement vanilla) <pos></gray>",
                        Placeholder.unparsed("pos", describe(block))))));
    }

    private void list(Player player) {
        List<VaultAccessPointService.AccessPoint> points = service.all();
        if (points.isEmpty()) {
            player.sendMessage(MM.deserialize("<gray>Aucun point d'accès aux coffres personnels.</gray>"));
            return;
        }
        player.sendMessage(MM.deserialize("<gold>Points d'accès (<count>) :</gold>",
                Placeholder.unparsed("count", String.valueOf(points.size()))));
        for (VaultAccessPointService.AccessPoint point : points) {
            player.sendMessage(MM.deserialize("<gray>- <white><vault></white> : <pos></gray>",
                    Placeholder.unparsed("vault", point.vaultId().value()),
                    Placeholder.unparsed("pos", point.world() + " " + point.x() + " " + point.y() + " " + point.z())));
        }
    }

    private static Optional<Block> target(Player player) {
        Block block = player.getTargetBlockExact(TARGET_RANGE);
        if (block == null || block.getType().isAir()) {
            player.sendMessage(MM.deserialize("<red>Vise un bloc à " + TARGET_RANGE + " blocs au plus.</red>"));
            return Optional.empty();
        }
        return Optional.of(block);
    }

    private static String describe(Block block) {
        return block.getWorld().getName() + " " + block.getX() + " " + block.getY() + " " + block.getZ();
    }
}
