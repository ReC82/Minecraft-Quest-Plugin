#!/usr/bin/env bash
#
# rollback.sh — retour arrière PlugAdmin (issue #44).
#
# Cible UNIQUEMENT PlugAdmin. Ne touche à AUCUN autre vhost / service / site.
#
# Modes :
#   scripts/plugadmin/rollback.sh app        # restaure la release précédente de /opt/plugadmin/app
#   scripts/plugadmin/rollback.sh disable     # arrête + désactive le service (laisse fichiers en place)
#   scripts/plugadmin/rollback.sh nginx-off   # retire le vhost plugadmin de nginx (reload), garde le service
#   scripts/plugadmin/rollback.sh full        # disable service + retire vhost nginx (état « comme avant #44 »)
#   scripts/plugadmin/rollback.sh purge       # full + supprime /opt/plugadmin, /var/lib/plugadmin,
#                                             # /etc/plugadmin, l'unit, l'utilisateur (DESTRUCTIF, demande confirmation)
#
# Les certificats Let's Encrypt ne sont PAS supprimés automatiquement (commande
# fournie en fin de 'full'/'purge' si besoin : certbot delete --cert-name plugadmin.lodylands.com).
#
set -euo pipefail

APP_DIR="/opt/plugadmin/app"
RELEASES_DIR="/opt/plugadmin/releases"
STATE_DIR="/var/lib/plugadmin"
CONF_DIR="/etc/plugadmin"
UNIT="/etc/systemd/system/plugadmin.service"
NGINX_AVAIL="/etc/nginx/sites-available/plugadmin"
NGINX_ENABLED="/etc/nginx/sites-enabled/plugadmin"
DOMAIN="plugadmin.lodylands.com"

SUDO=""; [ "$(id -u)" = 0 ] || SUDO="sudo"
MODE="${1:-}"

reload_nginx() {
  if $SUDO nginx -t; then $SUDO systemctl reload nginx; else
    echo "!! nginx -t KO après modification — inspecter /etc/nginx/sites-*." >&2; exit 1
  fi
}

case "$MODE" in
  app)
    PREV="$(ls -1dt "$RELEASES_DIR"/*/ 2>/dev/null | head -n1 || true)"
    [ -n "$PREV" ] || { echo "aucune release précédente sous $RELEASES_DIR" >&2; exit 1; }
    echo "==> restauration de $PREV -> $APP_DIR"
    TS="$(date +%Y%m%d-%H%M%S)"
    [ -d "$APP_DIR" ] && $SUDO mv "$APP_DIR" "$RELEASES_DIR/rolledback-$TS"
    $SUDO mv "${PREV%/}" "$APP_DIR"
    $SUDO systemctl restart plugadmin.service
    sleep 2 && curl -fsS http://127.0.0.1:8090/health && echo " OK"
    ;;
  disable)
    echo "==> arrêt + désactivation du service plugadmin"
    $SUDO systemctl disable --now plugadmin.service || true
    $SUDO systemctl --no-pager status plugadmin.service | head -n 5 || true
    ;;
  nginx-off)
    echo "==> retrait du vhost nginx plugadmin (les autres sites ne bougent pas)"
    $SUDO rm -f "$NGINX_ENABLED"
    reload_nginx
    echo "vhost désactivé. Fichier conservé : $NGINX_AVAIL"
    ;;
  full)
    echo "==> disable service + retrait vhost nginx"
    $SUDO systemctl disable --now plugadmin.service || true
    $SUDO rm -f "$NGINX_ENABLED"
    reload_nginx
    echo
    echo "État : PlugAdmin hors ligne, autres sites intacts."
    echo "Pour supprimer aussi le certificat :  sudo certbot delete --cert-name $DOMAIN"
    ;;
  purge)
    read -r -p "PURGE destructive de PlugAdmin (app, données, config, user). Continuer ? [tape OUI] " c
    [ "$c" = "OUI" ] || { echo "annulé."; exit 1; }
    $SUDO systemctl disable --now plugadmin.service || true
    $SUDO rm -f "$NGINX_ENABLED" "$NGINX_AVAIL"
    reload_nginx
    $SUDO rm -f "$UNIT"
    $SUDO systemctl daemon-reload
    $SUDO rm -rf /opt/plugadmin "$STATE_DIR" "$CONF_DIR"
    $SUDO userdel plugadmin 2>/dev/null || true
    echo "PlugAdmin purgé. Certificat éventuel :  sudo certbot delete --cert-name $DOMAIN"
    ;;
  *)
    sed -n '2,30p' "$0"; exit 2 ;;
esac
