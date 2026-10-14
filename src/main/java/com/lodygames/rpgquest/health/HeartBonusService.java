package com.lodygames.rpgquest.health;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.lodygames.rpgquest.database.HeartGrantRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository.HeartGrantOutcome;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

/**
 * Applique en jeu les cœurs de vie maximum gagnés par récompense de quête (issue #166).
 *
 * <p><strong>La base est la seule vérité</strong> ({@code player_heart_grants}). Le bonus est un
 * {@link AttributeModifier} {@code ADD_NUMBER} sur {@link Attribute#MAX_HEALTH}, toujours sous la
 * même clé {@link #MODIFIER_KEY} : le réappliquer <em>remplace</em> l'ancien au lieu de
 * s'additionner, donc ni une reconnexion, ni une mort, ni un redémarrage ne peuvent cumuler le
 * bonus. Les autres sources de vie maximum (équipement, effets, autres plugins) ont leurs propres
 * modificateurs et ne sont jamais touchées : la valeur de base n'est jamais réécrite.</p>
 *
 * <p>Le modificateur est persistant (sauvegardé avec le joueur) pour qu'une reconnexion ne rogne
 * pas la vie actuelle au-delà de 20 PV avant que la base ait répondu ; il est néanmoins
 * <strong>recalculé</strong> depuis la base à chaque connexion, et réappliqué après chaque
 * réapparition.</p>
 *
 * <p>Gain : seuls les PV nouvellement ajoutés sont remplis, jamais toute la barre — un cœur gagné
 * n'est pas une guérison.</p>
 */
public final class HeartBonusService implements Listener {

    /** Clé unique du modificateur : un seul bonus RPGQuest, quel que soit le nombre de droits. */
    public static final NamespacedKey MODIFIER_KEY = new NamespacedKey("rpgquest", "quest_heart_bonus");

    private static final double HP_PER_HEART = 2.0;

    private final JavaPlugin plugin;
    private final HeartGrantRepository repository;
    private final QuestMessagesService messagesService;
    private final IntSupplier maxBonusHearts;
    private final Logger logger;
    /** Cœurs accordés (avant plafond) des joueurs connectés. */
    private final Map<UUID, Integer> grantedHearts = new ConcurrentHashMap<>();

    /**
     * @param maxBonusHearts plafond gameplay courant ({@code progression.max-bonus-hearts}),
     *                       {@code 0} = aucun plafond ; relu à chaque application (rechargement)
     */
    public HeartBonusService(JavaPlugin plugin, HeartGrantRepository repository,
                             QuestMessagesService messagesService, IntSupplier maxBonusHearts) {
        this.plugin = plugin;
        this.repository = repository;
        this.messagesService = messagesService;
        this.maxBonusHearts = maxBonusHearts;
        this.logger = plugin.getSLF4JLogger();
    }

    /** Cœurs réellement actifs pour {@code granted} cœurs accordés, plafond gameplay appliqué. */
    public int effectiveHearts(int granted) {
        int cap = maxBonusHearts.getAsInt();
        return cap > 0 ? Math.min(granted, cap) : granted;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPostRespawn(PlayerPostRespawnEvent event) {
        Player player = event.getPlayer();
        Integer granted = grantedHearts.get(player.getUniqueId());
        if (granted != null) {
            apply(player, effectiveHearts(granted));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        grantedHearts.remove(event.getPlayer().getUniqueId());
    }

    /** Relit le total en base (asynchrone) puis l'applique au joueur s'il est toujours connecté. */
    public void refresh(UUID playerId) {
        repository.totalHearts(playerId)
                .thenAccept(total -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Player online = plugin.getServer().getPlayer(playerId);
                    if (online != null) {
                        grantedHearts.put(playerId, total);
                        apply(online, effectiveHearts(total));
                    }
                }))
                .exceptionally(error -> {
                    // Le modificateur sauvegardé avec le joueur reste en place : rien n'est retiré
                    // sur une simple panne de lecture.
                    logger.error("Impossible de relire les cœurs supplémentaires de {}", playerId, error);
                    return null;
                });
    }

    /**
     * Réagit aux droits que la complétion d'une quête vient d'écrire (thread principal). Appelée
     * seulement après le commit : un message de gain correspond toujours à un droit en base.
     */
    public void onQuestCompleted(Player player, String questTitle, List<HeartGrantOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return;
        }
        int gained = grantedSum(outcomes);
        int total = outcomes.getLast().totalAfter();
        int before = effectiveHearts(total - gained);
        grantedHearts.put(player.getUniqueId(), total);
        int after = effectiveHearts(total);
        apply(player, after);
        heal(player, (after - before) * HP_PER_HEART);

        for (HeartGrantOutcome outcome : outcomes) {
            if (!outcome.granted()) {
                player.sendMessage(messagesService.current().format("quest.reward-heart-already",
                        Placeholder.parsed("quest", questTitle)));
            }
        }
        if (gained == 0) {
            return;
        }
        if (after - before < gained) {
            player.sendMessage(messagesService.current().format("quest.reward-heart-capped",
                    Placeholder.parsed("quest", questTitle),
                    Placeholder.unparsed("cap", String.valueOf(maxBonusHearts.getAsInt()))));
        }
        if (after > before) {
            player.sendMessage(messagesService.current().format("quest.reward-heart-granted",
                    Placeholder.parsed("quest", questTitle),
                    Placeholder.unparsed("hearts", String.valueOf(after - before)),
                    Placeholder.unparsed("total", formatHearts(maxHealth(player)))));
        }
    }

    /** Écriture des droits en échec : le joueur est prévenu, rien n'est appliqué. */
    public void onQuestCompletionFailed(Player player) {
        player.sendMessage(messagesService.current().format("quest.reward-heart-failed"));
    }

    private static int grantedSum(List<HeartGrantOutcome> outcomes) {
        return outcomes.stream().filter(HeartGrantOutcome::granted).mapToInt(HeartGrantOutcome::hearts).sum();
    }

    /** Pose (ou retire, pour 0) le modificateur RPGQuest, sans toucher aux autres sources. */
    void apply(Player player, int hearts) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) {
            return;
        }
        maxHealth.removeModifier(MODIFIER_KEY);
        if (hearts > 0) {
            maxHealth.addModifier(new AttributeModifier(MODIFIER_KEY, hearts * HP_PER_HEART,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        }
        double max = maxHealth.getValue();
        if (player.getHealth() > max) {
            player.setHealth(max);
        }
    }

    private static void heal(Player player, double amount) {
        if (amount <= 0 || player.isDead()) {
            return;
        }
        player.setHealth(Math.min(maxHealth(player), player.getHealth() + amount));
    }

    private static double maxHealth(Player player) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        return maxHealth != null ? maxHealth.getValue() : player.getHealth();
    }

    /** {@code 22.0} PV → {@code "11"} ; {@code 23.0} → {@code "11,5"}. */
    static String formatHearts(double hp) {
        double hearts = hp / HP_PER_HEART;
        return hearts == Math.rint(hearts) ? String.valueOf((long) hearts)
                : String.valueOf(hearts).replace('.', ',');
    }
}
