# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-09
* Heure : 06:07 (locale / UTC sur cette box)
* Sujet : Déploiement AWS du Control Panel (#92 refonte UX + #46 éditeur guidé) et activation
  réelle de l'éditeur de contenu (workspace inscriptible)
* Statut : DONE (déploiement effectué et vérifié ; validation navigateur authentifiée = PENDING)
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel si disponible : `498d46e6e38c336ef94fba9f2c7c393e75ce2952`
* Début de la tâche : 2026-09-09 06:02:50
* Fin de la tâche : 2026-09-09 06:08:30
* Durée totale : 00:05:40

---

## Demande

Le code #92/#46 était committé et poussé mais **jamais déployé** : `https://plugadmin.lodylands.com`
servait toujours l'ancien PlugAdmin sombre (release du 2026-09-08 20:10, commit `c4d1290` / #49).
Demande : **uniquement finaliser le déploiement** et rendre #46 réellement utilisable — sans
toucher au design, sans refactor, sans nouvelle fonctionnalité.

Étapes imposées : vérifier la branche ; `scripts/plugadmin/deploy.sh` ; configurer
`PLUGADMIN_CONTENT_DIR` hors Git ; donner à l'utilisateur du service `plugadmin` **uniquement** les
droits nécessaires sur `src/main/resources/quests` et `.../stories` (ACL ciblée `setfacl` de
préférence) ; validation technique post-déploiement (rendu, routes, workspace inscriptible) ;
sécurité (anon→login, CSRF, path traversal, aucune écriture hors des 2 dossiers, pas d'accès
`.env`/secrets/mondes/`data.db`, aucune erreur serveur) ; vérifier `dig.lodygames.com` et
`lodylands.com` toujours disponibles ; **ne pas toucher VeryGames / Minecraft**.

---

## Analyse

### État avant

* Service `plugadmin.service` (systemd) : `User=plugadmin` (uid 999), `Group=plugadmin` (gid 988),
  `EnvironmentFile=/etc/plugadmin/plugadmin.env`, `ExecStart=/opt/plugadmin/app/bin/control-panel`.
  Durcissement : `ProtectSystem=strict` (**tout le FS en lecture seule** sauf `ReadWritePaths`),
  `ProtectHome=true`, `PrivateTmp=true`, `ReadWritePaths=/var/lib/plugadmin`.
* App déployée : `/opt/plugadmin/app`, jar `control-panel-0.1.0-SNAPSHOT.jar` du 2026-09-08 20:10
  (219 389 o). **Aucune** classe `Icons` / `ContentEditorPages` / `panel.content.*` → ancienne
  release confirmée.
* `/srv/rpgquest/repo/src/main/resources/{quests,stories}` : `drwxrwxr-x ubuntu:ubuntu`, fichiers
  `-rw-rw-r-- ubuntu:ubuntu`. `plugadmin` n'a que `r-x` (others) → **pas d'écriture**.
* Paquet `acl` **non installé** ; FS racine ext4 `rw` (ACL supporté nativement).
* `RPGQUEST_PANEL_CONFIG=/etc/plugadmin/control-panel.properties` (config non secrète, hors Git).

### Deux obstacles à l'écriture, indépendants

1. **DAC** : `plugadmin` n'a aucun droit d'écriture POSIX sur les deux dossiers.
2. **Bac à sable systemd** : `ProtectSystem=strict` rend `/srv` en lecture seule pour le service,
   **quels que soient** les droits POSIX/ACL.

→ Il faut traiter **les deux** : ACL ciblée **et** `ReadWritePaths` limité à ces deux dossiers.

### Décisions

| Sujet | Décision | Raison |
|---|---|---|
| Outil ACL | `apt-get install -y acl` (n'était pas là) | l'énoncé préfère `setfacl` ; alternative `chgrp`/setgid casse l'édition des nouveaux fichiers par le propriétaire du dépôt |
| Portée ACL | `u:plugadmin:rwx` sur `quests/` + `stories/` uniquement, `default:` idem pour les nouveaux fichiers, `rw` sur les `*.yml` existants, `default:g:ubuntu:rwx` pour garder l'accès du propriétaire aux fichiers créés par le panel | strictement les 2 dossiers, rien d'autre ; parents déjà traversables (`o+x`) |
| `PLUGADMIN_CONTENT_DIR` | ajouté à `/etc/plugadmin/plugadmin.env` (EnvironmentFile, hors Git, 640 root:plugadmin), backup `.bak-20260909` | « le mécanisme d'environnement déjà utilisé » |
| Bac à sable | drop-in `/etc/systemd/system/plugadmin.service.d/10-content-workspace.conf` avec `ReadWritePaths=` des 2 dossiers seulement | ne pas éditer l'unité de base ; additif et réversible |
| VeryGames | non touché | aucun changement plugin/agent |

---

## Travail effectué

1. **Audit branche** : `feat/control-panel-admin-tools`, working tree **propre**, HEAD `498d46e`.
   Commits présents et ancêtres de HEAD : `9202aad`, `0e529c5` (#92), `bd1f20f`, `498d46e` (#46).
2. `sudo apt-get install -y acl` → `setfacl 2.3.2`.
3. **ACL ciblées** sur `/srv/rpgquest/repo/src/main/resources/quests` et `.../stories` :
   ```
   setfacl -m  u:plugadmin:rwx   <dir>
   setfacl -d -m u:plugadmin:rwx  <dir>     # nouveaux fichiers
   setfacl -d -m g:ubuntu:rwx     <dir>     # le propriétaire du dépôt garde l'accès aux fichiers créés par le panel
   setfacl -m  u:plugadmin:rw-   <dir>/*.yml
   ```
   `getfacl` après : `user:plugadmin:rwx` + `mask::rwx` sur les dossiers, `user:plugadmin:rw-`
   sur `crystal_hunt.yml` / `first_steps.yml` / `premiers_pas.yml` / `woodcutters_request.yml`
   et `main_story.yml`. Propriétaire `ubuntu` conserve `user::rwx` / `user::rw-`.
4. **EnvironmentFile** : backup `/etc/plugadmin/plugadmin.env.bak-20260909`, puis ajout
   `PLUGADMIN_CONTENT_DIR=/srv/rpgquest/repo/src/main/resources` (perms inchangées, 640
   root:plugadmin).
5. **Drop-in systemd** `/etc/systemd/system/plugadmin.service.d/10-content-workspace.conf` :
   ```
   [Service]
   ReadWritePaths=/srv/rpgquest/repo/src/main/resources/quests
   ReadWritePaths=/srv/rpgquest/repo/src/main/resources/stories
   ```
   `systemctl daemon-reload`.
6. **Déploiement** : `scripts/plugadmin/deploy.sh` — `./gradlew :control-panel:installDist`
   (BUILD SUCCESSFUL), sauvegarde `/opt/plugadmin/app` → `/opt/plugadmin/releases/20260909-060511`,
   copie de la nouvelle distribution, `systemctl restart plugadmin`, `/health` local OK.
7. **Vérifications** (section suivante).

Aucun autre fichier du dépôt modifié. Aucun `chmod 777`, aucun `chown` du dépôt, aucun accès
`.env` / secrets / mondes / `data.db` accordé. VeryGames non touché.

---

## Fichiers créés

* `/etc/systemd/system/plugadmin.service.d/10-content-workspace.conf` *(hors dépôt — serveur AWS)*
* `/etc/plugadmin/plugadmin.env.bak-20260909` *(backup, hors dépôt)*
* `/opt/plugadmin/releases/20260909-060511/` *(release précédente sauvegardée par deploy.sh)*
* `docs/claude-reports/2026-09-09_0607_deploiement-aws-92-46-activation-editeur.md` *(ce rapport)*

## Fichiers modifiés

* `/etc/plugadmin/plugadmin.env` *(hors dépôt)* — ajout `PLUGADMIN_CONTENT_DIR`.
* `/opt/plugadmin/app/**` *(hors dépôt)* — nouvelle distribution `:control-panel:installDist`.
* ACL POSIX de `src/main/resources/quests` + `.../stories` et de leurs `*.yml` *(métadonnées FS,
  pas de contenu Git modifié)*.
* `docs/claude-reports/README.md` — ligne d'index.

Aucun fichier de code / de contenu du dépôt modifié (`git status` propre avant et après).

---

## Base de données / migrations

Aucune.

---

## Configuration / données

* Nouvelle variable d'environnement du service : `PLUGADMIN_CONTENT_DIR=/srv/rpgquest/repo/src/main/resources`
  dans `/etc/plugadmin/plugadmin.env`.
* Drop-in systemd `10-content-workspace.conf` : `ReadWritePaths` limité à `quests/` + `stories/`.
* ACL : `u:plugadmin:rwx` (dossiers, + default) / `u:plugadmin:rw-` (fichiers `*.yml` existants),
  `default:g:ubuntu:rwx`.

---

## Tests automatiques

Non réexécutés ici (aucun code modifié dans cette tâche). Rappel de la session précédente :
`:control-panel:test` 169/0, `./gradlew build` vert. Le jar déployé est **byte-identique** à un
build frais du working tree (SHA-256 `8ed58f74…`, 99 classes).

---

## Validation technique post-déploiement (réellement exécutée)

### Jar déployé = HEAD

* `/opt/plugadmin/app/lib/control-panel-0.1.0-SNAPSHOT.jar` — SHA-256 `8ed58f74ae60…a73e3e`,
  **identique** au jar fraîchement rebâti depuis le working tree (HEAD `498d46e`, arbre propre).
* Classes présentes dans le jar déployé : `panel/web/Icons`, `panel/web/ContentEditorPages`
  (46 116 o), `panel/content/ContentWorkspace`, `Descriptors`, `MiniYaml`, `QuestYaml`,
  `Diagnostic`, `RefData`, `TextDiff`, `StoryYaml`… → #92 **et** #46 dans la release en ligne.
* `PanelApp.class` (strings) : routes `/quests/new`, `/quests/edit`, `/quests/save`,
  `/stories/new` présentes.
* `Layout.class` : `--sidebar:#1d2534`, `topbar`, `brand-mark`, `nav-group`, `srv-chip` →
  design system #92 embarqué. `assets/panel.js` : `initFilters` / `initDrawer` présents.

### Service

* `systemctl status plugadmin` : `active (running)` depuis 2026-09-09 06:05:24 UTC,
  `Main PID 299952 (java)`, **`NRestarts=0`** (pas de boucle de crash), drop-in
  `10-content-workspace.conf` chargé.
* `systemctl show` : `ReadWritePaths=/var/lib/plugadmin /srv/rpgquest/repo/src/main/resources/quests
  /srv/rpgquest/repo/src/main/resources/stories`, `ProtectSystem=strict`.
* Logs de démarrage : `event=panel_started port=8090 target=dev disabled=false`, heartbeats agent
  `rpgquest-dev` OK. **Aucun ERROR / Exception** (`journalctl -u plugadmin` depuis le déploiement).

### `/health`

* Local : `curl http://127.0.0.1:8090/health` → `{"panel":"ONLINE","disabled":false,...}`
* Public : `curl https://plugadmin.lodylands.com/health` → `{"panel":"ONLINE","disabled":false,...}`

### Rendu HTTP (non authentifié — pas d'accès au mot de passe owner)

* `https://plugadmin.lodylands.com/login` → **200**, HTML 37 797 o, contient : tokens du nouveau
  thème (`--bg:#f4f6fb`, `--sidebar:#1d2534`, `--primary:#2f6df6`), **sprite SVG** inline
  (`<svg width="0" height="0"` + `id="i-…"`), `brand-mark`, `nav-toggle` (drawer) → **refonte #92
  bien servie**.
* Routage anonyme (attendu : redirection vers `/login`) :

  | Chemin | Résultat |
  |---|---|
  | `/` | 303 → `/dashboard` |
  | `/dashboard` `/quests` `/stories` `/npcs` `/players` `/dialogues` `/docs` | 303 → `/login` |
  | **`/quests/new`** | **303 → `/login`** (route présente, protégée) |
  | **`/quests/edit/first_steps`** | **303 → `/login`** |
  | **`/stories/new`** | **303 → `/login`** |
  | **`/quests/save`** | **303 → `/login`** (POST protégé) |

* `/assets/panel.js` → 200 `application/javascript`, contient `initFilters` / `initDrawer` / `initCopy`.
* En-têtes `/login` **inchangés** : `Content-Security-Policy: default-src 'self'; style-src 'self'
  'unsafe-inline'; img-src 'self' data:; form-action 'self'; frame-ancestors 'none'`,
  `X-Frame-Options: DENY`, cookie `panel_login_csrf` `HttpOnly; SameSite=Lax; Secure; Max-Age=600`.

### Workspace réellement inscriptible (test sous le bac à sable réel du service)

`systemd-run` transitoire répliquant `User=plugadmin` + `ProtectSystem=strict` + `ProtectHome` +
`PrivateTmp` + les mêmes `ReadWritePaths` :

* Écriture `quests/` : `écrit .tmp puis mv` → **OK**, fichier `plugadmin:plugadmin 664`.
* Suppression `quests/` : **OK**.
* Écriture + suppression `stories/` : **OK**.
* Écriture `src/main/resources/__nope__` (dossier parent, hors périmètre) → **`Read-only file
  system`** (bloqué par `ProtectSystem=strict`).
* Écriture `src/main/resources/config.yml`, `/srv/rpgquest/repo/__nope__`,
  `/etc/plugadmin/plugadmin.env`, `/etc/plugadmin/control-panel.properties` → **toutes refusées**.
* `repo/.env` → non lisible / absent. `EnvironmentFile` lisible (attendu, c'est sa fonction ;
  640 root:plugadmin, non inscriptible).

→ L'éditeur #46 rendra le formulaire en **lecture-écriture** (bouton « Enregistrer dans la
source » actif), avec une écriture **strictement confinée** à `quests/` et `stories/`.

### Autres services AWS

* `https://dig.lodygames.com/` → **200** · `https://lodylands.com/` → **200** ·
  `nginx` / `dig.service` / `lodyland.service` → **active**. Rien d'autre touché.

---

## Tests manuels à effectuer (PENDING MANUAL VALIDATION)

Nécessitent l'authentification owner (mot de passe non détenu par l'assistant) :

1. Se connecter à `https://plugadmin.lodylands.com` : constater le thème clair, la topbar, la
   sidebar à icônes groupée, le dashboard #92 en cartes, le drawer sur mobile.
2. `/docs`, `/players`, `/npcs`, `/quests`, `/stories`, `/dialogues` : en-têtes standard,
   recherche + filtres, cartes lisibles.
3. **Chemin principal #46** : `/quests` → « Créer une quête » → titre / description / catégorie →
   objectif « Tuer des araignées ×5 » (type + `SPIDER` via datalist + `5`) → objectif « Parler au
   Garde » (`TALK_TO_NPC` + `guard`) → récompense XP → « Vérifier » (diagnostics + aperçu YAML +
   diff) → « Enregistrer dans la source » → constater `src/main/resources/quests/<slug>.yml` créé
   (propriétaire `plugadmin:plugadmin`, lisible/éditable par `ubuntu`) → « Modifier » → changer
   une valeur → ré-enregistrer (pas de conflit) → créer une story, ajouter plusieurs quêtes,
   réordonner avec les flèches, « Vérifier ».
4. Confirmer que l'éditeur **n'est pas** en lecture seule (pas de bannière « lecture seule »).
5. Après création d'un fichier via le panel : `git status` doit le voir ; `git checkout --` doit
   pouvoir l'annuler (le propriétaire du dépôt garde l'accès via `default:g:ubuntu:rwx`).

Aucun test client Minecraft (aucun changement plugin/agent).

---

## Résultat attendu

`https://plugadmin.lodylands.com` sert désormais la refonte #92 (thème clair, topbar, sidebar à
icônes, dashboard en cartes) et expose l'éditeur guidé #46 (`/quests/new`, `/stories/new`,
« Modifier » sur les cartes) en **écriture réelle** vers `src/main/resources/quests|stories`
uniquement — jamais ailleurs, jamais le serveur live, aucun déploiement automatique.

---

## Reset / retour à l'état initial

### Rollback applicatif (revenir à l'ancien PlugAdmin #49)

```
scripts/plugadmin/rollback.sh app
```
Restaure `/opt/plugadmin/releases/20260909-060511/` (jar `control-panel-0.1.0-SNAPSHOT.jar`
219 389 o, 2026-09-08 20:10, **sans** classes #92/#46) → `/opt/plugadmin/app`, restart, `/health`.
L'ancien jar ignore simplement `PLUGADMIN_CONTENT_DIR` (pas besoin de défaire la config).

### Rollback configuration (optionnel, si l'on veut aussi retirer l'activation #46)

```
sudo rm /etc/systemd/system/plugadmin.service.d/10-content-workspace.conf
sudo cp -a /etc/plugadmin/plugadmin.env.bak-20260909 /etc/plugadmin/plugadmin.env
sudo systemctl daemon-reload && sudo systemctl restart plugadmin
sudo setfacl -b /srv/rpgquest/repo/src/main/resources/quests /srv/rpgquest/repo/src/main/resources/stories
sudo setfacl -b /srv/rpgquest/repo/src/main/resources/quests/*.yml /srv/rpgquest/repo/src/main/resources/stories/*.yml
# facultatif : sudo apt-get purge -y acl
```

### Code

`git` : rien à défaire — aucun fichier du dépôt modifié.

---

## Déploiement VeryGames

**Non applicable. VeryGames / Minecraft NON touché** : aucun changement plugin/agent, aucun
`verygames-restart.sh`, aucun redémarrage. La règle anti-auto-reboot VeryGames n'a pas eu à
s'appliquer (aucune tentative de restart).

---

## Rollback

Voir « Reset / retour à l'état initial ». Point de rollback applicatif :
`/opt/plugadmin/releases/20260909-060511/` via `scripts/plugadmin/rollback.sh app`.

---

## Logs / diagnostic

* `journalctl -u plugadmin` depuis 06:05:23 : **0 ERROR**, 0 Exception, 0 `Read-only file system`
  imputable au service ; heartbeats agent `rpgquest-dev` `env=dev version=0.1.0-SNAPSHOT` OK.
* Les écritures de contenu sont journalisées côté panel en `quests.content.write` /
  `stories.content.write` (audit) dès qu'un enregistrement sera fait via l'UI.

---

## Documentation mise à jour

* `docs/claude-reports/README.md` — ligne d'index de ce rapport.
* Ce rapport documente l'exécution ; la doc fonctionnelle (`docs/control-panel/CONFIGURATION.md`,
  `SECURITY.md`, `ROADMAP.md`, `RPGQUEST_BIBLE.md`, `current_state.md`) a été mise à jour dans la
  session de code précédente (commit `498d46e`) et reste exacte — l'activation serveur décrite
  y est désormais réellement en place.

---

## Limitations / travail restant

* **Validation navigateur authentifiée** du rendu #92 et du chemin principal #46 : PENDING
  (mot de passe owner non détenu par l'assistant).
* Fichiers créés par le panel : propriétaire `plugadmin:plugadmin`, mode `664`, groupe
  `plugadmin`. Le propriétaire du dépôt (`ubuntu`) y accède via l'ACL par défaut
  `g:ubuntu:rwx` (et peut de toute façon les remplacer/supprimer, étant propriétaire du
  dossier). Une édition **en place** par un autre utilisateur non membre du groupe `plugadmin`
  retomberait sur `other::r--` — cas non pertinent ici.
* V2 #46 (inchangé) : lignes guidées prérequis/variables, duplication de ligne, rechargement des
  champs au changement de type, action agent `quest.definition.validate`.
* #92 : refonte de contenu des pages Agents / placeholders.

---

## Prochaine étape suggérée

1. Validation navigateur authentifiée (liste « Tests manuels »).
2. Exécuter une fois le chemin complet de création de quête via l'UI et vérifier le fichier
   produit + `git diff`.
3. Reprendre le backlog V2 #46 et la fin de #92.
