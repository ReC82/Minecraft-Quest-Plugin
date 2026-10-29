# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-29
* Heure : 21:15
* Sujet : #907 — PlugAdmin : vue de suivi de la file lqorch (lecture seule), premier lot
* Statut : DONE (livré et déployé ; recette propriétaire TC-446 en attente, ticket laissé ouvert)
* Branche Git : `feature/907-plugadmin-suivi-lqorch` (PR #923, fusionnée dans `chore/257-ci-github-actions`) ; rapport sur `docs/907-rapport-suivi-lqorch`
* Commit actuel si disponible : merge `92d5c03d` (code `e6f82c00` + `62403470`)
* Début de la tâche : 2026-10-29 20:29:01
* Fin de la tâche : 2026-10-29 21:15:51
* Durée totale : 00:46:50

## Demande
Ticket #907 (décision propriétaire du 29/10) : page PlugAdmin claire, en français, adaptée au mobile,
montrant la file du superviseur `lqorch` — En cours / Prêts / En attente du propriétaire / Bloqués /
Terminés récemment —, liens GitHub, priorité P0–P3, motifs d'exclusion, dernier examen, dernière
session, état du superviseur ; distinguer « ouvert sur GitHub » et « éligible » ; filtres ; cache sûr
sans appels GitHub excessifs ; pannes affichées ; permissions ; aucun secret ; **lecture seule,
aucun bouton sans effet** ; tests sur données fictives, QA 1280/390, CI. Aucune mutation du jeu,
aucun lancement forcé de session.

## Analyse
- L'état du superviseur est dans `~/.local/state/lodyquests-orch/state.db` (utilisateur `ubuntu`,
  HOME en `0750`) : illisible pour le service `plugadmin` (utilisateur `plugadmin`,
  `ProtectHome=true`). Donner au panel un accès direct à la base aurait couplé deux applications et
  exigé d'ouvrir le HOME.
- Le dernier examen de la file automatique (`auto_last`) est déjà mémorisé par le superviseur avec
  ses motifs : le panel n'a besoin d'**aucun** appel GitHub.
- Choix : le superviseur **publie** un instantané JSON assaini hors du HOME
  (`/var/lib/lodyquests-orch/queue.json`) ; le panel le lit, avec un cache par date + taille.
  Choix réversible, sans schéma de base ni format public nouveau côté jeu.

## Travail effectué
**Superviseur (`scripts/orchestrator/lqorch.py`)**
- `snapshot(con)` : tickets (statut, priorité, dépendances, PR, branche, résumé / tests / blocage /
  prochaine action / note tronqués, tentatives, dernière session courte, dates, retenue, rapports
  `docs/claude-reports/` qui citent le numéro exact), 40 dernières sessions (sans chemin de journal,
  PID ni coût), état (mode, vivant, code, motif d'attente, prochain démarrage possible, sessions du
  jour / plafond), session en cours, dernier examen `agent:auto` (admissibles, écartés + motif + rang).
- `write_snapshot(con, force)` : écriture atomique (`os.replace`), `0644`, au plus 1×/min, forcée au
  début et à la fin de chaque session et à l'arrêt ; appelée dans la boucle, dans `pause_for` et
  pendant une session. Un échec n'arrête rien : noté une fois (`snapshot_error`, événement,
  `lqorch status`).
- Le rang P0–P3 est désormais conservé sur les tickets **écartés** de l'examen automatique.
- `lqorch snapshot [--write]` ; `install.sh` crée `/var/lib/lodyquests-orch` (`ubuntu`, `0755`).
- Chemin : `LQ_SNAPSHOT`, sinon `/var/lib/…` — mais un `LQ_STATE_DIR` de test garde son instantané à
  côté de sa base (jamais l'instantané réel écrasé par les tests).

**PlugAdmin**
- `panel.orch.OrchSnapshot` (lecture tolérante : champ absent / mal typé → vide) et
  `OrchSnapshotReader` (cache date + taille, garde-fou 8 Mo, états `NOT_CONFIGURED` / `MISSING` /
  `UNREADABLE` / `INVALID` explicites).
- `web.OrchPages` → route `/orch` (« File de développement »), permission **`ORCH_READ`**
  (propriétaire par défaut, attribuable par groupe), entrée de menu Administration + tuile d'accueil.
  Cartes : superviseur (Actif / En pause / Arrêté + fraîcheur), session en cours, prochaine session
  (motif + heure de reprise), sessions du jour. Bandeaux : instantané absent / invalide (aucun ticket
  affiché plutôt qu'un état faux), > 5 min « ancien », > 15 min « ne publie plus », arrêté, en pause.
  Tickets repliables par groupe (lien GitHub, état, priorité `P0–P3` connue ou « prio N » locale,
  ligne « pourquoi », puis prochaine action, blocage, résumé, tests, note, statut dans la file
  automatique, PR liées, branche, dernière session, rapports). Terminés : 15 plus récents. Section
  « File automatique » expliquant ouvert ≠ admissible et « aucun admissible ≠ panne », avec les écartés
  et leur motif. 20 dernières sessions. Filtres état / priorité / texte par **formulaire GET** (pas de
  JavaScript, CSP inchangée). **Aucun formulaire POST, aucun bouton d'action.**
- Configuration : `PLUGADMIN_LQORCH_SNAPSHOT` ou `orch.snapshot`, défaut
  `/var/lib/lodyquests-orch/queue.json` (aucun changement de `/etc/plugadmin` nécessaire).
- CSS : listes compactes (pas de tableau) pour écartés et sessions, cartes 2 colonnes sous 576 px.

## Fichiers créés
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/orch/OrchSnapshot.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/orch/OrchSnapshotReader.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/OrchPages.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/orch/OrchSnapshotReaderTest.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/OrchPageTest.java`

## Fichiers modifiés
- `scripts/orchestrator/lqorch.py`, `test_lqorch.py`, `install.sh`, `README.md`
- `control-panel/…/authz/Permission.java` (`ORCH_READ`), `config/PanelConfig.java`,
  `config/PanelConfigLoader.java`, `web/PanelApp.java`, `web/Layout.java`, `web/HomePages.java`,
  `resources/assets/plugadmin.css`, `test/…/support/TestConfig.java`
- `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md` (TC-446)
- (PR de rapport) `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/claude-reports/README.md`, ce rapport

## Base de données / migrations
Aucune. Ni la base du jeu, ni celle du panel, ni la base d'état du superviseur ne changent de schéma.

## Configuration / données
- Nouveau dossier `/var/lib/lodyquests-orch/` (`ubuntu:ubuntu 0755`) et fichier `queue.json` (`0644`).
- Aucune donnée de jeu, aucun joueur, aucun ticket de travail modifié par la page.
- Vérification en ligne : un compte PlugAdmin jetable `claude907` (OWNER) a été créé puis **supprimé**
  (`panel_user` : 0 ligne restante) ; ses connexions restent dans le journal d'audit.

## Tests automatiques
- `python3 -W error::ResourceWarning -m unittest scripts/orchestrator/test_lqorch.py` : **40 OK**
  (7 nouveaux `SnapshotTest` : chemin de test isolé, écriture atomique `0644` sans journal/PID/coût ni
  appel GitHub, limitation 1×/min, motifs + rang, échec d'écriture signalé une fois puis rétabli,
  rapports au numéro exact, CLI).
- Panel ciblés : `OrchPageTest` (7 : anonyme → login, seul OWNER a `ORCH_READ`, groupes + liens +
  motifs + échappement + aucun POST, terminés limités, filtres, fichier absent / invalide,
  superviseur ancien / arrêté / en pause), `OrchSnapshotReaderTest` (3), plus
  `RolePermissionMatrixTest`, `EffectivePermissionsTest`, `GroupAdminHttpTest`, `HomeLauncherTest`,
  `ActionReturnPathTest`, `PanelConfigLoaderTest`, `AuthenticatedSmokeTest` : verts.
- CI GitHub : PR #923 verte (plugin, control-panel, web-api, scripts, artefacts) ; CI du merge
  `92d5c03d` verte (run 40945099232).
- QA visuelle locale (panel local port 8097 + Chromium Playwright) sur une **copie `.backup`** de
  l'état réel (144 tickets, 40 sessions) : 1280 et 390 px, `scrollWidth` = largeur (aucun
  débordement) ; filtres et section « File automatique » relus en capture ; tableaux remplacés par
  des listes après la première capture mobile (colonnes tronquées).

## Tests manuels à effectuer
**TC-446 — PENDING MANUAL VALIDATION** (propriétaire, PlugAdmin seulement, aucun test en jeu) : voir
`docs/MANUAL_TEST_PLAN.md`.

## Résultat attendu
Administration → « File de développement » : état du superviseur mis à jour il y a moins de deux
minutes, ticket en cours, 97 tickets en attente avec leur motif, examen automatique et écartés lisibles,
filtres fonctionnels, rien ne déborde sur téléphone, aucun bouton.

## Reset / retour à l'état initial
Rien à réinitialiser : la page ne modifie rien.

## Déploiement VeryGames
### À transférer
Rien : aucun changement du plugin ni du serveur Minecraft.
### Ne PAS transférer/altérer
JAR, `data.db`, configurations, contenus DEV : inchangés.
### Redémarrage requis
Aucun redémarrage Minecraft. PlugAdmin redémarré par `deploy.sh`.
### Migration automatique
Aucune.

**Déploiements réellement effectués (box AWS) :**
- Superviseur : `scripts/orchestrator/install.sh` (code installé `f7d2c2fdbee4`), `lqorch snapshot
  --write` (instantané publié 20:00 UTC), `lqorch reload` demandé — **appliqué à la fin de cette
  session** (le superviseur en cours `b5af38fdc439` ne publie pas encore : la page affiche
  « Instantané ancien » jusque-là).
- PlugAdmin : `scripts/plugadmin/deploy.sh` → `DEPLOYED commit=92d5c03d…`, `validation=CI verte sur
  92d5c03`, à 21:14:14 ; classes `panel.orch.*` et `OrchPages` présentes dans le JAR servi ; page
  `/orch` rendue en HTTPS avec le compte jetable (titre, « En attente du propriétaire » 97, dernier
  examen 29/10 20:25) ; compte supprimé.

## Rollback
- PlugAdmin : `scripts/plugadmin/rollback.sh` vers la release `/opt/plugadmin/releases/20261029-211412`
  (= `da2257c1`).
- Superviseur : réinstaller `scripts/orchestrator/` d'un commit antérieur à `e6f82c00` puis
  `lqorch reload` ; `/var/lib/lodyquests-orch/` peut rester (inerte) ou être supprimé.

## Logs / diagnostic
- `lqorch status` affiche « ⚠ Vue PlugAdmin (#907) : instantané non publié — … » en cas d'échec.
- `lqorch snapshot` affiche l'instantané ; `--write` le republie immédiatement.

## Documentation mise à jour
Bible (section « File de développement lqorch dans PlugAdmin »), `docs/current_state.md`,
`docs/MANUAL_TEST_PLAN.md` (TC-446), `scripts/orchestrator/README.md`, registre
`docs/SUIVI_VALIDATIONS_DEV.md`. Pas de page `docs-site/` correspondante (aucune pour `/ops` non plus).

## Limitations / travail restant
- La priorité **P0–P3** n'est connue que pour les tickets `agent:auto` vus au dernier examen (et
  seulement après le rechargement du superviseur pour les écartés) ; les autres montrent leur
  priorité locale « prio N ». Le filtre P0–P3 l'explique à l'écran.
- L'état ouvert / fermé GitHub n'est connu que pour l'examen automatique et à la reprise d'un ticket ;
  un ticket WAITING_OWNER fermé à la main reste affiché jusqu'à ce que le superviseur le relise.
- Rapports : nom du fichier (copiable), pas de lien direct (le fichier n'est pas sur `main`).
- Section « En attente » longue (97 tickets) : filtres et recherche pour s'y retrouver.
- Actions (hold / release / priorité) : non proposées, à décider séparément.

## Prochaine étape suggérée
Recette TC-446 par le propriétaire ; selon son retour, éventuel lot 2 (actions hold/release/priorité
avec vérification serveur, confirmation et audit) uniquement sur décision explicite.
