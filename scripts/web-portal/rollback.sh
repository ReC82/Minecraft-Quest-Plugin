#!/usr/bin/env bash
#
# rollback.sh — retour arrière du portail public LodyQuests (issue #52).
#
# Cible UNIQUEMENT le portail. Ne touche à AUCUN autre vhost, service ou site (PlugAdmin compris).
#
# Modes :
#   scripts/web-portal/rollback.sh app        # remet la release précédente de /opt/lodyquests-portal/app
#   scripts/web-portal/rollback.sh nginx-off  # retire le vhost du portail (reload), garde le service
#   scripts/web-portal/rollback.sh disable    # arrête + désactive le service (fichiers conservés)
#   scripts/web-portal/rollback.sh full       # disable + nginx-off : état « comme avant #52 »
#
# Rien n'est supprimé : ni releases, ni configuration, ni certificat. Pour retirer aussi le
# certificat après un `full` :  sudo certbot delete --cert-name lodyquests.lodylands.com
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/web-portal/paths.sh
. "$HERE/paths.sh"
SUDO=""; [ "$(id -u)" = 0 ] || SUDO="sudo"

reload_nginx() {
  if $SUDO nginx -t; then $SUDO systemctl reload nginx; else
    echo "!! nginx -t KO — reload annulé, inspecter /etc/nginx/sites-*." >&2; exit 1
  fi
}

case "${1:-}" in
  app)
    PREV="$(portal::releases_desc | head -n1 || true)"
    [ -n "$PREV" ] || { echo "aucune release précédente sous $PORTAL_RELEASES_DIR" >&2; exit 1; }
    echo "==> restauration de $PREV -> $PORTAL_APP_DIR"
    [ -d "$PORTAL_APP_DIR" ] && $SUDO mv "$PORTAL_APP_DIR" "$PORTAL_RELEASES_DIR/rolledback-$(date +%Y%m%d-%H%M%S)"
    $SUDO mv "${PREV%/}" "$PORTAL_APP_DIR"
    $SUDO systemctl restart "$PORTAL_SERVICE"
    if portal::wait_health; then echo "OK — version précédente en ligne."; else
      echo "!! /health KO aussi après rollback — journalctl -u $PORTAL_SERVICE -n 50" >&2; exit 1
    fi
    ;;
  nginx-off)
    echo "==> retrait du vhost du portail (les autres sites ne bougent pas)"
    $SUDO rm -f "$PORTAL_NGINX_ENABLED"
    reload_nginx
    echo "vhost désactivé. Fichier conservé : $PORTAL_NGINX_AVAIL"
    ;;
  disable)
    $SUDO systemctl disable --now "$PORTAL_SERVICE" || true
    ;;
  full)
    $SUDO systemctl disable --now "$PORTAL_SERVICE" || true
    $SUDO rm -f "$PORTAL_NGINX_ENABLED"
    reload_nginx
    echo "Portail hors ligne, autres sites intacts. Remise en ligne : sudo scripts/web-portal/install.sh"
    ;;
  *)
    sed -n '2,16p' "$0"; exit 2 ;;
esac
