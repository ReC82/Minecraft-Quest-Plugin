#!/usr/bin/env bash
# Installe (ou met à jour) le superviseur LodyQuests. Ne le démarre pas : voir README.md.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
sudo install -d -m 0755 /opt/lodyquests-orch
sudo install -m 0755 "$here/lqorch.py" /opt/lodyquests-orch/lqorch.py
# Instantané de la file lu par PlugAdmin (#907) : hors du HOME, lisible par l'utilisateur plugadmin.
sudo install -d -o ubuntu -g ubuntu -m 0755 /var/lib/lodyquests-orch
sudo install -m 0644 "$here/session_prompt.md" /opt/lodyquests-orch/session_prompt.md
sudo install -m 0644 "$here/lodyquests-orch.service" /etc/systemd/system/lodyquests-orch.service
printf '#!/bin/sh\nexec /usr/bin/python3 /opt/lodyquests-orch/lqorch.py "$@"\n' | sudo tee /usr/local/bin/lqorch >/dev/null
sudo chmod 0755 /usr/local/bin/lqorch
sudo systemctl daemon-reload
echo "Installé. État : lqorch status — démarrer : sudo systemctl start lodyquests-orch"
echo "Superviseur déjà actif : « lqorch reload » applique le nouveau code entre deux sessions."
