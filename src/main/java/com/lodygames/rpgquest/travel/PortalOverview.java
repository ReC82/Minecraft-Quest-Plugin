package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.claim.ClaimWorldAccessGuard;
import com.lodygames.rpgquest.config.RandomSafeArrivalConfig;
import com.lodygames.rpgquest.permission.RpgPermissions;
import com.lodygames.rpgquest.travel.model.Destination;
import com.lodygames.rpgquest.travel.model.PortalDefinition;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Vue en <strong>lecture seule</strong> de tous les portails du serveur (issue #564, #228-P1) : ce
 * que la page « Portails » de PlugAdmin affiche.
 *
 * <p>Un seul modèle pour les deux moteurs existants : les portails entre mondes ({@code
 * world-portals/}, {@link WorldPortalRegistry} — ceux de DEV, {@code hub_to_wild} et {@code
 * hub_to_claims}) et les portails de zone à conditions ({@code portals/}, {@link PortalService}),
 * marqués « ancien format » (décision Q2 de l'étude #331/#228). Les gardes affichés sont ceux que
 * le serveur applique réellement : leur règle d'application est celle des gardes eux-mêmes
 * ({@link WildEntryWarningService#appliesTo}, {@link ClaimWorldAccessGuard#appliesTo}) et les
 * textes de l'avertissement Wild sont ses constantes.</p>
 *
 * <p>Rien ici ne crée, ne modifie, n'active ni ne téléporte : l'assemblage ne lit que des relevés
 * en mémoire et se teste sans serveur.</p>
 */
public final class PortalOverview {

    /** Moteur d'origine d'un portail. */
    public enum Kind {
        /** {@code world-portals/<id>.yml} : vers un monde entier, gardes Wild/Claims. */
        WORLD_PORTAL,
        /** {@code portals/<id>.yml} : vers une destination nommée, conditions et canalisation. */
        ZONE_PORTAL
    }

    /** Mondes à rôle : {@code hub.world}, {@code travel.wild-world}, {@code claims.world}. */
    public record Roles(String hub, String wild, String claims) {
    }

    /** Paramètres de l'arrivée {@code CLAIM_HOME} hors claim ({@code claims.arrival.*}). */
    public record ClaimArrival(int minRadius, int maxRadius, int spacing, int attempts) {
    }

    /**
     * Tout ce que la collecte a relevé.
     *
     * @param worldPortalFiles fichier d'origine de chaque portail de {@code worldPortals}, même rang
     * @param loadedWorlds     mondes chargés → point d'arrivée {@code "x, y, z"}
     */
    public record Inputs(List<WorldPortalDefinition> worldPortals, List<String> worldPortalFiles,
                         List<PortalLoadIssue> worldPortalSkipped, List<PortalDefinition> zonePortals,
                         List<PortalLoadIssue> zonePortalIssues, List<Destination> destinations,
                         Map<String, String> loadedWorlds, Roles roles, RandomSafeArrivalConfig randomSafe,
                         ClaimArrival claimArrival) {
    }

    /** Un problème lisible : {@code code} renvoie à l'aide du panel ({@code DiagnosticHelp}). */
    public record Issue(String code, String severity, String message) {
    }

    /** Un texte montré au joueur, tel quel. */
    public record Text(String label, String value) {
    }

    /** Une règle appliquée par le serveur avant la téléportation. */
    public record Guard(String code, String title, List<String> rules, List<Text> texts) {
    }

    /** Une condition d'accès d'un portail de zone. */
    public record Condition(String label, String value) {
    }

    /**
     * Un portail. {@code destinationLoaded} vaut {@code null} quand le monde d'arrivée n'est pas
     * connu (destination nommée absente).
     *
     * @param name nom affiché <em>déduit</em> de la destination : aucun nom n'est encore stocké
     */
    public record Entry(String id, String name, Kind kind, boolean enabled, String file,
                        String world, boolean worldLoaded, String worldRole,
                        int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                        String destinationWorld, Boolean destinationLoaded, String destinationRole,
                        String destinationId, String destinationPoint, String strategy, List<String> arrival,
                        List<String> trigger, List<Guard> guards, List<Condition> conditions, List<Issue> issues) {

        public String size() {
            return (maxX - minX + 1) + " × " + (maxY - minY + 1) + " × " + (maxZ - minZ + 1);
        }
    }

    /** Résultat complet de {@code portal.list}. */
    public record View(boolean available, Roles roles, List<Entry> portals, List<Issue> issues) {

        public static View unavailable() {
            return new View(false, new Roles("", "", ""), List.of(), List.of());
        }
    }

    private PortalOverview() {
    }

    /** Assemble la vue : portails entre mondes (ordre de chargement), puis portails de zone. */
    public static View build(Inputs in) {
        List<Entry> entries = new ArrayList<>();
        List<WorldPortalDefinition> wps = in.worldPortals();
        for (int i = 0; i < wps.size(); i++) {
            String file = i < in.worldPortalFiles().size() ? in.worldPortalFiles().get(i) : "";
            entries.add(worldPortal(wps.get(i), i, file, in));
        }
        for (PortalDefinition p : in.zonePortals()) {
            entries.add(zonePortal(p, in));
        }
        List<Issue> issues = new ArrayList<>();
        for (PortalLoadIssue skipped : in.worldPortalSkipped()) {
            issues.add(new Issue("PORTAL_FILE_SKIPPED", "error", "Fichier world-portals/" + skipped.file()
                    + " ignoré au chargement : " + skipped.message() + " Ce portail n'existe pas en jeu."));
        }
        for (PortalLoadIssue skipped : in.zonePortalIssues()) {
            issues.add(new Issue("PORTAL_FILE_SKIPPED", "error", "Fichier portals/" + skipped.file()
                    + " refusé au chargement : " + skipped.message()));
        }
        return new View(true, in.roles(), List.copyOf(entries), List.copyOf(issues));
    }

    // ---- Portails entre mondes ------------------------------------------------------------------

    private static Entry worldPortal(WorldPortalDefinition p, int index, String file, Inputs in) {
        boolean sourceLoaded = in.loadedWorlds().containsKey(p.world());
        boolean destLoaded = in.loadedWorlds().containsKey(p.destinationWorld());
        List<Guard> guards = new ArrayList<>();
        if (WildEntryWarningService.appliesTo(p, in.roles().wild())) {
            guards.add(wildGuard());
        }
        if (ClaimWorldAccessGuard.appliesTo(p, in.roles().claims())) {
            guards.add(claimsGuard());
        }
        List<Issue> issues = new ArrayList<>();
        if (!p.enabled()) {
            issues.add(new Issue("PORTAL_DISABLED", "info", "Portail désactivé (enabled: false) : "
                    + "entrer dans sa zone ne téléporte personne."));
        }
        if (!sourceLoaded) {
            issues.add(new Issue("PORTAL_SOURCE_NOT_LOADED", "warning", "Le monde de départ « " + p.world()
                    + " » n'est pas chargé : personne ne peut atteindre ce portail."));
        }
        if (!destLoaded) {
            issues.add(new Issue("PORTAL_DESTINATION_NOT_LOADED", p.enabled() ? "error" : "warning",
                    "Le monde d'arrivée « " + p.destinationWorld() + " » n'est pas chargé : la téléportation "
                            + "échoue et le joueur reste sur place."));
        }
        if (p.destinationWorld().equals(p.world())) {
            issues.add(new Issue("PORTAL_SAME_WORLD", "info", "Le portail mène au monde où il se trouve."));
        }
        if (p.destinationStrategy() == com.lodygames.rpgquest.travel.model.DestinationStrategy.CLAIM_HOME
                && !p.destinationWorld().equals(in.roles().claims())) {
            issues.add(new Issue("PORTAL_CLAIM_HOME_OUTSIDE_CLAIMS", "warning", "Arrivée « claim du joueur » vers « "
                    + p.destinationWorld() + " », qui n'est pas le monde des claims (" + in.roles().claims()
                    + ") : le garde Claims ne s'applique pas."));
        }
        if (!file.isEmpty() && !file.equals(p.id() + ".yml")) {
            issues.add(new Issue("PORTAL_FILE_NAME_MISMATCH", "warning", "Défini dans world-portals/" + file
                    + " au lieu de " + p.id() + ".yml : les commandes /rpgadmin worldportal enable/disable/delete "
                    + "écrivent ou cherchent " + p.id() + ".yml."));
        }
        List<String> sameId = new ArrayList<>();
        List<WorldPortalDefinition> all = in.worldPortals();
        for (int j = 0; j < all.size(); j++) {
            if (j != index && all.get(j).id().equals(p.id())) {
                sameId.add(j < in.worldPortalFiles().size() ? in.worldPortalFiles().get(j) : "?");
            }
        }
        if (!sameId.isEmpty()) {
            issues.add(new Issue("PORTAL_DUPLICATE_ID", "error", "L'id « " + p.id() + " » est aussi déclaré par "
                    + String.join(", ", sameId) + " : les commandes n'atteignent que le premier fichier, mais "
                    + "chaque zone reste active en jeu."));
        }
        for (int j = 0; j < all.size(); j++) {
            WorldPortalDefinition other = all.get(j);
            if (j != index && p.overlaps(other)) {
                String first = j < index ? other.id() : p.id();
                issues.add(new Issue("PORTAL_OVERLAP", "warning", "Sa zone recouvre celle de « " + other.id()
                        + " » : là où elles se recouvrent, le serveur ne consulte que « " + first
                        + " » (premier fichier dans l'ordre alphabétique), même s'il est désactivé."));
            }
        }
        for (PortalDefinition zone : in.zonePortals()) {
            if (overlaps(p, zone)) {
                issues.add(new Issue("PORTAL_OVERLAP_ENGINES", "warning", "Sa zone recouvre celle du portail de zone "
                        + "« " + zone.id() + " » (ancien format) : un joueur peut déclencher les deux."));
            }
        }
        return new Entry(p.id(), nameFor(p.destinationWorld(), in.roles()), Kind.WORLD_PORTAL, p.enabled(), file,
                p.world(), sourceLoaded, role(p.world(), in.roles()),
                p.minX(), p.minY(), p.minZ(), p.maxX(), p.maxY(), p.maxZ(),
                p.destinationWorld(), destLoaded, role(p.destinationWorld(), in.roles()), "", "",
                p.destinationStrategy().name(), arrival(p, in), worldPortalTrigger(),
                List.copyOf(guards), List.of(), List.copyOf(issues));
    }

    private static List<String> arrival(WorldPortalDefinition p, Inputs in) {
        String spawn = in.loadedWorlds().get(p.destinationWorld());
        String spawnText = spawn == null ? "point d'arrivée du monde (inconnu : monde non chargé)"
                : "point d'arrivée du monde (" + spawn + ")";
        RandomSafeArrivalConfig rs = in.randomSafe();
        return switch (p.destinationStrategy()) {
            case WORLD_SPAWN -> List.of("Arrivée au " + spawnText + ", relu à chaque passage.");
            case RANDOM_SAFE -> List.of(
                    "Point sûr aléatoire entre " + rs.minRadius() + " et " + rs.maxRadius()
                            + " blocs du point d'arrivée du monde (travel.random-safe-arrival).",
                    rs.maxAttempts() + " tentatives au plus ; sinon repli sur le " + spawnText + ".");
            case CLAIM_HOME -> List.of(
                    "Joueur qui possède un claim principal : arrivée sur son claim.",
                    "Sinon : point sûr entre " + in.claimArrival().minRadius() + " et " + in.claimArrival().maxRadius()
                            + " blocs du point d'arrivée, à au moins " + in.claimArrival().spacing()
                            + " blocs de tout claim (claims.arrival), " + in.claimArrival().attempts() + " tentatives.",
                    "Repli : point sûr aléatoire (travel.random-safe-arrival), puis " + spawnText + ".");
        };
    }

    private static List<String> worldPortalTrigger() {
        return List.of(
                "Téléportation immédiate en entrant dans la zone (de l'extérieur vers l'intérieur), sans attente ni coût.",
                "Répit de 2 secondes après une arrivée ou une connexion : pas de renvoi en boucle.",
                "Aucune condition de niveau, de quête ni de permission sur ce type de portail.");
    }

    private static Guard wildGuard() {
        return new Guard("WILD_WARNING", "Avertissement avant d'entrer dans le Wild", List.of(
                "Avant chaque départ, une fenêtre Minecraft demande de confirmer. Fermer la fenêtre = annuler.",
                "« Ne plus afficher » est mémorisé par joueur (variable " + WildEntryWarningService.WARNING_HIDDEN_KEY
                        + ") ; le message de préparation reste affiché.",
                "Aucune inspection d'inventaire : le texte énonce le risque de la zone, il ne juge pas l'équipement.",
                "Une seule demande à la fois par joueur ; après « Annuler », il faut ressortir puis rentrer."),
                List.of(new Text("Message", WildEntryWarningService.WARNING_TEXT),
                        new Text("Indice", WildEntryWarningService.GUARD_HINT),
                        new Text("Bouton", WildEntryWarningService.ENTER_LABEL),
                        new Text("Bouton", WildEntryWarningService.ENTER_AND_HIDE_LABEL),
                        new Text("Bouton", WildEntryWarningService.CANCEL_LABEL),
                        new Text("Après « Annuler »", WildEntryWarningService.CANCEL_TEXT),
                        new Text("Après confirmation", WildEntryWarningService.PREPARING_TEXT)));
    }

    private static Guard claimsGuard() {
        return new Guard("CLAIMS_ACCESS", "Accès au monde des claims", List.of(
                "Entrée réservée au joueur qui a débloqué son premier terrain (variable CLAIM_TIER_1) "
                        + "ou qui possède déjà un claim principal ; sinon il reste sur place et est orienté vers le Guide.",
                "Moyen de retour garanti : une Pierre de retour est remise avant le départ ; entrée refusée si "
                        + "l'inventaire est plein ou si le retour au Hub ne se résout pas.",
                "Être OP ne suffit pas : seul le mode bypass administrateur (/rpgadmin bypass on + "
                        + RpgPermissions.BYPASS_CLAIM_WORLD + " ou rpgquest.admin.world) passe outre."),
                List.of());
    }

    // ---- Portails de zone (ancien format) -------------------------------------------------------

    private static Entry zonePortal(PortalDefinition p, Inputs in) {
        boolean sourceLoaded = in.loadedWorlds().containsKey(p.world());
        Optional<Destination> dest = p.hasDestination()
                ? in.destinations().stream().filter(d -> d.id().equals(p.destinationId())).findFirst()
                : Optional.empty();
        String destWorld = dest.map(Destination::world).orElse("");
        Boolean destLoaded = dest.isPresent() ? in.loadedWorlds().containsKey(destWorld) : null;
        List<Issue> issues = new ArrayList<>();
        issues.add(new Issue("PORTAL_LEGACY_FORMAT", "info", "Portail de zone (portals/, /rpgadmin portal) : "
                + "ancien format, appelé à rejoindre le format unique des portails."));
        if (!sourceLoaded) {
            issues.add(new Issue("PORTAL_SOURCE_NOT_LOADED", "warning", "Le monde de départ « " + p.world()
                    + " » n'est pas chargé : personne ne peut atteindre ce portail."));
        }
        if (!p.hasDestination()) {
            issues.add(new Issue("PORTAL_ZONE_NO_DESTINATION", "warning", "Aucune destination : le portail "
                    + "ne téléporte personne."));
        } else if (dest.isEmpty()) {
            issues.add(new Issue("PORTAL_ZONE_DESTINATION_UNKNOWN", "error", "La destination « " + p.destinationId()
                    + " » n'existe pas dans destinations/ : la téléportation échoue."));
        } else if (!Boolean.TRUE.equals(destLoaded)) {
            issues.add(new Issue("PORTAL_DESTINATION_NOT_LOADED", "error", "Le monde d'arrivée « " + destWorld
                    + " » n'est pas chargé : la téléportation échoue et le joueur reste sur place."));
        }
        String point = dest.map(d -> String.format(Locale.ROOT, "%s %.1f, %.1f, %.1f (regard %.0f°)",
                d.world(), d.x(), d.y(), d.z(), d.yaw())).orElse("");
        return new Entry(p.id(), dest.isPresent() ? nameFor(destWorld, in.roles()) : "Portail de zone",
                Kind.ZONE_PORTAL, true, "", p.world(), sourceLoaded, role(p.world(), in.roles()),
                p.minX(), p.minY(), p.minZ(), p.maxX(), p.maxY(), p.maxZ(),
                destWorld, destLoaded, role(destWorld, in.roles()),
                p.hasDestination() ? p.destinationId() : "", point, "FIXED_POINT",
                dest.isPresent() ? List.of("Point fixe nommé « " + p.destinationId() + " » : " + point + ".") : List.of(),
                List.of("Canalisation de " + p.channelSeconds() + " s en restant dans la zone ; annulée par un "
                        + "déplacement, un dégât ou un départ."), List.of(), conditions(p), List.copyOf(issues));
    }

    private static List<Condition> conditions(PortalDefinition p) {
        List<Condition> out = new ArrayList<>();
        if (p.requiredPermission() != null) {
            out.add(new Condition("Permission", p.requiredPermission()));
        }
        if (p.requiredQuestId() != null) {
            out.add(new Condition("Quête", p.requiredQuestId() + " : " + p.requiredQuestState()));
        }
        if (p.requiredLevel() != null) {
            out.add(new Condition("Niveau RPG minimum", String.valueOf(p.requiredLevel())));
        }
        if (p.cost() != null) {
            out.add(new Condition("Coût", String.valueOf(p.cost())));
        }
        out.add(new Condition("Attente entre deux passages", p.cooldownSeconds() + " s"));
        return List.copyOf(out);
    }

    // ---- Briques --------------------------------------------------------------------------------

    private static boolean overlaps(WorldPortalDefinition a, PortalDefinition b) {
        return a.world().equals(b.world())
                && a.minX() <= b.maxX() && a.maxX() >= b.minX()
                && a.minY() <= b.maxY() && a.maxY() >= b.minY()
                && a.minZ() <= b.maxZ() && a.maxZ() >= b.minZ();
    }

    static String role(String world, Roles roles) {
        if (world == null || world.isEmpty()) {
            return "";
        }
        if (world.equals(roles.hub())) {
            return "HUB";
        }
        if (world.equals(roles.wild())) {
            return "WILD";
        }
        return world.equals(roles.claims()) ? "CLAIMS" : "";
    }

    private static String nameFor(String destinationWorld, Roles roles) {
        return switch (role(destinationWorld, roles)) {
            case "WILD" -> "Portail vers le Wild";
            case "CLAIMS" -> "Portail vers les Claims";
            case "HUB" -> "Portail vers le Hub";
            default -> "Portail vers " + destinationWorld;
        };
    }
}
