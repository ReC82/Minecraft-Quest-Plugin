package com.lodygames.rpgquest.ui;

import com.lodygames.rpgquest.progression.model.ProgressionCurve;
import com.lodygames.rpgquest.progression.model.SkillType;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/**
 * Issue #183 (retour du 20/10) — jauge d'une piste de progression RPG telle que le journal
 * l'affiche : niveau, XP dans le niveau / XP requise pour le suivant, pourcentage et barre. Une
 * simple <strong>lecture</strong> : tout vient de l'XP totale en cache et de la courbe de {@code
 * ProgressionService} (les mêmes calculs que {@code /skills} et que la bossbar), aucun calcul de
 * niveau parallèle.
 */
record ProgressionGauge(SkillType skill, long totalXp, int level, int maxLevel, long xpIntoLevel, long xpForNextLevel) {

    /** Cases de la barre : ~80 px d'interface, loin de la largeur utile d'une infobulle (#475). */
    static final int BAR_SEGMENTS = 10;
    private static final String SEGMENT = "■";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    static ProgressionGauge of(ProgressionCurve curve, SkillType skill, long totalXp) {
        long safeTotal = Math.max(0L, totalXp);
        int level = curve.levelForTotalXp(safeTotal);
        return new ProgressionGauge(skill, safeTotal, level, curve.maxLevel(),
                curve.xpIntoCurrentLevel(safeTotal), curve.xpToNextLevel(level));
    }

    boolean isMaxLevel() {
        return xpForNextLevel <= 0;
    }

    /** Pourcentage du niveau en cours, arrondi vers le bas : 100 % seulement au niveau maximal. */
    int percent() {
        if (isMaxLevel()) {
            return 100;
        }
        return (int) Math.min(99L, Math.max(0L, xpIntoLevel * 100L / xpForNextLevel));
    }

    int filledSegments() {
        return isMaxLevel() ? BAR_SEGMENTS : percent() * BAR_SEGMENTS / 100;
    }

    /** Barre en MiniMessage : cases pleines vertes (dorées au niveau maximal), cases vides grises. */
    String bar() {
        int filled = filledSegments();
        String color = isMaxLevel() ? "gold" : "green";
        return "<" + color + ">" + SEGMENT.repeat(filled) + "</" + color + "><dark_gray>"
                + SEGMENT.repeat(BAR_SEGMENTS - filled) + "</dark_gray>";
    }

    /** Titre de l'objet : « Global — niveau 3 ». */
    Component title() {
        return MM.deserialize("<yellow><skill></yellow> <gray>— niveau</gray> <aqua><level></aqua>",
                Placeholder.unparsed("skill", skill.label()),
                Placeholder.unparsed("level", String.valueOf(level)));
    }

    /** Ligne courte « Niveau RPG 3 ■■■■□□□□□□ 45 % », pour un résumé sur une seule ligne. */
    Component summaryLine() {
        return MM.deserialize("<gray>Niveau RPG</gray> <aqua><level></aqua> " + bar() + " <white><percent> %</white>",
                Placeholder.unparsed("level", String.valueOf(level)),
                Placeholder.unparsed("percent", String.valueOf(percent())));
    }

    /** Infobulle complète : niveau sur maximum, barre, XP courante / requise, XP totale. */
    List<Component> lore() {
        List<Component> lines = new ArrayList<>();
        lines.add(MM.deserialize("<gray>Niveau</gray> <aqua><level></aqua> <dark_gray>/ <max></dark_gray>",
                Placeholder.unparsed("level", String.valueOf(level)),
                Placeholder.unparsed("max", String.valueOf(maxLevel))));
        lines.add(MM.deserialize(bar() + " <white><percent> %</white>",
                Placeholder.unparsed("percent", String.valueOf(percent()))));
        if (isMaxLevel()) {
            lines.add(MM.deserialize("<gold>Niveau maximal atteint</gold>"));
        } else {
            lines.add(MM.deserialize("<gray>XP :</gray> <white><into> / <next></white> <gray>pour le niveau</gray> <aqua><next_level></aqua>",
                    Placeholder.unparsed("into", String.valueOf(xpIntoLevel)),
                    Placeholder.unparsed("next", String.valueOf(xpForNextLevel)),
                    Placeholder.unparsed("next_level", String.valueOf(level + 1))));
        }
        lines.add(MM.deserialize("<dark_gray>XP totale : <total></dark_gray>",
                Placeholder.unparsed("total", String.valueOf(totalXp))));
        return lines;
    }
}
