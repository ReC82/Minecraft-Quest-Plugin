# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-08
* Heure : 21:24 (locale)
* Sujet : Refonte graphique complète de PlugAdmin (#92) + éditeur guidé de quêtes et de stories (#46)
* Statut : PARTIAL (chemin principal livré et testé ; sous-fonctions V2 et validation manuelle navigateur restantes)
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel si disponible : à renseigner après commit (base : `0e529c5`)
* Début de la tâche : non mesurable précisément — tâche de nuit reprise après compactage de
  contexte. Repères objectifs : le rapport précédent (#49) est clos à 20:03 ; les commits #92
  `9202aad` / `0e529c5` datent de ~20:50–20:56 ; le travail #46 de cette fenêtre.
  Estimation raisonnable de début : ~2026-09-08 20:10:00.
* Fin de la tâche : 2026-09-08 21:24:32
* Durée totale : ~01:14:00 (approximative — voir « Début » ci-dessus ; non calculée à la seconde)

---

## Demande

Session autonome de nuit, deux tickets dans l'ordre strict :

1. **#92 — Refonte graphique complète de PlugAdmin.** Pas une passe CSS : une vraie refonte
   produit (référence DirectAdmin / back-office SaaS moderne). Design system interne (tokens
   couleurs/surfaces/espacements/typo + composants réutilisables), sortie du « tout noir » vers un
   thème clair, iconographie SVG locale cohérente (pas d'emoji comme système, pas de CDN), shell
   (topbar + sidebar groupée + drawer mobile), refonte du Dashboard, de la Documentation, des
   pages Joueurs / PNJ / Quêtes / Stories / Dialogues / Agents, recherche + filtres généralisés,
   composants de formulaire prêts pour #46, responsive obligatoire, accessibilité minimale, aucun
   framework front lourd.
2. **#46 — Éditeur guidé de quêtes et de stories.** Une fois la refonte faite : administrer le
   contenu LodyQuests depuis PlugAdmin sans écrire de YAML à la main. Priorités : créer / modifier
   une quête, gérer objectifs (ajout/modif/suppression/réordonnancement), récompenses, prérequis /
   donneur, créer / modifier une story, **valider AVANT enregistrement**, **aucun déploiement
   VeryGames automatique**. Réutiliser le moteur réel (pas de second modèle de quêtes), séparer
   strictement « éditer » ≠ « enregistrer dans la source » ≠ « déployer », écriture whitelistée +
   versionnée + atomique, relecture par un garde-fou round-trip, sécurité complète (auth,
   permission dédiée, CSRF, anti-traversal, anti-double-submit, audit).

Contraintes permanentes : ne rien merger, ne pas fermer #92/#46 (l'utilisateur gère GitHub), ne
pas redémarrer Minecraft pour une refonte UI, règle anti-boucle de reboot VeryGames, ne pas
bloquer en attente du propriétaire pour des détails réversibles.

---

## Analyse

### État avant intervention

* PlugAdmin = serveur HTTP JDK (`com.sun.net.httpserver`), rendu HTML côté serveur
  (`Layout` + `AgentPages` + `DocsPages` + `Ui`), JS progressif unique `assets/panel.js`
  (conforme CSP : aucun script inline). Module `control-panel` interdit de dépendre de Paper /
  `:web-api` / plugin.
* Thème très sombre, hétérogène, peu hiérarchisé. 154 tests `:control-panel:test` verts.
* Le moteur de quêtes réel (`src/main/java/com/lodygames/rpgquest/quest/…`) : `QuestDefinition`,
  `QuestStep`, objectifs `ObjectiveType` (7), récompenses `RewardType` (4),
  `QuestDefinitionParser` (validation YAML, **package-private, dépend de Bukkit**). Stories :
  `StoryDefinition` + `StoryDefinitionParser` (id strict, `name`, liste ordonnée `quests`).
* Le service `plugadmin` (déployé sur AWS) tourne sous un utilisateur distinct du propriétaire du
  checkout `/srv/rpgquest/repo` : **pas de droit d'écriture** sur `src/main/resources/quests|stories`.

### Décisions structurantes

| Sujet | Décision | Raison |
|---|---|---|
| Refonte sans casser les tests | Réécriture visuelle du CSS + shell en **conservant tous les noms de classes** et toutes les sous-chaînes HTML asserties | 154 tests + toutes les pages continuent de fonctionner sans toucher la logique |
| Iconographie | Sprite SVG **inline** (`<symbol>`/`<use>`), 35 icônes dans `Icons.java` | Interdit CDN, interdit emoji comme système ; conforme CSP |
| Drawer mobile | `<input type=checkbox>` + `<label>` + CSS `:checked` | Pas de JS requis |
| Filtres / recherche | `panel.js initFilters()` sur attributs `data-filter-*` | Progressif — sans JS la liste reste entière |
| #46 — modèle | `QuestDraft` / `StoryDraft` = **mêmes champs** que le moteur, exprimés en types simples ; **pas** un second modèle | Contrainte B1 |
| #46 — parser réel (B12) | Le module control-panel ne peut pas importer `QuestDefinitionParser` (dépend de Bukkit). → **garde-fou round-trip** panel-side (émettre → relire → ré-émettre → comparer, motif de `DialogueDefinitionEditor` #82) + validation d'autorité finale garantie au chargement serveur (le plugin refuse un fichier invalide au boot) | Impossible autrement sans casser l'isolation de module |
| #46 — écriture source | `ContentWorkspace` : whitelist stricte `quests/*.yml` + `stories/*.yml`, hash SHA-256 de version, écriture atomique (tmp + `ATOMIC_MOVE`), détection de conflit, **dégradation gracieuse en lecture seule** si pas les droits (jamais de `chmod`/`sudo` automatique) | Contraintes B9/B10/B13 + réalité des droits du service |
| #46 — formulaire dynamique | Round-trip serveur : chaque bouton `_action` renvoie tout l'état, le serveur relit → mute → re-rend | Conforme CSP (pas de JS), testable, même motif que l'éditeur de dialogues #82 |
| #46 — sources de select | `<datalist>` (autocomplétion + saisie libre) alimentées par le dernier relevé agent (`quest.list`, `npc.list`, mondes du heartbeat) + listes curées entités/matériaux | Le module ne peut pas énumérer les enums Bukkit ; le vrai parser tranche au chargement |
| #46 — permissions | Nouvelles `QUEST_CONTENT_WRITE` / `STORY_CONTENT_WRITE` (rôles `CONTENT_EDITOR` + `OWNER`) | Pas de `if role == OWNER` dans les contrôleurs (B14) |
| #46 — config | Nouvelle clé `content.repo-dir` / env `PLUGADMIN_CONTENT_DIR` (défaut : absent → éditeur en lecture seule) | Réversible, pas de chemin fourni par le navigateur |

---

## Travail effectué

### Phase A — #92 (committée : `9202aad`, `0e529c5`)

* **Design system** dans `Layout.CSS` : thème clair complet en *custom properties*
  (`--bg #f4f6fb`, surfaces blanches, sidebar `#1d2534` contrastée mais non noire, primaire bleu
  `#2f6df6`, couleurs d'état vert/orange/rouge/bleu, rayons, ombres, espacement, typo, breakpoints).
* **`Icons.java`** (nouveau) : 35 icônes SVG locales + `sprite()` (émis une fois par page) +
  `icon(name[, class])`.
* **Shell** : `Layout.page(...)` refait — topbar (marque PlugAdmin, chip environnement DEV, chip
  d'état serveur synthétique, menu session), sidebar en **4 groupes** (Vue d'ensemble / RPGQuest /
  Ressources / Administration), état actif très visible, zone « à venir » intégrée, **drawer
  mobile** sans JS.
* **`Ui.java`** étendu : `pageHeader`, `sectionTitle`, `statCard`, `banner`, `searchToolbar`,
  `filterChip`, `countNote`, `primaryLink`, `empty(icon,msg)`, `severity`.
* **Dashboard** (`PanelApp`) : en-tête standard, bloc serveur principal (hero), grille de cartes
  stat, détails techniques (bridge local) repliés dans un `<details class="tech-detail">` quand
  l'agent est primaire.
* **Documentation** (`DocsPages`) : accueil restylé (grand titre, recherche large, catégories en
  cartes à icônes, « Comment faire ? » en cartes) ; bouton *Copier* des blocs de code avec icône.
* **Pages métier** (`AgentPages`) : en-têtes standardisés, barres recherche + puces de filtre sur
  PNJ / Quêtes / Stories / Dialogues, cartes taggées `data-filter-*`, `Ui.banner` pour les états.
* **`panel.js`** : `initFilters()` (recherche + puces, met à jour le compteur), `initDrawer()`.
* Migration `:control-panel:test` : **154 / 154 verts** conservés à chaque étape (stratégie de
  préservation des noms de classes).

### Phase B — #46 (dans cette fenêtre, à committer)

Nouveau paquet `com.lodygames.rpgquest.panel.content` :

* **`ContentWorkspace`** — accès FS whitelisté au checkout source. `list/read/exists/write`,
  `configured()`, `writable(kind)`. `write()` : résolution de chemin sûre (jamais un chemin du
  navigateur, `startsWith` vérifié, slug `[a-z0-9][a-z0-9_-]{0,63}`), hash SHA-256 recalculé,
  refus `EXISTS` (création sur fichier présent), `CONFLICT` (hash divergent / fichier disparu),
  `READONLY` (dossier non inscriptible), écriture `tmp` + `ATOMIC_MOVE` (repli `REPLACE_EXISTING`).
* **`MiniYaml`** — lecteur YAML minimal (indentation, `clé: valeur`, blocs map/liste, listes
  `- `, map en ligne `- clé: valeur`, guillemets, `true/false/null`, commentaires hors chaînes).
  Volontairement partiel : sert uniquement au round-trip.
* **`QuestYaml`** / **`StoryYaml`** — émetteur déterministe (forme exacte attendue par
  `QuestDefinitionParser` / `StoryDefinitionParser`) + relecteur + `roundTripProblems(yaml)`
  (émettre → relire → ré-émettre → égalité).
* **`QuestDraft`** / **`StoryDraft`** — modèles éditables (mêmes champs que le moteur), `blank()`,
  `copy()`.
* **`Descriptors`** — descripteurs des **7 types d'objectifs réels** et **4 récompenses réelles**
  (clé technique, libellé humain, icône, champs : nom/label/type de saisie/aide/source de select).
  Abstraction légère : ajouter un type = ajouter un descripteur.
* **`RefData`** — données de référence : id quêtes / PNJ connus (dernier relevé agent) + mondes
  chargés + listes curées entités/matériaux ; `options(source)`, `isValueKnown`, `sourceKnown`.
* **`Diagnostic`** (ERROR / WARNING / INFO, sémantique B7) + **`QuestValidator`** /
  **`StoryValidator`** — contrôles structurels calqués sur le parser (champs obligatoires, entiers
  > 0, types connus, ids valides, étapes ≥ 1, objectif ≥ 1 par étape…) + cohérence de référence
  (prérequis / PNJ / quête inconnus → WARNING ; non vérifiable → INFO ; commande console → WARNING).
* **`TextDiff`** — diff ligne à ligne minimal pour l'aperçu « avant / après ».
* **`ContentEditorPages`** (`panel.web`) — formulaire guidé multi-sections
  (Général / Prérequis / Objectifs / Récompenses / Variables / Validation & aperçu pour les
  quêtes ; Général / Chaîne de quêtes / Validation & aperçu pour les stories), **sans JS** :
  boutons `_action` (`add_step`, `del_step:i`, `add_obj:si`, `del_obj:si:oi`, `mv_obj:si:oi:up|down`,
  `add_reward`, `del_reward:i`, `mv_reward:i:up|down`, `add_q`, `del_q:i`, `mv_q:i:up|down`,
  `refresh`, `validate`, `save`). Champs de select rendus en `<input list=dl-…>` + `<datalist>`
  partagées. Aperçu : diagnostics groupés + problèmes round-trip + YAML généré (lecture seule) +
  diff vs source. Mode lecture seule : bannière explicite + bouton d'enregistrement désactivé,
  validation/aperçu/diff toujours actifs.
* **`AgentPages.referenceData(agentId)`** — construit `RefData` depuis les derniers relevés agent.
* **`AgentPages`** — carte quête / story : lien « Modifier » (`/quests/edit/<slug>` /
  `/stories/edit/<slug>`) si permission d'écriture ; helper `editSlug`.
* **`PanelApp`** — routes `/quests/new|edit|save`, `/stories/new|edit|save` (session +
  `QUEST_CONTENT_WRITE` / `STORY_CONTENT_WRITE` + CSRF sur `save`) ; `ContentWorkspace` +
  `ContentEditorPages` câblés ; entrée d'audit `quests.content.write` / `stories.content.write`.
* **`Permission`** — `QUEST_CONTENT_WRITE`, `STORY_CONTENT_WRITE` ; **`Role`** — ajoutées à
  `CONTENT_EDITOR` (déjà `OWNER` via `allOf`). *(déjà en place depuis la phase #92)*
* **`PanelConfig` / `PanelConfigLoader`** — champ `contentRepoDir` (env `PLUGADMIN_CONTENT_DIR`
  ou propriété `content.repo-dir`).

---

## Fichiers créés

**#46 — code**
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/content/ContentWorkspace.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/content/MiniYaml.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/content/QuestYaml.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/content/StoryYaml.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/content/QuestDraft.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/content/StoryDraft.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/content/Descriptors.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/content/RefData.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/content/Diagnostic.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/content/QuestValidator.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/content/StoryValidator.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/content/TextDiff.java`
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/ContentEditorPages.java`

**#46 — tests**
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/content/ContentYamlRoundTripTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/ContentEditorPagesTest.java`

**#92 — code (déjà committé `9202aad` / `0e529c5`)**
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/Icons.java`

---

## Fichiers modifiés

**#46**
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/PanelApp.java` — routes, câblage,
  handler `handleContentEditor`, audit.
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/AgentPages.java` — `referenceData`,
  liens « Modifier », `editSlug`.
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/Layout.java` — CSS `.field`,
  `.diag-list`, `.editor`.
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/config/PanelConfig.java` — champ
  `contentRepoDir`.
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/config/PanelConfigLoader.java` —
  lecture `PLUGADMIN_CONTENT_DIR` / `content.repo-dir`.
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/support/TestConfig.java` —
  `withContentDir(...)`.

**#92 (déjà committé)** : `Layout.java`, `Ui.java`, `AgentPages.java`, `DocsPages.java`,
`PanelApp.java`, `assets/panel.js`, `authz/Permission.java`, `authz/Role.java`,
`docs/Markdown.java`.

---

## Base de données / migrations

Aucune. Le Control Panel n'utilise SQLite que pour l'audit et les actions agent ; aucune table
ajoutée ou modifiée. `data.db` du serveur Minecraft **jamais** touché.

---

## Configuration / données

* **Nouvelle clé** (optionnelle) : `content.repo-dir` dans `control-panel.properties`, ou variable
  d'environnement `PLUGADMIN_CONTENT_DIR`. Valeur attendue : racine du checkout source du contenu,
  typiquement `/srv/rpgquest/repo/src/main/resources`. **Absente → l'éditeur #46 s'affiche mais
  reste en lecture seule** (validation / aperçu / diff disponibles ; enregistrement désactivé).
* Aucun secret. Aucun chemin fourni par le navigateur n'est utilisé pour résoudre un fichier.
* L'éditeur n'écrit que dans `<content.repo-dir>/quests/*.yml` et `<content.repo-dir>/stories/*.yml`.
  Interdits : `data.db`, `.env`, `config.yml`, mondes, Citizens, plugins externes, tout chemin
  arbitraire.

---

## Tests automatiques

Commandes exécutées sur la box AWS de build :

```
./gradlew :control-panel:test    → BUILD SUCCESSFUL — 169 tests, 0 échec, 0 erreur
./gradlew test                   → BUILD SUCCESSFUL (plugin :test UP-TO-DATE — aucun fichier
                                   src/main/java du plugin modifié par cette tâche)
./gradlew build                  → BUILD SUCCESSFUL
```

Nouveaux tests :

* `ContentYamlRoundTripTest` (5) — quête minimale / riche round-trip identique, story round-trip,
  `QuestValidator` signale titre manquant + prérequis inconnu.
* `ContentEditorPagesTest` (11, bout en bout HTTP) — rendu des sections guidées + injection CSRF +
  datalists ; redirection anonyme → login ; bouton « ajouter un objectif » ajoute une ligne
  (round-trip serveur) ; enregistrement d'une quête valide → fichier créé dans la source +
  round-trip OK + ré-ouverture repart du fichier ; enregistrement refusé si erreurs de validation
  (aucun fichier écrit) ; hash périmé → conflit détecté, fichier non écrasé ; id traversant
  (`../../evil`) refusé, aucun fichier hors dossier ; CSRF invalide → 403 ; espace de travail en
  lecture seule → bannière + enregistrement bloqué ; story : chaîne ordonnée écrite dans l'ordre ;
  `READ_ONLY` n'a pas `QUEST_CONTENT_WRITE`, `CONTENT_EDITOR`/`OWNER` l'ont.

Total `:control-panel:test` : **169 / 169 verts** (154 avant #92, +5 round-trip, +11 éditeur ;
les compteurs incluent aussi les tests #92 déjà committés).

---

## Tests manuels à effectuer (PENDING MANUAL VALIDATION)

Sur le PlugAdmin déployé (AWS), après déploiement :

1. Ouvrir PlugAdmin : vérifier le nouveau thème clair, la topbar, la sidebar groupée à icônes,
   l'état actif, le dashboard en cartes, le drawer sur mobile.
2. `/docs` : accueil restylé, recherche, fiche lisible, bouton *Copier*.
3. `/players`, `/npcs`, `/quests`, `/stories`, `/dialogues` : en-têtes standard, recherche +
   filtres, cartes lisibles.
4. **Chemin principal #46** :
   - `/quests` → « Créer une quête » → saisir id/titre/description/catégorie ;
   - ajouter « Tuer des araignées ×5 » via le formulaire (type + `SPIDER` via datalist + `5`) ;
   - ajouter un objectif « Parler au Garde » (type TALK_TO_NPC + `guard`) ;
   - ajouter une récompense XP ;
   - « Vérifier » → diagnostics + aperçu YAML + diff ;
   - « Enregistrer dans la source » → vérifier le fichier `src/main/resources/quests/<slug>.yml` ;
   - ré-ouvrir via « Modifier », changer une valeur, ré-enregistrer, vérifier l'absence de conflit ;
   - créer une story, ajouter plusieurs quêtes, changer l'ordre avec les flèches, « Vérifier ».
5. Vérifier que l'éditeur est en **lecture seule** si `PLUGADMIN_CONTENT_DIR` n'est pas configuré
   ou si le service n'a pas les droits d'écriture (bannière explicite).
6. Responsive : formulaire éditeur en 1 colonne sur mobile, boutons cliquables au doigt.

Aucun test client Minecraft requis (aucun changement plugin).

---

## Résultat attendu

En ouvrant PlugAdmin : version visiblement neuve — thème clair, topbar propre, sidebar à icônes
groupée, dashboard en cartes, recherche et filtres, meilleure typographie, actions identifiables,
documentation agréable, pages PNJ / Joueurs / Quêtes / Stories lisibles, Dialogues moins rustique,
mobile propre.

Puis : ouvrir Quêtes → « Créer une quête » → saisir titre/description → ajouter un objectif
« tuer des araignées ×5 » et « parler au Garde » via formulaire → ajouter une récompense XP →
voir les diagnostics → voir l'aperçu / le diff → enregistrer dans la source en sécurité →
ré-ouvrir → modifier → créer une story → ajouter des quêtes → changer l'ordre → voir les erreurs
avant enregistrement. Chemin principal réellement utilisable ; sous-fonctions secondaires en V2.

---

## Reset / retour à l'état initial

* Tout est sur la branche `feat/control-panel-admin-tools` ; `git checkout main` ou reset de la
  branche annule la totalité.
* Aucune écriture hors dépôt, aucune migration, aucun état persistant nouveau.
* Les fichiers de contenu créés via l'éditeur pendant un test sont de simples `quests/*.yml` /
  `stories/*.yml` : les supprimer ou `git checkout -- src/main/resources/quests` suffit.
* Retirer la clé `content.repo-dir` / `PLUGADMIN_CONTENT_DIR` remet l'éditeur en lecture seule.

---

## Déploiement VeryGames

### À transférer
Rien. Aucun changement du plugin Minecraft ni de l'agent. **Ne pas déployer VeryGames. Ne pas
redémarrer Minecraft.**

### Ne PAS transférer/altérer
`data.db`, mondes, Citizens, config serveur, JAR plugin, agent.

### Redémarrage requis
Non (VeryGames).

### Migration automatique
Sans objet.

---

## Déploiement AWS (Control Panel uniquement)

* Procédure : `scripts/plugadmin/deploy.sh` (build + swap du JAR `control-panel` + restart du
  service `plugadmin`). Rollback : `scripts/plugadmin/rollback.sh app`.
* **Pour activer l'enregistrement #46** (sinon éditeur en lecture seule) : définir
  `PLUGADMIN_CONTENT_DIR=/srv/rpgquest/repo/src/main/resources` (ou `content.repo-dir` dans
  `control-panel.properties`) **et** donner au service `plugadmin` le droit d'écriture sur
  `src/main/resources/quests` et `src/main/resources/stories` — à faire **par le propriétaire du
  dépôt**, p.ex. `setfacl -m u:plugadmin:rwx src/main/resources/quests src/main/resources/stories`.
  Aucun `chmod`/`chown`/`sudo` n'est effectué automatiquement.
* Vérifications post-déploiement : `/health` ONLINE, login, dashboard, `/docs`, `/players`,
  `/npcs`, `/quests`, `/stories`, `/dialogues`, `/quests/new`, `/stories/new`, anon → login,
  assets (`/assets/panel.js`), logs propres, autres vhosts AWS inchangés (`dig.lodygames.com`,
  `lodylands.com` restent disponibles).
* **Non exécuté dans cette session** : le déploiement AWS n'a pas été lancé (laissé à la décision
  explicite du propriétaire, conformément aux règles). La branche est prête.

---

## Rollback

* Control Panel : `scripts/plugadmin/rollback.sh app` (restaure le JAR précédent + restart).
* Code : `git reset --hard 0e529c5` sur la branche, ou abandon de la branche.
* Contenu : `git checkout -- src/main/resources/quests src/main/resources/stories`.

---

## Logs / diagnostic

* Écritures de contenu journalisées dans l'audit PlugAdmin : action `quests.content.write` /
  `stories.content.write`, acteur = utilisateur de session, cible = chemin de redirection.
* `ContentWorkspace.write` renvoie un code explicite (`CREATED`, `UPDATED`, `EXISTS`, `CONFLICT`,
  `READONLY`, `NOT_FOUND`, `INVALID`, `ERROR`) rendu tel quel dans une bannière.

---

## Documentation mise à jour

* `docs/control-panel/ROADMAP.md` — #92 et #46 marqués en cours / livrés (chemin principal).
* `docs/control-panel/SECURITY.md` — section éditeur de contenu (whitelist, hash de version,
  écriture atomique, anti-traversal, permissions dédiées, audit, lecture seule gracieuse).
* `docs/control-panel/CONFIGURATION.md` — clé `content.repo-dir` / `PLUGADMIN_CONTENT_DIR`.
* `docs/current_state.md` — état PlugAdmin (thème clair, éditeur #46 chemin principal).
* `docs/RPGQUEST_BIBLE.md` + `docs-site/` — page « Éditeur de quêtes et de stories (PlugAdmin) ».
* `docs/claude-reports/README.md` — ligne d'index.

---

## Limitations / travail restant

**#92**
* Page Agents (A15) et pages placeholder (`/diagnostics`, `/admin`, `/dev`) : héritent du nouveau
  CSS mais pas de refonte de contenu dédiée.
* Fiche Documentation : lisible, polissage largeur/TOC/breadcrumb encore possible.
* Validation navigateur (rendu réel, mobile) : PENDING.

**#46 — livré**
* Créer / modifier une quête ; sections Général / Prérequis / Objectifs / Récompenses / Variables ;
  ajout/suppression/réordonnancement d'objectifs et de récompenses ; 7 types d'objectifs + 4
  récompenses réels avec champs adaptés et datalists ; prérequis + donneur ; créer / modifier une
  story avec chaîne ordonnée réordonnable ; validation ERROR/WARNING/INFO **avant** enregistrement ;
  aperçu YAML + diff ; garde-fou round-trip ; écriture whitelistée + versionnée + atomique +
  détection de conflit ; lecture seule gracieuse ; auth + permission dédiée + CSRF + anti-traversal
  + audit ; **aucun déploiement**.

**#46 — V2 / restant**
* Prérequis et variables saisis en zone de texte (une ligne par entrée) plutôt qu'en lignes à
  boutons — fonctionnel mais moins guidé.
* Le changement du `<select>` de type d'objectif nécessite un clic sur « Actualiser le formulaire »
  (ou tout autre bouton) pour recharger les champs du nouveau type (pas de JS).
* Duplication d'objectif / de récompense non implémentée (ajout + ré-saisie à la place).
* Édition d'un fichier YAML **hand-written** contenant des commentaires : l'aperçu montre un gros
  diff (commentaires retirés, forme canonique) — assumé, l'utilisateur décide ; le garde-fou
  round-trip porte sur la forme canonique, pas sur le fichier d'origine.
* `quest.definition.validate` côté agent (relecture par le **vrai** parser à distance) : non fait,
  V2. Aujourd'hui l'autorité finale reste le chargement du plugin (qui refuse un fichier invalide).
* Éditeur non configuré (`PLUGADMIN_CONTENT_DIR` absent) : rend le formulaire en lecture seule
  sans message spécifique « non configuré » distinct de « pas les droits ».
* Aperçu chaîne de story sous forme de schéma vertical : liste ordonnée simple pour l'instant.

**Général**
* Déploiement AWS non lancé (décision propriétaire).
* Aucun test client Minecraft nécessaire.

---

## Prochaine étape suggérée

1. Validation navigateur du nouveau PlugAdmin + du chemin principal #46 (liste ci-dessus).
2. Déploiement AWS via `scripts/plugadmin/deploy.sh` + configuration de `PLUGADMIN_CONTENT_DIR` et
   des droits d'écriture (par le propriétaire).
3. Itération V2 #46 : lignes guidées pour prérequis/variables, duplication de ligne, rechargement
   des champs au changement de type (petit JS progressif), action agent `quest.definition.validate`.
4. Finir #92 : refonte de contenu de la page Agents et des placeholders.
