# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-13
* Heure : 01:45
* Sujet : session de nuit — backlog prioritaire, #246, portail web, déploiement DEV
* Statut : PARTIAL (tout le code livré est testé et déployé ; validations en jeu du propriétaire à faire ; #299 partiel, #53/#57 conçus seulement, #300/#302 non traités)
* Branche Git : `chore/257-ci-github-actions` (ligne d'intégration) ; branches de lot listées plus bas
* Commit actuel si disponible : `d43a2f4` (intégration) — déployé : JAR `2fe36bc`, panel `d43a2f4` (voir § Déploiement)
* Début de la tâche : 2026-10-12 23:49:57
* Fin de la tâche : 2026-10-13 01:50:54
* Durée totale : 02:00:57

## Demande

Travailler la nuit sur le backlog, dans l'ordre : priorités absolues #73+#208, #210, #211, #215,
#220, #221 ; puis #141+#242, #185+#240, #218, #12, #69+#209, #72, #85, #248, #295 ; portail web
#52–#57 ; analyse #296 sans message réel ; #293 ; puis tickets libres. Lot ajouté en cours de nuit :
#246 (capturer une construction posée), sans toucher à la Maison de Lily. Coder, tester,
fusionner, déployer sur DEV, redémarrer à 0 joueur, préserver données/constructions, aucun appel IA
payant, ne fermer aucun ticket demandant un test en jeu.

## Analyse — cause et correctif par ticket

| Ticket | Cause | Correctif | PR |
| --- | --- | --- | --- |
| #73 / #208 | portail `hub_to_claims` en `WORLD_SPAWN` : même point pour tous, retour sur l'ancien emplacement après reset | stratégie `CLAIM_HOME` (son claim, sinon point sûr réparti 64–2000 blocs, ≥ 100 blocs des claims) ; portail serveur basculé ; aperçu de reset complet | #303 |
| #220 | le lit vanilla fixait la réapparition | lit/ancre : événement annulé, message, point effacé à la connexion et au reset | #303 |
| #210 | pseudo saisi comparé au paramètre UUID | une case décochée, motif facultatif, refus d'un doublon | #303 |
| #221 | expiration absolue en base | temps restant (V32), points de contrôle 60 s, quit, arrêt | #303 |
| #215 | — (fonction absente) | « Dernier waypoint » dans la borne | #303 |
| #211 | JSON refusé, PNJ ignorés, ID de dialogue namespacé rejeté, pas de retour arrière | import JSON, création du PNJ, tout-ou-rien ; pack Tom | #303 |
| #141 / #242 (+#185/#240) | publication à chaud sans reconstruction de l'index/écouteurs | `afterExternalReload()` après publication de quêtes | #304 |
| #218 | paliers 3–5 absents | quêtes `kit_tier3`–`5`, Guide, config ; `ConfigFileCompleter` complète les paliers par `level` | #304, #311 |
| #246 | — | aperçu + capture par jeton : état réel du monde → nouveau `.schem` + définition avec provenance ; conteneurs vides, entités listées ; refus voisin/chunk/ID ; anti-double-envoi | #309 |
| #12 | signal à PNJ + 2,2 blocs, 1 particule/s | « ! » / « … » à 0,6 au-dessus de la tête, 2 passes/s ; anciens défauts migrés | #310, #311 |
| #69 / #209 | l'XP de combat du même kill écrasait la progression dans l'ActionBar | canal commun : progression prioritaire 2 s, XP ajoutée, regroupement (3) | #310 |
| #72 | garde `LACKS_CUSTOM_ITEM` absente + double clic (réévaluation asynchrone) | garde ajoutée, nœud « déjà détenu » ; un choix à la fois par joueur | #311 |
| #85 | `customitem give` jette au sol si plein | condition `HAS_INVENTORY_SPACE` (moteur, éditeur, panel) ; nœud « inventaire plein » | #311 |
| #295 | règles Claims sur `Monster` ; le phantom est `Enemy` | spawn, purge, ciblage, entrée par portail/TP sur `Enemy` | #311 |
| #293 | aucune notion de lecture | lu par compte, « Tout marquer comme lu », persisté | #312 |
| #301 | `light` à y=5 (plafond) : lanterne dans un trou, chaîne jusqu'au toit | une lumière ne perce plus un bloc plein, suspendue dessous | #312 |
| #299 (partiel) | « Repartir de zéro » réécrivait la conception (v2 manuelle → v3 IA en 90 s) | repartir de zéro = plan seulement ; case distincte « Réécrire la conception » ; version/empreinte tracées | #313 |
| #52–#57 | — | instantané v2 (statistiques lifetime/season/events), profils publics sans UUID, `/profile web prive`, `/joueurs`, `/confidentialite`, `/api/profiles` ; #53 et #57 conçus | #312 |
| #296 | — | analyse `docs/DISCORD_PANEL.md`, aucun appel Discord | #312 |
| #248 | — | **déjà livré et déployé** (PR #258, dans `ce87833`) : rien à coder, TC-280 en attente | — |

## Travail effectué

Lots fusionnés dans `chore/257-ci-github-actions` après CI verte : PR #303 (`72ad450`), #304,
#309 (`9b85065`), #310 (`34a156f`), #311 (`eb07cb8`), #312 (`2fe36bc`), #313 (`d43a2f4`).
Commentaire de résultat posté sur chaque ticket traité ; **aucun ticket fermé**.

## Fichiers créés

Principaux : `building/BuildingCaptureService.java`, `building/CaptureSurvey.java`,
`claim/ClaimArrivalResolver.java`, `feedback/ActionBarFeedback.java`,
`dialogue/model/HasInventorySpaceCondition.java`, `player/PublicProfilePolicy.java`,
`database/QuestStatisticsRepository.java`, panel `web/NotificationReadStore.java`, web-api
`site/PlayersPageHandler.java`, `site/PrivacyPageHandler.java`, `api/ProfilesHandler.java` ;
quêtes `kit_tier3`–`5.yml` ; docs `WEB_PORTAL.md`, `DISCORD_PANEL.md`,
`content-packs/tom_reprend_la_route.json` ; tests (voir § Tests).

## Fichiers modifiés

Voir les diffs des PR #303 à #313. Contenu embarqué modifié : `dialogues/jo.yml`,
`dialogues/guide.yml`, `config.yml`, `plugin.yml` (`/profile web`).

## Base de données / migrations

- Plugin : **V32** `item_travel_cooldowns.remaining_ms` (nullable) — appliquée sur DEV.
- Panel : colonnes/tables créées au démarrage sans perte : `notification_read`,
  `building_design_run.rewrite_design`.

## Configuration / données

`config.yml` DEV **non téléversé** : complété au démarrage par `ConfigFileCompleter`
(`claims.arrival`, paliers 3–5, `npc-hints` 2.2→0.6 et 20→10 seulement si anciens défauts).
Fichiers serveur remplacés (sauvegardés) : `dialogues/jo.yml`, `dialogues/guide.yml` (identiques au
dépôt avant remplacement), `world-portals/hub_to_claims.yml` (`CLAIM_HOME`).

## Tests automatiques

Ciblés en local (jamais deux Gradle en parallèle), CI complète verte sur chaque PR et sur chaque
commit déployé. Nouveaux tests : `BuildingCaptureServiceTest` (12), `ClaimArrivalResolverTest` (4),
`BedSpawnPolicyTest` (3), `ActionBarFeedbackTest` (5), `NpcHintShapeTest` (3),
`PublicPortalExportTest` (5), `LightIntoCeiling301Test` (2), `TomContentPackTest` (5),
`KitTiersContentTest`, ajouts dans `DialogueSessionEngineTest` (+4 dont le vrai `jo.yml` et le
double clic), `ClaimsWorldRulesListenerTest` (+5), `ConfigFileCompleterTest` (+3),
`AgentActionExecutorTest` (+3), `BuildingLifecyclePageTest` (+4), `ActionsCenterTest` (+3),
`BuildingDesignPipelineTest` (+2), `HttpServerBootstrapTest` (+5), vocabulaire du panel.
Une attente de test erronée (ordre de regroupement ActionBar) corrigée avant poussée.

## Tests manuels à effectuer (PENDING MANUAL VALIDATION)

Checklist courte pour le propriétaire :

1. **Claims (#73/#208/#220)** — compte de test : `/players` → réinitialiser ; en jeu, portail Hub →
   Claims : arrivée à un point sûr, pas sur l'ancien claim ; poser un acte ; ressortir et reprendre
   le portail → arrivée dans son claim. Dormir dans un lit puis mourir → pas de réapparition au lit.
2. **Jo (#72/#85, TC-300)** — « Réclamer mon acte » : un acte ; aussitôt encore → « Tu as déjà ton
   acte » ; inventaire plein → « Ton inventaire est plein », rien au sol ; double clic → un seul.
3. **Phantoms (#295)** — nuit sans dormir dans Claims : aucun phantom ; dans le Wild : normal.
4. **Kills (#69/#209)** — quête « Premiers pas » : chaque araignée affiche « Araignées : n/10 · +XP ».
5. **Signal PNJ (#12)** — PNJ avec quête : « ! » vert juste au-dessus du nom ; dialogue non lu : « … ».
6. **Kit (#218)** — Guide → « Commencer l'amélioration » selon le palier ; quêtes `kit_tier3`–`5`.
7. **OP (#210)** — `/players` → fiche d'un compte de test → **une case** → OP / DEOP.
8. **Cooldown (#221)** — utiliser la Pierre de retour, se déconnecter 2 min, revenir : le reste n'a
   pas diminué. **Borne (#215)** — case « Dernier waypoint ».
9. **Pack Tom (#211, TC-298)** — `/content/import` → fichier `docs/content-packs/tom_reprend_la_route.json`.
10. **Capture (#246, TC-299)** — `/buildings/sites` → fiche d'un emplacement **occupé** → section
    **« Capturer la construction actuelle »** → identifiant, nom → **Calculer l'aperçu de la
    capture** → cocher → **Capturer la construction actuelle** → `/buildings` (bibliothèque) →
    autre emplacement → **Poser un bâtiment**. Puis la Maison de Lily retouchée, par vous.
11. **Notifications (#293)** — cloche : ouvrir une notification en échec → badge −1 ; « Tout marquer
    comme lu » → 0 ; se reconnecter → toujours 0.
12. **Atelier (#299/#301)** — projet Lily : « Repartir de zéro » sans cocher « Réécrire la
    conception » → la passe « CONCEPTION EN VIGUEUR » cite votre version ; prochaine génération :
    lanternes sous le plafond, sans trou ni chaîne dans les combles (appels facturés : à votre main).
13. **`/profile web`**, **`/profile web prive`** : message attendu (le portail n'est pas hébergé).

## Résultat attendu

Voir colonne « Correctif » ; aucun bâtiment posé, monde ou donnée joueur modifié par le déploiement.

## Reset / retour à l'état initial

Voir § Rollback de l'entrée `SERVER_CHANGELOG` du 2026-10-13 01:44.

## Déploiement VeryGames

- Sauvegardes : `/srv/rpgquest/backups/20261013-predeploy-night/` (data.db intègre, config,
  portail, 2 dialogues, 5 bâtiments, 12 schematics), `control-panel-20261013-predeploy-night.db`.
- Panel `deploy.sh --wait-ci` depuis `2fe36bc` (JAR servi `6cc098c5…`), puis depuis `d43a2f4`
  à 01:50 (JAR servi `4722195f…`, sauvegarde `control-panel-20261013-predeploy-299.db`, sans
  redémarrage Minecraft). Le sondage `/health` des deux scripts échoue par course au démarrage ;
  service vérifié ensuite (200, routes et colonnes présentes).
- Plugin `deploy-verygames.sh -y --wait-ci` : JAR `d1230d5ce1bf060b3fae46e00eb0ecf396d37c1972419dfdca12d828257dfbdb`
  + `--also` jo.yml, guide.yml, hub_to_claims.yml ; contournement TLS autorisé pour la nuit, épingle
  vérifiée, seule l'expiration échoue.
- Redémarrage à 0 joueur (entre 01:41:39 et 01:44:17), ONLINE, 5 plugins, agent reconnecté, 3 mondes chargés, V32
  appliquée, `config.yml` complété comme prévu (comparaison avant/après).
- **Non déployé** : portail `web-api` (aucun hébergement ; publier des pseudos est votre décision).

## Non traité / restant

- #299 : contrat d'étages et réparation qui ajoute un escalier vers des combles — non traité.
- #300, #302 : non traités (génération IA, validation payante).
- #53 (comptes) et #57 (événements) : conçus dans `WEB_PORTAL.md`, non réalisés.
- #248 : déjà déployé, attend TC-280.
- Phantoms dans le **Hub** : la règle du Hub porte encore sur `Monster` (non touchée, hors ticket).

## Journal du travail supplémentaire

Ordre réel (heures seulement quand elles ont été relevées) : #246 (démarré à 00:32:10) → #12 →
#69/#209 → #72/#85 → #295 → complétion de `config.yml` (évite tout téléversement manuel) → portail
#52–#57 → #296 → #293 → #301 → #299 → sauvegardes (01:37, horodatage des fichiers) → panel (01:38:31,
release sauvegardée) → JAR (sauvegarde 23:41:39 UTC = 01:41:39) → redémarrage → vérifications
(battement agent 23:44:17 UTC = 01:44:17) → commentaires tickets → rapport (01:45:56).
