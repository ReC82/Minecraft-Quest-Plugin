# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-19
* Heure : 14:53
* Sujet : #560 (retours TC-362 du 19/10) — noms de mobs avec balises MiniMessage brutes dans la fiche Wild ; compteur de joueurs à 0
* Statut : PARTIAL (livré, déployé sur DEV et vérifié en lecture) — revalidation ciblée `PENDING MANUAL VALIDATION` (TC-362-bis) ; compteur : anomalie non reproduite, cause non établie
* Branche Git : `fix/560-mondes-noms-joueurs` (PR #589, fusionnée dans `chore/257-ci-github-actions`, merge `7f2d486`) ; rapport : `docs/560-rapport-noms-joueurs`
* Commit actuel si disponible : `7f2d486`
* Début de la tâche : 2026-10-19 14:53:45
* Fin de la tâche : 2026-10-19 15:23:01
* Durée totale : 00:29:16

## Demande
Session superviseur `lqorch`, corrections après la validation manuelle de TC-362 par le propriétaire (19/10).
À conserver : liste, recherche, filtre par rôle, fiches Wild/Hub/Nether, rafraîchissement, graine Wild = `/seed`,
fiche Hub sur téléphone. À corriger : (1) fiche Wild, éléments liés : noms de mobs avec balises MiniMessage
brutes (`<dark_green>`, `<bold>`…), à afficher en texte lisible et sûr. (2) Compteur de joueurs : `world.list`
renvoyait 0 partout à 09:44Z alors que LoDyMcFly était en ligne dans le Wild peu avant. **Anomalie à confirmer** :
faire des relevés au même moment, vérifier la source du compteur, la rapprocher du correctif #169 ; ne corriger
que si la cause est établie, sinon documenter. Lecture seule maintenue, ticket laissé ouvert.

## Analyse
### (1) Noms de mobs
* `world.list` renvoie le `displayName` du profil tel quel (relu sur DEV après déploiement :
  `<dark_green><bold>Zombie d'entraînement</bold></dark_green>`, `<red>Diag Boss</red>`,
  `<dark_blue>Zombie Runner</dark_blue>`). `WorldPages.placesText` se contentait de l'échapper : les balises
  apparaissaient donc en clair. Même chose possible pour les noms de waypoints et de villes.
* Un utilitaire existe déjà dans le panel : `MiniText.plain` (retire les balises, normalise les espaces), déjà
  utilisé par la page Mobs. C'est lui que j'ai réutilisé.

### (2) Compteur de joueurs — relevés
Relevés en lecture de la base PlugAdmin (`agent_action`, aucune modification) :

| Heure (UTC) | Action | Résultat |
|---|---|---|
| 09:38:15 | `player.catalog` (propriétaire) | 1 en ligne : LoDyMcFly, monde `wild` (-1338, 66, 4080) ; `lastSeen` = 09:38:15 |
| 09:44:00 | `world.list` (propriétaire) | 6 mondes, `players` = 0 partout |
| 10:19 / 10:25 | `mob.test.spawn` | apparition « près de LoDyMcFly » (joueur à nouveau connecté) |
| 10:43:43 | `player.catalog` (session #169) | LoDyMcFly en ligne, monde `wild` |
| 12:54:36 | `player.catalog` + `world.list`, mis en file au même instant (`claude-560`) | 0 en ligne / 0 dans chaque monde — cohérents, mais personne n'était connecté |

* **Aucun relevé** entre 09:38:15 et 09:44:00 ne prouve que LoDyMcFly était encore connecté à 09:44. Le
  heartbeat de l'agent ne garde que la dernière valeur, et rien dans les données ne trace les déconnexions.
  Je n'ai pas pu faire de relevé avec un joueur connecté : personne n'était en ligne pendant la session.
* Source du compteur : `World#getPlayerCount()` de Paper, lu **sur le thread principal**, dans le même appel que
  le reste du relevé (`BukkitAgentActions.worldInputs`, via `onMain`). Ni cache, ni instantané ancien, ni
  Multiverse. `player.catalog` lit le monde de chaque joueur via `getOnlinePlayers()`, sur le même thread.
  Je n'ai trouvé dans le code aucune raison pour que les deux divergent.
* Lien avec #169 : ce bug venait d'une **source PlugAdmin différente** (`player.list` périmé depuis le 09/09
  au lieu de `player.catalog`). `/worlds`, lui, lit son propre relevé `world.list`, rafraîchi par le bouton de la
  page : ce n'est pas le même défaut. Reprendre `OnlinePlayers` ne changerait rien au compteur, car il est
  calculé côté plugin.
* **Conclusion : cause non établie, aucun bug prouvé. Je n'ai rien « corrigé » à l'aveugle.** Pour qu'un seul
  relevé suffise à trancher la prochaine fois, `world.list` renvoie maintenant aussi le **total des joueurs
  connectés**, lu dans le même appel. La page affiche ce total et écrit « attention » si la somme des mondes en
  diffère.

## Travail effectué
* Plugin : `WorldOverview.Inputs` / `View` reçoivent `onlinePlayers` (`getOnlinePlayers().size()`, même appel
  sur le thread principal que les compteurs par monde) ; `WorldListDetails` l'expose (`details.onlinePlayers`) ;
  le message de `world.list` se termine par « — N joueur(s) connecté(s) ».
* PlugAdmin : `WorldPages.placesText` passe les noms par `MiniText.plain` avant `Http.esc` (mobs, waypoints,
  villes, emplacements) ; un nom vide après nettoyage affiche l'identifiant. Nouvelle ligne
  `WorldPages.onlineLine` sous « Relevé du serveur » (liste et fiche) : « N joueur(s) connecté(s) sur le serveur
  à ce relevé », plus un avertissement si la somme des mondes diffère. Rien n'est affiché si le plugin est
  ancien (champ absent).
* Lecture seule inchangée : aucune action mutative, aucun monde/portail/bâtiment/contenu touché,
  `lody_test_flat` non créé.

## Fichiers créés
* ce rapport

## Fichiers modifiés
* `src/main/java/com/lodygames/rpgquest/world/WorldOverview.java`
* `src/main/java/com/lodygames/rpgquest/web/agent/WorldListDetails.java`
* `src/main/java/com/lodygames/rpgquest/web/agent/BukkitAgentActions.java`
* `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionExecutor.java`
* `src/test/java/com/lodygames/rpgquest/world/WorldOverviewTest.java`
* `src/test/java/com/lodygames/rpgquest/web/agent/WorldListActionTest.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/WorldPages.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/WorldsPageTest.java`
* `control-panel/src/main/resources/docs/mondes.md`
* `docs/MANUAL_TEST_PLAN.md` (TC-362-bis)
* `docs/RPGQUEST_BIBLE.md` (section Mondes)
* `docs/deployment/SERVER_CHANGELOG.md`
* `docs/SUIVI_VALIDATIONS_DEV.md` (ligne 6d-bis « Partiel » ; lignes 6b et 7 : report des éditions
  propriétaire de `main` f4b6067 et 6d25347, absentes de l'intégration, sans rien perdre)
* `docs/claude-reports/README.md` (index)

## Base de données / migrations
Aucune migration. Aucune donnée de jeu écrite. Côté PlugAdmin, seulement des relevés en lecture mis en file
(`player.catalog`, `world.list`, `created_by = claude-560`), et un compte temporaire de vérification créé puis
supprimé.

## Configuration / données
Aucune.

## Tests automatiques
* Local, ciblés (`RPGQUEST_TEST_MAX_HEAP=768m`) : `WorldOverviewTest` 6, `WorldListActionTest` 2 (total
  `onlinePlayers` + message), `AgentActionExecutorTest` 136, `WorldsPageTest` 7 (+2 : noms stylés lisibles,
  échappés, repli sur l'identifiant ; total affiché et écart signalé, rien pour un ancien plugin) — 0 échec.
* CI GitHub de la PR #589 : tous les jobs verts (plugin, control-panel, web-api, scripts, artefacts), ainsi que
  la CI du merge `7f2d486` (attendue par `--wait-ci`).

## Tests manuels à effectuer
`PENDING MANUAL VALIDATION` — **TC-362-bis**, sur ces deux points seulement :
1. Monde → Mondes → Rafraîchir → fiche `wild` : noms de mobs lisibles, sans balises.
2. Connecté dans le Wild → Rafraîchir les mondes : « 1 joueur(s) connecté(s) sur le serveur à ce relevé » et
   « Joueurs 1 » sur la carte `wild`, sans « attention ».
3. En cas d'écart : noter l'heure, la ligne affichée et la page Joueurs rafraîchie dans la même minute, puis le
   signaler dans #560.

## Résultat attendu
Noms lisibles. Compteur par monde cohérent avec le total des connectés, ou écart signalé explicitement sur la
page.

## Reset / retour à l'état initial
Rien à réinitialiser.

## Déploiement VeryGames
### À transférer
JAR RPGQuest `7f2d486` (3 738 878 octets, SHA-256 `e9c87bfc3f71842a78bdf244a8af3a39d9b275d277bda2586c1ac768b3ca2c37`),
transféré par `scripts/deploy-verygames.sh -y --wait-ci` ; PlugAdmin redéployé par `scripts/plugadmin/deploy.sh --wait-ci`.
### Ne PAS transférer/altérer
`data.db`, `config.yml`, mondes, portails, profils de mobs, contenus.
### Redémarrage requis
Oui (JAR) : `scripts/verygames-restart.sh` à 15:20, 0 joueur, DEV de nouveau ONLINE.
### Migration automatique
Aucune.

## Rollback
* JAR : `scripts/rollback-verygames.sh --latest` (sauvegarde datée
  `rpgquest-20261019T131923Z-predeploy.jar`, 3 738 617 octets = JAR `8e4f8f6`), puis redémarrage.
* PlugAdmin : version précédente conservée par `deploy.sh` sous `/opt/plugadmin/releases/` ; la remettre
  dans `/opt/plugadmin/app` et redémarrer `plugadmin.service`. Aucune donnée à restaurer.

## Logs / diagnostic
* Déploiement JAR : « JAR en ligne : 3738878 octets (== local) », « Déploiement terminé ». JAR distant
  retéléchargé : SHA-256 identique (`e9c87bfc…`).
* PlugAdmin : `/health` → `{"panel":"ONLINE"}`, `DEPLOYED` = `7f2d486`.
* `world.list` réel après redémarrage (13:21Z) : « 6 monde(s), 6 chargé(s) — Multiverse-Core 5.7.3 — 0 joueur(s)
  connecté(s) », `onlinePlayers` = 0, 0 dans chaque monde.
* Page servie `https://plugadmin.lodylands.com/worlds?world=wild`, lue avec un compte temporaire supprimé juste
  après : « profil(s) de mob qui citent ce monde : Zombie d&#39;entraînement, Diag Boss, Zombie Runner »,
  aucune occurrence de `dark_green`. La liste affiche bien la ligne « joueur(s) connecté(s) sur le serveur à ce relevé ».

## Documentation mise à jour
`docs/MANUAL_TEST_PLAN.md` (TC-362-bis), `docs/RPGQUEST_BIBLE.md`, aide PlugAdmin `docs/mondes.md`,
`docs/deployment/SERVER_CHANGELOG.md`, `docs/SUIVI_VALIDATIONS_DEV.md`, index des rapports.

## Limitations / travail restant
* L'anomalie du compteur n'a été ni reproduite ni expliquée : il faut un relevé avec un joueur réellement
  connecté (TC-362-bis étape 2). Le compteur reste un relevé au moment du rafraîchissement, pas une valeur en
  direct.
* Ticket #560 laissé ouvert.

## Prochaine étape suggérée
Le propriétaire joue TC-362-bis. Si « attention » apparaît, la divergence est prouvée dans un seul relevé et
pourra être corrigée.
