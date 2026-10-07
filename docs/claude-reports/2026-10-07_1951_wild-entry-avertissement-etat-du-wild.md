# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-07
* Heure : 19:51 (heure locale)
* Sujet : Entrée dans le Wild — avertissement de danger générique, retour d'attente et diagnostic de latence (#161) ; état réel du Wild demandé au Garde (#24) ; partie B de #26 livrée sans aucune inspection d'inventaire
* Statut : DONE (livraison complète et déployée ; les trois tickets restent ouverts — voir « Limitations / travail restant »)
* Branche Git : `feature/161-wild-entry-ux` (créée depuis `origin/feature/169-special-mobs-boss`, poussée)
* Commit actuel si disponible : `4f9af35`
* Début de la tâche : 2026-10-07 18:32:11
* Fin de la tâche : 2026-10-07 19:51:00
* Durée totale : 01:18:49

## Demande

Travailler sur les tickets **#161**, **#26** et **#24**, qui se recouvrent partiellement, avec pour
objectif de réduire le backlog et de clôturer les trois si tous les critères retenus sont validés.
Les décisions fonctionnelles suivantes ont été données explicitement en conversation et prévalent
sur le texte d'origine des tickets :

**#161 — passage Hub → Wild** (devient la référence de l'UX d'entrée dans le Wild) : avertissement
clair avant la téléportation (texte imposé mot pour mot), plus une phrase indiquant que « Le Garde
peut vous renseigner sur les conditions actuelles du Wild. ». Conserver une action explicite
« Entrer dans le Wild », une action « Annuler », la fermeture du menu valant annulation, une option
persistante « Ne plus afficher cet avertissement » mémorisée **seulement** sur une confirmation
réelle de départ, l'absence de réouverture immédiate tant que le joueur reste dans le portail, et la
visibilité du reste du parcours même avertissement masqué. Afficher immédiatement après confirmation
« Recherche d'un point d'arrivée sûr… Téléportation en préparation. », puis un retour de réussite ou
d'échec compréhensible. Préserver RANDOM_SAFE, les contrôles de sécurité, le délai de grâce, une
seule téléportation/recherche en cours par joueur, la gestion propre de la déconnexion/annulation/
erreur/timeout, et l'absence de téléportation tardive parasite. Mesurer si nécessaire la latence des
étapes principales (recherche du point sûr, génération/chargement des chunks, téléportation) sans
jamais supprimer un contrôle de sécurité pour accélérer artificiellement le passage.

**#26 — partie A** : ne pas la refaire (kit de départ demandé au Guide, déjà validé manuellement en
jeu) ; seulement vérifier qu'aucune modification de ce chantier ne la casse. **Partie B** : la
conception initiale est **modifiée** — ne surtout pas analyser l'inventaire du joueur, donc retirer
du périmètre toute logique de nourriture disponible, présence d'une arme, outils, Rune de rappel,
kit déjà récupéré, gear score ou estimation de préparation. Le passage vers le Wild doit simplement
afficher l'avertissement générique de danger prévu dans #161, et la partie B est considérée comme
satisfaite par l'implémentation de #161. Mettre à jour toute documentation ou tout test de #26 qui
imposerait encore une inspection d'inventaire.

**#24 — état du Wild** : l'information ne doit **pas** être injectée automatiquement dans
l'avertissement du portail. Le joueur doit pouvoir demander l'état actuel du Wild au Garde depuis
son dialogue, via un choix permanent (« Comment est le Wild actuellement ? »). La réponse doit être
dynamique et provenir de l'état réel du monde Wild, en indiquant au minimum jour ou nuit et la météo
globale (clair, pluie, orage). Ne pas essayer de prédire la météo du biome d'arrivée : annoncer
l'état global du monde. Obtenir l'information sans commande. Ne modifier ni l'heure, ni la météo, ni
le cycle jour/nuit. Prévoir une implémentation assez propre pour enrichir plus tard (événements,
niveau de danger) sans développer ces fonctions maintenant.

**Interaction #161 / #24** : dans l'avertissement du portail, ajouter uniquement la phrase renvoyant
au Garde — jamais jour/nuit/météo directement. Le Garde est la source volontaire d'information.

Gouvernance accordée : coder, tester, commit, push, déployer sur DEV, redémarrer le serveur si
nécessaire pour la validation de ce lot. Préserver tous les fichiers locaux non suivis, ne toucher à
aucun contenu hors périmètre, **ne pas merger**. À la fin : fournir les commits et le résultat exact
des tests, distinguer ce qui est validé automatiquement de ce qui reste manuel, mettre à jour la
documentation et le plan de recette, puis fermer #161, #26 et #24 si tous les critères retenus sont
remplis — sinon expliquer précisément pourquoi chacun reste ouvert.

## Analyse

### État réel avant l'intervention (audit Git + lecture du code)

- La branche de travail de la session précédente était `feat/control-panel-admin-tools` ; l'audit a
  confirmé qu'elle est **déjà intégrée** dans `feature/169-special-mobs-boss`
  (`git merge-base --is-ancestor` vrai, merge `dfe642b`). La branche d'intégration réelle du moment
  est donc `feature/169-special-mobs-boss` (`21fea61`), **pas** celle mentionnée dans `CLAUDE.md`
  (`feature/23-mod-prototype`) : la nouvelle branche a été créée depuis elle, et c'est important ici
  parce que son `dialogues/guard.yml` contient 127 lignes de plus (chaîne de paliers du Garde,
  issue #179) que la version de la branche précédente.
- **#26 partie A existe et fonctionne** : `ActionType.GIVE_STARTER_KIT` + `GiveStarterKitAction` +
  `player.StarterToolKitService` (droit persistant `STARTER_TOOL_KIT_AVAILABLE`, remise tout ou rien,
  anti double-clic), câblés dans `dialogues/guide.yml`. Rien de ce chantier n'y touche.
- **#26 partie B existait déjà, mais exactement sous la forme désormais rejetée** :
  `travel.WildEntryWarningService` **lisait l'inventaire** (`player.getInventory().getContents()`)
  à la recherche d'une Rune de rappel et n'avertissait que les joueurs qui n'en avaient pas, avec
  deux boutons `[Continuer]`/`[Annuler]` dans le chat, un anti-spam de 4 s et un laissez-passer à
  usage unique. Ce n'était donc pas « rien à faire » mais **un comportement à retirer**, documenté
  dans `docs/TRAVEL.md`, `docs/current_state.md` et testé par `WildEntryWarningServiceTest`.
- Aucun retour visuel n'existait pendant l'attente, et `WorldPortalTeleportListener#teleport`
  envoyait « Téléportation réussie. » **même quand `Player#teleport` renvoyait `false`** — un faux
  succès, incompatible avec le « retour compréhensible de réussite ou d'échec » demandé.
- La recherche de point sûr (`RandomSafeLocationFinder`) est **synchrone sur le thread principal** et
  appelle `World#getChunkAt` à chaque tentative : c'est le candidat n°1 pour la latence signalée, mais
  rien ne le mesurait.
- Le moteur de dialogue ne savait afficher que du **texte statique** (`node.text().base()` passé
  directement à `MiniMessage` par les deux renderers), donc #24 n'était pas réalisable sans un
  mécanisme de valeur dynamique.
- Le Hub est volontairement figé en plein jour (`hub.HubWorldRulesService` écrit `setTime`/`setStorm`
  sur le **Hub**), ce qui confirme la prémisse de #24 : un joueur du Hub ne peut pas deviner l'heure
  du Wild.

### Décisions techniques prises

1. **Avertissement générique, pas de garde conditionnel.** `allowEntry` renvoie désormais
   **toujours** `false` pour un portail vers le Wild et le service devient propriétaire de la suite.
   Raison : la préférence « ne plus afficher » vit en base (SQLite asynchrone, aucun accès bloquant
   sur le thread principal), donc **aucune** décision ne peut être rendue de façon synchrone. Le
   contrat de `WorldPortalEntryGuard` prévoyait déjà ce cas (« à charge de l'implémentation de
   relancer elle-même la téléportation »), et `ClaimWorldAccessGuard` fonctionne déjà ainsi.
2. **Option « ne plus afficher » en variable joueur**, clé `WILD_ENTRY_WARNING_HIDDEN` dans la table
   existante `player_variables` : **aucune migration de schéma**, et `/rpgadmin player resetnew`
   rétablit l'avertissement sans une ligne de code dédiée (il efface déjà toutes les variables).
   Même choix que `STARTER_TOOL_KIT_AVAILABLE` pour #26 partie A.
3. **Trois boutons plutôt qu'une case à cocher.** Le ticket demande une option persistante mémorisée
   seulement avec une confirmation explicite du départ. Une case à cocher aurait exigé un
   `DialogInput` Paper (API expérimentale supplémentaire) et aurait laissé ambigu le cas « coche la
   case puis annule ». Avec « Entrer dans le Wild » / « Entrer et ne plus afficher cet
   avertissement » / « Annuler », la mémorisation est **structurellement** impossible sans départ
   réel : c'est le même bouton qui fait les deux.
4. **Fermer la fenêtre = annuler, sans code de fermeture.** L'API Dialog de Paper n'expose aucun
   rappel de fermeture. Plutôt que de simuler une détection, aucun bouton n'est « par défaut » et
   aucune action n'est exécutée sans clic : fermer n'a donc littéralement aucun effet, ce qui est
   exactement l'annulation demandée (`canCloseWithEscape(true)`).
5. **Pas de minuteur anti-spam.** L'anti-boucle déjà présente dans `WorldPortalTeleportListener`
   (`currentPortalByPlayer`, mis à jour **avant** la consultation des gardes) garantit qu'un joueur
   restant dans la zone ne provoque plus aucune consultation. L'ancien cooldown de 4 s était donc
   redondant **et** nuisible : il empêchait aussi de ressortir et rentrer volontairement. Il a été
   supprimé, et un test du listener verrouille la règle.
6. **Un seul jeton `inFlight` par joueur**, couvrant la lecture de la préférence **et** le départ,
   relâché dès l'affichage de l'avertissement. Raison : si le jeton survivait à l'affichage, une
   fermeture silencieuse (non détectable, cf. point 4) bloquerait définitivement l'accès au Wild
   jusqu'à la reconnexion.
7. **Départ au tick suivant** (`runTask`) plutôt que dans la foulée : le message d'attente doit être
   parti **avant** que la recherche de point sûr (qui peut générer des chunks sur le thread
   principal) ne commence. Le `finally` relâche toujours le jeton, et le joueur est **relu** avant la
   téléportation — un joueur déconnecté entre-temps n'est jamais téléporté (aucune TP tardive).
8. **Présentation calquée sur `dialogue.render`** : `WildEntryPromptPresenter` +
   `PaperDialogWildEntryPromptPresenter` + `ChatWildEntryPromptPresenter` +
   `FallbackWildEntryPromptPresenter`, pilotés par la préférence serveur existante
   `dialogue.renderer`. Un serveur qui a choisi `chat` pour éviter l'API expérimentale ne la voit
   pas réapparaître par cette porte, et un échec de la fenêtre Paper ne laisse jamais le joueur sans
   moyen de confirmer (sinon le portail paraîtrait cassé).
9. **#24 par substitution dans le texte du nœud**, pas par une nouvelle action de dialogue. Une
   action aurait fermé le dialogue pour écrire dans le chat ; `%wild_conditions%` laisse le Garde
   « parler » et conserve le fil (« Merci pour l'information » → retour à l'accueil). La convention
   `%clé%` est celle déjà utilisée par `RUN_SAFE_COMMAND` (`%player%`) ; une balise MiniMessage
   aurait été soit validée soit affichée littéralement par le parseur.
10. **Jour/nuit indépendant de la météo.** `World#isDayTime()` tient compte de l'orage (un orage de
    midi y est « la nuit »), ce qui rendrait la phrase du Garde contradictoire avec l'heure réelle.
    Le service lit `World#getTime()` et applique les bornes historiques 12300/23850, et rapporte la
    météo comme un **second** fait distinct.
11. **Latence mesurée, jamais « optimisée » à l'aveugle.** `RandomSafeLocationFinder.SearchMetrics`
    (accumulateur possédé par l'appelant, pas d'état d'instance) sépare le temps de `getChunkAt` du
    temps d'évaluation, et `TpTraceLogger.logLatency` émet une ligne `[TP-LATENCY]` par passage.
    Aucun contrôle de sécurité n'a été touché : la mesure servira à décider **sur données réelles**
    s'il faut agir et où. C'est le point du ticket qui reste à confirmer en jeu.

### Ce qui a été volontairement **supprimé**

La lecture d'inventaire de `WildEntryWarningService` (recherche d'une Rune de rappel via
`YamlCustomItemRegistry#identify`), le cooldown anti-spam de 4 s, le laissez-passer `bypass` à usage
unique, les boutons `[Continuer]`/`[Annuler]` et le message « Vous partez sans moyen de rappel ».
Les tests correspondants (joueur avec/sans Rune, anti-spam, bypass à usage unique) ont été remplacés,
pas désactivés. `docs/TRAVEL.md` et `docs/current_state.md` ne décrivent plus ce comportement.

## Travail effectué

### #161 — avertissement et retour d'attente avant le Wild

`travel.WildEntryWarningService` **réécrit** (242 lignes modifiées) :

- `allowEntry` ne concerne que les portails dont `destinationWorld` vaut `travel.wild-world` ; tout
  autre portail passe sans contrôle (inchangé). Pour le Wild, le passage immédiat est **toujours**
  refusé et le service prend la main.
- Lecture asynchrone de la préférence `WILD_ENTRY_WARNING_HIDDEN` via `PlayerVariableRepository`
  (jamais d'accès SQL sur le thread principal), reprise sur le thread principal. Erreur de lecture →
  l'avertissement est affiché **par défaut** (dégradation sûre : on n'envoie jamais un joueur dans le
  Wild en silence à cause d'une panne de base).
- Avertissement : titre « ⚠ Entrée dans le Wild », corps en deux lignes (texte de danger imposé par
  le ticket, puis renvoi vers le Garde), trois boutons.
- `onConfirm(playerId, portal, hideFutureWarnings)` : relit le joueur en ligne, prend le jeton
  `inFlight`, écrit la préférence **seulement** si le bouton « … et ne plus afficher … » a été
  utilisé, puis lance le départ.
- `onCancel(playerId)` : « Vous restez au Hub. », rien d'autre.
- `beginTeleport` : message d'attente immédiat, puis `runTask` → relecture du joueur →
  `teleporter.teleportNow(...)`, le jeton étant relâché dans un `finally`.
- `onQuit` (le service est désormais un `Listener`, enregistré via `PlayerListenerService`) relâche
  un jeton resté en vol.

`travel.WorldPortalTeleportListener` :

- Mesure des trois étapes et émission de `[TP-LATENCY]`.
- **Correction d'un faux succès** : « Téléportation réussie. » n'est plus envoyé quand
  `Player#teleport` renvoie `false` ; un message d'échec explicite le remplace.
- `resolveRandomSafeLocation` prend un accumulateur de métriques. Aucune règle de sécurité modifiée.

`travel.RandomSafeLocationFinder` : nouvelle classe imbriquée `SearchMetrics` (tentatives + temps
`getChunkAt` cumulé) et surcharge `find(world, center, metrics)`. `find(world, center)` conserve sa
signature et son comportement ; `findAtColumn`/`findAccessibleColumn` ne sont pas touchés.

`travel.TpTraceLogger` : `logLatency(...)` + `toMillis(...)`, format `[TP-LATENCY]` distinct de
l'instrumentation temporaire `[TP-TRACE]`.

Présentation : `WildEntryPrompt`, `WildEntryPromptButton`, `WildEntryPromptPresenter`,
`ChatWildEntryPromptPresenter`, `PaperDialogWildEntryPromptPresenter`,
`FallbackWildEntryPromptPresenter` — exactement le patron de `dialogue.render`.

`bootstrap.RPGQuestBootstrap` : le garde reçoit désormais `variableRepository` (plus
`customItemRegistry`, devenu inutile) et un présentateur choisi par `createWildEntryPromptPresenter()`
selon `dialogue.renderer` ; le service est enregistré comme écouteur.

### #24 — état réel du Wild demandé au Garde

- `travel.model.WildWeather` (CLEAR/RAIN/THUNDER, chacun portant sa phrase française) et
  `travel.model.WildConditions` (`daytime`, `weather`, `description()`).
- `travel.WildConditionsService` : `current()` / `describe()`, monde résolu à chaque appel via
  `worldService::find`, aucune écriture.
- `dialogue.DialogueTextPlaceholders` : substitution `%clé%` en une passe, clé inconnue conservée,
  toutes les langues de `LocalizedText` traitées, nœud sans `%` renvoyé tel quel.
- `dialogue.session.DialogueSessionEngine` : `setPlaceholders(...)` (même patron que `setRenderer`) et
  substitution dans `openNode`, **juste avant** le rendu, sur le thread principal.
- `src/main/resources/dialogues/guard.yml` : choix permanent « Comment est le Wild actuellement ? »
  au nœud d'accueil + nœud `wild_conditions` (`<white>%wild_conditions%</white>`) avec retour à
  l'accueil.
- Câblage dans le bootstrap : `Map.of("wild_conditions", player -> wildConditionsService.describe())`.

### #26 — ce qui a été fait et ce qui ne l'a pas été

- **Partie A : pas touchée.** Aucun fichier de `player.StarterToolKitService`, `GiveStarterKitAction`,
  `ActionType` ou `dialogues/guide.yml` n'a été modifié (vérifiable sur le diff). Sa non-régression
  est couverte par `StarterToolKitServiceTest`, réexécuté vert.
- **Partie B : livrée sous sa forme révisée**, c'est-à-dire par l'avertissement générique de #161.
  La logique d'inspection d'inventaire qui existait déjà a été **supprimée**, et la documentation qui
  la décrivait (`docs/TRAVEL.md`, `docs/current_state.md`) a été réécrite. Aucun test n'impose plus
  d'inspection d'inventaire : les trois tests qui en dépendaient (joueur avec Rune, joueur sans Rune,
  anti-spam) ont été remplacés, et un test vérifie explicitement qu'un joueur à l'**inventaire vide**
  reçoit le même avertissement que les autres.

## Fichiers créés

| Fichier | Rôle |
|---|---|
| `src/main/java/com/lodygames/rpgquest/travel/model/WildWeather.java` | Météo globale du Wild (3 états + phrase). |
| `src/main/java/com/lodygames/rpgquest/travel/model/WildConditions.java` | Relevé instantané (jour/nuit + météo) et sa description. |
| `src/main/java/com/lodygames/rpgquest/travel/WildConditionsService.java` | Lecture seule de l'état réel du monde d'exploration. |
| `src/main/java/com/lodygames/rpgquest/dialogue/DialogueTextPlaceholders.java` | Substitution `%clé%` dans le texte d'un nœud. |
| `src/main/java/com/lodygames/rpgquest/travel/WildEntryPrompt.java` | Contenu de l'avertissement, indépendant de l'affichage. |
| `src/main/java/com/lodygames/rpgquest/travel/WildEntryPromptButton.java` | Libellé + action d'un bouton. |
| `src/main/java/com/lodygames/rpgquest/travel/WildEntryPromptPresenter.java` | Abstraction d'affichage. |
| `src/main/java/com/lodygames/rpgquest/travel/ChatWildEntryPromptPresenter.java` | Repli stable (chat cliquable). |
| `src/main/java/com/lodygames/rpgquest/travel/PaperDialogWildEntryPromptPresenter.java` | Fenêtre Paper native. |
| `src/main/java/com/lodygames/rpgquest/travel/FallbackWildEntryPromptPresenter.java` | Repli automatique Paper → chat. |
| `src/test/java/com/lodygames/rpgquest/travel/WildConditionsServiceTest.java` | 9 tests #24. |
| `src/test/java/com/lodygames/rpgquest/dialogue/DialogueTextPlaceholdersTest.java` | 6 tests de substitution. |

## Fichiers modifiés

| Fichier | Changement |
|---|---|
| `travel/WildEntryWarningService.java` | Réécriture complète (voir ci-dessus). |
| `travel/WorldPortalTeleportListener.java` | Mesure de latence, message d'échec réel. |
| `travel/RandomSafeLocationFinder.java` | `SearchMetrics` + surcharge `find`. |
| `travel/TpTraceLogger.java` | `logLatency` / `toMillis`. |
| `claim/ClaimWorldAccessGuard.java` | Javadoc : le bouton s'appelle « Entrer dans le Wild ». |
| `dialogue/session/DialogueSessionEngine.java` | `setPlaceholders` + substitution au rendu. |
| `bootstrap/RPGQuestBootstrap.java` | Câblage du garde, du présentateur et des placeholders. |
| `src/main/resources/dialogues/guard.yml` | Choix permanent + nœud `wild_conditions`. |
| 6 fichiers de tests | Nouveaux cas (voir « Tests automatiques »). |
| `docs/TRAVEL.md`, `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md`, `docs-site/dialogues.html`, `docs-site/worlds.html` | Documentation. |

## Base de données / migrations

**Aucune migration.** La préférence « ne plus afficher » réutilise la table existante
`player_variables` (clé `WILD_ENTRY_WARNING_HIDDEN`), comme `STARTER_TOOL_KIT_AVAILABLE` pour #26
partie A. Le schéma reste à sa version actuelle ; un serveur déjà déployé n'a rien à migrer, et un
retour arrière ne laisse derrière lui qu'une ligne inerte dans `player_variables`.

## Configuration / données

- **Aucune nouvelle clé de configuration.** Le choix d'affichage réutilise `dialogue.renderer`.
- **Une donnée livrée modifiée** : `dialogues/guard.yml` (choix permanent + nœud de réponse).
  ⚠️ Un redéploiement de JAR ne met **jamais** à jour un `guard.yml` déjà présent sur le serveur
  (les exemples embarqués ne sont semés que s'ils manquent) : ce fichier doit être transféré
  explicitement, ce qui a été fait ici via `--also`.

## Tests automatiques

Exécutés dans un **worktree Git propre** (les fichiers de contenu non suivis du propriétaire —
`crystal_hunt.yml` modifié, `jeff_skeleton.yml`, `lily_pumpkin.yml`, `st0_meet_people.yml`,
`lily_memories.yml` — font échouer `CrystalHuntIntegrationTest` et bloquent le script de
déploiement ; ils n'ont jamais été commités ni modifiés).

`./gradlew test` — **BUILD SUCCESSFUL en 28 min 32 s** (comptages relus dans les XML JUnit) :

| Module | Tests | Échecs | Erreurs | Ignorés |
|---|---|---|---|---|
| plugin (racine) | 1801 | **0** | 0 | 37 |
| control-panel | 722 | **0** | 0 | 1 |
| web-api | 30 | **0** | 0 | 0 |

`./gradlew build` — **BUILD SUCCESSFUL**, JAR produit (1 935 862 o). Vérifié ensuite que
`:compileJava`, `:test` et `:jar` sont **à jour** sur le commit déployé `4f9af35` : les résultats
verts ci-dessus correspondent donc exactement au bytecode livré (le dernier commit ne modifie qu'un
commentaire Javadoc, absent du bytecode, d'où un `:test` resté à jour).

Les 37 tests ignorés du plugin et le 1 du panel sont les limitations MockBukkit déjà documentées
dans les sessions précédentes (notamment `teleportAsync`), **pas** des tests de ce lot.

### Ce qui est couvert, par critère du ticket

**#161** — `WildEntryWarningServiceTest` (réécrit, 12 tests) :

| Critère | Test |
|---|---|
| Première entrée → avertissement complet | `theFirstEntryWarnsWithoutInspectingTheInventoryAndNeverTeleports` — inventaire **vidé** avant l'appel, texte des trois actions et des deux lignes de corps vérifié mot pour mot |
| Aucune TP avant confirmation | même test (compteur de téléportation à 0 après plusieurs ticks) |
| Annuler → reste au Hub | `cancellingKeepsThePlayerAtTheHub` (message + 0 TP + préférence absente) |
| Fermer le menu → reste au Hub | `closingTheMenuWithoutChoosingKeepsThePlayerAtTheHub` (aucune action exécutée, aucun message de préparation, jeton relâché) |
| Pas de réouverture en boucle dans le portail | `WorldPortalTeleportListenerTest#aRefusingEntryGuardIsNotConsultedAgainWhileThePlayerStaysInsideTheSameZone` (1 consultation en restant dedans, 2 après sortie/entrée) |
| Confirmation → préparation immédiate | `confirmingSendsThePreparationFeedbackImmediatelyThenTeleports` (message lu **avant** tout tick) |
| TP sûre | même test (portail d'origine conservé) + tests RANDOM_SAFE existants inchangés |
| « Ne plus afficher » persistante | `neverShowAgainIsStoredOnlyWithARealDeparture` + `theStoredPreferenceSurvivesARestart` (service neuf qui relit la base) |
| Préparation visible même avertissement masqué | `aHiddenWarningTeleportsDirectlyButStillShowsThePreparation` |
| Single-flight par joueur | `onlyOneRequestIsInFlightPerPlayer` (3 entrées → 1 avertissement) et `repeatedConfirmationsNeverStartTwoDepartures` (double-clic → 1 TP) |
| Déconnexion propre, aucune TP tardive | `aPlayerWhoDisconnectsIsNeverTeleportedLater` |
| Portails hors Wild non concernés | `portalsThatDoNotLeadToTheWildAreNeverAffected` |
| Rendu réel du repli chat | `theChatFallbackShowsTheWarningAndItsThreeActions` |
| `teleportNow` contourne le garde | `WorldPortalTeleportListenerTest#teleportNowBypassesTheEntryGuardOnPurpose` |
| Latence mesurée | `RandomSafeLocationFinderTest` (tentatives et temps de chunk réellement comptés, résultat inchangé ; recherche épuisée → toutes les tentatives comptées) et `TpTraceLoggerTest#latencyNeverThrowsAndConvertsNanosToMillis` |

**#24** — `WildConditionsServiceTest` (9 tests) : midi + ciel clair, minuit, pluie, orage (jamais
annoncé comme une simple averse), météo qui **ne change jamais** le verdict jour/nuit, bornes de nuit
exactes aux quatre points limites, lecture qui laisse heure/pluie/orage **inchangés**, Wild non
chargé jamais inventé, nom de monde vide jamais recherché. `DialogueTextPlaceholdersTest` (6 tests) :
substitution, clé inconnue laissée visible, nœud sans marqueur renvoyé **tel quel** (même instance),
aucune substitution quand rien n'est enregistré, valeur contenant `%` insérée littéralement sans
récursion, toutes les langues traitées. `DialogueSessionEngineTest#aDynamicPlaceholderIsSubstitutedJustBeforeRendering`
vérifie la substitution sur le **vrai** moteur, au moment du rendu. `BundledDialoguesValidityTest#theGuardAlwaysOffersToReportTheCurrentWildConditions`
verrouille la donnée livrée : un seul choix, **sans condition** (donc permanent) et **sans action**,
menant à un nœud dont le texte porte `%wild_conditions%`.

**#26** — `StarterToolKitServiceTest` et `DialogueSessionEngineTest` (partie A) réexécutés verts ;
aucun fichier de la partie A n'est touché par le diff. Le test
`theFirstEntryWarnsWithoutInspectingTheInventoryAndNeverTeleports` **vide explicitement
l'inventaire** avant de vérifier que l'avertissement s'affiche quand même : c'est la garde
automatisée contre une réintroduction d'inspection d'inventaire.

### Ce que les tests automatisés ne prouvent pas

- La **fenêtre Paper** elle-même (API expérimentale) n'est pas instanciable sous MockBukkit : les
  tests utilisent un présentateur injecté et vérifient séparément le repli chat réel. Le rendu Paper
  et la fermeture au clavier sont du ressort de TC-256.
- La **latence réelle** : MockBukkit ne génère pas de terrain, donc `chunks_ms` y est négligeable.
  Les valeurs qui comptent ne peuvent être relevées qu'en jeu (TC-256, point 9).
- L'**horloge et la météo d'un vrai monde** : les tests pilotent un `WorldMock`.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — **TC-256** ajouté à `docs/MANUAL_TEST_PLAN.md` (22 points) :
avertissement et son texte, absence de jour/nuit/météo dans le portail, annulation, absence de
boucle en restant dans le portail, sortie/entrée qui réaffiche, fermeture au clavier sans effet et
sans mémorisation, départ confirmé avec préparation immédiate puis arrivée, **relevé de la ligne
`[TP-LATENCY]` à reporter**, « ne plus afficher » puis reconnexion **et** redémarrage, `resetnew`
qui rétablit l'avertissement, choix du Garde présent quelle que soit la progression, réponses
jour/nuit/clair/pluie/orage pilotées depuis la console sur le monde `wild`, vérification que la
lecture ne modifie **ni** l'heure **ni** la météo, Wild déchargé, et non-régression du kit du Guide
(contenu exact, droit après mort, refus si moins de 4 emplacements libres).

La recette rappelle de terminer par `weather clear` et de ne jamais laisser des commandes de test
appliquées sur le serveur.

## Résultat attendu

En jeu, après ce déploiement :

1. Entrer dans le passage Hub → Wild ouvre un avertissement, **sans aucune téléportation**.
2. Le texte annonce la zone dangereuse, le PvP autorisé, la perte d'inventaire à la mort, puis
   renvoie au Garde — **sans** afficher jour/nuit/météo.
3. « Annuler » ou fermer la fenêtre laisse le joueur au Hub ; rester dans le portail ne rouvre rien ;
   ressortir et rentrer réaffiche l'avertissement.
4. « Entrer dans le Wild » affiche immédiatement « Recherche d'un point d'arrivée sûr… Téléportation
   en préparation. », puis l'arrivée (ou un échec explicite).
5. « Entrer et ne plus afficher cet avertissement » part **et** mémorise le choix, qui survit à une
   reconnexion et à un redémarrage ; le message de préparation reste affiché.
6. Le Garde propose en permanence « Comment est le Wild actuellement ? » et répond avec l'heure et la
   météo réelles du monde `wild`, sans rien y modifier.
7. Le kit du Guide se comporte exactement comme avant.
8. La console journalise une ligne `[TP-LATENCY]` par passage.

## Reset / retour à l'état initial

- **Rétablir l'avertissement pour un joueur** : `/rpgadmin player resetnew <joueur>` (efface toutes
  ses variables, donc `WILD_ENTRY_WARNING_HIDDEN`), ou supprimer cette seule clé via
  `/rpgadmin player variable` si l'on ne veut pas toucher au reste.
- **Aucun reset de monde, de quête ou de joueur n'est nécessaire** : ce lot n'écrit qu'une variable
  par joueur, et seulement si le joueur choisit explicitement de masquer l'avertissement.
- Les fichiers de contenu non suivis du propriétaire n'ont pas été modifiés (vérifié par
  `git status` avant et après).

## Déploiement VeryGames

**Déjà effectué** sur le serveur DEV le 2026-10-07 entre 19:41 et 19:45 (autorisation explicite
reçue pour ce lot). Détail complet et empreintes : `docs/deployment/SERVER_CHANGELOG.md`, entrée
« 2026-10-07 (lot 6) ».

### À transférer

- `build/libs/rpgquest-0.1.0-SNAPSHOT.jar` → racine du dossier de plugins
  (1 935 862 o, SHA-256 `b234c5946a7a3f09425fa4c44d0e55556a6ec543843da234f5d08a950d0120c8`).
- `src/main/resources/dialogues/guard.yml` → `RPGQuest/dialogues/guard.yml`
  (6 638 o, SHA-256 `a984f15badf95c0beea37a295de4aa98d56bcc3b02b834963c257dd14228b067`).
  **Obligatoire** : un redéploiement de JAR ne met jamais à jour un dialogue déjà présent, donc sans
  ce fichier le choix du Garde n'apparaîtrait pas malgré un code correct.

### Ne PAS transférer/altérer

`data.db`, `config.yml`, `messages.yml`, `spawn.yml`, `RPGQuest/Citizens/`, les mondes
(`world_hub`, `claims`, `wild`), les autres plugins, les autres fichiers de `dialogues/`,
`quests/` et `stories/` — en particulier les contenus locaux du propriétaire
(`jeff_skeleton.yml`, `lily_pumpkin.yml`, `st0_meet_people.yml`, `lily_memories.yml`,
`crystal_hunt.yml` modifié), qui ne sont ni commités ni déployés.

### Redémarrage requis

**Oui** — le JAR et un dialogue ne sont relus qu'au démarrage. Effectué via
`scripts/verygames-restart.sh` (`save-all` → `stop` RCON → retour ONLINE), le seul joueur connecté
ayant été prévenu **deux fois** avant l'arrêt.

### Migration automatique

**Aucune migration.** La préférence réutilise la table existante `player_variables` ; le schéma reste
à sa version actuelle.

## Rollback

`scripts/rollback-verygames.sh --latest` restaure le JAR du lot 5
(`rpgquest-20261007T174129Z-predeploy.jar`, 1 917 683 o, SHA-256 `71020c82…031dc2572` — empreinte
confirmée identique à celle du JAR déployé au lot 5, donc la chaîne de rollback est intacte), puis
redémarrer. Pour `guard.yml` :

```bash
scripts/rollback-verygames.sh --also \
  /home/ubuntu/.local/share/rpgquest/verygames-backups/extra-20261007T174129Z/RPGQuest/dialogues/guard.yml:RPGQuest/dialogues/guard.yml
```

Un rollback du JAR seul est sans danger (l'ancien code ignore le nœud `wild_conditions` ; le marqueur
`%wild_conditions%` s'afficherait littéralement si un joueur empruntait ce choix). Aucune donnée
joueur n'est perdue : la seule écriture de ce lot est une ligne inerte dans `player_variables`.

## Logs / diagnostic

Nouvelle ligne, niveau `INFO`, une par téléportation de portail simple :

```
[TP-LATENCY] uuid=… player=… portal=hub_to_wild world=wild strategy=RANDOM_SAFE attempts=7 search_ms=12 chunks_ms=340 teleport_ms=5 total_ms=357
```

`chunks_ms` = temps cumulé dans `World#getChunkAt` (chargement **ou génération** à la demande),
`search_ms` = évaluation des colonnes hors chargement, `teleport_ms` = l'appel `Player#teleport`,
`total_ms` = mesure de bout en bout. C'est l'outil prévu pour attribuer la latence signalée sans
supposition ; l'hypothèse de travail (le poste dominant est la génération de chunks) **n'est pas
encore confirmée sur données réelles**.

Les lignes `[TP-TRACE]` existantes (instrumentation temporaire) sont inchangées.

## Documentation mise à jour

| Fichier | Contenu |
|---|---|
| `docs/TRAVEL.md` | Section d'avertissement réécrite (parcours, option persistante, absence de boucle, présentation), nouvelle section « État du Wild demandé au Garde », format `[TP-LATENCY]` champ par champ, liste des tests actualisée. |
| `docs/RPGQUEST_BIBLE.md` | Section 4 : valeurs dynamiques `%clé%` dans le texte d'un nœud, avec le tableau des marqueurs. Section 6 : avertissement d'entrée dans le Wild. |
| `docs-site/dialogues.html` | Nouvelle section « Valeurs dynamiques dans le texte » avec l'exemple réel de `guard.yml`. |
| `docs-site/worlds.html` | Encart d'avertissement dans la section « Portail Hub → wild ». |
| `docs/current_state.md` | L'ancien avertissement « sans Rune » est retiré ; les deux comportements livrés sont décrits. |
| `docs/MANUAL_TEST_PLAN.md` | TC-256 (22 points) + ligne dans la table de recette. |
| `docs/deployment/SERVER_CHANGELOG.md` | Entrée « 2026-10-07 (lot 6) » avec empreintes, backups et rollback. |
| `.ai/ROADMAP.md` | Entrée de journal de session. |

## Limitations / travail restant

### Les trois tickets restent ouverts — raison précise pour chacun

La consigne reçue était de les fermer **si tous leurs critères retenus sont remplis et validés**.
Chacun d'eux porte un critère de **validation manuelle en jeu** que je ne peux pas exécuter (aucun
client Minecraft depuis la machine de build), et `CLAUDE.md` interdit explicitement de fermer une
issue quand des tests manuels restent nécessaires. Je ne les ai donc pas fermées :

- **#161** — tout le comportement est livré, déployé et couvert par des tests automatisés, mais deux
  de ses critères d'acceptation ne peuvent pas être cochés par moi : « Tests pertinents **et
  validation manuelle en jeu après déploiement** » et surtout « **Latence observée et cause
  documentées** ; optimisations vérifiées si nécessaires ». L'outil de mesure est livré, mais la
  mesure elle-même exige un vrai passage (TC-256, point 9) : écrire que la cause est documentée sans
  ce relevé serait une affirmation inventée.
- **#24** — même situation : « Validation manuelle Minecraft » est un critère explicite du ticket. Le
  reste est livré et testé.
- **#26** — la partie A est déjà validée en jeu (information donnée par le propriétaire) et n'a pas
  été touchée ; la partie B est livrée sous sa forme révisée par #161. Il ne reste que la validation
  en jeu du parcours livré, qui est précisément TC-256. Fermer #26 avant #161 reviendrait à déclarer
  sa partie B validée alors que l'implémentation qui la porte ne l'est pas encore.

**Les trois peuvent être fermés dès que TC-256 passe.** Je peux le faire immédiatement sur demande,
en reportant dans #161 les valeurs `[TP-LATENCY]` relevées.

### Limites assumées, non acquises

- Le **chargement du dialogue déployé n'a pas été constaté en jeu** : les logs serveur sont
  inaccessibles depuis la machine de build (racine FTP = `plugins/`) et `/dialogue open` exige un
  joueur connecté — il n'y en avait aucun après le redémarrage. Le fichier déployé est néanmoins
  identique octet pour octet à celui que `BundledDialoguesValidityTest` charge sans problème.
- La **fenêtre Paper** repose sur une API marquée expérimentale par Paper. Le repli chat est
  automatique en cas d'exception, mais un rendu Paper *visuellement* dégradé (libellés tronqués par
  exemple) ne déclencherait aucun repli : c'est à observer en jeu. Largeur de bouton fixée à 200 px,
  contre 150 px pour les dialogues, à cause du libellé long « Entrer et ne plus afficher cet
  avertissement ».
- Le texte affirme que « le PvP y est autorisé » dans le Wild. Vérifié par lecture du code :
  **aucune** annulation de dégâts PvP n'existe pour le monde d'exploration (seuls le Hub,
  `hub.HubWorldProtectionListener`, le monde des claims, `claim.ClaimsWorldRulesListener`, et les
  zones à drapeau `pvp: false` en annulent). Le PvP effectif dépend donc de `pvp=true` dans
  `server.properties`, que je n'ai pas lu (non accessible via FTP) — si le propriétaire l'avait mis
  à `false`, le texte deviendrait inexact. **Aucune règle de PvP, de mort ou de drop n'a été
  modifiée**, conformément à la consigne.
- Les **trois boutons** remplacent la case à cocher que l'on pourrait imaginer pour « ne plus
  afficher ». C'est un choix de conception (voir « Analyse ») : il rend la mémorisation sans départ
  structurellement impossible, mais il allonge la liste des actions.
- L'avertissement ne couvre que les **portails simples** (`worldportal`) vers le Wild. Un voyage par
  Waystone, par Rune ou par un portail « classique » (`/rpgadmin portal`) vers le Wild ne l'affiche
  pas — conformément à la consigne du ticket de ne pas imposer un avertissement intrusif à chaque
  voyage entre waypoints, et après audit : le seul point d'entrée Hub → Wild du parcours joueur est
  bien un portail simple.

## Prochaine étape suggérée

Dérouler **TC-256** en jeu (compte non OP pour le parcours principal), en commençant par les points
1 à 6 (avertissement, annulation, absence de boucle, fermeture au clavier) puis le point 9 (relevé
`[TP-LATENCY]`). Me transmettre les valeurs relevées : je les reporte dans #161 et je ferme les trois
tickets si tout passe. Si la latence s'avère dominée par la génération de chunks, la suite naturelle
est une recherche de point d'arrivée hors du thread principal (chargement asynchrone de chunk via
l'API publique Paper), à traiter comme un ticket distinct plutôt qu'en effet de bord de celui-ci.
