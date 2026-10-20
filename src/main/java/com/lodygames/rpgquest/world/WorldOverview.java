package com.lodygames.rpgquest.world;

import com.lodygames.rpgquest.waypoint.WaypointWorldEligibility;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Vue en <strong>lecture seule</strong> de tous les mondes du serveur (issue #560, #331-W1) : ce
 * que la page « Mondes » de PlugAdmin affiche.
 *
 * <p>Code RPGQuest ordinaire, sans aucun type Bukkit ni Multiverse : la collecte (thread principal,
 * adaptateur Multiverse) fournit des relevés simples, et l'assemblage — gestionnaire, rôles,
 * mondes essentiels, éléments liés, problèmes — se teste sans serveur.</p>
 *
 * <p>Rien ici ne crée, ne charge, ne décharge ni ne modifie un monde : il n'y a que des lectures.</p>
 */
public final class WorldOverview {

    /** Qui crée et recharge ce monde au démarrage. */
    public enum Manager {
        /** Déclaré dans le {@code worlds.yml} de Multiverse-Core. */
        MULTIVERSE,
        /** Déclaré dans {@code plugins/RPGQuest/worlds.yml} ({@code /rpgadmin world create}). */
        RPGQUEST,
        /** Monde principal du serveur (et ses dimensions liées) ou monde chargé par un autre moyen. */
        SERVER,
        /** Monde cité par la configuration mais introuvable : ni chargé, ni connu d'un gestionnaire. */
        UNKNOWN
    }

    /** Un monde chargé, relevé par l'API Bukkit sur le thread principal. */
    public record LoadedWorld(String name, String environment, String worldType, long seed, boolean structures,
                              int players, String difficulty, boolean pvp, boolean hardcore,
                              int spawnX, int spawnY, int spawnZ, String customGenerator) {
    }

    /** Un monde tel que Multiverse-Core le décrit (chargé ou non). Valeurs {@code null} = non lisibles. */
    public record ExternalWorld(String name, boolean loaded, String environment, String generator,
                                String generatorSettings, Long seed, String difficulty, Boolean pvp,
                                Boolean autoLoad) {
    }

    /**
     * Relevé de Multiverse-Core.
     *
     * @param available {@code false} = plugin absent, désactivé ou incompatible ({@code reason})
     * @param version   version installée, {@code ""} si inconnue
     */
    public record ExternalSource(boolean available, String version, String reason, List<ExternalWorld> worlds) {

        public ExternalSource {
            version = version == null ? "" : version;
            reason = reason == null ? "" : reason;
            worlds = worlds == null ? List.of() : List.copyOf(worlds);
        }

        public static ExternalSource unavailable(String reason) {
            return new ExternalSource(false, "", reason, List.of());
        }
    }

    /** Mondes à rôle lus dans {@code config.yml} : {@code hub.world}, {@code travel.wild-world}, {@code claims.world}. */
    public record Roles(String hub, String wild, String claims) {
    }

    /**
     * Politique de génération automatique des waypoints (#136/#137/#149) : rôles + liste explicite
     * {@code travel.waypoint.worlds}, évaluée par {@link WaypointWorldEligibility}.
     */
    public record WaypointPolicy(boolean enabled, boolean hubEnabled, Map<String, Boolean> worlds) {

        public WaypointPolicy {
            worlds = worlds == null ? Map.of() : Map.copyOf(worlds);
        }

        public WaypointPolicy(boolean enabled, boolean hubEnabled) {
            this(enabled, hubEnabled, Map.of());
        }
    }

    /** Un portail entre mondes ({@code world-portals/*.yml}). */
    public record PortalLink(String id, String world, String destinationWorld, boolean enabled, String strategy) {
    }

    /** Un élément localisé dans un monde : emplacement de bâtiment, emplacement PNJ, ville, waypoint. */
    public record Place(String id, String name, String world) {
    }

    /** Un profil de mob spécial/boss et les mondes qu'il autorise explicitement (vide = aucune restriction). */
    public record MobLink(String id, String name, Set<String> worlds) {
    }

    /** Tout ce que la collecte a relevé. */
    public record Inputs(List<LoadedWorld> loaded, String mainWorld, ExternalSource multiverse,
                         Collection<String> rpgquestManaged, Roles roles, WaypointPolicy waypoints,
                         List<PortalLink> portals, List<Place> sites, List<Place> waypointPlaces,
                         List<MobLink> mobs, List<Place> npcSpots, List<Place> cities, int onlinePlayers) {
    }

    /** Un problème lisible : {@code code} renvoie à l'aide du panel ({@code DiagnosticHelp}). */
    public record Issue(String code, String severity, String message) {
    }

    /** Éléments RPGQuest rattachés à un monde. */
    public record Links(List<PortalLink> portalsFrom, List<PortalLink> portalsTo, List<Place> sites,
                        int waypoints, boolean autoWaypoints, List<MobLink> mobs, List<Place> npcSpots,
                        List<Place> cities) {
    }

    /**
     * Un monde. Les champs objets sont {@code null} quand la donnée n'est pas lisible (monde non
     * chargé que Multiverse ne décrit pas) : le panel affiche alors « inconnu ».
     *
     * @param roles     rôles LodyQuests : {@code HUB}, {@code WILD}, {@code CLAIMS} (vide = aucun rôle)
     * @param essential monde principal ou monde à rôle : jamais déchargé ni supprimé
     */
    public record Entry(String name, boolean loaded, Manager manager, boolean main, boolean essential,
                        List<String> roles, boolean multiverseKnown, boolean rpgquestManaged,
                        String environment, String worldType, Long seed, Boolean structures,
                        Integer players, String difficulty, Boolean pvp, Boolean hardcore, String spawn,
                        String generator, String generatorSettings, Boolean autoLoad, Links links,
                        List<Issue> issues) {
    }

    /**
     * Résultat complet de {@code world.list}. {@code onlinePlayers} = joueurs connectés sur tout le
     * serveur au même instant que les compteurs par monde : leur somme doit lui être égale.
     */
    public record View(boolean available, String mainWorld, Roles roles, ExternalSource multiverse,
                       List<Entry> worlds, List<Issue> issues, int onlinePlayers) {

        public static View unavailable() {
            return new View(false, "", new Roles("", "", ""), ExternalSource.unavailable(""), List.of(), List.of(),
                    0);
        }
    }

    private WorldOverview() {
    }

    /** Assemble la vue. Ordre : monde principal, puis mondes à rôle, puis les autres par nom. */
    public static View build(Inputs in) {
        Map<String, LoadedWorld> loaded = new LinkedHashMap<>();
        in.loaded().forEach(w -> loaded.put(key(w.name()), w));
        Map<String, ExternalWorld> external = new LinkedHashMap<>();
        if (in.multiverse().available()) {
            in.multiverse().worlds().forEach(w -> external.put(key(w.name()), w));
        }
        Set<String> managed = new java.util.HashSet<>();
        in.rpgquestManaged().forEach(n -> managed.add(key(n)));

        Map<String, String> names = new LinkedHashMap<>();
        in.loaded().forEach(w -> names.putIfAbsent(key(w.name()), w.name()));
        external.values().forEach(w -> names.putIfAbsent(key(w.name()), w.name()));
        in.rpgquestManaged().forEach(n -> names.putIfAbsent(key(n), n));
        for (String role : List.of(in.roles().hub(), in.roles().wild(), in.roles().claims())) {
            if (role != null && !role.isBlank()) {
                names.putIfAbsent(key(role), role);
            }
        }

        List<Entry> entries = new ArrayList<>();
        for (String name : names.values()) {
            entries.add(entry(name, loaded.get(key(name)), external.get(key(name)), managed.contains(key(name)), in));
        }
        entries.sort(java.util.Comparator.comparingInt(WorldOverview::rank)
                .thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));

        List<Issue> issues = new ArrayList<>();
        if (!in.multiverse().available()) {
            issues.add(new Issue("WORLD_MULTIVERSE_ABSENT", "info",
                    "Multiverse-Core n'est pas lisible (" + in.multiverse().reason() + ") : seuls les mondes "
                            + "chargés sont affichés, d'après le serveur."));
        }
        return new View(true, in.mainWorld(), in.roles(), in.multiverse(), entries, issues, in.onlinePlayers());
    }

    private static Entry entry(String name, LoadedWorld bukkit, ExternalWorld mv, boolean managed, Inputs in) {
        boolean main = name.equalsIgnoreCase(in.mainWorld());
        List<String> roles = new ArrayList<>();
        if (name.equalsIgnoreCase(in.roles().hub())) {
            roles.add("HUB");
        }
        if (name.equalsIgnoreCase(in.roles().wild())) {
            roles.add("WILD");
        }
        if (name.equalsIgnoreCase(in.roles().claims())) {
            roles.add("CLAIMS");
        }
        Manager manager = manager(name, bukkit != null, mv != null, managed, in.mainWorld());
        Links links = links(name, roles, in);
        List<Issue> issues = new ArrayList<>();
        boolean isLoaded = bukkit != null;
        if (!isLoaded && !roles.isEmpty()) {
            issues.add(new Issue("WORLD_ROLE_NOT_LOADED", "error", "Le monde « " + name + " » est configuré comme "
                    + String.join(" / ", roles) + " mais n'est pas chargé sur le serveur."));
        }
        if (!isLoaded && managed) {
            issues.add(new Issue("WORLD_MANAGED_NOT_LOADED", "warning", "RPGQuest devait recharger « " + name
                    + " » au démarrage (plugins/RPGQuest/worlds.yml), mais il n'est pas chargé."));
        }
        if (managed && mv != null) {
            issues.add(new Issue("WORLD_TWO_MANAGERS", "warning", "« " + name + " » est déclaré à la fois dans "
                    + "plugins/RPGQuest/worlds.yml et dans Multiverse-Core : deux gestionnaires le rechargent."));
        }
        for (PortalLink p : links.portalsFrom()) {
            if (p.enabled() && !isLoaded) {
                issues.add(new Issue("WORLD_PORTAL_FROM_UNLOADED", "warning", "Le portail « " + p.id()
                        + " » part de ce monde, qui n'est pas chargé : personne ne peut l'emprunter."));
            }
        }
        for (PortalLink p : links.portalsTo()) {
            if (p.enabled() && !isLoaded) {
                issues.add(new Issue("WORLD_PORTAL_TO_UNLOADED", "error", "Le portail actif « " + p.id()
                        + " » mène à ce monde, qui n'est pas chargé : la téléportation échouera."));
            }
        }
        boolean mvLoaded = mv != null && mv.loaded();
        if (mv != null && Boolean.TRUE.equals(mv.autoLoad()) && !isLoaded && !mvLoaded) {
            issues.add(new Issue("WORLD_AUTOLOAD_NOT_LOADED", "info", "Multiverse doit charger « " + name
                    + " » au démarrage (auto-load) mais il n'est pas chargé actuellement."));
        }
        return new Entry(name, isLoaded, manager, main, main || !roles.isEmpty(), List.copyOf(roles), mv != null,
                managed,
                isLoaded ? bukkit.environment() : mv == null ? null : mv.environment(),
                isLoaded ? bukkit.worldType() : null,
                isLoaded ? Long.valueOf(bukkit.seed()) : mv == null ? null : mv.seed(),
                isLoaded ? bukkit.structures() : null,
                isLoaded ? bukkit.players() : null,
                isLoaded ? bukkit.difficulty() : mv == null ? null : mv.difficulty(),
                isLoaded ? Boolean.valueOf(bukkit.pvp()) : mv == null ? null : mv.pvp(),
                isLoaded ? bukkit.hardcore() : null,
                isLoaded ? bukkit.spawnX() + ", " + bukkit.spawnY() + ", " + bukkit.spawnZ() : null,
                generator(bukkit, mv), mv == null || blank(mv.generatorSettings()) ? null : mv.generatorSettings(),
                mv == null ? null : mv.autoLoad(), links, List.copyOf(issues));
    }

    /**
     * Le monde principal et ses dimensions vanilla ({@code <principal>_nether}, {@code _the_end})
     * sont créés par le serveur lui-même, même si Multiverse les répertorie aussi.
     */
    static Manager manager(String name, boolean loaded, boolean multiverse, boolean managed, String mainWorld) {
        String main = mainWorld == null ? "" : mainWorld;
        if (!main.isEmpty() && (name.equalsIgnoreCase(main) || name.equalsIgnoreCase(main + "_nether")
                || name.equalsIgnoreCase(main + "_the_end"))) {
            return Manager.SERVER;
        }
        if (managed) {
            return Manager.RPGQUEST;
        }
        if (multiverse) {
            return Manager.MULTIVERSE;
        }
        return loaded ? Manager.SERVER : Manager.UNKNOWN;
    }

    private static Links links(String name, List<String> roles, Inputs in) {
        List<PortalLink> from = in.portals().stream().filter(p -> name.equalsIgnoreCase(p.world())).toList();
        List<PortalLink> to = in.portals().stream().filter(p -> name.equalsIgnoreCase(p.destinationWorld())).toList();
        List<Place> sites = in.sites().stream().filter(s -> name.equalsIgnoreCase(s.world())).toList();
        int waypoints = (int) in.waypointPlaces().stream().filter(w -> name.equalsIgnoreCase(w.world())).count();
        WaypointPolicy policy = in.waypoints();
        boolean auto = WaypointWorldEligibility.evaluate(policy.enabled(), policy.hubEnabled(), policy.worlds(),
                in.roles().wild(), in.roles().hub(), in.roles().claims(), name).allowed();
        List<MobLink> mobs = in.mobs().stream()
                .filter(m -> m.worlds().stream().anyMatch(name::equalsIgnoreCase)).toList();
        List<Place> spots = in.npcSpots().stream().filter(s -> name.equalsIgnoreCase(s.world())).toList();
        List<Place> cities = in.cities().stream().filter(c -> name.equalsIgnoreCase(c.world())).toList();
        return new Links(from, to, sites, waypoints, auto, mobs, spots, cities);
    }

    private static String generator(LoadedWorld bukkit, ExternalWorld mv) {
        if (mv != null && !blank(mv.generator())) {
            return mv.generator();
        }
        if (bukkit != null && !blank(bukkit.customGenerator())) {
            return bukkit.customGenerator();
        }
        return null;
    }

    private static int rank(Entry e) {
        if (e.main()) {
            return 0;
        }
        return e.roles().isEmpty() ? 2 : 1;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
