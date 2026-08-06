# Architecture

## Arborescence des packages (prévue)

    be.lloyd.rpgquest
    ├── RPGQuestPlugin       (point d'entrée, délègue tout à bootstrap)
    ├── bootstrap            (cycle de vie : PluginService, registre, orchestration)
    ├── command
    ├── config               (chargement + validation de config.yml)
    ├── database
    ├── dialogue             (interface du futur moteur, non implémenté)
    ├── item                 (interface du futur registre d'objets, non implémenté)
    ├── player
    ├── quest                (définitions YAML implémentées ; progression à venir)
    │   └── model            (modèles immuables : quête, étapes, objectifs, récompenses)
    ├── resource
    ├── ui                   (interface de la future UI, non implémenté)
    └── util                 (vide pour l'instant : utilitaires génériques uniquement)

Tous les packages listés existent désormais. `resource` reste à créer (pas
encore requis) ; `dialogue`, `item` et `ui` ne contiennent toujours qu'une
interface marqueur `extends PluginService`, sans implémentation. `quest`
contient désormais un chargeur de définitions complet (voir ci-dessous) ;
seule la **progression** des joueurs (avancement d'étape, octroi réel des
récompenses) reste à construire.

### `bootstrap` (cycle de vie du plugin)

-   `PluginService` — contrat minimal `start()`/`stop()` (+ `name()` par
    défaut) commun à tous les services et aux futurs moteurs.
-   `PluginServiceRegistry` — pas de séparation entre « enregistrement » et
    « démarrage » : `start(PluginService)` démarre le service immédiatement
    et le retient. Cela élimine toute ambiguïté sur l'ordre : l'ordre de
    démarrage *est* l'ordre d'appel du code (garanti par l'exécution séquentielle
    Java), aucune liste à maintenir séparément. `stopAll()` arrête les
    services réellement démarrés dans l'ordre inverse (LIFO) et continue
    même si l'un d'eux échoue à s'arrêter (log de l'erreur, pas d'arrêt
    prématuré des autres). Si un `start()` échoue, tous les services déjà
    démarrés sont arrêtés (rollback) avant que l'exception ne soit propagée
    — jamais de plugin à moitié initialisé.
-   `RPGQuestBootstrap` — construit et orchestre les services dans
    l'ordre où ils sont réellement nécessaires : `ConfigService` (les autres
    en dépendent) → `DatabaseService` (a besoin de `config.database.file`,
    lu à l'intérieur de son propre `start()`, jamais avant) →
    `PlayerListenerService`. `RPGQuestPlugin.onEnable()` se contente de créer
    un `RPGQuestBootstrap` et d'appeler `start()`/`stop()` ; toute la logique
    de câblage vit dans `bootstrap`.

### `config` (validation, indépendant de JavaPlugin pour la partie validation)

-   `ConfigValidator` — fonction statique pure `validate(ConfigurationSection)
    -> PluginConfig`, qui ne dépend que du type Bukkit `ConfigurationSection`
    (pas de `JavaPlugin`) : testable en JUnit pur via
    `YamlConfiguration.loadConfiguration(...)`, sans MockBukkit. Chaque champ
    invalide lève une `ConfigValidationException` avec un message précis
    (valeur trouvée, règle violée).
-   `PluginConfig` / `ResourcePackConfig` — records immuables portant le
    résultat validé (`debug`, `locale`, `databaseFile`, `resourcePack`).
-   `ConfigService` (implémente `PluginService`) — couche Paper : appelle
    `saveDefaultConfig()`/`getConfig()`/`reloadConfig()` puis délègue à
    `ConfigValidator`. Détient l'unique état mutable de cette classe :
    `current` (le `PluginConfig` actif). C'est une mutation **justifiée** et
    non un singleton global — `ConfigService` est une instance normale créée
    par `RPGQuestBootstrap` (une par instance de plugin, jamais `static`), et
    cette mutabilité est le mécanisme même du rechargement à chaud demandé.
    -   `start()` : échec de validation → `IllegalStateException` (non
        vérifiée) qui remonte à travers `PluginServiceRegistry` (rollback
        des autres services) jusqu'à `RPGQuestPlugin.onEnable()`, qui logue
        un message clair et appelle `disablePlugin` — pas de crash opaque
        même si `config.yml` est invalide dès le premier démarrage.
    -   `reload()` : échec de validation → `ConfigValidationException`
        (vérifiée) propagée telle quelle à l'appelant ; `current` n'est
        **jamais** réassigné avant la validation complète, donc la
        configuration précédente reste active telle quelle en cas de refus.
        `/rpgquest reload` (commande, permission `rpgquest.admin`) attrape
        cette exception et affiche le message tel quel au joueur/à la
        console, sans toucher aux services ni aux données existantes.
    -   Le rechargement lit un petit fichier YAML de façon synchrone, sur
        déclenchement explicite d'un administrateur — comme
        `plugin.reloadConfig()` partout dans l'écosystème Bukkit. Ce n'est
        pas une exception à la règle « aucune requête disque sur le thread
        principal », qui vise spécifiquement SQLite (voir plus bas).

### `database` (indépendant de Bukkit/Paper)

-   `DatabaseManager` — possède l'unique `Connection` JDBC SQLite et sérialise
    tous les accès sur un `ExecutorService` mono-thread dédié (thread daemon
    `RPGQuest-Database`). `initialize()` crée le dossier de données, ouvre la
    connexion, active `PRAGMA foreign_keys` et applique les migrations.
    `execute(SqlFunction<T>)` exécute une action JDBC sur ce thread et
    retourne un `CompletableFuture<T>` — jamais bloquant pour l'appelant.
    Comme l'executor est mono-thread et FIFO, toute requête soumise avant la
    fin de `initialize()` est simplement mise en file et s'exécute après la
    migration : aucune synchronisation explicite n'est nécessaire pour
    garantir « schéma prêt avant requêtes ».
-   `SchemaMigrator` — version de schéma suivie via `PRAGMA user_version`
    (SQLite natif, pas de table dédiée). `migrate()` est idempotent : rejouée
    sur une base déjà à jour, elle ne fait rien (et les `CREATE TABLE IF NOT
    EXISTS` protègent en plus contre toute erreur si le mécanisme de version
    était contourné).
-   `PlayerProfileRepository` / `PlayerVariableRepository` — requêtes
    préparées uniquement (`PreparedStatement`), aucune concaténation de
    valeurs dans le SQL. Types 100% JDK (`UUID`, `Instant`, `Optional`) : ces
    classes ne dépendent d'aucun type Bukkit/Paper et sont testées en JUnit
    pur, sans MockBukkit.
-   Tables : `player_profiles(uuid, last_name, created_at, updated_at)`,
    `player_variables(player_uuid, variable_key, variable_value)` (clé
    primaire composite, upsert via `ON CONFLICT`), `quest_progress(...)`
    créée mais sans repository — préparée pour une étape ultérieure.
-   **Fermeture** : `DatabaseManager.shutdown()` est volontairement
    bloquant (jusqu'à 5 s) — c'est le seul point du code où l'on attend
    délibérément le thread base de données, et uniquement depuis
    `onDisable()`, quand plus aucune requête de gameplay ne peut être
    déclenchée. Ce n'est pas une exception à la règle « aucun accès disque
    sur le thread principal », qui vise le fonctionnement normal du serveur.
-   `DatabaseService` (implémente `PluginService`) — adapte `DatabaseManager`
    au cycle de vie du plugin. Ne construit le `DatabaseManager` qu'à
    l'intérieur de `start()` (jamais dans le constructeur), pour lire
    `configService.current().databaseFile()` uniquement une fois
    `ConfigService` réellement démarré — c'est cette dépendance concrète qui
    justifie l'ordre de démarrage imposé par `RPGQuestBootstrap`.

### `player` (dépendant de Bukkit/Paper)

-   `PlayerProfileService` — cache `ConcurrentHashMap<UUID, PlayerProfile>`
    peuplé à la connexion (`loadOnJoin`) et vidé à la déconnexion
    (`invalidate`) : sa taille est donc bornée naturellement par le nombre de
    joueurs connectés (cache « limité » au sens de la consigne). `getOrLoad`
    sert le cache si présent, sinon interroge la base (joueur hors-ligne).
-   `PlayerConnectionListener` — `PlayerJoinEvent`/`PlayerQuitEvent`. Les
    callbacks qui touchent l'API Bukkit (log, futur message au joueur) sont
    systématiquement renvoyés sur le thread principal via
    `Bukkit.getScheduler().runTask(...)`, jamais exécutés depuis le thread
    base de données.
-   `PlayerListenerService` (implémente `PluginService`) — enregistre le
    listener dans `start()` et l'appelle `HandlerList.unregisterAll(...)`
    dans `stop()`. Générique sur `Listener` (pas seulement
    `PlayerConnectionListener`), réutilisable pour de futurs listeners.
    `/rpgquest reload` ne touche que `ConfigService` : ce service n'est
    jamais redémarré par un rechargement, donc le listener n'est jamais
    recréé, conformément à la consigne.
-   `/rpgquest profile [joueur]` — résolution du pseudo hors-ligne
    (`Server#getOfflinePlayer(String)`, dépréciée car potentiellement
    bloquante) déportée sur `runTaskAsynchronously` ; la réponse est
    formatée et envoyée uniquement après un retour sur le thread principal.

### `quest` (indépendant de Bukkit/Paper sauf `Material`/`EntityType`/`NamespacedKey`)

-   **Modèles (`quest.model`)** — records immuables. `QuestDefinition`,
    `QuestStep` copient défensivement leurs listes/maps (`List.copyOf`,
    `Map.copyOf`) dans leur constructeur canonique compact, et valident leurs
    propres invariants (ex. une étape sans objectif est rejetée par
    `QuestStep` lui-même, pas seulement par le parseur) : « correct par
    construction », pas juste « non modifiable ».
    -   `QuestObjective` / `QuestReward` sont des **interfaces scellées**
        (Java 21 `sealed`) avec un type par variante (`BreakBlockObjective`,
        `KillEntityObjective`, ... / `ExperienceReward`, `ItemReward`, ...) :
        le compilateur garantit qu'un `switch` sur les 7 types d'objectifs ou
        les 4 types de récompenses est exhaustif, sans `default` fourre-tout.
    -   `LocalizedText` porte un texte MiniMessage par code de langue avec
        une clé `default` obligatoire. La résolution selon la langue active
        du joueur (`config.yml` → `locale`) n'est **pas** câblée à ce stade :
        seule la donnée est portée, la même limite déjà documentée pour
        `locale` dans les décisions techniques.
    -   Identifiants **namespacés** via `org.bukkit.NamespacedKey` (pas un
        type maison) : `id: first_steps` est résolu dans le namespace par
        défaut `rpgquest`, `id: monpack:first_steps` référence un autre
        namespace. Comme `ConfigurationSection`, c'est un type Bukkit léger,
        sans dépendance à un serveur vivant — testable en JUnit pur.
-   **`QuestDefinitionParser`** (package-privé) — valide **un seul fichier**
    à la fois, uniquement à partir de `ConfigurationSection` : aucune
    dépendance à `JavaPlugin`, testable sans MockBukkit. Contrairement à
    `ConfigValidator` (qui lève une exception à la première erreur, un choix
    délibéré puisqu'il n'y a qu'un seul `config.yml` et qu'un rechargement
    doit soit pleinement réussir, soit ne rien changer), le parseur de
    quêtes **accumule toutes les erreurs structurelles** trouvées dans un
    fichier (champs manquants, types inconnus, nombres négatifs, étape sans
    objectif, etc.) avant de renvoyer un `ParseResult` en échec — un seul
    rechargement suffit à voir tous les problèmes d'un fichier donné,
    plutôt que de les corriger un par un. `Material.matchMaterial(...)` et
    `EntityType.fromName(...)` (API Paper publique, résolution tolérante des
    noms) sont utilisés pour valider matériaux/entités sans lister
    manuellement les valeurs correctes.
-   **`QuestLoader`** — orchestre le chargement multi-fichiers en deux
    phases : (1) chaque fichier est parsé indépendamment via
    `QuestDefinitionParser` — un fichier en erreur est exclu, mais n'empêche
    jamais le chargement des autres ; (2) une fois tous les fichiers parsés
    individuellement, une passe de validation croisée rejette les **id
    dupliqués entre fichiers** (les deux fichiers concernés sont rejetés,
    impossible de choisir un « gagnant » arbitraire) puis, en boucle à point
    fixe, les quêtes dont un **prérequis ne résout vers aucune quête
    effectivement survivante** (gère les rejets en cascade : si B est
    rejetée, toute quête C qui dépend de B est rejetée à son tour).
    `loadDirectory(Path)` scanne le dossier réel (fichier YAML syntaxiquement
    invalide → `InvalidConfigurationException` capturée et transformée en
    `QuestLoadIssue` lisible, pas de plantage) ; `load(Map<String,
    ConfigurationSection>)` est l'entrée pure utilisée par les tests.
-   **`YamlQuestEngine`** (implémente `QuestEngine`, donc `PluginService`) —
    couche Paper : lit/écrit dans `plugins/RPGQuest/quests/`. Au premier
    démarrage (`start()`), génère les deux quêtes d'exemple embarquées dans
    le jar (`getResourceAsStream`, jamais si le fichier cible existe déjà —
    ne jamais écraser une quête que l'opérateur a modifiée), puis charge le
    dossier. `reload()` remplace l'ensemble de quêtes actif ; `validate()`
    fait le même chargement mais **sans** toucher à l'ensemble actif
    (dry-run explicite demandé par la mission, distinct de `reload`).
-   **`/quest admin reload|validate`** (`rpgquest.admin`, commande dédiée
    `/quest`, distincte de `/rpgquest`) — affiche un rapport (nombre chargé,
    liste des erreurs avec fichier + message). Aucune des deux commandes ne
    touche aux autres services (base de données, profils) : recharger les
    quêtes ne recrée ni ne redémarre rien d'autre.

## Flux principal (cible)

Dialogue → Acceptation de quête → Progression → Récolte / Combat →
Fabrication → Remise → Récompense

## Décisions techniques

-   **Java 21** (toolchain Gradle), imposé par Paper 1.21.11.
-   **Paper 1.21.11** (`io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`).
    Paper est passé à un nouveau schéma de version (CalVer, ex. `26.2.build.98-stable`)
    à partir des versions Minecraft 26.1+. La version 1.21.11 reste disponible sous
    l'ancien format `{VERSION}-R0.1-SNAPSHOT` sur le dépôt Maven officiel
    (`https://repo.papermc.io/repository/maven-public/`) : aucune incompatibilité
    documentée, donc aucun changement de version n'a été effectué.
-   **Gradle 9.6.1** avec Kotlin DSL, wrapper committé.
-   **Plugin `xyz.jpenilla.run-paper` 3.0.2** pour la tâche `runServer`.
-   **Descripteur de plugin : `plugin.yml`** (et non le manifeste `paper-plugin.yml`).
    La documentation officielle PaperMC indique explicitement que le nouveau
    manifeste est encore expérimental et « not recommended » ; `plugin.yml`
    reste donc le format recommandé actuellement.
-   **Tests : JUnit 5 (`junit-jupiter` 5.14.4) + MockBukkit (`mockbukkit-v1.21` 4.110.0)**.
    `mockbukkit-v1.21` cible la branche 1.21.x et n'est pas versionné par patch ;
    aucune incompatibilité connue avec 1.21.11 à ce jour.
    MockBukkit instancie le plugin via un sous-classement dynamique (ByteBuddy) :
    la classe principale du plugin ne doit donc pas être `final`.
    `paper-api` étant en `compileOnly`, les configurations `testCompileOnly` et
    `testRuntimeOnly` héritent explicitement de `compileOnly` pour exposer les
    types Bukkit/Paper au classpath de test.
-   **Adventure / MiniMessage** pour tous les textes envoyés aux joueurs.
-   **PersistentDataContainer** prévu pour tous les objets personnalisés (aucun
    objet créé à ce stade).
-   **SQLite asynchrone** : `org.xerial:sqlite-jdbc:3.53.2.1`. Le driver
    n'est **pas** empaqueté dans le jar (pas de plugin Shadow) : il est
    déclaré dans `plugin.yml` (`libraries:`) et téléchargé/ajouté au
    classpath au démarrage par le `LibraryLoader` de Paper — vérifié en
    conditions réelles via `runServer` (`[SpigotLibraryLoader] Loaded
    library ... sqlite-jdbc-3.53.2.1.jar`). Il n'est ajouté en dépendance
    Gradle (`testImplementation`) que pour exécuter les tests JUnit hors
    serveur.
-   **`locale` (ISO 639-1)** validé via `Locale.getISOLanguages()` (liste
    JDK faisant autorité, pas de liste inventée). Le champ est stocké et
    exposé par `PluginConfig` mais **pas encore utilisé** ailleurs dans le
    code — aucun système d'i18n n'existe à ce stade ; câblage prévu pour une
    étape ultérieure.
-   **`debug`** est le seul champ de configuration réellement branché à un
    comportement observable pour l'instant : quand `true`, `/rpgquest
    version` affiche une ligne supplémentaire résumant la configuration
    active. Choisi précisément pour rendre le scénario de test manuel
    (« modifier debug, reload, vérifier l'application ») vérifiable sans
    dépendre d'une fonctionnalité de jeu qui n'existe pas encore.

## Limites connues

-   `DialogueEngine`, `CustomItemRegistry` et `QuestJournalUi` restent des
    interfaces marqueurs `extends PluginService`, sans aucune classe qui les
    implémente. `QuestEngine` a désormais une implémentation
    (`YamlQuestEngine`), mais **uniquement pour le chargement des
    définitions** : aucune progression de joueur n'existe encore (pas de
    suivi d'étape, pas d'octroi réel de récompense, pas d'exécution des
    `CommandReward`/`VariableReward`, pas de résolution de `TALK_TO_NPC` ou
    `REACH_LOCATION` contre un monde/PNJ réel). C'est le socle du moteur, pas
    le moteur complet — cohérent avec le nom de la mission (« moteur de
    **définitions** de quêtes »).
-   `locale` et `resource-pack` sont validés et stockés dans `PluginConfig`
    mais ne pilotent encore aucun comportement réel (pas d'i18n, pas d'envoi
    de resource pack aux joueurs) — prévu pour des étapes ultérieures
    dédiées.
-   `player_variables` a un repository (`get`/`set`) mais n'est exploité par
    aucune commande ni par les récompenses `VariableReward` pour l'instant ;
    `quest_progress` n'a même pas de repository, seulement sa table.
-   Sur la console Windows, les caractères accentués des logs (SLF4J) peuvent
    s'afficher incorrectement (`�`) selon la page de code active du terminal.
    Le contenu réel des chaînes est en UTF-8 et n'est pas affecté ; c'est un
    problème d'affichage console, pas un bug du plugin.
-   Test manuel limité par l'environnement : aucun client Minecraft réel
    n'est disponible ici pour simuler une vraie connexion/déconnexion de
    joueur. Vérifié à la place via la console (`/rpgquest profile`,
    `/rpgquest profile <nom>` sur un joueur jamais connecté) et inspection
    directe du fichier `data.db` généré (tables + `PRAGMA user_version`). Le
    scénario complet « connexion → déconnexion/reconnexion → redémarrage »
    reste à valider manuellement par un testeur humain avec un client.
