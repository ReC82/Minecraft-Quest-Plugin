#!/usr/bin/env bash
#
# worldedit-wand-item.sh — change l'outil de sélection de WorldEdit sur le serveur (issue #192).
#
# Pourquoi un script dédié plutôt qu'une option de deploy-verygames.sh
# -------------------------------------------------------------------
# `deploy-verygames.sh --also` refuse DÉLIBÉRÉMENT tout chemin hors de `RPGQuest/` : ce garde-fou
# empêche de toucher par accident un autre plugin, un monde ou data.db. On ne l'affaiblit pas pour
# un cas particulier. Ce script-ci est donc volontairement **mono-usage et sans paramètre de
# chemin** : il ne peut écrire que le `config.yml` de WorldEdit, et n'y modifie qu'une seule clé.
#
# Le problème corrigé (voir docs/RPGQUEST_BIBLE.md §14)
# ----------------------------------------------------
# WorldEdit reconnaît sa baguette de sélection par TYPE D'OBJET (`wand-item`, par défaut
# `minecraft:wooden_axe`) en ignorant le PersistentDataContainer. La hache en bois du kit de départ
# (#26, qui doit garder ses quatre outils en bois) est donc interceptée : clic = « Première
# position définie », le bloc ne casse pas. Aucun correctif côté RPGQuest ne peut les distinguer.
# `/toggleeditwand` n'est PAS une solution : en WorldEdit 7.4.x il n'affiche plus qu'un rappel.
#
# Usage :
#   scripts/worldedit-wand-item.sh [--item minecraft:golden_axe] [--dry-run] [--no-reload]
#
#   --item ITEM   matériau de la baguette (défaut : minecraft:golden_axe). Doit être un id
#                 Minecraft en minuscules ; les matériaux du kit de départ sont REFUSÉS.
#   --dry-run     télécharge, montre le diff, n'écrit rien à distance.
#   --no-reload   n'exécute pas `/worldedit reload` par RCON (à faire soi-même ensuite).
#
# Le fichier distant est toujours sauvegardé AVANT écriture, et le téléversement est atomique
# (.part puis RNFR/RNTO), comme pour le JAR.
#
set -euo pipefail
IFS=$'\n\t'

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/verygames-common.sh
. "$HERE/lib/verygames-common.sh"

# Le dossier distant FTP (VERYGAMES_FTP_REMOTE_DIR=/) EST déjà le dossier « plugins/ » du serveur :
# la racine listée contient WorldEdit/, RPGQuest/, Citizens/… Le chemin est donc « WorldEdit/… »
# et surtout pas « plugins/WorldEdit/… » (vérifié par un listing réel, pas supposé).
REMOTE_PATH="WorldEdit/config.yml"   # jamais paramétrable : c'est tout l'intérêt du script
WAND_ITEM="minecraft:golden_axe"
DRY=0
DO_RELOAD=1

# Matériaux interdits : ceux du kit de départ (#26). Les remettre ici recréerait exactement le bug.
KIT_ITEMS="minecraft:wooden_axe minecraft:wooden_sword minecraft:wooden_pickaxe minecraft:wooden_shovel"

while [ $# -gt 0 ]; do
  case "$1" in
    --item)       WAND_ITEM="${2:-}" ; shift 2 ;;
    --dry-run)    DRY=1 ; shift ;;
    --no-reload)  DO_RELOAD=0 ; shift ;;
    -h|--help)    sed -n '2,32p' "$0" ; exit 0 ;;
    *) vg::die "Option inconnue : $1" ;;
  esac
done

[[ "$WAND_ITEM" =~ ^minecraft:[a-z_]+$ ]] \
  || vg::die "Item invalide : « $WAND_ITEM » (attendu : minecraft:<nom_en_minuscules>)."
for forbidden in $KIT_ITEMS; do
  [ "$WAND_ITEM" != "$forbidden" ] \
    || vg::die "« $WAND_ITEM » fait partie du kit de départ (#26) : ce serait recréer le bug #192."
done

vg::require_curl
vg::load_config
vg::validate_config
vg::has_connection_config || vg::die "Identifiants FTP absents — voir docs/deployment/VERYGAMES.md."

STAMP="$(vg::utc_stamp)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/we-wand.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
CURRENT="$WORK/config.yml"
PATCHED="$WORK/config.patched.yml"

vg::step "1/5 — Téléchargement de $REMOTE_PATH"
vg::remote_download "$REMOTE_PATH" "$CURRENT" \
  || vg::die "Téléchargement impossible — WorldEdit est-il bien installé dans WorldEdit/ ?"
vg::log "$(wc -c <"$CURRENT") octets reçus."

CUR_LINE="$(grep -E '^[[:space:]]*wand-item:' "$CURRENT" || true)"
[ -n "$CUR_LINE" ] || vg::die "Clé « wand-item » introuvable dans la config distante — abandon (aucune écriture)."
vg::log "Valeur actuelle :$(printf '%s' "$CUR_LINE" | sed 's/^[[:space:]]*wand-item://')"

vg::step "2/5 — Sauvegarde horodatée"
BACKUP_DIR="$VERYGAMES_BACKUP_DIR/worldedit-$STAMP"
if [ "$DRY" = 1 ]; then
  vg::log "[dry-run] sauvegarde qui serait écrite : $BACKUP_DIR/config.yml"
else
  mkdir -p "$BACKUP_DIR"
  cp "$CURRENT" "$BACKUP_DIR/config.yml"
  {
    printf 'backup_utc=%s\n' "$STAMP"
    printf 'remote_path=%s\n' "$REMOTE_PATH"
    printf 'operator=%s\n' "$(vg::operator_tag)"
    printf 'previous_wand_item=%s\n' "$(printf '%s' "$CUR_LINE" | sed 's/.*wand-item:[[:space:]]*//')"
    printf 'new_wand_item=%s\n' "$WAND_ITEM"
    printf 'issue=192\n'
  } >"$BACKUP_DIR/MANIFEST.txt"
  vg::log "Sauvegarde : $BACKUP_DIR/config.yml"
fi

vg::step "3/5 — Réécriture de la seule clé « wand-item »"
# Une seule ligne touchée, indentation d'origine préservée ; tout le reste du fichier est intact.
awk -v item="$WAND_ITEM" '
  /^[[:space:]]*wand-item:/ && !done {
    match($0, /^[[:space:]]*/)
    printf "%swand-item: %s\n", substr($0, 1, RLENGTH), item
    done = 1
    next
  }
  { print }
' "$CURRENT" >"$PATCHED"

if diff -u "$CURRENT" "$PATCHED" >"$WORK/diff.txt"; then
  vg::warn "Aucun changement : la baguette est déjà « $WAND_ITEM »."
  CHANGED=0
else
  CHANGED=1
  sed -n '1,40p' "$WORK/diff.txt" >&2
fi

if [ "$DRY" = 1 ]; then
  vg::step "Dry-run terminé — rien n'a été envoyé."
  exit 0
fi

if [ "$CHANGED" = 1 ]; then
  vg::step "4/5 — Téléversement atomique"
  vg::remote_put_atomic "$PATCHED" "$REMOTE_PATH" "$STAMP" "$(wc -c <"$PATCHED")" \
    || vg::die "Téléversement échoué — la config distante est inchangée (sauvegarde conservée)."
  vg::log "Écrit : $REMOTE_PATH"
else
  vg::step "4/5 — Téléversement ignoré (aucun changement)"
fi

vg::step "5/5 — Rechargement et vérification"
RCON="$HERE/verygames-rcon.py"
if [ "$DO_RELOAD" = 1 ] && [ -x "$RCON" ] || [ "$DO_RELOAD" = 1 ] && [ -f "$RCON" ]; then
  python3 "$RCON" "worldedit reload" || vg::warn "Rechargement RCON indisponible — redémarrer le serveur."
else
  vg::log "--no-reload : penser à exécuter « /worldedit reload »."
fi

# Vérification du FICHIER distant (ce qui est écrit), puis de la valeur réellement CHARGÉE :
# `/worldedit report` écrit un rapport côté serveur, qu'on relit pour confirmer l'état en mémoire.
vg::remote_download "$REMOTE_PATH" "$WORK/verify.yml" \
  && vg::log "Fichier distant relu :$(grep -E '^[[:space:]]*wand-item:' "$WORK/verify.yml" | sed 's/^[[:space:]]*wand-item://')"

vg::log "Vérifier en jeu : //wand doit donner « $WAND_ITEM » et la hache en bois du kit doit "
vg::log "casser un bloc normalement (voir docs/MANUAL_TEST_PLAN.md, TC-236)."
