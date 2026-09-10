# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-10
* Heure : 10:32 (heure locale de la machine de build)
* Sujet : Issue #46 — passe UX ciblée sur l'éditeur guidé de **stories** (`/stories/new`, `/stories/edit/...`)
* Statut : DONE (code + tests + docs + déploiement AWS Control Panel) — validation navigateur owner `PENDING MANUAL VALIDATION`
* Branche Git : `feat/control-panel-admin-tools` (consigne : y rester, ne rien merger)
* Commit au démarrage : `2bf16e0`
* Commit de code : `007a419`
* Début de la tâche : 2026-09-10 10:17:05
* Fin de la tâche : 2026-09-10 11:14:41
* Durée totale : 00:57:36 (dont ~00:18:00 de `./gradlew build` sur la box contrainte, dont un premier essai OOM `Java heap space` sur `:test` relancé avec `RPGQUEST_TEST_MAX_HEAP=768m`)

## Demande

Après la passe UX de l'éditeur de **quêtes** (validée manuellement, déployée), faire la même passe
ciblée sur l'éditeur guidé de **stories**. Control Panel uniquement, testable au navigateur, sans
toucher VeryGames / Minecraft / le JAR. Points :

1. Auditer l'éditeur de stories existant (champs réels du modèle, ordre des quêtes, références,
   validation, preview YAML, diff, hash de conflit, écriture atomique, scroll, actions de
   brouillon) — ne pas réécrire l'architecture si les mécanismes de l'éditeur de quêtes sont
   réutilisables ; refléter **strictement** le modèle et le parser Story réels, ne rien inventer.
2. UX cible identique à l'éditeur de quêtes : construire le brouillon sans validation bloquante →
   Vérifier → Aperçu (YAML + diff) → Enregistrer si valide. Même ergonomie Bootstrap, même
   comportement mobile.
3. Sélection **recherchable** des quêtes existantes, par **titre humain** et par **id technique**,
   affichage type « Premiers pas / first_steps » (pas seulement l'id).
4. Ajout / suppression / réordonnancement possibles, y compris sur une story incomplète, sans
   déclencher la validation métier complète, sans blocage `required` navigateur.
5. Ordre des quêtes visuellement évident (rang, monter / descendre / retirer), mobile-first, pas
   de drag-and-drop.
6. Doublons / références : respecter le comportement réel du moteur — l'empêcher/signaler si une
   quête ne peut apparaître qu'une fois, ne pas inventer d'interdiction si c'est autorisé ; une
   référence inexistante doit être détectée à Vérifier / Aperçu / Enregistrer.
7. Réutiliser le composant de recherche léger de #46 (pas de framework / CDN).
8. Conservation du contexte (scroll) après ajout / suppression / monter / descendre.
9. Validation métier complète uniquement à Vérifier / Aperçu / Enregistrer, selon le vrai
   parser/modèle.
10. Aperçu YAML + diff + hash de conflit + détection de modification externe + round-trip +
    écriture atomique conservés ; l'aperçu doit correspondre exactement au YAML sauvegardé.
11. Cohérence avec le catalogue `/stories` (voir, ouvrir le détail, Modifier, Créer) — pas de
    refonte si déjà correct.
12. Tests ciblés Stories, puis `./gradlew :control-panel:test` / `test` / `build` verts.
13. Déployer **uniquement** le Control Panel AWS (`scripts/plugadmin/deploy.sh`), vérifier
    `/health`, `/stories`, `/stories/new`, `/stories/edit/...`.
14. Validation navigateur laissée à l'utilisateur ; tests HTTP/rendu à défaut de compte owner.
15. Hors périmètre : #109 import, #110 génération IA, changement de format Content Pack, refonte
    du panel, migration DB, VeryGames, Minecraft. Ne pas fermer #46, ne rien merger. Commit/push
    sur `feat/control-panel-admin-tools`.

## Analyse

### État réel de l'éditeur de stories (audit)

L'éditeur de stories partage **déjà** toute l'infrastructure de l'éditeur de quêtes :

- **Modèle** : `StoryDraft` (`id`, `name`, `secret`, `List<String> questIds`) reprend fidèlement
  le modèle moteur `story.model.StoryDefinition` (`id`, `LocalizedText name`,
  `List<NamespacedKey> questIds`, `secret`). `story.StoryDefinitionParser` : `id` obligatoire,
  `name` obligatoire, `quests` = liste d'au moins un id (namespace `rpgquest:` par défaut),
  `secret` optionnel — **rien d'autre**. Le parser **ne résout jamais** les id de quête contre le
  moteur de quête (indépendance de l'ordre de démarrage) et **n'interdit pas les doublons** (c'est
  une simple `List`).
- **Sérialisation / round-trip** : `StoryYaml.write` / `read` / `roundTripProblems` produisent
  exactement la forme attendue par `StoryDefinitionParser` (`id` nu, `name` cité, `quests:` liste
  avec `rpgquest:` explicite) ; garde-fou round-trip identique à celui des quêtes.
- **Validation** : `StoryValidator` reproduit les contrôles du parser (id `[a-z0-9_-]+`, nom
  obligatoire, ≥ 1 quête, format d'id de quête) et ajoute la cohérence de référence via `RefData`
  (quête inconnue → `WARNING`, doublon → `WARNING`, aucun relevé `quest.list` → `INFO`).
- **Écriture** : `ContentWorkspace` (whitelist `stories/*.yml` du checkout Git, hash SHA-256,
  refus de conflit / d'écrasement, écriture atomique, jamais de FTP ni de déploiement).
- **Formulaire** : `ContentEditorPages.renderStory` — sections Général / Chaîne de quêtes /
  Validation & aperçu ; `<form class="editor" novalidate>`, aucun `required` (V4) ; actions de
  brouillon `add_q` / `del_q` / `mv_q` avec `formnovalidate` + `formaction="/stories/save#<ancre>"` ;
  conservation du scroll par `panel.js` (mécanisme partagé, V4) ; aperçu YAML + diff + hash de
  conflit via `renderPreview` partagé.
- **Catalogue** `/stories` (`AgentPages.stories` / `renderStoryAccordionItem`) : liste filtrable,
  détail accordéon (Identité / Chaîne de quêtes / Diagnostics / Actions), « Modifier la story »,
  « Créer une story », chaîne affichée `1. Titre [id]` avec badge « inconnue ». **Déjà cohérent.**

**Conclusion de l'audit** : l'architecture est saine et déjà couverte par
`storyEditorCreatesOrderedChain` + `storyEditorFormAlsoDisablesNativeValidation`. Deux manques
réels par rapport à la cible :

1. **§3 / §7 — la sélection des quêtes ne montre que l'id.** La datalist `dl-quest` était brute
   (`datalist("dl-quest", r.quests())`) : aucun titre humain, alors que le payload `quest.list`
   contient bien `title` par quête (utilisé ailleurs par le catalogue). Le combo `panel.js`
   filtre pourtant déjà sur le `label` — il n'était juste jamais fourni.
2. **§5 — l'ordre n'était pas assez lisible.** Chaque ligne de la chaîne n'affichait qu'un rang
   `N.` et un champ contenant l'id ; pas de titre humain.

Plus un manque de parité mineur : pas de bouton « Actualiser le formulaire » (présent côté
quêtes).

### Décisions

- **Ne pas** réécrire l'architecture ni toucher `panel.js` (le combo affiche déjà valeur + label
  et filtre sur les deux). Se limiter à : fournir les libellés, rendre l'ordre lisible côté
  serveur, ajouter le bouton de parité.
- **Ne pas** interdire les doublons (le moteur les autorise — `StoryDefinition.questIds` est une
  `List`) : garder l'avertissement `StoryValidator` existant (§6).
- **Ne pas** transformer « quête inconnue » en erreur bloquante : le parser moteur ne résout
  jamais les références → rester en `WARNING` (§6, §9), cohérent avec le catalogue `/stories`.
- Le titre humain vient du **dernier `quest.list` réussi** (données locales du panel, aucun appel
  agent supplémentaire) ; sans relevé, on retombe proprement sur l'id et aucun badge n'est affiché
  (on ne présume rien).

## Travail effectué

### `panel/content/RefData.java`

- Nouvelle composante `Map<String,String> questNames` (8ᵉ, après `npcNames`). Constructeurs
  historiques à **6** et **7** composantes conservés (délèguent avec `Map.of()`) — aucun site
  d'appel existant à changer (`ContentYamlRoundTripTest`, `ContentEditorPagesTest` compilent tels
  quels).
- `questLabel(String id)` : titre humain d'une quête à partir de son id, ou l'id lui-même si
  inconnu. Comparaison sur l'id « nu » (`QuestYaml.plainId` — namespace `rpgquest:` retiré).
- `withExtraQuests(...)` propage `questNames`.

### `panel/web/AgentPages.java`

- `referenceData(...)` construit `questNames` à partir du dernier `quest.list` réussi
  (`quests[].id` + `quests[].title`), titre nettoyé de son MiniMessage via `MiniText.plain`.

### `panel/web/ContentEditorPages.java`

- Nouvelle `questDatalist("dl-quest", ref)` : `<option value="first_steps" label="Premiers pas">`
  quand le titre est connu, `value` seul sinon (jamais de `label` vide). `sharedDatalists`
  l'utilise à la place du `datalist` brut. **Affecte uniquement l'éditeur de stories** : aucun
  descripteur d'objectif / récompense n'utilise la source `quest`, donc l'éditeur de quêtes
  (validé) n'est pas impacté.
- Nouvelle `renderStoryQuestRow(ref, questId, i, total)` (méthode courte extraite du corps de
  `renderStory`) : en-tête = `<span class="row-n">N.</span>` + `<span class="row-title">Titre</span>`
  (si connu) **au-dessus** du champ id éditable ; sinon, si le catalogue de quêtes est chargé et
  l'id absent → `<span class="badge text-bg-warning">quête inconnue</span>`. Contrôles monter /
  descendre / retirer inchangés (ancres `q-<i±1>` / `sec-chain`). Le champ garde la liste
  recherchable `dl-quest` et une aide « chercher par titre (« Premiers pas ») ou par id
  (« first_steps ») ».
- Section « Validation & aperçu » de la story : bouton **« Actualiser le formulaire »**
  (`_action=refresh`) ajouté avant « Vérifier », pour la parité avec l'éditeur de quêtes.
  Descriptions de sections précisées (aucune vérification tant qu'on n'a pas cliqué Vérifier /
  Enregistrer ; liste des contrôles de validation).

### `assets/plugadmin.css`

- `.rowitem-h .row-n` (rang, `tabular-nums`, `flex:none`) et `.rowitem-h .row-title` (titre,
  ellipsis) — deux règles, dans le bloc éditeur existant.

### Non modifié

`panel.js` (le combo affiche déjà `combo-v` + `combo-l` et filtre sur les deux) ; le catalogue
`/stories` (déjà conforme au §11) ; `StoryDraft` / `StoryYaml` / `StoryValidator` /
`ContentWorkspace` (le modèle et le parser sont déjà fidèles — §1 « ne rien inventer »).

## Fichiers créés

* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/StoryEditorPassTest.java` (17 tests)
* `docs/claude-reports/2026-09-10_1032_editeur-stories-passe-ux-46.md` (ce rapport)

## Fichiers modifiés

* `control-panel/src/main/java/com/lodygames/rpgquest/panel/content/RefData.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/AgentPages.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/ContentEditorPages.java`
* `control-panel/src/main/resources/assets/plugadmin.css`
* `docs/current_state.md` (bullet #46 « V5 »)
* `docs/RPGQUEST_BIBLE.md` (§46 — bullet « Chaîne de quêtes d'une story »)
* `.ai/ROADMAP.md` (journal 2026-09-10 soir)
* `docs/deployment/SERVER_CHANGELOG.md` (entrée 2026-09-10 — Éditeur guidé de Stories)

## Base de données / migrations

**Aucune.** `control-panel.db` non touché (l'éditeur écrit dans le checkout Git, jamais en base).
Plugin : non modifié.

## Configuration / données

Aucun changement `config.yml` / `messages.yml` / secrets. **Aucune permission ajoutée**
(`STORY_CONTENT_WRITE` existait déjà).

## Tests automatiques

Nouveau `StoryEditorPassTest` (rendu direct de `ContentEditorPages`, sans la pile HTTP — plus
rapide, permet d'injecter un `RefData` avec des titres) :

| Attendu #46 (stories) | Test |
|---|---|
| §2 rendu du formulaire guidé new + edit | `newStoryFormRendersGuidedSections`, `editFormRendersFromSourceFile` |
| §3/§7 datalist quêtes = titre humain + id | `questDatalistCarriesHumanTitlesNextToTechnicalIds`, `questDatalistFallsBackToIdWhenNoTitleKnown` |
| §3 lookup par titre **ou** id (`rpgquest:` toléré) | `questLabelResolvesTitleFromPlainOrNamespacedId` |
| §5 titre humain au-dessus de l'id dans chaque ligne | `chainRowShowsHumanTitleAboveTechnicalId` |
| §6/§9 référence inconnue signalée | `chainRowFlagsAnUnknownQuestWhenCatalogIsLoaded`, `verifyFlagsUnknownReferenceAndDuplicate` |
| §4/§8 ajout sur brouillon incomplet sans validation | `addQuestOnIncompleteDraftNeverTriggersBusinessValidation` |
| §4/§8 suppression toujours possible | `removeQuestOnIncompleteDraftAlwaysWorks` |
| §4/§5 réordonnancement | `reorderMovesRowsWithoutValidation` |
| §8 ancres de scroll sur chaque bouton structurel | `everyStructuralButtonCarriesANonEmptyScrollAnchor` |
| §4 aucun `required` navigateur bloquant | `storyEditorFormEmitsNoBlockingRequiredAttribute` |
| §9 validation finale id / nom / chaîne vide | `verifyReportsMissingIdAndNameAndEmptyChain` |
| §6 doublon (autorisé → avertissement) | `verifyFlagsUnknownReferenceAndDuplicate` |
| §10 aperçu YAML = fichier écrit + round-trip | `previewYamlMatchesWhatGetsSavedAndRoundTrips` |
| §10 diff avant sauvegarde | `previewShowsADiffAgainstTheExistingSource` |
| §10 conflit de hash (modification externe) | `staleHashIsDetectedAsConflict` |

Résultats :

- **Ciblé** : `StoryEditorPassTest` **17 / 0**, `ContentEditorPagesTest` (inchangé, dont
  `storyEditorCreatesOrderedChain`, `storyEditorFormAlsoDisablesNativeValidation`),
  `ContentYamlRoundTripTest` **verts**.
- `:control-panel:test` : **311 tests, 0 échec** (1 skip pré-existant) — +17 vs avant (294).
- `./gradlew build` : **BUILD SUCCESSFUL en 9 min 16 s** (2ᵉ essai — le 1ᵉʳ a OOM `Java heap
  space` sur le fork de `:test`, relancé avec `RPGQUEST_TEST_MAX_HEAP=768m` après
  `./gradlew --stop`). root `:test` **1282 / 0** (29 skip MariaDB gated) — inchangé (plugin non
  modifié) ; `:web-api:test` **30 / 0** — inchangé.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — session navigateur owner (mot de passe non détenu) :

1. `/stories/new` : créer une story.
2. Rechercher une quête **par titre** (« Premiers ») → « Premiers pas / first_steps » apparaît.
3. Rechercher **par id** (« first_steps ») → même résultat.
4. Ajouter plusieurs quêtes.
5. Monter / descendre une quête → l'ordre change, la page **ne repart pas en haut**.
6. Retirer une quête → retirée immédiatement, aucun message « Veuillez renseigner ce champ ».
7. Vérifier que le scroll reste sur la section « Chaîne de quêtes ».
8. « Vérifier » / « Aperçu » → diagnostics + YAML généré + diff.
9. Contrôler le YAML (`quests:` dans l'ordre affiché, `rpgquest:` explicite) et l'ordre.
10. Rendu mobile : lignes lisibles, liste de recherche dans la largeur du champ, scroll interne.

## Résultat attendu

CONSTRUIRE LA STORY (ajouter / retirer / réordonner des quêtes) → sans validation bloquante, sans
perdre sa position, avec le **titre humain** visible partout. VÉRIFIER / APERÇU → diagnostics
métier + YAML + diff. ENREGISTRER → écriture atomique whitelistée dans le checkout Git, jamais de
déploiement.

## Reset / retour à l'état initial

Revenir au commit `2bf16e0` (aucune donnée, aucune migration). L'éditeur n'écrit que dans le
checkout Git ; aucun contenu serveur touché.

## Déploiement VeryGames

### À transférer

**Rien vers VeryGames.** Déploiement **AWS Control Panel uniquement** : `scripts/plugadmin/deploy.sh`
(build + release + `systemctl restart plugadmin` + `/health`).

### Ne PAS transférer/altérer

VeryGames, Minecraft, `data.db`, mondes, Citizens, le JAR du plugin. **Aucun redémarrage Minecraft.**

### Redémarrage requis

`systemctl restart plugadmin` (via `deploy.sh`). Pas de migration.

### Migration automatique

Aucune.

### Déploiement effectué ?

**Oui — AWS Control Panel uniquement**, le 2026-09-10 ~11:14 UTC depuis `feat/control-panel-admin-tools`
@ `007a419`. `scripts/plugadmin/deploy.sh` : `:control-panel:installDist` **BUILD SUCCESSFUL**
(`compileJava` / `jar` **UP-TO-DATE**), release précédente sauvegardée sous
`/opt/plugadmin/releases/20260910-111417`, `systemctl restart plugadmin` → `active (running)`,
drop-in `10-content-workspace.conf` toujours chargé, `Memory` ~51 M.

Vérifications live : `/health` **ONLINE** local **et** public
(`https://plugadmin.lodylands.com/health`). `/stories`, `/stories/new`, `/stories/edit/<slug>`
anonymes → **303** vers `/login` (routes vivantes, auth appliquée). `assets/plugadmin.css` servi
contient `.row-title{…}` ; le JAR déployé contient `questDatalist`, `renderStoryQuestRow`,
« Actualiser le formulaire ». **0 `ERROR` / `SEVERE` / `Exception`** au journal depuis le
redéploiement. `control-panel.db` non touché. **VeryGames / Minecraft non touchés, aucun
redémarrage Minecraft.**

Rollback : `scripts/plugadmin/rollback.sh app` (→ `20260910-111417`). Détail :
`docs/deployment/SERVER_CHANGELOG.md`, entrée 2026-09-10 « Éditeur guidé de Stories », section
« Exécution réelle ».

## Rollback

`scripts/plugadmin/rollback.sh app` (release précédente). Aucun état à défaire.

## Logs / diagnostic

- Audit PlugAdmin : `stories.content.write` inchangé à l'enregistrement d'une story.
- Aucun nouveau log ; `panel.js` non modifié.

## Documentation mise à jour

`docs/current_state.md` (bullet #46 « V5 »), `docs/RPGQUEST_BIBLE.md` §46 (bullet « Chaîne de
quêtes d'une story » : sélection titre+id, ordre lisible, doublon autorisé), `.ai/ROADMAP.md`
(journal), `docs/deployment/SERVER_CHANGELOG.md` (entrée 2026-09-10).

## Limitations / travail restant

- **Validation navigateur owner non faite** (`PENDING MANUAL VALIDATION`) — couverte par les
  tests de rendu.
- Le titre humain d'une quête n'apparaît que si un `quest.list` a déjà été chargé pour l'agent
  (sinon : id seul, sans badge — on ne présume rien). Rafraîchir « Quêtes » depuis `/stories`
  suffit.
- Action agent `quest.definition.validate` (validation métier « live » côté plugin) : toujours à
  faire — **#46 reste ouverte**.
- Doublons de quête dans une chaîne : autorisés par le moteur, donc simple avertissement (choix
  assumé, aligné sur `StoryDefinition`).

## Prochaine étape suggérée

Session navigateur owner pour dérouler les 10 tests manuels ci-dessus. Puis : action agent
`quest.definition.validate` (#46), ou pipeline de contenus avec l'import #109.
