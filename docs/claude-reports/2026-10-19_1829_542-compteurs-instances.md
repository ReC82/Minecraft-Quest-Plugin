# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-19
* Heure : 18:29
* Sujet : #542 — diagnostic de l'écart des compteurs « 1 en jeu » / « 0 instance de test » (retour TC-361 du 19/10), correction et déploiement DEV
* Statut : PARTIAL (livré et déployé ; revalidation propriétaire TC-361-ter en attente)
* Branche Git : `fix/542-compteurs-instances` (PR #596, fusionnée dans `chore/257-ci-github-actions`, merge `64f0a94`) ; rapport : `docs/542-rapport-compteurs`
* Commit actuel si disponible : `64f0a94`
* Début de la tâche : 2026-10-19 17:00:36
* Fin de la tâche : 2026-10-19 18:30:18
* Durée totale : 01:29:42

## Demande
Session `lqorch` sur #542. Après plusieurs rafraîchissements, `zz542_cochon` (profil désactivé par le
propriétaire) affichait « 1 en jeu » alors que le résumé « instances de test » et l'encadré « Instances de
test » disaient 0. Diagnostiquer sans supposer la cause, corriger la cause démontrée, clarifier les
libellés, ne jamais forcer un compteur à zéro, ne rien nettoyer, ne rien faire apparaître, ne toucher ni
au boss communautaire réel ni aux profils `zz542_*`.

## Analyse
**Sources et périmètres (code)** :
- « N en jeu » de la fiche = `alivePopulation` = `SpecialMobService.populationOf` = taille de l'ensemble
  en mémoire des UUID **suivis depuis le démarrage** (apparus, ou retrouvés au chargement de leur zone),
  retirés uniquement sur `EntityDeathEvent`. Une zone qui se décharge ne les retire **pas** (voulu : la
  créature existe toujours).
- « instances de test » (résumé et encadré) = `testPopulation` = parmi ces UUID, ceux que
  `Bukkit.getEntity` trouve **chargés et valides** et marqués test (PDC).
- Donc tout mob suivi dont la zone s'est déchargée compte dans « en jeu » mais pas dans « instances de
  test ». Aucune aide ne le disait.

**Relevés DEV (lecture seule de `agent_action`, `mob.list` après le redémarrage de 12:51 UTC)** :
5 apparitions de test de `zz542_cochon` (13:44, 14:05, 14:12, 14:16, 14:17 UTC). Compteur du cochon :
13:44 `1 suivi / 1 chargé / 1 test` → 13:45 disparu (mort décomptée) ; 14:06 et 14:13 `1/1/1` ;
**14:16:50 `1/0/0`**, inchangé jusqu'à 14:35 (profil désactivé à 14:33). Entre 14:13 et 14:16, les profils
naturels changent de zone chargée (Creeper Doré 0→2, Zombie fissible 0→3 chargés) : le joueur s'était
déplacé. Au même instant `red_creeper` = 13 suivis pour 7 chargés : le même écart, à grande échelle.

**Explosion** : `ExplosiveOnAttackExecutor` tue la source par `entity.damage(vie + 1)`, qui passe par
`EntityDeathEvent` → décompte. Vérifié par test (dégâts létaux, listener enregistré : 0 partout). L'écart
n'est donc **pas** un défaut de décompte à l'explosion.

**Défaut secondaire réel** : une variante retirée du monde **sans mourir** (`remove()` d'un autre plugin,
transformation, despawn forcé…) n'était jamais décomptée et restait jusqu'au redémarrage (elle bloquait
aussi le plafond de population).

**Conclusion** : cause démontrée = périmètres différents non expliqués ; l'instance restante de 14:16 est
le plus probablement un cochon de test resté dans une zone déchargée après le déplacement du joueur. Un
retrait sans mort ne peut pas être exclu à 100 % faute de journal serveur (le FTP ne donne pas accès aux
logs ni au monde) ; les deux cas sont désormais distingués à l'affichage.

## Travail effectué
- `SpecialMobService` : `onRemove(EntityRemoveEvent)` (MONITOR) décompte une variante sur toute sortie
  définitive (hors `UNLOAD`/`PLAYER_QUIT`) et journalise une sortie autre que la mort ;
  `onEntitiesUnload(EntitiesUnloadEvent)` retient en mémoire la dernière position (UUID, profil, monde,
  bloc, test, instant) des instances suivies ; effacée au rechargement, au décompte et à l'arrêt.
  `Population` gagne `unloaded` (liste) et `unknown()` (suivis ni chargés ni vus se décharger).
- Agent : `MobPopulationView` + `MobUnloadedInstanceView` ; `mob.list` ajoute `unloadedPopulation`,
  `unknownPopulation`, `unloadedInstances` (5 au plus par profil, pour la taille du résultat).
  Compatibilité : constructeur antérieur conservé ; PlugAdmin garde « N en jeu » avec un plugin antérieur.
- PlugAdmin : fiche « X en zone chargée (dont T de test) · Y en zone non chargée · Z introuvable(s) » ;
  bloc « Hors zone chargée » (UUID, monde, x/y/z, date UTC) ; encadré de nettoyage qui nomme les instances
  de test hors de sa portée ; même libellé dans l'onglet Apparitions ; aide « Comment lire les compteurs »
  réécrite (désactiver un profil ne retire pas ses créatures).
- Aucun compteur forcé à zéro ; aucun profil, mob, bloc, boss, monde touché.

## Fichiers créés
- `docs/claude-reports/2026-10-19_1829_542-compteurs-instances.md`

## Fichiers modifiés
- `src/main/java/com/lodygames/rpgquest/mob/SpecialMobService.java`
- `src/main/java/com/lodygames/rpgquest/web/agent/AgentActions.java`, `AgentActionExecutor.java`, `BukkitAgentActions.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/AgentPages.java`
- Tests : `SpecialMobServiceTest`, `AgentActionExecutorTest`, `MobsPageLayoutTest`
- Docs : `docs/RPGQUEST_BIBLE.md`, `docs/MANUAL_TEST_PLAN.md` (TC-361-ter, TC-360 libellés), `docs/deployment/SERVER_CHANGELOG.md`, `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune. Positions en mémoire seulement (perdues au redémarrage, comme la population) — les persister
serait une décision de schéma, non prise.

## Configuration / données
Aucune clé, aucun fichier serveur écrit.

## Tests automatiques
- Locaux ciblés (`mob.*`, `AgentActionExecutorTest`, `*BukkitAgentActionsMob*`, panel `*Mob*`) : 396 tests,
  0 échec, 8 ignorés (limitation MockBukkit préexistante `setRemoveWhenFarAway`, aucun des nouveaux).
- Nouveaux : mort par dégâts létaux comme l'explosion ; retrait sans mort vs déchargement ; position
  retenue au déchargement ; position oubliée au rechargement ; redémarrage puis rechargement compté une
  fois ; disparition sans événement = « introuvable », jamais masquée ; payload agent ; page PlugAdmin.
- CI GitHub verte sur PR #596 ; le script de déploiement a relancé la suite plugin complète (verte).

## Tests manuels à effectuer
**TC-361-ter** (PENDING MANUAL VALIDATION, compteurs seulement) — `docs/MANUAL_TEST_PLAN.md`.

## Résultat attendu
Les fiches disent séparément ce qui est chargé, hors zone chargée (avec position) et introuvable ; la
somme des « dont T de test » égale le résumé « instances de test ».

## Reset / retour à l'état initial
Rien à remettre : aucune donnée écrite.

## Déploiement VeryGames
Fait le 19/10 à 18:26 (JAR seul via `deploy-verygames.sh -y`, DEPLOY_EXIT=0), un redémarrage RCON
(0 joueur en ligne), puis PlugAdmin `scripts/plugadmin/deploy.sh` (health ONLINE).
- JAR : 3 745 777 o, SHA-256 `1f5c03880e2e205b912209c743fd5e73e4aeacdfdd9c03ea94ade9b4bd1fd7a5`.
- Backup : `rpgquest-20261019T162603Z-predeploy.jar` (3 740 849 o, SHA-256 `39fdaf9c…`).
### À transférer
Le JAR RPGQuest uniquement.
### Ne PAS transférer/altérer
`data.db`, `config.yml`, `mobs/*.yml` (dont `zz542_*`), `state/community-bosses.yml`, mondes.
### Redémarrage requis
Oui (fait, une fois).
### Migration automatique
Aucune.

## Rollback
Remettre `rpgquest-20261019T162603Z-predeploy.jar` et redémarrer ; `scripts/plugadmin/rollback.sh`.

## Logs / diagnostic
- Avant redémarrage (16:26 UTC, ancien plugin) : `zz542_cochon` 1 suivi / 0 chargé / 0 test.
- Après (16:28 UTC, nouveau plugin) : 10 profils, 0 problème, nouveaux champs présents ; `zz542_cochon`
  0 (mémoire remise à zéro par le redémarrage) ; `red_creeper` « 1 en zone non chargée », UUID
  `5a381401-3196-499c-97d1-eb90b701cb8c`, `wild` 4447 / 11 / −1481 (chargé au démarrage puis déchargé :
  le mécanisme fonctionne en réel). Boss communautaire réel toujours 1 actif, non touché.
- **Instance résiduelle du cochon** : non identifiable avant redémarrage (l'ancien plugin n'exposait pas
  les UUID). S'il existe encore, il réapparaîtra « en zone chargée » (instance de test) quand un joueur
  s'en approchera ; action ciblée possible alors : « Nettoyer les instances de test » (retire les seules
  instances de test chargées) — **à décider par le propriétaire, non exécutée**.
- Une sortie sans mort est désormais journalisée : `Mob spécial « … » (uuid) retiré du monde sans mourir (cause …) : décompté.`
- Page servie vérifiée : classe déployée contenant les nouveaux libellés, `/mobs` → 303 `/login`.

## Documentation mise à jour
Bible (compteurs /mobs), MANUAL_TEST_PLAN (TC-361-ter, libellés TC-360), SERVER_CHANGELOG, registre
`SUIVI_VALIDATIONS_DEV.md` (lignes #542 + décision 9, éditions de `main` du 19/10 16:32 reportées :
#177, #560, section #583).
