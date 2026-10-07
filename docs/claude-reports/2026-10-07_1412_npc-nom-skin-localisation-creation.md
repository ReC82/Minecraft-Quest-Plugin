# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-07
* Heure : 14:12 (heure locale, CEST)
* Sujet : PNJ — nom et skin rendus indépendants (ou refus explicite), couleurs via StyleField, nom et localisation réels, et création complète d'un PNJ depuis le panel
* Statut : DONE
* Branche Git : `feature/169-special-mobs-boss`
* Commit actuel si disponible : `2c9db65` (poussé) + le commit de ce rapport
* Début de la tâche : 2026-10-07 11:55:56
* Fin de la tâche : 2026-10-07 14:11:59
* Durée totale : 02:16:03

## Demande

Corriger trois défauts constatés sur le PNJ « Tan » — renommer change le skin,
colorer le nom impose d'écrire des balises, le panel affiche un nom périmé — puis,
sur précisions ultérieures : préserver aussi les skins posés directement via
Citizens ou antérieurs à la migration, vérifier les **capacités réelles** de
Citizens et ne rien prétendre sans preuve, refuser proprement si la préservation
est impossible. Ajouter l'affichage de la localisation réelle, un formulaire de
création de PNJ (nom obligatoire avec StyleField, skin et localisation
optionnels, placement près du Guide à défaut), des aides par champ, le respect
des permissions existantes sans droit implicite, un nettoyage compensatoire avec
gestion de ses échecs, et une protection contre les doubles clics et les retries.
Travailler depuis la branche contenant `c97d96f`, worktree propre, sans
sous-agents, monitors ni Gradle parallèle. Puis commit, push et déploiement
autorisé, en regroupant le redémarrage Minecraft et en prévenant les joueurs.

## Analyse

### Continuité de la branche, vérifiée avant de déployer

`feature/169-special-mobs-boss` contenait bien `c97d96f` et **`e0c1203`**, le
commit dont l'artefact tournait en production — c'est le contrôle qui manquait
lors de la régression de la veille. Les quatre branches ayant des commits
d'avance (Discord #202, #27, #10, `main`) ont été inspectées : aucune ne touche
aux PNJ, aux mobs ni au panel. Boss/Mobs, LuckPerms et le correctif refresh
`/ops` sont donc bien dans le périmètre livré.

### Défaut 1 — renommer changeait le skin

Ni le panel ni l'agent : leurs deux chemins sont déjà strictement séparés (deux
formulaires, deux actions, deux boutons). La cause est **Citizens** : un PNJ de
type `PLAYER` sans skin explicite dérive son apparence de son **nom**, donc
`NPC#setName(...)` la change par effet de bord. Les PNJ sont bien créés en
`EntityType.PLAYER` (`createNPC(EntityType.PLAYER, name)`).

**Vérification des capacités réelles, avant toute promesse.** L'artefact
`citizensapi` — seule dépendance Citizens du projet — a été inspecté :

- **0 classe** contenant « Skin » (ni `SkinTrait`, ni `SkinnableEntity`) ;
- l'enum public `NPC.Metadata` contient 47 clés, **aucune** liée au skin ;
- en revanche `NPC#data()` (`MetadataStore`) et le trait public `MobType`
  existent.

Conclusion : **lire le skin appliqué est impossible** par cette API. Y accéder
demanderait de coder en dur les clés internes de Citizens — précisément ce que le
projet refuse, et ce que je ne peux pas valider.

Ma première implémentation « rattacher l'ancien nom pour préserver l'apparence »
était donc **fausse** dans le cas qui compte : si un skin explicite avait été posé
via Citizens, la rattacher au nom l'aurait **écrasé** par une texture dérivée d'un
pseudo. C'est exactement le remplacement silencieux interdit. Elle a été
remplacée.

### Défaut 2 — colorer le nom imposait des balises

Le champ « Nom du PNJ » de la définition était un `<input>` nu, alors que #195
avait livré `StyleField`, composant partagé déjà appliqué aux noms de mobs et aux
textes de dialogue.

### Défaut 3 — le panel affichait un nom périmé

Pas un cache. `npc.list.displayName` est résolu depuis la **définition** RPGQuest
(`npcs/<id>.yml`), ou à défaut depuis le locuteur du dialogue — il n'est **jamais**
lu depuis Citizens. L'afficher sous le libellé « Nom en jeu » montrait donc un nom
qui ne pouvait pas changer, quel que soit le nombre de rafraîchissements. Les deux
noms ont des fonctions différentes.

### Défaut trouvé en traitant les doubles clics

`NpcDefinitionStore#create` faisait un `Files.exists()` puis écrivait avec
`REPLACE_EXISTING`. Deux tentatives simultanées du même identifiant passaient
toutes les deux le contrôle, la seconde **écrasait** la première, puis son propre
nettoyage compensatoire **supprimait la définition que la première venait
légitimement de créer**. C'était la conséquence la plus grave possible d'un double
clic, et elle n'était visible qu'en combinant les deux exigences.

## Travail effectué

### 1. Nom et skin réellement indépendants

`SkinPreservationPlanner` — classe **pure**, donc testable sans Citizens — porte
la décision :

| Cas | Décision |
|---|---|
| PNJ **non joueur** (type lu via le trait public `MobType`) | renommage **libre** : pas de skin, le nom n'influence rien |
| PNJ joueur, **source connue** de RPGQuest | source réappliquée **avant** le renommage ; un échec d'ancrage **annule tout** sans renommer |
| PNJ joueur, **skin inconnu** | **refus** `SKIN_SOURCE_UNKNOWN`, avec la limite expliquée et la sortie indiquée |
| Type **indéterminable** | **refus** `NPC_TYPE_UNKNOWN` |

Pour que le refus soit praticable, `npc.citizens.skin` accepte désormais **deux
sources réellement supportées** : un lien MineSkin ou un **pseudo Minecraft**. La
source appliquée est enregistrée (`npc_citizens_skins`, **migration V27**), ce qui
rend les renommages suivants sûrs. Appliquer un skin ne touche jamais au nom.
RPGQuest ne fait **aucun appel réseau** : Citizens résout et télécharge, donc un
succès signifie « demande transmise ».

### 2. Couleurs et nom réellement affiché

Le nom de définition passe sur `StyleField` (#195) — aucun composant parallèle,
champ soumis et valeur MiniMessage inchangés, noms multi-styles préservés. Le
champ « Nom en jeu » vient du relevé `npc.citizens.list` ; la liste montre les
deux noms quand ils diffèrent (tous deux cherchables) ; sans relevé récent le
champ reste **vide avec la raison** plutôt que rempli avec un autre nom. Les deux
actions déclaraient déjà `npc.list` et `npc.citizens.list` en relevés de suivi :
la fiche et la liste se réactualisent après succès.

### 3. Localisation

La vue roster expose désormais la position Citizens réelle : monde, X/Y/Z,
yaw/pitch, `spawned`, et `liveLocation` qui distingue la position de l'entité
présente de la **dernière position enregistrée** d'un PNJ non apparu. Affichée
dans la liste (compacte) et la fiche (avec orientation), avec la **fraîcheur du
relevé** et, si aucune position n'est exploitable, **la raison**. Jamais déduite de
la définition RPGQuest, qui n'en contient aucune.

### 4. Création complète d'un PNJ

Une **seule** action agent, `npc.citizens.provision`, qui réutilise le parcours
canonique (`CitizensSpawnPlanner` / `CitizensSpawnCoordinator`) — jamais un second
système.

**Permissions (choix 1 retenu par l'utilisateur)** : l'action produit les effets
de trois permissions, donc elle les exige toutes les trois (`NPC_WRITE`,
`NPC_SPAWN_WRITE`, `NPC_BIND_WRITE`, ce dernier couvrant liaison **et** skin). Le
catalogue sait désormais déclarer des permissions supplémentaires
(`Spec#alsoRequires`), vérifiées au **rendu** et à l'**exécution** : le bouton
n'apparaît pas si l'une manque, et une requête forgée est refusée. Regrouper des
effets n'accorde aucun droit implicite.

**Identifiant déduit du nom** (« Bob le Bûcheron » → `bob_le_bucheron`), sans
accents ni balises — ce qui sert aussi de garde-fou anti-doublon.

**Localisation optionnelle, jamais partielle** : monde **et** X/Y/Z ensemble, ou
les quatre vides. Refusée des deux côtés. Coordonnées validées (finies, bords de
monde, bornes réelles du monde chargé).

**Sans localisation, placement près du Guide**, identifié par sa **définition
stable** (`hub.guide-npc-id`) et jamais par son nom affiché. `NpcPlacementPlanner`
est une classe **pure** : le monde n'y est vu qu'à travers une interface `Probe`,
ce qui rend testables les cas impossibles à provoquer à la demande. Elle visite
les candidats par **distance totale croissante**, exige un sol plein et deux cases
libres, évite liquides, portails, vide et chevauchement, et **ne casse ni ne pose
aucun bloc**. Bornes configurables et validées : `search-radius` 1–64,
`vertical-radius` 0–32, `max-attempts` 1–100000. Guide absent, non lié, position
inconnue, hors Hub, Hub non chargé, aucun emplacement : **un code et un message
chacun**, et aucun PNJ partiel.

**Skin optionnel** validé **avant** toute création : une URL invalide ne laisse
jamais un PNJ derrière elle. Champ vide = apparence par défaut documentée, sans
aucun appel.

### 5. Nettoyage compensatoire, avec gestion de ses propres échecs

| Code | Situation |
|---|---|
| `PROVISION_ROLLED_BACK` | nettoyage complet, plus rien ne subsiste |
| `PROVISION_CLEANUP_SKIPPED` | une liaison Citizens référence déjà cet identifiant → **rien n'est supprimé**, et c'est dit |
| `PROVISION_CLEANUP_INCOMPLETE` | le retrait a échoué → le message nomme ce qui reste à retirer à la main |
| `PROVISIONED_SKIN_FAILED` | le PNJ est créé et valide, seul le skin n'est pas passé |

Détruire un PNJ correct parce qu'une texture n'a pas été acceptée serait
disproportionné : on le garde et on le dit.

### 6. Doubles clics et retries

1. action **rejouée** (même identifiant) → résultat mémorisé, jamais réexécutée
   (`ProcessedActionCache`, déjà en place) ;
2. **double clic** → deux actions distinctes : `create` écrit désormais en
   `CREATE_NEW`, donc le système de fichiers arbitre et **une seule** réussit.
   `compensate` vérifie en plus l'absence de liaison avant de supprimer quoi que
   ce soit ;
3. côté navigateur, le bouton se désactive à l'envoi — **confort seulement**, la
   garantie vient du serveur.

### 7. Aides et permissions

Chaque nouveau champ porte une explication, un exemple, son défaut et son
comportement à vide, dans un bloc rendu **sous** le champ et empilé sur téléphone.
Le formulaire énonce les trois droits requis.

## Fichiers créés

* `src/main/java/com/lodygames/rpgquest/npc/SkinPreservationPlanner.java`
* `src/main/java/com/lodygames/rpgquest/npc/NpcPlacementPlanner.java`
* `src/main/java/com/lodygames/rpgquest/npc/BukkitNpcPlacementProbe.java`
* `src/main/java/com/lodygames/rpgquest/database/NpcSkinSourceRepository.java`
* `src/test/java/.../SkinPreservationPlannerTest.java`,
  `NpcPlacementPlannerTest.java`, `NpcDefinitionCreateAtomicityTest.java`,
  `NpcSkinSourceRepositoryTest.java`, `NpcProvisionSlugTest.java`
* `docs/claude-reports/2026-10-07_1412_npc-nom-skin-localisation-creation.md`

## Fichiers modifiés

Plugin : `CitizensNpc`, `CitizensNpcBridge`, `NpcIdentityService`,
`NpcDefinitionStore`, `SchemaMigrator`, `HubConfig`, `ConfigValidator`,
`RPGQuestBootstrap`, `AgentActions`, `AgentActionType`, `AgentActionExecutor`,
`BukkitAgentActions`, `config.yml`, et quatre fichiers de test.

Panel : `AgentActionCatalog`, `AgentPages`, `PanelApp`, `panel.js`,
`plugadmin.css`, `NpcsCatalogTest`.

Docs : `RPGQUEST_BIBLE.md`, `SERVER_CHANGELOG.md`.

**Non touchés** : les cinq fichiers de contenu locaux de l'utilisateur (ils vivent
dans `/srv/rpgquest/repo`, ce travail s'est fait dans un worktree distinct), les
données, les clés et les configurations déployées.

## Base de données / migrations

**Migration V27** — `npc_citizens_skins` (clé = UUID Citizens, source
`URL`/`NAME`, horodatage). Table neuve, aucune donnée existante touchée,
idempotente. `CURRENT_VERSION` porté à 27 — un test dédié avait d'ailleurs échoué
parce que je l'avais oublié, ce qui est exactement son rôle.

## Configuration / données

Nouvelles clés, toutes avec un défaut (aucune édition requise) :
`hub.guide-npc-id`, `hub.placement.search-radius` / `vertical-radius` /
`max-attempts`. Aucun fichier de contenu transféré au serveur.

## Tests automatiques

`RPGQUEST_TEST_MAX_HEAP=768m ./gradlew test build` sur le worktree propre →
**BUILD SUCCESSFUL** : **1742 tests plugin (0 échec, 37 ignorés)** et
**703 tests panel (0 échec, 1 ignoré)**.

Couverture ajoutée, par exigence demandée :

* **renommage seul / skin conservé**, et les refus : `SkinPreservationPlannerTest`
  (7 tests, matrice complète type × source connue, dont « un refus porte toujours
  un code, une autorisation jamais ») ;
* **skin seul / nom conservé** : l'action ne touche pas au nom (chemin distinct),
  et `NpcSkinSourceRepositoryTest` (6 tests) vérifie l'enregistrement, le
  remplacement, l'isolation entre PNJ et la **persistance après réouverture de la
  base** ;
* **nom coloré et multi-styles** : `NpcsCatalogTest` vérifie `StyleField` et la
  préservation d'un `<yellow>Garde</yellow>` existant ;
* **actualisation nom + position** : champ alimenté par le relevé Citizens, deux
  noms distingués, relevés de suivi déclarés, et trois tests de localisation
  (présent / non apparu / position inconnue avec sa raison) ;
* **création avec position explicite** et **sans position** : paramètres transmis
  exactement, aucune position imposée quand elle est omise ;
* **skin optionnel et URL invalide** : refus avant toute création, aucune action
  enfilée ;
* **coordonnées partielles**, **Y hors bornes** : refusés ;
* **Guide absent / aucun emplacement libre** : `NpcPlacementPlannerTest`
  (11 tests : vide intégral, liquide, portail, obstacle à hauteur de tête, PNJ
  superposés, budget épuisé, hors rayon, bornes invalides) ;
* **absence de doublon et nettoyage** : `NpcDefinitionCreateAtomicityTest`
  (4 tests, dont **8 créations concurrentes du même identifiant → exactement une
  réussit**, et un nettoyage idempotent qui ne touche que sa propre définition) ;
* **liens dialogues/quêtes préservés** : vérifié sur la fiche après renommage.

Un test a révélé un vrai défaut de conception que j'ai corrigé : la recherche
préférait un emplacement **deux blocs au-dessus** du Guide à un emplacement un
bloc à côté.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION`. **Je ne me suis connecté à aucun compte du panel et
n'ai créé aucun PNJ de test en jeu** : créer puis renommer un PNJ Citizens exige
une session du panel ou un joueur en jeu. Tan et les autres PNJ réels n'ont servi
à rien d'autre qu'à l'analyse.

Restent à valider par l'utilisateur :

1. renommage d'un PNJ dont le skin a été posé depuis le panel → skin conservé ;
2. renommage d'un PNJ au skin inconnu → **refus** lisible, puis application d'un
   skin, puis renommage → passe ;
3. skin seul → nom inchangé ; nom coloré via StyleField ; nom multi-styles
   préservé ;
4. fiche et liste après renommage → nom et position à jour au relevé suivant ;
5. création avec position explicite, et sans position (près du Guide) ;
6. skin invalide, coordonnées partielles, monde inexistant, Guide absent, aucun
   emplacement libre → messages explicites, aucun PNJ partiel ;
7. double clic sur « Créer le PNJ » → un seul PNJ ;
8. persistance après rechargement/redémarrage, et liens dialogues/quêtes intacts.

## Résultat attendu

Le nom affiché et le skin d'un PNJ sont indépendants — ou, quand c'est
techniquement impossible de le garantir, l'action est refusée en expliquant
pourquoi plutôt que d'abîmer l'apparence. Le panel montre ce qui est réellement en
jeu (nom Citizens, position, fraîcheur) et non ce que la définition contient. Un
PNJ complet se crée en un geste, avec un placement sûr, sans droit implicite et
sans jamais laisser de PNJ à moitié créé.

## Reset / retour à l'état initial

Aucun reset nécessaire : aucune donnée joueur ni contenu serveur modifié.

## Déploiement VeryGames

### À transférer

* **Plugin, VeryGames DEV** : `rpgquest-0.1.0-SNAPSHOT.jar` — 1 895 818 o,
  SHA-256 `db44b220dbd936709d9869c158695e1f716617c04b8c00f0f8767aadaf85d239`.
  Transféré ; backup préalable `rpgquest-20261007T120918Z-predeploy.jar`.
* **Control Panel, AWS** : `scripts/plugadmin/deploy.sh`, release précédente
  sauvegardée, `/health` → ONLINE, artefact installé contenant
  `npc.citizens.provision`.

### Ne PAS transférer/altérer

`data.db`, `config.yml` déployé, `messages.yml`, `spawn.yml`,
`RPGQuest/Citizens/`, les mondes, et tous les fichiers de contenu. Aucun `--also`
utilisé.

### Redémarrage requis

Oui, **un seul**, regroupé pour les quatre changements. 1 joueur connecté
(`LoDyMcFly`), **prévenu deux fois en jeu** (préavis ~30 s puis annonce
immédiate), `save-all`, OFFLINE puis **ONLINE**. Vérifications :
`/rpgquest version` → `v0.1.0-SNAPSHOT` ; `/plugins` → 5 plugins verts dont
LuckPerms ; heartbeat `uptime_seconds=5` (redémarrage réel) ; `/npcs`, `/mobs`,
`/ops` en `303`.

### Migration automatique

Oui, **V27**, appliquée au démarrage. Le plugin ayant démarré proprement
(heartbeat ONLINE), elle s'est appliquée sans erreur — même niveau de preuve que
les migrations précédentes, ce compte n'ayant pas d'accès direct aux logs.

## Rollback

`scripts/rollback-verygames.sh --latest` restaure
`rpgquest-20261007T120918Z-predeploy.jar`, puis redémarrer. La table V27 reste en
place et est inerte pour une version antérieure : le runner ignore toute version
déjà appliquée et ne rétrograde jamais.
`scripts/plugadmin/rollback.sh app` pour le Control Panel.

## Logs / diagnostic

Aucune instrumentation ajoutée. Preuves utilisées : inspection du jar
`citizensapi` (absence d'API de skin), `javap` sur `NPC`, `NPC.Metadata`,
`MetadataStore` et `MobType`, heartbeat de l'agent, codes HTTP des routes, et
contenu de l'artefact panel installé.

## Documentation mise à jour

* `docs/RPGQUEST_BIBLE.md` — sections « Nom et apparence réellement
  indépendants » et « Créer un PNJ complet depuis le panel », avec les tableaux
  des codes de nettoyage et les trois niveaux de protection anti-doublon.
* `docs/deployment/SERVER_CHANGELOG.md` — entrée du 2026-10-07 (lot 2) : SHA
  livrés et sauvegardés, migration V27, nouvelles clés de configuration,
  redémarrage unique, et ce qui reste à valider.

## Limitations / travail restant

* **Lire le skin d'un PNJ reste impossible** avec `citizensapi`. C'est la raison
  du refus, pas un choix de confort. Le lever demanderait soit la dépendance
  `citizens-main`, soit un couplage aux clés de métadonnées internes de Citizens —
  décision d'architecture qui n'était pas demandée.
* Les validations en navigateur et en jeu restent entières (liste ci-dessus) : je
  n'ai créé aucun PNJ de test, faute de session.
* `NpcSkinSourceRepository.Kind.NAME` ne se remplit que par une application
  explicite « pseudo Minecraft » : les skins posés via Citizens avant cette
  version restent inconnus de RPGQuest, donc leurs PNJ joueurs ne sont renommables
  qu'après avoir appliqué un skin depuis le panel. C'est le compromis assumé.
* La recherche d'emplacement ne considère que des cases de bloc entières et une
  hauteur de 2 : un PNJ de gabarit inhabituel n'est pas modélisé.
* `RestartServiceTest` reste sensible à la charge (constaté la veille), sans lien
  avec ce travail.

## Prochaine étape suggérée

Valider les huit points en navigateur et en jeu, en créant un PNJ temporaire puis
en le supprimant. Si le refus de renommage gêne sur des PNJ existants, le
débloquer en appliquant une fois le skin voulu depuis le panel — ce qui enregistre
la source et rend tous les renommages suivants sûrs.
