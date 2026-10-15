#!/usr/bin/env bash
#
# deploy.sh — met à jour le CODE du portail public LodyQuests déjà installé (issue #52).
#
# Reconstruit `:web-api:installDist`, remplace /opt/lodyquests-portal/app (ancienne app sauvegardée
# sous releases/<horodatage>), redémarre le service et vérifie /health. En cas d'échec de /health,
# l'ancienne version est REMISE EN PLACE automatiquement. Ne touche NI à la configuration, NI aux
# secrets, NI à nginx, NI au TLS, NI aux autres services.
#
# Usage :
#   scripts/web-portal/deploy.sh               # valide, build, déploie, restart, health
#   scripts/web-portal/deploy.sh --no-build    # déploie la distribution déjà construite
#
# Validation (issue #257, même règle que PlugAdmin) : code web-api commité obligatoire (sauf
# --allow-dirty) ; CI verte → aucun test local ; CI rouge → refus (sauf --allow-ci-red) ; CI en
# cours/absente → marqueur local de scripts/dev/check.sh, sinon ./gradlew :web-api:test.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
# shellcheck source=scripts/web-portal/paths.sh
. "$HERE/paths.sh"
DIST_SRC="$REPO_ROOT/web-api/build/install/web-api"
TS="$(date +%Y%m%d-%H%M%S)"

BUILD=1 ; ALLOW_DIRTY=0 ; ALLOW_CI_RED=0
for arg in "$@"; do
  case "$arg" in
    --no-build)     BUILD=0 ;;
    --allow-dirty)  ALLOW_DIRTY=1 ;;
    --allow-ci-red) ALLOW_CI_RED=1 ;;
    *) echo "Option inconnue : $arg" >&2; exit 2 ;;
  esac
done
[ "$(id -u)" != 0 ] || { echo "Ne pas lancer sous sudo : le script appelle sudo lui-même (gh doit rester authentifié)." >&2; exit 2; }
[ -d "$PORTAL_ROOT" ] || { echo "Portail non installé : lancer d'abord sudo scripts/web-portal/install.sh" >&2; exit 1; }

# shellcheck source=scripts/lib/validation.sh
. "$REPO_ROOT/scripts/lib/validation.sh"
cd "$REPO_ROOT"
COMMIT="$(git rev-parse HEAD)"
echo "==> validation du commit ${COMMIT:0:7} ($(git branch --show-current || echo detached))"
if val::module_dirty webapi; then
  if [ "$ALLOW_DIRTY" = 1 ]; then
    echo "!! code web-api NON COMMITÉ — poursuite forcée (--allow-dirty)" >&2
  else
    echo "!! code web-api non commité : committer d'abord, ou --allow-dirty." >&2
    git status --short -- web-api >&2
    exit 1
  fi
fi
HEAD_CI="$(val::head_ci)"
CI_SHA="${HEAD_CI%% *}"
CI="${HEAD_CI#* }"
case "${CI%% *}" in
  success) VALIDATION="CI verte sur ${CI_SHA:0:7} (${CI#* })" ;;
  failure)
    if [ "$ALLOW_CI_RED" = 1 ]; then
      VALIDATION="CI ROUGE sur ${CI_SHA:0:7}, déploiement forcé (--allow-ci-red)"
      echo "!! $VALIDATION" >&2
    else
      echo "!! CI rouge sur ${CI_SHA:0:7} : refus de déployer (--allow-ci-red pour forcer)." >&2
      exit 1
    fi ;;
  *)
    if [ "$ALLOW_DIRTY" = 0 ] && val::has_marker webapi; then
      VALIDATION="tests web-api déjà verts localement pour ce code (CI : ${CI%% *})"
    else
      echo "    CI ${CI%% *} pour ${CI_SHA:0:7} : tests web-api en local"
      RPGQUEST_TEST_MAX_HEAP="${RPGQUEST_TEST_MAX_HEAP:-768m}" ./gradlew :web-api:test --console=plain
      val::write_marker webapi
      VALIDATION="tests web-api verts en local (CI : ${CI%% *})"
    fi ;;
esac
echo "    validation : $VALIDATION"

if [ "$BUILD" = 1 ]; then
  echo "==> ./gradlew :web-api:installDist"
  ./gradlew :web-api:installDist --console=plain
fi
[ -x "$DIST_SRC/bin/web-api" ] || { echo "distribution absente : $DIST_SRC" >&2; exit 1; }

echo "==> sauvegarde $PORTAL_APP_DIR -> $PORTAL_RELEASES_DIR/$TS"
[ -d "$PORTAL_APP_DIR" ] && sudo mv "$PORTAL_APP_DIR" "$PORTAL_RELEASES_DIR/$TS"

echo "==> déploiement de la nouvelle distribution"
sudo install -d -m 0755 "$PORTAL_APP_DIR"
sudo cp -a "$DIST_SRC/." "$PORTAL_APP_DIR/"
sudo chown -R root:root "$PORTAL_APP_DIR"
sudo chmod -R go-w "$PORTAL_APP_DIR"
printf 'commit=%s\nbranch=%s\ndeployed_at=%s\nvalidation=%s\n' "$COMMIT" \
  "$(git branch --show-current || echo detached)" "$(date -Iseconds)" "$VALIDATION" \
  | sudo tee "$PORTAL_APP_DIR/DEPLOYED" >/dev/null

# #53 : la base des comptes joueurs est copiée service ARRÊTÉ (copie cohérente), jamais écrasée :
# un fichier daté par déploiement, 10 gardés. Absente tant que accounts-enabled=false.
echo "==> systemctl stop $PORTAL_SERVICE"
sudo systemctl stop "$PORTAL_SERVICE"
if sudo test -f "$PORTAL_STATE_DIR/accounts.db"; then
  echo "==> sauvegarde des comptes -> $PORTAL_ACCOUNTS_BACKUP_DIR/accounts-$TS.db"
  sudo install -d -m 0700 -o root -g root "$PORTAL_ACCOUNTS_BACKUP_DIR"
  sudo cp -a "$PORTAL_STATE_DIR/accounts.db" "$PORTAL_ACCOUNTS_BACKUP_DIR/accounts-$TS.db"
  sudo bash -c "ls -1 '$PORTAL_ACCOUNTS_BACKUP_DIR'/accounts-*.db | sort -r | tail -n +11 | xargs -r rm -f" || true
fi
echo "==> systemctl start $PORTAL_SERVICE"
sudo systemctl start "$PORTAL_SERVICE"
echo "==> health local"
if portal::wait_health; then
  echo "==> rétention : 5 releases max"
  sudo bash -c ". '$HERE/paths.sh'; portal::releases_desc | tail -n +6 | xargs -r rm -rf" || true
  echo "OK — déploiement terminé (${COMMIT:0:7})."
else
  echo "!! /health KO après déploiement — remise en place de la version précédente." >&2
  sudo journalctl -u "$PORTAL_SERVICE" -n 30 --no-pager >&2 || true
  "$HERE/rollback.sh" app
  exit 1
fi
