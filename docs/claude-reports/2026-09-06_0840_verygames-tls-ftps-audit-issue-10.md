# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-06
* Heure : 08:40
* Sujet : Issue #10 — audit et correction du comportement TLS des scripts de déploiement VeryGames (FTP simple vs FTPS explicite), avec les vrais identifiants, en connexions/listings non destructifs uniquement
* Statut : PARTIAL — TLS entièrement déterminé et corrigé ; blocage externe restant : le mot de passe du compte FTP `si-16041.awsplugin` dans `~/.config/rpgquest/verygames.env` est erroné (login accepté, mot de passe refusé). Aucun fichier transféré.
* Branche Git : feature/10-verygames-deploy-scripts
* Commit actuel si disponible : voir `git log -1`
* Début de la tâche : 2026-09-06 08:25:34 (heure locale réelle)
* Fin de la tâche : 2026-09-06 08:52:00 (heure locale réelle)
* Durée totale : 00:26:26

## Demande

Le test réseau `curl -v --ftp-pasv ftp://si-16041.dg.vg:21/` répond `220 ProFTPD
Server` puis `530` (curl tente `USER anonymous`). Le réseau et le port 21
fonctionnent. Les vrais identifiants `awsplugin` sont dans
`~/.config/rpgquest/verygames.env`.

Corriger / auditer le comportement TLS du script de l'issue #10 :

1. tester **uniquement** des connexions/listings **non destructifs** avec les
   vrais identifiants — FTP simple, FTPS explicite si nécessaire, **sans
   `--insecure`** ;
2. déterminer quelle variante est réellement acceptée par VeryGames ;
3. si FTP simple marche → configurer/documenter `VERYGAMES_FTP_TLS=none` ;
4. si FTPS marche avec une chaîne vérifiable après correction CA → documenter
   cette solution ;
5. ne transférer aucun fichier ;
6. relancer `scripts/deploy-verygames.sh --check` et rapporter : résultat de
   connexion, dossier distant visible, présence du JAR, présence de
   `RPGQuest/`, confirmation que le compte est bien limité au périmètre
   attendu.

## Analyse — ce qui a été testé (aucun transfert)

Tous les tests via `curl`/`openssl`, en `--list-only` (NLST) ou handshake TLS
seul. Le mot de passe n'a jamais été affiché ni passé sur une ligne de
commande (fichier `curl --config` en mode 600).

### 1. FTP en clair (`VERYGAMES_FTP_TLS=none`) — REFUSÉ

Avec les vrais identifiants : `curl (67) Access denied: 530`. Dialogue :
`220` → `USER si-16041.awsplugin` → `530 Login incorrect.` **avant même
`PASS`**. Idem pour `USER anonymous`. → **VeryGames refuse toute connexion FTP
non chiffrée.** `VERYGAMES_FTP_TLS=none` n'est donc **pas** une option viable
ici.

### 2. FTPS explicite (AUTH TLS) — ACCEPTÉ, mais chaîne de certificat incomplète

- `AUTH SSL` → `234 AUTH SSL successful`, handshake **TLS 1.3** OK.
- Certificat : `CN=*.dg.vg`, émetteur `Let's Encrypt CN=YE1`, valide
  (13 juil. → 11 oct. 2026), SAN `DNS:*.dg.vg` — correspond à
  `si-16041.dg.vg`.
- **Le serveur ne renvoie que le certificat feuille.** Les intermédiaires
  ne sont pas envoyés. `curl`/`openssl` : *« unable to get local issuer
  certificate »* (curl `(60)`), alors que le certificat est parfaitement
  valide.
- Chaîne réelle reconstituée via les URL AIA (« CA Issuers », servies par
  Let's Encrypt) :

  ```
  leaf (*.dg.vg)
    └─ YE1            (Let's Encrypt)      http://ye1.i.lencr.org/
        └─ Root YE    (ISRG)               http://ye.i.lencr.org/
            └─ [cross-signé par] ISRG Root X2   ← DÉJÀ dans le magasin système
  ```

- `openssl verify -CAfile <magasin système> -untrusted <YE1 + Root YE>
  leaf.pem` → **OK**. La chaîne est donc vérifiable **sans `--insecure`**, il
  suffit de fournir les 2 intermédiaires manquants.

### 3. Login FTP — le nom nu `awsplugin` est refusé

Sur le serveur FTP multi-clients de VeryGames, le login attendu est
`<slot>.<sous-compte>`. Déterminé par une commande **`USER` seule (aucun mot de
passe envoyé)** sur le canal TLS vérifié :

| `USER` envoyé | Réponse serveur |
|---|---|
| `awsplugin` | `530 Login incorrect.` |
| `si-16041.awsplugin` | `331 Password required for si-16041.awsplugin` |

→ Le login correct est **`si-16041.awsplugin`**. Avec ce login + le mot de
passe actuel du fichier : `530` (une seule tentative faite) → **le mot de
passe dans `verygames.env` n'est pas celui de ce compte** (ou le sous-compte
n'est pas encore provisionné). À récupérer / réinitialiser dans le panel
VeryGames.

## Travail effectué

### `scripts/verygames-fetch-ca.sh` (nouveau)

Reconstruit la chaîne manquante **sans identifiants et sans transfert** : se
connecte en TLS seul (`openssl s_client -starttls ftp`), récupère le
certificat servi, suit les URL AIA pour télécharger chaque intermédiaire
manquant (max 5 sauts, s'arrête dès que `openssl verify` réussit contre le
magasin système), vérifie la chaîne complète, écrit un PEM public (mode 644,
défaut `~/.config/rpgquest/verygames-ca.pem`). Affiche chaque certificat
récupéré (sujet/émetteur/dates). `--print` pour ne pas écrire, `--host`/
`--port` pour surcharger.

Exécution réelle : 2 intermédiaires reconstruits (`YE1`, `Root YE`),
`leaf.pem: OK`.

### `scripts/lib/verygames-common.sh`

- **Défaut `VERYGAMES_FTP_TLS=require`** (au lieu de `auto`). `auto` est
  désormais un **alias de `require`** ; l'option opportuniste `--ssl` (que
  curl signale comme non sûre) n'est **plus jamais** émise. `none` reste pour
  un autre hôte FTP en clair.
- Nouvelles variables :
  - `VERYGAMES_FTP_CA_EXTRA` — PEM d'intermédiaires à **ajouter** au magasin
    système. Le script construit à l'exécution un bundle temporaire
    (magasin système + extra) passé en `curl --cacert` ; à défaut de fichier
    système repéré, `--capath /etc/ssl/certs` + `--cacert <extra>`.
  - `VERYGAMES_FTP_CACERT` — magasin de remplacement **total** (usage
    avancé).
- `vg::_system_ca_file` : repère le magasin système
  (`$CURL_CA_BUNDLE`/`$SSL_CERT_FILE`/chemins usuels).
- `vg::explain_curl_rc` : messages actionnables par code de sortie curl
  (`6` DNS, `7` TCP, `60` chaîne CA → lancer `verygames-fetch-ca.sh`, `67`
  login refusé → format `<slot>.<sous-compte>`, …).
- Nettoyage par trap étendu au bundle CA temporaire.
- Jamais de `--insecure` / `-k` nulle part.

### `scripts/deploy-verygames.sh` / `scripts/rollback-verygames.sh`

- `--check` : liste réellement le dossier distant (indenté), signale
  **présence/absence** du JAR, et en cas d'échec affiche le diagnostic
  `vg::explain_curl_rc`.
- Étape « connexion » : capture correcte du code curl (le `if !` masquait le
  `$?`) + message `vg::explain_curl_rc`.
- Résumé de config : lignes `TLS`, `CA intermédiaires`, `CA (remplacement)`.

### `scripts/verygames.env.example`

`VERYGAMES_FTP_TLS=require` par défaut ; `VERYGAMES_FTP_USER=si-XXXXX.awsplugin`
avec explication du préfixe ; bloc `VERYGAMES_FTP_CA_EXTRA` +
`scripts/verygames-fetch-ca.sh` ; `VERYGAMES_FTP_CACERT`.

### Documentation

- `docs/deployment/VERYGAMES.md` — section « Accès FTP » réécrite (AUTH TLS
  obligatoire, chaîne incomplète + solution `verygames-fetch-ca.sh` /
  `VERYGAMES_FTP_CA_EXTRA` sans `--insecure`, login préfixé) ; exemple
  `verygames.env` et tableau des variables mis à jour ; « Limites assumées »
  actualisées.
- `docs/deployment/SERVER_CHANGELOG.md` — entrée 2026-09-06 « Audit TLS FTPS
  VeryGames » : constats, aucune action serveur, remarque « chaîne incomplète
  = à corriger côté VeryGames à terme ».
- `.gitignore` — `scripts/verygames-ca*.pem`.

## Fichiers créés
* `scripts/verygames-fetch-ca.sh` (exécutable)
* `docs/claude-reports/2026-09-06_0840_verygames-tls-ftps-audit-issue-10.md`

## Fichiers modifiés
* `scripts/lib/verygames-common.sh`
* `scripts/deploy-verygames.sh`
* `scripts/rollback-verygames.sh`
* `scripts/verygames.env.example`
* `.gitignore`
* `docs/deployment/VERYGAMES.md`
* `docs/deployment/SERVER_CHANGELOG.md`
* `docs/claude-reports/README.md` (index)

## Base de données / migrations
Aucune.

## Configuration / données
Aucun fichier serveur touché. Sur le poste AWS : `~/.config/rpgquest/verygames-ca.pem`
généré par le helper (certificats publics). `~/.config/rpgquest/verygames.env`
**non modifié par ce rapport** — les 3 lignes à ajuster sont listées dans
« Prochaine étape ».

## Tests automatiques
`shellcheck -x` (v0.9.0) sur les 4 scripts : **0 avertissement**. `bash -n` OK.
`./gradlew test build` : aucun code Java touché → identique à la base verte de
la branche (911 tests). Vérifs fonctionnelles : `verygames-fetch-ca.sh`
(reconstruit 2 intermédiaires, chaîne vérifiée), `deploy --dry-run
--skip-build` (plan FTP, aucune connexion), `deploy --check` (voir « Résultat »).

## Tests manuels à effectuer
`PENDING MANUAL VALIDATION` — nécessitent le **bon mot de passe** du compte
`si-16041.awsplugin` (panel VeryGames) :
1. `scripts/verygames-fetch-ca.sh` puis renseigner les 3 lignes.
2. `scripts/deploy-verygames.sh --check` → doit lister `plugins/` et indiquer
   présence/absence du JAR + du dossier `RPGQuest/`.
3. Déploiement d'un JAR de test (serveur arrêté) + rollback.

## Résultat attendu
FTPS explicite avec certificat **vérifié** (jamais `--insecure`), grâce à
`VERYGAMES_FTP_CA_EXTRA` généré par `verygames-fetch-ca.sh`. `VERYGAMES_FTP_TLS`
par défaut sur `require`.

## Résultat de `scripts/deploy-verygames.sh --check`

Lancé avec la config corrigée (login `si-16041.awsplugin`, `VERYGAMES_FTP_CA_EXTRA`
pointant sur le PEM généré, `TLS=require`) :

- **Résultat de connexion :** TLS 1.3 négocié, **certificat vérifié OK** (SAN
  `*.dg.vg` correspond à `si-16041.dg.vg`, chaîne
  `leaf → YE1 → Root YE → ISRG Root X2`). Puis **échec à l'authentification
  FTP** : `curl (67) Access denied: 530` — le **mot de passe** du fichier
  n'est pas celui du compte `si-16041.awsplugin` (le login, lui, est accepté :
  `331 Password required`).
- **Dossier distant réellement visible :** *indéterminé* — le listing exige
  une session authentifiée, impossible tant que le mot de passe est faux.
- **Présence du JAR RPGQuest (`rpgquest-0.1.0-SNAPSHOT.jar`) :** *indéterminé*
  (même raison).
- **Présence du dossier `RPGQuest/` :** *indéterminé* (même raison).
- **Périmètre du compte :** *non confirmable* sans login réussi. Attendu (à
  vérifier une fois connecté) : le sous-compte `awsplugin` est chrooté sur
  `plugins/`, donc la racine de session FTP doit être `plugins/` (contenu
  attendu : `rpgquest-*.jar`, `RPGQuest/`, `Citizens/`, `WorldEdit/`,
  `Multiverse-Core/`, …) et rien au-dessus (`world/`, `world_hub/` NON
  visibles).

## Reset / retour à l'état initial
`git revert` du commit. `rm ~/.config/rpgquest/verygames-ca.pem` (certs
publics). Aucune donnée serveur touchée.

## Déploiement VeryGames
### À transférer
Rien. Audit seul.
### Ne PAS transférer/altérer
Rien n'a été transféré ni altéré (ni tenté).
### Redémarrage requis
Non.
### Migration automatique
Aucune.

## Rollback
Voir « Reset ».

## Logs / diagnostic
Le mot de passe n'apparaît dans aucune sortie ni aucun log. `--check` /
`--dry-run` / `verygames-fetch-ca.sh` sont non destructifs.

## Documentation mise à jour
`docs/deployment/VERYGAMES.md`, `docs/deployment/SERVER_CHANGELOG.md`,
`docs/claude-reports/README.md`.

## Limitations / travail restant
- **Mot de passe FTP erroné** dans `~/.config/rpgquest/verygames.env` pour
  `si-16041.awsplugin` → blocage externe. À corriger dans le panel VeryGames.
- Une fois le mot de passe bon : relancer `--check`, compléter les items
  « indéterminé » ci-dessus.
- Cause racine TLS = chaîne incomplète servie par ProFTPD **côté VeryGames** ;
  correctif propre à terme chez l'hébergeur (`TLSCertificateChainFile`
  complet). `VERYGAMES_FTP_CA_EXTRA` est le contournement client propre en
  attendant.
- Rotation d'intermédiaire Let's Encrypt → relancer `verygames-fetch-ca.sh`.

## Prochaine étape suggérée

Dans le panel VeryGames (onglet FTP), récupérer / réinitialiser le mot de
passe du sous-compte `awsplugin`, puis sur AWS :

```bash
scripts/verygames-fetch-ca.sh          # -> ~/.config/rpgquest/verygames-ca.pem

# éditer ~/.config/rpgquest/verygames.env :
#   VERYGAMES_FTP_USER=si-16041.awsplugin
#   VERYGAMES_FTP_TLS=require
#   VERYGAMES_FTP_CA_EXTRA=~/.config/rpgquest/verygames-ca.pem
#   VERYGAMES_FTP_PASS='<mot de passe EXACT du panel>'

scripts/deploy-verygames.sh --check
```
