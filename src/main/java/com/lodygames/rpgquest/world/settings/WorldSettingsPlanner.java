package com.lodygames.rpgquest.world.settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Aperçu <strong>sans écriture</strong> d'une demande de réglages (issue #561) : pour chaque clé
 * demandée, la valeur actuelle relue, la valeur proposée normalisée, le chemin d'écriture, l'effet
 * attendu et le verdict (appliquer, inchangé, refusé). Code pur, testable sans serveur.
 *
 * <p>Les protections LodyQuests priment : un réglage qui contredirait une règle imposée au Hub, au
 * monde des claims ou au Wild est <strong>refusé</strong> avec sa raison, jamais appliqué « pour
 * rien » ni contourné.</p>
 *
 * <p>L'empreinte ({@link Plan#fingerprint()}) résume toutes les valeurs relues : l'application la
 * compare à celle de l'aperçu et refuse d'écrire si le monde a changé entre-temps.</p>
 */
public final class WorldSettingsPlanner {

    /** Bornes de coordonnées de Minecraft (bordure et point d'arrivée). */
    public static final double MAX_COORDINATE = 29_999_984d;
    /** Diamètre maximal d'une bordure (= « pas de bordure » vanilla). */
    public static final double MAX_BORDER_SIZE = 59_999_968d;
    /** Nombre maximal de clés dans une seule demande. */
    public static final int MAX_CHANGES = 30;

    private static final Set<String> DIFFICULTIES = Set.of("PEACEFUL", "EASY", "NORMAL", "HARD");
    private static final Set<String> GAMEMODES = Set.of("SURVIVAL", "ADVENTURE", "CREATIVE", "SPECTATOR");
    private static final Set<String> WEATHERS = Set.of("CLEAR", "RAIN", "THUNDER");
    private static final Pattern RULE_NAME = Pattern.compile("[a-z0-9_]{1,64}");

    public enum Status { APPLY, UNCHANGED, REFUSED }

    /**
     * Une ligne de l'aperçu.
     *
     * @param current  valeur relue ({@code null} = inconnue / indisponible)
     * @param proposed valeur normalisée ({@code null} si la saisie est invalide)
     * @param writer   chemin d'écriture ({@code MULTIVERSE}, {@code BUKKIT}, {@code GAMERULE}), {@code ""} si aucun
     * @param reason   raison d'un refus, {@code ""} sinon
     * @param effect   effet attendu en jeu, en français
     */
    public record Change(String key, String label, String current, String proposed, String writer, Status status,
                         String reason, String effect) {
    }

    /**
     * @param fingerprint empreinte des valeurs relues (garde contre un aperçu périmé)
     * @param warnings    points d'attention qui n'empêchent pas l'application
     */
    public record Plan(String world, List<Change> changes, String fingerprint, List<String> warnings) {

        public long applicable() {
            return changes.stream().filter(c -> c.status() == Status.APPLY).count();
        }

        public long refused() {
            return changes.stream().filter(c -> c.status() == Status.REFUSED).count();
        }
    }

    private WorldSettingsPlanner() {
    }

    /** Planifie {@code requested} (clé → valeur saisie) contre l'état relu {@code current}. */
    public static Plan plan(WorldSettingsSnapshot current, Map<String, String> requested) {
        List<Change> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> sorted = new TreeMap<>(keyOrder());
        sorted.putAll(requested);
        String proposedSpawn = sorted.containsKey(WorldSetting.SPAWN.key())
                ? normalize(current, WorldSetting.SPAWN.key(), sorted.get(WorldSetting.SPAWN.key())) : null;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            changes.add(change(current, e.getKey(), e.getValue(), sorted, proposedSpawn, warnings));
        }
        return new Plan(current.world(), List.copyOf(changes), fingerprint(current), List.copyOf(warnings));
    }

    /** Empreinte stable des valeurs relues (réglages + règles), indépendante de l'ordre. */
    public static String fingerprint(WorldSettingsSnapshot s) {
        StringBuilder sb = new StringBuilder(s.world()).append('|').append(s.multiverse());
        new TreeMap<>(s.values()).forEach((k, v) -> sb.append('|').append(k).append('=').append(v));
        s.rules().stream().sorted(java.util.Comparator.comparing(WorldSettingsSnapshot.Rule::name))
                .forEach(r -> sb.append("|rule.").append(r.name()).append('=').append(r.value()));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    // ---- Une clé --------------------------------------------------------------------------------

    private static Change change(WorldSettingsSnapshot s, String key, String raw, Map<String, String> all,
                                 String proposedSpawn, List<String> warnings) {
        boolean rule = key.startsWith(WorldSetting.RULE_PREFIX);
        String label = rule ? "Règle « " + key.substring(WorldSetting.RULE_PREFIX.length()) + " »"
                : WorldSetting.byKey(key).map(WorldSetting::label).orElse(key);
        String current = s.current(key);
        String writer = rule ? WorldSettingsSnapshot.WRITER_GAMERULE : s.writers().getOrDefault(key, "");
        String unknownKey = unknownKey(s, key, rule);
        if (unknownKey != null) {
            return new Change(key, label, current, null, writer, Status.REFUSED, unknownKey, "");
        }
        String proposed = normalize(s, key, raw);
        if (proposed == null) {
            return new Change(key, label, current, null, writer, Status.REFUSED, invalidReason(s, key), "");
        }
        if (proposed.equals(current)) {
            return new Change(key, label, current, proposed, writer, Status.UNCHANGED, "", "Déjà la valeur actuelle : rien ne sera écrit.");
        }
        String protection = protection(s, key, proposed, all, proposedSpawn);
        if (protection != null) {
            return new Change(key, label, current, proposed, writer, Status.REFUSED, protection, "");
        }
        warning(s, key, proposed).ifPresent(warnings::add);
        return new Change(key, label, current, proposed, writer, Status.APPLY, "", effect(s, key, proposed));
    }

    private static String unknownKey(WorldSettingsSnapshot s, String key, boolean rule) {
        if (rule) {
            String name = key.substring(WorldSetting.RULE_PREFIX.length());
            if (!RULE_NAME.matcher(name).matches() || s.rule(name).isEmpty()) {
                return "Règle de jeu inconnue de ce serveur.";
            }
            return WorldSetting.owningRule(name).map(owner -> "Cette règle se règle avec « " + owner.label()
                    + " » (un seul chemin d'écriture).").orElse(null);
        }
        if (WorldSetting.byKey(key).isEmpty()) {
            return "Réglage inconnu.";
        }
        if (!s.values().containsKey(key)) {
            return s.unavailable().getOrDefault(key, "Réglage indisponible pour ce monde.");
        }
        return null;
    }

    /** Valeur canonique, ou {@code null} si la saisie est invalide. */
    static String normalize(WorldSettingsSnapshot s, String key, String raw) {
        String v = raw == null ? "" : raw.trim();
        if (key.startsWith(WorldSetting.RULE_PREFIX)) {
            return s.rule(key.substring(WorldSetting.RULE_PREFIX.length()))
                    .map(r -> "BOOLEAN".equals(r.type()) ? bool(v) : integer(v, Integer.MIN_VALUE, Integer.MAX_VALUE))
                    .orElse(null);
        }
        return switch (WorldSetting.byKey(key).orElse(null)) {
            case DIFFICULTY -> oneOf(v, DIFFICULTIES);
            case GAMEMODE -> oneOf(v, GAMEMODES);
            case WEATHER -> oneOf(v, WEATHERS);
            case PVP, MONSTERS, ANIMALS, ADVANCE_TIME, ADVANCE_WEATHER -> bool(v);
            case TIME -> integer(v, 0, 23_999);
            case BORDER_SIZE -> decimal(v, 1, MAX_BORDER_SIZE);
            case BORDER_CENTER -> pair(v);
            case SPAWN -> spawn(v, s.minHeight(), s.maxHeight());
            case null -> null;
        };
    }

    private static String invalidReason(WorldSettingsSnapshot s, String key) {
        if (key.startsWith(WorldSetting.RULE_PREFIX)) {
            return s.rule(key.substring(WorldSetting.RULE_PREFIX.length()))
                    .map(r -> "BOOLEAN".equals(r.type()) ? "Valeur attendue : oui ou non." : "Valeur attendue : un nombre entier.")
                    .orElse("Valeur invalide.");
        }
        return switch (WorldSetting.byKey(key).orElseThrow()) {
            case DIFFICULTY -> "Difficulté attendue : Paisible, Facile, Normale ou Difficile.";
            case GAMEMODE -> "Mode attendu : Survie, Aventure, Créatif ou Spectateur.";
            case WEATHER -> "Météo attendue : dégagée, pluie ou orage.";
            case PVP, MONSTERS, ANIMALS, ADVANCE_TIME, ADVANCE_WEATHER -> "Valeur attendue : oui ou non.";
            case TIME -> "Heure attendue : un nombre de 0 à 23999 (6000 = midi, 18000 = minuit).";
            case BORDER_SIZE -> "Diamètre attendu : de 1 à 59999968 blocs.";
            case BORDER_CENTER -> "Centre attendu : « x, z » (deux nombres entre -29999984 et 29999984).";
            case SPAWN -> "Point attendu : « x, y, z » ou « x, y, z, orientation » avec y entre " + s.minHeight()
                    + " et " + (s.maxHeight() - 1) + ".";
        };
    }

    // ---- Protections LodyQuests -----------------------------------------------------------------

    private static String protection(WorldSettingsSnapshot s, String key, String proposed, Map<String, String> all,
                                     String proposedSpawn) {
        boolean hub = s.hasRole("HUB");
        boolean claims = s.hasRole("CLAIMS");
        boolean wild = s.hasRole("WILD");
        if (hub && Set.of(WorldSetting.ADVANCE_TIME.key(), WorldSetting.TIME.key(), WorldSetting.ADVANCE_WEATHER.key(),
                WorldSetting.WEATHER.key()).contains(key)) {
            return "Hub : jour et météo permanents imposés par RPGQuest (réappliqués à chaque démarrage). "
                    + "Réglage refusé pour ne pas contredire cette protection.";
        }
        if ((hub || claims) && WorldSetting.PVP.key().equals(key) && "true".equals(proposed)) {
            return (hub ? "Hub" : "Monde des claims") + " : RPGQuest y annule tout dégât subi par un joueur ; "
                    + "activer le PvP n'aurait aucun effet et contredirait cette protection.";
        }
        if ((hub || claims) && WorldSetting.MONSTERS.key().equals(key) && "true".equals(proposed)) {
            return (hub ? "Hub" : "Monde des claims") + " : RPGQuest y empêche toute apparition de monstre hostile ; "
                    + "autoriser les monstres contredirait cette protection.";
        }
        if (wild && WorldSetting.DIFFICULTY.key().equals(key) && "PEACEFUL".equals(proposed)) {
            return "Wild : la difficulté Paisible fait disparaître tous les monstres, y compris les mobs spéciaux, "
                    + "les boss communautaires et les boss de quête. Réglage refusé.";
        }
        if (WorldSetting.GAMEMODE.key().equals(key) && s.players() > 0) {
            return s.players() + " joueur(s) présent(s) : Multiverse leur imposerait ce mode immédiatement. "
                    + "Réessayer quand le monde est vide (le mode ne doit viser que les arrivées).";
        }
        if (WorldSetting.BORDER_SIZE.key().equals(key) || WorldSetting.BORDER_CENTER.key().equals(key)
                || WorldSetting.SPAWN.key().equals(key)) {
            return spawnOutsideBorder(s, all, proposedSpawn);
        }
        return null;
    }

    /** Le point d'arrivée (actuel ou proposé) doit rester dans la bordure (actuelle ou proposée). */
    private static String spawnOutsideBorder(WorldSettingsSnapshot s, Map<String, String> all, String proposedSpawn) {
        String size = pick(s, all, WorldSetting.BORDER_SIZE.key());
        String center = pick(s, all, WorldSetting.BORDER_CENTER.key());
        String spawn = proposedSpawn != null ? proposedSpawn : s.values().get(WorldSetting.SPAWN.key());
        if (size == null || center == null || spawn == null) {
            return null;
        }
        double half = Double.parseDouble(size) / 2d;
        String[] c = center.split(",");
        String[] p = spawn.split(",");
        double dx = Math.abs(Double.parseDouble(p[0]) + 0.5 - Double.parseDouble(c[0]));
        double dz = Math.abs(Double.parseDouble(p[2]) + 0.5 - Double.parseDouble(c[1]));
        if (dx > half || dz > half) {
            return "Le point d'arrivée (" + p[0] + ", " + p[1] + ", " + p[2] + ") serait hors de la bordure : les "
                    + "joueurs y arriveraient en prenant des dégâts. Ajuster la bordure ou le point d'arrivée.";
        }
        return null;
    }

    private static String pick(WorldSettingsSnapshot s, Map<String, String> all, String key) {
        String raw = all.get(key);
        String normalized = raw == null ? null : normalize(s, key, raw);
        return normalized != null ? normalized : s.values().get(key);
    }

    private static java.util.Optional<String> warning(WorldSettingsSnapshot s, String key, String proposed) {
        if (s.hasRole("WILD") && WorldSetting.MONSTERS.key().equals(key) && "false".equals(proposed)) {
            return java.util.Optional.of("Wild : sans apparition naturelle de monstres, le Wild perd son danger "
                    + "ordinaire. Les mobs spéciaux et boss RPGQuest continuent d'apparaître.");
        }
        if (WorldSetting.BORDER_SIZE.key().equals(key) && s.players() > 0) {
            return java.util.Optional.of(s.players() + " joueur(s) dans ce monde : ceux qui se trouveraient hors de la "
                    + "nouvelle bordure subiraient des dégâts jusqu'à y revenir.");
        }
        return java.util.Optional.empty();
    }

    // ---- Effets attendus ------------------------------------------------------------------------

    private static String effect(WorldSettingsSnapshot s, String key, String proposed) {
        boolean on = "true".equals(proposed);
        String persist = WorldSettingsSnapshot.WRITER_MULTIVERSE.equals(s.writers().get(key))
                ? " Enregistré dans Multiverse-Core (conservé au redémarrage)." : "";
        if (key.startsWith(WorldSetting.RULE_PREFIX)) {
            return "La règle prend la valeur " + proposed + " dans ce monde seulement (conservée au redémarrage).";
        }
        return switch (WorldSetting.byKey(key).orElseThrow()) {
            case DIFFICULTY -> "Difficulté changée immédiatement pour tous les joueurs de ce monde." + persist;
            case PVP -> (on ? "Les joueurs pourront se blesser entre eux dans ce monde."
                    : "Les joueurs ne pourront plus se blesser entre eux dans ce monde.") + persist;
            case MONSTERS -> (on ? "Les monstres apparaissent de nouveau naturellement."
                    : "Plus aucune nouvelle apparition naturelle de monstre ; ceux déjà présents restent.") + persist;
            case ANIMALS -> (on ? "Les animaux apparaissent de nouveau naturellement."
                    : "Plus aucune nouvelle apparition naturelle d'animal ; ceux déjà présents restent.") + persist;
            case GAMEMODE -> "Multiverse donnera ce mode aux joueurs qui entrent dans ce monde." + persist;
            case ADVANCE_TIME -> on ? "L'heure avance de nouveau (cycle jour/nuit)." : "L'heure reste figée.";
            case TIME -> "L'heure de ce monde passe à " + proposed + " (" + timeLabel(Integer.parseInt(proposed))
                    + ") pour tous ses joueurs.";
            case ADVANCE_WEATHER -> on ? "La météo évolue de nouveau d'elle-même." : "La météo reste figée.";
            case WEATHER -> "Météo changée immédiatement ; elle peut encore évoluer si « Météo qui change » est actif.";
            case BORDER_CENTER, BORDER_SIZE -> "Bordure déplacée/redimensionnée immédiatement (conservée au redémarrage).";
            case SPAWN -> "Nouveau point d'arrivée du monde (réapparition sans lit, portails « point d'arrivée du monde »)."
                    + persist;
        };
    }

    static String timeLabel(int ticks) {
        int hours = (ticks / 1000 + 6) % 24;
        int minutes = (ticks % 1000) * 60 / 1000;
        return String.format(Locale.ROOT, "%02dh%02d", hours, minutes);
    }

    // ---- Normalisation --------------------------------------------------------------------------

    private static String oneOf(String v, Set<String> allowed) {
        String u = v.toUpperCase(Locale.ROOT);
        return allowed.contains(u) ? u : null;
    }

    private static String bool(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "true", "oui" -> "true";
            case "false", "non" -> "false";
            default -> null;
        };
    }

    private static String integer(String v, long min, long max) {
        try {
            long n = Long.parseLong(v);
            return n < min || n > max ? null : String.valueOf(n);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String decimal(String v, double min, double max) {
        Double d = parse(v);
        return d == null || d < min || d > max ? null : number(d);
    }

    private static String pair(String v) {
        String[] parts = v.split("[,;\\s]+");
        if (parts.length != 2) {
            return null;
        }
        Double x = parse(parts[0]);
        Double z = parse(parts[1]);
        if (x == null || z == null || Math.abs(x) > MAX_COORDINATE || Math.abs(z) > MAX_COORDINATE) {
            return null;
        }
        return number(x) + "," + number(z);
    }

    private static String spawn(String v, int minHeight, int maxHeight) {
        String[] parts = v.split("[,;\\s]+");
        if (parts.length != 3 && parts.length != 4) {
            return null;
        }
        Double x = parse(parts[0]);
        Double y = parse(parts[1]);
        Double z = parse(parts[2]);
        Double yaw = parts.length == 4 ? parse(parts[3]) : Double.valueOf(0);
        if (x == null || y == null || z == null || yaw == null || Math.abs(x) > MAX_COORDINATE
                || Math.abs(z) > MAX_COORDINATE || y < minHeight || y >= maxHeight || yaw < -180 || yaw > 360) {
            return null;
        }
        return spawnValue(x, y, z, yaw);
    }

    /** Forme canonique d'un point d'arrivée : coordonnées de bloc + orientation entière dans [-180, 180[. */
    public static String spawnValue(double x, double y, double z, double yaw) {
        long wrapped = Math.round(yaw);
        wrapped = ((wrapped + 180) % 360 + 360) % 360 - 180;
        return (long) Math.floor(x) + "," + (long) Math.floor(y) + "," + (long) Math.floor(z) + "," + wrapped;
    }

    /** Nombre canonique : entier sans décimale, sinon deux décimales au plus. */
    public static String number(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return new java.math.BigDecimal(d).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
                .toPlainString();
    }

    private static Double parse(String v) {
        try {
            double d = Double.parseDouble(v.trim());
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Réglages dans l'ordre de la fiche, puis règles avancées par nom. */
    private static java.util.Comparator<String> keyOrder() {
        return java.util.Comparator.comparingInt((String k) -> WorldSetting.byKey(k).map(Enum::ordinal)
                .orElse(Integer.MAX_VALUE)).thenComparing(k -> k);
    }
}
