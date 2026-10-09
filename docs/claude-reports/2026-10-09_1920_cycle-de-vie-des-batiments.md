# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-09
* Heure : 19:20 (heure locale, CEST)
* Sujet : #234 — cycle de vie des bâtiments posés : libérer, réorienter, remplacer, et préparer
  villages/villes. Plus la vérification factuelle du déploiement de #235 (phase 0).
* Statut : DONE (validation manuelle en jeu restante — TC-274)
* Branche Git : `feature/234-building-placement-lifecycle`
* Commit actuel si disponible : `707f496` (+ les commits de déploiement et de ce rapport)
* Début de la tâche : 2026-10-09 17:36:54
* Fin de la tâche : 2026-10-09 19:20:15
* Durée totale : 01:43:21

## Demande

Ordre strict imposé : (1) vérifier factuellement le déploiement de #235, puis **ne plus y toucher** ;
(2) travailler sur **#234** ; (3) si #234 est réellement terminé, un **audit seul** de #156.

Pour #234 : auditer précisément l'état livré par #213 **avant** de coder ; distinguer explicitement
l'emplacement (intention) du placement (fait) ; rendre la **libération irréprochable en priorité 1** ;
conserver une **baseline originale** distincte de l'état d'avant la dernière opération ; introduire un
historique minimal ; mémoriser la version/empreinte du bâtiment posé ; ajouter **réorienter** et
**remplacer** avec aperçu, compensation et protections de concurrence ; créer une **deuxième
structure** de test exigeante et asymétrique ; ne pas créer le moteur de villages mais ne pas
l'empêcher. Interdits explicites : génération IA, import de schematic externe, éditeur de plan,
villages automatiques, #229/#230/#233, modification du Guide/#235. **Aucune mutation du monde réel
depuis la machine** — les tests de blocs seront faits par le propriétaire.

## Analyse

### Phase 0 — ce que #235 sert réellement sur DEV

Vérifié par téléchargement et empreinte, pas par déduction.

| Vérification | Résultat |
|---|---|
| JAR installé | **2 159 437 o**, SHA-256 `f9afbcde…` — identique au JAR #235 |
| `guide.yml` installé | **12 656 o**, SHA-256 `2d0dc9bb…` — **bit-pour-bit identique au local** |
| « Très bien, j'y vais. » | **absent des 38 libellés** ; seule occurrence = un **commentaire** documentant le remplacement |
| Les 4 autres exigences | ✅ « Que dois-je faire… », « Commencer la quête : Premiers pas », « Comment améliorer mon kit ? », chemin `START_QUEST → rpgquest:kit_tier2` |
| Nœuds et marqueurs | ✅ `intro_quest`, `kit_progress`, les trois `%kit_*%` |
| Panel | ✅ deux actions, `ACTION_PLAYER_RESET_FULL`, avertissement du second kit dans le JAR **servi** ; `/health` ONLINE |
| Plugins | ✅ Citizens, RPGQuest, WorldEdit **verts** |
| Dialogues rechargés | ✅ redémarrage à 16:56–16:58, **après** le transfert de 16:53:59 ; moteur interrogé après |
| Backup ancien `guide.yml` | ✅ `verygames-backups/extra-20261009T145359Z/` + `MANIFEST.txt` |
| Fichiers utilisateur | ✅ les 8 fichiers non suivis du propriétaire intacts |

Une première assertion de mon script a signalé « Très bien, j'y vais » comme encore présent : c'était
un **commentaire** du fichier, pas un libellé de choix. Vérifié ligne par ligne — aucun des 38
libellés ne le contient. Aucun reset, aucune modification de progression, aucun test de dialogue à la
place du propriétaire : la seule action lancée était un **aperçu en lecture seule**.

### Audit de #234 — l'état livré par #213, question par question

**Ce que « Annuler la pose » faisait** (`BuildingPlacementService.rollback`) :

1. exige un placement en mémoire, le moteur disponible, `restorable()`, et le fichier de sauvegarde
   présent ;
2. garde anti-double-clic par emplacement ;
3. `gateway.restore(backup, footprint)` ;
4. `repository.delete(siteId)` ;
5. `sites.markStatus(siteId, EMPTY)` ;
6. retire l'entrée du cache mémoire.

| Question du ticket | Réponse factuelle |
|---|---|
| Le site repasse-t-il réellement `EMPTY` ? | **Oui**, et seulement après une restauration réussie |
| Qu'arrive-t-il à la ligne `building_placements` ? | **Supprimée** — aucun historique ne subsistait |
| Qu'arrive-t-il au backup ? | **Conservé sur disque**, mais plus référencé par rien → orphelin |
| L'opération survit-elle à un restart ? | **Oui** : la base est la source de vérité, `load()` recharge |
| Un site libéré est-il immédiatement réutilisable ? | **Oui** : l'aperçu revérifie `at(siteId)` et le statut |

**Ce qui manquait donc** : (1) aucun historique ; (2) **aucune baseline originale** — chaque pose crée
sa propre sauvegarde, donc implémenter `replace` comme un `rollback + place` aurait fait du
*terrain-avec-hutte* la nouvelle origine, exactement le piège décrit par le ticket ; (3) ni
réorientation ni remplacement ; (4) aucune divergence affichée (la fiche montrait les deux
orientations mais ne disait pas qu'elles divergeaient) ; (5) le placement ne mémorisait **ni version
ni empreinte**, alors que `BuildingDefinition.version` existait déjà.

### Deux constats de l'audit qui ont orienté la conception

* **`SchematicGateway` et `WorldProbe` rendent l'essentiel testable.** C'est ce qui permet de
  vérifier un *ordre d'opérations* — la seule garantie réelle de ce lot — sans serveur ni WorldEdit.
* **`Blueprint.validate()` refuse les blocs hors emprise et les doublons.** Un plan écrit à la main
  produit précisément ces deux fautes, et elles ont été attrapées (voir plus bas).

## Travail effectué

### A. Modèle du cycle de vie

| Type | Nature | Où |
|---|---|---|
| `BuildingSite` | intention / emplacement | `building_sites` (V28) |
| `BuildingDefinition` | contenu déclaratif | `buildings/*.yml` |
| `BuildingPlacement` | fait posé (+ **version** et **empreinte**) | `building_placements` (V29 + V30) |
| `BuildingBaseline` | **terrain d'origine**, en fragments | `building_baselines` (**V30**) |
| `BuildingHistoryEntry` | ce qui a été tenté, et le résultat | `building_placement_history` (**V30**) |
| `BuildingOperation` | `PLACE` / `ROTATE` / `REPLACE` / `RESTORE` | — |

### B. Baseline originale ≠ état d'avant la dernière opération

La distinction centrale du ticket. La sauvegarde de chaque opération sert à **compenser** un échec en
cours de route ; le terrain d'origine est conservé **à part** et **jamais réécrit**.

**Plusieurs fragments par emplacement**, et ce n'est pas une facilité : une tour occupe plus de place
qu'une hutte, donc restaurer une baseline prise sur l'emprise de la hutte laisserait des blocs de
tour *en dehors* — un terrain « presque d'origine », c'est-à-dire faux, et faux d'une manière qui en
a l'air. Un fragment est capturé pour chaque emprise touchée **pour la première fois**, et l'ordre des
opérations garantit que la zone est **vierge** à cet instant (l'ancienne emprise est restaurée
*avant*). Conséquence utile : l'ordre de restauration des fragments n'a aucune importance logique —
il est quand même fixé, pour que deux restaurations successives produisent le même monde bloc pour
bloc.

**Nommage auto-descriptif**, rendu nécessaire par un test : `origine_*` = terrain d'origine à ne
jamais supprimer · `compens_*` = compensation · `backup_*` = capture dont l'origine est déjà conservée
ailleurs. Avant cette correction, le premier fragment gardait le nom `backup_` alors qu'il **est** la
baseline, et rien dans le dossier ne disait lequel des fichiers était intouchable.

### C. Libérer l'emplacement — priorité 1

Ordre non négociable : **restaurer d'abord, libérer ensuite**. Si la restauration échoue,
l'emplacement reste `OCCUPIED` et le placement actif est conservé — il n'y a **jamais de faux
`EMPTY`**. L'échec est journalisé. Idempotent : un second appel ne trouve plus de placement et ne
touche pas au monde.

**La baseline n'est pas supprimée à la libération** : l'emplacement doit pouvoir être rebâti puis
libéré à nouveau et retrouver le *même* terrain d'origine. C'est tout l'objet de la chaîne
d'expérimentation que le ticket décrit.

**Rétrocompatibilité** : un placement antérieur à ce lot n'a pas de fragment de baseline. Il retombe
sur sa sauvegarde de pose — qui **est** le terrain d'origine, puisque c'était la seule opération. Le
cas est distingué à l'écran (`sauvegarde de la pose (aucune baseline enregistrée)`) plutôt que
confondu avec une vraie baseline.

### D. Un emplacement est une intention, un placement est un fait

Changer `facing` ne déplace **aucun bloc**. La fiche affiche les deux lignes et avertit
explicitement que le bâtiment physique n'a pas été modifié. Deux tests le figent : la rotation du
placement est inchangée, et **aucun appel de collage** n'a lieu.

### E. Réorienter et remplacer — une seule séquence, partagée

Les deux gestes exécutent la **même séquence dangereuse**. La partager est une exigence de sûreté,
pas une économie de lignes : deux copies divergeraient, et c'est la moins soignée qui détruirait un
terrain.

1. revérifier, puis comparer le **jeton de l'aperçu** ;
2. **sauvegarder l'union** des deux emprises — la compensation, prise avant toute mutation ;
3. **restaurer le terrain d'origine** : l'ancien bâtiment disparaît, la zone redevient vierge ;
4. **capturer la baseline de la nouvelle emprise** si elle n'est pas déjà couverte ;
5. **coller** ;
6. échec du collage → **remettre la compensation** : code `COMPENSATED`, distinct d'un refus, parce
   que le monde est cohérent et que l'opération n'a simplement pas eu lieu ;
7. n'écrire la fiche qu'**après** un collage réussi.

**Réorienter ne tourne pas les blocs en place** : on repart du terrain d'origine et de la définition
(`terrain + définition + rotation → nouveau placement`), ce qui donne exactement le même résultat
qu'une pose initiale dans cette orientation. Faire tourner les blocs existants accumulerait les
erreurs essai après essai.

L'ordre est vérifié **sur la séquence réelle des appels** au moteur simulé, pas par relecture.

### F. Concurrence et aperçus périmés

Un jeton résume l'emplacement (position, orientation souhaitée, statut), le bâtiment posé (identité,
rotation, empreinte collée) et la cible visée (identité, version, empreinte). Il voyage avec le
formulaire et est **revérifié à la confirmation** : si quoi que ce soit a changé depuis l'affichage,
l'opération est refusée. Même idée que les empreintes de #47. Le verrou par emplacement déjà présent
protège le double-clic et deux administrateurs simultanés.

### G. Version et empreinte

Un placement mémorise la **version déclarée** et l'**empreinte SHA-256 du fichier réellement collé**.
La version peut être oubliée par qui édite le YAML ; le contenu ne peut pas mentir. Sans information
des deux côtés, on **ne conclut pas** — annoncer « à jour » sans le savoir serait une affirmation
qu'on n'a pas le droit de faire.

### H. La tour de garde de test

`test_watchtower_01`, **9 × 9 × 14**, produite par le **même chemin** que la hutte
(`Blueprint → SchematicWorkshop → SchematicGateway → écrivain Sponge V3`). Aucune seconde technique,
aucun fichier externe, aucun type WorldEdit dans le domaine.

Elle met en difficulté ce que la hutte ne testait pas : la hauteur (les limites verticales du monde
deviennent une contrainte réelle), trois niveaux avec planchers **percés**, un escalier en spirale
dont chaque volée regarde une direction différente, des blocs orientés de trois familles, et quatre
faces franchement distinctes :

| Face | Signature |
|---|---|
| nord (façade) | la porte, deux fenêtres, deux torches murales |
| est | une meurtrière par niveau |
| sud | une large ouverture de guet, au dernier niveau seulement |
| ouest | aveugle, en moellon brut là où les autres sont en pierre taillée |

### I. Panel

Sur un emplacement occupé : nom, identifiant, **version posée + empreinte**, **orientation souhaitée
du site**, **orientation du bâtiment posé**, emprise, dimensions, ancre, date, auteur, **source du
terrain d'origine**. Bandeau de divergence, bandeau de version plus récente. Puis aperçu
(n'écrit rien) → **Réorienter** / **Remplacer** → zone rouge séparée pour **Libérer**. Et un
**journal** relevé à la demande.

Le bouton qui écrit dans le monde **n'existe pas** avant un aperçu applicable, et il porte le jeton de
cet aperçu.

**Permissions** : `BUILDING_PLACE` gouverne le fait de *poser* (pose, réorientation, remplacement),
`BUILDING_ROLLBACK` le fait de *vider*. La ligne n'est pas la dangerosité mais le **résultat**.

### J. Villages et villes — préparés, pas commencés

Chaque emplacement reste **indépendant**. Un futur `Settlement` / `ConstructionProject` n'aura qu'à
référencer des identifiants d'emplacements : le cycle de vie décrit ici ne suppose aucun
regroupement, et aucune opération ne traverse plusieurs emplacements.

**Aucune migration n'a été créée « au cas où »**, conformément au §19 du ticket. Le jour où le
regroupement sera utile : une table de projet, et une colonne `project_id` nullable sur
`building_sites` — sans toucher à quoi que ce soit de ce lot.

## Fichiers créés

* `src/main/java/com/lodygames/rpgquest/building/model/BuildingBaseline.java`
* `src/main/java/com/lodygames/rpgquest/building/model/BuildingHistoryEntry.java`
* `src/main/java/com/lodygames/rpgquest/building/model/BuildingOperation.java`
* `src/main/java/com/lodygames/rpgquest/building/TestWatchtowerBlueprint.java`
* `src/main/java/com/lodygames/rpgquest/database/BuildingBaselineRepository.java`
* `src/main/java/com/lodygames/rpgquest/database/BuildingHistoryRepository.java`
* `src/main/resources/buildings/test_watchtower_01.yml`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/building/BuildingRetargetPreview.java`
* `src/test/java/com/lodygames/rpgquest/building/BuildingLifecycleServiceTest.java`
* `src/test/java/com/lodygames/rpgquest/building/TestWatchtowerBlueprintTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/BuildingLifecyclePageTest.java`
* `docs/claude-reports/2026-10-09_2300_cycle-de-vie-des-batiments.md` (ce rapport)

## Fichiers modifiés

**Moteur** : `building/BuildingPlacementService.java` (baseline, libération, réorientation,
remplacement, jetons, journal), `building/SchematicWorkshop.java` (généralisé aux deux plans),
`building/SchematicGateway.java` + `building/worldedit/WorldEditSchematicGateway.java`
(`fingerprint`), `building/BuildingLibrary.java` (second exemple livré),
`building/model/BuildingPlacement.java` (version, empreinte, `outdatedAgainst`),
`building/model/BuildingFootprint.java` (`union`, `covers`),
`database/SchemaMigrator.java` (**V30**), `database/BuildingPlacementRepository.java`,
`bootstrap/RPGQuestBootstrap.java`,
`web/agent/{AgentActionType,AgentActions,BukkitAgentActions,AgentActionExecutor}.java`.

**Panel** : `panel/agent/AgentActionCatalog.java`, `panel/web/AgentPages.java`,
`panel/building/{BuildingPlacementView,BuildingSiteDirectory}.java`.

**Tests** : `building/BuildingPlacementServiceTest.java`, `building/BuildingLibraryTest.java`,
`web/agent/{AgentActionExecutorTest,StubAgentActions}.java`,
`panel/web/BuildingPlacementPageTest.java`.

**Documentation** : `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`,
`docs/MANUAL_TEST_PLAN.md` (TC-274),
`control-panel/src/main/resources/docs/batiments-bibliotheque.md`.

## Base de données / migrations

**Migration V30, strictement additive.** `CURRENT_VERSION` passe de 29 à 30.

| Changement | Nature |
|---|---|
| `building_placements.building_version INTEGER NOT NULL DEFAULT 0` | colonne ajoutée |
| `building_placements.schematic_sha256 TEXT NOT NULL DEFAULT ''` | colonne ajoutée |
| `building_baselines` (+ index `site_id`) | table créée |
| `building_placement_history` (+ index `site_id, id`) | table créée |

Aucune colonne existante n'est modifiée, aucune ligne réécrite. Les deux `ALTER TABLE` vérifient
d'abord l'existence de la colonne (`dialect.columnExists`), donc l'étape est **rejouable** — ce que
`CREATE TABLE IF NOT EXISTS` est nativement mais pas `ALTER TABLE ADD COLUMN`. Un emplacement déjà
occupé avant cette étape reste exploitable : ses nouvelles colonnes valent leurs défauts, qui se
lisent « inconnu » et non « zéro ».

## Configuration / données

Aucune nouvelle clé de `config.yml`, aucun nouveau message. **Un nouveau fichier de contenu livré** :
`buildings/test_watchtower_01.yml`, déposé au démarrage s'il manque et **jamais écrasé** s'il existe.
Le `.schem` correspondant est **produit par le plugin** au démarrage s'il manque, par le même chemin
que la hutte.

## Tests automatiques

**Tests ciblés pendant le développement**, puis **une seule** build complète finale, depuis un
worktree propre, après `./gradlew --stop` :

```
./gradlew clean build   (worktree propre sur 707f496)
BUILD SUCCESSFUL in 42m 19s
3462 tests, 0 échec, 0 erreur, 38 ignorés
  plugin         2225  (37 ignorés — limitations MockBukkit héritées)
  control-panel  1207  (1 ignoré)
  web-api          30
```

**+73 tests** par rapport au lot précédent (3389).

**Tests ajoutés pour ce lot :**

| Classe | Objet |
|---|---|
| `BuildingLifecycleServiceTest` (35) | libération (succès, échec → reste `OCCUPIED`, idempotence, survie au restart, baseline survivante), divergence (fraîche, après changement de `facing`, résorbée par réorientation), réorientation (**ordre réel des appels**, emprise, refus si identique, hors limites, compensation sur échec, fiche écrite seulement après succès, jeton périmé, site modifié entre aperçu et confirmation), remplacement (petit↔grand, fragment capturé pour la zone agrandie, aucun fragment superflu, chevauchement refusé, bâtiment inconnu), **baseline sans dérive** après quatre opérations, version/empreinte, journal (types, ordre, contenu, survie à la libération) |
| `TestWatchtowerBlueprintTest` (20) | cohérence du plan, dimensions, blocs dans l'emprise, ancre = porte, **les quatre faces comparées deux à deux**, accès vertical réel (4 marches + trémie + palier plein par niveau), trois orientations de marches, intérieur vide, terrasse et créneaux, définition = plan, déterminisme |
| `BuildingLifecyclePageTest` (15) | la fiche rendue par HTTP : les deux orientations, l'affirmation qu'aucun bloc n'a bougé, version + empreinte, version plus récente annoncée mais jamais appliquée, **aucun bouton d'écriture avant un aperçu**, jeton transporté, aperçu refusé n'ouvre rien, « inconnu » ≠ « 0 », journal avec succès **et** échecs, journal d'un autre emplacement ignoré, formulaire réel, orientation hors liste blanche refusée, CSRF |

**Trois fautes réelles attrapées par les tests, pas par relecture :**

1. une **torche posée sur une marche** au niveau 2 de la tour — `Blueprint.validate()` l'aurait
   refusée, mais mieux valait ne pas écrire la faute ;
2. un **contrefort qui doublait le mur ouest** : même position, même refus. Remplacé par un
   changement de **matériau**, qui donne le même effet visuel sans bloc en conflit ;
3. des volées de **trois marches** là où quatre sont nécessaires. Deux planchers sont séparés de
   quatre blocs et une marche ne fait gagner qu'un demi-bloc : la troisième culminait à 3,5 quand le
   plancher s'atteint à 5,0. **La tour aurait été invisitable**, et cela ne se serait vu qu'en jeu.

**Quatre attentes de test actualisées, et c'est le contrat qui a changé** : le libellé « Annuler la
pose » devient « Libérer l'emplacement », et la promesse n'est plus « la zone telle qu'elle était
avant la pose » mais « le terrain d'origine, celui d'avant le premier bâtiment ». Les nouvelles
assertions portent sur les nouvelles garanties. `BuildingLibraryTest` passe de un à deux exemples
livrés, et gagne le même garde-fou définition/plan pour la tour.

## Tests manuels à effectuer

* **TC-274** (nouveau, ~20 min) — le parcours complet en jeu : préparer un emplacement de test,
  **libérer** et constater le retour du terrain, constater qu'une rotation de site **ne déplace aucun
  bloc**, **réorienter**, **remplacer** par la tour et **la visiter** (entrer, monter les trois
  volées, déboucher sur la terrasse, reconnaître les quatre faces), puis enchaîner plusieurs essais
  et vérifier que le terrain restauré est bien celui du **tout début**. `PENDING MANUAL VALIDATION`.
* L'étape **D** est la plus importante : si le terrain restauré porte encore la trace d'un bâtiment,
  la baseline a dérivé et toute la chaîne d'expérimentation est fausse.
* Restent par ailleurs : TC-273, TC-272, TC-271, TC-270, TC-269, TC-268, TC-267, TC-265, TC-266,
  TC-264, TC-257, TC-258..TC-263.

**Aucune mutation du monde réel n'a été faite depuis la machine**, conformément à la consigne.

## Résultat attendu

Sur un emplacement occupé, la fiche dit ce qu'il porte, avec quelle version, dans quelle orientation,
et d'où viendrait le terrain si on le libérait. Si l'orientation souhaitée a changé depuis la pose,
elle le signale sans avoir touché à un bloc. Quatre actions sont proposées, l'aperçu d'abord, la
libération dans une zone rouge séparée. Après plusieurs essais, « Restaurer le terrain et libérer »
rend le terrain d'avant le **premier** bâtiment.

## Reset / retour à l'état initial

La migration V30 est additive : revenir en arrière ne demande rien (les colonnes et tables en trop
sont simplement ignorées par l'ancien code). Les fichiers `origine_*` dans
`plugins/RPGQuest/schematics/` sont le terrain d'origine des emplacements : **ne pas les supprimer**
tant que les emplacements existent.

## Déploiement VeryGames

### À transférer

1. **Panel** (`scripts/plugadmin/deploy.sh`) — quatre nouvelles actions, la section « Bâtiment
   posé » réécrite, la projection d'aperçu de transformation.
2. **JAR du plugin** — le moteur de cycle de vie, la migration V30, la tour de garde.
3. **Rien d'autre.** Contrairement au lot #235, **aucun `--also` n'est nécessaire** :
   `buildings/test_watchtower_01.yml` **est** dans `BuildingLibrary.BUNDLED_EXAMPLES` (donc déposé
   au démarrage s'il manque), et `test_watchtower_01.schem` est **produit par le plugin**.

**Fait le 2026-10-09, 19:15–19:25 UTC+2** : panel d'abord, puis le JAR, puis **un seul
redémarrage**, **0 joueur connecté** avant *et* après.

| | Valeur |
|---|---|
| JAR déployé | **2 213 495 o**, SHA-256 `171020a69eb0c09016c32ed5ae6f566607fa4bccc92a3fcddd40d6a4007824c8` |
| Backup JAR | `rpgquest-20261009T171623Z-predeploy.jar` (**2 159 437 o** — plus petit que le neuf) |
| **Backup `data.db` AVANT migration** | `data-20261009T163556Z-pre-v30.db`, **1 802 240 o**, SHA-256 `7ef3c563…`, `integrity_check` **ok**, `user_version` 29 |

Le script de déploiement ne touche **jamais** `data.db` — mais il ne le sauvegarde pas non plus. La
sauvegarde a donc été prise à la main, avant le transfert, et son intégrité vérifiée.

### Ne PAS transférer/altérer

`data.db` (migré par le plugin, jamais par un transfert), `config.yml`, `messages.yml`, `spawn.yml`,
les mondes, `plugins/Citizens/`, les dialogues et quêtes du serveur.

⚠️ **`plugins/RPGQuest/schematics/origine_*.schem` est le terrain d'origine des emplacements : ne
jamais les supprimer** tant que les emplacements existent. Les `compens_*` et `backup_*` sont des
sauvegardes d'étape, supprimables sans risque après coup.

### Redémarrage requis

**Oui, un seul** — effectué. La migration s'applique au démarrage, et les quatre nouvelles actions
ne sont connues du moteur qu'après.

### Migration automatique

**Oui : V30, additive.** Vérifiée sur la base réelle après redémarrage :

| Vérification | Résultat |
|---|---|
| `PRAGMA integrity_check` | **ok** |
| `PRAGMA user_version` | **30** (était 29) |
| Tables créées | `building_baselines`, `building_placement_history` |
| Index créés | `idx_building_baselines_site`, `idx_building_history_site` |
| Colonnes de `building_placements` | **18** (étaient 16) |
| Tables au total | **38** (étaient 36) |
| Placement existant | **intact** : `buildsite_0006` / `test_hut_01` / rotation **180** / backup inchangé ; `building_version = 0` et `schematic_sha256 = ''`, les défauts qui se lisent « inconnu » |

### Vérifié sur le serveur réel

| Vérification | Résultat |
|---|---|
| Plugins | **Citizens, RPGQuest, WorldEdit verts** ; `rpgquest version` → `v0.1.0-SNAPSHOT` |
| Panel : ce qu'il **sert** | les quatre actions, `BuildingRetargetPreview.class`, « Orientation souhaitée du site », « Orientation du bâtiment posé », « Version posée », « Journal des opérations », « Relever le journal » ; fiche d'aide à jour |
| Tour **générée par le plugin** | `schematics/test_watchtower_01.schem` **présent, 641 o** — absent avant le redémarrage |
| Définition déposée | `buildings/test_watchtower_01.yml`, **2 455 o** |

**Aucune mutation du monde depuis la machine** : aucun bâtiment posé, aucun bloc touché, aucun
emplacement modifié.

### À savoir pour le test de demain

L'emplacement réel **`buildsite_0006` diverge déjà** : site `NORTH`, hutte posée à **180°** —
l'orientation du site a été changée après la pose, probablement pendant TC-270. La fiche affichera
donc l'avertissement de divergence sur cet emplacement : **ce n'est pas une régression**, c'est
exactement le cas que ce lot rend visible, et il est observable immédiatement.

Ce placement n'a pas de baseline (il précède ce lot) : il retombe sur sa sauvegarde de pose,
`backup_buildsite_0006_1791498372735.schem`, **vérifiée présente (321 o)**. Elle *est* le terrain
d'origine, puisque la pose était sa seule opération — et l'écran le dit explicitement plutôt que de
la confondre avec une vraie baseline.

### Effet de bord à connaître

**`building.placement.rollback` a changé de sens, en mieux** : l'action rendait l'état d'avant la
dernière pose, elle rend désormais le **terrain d'origine** et libère l'emplacement. Pour tout
placement n'ayant eu qu'une pose — le seul cas possible avant ce lot — les deux coïncident.

## Rollback

* **Plugin** : `scripts/rollback-verygames.sh --latest`, puis redémarrer. La migration V30 reste en
  base — elle est additive, donc l'ancien code l'ignore. Les quatre nouvelles actions redeviennent
  inconnues de l'agent ; `building.placement.rollback` retrouve son ancienne sémantique (restaurer la
  sauvegarde de la pose), qui coïncide avec la baseline pour tout emplacement n'ayant eu qu'une pose.
* **Panel** : `scripts/plugadmin/rollback.sh app` puis `systemctl restart plugadmin`.
* **Un placement précis** se défait depuis sa fiche.

## Logs / diagnostic

* Journal d'actions de l'agent : l'aperçu de transformation porte `operation`, les deux emprises, le
  recouvrement, `restore_source`, les refus, les avertissements et le `token`. Les mutations portent
  rotation, emprise, version, empreinte et nom de la compensation.
* `building_placement_history` : consultable depuis la fiche (« Relever le journal »), **échecs
  compris**.
* `plugins/RPGQuest/schematics/` : les préfixes `origine_` / `compens_` / `backup_` disent le rôle de
  chaque sauvegarde.

## Documentation mise à jour

* `docs/RPGQUEST_BIBLE.md` — cycle de vie, baseline vs compensation, intention vs fait, ordre des
  opérations, permissions, historique, préparation villages/villes, et la tour de garde.
* `control-panel/src/main/resources/docs/batiments-bibliotheque.md` — fiche d'aide consultable
  **depuis le panel** : les quatre actions, la convention de nommage des sauvegardes, les jetons
  d'aperçu, les versions, et les cas de refus.
* `docs/current_state.md` — état courant.
* `docs/MANUAL_TEST_PLAN.md` — **TC-274** + ligne d'index.

## Une erreur de ma part, et sa correction

Mon `git add -A src/` du commit `4729373` a **ajouté au suivi Git les sept fichiers de contenu que
vous éditez depuis PlugAdmin** (`mira_cartographer.yml`, `jeff_skeleton.yml`, `lily_pumpkin.yml`,
`st0_meet_people.yml`, `tc265_ai_securiser_environs.yml`, `test_remise.yml`, `lily_memories.yml`),
alors que la consigne était de les préserver **non suivis**. C'est une faute de ma part : un
`git add -A` sur un dossier qui contient du contenu utilisateur ne distingue pas ce qui m'appartient
de ce qui vous appartient.

**Corrigé par un retrait du suivi** (`git rm --cached`), et **jamais par une réécriture
d'historique** — la branche était déjà poussée. Les fichiers sont **intacts sur le disque** :
empreintes vérifiées identiques avant et après, seul l'index Git a changé. Ils sont de nouveau
`??` dans `git status`.

**Conséquence sur le JAR déployé, assumée et sans effet fonctionnel** : le JAR de ce lot les
embarque comme ressources. Elles sont **inertes** — aucune ne figure dans les listes
`BUNDLED_EXAMPLES` de `YamlQuestEngine`, `StoryRegistry` ni `YamlDialogueEngine`, donc **rien ne les
dépose sur le serveur** ; ce sont quelques kilo-octets inutiles dans l'archive. Le prochain JAR, bâti
après le commit de correction, ne les contiendra plus. Aucun fichier de votre serveur n'a été
modifié, et aucune de vos éditions n'a été perdue.

## Limitations / travail restant

* **TC-274 n'est pas exécuté** : la correction d'un collage, l'orientation d'un escalier et le fait
  de pouvoir monter se constatent avec un client. **#234 ne doit pas être fermée** avant.
* **La réorientation d'un placement sans baseline** (antérieur à ce lot) utilise sa sauvegarde de
  pose. C'est correct — c'était la seule opération — mais l'écran le dit explicitement plutôt que de
  laisser croire à une vraie baseline.
* **Les fragments de baseline s'accumulent** si un emplacement reçoit des bâtiments de tailles
  croissantes. Aucun nettoyage automatique : supprimer un fragment reviendrait à perdre une partie du
  terrain d'origine. Un outil d'entretien explicite serait un sous-ticket raisonnable.
* **La mise à jour d'un bâtiment vers une nouvelle version** n'a pas d'action dédiée : on passe par
  « Remplacer » avec le même bâtiment. Le ticket l'autorisait (« hors MVP si nécessaire »).
* **Villages/villes** : non commencés, et volontairement non préparés par une migration.
* **#156** : audit non commencé à l'heure de ce rapport — voir « Prochaine étape ».

## Prochaine étape suggérée

**TC-274** (~20 min, un client Minecraft), en commençant par un emplacement de test neuf. Puis, selon
le temps, l'**audit de #156** demandé en fin de prompt — diagnostic seul, sans toucher à la politique
de densité des bornes.
