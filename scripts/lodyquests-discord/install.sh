#!/usr/bin/env bash
#
# install.sh — installe ou met à jour le service de synchronisation forum Discord ↔ issues
# GitHub (issue #202).
#
# Idempotent : relancer le script sur une installation à jour ne casse rien. L'ancienne
# application est sauvegardée sous /opt/lodyquests-discord/releases/<horodatage>.
#
# Ce script ne touche JAMAIS :
#   - au serveur Minecraft (aucun redémarrage, aucun fichier de monde) ;
#   - au Control Panel (plugadmin.service) ni aux autres services de l'instance ;
#   - au fichier de secrets ~/.config/lodyquests-discord/bot.env, qui appartient au propriétaire.
#
# Usage :
#   scripts/lodyquests-discord/install.sh              # build + installe + active + démarre
#   scripts/lodyquests-discord/install.sh --no-build   # installe la distribution déjà bâtie
#   scripts/lodyquests-discord/install.sh --no-start   # installe sans démarrer
#
set -euo pipefail

APP_DIR="/opt/lodyquests-discord/app"
RELEASES_DIR="/opt/lodyquests-discord/releases"
STATE_DIR="/var/lib/lodyquests-discord"
UNIT_NAME="lodyquests-discord.service"
UNIT_PATH="/etc/systemd/system/$UNIT_NAME"
ENV_FILE="/home/ubuntu/.config/lodyquests-discord/bot.env"
TS="$(date +%Y%m%d-%H%M%S)"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DIST_SRC="$REPO_ROOT/discord-sync/build/install/discord-sync"

BUILD=1
START=1
for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD=0 ;;
    --no-start) START=0 ;;
    *) echo "argument inconnu : $arg" >&2; exit 2 ;;
  esac
done

SUDO=""; [ "$(id -u)" = 0 ] || SUDO="sudo"

if [ "$BUILD" = 1 ]; then
  echo "==> ./gradlew :discord-sync:installDist"
  ( cd "$REPO_ROOT" && ./gradlew :discord-sync:installDist --console=plain )
fi
[ -x "$DIST_SRC/bin/discord-sync" ] || {
  echo "distribution absente : $DIST_SRC (lancer sans --no-build)" >&2; exit 1; }

# Le fichier de secrets est une précondition, pas quelque chose que ce script crée : il contient
# des jetons qui n'appartiennent qu'au propriétaire. On vérifie seulement sa présence.
if [ ! -r "$ENV_FILE" ]; then
  echo "!! fichier de secrets absent ou illisible : $ENV_FILE" >&2
  echo "   Clés attendues : DISCORD_GUILD_ID, DISCORD_FORUM_CHANNEL_ID, DISCORD_BOT_TOKEN," >&2
  echo "   GITHUB_REPOSITORY, GITHUB_TOKEN. Voir scripts/lodyquests-discord/bot.env.example." >&2
  exit 1
fi

echo "==> sauvegarde $APP_DIR -> $RELEASES_DIR/$TS"
$SUDO install -d -m 0755 "$RELEASES_DIR"
[ -d "$APP_DIR" ] && $SUDO mv "$APP_DIR" "$RELEASES_DIR/$TS"

echo "==> déploiement de la nouvelle distribution"
$SUDO install -d -m 0755 "$APP_DIR"
$SUDO cp -a "$DIST_SRC/." "$APP_DIR/"
$SUDO chown -R root:root "$APP_DIR"
$SUDO chmod 0755 "$APP_DIR/bin/discord-sync"

echo "==> rétention : 5 releases max"
# Tri lexical décroissant sur le NOM (YYYYMMDD-HHMMSS), pas sur le mtime : un `mv` conserve le
# mtime source et fausserait l'ordre.
$SUDO bash -c "ls -1d '$RELEASES_DIR'/*/ 2>/dev/null | sort -r | tail -n +6 | xargs -r rm -rf" || true

echo "==> dossier d'état $STATE_DIR"
$SUDO install -d -m 0750 -o ubuntu -g ubuntu "$STATE_DIR"

echo "==> unité systemd $UNIT_PATH"
$SUDO install -m 0644 "$REPO_ROOT/scripts/lodyquests-discord/$UNIT_NAME" "$UNIT_PATH"
$SUDO systemctl daemon-reload
$SUDO systemctl enable "$UNIT_NAME"

if [ "$START" = 1 ]; then
  echo "==> systemctl restart $UNIT_NAME"
  $SUDO systemctl restart "$UNIT_NAME"
  sleep 3
  $SUDO systemctl --no-pager --full status "$UNIT_NAME" | head -n 12 || true
  echo
  echo "==> dernières lignes du journal"
  $SUDO journalctl -u "$UNIT_NAME" -n 15 --no-pager || true
else
  echo "==> service installé et activé, non démarré (--no-start)"
fi

cat <<'NEXT'

OK — installation terminée.

Vérifier la configuration SANS rien écrire :
    /opt/lodyquests-discord/app/bin/discord-sync check

Prendre en charge explicitement un sujet existant (par exemple le sujet TEST) :
    /opt/lodyquests-discord/app/bin/discord-sync adopt <idDuSujet> "sujet TEST"

Texte d'information à coller dans les consignes du forum :
    /opt/lodyquests-discord/app/bin/discord-sync notice

État local (repère, sujets adoptés, appariements) :
    /opt/lodyquests-discord/app/bin/discord-sync status

Journal : journalctl -u lodyquests-discord -f
NEXT
