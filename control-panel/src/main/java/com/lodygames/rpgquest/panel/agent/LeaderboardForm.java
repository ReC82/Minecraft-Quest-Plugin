package com.lodygames.rpgquest.panel.agent;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Formulaires « Classements du site » de PlugAdmin (issue #735) → paramètres des actions
 * {@code leaderboard.*}. Le formulaire guidé parle en mots simples (« Que mesurer ? », cases de
 * familles, dates locales) ; ce code le traduit en paramètres stricts. Le plugin revalide tout et le
 * vrai chargeur tranche avant toute écriture.
 *
 * <p>Familles : une case {@code fam__<clé>__<famille>} par famille ({@code fam__entity-family__zombies}).
 * Cibles exactes : {@code targets} (« ZOMBIE, SKELETON »), rattachées à la clé naturelle de la mesure.
 * Dates : {@code start_local} / {@code end_local} ({@code datetime-local}) lues dans le fuseau
 * {@code timezone} du formulaire, envoyées avec leur décalage.</p>
 */
public final class LeaderboardForm {

    public static final Pattern ID = Pattern.compile("[a-z0-9_]{1,64}");
    static final Pattern ICON = Pattern.compile("[a-z0-9-]{1,32}");
    static final Pattern TARGET = Pattern.compile("[A-Za-z0-9_.:/-]{1,128}");
    static final Pattern FAMILY = Pattern.compile("[a-z0-9_-]{1,64}");
    static final Pattern VERSION = Pattern.compile("[0-9a-f]{12}");
    public static final List<String> CATEGORIES =
            List.of("aventure", "combat", "recolte", "exploration", "communaute", "autres");
    public static final List<String> PERIODS = List.of("lifetime", "daily", "weekly", "monthly", "season", "event", "custom");
    public static final Set<String> STATUSES = Set.of("draft", "enabled", "paused", "archived");
    static final int LABEL_MAX = 120;
    static final int DESCRIPTION_MAX = 200;
    static final int MAX_TARGETS = 64;
    /** Clé de filtre des cibles exactes selon la mesure ; absente = la mesure ne se filtre pas ainsi. */
    public static final Map<String, String> EXACT_KEYS = Map.of("ENTITY_KILL", "entity", "BLOCK_BREAK", "block",
            "FISH_CAUGHT", "item", "QUEST_COMPLETION", "quest", "ANIMAL_BRED", "entity");
    /** Clé de filtre des familles selon la mesure. */
    public static final Map<String, String> FAMILY_KEYS = Map.of("ENTITY_KILL", "entity-family",
            "BLOCK_BREAK", "block-family");
    static final String FAMILY_PREFIX = "fam__";

    private LeaderboardForm() {
    }

    /** {@code leaderboard.save} ; renvoie le message d'erreur à afficher, ou {@code null}. */
    static String fill(Map<String, String> form, Map<String, String> params) {
        boolean create = "true".equals(trim(form.get("create")));
        String name = trim(form.get("name"));
        if (name.isEmpty() || name.length() > LABEL_MAX || name.chars().anyMatch(Character::isISOControl)) {
            return "Titre obligatoire, sur une ligne, " + LABEL_MAX + " caractères au plus.";
        }
        String id = trim(form.get("board_id")).toLowerCase(Locale.ROOT);
        if (create && id.isEmpty()) {
            id = RewardTableForm.slug(name).replace('-', '_');
        }
        if (!ID.matcher(id).matches()) {
            return "Identifiant : minuscules, chiffres et _ (64 caractères au plus), ex. zombies_semaine.";
        }
        String description = trim(form.get("description"));
        if (description.length() > DESCRIPTION_MAX || description.chars().anyMatch(Character::isISOControl)) {
            return "Description : une ligne, " + DESCRIPTION_MAX + " caractères au plus.";
        }
        String category = trim(form.get("category"));
        if (!CATEGORIES.contains(category)) {
            return "Choisir une catégorie.";
        }
        String icon = trim(form.get("icon"));
        if (!icon.isEmpty() && !ICON.matcher(icon).matches()) {
            return "Icône invalide.";
        }
        String metric = trim(form.get("metric"));
        if (!metric.matches("[A-Z0-9_]{1,64}")) {
            return "Choisir ce que le classement mesure.";
        }
        String period = trim(form.get("period"));
        if (!PERIODS.contains(period)) {
            return "Choisir une période.";
        }
        String expected = trim(form.get("expected_version"));
        if (!expected.isEmpty() && !VERSION.matcher(expected).matches()) {
            return "Version du formulaire invalide : recharger la page.";
        }
        String size = trim(form.get("size"));
        if (!size.matches("[0-9]{1,2}") || Integer.parseInt(size) < 1 || Integer.parseInt(size) > 50) {
            return "Nombre de joueurs affichés : entre 1 et 50.";
        }
        String error = filter(form, metric, params);
        if (error != null) {
            return error;
        }
        if (period.equals("custom")) {
            error = dates(form, params);
            if (error != null) {
                return error;
            }
        }
        if (period.equals("event")) {
            String event = trim(form.get("event"));
            if (!ID.matcher(event).matches()) {
                return "Classement d'événement : choisir l'événement suivi.";
            }
            params.put("event", event);
        }
        params.put("board_id", id);
        params.put("name", name);
        if (!description.isEmpty()) {
            params.put("description", description);
        }
        params.put("category", category);
        if (!icon.isEmpty()) {
            params.put("icon", icon);
        }
        params.put("metric", metric);
        params.put("period", period);
        params.put("size", size);
        params.put("visible", Boolean.toString(checked(form, "visible")));
        params.put("featured", Boolean.toString(checked(form, "featured")));
        params.put("create", Boolean.toString(create));
        if (!expected.isEmpty()) {
            params.put("expected_version", expected);
        }
        return null;
    }

    /** Familles cochées + cibles exactes → {@code filter = clé=v1,v2;clé2=v3}. */
    static String filter(Map<String, String> form, String metric, Map<String, String> params) {
        Map<String, List<String>> filter = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : form.entrySet()) {
            if (!field.getKey().startsWith(FAMILY_PREFIX) || !isOn(field.getValue())) {
                continue;
            }
            String[] parts = field.getKey().substring(FAMILY_PREFIX.length()).split("__", 2);
            if (parts.length != 2 || !FAMILY.matcher(parts[1]).matches()) {
                return "Famille de cibles invalide : recharger la page.";
            }
            if (!parts[0].equals(FAMILY_KEYS.get(metric))) {
                return parts[0].equals("entity-family")
                        ? "Les familles de monstres ne s'appliquent qu'à la mesure « Monstres et créatures vaincus » : "
                        + "décocher ces cases ou changer de mesure."
                        : "Les familles de blocs ne s'appliquent qu'à la mesure « Blocs cassés » : décocher ces cases "
                        + "ou changer de mesure.";
            }
            filter.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(parts[1]);
        }
        String targets = trim(form.get("targets"));
        if (!targets.isEmpty()) {
            String key = EXACT_KEYS.get(metric);
            if (key == null) {
                return "Cette mesure ne se limite pas à des cibles précises : vider le champ « Cibles exactes ».";
            }
            for (String raw : targets.split("[,;\\s]+")) {
                String value = raw.trim();
                if (value.isEmpty()) {
                    continue;
                }
                if (!key.equals("quest")) {
                    value = value.toUpperCase(Locale.ROOT);
                }
                if (!TARGET.matcher(value).matches()) {
                    return "Cible « " + value + " » invalide : nom Minecraft (ZOMBIE, STONE, COD) ou id de quête.";
                }
                filter.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            }
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : filter.entrySet()) {
            if (entry.getValue().size() > MAX_TARGETS) {
                return MAX_TARGETS + " cibles au plus par type.";
            }
            parts.add(entry.getKey() + "=" + String.join(",", entry.getValue()));
        }
        if (!parts.isEmpty()) {
            params.put("filter", String.join(";", parts));
        }
        return null;
    }

    /** Dates locales du formulaire, lues dans son fuseau, envoyées avec leur décalage. */
    static String dates(Map<String, String> form, Map<String, String> params) {
        ZoneId zone;
        try {
            zone = ZoneId.of(trim(form.get("timezone")).isEmpty() ? "Europe/Brussels" : trim(form.get("timezone")));
        } catch (DateTimeException e) {
            return "Fuseau horaire invalide : recharger la page.";
        }
        String[] keys = {"start", "end"};
        for (String key : keys) {
            String raw = trim(form.get(key + "_local"));
            if (raw.isEmpty()) {
                return "Dates personnalisées : début et fin obligatoires.";
            }
            try {
                LocalDateTime local = LocalDateTime.parse(raw.length() == 16 ? raw + ":00" : raw).withNano(0);
                params.put(key, local.atZone(zone).toOffsetDateTime().toString());
            } catch (DateTimeParseException e) {
                return "Date « " + raw + " » invalide.";
            }
        }
        if (!java.time.OffsetDateTime.parse(params.get("start")).isBefore(java.time.OffsetDateTime.parse(params.get("end")))) {
            return "La date de début doit précéder la date de fin.";
        }
        return null;
    }

    /** {@code leaderboard.duplicate}. */
    static String fillDuplicate(Map<String, String> form, Map<String, String> params) {
        String source = trim(form.get("board_id"));
        String target = trim(form.get("new_id")).toLowerCase(Locale.ROOT);
        if (!ID.matcher(source).matches() || !ID.matcher(target).matches()) {
            return "Identifiant de la copie : minuscules, chiffres et _ (64 caractères au plus).";
        }
        String name = trim(form.get("new_name"));
        if (name.length() > LABEL_MAX || name.chars().anyMatch(Character::isISOControl)) {
            return "Titre de la copie : une ligne, " + LABEL_MAX + " caractères au plus.";
        }
        params.put("board_id", source);
        params.put("new_id", target);
        if (!name.isEmpty()) {
            params.put("new_name", name);
        }
        return null;
    }

    /** {@code leaderboard.status} : archiver demande la case de confirmation. */
    static String fillStatus(Map<String, String> form, Map<String, String> params) {
        String id = trim(form.get("board_id"));
        String status = trim(form.get("status"));
        if (!ID.matcher(id).matches() || !STATUSES.contains(status)) {
            return "Classement ou statut invalide : recharger la page.";
        }
        if (status.equals("archived") && !checked(form, "confirm")) {
            return "Cocher « Je confirme l'archivage » : le classement quitte le site (ses résultats figés restent).";
        }
        params.put("board_id", id);
        params.put("status", status);
        return null;
    }

    /** {@code leaderboard.flag}. */
    static String fillFlag(Map<String, String> form, Map<String, String> params) {
        String id = trim(form.get("board_id"));
        String flag = trim(form.get("flag"));
        if (!ID.matcher(id).matches() || !(flag.equals("featured") || flag.equals("visible"))) {
            return "Classement invalide : recharger la page.";
        }
        params.put("board_id", id);
        params.put("flag", flag);
        params.put("value", "true".equals(trim(form.get("value"))) ? "true" : "false");
        return null;
    }

    /** {@code leaderboard.move}. */
    static String fillMove(Map<String, String> form, Map<String, String> params) {
        String id = trim(form.get("board_id"));
        String direction = trim(form.get("direction"));
        if (!ID.matcher(id).matches() || !(direction.equals("up") || direction.equals("down"))) {
            return "Classement invalide : recharger la page.";
        }
        params.put("board_id", id);
        params.put("direction", direction);
        return null;
    }

    /** {@code leaderboard.preview} (lecture seule). */
    static String fillPreview(Map<String, String> form, Map<String, String> params) {
        String id = trim(form.get("board_id"));
        if (!ID.matcher(id).matches()) {
            return "Classement invalide : recharger la page.";
        }
        params.put("board_id", id);
        return null;
    }

    /** {@code leaderboard.timezone}. */
    static String fillTimezone(Map<String, String> form, Map<String, String> params) {
        String zone = trim(form.get("timezone"));
        try {
            params.put("timezone", ZoneId.of(zone).getId());
        } catch (DateTimeException e) {
            return "Fuseau horaire inconnu « " + zone + " » (ex. Europe/Brussels).";
        }
        return null;
    }

    private static boolean checked(Map<String, String> form, String key) {
        return isOn(form.get(key));
    }

    private static boolean isOn(String value) {
        String v = trim(value);
        return v.equals("on") || v.equals("true") || v.equals("1");
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
