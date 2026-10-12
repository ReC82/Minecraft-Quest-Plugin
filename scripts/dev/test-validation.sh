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

echo "commit dont la CI fait foi (faux gh : aucun run sauf sur \$RUN_ON)"
mkdir -p "$WORK/bin"
PATH="$WORK/bin:$PATH"
cat > "$WORK/bin/gh" <<'GH'
#!/usr/bin/env bash
case "$*" in *"$RUN_ON"*) echo "completed success https://ci/run" ;; *) echo "none -" ;; esac
GH
chmod +x "$WORK/bin/gh"
export RUN_ON=$C3
check "HEAD de code avec run = lui-même" '[ "$(val::head_ci)" = "$C3 success https://ci/run" ]'
echo x >> docs/D.md && git commit -qam d1
echo y > README.md && git add README.md && git commit -qm d2
check "deux commits de doc sans run = le run du commit de code" '[ "$(val::head_ci)" = "$C3 success https://ci/run" ]'
RUN_ON=$(git rev-parse HEAD); export RUN_ON
check "un run sur le commit de doc poussé fait foi" '[ "$(val::head_ci | cut -d" " -f2)" = success ]'
export RUN_ON=nulle-part
echo z >> src/A.java && git commit -qam code
echo w >> docs/D.md && git commit -qam d3
check "jamais au-delà d un commit de code sans run" '[ "$(val::head_ci | cut -d" " -f1)" = "$(git rev-parse HEAD^)" ] && [ "$(val::head_ci | cut -d" " -f2)" = none ]'
mkdir -p docs/manual-tests && echo q > docs/manual-tests/q.yml && git add -A && git commit -qm mt
check "docs/manual-tests compte comme du code" '[ "$(val::head_ci | cut -d" " -f1)" = "$(git rev-parse HEAD)" ]'
: "$C1" "$C2"
rm -f "$WORK/bin/gh"

echo "statut CI (faux gh)"
fake_gh() { printf '#!/usr/bin/env bash\n%s\n' "$1" > "$WORK/bin/gh"; chmod +x "$WORK/bin/gh"; }
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
