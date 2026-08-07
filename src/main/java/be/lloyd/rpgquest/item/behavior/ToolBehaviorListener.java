package be.lloyd.rpgquest.item.behavior;

import be.lloyd.rpgquest.item.YamlCustomItemRegistry;
import be.lloyd.rpgquest.item.model.CustomItemDefinition;
import be.lloyd.rpgquest.item.model.ToolBehavior;
import be.lloyd.rpgquest.item.model.ToolSpecialAbility;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.slf4j.Logger;

/**
 * Comportement des outils personnalisés ({@code tool:} dans le YAML), en
 * trois volets indépendants — mêmes garde-fous que {@link WeaponBehaviorListener}
 * (événements ignorés si déjà annulés, main secondaire jamais consultée,
 * identification exclusivement via {@link YamlCustomItemRegistry#resolve}) :
 * <ul>
 *     <li>{@link #onItemDamage} — consommation de durabilité personnalisée,
 *     en <b>remplaçant</b> {@link PlayerItemDamageEvent#setDamage} (jamais
 *     un appel additionnel), donc jamais appliquée deux fois.</li>
 *     <li>{@link #onBlockDropItem} — bonus de récolte, limité aux blocs
 *     autorisés ({@link ToolBehavior#appliesTo}), en dupliquant les objets
 *     déjà déposés par le jeu (fortune/silk touch déjà pris en compte)
 *     plutôt que de deviner la table de butin.</li>
 *     <li>{@link #onInteract} — capacité spéciale à cooldown, déclenchée par
 *     un clic droit en main principale uniquement
 *     ({@code event.getHand() == EquipmentSlot.HAND}).</li>
 * </ul>
 */
public final class ToolBehaviorListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final YamlCustomItemRegistry registry;
    private final CooldownManager cooldowns;
    private final BooleanSupplier debugEnabled;
    private final Logger logger;
    private final Random random;

    public ToolBehaviorListener(YamlCustomItemRegistry registry, CooldownManager cooldowns,
                                 BooleanSupplier debugEnabled, Logger logger) {
        this(registry, cooldowns, debugEnabled, logger, new Random());
    }

    ToolBehaviorListener(YamlCustomItemRegistry registry, CooldownManager cooldowns,
                          BooleanSupplier debugEnabled, Logger logger, Random random) {
        this.registry = registry;
        this.cooldowns = cooldowns;
        this.debugEnabled = debugEnabled;
        this.logger = logger;
        this.random = random;
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Optional<CustomItemDefinition> definitionOpt = registry.resolve(event.getItem());
        if (definitionOpt.isEmpty()) {
            return;
        }
        ToolBehavior behavior = definitionOpt.get().toolBehavior();
        if (behavior == null) {
            return;
        }

        event.setDamage(behavior.durabilityCost());

        if (debugEnabled.getAsBoolean()) {
            logger.info("[debug][tool] {} : durabilité consommée = {} (au lieu de {})",
                    definitionOpt.get().id(), behavior.durabilityCost(), event.getDamage());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockDropItem(BlockDropItemEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        Optional<CustomItemDefinition> definitionOpt = registry.resolve(tool);
        if (definitionOpt.isEmpty()) {
            return;
        }
        ToolBehavior behavior = definitionOpt.get().toolBehavior();
        if (behavior == null) {
            return;
        }

        Material blockType = event.getBlockState().getType();
        if (!behavior.appliesTo(blockType)) {
            return;
        }
        if (behavior.harvestBonusChance() <= 0 || behavior.harvestBonusAmount() <= 0) {
            return;
        }
        if (random.nextDouble() >= behavior.harvestBonusChance()) {
            return;
        }

        List<ItemStack> drops = event.getItems().stream().map(Item::getItemStack).toList();
        for (ItemStack drop : drops) {
            for (int i = 0; i < behavior.harvestBonusAmount(); i++) {
                event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), drop.clone());
            }
        }

        if (debugEnabled.getAsBoolean()) {
            logger.info("[debug][tool] {} : bonus de récolte déclenché sur {} ({}x{} objet(s))",
                    definitionOpt.get().id(), blockType, behavior.harvestBonusAmount(), drops.size());
        }
    }

    // PlayerInteractEvent#isCancelled() est déprécié (sémantique ambiguë entre bloc/objet) : on
    // s'appuie ici uniquement sur `ignoreCancelled = true`, la voie non dépréciée recommandée par
    // Paper pour ignorer un clic déjà annulé par un autre plugin.
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        Optional<CustomItemDefinition> definitionOpt = registry.resolve(tool);
        if (definitionOpt.isEmpty()) {
            return;
        }
        ToolBehavior behavior = definitionOpt.get().toolBehavior();
        if (behavior == null || behavior.specialAbility() == null) {
            return;
        }
        ToolSpecialAbility ability = behavior.specialAbility();

        String abilityId = definitionOpt.get().id() + ":" + ability.abilityId();
        if (!cooldowns.isReady(player.getUniqueId(), abilityId)) {
            return;
        }
        cooldowns.start(player.getUniqueId(), abilityId, ability.cooldownMillis());

        if (ability.activationMessage() != null && !ability.activationMessage().isBlank()) {
            player.sendMessage(MM.deserialize(ability.activationMessage()));
        }
        if (ability.particle() != null) {
            player.getWorld().spawnParticle(ability.particle(), player.getLocation().add(0, 1, 0), ability.particleCount());
        }

        if (debugEnabled.getAsBoolean()) {
            logger.info("[debug][tool] {} : capacité spéciale {} activée par {}",
                    definitionOpt.get().id(), ability.abilityId(), player.getName());
        }
    }
}
