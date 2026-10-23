package com.lodygames.rpgquest.webapi.account;

import com.lodygames.rpgquest.webapi.http.MalformedRequestException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Progression personnelle d'un joueur relié (issue #53), envoyée par le serveur Minecraft sur
 * {@code POST /api/link/progress} et montrée <strong>uniquement</strong> au titulaire du compte relié.
 *
 * <p>Lue par liste blanche : seuls les champs ci-dessous sont gardés, chacun borné et validé ; une
 * entrée hors contrat rejette tout l'envoi (400), rien n'est stocké à moitié. Aucun pseudo d'autrui,
 * aucun UUID n'y a sa place.</p>
 *
 * @param generatedAt instant du calcul côté serveur de jeu
 * @param skills      XP et niveau par piste
 * @param quests      quêtes terminées, avec rang et effectif s'il en a terminé au moins une
 * @param boards      place sur chaque classement publié
 */
public record LinkedProgress(Instant generatedAt, List<Skill> skills, Standing quests, List<Board> boards) {

    static final int MAX_SKILLS = 16;
    static final int MAX_BOARDS = 100;
    private static final Pattern CONSTANT = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9_.:-]{0,63}");

    public record Skill(String skill, long xp, int level) {
    }

    /** Valeur, et rang + effectif seulement si le joueur est classé ({@code rank == 0} sinon). */
    public record Standing(long value, int rank, long players) {
        public boolean ranked() {
            return rank > 0;
        }
    }

    public record Board(String id, String name, String category, String icon, String metric, String period,
                        String window, String status, Standing standing) {
    }

    public LinkedProgress {
        skills = List.copyOf(skills);
        boards = List.copyOf(boards);
    }

    /** Contrat de l'API : lève {@link MalformedRequestException} sur toute entrée hors liste blanche. */
    public static LinkedProgress fromWire(Object raw) {
        Map<?, ?> map = map(raw, "progress");
        Instant generatedAt;
        try {
            generatedAt = Instant.parse(text(map, "generatedAt", 40));
        } catch (DateTimeParseException e) {
            throw new MalformedRequestException("« generatedAt » invalide.");
        }
        List<Skill> skills = new ArrayList<>();
        for (Object entry : list(map.get("skills"), "skills", MAX_SKILLS)) {
            Map<?, ?> skill = map(entry, "skills[]");
            skills.add(new Skill(constant(skill, "skill"), count(skill, "xp"), (int) Math.min(10_000, count(skill, "level"))));
        }
        Map<?, ?> quests = map(map.get("quests"), "quests");
        List<Board> boards = new ArrayList<>();
        for (Object entry : list(map.get("boards"), "boards", MAX_BOARDS)) {
            Map<?, ?> board = map(entry, "boards[]");
            String status = text(board, "status", 16);
            if (!status.equals("ACTIVE") && !status.equals("ENDED")) {
                throw new MalformedRequestException("« status » invalide.");
            }
            boards.add(new Board(slug(board, "id"), text(board, "name", 80), slug(board, "category"),
                    board.get("icon") == null ? null : slug(board, "icon"), constant(board, "metric"),
                    slug(board, "period"), text(board, "window", 64), status, standing(board, "value")));
        }
        return new LinkedProgress(generatedAt, skills, standing(quests, "completed"), boards);
    }

    /** Forme stockée : exactement le contrat, déjà nettoyé. */
    public Map<String, Object> toWire() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("generatedAt", generatedAt.toString());
        List<Map<String, Object>> skillList = new ArrayList<>();
        for (Skill skill : skills) {
            skillList.add(new LinkedHashMap<>(Map.of("skill", skill.skill(), "xp", skill.xp(), "level", skill.level())));
        }
        map.put("skills", skillList);
        map.put("quests", standingWire("completed", quests));
        List<Map<String, Object>> boardList = new ArrayList<>();
        for (Board board : boards) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", board.id());
            entry.put("name", board.name());
            entry.put("category", board.category());
            if (board.icon() != null) {
                entry.put("icon", board.icon());
            }
            entry.put("metric", board.metric());
            entry.put("period", board.period());
            entry.put("window", board.window());
            entry.put("status", board.status());
            entry.putAll(standingWire("value", board.standing()));
            boardList.add(entry);
        }
        map.put("boards", boardList);
        return map;
    }

    private static Map<String, Object> standingWire(String valueKey, Standing standing) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(valueKey, standing.value());
        if (standing.ranked()) {
            map.put("rank", standing.rank());
            map.put("players", standing.players());
        }
        return map;
    }

    private static Standing standing(Map<?, ?> map, String valueKey) {
        long value = count(map, valueKey);
        if (map.get("rank") == null && map.get("players") == null) {
            return new Standing(value, 0, 0);
        }
        long rank = count(map, "rank");
        long players = count(map, "players");
        if (rank < 1 || rank > players || rank > Integer.MAX_VALUE) {
            throw new MalformedRequestException("« rank » / « players » incohérents.");
        }
        return new Standing(value, (int) rank, players);
    }

    private static Map<?, ?> map(Object raw, String key) {
        if (raw instanceof Map<?, ?> map) {
            return map;
        }
        throw new MalformedRequestException("« " + key + " » : objet attendu.");
    }

    private static List<?> list(Object raw, String key, int max) {
        if (raw instanceof List<?> list && list.size() <= max) {
            return list;
        }
        throw new MalformedRequestException("« " + key + " » : liste de " + max + " éléments au plus attendue.");
    }

    private static String text(Map<?, ?> map, String key, int max) {
        if (map.get(key) instanceof String value && !value.isBlank() && value.length() <= max
                && value.chars().noneMatch(Character::isISOControl)) {
            return value;
        }
        throw new MalformedRequestException("« " + key + " » manquant ou invalide.");
    }

    private static String constant(Map<?, ?> map, String key) {
        String value = text(map, key, 64);
        if (!CONSTANT.matcher(value).matches()) {
            throw new MalformedRequestException("« " + key + " » invalide.");
        }
        return value;
    }

    private static String slug(Map<?, ?> map, String key) {
        String value = text(map, key, 64);
        if (!SLUG.matcher(value).matches()) {
            throw new MalformedRequestException("« " + key + " » invalide.");
        }
        return value;
    }

    private static long count(Map<?, ?> map, String key) {
        if (map.get(key) instanceof Number number && number.doubleValue() >= 0 && number.doubleValue() == Math.floor(number.doubleValue())
                && number.doubleValue() <= Long.MAX_VALUE) {
            return number.longValue();
        }
        throw new MalformedRequestException("« " + key + " » : entier positif attendu.");
    }
}
