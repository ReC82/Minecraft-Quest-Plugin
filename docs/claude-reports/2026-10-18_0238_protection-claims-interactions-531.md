# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-18
* Heure : 02:38
* Sujet : Issue #531 — protéger animaux, seaux, plaques de pression et cultures des non-membres d'un claim
* Statut : DONE (livré et déployé sur DEV ; validation en jeu PENDING MANUAL VALIDATION, TC-356)
* Branche Git : `fix/531-claims-protection-interactions` (PR #534, fusionnée dans `chore/257-ci-github-actions`, merge `c0f2b0d`) ; rapport : `docs/531-rapport-protection-claims`
* Commit actuel si disponible : `c0f2b0d`
* Début de la tâche : 2026-10-18 02:03:51
* Fin de la tâche : 2026-10-18 02:38:36
* Durée totale : 00:34:45

## Demande
Session autonome lqorch sur le ticket #531 : dans un claim, un joueur sans droit de construire ne
doit plus déclencher dalles/fils ni piétiner les cultures, interagir avec les entités (hors PNJ
RPGQuest/Citizens), vider ou remplir un seau, ni faire piétiner la terre labourée par sa monture.

## Analyse
`ClaimProtectionListener` (état `e3f236b`) confirmait le constat du ticket :
- `onInteract` sortait sur `getHand() != HAND` ; pour `Action.PHYSICAL`, `getHand()` vaut `null` :
  la branche dalles de pression était morte, le piétinement de terre labourée jamais traité ;
- aucun gestionnaire `PlayerInteractEntityEvent` / `PlayerInteractAtEntityEvent` ;
- aucun gestionnaire de seau (`PlayerBucketEmptyEvent`/`FillEvent` n'émettent ni pose ni casse) ;
- aucun `EntityInteractEvent` (piétinement par une entité) ;
- cadres : seuls `Animals` et `ArmorStand` étaient protégés contre les dégâts.
Les PNJ sont identifiés via `NpcIdentityService` (liaison Citizens ou PDC `rpgquest:npc_id`), même
service que `ZoneProtectionListener` et `HubWorldProtectionListener` ; les écouteurs de dialogue et
de quête (`ignoreCancelled = true`) seraient sinon court-circuités par une annulation en `LOW`.

## Travail effectué
- `onInteract` : `PHYSICAL` traité avant le filtre de main. Terre labourée et œufs de tortue
  toujours protégés ; dalles de pression et fils (`TRIPWIRE`) selon `/claim flag redstone`. Refus
  silencieux (l'événement se répète à chaque tick). Le clic droit garde exactement l'ancienne règle.
- `onEntityStep(EntityInteractEvent)` : mêmes blocs, annulé seulement si un passager joueur de
  l'entité n'est ni membre ni en bypass ; une entité sans cavalier joueur reste vanilla.
- `onBucketEmpty` / `onBucketFill` : mêmes règles que casse/pose (`protect`).
- `onInteractEntity` / `onInteractAtEntity` : refus pour toute entité du claim sauf joueurs et PNJ
  RPGQuest/Citizens ; les deux mains sont annulées (laisser la main secondaire permettrait la même
  action). Couvre traite, tonte, nourriture/reproduction, laisse, nom, monture, échange villageois,
  rotation d'un cadre, seau à poisson.
- Cadres/tableaux : `HangingBreakByEntityEvent` (joueur ou projectile d'un joueur) et dégâts sur
  `Hanging` (retrait de l'objet d'un cadre).
- Constructeur : `ClaimProtectionListener(ClaimService, NpcIdentityService, AdminBypassMode)`,
  branché dans `RPGQuestBootstrap`.
- Aucun message nouveau : l'existant n'en envoyait pas, le comportement reste silencieux.

## Fichiers créés
- `docs/claude-reports/2026-10-18_0238_protection-claims-interactions-531.md`

## Fichiers modifiés
- `src/main/java/com/lodygames/rpgquest/claim/ClaimProtectionListener.java`
- `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java`
- `src/test/java/com/lodygames/rpgquest/claim/ClaimProtectionListenerTest.java` (+15 tests)
- `docs/CLAIMS.md`, `docs/RPGQUEST_BIBLE.md` (tableau des protections), `docs/MANUAL_TEST_PLAN.md` (TC-356)
- PR de rapport : ce rapport, `docs/claude-reports/README.md`, `docs/SUIVI_VALIDATIONS_DEV.md`,
  `docs/deployment/SERVER_CHANGELOG.md`

## Base de données / migrations
Aucune.

## Configuration / données
Aucune clé de configuration ; aucune donnée joueur ni monde touché.

## Tests automatiques
- Local (heap 768 m) : `ClaimProtectionListenerTest` 34/34, `BlockEventPriorityContractTest` 1/1,
  `ZoneProtectionListenerTest` 29/29 — verts.
- CI GitHub PR #534 (push + pull_request) : Tests plugin, control-panel, web-api, Scripts,
  Artefacts, CI OK — verts ; CI du merge `c0f2b0d` verte (attendue par `deploy-verygames.sh --wait-ci`).
- Nouveaux cas : dalle non-membre annulée sans main / autorisée si redstone publique ; terre
  labourée annulée même redstone publique, autorisée au membre et hors claim ; monture chevauchée
  par un non-membre annulée, sans cavalier ou chevauchée par le propriétaire autorisée ; seau de
  lave vidé et seau d'eau rempli annulés (membre autorisé) ; vache refusée sur les deux mains,
  autorisée au membre et hors claim ; PNJ RPGQuest taggé utilisable ; villageois ordinaire refusé ;
  bypass admin autorisé.

## Tests manuels à effectuer
PENDING MANUAL VALIDATION — **TC-356** (`docs/MANUAL_TEST_PLAN.md`) : deux comptes (propriétaire du
claim + non-membre) — dalle de pression, terre labourée (à pied et à dos de cochon), traite, tonte,
seaux de lave/eau, cadre, villageois, PNJ RPGQuest, non-régression membre et hors claim. Aucun test
Minecraft n'a été effectué par Claude.

## Résultat attendu
Un non-membre ne peut plus rien modifier ni exploiter dans un claim (animaux, cultures, liquides,
cadres, redstone non publique) ; les PNJ RPGQuest restent utilisables ; les membres et le
propriétaire gardent un comportement vanilla.

## Reset / retour à l'état initial
Rien à réinitialiser. Retour arrière : rollback du JAR (ci-dessous).

## Déploiement VeryGames
Effectué sur DEV le 2026-10-18 vers 02:35 : `scripts/deploy-verygames.sh -y --wait-ci` depuis le
worktree propre `/srv/rpgquest/wt-orch` détaché sur `c0f2b0d` (CI verte → assemble seul), JAR seul.
- Sauvegarde : `rpgquest-20261018T003622Z-predeploy.jar` (3 626 532 octets, SHA `b6d97b7b…` = JAR #107).
- Nouveau JAR : 3 628 062 octets, SHA-256 `9812280a…`, **vérifié par retéléchargement FTP**.
- Redémarrage : `verygames-restart.sh` (0 joueur), serveur de nouveau ONLINE, `/plugins` → RPGQuest actif.
- À ne pas altérer : `data.db`, `config.yml`, mondes. Pas de migration. PlugAdmin inchangé.
- Rollback : `scripts/rollback-verygames.sh --latest` puis redémarrage.

## Risques / points d'attention
- Un villageois **vanilla** utilisé comme marchand dans un claim sans être taggé PNJ RPGQuest n'est
  plus utilisable par les non-membres (voulu par le ticket).
- Une entité poussée (sans cavalier) peut toujours piétiner : non attribuable à un joueur.
- Laisse attachée sur une clôture et pose de cadres (`HangingPlaceEvent`) non traitées : hors ticket.
