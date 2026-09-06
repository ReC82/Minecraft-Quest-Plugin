# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-06
* Heure : 07:56
* Sujet : Issue #10 — automatiser le déploiement VeryGames (backup + rollback), et préparer une méthode sûre de configuration locale des identifiants FTP hors Git
* Statut : DONE (transfert FTP réel : PENDING MANUAL VALIDATION — hôte et mot de passe VeryGames pas encore disponibles)
* Branche Git : feature/10-verygames-deploy-scripts
* Commit actuel si disponible : voir `git log -1` (commit unique de la branche, créé en fin de tâche)
* Début de la tâche : 2026-09-06 07:43:18 (heure locale réelle)
* Fin de la tâche : 2026-09-06 08:05:00 (heure locale réelle)
* Durée totale : 00:21:42

## Demande

Prendre l'issue GitHub #10, respecter `CLAUDE.md`. Contexte fourni par
l'utilisateur : VeryGames ne fournit que du **FTP** (pas de SFTP) ; le compte
FTP prévu est **`awsplugin`**, restreint au dossier `plugins/` ; seul ce nom
d'utilisateur est connu — **l'hôte et le mot de passe ne sont pas encore sur
AWS**.

Priorités explicites :

1. préparer d'abord une **méthode sûre de configuration locale des identifiants,
   hors Git** — quel fichier créer, chemin exact, format, variables attendues,
   permissions Unix, comment vérifier qu'il est ignoré par Git ;
2. créer un exemple documenté **sans aucun vrai secret** ;
3. tant que l'hôte / le mot de passe manquent : **ne tenter aucune connexion
   FTP**, ne pas bloquer — préparer scripts, validation des variables,
   documentation, backup/rollback et un mode `--dry-run` ;
4. à la fin : donner les **commandes exactes** à exécuter sur AWS pour créer et
   sécuriser le fichier d'identifiants ;
5. **ne déployer aucune nouvelle version** sans autorisation explicite.

## Analyse

### Audit de l'existant

- **Aucun dossier `scripts/`** dans le dépôt avant cette tâche.
- `docs/deployment/VERYGAMES.md` : procédure **entièrement manuelle** (FTP port
  21, scénarios installation neuve / mise à jour du seul JAR / migration
  complète). Section « Rollback » = liste de fichiers à copier + procédure
  manuelle. Indiquait déjà « ne jamais stocker le mot de passe FTP dans Git ».
- `docs/deployment/SERVER_CHANGELOG.md` : journal daté des actions serveur.
- Convention de secret déjà présente dans le projet : `web-api/web-api.properties`
  (ignoré) + `web-api/web-api.properties.example` (versionné, sans secret),
  secret par variable d'environnement `RPGQUEST_WEB_API_TOKEN`. Les nouveaux
  scripts suivent le même patron.
- Outils disponibles sur AWS : `curl`, `ftp`, `wget` (pas de `lftp`, pas de
  `sftp` utile côté VeryGames). **`curl`** retenu : présent partout,
  scriptable, codes de sortie exploitables, upload/download/listing FTP +
  FTPS, renommage via `-Q`.
- JAR produit : `build/libs/rpgquest-0.1.0-SNAPSHOT.jar` (confirmé par
  `docs/deployment/VERYGAMES.md`).
- Compte `awsplugin` chrooté sur `plugins/` ⇒ après connexion la racine FTP
  *est* `plugins/` ⇒ le JAR se dépose à la racine de session (`VERYGAMES_FTP_REMOTE_DIR=/`).

### Décisions structurantes

- **Branche partie de `origin/main`** : l'issue #10 est de l'outillage
  d'exploitation, sans dépendance au code du plugin ; la « branche
  d'intégration » mentionnée dans `CLAUDE.md` (`feature/23-mod-prototype`) est
  désormais mergée dans `main` (PR #7), donc on applique la cible à terme
  (« les nouvelles tâches partent de `main` »). Conséquence mineure : l'entrée
  `SERVER_CHANGELOG.md` ajoutée ici devra être fusionnée-en-append avec les
  entrées des branches #8/#11/#27 non encore mergées.
- **Fichier d'identifiants hors dépôt**, emplacement par défaut
  `~/.config/rpgquest/verygames.env` (XDG, hors repo donc *impossible* à
  committer), surchargeable par `RPGQUEST_VERYGAMES_ENV`. Précédence :
  variables d'environnement exportées > fichier `$RPGQUEST_VERYGAMES_ENV` >
  `~/.config/rpgquest/verygames.env`.
- **Sécurité du mot de passe** : jamais sur une ligne de commande (invisible
  dans `ps`). `curl` reçoit `user = "USER:PASS"` via un fichier `--config`
  temporaire créé en mode 600 et supprimé juste après ; les valeurs `"` et `\`
  du mot de passe sont échappées pour la syntaxe curl. Les messages passent par
  `vg::redact` (masque toute occurrence du mot de passe). Le script **refuse**
  le fichier d'identifiants s'il n'est pas en mode `600`/`400`.
- **Transfert atomique** : upload sous `<jar>.part-<UTC>Z`, vérification de la
  taille distante, puis `RNFR`/`RNTO` vers le nom final (repli sur upload
  direct si le serveur refuse le renommage). Le JAR final n'est jamais un
  fichier à moitié transféré.
- **Backups** : téléchargés sur le disque **local AWS**
  (`~/.local/share/rpgquest/verygames-backups/`), nommés
  `rpgquest-<UTC>Z-{predeploy,prerollback}.jar` + sidecar `.meta` (SHA-256,
  taille, commit qui a remplacé, opérateur, date). Noms horodatés ⇒ jamais
  d'écrasement. Rétention opt-in (`--prune-keep N` / `VERYGAMES_BACKUP_KEEP`),
  ne supprime jamais le plus récent.
- **Ce qui reste manuel (contrainte VeryGames, documenté)** : arrêt et
  redémarrage du serveur (pas d'API/RCON), et la vérification post-redémarrage
  (`/rpgquest version`). Le script demande confirmation « serveur arrêté ? »
  avant d'écrire et affiche la checklist de redémarrage à la fin.

## Travail effectué

### `scripts/lib/verygames-common.sh` (sourcé)

Fonctions partagées : journalisation colorée sur stderr, `vg::redact`,
`vg::load_config` (précédence env > fichier, contrôle du mode 600, défauts),
`vg::validate_config [--require-connection]` (JAR non vide, port 1..65535, TLS
∈ auto/require/none ; avec connexion : host sans schéma/espace/slash, user/pass
non vides), `vg::config_summary` (résumé masqué), `vg::curl` (curl durci :
`--disable --config <tmp600> --fail --show-error --silent --connect-timeout
--max-time --ftp-pasv`), opérations FTP haut niveau
(`vg::remote_file_exists|_size|_list|_download|_upload|_rename|_delete`,
`vg::connectivity_check`), `vg::sha256`, `vg::utc_stamp`, `vg::backup_files`
(glob trié), `vg::looks_like_jar` (signature `PK`), `vg::require_curl`.

### `scripts/deploy-verygames.sh`

`set -euo pipefail`. Options : `--dry-run`, `--check`, `--skip-build` (dry-run
only), `--allow-dirty`, `--allow-no-backup`, `--server-stopped`, `--prune-keep
N`, `--jar PATH`, `-y`, `--help`.

Déroulé : config → (`--check` : valide + teste la connexion si identifiants
présents, puis sortie) → si pas `--dry-run` et connexion incomplète : **échec
propre avec le chemin du fichier à renseigner** → 1) working tree Git propre →
2) branche+commit → 3) `./gradlew test` → 4) `./gradlew build` → 5) JAR présent
+ SHA-256/taille → (`--dry-run` : imprime le plan FTP détaillé, masqué, sortie
0 **même sans hôte/mot de passe**) → confirmation « serveur arrêté ? » → 6)
connexion FTP + listing (abandon avant écriture si échec) → 7) backup daté de
la version en ligne (+ `.meta`), ou `--allow-no-backup` si premier déploiement
→ 8) upload `.part` + contrôle taille + `RNFR`/`RNTO` → rétention optionnelle →
résumé + **actions manuelles restantes** (démarrage panel, `/rpgquest
version`, `/plugins`, checklist, renseigner `SERVER_CHANGELOG`).

**Ne touche jamais** `data.db`, `config.yml`, `messages.yml`, `spawn.yml`,
dossiers de contenu, mondes, autres plugins : un seul chemin distant adressé.

### `scripts/rollback-verygames.sh`

`set -euo pipefail`. Options : `--list`, `--latest`, `--backup PATH`,
`--dry-run`, `--server-stopped`, `-y`, `--help`.

`--list` : backups triés (nom = horodatage UTC) + méta. Sélection : `--latest`
(dernier `predeploy`) ou `--backup <chemin>`. **Refuse** si aucun backup
valide (présent, non vide, signature ZIP, SHA-256 == `.meta`). `--dry-run` :
plan FTP, aucune connexion. Sinon : confirmation → connexion → **backup de
sécurité** de la version en ligne (`prerollback`) → upload atomique `.part` +
`RNFR`/`RNTO` → instructions de redémarrage. **Ne modifie aucune donnée
persistante.**

### `scripts/verygames.env.example`

Modèle versionné **sans secret** : en-tête = commandes exactes de création
sécurisée, format, précédence, vérification gitignore ; puis les variables
(obligatoires `VERYGAMES_FTP_HOST`, `VERYGAMES_FTP_PASS` ; avec défaut
`VERYGAMES_FTP_PORT=21`, `VERYGAMES_FTP_USER=awsplugin`,
`VERYGAMES_FTP_REMOTE_DIR=/`, `VERYGAMES_PLUGIN_JAR_NAME=rpgquest-0.1.0-SNAPSHOT.jar`,
`VERYGAMES_FTP_TLS=auto` ; optionnelles `VERYGAMES_BACKUP_DIR`,
`VERYGAMES_BACKUP_KEEP`, `VERYGAMES_CURL_*`, `VERYGAMES_FTP_EXTRA_CURL_ARGS`).

### `.gitignore`

Ajout : `scripts/verygames.env`, `scripts/*.env` (négation
`!scripts/verygames.env.example`), `scripts/verygames-backups/`, `*.jar.meta`.
Filet de sécurité — le fichier réel vit de toute façon hors du dépôt.

### Documentation

- `docs/deployment/VERYGAMES.md` : nouvelle section **« Déploiement automatisé
  (`scripts/deploy-verygames.sh`) »** (création du fichier d'identifiants,
  tableau des variables, `--check`/`--dry-run`, déroulé du déploiement, section
  rollback, limites assumées) ; section « Accès FTP » complétée (pas de SFTP,
  compte `awsplugin` chrooté) ; renvois depuis « Mise à jour du seul JAR
  (scénario 2) » et « Rollback ».
- `docs/deployment/SERVER_CHANGELOG.md` : entrée **2026-09-06** — précise
  qu'**aucune action serveur** n'est requise pour ce changement (scripts +
  doc), et donne la commande de création du fichier local sur AWS.

## Fichiers créés

* `scripts/lib/verygames-common.sh`
* `scripts/deploy-verygames.sh` (exécutable)
* `scripts/rollback-verygames.sh` (exécutable)
* `scripts/verygames.env.example`
* `docs/claude-reports/2026-09-06_0756_verygames-deploy-scripts-issue-10.md` (ce fichier)

## Fichiers modifiés

* `.gitignore`
* `docs/deployment/VERYGAMES.md`
* `docs/deployment/SERVER_CHANGELOG.md`
* `docs/claude-reports/README.md` (index)
* `gradlew` — bit d'exécution restauré (`100644` → `100755`). Sur `origin/main`
  le wrapper avait perdu son `+x` (le correctif `918c0f0` « mark Gradle wrapper
  executable » n'est présent que sur les branches non encore mergées) ; sans
  lui, `./gradlew test` du script de déploiement échoue en « Permission
  denied ». Le script tolère aussi ce cas en repli (`bash ./gradlew`).

## Base de données / migrations

Aucune.

## Configuration / données

- **Rien n'est modifié sur le serveur.**
- **Fichier local à créer sur AWS (hors dépôt)** — voir la section
  « Commandes exactes à exécuter sur AWS » ci-dessous.

## Tests automatiques

`./gradlew test build` — exécuté (voir la sortie de session) ; le changement
n'ajoute aucun code Java (scripts shell + doc + `.gitignore`), donc build/tests
identiques à `origin/main` (base verte).

Vérifications spécifiques aux scripts, effectuées :

- `bash -n` : OK sur les 3 scripts.
- `shellcheck -x` (v0.9.0) : **0 avertissement** (SC2034 / SC2012 / SC2015 /
  SC1090 corrigés ou dûment justifiés).
- `deploy --dry-run --skip-build` sans config : sortie 0, liste ce qu'il reste
  à configurer.
- `deploy --dry-run --skip-build --allow-dirty` avec config factice : plan FTP
  complet, mot de passe **masqué**, SHA-256/taille du JAR réels.
- `deploy --check` sans identifiants : n'établit **aucune connexion**, explique.
- Rejet d'un fichier d'identifiants en mode `644`.
- Précédence : `VERYGAMES_FTP_HOST` exporté l'emporte sur le fichier.
- `rollback --list` (avec/sans backups), `rollback --latest --dry-run` (choisit
  bien le plus récent par horodatage de nom), refus sur SHA-256 `.meta` non
  conforme.
- Mot de passe contenant espace / `:` / `"` : masqué à l'affichage, échappé
  pour curl.

**Aucune connexion FTP n'a été tentée** (hôte et mot de passe non fournis,
conformément à la consigne).

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — nécessitent l'hôte + le mot de passe VeryGames,
puis un serveur de test :

1. `scripts/deploy-verygames.sh --check` → connexion OK, dossier distant listé.
2. `scripts/deploy-verygames.sh --dry-run` → plan complet, connexion valide.
3. Déploiement d'un JAR de test (serveur arrêté) → backup `predeploy` créé
   avant remplacement, JAR remplacé.
4. Vérifier que `data.db` et les configs n'ont pas changé (date/So/taille).
5. Redémarrage panel + `/rpgquest version` (nouvelle version).
6. `scripts/rollback-verygames.sh --latest` → JAR précédent restauré, backup
   `prerollback` créé.
7. Redémarrage + `/rpgquest version` (version restaurée), `/plugins` en vert.

## Résultat attendu

Un déploiement JAR sûr, tracé (branche/commit + SHA-256 dans le `.meta`),
réversible (backup daté avant chaque écriture, rollback qui se sauvegarde
lui-même), sans secret dans Git, sans jamais toucher aux données persistantes.
Déclenchement et redémarrage manuels, tout le reste automatisé.

## Reset / retour à l'état initial

`git revert` du commit (ou suppression de la branche) : rien n'a été déployé,
aucune donnée touchée. Optionnel : `rm ~/.config/rpgquest/verygames.env`.

## Déploiement VeryGames

### À transférer
Rien pour cette tâche. (Les scripts servent aux **futurs** déploiements du JAR.)

### Ne PAS transférer/altérer
`data.db`, `config.yml`, `messages.yml`, `spawn.yml`, mondes, `Citizens/saves.yml`,
autres plugins — jamais touchés par les scripts.

### Redémarrage requis
Non pour cette tâche.

### Migration automatique
Aucune.

## Rollback

Voir « Reset / retour à l'état initial ». Les scripts eux-mêmes fournissent
`scripts/rollback-verygames.sh` pour les futurs déploiements.

## Logs / diagnostic

Les scripts écrivent sur **stderr** (préfixes `[deploy]` / `[warn]` /
`[error]`, étapes `==>`). Aucun secret n'est journalisé. `--dry-run` et
`--check` sont non destructifs et sûrs à lancer à tout moment.

## Documentation mise à jour

- `docs/deployment/VERYGAMES.md` — section « Déploiement automatisé », accès
  FTP complété, renvois scénario 2 / rollback.
- `docs/deployment/SERVER_CHANGELOG.md` — entrée 2026-09-06.
- `docs/claude-reports/README.md` — ligne d'index.

## Limitations / travail restant

- **Hôte et mot de passe VeryGames absents** : impossible de valider le
  transfert réel maintenant (`PENDING MANUAL VALIDATION`).
- Arrêt / redémarrage du serveur : **manuels** (contrainte VeryGames, aucune
  API/RCON). Documenté ; le script demande confirmation et rappelle les étapes.
- Pas de vérification `/rpgquest version` automatisée post-redémarrage.
- FTP en clair par défaut (`VERYGAMES_FTP_TLS=auto` tente AUTH TLS) ;
  `require` disponible si le serveur le supporte.
- Hors périmètre (issue) : migration mondes/`data.db`/configs, CI/CD GitHub
  Actions, gestion des releases GitHub.

## Prochaine étape suggérée

1. Sur AWS, créer `~/.config/rpgquest/verygames.env` (commandes ci-dessous) et
   y mettre l'hôte + le mot de passe une fois disponibles.
2. `scripts/deploy-verygames.sh --check` puis `--dry-run`.
3. Sur un serveur de test VeryGames, dérouler les tests manuels 3→7.
4. Sur autorisation explicite : premier déploiement réel via le script.

---

## Commandes exactes à exécuter sur AWS (création + sécurisation du fichier d'identifiants)

Depuis la racine du dépôt (`/srv/rpgquest/repo`) :

```bash
# 1. Dossier de config, accessible au seul propriétaire
mkdir -p ~/.config/rpgquest
chmod 700 ~/.config/rpgquest

# 2. Partir du modèle versionné (sans secret)
cp scripts/verygames.env.example ~/.config/rpgquest/verygames.env

# 3. Permissions strictes (le script REFUSE le fichier sinon)
chmod 600 ~/.config/rpgquest/verygames.env

# 4. Renseigner l'hôte et le mot de passe (le reste a déjà de bons défauts)
${EDITOR:-nano} ~/.config/rpgquest/verygames.env
```

Contenu minimal du fichier réel (`~/.config/rpgquest/verygames.env`) :

```sh
VERYGAMES_FTP_HOST=ftpXX.verygames.net          # depuis le panel VeryGames (onglet FTP)
VERYGAMES_FTP_PORT=21
VERYGAMES_FTP_USER=awsplugin
VERYGAMES_FTP_PASS='le-mot-de-passe-du-panel'   # guillemets simples ; jamais dans Git
VERYGAMES_FTP_REMOTE_DIR=/                       # awsplugin est chrooté sur plugins/
VERYGAMES_PLUGIN_JAR_NAME=rpgquest-0.1.0-SNAPSHOT.jar
VERYGAMES_FTP_TLS=auto
```

Vérifier qu'il est bien hors du suivi Git :

```bash
git check-ignore -v scripts/verygames.env           # -> ligne .gitignore (règle de secours)
git status --porcelain | grep -i 'verygames\.env'   # -> AUCUNE sortie (hors .example)
git ls-files | grep -i 'verygames\.env'             # -> uniquement scripts/verygames.env.example
ls -l ~/.config/rpgquest/verygames.env              # -> -rw------- (600)
```

Puis, sans rien déployer :

```bash
scripts/deploy-verygames.sh --check      # valide la config ; teste la connexion si HOST+PASS présents
scripts/deploy-verygames.sh --dry-run    # git + ./gradlew test + build + plan FTP (aucune connexion)
scripts/rollback-verygames.sh --list     # liste des backups (vide au départ)
```
