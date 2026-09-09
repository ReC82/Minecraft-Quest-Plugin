# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-09
* Heure : 09:30 → 10:00 (heure locale machine, UTC)
* Sujet : Généralisation de la refonte UX `/npcs` aux pages `/quests`, `/stories`, `/dialogues` (+ audit `/players`) et diagnostics humains actionnables via un registre `DiagnosticHelp` + 4 fiches de dépannage `/docs` (issues #89 suite / #49)
* Statut : DONE
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel si disponible : `6835c15` (code `bdbbcb9`, docs `6835c15`) ; poussé sur `origin/feat/control-panel-admin-tools`
* Début de la tâche : 2026-09-09 09:30:43
* Fin de la tâche : 2026-09-09 10:00:12
* Durée totale : 00:29:29

## Demande

Après la refonte ciblée de `/npcs` (#89), l'utilisateur demande de :

1. **Généraliser la même philosophie UX** aux pages `/dialogues`, `/stories`,
   `/quests` (et auditer `/players`, ne le refondre que si le même problème de
   densité existe). Modèle : *liste = synthèse* ; *clic sur un élément = détail* ;
   *clic sur une action = formulaire*. Jamais *liste = tous les détails +
   diagnostics + formulaires + boutons en permanence*.
2. **Rendre TOUS les diagnostics WARNING/ERROR compréhensibles et actionnables** :
   français clair, expliquer ce qui ne va pas, la conséquence, quoi faire, et un
   **lien direct vers une ancre précise de la documentation** (pas juste `/docs`).
3. **Toolbars de rafraîchissement compactes** partout (fin des grandes cartes
   vides ne contenant qu'un bouton « Rafraîchir le catalogue »).
4. **Recherche `input-group` Bootstrap** sur toutes les listes (pas d'icône qui
   chevauche le placeholder), filtres alignés.
5. **Créer un composant/registre de diagnostics** (`DiagnosticHelp`) plutôt que
   des messages codés en dur dans les templates. Composant Bootstrap : ERROR =
   rouge léger, WARNING = orange, INFO = bleu.
6. **Terminologie française** : bannir `tagué`, `binding`, `giver`, `orphan`,
   `namespaced id`, `raw`, `target`, `kind` des messages principaux.
7. **Étendre le wiki #49** : fiches « Résoudre les problèmes PNJ / dialogues /
   quêtes / stories » au format *Ce que cela signifie / Pourquoi il faut corriger
   / Comment corriger / Vérification / Référence technique*.
8. **Tests** couvrant listes compactes par défaut, détails/formulaires masqués,
   recherches, toolbars compactes, diagnostics humains, code technique
   secondaire, bouton « Comment corriger ? », `href` vers l'ancre doc précise,
   `BINDING_NO_DEFINITION`, `DIALOGUE_NO_NPC`, au moins un diagnostic
   quête/story, terminologie interdite absente, aucune régression #49 / #93.
9. Ne pas toucher au gameplay, ne pas toucher VeryGames, ne pas redémarrer
   Minecraft. **Control Panel uniquement.** Ne pas fermer #89 ni #49, ne rien
   merger, commit/push sur `feat/control-panel-admin-tools`.

## Analyse

État avant l'intervention :

* `/npcs` était déjà refondu (#89) : accordéon Bootstrap `npc-accordion` /
  `npc-item`, en-tête synthétique, détail en sections (`detailSection`,
  `dlRow`), actions = boutons dépliant un `collapse` (`actionToggle` /
  `actionCollapse`), toolbar compacte (`compactRefresh`, codé en dur pour
  `/npcs`), recherche `input-group` (`npc-search`) + filtres (`filterBtn` →
  `pa-chip`). Les diagnostics étaient rendus par un `diagAlert(severity,
  message, code)` local : message **brut du moteur** + `ID diagnostic :
  <code>`.
* `/quests`, `/stories`, `/dialogues` utilisaient encore l'ancien modèle
  `entity-card` / `entity-name` + `Ui.searchToolbar` (icône en overlay) + un
  gros bouton `actionButton(... "Rafraîchir le catalogue")`. `/dialogues`
  affichait le **graphe des nœuds ouvert d'office** (`<details open>`) et les
  erreurs de chargement dans des `<div class="banner err">` (blocs rouges
  agressifs listant des messages bruts).
* **Sources de diagnostics** : PNJ (`NpcCatalog.Warning{code,severity,message}`)
  et dialogues (`DialogueCatalog` → `warnings[]{code,severity,message}` +
  `loadIssues[]{file,message}` + `declaredButMissing[]{npcId,dialogueId}`) ont
  des codes. **`quest.list` et `story.list` n'ont AUCUN code ni load issue** :
  les diagnostics de référence des quêtes/stories doivent donc être **calculés
  côté panel** (comme les validateurs #46).
* `/players` = roster (table) + formulaires d'action (variables, give, reset).
  **Pas dense en diagnostics** → ne nécessite pas d'accordéon, juste la toolbar
  compacte et une recherche légère.

Décisions techniques :

* **`DiagnosticHelp` = un seul registre** (`Map<code, Entry>` statique). Chaque
  `Entry(code, level, title, what, consequence, action, docSlug)` ; l'**ancre**
  de doc est **dérivée du titre** (`Markdown.slug(title)`) → cohérence garantie
  entre le registre et la fiche. `render(...)` produit le composant Bootstrap
  (`alert alert-{danger|warning|info} pa-diag pa-diag-{error|warning|info}`),
  titre + « Conséquence : » + « À faire : » + boutons + `Code technique :
  <code class="tid">CODE</code>`. Code inconnu → même habillage, message brut
  du moteur conservé, `href="/docs"` générique.
* **`Markdown.slug` replie les accents** (NFD + suppression des diacritiques)
  pour des ancres propres (`#prerequis-inconnu` et non `#pr-requis-inconnu`).
  Le titre de section dans la fiche et l'ancre du registre passent par la même
  fonction → toujours alignés.
* Helpers partagés dans `AgentPages` : `listCatbar(titre, boutonsHtml)`,
  `compactRefresh(..., returnPath)` (surcharge), `listControls(scope,
  placeholder, chipsHtml)` (input-group + puces), `idKeySet` / `known` /
  `npcKeySet` (normalisation avec/sans préfixe `rpgquest:`).
* **Vérifications de référence côté panel** :
  * `QUEST_PREREQ_UNKNOWN` : un `prerequisites[]` absent du catalogue de quêtes.
  * `QUEST_GIVER_UNKNOWN` : `giverId` absent de `npc.list` (`definedIds` ∪
    `canonicalIds`) — **seulement si `npc.list` a déjà été chargé** (sinon on ne
    peut pas conclure). Bouton `[ ↻ PNJ ]` ajouté à la toolbar `/quests` (guard
    `NPC_READ`).
  * `STORY_QUEST_UNKNOWN` : une quête de `stepQuestIds[]` absente du catalogue —
    seulement si un `quest.list` a été chargé (bouton `[ ↻ Quêtes ]` ajouté).
* Réutilisation **volontaire** des classes CSS `npc-*` (`npc-accordion`,
  `npc-item`, `npc-head`, `npc-detail`, `npc-section`, `npc-dl`, `npc-actions`)
  pour les 3 autres pages : ces styles ne sont pas spécifiques aux PNJ, zéro
  duplication de CSS.

## Travail effectué

### `DiagnosticHelp` (nouveau)

* Registre de **21 entrées** couvrant : PNJ (`BINDING_NO_DEFINITION`,
  `NO_DEFINITION`, `DIALOGUE_MISSING`, `DUPLICATE_DEFINITION`,
  `DUPLICATE_BINDING`, `DISABLED`, `NOT_LINKED`, `GIVER_NO_DIALOGUE`) ;
  dialogues (`DIALOGUE_NO_NPC`, `MULTIPLE_NPCS`, `DEFINITION_DIALOGUE_DIVERGES`,
  `NEXT_MISSING`, `QUEST_REF_UNKNOWN`, `NODE_UNREACHABLE`,
  `DIALOGUE_LOAD_ISSUE`, `DIALOGUE_DECLARED_MISSING`) ; quêtes/stories
  (`QUEST_LOAD_ISSUE`, `QUEST_PREREQ_UNKNOWN`, `QUEST_GIVER_UNKNOWN`,
  `STORY_LOAD_ISSUE`, `STORY_QUEST_UNKNOWN`).
* `render(code, severity, rawMessage, subjectName, detail, fixHtml)` : composant
  Bootstrap complet. `{name}` / `{detail}` interpolés (`« … »`). Bouton
  « Comment corriger ? » (`bi-question-circle`) toujours ; `fixHtml`
  (« Corriger maintenant ») injecté par la page quand une action immédiate
  existe.
* `forCode`, `all()` (tests / génération doc), `Level.of(severity)` tolérant.

### `/npcs` (migration vers `DiagnosticHelp`)

* La section **Diagnostics** de `renderNpcAccordionItem` remplace la boucle
  `diagAlert(...)` par `DiagnosticHelp.render(code, severity, message, sujet, "",
  npcFixButton(...))`. `diagAlert` supprimé.
* Nouveau `npcFixButton(code, slug, …)` : rend « Corriger maintenant » (bouton
  `btn-primary` `bi-wrench`) qui déplie le bon formulaire d'action
  (`-f-create` / `-f-edit` / `-f-link`) selon le code et les permissions.
* Icônes `help` (`question-circle`) et `wrench` (`wrench-adjustable`) ajoutées à
  `Icons`.

### `/quests` (refonte)

* Toolbar compacte : `[ ↻ Quêtes ]` (+ `[ ↻ PNJ ]` si `NPC_READ`).
* `listControls("quests", …)` : recherche input-group + puces
  *Toutes / Sans alerte / À vérifier*.
* `renderQuestAccordionItem` : accordéon `#quests-accordion`. En-tête = titre
  humain (MiniMessage rendu) + id technique discret + badges (catégorie,
  « Donneur », N objectifs, N récompenses, répétable, `OK` / `à vérifier`).
  Détail : **Général / Donneur / Prérequis / Objectifs / Récompenses /
  Diagnostics / Actions**. Objectifs et récompenses gardent le rendu structuré
  #78 (`ObjectiveText` / `RewardText`) ; valeur technique en secondaire.
* Diagnostics `QUEST_PREREQ_UNKNOWN` / `QUEST_GIVER_UNKNOWN` calculés côté panel.
* Action : lien « Modifier la quête » (éditeur #46) si `QUEST_CONTENT_WRITE`.

### `/stories` (refonte)

* Toolbar `[ ↻ Stories ] [ ↻ Quêtes ]`. `listControls("stories", …)`.
* `renderStoryAccordionItem` : accordéon `#stories-accordion`. En-tête = titre +
  id + N quêtes + `OK` / `à vérifier`. Détail : **Identité / Chaîne de quêtes /
  Diagnostics / Actions**. Chaque quête de la chaîne marquée « inconnue » si
  absente du catalogue.
* `STORY_QUEST_UNKNOWN` côté panel ; si aucun `quest.list` chargé, la section
  Diagnostics affiche « Chaîne non vérifiée … » (pas de fausse alerte).

### `/dialogues` (refonte)

* Toolbar `[ ↻ Dialogues ] [ ↻ Quêtes ]` (si `CONTENT_READ`).
  `listControls("dialogues", …)` : puces *Tous / Liés / Non liés / À vérifier*.
* `loadIssues` et `declaredButMissing` : **fin des bannières rouges brutes**,
  rendus via `DiagnosticHelp` (`DIALOGUE_LOAD_ISSUE`,
  `DIALOGUE_DECLARED_MISSING`).
* `renderDialogueAccordionItem` : accordéon `#dialogues-accordion`. En-tête =
  nom lisible + id + N nœuds / N choix + `sans PNJ` + pastille de santé. Détail :
  **Résumé / PNJ / Quêtes / Diagnostics / Graphe / Actions**. Le **graphe est un
  `<details>` NON ouvert** par défaut (`dlg-node*` inchangés, édition guidée #82
  conservée derrière). Les warnings du moteur triés par sévérité et rendus par
  `DiagnosticHelp`. `dialogueDiagnostics` (ancien) supprimé.

### `/players` (audit — refonte légère)

* Gros `actionButton` → `listCatbar("Relevé", compactRefresh(…))`.
* Compteur `count-note` ; au-delà de 6 joueurs connectés, `listControls("players",
  …)` (recherche input-group) ; les `<tr>` portent `data-filter-item="players"`.
  CSS `tr[hidden]{display:none}` pour le filtre progressif.

### Documentation `/docs` (#49) — 4 fiches de dépannage (nouvelles)

* `pnj-depannage.md`, `dialogues-depannage.md`, `quetes-depannage.md`,
  `stories-depannage.md` — ajoutées à la liste blanche `_index.txt`.
* Chaque section porte **exactement** le titre humain de l'entrée
  `DiagnosticHelp` correspondante (ancre garantie), au format §15 :
  *Ce que cela signifie / Pourquoi il faut corriger / Comment corriger*
  (numéroté) */ Vérification / Référence technique* (le code).

### Terminologie (§18)

* Messages principaux : « associé / identifié », « liaison », « donneur de
  quête », « sans fiche correspondante », « identifiant technique ». `tagué`,
  `binding`, `giver`, `orphan`, `raw`, `namespaced` ne subsistent que dans le
  `code technique` secondaire (test dédié).

## Fichiers créés

* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/DiagnosticHelp.java`
* `control-panel/src/main/resources/docs/pnj-depannage.md`
* `control-panel/src/main/resources/docs/dialogues-depannage.md`
* `control-panel/src/main/resources/docs/quetes-depannage.md`
* `control-panel/src/main/resources/docs/stories-depannage.md`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/DiagnosticHelpTest.java`
* `docs/claude-reports/2026-09-09_0930_control-panel-ux-metier-diagnostics-89-49.md` (ce rapport)

## Fichiers modifiés

* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/AgentPages.java`
  — helpers partagés (`listCatbar`, `compactRefresh(returnPath)`,
  `listControls`, `idKeySet`/`known`/`npcKeySet`) ; `/quests`, `/stories`,
  `/dialogues` refondus (`renderQuestAccordionItem`, `renderStoryAccordionItem`,
  `renderDialogueAccordionItem`) ; `/players` toolbar + recherche ; `/npcs`
  Diagnostics → `DiagnosticHelp` + `npcFixButton` ; `renderQuestCard`,
  `renderStoryCard`, `renderDialogueCard`, `dialogueDiagnostics`, `diagAlert`
  supprimés.
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/Icons.java`
  — `help`, `wrench`.
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/docs/Markdown.java`
  — `slug()` replie les accents (NFD).
* `control-panel/src/main/resources/assets/plugadmin.css`
  — bloc `.pa-diag*` (composant diagnostic), styles de détail réutilisés
  (`.npc-detail .obj-list/.reward-list/.step-list`, `.dlg-graph-wrap` replié),
  `.dlg-global-diag`, `tr[hidden]`.
* `control-panel/src/main/resources/docs/_index.txt` — 4 fiches ajoutées.
* `control-panel/src/test/java/.../NpcsCatalogTest.java` — assertions Diagnostics
  migrées vers `DiagnosticHelp` (titre humain, ancre, code secondaire,
  terminologie interdite).
* `control-panel/src/test/java/.../QuestsCatalogTest.java` — accordéon +
  sections + diagnostic `QUEST_PREREQ_UNKNOWN` humanisé + ancre doc.
* `control-panel/src/test/java/.../StoriesCatalogTest.java` — accordéon compact,
  détail replié, toolbar compacte ; `STORY_QUEST_UNKNOWN` humanisé + ancre.
* `control-panel/src/test/java/.../DialoguesCatalogTest.java` — accordéon, graphe
  non ouvert d'office, diagnostics `DiagnosticHelp`, plus de `banner err`.
* `docs/control-panel/ROADMAP.md` — étape 3f LIVRÉ.
* `docs/current_state.md` — entrée généralisation UX + `DiagnosticHelp` + fiches.
* `docs/RPGQUEST_BIBLE.md` — section « Présentation des pages métier et
  diagnostics contextuels (#89 / #49) ».
* `docs/deployment/SERVER_CHANGELOG.md` — entrée 2026-09-09 (AWS uniquement).

## Base de données / migrations

Aucune. Aucun schéma touché (plugin ou panel).

## Configuration / données

Aucune. Aucun nouveau paramètre, aucune variable d'environnement. Les 4 fiches
`.md` sont embarquées dans le jar du Control Panel.

## Tests automatiques

* `./gradlew :control-panel:test` — **205 tests, 0 échec** (198 précédents + 7
  `DiagnosticHelpTest` ; assertions Quests/Stories/Dialogues/Npcs mises à jour).
* `./gradlew test` — inchangé (aucun module hors `control-panel` touché ; tâches
  `:test` / `:web-api:test` `UP-TO-DATE`).
* `./gradlew build` — **SUCCESS**.

`DiagnosticHelpTest` vérifie notamment :
* chaque entrée pointe vers `/docs/<fiche>#<ancre>` avec `ancre = slug(titre)` ;
* chaque `docSlug` existe, est dans `_index.txt`, et la fiche rendue contient
  `id="<ancre>"` (section réelle) ;
* `render("BINDING_NO_DEFINITION", …)` = titre humain « Fiche RPGQuest
  manquante », « Conséquence : », « À faire : », `href` d'ancre précis, bouton
  « Comment corriger ? », `Code technique : <code class="tid">…`, `alert
  alert-danger pa-diag pa-diag-error` ; **aucun** terme de `BANNED` (`tagué`,
  `binding`, `giver`, `orphan`, `namespaced`, `raw`, `target`, `kind`) hors la
  ligne « code technique » ;
* `DIALOGUE_NO_NPC` = niveau INFO, titre « Dialogue non associé à un PNJ » ;
* `QUEST_PREREQ_UNKNOWN` / `QUEST_GIVER_UNKNOWN` / `STORY_QUEST_UNKNOWN` couverts ;
* code inconnu → message brut conservé + `href="/docs"` + habillage `pa-diag`.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` (mot de passe owner non détenu dans cette session) —
en navigateur authentifié sur `https://plugadmin.lodylands.com` :

1. `/quests`, `/stories`, `/dialogues` : la liste est **compacte** (une ligne
   d'accordéon par élément), aucun détail/formulaire/diagnostic visible avant le
   clic ; un seul élément déplié à la fois.
2. La recherche `input-group` filtre bien (pas d'icône qui chevauche le
   placeholder) ; les puces de filtre s'alignent (desktop : même ligne ; mobile :
   recherche pleine largeur puis puces).
3. Un diagnostic (ex. quête avec prérequis absent, dialogue rejeté) affiche :
   titre FR clair, « Conséquence : », « À faire : », bouton **« Comment corriger ? »**
   qui ouvre `/docs/<fiche>#<section>` sur la bonne ancre ; code technique discret.
4. `/dialogues` : le graphe des nœuds est **replié** par défaut (« Afficher le
   graphe »).
5. `/players` : bouton de relevé compact ; recherche apparaît au-delà de 6
   joueurs et masque bien les lignes.
6. Non-régression #93 (toasts + cloche de notifications) et #49 (`/docs` :
   recherche, catégories, anonymes → `/login`).

## Résultat attendu

Les 4 pages métier partagent le même modèle « comprendre → identifier →
savoir quoi faire → pouvoir le faire → lire l'aide ». Aucun message technique
n'est laissé sans action ni sans lien de documentation.

## Reset / retour à l'état initial

* Code : `git revert 6835c15 bdbbcb9` (ou `git checkout 307be4a -- control-panel/`).
  Aucun état persistant à nettoyer.
* Déploiement : `scripts/plugadmin/rollback.sh app` (restaure
  `/opt/plugadmin/releases/20260909-095349`).

## Déploiement VeryGames

### À transférer
Rien. **Aucun changement plugin, agent, monde ou donnée Minecraft.**

### Ne PAS transférer/altérer
Le serveur VeryGames (JAR RPGQuest, `data.db`, config, mondes, Citizens). Ne pas
redémarrer Minecraft.

### Redémarrage requis
Uniquement le service `plugadmin` sur AWS (fait par `deploy.sh`).

### Migration automatique
Aucune.

## Déploiement AWS (Control Panel) — effectué

* `scripts/plugadmin/deploy.sh` — release `/opt/plugadmin/releases/20260909-095349`,
  `systemctl restart plugadmin`, health local **ONLINE**.
* Vérifications :
  * `https://plugadmin.lodylands.com/health` → `{"panel":"ONLINE"}` ;
  * `/npcs` `/dialogues` `/quests` `/stories` `/docs` `/players` anonymes → **303**
    vers `/login` ;
  * En-tête **CSP inchangé** : `default-src 'self'; style-src 'self'
    'unsafe-inline'; img-src 'self' data:; form-action 'self'; frame-ancestors
    'none'` ;
  * `/assets/plugadmin.css?v=bedb0b2d` → **200** ;
  * `dig.lodygames.com` → **200**, `lodylands.com` → **200** (inchangés) ;
  * `plugadmin.service` `active`, `NRestarts=0` ; `journalctl -u plugadmin` :
    **aucun `ERROR`** ;
  * Jar déployé : `DiagnosticHelp.class` + `docs/{pnj,dialogues,quetes,stories}-depannage.md`
    + `_index.txt` embarqués.

## Rollback

`scripts/plugadmin/rollback.sh app` (release précédente + restart). Aucune
migration à défaire. Aucune config à restaurer.

## Logs / diagnostic

`journalctl -u plugadmin -f` — au démarrage : `com.lodygames.rpgquest.panel.web.PanelApp
start` puis health `ONLINE`. Aucun `ERROR` / `Exception` depuis le déploiement.

## Documentation mise à jour

* `docs/control-panel/ROADMAP.md` (étape 3f), `docs/current_state.md`,
  `docs/RPGQUEST_BIBLE.md`, `docs/deployment/SERVER_CHANGELOG.md`.
* Centre `/docs` : 4 nouvelles fiches de dépannage (contenu utilisateur).

## Limitations / travail restant

* Validation navigateur authentifiée : `PENDING MANUAL VALIDATION` (voir
  « Tests manuels »).
* Diagnostics quêtes/stories : seules les **références cassées** (prérequis,
  donneur, quête de chaîne) sont détectées côté panel. « Objectif invalide »,
  « custom item inconnu », « cycle » et le chargement rejeté (`QUEST_LOAD_ISSUE`
  / `STORY_LOAD_ISSUE`) restent dans le registre `DiagnosticHelp` (avec fiche
  doc) mais ne sont pas encore émis : `quest.list` / `story.list` ne renvoient
  ni code ni load issue. À traiter quand le protocole agent les exposera (ou via
  un `*.validate` dédié, cf. V2 #46).
* `QUEST_GIVER_UNKNOWN` / `STORY_QUEST_UNKNOWN` ne s'affichent que si
  `npc.list` / `quest.list` a été rafraîchi au moins une fois dans la session
  d'agent (sinon : mention « non vérifié », pas de fausse alerte).

## Prochaine étape suggérée

* Faire la validation navigateur authentifiée des 4 pages.
* Étendre les diagnostics quêtes/stories aux cas non couverts (objectif
  invalide, item custom inconnu, cycle) — nécessite un enrichissement du
  protocole `quest.list` / `story.list` ou une action `quest.definition.validate`.
* Après validation, envisager l'intégration de `feat/control-panel-admin-tools`
  (décision propriétaire — #89 et #49 restent ouvertes, aucun merge fait).
