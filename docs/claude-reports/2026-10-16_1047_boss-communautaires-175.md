# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-16
* Heure : 10:47
* Sujet : Issue #175 — Boss communautaires du Wild : scheduler, plafond, annonces, rappel de connexion (lot P1)
* Statut : DONE (version testable livrée et déployée sur DEV ; validation en jeu en attente — ticket laissé ouvert)
* Branche Git : feature/175-community-boss → PR #456 fusionnée dans chore/257-ci-github-actions (merge `2bbc665`)
* Commit actuel si disponible : 2bbc665 (code déployé) ; rapport sur docs/175-rapport
* Début de la tâche : 2026-10-16 09:44:32
* Fin de la tâche : 2026-10-16 10:47:03
* Durée totale : 01:02:31

## Demande
Ticket #175 (priorité propriétaire du 16/10), lot minimal testable : profils BOSS en mode « Boss
communautaire » réglables dans PlugAdmin, scheduler unique persisté et borné (intervalle 15 min, max 1),
placement sûr dans le Wild seulement (Hub/Claims interdits), annonce globale (nom, biome, waypoint réel),
rappel individuel à la connexion, tableau PlugAdmin avec apparition de test et retrait confirmé, sans
second moteur de mobs, **sans migration de base**, sans annoncer de récompenses (#176/#177).

## Analyse
- Moteur existant : `SpecialMobService` (application de profil PDC, barre de vie BOSS, drops), profils
  `SpecialMobDefinition` (catégorie SPECIAL/BOSS, mondes/biomes/zones), test spawn panel (#172).
- Limite constatée : la population de mobs spéciaux est en mémoire et reconstruite sur `ChunkLoadEvent`,
  où Paper n'a pas encore chargé les entités → un boss d'avant redémarrage n'était pas recompté. Un
  plafond fiable exige donc un verrou persistant propre au scheduler.
- Persistance sans table : fichier de données YAML (`state/community-bosses.yml`), écrit hors thread
  principal avec révisions ordonnées.
- Mondes Wild : `WildHostileRulesService#isWildWorld` (`wild.worlds` / `travel.wild-world`), avec exclusion
  explicite du Hub et du monde des Claims.
- Constat annexe : `mob.definition.update` effaçait la table de drops du profil (le formulaire ne l'édite pas).

## Travail effectué
1. **Modèle** : `BossMode` (QUEST défaut / COMMUNITY) + `community-weight` (≥ 1) sur `SpecialMobDefinition`
   (constructeur historique conservé), parseur (COMMUNITY refusé sur SPECIAL), rendu YAML.
2. **`mob.community`** : `CommunityBossSettings(+Store)` (`mobs/community-bosses.yml`, désactivé par défaut),
   `CommunityBossState(+Store)`, `CommunityBossSchedule` (au plus une tentative après échéance manquée,
   pas de rafale), `CommunityBossPicker` (tirage pondéré), `WaypointHint` (waypoint réel ≤ 600 blocs,
   distance + direction), `CommunityBossMessages` (biome en clé de traduction, nom de waypoint non
   interprété), `CommunityBossHooks` (extension vide pour #176/#177), `CommunityBossService` :
   tâche 20 s, placement dans les chunks déjà chargés (40–96 blocs d'un joueur, > 24 blocs de tout joueur,
   colonne accessible, refus zones protégées / villes / abords de waypoint / biomes-mondes-zones hors
   profil), annonce, verrou PDC + fichier, mort, retrait (différé si chunk déchargé), réconciliation
   (`EntitiesLoadEvent`, `EntitiesUnloadEvent`, tâche), orphelin réadopté sous plafond sinon retiré,
   rappel de connexion individuel.
3. **`SpecialMobService`** : redécouverte sur `EntitiesLoadEvent` (population + barre de vie après
   redémarrage), `discard()` (retrait sans drop).
4. **Agent** : `boss.community.status|settings.set|test.spawn|remove` (revalidation complète), mode/poids
   dans `mob.definition.create|update`, conservation des drops à la modification.
5. **PlugAdmin** `/mobs` : section « Boss communautaires du Wild » (état, prochain essai, dernière
   apparition, dernier essai et raison, réglages, apparition de test, boss actifs avec biome/waypoint/
   dernière position, retrait avec case de confirmation, aucun UUID affiché) ; « Type de boss » et
   « Poids » dans l'éditeur de profil. Rendu vérifié en local à 1280 et **390 px** (Playwright, pas de
   défilement horizontal), boutons de retrait passés en rouge après ce contrôle.
6. Docs, CI, fusion, déploiement DEV (JAR + PlugAdmin), vérification réelle sur DEV.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/mob/model/BossMode.java`
- `src/main/java/com/lodygames/rpgquest/mob/community/` : `CommunityBossService`, `CommunityBossSettings`,
  `CommunityBossSettingsStore`, `CommunityBossState`, `CommunityBossStateStore`, `CommunityBossInstance`,
  `CommunityBossSchedule`, `CommunityBossPicker`, `CommunityBossMessages`, `CommunityBossHooks`, `WaypointHint`
- Tests : `mob/community/CommunityBossServiceTest` (15, MockBukkit), `CommunityBossScheduleTest`,
  `CommunityBossPickerTest`, `CommunityBossStateStoreTest`, `WaypointHintTest` ;
  panel `CommunityBossPageTest`
- Ce rapport

## Fichiers modifiés
- `mob/model/SpecialMobDefinition.java`, `mob/SpecialMobDefinitionParser.java`, `mob/SpecialMobDefinitionYaml.java`,
  `mob/SpecialMobService.java`, `bootstrap/RPGQuestBootstrap.java`
- `web/agent/AgentActionType.java`, `AgentActions.java`, `AgentActionExecutor.java`, `BukkitAgentActions.java`
- `control-panel/.../agent/AgentActionCatalog.java`, `control-panel/.../web/AgentPages.java`
- Tests existants adaptés : `SpecialMobDefinitionParserTest`, `SpecialMobDefinitionYamlTest`,
  `AgentActionExecutorTest`, `StubAgentActions`, `BukkitAgentActionsMobTest`, `AgentActionCatalogTest`
- Docs : `docs/RPGQUEST_BIBLE.md` (§11), `SPECIAL_MOB_FORMAT.md`, `docs/MANUAL_TEST_PLAN.md` (TC-329),
  `docs/current_state.md`, `docs/deployment/SERVER_CHANGELOG.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune. Schéma inchangé (V41). L'état est un fichier de données YAML.

## Configuration / données
- `plugins/RPGQuest/mobs/community-bosses.yml` : créé à la première sauvegarde PlugAdmin ; absent = désactivé.
- `plugins/RPGQuest/state/community-bosses.yml` : créé par le plugin au démarrage (verrous, échéance).
- Profils : clés optionnelles `boss-mode`, `community-weight` (profils existants inchangés).

## Tests automatiques
- Locaux ciblés : `com.lodygames.rpgquest.mob.*` (dont 15 tests de service, **0 ignoré** après contournement
  de `LivingEntityMock#setRemoveWhenFarAway` non implémenté par MockBukkit), `web.agent.*` (130 tests
  executor verts), panel `AgentActionCatalogTest` (50), `MobFormHelpTest` (8), `CommunityBossPageTest` (3).
- Le test d'exécuteur ajouté a révélé un vrai défaut corrigé avant fusion (`nextDue` nul rejeté par
  `Map.copyOf` de l'outcome).
- CI GitHub : verte sur la tête de branche `44f154d` (tous jobs) ; sur le merge `2bbc665`, premier passage
  rouge sur `QuestProgressEngineTest.advancingToTheNextStepAnnouncesItsObjectivesInChat` (ligne 665,
  message attendu après une écriture asynchrone et seulement 2 ticks — test sans lien avec #175, même
  arbre vert juste avant) ; relance du job en échec : **verte**. Test fragile à signaler.

## Tests manuels à effectuer
PENDING MANUAL VALIDATION — TC-329 (`docs/MANUAL_TEST_PLAN.md`) : passer un profil Boss jetable en
« Boss communautaire », apparition de test (annonce + waypoint réel), reconnexion (rappel individuel),
intervalle 1 min sur DEV + mort + réapparition + plafond, déchargement/redémarrage sans doublon, pause
immédiate, retrait confirmé ; restaurer les réglages après.

## Résultat attendu
Aucun boss tant que le scheduler n'est pas activé dans PlugAdmin ; ensuite au plus `max-active` boss
communautaires vivants, annoncés à tous avec un repère réel, rappelés aux joueurs qui arrivent.

## Reset / retour à l'état initial
Décocher « Apparitions automatiques actives » ; « Retirer ce boss » / « Retirer toutes les apparitions de
test ». Repasser un profil en « Boss de quête ». Supprimer `mobs/community-bosses.yml` revient aux défauts.

## Déploiement VeryGames
### À transférer
Fait le 2026-10-16 ~10:44 : JAR `2bbc665` (3 225 009 o, SHA-256 80ac107342c0b24b0c9e2a231b09e7f162fdd77297e51713bfebfd840cdcd3ce)
via `scripts/deploy-verygames.sh -y`, puis `scripts/verygames-restart.sh` (0 joueur) ; PlugAdmin via
`scripts/plugadmin/deploy.sh`.
### Ne PAS transférer/altérer
`data.db`, `config.yml`, `state/community-bosses.yml` (géré par le plugin), contenus du propriétaire.
### Redémarrage requis
Oui (fait, une seule fois) — DEV de nouveau ONLINE, heartbeat agent ONLINE.
### Migration automatique
Aucune.

## Rollback
`scripts/rollback-verygames.sh --latest` (backup `rpgquest-20261016T084423Z-predeploy.jar`, 3 172 227 o)
+ redémarrage ; PlugAdmin : release précédente `/opt/plugadmin/releases/`. Fichiers ajoutés ignorés par
l'ancien JAR.

## Logs / diagnostic
- Démarrage : `Boss communautaires : scheduler en pause (N verrou(s) restauré(s), prochaine échéance dans … s).`
- Échec d'échéance : `Boss communautaire : pas d'apparition à cette échéance — <raison>` (aussi visible
  dans PlugAdmin « Dernier essai »).
- Vérifié sur DEV après déploiement : `boss.community.status` → SUCCESS, `available=true`, `enabled=false`,
  0 instance, échéance armée ; seul profil BOSS présent : `claude_diag_boss` (désactivé, mode QUEST).
- Contrôle d'accès vérifié avec un compte PlugAdmin jetable READ_ONLY, supprimé après usage.

## Documentation mise à jour
Bible §11 (section « Boss communautaires du Wild »), `SPECIAL_MOB_FORMAT.md`, TC-329, `current_state.md`,
`SERVER_CHANGELOG.md`. Pas de page docs-site (la section 11 n'en a pas).

## Limitations / travail restant
- Récompenses / participation : #176 ; tables aléatoires : #177 (`CommunityBossHooks` prêt, vide).
- Un boss n'apparaît qu'autour d'un joueur présent dans le Wild (chunks déjà chargés) : aucun boss
  « hors joueurs ». Choix de sécurité/performance assumé, à confirmer par le propriétaire.
- Le formulaire de profil n'édite toujours ni la table de drops ni les capacités autres qu'Enragé /
  Renforts : ces dernières (Explosion renforcée, Division…) restent écrasées par une modification panel
  (comportement antérieur, non traité ici).
- Test fragile `QuestProgressEngineTest.advancingToTheNextStepAnnouncesItsObjectivesInChat` en CI.

## Prochaine étape suggérée
Validation TC-329 par le propriétaire sur DEV, puis #176 (participation et paiements idempotents) en
s'appuyant sur `CommunityBossHooks.onDefeated`.
