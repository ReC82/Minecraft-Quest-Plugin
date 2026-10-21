package com.lodygames.rpgquest.world.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation <strong>pure</strong> d'un chargement, d'un déchargement, d'un import de dossier ou
 * d'un changement de « charger au démarrage » (issue #563, #331-W4) : aucun appel serveur, aucune
 * écriture. Les mêmes règles servent au relevé (ce que PlugAdmin propose) et à l'exécution
 * (revérifiées sur le thread principal juste avant d'agir).
 *
 * <p>Refus imposés : Multiverse-Core absent ; monde principal et ses dimensions liées ; monde à
 * rôle Hub / Wild / Claims ; monde rechargé par RPGQuest ({@code plugins/RPGQuest/worlds.yml}) ;
 * pour décharger, des joueurs présents ou un portail actif qui y mène. Rien ici ne supprime,
 * ne régénère ni ne retire un monde du fichier de Multiverse-Core.</p>
 */
public final class WorldStatePlanner {

    /** Nom de monde accepté : celui d'un dossier, sans séparateur de chemin. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}");
    /** Dossiers du serveur qui ne sont jamais des mondes. */
    private static final Set<String> RESERVED = Set.of("plugins", "logs", "libraries", "versions", "cache", "config",
            "crash-reports", "debug", "bundler", "backups");
    private static final Set<String> DIMENSIONS = Set.of("NORMAL", "NETHER", "THE_END");

    public enum Operation {
        LOAD("Charger"), UNLOAD("Décharger"), IMPORT("Importer"), AUTOLOAD("Charger au démarrage");

        private final String label;

        Operation(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Un monde que Multiverse-Core connaît (chargé ou non). {@code environment} {@code ""} = inconnu. */
    public record Known(String name, boolean loaded, boolean autoLoad, String environment) {
    }

    /**
     * Un dossier du répertoire des mondes qui contient un monde Minecraft ({@code level.dat}).
     * {@code dimension} : déduite du contenu ({@code DIM-1} = Nether, {@code DIM1} = End, {@code
     * region} = monde normal), {@code ""} si rien ne permet de le dire.
     */
    public record Folder(String name, String dimension) {
    }

    /**
     * Un portail et le monde où il mène. {@code active} : un portail entre mondes activé, ou un
     * portail de zone (ancien format, toujours actif).
     */
    public record PortalTarget(String id, String from, String to, boolean active) {
    }

    /**
     * État du serveur au moment de la validation.
     *
     * @param roles   rôles par monde (clé en minuscules) : {@code HUB}, {@code WILD}, {@code CLAIMS}
     * @param players joueurs présents par monde chargé (clé = nom exact du monde chargé)
     * @param known   mondes connus de Multiverse-Core (clé en minuscules) ; vide si absent
     * @param folders dossiers de monde relevés hors du thread principal
     */
    public record Context(boolean multiverseAvailable, String multiverseReason, String mainWorld,
                          Map<String, List<String>> roles, Set<String> rpgquestManaged, Map<String, Integer> players,
                          Map<String, Known> known, List<Folder> folders, List<PortalTarget> portals) {

        public Context {
            roles = roles == null ? Map.of() : Map.copyOf(roles);
            rpgquestManaged = rpgquestManaged == null ? Set.of() : Set.copyOf(rpgquestManaged);
            players = players == null ? Map.of() : Map.copyOf(players);
            known = known == null ? Map.of() : Map.copyOf(known);
            folders = folders == null ? List.of() : List.copyOf(folders);
            portals = portals == null ? List.of() : List.copyOf(portals);
        }

        boolean loaded(String world) {
            return players.keySet().stream().anyMatch(n -> n.equalsIgnoreCase(world));
        }

        int playersIn(String world) {
            return players.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(world))
                    .mapToInt(Map.Entry::getValue).sum();
        }

        Known knownWorld(String world) {
            return known.get(key(world));
        }
    }

    /**
     * Une demande. {@code environment} ne sert qu'à l'import ({@code NORMAL}, {@code NETHER},
     * {@code THE_END}) ; {@code autoLoad} = « charger au démarrage » voulu après l'opération.
     */
    public record Request(Operation operation, String world, String environment, boolean autoLoad) {
    }

    /** Verdict : {@code ok} = rien ne s'y oppose ; sinon {@code errors} dit pourquoi, en français. */
    public record Plan(Operation operation, String world, String environment, boolean autoLoad, boolean ok,
                       List<String> errors, List<String> warnings, String summary) {
    }

    private WorldStatePlanner() {
    }

    public static Plan plan(Request request, Context context) {
        String world = request.world() == null ? "" : request.world().trim();
        Operation op = request.operation();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String environment = request.environment() == null ? "" : request.environment().trim().toUpperCase(Locale.ROOT);
        if (!context.multiverseAvailable()) {
            errors.add("Multiverse-Core n'est pas utilisable (" + context.multiverseReason() + ") : RPGQuest ne charge, "
                    + "ne décharge ni n'importe aucun monde sans lui.");
        }
        if (!NAME.matcher(world).matches()) {
            errors.add("Nom de monde invalide : lettres, chiffres, « _ », « - » et « . », 64 caractères au plus.");
            return new Plan(op, world, environment, request.autoLoad(), false, errors, warnings, "");
        }
        errors.addAll(essentialReasons(world, context));
        if (context.rpgquestManaged().stream().anyMatch(n -> n.equalsIgnoreCase(world))) {
            errors.add("« " + world + " » est rechargé au démarrage par RPGQuest (plugins/RPGQuest/worlds.yml) : "
                    + "PlugAdmin ne change pas son état.");
        }
        switch (op) {
            case LOAD -> load(world, request.autoLoad(), context, errors, warnings);
            case UNLOAD -> unload(world, context, errors, warnings);
            case IMPORT -> importFolder(world, environment, context, errors, warnings);
            case AUTOLOAD -> autoLoad(world, request.autoLoad(), context, errors);
        }
        return new Plan(op, world, environment, request.autoLoad(), errors.isEmpty(), List.copyOf(errors),
                List.copyOf(warnings), summary(op, world, environment, request.autoLoad()));
    }

    /** Raisons pour lesquelles un monde est essentiel (vide = aucune) : jamais chargé, déchargé ni importé d'ici. */
    public static List<String> essentialReasons(String world, Context context) {
        List<String> out = new ArrayList<>();
        String main = context.mainWorld() == null ? "" : context.mainWorld();
        if (!main.isEmpty() && world.equalsIgnoreCase(main)) {
            out.add("« " + world + " » est le monde principal du serveur.");
        } else if (!main.isEmpty() && (world.equalsIgnoreCase(main + "_nether") || world.equalsIgnoreCase(main + "_the_end"))) {
            out.add("« " + world + " » est une dimension du monde principal du serveur.");
        }
        List<String> roles = context.roles().getOrDefault(key(world), List.of());
        if (!roles.isEmpty()) {
            out.add("« " + world + " » a le rôle " + String.join(" / ", roles) + " dans LodyQuests (config.yml).");
        }
        return out;
    }

    private static void load(String world, boolean autoLoad, Context context, List<String> errors,
                             List<String> warnings) {
        if (context.loaded(world)) {
            errors.add("« " + world + " » est déjà chargé.");
            return;
        }
        Known known = context.knownWorld(world);
        if (known == null) {
            errors.add("Multiverse-Core ne connaît pas « " + world + " » : s'il s'agit d'un dossier présent sur le "
                    + "serveur, l'importer depuis la liste des dossiers importables.");
            return;
        }
        warnings.add("Le serveur se fige quelques secondes pendant le chargement (opération synchrone de Paper).");
        warnings.add("Le monde ne reçoit aucun rôle, portail ni waypoint : il reste à les configurer à part.");
        if (!autoLoad && known.autoLoad()) {
            warnings.add("« Charger au démarrage » sera désactivé : le monde ne reviendra pas au prochain redémarrage.");
        }
    }

    private static void unload(String world, Context context, List<String> errors, List<String> warnings) {
        if (!context.loaded(world)) {
            errors.add("« " + world + " » n'est pas chargé : rien à décharger.");
            return;
        }
        if (context.knownWorld(world) == null) {
            errors.add("« " + world + " » n'est pas géré par Multiverse-Core : une fois déchargé, PlugAdmin ne "
                    + "pourrait plus le recharger.");
        }
        int players = context.playersIn(world);
        if (players > 0) {
            errors.add(players + " joueur(s) se trouvent dans « " + world + " » : attendre qu'il soit vide.");
        }
        for (PortalTarget p : context.portals()) {
            if (world.equalsIgnoreCase(p.to()) && p.active()) {
                errors.add("Le portail actif « " + p.id() + " » (depuis « " + p.from() + " ») mène à ce monde : le "
                        + "désactiver d'abord (page Portails).");
            } else if (world.equalsIgnoreCase(p.from()) && p.active()) {
                warnings.add("Le portail « " + p.id() + " » part de ce monde : personne ne pourra l'emprunter tant "
                        + "que le monde est déchargé.");
            }
        }
        warnings.add("Le monde est enregistré puis déchargé : ses fichiers restent sur le disque, rien n'est supprimé, "
                + "et Multiverse-Core le garde dans sa liste pour le recharger.");
    }

    private static void importFolder(String world, String environment, Context context, List<String> errors,
                                     List<String> warnings) {
        if (RESERVED.contains(world.toLowerCase(Locale.ROOT))) {
            errors.add("« " + world + " » est un dossier du serveur, pas un monde.");
            return;
        }
        if (context.loaded(world) || context.knownWorld(world) != null) {
            errors.add("« " + world + " » est déjà connu du serveur : utiliser « Charger » s'il n'est pas chargé.");
            return;
        }
        Folder folder = context.folders().stream().filter(f -> f.name().equals(world)).findFirst().orElse(null);
        if (folder == null) {
            errors.add("Aucun dossier de monde importable nommé « " + world + " » (il doit contenir level.dat).");
            return;
        }
        if (!DIMENSIONS.contains(environment)) {
            errors.add("Dimension à choisir : monde normal, Nether ou End.");
            return;
        }
        if (folder.dimension().isEmpty()) {
            errors.add("Le dossier « " + world + " » ne permet pas de déterminer sa dimension (ni region, ni DIM-1, "
                    + "ni DIM1) : import refusé.");
        } else if (!folder.dimension().equals(environment)) {
            errors.add("Le dossier « " + world + " » contient un monde " + label(folder.dimension()) + ", pas un monde "
                    + label(environment) + " : import refusé.");
        }
        warnings.add("Le serveur se fige quelques secondes pendant le chargement (opération synchrone de Paper).");
        warnings.add("Le monde est ajouté à Multiverse-Core tel quel : aucun bloc n'est modifié, aucun rôle, portail ni "
                + "waypoint n'est attribué.");
    }

    private static void autoLoad(String world, boolean autoLoad, Context context, List<String> errors) {
        Known known = context.knownWorld(world);
        if (known == null) {
            errors.add("Multiverse-Core ne connaît pas « " + world + " » : rien à régler.");
            return;
        }
        if (known.autoLoad() == autoLoad) {
            errors.add("« Charger au démarrage » est déjà " + (autoLoad ? "activé" : "désactivé") + " pour « "
                    + world + " » : rien à changer.");
        }
    }

    private static String summary(Operation op, String world, String environment, boolean autoLoad) {
        String start = autoLoad ? "rechargé au démarrage" : "non rechargé au démarrage";
        return switch (op) {
            case LOAD -> "Charger « " + world + " » via Multiverse-Core, " + start + ".";
            case UNLOAD -> "Enregistrer puis décharger « " + world + " » (fichiers conservés), " + start + ".";
            case IMPORT -> "Importer le dossier « " + world + " » (monde " + label(environment) + ") dans Multiverse-Core et "
                    + "le charger, " + start + ".";
            case AUTOLOAD -> "« " + world + " » " + start + ".";
        };
    }

    /** Dossiers proposés à l'import : ni chargés, ni connus de Multiverse, ni dossiers du serveur. */
    public static List<Folder> importable(Context context) {
        List<Folder> out = new ArrayList<>();
        for (Folder f : context.folders()) {
            if (NAME.matcher(f.name()).matches() && !RESERVED.contains(f.name().toLowerCase(Locale.ROOT))
                    && !context.loaded(f.name()) && context.knownWorld(f.name()) == null) {
                out.add(f);
            }
        }
        return out;
    }

    /** Verdict de chaque opération possible pour un monde, avec son état actuel comme demande. */
    public static Map<Operation, Plan> verdicts(String world, Context context) {
        Known known = context.knownWorld(world);
        boolean autoLoad = known != null && known.autoLoad();
        Map<Operation, Plan> out = new LinkedHashMap<>();
        out.put(Operation.LOAD, plan(new Request(Operation.LOAD, world, "", known == null || autoLoad), context));
        out.put(Operation.UNLOAD, plan(new Request(Operation.UNLOAD, world, "", autoLoad), context));
        out.put(Operation.AUTOLOAD, plan(new Request(Operation.AUTOLOAD, world, "", !autoLoad), context));
        return out;
    }

    static String label(String dimension) {
        return switch (dimension) {
            case "NORMAL" -> "normal";
            case "NETHER" -> "du Nether";
            case "THE_END" -> "de l'End";
            default -> "de dimension inconnue";
        };
    }

    static String key(String world) {
        return world == null ? "" : world.toLowerCase(Locale.ROOT);
    }
}
