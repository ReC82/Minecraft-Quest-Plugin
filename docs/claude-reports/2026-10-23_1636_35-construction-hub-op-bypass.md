# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-23
* Heure : 16:36
* Sujet : #35 rouvert — un OP bypass OFF pouvait casser un bloc au Hub
* Statut : DONE (livré et déployé sur DEV ; validation en jeu PENDING MANUAL VALIDATION, TC-302 étapes 11–15)
* Branche Git : `fix/35-hub-op-build-bypass` (PR #791, fusionnée dans `chore/257-ci-github-actions` @ `04afc09f`) ; rapport : `docs/35-rapport-construction-hub-op`
* Commit actuel si disponible : `04afc09f1528d238902e93f6b1d23c40e47d1a91`
* Début de la tâche : 2026-10-23 15:56:50
* Fin de la tâche : 2026-10-23 16:36:58
* Durée totale : 00:40:08

## Demande
Réouverture de #35 par le propriétaire le 23/10 : LoDyMcFly, OP Minecraft, `/rpgadmin bypass off`, a pu
casser un bloc au Hub. Attendu : un OP joue comme un joueur normal tant que le bypass est OFF ; la
construction au Hub d'un OP passe par le bypass ON ; les droits builder explicitement attribués sont
préservés dans leur périmètre ; aucun droit réel, OP, groupe ni LuckPerms modifié.

## Analyse
Cause : `HubWorldProtectionListener.isBypassingBuild` appelait `RpgPermissions.canBuildInHub`, qui
accepte l'ombrelle `rpgquest.admin.world` (`default: op`) **sans passer par le mode bypass** — la
livraison #35 du 14/10 avait volontairement classé la construction comme « droit, pas contournement ».
Second chemin : Bukkit répond `true` à un OP pour tout nœud **non déclaré** dans `plugin.yml`
(`rpgquest.build.hub.world_hub` ne l'est pas), et LuckPerms `enable-ops` fait de même. Pour un OP, il est
donc impossible de distinguer un octroi builder explicite d'un défaut OP via l'API Bukkit
(`isPermissionSet` ne suffit pas non plus avec LuckPerms).

Audit des autres chemins de modification du décor au Hub :
- pose, seaux (vider/remplir), feu (briquet) : même contrôle que la casse → corrigés par le même point ;
- explosions : `blockList` vidée pour tout le monde (inchangé, aucune exemption) ;
- cadres et tableaux (poser, casser, frapper, tourner/garnir), porte-armures (manipulation), réécriture
  de panneaux, piétinement de terre labourée, poudre d'os : **aucune protection Hub n'existait**, pour
  personne → ajoutés avec la même règle ;
- `HubStationLockListener` / `HubResourceLockListener` (#675) : déjà conditionnés à la session bypass
  (`rpgquest.admin.hub.resources`) — non modifiés ; combat Hub déjà conditionné — non modifié.

## Travail effectué
Règle désormais appliquée (contrôle refait à chaque événement, donc `off` refuse immédiatement) :
- compte **non OP** portant `rpgquest.build.hub.<monde>` ou `rpgquest.build.hub.*` → construit en
  permanence dans ce Hub (droit builder explicite préservé) ;
- tout autre cas — OP, ombrelle `rpgquest.admin.world`, OP portant aussi un nœud builder — → seulement
  pendant une session `/rpgadmin bypass on` ;
- l'ombrelle suffit désormais à ouvrir la session (elle « implique tout ») : un administrateur non OP
  qui ne portait qu'elle ne perd donc pas la construction, il passe par la session.

Choix documenté : pas de `default: false` + `isPermissionSet` pour distinguer l'octroi explicite d'un
OP, car LuckPerms (`enable-ops`) rend ce test vrai pour tout nœud chez un OP ; la règle « OP ⇒ session »
est la seule fiable avec l'API publique.

## Fichiers créés
- `docs/claude-reports/2026-10-23_1636_35-construction-hub-op-bypass.md`

## Fichiers modifiés
- `src/main/java/com/lodygames/rpgquest/hub/HubWorldProtectionListener.java` (règle de construction, 7 gestionnaires de décor)
- `src/main/java/com/lodygames/rpgquest/permission/RpgPermissions.java` (`hasHubBuilderNode`)
- `src/main/java/com/lodygames/rpgquest/permission/AdminBypassMode.java` (ombrelle accepte l'ouverture de session)
- `src/main/java/com/lodygames/rpgquest/admin/RpgAdminCommand.java` (message d'activation mentionne la construction au Hub)
- `src/main/resources/plugin.yml` (descriptions `rpgquest.admin.world`, `rpgquest.build.hub.*`)
- tests `HubWorldProtectionListenerTest`, `AdminBypassModeTest`
- docs : `docs/PERMISSIONS.md`, `docs/RPGQUEST_BIBLE.md`, `docs-site/admin-testing.html`, `docs/MANUAL_TEST_PLAN.md` (TC-302 étapes 11–15), `docs/deployment/SERVER_CHANGELOG.md`, `docs/SUIVI_VALIDATIONS_DEV.md`

## Base de données / migrations
Aucune.

## Configuration / données
Aucune. LuckPerms, groupes, statut OP de DEV : non touchés.

## Tests automatiques
- Local ciblé (`RPGQUEST_TEST_MAX_HEAP=768m`) : `HubWorldProtectionListenerTest` 45, `AdminBypassModeTest` 11,
  `RpgPermissionsMatrixTest` 9, `BridgeProvenanceTest` 21, `HubStationLockListenerTest` 10,
  `HubResourceLockListenerTest` 9, `RpgAdminTestShortcutsCommandTest` 1 — 106/106 OK.
  Nouveaux cas : OP OFF casse/pose refusées ; OFF → ON autorisé → OFF refus immédiat (casse et pose) ;
  OP OFF seau/briquet/panneau refusés ; OP avec nœud builder soumis à la session ; ombrelle non OP via
  session ; joueur normal refusé (et ne peut ouvrir de session) ; builder non OP permanent (tests existants) ;
  cadres (rotation, casse, coup) refusés au joueur normal, autorisés au builder ; piétinement refusé ;
  autre monde intact.
- CI GitHub suite complète verte sur la PR #791 (deux runs) et sur la fusion `04afc09f` (run 39968925327).

## Tests manuels à effectuer
PENDING MANUAL VALIDATION — `docs/MANUAL_TEST_PLAN.md` TC-302, étapes 11 à 15 (revalidation ciblée).
Aucun test en jeu n'a été fait par Claude.

## Résultat attendu
OP bypass OFF : rien cassé/posé/modifié au Hub. Bypass ON : autorisé. `off` : refusé tout de suite.

## Reset / retour à l'état initial
Rien à remettre : aucune donnée modifiée.

## Déploiement VeryGames
- 23/10 16:34 : `scripts/deploy-verygames.sh -y` (CI verte sur `04afc09`), JAR 4 377 891 octets,
  SHA-256 `b80c13a6…` ; JAR distant retéléchargé : SHA identique.
- Sauvegarde : `rpgquest-20261023T143419Z-predeploy.jar` (= JAR `7093bc80`, SHA `1dad952a…`).
- Redémarrage `scripts/verygames-restart.sh` à 0 joueur ; DEV en ligne, RPGQuest actif (`plugins`).
- PlugAdmin non concerné (aucun fichier `control-panel/`).
- À ne pas altérer : `data.db`, configs, LuckPerms.

## Rollback
`scripts/rollback-verygames.sh` avec `rpgquest-20261023T143419Z-predeploy.jar`, puis redémarrage.
Effet : retour au comportement où un OP construit au Hub sans bypass.

## Logs / diagnostic
Journal de déploiement local `/home/ubuntu/deploy-35-1634.log` (hors dépôt).

## Documentation mise à jour
PERMISSIONS, Bible (`/rpgadmin bypass`), docs-site admin-testing, TC-302, SERVER_CHANGELOG, registre.

## Limitations / travail restant
- Un OP qui est aussi builder explicite doit passer par `/rpgadmin bypass on` pour construire au Hub
  (indistinguable d'un défaut OP).
- Plusieurs Hubs dans le même monde restent non distinguables (limite #200 inchangée).

## Prochaine étape suggérée
Propriétaire : TC-302 étapes 11–15 sur DEV, puis fermer #35 (et #321) si tout est conforme.
