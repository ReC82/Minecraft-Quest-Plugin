# Control Panel sur AWS — audit lecture seule & préparation de l'issue #44

> **#37 ne déploie rien.** Ce document est l'audit (lecture seule) de l'instance et la liste
> exacte de ce qu'il faudra pour #44. **Aucune configuration de site existant n'a été modifiée.**

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

## Cible retenue

- sous-domaine **`panel.lodygames.com`** (nom exact à confirmer) dans la zone `lodygames.com` ;
- HTTPS obligatoire, redirection 80→443 ;
- reverse proxy nginx public → **service local `127.0.0.1:8090`** (le Control Panel) ;
- service systemd dédié `rpgquest-panel.service`, `User=ubuntu` (ou utilisateur dédié), répertoire
  applicatif dédié, `EnvironmentFile=` hors dépôt (`chmod 600`), `Restart=on-failure` ;
- **le port 8090 n'est jamais exposé publiquement** — seul nginx y accède.

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

## Ce qu'il faut de la part de l'owner pour #44

| # | Élément | Détail |
|---|---|---|
| 1 | **Nom de sous-domaine** | `panel.lodygames.com` ? `rpgquest.lodygames.com` ? |
| 2 | **IP publique / Elastic IP** de l'instance AWS | pour l'enregistrement DNS `A` (et éventuellement `AAAA`) |
| 3 | **Accès au gestionnaire DNS** de `lodygames.com` | soit l'owner crée l'enregistrement `A <sous-domaine> → <IP>`, soit il confirme que Claude peut fournir la valeur exacte à créer manuellement (pas d'API DNS ici) |
| 4 | **Décision « où tourne le bridge »** | option 1 / 2 / 3 ci-dessus |
| 5 | Si option 1 : **feu vert pour installer un Paper DEV headless** sur l'instance | version Paper, `server.properties` minimal, `eula=true`, ressources |
| 6 | Confirmation que le compte `ubuntu` (ou un utilisateur dédié) peut porter un `rpgquest-panel.service` | + emplacement voulu (`/home/ubuntu/rpgquest-panel/` ?) |
| 7 | Email pour Certbot (renouvellement) | souvent déjà configuré globalement |

## Procédure #44 (esquisse, à valider — NE PAS exécuter en #37)

1. build : `./gradlew :control-panel:build` → `control-panel/build/libs/control-panel.jar`.
2. installer sous `/home/ubuntu/rpgquest-panel/` (jar + `control-panel.properties`) ;
   `EnvironmentFile=/home/ubuntu/rpgquest-panel/panel.env` (`chmod 600`, secrets).
3. `rpgquest-panel.service` : `ExecStart=/usr/bin/java -jar .../control-panel.jar`,
   `WorkingDirectory=/home/ubuntu/rpgquest-panel`, `User=ubuntu`, `Restart=on-failure`.
   `systemctl enable --now rpgquest-panel` → écoute `127.0.0.1:8090`.
4. **nouveau** vhost nginx `/etc/nginx/sites-available/rpgquest-panel` (server block dédié,
   `server_name panel.lodygames.com`, `proxy_pass http://127.0.0.1:8090`, en-têtes `X-Forwarded-*`).
   Symlink dans `sites-enabled/`. `nginx -t` puis `systemctl reload nginx`. **Ne toucher ni `dig`
   ni `lodyland`.** Sauvegarder tout fichier créé/modifié.
5. `certbot --nginx -d panel.lodygames.com` → cert + bloc 443 + redirection 80→443.
6. `panel.cookie-secure=true`, `panel.base-url=https://panel.lodygames.com`.
7. health : `curl -fsS https://panel.lodygames.com/health` → `{"panel":"ONLINE",...}` ;
   ouvrir l'URL, login owner, dashboard.
8. rollback : `systemctl disable --now rpgquest-panel` + retirer le symlink nginx + `reload` +
   `certbot delete --cert-name panel.lodygames.com` si besoin. Les sites existants ne sont jamais
   touchés → rollback sans impact.

## Sécurité déploiement (rappel)

- port `8090` **jamais** ouvert dans le security group ; seuls `80`/`443`/`22` publics ;
- secrets uniquement dans `EnvironmentFile` `chmod 600`, hors dépôt ;
- cookies `Secure` en prod ;
- kill-switch : `PANEL_DISABLED=true` dans l'`EnvironmentFile` + `systemctl restart rpgquest-panel`.
