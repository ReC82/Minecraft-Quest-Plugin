package com.lodygames.rpgquest.command;

import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.item.model.CustomItemDefinition;
import com.lodygames.rpgquest.item.model.ItemAttributeSpec;
import com.lodygames.rpgquest.item.model.ItemEnchantmentSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /customitem give|list|inspect}. {@code give}/{@code list} sont des
 * outils d'administration ({@code rpgquest.admin}, comme {@code /quest
 * complete} et {@code /dialogue open}) ; {@code inspect} (identifier
 * l'objet en main) est ouvert à tout joueur ({@code rpgquest.item}), un peu
 * comme {@code /quest progress}.
 */
public final class CustomItemCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "rpgquest.admin";
    private static final String ITEM_PERMISSION = "rpgquest.item";
    private static final String DEFAULT_NAMESPACE = "rpgquest";
    private static final List<String> TOP_LEVEL_SUBCOMMANDS = List.of("give", "list", "inspect");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final YamlCustomItemRegistry registry;

    public CustomItemCommand(YamlCustomItemRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String @NotNull [] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "give" -> handleGive(sender, args);
            case "list" -> handleList(sender);
            case "inspect" -> handleInspect(sender);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (!hasPermission(sender, ADMIN_PERMISSION)) {
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

        NamespacedKey id = resolveId(args[2]);
        if (id == null) {
            sender.sendMessage(MM.deserialize(
                    "<red>ID invalide :</red> <white><id></white>", Placeholder.unparsed("id", args[2])));
            return;
        }
        Optional<CustomItemDefinition> definition = registry.find(id);
        if (definition.isEmpty()) {
            sender.sendMessage(MM.deserialize(
                    "<red>ID d'objet inconnu :</red> <white><id></white>", Placeholder.unparsed("id", id.toString())));
            return;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(MM.deserialize(
                        "<red>Quantité invalide (nombre attendu) :</red> <white><amount></white>",
                        Placeholder.unparsed("amount", args[3])));
                return;
            }
        }

        Optional<ItemStack> stack = registry.create(id, amount);
        if (stack.isEmpty()) {
            sender.sendMessage(MM.deserialize(
                    "<red>Quantité invalide :</red> <white><amount></white> <gray>(doit être entre 1 et <max>)</gray>",
                    Placeholder.unparsed("amount", String.valueOf(amount)),
                    Placeholder.unparsed("max", String.valueOf(definition.get().effectiveMaxStackSize()))));
            return;
        }

        Map<Integer, ItemStack> leftover = target.getInventory().addItem(stack.get());
        leftover.values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));

        sender.sendMessage(MM.deserialize(
                "<green>Donné</green> <white><amount>x <id></white> <green>à</green> <white><player></white>",
                Placeholder.unparsed("amount", String.valueOf(amount)),
                Placeholder.unparsed("id", id.toString()),
                Placeholder.unparsed("player", target.getName())));
        if (!target.equals(sender)) {
            target.sendMessage(MM.deserialize(
                    "<green>Vous avez reçu</green> <white><amount>x <id></white>",
                    Placeholder.unparsed("amount", String.valueOf(amount)),
                    Placeholder.unparsed("id", id.toString())));
        }
    }

    private void handleList(CommandSender sender) {
        if (!hasPermission(sender, ADMIN_PERMISSION)) {
            return;
        }
        List<CustomItemDefinition> items = registry.items();
        if (items.isEmpty()) {
            sender.sendMessage(MM.deserialize("<gray>Aucun objet personnalisé chargé.</gray>"));
            return;
        }
        sender.sendMessage(MM.deserialize(
                "<gold><count></gold> <gray>objet(s) personnalisé(s) chargé(s) :</gray>",
                Placeholder.unparsed("count", String.valueOf(items.size()))));
        for (CustomItemDefinition item : items) {
            sender.sendMessage(MM.deserialize(
                    "<yellow>- <id></yellow> <gray>(<type>, <rarity>)</gray>",
                    Placeholder.unparsed("id", item.id().toString()),
                    Placeholder.unparsed("type", item.type().name()),
                    Placeholder.unparsed("rarity", item.rarity().name())));
        }
    }

    private void handleInspect(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sendPlayerOnly(sender);
            return;
        }
        if (!hasPermission(sender, ITEM_PERMISSION)) {
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        Optional<NamespacedKey> idOpt = registry.identify(held);
        if (idOpt.isEmpty()) {
            sender.sendMessage(MM.deserialize("<gray>L'objet en main n'est pas un objet personnalisé RPGQuest.</gray>"));
            return;
        }

        Optional<CustomItemDefinition> definitionOpt = registry.find(idOpt.get());
        if (definitionOpt.isEmpty()) {
            sender.sendMessage(MM.deserialize(
                    "<yellow>Objet personnalisé reconnu</yellow> <white><id></white> "
                            + "<red>mais sa définition n'est plus chargée</red> <gray>(reload ?).</gray>",
                    Placeholder.unparsed("id", idOpt.get().toString())));
            return;
        }

        CustomItemDefinition item = definitionOpt.get();
        sender.sendMessage(MM.deserialize(
                "<gold>=== <id> ===</gold>", Placeholder.unparsed("id", item.id().toString())));
        sender.sendMessage(MM.deserialize(
                "<white>Type :</white> <gray><type></gray>", Placeholder.unparsed("type", item.type().name())));
        sender.sendMessage(MM.deserialize(
                "<white>Matériau :</white> <gray><material></gray>", Placeholder.unparsed("material", item.baseMaterial().name())));
        sender.sendMessage(MM.deserialize(
                "<white>Rareté :</white> " + item.rarity().colorTag() + "<rarity>" + item.rarity().colorTag().replace("<", "</"),
                Placeholder.unparsed("rarity", item.rarity().name())));
        sender.sendMessage(MM.deserialize(
                "<white>Empilable :</white> <gray><stackable> (max <max>)</gray>",
                Placeholder.unparsed("stackable", item.stackable() ? "oui" : "non"),
                Placeholder.unparsed("max", String.valueOf(item.effectiveMaxStackSize()))));
        if (item.maxDurability() != null) {
            sender.sendMessage(MM.deserialize(
                    "<white>Durabilité max :</white> <gray><durability></gray>",
                    Placeholder.unparsed("durability", String.valueOf(item.maxDurability()))));
        }
        for (ItemAttributeSpec attribute : item.attributes()) {
            sender.sendMessage(MM.deserialize(
                    "<white>Attribut :</white> <gray><attribute> <amount> (<operation>, <slot>)</gray>",
                    Placeholder.unparsed("attribute", attribute.attribute().getKey().getKey()),
                    Placeholder.unparsed("amount", String.valueOf(attribute.amount())),
                    Placeholder.unparsed("operation", attribute.operation().name()),
                    Placeholder.unparsed("slot", attribute.slot().toString())));
        }
        for (ItemEnchantmentSpec enchantment : item.enchantments()) {
            sender.sendMessage(MM.deserialize(
                    "<white>Enchantement :</white> <gray><enchant> <level></gray>",
                    Placeholder.unparsed("enchant", enchantment.enchantment().getKey().getKey()),
                    Placeholder.unparsed("level", String.valueOf(enchantment.level()))));
        }
        if (!item.gameplayTags().isEmpty()) {
            sender.sendMessage(MM.deserialize(
                    "<white>Tags :</white> <gray><tags></gray>",
                    Placeholder.unparsed("tags", String.join(", ", item.gameplayTags()))));
        }
    }

    private @Nullable NamespacedKey resolveId(String raw) {
        return raw.contains(":") ? NamespacedKey.fromString(raw) : new NamespacedKey(DEFAULT_NAMESPACE, raw);
    }

    private boolean hasPermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage(MM.deserialize(
                "<red>Permission manquante :</red> <white><permission></white>", Placeholder.unparsed("permission", permission)));
        return false;
    }

    private void sendPlayerOnly(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<red>Cette commande doit être exécutée par un joueur.</red>"));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize(
                "<yellow>/customitem give <joueur> <id> [quantité]</yellow> <gray>- donne un objet (rpgquest.admin)</gray>"));
        sender.sendMessage(MM.deserialize(
                "<yellow>/customitem list</yellow> <gray>- liste les objets chargés (rpgquest.admin)</gray>"));
        sender.sendMessage(MM.deserialize(
                "<yellow>/customitem inspect</yellow> <gray>- identifie l'objet en main (rpgquest.item)</gray>"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            return TOP_LEVEL_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[1].toLowerCase();
            return sender.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[2].toLowerCase();
            return registry.items().stream()
                    .map(item -> item.id().toString())
                    .filter(id -> id.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
