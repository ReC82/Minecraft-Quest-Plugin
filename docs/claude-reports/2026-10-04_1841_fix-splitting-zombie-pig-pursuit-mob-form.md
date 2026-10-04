# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-04
* Heure : 18:41 (heure locale machine, CEST)
* Sujet : Issue #190 — équilibrage du Zombie fissile (vitesse + multiplication), poursuite du
  Cochon Creeper, confirmation des bases passives, correction du formulaire « Nouveau profil »
  du Control Panel.
* Statut : DONE (correctifs livrés et déployés ; validation en jeu de la poursuite et du parcours
  « Nouveau profil » toujours à la charge de l'utilisateur, voir TC-232)
* Branche Git : `feature/169-special-mobs-boss` (poussée, non fusionnée)
* Commit actuel : `3470436`
* Début de la tâche : 2026-10-04 ~17:30 CEST (approximatif — première action vérifiable : lecture
  de `splitting_zombie.yml` et de `SplitOnHitAbilityListener.java`)
* Fin de la tâche : 2026-10-04 18:41:45 CEST
* Durée totale : ~01:10:00 (approximatif, voir ci-dessus)

## Demande

Traiter l'issue #190 avant de reprendre #179. Quatre points, avec consigne explicite d'identifier
le mécanisme réel responsable avant de corriger (pas de supposition) et d'appliquer les correctifs
au profil déjà déployé, pas seulement au gabarit des futurs profils :

1. Le Cochon Creeper apparaît et explose bien à proximité mais ne poursuit pas le joueur — corriger
   via l'API publique Paper, sans rendre les animaux ordinaires agressifs.
2. Le Zombie fissile rencontré naturellement est beaucoup trop rapide et se multiplie
   excessivement — vitesse proche du zombie normal, 2 renforts maximum vivants par parent par
   défaut, pas de multiplication récursive.
3. Les profils spéciaux doivent pouvoir utiliser des bases passives compatibles (cochons, poules,
   grenouilles, etc.).
4. Vérifier le parcours « Nouveau profil » (annoncé disponible mais l'utilisateur ne parvient pas
   à créer de profil) — fournir les clics exacts et corriger si nécessaire.

Tests ciblés, commit/push, déploiement DEV et redémarrage autorisés sans reconfirmation. Préserver
les contenus locaux. Terminer par les clics exacts pour tester, puis passer à #179.

## Analyse

**Zombie fissile** — lecture de `SplitOnHitAbilityListener#onDamage`/`#split` : le coup déclenche
une division à chaque fois que l'entité reçoit des dégâts non mortels, tant que sa profondeur
(PDC) reste sous `max-depth`. Aucune vérification n'existait pour savoir combien d'enfants ce
parent précis avait déjà produits — seul `SpecialMobService#atPopulationLimit` (plafond **global**,
partagé entre toutes les instances du profil) limitait quoi que ce soit. Au combat normal, un
joueur porte plusieurs coups avant de tuer l'entité : chacun relançait une division complète,
produisant potentiellement bien plus que `max-children-per-hit` descendants directs pour un seul
parent. Par ailleurs, `speed: 1.0` dans le profil n'est pas un multiplicateur mais la valeur brute
de l'attribut `MOVEMENT_SPEED`, dont la base vanilla du zombie est 0.23 — `1.0` valait donc environ
4,3× la vitesse normale, exactement le « sprint démesuré » rapporté.

**Cochon Creeper** — lecture de `ExplosiveOnAttackAbilityService` : le balayage périodique ne
faisait que vérifier la distance à un joueur pour déclencher l'explosion ; aucun code ne
commandait de déplacement. Changer les statistiques d'une base passive ne lui ajoute aucun goal
d'IA de poursuite (un `Pig` vanilla n'a simplement pas de goal de combat/poursuite dans son
sélecteur). Vérifié que `org.bukkit.entity.Mob#getPathfinder()` (`com.destroystokyo.paper.entity.Pathfinder`)
est de l'API publique Paper exposée dans la version du serveur (confirmé par introspection du JAR
`paper-api`), permettant de commander un déplacement vers une cible indépendamment des goals d'IA
de l'entité — sans NMS.

**Bases passives** — lecture de `SpecialMobDefinitionParser#parseEntityType` : aucune restriction
de type n'existe, seule la vivacité (`EntityType#isAlive()`) est vérifiée. Confirmé par un nouveau
test direct créant des profils sur `PIG`/`CHICKEN`/`FROG` : tous réussissent. Le gap réel n'était
donc pas une restriction backend, mais que les capacités réellement « offensives » existantes
(`EXPLOSIVE_ON_ATTACK`/`STRONGER_EXPLOSION`) ne sont pas encore éditables depuis le formulaire du
panel (seules `ENRAGED`/`SUMMON_ON_DAMAGE` le sont) — explicitement du périmètre de l'issue #170,
pas étendu ici.

**« Nouveau profil »** — avant de supposer un bug de formulaire, lecture directe des tables
`agent_action` et `audit_log` de la base SQLite du Control Panel déployé
(`/var/lib/plugadmin/control-panel.db`, lecture seule) : **aucune action `mob.definition.create`
n'y apparaît, ni réussie ni refusée**, alors que `mob.list`/`mob.test.spawn` y figurent bien et
fonctionnent. Ceci exclut un bug de validation côté serveur (qui aurait laissé une trace « DENIED »)
et pointe vers le formulaire lui-même n'étant jamais soumis avec succès jusqu'au bout. Un nouveau
test direct de `BukkitAgentActions.mobDefinitionCreate`/`Update`/`Toggle` (jamais testé directement
jusqu'ici — seulement via le double `FakeAgentActions`) confirme que la logique serveur elle-même
est correcte. Cause la plus probable restante : le formulaire de création compte environ 25 champs
répartis sur 5 sections, avec un unique bouton d'enregistrement tout en bas — facilement manqué,
en particulier sur mobile.

## Travail effectué

- `SplitOnHitAbility` (modèle) : nouveau champ `maxAlivePerParent` (optionnel au chargement YAML,
  défaut 2 via un constructeur historique à 2 arguments — rétro-compatible).
- `SpecialMobDefinitionParser`/`SpecialMobDefinitionYaml` : parsing/rendu du nouveau champ
  `max-alive-per-parent`.
- `SpecialMobService` : nouvelle clé PDC `splitParentKey` (accesseur, même discipline que
  `splitDepthKey`).
- `SplitOnHitAbilityListener` : chaque enfant créé est tagué avec l'UUID de son parent direct ;
  avant toute division, compte les enfants déjà vivants de ce parent précis (balayage borné des
  entités proches, jamais un scan global) et refuse de dépasser `maxAlivePerParent`, quel que soit
  le nombre de coups déjà reçus.
- `src/main/resources/mobs/splitting_zombie.yml` (gabarit par défaut) : `speed: 1.0` → `0.25` ;
  `max-alive-per-parent: 2` ajouté explicitement.
- **Profil déjà déployé sur VeryGames DEV mis à jour** (`deploy-verygames.sh --also`), pas
  seulement le gabarit — backup préalable vérifié identique au gabarit par défaut (aucune
  personnalisation admin n'existait pour ce profil, donc écrasement sûr).
- `ExplosiveOnAttackAbilityService` : nouvelle poursuite — tant qu'un joueur éligible est à moins
  de 16 blocs (hors de `trigger-range-blocks`), l'entité se met en chemin vers lui via
  `Mob#getPathfinder().moveTo(player, speed)` (vitesse = son attribut `MOVEMENT_SPEED` courant) ;
  recalculé à chaque balayage (1 s). N'affecte que les entités taguées avec cette capacité.
- Control Panel `/mobs` — formulaire de profil : bouton « Créer »/« Enregistrer » dupliqué juste
  après la section Identité ; sections « Capacité : Enragé » et « Capacité : Invocation de
  renforts » reconverties en `<details>` natifs, repliés par défaut, auto-ouverts en modification
  si la capacité est déjà active sur le profil ; aide contextuelle du champ « Type d'entité »
  mise à jour (exemples de bases passives, mention honnête que les capacités offensives pour elles
  ne sont pas encore éditables ici).

## Fichiers créés

- `src/test/java/com/lodygames/rpgquest/mob/ability/ExplosiveOnAttackAbilityServiceTest.java`
- `src/test/java/com/lodygames/rpgquest/web/agent/BukkitAgentActionsMobTest.java`

## Fichiers modifiés

- `src/main/java/com/lodygames/rpgquest/mob/model/SplitOnHitAbility.java`
- `src/main/java/com/lodygames/rpgquest/mob/{SpecialMobDefinitionParser,SpecialMobDefinitionYaml,SpecialMobService}.java`
- `src/main/java/com/lodygames/rpgquest/mob/ability/{SplitOnHitAbilityListener,ExplosiveOnAttackAbilityService}.java`
- `src/main/resources/mobs/splitting_zombie.yml`
- `src/test/java/com/lodygames/rpgquest/mob/{SpecialMobDefinitionParserTest,SpecialMobDefinitionYamlTest}.java`
- `src/test/java/com/lodygames/rpgquest/mob/ability/SplitOnHitAbilityListenerTest.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/AgentPages.java`
- `SPECIAL_MOB_FORMAT.md`, `docs/RPGQUEST_BIBLE.md`, `docs/MANUAL_TEST_PLAN.md`, `.ai/ROADMAP.md`,
  `docs/deployment/SERVER_CHANGELOG.md`

Contenus locaux utilisateur : non touchés (travail réalisé exclusivement dans le worktree isolé
`/srv/rpgquest/worktree-169`, dépôt principal inchangé).

## Base de données / migrations

Aucune.

## Configuration / données

`mobs/splitting_zombie.yml` modifié sur le serveur déployé (voir « Déploiement VeryGames »).

## Tests automatiques

Suite complète des 3 modules : **1841 tests, 1806 exécutés verts, 35 ignorés, 0 échec, 0 erreur**
(vérifié via les XML de résultat). `./gradlew build` (interne au script de déploiement officiel)
également vert.

Nouveaux tests significatifs :
- `SpecialMobDefinitionParserTest`/`SpecialMobDefinitionYamlTest` (+2) : `max-alive-per-parent`
  explicite et par défaut, round-trip complet.
- `SplitOnHitAbilityListenerTest` : réécrit pour taguer l'identité directement en PDC plutôt que
  via `SpecialMobService#apply` (bloqué par une limitation MockBukkit déjà documentée dans ce
  projet) — 2 tests sur 6 s'exécutent désormais réellement au lieu d'être ignorés
  (`lethalHitNeverSplits`, `cancelledDamageEventNeverSplits`) ; **nouveau test
  `repeatedNonLethalHitsOnTheSameParentNeverExceedMaxAlivePerParent`** reproduisant exactement le
  bug rapporté (4 coups non mortels répétés sur le même parent) et prouvant le plafond à 2.
- `ExplosiveOnAttackAbilityServiceTest` (nouveau, premiers tests de cette classe) : identité posée
  via `onChunkLoad` (pas `apply()`, pour éviter la même limitation) ; le déclenchement de
  l'explosion et l'absence de déclenchement hors de portée de perception s'exécutent réellement
  (2/3) ; le test exerçant directement la poursuite échoue sur
  `org.mockbukkit.mockbukkit.entity.MobMock.getPathfinder` — non implémenté par MockBukkit,
  confirmé en écrivant le test, documenté honnêtement plutôt que masqué.
- `BukkitAgentActionsMobTest` (nouveau, premiers tests directs de `BukkitAgentActions` — jusqu'ici
  seul son double `FakeAgentActions` était exercé) : création minimale, création sur bases
  PIG/CHICKEN/FROG, modification puis bascule activer/désactiver, refus de créer un id déjà
  existant — tous vérifiés contre la vraie implémentation.

**Vérification du bug du Zombie fissile avant correction** : reproduit concrètement en lisant le
code (pas seulement supposé), confirmé par le nouveau test qui échoue sans le correctif et réussit
avec.

## Tests manuels à effectuer

Voir `docs/MANUAL_TEST_PLAN.md` TC-232 (nouveau, `PENDING MANUAL VALIDATION`) : vitesse/plafond du
Zombie fissile en situation réelle, poursuite du Cochon Creeper (aucune couverture automatisée
possible pour ce point précis — limitation MockBukkit sur `Mob#getPathfinder()`), confirmation
qu'un animal ordinaire ne poursuit jamais, et parcours complet de création d'un nouveau profil
depuis `/mobs`.

## Résultat attendu

Un Zombie fissible rencontré naturellement a une vitesse proche de la normale et ne produit jamais
plus de 2 enfants vivants directs par parent, quel que soit le nombre de coups reçus. Un Cochon
Creeper (ou tout profil offensif à base passive) poursuit un joueur à portée. Créer un nouveau
profil depuis `/mobs` fonctionne en cliquant le bouton d'enregistrement situé juste après la
section Identité, sans avoir besoin de faire défiler tout le formulaire.

## Reset / retour à l'état initial

Aucune donnée joueur touchée. Le fichier `mobs/splitting_zombie.yml` modifié sur le serveur peut
être restauré depuis son backup (voir « Rollback »).

## Déploiement VeryGames

### À transférer
JAR (1 660 945 o, SHA-256 `0a56cbcf75d31417b9904aaf348efb0bd14138e2ef47a9ba1fdbb9fc4c75140f`) et
`mobs/splitting_zombie.yml` (1065 o, SHA-256
`79c34fcefe18f7c37607c1f4c44b96ed7c3190c6f4603d6ac0b0369effbefc63`) — tous deux déjà transférés.

### Ne PAS transférer/altérer
`data.db`, `config.yml`, `messages.yml`, `spawn.yml`, mondes, autres plugins, autres fichiers
`mobs/*.yml`.

### Redémarrage requis
Oui — effectué (`scripts/verygames-restart.sh --timeout 240`), OFFLINE puis ONLINE confirmés.

### Migration automatique
Aucune.

## Rollback

Plugin : `scripts/rollback-verygames.sh --latest` (restaure
`rpgquest-20261004T163803Z-predeploy.jar`). Fichier de contenu seul : voir le `MANIFEST.txt` dans
`/home/ubuntu/.local/share/rpgquest/verygames-backups/extra-20261004T163803Z/`. Control Panel :
`scripts/plugadmin/rollback.sh app` (restaure `/opt/plugadmin/releases/20261004-184100`).

## Logs / diagnostic

`/plugins` → 4 plugins verts après redémarrage. Control Panel : incident transitoire observé — le
contrôle `/health` du script `deploy.sh` a échoué une fois immédiatement après `systemctl restart`
(`curl: Couldn't connect`), la JVM n'ayant pas encore fini de se lier au port 8090 (course connue
de l'outil, pas un défaut du code livré). Confirmé résolu en moins de 30 secondes via
`journalctl -u plugadmin` (`event=panel_started port=8090` puis `path=/health status=200`) et une
vérification `curl` manuelle immédiate. Aucun rollback nécessaire. Amélioration suggérée pour plus
tard (hors périmètre de cette tâche) : ajouter une attente/retry dans `deploy.sh` avant son propre
contrôle de santé.

## Documentation mise à jour

`SPECIAL_MOB_FORMAT.md`, `docs/RPGQUEST_BIBLE.md`, `docs/MANUAL_TEST_PLAN.md` (TC-232),
`docs/deployment/SERVER_CHANGELOG.md`, `.ai/ROADMAP.md`.

## Limitations / travail restant

- Poursuite du Cochon Creeper : sans couverture automatisée exécutable (MockBukkit ne simule pas
  `Mob#getPathfinder()`) — entièrement `PENDING MANUAL VALIDATION`.
- Capacités offensives (`EXPLOSIVE_ON_ATTACK`/`STRONGER_EXPLOSION`) toujours non éditables depuis
  le formulaire du panel — scope de l'issue #170, explicitement non traité ici.
- Incident transitoire du script `plugadmin/deploy.sh` (course santé/démarrage) : identifié,
  auto-résolu, non corrigé dans l'outil lui-même (amélioration future suggérée, hors périmètre).
- Le parcours complet de création depuis le navigateur reste à confirmer par l'utilisateur ; la
  correction est basée sur l'évidence du journal d'actions réel + un test direct de la logique
  serveur, pas sur une reproduction interactive du clic (Claude n'a pas d'accès navigateur).

## Prochaine étape suggérée

Validation manuelle de TC-232 par l'utilisateur. Une fois confirmée, reprendre #179 (parcours du
Garde), comme demandé.
