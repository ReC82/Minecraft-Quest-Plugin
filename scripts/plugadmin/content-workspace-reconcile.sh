#!/usr/bin/env bash
#
# content-workspace-reconcile.sh — inventorier et réconcilier l'espace de travail de contenu de
#                                  PlugAdmin avec le serveur DEV (issue #798).
#
# Pourquoi ce script existe
# -------------------------
# Jusqu'au 23/10/2026, PLUGADMIN_CONTENT_DIR pointait sur l'arbre Git du propriétaire
# (/srv/rpgquest/repo/src/main/resources). Les sessions de développement travaillent dans des
# worktrees et livrent le contenu sur DEV par `deploy-verygames.sh --also` : cet arbre n'était
# donc jamais mis à jour, il est resté sur une branche d'octobre, et PlugAdmin montrait comme
# « brouillons à publier » des versions plus anciennes que DEV (Elias, Gauthier) et des quêtes
# retirées (first_steps, crystal_hunt). Une publication groupée les aurait remises en jeu.
#
# La correction durable a deux moitiés :
#   1. dans PlugAdmin : une version commune par ressource, des états qui disent QUI a changé,
#      « Récupérer depuis DEV » / « Conserver mon brouillon », aucune publication sans choix ;
#   2. ici : un espace de travail DÉDIÉ (/var/lib/plugadmin/content), qui n'appartient à aucune
#      branche Git, amorcé depuis DEV — et cet outil pour l'inventorier à tout moment.
#
# Sous-commandes (lecture seule sauf `seed` et `switch`, qui n'écrasent jamais rien) :
#
#   snapshot            télécharge (FTP, lecture seule) quests/, stories/, dialogues/ de DEV et copie
#                       l'espace de travail actuel, dans un dossier daté avec MANIFEST SHA-256.
#                       Affiche le chemin du dossier créé.
#   plan  SNAP          compare source et DEV fichier par fichier (SNAP/plan.tsv) : SAME, DEV_ONLY,
#                       SOURCE_ONLY, DIFFERENT, RETIRED. N'écrit que plan.tsv.
#   seed  SNAP [--keep kind/slug]...
#                       prépare SNAP/workspace : toutes les versions DEV, plus les seuls brouillons
#                       absents de DEV listés par --keep (jamais un contenu retiré). Rien d'autre.
#   switch SNAP [--apply]
#                       installe SNAP/workspace en /var/lib/plugadmin/content (refus s'il existe
#                       déjà), sauvegarde /etc/plugadmin/plugadmin.env, change PLUGADMIN_CONTENT_DIR
#                       et redémarre PlugAdmin. Sans --apply : affiche seulement ce qui serait fait.
#   rollback-env BACKUP remet un plugadmin.env sauvegardé par `switch`, puis redémarre PlugAdmin.
#                       Le dossier /var/lib/plugadmin/content est laissé en place (rien n'est perdu).
#
# Ne touche JAMAIS : les fichiers de DEV (aucun envoi FTP), l'arbre Git du propriétaire, data.db,
# config.yml, Citizens, les mondes. Les secrets FTP viennent de la configuration habituelle
# (scripts/lib/verygames-common.sh) et ne sont jamais affichés.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KINDS=(quests stories dialogues)
# Même liste que RetiredContent (plugin et panel) : décision du propriétaire, #778.
RETIRED=(quests/first_steps quests/crystal_hunt)
ENV_FILE=/etc/plugadmin/plugadmin.env
TARGET=/var/lib/plugadmin/content
BACKUP_ROOT="${RPGQUEST_BACKUP_ROOT:-$HOME/.local/share/rpgquest/verygames-backups}"

die() { printf '[reconcile] ERREUR : %s\n' "$*" >&2; exit 1; }
log() { printf '[reconcile] %s\n' "$*" >&2; }

is_retired() {
  local key=$1 r
  for r in "${RETIRED[@]}"; do [ "$r" = "$key" ] && return 0; done
  return 1
}

current_content_dir() {
  sudo -n grep -E '^PLUGADMIN_CONTENT_DIR=' "$ENV_FILE" | head -1 | cut -d= -f2-
}

manifest() { # <dir> → MANIFEST.sha256 (chemins relatifs)
  (cd "$1" && find . -type f -name '*.yml' | sort | xargs -r sha256sum) > "$1/MANIFEST.sha256"
}

cmd_snapshot() {
  # shellcheck source=../lib/verygames-common.sh
  source "$REPO_ROOT/scripts/lib/verygames-common.sh"
  vg::load_config >/dev/null
  local snap kind name src
  snap="$BACKUP_ROOT/content-snapshot-$(date -u +%Y%m%dT%H%M%SZ)"
  mkdir -p "$snap/dev" "$snap/source"
  for kind in "${KINDS[@]}"; do
    mkdir -p "$snap/dev/$kind"
    vg::curl --list-only "$(vg::remote_url_for "RPGQuest/$kind/")" > "$snap/dev/$kind.list" \
      || die "liste DEV $kind illisible"
    while read -r name; do
      name=${name%$'\r'}
      case "$name" in *.yml) ;; *) continue ;; esac
      vg::remote_download "RPGQuest/$kind/$name" "$snap/dev/$kind/$name" \
        || die "téléchargement DEV $kind/$name impossible"
    done < "$snap/dev/$kind.list"
  done
  manifest "$snap/dev"
  src=$(current_content_dir)
  [ -n "$src" ] || die "PLUGADMIN_CONTENT_DIR introuvable dans $ENV_FILE"
  printf '%s\n' "$src" > "$snap/source/ORIGIN"
  for kind in "${KINDS[@]}"; do
    mkdir -p "$snap/source/$kind"
    for f in $(sudo -n find "$src/$kind" -maxdepth 1 -type f -name '*.yml' -printf '%f\n' | sort); do
      # shellcheck disable=SC2024 # lecture en sudo, écriture voulue par l'utilisateur courant
      sudo -n cat "$src/$kind/$f" > "$snap/source/$kind/$f"
    done
  done
  manifest "$snap/source"
  log "instantané : $snap ($(wc -l < "$snap/dev/MANIFEST.sha256") fichiers DEV, $(wc -l < "$snap/source/MANIFEST.sha256") fichiers source)"
  printf '%s\n' "$snap"
}

sha_of() { [ -f "$1" ] && sha256sum "$1" | cut -c1-64 || printf ''; }

cmd_plan() {
  local snap=$1 kind slug key a b state
  [ -d "$snap/dev" ] && [ -d "$snap/source" ] || die "instantané incomplet : $snap"
  {
    printf 'famille\tid\tetat\tsource\tdev\n'
    for kind in "${KINDS[@]}"; do
      for slug in $( (ls "$snap/dev/$kind" "$snap/source/$kind" 2>/dev/null || true) | grep '\.yml$' | sed 's/\.yml$//' | sort -u); do
        key="$kind/$slug"
        a=$(sha_of "$snap/source/$kind/$slug.yml"); b=$(sha_of "$snap/dev/$kind/$slug.yml")
        if is_retired "$key"; then state=RETIRED
        elif [ -n "$a" ] && [ "$a" = "$b" ]; then state=SAME
        elif [ -z "$a" ]; then state=DEV_ONLY
        elif [ -z "$b" ]; then state=SOURCE_ONLY
        else state=DIFFERENT
        fi
        printf '%s\t%s\t%s\t%s\t%s\n' "$kind" "$slug" "$state" "${a:0:12}" "${b:0:12}"
      done
    done
  } > "$snap/plan.tsv"
  column -t -s $'\t' "$snap/plan.tsv"
}

cmd_seed() {
  local snap=$1; shift
  local keep=() kind f key
  while [ $# -gt 0 ]; do
    case "$1" in
      --keep) keep+=("$2"); shift 2 ;;
      *) die "option inconnue : $1" ;;
    esac
  done
  [ -d "$snap/dev" ] || die "instantané incomplet : $snap"
  [ ! -e "$snap/workspace" ] || die "$snap/workspace existe déjà : rien n'est écrasé"
  for kind in "${KINDS[@]}"; do
    mkdir -p "$snap/workspace/$kind"
    for f in "$snap/dev/$kind"/*.yml; do
      [ -e "$f" ] || continue
      key="$kind/$(basename "$f" .yml)"
      is_retired "$key" && { log "ignoré (retiré) : $key"; continue; }
      cp -p "$f" "$snap/workspace/$kind/"
    done
  done
  for key in "${keep[@]}"; do
    is_retired "$key" && die "$key est retiré définitivement : jamais repris"
    [ -f "$snap/source/$key.yml" ] || die "brouillon introuvable dans l'instantané : $key"
    [ ! -f "$snap/dev/$key.yml" ] || die "$key existe sur DEV : la version DEV est déjà reprise"
    cp -p "$snap/source/$key.yml" "$snap/workspace/$key.yml"
    log "brouillon conservé : $key"
  done
  manifest "$snap/workspace"
  log "espace de travail préparé : $snap/workspace ($(wc -l < "$snap/workspace/MANIFEST.sha256") fichiers)"
}

cmd_switch() {
  local snap=$1 apply=${2:-}
  local old stamp
  [ -f "$snap/workspace/MANIFEST.sha256" ] || die "lancer d'abord : seed $snap"
  old=$(current_content_dir)
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  log "actuel  : PLUGADMIN_CONTENT_DIR=$old"
  log "nouveau : PLUGADMIN_CONTENT_DIR=$TARGET (copie de $snap/workspace)"
  log "sauvegarde de $ENV_FILE → $ENV_FILE.pre-798-$stamp"
  if [ "$apply" != "--apply" ]; then
    log "simulation : relancer avec --apply pour appliquer"
    return 0
  fi
  sudo -n test ! -e "$TARGET" || die "$TARGET existe déjà : rien n'est écrasé"
  sudo -n cp -p "$ENV_FILE" "$ENV_FILE.pre-798-$stamp"
  sudo -n mkdir -p "$TARGET"
  sudo -n cp -a "$snap/workspace/." "$TARGET/"
  sudo -n rm -f "$TARGET/MANIFEST.sha256"
  sudo -n chown -R plugadmin:plugadmin "$TARGET"
  sudo -n chmod -R u=rwX,g=rX,o= "$TARGET"
  (cd "$snap/workspace" && grep -v MANIFEST MANIFEST.sha256 | sed "s#  \./#  $TARGET/#" | sudo -n sha256sum -c --quiet -) \
    || die "copie incomplète dans $TARGET : PLUGADMIN_CONTENT_DIR inchangé"
  sudo -n sed -i "s#^PLUGADMIN_CONTENT_DIR=.*#PLUGADMIN_CONTENT_DIR=$TARGET#" "$ENV_FILE"
  sudo -n systemctl restart plugadmin
  log "PlugAdmin redémarré sur $TARGET. Retour arrière : $0 rollback-env $ENV_FILE.pre-798-$stamp"
}

cmd_rollback_env() {
  local backup=$1
  sudo -n test -f "$backup" || die "sauvegarde introuvable : $backup"
  sudo -n cp -p "$backup" "$ENV_FILE"
  sudo -n systemctl restart plugadmin
  log "PLUGADMIN_CONTENT_DIR=$(current_content_dir) remis ; $TARGET laissé en place."
}

case "${1:-}" in
  snapshot) cmd_snapshot ;;
  plan) [ $# -ge 2 ] || die "usage : $0 plan SNAP"; cmd_plan "$2" ;;
  seed) [ $# -ge 2 ] || die "usage : $0 seed SNAP [--keep kind/slug]..."; shift; cmd_seed "$@" ;;
  switch) [ $# -ge 2 ] || die "usage : $0 switch SNAP [--apply]"; cmd_switch "$2" "${3:-}" ;;
  rollback-env) [ $# -ge 2 ] || die "usage : $0 rollback-env BACKUP"; cmd_rollback_env "$2" ;;
  *) sed -n '2,45p' "$0"; exit 2 ;;
esac
