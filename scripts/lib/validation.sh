# shellcheck shell=bash
#
# validation.sh — « ce commit est-il déjà validé ? » (issue #257).
#
# Deux sources, et seulement deux :
#
#   1. la CI GitHub Actions (.github/workflows/ci.yml, workflow « CI »), pour un commit poussé ;
#   2. un marqueur LOCAL écrit par scripts/dev/check.sh après une exécution réussie, indexé par
#      l'empreinte Git du CODE d'un module — pas par le commit. Un commit qui ne change que de la
#      documentation ne change pas cette empreinte : il n'invalide rien.
#
# Aucune de ces fonctions ne lance Gradle. Elles disent ce qui est déjà prouvé ; décider de ce
# qu'il reste à faire appartient à l'appelant.
#
# Modules connus : plugin, panel, webapi.

VAL_WORKFLOW_NAME="CI"
VAL_MARKER_DIR="${RPGQUEST_VALIDATION_DIR:-$HOME/.cache/rpgquest/validated}"

# Fichiers de build communs : les modifier peut changer le résultat de n'importe quel module.
VAL_ROOT_BUILD=(build.gradle.kts settings.gradle.kts gradle gradlew gradle.properties)

val::_repo() { git rev-parse --show-toplevel; }

# Chemins dont dépend chaque module. docs/manual-tests est lu par un test du plugin
# (ManualTestQuestPackTest) : il en fait partie.
val::module_paths() {
  case "$1" in
    plugin) printf '%s\n' src docs/manual-tests "${VAL_ROOT_BUILD[@]}" ;;
    panel)  printf '%s\n' control-panel "${VAL_ROOT_BUILD[@]}" ;;
    webapi) printf '%s\n' web-api "${VAL_ROOT_BUILD[@]}" ;;
    *) echo "module inconnu : $1" >&2; return 2 ;;
  esac
}

val::module_task() {
  case "$1" in
    plugin) echo ":test" ;;
    panel)  echo ":control-panel:test" ;;
    webapi) echo ":web-api:test" ;;
    *) return 2 ;;
  esac
}

# Un chemin de pure documentation : ni la CI ni les tests n'en dépendent.
# MÊME RÈGLE que le filtre « paths » de .github/workflows/ci.yml.
val::is_doc_path() {
  case "$1" in
    docs/manual-tests/*) return 1 ;;
    docs/*|docs-site/*|.ai/*) return 0 ;;
    */*) return 1 ;;
    *.md) return 0 ;;
    *) return 1 ;;
  esac
}

# Le module concerné par un chemin modifié (vide si aucun) ; « all » pour un fichier de build commun.
val::module_of_path() {
  local p=$1
  if val::is_doc_path "$p"; then return 0; fi
  case "$p" in
    control-panel/*) echo panel ;;
    web-api/*) echo webapi ;;
    src/*|docs/manual-tests/*) echo plugin ;;
    build.gradle.kts|settings.gradle.kts|gradle.properties|gradlew|gradlew.bat|gradle/*) echo all ;;
    *) : ;;  # scripts/, .github/, fichiers divers : aucun test Gradle n'en dépend
  esac
}

# Empreinte du code d'un module dans HEAD : arbres Git des chemins dont il dépend.
val::module_key() {
  local module=$1 p acc=""
  while IFS= read -r p; do
    acc+="$p=$(git rev-parse --verify --quiet "HEAD:$p" 2>/dev/null || echo absent);"
  done < <(val::module_paths "$module")
  printf '%s' "$acc" | sha256sum | cut -c1-40
}

# Le code du module a-t-il des modifications non commitées ? (Le marqueur ne vaut alors rien.)
val::module_dirty() {
  local -a paths
  mapfile -t paths < <(val::module_paths "$1")
  [ -n "$(git status --porcelain -- "${paths[@]}" 2>/dev/null)" ]
}

val::marker_path() { echo "$VAL_MARKER_DIR/$1/$(val::module_key "$1")"; }

val::has_marker() {
  ! val::module_dirty "$1" && [ -f "$(val::marker_path "$1")" ]
}

val::write_marker() {
  local module=$1 path
  val::module_dirty "$module" && return 0  # jamais de marqueur pour du code non commité
  path=$(val::marker_path "$module")
  mkdir -p "$(dirname "$path")"
  printf 'commit=%s\ndate=%s\ntask=%s\n' "$(git rev-parse HEAD)" "$(date -Iseconds)" \
    "$(val::module_task "$module")" > "$path"
}

# Le commit dont la CI fait foi pour HEAD, et son statut : « <sha> <statut> <url> ».
#
# La CI ne tourne que sur le commit POUSSÉ en tête, jamais sur chaque commit intermédiaire, et pas du
# tout sur un push de pure documentation. On part donc de HEAD et on remonte (premier parent) tant
# que le commit examiné n'a aucun run ET n'est que de la documentation : franchir un commit de code
# reviendrait à prêter à ce code le verdict d'un autre.
val::_docs_only() {
  local f
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    val::is_doc_path "$f" || return 1
  done < <(git diff-tree --no-commit-id --name-only -r "$1" 2>/dev/null)
  return 0
}

val::head_ci() {
  local c n=0 st
  c=$(git rev-parse HEAD)
  while :; do
    st=$(val::ci_status "$c")
    if [ "${st%% *}" != none ] || ! val::_docs_only "$c" || [ "$n" -ge 50 ] \
        || ! git rev-parse --verify --quiet "$c^" >/dev/null; then
      echo "$c $st"
      return 0
    fi
    c=$(git rev-parse "$c^"); n=$((n + 1))
  done
}

# Statut CI d'un commit : « success|failure|pending|none <url> ». Ne lève jamais : sans gh, sans
# réseau ou sans run, la réponse est « none » — c'est-à-dire « pas de preuve », jamais « vert ».
#
# On lit le RUN du workflow « CI » (visible dès son démarrage), et non le check « CI OK », qui
# n'existe qu'une fois ses dépendances terminées. Le run n'est « success » que si TOUS ses jobs le
# sont, « CI OK » compris ; annulé, ignoré ou en échec, ce n'est pas un vert.
val::ci_status() {
  local sha=$1 out
  command -v gh >/dev/null 2>&1 || { echo "none -"; return 0; }
  out=$(gh api "repos/{owner}/{repo}/actions/runs?head_sha=$sha&per_page=20" \
        --jq "[.workflow_runs[] | select(.name == \"$VAL_WORKFLOW_NAME\")] | sort_by(.created_at) | last
              | if . == null then \"none -\" else \"\\(.status) \\(.conclusion // \"-\") \\(.html_url)\" end" \
        2>/dev/null) || { echo "none -"; return 0; }
  case "$out" in
    "none -"|"") echo "none -" ;;
    "completed success "*) echo "success ${out##* }" ;;
    "completed "*) echo "failure ${out##* }" ;;
    *) echo "pending ${out##* }" ;;
  esac
}

# Attend la fin de la CI d'un commit, au plus $2 secondes. Affiche le statut final.
val::wait_ci() {
  local sha=$1 budget=${2:-1800} waited=0 st
  while :; do
    st=$(val::ci_status "$sha")
    case "$st" in
      pending*) ;;
      *) echo "$st"; return 0 ;;
    esac
    [ "$waited" -ge "$budget" ] && { echo "$st"; return 0; }
    sleep 30; waited=$((waited + 30))
  done
}
