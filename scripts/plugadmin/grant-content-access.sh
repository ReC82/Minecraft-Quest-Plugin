#!/usr/bin/env bash
#
# grant-content-access.sh — accorde au service PlugAdmin le droit d'écrire dans les dossiers
#                           de contenu éditables du checkout source (issue #162).
#
# Pourquoi ce script existe
# -------------------------
# L'éditeur du Control Panel (#46 quêtes/stories, #145 dialogues) écrit dans
# <PLUGADMIN_CONTENT_DIR>/{quests,stories,dialogues}/*.yml. Pour que ça marche, DEUX
# conditions doivent être réunies — l'oubli de l'une des deux produit exactement le
# bandeau « Éditeur en lecture seule » :
#
#   1. ACL POSIX : l'utilisateur système « plugadmin » doit avoir rwx sur le dossier
#      (le dossier appartient à « ubuntu », pas à « plugadmin » — jamais de chown, jamais
#      de chmod 777, jamais de service lancé en root).
#   2. Bac à sable systemd : l'unité utilise ProtectSystem=strict, qui rend TOUT le système
#      de fichiers en lecture seule pour le service, ACL correcte ou non. Chaque dossier
#      autorisé doit donc être listé en ReadWritePaths= dans un drop-in.
#
# L'incident #162 venait précisément d'un état partiel : quests/ et stories/ avaient les deux,
# dialogues/ n'avait ni l'ACL ni le ReadWritePaths — d'où une création de dialogue impossible
# alors que les quêtes fonctionnaient.
#
# Idempotent : ré-exécutable sans effet de bord sur un état déjà sain.
#
# Usage :
#   sudo scripts/plugadmin/grant-content-access.sh [--content-dir DIR] [--no-restart] [--dry-run]
#
#   --content-dir DIR  racine du contenu (défaut : PLUGADMIN_CONTENT_DIR lu dans
#                      /etc/plugadmin/plugadmin.env).
#   --no-restart       n'applique pas `systemctl restart plugadmin` à la fin
#                      (le drop-in systemd ne prend effet qu'au redémarrage du service).
#   --dry-run          affiche ce qui serait fait, ne modifie rien.
#
# Ce script ne touche JAMAIS : le contenu des fichiers YAML, data.db, les mondes, Citizens,
# un autre service, un autre vhost, ni les droits d'un dossier hors de la liste ci-dessous.
#
set -euo pipefail

SERVICE_USER="plugadmin"
SERVICE_NAME="plugadmin"
ENV_FILE="/etc/plugadmin/plugadmin.env"
DROPIN_DIR="/etc/systemd/system/plugadmin.service.d"
DROPIN_FILE="$DROPIN_DIR/10-content-workspace.conf"

# Doit rester aligné sur ContentWorkspace.KINDS (control-panel) — rien d'autre n'est éditable.
KINDS=(quests stories dialogues)

CONTENT_DIR=""
DO_RESTART=1
DRY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --content-dir) CONTENT_DIR="${2:-}" ; shift 2 ;;
    --no-restart)  DO_RESTART=0 ; shift ;;
    --dry-run)     DRY=1 ; shift ;;
    -h|--help)     sed -n '2,36p' "$0" ; exit 0 ;;
    *) echo "Option inconnue : $1" >&2 ; exit 2 ;;
  esac
done

say()  { printf '%s\n' "$*"; }
step() { printf '\n==> %s\n' "$*"; }
run()  {
  if [ "$DRY" = 1 ]; then
    printf '   [dry-run] %s\n' "$*"
  else
    printf '   %s\n' "$*"
    "$@"
  fi
}

if [ "$DRY" != 1 ] && [ "$(id -u)" != 0 ]; then
  echo "Ce script doit être lancé avec sudo (ACL + systemd)." >&2
  exit 2
fi

step "1/5 — Racine du contenu"
if [ -z "$CONTENT_DIR" ]; then
  if [ -r "$ENV_FILE" ]; then
    CONTENT_DIR="$(sed -n 's/^PLUGADMIN_CONTENT_DIR=//p' "$ENV_FILE" | tail -n1)"
  fi
fi
if [ -z "$CONTENT_DIR" ]; then
  echo "PLUGADMIN_CONTENT_DIR introuvable dans $ENV_FILE et --content-dir non fourni." >&2
  echo "L'éditeur de contenu n'est alors pas configuré du tout — rien à accorder." >&2
  exit 2
fi
if [ ! -d "$CONTENT_DIR" ]; then
  echo "Racine de contenu inexistante : $CONTENT_DIR" >&2
  exit 2
fi
say "   PLUGADMIN_CONTENT_DIR = $CONTENT_DIR"

step "2/5 — Utilisateur de service"
if ! id "$SERVICE_USER" >/dev/null 2>&1; then
  echo "Utilisateur système « $SERVICE_USER » absent — lancer d'abord scripts/plugadmin/install.sh." >&2
  exit 2
fi
say "   $SERVICE_USER présent."

step "3/5 — Traversée des dossiers parents (x uniquement, jamais d'écriture)"
# Le service doit pouvoir TRAVERSER les parents jusqu'à la racine du contenu. On vérifie
# seulement, sans élargir des droits : un parent non traversable est signalé, pas « corrigé »
# en masse (ce serait un élargissement global que le ticket interdit explicitement).
PARENT_BLOCKED=0
probe="$CONTENT_DIR"
while [ "$probe" != "/" ] && [ -n "$probe" ]; do
  if ! sudo -u "$SERVICE_USER" test -x "$probe"; then
    say "   MANQUE x pour $SERVICE_USER : $probe"
    PARENT_BLOCKED=1
  fi
  probe="$(dirname "$probe")"
done
if [ "$PARENT_BLOCKED" = 1 ]; then
  echo "Au moins un dossier parent n'est pas traversable par $SERVICE_USER (voir ci-dessus)." >&2
  echo "À corriger explicitement par le propriétaire du dépôt (chmod o+x sur ce parent précis)." >&2
  exit 3
fi
say "   Tous les parents sont traversables."

step "4/5 — ACL minimale sur les dossiers éditables"
for kind in "${KINDS[@]}"; do
  dir="$CONTENT_DIR/$kind"
  if [ ! -d "$dir" ]; then
    say "   $kind/ : dossier absent, ignoré (aucune ACL posée sur un chemin inexistant)."
    continue
  fi
  if getfacl -p "$dir" 2>/dev/null | grep -q "^user:$SERVICE_USER:rwx"; then
    say "   $kind/ : ACL déjà en place."
  else
    say "   $kind/ : ACL manquante — ajout."
  fi
  # -m est idempotent ; la « default » garantit que les fichiers CRÉÉS ensuite restent
  # accessibles (sinon un YAML créé par ubuntu redeviendrait non modifiable par le panel).
  run setfacl -m "u:$SERVICE_USER:rwx" "$dir"
  run setfacl -d -m "u:$SERVICE_USER:rwx" "$dir"
  # Fichiers déjà présents : l'écriture se fait par remplacement atomique (fichier temporaire
  # dans le même dossier + rename), donc le droit sur le DOSSIER suffit. On aligne malgré tout
  # les fichiers existants pour que les futures lectures/écritures directes restent possibles.
  run setfacl -m "u:$SERVICE_USER:rw" $(find "$dir" -maxdepth 1 -name '*.yml' -print 2>/dev/null | tr '\n' ' ') 2>/dev/null || true
done

step "5/5 — Drop-in systemd ReadWritePaths"
NEW_DROPIN="$(
  printf '%s\n' \
    "# Généré par scripts/plugadmin/grant-content-access.sh (issue #162) — ne pas éditer à la main." \
    "#" \
    "# ProtectSystem=strict rend tout le système en lecture seule pour le service ;" \
    "# chaque dossier de contenu réellement éditable doit être listé ici, sinon l'éditeur" \
    "# reste en lecture seule même avec la bonne ACL POSIX." \
    "[Service]"
  for kind in "${KINDS[@]}"; do
    [ -d "$CONTENT_DIR/$kind" ] && printf 'ReadWritePaths=%s\n' "$CONTENT_DIR/$kind"
  done
)"

if [ -f "$DROPIN_FILE" ] && [ "$(cat "$DROPIN_FILE")" = "$NEW_DROPIN" ]; then
  say "   Drop-in déjà à jour : $DROPIN_FILE"
else
  say "   Écriture du drop-in : $DROPIN_FILE"
  if [ "$DRY" = 1 ]; then
    printf '   [dry-run] contenu :\n'
    printf '%s\n' "$NEW_DROPIN" | sed 's/^/     /'
  else
    mkdir -p "$DROPIN_DIR"
    printf '%s\n' "$NEW_DROPIN" > "$DROPIN_FILE"
    chmod 644 "$DROPIN_FILE"
  fi
  run systemctl daemon-reload
  if [ "$DO_RESTART" = 1 ]; then
    run systemctl restart "$SERVICE_NAME"
  else
    say "   --no-restart : le drop-in ne prendra effet qu'au prochain redémarrage du service."
  fi
fi

step "Vérification"
for kind in "${KINDS[@]}"; do
  dir="$CONTENT_DIR/$kind"
  [ -d "$dir" ] || continue
  if [ "$DRY" = 1 ]; then
    say "   [dry-run] $kind/ : non vérifié"
  elif sudo -u "$SERVICE_USER" test -w "$dir"; then
    say "   $kind/ : écriture OK pour $SERVICE_USER (ACL)."
  else
    say "   $kind/ : ÉCRITURE REFUSÉE pour $SERVICE_USER — ACL à revoir."
  fi
done
say ""
say "Rappel : le test « test -w » ci-dessus ne voit que l'ACL. Le bac à sable systemd n'est"
say "vérifiable qu'en interrogeant le panel lui-même (page /dialogues/new ne doit plus afficher"
say "le bandeau « Éditeur en lecture seule »), après redémarrage du service."
