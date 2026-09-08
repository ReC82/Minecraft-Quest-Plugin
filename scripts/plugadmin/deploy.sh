#!/usr/bin/env bash
#
# deploy.sh — met à jour le CODE de PlugAdmin déjà installé (issue #44).
#
# Reconstruit la distribution puis remplace /opt/plugadmin/app (ancienne app
# sauvegardée sous /opt/plugadmin/releases/<horodatage>), redémarre le service
# et vérifie /health. Ne touche NI aux secrets, NI à nginx, NI au TLS, NI aux
# autres services.
#
# Usage :
#   scripts/plugadmin/deploy.sh            # build + déploie + restart + health
#   scripts/plugadmin/deploy.sh --no-build # déploie la distribution déjà bâtie
#
set -euo pipefail

APP_DIR="/opt/plugadmin/app"
RELEASES_DIR="/opt/plugadmin/releases"
TS="$(date +%Y%m%d-%H%M%S)"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DIST_SRC="$REPO_ROOT/control-panel/build/install/control-panel"

BUILD=1
[ "${1:-}" = "--no-build" ] && BUILD=0

if [ "$BUILD" = 1 ]; then
  echo "==> ./gradlew :control-panel:installDist"
  ( cd "$REPO_ROOT" && ./gradlew :control-panel:installDist --console=plain )
fi
[ -x "$DIST_SRC/bin/control-panel" ] || { echo "distribution absente : $DIST_SRC" >&2; exit 1; }

SUDO=""; [ "$(id -u)" = 0 ] || SUDO="sudo"

echo "==> sauvegarde $APP_DIR -> $RELEASES_DIR/$TS"
$SUDO install -d -m 0755 "$RELEASES_DIR"
[ -d "$APP_DIR" ] && $SUDO mv "$APP_DIR" "$RELEASES_DIR/$TS"

echo "==> déploiement de la nouvelle distribution"
$SUDO install -d -m 0755 "$APP_DIR"
$SUDO cp -a "$DIST_SRC/." "$APP_DIR/"
$SUDO chown -R root:root "$APP_DIR"
$SUDO chmod 0755 "$APP_DIR/bin/control-panel"

echo "==> rétention : 5 releases max"
# Tri lexical décroissant sur le NOM (YYYYMMDD-HHMMSS), pas sur le mtime : un `mv` conserve le
# mtime source et fausserait l'ordre. On ne supprime jamais un dossier `rolledback-*`.
$SUDO bash -c "ls -1d '$RELEASES_DIR'/*/ 2>/dev/null | grep -v '/rolledback-[0-9]*/\$' | sort -r | tail -n +6 | xargs -r rm -rf" || true

echo "==> systemctl restart plugadmin"
$SUDO systemctl restart plugadmin.service
sleep 2
$SUDO systemctl --no-pager --full status plugadmin.service | head -n 15 || true

echo "==> health local"
if curl -fsS http://127.0.0.1:8090/health; then
  echo; echo "OK — déploiement terminé."
else
  echo >&2; echo "!! /health KO — voir 'journalctl -u plugadmin -n 50'. Rollback : scripts/plugadmin/rollback.sh app" >&2
  exit 1
fi
