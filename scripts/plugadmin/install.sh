#!/usr/bin/env bash
#
# install.sh — installe / met à jour PlugAdmin (RPGQuest Control Panel) sur AWS.
#              Issue #44. Idempotent : ré-exécutable sans casser un état sain.
#
# Ce que fait le script :
#   1. crée l'utilisateur système « plugadmin » (sans shell) ;
#   2. crée l'arborescence /opt/plugadmin, /var/lib/plugadmin, /etc/plugadmin ;
#   3. déploie la distribution `:control-panel:installDist` vers /opt/plugadmin/app
#      (l'ancienne app est sauvegardée sous /opt/plugadmin/releases/<horodatage>) ;
#   4. crée /etc/plugadmin/plugadmin.env s'il manque
#      (RPGQUEST_PANEL_SECRET + RPGQUEST_BRIDGE_TOKEN_DEV générés aléatoirement ;
#       RPGQUEST_PANEL_OWNER_HASH laissé À COMPLÉTER) ;
#   5. installe /etc/plugadmin/control-panel.properties s'il manque ;
#   6. installe le service systemd plugadmin.service (+ enable) ;
#   7. installe le vhost nginx dédié plugadmin.lodylands.com (+ `nginx -t` + reload) ;
#   8. NE lance PAS certbot (étape TLS séparée, voir --tls et le runbook).
#
# Ce que le script NE fait JAMAIS :
#   - toucher un autre vhost nginx (dig, lodyland) ou un autre service ;
#   - redémarrer nginx (reload uniquement, et seulement si `nginx -t` passe) ;
#   - écraser un /etc/plugadmin/plugadmin.env existant ;
#   - inventer / afficher un mot de passe ou un secret ;
#   - redémarrer la machine.
#
# Usage :
#   sudo scripts/plugadmin/install.sh [--tls] [--start] [--dry-run]
#
#   --tls      après l'installation nginx, lance
#              `certbot --nginx -d plugadmin.lodylands.com --non-interactive
#               --agree-tos --key-type ecdsa --redirect`
#              (nécessite que plugadmin.lodylands.com résolve déjà vers cette instance).
#   --start    démarre/redémarre le service à la fin (échoue proprement si
#              RPGQUEST_PANEL_OWNER_HASH n'est pas encore renseigné).
#   --dry-run  affiche les actions sans rien modifier.
#
set -euo pipefail

DOMAIN="plugadmin.lodylands.com"
SERVICE_USER="plugadmin"
APP_DIR="/opt/plugadmin/app"
RELEASES_DIR="/opt/plugadmin/releases"
STATE_DIR="/var/lib/plugadmin"
CONF_DIR="/etc/plugadmin"
ENV_FILE="$CONF_DIR/plugadmin.env"
PROPS_FILE="$CONF_DIR/control-panel.properties"
UNIT_DST="/etc/systemd/system/plugadmin.service"
NGINX_AVAIL="/etc/nginx/sites-available/plugadmin"
NGINX_ENABLED="/etc/nginx/sites-enabled/plugadmin"
BACKPORT_TS="$(date +%Y%m%d-%H%M%S)"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DIST_SRC="$REPO_ROOT/control-panel/build/install/control-panel"
HERE="$REPO_ROOT/scripts/plugadmin"

DO_TLS=0 ; DO_START=0 ; DRY=0
for a in "$@"; do
  case "$a" in
    --tls) DO_TLS=1 ;;
    --start) DO_START=1 ;;
    --dry-run) DRY=1 ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "option inconnue : $a" >&2; exit 2 ;;
  esac
done

say()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
run()  { if [ "$DRY" = 1 ]; then printf '   [dry-run] %s\n' "$*"; else eval "$@"; fi; }

[ "$(id -u)" = 0 ] || { echo "Ce script doit être lancé avec sudo/root." >&2; exit 1; }

# --- 0. pré-requis --------------------------------------------------------------
if [ ! -x "$DIST_SRC/bin/control-panel" ]; then
  echo "Distribution absente : $DIST_SRC" >&2
  echo "Construire d'abord :  ./gradlew :control-panel:installDist" >&2
  exit 1
fi
command -v nginx >/dev/null || { echo "nginx introuvable." >&2; exit 1; }
command -v java  >/dev/null || { echo "java introuvable." >&2; exit 1; }

# --- 1. utilisateur système ---------------------------------------------------
if id "$SERVICE_USER" >/dev/null 2>&1; then
  say "utilisateur $SERVICE_USER : déjà présent"
else
  say "création de l'utilisateur système $SERVICE_USER"
  run "useradd --system --no-create-home --home-dir /opt/plugadmin --shell /usr/sbin/nologin '$SERVICE_USER'"
fi

# --- 2. arborescence --------------------------------------------------------------
say "arborescence /opt/plugadmin, $STATE_DIR, $CONF_DIR"
run "install -d -m 0755 -o root -g root /opt/plugadmin"
run "install -d -m 0755 -o root -g root '$RELEASES_DIR'"
run "install -d -m 0750 -o '$SERVICE_USER' -g '$SERVICE_USER' '$STATE_DIR'"
run "install -d -m 0755 -o root -g root '$CONF_DIR'"

# --- 3. déploiement de l'app ------------------------------------------------------
if [ -d "$APP_DIR" ] && [ "$DRY" != 1 ]; then
  say "sauvegarde de l'app actuelle -> $RELEASES_DIR/$BACKPORT_TS"
  mv "$APP_DIR" "$RELEASES_DIR/$BACKPORT_TS"
fi
say "déploiement de la distribution -> $APP_DIR"
run "install -d -m 0755 '$APP_DIR'"
run "cp -a '$DIST_SRC/.' '$APP_DIR/'"
run "chown -R root:root '$APP_DIR'"
run "chmod -R g-w,o-w '$APP_DIR'"
run "find '$APP_DIR' -type d -exec chmod 0755 {} +"
run "chmod 0755 '$APP_DIR/bin/control-panel'"
# rétention : garder les 5 releases les plus récentes
if [ "$DRY" != 1 ] && [ -d "$RELEASES_DIR" ]; then
  ( ls -1dt "$RELEASES_DIR"/*/ 2>/dev/null || true ) | tail -n +6 | xargs -r rm -rf || true
fi

# --- 4. EnvironmentFile (secrets) — jamais écrasé -------------------------------
if [ -f "$ENV_FILE" ]; then
  say "EnvironmentFile : déjà présent ($ENV_FILE) — inchangé"
else
  say "création de $ENV_FILE (secrets générés, OWNER_HASH à compléter)"
  if [ "$DRY" != 1 ]; then
    SECRET="$(head -c 48 /dev/urandom | base64 -w0)"
    BRIDGE="$(head -c 32 /dev/urandom | base64 -w0)"
    umask 077
    cat > "$ENV_FILE" <<EOF
# PlugAdmin — EnvironmentFile réel. NE PAS committer. Voir
# scripts/plugadmin/plugadmin.env.example pour la doc de chaque clé.
RPGQUEST_PANEL_SECRET=$SECRET
# À COMPLÉTER : sudo -u $SERVICE_USER $APP_DIR/bin/control-panel hash-password
RPGQUEST_PANEL_OWNER_HASH=
RPGQUEST_PANEL_PORT=8090
RPGQUEST_PANEL_COOKIE_SECURE=true
RPGQUEST_PANEL_BASE_URL=https://$DOMAIN
RPGQUEST_PANEL_CONFIG=$PROPS_FILE
RPGQUEST_PANEL_DB=$STATE_DIR/control-panel.db
RPGQUEST_BRIDGE_TOKEN_DEV=$BRIDGE
EOF
    unset SECRET BRIDGE
  fi
fi
run "chown root:'$SERVICE_USER' '$ENV_FILE'"
run "chmod 0640 '$ENV_FILE'"

# --- 5. control-panel.properties (non secret) ----------------------------------
if [ -f "$PROPS_FILE" ]; then
  say "control-panel.properties : déjà présent — inchangé"
else
  say "installation de $PROPS_FILE"
  run "install -m 0644 -o root -g '$SERVICE_USER' '$HERE/control-panel.properties.example' '$PROPS_FILE'"
fi

# --- 6. service systemd --------------------------------------------------------
if [ -f "$UNIT_DST" ]; then
  run "cp -a '$UNIT_DST' '$RELEASES_DIR/plugadmin.service.$BACKPORT_TS.bak'"
fi
say "installation du service systemd -> $UNIT_DST"
run "install -m 0644 '$HERE/plugadmin.service' '$UNIT_DST'"
run "systemctl daemon-reload"
run "systemctl enable plugadmin.service"

# --- 7. vhost nginx dédié ----------------------------------------------------
if [ -f "$NGINX_AVAIL" ]; then
  say "vhost nginx déjà présent — sauvegarde puis mise à jour prudente"
  run "cp -a '$NGINX_AVAIL' '$RELEASES_DIR/nginx-plugadmin.$BACKPORT_TS.bak'"
  say "  (fichier conservé tel quel s'il a déjà été augmenté par certbot ; supprimer manuellement pour repartir du template)"
else
  say "installation du vhost nginx -> $NGINX_AVAIL"
  run "install -m 0644 '$HERE/nginx-plugadmin.conf' '$NGINX_AVAIL'"
fi
if [ ! -e "$NGINX_ENABLED" ]; then
  run "ln -s '$NGINX_AVAIL' '$NGINX_ENABLED'"
fi
say "nginx -t"
if [ "$DRY" != 1 ]; then
  if nginx -t; then
    say "reload nginx (pas de restart)"
    systemctl reload nginx
  else
    echo "!! nginx -t a ÉCHOUÉ — reload annulé. Retrait du symlink pour ne rien casser." >&2
    rm -f "$NGINX_ENABLED"
    nginx -t && systemctl reload nginx || true
    exit 1
  fi
fi

# --- 8. TLS (optionnel) -----------------------------------------------------
if [ "$DO_TLS" = 1 ]; then
  say "certbot --nginx -d $DOMAIN"
  run "certbot --nginx -d '$DOMAIN' --non-interactive --agree-tos --key-type ecdsa --redirect"
  run "nginx -t && systemctl reload nginx"
else
  say "TLS non demandé. Étape manuelle :"
  echo "   sudo certbot --nginx -d $DOMAIN --non-interactive --agree-tos --key-type ecdsa --redirect"
fi

# --- 9. démarrage (optionnel) ----------------------------------------------
OWNER_SET=0
if [ -f "$ENV_FILE" ] && grep -Eq '^RPGQUEST_PANEL_OWNER_HASH=.+' "$ENV_FILE"; then OWNER_SET=1; fi
if [ "$DO_START" = 1 ]; then
  if [ "$OWNER_SET" = 1 ]; then
    say "démarrage du service"
    run "systemctl restart plugadmin.service"
    run "sleep 2 && systemctl --no-pager --full status plugadmin.service | head -n 20"
    run "curl -fsS http://127.0.0.1:8090/health && echo"
  else
    echo "!! RPGQUEST_PANEL_OWNER_HASH vide dans $ENV_FILE — service NON démarré." >&2
    echo "   Générer le hash :  sudo -u $SERVICE_USER $APP_DIR/bin/control-panel hash-password" >&2
    echo "   Le coller dans $ENV_FILE puis :  sudo systemctl start plugadmin" >&2
  fi
fi

say "terminé."
if [ "$OWNER_SET" != 1 ]; then
  echo
  echo "PROCHAINE ÉTAPE OBLIGATOIRE (owner) :"
  echo "  sudo -u $SERVICE_USER $APP_DIR/bin/control-panel hash-password"
  echo "  # coller la sortie 'pbkdf2_sha256\$...' dans RPGQUEST_PANEL_OWNER_HASH de $ENV_FILE"
  echo "  sudo systemctl start plugadmin && curl -fsS http://127.0.0.1:8090/health"
fi
