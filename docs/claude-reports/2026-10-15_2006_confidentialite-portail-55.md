# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-15
* Heure : 20:06
* Sujet : Issue #55 — portail web : confidentialité, RGPD et joueurs mineurs (garde-fous)
* Statut : DONE (livré et déployé ; décisions juridiques et validation propriétaire en attente, ticket laissé ouvert)
* Branche Git : feature/55-confidentialite-portail (PR #438, fusionnée dans chore/257-ci-github-actions → 901d31e) ; rapport : docs/55-rapport-confidentialite
* Commit actuel si disponible : 901d31e
* Début de la tâche : 2026-10-15 19:05:10
* Fin de la tâche : 2026-10-15 20:06:11
* Durée totale : 01:01:01

## Demande
Session autonome lqorch, ticket #55 : cadrer confidentialité, RGPD et mineurs du portail public ;
implémenter les garde-fous dans le portail, documenter les décisions juridiques comme décisions du
propriétaire (pas inventées). Aucun pseudo ni donnée joueur publics tant que le propriétaire n'a pas
validé ; profils privés par défaut.

## Analyse
Existant (PR #312, #52) : instantané v2 sans UUID, `/profile web prive|public`, filtre SQL à
l'export, `player-data-public=false` sur le portail AWS, page `/confidentialite`. Écarts trouvés :
1. les profils étaient **visibles par défaut** (seul `/profile web prive` retirait) ;
2. la liste des connectés (`include-connected-players`) ignorait `/profile web prive` ;
3. `/api/profiles|leaderboards|players` relayaient l'instantané brut (aucune liste blanche) et
   publiaient des pseudos même avec `player-data-public=false` (API à jeton, fermée par nginx,
   mais sans seconde barrière) ;
4. aucune politique d'âge ni statut de consentement représentable ; aucun registre des données.
Les comptes (#53) n'existent pas : export/suppression de compte, purge des codes de liaison,
sessions ne peuvent pas être codés maintenant — documentés comme exigences pour #53.

## Travail effectué
- Plugin : `PublicProfilePolicy.Visibility` (PRIVATE/PUBLIC) + clé `web-export.profile-visibility-default`
  (`private` par défaut, validée au chargement). PRIVATE = seuls les joueurs ayant tapé
  `/profile web public` (valeur `false` de `WEB_PROFILE_HIDDEN`) sont exportés ; un choix explicite
  prime toujours ; une valeur inconnue n'est jamais plus permissive que le défaut. Classements XP,
  quêtes terminées et liste des connectés passent par la même règle (lecture asynchrone des
  préférences ; sans dépôt de variables, aucun nom exporté). `/profile web` signale « réglage par
  défaut du serveur ».
- Portail : `privacy.PublicPlayerData` (liste blanche name/skills/questsCompleted/totalXp/value,
  saisons/événements non relayés tant que non spécifiés) ; les trois API à pseudos renvoient vide si
  `player-data-public=false` ; `privacy.AgePolicy` (`accounts.age-threshold.<PAYS>`,
  `accounts.below-threshold`, `accounts.unknown-region`, comptes fermés sans configuration, jamais
  ALLOWED sous le seuil, âge déclaré non conservé) ; `privacy.ParentalConsentStatus`
  (NOT_REQUIRED/PENDING/APPROVED/REVOKED/EXPIRED, transitions bornées, compte limité tant que le
  consentement manque) ; `/confidentialite` annonce l'état réel (pseudos publiés ou non, comptes
  ouverts ou non, privé par défaut).
- Documentation : `docs/PRIVACY.md` (garde-fous, registre des données actuelles et prévues avec
  durées **proposées**, données à ne pas collecter, âge/consentement, droits, cookies, prestataires,
  sécurité, 6 décisions du propriétaire, revue juridique obligatoire).

## Fichiers créés
- docs/PRIVACY.md
- web-api/src/main/java/com/lodygames/rpgquest/webapi/privacy/{PublicPlayerData,AgePolicy,ParentalConsentStatus}.java
- web-api/src/test/java/com/lodygames/rpgquest/webapi/privacy/{PublicPlayerDataTest,AgePolicyTest,ParentalConsentStatusTest}.java
- ce rapport

## Fichiers modifiés
- Plugin : PublicProfilePolicy, ProgressionRepository, QuestStatisticsRepository, WebExportConfig,
  ConfigValidator, WebSnapshotWriter, ProfileCommand, RPGQuestBootstrap, config.yml ; tests
  ConfigValidatorTest, ProgressionRepositoryTest, PublicPortalExportTest, WebSnapshotWriterTest.
- web-api : WebApiConfig, WebApiConfigLoader, HttpServerBootstrap, ProfilesHandler, PlayersHandler,
  LeaderboardsHandler, PrivacyPageHandler ; tests HttpServerBootstrapTest, PublicHostingTest.
- Docs : WEB_PORTAL.md (§1 feuille de route, §5), WEB_API.md, RPGQUEST_BIBLE.md (`/profile web`,
  portail), current_state.md, MANUAL_TEST_PLAN.md (TC-325), deployment/SERVER_CHANGELOG.md,
  scripts/web-portal/web-api.properties.example, claude-reports/README.md.

## Base de données / migrations
Aucune migration. La variable existante `WEB_PROFILE_HIDDEN` sert : `true` = masqué, `false` =
accord explicite (déjà écrit par `/profile web public` avant ce lot), absente = défaut du serveur.

## Configuration / données
- Plugin : `web-export.profile-visibility-default: private`, ajoutée par le compléteur de config au
  démarrage (config.yml non téléversé).
- Portail : clés `accounts.*` facultatives, aucune ajoutée sur AWS (comptes fermés).
  `player-data-public=false` inchangé.
- Effet de comportement : un joueur qui n'a jamais choisi n'est plus publié (avant : publié).
  Sans effet visible aujourd'hui (`web-export.enabled=false` sur DEV).

## Tests automatiques
- `./gradlew :web-api:test` : 57/57 (dont 13 nouveaux tests privacy + 2 tests HTTP).
- Plugin ciblé (`:test --tests` ConfigValidator, ConfigFileCompleter, ProgressionRepository,
  PublicPortalExport, WebSnapshotWriter) : 86/86.
- CI GitHub PR #438 : Scripts, Tests plugin, Tests control-panel, Tests web-api — tous verts.
- `deploy-verygames.sh` a relancé la suite plugin en local : verte.

## Tests manuels à effectuer
TC-325 (docs/MANUAL_TEST_PLAN.md) — **PENDING MANUAL VALIDATION** : `/profile web` en jeu (défaut
masqué, public, prive), page `/confidentialite`, présence de la clé dans config.yml de DEV.
Aucun test Minecraft n'a été effectué dans cette session.

## Résultat attendu
Aucun pseudo publié sans accord explicite du joueur et décision du propriétaire ; API sans champ hors
liste blanche ; aucun compte ouvrable avant configuration d'une politique d'âge.

## Reset / retour à l'état initial
`web-export.profile-visibility-default: public` restaure l'ancien comportement (visible sauf
`/profile web prive`). Rollback code : voir ci-dessous.

## Déploiement VeryGames
- JAR 901d31e transféré par `scripts/deploy-verygames.sh -y` (DEPLOY_EXIT=0, 3 034 812 o == local,
  SHA-256 321d877a…). Sauvegarde : `~/.local/share/rpgquest/verygames-backups/rpgquest-20261015T180409Z-predeploy.jar`
  (3 031 021 o, plus petite que le nouveau JAR).
- 0 joueur en ligne ; DEV redémarré par `scripts/verygames-restart.sh` → ONLINE, `/plugins` :
  RPGQuest vert. Présence de la clé dans config.yml distant non vérifiée (TC-325 étape 3).
- Ne pas altérer : data.db, config.yml (pas de téléversement), mondes. Aucune migration.
- Rollback : `scripts/rollback-verygames.sh --latest` puis redémarrage.

## Portail AWS
`scripts/web-portal/deploy.sh` : release précédente `/opt/lodyquests-portal/releases/20261015-192216`,
`/health` UP (`playerDataPublic:false`). Vérifié en HTTPS : `/confidentialite` affiche « aucun pseudo
n'est publié », « ton profil est privé », « ni compte, ni connexion » ; `/api/profiles` → 404 (fermé
par nginx). Rollback : `scripts/web-portal/rollback.sh app`. PlugAdmin non touché.

## Décisions restant au propriétaire
Voir docs/PRIVACY.md §7 : pays et seuils d'âge ; garder le défaut privé ; publication des pseudos
(`player-data-public` + `web-export.enabled`, après #421) ; durées de conservation ; prestataire
e-mail et moyen de consentement parental ; textes juridiques après revue.

## Limites / suite
Export, suppression, dissociation, purge des codes et sessions : à réaliser avec les comptes (#53),
qui doivent utiliser AgePolicy et ParentalConsentStatus.
