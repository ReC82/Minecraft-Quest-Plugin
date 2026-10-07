# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-07
* Heure : 22:04 (heure locale)
* Sujet : Remise réelle d'objets à un PNJ avec dépôts partiels persistants — objectif `DELIVER_ITEM_TO_NPC` (#123), de bout en bout : moteur, dialogue, Control Panel, déploiement DEV
* Statut : DONE (livraison complète et déployée ; #123 reste ouvert — voir « Limitations / travail restant »)
* Branche Git : `feature/123-deliver-item-to-npc` (créée depuis `feature/161-wild-entry-ux`, la ligne réellement déployée en DEV ; poussée)
* Commit actuel si disponible : `26a17ea`
* Début de la tâche : 2026-10-07 20:28:53
* Fin de la tâche : 2026-10-07 22:04:02
* Durée totale : 01:35:09

## Demande

Travailler **uniquement** sur le ticket **#123** : implémenter complètement
`DELIVER_ITEM_TO_NPC` avec dépôts partiels persistants, le rendre administrable depuis le Control
Panel, le déployer sur DEV et préparer une recette manuelle courte. Objectif explicite : terminer
réellement #123 **avant** de passer à #218.

Décisions fonctionnelles imposées :

- posséder ou collecter les objets ne complète **jamais** l'objectif ; le joueur doit réellement les
  remettre au PNJ configuré ;
- les dépôts partiels sont autorisés et **persistent** (4 LEATHER demandés, 2 remis → 2/4 acquis même
  après mort, déconnexion ou redémarrage) ;
- les matériaux remis sont consommés et jamais restitués ; le joueur revient déposer le reliquat ;
- ne jamais retirer plus que la quantité encore nécessaire ; gérer plusieurs piles ;
- le mauvais PNJ ne peut jamais accepter la remise ;
- spam, double clic, lag ou appels concurrents ne doivent jamais provoquer de double retrait ni de
  double progression ;
- plusieurs objectifs `DELIVER_ITEM_TO_NPC` doivent coexister dans une même étape (cible #218 :
  1 STICK, 2 COBBLESTONE, 4 LEATHER, 3 WHEAT_SEEDS), et **une seule** interaction « Donner les
  matériaux que j'ai » doit traiter d'un coup tout ce que le joueur possède d'utile ;
- le dialogue doit afficher ce qui a déjà été remis, ce qui manque, ce qui vient d'être remis, et
  quand tout est terminé ;
- le mécanisme doit être générique et **jamais** codé pour un PNJ précis ;
- Control Panel : type d'objectif « Rapporter des objets à un PNJ », sélection du PNJ, recherche du
  matériau, quantité, validation, édition ultérieure, résumé lisible, aller-retour correct, et
  journal/HUD lisible avec progression X / N ;
- V1 : matériaux Minecraft standards, architecture compatible plus tard avec les items custom ;
- réutiliser le moteur de quêtes, de dialogue, d'inventaire, de persistance et les descripteurs du
  panel **existants** ; ne pas créer de mécanique parallèle spécifique à #218 ; ne pas modifier #218
  maintenant ; préserver les fichiers locaux non suivis ; ne pas toucher à Lily ; **ne pas merger**.

Gouvernance accordée : coder, tester, commit, push, déployer sur DEV, redémarrer le serveur.
À la fin : commits, résultat exact des tests, ce qui est validé automatiquement, ce qui reste à
valider manuellement, documentation et plan de recette à jour, un TC manuel court et précis, et
fermeture de #123 **si tout est réellement validé** — sinon laisser ouvert en expliquant exactement
ce qui manque.

## Analyse

### État réel avant l'intervention (lecture du code, pas du ticket)

- `ObjectiveType` comptait **7** valeurs ; aucune ne correspondait à une remise. `COLLECT_ITEM`
  existait et progressait sur `EntityPickupItemEvent` — exactement la sémantique que #123 veut
  éviter.
- `QuestProgressEngine` (1007 lignes) ne savait progresser que **par pas de 1**
  (`handleCandidates` → `progress.increment(...)`), piloté par un écouteur d'événement par type
  d'objectif (`createListeners`). Il n'existait aucun chemin « avancer de N d'un coup », ni aucun
  chemin déclenché autrement que par un événement de jeu.
- En revanche, **toute la persistance nécessaire existait déjà** : les compteurs d'objectif sont
  stockés par `(joueur, quête, étape, index d'objectif)` via
  `QuestProgressRepository#setObjectiveProgress`, et `loadForPlayer` les relit à la connexion. C'est
  ce qui rend les dépôts partiels persistants **sans aucune migration de schéma** — le point le plus
  important de l'analyse.
- Le moteur de dialogue pouvait déjà exécuter des actions et filtrer des choix par condition, et
  disposait depuis la session précédente (#24) d'un mécanisme de **valeur dynamique dans le texte
  d'un nœud** (`%wild_conditions%`) — mais celui-ci ne recevait que le joueur, pas le dialogue.
- Le Control Panel était déjà **entièrement piloté par descripteurs** (`Descriptors.OBJECTIVES`) :
  formulaire, sérialisation YAML, aller-retour et validation des références découlent des champs
  déclarés. Ajouter un type = ajouter un descripteur, sans code de formulaire.
- Le relevé structuré envoyé au panel (`AgentActions.ObjectiveSummary`) ne portait qu'**une** cible
  (`target`) — insuffisant pour une remise, qui en a deux (l'objet compté et le PNJ qui le reçoit).

### Décisions techniques

1. **Aucun écouteur d'événement pour ce type.** `createListeners` renvoie une liste vide pour
   `DELIVER_ITEM_TO_NPC`. C'est la décision structurante : il n'existe littéralement aucun chemin par
   lequel ramasser, fabriquer ou porter un objet puisse faire avancer une remise. Un écouteur, même
   « intelligent », rouvrirait la sémantique de `COLLECT_ITEM` que ce type existe pour éviter.
2. **Le compteur d'objectif *est* la quantité déjà remise.** Pas de nouvelle table, pas de migration,
   et la persistance (mort / reconnexion / redémarrage) est obtenue en réutilisant le mécanisme
   existant plutôt qu'en le doublant. `/rpgadmin player resetnew` et les resets de quête remettent
   la remise à zéro sans code dédié.
3. **Retrait d'abord, progression ensuite, de la quantité réellement retirée.**
   `QuestItemWithdrawal.withdraw` renvoie ce qu'il a retiré ; le compteur n'avance que de cette
   valeur. Il est donc structurellement impossible de progresser sans retrait — c'est la garantie que
   le ticket formule comme « ne pas créer un état où les objets sont consommés sans progression
   correspondante », prise dans l'autre sens, le seul qui ne puisse pas léser le joueur.
4. **Comptage et retrait dans la même passe**, sur le thread principal : aucune fenêtre entre « j'ai
   compté » et « je retire » pendant laquelle l'inventaire pourrait changer.
5. **Les objets personnalisés RPGQuest ne sont jamais consommés.** Une pile portant une identité dans
   son `PersistentDataContainer` est ignorée même si son `Material` correspond. Sans cette règle, une
   quête demandant `BOOK` détruirait le journal de quêtes du joueur, et une quête demandant du cuir
   pourrait détruire un objet custom en cuir. C'est aussi la règle d'identité du projet (jamais
   reconnu par matériau seul). Pour ne pas créer une seconde source de vérité, la lecture du PDC a
   été exposée en **statique** sur `YamlCustomItemRegistry` (`identityOf`), qui reste le seul endroit
   où la clé est connue.
6. **Stockage normal uniquement** (`getStorageContents`), ni armure ni main secondaire — même
   périmètre que le comptage de place de `StarterToolKitService`. Un objet porté ne peut pas
   disparaître dans une remise.
7. **Un seul jeton par joueur** (`deliveriesInFlight`) : une remise réentrante est refusée avec le
   statut `BUSY`, sans rien retirer. Combiné à la mise à jour mémoire synchrone (déjà la garantie
   anti-double-incrément du moteur), cela couvre double-clic, spam et cascade d'événements.
8. **La complétion d'étape n'est évaluée qu'après toute la remise.** Une étape à quatre matériaux ne
   doit pas se terminer au milieu de l'opération — sinon le quatrième retrait porterait sur une quête
   déjà sortie des quêtes actives.
9. **PNJ implicite, déduit de la clé du dialogue.** `DELIVER_QUEST_ITEMS` et `HAS_PENDING_DELIVERY`
   acceptent un `npc` vide : le destinataire est alors la clé du dialogue courant
   (`rpgquest:guard` → `guard`), suivant la convention existante du projet. C'est ce qui satisfait
   « générique, jamais codé pour un PNJ précis » **au niveau de la donnée** : la branche livrée dans
   `guard.yml` ne nomme ni quête, ni matériau, ni PNJ, et se recopie telle quelle. Un id explicite
   reste accepté pour un dialogue partagé.
10. **Substitution de texte plutôt qu'une nouvelle action pour l'affichage.** `%delivery_status%`
    laisse le PNJ « parler » et conserve le fil du dialogue ; une action aurait dû fermer la fenêtre
    pour écrire dans le chat. Cela a demandé de faire passer un **contexte** (joueur + dialogue) aux
    valeurs dynamiques au lieu du seul joueur — évolution directe du mécanisme livré pour #24.
11. **La condition niée remplace un second type de condition.** « Ce PNJ n'attend plus rien » est
    `HAS_PENDING_DELIVERY` + `negate: true`, ce qui fait basculer vers le nœud de fin sans ajouter
    quoi que ce soit au catalogue.
12. **Un champ `npc` ajouté au relevé structuré** (`ObjectiveSummary`, lignes JSON de l'agent, DTO de
    content pack) plutôt que d'écraser `target` : une remise a deux cibles, et les fusionner rendrait
    le libellé du panel faux. L'ajout est **additif** — un constructeur de compatibilité à 4
    arguments conserve tous les appelants existants, et le sérialiseur de content pack n'écrit le
    champ que s'il est présent.
13. **Extensibilité aux items custom (V2) concentrée en un point** : `QuestItemWithdrawal#matches`
    est la seule règle d'éligibilité d'une pile, et `QuestDefinitionParser` le seul endroit qui lit
    `material:`. Ajouter un `item:` namespacé plus tard ne casse donc aucun contrat de contenu.

## Travail effectué

### Objectif et moteur de remise

- **`quest.model.DeliverItemToNpcObjective`** (`npcId`, `material`, `amount`) — huitième valeur de
  `ObjectiveType`, ajoutée à l'interface scellée `QuestObjective` (donc tous les `switch` exhaustifs
  du projet ont dû être complétés : description, quantité requise, index, libellés du journal,
  content pack, relevé de l'agent — aucun n'a pu être oublié par construction).
- **`QuestDefinitionParser`** : les trois champs sont validés séparément, pour que l'erreur nomme
  précisément ce qui manque.
- **`QuestObjectiveIndex#deliverToNpc(npcId)`** : index par PNJ destinataire, construit une fois par
  chargement. Un PNJ qui n'est destinataire de rien renvoie une liste vide — c'est ce qui fait qu'un
  mauvais PNJ ne peut rien accepter.
- **`QuestProgressEngine`** :
  - `pendingDeliveries(playerId, npcId)` — vue en lecture seule (quête `ACTIVE`, étape **courante**),
    appelable à chaque ouverture de nœud sans effet de bord ;
  - `hasPendingDelivery(...)` — raccourci pour la condition de dialogue ;
  - `deliverTo(player, npcId)` — la remise : jeton anti-concurrence, reliquat calculé en mémoire,
    retrait exact, progression de la quantité réellement retirée, persistance immédiate,
    récapitulatif au joueur, puis complétion d'étape évaluée une seule fois à la fin ;
  - `createListeners` renvoie **aucun** écouteur pour ce type.
- **`QuestItemWithdrawal`** (nouveau) : retrait exact, multi-piles, stockage normal, objets
  personnalisés épargnés, renvoie le nombre réellement retiré. Seule règle d'éligibilité d'une pile.
- **`DeliveryLine` / `DeliveryOutcome` / `DeliveryStatusText`** (nouveaux) : état d'une ligne
  (`delivered`, `required`, `justNow`, `remaining()`, `complete()`), résultat d'une tentative avec
  cinq statuts explicites (`NO_OBJECTIVE`, `ALREADY_COMPLETE`, `NOTHING_USEFUL`, `DELIVERED`,
  `BUSY`), et rendu texte de l'état pour le dialogue.
- **`YamlCustomItemRegistry#identityOf`** : variante statique de `identify`, pour que le retrait
  puisse reconnaître un objet personnalisé sans dupliquer la clé PDC.

### Dialogue

- **`DELIVER_QUEST_ITEMS`** (`DeliverQuestItemsAction`, `npc` optionnel) et
  **`HAS_PENDING_DELIVERY`** (`PendingDeliveryCondition`, `npc` optionnel) : parsing, écriture
  (aller-retour fidèle, PNJ implicite **jamais** réécrit), exécution et évaluation.
- **`DialogueNpcResolution`** (nouveau) : un seul endroit décide « id explicite, sinon clé du
  dialogue, sinon aucun PNJ ».
- **`DialoguePlaceholderContext`** (nouveau) + `DialogueTextPlaceholders` : les valeurs dynamiques
  reçoivent joueur **et** dialogue. `%wild_conditions%` (#24) ignore simplement le contexte.
- **`DialogueSessionEngine`** : le dialogue courant est désormais transmis aux conditions et aux
  actions (`visibleChoices`, `evaluateAll`, `evaluateCondition`, `executeAction`), ce qui était
  nécessaire pour résoudre le PNJ implicite.
- **`messages.yml`** : six clés `quest.delivery-*` (remis / manque / tout remis / rien d'utile / déjà
  remis / rien attendu). Envoyées dans le **chat** et non l'ActionBar — une remise groupée touche
  plusieurs objectifs et autant de messages d'ActionBar s'écraseraient l'un l'autre.
- **`dialogues/guard.yml`** : branche `delivery` / `delivery_after` / `delivery_done`, générique.

### Control Panel

- Descripteur `DELIVER_ITEM_TO_NPC` (« Rapporter des objets à un PNJ ») : PNJ destinataire
  (select `npc`), objet (select `material`), quantité (entier > 0), avec une aide qui dit ce qui
  n'est pas évident.
- `ObjectiveText` : libellé « Rapporter Cuir (x4) à Guard », qui nomme les deux cibles.
- `ObjectiveSummary` + lignes JSON de l'agent : champ `npc` ajouté.

### Journal

`ObjectiveLabels` : la ligne affiche « Cuir (à remettre) 2/4 ». Le verbe distingue la remise d'une
collecte — sans lui, deux lignes identiques attendraient deux actions différentes. Le PNJ n'y est pas
nommé (son id est une donnée interne, même raison que `TALK_TO_NPC`).

## Fichiers créés

| Fichier | Rôle |
|---|---|
| `quest/model/DeliverItemToNpcObjective.java` | Le type d'objectif. |
| `quest/progress/QuestItemWithdrawal.java` | Retrait exact, multi-piles, objets custom épargnés. |
| `quest/progress/DeliveryLine.java` | Une ligne d'état (remis / demandé / à l'instant). |
| `quest/progress/DeliveryOutcome.java` | Résultat d'une tentative de remise, cinq statuts. |
| `quest/progress/DeliveryStatusText.java` | Rendu MiniMessage de l'état pour le dialogue. |
| `dialogue/model/DeliverQuestItemsAction.java` | Action de dialogue. |
| `dialogue/model/PendingDeliveryCondition.java` | Condition de dialogue. |
| `dialogue/DialogueNpcResolution.java` | Résolution du PNJ (explicite, sinon clé du dialogue). |
| `dialogue/DialoguePlaceholderContext.java` | Contexte d'une valeur dynamique de texte. |
| `src/test/.../QuestItemDeliveryTest.java` | 22 tests de remise (voir « Tests automatiques »). |
| `src/test/.../QuestItemWithdrawalTest.java` | 8 tests du retrait isolé. |
| `src/test/.../ui/ObjectiveLabelsTest.java` | 2 tests de lisibilité du journal. |

## Fichiers modifiés

| Fichier | Changement |
|---|---|
| `quest/model/{ObjectiveType,QuestObjective}.java` | Huitième type, interface scellée, description et quantité. |
| `quest/QuestDefinitionParser.java` | Parsing des trois champs, erreurs nommées. |
| `quest/progress/QuestObjectiveIndex.java` | Index par PNJ destinataire. |
| `quest/progress/QuestProgressEngine.java` | `pendingDeliveries`, `hasPendingDelivery`, `deliverTo`, jeton anti-concurrence, aucun écouteur pour ce type. |
| `item/YamlCustomItemRegistry.java` | `identityOf` statique (même clé PDC, aucune duplication). |
| `ui/ObjectiveLabels.java` | Libellé « (à remettre) », objectif comptable. |
| `dialogue/model/{ActionType,ConditionType,DialogueAction,DialogueCondition}.java` | Nouveaux types. |
| `dialogue/{DialogueDefinitionParser,DialogueDefinitionWriter}.java` | Parsing et écriture fidèle. |
| `dialogue/DialogueTextPlaceholders.java` | Contexte au lieu du seul joueur. |
| `dialogue/session/DialogueSessionEngine.java` | Dialogue transmis aux conditions/actions, exécution de la remise. |
| `content/pack/{QuestPackEntry,DialoguePackEntry,ContentPackMapper,ContentPackSerializer}.java` | Export de l'objectif, de l'action et de la condition. |
| `web/agent/{AgentActions,AgentActionExecutor,BukkitAgentActions}.java` | Champ `npc` du relevé structuré. |
| `bootstrap/RPGQuestBootstrap.java` | Câblage de `%delivery_status%`. |
| `control-panel/.../content/Descriptors.java` | Descripteur du type. |
| `control-panel/.../web/ObjectiveText.java` | Résumé lisible à deux cibles. |
| `src/main/resources/messages.yml` | Six clés `quest.delivery-*`. |
| `src/main/resources/dialogues/guard.yml` | Branche de remise générique. |
| 8 fichiers de tests existants | Nouveaux cas (parser, writer, round-trip, pack, panel). |

## Base de données / migrations

**Aucune migration.** C'est le résultat d'une décision d'architecture, pas un hasard : la quantité
déjà remise *est* le compteur de l'objectif, stocké dans la table `quest_objective_progress` qui
existait déjà et que `loadForPlayer` relit à chaque connexion. Conséquences directes :

- la persistance après mort / reconnexion / redémarrage est obtenue sans une ligne de code dédiée ;
- un serveur déjà déployé n'a rien à migrer, et un rollback ne laisse aucune structure orpheline ;
- les resets existants (`/rpgadmin player resetnew`, reset de quête) remettent la remise à zéro
  gratuitement.

## Configuration / données

- **Aucune nouvelle clé de `config.yml`.**
- `messages.yml` : six clés ajoutées. Un `messages.yml` déjà personnalisé sur le serveur les reçoit
  automatiquement (`QuestMessagesService#mergeMissingKeys` fusionne les clés manquantes de la
  ressource embarquée à chaque rechargement) — aucune personnalisation existante n'est écrasée.
- `dialogues/guard.yml` : branche de remise ajoutée. ⚠️ Un redéploiement de JAR ne met **jamais** à
  jour un dialogue déjà présent sur le serveur : ce fichier doit être transféré explicitement.

## Tests automatiques

Exécutés dans un **worktree Git propre** (les fichiers de contenu non suivis du propriétaire —
`crystal_hunt.yml` modifié, `jeff_skeleton.yml`, `lily_pumpkin.yml`, `st0_meet_people.yml`,
`lily_memories.yml` — font échouer `CrystalHuntIntegrationTest` ; ils n'ont été ni commités ni
modifiés).

`./gradlew build` (qui inclut `test` des trois modules) — **BUILD SUCCESSFUL** :

| Module | Tests | Échecs | Erreurs | Ignorés |
|---|---|---|---|---|
| plugin (racine) | 1851 | **0** | 0 | 37 |
| control-panel | 725 | **0** | 0 | 1 |
| web-api | 30 | **0** | 0 | 0 |

Les tests ignorés sont les limitations MockBukkit déjà documentées dans les sessions précédentes,
pas des tests de ce lot.

### Un défaut trouvé par la suite complète, pas par les tests ciblés

Le premier passage de `./gradlew build` sur le lot a **échoué** : `ManualTestQuestPackTest` exige que
`docs/manual-tests/quests/` contienne **une quête par type d'`ObjectiveType` implémenté, ni plus ni
moins**, et le huitième type n'en avait pas.

C'est exactement ce que ce filet est conçu pour attraper, et aucun des tests ciblés que j'avais
lancés auparavant ne pouvait le voir. Corrigé par
`docs/manual-tests/quests/test_deliver_item_to_npc.yml` (qui reprend l'exemple cible de #218 :
1 bâton, 2 pierres, 4 cuirs, 3 graines) et la mise à jour du pack dans `MANUAL_TEST_PLAN.md` (7 → 8
quêtes, procédure d'activation et de retrait incluses). Les résultats verts annoncés ci-dessus sont
ceux du passage **après** cette correction.


### Couverture, par exigence du ticket

`QuestItemDeliveryTest` (nouveau, 22 tests) — vrai `QuestProgressEngine` + vraie base SQLite, la
persistance n'est jamais simulée :

| Exigence #123 | Test |
|---|---|
| Posséder/collecter ne complète jamais | `holdingOrCollectingTheItemsNeverCompletesTheObjective` (64 cuirs en poche + événements de ramassage et de craft → 0/4, rien consommé) |
| Dépôt 0 | `deliveringWithAnEmptyInventoryChangesNothing` (statut `NOTHING_USEFUL`, aucune écriture) |
| Dépôt partiel | `aPartialDeliveryProgressesImmediatelyAndConsumesExactlyWhatWasGiven` (2/4, 2 cuirs consommés, valeur **persistée** vérifiée en base) |
| Reliquat plus tard | `theRemainderCanBeDeliveredLaterAndCompletesTheQuest` |
| Quantité exacte | `anExactDeliveryCompletesTheObjectiveInOneGo` |
| Quantité supérieure au besoin | `deliveringMoreThanNeededNeverConsumesTheSurplus` (64 possédés → 4 pris, 60 restants) |
| Objectif déjà terminé | `anAlreadyCompletedDeliveryNeverConsumesAnythingMore` (statut `ALREADY_COMPLETE`, 10 cuirs intacts) |
| Plusieurs piles | `itemsSpreadOverSeveralStacksAreAddedUp`, `onlyTheNeededPartIsTakenAcrossSeveralStacks` |
| Plusieurs objectifs + plusieurs matériaux en **une** interaction | `oneSingleInteractionDeliversEveryUsefulMaterialAtOnce` (1 STICK / 2 COBBLESTONE / 4 LEATHER / 3 WHEAT_SEEDS — exactement l'exemple cible de #218 : quatre compteurs avancent d'un coup, le reliquat est annoncé, puis une seconde remise termine) |
| Deux quêtes, même matériau | `twoQuestsAskingTheSameMaterialShareWhatThePlayerHasWithoutDuplicating` (5 cuirs répartis, jamais comptés deux fois) |
| Mauvais PNJ | `theWrongNpcCanNeverAcceptADelivery`, `anUnknownOrMissingNpcIdNeverDeliversAnything`, `aPlayerWithoutTheQuestNeverDeliversAnything` |
| Appels concurrents | `aReentrantDeliveryIsRefusedWithoutTakingAnything` (seconde remise déclenchée **pendant** la première, via l'observateur de progression → `BUSY`, exactement 4 cuirs retirés en tout) |
| Double clic | `clickingTwiceInARowNeverDoublesTheProgressBeyondWhatWasGiven` |
| Mort + reconnexion | `progressSurvivesDeathAndReconnection` (vrai `PlayerDeathEvent` diffusé, inventaire perdu, état purgé puis relu depuis la base) |
| Redémarrage | `progressSurvivesAServerRestartAndTheRemainderStillCompletesIt` (moteur **entièrement neuf**, même base) |
| Objets custom épargnés | `aCustomRpgquestItemIsNeverConsumedByADelivery` |
| Vue d'état | `theStatusViewReportsDeliveredAndRemainingWithoutChangingAnything`, `theStatusOfANpcThatExpectsNothingIsExplicit` |

`QuestItemWithdrawalTest` (nouveau, 8 tests) : inventaire vide, maximum nul ou négatif, pile
partielle, pile entière (emplacement libéré), plusieurs piles additionnées, demande supérieure au
disponible (le nombre renvoyé est le nombre **réel**), autre matériau jamais touché, objet
personnalisé ni compté ni consommé, armure et main secondaire épargnées.

`QuestDefinitionParserTest` (+5) : parsing complet, **quatre** objectifs de remise dans une même
étape avec l'ordre préservé, trois champs manquants tous signalés, matériau inconnu, quantité nulle.

`DialogueDefinitionParserTest` (+5) : action avec et sans `npc`, `npc` vide traité comme absent,
condition avec et sans `npc`, condition **niée**.

`DialogueDefinitionEditorTest` (+1) : aller-retour YAML de la branche de remise, **à l'octet près**,
PNJ implicite conservé implicite.

`DialogueSessionEngineTest` (+3) : le choix de remise n'est visible que s'il reste quelque chose à
remettre, le clic consomme réellement les objets et fait progresser, et `%delivery_status%` est
substitué au rendu avec le PNJ **déduit de la clé du dialogue** (vérifié par le fait qu'un dialogue
dont la clé n'est pas celle du PNJ affiche « n'attend rien »).

`DialogueTextPlaceholdersTest` (+1), `BundledDialoguesValidityTest` (+1 : la branche livrée du Garde
ne nomme ni quête, ni matériau, ni PNJ), `ContentPackMapperTest` (+2 : objectif exporté avec ses deux
cibles ; PNJ implicite d'une action/condition conservé implicite), `ObjectiveLabelsTest` (nouveau,
+2 : la remise se lit différemment d'une collecte et reste comptable).

Control Panel : `EditorDescriptorsTest` (le type rejoint l'ensemble exigé identique au moteur),
`ContentYamlRoundTripTest` (+1 : aller-retour des trois champs), `ObjectiveTextTest` (+3 : libellé à
deux cibles, singulier sans compteur, absence du champ `npc` d'un agent plus ancien).

### Ce que les tests automatisés ne prouvent pas

- Le rendu réel de la **fenêtre de dialogue** Paper et des noms d'objets traduits par le client.
- Le comportement d'un **vrai** redémarrage de serveur (le test redémarre le moteur, pas la JVM).
- Le **rythme de clic** d'un joueur réel : le test d'appel réentrant couvre le cas dangereux
  (seconde remise pendant la première), pas une course de quelques millisecondes entre deux paquets.
- Le **parcours complet depuis le Control Panel** (créer la quête dans le navigateur, l'enregistrer,
  la recharger côté serveur).

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — **TC-257** ajouté à `docs/MANUAL_TEST_PLAN.md` (24 points) : créer la
quête depuis le Control Panel avec quatre objectifs de remise et vérifier l'aller-retour en édition,
« posséder ne suffit pas », dépôt partiel et compteur du journal, survie à la mort / à la
reconnexion / au redémarrage, les quatre matériaux remis en **une** interaction, surplus jamais
consommé, piles séparées, spam du bouton, mauvais PNJ, objectif déjà terminé, objet personnalisé
épargné, puis nettoyage de la quête de test.

## Résultat attendu

En jeu, après ce déploiement :

1. Dans l'éditeur de quêtes du panel, le type « Rapporter des objets à un PNJ » est proposé et
   demande PNJ + objet + quantité ; plusieurs objectifs de remise coexistent dans une étape.
2. Avec une telle quête active, le Garde propose « J'ai des matériaux à te remettre » — et **lui
   seul** : les autres PNJ n'affichent rien.
3. Le nœud liste chaque matériau avec `déjà remis/demandé`.
4. « Donner les matériaux que j'ai » remet **tout l'utile d'un coup**, annonce ce qui vient d'être
   remis puis ce qui manque, et ne prend jamais plus que le reliquat.
5. Le journal affiche `Cuir (à remettre) 2/4`.
6. Mourir, se déconnecter ou redémarrer le serveur ne fait **jamais** perdre ce qui a été remis.
7. Quand tout est remis, le PNJ le dit et le nœud de fin apparaît.

## Reset / retour à l'état initial

- `/rpgadmin player resetnew <joueur>` et les resets de quête existants effacent la progression de
  remise comme n'importe quel compteur d'objectif — aucun outil dédié n'a été nécessaire.
- Les objets déjà remis sont **consommés** : aucun mécanisme de restitution n'existe (hors périmètre
  de #123, explicitement). Un administrateur qui veut « rendre » des objets passe par `/give`.
- Aucun monde, aucun PNJ, aucun contenu hors périmètre n'a été touché ; les fichiers locaux non
  suivis du propriétaire sont intacts (`git status` vérifié avant et après).

## Déploiement VeryGames

**Effectué** sur le serveur DEV (autorisation explicite reçue pour ce lot). Détail complet et
empreintes : `docs/deployment/SERVER_CHANGELOG.md`, entrée « 2026-10-07 (lot 7) ».

### À transférer

- `build/libs/rpgquest-0.1.0-SNAPSHOT.jar` → racine du dossier de plugins.
- `src/main/resources/dialogues/guard.yml` → `RPGQuest/dialogues/guard.yml`. **Obligatoire** : sans
  ce fichier, le code de remise serait en place mais aucun PNJ ne proposerait de recevoir quoi que ce
  soit (un redéploiement de JAR ne met jamais à jour un dialogue déjà présent). La version en ligne a
  été **comparée avant transfert** : identique à celle déployée au lot précédent, donc aucune édition
  faite depuis le Control Panel n'a été écrasée.
- `messages.yml` : **non transféré**, volontairement. Les six nouvelles clés
  `quest.delivery-*` sont fusionnées automatiquement dans le fichier du serveur par
  `QuestMessagesService#mergeMissingKeys` au démarrage/rechargement, ce qui préserve les
  personnalisations existantes. Transférer le fichier les écraserait.

### Ne PAS transférer/altérer

`data.db`, `config.yml`, `messages.yml`, `spawn.yml`, `RPGQuest/Citizens/`, les mondes
(`world_hub`, `claims`, `wild`), les autres plugins, les autres fichiers de `dialogues/`, `quests/`
et `stories/` — en particulier les contenus locaux du propriétaire (`jeff_skeleton.yml`,
`lily_pumpkin.yml`, `st0_meet_people.yml`, `lily_memories.yml`, `crystal_hunt.yml` modifié), qui ne
sont ni commités ni déployés. **Lily n'a pas été touchée.**

### Redémarrage requis

**Oui** — le JAR et un dialogue ne sont relus qu'au démarrage.

### Migration automatique

**Aucune migration**, et rien à migrer : la progression de remise réutilise la table de compteurs
d'objectifs existante.

## Rollback

`scripts/rollback-verygames.sh --latest` restaure le JAR précédent, puis redémarrer. Pour
`guard.yml`, réutiliser `--also` en sens inverse depuis le dossier de backup `extra-<horodatage>/`
(voir son `MANIFEST.txt`).

Un rollback du **JAR seul** est sans danger mais laisse une anomalie cosmétique : l'ancien code ne
connaît ni `DELIVER_QUEST_ITEMS` ni `HAS_PENDING_DELIVERY`, donc le `guard.yml` resté en place serait
**rejeté au chargement** (type d'action inconnu) et le dialogue du Garde disparaîtrait. **Restaurer
les deux fichiers**, pas seulement le JAR — c'est la différence importante avec le lot précédent, où
le rollback du JAR seul restait acceptable.

Aucune donnée joueur n'est perdue par un rollback : les compteurs de remise déjà écrits restent dans
`quest_objective_progress` et redeviendront lisibles si le JAR est redéployé. En revanche les objets
déjà consommés ne reviennent pas — c'est la sémantique voulue du ticket.

## Logs / diagnostic

Aucun nouveau format de log. Une remise écrit les compteurs par le même chemin que n'importe quelle
progression d'objectif (erreur de persistance journalisée en `ERROR` avec la quête et le joueur).
Pour diagnostiquer en jeu : le journal de quêtes affiche `remis/demandé`, et `%delivery_status%`
l'affiche dans le dialogue du PNJ.

## Documentation mise à jour

| Fichier | Contenu |
|---|---|
| `docs/RPGQUEST_BIBLE.md` | Le type dans le tableau des objectifs (8 types), section dédiée « Remise d'objets à un PNJ », action `DELIVER_QUEST_ITEMS`, condition `HAS_PENDING_DELIVERY`, règle du PNJ implicite, marqueur `%delivery_status%`. |
| `docs-site/quests.html` | Ligne du type + encart pour le rédacteur de contenu. |
| `docs-site/dialogues.html` | Action, condition, marqueur, et branche de dialogue générique prête à recopier. |
| `docs/current_state.md` | Comportement livré. |
| `docs/MANUAL_TEST_PLAN.md` | **TC-257** (24 points) + ligne dans la table de recette. |
| `docs/deployment/SERVER_CHANGELOG.md` | Entrée « lot 7 » avec empreintes, backups et rollback des **deux** fichiers. |
| `.ai/ROADMAP.md` | Entrée de journal de session. |

## Limitations / travail restant

### #123 reste ouvert — raison précise

La consigne était de le fermer **si tout est réellement validé**. Tout le périmètre fonctionnel est
livré, déployé et couvert par des tests automatisés, mais **la validation manuelle en jeu n'a pas été
faite** : je n'ai pas de client Minecraft depuis la machine de build, et `CLAUDE.md` interdit de
fermer une issue quand des tests manuels restent nécessaires. Les critères d'acceptation du ticket
qui ne peuvent pas être cochés par moi sont ceux qui passent par l'interface réelle :

- le parcours complet depuis le **Control Panel** dans un navigateur (créer la quête, l'enregistrer,
  la rouvrir en édition) ;
- la **fenêtre de dialogue** réelle et ses messages tels que le joueur les lit ;
- le comportement après un **vrai** redémarrage du serveur avec un vrai joueur.

Recette prête : **TC-257**. Le ticket peut être fermé dès qu'elle passe — je peux le faire
immédiatement sur demande.

### Limites assumées, non acquises

- **Items custom non acceptés en V1** (conforme au ticket, « hors périmètre »). L'architecture est
  prête : `QuestItemWithdrawal#matches` est la seule règle d'éligibilité et `QuestDefinitionParser`
  le seul lecteur de `material:`. Mais c'est une *extension préparée*, pas une extension faite : rien
  n'est testé pour ce cas, et le champ `item:` n'existe pas.
- **Aucune restitution** des objets remis (hors périmètre explicite). Un administrateur qui doit
  corriger une erreur passe par `/give` ; aucun outil dédié n'existe.
- Le **nom du PNJ n'apparaît pas dans le journal** — seulement « (à remettre) ». L'id logique est une
  donnée interne, et le nom affiché n'est pas accessible depuis le rendu du journal. Un joueur ayant
  deux quêtes de remise vers deux PNJ différents verra donc deux lignes qui ne se distinguent que par
  l'objet. À corriger si cela gêne en jeu (il faudrait faire descendre le nom d'affichage du PNJ
  jusqu'au journal).
- Le **spam réel** d'un joueur n'est couvert qu'indirectement : le test d'appel réentrant couvre le
  cas dangereux (seconde remise pendant la première), et la mise à jour mémoire synchrone couvre le
  cas de deux paquets successifs. Une course de quelques millisecondes entre deux threads n'est pas
  reproductible en test unitaire ; le raisonnement est que la remise s'exécute entièrement sur le
  thread principal, donc deux remises ne peuvent pas s'entrelacer.
- Le **relevé structuré de l'agent a changé de forme** (champ `npc` ajouté). Le Control Panel déployé
  en même temps le lit ; un panel plus ancien l'ignorerait simplement, et un agent plus ancien
  n'enverrait pas le champ — cas couvert par un test côté panel, mais jamais vérifié avec deux
  versions réellement désynchronisées.
- `guard.yml` est désormais **couplé au nouveau JAR** : un rollback du JAR seul ferait rejeter ce
  dialogue au chargement (voir « Rollback »).

## Prochaine étape suggérée

Dérouler **TC-257** en jeu (compte non OP), en commençant par les points 1 à 6 (création depuis le
panel et aller-retour) puis 8 à 14 (dépôt partiel et persistance après mort/reconnexion/redémarrage).
Me confirmer le résultat : je ferme #123. Ensuite seulement, attaquer **#218** (progression du kit de
démarrage par paliers), qui est le consommateur prévu de ce mécanisme et qui n'a volontairement pas
été touché ici.
