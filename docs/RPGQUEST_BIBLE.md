# RPGQuest — Bible technique

Référence technique exhaustive et consultable rapidement (VS Code, GitHub,
Claude) : toutes les commandes et procédures réellement implémentées dans
ce dépôt, vérifiées contre le code source au moment de la rédaction — rien
n'est inventé. Chaque commande a été confirmée dans le fichier Java qui la
déclare (ou, pour les plugins externes, dans la procédure du projet qui la
référence réellement).

Cette bible **complète** les documents existants (`docs/*.md`, `*.md` à la
racine) et le site de navigation visuelle `docs-site/` — elle ne les
remplace pas. En cas de divergence constatée entre le code et un document
existant, cette bible signale l'écart et suit le code.

-   **Version cible :** RPGQuest `0.1.0-SNAPSHOT`, Paper `1.21.11` (`1.21.11-132` en production VeryGames), Java 21.
-   **Dépôt :** branche de rédaction `feature/23-mod-prototype`.
-   **Documentation cible :** ce document décrit l'état du code au moment de sa rédaction ; voir « Maintenance » en fin de document pour la règle qui le garde à jour.

## Convention utilisée pour chaque commande

-   **Type** — `Joueur` (permission par défaut ouverte), `Admin` (permission `op` par défaut), `Console` (exécutable/utile en console), ou `Plugin externe` (WorldEdit/Citizens/Multiverse-Core, non implémentée par RPGQuest).
-   **Permission** — nœud de permission exact vérifié dans le code.
-   **Syntaxe** — `<obligatoire>`, `[facultatif]`.
-   **Persistance** — précise si l'action modifie un état durable (fichier YAML, SQLite) ou non.
-   Une commande triviale (lecture seule, sans piège) reste courte — pas de gabarit alourdi artificiellement.

## Table des matières

1.  [Installation / Serveur](#1-installation--serveur)
2.  [Administration RPGQuest (`/rpgadmin`)](#2-administration-rpgquest-rpgadmin)
3.  [Quêtes](#3-quêtes)
4.  [Dialogues](#4-dialogues)
5.  [NPC / Citizens](#5-npc--citizens)
6.  [Portails](#6-portails)
7.  [Mondes](#7-mondes)
8.  [Claims](#8-claims)
9.  [Items / Équipements](#9-items--équipements)
10. [Ressources](#10-ressources)
11. [Mobs spéciaux](#11-mobs-spéciaux)
12. [Marchands / Économie / Marché](#12-marchands--économie--marché)
13. [Backpacks](#13-backpacks)
14. [WorldEdit](#14-worldedit)
15. [Tests et diagnostic](#15-tests-et-diagnostic)
16. [Fichiers importants](#16-fichiers-importants)
17. [Persistance / Migration](#17-persistance--migration)
18. [Dépannage](#18-dépannage)
19. [Synchronisation avec docs-site](#19-synchronisation-avec-docs-site)
20. [Maintenance](#20-maintenance)

---

## 1. Installation / Serveur

Vérifié dans : [docs/deployment/VERYGAMES.md](deployment/VERYGAMES.md) (procédure complète, faisant foi pour la production VeryGames), [docs/LOCAL_SERVER.md](LOCAL_SERVER.md) (cycle de développement local), [docs/deployment/SERVER_CHANGELOG.md](deployment/SERVER_CHANGELOG.md) (historique des actions serveur réellement effectuées).

### `./gradlew clean build` (`gradlew.bat clean build` sous Windows)
Type : Console (poste de développement, pas le serveur de jeu)
Où l'exécuter : racine du dépôt.
But : compiler le plugin **et** `web-api`, exécuter les suites JUnit des deux modules.
Résultat attendu : `BUILD SUCCESSFUL`, jar produit dans `build/libs/rpgquest-<version>.jar`.
Pièges fréquents : un `BUILD FAILED` sur les tests bloque volontairement la production d'un jar à déployer — ne jamais contourner avec `-x test`. Le jar de `run/plugins/` (généré par `runServer`) n'est **jamais** celui à déployer en production, seul `build/libs/rpgquest-<version>.jar` l'est.

### `./gradlew runServer` (`gradlew.bat runServer`, ou `.\launcher.ps1`)
Type : Console (poste de développement local uniquement — jamais en production VeryGames)
Où l'exécuter : racine du dépôt.
But : lancer un vrai serveur Paper 1.21.11 local dans `run/`, avec RPGQuest recompilé et copié automatiquement à chaque lancement.
Résultat attendu : premier lancement → arrêt immédiat demandant d'accepter l'EULA (`run/eula.txt`, `eula=false` → `eula=true`, décision manuelle). Relancer → `Done (...)! For help, type "help"` puis `RPGQuest <version> activé`.
Pièges fréquents : ne jamais utiliser `/reload` (Bukkit vanilla) pour tester un changement de code — recharge tous les plugins de façon non fiable, `/rpgquest reload` (config uniquement) reste sûr. Toujours arrêter avec `stop` en console plutôt que tuer le processus, sous peine de verrou de session orphelin (`run/world/session.lock`) qui bloque le lancement suivant (`DirectoryLock`/`IOException`) — voir section 18.

### Arrêt / redémarrage
Type : Console
Où l'exécuter : console du serveur (locale ou VeryGames).
But : arrêt propre — `onDisable()` s'exécute, `data.db` se ferme correctement.
Syntaxe : `stop` (ou `Ctrl+C` depuis le terminal `runServer` en local).
À savoir : ne jamais couper le processus brutalement ; c'est la seule façon fiable d'éviter la corruption/le verrou SQLite et le verrou de session du monde.

### Déploiement VeryGames — vue d'ensemble
Trois scénarios distincts documentés dans [VERYGAMES.md](deployment/VERYGAMES.md), à ne jamais mélanger :

1.  **Installation neuve** — aucun serveur VeryGames existant (ordre strict : Paper → Java 21 vérifié → WorldEdit → Citizens → Multiverse-Core → RPGQuest → migration des données).
2.  **Mise à jour du seul JAR RPGQuest** — remplacement du jar uniquement, aucune donnée/config touchée.
3.  **Migration complète** — inclut les données de jeu (`data.db`, `saves.yml`, `world_hub/`).

### Installation des JAR / vérification des plugins
Type : Console + FTP (VeryGames)
Où l'exécuter : panel VeryGames (FTP vers `/plugins/`), puis console/RCON.
But : installer WorldEdit, Citizens, Multiverse-Core puis RPGQuest, **un par un, en testant chaque étape avant la suivante**.
Résultat attendu par plugin : `/plugins` liste chaque plugin en **vert**. WorldEdit → `/worldedit version` répond. Citizens → `/npc create test_citizens` crée un PNJ visible (à supprimer immédiatement après test, ne jamais laisser en production). Multiverse-Core → `/mv list` répond et liste au moins `world`. RPGQuest → `/rpgquest version` répond `RPGQuest v<version>`.
Piège fréquent : un plugin qui échoue silencieusement à l'étape N complique le diagnostic à l'étape N+2 — d'où l'ordre strict et le test immédiat après chaque JAR.

### `/mv import world_hub normal`
Type : Console/RCON (Multiverse-Core, plugin externe)
Où l'exécuter : console VeryGames, **après** avoir transféré le dossier `world_hub/` à la racine du serveur (même niveau que `world/`, jamais dans `/plugins/`).
But : déclarer le monde `world_hub` (dossier déjà présent sur disque) auprès de Multiverse-Core, en environnement `normal`.
Résultat attendu : le monde est chargé, prêt à apparaître dans `/mv list`.
Pièges fréquents : ne jamais renommer `world_hub` en `world`, ne jamais l'importer à la place d'un monde par défaut existant.

### `/mv list`
Type : Console/RCON (Multiverse-Core, plugin externe)
But : vérification immédiate que `world_hub` (et les autres mondes) sont bien chargés.
Résultat attendu : `world_hub` apparaît dans la liste après `/mv import`.

### `/mvtp <monde>` (Multiverse-Core, non utilisé dans notre procédure documentée)
Type : Console/joueur (Multiverse-Core, plugin externe)
À savoir : commande standard de téléportation Multiverse — **non référencée** dans `VERYGAMES.md` ni dans le code RPGQuest ; notre procédure validée utilise `/rpgadmin world tp <name>` (mondes gérés par RPGQuest, section 7) ou `/rpgadmin spawn tp` (spawn du village, section 2) à la place. À ne considérer que comme un outil de dépannage Multiverse générique, jamais comme la référence gameplay — voir « Spawn Minecraft vs Multiverse vs RPGQuest » en section 7.

### Sauvegardes / rollback
Voir [VERYGAMES.md § Rollback](deployment/VERYGAMES.md#rollback) pour la liste exacte des fichiers à sauvegarder avant toute opération (ancien JAR, `world_hub/` complet, `saves.yml` **et** `data.db` ensemble — jamais l'un sans l'autre, voir section 17 — fichiers de configuration et `worlds.yml`) et la procédure de restauration pas à pas. Chaque changement nécessitant une action serveur doit en plus avoir une entrée dans [SERVER_CHANGELOG.md](deployment/SERVER_CHANGELOG.md) (sauvegarde préalable, déploiement, validation, rollback spécifiques à ce changement).

---

## 2. Administration RPGQuest (`/rpgadmin`)

Vérifié intégralement dans `src/main/java/com/lodygames/rpgquest/admin/RpgAdminCommand.java` (1269 lignes, lu en entier). Racine unique pour huit sous-systèmes : `flatten`, `zone`, `portal`, `mob`, `npc`, `spawn`, `world`, `worldportal`.

Type : Admin (toutes les sous-commandes) — Permission : **`rpgquest.admin.world`** (unique pour tout `/rpgadmin`, pas de permission plus fine par sous-commande). Exigent toujours un **joueur en jeu** (jamais la console — aucune sous-commande ne prend de coordonnée explicite, toutes utilisent la position/sélection du joueur).

### Aplatissement de terrain — `/rpgadmin flatten`
Détail complet : [docs/ADMIN_FLATTEN.md](ADMIN_FLATTEN.md). Page docs-site : aucune.

| Commande | Effet |
|---|---|
| `/rpgadmin flatten <rayon> [hauteur]` | Calcule un **aperçu** (aucun bloc modifié), centré sur le joueur. `hauteur` optionnelle (Y cible, défaut = bloc sous les pieds). |
| `/rpgadmin flatten confirm` | Exécute l'aperçu en attente (expire après 30 s par défaut). |
| `/rpgadmin flatten cancel` | Annule l'aperçu, ou arrête un chantier en cours (le travail déjà fait reste). |
| `/rpgadmin flatten undo` | Annule le **dernier** aplatissement (un seul niveau, écrasé par le suivant). |

Persistance : non (opère directement sur les blocs du monde ; l'état d'annulation est en mémoire, perdu au redémarrage). À savoir : rayon max configurable (`admin.flatten.max-radius`, 48 par défaut), traitement par lots (4000 blocs/tick par défaut) pour ne jamais geler le serveur.

### Zones protégées — `/rpgadmin zone`
Détail complet : [docs/SAFE_ZONE.md](SAFE_ZONE.md). Page docs-site : `hub-safe-zone.html` (couvre `zone wand/create/delete/list/info` et le tableau des flags).

| Commande | Effet | Persistance |
|---|---|---|
| `/rpgadmin zone wand` | Donne l'outil de sélection (tige de blaze marquée PDC — outil **propre à RPGQuest**, pas WorldEdit ; clic gauche = pos1, clic droit = pos2). | non |
| `/rpgadmin zone create <id>` | Crée une zone cuboïde depuis la sélection courante, flags par défaut (voir SAFE_ZONE.md). | oui — fichier YAML `plugins/RPGQuest/zones/<id>.yml` |
| `/rpgadmin zone delete <id>` | Supprime la zone. | oui |
| `/rpgadmin zone list` | Liste les zones chargées. | non |
| `/rpgadmin zone info <id>` | Bornes + tous les flags (protection et sécurité) d'une zone. | non |

À savoir : rejeté si chevauchement avec une zone existante du même monde ou id déjà pris ; pas de rechargement à chaud d'un fichier YAML édité à la main (supprimer/recréer, ou redémarrer).

### Portails — `/rpgadmin portal`
Voir section 6 (Portails) pour le détail complet — non dupliqué ici.

### Mobs spéciaux — `/rpgadmin mob`
Détail du format YAML : section 11. Page docs-site : aucune.

| Commande | Effet | Persistance |
|---|---|---|
| `/rpgadmin mob spawn <id>` | Invoque la variante à la position du joueur — **contourne** les restrictions de spawn naturel (mondes/biomes/zones, population) : outil de test admin, pas le spawn naturel du jeu. | non |
| `/rpgadmin mob list` | Liste les variantes chargées + population courante. | non |
| `/rpgadmin mob inspect <id>` | Détail complet (entité, nom, chance de spawn, mondes/biomes/zones autorisés, stats, capacités, drops, XP, population/max). | non |
| `/rpgadmin mob reload` | Recharge les définitions depuis le disque, rapporte `N chargé(s), N erreur(s)`. | non (relit les fichiers) |
| `/rpgadmin mob metrics` | Compteurs de spawn et de déclenchement de capacité depuis le démarrage. | non (métriques en mémoire) |

À savoir : `id` accepte un id court (préfixé `rpgquest:` automatiquement) ou namespacé complet.

### PNJ — identité stable — `/rpgadmin npc`
Voir aussi section 5 (NPC/Citizens) pour le mécanisme complet. Page docs-site : `npc.html`.

| Commande | Effet | Persistance |
|---|---|---|
| `/rpgadmin npc tag [id]` | Identifie l'entité visée (portée 6 blocs) d'un id stable, **indépendant de son nom affiché**. Id auto-généré (`npc_<n>`) si omis ; refusé si l'entité est déjà identifiée (utiliser `untag` d'abord). | oui — SQLite (`npc_ids`, migration V11 ; `npc_citizens_bindings`, migration V12, si c'est un PNJ Citizens) |
| `/rpgadmin npc untag` | Retire l'identifiant de l'entité visée. | oui |
| `/rpgadmin npc info` | Affiche l'identifiant courant de l'entité visée (+ suffixe « Citizens NPC #n » si applicable). | non |

À savoir : id valide = minuscules/chiffres/`.`/`_`/`-` uniquement. « Aucune entité visée à portée » si le rayon (`ray trace`, 6 blocs) ne touche rien.

### Spawn du village — `/rpgadmin spawn`
Page docs-site : `hub-safe-zone.html`.

| Commande | Effet | Persistance |
|---|---|---|
| `/rpgadmin spawn set` | Capture la position **et l'orientation** exactes du joueur comme nouveau spawn du village, remplace l'ancien. | oui |
| `/rpgadmin spawn tp` | Téléporte au spawn du village. | non |

À savoir : voir « Spawn Minecraft vs Multiverse vs RPGQuest » en section 7 — c'est **cette** commande, jamais `/mv setspawn`, qui définit le spawn gameplay réel.

### Mondes supplémentaires — `/rpgadmin world`
Voir section 7 (Mondes) pour le détail complet — non dupliqué ici.

### Portails simples entre mondes — `/rpgadmin worldportal`
Voir section 6 (Portails) pour le détail complet — non dupliqué ici.

**Nombre de sous-commandes recensées dans cette section (hors portal/worldportal, détaillées en section 6) : 17** (`flatten` ×4, `zone` ×5, `mob` ×5, `npc` ×3, `spawn` ×2 — auxquels s'ajoutent `portal` ×5 et `worldportal` ×6 comptées en section 6, et `world` ×3 compté en section 7).

---

## 3. Quêtes

Vérifié dans `src/main/java/com/lodygames/rpgquest/command/QuestCommand.java`,
`QuestsCommand.java`, `quest/model/ObjectiveType.java` et les 7 records
`quest/model/*Objective.java`, ainsi que `QUEST_FORMAT.md` (racine). Format
YAML complet : [QUEST_FORMAT.md](../QUEST_FORMAT.md) ; détail
d'implémentation (moteur de progression, index d'objectifs) :
[docs/ARCHITECTURE.md](ARCHITECTURE.md). Page docs-site : `quests.html`
(quêtes joueur) + `admin-testing.html` (sous-commandes admin).

### Commandes joueur — `/quest`

Permission : `rpgquest.quest` (toutes), joueur uniquement (jamais console).

| Commande | Effet |
|---|---|
| `/quest list` | Liste toutes les quêtes connues avec l'état du joueur sur chacune. |
| `/quest accept <id>` | Accepte une quête (vérifie prérequis, répétabilité, doublon). |
| `/quest progress [id]` | Sans argument : état de toutes les quêtes actives/terminées. Avec un id : détail des compteurs d'objectifs de l'étape active. |
| `/quest abandon <id>` | Abandonne une quête active (réacceptable ensuite, indépendamment de `repeatable`). |

Persistance : oui pour tout ce qui touche à l'état (`accept`/`abandon`) — SQLite `quest_progress` + `quest_objective_progress` (migration V2). `list`/`progress` sont en lecture seule.

### `/quests` — journal de quêtes
Type : Joueur — Permission : `rpgquest.quest`
But : ouvrir le journal paginé (onglets Actives/Disponibles/Terminées).
Syntaxe : `/quests`
Effet : ouvre un inventaire GUI (voir `docs/ARCHITECTURE.md` pour le détail : clic gauche = détail, clic droit = suivi/bossbar). Aucun paramètre.
Persistance : le suivi (quête « trackée ») persiste (`player_variables`), pas la simple ouverture du menu.
Bouton « Fermer » (slot `CLOSE_SLOT`/`DETAIL_CLOSE_SLOT`) : la fermeture est différée d'un tick serveur (`QuestJournalService#closeNextTick`) plutôt qu'appelée directement dans le gestionnaire de `InventoryClickEvent` — fermer une fenêtre pendant le traitement de son propre clic annulé pouvait laisser le client avec une fenêtre visuellement toujours ouverte (paquet de resynchronisation du clic annulé arrivant après le paquet de fermeture).

### Commandes admin — `/quest admin`

Permission : `rpgquest.admin` (toutes), sauf `/quest complete` qui est aussi `rpgquest.admin`.

| Commande | Effet | Persistance |
|---|---|---|
| `/quest complete <id>` | Force la fin d'une quête (récompenses appliquées même sans objectifs remplis) — outil de test admin uniquement, joueur exécutant lui-même. | oui |
| `/quest admin reload` | Recharge les définitions de quêtes **et** `messages.yml` depuis le disque, reconstruit l'index/les écouteurs de progression. Rapport `N chargée(s), N erreur(s)` ; un fichier invalide n'empêche pas le chargement des autres. `messages.yml` existant n'est jamais écrasé (personnalisations admin préservées), mais toute clé présente dans la ressource embarquée et absente du fichier disque (ex. nouvelle clé apportée par une mise à jour) est **fusionnée** dans le fichier disque à chaque reload (`QuestMessagesService#mergeMissingKeys`) — la cause la plus courante d'une clé manquante. Si une clé est malgré tout manquante (fichier corrompu, clé mal orthographiée), `QuestMessages#format` ne l'affiche plus jamais en jeu : un texte de remplacement discret est montré au joueur et un avertissement avec le nom exact de la clé part dans les logs serveur. | non (relit les fichiers), sauf ajout de clés manquantes à `messages.yml` |
| `/quest admin validate` | Même chargement, sans rien appliquer (dry-run). | non |
| `/quest admin reset <joueur> <id\|all>` | Supprime l'état persisté et les compteurs d'objectifs d'une quête (ou de toutes) pour un joueur **en ligne** — jamais l'inventaire ni l'économie. Outil de test. | oui (suppression) |

### Types d'objectifs (`steps[].objectives[].type`)

Les 7 types demandés existent **tous** réellement dans le code (`ObjectiveType` enum, exactement ces 7 valeurs, aucune de plus) :

| Type | Champs YAML | Classe | Événement Bukkit déclencheur | Comportement multi-monde |
|---|---|---|---|---|
| `BREAK_BLOCK` | `material` (Bukkit `Material`), `amount` (> 0) | `BreakBlockObjective` | `BlockBreakEvent` (`QuestBlockBreakListener`, `ignoreCancelled = true`) | **Global** — aucun champ `world` ; compte dans n'importe quel monde. |
| `PLACE_BLOCK` | `material`, `amount` (> 0) | `PlaceBlockObjective` | `BlockPlaceEvent` (`QuestBlockPlaceListener`, `ignoreCancelled = true`) | Global, idem. |
| `KILL_ENTITY` | `entity` (Bukkit `EntityType`), `amount` (> 0) | `KillEntityObjective` | `EntityDeathEvent` (`QuestEntityDeathListener`) — **seulement** si `event.getEntity().getKiller()` est le joueur (kill direct) | Global, idem (ex. `first_steps` : `entity: SPIDER`, `amount: 10`). **Limite connue** : une mort provoquée indirectement (piège, chute, autre mob, dégât de zone) ne compte jamais, même si le joueur en est la cause ultime — cohérent avec `SpiderFangDropListener` (TC-043). |
| `COLLECT_ITEM` | `material`, `amount` (> 0) | `CollectItemObjective` | `EntityPickupItemEvent` (`QuestItemPickupListener`, `ignoreCancelled = true`) | Global, idem. **Limite connue** : compte uniquement un ramassage physique au sol par le joueur ; recevoir l'objet autrement (coffre, `/give`, craft, troc marchand) ne progresse jamais cet objectif. |
| `CRAFT_ITEM` | `material`, `amount` (> 0) | `CraftItemObjective` | `CraftItemEvent` (`QuestCraftItemListener`, `ignoreCancelled = true`) — matériau du résultat de la recette (`event.getRecipe().getResult().getType()`) | Global, idem. **Limite connue** (documentée dans `MANUAL_TEST_PLAN.md` TC-012) : ne distingue pas un objet personnalisé d'un objet vanilla du même `Material`. |
| `TALK_TO_NPC` | `npc` (id logique RPGQuest attribué par `/rpgadmin npc tag`, **pas** le nom affiché — voir section 5) | `TalkToNpcObjective` | `PlayerInteractEntityEvent` (`QuestNpcInteractListener`, entité vanilla/Citizens non géré) **ou** `NPCRightClickEvent` (`QuestCitizensNpcInteractListener`, uniquement si Citizens est actif et gère l'entité) — jamais les deux sur la même entité | Implicitement lié au monde où se trouve le PNJ visé, mais le champ lui-même ne porte pas de monde. À ne pas confondre avec l'identification par nom affiché utilisée par le système de **dialogue** (section 4/5) : `TALK_TO_NPC` (quête) exige un id logique posé au préalable via `/rpgadmin npc tag`, une entité renommée sans être taguée ne progresse jamais cet objectif. |
| `REACH_LOCATION` | `world`, `x`, `y`, `z`, `radius` (> 0) | `ReachLocationObjective` | `PlayerMoveEvent` (`QuestLocationListener`, `ignoreCancelled = true`, ignore les mouvements qui ne changent pas de bloc) — distance euclidienne comparée à `radius` | **Seul type explicitement lié à un monde précis** — `world` est un simple nom (résolu à l'évaluation, pas au chargement) ; un déplacement dans un autre monde n'est jamais candidat, même avec les mêmes coordonnées. |

Dans tous les cas, la progression n'a lieu que si, au moment de l'événement, la quête est `ACTIVE` pour ce joueur **et** l'objectif appartient à l'étape actuellement active (`QuestProgressEngine#handleCandidates`, couvert par `QuestProgressEngineTest#objectiveEventBeforeAcceptingTheQuestIsIgnored` et `#objectiveOnALaterStepIsIgnoredUntilItsOwnStepIsActive`) — un événement pour une quête non acceptée, abandonnée, terminée, ou pour une étape pas encore atteinte, est silencieusement ignoré.

Exemples minimaux (champs vérifiés dans le code, valeurs d'illustration) :

```yaml
- type: BREAK_BLOCK
  material: OAK_LOG
  amount: 20

- type: KILL_ENTITY
  entity: SPIDER
  amount: 10

- type: TALK_TO_NPC
  npc: woodcutter_bob        # id posé par /rpgadmin npc tag, jamais le nom affiché

- type: REACH_LOCATION
  world: world
  x: 120.0
  y: 64.0
  z: -40.0
  radius: 5.0
```

Récompenses (`rewards[].type`, hors périmètre strict de la question mais nécessaires à tout exemple complet) : `EXPERIENCE` (`amount`), `ITEM`, `VARIABLE` (`key`/`value`), `COMMAND` (`command`, liste blanche via `dialogue.allowed-commands` **non requise** ici — seules les actions `RUN_SAFE_COMMAND` de dialogue sont filtrées, une récompense `COMMAND` de quête ne l'est pas, vérifié dans `QUEST_FORMAT.md`/`QuestDefinitionParser`).

**Feedback de remise** (`QuestProgressEngine#turnIn`) : un Title/Subtitle bref (`quest.completed-title`/`-subtitle`, `messages.yml`) annonce la fin, puis un résumé est envoyé dans le chat (`quest.reward-summary-header` + une ligne par récompense **réellement accordée** — `quest.reward-line-experience`/`-item`/`-special`). `VARIABLE` n'a pas de ligne (état interne, pas une récompense visible du joueur) ; `COMMAND` affiche une ligne générique (« Récompense spéciale ») car le contenu d'une commande arbitraire n'est pas inspectable — jamais de nom d'objet inventé. Une quête sans `rewards` n'envoie aucun résumé chat. Le journal (`QuestJournalService`) continue d'afficher les récompenses **prévues** dans le lore de chaque quête, y compris avant complétion.

---

## 4. Dialogues

Un dialogue est un graphe de nœuds (locuteur, texte, choix) reliés par
`next`, avec conditions de visibilité et actions par choix. Fichiers YAML
dans `plugins/RPGQuest/dialogues/*.yml` (un par dialogue), jamais
rechargeables à chaud (redémarrage complet requis — voir section 15).
Implémentation : `dialogue.DialogueDefinitionParser` (validation),
`dialogue.session.DialogueSessionEngine` (exécution). Référence complète :
[DIALOGUE_FORMAT.md](../DIALOGUE_FORMAT.md), [docs/NPC_DIALOGUES_QUESTS_GUIDE.md](NPC_DIALOGUES_QUESTS_GUIDE.md).
Page docs-site : `dialogues.html`.

### Format YAML

```yaml
id: rpgquest:guide            # namespacé ; sans ":", namespace "rpgquest" par défaut
start: greeting                # id du nœud de départ

nodes:
  greeting:
    speaker: "Guide"
    text: "<white>Bienvenue au village !</white>"   # MiniMessage, ou table de traductions (clé "default" obligatoire)
    choices:
      - text: "Très bien, j'y vais."
        conditions:
          - type: QUEST_STATE
            quest: rpgquest:premiers_pas
            state: NOT_STARTED
        actions:
          - type: START_QUEST
            quest: rpgquest:premiers_pas
        next: null              # id d'un autre nœud du même dialogue, optionnel
      - text: "D'accord !"
        actions:
          - type: CLOSE
```

Champs : `id`/`start`/`nodes` obligatoires ; `nodes.<id>.speaker`/`text`/
`choices` obligatoires ; `choices[].text` obligatoire, `conditions`/
`actions`/`next` optionnels.

**Conditions** (`choices[].conditions[].type`) : `QUEST_STATE` (`quest`,
`state` parmi `NOT_STARTED|ACTIVE|READY_TO_TURN_IN|COMPLETED|FAILED|ABANDONED`),
`HAS_ITEM` (`material`, `amount`), `HAS_PERMISSION` (`permission`),
`VARIABLE_EQUALS` (`key`, `value`). Revérifiées au clic, pas seulement à
l'affichage.

**Actions** (`choices[].actions[].type`) : `START_QUEST`, `ADVANCE_QUEST`,
`TURN_IN_QUEST` (champ `quest`) ; `GIVE_ITEM`/`TAKE_ITEM` (`material`,
`amount`) ; `SET_VARIABLE` (`key`, `value`) ; `RUN_SAFE_COMMAND`
(`command` — le **nom** de la commande doit être dans `config.yml` →
`dialogue.allowed-commands`, vérifié au chargement) ; `OPEN_DIALOGUE`
(`dialogue`) ; `OPEN_MERCHANT` (`merchant`, voir section 12) ; `CLOSE`.
`OPEN_DIALOGUE`/`CLOSE` prennent le pas sur `next`.

**Renderer** : `config.yml` → `dialogue.renderer`, défaut réel
**`paper-dialog`** (API Dialog native Paper, marquée expérimentale par
Paper) ; alternative `chat` (liens cliquables `ClickEvent.callback`,
compatible tout client, aucune API instable). ⚠️ Divergence constatée :
`README.md` (racine) affirme à tort que `chat` est la valeur par défaut —
c'est `paper-dialog` dans le `config.yml` généré réellement ; `docs-site/dialogues.html` a la bonne valeur.

### Convention id dialogue ↔ id PNJ

Cliquer sur un PNJ identifié `X` (voir section 5) ouvre automatiquement le
dialogue `id: rpgquest:X` s'il existe — aucune configuration
supplémentaire.

### `/dialogue open <joueur> <dialogueId>`

Type : Admin
Permission : `rpgquest.admin`
But : ouvrir un dialogue à distance pour un joueur en ligne (test, ou action
d'un autre système), sans qu'il soit devant un PNJ.
Syntaxe : `/dialogue open <joueur> <dialogueId>`
Exemple : `/dialogue open Steve rpgquest:guide`
Effet : ouvre immédiatement la session de dialogue chez le joueur ciblé.
Persistance : non — session de dialogue **en mémoire uniquement** (fermée
sur déconnexion, jamais restaurée).
À savoir : `dialogueId` sans `:` prend le namespace `rpgquest` par défaut ;
joueur hors-ligne → message d'erreur, aucune action.

---

## 5. NPC / Citizens

RPGQuest identifie un PNJ par un **id logique stable** (ex. `guide`),
totalement indépendant de son nom affiché (purement cosmétique) et de tout
id interne Citizens. Deux backends transparents pour l'appelant :

-   **PNJ Citizens** (prioritaire si Citizens est installé et actif,
    `softdepend: Citizens`) : le mapping `NPC#getUniqueId()` (UUID stable
    garanti par Citizens) → id logique est stocké dans **`data.db`**
    (table `npc_citizens_bindings`), jamais sur l'entité Bukkit — Citizens
    recrée une entité Bukkit éphémère à chaque (re)spawn/redémarrage, donc
    tout ce qui serait posé sur l'entité elle-même serait perdu.
-   **Entité vanilla ordinaire** (Citizens absent, ou entité non gérée par
    Citizens) : id stocké dans le `PersistentDataContainer` de l'entité
    (`rpgquest:npc_id`), survit nativement aux redémarrages.

Implémentation : `npc.NpcIdentityService` (façade unique), `npc.CitizensNpcBridge`
(seul point de contact avec l'API Citizens — jamais chargé si Citizens est
absent). Page docs-site : `npc.html`. Référence complète :
[docs/NPC_DIALOGUES_QUESTS_GUIDE.md](NPC_DIALOGUES_QUESTS_GUIDE.md) section 1.

### Commandes RPGQuest — `/rpgadmin npc`

Documentées en détail en **section 2 (Administration)** ; résumé :
`/rpgadmin npc tag [id]` (id auto-généré `npc_<n>` si omis) | `untag` |
`info`, permission `rpgquest.admin.world`, ciblent toujours l'entité
regardée à ≤ 6 blocs. `tag` est idempotent (ré-étiqueter exige `untag`
d'abord). Aucune liste globale des PNJ identifiés n'existe (il faut viser
physiquement l'entité).

### Commandes Citizens (plugin externe 2.0.43) réellement utilisées dans ce projet

Type : Plugin externe (Citizens) — non implémentées par RPGQuest, non
vérifiables dans ce code source (comportement standard Citizens, à valider
en jeu). RPGQuest ne fournit ni ne modifie aucune commande Citizens.
D'après `docs/NPC_DIALOGUES_QUESTS_GUIDE.md` et `docs/deployment/VERYGAMES.md`,
seules ces commandes sont effectivement citées dans la procédure du projet :

| Commande | Usage dans ce projet |
|---|---|
| `/npc create <nom>` | Crée le PNJ Citizens à la position courante (ex. `/npc create Guide`). |
| `/npc select <id>` | Sélectionne un PNJ Citizens existant par son id numérique, pour ensuite viser/gérer via `/rpgadmin npc info`. |
| `/npc rename <nom>` | Change le nom cosmétique affiché (sans effet sur l'id logique RPGQuest). |
| `/npc remove` | Supprime le PNJ Citizens visé/sélectionné. **⚠️ Ne nettoie pas le mapping RPGQuest** (`npc_citizens_bindings`) — faire `/rpgadmin npc untag` **avant** de supprimer. |

`/npc list`, `/npc tp`, `/npc tphere`, `/npc tpto`, `/npc skin` : commandes
Citizens standard, **aucune trace d'usage dans ce dépôt** (code, docs/,
docs-site/) — non documentées ici pour ne pas inventer un usage projet qui
n'est pas vérifié ; se référer à la documentation Citizens officielle si
besoin.

### Fichiers Citizens

-   `plugins/Citizens/saves.yml` — stockage des PNJ Citizens (référencé dans
    [docs/deployment/VERYGAMES.md](deployment/VERYGAMES.md), à traiter comme
    une **unité indissociable** avec `plugins/RPGQuest/data.db` lors d'une
    migration, voir section 17).
-   `skins/`, `shops.yml` (Citizens Trait shop) : **aucune référence dans ce
    dépôt** — non utilisés dans ce projet à ce jour, ne pas documenter de
    procédure les concernant.

### Exemple réel (environnement de développement, `world_hub`)

-   id Citizens numérique `0` → PNJ **Guide** (id logique RPGQuest `guide`).
-   id Citizens numérique `1` → PNJ **Libraire** (id logique RPGQuest
    `libraire`).

Procédure complète (créer → tagger → dialogue → quête) : voir
`docs/NPC_DIALOGUES_QUESTS_GUIDE.md` section 6.

### Limites connues

-   Suppression Citizens (`/npc remove`) ne nettoie pas le mapping RPGQuest.
-   Pas de liste globale des PNJ identifiés.
-   Aucune fonctionnalité Citizens avancée (patrouilles, hologrammes,
    traits...) exposée par RPGQuest — géré entièrement côté Citizens.
-   Aucune protection/persistance automatique du PNJ lui-même (pas
    invulnérable, pas de résurrection) : à protéger via zone/claim.

---

## 6. Portails

Deux systèmes de portails **distincts**, à ne pas confondre — vérifié dans `RpgAdminCommand.java` et `docs/TRAVEL.md`.

| | Portail « classique » (`/rpgadmin portal`) | Portail « simple » (`/rpgadmin worldportal`) |
|---|---|---|
| Usage typique | Village ↔ zones d'aventure, **même monde ou entre mondes**, via une **destination** précise (position exacte) | Passage rapide **entre deux mondes** (ex. Hub → monde `wild`), sans position précise — juste « le spawn (ou une zone aléatoire sûre) du monde destination » |
| Canalisation | Oui, délai configurable (3 s par défaut), annulée si mouvement/dégâts/déconnexion | Non (voir `WorldPortalTeleportListener`) |
| Conditions d'accès | Permission, quête, niveau, coût en pièces (optionnels) | Aucune condition (activation immédiate à l'entrée de la zone, si le portail est activé) |
| Destination | `Destination` nommée (monde, x, y, z, yaw, pitch exacts), réutilisable par plusieurs portails | Juste un nom de monde + une `DestinationStrategy` (`WORLD_SPAWN` ou `RANDOM_SAFE`) |
| Cooldown persisté | Oui (par joueur/portail, SQLite `portal_cooldowns`, migration V6) | Non documenté dans le code lu — aucun cooldown constaté dans `WorldPortalDefinition` |
| Fichiers | `plugins/RPGQuest/portals/<id>.yml` + `plugins/RPGQuest/destinations/<id>.yml` | `plugins/RPGQuest/world-portals/<id>.yml` (registre `WorldPortalRegistry`) |

### `/rpgadmin portal` — commandes
Détail complet (sécurité de destination, canalisation, coût) : [docs/TRAVEL.md](TRAVEL.md). Page docs-site : aucune.

| Commande | Effet | Persistance |
|---|---|---|
| `/rpgadmin portal create <id>` | Crée un portail cuboïde depuis la sélection `/rpgadmin zone wand` (délai 3 s, cooldown 5 s, aucune condition par défaut). | oui — fichier YAML |
| `/rpgadmin portal delete <id>` | Supprime le portail. | oui |
| `/rpgadmin portal list` | Liste les portails chargés (id, monde, destination). | non |
| `/rpgadmin portal info <id>` | Bornes, destination, canalisation/cooldown, conditions (permission/quête/niveau/coût). | non |
| `/rpgadmin portal setdestination <id> <destinationId>` | Crée/met à jour la destination `<destinationId>` **à la position exacte de l'admin**, puis relie le portail à cette destination — seule façon de créer une destination. | oui — écrit le portail **et** la destination |

À savoir : un portail sans destination configurée ne fait rien (message affiché, aucune canalisation). Sécurité de destination : avant toute téléportation, le monde doit exister, une position sûre est recherchée (balayage vertical ±5 blocs, aucun bloc dangereux/solide) — sinon aucune téléportation, aucun débit. Le coût n'est débité **qu'après** résolution réussie de la destination.

### `/rpgadmin worldportal` — commandes
Page docs-site : `worlds.html` (mais voir la note d'obsolescence en section 19).

| Commande | Effet | Persistance |
|---|---|---|
| `/rpgadmin worldportal create <id> <destinationWorld> [world_spawn\|random_safe]` | Crée un portail simple depuis la sélection `zone wand` ; stratégie `world_spawn` par défaut si omise. | oui |
| `/rpgadmin worldportal info <id>` | Monde source, bornes, destination, actif/inactif, stratégie. | non |
| `/rpgadmin worldportal list` | Liste les portails simples. | non |
| `/rpgadmin worldportal enable <id>` / `disable <id>` | Active/désactive le déclenchement (la configuration est conservée, désactivé = aucun effet à l'entrée). | oui |
| `/rpgadmin worldportal delete <id>` | Supprime définitivement (fichier + mémoire). | oui |

Stratégies de destination (`travel.model.DestinationStrategy`, vérifié dans le code) :
-   **`WORLD_SPAWN`** — `World#getSpawnLocation()` du monde destination, résolue à chaque activation.
-   **`RANDOM_SAFE`** — position aléatoire sûre autour du spawn du monde destination (`travel.RandomSafeLocationFinder`), repli automatique sur `WORLD_SPAWN` si aucune position sûre trouvée. Réglages (`config.yml` → `travel.random-safe-arrival`, vérifiés dans `RandomSafeArrivalConfig`/`config.yml`) : `min-radius: 500`, `max-radius: 5000`, `max-attempts: 20` (distance autour du **spawn du monde**, pas du portail).

---

## 7. Mondes

Vérifié dans `RpgAdminCommand.java` (`handleWorld*`), `hub/HubWorldRulesService.java`, `config.yml`, et [VERYGAMES.md](deployment/VERYGAMES.md).

### Spawn Minecraft vs Multiverse vs RPGQuest — bien distinguer

| Spawn | Défini par | Référence gameplay ? |
|---|---|---|
| Spawn Minecraft/Paper (`World#getSpawnLocation()`) | Génération du monde ou `/setworldspawn` vanilla | Non — seulement utilisé en repli par `DestinationStrategy.WORLD_SPAWN` et par `/rpgadmin world tp` |
| Spawn Multiverse (`/mv setspawn`) | Commande Multiverse-Core | **Non** — `VERYGAMES.md` est explicite : « le spawn Multiverse n'est pas la référence gameplay » |
| Spawn RPGQuest (village) | `/rpgadmin spawn set` | **Oui** — seule source de vérité pour le point d'apparition du village, gérée par `SpawnService` |

### `/rpgadmin world` — mondes supplémentaires
Page docs-site : `worlds.html` (⚠️ obsolète sur le point Multiverse/Hub, voir section 19).

| Commande | Effet | Persistance |
|---|---|---|
| `/rpgadmin world create <name>` | Crée (première fois) ou recharge (dossier déjà présent) un monde en environnement `NORMAL`, seed aléatoire, puis le charge. | non (le monde lui-même est un dossier disque, pas une entrée base) |
| `/rpgadmin world tp <name>` | Téléporte au spawn Bukkit de ce monde (ex. `world` pour revenir au monde principal). | non |
| `/rpgadmin world list` | Liste les mondes actuellement chargés, avec un tag « (géré par RPGQuest) » pour ceux créés/suivis par `WorldService`. | non |

À savoir : nom de monde valide = minuscules/chiffres/`.`/`_`/`-`. `world tp` échoue si le monde n'est pas chargé (le créer/charger d'abord).

### Le monde Hub (`world_hub`) et `HubWorldRulesService`

Nom du monde lu **uniquement** depuis `hub.world` dans `config.yml` (défaut `world_hub` si la section `hub:` est absente ou incomplète — jamais codé en dur). Réappliqué automatiquement au démarrage du plugin **et** à chaque (re)chargement tardif du monde (`WorldLoadEvent`, ex. Multiverse-Core qui charge `world_hub` après RPGQuest) — jamais via une commande `/gamerule` manuelle.

Règles appliquées (idempotentes) :
-   jour permanent (heure figée à midi, `ADVANCE_TIME` désactivé) ;
-   météo permanente (`ADVANCE_WEATHER` désactivé, pas de pluie/orage) ;
-   dégâts joueurs **toujours annulés** (aucune exception, même admin) → PvP bloqué de fait ;
-   casse/pose de bloc bloquée sauf bypass `rpgquest.admin.world` ;
-   explosions sans destruction de bloc (`blockList()` vidée) ;
-   aucun spawn naturel de mob hostile ;
-   claims interdits (`ClaimService` refuse toute création dans le monde exact de `hub.world`, voir section 8).

Log attendu au démarrage/chargement :
```
Règles du monde Hub appliquées : world_hub (jour et météo permanents).
```
Son absence après un redémarrage signale que `world_hub` n'a pas été détecté (nom de monde incorrect au transfert, ou `hub.world` mal configuré).

### Multiverse-Core en production

`softdepend`/dépendance dure : RPGQuest **ne dépend techniquement d'aucun** des trois plugins externes pour démarrer (`plugin.yml` ne déclare que `softdepend: [Citizens]`) — Multiverse-Core est néanmoins installé et utilisé en production VeryGames pour gérer `world_hub` (import, listing). Voir section 1 pour `/mv import`/`/mv list`.

### Mondes futurs (`wild`, claims...) — état réel

Aucune trace dans le code actuel (`RpgAdminCommand`, `WorldService`) d'un monde `wild` prédéfini ou d'un traitement spécial par nom de monde autre que `hub.world` : `/rpgadmin world create <name>` crée un monde générique en environnement `NORMAL`, sans distinction. Un monde `wild` mentionné dans `docs-site/worlds.html` (exemple d'usage avec `worldportal`) est un **exemple d'utilisation de la fonctionnalité générique**, pas un monde livré ou codé en dur — à traiter comme *Prévu / exemple*, pas comme une fonctionnalité dédiée implémentée.

---

## 8. Claims

Vérifié dans `src/main/java/com/lodygames/rpgquest/command/ClaimCommand.java`, `src/main/java/com/lodygames/rpgquest/claim/ClaimService.java`, `docs/CLAIMS.md`.

Système de claims de terrain protégés, créés et gérés par les joueurs eux-mêmes (pas d'administrateur requis). Toutes les sous-commandes qui agissent sur un claim précis (`delete`, `info`, `trust`, `untrust`, `flag`) opèrent sur **le claim où le joueur se trouve**, jamais sur un id tapé à la main — seule `create` prend un id explicite.

### `/claim wand`
Type : Joueur — Permission : `rpgquest.claim`
But : obtenir l'outil de sélection de claim (distinct de l'outil de sélection de zone protégée/portail).
Syntaxe : `/claim wand`
Effet : donne une hache marquée par PersistentDataContainer (jamais reconnue par son nom). Clic gauche = position 1, clic droit = position 2.
Persistance : non (sélection en mémoire, par joueur).
À savoir : la sélection est propre à `/claim`, ne partage pas son état avec `/rpgadmin zone wand`/`/rpgadmin portal`.

### `/claim create <id>`
Type : Joueur — Permission : `rpgquest.claim`
But : créer un claim cuboïde depuis la sélection courante.
Syntaxe : `/claim create <id>`
Exemple : `/claim create ma_ferme`
Effet : crée le claim si toutes les vérifications passent (voir refus ci-dessous) ; échec = rien n'est écrit.
Persistance : oui — SQLite (`claims`), migration V7.
À savoir : refusé si — id invalide ; positions dans des mondes différents ; id déjà pris ; sélection trop grande (`claims.max-width`/`max-height`, 64×384 par défaut) ; nombre max de claims atteint (`claims.max-claims-per-player`, 3 par défaut + 1 tous les 10 niveaux `GLOBAL`, voir `ClaimService#effectiveMaxClaims`) ; chevauche un claim ou une zone protégée existants ; trop proche d'un portail (`claims.portal-buffer-blocks`, 16 par défaut) ; **monde interdit** — voir ci-dessous.

**Monde interdit — vérifié dans le code** (`ClaimService.create`) :
```java
if (world.equals(configService.current().hub().world())) {
    return CompletableFuture.completedFuture(CreateOutcome.FORBIDDEN_WORLD);
}
```
Le monde interdit n'est pas codé en dur sous le nom `world_hub` : c'est **exactement** la valeur de `hub.world` dans `config.yml` (par défaut `world_hub`, voir `HubConfig`). Si `hub.world` est reconfiguré, l'interdiction suit automatiquement. Message joueur : « Les claims sont interdits dans le monde Hub. »

### `/claim delete`
Type : Joueur (propriétaire uniquement) — Permission : `rpgquest.claim`
But : supprimer le claim où le joueur se trouve.
Syntaxe : `/claim delete`
Effet : supprime le claim (cascade sur ses membres en base) ; refusé si le joueur n'est pas propriétaire ou n'est dans aucun claim.
Persistance : oui — suppression SQLite.
À savoir : bypass admin `rpgquest.admin.world` exempte l'acteur des protections mais ne donne pas le droit de `delete`/`trust`/`flag` sur le claim d'autrui (ces sous-commandes vérifient explicitement la propriété, indépendamment du bypass de protection).

### `/claim info`
Type : Joueur — Permission : `rpgquest.claim`
But : afficher le détail du claim où le joueur se trouve.
Syntaxe : `/claim info`
Effet : affiche propriétaire, monde, bornes, nombre de membres, état du flag redstone publique.
Persistance : non (lecture seule).

### `/claim trust <joueur>` / `/claim untrust <joueur>`
Type : Joueur (propriétaire uniquement) — Permission : `rpgquest.claim`
But : ajouter/retirer un membre de confiance sur le claim où le joueur se trouve.
Syntaxe : `/claim trust <joueur>` · `/claim untrust <joueur>`
Exemple : `/claim trust Steve`
Effet : le joueur cible (doit être **en ligne**) devient/cesse d'être membre — les membres échappent aux protections (casse/pose, conteneurs, animaux, armor stands).
Persistance : oui — SQLite (`claim_members`).
À savoir : cible introuvable si hors ligne (aucune résolution offline ici, contrairement à `claim info` qui résout le nom du propriétaire même hors ligne).

### `/claim list`
Type : Joueur — Permission : `rpgquest.claim`
But : lister ses propres claims.
Syntaxe : `/claim list`
Effet : liste id + monde de tous les claims dont l'exécutant est propriétaire, où qu'il se trouve.
Persistance : non (lecture seule).

### `/claim flag redstone <true|false>`
Type : Joueur (propriétaire uniquement) — Permission : `rpgquest.claim`
But : autoriser ou non les non-membres à utiliser boutons/leviers/portes/dalles de pression du claim où le joueur se trouve.
Syntaxe : `/claim flag redstone <true|false>`
Exemple : `/claim flag redstone true`
Effet : seule protection réellement configurable (les autres — blocs, conteneurs, animaux, armor stands, explosions, pistons traversant la frontière — sont fixes, non configurables).
Persistance : oui — SQLite (flags du claim).

### Protections (résumé, non configurables sauf redstone)

| Catégorie | Configurable ? | Comportement |
|---|---|---|
| Blocs (casse/pose) | non | Bloqué pour tout non-membre |
| Conteneurs | non | Bloqué pour tout non-membre |
| Animaux (dégâts) | non | Bloqué pour tout non-membre |
| Armor stands | non | Bloqué pour tout non-membre |
| Redstone | **oui** (`/claim flag redstone`) | Membre uniquement par défaut |
| Explosions | non | Toujours bloquées (destruction de bloc empêchée) |
| Pistons traversant la frontière | non | Toujours bloqué |

Bypass : `rpgquest.admin.world` (même permission que le bypass des zones protégées) exempte l'acteur direct d'une action, jamais la victime.

### Prévu / TODO

-   **Agrandissement de claim par le propriétaire (largeur/hauteur au-delà du niveau RPG)** : non implémenté. Seul le **nombre** maximal de claims augmente avec le niveau `GLOBAL` (+1 tous les 10 niveaux) ; largeur/hauteur restent fixes (`config.yml`, identiques pour tous). Aucun avantage payant n'est prévu (politique confirmée dans `docs/CLAIMS.md`).
-   Aucune autre évolution de claims trouvée dans `TODO.md` à la racine.

---

## 9. Items / Équipements

Les objets personnalisés (armes, outils, ressources, objets de quête) sont des fichiers YAML dans `plugins/RPGQuest/items/*.yml` (quatre exemples générés au premier démarrage : `forest_blade`, `miner_pickaxe`, `spider_fang`, `refined_crystal`). Identification **exclusivement** via `PersistentDataContainer` — un objet vanilla renommé pour imiter un objet personnalisé n'est jamais reconnu. Format complet et règles de validation : [CUSTOM_ITEMS.md](../CUSTOM_ITEMS.md) ; comportements de combat/outil (`combat:`, `tool:`) et détail d'implémentation : [docs/ARCHITECTURE.md](ARCHITECTURE.md).

Exemple minimal (arme) :
```yaml
id: rpgquest:forest_blade
type: WEAPON            # WEAPON | TOOL | RESOURCE | QUEST_ITEM
material: DIAMOND_SWORD
name: "<green>Lame de la forêt</green>"
rarity: RARE             # COMMON | UNCOMMON | RARE | EPIC | LEGENDARY
stackable: false
```

Les recettes (façonnées `SHAPED` ou sans forme `SHAPELESS`) sont des fichiers YAML séparés dans `plugins/RPGQuest/recipes/*.yml` (trois exemples : `forest_blade_recipe`, `refined_crystal_recipe`, `miner_pickaxe_recipe`), enregistrées comme vraies recettes Bukkit au démarrage. Un ingrédient `custom-item:` est vérifié par PersistentDataContainer à chaque préparation de fabrication (clic simple, shift-clic, recette automatique). Format complet : [RECIPE_FORMAT.md](../RECIPE_FORMAT.md).

### `/customitem give <joueur> <id> [quantité]`
Type : Admin — Permission : `rpgquest.admin`
But : donner un objet personnalisé à un joueur en ligne.
Syntaxe : `/customitem give <joueur> <id> [quantité]`
Exemple : `/customitem give Notch rpgquest:forest_blade 1`
Effet : ajoute l'objet à l'inventaire (surplus lâché au sol si l'inventaire est plein) ; message au joueur cible s'il diffère de l'exécutant.
Persistance : non (objet créé à la volée depuis la définition YAML).
À savoir : `id` accepte soit un id namespacé complet (`rpgquest:forest_blade`), soit juste le nom court (préfixé automatiquement par `rpgquest:`) ; échoue si le joueur est hors-ligne, l'id inconnu, ou la quantité hors de `[1, max-stack-size effectif]`.

### `/customitem list`
Type : Admin — Permission : `rpgquest.admin`
But : lister les objets personnalisés chargés (id, type, rareté).
Syntaxe : `/customitem list`
Persistance : non.

### `/customitem inspect`
Type : Joueur — Permission : `rpgquest.item`
But : identifier l'objet personnalisé tenu en main principale.
Syntaxe : `/customitem inspect`
Effet : affiche type, matériau, rareté, empilabilité, durabilité, attributs, enchantements, tags de gameplay.
Persistance : non.
À savoir : ne fonctionne que sur un objet réellement reconnu par PDC ; un objet vanilla imitant l'apparence n'affiche rien.

## 10. Ressources

Les **types** de nœuds de ressource sont des fichiers YAML dans `plugins/RPGQuest/resource-nodes/*.yml` (un exemple généré : `crystal_ore`) : bloc actif/épuisé vanilla, outils autorisés, temps de respawn, table de drops pondérée (un seul tirage par récolte). Les **positions** concrètes sont créées en jeu et persistées par monde en SQLite (survivent à un redémarrage, y compris un cooldown en cours). Format complet : [RESOURCE_NODE_FORMAT.md](../RESOURCE_NODE_FORMAT.md) ; détail d'implémentation et garanties anti-exploitation : [docs/ARCHITECTURE.md](ARCHITECTURE.md).

Exemple minimal :
```yaml
id: rpgquest:crystal_ore
active-material: EMERALD_ORE
depleted-material: STONE
required-tools: [IRON_PICKAXE]   # vide = n'importe quel outil
respawn-seconds: 300
drops:
  - material: QUARTZ
    weight: 100
    min-amount: 1
    max-amount: 3
```

Les trois commandes ci-dessous partagent : Type Admin, Permission `rpgquest.admin`, agissent sur le **bloc visé** (portée 6 blocs).

### `/resourcenode create <typeId>`
But : place un nœud de ressource récoltable sur le bloc visé.
Syntaxe : `/resourcenode create <typeId>`
Exemple : `/resourcenode create rpgquest:crystal_ore`
Persistance : oui — position enregistrée en SQLite (`data.db`), par monde.
À savoir : échoue si le type est inconnu ou si un nœud existe déjà à cette position.

### `/resourcenode remove`
But : retire le suivi du nœud sur le bloc visé — ne touche pas au bloc physique.
Syntaxe : `/resourcenode remove`
Persistance : oui — suppression en base.

### `/resourcenode inspect`
But : afficher le type et l'état (actif / épuisé, temps de respawn restant) du nœud visé.
Syntaxe : `/resourcenode inspect`
Persistance : non (lecture seule).

## 11. Mobs spéciaux

Variantes entièrement vanilla pilotées par YAML dans `plugins/RPGQuest/mobs/*.yml` (quatre exemples générés : `red_creeper`, `golden_creeper`, `creeper_pig`, `splitting_zombie`). Un spawn naturel correspondant au type d'entité/mondes/biomes/zones autorisés et sous la limite de population peut être tiré au sort comme variante ; identification uniquement par PersistentDataContainer. Format complet : [SPECIAL_MOB_FORMAT.md](../SPECIAL_MOB_FORMAT.md) ; détail d'implémentation : [docs/ARCHITECTURE.md](ARCHITECTURE.md).

Les commandes d'administration (`/rpgadmin mob spawn|list|inspect|reload|metrics`, `rpgquest.admin.world`) sont documentées en **section 2 (Administration RPGQuest)** — non répétées ici.

Exemple minimal (registry `id`, `entity-type`, `name`, `spawn-chance` obligatoires) :
```yaml
id: rpgquest:golden_creeper
entity-type: CREEPER
name: "<gold><bold>Creeper Doré</bold></gold>"
spawn-chance: 0.005
abilities:
  - type: STRONGER_EXPLOSION
    radius-multiplier: 1.5
```

Trois `abilities` réellement implémentées (enum `MobAbilityType`, vérifié dans `src/main/java/com/lodygames/rpgquest/mob/model/MobAbilityType.java`) — ne pas en inventer d'autres :

| type | champs requis | rôle |
|---|---|---|
| `STRONGER_EXPLOSION` | `radius-multiplier` (> 0) | multiplie le rayon d'une explosion vanilla qui prime |
| `EXPLOSIVE_ON_ATTACK` | `power` (> 0), `set-fire`, `trigger-range-blocks` (> 0) | rend une entité passive agressive : explosion réelle en approche, puis mort de l'entité |
| `SPLIT_ON_HIT` | `max-depth` (≥ 1), `max-children-per-hit` (≥ 1) | fait apparaître des enfants à chaque coup non mortel, profondeur/nombre strictement bornés |

---

## 12. Marchands / Économie / Marché

Vérifié dans `MoneyCommand.java`, `MerchantCommand.java`, `MarketCommand.java`, `ProfileCommand.java`, `SkillsCommand.java`, `StoreCommand.java`. Détail complet : [docs/ECONOMY.md](ECONOMY.md), [MERCHANT_FORMAT.md](../MERCHANT_FORMAT.md), [docs/PROGRESSION.md](PROGRESSION.md), [docs/STORE.md](STORE.md). Page docs-site : aucune dédiée (l'action de dialogue `OPEN_MERCHANT` est mentionnée dans `dialogues.html`).

### Portefeuille — `/money`

| Commande | Type | Permission | Effet | Persistance |
|---|---|---|---|---|
| `/money` | Joueur | `rpgquest.money` | Affiche le solde de l'exécutant (entier, aucune virgule). | non (lecture) |
| `/money pay <joueur> <montant>` | Joueur | `rpgquest.money` | Transfert atomique vers un joueur **en ligne**. | oui — `wallets`, `transactions` (SQLite, migration V4) |
| `/money admin give\|take\|set <joueur> <montant>` | Admin | `rpgquest.admin` | Crédite/débite/fixe le solde d'un joueur en ligne — seule façon d'introduire de la monnaie hors marchand/vente. | oui |

À savoir : montant toujours un entier strictement positif (`pay`/`give`/`take`) ; `take` échoue proprement si solde insuffisant (aucun solde négatif possible) ; `pay` à soi-même refusé.

### Marchands PNJ — `/merchant` (entièrement admin, aucune sous-commande joueur)

| Commande | Effet | Persistance |
|---|---|---|
| `/merchant reload` | Recharge les marchands depuis le disque, rapport `N chargé(s), N erreur(s)`. | non |
| `/merchant validate` | Même validation sans appliquer. | non |
| `/merchant list` | Liste les marchands chargés (id, nombre d'offres). | non |

Un marchand n'a **aucun lien direct à une entité PNJ** — il ne s'ouvre que via l'action de dialogue `OPEN_MERCHANT` (section 4). Format YAML complet (offres `SELL_TO_PLAYER`/`BUY_FROM_PLAYER`, conditions cumulatives permission/quête/niveau) : [MERCHANT_FORMAT.md](../MERCHANT_FORMAT.md).

### Marché entre joueurs — `/market`

| Commande | Type | Permission | Effet | Persistance |
|---|---|---|---|---|
| `/market` | Joueur | `rpgquest.market` | Ouvre la vitrine partagée (toutes offres actives, paginée). | non |
| `/market sell <prix>` | Joueur | `rpgquest.market` | Met en vente la pile entière tenue en main (objet complet, PDC compris). | oui — `market_listings` (migration V5) |
| `/market cancel <id>` | Joueur | `rpgquest.market` | Annule **sa propre** offre, restitue l'objet (aussi possible en cliquant dessus dans la vitrine). | oui |
| `/market admin list` | Admin | `rpgquest.admin` | Liste en lecture seule toutes les offres actives (modération). | non |

À savoir : clic sur l'offre d'un autre joueur = achat immédiat au prix fixe, vendeur crédité même hors ligne (réservation atomique anti-duplication, voir `MarketRepository`).

### Progression RPG — `/profile`, `/skills`

| Commande | Type | Permission | Effet |
|---|---|---|---|
| `/profile` | Joueur | `rpgquest.progression` | Une ligne par piste (`Global, Combat, Minage, Agriculture, Pêche, Exploration`) — niveau uniquement. |
| `/skills` | Joueur | `rpgquest.progression` | Détail par compétence : niveau + XP dans le niveau courant/XP requise pour le suivant (ou « niveau maximal »). |
| `/skills admin grant\|set <joueur> <compétence> <montant>` | Admin | `rpgquest.admin` | `grant` passe par le pipeline normal (dédup + mirroir `GLOBAL`) ; `set` fixe l'XP totale directement, sans dédup ni mirroir. |

Persistance : `player_skills`, `xp_grants` (dédup anti-farm), `player_placed_blocks` (SQLite, migration V8). Détail complet (courbe, anti-farm, sources d'XP) : [docs/PROGRESSION.md](PROGRESSION.md).

### Boutique web — `/store`

| Commande | Type | Permission | Effet |
|---|---|---|---|
| `/store history [joueur\|uuid]` | Admin | `rpgquest.admin` | Historique des commandes/livraisons (produit, joueur, statut coloré, date), 20 par défaut — interroge **directement web-api**, jamais de copie locale côté plugin. |

À savoir : si `web-api` est injoignable → `Impossible de contacter web-api (voir la console)`. Détail complet (sandbox de paiement, remboursement, idempotence) : [docs/STORE.md](STORE.md). Persistance : `store_deliveries_processed` (SQLite, migration V10) côté plugin ; base séparée côté `web-api`.

---

## 13. Backpacks

Vérifié dans `BackpackCommand.java`. Détail complet : [docs/BACKPACKS.md](BACKPACKS.md). Page docs-site : aucune.

| Commande | Type | Permission | Effet | Persistance |
|---|---|---|---|---|
| `/backpack` | Joueur | `rpgquest.backpack` | Ouvre le backpack (palier selon l'avantage détenu, ou `SMALL` via `rpgquest.backpack.free` en secours) ; « Tu n'as accès à aucun backpack pour l'instant » si aucun accès. | non (ouverture) |
| `/backpack recover [numéro]` | Joueur | `rpgquest.backpack` | Sans numéro : liste la boîte de récupération (surplus après réduction de taille). Avec un numéro : réclame l'entrée (dépose au sol si l'inventaire est plein). | oui — dépile l'entrée réclamée |
| `/backpack admin grant <joueur> <taille>` | Admin | `rpgquest.admin` | Accorde un palier (`SMALL`/`MEDIUM`/`LARGE`) via `EntitlementService`, redimensionne immédiatement en conservant le contenu. | oui |
| `/backpack admin revoke <joueur>` | Admin | `rpgquest.admin` | Retire l'avantage explicite, retombe sur la taille effective restante (ex. `rpgquest.backpack.free`). | oui |

Persistance : `player_entitlements`, `backpacks`, `backpack_overflow`, `backpack_audit` (SQLite, migration V9). Sauvegarde à la fermeture/déconnexion/arrêt du plugin. Un downgrade compacte le contenu et bascule le surplus dans la boîte de récupération (jamais perdu) — un upgrade ne fait jamais déborder.

---

## 14. WorldEdit

**RPGQuest n'utilise WorldEdit dans aucun de ses propres outils** : la sélection utilisée par `/rpgadmin zone wand`, `/rpgadmin portal create` et `/claim wand` est un outil **propre à RPGQuest** (item marqué PersistentDataContainer, `ZoneSelectionService`/`ClaimCommand`), pas WorldEdit `//wand`. Ne pas confondre les deux.

**Piège constaté en test réel** (VeryGames, WorldEdit 7.4.1) : la reconnaissance par PDC protège contre un *nom* usurpé, mais pas contre WorldEdit lui-même — WorldEdit reconnaît sa propre wand par **type d'objet** (`wand-item` dans sa config, `minecraft:wooden_axe` par défaut), sans se soucier du PDC. La wand de `/rpgadmin zone wand` était initialement une hache en bois : WorldEdit la traitait donc *aussi* comme la sienne (ses propres messages « Première/Seconde position définie », sa propre sélection, événement parfois annulé avant que RPGQuest ne le voie) et la sélection RPGQuest n'était jamais enregistrée. Corrigé en donnant à chaque wand RPGQuest un matériau qu'aucun plugin tiers connu (WorldEdit inclus) n'utilise par défaut (tige de blaze pour `zone`/`portal`, houe en bois pour `claim`) — règle à respecter pour tout futur outil de sélection du projet.

WorldEdit est installé en production VeryGames uniquement pour la **préparation manuelle de terrain par un administrateur** (voir [VERYGAMES.md](deployment/VERYGAMES.md)), en dehors de toute commande RPGQuest. Cette section reste volontairement minimale — pour la documentation complète, se référer à la documentation officielle WorldEdit.

### `//wand` (ou `/worldedit version`)
Type : Plugin externe (WorldEdit `7.4.1`)
Où l'exécuter : en jeu (admin) ou console, immédiatement après avoir installé le JAR WorldEdit, avant d'installer Citizens/Multiverse/RPGQuest.
But : test d'installation validé dans la procédure VeryGames — confirme que WorldEdit répond sans erreur.
Résultat attendu : `//wand` donne la hache de sélection WorldEdit (ou `/worldedit version` affiche la version) ; `/plugins` liste WorldEdit en vert.
À savoir : c'est le **seul** usage de WorldEdit vérifié dans la documentation du projet — aucune commande `//pos1`/`//pos2`/`//copy`/`//schematic` n'est référencée dans le code ou les procédures RPGQuest ; à documenter séparément si un usage réel émerge (préparation de terrain), en gardant cette section courte (ne pas dupliquer la documentation WorldEdit complète).

---

## 15. Tests et diagnostic

### `./gradlew test` (`gradlew.bat test`)
Type : Console (développement)
But : exécute la suite JUnit complète (plugin + `web-api`), sans démarrer de serveur réel.
Résultat attendu : `BUILD SUCCESSFUL` ; tout `FAILED` doit être corrigé avant de considérer une fonctionnalité terminée (voir `CLAUDE.md` § Build).
À savoir : plusieurs classes de test utilisent MockBukkit — certains comportements (Citizens réel, rendu client, réseau réel) restent hors de portée et sont marqués `PENDING MANUAL VALIDATION` dans [docs/MANUAL_TEST_PLAN.md](MANUAL_TEST_PLAN.md).

### `./gradlew runServer` — voir section 1.

### Commandes de reload utiles en diagnostic
-   `/rpgquest reload` (`rpgquest.admin`) — recharge **uniquement** `config.yml`. En cas de configuration invalide, message explicite affiché et **ancienne configuration conservée** (aucune rupture de service).
-   `/quest admin reload` (`rpgquest.admin`) — recharge quêtes + `messages.yml`, rapport `N chargée(s), N erreur(s)` ; un fichier invalide n'empêche jamais le chargement des autres.
-   `/quest admin validate` — même validation, sans rien appliquer (dry-run).
-   `/merchant reload`/`/merchant validate`, `/rpgadmin mob reload` — même patron (reload applique, validate ne fait qu'un rapport) pour marchands et mobs spéciaux.

### Logs à vérifier après un redémarrage complet
-   `Done (...)! For help, type "help"` puis `RPGQuest <version> activé` — services démarrés dans l'ordre (config, base de données, moteur de quêtes, objets, recettes, nœuds de ressource, dialogues, journal).
-   `Règles du monde Hub appliquées : world_hub (jour et météo permanents).` — voir section 7.
-   Aucune exception au chargement, y compris avec un fichier YAML volontairement invalide présent (rejeté proprement, seul).

### Fichiers de tests manuels — `broken_quest.yml` comme fixture MANUELLE uniquement

`run/plugins/RPGQuest/quests/broken_quest.yml` est un fichier **volontairement invalide**, créé pour tester le rejet propre d'une quête invalide au chargement (TC-011 de [MANUAL_TEST_PLAN.md](MANUAL_TEST_PLAN.md)). **Il ne doit jamais être transféré en production** — voir la procédure de contrôle en section 17 et le piège correspondant en section 18.

### Procédure de reset de joueur
Aucune commande dédiée « reset complet d'un joueur » trouvée dans le code (`/quest admin reset` existe pour les **quêtes** uniquement). Pour repartir d'un état de test propre : soit réinitialiser une quête précise avec cette commande, soit — pour un reset total — supprimer/modifier directement les lignes du joueur dans `data.db` (aucun outil intégré, action manuelle en base à faire avec précaution, jamais en production sans sauvegarde préalable).

### Tests nouveau joueur / Hub / portails — voir [MANUAL_TEST_PLAN.md](MANUAL_TEST_PLAN.md)
Plan de recette structuré en 18 sections (TC-001 à TC-183), chaque cas référençant la classe réelle qui l'implémente. Points clés pour un nouveau joueur/Hub/portails :
-   **Nouveau joueur** : `/rpgquest profile` crée le profil au premier join (`PlayerConnectionListener`) ; solde initial `0` pièce (aucun don de départ) ; aucun backpack sans `rpgquest.backpack.free` (palier `SMALL` de secours, activé par défaut).
-   **Hub** : voir checklist complète en section 7/VERYGAMES.md (jour/météo fixes, dégâts annulés, casse/pose bloquée sauf admin, PvP bloqué, claims interdits).
-   **Portails** : create → sans destination → aucune canalisation ; setdestination → canalisation puis téléportation uniquement si position sûre trouvée ; cooldown persisté (survit reconnexion/redémarrage).

---

## 16. Fichiers importants

Carte des dossiers concernés par une installation RPGQuest (VeryGames comme local) :

```
plugins/RPGQuest/
├── config.yml              # configuration principale (hub, admin.flatten, travel.random-safe-arrival, web-export, store, client-mod...)
├── messages.yml             # messages joueur personnalisables
├── spawn.yml                 # spawn du village (SpawnService, /rpgadmin spawn)
├── data.db                   # SQLite — TOUTES les données joueur/monde persistées (voir section 17)
├── quests/                   # définitions de quêtes YAML
├── dialogues/                 # définitions de dialogues YAML
├── items/                     # objets personnalisés YAML
├── recipes/                   # recettes YAML
├── resource-nodes/            # types de nœuds de ressource YAML
├── merchants/                  # marchands PNJ YAML
├── mobs/                       # mobs spéciaux YAML
├── zones/                      # zones protégées YAML (dont central_village)
├── portals/                    # portails "classiques" YAML
├── destinations/                # destinations de portails YAML
├── world-portals/                # portails "simples" entre mondes YAML
├── store-products/                # catalogue boutique web (si store.enabled)
└── web-export/snapshot.json        # export périodique lu par web-api (si web-export.enabled)

plugins/Citizens/
├── saves.yml                 # PNJ Citizens — unité indissociable de data.db (voir section 17)
├── skins/                    # (présence non confirmée dans ce dépôt — vérifier avant migration)
└── shops.yml                 # (présence non confirmée dans ce dépôt — vérifier avant migration)

plugins/Multiverse-Core/
└── worlds.yml                 # config par-monde Multiverse (à sauvegarder avant toute opération de migration)

run/                            # environnement de développement local UNIQUEMENT — jamais commité, jamais copié tel quel en production (voir .gitignore, docs/LOCAL_SERVER.md)
build/libs/rpgquest-<version>.jar   # LE jar à déployer en production (jamais un jar depuis run/plugins/)

docs/                            # documentation Markdown de référence (dont ce fichier)
docs-site/                        # version HTML conviviale/navigation visuelle, voir section 19
```

À savoir : la présence de `skins/` et `shops.yml` sous `plugins/Citizens/` n'a été confirmée nulle part dans ce dépôt (code ni docs) au moment de la rédaction — ne pas présumer qu'ils existent sur l'installation VeryGames réelle sans vérification directe en FTP avant une migration.

---

## 17. Persistance / Migration

### Règle d'or : `saves.yml` (Citizens) + `data.db` (RPGQuest) sont une seule unité

RPGQuest identifie les PNJ Citizens par leur id numérique Citizens (`npc_citizens_bindings`, migration V12, table `npc_ids` migration V11 pour l'identité générique — voir section 2 § `/rpgadmin npc`). **Ne jamais migrer l'un sans l'autre** : les deux doivent provenir du même instantané temporel. Détail complet et procédure : [VERYGAMES.md § Migration des données](deployment/VERYGAMES.md#migration-des-données-scénario-3).

### Tables SQLite par migration (`PRAGMA user_version`, vérifié dans `SchemaMigrator.java`, version courante = 12)

| Version | Tables créées | Domaine |
|---|---|---|
| V1 | `player_profiles`, `player_variables` | Socle joueur |
| V2 | `quest_progress`, `quest_objective_progress` | Quêtes |
| V3 | `resource_nodes` | Ressources |
| V4 | `wallets`, `transactions` | Économie |
| V5 | `market_listings` | Marché entre joueurs |
| V6 | `portal_cooldowns` | Portails |
| V7 | `claims`, `claim_members` | Claims |
| V8 | `player_skills`, `xp_grants`, `player_placed_blocks` | Progression RPG / anti-farm |
| V9 | `player_entitlements`, `backpacks`, `backpack_overflow`, `backpack_audit` | Backpacks / avantages |
| V10 | `store_deliveries_processed` | Boutique web (idempotence des livraisons) |
| V11 | `npc_ids` | Identité PNJ stable (`/rpgadmin npc`) |
| V12 | `npc_citizens_bindings` | Liaison PNJ Citizens ↔ identifiant RPGQuest |

Migrations idempotentes : `migrate()` sur une base déjà à jour ne fait rien ; toutes les `CREATE TABLE` utilisent `IF NOT EXISTS`.

### Classification des données

-   **Configuration pure (YAML, éditable/versionnable en dehors du dépôt Git)** : `config.yml`, `messages.yml`, `spawn.yml`, `quests/`, `dialogues/`, `items/`, `recipes/`, `resource-nodes/`, `merchants/`, `mobs/`, `zones/`, `portals/`, `destinations/`, `world-portals/`, `store-products/`.
-   **Données joueurs (jamais régénérables)** : `data.db` en intégralité — profils, progression de quêtes, variables, portefeuilles/transactions, offres de marché, cooldowns de portails, claims + membres, compétences/XP/anti-farm, entitlements, backpacks + surplus + audit, livraisons boutique traitées, identités PNJ.
-   **Mondes** : `world_hub/` (et tout autre monde géré) — dossier binaire à la racine du serveur, jamais dans `/plugins/`.
-   **PNJ Citizens** : `plugins/Citizens/saves.yml` — à traiter **avec** `data.db` (règle d'or ci-dessus).

### Ce qui peut être régénéré sans risque
-   Les fichiers YAML d'exemple (générés automatiquement au premier démarrage si absents) — RPGQuest **ne régénère jamais un fichier déjà présent**, donc un fichier de contenu réel personnalisé n'est jamais écrasé par une régénération accidentelle.
-   `web-export/snapshot.json` — recalculé périodiquement, aucune perte si supprimé.

### Ce qui ne doit **jamais** être copié depuis un environnement de test sans vérification
-   Tout `run/plugins/RPGQuest/` copié aveuglément en production — `run/` est un environnement de développement/test (jamais commité), pouvant contenir des fixtures volontairement invalides (ex. `broken_quest.yml`, voir section 15/18) ou du contenu de test non destiné aux joueurs.
-   Procédure de contrôle obligatoire avant toute copie de fichiers YAML vers VeryGames : lister chaque dossier, écarter tout fichier connu comme fixture de test ou nommé « test »/« debug »/« tmp », vérifier en cas de doute que le contenu est cohérent pour un vrai joueur — voir [VERYGAMES.md § Contrôle avant migration](deployment/VERYGAMES.md#️-contrôle-avant-migration--exclure-les-fixtures-de-test-manuel).

---

## 18. Dépannage

FAQ basée sur des problèmes réellement documentés dans le projet (code, `VERYGAMES.md`, `LOCAL_SERVER.md`, `MANUAL_TEST_PLAN.md`) — pas de scénario inventé.

### Plugin non chargé
-   **Symptôme :** `/plugins` liste RPGQuest en rouge, ou absent.
-   **Cause probable :** Java < 21 (RPGQuest compilé avec `options.release.set(21)`), ou exception au démarrage (config invalide, dépendance manquante).
-   **Vérification :** logs de démarrage — ligne de version JVM tout en haut ; rechercher une stack trace juste après `RPGQuest`.
-   **Correction :** installer Java 21/Temurin 21 côté hébergeur ; corriger `config.yml` si un message d'erreur précis est loggé (le plugin refuse de démarrer plutôt que de tourner avec une config invalide).

### Mauvaise version Java
-   **Symptôme :** échec de chargement immédiat, souvent `UnsupportedClassVersionError`.
-   **Cause :** version Java du serveur < 21.
-   **Vérification :** ligne de version JVM en tout début de log de démarrage.
-   **Correction :** changer la version Java de l'instance VeryGames (panel), redémarrer.

### WorldEdit incompatible
-   **Symptôme :** `//wand`/`/worldedit version` échoue, ou `/plugins` liste WorldEdit en rouge.
-   **Cause probable :** version WorldEdit incompatible avec Paper `1.21.11-132` (seule combinaison validée : `worldedit-bukkit-7.4.1.jar`).
-   **Vérification :** version exacte du jar installé.
-   **Correction :** réinstaller la version validée avant de poursuivre l'installation des plugins suivants (ordre strict, section 1).

### NPC présent mais dialogue absent
-   **Symptôme :** clic droit sur le PNJ n'ouvre aucun dialogue.
-   **Cause probable :** l'identification se fait par **identifiant logique stable** (`NpcIdentityService`), **jamais** par le nom affiché de l'entité (voir section 4/5) — un dialogue ne s'ouvre que si `rpgquest:<id logique>` existe et est chargé. Causes réelles les plus fréquentes : le PNJ Citizens n'est pas (ou plus) tagué côté RPGQuest (mapping `npc_citizens_bindings` absent/désynchronisé, typiquement après une migration `saves.yml`/`data.db` qui ne provient pas du même instantané, voir section 17) ; ou le dialogue n'a pas été rechargé après ajout (redémarrage complet requis, section 4).
-   **Vérification :** le PNJ Citizens existe bien à cet endroit ; en le visant, `/rpgadmin npc info` affiche l'identifiant logique attendu (ex. `guide`, `libraire`) — absent ou différent de l'attendu signale un tag manquant/perdu ; le fichier `dialogues/<id logique>.yml` (`id: rpgquest:<id logique>`) existe et est bien chargé (vérifier les logs au démarrage/`reload`) ; `saves.yml` et `data.db` proviennent bien du même instantané (règle d'or, section 17).
-   **Correction :** si le tag est manquant/perdu, `/rpgadmin npc tag <id logique>` sur le PNJ visé (un renommage cosmétique via `/npc rename` n'a **aucun** effet sur le dialogue) ; sinon corriger l'id ou recharger le fichier de dialogue correspondant.

### `world_hub` non importé
-   **Symptôme :** absence de la ligne de log `Règles du monde Hub appliquées : world_hub` après un redémarrage complet.
-   **Cause probable :** `world_hub/` non transféré à la racine du serveur (transféré dans `/plugins/` par erreur, ou nom de dossier incorrect), ou `/mv import world_hub normal` non exécuté, ou `hub.world` mal configuré dans `config.yml`.
-   **Vérification :** `/mv list` (le monde doit apparaître) ; contenu de `config.yml` → `hub.world`.
-   **Correction :** transférer/importer correctement (section 1/7), corriger `hub.world`, redémarrage complet (pas un simple `/rpgquest reload`, qui ne recharge que la config, ni le service Hub).

### Mauvais spawn (village)
-   **Symptôme :** `/rpgadmin spawn tp` téléporte à un endroit incorrect, ou un nouveau joueur apparaît au mauvais endroit.
-   **Cause probable :** confusion avec le spawn Multiverse ou le spawn Bukkit du monde (voir tableau section 7) — le spawn Multiverse **n'est jamais** la référence gameplay.
-   **Correction :** `/rpgadmin spawn set` à la position exacte souhaitée, puis vérifier avec `/rpgadmin spawn tp`.

### `broken_quest.yml` chargé en production
-   **Symptôme :** une quête manifestement invalide/de test apparaît en jeu, ou un avertissement de rejet de fichier inattendu en production.
-   **Cause :** copie aveugle de `run/plugins/RPGQuest/quests/` (environnement de développement/test) vers `/plugins/RPGQuest/quests/` en production, sans le contrôle de tri prévu.
-   **Vérification :** `/quest admin validate` → doit rapporter `0 erreur(s)` **et** aucune quête au nom suspect (« test », « debug », « broken »...).
-   **Correction :** retirer le fichier fautif, `/quest admin reload`. Prévention : toujours appliquer la procédure de contrôle avant migration (section 17).

### Serveur démarré mais monde absent
-   **Symptôme :** un monde attendu (`world_hub` ou un monde créé via `/rpgadmin world create`) n'apparaît pas dans `/rpgadmin world list`/`/mv list`.
-   **Cause probable :** dossier du monde non transféré à la racine du serveur (erreur de chemin FTP — les mondes vont à la racine, jamais dans `/plugins/`), ou jamais créé/importé.
-   **Correction :** vérifier l'arborescence FTP, réimporter/recréer.

### Commande exécutée avant « Done »
-   **Symptôme :** une commande RPGQuest tapée juste après le lancement échoue ou ne répond pas (commande inconnue, service non initialisé).
-   **Cause :** le serveur n'a pas fini son démarrage (les services RPGQuest démarrent dans un ordre précis : config, base de données, moteur de quêtes, objets, recettes, nœuds de ressource, dialogues, journal).
-   **Correction :** attendre la ligne `Done (...)! For help, type "help"` **suivie de** `RPGQuest <version> activé` avant toute commande.

### Différences OP / joueur normal dans le Hub
-   **Symptôme apparent de bug :** un joueur OP peut casser/poser des blocs dans `world_hub`, un joueur normal non.
-   **Explication (comportement attendu, pas un bug) :** `rpgquest.admin.world` accorde un bypass de casse/pose **à l'acteur direct uniquement** — ne s'applique jamais aux dégâts environnementaux/hostiles (toujours annulés pour tout le monde, y compris un admin) ni à la victime d'une action d'un tiers.
-   **Vérification :** comparer avec la checklist finale de VERYGAMES.md (« OP peut construire », « non-OP ne peut pas », « aucun dégât subi par quiconque »).

### Verrou de session orphelin (`run/world/session.lock`)
-   **Symptôme (développement local uniquement) :** `runServer` échoue au démarrage avec `DirectoryLock`/`IOException`.
-   **Cause :** un précédent processus `runServer` a été terminé brutalement (tué) plutôt qu'arrêté proprement (`stop`).
-   **Correction :** identifier et terminer le processus Java orphelin, relancer. Prévention : toujours utiliser `stop` en console, jamais tuer le processus.

---

## 19. Synchronisation avec docs-site

`docs-site/` reste la version conviviale/navigation visuelle ; cette bible Markdown est la référence exhaustive. Analyse des 7 pages HTML au moment de la rédaction :

| Page docs-site | Sujet | Sections de la bible couvertes |
|---|---|---|
| `index.html` | Sommaire, liste les 5 autres pages, renvoie vers les `.md` comme sources faisant foi | — (page d'index, pas une section technique) |
| `npc.html` | Identité PNJ (Citizens prioritaire/vanilla), `/rpgadmin npc`, procédure Citizens complète, association dialogue/quête | Section 5 en quasi-totalité, alimente aussi 3 et 4 |
| `dialogues.html` | Nœuds/choix, conditions, actions (dont `OPEN_MERCHANT`), les 2 renderers, `/dialogue open` | Section 4 en quasi-totalité |
| `quests.html` | Format YAML, 7 types d'objectifs, 4 types de récompenses, `/quest`/`/quests` | Section 3 en quasi-totalité |
| `admin-testing.html` | `/quest admin reload\|validate\|reset`, `/quest list\|progress`, `/quests`, checklist manuelle | Sous-section admin de la section 3 |
| `hub-safe-zone.html` | `/rpgadmin spawn`, `/rpgadmin zone`, tableau des flags, bypass admin | Sous-ensemble de la section 2 (spawn + zone) |
| `worlds.html` | `/rpgadmin world`, `/rpgadmin worldportal`, stratégies `WORLD_SPAWN`/`RANDOM_SAFE` | Sections 6 (volet worldportal) et 7 |

### ⚠️ Page obsolète détectée — `worlds.html`

`worlds.html` affirme explicitement l'absence de Multiverse-Core et de dépendance externe pour la gestion de mondes. Or `Multiverse-Core 5.7.3` est installé et utilisé en production ([VERYGAMES.md](deployment/VERYGAMES.md), `/mv import`/`/mv list`) et `HubWorldRulesService`/`HubWorldProtectionListener` (monde Hub, jour/météo permanents, protections) n'y sont mentionnés nulle part. Cette page semble antérieure à l'ajout de Multiverse-Core et du monde Hub réel — **cette bible fait foi sur ce point précis** (code + VERYGAMES.md), pas `worlds.html`.

### Sections de la bible sans page docs-site équivalente

1.  Installation / Serveur
2.  Administration RPGQuest — partiellement couverte (spawn/zone via `hub-safe-zone.html`, npc via `npc.html`) ; **aucune page** pour `/rpgadmin flatten` ni `/rpgadmin mob`
6.  Portails — le système riche `/rpgadmin portal` (canalisation, coût, destinations) n'a **aucune page** ; seul le portail simple (`worldportal`) est dans `worlds.html`
8.  Claims
9.  Items / Équipements
10. Ressources
11. Mobs spéciaux
12. Marchands / Économie / Marché
13. Backpacks
14. WorldEdit
15. Tests et diagnostic — partiellement dans `admin-testing.html`, pas de vue globale
16. Fichiers importants
17. Persistance / Migration
18. Dépannage

Si une future modification concerne une page **existante** du docs-site, cette page doit être mise à jour dans la même branche/PR (voir section 20). Créer les pages manquantes ci-dessus reste un travail futur, hors périmètre de cette tâche (documentation uniquement, aucune page HTML modifiée ici).

---

## 20. Maintenance

Toute nouvelle commande, modification de syntaxe, nouveau fichier de configuration, nouvelle procédure serveur ou nouveau système administrable doit mettre à jour **`docs/RPGQUEST_BIBLE.md`** dans la même branche/PR que le changement de code. Si le changement concerne une page existante du docs-site (voir la table de correspondance en section 19), cette page doit être mise à jour dans la même branche/PR. La bible et le docs-site ne doivent pas diverger volontairement. Règle miroir posée dans [PROJECT_RULES.md](../PROJECT_RULES.md).

---

## 21. Storylines

Détail complet : [docs/storylines.md](storylines.md).

Un conteneur logique **ordonné** de quêtes existantes (`story.model.StoryDefinition`), avec sa propre progression par joueur (`NOT_STARTED`/`ACTIVE`/`COMPLETED`, table `story_progress`) — **délibérément indépendant** du moteur de quête : aucune référence croisée entre `story.StoryService` et `quest.progress.QuestProgressEngine`, une quête terminée ne fait pas encore avancer une Story automatiquement (prévu comme extension future, pas câblé à cette étape).

Définitions chargées depuis `plugins/RPGQuest/stories/` (un exemple `main_story.yml` généré au premier démarrage). Aucune commande `/rpgadmin story create`/`delete` — seulement :

| Commande | Effet | Persistance |
|---|---|---|
| `/rpgadmin story info <joueur>` | Liste toutes les Stories connues et leur état pour ce joueur. | non |
| `/rpgadmin story start <joueur> <storyId>` | Démarre une Story (`ACTIVE`). Refusé si id inconnu, déjà active, ou déjà terminée. | oui |
| `/rpgadmin story reset <joueur> <storyId\|all>` | Supprime la progression d'une Story (ou de toutes), reset ciblé — jamais l'inventaire, l'économie, ni les autres Stories/quêtes. | oui (suppression) |

À savoir : **seule branche de `/rpgadmin` utilisable depuis la console** (cible un joueur passé en argument, jamais la position de l'exécutant) ; le joueur ciblé peut être hors ligne (résolution asynchrone, profil créé au besoin). Ces commandes sont strictement admin/debug — l'UX finale prévue pour les Storylines ne repose sur aucune commande joueur.
