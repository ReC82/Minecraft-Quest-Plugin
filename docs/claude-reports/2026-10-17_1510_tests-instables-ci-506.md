# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-17
* Heure : 15:10
* Sujet : #506 — fiabiliser les tests instables `DeedClaimListenerTest`, `QuestHeartRewardIntegrationTest` et `NpcsCatalogTest`
* Statut : DONE (aucune validation en jeu requise ; ticket laissé ouvert pour constat du propriétaire)
* Branche Git : `fix/506-tests-instables` (PR #511 vers `chore/257-ci-github-actions`)
* Commit actuel si disponible : `01c6419`, `b32dfbc`, `bd31b7b` (+ commit de ce rapport)
* Début de la tâche : 2026-10-17 14:46:59
* Fin de la tâche : 2026-10-17 15:32:14
* Durée totale : 00:45:15

## Demande

Ticket #506 (créé par le propriétaire le 17/10) : trois tests échouent de façon intermittente en CI
sans lien avec le changement testé. Pour chacun : reproduire, trouver la **cause exacte**, corriger à
la source (pas de `@Disabled`, pas de retry, pas d'allongement aveugle des délais), corriger le code de
production si la cause révèle un vrai défaut. Critère : 20 exécutions ciblées successives vertes en
local (`RPGQUEST_TEST_MAX_HEAP=768m`) et CI verte. Aucune validation en jeu.

## Analyse

Les trois tests ont **trois causes différentes**. Pour les deux tests du plugin, la cause a été
prouvée par une expérience temporaire (non commitée) qui force l'ordre des événements : rouge avant
le correctif, verte après.

### 1. `DeedClaimListenerTest.rightClickingTheDeedOnceAMainClaimExistsNeverConsumesIt` — vrai défaut de production

* Le helper `rightClick` attendait une fenêtre **fixe** (20 ticks × 10 ms ≈ 200 ms). La confirmation
  enchaîne plusieurs allers-retours asynchrones (`highestEntitledTier` = jusqu'à 4 lectures SQLite,
  puis `ClaimService#create`, puis `runTask`). Sur un runner CI chargé (le plugin chargé par
  MockBukkit fait ses ~41 migrations sur une autre base en même temps), la création aboutit **pendant
  le clic suivant**.
* À ce moment, `DeedClaimListener#consumeOne` appelait `Inventory#remove(ItemStack)`, qui retire
  **toutes les piles identiques** (même type, même quantité). L'Acte de visualisation (quantité 1,
  même PDC) placé en main entre-temps était donc supprimé → « l'Acte réutilisé comme visualiseur ne
  doit jamais être consommé » échoue.
* Preuve : expérience qui ralentit de 400 ms la base du test avant le 2ᵉ clic → échec
  « claims=1, Acte absent ». Après le correctif, la même expérience passe.
* **Défaut réel en jeu** (indépendant de la CI) : un joueur qui porte deux Actes isolés (par exemple
  l'Acte redonné par Jo) les perdait **tous les deux** à la création de son claim. Nouveau test
  `confirmingConsumesOneDeedEvenWhenSeveralSingleDeedsAreCarried` : rouge avec l'ancien code
  (« expected: <1> but was: <0> »), vert avec le nouveau.

### 2. `QuestHeartRewardIntegrationTest` — interférence du plugin chargé par MockBukkit

* `MockBukkit.load(RPGQuestPlugin.class)` démarre le **vrai** plugin, dont son propre
  `HeartBonusService`, branché sur **sa propre** base SQLite, écoute `PlayerJoinEvent`.
* `server.addPlayer()` déclenche donc `refresh()` de ce service : une lecture asynchrone (0 cœur)
  placée **derrière les migrations** de la base du plugin, puis un `runTask` qui pose 0 cœur, donc
  **retire** le modificateur `rpgquest:quest_heart_bonus`.
* Si cette relecture arrive après la complétion de quête du test (runner lent), le bonus repasse de
  22 à 20 PV : échecs observés sur `onlyTheNewHealthIsFilledNotTheWholeBar` (14/10) et
  `twoQuestsSharingAGrantKeyGiveASingleHeart` (15/10).
* Preuve : expérience qui bloque l'exécuteur de la base du plugin jusqu'après la complétion →
  « expected: <22.0> but was: <20.0> ». Après le correctif, la même expérience passe.
* Pas de défaut de production : en jeu il n'y a qu'un `HeartBonusService` et un seul exécuteur
  SQLite FIFO ; une relecture lancée avant l'écriture du droit publie son `runTask` avant celui de
  la complétion, donc elle ne peut pas écraser une valeur plus récente.

### 3. `NpcsCatalogTest.theMainPageHasOneCreateButtonOneRefreshAndNoTechnicalClutter` — jeton aléatoire

* Le log CI du run 38708798652 (PR #413) montre l'échec à la ligne de la boucle « pas de jargon ».
* La recherche est **insensible à la casse** sur toute la page HTML. Celle-ci contient 13 fois le
  jeton CSRF de la session (base64url, 43 caractères aléatoires). Ce jeton contient « pdc » (par
  exemple `PdC`, `pDc`) dans ≈ 0,14 % des sessions (simulation : 276 sur 200 000 jetons de 32 octets).
  Les autres valeurs aléatoires de la page (`request_token`, identifiants d'action) sont en hexadécimal
  et ne peuvent former aucun des mots cherchés. La page ne contient aucun texte dépendant de l'heure.
* 600 sessions locales n'ont donné aucun cas, ce qui est cohérent avec cette probabilité (≈ 0,8 cas
  attendu). La preuve repose donc sur l'analyse et la simulation, pas sur une reproduction.
* Les autres tests « jargon » (`DialogueEditorTest`, `WorkshopFlowTest`) sont sensibles à la casse et
  cherchent des mots de 4 lettres ou plus : risque de l'ordre de 10⁻⁶, laissés en l'état.

## Travail effectué

1. `DeedClaimListener#consumeOne` : `player.getInventory().removeItem(deed.asOne())`. Retire
   exactement **un** Acte reconnu (`isSimilar`, PDC compris), où qu'il soit dans l'inventaire.
2. `DeedClaimListenerTest#rightClick` : vide les messages en attente, déclenche le clic, puis fait
   tourner le planificateur jusqu'au **message final** du listener (aperçu, refus, création,
   visualisation ; liste `FINAL_MESSAGES`), avec un plafond de 5 s qui fait échouer le test avec les
   messages vus. Les clics ne se chevauchent plus. Ajout du test de régression et du helper
   `deedsCarried`.
3. `QuestHeartRewardIntegrationTest#setUp` : `HandlerList.unregisterAll(plugin)` juste après le
   chargement. Seuls les services construits par le test agissent sur le joueur ; le test appelle déjà
   `hearts.refresh` et `hearts.onPostRespawn` explicitement, donc aucune couverture n'est perdue.
4. `NpcsCatalogTest` : la recherche de jargon porte sur la page **sans** le jeton CSRF
   (`page.replace(csrf(page), "")`). Toutes les autres assertions restent sur la page complète.
5. Registre `docs/SUIVI_VALIDATIONS_DEV.md` : report des modifications du propriétaire sur `main`
   (`0545ee3` : ligne 11e #183 et décision n° 4), sans rien retirer, puis ajout de la ligne 20 (#506).

Aucun test désactivé, aucun retry, aucun délai allongé à l'aveugle.

## Fichiers créés
* `docs/claude-reports/2026-10-17_1510_tests-instables-ci-506.md`

## Fichiers modifiés
* `src/main/java/com/lodygames/rpgquest/claim/DeedClaimListener.java`
* `src/test/java/com/lodygames/rpgquest/claim/DeedClaimListenerTest.java`
* `src/test/java/com/lodygames/rpgquest/quest/progress/QuestHeartRewardIntegrationTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/NpcsCatalogTest.java`
* `docs/SUIVI_VALIDATIONS_DEV.md`
* `docs/claude-reports/README.md` (index)

## Base de données / migrations
Aucune.

## Configuration / données
Aucune.

## Tests automatiques
* Preuves des causes (tests temporaires, non commités) : expérience « base du plugin bloquée » →
  `QuestHeartRewardIntegrationTest` rouge (22 → 20 PV) avant correctif, vert après ; expérience « création
  ralentie de 400 ms » → `DeedClaimListenerTest` rouge (Acte consommé) avant correctif, vert après ;
  nouveau test `confirmingConsumesOneDeedEvenWhenSeveralSingleDeedsAreCarried` rouge avec l'ancien
  `consumeOne` (« expected: <1> but was: <0> »), vert avec le nouveau.
* **20 exécutions locales successives** (`RPGQUEST_TEST_MAX_HEAP=768m`, `--rerun`, une seule Gradle à
  la fois) de `:test --tests '*DeedClaimListenerTest' --tests '*QuestHeartRewardIntegrationTest'` :
  20/20 vertes, 19 tests à chaque fois (11 + 8), 0 échec.
* **20 exécutions locales successives** de `:control-panel:test --tests
  '*NpcsCatalogTest.theMainPageHasOneCreateButtonOneRefreshAndNoTechnicalClutter'` : 20/20 vertes
  (1 test exécuté à chaque passage, vérifié dans le XML de résultats).
* Simulation de la probabilité du jeton CSRF : 276 / 200 000 (0,14 %) ; 600 sessions réelles locales :
  0 cas.
* **CI GitHub PR #511** (runs 39035038131 et 39035040276, push et pull_request) : tous les jobs verts
  (Tests plugin, Tests control-panel, Tests web-api, Artefacts, Scripts, CI OK), soit la suite complète.

## Tests manuels à effectuer
Aucun requis par le ticket. Effet visible en jeu du correctif de production, **non testé en jeu**
(`PENDING MANUAL VALIDATION`, facultatif) : avec deux Actes de propriété isolés dans l'inventaire et
sans claim, poser puis confirmer le claim → il reste **un** Acte.

## Résultat attendu
La CI ne retombe plus sur ces trois tests. En jeu, la création d'un claim ne consomme qu'un Acte.

## Reset / retour à l'état initial
Rien à réinitialiser.

## Déploiement VeryGames
**Non déployé** dans cette session. Le correctif de `DeedClaimListener` partira avec le prochain JAR
DEV construit depuis `chore/257-ci-github-actions`. Il ne justifie pas à lui seul un redémarrage de DEV
pendant les tests du propriétaire (#475).
### À transférer
Le JAR du plugin, au prochain déploiement normal.
### Ne PAS transférer/altérer
`data.db`, `config.yml`, contenus du serveur.
### Redémarrage requis
Oui, comme pour tout JAR (au prochain déploiement).
### Migration automatique
Aucune.

## Rollback
Redéployer le JAR précédent depuis la sauvegarde datée créée par `deploy-verygames.sh`. Côté code :
revert des commits `01c6419`, `b32dfbc`, `bd31b7b`.

## Logs / diagnostic
* Échecs CI d'origine : run 39023489921 (PR #503, Deed), rapport `2026-10-14_1908` (Heart, ligne 251),
  rapport `2026-10-15_1330` (Heart, run 38719056218), job 116334759281 du run 38708798652 (Npcs,
  `NpcsCatalogTest.java:978` = boucle jargon).
* Les expériences de preuve étaient des tests temporaires (réflexion sur `RPGQuestBootstrap.databaseService`
  pour bloquer l'exécuteur du plugin ; tâche de 400 ms sur la base du test). Elles n'ont pas été commitées.

## Documentation mise à jour
Registre DEV, ce rapport et son index. Aucune documentation fonctionnelle à modifier : le
comportement attendu (un Acte consommé à la création du claim) ne change pas, seul le défaut qui en
retirait plusieurs est corrigé.

## Limitations / travail restant
* La cause de `NpcsCatalogTest` est établie par analyse et simulation, pas par une reproduction
  (probabilité ≈ 0,14 % par exécution).
* `DeedClaimListener` : si le joueur se débarrasse de l'Acte avant la fin de la création asynchrone,
  le claim est créé sans consommation (comportement antérieur, sans enjeu : un seul claim principal
  par joueur et l'Acte n'a pas d'autre valeur).

## Prochaine étape suggérée
Fusionner la PR #511 quand la CI est verte, puis surveiller les prochains runs CI. Le ticket reste
ouvert jusqu'au constat du propriétaire.
