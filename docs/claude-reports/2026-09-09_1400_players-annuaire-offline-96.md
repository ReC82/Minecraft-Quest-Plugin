# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-09
* Heure : 14:00
* Sujet : #96 — feat(control-panel) : gérer les joueurs hors ligne depuis la page Joueurs
* Statut : DONE — annuaire + ban/unban livrés et déployés (DEV + AWS) ; droit de construction
  BLOQUÉ par #27 (documenté) ; ban/unban en réel + navigateur owner = PENDING
* Branche Git : `feat/control-panel-admin-tools`
* Commit au démarrage : `35cc5ee` — commits de la tâche : `275774f` (feat), `faf87ec` (docs), `<docs2>` (changelog exécution réelle + rapport)
* Début de la tâche : 2026-09-09 13:37:54
* Fin de la tâche : 2026-09-09 14:31:00
* Durée totale : 00:53:06

## Demande

Transformer `/players` (aujourd'hui centrée sur les joueurs connectés) en **annuaire
d'administration** : voir aussi les joueurs hors ligne déjà venus, ouvrir une fiche par joueur,
et agir dessus même hors ligne — avec des actions **typées, permissionnées, auditées**, chacune
explicite sur sa compatibilité offline. MVP obligatoire : **ban / unban** (online ou offline) et
**droit de builder**. Ne pas transformer la page en console. Volume : prévoir plusieurs milliers
de joueurs (pagination). Ne pas fermer #96, ne rien merger.

## SOURCE DE VÉRITÉ JOUEURS

**Serveur Paper (`OfflinePlayer`) + joueurs connectés.** `Bukkit.getOfflinePlayers()` = tous les
joueurs ayant un `playerdata` (donc déjà venus au moins une fois) ; complété par les connectés.
Aucune base de joueurs propre à PlugAdmin n'est créée — le futur passage SQLite→MariaDB (#42)
n'est pas impacté. RPGQuest possède bien une table `player_profiles` (`uuid`, `last_name`,
`created_at`, `updated_at`) mais elle n'expose pas de `findAll()` et n'est **pas** la source
retenue (Paper est plus complet : `firstPlayed`, `lastSeen`, `banned`, `hasPlayedBefore`).

La résolution **nom ↔ UUID** réutilise le `PlayerDirectory` / `BukkitPlayerDirectory` existant,
déjà *offline-aware* (`getOfflinePlayer(UUID)` direct, `getOfflinePlayer(String)` sur thread
async, jamais bloquant) : toute mutation résout d'abord l'**UUID canonique** avant d'agir.

## ACTION AGENT

Trois nouvelles entrées `AgentActionType` (aucune commande console, aucun SQL, aucune migration) :

- **`player.catalog`** — annuaire complet. Instantané des connectés sur le **thread principal**
  (Location = API main-thread), puis parcours de `getOfflinePlayers()` (lecture disque) sur un
  **thread asynchrone** Bukkit. Payload par joueur : `uuid`, `name`, `online`, `hasPlayedBefore`,
  `firstPlayed`, `lastSeen`, `banned` (+ `banReason` si banni), `world`/`x`/`y`/`z` **seulement
  si en ligne**. Détails agrégés : `total`, `online`, `offline`, `banned`, `truncated`. Cap de
  sécurité **5000** quand aucune limite n'est demandée (`truncated=true` → avertissement panel) ;
  borne dure 20000.
- **`player.ban`** — `BanList` de **profil** Paper (`Bukkit.getBanList(BanListType.PROFILE)` →
  `ProfileBanList#addBan(profile, reason, (Instant) null, "PlugAdmin")`). Fonctionne **hors
  ligne** ; si le joueur est connecté il est **expulsé** (`Player#kick`). **Raison obligatoire**
  (validée côté panel *et* côté exécuteur agent). Idempotent : re-bannir → `ok=true` code
  `ALREADY_BANNED` (raison mise à jour).
- **`player.unban`** — `ProfileBanList#pardon(profile)`. Idempotent : `NOT_BANNED` si rien à
  faire.

Nomenclature choisie après audit des conventions existantes (`player.list`, `player.variable.*`,
`player.resetnew.*`, `player.item.give`) : `player.catalog` (extension non cassante — `player.list`
reste « joueurs connectés »), `player.ban`, `player.unban`.

## ONLINE / OFFLINE

Badges avec **texte** (jamais que la couleur) : **● En ligne** (`text-bg-success`) / **○ Hors
ligne** (`text-bg-secondary`) / **Banni** (`text-bg-danger`). Ordre par défaut : connectés
d'abord, puis hors ligne par **dernière connexion décroissante**. Tri alternatif : **Nom A-Z**.
« Dernière connexion » en relatif (« il y a 5 min », « il y a 3 j », sinon date UTC).

## BAN / UNBAN

Livré. Depuis la fiche → **Actions** :

- **Bannir** (si non banni) : formulaire avec **raison obligatoire** (`required`, max 256),
  confirmation forte Bootstrap, `type=player.ban`, cible = **UUID** en champ caché.
- **Débannir** (si banni) : confirmation, `type=player.unban`.

Permission Control Panel dédiée **`PLAYER_MODERATE`** (OWNER uniquement ; `READ_ONLY` / `TESTER` /
`CONTENT_EDITOR` ne l'ont pas → ne voient pas et ne peuvent pas appeler ban/unban). Bans
**temporaires** : hors périmètre (l'API `Date`/`Instant` les rendrait possibles mais l'énoncé les
exclut sauf trivialité).

## BUILD RIGHT

**BLOQUÉ — pas livré comme fonctionnalité.** Motif précis :

- Le « droit de builder » sur LodyQuests = permission Bukkit. Sur cette branche
  (`feat/control-panel-admin-tools`), construire dans le Hub exige `rpgquest.admin.world`
  (`HubWorldProtectionListener.BYPASS_PERMISSION`), permission large (défaut `op`).
- L'issue **#27** (permissions granulaires `rpgquest.build.hub.*`, `BuildPermissionService`)
  existe mais vit **uniquement sur `feature/27-granular-permissions`** — elle n'est **pas
  fusionnée** ici (vérifié : `git merge-base --is-ancestor c915684 HEAD` → non).
- Même #27 **délègue la persistance à LuckPerms** (« compatible LuckPerms sans le rendre
  obligatoire ») : RPGQuest n'a **aucun store propre** pour « le joueur X peut construire dans le
  Hub ». `player.addAttachment(...)` sans persistance ne survivrait ni au logout ni au restart.

Décision (conforme au §19 de l'énoncé) : livrer le **catalogue offline + ban/unban + l'architecture
de capacité** ; poser la permission `PLAYER_BUILD_WRITE` (OWNER) pour cadrer l'évolution ; la
fiche affiche « droit de construction : **non géré par PlugAdmin** » avec l'explication, **sans
faux interrupteur qui ne contrôlerait rien**. À reprendre quand #27 est intégrée **et** qu'un
mécanisme de grant persistant existe.

## PERSISTANCE

- Ban / unban : persistés par Paper (`banned-players.json` via `BanList`) — survivent logout /
  reconnect / restart. Rien à stocker côté RPGQuest ou PlugAdmin.
- Catalogue : lecture seule, aucune persistance.
- Aucune nouvelle table, aucune migration (`SchemaMigrator` inchangé, version 17).

## PERMISSIONS

`control-panel` `authz.Permission` : ajout de **`PLAYER_MODERATE`** (ban/unban) et
**`PLAYER_BUILD_WRITE`** (réservé, non fonctionnel). `Role.OWNER` = `allOf` → les obtient ;
aucun autre rôle. `PLAYERS_READ` (déjà là) reste requis pour voir la page. `AgentActionCatalog` :
`player.catalog` → `PLAYERS_READ` (lecture) ; `player.ban` / `player.unban` → `PLAYER_MODERATE`
(mutation, `needsPlayer`, `confirm` obligatoire) ; `player.ban` valide en plus une `reason`
non vide ≤ 256 sans saut de ligne. Défense en profondeur : panel → agent → API Paper re-valident.

## AUDIT

Inchangé dans son mécanisme, déjà conforme au §17 : `PanelApp#createAgentAction` journalise
`agent.action.create` avec acteur (`session.username()`), `type`, `action` id, `PENDING`,
`safeParams` (inclut `player` = UUID cible **et** `reason`), `rid` (correlation id). Le résultat
(ancienne/nouvelle valeur : `ALREADY_BANNED` vs `BANNED` vs `NOT_BANNED`) revient dans le résultat
d'action, visible dans `/actions` (domaine **Joueurs**, mappé par le préfixe `player.`).

## UX

- **Toolbar compacte** : « Actualiser » (`player.catalog`, feedback toast #93). Pas de grande
  carte vide.
- **Recherche** : `input-group` Bootstrap, **GET serveur** (pseudo insensible à la casse **ou**
  UUID). Filtres **Tous / En ligne / Hors ligne / Bannis** = liens serveur (puce active marquée).
  Tri **Plus récent / Nom A-Z**. **Pagination 50** (Précédent / Suivant, « Page X / Y »). Tout
  côté serveur pour tenir le volume (§28).
- **Liste = accordion** : en-tête = pseudo (libellé principal) + « Dernière connexion : … » +
  badges. **UUID jamais en en-tête** — uniquement dans le détail (copiable).
- **Détail** : sections **Identité** (pseudo, UUID [Copier], état, 1re / dernière connexion),
  **Activité** (monde + position si en ligne ; sinon dernière présence), **RPGQuest** (liens vers
  `/quests?player=` `/stories?player=` `/actions?domain=players` — jamais un dump JSON),
  **Droits** (droit de construction : non géré + explication), **Modération** (statut ban + raison),
  **Actions**.
- **Capacité par action** (§15) : ban/unban/variables/reset portent le badge
  « hors ligne OK » ; « donner un objet » porte « en ligne uniquement » et, si le joueur est hors
  ligne, affiche « Indisponible : le joueur est hors ligne » **sans** bouton — jamais de faux
  succès (§23). Reset : workflow existant préservé (aperçu → confirmation, zone danger).
- **Mobile** : réutilise le CSS `.npc-*` déjà responsive (accordion pleine largeur, actions
  empilées) — pas de tableau horizontal géant.
- **Accessibilité** : boutons/liens sémantiques, badges avec texte, `aria-expanded` /
  `aria-controls` sur l'accordion (Bootstrap), `<label>` sur les champs, confirmations cochables.
- **Diagnostics #38** : **pas** de diagnostic par joueur hors ligne (offline = normal). Aucun
  ajout à `/diagnostics` (pas de règle réelle d'incohérence à ce stade).

## Fichiers créés

* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/PlayerCatalog.java` — modèle pur.
* `control-panel/src/main/resources/docs/joueurs-admin.md` — fiche `/docs`.
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/PlayerCatalogTest.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/PlayersPageTest.java`
* `docs/claude-reports/2026-09-09_1400_players-annuaire-offline-96.md` (ce rapport)

## Fichiers modifiés

* `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionType.java` — `PLAYER_CATALOG`,
  `PLAYER_BAN`, `PLAYER_UNBAN`.
* `src/main/java/com/lodygames/rpgquest/web/agent/AgentActions.java` — `PlayerCatalogEntry`,
  `playerCatalog`, `banPlayer`, `unbanPlayer`.
* `src/main/java/com/lodygames/rpgquest/web/agent/BukkitAgentActions.java` — implémentations
  (async pour la lecture disque, `BanList` de profil, `kick` si en ligne, helper `async`).
* `src/main/java/com/lodygames/rpgquest/web/agent/AgentActionExecutor.java` — dispatch +
  handlers `playerCatalog` / `playerBan` (raison obligatoire) / `playerUnban`.
* `src/test/java/com/lodygames/rpgquest/web/agent/StubAgentActions.java` +
  `AgentActionExecutorTest.java` — stubs + 5 tests.
* `control-panel/.../authz/Permission.java` — `PLAYER_MODERATE`, `PLAYER_BUILD_WRITE`.
* `control-panel/.../agent/AgentActionCatalog.java` — specs + validation `player.catalog` /
  `player.ban` (reason) / `player.unban`.
* `control-panel/.../web/AgentPages.java` — `players()` + détail réécrits (annuaire, accordion,
  recherche/filtre/tri/pagination serveur, capacités, ban/unban).
* `control-panel/.../web/BusinessPagesTest.java` — assertion toolbar `player.catalog`.
* `NPC_FORMAT.md` non concerné ; `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`,
  `docs/control-panel/ROADMAP.md`, `docs/deployment/SERVER_CHANGELOG.md`,
  `control-panel/src/main/resources/docs/_index.txt`.

## Base de données / migrations

Aucune. `SchemaMigrator` version 17 inchangée. `control-panel.db` non touché.

## Configuration / données

Aucun changement de configuration. Les bannissements sont dans `banned-players.json` géré par
Paper — jamais édité à la main.

## Tests automatiques (AUTOMATED)

* `./gradlew test build` — **BUILD SUCCESSFUL**. Plugin **1214/0** (29 ignorés MockBukkit,
  inchangés) ; control-panel **257/0** ; web-api **30/0**.
  - `AgentActionExecutorTest` **40/0** (+5) : `player.catalog` (compteurs online/offline/banned,
    cap 5000, limite explicite) ; `player.ban` (REJECTED sans raison ; SUCCESS résout l'UUID +
    passe la raison ; FAILED « Joueur inconnu ») ; `player.unban`.
  - `PlayerCatalogTest` **8/0** : parse tolérant, tri « connectés puis lastSeen desc », tri Nom
    A-Z, recherche pseudo (ci) / UUID, filtres exclusifs + combinés avec la recherche, pagination
    (slice + clamp).
  - `PlayersPageTest` **8/0** (HTTP **authentifié**, session owner de test) : badges avec texte,
    nom en libellé / UUID absent de l'en-tête, sections du détail, aucune saisie de commande
    brute (`name="command"` absent), recherche serveur, filtres serveur (puce active), ban validé
    (CSRF + `confirm` + `reason` obligatoires, cible = UUID, toast), unban présent si banni, give
    « en ligne uniquement » (indisponible si hors ligne), `?player=` pré-ouvre la fiche,
    pagination > 50.
  - `BusinessPagesTest` **9/0** (assertion toolbar `player.catalog`).
  - `PanelHardeningMalformedAgentDataTest` (#103) : le smoke authentifié `-DpanelProdDbCopy`
    contre une **copie de la vraie `control-panel.db`** (après le relevé `player.catalog` réel) →
    `/players` = **200** authentifié sur le payload de production réel.

## Validation live (LIVE)

* **Déploiement** : plugin RPGQuest → VeryGames DEV (JAR SHA `4f39e1e4…`, backup
  `rpgquest-20260909T142659Z-predeploy.jar`), **un seul** redémarrage RCON → serveur **ONLINE**
  (< 180 s, aucun message anti-auto-reboot). `/plugins` : Citizens / Multiverse-Core / RPGQuest /
  WorldEdit **verts**. Control Panel → AWS (release `20260909-141707`, jar `ee72425a…`).
* **`player.catalog` déclenché en réel** (relevé enfilé côté PlugAdmin) → **SUCCESS** :
  `total=3`, `online=0`, `offline=3`, `banned=0`, `truncated=false` — 3 joueurs réels déjà venus
  (`Rondoudou9000`, `Madix666`, `LoDyMcFly`) avec UUID + `firstPlayed` / `lastSeen` **crédibles**
  (LoDyMcFly vu le 2026-09-08, le plus récent). Personne de connecté sur DEV → pas de
  monde/position (attendu, correct). `npc.list` baseline → **SUCCESS inchangé** (8 PNJ).
* Rendu `/players` authentifié sur ce payload réel : **200** (via le smoke `-DpanelProdDbCopy`).
* `journalctl -u plugadmin` depuis le redéploiement : **0 `ERROR`**.

## PENDING OWNER

* **ban / unban en réel** : `PENDING MANUAL VALIDATION` — aucune cible de test sûre parmi les 3
  joueurs DEV (l'owner `LoDyMcFly` ne doit pas être banni ; `Rondoudou9000` / `Madix666` sans
  autorisation ; ne pas créer de faux joueur — §35). Logique couverte par les tests unitaires
  (`AgentActionExecutorTest`) et HTTP (`PlayersPageTest`).
* **Navigateur authentifié `/players`** par l'owner (mot de passe non détenu par Claude) : les
  5 étapes courtes de la section « Tests manuels ».

## Tests manuels à effectuer (courts, ciblés #96)

`PENDING OWNER` — 5 étapes :

1. Ouvrir **Joueurs**, cliquer **Actualiser**.
2. Vérifier la présence d'au moins **un joueur en ligne** et **un joueur hors ligne** avec des
   « dernière connexion » crédibles.
3. Rechercher un **joueur hors ligne** par son pseudo.
4. Ouvrir sa fiche → vérifier Identité / Activité / Modération.
5. (Optionnel, cible de test sûre uniquement) **Bannir** puis **Débannir** ce joueur ; vérifier
   les toasts et l'entrée dans `/actions` (domaine Joueurs).

## Résultat attendu

`/players` est un annuaire : recherche + filtres En ligne / Hors ligne / Bannis, liste compacte
(pseudo · statut · dernière connexion), fiche au clic avec Identité / Activité / RPGQuest /
Droits / Modération / Actions, et **Bannir / Débannir** utilisables sur un joueur hors ligne,
chaque action indiquant clairement si elle fonctionne hors ligne.

## Reset / retour à l'état initial

`git revert` des commits `#96`, ou rollback JAR VeryGames + `rollback.sh app` côté AWS. Un joueur
banni pendant un test doit être débanni séparément (`player.unban` ou `/pardon <uuid>` console) —
un rollback de JAR n'annule pas un ban Paper.

## Déploiement — effectué

- **VeryGames DEV** : `scripts/deploy-verygames.sh -y` (gate `./gradlew test`+`build` OK), JAR
  `rpgquest-0.1.0-SNAPSHOT.jar` 1 437 876 o SHA-256 `4f39e1e41e6860921a38b9c5893f5f56b95a0fac7fc1500d321ca2b64316765c` ;
  backup `~/.local/share/rpgquest/verygames-backups/rpgquest-20260909T142659Z-predeploy.jar`
  (SHA-256 `d9a47cf868991dae8f6a072856f1f0519e87fd201cfc63388c486e4b0ba9f7ee`). **Un seul**
  `scripts/verygames-restart.sh` → OFFLINE → relance auto → **ONLINE**. `/plugins` verts.
- **Control Panel AWS** : `scripts/plugadmin/deploy.sh` — release
  `/opt/plugadmin/releases/20260909-141707`, jar SHA-256
  `ee72425ae36d87b3a52bfc54c5f214aed7eb44580b1f240fb45060df040507dc` (byte-identique au build).
  `/health` ONLINE local + public ; routes anon → 303 ; CSP inchangée ; service `active`
  `NRestarts=0`.

## Déploiement VeryGames — procédure de référence

### À transférer
Le **JAR RPGQuest uniquement** (`scripts/deploy-verygames.sh -y`, backup daté auto).

### Ne PAS transférer/altérer
`data.db`, `config.yml`/`messages.yml`/`spawn.yml`, `RPGQuest/Citizens/`, tout autre plugin,
tout monde. `banned-players.json` : géré par Paper, jamais édité.

### Redémarrage requis
Oui — **un seul** : `scripts/verygames-restart.sh` (stop RCON → relance auto). Vérifier ONLINE.
Règle anti-auto-reboot : si « 10 auto-reboot in less than 30 minutes … We exit now » → STOP,
aucun retry, rapporter « VeryGames auto-reboot safety triggered », attendre relance manuelle.

### Migration automatique
Aucune.

## Rollback

`scripts/rollback-verygames.sh --latest` + `scripts/verygames-restart.sh` (plugin) ;
`scripts/plugadmin/rollback.sh app` (Control Panel). Aucune migration à défaire.

## Logs / diagnostic

Après restart : `/plugins` (RCON) verts, heartbeat agent ONLINE, `player.catalog` → SUCCESS,
`journalctl -u plugadmin` 0 `ERROR`.

## Documentation mise à jour

`control-panel/src/main/resources/docs/joueurs-admin.md` (+ `_index.txt`), `docs/RPGQUEST_BIBLE.md`,
`docs/current_state.md`, `docs/control-panel/ROADMAP.md` (Étape 3j), `docs/deployment/SERVER_CHANGELOG.md`.

## Limitations / travail restant

* **Droit de construction persistant : BLOQUÉ par #27** (voir « BUILD RIGHT »).
* Bans **temporaires** : hors périmètre.
* `player.catalog` n'expose pas encore la santé / le game mode d'un joueur en ligne (non requis
  par #96, éviter le dump).
* Protocole prêt pour `player.kick` / `player.mute` / `player.whitelist` / `player.teleport` /
  `player.give` / `player.note` … (mêmes patterns `AgentActionType` + `AgentActionCatalog` +
  capacité) — non implémentés (§32).
* Validation navigateur authentifiée par l'owner : `PENDING OWNER`.

## Prochaine étape suggérée

Intégrer #27 (permissions granulaires) sur cette branche, puis livrer `PLAYER_BUILD_WRITE`
(grant `rpgquest.build.hub` persistant via le mécanisme retenu). Ensuite, kick / whitelist si
utile.
