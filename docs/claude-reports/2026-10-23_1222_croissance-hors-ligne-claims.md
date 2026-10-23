# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-23
* Heure : 12:22
* Sujet : #776 — croissance des plantations hors ligne dans le monde des claims, sans chunk chargé en permanence
* Statut : DONE (livré et déployé sur DEV ; validation en jeu PENDING MANUAL VALIDATION, TC-420)
* Branche Git : `feature/776-croissance-hors-ligne-claims` → fusionnée dans `chore/257-ci-github-actions` (PR #781, fusion `4cc191cb`) ; rapport : `docs/776-rapport-croissance-hors-ligne`
* Commit actuel si disponible : `4cc191cb` (code déployé)
* Début de la tâche : 2026-10-23 11:37:11
* Fin de la tâche : 2026-10-23 12:26:00
* Durée totale : 00:48:49

## Demande
Ticket P0 du propriétaire #776 (session autonome lqorch) : faire progresser le farming des claims sans
joueur présent, par rattrapage au chargement du chunk (instant mémorisé), barème vanilla documenté,
plafond, lots sur plusieurs ticks, sans scan global ni chunk chargé, limité aux claims, idempotent,
`BlockGrowEvent` respecté ; seule la croissance des plantes ; config via ConfigFileCompleter ; docs ;
tests ; déploiement DEV ; laisser le ticket ouvert.

## Analyse
- Aucun mécanisme de croissance existant ; `ClaimService` indexe les claims par monde
  (`claimsInWorld`) mais les charge **en asynchrone** au démarrage → la décision « ce chunk touche un
  claim » au rechargement doit attendre ce chargement (sinon une marque serait perdue).
- `ClaimProtectionListener` (#531) n'écoute pas `BlockGrowEvent` : le rattrapage ne contourne aucune
  protection (elles portent sur les actions de non-membres).
- Horloge : `World#getGameTime()` plutôt que l'heure murale — n'avance que serveur allumé, ignore
  `/time set` ; un serveur éteint ne fait rien pousser en vanilla.
- Le PDC du chunk est sauvegardé avec ses blocs : après un crash, marque et blocs reviennent ensemble
  → pas de double croissance.

## Travail effectué
- `config/OfflineGrowthConfig` + champ `offlineGrowth` de `ClaimConfig` (constructeurs existants
  conservés), validation dans `ConfigValidator` (`max-catch-up-minutes` 1–43 200, `blocks-per-tick`
  1–10 000, monde non vide), section `claims.offline-growth` dans le `config.yml` embarqué (complétée
  automatiquement par `ConfigFileCompleter`, mécanisme générique des clés manquantes).
- `claim/growth/PlantGrowthSimulator` : barème vanilla (random ticks moyens `vitesse/4096` par tick,
  succès arrondis au hasard) — cultures avec facteur vanilla de terre labourée (humide 3 / sèche 1,
  voisines ¼, ÷2), betteraves ×⅔, tiges + **un** fruit sur sol valide dans le même claim puis tige
  attachée, verrues 1/10, baies 1/5 (lumière au-dessus), cacao 1/5, canne/cactus (16 ticks par bloc,
  colonne ≤ 3, cactus jamais à côté d'un bloc solide/lave), bambou (port de `growBamboo`, ≤ 16).
  Lumière brute ≥ 9. Chaque pose/montée d'âge = `BlockGrowEvent` ; annulé → la plante s'arrête.
  Voisin dans un chunk non chargé = absent (`isChunkLoaded`), jamais lu.
- `claim/growth/OfflineGrowthService` : marque `rpgquest:offline_growth_since` (gameTime) au
  `ChunkUnloadEvent` d'un chunk touchant un claim d'un monde concerné ; au `ChunkLoadEvent` (chunk non
  neuf), marque lue puis **retirée**, durée plafonnée, file de chunks ; à chaque tick au plus un
  inventaire (instantané, cuboïdes de claims) et `blocks-per-tick` plantes ; attend
  `ClaimService#isLoaded` ; reprend au démarrage les chunks déjà chargés ; chunk redéchargé avant son
  tour = abandonné (jamais de double croissance) ; coût par chunk journalisé (debug, INFO > 20 ms).
- `ClaimService#isLoaded` (nouveau), branchement dans `RPGQuestBootstrap`.
- Revue de mon propre code avant PR : correction du risque « marque perdue si le chunk se recharge
  avant la lecture des claims » (2e commit).

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/config/OfflineGrowthConfig.java`
- `src/main/java/com/lodygames/rpgquest/claim/growth/PlantGrowthSimulator.java`
- `src/main/java/com/lodygames/rpgquest/claim/growth/OfflineGrowthService.java`
- `src/test/java/com/lodygames/rpgquest/claim/growth/PlantGrowthSimulatorTest.java`
- `src/test/java/com/lodygames/rpgquest/claim/growth/OfflineGrowthServiceTest.java`
- ce rapport

## Fichiers modifiés
- `ClaimConfig.java`, `ConfigValidator.java`, `ClaimService.java`, `RPGQuestBootstrap.java`,
  `src/main/resources/config.yml`, `ConfigValidatorTest.java`
- Docs : `docs/CLAIMS.md`, `docs/RPGQUEST_BIBLE.md`, `docs-site/worlds.html`, `docs/current_state.md`,
  `docs/MANUAL_TEST_PLAN.md` (TC-420), `docs/deployment/SERVER_CHANGELOG.md`,
  `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune. Seule persistance : une valeur `LONG` dans le PDC des chunks de claims, consommée au rechargement.

## Configuration / données
`claims.offline-growth` : `enabled: true`, `worlds: []` (= `claims.world`), `max-catch-up-minutes: 1440`,
`blocks-per-tick: 256`. Ajoutée automatiquement au `config.yml` DEV au redémarrage — **relue sur DEV**
(FTP, lecture seule).

## Tests automatiques
- Local (ciblé, `RPGQUEST_TEST_MAX_HEAP=768m`) : paquets `claim.*` + `config.*` → 284 tests, 0 échec,
  dont `PlantGrowthSimulatorTest` (11 : chaque culture au plafond, moyenne courte et terre humide plus
  rapide que sèche, lumière, hors claim, événement annulé, fruit unique et dans le claim, sol inadapté,
  canne/cactus ≤ 3, cactus près d'un solide, bambou ≤ 16, verrues/baies/cacao, voisin de chunk non
  chargé) et `OfflineGrowthServiceTest` (9 : marque consommée une fois, redémarrage, plafond,
  autres mondes / chunk sans claim / désactivé, liste de mondes, `randomTickSpeed 0`, attente des
  claims, chunk redéchargé, champ plein de 255 plantes étalé sur ≥ 4 ticks, pierre intacte, aucun chunk
  chargé).
- Coût mesuré (MockBukkit, non représentatif de Paper) : 255 plantes, 255 blocs, ≈ 0,26–0,28 s cumulées
  sur le chunk. Sur DEV, la mesure réelle apparaîtra dans les logs (INFO si > 20 ms par chunk).
- CI GitHub : PR #781 verte (deux runs), commit de fusion `4cc191cb` vert (run 39936992269).

## Tests manuels à effectuer
TC-420 — **PENDING MANUAL VALIDATION**. Aucune plantation n'a été forcée ni simulée sur DEV par Claude.

## Résultat attendu
Une ferme de claim quittée 20–30 min a avancé à l'arrivée du joueur ; un seul fruit par tige ; rien
hors du claim ; aucun objet ; un retour immédiat ne redonne pas de bond.

## Reset / retour à l'état initial
`claims.offline-growth.enabled: false` + `/rpgquest reload` coupe le mécanisme (marques consommées sans
effet). Aucune donnée à nettoyer.

## Déploiement VeryGames
Effectué le 23/10/2026 à 12:20 (`deploy-verygames.sh -y`, CI verte), JAR 4 369 346 octets, SHA-256
retéléchargé identique `cd3ce7d4…` ; redémarrage via `verygames-restart.sh` (seul LoDyMcFly était en
ligne) ; RPGQuest actif après redémarrage.
### À transférer
Le JAR RPGQuest seul (fait).
### Ne PAS transférer/altérer
`config.yml` (complété automatiquement), `data.db`, mondes, contenus.
### Redémarrage requis
Oui (fait).
### Migration automatique
Aucune migration de base ; complétion de `config.yml` au démarrage.

## Rollback
`scripts/rollback-verygames.sh` avec `rpgquest-20261023T102018Z-predeploy.jar` (JAR `d7441bb1`) puis
redémarrage, ou désactivation par la config. L'ancien JAR ignore les marques PDC.

## Logs / diagnostic
`Croissance hors ligne : chunk claims (x, z) — N plante(s), M bloc(s) modifié(s), T µs.` (debug ; INFO
au-delà de 20 ms).

## Documentation mise à jour
CLAIMS.md (section « Croissance hors ligne des plantations » : cycle, idempotence, barème, non couvert,
garanties, limites, coût ; configuration), Bible (§ Claims), docs-site/worlds.html, current_state,
MANUAL_TEST_PLAN (TC-420), SERVER_CHANGELOG, registre SUIVI_VALIDATIONS_DEV (modifications du
propriétaire sur `main` reportées d'abord : 28 lignes, aucune perte).

## Limitations / travail restant
- Pousses d'arbres, fleurs de cactus, torche-fleur/plante carnivore, champignons, lianes, varech non
  couverts (documenté).
- Croissance moyenne en une fois (un seul `BlockGrowEvent` pour plusieurs âges d'une culture).
- Chunk chargé mais hors distance de simulation : ne pousse pas et n'est rattrapé qu'après déchargement.
- Persistance de la marque écrite pendant `ChunkUnloadEvent` : conforme à l'usage Paper, à confirmer
  par TC-420 (seul un vrai serveur le prouve).

## Prochaine étape suggérée
Le propriétaire déroule TC-420 ; selon le résultat, ajuster le plafond (24 h) ou décider d'ajouter les
arbres.
