# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-08
* Heure : 15:55 (heure locale de la machine de build AWS)
* Sujet : atelier IA corrigé (correction, identifiant, nombre de nœuds), cohérence PNJ ↔ dialogue, et suppression sûre d'un PNJ depuis le Control Panel
* Statut : **DONE** (code et tests automatisés) — les cinq tickets restent **ouverts** jusqu'à TC-267
* Branche Git : `fix/222-atelier-ia-et-suppression-pnj` (poussée, **jamais fusionnée**)
* Commit actuel si disponible : `3e06304`
* Début de la tâche : 2026-10-08 14:31:48
* Fin de la tâche : 2026-10-08 16:__:__ *(voir la ligne « Durée totale » — horodatage réel de fin de session)*
* Durée totale : *(renseignée en fin de session)*

> Les tickets couverts sont **#222**, **#223**, **#224**, **#225** et **#226**, dans cet ordre
> logique, et strictement dans ce périmètre.

---

## Demande

Cinq tickets ouverts le matin même par les tests réels de l'utilisateur (TC-265 et TC-266) :

| Ticket | Symptôme constaté en test réel |
|---|---|
| #222 | « Demander une correction » ne relançait pas correctement une proposition invalide — aucune correction exploitable, retour manuel au formulaire obligatoire |
| #223 | le champ « Identifiant souhaité » était ambigu : `rpgquest:tc265_…` menait à un refus, `tc265_…` fonctionnait, et l'aide ne le disait pas |
| #224 | dialogue Mira, **5 nœuds demandés**, **4 générés** — et la proposition était acceptée |
| #225 | deux entrées « Mira » dans `/npcs` (`mira_cartographer` définie + Citizens #9, et `mira_first_map` « sans définition »), et l'atelier voulait créer un **troisième** objet : un dialogue `rpgquest:mira_cartographer` |
| #226 | aucun moyen de supprimer un PNJ depuis le Control Panel |

Consigne explicite : **« Commence par l'audit réel du cas Mira avant tout changement
destructif. »** Elle a été respectée à la lettre — l'audit est la première chose faite, sur les
données de production, et **rien n'a été supprimé ni modifié** pendant celui-ci.

---

## Analyse

### 1. L'audit Mira, sur les données réelles (fait avant tout changement)

Les définitions PNJ ne vivent **pas** dans le dépôt : `src/main/resources/npcs/` ne contient que
`guard.yml`, et `npcs/` n'est même pas dans les `ReadWritePaths` du service PlugAdmin. Mira existe
donc **uniquement sur le serveur Minecraft**, et la seule trace exploitable depuis cette machine
est le dernier relevé `npc.list` conservé par le panel.

Méthode : snapshot en lecture seule de `/var/lib/plugadmin/control-panel.db` vers le répertoire de
travail temporaire, puis extraction du dernier `npc.list` **réussi** — action
`b9c15a45-de48-4717-a8cf-28f64bd764af`, `2026-10-08T12:13:21Z`. Aucune écriture, aucune commande
serveur, aucune suppression.

Les deux lignes, telles que le serveur les a renvoyées :

```json
{ "id": "mira_first_map", "displayName": "Mira",
  "logicalDefinitionPresent": false, "citizensBindingPresent": false,
  "citizensNumericId": null, "definedDialogueId": null,
  "hasDialogue": true, "dialogueId": "rpgquest:mira_first_map",
  "dialogueNodes": 2, "dialogueChoices": 3,
  "sources": ["DIALOGUE"], "state": "UNDEFINED_REFERENCE",
  "warnings": [{ "code": "NO_DEFINITION", "severity": "error", … }] }

{ "id": "mira_cartographer", "displayName": "Mira la Cartographe",
  "logicalDefinitionPresent": true, "citizensBindingPresent": true,
  "citizensNumericId": 9, "bindingCount": 1,
  "definedDialogueId": "rpgquest:mira_first_map",
  "hasDialogue": false, "dialogueId": null,
  "dialogueNodes": 0, "dialogueChoices": 0,
  "sources": ["DEFINITION", "BINDING"], "state": "LINKED", "warnings": [] }
```

#### Diagnostic exact de la seconde entrée Mira

**`mira_first_map` n'est pas un PNJ. C'est le dialogue lui-même, déduit en PNJ par le
catalogue.**

La cause est dans `NpcCatalog.build`, et elle tient en deux lignes :

1. `BukkitAgentActions.npcDefinitions()` construit chaque `DialogueLink` avec
   `new DialogueLink(d.id().getKey(), d.id().toString(), …)` — autrement dit **la clé du dialogue
   est utilisée comme identifiant de PNJ porteur**, par convention ;
2. `NpcCatalog.build` faisait `canonical.addAll(dialogueByNpc.keySet())` — donc **tout** dialogue
   chargé produisait une entrée de catalogue, qu'un PNJ le réclame ou non.

Le dialogue `rpgquest:mira_first_map` fabriquait donc une entrée `mira_first_map`, sans définition
(`sources: ["DIALOGUE"]`), dont le `displayName` « Mira » venait du `speaker` du dialogue.

**Et le symptôme miroir, que le ticket ne mentionnait pas** : la même convention appliquée dans
l'autre sens faisait que `mira_cartographer` ressortait avec `hasDialogue: false`,
`dialogueId: null`, **0 nœud** — alors qu'elle déclare parfaitement `rpgquest:mira_first_map` dans
son `definedDialogueId`, et que les joueurs entendent ce dialogue. Le catalogue cherchait
`dialogueByNpc.get("mira_cartographer")`, ne trouvait rien, et concluait « aucun dialogue ». La
fiche affichait alors, au mieux, « Dialogue déclaré … (pas encore chargé en jeu) » — ce qui était
**faux** : il était chargé.

Les deux défauts ont donc **une** cause : le rattachement PNJ → dialogue n'était lu que par la
convention de nom, jamais par la déclaration de la fiche. Et c'est précisément pourquoi personne ne
pouvait corriger l'entrée fantôme : elle n'était la faute de personne.

**Conclusion de l'audit : il n'y avait rien à supprimer.** Ni fichier en trop, ni donnée
corrompue, ni ancien binding. Une déduction fausse, et deux affichages faux. Le correctif est dans
le code du catalogue, pas dans les données.

### 2. #222 — trois causes cumulées, pas une

Le bouton **appelait bien** la bonne route avec le bon CSRF. Ce qui ne marchait pas :

1. **Les diagnostics ne traversaient pas.** La page émettait un champ caché `problem` **par
   problème**. Or `Http.parseUrlEncoded` construit une `Map<String, String>` : des champs
   homonymes s'écrasent, et **un seul** survivait. L'IA recevait donc un problème sur cinq. C'était
   le seul endroit du panel à faire ça — l'import, lui, utilise des noms distincts
   (`decision.<clé>`), donc rien d'autre n'était affecté.
2. **Les consignes d'origine étaient perdues.** `ContentPromptBuilder.correctionPrompt` ne recevait
   que la famille, la sortie précédente et les diagnostics. Le modèle réparait donc l'erreur
   signalée **en oubliant** l'identifiant imposé, le titre exact, le nombre de nœuds — et la
   nouvelle proposition était refusée pour une **autre** raison. Vu de l'utilisateur : « le bouton
   ne sert à rien ».
3. **Le formulaire se rouvrait vide.** Sur un POST `_action=correct`, `questForm` /
   `dialogueForm` / `storyForm` restaient `null`, et `AiStudioPages.render` les recevait tels
   quels. L'utilisateur devait tout retaper — ce qu'il décrit exactement dans le ticket.

Un quatrième défaut, découvert en chemin : si l'appel de correction échouait lui-même (401,
timeout), `Generation.correctable()` était faux (pas de YAML), donc **le bouton disparaissait avec
la proposition précédente**. Un échec réseau faisait perdre le travail.

### 3. #223 — le comportement était correct, l'UX mentait

`QuestYaml.nsId` ne double jamais le namespace, et `ContentPackImport.slugOf` refuse à juste titre
`testia:securiser_environs`. Le problème était ailleurs :

- le placeholder du champ montrait `rpgquest:mines_oubliees`, donc **invitait** à écrire le
  namespace ;
- l'aide disait « Minuscules, chiffres et « _ » » — ce qui contredit le placeholder ;
- et **rien n'était vérifié avant l'appel** : une saisie invalide partait chez le fournisseur,
  consommait des jetons, et n'échouait qu'à la validation du document produit.

À quoi s'ajoute une asymétrie non documentée : une **quête** s'écrit `rpgquest:clé` dans un pack,
une **story** et un **dialogue** s'écrivent en clé nue (`ContentPackImport.slugOf` ne retire le
namespace que pour la famille `quests`).

### 4. #224 — le prompt demandait, personne ne vérifiait

Le champ était transmis comme « Nombre de nœuds **souhaité** », et rien ne recomptait la réponse.
Un modèle qui produit 4 nœuds sur 5 demandés produit un document **parfaitement valide** : les
validateurs ne voient que le document, pas la demande. Il fallait donc un contrôle d'un genre
nouveau — comparer la proposition à **la demande**, et non à la grammaire.

### 5. #226 — tout est côté serveur

Trois constats qui ont déterminé l'architecture :

- les définitions PNJ ne sont **pas** dans l'espace de travail éditable du panel (seuls
  `quests/`, `stories/`, `dialogues/` y sont). Le modèle de #194 — analyser le workspace, écrire,
  sauvegarder localement — **ne s'applique pas** ;
- supprimer un PNJ touche cinq couches, dont une entité de plugin tiers (Citizens) ;
- le panel ne sait pas supprimer un dialogue, et c'est volontaire.

D'où : les **principes** de #194 sont réutilisés (plan séparé de l'exécution, aperçu complet,
blocages explicites, confirmation tapée, jamais de cascade, progression joueur intouchée), mais
l'exécution passe par des **actions agent whitelistées**, et la sauvegarde a lieu côté serveur.

---

## Travail effectué

### #222 — la correction relance réellement

- `ContentPromptBuilder.Demand` : interface **scellée** implémentée par `QuestRequest`,
  `DialogueRequest` et `StoryRequest`. Chaque demande sait produire son bloc « CONSIGNES
  PRÉCISES » (`instructions()`) et les contraintes que le backend vérifiera (`imposed()`). Les
  trois surcharges de `userPrompt` deviennent **une** méthode.
- `correctionPrompt(Demand, previousYaml, problems, refs)` réinjecte l'intention **et** le bloc de
  consignes d'origine, annoncés « toujours impératives ».
- `AiContentStudio.Correction` : record portant la demande d'origine, le fournisseur, la sortie
  refusée et les diagnostics. Il voyage dans le formulaire, et sert de **repli** si l'appel de
  correction échoue — le bouton reste alors disponible (« Relancer la correction »).
- Les diagnostics tiennent dans **un seul** champ `problems`, une ligne par problème, relu par
  `split("\\R")`. `Http.parseUrlEncoded` n'a **pas** été touché : le changer aurait un rayon
  d'impact large pour un seul appelant fautif.
- Génération et correction lisent le formulaire par le **même** chemin dans `handleAiStudio` :
  une correction ne peut plus perdre un champ, et l'écran se rouvre rempli.
- L'audit `ai.correct` porte en plus le nombre d'exigences non tenues et, en cas de refus, leur
  texte.

### #223 — l'identifiant, normalisé avant l'appel

- `panel/content/ContentId` : accepte `clé` et `rpgquest:clé`, ne double jamais le namespace,
  refuse un namespace étranger (en **proposant la clé à écrire**), refuse `rpgquest:rpgquest:…`,
  refuse un caractère interdit. `quest()` rend la forme namespacée, `dialogue()` et `story()` la
  clé nue — chacune la forme que l'import attend réellement.
- `ContentId.KEY_PATTERN` est **la** constante utilisée par `ContentPackImport.slugOf` : une clé
  acceptée à la saisie ne peut donc plus être refusée plus loin, ce qui était exactement le piège.
- Le refus arrive **avant** l'appel : aucun jeton n'est consommé.
- Placeholders et aides refaits pour les trois familles, y compris l'asymétrie quête / story.

### #224 — le nombre de nœuds est recompté

- `panel/ai/AiConstraints` : porte l'identifiant imposé et le nombre de nœuds, et `verify(kind,
  packYaml)` rend les exigences non tenues.
- Les nœuds sont comptés via **`DialogueYaml.fromMap`**, c'est-à-dire le lecteur qui servira à
  l'import : on compte la vraie map `nodes`, jamais un identifiant cité dans un `next`.
- L'identifiant imposé est comparé sur la forme **normalisée** : `ma_quete` et
  `rpgquest:ma_quete` sont la même réponse ; un **autre** identifiant est un écart.
- `Generation.unmet` + `Generation.acceptable()` : un écart bloque l'enregistrement aussi
  fermement qu'une erreur de validation, et passe **en tête** des problèmes renvoyés à l'IA.
- Le prompt dit « Nombre de nœuds IMPÉRATIF : exactement N … recompté après ta réponse ».
- `MiniYaml` et sa méthode `parse` passent en `public` (même paquet de contenu, aucun invariant à
  protéger) pour que l'atelier puisse relire sa propre proposition.

### #225 — le dialogue réellement lié

**Côté moteur (`NpcCatalog`)** :

- un index `dialogueById` et un index `dialogueClaimedBy` (dialogue déclaré → PNJ qui le déclare) ;
- le dialogue d'une fiche est **le déclaré d'abord**, la convention de nom ensuite ;
- un dialogue **revendiqué** par une définition ne produit plus d'entrée de catalogue ;
- un dialogue que personne ne revendique en produit toujours une — cas de transition légitime —
  mais avec une anomalie **`DIALOGUE_WITHOUT_NPC`** qui nomme la cause *et* les deux remèdes.

**Les remises deviennent visibles** : `QuestLink.deliverNpcIds` et `NpcRow.questsDelivering`,
alimentés par `DeliverItemToNpcObjective` dans `BukkitAgentActions`, source `QUEST_DELIVER`,
sérialisés dans le payload `npc.list`. Avant, un PNJ destinataire d'un `DELIVER_ITEM_TO_NPC`
n'apparaissait dans **aucune** colonne du catalogue.

**Côté panel** :

- `panel/npc/NpcView` + `NpcDirectory` : projection typée du relevé, avec `linkedDialogueId()`,
  `dialogueNamedDifferently()`, `provenance()` (en français), `npcsLinkedToDialogue()` (dialogue
  partagé) et `relatedEntries()` (ce qui relie les deux Mira) ;
- `panel/npc/DialogueTargetDecision` : quand le dialogue lié ne porte pas le nom du PNJ, la
  décision est **bloquante et arrive avant l'appel**. Deux options seulement — modifier
  l'existant, ou créer un nouveau dialogue en remplaçant le lien — parce que le moteur n'en
  supporte que deux. Le « dialogue supplémentaire non lié » est refusé, et l'écran dit pourquoi :
  il ne serait joignable par personne et réapparaîtrait comme entrée sans définition ;
- le champ du formulaire demande désormais le **PNJ porteur**, non « l'identifiant du dialogue » ;
- la règle de prompt « l'id du dialogue EST l'id du PNJ » est remplacée par « c'est celui qui
  t'est IMPOSÉ … ne le déduis pas du nom du PNJ ».

### #226 — supprimer un PNJ, couche par couche

**Côté plugin — trois actions agent whitelistées** :

| Action | Ce qu'elle fait | Garde-fou |
|---|---|---|
| `npc.definition.delete` | supprime `npcs/<id>.yml` après **sauvegarde** dans `npc-backups/` | **revalide** les références de contenu sur les moteurs en mémoire, et refuse en nommant les quêtes ; `expect_dialogue` confronté à la réalité |
| `npc.citizens.unlink` | retire la liaison, laisse vivre l'entité | exige l'identifiant Citizens attendu ; `MISMATCH` sinon |
| `npc.citizens.delete` | détruit l'entité et retire sa liaison | résout l'entité par la **liaison en base**, puis `destroyIfMatches(numericId, uuid)` — **double clé** |

`NpcDefinitionStore.deleteDefinition(id, backupDir)` sauvegarde **avant** de supprimer : si la
copie échoue, rien n'est supprimé. Le dossier de sauvegarde est `<dataFolder>/npc-backups/`,
volontairement **hors** de `npcs/` — un fichier de sauvegarde que le chargeur relirait recréerait
le PNJ qu'on vient de supprimer. Les trois actions sont **idempotentes** (`ABSENT` / `MISMATCH`).

**Côté panel** :

- `Permission.NPC_DELETE`, accordée à `OWNER` et `ADMIN` ; refusée au `CONTENT_EDITOR`, au
  `BUILDER` et au `TESTER`. `npc.citizens.delete` est enregistrée en
  **`addOrchestratedWrite`** et exige **en plus** `NPC_SPAWN_WRITE` : détruire une entité est
  l'inverse exact de sa création ;
- `panel/npc/NpcDeletionPlan` + `NpcDeletionAnalyzer` : les cinq couches (présentes **et**
  absentes), les quatre opérations avec leurs blocages et leurs effets énumérés, et les notes ;
- `panel/web/NpcDeletionPages` : aperçu, confirmation tapée, page de résultat ;
- route `/npcs/delete`, et une **Zone de danger** sur la fiche PNJ qui n'est qu'un **lien** vers
  l'aperçu — rien ne se supprime depuis la liste ;
- le plan est **recalculé au POST** : une URL forgée vers une opération que l'aperçu bloque est
  refusée, et le refus est audité ;
- `DiagnosticHelp` gagne l'entrée `DIALOGUE_WITHOUT_NPC`, qui pointe la fiche de dépannage.

### Correctif d'affichage trouvé en chemin

`Ui.banner` n'acceptait que `ok` / `err` / `warn` / `info`, et retombait **en silence** sur `info`
pour les noms longs `error` / `warning` / `success`. Or l'atelier IA n'utilise que les noms longs
depuis #146 : **tous** ses bandeaux d'erreur — échec d'appel, 401, timeout, refus de validation —
s'affichaient en bleu « information ». Visibles, mais pas comme un problème. Les deux vocabulaires
sont désormais acceptés, ce qui corrige chaque appel existant d'un coup.

---

## Fichiers créés

**Plugin** — aucun.

**Control Panel (main)**

* `control-panel/src/main/java/com/lodygames/rpgquest/panel/content/ContentId.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/ai/AiConstraints.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/npc/NpcView.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/npc/NpcDirectory.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/npc/DialogueTargetDecision.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/npc/NpcDeletionPlan.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/npc/NpcDeletionAnalyzer.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/NpcDeletionPages.java`

**Tests**

* `control-panel/src/test/java/com/lodygames/rpgquest/panel/content/ContentIdTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/npc/NpcDirectoryTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/npc/DialogueTargetDecisionTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/npc/NpcDeletionAnalyzerTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/NpcDeletionPageTest.java`

**Documentation**

* `docs/claude-reports/2026-10-08_1555_atelier-ia-corrige-pnj-dialogue-suppression-pnj.md` (ce
  fichier)

## Fichiers modifiés

**Plugin**

* `src/main/java/com/lodygames/rpgquest/npc/NpcCatalog.java` — dialogue déclaré prioritaire ; un
  dialogue revendiqué ne produit plus d'entrée ; `DIALOGUE_WITHOUT_NPC` ; `questsDelivering`
* `src/main/java/com/lodygames/rpgquest/npc/NpcDefinitionStore.java` —
  `deleteDefinition(id, backupDir)`
* `src/main/java/com/lodygames/rpgquest/npc/NpcIdentityService.java` — `unbindCitizens`,
  `bindingOf`, `destroyBoundCitizens`, `forgetBinding`
* `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionType.java` — trois nouveaux types
* `src/main/java/com/lodygames/rpgquest/web/agent/AgentActions.java` — trois méthodes +
  `questsDelivering` dans `NpcSummary`
* `src/main/java/com/lodygames/rpgquest/web/agent/BukkitAgentActions.java` — implémentations,
  revalidation des références, collecte des remises
* `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionExecutor.java` — dispatch +
  validation des paramètres + sérialisation de `questsDelivering`

**Control Panel**

* `panel/ai/AiContentStudio.java` — `generate`/`correct` unifiés, `Correction`, `unmet`,
  `acceptable()`
* `panel/ai/ContentPromptBuilder.java` — `Demand` scellée, `instructions()`, `imposed()`,
  `correctionPrompt` avec les consignes d'origine, règle de dialogue réécrite
* `panel/content/ContentPackImport.java` — `slugOf` utilise `ContentId.KEY_PATTERN`
* `panel/content/MiniYaml.java` — `parse` public
* `panel/authz/Permission.java`, `panel/authz/Role.java` — `NPC_DELETE`
* `panel/agent/AgentActionCatalog.java` — trois actions + validation des paramètres
* `panel/web/AiStudioPages.java` — champ PNJ, aides, carte d'arbitrage, formulaire de correction
  complet, bandeaux d'écart
* `panel/web/PanelApp.java` — `handleAiStudio` refait, route et handler `/npcs/delete`
* `panel/web/AgentPages.java` — `npcDirectory()`, Zone de danger sur la fiche PNJ
* `panel/web/DiagnosticHelp.java` — entrée `DIALOGUE_WITHOUT_NPC`
* `panel/web/Ui.java` — `banner` accepte les deux vocabulaires

**Tests modifiés** : `AiContentStudioTest`, `AiPagesTest`, `NpcCatalogTest`,
`NpcDefinitionStoreTest`, `AgentActionExecutorTest`, `NpcListPayloadTest`,
`NpcCitizensPayloadTest`, `StubAgentActions`.

**Documentation modifiée**

* `docs/RPGQUEST_BIBLE.md` — atelier IA (contraintes vérifiées, correction, arbitrage PNJ) ;
  deux sections nouvelles : « Supprimer un PNJ (#226) » et « Catalogue PNJ : le dialogue
  réellement lié (#225) »
* `docs/current_state.md`
* `docs/deployment/SERVER_CHANGELOG.md` — entrée du 2026-10-08 (après-midi)
* `docs/MANUAL_TEST_PLAN.md` — **TC-267** + index
* `control-panel/src/main/resources/docs/pnj-depannage.md` — fiche « Dialogue sans PNJ porteur »
  (avec le tableau des deux entrées Mira)
* `control-panel/src/main/resources/docs/pnj-citizens.md` — « Quel dialogue un PNJ porte-t-il
  vraiment ? » et « Supprimer un PNJ »
* `.ai/ROADMAP.md`
* `docs/claude-reports/README.md` — index

## Base de données / migrations

**Aucune.** Aucun `SchemaMigrator`, aucune table ajoutée ou modifiée, ni côté plugin (`data.db`)
ni côté panel (`control-panel.db`). La table `npc_citizens_bindings` est lue et écrite par les
chemins existants.

## Configuration / données

**Aucun changement de configuration.** Aucun fichier de contenu créé ou modifié : les définitions
PNJ, dialogues, quêtes et stories du serveur sont inchangés.

Un **nouveau dossier** apparaîtra côté serveur à la première suppression de définition :
`plugins/RPGQuest/npc-backups/`. Il est créé à la demande, hors du dossier lu par le chargeur.

---

## Tests automatiques

Exécution depuis un **worktree propre** (`git worktree add --detach`) sur le commit `3e06304`,
avec `RPGQUEST_TEST_MAX_HEAP=768m`, **un seul Gradle à la fois**. Le worktree propre est
nécessaire : les fichiers de contenu non suivis présents dans l'arbre de travail de l'utilisateur
font échouer `CrystalHuntIntegrationTest`.

*(Nombres exacts renseignés en fin de session — voir la section « Résultat de la suite ».)*

### Ce que les tests couvrent réellement

**#222** — correction valide qui revient acceptable ; correction encore invalide qui ressort avec
ses **nouveaux** diagnostics (et pas les anciens) ; même fournisseur et même modèle conservés ;
consignes d'origine **présentes dans le prompt** (identifiant imposé, titre, contraintes libres,
intention, mention « toujours impératives ») ; erreur d'API visible sans rien écrire ; contexte de
correction incomplet refusé **sans appeler** ; formulaire réaffiché rempli ; absence de champs
`problem` homonymes dans la page.

**#223** — ID local, ID namespacé, jamais de double namespace, namespace étranger refusé avec la
clé proposée, caractères interdits refusés **avant** l'appel, clé nue pour story et dialogue,
cohérence littérale avec l'expression de l'import.

**#224** — `0` laisse libre ; exactement N accepté ; **N-1** et **N+1** refusés avec le compte
exact ; borne maximale du formulaire ; le prompt déclare la contrainte impérative ; un écart
n'écrit rien.

**#225** — le cas Mira reproduit **à l'identique depuis le relevé réel**, côté moteur
(`NpcCatalogTest`) et côté panel (`NpcDirectoryTest`) : une seule entrée, dialogue résolu, nœuds et
choix corrects, aucune anomalie, provenance de l'entrée fantôme explicitée ; convention de nom
préservée quand rien n'est déclaré ; dialogue déclaré mais absent → toujours `BROKEN` ; dialogue
partagé par deux définitions vu par les deux ; décision bloquante avant appel, et les deux options
seulement.

**#226** — suppression avec sauvegarde **avant** suppression ; refus sans dossier de sauvegarde
(rien n'est touché) ; idempotence ; un voisin jamais touché ; validation des paramètres des trois
actions ; aperçu listant les cinq couches ; dépendance de remise bloquante **sans formulaire** ;
CSRF ; identifiant mal retapé refusé ; **autre** identifiant Citizens refusé ; URL forgée vers une
opération bloquée refusée ; chaque opération ne met en file **que** ses actions ; double clic
inoffensif ; permission dédiée vérifiée rôle par rôle.

Aucun test ne sort sur le réseau : l'atelier IA est testé avec un **fournisseur bouchon**.

## Tests manuels à effectuer

**TC-267** — `PENDING MANUAL VALIDATION`, rédigé dans `docs/MANUAL_TEST_PLAN.md`. Sept étapes :
correction IA réelle, identifiant local puis namespacé, nombre exact de nœuds, **consultation** de
la fiche Mira, création d'un PNJ de test complet, sa suppression couche par couche, et
vérification finale que Mira n'a rien perdu.

> 🚫 **La partie suppression se fait exclusivement sur le PNJ de test créé à l'étape 5.** Mira, le
> Garde, le Guide, Jo, Lily et Jeff sont hors périmètre : Mira n'est que **consultée**.

TC-265 et TC-266 restent également à faire.

## Résultat attendu

- une proposition refusée se corrige **sans ressaisie**, en conservant ce qui était imposé ;
- `tc265_ai_securiser_environs` et `rpgquest:tc265_ai_securiser_environs` fonctionnent tous deux,
  et une saisie invalide est refusée **sans dépenser de jetons** ;
- « 5 nœuds » donne 5 nœuds, ou un refus qui dit combien ont été produits ;
- **une seule** entrée Mira dans `/npcs`, avec son dialogue `rpgquest:mira_first_map` affiché ;
- un PNJ de test se supprime proprement depuis `/npcs`, couche par couche, sans commande
  Minecraft, sans SQL et sans édition manuelle de YAML — et le dialogue survit toujours.

## Reset / retour à l'état initial

Rien à réinitialiser : aucune donnée n'est écrite par ce lot. Pour défaire les effets des
suppressions faites pendant TC-267 :

- une **définition** supprimée se restaure en recopiant le fichier daté de
  `plugins/RPGQuest/npc-backups/` vers `plugins/RPGQuest/npcs/`, puis `/rpgadmin reload npcs` (ou
  redémarrage) ;
- une **liaison** retirée se recrée depuis la fiche PNJ (« Lier un PNJ Citizens ») ;
- un **PNJ Citizens détruit** ne se restaure pas : il faut le recréer (`/npc create`, skin,
  position) puis le relier. C'est pourquoi l'opération C exige la double confirmation.

---

## Déploiement VeryGames

> **Aucun déploiement n'a été effectué par cette session.** Ce qui suit est la procédure à
> exécuter sur demande.

Contrairement aux trois lots précédents, ce lot **touche le plugin** : il y a un JAR à déployer et
un redémarrage Minecraft à faire, **et** un déploiement du Control Panel.

### À transférer

1. **JAR RPGQuest**, construit depuis un arbre propre sur `3e06304` —
   `scripts/deploy-verygames.sh` (avec `RPGQUEST_TEST_MAX_HEAP=768m` et `-y`), qui fait le backup
   daté du JAR en place.
2. **Control Panel** — `scripts/plugadmin/deploy.sh`.

### Ne PAS transférer/altérer

- `plugins/RPGQuest/npcs/`, `dialogues/`, `quests/`, `stories/` — **aucun** fichier de contenu
  n'est concerné par ce lot ;
- `data.db` — aucune migration ;
- la configuration Citizens, et `plugins/Citizens/saves.yml` ;
- `plugins/RPGQuest/config.yml`.

### Redémarrage requis

**Oui, côté Minecraft** : le catalogue PNJ et les trois nouvelles actions agent vivent dans le
plugin. Sans redémarrage, le panel proposera la Zone de danger mais l'agent refusera les trois
actions comme inconnues, et le catalogue continuera d'afficher l'entrée fantôme.

Le Control Panel se redémarre seul via son script de déploiement.

### Migration automatique

**Aucune.**

### Vérification après déploiement

1. `/health` du panel ;
2. `PNJ → Rafraîchir` : la fiche `mira_cartographer` affiche `rpgquest:mira_first_map` avec ses 2
   nœuds et 3 choix, et l'entrée `mira_first_map` « sans définition » a **disparu** ;
3. une fiche PNJ montre une **Zone de danger** avec « Supprimer… » ;
4. en jeu, parler à Mira : le dialogue s'ouvre.

## Rollback

- **Plugin** : redéployer le JAR sauvegardé, puis redémarrer Minecraft. Le catalogue retrouve son
  ancienne déduction (donc ses entrées fantômes), et les trois actions redeviennent inconnues de
  l'agent — le panel les verrait refusées, ce qui est le comportement normal pour une action qu'un
  agent ne connaît pas.
- **Panel** : `scripts/plugadmin/rollback.sh app` puis `systemctl restart plugadmin`.
- **Aucune donnée n'est concernée** par un rollback : ce lot n'écrit rien de lui-même.

## Logs / diagnostic

- Audit panel : `ai.generate`, `ai.correct` (avec le nombre d'exigences non tenues), et
  `npc.delete` — **y compris les refus**, ce qui est précisément ce qu'on veut pouvoir relire.
- Log applicatif : `event=npc_delete rid=… npc=… op=… citizens=… actions=… by=…`.
- Journal d'actions du panel : le verdict réel de chaque suppression (asynchrone).
- Côté serveur : `plugins/RPGQuest/npc-backups/` contient une copie datée par définition
  supprimée.

## Documentation mise à jour

Voir « Fichiers modifiés → Documentation ». Les deux fiches du centre d'aide du panel sont à jour,
et la nouvelle anomalie `DIALOGUE_WITHOUT_NPC` pointe vers une section dédiée — l'ancre est dérivée
du titre, comme le veut le registre unique de `DiagnosticHelp`.

## Limitations / travail restant

1. **Le nombre d'étapes d'une quête reste une indication**, non vérifiée — seul le nombre de nœuds
   d'un dialogue est une contrainte. C'est le périmètre de #224, et le choix est délibéré : un
   objectif de plus ou de moins est souvent ce qui rend une quête jouable, alors qu'un nœud de
   dialogue en trop ou en moins est un défaut de structure. Le formulaire le dit désormais
   explicitement, pour ne pas reproduire l'ambiguïté de #224 à l'étage voisin.
2. **Les suppressions de PNJ sont asynchrones.** La page annonce une *demande* ; le verdict se lit
   dans le journal d'actions. Un retour synchrone demanderait un aller-retour bloquant avec
   l'agent, que le panel ne fait nulle part ailleurs.
3. **Le panel ne sait toujours pas supprimer un dialogue**, et c'est volontaire. Conséquence
   assumée : supprimer la définition d'un PNJ dont le dialogue porte son nom fait réapparaître ce
   dialogue comme entrée « sans définition » — désormais avec une anomalie qui l'explique et
   propose ses remèdes.
4. **Plusieurs liaisons Citizens sur un même identifiant** : seule celle dont l'identifiant
   numérique est affiché est traitée. L'aperçu le dit en toutes lettres plutôt que de prétendre
   nettoyer les autres.
5. **Après le redémarrage, le compteur de PNJ va baisser** (les entrées fantômes disparaissent).
   Ce n'est pas une perte de données — c'est la déduction qui était fausse, pas les fichiers.
6. **Aucun des cinq tickets ne peut être fermé** avant TC-267. #225 et #226 demandent explicitement
   une validation manuelle avec un PNJ de test.
7. Dettes rappelées, hors périmètre : `MiniYaml` ne gère pas les scalaires repliés (`text: >`),
   donc `dialogues/guard.yml` n'est pas éditable depuis le panel ; `RestartServiceTest` reste
   sensible au temps réel.

## Prochaine étape suggérée

1. Déployer : JAR + **redémarrage Minecraft** + Control Panel.
2. Faire **TC-267**, dans l'ordre des sept étapes — en particulier l'étape 4 (fiche Mira) avant
   toute suppression, et l'étape 7 (Mira intacte) après.
3. Selon les résultats, fermer #222, #223, #224, #225, #226 — jamais avant.
4. Reprendre TC-265 et TC-266, qui bloquent encore #146.
