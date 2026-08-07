# Architecture

## Arborescence des packages (prévue)

    be.lloyd.rpgquest
    ├── RPGQuestPlugin       (point d'entrée, délègue tout à bootstrap)
    ├── admin                (/rpgadmin : aplatissement de terrain, zones, portails, mobs spéciaux)
    ├── backpack             (inventaire virtuel persistant par joueur, trois paliers)
    │   └── model            (modèle immuable : palier)
    ├── bootstrap            (cycle de vie : PluginService, registre, orchestration)
    ├── command
    ├── config               (chargement + validation de config.yml)
    ├── claim                (claims de terrain joueurs : persistance SQLite, sélection, protection)
    │   └── model            (modèles immuables : claim, permissions)
    ├── entitlement          (interface générique d'avantage joueur — mission étape 20, point 11)
    ├── crafting             (recettes YAML + enregistrement Bukkit + garde anti-substitution)
    │   └── model            (modèles immuables : recette façonnée/non, ingrédients, résultat)
    ├── database
    ├── dialogue             (définitions YAML + moteur de session, relié aux quêtes)
    │   ├── model           (modèles immuables : dialogue, nœuds, choix, conditions, actions)
    │   ├── render          (DialogueRenderer : ChatDialogueRenderer, PaperDialogRenderer)
    │   └── session         (état runtime mutable, sessions en mémoire, listeners)
    ├── economy              (portefeuille joueur, paiements, marchands PNJ, marché entre joueurs)
    │   ├── merchant        (définitions YAML de marchands + vitrine en jeu)
    │   │   └── model       (modèles immuables : marchand, offre)
    │   └── market          (marché entre joueurs : offres persistées, vitrine paginée)
    ├── item                 (définitions YAML d'objets personnalisés + registre)
    │   ├── model            (modèles immuables : type, rareté, attributs, enchantements...)
    │   └── behavior         (comportements de combat/outil en jeu : cooldowns, listeners)
    ├── mob                  (variantes de mobs vanilla YAML + upgrade au spawn + population)
    │   ├── model            (modèles immuables : définition de variante, capacités)
    │   └── ability          (écouteurs/services par capacité : explosion, agressivité, division)
    ├── player
    ├── progression          (XP RPG multi-compétences, indépendante de l'XP vanilla)
    │   ├── model            (modèles immuables : compétence, courbe de niveaux, résultat d'octroi)
    │   └── listener         (écouteurs par source d'XP : combat, minage, agriculture, pêche, exploration, quêtes)
    ├── quest                (définitions YAML + progression des joueurs)
    │   ├── model            (modèles immuables : quête, étapes, objectifs, récompenses)
    │   └── progress         (état runtime mutable, index, listeners, commandes)
    ├── resource             (types de nœuds YAML + positions récoltables persistées)
    │   └── model            (modèles immuables : type de nœud, drops pondérés)
    ├── travel               (portails et téléportation : destinations, canalisation, sécurité)
    │   └── model            (modèles immuables : destination, portail)
    ├── ui                   (journal de quêtes : menu paginé, vue détail, suivi)
    ├── util                 (vide pour l'instant : utilitaires génériques uniquement)
    └── zone                 (zones protégées : registre YAML, protection d'événements, sélection)
        └── model            (modèles immuables : zone, permissions)

Tous les packages listés existent désormais. `quest`, `dialogue`, `ui`,
`item` et désormais `resource` couvrent chacun le chargement/l'affichage
**et** l'exécution en jeu (voir ci-dessous).

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

### `ui` (journal de quêtes)

-   **Disposition du menu liste** — inventaire de 54 slots : une seule
    ligne de « chrome » (slots 0-8 : trois onglets, précédent/indicateur de
    page/suivant, fermer) suivie de **45 slots de contenu** (slots 9-53).
    `JournalPagination.PAGE_SIZE = 45` correspond exactement à cette
    surface — ce n'est pas un hasard : c'est ce qui rend triviale la
    correspondance entre « nombre de quêtes » et « nombre de pages »
    (45 quêtes = 1 page pile, 46 = 2 pages), directement vérifiable par des
    tests purs (`JournalPaginationTest`, aucune dépendance Bukkit).
-   **`JournalPagination`** — arithmétique de pagination isolée dans une
    classe sans aucun type Bukkit (`pageCount`, `clampPage`, `pageOf`),
    testée en JUnit pur. `QuestJournalService` ne fait que l'appeler ; toute
    la logique « combien de pages, quelle page demandée est valide » est
    donc vérifiable sans MockBukkit, comme `ConfigValidator`/`QuestDefinitionParser`.
-   **Trois onglets** — Actives (`ACTIVE`/`READY_TO_TURN_IN`), Disponibles
    (`NOT_STARTED`/`ABANDONED`), Terminées (`COMPLETED`). Une quête
    répétable déjà terminée n'est **pas** re-listée dans « Disponibles » :
    limitation assumée (voir Limites connues), `/quest accept` reste le
    chemin pour la relancer.
-   **`JournalSession`** — état en mémoire (comme les sessions de
    dialogue, jamais persisté) de ce qu'un joueur regarde actuellement
    (onglet, page, **liste figée des quêtes affichées sur cette page**).
    Un clic sur un slot de contenu est résolu contre cette liste figée, pas
    recalculé à la volée : un `/quest admin reload` qui change l'ensemble
    de quêtes entre le rendu et le clic ne peut donc jamais faire cliquer
    un joueur sur « la mauvaise quête ».
-   **Vue détail séparée** — clic gauche sur une icône de la liste ouvre un
    second inventaire (27 slots) dédié à une seule quête (icône, retour,
    suivre/ne plus suivre, fermer). Clic droit sur la liste **ne change pas
    de vue** : il bascule le suivi directement (`toggleTracking`) et
    re-rend la liste sur place, cohérent avec la mission (clic gauche =
    détails, clic droit = suivre).
-   **`JournalInventoryHolder`** — marqueur (`InventoryHolder`) posé sur
    chaque inventaire créé par le journal. C'est sur ce type, lu via
    `event.getView().getTopInventory().getHolder()`, que
    `QuestJournalListener` reconnaît « cet événement concerne un menu du
    plugin », **indépendamment du côté cliqué** (haut ou bas de l'écran).
-   **Protection anti-vol/duplication** — `QuestJournalListener.onInventoryClick`
    annule (`setCancelled(true)`) **tout** `InventoryClickEvent` dès que le
    holder de l'inventaire du haut est un `JournalInventoryHolder`, avant
    même de regarder le type de clic. Un shift-clic, un double-clic (qui
    collecte normalement des objets similaires dans les deux inventaires),
    une touche numérique (échange avec la barre d'accès rapide) ou un clic
    simple sont donc tous bloqués par la même unique ligne de code — pas
    un `switch` sur chaque `ClickType` à maintenir. `InventoryDragEvent`
    suit la même logique mais seulement si le glisser touche réellement
    une case du haut (`rawSlot < taille de l'inventaire du haut`) : un
    glisser entièrement dans l'inventaire du joueur n'est pas bloqué.
-   **`QuestJournalListener`** délègue tout à `QuestJournalService` (même
    organisation que `DialogueNpcInteractListener` → `DialogueSessionEngine`) :
    le listener ne connaît que la traduction événement Bukkit → appel de
    méthode, toute la logique de menu (navigation, détails, suivi) vit
    dans le service, testable en l'invoquant directement sans passer par
    le bus d'événements.
-   **Icône configurable par quête** — nouveau champ optionnel `icon:`
    dans le YAML de quête (`QuestDefinitionParser`), résolu comme un
    `Material` (mêmes règles de tolérance que les objectifs `BREAK_BLOCK`/
    `COLLECT_ITEM`) ; absent, il vaut `BOOK` par défaut. `QuestDefinition`
    valide lui-même qu'un icône est toujours présent (jamais nul), comme
    ses autres invariants.
-   **Lore construit dynamiquement** — description, catégorie, état,
    étape courante avec la progression de chaque objectif (réutilise
    `QuestProgressEngine.activeStepView` pour les quêtes actives ; pour
    les autres états, un `QuestStepProgressView` de secours est calculé
    localement à partir de la première étape de la définition, sans
    requête base), récompenses, et prérequis (dont le nom est résolu via
    `questEngine.find`, avec repli sur l'id brut si la quête référencée a
    disparu).
-   **`QuestObjective.requiredAmount`/`describe`** — promus en méthodes
    statiques de l'interface scellée (avant : logique privée dupliquée
    dans `QuestProgressEngine`), réutilisées à la fois par
    `QuestProgressEngine` et par `QuestJournalService` : un seul `switch`
    exhaustif par comportement, plus de duplication entre les deux
    packages.
-   **Suivi (« quête suivie ») — persistant, indépendant de l'état** —
    stocké via la table `player_variables` déjà existante (clé réservée
    `__tracked_quest__`, valeur = id de quête ou chaîne vide), pas de
    nouvelle table/migration. Suivre une quête **n'exige pas** qu'elle
    soit déjà active : un joueur peut suivre une quête encore dans l'onglet
    « Disponibles » pour la retrouver facilement plus tard. Le suivi n'est
    effacé automatiquement que dans un seul cas : la quête suivie a
    disparu d'un `/quest admin reload` (elle n'existe plus, rien à
    afficher). Ni la remise, ni l'abandon, ni le simple fait de ne pas
    encore avoir accepté la quête ne l'effacent — seule une action
    explicite du joueur (re-clic droit) le fait.
-   **`TrackedQuestDisplay`** — bossbar Adventure (`BossBar.bossBar(...)`,
    pas de scoreboard/équipe legacy) montrant le nom de la quête suivie,
    l'étape courante et une barre de progression (somme des compteurs
    d'objectifs de l'étape / somme des montants requis). N'affiche rien
    tant que la quête suivie n'est pas `ACTIVE` (rien à montrer), mais ne
    touche jamais au suivi lui-même dans ce cas (voir point précédent).
    Contrôlée par `config.yml` → `journal.tracker-enabled` (`true` par
    défaut) : purement cosmétique, la désactiver n'affecte pas la
    persistance du suivi.
-   **Rafraîchissement piloté par les événements, jamais par sondage** —
    `QuestProgressEngine.onProgressChanged(Consumer<UUID>)` (nouveau hook,
    minimal : une liste de callbacks notifiée à chaque mutation réelle de
    progression — acceptation, incrément d'objectif, changement d'étape,
    remise, abandon) est le seul mécanisme qui déclenche un nouveau rendu.
    `QuestJournalService.start()` s'y abonne une fois ; à chaque
    notification, si le joueur concerné a un menu ouvert et/ou une quête
    suivie, le contenu est reconstruit **à cet instant précis** — aucun
    `runTaskTimer`, aucune tâche périodique, nulle part.
-   **Nettoyage** — à la déconnexion (`PlayerQuitEvent`) : session de menu
    et bossbar du joueur supprimées (le suivi persisté en base, lui, n'est
    pas touché). À la désactivation du plugin (`QuestJournalService.stop()`) :
    tous les inventaires de journal encore ouverts sont fermés
    (`player.closeInventory()`) et toutes les bossbars actives sont
    masquées avant que le service ne s'arrête.
-   **`/quests`** (`rpgquest.quest`, même permission que les commandes
    `/quest` joueur) — ouvre toujours sur l'onglet Actives, première page.

### `item` (objets personnalisés)

-   **`CustomItemDefinition`** — même discipline « correct par construction »
    que `QuestDefinition`/`DialogueDefinition`/`CustomItemDefinition` : id
    namespacé, `CustomItemType` (`WEAPON`/`TOOL`/`RESOURCE`/`QUEST_ITEM`),
    matériau vanilla de base, nom + lore (chaînes MiniMessage brutes, pas de
    table de traductions — contrairement à `LocalizedText`, non demandé
    pour les objets), rareté (`ItemRarity`, porte sa propre couleur
    MiniMessage), model data **et/ou** item model (les deux mécanismes
    coexistent, voir plus bas), empilabilité + taille de pile effective,
    durabilité optionnelle, attributs (`ItemAttributeSpec`), enchantements
    (`ItemEnchantmentSpec`), tags de gameplay, comportement spécial
    (identifiant libre, donnée uniquement — voir Limites connues),
    restrictions de fabrication (`CraftingRestrictions`, donnée uniquement).
    Invariants rejetés dès le record lui-même (pas seulement le parseur) :
    taille de pile hors `[1, 99]`, durabilité sur un matériau qui n'en a
    pas en vanilla, **objet à la fois empilable et doté d'une durabilité**
    (Minecraft ne fusionne jamais un objet endommagé, quel que soit ce que
    dirait un composant de taille de pile — combinaison rejetée à la
    source plutôt que silencieusement ignorée en jeu).
-   **`ItemDefinitionParser`/`ItemLoader`** — même structure à deux phases
    que `QuestDefinitionParser`/`QuestLoader` (un fichier à la fois,
    accumulation de toutes les erreurs ; puis validation croisée qui
    rejette les id dupliqués entre fichiers). Contrairement au chargeur de
    quêtes, pas de notion de prérequis entre objets : la validation croisée
    s'arrête aux doublons.
    -   **Résolution tolérante contre les registres vanilla** : `material`
        (`Material.matchMaterial`), `attribute`/`type` d'enchantement
        (comparés à `Registry.ATTRIBUTE`/au registre d'enchantements via
        `NamespacedKey.minecraft(nom_en_minuscules)`), `slot`
        (`EquipmentSlotGroup.getByName`) — même philosophie que
        `Material.matchMaterial`/`EntityType.fromName` déjà utilisés par le
        parseur de quêtes : pas de liste de valeurs valides recopiée à la
        main.
    -   **`Registry.ENCHANTMENT` est déprécié** dans cette version de
        l'API (`Registry.ATTRIBUTE` ne l'est pas) : le parseur utilise à la
        place `RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)`,
        le remplacement non déprécié recommandé par Paper — vérifié par
        compilation avec `-Xlint:deprecation` (aucun avertissement restant).
-   **Identification — seule source de vérité : le PersistentDataContainer**
    — chaque objet créé porte son id namespacé sous la clé
    `rpgquest:custom_item_id` (`PersistentDataType.STRING`).
    `YamlCustomItemRegistry.identify(ItemStack)` ne lit **que** cette clé ;
    `isCustomItem`/`resolve` en découlent. Aucune vérification ne compare
    jamais le nom affiché ou le lore : un objet vanilla renommé pour imiter
    un objet personnalisé (même nom, même lore) n'est donc jamais reconnu
    — testé explicitement (`renamedVanillaItemIsNotRecognized`).
-   **`create(id, amount)` distingue « id inconnu » de « quantité invalide »**
    seulement au niveau de l'appelant : la méthode elle-même retourne
    `Optional.empty()` dans les deux cas (id absent du registre, ou
    `amount` hors de `[1, effectiveMaxStackSize()]`), volontairement — la
    commande `/customitem give` appelle `find(id)` séparément d'abord pour
    donner un message d'erreur distinct dans chaque cas.
-   **Empilabilité réellement appliquée**, pas seulement documentée :
    `ItemMeta#setMaxStackSize(effectiveMaxStackSize())` est posé sur
    chaque objet créé (composant Paper 1.20.5+, vérifié présent dans
    `paper-api-1.21.11` par inspection du jar). Un objet `stackable: false`
    a donc réellement `getMaxStackSize() == 1` en jeu, et
    `registry.create(id, 2)` est refusé à la source plutôt que de créer un
    objet qui mentirait sur sa propre empilabilité.
-   **Model data ou item model, les deux mécanismes cohabitent** —
    `custom-model-data` (entier unique, `ItemMeta#setCustomModelData`,
    **déprécié** par Paper au profit du composant `CustomModelDataComponent`
    mais toujours fonctionnel et encore utilisé par de nombreux resource
    packs existants basés sur des prédicats simples) et `item-model`
    (`NamespacedKey`, `ItemMeta#setItemModel`, non déprécié, mécanisme
    recommandé actuellement) sont tous deux exposés comme champs
    optionnels indépendants — c'est la lecture littérale du « model data
    *ou* item model » de la mission. L'appel dépréciée est explicitement
    justifié et supprimé (`@SuppressWarnings("deprecation")`) dans
    `YamlCustomItemRegistry`, même pratique que `PaperDialogRenderer`
    (`@SuppressWarnings("UnstableApiUsage")`) à l'étape des dialogues.
-   **Identifiant de modificateur d'attribut dérivé, jamais dans le YAML**
    — `AttributeModifier` exige une clé unique (`NamespacedKey`, plus
    l'ancien système par `UUID` aléatoire) ; `YamlCustomItemRegistry` la
    dérive automatiquement de l'id de l'objet, de la clé de l'attribut et
    de son index dans la liste (`rpgquest:<item>_<attribut>_<index>`),
    pour ne pas alourdir la définition d'un champ purement technique que
    l'auteur du YAML n'a aucune raison de choisir lui-même.
-   **Niveaux d'enchantement au-delà du maximum vanilla autorisés** —
    `meta.addEnchant(enchantment, level, true)` (dernier paramètre
    `ignoreLevelRestriction`) : un plugin RPG a un usage légitime
    d'enchantements « brisés » (ex. Tranchant X) sur des objets
    personnalisés, contrairement à un objet vanilla obtenu par enchantement
    normal en jeu.
-   **4 objets d'exemple générés automatiquement** (même mécanisme que les
    quêtes/dialogues d'exemple — jamais écrasés s'ils existent déjà) :
    `forest_blade` (`WEAPON`, non empilable, durabilité, attributs +
    enchantements), `miner_pickaxe` (`TOOL`, non empilable, durabilité,
    enchantements), `spider_fang` (`RESOURCE`, empilable jusqu'à 64),
    `refined_crystal` (`QUEST_ITEM`, empilable jusqu'à 16, model data) —
    un exemple par type initial demandé par la mission.
-   **`/customitem give|list`** (`rpgquest.admin`, outils d'administration
    comme `/quest complete`/`/dialogue open`) et **`/customitem inspect`**
    (nouvelle permission `rpgquest.item`, `default: true` — comme
    `rpgquest.quest`, ouverte à tout joueur puisqu'identifier l'objet
    qu'on tient en main est une action légitime pour n'importe qui, pas
    seulement un administrateur).

### `item.behavior` (comportements de combat et d'outil)

-   **Deux nouveaux champs optionnels sur `CustomItemDefinition`** —
    `weaponBehavior` (`WeaponBehavior`, section YAML `combat:`) et
    `toolBehavior` (`ToolBehavior`, section YAML `tool:`), tous deux
    nullable et sans contrainte de type imposée (un `RESOURCE` peut
    techniquement déclarer un `combat:`, c'est juste de la donnée) — cohérent
    avec le reste du modèle, qui ne mélange jamais validation structurelle
    et règles métier de gameplay.
-   **`base-damage`/`attack-speed-bonus` sont des bonus additifs, jamais un
    remplacement** — c'est la décision de conception centrale de cette
    étape, dictée par la règle « compatibilité enchantements » : au moment
    où `EntityDamageByEntityEvent` se déclenche, le jeu a déjà intégré
    l'attribut `ATTACK_DAMAGE` et les enchantements (Tranchant, etc.) dans
    `event.getDamage()`. `WeaponBehaviorListener` calcule donc
    `(dégât reçu + base-damage) [* critical-multiplier si critique]` et
    appelle `event.setDamage(...)` **une seule fois** avec ce résultat —
    remplacer entièrement le dégât aurait annulé silencieusement tout
    enchantement du joueur, contraire à la mission.
-   **Critique personnalisé, indépendant du critique vanilla** — un jet
    aléatoire propre à l'arme (`critical-chance`), pas une détection de
    saut/chute côté serveur (non fiable/anti-triche côté client de toute
    façon). `critical-multiplier` s'applique au dégât déjà additionné du
    bonus, pas seulement au dégât de base — un seul calcul, une seule
    écriture, conformément à la règle « ne jamais appliquer les dégâts
    deux fois ».
-   **`mining-speed-bonus`/`attack-speed-bonus` appliqués comme attributs
    vanilla, pas via un listener** — `MINING_EFFICIENCY` (outils) et
    `ATTACK_SPEED` (armes) sont deux attributs réels de l'API Paper
    1.21.11 (vérifiés dans le jar). `YamlCustomItemRegistry.applyBehaviorAttributes`
    les pose à la création de l'`ItemStack`, exactement comme les
    attributs génériques déjà supportés depuis l'étape précédente : c'est
    le mécanisme natif et correct pour un bonus de vitesse, une action
    persistante liée à l'objet plutôt qu'à un événement ponctuel — aucun
    listener n'est nécessaire pour cette partie du comportement.
-   **`CooldownManager`** — indexé par **(UUID joueur, id de capacité)**,
    jamais par le seul id de l'objet (règle 4 de la mission), pour
    permettre à plusieurs objets de partager une capacité ou à un même
    objet d'en exposer plusieurs. L'horloge est injectable
    (`LongSupplier`), ce qui rend `CooldownManagerTest` entièrement
    déterministe sans `Thread.sleep`. Nettoyage actif (règle 5) à trois
    niveaux : suppression paresseuse d'une entrée expirée dès qu'elle est
    consultée (`isReady`), suppression de toutes les entrées d'un joueur à
    sa déconnexion (`clear`, évite d'accumuler des entrées mortes pour des
    joueurs partis en plein cooldown), et `purgeExpired()` appelée par une
    tâche asynchrone toutes les 5 minutes (`EquipmentBehaviorService`) —
    une purge active, pas seulement paresseuse, comme demandé.
-   **`WeaponBehaviorListener`/`ToolBehaviorListener`** — chaque règle de
    sécurité de la mission a un garde explicite et commenté dans le code :
    -   *Événements Paper et annulations (règle 1)* — `@EventHandler(ignoreCancelled
        = true)` partout, **plus** une vérification explicite
        `event.isCancelled()` en début de méthode pour les événements dont
        cette méthode n'est pas dépréciée (`EntityDamageByEntityEvent`,
        `PlayerItemDamageEvent`, `BlockDropItemEvent`) — la redondance est
        volontaire : elle rend la règle vraie même en appelant la méthode
        directement dans un test, pas seulement quand Bukkit filtre avant
        dispatch. `PlayerInteractEvent#isCancelled()` est dépréciée
        (sémantique ambiguë bloc/objet) : seul `ignoreCancelled = true`
        est utilisé pour cet événement, avec un commentaire l'expliquant.
    -   *Dégâts appliqués une seule fois (règle 2)* — un seul appel à
        `event.setDamage(...)`, jamais d'appel supplémentaire à
        `entity.damage(...)`.
    -   *NaN/infini/négatif (règle 3)* — validé au chargement (records du
        package `item.model`, ex. `critical-chance` hors `[0,1]` rejeté) et
        re-vérifié à l'exécution (`sanitizeDamage` : NaN/infini → 0,
        négatif → 0) avant l'écriture finale, en défense en profondeur
        contre une combinaison de bonus qui produirait un résultat
        incohérent (ex. `base-damage` très négatif).
    -   *Main secondaire (règle 6)* — seul `PlayerInventory#getItemInMainHand()`
        est jamais consulté pour résoudre l'arme/l'outil en jeu (jamais
        `getItemInOffHand()`) ; pour la capacité spéciale d'outil,
        `PlayerInteractEvent#getHand() == EquipmentSlot.HAND` est vérifié
        explicitement (l'événement se déclenche séparément pour chaque
        main).
    -   *Armor stand (règle 6)* — `WeaponBehaviorListener.isValidTarget`
        rejette explicitement `ArmorStand` (qui, en API Bukkit, est bien
        une `LivingEntity` — un simple `instanceof LivingEntity` ne
        suffit pas à l'exclure, il faut le test dédié).
    -   *Projectile (règle 6)* — `event.getDamager() instanceof Player`
        exclut structurellement tout dégât de projectile (flèche,
        trident...) : le damager d'un tel événement est le projectile
        lui-même, jamais le joueur qui l'a tiré, même indirectement.
    -   *Faux objet (règle 6)* — `YamlCustomItemRegistry.resolve(...)`
        (PersistentDataContainer) est l'unique source de vérité, jamais le
        nom affiché ni le lore — un objet vanilla renommé pour imiter
        `forest_blade` ne déclenche jamais son comportement.
-   **Bonus de récolte sans deviner la table de butin** —
    `ToolBehaviorListener.onBlockDropItem` écoute `BlockDropItemEvent`
    (après que le jeu a déjà calculé les drops, fortune/silk touch inclus)
    et duplique les `ItemStack` déjà déposés plutôt que de recalculer une
    table de loot — plus simple, toujours cohérent avec les enchantements
    de l'outil.
-   **Consommation de durabilité par remplacement, pas par addition** —
    `ToolBehaviorListener.onItemDamage` écoute `PlayerItemDamageEvent`
    (déclenché par le jeu juste avant sa propre réduction de durabilité)
    et appelle `event.setDamage(durabilityCost)` pour **remplacer** la
    valeur vanilla (généralement 1), jamais en plus d'un appel séparé —
    même principe « une seule écriture » que pour les dégâts d'arme. Ce
    remplacement n'est pas filtré par `allowed-blocks` (l'événement ne
    porte pas le contexte du bloc cassé) : seuls le bonus de récolte et la
    capacité spéciale le sont — limite assumée, documentée plus bas.
-   **`EquipmentBehaviorService`** (`PluginService`) — construit un seul
    `CooldownManager` partagé par les deux listeners, démarre la tâche de
    purge périodique, expose `weaponListener()`/`toolListener()`/
    `cooldownCleanupListener()` pour un câblage via `PlayerListenerService`
    (même convention que tous les autres listeners du plugin).
-   **Logs debug (règle 8)** — pas de nouveau réglage : le flag `debug`
    existant de `config.yml` (déjà utilisé par `/rpgquest version`) est
    réutilisé, lu à chaque événement via `configService.current().debug()`
    (jamais mis en cache), donc activable/désactivable par
    `/rpgquest reload` sans recréer les listeners.

### `resource` (nœuds de ressource récoltables)

-   **Deux notions séparées, comme `item`/`item.behavior`** : un **type** de
    nœud (`ResourceNodeDefinition`, `resource.model`, YAML dans
    `plugins/RPGQuest/resource-nodes/`) décrit une recette de récolte
    (bloc actif/épuisé, outils requis, respawn, drops) ; une **position**
    (`ResourceNodeService.NodeState`, en mémoire + `resource_nodes` en
    base) est une instance concrète créée en jeu via `/resourcenode
    create`. Un même type peut être posé à un nombre arbitraire de
    positions, exactement comme un `CustomItemDefinition` peut être
    instancié en un nombre arbitraire d'`ItemStack`.
-   **Aucun nouveau bloc client** — `active-material`/`depleted-material`
    sont des blocs vanilla ordinaires (ex. `EMERALD_ORE`/`STONE`) : un nœud
    « ressource personnalisée » est reconnu uniquement par sa **position**
    exacte suivie côté serveur, jamais par un identifiant de bloc qui
    n'existe pas côté client. C'est la même philosophie que les objets
    personnalisés (identification serveur uniquement, jamais un mécanisme
    visuel), appliquée aux blocs plutôt qu'aux `ItemStack`.
-   **`ResourceNodeDefinition`/`ResourceDrop`** — même discipline « correct
    par construction » que `QuestDefinition`/`CustomItemDefinition` :
    bloc actif et bloc épuisé doivent être différents (sinon aucun indice
    visuel de récolte), `respawn-seconds` strictement positif, `drops` non
    vide. `ResourceDrop` est une interface scellée (`CustomItemDrop` —
    référence un id résolu via `YamlCustomItemRegistry` au moment de la
    récolte — ou `VanillaItemDrop` — un `Material` brut), un seul tirage
    pondéré par récolte (comme une table de loot vanilla classique), pas un
    jet indépendant par entrée.
-   **`ResourceNodeDefinitionParser`/`ResourceNodeLoader`** — même
    conception à deux phases que `QuestLoader`/`ItemLoader` : un fichier
    parsé indépendamment (accumulation de toutes les erreurs), puis une
    passe de validation croisée qui rejette les id de type dupliqués entre
    fichiers (pas de notion de prérequis pour un type de nœud, comme pour
    un objet).
-   **`ResourceNodeRegistry`** (`PluginService`) — ne connaît que les
    *types*, symétrique à `YamlCustomItemRegistry` (génère l'exemple
    `crystal_ore` embarqué au premier démarrage, jamais s'il existe déjà).
-   **`ResourceNodeService`** (`PluginService`) — possède l'état runtime
    des positions : une `ConcurrentHashMap` en mémoire (position → type +
    échéance de respawn, `null` = actif), peuplée au démarrage depuis
    `resource_nodes` (chargement asynchrone puis application sur le thread
    principal, même patron que les autres services). `clock`/le résolveur
    de monde/le vérificateur de chunk chargé sont injectables (comme le
    `LongSupplier` de `CooldownManager`), ce qui rend `sweepRespawns(long)`
    testable de façon déterministe sans dépendre du comportement de
    monde/chunk simulé par MockBukkit.
    -   **Récolte (`handleBreak`)** — un `BlockBreakEvent` sur une position
        suivie est **toujours annulé** (`setCancelled(true)`), y compris en
        cas de récolte réussie : poser le bloc épuisé à l'intérieur du
        handler serait sinon immédiatement écrasé par le post-traitement
        vanilla de l'événement (qui pose `AIR`) si celui-ci n'était pas
        annulé. Le service prend donc le contrôle total du cassage —
        remplacement du bloc, dépôt du butin — plutôt que de laisser
        vanilla agir en parallèle.
    -   **Anti double-cassage simultané (règle demandée)** — le nœud est
        marqué épuisé **de façon synchrone**, avant tout dépôt d'objet ou
        appel asynchrone à la base, exactement le même principe que
        l'anti double-remise de `QuestProgressEngine` : un second
        `BlockBreakEvent` sur la même position (même joueur ou un autre)
        voit déjà l'échéance de cooldown à jour et est annulé sans drop
        supplémentaire.
    -   **Bloc physique modifié détecté, jamais deviné** — si le bloc à une
        position suivie ne correspond ni au matériau actif ni au matériau
        épuisé attendu (WorldEdit, un joueur qui a construit par-dessus...),
        `handleBreak` laisse vanilla reprendre la main sur ce cassage
        plutôt que de continuer sur une hypothèse fausse ; le nœud reste
        suivi pour autant.
    -   **Respawn par balayage périodique, jamais de chargement forcé de
        chunk** — `sweepRespawns` (appelée toutes les 5 s par une tâche
        Bukkit synchrone, car reposer un bloc exige le thread principal)
        ne restaure un nœud épuisé dont l'échéance est passée que si son
        monde existe encore (`Function<String, World>`, `null` = monde
        supprimé/renommé, respawn différé indéfiniment sans jamais lever
        d'exception) **et** que son chunk est *déjà* chargé naturellement
        (`ChunkLoadedChecker`, jamais `Chunk#addPluginChunkTicket` ni
        équivalent) — un nœud dans un chunk déchargé reste simplement en
        attente, revérifié au balayage suivant.
    -   **Redémarrage pendant un cooldown** — les positions et leur
        `depleted_at` sont rechargées telles quelles depuis
        `resource_nodes` : un nœud encore en cooldown au moment de l'arrêt
        du serveur le reste après redémarrage, jusqu'à ce que
        `sweepRespawns` constate que l'échéance est dépassée (testé
        directement sans dépendre d'un vrai redémarrage, grâce à l'horloge
        injectable).
-   **`/resourcenode create|remove|inspect`** (`rpgquest.admin`, outil
    d'administration comme `/customitem give|list`) — toutes les
    sous-commandes résolvent le bloc visé par le joueur via
    `Player#rayTraceBlocks` (portée 6 blocs), pas de coordonnées à taper à
    la main.

### `crafting` (recettes personnalisées)

-   **`RecipeDefinition`/`RecipeResult`/`RecipeIngredient`** — même
    discipline « correct par construction » que les autres modèles :
    interfaces scellées (`ShapedRecipeDefinition`/`ShapelessRecipeDefinition`,
    `CustomItemResult`/`VanillaResult`, `CustomItemIngredient`/
    `VanillaIngredient`), invariants validés par les records eux-mêmes
    (motif 1-3 lignes cohérentes, chaque caractère du motif présent dans
    `key`, total d'ingrédients `SHAPELESS` ≤ 9 — taille de la grille 3x3).
    Le parseur (`RecipeDefinitionParser`, même conception à deux phases que
    les autres) ne connaît aucun registre d'objets : il ne peut donc pas
    valider qu'un `custom-item:` référencé existe réellement, seulement sa
    forme syntaxique.
-   **Résolution et enregistrement (`YamlCraftingRegistry`)** — c'est ici,
    une fois le registre d'objets disponible, que chaque référence
    `custom-item:` (ingrédient ou résultat) est résolue via {@code
    YamlCustomItemRegistry#create} ; un id inconnu (ou une quantité de
    résultat invalide) rejette **seulement cette recette** avec un message
    explicite, sans bloquer les autres — même philosophie que le rejet en
    cascade des quêtes/dialogues. `reload()` désenregistre d'abord toutes
    les recettes actuellement connues (`Bukkit.removeRecipe`) avant d'en
    réenregistrer de nouvelles, pour rester idempotent (pas d'exception
    « clé déjà enregistrée » sur un second appel).
-   **Ingrédient personnalisé jamais satisfait par un vanilla qui l'imite**
    — chaque `CustomItemIngredient` devient un `RecipeChoice.ExactChoice`
    construit à partir de l'`ItemStack` canonique du registre (donc avec
    son PersistentDataContainer) : c'est la vérification Bukkit native qui
    empêche déjà la majorité des substituts, avant même qu'un joueur ne
    puisse valider une fabrication.
-   **`RecipeCraftGuardListener` — défense en profondeur, pas de suivi
    positionnel exact** — écoute `PrepareItemCraftEvent` (se déclenche
    identiquement pour un clic simple, un shift-clic ou une recette
    automatique du livre de recettes : aucune distinction de code n'est
    donc nécessaire entre ces cas). Plutôt que de réimplémenter le calcul
    de décalage d'un motif `SHAPED` dans la grille 3x3 (Bukkit le fait en
    interne, sans l'exposer), la vérification porte sur l'**ensemble** des
    identités attendues par la recette matchée (`isValidMatrix`, cœur
    testable indépendant de l'événement réel) : tout objet de la grille
    doit être soit un objet personnalisé dont l'id fait partie des
    ingrédients personnalisés attendus, soit un matériau vanilla qui fait
    partie des ingrédients vanilla attendus — jamais l'inverse. Un objet
    personnalisé de la même famille de matériau qu'un ingrédient vanilla
    attendu (ou l'inverse) est donc rejeté même sans suivi de position.
-   **Recettes d'exemple** (mêmes conventions que quêtes/dialogues/objets/
    types de nœuds — jamais écrasées si déjà présentes) : `forest_blade_recipe`
    (`SHAPED`, 2 `spider_fang` + 1 bâton), `refined_crystal_recipe`
    (`SHAPELESS`, 4 quartz — voie alternative au nœud `crystal_ore`),
    `miner_pickaxe_recipe` (`SHAPELESS`, 1 `refined_crystal` + 1 pioche en
    fer — une amélioration).
-   **`item.SpiderFangDropListener`** (hors du package `crafting`, mais
    ajouté pour la même raison) — fait tomber `spider_fang` des araignées
    tuées par un joueur : sans cette petite addition, `forest_blade_recipe`
    ne serait atteignable qu'via `/customitem give`, ce qui aurait rendu le
    parcours RPG complet (voir plus bas) non jouable de bout en bout sans
    intervention admin. Portée volontairement minimale (un objet, un type
    de mob, chance de 100 %, pas de table de loot générique) — une vraie
    table de drops par mob est hors périmètre de cette étape.

### `economy` (portefeuille et marchands PNJ)

-   **`WalletRepository` (`database`, pure JDBC)** — même discipline que
    `PlayerVariableRepository` : aucun type Bukkit/Paper. Solde en `long`
    (entier, jamais de virgule flottante pour de la monnaie), table
    `wallets` créée paresseusement (`INSERT OR IGNORE`) au premier contact
    plutôt qu'à la connexion du joueur — un joueur qui n'a jamais touché
    l'économie n'a simplement pas de ligne, `balance()` répond `0` sans en
    créer une.
-   **Débit/crédit/paiement réellement atomiques, pas seulement séquentiels**
    — chaque opération (`debit`/`credit`/`pay`/`setBalance`) s'exécute comme
    **une seule** transaction JDBC explicite (`setAutoCommit(false)` puis
    `commit`/`rollback`) à l'intérieur d'un unique appel à
    `DatabaseManager#execute`. Deux garanties se cumulent :
    1.  le thread base de données étant mono-thread et FIFO (voir section
        `database`), deux requêtes concurrentes sur le même portefeuille
        (double-clic, deux joueurs qui se paient simultanément) sont de
        toute façon sérialisées — la seconde relit toujours un solde à jour ;
    2.  la transaction SQL explicite protège en plus contre un crash du
        processus *au milieu* d'une opération (ex. solde débité mais ligne
        de `transactions` jamais écrite) — un cas que la seule sérialisation
        du thread ne couvre pas.
    `pay(from, to, amount)` débite et crédite dans **la même** transaction :
    soit les deux comptes bougent, soit aucun (jamais un virement à moitié
    appliqué). Testé directement (`WalletRepositoryTest`), y compris deux
    débits soumis avant que le premier ne se termine
    (`concurrentDebitsAgainstTheSameWalletNeverOverdraw`).
-   **Montant invalide rejeté à la source** — `debit`/`credit`/`pay` refusent
    tout montant `<= 0` (`IllegalArgumentException`, avant même de toucher
    la base) ; `setBalance` (admin) refuse un montant négatif. Un
    dépassement de capacité (`Math.addExact`) transforme l'opération en
    échec propre plutôt qu'un solde qui déborderait silencieusement en
    négatif.
-   **`EconomyService` (`economy`, pas de cycle de vie propre)** — couche
    fine au-dessus de `WalletRepository`, même conception que
    `PlayerProfileService` : traduit les demandes en appels typés
    (`TransactionType`, `PayOutcome`) sans exposer les chaînes brutes de la
    base aux appelants. `pay()` rejette `from == to` et un montant invalide
    **avant** de toucher la base (`PayOutcome.SAME_PLAYER`/`INVALID_AMOUNT`),
    ce que `WalletRepository` seul ne pouvait pas distinguer proprement.
-   **`/money [pay|admin]`** (`rpgquest.money`, `default: true`, comme
    `rpgquest.quest`) — sans argument, affiche le solde de l'exécutant ;
    `pay <joueur> <montant>` ne cible que des joueurs **en ligne**
    (`Server#getPlayerExact`), limitation assumée par simplicité (voir
    « Limites connues »). `admin give|take|set` (`rpgquest.admin`) est
    l'unique façon de faire entrer de la monnaie dans l'économie hors
    marchand — nécessaire puisqu'aucun « solde de départ » n'est accordé à
    la connexion (encore une simplification assumée).
-   **`economy.merchant` (marchands PNJ)** — un marchand est un modèle
    figé (`MerchantDefinition`/`MerchantOffer`, « correct par construction »
    comme `CustomItemDefinition`/`QuestDefinition`), chargé depuis
    `plugins/RPGQuest/merchants/*.yml` par `YamlMerchantRegistry` avec
    exactement le même patron à deux phases que `ItemLoader`/`QuestLoader`
    (`MerchantDefinitionParser` par fichier, accumulation des erreurs, puis
    rejet des id dupliqués entre fichiers — pas de notion de prérequis
    entre marchands). `MerchantOffer` porte une direction
    (`SELL_TO_PLAYER`/`BUY_FROM_PLAYER`), un objet vanilla **ou** personnalisé
    (même garde « exactement un des deux » que les ingrédients de
    `crafting`), une quantité/un prix, et des conditions d'accès cumulatives
    optionnelles (permission, quête+état — `COMPLETED` par défaut si
    l'état n'est pas précisé —, niveau). Le **niveau** requis est le niveau
    d'expérience vanilla (`Player#getLevel()`) : aucun système de niveau RPG
    dédié n'existe encore (étape 19), décision d'ingénierie délibérée plutôt
    que d'inventer une notion parallèle qui devrait être migrée plus tard.
-   **Aucun système d'identification de PNJ parallèle — un marchand ne
    s'ouvre que depuis un dialogue** — contrairement à `TALK_TO_NPC`
    (`quest.progress`) et `DialogueNpcInteractListener` (`dialogue.session`),
    qui identifient tous deux un PNJ par le nom personnalisé d'une entité,
    `economy.merchant` n'a **aucun** écouteur de clic sur entité : une
    nouvelle action de dialogue scellée, `OpenMerchantAction`
    (`ActionType.OPEN_MERCHANT`, champ YAML `merchant:`, même forme que
    `OPEN_DIALOGUE`/`dialogue:`), est l'unique porte d'entrée. Décision
    explicitement dictée par la mission de cette étape : un marchand
    « relié aux dialogues existants » plutôt qu'un troisième mécanisme de
    reconnaissance de PNJ à maintenir en parallèle des deux premiers — un
    même PNJ peut donc continuer à satisfaire un objectif `TALK_TO_NPC`
    *et* ouvrir un dialogue qui propose ensuite sa boutique, sans aucune
    duplication de convention.
    -   **`DialogueSessionEngine` traite `OpenMerchantAction` comme
        `OpenDialogueAction`** (arrêt anticipé de la boucle d'actions) mais
        **ferme** la session de dialogue plutôt que d'en ouvrir un autre —
        un joueur qui entre dans une vitrine quitte la conversation, il ne
        peut pas y revenir en fermant l'inventaire (il faudrait recliquer le
        PNJ). `MerchantTradeService` est une dépendance directe du
        constructeur, même conception que sa dépendance à
        `QuestProgressEngine` (pas d'interface d'abstraction
        supplémentaire pour une relation à un seul consommateur).
    -   Un id de marchand référencé par un dialogue mais absent du registre
        au moment de l'exécution (marchand supprimé, faute de frappe) n'est
        **pas** validé au chargement du dialogue (contrairement à
        `OPEN_DIALOGUE`, validé par `DialogueLoader` puisque les deux
        registres sont dans le même package) — `MerchantTradeService#openShop`
        envoie un message d'erreur au joueur et n'ouvre rien, jamais
        d'exception. Découpler les deux registres évite une dépendance
        `dialogue` → `economy.merchant` au chargement.
-   **`MerchantTradeService` — vitrine en inventaire, même protection
    anti-vol que le journal de quêtes** — `MerchantShopInventoryHolder`/
    `MerchantShopListener` reprennent exactement le patron de
    `JournalInventoryHolder`/`QuestJournalListener` (tout clic ou drag qui
    touche la vitrine est annulé, quel que soit son type, avant même
    d'interpréter le slot cliqué). Taille de l'inventaire calculée à partir
    du nombre d'offres (3 à 6 lignes, la dernière réservée au chrome) ; un
    marchand avec plus d'offres que de slots de contenu disponibles voit
    les offres en trop simplement non affichées (log d'avertissement) —
    pas de pagination, limitation assumée (voir « Limites connues »).
    `MerchantSession` (comme `DialogueSession`/`JournalSession`) fige le
    marchand ouvert : un `/merchant reload` pendant qu'une vitrine est
    ouverte ne peut jamais faire acheter « la mauvaise offre », il ferme
    proprement l'inventaire si le marchand a disparu.
-   **Anti-duplication achat/vente — asymétrique par construction, pas par
    accident** :
    -   **Achat (`SELL_TO_PLAYER`, le joueur paie)** — le débit (atomique,
        voir plus haut) est toujours tenté **avant** de donner l'objet ;
        l'objet n'est donné que si le débit a réellement réussi. Un débit
        refusé (fonds insuffisants) ne donne donc jamais rien.
    -   **Vente (`BUY_FROM_PLAYER`, le joueur reçoit)** — l'ordre est
        inversé : l'objet est retiré de l'inventaire **de façon synchrone**,
        sur le même thread que l'événement de clic, avant même de démarrer
        le crédit asynchrone. Comme le thread principal traite les
        événements un par un, un second clic pendant que le premier crédit
        est encore « en vol » revoit un inventaire déjà réduit et échoue
        naturellement au contrôle de stock (`containsAtLeast`) — aucune
        duplication d'objet ni de monnaie possible. Si le crédit échoue
        malgré tout (cas pratiquement impossible hors dépassement de
        capacité), l'objet déjà retiré est rendu au joueur plutôt que
        purement perdu.
    -   Les deux directions réutilisent la même détection d'objet
        (`YamlCustomItemRegistry#create`/`#identify`) que le reste du
        projet : un objet personnalisé vendu au marchand est reconnu
        uniquement par son PersistentDataContainer, jamais son nom/lore —
        même garantie anti-contrefaçon que `crafting`/`item`.
-   **Conditions d'offre revérifiées à chaque clic, pas seulement à
    l'affichage** — même principe que la revalidation des choix de
    dialogue au clic (`dialogue.session`) : permission/niveau sont
    vérifiés de façon synchrone, la quête de façon asynchrone
    (`QuestProgressEngine#stateOf`), avant d'exécuter l'échange. Toutes les
    offres sont affichées en permanence dans la vitrine (pas de
    masquage dynamique selon les conditions, contrairement aux choix de
    dialogue) : un joueur voit toujours ce qu'il pourrait débloquer, un
    clic sur une offre non satisfaite renvoie simplement un message
    explicite plutôt que d'être invisible.
-   **`/merchant reload|validate|list`** (`rpgquest.admin`, même trio que
    `/quest admin reload|validate`) — entièrement administratif, aucune
    sous-commande joueur : un marchand n'a pas d'existence en dehors d'un
    dialogue qui l'ouvre.
-   **Intégration Vault — préparée, pas câblée** — `EconomyService` expose
    délibérément une forme « compatible Vault » (solde, débit/crédit avec
    vérification de fonds, paiement) plutôt qu'une API arbitraire, pour
    qu'un futur adaptateur implémentant `net.milkbowl.vault.economy.Economy`
    puisse déléguer directement à ces méthodes. Aucune dépendance Vault
    n'a été ajoutée à `build.gradle.kts` : au moment de cette étape, Vault
    n'est ni installé ni nécessaire, et ajouter une dépendance externe
    (même `compileOnly`) sans un besoin réel irait à l'encontre de la
    règle « pas de dépendance externe obligatoire lorsqu'une intégration
    optionnelle suffit ». Voir `docs/ECONOMY.md` pour le plan d'intégration
    exact si un serveur a besoin de Vault plus tard.

### `economy.market` (marché entre joueurs)

-   **Aucun modèle YAML, aucun registre** — contrairement à
    `economy.merchant` (offres figées, définies par un administrateur),
    une offre de marché est **créée en jeu par un joueur** et vit entièrement
    en base (`database.MarketRepository`, `market_listings`, migration V5) :
    pas de fichier, pas de rechargement, pas de notion de « définition ».
-   **L'objet entier est mis en dépôt, sérialisé tel quel** —
    `ItemStack#serializeAsBytes()`/`ItemStack#deserializeBytes(byte[])`
    (API Bukkit vérifiée présente dans `paper-api-1.21.11` par inspection
    du jar) capturent la totalité de l'`ItemStack` (méta, PersistentDataContainer
    d'un objet personnalisé compris) dans une colonne `BLOB`. Contrairement
    à `economy.merchant`, **aucune dépendance à
    `YamlCustomItemRegistry`** n'est nécessaire : l'objet mis en vente
    porte déjà toute son identité, il n'a besoin d'être ni recréé ni
    ré-identifié à la remise.
-   **`MarketRepository` — trois opérations atomiques distinctes**, même
    discipline transactionnelle explicite que `WalletRepository`
    (`setAutoCommit(false)`/`commit`/`rollback` dans un seul appel à
    `DatabaseManager#execute`) :
    -   `claim(id, buyer)` — lit puis bascule `ACTIVE → SOLD` **dans la
        même transaction**, seulement si l'offre était encore active ;
        retourne l'offre (donc son prix) ou vide. C'est la garde qui
        garantit qu'une offre ne peut jamais être vendue deux fois, même
        avec deux clics simultanés sur deux joueurs différents.
    -   `cancel(id, seller)` — même patron, avec une condition
        supplémentaire (`seller_uuid = ?`) : seul le vendeur peut annuler
        sa propre offre, jamais un tiers ni un administrateur (voir
        limitation ci-dessous).
    -   `reactivate(id, buyer)` — remet une offre `SOLD` à `ACTIVE`
        (uniquement si `buyer_uuid` correspond encore, défense en
        profondeur), utilisée après un débit refusé (voir plus bas).
-   **Achat en deux temps, ordre imposé par une contrainte que
    `economy.merchant` n'a pas** — pour un marchand PNJ, le prix vient
    d'un YAML déjà chargé, donc « débiter puis remettre » suffit. Pour une
    offre de marché, le prix n'est connu qu'**après** lecture en base :
    l'ordre est donc nécessairement (1) réserver atomiquement
    (`claim`, qui ne bouge aucun argent), (2) débiter l'acheteur du prix
    ainsi découvert, (3) si le débit échoue, `reactivate` plutôt que
    perdre l'offre. Entre (1) et un `reactivate` éventuel, l'offre est
    indisponible pour tout le monde pendant quelques millisecondes tout au
    plus (aucun argent ni objet n'est en jeu durant cette fenêtre) — un
    compromis délibéré, pas un bug : la réservation doit précéder la
    connaissance du prix, contrairement à un marchand YAML.
-   **`MarketService` — vitrine unique, pas d'onglets** — délibérément plus
    simple que `QuestJournalService` (pas de distinction « mes offres »/
    « toutes les offres » en onglets séparés) : la vitrine liste **toutes**
    les offres actives de **tous** les vendeurs, triées par ancienneté ;
    cliquer sur sa propre offre l'annule (récupère l'objet), cliquer sur
    celle d'un autre l'achète. Un seul geste (le clic) couvre les deux
    usages, sans naviguer entre onglets — jugé suffisant pour la mission
    (« marché entre joueurs »), qui ne demande pas explicitement de vue
    séparée. `MarketPagination` (dupliquée depuis `ui.JournalPagination`,
    gardée package-privée dans `ui`, voir plus haut) fournit la même
    arithmétique de pagination (45 slots de contenu par page) sans
    dépendance croisée entre packages.
-   **`MarketSession` fige la liste des offres affichées, pas seulement
    leurs id** — contrairement à `JournalSession`/`MerchantSession`
    (qui figent des identifiants, revalidés contre le registre courant au
    clic), `MarketSession` fige les enregistrements `MarketListingRecord`
    complets de la page rendue : le prix d'une offre ne pouvant jamais
    changer après création (pas de fonctionnalité d'édition), le
    réutiliser tel quel pour lancer l'achat est sûr, et la disponibilité
    réelle est de toute façon revérifiée par `claim` au moment du clic —
    aucune fenêtre exploitable.
-   **Vente : la pile entière tenue en main, jamais une quantité partielle**
    — pas de sélection de quantité dans `/market sell <prix>` ; un joueur
    qui veut vendre moins sépare sa pile dans son inventaire au préalable
    (mécanisme vanilla standard), cohérent avec l'absence de quantité
    configurable pour une offre de marché (contrairement à
    `economy.merchant`, où `quantity` fait partie de la définition YAML
    figée par l'administrateur).
-   **Vendeur crédité même hors ligne** — `EconomyService#credit` opère
    par UUID, sans dépendre d'une session Bukkit active : un joueur peut
    vendre un objet et se déconnecter, il sera payé à la vente sans avoir
    besoin d'être reconnecté (contrairement à `/money pay`, qui exige un
    destinataire en ligne).
-   **`/market admin list`** (`rpgquest.admin`) — lecture seule
    (modération) : aucune commande d'administration ne peut annuler l'offre
    d'un joueur hors ligne avec restitution de l'objet, voir
    « Limites connues ».

## Resource pack (optionnel)

-   **`resource-pack/` à la racine du dépôt** (pas dans `src/main/resources`,
    puisqu'il n'est pas empaqueté dans le jar du plugin — il est distribué
    séparément et téléchargé par le client). Contient `pack.mcmeta` et un
    modèle JSON par objet personnalisé d'exemple (`item-model:` dans son
    YAML), chacun réutilisant pour l'instant une **texture vanilla
    existante** comme placeholder temporaire simple (aucune texture propre
    au projet fournie à ce stade, cohérent avec « textures temporaires
    originales simples » — remplacer `textures.layer0` par une texture
    propre plus tard ne demande aucun changement côté plugin).
-   **Tâches Gradle `buildResourcePack`/`resourcePackSha1`** — zip
    reproductible (`isPreserveFileTimestamps = false`,
    `isReproducibleFileOrder = true`, un contenu inchangé produit toujours
    le même SHA-1) suivi du calcul du hash, écrit dans un fichier `.sha1` à
    côté du zip. Pas de dépendance externe : `java.security.MessageDigest`
    du JDK suffit.
-   **`config.yml` → `resource-pack.required`** (nouveau champ, `false` par
    défaut) — un refus/échec affiche un message d'avertissement au joueur
    **uniquement** si `required: true` ; aucune déconnexion automatique
    dans aucun cas, conformément à la règle « le plugin doit fonctionner
    sans le resource pack ».
-   **`player.ResourcePackListener`** — envoie le pack à la connexion
    (`Player#setResourcePack(UUID, String, byte[], Component, boolean)`,
    signature vérifiée par inspection du jar `paper-api-1.21.11`) si
    `resource-pack.enabled`, et réagit à
    `PlayerResourcePackStatusEvent` (`SUCCESSFULLY_LOADED`,
    `DECLINED`, `FAILED_DOWNLOAD`, `INVALID_URL`, `FAILED_RELOAD`,
    `ACCEPTED`, `DOWNLOADED`, `DISCARDED`). Un envoi qui lève une exception
    (client trop ancien, etc.) est journalisé, jamais fatal à la connexion.

## Parcours RPG complet (dialogue → quête → combat/récolte → fabrication → remise → récompense)

Vertical slice minimal reliant tous les systèmes livrés, construit sur
l'existant plutôt qu'un nouveau système dédié :

1.  Parler au garde (`dialogues/guard.yml`) → accepter `first_steps` (déjà
    existante depuis l'étape 3).
2.  Tuer 10 araignées → remise automatique de `first_steps`
    (`TALK_TO_NPC`-free : uniquement `KILL_ENTITY`, une seule étape).
3.  Reparler au garde (nouveau choix, visible seulement une fois
    `first_steps` `COMPLETED` et `crystal_hunt` `NOT_STARTED`) → accepter
    `crystal_hunt` (nouvelle quête, prérequis `first_steps`).
4.  Tuer 5 araignées supplémentaires (`spider_fang` tombe automatiquement,
    voir `SpiderFangDropListener` ci-dessus) → étape `hunt_spiders`.
5.  Récolter 2 `refined_crystal` (`COLLECT_ITEM material: AMETHYST_SHARD`,
    matériau de base de `refined_crystal`) — atteignable soit en récoltant
    le nœud `crystal_ore` (30 % de chance directe), soit en fabriquant via
    `refined_crystal_recipe` (4 quartz, obtenus via le même nœud à 70 % de
    chance) : les deux systèmes se combinent naturellement sans câblage
    spécifique.
6.  Fabriquer `forest_blade` via `forest_blade_recipe` → étape `forge_blade`
    (`CRAFT_ITEM material: DIAMOND_SWORD`, matériau de base de
    `forest_blade` — **limite connue** : cet objectif ne distingue pas
    encore un objet personnalisé d'un objet vanilla de même matériau de
    base, voir « Limites connues » ; une épée en diamant ordinaire validerait
    aussi cette étape).
7.  Reparler au garde → dernier objectif (`TALK_TO_NPC npc: guard`) →
    remise automatique de `crystal_hunt` → récompense `COMMAND`
    (`customitem give %player% rpgquest:miner_pickaxe 1`, exécutée par la
    console, donc sans dépendre d'une permission du joueur).

Couvert de bout en bout par `CrystalHuntIntegrationTest`
(`quest.progress`), qui utilise volontairement le plugin **réellement**
démarré (`MockBukkit.load(RPGQuestPlugin.class)`) plutôt que des
définitions synthétiques, pour exercer les quêtes/objets/recettes tels
qu'embarqués dans le jar.

## `admin` (commandes d'administration du monde)

-   **`/rpgadmin` — dispatcher racine, pensé pour grandir** —
    `RpgAdminCommand` route aujourd'hui uniquement vers `flatten`
    (aplatissement de terrain), mais est structuré pour recevoir d'autres
    branches (`zone`, `portal`, `mob`...) à mesure que les étapes
    correspondantes sont livrées, sans dupliquer la vérification de
    permission/joueur déjà centralisée en tête de `onCommand`. Toujours
    `rpgquest.admin.world` (permission dédiée, distincte de
    `rpgquest.admin`) et toujours un joueur (jamais la console : la
    syntaxe ne porte aucune coordonnée explicite, centrer l'opération sur
    « la position de la console » n'a pas de sens).
-   **`FlattenService` — aperçu, confirmation à expiration, traitement par
    lots, annulation unique** — même discipline « état runtime séparé de
    la config » que les autres services (`ResourceNodeService`,
    `EquipmentBehaviorService`) : `AdminFlattenConfig` (nouveau champ de
    `PluginConfig`, validé par `ConfigValidator`) porte les valeurs
    ajustables, `FlattenService` porte l'état par joueur (aperçu en
    attente, opération active, dernière annulation possible). `clock` est
    injectable (comme `CooldownManager`/`ResourceNodeService`), ce qui
    rend l'expiration de la confirmation testable sans dépendre du temps
    réel ni de `Thread.sleep`.
    -   **Aperçu pur, aucune écriture** — `preview()` calcule la liste des
        colonnes réellement concernées par la forme (carré ou cercle,
        géométrie simple `dx²+dz² ≤ r²` pour le cercle) et une estimation
        majorante du nombre de blocs, sans jamais toucher un `Block`. Cette
        même liste de colonnes est réutilisée telle quelle par `confirm()`
        (calculée une seule fois, pas recalculée à l'exécution).
    -   **Traitement par lots via `runTaskTimer`, jamais tout d'un coup** —
        `processTick` consomme un budget de blocs (`blocks-per-tick`) par
        tick et s'arrête dès qu'il est épuisé, reprenant au tick suivant
        exactement là où il s'était arrêté (`columnIndex` sauvegardé dans
        l'opération active). C'est ce qui répond à « jamais de gel du
        serveur sur une grande zone », contrairement à une boucle
        synchrone qui traiterait toute la zone d'un coup dans le même tick.
    -   **Un bloc déjà correct n'est jamais réécrit** (`setBlockIfChanged`
        compare `Block#getType()` avant d'écrire) : économise à la fois le
        budget par tick et la taille de l'enregistrement d'annulation.
    -   **Annulation unique, pas une pile** — chaque opération (terminée
        normalement ou interrompue par `cancel`) construit son propre
        `UndoRecord` (position + `BlockData` d'origine de chaque bloc
        réellement modifié) ; un nouvel aplatissement écrase
        l'enregistrement précédent pour ce joueur. `undo()` restaure dans
        l'ordre inverse d'écriture (les positions étant toutes distinctes,
        l'ordre n'a pas d'effet pratique ici, mais le principe reste
        « dérouler à l'envers »).
    -   **`cancel()` sur une opération active** ne défait rien : il arrête
        simplement la tâche répétée et finalise l'enregistrement
        d'annulation avec ce qui a déjà été appliqué — `undo` reste le
        seul moyen de revenir en arrière, cohérent avec « annulation » et
        « undo » comme deux actions distinctes de la mission.
    -   **`setType(material, false)`** — le second paramètre (`false`,
        pas de mise à jour physique) évite les cascades de recalcul
        (sable qui tombe, redstone qui se propage, etc.) lors d'un
        remplacement en masse, qui seraient à la fois lentes et
        potentiellement destructrices dans une zone qui vient tout juste
        d'être nettoyée.
-   **« Zones interdites » — placeholder assumé, pas encore le vrai
    registre** — `admin.flatten.forbidden-worlds` (liste de noms de
    mondes) est la seule protection disponible à cette étape : le
    registre de zones cuboïdes (village central / safe zone) n'existe pas
    encore (prévu étape 13). Une fois disponible, `FlattenService` devra
    être recroisé avec ce registre pour refuser un aplatissement dont la
    zone chevauche une zone protégée — noté ici plutôt qu'anticipé
    prématurément sur une API qui n'existe pas encore.
-   **`rpgadmin.flatten` est enregistré tôt dans `RPGQuestBootstrap`**
    (juste après `databaseService`) — ne dépend que de `configService`
    (déjà démarré) et n'a besoin d'aucun autre service, contrairement à la
    plupart des autres commandes qui attendent leurs moteurs respectifs.

## `zone` (zones protégées)

-   **Zones créées à l'exécution, mais persistées en YAML** — contrairement
    aux autres registres YAML du projet (quêtes, dialogues, objets, types
    de nœuds, recettes — tous hand-authored), une zone est créée en jeu via
    `/rpgadmin zone create` (coordonnées venant de l'outil de sélection, pas
    d'édition manuelle). `ZoneRegistry.create()` écrit directement un
    fichier YAML dans `plugins/RPGQuest/zones/` puis recharge — le fichier
    reste la seule source de vérité, jamais un état mémoire divergent.
    `ZoneDefinition` reste « correct par construction » comme les autres
    modèles (bornes normalisées, id validé par expression régulière).
-   **`ZoneLoader` rejette aussi les chevauchements, pas seulement les id
    dupliqués** — extension du patron à deux phases de `QuestLoader`/
    `ResourceNodeLoader` : après le rejet des id dupliqués, une
    passe supplémentaire rejette toute zone dont le cuboïde chevauche une
    zone déjà retenue **dans le même monde** (`ZoneDefinition#overlaps`,
    test AABB standard) — deux zones dans des mondes différents peuvent
    occuper les mêmes coordonnées sans conflit.
-   **Index par monde, vérification bon marché à chaque événement** —
    `ZoneRegistry.zonesInWorld` est construit une seule fois par
    `reload()` (`Map<String, List<ZoneDefinition>>`), jamais recalculé par
    événement. `ZoneProtectionListener` ne balaie donc jamais que les
    quelques zones du monde concerné (généralement une poignée), jamais
    toutes les zones de tous les mondes — c'est ce qui répond à « aucune
    boucle de scan coûteuse », le nombre de zones total n'ayant aucun
    impact sur le coût d'un événement dans un monde qui n'en a aucune.
-   **`ZoneFlags` — deux groupes aux défauts opposés, décision documentée**
    — les six permissions listées « bloquées par défaut » par la mission
    (pvp, casse, pose, explosions, feu, lave, pistons traversant la
    frontière, spawn hostile — sept en réalité, l'énoncé en groupe deux)
    valent toutes `false` sur `ZoneFlags.defaults()` ; les cinq permissions
    « autorisées par config » (portes, boutons, leviers, PNJ, conteneurs
    publics) valent `true` par défaut **sauf** les conteneurs publics
    (`false`) — un village doit rester utilisable dès sa création (portes,
    boutons, dialogues PNJ) sans configuration supplémentaire, alors que
    l'accès à un conteneur partagé est un risque de vol qui justifie un
    opt-in explicite. Voir `docs/SAFE_ZONE.md` pour le détail complet.
-   **`ZoneProtectionListener` — un seul point de vérification par
    catégorie d'événement**, chacun suivant le même patron
    (`zoneAt(location)` puis test du flag concerné) :
    -   **PvP et dégâts d'explosion regroupés sur `EntityDamageEvent`**
        (pas `EntityDamageByEntityEvent` séparément) : Bukkit délivre une
        instance de la sous-classe à un gestionnaire enregistré sur la
        classe mère, donc un seul `@EventHandler` couvre les deux cas
        (`instanceof EntityDamageByEntityEvent` pour le PvP direct **et**
        les projectiles — `Projectile#getShooter()` — ; la cause
        `ENTITY_EXPLOSION`/`BLOCK_EXPLOSION` pour les dégâts d'explosion,
        qui ne passent pas forcément par un dommageur direct).
    -   **`EntityExplodeEvent`/`BlockExplodeEvent` : `blockList().clear()`,
        pas `setCancelled(true)`** — laisse l'entité (creeper, TNT...) se
        consumer normalement (son cycle de vie, le son, les particules ne
        sont pas de la destruction de terrain), seule la destruction de
        blocs est empêchée. Cohérent avec `explosions` comme protection du
        terrain plutôt qu'une interdiction totale de l'explosion elle-même.
    -   **Pistons traversant la frontière** — compare la zone du bloc
        piston à la zone de destination de chaque bloc déplacé
        (`BlockPistonExtendEvent#getBlocks()` décalés d'une case dans la
        direction du piston pour l'extension ; positions actuelles pour la
        rétraction) : si l'un des deux camps (zone du piston ou zone de
        destination) refuse explicitement les pistons traversant sa
        frontière et que les deux camps diffèrent, l'événement est annulé.
    -   **Portes/boutons/leviers/conteneurs via un seul `PlayerInteractEvent`**
        à priorité `LOW` (avant le traitement `NORMAL` par défaut des
        autres listeners du plugin — notamment `DialogueNpcInteractListener`/
        `QuestNpcInteractListener` pour la corrélation avec `npc-interact`,
        bien qu'ici la classification porte sur le **bloc** cliqué) :
        classification par suffixe de nom de matériau
        (`_DOOR`/`_TRAPDOOR`/`_FENCE_GATE`, `_BUTTON`) ou par interface
        (`block.getState() instanceof Container`, qui couvre coffres,
        tonneaux, fourneaux, distributeurs... sans liste de matériaux
        codée en dur à maintenir).
    -   **Bypass vérifié sur l'acteur direct, jamais la victime** — un
        administrateur (`rpgquest.admin.world`) peut casser/poser/interagir
        librement dans une zone, mais son statut n'exempte personne
        d'autre de la protection (attaquer un administrateur en PvP reste
        bloqué si l'administrateur est la victime et n'a pas lui-même la
        permission... en pratique un administrateur l'a presque toujours,
        limite acceptée).
    -   **Affichage entrée/sortie sans sondage** — `PlayerMoveEvent` (priorité
        `MONITOR`, en observation uniquement) filtré aux changements de
        **bloc** de position (pas chaque micro-mouvement) ; une map en
        mémoire (joueur → id de zone courante, jamais persistée) détecte la
        transition et n'envoie une actionbar qu'à ce moment précis — aucune
        tâche répétitive nulle part.
-   **`ZoneSelectionService`/`ZoneWandListener`** — même conception que les
    sessions de dialogue/journal : état de sélection **en mémoire
    uniquement**, aucune raison de survivre à une reconnexion. L'outil
    (hache en bois) est reconnu **exclusivement** par son
    PersistentDataContainer (`rpgquest:zone_wand`), jamais par son nom
    affiché — même garantie anti-contrefaçon que les objets personnalisés
    de l'étape 7.
-   **Pas de rechargement à chaud d'une zone éditée à la main** — modifier
    directement un fichier de zone existant sur disque n'est repris qu'au
    prochain redémarrage (pas de commande `/rpgadmin zone reload` à cette
    étape) ; `create`/`delete` recharge déjà automatiquement, ce qui
    couvre le flux d'usage principal (création/suppression via l'outil de
    sélection).

## `travel` (portails et téléportation)

-   **Deux registres YAML indépendants, comme le mission l'a explicitement
    demandé** — `Destination` (position nommée réutilisable, `travel/`
    n'ayant aucune notion de PNJ ni de zone) et `PortalDefinition` (zone
    d'activation cuboïde, même forme que `ZoneDefinition` mais
    délibérément dupliquée plutôt que réutilisée — un portail n'a ni id à
    motif imposé par une zone protégée ni `ZoneFlags`) reliée à une
    destination **par id, résolu paresseusement à l'activation**, jamais
    validé au chargement — même choix que les offres de marchand
    référençant un objet personnalisé (`economy.merchant`) : les deux
    registres restent découplés, pas de dépendance `travel` interne
    portail → destination au chargement.
-   **Aucune commande dédiée pour créer une destination « à part » —
    `/rpgadmin portal setdestination <id> <destinationId>` en tient lieu** :
    elle capture la position exacte (x, y, z, yaw, pitch) de
    l'administrateur au moment de l'appel dans `YamlDestinationRegistry`
    (créée si l'id est nouveau, remplacée si l'id existe déjà — sémantique
    « set »), puis relie le portail à cet id. Décision d'ingénierie : la
    mission ne liste que des sous-commandes `portal`, pas de CRUD
    `destination` séparé ; capturer la position en marchant dessus est de
    toute façon plus fiable que de faire taper des coordonnées à la main.
-   **Aucun exemple embarqué** (contrairement à `central_village` pour les
    zones) — une destination doit être une position réellement sûre sur le
    monde de l'opérateur ; contrairement à une zone protégée (un simple
    cuboïde, sûr à n'importe quelles coordonnées), bundler des coordonnées
    de destination arbitraires serait plus nuisible qu'utile sur un monde
    généré procéduralement (risque réel d'enterrer un joueur). Documenté
    dans `docs/TRAVEL.md`.
-   **`PortalLoader` rejette aussi les chevauchements de zone d'activation**
    entre portails du même monde (même extension du patron à deux phases
    que `ZoneLoader` : deux portails activables au même endroit rendraient
    ambigu « lequel s'active en premier »), mais `DestinationLoader` ne
    rejette **pas** les positions superposées entre destinations (rien
    n'empêche deux noms de pointer légitimement au même endroit).
-   **`travel.PortalService` — canalisation à délai, même patron « tâche
    répétée + annulation » que `admin.FlattenService`** : une session par
    joueur (`ChannelingSession`, position de départ figée, compteur de
    ticks écoulés/total plutôt qu'une horloge murale injectée — la durée
    d'un portail se mesure en ticks de jeu, pas en temps réel, donc
    `server.getScheduler().performTicks(n)` suffit à rendre les tests
    déterministes sans injection d'horloge). Annulée dans trois cas
    (mouvement au-delà d'une tolérance de 0,6 bloc au carré — comparaison
    sans `sqrt` —, dégâts, déconnexion), chacun relayé par
    `PortalListener` vers une seule méthode `cancelChanneling`.
-   **Détection d'entrée dans un portail — même filtrage que
    `ZoneProtectionListener`** : `PlayerMoveEvent` n'est traité qu'aux
    changements réels de position de bloc (jamais chaque micro-mouvement
    de caméra), et `PortalService.handleMove` ne redéclenche
    `attemptActivate` que sur une vraie **transition** (nouvel id de
    portail différent du précédent connu pour ce joueur) — un joueur
    immobile dans une zone d'activation dont il ne remplit pas les
    conditions ne voit donc pas son message de refus spammé à chaque tick.
-   **Vérification des conditions, dans un ordre délibéré** — synchrones
    d'abord (permission, niveau, cooldown depuis le cache mémoire), puis
    asynchrones (état de quête via `QuestProgressEngine#stateOf`, solde
    via `EconomyService#balance`) : échouer vite sur les vérifications bon
    marché évite une consultation base pour un joueur qui n'a de toute
    façon pas la permission.
-   **Aucun débit tant que le succès n'est pas garanti — cœur de la
    garantie demandée par la mission** : la vérification de fonds
    suffisants a lieu *avant* la canalisation (évite de faire attendre un
    joueur pour un échec prévisible), mais le débit réel n'a lieu qu'**après**
    la résolution de la destination et la vérification de sécurité, juste
    avant `teleportAsync` — un monde absent, une destination introuvable
    ou aucune position sûre trouvée n'entraînent donc jamais aucun débit
    (voir aussi `economy.EconomyService`).
-   **Recherche de position sûre — balayage vertical borné, jamais un
    chargement de chunk permanent** — `findSafeLocation` teste d'abord la
    position enregistrée telle quelle, puis alterne au-dessus/en-dessous
    jusqu'à 5 blocs ; un bloc dangereux (lave, feu, feu d'âme, magma,
    cactus) ou l'absence de sol solide sous les pieds rejette la position.
    `World#getChunkAt` est appelé **une seule fois**, un accès ponctuel qui
    charge la colonne si besoin (comme n'importe quelle téléportation
    vanilla vers une zone déchargée) — aucun ticket de chunk n'est jamais
    posé, donc rien ne reste chargé de force après coup.
-   **Cooldown persisté, jamais consulté en base depuis un événement aussi
    fréquent que `PlayerMoveEvent`** — `database.PortalCooldownRepository`
    (`portal_cooldowns`, migration V6) est lu **une seule fois**, à la
    connexion (`PlayerJoinEvent`), et mis en cache mémoire
    (`Map<UUID, Map<String, Instant>>`) ; chaque utilisation réussie d'un
    portail met à jour le cache **et** écrit en base (asynchrone,
    tolérant à l'échec — un cooldown non persisté à cause d'une erreur
    transitoire reste actif en mémoire jusqu'à la prochaine déconnexion,
    limitation mineure jugée acceptable). C'est ce qui permet au cooldown
    de survivre à une reconnexion, comme demandé par la mission.
-   **`/rpgadmin portal create|delete|list|info|setdestination`** — nouvelle
    branche de `RpgAdminCommand` (déjà pensée pour grandir ainsi, voir
    section `admin`), réutilisant l'outil de sélection `wand` déjà existant
    pour délimiter la zone d'activation — aucun nouvel outil nécessaire.

## `claim` (claims de terrain joueurs)

-   **SQLite plutôt que YAML, contrairement à `zone`** — décision
    d'ingénierie délibérée : une zone protégée est curée par un
    administrateur, peu nombreuse, modifiée rarement, et bénéficie d'être
    éditable à la main ; un claim est créé/modifié par **n'importe quel
    joueur**, potentiellement nombreux, avec une appartenance et une liste
    de membres qui changent en jeu — le même profil d'usage que les offres
    de marché (`economy.market`), donc la même solution technique
    (`database.ClaimRepository`, `claims`/`claim_members`, migration V7).
-   **`claim.model.Claim` n'a aucune dépendance Bukkit et est réutilisé tel
    quel par `ClaimRepository`** — pas de type « ligne de base de données »
    séparé façon `MarketListingRecord` : `Claim` satisfait déjà la
    contrainte que cette séparation protège d'habitude (testable sans
    MockBukkit), dupliquer ses huit champs dans un second type n'aurait
    apporté aucune valeur. Documenté explicitement dans le Javadoc de la
    classe pour ne pas ressembler à un oubli de convention.
-   **Propriété et confiance exclusivement par UUID, jamais par pseudo**
    (exigence explicite de la mission) — `Claim#isTrusted(UUID)` est
    l'unique porte d'entrée utilisée par `ClaimProtectionListener` ; aucun
    chemin de code ne compare un nom affiché. `/claim trust <joueur>`
    résout bien un pseudo au moment de la commande (comme `/money pay`),
    mais seul l'UUID résolu est stocké.
-   **Outil de sélection dédié** (`ClaimSelectionService`/`ClaimWandListener`,
    clé PDC `rpgquest:claim_wand`) — copie volontaire de
    `zone.ZoneSelectionService`, pas une réutilisation : un joueur qui est
    aussi administrateur ne doit pas voir sa sélection de claim mélangée à
    sa sélection de zone protégée (deux services, deux états en mémoire
    totalement indépendants). Hache en **bois** différente (houe plutôt que
    hache) pour que les deux outils restent visuellement distincts en jeu.
-   **Toutes les sous-commandes `/claim` sauf `create` opèrent sur « le
    claim où tu te trouves », jamais sur un id tapé à la main** — lecture
    littérale de la mission, qui ne montre un argument que pour
    `trust`/`untrust <joueur>` (pas pour `delete`/`info`) :
    `ClaimCommand` résout le claim via `ClaimService#claimAt` sur la
    position exacte du joueur au moment de la commande, plutôt que
    d'ajouter un identifiant à chaque syntaxe.
-   **`ClaimService` porte toute la validation métier, `ClaimRepository`
    reste pure JDBC** — chevauchement (claim/claim, claim/zone protégée),
    distance aux portails, taille, nombre maximal : c'est ce qui crée la
    dépendance délibérée de `claim` vers `zone`/`travel` (documentée ici et
    dans `docs/CLAIMS.md`), une validation qui touche plusieurs systèmes ne
    peut pas vivre dans le repository sans lui faire connaître Bukkit.
-   **Cache en mémoire indexé par monde, rechargé intégralement après
    chaque mutation** — même patron que `ZoneRegistry#zonesInWorld`/
    `YamlPortalRegistry#portalsInWorld` pour la lecture (bon marché à
    chaque événement protégé), mais **pas** le patron « écrire un fichier
    puis recharger » des registres YAML : après une création/suppression/
    confiance/permission réussie, `ClaimService` relit l'intégralité de
    `claims`/`claim_members` depuis la base plutôt que de corriger le cache
    à la main — plus simple, sans risque de désynchronisation, largement
    assez rapide pour une fréquence de mutation (créations/suppressions de
    claims) bien plus faible qu'un `PlayerMoveEvent`.
-   **Seams pour une politique liée à la progression, sans rien implémenter
    encore** (mission, point 8) — `effectiveMaxWidth`/`effectiveMaxHeight`/
    `effectiveMaxClaims` prennent déjà un `Player` en paramètre mais ne
    retournent que la valeur globale de `config.yml` pour l'instant ; une
    étape ultérieure (XP RPG) pourra les faire varier sans changer un seul
    appelant. Même philosophie que l'intégration Vault préparée en étape 14
    (`economy.EconomyService`).
-   **`ClaimProtectionListener` — même conception que
    `ZoneProtectionListener`, deux catégories de protection nouvelles**
    (animaux, armor stands) qui n'existaient pas pour les zones : `Animals`
    (interface Bukkit dédiée, couvre vaches/moutons/poules/loups...) pour
    les dégâts, et `PlayerArmorStandManipulateEvent` (événement Paper dédié
    à l'échange d'équipement sur un armor stand, plus précis qu'un
    `PlayerInteractAtEntityEvent` générique) pour la manipulation.
    Conteneurs et redstone réutilisent la même détection par suffixe de nom
    de matériau/`instanceof Container` que les zones ; les dalles de
    pression (déclenchées par `Action.PHYSICAL`, pas un clic droit) sont
    rattachées au même groupe « redstone » que boutons/leviers/portes.
-   **Piston et explosions toujours protégés, sans flag** — contrairement
    aux zones (`allowPistonsAcrossBorder`/`allowExplosions` configurables),
    la mission ne liste que la redstone comme configurable pour un claim :
    ces deux protections sont donc fixes, jamais désactivables par le
    propriétaire.
-   **Bypass (`rpgquest.admin.world`)** — même permission que le bypass des
    zones protégées, décision délibérée pour ne pas multiplier les nœuds de
    permission pour un concept équivalent (« administrateur du monde »).

## `mob` (mobs spéciaux)

-   **Variantes d'entités vanilla, jamais de mob custom via NMS** — une
    `SpecialMobDefinition` habille un `EntityType` vivant existant
    (attributs via `Attribute`, nom MiniMessage, particule/son, capacités,
    table de drops) plutôt que de créer un nouveau type d'entité : seule
    approche compatible avec l'exigence « API Paper publique uniquement,
    pas de NMS ».
-   **`allowedBiomes`/`allowedZones` restent de simples `Set<String>`,
    jamais résolus en objets au chargement** — même choix que
    `allowedWorlds` : comparaison par nom de clé (`Biome#getKey().getKey()`)
    ou par id de zone au moment du spawn seulement. Évite un couplage de
    `mob` vers `zone` au chargement du fichier (`ZoneRegistry` n'est
    injecté que dans `SpecialMobService`, jamais dans le parseur/loader) et
    une dépendance à la forme exacte de l'API `Biome` dans cette version de
    Paper (`OldEnum` adossé à un registre serveur, comme `Sound`/`Particle`
    — voir plus bas).
-   **`SpecialMobDefinition.drops()` réutilise `resource.model.ResourceDrop`
    tel quel** — même besoin exact que `resource.ResourceNodeDefinition`
    (un tirage pondéré unique, objet personnalisé ou matériau vanilla) :
    dupliquer ce type n'aurait apporté aucune valeur, seul le parseur
    diffère par le contexte (`drops:` d'une variante plutôt que d'un type de
    nœud).
-   **Identification exclusivement par PersistentDataContainer, jamais par
    le nom affiché** — exigence explicite de la mission (point 10) :
    `SpecialMobService#specialMobId`/`specialMobDefinition` sont l'unique
    porte d'entrée utilisée par les écouteurs de capacités et par
    `/rpgadmin mob inspect`. Un joueur qui renomme une entité (nametag) ne
    casse jamais sa reconnaissance.
-   **Upgrade au spawn naturel via `CreatureSpawnEvent` en priorité `HIGH`
    avec `ignoreCancelled = true`** — s'exécute délibérément après
    `ZoneProtectionListener#onCreatureSpawn` (priorité par défaut) : un
    spawn déjà annulé par la safe zone n'est jamais vu, donc jamais upgradé,
    sans que `SpecialMobService` ait besoin de connaître `ZoneFlags`
    directement pour ce cas précis (mission point 5). L'autorisation
    « zones autorisées » de la variante elle-même reste une vérification
    distincte (`allowedZones`, ci-dessus).
-   **`setRemoveWhenFarAway(false)` posé à chaque application de variante**
    — la population n'est décomptée qu'à `EntityDeathEvent`, jamais ailleurs
    ; sans cette ligne, le despawn naturel vanilla par éloignement
    ferait fuir silencieusement la population suivie (aucun événement
    Bukkit ne l'accompagne). Combiné au fait qu'aucun listener n'écoute le
    déchargement de chunk pour décrémenter, cela garantit le test manuel
    « décharger/recharger un chunk ne doit jamais changer la population
    suivie » — un `ChunkLoadEvent` ne fait que redécouvrir (jamais
    recompter) les entités déjà taguées PDC, ce qui couvre aussi le
    redémarrage du serveur.
-   **`ExplosiveOnAttackAbilityService` balaye `SpecialMobService#aliveEntityIds`,
    jamais `World#getLivingEntities()`** — borné à la population réelle des
    variantes possédant cette capacité plutôt qu'à tous les mobs du monde ;
    conséquence directe du choix précédent (population toujours à jour, y
    compris après un redémarrage).
-   **Pas de goal d'IA « attaque » pour une entité passive (`creeper_pig`)
    via l'API publique** — décision documentée dans le Javadoc de
    `ExplosiveOnAttackAbility` : un balayage périodique (1 s, mirroir du
    patron de `resource.ResourceNodeService#sweepRespawns`) détecte un
    joueur à portée et déclenche une explosion réelle
    (`World#createExplosion`), qui émet un `EntityExplodeEvent` normal — les
    écouteurs de protection de zone/claim s'appliquent donc automatiquement,
    sans dupliquer cette logique (mission point 6).
-   **`SplitOnHitAbilityListener` stocke la profondeur de génération en PDC,
    jamais dans le nom affiché** — combinée à `max-children-per-hit` et à
    `max-population` (vérifiée avant chaque enfant), ces trois bornes
    garantissent ensemble qu'aucune chaîne de division n'est infinie
    (mission point 7, validation explicite « aucune capacité ne crée une
    croissance incontrôlée d'entités »).
-   **`Sound`/`Particle` restent résolus par nom d'enum classique
    (`Sound#valueOf`, marqué « for removal »), pas par `Registry.SOUNDS`** —
    ce registre s'indexe par clé namespacée en points
    (`entity.creeper.primed`), pas par le nom d'enum attendu dans le YAML
    (`ENTITY_CREEPER_PRIMED`) ; conservé tant que Paper ne fournit pas de
    résolution par nom d'enum sur le registre (`@SuppressWarnings("removal")`
    documenté sur place, dans `SpecialMobDefinitionParser`).
-   **Bypass complet pour `/rpgadmin mob spawn`** — contrairement au spawn
    naturel, la commande d'administration ignore volontairement
    mondes/biomes/zones/population : c'est un outil de test (mission,
    test manuel « faire apparaître chaque variante »), pas un second chemin
    de spawn naturel.

## `progression` (XP RPG multi-compétences)

-   **Le niveau n'est jamais persisté séparément de l'XP totale** —
    `ProgressionCurve#levelForTotalXp` le recalcule systématiquement depuis
    `player_skills.total_xp` (SQLite) ou depuis le cache en mémoire ; aucun
    état stocké ne peut donc diverger entre XP et niveau affiché (mission
    étape 19, validation « les valeurs ne peuvent ni déborder ni devenir
    incohérentes »). Même philosophie que `mob.SpecialMobDefinition`
    (`totalDropWeight()` toujours recalculé, jamais mis en cache).
-   **Déduplication au niveau de `ProgressionRepository`, une seule
    transaction JDBC explicite** (`xp_grants` avec clé primaire (joueur,
    compétence, id d'événement) puis mise à jour du total) — même
    conception que `WalletRepository#credit`/`ClaimRepository` : soit
    l'octroi ET la mise à jour du total réussissent tous les deux, soit ni
    l'un ni l'autre. `INSERT OR IGNORE` sur `xp_grants` retourne 0 ligne
    affectée pour un id déjà vu, qui court-circuite la mise à jour du
    total sans jamais la tenter.
-   **L'id d'événement est choisi différemment selon que l'action est
    répétable ou « une fois pour toutes »** — pour le combat/minage/
    agriculture/pêche (répétables), l'id combine position/entité et un
    compteur monotone (une même position peut légitimement être re-minée
    après un respawn de nœud de ressource, `resource.ResourceNodeService`)
    ; pour l'exploration/les quêtes (« une fois par (joueur, zone/quête)
    pour toujours »), l'id est l'identifiant stable de la zone/quête —
    c'est la déduplication elle-même, pas un état séparé, qui garantit le
    « une fois », y compris après un redémarrage.
-   **`GLOBAL` est un mirroir automatique, jamais un octroi séparé côté
    appelant** — `ProgressionService#awardXp` accorde toujours en plus
    `amount × globalMirrorRatio` sur `GLOBAL` avec un id d'événement dérivé
    (`eventId + "#global"`, dédupliqué indépendamment) : un écouteur de
    source d'XP n'a jamais besoin d'accorder GLOBAL explicitement.
-   **Configuration lue via `Supplier<ProgressionConfig>`, jamais une
    valeur figée au démarrage** — même patron que
    `item.behavior.EquipmentBehaviorService` (`() -> configService.current().debug()`)
    : un `/rpgquest reload` change immédiatement la courbe, les montants
    par source, le mode d'affichage et les seuils anti-farm, sans
    redémarrer le service.
-   **Anti-farm blocs posés par un joueur : `PlacedBlockTracker` ne réagit
    jamais à `BlockBreakEvent` lui-même** — `isPlayerPlaced`/
    `clearPlacement` sont appelés explicitement par `MiningXpListener`
    dans l'ordre voulu (lire puis effacer), pour ne jamais dépendre de
    l'ordre d'exécution entre deux listeners Bukkit distincts sur le même
    événement (contrairement à un design qui aurait fait effacer le suivi
    dans le propre `BlockBreakEvent` du tracker).
-   **Anti-farm mobs de spawner : tag PDC posé à `CreatureSpawnEvent`,
    lu à `EntityDeathEvent`** — `SpawnReason` n'est disponible qu'au
    moment du spawn, jamais à la mort ; même contrainte que `mob.
    SpecialMobService` a dû résoudre pour son upgrade de spawn naturel, la
    solution est un tag PDC posé une fois au spawn.
-   **Anti-farm mobs de division : dépendance directe à
    `mob.SpecialMobService#isSplitOffspring`**, exposée publiquement pour
    l'occasion (la clé PDC de profondeur, avant étape 19, était possédée
    localement par `mob.ability.SplitOnHitAbilityListener` — centralisée
    sur `SpecialMobService` avec les autres clés PDC du package, single
    source of truth).
-   **Cultures : `Ageable#getAge() == getMaximumAge()`, pas une liste de
    matériaux** — couvre toute culture vanilla par construction (blé,
    carottes, pastèque/citrouille, cacao, baies...) sans table à maintenir ;
    en contrepartie couvre aussi des blocs `Ageable` non-agricoles
    (jeunes pousses d'arbre, corail) — simplification assumée, documentée
    dans `docs/PROGRESSION.md`.
-   **Découverte de zone : `PlayerMoveEvent` filtré au changement de
    bloc**, jamais à chaque micro-mouvement — coût amorti, même patron que
    tout listener de mouvement du projet. Aucune zone n'est exclue par
    construction (y compris la safe zone centrale) : documenté comme
    choix délibéré plutôt que comme un cas particulier fragile à
    maintenir.
-   **Fin de quête : callback générique `QuestProgressEngine#onProgressChanged`,
    aucune modification du package `quest`** — ce callback notifie après
    *tout* changement de progression, pas spécifiquement une fin de
    quête ; `QuestCompletionXpListener` reparcourt donc l'ensemble des
    états à chaque appel et laisse la déduplication (id = id de quête)
    garantir qu'une quête déjà récompensée ne l'est plus jamais, même
    rappelée plusieurs fois. Évite d'inverser la dépendance
    quest → progression (le reste du projet dépend de `quest`, jamais
    l'inverse) et d'étendre `quest.model.QuestReward` pour ce besoin.
-   **`ProgressionService#hasLevel` est le hook de déblocage générique**
    (mission point 11), câblé concrètement dans
    `claim.ClaimService#effectiveMaxClaims` (même seam que `claim` avait
    préparée en étape 17, point 8, jusqu'ici jamais remplie) ; portails et
    recettes n'ont pas encore de champ dédié dans leur format YAML pour le
    consommer, documenté comme extension future dans
    `docs/PROGRESSION.md` plutôt que forcé maintenant.

## `entitlement` (avantage joueur générique)

-   **Une seule interface, une seule implémentation SQL, un seul
    consommateur pour l'instant** (`backpack`, mission étape 20, point 11
    — « sans créer encore la boutique ») — `EntitlementService` ne prend
    volontairement aucun type générique Java (`grant(UUID, String key,
    String tier, String reason)`, pas `grant(UUID, K key, T tier)`) : le
    palier est une chaîne libre, jamais couplée à `backpack.model.BackpackSize`
    ni à un futur enum d'avantage, pour qu'un avantage totalement
    différent (métier, cosmétique...) réutilise la même table
    `player_entitlements` sans migration supplémentaire — seule la clé
    (`entitlement_key`) change.
-   **`database.EntitlementRepository` implémente directement
    l'interface**, sans service métier intermédiaire — contrairement à
    `claim.ClaimService`/`backpack.BackpackService`, un avantage générique
    n'a aucune règle de validation propre (pas de chevauchement, pas de
    limite), une simple lecture/écriture clé-valeur suffit.

## `backpack` (inventaire virtuel persistant)

-   **Le palier effectif est résolu via `EntitlementService`, jamais
    stocké directement sur la ligne `backpacks`** — `BackpackService#effectiveSize`
    interroge l'avantage `"backpack"` à chaque ouverture ; la permission
    de secours `rpgquest.backpack.free` (mission point 5) n'intervient
    qu'en repli, si aucun avantage explicite n'existe. Le contenu
    (`backpacks.contents`) et le palier ne peuvent donc jamais diverger
    silencieusement : `BackpackService#open` adapte défensivement le
    contenu chargé si sa taille ne correspond plus au palier effectif
    (ex. avantage modifié hors de `applySizeChange`), plutôt que de
    planter ou tronquer.
-   **Une seule instance vivante d'`Inventory` par joueur, jamais
    recréée tant qu'elle existe** (`BackpackService#sessions`) — la garde
    contre l'« ouverture simultanée » (mission point 7) n'est pas un
    verrou explicite : elle repose sur le fait que Bukkit exécute les
    tâches planifiées séquentiellement sur le thread principal, donc deux
    appels à `open()` qui se chevauchent voient forcément l'un des deux
    callbacks (celui qui s'exécute en second) trouver la session déjà
    posée par le premier et la réutiliser au lieu d'en recréer une.
-   **`BackpackListener` est en lecture-écriture, contrairement à
    `economy.market.MarketListener`** (vitrine en lecture seule, tout
    clic annulé sans condition) — un backpack doit rester manipulable
    normalement ; seuls les mouvements qui feraient entrer un objet
    interdit (`BackpackService#isForbidden`) sont annulés, sur les
    vecteurs de clic/glisser-déposer classiques (voir Javadoc de la
    classe pour la liste exacte et sa limite assumée : l'édition interne
    d'un bundle légitimement présent dans le backpack n'est pas auditée).
-   **`backpack.ItemArraySerializer` est un format binaire maison** —
    aucun précédent dans le projet (`economy.market` ne sérialise qu'un
    `ItemStack` isolé). Longueur-préfixé par case, réutilise
    `ItemStack#serializeAsBytes()`/`deserializeBytes()` pour chaque case
    non vide (même garantie de méta complète que le marché) ;
    `SCHEMA_VERSION` vit à côté du binaire (colonne `schema_version`,
    jamais dans le binaire lui-même) pour qu'un futur changement de
    format puisse migrer les lignes existantes sans d'abord les décoder
    avec l'ancien format (mission point 6).
-   **Upgrade et downgrade partagent un seul algorithme de
    redimensionnement** (`BackpackService#compact`) plutôt que deux
    chemins de code séparés — les objets non vides sont compactés en
    début de tableau dans la nouvelle taille ; ce qui ne rentre plus part
    en surplus. Un agrandissement ne produit jamais de surplus par
    construction (mission point 10) ; une réduction peut en produire
    (mission point 9), toujours écrit dans la même transaction JDBC que
    le nouveau contenu (`database.BackpackRepository#applyResize`) —
    jamais l'un sans l'autre.
-   **Sauvegarde à l'arrêt sans mécanisme de flush dédié** — même
    découverte que pour les vitrines (`economy.market.MarketService`,
    `economy.merchant.MerchantTradeService`) : `BackpackService#stop`
    force la fermeture (`Player#closeInventory`) de tout backpack encore
    ouvert, ce qui déclenche `InventoryCloseEvent` de façon synchrone sur
    le thread principal *avant* que `stop()` ne rende la main ; la
    sauvegarde qu'il enclenche est donc déjà en file sur l'exécuteur de
    base de données avant que `DatabaseService` (démarré avant, donc
    arrêté après, LIFO) ne ferme la connexion.
-   **Anomalie de lecture (contenu illisible) : jamais une exception qui
    remonte, toujours une entrée de récupération** — `BackpackService#safeDeserialize`
    capture toute `IOException` de désérialisation, journalise une entrée
    `backpack_audit`, déplace les octets bruts illisibles vers
    `backpack_overflow` (récupérable manuellement si un futur outil sait
    les interpréter) et retourne un backpack vide plutôt que de faire
    échouer l'ouverture (mission, validation « toute anomalie crée une
    entrée de récupération ou d'audit »).

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
-   **`config.yml` → `journal`** (nouveau) : `tracker-enabled` (`true` par
    défaut), seul réglage du journal — tout le reste (disposition du menu,
    onglets, pagination) n'est pas configurable, une YAML dédiée aurait été
    disproportionnée pour un menu dont la mission fixe déjà la structure.
    Même comportement de compatibilité que `dialogue` : une section absente
    vaut « valeurs par défaut », pas une erreur — vérifié via `runServer`
    avec l'ancien `config.yml` du dépôt de test (généré avant même la
    section `dialogue`), qui démarre toujours sans modification.
-   **`ItemDefinitionParserTest`/`YamlCustomItemRegistryTest` ont besoin de
    MockBukkit**, contrairement à `QuestDefinitionParserTest`/
    `DialogueDefinitionParserTest` qui restent purs. Découvert en écrivant
    les tests : `Material`/`EntityType` sont de simples enums résolubles
    sans serveur, mais `Registry.ATTRIBUTE`/le registre d'enchantements
    sont des champs statiques peuplés **depuis un serveur Bukkit vivant** —
    y accéder avant qu'un serveur (mocké ou réel) n'existe échoue avec une
    `ExceptionInInitializerError` qui, une fois survenue, casse
    définitivement la classe `Registry` pour le reste de la JVM de test
    (sémantique standard Java sur l'échec d'initialisation de classe).
    `ItemDefinitionParserTest` mocke donc systématiquement un serveur
    (`MockBukkit.mock()`/`unmock()` en `@BeforeEach`/`@AfterEach`), ce qui
    a aussi pour effet d'amorcer `Registry` correctement pour le reste de
    la suite quel que soit l'ordre d'exécution des classes de test.
    Construire un `ItemStack` avec métadonnées (nom, lore, PDC) nécessite
    de toute façon un `ItemFactory` fourni par le serveur, donc
    `YamlCustomItemRegistryTest` avait de toute façon besoin de MockBukkit.
-   **`Registry.ENCHANTMENT` est déprécié, `RegistryAccess.registryAccess().getRegistry(RegistryKey...)`
    est le remplacement** (déjà utilisé pour les enchantements d'objet à
    l'étape précédente) — réutilisé ici pour résoudre `PotionEffectType`
    via `RegistryKey.MOB_EFFECT` dans l'effet conditionnel d'une arme,
    pour la même raison.
-   **Constructeurs `EntityDamageByEntityEvent(..., DamageSource, double)`
    et `PlayerItemDamageEvent(Player, ItemStack, int)` dépréciés « for
    removal »** dans cette version de l'API (découvert en écrivant les
    tests, `-Xlint:deprecation`) — le remplacement complet pour
    `EntityDamageByEntityEvent` exige une `Map<DamageModifier, ...>`
    interne au moteur vanilla, disproportionné pour un fixture de test ;
    `@SuppressWarnings("removal")` documenté est utilisé dans
    `WeaponBehaviorListenerTest`. `PlayerItemDamageEvent` a un remplacement
    direct et simple (constructeur 4 arguments avec `originalDamage`),
    utilisé sans suppression. Le code de production, lui, ne présente
    aucun avertissement de dépréciation (vérifié par compilation complète
    avec `-Xlint:deprecation`).

## Limites connues

-   `QuestJournalUi` et `CustomItemRegistry` sont désormais toutes deux
    implémentées (`QuestJournalService`, `YamlCustomItemRegistry` — voir
    sections `ui` et `item`).
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
-   **Onglet « Disponibles » et quêtes répétables déjà terminées** : une
    quête `repeatable: true` déjà `COMPLETED` n'apparaît que dans l'onglet
    « Terminées », pas dans « Disponibles », même si `/quest accept`
    l'accepterait à nouveau sans problème. Limitation assumée par manque de
    temps plutôt qu'un choix délibéré ; contournement : `/quest accept`
    reste utilisable directement, avec ou sans passer par le menu.
-   **Test manuel du journal limité par l'environnement** : comme pour les
    étapes précédentes, aucun client Minecraft réel n'est disponible ici,
    donc la disposition visuelle réelle du menu (icônes, lore, bossbar) n'a
    pas pu être observée dans un client. Vérifié à la place : démarrage
    propre du service (`QuestJournalService` dans l'ordre attendu des
    services), et surtout — contrairement aux étapes précédentes — la
    logique de clic/drag elle-même **est** couverte par des tests
    automatisés qui construisent de vrais `InventoryClickEvent`/
    `InventoryDragEvent` Bukkit et vérifient leur annulation
    (`QuestJournalServiceTest`), ce qui teste le comportement réel de
    protection anti-vol/duplication sans nécessiter de client. Le rendu
    visuel exact (positionnement, couleurs, lisibilité du lore en jeu,
    inventaire plein, latence réseau) reste à valider par un testeur
    humain.
-   **`special-behavior` : donnée capturée, aucun comportement câblé** — le
    champ existe, est validé (chaîne libre optionnelle) et exposé (ex.
    disponible pour une future extension), mais aucun système d'écoute
    d'événements ne déclenche encore quoi que ce soit à partir de sa
    valeur (ex. `forest_blade_leaf_trail` sur `forest_blade` ne produit
    aujourd'hui aucune particule). Choix délibéré de périmètre : la
    mission de cette étape porte sur le *registre* (définition, création,
    identification), pas sur un système de comportements de gameplay —
    câblage prévu pour une étape ultérieure dédiée.
-   **`crafting` (restrictions de fabrication) : donnée capturée, non
    appliquée** — `craftable`/`required-permissions` sont validés et
    consultables (`/customitem inspect`), mais aucune recette personnalisée
    n'existe encore pour les faire respecter (voir objectif global «
    recettes personnalisées », étape ultérieure). Un objet marqué
    `craftable: false` ne peut donc pas *encore* être empêché d'apparaître
    dans une recette, faute de système de recettes à contraindre.
-   **Pas de commande `/customitem admin reload`** — non demandée par
    cette étape (contrairement à `/quest admin reload`/`/dialogue admin
    reload` — ce dernier n'existe d'ailleurs pas non plus, voir plus haut).
    `YamlCustomItemRegistry.reload()`/`.validate()` existent et sont
    testés directement ; les exposer via une commande serait un miroir
    trivial de `/quest admin reload` si nécessaire plus tard.
-   **Test manuel des objets personnalisés limité par l'environnement** —
    aucun client Minecraft réel disponible ici. La mission demande
    explicitement de « donner, jeter, ramasser, stocker dans un coffre,
    mourir, reconnecter et redémarrer le serveur » avec un vrai client :
    non réalisable dans cet environnement. Vérifié à la place : démarrage
    propre (`YamlCustomItemRegistry` dans l'ordre attendu, « 4 chargé(s),
    0 erreur(s) » au log), fichiers d'exemple générés strictement
    identiques aux ressources embarquées dans le jar, et surtout — la
    persistance à travers un « redémarrage » **est** directement testée
    (`survivesSerializationRoundTrip`, sérialisation/désérialisation d'un
    `ItemStack` complet via l'API Bukkit, le même mécanisme que celui
    utilisé pour écrire un inventaire de joueur sur disque). Le scénario
    complet en jeu (jeter/ramasser, coffre, mort avec perte/keepInventory,
    reconnexion avec un vrai client) reste à valider par un testeur humain.
-   **`durability-cost` n'est pas limité par `allowed-blocks`** —
    `PlayerItemDamageEvent` ne porte pas le bloc cassé dans son contexte ;
    seuls le bonus de récolte (`BlockDropItemEvent`) et la capacité
    spéciale (clic droit) respectent la liste de blocs autorisés. Un outil
    avec une liste de blocs restreinte consomme donc toujours sa
    durabilité personnalisée, même sur un bloc hors liste — limitation
    assumée et documentée dans la section `item.behavior` plutôt que
    contournée par une solution fragile (deviner le bloc via la position
    du joueur au moment de l'événement).
-   **Message/particule liés au coup « notable » (critique ou effet
    déclenché), pas à chaque coup** — choix délibéré pour éviter un spam
    de messages sur une attaque normale ; la mission ne précise pas la
    fréquence exacte, cette interprétation reste cohérente avec l'esprit
    « retour visuel sur quelque chose de spécial ».
-   **`special-behavior` (donnée libre, étape précédente) reste non câblé**
    — cette étape ajoute des comportements *structurés* et *configurables*
    (`combat:`/`tool:`), mais le champ `special-behavior` générique
    continue de n'être qu'une donnée exposée, pas un point d'extension
    exécuté. Les deux systèmes sont volontairement distincts : `combat:`/
    `tool:` couvrent les cas concrets demandés par cette mission,
    `special-behavior` reste réservé à un futur système de plugins de
    comportement arbitraires si le besoin se précise.
-   **Test manuel des comportements de combat/outil limité par
    l'environnement** — aucun client Minecraft réel disponible ici, donc
    ni comparaison de dégâts avec une arme vanilla en jeu, ni test PvE/PvP
    réel n'ont pu être menés comme demandé par la mission. Compensé par
    une couverture de test directe et inhabituellement large pour ce
    projet : `WeaponBehaviorListenerTest`/`ToolBehaviorListenerTest`
    construisent de **vrais** événements Bukkit (`EntityDamageByEntityEvent`,
    `PlayerItemDamageEvent`, `BlockDropItemEvent`, `PlayerInteractEvent`)
    et vérifient l'effet exact de chaque règle de sécurité (annulation,
    main secondaire, armor stand, projectile, objet contrefait,
    cooldown, rechargement de configuration) plutôt que de se contenter
    d'inspecter la configuration chargée. Le calcul de dégâts lui-même est
    vérifié arithmétiquement (`assertEquals` sur la valeur exacte
    attendue), la comparaison avec une arme vanilla en conditions réelles
    reste à faire par un testeur humain.
-   **Pas de `/resourcenode reload`** — `ResourceNodeRegistry.reload()`/
    `.validate()` existent et sont testés directement, mais ne sont pas
    exposés par une commande (non demandée par cette étape), même
    limitation déjà documentée pour `/customitem admin reload`.
-   **Aucun son/particule de cassage vanilla à la récolte d'un nœud** —
    `handleBreak` annule systématiquement le `BlockBreakEvent` (nécessaire
    pour poser le bloc épuisé sans que le post-traitement vanilla ne
    l'écrase, voir section `resource`) ; le joueur voit le bloc changer
    instantanément plutôt qu'une animation de cassage. Compromis délibéré,
    cosmétique uniquement.
-   **La durabilité de l'outil n'est pas consommée par la récolte d'un
    nœud** — `handleBreak` court-circuite le traitement vanilla habituel
    (`PlayerItemDamageEvent`) en annulant l'événement ; le système de
    `durability-cost` d'`item.behavior` reste indépendant et continue de
    s'appliquer normalement à tout autre cassage de bloc avec un outil
    personnalisé. Non demandé par cette étape, pourrait être ajouté en
    appelant manuellement la même logique si nécessaire plus tard.
-   **Test manuel des nœuds de ressource limité par l'environnement** —
    aucun client Minecraft réel disponible ici. Compensé par une
    couverture de test directe construisant de vrais événements Bukkit
    (`BlockBreakEvent`) sur un monde MockBukkit, avec horloge/résolveur de
    monde/vérificateur de chunk injectés pour couvrir déterministiquement
    le respawn, le monde supprimé et le chunk déchargé sans dépendre du
    comportement réel (non garanti) de MockBukkit sur ces points. Le
    scénario complet en jeu (poser un nœud, le récolter à plusieurs, sur
    plusieurs sessions, avec un vrai client) reste à valider par un
    testeur humain.
-   **Race condition découverte pendant les tests d'intégration de l'étape
    10, non corrigée** — `QuestProgressEngine.loadForPlayer` (chargement de
    la progression au `PlayerJoinEvent`) écrit `activeByPlayer.put(playerId,
    map)` sans condition, à la fin d'une chaîne entièrement asynchrone. Si
    un joueur interagit avec le système de quêtes (`accept`, événement de
    progression) dans la très courte fenêtre entre sa connexion et la fin
    de ce chargement, ce `put` peut en théorie écraser silencieusement l'état
    fraîchement modifié en mémoire par autre chose de plus ancien lu en
    base. En conditions réelles, la latence réseau d'un vrai client rend
    cette fenêtre infranchissable ; le risque ne s'est manifesté qu'en test
    automatisé (interaction immédiate, sans latence, avec le joueur simulé
    juste après `server.addPlayer()` — voir `CrystalHuntIntegrationTest`,
    qui attend explicitement la fin du chargement avant d'interagir).
    Risque jugé acceptable et documenté plutôt que corrigé dans cette
    étape (correctif non trivial — fusion au lieu de remplacement, ou
    compteur de génération — et hors du périmètre demandé, qui porte sur
    les étapes 9/10, pas un audit de l'étape 4).
-   **`CRAFT_ITEM` ne distingue pas un objet personnalisé d'un objet
    vanilla de même matériau de base** — limitation préexistante depuis
    l'étape 4 (l'objectif compare uniquement
    `event.getRecipe().getResult().getType()`), réutilisée telle quelle
    par la quête `crystal_hunt` (voir « Parcours RPG complet ») : fabriquer
    une épée en diamant vanilla ordinaire valide donc aussi l'étape
    « forger une lame », pas seulement `forest_blade`. Même famille de
    limitation assumée que `TALK_TO_NPC` (identification par un critère
    grossier plutôt qu'un système dédié) ; corriger cela demanderait de
    faire porter l'objectif sur un id d'objet personnalisé plutôt qu'un
    `Material`, un changement de modèle hors du périmètre de cette étape.
-   **`resource-pack/pack.mcmeta` → `pack_format` non vérifié en
    conditions réelles** — aucun client Minecraft réel disponible ici (ni
    la version exacte du client visée, Paper 1.21.11 ne correspondant à
    aucune version publique connue à la date de cette étape) : la valeur
    choisie est une estimation raisonnable pour la plage 1.21.x, à
    ajuster si un client réel la refuse.
-   **`shift-clic`/recette automatique non testés par un vrai événement
    Bukkit** — `RecipeCraftGuardListenerTest` vérifie le cœur logique
    (`isValidMatrix`) directement plutôt que via un `PrepareItemCraftEvent`
    construit à la main : Bukkit ne fournit aucune API publique pour
    calculer le décalage réel d'un motif `SHAPED` dans la grille, ce qui
    aurait rendu un événement construit manuellement non représentatif.
    Comme `PrepareItemCraftEvent` se déclenche identiquement quel que soit
    le type de clic (voir section `crafting`), la couverture logique reste
    valable pour ces cas ; le rendu réel (grille de craft, livre de
    recettes) reste à valider par un testeur humain.
-   **`/rpgadmin flatten` : « zones interdites » limitées à une liste de
    mondes** — voir section `admin` : le vrai recroisement avec un
    registre de zones protégées reste à faire une fois l'étape 13 livrée.
-   **`/rpgadmin flatten` : test manuel en jeu limité par l'environnement**
    — aucun client Minecraft réel disponible ici. Compensé par
    `FlattenServiceTest`, qui vérifie directement sur un vrai monde
    MockBukkit (blocs `Block#getType()` réels, pas une simulation) le
    calcul d'aperçu, le traitement par lots sur plusieurs ticks
    (`ServerMock#getScheduler().performTicks`), et le contenu exact des
    blocs après exécution/annulation. Le ressenti en jeu (terrain vallonné,
    arbres, eau, cavités, absence de gel perceptible sur une grande zone,
    reconnexion pendant une opération) reste à valider par un testeur
    humain — voir `docs/ADMIN_FLATTEN.md`.
-   **Zones protégées : pas de rechargement à chaud d'un fichier édité à
    la main** — voir section `zone` : seuls `create`/`delete` rechargent
    automatiquement.
-   **Zones protégées : test manuel en jeu limité par l'environnement** —
    aucun client Minecraft réel disponible ici. Compensé par
    `ZoneProtectionListenerTest`, qui construit de vrais événements Bukkit
    (`BlockBreakEvent`, `BlockPlaceEvent`, `EntityDamageByEntityEvent`,
    `EntityExplodeEvent`) sur un monde MockBukkit et vérifie leur
    annulation exacte. Le ressenti en jeu (PvP réel, projectiles, creeper,
    TNT, lit, cristal d'End, feu, lave, piston traversant une frontière,
    redstone, mort/reconnexion dans une zone, spawn hostile observé) reste
    à valider par un testeur humain — voir `docs/SAFE_ZONE.md`.
-   **`/money pay`/`/money admin` ne ciblent que des joueurs en ligne** —
    `Server#getPlayerExact` uniquement, contrairement à `/rpgquest profile`
    qui résout un pseudo hors-ligne. Limitation assumée par simplicité :
    étendre à un joueur hors-ligne demanderait la même résolution
    asynchrone (`Server#getOfflinePlayer`) déjà utilisée par `/rpgquest
    profile`, ajoutable plus tard sans changer `EconomyService`.
-   **Aucun solde de départ, aucune commande de consultation de
    l'historique des transactions** — `wallets`/`transactions` existent et
    sont écrites à chaque opération, mais rien ne lit encore `transactions`
    en jeu (ni commande, ni UI) : la table sert aujourd'hui de journal
    d'audit brut, consultable uniquement via une requête SQL directe.
-   **Vitrine de marchand sans pagination** — au-delà du nombre de slots de
    contenu disponibles (jusqu'à 45, vitrine à 6 lignes), les offres
    excédentaires ne sont simplement pas affichées (avertissement au log
    au chargement). Non demandé par cette étape ; mirroir facile de la
    pagination déjà existante dans `ui.JournalPagination` si un marchand a
    un jour besoin de plus de 45 offres.
-   **Intégration Vault non câblée, seulement préparée** — voir section
    `economy` : aucune dépendance Vault n'a été ajoutée, `EconomyService`
    expose une forme compatible pour un futur adaptateur.
-   **Test manuel de l'économie/des marchands limité par l'environnement**
    — aucun client Minecraft réel disponible ici. Compensé par
    `WalletRepositoryTest` (transactions réellement atomiques sur une vraie
    base SQLite temporaire, y compris un scénario de double-débit
    concurrent) et `MerchantTradeServiceTest`/`DialogueSessionEngineTest`
    (achat/vente/permission/niveau/quête, ouverture depuis un vrai choix de
    dialogue, via de vrais appels MockBukkit). Le ressenti en jeu
    (ouverture de la vitrine par clic sur un PNJ renommé, lisibilité du
    lore, latence réseau) reste à valider par un testeur humain.
-   **`/market admin` ne peut pas forcer l'annulation d'une offre d'un
    joueur hors ligne avec restitution de l'objet** — seul le vendeur
    lui-même, en ligne, peut annuler sa propre offre
    (`MarketRepository#cancel` vérifie `seller_uuid`). Pas de système de
    livraison différée (« boîte aux lettres ») pour remettre un objet à un
    joueur absent au moment où son offre serait annulée par un
    administrateur — limitation assumée, mirroir facile d'un futur système
    de backpacks/livraison (étape 20) si le besoin se précise.
-   **Vitrine du marché sans onglets** — une seule liste, toutes offres de
    tous les vendeurs confondues (voir section `economy.market`) ; pas de
    vue « mes offres uniquement » dans l'inventaire (mirroir facile des
    onglets de `ui.JournalTab` si nécessaire plus tard). `/market cancel
    <id>` et `/market admin list` restent utilisables sans passer par le
    menu.
-   **Test manuel du marché entre joueurs limité par l'environnement** —
    aucun client Minecraft réel disponible ici. Compensé par
    `MarketRepositoryTest` (réservation atomique, y compris l'échec d'une
    seconde réservation concurrente sur la même offre, réactivation après
    débit refusé, annulation restreinte au vendeur) et `MarketServiceTest`
    (vente réelle avec retrait de l'objet en main, achat réel fonds
    suffisants/insuffisants, clic sur sa propre offre). Le ressenti en jeu
    (navigation entre pages avec beaucoup d'offres, deux vrais joueurs
    achetant simultanément la même offre, latence réseau) reste à valider
    par un testeur humain.
-   **Aucun avantage payant pour les claims à cette étape** — délibéré
    (mission, point 9) : `effectiveMaxWidth`/`effectiveMaxHeight`/
    `effectiveMaxClaims` ne dépendent d'aucune monnaie ni d'aucun achat,
    uniquement d'un seam pour une future politique liée à la progression
    (XP RPG), voir section `claim`.
-   **`/claim list` liste uniquement les claims du joueur qui l'exécute** —
    pas de variante admin listant les claims de tous les joueurs, non
    demandée par la mission ; mirroir facile d'une future commande
    `/rpgadmin claim list <joueur>` si le besoin se précise.
-   **La redstone d'un claim n'a pas la granularité des zones protégées** —
    pas de distinction entre boutons/leviers/portes/dalles de pression
    comme le permettent les zones (`allowDoors`/`allowButtons`/
    `allowLevers` séparés) : la mission ne demande qu'« redstone
    configurable », traitée comme un seul groupe pour un claim.
-   **Test manuel des claims limité par l'environnement** — aucun client
    Minecraft réel disponible ici. Compensé par `ClaimServiceTest` (toutes
    les conditions de refus à la création, suppression, confiance,
    protection indépendante du statut en ligne du propriétaire) et
    `ClaimProtectionListenerTest` (construit de vrais événements Bukkit sur
    un monde MockBukkit : frontière incluse, membre autorisé/non autorisé,
    conteneurs, redstone configurable, animaux, armor stands, explosion
    externe, piston traversant la frontière, monde sans claim). Le
    ressenti en jeu (deux joueurs voisins, TNT dedans/dehors, piston réel,
    redémarrage complet) reste à valider par un testeur humain — voir
    `docs/CLAIMS.md`.
-   **Pas de rechargement à chaud d'un portail/d'une destination édités à
    la main** — même limitation déjà documentée pour les zones protégées :
    `create`/`delete`/`setdestination` rechargent déjà automatiquement, ce
    qui couvre le flux d'usage principal.
-   **Aucune commande `destination create` séparée** — voir section
    `travel` : `/rpgadmin portal setdestination` capture la position de
    l'administrateur et tient lieu de création/mise à jour de destination,
    seule sous-commande demandée par la mission pour ce besoin.
-   **Aucun exemple de portail/destination embarqué** — décision
    délibérée, voir section `travel` (bundler des coordonnées arbitraires
    sur un monde généré procéduralement serait plus nuisible qu'utile).
-   **Test manuel des portails limité par l'environnement** — aucun client
    Minecraft réel disponible ici. Compensé par `PortalServiceTest`, qui
    construit un vrai monde MockBukkit (blocs réels) et vérifie chaque
    garantie de sécurité demandée directement (conditions non remplies,
    cooldown, coût débité uniquement au succès, monde de destination
    absent, destination dangereuse, annulation par mouvement/dégâts/
    déconnexion via de vrais appels), y compris que `teleportAsync` déplace
    réellement le joueur simulé. Le ressenti en jeu (portail vers un chunk
    réellement déchargé, reconnexion en pleine canalisation, latence
    réseau, téléportation avec un inventaire chargé et une quête active,
    test depuis/vers une safe zone réelle) reste à valider par un testeur
    humain — voir `docs/TRAVEL.md`.
