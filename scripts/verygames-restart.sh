#!/usr/bin/env bash
#
# verygames-restart.sh — redémarrage du serveur RPGQuest DEV via RCON (issues #10 / #51).
#
# Minimal, volontairement : `stop` RCON -> attente que le serveur revienne
# (VeryGames relance automatiquement le processus ~15 s après l'arrêt).
#
# À enchaîner APRÈS `scripts/deploy-verygames.sh` (upload FTP) pour appliquer un
# nouveau JAR / fichier de config sans intervention manuelle dans le panel VeryGames.
#
# Config : RCON_HOST / RCON_PORT / RCON_PASSWORD dans
#   ~/.config/rpgquest/verygames.env  (ou $RPGQUEST_VERYGAMES_ENV) — le MÊME
#   fichier que l'accès FTP. Le mot de passe n'est jamais affiché.
#
# Usage :
#   scripts/verygames-restart.sh [--timeout SECONDS] [--no-save]
#
# Sortie : 0 = serveur revenu en ligne ; 1 = timeout / échec.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RCON=("python3" "$SCRIPT_DIR/verygames-rcon.py")

TIMEOUT=180
SAVE=1
while [ $# -gt 0 ]; do
  case "$1" in
    --timeout) TIMEOUT="${2:?}"; shift 2 ;;
    --no-save) SAVE=0; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "option inconnue : $1" >&2; exit 2 ;;
  esac
done

echo "==> Vérification RCON (serveur en ligne avant redémarrage)"
if ! "${RCON[@]}" --ping; then
  echo "!! RCON injoignable ou authentification refusée — abandon." >&2
  exit 1
fi
echo "   joueurs connectés : $("${RCON[@]}" "list" | head -n1)"

if [ "$SAVE" = 1 ]; then
  echo "==> save-all (best effort)"
  "${RCON[@]}" "save-all" || true
  sleep 3
fi

echo "==> stop (RCON)"
"${RCON[@]}" "stop" || true

echo "==> Attente de l'arrêt effectif (max 60 s)"
DOWN=0
for _ in $(seq 1 30); do
  if ! "${RCON[@]}" --ping 2>/dev/null; then DOWN=1; break; fi
  sleep 2
done
[ "$DOWN" = 1 ] && echo "   serveur OFFLINE." || echo "   (RCON répond encore — VeryGames a peut-être déjà relancé)"

echo "==> Attente du retour en ligne (max ${TIMEOUT} s)"
DEADLINE=$(( $(date +%s) + TIMEOUT ))
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  if "${RCON[@]}" --ping 2>/dev/null; then
    sleep 2
    if "${RCON[@]}" "list" >/dev/null 2>&1; then
      echo "==> Serveur RPGQuest DEV de nouveau ONLINE ($("${RCON[@]}" "list" | head -n1))"
      exit 0
    fi
  fi
  sleep 5
done

echo "!! Le serveur n'est pas revenu en ligne dans le délai imparti (${TIMEOUT} s)." >&2
echo "   Diagnostic : panel VeryGames (console/logs), ou 'scripts/verygames-rcon.py list'." >&2
exit 1
