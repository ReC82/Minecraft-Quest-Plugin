# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-14
* Heure : 19:03
* Sujet : Issue #380 — superviseur `lqorch` : plafond de sessions configurable, quotas explicites, rechargement sûr
* Statut : DONE (code livré et installé) — activation en attente d'un redémarrage unique par le propriétaire
* Branche Git : `feature/380-lqorch-configurable-cap` → PR #386 fusionnée dans `chore/257-ci-github-actions`
* Commit actuel si disponible : `062c2ba` (fusion `b26607f`)
* Début de la tâche : 2026-10-14 18:48:29
* Fin de la tâche : 2026-10-14 19:03:53
* Durée totale : 00:15:24

## Demande
Le propriétaire voit « 24 sessions au maximum » alors que son interface indique ~40 % d'usage
hebdomadaire. Identifier l'origine du 24, le rendre configurable et observable, distinguer limite
locale et quota fournisseur, sans boucle coûteuse ni contournement, et appliquer le changement
sans tuer la session en cours.

## Analyse
- **Origine du 24** : `scripts/orchestrator/lqorch.py`, ligne 55 avant ce lot :
  `MAX_SESSIONS_PER_DAY = int(os.environ.get("LQ_MAX_SESSIONS_PER_DAY", 24))`, choisi à la
  création du superviseur (#338). L'unité systemd ne définit aucune variable `LQ_*`
  (`systemctl show -p Environment` : seulement HOME et PATH). Aucun lien avec un quota fournisseur.
  Compté sur les sessions **démarrées** du jour **UTC**.
- **Autres limites existantes** : session 4 h max, 75 min sans sortie, 3 tentatives sans rapport
  par ticket, tri au plus toutes les 6 h, attente jusqu'au `resetsAt` (+2 min) quand Claude Code
  signale une limite (`rate_limit_event` statut ≠ `allowed`, ou 429). Pas de budget, pas de
  disjoncteur global, pas d'écart minimal entre sessions.
- **Ce que Claude Code fournit réellement** (journaux de session) : chaque session émet des
  `rate_limit_event` avec `unifiedWindows.five_hour` / `seven_day` (`utilization`, `resetsAt`),
  `overageStatus: rejected` / `org_level_disabled` (aucun dépassement payant possible), et un
  `total_cost_usd` en `costBasis: list` (prix catalogue : une estimation, pas une facture).
  Les pourcentages de l'interface claude.ai et l'usage interactif entre deux sessions ne sont pas
  lisibles : rien n'en est déduit.
- **Mesures 13–14/10** : 22 sessions, fenêtre 7 j de 36 % à 45 % (≈ 0,4 point/session), fenêtre
  5 h ≤ 40 %, ≈ 5 USD estimés/session, 106,78 USD estimés le 14/10 à 17 h UTC.

## Travail effectué
- Registre `SETTINGS` (9 réglages typés et bornés) ; priorité `lqorch config set` (base d'état) >
  env `LQ_*` > défaut du code ; valeur invalide ignorée. Relus à chaque tour de boucle : appliqués
  avant la session suivante, jamais pendant.
- Nouveaux garde-fous : `max_cost_usd_per_day` (0 = désactivé), `max_five_hour_utilization` (0,9),
  `max_seven_day_utilization` (0,8), `min_seconds_between_sessions` (60, min 30),
  `max_consecutive_failures` (4 → pause globale avec motif, réarmée par `lqorch resume`).
- `max_sessions_per_day 0` (sans plafond) refusé si aucun autre garde-fou n'est actif ; retirer le
  dernier garde-fou est refusé ; un « sans plafond » non gardé venu de l'environnement retombe à 24.
- `start_blocker()` unique, utilisé par la boucle et par `status` : pause/disjoncteur → limite
  fournisseur → seuils d'utilisation → plafond local → budget → écart minimal.
- `lqorch status` : prochaine session autorisée ou motif + reprise au plus tôt (UTC + heure
  locale), sessions/plafond + origine, remise à zéro 00:00 UTC, coût estimé, disjoncteur, dernier
  instantané fournisseur daté, avertissement explicite « quota restant réel inconnu », version du
  code en cours vs installé.
- `lqorch config [show|set|unset]`, `lqorch reload` (os.execv entre deux sessions, même PID pour
  systemd, verrou libéré par l'exec puis repris).
- Correctif : le pipe stdout de chaque session n'était jamais fermé (fuite d'un descripteur par
  session dans le superviseur longue durée).
- `test_lqorch.py` ajouté au job CI « Scripts ».
- Installation dans `/opt/lodyquests-orch` via `install.sh` (sauvegarde préalable, voir Rollback).
  Le superviseur actif (pid 1432189, antérieur à #380) **n'a pas été redémarré** : il porte cette
  session même.

## Fichiers créés
- `scripts/orchestrator/test_lqorch.py`
- `docs/claude-reports/2026-10-14_1903_lqorch-plafond-configurable-380.md`

## Fichiers modifiés
- `scripts/orchestrator/lqorch.py`
- `scripts/orchestrator/README.md` (section « Garde-fous et quotas (#380) »)
- `scripts/orchestrator/install.sh` (message `lqorch reload`)
- `.github/workflows/ci.yml` (étape de test du superviseur)
- `docs/claude-reports/README.md` (index)

## Base de données / migrations
Aucune migration RPGQuest. Base d'état du superviseur : aucune table nouvelle, seulement des clés
dans `control` (`cfg.*`, `provider_usage`, `consecutive_failures`, `pause_reason`,
`reload_requested`, `supervisor_version`) — compatible avec l'ancien superviseur, qui les ignore.

## Configuration / données
Aucun réglage modifié sur l'état réel : la valeur reste 24 (défaut du code) — choix laissé au
propriétaire (voir Prochaine étape).

## Tests automatiques
- Local : `python3 -W error::ResourceWarning -m unittest scripts/orchestrator/test_lqorch.py` →
  17/17 OK (faux `claude`, faux `gh`, STATE_DIR temporaire) : précédence des réglages, refus du
  sans-plafond non gardé, plafond atteint puis rehaussé (unitaire et superviseur réel en
  sous-processus), budget, seuil d'utilisation et remise à zéro de fenêtre, limite fournisseur
  non contournée par un plafond rehaussé, écart minimal, pause, disjoncteur + resume, rate limit
  sans tentative comptée, `stop` puis relance refusée, `stop --now` sans tentative comptée,
  `reload` (même PID, verrou conservé : second superviseur refusé).
- `status` exécuté sur une **copie** de `state.db` avant installation, puis sur l'état réel après.
- CI PR #386 : verte (Scripts : « Ran 17 tests … OK » ; plugin, control-panel, web-api, artefacts, CI OK).
- Pas de Gradle local : aucun code Java touché.

## Tests manuels à effectuer
- Après le redémarrage unique : `lqorch status` doit afficher `code 573d74066d25` sans
  avertissement. PENDING MANUAL VALIDATION.

## Résultat attendu
Le propriétaire règle `lqorch config set max_sessions_per_day 40` (ou autre) sans toucher au code ;
le superviseur s'arrête de lui-même avant d'épuiser la semaine fournisseur (seuil 80 %).

## Reset / retour à l'état initial
`lqorch config unset <nom>` pour chaque réglage modifié.

## Déploiement VeryGames
### À transférer
Rien : outil de la machine AWS, aucun impact sur le serveur Minecraft ni sur PlugAdmin.
### Ne PAS transférer/altérer
`~/.local/state/lodyquests-orch/state.db` (état réel du superviseur).
### Redémarrage requis
Du superviseur seulement, **une fois**, à une frontière de session :
`lqorch stop` → attendre « superviseur arrêté » dans `lqorch status` →
`lqorch resume && sudo systemctl start lodyquests-orch`. Jamais `systemctl restart` pendant une
session (`KillMode=control-group`). Ensuite, toute mise à jour : `install.sh` puis `lqorch reload`.
### Migration automatique
Non.

## Rollback
Sauvegarde des fichiers précédents :
`~/.local/state/lodyquests-orch/backup-20261014-*/` (`lqorch.py`, `session_prompt.md`,
`lodyquests-orch.service`). Restaurer : `sudo install -m 0755 <backup>/lqorch.py /opt/lodyquests-orch/lqorch.py`
puis redémarrer le superviseur selon la même procédure. Les clés `control` ajoutées sont ignorées
par l'ancien code.

## Logs / diagnostic
`lqorch status`, `lqorch config`, `lqorch log` (événements `config`, `breaker`, `supervisor`).

## Documentation mise à jour
`scripts/orchestrator/README.md`.

## Limitations / travail restant
- L'instantané fournisseur n'est rafraîchi que pendant les sessions : l'usage interactif du
  propriétaire n'est vu qu'au début de la session suivante. Une session déjà lancée n'est jamais
  coupée pour un seuil d'utilisation (seulement par la limite fournisseur elle-même).
- `max_cost_usd_per_day` n'a de sens de facturation qu'à l'usage (API) ; sur abonnement, indicateur.

## Prochaine étape suggérée
1. Redémarrage unique (ci-dessus). 2. `lqorch config set max_sessions_per_day 40` (recommandé :
≈ 16 points de semaine/jour, le seuil 7 j à 80 % arrête le superviseur avant l'épuisement).
