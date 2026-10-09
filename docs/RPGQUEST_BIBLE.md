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

Vérifié dans `src/main/java/com/lodygames/rpgquest/admin/RpgAdminCommand.java`. Racine unique pour les sous-systèmes d'administration : `flatten`, `zone`, `portal`, `mob`, `npc`, `spawn`, `world`, `worldportal`, `quest`, `story`, `waystone`, `player`, `guide`.

Type : Admin (toutes les sous-commandes) — Permission : **`rpgquest.admin.world`** pour tout `/rpgadmin`. **Exception** : `/rpgadmin player variable set` exige **en plus** `rpgquest.admin.debug` (défaut `op`, écriture bas niveau). La plupart des sous-commandes exigent un **joueur en jeu** (position/sélection) ; `quest`, `story`, `player` et `guide` ciblent au contraire un joueur passé en argument et sont utilisables **depuis la console**.

### Aplatissement de terrain — `/rpgadmin flatten`
Détail complet : [docs/ADMIN_FLATTEN.md](ADMIN_FLATTEN.md). Page docs-site : aucune.

| Commande | Effet |
|---|---|
| `/rpgadmin flatten <rayon> [hauteur]` | Calcule un **aperçu** (aucun bloc modifié), centré sur le joueur. `hauteur` optionnelle (Y cible, défaut = bloc sous les pieds). |
| `/rpgadmin flatten confirm` | Exécute l'aperçu en attente (expire après 30 s par défaut). |
| `/rpgadmin flatten cancel` | Annule l'aperçu, ou arrête un chantier en cours (le travail déjà fait reste). |
| `/rpgadmin flatten undo` | Annule le **dernier** aplatissement (un seul niveau, écrasé par le suivant). |

Persistance : non (opère directement sur les blocs du monde ; l'état d'annulation est en mémoire, perdu au redémarrage). À savoir : rayon max configurable (`admin.flatten.max-radius`, 48 par défaut), traitement par lots (4000 blocs/tick par défaut) pour ne jamais geler le serveur.

### Reset « nouveau joueur » — `/rpgadmin player resetnew`
Détail complet : [docs/ADMIN_PLAYER_RESET.md](ADMIN_PLAYER_RESET.md).

| Commande | Effet |
|---|---|
| `/rpgadmin player resetnew <joueur>` | Affiche l'avertissement listant ce qui serait effacé (ne fait rien). |
| `/rpgadmin player resetnew <joueur> preview` | **Dry-run** : liste, catégorie par catégorie, ce qu'un reset réel effacerait (nombre + détail ; « rien à réinitialiser » si vide ; « non applicable » pour l'inventaire d'un joueur hors ligne). **Aucune écriture** : pas de suppression, pas de marqueur, pas d'invalidation de cache, aucun objet retiré. En ligne **ou** hors ligne. |
| `/rpgadmin player resetnew <joueur> confirm` | Remet l'état **RPGQuest** d'un seul joueur (en ligne **ou** hors ligne) dans l'équivalent d'un joueur jamais connecté : quêtes (actives/progression/terminées/suivie), Stories, **toutes** les variables/unlocks (dont `CLAIM_TIER_1`), progression RPG (`player_skills`/`xp_grants`), découvertes de Waystones, cooldowns persistants (portails + Rune), claim principal (données de protection uniquement, cascade `claim_members`), et objets personnalisés RPGQuest de l'inventaire (immédiat si en ligne, différé au prochain login sinon). |

Ne touche **jamais** : `data.db` entier, un autre joueur, le profil/UUID/playerdata vanilla, les mondes, les PNJ Citizens, les définitions de quêtes/Stories, les portails, les Waystones globales, les blocs construits. Conservés volontairement : économie, backpacks/entitlements, annonces de marché. Console : autorisée (comme `/rpgadmin story`). Protection : mot `confirm` obligatoire.

### Raccourcis de test quêtes & stories — `/rpgadmin quest`, `/rpgadmin story advance|complete`, `/rpgadmin player variable` (issue #36)
Détail complet : [docs/ADMIN_TEST_SHORTCUTS.md](ADMIN_TEST_SHORTCUTS.md). Outils DEV/admin pour atteindre vite un état de progression sans rejouer le gameplay ; réutilisent `QuestProgressEngine`/`StoryService`, jamais d'écriture directe en base. Console OK. Opérations journalisées (`[admin] …`).

| Commande | Effet | Cible | Récompenses |
|---|---|---|---|
| `/rpgadmin quest start <joueur> <quest-id> [force]` | Démarre la quête (`QuestProgressEngine.accept`). Prérequis respectés sauf `force`. Id inconnu refusé ; pas de doublon d'une quête active. | en ligne | aucune (l'acceptation n'en donne jamais) |
| `/rpgadmin quest complete <joueur> <quest-id>` | Complète sans simuler les objectifs (`forceComplete`). | en ligne | **appliquées une seule fois** : `VARIABLE` (ex. `CLAIM_TIER_1=true`), `EXPERIENCE`, `ITEM`, `COMMAND`, `MONEY` (le crédit monétaire est en plus idempotent **en base**, par occasion de complétion — voir « Récompense monétaire `MONEY` »). Quête déjà `COMPLETED` → « déjà terminée », rien re-crédité. |
| `/rpgadmin quest reset <joueur> <quest-id>` | Supprime progression + compteurs → quête rejouable (`resetQuest`). | en ligne **ou** hors ligne | **n'annule pas** les récompenses déjà données (XP, objets, variables, effets de commande) — limite documentée. |
| `/rpgadmin story advance <joueur> <storyId>` | Démarre la story si besoin, `forceComplete` de sa quête courante, avance d'un cran (accepte la suivante ou termine la story). Le message dit quelle étape tester. | en ligne | via `forceComplete`, une seule fois par quête |
| `/rpgadmin story complete <joueur> <storyId>` | Enchaîne `advance` jusqu'au bout, dans l'ordre, borné. | en ligne | via `forceComplete`, une seule fois par quête |
| `/rpgadmin player variable get <joueur> <clé>` | Lit `player_variables` (lecture pure). Clé absente signalée. | en ligne **ou** hors ligne | — |
| `/rpgadmin player variable set <joueur> <clé> <valeur>` | Écrit la clé. Avertissement + journalisation (ancienne/nouvelle valeur). | en ligne **ou** hors ligne | **exige `rpgquest.admin.debug` en plus** |
| `/rpgadmin claim grant-tier <joueur> <TIER_n>` (issue #179) | Fait directement grandir le claim principal déjà posé (`ClaimService#upgradeTier`), sans passer par une quête. | en ligne | n/a (pas une récompense de quête — voir `/rpgadmin quest complete guard_tierN` pour tester la quête elle-même, récompenses incluses) |

`story advance`/`complete` ne touchent jamais une quête non référencée par la story ciblée. Pour un état vraiment propre : `/rpgadmin player resetnew … confirm`, `/rpgadmin story resetwithquests …`, ou `/rpgadmin player variable set … CLAIM_TIER_1 false`.

### Guides de Hub — `/rpgadmin guide`
Détail complet : [docs/HUB_GUIDE.md](HUB_GUIDE.md). Lecture seule, console autorisée, permission `rpgquest.admin.world`.

| Commande | Effet |
|---|---|
| `/rpgadmin guide list` | Liste les Guides de Hub chargés (`plugins/RPGQuest/hub-guides/*.yml`) : `hub-id`, mondes, dialogue d'aide + nœud. |
| `/rpgadmin guide info <hub>` | Détail d'un Hub : mondes, dialogue/nœud d'aide, message d'accueil, spécialité locale, orientations vers les PNJ (`role → npc : note`). |

Aucune écriture. Le contenu du menu d'aide vit dans le dialogue référencé (`guide.yml`, nœud `help_menu`) — voir « /quests » ci-dessus et [NPC_DIALOGUES_QUESTS_GUIDE.md](NPC_DIALOGUES_QUESTS_GUIDE.md) §6b.

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

### Bornes et villages du réseau de voyage — `/rpgadmin travel` (issues #132/#150/#151/#149/#133/#135)
Voir aussi `docs/TRAVEL.md` section « Réseau de voyage / bornes » pour le détail complet.

| Commande | Effet | Persistance |
|---|---|---|
| `/rpgadmin travel beacon set` | Pose une borne de voyage (support waypoint + `DIAMOND_BLOCK` + bouton en bois configurable, `OAK_BUTTON` par défaut) à la position **réelle** du joueur, orientée selon son regard. Idempotent (rejouer à la même colonne est refusé). Seule voie de placement dans le **Wild**. | oui — SQLite (`travel_beacons`, migration V19, colonne `biome_instance` V21) |
| `/rpgadmin travel village sethub <id> <nom...>` | Crée/déplace un centre de village **sur le spawn du Hub déjà configuré** (`SpawnService#resolve`, jamais une coordonnée inventée). Refusé si aucun spawn n'est défini. | oui — SQLite (`village_centers`, migration V20) |
| `/rpgadmin travel village set <id> <nom...>` | Crée/déplace un centre de village à la position **réelle** du joueur (orientation incluse) — pour un centre secondaire ailleurs dans `world_hub`. | oui |
| `/rpgadmin travel village remove <id>` | Supprime définitivement un centre. | oui |
| `/rpgadmin travel village enable\|disable <id>` | (Dés)active un centre sans changer son `id` ni ses coordonnées — une désactivation ne casse jamais une référence déjà exposée dans le menu. | oui |
| `/rpgadmin travel village list` | Liste les centres configurés (id, nom, monde, actif). | non |
| `/rpgadmin travel diagnose [monde]` | Diagnostic lecture seule (#153/#156) : comptes waypoints/bornes, instances Hub sans borne appariée, structures inaccessibles. | non |
| `/rpgadmin travel repair waypoint\|beacon <id> confirm` | **Déplace** une structure inaccessible (#153) vers un emplacement accessible proche ; id/nom/découvertes conservés. | oui |
| `/rpgadmin travel restore waypoint <id> confirm [force]` | (#191) **Repose les blocs manquants sans déplacer** le waypoint — à utiliser après une destruction. Conserve id, nom, découvertes et borne appariée ; ne crée aucun doublon. Un emplacement occupé par une construction tierce est signalé en conflit et **laissé intact** sauf `force` explicite. Ne pas confondre avec `repair`, qui relocalise. | oui (blocs du monde) |
| `/rpgadmin travel maintenance <on\|off>` | (#191) Autorise temporairement, **pour soi uniquement**, la casse des blocs de waypoints/bornes. Exige la permission dédiée `rpgquest.admin.travel.maintenance` (`default: false`, **jamais accordée par le simple statut OP**) ; expire d'elle-même au bout de 5 minutes. Sans argument : affiche l'état courant. | non (mémoire, perdu au redémarrage — défaut sûr) |

**Protection des structures (issue #191)** — casser le bouton, le bloc d'or, le support ou un
panneau latéral d'un waypoint, ainsi que les blocs d'une borne diamant, est refusé pour **tout le
monde** : explosions, pistons et perte de support inclus. Il n'existe plus de bypass implicite par
statut OP — c'était la cause du défaut signalé en jeu, où un compte opérateur détruisait la
structure entière sans geste délibéré. Pour une maintenance réelle, il faut **deux** choses :
la permission dédiée `rpgquest.admin.travel.maintenance` (`default: false`) **et**
`/rpgadmin travel maintenance on`, qui expire tout seul. `/rpgadmin travel diagnose` signale
désormais aussi les structures **abîmées** (blocs manquants), distinctes des structures
*inaccessibles* — une structure cassée restait « accessible », elle n'était donc pas détectée.

À savoir : dans le **Hub**, un waypoint (modèle or) **et** une borne distincte s'y génèrent
désormais automatiquement par instance de biome (issue #149, réutilise #124) — ces commandes
restent le **seul** moyen de poser une borne/un centre dans le **Wild** et pour les centres de
village. Le bouton d'une borne (administrée ou auto-générée) ouvre le menu de voyage pour **tout**
joueur, sans commande ; l'identité d'un centre de village (`id`) est **indépendante du monde** :
plusieurs centres peuvent coexister dans `world_hub`, distingués par id/nom.

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

### Chaîne de paliers du Garde — `guard_tier1`..`guard_tier5` (issue #179)

Parcours combat simplifié pour obtenir les claims de palier 1 à 5 (voir
[docs/CLAIMS.md](../CLAIMS.md), section « Montée de palier ») — **chemin
parallèle à `crystal_hunt`, jamais modifié**, même PNJ donneur (`guard`).

Cinq quêtes (`rpgquest:guard_tier1` → `rpgquest:guard_tier5`,
`src/main/resources/quests/guard_tier{1..5}.yml`), chacune :

-   **une seule étape**, **quatre objectifs `KILL_ENTITY` simultanés**
    (araignées, zombies, squelettes, creepers), tous requis avant de
    terminer l'étape, dans n'importe quel ordre — supporté nativement par
    le moteur (`QuestObjectiveIndex` indexe chaque objectif indépendamment
    par type+clé), **aucun changement du moteur n'a été nécessaire** ;
-   montants par palier : 5/10/20/40/80 de chaque menace ;
-   **aucun report d'une quête à l'autre** : chaque quête a son propre
    compteur de progression (clé = id de quête + étape), remis à zéro à
    l'acceptation d'une nouvelle quête, jamais partagé avec la précédente ;
-   `prerequisites: [rpgquest:guard_tier(N-1)]` pour N ≥ 2 — palier N+1
    invisible dans le dialogue tant que le palier N n'est pas `COMPLETED` ;
-   `repeatable: false` ;
-   récompenses : `EXPERIENCE` (20×N), `VARIABLE CLAIM_TIER_N=true`
    (entitlement, même mécanisme que `crystal_hunt` pour `CLAIM_TIER_1`),
    `COMMAND: "rpgadmin claim grant-tier %player% TIER_N"` (montée réelle du
    claim déjà posé, idempotente dans tous les cas).

**Dialogue** (`dialogues/guard.yml`) : un choix par palier dans `greeting`,
conditionné par `QUEST_STATE` (palier précédent `COMPLETED` + palier
courant `NOT_STARTED`, palier 1 sans condition de prérequis dialogue — le
`prerequisites` de la quête elle-même suffit), menant à un nœud
`guard_tierN_accepted` qui énonce les quatre objectifs et leurs montants.
Après `guard_tier5` `COMPLETED`, un choix dédié (« J'ai fini tout ce que tu
m'as demandé... ») mène à `guard_tiers_done` — un message cohérent de
clôture, **jamais** un rappel d'objectif resté bloqué sur un état périmé
(voir l'issue #158 pour le symptôme évité). Les branches `first_steps`/
`crystal_hunt` existantes ne sont pas modifiées.

**Test en jeu sans grinder** : `/rpgadmin quest complete <joueur> guard_tierN`
complète instantanément une quête de palier avec ses récompenses (donc la
montée de claim réelle) ; `/rpgadmin claim grant-tier <joueur> TIER_n`
fait grandir le claim sans toucher à l'état des quêtes, si seule la taille
doit être vérifiée. Voir section 2 et [docs/ADMIN_TEST_SHORTCUTS.md](ADMIN_TEST_SHORTCUTS.md).

### `/quests` — journal de quêtes
Type : Joueur — Permission : `rpgquest.quest`
But : ouvrir le journal paginé. **Deux onglets** : « Quêtes en cours » (`ACTIVE`/`READY_TO_TURN_IN`) et « Quêtes terminées » (`COMPLETED`). Il n'y a **pas** d'onglet catalogue des quêtes disponibles (issue #11) : le journal ne liste que les quêtes déjà acceptées par le joueur, jamais les quêtes non découvertes.
Ouverture : `/quests`, **ou** un clic droit sur l'item `rpgquest:journal_quetes` remis par le Libraire (même GUI, `QuestJournalService`). L'item est reconnu par son identité RPGQuest/PDC, jamais par son nom/lore ; il ne peut pas être dupliqué (garde `LACKS_CUSTOM_ITEM` sur le dialogue du Libraire) ni perdu (`SoulboundItemService`).
Effet : ouvre un inventaire GUI (voir `docs/ARCHITECTURE.md` pour le détail : clic gauche = détail, clic droit = suivi/bossbar). Aucune interaction ne permet de retirer ou dupliquer un item de la GUI (tout clic/drag sur un `JournalInventoryHolder` est annulé). Aucun paramètre.
Persistance : le suivi (quête « trackée ») persiste (`player_variables`), pas la simple ouverture du menu.
Bouton « Fermer » (slot `CLOSE_SLOT`/`DETAIL_CLOSE_SLOT`) : la fermeture est différée d'un tick serveur (`QuestJournalService#closeNextTick`) plutôt qu'appelée directement dans le gestionnaire de `InventoryClickEvent` — fermer une fenêtre pendant le traitement de son propre clic annulé pouvait laisser le client avec une fenêtre visuellement toujours ouverte (paquet de resynchronisation du clic annulé arrivant après le paquet de fermeture).

**Bossbar de suivi (`ui.TrackedQuestDisplay`)** — retour joueur 2026-10-04 (issue #157) : le gabarit
du titre comportait une balise `</gray>` de fermeture en trop (sans ouverture correspondante),
rendue littéralement par MiniMessage au lieu d'être interprétée, et affichait l'id technique brut
de l'étape (ex. `kill_spiders`) plutôt qu'un libellé humain. Corrigé : gabarit réparé, libellé
construit à partir des descriptions déjà humanisées de chaque objectif de l'étape
(`ObjectiveProgressView#description()`, même source que `/quest progress`), avec repli sur l'id
technique seulement si aucune description n'est disponible. Les ids internes ne sont jamais
modifiés — correction d'affichage uniquement.

### Commandes admin — `/quest admin`

Permission : `rpgquest.admin` (toutes), sauf `/quest complete` qui est aussi `rpgquest.admin`.

| Commande | Effet | Persistance |
|---|---|---|
| `/quest complete <id>` | Force la fin d'une quête (récompenses appliquées même sans objectifs remplis) — outil de test admin uniquement, joueur exécutant lui-même. | oui |
| `/quest admin reload` | Recharge les définitions de quêtes **et** `messages.yml` depuis le disque, reconstruit l'index/les écouteurs de progression. Rapport `N chargée(s), N erreur(s)` ; un fichier invalide n'empêche pas le chargement des autres. `messages.yml` existant n'est jamais écrasé (personnalisations admin préservées), mais toute clé présente dans la ressource embarquée et absente du fichier disque (ex. nouvelle clé apportée par une mise à jour) est **fusionnée** dans le fichier disque à chaque reload (`QuestMessagesService#mergeMissingKeys`) — la cause la plus courante d'une clé manquante. Si une clé est malgré tout manquante (fichier corrompu, clé mal orthographiée), `QuestMessages#format` ne l'affiche plus jamais en jeu : un texte de remplacement discret est montré au joueur et un avertissement avec le nom exact de la clé part dans les logs serveur. | non (relit les fichiers), sauf ajout de clés manquantes à `messages.yml` |
| `/quest admin validate` | Même chargement, sans rien appliquer (dry-run). | non |
| `/quest admin reset <joueur> <id\|all>` | Supprime l'état persisté et les compteurs d'objectifs d'une quête (ou de toutes) pour un joueur **en ligne** — jamais l'inventaire ni l'économie. Outil de test. | oui (suppression) |

### Types d'objectifs (`steps[].objectives[].type`)

Les 10 types existent **tous** réellement dans le code (`ObjectiveType` enum, exactement ces 10 valeurs, aucune de plus) :

| Type | Champs YAML | Classe | Événement Bukkit déclencheur | Comportement multi-monde |
|---|---|---|---|---|
| `BREAK_BLOCK` | `material` (Bukkit `Material`), `amount` (> 0) | `BreakBlockObjective` | `BlockBreakEvent` (`QuestBlockBreakListener`, `ignoreCancelled = true`) | **Global** — aucun champ `world` ; compte dans n'importe quel monde. |
| `PLACE_BLOCK` | `material`, `amount` (> 0) | `PlaceBlockObjective` | `BlockPlaceEvent` (`QuestBlockPlaceListener`, `ignoreCancelled = true`) | Global, idem. |
| `KILL_ENTITY` | `entity` (Bukkit `EntityType`), `amount` (> 0) | `KillEntityObjective` | `EntityDeathEvent` (`QuestEntityDeathListener`) — **seulement** si `event.getEntity().getKiller()` est le joueur (kill direct) | Global, idem (ex. `first_steps` : `entity: SPIDER`, `amount: 10`). **Limite connue** : une mort provoquée indirectement (piège, chute, autre mob, dégât de zone) ne compte jamais, même si le joueur en est la cause ultime — cohérent avec `SpiderFangDropListener` (TC-043). |
| `COLLECT_ITEM` | `material`, `amount` (> 0) | `CollectItemObjective` | `EntityPickupItemEvent` (`QuestItemPickupListener`, `ignoreCancelled = true`) | Global, idem. **Limite connue** : compte uniquement un ramassage physique au sol par le joueur ; recevoir l'objet autrement (coffre, `/give`, craft, troc marchand) ne progresse jamais cet objectif. |
| `CRAFT_ITEM` | `material`, `amount` (> 0) | `CraftItemObjective` | `CraftItemEvent` (`QuestCraftItemListener`, `ignoreCancelled = true`) — matériau du résultat de la recette (`event.getRecipe().getResult().getType()`) | Global, idem. **Limite connue** (documentée dans `MANUAL_TEST_PLAN.md` TC-012) : ne distingue pas un objet personnalisé d'un objet vanilla du même `Material`. |
| `TALK_TO_NPC` | `npc` (id logique RPGQuest attribué par `/rpgadmin npc tag`, **pas** le nom affiché — voir section 5) | `TalkToNpcObjective` | `PlayerInteractEntityEvent` (`QuestNpcInteractListener`, entité vanilla/Citizens non géré) **ou** `NPCRightClickEvent` (`QuestCitizensNpcInteractListener`, uniquement si Citizens est actif et gère l'entité) — jamais les deux sur la même entité | Implicitement lié au monde où se trouve le PNJ visé, mais le champ lui-même ne porte pas de monde. À ne pas confondre avec l'identification par nom affiché utilisée par le système de **dialogue** (section 4/5) : `TALK_TO_NPC` (quête) exige un id logique posé au préalable via `/rpgadmin npc tag`, une entité renommée sans être taguée ne progresse jamais cet objectif. |
| `SMELT_ITEM` | `material` (objet **obtenu** après cuisson), `amount` (> 0) | `SmeltItemObjective` | `FurnaceExtractEvent` (`QuestSmeltListener`, `ignoreCancelled = true`) — **seul** événement de cuisson qui porte un joueur, et il donne la quantité réellement retirée | Global. Progresse de la quantité extraite, plafonnée au reste à faire. Les trois fours vanilla comptent (`FURNACE`, `BLAST_FURNACE`, `SMOKER`), vérifiés explicitement. **Limite connue** : obtenir l'objet autrement (coffre, `/give`, craft, ramassage) ou le laisser sortir par un entonnoir ne progresse jamais — c'est voulu ; et un joueur qui vide le four d'un autre progresse, seule attribution que l'API publique garantisse. |
| `DELIVER_ITEM_TO_NPC` | `npc` (id logique RPGQuest, comme `TALK_TO_NPC`), `material`, `amount` (> 0) | `DeliverItemToNpcObjective` | **Aucun** — volontairement. Seule l'action de dialogue `DELIVER_QUEST_ITEMS` sur le bon PNJ fait progresser cet objectif (`QuestProgressEngine#deliverTo`) | Global (le PNJ est où il est). Le compteur porte la quantité **déjà remise** : dépôts partiels acquis définitivement, objets remis **consommés**, jamais restitués. Voir « Remise d'objets à un PNJ » plus bas. |
| `DISCOVER_WAYPOINT` | `amount` (> 0), `worlds` (liste optionnelle ; vide = tous les mondes), `count-mode` (`NEW_ONLY` par défaut, ou `INCLUDE_EXISTING`) | `DiscoverWaypointObjective` | **Aucun** — volontairement. La progression vient de l'abonnement aux **premières** découvertes du système de waypoints (`WaypointService#onFirstDiscovery` → `QuestProgressEngine#handleWaypointDiscovered`), branché une seule fois au bootstrap. Un écouteur de déplacement ou d'interaction recréerait une seconde règle de découverte à côté de celle du système de waypoints. | Filtré par `worlds` quand la liste est renseignée, global sinon. L'identité comptée est l'**id stable** du waypoint : deux waypoints du même biome comptent séparément, renommer ou déplacer un waypoint ne le fait pas compter deux fois. Aucune table supplémentaire — une première découverte est unique par couple (joueur, waypoint) au niveau de la base, donc un reclic ne notifie jamais. **Limite connue, assumée** : en `NEW_ONLY`, un joueur ayant déjà découvert tous les waypoints accessibles ne peut pas valider un nouveau cycle d'une quête répétable ; aucune découverte n'est jamais supprimée ni aucun waypoint débloqué pour y remédier. Utiliser `INCLUDE_EXISTING` si ce cumul est voulu. |
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

- type: DELIVER_ITEM_TO_NPC
  npc: guard                 # même convention d'id que TALK_TO_NPC
  material: LEATHER
  amount: 4

- type: SMELT_ITEM
  material: GREEN_DYE        # l'objet qui SORT du four, pas le cactus qui y entre
  amount: 2

- type: DISCOVER_WAYPOINT
  amount: 5
  worlds: [world_hub]        # optionnel ; absent ou vide = tous les mondes
  count-mode: NEW_ONLY       # optionnel ; NEW_ONLY (défaut) ou INCLUDE_EXISTING
```

### Découverte de waypoints — `DISCOVER_WAYPOINT` (issue #185)

La règle de comptage est **toujours explicite** et **toujours annoncée au joueur** : le libellé du
journal énonce la portée (« tous mondes », ou la liste des mondes retenus) puis la règle
(« nouvelles découvertes » ou « découvertes déjà acquises incluses »). Le ticket interdit d'imposer
l'une des deux silencieusement, parce qu'elle change ce que le joueur doit réellement faire.

- `NEW_ONLY` (défaut) : seules les découvertes faites **pendant que l'objectif est actif** comptent.
  Le compteur s'incrémente à chaque première découverte, et rien d'autre ne le fait bouger.
- `INCLUDE_EXISTING` : la progression vaut le **total** des découvertes du joueur correspondant aux
  filtres, recalculé à l'acceptation, au changement d'étape, au chargement du joueur et à chaque
  nouvelle découverte. Aucun instantané n'est stocké, donc la valeur reste exacte après un
  redémarrage, et elle ne recule jamais si un waypoint est désactivé après coup.

Le Control Panel expose les trois champs (`amount`, `worlds`, `count-mode`) dans l'éditeur de
quêtes ; `worlds` se saisit en texte séparé par des virgules et est écrit en liste YAML, chaque
entrée étant vérifiée contre les mondes réellement relevés et les doublons signalés.

### Remise d'objets à un PNJ — `DELIVER_ITEM_TO_NPC` (issue #123)

Le joueur doit **réellement remettre** les objets au PNJ configuré, dans son dialogue. Implémenté
par `QuestProgressEngine#deliverTo` + `QuestItemWithdrawal`. Page docs-site : `quests.html`
(objectifs) et `dialogues.html` (action/condition).

-   **Posséder ne suffit jamais.** Cet objectif n'écoute **aucun** événement de jeu : ramasser,
    fabriquer, acheter ou porter l'objet ne le fait pas avancer d'un seul point. C'est exactement ce
    qui le distingue de `COLLECT_ITEM`.
-   **Dépôts partiels persistants.** Le compteur de l'objectif *est* la quantité déjà remise, stockée
    comme n'importe quel compteur (`quest_objective_progress`) : **aucune migration de schéma**, et la
    progression survit à la mort, à une reconnexion et à un redémarrage. 2 cuirs remis sur 4 restent
    acquis ; le joueur revient déposer le reliquat plus tard.
-   **Objets consommés, jamais restitués** — ils sont « en sécurité auprès du PNJ ». Aucun mécanisme
    de reprise n'existe (hors périmètre de #123).
-   **Une seule interaction remet tout l'utile** : tous les objectifs de remise de ce PNJ, y compris
    ceux de plusieurs quêtes, sont traités en une fois. Pour chacun, le reliquat est calculé puis
    `QuestItemWithdrawal` retire **au plus** ce reliquat et renvoie ce qu'il a *réellement* retiré ;
    le compteur n'avance que de cette quantité. Impossible de progresser sans retrait, impossible de
    consommer au-delà du besoin, surplus laissé au joueur.
-   **Plusieurs piles** additionnées emplacement par emplacement, **stockage normal uniquement**
    (ni armure, ni main secondaire).
-   **Aucun objet personnalisé consommé** : une pile portant une identité RPGQuest dans son PDC est
    ignorée même si son `Material` correspond (sans cette règle, une remise de `BOOK` détruirait le
    journal de quêtes). C'est aussi le point d'extension prévu pour accepter des objets
    personnalisés en V2.
-   **Mauvais PNJ = rien.** Un PNJ qui n'est destinataire d'aucun objectif actif du joueur ne peut
    rien accepter ; seule l'étape **courante** est remisable.
-   **Anti double-remise** : un jeton par joueur refuse toute remise réentrante (double-clic, spam,
    cascade d'événements), et la mise à jour mémoire précède toute écriture asynchrone. La complétion
    d'étape n'est évaluée qu'une fois **toute** la remise appliquée.
-   **Journal** : la ligne affiche `<objet> (à remettre) remis/demandé` — le verbe distingue la
    remise d'une collecte. Le PNJ n'y est pas nommé (son id est une donnée interne, même raison que
    `TALK_TO_NPC`) ; le dialogue, lui, donne le détail complet.

Champ `giver` (optionnel, racine de la quête) : id logique stable du **PNJ donneur** (même convention que `objectives[].npc`, posé via `/rpgadmin npc tag`), jamais le nom affiché. Purement informatif — n'affecte ni la progression ni l'acceptation ; exposé tel quel dans le catalogue du Control Panel (`quest.list` → `giverId`, issue #75). Absent = aucun donneur ; présent mais vide = erreur de chargement. Vérifié dans `QuestDefinitionParser#parseGiver` / `QuestDefinition`.

Récompenses (`rewards[].type`, hors périmètre strict de la question mais nécessaires à tout exemple complet) : `EXPERIENCE` (`amount`), `ITEM`, `VARIABLE` (`key`/`value`), `COMMAND` (`command`, liste blanche via `dialogue.allowed-commands` **non requise** ici — seules les actions `RUN_SAFE_COMMAND` de dialogue sont filtrées, une récompense `COMMAND` de quête ne l'est pas, vérifié dans `QUEST_FORMAT.md`/`QuestDefinitionParser`).

**Récompense monétaire `MONEY` (`amount`, entier > 0 — issue #16).** Crédite le **portefeuille
persistant** du joueur (`wallets`, la même source que `/money`, les marchands, le marché et le
Control Panel). Elle ne donne **aucun objet** : la monnaie RPGQuest est un solde, et aucun objet
d'inventaire n'est jamais compté comme de l'argent.

- **Aucun plafond côté moteur.** Le montant est une décision d'équilibrage, pas une règle
  technique : seul l'invariant vérifiable est appliqué (strictement positif). Le panel
  **avertit** au-delà de 1 000 000 (`QuestValidator.MONEY_REWARD_WARNING_THRESHOLD`) pour attraper
  la faute de frappe, sans jamais refuser.
- **Enregistrée comme une dette avant d'être payée (schéma V26).** À la remise,
  `QuestProgressRepository#completeQuestWithMoneyDebts` écrit dans **une seule transaction** l'état
  `COMPLETED` de la quête **et** une ligne `PENDING` par récompense monétaire. Cette atomicité
  ferme une fenêtre précise : avec deux transactions séparées, un arrêt brutal entre les deux
  laisse soit une quête terminée **sans aucune trace** de la récompense due (perdue en silence,
  et jamais rejouable si la quête n'est pas répétable), soit des dettes pour une quête **pas
  terminée** (le joueur la refait et est payé deux fois).
- **Créditée au plus une fois.** `WalletRepository#payQuestRewardDebt` fait passer la ligne
  `PENDING → PAID` **et** met à jour le portefeuille **et** écrit la ligne `transactions`, dans la
  même transaction. Le passage est conditionnel (`WHERE status = 'PENDING'`), donc deux reprises
  concurrentes ne peuvent pas l'emporter toutes les deux. Une erreur SQL annule tout : la dette
  reste `PENDING`, donc encore payable.
- **Une récompense par ligne.** L'identité de paiement est `<jeton de complétion>#<index>` : une
  quête portant plusieurs récompenses `MONEY` les paie **toutes**. Avec une identité partagée
  (premier lot), la clé primaire rejetait toutes sauf la première — défaut mesuré : `100 + 30`
  créditait **100**.
- **Le montant et le contexte du journal viennent de la LIGNE**, jamais de l'appelant. C'est ce qui
  rend vraie l'exigence « les montants dus sont conservés même si la définition de quête change
  ensuite » : rééditer la quête ne touche pas une dette déjà née.
- **Reprise bornée.** Les dettes restées `PENDING` sont relues **une fois par chargement de
  joueur** (`QuestProgressEngine#recoverPendingMoneyRewards`, jamais une tâche par tick, jamais sur
  le thread principal), au plus `RECOVERY_BATCH = 20` à la fois, et une dette ayant déjà échoué
  `RECOVERY_MAX_ATTEMPTS = 5` fois est **laissée au panel** au lieu d'être réessayée indéfiniment.
  Une reprise échouée ne parle **pas** au joueur : sans cela, une base en panne produirait le même
  message d'échec à chaque reconnexion. Une reprise ne rejoue **que** le crédit monétaire — ni XP,
  ni objets, ni variables, ni commandes, ni la complétion elle-même.
- **Règlement manuel.** `SETTLED_MANUALLY` existe pour un cas précis : un administrateur a compensé
  le joueur par un crédit libre. Ce statut rend la dette **non payable**, sinon une reprise
  ultérieure paierait la même récompense une seconde fois. Il ne touche **aucun** solde.
- **Limite historique assumée.** Les complétions antérieures à V26 n'ont laissé aucune trace d'une
  récompense non payée : la migration marque les lignes existantes `PAID` (elles l'étaient, par
  construction de V25) et n'invente **aucune** dette rétroactive. Rien n'est payé rétroactivement,
  et le panel le dit à l'écran.
- **Quête répétable** : chaque acceptation crée une nouvelle progression, donc une nouvelle
  occasion, légitimement payée. `quest_reward_grants.occurrence` numérote ces complétions par
  joueur et par quête.
- **Traçabilité** : une ligne `transactions` de type `QUEST_REWARD` avec le contexte
  `quest:<id>#<identité de paiement>`, **composé depuis la ligne de dette** — rattachable à une
  complétion précise des mois plus tard, et non à un « gain de jeu » anonyme.
- **Administration** : trois actions agent, `economy.debts` (lecture, `ECONOMY_READ`),
  `economy.debt.retry` et `economy.debt.settle` (sensibles, `ECONOMY_WRITE`, confirmation + audit).
  La reprise **n'accepte aucun montant** — en accepter un permettrait d'en inventer un.
- **Table `quest_reward_grants` (schéma V25, étendue en V26)** : elle ne contient **aucun solde**.
  `wallets` reste la seule source de vérité ; cette table ne répond qu'à deux questions — « que
  reste-t-il à payer ? » et « cette occasion a-t-elle déjà été payée ? ». Statuts réels :
  `PENDING` / `PAID` / `SETTLED_MANUALLY`, plus `attempts` et `last_error` pour afficher un état
  honnête (« en échec », avec son motif) au lieu d'un « en attente » qui cacherait des échecs. Volontairement **sans** clé étrangère vers `player_profiles`, contrairement à
  `transactions` : un `ON DELETE CASCADE` rendrait un profil supprimé puis recréé payable une
  seconde fois pour les mêmes occasions.
- **Hors périmètre à ce jour** (décisions de gameplay non prises) : monnaie physique et conversion
  solde ↔ objet, perte à la mort, prix, règles d'échange.

**Feedback de remise** (`QuestProgressEngine#turnIn`) : un Title/Subtitle bref (`quest.completed-title`/`-subtitle`, `messages.yml`) annonce la fin, puis un résumé est envoyé dans le chat (`quest.reward-summary-header` + une ligne par récompense **réellement accordée** — `quest.reward-line-experience`/`-item`/`-special`). `VARIABLE` n'a pas de ligne (état interne, pas une récompense visible du joueur) ; `COMMAND` affiche une ligne générique (« Récompense spéciale ») car le contenu d'une commande arbitraire n'est pas inspectable — jamais de nom d'objet inventé. Une quête sans `rewards` n'envoie aucun résumé chat. Le journal (`QuestJournalService`) continue d'afficher les récompenses **prévues** dans le lore de chaque quête, y compris avant complétion.

`MONEY` n'a **pas** de ligne dans ce résumé synchrone, et pour une raison précise : son crédit est
asynchrone. Une ligne construite à cet instant annoncerait un gain avant d'avoir la moindre preuve
qu'il a eu lieu. Le message part donc **séparément**, un court instant plus tard, et seulement
après la réponse de la base : `quest.reward-money-credited` (montant **et** nouveau solde relu en
base) en cas de succès, `quest.reward-money-failed` en cas d'échec de persistance — jamais de
silence et jamais de faux succès. Si l'occasion était déjà payée, **rien** n'est dit au joueur
(ce serait un gain fantôme) et un avertissement part dans les logs serveur. Ces deux messages
passent par le **chat** et jamais par l'ActionBar, qui appartient à la progression des objectifs :
une notification d'économie y effacerait l'avancement affiché.

**Bourse dans le journal de quêtes** (`QuestJournalService`, issue #16) : le solde réel est affiché
dans un emplacement **inerte** de la barre du haut de la liste (`BALANCE_SLOT`) et de la vue détail
(`DETAIL_BALANCE_SLOT`) — l'objectif explicite étant que le solde soit lisible dans une interface
déjà utilisée, sans imposer de connaître `/money`. Il est relu à chaque ouverture et après chaque
transaction (le crédit déclenche `notifyChanged`, qui recompose un menu ouvert). Une erreur de
lecture affiche « indisponible » avec son motif : jamais un `0` inventé, qui ferait croire à un vol.

### Éditeur guidé de quêtes et de stories dans PlugAdmin (issue #46)

Le Control Panel (« PlugAdmin ») permet de **créer et modifier des quêtes et des stories sans
écrire de YAML à la main** :

- **Accès** : pages `/quests` (bouton « Créer une quête »), `/stories` (« Créer une story »), ou
  lien « Modifier » sur une carte du catalogue. Réservé aux rôles disposant de
  `QUEST_CONTENT_WRITE` / `STORY_CONTENT_WRITE` (`content-editor` et `owner`).
- **Formulaire guidé** : sections Général / Prérequis / Objectifs / Récompenses /
  Variables (quête) et Général / Chaîne de quêtes (story). Chaque type d'objectif
  (`KILL_ENTITY`, `COLLECT_ITEM`, `CRAFT_ITEM`, `BREAK_BLOCK`, `PLACE_BLOCK`, `TALK_TO_NPC`,
  `REACH_LOCATION`, `DELIVER_ITEM_TO_NPC`, `SMELT_ITEM`) et de récompense (`EXPERIENCE`, `ITEM`, `VARIABLE`, `COMMAND`, `MONEY`) est décrit par
  **un seul descripteur** (`Descriptors`) qui pilote ensemble libellé, description, champs, aide,
  listes proposées et validation. **Choisir le type n'affiche que les champs pertinents** ; le
  changement est immédiat (JavaScript progressif — `panel.js`) et **efface** les valeurs saisies
  pour le type précédent (jamais de valeur d'un autre type conservée en douce). Sans JavaScript,
  le serveur re-rend le bon jeu de champs au premier aller-retour.
- **Construire le brouillon n'est jamais bloqué** : le `<form>` de l'éditeur porte `novalidate`
  **et** aucun champ ne reçoit l'attribut HTML `required` (seul le marqueur visuel « * » reste ;
  les boutons d'action portent en plus `formnovalidate`). Ajouter / supprimer / réordonner une
  étape, un objectif ou une récompense ne peut donc jamais déclencher « Veuillez renseigner ce
  champ », quel que soit le mode de soumission. Après chaque action, la page se recale sur le
  composant concerné : ids stables `step-<i>` / `obj-<i>-<j>` / `rew-<i>` / `sec-*`,
  `formaction=".../save#ancre"`, et `panel.js` restaure explicitement la position au chargement
  (cible du hash via `scrollIntoView` + focus, sinon position mémorisée) — un POST HTML 200
  n'applique pas de façon fiable un fragment de `formaction`.
- **Listes recherchables** : les champs à liste longue (entité, matériau, PNJ, icône, catégorie,
  quête) sont des champs de recherche filtrés (composant local léger `panel.js` `initCombo`,
  aucune dépendance externe, aucun CDN), alimentés par la **bonne source** — la catégorie par une
  liste curée `RefData.CATEGORIES` + saisie libre, l'icône et la récompense `ITEM` par le
  **catalogue réel du serveur** (voir ci-dessous). Entités, matériaux **et PNJ** portent un
  libellé humain (`<option value="guard" label="Garde">`) : la recherche filtre sur le nom
  **et** l'id. La récompense `ITEM` accepte uniquement un `Material` vanilla (le moteur ne gère pas
  un objet personnalisé RPGQuest à cet endroit — l'aide le précise).
- **Résultats bornés, jamais perdus (issue #196)** : la liste n'affiche que les 100 premières
  correspondances pour ne pas construire mille lignes à chaque frappe, mais elle **annonce** le
  total (« 100 sur 142 affichés ») et un clic en affiche 100 de plus. Auparavant la borne était de
  60 **et muette** : une recherche large semblait n'avoir que 60 réponses.

#### Catalogue des objets : icônes et récompenses (issue #196)

**Défaut corrigé.** `RefData.MATERIALS` — une liste écrite à la main de **76 entrées** — servait de
catalogue. Elle ne contenait que `IRON_SWORD` et `DIAMOND_SWORD` : chercher « sword » ne trouvait
donc que **deux** épées sur les **sept** que la version installée expose
(bois, pierre, **cuivre**, or, fer, diamant, netherite). Même problème pour les récompenses.

**Nouveau relevé `item.catalogs`** (permission `CONTENT_READ`, bouton « Objets Minecraft » sur
`/quests`) : lit le registre `Material` **réel** de la version installée et renvoie
trois informations.

| Donnée renvoyée | Contenu | Usage |
|---|---|---|
| `items` | matériaux dont `isItem()` est vrai, hors `LEGACY_*` et hors air | proposés comme **icône** et comme **récompense** |
| `blocksWithoutItem` | blocs réels **sans** forme d'objet (eau, feu, portail…) | **refusés**, avec le motif écrit |
| `minecraftVersion` | version Minecraft du serveur | affichée pour tracer la provenance de la liste |

Trois filtres, chacun pour une raison :

- `isLegacy()` — les constantes d'avant l'aplatissement 1.13 donneraient des doublons trompeurs
  (`LEGACY_WOOD_SWORD` à côté de `WOODEN_SWORD`) et des objets que le serveur ne sait plus produire ;
- `isAir()` — un `ItemStack` d'air est une pile vide : ni icône visible, ni récompense livrable ;
- `isItem()` — seule garantie que l'API donne sur « peut exister comme objet ».

**Représentable vs délivrable.** Dans l'API Bukkit, c'est la **même** condition : un objet
affichable en inventaire et un objet livrable à un joueur ont tous deux besoin de `isItem()`. La
distinction utile n'est donc pas entre les deux listes, mais avec les **blocs sans forme d'objet** :
ceux-là ne peuvent être ni l'un ni l'autre, et le panel le dit explicitement
(« WATER existe comme bloc mais n'a aucune forme d'objet dans cette version ») au lieu de répondre
« matériau inconnu », qui enverrait chercher une faute de frappe inexistante.

**Noms français.** Le Control Panel ne peut pas lire les fichiers de langue du jeu (aucune
dépendance Bukkit). `MaterialNames` compose donc les libellés à partir de la structure des
identifiants — `DIAMOND_SWORD` → « Épée en diamant », `OAK_PLANKS` → « Planches de chêne »,
`GOLD_INGOT` → « Lingot d'or » (élision incluse). Ce qui ne correspond à aucune règle tombe sur une
table nominative courte, puis sur l'**anglais embelli** (`SCULK_CATALYST` → « Sculk Catalyst ») :
un repli visiblement non traduit est préférable à une traduction inventée. La recherche filtrant sur
l'identifiant **et** sur le libellé, taper « sword » ou « épée » donne le même résultat — un repli
anglais ne rend jamais un objet introuvable.

**Objets de créatif.** Quelques objets existent bel et bien mais ne s'obtiennent pas en jeu normal
(bloc de commande, bedrock, bâton de débogage…). Ils sont **signalés** « créatif / technique » dans
la liste, jamais retirés : l'API n'expose aucun indicateur « obtenable en survie », donc la seule
option honnête est une liste nommée et explicitement indicative.

**Sans relevé** : le repli curé reste utilisable, mais un bandeau et l'aide du champ disent
clairement qu'il s'agit d'une **liste de dépannage de 76 entrées**, pas du catalogue — et aucun
avertissement de validation n'est émis sur un matériau absent de ce repli, qui serait un faux positif.


#### Supprimer une quête ou une story (issue #194)

**Défaut corrigé** : il n'existait aucun moyen de supprimer une quête ou une story depuis le panel.
Un doublon créé par erreur restait là.

Bouton **« Supprimer… »** sur les fiches de `/quests` et `/stories`, visible seulement avec la
**permission dédiée `CONTENT_DELETE`** — accordée à `OWNER` et `ADMIN`, **volontairement refusée à
`CONTENT_EDITOR`** : écrire du contenu est réversible, le détruire ne l'est pas de la même façon.

Le bouton n'ouvre pas une suppression : il ouvre un **aperçu des conséquences**.

##### Trois règles de conduite

1. **Jamais de cascade.** Supprimer une quête ne supprime ni PNJ, ni dialogue, ni story. On
   *retire la référence*, on ne détruit pas le contenu qui la portait.
2. **Jamais de lien orphelin.** Si retirer une référence rendrait un contenu invalide, la
   suppression est **bloquée**, avec le fichier, la ligne et la marche à suivre — plutôt que de
   produire un fichier que le serveur refusera au chargement.
3. **Jamais de progression joueur touchée.** La suppression est **éditoriale**. Les lignes de
   progression existantes restent en base et deviennent sans objet. Réinitialiser un joueur est
   une opération distincte, avec sa propre action et sa propre permission.

##### Ce que l'aperçu affiche, et ce qui se passe à la confirmation

| Référence trouvée | Traitement |
|---|---|
| **Prérequis** d'une autre quête | **retiré automatiquement** — un prérequis est facultatif, la quête reste valide |
| Quête dans la **chaîne d'une story** | retirée de la chaîne… |
| …sauf si c'était la **seule** quête de la story | **BLOQUE** : une story à chaîne vide est refusée par le moteur |
| **Dialogue** (`QUEST_STATE`, `START_QUEST`…) | **BLOQUE**, avec le fichier et les **numéros de ligne**. Retirer l'action laisserait un choix sans effet ; retirer le choix change le dialogue. C'est une décision éditoriale, pas un nettoyage mécanique. |
| Fichier référençant **non analysable** | **BLOQUE** : on ne réécrit jamais un fichier qu'on n'a pas su lire |
| **PNJ donneur** | rien à nettoyer : le lien vit dans le champ `giver:` **de la quête supprimée**, et `NpcDefinition` ne référence aucune quête |
| **Progression joueur** | jamais touchée (voir règle 3) |

La **confirmation exige de retaper l'identifiant exact**. Un bouton « Confirmer » seul se clique
par réflexe ; un identifiant se tape volontairement. Et tant qu'un blocage subsiste, **le
formulaire de confirmation n'existe pas** : il n'y a rien à cliquer.

##### Source et runtime, traités séparément

Supprimer de la source ne retire pas le contenu du serveur : le fichier déployé reste chargé, et
le contenu « réapparaît » au prochain rafraîchissement du catalogue. L'aperçu dit donc où le
contenu existe, et à la confirmation :

1. la source est nettoyée puis le fichier supprimé ;
2. si le serveur le connaît, l'action agent **`content.definition.delete`** est enfilée. Côté
   serveur, le fichier est retrouvé par l'**identifiant déclaré dedans** (jamais par un nom de
   fichier deviné), sauvegardé, supprimé, puis les définitions sont **relues** — sans redémarrage.

**Exemples embarqués** : les 9 quêtes et la story d'exemple livrées dans le JAR sont **recréées au
démarrage** si le fichier manque. L'aperçu le signale explicitement : la suppression ne devient
définitive qu'après avoir reconstruit et redéployé le plugin depuis la source sans elles. Ce n'est
pas un blocage — on doit pouvoir retirer un exemple — mais ce n'est jamais passé sous silence. Un
test de cohérence du dépôt compare la liste du panel aux `BUNDLED_EXAMPLES` réelles du plugin, donc
une dérive est détectée au lieu d'être subie.

##### Sauvegarde et échec partiel

Tout fichier touché — celui qui part **et** ceux qui sont réécrits — est copié **avant** toute
écriture, sous un horodatage commun, dans `/var/lib/plugadmin/content-backups/<horodatage>/`.
Hors du dépôt Git et hors du JAR, délibérément : des sauvegardes sous `src/main/resources/`
entreraient dans le plugin construit et saliraient le working tree, ce qui bloquerait les scripts
de déploiement.

L'ordre d'exécution est choisi pour que le contenu visé soit toujours récupérable :

1. tout sauvegarder ;
2. réécrire les références — en cas d'échec, les réécritures déjà faites sont **restaurées** et on
   s'arrête **avant** toute suppression ;
3. supprimer le fichier visé **en dernier**, car c'est l'étape irréversible.

Si le fichier a changé entre l'aperçu et la confirmation, la suppression est **refusée** (conflit
de version) : on ne détruit pas un contenu que l'opérateur n'a jamais vu. La page de résultat cite
toujours le chemin de sauvegarde, pour qu'une restauration ne se devine pas.

- **Chaîne de quêtes d'une story** : une story est une liste **ordonnée** de quêtes existantes
  (modèle moteur : `id`, `name`, `secret`, `questIds` — rien d'autre). La sélection d'une quête
  est recherchable **par titre humain (« Premiers pas ») ou par id technique (« first_steps »)** :
  la datalist `dl-quest` porte le titre en `label`, alimenté par le dernier relevé `quest.list`.
  Chaque ligne de la chaîne affiche le rang `N.` et le titre humain **au-dessus** de
  l'identifiant technique, avec les contrôles monter / descendre / retirer ; une quête absente du
  catalogue chargé est signalée « quête inconnue » dès la saisie. Ajouter / retirer / réordonner
  une quête ne déclenche jamais la validation ; `StoryValidator` (id, nom, chaîne non vide,
  référence inconnue, doublon — **autorisé** par le moteur, donc simple avertissement) ne
  s'exécute qu'à « Vérifier » / « Enregistrer ».
- **Validation métier uniquement à la fin** — sur « Vérifier » / « Aperçu » / « Enregistrer » :
  diagnostics `ERREUR` (bloquants), `ATTENTION` (enregistrement possible après vérification),
  `INFO`. Un aperçu montre le fichier YAML généré et le diff avec la version actuelle de la source.
- **Enregistrement dans la source, jamais de déploiement** : l'écriture ne se fait que dans le
  **checkout Git** (`src/main/resources/quests/<id>.yml`, `.../stories/<id>.yml`), avec détection
  de conflit par hash, écriture atomique et garde-fou de relecture. L'éditeur ne touche **jamais**
  le serveur live et ne fait **aucun** transfert FTP. Le déploiement reste une opération manuelle
  distincte (voir §1). Si le service PlugAdmin n'a pas les droits d'écriture sur ces dossiers (ou
  si `content.repo-dir` n'est pas configuré), l'éditeur reste consultable en **lecture seule**.
- L'autorité finale sur la validité d'un fichier reste le **chargement du plugin** au démarrage
  du serveur : un fichier incompatible est rejeté à ce moment-là (voir `QUEST_FORMAT.md`).

#### Supprimer un PNJ (issue #226)

**Défaut corrigé** : le panel savait créer une définition PNJ, créer et lier un Citizens, et
administrer ses propriétés — mais pas supprimer. Un PNJ de test, ou l'entrée fantôme d'un doublon,
restait là sans autre recours qu'une commande en jeu ou un fichier modifié à la main.

Bouton **« Supprimer… »** dans une **Zone de danger** sur la fiche de `/npcs`, visible seulement
avec la **permission dédiée `NPC_DELETE`** — accordée à `OWNER` et `ADMIN`, refusée au
`CONTENT_EDITOR`, au `BUILDER` et au `TESTER`. Détruire l'entité Citizens *physique* exige en plus
`NPC_SPAWN_WRITE` : c'est l'inverse exact de sa création, et regrouper ne doit jamais accorder un
droit que l'opérateur n'a pas.

Le bouton n'ouvre pas une suppression : il ouvre un **aperçu des dépendances**. Rien ne se supprime
depuis la liste.

##### Cinq couches, parce qu'un PNJ n'existe pas en un seul endroit

L'aperçu affiche les couches **présentes et absentes** — « aucun PNJ Citizens lié » est précisément
ce qu'il faut savoir avant de choisir, et son absence à l'écran se lirait comme un oubli :

1. définition logique RPGQuest (`npcs/<id>.yml`) ;
2. binding RPGQuest ↔ Citizens ;
3. PNJ Citizens physique ;
4. dialogue lié — et s'il est **partagé** par plusieurs définitions ;
5. références de contenu : donneur de quête, objectif `TALK_TO_NPC`, remise
   `DELIVER_ITEM_TO_NPC`, quêtes démarrées par son dialogue.

##### Quatre opérations, jamais une cascade

| | Opération | Effets | Ce qu'elle préserve |
|---|---|---|---|
| **A** | Définition logique seule | supprime `npcs/<id>.yml`, **après sauvegarde** serveur dans `npc-backups/` | le PNJ Citizens, sa liaison, le dialogue. L'aperçu annonce que le Citizens deviendra « orphelin » |
| **B** | Délier Citizens | retire la liaison — **seule opération réversible** | l'entité, la définition, le dialogue |
| **C** | Supprimer le Citizens physique | détruit l'entité et retire sa liaison devenue sans objet | la définition et le dialogue |
| **D** | Nettoyage complet | A + C, dans l'ordre | **le dialogue, toujours** |

**Aucune opération ne supprime un dialogue**, et la page le répète à chaque étape : un dialogue
peut être porté par plusieurs PNJ, et de toute façon son auteur n'est pas forcément celui qui
supprime le PNJ.

##### Ce qui bloque

Une quête qui désigne encore le PNJ — **donneur**, cible d'un **« parler à »**, ou **destinataire
d'une remise** — bloque la suppression de sa définition, en nommant les quêtes. Les trois sont
distinguées parce qu'elles ne se corrigent pas de la même façon : une remise qui perd son
destinataire rend la quête **infinissable**, alors qu'un « parler à » se réaffecte. La remise était
d'ailleurs la seule des trois qu'aucun écran ne montrait — elle traverse désormais le relevé
`npc.list` (`questsDelivering`, source `QUEST_DELIVER`).

Une opération bloquée **n'a pas de formulaire** : il n'y a rien à cliquer.

##### Garde-fous

- **Confirmation tapée** : retaper l'identifiant du PNJ, et l'identifiant **numérique Citizens**
  quand l'opération touche l'entité. Un bouton « Confirmer » seul se clique par réflexe.
- **Jamais le mauvais Citizens** : le serveur confronte l'identifiant numérique à la liaison
  réelle, puis détruit par la **double clé UUID + identifiant numérique**. Un identifiant recyclé
  par Citizens depuis l'affichage de l'écran ne peut donc pas faire détruire un voisin.
- **Le plan est recalculé au POST** : une URL forgée vers une opération que l'aperçu bloque est
  refusée, et le refus est audité.
- **Sans relevé `npc.list`, aucune opération n'est proposée.** « Aucune dépendance » et « on n'a
  pas regardé » ne sont pas la même phrase, et devant un bouton de suppression la confusion
  détruit du contenu.
- **Le serveur revalide** les références de contenu au moment d'exécuter, sur les moteurs en
  mémoire — pas seulement à l'affichage. Un aperçu a l'âge de son calcul.
- **Idempotent** : une définition déjà absente ou une liaison déjà retirée renvoient « rien à
  faire » plutôt qu'une erreur. Un double clic ou un rejeu réseau est inoffensif.
- **Asynchrone, et dit comme tel** : la demande devient une action agent whitelistée
  (`npc.definition.delete`, `npc.citizens.unlink`, `npc.citizens.delete`). Le verdict se lit dans
  le journal d'actions. La page annonce une *demande*, jamais un fait accompli.
- **Aucune progression de joueur n'est effacée.** La suppression est éditoriale.

##### Une entrée « sans définition »

Elle n'est pas un PNJ cassé : c'est la **trace d'une référence**. L'aperçu en dit la provenance et
les remèdes. Voir « Catalogue PNJ : le dialogue réellement lié » ci-dessous.

#### Catalogue PNJ : le dialogue réellement lié (issue #225)

**Défaut corrigé** : `/npcs` montrait **deux** entrées « Mira » — `mira_cartographer` (définie,
Citizens #9) et `mira_first_map` « sans définition » — et la première apparaissait *sans dialogue*
alors que les joueurs l'entendaient.

Cause, trouvée dans le relevé `npc.list` réel du serveur : `NpcCatalog` ne lisait le rattachement
PNJ → dialogue que par la **convention de nom**. Or `mira_cartographer` déclare
`rpgquest:mira_first_map` dans son champ `dialogue:`, et aucun dialogue ne porte son nom. Deux
conséquences, toutes deux visibles :

1. le PNJ apparaissait sans dialogue, bien qu'il en ait un, chargé ;
2. ce dialogue, que personne ne « réclamait », fabriquait à son tour une entrée de catalogue — un
   « PNJ » sans définition portant le nom du dialogue, que rien ne permettait de corriger puisqu'il
   n'était la faute de personne.

Le rattachement se lit désormais dans cet ordre :

1. le dialogue que la définition **déclare** (`dialogue:`) ;
2. à défaut, le dialogue qui porte le **nom** du PNJ — la convention historique, qui reste le
   défaut.

Un dialogue **revendiqué** par une définition n'est plus déduit en PNJ. Un dialogue que personne ne
revendique l'est toujours — c'est le cas de transition légitime — mais avec une anomalie
`DIALOGUE_WITHOUT_NPC` qui **nomme sa cause** et ses deux remèdes : créer la définition de ce nom,
ou rattacher le dialogue à un PNJ existant via son champ `dialogue:`. Dire « à migrer » sans dire
d'où vient l'entrée était la moitié du problème : on ne corrige pas une anomalie dont on ignore la
cause.

> La convention « le dialogue porte le nom du PNJ » n'est qu'un **défaut**. La fiche fait autorité,
> et on ne renomme jamais un dialogue pour « respecter la convention » — cela casserait le lien
> existant.

### Emplacements de construction — `/buildings/sites` (issue #213)

Première brique du futur système de bâtiments. Un **emplacement** est un point d'ancrage nommé dans
un monde : il ne contient **aucun** bâtiment, aucune dimension, aucune emprise et aucun schematic.
Ce lot ne sait rien poser, et ne prétend rien à leur sujet.

#### Ce qu'un emplacement porte

| Champ | Rôle |
|---|---|
| `id` | identité **stable**, attribuée une fois (`buildsite_0001`), jamais recalculée depuis la position ni réutilisée après suppression — c'est elle qu'un futur placement citera |
| `name` | libellé humain, **saisi à la création** dans l'enclume (prérempli « Nouvel emplacement »), modifiable à volonté |
| `description` | note libre de l'administrateur, vide par défaut |
| `world` / `x` / `y` / `z` | l'ancre, en coordonnées de **blocs** |
| `facing` | `NORTH` / `EAST` / `SOUTH` / `WEST`, corrigeable |
| `status` | `EMPTY` — seul état que ce lot sait produire |
| `created_by` / `created_at` | auteur (vide si l'identité n'était pas disponible — jamais inventée) et date |

**Coordonnées entières, et pas flottantes** : un bâtiment se pose sur la grille de blocs. Les
centres de village (`village_centers`) stockent des `REAL` parce qu'ils sont des destinations de
téléportation, où un demi-bloc compte ; ici un `REAL` n'exprimerait qu'une précision inutilisable.

#### La règle d'ancrage

**L'ancre est le bloc adjacent à la face cliquée** — l'espace libre contre lequel on vient de
cliquer. Cliquer le dessus d'un bloc d'herbe en `y=66` enregistre `y=67` : la case où l'on se
tiendrait, et où reposera le premier niveau du bâtiment.

Pourquoi pas le bloc cliqué lui-même : un bâtiment ne s'enfonce pas d'un bloc dans le terrain. Si
l'ancre était le bloc cliqué, tout placement futur devrait ajouter `+1` en Y — un décalage
implicite, que chaque appelant appliquerait de son côté, et qu'un seul oublierait. Le ticket #213
l'exige d'ailleurs : l'ancre doit être « indépendante d'un offset interne implicite ».

La même règle s'applique aux six faces, sans cas particulier : cliquer la face nord d'un mur ancre
un bloc au nord de ce mur. La règle est une fonction **pure** (`BuildingSiteAnchor`), donc
réellement exécutable dans un test — une règle qu'on ne peut pas exécuter n'est pas précise, elle
est seulement écrite.

Une ancre hors des limites du monde (cliquer le dessus du bloc le plus haut) est **refusée** avec
son message : seul le serveur connaît les limites réelles du monde chargé.

#### L'orientation

Convertie **une seule fois**, à la création, depuis le regard horizontal du joueur. Le yaw brut
n'est pas stocké : un bâtiment se pose aligné sur la grille, et conserver `177,43°` donnerait une
précision que le placement ne saura jamais utiliser — en obligeant chaque lecteur à refaire la même
conversion, donc à la refaire différemment.

> **Piège à connaître** : dans Minecraft, le yaw `0` regarde le **sud** (`+Z`), pas le nord. La
> conversion est couverte sur les quatre cardinaux, les yaw négatifs, les tours multiples et les
> diagonales exactes (qui tombent sur le quadrant suivant — arbitraire, mais déterministe, et
> l'orientation reste corrigeable depuis le panel).

#### L'outil en jeu

`/rpgadmin buildsite tool` donne l'outil ; `/rpgadmin buildsite list` liste les emplacements sans
quitter le jeu. Le clic gauche ne crée rien (il est simplement annulé, pour ne pas casser de bloc
avec l'outil) et rappelle la bonne manipulation.

- **Reconnu par son PDC uniquement.** Un joueur peut nommer une houe « Outil d'emplacement de
  construction » dans une enclume : elle ne fera rien.
- **Une houe en fer**, ni hache en bois (wand WorldEdit par défaut, que WorldEdit reconnaît *par
  type d'objet* — les deux plugins se disputeraient le clic), ni tige de blaze (outil de zone
  existant, qu'on confondrait dans la barre d'inventaire). Le ticket demande explicitement de ne pas
  perturber WorldEdit.
- La permission est vérifiée **avant** de regarder le clic : un joueur ordinaire qui récupérerait
  l'outil (mort d'un administrateur, coffre, `/give`) ne crée rien, et le comprend plutôt que de
  cliquer dans le vide.

#### Créer en deux temps : clic, puis nom (issue #227)

Le clic droit **n'écrit plus rien**. Il calcule l'ancre et l'orientation, les retient en mémoire, et
ouvre une **enclume vanilla** pour saisir le nom. Seul un clic sur le résultat crée l'emplacement.

Ce découpage vient directement de la validation manuelle de #213 : un clic de travers créait
immédiatement un emplacement, qu'il fallait ensuite aller supprimer depuis le Control Panel. Un
repère nommé n'est pas une chose qu'on crée par accident.

| Geste du joueur | Ce qui est écrit |
|---|---|
| clic droit sur un bloc | **rien** — une demande en attente, en mémoire seulement |
| nom tapé puis clic sur le résultat | l'emplacement, avec son nom |
| fenêtre fermée (Échap, inventaire, clic hors fenêtre) | **rien**, et c'est dit |
| 60 secondes sans valider | **rien** — la demande expire |
| déconnexion pendant la saisie | **rien** |

**Aucun identifiant n'est consommé avant la confirmation** : l'allocateur `AUTOINCREMENT` n'est
appelé qu'à l'écriture. Cent clics annulés ne font pas sauter cent numéros.

**L'enclume, et pas le chat.** C'est une fenêtre vanilla, sans resource pack, qui offre un champ de
texte, accepte l'Unicode, et dont la fermeture est un geste naturel d'annulation — là où un « tapez
le nom dans le chat » laisse une demande ouverte que rien ne vient clore.

API publique utilisée, auditée sur le JAR Paper réellement installé avant d'écrire la moindre ligne
(aucun NMS, aucune réflexion) :

| Besoin | API publique |
|---|---|
| ouvrir la fenêtre | `HumanEntity#openAnvil(Location, boolean force)` |
| lire le texte tapé | `AnvilInventory#getRenameText()` |
| forcer un résultat cliquable | `PrepareAnvilEvent#setResult(ItemStack)` |
| annuler le coût en niveaux | `AnvilView#setRepairCost(int)` / `setMaximumRepairCost(int)` |

> **Deux pièges de l'enclume, et leur traitement.** (1) Vanilla ne propose un résultat que si le nom
> diffère de l'original, et **réclame des niveaux** : sans `PrepareAnvilEvent`, le bouton de
> validation serait inerte ou payant. Le résultat est donc forcé et le coût ramené à zéro.
> (2) Valider implique de **fermer** la fenêtre, donc `InventoryCloseEvent` part *aussi* après un
> succès. La demande est retirée du registre au moment de confirmer : la fermeture qui suit ne trouve
> plus rien et se tait, au lieu d'annoncer « annulé » juste après une création réussie. C'est la même
> mécanique qui rend un double clic sur le résultat inoffensif.

**Le nom est obligatoire**, coupé de ses espaces, limité à 64 caractères, Unicode accepté. Un nom
vide ou trop long est **refusé sans fermer la fenêtre** : le joueur corrige sa saisie au lieu de
devoir retourner cliquer dans le monde. Un nom trop long n'est jamais tronqué en silence.

**Le nom ne dérive pas l'identifiant.** `buildsite_0001` reste attribué par l'allocateur : renommer
« Taverne » en « Auberge » ne change aucune référence, et deux emplacements peuvent porter le même
nom sans se marcher dessus.

#### Anti-doublon et anti-missclick

1. **Le même bloc.** Un emplacement existe déjà exactement là ? La fenêtre de nom ne s'ouvre même
   pas : inutile de faire taper un nom pour annoncer ensuite qu'il n'y a rien à créer. La règle est
   **revérifiée à la confirmation**, car un autre administrateur a pu marquer ce bloc pendant la
   saisie.
2. **Le même geste.** Un clic droit Minecraft émet couramment deux événements rapprochés. Une
   fenêtre d'anti-rebond de **500 ms par joueur** l'absorbe — sans elle, le second clic rouvrirait la
   fenêtre par-dessus la première, et cette réouverture annulerait la demande en cours de saisie.
3. **Le bloc voisin.** Un emplacement à un bloc de l'ancre (les 26 cases du cube autour) déclenche un
   **avertissement avant validation**, nommant l'emplacement concerné et invitant à fermer la fenêtre
   si c'était un clic de travers. **Ce n'est pas un refus** : deux emplacements voisins peuvent être
   légitimes.

> **L'anti-rebond appartient au clic, pas à l'écriture.** Il a d'abord été placé dans la création
> elle-même ; un joueur rapide qui validait son nom moins de 500 ms après avoir cliqué aurait vu sa
> confirmation avalée en silence. Il vit donc sur le chemin du clic (`acceptClick`), et un test
> (`writingIsNeverDebounced`) verrouille le fait qu'une confirmation n'est **jamais** anti-rebondie.

**Aucune règle de distance minimale n'est inventée.** Deux emplacements à deux blocs l'un de l'autre
peuvent être parfaitement légitimes — une maison et son puits. L'interdire demanderait de connaître
l'emprise des bâtiments, que ce lot ne connaît pas : la question appartient au lot de placement.

#### Permission dédiée

`rpgquest.admin.buildsite` (`default: false`), **pas** une permission WorldEdit : l'outil ne
sélectionne aucune région et ne modifie aucun bloc, et un builder équipé de WorldEdit n'a aucune
raison de créer des points d'ancrage de contenu. Réutiliser `worldedit.wand` aurait lié deux
surfaces d'autorisation sans rapport, et rendu impossible d'accorder l'une sans l'autre.

La branche `/rpgadmin buildsite` échappe à l'ombrelle historique `rpgquest.admin.world` — comme la
branche PNJ de #200 — mais l'ombrelle l'implique : un administrateur existant ne perd rien, et un
compte non-OP peut recevoir ce seul nœud.

#### Persistance

Table `building_sites` (+ `building_site_ids` pour l'allocateur), migration **V28**, purement
additive : deux tables neuves, aucune colonne ajoutée ailleurs, aucune donnée existante lue ni
réécrite. La base est la **source de vérité** ; le service en garde un cache mémoire, chargé au
démarrage et mis à jour après chaque écriture réussie, jamais consulté pour décider si une écriture
a eu lieu.

**Les identifiants ne sont jamais réutilisés** : l'allocateur est une table `AUTOINCREMENT`, pas un
`MAX()` sur `building_sites`. Dériver le prochain numéro des lignes existantes recyclerait
l'identifiant d'un emplacement supprimé — et un identifiant recyclé est exactement ce qui ferait
pointer un futur placement sur le mauvais emplacement.

`status` est un `TEXT` sans contrainte et la lecture est tolérante (valeur inconnue → `EMPTY`) :
ajouter `RESERVED`/`OCCUPIED` plus tard ne demandera **aucune migration**, et une base écrite par
une version plus récente reste lisible par une plus ancienne.

#### Le Control Panel

Navigation **Bâtiments → Emplacements**. Liste avec recherche et filtre par monde (un lien par
monde : aucun script, et l'URL est partageable), fiche en accordion, renommage, description,
correction d'orientation, suppression.

**Aucun bouton « Créer », et la page l'explique.** Un emplacement est défini par une position
désignée du doigt ; un formulaire web devrait inventer des coordonnées. La carte « Créer un
emplacement : en jeu, pas ici » donne la marche à suivre, et s'affiche même quand la liste est vide
— c'est-à-dire précisément quand on en a besoin.

**L'orientation est éditable, la position non.** Se tromper de façade au moment du clic est banal ;
déplacer un point d'ancrage depuis un écran ne l'est pas — on retourne le désigner en jeu, et l'aide
le dit.

Sans relevé `building.site.list`, la page dit « cliquez sur Rafraîchir » au lieu d'afficher une
liste vide, qui se lirait comme « aucun emplacement » — donc comme une perte de données après un
redémarrage. Un emplacement situé dans un monde **non chargé** est signalé et reste compté : il est
parfaitement valide, il n'est simplement pas visitable pour l'instant.

##### Le retour après une action : dérivé de la navigation, jamais écrit à la main (issue #227)

Après une action agent, `/agents/action` renvoie l'utilisateur sur la page d'où il vient. La liste
des chemins de retour acceptés — un filtre, qui existe pour empêcher une redirection ouverte vers un
site tiers — était **écrite à la main**, et `/buildings/sites` n'y figurait pas. Conséquence :
*toutes* les actions de la page (renommer, décrire, orienter, supprimer, **et le bouton
Rafraîchir**) jetaient l'utilisateur sur `/agents`.

> **Les mutations fonctionnaient.** Le journal d'actions du serveur de validation est sans
> ambiguïté : chaque `building.site.*` y est en `SUCCESS`, avec son compte rendu (« renommé “Test
> hutte” », « orienté vers le nord », « supprimé », puis `building.site.list` → « 0 emplacement(s) »).
> Le défaut était entièrement dans le retour. Mais comme Rafraîchir souffrait du **même** défaut,
> l'utilisateur ne pouvait jamais revenir constater le résultat — d'où sa conclusion, logique et
> pourtant fausse, que rien n'était enregistré. Corriger la seule redirection de #213 aurait laissé
> le défaut armé pour la page suivante.

La liste est désormais **dérivée de `Layout.nav()`** : toute page atteignable par la navigation est
un retour accepté, par construction. Ajouter une page au menu ne demande plus de penser à une
seconde liste — ce qui est précisément l'oubli qui a produit #227. Le filtre garde son rôle : une
cible hors du panel (`https://…`, `//…`, `javascript:`, chemin inconnu) retombe toujours sur
`/agents`.

Deux familles de tests verrouillent cela. `ActionReturnPathTest#everyNavigablePageIsAnAcceptedReturnPath`
parcourt la navigation réelle et échoue dès qu'une page y est ajoutée sans être acceptée en retour.
Et les tests de `/buildings/sites` **extraient le formulaire rendu par la page** — URL d'action,
champs cachés, jeton CSRF, case de confirmation — et le soumettent tel quel, au lieu d'écrire un
corps de requête à la main : un corps écrit à la main contient ce que le test croit nécessaire, et
aurait donc porté le bon `return` même quand la page en émettait un que le serveur refusait. Les six
passent au vert avec le correctif et échouent toutes avec le symptôme exact de #227 sans lui.

| Permission | Qui l'a | Ce qu'elle donne |
|---|---|---|
| `BUILDING_READ` | Propriétaire, Administrateur, **Builder**, Testeur | voir la page et les fiches |
| `BUILDING_WRITE` | Propriétaire, Administrateur | libellé, note, orientation |
| `BUILDING_DELETE` | Propriétaire, Administrateur | retirer le marqueur |

`BUILDING_READ` est accordée au **Builder** parce qu'un emplacement est un repère de construction —
exactement ce que ce rôle consulte — et au **Testeur** pour vérifier qu'un emplacement marqué en jeu
est bien arrivé. Aucune des trois ne permet de créer.

#### Actions agent

| Action | Permission | Effet |
|---|---|---|
| `building.site.list` | `BUILDING_READ` | catalogue — lecture seule |
| `building.site.rename` | `BUILDING_WRITE` | libellé humain |
| `building.site.describe` | `BUILDING_WRITE` | note libre ; une description **vide est valide** (c'est « effacer la note ») |
| `building.site.facing` | `BUILDING_WRITE` | orientation — **jamais** la position |
| `building.site.delete` | `BUILDING_DELETE` | retire le marqueur logique |

**Il n'existe volontairement aucune action de création.** Un emplacement est défini par une position
choisie dans le monde ; le clic en jeu la connaît, un écran devrait l'inventer.

#### Supprimer un emplacement

Depuis la fiche, **Zone de danger → Supprimer**, avec case à cocher explicite (l'action est déclarée
sensible). **Aucun bloc du monde n'est touché** : un emplacement n'est qu'un repère, et ce lot ne
sait rien poser — il n'y a donc rien à défaire en jeu. L'opération est **idempotente** : un
identifiant déjà absent réussit en disant qu'il n'y avait rien, donc un double clic est inoffensif.
L'identifiant supprimé n'est jamais réattribué.

Cette suppression devra être **repensée** le jour où un bâtiment pourra être réellement posé sur un
emplacement : il faudra alors la bloquer, ou traiter la construction posée explicitement.

#### Ce qui est prêt pour la suite, et ce qui ne l'est pas

Prêt : l'identité stable et réutilisable, la position et l'orientation d'ancrage, la fiche
éditable, et un état extensible sans migration. Un futur `BuildingPlacement` n'a qu'à citer un
`buildsite_id`.

Pas fait, et volontairement : aucune dimension ni emprise (le ticket les veut définies *avant*
placement, et elles dépendent du bâtiment, pas du site), aucune action de téléportation vers un
emplacement (il n'existe aucun service de TP admin réutilisable — le construire serait un lot à
part), et aucun marqueur visuel en jeu.

### Bibliothèque de bâtiments et premier placement — `/buildings/library` (issue #213)

Le lot suivant du même chantier : un bâtiment peut enfin être **affecté à un emplacement et posé
dans le monde**. Trois concepts, et leur séparation n'est pas cosmétique :

| Concept | Nature | Où il vit |
|---|---|---|
| `BuildingSite` | un point d'ancrage nommé | base (`building_sites`, V28) |
| `BuildingDefinition` | ce qu'on peut poser | **fichier** `plugins/RPGQuest/buildings/*.yml` |
| `BuildingPlacement` | un bâtiment réellement posé | base (`building_placements`, V29) |

**Pourquoi la définition est un fichier et le placement une table.** Une définition est du
*contenu* : elle se relit, se compare entre deux versions, se corrige dans un éditeur et se
versionne avec le dépôt — exactement comme une quête ou un dialogue. Un placement est un *fait* :
« le 8 octobre à 22 h, la hutte a été posée ici, tournée de 90°, et voici la sauvegarde de ce qui
s'y trouvait avant ». Rien d'autre ne peut le reconstituer.

#### WorldEdit est une dépendance d'infrastructure, jamais du domaine

Tout passe par **`SchematicGateway`**, une interface RPGQuest dont la surface ne parle que de plans,
de noms de fichiers, d'emprises et de degrés. `BuildingSite`, `BuildingDefinition` et
`BuildingPlacement` **n'exposent aucun type WorldEdit** ; ils ne savent pas qu'il existe.

Une seule classe le connaît : `building.worldedit.WorldEditSchematicGateway`. Même conception que
le pont Citizens — `compileOnly`, `softdepend`, et un refus **nommé** si le plugin est absent,
désactivé ou incompatible (`LinkageError` intercepté). RPGQuest démarre normalement sans WorldEdit ;
seule la pose est refusée, en le disant.

L'effet mesurable de cette frontière : **l'essentiel du lot s'exécute dans les tests**. La rotation,
l'emprise, l'ordre des opérations, les refus, le retour arrière et la survie à un redémarrage sont
vérifiés contre un faux moteur, sans WorldEdit sur le chemin de classe. Ce qui reste derrière
l'interface est mince, et c'est précisément la part qu'aucun test honnête ne couvrirait sans serveur.

#### La hutte de test est produite par notre code, pas importée

Aucun fichier externe n'entre dans le dépôt. `TestHutBlueprint` décrit la hutte **en Java** —
7 × 5 × 6, fondation en pierre, poteaux d'angle en rondins, murs en planches, porte centrée, deux
fenêtres, toit à deux pans, intérieur vide — et `SchematicWorkshop` l'écrit avec l'écrivain officiel
du moteur (Sponge v3).

> **Pourquoi un plan en code plutôt qu'un `.schem` dans Git.** Un blob gzip de quelques kilo-octets
> ne se relit pas en revue : personne ne pourrait affirmer qu'il mesure 7 × 5 × 6 ni que sa porte
> est centrée. Le plan, lui, s'inspecte — et `TestHutBlueprintTest` l'inspecte réellement, bloc par
> bloc. La définition YAML lit d'ailleurs ses dimensions **sur le plan**, et un test échoue si les
> deux divergent : une définition qui mentirait sur ses dimensions ferait annoncer une emprise
> fausse, et la faute ne se verrait qu'après le collage.

**La façade est volontairement dissymétrique** (porte au centre, deux fenêtres de part et d'autre,
toit à deux pans). Une hutte à symétrie parfaite ne dirait rien d'une rotation de 180° : on ne
pourrait pas la distinguer d'une rotation nulle, et le test manuel ne conclurait rien.

**Où vit le fichier, et pourquoi.** Dans `plugins/RPGQuest/schematics/`, produit au démarrage s'il
est absent — jamais écrasé s'il existe, comme tout contenu déposé par le plugin. Le dépôt versionne
le **générateur** et la **définition**, pas le binaire : un audit préalable a montré que
`worldedit-core` lève `IllegalStateException: WorldEdit is not initialized yet` hors d'un serveur
(le registre de blocs est peuplé par la plateforme), donc un `.schem` ne peut être ni produit ni
vérifié sur la machine de build. Versionner un binaire que la CI ne peut pas régénérer serait
versionner quelque chose que personne ne peut contrôler. `/rpgadmin building generate` le réécrit à
la demande.

#### L'ancre : centre de la porte, au niveau du sol

Convention **unique et explicite**, énoncée dans la définition et nulle part ailleurs en dur. Pour
la hutte : `3 / 1 / 0`.

Le `1` porte une conséquence qu'il faut connaître : la fondation (`y = 0`) se place **un bloc sous**
l'ancre de l'emplacement. C'est voulu — une fondation s'enfonce dans le sol, et l'ancre d'un
emplacement est justement la case libre au-dessus du bloc cliqué.

`front` déclare la direction que regarde la façade à rotation nulle, et elle doit être
**géométriquement vraie**. Pour la hutte, la porte est sur la paroi `z = 0`, qui regarde les `-Z`,
donc le nord : d'où `front: NORTH`. Déclarer `SOUTH` sur la même géométrie aurait caché un demi-tour
permanent dans le code de collage — exactement l'offset implicite que ce lot devait éviter.

#### La rotation, mesurée et non supposée

`BuildingRotation` est une fonction **pure** : azimut de boussole (nord = 0, horaire), rotation =
écart d'azimut entre `front` et l'orientation de l'emplacement, toujours multiple de 90°.
`BuildingFootprint` exprime les coins du schematic en décalages **relatifs à l'ancre**, les fait
tourner, puis les replace autour de l'ancre du monde — l'ancre est donc un point fixe, ce qui est
précisément ce qu'on attend d'une ancre.

À **90° et 270°, largeur et profondeur s'échangent** : une hutte 7 × 5 occupe 5 × 7. L'aperçu
l'affiche et l'explique, parce que c'est l'erreur la plus facile à faire en estimant une emprise de
tête.

> **Le sens de rotation de WorldEdit n'est pas deviné.** La convention de signe de
> `AffineTransform#rotateY` est une décision interne à la bibliothèque ; la supposer serait un pari,
> et un pari perdu pose la hutte à l'envers sur du terrain déjà écrasé. L'adaptateur **applique donc
> la transformation aux coins du schematic**, compare l'emprise obtenue à celle calculée par le
> domaine, et ne colle que si les deux coïncident. Si le signe opposé est celui qui correspond, il
> est utilisé et journalisé une fois ; si aucun ne correspond, le collage est **refusé**. L'emprise
> annoncée à l'administrateur est ainsi toujours celle qui sera réellement occupée.

#### L'ordre des opérations est la garantie principale

1. **Vérifier** — toutes les règles de l'aperçu sont **rejouées** au moment de poser. Entre l'écran
   et le clic, un autre administrateur a pu occuper l'emplacement ou décharger le monde. *Un aperçu
   n'est pas une réservation.*
2. **Sauvegarder** la zone de l'emprise dans un `.schem` daté. Si cela échoue, **on ne colle pas** :
   poser sans pouvoir revenir en arrière n'est pas acceptable pour une première validation.
3. **Coller**, avec l'air (c'est lui qui creuse l'intérieur de la hutte ; l'ignorer laisserait le
   terrain dans les murs).
4. **Enregistrer** le placement, puis marquer l'emplacement `OCCUPIED`.

Un échec au collage laisse l'emplacement **vide** et n'inscrit aucun placement : il n'y a pas de
faux placement possible. Si l'enregistrement échoue *après* un collage réussi, le message le dit
explicitement et nomme le fichier de sauvegarde — mieux vaut un bâtiment posé sans fiche, qu'un
administrateur peut constater, qu'une fiche sans bâtiment.

**`site_id` est la clé primaire de `building_placements`** : « un emplacement porte au plus un
bâtiment » est donc une règle appliquée par le **schéma**, pas seulement par le service. Un double
clic est refusé par le service, et le serait de toute façon par la base si deux requêtes arrivaient
ensemble.

#### Le retour arrière restaure, il ne détruit pas

La sauvegarde prise avant la pose est reposée **telle quelle**, sans transformation, à l'emplacement
exact d'où elle vient. Puis l'emplacement redevient vide.

**Sans sauvegarde, le retour arrière est refusé** — et le bouton n'apparaît même pas. Remettre de
l'air dans l'emprise détruirait le terrain d'origine : ce serait une destruction déguisée en
annulation. C'est la limite que le ticket demandait de ne pas franchir, et de documenter plutôt que
de contourner.

**Limite à connaître, et elle est réelle** : la restauration repose un instantané. Tout ce qui a été
construit dans l'emprise *après* la pose est également écrasé. L'écran le dit en clair.

#### `SiteStatus.OCCUPIED`, arrivé sans migration

Le socle de #213 ne déclarait qu'`EMPTY`, exprès : aucun geste ne pouvait produire autre chose. Le
geste existe maintenant, donc l'état existe — et son ajout n'a demandé **aucune migration**, parce
que la colonne est un `TEXT` et la lecture tolérante. C'est exactement le bénéfice que cette
décision visait, constaté un lot plus tard.

L'état **suit** le fait, il ne le décide pas : il n'existe volontairement **aucune action agent**
pour l'éditer à la main, ce qui permettrait de déclarer « occupé » un emplacement vide.

#### Actions agent et permissions

| Action | Permission | Effet |
|---|---|---|
| `building.definition.list` | `BUILDING_READ` | bibliothèque — lecture seule |
| `building.placement.preview` | `BUILDING_READ` | rotation et emprise — **n'écrit rien** |
| `building.placement.place` | `BUILDING_PLACE` | écrit dans le monde, `confirm=true` exigé |
| `building.placement.rollback` | `BUILDING_ROLLBACK` | restaure la zone, `confirm=true` exigé |

**Aucune commande WorldEdit libre n'est envoyée par le navigateur** : le panel n'enfile que ces
actions, dont les paramètres sont validés deux fois — par le catalogue du panel, puis par l'agent.

`BUILDING_PLACE` et `BUILDING_ROLLBACK` sont **distinctes** de `BUILDING_WRITE` et l'une de l'autre,
parce que les risques sont de natures différentes : renommer une fiche ne change rien dans le jeu,
poser écrase des blocs réels, et restaurer écrase aussi ce qui a été ajouté depuis. Le **Builder**
consulte la bibliothèque mais ne pose pas.

#### Ce qui n'est pas fait, et volontairement

Aucune génération IA de bâtiment, aucune analyse d'image, aucun import de schematic depuis le panel,
aucun aperçu visuel en jeu de l'emprise, aucun versioning de bâtiment ni remplacement d'un bâtiment
posé. Le ticket les plaçait explicitement hors de ce lot.

### Rôles, permissions et comptes PlugAdmin (issue #50)

Le contrôle d'accès du Control Panel repose sur un modèle **utilisateur → rôle →
`Set<Permission>` → contrôle backend → interface filtrée**. Ces droits sont **propres à
PlugAdmin** : aucune correspondance automatique avec OP Minecraft, Paper ou LuckPerms. Un
`tester` PlugAdmin peut préparer une story par une action contrôlée sans jamais être OP en jeu.

- **Rôles** (`authz.Role`, libellé FR via `Role.label()`) :
  - `OWNER` — accès total (`EnumSet.allOf(Permission.class)` : toute permission ajoutée lui
    revient automatiquement). Seul à gérer les comptes (`USER_MANAGE`) et le module
    dev/déploiement (`DEV_MODULE`) par défaut.
  - `ADMIN` — exploitation serveur : joueurs (dont modération), PNJ, dialogues, diagnostics,
    contenu, actions admin (quest / story / variable / reset / item / reload) et le module
    **Exploitation serveur** complet (#95 : `OPS_VIEW`, `OPS_ANNOUNCE`, `OPS_RESTART`,
    `OPS_LOGS`). **Pas** de gestion des comptes ni du module dev.
  - `TESTER` — lectures utiles au test + `ACTION_QUEST` / `ACTION_STORY` / `ACTION_VARIABLE_GET`,
    plus l'**état serveur et la console** (`OPS_VIEW`, `OPS_LOGS`) pour comprendre ce qu'il
    observe en jeu. Pas d'annonce, pas de redémarrage, pas d'écriture de contenu, pas de reset,
    pas de modération.
  - `BUILDER` — documentation + infos PNJ / contenu. **Pas** de données joueurs, pas d'action
    serveur.
  - `CONTENT_EDITOR` — lecture + édition guidée quêtes / stories / dialogues / PNJ logiques,
    brouillons, validation, reload contenu. Pas de modération, pas de spawn.
  - `READ_ONLY` — lecture seule ; aucune permission détenue ne pilote une mutation. Voit l'état
    serveur (`OPS_VIEW`) mais **pas la console** (`OPS_LOGS`), plus bavarde : pseudos,
    coordonnées, erreurs internes.
- **Contrôle centralisé** : `PermissionService.can(roleName, Permission)` — **jamais** un test
  `if role == OWNER` dans un handler. Chaque route et chaque mutation vérifie la permission
  côté backend ; une requête directe sans droit renvoie un **403** cohérent (« Vous n'avez pas
  l'autorisation d'accéder à cette fonction. »). Masquer un bouton dans l'UI n'est jamais une
  sécurité.
- **Comptes** dans `control-panel.db`, table `panel_user` (migration additive idempotente ;
  aucun rapport avec `data.db` ni MariaDB #42). Mot de passe haché PBKDF2-HMAC-SHA256
  (`PasswordHasher`, 210k itérations) — jamais en clair, jamais réaffiché, jamais journalisé. Un
  compte peut être **actif ou désactivé** ; un compte désactivé ne peut plus se connecter et
  perd sa session en cours à la requête suivante. Un changement de rôle prend effet sans
  reconnexion.
- **Compte OWNER d'amorçage** : au démarrage, PlugAdmin garantit qu'un compte correspondant à
  `RPGQUEST_PANEL_OWNER_USERNAME` / `RPGQUEST_PANEL_OWNER_HASH` existe, est actif et `OWNER`
  (hash réaligné sur l'environnement). C'est le **chemin de récupération** : tant que ces
  variables sont définies, l'accès ne peut pas être perdu.
- **Page `/users`** (permission `USER_MANAGE`, motif liste compacte → clic → détail) : voir les
  comptes (identifiant, rôle, statut, dates), **créer** un compte (mot de passe ≥ 12 caractères,
  jamais réaffiché après création), **changer un rôle**, **activer / désactiver**. Pas de
  suppression (désactivation seulement). Formulaires par aller-retour serveur, **CSRF
  synchroniseur** sur chaque POST.
- **Protection du dernier OWNER** : impossible de retirer `OWNER` au dernier OWNER actif, de le
  désactiver, ou de désactiver son propre compte.
- **Audit** : `user.create`, `user.role.change` (`from=… to=…`), `user.active.change`, refus
  sensibles en `DENIED`, `login.failure` avec motif `compte désactivé`. Jamais de mot de passe
  ni de hash.

Détails et modèle de menace complet : [docs/control-panel/SECURITY.md](control-panel/SECURITY.md),
section « Rôles et permissions #50 ».

### Exploitation serveur depuis PlugAdmin (issue #95, lot 1)

Page **`/ops`** : état réel, annonce globale, redémarrage vérifié, console récente. Tout y est
**typé et whitelisté** — le navigateur ne transporte jamais de commande shell ni de commande RCON,
seulement des *opérations* dont le serveur décide le contenu. Permissions dédiées `OPS_VIEW`,
`OPS_ANNOUNCE`, `OPS_RESTART`, `OPS_LOGS`.

**État et fraîcheur** — les quatre cartes (vivacité de l'agent, joueurs, uptime, version) viennent
du **dernier heartbeat** (~20 s). La page affiche l'âge du relevé : un `ONLINE` vieux de 40 s
signifie « le serveur allait bien il y a 40 s », pas une mesure en direct. Aucun heartbeat reçu =
dit explicitement, jamais des zéros.

**Annonce globale** (`server.announce`) — trois canaux, et seulement ceux qui existent réellement
dans l'API publique : `chat` (`sendMessage`), `actionbar` (`sendActionBar`), `title`
(`showTitle`). Le message est envoyé en **texte littéral** : jamais exécuté comme commande, jamais
passé à MiniMessage. Un `<click:run_command:…>` dans une annonce ferait exécuter une commande à
tous les joueurs qui cliquent — cette porte reste fermée, et un message commençant par `/` est
refusé. Résultat structuré : destinataires **réels** et joueurs connectés ; `NO_PLAYERS` quand
personne n'était là, plutôt qu'un « envoyé » trompeur. 200 caractères, une ligne, audité.

**Redémarrage** — exécuté par **PlugAdmin en RCON depuis AWS**, pas par l'agent : un plugin ne peut
pas garantir son propre retour, puisque l'agent s'arrête avec le serveur. C'est le mécanisme déjà
éprouvé par `scripts/verygames-restart.sh` (arrêt RCON, relance automatique de l'hébergeur), porté
en Java parce que le service PlugAdmin tourne sous `systemd` avec `ProtectHome=true` et n'a accès ni
au `$HOME` de l'opérateur ni à son fichier d'identifiants. Les commandes émissibles sont une
**énumération de trois valeurs** (`list`, `save-all`, `stop`) : « aucune commande RCON arbitraire »
est une propriété **du type**, pas une promesse en commentaire.

**Un arrêt n'est pas un redémarrage.** Avant d'arrêter, on vérifie que le serveur répond — sinon
**aucun arrêt n'est demandé**. Après l'arrêt, deux preuves seulement sont acceptées : serveur **vu
hors ligne** puis répondant de nouveau, **ou** uptime du plugin **diminué** (relevé par le
heartbeat, ce qui couvre une relance plus rapide que l'intervalle de sonde). Sans preuve avant le
délai, l'opération finit en **échec** avec le motif. **Single-flight** : une seule opération à la
fois, ce qui est aussi la protection contre le double-clic et le rejeu. Confirmations **adaptées** :
case à cocher pour un différé (annulable), saisie du mot `REDEMARRER` pour un immédiat.

**Console récente** (`server.logs.tail`) — lecture seule, 500 lignes en tampon **circulaire**,
recherche, filtres `ERROR`/`WARN`/`INFO`, pause, suivi auto, retour en bas. Les lignes sont captées
par un **appender Log4j2** côté plugin (`ops.ConsoleTap`) : c'est la sortie réelle du serveur —
vanilla, Citizens, WorldEdit, Multiverse, RPGQuest. Un `Handler` `java.util.logging` ne suffirait
pas, car Paper route `getSLF4JLogger()` directement vers Log4j2 et la quasi-totalité de nos lignes
lui échapperait. `log4j-core` est `compileOnly` (fourni par le serveur) et une absence ou une
incompatibilité est **rattrapée** (`LinkageError`) : la console s'affiche « indisponible » avec son
motif, le serveur démarre normalement. Si le niveau du logger racine est plus restrictif que
`INFO`, c'est **dit** plutôt que de laisser croire à une console en panne.

**Transport** — aucun SSE, aucun WebSocket, aucun port entrant côté serveur de jeu : les lignes
remontent par l'**agent sortant** existant (scrutation ~15 s), et le panel n'enfile **qu'un** relevé
à la fois. La console est donc *récente*, pas instantanée, et la page l'écrit. Le ticket demandait
d'évaluer SSE : il aurait ajouté un second canal pour une donnée dont la fraîcheur reste de toute
façon bornée par l'agent.

**Fonctions affichées comme indisponibles, avec leur motif réel** : démarrage/arrêt explicite
(l'hébergeur n'expose aucune API de supervision) ; console complète de l'hébergeur (le fichier de
log n'est pas atteignable — racine FTP = `plugins/`, remontée de dossier refusée, mesuré) ;
sauvegarde restaurable (`save-all` n'est pas un point de restauration) ; mode maintenance et
annonces programmées. Rechargement du contenu (#131) et actions joueur / OP-DEOP (#210) sont
annoncés comme lots suivants, avec leur emplacement prévu.

**Limite assumée** : l'opération de redémarrage vit en mémoire ; si PlugAdmin redémarre, un
redémarrage *différé* est perdu et doit être reprogrammé. Rien n'est jamais exécuté en retard.

Fiche utilisateur : `/docs/exploitation-serveur`. Configuration : `ops.rcon.<cible>.host` / `.port`
dans `control-panel.properties`, mot de passe **uniquement** depuis l'environnement
(`RPGQUEST_RCON_PASSWORD_<CIBLE>`), jamais dans le dépôt.

### Rechargement du contenu dans le runtime (issue #131)

**Constat d'audit qui a tout décidé.** Les six registres (quêtes, stories, dialogues, PNJ, objets,
profils de mobs) savaient déjà se recharger, mais rien ne l'exposait : la permission
`ACTION_CONTENT_RELOAD` existait côté panel **sans aucune action derrière elle**, et la seule porte
réelle était `/rpgadmin mob reload` — une famille sur six. Surtout, `reload()` remplace l'ensemble
actif par les fichiers **valides** : un fichier devenu invalide ne provoque donc pas d'erreur, il
fait **disparaître sa définition du runtime en silence**. Une quête active pour des joueurs pouvait
s'évaporer d'un seul rechargement, depuis une commande déjà livrée.

**`content.reload.ContentReloadService` est désormais le seul point qui permute un ensemble actif.**
`/rpgadmin content preview|reload`, `/rpgadmin mob reload` (qui **délègue** maintenant) et les
actions agent `content.reload.preview` / `content.reload` n'en sont que des appelants — c'est
l'exigence du ticket d'éviter plusieurs implémentations divergentes.

**Séquence, et c'est elle qui protège :** dry-run de chaque famille (`validate()`, ajouté aux trois
registres qui n'en avaient pas) → une seule erreur de parsing **annule tout**, l'ancien runtime
valide reste en place → **validation des références croisées** sur le graphe *candidat* (dry-run
pour les familles rechargées, runtime courant pour les autres) → application dans l'**ordre de
dépendance** (objets, PNJ, quêtes, stories, dialogues, mobs) → relecture du runtime avec
**empreinte** (12 caractères de SHA-256 des identifiants triés) et durée.

**Contenu lié.** Quand une référence casserait, le service **nomme la famille à recharger
conjointement** : story→quête, dialogue→quête, PNJ→dialogue, quête→donneur. Le cas réel est une
quête et la story qui la cite, ajoutées ensemble — les stories seules échouent, les deux ensemble
réussissent. Les actions contextuelles des pages de contenu proposent donc d'emblée les bonnes
familles. À noter : le **chargeur de quêtes valide déjà les prérequis à l'échelle du dossier**, donc
une référence intra-famille est attrapée plus tôt (`INVALID_CONTENT`) ; le filet croisé couvre ce
qu'un chargeur de famille unique ne peut pas voir.

**Les trois états, enfin distinguables.** Le panel ne distinguait que source/runtime, ce qui confond
« jamais publié sur VeryGames » et « publié mais pas rechargé » — deux causes dont **une seule** se
répare par un rechargement. L'aperçu lit le **disque du serveur** et renvoie les identifiants
trouvés, ce qui permet au panel de trancher. La page énonce les trois états et écrit noir sur blanc
qu'un **rechargement ne transfère rien depuis AWS**.

**Jamais touché** : progression, quêtes actives, inventaires, sessions de dialogue (elles ne
stockent que des identifiants et sont re-résolues défensivement — vérifié, non modifié), et
**instances de mobs/boss vivantes**. Recharger un profil change ce qui apparaîtra ensuite, pas ce
qui est déjà dans le monde. Aucune récompense redistribuée, aucun respawn, aucun reset, **aucun
`/reload` Bukkit**. `NpcHintService#invalidateAll` est appelé après application pour que le signal
visuel (#12) ne survive pas à la disparition de sa quête. **Single-flight** : un second
rechargement est refusé, pas sérialisé ; un aperçu reste autorisé puisqu'il ne touche rien.

**`config.yml` n'est pas rechargeable de façon fiable** : plusieurs valeurs sont lues une seule fois
au démarrage et capturées par les services. Un rechargement partiel serait le « succès ambigu » que
le ticket interdit — le panel dit donc **redémarrage requis** et renvoie au workflow #95.

### Publier du contenu sur DEV sans rebuild — `content.publish` (issue #47)

**Le verrou, et ce qu'il coûtait.** Une quête créée depuis PlugAdmin était bien écrite dans la
source éditable (côté AWS), mais restait « Source uniquement » tant qu'un script de déploiement
externe n'avait pas copié son YAML sur le serveur. Un rechargement ne pouvait rien charger, puisque
le fichier n'existait pas. Autrement dit : créer du contenu depuis le panel exigeait de connaître et
de lancer le pipeline de déploiement — ou de passer par quelqu'un qui le connaissait.

#### L'audit a réduit le périmètre, et c'est le résultat le plus utile

Le ticket énumérait huit familles. L'audit en a trouvé **deux** réellement bloquées :

| Famille | Source côté panel | Écriture côté serveur avant #47 | Verdict |
|---|---|---|---|
| **Quêtes** | fichier de l'espace de travail | **aucune** | vrai manque |
| **Stories** | fichier de l'espace de travail | **aucune** | vrai manque |
| **Dialogues** | fichier de l'espace de travail | partielle (l'éditeur guidé écrit côté serveur) | le chemin « fichier » manquait |
| PNJ | — (store côté serveur) | oui | **déjà appliqué au runtime** |
| Mobs spéciaux / Boss | — (store côté serveur) | oui | **déjà appliqué au runtime** |
| Objets / Recettes | — | dépôt d'exemples embarqués uniquement | **rien ne les crée depuis le panel** |

`ContentWorkspace.KINDS` vaut exactement `{quests, stories, dialogues}`, et ce n'est pas une
coïncidence : ce sont les seules familles que le panel écrit comme fichiers. La liste blanche de
publication et le manque réel coïncident donc.

> **Conséquence assumée.** Les PNJ et les mobs ne sont pas « oubliés » : les forcer à passer par une
> copie de fichier inventerait un second chemin pour un problème que des actions runtime résolvent
> déjà. Et pour les objets et les recettes, il n'y a rien à publier — il manque un **éditeur**, pas
> un transfert. Le dire vaut mieux que livrer une publication qui n'aurait aucune source.

#### L'agent est sortant, donc le serveur écrit lui-même

C'est la contrainte qui a décidé de l'architecture. Le plugin **interroge** le panel ; le panel ne
peut donc pas pousser un fichier. Le YAML voyage dans un **paramètre d'action**, et c'est le serveur
qui l'écrit dans un dossier issu d'une liste blanche.

Ce n'est pas seulement plus simple que du FTP, c'est plus sûr : aucune connexion entrante vers le
serveur de jeu, **aucun identifiant FTP côté panel**, et le navigateur n'envoie ni chemin ni contenu.

> **Le navigateur envoie quatre choses** : une famille, un identifiant, l'empreinte de la source
> qu'il a vue et l'empreinte DEV qu'il a vue. Rien d'autre. C'est le **panel** qui lit la source et
> joint le YAML au moment d'enfiler l'action, côté serveur — donc un formulaire forgé ne peut pas
> publier un contenu fabriqué, et n'a jamais besoin de connaître un chemin.

#### `PublishKind` *est* la liste blanche

Trois familles, et le reste est hors d'atteinte **par construction** plutôt que par une liste
d'interdits qu'on aurait pu oublier de compléter : `data.db`, les mondes, les secrets, les données
Citizens brutes, le JAR et toute configuration inconnue n'ont simplement aucune entrée.

La résolution d'un chemin passe par **trois verrous successifs** : l'énumération, un motif
d'identifiant sans séparateur, et une vérification de confinement **après** normalisation. Le
troisième ne devrait jamais servir — et c'est précisément pour cela qu'il est là.

Les sauvegardes vont sous `content-backups/<horodatage>/<famille>/`, **hors** des dossiers de
contenu. Déposées dans `quests/`, elles seraient relues comme des définitions au prochain
rechargement et produiraient des doublons d'identifiants. C'est la règle déjà posée par #194.

#### « Fichier copié » n'est pas un succès

Trois raccourcis sont interdits, et chacun a son code de sortie :

| Jusqu'où on est allé | Code | Ce que le message dit |
|---|---|---|
| le fichier est écrit, le rechargement a échoué | `RELOAD_FAILED` | que le fichier **est** écrit, et où est la sauvegarde |
| rechargé, mais le moteur ignore l'identifiant | `RUNTIME_MISSING` | de vérifier que l'identifiant déclaré correspond au nom du fichier |
| le moteur confirme | `PUBLISHED` | et **seulement là**, « Synchronisé » |

La vérification repose sur `ContentReloadService#loadedIds` / `runtimeHas`, qui énumèrent les
identifiants réellement portés par chaque registre. Sans elles, « reload demandé » devrait être pris
pour « reload réussi ».

> **Pourquoi l'honnêteté des échecs compte autant que les succès.** Un échec de rechargement laisse
> le fichier sur le serveur. Annoncer « rien n'a été fait » serait pire que l'échec lui-même :
> l'administrateur chercherait au mauvais endroit.

#### L'ordre des opérations

1. valider la famille et l'identifiant — une entrée forgée n'atteint jamais le disque ;
2. comparer l'état DEV à celui que l'appelant croyait voir ; un désaccord est un **conflit**, jamais
   un écrasement silencieux ;
3. **sauvegarder** si un fichier existe. Échec ⇒ on s'arrête : publier sans pouvoir revenir en
   arrière n'est pas acceptable ;
4. écrire de façon **atomique** (fichier temporaire puis `move`) — un plantage ne laisse jamais un
   YAML tronqué, qui ferait échouer le chargement de toute la famille ;
5. recharger **la seule famille concernée** ;
6. relire le runtime.

Un verrou **par ressource** (`inFlight`) empêche deux publications de la même ressource de
s'entrelacer ; deux ressources différentes ne se bloquent pas, rien ne le justifierait.

#### Le retour arrière dit ce qu'il fait

Deux cas, et un seul est une restauration : si une sauvegarde existe, on la repose ; si la ressource
était **nouvelle**, il n'y a rien à restaurer et le seul retour arrière honnête est de **retirer** le
fichier. Le bouton porte donc deux libellés différents, et il n'apparaît pas du tout quand aucune
publication n'a eu lieu depuis le panel.

#### `PublishState` : un vocabulaire, et un mensonge corrigé

Avant #47, `SYNCED` signifiait seulement « présent des deux côtés ». Une quête modifiée dans la
source mais jamais republiée s'affichait donc **« Synchronisé »**, ce qui était faux et invisible.

L'état est désormais une fonction **pure** de (empreinte source, empreinte DEV, chargé par le
moteur), partagée par toutes les pages — une seule fonction, donc un seul vocabulaire. Deux états
manquaient : `DIFFERENT`, et `NOT_LOADED` (« le fichier est là, le moteur ne le charge pas »), que
l'ancien modèle ne savait pas exprimer. Chaque état porte son code stable, son libellé, son
explication **et** l'action possible : un état sans action laisse l'utilisateur bloqué devant un
badge.

`CONFLICT` se distingue de `DIFFERENT` grâce à l'empreinte DEV laissée par **notre** dernière
publication : si le fichier n'est ni la source ni ce que nous y avions mis, quelqu'un d'autre y a
touché.

Sans relevé du serveur, l'état est `UNKNOWN` et **aucun bouton Publier n'apparaît**. Afficher
« Source uniquement » sans avoir interrogé DEV serait exactement le genre d'affirmation gratuite que
ce ticket corrige.

#### Permissions

`CONTENT_PUBLISH` et `CONTENT_ROLLBACK` sont **dédiées**, et l'**Éditeur de contenu ne les a pas** :
enregistrer dans la source est réversible et sans effet sur le jeu ; changer ce qui tourne sur le
serveur de test ne l'est pas de la même façon. Les deux sont également distinctes l'une de l'autre,
parce qu'un retour arrière réécrit lui aussi l'état du serveur.

#### Dialogues : la publication ne normalise rien

Publier copie le fichier **tel quel** ; le transfert ne passe jamais par `MiniYaml`. Il ne peut donc
pas reformater un scalaire replié au passage. La dette connue sur ce point (`guard.yml`) appartient à
l'**éditeur guidé**, qui réécrit le YAML — pas au transfert.

#### Ce que la publication n'est pas

Ni un commit, ni une fusion, ni une construction du JAR, ni une release. Une copie contrôlée d'un
fichier de contenu vers le serveur de test, auditée, et réversible.

### OP/DEOP et actions de secours sur un joueur (issue #210)

Sur la fiche `/players` : statut **OP réel** (relu du serveur, donc un OP accordé en jeu y apparaît),
boutons OP/DEOP, **renvoi au Hub**, **expulsion** avec raison, et **whitelist**.

**Quatre notions distinctes, et l'action n'en touche qu'une.** OP Minecraft n'est ni le rôle
PlugAdmin (`/users`), ni le droit de construction par monde (#200), ni le bypass de gameplay (#35).
Accorder OP ne modifie aucun des trois, et le résultat le rappelle à l'administrateur.

**Permission dédiée `PLAYER_OP_WRITE`, la plus restreinte du panel : `OWNER` uniquement, pas
`ADMIN`.** Le ticket l'exige — ne pas accorder ce droit implicitement à tout administrateur. Kick,
whitelist et renvoi au Hub réutilisent `PLAYER_MODERATE` comme demandé, sans inflation de
permissions.

**Garde-fous de l'élévation OP** : raison **obligatoire**, et il faut **retaper le pseudo exact** —
un clic sur la mauvaise fiche ne peut donc pas élever le mauvais compte. L'opération est
**idempotente** (`ALREADY_OP` / `NOT_OP` au rejeu) et l'état est **relu** après écriture : si
l'écriture n'a pas pris, le résultat est `NOT_APPLIED` avec la valeur relue, jamais un « OP
accordé » mensonger.

**Renvoi au Hub** : même mécanisme que la Pierre de retour (`spawnService.resolve()`, jamais une
coordonnée figée). **Inventaire, Acte, claim et progression préservés**, aucun reset. Refusé
clairement si le joueur est hors ligne ou si la destination ne se résout pas.

**Whitelist** : fonctionne hors ligne, et le résultat précise si la whitelist est **réellement
appliquée** — l'ajouter alors qu'elle est désactivée ne protège rien. Ban et unban ne sont pas
dupliqués. Une action impossible hors ligne est **affichée avec son motif**, jamais silencieusement
inopérante. Cible toujours par **UUID**.

### Monnaie : source de vérité et administration (issues #16/#140, premier lot)

**Constat d'audit : le socle n'était pas manquant.** `economy.EconomyService` et
`database.WalletRepository` existaient déjà avec les tables `wallets` **et** `transactions`, des
transactions JDBC explicites (solde + ligne de journal dans la **même** transaction SQL), un débit
qui **refuse** de passer négatif, un `pay` atomique et dix `TransactionType`. Marchands et marché
l'utilisent. La consigne appliquée a donc été de **compléter la fiabilité et l'administration**, pas
de recréer.

**Source de vérité, tranchée explicitement : le portefeuille persistant.** La table `transactions`
en est le journal. Aucun objet d'inventaire n'est consulté ni interprété comme de la monnaie, et
**aucune monnaie n'est reconnue par son nom ou son lore**.

**Une monnaie physique (#138) introduirait une seconde source de vérité.** Le lien entre les deux —
taux, sens de conversion, perte à la mort, fabricable ou non, comportement au drop — est une
**décision de gameplay** et n'est donc pas prise. Rien de ce lot ne convertit ni ne migre quoi que
ce soit : aucun solde existant, aucun inventaire n'est touché.

**Ce que ce lot ajoute.** Le journal était **écrit sans être lisible** : aucune méthode ne le
consultait, donc la traçabilité existait sans être exploitable. `WalletRepository#history` (lecture
seule, bornée, asynchrone) la rend consultable, et trois actions agent whitelistées l'exposent :
`economy.balance` (lecture, permission `ECONOMY_READ`), `economy.credit` et `economy.debit`
(mutations **sensibles**, permission `ECONOMY_WRITE`).

**Deux permissions, parce que les gestes diffèrent** : voir combien possède un joueur sert au
support ; lui en créer n'est pas le même acte. `TESTER` et `READ_ONLY` lisent sans pouvoir créer ;
`BUILDER` et `CONTENT_EDITOR` n'ont aucun accès.

**Garanties.** Montant entier **strictement positif**, plafonné par opération — garde-fou de
**saisie** contre une faute de frappe à six zéros, pas une règle d'équilibrage. **Raison
obligatoire**, enregistrée dans le journal : sans elle, une création administrative serait
indiscernable d'un gain de jeu quelques mois plus tard. Un débit au-delà du disponible est un
**refus métier lisible** (`INSUFFICIENT_FUNDS`) qui ne laisse **aucune ligne** au journal et ne
modifie rien. Le solde est **relu** après opération et le résultat affiche avant → après.
Confirmation explicite, audit, et ré-enfilement de l'annuaire pour que le solde affiché soit relu.

**Décisions de gameplay délibérément non prises** : montants des récompenses, prix marchands, coûts
artisans, perte à la mort, existence et règles d'une monnaie physique, plafond de solde. À noter
aussi : `RewardType` n'a **pas** de type monnaie, donc une quête ne peut pas encore créditer — c'est
un manque identifié, pas un oubli silencieux.

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
`VARIABLE_EQUALS` (`key`, `value`), `NO_MAIN_CLAIM` (aucun paramètre — vrai
si le joueur ne possède encore aucun claim, source de vérité directement
`claim.ClaimService#claimsOwnedBy`, voir [docs/CLAIMS.md](CLAIMS.md)),
`HAS_PENDING_DELIVERY` (`npc` optionnel — vrai s'il reste au moins un objet
à remettre à ce PNJ pour une quête active, issue #123 ; avec
`negate: true`, exprime « ce PNJ n'attend plus rien » et sert à basculer
vers un nœud de fin),
`HAS_MAIN_CLAIM` (aucun paramètre — strict opposé de `NO_MAIN_CLAIM`, même
source de vérité via `ClaimService#mainClaimOf`, utilisé par Jo pour « Me
rendre sur ma propriété »), `LACKS_CUSTOM_ITEM` (`item` — id namespacé,
identifié par PDC via `item.YamlCustomItemRegistry#identify`, jamais par
matériau seul contrairement à `HAS_ITEM` — vrai si le joueur ne possède
aucun exemplaire ; utilisé par Jo pour ne jamais permettre de farmer
l'Acte réutilisé comme visualiseur ou la Pierre de retour, voir
[docs/CLAIMS.md](CLAIMS.md)).
`negate: true` sur **n'importe quelle** condition inverse son verdict
(`dialogue.model.NegatedCondition`, double négation refusée au chargement) —
sert notamment à exprimer « le déblocage n'a *pas* eu lieu » :
`VARIABLE_EQUALS key: CLAIM_TIER_1 value: "true"` + `negate: true` est vrai
tant que la variable n'est pas `"true"` (valeur absente incluse), utilisé
par `dialogues/jo.yml` pour l'état « claim non débloqué ».
Toutes les conditions d'un choix doivent être vraies (ET). Revérifiées au
clic, pas seulement à l'affichage.

**Actions** (`choices[].actions[].type`) : `START_QUEST`, `ADVANCE_QUEST`,
`TURN_IN_QUEST` (champ `quest`) ; `GIVE_ITEM`/`TAKE_ITEM` (`material`,
`amount`) ; `SET_VARIABLE` (`key`, `value`) ; `RUN_SAFE_COMMAND`
(`command` — le **nom** de la commande doit être dans `config.yml` →
`dialogue.allowed-commands`, vérifié au chargement) ; `OPEN_DIALOGUE`
(`dialogue`) ; `OPEN_MERCHANT` (`merchant`, voir section 12) ;
`GIVE_STARTER_KIT` (aucun paramètre — remet le kit d'outils en bois de
départ si le joueur y a droit, voir « Kit d'outils en bois » ci-dessous) ;
`DELIVER_QUEST_ITEMS` (`npc` **optionnel** — remet en une fois tous les
objets utiles aux objectifs `DELIVER_ITEM_TO_NPC` de ce PNJ, issue #123 ;
voir « Remise d'objets à un PNJ » en section 3) ;
`CLOSE`. `OPEN_DIALOGUE`/`CLOSE` prennent le pas sur `next`.

**PNJ implicite** (issue #123) : `DELIVER_QUEST_ITEMS` et la condition
`HAS_PENDING_DELIVERY` acceptent un `npc` vide, auquel cas le destinataire
est la **clé du dialogue courant** (`rpgquest:guard` → PNJ `guard`, voir
« Convention id dialogue ↔ id PNJ »). C'est ce qui rend la branche de
remise générique : recopiée dans le dialogue d'un autre PNJ, elle remet à
cet autre PNJ sans qu'aucune donnée ne nomme qui que ce soit. Un `npc`
explicite reste possible pour un dialogue partagé, ou ouvert par
`OPEN_DIALOGUE` depuis un PNJ différent du destinataire.

### Kit d'outils en bois (issue #26, partie A)

`dialogues/guide.yml` propose « Demander mon kit de départ » (action
`GIVE_STARTER_KIT`, sans condition de dialogue — toujours affiché). Géré
entièrement par `player.StarterToolKitService`, jamais le moteur de
dialogue lui-même :

- **Aucune remise automatique** : ni clic simple sur un PNJ, ni connexion,
  ni réapparition — seule cette demande explicite déclenche quoi que ce
  soit.
- **Contenu** : un exemplaire de chaque matériau de `config.yml` →
  `starter-tool-kit.items` (par défaut `WOODEN_SWORD`/`WOODEN_PICKAXE`/
  `WOODEN_SHOVEL`/`WOODEN_AXE`) — configurable, jamais codé en dur dans le
  dialogue.
- **Droit renouvelé à chaque mort** : persisté par joueur (variable
  `player_variables` → `STARTER_TOOL_KIT_AVAILABLE`, absente = droit
  disponible — un nouveau joueur l'a donc dès le début). Une remise
  réussie le consomme (`"false"`) ; chaque mort le restaure (`"true"`),
  y compris une mort avant toute première remise (droit initial jamais
  perdu). Aucune limite totale de récupérations après des morts
  successives.
- **Tout ou rien** : les emplacements libres du stockage normal (`
  PlayerInventory#getStorageContents`, donc hors armure/main secondaire)
  sont comptés **avant** toute écriture ; en dessous du nombre d'objets du
  kit, aucun objet n'est distribué, rien n'est jeté/remplacé, et le droit
  n'est jamais consommé (message : « Tu n'as pas assez de place dans ton
  inventaire. Libère 4 emplacements pour recevoir ton kit de départ. »).
  Déjà reçu depuis la dernière mort → message dédié, aucune remise.
- **Anti double-clic** : un ensemble en mémoire (`Set<UUID>`) empêche deux
  demandes concurrentes du même joueur de passer toutes les deux la
  vérification avant que l'une n'ait consommé le droit.
- **`/rpgadmin player resetnew`** restaure le droit initial gratuitement
  (`PlayerResetService` efface déjà **toutes** les variables du joueur —
  aucun code dédié au kit n'était nécessaire).
- Distinct de `player.StarterKitListener` (Rune de rappel, remise unique
  à vie, automatique à la connexion) — même motif de nommage « kit de
  départ », mécanismes et objets totalement indépendants.

### Valeurs dynamiques dans le texte d'un nœud (issue #24)

Le `text` d'un nœud peut contenir un marqueur `%clé%`, remplacé **juste
avant l'affichage** par une valeur lue en direct — même convention que
`%player%` de `RUN_SAFE_COMMAND`, jamais une balise MiniMessage.
Implémentation : `dialogue.DialogueTextPlaceholders`, appelée par
`DialogueSessionEngine#openNode` (le seul point de rendu).

| Clé | Valeur substituée |
|---|---|
| `%delivery_status%` | Issue #123 — état de remise du PNJ **porteur du dialogue courant** : une ligne par matériau, `déjà remis/demandé`, nom d'objet traduit côté client. « Je n'attends aucun matériau de ta part. » si ce PNJ n'attend rien. Lecture pure de la progression en mémoire. |
| `%wild_conditions%` | État réel du monde `travel.wild-world` : « Il fait jour dans le Wild. Le temps est clair. », « Il fait nuit dans le Wild. Il pleut. », « Il fait nuit dans le Wild. Un orage est en cours. », ou « Je n'ai pas de nouvelles du Wild pour le moment. » si le monde n'est pas chargé. Lecture seule (`travel.WildConditionsService`) : ni l'heure, ni la météo, ni le cycle jour/nuit du Wild ne sont modifiés. |

Règles : une clé **non enregistrée** est laissée visible telle quelle (une
faute de frappe se voit en jeu plutôt que d'effacer du texte) ; une seule
passe de substitution, donc une valeur contenant elle-même un `%` n'est
jamais re-substituée ; toutes les traductions du texte sont traitées, pas
seulement `default`. Un nœud sans `%` n'est jamais copié (coût nul).

Utilisation livrée : `dialogues/guard.yml`, choix **permanent** (aucune
condition, aucune action) « Comment est le Wild actuellement ? » → nœud
`wild_conditions` dont le texte vaut `%wild_conditions%`. Le joueur obtient
donc l'information **sans commande**, et uniquement quand il la demande —
elle n'est jamais injectée dans l'avertissement du portail (voir
section 6). Ajouter plus tard un niveau de danger ou un événement actif =
un champ de plus dans `travel.model.WildConditions`, rien à changer dans le
moteur de dialogue.

**Renderer** : `config.yml` → `dialogue.renderer`, défaut réel
**`paper-dialog`** (API Dialog native Paper, marquée expérimentale par
Paper) ; alternative `chat` (liens cliquables `ClickEvent.callback`,
compatible tout client, aucune API instable). ⚠️ Divergence constatée :
`README.md` (racine) affirme à tort que `chat` est la valeur par défaut —
c'est `paper-dialog` dans le `config.yml` généré réellement ; `docs-site/dialogues.html` a la bonne valeur.

### Paliers du kit de départ (issue #218)

Le kit de #26 devient le **palier 1** d'une progression. Une quête par montée de palier améliore le
kit que le joueur récupère auprès du Guide après une mort. Implémentation :
`player.StarterToolKitService` + `config.yml` → `starter-tool-kit.tiers`.

-   **Palier 1 acquis d'office** (aucune quête) : `WOODEN_SWORD`, `WOODEN_PICKAXE`, `WOODEN_SHOVEL`,
    `WOODEN_AXE`.
-   **Palier 2** (`rpgquest:kit_tier2`) : `STONE_SWORD`, `WOODEN_PICKAXE`, `WOODEN_SHOVEL`,
    `WOODEN_AXE`, `LEATHER_BOOTS`, `BREAD`. Sa quête demande **1 `STICK` + 2 `COBBLESTONE`**
    (recette de l'épée en pierre), **4 `LEATHER`** (recette des bottes) et **3 `WHEAT_SEEDS`**.
    ⚠️ Les **graines** sont une décision de gameplay assumée, pas une erreur : produire du blé
    suppose déjà un endroit sécurisé pour cultiver, alors que des graines se trouvent immédiatement
    dans le Wild. **Ne pas « corriger » en `WHEAT`.**
-   **Paliers 3 à 5** : l'architecture les accepte (il suffit de les déclarer en configuration), mais
    leur contenu exact **n'est pas décidé** et n'a donc pas été inventé.
-   **Palier persistant** : variable joueur `STARTER_KIT_TIER` (absente = palier 1). Survit à la
    mort, à la reconnexion et au redémarrage. `/rpgadmin player resetnew` la remet à zéro avec les
    autres variables.
-   **Impossible de sauter un palier** : le déblocage passe par
    `/rpgadmin kit grant-tier <joueur> <niveau>`, appelé en **récompense `COMMAND`** de la quête du
    palier. Le moteur refuse tout niveau non contigu (`SKIPPED`), tout niveau non défini
    (`UNKNOWN_TIER`), et ne fait rien si le palier est déjà atteint (`ALREADY_AT_LEAST`, donc une
    quête rejouée ne redonne rien). **Ne jamais débloquer en écrivant la variable** : ce serait
    contourner exactement ce garde-fou.
-   **Niveaux contigus exigés en configuration** : un trou (1, 2, 4) ferait du palier 4 une
    impasse — le plugin refuse de démarrer et le dit.
-   **Emplacements requis calculés sur le contenu réel** du palier (6 pour le palier 2, pas 4), et le
    message au joueur annonce le bon chiffre. Remise toujours **tout ou rien**.
-   **Une seule remise réussie par vie**, inchangé depuis #26 : monter de palier au milieu d'une vie
    ne réouvre pas le droit ; c'est la mort suivante qui le fait, et le kit servi est alors celui du
    nouveau palier.
-   **Matériaux déjà remis** pour la quête du palier : sécurisés par `DELIVER_ITEM_TO_NPC` (#123),
    indépendamment du kit — une mort ne les perd jamais.
-   **Valeur aberrante en base** (palier retiré de la configuration, variable éditée à la main) :
    le meilleur palier **défini** est servi, jamais une absence de kit.
-   `/rpgadmin kit status <joueur>` affiche le palier et son contenu. Côté Control Panel, la variable
    `STARTER_KIT_TIER` est proposée par l'outil « Lire une variable » de la fiche joueur.

### Signal visuel sur les PNJ (issue #12, première version)

Particules **discrètes** au-dessus d'un PNJ quand une **quête est réellement disponible** pour
**ce** joueur, ou qu'un **dialogue accessible comporte un nœud jamais lu**.

**Propre à chaque joueur.** Les particules partent par `Player#spawnParticle` : seul le
destinataire les voit. Deux joueurs devant le même PNJ voient donc des états différents, et le PNJ
lui-même n'est **jamais** modifié — ni son nom, ni son équipement, ni rien de global. Rendu par un
client **vanilla**, compatible Citizens (le signal suit l'entité, quelle qu'elle soit, dès qu'elle
porte un id RPGQuest).

| Signal | Signification | Particule par défaut |
|---|---|---|
| Quête | une quête dont ce PNJ est le donneur est **réellement disponible** | `HAPPY_VILLAGER` |
| Dialogue | un nœud **atteignable** du dialogue de ce PNJ n'a **jamais** été lu | `ENCHANT` |

La **quête prime** sur le dialogue si les deux s'appliquent au même PNJ.

#### Aucune règle dupliquée

- **Disponibilité d'une quête** : `QuestProgressEngine#availability` — exactement ce que
  `accept()` utilise pour refuser. Une quête verrouillée, déjà active, ou terminée et non
  répétable **ne peut pas** être annoncée comme nouvelle, parce qu'il n'existe qu'une seule
  implémentation de ces règles.
- **Conditions de dialogue** : `DialogueSessionEngine#reachableNodes` parcourt le graphe depuis le
  nœud de départ en suivant **uniquement** les choix dont les conditions passent, via l'évaluateur
  du moteur de dialogue. Un choix qui ferme le dialogue n'est pas suivi.

#### Ce que « lu » veut dire

Un nœud est marqué lu **au moment où il est réellement affiché**, et nulle part ailleurs —
`DialogueSessionEngine#openNode` est le seul point de rendu. Conséquences voulues :

- **ouvrir un PNJ ne marque pas toutes ses branches** : seul le nœud de départ est enregistré ;
- une branche **nouvellement débloquée** reste signalée jusqu'à ce qu'elle soit lue pour de vrai ;
- l'état est persistant par **UUID** (table `dialogue_node_reads`, migration V24) : il survit à une
  reconnexion comme à un redémarrage, et un changement de pseudo ne le perd pas.

#### Coût maîtrisé

- **Jamais de travail à chaque tick** : une passe toutes les `period-ticks` (1 s par défaut), et
  **par joueur connecté** — jamais un balayage de tous les PNJ du monde.
- **Jamais de PNJ distant** : les candidats viennent de `getNearbyEntities` dans le `radius`
  configuré. Hors rayon, dans un autre monde, non chargé ou hors ligne de vue : rien n'est calculé
  ni envoyé, et **aucun chunk n'est chargé** pour l'occasion.
- **Calcul découplé de l'affichage** : l'état d'un joueur est recalculé au plus une fois par
  `refresh-seconds`, en asynchrone, et l'affichage lit un cache. Le cache est invalidé
  **immédiatement** quand quelque chose change (quête acceptée, progression, nœud lu, changement de
  monde), pour que le signal suive sans attendre. Un seul recalcul à la fois par joueur.
- Nettoyage à la déconnexion et à l'arrêt du service.

#### Réglages — section `npc-hints:` de `config.yml`

| Clé | Défaut | Bornes | Effet |
|---|---|---|---|
| `enabled` | `true` | — | active le signal |
| `period-ticks` | `20` | 10–100 | période de la passe d'affichage |
| `radius` | `16.0` | 4–48 | distance maximale, en blocs |
| `require-line-of-sight` | `true` | — | pas de signal à travers un mur |
| `refresh-seconds` | `5` | 1–60 | intervalle minimal entre deux recalculs |
| `height-offset` | `2.2` | 0–3 | hauteur au-dessus du PNJ |
| `quest-particle` | `HAPPY_VILLAGER` | nom valide | convention « quête » |
| `dialogue-particle` | `ENCHANT` | nom valide | convention « dialogue » |
| `count` | `1` | 1–10 | particules par passe |

Les valeurs numériques hors bornes sont **corrigées** au chargement : un signal visuel ne doit pas
empêcher le serveur de démarrer. Une **particule inconnue**, en revanche, est **refusée** au
démarrage — c'est une faute de frappe qu'il faut voir.

#### Hors de cette première version

**« Quête prête à être rendue » n'est pas signalé**, et ce n'est pas un oubli :
`QuestState.READY_TO_TURN_IN` existe dans l'énumération mais n'est **jamais un état observable** —
`QuestProgressEngine#turnIn` le pose puis le remplace par `COMPLETED` dans la même méthode, et
seul `COMPLETED` est persisté. Le signaler supposerait d'inventer une mécanique de remise, ce que
le ticket interdit. À reprendre si un véritable état « à rendre » est introduit.

Également hors périmètre : halo ou icône au-dessus du PNJ (demanderait un affichage serveur
supplémentaire), et signal pour un dialogue atteint autrement que par le PNJ lui-même.

### Convention id dialogue ↔ id PNJ

Cliquer sur un PNJ identifié `X` (voir section 5) ouvre automatiquement le
dialogue `id: rpgquest:X` s'il existe — aucune configuration
supplémentaire.

### Vocabulaire des actions et des conditions, et sa validation (issue #146)

Le vocabulaire d'un choix de dialogue ne vivait que dans les énumérations `ActionType` et
`ConditionType` du moteur. Le Control Panel, qui ne dépend pas du module du plugin, ne pouvait donc
ni le contraindre, ni le documenter, ni le fournir à une IA. Il est désormais **déclaré** dans
`Descriptors` — **12 actions** et **8 conditions** — et **verrouillé sur le moteur** par
`DialogueDescriptorsTest`, qui compare les ensembles à l'identique : une action ajoutée ou retirée
côté moteur fait échouer la suite jusqu'à ce que quelqu'un décide quoi en faire.

| Actions | Conditions |
|---|---|
| `START_QUEST`, `ADVANCE_QUEST`, `TURN_IN_QUEST` (`quest`) | `QUEST_STATE` (`quest`, `state`) |
| `GIVE_ITEM`, `TAKE_ITEM` (`material`, `amount`) | `HAS_ITEM` (`material`, `amount`) |
| `SET_VARIABLE` (`key`, `value` facultatif) | `VARIABLE_EQUALS` (`key`, `value` facultatif) |
| `RUN_SAFE_COMMAND` (`command`, liste blanche) | `HAS_PERMISSION` (`permission`) |
| `OPEN_DIALOGUE` (`dialogue`), `OPEN_MERCHANT` (`merchant`) | `NO_MAIN_CLAIM`, `HAS_MAIN_CLAIM` |
| `GIVE_STARTER_KIT`, `CLOSE` (aucun champ) | `LACKS_CUSTOM_ITEM` (`item`) |
| `DELIVER_QUEST_ITEMS` (`npc` facultatif) | `HAS_PENDING_DELIVERY` (`npc` facultatif) |

`negate: true` inverse n'importe quelle condition : c'est le **seul** champ commun à toutes, et il
n'a aucun sens sur une action. `state` accepte les **six** états de `QuestState` : `NOT_STARTED`,
`ACTIVE`, `READY_TO_TURN_IN`, `COMPLETED`, `FAILED`, `ABANDONED`.

**Ce que le panel refuse désormais à l'enregistrement** (`DialogueValidator`) : un type d'action ou
de condition inconnu, un champ obligatoire absent, un champ appartenant à un autre type, un entier
non positif, un état de quête inexistant. Jusqu'ici une action inventée traversait l'éditeur **et**
l'import sans un mot et n'échouait qu'au chargement du serveur Minecraft — c'est-à-dire longtemps
après le clic sur « enregistrer », dans un journal que personne ne lit à ce moment-là. Le moteur
reste l'autorité finale ; ce qui est vérifié ici, c'est ce que le panel est capable de savoir.

**Forme des nœuds.** `nodes` est une **map indexée par identifiant de nœud**, jamais une liste :
l'identifiant est la clé et ne se répète pas à l'intérieur du nœud. C'est la forme que le moteur lit
et que l'export du plugin écrit ; une liste est refusée à l'import. Le schéma de content pack de
#110 l'annonçait à tort comme une liste, si bien que son propre exemple de référence était
inimportable — corrigé, et désormais couvert par un test qui fait passer les exemples du contrat par
un vrai import.

**Limite du lecteur YAML du panel** : `MiniYaml` ne gère pas les scalaires repliés (`text: >`) et
abandonne la suite de la map. `dialogues/guard.yml` en contient : six de ses treize nœuds ne sont
donc pas vus par le panel. La troncature n'est pas silencieuse — le garde-fou round-trip la signale
et l'éditeur refuse d'écraser à l'aveugle, ce qu'un test vérifie fichier par fichier — mais ce
dialogue n'est pas éditable depuis le panel tant que le lecteur ne gère pas cette construction.

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

### Couleurs et styles sans écrire de MiniMessage (issue #195)

**Composant partagé du Control Panel.** Colorer un texte imposait auparavant de taper du
MiniMessage à la main (`<red>Roi des Marais</red>`). Les champs de texte destinés aux joueurs
offrent désormais : **palette de 16 couleurs nommées au clic** plus « ∅ aucune couleur », cases
**Gras / Italique / Souligné / Barré**, **aperçu** du résultat, et saisie en **texte simple**.
Aucun code à connaître pour un usage courant.

**Audit complet des champs de texte destinés aux joueurs** (toutes les pages du panel) :

| Page / champ | État |
|---|---|
| `/mobs` — nom affiché d'un mob spécial ou d'un boss (création et modification) | composant guidé |
| `/dialogues` — texte d'un nœud, texte d'un choix, nouveau nœud (éditeur guidé) | composant guidé |
| `/npcs` — nom du PNJ (définition RPGQuest) | composant guidé |
| `/quests` — **titre** affiché d'une quête | composant guidé |
| `/quests` — **description** d'une quête (multiligne) | composant guidé |
| `/stories` — **nom affiché** d'une story | composant guidé |
| `/dialogues/new`, `/dialogues/edit` — **réplique de départ** (multiligne) | composant guidé |
| `/dialogues` — **locuteur affiché** (`speaker`) | texte simple, **non stylé** — c'est un nom de personne, jamais coloré en jeu |
| Champs de descripteur d'objectif ou de récompense (`key`, `value`, `command`, `category`, identifiants…) | texte simple, **non stylé** — ce sont des valeurs techniques, pas du texte affiché |

Il ne reste donc **aucun champ de texte destiné aux joueurs qui impose d'écrire du MiniMessage**
dans le chemin normal. Les seuls champs restés en texte simple sont ceux qui ne sont jamais rendus
avec des couleurs en jeu.

**Un seul mécanisme, et un seul.** La page d'édition de dialogue possédait un *second* dispositif :
un `select` « Couleur du texte » appliqué au texte simple et **ignoré** si le texte contenait déjà du
MiniMessage. Deux mécanismes concurrents pour le même besoin divergent tôt ou tard ; le `select` a
donc été retiré au profit du composant partagé, qui offre en plus les décorations, l'aperçu et le
mode code explicite. Le serveur continue d'accepter le champ `text_color` (une requête ou un
enregistrement antérieur fonctionne à l'identique) ; le formulaire ne l'émet simplement plus.

- **MiniMessage reste le format stocké** — c'est un détail interne. Le champ réellement soumis
  garde le même nom et la même valeur qu'avant : les actions agent, les validateurs et le plugin
  ne voient aucune différence, et les fichiers YAML produits sont inchangés.
- **Sans JavaScript**, c'est un champ texte ordinaire : la page reste utilisable.
- **Les textes multi-styles sont préservés.** Un éditeur « une couleur + des cases » ne peut pas
  représenter `<red>Roi</red> <gold>des Marais</gold>` sans l'aplatir. Le composant n'ouvre donc
  l'éditeur guidé que pour une valeur **uniforme** : au plus une couleur et des décorations qui
  englobent **tout** le texte. Dès qu'il y a deux couleurs, une balise au milieu du texte, une
  couleur hexadécimale ou une balise avancée (`<gradient>`, `<hover>`…), le texte reste affiché
  **tel quel** en mode avancé, avec la raison écrite à l'écran ; passer en mode guidé demande
  alors un **geste explicite** et le bouton annonce qu'il simplifiera les styles. **Aucune
  simplification silencieuse.**
- **Couleurs proposées** : `white`, `gray`, `dark_gray`, `black`, `red`, `dark_red`, `gold`,
  `yellow`, `green`, `dark_green`, `aqua`, `dark_aqua`, `blue`, `dark_blue`, `light_purple`,
  `dark_purple`. Les formats non couverts par la palette (dégradés, hexadécimal, interactions)
  restent entièrement disponibles en mode avancé — le composant ne retire aucune possibilité.

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

### Définition logique de PNJ — `npcs/*.yml` (V2 déclarative)

Depuis la V2, un PNJ RPGQuest a une **définition logique** en données :
un fichier par PNJ sous `plugins/RPGQuest/npcs/*.yml` (`YamlNpcEngine`,
même pattern que `quests/`), avec `id`, `display_name`, `dialogue` (optionnel),
`role` (optionnel), `enabled`. Format complet :
[NPC_FORMAT.md](../NPC_FORMAT.md). Cette définition est **indépendante de
Citizens et du monde** : on peut préparer toute la configuration d'un PNJ
(nom, dialogue, quêtes données) avant qu'il n'existe en jeu. Le **binding
Citizens** (`npc_citizens_bindings`, posé par `/rpgadmin npc tag`) reste
séparé et optionnel.

À terme, `NpcDefinition` devient la **source de vérité** des ids PNJ : un id
utilisé par `giver:`, `TALK_TO_NPC` ou un dialogue sans définition
correspondante est une **erreur de contenu** signalée dans le Control Panel
(`/npcs`, action agent `npc.list`). Le panel permet aussi de **créer / éditer**
une définition, d'**attribuer une quête** (pose `giver:`), de **lier une
définition à un PNJ Citizens existant** (`npc.citizens.list` /
`npc.citizens.link`, issue #81 phase 1 — collisions refusées, jamais de rebind)
et de **créer physiquement un PNJ Citizens depuis une définition** puis de le
lier (`npc.citizens.create`, issue #81 phase 2 — nom = `displayName`, monde de
la liste blanche RPGQuest, position bornée, rollback du PNJ créé si la liaison
échoue). Actions agent `npc.definition.create` / `npc.definition.update` /
`quest.giver.set` / `npc.citizens.link` / `npc.citizens.create`, jamais de YAML
brut ni de commande console. Le câblage de `/rpgadmin npc tag` (#66), la
suppression générale d'un PNJ Citizens et le rebind d'un binding existant ne
sont **pas** encore faits.

Un PNJ peut exister dans Citizens **sans** être encore géré par RPGQuest.
`NpcCatalog` (action `npc.list`) ne lit pas le registre Citizens : un PNJ créé
directement en jeu (`/npc create …`), sans définition **ni** binding, n'y
produit aucune ligne. La page `/npcs` du Control Panel comble ce trou en
raccrochant chaque PNJ du relevé `npc.citizens.list` sans liaison comme une
**ligne d'identité physique** (nom en jeu + `Citizens #N`, badges « sans fiche
RPGQuest » + « non lié », filtre *Non liés*, recherche par nom / id numérique /
UUID). Trois états lisibles : **Citizens uniquement** (`CITIZENS_ONLY`,
information — PNJ d'ambiance), **fiche RPGQuest uniquement** (`NOT_LINKED`,
« à lier ») et **lié** (`LINKED`). Depuis sa ligne, deux actions facultatives :
*Créer une fiche RPGQuest* (id pré-rempli = nom normalisé) et *Lier à une fiche
existante* (liaison inverse via `npc.citizens.link`, le PNJ Citizens fixé).
`/diagnostics` émet un INFO `CITIZENS_ONLY` par PNJ Citizens libre (issue #101).

Une page **`/dialogues`** (V1) donne la **lecture structurée** des dialogues à
embranchements (`dialogues/*.yml`) : par dialogue, les nœuds ordonnés (départ
d'abord), les choix avec leurs **actions et conditions typées**
(`{kind, target, value, raw}`), les relations PNJ et quêtes, l'accessibilité de
chaque nœud, et des diagnostics de cohérence (`NODE_UNREACHABLE`,
`QUEST_REF_UNKNOWN`, `DIALOGUE_NO_NPC`, `DEFINITION_DIALOGUE_DIVERGES`…). Les
erreurs de chargement (dialogue sans nœud, `start` invalide, cycle
`OPEN_DIALOGUE`, id dupliqué) sont listées à part. **L'ouverture d'un dialogue en
jeu se fait toujours par convention `rpgquest:<id du PNJ>`** (identité stable du
PNJ) — le champ `dialogue:` d'une `NpcDefinition` ne sert qu'aux diagnostics.
L'action `dialogue.definition.create` (permission dédiée `DIALOGUE_WRITE`) crée
un **squelette** de dialogue minimal (`id` + `start` + un nœud avec
locuteur/texte + un choix « fermer »).

Un **éditeur guidé** (issue #82) permet en plus, sous `DIALOGUE_WRITE` :
modifier le **locuteur / texte** d'un nœud (`dialogue.node.update`), **ajouter un
nœud simple** (`dialogue.node.create`, nœud orphelin à relier ensuite), et
**ajouter / modifier / supprimer un choix** (`dialogue.choice.add` / `.update` /
`.delete`). Chaque écriture **réécrit le fichier au format canonique** du panel
(commentaires et mise en forme d'origine non conservés), puis le **re-parse** et
le **recharge** ; en cas d'échec le contenu d'origine est **restauré**.

`dialogue.choice.update` édite **n'importe quel choix**, y compris un choix
porteur de conditions et d'actions : son texte et sa cible sont modifiables, et
deux propriétés structurées s'éditent par sélecteurs —

- l'**action de quête** du choix (`quest_action` ∈ `keep` / `none` /
  `start_quest` / `advance_quest` / `turn_in_quest`, avec `quest_id`) ;
- la **condition d'état de quête** (`quest_condition` ∈ `keep` / `none` / un
  `QuestState`, avec `condition_quest_id` et l'option `condition_negate`).

La valeur par défaut des deux est `keep` : **un paramètre absent ne touche à
rien**. Toute autre action et toute autre condition du choix (`GIVE_ITEM`,
`HAS_PERMISSION`, `RUN_SAFE_COMMAND`…) sont **reconduites à l'identique**, dans
leur ordre d'origine — l'enregistrement ne peut pas les perdre, et la page les
liste explicitement sous le choix comme « conservé à l'identique ». Deux actions
de quête (ou deux conditions `QUEST_STATE`) sur un même choix ne sont pas
représentables par le formulaire : il bascule alors en `keep` et le dit, plutôt
que de réduire deux effets à un seul.

`dialogue.choice.delete` reste réservé aux choix **sans condition ni action hors
« fermer »** : supprimer un choix porteur d'effets de jeu doit rester un geste
explicite (retirer d'abord son action et sa condition). Restent hors périmètre :
le renommage / déplacement / suppression de nœud et le réordonnancement des
choix.

Les champs de texte de l'éditeur montrent la **source MiniMessage brute** (rien
n'est réécrit à l'insu de l'utilisateur), accompagnée d'un **aperçu rendu** et de
la **palette de couleurs** du formulaire de création. La palette ne réécrit que
la balise de couleur **englobante** ; sur un texte à balises composites elle se
désactive et l'annonce.

### Présentation des pages métier et diagnostics contextuels (issues #89 / #49)

Les pages `/npcs`, `/quests`, `/stories` et `/dialogues` suivent le même modèle :
la **liste est une synthèse** (accordéon Bootstrap, un seul élément déplié à la
fois), **cliquer** ouvre un **détail structuré en sections**, et **cliquer sur une
action** déplie son **formulaire** (rien n'est affiché d'emblée). Le
rafraîchissement des catalogues passe par une **barre d'outils compacte** de
boutons ; la recherche est un `input-group` Bootstrap aligné à des filtres.

#### Catalogue fusionné source + runtime pour `/quests` et `/stories` (issue #144)

Les pages `/quests` et `/stories` **fusionnent deux origines** : le dernier relevé
runtime de l'agent (`quest.list` / `story.list`, ce que le serveur DEV a
réellement chargé) **et** la **source éditable** relue à chaque affichage
(`SourceCatalog`, module `control-panel` `panel.content` : les fichiers
`src/main/resources/quests/*.yml` + `stories/*.yml` du checkout, via les mêmes
relecteurs `QuestYaml` / `StoryYaml` que l'éditeur #46). La clé de fusion est
l'identifiant « nu » (sans préfixe `rpgquest:`). Chaque entrée porte un **état
explicite** :

- **Source + serveur** (`SYNCED`) — présente des deux côtés ; aucun badge.
- **« Source uniquement »** (`SOURCE_ONLY`) — enregistrée dans la source (par
  exemple juste créée depuis `/quests/new`) mais **pas encore chargée en jeu**.
  Badge `info` + note : elle sera prise en compte au prochain rechargement du
  contenu RPGQuest côté serveur. **Jamais** présentée comme active.
- **« Hors source »** (`RUNTIME_ONLY`) — chargée par le serveur mais **absente**
  de la source éditable (fichier supprimé, renommé, ou source non montée).

L'édition et l'activation en jeu **restent deux étapes distinctes** : enregistrer
depuis l'éditeur n'appelle **aucun** rechargement Minecraft. « Rafraîchir le
catalogue » interroge toujours le serveur ; la source, elle, est relue à chaque
affichage — aucun bouton n'est nécessaire pour la voir. Les listes déroulantes
des **actions admin** (`quest.start`, `story.advance`…) restent limitées aux
entrées runtime (une entrée source-only échouerait côté agent). Sans
`content.repo-dir` configuré, aucun badge d'origine n'est affiché.

Corollaire pour l'éditeur #46 : `AgentPages.referenceData()` fusionne aussi les
quêtes de la source dans les lookups de construction de contenu (prérequis de
quête, chaîne de story) — une quête tout juste créée est immédiatement
sélectionnable, et le diagnostic « quête inconnue dans la chaîne » d'une story en
tient compte, **sans redémarrage Minecraft**.

#### Catalogue de dialogues fusionné source + runtime, et création avec PNJ (issue #145)

Le même principe s'applique à **`/dialogues`** : `"dialogues"` est un `KIND` de
`ContentWorkspace` (fichiers `src/main/resources/dialogues/*.yml`),
`SourceCatalog.dialogues()` les relit via `DialogueYaml` (module `control-panel`,
`panel.content`), et `AgentPages` fusionne `dialogue.list` (runtime) avec la
source sur l'id « nu », avec les **mêmes trois états** que #144
(`SYNCED` / **« Source uniquement »** / **« Hors source »**), la source relue à
chaque affichage.

La **création** d'un dialogue passe désormais par l'éditeur source
**`/dialogues/new`** (`ContentEditorPages`, page server-rendered sans JavaScript,
comme `/quests/new`) et non plus par l'action agent : identité, **PNJ à rattacher**
(champ recherchable par nom humain ou par id, alimenté par le dernier `npc.list`),
locuteur affiché, couleur du texte (palette), et réplique du nœud `start`.
`DialogueYaml.write` produit le fichier au **format canonique du moteur** —
identique octet pour octet au squelette de `dialogue.definition.create`, donc
re-lisible et rechargeable sans surprise. Un dialogue tout juste enregistré
apparaît immédiatement dans `/dialogues` avec le badge **« Source uniquement »**
et **jamais** comme actif en jeu.

Si un PNJ est sélectionné à la création, une **seconde écriture** met à jour sa
**définition** pour qu'elle pointe vers ce dialogue, via l'action agent
**existante** `npc.definition.update` (jamais de stockage parallèle) : les champs
`display_name` / `role` / `enabled` sont repris du dernier `npc.list` pour ne rien
écraser, et seul `dialogue_id` change. Si le PNJ est absent du dernier relevé, le
dialogue est **quand même** enregistré dans la source et le rattachement est
signalé comme à refaire depuis la fiche du PNJ (demi-état explicite, jamais
silencieux). La fiche PNJ (`/npcs`) porte un bouton **« Créer un dialogue pour ce
PNJ »** → `/dialogues/new?npc=<id>` avec locuteur prérempli, et le `<select>`
Dialogue de la fiche PNJ propose aussi les dialogues **de la source** (un dialogue
créé à l'instant est sélectionnable sans redémarrage Minecraft).

**Rééditer** un dialogue depuis `/dialogues/edit/<id>` ne touche qu'au **locuteur
et à la réplique du nœud de départ**. L'enregistrement repart du **fichier réel** :
les autres nœuds, les choix, leurs conditions et leurs actions sont reconduits
tels quels (`DialogueYaml` les porte dans son modèle), et la page annonce ce
qu'elle conserve. Si la relecture du fichier signale quoi que ce soit que
l'éditeur ne sait pas représenter (table de traductions, construction YAML
exotique), l'enregistrement est **refusé** et la raison affichée — jamais un
écrasement partiel. Même garde-fou sur `/quests/edit/<id>`, dont le formulaire
reconstruit la quête entière.

Un diagnostic `DIALOGUE_DECLARED_MISSING` (une fiche PNJ pointe vers un dialogue
que le serveur n'a pas chargé) est **rétrogradé en info** — « rechargement en
attente » — quand ce dialogue existe dans la source ; les incohérences réelles
(dialogue vraiment introuvable, fichier rejeté, choix vers un nœud inexistant)
restent des erreurs. L'édition guidée des nœuds et des choix (#82) reste réservée
aux dialogues **chargés par le serveur** (elle repose sur des actions agent qui
ciblent un dialogue runtime).

Chaque avertissement ou erreur est rendu par le registre **`DiagnosticHelp`**
(module `control-panel`, `panel.web`) sous une forme **actionnable** : titre en
français clair, **conséquence**, **action recommandée**, **lien vers une ancre
précise du centre de documentation** (`/docs/<fiche>#<section>`), et le **code
technique** (`BINDING_NO_DEFINITION`, `NODE_UNREACHABLE`, `QUEST_PREREQ_UNKNOWN`,
`STORY_QUEST_UNKNOWN`…) en second plan seulement. Les vérifications de référence
propres aux quêtes et aux stories (prérequis inconnu, donneur sans fiche, quête
absente d'une chaîne) sont calculées côté panel à partir des derniers relevés
`quest.list` / `npc.list`, **complétés par les quêtes de la source éditable**
(issue #144) : une quête « source uniquement » n'est jamais signalée comme
prérequis ou étape « inconnu ». Quatre fiches de dépannage dédiées existent dans le
centre de documentation : **`pnj-depannage`**, **`dialogues-depannage`**,
**`quetes-depannage`**, **`stories-depannage`** (déclarées dans
`control-panel/src/main/resources/docs/_index.txt`), chacune au format
*Ce que cela signifie / Pourquoi il faut corriger / Comment corriger /
Vérification / Référence technique*.

La page **`/diagnostics`** (issue #38) centralise tous ces diagnostics — PNJ,
dialogues, quêtes, stories, serveur/agent, mondes — en **un seul endroit trié**
(erreurs d'abord), pour répondre à « que dois-je corriger maintenant ? » sans
ouvrir chaque page. Elle agrège des `DiagnosticProvider` (paquet
`control-panel` `panel.diag`) qui lisent le **dernier relevé de l'agent**
(catalogues + heartbeat) — aucune requête n'est déclenchée au chargement. Chaque
carte donne : titre humain, domaine et ressource concernée, conséquence, action,
un bouton **Ouvrir** (lien profond vers la page métier, qui déplie l'élément
ciblé), un éventuel **Corriger maintenant** (uniquement une action déjà offerte
par le panel — jamais de correction automatique), **Comment corriger ?** (ancre
doc précise), et le code technique en dernier. Le bouton **Actualiser les
diagnostics** enqueue en une fois les quelques relevés `*.list` nécessaires. La
tuile Diagnostics de l'accueil et le Dashboard affichent une synthèse
(compteurs). Fiche `/docs/serveur-depannage` pour les diagnostics
serveur/agent/mondes.

### Resynchronisation automatique après une mutation (issues #111 → #120)

PlugAdmin distingue trois états : **A.** le contenu écrit dans la source de
vérité (`npcs/*.yml`, `dialogues/*.yml`…) ; **B.** l'instantané du catalogue que
le panel a relu (dernier relevé `npc.list` / `dialogue.list` / `npc.citizens.list`
réussi de l'agent) ; **C.** ce que le serveur Minecraft a effectivement rechargé
en jeu. Le panel n'affiche jamais **C** comme acquis : « Dialogue déclaré … (pas
encore chargé en jeu) » reste tel quel tant qu'un relevé ne le confirme pas.

Chaque mutation de contenu **déclare les catalogues qu'elle périme**
(`AgentActionCatalog.Spec#refreshTypes()`). Dès qu'elle **réussit**,
`AgentEndpoints` ré-enfile automatiquement ces relevés `*.list`
(`created_by = "auto"`, dédupliqués, jamais sur un échec ni un renvoi
idempotent) ; le centre de notifications les masque. Côté navigateur,
`panel.js` recharge **une seule fois** la page métier quand la file d'actions
est retombée au repos. Résultat : après « Enregistrer » (fiche PNJ, dialogue,
liaison Citizens…), la fiche, la liste générale et les diagnostics reflètent
le nouvel état **sans F5 et sans clic manuel sur « Rafraîchir catalogue »**.
Correspondances : `npc.definition.*` → `npc.list` ; `npc.citizens.link/create`
→ `npc.list` + `npc.citizens.list` ; `dialogue.*` → `dialogue.list` ;
`quest.giver.set` → `npc.list` + `quest.list` ; `player.ban/unban` →
`player.catalog`.

### Confirmations et saisie des formulaires PlugAdmin (issues #111 / #113 / #117 / #118)

Une **édition de contenu réversible** (créer / modifier une fiche PNJ, un nœud,
un choix, une liaison logique, attribuer une quête) **ne demande plus de case à
cocher** : une phrase d'information suffit, le bouton reste l'engagement
explicite. Seules les actions **réellement sensibles** (bannissement, reset
« nouveau joueur », spawn d'un PNJ Citizens, suppression d'un choix) gardent une
confirmation explicite. La distinction est portée par
`AgentActionCatalog.Spec#sensitive()`.

Les formulaires affichent les exemples en `placeholder` (jamais en valeur par
défaut), une aide métier sous chaque champ, et ouvrent la documentation dans un
nouvel onglet. Le formulaire **« Nouveau dialogue »** est une action primaire
visible ; l'ID technique est distinct du **locuteur affiché** ; une **palette de
couleurs MiniMessage** (14 teintes, pastilles + aperçu réel) génère le
`<couleur>…</couleur>` — le MiniMessage saisi à la main reste possible et n'est
jamais ré-enrobé.

### Annuaire des joueurs dans PlugAdmin (issue #96)

La page **`/players`** est un **annuaire d'administration** : joueurs **connectés**
et joueurs **hors ligne déjà venus au moins une fois**. La source de vérité est le
**serveur Paper** (`OfflinePlayer` + connectés) via l'action agent
`player.catalog` — PlugAdmin ne tient **aucune base de joueurs propre**. Identité
stable = **UUID** ; identité d'affichage = **pseudo** (dernier connu). Chaque
joueur porte : `online`, `firstPlayed`, `lastSeen`, `banned` (+ raison), monde /
position si en ligne. Tri par défaut : connectés d'abord, puis dernière connexion
décroissante ; recherche pseudo / UUID ; filtres Tous / En ligne / Hors ligne /
Bannis ; pagination (tout côté serveur).

**Modération** — actions agent `player.ban` / `player.unban` via l'API `BanList`
de profil Paper : fonctionnent **en ligne comme hors ligne** (expulsion si
connecté), **raison obligatoire** pour le ban, idempotentes, auditées (acteur
PlugAdmin, UUID + pseudo, raison, résultat). Permission Control Panel dédiée
`PLAYER_MODERATE` (jamais un rôle en lecture seule). Aucune commande console
libre ; toute mutation résout d'abord l'UUID canonique. Bans temporaires : hors
périmètre.

Chaque action de la fiche déclare sa compatibilité **hors ligne** :
ban/unban/variables/reset fonctionnent hors ligne ; « donner un objet » est **en
ligne uniquement** (indisponible expliqué, jamais de faux succès). Le **droit de
construction** persistant pour un joueur hors ligne n'est **pas** géré : il exige
un gestionnaire de permissions persistant (permissions granulaires *issue #27* +
LuckPerms), non intégré à cette branche — la fiche affiche « non géré » sans faux
interrupteur. Fiche `/docs/joueurs-admin`.

### Export versionné du contenu — `/content/export` (issue #108)

Phase 1 du pipeline de contenus LodyQuests (export → import #109 → génération IA #110). La page
**`/content/export`** permet d'exporter **quêtes, stories, dialogues et PNJ logiques** dans un
**format public versionné** `lodyquests-content-pack` (`schemaVersion: 1`, YAML déterministe) —
pour sauvegarder, archiver ou fournir à une IA. Granularités : *tout le contenu*, *une famille*,
*une sélection d'identifiants d'une même famille*.

- **Couche d'export = plugin** (`com.lodygames.rpgquest.content.pack`) : DTO sans Bukkit,
  `ContentPackMapper` (modèle runtime → DTO ; jamais de sérialisation runtime directe, aucun accès
  disque, aucune donnée joueur/secret atteignable), `ContentPackSerializer` (YAML canonique écrit
  à la main — ordre de clés fixe, familles triées par id, textes entre guillemets, reproductible
  octet-pour-octet), `ContentPackAssembler` (`exportAll`/`exportFamily`/`exportElement`/
  `exportSelection`, manifest + `counts`).
- **Vocabulaire du pack = le schéma YAML RPGQuest existant** (contrat stable versionné par
  `schemaVersion`), pas une 2ᵉ représentation métier → re-parsable par les parseurs réels (round-trip
  visé pour #109). Ajouter une famille (items, recettes…) = 1 constante `ContentFamily` + 1
  `*PackEntry` + 1 cas de mapper/serializer + 1 `Supplier`.
- **Transport** : action agent **lecture seule** `content.export` (`AgentActionType.CONTENT_EXPORT`,
  params `family` ∈ `all|quests|stories|dialogues|npcs` et `ids` optionnel). Le pack revient sous
  `details.pack`, borné à **~56 Kio** (plafond du dépôt de résultat d'action = 64 Kio) ; au-delà,
  échec lisible → exporter par famille ou par élément. Aucun effet de bord, aucun catalogue à
  réenfiler.
- **Control Panel** : permission `CONTENT_EXPORT` (tous les rôles en lecture) ; téléchargement via
  `GET /content/export/download?action=<id>` (`Content-Disposition: attachment`, nom
  `lodyquests-<famille>-<AAAA-MM-JJ>.yaml`) ; audit à l'enfilement et au téléchargement.
- Le contenu `secret: true` **est** exporté (un backup ne doit pas perdre de contenu), avec son
  flag conservé.
- Format complet + exemple + stratégie d'évolution `schemaVersion` : [docs/CONTENT_PACK.md](CONTENT_PACK.md).
- **Hors périmètre #108** : import/écriture (#109), familles items/recettes,
  découpage/compression du transport.

### Atelier IA — `/ai/studio` et `/ai/providers` (issue #146)

Troisième et dernière phase du pipeline de contenus : **décrire en français** ce que l'on veut, et
obtenir une proposition validée, prête à être importée. L'atelier s'appuie entièrement sur les deux
phases précédentes — le contrat de #110 pour le prompt, l'import de #109 pour tout ce qui suit la
réponse.

**Trois familles, une à la fois.** L'atelier sait produire **une quête**, **un dialogue** ou **une
story**, choisis par un lien en tête de page (`/ai/studio?kind=quest|dialogue|story`). Le choix
passe par un lien, donc par un GET, parce que la politique de sécurité du panel interdit le
JavaScript en ligne et qu'un sélecteur échangeant le formulaire côté client exigerait un script ;
la famille voyage ensuite en champ caché, pour que l'envoi ne dépende pas de l'URL d'où il part.

Un seul élément par demande, volontairement : demander « une quête, son dialogue et une story » en
un appel produit un pack dont une partie est bonne et l'autre refusée, sans moyen simple de ne
corriger que la mauvaise. Pour une quête **et** son dialogue, on fait deux demandes — la seconde
peut citer la première, qui existe alors déjà.

Chaque famille a ses propres consignes, celles que les modèles manquent spontanément :

| Famille | Ce que le prompt impose en plus |
|---|---|
| Quête | une seule quête, dans `quests` ; ne pas remplir les autres sections |
| Dialogue | l'id du dialogue est celui **imposé par le formulaire** — jamais déduit du nom du PNJ (#225) ; `nodes` est une **map** ; un choix dont une condition est fausse **n'est pas affiché** ; aucune action ni condition ne s'invente ; toujours prévoir une sortie |
| Story | un **enchaînement ordonné de quêtes existantes** ; aucune quête inventée ; **aucune** section `quests` — une quête manquante est une dépendance, pas une quête à écrire |

**Deux pages, deux permissions distinctes** :

| Page | Permission | Qui l'a |
|---|---|---|
| `/ai/studio` — décrire, générer, relire | `AI_USE` | Propriétaire, Administrateur, Éditeur de contenu |
| `/ai/providers` — clés API, modèles, plafonds | `AI_CONFIGURE` | Propriétaire, Administrateur **seulement** |

La séparation est voulue : un appel d'IA coûte de l'argent réel et part vers un tiers, ce qui n'est
pas la même décision que modifier un fichier local ; et manipuler une clé d'API tierce n'est pas un
geste d'édition de contenu. Un **Testeur** et un rôle **Lecture seule** n'ont ni l'une ni l'autre.

**L'atelier n'écrit jamais.** `AiContentStudio` s'arrête à l'aperçu validé ; le bouton
d'enregistrement de la page poste vers `/content/import`, qui applique le pipeline de #109 avec son
arbitrage des collisions et sa confirmation explicite. Il n'existe donc **qu'un seul chemin
d'écriture** dans tout le panel, et l'IA n'en obtient aucun raccourci — c'est la dernière exigence
du ticket (« aucune publication ou modification automatique sans approbation humaine »), obtenue par
construction et non par vigilance à l'écran.

**Ce que l'administrateur n'a pas à fournir.** Il décrit son intention ; le panel joint
automatiquement les règles de LodyQuests, la documentation de contenu générée pour #110, le schéma
JSON, les types d'objectifs et de récompenses **réellement** supportés, et les **références
réellement existantes** relevées sur le serveur (PNJ, quêtes, mondes). Rien n'est recopié dans
`ContentPromptBuilder` : un type ajouté au moteur arrive dans le prompt sans qu'une ligne soit
touchée, et un type qui n'existe pas ne peut pas y apparaître. Les trois familles partagent tout ce
qui est coûteux à maintenir — contrat, schéma, références, consigne de format, demande de
correction — et ne divergent que par leurs consignes propres. Les références réelles sont la mesure
la plus efficace contre les références inventées, que le ticket demande explicitement de limiter.

**L'IA ne remplace pas les validateurs.** La réponse traverse `AiYamlExtractor` (délimitation d'une
réponse enrobée ou bavarde — on délimite, on ne répare jamais), puis l'analyse d'import, donc les
validateurs réels. Un type inventé, une référence inconnue, un champ obligatoire manquant sont
attrapés et affichés.

**Ce que vous imposez est vérifié, pas espéré (issues #223 et #224).** Un document peut être
parfaitement valide pour le moteur sans être ce qui avait été demandé — cinq nœuds réclamés, quatre
produits ; un identifiant imposé, un autre inventé. Les validateurs ne peuvent pas le savoir : eux
ne voient que le document. Le panel compare donc **lui-même** la proposition à la demande, et la
refuse aussi fermement qu'une erreur de validation :

| Champ | Statut | Ce que fait le backend |
|---|---|---|
| **Identifiant souhaité** (quête, story, PNJ porteur) | contrainte | normalisé **avant** l'appel : `clé` et `rpgquest:clé` sont tous deux acceptés, le namespace n'est jamais doublé, un namespace étranger ou un caractère interdit est refusé sans dépenser de jeton. Après génération, l'identifiant produit est confronté à l'identifiant imposé. |
| **Nombre de nœuds** (`0` = libre) | contrainte dès que `> 0` | le prompt le déclare impératif, puis le panel recompte la **vraie map `nodes`** avec le lecteur réel et refuse l'écart : « 5 nœuds demandés, 4 générés ». |
| **Nombre d'étapes** d'une quête | indication, et le formulaire le dit | non vérifié : contrairement aux nœuds d'un dialogue, un objectif de plus ou de moins est souvent ce qui rend la quête jouable. |

**« Demander une correction » (issue #222).** En cas de refus — validation ou écart de demande — le
bouton renvoie à l'IA sa propre sortie, les diagnostics réels **et la demande d'origine**, annoncée
comme toujours impérative. Les trois comptent :

- sans la sortie précédente, le modèle repart de zéro et reproduit souvent la même erreur ;
- sans **tous** les diagnostics, il corrige un problème sur cinq. Ils voyagent dans **un seul**
  champ de formulaire, une ligne par problème : le lecteur de formulaire du panel ne garde qu'une
  valeur par nom, et une série de champs homonymes n'en transportait donc qu'un ;
- sans les consignes d'origine, il répare l'erreur signalée **en perdant** l'identifiant imposé ou
  le nombre de nœuds, et la proposition est refusée pour une autre raison.

Génération et correction lisent le formulaire par le **même** chemin : une correction ne peut donc
pas perdre un champ, et l'écran se rouvre rempli. Si l'appel de correction échoue lui-même (réseau,
401, délai), la proposition précédente et ses diagnostics sont conservés et le bouton reste
disponible.

**Un PNJ qui a déjà un dialogue n'en reçoit jamais un second en silence (issue #225).** Le champ
demande le **PNJ porteur**, non « l'identifiant du dialogue » : les deux ne coïncident que par
défaut. Quand le dialogue réellement lié ne porte pas le nom du PNJ — `mira_cartographer` déclare
`rpgquest:mira_first_map` — la génération **s'arrête avant tout appel payant** et affiche le
dialogue lié, l'identifiant du PNJ, son Citizens et les entrées apparentées, puis demande :

- **modifier le dialogue existant** (recommandé) — la proposition porte son identifiant, aucun
  second dialogue n'est créé, et l'import arbitre la collision ;
- **créer un nouveau dialogue et remplacer le lien** — l'ancien n'est ni supprimé ni délié, et
  l'écran dit qu'il faut repointer la fiche du PNJ, sinon les joueurs continuent d'entendre
  l'ancien.

Il n'y a pas de troisième possibilité : le moteur ne rattache un dialogue à un PNJ que par le champ
`dialogue:` de sa définition ou par la convention de nom. Un dialogue « supplémentaire mais non
lié » ne serait joignable par personne.

**Abstraction de fournisseur.** `AiProvider` n'expose que trois opérations (identité, test de
connexion, génération). Tout ce qui est propre à une API — forme du corps JSON, en-tête
d'authentification, emplacement du texte dans la réponse — reste enfermé dans l'implémentation, et
`AiProviderRegistry` est le seul endroit qui connaît la liste. Trois fournisseurs réels sont
livrés : **Anthropic / Claude** (en-tête `x-api-key`, version d'API épinglée, `system` de premier
niveau, blocs `content` typés), **OpenAI et API compatibles** (Chat Completions, choisie pour sa
compatibilité avec Azure, les mandataires et les moteurs locaux via l'URL de base), et **Google
Gemini** (en-tête `x-goog-api-key`, modèle dans le chemin — donc encodé —, `systemInstruction`).

**Sécurité de la clé** :

- stockée dans la base locale du panel (`/var/lib/plugadmin/control-panel.db`), **hors dépôt Git**,
  en mode `600` lisible par le seul compte du service ;
- **jamais renvoyée au navigateur** : le champ de saisie est toujours vide, aucun champ caché ne la
  transporte, et l'écran n'affiche que sa longueur et une **empreinte SHA-256 tronquée** — assez pour
  reconnaître quelle clé est en place après une rotation, jamais pour la reconstituer ;
- **jamais journalisée** : l'audit retient le fournisseur, le modèle, les jetons, le verdict et
  l'empreinte, jamais la clé. `AiProviderSettings.toString()` est **redéfini exprès** — le
  `toString` généré d'un record aurait imprimé la clé entière, et il suffit d'un message
  d'exception pour la faire fuir ;
- **jamais en clair sur le réseau** : une URL de base en HTTP est refusée (une adresse de boucle
  locale est tolérée pour un mandataire) ;
- un champ clé vide signifie « ne pas y toucher », jamais « effacer » — sinon changer de modèle
  effacerait la clé. Effacer est un bouton distinct, qui désactive aussi le fournisseur.

**Garde-fous d'appel** : plafond de jetons en sortie et délai maximal, tous deux configurables par
fournisseur. Un échec, un délai dépassé ou une réponse illisible ne modifient **rien** et sont
affichés comme tels. « Tester la connexion » fait un **vrai** aller-retour minimal, parce que
« configuration enregistrée » ne prouve rien.

**Texte stylé** : tout champ destiné au joueur — titre de quête, titre de story, **nom affiché du
locuteur** d'un dialogue — utilise le composant guidé partagé, comme partout ailleurs (voir § 4).
Aucune balise MiniMessage n'est demandée dans le parcours normal, et l'IA est priée de rester sobre
sur la couleur.

**Limites connues** : un seul élément par génération (voir ci-dessus, c'est un choix), les PNJ ne
sont pas générables depuis l'atelier parce que la famille `npcs` n'est pas éditable depuis le panel,
et aucun coût monétaire n'est estimé — les jetons rapportés par le fournisseur sont affichés et
audités, mais inventer un prix supposerait une grille tarifaire qui change sans prévenir. Le plafond
de jetons et le délai sont des garde-fous **par appel**, pas un budget cumulé.

### Import sécurisé d'un content pack — `/content/import` (issue #109, phase 2)

Phase 2 du pipeline de contenus. Le pack n'est **jamais** copié tel quel : il est analysé, validé,
comparé à la source, puis enregistré seulement sur confirmation explicite —
`IMPORT → ANALYSE → VALIDATION → DIFF → BROUILLON → CONFIRMATION → ENREGISTREMENT SOURCE`.
Permission dédiée **`CONTENT_IMPORT`** (Propriétaire, Administrateur, Éditeur de contenu ; jamais un
rôle de lecture ou de test), CSRF, audit à l'analyse, à la confirmation et à chaque écriture.

**Trois propriétés de sécurité obtenues par construction**, pas par vérification :

1. **Aucun chemin ne vient du fichier.** La destination est calculée par
   `ContentWorkspace.write(kind, slug, …)` depuis une famille prise dans une liste blanche et un
   `slug` dérivé de l'identifiant métier, validé par l'expression régulière du workspace. Il n'y a
   pas de chemin à traverser : un `id` de la forme `rpgquest:../../etc/passwd` est refusé parce
   qu'il ne peut pas devenir un nom de contenu, pas parce qu'un filtre l'a repéré.
2. **Aucun octet du fichier n'est écrit tel quel.** Chaque élément est relu en brouillon par le
   lecteur réel de sa famille (`QuestYaml.fromMap`, `StoryYaml.fromMap`, `DialogueYaml.fromMap` —
   exactement le code qui lit un fichier source, extrait pour l'occasion) puis **ré-émis** par
   l'écrivain réel. Ce qui atterrit sur le disque est donc produit par le panel, dans sa forme
   canonique, et relisible par les parseurs du plugin. Une construction que l'éditeur ne sait pas
   représenter (table de traductions, par exemple) est signalée, jamais aplatie en silence.
3. **Aucun écrasement silencieux.** Un identifiant déjà présent et différent devient un **conflit**
   qui bloque l'import jusqu'à un arbitrage explicite (*Remplacer l'existant* / *Garder l'existant*).
   Le remplacement repasse par le verrou optimiste du workspace : un fichier modifié entre l'analyse
   et la confirmation est **refusé**, pas écrasé.

**États par élément**, dans le vocabulaire du ticket : `NEW` (nouveau), `MODIFIED` (modifié),
`UNCHANGED` (inchangé, rien à écrire), `CONFLICT` (collision en attente de décision), `SKIPPED`
(ignoré — décision de l'utilisateur, ou famille non écrivable), `INVALID` (inexploitable, bloquant).
Chaque élément modifié porte son **diff ligne à ligne**, replié par défaut pour rester lisible sur
mobile — l'exigence du ticket est de ne pas se limiter à un gros dump YAML.

**Validation** : les validateurs réels du panel (`QuestValidator`, `StoryValidator`,
`DialogueValidator`), donc les mêmes règles que l'éditeur guidé. Les **références internes au pack**
sont résolues : une story qui cite une quête du même pack ne déclenche pas « référence inconnue »,
grâce à `RefData#plus`, qui ajoute les identifiants fournis par le pack avec l'origine *source*.
Doublons dans le pack, identifiants invalides, types d'objectifs inconnus et paramètres obligatoires
manquants sont des **erreurs** qui bloquent l'import.

**Version de schéma** : une `schemaVersion` plus récente que celle supportée est **refusée
explicitement** (jamais interprétée approximativement) ; une version antérieure est refusée en
nommant la version attendue, aucun migrateur n'existant à ce jour.

**Sans état serveur** : le pack est reposté à chaque étape et l'analyse est **refaite** à chaque
soumission. Une analyse mise en cache deviendrait fausse dès qu'un éditeur enregistre en parallèle,
et la confirmation écrirait d'après un état périmé ; ici elle re-valide tout et redétecte les
collisions apparues entre-temps. Le corps de la requête est lu avec une **limite explicite qui
signale le dépassement** (`Http.formBody(exchange, max)`) : le lecteur historique tronque en silence
à 64 Kio, ce qui aurait importé un pack amputé de ses derniers éléments sans que personne ne le voie.

**Familles importables** : quêtes, stories et dialogues — exactement celles que `ContentWorkspace`
sait écrire. Les **PNJ** font partie du format mais ne sont pas éditables depuis le panel : ils sont
rapportés `SKIPPED` **avec leur motif**, jamais ignorés en silence.

**L'import n'active rien** : il écrit dans la source (le dépôt AWS). Le contenu n'arrive sur le
serveur Minecraft que par un rechargement ou un déploiement, comme pour l'éditeur guidé.

> **Correction de format au passage (schemaVersion 1).** Le pack écrivait la liste des quêtes d'une
> story sous la clé `questIds`, alors que le moteur lit `quests` (`StoryDefinitionParser`). Une story
> exportée par #108 n'était donc **pas relisible** par le serveur, ce qui vide le format de son sens.
> L'export écrit désormais `quests` ; l'import accepte **les deux** orthographes, pour que les packs
> déjà exportés restent importables, et refuse qu'elles soient renseignées ensemble. Le schéma, les
> gabarits et le contrat rédigé de #110 ont été alignés.

### Contrat de contenu machine-readable (issue #110, phase 1)

Pour qu'une IA ou un outil externe produise un pack valide **sans accès au serveur ni au code**, le
Control Panel publie trois documents, tous **générés** et téléchargeables depuis `/content/export`
(permission `CONTENT_EXPORT`, lecture pure, aucun agent sollicité — le contrat ne dépend pas du
serveur Minecraft) :

| Route | Contenu | Usage |
|---|---|---|
| `GET /content/schema.json` | JSON Schema draft 2020-12 du format `lodyquests-content-pack` v1 | validation automatique |
| `GET /content/template?family=<famille>` | gabarit YAML commenté (famille, ou pack complet sans paramètre) | rédaction à la main |
| `GET /content/contract.md` | contrat rédigé : identifiants, types et paramètres, stories, références, dialogues, exemple minimal et exemple complet | à coller dans un prompt |

**Rien n'est maintenu à la main.** `ContentPackSchema` et `ContentPackTemplates` dérivent les
objectifs et les récompenses de `Descriptors` — la même source qui pilote le formulaire, l'écriture
YAML, l'aller-retour et la validation du panel, et dont `EditorDescriptorsTest` verrouille
l'ensemble des types sur l'`ObjectiveType` du moteur. Un type ajouté au moteur apparaît donc dans le
schéma, le gabarit et la documentation sans qu'une ligne soit recopiée ; `ContentPackContractTest`
échoue si le contrat décrit un type inexistant, en oublie un, ou laisse passer un champ étranger
(`additionalProperties: false` par branche de type).

Ce que le schéma contraint : l'enveloppe (`format` et `schemaVersion` en constantes — les **seuls**
champs sur lesquels un import décide), les quêtes (identifiants `namespace:clé` par expression
régulière, au moins une étape, au moins un objectif par étape, un `oneOf` par type d'objectif et de
récompense avec leurs champs obligatoires réels), les stories, les PNJ logiques, les `dependencies`
déclarées (quêtes / PNJ / dialogues / objets) et les `metadata` de provenance, volontairement
ouvertes et sans effet sur le gameplay.

**Limite assumée de cette phase** : la section `dialogues` n'est contrainte que sur son squelette
(id, `start`, nœuds, choix). Le vocabulaire des actions et des conditions vit dans les énumérations
`ActionType` / `ConditionType` du plugin et n'est, à ce jour, déclaré nulle part que le Control Panel
puisse dériver : le schéma laisse donc ces deux tableaux libres plutôt que de recopier une liste qui
divergerait en silence. Le contrat rédigé l'énonce explicitement au lecteur, humain ou IA. Rendre ce
vocabulaire dérivable (descripteurs de dialogue côté panel, ou relevé d'agent) est la suite directe.

Les exemples portent tous le préfixe d'identifiant **`tc110_`** : reconnaissables, donc faciles à
retrouver et à supprimer après un essai, et impossibles à confondre avec du contenu de production.
L'exemple complet (« Les mines oubliées ») enchaîne deux quêtes par une story, fournit un PNJ et son
dialogue — dont l'id est celui du PNJ, convention du moteur — et déclare ses dépendances, y compris
celles que le pack satisfait lui-même, pour que l'import puisse les reconnaître comme telles.
Aucune contrainte d'équilibrage n'est inventée : le contrat dit explicitement que rien ne plafonne
une récompense côté moteur.

### Commandes RPGQuest — `/rpgadmin npc`

Documentées en détail en **section 2 (Administration)** ; résumé :
`/rpgadmin npc tag [id]` (id auto-généré `npc_<n>` si omis) | `untag` |
`info`, permission `rpgquest.admin.world`, ciblent toujours l'entité
regardée à ≤ 6 blocs. `tag` est idempotent (ré-étiqueter exige `untag`
d'abord). **Aucune liste globale des PNJ n'existe en jeu** (il faut viser
physiquement l'entité) — en revanche le **Control Panel** expose une page
`/npcs` en lecture (action agent `npc.list`, `NpcCatalog`) qui croise
liaisons Citizens, dialogues `rpgquest:<id>`, `giver:` des quêtes et
objectifs `TALK_TO_NPC`, liste les **ids canoniques** connus et signale les
anomalies de configuration (id référencé sans PNJ tagué, tag orphelin type
`garde` au lieu de `guard`, doublon). Voir `docs/control-panel/AGENT.md`
(payload `npc.list`). La validation/autocomplétion de `/rpgadmin npc tag`
elle-même (issue #66) n'est **pas** encore câblée.

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
    Dialogue `guide.yml` = **centre d'aide** : « Comment fonctionne le jeu ? »
    → nœud `help_menu` (un sujet par mécanique), orientations textuelles vers
    les autres PNJ. Structure multi-Hub : `hub-guides/*.yml`,
    `/rpgadmin guide list|info` — voir [HUB_GUIDE.md](HUB_GUIDE.md).
-   id Citizens numérique `1` → PNJ **Libraire** (id logique RPGQuest
    `libraire`). Remet `rpgquest:journal_quetes` (une seule fois, garde
    `LACKS_CUSTOM_ITEM` + soulbound) ; clic droit sur le journal → GUI
    `/quests` à deux onglets.

Procédure complète (créer → tagger → dialogue → quête) : voir
`docs/NPC_DIALOGUES_QUESTS_GUIDE.md` sections 6 et 6b.

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
Page docs-site : `worlds.html` (mais voir la note d'obsolescence en section 19). Détail complet des outils de diagnostic (`here`/`debug`) et de l'instrumentation `TP-TRACE` : [docs/TRAVEL.md](TRAVEL.md).

| Commande | Effet | Persistance |
|---|---|---|
| `/rpgadmin worldportal create <id> <destinationWorld> [world_spawn\|random_safe]` | Crée un portail simple depuis la sélection `zone wand` ; stratégie `world_spawn` par défaut si omise. | oui |
| `/rpgadmin worldportal info <id>` | Monde source, bornes, destination, actif/inactif, stratégie, largeur × hauteur × profondeur, centre, répit d'arrivée (global). | non |
| `/rpgadmin worldportal list` | Liste les portails simples. | non |
| `/rpgadmin worldportal enable <id>` / `disable <id>` | Active/désactive le déclenchement (la configuration est conservée, désactivé = aucun effet à l'entrée). | oui |
| `/rpgadmin worldportal delete <id>` | Supprime définitivement (fichier + mémoire). | oui |
| `/rpgadmin worldportal here` | **Diagnostic** : liste TOUS les portails simples dont la zone contient la position actuelle (contrairement au jeu, qui n'en consulte qu'un seul) — révèle les chevauchements invisibles. | non |
| `/rpgadmin worldportal debug show\|hide <id>` / `showall` / `hideall` | **Diagnostic** : affiche/masque le contour d'un (ou tous les) portail(s) simple(s) par particules + étiquette flottante (jamais de bloc modifié). | non (purement visuel) |

Stratégies de destination (`travel.model.DestinationStrategy`, vérifié dans le code) :
-   **`WORLD_SPAWN`** — `World#getSpawnLocation()` du monde destination, résolue à chaque activation.
-   **`RANDOM_SAFE`** — position aléatoire sûre autour du spawn du monde destination (`travel.RandomSafeLocationFinder`), repli automatique sur `WORLD_SPAWN` si aucune position sûre trouvée. Réglages (`config.yml` → `travel.random-safe-arrival`, vérifiés dans `RandomSafeArrivalConfig`/`config.yml`) : `min-radius: 500`, `max-radius: 5000`, `max-attempts: 20` (distance autour du **spawn du monde**, pas du portail).

### Avertissement avant l'entrée dans le Wild (issue #161)

Avant toute téléportation vers `travel.wild-world`, le joueur reçoit un avertissement de danger **générique** et doit confirmer explicitement. Implémentation : `travel.WildEntryWarningService` (un `WorldPortalEntryGuard`, composé avec `ClaimWorldAccessGuard`). Détail complet : [docs/TRAVEL.md](TRAVEL.md).

-   **Aucune inspection d'inventaire** (décision du 2026-10-07, qui remplace la conception initiale de #26 partie B) : ni nourriture, ni arme, ni outil, ni Rune de rappel, ni kit de départ, ni « gear score ». L'ancien avertissement « vous partez sans moyen de rappel », qui lisait l'inventaire, a été retiré.
-   **Texte** : « Le Wild est une zone dangereuse. Le PvP y est autorisé : d'autres joueurs peuvent vous attaquer. Vous pouvez mourir et perdre les objets de votre inventaire. Voulez-vous continuer ? » + « Le Garde peut vous renseigner sur les conditions actuelles du Wild. » L'état jour/nuit/météo n'est **jamais** affiché ici (c'est le Garde qui renseigne, section 4).
-   **Trois actions** : « Entrer dans le Wild », « Entrer et ne plus afficher cet avertissement », « Annuler ». **Fermer la fenêtre n'exécute rien** = annulation.
-   **Option persistante** : `player_variables` → `WILD_ENTRY_WARNING_HIDDEN` = `"true"` (aucune migration de schéma ; absente = avertissement affiché). Écrite **uniquement** sur une confirmation réelle de départ, jamais sur une annulation/fermeture. Masque l'avertissement **seul** : préparation, messages de réussite/échec et contrôles de sécurité restent en place. `/rpgadmin player resetnew` la rétablit (il efface toutes les variables).
-   **Retour d'attente** : « Recherche d'un point d'arrivée sûr… Téléportation en préparation. » dès la confirmation (ou directement si l'avertissement est masqué), puis réussite ou échec explicite — jamais un faux « Téléportation réussie. » après un échec.
-   **Pas de boucle de menu, une seule demande en vol** : un joueur qui annule et reste dans le portail ne revoit pas la fenêtre (les gardes ne sont consultés que sur une vraie transition extérieur → intérieur) ; un double-clic ou plusieurs pas dans la zone ne lancent jamais deux recherches/téléportations ; une déconnexion annule le départ différé.
-   **Présentation** : fenêtre Paper native ou chat cliquable selon `config.yml` → `dialogue.renderer` (même préférence que les dialogues), avec repli chat automatique.
-   **Latence** : chaque passage émet une ligne `[TP-LATENCY]` distinguant recherche / chargement de chunks / téléportation (voir [docs/TRAVEL.md](TRAVEL.md)). Aucun contrôle de sécurité n'a été allégé.

**Investigation en cours (bug de téléportation automatique dans le Hub)** : `WorldPortalTeleportListener` applique un répit d'arrivée global de 40 ticks (2 s) après connexion/téléportation externe avant qu'un portail simple ne puisse se déclencher automatiquement. Ce répit **retarde** un déclenchement plutôt que de le supprimer si la zone couvre réellement le point d'arrivée — voir [docs/TRAVEL.md](TRAVEL.md) pour l'analyse complète et les outils de diagnostic (`here`/`debug`/logs `TP-TRACE`) ajoutés pour confirmer la cause exacte sur le serveur réel avant tout correctif définitif.

---

## 7. Mondes

Vérifié dans `RpgAdminCommand.java` (`handleWorld*`), `hub/HubWorldRulesService.java`, `config.yml`, et [VERYGAMES.md](deployment/VERYGAMES.md).

### Spawn Minecraft vs Multiverse vs RPGQuest — bien distinguer

| Spawn | Défini par | Référence gameplay ? |
|---|---|---|
| Spawn Minecraft/Paper (`World#getSpawnLocation()`) | Génération du monde ou `/setworldspawn` vanilla | Non — seulement utilisé en repli par `DestinationStrategy.WORLD_SPAWN` et par `/rpgadmin world tp` |
| Spawn Multiverse (`/mv setspawn`) | Commande Multiverse-Core | **Non** — `VERYGAMES.md` est explicite : « le spawn Multiverse n'est pas la référence gameplay » |
| Spawn RPGQuest (village) | `/rpgadmin spawn set` | **Oui** — seule source de vérité pour le point d'apparition du village, gérée par `SpawnService` |

**Redirection à la connexion (issue #87)** : à chaque connexion, `SpawnService.applyJoinSpawnPolicy`
(règle pure `spawn.JoinSpawnPolicy`) ne redirige vers le spawn du village **que** si un nouveau
joueur (`Player#hasPlayedBefore() == false`, heuristique **peu fiable** — voir plus bas) est placé
par Paper dans le **monde principal** (`getServer().getWorlds().get(0)`) ou le **monde Hub**
(`hub.world`). Si Paper restaure déjà le joueur dans un autre monde chargé (`wild`, `claims`…), sa
position est **conservée telle quelle**, quel que soit `hasPlayedBefore()` : une reconnexion depuis
le Wild reste dans le Wild (ce n'est **pas** un raccourci gratuit vers le Hub). Repli légitime :
si le monde précédent n'est plus chargé, Paper renvoie lui-même le joueur au monde principal, ce
qui retombe sur la redirection village. `SpawnService.handleRespawn` (réapparition **après la
mort**) est indépendant et redirige toujours vers le village. Log par connexion :
`join_restore player=<uuid> world=<w> action=KEEP_LAST_LOCATION|REDIRECT_TO_VILLAGE_SPAWN`.
> `hasPlayedBefore()` renvoie `false` pour un joueur déjà venu si la métadonnée Bukkit
> `bukkit.firstPlayed` de son `playerdata` manque (migration/transfert de serveur, UUID
> hors-ligne↔en-ligne, restauration de sauvegarde) — d'où la double condition sur le monde
> d'arrivée plutôt qu'une confiance aveugle en cette méthode.

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
-   **aucun spawn de mob indésirable, quelle que soit la raison** (issues #121/#155) : tout mob
    hostile (`Monster`), `ENDERMAN` (même neutre), `WANDERING_TRADER`/`TRADER_LLAMA` — plus seulement
    les spawns `NATURAL` (un spawn `CHUNK_GEN`/`SPAWNER` est aussi bloqué). Nettoyage ciblé des
    entités déjà présentes au démarrage (chunks déjà chargés, `sweepAlreadyLoaded`) et à chaque
    nouveau chargement de chunk (`EntitiesLoadEvent`) — jamais Citizens/PNJ, jamais un animal
    passif, jamais un chargement forcé de tout le monde.
-   **dégâts à une entité protégée toujours annulés** (issues #30/#31) : tout dégât **causé par un
    joueur** (mêlée ou projectile) à une entité vivante non-joueur (animal, mob décoratif...) est
    annulé — sauf un PNJ Citizens. Bypass **explicite et distinct** de la construction :
    `rpgquest.admin.hub.combat` (jamais accordé implicitement par `rpgquest.admin.world`).
-   **faim/saturation jamais réduites** (issue #33, `hub.HubComfortService`) : `FoodLevelChangeEvent`
    annulé pour toute diminution (sprint/sauts/épuisement) ; une garde périodique (1 s) neutralise
    aussi l'épuisement silencieux (`exhaustion`/`saturation` internes, sans événement dédié). Vie,
    faim et saturation restaurées au maximum à la connexion, à tout changement de monde vers le Hub
    et à une réapparition dans le Hub — jamais dans les autres mondes. Strictement scopé par
    `isHub(player)` à chaque appel : le Wild garde sa survie normale (couvert automatiquement). Un
    signalement (#159, 2026-10-04) rapportant la faim bloquée aussi dans le Wild n'a trouvé aucune
    cause côté code/config à l'audit ; une trace `[HUNGER-TRACE]` (temporaire) log désormais toute
    annulation réelle avec le monde concerné, pour confirmer/infirmer depuis les logs serveur.
-   **secours gratuit dans le Hub via la Rune de rappel** (issue #154) : la même Rune
    (`ItemTravelDefinition#freeRescueWorld = hub.world`) téléporte, dans le Hub uniquement,
    immédiatement et sans canalisation/cooldown vers `SpawnService#resolve` — pour un joueur coincé
    sans pouvoir poser/casser. Comportement Wild (restriction + canalisation + cooldown) inchangé.
    `hub.HubRescueFallbackService` complète un éventuel inventaire plein par un accès graphique
    minimal (1 bouton), et `player.StarterKitListener` ne marque plus la Rune distribuée si elle
    n'a pas pu être réellement ajoutée (retenté à la connexion suivante).
-   claims interdits (`ClaimService` refuse toute création dans le monde exact de `hub.world`, voir section 8).

Log attendu au démarrage/chargement :
```
Règles du monde Hub appliquées : world_hub (jour et météo permanents).
```
Son absence après un redémarrage signale que `world_hub` n'a pas été détecté (nom de monde incorrect au transfert, ou `hub.world` mal configuré).

### Multiverse-Core en production

`softdepend`/dépendance dure : RPGQuest **ne dépend techniquement d'aucun** des trois plugins externes pour démarrer (`plugin.yml` ne déclare que `softdepend: [Citizens]`) — Multiverse-Core est néanmoins installé et utilisé en production VeryGames pour gérer `world_hub` (import, listing). Voir section 1 pour `/mv import`/`/mv list`.

### Mondes futurs (`wild`, claims...) — état réel

Aucune trace dans le code actuel (`RpgAdminCommand`, `WorldService`) d'un monde `wild` prédéfini ou d'un traitement spécial par nom de monde autre que `hub.world` : `/rpgadmin world create <name>` crée un monde générique en environnement `NORMAL`, sans distinction. Un monde `wild` mentionné dans `docs-site/worlds.html` (exemple d'usage avec `worldportal`) est un **exemple d'utilisation de la fonctionnalité générique**, pas un monde livré ou codé en dur — à traiter comme *Prévu / exemple*, pas comme une fonctionnalité dédiée implémentée. **Depuis l'issue #168**, une exception ciblée existe : la section `wild:` de `config.yml` (ci-dessous) applique des règles hostiles aux mondes qu'elle liste explicitement.

### Créatures hostiles de jour dans le Wild (issue #168)

Vérifié dans `src/main/java/com/lodygames/rpgquest/wild/WildHostileRulesService.java` et la section
`wild:` de `config.yml`. **Ne s'applique qu'aux mondes listés** (`wild.worlds`, vide = le seul
`travel.wild-world`) : le Hub reste sans mob hostile, les protections de Claims sont inchangées, et
tout monde non listé conserve exactement les règles vanilla. Plusieurs mondes Wild sont supportés
sans changement de code.

Trois mécanismes **distincts** — le ticket demandait explicitement de ne pas confondre « ne plus
brûler » et « apparaître de jour » :

| Mécanisme | Clé | Comportement |
|---|---|---|
| Immunité au soleil | `wild.sun-immunity` | Annule **uniquement** l'embrasement spontané par la lumière du jour (l'`EntityCombustEvent` qui n'est ni `…ByBlock` ni `…ByEntity`). Feu, lave, flèches enflammées et tous les dégâts de combat restent **strictement normaux** : aucun `EntityDamageEvent` n'est touché. Monstres uniquement, jamais les animaux passifs. |
| Apparitions diurnes | `wild.daylight-spawns.*` | Le spawn vanilla refuse la surface éclairée ; on le **complète de jour seulement**, par une passe périodique autour des joueurs. La nuit, le service ne fait **rien** : les apparitions nocturnes restent celles du jeu, jamais doublées. **Aucune nuit permanente simulée** — le cycle jour/nuit réel est intact. |
| Araignées agressives | `wild.aggressive-spiders` | En journée, une araignée vanilla est neutre : on lui réattribue une cible valide à portée **seulement si elle n'en a pas déjà une** (jamais de vol de cible), et jamais sur un joueur exempté (créatif, spectateur, invulnérable, mort). |

**Bornes de coût et de sûreté** : une seule tâche périodique pour tout le serveur (jamais une tâche
par mob), `attempts-per-player` tentatives par passe pour **au plus une** apparition retenue,
plafonds `max-per-player` et `max-per-world`, anneau `min-distance`/`max-distance` autour du joueur,
et **aucune génération de chunk** (une tentative dans un chunk non chargé est simplement
abandonnée). Les créatures ajoutées passent par `World#spawnEntity`, donc un `CreatureSpawnEvent`
normal : les mobs spéciaux (#169), les règles du Hub et les protections de Claims continuent de
s'appliquer et peuvent annuler l'apparition. Elles ne sont pas marquées persistantes — le despawn
naturel s'applique.

Les espèces à conditions spéciales (`PHANTOM`, `WARDEN`…) sont **volontairement absentes** de
`types` par défaut : elles exigent un traitement explicite, pas un spawn de masse.

### Waypoints par instance de biome (issue #124)

Vérifié dans `src/main/java/com/lodygames/rpgquest/waypoint/` et `docs/WAYPOINTS.md`. **Distinct des
Waystones** (`waystone.WaystoneService` — réseau de voyage sur grille, retour au Hub) : les waypoints
sont des repères physiques persistants, partagés, générés **par instance réelle de biome** dans
`travel.wild-world`, à découvrir par interaction explicite.

- **Instance de biome** = `(monde, type de biome, tuile de `region-size` blocs)` — voir
  `waypoint.model.BiomeInstanceKey`. Deux zones du même biome séparées de plus de `region-size` ont
  deux waypoints distincts ; jamais un simple `biomeType -> waypoint`. Compromis MVP documenté dans
  `docs/WAYPOINTS.md` (approximation par tuile, pas de flood-fill de blob de biome contigu).
- **Génération paresseuse et unique** : à l'entrée d'un joueur dans une instance sans waypoint
  (`PlayerMoveEvent` throttlé, `travel.waypoint.move-throttle-millis`), le moteur cherche **une
  seule fois** un emplacement de surface à `min-distance..max-distance` blocs (jamais au pied du
  joueur), qui reste dans la même instance, sur un sol sûr (`RandomSafeLocationFinder`), hors claim
  et sans écraser de construction. Verrou mémoire + index unique `(world, biome_instance)` : deux
  entrées simultanées ne créent jamais deux waypoints. Échec → retry borné (30 s → 1 h), pas de
  boucle.
- **Rendu versionné** (`waypoint.render.WaypointModelRegistry`) : la logique ne stocke qu'un numéro
  de version (`waypoints.model_version`). V1 = support en `COBBLESTONE_WALL` + `GOLD_BLOCK` +
  `STONE_BUTTON` latéral. L'identité (`id`, `biome_instance`) est **indépendante du rendu** : un
  futur modèle v2 ne change ni l'identité ni les découvertes.
- **Découverte** : la proximité ne découvre rien. Seul un **clic droit sur le bouton** valide la
  découverte, persistée par UUID (`waypoint_discoveries`). Retour joueur : message + son.
- **Protection MVP** (`WaypointProtectionListener`, même esprit que `ClaimProtectionListener`) :
  blocs constitutifs protégés contre casse joueur, explosion, piston, feu (burn/ignite), fluide
  entrant, entités (sable/gravier/enderman). Bypass `rpgquest.admin.world`. La protection
  fonctionnelle anti-enfermement de proximité est **hors périmètre** → issue #122.
- **Config** : `travel.waypoint.*` dans `config.yml` (`enabled`, `region-size`, `min-distance`,
  `max-distance`, `candidate-attempts`, `move-throttle-millis`, `minimum-spacing`, `model-version`,
  `hub-enabled` — issue #149, étend ce mécanisme au Hub, voir ci-dessous).
- **Persistance** : tables `waypoints` (définition monde) et `waypoint_discoveries` (progression
  joueur) — migration V18, séparation stricte, aucun couplage MariaDB supplémentaire.
- **Hors périmètre MVP** : téléportation / fast travel, coût, menus, waypoint de quête, éditeur
  PlugAdmin complet, lecture `/waypoints` (une lecture Control Panel est prévue par #124 mais non
  livrée dans ce MVP — `WaypointService` expose déjà `all()` / `byId()` / `discoveryCount()`).

### Réseau de voyage / bornes (issues #132/#150/#151/#149/#133/#135)

Vérifié dans `src/main/java/com/lodygames/rpgquest/travel/beacon/` et `docs/TRAVEL.md` (section
dédiée, détail complet). Fournit le fast travel que les waypoints eux-mêmes n'apportent pas :
**borne** physique (même support qu'un waypoint, `DIAMOND_BLOCK` + bouton en bois configurable,
jamais fusionnée avec les tables waypoint/waystone) dont le bouton ouvre un **menu graphique** à
trois catégories, toutes revalidées fraîchement à chaque clic :

- **Waypoints découverts** : ouvre d'abord un **choix du monde** (Hub/Wild toujours proposés même à
  0 découverte, tout autre monde extensible dès qu'une découverte active y existe), puis la liste —
  uniquement celle du joueur courant, **filtrée au monde choisi**. Pagination (45/page), recherche
  graphique via une enclume virtuelle (`InventoryType.ANVIL`, `repairCost` **et**
  `repairCostAmount` à 0 — aucun coût XP, `PENDING MANUAL VALIDATION` : non simulable par
  MockBukkit, voir TRAVEL.md, insensible casse/accents, filtre sur le **nom d'affichage** — voir
  ci-dessous, indicateur de recherche active + nombre de résultats + bouton d'effacement),
  revalidation stricte (`hasActivelyDiscovered`) et arrivée sûre
  (`RandomSafeLocationFinder#findAtColumn`).
- **Mon claim** (#151) : résout le claim courant via `ClaimService#mainClaimOf` (jamais une
  coordonnée copiée) ; absent → icône grisée + message, jamais un bouton muet. Arrivée au centre du
  claim, vérifiée **dans** son cuboïde actif.
- **Villages** (#151) : centres administrés (`VillageCenter`, table `village_centers`, identité
  **indépendante du monde** — plusieurs centres possibles dans `world_hub`). Arrivée à la position
  et orientation exactes administrées (même confiance qu'un spawn).

**Noms de waypoints (#133/#135)** : chaque waypoint a un **nom d'affichage** unique et persistant
(`waypoint.display_name`), identité lisible principale — le biome reste une métadonnée secondaire
(deux waypoints du même biome n'affichent plus jamais le même libellé). Réserve statique bundlée
(`waypoint-names.txt`, lue par `WaypointNameCatalog`, **jamais un appel IA au runtime** — l'issue
#148 l'enrichira plus tard) ; dédoublonnage à l'import (casse/accents/espaces) ; attribution
synchrone sans doublon à la génération (+ index SQL unique en défense) ; nom de secours
(`"Avant-poste N"`) si la réserve est épuisée ; migration **V22** attribue un nom aux waypoints déjà
existants (id et découvertes joueurs inchangés). **Noms lisibles** (retour joueur 2026-10-04) : la
réserve ne concatène plus deux mots sans séparateur (ex. l'ancien `Lacgivre` → `Lac de Givre`,
grammaire `<nom> de <nom>` ou `<adjectif accordé> <nom>`) ; migration **V23** renomme les noms déjà
attribués via une correspondance figée ancien→nouveau (id/découvertes inchangés, unicité
revérifiée). **Retour « déjà découvert »** : un reclic sur un waypoint déjà découvert affiche
désormais `Waypoint déjà découvert : <nom>` (silence complet auparavant), sans nouvelle récompense.

**Correction d'un bug de clics (validation en jeu)** : destination, retour et recherche restaient
silencieux une fois le menu ouvert — l'état du menu (`BeaconMenuSession`) était posé **avant**
l'ouverture de l'inventaire, effacé aussitôt par la fermeture synchrone de l'ancien menu (même
classe de bug que la navigation du journal de quêtes, #11). Corrigé en posant la session **après**
l'ouverture partout.

Placement/administration : `/rpgadmin travel beacon set` (borne **Wild**, position réelle,
idempotent), `/rpgadmin travel village sethub|set|remove|enable|disable|list` (centres, `sethub`
réutilise le spawn du Hub déjà configuré, jamais une coordonnée inventée). **Génération automatique
Hub (#149)** : à l'entrée d'un joueur dans une instance de biome du Hub sans waypoint,
`WaypointService#ensureGenerated` (même mécanisme exact que #124, exposé publiquement) génère le
waypoint, puis `TravelBeaconService` appaire une borne **distincte** à proximité (anneau
configurable `travel.beacon.hub-generation.pair-min-spacing/pair-max-spacing`, défaut 6..16) une
fois ce waypoint réellement persisté — verrou mono-vol + retry borné, jamais de doublon même avec
plusieurs joueurs ou après redémarrage ; jamais dans le Wild, où seul le placement administré
existe. Gating indépendant : `travel.waypoint.hub-enabled` (coupe la génération Hub seule) et
`travel.beacon.hub-generation.enabled` (coupe l'appariement de borne seul, le waypoint continue de
se générer). Tables dédiées `travel_beacons` (migration V19, colonne `biome_instance` V21) et
`village_centers` (migration V20).

**Control Panel — `/travel` (issue #152, terminé)** : consultation **lecture seule** des
waypoints/bornes réellement persistés (action agent `travel.catalog`, réutilise
`WaypointService#all()`/`TravelBeaconService#all()`, jamais un scan monde). Listes séparées avec
pairage waypoint↔borne visible (y compris les waypoints du Hub sans borne), recherche/filtre par
monde, pagination, horodatage de fraîcheur, distinction explicite **enregistré** (dernier relevé)
vs **vérifié physiquement** (`/rpgadmin travel diagnose`). Permission dédiée `TRAVEL_READ` ; aucun
éditeur (hors périmètre explicite du ticket). **Libellés clarifiés** (retour joueur 2026-10-04) :
« Apparié » (oui/non ambigu, confondu avec un statut de découverte joueur) remplacé par « Borne
associée »/« Waypoint associé », affichant directement l'id réel de l'association plutôt qu'un
simple booléen, avec une phrase explicative au-dessus de chaque tableau.

**Densité des bornes du Hub — audit et correction (issue #156)** : l'appariement d'une borne n'est
jamais déclenché par un balayage du monde, uniquement par le passage d'un joueur dans une instance
de biome du Hub (`TravelBeaconService#handleHubMovement`, throttlé par
`travel.waypoint.move-throttle-millis`). Un **défaut du déclencheur** laissait des instances
durablement sans borne : la persistance du waypoint est asynchrone, donc le tout premier passage ne
peut jamais apparier, et une sortie anticipée « le joueur est déjà dans cette instance » empêchait
ensuite toute nouvelle tentative jusqu'à ce qu'il quitte l'instance puis y revienne. Mesure sur le
serveur DEV avant correction : **22 waypoints du Hub pour 13 bornes**, les 13 bornes toutes créées
entre 17 secondes et 11 h 50 **après** leur waypoint (jamais simultanément), et les 9 instances
sans borne toutes situées à **88 blocs ou plus** de la borne la plus proche — donc jamais bloquées
par l'espacement minimal. L'évaluation se poursuit désormais tant que l'instance n'a pas sa borne ;
le coût reste borné par le throttle, le verrou mono-vol et le backoff de réessai. **Aucun ratio
borne/waypoint n'a été introduit et aucune politique de génération n'a été modifiée** : la densité
reste celle d'« une borne par instance de biome visitée ».

Le diagnostic correspondant est administrable et strictement en lecture seule, exposé par la même
source des deux côtés (`TravelBeaconService#hubPairingGaps`) : section **« Appariements manquants
dans le Hub »** de `/travel` (via `travel.catalog`) et `/rpgadmin travel diagnose`. Pour chaque
instance encore sans borne il indique la **cause** — *jamais tenté depuis le démarrage* (déclencheur),
*appariement en cours*, ou *N essai(s), candidats refusés* (terrain ou espacement) — avec la distance
à la borne la plus proche et le délai avant le prochain essai. Ces compteurs vivent en mémoire : un
redémarrage les remet à zéro, ce que l'affichage annonce explicitement.

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

### Accès au monde des claims et retour au Hub (issues #21/#22/#23)

Vérifié dans `claim.ClaimWorldAccessGuard`, `claim.ClaimWorldSafetyListener`, `travel.CompositeWorldPortalEntryGuard`, `dialogues/guide.yml`, `dialogues/jo.yml`, `quests/crystal_hunt.yml`.

**Condition réelle du premier claim** : la variable joueur `CLAIM_TIER_1 == "true"`, accordée par la récompense `VARIABLE` de `rpgquest:crystal_hunt` (dernière quête de `main_story`, rendue au Garde) **ou**, depuis l'issue #179, par `rpgquest:guard_tier1` (chemin parallèle et plus simple, même Garde, voir section 3 « Chaîne de paliers du Garde »). `/rpgadmin player resetnew` l'efface (avec tous les claims), `/claim admin resettier1` la met à `"false"`.

**Portail Hub → `claims` fermé tant que le premier claim n'est pas débloqué** : `ClaimWorldAccessGuard` (un `travel.WorldPortalEntryGuard`, composé avec l'avertissement d'entrée dans le Wild) refuse l'entrée d'un joueur qui n'a ni `CLAIM_TIER_1 == "true"` ni claim existant — **aucune téléportation**, message d'orientation vers Jo / le Guide. Seul `rpgquest.admin.world` passe outre ; aucune permission de build/admin ne contourne la règle par accident.

**Aucune entrée sans moyen de repartir (issue #22)** : être éligible ne suffit pas. **Avant** la téléportation, `ClaimWorldAccessGuard` exige, via l'unique `claim.ClaimReturnService`, que la destination de retour se résolve (`spawnService::resolve` — exactement la cible de la Pierre de retour, jamais une copie) **et** que le joueur détienne une `rpgquest:pierre_retour` ou puisse en recevoir une **dans son inventaire**. Le contrôle préventif ne dépose jamais l'objet au sol (le joueur est encore au Hub). Sinon l'entrée est **refusée avec le motif exact** (« libère un emplacement » / « retour indisponible, signale-le à un administrateur ») : le filet d'arrivée agit trop tard, au portail rien n'est encore irréversible. Le bypass `rpgquest.admin.world` n'est jamais refusé, mais reçoit tout de même la Pierre quand c'est possible.

**Aucun joueur coincé, retour Hub sans commande** : `ClaimWorldSafetyListener` (sur `PlayerChangedWorldEvent` / `PlayerJoinEvent`) donne automatiquement une `rpgquest:pierre_retour` (voyage claims → Hub, clic droit, jamais consommée) à tout joueur éligible qui arrive dans le monde des claims sans en avoir une ; un joueur non éligible qui s'y retrouve autrement (`/tp`, reconnexion) est renvoyé au Hub. Inventaire plein : le joueur est déjà sur place, l'objet tombe donc **à ses pieds** et le message le dit (il annonçait « tu reçois » dans les deux cas — issue #22). `/claim admin sendhome` et `/spawn` restent auxiliaires.

**Dialogues alignés** : `guide.yml` (`help_claims`) énonce le prérequis réel (finir l'histoire principale au Garde, *puis* voir Jo) ; `jo.yml` adapte son texte aux 3 états — non débloqué (« Comment obtenir mon premier terrain ? », via `negate` sur `VARIABLE_EQUALS CLAIM_TIER_1`), débloqué sans claim (remet l'Acte), claim existant (retour / limites). Le choix « Obtenir une Pierre de retour » n'exige plus un claim **déjà posé** (`HAS_MAIN_CLAIM`) mais seulement `CLAIM_TIER_1 == "true"` (issue #22) : ce recours devenait inaccessible exactement quand il sert, pour un joueur débloqué qui n'a pas encore choisi son terrain.

**Prérequis opérationnels de ce parcours (non portés par le code, à provisionner sur chaque serveur)** :

- **4 PNJ Citizens liés** — `guide`, `libraire`, **`guard`**, `jo` (via `/rpgadmin npc tag <id>`, jamais par le nom affiché ; mapping en base `npc_citizens_bindings`). Le PNJ **`guard`** démarre `first_steps` **et** démarre/valide `crystal_hunt` : sans lui, `CLAIM_TIER_1` ne peut **jamais** être accordé et le déblocage du claim est impossible (voir `docs/NPC_DIALOGUES_QUESTS_GUIDE.md` §1b).
- **`dialogues/guard.yml` à jour** — doit contenir la branche `crystal_hunt` (choix « J'ai entendu dire… » + nœud `crystal_hunt_accepted`). Un `guard.yml` antérieur ne permet de démarrer que `first_steps`.
- **Un World-Portal RPGQuest** `world_hub → claims` (`world-portals/hub_to_claims.yml`, `destination-world` = `claims.world`). `ClaimWorldAccessGuard` ne contrôle que les World-Portals : une téléportation par un autre moyen (`/mv tp`, `/tp`) est rattrapée par `ClaimWorldSafetyListener`, pas par le garde d'entrée.

**Compte OP = bypass, pas un bug** : `rpgquest.admin.world` (défaut `op`, aucun plugin de permissions requis) contourne le **contrôle d'éligibilité** de `ClaimWorldAccessGuard` (entre sans contrôle) et n'est **jamais renvoyé** par `ClaimWorldSafetyListener` (il est venu volontairement). Toute validation « le portail refuse » doit donc se faire avec un **compte non opéré** — un test OP produit exactement le symptôme « j'entre sans avoir débloqué ». Depuis l'issue #22, en revanche, le bypass **ne dispense plus de la Pierre de retour** : elle est garantie à l'entrée comme à l'arrivée. Le dispenser des deux laissait un administrateur éligible sans aucune sortie — c'est la cause racine du blocage signalé le 05/10/2026. Les décisions des deux composants sont journalisées (`[claims-access]` / `[claims-safety]` / `[claims-retour]`).

### Prévu / TODO

-   **Agrandissement du claim principal (`TIER_1` → `TIER_5`)** : implémenté depuis l'issue #179 via `ClaimService#upgradeTier`, déclenché par la chaîne de quêtes du Garde (voir section 3). Toujours **hors périmètre** : agrandissement au-delà de `TIER_5`, ou largeur/hauteur indépendantes du modèle de palier (seul le **nombre** maximal de claims augmente encore avec le niveau `GLOBAL`, +1 tous les 10 niveaux). Aucun avantage payant n'est prévu (politique confirmée dans `docs/CLAIMS.md`).
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

Les commandes d'administration (`/rpgadmin mob spawn|list|inspect|reload|metrics`, `rpgquest.admin.world`) sont documentées en **section 2 (Administration RPGQuest)** — non répétées ici. Depuis l'issue #169 (lot 1), la **création/modification/activation/désactivation d'un profil, le réglage du tirage aléatoire Wild, et le spawn/nettoyage d'instances de test se font depuis le Control Panel (page « Mobs spéciaux & boss ») — aucune YAML ni commande à écrire pour un usage courant.**

Exemple minimal (registry `id`, `entity-type`, `name`, `spawn-chance` obligatoires ; `category` optionnelle, défaut `SPECIAL` pour rétro-compatibilité) :
```yaml
id: rpgquest:golden_creeper
category: SPECIAL
entity-type: CREEPER
name: "<gold><bold>Creeper Doré</bold></gold>"
spawn-chance: 0.005
abilities:
  - type: STRONGER_EXPLOSION
    radius-multiplier: 1.5
```

### Éditeur de mobs : catalogues réels du serveur (issue #172)

**Défaut corrigé, diagnostiqué sur le parcours réel** : la page `/mobs` n'émettait **aucune**
`<datalist>`. Le type d'entité (champ *obligatoire*), la particule, le son, les mondes et les
biomes étaient de simples champs texte avec un placeholder — il fallait donc connaître
l'identifiant vanilla exact pour créer un profil. L'action serveur, elle, fonctionnait déjà
(`mob.definition.create` → `CREATED`), ce qui explique pourquoi l'édition d'un profil existant
marchait alors que la création semblait impossible.

Nouveau relevé **`mob.catalogs`** (permission `MOB_READ`, bouton « Catalogues Minecraft » sur
`/mobs`) : lit les **registres réels de la version installée** — types d'entité vivants et
invocables, particules, sons, biomes, mondes chargés, et la liste des particules qui acceptent
réellement une couleur (d'après le type de données Paper du type choisi, jamais une supposition).
Relevé constaté sur le DEV : 91 entités, 115 particules, 1838 sons, 65 biomes.

L'éditeur s'appuie dessus : liste **recherchable** pour entité / particule / son, **multisélection**
pour mondes et biomes (même composant que les prérequis de quête, #163 ; le champ soumis reste la
liste CSV attendue par l'action, le contrat serveur ne change pas). Sans relevé disponible, un
bandeau le dit et les champs restent en saisie libre — jamais une liste inventée.

### Aide par champ des formulaires de mobs (issue #169)

Chaque champ des formulaires « Mobs spéciaux & boss » porte une aide **rendue sous le champ**
(jamais un simple `placeholder` ni une infobulle : invisibles sur mobile, et un placeholder
disparaît dès la première frappe). L'aide énonce quatre choses, dans cet ordre : ce que le champ
contrôle **en jeu**, un **exemple**, le **défaut réellement appliqué par le moteur**, et — pour un
champ optionnel — ce qui se passe **s'il reste vide**.

Règles tenues par ce bloc d'aide :

- **Aucun défaut inventé.** Les valeurs annoncées sont relevées dans le code
  (`SpecialMobDefinition`, `SpecialMobDefinitionParser`, `MobSpawnSettings.defaults()`,
  `AgentActionCatalog`). Quand il n'existe pas de défaut, l'aide écrit « Obligatoire » ou
  « Aucun » — par exemple les cinq réglages d'« Invocation de renforts » sont *obligatoires dès que
  la case est cochée*, le moteur n'ayant aucune valeur de repli.
- **Bornes et valeurs spéciales seulement si elles existent.** `xp-reward: 0` signifie
  explicitement *aucune XP* (distinct du champ vide, qui laisse l'XP vanilla) ;
  `summon_cooldown_seconds: 0` signifie *aucun délai* ; le seuil de rage est **strictement** entre
  0 et 1 (ni 0 ni 1) ; la chance d'invocation est supérieure à 0 et au plus 1.
- **Préremplissage uniquement là où le moteur a un défaut.** À la création, seuls « Profil actif »
  (coché) et la catégorie (Spécial) le sont ; les statistiques restent **vides**, parce que
  `mob.definition.update` **remplace le profil entier** — une valeur préremplie serait enregistrée
  et le mob cesserait d'hériter de l'attribut vanilla. La chance individuelle est préremplie à 0.01
  et l'aide précise que c'est une **suggestion du panel**, pas un défaut du moteur.
- **À la modification, rien n'est substitué** : chaque champ reçoit la valeur du profil, un champ
  absent reste vide, et les exemples vivent dans l'aide — jamais dans un attribut `value`.

**Deux imprécisions de l'ancienne aide corrigées au passage**, après relecture de
`SpecialMobService` : le champ *Particule* n'est **pas** une aura continue de boss mais une bouffée
émise **une seule fois à l'apparition** (l'aura d'un boss est un effet fixe du moteur, indépendant
de ce champ) ; et *Rayon d'explosion* n'est retenu qu'en **partie entière** (`intValue()`).

**Piège de soumission silencieuse également corrigé** : deux sections de capacités sont repliées
par défaut et contiennent des champs numériques contraints. Dès qu'une valeur devenait invalide, le
navigateur refusait de soumettre **et** ne pouvait pas focaliser un champ caché dans un `<details>`
fermé — le bouton semblait ne rien faire, sans aucun message. Le formulaire est désormais
`novalidate` et s'appuie sur la validation métier qui existe déjà côté panel **et** côté plugin.

**Catalogue** : recherche par nom ou identifiant, filtres **Boss / Mob spécial / Désactivés**.

**Nom affiché** : plus besoin d'écrire du MiniMessage — couleur au clic, cases de style et aperçu.
Voir § 4 « Couleurs et styles sans écrire de MiniMessage » (issue #195) ; le composant est partagé
avec les textes de dialogue et préserve les noms déjà écrits avec plusieurs styles.

**Ce qui reste explicitement hors de ce lot** : un profil à base passive ne devient **jamais**
agressif implicitement (#201) ; les renforts multi-types avec quantité par type et les catalogues
d'icônes/récompenses (#196) sont des lots distincts.

### Catégorie et attributs (issue #169, lot 1)

- `category` : `SPECIAL` (éligible au tirage aléatoire Wild, nom coloré) ou `BOSS` (jamais tiré au hasard — n'apparaît que par spawn de test admin ou, plus tard, objectif de quête #173 — nom coloré + particules colorées en continu + barre de vie Adventure).
- `enabled` (optionnel, défaut `true`) : désactivé = ignoré par le tirage aléatoire ET par le spawn de test, jamais supprimé du disque.
- `knockback-resistance` (0 à 1), `scale` (taille relative, 1.0 = normale) : attributs vanilla supplémentaires, en plus de `health`/`damage`/`speed`/`armor` déjà existants.
- `creeper-explosion-radius` : uniquement si `entity-type: CREEPER` — refusé (erreur de chargement, jamais une option silencieusement ignorée) pour tout autre type.

### Tirage aléatoire Wild : sémantique à deux étages (issue #169, lot 1)

Réglé depuis le Control Panel (`mobs/spawn-settings.yml`, jamais édité à la main) :

1. **Throttle global** (`chance`, 0 à 1) : évalué une seule fois par spawn naturel éligible, *avant* d'examiner les profils individuels. Résout l'ambiguïté d'un pourcentage par-profil cumulé.
2. Seulement si ce tirage réussit, chaque profil `SPECIAL` activé et compatible (type/monde/biome/zone, sous son `max-population`) tire indépendamment sa propre `spawn-chance`.
3. Zéro résultat → rien. Un résultat → appliqué. **Plusieurs résultats simultanés → tirage pondéré explicite** entre eux (poids = la `spawn-chance` de chacun), jamais le premier trouvé dans le registre.
4. `max_simultaneous_special` (optionnel) plafonne le nombre total de mobs `SPECIAL` vivants, toutes définitions confondues, en plus du `max-population` déjà existant par profil.
5. Les profils `BOSS` sont **exclus** de ce tirage automatique, sans exception.
6. Aucun nouveau tirage au chargement de chunk ni à la reconnexion (seul un vrai spawn naturel `CreatureSpawnEvent` déclenche un tirage).

### Capacités (premier lot, issue #171)

Cinq `abilities` réellement implémentées (enum `MobAbilityType`, vérifié dans `src/main/java/com/lodygames/rpgquest/mob/model/MobAbilityType.java`) — ne pas en inventer d'autres :

| type | champs requis | rôle |
|---|---|---|
| `STRONGER_EXPLOSION` | `radius-multiplier` (> 0) | multiplie le rayon d'une explosion vanilla qui prime |
| `EXPLOSIVE_ON_ATTACK` | `power` (> 0), `set-fire`, `trigger-range-blocks` (> 0) | rend une entité passive agressive : explosion réelle en approche, puis mort de l'entité |
| `SPLIT_ON_HIT` | `max-depth` (≥ 1), `max-children-per-hit` (≥ 1), `max-alive-per-parent` (≥ 1, optionnel, défaut 2 — issue #190) | fait apparaître des enfants à chaque coup non mortel ; `max-alive-per-parent` plafonne séparément le nombre d'enfants vivants d'un même parent, pour que des coups répétés sur la même entité avant sa mort ne produisent pas plus de descendants que ce plafond |
| `ENRAGED` | `health-fraction` (0 < x < 1), `speed-multiplier` (> 0), `damage-multiplier` (> 0) | sous le seuil de vie, signal visuel (particule + son) puis bascule en rage une seule fois (jamais réappliqué/cumulé) |
| `SUMMON_ON_DAMAGE` | `summon-entity-type`, `amount` (> 0), `chance` (0 < x ≤ 1), `cooldown-seconds` (≥ 0), `max-alive` (> 0) | invoque des renforts sur dégâts effectifs, cooldown + plafond de renforts vivants ; jamais de cascade (les renforts invoqués sont de simples mobs vanilla, jamais eux-mêmes des mobs spéciaux) |

Le boss de quête (spawn à l'acceptation, identité liée joueur/quête/cycle, objectif de victoire, localisation biome/waypoint) est un développement séparé, pas encore implémenté — ne jamais le présenter comme disponible.

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

### Nom en jeu et apparence d'un PNJ depuis PlugAdmin (issue #165)

Fiche PNJ → **« Nom en jeu & apparence »** (visible dès qu'un PNJ Citizens est lié ; aucune
définition RPGQuest n'est exigée, pour couvrir un Citizens « orphelin » comme Help). Deux
opérations **séparées**, chacune son bouton, pour qu'un échec ne soit jamais ambigu :

| Action agent | Permission | Effet |
|---|---|---|
| `npc.citizens.rename` | `NPC_BIND_WRITE` | Change le **nom affiché en jeu** du PNJ Citizens lié (1 à 48 caractères). |
| `npc.citizens.skin` | `NPC_BIND_WRITE` | Applique un skin depuis un lien **MineSkin**. |

**Ce qui n'est jamais touché** : l'identifiant logique RPGQuest (renommer « Help » ne renomme pas
l'id `help`), l'UUID et l'id numérique Citizens, et les liaisons quêtes / dialogues / stories. Le
nom de la **définition** RPGQuest et le **locuteur** des dialogues sont également laissés tels
quels — les modifier automatiquement réécrirait des textes potentiellement partagés ; une
synchronisation éventuelle doit rester une action explicite et distincte.

**Ciblage** : le PNJ est résolu depuis la **liaison persistée** (id logique → UUID Citizens), jamais
depuis le nom affiché ni depuis une sélection Citizens préexistante. Pour le skin, Citizens
n'expose sa pose que par commande (`SkinTrait` vit dans `citizens-main`, pas dans l'artefact
`citizensapi` auquel ce projet se limite) : on sélectionne donc explicitement le PNJ visé pour la
console (`NPCSelector`, API publique) juste avant la commande et on désélectionne juste après, le
tout dans le même passage sur le thread principal — deux administrateurs simultanés ne peuvent pas
s'intercaler et viser le mauvais PNJ.

**Lien MineSkin** : seul le format `https://minesk.in/<identifiant>` est accepté, validé par le
panel **et** revalidé par le plugin (le navigateur n'est pas une source de confiance). Coller la
commande `/npc skin --url …` est explicitement refusé : l'action n'est pas une console libre.

**Retour d'état** : le téléchargement du skin est asynchrone côté Citizens. Un succès signifie donc
« demande transmise », pas « skin visuellement confirmé » — l'interface le dit, et la vérification
visuelle reste un test en jeu. En cas de refus (PNJ introuvable, type sans skin), le skin précédent
est conservé.

#### Nom et apparence réellement indépendants (correctif)

**Trois défauts constatés en production, sur le PNJ « Tan ».**

**1. Renommer changeait aussi le skin.** Ce n'était ni le panel ni l'action agent — les deux
chemins sont bien séparés — mais Citizens lui-même : un PNJ de type `PLAYER` **sans skin
explicite dérive son apparence de son nom**, donc `NPC#setName(...)` la change par effet de bord.

Correctif : avant tout renommage, l'apparence est **rattachée explicitement**, puis le nom est
changé. Deux cas, et aucun n'applique un skin neuf :

- une **source enregistrée** existe (un lien MineSkin posé depuis le panel) → elle est réappliquée ;
- aucune source connue → l'apparence est rattachée à l'**ancien nom**, dont elle était déjà
  dérivée : les joueurs voient exactement la même chose qu'avant.

Pour savoir quoi réappliquer, RPGQuest mémorise **sa propre** source d'apparence
(`npc_citizens_skins`, migration **V27**) plutôt que de lire les métadonnées internes de Citizens,
dont les clés ne font pas partie de `citizensapi` et changeraient en silence. Rien n'est appliqué
proactivement : **un PNJ existant n'est jamais rhabillé tant qu'on ne le renomme pas**.

Symétriquement, appliquer un skin ne touche pas au nom — l'action ne fait que poser le skin — et
les deux réglages survivent à un rechargement comme à un redémarrage (Citizens persiste les siens,
la table V27 persiste la source).

Si l'ancrage échoue, le renommage réussit quand même mais le message le **dit explicitement** au
lieu de laisser croire que le skin est conservé.

**2. Colorer le nom imposait d'écrire des balises.** Le champ « Nom du PNJ » de la définition
utilise désormais le composant partagé **`StyleField`** (#195), comme les noms de mobs et les
textes de dialogue : couleur au clic, styles, aperçu. Le champ soumis garde son nom et sa valeur
MiniMessage — ni l'action agent ni le plugin ne voient de différence — et un nom déjà écrit en
plusieurs styles est conservé tel quel. Aucun composant parallèle n'a été créé.

**3. Le panel affichait un nom périmé.** Ce n'était pas un cache : le `displayName` d'une ligne
`npc.list` est le nom de la **définition** RPGQuest (ou, à défaut, le locuteur du dialogue), et
n'est **jamais** lu depuis Citizens. L'afficher sous le libellé « Nom en jeu » montrait donc un nom
qui ne pouvait pas changer, quel que soit le nombre de rafraîchissements.

Les deux noms ont des fonctions différentes et sont désormais présentés comme tels :

| | Sert à | Se change dans |
|---|---|---|
| **Nom de définition** (`npcs/<id>.yml`) | catalogues du panel, locuteur par défaut des dialogues | « Modifier » |
| **Nom en jeu** (Citizens) | ce que les joueurs lisent au-dessus du PNJ | « Nom en jeu & apparence » |

Le champ « Nom en jeu » est prérempli depuis le relevé **`npc.citizens.list`**, et la liste affiche
le nom en jeu à côté du nom de définition **quand ils diffèrent** (les deux restent cherchables).
Sans relevé Citizens récent, le champ est **laissé vide** avec la raison — jamais prérempli avec un
autre nom.

**Sérialisation du relevé : les lignes sont écrites à la main.** `npc.citizens.list` et `npc.list`
construisent chaque ligne clé par clé. Ajouter un champ à `CitizensNpcSummary` ou à `NpcSummary`
sans l'émettre le fait **disparaître en silence** entre le serveur, qui le connaît, et le panel,
qui affiche alors « inconnu ». C'est exactement ce qui est arrivé à la localisation : le pont
Citizens la lisait, la vue métier la portait, et la sérialisation s'arrêtait à `spawned`. Deux
tests structurels verrouillent désormais les deux relevés — ils parcourent les composants du
record par réflexion et exigent une clé pour chacun.

#### État de présence : trois états, pas deux (#165)

**Défaut constaté.** Andy, créé près du Guide et visible en jeu, s'affichait « non apparu » — ce
qui se lit comme « ce PNJ est cassé ». Diagnostic sur les relevés réellement stockés : à 12:27,
**12 PNJ sur 13** étaient `spawned=true` (Andy compris) ; après le redémarrage de 12:49, sans aucun
joueur connecté, **0 sur 13**. `NPC#isSpawned()` est donc exact : Citizens **dématérialise** ses
PNJ dès qu'aucun joueur n'est à portée, et leurs chunks se déchargent.

Le défaut était donc le **vocabulaire**, pas la donnée. La fiche distingue désormais :

| État | Signification |
|---|---|
| **présent en jeu** | `isSpawned()` vrai — entité réellement matérialisée |
| **en veille** | pas matérialisé, mais le trait Citizens `Spawned` dit qu'il doit l'être → il réapparaîtra dès qu'un joueur approchera. **Ce n'est pas une anomalie**, et la fiche le dit, en précisant si le chunk est chargé |
| **désactivé dans Citizens** | trait `Spawned` à faux → il n'apparaîtra pas, même avec un joueur à côté |

Le relevé expose donc `spawned` (transitoire), `shouldSpawn` (intention persistante, trait public
`Spawned`) et `chunkLoaded`, lu **sans** charger le chunk.

#### Déplacer un PNJ existant (#165)

Fiche PNJ → **« Déplacer »**, sous `NPC_SPAWN_WRITE` — le même droit que faire apparaître un PNJ,
donc aucun droit nouveau.

Le PNJ est déplacé par `NPC#teleport`, l'API publique prévue pour cela : **il n'est jamais
recréé**, donc son identifiant Citizens, son identifiant RPGQuest, son skin, ses traits et ses
liaisons dialogues/quêtes sont préservés par construction.

- champs **préremplis** depuis la position réelle si le PNJ est matérialisé, sinon depuis sa
  dernière position enregistrée — et le formulaire **dit laquelle** ;
- monde choisi parmi les mondes réellement chargés ; changer de monde est permis ;
- orientation yaw/pitch en section **avancée**, repliée ;
- **arrivée validée** avant tout mouvement, avec le même critère que le placement automatique :
  sol praticable, deux cases libres, ni liquide ni portail. Aucun bloc cassé ni posé ;
- pour un PNJ **non matérialisé**, c'est sa position **enregistrée** qui change, et il n'est
  **pas** fait apparaître — le formulaire l'annonce ;
- la position obtenue est **relue et comparée** à celle demandée : si Citizens n'applique pas le
  déplacement, l'action échoue (`MOVE_NOT_APPLIED`) en disant la position conservée, plutôt que de
  rendre un succès trompeur.

#### Ce que Citizens permet — et ce qu'il ne permet pas depuis `citizensapi` (#165)

Relevé sur la build **réellement installée** (Citizens 2.0.43-SNAPSHOT build 4232), en interrogeant
le serveur, pas la documentation en ligne.

**Déjà exploité par le panel** : `createNPC` / `spawn` / `destroy`, `setName`, `teleport`,
`getStoredLocation`, trait `MobType`, trait `Spawned`, et la commande structurée `/npc skin`.

**Utile et exploitable plus tard** (API ou commande structurée, sans dépendance nouvelle) :

| Possibilité | Intérêt | Limite connue |
|---|---|---|
| Équipement (`Equipment`, trait **public**) | donner casque/arme à un PNJ | l'éditeur `/npc equip` est interactif ; passer par le trait |
| Visibilité du nom (`/npc name`) | masquer la plaque, ou ne l'afficher qu'au survol | **toggle**, non lisible depuis l'API |
| Posture (`/npc pose`) | figer un regard, poses nommées | commande, état non lisible |
| Hologrammes (`/npc hologram`) | lignes au-dessus du PNJ | commande uniquement |
| `PlayerFilter` (trait **public**) | montrer un PNJ à certains joueurs | demande une règle métier côté RPGQuest |

**Décision prise (#165) : `citizens-main` en `compileOnly`.** Les classes `LookClose` et
`Waypoints` ne sont pas dans `citizensapi` — elles vivent dans le plugin Citizens lui-même. Le dépôt
déclare donc désormais `net.citizensnpcs:citizens-main` en **`compileOnly` strict** (`isTransitive =
false`), au même titre que `citizensapi`, `paper-api` ou `log4j-core` : jamais empaqueté dans notre
JAR, fourni à l'exécution par le plugin installé. Rien d'autre n'entre : dans le POM de
`citizens-main`, WorldGuard, Denizen, PlaceholderAPI, Vault, Spigot et packetevents sont tous en
scope `provided`, que Gradle ne résout pas.

Ce choix permet des **appels typés** (`LookClose#lookClose(boolean)`,
`WanderWaypointProvider#setXYRange`) au lieu de réflexion ou de clés de persistance internes : le
compilateur vérifie les signatures.

**Correction d'une analyse antérieure.** Une version précédente de cette page concluait que ces deux
options étaient « non implémentables ». Les sources complètes la contredisent sur trois points, et
c'est la version ci-dessous qui fait foi :

- le **toggle** n'est une limite que de la *commande* `/npc lookclose` ; le trait expose
  `lookClose(boolean)`, un setter d'état explicite ;
- Wander **persiste** bel et bien : il n'est pas ajouté comme *goal* à la main, c'est le fournisseur
  `wander` du trait `waypoints`, que Citizens sauvegarde ;
- une patrouille existante **est** détectable, via `Waypoints#getCurrentProviderName()` et
  `WaypointProvider.EnumerableWaypointProvider#waypoints()`.

**Isolation et compatibilité.** Toute la surface « plugin Citizens » est confinée à
`CitizensBehaviourBridge`, distinct de `CitizensNpcBridge` (qui, lui, ne touche que `citizensapi`).
Chaque appel est enveloppé dans une garde qui intercepte `LinkageError` : sur une build Citizens qui
n'exposerait pas ces classes, ces deux options seules renvoient `CITIZENS_INCOMPATIBLE` avec un
message explicite, et **tout le reste de l'intégration PNJ continue de fonctionner**. Même
discipline que `ops.ConsoleTap` pour Log4j.

**Ce qui reste hors périmètre, et pourquoi.** `MockBukkit` n'embarque pas Citizens : le pont
lui-même n'est donc couvert par aucun test automatisé. Ce qui est testé est tout ce qui a pu être
rendu pur — la règle de non-écrasement (`WanderChangePlanner`), la validation des paramètres des
deux actions, leur idempotence, et le rendu du panel. Le comportement des traits réels demande une
validation en jeu, listée dans `docs/MANUAL_TEST_PLAN.md`.

#### « Regarder les joueurs » et promenade depuis le panel (#165)

Fiche PNJ → **« Regarder les joueurs »** et **« Promenade »**. Un PNJ Citizens doit être lié ;
aucune permission nouvelle n'est créée : le regard est cosmétique et relève de `NPC_BIND_WRITE`
(comme renommer ou habiller), la promenade fait bouger le PNJ et relève de `NPC_SPAWN_WRITE`
(comme le déplacer).

**Aucune bascule, nulle part.** Les deux actions portent un paramètre `enabled` valant `true` ou
`false`, et rien d'autre n'est accepté — ni vide, ni `toggle`. Le formulaire propose donc deux
boutons d'état plutôt qu'un interrupteur. C'est ce qui garantit qu'un double clic, un retry réseau
ou un rejeu par le cache d'idempotence aboutit au **même** état, et non à son inverse.

**Look Close** (`npc.citizens.lookclose`) : état activé/désactivé et **portée** en blocs
(`LookClose#setRange`). Après écriture, l'état est **relu sur le trait** ; s'il ne correspond pas,
l'action échoue (`LOOKCLOSE_NOT_APPLIED`) au lieu de rendre un succès trompeur. La fiche affiche
l'état réellement enregistré — et, quand le relevé ne le porte pas, « **inconnu** », jamais
« désactivé ». Défauts effectifs lus sur la configuration Citizens installée (`Settings.Setting`),
pas recopiés : par défaut `npc.default.look-close.enabled` = faux et `…range` = 10 blocs. Bornes du
panel : 1 à 64 blocs (Citizens n'en impose aucune).

**Promenade** (`npc.citizens.wander`) : le PNJ se déplace au hasard dans une zone bornée autour
d'une **ancre**, via le fournisseur `wander` natif — aucun moteur de déplacement ajouté.

- **Ancre** obligatoire côté effet : sans elle, `WanderWaypointProvider` ne transmet aucune région
  et le PNJ errerait sans limite. Le formulaire la préremplit depuis l'ancre enregistrée si la
  promenade tourne, sinon depuis la position du PNJ — et dit laquelle. Monde + X/Y/Z vont ensemble ;
  une ancre partielle est refusée des deux côtés, jamais complétée au jugé.
- **L'ancre n'est pas la position.** Déplacer le PNJ (« Déplacer ») ne déplace pas son ancre : il
  faut réappliquer la promenade pour la réancrer. La fiche et l'aide le disent explicitement.
- **Zone bornée** : `x_range` (1–64) et `y_range` (0–32) sont des demi-côtés en blocs autour de
  l'ancre. `WanderGoal` filtre ses destinations par cette boîte, dans le monde du PNJ : il ne peut
  ni en sortir, ni changer de monde. `y_range = 0` est légitime — promenade sur un seul plan.
- **Jamais d'écrasement silencieux.** Citizens n'accorde qu'un fournisseur de parcours par PNJ :
  `Waypoints#setWaypointProvider` retire le précédent. `WanderChangePlanner` (classe pure, testée)
  distingue l'état **neutre** — aucun fournisseur, ou `linear` sans aucun point, ce dans quoi
  Citizens laisse tout PNJ jamais configuré — d'un **comportement réel** : patrouille `linear`
  garnie, `guided`, ou fournisseur d'un plugin tiers. Dans ce second cas l'action est refusée
  (`WANDER_CONFLICT`) en **nommant ce qui serait perdu**, tant que `confirm_replace` n'est pas coché.
- **Réactiver est idempotent** : sur un PNJ déjà en promenade, la décision est `RECONFIGURE` — on
  règle ancre et zone, sans retirer ni réinstaller le fournisseur.
- **Désactiver ne retire que la promenade.** Si le PNJ suit un autre parcours, l'action répond
  `WANDER_NOT_ACTIVE` et ne touche à rien : désactiver ne doit pas devenir une façon détournée
  d'effacer la patrouille d'autrui. Quand elle s'applique, elle revient au fournisseur neutre, la
  navigation en cours est annulée et le message donne la **position finale**.
- **Ordre d'application volontaire** : dans Citizens 2.0.43, `setXYRange` ne recalcule pas l'arbre
  de régions — seul `addRegionCentre`/`removeRegionCentres` le fait. La zone est donc réglée
  **avant** que l'ancre soit posée, sinon la zone effective resterait l'ancienne alors que la fiche
  afficherait la nouvelle.
- **Non exposé, et pourquoi** : `worldguardregion` exigerait WorldGuard (exclu) ; la **vitesse**
  n'est pas un réglage de promenade mais de navigation globale du PNJ (`/npc speed`) ; `delay` (les
  pauses, en ticks, défaut `-1` = aucune) et `pathfind` existent et pourront être exposés, mais
  n'ont pas été jugés nécessaires à ce premier lot.


#### Créer un PNJ complet depuis le panel (#165)

Fiche PNJ → **« Créer un PNJ »**. Une **seule** action agent,
`npc.citizens.provision`, qui enchaîne la **définition RPGQuest**, l'**apparition**
Citizens, leur **liaison** et le **skin** optionnel, en réutilisant le parcours canonique
existant (`CitizensSpawnPlanner` / `CitizensSpawnCoordinator`) — jamais un second système.

**Permissions : l'union, jamais un droit implicite.** L'action produit les effets de trois
permissions, donc elle les exige toutes les trois (`NPC_WRITE`, `NPC_SPAWN_WRITE`,
`NPC_BIND_WRITE`). Le catalogue des actions sait désormais déclarer des permissions
**supplémentaires** (`Spec#alsoRequires`), vérifiées à l'exécution comme au rendu : le bouton
n'apparaît pas si l'une manque, et une requête forgée est refusée. Regrouper des effets ne doit
jamais accorder un droit que l'opérateur n'a pas.

**Identifiant déduit du nom.** Aucun identifiant technique n'est demandé : il est dérivé du nom
(« Bob le Bûcheron » → `bob_le_bucheron`), sans accents ni balises de couleur. C'est aussi le
**garde-fou anti-doublon** : un double clic retombe sur le même identifiant, et la seconde
tentative est refusée parce que la définition existe déjà.

**Localisation optionnelle, jamais partielle.** Monde **et** X/Y/Z ensemble, ou les quatre vides.
Une saisie partielle est refusée des deux côtés (panel et plugin). Les coordonnées sont validées
(nombres finis, bords de monde, bornes réelles du monde chargé), et la création n'est jamais
déplacée en silence ailleurs.

**Sans localisation : placement près du Guide.** Le Guide est identifié par sa **définition
stable** (`hub.guide-npc-id`, défaut `guide`), jamais par son nom affiché — le renommer ne casse
rien. La recherche est portée par `NpcPlacementPlanner`, classe **pure** : le monde n'y est vu qu'à
travers une interface `Probe`, ce qui rend testables les cas qu'on ne peut pas provoquer à la
demande (vide intégral, lave partout, portail, PNJ superposés, budget épuisé). Elle visite les
candidats par **distance totale croissante** (un emplacement à côté est préféré à un emplacement
perché), exige un **sol plein** et **deux cases libres** pour le corps, évite liquides, portails,
vide et chevauchement avec un autre PNJ, et **ne casse ni ne pose aucun bloc**. Les bornes sont
configurables : `hub.placement.search-radius` (1–64), `vertical-radius` (0–32), `max-attempts`
(1–100000).

Chaque impossibilité a son code et son message : Guide absent, Guide non lié, position du Guide
inconnue, Guide hors du Hub configuré, Hub non chargé, aucun emplacement sûr. **Aucun PNJ partiel
n'est créé** dans ces cas.

**Skin optionnel, formats réellement supportés.** Deux sources, et seulement deux : un lien
`https://minesk.in/<identifiant>` ou un **pseudo Minecraft** (`[A-Za-z0-9_]{3,16}`). Le format est
validé **avant** toute création, donc une URL invalide ne laisse jamais un PNJ derrière elle.
RPGQuest ne fait **aucun appel réseau** : Citizens résout et télécharge lui-même, de façon
asynchrone — un succès signifie « demande transmise ». Champ vide = apparence par défaut de
Citizens, sans aucun appel. Si Citizens refuse le skin après la création, le PNJ existe et le
message le **dit** au lieu de laisser croire que tout s'est bien passé.

**Nettoyage compensatoire, et gestion de ses propres échecs.** Si l'apparition échoue, la
définition que cette tentative vient de créer est retirée — mais jamais à l'aveugle. Trois issues,
chacune avec son code :

| Code | Situation |
|---|---|
| `PROVISION_ROLLED_BACK` | nettoyage complet : plus rien de cette tentative ne subsiste |
| `PROVISION_CLEANUP_SKIPPED` | une liaison Citizens référence déjà cet identifiant (une autre tentative a abouti) → **rien n'est supprimé**, et c'est dit |
| `PROVISION_CLEANUP_INCOMPLETE` | le retrait a échoué → le message nomme exactement ce qui reste à retirer à la main |

Un **échec de skin** ne détruit pas un PNJ correct : il est créé et lié, le résultat porte le code
`PROVISIONED_SKIN_FAILED`, et le message dit où réappliquer le skin. Détruire un PNJ valide parce
qu'une texture n'a pas été acceptée serait un nettoyage disproportionné.

**Doubles clics et requêtes rejouées.** Trois niveaux, du plus fiable au plus cosmétique :

1. une action **rejouée** (même identifiant d'action) renvoie le résultat mémorisé sans
   réexécution (`ProcessedActionCache`) ;
2. un **double clic** produit deux actions distinctes : `NpcDefinitionStore#create` écrit avec
   `CREATE_NEW`, donc le système de fichiers arbitre et une seule réussit. Auparavant un
   `exists()` suivi d'une écriture `REPLACE_EXISTING` laissait les deux passer — la seconde
   écrasait la première, puis son nettoyage supprimait la définition que la première venait de
   créer. Couvert par un test à 8 créations concurrentes ;
3. côté navigateur, le bouton se désactive à l'envoi (`data-submit-once`) — confort seulement, la
   garantie vient du serveur.

**Après succès**, la réponse porte l'identifiant RPGQuest, l'identifiant Citizens, le nom et la
**position réellement retenue** ; l'action déclare `npc.list` et `npc.citizens.list` en relevés de
suivi, donc la fiche et la liste se réactualisent.

### Hache du kit interceptée par WorldEdit (issue #192) — correctif de **configuration serveur**

Symptôme en jeu : casser une bûche avec la **hache en bois du kit** (#26) affiche « Première position définie en (…) » et ne casse pas le bloc.

Cause, dans le prolongement direct du piège documenté juste au-dessus : WorldEdit reconnaît sa wand **par type d'objet** (`wand-item`, `minecraft:wooden_axe` par défaut) et ignore le PersistentDataContainer. Le kit, lui, doit conserver ses quatre outils en bois (exigence #26) : la hache de gameplay et la wand WorldEdit sont donc le même matériau, et **aucun correctif côté plugin RPGQuest ne peut les distinguer** de façon fiable (l'interception a lieu dans le listener de WorldEdit).

**`/toggleeditwand` n'est pas la solution — et c'est pourquoi « ça ne change rien ».** Vérifié par RCON sur le DEV (WorldEdit `7.4.1`) : cette commande ne fait plus qu'**afficher un rappel**, son aide répond littéralement « Remind the user that the wand is now a tool and can be unbound with `/tool none` ». Depuis WorldEdit 7.3+, la wand est un *tool* lié à l'objet tenu. Conserver ce réflexe mène à croire le problème contourné alors que rien n'a changé.

| Piste | Effet réel | Verdict |
|---|---|---|
| `/toggleeditwand` | affiche un rappel, ne délie rien (7.4.1) | ❌ ne corrige rien |
| `/tool none` (hache en main) | délie la wand **pour ce joueur** | ⚠️ par joueur, à refaire — rejeté par le ticket (« ne pas demander au joueur de contourner à chaque connexion ») |
| `wand-item` ≠ hache en bois | la hache de gameplay n'est **plus jamais** une wand, pour tout le monde | ✅ correctif retenu |

**Correctif à appliquer** (hors dépôt : configuration d'un plugin tiers) :

1. éditer `plugins/WorldEdit/config.yml` sur le serveur → `wand-item: minecraft:golden_axe`
   (n'importe quel matériau absent de `starter-tool-kit.items` ; la hache en or n'est dans aucun kit) ;
2. `/worldedit reload` (ou redémarrage) ;
3. vérifier : la hache en bois du kit casse une bûche normalement, **y compris sur un compte administrateur** ; `//wand` donne désormais une hache en or et la sélection WorldEdit reste pleinement disponible pour l'administration (`/tool selwand` permet aussi de lier explicitement l'objet tenu).

Aucune permission OP n'est retirée, aucune protection de blocs n'est assouplie : Hub, claims, waypoints et bornes gardent exactement leurs règles.

> **Outillage** : `scripts/deploy-verygames.sh` refuse par conception tout fichier d'un autre plugin
> (liste blanche limitée au JAR RPGQuest et à `RPGQuest/…`) et ce garde-fou reste intact. Le
> changement se fait donc avec le script dédié **mono-usage** `scripts/worldedit-wand-item.sh`, qui
> ne peut écrire que le `config.yml` de WorldEdit, n'y modifie que la clé `wand-item`, refuse tout
> matériau du kit de départ, sauvegarde le fichier avant écriture et téléverse de façon atomique.
> À rejouer après une réinstallation de WorldEdit.
>
> **Appliqué sur DEV le 2026-10-05** : `minecraft:wooden_axe` → `minecraft:golden_axe`,
> `/worldedit reload` exécuté, et valeur **réellement chargée** confirmée par le rapport interne de
> WorldEdit (`/worldedit report` → `wandItem: minecraft:golden_axe`), pas seulement par le fichier.

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

### Moteur SQL configurable (issues #40 / #41)

Le moteur de base de données est un **détail d'infrastructure** choisi par `config.yml` :

```yaml
database:
  type: sqlite            # sqlite (défaut) | mysql (alias : mariadb)
  sqlite:
    file: data.db
  mysql:
    host: localhost
    port: 3306
    database: rpgquest
    username: rpgquest    # compte dédié RPGQuest, jamais admin global
    password-env: RPGQUEST_DB_PASSWORD   # NOM d'une variable d'environnement — jamais le mot de passe
    ssl-mode: disable      # disable | trust | verify-ca | verify-full
    pool:
      minimum-idle: 2
      maximum-pool-size: 10
      connection-timeout-ms: 10000
      max-lifetime-ms: 1800000
```

- **SQLite** reste câblé et **inchangé** : c'est le défaut, le mode test et le mode « installation
  simple ». L'ancienne clé `database.file` reste acceptée comme alias de `database.sqlite.file`.
- **MySQL/MariaDB (issue #41)** : backend **réel** — driver *MariaDB Connector/J* + pool
  *HikariCP* (déclarés dans `plugin.yml` `libraries:`, jamais empaquetés). `MySqlDialect` adapte
  automatiquement le SQL des repositories et le DDL des migrations (types, `AUTO_INCREMENT`,
  `LONGBLOB`, InnoDB/`utf8mb4_bin`, `ON DUPLICATE KEY UPDATE`, index). Historique de schéma dans
  la table `rpgquest_schema_migrations`. **Validé** contre un serveur MariaDB 10.11 (base de test
  VeryGames vide). Si la base est injoignable/mal configurée : refus de démarrage propre et borné.
- Le code de gameplay ne contient **aucun SQL** et ne teste **jamais** le moteur.
- **Migration des données `data.db` → MariaDB et bascule de production = issue #42** (non faite).
- Détail complet : [PERSISTENCE.md](PERSISTENCE.md).

### Tables SQLite par migration (catalogue `SchemaMigrator.ALL`, version courante = 18)

| Version | Tables créées | Domaine |
|---|---|---|
| V1 | `player_profiles`, `player_variables`, `quest_progress` | Socle joueur / quêtes |
| V2 | `quest_objective_progress` | Quêtes |
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
| V13 | `story_progress` | Storylines |
| V14 | `story_progress.current_index` (ALTER) | Storylines — quête suivie |
| V15 | colonnes `claims.reserved_*` (ALTER) | Claims — réservation foncière |
| V16 | `item_travel_cooldowns` | Voyage — cooldown Rune de rappel |
| V17 | `waystones`, `waystone_discoveries` | Voyage — Waystones Wild |
| V18 | `waypoints`, `waypoint_discoveries` | Waypoints par instance de biome (#124) |
| V19 | `travel_beacons` | Bornes du réseau de voyage (#132/#150) |
| V20 | `village_centers` | Centres de village du réseau de voyage (#151) |
| V21 | `travel_beacons.biome_instance` (ALTER) | Appariement borne↔waypoint du Hub (#149), idempotence uniquement |
| V22 | `waypoints.display_name` (ALTER + backfill + index unique) | Nom d'affichage humain unique par waypoint (#133/#135) |
| V23 | `waypoints.display_name` (UPDATE via correspondance figée) | Renommage des noms concaténés sans séparateur en noms lisibles (retour joueur 2026-10-04) |

Suivi de version : `PRAGMA user_version` en SQLite (natif, inchangé) ; table portable
`rpgquest_schema_migrations` en MySQL (#41). `SchemaMigrationRunner` applique les étapes en
attente **dans l'ordre**, une fois ; rejeu = no-op ; échec → `SchemaMigrationException` nommant
l'étape. Migrations idempotentes (`CREATE TABLE IF NOT EXISTS`, `ALTER` gardé par `columnExists`).

### Classification des données

-   **Configuration pure (YAML, éditable/versionnable en dehors du dépôt Git)** : `config.yml`, `messages.yml`, `spawn.yml`, `quests/`, `dialogues/`, `items/`, `recipes/`, `resource-nodes/`, `merchants/`, `mobs/`, `zones/`, `portals/`, `destinations/`, `world-portals/`, `store-products/`.
-   **Données joueurs (jamais régénérables)** : `data.db` en intégralité — profils, progression de quêtes, variables, portefeuilles/transactions, offres de marché, cooldowns de portails, claims + membres, compétences/XP/anti-farm, entitlements, backpacks + surplus + audit, livraisons boutique traitées, identités PNJ, Waystones + découvertes, waypoints + découvertes.
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

Section 7 — **Waypoints par instance de biome (#124)** : aucune page docs-site (comme les
Waystones). Référence : `docs/WAYPOINTS.md`.

Si une future modification concerne une page **existante** du docs-site, cette page doit être mise à jour dans la même branche/PR (voir section 20). Créer les pages manquantes ci-dessus reste un travail futur, hors périmètre de cette tâche (documentation uniquement, aucune page HTML modifiée ici).

---

## 20. Maintenance

Toute nouvelle commande, modification de syntaxe, nouveau fichier de configuration, nouvelle procédure serveur ou nouveau système administrable doit mettre à jour **`docs/RPGQUEST_BIBLE.md`** dans la même branche/PR que le changement de code. Si le changement concerne une page existante du docs-site (voir la table de correspondance en section 19), cette page doit être mise à jour dans la même branche/PR. La bible et le docs-site ne doivent pas diverger volontairement. Règle miroir posée dans [PROJECT_RULES.md](../PROJECT_RULES.md).

---

## 21. Storylines

Détail complet : [docs/storylines.md](storylines.md).

Un conteneur logique **ordonné** de quêtes existantes (`story.model.StoryDefinition`), avec sa propre progression par joueur (`NOT_STARTED`/`ACTIVE`/`COMPLETED` + `current_index`, table `story_progress`). Depuis cette étape, connectée au moteur de quête : une Story `ACTIVE` avance **automatiquement** — sa quête courante démarre toute seule, une complétion la fait avancer vers la suivante (démarrée à son tour), la dernière complétion passe la Story à `COMPLETED` — sans aucune commande joueur ni interaction PNJ entre deux quêtes. Branché sur `QuestProgressEngine#onProgressChanged` (`story.StoryService#onQuestProgressChanged`, même patron que `progression.listener.QuestCompletionXpListener`), idempotent (garde par comparaison d'index en mémoire, aucune récompense distribuée par la Story elle-même), et auto-guérit après un redémarrage/une reconnexion (`StoryService#loadForPlayer` rattrape une quête déjà terminée ou jamais acceptée).

Définitions chargées depuis `plugins/RPGQuest/stories/` (un exemple `main_story.yml` généré au premier démarrage). Aucune commande `/rpgadmin story create`/`delete` — seulement :

| Commande | Effet | Persistance |
|---|---|---|
| `/rpgadmin story info <joueur>` | Liste toutes les Stories connues, leur état, et — si `ACTIVE` — la quête courante (id + position `n/total`). | non |
| `/rpgadmin story start <joueur> <storyId>` | Démarre une Story (`ACTIVE`) et sa première quête. Refusé si id inconnu, déjà active, ou déjà terminée. | oui |
| `/rpgadmin story advance <joueur> <storyId>` | *(issue #36)* Démarre la Story si besoin, `forceComplete` de sa quête courante (récompenses appliquées **une fois**), avance d'un cran — accepte la quête suivante ou termine la Story. Message : quelle étape tester maintenant. Cible **en ligne**. Voir [ADMIN_TEST_SHORTCUTS.md](ADMIN_TEST_SHORTCUTS.md). | oui |
| `/rpgadmin story complete <joueur> <storyId>` | *(issue #36)* Enchaîne `advance` jusqu'au bout, dans l'ordre, borné (jamais de boucle infinie). Récompenses appliquées **une fois par quête**. Cible **en ligne**. | oui |
| `/rpgadmin story reset <joueur> <storyId\|all>` | Supprime la progression d'une Story (ou de toutes), reset ciblé — jamais l'inventaire, l'économie, ni les quêtes (`quest_progress` non touché). | oui (suppression) |
| `/rpgadmin story resetwithquests <joueur> <storyId>` | Comme `reset`, **et** réinitialise (via `QuestProgressEngine#resetQuest`) chacune des quêtes que cette Story référence — jamais les autres quêtes du joueur, jamais un `... all`. Outil ciblé pour rejouer un scénario de test. | oui (suppression) |

À savoir : **seule branche de `/rpgadmin` utilisable depuis la console** (cible un joueur passé en argument, jamais la position de l'exécutant) ; le joueur ciblé peut être hors ligne pour ces quatre sous-commandes (résolution asynchrone, profil créé au besoin par `start`) — seul le déclenchement effectif de la première quête attend une connexion réelle. Ces commandes restent strictement admin/debug — l'UX finale ne repose sur aucune commande joueur. Feedback joueur envoyé dans le **chat** (`messages.yml` → `story:`, jamais Title/Subtitle comme les quêtes, pour éviter une course d'affichage avec le Title « Quête commencée » que `QuestProgressEngine#accept` affiche déjà à chaque démarrage de quête).
