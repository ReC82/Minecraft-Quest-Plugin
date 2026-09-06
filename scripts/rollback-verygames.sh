#!/usr/bin/env bash
#
# rollback-verygames.sh — restaure une version précédente du JAR RPGQuest sur
# VeryGames à partir d'un backup local créé par deploy-verygames.sh (issue #10).
#
# Ne modifie AUCUNE donnée persistante (data.db, config.yml, messages.yml,
# spawn.yml, RPGQuest/Citizens/, mondes, autres plugins) : seuls le JAR et/ou
# les chemins RPGQuest/ passés en --also sont adressés.
# Le déclenchement et le redémarrage du serveur restent MANUELS.
#
# Usage :
#   scripts/rollback-verygames.sh --list
#   scripts/rollback-verygames.sh --latest [options]
#   scripts/rollback-verygames.sh --backup <fichier.jar> [options]
#   scripts/rollback-verygames.sh --also LOCAL:REMOTE [--also ...] [options]
#
#   --list              Lister les backups JAR disponibles puis quitter.
#   --latest            Restaurer le backup « predeploy » le plus récent
#                       (= la version qui était en ligne avant le dernier
#                       déploiement).
#   --backup PATH       Restaurer ce fichier de backup JAR précis.
#   --also LOCAL:REMOTE  Restaurer un fichier RPGQuest/ précis depuis un backup
#                       local (typiquement un fichier du dossier extra-<UTC>Z/
#                       créé par deploy-verygames.sh). Répétable. La version
#                       actuellement en ligne est sauvegardée avant écrasement.
#                       Peut être utilisé seul (sans --latest/--backup).
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
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

MODE=""
BACKUP_ARG=""
DRY_RUN=0
SERVER_STOPPED=0
ALSO_SPECS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --list)           MODE="list" ;;
    --latest)         MODE="latest" ;;
    --backup)         MODE="explicit"; BACKUP_ARG="${2:-}"; shift ;;
    --also)           ALSO_SPECS+=("${2:-}"); shift ;;
    --dry-run)        DRY_RUN=1 ;;
    --server-stopped) SERVER_STOPPED=1 ;;
    -y|--yes)         SERVER_STOPPED=1 ;;
    -h|--help)        awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"; exit 0 ;;
    *) vg::die "Option inconnue : $1 (voir --help)" ;;
  esac
  shift
done

# --- Validation des fichiers --also (mêmes règles que deploy) ---------------
ALSO_LOCAL=()
ALSO_REMOTE=()
for spec in "${ALSO_SPECS[@]:-}"; do
  [ -n "$spec" ] || continue
  case "$spec" in *:*) : ;; *) vg::die "--also attend LOCAL:REMOTE : '$spec'" ;; esac
  lp=${spec%%:*}; rp=${spec#*:}
  if [ -z "$lp" ] || [ -z "$rp" ]; then vg::die "--also LOCAL/REMOTE vides : '$spec'"; fi
  case "$lp" in /*) : ;; *) lp="$REPO_ROOT/$lp" ;; esac
  [ -f "$lp" ] || vg::die "--also : fichier local introuvable : $lp"
  [ -s "$lp" ] || vg::die "--also : fichier local vide : $lp"
  rp=${rp#./}
  if vg::_is_forbidden_remote "$rp"; then
    vg::die "--also : cible distante INTERDITE : '$rp' (uniquement sous RPGQuest/, jamais data.db/config.yml/messages.yml/spawn.yml/Citizens/…)."
  fi
  ALSO_LOCAL+=("$lp"); ALSO_REMOTE+=("$rp")
done
ALSO_COUNT=${#ALSO_LOCAL[@]}

[ -n "$MODE" ] || [ "$ALSO_COUNT" -gt 0 ] \
  || vg::die "Préciser --list, --latest, --backup <fichier>, ou --also LOCAL:REMOTE. Voir --help."

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

DO_JAR=0
case "$MODE" in latest|explicit) DO_JAR=1 ;; esac

# ---------------------------------------------------------------------------
# Sélection du backup JAR (si --latest / --backup)
# ---------------------------------------------------------------------------
BACKUP_FILE=""; BACKUP_SHA=""; BACKUP_SIZE=""
if [ "$DO_JAR" -eq 1 ]; then
  vg::step "Sélection du backup JAR"
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
fi

if [ "$ALSO_COUNT" -gt 0 ]; then
  vg::step "Fichiers --also à restaurer"
  for i in $(seq 0 $((ALSO_COUNT - 1))); do
    vg::log "· ${ALSO_LOCAL[$i]}  ->  ${ALSO_REMOTE[$i]}  ($(wc -c <"${ALSO_LOCAL[$i]}" | tr -d ' ') o)"
  done
fi

# ---------------------------------------------------------------------------
# Validation de la config + dry-run
# ---------------------------------------------------------------------------
CONNECTION_READY=0
if vg::has_connection_config && vg::validate_config --require-connection; then
  CONNECTION_READY=1
fi

if [ "$DRY_RUN" -eq 1 ]; then
  vg::step "DRY-RUN — actions FTP qui seraient exécutées"
  printf '  (aucune connexion n'\''est établie en dry-run)\n\n' >&2
  if [ "$DO_JAR" -eq 1 ]; then
    cat >&2 <<EOF
  JAR :
  1. Télécharger la version en ligne -> ${VERYGAMES_BACKUP_DIR}/rpgquest-<UTC>Z-prerollback.jar (+ .meta)
  2. Téléverser $(basename "$BACKUP_FILE") en .part-<UTC>Z, contrôle taille (== ${BACKUP_SIZE} o), RNFR/RNTO -> ${VERYGAMES_PLUGIN_JAR_NAME}
EOF
  fi
  if [ "$ALSO_COUNT" -gt 0 ]; then
    printf '\n  Fichiers --also :\n' >&2
    for i in $(seq 0 $((ALSO_COUNT - 1))); do
      printf '    - %s\n        backup en ligne -> %s/extra-<UTC>Z-prerollback/%s\n        upload atomique %s%s\n' \
        "${ALSO_REMOTE[$i]}" "$VERYGAMES_BACKUP_DIR" "${ALSO_REMOTE[$i]}" \
        "$(vg::redact "$(vg::remote_dir_url)")" "${ALSO_REMOTE[$i]}" >&2
    done
  fi
  printf '\n  Fichiers JAMAIS touchés : data.db, config.yml, messages.yml, spawn.yml, RPGQuest/Citizens/, mondes, autres plugins.\n' >&2
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
    if [ "$DO_JAR" -eq 1 ]; then
      printf '%s\n' "Restaurer le JAR $(basename "$BACKUP_FILE") vers $(vg::redact "$(vg::remote_jar_url)")" >&2
    fi
    [ "$ALSO_COUNT" -gt 0 ] && printf '%s\n' "Restaurer $ALSO_COUNT fichier(s) --also sous RPGQuest/" >&2
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
vg::connectivity_check && _rc=0 || _rc=$?
if [ "$_rc" -ne 0 ]; then
  vg::die "Connexion FTP impossible : $(vg::explain_curl_rc "$_rc"). Abandon avant toute écriture."
fi

mkdir -p "$VERYGAMES_BACKUP_DIR"
STAMP=$(vg::utc_stamp)

if [ "$DO_JAR" -eq 1 ]; then
  vg::step "Backup de sécurité du JAR actuellement en ligne"
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

  vg::step "Restauration du JAR"
  vg::remote_put_atomic "$BACKUP_FILE" "$VERYGAMES_PLUGIN_JAR_NAME" "$STAMP" "$BACKUP_SIZE" \
    || vg::die "Échec de la restauration du JAR. État distant à vérifier."
  vg::log "JAR restauré : $(basename "$BACKUP_FILE") -> $(vg::redact "$(vg::remote_jar_url)")"
fi

EXTRA_PRE_DIR=""
if [ "$ALSO_COUNT" -gt 0 ]; then
  vg::step "Restauration de $ALSO_COUNT fichier(s) --also"
  EXTRA_PRE_DIR="$VERYGAMES_BACKUP_DIR/extra-${STAMP}-prerollback"
  mkdir -p "$EXTRA_PRE_DIR"
  for i in $(seq 0 $((ALSO_COUNT - 1))); do
    lp="${ALSO_LOCAL[$i]}"; rp="${ALSO_REMOTE[$i]}"
    lsize=$(wc -c <"$lp" | tr -d ' ')
    vg::log "· $rp"
    if vg::remote_file_exists "$rp"; then
      bp="$EXTRA_PRE_DIR/$rp"
      mkdir -p "$(dirname "$bp")"
      vg::remote_download "$rp" "$bp" \
        || vg::die "Échec du backup de sécurité de '$rp'. Aucun changement pour ce fichier."
      vg::log "  backup en ligne -> $bp"
    else
      vg::warn "  '$rp' absent en ligne — création."
    fi
    vg::remote_put_atomic "$lp" "$rp" "$STAMP" "$lsize" \
      || vg::die "Échec de la restauration de '$rp'. État distant à vérifier."
    vg::log "  restauré ($lsize o)"
  done
fi

vg::step "Rollback terminé"
[ "$DO_JAR" -eq 1 ] && printf '%s %s -> %s\n' "${VG_GREEN}JAR restauré :${VG_RESET}" "$(basename "$BACKUP_FILE")" "$(vg::redact "$(vg::remote_jar_url)")" >&2
[ "$ALSO_COUNT" -gt 0 ] && printf '%s %s fichier(s) (backup en ligne dans %s/)\n' "${VG_GREEN}--also restaurés :${VG_RESET}" "$ALSO_COUNT" "$EXTRA_PRE_DIR" >&2
cat >&2 <<EOF

${VG_YELLOW}Actions MANUELLES restantes :${VG_RESET}
  1. Démarrer le serveur depuis le panel VeryGames.
  2. Console : absence d'ERROR au démarrage.
  3. /rpgquest version  -> doit afficher la version restaurée.
  4. /plugins  -> RPGQuest en vert ; world_hub, PNJ et quêtes intacts.
  5. Renseigner docs/deployment/SERVER_CHANGELOG.md (rollback effectué).
EOF
