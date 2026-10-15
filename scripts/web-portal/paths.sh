# shellcheck shell=bash disable=SC2034
#
# paths.sh — emplacements du portail public LodyQuests sur l'instance AWS (issue #52).
# Sourcé par install.sh, deploy.sh et rollback.sh : une seule définition, jamais recopiée.
#
# Tout est DISTINCT de PlugAdmin (/opt/plugadmin, plugadmin.service, port 8090) : autre
# utilisateur système, autre service, autre port, autre vhost nginx, aucun secret partagé.

PORTAL_DOMAIN="lodyquests.lodylands.com"
PORTAL_USER="lodyquests-web"
PORTAL_PORT=8092
PORTAL_SERVICE="lodyquests-portal.service"
PORTAL_ROOT="/opt/lodyquests-portal"
PORTAL_APP_DIR="$PORTAL_ROOT/app"
PORTAL_RELEASES_DIR="$PORTAL_ROOT/releases"
PORTAL_STATE_DIR="/var/lib/lodyquests-portal"
PORTAL_ACCOUNTS_BACKUP_DIR="$PORTAL_ROOT/backups"
PORTAL_CONF_DIR="/etc/lodyquests-portal"
PORTAL_ENV_FILE="$PORTAL_CONF_DIR/portal.env"
PORTAL_PROPS_FILE="$PORTAL_CONF_DIR/web-api.properties"
PORTAL_UNIT_DST="/etc/systemd/system/$PORTAL_SERVICE"
PORTAL_NGINX_AVAIL="/etc/nginx/sites-available/lodyquests-portal"
PORTAL_NGINX_ENABLED="/etc/nginx/sites-enabled/lodyquests-portal"
PORTAL_HEALTH_URL="http://127.0.0.1:$PORTAL_PORT/health"

# Sonde /health avec quelques essais : la JVM met une à trois secondes à ouvrir son port.
portal::wait_health() {
  local _
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    if curl -fsS -m 3 "$PORTAL_HEALTH_URL" 2>/dev/null; then echo; return 0; fi
    sleep 1
  done
  return 1
}

# Releases triées par NOM (YYYYMMDD-HHMMSS) décroissant, sans les dossiers `rolledback-*`.
portal::releases_desc() {
  ls -1d "$PORTAL_RELEASES_DIR"/*/ 2>/dev/null | grep -v '/rolledback-[0-9-]*/$' | sort -r
}
