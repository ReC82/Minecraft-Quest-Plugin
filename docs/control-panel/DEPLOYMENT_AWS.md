# PlugAdmin — déploiement AWS (issue #44)

> **PlugAdmin** = le RPGQuest Control Panel livré par #37, déployé publiquement sur l'instance AWS
> de développement, **sans perturber les autres sites** qui y tournent (`dig.lodygames.com`,
> `lodylands.com` & co).
>
> URL publique : **https://plugadmin.lodylands.com**
> Backend : **`127.0.0.1:8090`** (jamais exposé directement).

Cet ancien nom d'exemple `panel.lodygames.com` / `rpgquest-panel` est **abandonné**. Le nom
produit est **PlugAdmin**, le service systemd est **`plugadmin`**, le domaine est
**`plugadmin.lodylands.com`**.

---

## 1. Architecture déployée

```text
Navigateur externe (poste, téléphone…)
   │  HTTPS
   ▼
plugadmin.lodylands.com  ──►  nginx 1.24 (AWS, :80/:443)
                                 │  vhost DÉDIÉ /etc/nginx/sites-available/plugadmin
                                 │  proxy_pass + X-Forwarded-*
                                 ▼
                         127.0.0.1:8090   PlugAdmin (service systemd « plugadmin »)
                                 │
                                 ▼
                         RPGQuest DEV (cible « dev », mode bridge, http://127.0.0.1:8100/admin/v1)
                                 └─ AUCUN bridge à cette adresse sur AWS aujourd'hui
                                    → dashboard affiche « RPGQuest DEV indisponible »
                                    → NORMAL jusqu'à #51 (agent sortant VeryGames → PlugAdmin)
```

Flux live futur (#51, **hors périmètre #44**) : `RPGQuest VeryGames ──HTTPS sortant──► PlugAdmin AWS`.
Aucun port entrant n'est ni ouvert ni requis côté VeryGames par #44.

---

## 2. Emplacements sur l'instance

| Rôle | Chemin | Propriété / droits |
|---|---|---|
| Application (sortie `installDist`) | `/opt/plugadmin/app/` | `root:root`, remplaçable à chaud |
| Releases précédentes (rollback) | `/opt/plugadmin/releases/<horodatage>/` | `root:root`, 5 gardées |
| Données du panel (SQLite audit log) | `/var/lib/plugadmin/control-panel.db` | `plugadmin:plugadmin`, dir `0750` |
| Secrets (EnvironmentFile systemd) | `/etc/plugadmin/plugadmin.env` | `root:plugadmin`, **`0640`**, **hors Git** |
| Config non secrète | `/etc/plugadmin/control-panel.properties` | `root:plugadmin`, `0644` |
| Unité systemd | `/etc/systemd/system/plugadmin.service` | `root:root`, `0644` |
| Vhost nginx | `/etc/nginx/sites-available/plugadmin` (+ symlink `sites-enabled/`) | `root:root` |
| Certificat TLS | `/etc/letsencrypt/live/plugadmin.lodylands.com/` | Certbot |
| Logs application | `journalctl -u plugadmin` | journald |
| Logs nginx du vhost | `/var/log/nginx/plugadmin_{access,error}.log` | — |

Utilisateur de service **`plugadmin`** : compte système, `--no-create-home`, shell `nologin`,
aucune appartenance à `sudo`. Le service tourne avec un durcissement systemd
(`ProtectSystem=strict`, `ProtectHome=true`, `NoNewPrivileges=true`, `PrivateTmp=true`,
seul `/var/lib/plugadmin` est accessible en écriture).

---

## 3. Sources versionnées

Tout est sous [`scripts/plugadmin/`](../../scripts/plugadmin/) :

| Fichier | Rôle |
|---|---|
| `install.sh` | bootstrap idempotent : user + arborescence + app + env + systemd + vhost nginx (+ `--tls`, `--start`) |
| `deploy.sh` | mise à jour du **code** seul : rebuild `installDist` → `/opt/plugadmin/app` → restart → `/health` |
| `rollback.sh` | `app` / `disable` / `nginx-off` / `full` / `purge` — cible **uniquement** PlugAdmin |
| `plugadmin.service` | template de l'unité systemd |
| `nginx-plugadmin.conf` | template du vhost (version **avant** Certbot) |
| `plugadmin.env.example` | modèle d'`EnvironmentFile` (aucun secret) |
| `control-panel.properties.example` | modèle de config non secrète déployée |

---

## 4. Installation depuis zéro (reproductible)

```bash
# sur l'instance AWS, depuis le dépôt
./gradlew :control-panel:installDist
sudo scripts/plugadmin/install.sh            # user, dirs, app, env (secrets générés), systemd, vhost nginx
sudo scripts/plugadmin/install.sh --tls      # + certbot --nginx pour plugadmin.lodylands.com (DNS déjà résolu)

# hash du mot de passe owner (JAMAIS le mot de passe en clair, jamais committé) :
sudo -u plugadmin /opt/plugadmin/app/bin/control-panel hash-password
#   → coller la ligne « RPGQUEST_PANEL_OWNER_HASH=pbkdf2_sha256$... » dans /etc/plugadmin/plugadmin.env
sudo systemctl start plugadmin

# vérifs
curl -fsS http://127.0.0.1:8090/health
curl -fsS https://plugadmin.lodylands.com/health
```

`install.sh` **ne démarre pas** le service tant que `RPGQUEST_PANEL_OWNER_HASH` est vide
(fail-closed du panel). Il **n'écrase jamais** un `plugadmin.env` existant. Il fait `nginx -t`
avant tout `reload` et **retire son propre symlink** si le test échoue — les autres vhosts ne
sont jamais touchés.

### Secrets générés automatiquement par `install.sh`

- `RPGQUEST_PANEL_SECRET` — 48 octets aléatoires base64 (signature du cookie de session) ;
- `RPGQUEST_BRIDGE_TOKEN_DEV` — 32 octets aléatoires base64 (dormant jusqu'à #51).

Ils ne sont **jamais** affichés. `RPGQUEST_PANEL_OWNER_HASH` est laissé vide : seul l'owner le
renseigne.

---

## 5. Mise à jour du code (upgrade)

```bash
git pull
sudo scripts/plugadmin/deploy.sh             # rebuild + swap /opt/plugadmin/app + restart + health
#   l'app précédente est déplacée sous /opt/plugadmin/releases/<horodatage>
```

`deploy.sh` ne touche ni aux secrets, ni à nginx, ni au TLS, ni aux autres services.

---

## 6. Exploitation

| Action | Commande |
|---|---|
| État | `systemctl status plugadmin` |
| Démarrer / arrêter / redémarrer | `sudo systemctl {start,stop,restart} plugadmin` |
| Logs (suivi) | `journalctl -u plugadmin -f` |
| Logs nginx | `sudo tail -f /var/log/nginx/plugadmin_error.log` |
| Health backend | `curl -fsS http://127.0.0.1:8090/health` |
| Health public | `curl -fsS https://plugadmin.lodylands.com/health` |
| Kill-switch (coupe l'accès, garde le process) | ajouter `PANEL_DISABLED=true` dans `plugadmin.env` puis `sudo systemctl restart plugadmin` → tout renvoie 503 sauf `/health` |
| Changer le mot de passe owner | `hash-password` → remplacer `RPGQUEST_PANEL_OWNER_HASH` → `restart` (invalide les sessions) |
| Renouvellement TLS | automatique (`certbot.timer` ; `sudo certbot renew --dry-run` pour tester) |

---

## 7. Rollback

Toujours ciblé PlugAdmin, jamais les autres sites :

| Besoin | Commande | Effet |
|---|---|---|
| Revenir au code précédent | `sudo scripts/plugadmin/rollback.sh app` | restaure la dernière release de `/opt/plugadmin/app`, restart, health |
| Couper le service | `sudo scripts/plugadmin/rollback.sh disable` | `systemctl disable --now plugadmin` |
| Retirer le vhost nginx | `sudo scripts/plugadmin/rollback.sh nginx-off` | supprime le symlink, `nginx -t`, `reload` (fichier conservé) |
| Revenir « comme avant #44 » | `sudo scripts/plugadmin/rollback.sh full` | service désactivé + vhost retiré ; `dig`/`lodyland` intacts |
| Tout supprimer | `sudo scripts/plugadmin/rollback.sh purge` | + supprime `/opt/plugadmin`, `/var/lib/plugadmin`, `/etc/plugadmin`, l'unité, l'utilisateur (demande `OUI`) |

Certificat (optionnel, seulement si on abandonne le domaine) :
`sudo certbot delete --cert-name plugadmin.lodylands.com`.

Après `full`, la seule trace résiduelle est le certificat Let's Encrypt (inoffensif) et
`/etc/nginx/sites-available/plugadmin` (désactivé).

---

## 8. Diagnostic

### HTTPS répond `502 Bad Gateway`
Le backend n'écoute pas / a crashé.
```bash
systemctl status plugadmin
journalctl -u plugadmin -n 50
curl -sv http://127.0.0.1:8090/health
```
Causes fréquentes : `RPGQUEST_PANEL_OWNER_HASH` vide (le panel refuse de démarrer, fail-closed) ;
`RPGQUEST_PANEL_SECRET` absent ; port 8090 déjà pris ; `plugadmin.env` illisible par l'utilisateur
`plugadmin` (vérifier `0640 root:plugadmin`).

### `503` partout sauf `/health`
Kill-switch actif : retirer `PANEL_DISABLED=true` de `plugadmin.env`, `restart`.

### Certificat invalide / avertissement navigateur
```bash
echo | openssl s_client -connect plugadmin.lodylands.com:443 -servername plugadmin.lodylands.com 2>/dev/null | openssl x509 -noout -subject -dates
sudo certbot certificates
sudo nginx -t && sudo systemctl reload nginx
```

### Login impossible / renvoyé au login en boucle
Cookie `Secure` mais accès en HTTP simple : vérifier `RPGQUEST_PANEL_COOKIE_SECURE=true` **et**
que l'accès passe bien par HTTPS (nginx envoie `X-Forwarded-Proto: https`). `base-url` doit être
`https://plugadmin.lodylands.com`.

### Dashboard : « RPGQuest DEV indisponible »
**Attendu** tant que #51 n'est pas déployé. Ce n'est pas une erreur : la page reste en `200`,
bannière rouge + détail, pas de `500`. Aucune action requise pour #44.

---

## 9. Non-régression des autres sites — checklist

À exécuter **avant** et **après** toute intervention nginx/systemd/TLS sur cette instance :

```bash
for u in https://dig.lodygames.com https://lodylands.com https://www.lodylands.com \
         https://beta.lodylands.com http://dig.lodygames.com http://lodylands.com ; do
  printf '%-32s ' "$u"; curl -s -o /dev/null -w 'http=%{http_code} redirect=%{redirect_url}\n' --max-time 10 "$u"
done
sudo certbot certificates    # les 3 certificats doivent rester présents et valides
```

Attendu (inchangé par #44) :

| URL | Code |
|---|---|
| `https://dig.lodygames.com` | `200` |
| `https://lodylands.com` / `www` / `beta` | `200` |
| `http://dig.lodygames.com`, `http://lodylands.com` | `301` → `https://…` |

Le vhost PlugAdmin a un `server_name` strictement égal à `plugadmin.lodylands.com` : il ne peut
pas capter le trafic d'un autre domaine. Aucun `default_server` n'est défini sur l'instance et
#44 n'en introduit pas.

---

## 10. Relation avec #51

#44 met PlugAdmin **en ligne**. Il n'apporte **pas** l'état live de RPGQuest : le serveur tourne
sur VeryGames, derrière NAT, sans port entrant. #51 ajoutera un **agent sortant** dans le plugin
(`RPGQuest VeryGames → HTTPS sortant → PlugAdmin AWS`) qui publiera heartbeat + état et récupérera
des actions whitelistées.

Le déploiement décrit ici est **prêt pour #51 sans refonte** : le vhost, le service, la structure
multi-cibles (`targets=…`) et `HealthSource` (#37) restent valables ; #51 ajoute des routes
`/agent/v1/*` servies par le même process `plugadmin` sur le même `127.0.0.1:8090`.

### Activation de l'agent (#51) sur l'instance déjà déployée

1. `/etc/plugadmin/control-panel.properties` — déclarer l'agent (non secret) :
   ```properties
   agents=rpgquest-dev
   agent.rpgquest-dev.environment=dev
   agent.rpgquest-dev.token-env=RPGQUEST_AGENT_TOKEN_RPGQUEST_DEV
   agent.stale-seconds=45
   agent.offline-seconds=150
   agent.action-expiry-seconds=300
   target.dev.agent=rpgquest-dev
   ```
2. `/etc/plugadmin/plugadmin.env` (`0640 root:plugadmin`) — ajouter le jeton (secret) :
   ```
   RPGQUEST_AGENT_TOKEN_RPGQUEST_DEV=<jeton fort, identique côté agent VeryGames>
   ```
3. Déployer le nouveau code + redémarrer :
   ```bash
   scripts/plugadmin/deploy.sh          # build :control-panel:installDist + swap + restart + /health
   ```
4. Vérifier :
   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' https://plugadmin.lodylands.com/agent/v1/actions \
     -H 'Authorization: Bearer wrong' -H 'X-Agent-Id: rpgquest-dev'    # -> 401
   journalctl -u plugadmin -f | grep -E 'agent_heartbeat|agent_actions_poll'   # après démarrage de l'agent côté serveur
   ```
5. Côté serveur RPGQuest : déposer `plugins/RPGQuest/plugadmin-agent.properties` avec **le même
   jeton** et redémarrer (voir [AGENT.md](AGENT.md) §2 et
   [docs/deployment/VERYGAMES.md](../deployment/VERYGAMES.md)).

**Rollback** : `agents=` (vide) dans `control-panel.properties` + `systemctl restart plugadmin` →
`/agent/v1/*` répond `401` partout ; le dashboard retombe sur le bridge local.
