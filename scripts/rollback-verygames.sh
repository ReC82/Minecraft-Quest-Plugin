#!/usr/bin/env bash
#
# rollback-verygames.sh — restaure une version précédente du JAR RPGQuest sur
# VeryGames à partir d'un backup local créé par deploy-verygames.sh (issue #10).
#
# Ne modifie AUCUNE donnée persistante (data.db, config.yml, messages.yml,
# mondes, autres plugins) : seul le chemin distant du JAR est adressé.
# Le déclenchement et le redémarrage du serveur restent MANUELS.
#
# Usage :
#   scripts/rollback-verygames.sh --list
#   scripts/rollback-verygames.sh --latest [options]
#   scripts/rollback-verygames.sh --backup <fichier.jar> [options]
#
#   --list              Lister les backups disponibles puis quitter.
#   --latest            Restaurer le backup « predeploy » le plus récent
#                       (= la version qui était en ligne avant le dernier
#                       déploiement).
#   --backup PATH       Restaurer ce fichier de backup précis.
#   --dry-run           N'afficher que les actions FTP prévues. Aucune connexion.
#   --server-stopped    Confirmer que le serveur VeryGames est déjà arrêté.
#   -y, --yes           Ne poser aucune question (implique --server-stopped).
#   -h, --help          Cette aide.
#
# Configuration d'accès (hors Git) : voir scripts/verygames.env.example et
# docs/deployment/VERYGAMES.md.

set -euo pipefail
IFS=$'\n\t'

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=scripts/lib/verygames-common.sh
. "$SCRIPT_DIR/lib/verygames-common.sh"
vg::install_cleanup

MODE=""
BACKUP_ARG=""
DRY_RUN=0
SERVER_STOPPED=0

while [ $# -gt 0 ]; do
  case "$1" in
    --list)           MODE="list" ;;
    --latest)         MODE="latest" ;;
    --backup)         MODE="explicit"; BACKUP_ARG="${2:-}"; shift ;;
    --dry-run)        DRY_RUN=1 ;;
    --server-stopped) SERVER_STOPPED=1 ;;
    -y|--yes)         SERVER_STOPPED=1 ;;
    -h|--help)        awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"; exit 0 ;;
    *) vg::die "Option inconnue : $1 (voir --help)" ;;
  esac
  shift
done

[ -n "$MODE" ] || vg::die "Préciser --list, --latest ou --backup <fichier>. Voir --help."

vg::require_curl
vg::step "Configuration d'accès VeryGames"
vg::load_config

# ---------------------------------------------------------------------------
# --list
# ---------------------------------------------------------------------------
list_backups() {
  local dir="$VERYGAMES_BACKUP_DIR"
  local list; list=$(vg::backup_files)
  if [ -z "$list" ]; then
    vg::warn "Aucun backup dans $dir"
    return 1
  fi
  printf '%s\n' "Backups disponibles (${dir}) — du plus ancien au plus récent :" >&2
  local f meta size sha commit describe kind when
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    size=$(wc -c <"$f" | tr -d ' ')
    sha=$(vg::sha256 "$f")
    meta="$f.meta"
    kind="?"; commit="?"; describe="?"; when="?"
    if [ -f "$meta" ]; then
      kind=$(awk -F= '$1=="kind"{print $2}' "$meta")
      when=$(awk -F= '$1=="backup_utc"{print $2}' "$meta")
      commit=$(awk -F= '$1=="new_version_git_commit"{print substr($2,1,10)}' "$meta")
      describe=$(awk -F= '$1=="new_version_git_describe"{print $2}' "$meta")
    fi
    printf '  %s\n    %s o | sha256 %s\n    kind=%s utc=%s | déployé par-dessus, commit=%s (%s)\n' \
      "$(basename "$f")" "$size" "$sha" "$kind" "$when" "$commit" "$describe" >&2
  done <<<"$list"
}

if [ "$MODE" = "list" ]; then
  list_backups || exit 1
  exit 0
fi

# ---------------------------------------------------------------------------
# Sélection du backup
# ---------------------------------------------------------------------------
vg::step "Sélection du backup"
case "$MODE" in
  latest)
    BACKUP_FILE=$(vg::backup_files 'rpgquest-*-predeploy.jar' | tail -n1)
    [ -n "$BACKUP_FILE" ] || BACKUP_FILE=$(vg::backup_files | tail -n1)
    [ -n "$BACKUP_FILE" ] || vg::die "Aucun backup valide dans $VERYGAMES_BACKUP_DIR — rollback impossible."
    ;;
  explicit)
    [ -n "$BACKUP_ARG" ] || vg::die "--backup attend un chemin de fichier."
    BACKUP_FILE="$BACKUP_ARG"
    [ -f "$BACKUP_FILE" ] || vg::die "Backup introuvable : $BACKUP_FILE"
    ;;
esac

vg::looks_like_jar "$BACKUP_FILE" || vg::die "$BACKUP_FILE n'est pas un JAR valide (signature ZIP absente) — rollback refusé."
BACKUP_SHA=$(vg::sha256 "$BACKUP_FILE")
BACKUP_SIZE=$(wc -c <"$BACKUP_FILE" | tr -d ' ')

if [ -f "$BACKUP_FILE.meta" ]; then
  META_SHA=$(awk -F= '$1=="backup_sha256"{print $2}' "$BACKUP_FILE.meta")
  if [ -n "$META_SHA" ] && [ "$META_SHA" != "$BACKUP_SHA" ]; then
    vg::die "SHA-256 du backup ($BACKUP_SHA) != valeur enregistrée dans .meta ($META_SHA). Fichier corrompu — rollback refusé."
  fi
fi

vg::log "Backup sélectionné : $BACKUP_FILE"
vg::log "  $BACKUP_SIZE octets, SHA-256 $BACKUP_SHA"
[ -f "$BACKUP_FILE.meta" ] && sed 's/^/  meta: /' "$BACKUP_FILE.meta" >&2

# ---------------------------------------------------------------------------
# Validation de la config + dry-run
# ---------------------------------------------------------------------------
CONNECTION_READY=0
if vg::has_connection_config && vg::validate_config --require-connection; then
  CONNECTION_READY=1
fi

if [ "$DRY_RUN" -eq 1 ]; then
  vg::step "DRY-RUN — actions FTP qui seraient exécutées"
  cat >&2 <<EOF
  (aucune connexion n'est établie en dry-run)

  1. Télécharger la version actuellement en ligne, si elle existe :
        $(vg::redact "$(vg::remote_jar_url)")
     -> ${VERYGAMES_BACKUP_DIR}/rpgquest-<UTC>Z-prerollback.jar (+ .meta)
  2. Téléverser le backup sélectionné sous un nom temporaire :
        ${VERYGAMES_PLUGIN_JAR_NAME}.part-<UTC>Z
  3. Renommer (RNFR/RNTO) en : ${VERYGAMES_PLUGIN_JAR_NAME}
  4. Vérifier la taille distante == ${BACKUP_SIZE} octets.

  Fichiers JAMAIS touchés : data.db, config.yml, messages.yml, mondes, autres plugins.
EOF
  if [ "$CONNECTION_READY" -eq 1 ]; then
    vg::log "Config de connexion : présente et valide."
  else
    vg::warn "Config de connexion INCOMPLÈTE — renseigner $(vg::config_file_path) avant un rollback réel."
  fi
  exit 0
fi

[ "$CONNECTION_READY" -eq 1 ] || vg::die "Hôte/mot de passe VeryGames absents ou invalides. Renseigner $(vg::config_file_path)."

# ---------------------------------------------------------------------------
# Confirmation
# ---------------------------------------------------------------------------
if [ "$SERVER_STOPPED" -eq 0 ]; then
  if [ -r /dev/tty ]; then
    printf '%s\n' "Restaurer $(basename "$BACKUP_FILE") vers $(vg::redact "$(vg::remote_jar_url)") ?" >&2
    printf '%s\n' "Le serveur VeryGames doit être ARRÊTÉ (panel)." >&2
    printf '%s' "Confirmer ? [y/N] " >&2
    read -r reply </dev/tty || reply=""
    case "$reply" in
      y|Y|o|O|yes|YES|oui|OUI) : ;;
      *) vg::die "Rollback annulé." ;;
    esac
  else
    vg::die "Confirmation requise mais pas de terminal. Relancer avec --server-stopped."
  fi
fi

# ---------------------------------------------------------------------------
# Connexion + backup de sécurité de la version courante
# ---------------------------------------------------------------------------
vg::step "Connexion FTP VeryGames"
vg::connectivity_check || vg::die "Impossible de lister le dossier distant. Abandon avant toute écriture."

mkdir -p "$VERYGAMES_BACKUP_DIR"
STAMP=$(vg::utc_stamp)

vg::step "Backup de sécurité de la version actuellement en ligne"
if vg::remote_file_exists "$VERYGAMES_PLUGIN_JAR_NAME"; then
  PRE="$VERYGAMES_BACKUP_DIR/rpgquest-${STAMP}-prerollback.jar"
  vg::remote_download "$VERYGAMES_PLUGIN_JAR_NAME" "$PRE" \
    || vg::die "Échec du téléchargement de la version en ligne. Aucun changement effectué."
  cat >"$PRE.meta" <<EOF
backup_utc=$STAMP
kind=prerollback
remote_dir=$VERYGAMES_FTP_REMOTE_DIR
remote_jar_name=$VERYGAMES_PLUGIN_JAR_NAME
backup_sha256=$(vg::sha256 "$PRE")
backup_size_bytes=$(wc -c <"$PRE" | tr -d ' ')
operator=$(vg::operator_tag)
restored_from=$(basename "$BACKUP_FILE")
restored_sha256=$BACKUP_SHA
EOF
  vg::log "Backup de sécurité : $PRE"
else
  vg::warn "Aucune version en ligne actuellement — pas de backup de sécurité à créer."
fi

# ---------------------------------------------------------------------------
# Transfert atomique du backup
# ---------------------------------------------------------------------------
vg::step "Restauration"
TMP_NAME="${VERYGAMES_PLUGIN_JAR_NAME}.part-${STAMP}"
vg::remote_upload "$BACKUP_FILE" "$TMP_NAME" || {
  vg::remote_delete "$TMP_NAME" 2>/dev/null || true
  vg::die "Échec du téléversement. Version en ligne inchangée."
}
REMOTE_TMP_SIZE=$(vg::remote_file_size "$TMP_NAME" || true)
if [ -n "$REMOTE_TMP_SIZE" ] && [ "$REMOTE_TMP_SIZE" != "$BACKUP_SIZE" ]; then
  vg::remote_delete "$TMP_NAME" 2>/dev/null || true
  vg::die "Taille distante ($REMOTE_TMP_SIZE) != backup ($BACKUP_SIZE). Fichier partiel supprimé, version en ligne intacte."
fi
if ! vg::remote_rename "$TMP_NAME" "$VERYGAMES_PLUGIN_JAR_NAME"; then
  vg::warn "RNFR/RNTO refusé. Repli : téléversement direct."
  vg::remote_upload "$BACKUP_FILE" "$VERYGAMES_PLUGIN_JAR_NAME" \
    || vg::die "Échec du téléversement direct. État distant à vérifier manuellement."
  vg::remote_delete "$TMP_NAME" 2>/dev/null || true
fi

vg::step "Rollback terminé"
cat >&2 <<EOF
${VG_GREEN}Restauré :${VG_RESET} $(basename "$BACKUP_FILE")
${VG_GREEN}Vers     :${VG_RESET} $(vg::redact "$(vg::remote_jar_url)")

${VG_YELLOW}Actions MANUELLES restantes :${VG_RESET}
  1. Démarrer le serveur depuis le panel VeryGames.
  2. Console : absence d'ERROR au démarrage.
  3. /rpgquest version  -> doit afficher la version restaurée.
  4. /plugins  -> RPGQuest en vert ; world_hub, PNJ et quêtes intacts.
  5. Renseigner docs/deployment/SERVER_CHANGELOG.md (rollback effectué).
EOF
