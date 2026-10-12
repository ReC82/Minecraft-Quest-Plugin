#!/usr/bin/env bash
#
# test-validation.sh — tests de scripts/lib/validation.sh (issue #257), sans réseau ni Gradle.
#
# Un dépôt Git jetable et un faux `gh` suffisent à éprouver ce qui compte : un commit de
# documentation n'invalide rien, un commit de code invalide son seul module, la CI d'un commit de
# documentation est cherchée sur son ancêtre, et une CI absente ou injoignable n'est jamais lue comme
# un vert.

# Les assertions sont des chaînes évaluées par `check` : leur expansion différée est voulue.
# shellcheck disable=SC2016,SC2034
set -euo pipefail

LIB="$(cd "$(dirname "${BASH_SOURCE[0]}")/../lib" && pwd)/validation.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
export RPGQUEST_VALIDATION_DIR="$WORK/markers"

FAILS=0
ok()   { printf '  ok   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { if eval "$2"; then ok "$1"; else fail "$1"; fi; }

# shellcheck source=scripts/lib/validation.sh
. "$LIB"

echo "classification des chemins"
check "docs/ est de la documentation"          'val::is_doc_path docs/RPGQUEST_BIBLE.md'
check "README.md racine aussi"                  'val::is_doc_path README.md'
check "docs/manual-tests n en est PAS"          '! val::is_doc_path docs/manual-tests/quests/a.yml'
check "une ressource .md de module n en est pas" '! val::is_doc_path control-panel/src/main/resources/docs/a.md'
check "panel"   '[ "$(val::module_of_path control-panel/src/X.java)" = panel ]'
check "plugin"  '[ "$(val::module_of_path src/main/java/X.java)" = plugin ]'
check "web-api" '[ "$(val::module_of_path web-api/build.gradle.kts)" = webapi ]'
check "build commun = tous" '[ "$(val::module_of_path build.gradle.kts)" = all ]'
check "scripts = aucun module" '[ -z "$(val::module_of_path scripts/deploy-verygames.sh)" ]'

echo "empreintes et marqueurs"
cd "$WORK" && git init -q repo && cd repo
git config user.email t@t && git config user.name t
mkdir -p src control-panel docs
echo a > src/A.java; echo p > control-panel/P.java; echo d > docs/D.md; echo b > build.gradle.kts
git add -A && git commit -qm init
C1=$(git rev-parse HEAD)
K_PANEL=$(val::module_key panel); K_PLUGIN=$(val::module_key plugin)

val::write_marker panel
check "marqueur écrit et lu" 'val::has_marker panel'
check "pas de marqueur pour le plugin" '! val::has_marker plugin'

echo dd >> docs/D.md && git commit -qam docs
C2=$(git rev-parse HEAD)
check "un commit de doc ne change pas l empreinte" '[ "$(val::module_key panel)" = "$K_PANEL" ]'
check "et le marqueur reste valable" 'val::has_marker panel'

echo pp >> control-panel/P.java
check "code non commité = marqueur sans valeur" '! val::has_marker panel'
git commit -qam panel
check "un commit du panel change son empreinte" '[ "$(val::module_key panel)" != "$K_PANEL" ]'
check "mais pas celle du plugin" '[ "$(val::module_key plugin)" = "$K_PLUGIN" ]'
check "et invalide le marqueur du panel" '! val::has_marker panel'
C3=$(git rev-parse HEAD)

echo "commit dont la CI fait foi"
check "HEAD de code = lui-même" '[ "$(val::ci_commit)" = "$C3" ]'
echo x >> docs/D.md && git commit -qam d1
echo y > README.md && git add README.md && git commit -qm d2
check "après deux commits de doc = le dernier commit de code" '[ "$(val::ci_commit)" = "$C3" ]'
mkdir -p docs/manual-tests && echo q > docs/manual-tests/q.yml && git add -A && git commit -qm mt
check "docs/manual-tests compte comme du code" '[ "$(val::ci_commit)" = "$(git rev-parse HEAD)" ]'
: "$C1" "$C2"

echo "statut CI (faux gh)"
mkdir -p "$WORK/bin"
fake_gh() { printf '#!/usr/bin/env bash\n%s\n' "$1" > "$WORK/bin/gh"; chmod +x "$WORK/bin/gh"; }
PATH="$WORK/bin:$PATH"
fake_gh 'echo "completed success https://ci/1"'
check "succès"     '[ "$(val::ci_status abc)" = "success https://ci/1" ]'
fake_gh 'echo "completed failure https://ci/2"'
check "échec"      '[ "$(val::ci_status abc)" = "failure https://ci/2" ]'
fake_gh 'echo "completed cancelled https://ci/3"'
check "annulé = pas vert" '[ "$(val::ci_status abc | cut -d" " -f1)" = failure ]'
fake_gh 'echo "in_progress - https://ci/4"'
check "en cours"   '[ "$(val::ci_status abc)" = "pending https://ci/4" ]'
fake_gh 'echo "none -"'
check "aucun run"  '[ "$(val::ci_status abc)" = "none -" ]'
fake_gh 'exit 1'
check "gh en erreur = none, jamais vert" '[ "$(val::ci_status abc)" = "none -" ]'

echo
if [ "$FAILS" -gt 0 ]; then
  echo "$FAILS échec(s)"
  exit 1
fi
echo "tous les tests passent"
