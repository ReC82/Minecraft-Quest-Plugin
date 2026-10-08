package com.lodygames.rpgquest.panel.authz;

/**
 * Permissions du Control Panel. Chaque module demande une permission explicite via
 * {@link PermissionService#can} — jamais un test {@code if role == …} dispersé. {@code OWNER}
 * obtient automatiquement toutes les valeurs de cette énumération ({@code EnumSet.allOf}), donc
 * aucune liste à maintenir permission par permission (issue #50).
 *
 * <p>Les permissions PlugAdmin sont <strong>totalement distinctes</strong> des permissions
 * Minecraft / Paper / LuckPerms : aucune correspondance automatique.</p>
 */
public enum Permission {
    DASHBOARD_VIEW,
    PLAYERS_READ,
    /** Modération d'un joueur (ban / unban). Jamais donnée à un rôle en lecture seule. */
    PLAYER_MODERATE,
    /** Accorder / retirer un droit de construction persistant à un joueur (issue #96 — dépend de #27). */
    PLAYER_BUILD_WRITE,
    NPC_READ,
    /** Consultation lecture seule du réseau de voyage : waypoints/bornes persistés (issue #152). */
    TRAVEL_READ,
    /** Consultation des profils de mob spécial/boss + throttle Wild (issue #169). */
    MOB_READ,
    /** Créer/modifier/activer-désactiver un profil de mob spécial/boss, régler le throttle Wild (issue #169). */
    MOB_WRITE,
    /** Faire apparaître/supprimer une instance de test d'un profil, jamais un mob ordinaire (issue #169). */
    MOB_TEST_SPAWN,
    NPC_WRITE,
    NPC_BIND_WRITE,
    NPC_SPAWN_WRITE,
    QUEST_GIVER_WRITE,
    DIALOGUE_READ,
    DIALOGUE_WRITE,
    QUEST_CONTENT_WRITE,
    STORY_CONTENT_WRITE,
    CONTENT_READ,
    /** Exporter le contenu déclaratif en content pack versionné (issue #108). Lecture — jamais d'écriture. */
    CONTENT_EXPORT,
    /**
     * Utiliser l'atelier IA (issue #146) : décrire une quête, lancer une génération, relire la
     * proposition. Permission <strong>dédiée</strong>, distincte de l'écriture de contenu : un appel
     * d'IA coûte de l'argent réel et part vers un tiers, ce qui n'est pas la même décision que
     * modifier un fichier local. L'enregistrement final exige en plus {@link #CONTENT_IMPORT}.
     */
    AI_USE,
    /**
     * Configurer les fournisseurs d'IA (issue #146) : clés API, modèles, URL de base, plafonds.
     * Réservée aux rôles d'administration — manipuler une clé d'API tierce n'est pas un geste
     * d'édition de contenu, et un éditeur n'a aucune raison d'y toucher.
     */
    AI_CONFIGURE,
    /**
     * Importer un content pack (issue #109). Permission <strong>dédiée</strong> : l'import écrit
     * dans la source, et peut remplacer plusieurs contenus d'un coup — ce n'est pas la même chose
     * qu'exporter, et pas la même chose qu'éditer un élément à la fois. Accordée aux rôles qui
     * écrivent déjà du contenu, jamais à un rôle de lecture ou de test.
     */
    CONTENT_IMPORT,
    /**
     * Supprimer une quête ou une story (issue #194). Permission <strong>dédiée</strong>, et
     * volontairement absente du rôle « Éditeur de contenu » : écrire et corriger du contenu est un
     * geste réversible, le détruire ne l'est pas de la même façon. Un éditeur peut vider ou
     * renommer ; seul un administrateur supprime.
     */
    CONTENT_DELETE,
    /**
     * Supprimer un PNJ depuis {@code /npcs} (issue #226) : définition logique, liaison Citizens, PNJ
     * Citizens physique, ou les trois.
     *
     * <p>Permission <strong>dédiée</strong>, et volontairement distincte de {@link #NPC_WRITE} et de
     * {@link #NPC_SPAWN_WRITE} : créer et corriger un PNJ est réversible, détruire une entité
     * Citizens ne l'est pas, et un PNJ supprimé par erreur emporte avec lui tout ce qui le
     * référençait. Même raisonnement que {@link #CONTENT_DELETE} pour les quêtes et les stories :
     * un éditeur peut désactiver ou renommer ; seul un administrateur supprime.</p>
     *
     * <p>Détruire le PNJ Citizens <em>physique</em> exige en plus {@link #NPC_SPAWN_WRITE} — c'est
     * l'inverse exact de sa création, et regrouper ne doit jamais accorder un droit que l'opérateur
     * n'a pas.</p>
     */
    NPC_DELETE,
    DOCS_READ,
    DIAGNOSTICS_READ,
    AUDIT_READ,
    ACTION_QUEST,
    ACTION_STORY,
    ACTION_VARIABLE_GET,
    ACTION_VARIABLE_SET,
    ACTION_PLAYER_RESET,
    ACTION_ITEM_GIVE,
    ACTION_CONTENT_RELOAD,
    DEV_MODULE,
    /**
     * Consulter le solde et le journal des transactions d'un joueur (issue #140). Lecture seule.
     *
     * <p>Séparée de {@link #ECONOMY_WRITE} : voir combien possède un joueur est utile au support,
     * lui en créer n'est pas le même geste.</p>
     */
    ECONOMY_READ,
    /**
     * Créditer ou débiter un joueur depuis le panel (issue #140).
     *
     * <p>Permission <strong>dédiée</strong>, parce qu'un crédit <em>crée de la monnaie</em>. Chaque
     * opération exige une raison, enregistrée dans le journal des transactions — sans elle, une
     * création administrative serait indiscernable d'un gain de jeu quelques mois plus tard.</p>
     */
    ECONOMY_WRITE,
    /**
     * Accorder ou retirer le statut <strong>OP Minecraft</strong> (issue #210).
     *
     * <p>Permission <strong>dédiée et volontairement la plus restreinte du panel</strong> :
     * réservée à {@code OWNER}, et <strong>pas</strong> accordée à {@code ADMIN}. Le ticket l'exige
     * explicitement — « ne pas accorder ce droit implicitement à tous les
     * administrateurs/modérateurs ». Une délégation reste possible, mais elle doit être une
     * décision explicite, pas un effet de bord du rôle d'administrateur.</p>
     *
     * <p>OP Minecraft n'est <strong>ni</strong> un rôle PlugAdmin, <strong>ni</strong> un droit de
     * construction par monde (#200), <strong>ni</strong> un bypass de gameplay (#35). Accorder OP ne
     * modifie aucun des trois.</p>
     */
    PLAYER_OP_WRITE,
    /**
     * Voir la page « Exploitation serveur » (issue #95) : état réel du serveur et de l'agent,
     * fraîcheur des données, historique des opérations. Lecture seule.
     */
    OPS_VIEW,
    /**
     * Envoyer une annonce globale aux joueurs (issue #95). Permission <strong>dédiée</strong> : une
     * annonce est visible par tout le monde, immédiatement, et ne peut pas être reprise.
     */
    OPS_ANNOUNCE,
    /**
     * Redémarrer le serveur (issue #95). Permission <strong>dédiée</strong> et volontairement la
     * plus rare : c'est la seule action du panel qui déconnecte tous les joueurs.
     */
    OPS_RESTART,
    /**
     * Lire la console récente du serveur (issue #95). Séparée de {@link #OPS_VIEW} : des lignes de
     * log peuvent contenir des pseudos, des coordonnées et des messages d'erreur internes — c'est
     * plus bavard que l'état synthétique.
     */
    OPS_LOGS,
    /**
     * Gérer les comptes PlugAdmin : créer un utilisateur, changer son rôle, l'activer / le
     * désactiver, consulter la page {@code /users} (issue #50). Couvre aussi, depuis l'issue #199,
     * la gestion des <strong>groupes</strong> et des appartenances — c'est la même surface
     * administrative, et la séparer donnerait l'illusion d'un cloisonnement qui n'existe pas :
     * créer un groupe, c'est distribuer des droits.
     *
     * <p>Réservée à {@code OWNER} par défaut ; jamais accordée à {@code ADMIN} sans décision
     * explicite. Un deuxième garde-fou existe indépendamment d'elle : on ne peut jamais accorder
     * une permission que l'on ne détient pas soi-même (voir {@code GroupDirectory}).</p>
     */
    USER_MANAGE;

    /**
     * Permission portant ce nom, ou vide si le nom est inconnu. Utilisé à la lecture d'un groupe en
     * base : une permission disparue du code ne doit ni faire échouer la lecture ni accorder quoi
     * que ce soit.
     */
    public static java.util.Optional<Permission> byNameOrNull(String name) {
        if (name == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Permission.valueOf(name.trim()));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }
}
