# Control Panel — configuration

**Aucun secret dans Git.** Toute la configuration sensible vient de variables d'environnement ou
d'un fichier ignoré (`control-panel.properties`, `chmod 600`). Un `control-panel.properties.example`
versionné documente les clés.

## Fichier `control-panel.properties` (non versionné)

```properties
# --- Serveur du panel ---
panel.port=8090
panel.bind=127.0.0.1            # prod : derrière un reverse proxy TLS
panel.base-url=https://panel.example            # pour les liens absolus / cookies
panel.session-ttl-minutes=120
panel.session-idle-minutes=30
panel.disabled=false                            # kill-switch (ou env PANEL_DISABLED)

# --- Cibles (multi-environnement) ---
targets=dev,prod
target.dev.label=VeryGames DEV
target.dev.mode=bridge|snapshot                 # bridge = live ; snapshot = mode dégradé
target.dev.bridge-url=http://127.0.0.1:8100/admin/v1
target.dev.snapshot-file=/srv/rpgquest/exports/dev/admin-snapshot.json
target.prod.label=VeryGames PROD
target.prod.mode=snapshot
target.prod.snapshot-file=/srv/rpgquest/exports/prod/admin-snapshot.json

# --- Persistance du panel ---
panel.db=control-panel.db                       # SQLite, séparé de data.db et store.db

# --- GitHub / Claude / SMTP : réservés, non utilisés en V1 ---
# github.token=(env)
# smtp.host=...
```

## Variables d'environnement (secrets — jamais dans le fichier)

| Variable | Rôle |
|---|---|
| `RPGQUEST_PANEL_SECRET` | secret de signature/chiffrement de session (≥ 32 octets aléatoires) |
| `RPGQUEST_PANEL_OWNER_HASH` | hash Argon2id/PBKDF2 du mot de passe owner |
| `RPGQUEST_BRIDGE_TOKEN_DEV` | token Bearer partagé avec le bridge du plugin (env DEV) |
| `RPGQUEST_BRIDGE_TOKEN_PROD` | idem PROD |
| `PANEL_DISABLED` | `true` → kill-switch (prioritaire sur le fichier) |
| `GITHUB_TOKEN` *(futur #29)* | — |
| `SMTP_PASSWORD` *(futur)* | — |

## Côté plugin — section `web-admin` de `config.yml` (bridge live)

```yaml
web-admin:
  enabled: false            # désactivé par défaut ; rien n'écoute tant que false
  bind: 127.0.0.1
  port: 8100
  # token : jamais dans config.yml — variable d'env RPGQUEST_WEB_ADMIN_TOKEN
  rate-limit-per-minute: 120
  snapshot:
    enabled: false          # mode dégradé : écrit admin-snapshot.json (atomique) toutes les N s
    output-dir: web-export
    interval-seconds: 30
```

## Précédence

1. variables d'environnement ;
2. `control-panel.properties` du répertoire de travail (ou `$RPGQUEST_PANEL_CONFIG`) ;
3. valeurs par défaut du code.

Le panel **refuse de démarrer** si un secret obligatoire manque (`RPGQUEST_PANEL_SECRET`,
`RPGQUEST_PANEL_OWNER_HASH`) — fail-closed, message explicite.
