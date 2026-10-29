# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-29
* Heure : 21:39
* Sujet : #917 — croissance hors ligne des pousses d'arbres dans les claims (phase 2 de #776)
* Statut : DONE (livré dans la branche d'intégration ; **non déployé** — autorisation propriétaire attendue ; recette TC-447 à faire)
* Branche Git : `feature/917-croissance-pousses-arbres` (PR #925, fusionnée dans `chore/257-ci-github-actions` : merge `ce1eb7b7`)
* Commit actuel si disponible : `ce1eb7b7` (merge PR #925 ; code `677228f4`)
* Début de la tâche : 2026-10-29 21:16:55
* Fin de la tâche : 2026-10-29 21:39:44
* Durée totale : 00:22:49

## Demande
Ticket #917 (session autonome lqorch dde8877d) : rattraper la croissance des pousses d'arbres au rechargement
d'un chunk de claim, sans chunk chargé en permanence ; respecter lumière, espace, sol, essences ; empêcher
destruction de constructions, débordement de claim, double pousse ; même politique temporelle que #776 (temps
de fonctionnement du serveur) ; lots, plafonds, idempotence ; tests sur plusieurs essences et bords ; recette
manuelle courte ; aucune pousse artificielle sur DEV sans accord.

## Analyse
- #776 (`claim.growth.OfflineGrowthService` + `PlantGrowthSimulator`) fournit déjà la marque PDC
  `rpgquest:offline_growth_since` consommée une fois, la file par chunk, le budget `blocks-per-tick`, le plafond
  `max-catch-up-minutes` et le temps de jeu (`getGameTime`). Il suffit d'ajouter les pousses comme « plantes ».
- Vanilla (`SaplingBlock`) : random tick, lumière locale ≥ 9 au-dessus (le ciel s'assombrit la nuit), 1/7 →
  stade 0 → 1, puis tentative d'arbre (`TreeGrower` : variantes, carrés 2×2, chêne noir / pâle 2×2 seulement).
- Paper `RegionAccessor#generateTree(Location, Random, TreeType, Predicate<BlockState>)` calcule l'arbre dans
  une liste tampon puis n'applique que les blocs acceptés par le prédicat : prédicat qui refuse tout = calcul
  **à blanc**. Le générateur lit le monde réel (y compris hors claim) : il pourrait charger un chunk voisin.
- MockBukkit ne génère pas d'arbres : la génération doit être injectable.

## Travail effectué
- `TreePlanner` (interface) : génération à blanc ; `TreePlanner.paper()` = générateur vanilla via le prédicat.
- `TreeGrowthSimulator` (appelé par `PlantGrowthSimulator.simulate`) :
  - succès = random ticks × part de lumière / 7 (lumière de blocs ≥ 9 → 1 ; sinon part du jour où le ciel
    assombri reste ≥ 9, calculée avec la formule vanilla par temps clair, ≈ 0,6 en plein ciel) ;
  - 1er succès : stade 1 ; suivants : au plus 4 tentatives d'arbre ;
  - essences `TreeGrower` (chêne 10 % grand, sapin 2×2 grand sapin/grand pin, jungle 2×2, acacia, cerisier,
    bouleau, chêne noir/pâle seulement en 2×2, palétuvier 85 % grand) ; 2×2 cherché dans l'ordre vanilla,
    origine au coin nord-ouest ; propagule suspendue = âge seulement ;
  - **tout ou rien** : générateur appelé seulement si les chunks à 16 blocs sont chargés ; pousses retirées
    (eau pour une propagule immergée) pendant le calcul ; arbre refusé si un bloc sort du claim, tombe dans un
    chunk non chargé ou remplacerait autre chose qu'air / feuillage / pousse / `replaceable_by_trees` / terre
    (par de la terre seulement) / sols des racines de palétuvier (par des racines seulement) ;
    `StructureGrowEvent` (non bonemeal, joueur nul) puis revalidation ; sinon pousses remises à l'identique.
- `PlantGrowthSimulator` : pousses ajoutées à `PLANTS`, délégation, `cost(type)` (une pousse = 32 du budget),
  `successes` statique partagé, nouveau constructeur avec `TreePlanner` (l'ancien utilise `paper()`, câblage du
  bootstrap inchangé).
- `OfflineGrowthService` : budget décompté par `PlantGrowthSimulator.cost`.
- Docs : `docs/CLAIMS.md` (barème, politique, limites), `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`,
  `docs-site/worlds.html`, `docs/MANUAL_TEST_PLAN.md` (TC-447), `docs/deployment/SERVER_CHANGELOG.md`
  (entrée « non déployé »), registre `docs/SUIVI_VALIDATIONS_DEV.md` (PR de rapport).

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/claim/growth/TreePlanner.java`
- `src/main/java/com/lodygames/rpgquest/claim/growth/TreeGrowthSimulator.java`
- `src/test/java/com/lodygames/rpgquest/claim/growth/TreeGrowthSimulatorTest.java`
- ce rapport

## Fichiers modifiés
- `src/main/java/com/lodygames/rpgquest/claim/growth/PlantGrowthSimulator.java`
- `src/main/java/com/lodygames/rpgquest/claim/growth/OfflineGrowthService.java`
- `src/test/java/com/lodygames/rpgquest/claim/growth/OfflineGrowthServiceTest.java`
- `docs/CLAIMS.md`, `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs-site/worlds.html`,
  `docs/MANUAL_TEST_PLAN.md`, `docs/deployment/SERVER_CHANGELOG.md`, `docs/SUIVI_VALIDATIONS_DEV.md`,
  `docs/claude-reports/README.md`

## Base de données / migrations
Aucune. Persistance inchangée : la marque PDC du chunk de #776.

## Configuration / données
Aucune nouvelle clé : `claims.offline-growth` (#776) s'applique aussi aux pousses (`enabled: false` les coupe).
Aucune donnée joueur, aucun monde modifié par cette session ; aucune pousse posée sur DEV.

## Tests automatiques
- Local (ciblé, `RPGQUEST_TEST_MAX_HEAP=768m`) : `claim.growth.*` — `TreeGrowthSimulatorTest` 14/14,
  `PlantGrowthSimulatorTest` 11/11, `OfflineGrowthServiceTest` 10/10 ; `ConfigValidatorTest` 62/62.
- CI GitHub PR #925 : verte (Tests plugin, control-panel, web-api, Scripts, Artefacts, CI OK — deux exécutions)
- `TreeGrowthSimulatorTest` couvre : chêne via `StructureGrowEvent`, 5 essences → `TreeType`, 2×2 sapin et
  jungle depuis le coin nord-ouest, chêne noir isolé / en carré, arbre débordant du claim refusé (pousse
  remise, rien posé), coffre jamais remplacé, herbe et feuillage remplacés, événement annulé + 4 tentatives
  max, chunk voisin non chargé → aucun appel du générateur, obscurité / lumière de blocs / part du jour, un
  succès = stade seul, pas de second arbre, politique de remplacement, propagules. `OfflineGrowthServiceTest`
  : une pousse dans un chunk rechargé fait un seul arbre, même après un second déchargement/rechargement.
- Limite : le générateur vanilla réel n'est pas exécutable sous MockBukkit (arbre factice) — forme réelle
  des arbres vérifiable seulement en jeu.

## Tests manuels à effectuer
TC-447 — PENDING MANUAL VALIDATION (après déploiement autorisé, dans le claim du propriétaire, avec son accord).

## Résultat attendu
Pousses dégagées d'un claim devenues arbres au retour (≈ 1 chance sur 2 en 30 min au soleil), jamais d'arbre
débordant du claim ni de construction remplacée, un seul arbre par pousse / carré, rien de nouveau au retour
immédiat.

## Reset / retour à l'état initial
Rollback du JAR suffit (aucune donnée). Les arbres poussés restent des blocs ordinaires.

## Déploiement VeryGames
**Non effectué.** Raisons : (1) le ticket conditionne la recette à « déploiement et autorisation » et interdit
toute pousse artificielle sans accord — or déployer fait pousser les pousses déjà présentes dans les claims de
DEV ; (2) la branche d'intégration contient aussi les JAR de #734 (TC-443) et #903 (TC-441), **explicitement
en attente d'une décision de déploiement** (registre) : un JAR construit depuis sa tête les embarquerait.
À transférer après accord : JAR RPGQuest construit depuis la tête de `chore/257-ci-github-actions` (contient
#917 + #734 + #903), via `scripts/deploy-verygames.sh -y` (après CI verte du commit), puis
`scripts/verygames-restart.sh`. Ne PAS altérer : `data.db`, `config.yml` (aucune clé nouvelle), mondes.
Redémarrage requis ; aucune migration. Rollback : JAR précédent `002facdb` (backup du script).

## Points d'attention
- Une pousse au bord du champ de vision (chunks voisins pas encore chargés à son tour) ne tente rien et perd
  ce rattrapage (jamais de chargement forcé).
- Arbre « tout ou rien » : une pousse à moins de ~3 blocs du bord (6 pour un 2×2) ne poussera jamais hors
  ligne ; elle pousse en jeu normalement quand le joueur est présent (comportement vanilla, hors RPGQuest).
- Pas de ruches/abeilles, pas de météo dans la part du jour, 2×2 un peu plus lent qu'en jeu.
