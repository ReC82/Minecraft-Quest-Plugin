#!/usr/bin/env bash
#
# install.sh — installe / met à jour le socle d'hébergement du portail public LodyQuests sur AWS
#              (issue #52). Idempotent : ré-exécutable sans casser un état sain.
#
# Ce que fait le script :
#   1. crée l'utilisateur système « lodyquests-web » (sans shell, distinct de « plugadmin ») ;
#   2. crée /opt/lodyquests-portal, /var/lib/lodyquests-portal, /etc/lodyquests-portal ;
#   3. déploie la distribution `:web-api:installDist` vers /opt/lodyquests-portal/app
#      (l'ancienne app est sauvegardée sous /opt/lodyquests-portal/releases/<horodatage>) ;
#   4. crée /etc/lodyquests-portal/portal.env (sans secret) et web-api.properties s'ils manquent ;
#   5. installe le service systemd lodyquests-portal.service (+ enable + démarrage + /health) ;
#   6. installe le vhost nginx dédié lodyquests.lodylands.com (+ `nginx -t` + reload).
#
# Ce que le script NE fait JAMAIS :
#   - toucher un autre vhost nginx ou un autre service (PlugAdmin compris) ;
#   - redémarrer nginx (reload uniquement, et seulement si `nginx -t` passe) ;
#   - écraser portal.env, web-api.properties ou un vhost déjà augmenté par certbot ;
#   - générer ou afficher un secret ;
#   - lancer certbot sans --tls.
#
# Usage :
#   sudo scripts/web-portal/install.sh [--tls] [--dry-run]
#
#   --tls      lance ensuite `certbot --nginx -d lodyquests.lodylands.com` (le DNS public doit
#              déjà pointer vers cette instance ; certbot échoue proprement sinon).
#   --dry-run  affiche les actions sans rien modifier.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
# shellcheck source=scripts/web-portal/paths.sh
. "$HERE/paths.sh"
DIST_SRC="$REPO_ROOT/web-api/build/install/web-api"
TS="$(date +%Y%m%d-%H%M%S)"

DO_TLS=0 ; DRY=0
for a in "$@"; do
  case "$a" in
    --tls) DO_TLS=1 ;;
    --dry-run) DRY=1 ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "option inconnue : $a" >&2; exit 2 ;;
  esac
done

say() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
run() { if [ "$DRY" = 1 ]; then printf '   [dry-run] %s\n' "$*"; else eval "$@"; fi; }

[ "$(id -u)" = 0 ] || { echo "Ce script doit être lancé avec sudo/root." >&2; exit 1; }

# --- 0. pré-requis --------------------------------------------------------------
if [ ! -x "$DIST_SRC/bin/web-api" ]; then
  echo "Distribution absente : $DIST_SRC — construire d'abord :  ./gradlew :web-api:installDist" >&2
  exit 1
fi
command -v nginx >/dev/null || { echo "nginx introuvable." >&2; exit 1; }
command -v java  >/dev/null || { echo "java introuvable." >&2; exit 1; }
if ss -ltn "sport = :$PORTAL_PORT" | grep -q LISTEN && ! systemctl is-active --quiet "$PORTAL_SERVICE"; then
  echo "Le port $PORTAL_PORT est déjà pris par un autre processus : installation annulée." >&2
  exit 1
fi

# --- 1. utilisateur système -------------------------------------------------------
if id "$PORTAL_USER" >/dev/null 2>&1; then
  say "utilisateur $PORTAL_USER : déjà présent"
else
  say "création de l'utilisateur système $PORTAL_USER"
  run "useradd --system --no-create-home --home-dir '$PORTAL_ROOT' --shell /usr/sbin/nologin '$PORTAL_USER'"
fi

# --- 2. arborescence --------------------------------------------------------------
say "arborescence $PORTAL_ROOT, $PORTAL_STATE_DIR, $PORTAL_CONF_DIR"
run "install -d -m 0755 -o root -g root '$PORTAL_ROOT' '$PORTAL_RELEASES_DIR' '$PORTAL_CONF_DIR'"
run "install -d -m 0750 -o '$PORTAL_USER' -g '$PORTAL_USER' '$PORTAL_STATE_DIR'"
# Actualités (#56) : propriété de root, le service ne peut que les lire. Un brouillon d'exemple
# n'est copié que si le dossier est vide (jamais d'écrasement, jamais publié automatiquement).
run "install -d -m 0755 -o root -g root '$PORTAL_NEWS_DIR'"
if [ -z "$(ls -A "$PORTAL_NEWS_DIR" 2>/dev/null)" ]; then
  say "actualités : brouillon d'exemple -> $PORTAL_NEWS_DIR/bienvenue.md (status: draft, invisible)"
  run "install -m 0644 -o root -g root '$HERE/news/bienvenue.md' '$PORTAL_NEWS_DIR/bienvenue.md'"
fi

# --- 3. application ---------------------------------------------------------------
if [ -d "$PORTAL_APP_DIR" ]; then
  say "sauvegarde de l'app actuelle -> $PORTAL_RELEASES_DIR/$TS"
  run "mv '$PORTAL_APP_DIR' '$PORTAL_RELEASES_DIR/$TS'"
fi
say "déploiement de la distribution -> $PORTAL_APP_DIR"
run "install -d -m 0755 '$PORTAL_APP_DIR'"
run "cp -a '$DIST_SRC/.' '$PORTAL_APP_DIR/'"
run "chown -R root:root '$PORTAL_APP_DIR'"
run "chmod -R go-w '$PORTAL_APP_DIR'"

# --- 4. configuration (jamais écrasée) ----------------------------------------------
if [ -f "$PORTAL_ENV_FILE" ]; then
  say "portal.env : déjà présent — inchangé"
else
  say "création de $PORTAL_ENV_FILE (aucun secret)"
  run "install -m 0640 -o root -g '$PORTAL_USER' '$HERE/portal.env.example' '$PORTAL_ENV_FILE'"
fi
if [ -f "$PORTAL_PROPS_FILE" ]; then
  say "web-api.properties : déjà présent — inchangé"
else
  say "installation de $PORTAL_PROPS_FILE"
  run "install -m 0644 -o root -g '$PORTAL_USER' '$HERE/web-api.properties.example' '$PORTAL_PROPS_FILE'"
fi

# --- 5. service systemd ---------------------------------------------------------
[ -f "$PORTAL_UNIT_DST" ] && run "cp -a '$PORTAL_UNIT_DST' '$PORTAL_RELEASES_DIR/$PORTAL_SERVICE.$TS.bak'"
say "service systemd -> $PORTAL_UNIT_DST"
run "install -m 0644 '$HERE/lodyquests-portal.service' '$PORTAL_UNIT_DST'"
run "systemctl daemon-reload"
run "systemctl enable '$PORTAL_SERVICE'"
run "systemctl restart '$PORTAL_SERVICE'"
if [ "$DRY" != 1 ]; then
  say "health local ($PORTAL_HEALTH_URL)"
  portal::wait_health || { echo "!! /health KO — journalctl -u $PORTAL_SERVICE -n 50" >&2; exit 1; }
fi

# --- 6. vhost nginx dédié ---------------------------------------------------------
if [ -f "$PORTAL_NGINX_AVAIL" ]; then
  say "vhost nginx déjà présent — conservé tel quel (peut contenir les ajouts de certbot)"
else
  say "vhost nginx -> $PORTAL_NGINX_AVAIL"
  run "install -m 0644 '$HERE/nginx-lodyquests-portal.conf' '$PORTAL_NGINX_AVAIL'"
fi
[ -e "$PORTAL_NGINX_ENABLED" ] || run "ln -s '$PORTAL_NGINX_AVAIL' '$PORTAL_NGINX_ENABLED'"
if [ "$DRY" != 1 ]; then
  say "nginx -t"
  if nginx -t; then
    say "reload nginx (jamais de restart)"
    systemctl reload nginx
  else
    echo "!! nginx -t a ÉCHOUÉ — reload annulé, symlink du portail retiré pour ne rien casser." >&2
    rm -f "$PORTAL_NGINX_ENABLED"
    exit 1
  fi
fi

# --- 7. TLS (optionnel) -----------------------------------------------------------
if [ "$DO_TLS" = 1 ]; then
  say "certbot --nginx -d $PORTAL_DOMAIN"
  run "certbot --nginx -d '$PORTAL_DOMAIN' --non-interactive --agree-tos --key-type ecdsa --redirect"
  run "nginx -t && systemctl reload nginx"
else
  say "TLS non demandé. Une fois le DNS public effectif :"
  echo "   sudo certbot --nginx -d $PORTAL_DOMAIN --non-interactive --agree-tos --key-type ecdsa --redirect"
fi
say "terminé."
