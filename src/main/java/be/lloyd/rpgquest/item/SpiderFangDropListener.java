package be.lloyd.rpgquest.item;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * Fait tomber {@code rpgquest:spider_fang} (objet personnalisé) des araignées
 * tuées par un joueur — nécessaire pour que la recette {@code
 * forest_blade_recipe} (2 crocs + un bâton) soit atteignable en jeu sans
 * passer par {@code /customitem give}, condition du parcours RPG complet
 * (dialogue → quête → combat → récolte → fabrication → remise). Portée
 * volontairement minimale : un seul objet, un seul type de mob, pas de
 * table de loot générique (hors périmètre de cette étape, contrairement à
 * {@code resource.ResourceNodeService} pour les blocs).
 */
public final class SpiderFangDropListener implements Listener {

    private static final NamespacedKey SPIDER_FANG = new NamespacedKey("rpgquest", "spider_fang");

    private final YamlCustomItemRegistry registry;

    public SpiderFangDropListener(YamlCustomItemRegistry registry) {
        this.registry = registry;
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (!shouldDrop(event.getEntityType(), event.getEntity().getKiller())) {
            return;
        }
        registry.create(SPIDER_FANG, 1).ifPresent(stack -> event.getDrops().add(stack));
    }

    /** Cœur testable, indépendant de l'événement Bukkit réel. */
    boolean shouldDrop(EntityType type, Player killer) {
        boolean isSpider = type == EntityType.SPIDER || type == EntityType.CAVE_SPIDER;
        return isSpider && killer != null; // mort non provoquée par un joueur : pas de drop.
    }
}
