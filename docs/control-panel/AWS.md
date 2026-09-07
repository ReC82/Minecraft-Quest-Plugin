# Control Panel sur AWS — audit d'instance (préparation #37, réalisé par #44)

> **Statut : #44 LIVRÉ.** PlugAdmin tourne sur AWS derrière nginx + TLS sur
> **https://plugadmin.lodylands.com** (backend `127.0.0.1:8090`, service systemd `plugadmin`).
> La **procédure d'exploitation réelle** (installation, upgrade, rollback, diagnostic,
> non-régression) est dans **[DEPLOYMENT_AWS.md](DEPLOYMENT_AWS.md)**.
>
> Ce document reste l'**audit d'instance** d'origine (lecture seule, fait pendant #37) : il garde
> une valeur de référence historique. Les anciennes hypothèses `panel.lodygames.com` /
> `rpgquest-panel.service` / `/home/ubuntu/rpgquest-panel/` sont **abandonnées** — voir
> DEPLOYMENT_AWS.md pour les valeurs réellement déployées.

## Instance (audit du 2026-09-07)

| Élément | Constat |
|---|---|
| Reverse proxy | **nginx 1.24.0 (Ubuntu)**, `nginx.service` actif, géré par **Certbot** |
| IP privée | `172.31.1.12` — **IP publique / Elastic IP à fournir par l'owner** (metadata IMDS non lue) |
| Ports publics | `80`, `443` (nginx) ; `22` (ssh) |
| Sites nginx activés | `/etc/nginx/sites-enabled/` : **`dig`** et **`lodyland`** (symlinks vers `sites-available/`) |
| `dig` | `server_name dig.lodygames.com` → `proxy_pass http://127.0.0.1:3000` (service `dig.service`, `node /home/ubuntu/Dig/server/index.js`, `User=ubuntu`, `Environment=PORT=3000`) |
| `lodyland` | `server_name lodylands.com www.lodylands.com beta.lodylands.com` → `proxy_pass http://127.0.0.1:5000` (service `lodyland.service`, gunicorn, `User=ubuntu`, `WorkingDirectory=/home/ubuntu/LodyLand`) |
| Ports localhost occupés | `3000` (dig), `5000` (lodyland), `6010-6013` (X11 ssh), `53` (systemd-resolved) |
| Ports localhost **libres** | `8090`, `8100`, `9000` (vérifiés) |
| TLS | Let's Encrypt / Certbot, **certificats par domaine** : `/etc/letsencrypt/live/dig.lodygames.com/`, `/etc/letsencrypt/live/lodylands.com/`. **Pas de wildcard.** |
| Zone DNS | `lodygames.com` (dig.lodygames.com y résout). `lodylands.com` = domaine distinct. |

## Cible retenue — telle que déployée par #44

- sous-domaine **`plugadmin.lodylands.com`** (zone `lodylands.com`, NS `one.com`) — l'enregistrement
  `A → 3.226.216.90` **existait déjà** au moment de #44, aucune action DNS n'a été nécessaire ;
- HTTPS obligatoire, redirection 80→443 (Certbot `--redirect`) ;
- reverse proxy nginx public → **service local `127.0.0.1:8090`** (PlugAdmin) ;
- service systemd dédié **`plugadmin.service`**, utilisateur système dédié **`plugadmin`**
  (`nologin`, hors `sudo`), app sous **`/opt/plugadmin/app`**, secrets dans
  **`/etc/plugadmin/plugadmin.env`** (`0640 root:plugadmin`, hors dépôt), `Restart=on-failure` ;
- **le port 8090 n'est jamais exposé publiquement** — `listen 127.0.0.1:8090`, seul nginx y accède,
  security group inchangé (80/443/22).

## ⚠️ Point structurant : où tourne le bridge ?

Le **bridge d'administration vit dans le plugin RPGQuest**, qui tourne sur **VeryGames**, pas sur
cette instance AWS. VeryGames n'expose **aucun port entrant exploitable**. Donc, en V1, le panel
sur AWS **ne peut pas** joindre un bridge live sur VeryGames.

Options pour #44 (à trancher avec l'owner) :

1. **Serveur RPGQuest DEV local sur l'instance AWS** (Paper headless, `RPGQUEST_WEB_ADMIN_ENABLED=true`
   sur `127.0.0.1:8100`) : la cible `dev` du panel pointe dessus, tout en local. Le plus simple et
   le plus utile immédiatement (l'owner veut administrer *à distance* sans lancer Minecraft — un
   DEV headless sur AWS répond à ce besoin).
2. **Mode dégradé `admin-snapshot`** : le plugin VeryGames écrit un `admin-snapshot.json` récupéré
   sur AWS (FTP sortant ou export), le panel l'affiche en lecture seule. Voir
   [DECISIONS.md](DECISIONS.md) ADR-004.
3. **Tunnel/relais** VeryGames → AWS (sortant depuis VeryGames). Plus lourd.

Le socle #37 supporte déjà (1) sans changement (`target.dev.bridge-url` + token). (2) et (3) sont
des évolutions.

Décision effective pour #44 : le dashboard affiche **« RPGQuest DEV indisponible »** (option
« aucune des trois » pour l'instant). L'état live viendra de l'**agent sortant #51**
(`RPGQuest VeryGames → HTTPS sortant → PlugAdmin AWS`), pas d'un bridge joignable depuis AWS.
#44 n'installe **pas** de Paper DEV sur l'instance.

## Ce qui a effectivement été fait par #44

Voir **[DEPLOYMENT_AWS.md](DEPLOYMENT_AWS.md)** pour le détail. En résumé :

- DNS : `plugadmin.lodylands.com` **résolvait déjà** vers `3.226.216.90` → aucune action DNS ;
- `scripts/plugadmin/install.sh` : utilisateur système `plugadmin`, `/opt/plugadmin/app` (sortie
  `installDist`), `/etc/plugadmin/plugadmin.env` (secrets générés, hors dépôt), `/var/lib/plugadmin`
  (SQLite), `plugadmin.service` (durci), vhost nginx dédié `sites-available/plugadmin` ;
- `nginx -t` OK → `reload` (jamais `restart`) ; `dig` et `lodyland` **non touchés** ;
- `certbot --nginx -d plugadmin.lodylands.com --redirect` → certificat ECDSA valide, 80→443 ;
- hash du mot de passe owner fourni par l'owner (`hash-password`), jamais committé ;
- `systemctl enable --now plugadmin` → `127.0.0.1:8090`, `/health` OK local **et** public.

## Sécurité déploiement (rappel, appliqué)

- port `8090` **jamais** ouvert dans le security group ; seuls `80`/`443`/`22` publics ;
  `listen 127.0.0.1:8090` vérifié, test externe direct sur `:8090` = échec de connexion ;
- secrets uniquement dans `EnvironmentFile` `chmod 640 root:plugadmin`, hors dépôt ;
- cookies `Secure` en prod (`RPGQUEST_PANEL_COOKIE_SECURE=true`) ;
- kill-switch : `PANEL_DISABLED=true` dans `/etc/plugadmin/plugadmin.env` + `systemctl restart plugadmin`.
