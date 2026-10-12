#!/usr/bin/env bash
#
# check.sh — validation locale RAPIDE, proportionnée à ce qui a changé (issue #257).
#
# La suite complète (trois modules, ~3 800 tests) tourne dans GitHub Actions à chaque push. Ici, on
# ne teste que les modules réellement touchés, et jamais deux fois le même code : un module déjà
# validé (même empreinte de code, arbre propre) est sauté.
#
# Usage :
#   scripts/dev/check.sh                     # modules touchés depuis l'amont (+ arbre de travail)
#   scripts/dev/check.sh --base REF          # modules touchés depuis REF
#   scripts/dev/check.sh --module panel      # un module précis (plugin | panel | webapi), répétable
#   scripts/dev/check.sh --module panel --tests '*BuildingPlan*'
#                                            # tests ciblés d'un seul module (aucun marqueur écrit)
#   scripts/dev/check.sh --full              # les trois modules
#   scripts/dev/check.sh --force             # ignorer les marqueurs « déjà validé »
#   scripts/dev/check.sh --dry-run           # dire ce qui serait lancé, sans rien lancer
#
# Les scripts shell modifiés sont vérifiés par `bash -n` (syntaxe), sans Gradle.
# Variables : RPGQUEST_TEST_MAX_HEAP (défaut 768m sur cette machine).

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
# shellcheck source=scripts/lib/validation.sh
. "$REPO_ROOT/scripts/lib/validation.sh"
cd "$REPO_ROOT"

BASE=""
MODULES=()
TESTS=()
FULL=0
FORCE=0
DRY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --base)    BASE="${2:?--base attend une référence}"; shift ;;
    --module)  MODULES+=("${2:?--module attend plugin|panel|webapi}"); shift ;;
    --tests)   TESTS+=("${2:?--tests attend un motif}"); shift ;;
    --full)    FULL=1 ;;
    --force)   FORCE=1 ;;
    --dry-run) DRY=1 ;;
    -h|--help) awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"; exit 0 ;;
    *) echo "Option inconnue : $1 (voir --help)" >&2; exit 2 ;;
  esac
  shift
done

say() { printf '[check] %s\n' "$*" >&2; }

# ---- Fichiers modifiés -------------------------------------------------------------------------
changed_files() {
  local base=$BASE
  if [ -z "$base" ]; then
    base=$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null || echo HEAD)
  fi
  {
    git diff --name-only "$base"...HEAD 2>/dev/null || git diff --name-only "$base" HEAD
    git diff --name-only HEAD
    git ls-files --others --exclude-standard
  } | sort -u
}

declare -A WANT=()
SCRIPTS=()
if [ "$FULL" -eq 1 ]; then
  WANT=([plugin]=1 [panel]=1 [webapi]=1)
elif [ "${#MODULES[@]}" -gt 0 ]; then
  for m in "${MODULES[@]}"; do
    val::module_task "$m" >/dev/null || { echo "module inconnu : $m" >&2; exit 2; }
    WANT[$m]=1
  done
else
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    case "$f" in *.sh) [ -f "$f" ] && SCRIPTS+=("$f") ;; esac
    m=$(val::module_of_path "$f")
    case "$m" in
      all) WANT=([plugin]=1 [panel]=1 [webapi]=1) ;;
      "") ;;
      *) WANT[$m]=1 ;;
    esac
  done < <(changed_files)
fi

for s in "${SCRIPTS[@]:-}"; do
  [ -n "$s" ] || continue
  bash -n "$s" && say "syntaxe OK : $s"
done

if [ "${#WANT[@]}" -eq 0 ]; then
  say "Aucun module à tester (documentation, scripts ou rien de modifié)."
  exit 0
fi

if [ "${#TESTS[@]}" -gt 0 ] && [ "${#WANT[@]}" -ne 1 ]; then
  echo "--tests exige un seul module (--module …) : Gradle appliquerait le filtre à chaque module" \
       "et échouerait sur ceux qui n'ont aucun test correspondant." >&2
  exit 2
fi

# ---- Ce qui est déjà validé --------------------------------------------------------------------
TASKS=()
RUN=()
for m in plugin panel webapi; do
  [ -n "${WANT[$m]:-}" ] || continue
  if [ "${#TESTS[@]}" -eq 0 ] && [ "$FORCE" -eq 0 ] && val::has_marker "$m"; then
    say "$m : déjà validé pour ce code ($(sed -n 's/^date=//p' "$(val::marker_path "$m")")) — sauté."
    continue
  fi
  TASKS+=("$(val::module_task "$m")")
  RUN+=("$m")
done

if [ "${#TASKS[@]}" -eq 0 ]; then
  say "Rien à relancer."
  exit 0
fi

ARGS=("${TASKS[@]}")
for t in "${TESTS[@]:-}"; do
  [ -n "$t" ] && ARGS+=(--tests "$t")
done

say "Lancement : ./gradlew ${ARGS[*]}"
[ "$DRY" -eq 1 ] && exit 0

export RPGQUEST_TEST_MAX_HEAP="${RPGQUEST_TEST_MAX_HEAP:-768m}"
./gradlew --console=plain "${ARGS[@]}"

# Un filtre --tests ne prouve pas le module entier : pas de marqueur.
if [ "${#TESTS[@]}" -eq 0 ]; then
  for m in "${RUN[@]}"; do
    val::write_marker "$m"
    if val::module_dirty "$m"; then
      say "$m : vert, mais code non commité — aucun marqueur écrit."
    else
      say "$m : vert, marqué validé pour ce code."
    fi
  done
fi
