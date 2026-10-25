# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-25
* Heure : 13:02
* Sujet : #829 — clarification propriétaire du 25/10 : audit des commandes de téléportation d'un joueur non OP, TC-436 recentré sur les déplacements en jeu
* Statut : DONE (livraison technique) — validation en jeu TC-436 étapes 9-10 PENDING MANUAL VALIDATION, décision de clôture au propriétaire
* Branche Git : `test/829-audit-permissions-tp` (PR #873, fusionnée dans `chore/257-ci-github-actions`, merge `af3efcff`) ; rapport : `docs/829-audit-permissions-rapport`
* Commit actuel si disponible : `af3efcff`
* Début de la tâche : 2026-10-25 12:41:56
* Fin de la tâche : 2026-10-25 13:01:55
* Durée totale : 00:19:59

## Demande
Session autonome lqorch (sélection automatique #836). Commentaire propriétaire du 25/10 11:41 : un joueur
ordinaire ne doit disposer d'aucune commande de téléportation et voyage par les mécaniques en jeu ; les
étapes 6-8 de TC-436 (`/tp` d'un compte OP) ne sont pas un scénario représentatif. Demandé : (1) auditer les
permissions effectives de `/tp`, `/minecraft:tp`, `/teleport`, `/rpgadmin world tp`, `/claim admin tp` et
variantes des plugins installés pour les joueurs non OP (LuckPerms) ; (2) confirmer que les déplacements
autorisés se font en jeu sans commande ; (3) garder D1/D2 en défense en profondeur sans test artificiel ;
(4) proposer une validation des permissions par test automatisé (profil non OP) et une validation des
portails en jeu séparée. Interdit : élargir la surface de commandes joueurs, modifier les claims.

## Analyse
- **Plugins DEV** (RCON `plugins`, lecture) : Citizens, LuckPerms, Multiverse-Core, RPGQuest, WorldEdit.
- **LuckPerms DEV** : copie de `plugins/LuckPerms/luckperms-h2-v2.mv.db` (FTP, lecture) ouverte en lecture seule
  (H2 2.1.214, somme SHA-1 vérifiée contre Maven Central) ; `config.yml` : `storage-method: h2`,
  `enable-ops: true`, `auto-op: false`. Groupes : `default` **sans aucun nœud** et le groupe PlugAdmin
  « Testeur RPGQuest » (`rpgquest.admin.testing`), attribué à LoDyMcFly seulement ; 2 joueurs connus.
  Les commandes `lp …` par RCON ne renvoient rien (sortie asynchrone de LuckPerms) : d'où la lecture de la base.
  Copie et JAR téléchargés supprimés après l'audit.
- **Défauts des nœuds** : vanilla `minecraft.command.teleport` (`/tp`, `/teleport`, `/minecraft:tp`) = OP ;
  Multiverse-Core 5.7.3 enregistre `multiverse.teleport.self|other.*` et `multiverse.core.spawn.*` en
  `PermissionDefault.OP` (bytecode de `CorePermissions`) ; Citizens `citizens.npc.tp|tphere|tpto` = op
  (plugin.yml) ; WorldEdit ne déclare pas `worldedit.navigation.*` (défaut Bukkit d'un nœud non déclaré = OP) ;
  RPGQuest `rpgquest.admin.world` = op. Aucun nœud `default: true` de RPGQuest ne téléporte (inventaire des
  appels `teleport` : voyages en jeu, gardes Claims, commandes admin, PNJ, agent PlugAdmin).
- **Déplacements en jeu sans commande** (code) : portails de monde (`WorldPortalTeleportListener`), portails
  historiques (`PortalService`), bornes du Réseau de voyage avec « Mon claim » (`TravelBeaconService`),
  Waystones (`WaystoneService`), Pierre de retour / Rune (`ItemTravelService`), bouton de secours du Hub.
- Conclusion : un joueur non OP n'a sur DEV **aucune** commande de téléportation ; seul un OP (défauts OP +
  `enable-ops`) les détient, d'où l'intérêt de D1/D2 comme défense en profondeur.

## Travail effectué
1. `TeleportCommandsNonOpAuditTest` (plugin complet chargé) : profil non OP vide (= DEV) puis paquet testeur
   exécutent `/rpgadmin world tp claims`, `/rpgadmin world tp <autre monde>`, `/rpgadmin spawn tp`,
   `/rpgadmin waystone tp`, `/claim admin tp <joueur>`, `/claim admin sendhome <joueur>` : « Permission
   manquante » à chaque fois, ni le joueur ni la cible ne bougent ; aucun `rpgquest.admin*`/`rpgquest.bypass*`
   accordé par défaut (y compris par enfant) ; témoin OP qui atteint bien la téléportation (sinon l'audit
   pourrait passer à tort).
2. `docs/PERMISSIONS.md` : section « Téléportation par commande : réservée à l'administration (#829) »
   (tableau d'audit, liste des nœuds à ne jamais accorder à un groupe de joueurs, refaire l'audit).
3. TC-436 : étapes 6-8 retirées du parcours propriétaire (restent couvertes par tests), étapes 9-10 sans
   commande (portail Hub → Claims ; borne « Mon claim »).
4. `docs/current_state.md` : D1/D2 présentées comme défense en profondeur.
5. Registre `docs/SUIVI_VALIDATIONS_DEV.md` : ligne #829 et décisions mises à jour.
Aucun code de production, aucune permission, aucun groupe, aucun claim modifié.

## Fichiers créés
- `src/test/java/com/lodygames/rpgquest/permission/TeleportCommandsNonOpAuditTest.java`
- `docs/claude-reports/2026-10-25_1302_829-audit-permissions-tp.md`

## Fichiers modifiés
- `docs/PERMISSIONS.md`, `docs/MANUAL_TEST_PLAN.md` (TC-436), `docs/current_state.md`
- `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune.

## Configuration / données
Aucune modification. Lecture seule de la base LuckPerms (copie locale supprimée) et des JAR des plugins.

## Tests automatiques
- Local : `TeleportCommandsNonOpAuditTest` 4/4, `RpgPermissionsMatrixTest` vert.
- CI PR #873 : tous les jobs verts (plugin, control-panel, web-api, scripts, artefacts).

## Tests manuels à effectuer
TC-436 étapes 9-10 (PENDING MANUAL VALIDATION), LoDyMcFly, bypass inactif, aucune commande : portail Hub →
Claims, puis borne « Mon claim ». Aucun test en jeu fait par Claude.

## Résultat attendu
Arrivée dans Claims avec la Pierre de retour, puis dans son propre claim via la borne, sans refus.

## Reset / retour à l'état initial
Rien à remettre en état.

## Déploiement VeryGames
### À transférer
Rien : test et documentation seulement ; le JAR DEV `56e754d8` contient déjà tout le comportement.
### Ne PAS transférer/altérer
LuckPerms (groupes, `default`), claims, `data.db`.
### Redémarrage requis
Non.
### Migration automatique
Non.

## Rollback
Sans objet (aucun déploiement).

## Logs / diagnostic
Pour refaire l'audit : `lp group default permission info` en jeu, ou PlugAdmin > Joueurs > « Lire ses droits
en jeu ».

## Documentation mise à jour
PERMISSIONS.md, MANUAL_TEST_PLAN.md (TC-436), current_state.md, SUIVI_VALIDATIONS_DEV.md.

## Limitations / travail restant
- Les commandes des autres plugins ne sont pas exécutables en test : leur défaut OP est relevé dans les JAR
  installés (à revoir si un plugin est ajouté ou mis à jour).
- Point ouvert inchangé : une téléportation de cause `UNKNOWN` vers le claim d'autrui n'est que tracée.
- Les OP détiennent toujours toutes les commandes (choix du serveur) ; D1/D2 les encadrent.

## Prochaine étape suggérée
Propriétaire : TC-436 étapes 9-10, puis décider la clôture de #829.
