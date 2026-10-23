package com.lodygames.rpgquest.web.link;

import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.database.QuestStatisticsRepository;
import com.lodygames.rpgquest.database.StatisticsRepository;
import com.lodygames.rpgquest.progression.model.ProgressionCurve;
import com.lodygames.rpgquest.progression.model.SkillType;
import com.lodygames.rpgquest.stats.BoardPeriod;
import com.lodygames.rpgquest.stats.StatBoard;
import com.lodygames.rpgquest.stats.StatPeriod;
import com.lodygames.rpgquest.stats.StatisticsDefinition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Progression personnelle d'un joueur relié au portail (issue #53) : niveaux et XP par compétence,
 * quêtes terminées, et sa place sur chaque classement publié (#734/#735) — valeur, rang, effectif.
 *
 * <p>Destinée au <strong>seul</strong> titulaire du compte du portail relié à cet UUID : elle ne
 * contient aucun autre pseudo ni UUID, et rien qui ne soit déjà en base (aucune valeur inventée).
 * Le rang se compte parmi tous les joueurs classés, profils privés compris, sans les exclus de
 * modération ; un joueur exclu d'un classement n'y a pas de place.</p>
 */
public final class LinkedProgressBuilder {

    /** Borne du nombre de classements envoyés, identique côté portail. */
    static final int MAX_BOARDS = 100;

    private final ProgressionRepository progression;
    private final Supplier<ProgressionCurve> curve;
    private final QuestStatisticsRepository quests;
    private final StatisticsRepository statistics;
    private final Supplier<StatisticsDefinition> definition;
    private final Predicate<String> archived;
    private final Supplier<Instant> clock;

    public LinkedProgressBuilder(ProgressionRepository progression, Supplier<ProgressionCurve> curve,
                                 QuestStatisticsRepository quests, StatisticsRepository statistics,
                                 Supplier<StatisticsDefinition> definition, Predicate<String> archived,
                                 Supplier<Instant> clock) {
        this.progression = progression;
        this.curve = curve;
        this.quests = quests;
        this.statistics = statistics;
        this.definition = definition;
        this.archived = archived;
        this.clock = clock;
    }

    /** Le contrat {@code progress} de {@code POST /api/link/progress}, calculé hors du fil principal. */
    public CompletableFuture<Map<String, Object>> build(UUID player) {
        Instant now = clock.get();
        StatisticsDefinition stats = definition.get();
        ProgressionCurve levels = curve.get();
        CompletableFuture<List<Map<String, Object>>> skills = progression.findAll(player)
                .thenApply(totals -> skills(totals, levels));
        CompletableFuture<Optional<StatisticsRepository.Standing>> questStanding = quests.standing(player, List.of());
        List<CompletableFuture<Optional<Map<String, Object>>>> boards = new ArrayList<>();
        for (StatBoard board : stats.publishedBoards()) {
            if (boards.size() >= MAX_BOARDS) {
                break;
            }
            boards.add(board(stats, board, player, now));
        }
        return skills.thenCombine(questStanding, (skillList, questValue) -> {
            Map<String, Object> progress = new LinkedHashMap<>();
            progress.put("generatedAt", now.toString());
            progress.put("skills", skillList);
            Map<String, Object> questMap = new LinkedHashMap<>();
            questMap.put("completed", questValue.map(StatisticsRepository.Standing::value).orElse(0L));
            questValue.ifPresent(standing -> {
                questMap.put("rank", standing.rank());
                questMap.put("players", standing.players());
            });
            progress.put("quests", questMap);
            return progress;
        }).thenCombine(CompletableFuture.allOf(boards.toArray(CompletableFuture[]::new)), (progress, ignored) -> {
            progress.put("boards", boards.stream().map(CompletableFuture::join).flatMap(Optional::stream).toList());
            return progress;
        });
    }

    static List<Map<String, Object>> skills(Map<SkillType, Long> totals, ProgressionCurve levels) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (SkillType skill : SkillType.values()) {
            long xp = Math.max(0L, totals.getOrDefault(skill, 0L));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("skill", skill.name());
            entry.put("xp", xp);
            entry.put("level", levels.levelForTotalXp(xp));
            list.add(entry);
        }
        return list;
    }

    private CompletableFuture<Optional<Map<String, Object>>> board(StatisticsDefinition stats, StatBoard board,
                                                                   UUID player, Instant now) {
        Set<UUID> excluded = stats.excludedFrom(board.id());
        Optional<BoardPeriod.Window> current = board.current(now, stats);
        if (excluded.contains(player) || current.isEmpty() || now.isBefore(current.get().start())) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        BoardPeriod.Window window = current.get();
        CompletableFuture<Optional<StatisticsRepository.Standing>> standing;
        if (!board.statMetric().windowed()) {
            // Quêtes terminées « depuis toujours » : quest_progress, comme le classement public (#54).
            standing = quests.standing(player, excluded);
        } else {
            StatPeriod period = board.periodFor(window);
            standing = archived.test(period.id())
                    ? statistics.archivedStanding(period, board.statMetric(), player, excluded)
                    : statistics.liveStanding(period, board.statMetric(), player, excluded, stats.excludedXpReasons());
        }
        String status = now.isBefore(window.end()) ? "ACTIVE" : "ENDED";
        return standing.thenApply(value -> Optional.of(entry(board, window, status, value)));
    }

    private static Map<String, Object> entry(StatBoard board, BoardPeriod.Window window, String status,
                                             Optional<StatisticsRepository.Standing> standing) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", board.id());
        entry.put("name", board.name());
        entry.put("category", board.category());
        if (board.icon() != null) {
            entry.put("icon", board.icon());
        }
        entry.put("metric", board.metric());
        entry.put("period", board.period().wireName());
        entry.put("window", window.key());
        entry.put("status", status);
        entry.put("value", standing.map(StatisticsRepository.Standing::value).orElse(0L));
        standing.ifPresent(s -> {
            entry.put("rank", s.rank());
            entry.put("players", s.players());
        });
        return entry;
    }
}
