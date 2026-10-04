# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-04
* Heure : 16:35 (heure locale machine, CEST)
* Sujet : EPIC #169 (mobs spéciaux et boss configurables dans PlugAdmin) — découpage en tickets
  enfants puis livraison complète du premier lot (socle profils + éditeur panel, capacités Enragé
  et Invocation de renforts, tirage aléatoire Wild à deux étages).
* Statut : DONE (lot 1 complet, déployé). Le reste de l'EPIC (#170/#171/#175/#176/#177) n'est pas
  traité — voir « Limitations / travail restant ».
* Branche Git : `feature/169-special-mobs-boss` (poussée, 5 commits, non fusionnée)
* Commit actuel : `2c171b2` (+ l'entrée `SERVER_CHANGELOG.md` de ce rapport, committée séparément)
* Début de la tâche : non mesurable précisément (la demande est arrivée avant une compaction du
  contexte de conversation ; la première action vérifiable de cette tâche — création du worktree
  isolé `/srv/rpgquest/worktree-169` — date de 16:07:45 CEST)
* Fin de la tâche : 2026-10-04 16:35:13 CEST
* Durée totale : non mesurable avec certitude pour la partie avant compaction ; ≥ 27 minutes pour
  la partie vérifiable (16:07:45 → 16:35:13)

## Demande

Message explicite de l'utilisateur : démarrer l'EPIC #169 dans `/srv/rpgquest/repo`, branche
`feat/control-panel-admin-tools`. Mise en pause explicite de la recherche de waypoints (#150) — ne
plus y toucher, ne jamais la marquer corrigée. Objectif : livrer rapidement un premier système de
mobs spéciaux et de boss **créable et testable depuis PlugAdmin**, sans passer la session à
concevoir/documenter uniquement. Découper #169 en tickets enfants avant de réaliser le premier
lot. Construire/tester depuis un worktree isolé pour ne jamais contaminer les brouillons locaux du
panel (`crystal_hunt.yml` en cours d'édition, `jeff_skeleton.yml`/`lily_pumpkin.yml`/
`st0_meet_people.yml`/`lily_memories.yml`) — ne plus les stasher par défaut. Détail exact du
premier lot demandé : profils catégorisés SPECIAL/BOSS avec éditeur graphique complet (créer/
modifier/activer-désactiver, spawn de test près d'un joueur choisi dans le Wild), statistiques
étendues, visuels boss (nom/particules colorés, barre de vie), tirage aléatoire Wild avec
pourcentage configurable et sémantique non ambiguë (jamais un tirage cumulé mal défini, jamais un
second moteur de spawn concurrent de #168), capacités Enragé et Invocation de renforts avec leurs
garde-fous (pas de cascade, plafonds). Ticket boss de quête à isoler en enfant séparé, jamais
présenté comme disponible. Livraison (tests, build, commits, push, déploiement DEV+AWS,
redémarrages, vérifications) autorisée sans reconfirmation.

## Analyse

Audit préalable du package `com.lodygames.rpgquest.mob` existant : le socle (identité PDC non
cumulative, filtrage monde/biome/zone, plafond de population, trois capacités déjà implémentées —
`STRONGER_EXPLOSION`/`EXPLOSIVE_ON_ATTACK`/`SPLIT_ON_HIT`) couvrait déjà une bonne partie des
exigences d'architecture ; le tirage aléatoire existant (`rollDefinition`) faisait exactement le
tirage « cumulé ambigu » signalé — premier-trouvé parmi des tirages indépendants par définition,
sans distinction SPECIAL/BOSS. Décision : étendre ce socle plutôt que le dupliquer, mirorer le
motif CRUD déjà utilisé pour les PNJ (`NpcDefinitionStore`/`NpcDefinitionYaml`/
`BukkitAgentActions.npcDefinitionCreate/Update`) pour les mobs, et mirorer le motif de capacité
« sweep périodique borné à la population suivie » déjà utilisé par
`ExplosiveOnAttackAbilityService` pour la nouvelle capacité Enragé.

Lecture complète du corps de l'issue #169 sur GitHub (beaucoup plus détaillé que le résumé reçu en
conversation : modes de boss « quête »/« communautaire », tables de récompenses aléatoires
partagées, conditions d'éligibilité aux récompenses, annonces aux joueurs). Découpage en 7 tickets
enfants réellement créés sur GitHub avant de commencer à coder (voir « Fichiers créés / tickets
GitHub » ci-dessous), avec une attention explicite à ne pas élargir le lot 1 au-delà de ce que
l'EPIC demandait lui-même de différer.

## Travail effectué

**Modèle et moteur (`com.lodygames.rpgquest.mob`)**
- `SpecialMobDefinition` étendu : `category` (`MobCategory` SPECIAL/BOSS, optionnelle au
  chargement YAML — défaut `SPECIAL`, rétro-compatible avec les 4 profils d'exemple existants),
  `enabled` (optionnel, défaut `true`), `knockbackResistance`, `scale`, `creeperExplosionRadius`
  (rejeté au chargement si `entity-type` n'est pas `CREEPER` — jamais une option silencieusement
  ignorée).
- Deux nouvelles capacités (sealed interface `MobAbility` étendue) :
  - `EnragedAbility` (`health-fraction`, `speed-multiplier`, `damage-multiplier`) : nouvelle
    `EnragedAbilityService` (balaye périodiquement `SpecialMobService#aliveEntityIds`, même motif
    que `ExplosiveOnAttackAbilityService`), applique un signal visuel (particule
    `ANGRY_VILLAGER` + son `ENTITY_RAVAGER_ROAR`) puis multiplie les attributs vitesse/dégâts
    **une seule fois** (marqueur PDC dédié, jamais réévalué ensuite).
  - `SummonOnDamageAbility` (`summon-entity-type`, `amount`, `chance`, `cooldown-seconds`,
    `max-alive`) : nouvelle `SummonOnDamageAbilityListener` (événement `EntityDamageEvent`,
    dégâts finaux > 0 uniquement, cooldown par entité, plafond de renforts vivants compté via une
    PDC dédiée sur chaque renfort). Aucune cascade possible par construction : les renforts
    invoqués sont de simples entités vanilla, jamais upgradées via `SpecialMobService#apply`, donc
    jamais reconnues comme mob spécial par cette même capacité.
- `SpecialMobService` :
  - applique les nouveaux attributs (`Attribute.KNOCKBACK_RESISTANCE`/`SCALE`) et le rayon
    d'explosion creeper (`Creeper#setExplosionRadius`) ;
  - visuels BOSS : `BossBar` Adventure par entité (nom = `displayName`, couleur rouge, overlay
    segmenté), mise à jour périodique (1 s) de la progression selon la vie réelle, affichage/
    masquage par joueur selon la distance (48 blocs), aura de particules `SOUL_FIRE_FLAME`
    périodique, nettoyage complet à la mort/arrêt du plugin/redécouverte de chunk ;
  - `rollDefinition()` entièrement redessiné en deux étages (voir ci-dessous) ;
  - nouvelles méthodes `findTestSpawnLocation`/`applyTestInstance`/`isTestInstance`/
    `clearTestInstances` pour le spawn/nettoyage de test depuis le panel (PDC dédiée, jamais un
    mob ordinaire supprimé).
- **Tirage aléatoire Wild (nouvelle sémantique à deux étages)**, résolvant l'ambiguïté du
  pourcentage cumulé signalée explicitement par l'utilisateur :
  1. `MobSpawnSettings`/`MobSpawnSettingsStore` (nouveau fichier `mobs/spawn-settings.yml`, édité
     uniquement depuis le panel) : throttle global (`chance`, 0 à 1), évalué **une seule fois**
     par spawn naturel éligible, **avant** d'examiner les profils individuels.
  2. Seulement si ce tirage réussit, chaque profil `SPECIAL` activé et compatible (type/monde/
     biome/zone, sous son propre `max-population`) tire indépendamment sa propre `spawn-chance`
     (champ inchangé).
  3. Zéro résultat → rien ; un résultat → appliqué ; **plusieurs résultats simultanés → tirage
     pondéré explicite entre eux** (poids = `spawn-chance` de chacun, implémenté par
     `weightedPick`) — jamais le premier trouvé dans le registre.
  4. `maxSimultaneousSpecial` (optionnel) plafonne le nombre total de mobs `SPECIAL` vivants, toutes
     définitions confondues, en plus du `max-population` par profil déjà existant.
  5. Catégorie `BOSS` **exclue par construction** de ce tirage (filtrée avant même le premier
     tirage par définition).
  6. Aucun nouveau tirage au chargement de chunk ni à la reconnexion (comportement déjà existant,
     inchangé — `onChunkLoad` ne fait que redécouvrir, jamais réappliquer).
  7. Pas de second moteur de spawn créé : la coordination avec #168 (hostiles jour/nuit) est
     explicitement différée à l'ouverture de ce ticket, documentée dans le ticket enfant #174.
- `SpecialMobDefinitionStore`/`SpecialMobDefinitionYaml` (nouveaux, miroir exact de
  `NpcDefinitionStore`/`NpcDefinitionYaml`) : écriture atomique (fichier temporaire + move),
  vérification par relecture avant de déclarer un succès, jamais de YAML brut envoyé par
  l'appelant.
- Fichiers d'exemple bundlés (`red_creeper.yml`, `golden_creeper.yml`, `creeper_pig.yml`,
  `splitting_zombie.yml`) mis à jour avec `category: SPECIAL` explicite (déjà le défaut, ajouté
  pour servir d'exemple clair aux administrateurs).

**Agent PlugAdmin (`com.lodygames.rpgquest.web.agent`)**

Sept nouvelles actions whitelistées de bout en bout (`AgentActionType` → validation dans
`AgentActionExecutor` → délégation dans `BukkitAgentActions` vers les services ci-dessus — jamais
une commande texte ni du SQL) :

| Action | Rôle |
|---|---|
| `mob.list` | Catalogue des profils + réglages du throttle + éventuelles erreurs de chargement |
| `mob.definition.create` / `.update` | Écrit un profil (y compris les deux capacités premier lot, via des champs à plat, présentes seulement si toutes leurs valeurs sont fournies) |
| `mob.definition.toggle` | Bascule `enabled` sans toucher au reste |
| `mob.spawn-settings.set` | Écrit `mobs/spawn-settings.yml` |
| `mob.test.spawn` | Résout le joueur choisi (doit être **dans le monde Wild configuré**, sinon échec lisible), cherche une position sûre à proximité (`RandomSafeLocationFinder`), applique le profil et tague l'instance comme test |
| `mob.test.clear` | Supprime uniquement les instances taguées test |

`BukkitAgentActions` reçoit en plus `SpecialMobRegistry`/`SpecialMobService`/
`SpecialMobDefinitionStore`/`MobSpawnSettingsStore`/un `Supplier<String>` résolvant le monde Wild
configuré (`configService.current().travel().wildWorld()`).

**Control Panel (`control-panel`)**

- Nouvelle page `/mobs` (`AgentPages#mobs`) : catalogue en accordéon (catégorie, actif/inactif,
  population vivante, capacités résumées en clair), formulaire de création/modification complet
  (identité, tirage Wild, statistiques étendues, les deux capacités premier lot chacune derrière
  sa propre case « activer »), formulaire de réglage du throttle Wild, bascule activer/désactiver
  en un clic, spawn de test près d'un joueur connecté + nettoyage des instances de test — **aucune
  YAML ni commande requise de l'opérateur**, y compris sur mobile (mêmes classes CSS Bootstrap que
  `/npcs`/`/travel`).
- Nouvelles permissions `MOB_READ`/`MOB_WRITE`/`MOB_TEST_SPAWN` : `ADMIN` a les trois,
  `CONTENT_EDITOR` lecture+écriture (pas de spawn, même logique que `NPC_WRITE` sans
  `NPC_SPAWN_WRITE`), `TESTER` lecture+spawn de test (pas d'édition de contenu), `BUILDER`/
  `READ_ONLY` lecture seule.
- `AgentActionCatalog` : whitelist + validation des 7 types côté panel (défense en profondeur,
  re-validés ensuite côté plugin) — pourcentages/plages bornés, les deux capacités premier lot
  acceptées en bloc complet ou pas du tout (jamais une capacité à moitié paramétrée).

**Tickets enfants GitHub créés** (découpage demandé avant l'implémentation) :
- #170 — Capacités avancées de mobs spéciaux/boss (lot 2+, non commencé).
- #171 — Boss de quête : spawn à l'acceptation, identité et localisation (non commencé, jamais
  présenté comme disponible).
- #172 — Socle : profils + éditeur Control Panel (lot 1) — **livré dans cette session**.
- #173 — Premier lot de capacités : Enragé et Invocation de renforts — **livré dans cette
  session**.
- #174 — Apparitions aléatoires dans le Wild : throttle global + tirage pondéré — **livré dans
  cette session**.
- #175 — Boss communautaire : scheduler, plafond, annonces (non commencé).
- #176 — Récompenses et participation aux boss (non commencé).
- #177 — Tables de récompenses aléatoires partagées quêtes/boss (non commencé).

Un commentaire de synthèse listant ces 7 tickets devrait être posté sur #169 pour que le suivi
reste lisible (non fait automatiquement dans cette session — voir « Prochaine étape suggérée »).

## Fichiers créés

- `src/main/java/com/lodygames/rpgquest/mob/model/MobCategory.java`
- `src/main/java/com/lodygames/rpgquest/mob/model/EnragedAbility.java`
- `src/main/java/com/lodygames/rpgquest/mob/model/SummonOnDamageAbility.java`
- `src/main/java/com/lodygames/rpgquest/mob/MobSpawnSettings.java`
- `src/main/java/com/lodygames/rpgquest/mob/MobSpawnSettingsStore.java`
- `src/main/java/com/lodygames/rpgquest/mob/SpecialMobDefinitionStore.java`
- `src/main/java/com/lodygames/rpgquest/mob/SpecialMobDefinitionYaml.java`
- `src/main/java/com/lodygames/rpgquest/mob/ability/EnragedAbilityService.java`
- `src/main/java/com/lodygames/rpgquest/mob/ability/SummonOnDamageAbilityListener.java`
- `src/test/java/com/lodygames/rpgquest/mob/SpecialMobDefinitionStoreTest.java`
- `src/test/java/com/lodygames/rpgquest/mob/SpecialMobDefinitionYamlTest.java`

## Fichiers modifiés

- `src/main/java/com/lodygames/rpgquest/mob/model/{MobAbility,MobAbilityType,SpecialMobDefinition}.java`
- `src/main/java/com/lodygames/rpgquest/mob/{SpecialMobDefinitionParser,SpecialMobService}.java`
- `src/main/resources/mobs/{red_creeper,golden_creeper,creeper_pig,splitting_zombie}.yml`
- `src/main/java/com/lodygames/rpgquest/web/agent/{AgentActions,AgentActionType,AgentActionExecutor,BukkitAgentActions}.java`
- `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java`
- `src/test/java/com/lodygames/rpgquest/mob/SpecialMobServiceTest.java`
- `src/test/java/com/lodygames/rpgquest/mob/ability/SplitOnHitAbilityListenerTest.java`
- `src/test/java/com/lodygames/rpgquest/progression/listener/CombatXpListenerTest.java`
- `src/test/java/com/lodygames/rpgquest/web/agent/{StubAgentActions,AgentActionExecutorTest}.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/agent/AgentActionCatalog.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/authz/{Permission,Role}.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/{AgentPages,Icons,Layout,PanelApp}.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/agent/AgentActionCatalogTest.java`
- `docs/RPGQUEST_BIBLE.md`, `SPECIAL_MOB_FORMAT.md`, `docs/current_state.md`,
  `docs/MANUAL_TEST_PLAN.md`, `.ai/ROADMAP.md`, `docs/deployment/SERVER_CHANGELOG.md`

Fichiers locaux utilisateur (`crystal_hunt.yml`, Lily/Jeff) : **jamais touchés** — tout le travail
a eu lieu dans un worktree Git isolé (`/srv/rpgquest/worktree-169`), vérifié à nouveau après coup
(`git status`/SHA-256 sur le dépôt principal inchangés).

## Base de données / migrations

Aucune. Les nouveaux fichiers (`mobs/<id>.yml`, `mobs/spawn-settings.yml`) sont de simples YAML,
hors base de données, comme les profils de mobs spéciaux existants.

## Configuration / données

- Nouveau fichier optionnel `plugins/RPGQuest/mobs/spawn-settings.yml`, généré par défaut
  (`enabled: true`, `chance: 1.0`, pas de plafond) si absent — comportement rétro-compatible
  identique à avant ce lot tant qu'il n'est pas modifié depuis le panel.
- `category`/`enabled` optionnels dans les fichiers `mobs/<id>.yml` existants — aucune migration de
  fichier nécessaire, les 4 profils d'exemple ont simplement reçu `category: SPECIAL` explicite.

## Tests automatiques

Suite complète des 3 modules, `RPGQUEST_TEST_MAX_HEAP=768m` (obligatoire sur cette box) :
- **Plugin** : 1405 tests, 34 ignorés (limitation MockBukkit `setRemoveWhenFarAway` non
  implémenté, préexistante — concerne aussi les 3 capacités précédentes), **0 échec**.
- **Control Panel** : 394 tests, 1 ignoré, **0 échec**.
- **web-api** : 30 tests, 0 ignoré, **0 échec** (module non touché par ce lot, suite relancée par
  precaution via `./gradlew build` à la racine qui couvre les 3 modules).
- **Total** : 1829 tests, 1794 exécutés verts, 35 ignorés, **0 échec, 0 erreur**.

Nouveaux tests significatifs :
- `SpecialMobServiceTest` (+6) : exclusion BOSS du tirage automatique, throttle global bloquant
  avant examen des profils, tirage pondéré explicite entre deux profils à chance égale (prouvé par
  un générateur aléatoire en séquence contrôlée, pas seulement « ça retourne quelque chose »),
  profil désactivé jamais tiré.
- `SpecialMobDefinitionYamlTest` (+4) : round-trip complet (rendu → re-parsing → égalité), y
  compris les deux nouvelles capacités, le rayon d'explosion creeper, et un profil désactivé.
- `SpecialMobDefinitionStoreTest` (+4) : création/refus d'écrasement/modification/liste avec
  erreurs, écriture atomique vérifiée par relecture.
- `AgentActionCatalogTest` (+5, 2 listes existantes étendues) : whitelisting des 7 types,
  permissions, validation complète/partielle des deux capacités premier lot, confirmation exigée
  pour les actions de test (spawn/clear) mais pas pour l'édition de profil.
- `RolePermissionMatrixTest` : revérifié vert, aucune régression sur les permissions existantes.

`./gradlew build` (3 modules) également vert (JAR produit : `build/libs/rpgquest-0.1.0-SNAPSHOT.jar`,
1 659 532 o).

**Vérification des codes de sortie réels** : chaque commande longue a été exécutée en arrière-plan
avec sa sortie redirigée vers un fichier, puis le code de sortie réel lu directement (jamais via un
pipe vers `tail`/`grep` qui masquerait le code réel) — confirmé `EXIT:0` systématiquement avant de
considérer une étape terminée.

## Tests manuels à effectuer

Voir `docs/MANUAL_TEST_PLAN.md`, **TC-231** (nouveau, `PENDING MANUAL VALIDATION`), qui couvre dans
l'ordre : création d'un profil SPECIAL et d'un profil BOSS sans YAML/commande ; spawn de test d'un
boss près d'un joueur dans le Wild et vérification visuelle (nom coloré, barre de vie qui diminue,
aura de particules) ; non-apparition du BOSS via le tirage naturel même en multipliant les spawns
éligibles ; activation d'Enragé et vérification du signal + de l'augmentation unique de vitesse/
dégâts ; activation d'Invocation de renforts et vérification du plafond/cooldown/absence de
cascade ; réglage du throttle Wild à 0 puis vérification de l'arrêt des transformations ;
nettoyage des instances de test sans toucher aux mobs ordinaires ; bascule activer/désactiver ;
persistance après redémarrage.

**Limitation MockBukkit documentée** : `LivingEntityMock#setRemoveWhenFarAway` n'est pas implémenté
par la version de MockBukkit utilisée ici — tout chemin de test automatisé passant par
`SpecialMobService#apply` (donc les visuels boss, les deux nouvelles capacités en situation réelle,
les nouveaux attributs KNOCKBACK_RESISTANCE/SCALE/rayon d'explosion creeper) est **sans couverture
automatisée exécutable** dans cet environnement — JUnit le signale comme `skipped`, jamais comme un
échec. C'est une limitation préexistante (les 3 capacités précédentes ont la même limite, ou
`StrongerExplosionAbilityListener`/`ExplosiveOnAttackAbilityService` n'ont simplement aucun test
dédié). Les points du scénario ci-dessus touchant directement l'apparence en jeu restent donc
réellement `PENDING MANUAL VALIDATION`, pas seulement par prudence rhétorique.

## Résultat attendu

Un administrateur peut, depuis le Control Panel (`/mobs`), sans écrire de YAML ni de commande :
créer un profil SPECIAL ou BOSS avec des statistiques étendues ; activer les capacités Enragé et/ou
Invocation de renforts avec leurs paramètres ; régler le pourcentage global de tirage aléatoire
Wild et un plafond simultané ; faire apparaître une instance de test près d'un joueur connecté dans
le Wild pour vérifier visuellement le résultat ; nettoyer ces instances de test ; activer/désactiver
un profil sans le supprimer. Un BOSS n'apparaît jamais via le tirage aléatoire automatique. Le boss
de quête n'est pas disponible et n'est présenté nulle part comme tel.

## Reset / retour à l'état initial

Aucune donnée joueur touchée. Pour revenir en arrière côté contenu : supprimer les fichiers
`mobs/<id>.yml` créés pendant les tests et/ou `mobs/spawn-settings.yml` (son absence restaure le
comportement par défaut rétro-compatible). Pour revenir en arrière côté code : voir « Rollback ».

## Déploiement VeryGames

### À transférer
JAR `build/libs/rpgquest-0.1.0-SNAPSHOT.jar` (1 659 532 o, SHA-256
`d43e6b2d350d23dbf24feb4533a12d6b73b6ac0b3e8655432b04d2fa63c449f3`) — déjà transféré, voir
`SERVER_CHANGELOG.md`.

### Ne PAS transférer/altérer
`data.db`, `config.yml`, `messages.yml`, `spawn.yml`, mondes, autres plugins — aucun de ces
éléments n'est concerné par ce lot ; le script de déploiement officiel les protège structurellement
(liste blanche stricte).

### Redémarrage requis
Oui — effectué (`scripts/verygames-restart.sh --timeout 240`), confirmé OFFLINE puis ONLINE.

### Migration automatique
Aucune (pas de changement de schéma SQLite dans ce lot).

## Rollback

`scripts/rollback-verygames.sh --latest` (restaure `rpgquest-20261004T143202Z-predeploy.jar`) pour
le plugin ; `scripts/plugadmin/rollback.sh app` (restaure `/opt/plugadmin/releases/20261004-163442`)
pour le Control Panel. Pas de rollback de code Git effectué ni nécessaire — build/tests/déploiement
tous verts.

## Logs / diagnostic

- `/rpgquest version` → `v0.1.0-SNAPSHOT` ; `/plugins` → 4 plugins verts ; `/mv list` → `claims`/
  `world`/`world_hub` chargés (`NORMAL`).
- Control Panel : `GET /health` → `{"panel":"ONLINE","disabled":false,...}` ; `GET /mobs` (non
  authentifié) → `303` (redirection login, jamais une erreur 500).

## Documentation mise à jour

`docs/RPGQUEST_BIBLE.md` (section 11), `SPECIAL_MOB_FORMAT.md`, `docs/current_state.md`,
`docs/MANUAL_TEST_PLAN.md` (TC-231 + table de recette), `.ai/ROADMAP.md` (journal de session),
`docs/deployment/SERVER_CHANGELOG.md`. Pas de nouvelle page `docs-site/` dédiée dans ce lot (même
précédent que `/travel`, qui n'en a pas non plus) — à revisiter si demandé explicitement.

## Limitations / travail restant

- Reste de l'EPIC #169 non traité (tickets #170/#171/#175/#176/#177) : capacités avancées, boss de
  quête, boss communautaire, récompenses/participation, tables de récompenses aléatoires — chacun
  nécessite des décisions de conception supplémentaires explicitement signalées dans l'EPIC
  lui-même (portée spatiale « dans le biome », règle « encore vivant », mode de tirage exclusif/
  indépendant) avant implémentation.
- Aperçu dynamique des réglages du formulaire selon le type de créature choisi : non implémenté
  (formulaire actuellement statique avec aide contextuelle par champ) — scope explicitement différé
  pour tenir le lot 1 dans un délai raisonnable.
- Duplication de profil (bouton dédié) : non implémentée — la modification/création couvre le
  besoin minimal du lot 1.
- Garde-fou dur supplémentaire contre le monde Wild directement dans `rollDefinition` (en plus du
  filtrage par profil `worlds`/zones déjà existant) : non ajouté dans ce lot, jugé non nécessaire
  tant qu'un profil n'autorise pas explicitement le Hub/Claims dans sa liste de mondes.
- Limitation MockBukkit documentée ci-dessus (`setRemoveWhenFarAway`) : aucune automatisation de
  test possible pour les chemins passant par `SpecialMobService#apply` dans cet environnement.
- Commentaire de synthèse listant les tickets enfants non posté sur l'issue #169 elle-même (fait
  dans ce rapport et dans `.ai/ROADMAP.md` à la place).

## Prochaine étape suggérée

Validation manuelle en jeu de TC-231 (voir ci-dessus). Poster un commentaire sur #169 résumant le
découpage en tickets enfants et l'état du lot 1. Choisir, avec l'utilisateur, le prochain ticket
enfant à traiter (probablement #170 « capacités avancées » ou #171 « boss de quête », selon la
priorité produit).
