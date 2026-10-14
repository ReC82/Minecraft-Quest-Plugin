# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-14
* Heure : 02:41
* Sujet : Issue #13 — PNJ récurrents par biome, étape 1 (moteur, modèles, persistance, apparition sûre, réparation)
* Statut : PARTIAL (étape 1 livrée et déployée sur DEV ; étapes PlugAdmin / apparence / quêtes secondaires restantes)
* Branche Git : `feature/13-recurring-encounter-npcs` (PR #344, fusionnée dans `chore/257-ci-github-actions`), rapport sur `docs/13-rapport-etape1`
* Commit actuel si disponible : `03d0772` (fusion #344)
* Début de la tâche : 2026-10-14 02:05:42
* Fin de la tâche : 2026-10-14 02:42:11
* Durée totale : 00:36:29

## Demande
Session autonome lancée par le superviseur `lqorch` : traiter #13 (version du 13/10/2026 : PNJ
récurrents propres aux biomes, l'ancien concept de livres abandonné), en auditant d'abord la
génération des waypoints/bornes, et livrer par étapes testables, jamais de génération massive au
démarrage.

## Analyse
- **Instances de biome** (`waypoint.model.BiomeInstanceKey`, #124) : pas de vrai blob de biome, mais
  une tuile `(monde, biome, region-size=256)`, calcul O(1), déterministe. Génération des waypoints :
  à l'entrée d'une instance (mouvement throttlé), candidats `WaypointGenerationPlanner` (anneau
  min/max autour du point d'entrée, graine = instance), `RandomSafeLocationFinder.findAccessibleColumn`
  (pas de canopée, sol solide, 2 blocs d'air), garde « hors claim », espacement, verrou mono-vol +
  index unique, nouvel essai borné. Les bornes (#149) réutilisent `ensureGenerated` au Hub.
  → réutilisés tels quels (resolver, planner, finder, garde claims), **sans modifier** ce mécanisme
  (seule une lecture `hasWaypointWithin` est ajoutée à `WaypointService`).
- **Identité PNJ** : `npc_citizens_bindings` lie un `npc_id` à **un seul** PNJ Citizens
  (`CitizensBindPlanner` refuse `NPC_ID_TAKEN`), et le dialogue d'un PNJ est `rpgquest:<npc_id>`.
  Un brigand présent dans chaque forêt ne peut donc pas passer par ces liaisons. Décision : registre
  propre `encounter_sites` ; `NpcIdentityService.currentId` consulte d'abord les liaisons classiques
  (prioritaires), puis ce registre. Dialogue, objectifs « parler à » et quêtes fonctionnent sans
  autre changement ; les PNJ de rencontre restent isolés des PNJ fixes.
- **Doublons** : risque d'un PNJ Citizens orphelin si le serveur s'arrête entre création et
  enregistrement. Décision : ligne écrite **avant** le PNJ, PNJ marqué par une donnée persistante
  Citizens `rpgquest-encounter = <id>` → réadopté au lieu d'être recréé.
- Quêtes : le moteur gère déjà `repeatable` — à exploiter à l'étape quêtes secondaires.

## Travail effectué
1. Modèles `encounters/*.yml` : `EncounterTemplate`, `EncounterTemplateParser` (toutes erreurs
   accumulées, biome sans espace de noms → `minecraft:`), `EncounterTemplateLoader` (doublons d'id
   rejetés), `YamlEncounterEngine` (3 exemples copiés une fois, biomes inconnus signalés).
2. `EncounterTemplateSelector` (actif, monde, biome, plafond `max_sites`, tirage pondéré
   déterministe), `EncounterWorldPolicy` (vide = `travel.wild-world`, Hub/claims toujours exclus).
3. Persistance : migration V34 `encounter_sites` + `EncounterSiteRepository` (insert-or-ignore,
   PNJ, statut, erreur, suppression).
4. `EncounterService` + `EncounterListener` : génération à l'entrée d'une instance, candidats
   uniquement dans des chunks déjà chargés, hors claim, à l'écart des waypoints et des autres
   rencontres, plafond par monde, nouvel essai 1 min/5 min/30 min/1 h, journal d'événements récents
   (CREATED/SPAWNED/ADOPTED/REPAIRED/NO_SAFE_SPOT/FAILED/DISABLED/REMOVED) ; réparation au
   chargement du chunk (table chunk → occurrence, O(1)) ; actions `retry`/`disable`/`remove`/
   `clearBackoffs` prêtes pour PlugAdmin ; PNJ « regarde les joueurs ».
5. `CitizensNpcBridge`/`NpcIdentityService` : création marquée, recherche par marque (registre
   seul), existence par UUID, crochet d'identité des rencontres.
6. `config.yml` section `encounters:` (`EncounterConfig`, bornée) ; câblage bootstrap.
7. Contenu : 3 modèles + 3 dialogues d'accueil `encounter_bandit|merchant|smith`.
8. Docs ; PR #344, CI verte, fusion ; déploiement DEV ; redémarrage.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/encounter/` : `EncounterService`, `EncounterListener`,
  `EncounterTemplateParser`, `EncounterTemplateLoader`, `EncounterTemplateSelector`,
  `EncounterWorldPolicy`, `YamlEncounterEngine`, `EncounterLoadIssue`, `EncounterLoadReport`,
  `model/EncounterTemplate`, `model/EncounterSite`
- `src/main/java/com/lodygames/rpgquest/config/EncounterConfig.java`
- `src/main/java/com/lodygames/rpgquest/database/EncounterSiteRepository.java`
- `src/main/resources/encounters/{forest_bandit,desert_merchant,mountain_smith}.yml`
- `src/main/resources/dialogues/encounter_{bandit,merchant,smith}.yml`
- Tests : `encounter/EncounterTemplateParserTest`, `EncounterTemplateSelectorTest`,
  `EncounterWorldPolicyTest`, `database/EncounterSiteRepositoryTest`
- `docs/ENCOUNTERS.md`, ce rapport

## Fichiers modifiés
- `SchemaMigrator` (V34), `ConfigValidator`, `PluginConfig`, `config.yml`
- `NpcIdentityService`, `CitizensNpcBridge`, `WaypointService` (lecture `hasWaypointWithin`),
  `RPGQuestBootstrap`
- Tests : `ConfigValidatorTest`, `BundledDialoguesValidityTest`
- Docs : `RPGQUEST_BIBLE.md`, `docs-site/npc.html`, `MANUAL_TEST_PLAN.md` (TC-303),
  `deployment/SERVER_CHANGELOG.md`, `current_state.md`, `claude-reports/README.md`

## Base de données / migrations
V34 additive et automatique : `encounter_sites` + index unique `(world, biome_instance)`. Aucune
table existante modifiée, aucune donnée joueur touchée.

## Configuration / données
- `config.yml` : section `encounters:` (défaut activé, Wild seul) — ajoutée sur le serveur par le
  complément automatique, `config.yml` jamais téléversé.
- `plugins/RPGQuest/encounters/` : 3 modèles copiés au premier démarrage (vérifié sur DEV).
- `plugins/RPGQuest/dialogues/encounter_*.yml` : nouveaux fichiers (déployés par `--also`, car la
  source de publication PlugAdmin est l'arbre du propriétaire, qui ne doit pas être modifié).

## Tests automatiques
- Locaux ciblés (verts) : `encounter.*` (12), `EncounterSiteRepositoryTest` (3),
  `SchemaMigratorTest` (42), `ConfigValidatorTest` (58), `BundledDialoguesValidityTest` (15),
  `NpcIdentityServiceTest` (12), `RPGQuestPluginTest` (5), `waypoint.*` (60) ; puis
  `config.*`, `content.*`, `dialogue.*`, `quest.*`, `KitTiersContentTest` : 453 tests, 0 échec.
- `./gradlew build -x test` vert.
- CI GitHub (suite complète plugin + control-panel + web-api) verte sur la PR #344 et sur `03d0772`.

## Tests manuels à effectuer
`PENDING MANUAL VALIDATION` — `docs/MANUAL_TEST_PLAN.md` **TC-303** (apparition en forêt/désert/
montagne, un seul PNJ par instance après sortie/reconnexion/redémarrage, deux forêts distinctes,
aucune rencontre en plaine/Hub/claims, réparation après `/npc remove`, performances). Aucun test en
jeu n'a été effectué pendant cette session.

## Résultat attendu
Dans le Wild, entrer dans une forêt jamais visitée fait apparaître un « Brigand des bois » à 24–64
blocs, sur un sol praticable ; clic droit → dialogue d'accueil ; unique et stable pour cette
instance ; idem Marchand égaré (désert/badlands) et Forgeron perdu (collines/pics).

## Reset / retour à l'état initial
Désactiver : `encounters.enabled: false` (arrête apparitions et réparations, PNJ conservés). Retirer
un PNJ : `/npc remove` sur lui **ne suffit pas** (il serait recréé) — attendre l'action PlugAdmin
« Désactiver/Retirer » de l'étape suivante, ou désactiver le système d'abord.

## Déploiement VeryGames
Effectué le 2026-10-14 ~02:39 : `scripts/deploy-verygames.sh -y --wait-ci --allow-no-backup` +
3 `--also` (dialogues), validation = CI verte sur `03d0772`, `./gradlew assemble`.
### À transférer
JAR `rpgquest-0.1.0-SNAPSHOT.jar` (2702691 o, SHA-256 `29166ee6a445960ab05ea49e679a1179dc95c416f2b62c280f867d8483d848c0`,
retéléchargé : identique) ; `RPGQuest/dialogues/encounter_{bandit,merchant,smith}.yml` (nouveaux).
### Ne PAS transférer/altérer
`config.yml` (complété automatiquement), `data.db`, `Citizens/`, `encounters/` (seedé par le JAR).
### Redémarrage requis
Oui — fait par `scripts/verygames-restart.sh` à 0 joueur ; serveur ONLINE, RPGQuest et Citizens
actifs (`/plugins`).
### Migration automatique
Oui (V34, additive).

## Rollback
`scripts/rollback-verygames.sh --latest` (restaure `rpgquest-20261014T003848Z-predeploy.jar`, JAR
#342) puis `scripts/verygames-restart.sh`. Les dialogues ajoutés sont inertes pour l'ancienne
version ; les PNJ Citizens déjà créés restent (donnée Citizens `rpgquest-encounter`) et peuvent être
retirés avec `/npc remove` si le retour arrière est définitif.

## Logs / diagnostic
Console : préfixe `[PNJ récurrents]` (CREATED, SPAWNED, ADOPTED, REPAIRED, NO_SAFE_SPOT, FAILED…),
« PNJ récurrents : N occurrence(s) chargée(s) », « Chargement des PNJ récurrents : N modèle(s) ».
Les logs serveur ne sont pas lisibles par FTP depuis AWS (`logs/` refusé) : vérification faite par
le JAR retéléchargé, le fichier de modèle seedé et `/plugins`.

## Documentation mise à jour
`docs/ENCOUNTERS.md` (nouveau), Bible §5, `docs-site/npc.html`, TC-303, changelog serveur,
`current_state.md`, index des rapports.

## Limitations / travail restant
- Les PNJ de rencontre apparaissent dans la liste Citizens de PlugAdmin comme PNJ non liés (à
  marquer dans l'onglet dédié).
- Modèles relus seulement au démarrage (rechargement à chaud avec l'étape PlugAdmin).
- `skin_url` lu et validé mais pas encore appliqué.
- Délais de nouvel essai en mémoire (remis à zéro au redémarrage) — voulu, documenté.
- Une très grande forêt sur deux tuiles peut porter deux rencontres (compromis hérité des waypoints).

## Prochaine étape suggérée
Étape 2 : actions agent + onglet PlugAdmin « PNJ récurrents » (table des modèles avec CRUD,
instances apparues par monde avec Localiser / Retenter / Désactiver / Retirer, journal récent,
rechargement des modèles), marquage des PNJ de rencontre dans le roster Citizens. Étape 3 :
apparence (skin MineSkin du modèle). Étape 4 : quêtes secondaires renouvelables (modèles `repeatable`
+ cooldown, accepter/refuser/plus tard dans les dialogues, onglet Quêtes « Secondaires / Rencontres »).
