#!/usr/bin/env bash
#
# verygames-fetch-ca.sh — reconstruit la chaîne de certificats manquante que le
# serveur FTPS VeryGames ne présente pas (issue #10).
#
# Le serveur (ProFTPD) ne renvoie que le certificat feuille ; les intermédiaires
# Let's Encrypt (« YE1 », « Root YE » cross-signé par « ISRG Root X2 ») ne sont
# pas envoyés, donc curl/openssl ne peuvent pas vérifier la chaîne — SANS que le
# certificat soit pour autant invalide.
#
# Ce script, à partir du certificat réellement servi, suit les URL « CA Issuers »
# (AIA, servies par Let's Encrypt) pour récupérer chaque intermédiaire manquant,
# vérifie la chaîne complète avec `openssl verify` contre le magasin système,
# puis écrit les intermédiaires dans un fichier PEM.
#
# Il ne se connecte JAMAIS avec des identifiants et ne transfère RIEN.
#
# Usage :
#   scripts/verygames-fetch-ca.sh [--host H] [--port P] [--out FICHIER] [--print]
#
#   --host / --port   surchargent VERYGAMES_FTP_HOST / VERYGAMES_FTP_PORT
#   --out FICHIER      destination (défaut : ~/.config/rpgquest/verygames-ca.pem)
#   --print           écrit la chaîne sur stdout au lieu du fichier
#   -h, --help        cette aide

set -euo pipefail
IFS=$'\n\t'

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=scripts/lib/verygames-common.sh
. "$SCRIPT_DIR/lib/verygames-common.sh"

HOST_OVERRIDE=""; PORT_OVERRIDE=""; PRINT_ONLY=0
OUT="${XDG_CONFIG_HOME:-$HOME/.config}/rpgquest/verygames-ca.pem"

while [ $# -gt 0 ]; do
  case "$1" in
    --host)  HOST_OVERRIDE="${2:-}"; shift ;;
    --port)  PORT_OVERRIDE="${2:-}"; shift ;;
    --out)   OUT="${2:-}"; shift ;;
    --print) PRINT_ONLY=1 ;;
    -h|--help) awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"; exit 0 ;;
    *) vg::die "Option inconnue : $1 (voir --help)" ;;
  esac
  shift
done

command -v openssl >/dev/null 2>&1 || vg::die "openssl est requis."
vg::require_curl
vg::load_config
HOST="${HOST_OVERRIDE:-${VERYGAMES_FTP_HOST:-}}"
PORT="${PORT_OVERRIDE:-${VERYGAMES_FTP_PORT:-21}}"
[ -n "$HOST" ] || vg::die "Hôte inconnu : renseigner VERYGAMES_FTP_HOST ou passer --host."

SYSCA=$(vg::_system_ca_file || true)
[ -n "$SYSCA" ] || vg::die "Magasin CA système introuvable (ca-certificates installé ?)."

WORK=$(mktemp -d "${TMPDIR:-/tmp}/vg-ca.XXXXXX")
trap 'rm -rf "$WORK"' EXIT INT TERM

vg::step "Certificat réellement servi par $HOST:$PORT (STARTTLS ftp, aucune authentification)"
if ! echo | openssl s_client -connect "$HOST:$PORT" -starttls ftp -servername "$HOST" \
      2>/dev/null | openssl x509 -out "$WORK/leaf.pem" 2>/dev/null; then
  vg::die "Impossible de récupérer le certificat (le serveur répond-il en FTPS sur ce port ?)."
fi
openssl x509 -in "$WORK/leaf.pem" -noout -subject -issuer -dates | sed 's/^/  /' >&2

# AIA-chasing : on suit « CA Issuers » tant que la chaîne n'est pas vérifiable
# contre le magasin système. On s'arrête dès que `openssl verify` réussit.
CHAIN="$WORK/chain.pem"
: >"$CHAIN"
cur="$WORK/leaf.pem"
verify_ok() {
  local vargs=(-CAfile "$SYSCA")
  [ -s "$CHAIN" ] && vargs+=(-untrusted "$CHAIN")
  openssl verify "${vargs[@]}" "$WORK/leaf.pem" >/dev/null 2>&1
}
for hop in 1 2 3 4 5; do
  if verify_ok; then
    break
  fi
  aia=$(openssl x509 -in "$cur" -noout -text 2>/dev/null \
          | awk -F'URI:' '/CA Issuers/{print $2; exit}' | tr -d '[:space:]')
  [ -n "$aia" ] || vg::die "Chaîne incomplète et pas d'URL « CA Issuers » sur $(basename "$cur") — abandon."
  vg::log "hop $hop : récupération de l'émetteur -> $aia"
  next="$WORK/inter-$hop.pem"
  curl -fsSL --max-time 20 -o "$WORK/inter-$hop.der" "$aia" \
    || vg::die "Téléchargement de l'intermédiaire échoué : $aia"
  openssl x509 -inform DER -in "$WORK/inter-$hop.der" -out "$next" 2>/dev/null \
    || openssl x509 -inform PEM -in "$WORK/inter-$hop.der" -out "$next"
  openssl x509 -in "$next" -noout -subject -issuer -dates | sed 's/^/    /' >&2
  cat "$next" >>"$CHAIN"
  cur="$next"
done

if ! [ -s "$CHAIN" ]; then
  vg::log "La chaîne est déjà vérifiable avec le seul magasin système — VERYGAMES_FTP_CA_EXTRA est inutile ici."
  exit 0
fi

vg::step "Vérification de la chaîne reconstruite (openssl verify, magasin système)"
if ! openssl verify -CAfile "$SYSCA" -untrusted "$CHAIN" "$WORK/leaf.pem"; then
  vg::die "La chaîne reste invalide après reconstruction — NE PAS contourner avec --insecure. Remonter le problème à VeryGames (chaîne TLS incomplète côté serveur)."
fi
n=$(grep -c 'BEGIN CERTIFICATE' "$CHAIN" || echo 0)
vg::log "OK — $n intermédiaire(s) reconstruit(s), chaîne vérifiable contre le magasin système."

if [ "$PRINT_ONLY" -eq 1 ]; then
  cat "$CHAIN"
  exit 0
fi

mkdir -p "$(dirname "$OUT")"
{
  printf '# Intermédiaires TLS reconstruits pour le FTPS VeryGames (%s)\n' "$HOST"
  printf '# Générés le %s par scripts/verygames-fetch-ca.sh — certificats publics, aucun secret.\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  cat "$CHAIN"
} >"$OUT"
chmod 644 "$OUT"

vg::step "Écrit : $OUT"
cat >&2 <<EOF
Ajouter cette ligne à ~/.config/rpgquest/verygames.env :

    VERYGAMES_FTP_CA_EXTRA=$OUT

puis :  scripts/deploy-verygames.sh --check
EOF
