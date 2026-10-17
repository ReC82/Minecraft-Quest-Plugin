package com.lodygames.rpgquest.config;

import com.lodygames.rpgquest.quest.model.QuestPeriod;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Section {@code periodic-quests:} (issue #15) — quêtes quotidiennes et hebdomadaires.
 *
 * @param enabled        onglets du journal et attribution actifs
 * @param zone           fuseau des réinitialisations (heure locale du serveur de jeu, pas de l'hôte)
 * @param resetTime      heure locale d'une réinitialisation quotidienne — et hebdomadaire, le jour {@code weeklyResetDay}
 * @param weeklyResetDay jour de la réinitialisation hebdomadaire
 * @param dailyCount     quêtes quotidiennes attribuées par joueur et par jour (limite fixe du MVP)
 * @param weeklyCount    quêtes hebdomadaires attribuées par joueur et par semaine (limite fixe du MVP)
 */
public record PeriodicQuestConfig(boolean enabled, ZoneId zone, LocalTime resetTime, DayOfWeek weeklyResetDay,
                                  int dailyCount, int weeklyCount) {

    /** Borne haute d'un nombre de quêtes par période : la page d'un onglet du journal en montre 45. */
    public static final int MAX_COUNT = 9;

    public static PeriodicQuestConfig defaults() {
        return new PeriodicQuestConfig(true, ZoneId.of("Europe/Paris"), LocalTime.of(4, 0), DayOfWeek.MONDAY, 2, 1);
    }

    public int count(QuestPeriod period) {
        return period == QuestPeriod.DAILY ? dailyCount : weeklyCount;
    }
}
