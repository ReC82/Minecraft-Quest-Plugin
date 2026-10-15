# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-15
* Heure : 11:54
* Sujet : #381 — outil « Emplacements PNJ » (clic droit sur un bloc), correction demandée par le propriétaire
* Statut : DONE (livré et déployé sur DEV) — `PENDING MANUAL VALIDATION` (TC-315), ticket laissé ouvert
* Branche Git : `feature/381-outil-emplacements-pnj` (PR #413) → `chore/257-ci-github-actions` ; rapport : `docs/381-outil-rapport`
* Commit actuel si disponible : merge `f9bbe57` (code `5addea7`, docs `b23f869`)
* Début de la tâche : 2026-10-15 10:43:07
* Fin de la tâche : 2026-10-15 11:55:11
* Durée totale : 01:12:04

## Instructions très courtes pour l'administrateur

1. En jeu : `/rpgadmin npc spot tool` → vous recevez l'outil « Emplacements PNJ » (un porte-armure).
2. Visez le bloc où le PNJ doit se tenir → **clic droit**. C'est enregistré (message vert + particules).
   Rien à taper. Recommencez sur d'autres blocs autant que voulu.
3. PlugAdmin → PNJ → **Emplacements** → « Actualiser » : renommez l'emplacement, choisissez le PNJ,
   « Placer / téléporter ici ». Déplacer = placer le même PNJ sur un autre emplacement.
4. Fini : retapez `/rpgadmin npc spot tool` → l'outil est rangé (retiré de l'inventaire).

## Demande

Ticket #381, « CORRECTION IMPÉRATIVE » du 15/10 : le marquage par commande
(`/rpgadmin npc spot mark`, PR #397) n'est pas conforme. Il faut un outil physique, sur le modèle
du marqueur d'emplacements de bâtiments : clic droit sur le bloc visé → emplacement enregistré
immédiatement, nom provisoire automatique, sans commande ni saisie ; anti-doublon ; aucun effet
hors outil ; pas d'interférence WorldEdit / Citizens / marqueur de bâtiments ; moyen simple
d'obtenir et ranger l'outil ; conserver tout PlugAdmin `/npcs/spots` ; tests du listener et de la
sécurité.

## Analyse

- Existant réutilisé : `BuildingSiteTool` / `BuildingSiteToolListener` (identité PDC, main
  secondaire ignorée, `HIGHEST` + `ignoreCancelled = false`, anti-rebond), `NpcSpotService`
  (création, `ALREADY_THERE`, persistance V38), `NpcPlacementPlanner.isAcceptable` (mêmes règles que
  le placement PlugAdmin), `rpgWorldWhitelist` (mondes PNJ) et `ClaimService.claimAt` (parcelles).
- Différence voulue avec l'outil de bâtiments : pas d'enclume pour le nom (#227) — le ticket exige
  l'enregistrement immédiat ; le nom se corrige dans PlugAdmin.
- Faille repérée dans le service : deux réservations du même bloc lancées avant la fin de la
  première écriture passaient toutes deux le contrôle `at()` → doublon possible. Corrigée par une
  garde par bloc.

## Travail effectué

- **`NpcSpotTool`** : porte-armure portant la clé PDC `rpgquest:npc_spot_tool` (seule
  identification ; nom/lore pour l'humain). Matériau choisi pour ne correspondre à aucune wand
  connue (hache/boussole WorldEdit, bâton Citizens, tige de blaze zone, houe en fer bâtiments, pelle
  en or villes). `removeFrom(inventory)` pour ranger.
- **`NpcSpotToolListener`** : clic droit sur un bloc avec l'outil → emplacement au centre de la case
  au-dessus du bloc visé (quelle que soit la face), regard tourné vers l'administrateur, pitch 0,
  nom vide → « Emplacement PNJ <n> ». Refus avant écriture : monde hors mondes PNJ, hauteur, pas de
  sol plein / deux blocs d'air (liquide, portail), parcelle de joueur. Événement annulé (le
  porte-armure n'est jamais posé, la clic gauche ne casse rien). Sans permission
  `rpgquest.admin.npc.spot` (ou `rpgquest.admin.npc`) : « Permission manquante », rien créé.
  Anti-doublon : main secondaire ignorée, anti-rebond 350 ms par joueur, garde de service. Retour :
  message + particules `HAPPY_VILLAGER` + son.
- **`NpcSpotService`** : garde par bloc (`IN_PROGRESS`) posée avant le contrôle d'existence ; nom par
  défaut numéroté (aussi pour `mark` sans nom).
- **`/rpgadmin npc spot tool`** : bascule donner/ranger (inventaire plein signalé). Aide et liste
  mises à jour ; `mark` présenté comme option avancée. Tab-complétion `tool`, `list`, `mark`.
- Enregistrement du listener dans `RPGQuestBootstrap` (après `ClaimService`).
- PlugAdmin `/npcs/spots` : texte d'aide pointe vers l'outil ; aucune action changée.

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/npc/spot/NpcSpotTool.java`
- `src/main/java/com/lodygames/rpgquest/npc/spot/NpcSpotToolListener.java`
- `src/test/java/com/lodygames/rpgquest/npc/spot/NpcSpotToolListenerTest.java`
- ce rapport

## Fichiers modifiés
- `src/main/java/com/lodygames/rpgquest/npc/spot/NpcSpotService.java`
- `src/main/java/com/lodygames/rpgquest/admin/RpgAdminCommand.java`
- `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java`
- `src/main/java/com/lodygames/rpgquest/web/agent/BukkitAgentActions.java` (cas `IN_PROGRESS` de la capture)
- `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionType.java` (commentaire)
- `src/main/resources/plugin.yml` (description du nœud)
- `src/test/java/com/lodygames/rpgquest/npc/spot/NpcSpotServiceTest.java`
- `control-panel/.../web/AgentPages.java`, `.../agent/AgentActionCatalog.java` (commentaire),
  `control-panel/src/main/resources/docs/pnj-citizens.md`, `.../docs/rpgquest-commandes.md`,
  `control-panel/.../web/NpcSpotsPageTest.java`
- `docs/RPGQUEST_BIBLE.md`, `docs-site/npc.html`, `docs/current_state.md`,
  `docs/MANUAL_TEST_PLAN.md` (TC-315 étapes 0-2 et 12), `docs/deployment/SERVER_CHANGELOG.md`

## Base de données / migrations
Aucune. Schéma V38 inchangé (tables `npc_spots`, `npc_spot_ids`).

## Configuration / données
Aucune.

## Tests automatiques
- Local : `NpcSpotToolListenerTest` 12/12 (MockBukkit + SQLite temporaire : création au-dessus du
  bloc avec nom auto, deux blocs → deux emplacements, double clic → un seul + un seul message,
  réservations simultanées → une seule écriture, porte-armure ordinaire/renommé/hache/bâton ignorés,
  main secondaire et clic gauche → rien, sans permission → rien, bloc impossible / monde sans PNJ /
  parcelle → refus clair, orientation vers l'administrateur, outil reconnu par PDC et rangé).
  `NpcSpotServiceTest` 12/12, `NpcSpotStateTest` 5/5, `AgentActionExecutorTest` 124/124.
- Panel local : `NpcSpotsPageTest` 9/9, `CommandReferenceSheetTest` 6/6, `DocsPagesTest` 8/8,
  `AgentActionCatalogTest` 47/47, `NpcsCatalogTest` 53/53.
- CI GitHub PR #413 : verte. Un échec isolé de `NpcsCatalogTest.theMainPageHasOneCreateButton…`
  sur l'exécution `pull_request` (vert sur l'exécution `push` du même code et en local) ; job relancé
  → vert. Test instable non lié, non corrigé ici. CI du commit de fusion `f9bbe57` : verte.
- `deploy-verygames.sh` a relancé tests + build du plugin : verts.

## Tests manuels à effectuer
`PENDING MANUAL VALIDATION` — TC-315 dans `docs/MANUAL_TEST_PLAN.md`, en particulier étapes 0 à 2
(outil, clic droit, doublon, refus, autres objets) et 12 (reconnexion/restart, joueur sans
permission), puis 3 à 10 (placer / déplacer A→B avec un PNJ de test). Aucun test en jeu n'a été
effectué par cette session.

## Résultat attendu
Un clic droit avec l'outil sur un bloc valide crée un emplacement visible dans PlugAdmin après
« Actualiser » ; aucun emplacement n'est créé autrement que par ce clic (ou la commande avancée).

## Reset / retour à l'état initial
Emplacements de test : « Supprimer l'emplacement » dans PlugAdmin (après « Libérer » s'il est
occupé). Outil : `/rpgadmin npc spot tool` le retire de l'inventaire.

## Déploiement VeryGames
Effectué le 2026-10-15 :
- JAR `f9bbe57` transféré par `scripts/deploy-verygames.sh -y` (« JAR en ligne : 3005692 octets
  (== local) ») ; JAR distant retéléchargé : SHA-256 `6cc711f3…f86a` = local. Sauvegarde du JAR
  précédent (`5790f1d`, `55297e39…`) : `rpgquest-20261015T095221Z-predeploy.jar`.
- Redémarrage DEV par `scripts/verygames-restart.sh` (0 joueur connecté au moment du stop) :
  serveur revenu ONLINE, `pl` montre RPGQuest actif.
- Panel `f9bbe57` déployé par `scripts/plugadmin/deploy.sh` (CI verte, health ONLINE) ; le JAR
  servi contient la nouvelle aide (`npc spot tool`) ; `/npcs/spots` répond 303 → /login.

### À transférer
Le JAR RPGQuest seul (fait). Panel local (fait).
### Ne PAS transférer/altérer
`data.db`, `config.yml`, fichiers de contenu, Citizens.
### Redémarrage requis
Oui (fait).
### Migration automatique
Aucune.

## Rollback
`scripts/rollback-verygames.sh` avec la sauvegarde `rpgquest-20261015T095221Z-predeploy.jar`, puis
redémarrage ; panel : release précédente dans `/opt/plugadmin/releases/`. Les emplacements créés
restent valides. Après rollback, l'outil n'est plus reconnu : le retirer des inventaires (sinon il
se pose comme un porte-armure ordinaire).

## Logs / diagnostic
Échec d'écriture : `Enregistrement d'un emplacement de PNJ impossible` (SEVERE) dans la console.
Journaux du déploiement : `~/deploy-logs/deploy-381-tool.log`, `restart-381-tool.log`,
`panel-381-tool.log` (machine de build).

## Documentation mise à jour
Bible, `docs-site/npc.html`, `current_state.md`, `MANUAL_TEST_PLAN.md` (TC-315), `SERVER_CHANGELOG.md`,
fiches PlugAdmin `pnj-citizens.md` et `rpgquest-commandes.md`.

## Limitations / travail restant
- Pas de mode « outil » hors objet : l'outil est un objet ; le ranger = le retirer de l'inventaire.
- Orientation par défaut vers l'administrateur (exacte, non arrondie) ; correction dans PlugAdmin.
- Hors MVP, inchangé : déplacement d'une occurrence de PNJ récurrent.
- Test instable `NpcsCatalogTest.theMainPageHasOneCreateButtonOneRefreshAndNoTechnicalClutter`
  (jargon détecté de façon intermittente) à examiner dans un ticket séparé.

## Prochaine étape suggérée
Le propriétaire valide TC-315 en jeu avec un PNJ de test ; ticket #381 laissé ouvert jusque-là.
