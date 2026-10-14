package com.lodygames.rpgquest.item.delivery;

import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Lecture des commandes {@code customitem give <joueur> <id> [quantité]} écrites dans le contenu
 * (action de dialogue {@code RUN_SAFE_COMMAND}, récompense de quête {@code COMMAND}) — issue #318.
 *
 * <p>Ces commandes partent vers la console : sans cette lecture, le dialogue ou la quête ne
 * saurait pas qu'elles remettent un objet, et ne pourrait pas vérifier la place AVANT d'avancer
 * une variable ou de terminer une quête. La syntaxe reconnue est exactement celle de
 * {@code CustomItemCommand} ; toute autre commande renvoie vide.</p>
 */
public final class CustomItemGiveCommands {

    private static final String DEFAULT_NAMESPACE = "rpgquest";

    private CustomItemGiveCommands() {
    }

    /** L'objet que {@code command} remettrait, ou vide si ce n'est pas un {@code customitem give} valide. */
    public static Optional<ItemStack> itemFor(String command, @Nullable YamlCustomItemRegistry registry) {
        if (registry == null || command == null) {
            return Optional.empty();
        }
        String[] tokens = command.strip().replaceFirst("^/", "").split("\\s+");
        if (tokens.length < 4 || tokens.length > 5
                || !tokens[0].equalsIgnoreCase("customitem") || !tokens[1].equalsIgnoreCase("give")) {
            return Optional.empty();
        }
        NamespacedKey id = resolveId(tokens[3]);
        if (id == null) {
            return Optional.empty();
        }
        int amount = 1;
        if (tokens.length == 5) {
            try {
                amount = Integer.parseInt(tokens[4]);
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return registry.create(id, amount);
    }

    /** {@code pierre_retour} → {@code rpgquest:pierre_retour} ; {@code null} si l'identifiant est invalide. */
    public static @Nullable NamespacedKey resolveId(String raw) {
        return NamespacedKey.fromString(raw.contains(":") ? raw : DEFAULT_NAMESPACE + ":" + raw);
    }
}
