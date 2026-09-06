#!/usr/bin/env bash
#
# deploy-verygames.sh — déploiement JAR RPGQuest vers VeryGames (issue #10).
#
# Automatise l'EXÉCUTION d'un déploiement (build vérifié, backup daté, transfert
# FTP atomique). Le déclenchement reste MANUEL, et l'arrêt / redémarrage du
# serveur VeryGames reste MANUEL (VeryGames n'expose ni API ni RCON exploitable
# dans notre configuration — accès FTP port 21 uniquement).
#
# Par défaut, le script n'adresse QU'UN seul chemin distant : celui du JAR.
# L'option --also permet une LISTE BLANCHE EXPLICITE de fichiers supplémentaires
# sous RPGQuest/ (chacun sauvegardé avant remplacement). Le script REFUSE
# toujours data.db, config.yml, messages.yml, spawn.yml, RPGQuest/Citizens/,
# tout autre plugin, tout monde, et toute traversée de chemin. Il ne synchronise
# JAMAIS un dossier entier.
#
# Usage :
#   scripts/deploy-verygames.sh [options]
#
#   --also LOCAL:REMOTE  Fichier supplémentaire à déployer, en plus du JAR.
#                        LOCAL   = chemin relatif au dépôt (ou absolu).
#                        REMOTE  = chemin distant, relatif à VERYGAMES_FTP_REMOTE_DIR,
#                                  qui DOIT commencer par "RPGQuest/".
#                        Répétable. Chaque fichier est sauvegardé avant remplacement.
#   --dry-run            Tout vérifier (git, tests, build, JAR) et n'AFFICHER
#                        que les actions FTP qui seraient faites. Aucune
#                        connexion. Sortie 0 même si l'hôte/mot de passe
#                        manquent encore.
#   --check              Ne rien construire : charger + valider la config, et
#                        (si les identifiants sont présents) tester la connexion.
#   --skip-build         (avec --dry-run uniquement) sauter ./gradlew test+build.
#   --allow-dirty        Autoriser un working tree Git non propre (déconseillé).
#   --allow-no-backup    Autoriser un déploiement même si un fichier ciblé
#                        (JAR ou --also) n'existe pas encore en ligne.
#   --server-stopped     Confirmer que le serveur VeryGames est déjà arrêté
#                        (saute la question interactive).
#   --prune-keep N       Après un déploiement réussi, ne garder que les N
#                        backups « predeploy » les plus récents (jamais le plus
#                        récent, jamais autre chose qu'un backup). Défaut : tout
#                        garder (ou VERYGAMES_BACKUP_KEEP).
#   --jar PATH           Chemin du JAR à déployer (défaut :
#                        build/libs/<VERYGAMES_PLUGIN_JAR_NAME>).
#   -y, --yes            Ne poser aucune question (implique --server-stopped).
#   -h, --help           Cette aide.
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

DRY_RUN=0
CHECK_ONLY=0
SKIP_BUILD=0
ALLOW_DIRTY=0
ALLOW_NO_BACKUP=0
SERVER_STOPPED=0
PRUNE_KEEP=""
JAR_OVERRIDE=""
ALSO_SPECS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --also)            ALSO_SPECS+=("${2:-}"); shift ;;
    --dry-run)         DRY_RUN=1 ;;
    --check)           CHECK_ONLY=1 ;;
    --skip-build)      SKIP_BUILD=1 ;;
    --allow-dirty)     ALLOW_DIRTY=1 ;;
    --allow-no-backup) ALLOW_NO_BACKUP=1 ;;
    --server-stopped)  SERVER_STOPPED=1 ;;
    --prune-keep)      PRUNE_KEEP="${2:-}"; shift ;;
    --jar)             JAR_OVERRIDE="${2:-}"; shift ;;
    -y|--yes)          SERVER_STOPPED=1 ;;
    -h|--help)         awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"; exit 0 ;;
    *) vg::die "Option inconnue : $1 (voir --help)" ;;
  esac
  shift
done

# --- Validation des fichiers supplémentaires (--also LOCAL:REMOTE) -----------
ALSO_LOCAL=()
ALSO_REMOTE=()
for spec in "${ALSO_SPECS[@]:-}"; do
  [ -n "$spec" ] || continue
  case "$spec" in
    *:*) : ;;
    *) vg::die "--also attend LOCAL:REMOTE (deux-points manquant) : '$spec'" ;;
  esac
  lp=${spec%%:*}
  rp=${spec#*:}
  if [ -z "$lp" ] || [ -z "$rp" ]; then
    vg::die "--also LOCAL et REMOTE ne peuvent pas être vides : '$spec'"
  fi
  case "$lp" in
    /*) : ;;
    *)  lp="$REPO_ROOT/$lp" ;;
  esac
  [ -f "$lp" ] || vg::die "--also : fichier local introuvable : $lp"
  [ -s "$lp" ] || vg::die "--also : fichier local vide : $lp"
  rp=${rp#./}
  if vg::_is_forbidden_remote "$rp"; then
    vg::die "--also : cible distante INTERDITE : '$rp'. Autorisé uniquement sous 'RPGQuest/' (jamais data.db, config.yml, messages.yml, spawn.yml, RPGQuest/Citizens/, un autre plugin, un monde, ni '..')."
  fi
  ALSO_LOCAL+=("$lp")
  ALSO_REMOTE+=("$rp")
done
ALSO_COUNT=${#ALSO_LOCAL[@]}

vg::require_curl

# ---------------------------------------------------------------------------
# Étape 0 — configuration d'accès
# ---------------------------------------------------------------------------
vg::step "Configuration d'accès VeryGames"
vg::load_config
vg::config_summary

if ! vg::validate_config; then
  vg::die "Configuration invalide (voir ci-dessus)."
fi

PRUNE_KEEP="${PRUNE_KEEP:-$VERYGAMES_BACKUP_KEEP}"
if [ -n "$PRUNE_KEEP" ]; then
  case "$PRUNE_KEEP" in
    ''|*[!0-9]*) vg::die "--prune-keep attend un entier, reçu : '$PRUNE_KEEP'" ;;
  esac
fi

if [ "$CHECK_ONLY" -eq 1 ]; then
  if vg::has_connection_config; then
    vg::validate_config --require-connection || vg::die "Configuration de connexion invalide."
    vg::step "Test de connexion FTP (listing non destructif)"
    if out=$(vg::remote_list 2>&1); then
      vg::log "Connexion + TLS OK. Dossier distant '$VERYGAMES_FTP_REMOTE_DIR' listé :"
      if [ -n "$out" ]; then printf '%s\n' "$out" | sed 's/^/    /' >&2; else vg::log "  (dossier vide)"; fi
      if printf '%s\n' "$out" | grep -qx "$VERYGAMES_PLUGIN_JAR_NAME"; then
        vg::log "JAR '$VERYGAMES_PLUGIN_JAR_NAME' : PRÉSENT en ligne."
      else
        vg::log "JAR '$VERYGAMES_PLUGIN_JAR_NAME' : ABSENT (premier déploiement -> --allow-no-backup)."
      fi
      exit 0
    else
      rc=$?
      vg::err "Échec : $(vg::explain_curl_rc "$rc")"
      exit 1
    fi
  else
    vg::warn "Hôte et/ou mot de passe absents : test de connexion impossible pour l'instant."
    vg::warn "Renseigner $(vg::config_file_path) puis relancer avec --check."
    exit 0
  fi
fi

CONNECTION_READY=0
if vg::has_connection_config; then
  vg::validate_config --require-connection && CONNECTION_READY=1 || CONNECTION_READY=0
fi

if [ "$DRY_RUN" -eq 0 ] && [ "$CONNECTION_READY" -eq 0 ]; then
  vg::err "Hôte et/ou mot de passe VeryGames absents ou invalides."
  vg::err "Renseigner $(vg::config_file_path) (voir docs/deployment/VERYGAMES.md),"
  vg::err "ou relancer avec --dry-run pour préparer sans transférer."
  exit 1
fi

# ---------------------------------------------------------------------------
# Étape 1 — working tree Git propre
# ---------------------------------------------------------------------------
vg::step "1/8 — État du dépôt Git"
cd "$REPO_ROOT"

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  vg::die "Pas dans un dépôt Git."
fi

if [ -n "$(git status --porcelain)" ]; then
  if [ "$ALLOW_DIRTY" -eq 1 ]; then
    vg::warn "Working tree NON propre — poursuite forcée (--allow-dirty)."
    git status --short >&2
  else
    vg::err "Working tree Git non propre. Committer/stasher, ou --allow-dirty."
    git status --short >&2
    exit 1
  fi
fi

# ---------------------------------------------------------------------------
# Étape 2 — branche et commit déployés
# ---------------------------------------------------------------------------
vg::step "2/8 — Version déployée"
GIT_BRANCH=$(git branch --show-current || echo '(detached)')
GIT_COMMIT=$(git rev-parse HEAD)
GIT_COMMIT_SHORT=$(git rev-parse --short HEAD)
GIT_DESCRIBE=$(git describe --tags --always --dirty 2>/dev/null || echo "$GIT_COMMIT_SHORT")
vg::log "Branche : $GIT_BRANCH"
vg::log "Commit  : $GIT_COMMIT_SHORT ($GIT_DESCRIBE)"
vg::log "         $(git log -1 --pretty=format:'%s' 2>/dev/null || true)"

# ---------------------------------------------------------------------------
# Étapes 3 & 4 — tests puis build
# ---------------------------------------------------------------------------
GRADLEW=("./gradlew")
[ -x "$REPO_ROOT/gradlew" ] || GRADLEW=("bash" "./gradlew")  # tolère un bit d'exécution perdu

if [ "$DRY_RUN" -eq 1 ] && [ "$SKIP_BUILD" -eq 1 ]; then
  vg::step "3-4/8 — ./gradlew test + build  (sautés : --dry-run --skip-build)"
else
  vg::step "3/8 — ./gradlew test"
  ( cd "$REPO_ROOT" && "${GRADLEW[@]}" --console=plain test )
  vg::step "4/8 — ./gradlew build"
  ( cd "$REPO_ROOT" && "${GRADLEW[@]}" --console=plain build )
fi

# ---------------------------------------------------------------------------
# Étape 5 — présence du JAR
# ---------------------------------------------------------------------------
vg::step "5/8 — JAR de déploiement"
JAR_PATH="${JAR_OVERRIDE:-$REPO_ROOT/build/libs/$VERYGAMES_PLUGIN_JAR_NAME}"
if [ ! -f "$JAR_PATH" ]; then
  if [ "$DRY_RUN" -eq 1 ] && [ "$SKIP_BUILD" -eq 1 ]; then
    vg::warn "JAR absent ($JAR_PATH) — attendu, build sauté en dry-run."
    JAR_SHA="(non construit)"
    JAR_SIZE="?"
  else
    vg::die "JAR introuvable : $JAR_PATH"
  fi
else
  vg::looks_like_jar "$JAR_PATH" || vg::die "$JAR_PATH ne ressemble pas à un JAR (signature ZIP absente)."
  JAR_SHA=$(vg::sha256 "$JAR_PATH")
  JAR_SIZE=$(wc -c <"$JAR_PATH" | tr -d ' ')
  vg::log "Fichier : $JAR_PATH"
  vg::log "Taille  : $JAR_SIZE octets"
  vg::log "SHA-256 : $JAR_SHA"
fi

# ---------------------------------------------------------------------------
# DRY-RUN : afficher le plan et sortir
# ---------------------------------------------------------------------------
if [ "$DRY_RUN" -eq 1 ]; then
  vg::step "DRY-RUN — actions FTP qui seraient exécutées"
  REMOTE_JAR=$(vg::redact "$(vg::remote_jar_url)")
  cat >&2 <<EOF
  (aucune connexion n'est établie en dry-run)

  1. Lister le dossier distant : $(vg::redact "$(vg::remote_dir_url)")
  2. Télécharger la version en ligne, si elle existe :
        $REMOTE_JAR
     -> ${VERYGAMES_BACKUP_DIR}/rpgquest-<UTC>Z-predeploy.jar (+ .meta)
  3. Téléverser le nouveau JAR sous un nom temporaire (.part-<UTC>Z),
     contrôler la taille (== ${JAR_SIZE} o), puis RNFR/RNTO -> ${VERYGAMES_PLUGIN_JAR_NAME}
EOF
  if [ "$ALSO_COUNT" -gt 0 ]; then
    printf '\n  Fichiers supplémentaires (--also), après le JAR :\n' >&2
    for i in $(seq 0 $((ALSO_COUNT - 1))); do
      printf '    - %s\n        backup : %s\n        upload : %s%s (atomique .part -> RNFR/RNTO)\n' \
        "${ALSO_REMOTE[$i]}" \
        "${VERYGAMES_BACKUP_DIR}/extra-<UTC>Z/${ALSO_REMOTE[$i]}" \
        "$(vg::redact "$(vg::remote_dir_url)")" "${ALSO_REMOTE[$i]}" >&2
    done
  fi
  cat >&2 <<EOF

  Fichiers JAMAIS touchés : data.db, config.yml, messages.yml, spawn.yml,
  RPGQuest/Citizens/, tout autre plugin, tout monde. Aucune synchro de dossier.
EOF
  if [ "$CONNECTION_READY" -eq 1 ]; then
    vg::log "Config de connexion : présente et valide."
  else
    vg::warn "Config de connexion : INCOMPLÈTE (hôte et/ou mot de passe manquants)."
    vg::warn "Renseigner $(vg::config_file_path) avant un déploiement réel."
  fi
  vg::log "Dry-run terminé — aucune action distante effectuée."
  exit 0
fi

# ---------------------------------------------------------------------------
# Confirmation : serveur arrêté ?
# ---------------------------------------------------------------------------
if [ "$SERVER_STOPPED" -eq 0 ]; then
  _also_note=""
  [ "$ALSO_COUNT" -gt 0 ] && _also_note=" + $ALSO_COUNT fichier(s) --also"
  if [ -r /dev/tty ]; then
    printf '%s\n' "Le serveur VeryGames doit être ARRÊTÉ (panel) avant de remplacer le JAR$_also_note." >&2
    printf '%s' "Le serveur est-il arrêté ? [y/N] " >&2
    read -r reply </dev/tty || reply=""
    case "$reply" in
      y|Y|o|O|yes|YES|oui|OUI) : ;;
      *) vg::die "Interrompu : arrêter le serveur puis relancer (ou --server-stopped)." ;;
    esac
  else
    vg::die "Confirmation requise mais pas de terminal. Relancer avec --server-stopped après avoir arrêté le serveur."
  fi
fi

# ---------------------------------------------------------------------------
# Étape 6 — connexion (déjà validée) + listing
# ---------------------------------------------------------------------------
vg::step "6/8 — Connexion FTP VeryGames"
vg::connectivity_check && _rc=0 || _rc=$?
if [ "$_rc" -ne 0 ]; then
  vg::die "Connexion FTP impossible : $(vg::explain_curl_rc "$_rc"). Abandon avant toute écriture."
fi
vg::log "Connexion OK."

# ---------------------------------------------------------------------------
# Étape 7 — backup daté de la version en ligne
# ---------------------------------------------------------------------------
vg::step "7/8 — Backup de la version actuellement déployée"
mkdir -p "$VERYGAMES_BACKUP_DIR"
STAMP=$(vg::utc_stamp)

if vg::remote_file_exists "$VERYGAMES_PLUGIN_JAR_NAME"; then
  BACKUP_JAR="$VERYGAMES_BACKUP_DIR/rpgquest-${STAMP}-predeploy.jar"
  vg::log "Téléchargement : $(vg::redact "$(vg::remote_jar_url)")"
  vg::remote_download "$VERYGAMES_PLUGIN_JAR_NAME" "$BACKUP_JAR" \
    || vg::die "Échec du téléchargement de la version en ligne. Aucun remplacement effectué."
  vg::looks_like_jar "$BACKUP_JAR" || vg::die "Le backup téléchargé n'est pas un JAR valide. Abandon."
  BACKUP_SHA=$(vg::sha256 "$BACKUP_JAR")
  BACKUP_SIZE=$(wc -c <"$BACKUP_JAR" | tr -d ' ')
  cat >"$BACKUP_JAR.meta" <<EOF
backup_utc=$STAMP
kind=predeploy
remote_dir=$VERYGAMES_FTP_REMOTE_DIR
remote_jar_name=$VERYGAMES_PLUGIN_JAR_NAME
backup_sha256=$BACKUP_SHA
backup_size_bytes=$BACKUP_SIZE
operator=$(vg::operator_tag)
new_version_git_commit=$GIT_COMMIT
new_version_git_branch=$GIT_BRANCH
new_version_git_describe=$GIT_DESCRIBE
new_version_jar_sha256=$JAR_SHA
EOF
  vg::log "Backup : $BACKUP_JAR"
  vg::log "         $BACKUP_SIZE octets, SHA-256 $BACKUP_SHA"
  vg::log "Méta   : $BACKUP_JAR.meta"
else
  if [ "$ALLOW_NO_BACKUP" -eq 1 ]; then
    vg::warn "Aucune version en ligne à sauvegarder (premier déploiement) — poursuite (--allow-no-backup)."
    BACKUP_JAR="(aucun — premier déploiement)"
  else
    vg::die "Aucun fichier '$VERYGAMES_PLUGIN_JAR_NAME' en ligne : rien à sauvegarder. Utiliser --allow-no-backup s'il s'agit du premier déploiement."
  fi
fi

# ---------------------------------------------------------------------------
# Étape 8 — transfert atomique du nouveau JAR
# ---------------------------------------------------------------------------
vg::step "8/8 — Transfert du nouveau JAR"
vg::log "Téléversement atomique -> $VERYGAMES_PLUGIN_JAR_NAME"
vg::remote_put_atomic "$JAR_PATH" "$VERYGAMES_PLUGIN_JAR_NAME" "$STAMP" "$JAR_SIZE" \
  || vg::die "Échec du transfert du JAR. La version en ligne n'a PAS été modifiée (ou : restaurer via scripts/rollback-verygames.sh --latest)."

FINAL_SIZE=$(vg::remote_file_size "$VERYGAMES_PLUGIN_JAR_NAME" || true)
if [ -n "$FINAL_SIZE" ] && [ "$FINAL_SIZE" != "$JAR_SIZE" ]; then
  vg::warn "Taille distante finale ($FINAL_SIZE) != locale ($JAR_SIZE) — À VÉRIFIER manuellement."
else
  vg::log "JAR en ligne : $FINAL_SIZE octets (== local)."
fi

# ---------------------------------------------------------------------------
# Étape 8b — fichiers RPGQuest supplémentaires (--also), liste blanche
# ---------------------------------------------------------------------------
EXTRA_BACKUP_DIR=""
if [ "$ALSO_COUNT" -gt 0 ]; then
  vg::step "8b — $ALSO_COUNT fichier(s) supplémentaire(s) (--also)"
  EXTRA_BACKUP_DIR="$VERYGAMES_BACKUP_DIR/extra-${STAMP}"
  mkdir -p "$EXTRA_BACKUP_DIR"
  MANIFEST="$EXTRA_BACKUP_DIR/MANIFEST.txt"
  {
    printf 'deploy_utc=%s\noperator=%s\ngit_branch=%s\ngit_commit=%s\ngit_describe=%s\n\n' \
      "$STAMP" "$(vg::operator_tag)" "$GIT_BRANCH" "$GIT_COMMIT" "$GIT_DESCRIBE"
    printf '# remote_path | ancien_sha256 | ancienne_taille | nouveau_sha256 (local) | nouvelle_taille\n'
  } >"$MANIFEST"

  for i in $(seq 0 $((ALSO_COUNT - 1))); do
    lp="${ALSO_LOCAL[$i]}"
    rp="${ALSO_REMOTE[$i]}"
    lsize=$(wc -c <"$lp" | tr -d ' ')
    lsha=$(vg::sha256 "$lp")
    vg::log "· $rp"

    old_sha="(absent)"; old_size="0"
    if vg::remote_file_exists "$rp"; then
      bpath="$EXTRA_BACKUP_DIR/$rp"
      mkdir -p "$(dirname "$bpath")"
      vg::remote_download "$rp" "$bpath" \
        || vg::die "Échec du backup de '$rp'. Aucun remplacement effectué pour ce fichier."
      old_sha=$(vg::sha256 "$bpath")
      old_size=$(wc -c <"$bpath" | tr -d ' ')
      vg::log "  backup : $bpath ($old_size o, sha256 $old_sha)"
    else
      if [ "$ALLOW_NO_BACKUP" -eq 1 ]; then
        vg::warn "  '$rp' absent en ligne — création (--allow-no-backup)."
      else
        vg::die "  '$rp' absent en ligne : rien à sauvegarder. Utiliser --allow-no-backup pour créer le fichier."
      fi
    fi

    vg::remote_put_atomic "$lp" "$rp" "$STAMP" "$lsize" \
      || vg::die "Échec du transfert de '$rp'. Fichiers déjà transférés : voir résumé ; restaurer depuis $EXTRA_BACKUP_DIR si besoin."
    gsize=$(vg::remote_file_size "$rp" || true)
    [ -z "$gsize" ] || [ "$gsize" = "$lsize" ] || vg::warn "  taille distante ($gsize) != locale ($lsize) pour '$rp' — À VÉRIFIER."
    vg::log "  transféré ($lsize o, sha256 $lsha)"
    printf '%s | %s | %s | %s | %s\n' "$rp" "$old_sha" "$old_size" "$lsha" "$lsize" >>"$MANIFEST"
  done
  vg::log "Manifeste : $MANIFEST"
fi

# ---------------------------------------------------------------------------
# Rétention optionnelle des backups
# ---------------------------------------------------------------------------
if [ -n "$PRUNE_KEEP" ]; then
  vg::step "Rétention des backups (garder $PRUNE_KEEP predeploy récents)"
  # Plus récents d'abord (sort -r) ; on supprime au-delà des PRUNE_KEEP premiers.
  mapfile -t OLD < <(vg::backup_files 'rpgquest-*-predeploy.jar' | sort -r | tail -n +"$((PRUNE_KEEP + 1))")
  if [ "${#OLD[@]}" -eq 0 ]; then
    vg::log "Rien à supprimer."
  else
    for f in "${OLD[@]}"; do
      vg::log "Suppression backup ancien : $f"
      rm -f "$f" "$f.meta"
    done
  fi
fi

# ---------------------------------------------------------------------------
# Résumé + actions manuelles restantes
# ---------------------------------------------------------------------------
vg::step "Déploiement terminé"
cat >&2 <<EOF
${VG_GREEN}Étapes réalisées :${VG_RESET}
  - working tree Git vérifié propre
  - branche $GIT_BRANCH / commit $GIT_COMMIT_SHORT
  - ./gradlew test  : OK
  - ./gradlew build : OK
  - JAR : $JAR_PATH ($JAR_SIZE o, SHA-256 $JAR_SHA)
  - backup JAR précédent : $BACKUP_JAR
  - JAR transféré vers : $(vg::redact "$(vg::remote_jar_url)")
EOF
if [ "$ALSO_COUNT" -gt 0 ]; then
  printf '  - fichiers --also transférés (%s) :\n' "$ALSO_COUNT" >&2
  for i in $(seq 0 $((ALSO_COUNT - 1))); do
    printf '      %s%s\n' "$(vg::redact "$(vg::remote_dir_url)")" "${ALSO_REMOTE[$i]}" >&2
  done
  printf '  - backups --also : %s/\n' "$EXTRA_BACKUP_DIR" >&2
fi
cat >&2 <<EOF

${VG_YELLOW}Actions MANUELLES restantes (VeryGames n'expose ni API ni RCON) :${VG_RESET}
  1. Démarrer le serveur depuis le panel VeryGames.
  2. Console : vérifier l'absence d'ERROR au démarrage, Java 21 confirmé.
  3. En jeu / console : /rpgquest version  -> doit afficher la nouvelle version.
  4. /plugins  -> RPGQuest en vert.
  5. Vérifier que world_hub, les PNJ et les quêtes sont intacts
     (sous-ensemble de la checklist docs/deployment/VERYGAMES.md).
  6. Renseigner docs/deployment/SERVER_CHANGELOG.md (déploiement effectué).

${VG_YELLOW}En cas de problème :${VG_RESET}
  scripts/rollback-verygames.sh --latest     # restaure le JAR précédent
EOF
if [ "$ALSO_COUNT" -gt 0 ]; then
  cat >&2 <<EOF
  # Restaurer les fichiers --also : réutiliser --also en sens inverse, p.ex.
  #   scripts/rollback-verygames.sh --also $EXTRA_BACKUP_DIR/<remote_path>:<remote_path>
  # (voir MANIFEST.txt dans le dossier de backup)
EOF
fi
