# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-05
* Heure : 23:14
* Sujet : Issue #27 — permissions granulaires par rôle, monde et action
* Statut : DONE
* Branche Git : feature/27-granular-permissions
* Commit actuel si disponible : voir `git log -1` sur `feature/27-granular-permissions` (commit unique de la branche)
* Début de la tâche : 2026-09-05 22:42:15 (heure locale réelle)
* Fin de la tâche : 2026-09-05 23:19:00 (heure locale réelle)
* Durée totale : 00:36:45

## Demande

Prendre l'issue GitHub #27, la lire entièrement, respecter `CLAUDE.md`, travailler de manière
autonome :

- auditer d'abord toutes les permissions existantes, les protections de monde, les bypass admin,
  les commandes `/rpgadmin` et l'intégration Citizens ;
- implémenter une granularité de permissions **par action** et **par monde/Hub**, sans obliger
  l'usage d'`op` pour les rôles courants (builder, NPC editor, quest editor) ;
- vérifier qu'un builder hub-0 peut construire **uniquement** dans le Hub 0 et ne peut **en aucun
  cas** contourner les protections de `world_claim` ;
- prévoir la compatibilité LuckPerms sans la rendre obligatoire ;
- ajouter les tests, mettre à jour la documentation, créer le rapport Claude ;
- pousser uniquement la branche dédiée si `./gradlew test` et `./gradlew build` sont verts ;
- ne rien fusionner, ne rien déployer sur VeryGames.

## Analyse

### Audit de l'existant (avant intervention)

**Permissions déclarées (`plugin.yml`)** : `rpgquest.admin` (op), `rpgquest.admin.world` (op),
plus des nœuds joueur `default: true` (`rpgquest.quest`, `rpgquest.item`, `rpgquest.money`,
`rpgquest.market`, `rpgquest.claim`, `rpgquest.progression`, `rpgquest.backpack`,
`rpgquest.backpack.free`). **Aucune** permission de build, **aucune** granularité `/rpgadmin`.

**Protections de monde / zone — toutes bypassées par la MÊME permission `rpgquest.admin.world`** :

| Listener | Rôle | Bypass (avant) |
|---|---|---|
| `hub.HubWorldProtectionListener` | casse/pose interdites dans le monde Hub | `rpgquest.admin.world` |
| `zone.ZoneProtectionListener` | flags d'une zone cuboïde (village, etc.) | `rpgquest.admin.world` |
| `claim.ClaimProtectionListener` | protection d'un claim joueur | `rpgquest.admin.world` |
| `claim.ClaimsWorldRulesListener` | pas de build hors claim dans le monde `claims` | `rpgquest.admin.world` |

**`/rpgadmin` (`admin.RpgAdminCommand`)** : une seule vérification `rpgquest.admin.world` en tête
de `onCommand`, pour **12 sous-systèmes** (`flatten`, `zone`, `portal`, `mob`, `npc`, `spawn`,
`world`, `worldportal`, `story`, `waystone`, `player`, `guide`).

**`/claim admin`** : `rpgquest.admin.world`.

**Intégration Citizens (`npc.NpcIdentityService` / `npc.CitizensNpcBridge`)** : RPGQuest **ne
wrappe aucune commande Citizens**. La création/déplacement d'un PNJ passe par les commandes **de
Citizens** (`/npc …`, permissions `citizens.npc.*`). RPGQuest n'ajoute qu'une couche d'identité
logique via `/rpgadmin npc tag|untag|info` (donc gouvernée par `rpgquest.admin.world`) et l'écoute
d'interactions/dégâts. Citizens reste `softdepend`, jamais requis.

**Conclusion de l'audit** : pour laisser un contributeur construire dans un Hub, il fallait lui
donner `rpgquest.admin.world` (ou `op`), ce qui lui donnait **aussi** : le contournement des
claims joueurs, toutes les commandes `/rpgadmin`, l'administration des claims. C'est exactement le
problème décrit par l'issue.

### Multi-Hub

L'état actuel n'a qu'**un** monde Hub (`hub.world` = `world_hub`). L'issue demande de prévoir des
Hubs supplémentaires « sans modifier le code pour chaque ID ». Choix : un mapping **nom de monde →
zone de build** dans `config.yml` (`permissions.build-areas`), avec des valeurs par défaut
déduites des noms de mondes déjà connus (`hub.world` → `hub.0`, `travel.wild-world` → `wild`,
`claims.world` → `world.claims`). Ajouter un Hub = une ligne de config + un nœud côté gestionnaire
de permissions.

## Travail effectué

### 1. Nouveau paquet `com.lodygames.rpgquest.permission`

- **`RpgQuestPermissions`** — source unique des littéraux de nœuds (miroir typé de `plugin.yml`).
- **`BuildArea`** (record `kind` + `id`) — classe d'un monde : `HUB` / `WILD` / `WORLD` /
  `UNMANAGED`.
- **`BuildPermissionService`** — décide `mayBuild(player, world)` :
  1. `rpgquest.admin.world` ou `rpgquest.build.*` → autorisé partout ;
  2. sinon selon la `BuildArea` du monde :
     `HUB` → `rpgquest.build.hub.*` / `rpgquest.build.hub.<id>` ;
     `WILD` → `rpgquest.build.wild` ;
     `WORLD` → `rpgquest.build.world.<id>` ;
     `UNMANAGED` → aucune permission de build RPGQuest ne s'applique.
  `areaFor(worldName)` : entrée explicite de `permissions.build-areas` en priorité, sinon repli
  sur `hub.world` / `travel.wild-world` / `claims.world`. Dépendances = `Supplier` de config
  (reflète un `/rpgquest reload` sans être recréé).

### 2. `config.PermissionsConfig` + validation

- Record `PermissionsConfig(Map<String,String> buildAreas)`, ajouté à `PluginConfig`.
- `ConfigValidator.validatePermissions` : section entièrement optionnelle ; chaque valeur doit
  être `wild`, `hub.<id>`, `world.<clé>` ou une clé de monde nue — jamais vide. Normalisation en
  minuscules.

### 3. Câblage des protections (aucune régression de comportement pour OP)

- `HubWorldProtectionListener` : bypass casse/pose = `buildPermissions.mayBuild(player, world)`
  (au lieu de `rpgquest.admin.world` en dur). Les dégâts restent protégés pour **tout le monde**.
- `ClaimsWorldRulesListener` : bypass build-hors-claim = `buildPermissions.mayBuild(...)`.
- `ZoneProtectionListener` : **deux portes distinctes** —
  build/interaction : `rpgquest.admin.world` ∨ `rpgquest.build.zone` ∨
  `buildPermissions.mayBuild(player, <monde de la zone>)` (un builder-hub-0 édite ainsi la zone du
  village **de son Hub**, pas celle d'un autre monde) ;
  combat (PvP, dégâts PNJ) : `rpgquest.admin.world` seul.
- `ClaimProtectionListener` : bypass = `rpgquest.claim.bypass` ∨ `rpgquest.admin.world`.
  **Aucune** permission de build n'y donne accès — garantie centrale de l'issue.
- `ClaimCommand` : `/claim admin` = `rpgquest.claim.admin` ∨ `rpgquest.admin.world`.

### 4. `/rpgadmin` — granularité par sous-commande

- `permissionFor(subcommand)` mappe chaque branche vers son nœud
  (`rpgquest.admin.flatten|zone|portal|mob|npc|spawn|worlds|waystone|story|player|guide` ;
  `worldportal` partage `rpgquest.admin.portal`).
- `requirePermission(sender, node)` : passe si `hasPermission(node)` **ou**
  `hasPermission(rpgquest.admin.world)` — le parapluie reste suffisant pour tout, que le
  gestionnaire de permissions déploie ou non la hiérarchie `children` de `plugin.yml` (compat OP
  + rôle historique).
- `onCommand` : dispatch de permission centralisé avant l'exécution de chaque branche.
- `onTabComplete` : les sous-commandes non autorisées ne sont plus suggérées.

### 5. `plugin.yml`

Arbre complet ajouté. `rpgquest.admin.world` (op) porte **en enfants** : `rpgquest.build.*`,
`rpgquest.build.zone`, `rpgquest.claim.bypass`, `rpgquest.claim.admin`, et les 12
`rpgquest.admin.<action>`. `rpgquest.build.*` (op) porte `rpgquest.build.hub.*`,
`rpgquest.build.wild`, `rpgquest.build.zone`. Nœuds explicites : `rpgquest.build.hub.*`,
`rpgquest.build.hub.0`, `rpgquest.build.wild`, `rpgquest.build.zone` (tous `default: false`) ;
`rpgquest.claim.bypass` / `rpgquest.claim.admin` (`default: op`) ; chaque `rpgquest.admin.<action>`
(`default: false`).

### 6. `config.yml`

Section `permissions.build-areas: {}` (vide) avec commentaire d'exemple documenté.

## Fichiers créés

* `src/main/java/com/lodygames/rpgquest/permission/RpgQuestPermissions.java`
* `src/main/java/com/lodygames/rpgquest/permission/BuildArea.java`
* `src/main/java/com/lodygames/rpgquest/permission/BuildPermissionService.java`
* `src/main/java/com/lodygames/rpgquest/config/PermissionsConfig.java`
* `src/test/java/com/lodygames/rpgquest/permission/TestBuildPermissions.java` (fabrique de test)
* `src/test/java/com/lodygames/rpgquest/permission/BuildPermissionServiceTest.java`
* `src/test/java/com/lodygames/rpgquest/admin/RpgAdminCommandPermissionsTest.java`
* `docs/PERMISSIONS.md`
* `docs/claude-reports/2026-09-05_2314_permissions-granulaires-issue-27.md` (ce fichier)

## Fichiers modifiés

* `src/main/resources/plugin.yml` — arbre de permissions complet (issue #27).
* `src/main/resources/config.yml` — section `permissions.build-areas`.
* `src/main/java/com/lodygames/rpgquest/config/PluginConfig.java` — champ `permissions`.
* `src/main/java/com/lodygames/rpgquest/config/ConfigValidator.java` — `validatePermissions`.
* `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java` — instancie
  `BuildPermissionService`, l'injecte aux 3 listeners.
* `src/main/java/com/lodygames/rpgquest/hub/HubWorldProtectionListener.java`
* `src/main/java/com/lodygames/rpgquest/zone/ZoneProtectionListener.java`
* `src/main/java/com/lodygames/rpgquest/claim/ClaimProtectionListener.java`
* `src/main/java/com/lodygames/rpgquest/claim/ClaimsWorldRulesListener.java`
* `src/main/java/com/lodygames/rpgquest/admin/RpgAdminCommand.java`
* `src/main/java/com/lodygames/rpgquest/command/ClaimCommand.java`
* `src/test/java/com/lodygames/rpgquest/hub/HubWorldProtectionListenerTest.java`
* `src/test/java/com/lodygames/rpgquest/zone/ZoneProtectionListenerTest.java`
* `src/test/java/com/lodygames/rpgquest/zone/WildWorldSeparationTest.java`
* `src/test/java/com/lodygames/rpgquest/claim/ClaimsWorldRulesListenerTest.java`
* `src/test/java/com/lodygames/rpgquest/claim/ClaimProtectionListenerTest.java`
* `src/test/java/com/lodygames/rpgquest/config/ConfigValidatorTest.java`
* `docs/current_state.md`, `docs/INDEX.md`, `docs/RPGQUEST_BIBLE.md`, `docs/SAFE_ZONE.md`,
  `docs/CLAIMS.md`, `docs/HUB_GUIDE.md`, `docs/ADMIN_FLATTEN.md`, `docs/TRAVEL.md`,
  `docs/deployment/SERVER_CHANGELOG.md`, `docs/claude-reports/README.md`, `.ai/ROADMAP.md`.

## Base de données / migrations

Aucune. Aucun schéma modifié, aucune migration ajoutée.

## Configuration / données

Nouvelle section `config.yml` → `permissions.build-areas` — **optionnelle**, vide par défaut. Un
`config.yml` existant sans cette section reste valide (les défauts s'appliquent). `plugin.yml`
enrichi de nouveaux nœuds de permission.

## Tests automatiques

`./gradlew test build` → **BUILD SUCCESSFUL** (exit 0).
Plugin : **956 tests, 0 échec, 0 erreur, 17 ignorés** (les 17 ignorés sont la limitation MockBukkit
héritée de l'étape 18, aucune occurrence supplémentaire). web-api : 30 tests, 0 échec.
JAR produit : `build/libs/rpgquest-0.1.0-SNAPSHOT.jar`.

Nouveaux tests :

- **`BuildPermissionServiceTest`** (11 cas) : joueur sans permission ne construit nulle part ;
  `rpgquest.build.hub.0` → Hub 0 uniquement (ni Wild ni monde des claims) ; `rpgquest.build.wild`
  → Wild uniquement ; `rpgquest.build.*` = parapluie ; `rpgquest.admin.world` construit partout ;
  `rpgquest.build.hub.*` couvre tout id ; mapping `permissions.build-areas` (`hub.<id>`,
  `world.<clé>`) ; `areaFor` ; `null` défensif.
- **`RpgAdminCommandPermissionsTest`** (7 cas) : joueur sans permission refusé avec le nom du
  nœud manquant ; NPC editor (`rpgquest.admin.npc`) ne peut pas `flatten` mais atteint `npc` ;
  `rpgquest.admin.world` et OP gardent tout ; les permissions de build seules n'ouvrent aucune
  commande admin.
- **`ClaimProtectionListenerTest`** : `buildPermissionsNeverBypassAClaim` (`build.*` +
  `build.hub.0` + `build.wild` + `build.zone` → casse dans un claim toujours annulée) ;
  `claimBypassPermissionAloneIsEnoughWithoutOp`.
- **`HubWorldProtectionListenerTest`** : builder-hub-0 construit sans `op` ; builder Wild ne
  construit pas dans le Hub ; builder-hub-0 subit quand même les dégâts.
- **`ConfigValidatorTest`** : `permissions.build-areas` — défaut vide, entrées valides
  normalisées, valeur vide rejetée, spec invalide rejetée.

Tests existants adaptés (nouvelle signature de constructeur des 3 listeners) via la fabrique
`TestBuildPermissions`.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` (client Minecraft requis) — voir `docs/deployment/SERVER_CHANGELOG.md`,
entrée du 2026-09-05 « Permissions granulaires », section Validation :

1. OP : tout `/rpgadmin`, casse/pose dans `world_hub`, casse dans un claim de `world_claim` →
   comportement identique à avant.
2. Non-OP avec `rpgquest.build.hub.0` seul : casse/pose OK dans `world_hub` ; **impossible** dans
   `world_claim` (claim et hors claim) ; `/rpgadmin flatten` refusé.
3. Non-OP avec `rpgquest.build.wild` : construit dans `wild` ; **ne contourne pas** un claim.
4. Non-OP avec `rpgquest.admin.npc` : `/rpgadmin npc info` passe la permission ; `/rpgadmin
   flatten` refusé.

## Résultat attendu

`op` n'est plus un prérequis fonctionnel des rôles courants. Les permissions sont séparées par
action ; le build est limitable par Hub/monde ; aucune permission de build ne touche aux claims
joueurs. Compatible Bukkit/Paper et LuckPerms, ce dernier non obligatoire. Aucune régression pour
les OP ni pour un rôle serveur portant déjà `rpgquest.admin.world`.

## Reset / retour à l'état initial

Rien à réinitialiser (aucune écriture de données). Pour revenir à l'ancien comportement :
retirer la branche / le JAR et remettre l'ancien `config.yml` s'il a été modifié. Les nœuds de
permission créés dans un gestionnaire externe sont inoffensifs pour l'ancienne version.

## Déploiement VeryGames

### À transférer
- `build/libs/rpgquest-<version>.jar` (JAR RPGQuest uniquement).
- **Optionnel** : section `permissions.build-areas` dans `plugins/RPGQuest/config.yml` si des
  Hubs supplémentaires existent.

### Ne PAS transférer/altérer
- `plugins/RPGQuest/data.db`, les mondes, les PNJ Citizens, tout autre plugin.
- Aucun autre fichier de config RPGQuest.

### Redémarrage requis
Oui (remplacement du JAR).

### Migration automatique
Aucune (pas de migration de base). Les groupes/rôles de permissions sont à créer manuellement
dans le gestionnaire de permissions d'après `docs/PERMISSIONS.md` §4 — tant que ce n'est pas fait,
seul un OP construit/administre (état identique à aujourd'hui).

## Rollback

1. Arrêter le serveur. 2. Remettre l'ancien JAR (et l'ancien `config.yml` si modifié).
3. Redémarrer, vérifier `/rpgquest version`. Aucune donnée à restaurer.

## Logs / diagnostic

Au démarrage : aucun log spécifique ajouté ; un `config.yml` invalide (spec `build-areas`
incorrecte) échoue au chargement avec un message explicite
(`« permissions.build-areas.<monde> » invalide : …`) et la config précédente est conservée
(comportement `ConfigService` existant).

## Documentation mise à jour

- **Nouveau** `docs/PERMISSIONS.md` — arbre complet, garantie build ⁄ claims, résolution monde →
  nœud, profils de rôles LuckPerms (builder Hub 0, builder Wild, NPC editor, content editor,
  tester), intégration Citizens, mode Bukkit pur.
- `docs/INDEX.md` — entrée `PERMISSIONS.md`.
- `docs/RPGQUEST_BIBLE.md` — section 2 (`/rpgadmin` granulaire), mentions de bypass (Hub, zones,
  claims, mobs, npc, guide, dépannage OP/joueur).
- `docs/SAFE_ZONE.md`, `docs/CLAIMS.md`, `docs/HUB_GUIDE.md`, `docs/ADMIN_FLATTEN.md`,
  `docs/TRAVEL.md` — permissions par commande + sections bypass.
- `docs/current_state.md` — nouveau système.
- `docs/deployment/SERVER_CHANGELOG.md` — entrée de déploiement dédiée.
- `.ai/ROADMAP.md` — entrée de journal de session.

## Limitations / travail restant

- Les nœuds **dynamiques** `rpgquest.build.hub.<id>` (id ≠ `0`) et `rpgquest.build.world.<clé>`
  ne sont pas énumérables dans `plugin.yml` : en Bukkit pur (sans gestionnaire de permissions),
  il faut les déclarer dans `permissions.yml` du serveur (documenté dans `PERMISSIONS.md` §6).
  Avec LuckPerms, rien à déclarer.
- L'outil de sélection cuboïde partagé (`/rpgadmin zone wand`) reste sous `rpgquest.admin.zone` :
  un éditeur de portails a besoin de `rpgquest.admin.zone` **en plus** de `rpgquest.admin.portal`
  (documenté).
- Hors périmètre (issue) : rôles/équipes internes au plugin, permissions temporaires, UI web,
  permissions géométriques par sélection WorldEdit, audit/log des actions sensibles.
- Validation manuelle Minecraft : `PENDING MANUAL VALIDATION`.

## Prochaine étape suggérée

Créer les groupes LuckPerms recommandés sur le serveur de test, exécuter la checklist de
validation manuelle du `SERVER_CHANGELOG`, puis déployer sur demande explicite.
