# Architecture

## Arborescence des packages (prévue)

    be.lloyd.rpgquest
    ├── RPGQuestPlugin       (point d'entrée, délègue tout à bootstrap)
    ├── bootstrap            (cycle de vie : PluginService, registre, orchestration)
    ├── command
    ├── config               (chargement + validation de config.yml)
    ├── database
    ├── dialogue             (définitions YAML + moteur de session, relié aux quêtes)
    │   ├── model           (modèles immuables : dialogue, nœuds, choix, conditions, actions)
    │   ├── render          (DialogueRenderer : ChatDialogueRenderer, PaperDialogRenderer)
    │   └── session         (état runtime mutable, sessions en mémoire, listeners)
    ├── item                 (interface du futur registre d'objets, non implémenté)
    ├── player
    ├── quest                (définitions YAML + progression des joueurs)
    │   ├── model            (modèles immuables : quête, étapes, objectifs, récompenses)
    │   └── progress         (état runtime mutable, index, listeners, commandes)
    ├── resource
    ├── ui                   (interface de la future UI, non implémenté)
    └── util                 (vide pour l'instant : utilitaires génériques uniquement)

Tous les packages listés existent désormais. `resource` reste à créer (pas
encore requis) ; `item` et `ui` ne contiennent toujours qu'une interface
marqueur `extends PluginService`, sans implémentation. `quest` et
`dialogue` couvrent maintenant chacun le chargement des définitions **et**
leur exécution en jeu (voir ci-dessous).

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
    `player_variables(player_uuid, variable_key, variable_value)`,
    `quest_progress(player_uuid, quest_id, state, progress_data, updated_at)`
    et `quest_objective_progress(player_uuid, quest_id, step_id,
    objective_index, progress)` (migration V2) — toutes avec clé primaire
    composite et upsert via `ON CONFLICT`. `QuestProgressRepository` couvre
    les deux dernières (voir section `quest.progress`).
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

### `quest.progress` (progression des joueurs)

-   **Immuable vs mutable, un choix délibéré** : `quest.model` reste
    entièrement immuable (définitions figées une fois chargées).
    `ActiveQuestProgress` (état runtime : étape courante, compteurs, état),
    lui, est **volontairement mutable** — c'est une donnée qui change à
    chaque événement de jeu ; la modéliser en immuable obligerait à
    reconstruire un objet à chaque incrément pour un bénéfice nul. Package-privé,
    jamais exposé hors de `quest.progress`.
-   **`QuestObjectiveIndex`** — construit une seule fois par ensemble de
    quêtes chargé (pas par événement, pas par joueur) : associe chaque
    critère « grossier » (`Material`, `EntityType`, id de PNJ, nom de monde
    pour `REACH_LOCATION`) à la liste des `ObjectiveRef` (quête + étape +
    index d'objectif) qui pourraient correspondre. Un événement de jeu ne
    consulte donc jamais l'ensemble des quêtes chargées, seulement le petit
    sous-ensemble indexé pour ce type de critère — c'est ce qui répond à
    « ne scanne pas toutes les quêtes à chaque événement ».
-   **Écouteurs conditionnels** — sept petites classes `Listener` (une par
    type d'objectif : `QuestBlockBreakListener`, `QuestEntityDeathListener`,
    ...), chacune ne délègue qu'à une seule méthode `handle*` de
    `QuestProgressEngine`. `QuestProgressEngine.rebuildIndexAndListeners()`
    n'enregistre (`registerEvents`) que les listeners dont l'index a au
    moins une entrée, et désenregistre (`HandlerList.unregisterAll`) ceux
    qui n'en ont plus — recalculé à **chaque** `/quest admin reload`,
    pas seulement au démarrage. Concrètement : si aucune quête chargée
    n'utilise `CRAFT_ITEM`, `CraftItemEvent` n'est même pas écouté — c'est
    le sens de « écoute uniquement les événements nécessaires aux objectifs
    actifs », particulièrement important pour `PlayerMoveEvent`
    (`REACH_LOCATION`), l'événement le plus fréquent du jeu.
    `QuestProgressConnectionListener` (join/quit → charge/vide le cache de
    progression) reste toujours enregistré, lui, indépendamment des quêtes
    chargées.
-   **Anti double-incrément** — avant d'incrémenter, on vérifie que le
    compteur actuel de cet objectif est strictement inférieur au montant
    requis ; une fois atteint, tout événement supplémentaire (ex. un 11ᵉ
    bloc cassé alors que 10 suffisaient) est ignoré pour cet objectif.
-   **Anti double-remise** — la bascule d'état en mémoire (`ACTIVE` →
    `READY_TO_TURN_IN` → `COMPLETED`, puis retrait de la quête du cache
    actif du joueur) est **synchrone**, effectuée avant tout appel
    asynchrone (persistance, octroi de récompenses). Un second événement
    arrivant avant même la fin de la persistance voit déjà l'état à jour en
    mémoire (ou l'entrée absente du cache) et ne redéclenche rien. La voie
    admin (`/quest complete`, `forceComplete`) applique la même garde
    en consultant en plus la base si rien n'est en mémoire (une quête
    remise puis évincée du cache ne doit pas repartir de `ACTIVE` par
    défaut — bug réel détecté et corrigé pendant cette étape, voir tests).
-   **Remise automatique** — aucune commande de remise n'existe pour les
    joueurs (seul `/quest complete`, explicitement outil de test admin,
    figure dans la liste demandée) : dès que le dernier objectif de la
    dernière étape est satisfait, la quête passe par `READY_TO_TURN_IN` puis
    `COMPLETED` dans le même appel, récompenses accordées immédiatement.
    `READY_TO_TURN_IN` existe et est persisté comme point d'extension pour
    une future étape (ex. remise conditionnée à un vrai dialogue de PNJ),
    mais n'est aujourd'hui jamais un état durable observable.
-   **Répétition contrôlée** — `accept()` consulte l'état persisté (pas
    seulement le cache mémoire) : `COMPLETED` + `repeatable: false` →
    refusé ; `COMPLETED` + `repeatable: true`, ou `ABANDONED`, ou absence de
    ligne → accepté, compteurs repartis de zéro.
-   **Prérequis appliqués à l'acceptation** — `QuestDefinition.prerequisites()`
    (déjà validés à l'existence au chargement, voir plus haut) sont
    désormais vérifiés un par un contre `quest_progress` : tous doivent être
    `COMPLETED` pour ce joueur, sinon `accept()` échoue en listant lesquels
    manquent.
-   **`TALK_TO_NPC` sans système de PNJ dédié** — `QuestNpcInteractListener`
    écoute `PlayerInteractEntityEvent` et compare le nom personnalisé
    (`Entity#customName()`, converti en texte brut via
    `PlainTextComponentSerializer`, aucune dépendance Citizens) de l'entité
    clic-droitée à l'id configuré dans l'objectif. N'importe quelle entité
    vivante renommée (à l'enclume, par exemple) peut donc servir de PNJ
    temporaire — limitation assumée, un vrai système de PNJ est hors
    périmètre de cette étape.
-   **Persistance** — `QuestProgressRepository` (`database`, requêtes
    préparées, `ON CONFLICT` pour les upserts) couvre `quest_progress`
    (état + étape courante) et la nouvelle table `quest_objective_progress`
    (migration V2, un compteur par `player_uuid, quest_id, step_id,
    objective_index`). `loadForPlayer()` recharge à la connexion l'étape et
    les compteurs de chaque quête `ACTIVE`/`READY_TO_TURN_IN` — c'est ce qui
    permet à un joueur de reprendre exactement là où il en était après une
    reconnexion en cours d'étape.

### `dialogue` (dialogues à embranchements, reliés aux quêtes)

-   **Modèles (`dialogue.model`)** — records immuables, même discipline que
    `quest.model` : `DialogueDefinition` (id, nœud de départ, `Map<String,
    DialogueNode>`), `DialogueNode` (locuteur, texte, ≥ 1 choix — validé par
    le record lui-même), `DialogueChoice` (texte, conditions, actions,
    `next` optionnel). Réutilise directement `quest.model.LocalizedText`
    (texte MiniMessage localisable) et `quest.model.QuestState` (pour
    `QuestStateCondition`) — pas de duplication de ces concepts.
    -   `DialogueCondition` / `DialogueAction` sont des **interfaces
        scellées** (comme `QuestObjective`/`QuestReward`) : un `switch`
        exhaustif sur les 4 conditions ou les 9 actions est vérifié par le
        compilateur.
    -   **Redirection vs fermeture vs ouverture d'un autre dialogue** :
        après exécution des actions d'un choix, trois issues sont
        possibles. Si les actions contiennent un `CLOSE` ou un
        `OPEN_DIALOGUE`, cette issue prend le pas (arrêt anticipé du
        traitement des actions restantes). Sinon, `next` (s'il est présent)
        redirige vers un autre nœud du même dialogue. Sans `next` ni action
        terminale, le dialogue se ferme par défaut — jamais de nœud
        « bloqué » sans issue possible.
-   **`DialogueDefinitionParser`** (package-privé, un seul fichier à la
    fois, accumule toutes les erreurs) — même conception que
    `QuestDefinitionParser`. Un dialogue = un fichier, donc les références
    `next` (vers un nœud du **même** dialogue) sont vérifiables directement
    ici, contrairement aux références `OPEN_DIALOGUE` (vers un **autre**
    dialogue), qui sont cross-fichier et donc du ressort du loader.
    `RUN_SAFE_COMMAND` est validé contre la liste blanche
    (`config.yml` → `dialogue.allowed-commands`) **au chargement**, pas à
    l'exécution : un fichier de dialogue référençant une commande non
    autorisée est rejeté avec un message explicite, avant même qu'un joueur
    ne puisse l'atteindre.
-   **`DialogueLoader`** — même structure à deux phases que `QuestLoader` :
    (1) chaque fichier parsé indépendamment, un fichier en erreur n'empêche
    pas les autres ; (2) validation croisée : id dupliqués entre fichiers
    (les deux rejetés), références `OPEN_DIALOGUE` vers un dialogue
    inexistant (rejet en cascade, boucle à point fixe comme les prérequis
    de quêtes), puis **détection de boucles** par parcours en profondeur
    (couleurs blanc/gris/noir) sur le graphe `OPEN_DIALOGUE` **entre
    dialogues** — tous les dialogues impliqués dans un cycle sont rejetés.
    Important : les redirections `next` **à l'intérieur d'un même
    dialogue** peuvent boucler librement (un menu « hub » qui revient sur
    lui-même après une question est un usage normal, pas un bug) — seul le
    graphe des dialogues qui s'ouvrent les uns les autres est contrôlé.
-   **Sécurité (`RUN_SAFE_COMMAND`)** — liste blanche portant sur le **nom**
    de la commande uniquement (premier mot, ex. `give`), vérifiée au
    chargement contre `config.yml` → `dialogue.allowed-commands`. La seule
    substitution effectuée à l'exécution est `%player%` (nom du joueur
    acteur) : ce n'est pas une exception à « aucun texte utilisateur ne
    doit être concaténé dans une commande console », car un pseudo
    Minecraft est déjà contraint par Mojang à `[a-zA-Z0-9_]{3,16}` — aucun
    caractère de contrôle shell/commande n'y est représentable. Aucune
    autre donnée (variable joueur, texte de choix, saisie libre) n'est
    jamais interpolée dans une commande : il n'existe d'ailleurs aucune
    saisie de texte libre joueur dans tout le système de dialogue (choix =
    sélection dans une liste fermée, jamais du texte tapé).
-   **`dialogue.render` — `DialogueRenderer` derrière une interface** :
    -   `PaperDialogRenderer` utilise l'API Dialog native de Paper
        (`io.papermc.paper.dialog.Dialog`, `DialogType.multiAction`,
        `DialogAction.customClick`). **Vérifié par inspection du bytecode
        de `paper-api-1.21.11`** : la classe `Dialog` porte l'annotation
        `@org.jetbrains.annotations.ApiStatus.Experimental` — donc pas
        « stable pour la version ciblée » au sens de la mission. Elle
        reste implémentée (et compile contre l'API réelle, signatures
        vérifiées une à une par inspection du jar) mais n'est **pas** le
        choix par défaut.
    -   `ChatDialogueRenderer` (par défaut, `config.yml` → `dialogue.renderer:
        chat`) affiche le dialogue en MiniMessage dans le chat, choix sous
        forme de lignes cliquables via `ClickEvent.callback(...)` — une API
        Adventure stable et générique (pas liée au système Dialog),
        fonctionnant avec n'importe quel client. C'est le renderer de
        secours demandé par la mission.
    -   Aucune dépendance de `dialogue.render` vers `dialogue.session` :
        les renderers ne connaissent que la petite interface
        `DialogueChoiceHandler` (un seul callback), implémentée par
        `DialogueSessionEngine`. Évite une dépendance circulaire entre
        packages tout en gardant les renderers totalement interchangeables.
-   **`dialogue.session.DialogueSessionEngine`** (implémente
    `PluginService` et `DialogueChoiceHandler`) — orchestre ouverture,
    évaluation des conditions, sélection des choix, exécution des actions.
    -   **Sessions en mémoire uniquement, jamais persistées** — choix
        délibéré, à la différence de `quest.progress` : un dialogue est une
        interaction courte et volatile (comme un inventaire vanilla, une
        fermeture de client ferme aussi l'inventaire ouvert). Une
        déconnexion en cours de dialogue vide simplement la session ; le
        joueur peut relancer le dialogue depuis le début à la reconnexion.
    -   **Conditions évaluées de façon asynchrone** : `QUEST_STATE`
        délègue à `QuestProgressEngine.stateOf(...)` (cache mémoire si la
        quête est active, sinon requête base), `VARIABLE_EQUALS` à
        `PlayerVariableRepository.get(...)` — toutes deux asynchrones,
        jamais de requête disque sur le thread principal. `HAS_ITEM` et
        `HAS_PERMISSION` sont synchrones (inventaire/permissions déjà en
        mémoire côté serveur) mais enveloppées dans un
        `CompletableFuture.completedFuture(...)` pour une évaluation
        uniforme (`allOf` sur la liste complète des conditions d'un choix).
        Chaque callback qui touche l'API Bukkit (rendu, exécution
        d'action) revient explicitement sur le thread principal via
        `Bukkit.getScheduler().runTask(...)` avant de s'exécuter.
    -   **Choix revalidés au clic, pas seulement à l'affichage** : la
        visibilité initiale ne garantit que l'affichage ; `onChoiceSelected`
        réévalue les conditions du choix cliqué avant d'exécuter quoi que
        ce soit — une condition qui change entre l'affichage et le clic
        (ex. quête acceptée entre-temps par une autre voie) est reprise en
        compte, pas contournable en rejouant un ancien clic.
    -   **La session elle-même protège contre les clics rejoués/étrangers** :
        chaque sélection est comparée au (dialogue, nœud) réellement en
        cours pour ce joueur ; un clic ne correspondant plus à la session
        actuelle est silencieusement ignoré. Comme chaque joueur ne peut
        agir que sur *sa propre* session (clé = son UUID), aucune
        permission dédiée n'est nécessaire pour la sélection d'un choix.
    -   **`ADVANCE_QUEST`/`TURN_IN_QUEST`** s'appuient sur deux méthodes
        ajoutées à `QuestProgressEngine` pendant cette étape :
        `advanceStep(...)` (satisfait manuellement les objectifs de
        l'étape courante, avance comme si le joueur les avait complétés en
        jeu) et la réutilisation de `forceComplete(...)` (déjà écrite pour
        `/quest complete`, dont la javadoc a été élargie : c'est désormais
        aussi un chemin joueur légitime, pas seulement un outil de test
        admin).
-   **PNJ partagé avec les quêtes** — `DialogueNpcInteractListener` (clic
    sur une entité) réutilise exactement la même convention que
    `QuestNpcInteractListener` (étape 04) : le nom personnalisé de
    l'entité identifie ce qu'elle représente. Un même PNJ renommé
    « guard » peut donc à la fois satisfaire un objectif `TALK_TO_NPC` et
    ouvrir le dialogue `rpgquest:guard` — cohérence volontaire entre les
    deux systèmes, toujours sans dépendance à Citizens.
-   **`/dialogue open <joueur> <dialogueId>`** (`rpgquest.admin`) — seule
    commande demandée par la mission ; aucune commande n'est nécessaire
    pour sélectionner un choix (`ClickEvent.callback` est un mécanisme
    entièrement serveur, pas de round-trip commande).

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
-   **`messages.yml`** (nouveau, distinct de `config.yml`) : messages
    MiniMessage des commandes `/quest`, copié depuis le jar au premier
    démarrage (jamais écrasé ensuite), rechargé par `/quest admin reload`.
    Choix d'un fichier séparé plutôt que d'alourdir `config.yml` (qui reste
    dédié aux réglages, pas au texte) — convention courante dans
    l'écosystème Bukkit.
-   **Bug de migration de schéma corrigé pendant cette étape** :
    l'ajout de la migration V2 (`quest_objective_progress`) a révélé que
    `SchemaMigrator.migrate()` ne persistait jamais la nouvelle version via
    `PRAGMA user_version` après une migration réelle (condition de
    comparaison inversée — comparait la version obtenue *après* migration à
    `CURRENT_VERSION`, toujours égales par construction, au lieu de la
    comparer à la version de *départ*). Les tables étaient bien créées mais
    `user_version` restait bloqué à `1` indéfiniment, ce qui n'aurait rien
    cassé fonctionnellement ici (les `CREATE TABLE IF NOT EXISTS` restent
    sûrs) mais aurait rendu toute future migration conditionnelle sur la
    version incorrecte. Détecté par inspection manuelle de la base après
    `runServer`, corrigé, et verrouillé par `SchemaMigratorTest`.
-   **`config.yml` → `dialogue`** (nouveau) : `renderer` (`chat` par défaut
    ou `paper-dialog`) et `allowed-commands` (liste blanche pour
    `RUN_SAFE_COMMAND`). Un `config.yml` déjà existant sur disque (généré
    par une étape précédente, sans cette section) continue de fonctionner
    sans modification : la section absente est traitée comme « valeurs par
    défaut », pas comme une erreur — vérifié en conditions réelles via
    `runServer` avec l'ancien `config.yml` du dépôt de test.
-   **`debug`** est le seul champ de configuration réellement branché à un
    comportement observable pour l'instant : quand `true`, `/rpgquest
    version` affiche une ligne supplémentaire résumant la configuration
    active. Choisi précisément pour rendre le scénario de test manuel
    (« modifier debug, reload, vérifier l'application ») vérifiable sans
    dépendre d'une fonctionnalité de jeu qui n'existe pas encore.

## Limites connues

-   `CustomItemRegistry` et `QuestJournalUi` restent des interfaces
    marqueurs `extends PluginService`, sans aucune classe qui les
    implémente.
-   Pas de commande `/dialogue admin reload` — non demandée par cette
    étape, les dialogues sont chargés une seule fois au démarrage. À
    ajouter si des dialogues doivent être modifiés sans redémarrer le
    serveur (mirroir facile de `/quest admin reload`, même mécanisme).
-   `PaperDialogRenderer` compile contre l'API réelle et son code a été
    vérifié signature par signature par inspection du jar, mais **n'a pas
    été exercé par un vrai client** dans cet environnement (aucun client
    Minecraft disponible ici, comme documenté pour les étapes
    précédentes) : le renderer par défaut (`ChatDialogueRenderer`, qui a
    été exercé) est celui recommandé tant que l'API Dialog reste
    expérimentale et non testée en conditions réelles.
-   `ADVANCE_QUEST` avance en satisfaisant immédiatement tous les
    objectifs de l'étape courante — il ne « joue » pas les objectifs un
    par un ; pour une quête à plusieurs étapes, un seul `ADVANCE_QUEST`
    depuis un dialogue ne fait passer qu'une étape à la fois (par
    conception : chaque étape doit rester une décision explicite du
    scénario, pas un raccourci qui termine toute la quête d'un coup).
-   `locale` et `resource-pack` sont validés et stockés dans `PluginConfig`
    mais ne pilotent encore aucun comportement réel (pas d'i18n, pas d'envoi
    de resource pack aux joueurs) — prévu pour des étapes ultérieures
    dédiées.
-   `TALK_TO_NPC` repose sur le nom personnalisé d'une entité vivante
    quelconque (voir section `quest.progress`) : aucun système de PNJ dédié
    n'existe (pas d'invulnérabilité, d'IA figée, de dialogue). `COMMAND` et
    `VARIABLE` (récompenses) s'exécutent bien à la remise, mais aucune
    commande ne permet encore de *lire* une `player_variable` en jeu.
-   Fenêtre de risque théorique, non traitée : si le processus plante entre
    l'octroi d'une récompense (déjà appliqué au joueur) et la confirmation
    de l'écriture asynchrone de `state = COMPLETED` en base, un redémarrage
    pourrait, en théorie, permettre un octroi supplémentaire au prochain
    déclenchement. Design volontairement simple (pas de transaction
    distribuée/outbox) ; risque jugé acceptable pour un plugin de ce type,
    documenté plutôt que traité.
-   Sur la console Windows, les caractères accentués des logs (SLF4J) peuvent
    s'afficher incorrectement (`�`) selon la page de code active du terminal.
    Le contenu réel des chaînes est en UTF-8 et n'est pas affecté ; c'est un
    problème d'affichage console, pas un bug du plugin.
-   **Test manuel limité par l'environnement** : aucun client Minecraft réel
    n'est disponible ici. La mission de cette étape demande explicitement de
    « jouer entièrement les deux quêtes d'exemple avec un vrai client » —
    non réalisable dans cet environnement. Vérifié à la place via la
    console : démarrage propre, écouteurs enregistrés uniquement pour les
    types d'objectifs réellement utilisés (`KILL_ENTITY`, `BREAK_BLOCK`,
    `TALK_TO_NPC` pour les deux exemples), rapport de chargement des
    quêtes, rejet correct des commandes joueur exécutées depuis la console,
    et inspection directe de `data.db` (tables `quest_progress`/
    `quest_objective_progress`, `PRAGMA user_version = 2`). Le scénario de
    jeu complet (accepter, progresser, remettre, reconnexion en cours de
    quête, avec un vrai client) reste à valider par un testeur humain.
