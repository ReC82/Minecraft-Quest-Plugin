# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-25
* Heure : 03:00
* Sujet : #813 — audit de clôturabilité des pages 7 (25 tickets) et 6 (25 tickets), suite de la page 8
* Statut : DONE côté audit — **décision groupée du propriétaire attendue** ; aucun ticket fermé
* Branche Git : `docs/813-audit-pages-7-6` (depuis `chore/257-ci-github-actions` `5f91d91a`)
* Commit actuel si disponible : voir la PR de ce rapport
* Début de la tâche : 2026-10-25 02:41:16
* Fin de la tâche : 2026-10-25 02:52:42
* Durée totale : 00:11:26

## Demande
Session lqorch sur #813, `next_action` : pages 7 et 6 de l'audit de clôturabilité. Le propriétaire les a ajoutées sur #813 le 24/10 (commentaires de 18:27 et 18:32). Méthode identique à la page 8 ([rapport du 24/10 23:35](2026-10-24_2335_813-cloturabilite-page8.md)) :
- matrice critère par critère, avec la preuve exacte ;
- confrontation au code **servi sur DEV** ;
- statut CLOSABLE / PARTIAL / OPEN / DUPLICATE / OBSOLETE-PROPOSED ;
- commentaire de clôture pour chaque CLOSABLE ;
- une seule demande de décision groupée.

Interdits respectés : aucun ticket fermé, aucune écriture sur DEV ni dans PlugAdmin, aucun appel IA, aucun redémarrage, aucune écriture sur LoDyMcFly, aucune publication Discord (#189 non modifié).

## Analyse

### Ce qui est servi (relu le 25/10 à 02:42)
- **PlugAdmin AWS** : `12484eb5` (`/opt/plugadmin/app/DEPLOYED`, déployé le 25/10 à 02:28, CI verte).
- **JAR DEV** : `12484eb5` (#565, même lot).
- `git diff 12484eb5 5f91d91a -- src/ control-panel/` est vide : le **code audité est le code servi**.

### Méthode
Six analyses en lecture seule (trois par page), une par famille de tickets. J'ai revérifié moi-même les constats qui fondent une proposition de clôture ou un nouveau ticket :
- présence des tests cités : `QuestSmeltObjectiveTest`, `QuestStepIdsTest`, `DialogueSourceMergeTest`, `NpcRolesTest`, `QuestPrereqGraphTest`, `MergedCatalogTest`, et 7 méthodes clés ;
- `AgentEndpoints.RESULT_JSON_MAX = 512 * 1024` ;
- `NpcDiagnosticProvider` l. 64/66/68 (`fix=create|edit|link`) ;
- `ContentEditorPages.readOnlyHint` l. 1008-1014 (cite encore `src/main/resources/` et `grant-content-access.sh`) ;
- `SpecialMobService.locationAllowed` l. 405 (liste `worlds` vide = tous les mondes) ;
- `MarketService.sell` l. 106-114 (aucun contrôle soulbound) ;
- `TravelBeaconService` l. 1007/1079 (tri `Comparator.comparing(Waypoint::displayName)`, sensible à la casse et aux accents) ;
- `.github/workflows/ci.yml` ne lance pas `control-panel/src/test/js/*.test.js`.

Tous les tickets de ces deux pages sont **ouverts** sur GitHub, y compris ceux que lqorch classe DONE (#125, #127, #128, #129, #130, #143, #158).

Légende des états : **A** = livré et testé automatiquement ; **P** = validé par le propriétaire (preuve citée) ; **N** = livré, non prouvé ou non testé ; **M** = manquant ; **O** = critère obsolète, remplacé par une refonte citée.

### Constat transversal : tests JavaScript hors CI
Les tests `resync-feedback.test.js`, `stylefield-parse.test.js` et `combo-pagination.test.js` (`control-panel/src/test/js/`) ne sont lancés ni par `ci.yml` ni par Gradle. Le test Java `CatalogResyncTest.panelScriptCarriesTheSharedReloadOnIdleMechanism` ne fait que chercher des chaînes dans `panel.js`.

Les preuves « A » du rechargement sans F5 (#120, #128, #129, et #112/#115/#116 en page 8) reposent donc sur des exécutions faites à la livraison, pas sur la CI. **Proposition** : ajouter un job `node --test` à la CI (petit, sans risque), à décider au point 6.

---

## Matrice — page 7

### Groupe Quêtes / IA

#### #141 — objectif SMELT_ITEM → **CLOSABLE**
| Critère | Preuve | État |
|---|---|---|
| Type `SMELT_ITEM` déclaratif (target = objet obtenu) | `quest/model/SmeltItemObjective`, `QuestDefinitionParser` ; `QuestDefinitionParserTest.aSmeltObjectiveIsParsedWithTheObtainedItem`, `…WithoutMaterialOrAmountIsRejected` | A |
| Progression sur une vraie cuisson, à la bonne quantité | `QuestSmeltListener.onExtract` (`FurnaceExtractEvent`, `ignoreCancelled`) ; `QuestSmeltObjectiveTest` (unitaire, lot, surproduction) ; `d9204cce` | A + **P** (24/10 : cactus → teinture verte, 1 par 1, lots, surproduction, quête finalisée) |
| Four, haut fourneau, fumoir ; rien pour un autre bloc | `theThreeVanillaFurnaceTypesAllCount`, `anotherBlockEmittingTheEventNeverCounts` | A |
| Aucune progression hors four (`/give`, collecte, craft, entonnoir) | `gettingTheItemWithoutSmeltingItNeverCounts`, `smeltingAnotherItemNeverCounts` ; l'entonnoir n'émet pas d'événement joueur (Bible l. 698) | A |
| Attribution au bon joueur | `progressGoesOnlyToThePlayerWhoExtracted`, `progressSurvivesAReconnection` | A |
| Publication à chaud | PR #304 `476dc6a2` ; `aSmeltQuestPublishedWhileRunningProgressesAfterTheReload` | A + P (#242) |
| Éditeur (libellé, matériau recherchable, quantité, aide) | `panel/content/Descriptors` « Cuire un objet » ; `EditorDescriptorsTest.objectiveKindsMatchTheEngineExactly` | A |
| Aller-retour YAML dans l'éditeur | Sérialisation générique par descripteurs, sans cas SMELT dans `ContentYamlRoundTripTest` | N (non bloquant) |
| Export de pack | `ContentPackMapper` l. 133 ; `ContentPackMapperTest` | A |
| Documentation | Bible l. 698/733, `docs-site/quests.html`, `current_state.md` | A |

Résidu : aucun bloquant. TC-259 est encore PENDING dans `MANUAL_TEST_PLAN.md` alors qu'il est fait.

#### #131 — recharger le contenu depuis PlugAdmin → **PARTIAL**
| Critère | Preuve | État |
|---|---|---|
| États source / publié / chargé visibles | `OpsPages.reloadBlock` ; `ContentReloadPageTest.theOpsPageStatesTheThreeDistinctStates` | A |
| Action proposée sur « pas encore chargé » | `AgentPages.reloadHint` | N (pas de test dédié) |
| Service central réutilisé (agent, commande, #47) | `ContentReloadService`, `ReloadServiceApplier`, `ReloadFamily` (6 familles) | A |
| Le badge disparaît sans F5 | `aSuccessfulReloadRequeuesEveryContentCatalog…`, `aFailedReloadRequeuesNothing` | A |
| Le jeu confirme la version chargée | `ContentReloadServiceTest.theRuntimeHashChanges…`, `theIdsAfterApplicationDescribeTheRuntime` | A |
| Une erreur ne détruit pas ce qui tourne | `anInvalidFileNeverSilentlyRemovesAnAlreadyLoadedDefinition`, `anInvalidFileInOneFamilyBlocksTheWholeRequest` | A |
| Contenus liés, références croisées | `reloadingTheLinkedFamiliesTogetherSucceeds…`, `aStoryPointingAtAnUnknownQuestBlocks…` | A |
| Un seul rechargement à la fois | `aSecondConcurrentReloadIsRefusedNotQueued` | A |
| Avertissement « redémarrage requis » | Documentation seule (`exploitation-serveur.md` l. 300) | N |
| Permission et audit | `Permission.ACTION_CONTENT_RELOAD` (testée) ; audit générique `agent.action.create` | A / N |
| Recette TC-247 (23 étapes) | PENDING ; usage déclaré par le propriétaire, sans trace datée | N |

Résidu : TC-247, au minimum ses parties B (fichier invalide, l'ancien contenu reste) et C (contenu lié). La partie B **écrit sur DEV** (fichier cassé puis restauré) et dépasse 2 minutes.

#### #143 — identifiants d'étape de quête → **CLOSABLE**
| Critère | Preuve | État |
|---|---|---|
| Portée et usages du stepId audités | Commentaire du 15/10 ; Bible l. 1011 | A (doc) |
| Règle d'unicité documentée | Bible, `quetes.md` du panel, `current_state.md` (`f04f5171`) | A |
| Plus de saisie obligatoire | « Identifiant technique (avancé) » ; `ContentEditorPagesTest.theStepIdIsNoLongerARequiredFieldButAnAdvancedDetail`, `aBlankStepIdIsGeneratedAndSaved` | A |
| Génération sûre (ajout, déplacement, suppression) | `QuestDraft.nextStepId` ; `QuestStepIdsTest` (7) ; `movingAStepKeepsEveryId`, `aDeletedSavedStepIdIsNeverReused` | A |
| Pas de régression de progression | `QuestValidator.stepIdChanges` ; `renamingASavedStepIdWarnsAboutPlayerProgress` | A |
| Plusieurs quêtes avec `step_1` | `QuestLoaderTest.twoQuestsMayReuseTheSameStepId` | A |

Résidu : aucun. TC-318 est facultatif (2 min, ne touche que la source PlugAdmin).

#### #146 — atelier IA de création (quête, dialogue, story) → **PARTIAL**
| Critère | Preuve | État |
|---|---|---|
| Décrire en français, une famille par formulaire | `panel/ai/AiContentStudio`, `ContentPromptBuilder.Kind` ; `AiPagesTest.eachFamilyHasItsOwnFormWithItsOwnFields` | A + usage propriétaire le 08/10 (#222) |
| Proposition sans écrire dans les sources | `generatingNeverWritesAnything`, `theStudioOffersNoDirectSaveAndSaysSo` | A |
| Références inconnues signalées, schéma bloquant | `anUnknownNpcReferenceIsReportedWithoutBlockingTheAnalysis`, `anInventedObjectiveTypeIsCaughtByTheRealValidators` | A + P partiel (#222) |
| Fichiers compatibles une fois approuvés | Réutilise l'import #109 ; `aValidResponseGoesAllTheWayToAnImportableAnalysis` | A (enregistrement réel N) |
| Échec ou délai dépassé : rien n'est modifié | `aTimeoutChangesNothingAndIsReported`, `afterAFailedCallNothingIsWrittenOnDisk` | A |
| Journal avec coût estimé | Audit `ai.generate`/`ai.correct` avec modèle et jetons ; pas de coût en $ | A / M |
| Budget plafonné, clé côté serveur | `maxOutputTokens` ; `aStoredKeyNeverAppearsInAnyResponse` | A |

Résidu :
- une ligne du propriétaire confirmant TC-265/TC-266 après le correctif du 08/10 (tickets #222 à #225 fermés par lui le 08/10) ;
- décider si le coût estimé en $ reste exigé, ou si les jetons suffisent.

Aucun appel IA par Claude.

#### #147 — audit IA de cohérence narrative → **OPEN (à fusionner avec #232)**
Les six critères sont **M** : aucune classe d'audit ou de relecture dans `panel/ai/`, aucun commit #147 ni #232. Seules existent des briques réutilisables (`AiProviderRegistry`, `HttpAiProvider`). Le ticket demande lui-même un service unique avec #232. Il faut donc fusionner les critères dans #232, puis fermer #147 comme DUPLICATE.

#### #148 — lots de noms de waypoints et textes d'ambiance par IA → **OPEN (avec #231 et #135)**
Tous les critères sont **M**. Seule brique existante : l'aperçu de dédoublonnage `WaypointNameImportPreview` (#135), qui est **inerte** (appelé nulle part ; `WaypointNameImportPreviewTest`). C'est une évolution future : il faut d'abord brancher l'import de noms sans IA (#135), puis regrouper avec #146 et #231.

### Groupe Waypoints / Voyage

#### #134 — gérer les waypoints existants → **PARTIAL**
| Critère | Preuve | État |
|---|---|---|
| Waypoints listés, distinguables (nom, ID, zone) | `TravelPages` onglet « Lieux », `travel.catalog` ; `TravelCatalogTest.theHomeSummarisesAndListsPlacesByTheirReadableNameOnly` | A |
| Recherche nom / biome / monde, tri | `filterPlaces` ; `searchIgnoresAccentsAndCoversWorldAndBiome`, `theWorldFilterSeparatesTheVillageFromTheWild` | A |
| Filtres actif / anomalie | `PlaceQuery(search, kind, recentFirst, page)` sans ces filtres | M |
| Renommer le nom affiché | `waypoint.rename` (PR #408 `00b0eb53`) ; `WaypointServiceTest.renameChangesOnlyTheDisplayNameRewritesSignsAndSurvivesReload` | A (TC-319 N) |
| Activer / désactiver | Champ `Waypoint.active` sans action ni interface | M |
| Nombre de découvertes | `WaypointService#discoveryCount` non exposé | M |
| Aucun SQL depuis le panel | Agent `travel.catalog` seulement | A |
| Téléphone | Aucune preuve | N |

Résidu : action et bouton activer/désactiver, compteur de découvertes, filtres actif/anomalie, TC-319.

#### #135 — import et gestion des listes de noms → **OPEN**
- Import PlugAdmin, liste, états, ajout manuel, réimport idempotent : **M**.
- Détection des doublons (internes et avec le catalogue) : **N**, aperçu inerte.
- Réserve du JAR : **A**, 220 noms, `reserveNameNeverReturnsAnAlreadyUsedName`.
- Format documenté : **A**, `docs/waypoints/preparation-import-lot1.md`.

Le ticket reste bloqué par la décision du propriétaire déjà posée : A (lot dans le JAR) ou B (import PlugAdmin), plan de renommage des 55 « Avant-poste » et seuil de 50 noms.

#### #136 — mondes autorisés pour la génération → **PARTIAL (cœur prêt)**
| Critère | Preuve | État |
|---|---|---|
| `wild` autorisé, `world_hub` séparé (#149), `claims` interdit | `WaypointWorldEligibility` ; `WaypointWorldEligibilityTest.defaultsKeepTodaysBehaviour`, `WaypointServiceTest.theHubIsLeftToTheBeaconServiceAndClaimsAreNeverEligible`, `ConfigValidatorTest.waypointWorldsAreAnExplicitAllowListAndClaimsCanNeverBeEnabled` | A |
| Monde inconnu interdit, second monde sans code, désactivation qui garde l'existant | `anUnlistedWorldNeverGeneratesAWaypoint`, `aSecondListedWorldGeneratesWithoutCodeChange`, `disablingAWorldStopsGenerationButKeepsItsWaypoints` | A |
| Aucune recherche coûteuse pour un monde interdit | Garde `generationAllowed` en tête de `handleMovement`/`ensureGenerated` (l. 277/315), sans test qui le vérifie | N |
| Politique conservée au rechargement | Lecture `config.get()`, sans test de rechargement | N |
| Complément du 20/10 (densité du Hub, option Wild désactivée) | Absent de `BeaconConfig` | M → #152 |

Résidu : TC-372 (« À tester »). Le complément densité/Wild doit être **explicitement déplacé vers #152** ; #136 devient alors CLOSABLE après TC-372. Contrôle de 1 min en lecture seule : PlugAdmin → Mondes doit afficher wild et world_hub « active », claims « désactivée ».

#### #137 — administrer les mondes éligibles depuis PlugAdmin → **OPEN**
- Lecture seule livrée (**A**) : `WorldPages` l. 265, `WorldsPageTest`, `WorldOverviewTest`.
- Toute la partie modification est **M** : activer un monde, désactiver en gardant l'existant, refus du Nether, audit, resynchronisation.
- Aucun commit #137.

#### #122 — protection anti-grief des waypoints → **OPEN (label idea)**
| Critère | Preuve | État |
|---|---|---|
| Bloc protégé : casse, explosion, piston, feu | `WaypointProtectionListener` ; `playerCannotBreakAProtectedBlock`, `explosionsDoNotDestroyProtectedBlocks`, `pistonsCannotPushBlocksOntoAProtectedBlock`, `fireCannotBurnAProtectedBlock` | A |
| Fluides, gravité | Code présent, sans test | N |
| Panneaux | #654 fermé par le propriétaire le 22/10 | P (implicite) |
| Anti-enfermement, accès praticable, espace devant le bouton, zone tampon contre les fluides | Javadoc : « hors périmètre — voir #122 » | M |
| Maintenance admin | `TravelMaintenanceMode` ; `explicitMaintenanceModeAllowsDeliberateRepair` | A |
| Cas limites spécifiés | Non fait | M |

Résidu : spécification puis développement de la protection d'accès. Le noyau n'a aucune preuve en jeu. Ne pas fermer.

#### #150 — menu de voyage paginé avec recherche → **PARTIAL**
| Critère | Preuve | État |
|---|---|---|
| Recherche réelle par enclume | `realTypingThenClickingTheResultFindsLacDeGivre…` (vue d'enclume simulée) ; **KO propriétaire deux fois le 04/10** (11:06Z et 13:25Z, « lac » + clic), mise en pause ; code inchangé depuis | **M (KO)** |
| Pagination | `paginationSplitsMoreThanFortyFiveDiscoveries…`, `realClickOnNextPage…` | A |
| Filtre biome distinct, grand volume | Absents ou non testés | M / N |
| Découvertes isolées par joueur, clic périmé revalidé | `discoveriesAreIsolatedBetweenPlayers`, `stalePickOfAnUndiscoveredOrUnknownWaypointIsRejected…` | A |
| « Mon claim » indisponible sans claim | Icône BARRIER ; `travelToClaimRefusesWithoutAClaim…` | A |
| Tri alphabétique français, insensible à la casse | `TravelBeaconService` l. 1007/1079 : `Comparator.comparing(Waypoint::displayName)`, sensible à la casse et aux accents (vérifié) ; aucun test | **M** |
| Navigation, retour, fermer, noms avec espaces | Propriétaire, 04/10 11:06Z | P |

Résidu :
- **correctif ciblé sans écriture sur DEV** : tri avec `Collator` français et repli, à l'affichage **et** à la sélection (comme `TravelPages`), avec un test ;
- rediagnostic de la recherche avec le texte réel saisi dans l'enclume ;
- filtre biome.

#### #151 — destinations « Mon claim » et centres de village → **PARTIAL**
| Critère | Preuve | État |
|---|---|---|
| Voyage vers son claim, refus propre sans claim | `travelToClaim` ; `travelToClaimRefusesWithoutAClaimThenArrivesInsideOnceOneExists` | A + P partiel (élément propriétaire, non tracé ; TC-222 PENDING) |
| Voyage vers un village, deux centres distingués | `travelToVillage` (`CityRegistry`, #178/#322) ; `villageCentersAreDistinctByIdAndTravelArrivesAtTheExactStoredLocation` | A + P partiel |
| Village désactivé ou supprimé | `disabledOrRemovedVillageIsRejectedWithoutTeleporting` | A |
| Monde indisponible ou arrivée dangereuse | Claim : contrôles présents, sans test. Village : **pas de vérification d'arrivée sûre** (`teleportAsync` direct) | N / M |

Résidu : arrivée sûre pour les villes, ou décision explicite que l'arrivée réglée par l'admin suffit ; tests du monde non chargé ; tracer TC-222.

#### #152 — administrer bornes, villages et zones sans génération → **PARTIAL (hors file)**
| Critère | Preuve | État |
|---|---|---|
| Listes waypoints et bornes avec diagnostics | `TravelPages` (Lieux, Bornes, À vérifier) ; `TravelCatalogTest` | A + P (04/10 : « Borne associée », listes et filtres) |
| Créer, déplacer, désactiver une borne ou changer son modèle | Seul `travel.beacon.pair` existe | M |
| Centres de village | Actions `city.*` (#178/#322) ; `CitiesPageTest` | A |
| Génération par monde (bornes), zones sans génération | Seul `hubGenerationEnabled` global ; aucune zone sans génération | M |
| Décision du 20/10 (densité du Hub, option Wild désactivée par défaut) | Absent. **Bornes supplémentaires dans le Wild expressément non autorisées** | M (étude seulement) |

Résidu : gestion complète des bornes, zones sans génération, bornes par monde, étude de densité. Aucune activation dans le Wild sans nouvel accord.

### Groupe PNJ / Dialogues (même socle que la page 8)

Socle : resynchronisation `0bf7f7df`, refonte PNJ #325, Dialogues #145 `e80b4591` puis #332, attente visible #128 `7053211b`. Seule validation propriétaire de ce socle : la recette « Musty » du 22/10 (rattachement, page actualisée sans F5 ; dialogue modifié et publié).

#### #145 — dialogues source + jeu, choix du PNJ à la création → **CLOSABLE**
| Critère | Preuve | État |
|---|---|---|
| Créer un dialogue depuis PlugAdmin, visible tout de suite | `PanelApp#handleDialogueCreate` ; `DialogueSourceMergeTest.dialogueCreatedFromEditorShowsUpImmediatelyWithoutAgentCall` | A |
| Le relevé du jeu ne le fait pas disparaître | `runtimeRefreshKeepsSourceOnlyDialogueVisible` | A |
| Badge « Source uniquement » | Page de diagnostic ; `sourceOnlyDialogueIsVisibleWithBadgeAndNote` ; liste simple remplacée par les états #332/#798 | A / O |
| Utilisable par l'éditeur | Éditeur #332 (`DialogueEditorTest`) | A + **P** (Musty 22/10) |
| Choix du PNJ ou présélection | `createFormOffersExistingNpcByName` ; « Créer son dialogue » → `/dialogues/new?npc=` | A |
| Rattachement visible depuis la fiche, jamais de faux « actif en jeu » | `sourceOnlyDialogueIsSelectableFromNpcForm`, `sourceOnlyDialogueNeverClaimsToBeActiveInGame` | A |

Résidu : la recherche de personnage parmi plus de 100 PNJ relève de #118 (page 8) ; elle ne bloque pas.

#### #125 — noms affichés en double → **CLOSABLE**
| Critère | Preuve | État |
|---|---|---|
| Doublon détecté, avertissement non bloquant sur les deux fiches | `NpcDisplayNames` ; `NpcsCatalogTest.bothCardsOfASharedDisplayNameCarryANonBlockingWarningNamingTheOtherId`, `aSharedNameIsNeverRefused` | A |
| Le diagnostic donne les ID en conflit | `NpcDiagnosticProvider` (`DUPLICATE_DISPLAY_NAME`) ; `theDiagnosticPageListsTheConflictingIds` | A |
| Avertissement avant d'enregistrer (création, renommage) | `theCreateAndRenameFormsWarnBeforeSavingWhenTheNameIsTaken` (l'affichage pendant la frappe n'est pas testé) | A / N |
| Une vraie collision d'ID reste bloquante | `NpcDefinitionStoreTest.createRefusesToOverwriteAnExistingId`, `aSecondNpcWithTheSameNameGetsTheNextFreeId` | A |

Résidu : contrôle facultatif d'une minute, sans écriture : taper « Robert » dans « Créer un PNJ » sans valider.

#### #127 — rôle métier extensible → **CLOSABLE**
| Critère | Preuve | État |
|---|---|---|
| Libellé libre (« Cartographe »), plus de liste fermée | `AgentPages.npcRoleField` ; `NpcRolesTest`, `NpcsCatalogTest.theRoleIsAFreeLabelShownInTheListAndTheSheet` | A |
| Distinct des capacités, visible sur la fiche et dans la liste | Même test (« aucun pouvoir ») | A |
| Compatible avec l'existant, validé | `NpcDefinitionParser.parseRole` inchangé ; `everySuggestionRoundTripsThroughTheStoredFormat`, `emptyMeansNoRoleAndUnusableInputIsRefusedNotTruncated` | A |

Résidu : aucun. Afficher le rôle en jeu reste une idée hors périmètre.

#### #120, #128, #129 — fiche, attente et `/dialogues` à jour après changement de dialogue → **PARTIAL (une seule vérification groupée)**
| Ticket | Critère | Preuve | État |
|---|---|---|---|
| #120 | La fiche se met à jour seule après changement de dialogue | `npc.definition.update` → `npc.list`, `dialogue.list` ; `CatalogResyncTest.npcDialogueReferenceChangeAlsoRefreshesTheDialogueCatalog` ; `resync-feedback.test.js` (**hors CI**) | A ; P seulement indirect (rattachement Musty) |
| #120 | Plus de bouton « Catalogue RPGQuest », mécanisme réutilisable | `theMainPageHasOneCreateButtonOneRefreshAndNoTechnicalClutter` ; `AgentActionCatalogTest.crossCatalogDependencies…` | A |
| #128 | Attente visible, état final (à jour, délai dépassé, échec) | `panel.js` l. 132-195 ; `resync-feedback.test.js` (35 contrôles, hors CI) | A |
| #128 | Pas de F5 dans le cas normal | Musty 22/10 | **P** |
| #129 | `/npcs` et `/dialogues` suivent un ajout, un remplacement ou un retrait | `CatalogResyncTest.npcDialogueReferenceChangeAlsoRefreshesTheDialogueCatalog`, `DialogueCatalogTest` (`linkedNpcIds`) | A |
| tous | Diagnostics recalculés, téléphone | Rendu à l'affichage, sans test ; rendus faits par Claude | N |

Résidu : **une seule vérification d'environ 2 min (TC-370 + TC-367), qui écrit sur DEV de façon réversible**. Sur un PNJ de test : Fiche → Réglages avancés → dialogue A, puis B, puis « Aucun ». À chaque étape, sans F5 : le toast d'attente puis « Affichage à jour », la fiche à jour, `/dialogues` à jour. Remettre ensuite l'état initial. Si elle passe, les trois tickets se ferment.

#### #126 — entrée PNJ orpheline ou ambiguë → **PARTIAL (même correctif que #113)**
| Critère | Preuve | État |
|---|---|---|
| L'entrée orpheline dit pourquoi elle existe | `NpcCatalog` l. 264-292 (`NO_DEFINITION`, `DIALOGUE_WITHOUT_NPC`) ; `NpcCatalogTest.anUnclaimedDialogueStillShowsUpButExplainsItself` | A |
| Au moins un chemin de correction | Action rapide « Créer la définition » (`NpcDiagnosticProvider` l. 64, `&fix=create`, vérifié) vers une fiche que `/npcs` n'affiche pas ; `fix` n'est lu nulle part ; aucune action pour `DIALOGUE_WITHOUT_NPC` | **M (lien cassé)** |
| Pas de fausse fiche, pas de doublon silencieux | Orphelins exclus de la liste (#325) ; aucun test | N |
| Test d'un orphelin avec correction | Absent | M |

Résidu, regroupé avec #113 (page 8), sans écriture sur DEV :
- faire pointer les actions rapides vers de vrais formulaires (`/dialogues/edit/<slug>` « Donner ce dialogue à un personnage », ou la quête qui cite le PNJ) ;
- ajouter un test avec un orphelin ;
- réécrire `pnj-depannage.md` l. 259-266.

#### #130 — réplique ajoutée visible sans F5 → **PARTIAL (dépend de #730)**
| Critère | Preuve | État |
|---|---|---|
| Le nœud ajouté apparaît seul, compteur et `NODE_UNREACHABLE` justes | `dc84b8e1` ; `DialoguesCatalogTest.addedNodeShowsUpOnTheGraphPageAfterTheAutomaticResync` | A + P (éditeur, Musty 22/10) |
| Aucun faux succès | Musty 22/10 : faux « la conversation s'arrête » puis `INVALID_CONTENT` → **#730** (TC-403 à tester) | M → #730 |
| Modifier ou supprimer | `refreshTypes` déclarés ; seul l'ajout est testé de bout en bout | A partiel |

Le propriétaire a écrit le 22/10 : « Ne pas affirmer la validation de #130 ». Résidu : après TC-403, refaire la recette Musty (publication, **écrit sur DEV**). Autre option : fermer sur la preuve technique, le graphe n'étant plus qu'un outil de diagnostic (#332), et laisser le reste à #730.

### Économie (page 7)

#### #138 — monnaie physique (objet PDC) → **OPEN (évolution future)**
Les 9 critères sont **M** : pas de `PhysicalCurrencyService` ni de clé `rpgquest:currency`. Seule existe l'analyse du 17/10 (PR #526), avec 8 décisions du propriétaire en attente. Le portefeuille `EconomyService` reste la source de vérité. Point à trancher : `/money pay` est déjà actif.

#### #139 — protection de la monnaie physique → **OPEN (dépend de #138)**
Tous les critères sont **M** (analyse PR #527, 7 questions). Le constat connexe « `/market sell` accepte les objets soulbound » est **vérifié** (`MarketService.sell` l. 106-114) et désormais suivi dans le ticket ciblé **#852**.

#### #140 — monnaie dans PlugAdmin → **PARTIAL**
| Critère | Preuve | État |
|---|---|---|
| Créditer ou débiter un joueur par action typée, raison obligatoire | `b7686c4d` ; `EconomyAdminTest` (`aValidCreditCarriesItsAmountAndReason`, `aReasonIsMandatoryBecauseItLandsInTheLedger`…) | A |
| Permissions dédiées, aucune commande libre | `ECONOMY_READ`/`ECONOMY_WRITE` ; `readingABalanceAndCreatingMoneyAreTwoDifferentPermissions` | A |
| Définition de la monnaie physique | Dépend de #138 | M |
| Téléphone, recette TC-249 (18 étapes) | Aucune | N / M |

Résidu : TC-249 avec un compte dédié (**écrit sur DEV** : un crédit puis un débit de test). Il faudra ensuite fermer la partie portefeuille et renvoyer la partie monnaie physique au lot 5 de #138.

---

## Matrice — page 6

### Villes, histoire, progression

#### #178 — villes : catalogue, découverte, DISCOVER_CITY → **PARTIAL**
| Critère | Preuve | État |
|---|---|---|
| Outil de ville, centre, nom à l'enclume, visible dans PlugAdmin | `city/CityTool`, `CityToolListener`, `CityNamePrompt` ; `CitiesPageTest` | A + **P** (création) |
| Périmètre (d = 50 par défaut, 5–500) | `CityGeometryTest.defaultDistanceIsFiftyInEachDirection`, `distanceLimitsAreValidated` | A |
| Renommage avec ID stable, activation/désactivation | `discoveryPersistsAcrossReconnectionAndRestartAndRename`, `inactiveCityIsNotDiscoveredUntilReactivated` | A + **P** |
| « Bienvenue à … » une seule fois, à pied ou après un voyage | `firstEntryOnFootDiscoversOnceAndReentryDoesNot`, `teleportIntoACityDiscoversIt` | A + **P** |
| Taille, déplacement, aperçu en particules, suppression | `CitiesPageTest.renameAndResizeAreQueued…`, `deletionRequiresAnExplicitConfirmation…` | A |
| Villes qui se chevauchent : un seul titre | `overlapPicksTheNearestCenter…`, `overlappingCitiesAnnounceOneAtATime` | A (TC-301 étape 9 non faite) |
| **DISCOVER_CITY** (ville précise ou X villes, `NEW_ONLY`/`INCLUDE_EXISTING`, une fois par ville) | `QuestDiscoverCityObjectiveTest` ; `DiscoverCityEditorTest.bothFormsRoundTrip…` ; `threeDistinctCitiesCompleteAndARediscoveryNeverCounts` | A (TC-301 étapes 10–11 non faites) |
| Règle lue par le joueur, nom des quêtes dans le suivi | `ObjectiveLabels` l. 77-80 sans test ville ; `oneDiscoveryAdvancesSeveralQuestsAtOnce` ne vérifie pas les noms | N |
| PNJ central et point d'arrivée distinct | Sortis du lot le 13/10 → **#322** | O |

Les validations du propriétaire citées ici sont celles de son commentaire #813 de 18:32. **Elles n'étaient pas encore au registre** : elles y sont maintenant reportées (ligne #178).

Résidu : DISCOVER_CITY en jeu (TC-301 étapes 10–11, **écrit sur DEV** : quête de test `docs/manual-tests/quests/test_discover_city.yml`), chevauchement, nom des quêtes dans le suivi.

#### #180 — histoire de Lily (5 chapitres vers Julius) → **PARTIAL (bug de contenu)**
| Critère | Preuve | État |
|---|---|---|
| Cinq quêtes successives + story `lily_souvenirs` | Parcours du propriétaire les 16 et 17/10 | P |
| Apprentissage réel des cultures | Aucun objectif « récolter une culture mûre » (vérification `Ageable` seulement dans `FarmingXpListener`) | M |
| Dialogues qui suivent l'histoire | PR #488 ; validé le 17/10 | P (sauf l'accueil) |
| Fin vers Julius, Nerana et sa direction | `%city_name:…%`, `%city_direction:…%` ; validé le 17/10 | P |
| Lieu de l'attaque, destination dans le Journal | Absents ; la description de `lily_julius` dit « une autre ville » | M |
| **État final cohérent après réouverture** | **Reproduit par le propriétaire le 17/10** : on revient à la présentation initiale (à ne pas redemander) | M (bug) |

Résidu : le correctif est **de contenu seulement**, dans `lily.yml`. Le moteur gère déjà `entries` conditionnelles (#158), `QUEST_STATE`, `SET_VARIABLE` et `VARIABLE_EQUALS`. Il faut un accueil d'après-histoire conditionné à `lily_julius COMPLETED`, et une entrée par chapitre. La publication **écrit sur DEV**.

Décisions en attente :
- Julius est-il le chef de Nerana ?
- que faire de `lily_pumpkin` ?
- la culture réelle mérite-t-elle un ticket dédié ?
- Nerana dans la description : texte fixe, ou valeurs dynamiques (code) ?

#### #181 — EPIC arène de survie → **OPEN (évolution future)**
Les 7 critères sont **M** : aucun code `arena`, aucun commit. Pas de doublon. Dépend de #178/#322, #180 et #169.

#### #182 — PNJ de ville et points de compétence → **OPEN (conception)**
Analyse seulement (PR #528, 8 questions au propriétaire), aucun code. Le constat `required-level` est traité dans #532.

#### #183 — XP RPG persistante, XP vanilla supprimée → **PARTIAL**
| Critère | Preuve | État |
|---|---|---|
| Mort : niveau et progression RPG inchangés | Aucun `PlayerDeathEvent` dans `progression/` ; XP vanilla remise à 0 à la mort ; aucun test mort + RPG | N |
| Reconnexion et redémarrage sans perte ni double gain | `ProgressionServiceTest.xpSurvivesUnloadAndReload…`, `awardingTheSameEventIdTwiceOnlyGrantsOnce` | A |
| Aucune XP vanilla en parallèle | `VanillaXpBlockListener` ; `VanillaXpBlockListenerTest` | A (TC-347 N) |
| Enchantement, enclume, raccommodage | `docs/PROGRESSION.md` ; décision du propriétaire en attente | N |
| Migration documentée | `existingVanillaLevelsAreNeverTouched` ; le champ `xp-reward` des mobs reste éditable mais n'a plus d'effet | A / N |
| Jauge lisible, progression sans commande | `ProgressionGaugeTest`, PR #680 | A + **P** (TC-384, 21/10) |

Résidu :
- TC-347 (2 min ; ne modifie que la progression du testeur) ;
- deux décisions : anciens niveaux vanilla, enchantements ;
- `xp-reward` inerte mais toujours éditable ;
- #532.

La ligne « TC-384 à tester » du registre est mise à jour (validé le 21/10).

#### #184 — HUD pièces et niveau RPG → **PARTIAL (décision)**
- Bourse et niveau visibles dans le Journal : **P** (TC-384, 21/10 : « 89 LodyCoins, niveau RPG 13 »).
- `/hud sidebar` : **A** (`HudTextTest`, `PlayerHudServiceIntegrationTest`), TC-346 non fait.
- Choix final : **M**, `hud.default-mode: "off"`.

Si le Journal suffit comme affichage final, le ticket devient CLOSABLE, `/hud` restant optionnel et désactivé par défaut.

#### #185 — objectif « découvrir X waypoints » → **PARTIAL (recette seulement)**
| Critère | Preuve | État |
|---|---|---|
| Créer et rouvrir dans le panel, quantité et filtres conservés | `DiscoverWaypointRoundTripTest` (8) ; correctif #240 (PR #260 `2c5848a`) | A (TC-282 N) |
| Découvertes distinctes, re-clic sans effet, même biome compté à part | `threeDistinctDiscoveriesComplete…`, `reclickingAnAlreadyDiscoveredWaypointNeverProgresses`, `twoWaypointsOfTheSameBiomeCountSeparately` | A |
| Monde exclu, modes nouvelles découvertes / historique | `anExcludedWorldNeverProgresses`, `existingDiscoveriesDoNotCountInNewOnlyMode`, `existingDiscoveriesCountInCumulativeMode` | A |
| Persistance, publication à chaud | `progressIsPersistedAcrossAReconnection`, PR #304 | A |
| Recette en jeu | TC-260 bloqué par le propriétaire le 11/10 (d'où #240), TC-282 PENDING | M |

Résidu : TC-282 puis TC-260 (environ 2 min, **écrit sur DEV**). Fermer **#240** en même temps.

### Mobs et boss (#169)

Sous-tickets **fermés** : #168, #195, #295, #461, #542. **Ouverts** : #170 à #177, #190, #471, #214, #229, #230, #102, #422, #474, et l'EPIC #540 (#541, #543 à #553).

| Ticket | Statut | Preuves clés | Déjà validé par le propriétaire | Résidu |
|---|---|---|---|---|
| **#169** EPIC | **OPEN** | — | Noms colorés, BossBar, Zombie Runner, TC-364 (20/10), nettoyage, boucle boss + table (1 joueur) | Sous-tickets ; **annonce de victoire sans biome ni waypoint** (`CommunityBossMessages.defeated()`, M) ; lots selon le cycle d'une quête répétable (M) |
| **#170** capacités lot 2+ | **OPEN** → candidat DUPLICATE de #540 | `MobAbilityType` = 5 capacités ; charge, onde de choc, totem, saut déjà dans #544/#545/#547/#548 | — | Environ 12 capacités sans ticket dans #540 : les y reporter, puis fermer #170 |
| **#171** boss de quête | **PARTIAL** | `KillQuestBossObjective`, PR #484 ; `acceptedObjectiveSpawnsOneOwnedBoss…`, `repeatedReconcileRetryAndPendingSearchNeverCreateADuplicate`, `twoPlayersOnTheSameQuestGetTwoDistinctBosses`, `restartRestoresTheSameBossWithoutDuplicate` (A) ; mort du joueur sans test (N) | Rien | 5 règles à décider (aide extérieure, 64 blocs, 60 s, plafond 40, double butin), puis TC-337 (**écrit sur DEV**) |
| **#172** profils + éditeur | **PARTIAL** | Champs et validations `SpecialMobDefinition` (A) ; `BukkitAgentActionsMobTest.createsAProfileOnEachCompatiblePassiveBase` ; `clearTestInstancesRemovesOnlyTestInstancesAndKeepsTheExcludedOnes` | Création **par import** (zz542, 19/10), apparition de test, nettoyage, recherche et filtres | « Nouveau profil » jamais essayé (critère du propriétaire du 05/10) ; duplication M ; formulaire adapté au type M |
| **#173** Enragé + renforts | **PARTIAL** | `AbilityEngineTest` (chance, délai, plafond, Hub, zone) (A) ; `EnragedExecutor` sans test (N) ; non-cascade sans test (N) | Enragé à l'œil (zz542_fissible, 19/10) | Renforts jamais vus en jeu ; tests du seuil et de la non-cascade |
| **#174** tirage Wild | **PARTIAL** | Bridage, tirage pondéré, BOSS exclu, plafond par profil (A) ; plafond global `atGlobalSpecialCap` sans test (N) | Apparition naturelle (04/10) | **Monde non vérifié** (voir #851) ; test du plafond global |
| **#175** boss communautaire | **PARTIAL (réserve #471, ne pas fermer)** | `CommunityBossScheduleTest`, `bossSpawnsInTheWildWhileEveryoneIsInTheHub…` (PR #476), `restartRestoresTheLockSoTheCapStillHolds`, `CommunityBossPageTest` | Apparition de test + annonce biome et waypoint (19/10), victoire (16/10), sélecteur, TC-364 | TC-334 (personne dans le Wild), apparition automatique observée, rappel à la connexion, redémarrage |
| **#176** participation | **PARTIAL (ne pas fermer)** | `oneEffectiveHitIsEnoughAndTheFinalBlowIsNotRequired`, `theRadiusIsInclusiveAndThreeDimensional`, `aPlayerWhoDiedThenHitAgainIsEligible`, `aFailedDeclarationIsRetriedWithTheSameKeys` | 1 joueur : 3 pommes reçues une seule fois, conservées après reconnexion (19/10) | TC-330 à deux joueurs (> 100 blocs, mort puis nouveau coup, inventaire plein) |
| **#177** tables de récompenses | **PARTIAL** | `RewardTableEditorTest` (13), `RewardTableDrawTest`, `QuestTableRewardIntegrationTest` (6) | Éditeur, 110 % refusé, association au boss, tirage reçu, traçabilité (19/10) | Tirage par une quête en jeu ; inventaire plein ; redémarrage ; multi-joueur ; XP et argent (M) ; suppression (M) |
| **#190** Zombie fissile | **PARTIAL** | `SplitOnHitExecutorTest`, `ExplosiveOnAttackExecutorTest` (A) | Cochon : poursuite + explosion sans casse (zz542_cochon, 19/10) ; division bornée sur zz542_fissible | Le **vrai** `splitting_zombie` n'a plus de capacité sur DEV (export du 19/10) et le modèle fourni a `max-depth: 2` ; poule et grenouille jamais essayées |

Détail par critère : analyse complète conservée dans les notes de la session. Les preuves clés sont ci-dessus, et aucun ticket de ce groupe n'est proposé à la fermeture.

### Éditeurs et divers

#### #162 — création de dialogues bloquée par les droits AWS → **PARTIAL (petit correctif de texte)**
| Critère | Preuve | État |
|---|---|---|
| `/dialogues/new` modifiable sur AWS | `PanelApp#dialogueRights` ; dossier `/var/lib/plugadmin` en écriture (`plugadmin.service` l. 38, #798) | **P** (Musty 22/10, #728 le 23/10) |
| Création réelle, rattachement sans écraser | `DialogueSourceMergeTest.aDialogueTheEditorCannotReadFaithfullyIsNeverOverwritten` | A + P (par le rattachement) ; `/dialogues/new` N |
| Droits conservés après redéploiement | Remplacé par #798 (dossier d'état systemd) | O + P |
| Message de dépannage | `ContentEditorPages#readOnlyHint` l. 1008-1014 cite **encore** `src/main/resources/<kind>` et `grant-content-access.sh` (vérifié) | **M (texte périmé)** |

Résidu : corriger le texte pour donner la vraie racine (`PLUGADMIN_CONTENT_DIR/<kind>`), avec un test. À regrouper avec le correctif #113/#126.

#### #163 — prérequis de quête par recherche → **CLOSABLE**
| Critère | Preuve | État |
|---|---|---|
| Recherche par nom ou ID, sans saisir d'ID | `ContentEditorPages` l. 468-480 (`multiSelect`, `dl-quest`) ; `MergedCatalogTest.newSourceQuestIsAvailableAsPrerequisiteLookup` | A (le widget JS n'a pas de test) |
| Plusieurs prérequis conservés à la réouverture | Une valeur par ligne, doublons ignorés ; `ContentYamlRoundTripTest` | A |
| Cycles et auto-référence refusés avec explication | `QuestValidator` l. 143 ; `QuestPrereqGraphTest` (9), `validatorBlocksAnIndirectCycleWithAnExplicitError` | A |
| Référence inconnue visible, jamais effacée | Puce `chip-unknown` ; `unknownPrerequisiteIsNeverTurnedIntoAnInventedCycle` | A |
| YAML compatible avec le moteur | `QuestYaml` l. 50-55 | A |

Résidu : coup d'œil facultatif d'une minute (le registre le classait déjà « à fermer après un coup d'œil »).

#### #164 — attribution PNJ, quêtes neuves absentes, Jeff absent → **CLOSABLE**
| Critère | Preuve | État |
|---|---|---|
| Une quête enregistrée est visible et recherchable sans redémarrage | Catalogue fusionné (`AgentPages` l. 5975-6011) ; `MergedCatalogTest.questCreatedFromEditorShowsUpInCatalogWithoutAgentCall`, `runtimeRefreshKeepsSourceOnlyQuestVisible` | A |
| État source / serveur explicite | `data-origin` ; `sourceOnlyQuestNeverClaimsToBeActiveInGame` | A |
| Une quête source seule n'est pas attribuée par erreur | Expliqué dans le panel ; `UNKNOWN_QUEST` côté serveur (`QuestGiverStoreTest`, `AgentActionExecutorTest.questGiverSetValidatesAndDelegates`) | A |
| Jeff retrouvé (cause : relevé tronqué) | `AgentEndpoints.RESULT_JSON_MAX = 512 Kio` (vérifié) ; `AgentEndpointsTest.largeResultBodyStaysParsableJson`, `oversizedResultBodyIsReplacedByAValidTruncationMarker` | A |
| Relevé illisible signalé | `AgentPages#unreadableReport` (page de diagnostic), sans test | N |

Résidu : la recherche de **personnage** à la création d'un dialogue est dans #118.

#### #158 — répliques après une quête terminée et répliques d'ambiance → **PARTIAL**
- Moteur livré et testé (**A**, PR #391 `8f1fcec4`) :
  - `entries` conditionnelles : `completedQuestNoLongerShowsTheInitialRequestNorAnOffer`, `anotherPlayerKeepsTheOfferWhileTheFirstIsThanked` ;
  - `variants` sans répétition immédiate : `DialogueLineRotationTest`.
- Non prouvé (**N**) : remise en attente, quêtes répétables, redémarrage.
- **M** : aucun dialogue du dépôt n'utilise `entries`, l'éditeur guidé ne sait pas les écrire (il les conserve seulement), aucune recette.

Deux options :
- (a) publier un dialogue de test, ce qui **écrit sur DEV** ;
- (b) fermer le moteur sur preuves automatiques et ouvrir une suite « écrire entrées et variantes dans l'éditeur guidé ».

C'est distinct de #841 (Gauthier).

#### #153 — waypoint inaccessible dans les arbres du Hub → **PARTIAL**
| Critère | Preuve | État |
|---|---|---|
| Jamais dans les feuilles ni sur un tronc | `RandomSafeLocationFinder#findAccessibleColumn`/`isTreeSurface` ; `findAccessibleColumnRejectsLeafCanopyGround`, `…RejectsATrunkTop` | A |
| Accès à pied (un voisin praticable à ±1) | `hasWalkableApproach` ; `…RejectsAnIsolatedColumnWithNoWalkableNeighbor`, `…AcceptsAStepUpOrDownOfOneBlock` | A ; pente et espace devant le bouton → **#237** |
| Réparation qui garde l'ID, le nom et les découvertes | `repairMovesAnInaccessibleWaypointPreservingIdNameAndDiscoveries`, `inaccessibleDetectsABeaconOnALeafCanopyAndRepairMovesItPreservingId` | A |
| Aperçu avant réparation | `/rpgadmin travel repair … confirm` déplace directement | M |
| Structures créées avant la règle (36/143, dont 15/23 au Hub, mesure du 11/10) | Aucune preuve de réparation depuis | M |
| Recette TC-224 (survie, non OP) | PENDING | M |

Résidu :
- `/rpgadmin travel diagnose` sur DEV, en lecture : compter les structures encore inaccessibles ;
- réparation au cas par cas, **écrit sur DEV**, sur décision du propriétaire ;
- TC-224 ;
- aperçu avant réparation.

#### #166 — récompense « cœur supplémentaire » → **PARTIAL (presque prêt)**
- Moteur, panel, une seule attribution, 10 → 11 → 12, autres sources de vie et plafond : **A**.
  - Preuves : `QuestHeartRewardIntegrationTest` (8), V36 `player_heart_grants`.
  - Le propriétaire l'a testé le 16/10 (**P**) : 10 → 11, cœur conservé après mort et reconnexion, second gain refusé.
- Redémarrage : **A** (`theBonusIsRestoredFromTheDatabaseAfterTheModifierWasLost`), non vu par le propriétaire.
- Export de pack : **N**.
- Stan, un cœur par ville : **M**, évolution de contenu.

Résidu : TC-311 après un redémarrage (lecture seule), puis fermeture. La décision Stan passe dans #178 ou dans un ticket dédié.

#### #189 — annonce Discord « En chantier : mobs spéciaux et boss » → **OPEN (texte périmé, décision du propriétaire)**
Les fonctions annoncées « à venir » sont livrées mais pas toutes validées : apparition de test, boss communautaires (#175), récompenses (#176/#177), boss de quête (#171).

**Attention** : `.github/workflows/discord-soon.yml` met à jour le message Discord à **chaque modification du corps** du ticket ; une fermeture n'a aucun effet sur Discord. Trois choix : réécrire (ce qui publie), fermer en laissant le message, ou garder. Rien n'a été modifié.

#### #198 — renommer l'identifiant logique d'un PNJ → **OPEN, OBSOLETE-PROPOSED possible**
Aucun code : `NPC_IDENTITY_RENAME` ne change que le nom. La refonte #325, fermée par le propriétaire le 13/10, a tranché « Peut-on changer l'identifiant ? → Non » (`pnj-citizens.md` l. 74-82). Si l'identifiant caché suffit, le ticket peut être fermé comme obsolète ; sinon, il reste en attente à basse priorité.

---

## Synthèse pages 7 et 6

| Statut | Page 7 | Page 6 |
|---|---|---|
| **CLOSABLE** | #141, #143, #145, #125, #127 | #163, #164 |
| PARTIAL — une vérification ou une décision suffit | #120 + #128 + #129 (une vérification groupée), #136 (TC-372 + déplacement vers #152), #146 (une ligne de confirmation) | #166 (TC-311), #184 (choix d'affichage), #185 (TC-282/260) |
| PARTIAL — petit correctif sans DEV | #150 (tri français), #126 (avec #113) | #162 (texte de dépannage) |
| PARTIAL — vrai reste à faire | #131, #134, #151, #152, #130 (→ #730), #140 | #178, #180, #183, #153, #158, #171 à #177, #190 |
| OPEN | #135, #137, #122, #138, #139 | #169, #181, #182, #189 |
| DUPLICATE / OBSOLETE proposés | #147 → #232 ; #148 avec #231/#135 | #170 → #540 (après report des capacités) ; #198 obsolète si l'ID caché suffit |

### Nouveaux tickets ciblés (constats vérifiés, aucun correctif)
- **#851** : le tirage Wild des mobs spéciaux ignore le monde quand `worlds: []`. Un cochon naturel au Hub ou dans les Claims peut devenir un Cochon Creeper (`SpecialMobService.locationAllowed` l. 405). Suite de #174.
- **#852** : `/market sell` accepte les objets soulbound et permanents RPGQuest (`MarketService.sell` l. 106-114). Signalé dans l'analyse #139, sans ticket jusqu'ici.

### Commentaires de clôture proposés
- **#141** : « Clôture : `SMELT_ITEM` livré (`d9204cce`, publication à chaud PR #304), validé en jeu par le propriétaire le 24/10 (unitaire, lots, surproduction, quête finalisée). Tests `QuestSmeltObjectiveTest` (trois fours, rien hors four, attribution au joueur qui retire, reconnexion, publication à chaud), `QuestDefinitionParserTest`, `ContentPackMapperTest`, `EditorDescriptorsTest` (« Cuire un objet »). `/give`, entonnoir et multi-joueur couverts par conception : seul `FurnaceExtractEvent` crédite, et il porte le joueur (Bible). TC-259 coché. »
- **#143** : « Clôture : PR #405 (`0b25da02`). L'identifiant d'étape n'a besoin d'être unique que dans sa quête. Il est généré une fois (`step_<max+1>`), jamais renuméroté, et reste modifiable en mode avancé avec un avertissement si une étape enregistrée est renommée ou supprimée. Tests `QuestStepIdsTest`, `ContentEditorPagesTest`, `QuestLoaderTest.twoQuestsMayReuseTheSameStepId`. Règle documentée, aucune migration. »
- **#145** : « Clôture : `/dialogues` fusionne la source PlugAdmin et le relevé du jeu (`e80b4591`, puis #332). Un dialogue créé apparaît sans recharger Minecraft et reste visible après le relevé (`DialogueSourceMergeTest`). Depuis la fiche, « Créer son dialogue » présélectionne le PNJ. Recette Musty du 22/10 : dialogue modifié, publié, vu en jeu. La recherche de PNJ continue dans #118. »
- **#125** : « Clôture : PR #389 (`5258b8da`). Un nom affiché partagé est signalé sans blocage : badge, ID de l'autre PNJ, section dédiée et `DUPLICATE_DISPLAY_NAME` dans `/diagnostics`, avertissement à la création et au renommage. Une collision d'identifiant reste refusée (`NpcDefinitionStoreTest`). Tests `NpcsCatalogTest` (11). »
- **#127** : « Clôture : PR #496 (`c889a41a`). « Rôle affiché » en texte libre avec suggestions, refusé plutôt que tronqué, visible dans la liste et sur la fiche, sans aucun pouvoir. Format `npcs/*.yml` inchangé. Tests `NpcRolesTest`, `NpcsCatalogTest.theRoleIsAFreeLabelShownInTheListAndTheSheet`. Le rôle en jeu reste une idée hors périmètre. »
- **#163** : « Clôture : livré par `14b2bcb2`, toujours servi. Les prérequis se choisissent dans une sélection multiple avec recherche (titre, ID, origine). Une référence inconnue reste affichée et n'est jamais effacée. Auto-référence et cycles refusés avec leur chemin (`QuestPrereqGraphTest`, 9 cas). Une quête source est disponible sans relevé (`MergedCatalogTest`). »
- **#164** : « Clôture : cause trouvée et corrigée (`14b2bcb2`). Le relevé `dialogue.list` était tronqué en plein JSON, ce qui cachait Jeff ; la limite est passée à 512 Kio, avec un marqueur valide au-delà (`AgentEndpointsTest`). « Attribuer une quête » se fait par recherche dans le catalogue fusionné, avec l'origine affichée. Une quête source seule est refusée côté serveur (`UNKNOWN_QUEST`). La recherche de personnage continue dans #118. »

### Demande de décision groupée (une seule, à ajouter à celle de la page 8)
1. **Fermer les 7 CLOSABLE** (#141, #143, #145, #125, #127, #163, #164) avec les commentaires ci-dessus : oui / non (ou exceptions).
2. **Vérification groupée PNJ ↔ dialogue** (#120, #128, #129, environ 2 min, réversible sur DEV, par toi) : oui, ou fermeture sur la seule preuve automatique ?
3. **Lot de petits correctifs sans DEV**, joint au point 2 de la page 8 (#113, #115, #118) : #126 (actions rapides et lien orphelin), #162 (texte de dépannage), #150 (tri français dans le menu de voyage). Oui / non.
4. **Regroupements** :
   - #147 fermé au profit de #232 ;
   - #170 fermé au profit de #540, après report des capacités manquantes ;
   - #198 obsolète ;
   - #189 : réécrire (publie sur Discord), fermer ou garder.

   Oui / non pour chacun.
5. **Décisions simples** :
   - #136 : déplacer le complément densité/Wild vers #152 ;
   - #184 : le Journal suffit-il comme affichage final ?
   - #146 : TC-265/266 passés après le 08/10, et coût en $ exigé ou non ?
   - #158 : option (a) ou (b) ;
   - #166 : Stan dans #178 ou dans un nouveau ticket ?
6. **CI** : ajouter les tests JS du panel (`node --test`) au workflow : oui / non.
7. **Nouveaux tickets #851 (mobs hors Wild) et #852 (marché soulbound)** : à mettre en file, avec quelle priorité ?

Les recettes en jeu restantes (TC-247, TC-301, TC-282/260, TC-311, TC-347, TC-224, TC-330, TC-334, TC-337, TC-249) sont listées par ticket ci-dessus. Je ne les demande pas toutes : elles restent dans leurs tickets.

## Fichiers créés
- `docs/claude-reports/2026-10-25_0300_813-cloturabilite-pages-7-6.md`

## Fichiers modifiés
- `docs/SUIVI_VALIDATIONS_DEV.md` : ligne #813 dans « Priorité immédiate », validations #178 et TC-384 (#183) reportées depuis les commentaires du propriétaire, dépendances #851/#852
- `docs/claude-reports/README.md` (index)

## Base de données / migrations
Aucune.

## Configuration / données
Aucune. Aucun contenu de DEV ni de PlugAdmin touché.

## Tests automatiques
Aucun lancé : audit documentaire. Les tests cités existent à `5f91d91a`, ce qui a été vérifié par lecture et grep.

## Tests manuels à effectuer
Seulement ceux de la décision groupée (point 2) et, plus tard, ceux listés par ticket. Aucun n'a été fait par Claude : `PENDING MANUAL VALIDATION`.

## Résultat attendu
Le propriétaire répond une fois aux décisions des pages 8, 7 et 6. Les sessions suivantes ferment, corrigent ou regroupent en conséquence.

## Reset / retour à l'état initial
Sans objet.

## Déploiement VeryGames
### À transférer
Rien.
### Ne PAS transférer/altérer
Rien n'a changé côté serveur.
### Redémarrage requis
Non.
### Migration automatique
Non.

## Rollback
Revert de la PR documentaire si besoin. Les tickets #851 et #852 peuvent être fermés s'ils sont jugés inutiles.

## Logs / diagnostic
Sans objet.

## Documentation mise à jour
Registre de validation et ce rapport.

## Limitations / travail restant
- Pour le groupe Mobs, la matrice est condensée en un tableau par ticket. Les critères de chaque sous-ticket viennent de l'analyse de session ; aucun n'est proposé à la fermeture.
- Le contenu de Lily (`/var/lib/plugadmin/content`) n'a pas été relu : il n'est pas dans le dépôt. Le diagnostic de #180 repose sur le ticket et sur le moteur.
- Les 50 tickets de ces deux pages et les 10 de la page 8 sont maintenant audités. Aucune autre page n'a été demandée.

## Prochaine étape suggérée
Attendre la décision groupée du propriétaire sur #813 (pages 8, 7 et 6). Ensuite : fermetures approuvées, lot de petits correctifs PNJ/dialogues/voyage, puis #851 et #852 selon la priorité donnée.

