# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-07
* Heure : 09:35 (heure locale réelle de la machine)
* Sujet : Issue #44 — déployer **PlugAdmin** (le RPGQuest Control Panel de #37) sur AWS,
  publiquement et proprement, via **https://plugadmin.lodylands.com**, sans perturber les
  autres sites hébergés sur l'instance.
* Statut : **PARTIAL** — infrastructure entièrement en place et vérifiée ; seule reste la
  validation d'un **login owner réel depuis un navigateur externe** (nécessite le mot de passe
  owner, non communiqué). Toute la chaîne d'authentification a été validée par ailleurs.
* Branche Git : `feat/44-plugadmin-aws-deploy` (créée depuis `feat/37-control-panel`).
* Commit actuel si disponible : voir « Commit(s) ».
* Début de la tâche : 2026-09-07 09:15:33
* Fin de la tâche : 2026-09-07 09:37:00
* Durée totale : 00:21:27

## Demande

Rendre PlugAdmin accessible publiquement sur AWS : HTTPS valide sur
`https://plugadmin.lodylands.com`, page de login, authentification owner, dashboard, en
réutilisant **le socle #37** (aucune nouvelle application). Auditer l'AWS avant toute mutation,
ne casser **aucun** autre vhost, service systemd dédié, backend `127.0.0.1:8090` non exposé,
secrets hors Git, vhost nginx dédié, TLS Certbot, procédure de rollback, non-régression
avant/après des autres sites, documentation `docs/control-panel/`, rapport. **Ne pas** implémenter
#51 (agent sortant VeryGames → PlugAdmin) : le dashboard peut afficher « RPGQuest DEV
indisponible », c'est attendu. Ne fermer ni fusionner aucune issue/branche.

## Analyse

### Audit AWS avant mutation (2026-09-07 ~09:15, lecture seule)

Confirmation de l'audit #37, avec vérification fraîche :

| Élément | Constat |
|---|---|
| nginx | 1.24.0 (Ubuntu), actif, géré par Certbot 2.9.0 |
| IP publique | **3.226.216.90** (`checkip.amazonaws.com`) |
| vhosts actifs | `sites-enabled/` : **`dig`** (`dig.lodygames.com` → `127.0.0.1:3000`) et **`lodyland`** (`lodylands.com www.lodylands.com beta.lodylands.com` → `127.0.0.1:5000`). Aucun `default_server`. |
| ports en écoute | `:80`/`:443` nginx (public), `:22` ssh, `127.0.0.1:3000` node (dig), `127.0.0.1:5000` gunicorn (lodyland), `127.0.0.54:53`/`127.0.0.53:53` resolved, `127.0.0.1:39309` java (autre process). **`8090` libre.** |
| TLS existant | certificats **par domaine** : `dig.lodygames.com` (exp. 2026-11-20), `lodylands.com`+`www`+`beta` (exp. 2026-10-22). Compte ACME `0d2376b9…`. |
| état HTTP/HTTPS des sites (avant) | `https://dig.lodygames.com` 200 ; `https://lodylands.com`/`www`/`beta` 200 ; `http://dig…` et `http://lodylands…` → 301 vers HTTPS. |
| `plugadmin.lodylands.com` (avant) | **résolvait déjà** vers `3.226.216.90` (enregistrement `A` explicite chez `one.com`, pas de wildcard). HTTP → 404 nginx ; HTTPS → servait le certificat de `dig.lodygames.com` (fallback SNI), donc avertissement navigateur. |

### DNS — aucune action requise

`plugadmin.lodylands.com` **résout déjà** vers l'IP publique de l'instance
(`A plugadmin.lodylands.com → 3.226.216.90`, vérifié en récursif et auprès de `ns01.one.com`).
Aucun enregistrement DNS à créer ni à modifier. Aucun autre enregistrement touché.

### Décisions d'implémentation (mineures, réversibles)

| Décision | Choix | Raison |
|---|---|---|
| Utilisateur de service | **compte système dédié `plugadmin`** (`nologin`, hors `sudo`) | least privilege (SECURITY.md), n'introduit rien dans `/home/ubuntu` ; réversible (`userdel`) |
| Emplacement app | **`/opt/plugadmin/app`** (sortie `installDist`) + `/opt/plugadmin/releases/<ts>` | ne pas lancer depuis le working tree Git (exigence #44) ; swap atomique + rollback |
| Secrets | **`/etc/plugadmin/plugadmin.env`** `0640 root:plugadmin`, `EnvironmentFile=` systemd | hors dépôt, non lisible par « autres », convention systemd |
| Données panel | **`/var/lib/plugadmin/control-panel.db`** | séparé de l'app, seul chemin en écriture du service (`ProtectSystem=strict`) |
| Domaine | **`plugadmin.lodylands.com`** | valeur owner ; remplace `panel.lodygames.com` & autres exemples historiques |
| Cible RPGQuest | `dev` en mode `bridge` sur `127.0.0.1:8100` (dormant) | #51 fournira l'état live ; d'ici là « indisponible » assumé |

## Travail effectué

### 1. Scripts de déploiement reproductibles — `scripts/plugadmin/`

- **`install.sh`** (idempotent, `sudo`) : crée l'utilisateur `plugadmin` ; l'arborescence
  `/opt/plugadmin`, `/var/lib/plugadmin` (`0750 plugadmin`), `/etc/plugadmin` ; déploie
  `control-panel/build/install/control-panel/` → `/opt/plugadmin/app` (ancienne app →
  `releases/<horodatage>`, 5 gardées) ; crée `/etc/plugadmin/plugadmin.env` **s'il manque** avec
  `RPGQUEST_PANEL_SECRET` (48 o aléatoires base64) et `RPGQUEST_BRIDGE_TOKEN_DEV` (32 o) générés,
  `RPGQUEST_PANEL_OWNER_HASH` **laissé vide** ; installe `control-panel.properties` ; installe et
  `enable` le service systemd ; installe le vhost nginx dédié + symlink ; `nginx -t` **puis**
  `reload` (jamais `restart`) — **retire son propre symlink si `nginx -t` échoue** ; options
  `--tls` (certbot) et `--start`. N'écrase jamais un `plugadmin.env` existant. N'invente ni
  n'affiche aucun mot de passe.
- **`deploy.sh`** : mise à jour du **code** seul — rebuild `installDist`, swap `/opt/plugadmin/app`
  (release précédente conservée), `systemctl restart plugadmin`, contrôle `/health`. Ne touche ni
  secrets, ni nginx, ni TLS, ni autres services.
- **`rollback.sh`** : `app` (restaure la release précédente) / `disable` (arrête+désactive le
  service) / `nginx-off` (retire le symlink vhost, `nginx -t`+`reload`) / `full` (`disable` +
  `nginx-off` = « comme avant #44 ») / `purge` (supprime tout + l'utilisateur, confirmation `OUI`).
  Cible **exclusivement** PlugAdmin.
- Templates versionnés : `plugadmin.service`, `nginx-plugadmin.conf` (version *avant* Certbot),
  `plugadmin.env.example`, `control-panel.properties.example`.

### 2. Installation effectuée sur l'instance AWS

```
sudo scripts/plugadmin/install.sh          # user, dirs, app, env (secrets générés), systemd, vhost nginx + reload
sudo certbot --nginx -d plugadmin.lodylands.com --non-interactive --agree-tos --key-type ecdsa --redirect
# owner : hash fourni via `hash-password`, écrit dans /etc/plugadmin/plugadmin.env (jamais committé)
sudo systemctl start plugadmin
```

- **systemd `plugadmin.service`** : `Type=simple`, `User=plugadmin`, `ExecStart=/opt/plugadmin/app/bin/control-panel`,
  `EnvironmentFile=/etc/plugadmin/plugadmin.env`, `Restart=on-failure` / `RestartSec=5`,
  `SuccessExitStatus=143 130 SIGTERM` (arrêt propre → état `inactive`, pas `failed`).
  Durcissement : `ProtectSystem=strict`, `ProtectHome=true`, `NoNewPrivileges=true`,
  `PrivateTmp=true`, `ReadWritePaths=/var/lib/plugadmin`, `RestrictAddressFamilies=AF_INET AF_INET6`,
  `RestrictNamespaces=true`, `LockPersonality=true`, `UMask=0077`. `enable` (démarrage au boot).
- **nginx** : nouveau `sites-available/plugadmin` + symlink `sites-enabled/plugadmin`.
  `server_name plugadmin.lodylands.com` **strict** (ne peut pas capter un autre domaine).
  `proxy_pass http://127.0.0.1:8090` avec `Host`, `X-Real-IP`, `X-Forwarded-For`,
  `X-Forwarded-Proto`, `X-Forwarded-Host` ; timeouts 5s/30s/30s ; `client_max_body_size 256k` ;
  logs dédiés `/var/log/nginx/plugadmin_{access,error}.log`. `dig` et `lodyland` **non modifiés**.
- **TLS** : Certbot a émis un certificat **ECDSA** `plugadmin.lodylands.com` (exp. **2026-12-06**),
  ajouté le bloc `listen 443 ssl` et un `server { listen 80; … return 301 https://… }`.
  Renouvellement pris en charge par `certbot.timer` existant. Les certificats `dig` et
  `lodylands.com` sont **intacts**.
- **EnvironmentFile** `/etc/plugadmin/plugadmin.env` : `RPGQUEST_PANEL_SECRET` (généré),
  `RPGQUEST_PANEL_OWNER_HASH` (fourni par l'owner via `hash-password`), `RPGQUEST_PANEL_PORT=8090`,
  `RPGQUEST_PANEL_COOKIE_SECURE=true`, `RPGQUEST_PANEL_BASE_URL=https://plugadmin.lodylands.com`,
  `RPGQUEST_PANEL_CONFIG=/etc/plugadmin/control-panel.properties`,
  `RPGQUEST_PANEL_DB=/var/lib/plugadmin/control-panel.db`, `RPGQUEST_BRIDGE_TOKEN_DEV` (généré).
  `0640 root:plugadmin`. **Aucune valeur de secret n'apparaît dans ce rapport, les logs ou Git.**

### 3. Documentation

- **`docs/control-panel/DEPLOYMENT_AWS.md`** (nouveau) : runbook complet — architecture,
  emplacements, sources versionnées, installation depuis zéro, upgrade, exploitation
  (start/stop/restart/logs/kill-switch/rotation mot de passe/TLS), rollback (5 modes), diagnostic
  (502, 503, cert, boucle login, dashboard « indisponible »), **checklist de non-régression des
  autres sites**, relation avec #51.
- `docs/control-panel/AWS.md` : marqué « #44 LIVRÉ », audit conservé comme référence historique,
  hypothèses obsolètes (`panel.lodygames.com`, `rpgquest-panel`, `/home/ubuntu/rpgquest-panel`)
  explicitement abandonnées, section « ce qui a effectivement été fait ».
- `docs/control-panel/CONFIGURATION.md` : section « Prod — déployé par #44 » réécrite (domaine,
  service, chemins, secrets réels).
- `docs/control-panel/README.md` : section « Déploiement (#44) — PlugAdmin en ligne » + relation #51.
- `docs/control-panel/ROADMAP.md` : Étape 0b cochée (login owner navigateur en attente).
- `docs/control-panel/SECURITY.md` : ligne « HTTPS + reverse proxy » passée à ✅.
- `.gitignore` : motifs `plugadmin.env` / `scripts/plugadmin/plugadmin.env` /
  `scripts/plugadmin/control-panel.properties` (défense en profondeur ; le fichier réel est hors
  dépôt).

## Fichiers créés

- `scripts/plugadmin/install.sh`
- `scripts/plugadmin/deploy.sh`
- `scripts/plugadmin/rollback.sh`
- `scripts/plugadmin/plugadmin.service`
- `scripts/plugadmin/nginx-plugadmin.conf`
- `scripts/plugadmin/plugadmin.env.example`
- `scripts/plugadmin/control-panel.properties.example`
- `docs/control-panel/DEPLOYMENT_AWS.md`
- `docs/claude-reports/2026-09-07_0935_plugadmin-aws-deploy-issue-44.md` (ce rapport)

## Fichiers modifiés

- `.gitignore`
- `docs/control-panel/AWS.md`
- `docs/control-panel/CONFIGURATION.md`
- `docs/control-panel/README.md`
- `docs/control-panel/ROADMAP.md`
- `docs/control-panel/SECURITY.md`
- `docs/claude-reports/README.md` (index)

**Aucun code Java/Kotlin modifié.** Le socle #37 est réutilisé tel quel.

## Base de données / migrations

**Aucune migration `data.db`.** Le panel crée sa propre base `control-panel.db` sous
`/var/lib/plugadmin/` (`CREATE TABLE IF NOT EXISTS audit_log …`), totalement séparée de `data.db`
et `store.db`. Vérifié : fichier créé au 1er démarrage, `plugadmin:plugadmin`, `0600`.

## Configuration / données

- Nouveaux fichiers **hors dépôt** sur l'instance : `/etc/plugadmin/plugadmin.env` (secrets,
  `0640`), `/etc/plugadmin/control-panel.properties` (non secret, `0644`).
- Nouveau service systemd `/etc/systemd/system/plugadmin.service`.
- Nouveau vhost `/etc/nginx/sites-available/plugadmin` (+ symlink). Certbot l'a augmenté.
- Nouveau certificat `/etc/letsencrypt/live/plugadmin.lodylands.com/`.
- **Aucun** fichier de configuration d'un autre site n'a été lu en écriture ni modifié.

## Tests automatiques

- `./gradlew :control-panel:test :control-panel:build` → **BUILD SUCCESSFUL** (22 tests #37
  inchangés, tout `UP-TO-DATE` : aucun code touché).
- `bash -n` sur les 3 scripts → OK.

### Validation live sur l'instance (exécutée)

| Vérification | Résultat |
|---|---|
| `curl http://127.0.0.1:8090/health` | `{"panel":"ONLINE","disabled":false,…}` **200** |
| `curl https://plugadmin.lodylands.com/health` | `{"panel":"ONLINE",…}` **200** |
| Backend non exposé | test externe direct `http://plugadmin.lodylands.com:8090/` → échec de connexion (`000`) ; `ss -tln` : `listen 127.0.0.1:8090` uniquement |
| `http://plugadmin.lodylands.com/` | **301** → `https://plugadmin.lodylands.com/` |
| Certificat servi (SNI `plugadmin`) | `CN = plugadmin.lodylands.com`, valide, exp. 2026-12-06 |
| `GET /login` (public) | **200**, en-têtes `Content-Security-Policy`, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy`, `Cache-Control: no-store` ; `Set-Cookie: panel_login_csrf=…; HttpOnly; SameSite=Lax; Secure; Max-Age=600` |
| `GET /dashboard` anonyme | **303** → `/login` |
| `POST /login` sans jeton CSRF | **403** |
| `POST /login` CSRF valide + mauvais mot de passe | **401** « Identifiants invalides. » (délai constant ~0,8 s : coût PBKDF2 payé même en échec) |
| `POST /login` CSRF non concordant | **403** |
| Cookie de session `Secure`/`HttpOnly`/`SameSite` | oui (constaté sur `panel_login_csrf` ; le cookie de session applique la même politique — `RPGQUEST_PANEL_COOKIE_SECURE=true`) |
| systemd `start` / `stop` / `restart` / `status` | OK ; arrêt propre → `inactive` (`Result=success`) ; `is-enabled` → `enabled` |
| Redémarrage après crash (`kill -9` du PID principal) | `Restart=on-failure` → nouveau PID, service `active`, `/health` **200** après ~8 s |
| Dashboard « RPGQuest DEV indisponible » sans 500 | **validé** sur une instance jetable isolée (`:8091`, secrets jetables, même binaire, même cible morte `127.0.0.1:8100`) : login valide → 303 `/dashboard` → **200** avec bannière « RPGQuest DEV indisponible » / « injoignable : connexion refusée », **jamais 500** ; logout → 303 `/login` + session invalidée. Instance jetable supprimée. |
| `control-panel.db` audit | fichier créé, table `audit_log` alimentée (`login.failure` sur les essais ci-dessus) |

## Non-régression autres sites AWS

Testé **avant** l'installation, **après** l'installation nginx, **après** Certbot, **après** les
tests de cycle de vie systemd (dont `kill -9`) :

| URL | Avant | Après (final) |
|---|---|---|
| `https://dig.lodygames.com` | 200 | **200** |
| `https://lodylands.com` | 200 | **200** |
| `https://www.lodylands.com` | 200 | **200** |
| `https://beta.lodylands.com` | 200 | **200** |
| `http://dig.lodygames.com` | 301 → https | **301 → https** |
| `http://lodylands.com` | 301 → https | **301 → https** |
| `certbot certificates` | `dig.lodygames.com`, `lodylands.com` (+ `www`/`beta`) | **inchangés** + nouveau `plugadmin.lodylands.com` |

**Aucun autre site n'a été cassé, déplacé ou reconfiguré.** `dig` et `lodyland` : fichiers
`sites-available/` non modifiés (aucun `.bak` produit pour eux), services `dig.service` /
`lodyland.service` non touchés. Reload nginx uniquement, jamais de `restart` global.

## Résultat attendu

Ouvrir **https://plugadmin.lodylands.com** depuis un navigateur externe :
- HTTPS valide (certificat Let's Encrypt `plugadmin.lodylands.com`) ;
- HTTP redirigé vers HTTPS ;
- page de login PlugAdmin ;
- après `owner` + mot de passe → dashboard ;
- dashboard : bandeau « RPGQuest DEV indisponible » (normal jusqu'à #51), **pas de 500**.

Les autres sites AWS restent opérationnels.

## Reset / retour à l'état initial

- `sudo scripts/plugadmin/rollback.sh full` → service désactivé + vhost nginx retiré
  (`nginx -t` + `reload`) → état « comme avant #44 », `dig`/`lodyland` intacts.
- `sudo scripts/plugadmin/rollback.sh purge` → en plus : suppression de `/opt/plugadmin`,
  `/var/lib/plugadmin`, `/etc/plugadmin`, l'unité systemd et l'utilisateur `plugadmin`
  (confirmation `OUI`).
- Certificat (optionnel) : `sudo certbot delete --cert-name plugadmin.lodylands.com`.
- Côté dépôt : `git checkout feat/37-control-panel` (ou supprimer la branche `feat/44-…`).

## Déploiement VeryGames

### À transférer
**Rien.** #44 ne concerne **que** l'instance AWS. Le plugin RPGQuest n'est pas modifié ; aucun
JAR, config, monde ou donnée VeryGames n'est impacté.

### Ne PAS transférer/altérer
`data.db`, `config.yml`, mondes, plugins VeryGames — hors périmètre total.

### Redémarrage requis
Aucun côté VeryGames. Côté AWS : `systemctl reload nginx` (fait), `systemctl start plugadmin`
(fait). Pas de redémarrage machine.

### Migration automatique
Sans objet (`control-panel.db` créé automatiquement au 1er démarrage).

## Rollback

Voir « Reset / retour à l'état initial » et `docs/control-panel/DEPLOYMENT_AWS.md` §7. Testé
partiellement : `rollback.sh nginx-off` retire proprement le symlink et recharge nginx (les autres
sites répondent toujours) ; le symlink a été remis et l'ensemble revalidé. `install.sh` retire
lui-même son symlink si `nginx -t` échoue.

## Logs / diagnostic

- Application : `journalctl -u plugadmin [-f]` — `event=panel_started port=8090 target=dev`,
  `event=request rid=… method=… path=… status=… ms=…`.
- nginx : `/var/log/nginx/plugadmin_{access,error}.log`.
- Health : `curl http://127.0.0.1:8090/health` (local), `curl https://plugadmin.lodylands.com/health`.
- Diagnostic 502 / 503 / cert / boucle login / « RPGQuest DEV indisponible » :
  `docs/control-panel/DEPLOYMENT_AWS.md` §8.

## Documentation mise à jour

`docs/control-panel/` : `DEPLOYMENT_AWS.md` (nouveau, runbook), `AWS.md`, `CONFIGURATION.md`,
`README.md`, `ROADMAP.md`, `SECURITY.md`. `.gitignore`. `docs/claude-reports/README.md` (index).

## Limitations / travail restant

1. **Login owner réel depuis un navigateur externe : à faire par l'owner.** Le mot de passe owner
   n'a pas été communiqué (seul son hash), donc un `POST /login` *valide* de bout en bout via le
   domaine public n'a pas été exécuté par cette session. Tout le reste de la chaîne d'auth est
   vérifié (GET login, login invalide → 401, CSRF → 403, `/dashboard` anonyme → 303, cookies
   `Secure`/`HttpOnly`/`SameSite`, et un login valide complet sur une instance jetable isolée).
   → **Action owner** : ouvrir `https://plugadmin.lodylands.com`, se connecter avec `owner` + le
   mot de passe choisi, vérifier le dashboard et le bandeau « RPGQuest DEV indisponible ».
2. **État live RPGQuest = #51.** Le dashboard affiche « RPGQuest DEV indisponible » : normal, aucun
   bridge n'écoute sur `127.0.0.1:8100` sur AWS et #44 n'en installe pas.
3. **Rate limiting login** : toujours non implémenté (délai constant PBKDF2 sur échec + nginx
   devant ; durcissement possible plus tard).
4. `#44` **non fermée**, `#37` **non fermée**, `#51` **non fermée**. Aucune branche fusionnée.

## Prochaine étape suggérée

1. **Owner** : login réel depuis un navigateur externe (point 1 ci-dessus). Si OK → #44 prête
   pour validation / clôture par l'owner.
2. Commenter #44 : « infra en place, en attente de la validation login owner par navigateur ».
3. Enchaîner sur **#51** (agent sortant VeryGames → PlugAdmin) pour l'état live — l'infra #44 est
   prête sans refonte (même vhost, même service, mêmes routes `127.0.0.1:8090`).
