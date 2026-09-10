# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-10
* Heure : 19:58 (locale machine / UTC)
* Sujet : #144 — une quête créée dans la source n'apparaît pas dans le catalogue `/quests` après refresh (catalogue fusionné source + runtime)
* Statut : DONE
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel si disponible : `592d0bd` au démarrage (commits de la tâche ajoutés ensuite)
* Début de la tâche : 2026-09-10 19:01:14 (heure locale réelle)
* Fin de la tâche : 2026-09-10 20:04:00 (heure locale réelle)
* Durée totale : 01:02:46

## Demande

Ticket #144, HAUTE / BLOQUANTE pour le workflow d'édition de contenu PlugAdmin.

Repro fournie : depuis `/quests/new`, création de la quête `lily_pumpkin`
(« Une citrouille pour Lily », giver `lily`, objectif `COLLECT_ITEM PUMPKIN 1`).
Le Control Panel confirme « Quête enregistrée dans la source ». Retour `/quests` :
la quête **n'apparaît pas**. Refresh navigateur, « Rafraîchir le catalogue de
quêtes » (SUCCESS), refresh PNJ (SUCCESS), nouveau refresh navigateur : toujours
aucune `lily_pumpkin`. La sauvegarde source fonctionne, mais le catalogue ne voit
pas la nouvelle quête.

Objectif : `CRÉER → ENREGISTRER SOURCE → RETOUR CATALOGUE → VOIR IMMÉDIATEMENT →
POUVOIR L'UTILISER (story / prérequis / giver)` sans redémarrer Minecraft, **sans**
prétendre que la quête est déjà active côté plugin. Distinguer explicitement
« existe source » et « existe runtime ». Ajouter des tests ciblés (A→I).
Hors périmètre : #142, #143, #141, #109, #42, PROD, merge. Ne pas fermer #144.

## Analyse

### Audit des sources de données (confirmation de l'hypothèse du ticket)

| Chemin | Source réelle | Constat |
|---|---|---|
| `/quests` (`AgentPages.quests`) | `latestDetails(agentId, "quest.list")` — **dernier relevé runtime de l'agent uniquement** | Ne lisait **jamais** la source. |
| `/quests/new` · `/quests/save` (`ContentEditorPages` + `ContentWorkspace`) | Écrit `src/main/resources/quests/<slug>.yml` du **checkout** | OK — le fichier `lily_pumpkin.yml` était bien présent (créé par l'utilisateur `plugadmin`). |
| `/stories` (`AgentPages.stories`) | `latestDetails(agentId, "story.list")` runtime uniquement | Même problème. |
| Lookups d'édition — prérequis de quête, chaîne de story (`AgentPages.referenceData` → `RefData`) | `quest.list` / `npc.list` runtime uniquement | Une quête source-only était « inconnue » → non sélectionnable, WARNING « prérequis inconnu ». |
| `RefData` | Construit dans `referenceData()` depuis `quest.list` / `npc.list` + heartbeat | — |
| `ContentWorkspace` | FS whitelisté `quests/*.yml` + `stories/*.yml` du checkout ; `list()` existait mais **n'avait aucun appelant** | — |
| Bouton « Rafraîchir le catalogue de quêtes » | Action agent `quest.list` (`AgentActionCatalog`, ligne 102) | N'interroge **que** le runtime — cohérent, mais ne pouvait jamais faire apparaître une quête source-only. |
| `content.reload` / `ACTION_CONTENT_RELOAD` | La **permission** `Permission.ACTION_CONTENT_RELOAD` existe, mais **aucune action** du catalogue ne l'utilise | Il n'existe aujourd'hui **aucun** moyen de déclencher un reload plugin depuis le panel → la correction reste 100 % côté Control Panel. |

**Diagnostic** : l'hypothèse du ticket est **confirmée**. `/quests` affichait
seulement `quest.list` (runtime) ; `/quests/new` écrit seulement le checkout source.
Aucune fusion, aucun état. Aucun changement plugin nécessaire.

## Travail effectué

1. **`SourceCatalog`** (nouveau, `panel.content`, lecture seule) — relit
   `quests/*.yml` + `stories/*.yml` du checkout via les **mêmes** relecteurs que
   l'éditeur (`QuestYaml.read` / `StoryYaml.read`). Expose `QuestSource` /
   `StorySource` (slug + brouillon relu + `parseOk`) avec `plainId()` (id « nu »,
   sans `rpgquest:`) comme clé de fusion. `available()` = espace de travail
   configuré. Un fichier illisible n'est jamais masqué (repli sur le slug).
   Jamais d'écriture, jamais de FTP, jamais de `content.reload`.

2. **Fusion dans `AgentPages`** :
   - `mergeQuestRows()` / `mergeStoryRows()` : union runtime + source sur l'id nu.
     Ordre = quêtes runtime (dans l'ordre du relevé) puis quêtes « source
     uniquement » (triées par slug). Chaque entrée → `MergedRow(data, CatalogState)`
     où `data` est une **ligne au format `quest.list` structuré** (réutilisation
     directe du rendu existant `ObjectiveText` / `RewardText` / `renderQuestAccordionItem`).
   - `CatalogState` : `SYNCED` (source + serveur, aucun badge), `SOURCE_ONLY`
     (badge **« Source uniquement »** `text-bg-info` + tooltip + note dans le
     corps : « pas encore chargée par le serveur DEV… l'édition et l'activation en
     jeu restent deux étapes distinctes »), `RUNTIME_ONLY` (badge **« Hors
     source »** `text-bg-warning`).
   - **Sans `content.repo-dir` configuré** : `SourceCatalog.available()` faux →
     toutes les entrées forcées à `SYNCED`, aucun badge, aucune légende (aucune
     comparaison possible — ne jamais qualifier à tort une quête de « hors source »).
   - `sourceQuestRow()` / `sourceStoryRow()` : projection `QuestDraft`/`StoryDraft`
     → map runtime-like (id `rpgquest:<plain>`, `title`, `category`, `repeatable`,
     `giverId`, `prerequisites` en `rpgquest:` , `steps[].objectiveDetails`,
     `rewardDetails`). `objectiveDetail()` / `rewardDetail()` mappent les champs de
     `Descriptors` vers `{kind,target,amount,value,command,raw}`.
   - `catalogOriginLegend()` : une phrase sous la barre du catalogue (affichée
     seulement si la source est montée).

3. **Lookups d'édition** — `referenceData()` fusionne les **quêtes de la source**
   dans `RefData.quests` + `RefData.questNames` (id nu, cohérent avec la
   comparaison `QuestYaml.plainId` de `RefData`), et `questsKnown` devient vrai dès
   qu'une source existe. Effet : `/stories/new` et le champ « prérequis » de
   `/quests/new` proposent immédiatement `lily_pumpkin` (datalist `dl-quest`), et
   `StoryValidator` ne la signale plus « inconnue ». La page `/stories` calcule
   aussi son ensemble « quêtes connues » (diagnostic « quête inconnue dans la
   chaîne ») à partir du **runtime fusionné avec la source**.

4. **Actions admin inchangées** — les listes déroulantes `quest.start` /
   `quest.complete` / `quest.reset` / `story.advance` / `story.complete` restent
   alimentées par le **runtime seul** (une entrée source-only échouerait côté
   agent ; commentaire ajouté).

5. **`PanelApp`** — construit `ContentWorkspace` avant `AgentPages` et lui injecte
   un `SourceCatalog` (5e paramètre ; l'ancien constructeur 4-args est conservé
   pour les tests avec un `SourceCatalog(null)`).

6. **Message d'état vide** reformulé en gardant la sous-chaîne « Aucun catalogue
   chargé » (tests existants).

7. **Documentation** : `docs/current_state.md`, `docs/RPGQUEST_BIBLE.md`
   (nouvelle sous-section « Catalogue fusionné source + runtime » + note sur les
   diagnostics de référence), `docs/control-panel/ROADMAP.md` (Étape 3m),
   `control-panel/src/main/resources/docs/quetes.md` (section opérateur
   « Catalogue : source et serveur (badges d'origine) » avec tableau),
   `docs/deployment/SERVER_CHANGELOG.md`, `.ai/ROADMAP.md`.

### Ce qui n'a PAS été fait (délibérément, dans le périmètre)

- **`SOURCE_MODIFIED`** (source diverge du runtime pour le même id) : écarté —
  comparaison fragile (le runtime est une projection lossy), et le ticket demande
  « au minimum : existe source / existe runtime » et « pas de taxonomie inutilement
  complexe ». Les trois états livrés suffisent.
- **Bouton « Charger sur DEV » / `content.reload`** : hors périmètre explicite du
  ticket (§8) — « ce ticket ne doit pas transformer Save en Deploy/Reload ».
- **Aucun changement plugin / agent / VeryGames.**

## Fichiers créés

- `control-panel/src/main/java/com/lodygames/rpgquest/panel/content/SourceCatalog.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/content/SourceCatalogTest.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/MergedCatalogTest.java`
- `docs/claude-reports/2026-09-10_1958_catalogue-fusionne-source-runtime-144.md` (ce fichier)

## Fichiers modifiés

- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/AgentPages.java`
  (fusion `quests()` / `stories()` / `referenceData()`, `MergedRow` / `CatalogState`,
  `mergeQuestRows` / `mergeStoryRows` / `sourceQuestRow` / `sourceStoryRow` /
  `objectiveDetail` / `rewardDetail`, badges + notes + légende, message d'état vide)
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/PanelApp.java`
  (ordre de construction + injection `SourceCatalog`)
- `control-panel/src/main/resources/docs/quetes.md`
- `docs/current_state.md`
- `docs/RPGQUEST_BIBLE.md`
- `docs/control-panel/ROADMAP.md`
- `docs/deployment/SERVER_CHANGELOG.md`
- `.ai/ROADMAP.md`

## Base de données / migrations

Aucune. `control-panel.db` non touché, `data.db` non touché, aucune migration.

## Configuration / données

Aucune nouvelle clé. Le comportement dépend de `content.repo-dir` / env
`PLUGADMIN_CONTENT_DIR` **déjà** configuré sur l'instance AWS (drop-in
`10-content-workspace.conf` → `/srv/rpgquest/repo/src/main/resources`). Sans lui,
la page fonctionne mais n'affiche aucun badge d'origine.

Deux fichiers de quête non suivis restent dans le working tree
(`src/main/resources/quests/lily_pumpkin.yml`, `.../st0_meet_people.yml`) — créés
par l'utilisateur / des sessions antérieures via l'éditeur. **Non commités** par
cette tâche (contenu, hors périmètre code). `lily_pumpkin.yml` sert de fixture à
la validation manuelle.

## Tests automatiques

- **`SourceCatalogTest`** (4) : espace non configuré → listes vides ;
  lecture d'un `quests/*.yml` + `plainId` ; fichier YAML « bizarre » jamais masqué
  (repli slug) ; lecture d'un `stories/*.yml`.
- **`MergedCatalogTest`** (11, bout-en-bout HTTP avec `PanelApp` réel + login
  owner + espace de travail temporaire) :
  - A quête runtime uniquement → visible + badge « Hors source » ;
  - B quête source uniquement → visible + badge « Source uniquement » + note +
    objectif relu ;
  - C source + runtime (même id) → **une seule** entrée fusionnée, compteur = 1,
    aucun badge ;
  - D création via `/quests/save` → `/quests` la montre **sans** aucun appel agent ;
  - E nouvelle quête source → présente dans le lookup `/stories/new` (`dl-quest`) ;
  - F nouvelle quête source → présente dans le lookup prérequis de `/quests/new` ;
  - G refresh runtime (`quest.list` SUCCESS sans la quête) → la source-only reste
    visible, compteur = 2 ;
  - H la source-only n'affirme **jamais** être active en jeu (tooltip « pas encore
    chargée par le serveur DEV ») ;
  - I édition du fichier source existant → `/quests` reflète le nouveau titre
    (relecture à chaque affichage) ;
  - story source-only → visible + badge.
- `:control-panel:test` : **368 / 0** (dont les 15 nouveaux) — suite complète verte.
- `./gradlew build` : **BUILD SUCCESSFUL in 14m 36s** (2e essai — `--no-daemon`,
  `RPGQUEST_TEST_MAX_HEAP=640m` ; le 1er essai a été tué par l'OOM connu de la box,
  `NoSuchFileException … in-progress-results-generic.bin`). Root `:test` vert,
  `:control-panel:test` 368/0, `:web-api` up-to-date.
- `:control-panel:build` : vert (checkstyle inclus).

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — session owner navigateur (mot de passe non détenu ;
couvert par `MergedCatalogTest` en HTTP authentifié) :

1. `/quests` → `lily_pumpkin` (« Une citrouille pour Lily ») visible avec le badge
   **« Source uniquement »** ;
2. tooltip / note du détail : indique clairement qu'elle n'est pas encore chargée
   par le serveur DEV ;
3. `/stories/new` → `lily_pumpkin` recherchable dans la sélection de quêtes
   (par titre ou par id) et ajoutable à une chaîne ;
4. cliquer « Rafraîchir le catalogue de quêtes » → `lily_pumpkin` **reste** visible
   (le refresh runtime ne l'efface pas).

## Résultat attendu

Le workflow `créer → enregistrer source → retour catalogue → voir immédiatement →
utiliser en story / prérequis` fonctionne sans redémarrer Minecraft. La quête
source-only est clairement étiquetée « pas encore active en jeu ». « Rafraîchir »
interroge toujours le serveur ; la source est relue à chaque affichage.

## Reset / retour à l'état initial

- Code : `git revert` des commits de la tâche, ou `rollback.sh app` côté AWS.
- Aucune donnée à nettoyer (pas de migration, pas d'écriture).

## Déploiement VeryGames

### À transférer
Rien. **Aucun changement plugin.** VeryGames / Minecraft **non concernés**.

### Ne PAS transférer/altérer
Le JAR RPGQuest, `data.db`, les mondes, la config serveur, les définitions de
quêtes de production.

### Redémarrage requis
Aucun redémarrage Minecraft.

### Migration automatique
Aucune.

## Rollback

Control Panel AWS : `scripts/plugadmin/rollback.sh app` → release
`20260910-195741`. Aucune base à restaurer.

## Logs / diagnostic

`journalctl -u plugadmin` — 0 `ERROR` / `SEVERE` / `Exception` / `WARN` depuis le
redéploiement.

## Documentation mise à jour

`docs/current_state.md`, `docs/RPGQUEST_BIBLE.md`, `docs/control-panel/ROADMAP.md`
(Étape 3m), `control-panel/src/main/resources/docs/quetes.md`,
`docs/deployment/SERVER_CHANGELOG.md`, `.ai/ROADMAP.md`, index
`docs/claude-reports/README.md`.

## Limitations / travail restant

- État `SOURCE_MODIFIED` non implémenté (comparaison fragile ; non demandé).
- Pas de bouton « Charger sur DEV » (hors périmètre #144 — édition ≠ activation).
- Validation navigateur owner : `PENDING MANUAL VALIDATION`.
- `#144` reste **ouverte** (l'utilisateur gère GitHub) ; aucun merge.

## Prochaine étape suggérée

Validation manuelle owner de la checklist ci-dessus, puis reprise du pipeline de
contenus (#109 import) ou de l'action agent `quest.definition.validate` (#46).

## Exécution réelle (déploiement)

Déploiement AWS Control Panel effectué le **2026-09-10 ~19:58 UTC** depuis
`feat/control-panel-admin-tools` via `scripts/plugadmin/deploy.sh` :
`:control-panel:installDist` **BUILD SUCCESSFUL**, app précédente sous
`/opt/plugadmin/releases/20260910-195741`, `systemctl restart plugadmin` →
`active (running)` (PID 860171, ~69 M), drop-in `10-content-workspace.conf`
chargé. `/health` **ONLINE** local **et** public
(`https://plugadmin.lodylands.com/health`) ; `/quests`, `/quests/new`, `/stories`,
`/stories/new` anonymes → **303** `/login` ; JAR déployé contient `SourceCatalog`,
`AgentPages$CatalogState`, `AgentPages$MergedRow` ; **0** erreur au journal.
`control-panel.db` non touché. **VeryGames / Minecraft non touchés.**
