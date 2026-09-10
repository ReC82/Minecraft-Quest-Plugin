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
- [x] module **Diagnostics** — page `/diagnostics` (issue #38) : voir « Étape 3g » ci-dessous.

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

### Étape 3g — dashboard d'observabilité / page `/diagnostics` (#38) — **LIVRÉ**

- [x] **Modèle unique** `panel.diag.DiagnosticEntry` (code stable, `Severity` ERROR/WARNING/INFO,
      `Domain` humain, ressource + label, titre/message/conséquence/action humains, `docHref`
      d'ancre précise, `resourceHref` « Ouvrir », `QuickAction` optionnelle, `source`,
      `observedAt`). Wording repris de `DiagnosticHelp` quand le code est connu — **jamais
      dupliqué**.
- [x] **Architecture extensible** : interface `DiagnosticProvider` + `DiagnosticContext` (lecture
      seule du dernier snapshot agent, **aucune requête déclenchée**). Providers : `Npc`,
      `Dialogue`, `Quest`, `Story`, `Server`. Ajouter une source = ajouter un provider (pas de
      `switch` géant).
- [x] **`DiagnosticsService`** agrège, **dédoublonne** (code + ressource), **trie**
      (ERROR → WARNING → INFO, puis domaine, ressource, code), calcule compteurs, domaines
      présents, fraîcheur (`oldestSnapshot`).
- [x] **Codes couverts** : PNJ `BINDING_NO_DEFINITION` / `NO_DEFINITION` / `DIALOGUE_MISSING` /
      `DUPLICATE_DEFINITION` / `DUPLICATE_BINDING` / `DISABLED` / `NOT_LINKED` /
      `GIVER_NO_DIALOGUE` + `CITIZENS_UNAVAILABLE` ; Dialogues `NEXT_MISSING` /
      `QUEST_REF_UNKNOWN` / `NODE_UNREACHABLE` / `DIALOGUE_NO_NPC` / `MULTIPLE_NPCS` /
      `DEFINITION_DIALOGUE_DIVERGES` / `DIALOGUE_LOAD_ISSUE` / `DIALOGUE_DECLARED_MISSING` ;
      Quêtes/Stories `QUEST_PREREQ_UNKNOWN` / `QUEST_GIVER_UNKNOWN` / `STORY_QUEST_UNKNOWN`
      (calcul de référence côté panel, `RefKeys` partagé avec `/quests` `/stories`) ; Serveur/Agent
      `AGENT_NOT_CONFIGURED` / `AGENT_NO_HEARTBEAT` / `AGENT_OFFLINE` / `AGENT_HEARTBEAT_STALE` /
      `SERVER_NOT_ONLINE` / `PLUGIN_VERSION_UNKNOWN` ; Mondes `WORLD_NOT_LOADED`.
- [x] **Page `/diagnostics`** (`DiagnosticsPages`, `Permission.DIAGNOSTICS_READ`, tous rôles) :
      en-tête + cartes synthétiques (Erreurs / Avertissements / Infos / Total), barre de filtres
      **combinables** (gravité + domaine + recherche plein texte via `data-filter-*` ;
      `panel.js` gère désormais **plusieurs groupes de puces** pour un même scope, rétro-compatible),
      cartes compactes (icône + titre humain · domaine · ressource · conséquence · **Ouvrir** /
      **Corriger maintenant** / **Comment corriger ?** · code technique en dernier), regroupement
      au-delà de 4 diagnostics identiques (`<details>` « Voir les N »), **état vide positif**
      (« Aucun problème détecté »), fraîcheur « Dernière vérification : il y a … ».
- [x] **« Ouvrir »** = lien profond `/<page>?agent=…&focus=<id>` ; `panel.js#initFocus()` déplie
      l'élément d'accordéon ciblé (attribut `data-res-id` ajouté aux 4 pages) + scroll + surbrillance
      ; **« Corriger maintenant »** = même lien profond avec `&fix=create|edit|link` qui déplie en
      plus le bon formulaire — **aucune correction automatique générique**, uniquement des actions
      déjà offertes par le panel.
- [x] **Refresh coordonné** : bouton `[ ↻ Actualiser les diagnostics ]` → `POST /diagnostics/refresh`
      (CSRF, `DIAGNOSTICS_READ`) enqueue **les 4** relevés `*.list` nécessaires (jamais dix) en une
      seule action opérateur ; feedback **toast** (#93).
- [x] **Home** : tuile Diagnostics active (« 2 erreurs · 7 avertissements » ou « Tout est en
      ordre ») ; **Dashboard** : section « État du contenu » (compteurs + « Voir les diagnostics »).
      Sidebar : entrée Diagnostics active.
- [x] **#49** : nouvelle fiche `serveur-depannage.md` (whitelist `_index.txt`) + section
      « Citizens inactif sur le serveur cible » dans `pnj-depannage.md` ; chaque diagnostic
      actionnable pointe vers une **ancre précise**.
- [x] **Tests** : `DiagnosticsServiceTest` (agrégation multi-domaines, un de chaque gravité, tri,
      dédoublonnage, heartbeat manquant → ERROR, monde non chargé, ancres doc ↔ fiches) +
      `DiagnosticsPageTest` (auth, cartes humaines, code secondaire, filtres combinables + panel.js,
      regroupement, refresh coordonné + toast, CSRF, Home/Dashboard, sidebar). `HomeLauncherTest`
      mis à jour (tuile active). `#49` / `#89` / `#93` sans régression.

### Étape 3h — bug `/npcs` : PNJ Citizens réel masqué (#101) — **LIVRÉ**

- [x] **Cause** : la liste `/npcs` était construite **uniquement** depuis `npc.list`
      (`NpcCatalog` = définitions + liaisons + références de contenu), qui ne lit pas le registre
      Citizens. Un PNJ créé en jeu (`/npc create …`) sans fiche **ni** binding n'y produit aucune
      ligne — invisible, alors que la ligne de synthèse « Citizens : N » le comptait. Ni le
      registre Citizens runtime, ni l'action agent `npc.citizens.list` (qui renvoie bien tous les
      PNJ, libres inclus — vérifié sur le payload DEV réel), ni le stockage n'étaient en cause.
- [x] **Fix (Control Panel uniquement, aucune modif plugin/agent)** : `AgentPages.npcs()`
      raccroche chaque PNJ de `npc.citizens.list` sans `linkedNpcId` comme **ligne d'identité
      physique** — libellé = nom en jeu, sous-titre `Citizens #N`, badges « sans fiche RPGQuest » +
      « non lié », `data-filter-cat="unlinked"`, `data-filter-text` = nom + id numérique + UUID
      (recherche §14). Détail « Identité Citizens » (numéro, UUID, spawné) + explication humaine.
- [x] **Actions facultatives par ligne** : *Créer une fiche RPGQuest* (`npcDefForm`, id pré-rempli
      = nom normalisé) ; *Lier à une fiche existante* (liaison inverse : `npc.citizens.link` avec
      le Citizens fixé, `<select>` des fiches prêtes non liées).
- [x] **État d'affichage `CITIZENS_ONLY`** (information, jamais erreur) + entrée `DiagnosticHelp`
      + section « PNJ du jeu sans fiche RPGQuest » dans `pnj-depannage.md` (ancre `slug(titre)`).
      `NpcDiagnosticProvider` émet un INFO `CITIZENS_ONLY` par PNJ Citizens libre sur
      `/diagnostics` (lecture de `npc.citizens.list`, sans effet si le relevé est absent).
- [x] **Compteur `/npcs`** = lignes `npc.list` + PNJ Citizens libres.
- [x] **Tests** : `NpcsCatalogTest` (PNJ libre listé / cherchable / rattachable ; deux homonymes
      conservés ; borne de la fiche `guard` corrigée) + `NpcCitizensPayloadTest` (payload agent :
      PNJ libre et homonymes conservés, aucune clé par nom). `:control-panel:test` + `test` verts.

### Étape 3i — hotfix #103 : 502 Bad Gateway après connexion — **LIVRÉ**

- [x] **Cause (pas une régression du code #101)** : pendant la validation live de #101, une ligne
      `agent_action` a été insérée **directement en base** avec `created_at` sans « Z »
      (`2026-09-09T12:51:33`). `AgentStore.readAction` faisait `Instant.parse` sans tolérance →
      `DateTimeParseException`, remontant par `NotificationCenter.recent` → `recentActions` →
      `readAction` jusqu'au rendu de la cloche de la topbar, donc de **toute** page authentifiée →
      500 → 502 nginx. `/login` n'affiche pas la cloche → smoke anonyme « → 303 » aveugle.
- [x] **Réparation de données** (immédiat, panel re-servi) : UPDATE ciblé de la ligne fautive
      (`0cd8dd38…`, `created_by='claude-101-validation'`, `EXPIRED`) → ajout du « Z ». Aucune
      suppression ; valeur d'origine consignée dans le rapport. 0 ligne au `created_at` sans « Z ».
- [x] **Durcissement `AgentStore`** : `parseTimestamp` tolérant (ISO canonique + formes héritées
      sans décalage → UTC ; illisible → `null` + WARNING) ; `created_at`/`received_at` (NOT NULL) →
      `Instant.EPOCH` en dernier recours ; **frontière de sécurité par ligne** dans
      `recentActions` (ligne illisible ignorée + WARNING, jamais propagée). Règle : donnée agent
      invalide ⇒ diagnostic, jamais crash du serveur web.
- [x] **Tests** : `PanelHardeningMalformedAgentDataTest` — connexion owner réelle + `/home`
      `/dashboard` `/npcs` `/diagnostics` `/docs` `/actions` = 200 avec la ligne exacte de #103,
      formes héritées variées, payload `npc.citizens.list` hérité (Stan sans `uuid`/`spawned`) ;
      **vérifié que le test échoue sans le fix** (`EOFException` = 502) ; smoke authentifié
      optionnel (`-DpanelProdDbCopy`) contre une **copie de la base de prod réelle** (Stan
      toujours visible). `:control-panel:test` **241/0** ; `:test` inchangé ; `build` vert.
- [x] **Déploiement AWS** `deploy.sh` : release `20260909-131643`, jar byte-identique
      (`9a21828f…`), `/health` ONLINE, `plugadmin.service` `active` `NRestarts=0`, 0 `ERROR`.
      **VeryGames non touché.** Rollback : `rollback.sh app` (⚠️ sans le durcissement, réparer
      toute future ligne `created_at` sans « Z »).

### Étape 3j — `/players` : annuaire d'administration des joueurs (#96) — **LIVRÉ (ban/unban + catalogue ; build BLOQUÉ par #27)**

- [x] **Source de vérité** = serveur Paper (`OfflinePlayer` + connectés). Aucune base joueurs
      propre à PlugAdmin ; le passage MariaDB #42 n'est pas rendu plus difficile.
- [x] **Agent** : `player.catalog` (tous les joueurs déjà venus + connectés : `uuid`, `name`,
      `online`, `hasPlayedBefore`, `firstPlayed`, `lastSeen`, `banned` + raison, monde/pos si en
      ligne ; instantané des connectés sur le thread principal puis `getOfflinePlayers()` sur un
      thread **asynchrone**). `player.ban` / `player.unban` via `BanList` de profil Paper —
      **hors ligne OK**, expulsion si connecté, **raison obligatoire**, idempotents. Résolution
      nom↔UUID par le `PlayerDirectory` existant (déjà offline-aware) ; toute mutation résout
      d'abord l'UUID canonique.
- [x] **Modèle pur** `PlayerCatalog` (parse tolérant + recherche pseudo/UUID + filtres
      Tous/En ligne/Hors ligne/Bannis + tri « récent »=connectés puis `lastSeen` desc / « nom »
      A-Z + pagination 50) — **tri/filtre/recherche/pagination côté serveur** (§28 volume).
- [x] **Page réécrite** : LISTE = synthèse (accordion), CLIC = détail, ACTION = formulaire.
      Badges **● En ligne** / **○ Hors ligne** / **Banni** (texte, pas que la couleur).
      Sections **Identité / Activité / RPGQuest (liens vers `/quests` `/stories` `/actions`
      filtrés) / Droits / Modération / Actions**. Toolbar compacte « Actualiser » (toast #93),
      recherche `input-group` GET, puces de filtre = liens serveur, pager Précédent/Suivant.
- [x] **Capacité par action** : ban/unban/variables/reset = **hors ligne OK** (badge) ;
      « donner un objet » = **en ligne uniquement** (indisponible expliqué si hors ligne, jamais
      de faux succès — §23). Reset : workflow existant (aperçu → confirmation, zone danger).
- [x] **Permissions** : `PLAYER_MODERATE` (ban/unban) + `PLAYER_BUILD_WRITE` — OWNER seul ;
      `READ_ONLY` / `TESTER` ne voient pas et ne peuvent pas appeler ban/unban. CSRF + confirmation
      forte + raison obligatoire pour le ban. Audit : acteur, UUID + pseudo cible, raison,
      résultat, `rid`.
- [x] **Historique** : les actions joueur apparaissent dans `/actions` (domaine Joueurs, déjà
      mappé par préfixe `player.`). La fiche ne duplique pas l'historique complet — juste un lien
      « Historique de ce joueur » + le résultat de la dernière action.
- [x] **Doc** : fiche `/docs/joueurs-admin` (annuaire, états, recherche, ban/unban, actions
      impossibles hors ligne, droit de construction bloqué, reset, sécurité) + lien depuis
      `/players`.
- [ ] **`PLAYER_BUILD_WRITE` — BLOQUÉ par #27.** Accorder un droit de construction *persistant*
      à un joueur hors ligne exige un gestionnaire de permissions persistant. #27 (permissions
      granulaires `rpgquest.build.*`) **n'est pas fusionnée sur cette branche** (elle vit sur
      `feature/27-granular-permissions`), et même #27 délègue la persistance à LuckPerms — RPGQuest
      n'a aucun store propre pour « le joueur X peut construire dans le Hub ». Décision (§19) :
      livrer l'architecture de capacité + le catalogue + ban/unban ; la fiche affiche « non géré »
      sans faux interrupteur. À reprendre quand #27 est intégrée + un mécanisme de grant persistant
      existe.
- [x] **Tests** : `PlayerCatalogTest` (8 — parse/tri/recherche/filtres/pagination),
      `PlayersPageTest` (8 — HTTP authentifié : badges, recherche serveur, filtres, ban validé
      CSRF+confirm+raison, unban, give en-ligne-uniquement, focus `?player=`, pagination),
      `AgentActionExecutorTest` (+5 — `player.catalog` compteurs, `player.ban` exige la raison +
      résout l'UUID, `player.unban`). `:control-panel:test` + `:test` + `build` verts.
- [x] **Hors périmètre** (non fait) : mute, sanctions temporaires, notes admin, inventaire
      offline, économie, claims management, RCON, OP management, permissions globales #27,
      migration MariaDB #42.

### Étape 3k — passe UX ciblée éditeur de quêtes (#46) — **LIVRÉ (chemin principal ; validation navigateur en attente)**

- [x] **Un type = une source de vérité** : `ContentEditorPages#renderRow` émet, pour **chaque**
      type d'objectif / récompense du catalogue, le jeu de champs complet issu du même
      `Descriptors.Descriptor` ; un seul visible + actif, les autres `hidden` + `disabled` (ni
      soumis, ni validés). `panel.js` `initEditorForms` / `applyType` bascule au `change` du
      `<select data-type-select>` **sans recharger** et **vide** les champs du type précédent ;
      sans JS, le serveur re-rend le bon jeu au 1er aller-retour ; nettoyage serveur de secours
      (`normaliseRow` : liste blanche `Descriptors.fieldNames` + champ caché `_was`).
      `Descriptors.any` / `.fieldNames` ajoutés ; récompense `EXPERIENCE` → « Points d'expérience ».
- [x] **Brouillon jamais bloqué** : tous les boutons `_action` (`add/del/mv` étape · objectif ·
      récompense, `refresh`, `validate`, `save`) portent `formnovalidate` ; la validation métier
      (`QuestValidator`, ERREUR / ATTENTION / INFO) n'a lieu qu'à « Vérifier » / « Enregistrer ».
      Bouton `submit` par défaut caché (`refresh`) → « Entrée » ne déclenche plus la 1re action
      destructive.
- [x] **Scroll / contexte** : ids stables (`step-<i>`, `obj-<i>-<j>`, `rew-<i>`, `q-<i>`,
      `sec-*`) + `formaction=".../save#ancre"` sur chaque action ; filet JS `sessionStorage`.
- [x] **Listes recherchables** : `panel.js` `initCombo` transforme tout `<input list>` de
      l'éditeur en liste filtrée (valeur **ou** libellé humain), largeur alignée, hauteur bornée,
      clavier ↑/↓/Entrée/Échap, repli au-dessus, fermeture au clic extérieur — **aucune
      dépendance, aucun CDN** (CSP `default-src 'self'`) ; sans JS l'`<input list>` natif reste.
      `RefData.CATEGORIES` (curée + saisie libre) pour la catégorie ; icône = matériaux ;
      entités / matériaux avec libellé FR (`MinecraftNames`, `<option label=…>`).
- [x] **Tests** : `EditorDescriptorsTest` (6 — cohérence descripteurs ↔ moteur, aucun
      débordement de champ entre types, `fieldNames` liste blanche) + `ContentEditorPagesTest`
      (+9 — `formnovalidate`, actions structurelles avec formulaire vide, ancre de scroll, type →
      champs + aide cohérents, ITEM = objet + quantité jamais XP, changement de type efface,
      catégorie ≠ liste PNJ, combos). `:control-panel:test` 271/0 ; `./gradlew build` vert.
- [x] Déploiement AWS `scripts/plugadmin/deploy.sh` (release `20260909-161744`) ; **VeryGames
      non touché**.
- [ ] Validation navigateur authentifiée (scénario §28) — `PENDING MANUAL VALIDATION`.
- [ ] Non fait (délibéré) : libellé humain du PNJ donneur (pas exposé par `npc.list`) ;
      distinction item vanilla / item custom RPGQuest (le moteur ne lit qu'un `Material` vanilla).

### Étape 3l — resynchronisation après mutation + UX formulaires PNJ/Dialogues (#111 → #120) — **LIVRÉ (chemin principal ; validation navigateur en attente)**

- [x] **Réconciliation mutualisée après mutation** (#112 / #115 / #116 / #119 / #120) : chaque
      mutation de contenu déclare, dans `AgentActionCatalog.Spec#refreshTypes()`, les relevés
      `*.list` que son succès rend périmés (`npc.definition.*` → `npc.list` ;
      `npc.citizens.link/create` → `npc.list` + `npc.citizens.list` ; `dialogue.*` → `dialogue.list` ;
      `quest.giver.set` → `npc.list` + `quest.list` ; `player.ban/unban` → `player.catalog`).
      `AgentEndpoints#handleActionResult` ré-enfile ces relevés **à la première transition vers
      SUCCESS** (`created_by = "auto"`, dédup via `AgentStore#hasOpenActionOfType`, jamais sur un
      renvoi idempotent ni sur un échec). Le centre de notifications ignore ces lignes `auto`
      (`NotificationCenter#recent`), mais `/agents/actions.json` les compte (`auto:true`).
      `panel.js` : quand une action **suivie et vue s'exécuter** (pending → succès) se termine, un
      **unique** plan de rechargement s'arme et se déclenche dès que `pending == 0` (garde
      `sawPending` + marqueur `sessionStorage` anti-boucle, repli délai 30 s). Plus de
      « Rafraîchir catalogue + F5 » : la fiche, la liste et les diagnostics se réconcilient seuls.
- [x] **Confirmations** (#111 / #113 / #118) : `Spec#sensitive()` sépare l'édition de contenu
      réversible (création / modification de définition, de nœud, de choix, liaison logique,
      `quest.giver.set`) — **aucune case à cocher cachée**, juste une phrase d'information et un
      `confirm` implicite — des actions réellement sensibles (bannissement, reset, spawn d'un PNJ
      Citizens, suppression de choix) qui gardent une confirmation explicite.
      `AgentPages#mutationConsent` centralise ce choix (jamais décidé à l'écran).
- [x] **Formulaire PNJ** (#111) : exemples en `placeholder` (jamais en valeur par défaut), aide
      métier sous chaque champ (nom / ID technique / dialogue / rôle / actif), lien documentation
      en `target=_blank rel=noopener` (formulaire jamais perdu).
- [x] **Formulaire dialogue** (#117 / #118) : bouton primaire `+ Nouveau dialogue` (fin de
      l'accordéon discret) ; champs expliqués ; ID technique distinct du locuteur affiché ;
      **palette de couleurs MiniMessage** (`AgentActionCatalog.PALETTE_COLORS`, 14 teintes) avec
      pastilles + aperçu réel (`panel.js#initColorPalette`) — le panel génère
      `<couleur>…</couleur>`, le MiniMessage manuel reste possible et n'est jamais ré-enrobé ;
      bandeau technique « phase 1 / format canonique » réduit à une phrase + `<details>` repliés.
- [x] **Tests** : `CatalogResyncTest` (+5 — auto-refresh après succès, double catalogue Citizens,
      dédup, renvoi idempotent, échec sans refresh, mécanisme `panel.js`) ;
      `AgentActionCatalogTest` (+3 — `sensitive` / `refreshTypes` / palette de couleurs) ;
      `NpcsCatalogTest` / `DialoguesCatalogTest` mis à jour (création réversible sans `confirm`).
      `:control-panel:test` vert ; `:control-panel:build` vert.
- [ ] Non traité (délibéré, à planifier) : `#114` sélecteur Citizens recherchable/paginé ;
      `#118` sélecteur de locuteur alimenté par les PNJ logiques ; `#113` modale de liaison
      dédiée (le formulaire inline mis en évidence reste).
- [ ] Validation navigateur authentifiée (mutation PNJ + dialogue, liaison Citizens, absence de
      F5 / refresh manuel, mobile) — `PENDING MANUAL VALIDATION`.

### Étape 3m — catalogue fusionné source + runtime pour `/quests` et `/stories` (#144) — **LIVRÉ (chemin principal ; validation navigateur en attente)**

- [x] **Cause** : `/quests` et `/stories` n'affichaient **que** le dernier relevé runtime de
      l'agent (`quest.list` / `story.list`). Une quête écrite dans la source par `ContentWorkspace`
      (`/quests/new`) restait invisible jusqu'à un rechargement RPGQuest côté serveur, et
      inutilisable comme prérequis ou étape de story.
- [x] **`SourceCatalog`** (`panel.content`, lecture seule) : relit `quests/*.yml` + `stories/*.yml`
      du checkout via `QuestYaml.read` / `StoryYaml.read` (mêmes relecteurs que l'éditeur). Jamais
      d'écriture, jamais de FTP, jamais de `content.reload`.
- [x] **Fusion dans `AgentPages`** (`quests()` / `stories()`) sur l'id « nu », avec un état
      explicite par entrée : `SYNCED` (aucun badge), `SOURCE_ONLY` (badge « Source uniquement » +
      note : pas encore chargée en jeu — **jamais** présentée comme active), `RUNTIME_ONLY` (badge
      « Hors source »). Source relue à chaque affichage ; « Rafraîchir » interroge toujours le
      serveur. Sans `content.repo-dir`, aucun badge d'origine.
- [x] **Lookups d'édition** : `AgentPages.referenceData()` fusionne les quêtes de la source
      (prérequis, chaîne de story) ; le diagnostic « quête inconnue dans la chaîne » en tient
      compte. Les listes d'**actions admin** restent limitées au runtime.
- [x] **Tests** : `SourceCatalogTest` (4), `MergedCatalogTest` (11 : runtime-only, source-only,
      fusion en une entrée, création éditeur visible sans appel agent, lookup story, lookup
      prérequis, refresh runtime préservant une source-only, aucune confusion « actif en jeu »,
      édition source reflétée, story source-only). `:control-panel:test` vert ; `./gradlew build`
      vert.
- [ ] Validation navigateur authentifiée (créer `lily_pumpkin`, retour `/quests`, badge, lookup
      `/stories/new`, refresh Quêtes) — `PENDING MANUAL VALIDATION`.

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
- [x] **V3 — passe UX ciblée éditeur de quêtes (#46, voir « Étape 3k »)** : un type =
      une source de vérité (jeux de champs rendus par le même `Descriptors`, bascule JS
      progressive au changement de `<select>` **sans recharger** + effacement du type
      précédent) ; actions de brouillon `formnovalidate` (validation métier seulement à
      la fin) ; ids stables + `formaction=".../save#ancre"` (recentrage du scroll) ;
      listes **recherchables** (`panel.js` `initCombo`, aucun CDN) alimentées par la
      bonne source (catégorie curée + saisie libre, icône = matériaux, libellés FR).
- [ ] **V2 restant** : lignes guidées pour prérequis / variables (au lieu de zones de
      texte) ; duplication d'objectif / de récompense ; action agent
      `quest.definition.validate` (relecture par le **vrai** parser à distance) ; schéma
      vertical de chaîne de story ; libellé humain pour le PNJ donneur (dépend d'un
      libellé exposé par `npc.list`).

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
