package com.lodygames.rpgquest.panel.agent;

import com.lodygames.rpgquest.panel.authz.Permission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Liste blanche <strong>côté Control Panel</strong> des actions agent (miroir de l'énumération
 * {@code AgentActionType} du plugin) : permission requise, nature (lecture / mutation), besoin d'un
 * joueur cible, et validation des paramètres <strong>avant</strong> toute création d'action.
 *
 * <p>Défense en profondeur : les mêmes bornes sont re-vérifiées par l'agent puis par le service
 * métier. Aucune action « console libre » n'existe ici — un type inconnu est refusé.</p>
 */
public final class AgentActionCatalog {

    private static final Pattern PLAYER_REF = Pattern.compile("[A-Za-z0-9_\\-]{1,40}|[0-9a-fA-F\\-]{36}");
    private static final Pattern VARIABLE_KEY = Pattern.compile("[A-Za-z0-9_.:\\-]{1,128}");
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-zA-Z0-9_.:\\-/]{1,128}");
    private static final Pattern STORY_ID = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final Pattern NPC_ID = Pattern.compile("[a-z0-9._-]{1,64}");
    /** Pseudo Minecraft : même forme que côté plugin (revalidée là-bas). */
    private static final Pattern MINECRAFT_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    /** Clé courte (auto-préfixée {@code rpgquest:} à la création) OU identifiant namespacé complet
     * ({@code NamespacedKey#asString()}, ex. {@code rpgquest:creeper_pig}) tel que renvoyé par
     * {@code mob.list} et réinjecté par les formulaires d'édition/bascule/spawn de test -- le « : »
     * doit être accepté, sinon tout profil existant est rejeté « identifiant invalide ». */
    private static final Pattern MOB_ID = Pattern.compile("[a-z0-9._-]{1,64}(?::[a-z0-9._/-]{1,64})?");
    private static final java.util.Set<String> MOB_CATEGORIES = java.util.Set.of("SPECIAL", "BOSS");
    private static final Pattern DIALOGUE_REF = Pattern.compile("[a-z0-9._-]{1,64}(?::[a-z0-9._/-]{1,128})?");
    private static final Pattern DIALOGUE_NODE_ID = Pattern.compile("[a-z0-9_][a-z0-9_-]{0,63}");
    private static final int MAX_DIALOGUE_TEXT = 512;
    private static final int MAX_DIALOGUE_CHOICE_INDEX = 199;
    private static final Pattern NPC_ROLE = Pattern.compile("[a-z0-9_-]{1,32}");
    /** Jeton anti-doublon d'un formulaire de création (issue #325). */
    private static final Pattern REQUEST_TOKEN = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    /** Issue #213 : exactement la forme que le serveur attribue à un emplacement. */
    private static final Pattern BUILD_SITE_ID = Pattern.compile("buildsite_[0-9]{1,12}");
    /** Issue #381 : exactement la forme que le serveur attribue à un emplacement de PNJ. */
    private static final Pattern NPC_SPOT_ID = Pattern.compile("npcspot_[0-9]{1,12}");
    /** Miroir de {@code NpcSpot.MAX_NAME_LENGTH} côté plugin, qui revalide. */
    public static final int MAX_NPC_SPOT_NAME = 64;
    /** Issue #47 — les seules familles publiables, miroir de {@code content.publish.PublishKind}. */
    private static final java.util.List<String> PUBLISHABLE_KINDS =
            java.util.List.of("quests", "stories", "dialogues");
    /** Identifiant de ressource : aucun séparateur, donc impossible d'en faire un chemin. */
    private static final Pattern PUBLISH_SLUG = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    /** Empreinte SHA-256 hexadécimale, ou vide (= ressource absente). */
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    /**
     * Forme EXACTE d'un chemin de sauvegarde produit par le serveur.
     *
     * <p>Jamais un chemin libre : le navigateur ne peut désigner qu'une sauvegarde dont la forme
     * correspond à celle que le serveur fabrique, et le serveur revérifie de toute façon qu'elle est
     * confinée à son dossier.</p>
     */
    private static final Pattern BACKUP_PATH = Pattern.compile(
            "content-backups/[0-9a-z]{1,40}/(quests|stories|dialogues)/[a-z0-9][a-z0-9_-]{0,63}\\.yml");
    /**
     * Issue #156 — forme EXACTE d'un identifiant de waypoint fabriqué par le serveur
     * ({@code wp_<monde>_<biome>_<regionX>_<regionZ>}, voir {@code BiomeInstanceKey#waypointId}).
     * Le tiret est accepté parce qu'une coordonnée de région est signée.
     */
    private static final Pattern WAYPOINT_ID = Pattern.compile("wp_[A-Za-z0-9_\\-]{1,120}");
    /** Issue #134 — miroir de {@code WaypointNameCatalog#validateDisplayName} côté plugin. */
    private static final Pattern WAYPOINT_DISPLAY_NAME = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} '’.\\-]*");
    /** Occurrence de PNJ récurrent (issue #13) : {@code enc_<monde>_<biome>_<rx>_<rz>}. */
    private static final Pattern ENCOUNTER_SITE_ID = Pattern.compile("enc_[A-Za-z0-9_\\-]{1,160}");
    /** Clé de biome d'un modèle de PNJ récurrent : {@code forest} ou {@code minecraft:forest}. */
    private static final Pattern ENCOUNTER_BIOME = Pattern.compile("([a-z0-9._-]{1,64}:)?[a-z0-9._/-]{1,128}");
    private static final Pattern ENCOUNTER_WORLD = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    /** Identifiant de bâtiment de bibliothèque — même forme que le reste du contenu du projet. */
    private static final Pattern BUILDING_ID = Pattern.compile("[a-z0-9][a-z0-9_]{0,63}");
    /** Les quatre orientations cardinales, miroir de {@code Facing} côté plugin. */
    private static final java.util.Set<String> BUILD_SITE_FACINGS =
            java.util.Set.of("NORTH", "EAST", "SOUTH", "WEST");
    private static final int MAX_BUILD_SITE_NAME = 64;
    /** Issue #178 — miroir de {@code city.CityPerimeter} côté plugin, qui revalide. */
    public static final int CITY_MIN_DISTANCE = 5;
    public static final int CITY_MAX_DISTANCE = 500;
    private static final java.util.regex.Pattern CITY_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final java.util.regex.Pattern CITY_PLAYER = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final int MAX_BUILD_SITE_DESCRIPTION = 500;
    /**
     * Issue #94 — borne du brief architectural. Doit rester <strong>identique</strong> à
     * {@code BuildingSite.MAX_AI_BRIEF_LENGTH} côté plugin : le panel, l'agent et le stockage
     * refusent au même seuil, sinon l'un des trois tronquerait ou rejetterait ce que les autres
     * acceptent.
     */
    private static final int MAX_BUILD_SITE_BRIEF = com.lodygames.rpgquest.panel.building.BuildingProject.MAX_BRIEF;
    /** Issue #94 — borne du plan transmis, identique à celle de l'exécuteur côté plugin. */
    private static final int MAX_BUILD_PLAN = 64_000;
    /** Familles de contenu exportables (issue #108) — miroir de {@code ContentFamily} côté plugin. */
    private static final java.util.Set<String> CONTENT_FAMILIES =
            java.util.Set.of("all", "quests", "stories", "dialogues", "npcs");
    private static final int MAX_EXPORT_IDS = 500;
    private static final Pattern WORLD_NAME = Pattern.compile("[A-Za-z0-9_./-]{1,64}");
    /** Issue #165 : lien MineSkin strict — même motif que la validation côté plugin. */
    private static final Pattern MINESKIN_URL = Pattern.compile("https://minesk\\.in/[A-Za-z0-9]{8,64}");

    /** Adresse du catalogue public de MineSkin, proposée à côté du champ « Lien du skin » (#325). */
    public static final String MINESKIN_CATALOG_URL = "https://mineskin.org/skins";

    /**
     * Issue #325 — ce qui ne va pas dans un lien de skin, en français simple, ou {@code null}. Le
     * contrôle « commence par https:// » vient d'abord parce que c'est l'erreur la plus courante ;
     * il ne suffit pas : seul le format {@code https://minesk.in/<identifiant>} est accepté, ici
     * comme dans le plugin.
     */
    public static String mineSkinProblem(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) {
            return "Collez le lien du skin.";
        }
        if (!u.startsWith("https://")) {
            return "Le lien doit commencer par https://";
        }
        if (!MINESKIN_URL.matcher(u).matches()) {
            return "Ce lien n'est pas un lien MineSkin. Il doit ressembler à https://minesk.in/…";
        }
        return null;
    }
    /** Bornes de sécurité de position miroir de {@code CitizensSpawnPlanner} côté plugin (#81 phase 2). */
    private static final double HORIZONTAL_LIMIT = 29_999_984.0;
    private static final double Y_MIN = -2048.0;
    private static final double Y_MAX = 2048.0;
    /**
     * Issue #165 — bornes propres au panel, Citizens n'en impose aucune. Au-delà, « regarder les
     * joueurs » compare la distance de chaque joueur à chaque tick pour un effet invisible en jeu,
     * et une zone de promenade plus large n'est plus une zone.
     */
    private static final double LOOKCLOSE_MAX_RANGE = 64.0;
    private static final int WANDER_MAX_X_RANGE = 64;
    private static final int WANDER_MAX_Y_RANGE = 32;

    /**
     * Plafond d'une opération monétaire administrative unique (issue #140). Garde-fou de
     * <strong>saisie</strong>, pas une règle d'équilibrage : il empêche qu'une faute de frappe crée
     * une fortune en un clic, et il est ajustable sans toucher au gameplay.
     */
    public static final long MAX_ECONOMY_AMOUNT = 1_000_000L;

    /**
     * Issue #131 — familles rechargeables, miroir de {@code content.reload.ReloadFamily}. L'ordre
     * est celui des dépendances : objets et PNJ avant les quêtes, quêtes avant stories et dialogues.
     */
    public static final List<String> RELOAD_FAMILIES =
            List.of("items", "npcs", "quests", "stories", "dialogues", "mobs");

    /** Libellés humains des familles rechargeables (même ordre que {@link #RELOAD_FAMILIES}). */
    public static final Map<String, String> RELOAD_FAMILY_LABELS = Map.of(
            "items", "Objets",
            "npcs", "PNJ",
            "quests", "Quêtes",
            "stories", "Stories",
            "dialogues", "Dialogues",
            "mobs", "Mobs spéciaux & boss");

    /** Issue #95 : canaux d'annonce réellement supportés — miroir de {@code ServerOpsService.CHANNELS}. */
    public static final List<String> ANNOUNCE_CHANNELS = List.of("chat", "actionbar", "title");
    /** Même borne que le serveur : au-delà, l'affichage en jeu n'est plus lisible. */
    public static final int MAX_ANNOUNCE_CHARS = 200;

    /**
     * Modèles d'annonce proposés en un clic (issue #95). Ce ne sont que des <strong>textes</strong>
     * pré-remplis dans le champ : aucun ne déclenche d'opération, et l'administrateur peut les
     * modifier avant d'envoyer. Un modèle « Redémarrage dans 5 minutes » n'a jamais redémarré quoi
     * que ce soit — c'est le bouton de redémarrage qui le fait, et il annonce de lui-même.
     */
    public static final List<String> ANNOUNCE_TEMPLATES = List.of(
            "Redémarrage du serveur dans 10 minutes.",
            "Redémarrage du serveur dans 5 minutes.",
            "Redémarrage du serveur dans 1 minute.",
            "Maintenance imminente : déconnexion conseillée.",
            "Merci de votre patience, le serveur revient dans un instant.");

    /** Suggestions de clés de variables pour les listes déroulantes (jamais imposées). */
    public static final List<String> KNOWN_VARIABLE_KEYS = List.of(
            "CLAIM_TIER_1", "tutorial_started", "crystal_hunt_started", "RUNE_RAPPEL_GRANTED",
            // Issue #218 : palier du kit de départ (absent = palier 1). Lisible avec l'outil
            // « Lire une variable » ; se débloque par /rpgadmin kit grant-tier, jamais en l'écrivant
            // à la main — l'écriture directe contournerait le refus de saut de palier.
            "STARTER_KIT_TIER",
            // Issue #161 : avertissement d'entrée dans le Wild masqué par le joueur ("true").
            "WILD_ENTRY_WARNING_HIDDEN",
            // Issue #26 : droit au kit de départ pour la vie en cours ("false" = déjà reçu).
            "STARTER_TOOL_KIT_AVAILABLE");

    /**
     * Couleurs MiniMessage proposées par la palette du formulaire de dialogue (issue #118) : l'ordre
     * est l'ordre d'affichage. Le panel enrobe le texte avec {@code <couleur>…</couleur>} — jamais de
     * couleur hors de cette liste, jamais de balise si l'utilisateur a déjà écrit du MiniMessage.
     */
    public static final List<String> PALETTE_COLORS = List.of(
            "white", "gray", "yellow", "gold", "green", "dark_green", "aqua", "dark_aqua",
            "blue", "dark_blue", "red", "dark_red", "light_purple", "dark_purple");

    /**
     * @param type         nom du fil (ex. {@code quest.start})
     * @param permission   permission requise
     * @param mutation     {@code true} = effet de bord
     * @param needsPlayer  {@code true} = paramètre {@code player} obligatoire
     * @param sensitive    {@code true} = action réellement sensible / difficilement réversible
     *                     (bannissement, reset, spawn d'entité, suppression…) → confirmation
     *                     explicite obligatoire. {@code false} = édition de contenu normale et
     *                     réversible → aucune case à cocher cachée (issues #111 / #113 / #118).
     * @param label        libellé humain court
     * @param refreshTypes relevés {@code *.list} dont l'instantané du Control Panel devient périmé
     *                     quand cette action réussit — ré-enfilés automatiquement (issues #112 /
     *                     #115 / #116 / #119 / #120). Jamais de refresh ad hoc par écran.
     */
    /**
     * @param permission   permission principale, telle qu'historiquement contrôlée
     * @param alsoRequires permissions <strong>supplémentaires</strong> exigées en plus de la
     *                     principale. Sert aux actions qui en orchestrent plusieurs en une seule
     *                     opération atomique : l'opérateur doit détenir <em>toutes</em> les
     *                     permissions des effets produits, sinon l'action lui accorderait un droit
     *                     implicite (par exemple créer une définition en ne détenant que le droit
     *                     de faire apparaître un PNJ).
     */
    public record Spec(String type, Permission permission, boolean mutation, boolean needsPlayer,
                       boolean sensitive, String label, List<String> refreshTypes,
                       List<Permission> alsoRequires) {
        public Spec {
            refreshTypes = refreshTypes == null ? List.of() : List.copyOf(refreshTypes);
            alsoRequires = alsoRequires == null ? List.of() : List.copyOf(alsoRequires);
        }

        public Spec(String type, Permission permission, boolean mutation, boolean needsPlayer,
                    boolean sensitive, String label, List<String> refreshTypes) {
            this(type, permission, mutation, needsPlayer, sensitive, label, refreshTypes, List.of());
        }

        /** Toutes les permissions nécessaires : la principale puis les supplémentaires, sans doublon. */
        public List<Permission> requiredPermissions() {
            java.util.LinkedHashSet<Permission> all = new java.util.LinkedHashSet<>();
            all.add(permission);
            all.addAll(alsoRequires);
            return List.copyOf(all);
        }
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    /** Lecture ou mutation « classique » : une mutation non annotée reste sensible (comportement historique). */
    /** Identifiant d'emplacement normalisé, ou {@code null} si la saisie n'en est pas un. */
    /** La famille, résolue depuis la liste blanche. {@code null} si inconnue. */
    private static String publishKind(Map<String, String> form) {
        String raw = trim(form.get("kind")).toLowerCase(java.util.Locale.ROOT);
        return PUBLISHABLE_KINDS.contains(raw) ? raw : null;
    }

    private static String publishSlug(Map<String, String> form) {
        String fromId = trim(form.get("id"));
        String raw = (fromId.isEmpty() ? trim(form.get("slug")) : fromId)
                .toLowerCase(java.util.Locale.ROOT);
        return PUBLISH_SLUG.matcher(raw).matches() ? raw : null;
    }

    /** Une empreinte, ou la chaîne vide. {@code null} si la valeur est mal formée. */
    private static String sha(String raw) {
        if (raw == null) {
            return null;
        }
        String clean = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (clean.isEmpty()) {
            return "";
        }
        return SHA256.matcher(clean).matches() ? clean : null;
    }

    private static String npcSpotId(Map<String, String> form) {
        String raw = trim(form.get("spot_id"));
        if (raw.isEmpty()) {
            raw = trim(form.get("id"));
        }
        String id = raw.toLowerCase(java.util.Locale.ROOT);
        return NPC_SPOT_ID.matcher(id).matches() ? id : null;
    }

    private static String buildSiteId(Map<String, String> form) {
        String raw = trim(form.get("id"));
        if (raw.isEmpty()) {
            raw = trim(form.get("site_id"));
        }
        String id = raw.toLowerCase(java.util.Locale.ROOT);
        return BUILD_SITE_ID.matcher(id).matches() ? id : null;
    }

    private static void add(String type, Permission p, boolean mutation, boolean needsPlayer, String label) {
        SPECS.put(type, new Spec(type, p, mutation, needsPlayer, mutation, label, List.of()));
    }

    /**
     * Édition de contenu réversible (création / modification d'une définition, d'un nœud, d'un
     * choix, d'une liaison logique) : pas de confirmation cachée, mais invalidation des catalogues
     * cités dès le succès.
     */
    private static void addContentWrite(String type, Permission p, String label, String... refresh) {
        SPECS.put(type, new Spec(type, p, true, false, false, label, List.of(refresh)));
    }

    /**
     * Écriture de contenu qui touche <strong>plusieurs couches</strong> et exige donc toutes les
     * permissions correspondantes (issue #197).
     *
     * <p>Distincte de {@code addOrchestratedWrite}, qui marque en plus l'action comme sensible :
     * renommer un personnage n'a rien d'irréversible et ne fait apparaître aucune entité. Ce qui
     * est en jeu ici, c'est seulement de ne pas accorder par regroupement un droit que
     * l'administrateur ne possède pas — qui peut renommer en jeu ne peut pas pour autant réécrire
     * une définition.</p>
     */
    private static void addContentWrite(String type, List<Permission> all, String label, String... refresh) {
        SPECS.put(type, new Spec(type, all.get(0), true, false, false, label, List.of(refresh),
                all.subList(1, all.size())));
    }

    /**
     * Mutation sensible qui <strong>orchestre plusieurs effets</strong> en une seule opération
     * atomique, et exige donc l'ensemble des permissions correspondantes — jamais un droit
     * implicite accordé par le regroupement.
     */
    private static void addOrchestratedWrite(String type, Permission primary, List<Permission> also,
                                             String label, String... refresh) {
        SPECS.put(type, new Spec(type, primary, true, false, true, label, List.of(refresh), also));
    }

    /** Mutation sensible qui invalide malgré tout des catalogues (spawn d'entité, bannissement…). */
    private static void addSensitiveWrite(String type, Permission p, boolean needsPlayer, String label,
                                          String... refresh) {
        SPECS.put(type, new Spec(type, p, true, needsPlayer, true, label, List.of(refresh)));
    }

    static {
        // Lectures
        add("player.list", Permission.PLAYERS_READ, false, false, "Rafraîchir les joueurs connectés");
        add("player.catalog", Permission.PLAYERS_READ, false, false, "Rafraîchir l'annuaire des joueurs");
        add("player.variable.get", Permission.ACTION_VARIABLE_GET, false, true, "Lire une variable joueur");
        add("player.resetnew.preview", Permission.PLAYERS_READ, false, true,
                "Aperçu du reset de progression RPGQuest");
        // Issue #235 : aperçu de la portée COMPLÈTE. Lecture seule comme son homologue — c'est
        // justement l'écran qui doit permettre de constater ce qui serait vidé avant de confirmer.
        add("player.resetfull.preview", Permission.PLAYERS_READ, false, true,
                "Aperçu du reset « nouveau joueur complet »");
        add("quest.list", Permission.CONTENT_READ, false, false, "Rafraîchir le catalogue de quêtes");
        add("quest.player.status", Permission.PLAYERS_READ, false, true, "État des quêtes d'un joueur");
        add("story.list", Permission.CONTENT_READ, false, false, "Rafraîchir le catalogue de stories");
        add("story.player.status", Permission.PLAYERS_READ, false, true, "État des stories d'un joueur");
        add("item.list", Permission.CONTENT_READ, false, false, "Rafraîchir la liste des objets");
        add("npc.list", Permission.NPC_READ, false, false, "Rafraîchir le catalogue des PNJ");
        // Issue #213 — emplacements de construction. La CRÉATION n'a volontairement pas d'action :
        // un emplacement est défini par une position désignée en jeu, pas saisie dans un écran.
        add("building.site.list", Permission.BUILDING_READ, false, false,
                "Rafraîchir les emplacements de construction");
        // Issue #178 — villes. La CRÉATION n'a pas d'action : le centre se désigne en jeu avec
        // l'outil de ville. Toutes les mutations invalident le relevé, relu automatiquement.
        add("city.list", Permission.TRAVEL_READ, false, false, "Rafraîchir les villes");
        addContentWrite("city.rename", Permission.CITY_WRITE, "Renommer une ville", "city.list");
        addContentWrite("city.resize", Permission.CITY_WRITE, "Régler la taille d'une ville", "city.list");
        addContentWrite("city.active", Permission.CITY_WRITE, "Activer / désactiver une ville", "city.list");
        addContentWrite("city.move.arm", Permission.CITY_WRITE,
                "Préparer le déplacement du centre d'une ville", "city.list");
        addContentWrite("city.perimeter.show", Permission.CITY_WRITE,
                "Montrer le périmètre d'une ville en jeu", "city.list");
        addSensitiveWrite("city.delete", Permission.CITY_DELETE, false,
                "Supprimer une ville et ses découvertes", "city.list");
        // Issue #322 — PNJ central (par identité stable) et arrivée relevée sur un joueur connecté.
        addContentWrite("city.npc.link", Permission.CITY_WRITE,
                "Associer / retirer le PNJ central d'une ville", "city.list");
        addContentWrite("city.arrival.set", Permission.CITY_WRITE,
                "Relever le point d'arrivée d'une ville sur un joueur", "city.list");
        addContentWrite("city.arrival.clear", Permission.CITY_WRITE,
                "Revenir à l'arrivée au centre d'une ville", "city.list");
        add("travel.catalog", Permission.TRAVEL_READ, false, false,
                "Rafraîchir le catalogue waypoints/bornes (issue #152)");
        // Issue #156 — rattrapage CIBLÉ d'une instance du Hub restée sans borne. Mutation sensible :
        // elle pose des blocs réels. Elle ne touche QUE l'instance nommée — aucun balayage, aucun
        // rattrapage global, aucune densité ni distance modifiée — et le catalogue est ré-enfilé
        // pour que la borne apparaisse sans « Rafraîchir + F5 ».
        addSensitiveWrite("travel.beacon.pair", Permission.TRAVEL_PAIR_WRITE, false,
                "Apparier une borne à une instance du Hub", "travel.catalog");
        // Issue #134 — renommer UN waypoint : réversible (on peut renommer à nouveau), seul le texte
        // change ; le catalogue est ré-enfilé pour que le nouveau nom s'affiche sans F5.
        addContentWrite("waypoint.rename", Permission.WAYPOINT_WRITE, "Renommer un waypoint", "travel.catalog");
        add("npc.citizens.list", Permission.NPC_READ, false, false, "Rafraîchir les PNJ Citizens");
        // Issue #13 — PNJ récurrents. Retenter / désactiver / retirer créent ou détruisent un PNJ
        // Citizens réel : sensibles, avec la permission de spawn. Relire les modèles et effacer
        // les délais ne touchent à aucun PNJ déjà en place.
        add("encounter.list", Permission.NPC_READ, false, false, "Rafraîchir les PNJ récurrents");
        addSensitiveWrite("encounter.retry", Permission.NPC_SPAWN_WRITE, false,
                "Retenter l'apparition d'un PNJ récurrent", "encounter.list", "npc.citizens.list");
        addSensitiveWrite("encounter.disable", Permission.NPC_SPAWN_WRITE, false,
                "Désactiver un PNJ récurrent", "encounter.list", "npc.citizens.list");
        addSensitiveWrite("encounter.remove", Permission.NPC_SPAWN_WRITE, false,
                "Retirer un PNJ récurrent", "encounter.list", "npc.citizens.list");
        addContentWrite("encounter.templates.reload", Permission.NPC_WRITE,
                "Relire les modèles de PNJ récurrents", "encounter.list");
        addContentWrite("encounter.backoffs.clear", Permission.NPC_WRITE,
                "Effacer les délais d'attente des PNJ récurrents", "encounter.list");
        // Étape 3 : édition des modèles. Créer, modifier, (dés)activer ne touchent à aucun PNJ en
        // place — écriture de contenu. Supprimer efface un fichier (sauvegardé) : NPC_DELETE, sensible.
        addContentWrite("encounter.template.create", Permission.NPC_WRITE,
                "Créer un modèle de PNJ récurrent", "encounter.list");
        addContentWrite("encounter.template.update", Permission.NPC_WRITE,
                "Modifier un modèle de PNJ récurrent", "encounter.list");
        addContentWrite("encounter.template.toggle", Permission.NPC_WRITE,
                "Activer ou désactiver un modèle de PNJ récurrent", "encounter.list");
        addSensitiveWrite("encounter.template.delete", Permission.NPC_DELETE, false,
                "Supprimer un modèle de PNJ récurrent", "encounter.list");
        // Issue #369 — catalogues de quêtes secondaires. Ils ne référencent que des quêtes
        // existantes : écriture de contenu de quêtes. Supprimer efface un fichier (sauvegardé) et
        // peut dissocier des modèles : sensible, avec confirmation.
        add("quest.catalog.list", Permission.NPC_READ, false, false, "Rafraîchir les catalogues de quêtes");
        addContentWrite("quest.catalog.create", Permission.QUEST_CONTENT_WRITE,
                "Créer un catalogue de quêtes secondaires", "quest.catalog.list");
        addContentWrite("quest.catalog.update", Permission.QUEST_CONTENT_WRITE,
                "Modifier un catalogue de quêtes secondaires", "quest.catalog.list");
        addSensitiveWrite("quest.catalog.delete", Permission.QUEST_CONTENT_WRITE, false,
                "Supprimer un catalogue de quêtes secondaires", "quest.catalog.list", "encounter.list");
        add("dialogue.list", Permission.DIALOGUE_READ, false, false, "Rafraîchir le catalogue des dialogues");
        add("mob.list", Permission.MOB_READ, false, false,
                "Rafraîchir le catalogue des profils de mob spécial/boss (issue #169)");
        // Issue #172/#196 : catalogues RÉELS de la version installée (entités, particules, sons,
        // biomes, mondes). Sans ce relevé, les champs de l'éditeur restent du texte libre et
        // l'administrateur doit deviner les identifiants vanilla exacts.
        add("mob.catalogs", Permission.MOB_READ, false, false,
                "Rafraîchir les catalogues Minecraft (entités, particules, sons, biomes)");
        // Issue #196 : catalogue COMPLET des matériaux de la version installée, pour les icônes de
        // quête et les récompenses d'objet. Sans ce relevé, le panel retombe sur une courte liste
        // curée — qui ne contenait que deux épées sur les sept réellement disponibles.
        add("item.catalogs", Permission.CONTENT_READ, false, false,
                "Rafraîchir le catalogue des objets Minecraft (icônes et récompenses)");
        // Issue #194 : suppression de la copie SERVEUR d'une quête/story. Mutation sensible —
        // confirmation exigée — et les catalogues impactés sont ré-enfilés, sans quoi le contenu
        // supprimé resterait affiché et donnerait l'impression de « réapparaître ».
        addSensitiveWrite("content.definition.delete", Permission.CONTENT_DELETE, false,
                "Supprimer une définition de quête/story/dialogue sur le serveur",
                "quest.list", "story.list", "dialogue.list");
        // Export versionné du contenu déclaratif (issue #108) — lecture seule, aucun effet de bord,
        // aucun catalogue à réenfiler.
        add("content.export", Permission.CONTENT_EXPORT, false, false, "Exporter le contenu (pack versionné)");
        // Issue #47 — l'état DEV du contenu : fichiers présents, empreintes, identifiants chargés.
        // Lecture seule, et c'est ce relevé qui permet de distinguer « différent » de « conflit »
        // AVANT de proposer de publier quoi que ce soit.
        add("content.dev.state", Permission.CONTENT_READ, false, false,
                "Relever l'état du contenu sur DEV (fichiers et runtime)");
        // Lecture d'UN fichier, pour pouvoir MONTRER la différence et pas seulement la détecter.
        // Le relevé d'état ne transporte que des empreintes, ce qui est suffisant pour comparer mais
        // pas pour expliquer.
        add("content.dev.read", Permission.CONTENT_READ, false, false,
                "Lire un fichier de contenu sur DEV (pour la comparaison)");
        // Publier ÉCRIT un fichier sur le serveur et permute le contenu chargé : mutation sensible,
        // confirmation exigée, permission dédiée distincte de l'écriture de la source. Les relevés
        // impactés sont ré-enfilés pour que l'état se réconcilie sans « Rafraîchir + F5 ».
        addSensitiveWrite("content.publish", Permission.CONTENT_PUBLISH, false,
                "Publier une ressource de contenu sur DEV",
                "content.dev.state", "quest.list", "story.list", "dialogue.list");
        addSensitiveWrite("content.publish.rollback", Permission.CONTENT_ROLLBACK, false,
                "Défaire une publication de contenu sur DEV",
                "content.dev.state", "quest.list", "story.list", "dialogue.list");
        // Écritures de contenu (V2 déclarative des PNJ) — réversibles, jamais de YAML brut. Chaque
        // succès ré-enfile le(s) relevé(s) de catalogue impacté(s) pour que la vue métier se
        // réconcilie sans « Rafraîchir catalogue + F5 ».
        addContentWrite("npc.definition.create", Permission.NPC_WRITE, "Créer une définition PNJ", "npc.list");
        addContentWrite("npc.definition.update", Permission.NPC_WRITE, "Modifier une définition PNJ", "npc.list");
        addContentWrite("quest.giver.set", Permission.QUEST_GIVER_WRITE, "Attribuer une quête à un PNJ",
                "npc.list", "quest.list");
        // Issue #213 — édition de la fiche d'un emplacement : réversible, donc pas de case à cocher.
        addContentWrite("building.site.rename", Permission.BUILDING_WRITE,
                "Renommer un emplacement de construction", "building.site.list");
        addContentWrite("building.site.describe", Permission.BUILDING_WRITE,
                "Décrire un emplacement de construction", "building.site.list");
        // Issue #94 — le brief architectural. Même droit que la note : c'est du texte sur une fiche,
        // et l'écrire ne déclenche aucune génération. Lancer la génération est une action à part.
        addContentWrite("building.site.brief", Permission.BUILDING_WRITE,
                "Enregistrer le brief d'un emplacement", "building.site.list");
        // Issue #94 — génération d'un bâtiment depuis un plan. Elle écrit DEUX fichiers sur le
        // serveur (le .schem et la définition) mais ne touche AUCUN bloc du monde : c'est une
        // écriture de contenu, pas une pose. Elle invalide la bibliothèque, qu'elle enrichit.
        addContentWrite("building.generate", Permission.BUILDING_WRITE,
                "Générer un bâtiment depuis un plan", "building.definition.list");
        // Issue #94, lot F — l'audit d'un plan n'écrit RIEN : ni schematic, ni définition, ni ligne
        // en base. Il est donc déclaré en lecture, et c'est précisément ce qui rend la boucle de
        // correction utilisable : on juge un plan autant de fois qu'il le faut sans laisser derrière
        // soi une succession de bâtiments ratés dans la bibliothèque.
        add("building.plan.audit", Permission.BUILDING_READ, false, false,
                "Auditer un plan de bâtiment sans rien écrire");
        // Lot E — l'aperçu 2D. Lecture également : il n'écrit rien et se relance librement. Les
        // coupes sont calculées par le PLUGIN, jamais ici : un aperçu dessiné par le panel
        // montrerait ce que le panel croit que le plan décrit, et la V1 a prouvé que les deux
        // peuvent diverger de cinq blocs.
        add("building.plan.preview", Permission.BUILDING_READ, false, false,
                "Prévisualiser un plan de bâtiment en coupes 2D");
        addContentWrite("building.site.facing", Permission.BUILDING_WRITE,
                "Corriger l'orientation d'un emplacement", "building.site.list");
        // Suppression du marqueur logique : aucun bloc du monde n'est touché, mais l'identifiant ne
        // sera jamais réattribué — donc mutation sensible, avec confirmation explicite.
        addSensitiveWrite("building.site.delete", Permission.BUILDING_DELETE, false,
                "Supprimer un emplacement de construction", "building.site.list");
        // Lot « placement » de #213 — la bibliothèque est en LECTURE SEULE : une définition de
        // bâtiment est du contenu déclaratif versionné, qui se modifie dans son fichier YAML.
        add("building.definition.list", Permission.BUILDING_READ, false, false,
                "Rafraîchir la bibliothèque de bâtiments");
        // Issue #284 — retirer un bâtiment de la bibliothèque : mutation sensible, confirmation
        // explicite, permission de suppression. Le plugin refuse un bâtiment posé ou un exemple
        // embarqué, et ARCHIVE les fichiers au lieu de les effacer.
        addSensitiveWrite("building.definition.retire", Permission.BUILDING_DELETE, false,
                "Retirer un bâtiment de la bibliothèque", "building.definition.list");
        // L'aperçu n'écrit RIEN : ni bloc, ni ligne en base. Il est donc déclaré en lecture, et
        // c'est ce qui permet de le relancer autant de fois qu'on veut avant de se décider.
        add("building.placement.preview", Permission.BUILDING_READ, false, false,
                "Prévisualiser la pose d'un bâtiment (emprise et rotation)");
        // Poser ÉCRASE DES BLOCS RÉELS : mutation sensible, confirmation explicite exigée, et
        // permission dédiée distincte de l'édition de fiche. Les deux catalogues sont ré-enfilés
        // pour que l'emplacement apparaisse occupé sans « Rafraîchir + F5 ».
        addSensitiveWrite("building.placement.place", Permission.BUILDING_PLACE, false,
                "Poser un bâtiment dans le monde",
                "building.site.list", "building.definition.list");
        // Restaurer réécrit aussi des blocs — et écrase ce qui a pu être ajouté APRÈS la pose.
        // Permission distincte : pouvoir poser n'implique pas pouvoir défaire.
        //
        // Issue #234 : cette action LIBÈRE désormais l'emplacement en rendant le terrain d'ORIGINE,
        // et non l'état d'avant la dernière opération. Pour un emplacement dont la pose est la
        // seule opération — le seul cas qui pouvait exister avant #234 — les deux coïncident.
        addSensitiveWrite("building.placement.rollback", Permission.BUILDING_ROLLBACK, false,
                "Libérer l'emplacement et restaurer le terrain d'origine",
                "building.site.list", "building.definition.list");
        // Issue #234 — l'aperçu d'une transformation n'écrit RIEN : déclaré en lecture, et
        // relançable autant qu'on veut avant de se décider.
        add("building.placement.retarget.preview", Permission.BUILDING_READ, false, false,
                "Prévisualiser une réorientation ou un remplacement");
        // Réorienter et remplacer POSENT un bâtiment : même permission que la pose. Ce qui les
        // sépare de la libération n'est pas leur dangerosité mais leur résultat — après elles il y a
        // toujours un bâtiment, après une libération il n'y en a plus. BUILDING_ROLLBACK gouverne
        // donc le fait de VIDER un emplacement, et lui seul.
        addSensitiveWrite("building.placement.reorient", Permission.BUILDING_PLACE, false,
                "Réorienter le bâtiment posé",
                "building.site.list", "building.definition.list");
        addSensitiveWrite("building.placement.replace", Permission.BUILDING_PLACE, false,
                "Remplacer le bâtiment posé",
                "building.site.list", "building.definition.list");
        add("building.placement.history", Permission.BUILDING_READ, false, false,
                "Journal des opérations d'un emplacement");
        // Issue #246 — capturer une construction posée comme NOUVEAU bâtiment. L'aperçu n'écrit rien
        // (lecture) ; la capture n'écrit que dans la bibliothèque — jamais dans le monde, jamais par-
        // dessus un fichier — d'où BUILDING_WRITE, la permission de la génération. Sensible quand même :
        // une confirmation explicite, et le jeton de l'aperçu.
        add("building.placement.capture.preview", Permission.BUILDING_READ, false, false,
                "Prévisualiser la capture d'une construction posée");
        addSensitiveWrite("building.placement.capture", Permission.BUILDING_WRITE, false,
                "Capturer la construction actuelle comme nouveau bâtiment",
                "building.definition.list", "building.site.list");
        addContentWrite("npc.citizens.link", Permission.NPC_BIND_WRITE, "Lier un PNJ Citizens existant",
                "npc.list", "npc.citizens.list");
        // Issue #226 — les trois suppressions de PNJ. Permission DÉDIÉE (NPC_DELETE) : créer et
        // corriger un PNJ est réversible, détruire ne l'est pas, et un PNJ supprimé par erreur
        // emporte ce qui le référençait. Toutes trois sont des mutations sensibles, donc
        // confirmées, et toutes trois invalident les deux catalogues PNJ.
        // Issue #325 : le relevé des dialogues aussi — sinon « supprimer aussi son dialogue », proposé
        // juste après, retombe sur un relevé qui croit encore le PNJ lié et refuse.
        addSensitiveWrite("npc.definition.delete", Permission.NPC_DELETE, false,
                "Supprimer la définition logique d'un PNJ", "npc.list", "npc.citizens.list", "dialogue.list");
        addSensitiveWrite("npc.citizens.unlink", Permission.NPC_DELETE, false,
                "Délier un PNJ Citizens (sans le supprimer)", "npc.list", "npc.citizens.list");
        // Détruire l'entité Citizens est l'inverse exact de sa création : elle exige donc AUSSI
        // NPC_SPAWN_WRITE. Regrouper ne doit jamais accorder un droit que l'opérateur n'a pas.
        addOrchestratedWrite("npc.citizens.delete", Permission.NPC_DELETE,
                List.of(Permission.NPC_SPAWN_WRITE),
                "Supprimer le PNJ Citizens physique", "npc.list", "npc.citizens.list", "dialogue.list");
        addSensitiveWrite("npc.citizens.create", Permission.NPC_SPAWN_WRITE, false, "Créer le PNJ Citizens",
                "npc.list", "npc.citizens.list");
        // Issue #165 : opérations purement cosmétiques sur un PNJ Citizens existant. Réutilisent
        // la permission de liaison (NPC_BIND_WRITE) : elles ne créent ni ne détruisent rien, et ne
        // touchent jamais l'identité logique RPGQuest ni les liaisons de contenu.
        // Création complète : définition + apparition + liaison + skin, en une seule opération
        // atomique. Elle produit les effets de TROIS permissions, donc elle les exige toutes les
        // trois — regrouper ne doit jamais accorder un droit que l'opérateur n'a pas.
        addOrchestratedWrite("npc.citizens.provision", Permission.NPC_SPAWN_WRITE,
                List.of(Permission.NPC_WRITE, Permission.NPC_BIND_WRITE),
                "Créer un PNJ (définition + apparition + liaison + skin)",
                "npc.list", "npc.citizens.list");
        // Issue #381 — emplacements de PNJ. La réservation se fait EN JEU (/rpgadmin npc spot mark) :
        // aucune action ne crée un emplacement depuis des coordonnées tapées. Placer déplace ou fait
        // apparaître une entité réelle : sensible, avec confirmation. Supprimer retire un repère
        // (jamais un PNJ) : permission de suppression, avec confirmation.
        add("npc.spot.list", Permission.NPC_READ, false, false, "Rafraîchir les emplacements de PNJ");
        addContentWrite("npc.spot.rename", Permission.NPC_SPAWN_WRITE, "Renommer un emplacement de PNJ",
                "npc.spot.list");
        addContentWrite("npc.spot.orient", Permission.NPC_SPAWN_WRITE,
                "Corriger l'orientation d'un emplacement de PNJ", "npc.spot.list", "npc.citizens.list");
        addContentWrite("npc.spot.release", Permission.NPC_SPAWN_WRITE, "Libérer un emplacement de PNJ",
                "npc.spot.list");
        addContentWrite("npc.spot.capture", Permission.NPC_SPAWN_WRITE,
                "Réserver la position actuelle d'un PNJ", "npc.spot.list");
        addSensitiveWrite("npc.spot.place", Permission.NPC_SPAWN_WRITE, false,
                "Placer un PNJ sur un emplacement", "npc.spot.list", "npc.citizens.list", "npc.list");
        addSensitiveWrite("npc.spot.delete", Permission.NPC_DELETE, false,
                "Supprimer un emplacement de PNJ", "npc.spot.list");
        // Déplacer un PNJ existant relève du même droit que le faire apparaître : NPC_SPAWN_WRITE.
        // Aucun droit nouveau, aucune permission élargie.
        addSensitiveWrite("npc.citizens.move", Permission.NPC_SPAWN_WRITE, false,
                "Déplacer le PNJ Citizens", "npc.list", "npc.citizens.list");
        addContentWrite("npc.citizens.rename", Permission.NPC_BIND_WRITE, "Renommer le PNJ en jeu",
                "npc.list", "npc.citizens.list");
        // Issue #197 — le nom du personnage, sur toutes ses couches à la fois. Exige les DEUX
        // droits que ses effets produisent : écrire la définition (NPC_WRITE) et modifier le PNJ
        // Citizens (NPC_BIND_WRITE). Aucun droit nouveau, et surtout aucun regroupement qui
        // permettrait d'obtenir l'un en ne possédant que l'autre.
        addContentWrite("npc.identity.rename",
                List.of(Permission.NPC_WRITE, Permission.NPC_BIND_WRITE),
                "Renommer le PNJ (catalogue et jeu)", "npc.list", "npc.citizens.list");
        addContentWrite("npc.citizens.skin", Permission.NPC_BIND_WRITE, "Appliquer un skin",
                "npc.list", "npc.citizens.list");
        // Issue #165 — comportements Citizens. « Regarder les joueurs » est purement cosmétique :
        // même droit que renommer ou habiller (NPC_BIND_WRITE). La promenade fait bouger le PNJ
        // dans le monde : même droit que le déplacer (NPC_SPAWN_WRITE). Aucun droit nouveau.
        addContentWrite("npc.citizens.lookclose", Permission.NPC_BIND_WRITE,
                "Regarder les joueurs à proximité", "npc.list", "npc.citizens.list");
        addSensitiveWrite("npc.citizens.wander", Permission.NPC_SPAWN_WRITE, false,
                "Promenade du PNJ", "npc.list", "npc.citizens.list");
        addContentWrite("dialogue.definition.create", Permission.DIALOGUE_WRITE, "Créer un dialogue (squelette)",
                "dialogue.list");
        addContentWrite("dialogue.node.create", Permission.DIALOGUE_WRITE, "Ajouter un nœud", "dialogue.list");
        addContentWrite("dialogue.node.update", Permission.DIALOGUE_WRITE, "Modifier un nœud", "dialogue.list");
        addContentWrite("dialogue.choice.add", Permission.DIALOGUE_WRITE, "Ajouter un choix", "dialogue.list");
        addContentWrite("dialogue.choice.update", Permission.DIALOGUE_WRITE, "Modifier un choix", "dialogue.list");
        addSensitiveWrite("dialogue.choice.delete", Permission.DIALOGUE_WRITE, false, "Supprimer un choix",
                "dialogue.list");
        addContentWrite("mob.definition.create", Permission.MOB_WRITE, "Créer un profil de mob spécial/boss", "mob.list");
        addContentWrite("mob.definition.update", Permission.MOB_WRITE, "Modifier un profil de mob spécial/boss", "mob.list");
        addContentWrite("mob.definition.toggle", Permission.MOB_WRITE, "Activer/désactiver un profil", "mob.list");
        addContentWrite("mob.spawn-settings.set", Permission.MOB_WRITE, "Régler le throttle Wild (issue #169)", "mob.list");
        addSensitiveWrite("mob.test.spawn", Permission.MOB_TEST_SPAWN, true,
                "Faire apparaître une instance de test", "mob.list");
        addSensitiveWrite("mob.test.clear", Permission.MOB_TEST_SPAWN, false,
                "Supprimer les instances de test", "mob.list");
        // Mutations
        add("player.item.give", Permission.ACTION_ITEM_GIVE, true, true, "Donner un objet");
        add("player.variable.set", Permission.ACTION_VARIABLE_SET, true, true, "Écrire une variable (debug)");
        add("player.resetnew.confirm", Permission.ACTION_PLAYER_RESET, true, true,
                "Reset progression RPGQuest (inventaire conservé)");
        // Issue #235 : vider l'inventaire d'un joueur est d'une autre nature que réinitialiser sa
        // progression — d'où une permission DÉDIÉE, et non la même case que le reset de progression.
        add("player.resetfull.confirm", Permission.ACTION_PLAYER_RESET_FULL, true, true,
                "Reset nouveau joueur COMPLET (vide l'inventaire)");
        // Issue #210 — administration de joueur. L'élévation OP a sa PROPRE permission, la plus
        // restreinte du panel : réservée à OWNER, pas accordée à ADMIN. Le reste réutilise
        // PLAYER_MODERATE comme le ticket le demande, pour ne pas multiplier les permissions.
        addSensitiveWrite("player.op", Permission.PLAYER_OP_WRITE, true,
                "Accorder le statut OP Minecraft", "player.catalog");
        addSensitiveWrite("player.deop", Permission.PLAYER_OP_WRITE, true,
                "Retirer le statut OP Minecraft", "player.catalog");
        addSensitiveWrite("player.send.hub", Permission.PLAYER_MODERATE, true,
                "Renvoyer un joueur au Hub", "player.list");
        addSensitiveWrite("player.kick", Permission.PLAYER_MODERATE, true,
                "Expulser un joueur", "player.list", "player.catalog");
        addSensitiveWrite("player.whitelist.add", Permission.PLAYER_MODERATE, true,
                "Ajouter à la whitelist", "player.catalog");
        addSensitiveWrite("player.whitelist.remove", Permission.PLAYER_MODERATE, true,
                "Retirer de la whitelist", "player.catalog");
        // Issue #140 — administration de la monnaie. La lecture est un relevé ; le crédit et le
        // débit sont des mutations SENSIBLES, car un crédit crée de la monnaie.
        add("economy.balance", Permission.ECONOMY_READ, false, true,
                "Lire le solde et le journal d'un joueur");
        // Issue #335 — paramètres généraux. Écriture validée ici ET par le plugin, qui relit le fichier.
        add("settings.get", Permission.SETTINGS_READ, false, false, "Lire les paramètres généraux");
        addContentWrite("settings.set", Permission.SETTINGS_WRITE, "Modifier les paramètres généraux", "settings.get");
        addSensitiveWrite("economy.credit", Permission.ECONOMY_WRITE, true,
                "Créditer un joueur", "player.catalog");
        addSensitiveWrite("economy.debit", Permission.ECONOMY_WRITE, true,
                "Débiter un joueur", "player.catalog");
        // Issue #16 (second lot) — récompenses monétaires restées dues. La lecture est un relevé ;
        // la reprise et le règlement manuel touchent à l'argent, donc sensibles et confirmés.
        add("economy.debts", Permission.ECONOMY_READ, false, true,
                "Lire les récompenses monétaires en attente d'un joueur");
        addSensitiveWrite("economy.debt.retry", Permission.ECONOMY_WRITE, true,
                "Reprendre le paiement d'une récompense due", "player.catalog");
        addSensitiveWrite("economy.debt.settle", Permission.ECONOMY_WRITE, true,
                "Marquer une récompense due comme réglée à la main", "player.catalog");
        // Issue #200 — pont vers les droits Minecraft. Distribuer un droit de construction ou un
        // bypass est au moins aussi sensible que gérer un groupe du panel : même permission.
        add("mc.rights.read", Permission.USER_MANAGE, false, true,
                "Lire les droits Minecraft réellement portés par un joueur");
        addSensitiveWrite("mc.group.sync", Permission.USER_MANAGE, false,
                "Appliquer les droits Minecraft d'un groupe");
        addSensitiveWrite("mc.group.delete", Permission.USER_MANAGE, false,
                "Retirer le groupe de droits Minecraft");
        addSensitiveWrite("mc.rights.sync", Permission.USER_MANAGE, true,
                "Synchroniser les droits Minecraft d'un joueur", "player.catalog");
        addSensitiveWrite("player.ban", Permission.PLAYER_MODERATE, true, "Bannir un joueur", "player.catalog");
        addSensitiveWrite("player.unban", Permission.PLAYER_MODERATE, true, "Débannir un joueur", "player.catalog");
        add("quest.start", Permission.ACTION_QUEST, true, true, "Démarrer une quête");
        add("quest.complete", Permission.ACTION_QUEST, true, true, "Compléter une quête");
        add("quest.reset", Permission.ACTION_QUEST, true, true, "Réinitialiser une quête");
        // Issue #95 — exploitation serveur. L'annonce est une mutation visible IMMÉDIATEMENT par
        // tous les joueurs et impossible à reprendre : confirmation exigée. La console est un
        // relevé comme les autres.
        addSensitiveWrite("server.announce", Permission.OPS_ANNOUNCE, false,
                "Envoyer une annonce globale aux joueurs");
        add("server.logs.tail", Permission.OPS_LOGS, false, false,
                "Lire les dernières lignes de la console serveur");
        // Issue #131 — rechargement du contenu dans le runtime. L'aperçu est une LECTURE (il ne
        // touche aucun ensemble actif) ; l'application est une mutation sensible, car elle change
        // ce que le serveur utilise pour des joueurs connectés. La permission existait déjà sans
        // aucune action derrière elle : la voici enfin utilisée.
        add("content.reload.preview", Permission.ACTION_CONTENT_RELOAD, false, false,
                "Valider le contenu sans rien appliquer");
        addSensitiveWrite("content.reload", Permission.ACTION_CONTENT_RELOAD, false,
                "Recharger le contenu en jeu",
                "quest.list", "story.list", "dialogue.list", "npc.list", "item.list", "mob.list");
        add("story.advance", Permission.ACTION_STORY, true, true, "Avancer une story");
        add("story.complete", Permission.ACTION_STORY, true, true, "Compléter une story");
    }

    private AgentActionCatalog() {
    }

    public static Optional<Spec> spec(String type) {
        return Optional.ofNullable(SPECS.get(type == null ? "" : type.trim()));
    }

    /** Toutes les specs whitelistées (lecture seule) — pour l'introspection et les tests RBAC (#50). */
    public static java.util.Collection<Spec> all() {
        return java.util.Collections.unmodifiableCollection(SPECS.values());
    }

    public static boolean isWhitelisted(String type) {
        return SPECS.containsKey(type == null ? "" : type.trim());
    }

    /** Résultat de validation : soit {@code params} (prêts à créer l'action), soit {@code error}. */
    public record Validation(Map<String, String> params, String error) {
        static Validation ok(Map<String, String> params) {
            return new Validation(params, null);
        }

        static Validation fail(String error) {
            return new Validation(null, error);
        }

        public boolean valid() {
            return error == null;
        }
    }

    /**
     * Valide {@code form} pour {@code type} et produit les paramètres normalisés à transmettre à
     * l'agent. Les mutations exigent {@code confirm=true} dans le formulaire.
     */
    /** Issue #335 — clés de {@code settings.set}, les mêmes que le plugin. */
    public static final List<String> SETTINGS_KEYS = List.of("currency_name", "beta_warning_enabled", "beta_warning_text",
            "admin_bypass_available", "admin_bypass_max_minutes");

    public static Validation validate(String type, Map<String, String> form) {
        Optional<Spec> maybe = spec(type);
        if (maybe.isEmpty()) {
            return Validation.fail("Type d'action non autorisé : " + safe(type));
        }
        Spec spec = maybe.get();
        Map<String, String> params = new LinkedHashMap<>();

        if (spec.needsPlayer()) {
            String player = trim(form.get("player"));
            if (player.isEmpty() || !PLAYER_REF.matcher(player).matches()) {
                return Validation.fail("Nom / UUID de joueur manquant ou invalide.");
            }
            params.put("player", player);
        }
        if (spec.mutation() && spec.sensitive() && !"true".equals(trim(form.get("confirm")))) {
            return Validation.fail("Confirmation obligatoire pour « " + spec.label() + " ».");
        }

        switch (type) {
            case "player.variable.get" -> {
                String key = orDefault(trim(form.get("key")), "CLAIM_TIER_1");
                if (!VARIABLE_KEY.matcher(key).matches()) {
                    return Validation.fail("Clé de variable invalide.");
                }
                params.put("key", key);
            }
            case "player.variable.set" -> {
                String key = trim(form.get("key"));
                String value = form.getOrDefault("value", "");
                if (!VARIABLE_KEY.matcher(key).matches()) {
                    return Validation.fail("Clé de variable invalide.");
                }
                if (value.isEmpty() || value.length() > 256) {
                    return Validation.fail("Valeur manquante ou trop longue (max 256).");
                }
                // Issue #319 : mêmes types que le plugin (VariableTypes), vérifiés des deux côtés.
                String v = value.trim();
                if (key.matches("CLAIM_TIER_\\d+|WILD_ENTRY_WARNING_HIDDEN|BETA_WARNING_HIDDEN|RUNE_RAPPEL_GRANTED")
                        && !"true".equals(v) && !"false".equals(v)) {
                    return Validation.fail("« " + v + " » n'est pas valide pour " + key + " : true ou false.");
                }
                if ("STARTER_KIT_TIER".equals(key) && !v.matches("[1-9]\\d?")) {
                    return Validation.fail("« " + v + " » n'est pas valide pour STARTER_KIT_TIER : un nombre entier de 1 à 99.");
                }
                params.put("key", key);
                params.put("value", value);
            }
            case "player.item.give" -> {
                String itemId = trim(form.get("item_id"));
                if (!RESOURCE_ID.matcher(itemId).matches()) {
                    return Validation.fail("Identifiant d'objet manquant ou invalide.");
                }
                int amount;
                try {
                    amount = Integer.parseInt(orDefault(trim(form.get("amount")), "1"));
                } catch (NumberFormatException e) {
                    return Validation.fail("Quantité invalide.");
                }
                if (amount < 1 || amount > 64) {
                    return Validation.fail("Quantité hors bornes (1 à 64).");
                }
                params.put("item_id", itemId);
                params.put("amount", Integer.toString(amount));
            }
            case "quest.start" -> {
                String questId = trim(form.get("quest_id"));
                if (!RESOURCE_ID.matcher(questId).matches()) {
                    return Validation.fail("Identifiant de quête manquant ou invalide.");
                }
                params.put("quest_id", questId);
                if ("true".equals(trim(form.get("force")))) {
                    params.put("force", "true");
                }
            }
            case "quest.complete", "quest.reset" -> {
                String questId = trim(form.get("quest_id"));
                if (!RESOURCE_ID.matcher(questId).matches()) {
                    return Validation.fail("Identifiant de quête manquant ou invalide.");
                }
                params.put("quest_id", questId);
            }
            case "story.advance", "story.complete" -> {
                String storyId = trim(form.get("story_id")).toLowerCase(java.util.Locale.ROOT);
                if (!STORY_ID.matcher(storyId).matches()) {
                    return Validation.fail("Identifiant de story manquant ou invalide.");
                }
                params.put("story_id", storyId);
            }
            case "npc.definition.create", "npc.definition.update" -> {
                String npcId = trim(form.get("npc_id")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(npcId).matches()) {
                    return Validation.fail("Identifiant de PNJ manquant ou invalide (minuscules, « . _ - »).");
                }
                String displayName = trim(form.get("display_name"));
                if (displayName.isEmpty() || displayName.length() > 128 || displayName.indexOf('\n') >= 0) {
                    return Validation.fail("Nom affiché manquant, trop long, ou multi-ligne.");
                }
                String dialogueId = trim(form.get("dialogue_id")).toLowerCase(java.util.Locale.ROOT);
                if (!dialogueId.isEmpty() && !DIALOGUE_REF.matcher(dialogueId).matches()) {
                    return Validation.fail("Dialogue invalide (« namespace:clé » ou une clé simple).");
                }
                String role = trim(form.get("role")).toLowerCase(java.util.Locale.ROOT);
                if (!role.isEmpty() && !NPC_ROLE.matcher(role).matches()) {
                    return Validation.fail("Rôle invalide (minuscules, « _ - », max 32).");
                }
                params.put("npc_id", npcId);
                params.put("display_name", displayName);
                if (!dialogueId.isEmpty()) {
                    params.put("dialogue_id", dialogueId);
                }
                if (!role.isEmpty()) {
                    params.put("role", role);
                }
                params.put("enabled", "true".equals(trim(form.get("enabled"))) ? "true" : "false");
            }
            case "quest.giver.set" -> {
                String questId = trim(form.get("quest_id"));
                if (!RESOURCE_ID.matcher(questId).matches()) {
                    return Validation.fail("Identifiant de quête manquant ou invalide.");
                }
                String npcId = trim(form.get("npc_id")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(npcId).matches()) {
                    return Validation.fail("Identifiant de PNJ manquant ou invalide.");
                }
                params.put("quest_id", questId);
                params.put("npc_id", npcId);
            }
            // Issue #226 — une suppression de PNJ ne part jamais sans cible explicite.
            // Issue #213 — bornes miroir de BuildingSite, revalidées par le serveur.
            case "building.site.rename" -> {
                String id = buildSiteId(form);
                if (id == null) {
                    return Validation.fail("Identifiant d'emplacement manquant ou invalide.");
                }
                String name = trim(form.get("name"));
                if (name.isEmpty()) {
                    return Validation.fail("Un emplacement garde toujours un libellé.");
                }
                if (name.length() > MAX_BUILD_SITE_NAME) {
                    return Validation.fail("Nom trop long (" + MAX_BUILD_SITE_NAME
                            + " caractères au plus).");
                }
                params.put("id", id);
                params.put("name", name);
            }
            case "building.site.describe" -> {
                String id = buildSiteId(form);
                if (id == null) {
                    return Validation.fail("Identifiant d'emplacement manquant ou invalide.");
                }
                // Une description vide est une valeur valide : c'est « effacer la note ».
                String description = trim(form.get("description"));
                if (description.length() > MAX_BUILD_SITE_DESCRIPTION) {
                    return Validation.fail("Description trop longue (" + MAX_BUILD_SITE_DESCRIPTION
                            + " caractères au plus).");
                }
                params.put("id", id);
                params.put("description", description);
            }
            // Issue #94 — le plan est une DONNÉE, transmise telle quelle au plugin qui la
            // revalide. Le panel ne borne ici que sa taille et la forme du drapeau de
            // remplacement : décider ce qu'est un plan valide est le travail du domaine, et le
            // dupliquer ici garantirait seulement que les deux divergent.
            // L'audit du lot F partage la validation du plan avec la génération : même borne, même
            // refus. Seul le drapeau de remplacement n'a pas de sens pour un audit, qui n'écrit rien.
            case "building.generate", "building.plan.audit", "building.plan.preview" -> {
                String plan = trim(form.get("plan"));
                if (plan.isEmpty()) {
                    return Validation.fail("Aucun plan à " + switch (type) {
                        case "building.plan.audit" -> "auditer.";
                        case "building.plan.preview" -> "prévisualiser.";
                        default -> "générer.";
                    });
                }
                if (plan.length() > MAX_BUILD_PLAN) {
                    return Validation.fail("Plan trop volumineux : " + plan.length()
                            + " caractères pour un maximum de " + MAX_BUILD_PLAN + ".");
                }
                params.put("plan", plan);
                if ("building.generate".equals(type) && "true".equals(trim(form.get("replace")))) {
                    params.put("replace", "true");
                    // #290 : la version vue au moment de republier — le plugin refuse si une
                    // autre publication est passée entre-temps.
                    String expected = trim(form.get("expected_version"));
                    if (!expected.isEmpty()) {
                        if (!expected.matches("[0-9]{1,6}") || Integer.parseInt(expected) < 1) {
                            return Validation.fail("Version attendue invalide : entier positif attendu.");
                        }
                        params.put("expected_version", expected);
                    }
                }
                // #282 : les bornes du projet, pour que la réparation déterministe du plugin
                // puisse agrandir le volume au lieu d'araser un toit. Optionnelles, entières.
                if (!"building.generate".equals(type)) {
                    for (String key : new String[] {"max_width", "max_height"}) {
                        String raw = trim(form.get(key));
                        if (raw.isEmpty()) {
                            continue;
                        }
                        if (!raw.matches("[0-9]{1,2}") || Integer.parseInt(raw) < 1
                                || Integer.parseInt(raw) > 64) {
                            return Validation.fail("Borne « " + key + " » invalide : entier de 1 à "
                                    + "64 attendu.");
                        }
                        params.put(key, raw);
                    }
                }
            }
            // Issue #94 — brief architectural. Un brief vide est valide : c'est « repartir de
            // zéro ». Au-delà de la borne, on REFUSE en disant de combien on dépasse, plutôt que
            // de tronquer : c'est tout le défaut que ce lot corrige.
            case "building.site.brief" -> {
                String id = buildSiteId(form);
                if (id == null) {
                    return Validation.fail("Identifiant d'emplacement manquant ou invalide.");
                }
                // #292 : fins de ligne normalisées avant de compter, comme pour un projet d'Atelier.
                String brief = trim(form.get("brief")).replace("\r\n", "\n").replace('\r', '\n');
                if (brief.length() > MAX_BUILD_SITE_BRIEF) {
                    return Validation.fail("Brief trop long : " + brief.length()
                            + " caractères pour un maximum de " + MAX_BUILD_SITE_BRIEF
                            + ". Rien n'a été enregistré — raccourcir de "
                            + (brief.length() - MAX_BUILD_SITE_BRIEF) + " caractères.");
                }
                params.put("id", id);
                params.put("brief", brief);
            }
            case "building.site.facing" -> {
                String id = buildSiteId(form);
                if (id == null) {
                    return Validation.fail("Identifiant d'emplacement manquant ou invalide.");
                }
                String facing = trim(form.get("facing")).toUpperCase(java.util.Locale.ROOT);
                if (!BUILD_SITE_FACINGS.contains(facing)) {
                    return Validation.fail("Orientation inconnue : attendu "
                            + String.join(", ", BUILD_SITE_FACINGS) + ".");
                }
                params.put("id", id);
                params.put("facing", facing);
            }
            case "encounter.template.create", "encounter.template.update" -> {
                // Issue #13 étape 3 : contrôle de forme seulement ; le serveur revalide avec le
                // parseur des fichiers, qui reste la seule autorité.
                String error = encounterTemplateParams(form, params);
                if (error != null) {
                    return Validation.fail(error);
                }
            }
            case "encounter.template.toggle", "encounter.template.delete" -> {
                String id = trim(form.get("id")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(id).matches()) {
                    return Validation.fail("Identifiant de modèle manquant ou invalide.");
                }
                params.put("id", id);
                if (type.endsWith("toggle")) {
                    params.put("enabled", "true".equals(trim(form.get("enabled"))) ? "true" : "false");
                } else if ("true".equals(trim(form.get("acknowledge_sites")))) {
                    params.put("acknowledge_sites", "true");
                }
            }
            case "quest.catalog.create", "quest.catalog.update" -> {
                // Issue #369 : contrôle de forme ; le serveur revalide avec le parseur des fichiers.
                String error = questCatalogParams(form, params);
                if (error != null) {
                    return Validation.fail(error);
                }
            }
            case "quest.catalog.delete" -> {
                String id = trim(form.get("id")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(id).matches()) {
                    return Validation.fail("Identifiant de catalogue manquant ou invalide.");
                }
                params.put("id", id);
                if ("true".equals(trim(form.get("detach")))) {
                    params.put("detach", "true");
                }
            }
            case "encounter.retry", "encounter.disable", "encounter.remove" -> {
                // Issue #13 : une seule occurrence, désignée par son identifiant — jamais un lot.
                String siteId = trim(form.get("siteId"));
                if (!ENCOUNTER_SITE_ID.matcher(siteId).matches()) {
                    return Validation.fail("Identifiant de PNJ récurrent manquant ou invalide.");
                }
                params.put("siteId", siteId);
            }
            case "travel.beacon.pair" -> {
                // Issue #156 : l'instance visée est désignée par SON waypoint, jamais par des
                // coordonnées libres — on ne peut donc pas demander une borne « quelque part ».
                String waypointId = form.getOrDefault("waypointId", "").trim();
                if (!WAYPOINT_ID.matcher(waypointId).matches()) {
                    return Validation.fail("Identifiant de waypoint manquant ou invalide.");
                }
                params.put("waypointId", waypointId);
            }
            case "waypoint.rename" -> {
                // Issue #134 : mêmes règles que le plugin (qui revalide et seul juge de l'unicité).
                String waypointId = trim(form.get("waypointId"));
                if (!WAYPOINT_ID.matcher(waypointId).matches()) {
                    return Validation.fail("Identifiant de waypoint manquant ou invalide.");
                }
                String name = trim(form.get("displayName")).replaceAll("\\s+", " ");
                if (name.length() < 2 || name.length() > 40) {
                    return Validation.fail("Le nom d'un waypoint fait entre 2 et 40 caractères.");
                }
                if (!WAYPOINT_DISPLAY_NAME.matcher(name).matches()) {
                    return Validation.fail("Caractères autorisés : lettres, chiffres, espaces, apostrophes, "
                            + "tirets et points (commencer par une lettre ou un chiffre).");
                }
                params.put("waypointId", waypointId);
                params.put("displayName", name);
            }
            case "building.definition.retire" -> {
                String id = trim(form.get("id")).toLowerCase(java.util.Locale.ROOT);
                if (!id.matches("[a-z0-9_]{3,48}")) {
                    return Validation.fail("Identifiant de bâtiment manquant ou invalide.");
                }
                if (!"true".equals(trim(form.get("confirm")))) {
                    return Validation.fail("Cochez la confirmation : rien n'a été retiré.");
                }
                params.put("id", id);
                String by = trim(form.get("by"));
                if (by.matches("[A-Za-z0-9_.@-]{1,64}")) {
                    params.put("by", by);
                }
                params.put("confirm", "true");
            }
            // Issue #178 — villes : bornes revalidées par le plugin, refus lisibles ici d'abord.
            case "city.rename", "city.resize", "city.active", "city.move.arm", "city.perimeter.show",
                 "city.delete", "city.npc.link", "city.arrival.set", "city.arrival.clear" -> {
                String id = trim(form.get("id"));
                if (!CITY_ID.matcher(id).matches()) {
                    return Validation.fail("Identifiant de ville manquant ou invalide.");
                }
                params.put("id", id);
                switch (type) {
                    case "city.rename" -> {
                        String name = trim(form.get("name")).replaceAll("\\s+", " ");
                        if (name.length() < 2 || name.length() > 40) {
                            return Validation.fail("Le nom d'une ville fait entre 2 et 40 caractères.");
                        }
                        params.put("name", name);
                    }
                    case "city.resize" -> {
                        int distance;
                        try {
                            distance = Integer.parseInt(trim(form.get("distance")));
                        } catch (NumberFormatException e) {
                            return Validation.fail("La distance doit être un nombre entier de blocs.");
                        }
                        if (distance < CITY_MIN_DISTANCE || distance > CITY_MAX_DISTANCE) {
                            return Validation.fail("Distance hors limites : entre " + CITY_MIN_DISTANCE
                                    + " et " + CITY_MAX_DISTANCE + " blocs dans chaque direction.");
                        }
                        params.put("distance", String.valueOf(distance));
                    }
                    case "city.active" -> {
                        String active = trim(form.get("active"));
                        if (!"true".equals(active) && !"false".equals(active)) {
                            return Validation.fail("État attendu : active ou inactive.");
                        }
                        params.put("active", active);
                    }
                    case "city.npc.link" -> {
                        // Vide = retirer le PNJ central. Sinon l'identité stable, jamais le nom affiché.
                        String npc = trim(form.get("npc"));
                        if (!npc.isEmpty() && !NPC_ID.matcher(npc).matches()) {
                            return Validation.fail("Choisissez un PNJ de la liste (identifiant stable, "
                                    + "minuscules, chiffres, . _ -), ou laissez vide pour le retirer.");
                        }
                        if (!npc.isEmpty()) {
                            params.put("npc", npc);
                        }
                    }
                    case "city.arrival.clear" -> {
                        // L'identifiant suffit.
                    }
                    case "city.move.arm", "city.perimeter.show", "city.arrival.set" -> {
                        String player = trim(form.get("player"));
                        if (!CITY_PLAYER.matcher(player).matches()) {
                            return Validation.fail("Choisissez le pseudo d'un joueur connecté.");
                        }
                        params.put("player", player);
                    }
                    default -> params.put("confirm", "true");
                }
            }
            case "npc.spot.rename" -> {
                String id = npcSpotId(form);
                if (id == null) {
                    return Validation.fail("Identifiant d'emplacement manquant ou invalide.");
                }
                String name = trim(form.get("name"));
                if (name.isEmpty()) {
                    return Validation.fail("Un emplacement garde toujours un nom.");
                }
                if (name.length() > MAX_NPC_SPOT_NAME) {
                    return Validation.fail("Nom trop long (" + MAX_NPC_SPOT_NAME + " caractères au plus).");
                }
                params.put("spot_id", id);
                params.put("name", name);
            }
            case "npc.spot.orient" -> {
                String id = npcSpotId(form);
                if (id == null) {
                    return Validation.fail("Identifiant d'emplacement manquant ou invalide.");
                }
                // Direction en mots (comme le déplacement d'un PNJ, #325) ; un angle saisi l'emporte.
                String rawYaw = trim(form.get("yaw"));
                if (rawYaw.isEmpty()) {
                    rawYaw = switch (trim(form.get("facing"))) {
                        case "south" -> "0";
                        case "west" -> "90";
                        case "north" -> "180";
                        case "east" -> "-90";
                        default -> "";
                    };
                }
                Double yaw = finite(rawYaw);
                if (yaw == null || Math.abs(yaw) > 360) {
                    return Validation.fail("Choisissez une direction, ou un angle entre −360 et 360.");
                }
                params.put("spot_id", id);
                params.put("yaw", trimNumber(yaw));
            }
            case "npc.spot.release", "npc.spot.delete" -> {
                String id = npcSpotId(form);
                if (id == null) {
                    return Validation.fail("Identifiant d'emplacement manquant ou invalide.");
                }
                params.put("spot_id", id);
            }
            case "npc.spot.place" -> {
                String id = npcSpotId(form);
                if (id == null) {
                    return Validation.fail("Identifiant d'emplacement manquant ou invalide.");
                }
                String npcId = trim(form.get("npc_id")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(npcId).matches()) {
                    return Validation.fail("Choisissez le PNJ à placer.");
                }
                params.put("spot_id", id);
                params.put("npc_id", npcId);
            }
            case "npc.spot.capture" -> {
                String npcId = trim(form.get("npc_id")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(npcId).matches()) {
                    return Validation.fail("Choisissez le PNJ dont la position sera réservée.");
                }
                String name = trim(form.get("name"));
                if (name.length() > MAX_NPC_SPOT_NAME) {
                    return Validation.fail("Nom trop long (" + MAX_NPC_SPOT_NAME + " caractères au plus).");
                }
                params.put("npc_id", npcId);
                if (!name.isEmpty()) {
                    params.put("name", name);
                }
                String by = trim(form.get("by"));
                if (by.matches("[A-Za-z0-9_.@-]{1,64}")) {
                    params.put("by", by);
                }
            }
            case "building.site.delete" -> {
                String id = buildSiteId(form);
                if (id == null) {
                    return Validation.fail("Identifiant d'emplacement manquant ou invalide.");
                }
                params.put("id", id);
            }
            case "content.publish" -> {
                String kind = publishKind(form);
                if (kind == null) {
                    return Validation.fail("Famille de contenu non publiable. Attendu : "
                            + String.join(", ", PUBLISHABLE_KINDS) + ".");
                }
                String slug = publishSlug(form);
                if (slug == null) {
                    return Validation.fail("Identifiant de ressource manquant ou invalide.");
                }
                // Les deux empreintes sont OBLIGATOIRES, la chaîne vide signifiant « absente ».
                // Sans elles, une modification faite entre l'aperçu et la publication serait
                // écrasée en silence — des deux côtés : source ET DEV.
                String expectedDev = sha(form.get("expected_dev_sha"));
                if (expectedDev == null) {
                    return Validation.fail("Empreinte DEV attendue manquante ou mal formée : "
                            + "relancez l'analyse.");
                }
                String expectedSource = sha(form.get("expected_source_sha"));
                if (expectedSource == null || expectedSource.isEmpty()) {
                    return Validation.fail("Empreinte source attendue manquante : "
                            + "relancez l'analyse.");
                }
                params.put("kind", kind);
                params.put("id", slug);
                params.put("expected_dev_sha", expectedDev);
                // Non transmis à l'agent : vérifié côté panel juste avant d'enfiler l'action, parce
                // que c'est le panel qui détient la source. Voir PanelApp#enrichPublish.
                params.put("expected_source_sha", expectedSource);
                params.put("confirm", "true");
            }
            case "content.dev.read" -> {
                String kind = publishKind(form);
                if (kind == null) {
                    return Validation.fail("Famille de contenu non publiable.");
                }
                String slug = publishSlug(form);
                if (slug == null) {
                    return Validation.fail("Identifiant de ressource manquant ou invalide.");
                }
                params.put("kind", kind);
                params.put("id", slug);
            }
            case "content.publish.rollback" -> {
                String kind = publishKind(form);
                if (kind == null) {
                    return Validation.fail("Famille de contenu non publiable.");
                }
                String slug = publishSlug(form);
                if (slug == null) {
                    return Validation.fail("Identifiant de ressource manquant ou invalide.");
                }
                params.put("kind", kind);
                params.put("id", slug);
                // Chemin de sauvegarde : facultatif, et JAMAIS un chemin libre — il doit avoir la
                // forme produite par le serveur. Absent = « retirer la ressource nouvelle ».
                String backup = trim(form.get("backup"));
                if (!backup.isEmpty()) {
                    if (!BACKUP_PATH.matcher(backup).matches()) {
                        return Validation.fail("Chemin de sauvegarde invalide.");
                    }
                    params.put("backup", backup);
                }
                params.put("confirm", "true");
            }
            case "building.placement.preview", "building.placement.place" -> {
                String id = buildSiteId(form);
                if (id == null) {
                    return Validation.fail("Identifiant d'emplacement manquant ou invalide.");
                }
                String building = trim(form.get("building")).toLowerCase(java.util.Locale.ROOT);
                if (!BUILDING_ID.matcher(building).matches()) {
                    return Validation.fail("Identifiant de bâtiment manquant ou invalide.");
                }
                params.put("id", id);
                params.put("building", building);
                // La confirmation n'est exigée que pour la pose : l'aperçu n'écrit rien, et obliger
                // à cocher une case pour regarder une emprise découragerait de la regarder.
                if (type.equals("building.placement.place")) {
                    params.put("confirm", "true");
                }
            }
            case "building.placement.rollback", "building.placement.history" -> {
                String id = buildSiteId(form);
                if (id == null) {
                    return Validation.fail("Identifiant d'emplacement manquant ou invalide.");
                }
                params.put("id", id);
                // La libération écrit dans le monde ; le journal se contente de lire.
                if (type.equals("building.placement.rollback")) {
                    // Issue #239 : confirmation FORTE — l'identifiant de l'emplacement, retapé. Une
                    // case cochée par réflexe ne suffit pas pour effacer des blocs.
                    String typed = trim(form.get("confirm_site")).toLowerCase(java.util.Locale.ROOT);
                    if (!typed.equals(id)) {
                        return Validation.fail("Pour retirer le bâtiment, retapez exactement "
                                + "l'identifiant de l'emplacement (« " + id + " »). Rien n'a été fait.");
                    }
                    params.put("confirm", "true");
                }
            }
            // Issue #234 — réorienter et remplacer. Le navigateur n'envoie QUE l'identifiant de
            // l'emplacement, celui du bâtiment, l'orientation et le jeton de l'aperçu : jamais une
            // emprise, jamais un chemin, jamais un nom de fichier.
            case "building.placement.retarget.preview", "building.placement.reorient",
                 "building.placement.replace" -> {
                String id = buildSiteId(form);
                if (id == null) {
                    return Validation.fail("Identifiant d'emplacement manquant ou invalide.");
                }
                String facing = trim(form.get("facing")).toUpperCase(java.util.Locale.ROOT);
                if (!facing.equals("NORTH") && !facing.equals("EAST")
                        && !facing.equals("SOUTH") && !facing.equals("WEST")) {
                    return Validation.fail("Orientation manquante ou invalide "
                            + "(NORTH, EAST, SOUTH ou WEST).");
                }
                params.put("id", id);
                params.put("facing", facing);
                // Le bâtiment est OBLIGATOIRE pour un remplacement, facultatif sinon : réorienter
                // porte par définition sur le bâtiment déjà posé.
                String building = trim(form.get("building")).toLowerCase(java.util.Locale.ROOT);
                if (type.equals("building.placement.replace")) {
                    if (!BUILDING_ID.matcher(building).matches()) {
                        return Validation.fail("Identifiant de bâtiment manquant ou invalide.");
                    }
                    params.put("building", building);
                } else if (BUILDING_ID.matcher(building).matches()) {
                    params.put("building", building);
                }
                // Le jeton de l'aperçu voyage avec la confirmation : c'est lui qui fait refuser une
                // décision prise sur un aperçu périmé. Facultatif pour l'aperçu lui-même.
                String token = trim(form.get("token"));
                if (!type.equals("building.placement.retarget.preview")) {
                    if (!token.matches("[0-9a-f]{0,32}")) {
                        return Validation.fail("Jeton d'aperçu invalide.");
                    }
                    params.put("token", token);
                    params.put("confirm", "true");
                }
            }
            // Issue #246 — le navigateur n'envoie que l'emplacement, des marges entières bornées et
            // l'identité du nouveau bâtiment : jamais une emprise, un chemin ni un nom de fichier.
            case "building.placement.capture.preview", "building.placement.capture" -> {
                String id = buildSiteId(form);
                if (id == null) {
                    return Validation.fail("Identifiant d'emplacement manquant ou invalide.");
                }
                params.put("id", id);
                for (String side : new String[] {"west", "east", "down", "up", "north", "south"}) {
                    String raw = trim(form.get("margin_" + side));
                    if (raw.isEmpty()) {
                        raw = "0";
                    }
                    if (!raw.matches("-?[0-9]{1,2}") || Math.abs(Integer.parseInt(raw)) > 16) {
                        return Validation.fail("Ajustement des bornes invalide (" + side
                                + ") : un entier entre -16 et 16.");
                    }
                    params.put("margin_" + side, String.valueOf(Integer.parseInt(raw)));
                }
                String newId = trim(form.get("new_id")).toLowerCase(java.util.Locale.ROOT);
                if (!BUILDING_ID.matcher(newId).matches()) {
                    return Validation.fail("Identifiant du nouveau bâtiment invalide (minuscules, "
                            + "chiffres et « _ », 64 caractères au plus).");
                }
                String name = trim(form.get("name"));
                if (name.isEmpty() || name.length() > 64 || name.contains("\n") || name.contains("\r")) {
                    return Validation.fail("Nom du nouveau bâtiment requis, sur une ligne, 64 caractères "
                            + "au plus.");
                }
                String description = trim(form.get("description")).replace("\r\n", "\n").replace('\r', '\n');
                if (description.length() > 500) {
                    return Validation.fail("Description trop longue (500 caractères au plus).");
                }
                String category = trim(form.get("category")).toLowerCase(java.util.Locale.ROOT);
                if (!category.isEmpty() && !category.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
                    return Validation.fail("Catégorie invalide (minuscules, chiffres, « _ » ou « - »).");
                }
                params.put("new_id", newId);
                params.put("name", name);
                params.put("description", description);
                params.put("category", category);
                if (type.equals("building.placement.capture")) {
                    String token = trim(form.get("token"));
                    if (!token.matches("[0-9a-f]{32}")) {
                        return Validation.fail("Jeton d'aperçu manquant : calculez l'aperçu de la capture "
                                + "avant de confirmer.");
                    }
                    params.put("token", token);
                    params.put("confirm", "true");
                }
            }
            case "npc.definition.delete" -> {
                String npcId = trim(form.get("npc_id")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(npcId).matches()) {
                    return Validation.fail("Identifiant de PNJ manquant ou invalide.");
                }
                params.put("npc_id", npcId);
                // Ce que l'écran croyait vrai : le serveur le confronte à la réalité et refuse si
                // cela ne correspond plus. Facultatif, mais jamais inventé ici.
                String expect = trim(form.get("expect_dialogue"));
                if (!expect.isEmpty()) {
                    if (!DIALOGUE_REF.matcher(expect).matches()) {
                        return Validation.fail("Identifiant de dialogue attendu invalide.");
                    }
                    params.put("expect_dialogue", expect);
                }
            }
            case "npc.citizens.unlink", "npc.citizens.delete" -> {
                String npcId = trim(form.get("npc_id")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(npcId).matches()) {
                    return Validation.fail("Identifiant de PNJ manquant ou invalide.");
                }
                int citizensId;
                try {
                    citizensId = Integer.parseInt(trim(form.get("citizens_id")));
                } catch (NumberFormatException e) {
                    return Validation.fail("Identifiant Citizens manquant ou invalide : une "
                            + "suppression ne se fait jamais sans cible numérique explicite.");
                }
                if (citizensId < 1 || citizensId > 10_000_000) {
                    return Validation.fail("Identifiant Citizens hors bornes.");
                }
                params.put("npc_id", npcId);
                params.put("citizens_id", String.valueOf(citizensId));
            }
            case "npc.citizens.link" -> {
                String npcId = trim(form.get("npc_id")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(npcId).matches()) {
                    return Validation.fail("Identifiant de PNJ manquant ou invalide.");
                }
                int citizensId;
                try {
                    citizensId = Integer.parseInt(trim(form.get("citizens_id")));
                } catch (NumberFormatException e) {
                    return Validation.fail("Identifiant Citizens manquant ou invalide.");
                }
                if (citizensId < 1 || citizensId > 10_000_000) {
                    return Validation.fail("Identifiant Citizens hors bornes.");
                }
                params.put("npc_id", npcId);
                params.put("citizens_id", Integer.toString(citizensId));
            }
            case "npc.citizens.move" -> {
                String npcId = trim(form.get("npc_id")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(npcId).matches()) {
                    return Validation.fail("Identifiant de PNJ manquant ou invalide.");
                }
                String world = trim(form.get("world"));
                if (!WORLD_NAME.matcher(world).matches()) {
                    return Validation.fail("Monde manquant ou invalide.");
                }
                Double x = finite(form.get("x"));
                Double y = finite(form.get("y"));
                Double z = finite(form.get("z"));
                if (x == null || y == null || z == null) {
                    return Validation.fail("Coordonnées X / Y / Z manquantes ou non numériques.");
                }
                if (Math.abs(x) > HORIZONTAL_LIMIT || Math.abs(z) > HORIZONTAL_LIMIT) {
                    return Validation.fail("X / Z hors du bord de monde (±" + (long) HORIZONTAL_LIMIT + ").");
                }
                if (y < Y_MIN || y > Y_MAX) {
                    return Validation.fail("Y hors bornes de sécurité (" + (long) Y_MIN + " à "
                            + (long) Y_MAX + ").");
                }
                params.put("npc_id", npcId);
                params.put("world", world);
                params.put("x", trimNumber(x));
                params.put("y", trimNumber(y));
                params.put("z", trimNumber(z));
                // Issue #325 : la direction se choisit en mots (« le nord », « vers le haut »). Les
                // angles précis, s'ils sont saisis, l'emportent. Rien de choisi = orientation gardée.
                String rawYaw = trim(form.get("yaw"));
                String rawPitch = trim(form.get("pitch"));
                if (rawYaw.isEmpty()) {
                    rawYaw = switch (trim(form.get("facing"))) {
                        case "south" -> "0";
                        case "west" -> "90";
                        case "north" -> "180";
                        case "east" -> "-90";
                        default -> "";
                    };
                }
                if (rawPitch.isEmpty()) {
                    rawPitch = switch (trim(form.get("tilt"))) {
                        case "level" -> "0";
                        case "up" -> "-30";
                        case "down" -> "30";
                        default -> "";
                    };
                }
                if (!rawYaw.isEmpty()) {
                    Double yawValue = finite(rawYaw);
                    if (yawValue == null) {
                        return Validation.fail("La rotation doit être un nombre.");
                    }
                    params.put("yaw", trimNumber(yawValue));
                }
                if (!rawPitch.isEmpty()) {
                    Double pitchValue = finite(rawPitch);
                    if (pitchValue == null || pitchValue < -90.0 || pitchValue > 90.0) {
                        return Validation.fail("L'inclinaison doit être comprise entre -90 et 90.");
                    }
                    params.put("pitch", trimNumber(pitchValue));
                }
            }
            // Issue #165 — « regarder les joueurs ». `enabled` porte un ÉTAT, jamais une bascule :
            // le formulaire envoie « true » ou « false », et rien d'autre n'est accepté. C'est ce
            // qui garantit qu'un double clic ou un rejeu ne peut pas inverser l'état obtenu.
            case "npc.citizens.lookclose" -> {
                String npcId = trim(form.get("npc_id")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(npcId).matches()) {
                    return Validation.fail("Identifiant de PNJ manquant ou invalide.");
                }
                String enabled = trim(form.get("enabled"));
                // Issue #325 : formulaire à case à cocher. La case porte l'état voulu — cochée ou
                // non — et ce sont ces deux états explicites qui partent, jamais une bascule.
                if (enabled.isEmpty() && "1".equals(trim(form.get("state_form")))) {
                    enabled = "on".equals(trim(form.get("state"))) ? "true" : "false";
                }
                if (!"true".equals(enabled) && !"false".equals(enabled)) {
                    return Validation.fail("État manquant : choisir « activé » ou « désactivé ».");
                }
                params.put("npc_id", npcId);
                params.put("enabled", enabled);
                String rawRange = trim(form.get("range"));
                if (!rawRange.isEmpty()) {
                    Double range = finite(rawRange);
                    if (range == null || range <= 0 || range > LOOKCLOSE_MAX_RANGE) {
                        return Validation.fail("La portée doit être comprise entre 1 et "
                                + (long) LOOKCLOSE_MAX_RANGE + " blocs.");
                    }
                    params.put("range", trimNumber(range));
                }
            }
            // Issue #165 — promenade. Même discipline d'état explicite. L'ancre est facultative,
            // mais jamais partielle : on refuse plutôt que de compléter une coordonnée manquante.
            case "npc.citizens.wander" -> {
                String npcId = trim(form.get("npc_id")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(npcId).matches()) {
                    return Validation.fail("Identifiant de PNJ manquant ou invalide.");
                }
                String enabled = trim(form.get("enabled"));
                if (!"true".equals(enabled) && !"false".equals(enabled)) {
                    return Validation.fail("État manquant : choisir « activée » ou « désactivée ».");
                }
                params.put("npc_id", npcId);
                params.put("enabled", enabled);
                if ("false".equals(enabled)) {
                    // Désactiver ne demande ni ancre ni zone : on ne transmet rien d'autre, pour ne
                    // pas laisser croire qu'un réglage a été enregistré au passage. Seule exception
                    // (issue #325) : revenir au point de départ en s'arrêtant.
                    if ("true".equals(trim(form.get("return_home")))) {
                        params.put("return_home", "true");
                    }
                    break;
                }
                Integer xRange = positiveInt(form.get("x_range"));
                if (xRange == null || xRange > WANDER_MAX_X_RANGE) {
                    return Validation.fail("Rayon horizontal manquant ou hors bornes (1 à "
                            + WANDER_MAX_X_RANGE + " blocs).");
                }
                Integer yRange = nonNegativeInt(form.get("y_range"));
                if (yRange == null || yRange > WANDER_MAX_Y_RANGE) {
                    return Validation.fail("Amplitude verticale manquante ou hors bornes (0 à "
                            + WANDER_MAX_Y_RANGE + " blocs).");
                }
                params.put("x_range", Integer.toString(xRange));
                params.put("y_range", Integer.toString(yRange));

                String world = trim(form.get("world"));
                Double x = finite(form.get("x"));
                Double y = finite(form.get("y"));
                Double z = finite(form.get("z"));
                boolean any = !world.isEmpty() || x != null || y != null || z != null;
                boolean full = !world.isEmpty() && x != null && y != null && z != null;
                if (any && !full) {
                    return Validation.fail("Ancre incomplète : monde, X, Y et Z vont ensemble. "
                            + "Tout laisser vide reprend la position actuelle du PNJ.");
                }
                if (full) {
                    if (!WORLD_NAME.matcher(world).matches()) {
                        return Validation.fail("Monde de l'ancre invalide.");
                    }
                    if (Math.abs(x) > HORIZONTAL_LIMIT || Math.abs(z) > HORIZONTAL_LIMIT) {
                        return Validation.fail("Ancre hors du bord de monde (±"
                                + (long) HORIZONTAL_LIMIT + ").");
                    }
                    if (y < Y_MIN || y > Y_MAX) {
                        return Validation.fail("Ancre : Y hors bornes de sécurité ("
                                + (long) Y_MIN + " à " + (long) Y_MAX + ").");
                    }
                    params.put("world", world);
                    params.put("x", trimNumber(x));
                    params.put("y", trimNumber(y));
                    params.put("z", trimNumber(z));
                }
                if ("true".equals(trim(form.get("confirm_replace")))) {
                    params.put("confirm_replace", "true");
                }
            }
            case "npc.citizens.rename" -> {
                String npcId = trim(form.get("npc_id")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(npcId).matches()) {
                    return Validation.fail("Identifiant de PNJ manquant ou invalide.");
                }
                String name = trim(form.get("name"));
                if (name.isEmpty() || name.length() > 48) {
                    return Validation.fail("Nom en jeu manquant, ou trop long (48 caractères maximum).");
                }
                params.put("npc_id", npcId);
                params.put("name", name);
            }
            // Issue #197 — même forme que le renommage Citizens, parce que c'est le même nom : la
            // borne de 48 caractères est celle que Citizens impose, et la définition RPGQuest s'y
            // conforme. Un seul champ côté écran, donc un seul contrôle ici.
            case "npc.identity.rename" -> {
                String npcId = trim(form.get("npc_id")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(npcId).matches()) {
                    return Validation.fail("Identifiant de PNJ manquant ou invalide.");
                }
                String name = trim(form.get("name"));
                if (name.isEmpty() || name.length() > 48) {
                    return Validation.fail("Nom du PNJ manquant, ou trop long (48 caractères maximum).");
                }
                params.put("npc_id", npcId);
                params.put("name", name);
            }
            case "npc.citizens.skin" -> {
                String npcId = trim(form.get("npc_id")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(npcId).matches()) {
                    return Validation.fail("Identifiant de PNJ manquant ou invalide.");
                }
                // Deux sources, et seulement deux : un lien MineSkin, ou un pseudo Minecraft que
                // Citizens résout lui-même. Jamais une commande libre, jamais une autre URL.
                // Tout est revalidé côté plugin — le navigateur n'est pas une source de confiance.
                String source = trim(form.get("skin_source")).toLowerCase(java.util.Locale.ROOT);
                if (source.isEmpty()) {
                    source = "url";
                }
                if (!source.equals("url") && !source.equals("player")) {
                    return Validation.fail("Source de skin inconnue (lien MineSkin ou pseudo Minecraft).");
                }
                params.put("npc_id", npcId);
                params.put("skin_source", source);
                if (source.equals("player")) {
                    String player = trim(form.get("skin_player"));
                    if (!MINECRAFT_NAME.matcher(player).matches()) {
                        return Validation.fail("Pseudo Minecraft invalide : 3 à 16 caractères, "
                                + "lettres, chiffres et « _ » uniquement.");
                    }
                    params.put("skin_player", player);
                } else {
                    String url = trim(form.get("skin_url"));
                    String problem = mineSkinProblem(url);
                    if (problem != null) {
                        return Validation.fail(problem);
                    }
                    params.put("skin_url", url);
                }
            }
            case "npc.citizens.provision" -> {
                String displayName = trim(form.get("display_name"));
                if (displayName.isEmpty() || displayName.length() > 128 || displayName.indexOf('\n') >= 0) {
                    return Validation.fail("Nom du PNJ manquant, trop long (128 maximum) ou multi-ligne.");
                }
                params.put("display_name", displayName);
                // Issue #325 : identifiant retenu par le panel et dialogue initial (optionnels).
                String wantedId = trim(form.get("npc_id")).toLowerCase(java.util.Locale.ROOT);
                if (!wantedId.isEmpty()) {
                    if (!NPC_ID.matcher(wantedId).matches()) {
                        return Validation.fail("Identifiant de PNJ invalide.");
                    }
                    params.put("npc_id", wantedId);
                }
                String initialDialogue = trim(form.get("dialogue_id")).toLowerCase(java.util.Locale.ROOT);
                if (!initialDialogue.isEmpty()) {
                    if (!DIALOGUE_REF.matcher(initialDialogue).matches()) {
                        return Validation.fail("Dialogue initial invalide.");
                    }
                    params.put("dialogue_id", initialDialogue);
                }
                String requestToken = trim(form.get("request_token"));
                if (!requestToken.isEmpty()) {
                    if (!REQUEST_TOKEN.matcher(requestToken).matches()) {
                        return Validation.fail("Jeton de demande invalide.");
                    }
                    params.put("request_token", requestToken);
                }

                // Skin optionnel. Une source renseignée sans valeur, ou l'inverse, est refusée :
                // mieux vaut un message clair qu'une création au skin par défaut inattendu.
                String source = trim(form.get("skin_source")).toLowerCase(java.util.Locale.ROOT);
                String skinUrl = trim(form.get("skin_url"));
                String skinPlayer = trim(form.get("skin_player"));
                if (source.equals("player")) {
                    if (!skinPlayer.isEmpty()) {
                        if (!MINECRAFT_NAME.matcher(skinPlayer).matches()) {
                            return Validation.fail("Pseudo Minecraft invalide : 3 à 16 caractères, "
                                    + "lettres, chiffres et « _ » uniquement.");
                        }
                        params.put("skin_source", "player");
                        params.put("skin_player", skinPlayer);
                    }
                } else if (!skinUrl.isEmpty()) {
                    String problem = mineSkinProblem(skinUrl);
                    if (problem != null) {
                        return Validation.fail(problem);
                    }
                    params.put("skin_source", "url");
                    params.put("skin_url", skinUrl);
                }

                // Localisation optionnelle, mais jamais partielle : monde ET X/Y/Z ensemble.
                String world = trim(form.get("world"));
                String rawX = trim(form.get("x"));
                String rawY = trim(form.get("y"));
                String rawZ = trim(form.get("z"));
                boolean anyLocation = !world.isEmpty() || !rawX.isEmpty() || !rawY.isEmpty() || !rawZ.isEmpty();
                boolean allLocation = !world.isEmpty() && !rawX.isEmpty() && !rawY.isEmpty() && !rawZ.isEmpty();
                if (anyLocation && !allLocation) {
                    return Validation.fail("Localisation incomplète : renseigner le monde ET X, Y, Z "
                            + "ensemble, ou laisser les quatre champs vides pour un placement "
                            + "automatique près du Guide.");
                }
                if (allLocation) {
                    if (!WORLD_NAME.matcher(world).matches()) {
                        return Validation.fail("Nom de monde invalide.");
                    }
                    Double x = finite(rawX);
                    Double y = finite(rawY);
                    Double z = finite(rawZ);
                    if (x == null || y == null || z == null) {
                        return Validation.fail("Coordonnées X / Y / Z non numériques.");
                    }
                    if (Math.abs(x) > HORIZONTAL_LIMIT || Math.abs(z) > HORIZONTAL_LIMIT) {
                        return Validation.fail("X / Z hors du bord de monde (±" + (long) HORIZONTAL_LIMIT + ").");
                    }
                    if (y < Y_MIN || y > Y_MAX) {
                        return Validation.fail("Y hors bornes de sécurité (" + (long) Y_MIN + " à "
                                + (long) Y_MAX + ").");
                    }
                    params.put("world", world);
                    params.put("x", trimNumber(x));
                    params.put("y", trimNumber(y));
                    params.put("z", trimNumber(z));
                }

                // Orientation optionnelle : absente = 0 / 0, documenté dans le formulaire.
                String rawYaw = trim(form.get("yaw"));
                String rawPitch = trim(form.get("pitch"));
                if (!rawYaw.isEmpty()) {
                    Double yawValue = finite(rawYaw);
                    if (yawValue == null) {
                        return Validation.fail("Yaw non numérique.");
                    }
                    params.put("yaw", trimNumber(yawValue));
                }
                if (!rawPitch.isEmpty()) {
                    Double pitchValue = finite(rawPitch);
                    if (pitchValue == null || pitchValue < -90.0 || pitchValue > 90.0) {
                        return Validation.fail("Pitch manquant ou hors bornes (-90 à 90).");
                    }
                    params.put("pitch", trimNumber(pitchValue));
                }
            }
            case "npc.citizens.create" -> {
                String npcId = trim(form.get("npc_id")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(npcId).matches()) {
                    return Validation.fail("Identifiant de PNJ manquant ou invalide.");
                }
                String world = trim(form.get("world"));
                if (!WORLD_NAME.matcher(world).matches()) {
                    return Validation.fail("Nom de monde manquant ou invalide.");
                }
                Double x = finite(form.get("x"));
                Double y = finite(form.get("y"));
                Double z = finite(form.get("z"));
                if (x == null || y == null || z == null) {
                    return Validation.fail("Coordonnées X / Y / Z manquantes ou non numériques.");
                }
                if (Math.abs(x) > HORIZONTAL_LIMIT || Math.abs(z) > HORIZONTAL_LIMIT) {
                    return Validation.fail("X / Z hors du bord de monde (±" + (long) HORIZONTAL_LIMIT + ").");
                }
                if (y < Y_MIN || y > Y_MAX) {
                    return Validation.fail("Y hors bornes de sécurité (" + (long) Y_MIN + " à " + (long) Y_MAX + ").");
                }
                Double yaw = form.getOrDefault("yaw", "").isBlank() ? Double.valueOf(0.0) : finite(form.get("yaw"));
                Double pitch = form.getOrDefault("pitch", "").isBlank() ? Double.valueOf(0.0) : finite(form.get("pitch"));
                if (yaw == null || pitch == null) {
                    return Validation.fail("Orientation yaw / pitch non numérique.");
                }
                if (pitch < -90.0 || pitch > 90.0) {
                    return Validation.fail("Pitch hors bornes (-90 à 90).");
                }
                params.put("npc_id", npcId);
                params.put("world", world);
                params.put("x", trimNumber(x));
                params.put("y", trimNumber(y));
                params.put("z", trimNumber(z));
                params.put("yaw", trimNumber(yaw));
                params.put("pitch", trimNumber(pitch));
            }
            case "dialogue.definition.create" -> {
                String key = trim(form.get("key")).toLowerCase(java.util.Locale.ROOT);
                if (!NPC_ID.matcher(key).matches()) {
                    return Validation.fail("Clé de dialogue manquante ou invalide (minuscules, « . _ - »).");
                }
                String speaker = trim(form.get("speaker"));
                if (speaker.isEmpty() || speaker.length() > 128 || speaker.indexOf('\n') >= 0) {
                    return Validation.fail("Locuteur manquant, trop long, ou multi-ligne.");
                }
                String text = trim(form.get("text"));
                if (text.isEmpty() || text.length() > 512 || text.indexOf('\n') >= 0) {
                    return Validation.fail("Texte du nœud manquant, trop long (max 512), ou multi-ligne.");
                }
                // Palette de couleurs (issue #118) : on n'enrobe que si l'utilisateur n'a pas déjà
                // saisi du MiniMessage — son texte avancé reste intact.
                String color = trim(form.get("text_color")).toLowerCase(java.util.Locale.ROOT);
                if (!color.isEmpty()) {
                    if (!PALETTE_COLORS.contains(color)) {
                        return Validation.fail("Couleur de texte non reconnue.");
                    }
                    if (text.indexOf('<') < 0) {
                        text = "<" + color + ">" + text + "</" + color + ">";
                        if (text.length() > 512) {
                            return Validation.fail("Texte trop long une fois la couleur appliquée (max 512).");
                        }
                    }
                }
                params.put("key", key);
                params.put("speaker", speaker);
                params.put("text", text);
            }
            case "dialogue.node.create", "dialogue.node.update" -> {
                String dialogueId = normalizeDialogueId(trim(form.get("dialogue_id")));
                if (dialogueId == null) {
                    return Validation.fail("Identifiant de dialogue manquant ou invalide (« namespace:clé » ou une clé simple).");
                }
                String nodeId = trim(form.get("node_id")).toLowerCase(java.util.Locale.ROOT);
                if (!DIALOGUE_NODE_ID.matcher(nodeId).matches()) {
                    return Validation.fail("Identifiant de nœud manquant ou invalide (minuscules, chiffres, « _ - », max 64).");
                }
                String speaker = trim(form.get("speaker"));
                if (speaker.isEmpty() || speaker.length() > 128 || speaker.indexOf('\n') >= 0) {
                    return Validation.fail("Locuteur manquant, trop long, ou multi-ligne.");
                }
                String text = trim(form.get("text"));
                if (text.isEmpty() || text.length() > MAX_DIALOGUE_TEXT || text.indexOf('\n') >= 0) {
                    return Validation.fail("Texte du nœud manquant, trop long (max " + MAX_DIALOGUE_TEXT + "), ou multi-ligne.");
                }
                params.put("dialogue_id", dialogueId);
                params.put("node_id", nodeId);
                params.put("speaker", speaker);
                params.put("text", text);
            }
            case "dialogue.choice.add", "dialogue.choice.update" -> {
                String dialogueId = normalizeDialogueId(trim(form.get("dialogue_id")));
                if (dialogueId == null) {
                    return Validation.fail("Identifiant de dialogue manquant ou invalide.");
                }
                String nodeId = trim(form.get("node_id")).toLowerCase(java.util.Locale.ROOT);
                if (!DIALOGUE_NODE_ID.matcher(nodeId).matches()) {
                    return Validation.fail("Nœud source manquant ou invalide.");
                }
                String choiceText = trim(form.get("choice_text"));
                if (choiceText.isEmpty() || choiceText.length() > MAX_DIALOGUE_TEXT || choiceText.indexOf('\n') >= 0) {
                    return Validation.fail("Texte du choix manquant, trop long (max " + MAX_DIALOGUE_TEXT + "), ou multi-ligne.");
                }
                boolean close = "true".equals(trim(form.get("close")));
                String next = trim(form.get("next_node_id")).toLowerCase(java.util.Locale.ROOT);
                if (close) {
                    if (!next.isEmpty()) {
                        return Validation.fail("Un choix « fermeture » ne cible pas de nœud — laisser « nœud cible » vide.");
                    }
                } else {
                    if (!DIALOGUE_NODE_ID.matcher(next).matches()) {
                        return Validation.fail("Choisir un nœud cible OU cocher « termine le dialogue ».");
                    }
                }
                params.put("dialogue_id", dialogueId);
                params.put("node_id", nodeId);
                params.put("choice_text", choiceText);
                if (close) {
                    params.put("close", "true");
                } else {
                    params.put("next_node_id", next);
                }
                if ("dialogue.choice.update".equals(type)) {
                    Integer idx = parseIndex(trim(form.get("choice_index")));
                    if (idx == null) {
                        return Validation.fail("Index de choix manquant ou hors bornes.");
                    }
                    params.put("choice_index", idx.toString());
                    Validation structured = dialogueChoiceStructuredParams(form, params);
                    if (structured != null) {
                        return structured;
                    }
                }
            }
            case "dialogue.choice.delete" -> {
                String dialogueId = normalizeDialogueId(trim(form.get("dialogue_id")));
                if (dialogueId == null) {
                    return Validation.fail("Identifiant de dialogue manquant ou invalide.");
                }
                String nodeId = trim(form.get("node_id")).toLowerCase(java.util.Locale.ROOT);
                if (!DIALOGUE_NODE_ID.matcher(nodeId).matches()) {
                    return Validation.fail("Nœud source manquant ou invalide.");
                }
                Integer idx = parseIndex(trim(form.get("choice_index")));
                if (idx == null) {
                    return Validation.fail("Index de choix manquant ou hors bornes.");
                }
                params.put("dialogue_id", dialogueId);
                params.put("node_id", nodeId);
                params.put("choice_index", idx.toString());
            }
            case "content.export" -> {
                String family = orDefault(trim(form.get("family")).toLowerCase(java.util.Locale.ROOT), "all");
                if (!CONTENT_FAMILIES.contains(family)) {
                    return Validation.fail("Famille de contenu inconnue (all, quests, stories, dialogues, npcs).");
                }
                params.put("family", family);
                String rawIds = trim(form.get("ids"));
                if (!rawIds.isEmpty()) {
                    if ("all".equals(family)) {
                        return Validation.fail("Une sélection d'identifiants n'est possible que pour une famille précise.");
                    }
                    java.util.List<String> ids = new java.util.ArrayList<>();
                    for (String piece : rawIds.split("[,\\s]+")) {
                        String t = piece.trim();
                        if (t.isEmpty()) {
                            continue;
                        }
                        if (!RESOURCE_ID.matcher(t).matches()) {
                            return Validation.fail("Identifiant à exporter invalide : « " + safe(t) + " ».");
                        }
                        ids.add(t);
                        if (ids.size() > MAX_EXPORT_IDS) {
                            return Validation.fail("Trop d'identifiants (max " + MAX_EXPORT_IDS + ") — exporter la famille entière.");
                        }
                    }
                    if (!ids.isEmpty()) {
                        params.put("ids", String.join(",", ids));
                    }
                }
            }
            case "mob.definition.create", "mob.definition.update" -> {
                String mobId = trim(form.get("mob_id")).toLowerCase(java.util.Locale.ROOT);
                if (!MOB_ID.matcher(mobId).matches()) {
                    return Validation.fail("Identifiant de profil manquant ou invalide (minuscules, « . _ - »).");
                }
                String category = trim(form.get("category")).toUpperCase(java.util.Locale.ROOT);
                if (!MOB_CATEGORIES.contains(category)) {
                    return Validation.fail("Catégorie manquante ou invalide (SPECIAL ou BOSS).");
                }
                String entityType = trim(form.get("entity_type")).toUpperCase(java.util.Locale.ROOT);
                if (entityType.isEmpty() || !entityType.matches("[A-Z_]{1,64}")) {
                    return Validation.fail("Type d'entité manquant ou invalide.");
                }
                String displayName = trim(form.get("display_name"));
                if (displayName.isEmpty() || displayName.length() > 128 || displayName.indexOf('\n') >= 0) {
                    return Validation.fail("Nom affiché manquant, trop long, ou multi-ligne.");
                }
                Double spawnChance = finite(form.get("spawn_chance"));
                if (spawnChance == null || spawnChance < 0 || spawnChance > 1) {
                    return Validation.fail("« Chance d'apparition » manquante ou hors bornes (0 à 1).");
                }
                params.put("mob_id", mobId);
                params.put("category", category);
                params.put("entity_type", entityType);
                params.put("display_name", displayName);
                params.put("spawn_chance", trimNumber(spawnChance));
                params.put("enabled", "true".equals(trim(form.get("enabled"))) ? "true" : "false");
                copyOptionalList(form, params, "worlds");
                copyOptionalList(form, params, "biomes");
                copyOptionalList(form, params, "zones");
                for (String key : new String[] {"health", "damage", "speed", "armor", "knockback_resistance",
                        "scale", "creeper_explosion_radius"}) {
                    Double v = finite(form.get(key));
                    if (v != null) {
                        params.put(key, trimNumber(v));
                    }
                }
                String particle = trim(form.get("particle")).toUpperCase(java.util.Locale.ROOT);
                if (!particle.isEmpty()) {
                    params.put("particle", particle);
                }
                String sound = trim(form.get("sound")).toUpperCase(java.util.Locale.ROOT);
                if (!sound.isEmpty()) {
                    params.put("sound", sound);
                }
                for (String key : new String[] {"xp_reward", "max_population"}) {
                    String raw = trim(form.get(key));
                    if (!raw.isEmpty()) {
                        try {
                            params.put(key, Integer.toString(Integer.parseInt(raw)));
                        } catch (NumberFormatException e) {
                            return Validation.fail("« " + key + " » doit être un entier.");
                        }
                    }
                }
                // Capacités du premier lot (issue #171) : toutes les clés d'une capacité ensemble, ou aucune.
                if ("true".equals(trim(form.get("enraged_enabled")))) {
                    Double hf = finite(form.get("enraged_health_fraction"));
                    Double sm = finite(form.get("enraged_speed_multiplier"));
                    Double dm = finite(form.get("enraged_damage_multiplier"));
                    if (hf == null || hf <= 0 || hf >= 1 || sm == null || sm <= 0 || dm == null || dm <= 0) {
                        return Validation.fail("Paramètres « Enragé » manquants ou invalides.");
                    }
                    params.put("enraged_health_fraction", trimNumber(hf));
                    params.put("enraged_speed_multiplier", trimNumber(sm));
                    params.put("enraged_damage_multiplier", trimNumber(dm));
                }
                if ("true".equals(trim(form.get("summon_enabled")))) {
                    String summonType = trim(form.get("summon_entity_type")).toUpperCase(java.util.Locale.ROOT);
                    Double chance = finite(form.get("summon_chance"));
                    String amountRaw = trim(form.get("summon_amount"));
                    String cooldownRaw = trim(form.get("summon_cooldown_seconds"));
                    String maxAliveRaw = trim(form.get("summon_max_alive"));
                    if (summonType.isEmpty() || chance == null || chance <= 0 || chance > 1
                            || amountRaw.isEmpty() || cooldownRaw.isEmpty() || maxAliveRaw.isEmpty()) {
                        return Validation.fail("Paramètres « Invocation de renforts » manquants ou invalides.");
                    }
                    try {
                        params.put("summon_amount", Integer.toString(Integer.parseInt(amountRaw)));
                        params.put("summon_cooldown_seconds", Integer.toString(Integer.parseInt(cooldownRaw)));
                        params.put("summon_max_alive", Integer.toString(Integer.parseInt(maxAliveRaw)));
                    } catch (NumberFormatException e) {
                        return Validation.fail("Paramètres « Invocation de renforts » non numériques.");
                    }
                    params.put("summon_entity_type", summonType);
                    params.put("summon_chance", trimNumber(chance));
                }
            }
            case "mob.definition.toggle" -> {
                String mobId = trim(form.get("mob_id")).toLowerCase(java.util.Locale.ROOT);
                if (!MOB_ID.matcher(mobId).matches()) {
                    return Validation.fail("Identifiant de profil manquant ou invalide.");
                }
                params.put("mob_id", mobId);
                params.put("enabled", "true".equals(trim(form.get("enabled"))) ? "true" : "false");
            }
            case "mob.spawn-settings.set" -> {
                Double chance = finite(form.get("chance"));
                if (chance == null || chance < 0 || chance > 1) {
                    return Validation.fail("Throttle global manquant ou hors bornes (0 à 1).");
                }
                params.put("enabled", "true".equals(trim(form.get("enabled"))) ? "true" : "false");
                params.put("chance", trimNumber(chance));
                String maxRaw = trim(form.get("max_simultaneous_special"));
                if (!maxRaw.isEmpty()) {
                    try {
                        params.put("max_simultaneous_special", Integer.toString(Integer.parseInt(maxRaw)));
                    } catch (NumberFormatException e) {
                        return Validation.fail("Plafond simultané doit être un entier.");
                    }
                }
            }
            case "mob.test.spawn" -> {
                String mobId = trim(form.get("mob_id")).toLowerCase(java.util.Locale.ROOT);
                if (!MOB_ID.matcher(mobId).matches()) {
                    return Validation.fail("Identifiant de profil manquant ou invalide.");
                }
                params.put("mob_id", mobId);
            }
            case "server.announce" -> {
                String message = form.getOrDefault("message", "").strip();
                if (message.isEmpty()) {
                    return Validation.fail("Message d'annonce vide.");
                }
                if (message.length() > MAX_ANNOUNCE_CHARS) {
                    return Validation.fail("Annonce trop longue (" + message.length()
                            + " caractères, maximum " + MAX_ANNOUNCE_CHARS + ").");
                }
                if (message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0) {
                    return Validation.fail("Annonce multi-ligne refusée.");
                }
                if (message.startsWith("/")) {
                    // Le serveur refuse déjà ce cas ; le refuser ici évite un aller-retour et dit
                    // clairement que ce champ n'est pas une console.
                    return Validation.fail("Une annonce est un texte, pas une commande : "
                            + "retirer le « / » initial.");
                }
                String channel = orDefault(trim(form.get("channel")).toLowerCase(java.util.Locale.ROOT), "chat");
                if (!ANNOUNCE_CHANNELS.contains(channel)) {
                    return Validation.fail("Canal d'annonce inconnu (chat, actionbar, title).");
                }
                params.put("message", message);
                params.put("channel", channel);
            }
            case "content.reload", "content.reload.preview" -> {
                // Deux formes acceptées, et c'est délibéré :
                //  * « families » en CSV — utilisé par les actions contextuelles des pages de
                //    contenu, où les familles liées sont connues d'avance ;
                //  * « family_<nom>=true » — une case à cocher par famille. Des cases partageant le
                //    MÊME nom ne fonctionneraient pas : le parseur de formulaire du panel ne garde
                //    qu'une valeur par clé, donc cocher trois familles n'en transmettrait qu'une.
                //    Des noms distincts rendent le formulaire correct SANS JavaScript.
                java.util.List<String> families = new java.util.ArrayList<>();
                String raw = trim(form.get("families"));
                if (!raw.isEmpty()) {
                    for (String token : raw.split(",")) {
                        String family = token.trim().toLowerCase(java.util.Locale.ROOT);
                        if (family.isEmpty()) {
                            continue;
                        }
                        if (!RELOAD_FAMILIES.contains(family)) {
                            // Jamais une interprétation partielle : l'administrateur croirait avoir
                            // rechargé plus que ce qui l'a été.
                            return Validation.fail("Famille de contenu inconnue : « " + safe(family) + " ».");
                        }
                        if (!families.contains(family)) {
                            families.add(family);
                        }
                    }
                }
                for (String family : RELOAD_FAMILIES) {
                    if ("true".equals(trim(form.get("family_" + family))) && !families.contains(family)) {
                        families.add(family);
                    }
                }
                if (families.isEmpty()) {
                    return Validation.fail("Sélectionner au moins une famille de contenu à recharger.");
                }
                // Réordonné selon les dépendances, quel que soit l'ordre des cases cochées.
                java.util.List<String> ordered = new java.util.ArrayList<>();
                for (String family : RELOAD_FAMILIES) {
                    if (families.contains(family)) {
                        ordered.add(family);
                    }
                }
                params.put("families", String.join(",", ordered));
            }
            case "server.logs.tail" -> {
                String after = trim(form.get("after"));
                if (!after.isEmpty()) {
                    long value;
                    try {
                        value = Long.parseLong(after);
                    } catch (NumberFormatException e) {
                        return Validation.fail("Curseur de console invalide.");
                    }
                    if (value < 0) {
                        return Validation.fail("Curseur de console négatif.");
                    }
                    params.put("after", Long.toString(value));
                }
                String logLimit = trim(form.get("limit"));
                if (!logLimit.isEmpty()) {
                    int value;
                    try {
                        value = Integer.parseInt(logLimit);
                    } catch (NumberFormatException e) {
                        return Validation.fail("Nombre de lignes invalide.");
                    }
                    if (value < 1 || value > 500) {
                        return Validation.fail("Nombre de lignes hors bornes (1 à 500).");
                    }
                    params.put("limit", Integer.toString(value));
                }
            }
            case "player.resetnew.confirm", "player.resetfull.confirm" -> params.put("confirm", "true");
            case "player.op", "player.deop" -> {
                // Issue #210, consigne du 12/10/2026 : UNE case à cocher (« confirm », vérifiée
                // plus haut comme pour toute mutation sensible), rien à retaper. L'ancienne saisie
                // comparait le pseudo tapé à l'UUID de la cible et échouait toujours. La raison
                // devient facultative ; à défaut, l'audit porte un motif explicite.
                String reason = trim(form.get("reason"));
                if (reason.length() > 200 || reason.indexOf('\n') >= 0) {
                    return Validation.fail("Raison trop longue (max 200) ou multi-ligne.");
                }
                params.put("reason", reason.isEmpty()
                        ? ("player.op".equals(type) ? "OP" : "DEOP") + " depuis PlugAdmin (sans motif saisi)" : reason);
            }
            case "settings.set" -> {
                for (String key : SETTINGS_KEYS) {
                    String v = form.get(key);
                    if (v != null) {
                        params.put(key, v.trim());
                    }
                }
                if (params.isEmpty()) {
                    return Validation.fail("Aucun paramètre à modifier.");
                }
                String cur = params.get("currency_name");
                if (cur != null && (cur.isEmpty() || cur.length() > 24 || !cur.matches("[\\p{L}\\p{N} '’.-]+"))) {
                    return Validation.fail("Nom de la monnaie : 1 à 24 lettres, chiffres ou espaces.");
                }
                String on = params.get("beta_warning_enabled");
                if (on != null && !"true".equals(on) && !"false".equals(on)) {
                    return Validation.fail("Avertissement bêta : activé ou désactivé.");
                }
                String text = params.get("beta_warning_text");
                if (text != null && (text.isEmpty() || text.length() > 500 || text.indexOf('<') >= 0)) {
                    return Validation.fail("Texte de l'avertissement : 1 à 500 caractères, sans balise.");
                }
                String bypassOn = params.get("admin_bypass_available");
                if (bypassOn != null && !"true".equals(bypassOn) && !"false".equals(bypassOn)) {
                    return Validation.fail("Mode bypass administrateur : proposé ou désactivé.");
                }
                String bypassMinutes = params.get("admin_bypass_max_minutes");
                if (bypassMinutes != null && (!bypassMinutes.matches("\\d{1,3}") || Integer.parseInt(bypassMinutes) > 480)) {
                    return Validation.fail("Durée maximale du mode bypass : 0 à 480 minutes.");
                }
                String actor = trim(form.get("actor"));
                if (!actor.isEmpty()) {
                    params.put("actor", actor.length() > 64 ? actor.substring(0, 64) : actor);
                }
            }
            case "economy.credit", "economy.debit" -> {
                long value;
                try {
                    value = Long.parseLong(trim(form.get("amount")));
                } catch (NumberFormatException e) {
                    return Validation.fail("Montant invalide : saisir un entier positif.");
                }
                if (value <= 0) {
                    return Validation.fail("Le montant doit être strictement positif.");
                }
                if (value > MAX_ECONOMY_AMOUNT) {
                    // Garde-fou de SAISIE, pas une règle d'équilibrage : une faute de frappe à six
                    // zéros ne doit pas pouvoir créer une fortune en un clic.
                    return Validation.fail("Montant trop élevé en une seule opération (maximum "
                            + MAX_ECONOMY_AMOUNT + ").");
                }
                String reason = trim(form.get("reason"));
                if (reason.isEmpty()) {
                    return Validation.fail("Une raison est obligatoire : elle est enregistrée dans "
                            + "le journal des transactions.");
                }
                if (reason.length() > 200 || reason.indexOf('\n') >= 0) {
                    return Validation.fail("Raison trop longue (max 200) ou multi-ligne.");
                }
                params.put("amount", Long.toString(value));
                params.put("reason", reason);
            }
            case "economy.balance" -> {
                String historyLimit = trim(form.get("history"));
                if (!historyLimit.isEmpty()) {
                    int n;
                    try {
                        n = Integer.parseInt(historyLimit);
                    } catch (NumberFormatException e) {
                        return Validation.fail("Nombre de lignes de journal invalide.");
                    }
                    if (n < 1 || n > 100) {
                        return Validation.fail("Nombre de lignes hors bornes (1 à 100).");
                    }
                    params.put("history", Integer.toString(n));
                }
            }
            case "economy.debts" -> {
                String limit = trim(form.get("limit"));
                if (!limit.isEmpty()) {
                    int n;
                    try {
                        n = Integer.parseInt(limit);
                    } catch (NumberFormatException e) {
                        return Validation.fail("Nombre de récompenses dues invalide.");
                    }
                    if (n < 1 || n > 100) {
                        return Validation.fail("Nombre de récompenses dues hors bornes (1 à 100).");
                    }
                    params.put("limit", Integer.toString(n));
                }
            }
            case "economy.debt.retry" -> {
                String grant = trim(form.get("grant"));
                if (grant.isEmpty()) {
                    return Validation.fail("Identifiant de récompense due manquant.");
                }
                if (grant.length() > 128) {
                    return Validation.fail("Identifiant de récompense due invalide (trop long).");
                }
                // AUCUN montant ici, volontairement : une reprise paie ce qui a été enregistré à la
                // complétion. Laisser saisir un montant permettrait d'en inventer un.
                params.put("grant", grant);
            }
            case "economy.debt.settle" -> {
                String grant = trim(form.get("grant"));
                if (grant.isEmpty()) {
                    return Validation.fail("Identifiant de récompense due manquant.");
                }
                if (grant.length() > 128) {
                    return Validation.fail("Identifiant de récompense due invalide (trop long).");
                }
                String reason = trim(form.get("reason"));
                if (reason.isEmpty()) {
                    return Validation.fail("Une raison est obligatoire : elle est conservée avec la "
                            + "récompense réglée, et c'est la seule trace de la compensation.");
                }
                if (reason.length() > 200 || reason.indexOf('\n') >= 0) {
                    return Validation.fail("Raison trop longue (max 200) ou multi-ligne.");
                }
                params.put("grant", grant);
                params.put("reason", reason);
            }
            case "mc.rights.read" -> {
                // Aucun paramètre au-delà du joueur : c'est un relevé.
            }
            case "mc.group.sync" -> {
                String group = trim(form.get("group"));
                if (group.isEmpty()) {
                    return Validation.fail("Identifiant de groupe manquant.");
                }
                params.put("group", group);
                String label = trim(form.get("label"));
                if (!label.isEmpty()) {
                    params.put("label", label);
                }
                // Un nom DISTINCT par droit : Http.formBody ne garde qu'une valeur par clé, donc
                // des champs homonymes perdraient tous leurs droits sauf un.
                int index = 0;
                for (int i = 0; i < 64; i++) {
                    String node = trim(form.get("node" + i));
                    if (node.isEmpty()) {
                        continue;
                    }
                    params.put("node" + index, node);
                    String world = trim(form.get("world" + i));
                    if (!world.isEmpty()) {
                        params.put("world" + index, world);
                    }
                    index++;
                }
            }
            case "mc.group.delete" -> {
                String group = trim(form.get("group"));
                if (group.isEmpty()) {
                    return Validation.fail("Identifiant de groupe manquant.");
                }
                params.put("group", group);
            }
            case "mc.rights.sync" -> {
                int index = 0;
                for (int i = 0; i < 64; i++) {
                    String group = trim(form.get("group" + i));
                    if (group.isEmpty()) {
                        continue;
                    }
                    params.put("group" + index, group);
                    index++;
                }
                // Zéro groupe est LÉGITIME : c'est la révocation complète d'un joueur.
            }
            case "player.kick" -> {
                String reason = trim(form.get("reason"));
                if (reason.isEmpty()) {
                    return Validation.fail("Une raison est obligatoire pour expulser un joueur.");
                }
                if (reason.length() > 200 || reason.indexOf('\n') >= 0) {
                    return Validation.fail("Raison trop longue (max 200) ou multi-ligne.");
                }
                params.put("reason", reason);
            }
            case "player.ban" -> {
                String reason = trim(form.get("reason"));
                if (reason.isEmpty()) {
                    return Validation.fail("Une raison de bannissement est obligatoire.");
                }
                if (reason.length() > 256 || reason.indexOf('\n') >= 0) {
                    return Validation.fail("Raison trop longue (max 256) ou multi-ligne.");
                }
                params.put("reason", reason);
            }
            case "player.catalog" -> {
                String limit = trim(form.get("limit"));
                if (!limit.isEmpty()) {
                    int n;
                    try {
                        n = Integer.parseInt(limit);
                    } catch (NumberFormatException e) {
                        return Validation.fail("Limite invalide.");
                    }
                    if (n < 1 || n > 20_000) {
                        return Validation.fail("Limite hors bornes (1 à 20000).");
                    }
                    params.put("limit", Integer.toString(n));
                }
            }
            default -> {
                // player.list / *.player.status / *.list / player.reset*.preview : pas de paramètre
                // supplémentaire au-delà de « player ».
            }
        }
        return Validation.ok(params);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Issue #13 étape 3 — champs d'un modèle de PNJ récurrent : contrôle de forme, puis listes
     * (biomes, mondes) normalisées en CSV quel que soit le séparateur saisi (virgule, espace, ligne).
     *
     * @return message d'erreur, ou {@code null} si le formulaire peut partir
     */
    private static String encounterTemplateParams(Map<String, String> form, Map<String, String> params) {
        String id = trim(form.get("id")).toLowerCase(java.util.Locale.ROOT);
        if (!NPC_ID.matcher(id).matches()) {
            return "Identifiant de modèle manquant ou invalide (minuscules, chiffres, « . _ - »).";
        }
        params.put("id", id);
        String displayName = trim(form.get("display_name"));
        if (displayName.isEmpty() || displayName.length() > 64 || displayName.indexOf('<') >= 0
                || displayName.indexOf('\n') >= 0) {
            return "Nom affiché obligatoire : texte simple de 64 caractères au plus, sans balise.";
        }
        params.put("display_name", displayName);
        String description = trim(form.get("description"));
        if (description.length() > 256 || description.indexOf('\n') >= 0) {
            return "Description trop longue (256 caractères) ou sur plusieurs lignes.";
        }
        String npcId = trim(form.get("npc_id")).toLowerCase(java.util.Locale.ROOT);
        if (!npcId.isEmpty() && !NPC_ID.matcher(npcId).matches()) {
            return "Identité de PNJ (npc_id) invalide.";
        }
        for (String label : List.of("role", "family")) {
            String value = trim(form.get(label)).toLowerCase(java.util.Locale.ROOT);
            if (!value.isEmpty() && !NPC_ROLE.matcher(value).matches()) {
                return "« " + label + " » invalide (minuscules, chiffres, « _ - », 32 au plus).";
            }
            if (!value.isEmpty()) {
                params.put(label, value);
            }
        }
        List<String> biomes = splitWords(form.get("biomes")).stream()
                .map(v -> v.toLowerCase(java.util.Locale.ROOT)).toList();
        if (biomes.isEmpty() || !biomes.stream().allMatch(b -> ENCOUNTER_BIOME.matcher(b).matches())) {
            return "Biomes : au moins un, sous la forme « forest » ou « minecraft:forest ».";
        }
        List<String> worlds = splitWords(form.get("worlds"));
        if (!worlds.stream().allMatch(w -> ENCOUNTER_WORLD.matcher(w).matches())) {
            return "Mondes : noms de mondes séparés par des virgules (vide = tous les mondes autorisés).";
        }
        for (String key : List.of("weight", "max_sites")) {
            String value = trim(form.get(key));
            if (!value.isEmpty() && !value.matches("\\d{1,6}")) {
                return "« " + key + " » doit être un entier positif.";
            }
            if (!value.isEmpty()) {
                params.put(key, value);
            }
        }
        String skin = trim(form.get("skin_url"));
        if (!skin.isEmpty() && !skin.startsWith("https://minesk.in/")) {
            return "Apparence : lien MineSkin attendu (https://minesk.in/…).";
        }
        if (!description.isEmpty()) {
            params.put("description", description);
        }
        if (!npcId.isEmpty()) {
            params.put("npc_id", npcId);
        }
        params.put("biomes", String.join(",", biomes));
        params.put("worlds", String.join(",", worlds));
        if (!skin.isEmpty()) {
            params.put("skin_url", skin);
        }
        List<String> catalogs = splitWords(form.get("quest_catalogs")).stream()
                .map(v -> v.toLowerCase(java.util.Locale.ROOT)).toList();
        if (!catalogs.stream().allMatch(c -> NPC_ID.matcher(c).matches())) {
            return "Catalogues de quêtes : identifiants séparés par des virgules.";
        }
        params.put("quest_catalogs", String.join(",", catalogs));
        params.put("enabled", "true".equals(trim(form.get("enabled"))) ? "true" : "false");
        return null;
    }

    private static final java.util.regex.Pattern QUEST_REF =
            java.util.regex.Pattern.compile("([a-z0-9._-]{1,64}:)?[a-z0-9._/-]{1,128}");

    /**
     * Issue #369 — champs d'un catalogue. Les lignes d'entrées arrivent numérotées
     * ({@code entry_<n>_quest}, {@code _weight}, {@code _enabled}, {@code _order}, {@code _remove}) ;
     * elles sont triées par ordre saisi puis par position, les lignes vides ou retirées ignorées,
     * et transmises en {@code quête|poids|on/off} séparés par des virgules.
     *
     * @return message d'erreur, ou {@code null} si le formulaire peut partir
     */
    static String questCatalogParams(Map<String, String> form, Map<String, String> params) {
        String id = trim(form.get("id")).toLowerCase(java.util.Locale.ROOT);
        if (!NPC_ID.matcher(id).matches()) {
            return "Identifiant de catalogue manquant ou invalide (minuscules, chiffres, « . _ - »).";
        }
        params.put("id", id);
        String displayName = trim(form.get("display_name"));
        if (displayName.isEmpty() || displayName.length() > 64 || displayName.indexOf('<') >= 0
                || displayName.indexOf('\n') >= 0) {
            return "Nom obligatoire : texte simple de 64 caractères au plus, sans balise.";
        }
        params.put("display_name", displayName);
        String description = trim(form.get("description"));
        if (description.length() > 256 || description.indexOf('\n') >= 0) {
            return "Description trop longue (256 caractères) ou sur plusieurs lignes.";
        }
        if (!description.isEmpty()) {
            params.put("description", description);
        }
        String avoid = trim(form.get("avoid_recent"));
        if (!avoid.isEmpty() && !avoid.matches("\\d{1,2}")) {
            return "« Éviter les dernières quêtes » doit être un entier entre 0 et 10.";
        }
        if (!avoid.isEmpty()) {
            params.put("avoid_recent", avoid);
        }
        params.put("enabled", "true".equals(trim(form.get("enabled"))) ? "true" : "false");

        record Row(int order, int position, String value) {
        }
        List<Row> rows = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int n = 0; n < 200; n++) {
            String prefix = "entry_" + n + "_";
            if (!form.containsKey(prefix + "quest")) {
                continue;
            }
            String quest = trim(form.get(prefix + "quest")).toLowerCase(java.util.Locale.ROOT);
            // « Titre — rpgquest:id » (choix dans la liste de recherche) : seul l'identifiant compte.
            int dash = quest.lastIndexOf(' ');
            if (dash >= 0) {
                quest = quest.substring(dash + 1);
            }
            if (quest.isEmpty() || "true".equals(trim(form.get(prefix + "remove")))) {
                continue;
            }
            if (!QUEST_REF.matcher(quest).matches()) {
                return "Quête invalide « " + quest + " » : choisissez-la dans la liste (titre ou identifiant).";
            }
            if (!seen.add(quest.contains(":") ? quest : "rpgquest:" + quest)) {
                return "La quête « " + quest + " » figure deux fois dans le catalogue.";
            }
            String weight = trim(form.get(prefix + "weight"));
            if (!weight.isEmpty() && !weight.matches("\\d{1,4}")) {
                return "Poids invalide pour « " + quest + " » : entier de 1 à 1000.";
            }
            String order = trim(form.get(prefix + "order"));
            int orderValue = order.matches("-?\\d{1,4}") ? Integer.parseInt(order) : n;
            String enabled = "true".equals(trim(form.get(prefix + "enabled"))) ? "on" : "off";
            rows.add(new Row(orderValue, n, quest + "|" + weight + "|" + enabled));
        }
        rows.sort(java.util.Comparator.comparingInt(Row::order).thenComparingInt(Row::position));
        params.put("entries", String.join(",", rows.stream().map(Row::value).toList()));
        return null;
    }

    private static List<String> splitWords(String raw) {
        return java.util.Arrays.stream(trim(raw).split("[,\\s]+")).filter(v -> !v.isBlank()).toList();
    }

    /** Copie une liste texte virgule (formulaire : une valeur par ligne, jointe côté page en CSV) si non vide. */
    private static void copyOptionalList(Map<String, String> form, Map<String, String> params, String key) {
        String raw = trim(form.get(key));
        if (!raw.isEmpty()) {
            params.put(key, raw);
        }
    }

    /** {@code namespace:clé} minuscule, ou {@code null} si invalide. Une clé simple reçoit {@code rpgquest:}. */
    private static String normalizeDialogueId(String raw) {
        String value = trim(raw).toLowerCase(java.util.Locale.ROOT);
        if (value.isEmpty() || !DIALOGUE_REF.matcher(value).matches()) {
            return null;
        }
        return value.contains(":") ? value : "rpgquest:" + value;
    }

    /**
     * Valeurs acceptées par {@code quest_action} / {@code quest_condition} de
     * {@code dialogue.choice.update}. {@code keep} (ou l'absence du champ) laisse la propriété
     * intacte côté serveur — c'est le défaut qui garantit qu'un formulaire partiel ne simplifie
     * jamais un choix.
     */
    private static final java.util.Set<String> QUEST_ACTION_MODES =
            java.util.Set.of("keep", "none", "start_quest", "advance_quest", "turn_in_quest");
    private static final java.util.Set<String> QUEST_STATE_MODES =
            java.util.Set.of("keep", "none", "not_started", "active", "ready_to_turn_in", "completed",
                    "failed", "abandoned");

    /**
     * Valide la partie « édition structurée » de {@code dialogue.choice.update} (action de quête et
     * condition d'état de quête) et l'ajoute à {@code params}. Retourne {@code null} si tout va
     * bien, un {@link Validation} en échec sinon.
     */
    private static Validation dialogueChoiceStructuredParams(Map<String, String> form, Map<String, String> params) {
        String questAction = trim(form.get("quest_action")).toLowerCase(java.util.Locale.ROOT);
        if (!questAction.isEmpty()) {
            if (!QUEST_ACTION_MODES.contains(questAction)) {
                return Validation.fail("Action de quête inconnue (keep, none, start_quest, advance_quest, turn_in_quest).");
            }
            params.put("quest_action", questAction);
            if (!questAction.equals("keep") && !questAction.equals("none")) {
                String questId = normalizeDialogueId(trim(form.get("quest_id")));
                if (questId == null) {
                    return Validation.fail("Quête à démarrer / avancer / rendre manquante ou invalide.");
                }
                params.put("quest_id", questId);
            }
        }
        String questCondition = trim(form.get("quest_condition")).toLowerCase(java.util.Locale.ROOT);
        if (!questCondition.isEmpty()) {
            if (!QUEST_STATE_MODES.contains(questCondition)) {
                return Validation.fail("État de quête inconnu pour la condition du choix.");
            }
            params.put("quest_condition", questCondition);
            if (!questCondition.equals("keep") && !questCondition.equals("none")) {
                String questId = normalizeDialogueId(trim(form.get("condition_quest_id")));
                if (questId == null) {
                    return Validation.fail("Quête de la condition d'état manquante ou invalide.");
                }
                params.put("condition_quest_id", questId);
                if ("true".equals(trim(form.get("condition_negate")))) {
                    params.put("condition_negate", "true");
                }
            }
        }
        return null;
    }

    private static Integer parseIndex(String raw) {
        try {
            int v = Integer.parseInt(raw);
            return (v < 0 || v > MAX_DIALOGUE_CHOICE_INDEX) ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parse un décimal fini, ou {@code null} (vide / non numérique / NaN / Infinity). */
    private static Double finite(String value) {
        String t = trim(value);
        if (t.isEmpty()) {
            return null;
        }
        try {
            double v = Double.parseDouble(t);
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Entier strictement positif, ou {@code null} si absent / illisible / négatif. */
    private static Integer positiveInt(String value) {
        Integer v = nonNegativeInt(value);
        return v == null || v == 0 ? null : v;
    }

    /** Entier {@code >= 0}, ou {@code null} si absent ou illisible. */
    private static Integer nonNegativeInt(String value) {
        String t = trim(value);
        if (t.isEmpty()) {
            return null;
        }
        try {
            int v = Integer.parseInt(t);
            return v < 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Sérialise un décimal : entier si rond (« 64 »), sinon décimal court (« 125.5 »). */
    private static String trimNumber(double v) {
        return v == Math.rint(v) && Double.isFinite(v) ? Long.toString((long) v) : Double.toString(v);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String safe(String raw) {
        if (raw == null) {
            return "—";
        }
        String t = raw.strip();
        return t.length() > 48 ? t.substring(0, 48) + "…" : t;
    }
}
