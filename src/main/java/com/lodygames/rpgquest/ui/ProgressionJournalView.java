package com.lodygames.rpgquest.ui;

import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.progression.model.ProgressionCurve;
import com.lodygames.rpgquest.progression.model.SkillType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Issue #183 (retour du 20/10) — contenu de la page « Ma progression RPG » du journal : un objet
 * par piste réellement définie ({@link SkillType}, {@code GLOBAL} en premier), avec niveau, XP
 * courante / requise et jauge. Lecture seule du cache de {@link ProgressionService} : aucune XP,
 * aucun niveau n'est écrit d'ici, et un joueur sans progression voit ses pistes au niveau 1, 0 %.
 */
final class ProgressionJournalView {

    static final String TITLE = "Ma progression RPG";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private ProgressionJournalView() {
    }

    /** Les jauges de toutes les pistes, dans l'ordre de {@link SkillType} — {@code GLOBAL} d'abord. */
    static List<ProgressionGauge> gauges(ProgressionService progression, UUID playerId) {
        ProgressionCurve curve = progression.curve();
        List<ProgressionGauge> gauges = new ArrayList<>();
        for (SkillType skill : SkillType.values()) {
            gauges.add(ProgressionGauge.of(curve, skill, progression.totalXp(playerId, skill)));
        }
        return gauges;
    }

    static ProgressionGauge global(ProgressionService progression, UUID playerId) {
        return ProgressionGauge.of(progression.curve(), SkillType.GLOBAL, progression.totalXp(playerId, SkillType.GLOBAL));
    }

    static ItemStack icon(ProgressionGauge gauge) {
        ItemStack stack = new ItemStack(material(gauge.skill()));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(gauge.title());
        List<Component> lore = new ArrayList<>(gauge.lore());
        lore.add(Component.empty());
        lore.add(MM.deserialize("<dark_gray>" + howToEarn(gauge.skill()) + "</dark_gray>"));
        meta.lore(lore);
        // Une épée ou une pioche afficherait sinon ses dégâts / sa vitesse d'attaque.
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP,
                ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        stack.setItemMeta(meta);
        return stack;
    }

    private static Material material(SkillType skill) {
        return switch (skill) {
            case GLOBAL -> Material.EXPERIENCE_BOTTLE;
            case COMBAT -> Material.IRON_SWORD;
            case MINING -> Material.IRON_PICKAXE;
            case FARMING -> Material.WHEAT;
            case FISHING -> Material.FISHING_ROD;
            case EXPLORATION -> Material.COMPASS;
        };
    }

    /** Une ligne courte et sans jargon : d'où vient l'XP de la piste (sources réelles des listeners). */
    static String howToEarn(SkillType skill) {
        return switch (skill) {
            case GLOBAL -> "Quêtes terminées et toutes les autres pistes.";
            case COMBAT -> "Vaincre des créatures.";
            case MINING -> "Miner des blocs naturels.";
            case FARMING -> "Récolter des cultures mûres.";
            case FISHING -> "Pêcher des prises.";
            case EXPLORATION -> "Découvrir de nouveaux lieux.";
        };
    }
}
