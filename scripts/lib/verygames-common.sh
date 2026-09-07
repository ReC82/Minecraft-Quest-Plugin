# shellcheck shell=bash
#
# Fonctions partagées par scripts/deploy-verygames.sh et
# scripts/rollback-verygames.sh (issue #10).
#
# Ce fichier est *sourcé*, jamais exécuté : il ne fixe pas `set -euo pipefail`
# lui-même (c'est le rôle des scripts appelants) et n'a pas de shebang.
#
# SÉCURITÉ
#   - Le mot de passe FTP n'est JAMAIS passé sur une ligne de commande (donc
#     jamais visible dans `ps`) : il est écrit dans un fichier de config curl
#     temporaire (`--config`), en mode 600, effacé après chaque appel ET par un
#     trap EXIT (`vg::install_cleanup`, à appeler une fois par le script).
#   - Aucune fonction n'affiche `VERYGAMES_FTP_PASS` ; `vg::redact` masque toute
#     occurrence dans les messages.
#   - Aucun secret n'est écrit dans un fichier versionné ni dans un log.

# ----------------------------------------------------------------------------
# Journalisation
# ----------------------------------------------------------------------------

VG_RED=''; VG_YELLOW=''; VG_GREEN=''; VG_BOLD=''; VG_RESET=''
if [ -t 2 ]; then
  VG_RED=$'\033[31m'; VG_YELLOW=$'\033[33m'; VG_GREEN=$'\033[32m'
  VG_BOLD=$'\033[1m'; VG_RESET=$'\033[0m'
fi

vg::log()  { printf '%s[deploy]%s %s\n'  "$VG_GREEN"  "$VG_RESET" "$*" >&2; }
vg::step() { printf '\n%s==> %s%s\n'      "$VG_BOLD"   "$*" "$VG_RESET" >&2; }
vg::warn() { printf '%s[warn]%s %s\n'     "$VG_YELLOW" "$VG_RESET" "$*" >&2; }
vg::err()  { printf '%s[error]%s %s\n'    "$VG_RED"    "$VG_RESET" "$*" >&2; }
vg::die()  { vg::err "$*"; exit 1; }

# Masque le mot de passe FTP (et l'éventuel ftp://user:pass@) dans une chaîne.
vg::redact() {
  local s=$*
  if [ -n "${VERYGAMES_FTP_PASS:-}" ]; then
    s=${s//"$VERYGAMES_FTP_PASS"/'***'}
  fi
  printf '%s' "$s"
}

# ----------------------------------------------------------------------------
# Chargement de la configuration d'accès (hors Git)
# ----------------------------------------------------------------------------
#
# Précédence (du plus fort au plus faible) :
#   1. variables déjà présentes dans l'environnement du shell appelant ;
#   2. fichier pointé par $RPGQUEST_VERYGAMES_ENV ;
#   3. ~/.config/rpgquest/verygames.env  (défaut recommandé).
#
# Le fichier est au format `CLE=valeur` (sourçable) — voir
# scripts/verygames.env.example.

VG_DEFAULT_ENV_FILE="${XDG_CONFIG_HOME:-$HOME/.config}/rpgquest/verygames.env"

vg::config_file_path() {
  if [ -n "${RPGQUEST_VERYGAMES_ENV:-}" ]; then
    printf '%s' "$RPGQUEST_VERYGAMES_ENV"
  else
    printf '%s' "$VG_DEFAULT_ENV_FILE"
  fi
}

# Charge le fichier de config s'il existe, sans écraser une variable déjà
# définie dans l'environnement. Refuse un fichier au mode trop permissif.
vg::load_config() {
  local file
  file=$(vg::config_file_path)

  if [ -f "$file" ]; then
    local perm
    perm=$(stat -c '%a' "$file" 2>/dev/null || stat -f '%Lp' "$file" 2>/dev/null || echo '')
    case "$perm" in
      600|400) : ;;
      '') vg::warn "Impossible de vérifier les permissions de $file." ;;
      *)  vg::die "Le fichier d'identifiants $file est en mode $perm : trop permissif. Corriger avec: chmod 600 $file" ;;
    esac

    # Les variables déjà présentes dans l'environnement du shell appelant
    # gagnent sur le fichier : on les sauvegarde (via printf -v, sûr même si la
    # valeur contient espaces/guillemets/$), on source le fichier, on restaure.
    local k save
    local vg_keys=(VERYGAMES_FTP_HOST VERYGAMES_FTP_PORT VERYGAMES_FTP_USER
                   VERYGAMES_FTP_PASS VERYGAMES_FTP_REMOTE_DIR VERYGAMES_PLUGIN_JAR_NAME
                   VERYGAMES_FTP_TLS VERYGAMES_FTP_CA_EXTRA VERYGAMES_FTP_CACERT
                   VERYGAMES_BACKUP_DIR VERYGAMES_FTP_EXTRA_CURL_ARGS
                   VERYGAMES_CURL_CONNECT_TIMEOUT VERYGAMES_CURL_MAX_TIME VERYGAMES_BACKUP_KEEP)
    for k in "${vg_keys[@]}"; do
      if [ -n "${!k+x}" ]; then
        printf -v "VG_ENVSAVE_$k" '%s' "${!k}"
        printf -v "VG_ENVHAS_$k" '%s' 1
      else
        unset "VG_ENVHAS_$k" 2>/dev/null || true
      fi
    done

    set -a
    # shellcheck disable=SC1090  # chemin dynamique voulu (~/.config/rpgquest/verygames.env)
    . "$file"
    set +a

    for k in "${vg_keys[@]}"; do
      save="VG_ENVHAS_$k"
      if [ "${!save:-}" = "1" ]; then
        local src="VG_ENVSAVE_$k"
        printf -v "$k" '%s' "${!src}"
      fi
      unset "VG_ENVSAVE_$k" "VG_ENVHAS_$k" 2>/dev/null || true
    done

    VG_CONFIG_SOURCE="$file"
  else
    VG_CONFIG_SOURCE="(aucun fichier — variables d'environnement uniquement)"
  fi

  # Valeurs par défaut.
  : "${VERYGAMES_FTP_PORT:=21}"
  : "${VERYGAMES_FTP_USER:=awsplugin}"
  : "${VERYGAMES_FTP_REMOTE_DIR:=/}"
  : "${VERYGAMES_PLUGIN_JAR_NAME:=rpgquest-0.1.0-SNAPSHOT.jar}"
  # VeryGames impose AUTH TLS sur le port 21 : une connexion en clair est
  # refusée (530). « require » est donc le défaut. « auto » est un alias de
  # « require » (jamais l'option opportuniste --ssl, que curl signale comme
  # non sûre). « none » n'est utile que pour un hôte FTP réellement en clair.
  : "${VERYGAMES_FTP_TLS:=require}"
  # Certificats supplémentaires (chaîne incomplète servie par VeryGames — voir
  # scripts/verygames-fetch-ca.sh) : fichier PEM d'intermédiaires, fusionné au
  # magasin système à l'exécution.
  : "${VERYGAMES_FTP_CA_EXTRA:=}"
  # Magasin CA complet, en remplacement total du magasin système (usage avancé).
  : "${VERYGAMES_FTP_CACERT:=}"
  : "${VERYGAMES_BACKUP_DIR:=${XDG_DATA_HOME:-$HOME/.local/share}/rpgquest/verygames-backups}"
  : "${VERYGAMES_CURL_CONNECT_TIMEOUT:=20}"
  : "${VERYGAMES_CURL_MAX_TIME:=600}"
  : "${VERYGAMES_FTP_EXTRA_CURL_ARGS:=}"
  : "${VERYGAMES_BACKUP_KEEP:=}"

  VERYGAMES_FTP_CA_EXTRA=${VERYGAMES_FTP_CA_EXTRA/#\~/$HOME}
  VERYGAMES_FTP_CACERT=${VERYGAMES_FTP_CACERT/#\~/$HOME}

  # Normalise le dossier distant : garantit un unique slash de tête, pas de
  # slash de fin (sauf racine).
  case "$VERYGAMES_FTP_REMOTE_DIR" in
    /*) : ;;
    *)  VERYGAMES_FTP_REMOTE_DIR="/$VERYGAMES_FTP_REMOTE_DIR" ;;
  esac
  if [ "$VERYGAMES_FTP_REMOTE_DIR" != "/" ]; then
    VERYGAMES_FTP_REMOTE_DIR=${VERYGAMES_FTP_REMOTE_DIR%/}
  fi

  VERYGAMES_BACKUP_DIR=${VERYGAMES_BACKUP_DIR/#\~/$HOME}
}

# vg::validate_config [--require-connection]
#   Toujours : VERYGAMES_PLUGIN_JAR_NAME non vide, port numérique 1..65535.
#   Avec --require-connection : HOST + USER + PASS non vides, HOST sans schéma
#   ni espace.
vg::validate_config() {
  local require_conn=0
  [ "${1:-}" = "--require-connection" ] && require_conn=1

  local errors=0

  if [ -z "${VERYGAMES_PLUGIN_JAR_NAME:-}" ]; then
    vg::err "VERYGAMES_PLUGIN_JAR_NAME est vide."; errors=$((errors + 1))
  fi
  case "$VERYGAMES_PLUGIN_JAR_NAME" in
    */*) vg::err "VERYGAMES_PLUGIN_JAR_NAME doit être un nom de fichier, pas un chemin : $VERYGAMES_PLUGIN_JAR_NAME"; errors=$((errors + 1)) ;;
  esac

  case "$VERYGAMES_FTP_PORT" in
    ''|*[!0-9]*) vg::err "VERYGAMES_FTP_PORT n'est pas numérique : '$VERYGAMES_FTP_PORT'"; errors=$((errors + 1)) ;;
    *) if [ "$VERYGAMES_FTP_PORT" -lt 1 ] || [ "$VERYGAMES_FTP_PORT" -gt 65535 ]; then
         vg::err "VERYGAMES_FTP_PORT hors plage 1..65535 : $VERYGAMES_FTP_PORT"; errors=$((errors + 1))
       fi ;;
  esac

  case "$VERYGAMES_FTP_TLS" in
    auto|require|none) : ;;
    *) vg::err "VERYGAMES_FTP_TLS doit valoir auto | require | none (trouvé : '$VERYGAMES_FTP_TLS')"; errors=$((errors + 1)) ;;
  esac

  if [ -n "$VERYGAMES_FTP_CA_EXTRA" ] && [ ! -r "$VERYGAMES_FTP_CA_EXTRA" ]; then
    vg::err "VERYGAMES_FTP_CA_EXTRA introuvable ou illisible : $VERYGAMES_FTP_CA_EXTRA"; errors=$((errors + 1))
  fi
  if [ -n "$VERYGAMES_FTP_CACERT" ] && [ ! -r "$VERYGAMES_FTP_CACERT" ]; then
    vg::err "VERYGAMES_FTP_CACERT introuvable ou illisible : $VERYGAMES_FTP_CACERT"; errors=$((errors + 1))
  fi

  if [ "$require_conn" -eq 1 ]; then
    if [ -z "${VERYGAMES_FTP_HOST:-}" ]; then
      vg::err "VERYGAMES_FTP_HOST est vide — indispensable pour se connecter."; errors=$((errors + 1))
    else
      case "$VERYGAMES_FTP_HOST" in
        *://*) vg::err "VERYGAMES_FTP_HOST ne doit pas contenir de schéma (pas de ftp://) : $VERYGAMES_FTP_HOST"; errors=$((errors + 1)) ;;
        *[[:space:]]*) vg::err "VERYGAMES_FTP_HOST contient une espace."; errors=$((errors + 1)) ;;
        */*) vg::err "VERYGAMES_FTP_HOST ne doit pas contenir de '/'."; errors=$((errors + 1)) ;;
      esac
    fi
    [ -z "${VERYGAMES_FTP_USER:-}" ] && { vg::err "VERYGAMES_FTP_USER est vide."; errors=$((errors + 1)); }
    [ -z "${VERYGAMES_FTP_PASS:-}" ] && { vg::err "VERYGAMES_FTP_PASS est vide."; errors=$((errors + 1)); }
  fi

  [ "$errors" -eq 0 ] || return 1
}

# Vrai si HOST/USER/PASS sont tous renseignés (assez pour tenter une connexion).
vg::has_connection_config() {
  [ -n "${VERYGAMES_FTP_HOST:-}" ] && [ -n "${VERYGAMES_FTP_USER:-}" ] && [ -n "${VERYGAMES_FTP_PASS:-}" ]
}

vg::config_summary() {
  local host="${VERYGAMES_FTP_HOST:-${VG_YELLOW}(non défini)${VG_RESET}}"
  local pass_state="${VG_YELLOW}(non défini)${VG_RESET}"
  [ -n "${VERYGAMES_FTP_PASS:-}" ] && pass_state="${VG_GREEN}(défini, masqué)${VG_RESET}"
  cat >&2 <<EOF
  source config     : ${VG_CONFIG_SOURCE}
  hôte FTP          : ${host}
  port FTP          : ${VERYGAMES_FTP_PORT}
  utilisateur FTP   : ${VERYGAMES_FTP_USER}
  mot de passe FTP  : ${pass_state}
  TLS (AUTH TLS)    : ${VERYGAMES_FTP_TLS}$( [ "$VERYGAMES_FTP_TLS" = auto ] && printf ' (= require)')
  CA intermédiaires : ${VERYGAMES_FTP_CA_EXTRA:-(magasin système seul)}
  CA (remplacement) : ${VERYGAMES_FTP_CACERT:-(non)}
  dossier distant   : ${VERYGAMES_FTP_REMOTE_DIR}
  nom du JAR        : ${VERYGAMES_PLUGIN_JAR_NAME}
  cible distante    : $(vg::redact "$(vg::remote_jar_url)")
  dossier backups   : ${VERYGAMES_BACKUP_DIR}
EOF
}

# ----------------------------------------------------------------------------
# URLs et appels curl
# ----------------------------------------------------------------------------

vg::ftp_base_url() {
  printf 'ftp://%s:%s' "${VERYGAMES_FTP_HOST:-HOST}" "${VERYGAMES_FTP_PORT:-21}"
}

# URL du dossier distant (toujours terminée par un slash).
vg::remote_dir_url() {
  if [ "$VERYGAMES_FTP_REMOTE_DIR" = "/" ]; then
    printf '%s/' "$(vg::ftp_base_url)"
  else
    printf '%s%s/' "$(vg::ftp_base_url)" "$VERYGAMES_FTP_REMOTE_DIR"
  fi
}

vg::remote_jar_url() {
  printf '%s%s' "$(vg::remote_dir_url)" "$VERYGAMES_PLUGIN_JAR_NAME"
}

vg::remote_url_for() {
  printf '%s%s' "$(vg::remote_dir_url)" "$1"
}

# Écrit sur stdout le contenu d'un fichier de config curl (--config) : identité
# et options TLS. Ne jamais rediriger vers un fichier persistant.
# Le couple user:pass est échappé (\ et ") pour la syntaxe des valeurs entre
# guillemets de curl : un mot de passe contenant " ou \ reste correct.
vg::_curl_config() {
  local u="${VERYGAMES_FTP_USER}" p="${VERYGAMES_FTP_PASS}"
  u=${u//\\/\\\\}; u=${u//\"/\\\"}
  p=${p//\\/\\\\}; p=${p//\"/\\\"}
  printf 'user = "%s:%s"\n' "$u" "$p"
  case "$VERYGAMES_FTP_TLS" in
    require|auto) printf 'ssl-reqd\n' ;;   # AUTH TLS obligatoire, cert vérifié
    none)         : ;;                     # FTP en clair (jamais accepté par VeryGames)
  esac
}

# Cherche le magasin CA système (fichier PEM concaténé). Vide si introuvable.
vg::_system_ca_file() {
  local f
  for f in "${CURL_CA_BUNDLE:-}" "${SSL_CERT_FILE:-}" \
           /etc/ssl/certs/ca-certificates.crt \
           /etc/pki/tls/certs/ca-bundle.crt \
           /etc/ssl/cert.pem; do
    [ -n "$f" ] && [ -r "$f" ] && { printf '%s' "$f"; return 0; }
  done
  return 1
}

# Bundle CA temporaire (magasin système + VERYGAMES_FTP_CA_EXTRA) et tableau
# d'arguments curl associés — remplis par vg::_build_ca_args dans le shell
# courant (pas de sous-shell : le chemin temporaire doit rester visible du trap).
VG_CA_BUNDLE=""
VG_CA_ARGS=()

# Prépare VG_CA_ARGS (arguments curl de vérification du certificat), sans jamais
# désactiver la vérification (--insecure interdit) :
#  - VERYGAMES_FTP_CACERT   remplace totalement le magasin (usage avancé) ;
#  - VERYGAMES_FTP_CA_EXTRA ajoute des intermédiaires au magasin système
#    (chaîne incomplète servie par VeryGames — voir scripts/verygames-fetch-ca.sh).
vg::_build_ca_args() {
  VG_CA_ARGS=()
  if [ -n "$VERYGAMES_FTP_CACERT" ]; then
    VG_CA_ARGS=(--cacert "$VERYGAMES_FTP_CACERT")
    return 0
  fi
  [ -n "$VERYGAMES_FTP_CA_EXTRA" ] || return 0

  local sysca
  sysca=$(vg::_system_ca_file || true)
  VG_CA_BUNDLE=$(mktemp "${TMPDIR:-/tmp}/vg-ca.XXXXXX") || return 1
  chmod 600 "$VG_CA_BUNDLE"
  if [ -n "$sysca" ]; then
    cat "$sysca" "$VERYGAMES_FTP_CA_EXTRA" >"$VG_CA_BUNDLE"
    VG_CA_ARGS=(--cacert "$VG_CA_BUNDLE")
  else
    cat "$VERYGAMES_FTP_CA_EXTRA" >"$VG_CA_BUNDLE"
    VG_CA_ARGS=(--capath /etc/ssl/certs --cacert "$VG_CA_BUNDLE")
  fi
}

# Fichier de config curl courant (chemin), pour le nettoyage par trap.
VG_CURL_CFG=""

# À appeler une fois par le script : garantit l'effacement des fichiers
# temporaires (config curl, bundle CA) même en cas d'interruption.
vg::install_cleanup() {
  trap 'rm -f "$VG_CURL_CFG" "$VG_CA_BUNDLE" 2>/dev/null || true' EXIT INT TERM
}

# vg::curl <args...> : curl durci, identité fournie via un --config temporaire
# en mode 600 (mot de passe jamais sur la ligne de commande).
vg::curl() {
  local cfg rc
  cfg=$(mktemp "${TMPDIR:-/tmp}/vg-curl.XXXXXX") || return 1
  VG_CURL_CFG="$cfg"
  chmod 600 "$cfg"
  vg::_curl_config >"$cfg"

  # Découpage sur les espaces voulu ici (IFS du script appelant = \n\t) :
  # VERYGAMES_FTP_EXTRA_CURL_ARGS est une liste d'arguments séparés par des espaces.
  local extra=()
  if [ -n "$VERYGAMES_FTP_EXTRA_CURL_ARGS" ]; then
    local IFS=$' \t\n'
    # shellcheck disable=SC2206
    extra=($VERYGAMES_FTP_EXTRA_CURL_ARGS)
  fi

  vg::_build_ca_args || { rm -f "$cfg"; return 1; }

  set +e
  curl --disable --config "$cfg" \
       --fail --show-error --silent \
       --connect-timeout "$VERYGAMES_CURL_CONNECT_TIMEOUT" \
       --max-time "$VERYGAMES_CURL_MAX_TIME" \
       --ftp-pasv \
       "${VG_CA_ARGS[@]}" \
       "${extra[@]}" \
       "$@"
  rc=$?
  set -e

  rm -f "$cfg"; VG_CURL_CFG=""
  [ -n "$VG_CA_BUNDLE" ] && { rm -f "$VG_CA_BUNDLE"; VG_CA_BUNDLE=""; }
  return $rc
}

# ----------------------------------------------------------------------------
# Opérations FTP de haut niveau
# ----------------------------------------------------------------------------

# Vrai si le fichier distant <name> (relatif au dossier distant) existe.
vg::remote_file_exists() {
  vg::curl --head -o /dev/null "$(vg::remote_url_for "$1")" 2>/dev/null
}

# Taille en octets du fichier distant <name>, ou "" si indéterminée.
vg::remote_file_size() {
  vg::curl --head "$(vg::remote_url_for "$1")" 2>/dev/null \
    | awk 'tolower($1) ~ /^content-length:/ { gsub("\r",""); print $2 }' \
    | tail -n1
}

# Liste (noms seuls) du dossier distant.
vg::remote_list() {
  vg::curl --list-only "$(vg::remote_dir_url)"
}

vg::remote_download() { # <remote-name> <local-path>
  vg::curl -o "$2" "$(vg::remote_url_for "$1")"
}

vg::remote_upload() { # <local-path> <remote-name>
  vg::curl --upload-file "$1" "$(vg::remote_url_for "$2")"
}

vg::remote_rename() { # <from-name> <to-name>
  vg::curl -o /dev/null \
    --quote "RNFR $1" --quote "RNTO $2" \
    "$(vg::remote_dir_url)"
}

vg::remote_delete() { # <remote-name>
  vg::curl -o /dev/null --quote "DELE $1" "$(vg::remote_dir_url)"
}

# Téléverse <local> vers <remote-rel> de façon atomique : téléversement sous un
# nom .part-<stamp>, contrôle de taille, puis RNFR/RNTO sur le nom final (repli
# sur téléversement direct si le serveur refuse le renommage). N'écrit jamais
# ailleurs que sur <remote-rel>.
vg::remote_put_atomic() { # <local> <remote-rel> <stamp> <expected-size>
  local lp=$1 rp=$2 tmp="${2}.part-${3}" want=$4 got
  vg::remote_upload "$lp" "$tmp" || { vg::remote_delete "$tmp" 2>/dev/null || true; return 1; }
  got=$(vg::remote_file_size "$tmp" || true)
  if [ -n "$got" ] && [ -n "$want" ] && [ "$got" != "$want" ]; then
    vg::remote_delete "$tmp" 2>/dev/null || true
    vg::err "Taille distante ($got) != locale ($want) pour '$rp' — fichier partiel supprimé."
    return 1
  fi
  if ! vg::remote_rename "$tmp" "$rp"; then
    vg::warn "RNFR/RNTO refusé pour '$rp' — repli : téléversement direct."
    vg::remote_upload "$lp" "$rp" || return 1
    vg::remote_delete "$tmp" 2>/dev/null || true
  fi
}

# Garde-fou pour --also : refuse (code 0 = INTERDIT) tout chemin distant qui
# n'est pas strictement sous RPGQuest/, toute traversée, et les fichiers
# sensibles quel que soit l'emplacement. Ne permet JAMAIS de toucher data.db,
# config.yml, messages.yml, spawn.yml, Citizens/, un autre plugin ou un monde.
vg::_is_forbidden_remote() {
  local p=$1 base
  case "$p" in
    ''|/*|~*) return 0 ;;
    *..*)     return 0 ;;
  esac
  [ "${p#RPGQuest/}" != "$p" ] || return 0     # DOIT commencer par RPGQuest/
  [ "$p" != "RPGQuest/" ] || return 0
  case "$p" in
    RPGQuest/Citizens/*) return 0 ;;
  esac
  base=${p##*/}
  case "$base" in
    data.db|config.yml|messages.yml|spawn.yml) return 0 ;;
  esac
  return 1
}

# Test de connexion : liste le dossier distant, renvoie 0/!=0.
vg::connectivity_check() {
  vg::remote_list >/dev/null
}

# Message actionnable pour un code de sortie curl (contexte FTP/FTPS).
vg::explain_curl_rc() {
  case "$1" in
    0)  printf 'OK' ;;
    6)  printf "hôte introuvable (DNS) : VERYGAMES_FTP_HOST='%s'" "${VERYGAMES_FTP_HOST:-}" ;;
    7)  printf 'connexion TCP refusée/impossible (port %s, pare-feu ?)' "${VERYGAMES_FTP_PORT:-}" ;;
    28) printf 'délai dépassé (VERYGAMES_CURL_CONNECT_TIMEOUT / réseau)' ;;
    35|58|59|77) printf 'échec de négociation TLS (AUTH TLS) — voir VERYGAMES_FTP_TLS' ;;
    60) printf 'certificat serveur NON vérifiable : chaîne incomplète côté VeryGames. Lancer scripts/verygames-fetch-ca.sh puis définir VERYGAMES_FTP_CA_EXTRA. Ne JAMAIS utiliser --insecure.' ;;
    67) printf "identifiants FTP refusés (530) pour l'utilisateur '%s' : login ou mot de passe erroné. Le login FTP VeryGames est celui EXACT du panel (onglet FTP) — souvent au format <slot>.<sous-compte>, ex. \"si-XXXXX.awsplugin\", pas juste \"awsplugin\"." "${VERYGAMES_FTP_USER:-}" ;;
    9)  printf 'accès au dossier distant refusé (droits FTP / VERYGAMES_FTP_REMOTE_DIR)' ;;
    78) printf 'fichier distant introuvable' ;;
    *)  printf 'échec curl (code %s)' "$1" ;;
  esac
}

# ----------------------------------------------------------------------------
# Outils divers
# ----------------------------------------------------------------------------

vg::sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    printf '(sha256 indisponible)'
  fi
}

vg::utc_stamp() { date -u +%Y%m%dT%H%M%SZ; }

# Liste triée des fichiers de backup ($1 = motif glob, défaut rpgquest-*.jar).
# Le nom porte l'horodatage UTC : tri lexical == tri chronologique. Rien si
# le dossier est absent ou vide.
vg::backup_files() {
  local dir="$VERYGAMES_BACKUP_DIR" pattern="${1:-rpgquest-*.jar}"
  local files=() f
  [ -d "$dir" ] || return 0
  shopt -s nullglob
  for f in "$dir"/$pattern; do files+=("$f"); done
  shopt -u nullglob
  [ "${#files[@]}" -gt 0 ] || return 0
  printf '%s\n' "${files[@]}" | sort
}

vg::operator_tag() { printf '%s@%s' "$(id -un 2>/dev/null || echo '?')" "$(hostname 2>/dev/null || echo '?')"; }

# Vrai si le fichier ressemble à un JAR / ZIP (magie "PK").
vg::looks_like_jar() {
  [ -s "$1" ] || return 1
  local sig
  sig=$(head -c 2 "$1" 2>/dev/null || true)
  [ "$sig" = "PK" ]
}

vg::require_curl() {
  command -v curl >/dev/null 2>&1 || vg::die "curl est requis mais introuvable. Installer curl puis relancer."
}

# Racine du dépôt (le dossier parent de scripts/).
vg::repo_root() {
  cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd
}
