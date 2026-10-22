# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-22
* Heure : 10:46
* Sujet : #675 (retour propriétaire du 22/10) — fermer par défaut les postes de travail du Hub, exceptions conditionnelles
* Statut : DONE (livré et déployé sur DEV ; validation en jeu TC-401 PENDING MANUAL VALIDATION)
* Branche Git : `feature/675-hub-stations` (PR #723, fusionnée dans `chore/257-ci-github-actions`, merge `9eec7812`) ; rapport : `docs/675-hub-stations-rapport`
* Commit actuel si disponible : `9eec7812`
* Début de la tâche : 2026-10-22 10:03:00
* Fin de la tâche : 2026-10-22 10:49:00
* Durée totale : 00:46:00

## Demande

Session lqorch sur #675. Retour propriétaire du 22/10 : dans le Hub, fermer **aussi par défaut** les postes
utilitaires (établis, fours, haut fourneau, fumoir) et inventorier/traiter les autres (alambic, enclume, meule,
forgeron, tailleur, métier à tisser, cartographie, enchantement, crafter…), avec la décision par station.
Conserver le lot du 21/10 (commerce et conteneurs). Exceptions explicites par type et/ou bloc précis, avec
condition d'accès, en réutilisant `PlayerConditionEvaluator` (#567) et la config hub (completer, jamais
d'upload). Message discret. Préserver PNJ/menus du plugin, coffres personnels #104/#105, coffre de l'Ender,
bypass admin en session. Wild et Claims inchangés. Documenter la révision de #107. Préparer les accès achetés
sans les implémenter, liste d'exceptions vide par défaut. Rien vidé ni modifié, aucune migration. Tests, CI,
déploiement DEV, registre, TC, changelog, commentaire ; ticket laissé ouvert.

## Analyse

- État réel vérifié : lot du 21/10 fusionné (`1549c345`, `HubResourceLockListener`) et en ligne ; aucun code
  postérieur sur #675. Le retour du 22/10 n'est présent que dans la note du superviseur (pas de commentaire
  GitHub du propriétaire après celui de lqorch).
- `PlayerConditionEvaluator` est asynchrone (état de quête non actif et variables lus en base) ; un clic de
  bloc doit être tranché sur le thread principal. Choix : si le verdict est déjà connu (permission, objet,
  niveau, quête active en mémoire, exception sans condition) le clic vanilla suit son cours ; sinon le clic est
  refusé puis le poste est **rouvert par le plugin** lié au bloc (`MenuType…builder().location().checkReachable(true)`,
  ou l'inventaire du bloc pour four/fumoir/haut fourneau/alambic) si le joueur est toujours à ≤ 6 blocs et n'a
  rien ouvert entre-temps. Erreur de lecture → refus.
- Le niveau RPG n'est pas dans l'évaluateur mais dans `RequiredLevelRule` (#532) : réutilisé comme les portails.
- Les types de conditions admis sont ceux des portails (`PortalAccess.ALLOWED_TYPES`), parsés par
  `DialogueConditionYaml` : aucun second système.

## Inventaire et décision par bloc (Hub uniquement)

| Bloc | Décision | Exception |
|---|---|---|
| Établi | Fermé | Oui |
| Four, haut fourneau, fumoir | Fermés | Oui |
| Alambic | Fermé | Oui |
| Enclume (+ ébréchée, endommagée) | Fermée | Oui |
| Meule | Fermée | Oui |
| Table de forgeron | Fermée | Oui |
| Tailleur de pierre | Fermé | Oui |
| Métier à tisser | Fermé | Oui |
| Table de cartographie | Fermée | Oui |
| Table d'enchantement | Fermée | Oui |
| Balise (changement d'effet de zone) | Fermée | Oui |
| Crafter | Fermé comme conteneur (lot du 21/10 : il stocke des objets) | Non |
| Coffres, tonneaux, shulkers, hoppers, distributeurs, droppers, wagonnets/bateaux à coffre, pots décorés, étagères | Fermés (lot du 21/10, inchangé) | Non |
| Composteur, jukebox, chaudrons (vide, eau, lave, neige poudreuse), pots de fleurs, coffre des épreuves | Fermés : ressource ou bloc qu'un seau modifierait | Non |
| Lutrin | Lecture libre, prise du livre refusée | — |
| Coffre de l'Ender | Libre (stockage personnel) | — |
| Coffres personnels liés #105 | Libres (`VaultAccessListener` en `LOWEST` annule le clic avant) | — |
| PNJ Citizens, boutiques #17, ateliers #18, menus RPGQuest | Libres (pas des blocs vanilla) | — |
| Portes, boutons, leviers, lits, cloches, panneaux | Inchangés | — |

Non couvert (signalé) : cadres et porte-armures (entités) du décor.

## Travail effectué

- `hub.HubStation` : catalogue des 13 postes (matériaux + ouverture liée au bloc) et `RESOURCE_BLOCKS`.
- `hub.HubStationException` + `hub.HubStationExceptionsYaml` : `hub.stations.exceptions` (`id`, `stations`,
  `blocks` x/y/z dans `hub.world`, `required-level`, `conditions`, `message`), validé au chargement (refus
  lisible : poste inconnu, bloc hors Hub, id dupliqué, condition non admise, bornes).
- `hub.HubStationLockListener` : `PlayerInteractEvent` (seul `useInteractedBlock` refusé, l'objet en main reste
  utilisable), deux mains gérées, une exception suffit, réouverture asynchrone unique, `PlayerTakeLecternBookEvent`.
- `hub.HubRefusalMessages` : cooldown de message (2 s) extrait de `HubResourceLockListener` et partagé.
- `HubConfig` + `ConfigValidator` : nouveau champ `stationExceptions` (constructeurs existants conservés).
- `config.yml` embarqué : `hub.stations.exceptions: []` commenté avec exemple.
- Bootstrap : un seul `PlayerConditionEvaluator` pour les portails et le Hub ; listener enregistré.
- **Révision de #107** (documentée HUB_SERVICES, Bible, docs-site) : #107 exemptait les établis (« utilisables
  par tout joueur ») et le lot du 21/10 laissait fours/alambics libres pour cette raison ; ils sont désormais
  fermés par défaut ; la salle de craft se rouvre par une exception. Conséquence : tant qu'aucune exception
  n'existe, les quêtes de craft/cuisson se font dans le Wild ou le claim.
- **Accès achetés (préparés, non implémentés)** : `id` = clé stable ; un futur achat (#336) ou droit à
  récompense (#422) posera une variable (ex. `HUB_STATION_<ID>=true`) exigée par `VARIABLE_EQUALS`.

## Fichiers créés

- `src/main/java/com/lodygames/rpgquest/hub/HubStation.java`
- `src/main/java/com/lodygames/rpgquest/hub/HubStationException.java`
- `src/main/java/com/lodygames/rpgquest/hub/HubStationExceptionsYaml.java`
- `src/main/java/com/lodygames/rpgquest/hub/HubStationLockListener.java`
- `src/main/java/com/lodygames/rpgquest/hub/HubRefusalMessages.java`
- `src/test/java/com/lodygames/rpgquest/hub/HubStationLockListenerTest.java`
- `src/test/java/com/lodygames/rpgquest/hub/HubStationExceptionsYamlTest.java`
- ce rapport

## Fichiers modifiés

- `hub/HubResourceLockListener.java` (messages partagés, Javadoc), `config/HubConfig.java`,
  `config/ConfigValidator.java`, `bootstrap/RPGQuestBootstrap.java`, `src/main/resources/config.yml`
- `docs/HUB_SERVICES.md`, `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/PERMISSIONS.md`,
  `docs-site/hub-safe-zone.html`, `docs/MANUAL_TEST_PLAN.md` (TC-401, TC-397 étape 5 ajustée),
  `docs/deployment/SERVER_CHANGELOG.md`, `docs/SUIVI_VALIDATIONS_DEV.md` (ligne 0j, 0i ajustée, dépendance #675),
  `docs/claude-reports/README.md`

## Base de données / migrations

Aucune. Aucune donnée joueur lue en écriture.

## Configuration / données

`hub.stations.exceptions: []` ajouté au `config.yml` DEV par `ConfigFileCompleter` au redémarrage — relu après
redémarrage par téléchargement FTP (lignes 182–183), copie locale supprimée ensuite. Aucune exception créée.

## Tests automatiques

- Ciblés (local, `RPGQUEST_TEST_MAX_HEAP=768m`) : `hub.*`, `storage.*`, `item.*`, `*ConfigValidator*`,
  `*ConfigFileCompleter*` : **283 tests, 0 échec** (`HubStationLockListenerTest` 10/10,
  `HubStationExceptionsYamlTest` 4/4, `HubResourceLockListenerTest` inchangé et vert).
- CI PR #723 verte (plugin, control-panel, web-api, scripts, artefacts) ; CI du merge `9eec7812` verte.
- Non couvert automatiquement : ouverture réelle du menu par le serveur après un verdict asynchrone (MockBukkit).

## Tests manuels à effectuer

TC-401 — PENDING MANUAL VALIDATION (aucun test en jeu fait) : postes du Hub fermés, blocs-ressources, lutrin,
non-régression coffre personnel / Ender / PNJ, Wild/Claims inchangés, bypass, exception d'essai facultative,
redémarrage. TC-397 reste à tester (étape 5 ajustée).

## Résultat attendu

Au Hub, sans bypass, aucun poste de travail vanilla ne s'ouvre (message gris discret) ; rien n'est modifié ;
une exception déclarée rouvre exactement ce qu'elle vise pour qui remplit ses conditions.

## Reset / retour à l'état initial

Rollback JAR (ci-dessous). Ouvrir tous les postes sans rollback : une exception `{ id: tout }` sans `stations`
ni `blocks` dans `hub.stations.exceptions` puis `/rpgadmin reload`.

## Déploiement VeryGames

- Effectué le 22/10 10:44 : JAR `9eec7812` via `scripts/deploy-verygames.sh -y` (DEPLOY_EXIT=0), 4226840 o,
  SHA-256 `2db4d08359d3f2d7…` retéléchargé identique. Sauvegarde : `rpgquest-20261022T084430Z-predeploy.jar`
  (4205779 o).
- Redémarrage requis (nouveau listener) : `scripts/verygames-restart.sh`, seul LoDyMcFly connecté (règle
  restart-with-owner-online) ; serveur de nouveau ONLINE, RPGQuest actif dans `plugins`.
- Migration : aucune. Ne PAS téléverser `config.yml`, ne vider aucun conteneur.
- Incident mineur : un appel `verygames-rcon.py --help` a envoyé `--help` comme commande console (rejetée
  « Unknown command », sans effet).
- Rollback : `scripts/rollback-verygames.sh --latest` (ou remettre le `-predeploy` ci-dessus) puis redémarrer ;
  la clé `hub.stations` est ignorée par l'ancien JAR.

## Points d'attention

- La salle de craft du Hub (#107) est **fermée** tant que le propriétaire n'a pas déclaré d'exception.
- Cadres / porte-armures du décor non protégés (hors périmètre).
