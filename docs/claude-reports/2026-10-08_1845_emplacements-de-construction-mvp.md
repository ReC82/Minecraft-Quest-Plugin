# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-08
* Heure : 18:45 (heure locale de la machine de build AWS)
* Sujet : MVP « Emplacements de construction » — marquer un point d'ancrage en jeu, le gérer depuis le Control Panel
* Statut : **DONE** (code et tests automatisés) — #213 reste **ouverte** : c'est sa première livraison sur trois, et TC-268 reste à faire
* Branche Git : `feature/213-building-sites` (**jamais fusionnée**)
* Commit actuel si disponible : `e56dc7a` (code) ; documentation dans le commit suivant
* Début de la tâche : 2026-10-08 17:57:09
* Fin de la tâche : 2026-10-08 19:22:16
* Durée totale : 01:25:07

---

## Demande

Créer la première brique du futur système de bâtiments : définir un emplacement en jeu,
l'enregistrer avec un ID stable, le voir et le gérer depuis le Control Panel, conserver position et
orientation, et préparer proprement la suite.

Explicitement **hors périmètre** et non touché : génération IA de bâtiment, génération de `.schem`,
import de schematic, placement WorldEdit, suppression d'un bâtiment posé, versioning.

### Le ticket réel

L'audit a trouvé **#213** — « construction MVP : marquer un emplacement en jeu et gérer un chantier
nommé dans le Control Panel », ouverte le 06/10, sous-chantier de #94 et liée à #20. Ce lot est
exactement sa section « **Première livraison indépendante de l'IA** ». Le ticket découpe lui-même :
*marquage + catalogue d'abord ; placement contrôlé ensuite ; génération IA après.*

Deux exigences du ticket, absentes du prompt, ont été honorées parce que `CLAUDE.md` fait de l'issue
la vérité sur le comportement cible :

- **« règle précise sur bloc cliqué/face et position finale »**, et **« ancre au niveau du sol »** ;
- **« description éditable »** — ajoutée, ce qui évite une seconde migration plus tard.

Une exigence du ticket a été volontairement laissée : les **dimensions envisagées** sur la fiche. Le
ticket dit lui-même « Taille/emprise à définir avant placement », et une emprise dépend du bâtiment,
pas du site.

---

## Analyse

### Trois constats d'audit qui ont déterminé l'architecture

**1. Le modèle de #194 ne s'applique pas.** La suppression de quête/story analyse l'espace de
travail du panel, écrit, sauvegarde localement. Or les emplacements n'existent pas dans un fichier
du dépôt : il n'y a aucun contenu éditable à manipuler, et tout doit passer par la base du serveur
et des actions agent. Même conclusion que pour les définitions PNJ de #226, pour la même raison.

**2. Il existe déjà un outil PDC, et son matériau a une histoire.**
`ZoneSelectionService.createWandItem()` porte un commentaire explicite : la hache en bois a été
abandonnée parce que **WorldEdit la reconnaît par type d'objet, pas par PDC** — les deux plugins se
disputaient le même clic, WorldEdit posant sa sélection et annulant l'événement avant RPGQuest. Le
modèle (PDC + `EventPriority.HIGHEST` + `ignoreCancelled = false`) est donc réutilisé tel quel ; le
matériau, non : une tige de blaze de plus dans la barre d'inventaire serait confondue avec l'outil
de zone. D'où une **houe en fer**, qu'aucune wand connue ne réclame.

**3. Deux repositories existants sont exactement la forme du besoin.**
`VillageCenterRepository` (V20) pour « id stable + monde + coordonnées + date », et
`NpcIdRepository` (V11) pour un allocateur d'identifiants séquentiels jamais réutilisés. Les deux
ont été suivis plutôt que réinventés.

### La règle d'ancrage, et pourquoi elle n'est pas « le bloc cliqué »

Le prompt dit « enregistrer exactement le bloc/point choisi », le ticket dit « l'ancre au niveau du
sol ». Les deux lectures se réconcilient sur une seule règle : **l'ancre est le bloc adjacent à la
face cliquée** — l'espace libre qu'on vient de désigner. Cliquer le dessus d'un bloc d'herbe en
`y=66` enregistre `y=67` : la case où l'on se tiendrait, et où reposera le premier niveau.

Si l'ancre était le bloc cliqué, **tout placement futur devrait ajouter `+1` en Y** — un décalage
implicite que chaque appelant appliquerait de son côté, et qu'un seul oublierait. Le ticket l'exige
d'ailleurs noir sur blanc : l'ancre doit être « indépendante d'un offset interne implicite ».

La règle est une **fonction pure** (`BuildingSiteAnchor.resolve`), avec une projection sans Bukkit
de `BlockFace` (`ClickedFace`). C'était la seule façon de la rendre réellement exécutable dans un
test : *une règle qu'on ne peut pas exécuter n'est pas précise, elle est seulement écrite.*

### L'orientation, et le piège du yaw

Dans Minecraft, le yaw `0` regarde le **sud** (`+Z`), et croît vers l'ouest. C'est la conversion
qu'on écrit à l'envers une fois sur deux. Elle est donc faite **une fois**, à la création, par une
fonction pure (`Facing.fromYaw`), et couverte sur les quatre cardinaux, les yaw négatifs, les tours
multiples et les diagonales exactes.

Le yaw brut n'est **pas** stocké, comme le prompt le demandait : un bâtiment se pose aligné sur la
grille, et garder `177,43°` donnerait une précision que le placement ne saura jamais utiliser — en
obligeant chaque lecteur à refaire la conversion, donc à la refaire différemment.

### Anti-doublon : un seul mécanisme n'aurait pas suffi

Le prompt demande d'éviter « 10 sites par double clic/spam » sans inventer de règle de distance.
Deux problèmes distincts se cachent là :

| Problème | Protection | Pourquoi l'autre ne suffit pas |
|---|---|---|
| Spam de clics sur **le même endroit** | un emplacement existe déjà exactement là → on renvoie **celui qui existe**, en le nommant | l'anti-rebond laisserait passer le 3ᵉ clic une seconde plus tard |
| **Un seul geste**, deux événements | fenêtre d'anti-rebond de **500 ms par joueur** | deux blocs voisins ne sont pas « le même bloc », donc la règle de position ne voit rien |

Renvoyer l'emplacement existant est plus fort qu'un simple avertissement, et c'est aussi la **bonne
réponse à un double clic légitime** : l'administrateur voulait un emplacement ici, il en a un.

**Aucune distance minimale n'a été inventée**, conformément au prompt : une maison et son puits sont
deux points d'ancrage légitimes à deux blocs l'un de l'autre. L'interdire demanderait de connaître
des emprises que ce lot ne connaît pas.

### Pourquoi les identifiants ne doivent jamais être recyclés

`buildsite_0001` est alloué par une table `AUTOINCREMENT` dédiée, pas par un `MAX()` sur
`building_sites`. Un `MAX()` aurait réattribué l'identifiant d'un emplacement supprimé — et un
identifiant recyclé est précisément ce qui ferait pointer un futur `BuildingPlacement` sur le
mauvais emplacement. C'est la même garantie que `npc_ids`, et elle est vérifiée par un test qui
supprime la ligne puis réalloue.

---

## Travail effectué

### Côté plugin

**Modèle** (`com.lodygames.rpgquest.building.model`) — tout sans Bukkit, donc testable :

* `BuildingSite` — id, nom, description, monde, x/y/z **entiers**, orientation, état, auteur, date.
  Coordonnées entières parce qu'un bâtiment se pose sur la grille ; `village_centers` stocke des
  `REAL` parce que c'est une destination de téléportation, où un demi-bloc compte.
* `Facing` — NORTH/EAST/SOUTH/WEST, `fromYaw` pure, vecteurs de direction pour le placement futur.
* `SiteStatus` — **`EMPTY` seul**. Déclarer `RESERVED`/`OCCUPIED` aujourd'hui serait inventer un
  cycle de vie que rien ne fait avancer : des états morts, que chaque écran devrait afficher sans
  pouvoir les produire. La colonne est un `TEXT` et la lecture est tolérante, donc l'ajout futur ne
  demandera **aucune migration**.
* `ClickedFace` + `BuildingSiteAnchor` — la règle d'ancrage, pure.

**Service et persistance** :

* `BuildingSiteRepository` — JDBC pur, sur le modèle de `VillageCenterRepository`. `INSERT` et non
  `UPSERT` : un identifiant déjà présent est une anomalie, et la faire échouer vaut mieux
  qu'écraser un emplacement en silence.
* `BuildingSiteService` — cache mémoire **copie** de la base, chargé au démarrage, mis à jour après
  chaque écriture réussie, jamais consulté pour décider si une écriture a eu lieu. Horloge
  injectable, pour que l'anti-rebond se teste sans attendre.
* Migration **V28** : `building_sites` + index `(world, x, y, z)` + `building_site_ids`. Purement
  additive.

**Outil en jeu** :

* `BuildingSiteTool` — PDC uniquement, houe en fer, nom et lore pour l'humain mais jamais pour
  l'identification.
* `BuildingSiteToolListener` — clic droit crée, clic gauche est neutralisé et rappelle la
  manipulation. L'événement est annulé (sinon la houe laboure et le coffre s'ouvre), la main
  secondaire ignorée. **La permission est vérifiée avant de regarder le clic** : un joueur ordinaire
  qui récupérerait l'outil ne crée rien, et le comprend. Une ancre hors limites du monde est
  refusée — seul le serveur connaît les limites du monde chargé.
* `/rpgadmin buildsite tool|list`, derrière son propre nœud.

**Permission** : `rpgquest.admin.buildsite` (`default: false`), déclarée dans `plugin.yml` et
`RpgPermissions`. La branche échappe à l'ombrelle historique — comme la branche PNJ de #200 — mais
l'ombrelle l'implique, donc aucun administrateur existant ne perd quoi que ce soit.

**Agent** : cinq actions (`list`, `rename`, `describe`, `facing`, `delete`), avec validation des
paramètres dans l'exécuteur *et* dans le catalogue du panel.

### Côté Control Panel

* `panel/building/BuildingSiteView` + `BuildingSiteDirectory` — projection typée du relevé, avec
  les libellés français, le formatage de date, le filtre par monde et la détection des mondes
  déchargés.
* Page `/buildings/sites` dans `AgentPages` (même structure que `/travel` et `/npcs`) : liste en
  accordion, recherche, filtre par monde en liens (aucun script, URL partageable), fiche complète,
  formulaires de renommage / description / orientation, zone de danger.
* `Permission.BUILDING_READ` / `BUILDING_WRITE` / `BUILDING_DELETE`, groupe de navigation
  **Bâtiments**, et une fiche du centre d'aide.

---

## Fichiers créés

**Plugin**

* `src/main/java/com/lodygames/rpgquest/building/model/BuildingSite.java`
* `src/main/java/com/lodygames/rpgquest/building/model/Facing.java`
* `src/main/java/com/lodygames/rpgquest/building/model/SiteStatus.java`
* `src/main/java/com/lodygames/rpgquest/building/model/ClickedFace.java`
* `src/main/java/com/lodygames/rpgquest/building/model/BuildingSiteAnchor.java`
* `src/main/java/com/lodygames/rpgquest/building/BuildingSiteService.java`
* `src/main/java/com/lodygames/rpgquest/building/BuildingSiteTool.java`
* `src/main/java/com/lodygames/rpgquest/building/BuildingSiteToolListener.java`
* `src/main/java/com/lodygames/rpgquest/database/BuildingSiteRepository.java`

**Control Panel**

* `control-panel/src/main/java/com/lodygames/rpgquest/panel/building/BuildingSiteView.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/building/BuildingSiteDirectory.java`
* `control-panel/src/main/resources/docs/batiments-emplacements.md`

**Tests**

* `src/test/java/com/lodygames/rpgquest/building/model/FacingTest.java`
* `src/test/java/com/lodygames/rpgquest/building/model/BuildingSiteAnchorTest.java`
* `src/test/java/com/lodygames/rpgquest/building/BuildingSiteServiceTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/building/BuildingSiteDirectoryTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/BuildingSitesPageTest.java`

**Documentation**

* `docs/claude-reports/2026-10-08_1845_emplacements-de-construction-mvp.md` (ce fichier)

## Fichiers modifiés

**Plugin** : `SchemaMigrator` (V28), `RpgPermissions`, `plugin.yml`, `RpgAdminCommand`
(branche `buildsite` + tab-complétion + exception à l'ombrelle), `RPGQuestBootstrap` (service,
chargement, listener, câblage agent et commande), `AgentActionType`, `AgentActions`,
`BukkitAgentActions`, `AgentActionExecutor`.

**Control Panel** : `Permission`, `Role`, `AgentActionCatalog`, `AgentPages`, `PanelApp` (route),
`Layout` (groupe de navigation), `docs/_index.txt`.

**Tests existants adaptés** : `StubAgentActions`, `AgentActionExecutorTest`,
`RpgAdminTestShortcutsCommandTest`, `BukkitAgentActionsMobTest`, `PlayerAdminActionsTest`,
`SchemaMigratorTest`.

**Documentation** : `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`,
`docs/deployment/SERVER_CHANGELOG.md`, `docs/MANUAL_TEST_PLAN.md` (TC-268), `.ai/ROADMAP.md`,
`docs/claude-reports/README.md`.

## Base de données / migrations

**Oui — migration V28**, et c'est la première de la journée.

```sql
building_sites(id PK, name, description DEFAULT '', world, x, y, z,
               facing, status DEFAULT 'EMPTY', created_by DEFAULT '', created_at)
INDEX idx_building_sites_world (world, x, y, z)
building_site_ids(id INTEGER PRIMARY KEY AUTOINCREMENT, created_at)
```

**Purement additive** : deux tables neuves et un index. Aucune colonne ajoutée à une table
existante, aucune donnée existante lue ni réécrite. Appliquée automatiquement au démarrage par le
`SchemaMigrationRunner` existant ; rejouée sur une base à jour, c'est un no-op.

`CURRENT_VERSION` passe de 27 à 28. La compatibilité MySQL est assurée par le `SqlDialect`, qui
traduit déjà `INTEGER PRIMARY KEY AUTOINCREMENT`.

## Configuration / données

Aucun changement de configuration. **Aucun fichier de contenu créé ni modifié** : ni quête, ni
dialogue, ni story, ni définition PNJ.

---

## Tests automatiques

Exécution depuis un **worktree propre**, avec `RPGQUEST_TEST_MAX_HEAP=768m`, **un seul Gradle à la
fois**.

`./gradlew build` (qui inclut `test`) depuis le worktree propre : **BUILD SUCCESSFUL en 34 min 17 s**.

| Module | Tests | Échecs | Erreurs | Ignorés |
|---|---|---|---|---|
| plugin | 1956 | 0 | 0 | 37 |
| control-panel | 1033 | 0 | 0 | 1 |
| web-api | 30 | 0 | 0 | 0 |
| **Total** | **3019** | **0** | **0** | **38** |

Soit **+84 tests** par rapport au lot précédent (2935) : 42 côté plugin (ancrage, orientation,
service et persistance, migration, payload agent) et 42 côté panel (projection et page HTTP).

Aucun échec en route, et aucun test existant n'a eu besoin d'être assoupli — seules des signatures
de constructeur ont été étendues dans cinq fichiers de test (un paramètre de plus).

### Ce que les tests couvrent réellement

**La règle d'ancrage** (`BuildingSiteAnchorTest`) : le cas normal (dessus du sol → +1 en Y), les six
faces, l'absence de face exploitable, une face diagonale inattendue, les coordonnées négatives et
le Y profond.

**La conversion d'orientation** (`FacingTest`) : les quatre cardinaux — avec l'assertion explicite
que le yaw `0` est le **sud** —, les yaw approximatifs, négatifs et de plusieurs tours, les
diagonales exactes (déterminisme), et un balayage de deux tours complets.

**Le service** (`BuildingSiteServiceTest`, sur une vraie base SQLite temporaire) : création avec
monde/coordonnées/orientation, les quatre orientations en aller-retour, identifiants séquentiels et
uniques, **non-recyclage après suppression**, le format de l'identifiant au-delà de quatre chiffres,
re-clic au même bloc, mêmes coordonnées dans un autre monde, bloc voisin autorisé, anti-rebond
(dedans, dehors, et par joueur), **persistance après redémarrage simulé** — un service neuf sur la
même base —, persistance d'une suppression, renommage qui ne touche que le libellé, nom vide, nom
trop long, réorientation qui préserve la position, description effaçable, mutation d'un identifiant
inconnu, suppression idempotente, suppression qui laisse les voisins tranquilles, tri, liste des
mondes, recherche insensible à la casse, et lecture tolérante d'un état inconnu.

**La migration** (`SchemaMigratorTest`) : les deux tables créées et utilisables, l'identité en clé
primaire refusée en doublon par la base elle-même, le non-recyclage `AUTOINCREMENT` après
suppression de ligne, et les valeurs par défaut qui rendent un `INSERT` minimal valide.

**Le payload agent** (`AgentActionExecutorTest`) : tous les champs dans leur type, le rejet d'un
identifiant forgé sur les quatre actions de mutation, la validation du nom et de sa longueur, la
description vide acceptée (c'est « effacer la note »), l'orientation obligatoire, la normalisation
de casse de l'identifiant, et **l'absence d'action de création**.

**La page** (`BuildingSitesPageTest`, sur un vrai serveur HTTP) : « rafraîchir » plutôt que « aucun
emplacement » sans relevé, tous les champs affichés, le monde déchargé signalé et compté, l'auteur
inconnu dit comme tel, le filtre par monde, la barre de recherche, l'absence de formulaire de
création *et* la présence de la marche à suivre, le renommage mis en file avec le bon identifiant,
le nom vide refusé avant la file, la description vide acceptée, l'orientation inconnue refusée et la
casse normalisée, l'identifiant forgé refusé, l'absence de cascade, le double clic, le CSRF, la
confirmation obligatoire, et la répartition des trois permissions rôle par rôle.

**La projection** (`BuildingSiteDirectoryTest`) : la forme réelle du payload, y compris ses laideurs
de transport (la chaîne `"null"`, un entier arrivé en texte), les libellés français, une valeur
inconnue affichée plutôt que masquée, le formatage de date et son repli, le filtre, les mondes venant
du serveur, et l'indisponibilité explicite sans relevé.

## Tests manuels à effectuer

**TC-268** — `PENDING MANUAL VALIDATION`, dans `docs/MANUAL_TEST_PLAN.md`, environ **5 minutes** :
obtenir l'outil, marquer un emplacement vers l'est, vérifier l'ancrage (**bloc cliqué + 1 en Y**),
l'anti-doublon, la fiche du panel, le renommage, le redémarrage du serveur, la suppression, et la
vérification qu'**aucun bloc du monde n'a bougé**.

> 🚫 **Ne construire aucun bâtiment pendant ce TC.** Ce lot ne sait rien poser.

## Résultat attendu

- un clic droit en jeu crée un emplacement persistant, à l'ancre attendue et avec l'orientation du
  regard ;
- le spam de clics ne crée pas de doublons ;
- le Control Panel montre monde, position, orientation, état, date, auteur, et permet de renommer,
  décrire et réorienter — jamais de déplacer ;
- tout survit à un redémarrage ;
- supprimer retire le repère sans toucher un seul bloc, est idempotent, et ne recycle pas
  l'identifiant.

## Reset / retour à l'état initial

Supprimer les emplacements de test depuis la page du panel (la suppression ne touche aucun bloc).
Pour repartir de zéro côté base, les deux tables peuvent être vidées — mais `building_site_ids` ne
doit **pas** être vidée si des emplacements subsistent ailleurs, sous peine de réattribuer des
identifiants. En pratique : supprimer les fiches depuis le panel suffit, et c'est le chemin prévu.

---

## Déploiement VeryGames

**Le déploiement A ÉTÉ effectué** sur le **DEV**, le lot l'autorisant. Détail horodaté en fin de
section, y compris la vérification de la migration sur la base réelle.

### À transférer

1. **JAR RPGQuest** depuis un arbre propre (`scripts/deploy-verygames.sh` avec
   `RPGQUEST_TEST_MAX_HEAP=768m` et `-y`).
2. **Control Panel** (`scripts/plugadmin/deploy.sh`).

### Ne PAS transférer/altérer

`plugins/RPGQuest/npcs/`, `dialogues/`, `quests/`, `stories/`, la configuration Citizens,
`config.yml`. Aucun fichier de contenu n'est concerné.

### Redémarrage requis

**Oui.** L'outil, les cinq actions agent et la migration V28 vivent dans le plugin.

### Migration automatique

**Oui**, V28, au démarrage. **Sauvegarder `data.db` avant** — ce n'est pas une précaution de
principe cette fois.

### Déploiement réellement effectué — 2026-10-08, 19:05 à 19:21 (CEST)

Serveur **RPGQuest DEV** (VeryGames), depuis le worktree propre sur `6f58b78`.

| Étape | Résultat vérifié |
|---|---|
| **Sauvegarde de `data.db` AVANT tout** | `data-20261008T170546Z-predeploy.db`, 1 167 360 o. Relue : **schéma V27, 33 tables** — donc une sauvegarde réellement exploitable, pas juste un fichier copié |
| `deploy-verygames.sh -y` | `DEPLOY_EXIT=0`. JAR en ligne **2 027 547 o — identique au local**, SHA-256 `96fd7ac9…` |
| Backup du JAR remplacé | `rpgquest-20261008T171841Z-predeploy.jar`, 1 993 126 o, SHA-256 `4abb4d91…` — **exactement le JAR du lot #222 de l'après-midi**, ce qui confirme la ligne déployée |
| Contrôle de taille | nouveau (2 027 547 o) **plus gros** que le déployé (1 993 126 o) : sens attendu |
| `verygames-restart.sh` | **un seul** redémarrage. 0 joueur connecté → personne déconnecté. Retour **ONLINE** confirmé |
| **Migration V28 vérifiée sur la base réelle** | `data.db` relu après redémarrage : **`user_version = 28`**, `building_sites` **présente**, `building_site_ids` **présente**, index `idx_building_sites_world` **présent**, 0 emplacement, **35 tables** (contre 33 avant) |
| RCON | `/plugins` → **RPGQuest en vert** ; `version RPGQuest` → `0.1.0-SNAPSHOT` ; `/rpgadmin buildsite list` **reconnue** (elle réclame un joueur en jeu, et non « sous-commande inconnue ») |
| `plugadmin/deploy.sh` | `PANEL_DEPLOY_EXIT=0`, service `active (running)`, `/health` → `{"panel":"ONLINE"}` |
| Contrôle du panel **réellement servi** | les classes `panel/building/*` sont dans le JAR déployé, la fiche d'aide `docs/batiments-emplacements.md` y est **et** est listée dans le manifeste, et `/buildings/sites` répond **303 → /login** (route enregistrée ; un 404 aurait signalé un panel périmé) |

Aucun fichier de contenu, aucune configuration autre que le schéma touchés.

### Ce qui n'a PAS pu être vérifié depuis la machine de build

**Le parcours en jeu.** Obtenir l'outil, cliquer, vérifier l'ancre et l'orientation exigent un
client Minecraft : `/rpgadmin buildsite` réclame un joueur (position requise), et aucune commande
RCON ne peut simuler un clic droit sur un bloc. C'est tout l'objet de TC-268.

Ce qui est établi : le plugin corrigé est **chargé et actif**, la branche de commande **existe**, le
schéma est **migré**, et le panel sert **bien** le nouveau code avec sa route enregistrée. Ce qui
reste à constater : qu'un clic crée réellement un emplacement à la bonne ancre.

## Rollback

- Plugin : redéployer le JAR sauvegardé puis redémarrer. **Les deux tables restent en place**,
  simplement inutilisées : elles ne gênent rien, et les emplacements déjà créés réapparaissent au
  redéploiement. Le runner de migration ne redescend jamais une version.
- Panel : `scripts/plugadmin/rollback.sh app` puis `systemctl restart plugadmin`.

## Logs / diagnostic

- Au démarrage : `N emplacement(s) de construction chargé(s).` Un échec de chargement est journalisé
  en `error` **sans empêcher le serveur de démarrer** — les emplacements sont un outil
  d'administration, pas une mécanique de jeu.
- En jeu : `/rpgadmin buildsite list`.
- Panel : journal d'actions pour le verdict réel de chaque mutation (elles sont asynchrones), et
  audit pour qui a demandé quoi.

## Documentation mise à jour

Voir « Fichiers modifiés → Documentation ». Le centre d'aide du panel gagne une fiche
*Emplacements de construction* (catégorie « Bâtiments »), qui explique notamment pourquoi il n'y a
pas de bouton « Créer » et ce que la suppression ne fait pas.

## Limitations / travail restant

1. **Aucune dimension ni emprise.** Le ticket les veut définies *avant* placement, et elles
   dépendent du bâtiment, pas du site. Marquer un point ne réserve donc aucune surface — c'est écrit
   dans #213 et c'est assumé ici.
2. **Aucune action « Me téléporter à l'emplacement ».** Le prompt la conditionnait à l'existence
   d'un service de TP admin sûr : il n'y en a pas. `ClaimTeleportService` est spécifique aux claims,
   et aucune action agent ne téléporte un joueur. En construire un serait un lot à part, et le
   prompt demandait de ne pas élargir le ticket.
3. **Aucun marqueur visuel en jeu.** Le ticket le veut « temporaire, pas un bloc permanent » :
   particules ou entité d'affichage temporaire, c'est un chantier distinct.
4. **La position n'est pas éditable depuis le panel**, seule l'orientation l'est. Corriger une
   position veut dire re-marquer en jeu puis supprimer l'ancien ; l'aide de la fiche le dit. Rendre
   la position éditable à l'écran serait rendre possible de déplacer un ancrage sans voir le
   terrain.
5. **La suppression devra être repensée** quand un bâtiment pourra être posé. Aujourd'hui elle est
   sûre *parce qu'il n'y a rien à défaire* ; demain elle devra bloquer ou traiter la construction
   explicitement. C'est écrit dans la permission et dans la documentation.
6. **Un seul point, pas deux coins.** Le ticket mentionne une « option future de deux coins pour
   borner une zone » : non faite, et elle n'est pas nécessaire au placement d'un schematic dont
   l'emprise est connue.
7. **`building.site.create` n'existe pas**, contrairement à la liste du prompt. Écart assumé et
   documenté : un emplacement est défini par une position désignée dans le monde ; un formulaire web
   devrait l'inventer. La page explique où créer.
8. Dettes rappelées, hors périmètre : `MiniYaml` ne gère pas les scalaires repliés
   (`dialogues/guard.yml` non éditable depuis le panel) ; `RestartServiceTest` reste sensible au
   temps réel.

## Ce qui est prêt pour le lot « affecter un schematic à un site »

- **Un identifiant stable et jamais réutilisé** : un futur `BuildingPlacement` n'a qu'à porter un
  `buildsite_id`, sans risque qu'il désigne un jour un autre emplacement.
- **Une ancre directement utilisable**, sans offset implicite à appliquer, et **une orientation
  cardinale** déjà normalisée — les deux entrées d'une transformation de placement.
- **`Facing` porte ses vecteurs de direction** (`modX`/`modZ`), ce dont une rotation de schematic a
  besoin.
- **Un état extensible sans migration** : `RESERVED` puis `OCCUPIED` s'ajoutent à l'énumération,
  la colonne est déjà un `TEXT` et la lecture est déjà tolérante.
- **Une page et un groupe de navigation** où la bibliothèque de bâtiments et les placements se
  rangeront sans casser de liens déjà partagés.
- **Un service et un repository** qui savent déjà lire, modifier et supprimer une fiche, avec le
  cache et la persistance en place.

Ce qu'il faudra ajouter, et qui n'est pas préparé d'avance exprès : les tables `building_definitions`
et `building_placements`, parce que leur forme dépendra de ce que l'import de schematic sait
réellement lire — les créer maintenant serait deviner.

## Prochaine étape suggérée

1. Déployer : JAR + **redémarrage Minecraft** (migration V28) + Control Panel.
2. Faire **TC-268** (~5 minutes).
3. Enchaîner sur la suite de #213 : associer un `.schem` existant à un emplacement, afficher son
   emprise, puis le placement contrôlé. L'identifiant stable est prêt pour ça.
4. TC-267 (#222…#226), TC-265 et TC-266 restent en attente et bloquent encore #146.
