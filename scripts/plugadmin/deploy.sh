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
#   scripts/plugadmin/deploy.sh            # valide, build + déploie + restart + health
#   scripts/plugadmin/deploy.sh --no-build # déploie la distribution déjà bâtie
#
# Validation adaptée (issue #257) — le panel n'exige que les tests du PANEL, jamais la suite du
# plugin, et jamais deux fois :
#   1. code du panel non commité            → refus (sauf --allow-dirty) : on ne déploie pas
#                                             un état qu'aucun commit ne décrit ;
#   2. CI « CI OK » verte pour ce commit    → aucun test local ;
#   3. CI rouge                             → REFUS (sauf --allow-ci-red, tracé dans DEPLOYED) ;
#   4. CI en cours / absente                → marqueur local de scripts/dev/check.sh, sinon
#                                             ./gradlew :control-panel:test (marqueur écrit).
#   --wait-ci     attendre la fin d'une CI en cours (30 min max) plutôt que tester localement.
#
set -euo pipefail

APP_DIR="/opt/plugadmin/app"
RELEASES_DIR="/opt/plugadmin/releases"
TS="$(date +%Y%m%d-%H%M%S)"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DIST_SRC="$REPO_ROOT/control-panel/build/install/control-panel"

BUILD=1
ALLOW_DIRTY=0
ALLOW_CI_RED=0
WAIT_CI=0
for arg in "$@"; do
  case "$arg" in
    --no-build)     BUILD=0 ;;
    --allow-dirty)  ALLOW_DIRTY=1 ;;
    --allow-ci-red) ALLOW_CI_RED=1 ;;
    --wait-ci)      WAIT_CI=1 ;;
    *) echo "Option inconnue : $arg" >&2; exit 2 ;;
  esac
done

# Lancé via sudo, Git refuserait un dépôt appartenant à un autre compte (« dubious ownership »).
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=safe.directory GIT_CONFIG_VALUE_0="$REPO_ROOT"
# shellcheck source=scripts/lib/validation.sh
. "$REPO_ROOT/scripts/lib/validation.sh"
cd "$REPO_ROOT"
COMMIT="$(git rev-parse HEAD)"
echo "==> validation du commit ${COMMIT:0:7} ($(git branch --show-current || echo detached))"
if val::module_dirty panel; then
  if [ "$ALLOW_DIRTY" = 1 ]; then
    echo "!! code du panel NON COMMITÉ — poursuite forcée (--allow-dirty)" >&2
  else
    echo "!! code du panel non commité : committer d'abord, ou --allow-dirty." >&2
    git status --short -- control-panel >&2
    exit 1
  fi
fi
CI_SHA="$(val::ci_commit)"
CI="$(val::ci_status "$CI_SHA")"
if [ "$WAIT_CI" = 1 ] && [ "${CI%% *}" = pending ]; then
  echo "    CI en cours sur ${CI_SHA:0:7}, attente…"
  CI="$(val::wait_ci "$CI_SHA" 1800)"
fi
VALIDATION=""
case "${CI%% *}" in
  success)
    VALIDATION="CI verte sur ${CI_SHA:0:7} (${CI#* })" ;;
  failure)
    if [ "$ALLOW_CI_RED" = 1 ]; then
      VALIDATION="CI ROUGE sur ${CI_SHA:0:7}, déploiement forcé (--allow-ci-red) (${CI#* })"
      echo "!! $VALIDATION" >&2
    else
      echo "!! CI rouge sur ${CI_SHA:0:7} : ${CI#* }" >&2
      echo "!! refus de déployer. Corriger, ou --allow-ci-red en connaissance de cause." >&2
      exit 1
    fi ;;
  *)
    if [ "$ALLOW_DIRTY" = 0 ] && val::has_marker panel; then
      VALIDATION="tests panel déjà verts localement pour ce code (CI : ${CI%% *})"
    else
      echo "    CI ${CI%% *} pour ${CI_SHA:0:7} : tests du panel en local"
      RPGQUEST_TEST_MAX_HEAP="${RPGQUEST_TEST_MAX_HEAP:-768m}" ./gradlew :control-panel:test --console=plain
      val::write_marker panel
      VALIDATION="tests panel verts en local (CI : ${CI%% *})"
    fi ;;
esac
echo "    validation : $VALIDATION"

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
# Ce qui tourne doit se lire sans deviner : commit, branche, date et preuve de validation.
printf 'commit=%s\nbranch=%s\ndeployed_at=%s\nvalidation=%s\n' "$COMMIT" \
  "$(git branch --show-current || echo detached)" "$(date -Iseconds)" "$VALIDATION" \
  | $SUDO tee "$APP_DIR/DEPLOYED" >/dev/null

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
