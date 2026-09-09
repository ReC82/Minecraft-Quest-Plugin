# Control Panel — roadmap modulaire

Chaque étape doit laisser `./gradlew build` **vert** et être testable. Aucune ne fabrique de faux
écran fonctionnel : un module non implémenté affiche « à venir ».

## Étape 0 — socle (issue #37, V1) — **LIVRÉ**

- [x] module Gradle `control-panel` autonome (pas de `web-common` en V1 — [ADR-009]).
- [x] `PanelMain` (+ `hash-password`), config par environnement (`Target` multi-cibles), fail-closed
      sur secrets manquants.
- [x] auth mono-utilisateur : `/login`, `/logout`, session signée HMAC, CSRF (double-submit login +
      synchroniseur authentifié), cookies `HttpOnly`/`SameSite`/`Secure` configurable.
- [x] `PermissionService` + énum `Permission`/`Role` (seul `owner` actif).
- [x] `AuditLog` append-only (`control-panel.db`) — utilisé par login/logout.
- [x] **Dashboard = health réel** : statut panel + statut bridge + version plugin + joueurs +
      uptime + mondes essentiels + dernier check. Indisponibilité du bridge → bannière claire,
      jamais un 500.
- [x] plugin : `WebAdminServer` — `GET /admin/v1/health` authentifié, fail-closed, bind interne.
- [x] navigation prête (Dashboard actif ; Joueurs/PNJ/Quêtes/Stories/Diagnostics/Admin/Dev = « à venir »).
- [x] tests : `PanelAppTest` (11), `BridgeClientTest` (4), `PasswordHasherTest` (4), `JsonTest` (3),
      `WebAdminServerTest` (4). `./gradlew test build` vert.
- [x] `docs/control-panel/*` alignés sur l'implémentation.
- [ ] `admin-snapshot.json` (mode dégradé) — reporté ([ADR-004]).

## Étape 0b — déploiement AWS (issue #44) — **LIVRÉ** (validation login owner par navigateur en attente)

- [x] **https://plugadmin.lodylands.com** en ligne : vhost nginx dédié + TLS Let's Encrypt ECDSA +
      redirection 80→443.
- [x] service systemd **`plugadmin`** (utilisateur système dédié, durci), app sous `/opt/plugadmin/app`
      (`installDist`), secrets `/etc/plugadmin/plugadmin.env` hors dépôt.
- [x] backend `127.0.0.1:8090` non exposé ; `/health` OK local **et** public.
- [x] scripts reproductibles `scripts/plugadmin/` (`install.sh` / `deploy.sh` / `rollback.sh`) +
      runbook [DEPLOYMENT_AWS.md](DEPLOYMENT_AWS.md).
- [x] **non-régression** `dig.lodygames.com` / `lodylands.com` (+ `www` / `beta`) : inchangés
      avant/après.
- [x] dashboard « RPGQuest DEV indisponible » rendu proprement (200, pas de 500) — état normal
      jusqu'à #51.
- [ ] login owner **réel** validé depuis un navigateur externe (l'owner ; tout le reste de la
      chaîne d'auth est vérifié : GET login, login invalide → 401, CSRF, `/dashboard` anonyme → 303,
      cookies `Secure`/`HttpOnly`/`SameSite`).

## Étape 0c — agent sortant VeryGames → PlugAdmin (issue #51) — **SOCLE LIVRÉ**

- [x] plugin `com.lodygames.rpgquest.web.agent` : `PlugAdminAgent` (service Paper) + `AgentLoop`
      (heartbeat + file d'actions, backoff, idempotence) + `PlugAdminClient` (HTTPS sortant) +
      `AgentConfigLoader` (fichier local `plugadmin-agent.properties` hors Git, surcharge env,
      fail-closed).
- [x] heartbeat via `HealthSource` (#37 réutilisé, aucune logique de health dupliquée).
- [x] contrat versionné `/agent/v1/heartbeat` · `/agent/v1/actions` · `/agent/v1/actions/{id}/result`
      côté PlugAdmin (`AgentEndpoints`), auth **par agent** (jeton dédié, temps constant), payload
      borné, kill-switch.
- [x] persistance `control-panel.db` : `agent_heartbeat` (dernier par agent) + `agent_action`
      (file + résultats), migrations idempotentes. Pas de MySQL #43.
- [x] ONLINE / STALE / OFFLINE (`AgentLiveness`), seuils configurables, indépendant du navigateur.
- [x] 1re action **non destructive** : `player.variable.get` via `PlayerVariableRepository`
      (jamais `/rpgadmin` texte). Type inconnu → `REJECTED`.
- [x] idempotence des deux côtés (action livrée jusqu'au résultat ; `ProcessedActionCache` agent).
- [x] dashboard : section **AGENT DISTANT** prioritaire quand configurée ; bridge local #37
      conservé en affichage secondaire (dev / co-localisé). Page **Agents** (diagnostic owner :
      statut, dernier heartbeat, envoi de l'action de preuve, historique).
- [x] tests : plugin `web.agent.*` (30) + control-panel `panel.agent.*` (17). `./gradlew build` vert.
- [x] docs : [AGENT.md](AGENT.md), `CONFIGURATION.md`, `SECURITY.md`, `ARCHITECTURE.md`,
      `RPGQUEST_BRIDGE.md`.
- [ ] validation **live VeryGames** (déploiement JAR + fichier agent + redémarrage owner) — voir
      rapport de session #51.

## Étape 1 — lectures — **PARTIELLEMENT LIVRÉ** (`feat/control-panel-admin-tools`)

- [x] module **Joueurs** : roster live + détail (variables get/set, aperçu reset, GIVE) via
      actions agent whitelistées.
- [x] module **Quêtes** : catalogue (titre lisible d'abord, étapes/objectifs/récompenses) + état
      par joueur.
- [x] module **Stories** : catalogue ordonné + état par joueur.
- [ ] module **PNJ** : bindings + « PNJ attendus mais non liés » (aurait signalé le `guard`
      manquant de la session #21). Comparaison contenu chargé ↔ bindings. **(P1, non livré)**
- [ ] module **Diagnostics** : contenu dépôt ↔ contenu chargé ↔ runtime (dépendances cassées,
      quête référencée inconnue, story sans PNJ de rendu…). **(P1, non livré)**

## Étape 2 — actions sûres (#36 via le panel) — **LIVRÉ** (via l'agent sortant #51, pas le bridge)

- [x] `quest start|complete|reset`, `story advance|complete`, `player.variable get|set`,
      `player.item.give`, `player.resetnew preview|confirm` — **actions agent whitelistées**
      (`AgentActionType` ↔ `AgentActionCatalog`), logique dans les services métier du plugin,
      audit log, `confirm` obligatoire sur les mutations, permission `ACTION_*`, validation 3 couches.
- [x] rafraîchissement auto du résultat des actions (issue #65) — `/assets/panel.js`
      démarre sur le compteur serveur **ou** sur un statut non terminal encore visible dans
      le tableau, fait un premier relevé immédiat, et s'arrête dès `pending == 0`.
- [ ] « préparer un état de test » = macro d'actions (ex. « claims: débloqué sans claim »). **(non livré)**

## Étape 3 — contenu (lecture structurée)

- [~] **Page `/npcs` V1** (lecture) — catalogue PNJ via `npc.list` : croise liaison Citizens ↔
      dialogue `rpgquest:<id>` ↔ `giver:` ↔ `TALK_TO_NPC`, anomalies de configuration, ids
      canoniques. Sans lecture du monde.
- [~] **Système PNJ V2 déclarative** — vraie **définition logique** (`npcs/*.yml`, `YamlNpcEngine`,
      `NpcDefinition`), indépendante de Citizens/monde. `npc.list` distingue `logicalDefinitionPresent`
      vs `citizensBindingPresent` + `state` ; `definedIds` = source canonique. Écritures whitelistées :
      `npc.definition.create` / `npc.definition.update` (`NpcDefinitionStore`) et `quest.giver.set`
      (`QuestGiverEditor`, édition minimale du YAML). Page `/npcs` : deux blocs, création + édition
      limitée + attribution de quête.
- [~] **Lier une définition à un PNJ Citizens existant** (#81, phase 1) — `npc.citizens.list`
      (registre Citizens, thread principal) + `npc.citizens.link` (`NpcIdentityService.bindCitizens`
      + `CitizensBindPlanner` : collisions refusées, no-op idempotent, jamais de rebind ni de spawn).
      Page `/npcs` : « Rafraîchir les PNJ Citizens » + formulaire de liaison sur les cartes
      `NOT_LINKED` (Citizens libres seulement).
- [~] **Spawn d'un PNJ Citizens depuis une définition** (#81, phase 2) — `npc.citizens.create`
      (permission dédiée `NPC_SPAWN_WRITE`). `CitizensSpawnPlanner` (pur : Citizens actif,
      définition présente + `enabled`, pas déjà lié, monde de la liste blanche RPGQuest, position
      finie et bornée) → `CitizensSpawnCoordinator` (pur : `create → bind → success`, sinon
      rollback du seul PNJ créé → `BIND_FAILED_ROLLED_BACK`). Nom = `displayName`. Thread principal
      pour Citizens + monde, persistance async. Page `/npcs` : bloc « Créer le PNJ Citizens » sur
      les cartes définies `NOT_LINKED` — preview + monde en liste (heartbeat) + coordonnées à
      saisir + confirmation. **Reste** : premier spawn réel `PENDING MANUAL VALIDATION` ;
      suppression générale (`npc.citizens.delete`), rebind/déplacement, choix de position depuis
      une carte, téléportation admin, câblage `/rpgadmin npc tag` (#66),
      enrichissement live (position/monde, PNJ Citizens non tagués).
- [~] **Page `/dialogues` V1** — lecture structurée + bases d'un futur éditeur. `dialogue.list`
      (lecture, permission dédiée `DIALOGUE_READ`) : `DialogueCatalog` (pur) dérive de
      `YamlDialogueEngine` + `YamlNpcEngine` + `YamlQuestEngine` : par dialogue, nœuds ordonnés
      (départ d'abord) avec `reachable`, choix avec **actions et conditions typées**
      `{kind, target, value, raw}`, relations PNJ (convention `rpgquest:<id>` + `NpcDefinition.dialogue`),
      quêtes référencées/démarrées, warnings (`NODE_UNREACHABLE`, `QUEST_REF_UNKNOWN`,
      `DIALOGUE_NO_NPC`, `DEFINITION_DIALOGUE_DIVERGES`…), `loadIssues[]` à part pour les fichiers
      rejetés. `dialogue.definition.create` (permission dédiée `DIALOGUE_WRITE`) : squelette
      `dialogues/<key>.yml` (`DialogueDraft` → `DialogueDefinitionYaml` déterministe →
      `DialogueDefinitionStore` atomique, refus d'écrasement, re-parsé). Page `/dialogues` :
      graphe lisible par carte, nœud de départ mis en avant, nœuds inaccessibles marqués,
      MiniMessage rendu, formulaire de création de squelette.
- [~] **Éditeur guidé `/dialogues` — phase 1 de #82.** Refonte de la page en quatre blocs
      (en-tête identité+état · résumé · diagnostics triés erreur→attention→info · graphe de
      cartes nœud). Cinq mutations agent (`DIALOGUE_WRITE`, `confirm`, audit) via
      `DialogueDefinitionEditor` : `dialogue.node.update` (locuteur/texte, choix conservés),
      `dialogue.node.create` (nœud simple orphelin), `dialogue.choice.add` / `.update` /
      `.delete` — **choix simple** seulement (ni condition ni action hors « fermer » ; `next`
      vers un nœud existant *ou* fermeture ; jamais le dernier choix). Écriture sûre :
      localisation par `id`, refus d'un fichier déjà invalide, **sérialisation fidèle du
      dialogue complet** (`DialogueDefinitionWriter` — toutes actions/conditions préservées),
      **garde-fou round-trip** (re-parse + égalité sémantique) → écriture atomique →
      rechargement → **restauration** si échec. Fichier édité au **format canonique** (perte
      assumée des commentaires). Formulaires `<details>` par nœud, cibles en `<select>`,
      aucun JS. **Reste pour #82** : actions/conditions typées éditables (`START_QUEST`,
      `CLOSE`…), renommage / déplacement / suppression de nœud, réordonnancement des choix,
      builder graphique interactif.
- [~] `quest.list` transporte des **objectifs et récompenses structurés**
      (`objectiveDetails` / `rewardDetails` = `{kind, target, amount, value, command, raw}`,
      commande non tronquée) + le **PNJ donneur** (`giverId`, champ YAML `giver:` optionnel) —
      issues #78 / #75. Le panel consomme la structure (`ObjectiveText` / `RewardText.fromSummary`)
      et ne reparse plus de chaîne métier ; repli legacy documenté et déprécié.
- [ ] consultation des YAML (quêtes/dialogues/stories/marchands/items), détection de
      dépendances cassées, comparaison dépôt/serveur, **sans édition** encore.
- [~] **Centre de documentation `/docs` (issue #49) — MVP.** Wiki d'administration **privé**
      (accès authentifié, permission `DOCS_READ` accordée à tous les rôles). Source de vérité =
      fichiers Markdown **versionnés** (`control-panel/src/main/resources/docs/*.md`), listés dans
      un manifeste `_index.txt` qui **est** la liste blanche ; aucun contenu copié en base, aucun
      chemin du navigateur ouvert (fiches adressées par `slug` interne résolu côté serveur —
      `[a-z0-9-]`, lookup en mémoire, zéro accès disque à la requête). `DocFrontMatter` (titre /
      catégorie / tags / order, Markdown sans front matter accepté), `Markdown` (rendu **sûr** :
      tout échappé, aucune balise brute, aucun JS, liens limités à `/docs/…` / ancre / `https://` ;
      titres, listes, tableaux, blocs de code avec bouton « Copier », callouts `> [!NOTE]` /
      `[!WARNING]`), `DocLibrary` (chargement + index mémoire), `DocSearchIndex` (recherche plein
      texte pondérée titre > tags > catégorie > commandes > corps, ET des termes, extraits
      contextualisés), `DocsPages` (accueil : recherche + catégories + « Comment faire ? » ;
      résultats ; fiche : fil d'Ariane + sommaire + tags + source). 9 fiches opérationnelles
      livrées (PNJ/Citizens détaillée, commandes Citizens, reset joueur, quêtes/stories, Claims,
      Wild, déploiement VeryGames + règle anti-auto-reboot, déploiement AWS Control Panel,
      référence `/rpgadmin`). Liens contextuels depuis `/npcs` `/quests` `/players` `/dialogues`.
      **Reste (V2)** : édition Markdown depuis le navigateur, permissions fines par rôle,
      indexation des `docs/` du dépôt, historique Git (dernier commit par fiche), favoris.

## Étape 3b — refonte UX (#92) — **LIVRÉ (chemin principal ; validation navigateur en attente)**

- [x] **design system interne** : tokens (couleurs / surfaces / espacement / rayons / ombres /
      typo / breakpoints) en *custom properties* dans `Layout.CSS`. Sortie du « tout noir » →
      thème clair (fond gris très clair, surfaces blanches, sidebar `#1d2534`, primaire bleu
      `#2f6df6`, couleurs d'état vert / orange / rouge / bleu).
- [x] **iconographie SVG locale** : `Icons.java` (35 icônes, sprite `<symbol>`/`<use>` inline) —
      aucun emoji comme système, aucune dépendance CDN, conforme CSP.
- [x] **shell** : topbar (identité PlugAdmin, chip environnement DEV, chip d'état serveur
      synthétique, menu session) + sidebar en 4 groupes (Vue d'ensemble / RPGQuest / Ressources /
      Administration) + **drawer mobile sans JS** (`<input checkbox>` + `<label>` + CSS `:checked`).
- [x] composants `Ui` : `pageHeader`, `sectionTitle`, `statCard`, `banner`, `searchToolbar`,
      `filterChip`, `countNote`, `primaryLink`, `empty(icon,msg)`, `severity`.
- [x] Dashboard en cartes ; détails techniques (bridge local) repliés dans `<details>` ;
      Documentation `/docs` restylée ; en-têtes standard + recherche/filtres `data-filter-*` sur
      PNJ / Quêtes / Stories / Dialogues ; `panel.js` `initFilters()` / `initDrawer()`.
- [x] **stratégie sans casse** : tous les noms de classes CSS et sous-chaînes HTML asserties
      conservés → tests `:control-panel:test` verts à chaque étape.
- [ ] refonte de contenu de la page Agents et des placeholders `/diagnostics` `/admin` `/dev` ;
      polissage largeur / TOC / fil d'Ariane de la fiche de documentation ; **validation
      navigateur du rendu réel et du mobile**.

### Étape 3b bis — socle Bootstrap + Home à tuiles — **LIVRÉ**

- [x] **Bootstrap 5.3.8 + Bootstrap Icons 1.13.1**, servis **100 % localement** sous
      `/assets/bootstrap/` et `/assets/bootstrap-icons/` (fichiers vendored dans
      `control-panel/src/main/resources/assets/`, voir
      [VENDOR_ASSETS.md](VENDOR_ASSETS.md)). Aucun CDN au runtime, **CSP inchangée**
      (`default-src 'self'` couvre script/style/font de même origine). Handler générique
      `PanelApp#handleAsset` : chemin validé (anti-traversal), content-type par extension,
      `ETag` + `304`, cache court.
- [x] **`plugadmin.css`** = design system #92 (extrait de `Layout`, verbatim) + **pont
      `--bs-*`** vers les tokens PlugAdmin (Bootstrap adopte l'identité, pas de bleu/blanc
      par défaut) + styles de la Home. `Layout` charge `bootstrap.min.css` +
      `bootstrap-icons.min.css` + `plugadmin.css`, et `bootstrap.bundle.min.js` + `panel.js`
      globalement.
- [x] **Iconographie = Bootstrap Icons** : `Icons.icon()` rend un `<i class="bi bi-…">`
      (signature inchangée → tous les appels existants fonctionnent ; ancien sprite SVG
      neutralisé). Pas d'emoji.
- [x] **Home = launcher à tuiles** (`/home`) : page d'arrivée après connexion (redirections
      `/` et post-login → `/home`). Grandes tuiles groupées (Vue d'ensemble / Gestion du jeu
      / Ressources / Administration), grille Bootstrap `row-cols-1/md-2/lg-3/xl-4`, chaque
      tuile activée = vrai `<a>` (toute la carte cliquable, focus clavier), tuiles « à venir »
      non cliquables (`aria-disabled`). Badges synthétiques depuis le **dernier relevé agent**
      (`AgentPages#homeSummary`, aucune requête déclenchée). Emplacement réservé pour une
      recherche globale (`input` désactivé).
- [x] **Dashboard** reste une page dédiée (`/dashboard`, 200) et devient une tuile.
      Sidebar : entrée **« Accueil »** en tête.
- [ ] migration progressive des formulaires / tableaux vers les composants Bootstrap
      (offcanvas mobile, modales de confirmation, `nav-pills`, `input-group`, tooltips) —
      au fil des prochaines évolutions, sans réécriture de masse.

### Étape 3d — toasts + centre de notifications + `/actions` (#93) — **LIVRÉ**

- [x] **Fin des gros blocs « Actions récentes »** sous les pages métier (`/players`, `/npcs`,
      `/quests`, `/stories`, `/dialogues`) : elles ne portent plus que leur contenu propre.
      `/agents` garde une « Activité récente » compacte (3 lignes) + lien vers `/actions`.
- [x] **Toasts Bootstrap** après une mutation : redirection `…&toast=<id>` (fin de `&ok=1`),
      un seul toast rendu côté serveur (`ActionView.toastHtml`). SUCCESS auto-dismiss (6 s),
      PENDING / FAILED persistants ; un toast PENDING est **mis à jour en place** par
      `panel.js` quand l'action se résout (pas de spam). Toast d'erreur immédiat sur `&err=`.
      `aria-live`, `role`, `btn-close`.
- [x] **Centre de notifications** : cloche `bi-bell` dans la topbar (placeholder `%NOTIF%`,
      rendu si `DIAGNOSTICS_READ`), **panneau off-canvas Bootstrap** (`offcanvas-end`, largeur
      ≈ 420 px desktop / 92 vw mobile — corrigé d'un dropdown trop étroit) listant les 8
      dernières actions (icône domaine, libellé humain, cible, statut, heure relative, résultat
      court borné à 90 caractères), **badge** = actions en cours + échecs de moins de 24 h, pied
      fixe « Voir toutes les actions » → `/actions`.
- [x] **Cache-busting des assets** (`web/Assets`) : `Layout` ajoute `?v=<hash8 du contenu>` à
      `bootstrap(.bundle).min.(css|js)`, `bootstrap-icons.min.css`, `plugadmin.css`, `panel.js`
      → un déploiement qui modifie un asset change son URL, le navigateur ne sert plus une
      version périmée depuis son cache (le handler ignore la query string). `panel.js`
      `showToast()` a un repli d'affichage manuel si Bootstrap JS n'est pas encore prêt.
- [x] **Page `/actions`** (`ActionsPages`) : recherche (GET, combinable avec les filtres) +
      `nav-pills` statut (Tous / Succès / En cours / Échec — regroupe les 6 statuts techniques)
      + puces domaine (Joueurs / PNJ / Quêtes / Stories / Dialogues / Items / Serveur-Agents /
      Autres, seulement ceux présents) + compteur. **Table Bootstrap desktop**
      (`d-none d-md-block`) + **cartes mobile** (`d-md-none`). **Pagination** 25 / page.
      Détail `/actions/<id>` (type technique, paramètres, cible, timestamps, livraisons,
      résultat, corps brut, acteur). Permission `DIAGNOSTICS_READ`, pas de bypass OWNER.
- [x] **`/agents/actions.json` enrichi** (`label`, `domain`, `group`, `target`, `resultShort`,
      `age`, `notifHtml`, `badge`) ; `panel.js` `initToasts()` + `initNotifications()`
      (polling léger 20 s, accéléré à 3 s tant qu'une action est en cours, garde-fou de durée) ;
      suppression de l'ancien tableau pollable. **Pas de WebSocket** (MVP). Source de vérité
      inchangée : table `agent_action`.

### Étape 3e — refonte ciblée de `/npcs` (#89) — **LIVRÉ**

- [x] **Philosophie** : la liste est une **synthèse** ; un clic sur un PNJ ouvre son **détail** ;
      un clic sur une action ouvre le **formulaire**. Plus de « détails + diagnostics +
      formulaires + actions » affichés d'emblée.
- [x] **Toolbar catalogue compacte** : boutons Bootstrap `btn-sm` (« ↻ Catalogue RPGQuest »
      `btn-outline-primary`, « ↻ Citizens » `btn-outline-secondary`, « + Nouvelle définition PNJ »
      qui déplie un `collapse`). Fin des grandes cartes vides pour un bouton.
- [x] **Recherche** = vrai `input-group` Bootstrap (icône dans son propre `.input-group-text`,
      plus aucun chevauchement). Recherche + filtres (Tous / Liés / Non liés / Warnings / Erreurs)
      alignés ; wrap/scroll propre sur mobile.
- [x] **Liste = `accordion` Bootstrap** (`data-bs-parent` → un seul PNJ ouvert à la fois).
      En-tête synthétique : nom affiché + id logique (discret) + 2-3 badges d'état + chevron.
- [x] **Détail structuré** : sections **Identité / Citizens / Contenu / Diagnostics / Actions**.
      Labels humains (« Donneur de quête » ; `quest_giver` en secondaire). Diagnostics dans des
      `alert` différenciées (danger / warning / info), code technique discret.
- [x] **Actions = boutons** qui déplient chacun un `collapse` contenant leur formulaire (Créer la
      définition / Modifier / Attribuer une quête / Lier un PNJ Citizens / Créer le PNJ Citizens).
      Liens « Ouvrir le dialogue » / « Ouvrir la quête ».
- [x] **Formulaire de définition repensé** : sections Identité / Contenu / État, `form-label` +
      `form-text`, select Dialogue alimenté par `dialogue.list`, select Rôle, `form-switch`
      « PNJ actif », boutons Annuler / Créer.
- [x] **Bug de contexte de formulaire corrigé** (capture : fiche « Guide » mais valeurs
      « Bûcheron Bob » créant `woodcutter_bob`) : dans une fiche, `npc_id` **et** `npc_ctx` sont
      des champs **cachés** = l'id de la fiche (jamais un input éditable), `autocomplete="off"`,
      pré-remplissage avec les valeurs du PNJ courant. **Garde-fou serveur**
      (`PanelApp#createAgentAction`) : si `npc_ctx` ≠ `npc_id` validé → refus, aucune action
      créée. Toasts #93 conservés (pas de gros message local).

### Étape 3f — généralisation de la refonte UX + diagnostics humains (#89 suite / #49) — **LIVRÉ**

- [x] **Même philosophie sur `/quests`, `/stories`, `/dialogues`** : liste = synthèse
      (`accordion` Bootstrap, un seul élément ouvert via `data-bs-parent`), clic = détail en
      sections repliées, clic sur une action = formulaire. `/players` (roster + actions, non
      dense) : simple toolbar compacte + recherche `input-group` au-delà de 6 joueurs.
- [x] **Toolbars catalogue compactes partout** (`listCatbar` + `compactRefresh`) : boutons
      `btn-sm` (`[ ↻ Quêtes ] [ ↻ PNJ ]`, `[ ↻ Stories ] [ ↻ Quêtes ]`, `[ ↻ Dialogues ]
      [ ↻ Quêtes ]`) — fin des grandes cartes vides « Rafraîchir le catalogue ».
- [x] **Recherche `input-group` Bootstrap + filtres alignés** (`listControls`) sur PNJ /
      Quêtes / Stories / Dialogues (puces Toutes / Sans alerte / À vérifier, ou Tous / Liés /
      Non liés / À vérifier pour les dialogues).
- [x] **Sections de détail** : Quêtes → Général / Donneur / Prérequis / Objectifs / Récompenses
      / Diagnostics / Actions. Stories → Identité / Chaîne de quêtes / Diagnostics / Actions.
      Dialogues → Résumé / PNJ / Quêtes / Diagnostics / **Graphe** (`<details>` **non ouvert**
      par défaut) / Actions.
- [x] **`DiagnosticHelp` — registre unique d'aide aux diagnostics** (`panel.web.DiagnosticHelp`).
      Chaque code du moteur (PNJ `NpcCatalog`, dialogues `DialogueCatalog`) + les vérifications
      de référence calculées côté panel (`QUEST_PREREQ_UNKNOWN`, `QUEST_GIVER_UNKNOWN`,
      `STORY_QUEST_UNKNOWN`, `DIALOGUE_LOAD_ISSUE`, `DIALOGUE_DECLARED_MISSING`) →
      **1. problème en français clair · 2. conséquence · 3. action recommandée · 4. lien vers
      une ancre précise de `/docs` · 5. code technique en secondaire**. Rendu = `alert`
      Bootstrap colorée selon la sévérité (`danger` / `warning` / `info`), bouton
      « Comment corriger ? » (`bi-question-circle`) et, quand une action immédiate existe côté
      PNJ, bouton « Corriger maintenant » qui déplie le bon formulaire.
- [x] **Terminologie UX (§18)** : plus de `tagué` / `binding` / `giver` / `orphan` / `raw` /
      `namespaced` dans les messages principaux — « associé », « liaison », « donneur de quête »,
      « sans fiche correspondante », « identifiant technique ». Le jargon ne subsiste que dans
      le `code technique` secondaire et la section avancée.
- [x] **`Markdown.slug` replie les accents** (NFD + suppression des diacritiques) : les ancres
      de section (`/docs/quetes-depannage#prerequis-inconnu`…) sont propres et **dérivées du
      titre**, donc toujours cohérentes entre le registre et la fiche.
- [x] **#49 — 4 fiches de dépannage contextuelles** : `pnj-depannage`, `dialogues-depannage`,
      `quetes-depannage`, `stories-depannage` (whitelist `_index.txt`). Format §15 :
      *Ce que cela signifie* / *Pourquoi il faut corriger* / *Comment corriger* (numéroté) /
      *Vérification* / *Référence technique* (le code). Le titre de chaque section est **exactement**
      le titre humain de l'entrée `DiagnosticHelp` correspondante (ancre garantie).
- [x] **Tests** : `DiagnosticHelpTest` (cohérence registre ↔ fiches ↔ `_index.txt` ↔ ancres ;
      message humain ; code secondaire ; bouton d'aide ; terminologie interdite absente ;
      code inconnu → repli propre) ; assertions `accordion` / toolbar compacte / `input-group`
      / diagnostic humanisé + ancre doc ajoutées à `QuestsCatalogTest`, `StoriesCatalogTest`,
      `DialoguesCatalogTest` ; `NpcsCatalogTest` migré vers `DiagnosticHelp`. `#93` / `#49`
      sans régression.

## Étape 3c — éditeur guidé de quêtes et de stories (#46) — **LIVRÉ (chemin principal ; V2 restant)**

- [x] paquet `panel.content` : `ContentWorkspace` (accès FS **whitelisté** `quests/*.yml` +
      `stories/*.yml` uniquement — jamais `data.db` / `.env` / config / mondes / Citizens / chemin
      du navigateur ; hash SHA-256 de version ; écriture `tmp` + `ATOMIC_MOVE` ; refus
      `EXISTS` / `CONFLICT` / `READONLY` ; **dégradation gracieuse en lecture seule** sans aucun
      `chmod` / `sudo` automatique).
- [x] `MiniYaml` (lecteur minimal), `QuestYaml` / `StoryYaml` (émetteur **déterministe** = forme
      exacte attendue par `QuestDefinitionParser` / `StoryDefinitionParser` + garde-fou
      **round-trip** : émettre → relire → ré-émettre → égalité, motif `DialogueDefinitionEditor`).
- [x] `QuestDraft` / `StoryDraft` = **mêmes champs** que le moteur (pas un 2e modèle) ;
      `Descriptors` = **7 objectifs + 4 récompenses réels** avec champs adaptés ; `RefData` (ids
      quêtes / PNJ connus du dernier relevé agent + mondes + listes curées entités / matériaux).
- [x] `Diagnostic` (ERROR bloque / WARNING confirme / INFO informe) + `QuestValidator` /
      `StoryValidator` (structure calquée sur le parser + cohérence de référence) ; `TextDiff`.
- [x] `ContentEditorPages` : formulaire guidé multi-sections **sans JS** (boutons `_action`
      ajout / suppression / réordonnancement, selects en `<datalist>`), aperçu YAML + diff avant
      enregistrement, mode lecture seule explicite. Routes `PanelApp` `/quests/new|edit|save` +
      `/stories/new|edit|save` : session + **`QUEST_CONTENT_WRITE` / `STORY_CONTENT_WRITE`** +
      CSRF sur `save` + audit `*.content.write`. **Aucun déploiement.**
- [x] config : `content.repo-dir` / env `PLUGADMIN_CONTENT_DIR` (absent → éditeur en lecture seule).
- [ ] **V2** : lignes guidées pour prérequis / variables (au lieu de zones de texte) ;
      duplication d'objectif / de récompense ; rechargement des champs au changement de type de
      `<select>` (petit JS progressif) ; action agent `quest.definition.validate` (relecture par
      le **vrai** parser à distance) ; schéma vertical de chaîne de story.

## Étape 4 — développement (#29)

- [ ] module « Développement » : GitHub (issues/branches/commits), rapports Claude, jobs Claude,
      tests/build, déploiements avec backup, notifications — **même socle auth/backend**, pas une
      2e app.

## Étape 5 — édition guidée & multi-serveur

- [ ] édition guidée de contenu + validation avant déploiement + historique.
- [ ] plusieurs cibles RPGQuest simultanées, staging.
- [ ] RBAC complet (tester / content-editor / builder / read-only).

## Hors périmètre (durablement)

- terminal shell dans le navigateur ;
- lecture/écriture directe de `data.db` comme API live ;
- SPA lourde ;
- dépendance FTP dans le cœur.
