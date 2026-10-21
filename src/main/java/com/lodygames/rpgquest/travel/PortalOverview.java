package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.building.model.BuildingSite;
import com.lodygames.rpgquest.claim.ClaimWorldAccessGuard;
import com.lodygames.rpgquest.config.RandomSafeArrivalConfig;
import com.lodygames.rpgquest.permission.RpgPermissions;
import com.lodygames.rpgquest.travel.model.Destination;
import com.lodygames.rpgquest.travel.model.PortalAccess;
import com.lodygames.rpgquest.travel.model.PortalDefinition;
import com.lodygames.rpgquest.travel.model.PortalMessages;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

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
     * PNJ guide du village ({@code hub.guide-npc-id}) cité par le garde Claims : {@code name} est son
     * nom affiché <em>actuel</em> (balises retirées), {@code null} s'il ne se résout pas.
     */
    public record GuideNpc(String npcId, String name) {
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
                         ClaimArrival claimArrival, GuideNpc guide,
                         Function<String, Optional<BuildingSite>> sites, List<PortalAuditLog.Entry> history,
                         Function<String, Optional<PortalBuildingLink.SiteBuilding>> buildings) {

        public Inputs {
            sites = sites == null ? id -> Optional.empty() : sites;
            history = history == null ? List.of() : List.copyOf(history);
            buildings = buildings == null ? id -> Optional.empty() : buildings;
        }

        /** Sans constructions relevées (#568) : conserve les appelants antérieurs. */
        public Inputs(List<WorldPortalDefinition> worldPortals, List<String> worldPortalFiles,
                      List<PortalLoadIssue> worldPortalSkipped, List<PortalDefinition> zonePortals,
                      List<PortalLoadIssue> zonePortalIssues, List<Destination> destinations,
                      Map<String, String> loadedWorlds, Roles roles, RandomSafeArrivalConfig randomSafe,
                      ClaimArrival claimArrival, GuideNpc guide,
                      Function<String, Optional<BuildingSite>> sites, List<PortalAuditLog.Entry> history) {
            this(worldPortals, worldPortalFiles, worldPortalSkipped, zonePortals, zonePortalIssues, destinations,
                    loadedWorlds, roles, randomSafe, claimArrival, guide, sites, history, null);
        }

        /** Sans emplacements ni journal (#565) : conserve les appelants antérieurs. */
        public Inputs(List<WorldPortalDefinition> worldPortals, List<String> worldPortalFiles,
                      List<PortalLoadIssue> worldPortalSkipped, List<PortalDefinition> zonePortals,
                      List<PortalLoadIssue> zonePortalIssues, List<Destination> destinations,
                      Map<String, String> loadedWorlds, Roles roles, RandomSafeArrivalConfig randomSafe,
                      ClaimArrival claimArrival, GuideNpc guide) {
            this(worldPortals, worldPortalFiles, worldPortalSkipped, zonePortals, zonePortalIssues, destinations,
                    loadedWorlds, roles, randomSafe, claimArrival, guide, null, null);
        }
    }

    /**
     * Ce que #565 ajoute à une entrée : état, emplacement lié, dimensions saisies, empreinte à
     * renvoyer avec toute modification, et portail essentiel (confirmation de plus).
     *
     * @param editable portail de {@code world-portals/} modifiable depuis PlugAdmin
     */
    public record Admin(String state, String storedName, String site, String siteName, int width, int height,
                        int depth, boolean essential, String fingerprint, boolean editable,
                        WorldPortalDefinition.FixedPoint point, String returnOf, List<String> returnedBy,
                        PortalAccess access, PortalMessages messages, WildEntryWarningService.Texts warningDefaults,
                        boolean warns, Construction construction) {

        static final Admin LEGACY = new Admin("ACTIVE", "", "", "", 0, 0, 0, false, "", false, null, "", List.of(),
                PortalAccess.NONE, PortalMessages.NONE, null, false, Construction.NONE);

        public Admin {
            messages = messages == null ? PortalMessages.NONE : messages;
            construction = construction == null ? Construction.NONE : construction;
        }
    }

    /**
     * Construction liée (issue #568), relevée sans rien modifier.
     *
     * @param linked     arche liée au portail ({@code ""} = aucune)
     * @param status     {@link PortalBuildingLink.Status} du lien
     * @param message    explication si le lien est rompu, sinon {@code ""}
     * @param placed     construction réellement posée sur l'emplacement ({@code ""} = rien)
     * @param placedName nom de cette construction dans la bibliothèque ({@code ""} si retirée)
     * @param rotation   rotation appliquée à la pose, en degrés
     * @param linkable   « Lier » donnerait une zone : arche de portail avec passage
     * @param zone       zone que donnerait « Lier » ({@code x1,y1,z1 → x2,y2,z2}), {@code ""} sinon
     * @param refusal    pourquoi « Lier » serait refusé, {@code ""} si {@code linkable}
     */
    public record Construction(String linked, String status, String message, String placed, String placedName,
                               int rotation, boolean linkable, String zone, String refusal) {

        public static final Construction NONE = new Construction("", PortalBuildingLink.Status.NONE.name(), "", "",
                "", 0, false, "", "");
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

    /** Une condition d'accès (portail de zone, ou portail entre mondes depuis #567). */
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
                        List<String> trigger, List<Guard> guards, List<Condition> conditions, List<Issue> issues,
                        Admin admin) {

        public String size() {
            return (maxX - minX + 1) + " × " + (maxY - minY + 1) + " × " + (maxZ - minZ + 1);
        }
    }

    /** Résultat complet de {@code portal.list}. */
    public record View(boolean available, Roles roles, List<Entry> portals, List<Issue> issues,
                       List<String> worlds, List<PortalAuditLog.Entry> history) {

        public static View unavailable() {
            return new View(false, new Roles("", "", ""), List.of(), List.of(), List.of(), List.of());
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
        List<String> worlds = new ArrayList<>(in.loadedWorlds().keySet());
        worlds.sort(null);
        return new View(true, in.roles(), List.copyOf(entries), List.copyOf(issues), List.copyOf(worlds),
                in.history());
    }

    // ---- Portails entre mondes ------------------------------------------------------------------

    private static Entry worldPortal(WorldPortalDefinition p, int index, String file, Inputs in) {
        boolean sourceLoaded = in.loadedWorlds().containsKey(p.world());
        boolean destLoaded = in.loadedWorlds().containsKey(p.destinationWorld());
        List<Guard> guards = new ArrayList<>();
        if (WildEntryWarningService.appliesTo(p, in.roles().wild())) {
            guards.add(wildGuard(p, in.roles().wild()));
        }
        if (ClaimWorldAccessGuard.appliesTo(p, in.roles().claims())) {
            guards.add(claimsGuard(in.guide()));
        }
        List<Issue> issues = new ArrayList<>();
        if (p.state() == WorldPortalDefinition.State.DRAFT) {
            issues.add(new Issue("PORTAL_DRAFT", "info", "Brouillon : jamais activé, entrer dans sa zone ne "
                    + "téléporte personne."));
        } else if (!p.enabled()) {
            issues.add(new Issue("PORTAL_DISABLED", "info", "Portail désactivé (enabled: false) : "
                    + "entrer dans sa zone ne téléporte personne."));
        }
        String site = p.details().site();
        Optional<BuildingSite> linked = site.isEmpty() ? Optional.empty() : in.sites().apply(site);
        if (!site.isEmpty() && linked.isEmpty()) {
            issues.add(new Issue("PORTAL_SITE_MISSING", "warning", "L'emplacement « " + site + " » cité par ce "
                    + "portail n'existe plus : sa zone reste celle enregistrée, mais il ne peut pas être activé."));
        }
        PortalBuildingLink.Status link = PortalBuildingLink.status(p,
                site.isEmpty() ? Optional.empty() : in.buildings().apply(site));
        if (link != PortalBuildingLink.Status.NONE && link != PortalBuildingLink.Status.OK) {
            issues.add(new Issue("PORTAL_BUILDING_LINK_BROKEN", p.enabled() ? "error" : "warning",
                    PortalBuildingLink.describe(link, p) + " Tant que le lien est rompu, le portail ne peut pas être "
                            + "activé."));
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
        WorldPortalDefinition.Details d = p.details();
        List<String> returnedBy = all.stream().filter(o -> o.returnOf().equals(p.id()))
                .map(WorldPortalDefinition::id).toList();
        if (!p.returnOf().isEmpty() && all.stream().noneMatch(o -> o.id().equals(p.returnOf()))) {
            issues.add(new Issue("PORTAL_RETURN_TARGET_MISSING", "warning", "Retour de « " + p.returnOf()
                    + " », qui n'existe plus."));
        }
        Admin admin = new Admin(p.state().name(), d.name(), site, linked.map(BuildingSite::name).orElse(""),
                d.width(), d.height(), d.depth(), WorldPortalAdminService.essential(p, in.roles()),
                WorldPortalAdminService.fingerprint(p), sameId.isEmpty(), p.fixedPoint(), p.returnOf(), returnedBy,
                p.access(), p.messages(), WildEntryWarningService.towardsWild(p, in.roles().wild())
                        ? WildEntryWarningService.WILD_DEFAULTS : WildEntryWarningService.GENERIC_DEFAULTS,
                WildEntryWarningService.appliesTo(p, in.roles().wild()), construction(p, in));
        WorldPortalDefinition.FixedPoint fp = p.fixedPoint();
        String point = fp == null ? "" : String.format(Locale.ROOT, "%s %.1f, %.1f, %.1f (regard %.0f°)",
                p.destinationWorld(), fp.x(), fp.y(), fp.z(), fp.yaw());
        return new Entry(p.id(), d.name().isEmpty() ? nameFor(p.destinationWorld(), in.roles()) : d.name(),
                Kind.WORLD_PORTAL, p.enabled(), file,
                p.world(), sourceLoaded, role(p.world(), in.roles()),
                p.minX(), p.minY(), p.minZ(), p.maxX(), p.maxY(), p.maxZ(),
                p.destinationWorld(), destLoaded, role(p.destinationWorld(), in.roles()), "", point,
                p.destinationStrategy().name(), arrival(p, in),
                worldPortalTrigger(!guards.isEmpty(), !p.access().isEmpty()),
                List.copyOf(guards), accessConditions(p.access()), List.copyOf(issues), admin);
    }

    /** Issue #568 : construction posée sur l'emplacement et état du lien, sans rien modifier. */
    private static Construction construction(WorldPortalDefinition p, Inputs in) {
        String site = p.details().site();
        Optional<PortalBuildingLink.SiteBuilding> onSite = site.isEmpty() ? Optional.empty()
                : in.buildings().apply(site);
        PortalBuildingLink.Status status = PortalBuildingLink.status(p, onSite);
        if (onSite.isEmpty()) {
            return new Construction(p.details().building(), status.name(), PortalBuildingLink.describe(status, p),
                    "", "", 0, false, "", site.isEmpty() ? "Aucun emplacement : zone saisie en coordonnées."
                            : "Aucune construction posée sur l'emplacement « " + site + " ».");
        }
        PortalBuildingLink.SiteBuilding sb = onSite.get();
        PortalBuildingLink.Derived derived = PortalBuildingLink.derive(sb);
        WorldPortalDefinition.Zone z = derived.zone();
        return new Construction(p.details().building(), status.name(), PortalBuildingLink.describe(status, p),
                sb.placement().buildingId(), sb.definition() == null ? "" : sb.definition().name(),
                sb.placement().rotationDegrees(), derived.ok(),
                z == null ? "" : z.minX() + "," + z.minY() + "," + z.minZ() + " → " + z.maxX() + "," + z.maxY() + ","
                        + z.maxZ(), derived.ok() ? "" : derived.message());
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
            case FIXED_POINT -> List.of(
                    "Point fixe : " + WorldPortalAdminService.pointText(p.fixedPoint()) + ".",
                    "Contrôlé à chaque passage : s'il n'est pas sûr, position sûre la plus proche à "
                            + ArrivalSafety.HORIZONTAL_RADIUS + " blocs au plus (" + ArrivalSafety.VERTICAL_RANGE
                            + " en hauteur), sinon passage refusé — jamais de repli lointain.",
                    "Chunks chargés et téléportation asynchrones.");
        };
    }

    /** Issue #567 : conditions d'accès d'un portail entre mondes, dans l'ordre où le serveur les vérifie. */
    private static List<Condition> accessConditions(PortalAccess a) {
        List<Condition> out = new ArrayList<>();
        if (a.requiredLevel() != null) {
            out.add(new Condition("Niveau RPG minimum", String.valueOf(a.requiredLevel())));
        }
        for (PortalAccess.Rule r : a.rules()) {
            out.add(new Condition("Condition", PortalAccess.describe(r.condition())));
        }
        if (a.cooldownSeconds() > 0) {
            out.add(new Condition("Attente entre deux passages", a.cooldownSeconds() + " s"));
        }
        return List.copyOf(out);
    }

    private static List<String> worldPortalTrigger(boolean guarded, boolean conditioned) {
        return List.of(
                "Téléportation immédiate en entrant dans la zone (de l'extérieur vers l'intérieur), sans attente ni coût.",
                "Répit de 2 secondes après une arrivée ou une connexion : pas de renvoi en boucle.",
                conditioned
                        ? "Conditions d'accès vérifiées par le serveur à chaque passage, après les règles Claims / "
                                + "Wild : un joueur refusé reste sur place et reçoit le message de la condition."
                        : guarded
                        ? "Aucune condition de niveau, de quête ni de permission n'est configurée sur le portail "
                                + "lui-même ; les règles du serveur (bloc « Règles appliquées par le serveur ») "
                                + "s'appliquent quand même."
                        : "Aucune condition de niveau, de quête ni de permission n'est configurée sur le portail, "
                                + "et aucune règle du serveur ne s'applique à sa destination.");
    }

    /** Avertissement avant le départ (#161), avec les textes effectifs du portail (#569). */
    private static Guard wildGuard(WorldPortalDefinition p, String wildWorld) {
        WildEntryWarningService.Texts t = WildEntryWarningService.texts(p, wildWorld);
        boolean wild = WildEntryWarningService.towardsWild(p, wildWorld);
        List<String> rules = new ArrayList<>();
        rules.add("Avant chaque départ, une fenêtre Minecraft demande de confirmer. Fermer la fenêtre = annuler.");
        rules.add(t.allowHide()
                ? "« Ne plus afficher » est mémorisé par joueur et par portail (variable "
                        + WildEntryWarningService.hiddenKey(p.id())
                        + (wild ? ", ou l'ancienne " + WildEntryWarningService.WARNING_HIDDEN_KEY : "")
                        + ") ; le message de préparation reste affiché."
                : "« Ne plus afficher » n'est pas proposé : l'avertissement s'affiche à chaque départ.");
        rules.add("Aucune inspection d'inventaire : le texte énonce le risque de la zone, il ne juge pas l'équipement.");
        rules.add("Une seule demande à la fois par joueur ; après « Annuler », il faut ressortir puis rentrer.");
        List<Text> texts = new ArrayList<>();
        texts.add(new Text("Message", plain(t.text())));
        if (!t.hint().isEmpty()) {
            texts.add(new Text("Indice", plain(t.hint())));
        }
        texts.add(new Text("Bouton", plain(t.enter())));
        if (t.allowHide()) {
            texts.add(new Text("Bouton", plain(t.enterHide())));
        }
        texts.add(new Text("Bouton", plain(t.cancel())));
        texts.add(new Text("Après « Annuler »", plain(t.cancelText())));
        texts.add(new Text("Après confirmation", plain(t.preparing())));
        texts.add(new Text("Titre de la fenêtre", plain(t.title())));
        return new Guard("WILD_WARNING", wild ? "Avertissement avant d'entrer dans le Wild"
                : "Avertissement avant le départ", List.copyOf(rules), List.copyOf(texts));
    }

    private static String plain(String miniMessage) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(miniMessage));
    }

    private static Guard claimsGuard(GuideNpc guide) {
        return new Guard("CLAIMS_ACCESS", "Accès au monde des claims", List.of(
                "Entrée réservée au joueur qui a débloqué son premier terrain (variable CLAIM_TIER_1) "
                        + "ou qui possède déjà un claim principal ; sinon il reste sur place et un message "
                        + "l'envoie vers Jo (acte de propriété) et vers " + guideLabel(guide) + ".",
                "Moyen de retour garanti : une Pierre de retour est remise avant le départ ; entrée refusée si "
                        + "l'inventaire est plein ou si le retour au Hub ne se résout pas.",
                "Être OP ne suffit pas : seul le mode bypass administrateur (/rpgadmin bypass on + "
                        + RpgPermissions.BYPASS_CLAIM_WORLD + " ou rpgquest.admin.world) passe outre."),
                List.of());
    }

    /** Le guide tel que le message en jeu le nomme ; repli lisible quand son nom ne se résout pas. */
    private static String guideLabel(GuideNpc guide) {
        if (guide == null || guide.npcId() == null || guide.npcId().isBlank()) {
            return "le guide du village (aucun PNJ guide configuré : hub.guide-npc-id)";
        }
        if (guide.name() == null || guide.name().isBlank()) {
            return "le guide du village (PNJ « " + guide.npcId() + " », nom affiché introuvable)";
        }
        return guide.name() + " (PNJ guide « " + guide.npcId() + " »)";
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
                        + "déplacement, un dégât ou un départ."), List.of(), conditions(p), List.copyOf(issues),
                Admin.LEGACY);
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
