# Superviseur LodyQuests (`lqorch`)

Fait avancer le backlog entre plusieurs sessions Claude Code, sans terminal ouvert.

## Pourquoi

Une session Claude interactive s'arrête dès que le modèle rend sa réponse finale : elle attend
alors un message humain. Rien ne la relançait. Le superviseur lance des sessions **non
interactives** (`claude -p`), une par ticket, avec un contexte reconstruit à chaque fois, et
enchaîne tant qu'il reste du travail éligible.

## Composants

| Élément | Où |
|---|---|
| Superviseur + CLI | `/opt/lodyquests-orch/lqorch.py` (source : `scripts/orchestrator/`) |
| Consignes de session | `/opt/lodyquests-orch/session_prompt.md` |
| Service | `lodyquests-orch.service` (systemd, utilisateur `ubuntu`) |
| État durable | `~/.local/state/lodyquests-orch/state.db` (SQLite : tickets, sessions, événements, contrôle) |
| Journaux de session | `~/.local/state/lodyquests-orch/sessions/*.jsonl` |
| Copie de travail des sessions | `/srv/rpgquest/wt-orch` (jamais l'arbre du propriétaire) |

Installer / mettre à jour : `scripts/orchestrator/install.sh`.

## Commandes

```
lqorch status                 # mode, session en cours, prochaine session possible ou motif, plafonds, quota observé
lqorch config                 # garde-fous : valeur, origine (défaut / env / lqorch config), bornes
lqorch config set max_sessions_per_day 40          # appliqué avant la prochaine session, sans redémarrage
lqorch config unset max_sessions_per_day           # retour à la variable d'environnement ou au défaut
lqorch reload                 # relire le code installé ENTRE deux sessions (même PID, session jamais coupée)
lqorch queue                  # file : statut, priorité, dépendances, prochaine action
lqorch pause                  # ne plus démarrer de session (la session en cours finit)
lqorch resume                 # reprendre (réarme aussi le disjoncteur ; autorise un redémarrage du service)
lqorch stop                   # arrêter après la session en cours ; le service ne repart pas tout seul
lqorch stop --now             # arrêter tout de suite (la session en cours est coupée, reprise plus tard)
lqorch skip 13 "raison"       # mettre un ticket de côté (BLOCKED) et passer au suivant
lqorch add 13 --prio 30 --deps 35,321 --note "…"   # ajouter / remettre en file
lqorch set 13 next_action "…" # corriger un champ
lqorch auto                   # file automatique agent:auto (#836) : examen à blanc, ordre et motifs d'exclusion
lqorch auto hold 54 "recette TC-327 E"   # ne jamais sélectionner seul ce ticket (même après un commentaire)
lqorch auto release 54        # le libérer (repris à la prochaine activité GitHub du propriétaire)
lqorch config set auto_select 0   # couper la sélection automatique (auto_comment 0 : sans commentaire GitHub)
lqorch log [13] / lqorch sessions
sudo systemctl start|stop|status lodyquests-orch
journalctl -u lodyquests-orch
```

## Comportement

- **Sélection** : d'abord un ticket interrompu (RUNNING/TESTING), puis le READY le plus prioritaire
  dont les dépendances sont fermées sur GitHub ou traitées ici. Un ticket fermé sur GitHub sort de
  la file. File vide → une session de **tri** (#0) relit le backlog et propose jusqu'à 6 tickets
  (au plus une fois toutes les 6 h) — mais seulement si la **file automatique** n'a rien trouvé.
- **File automatique (#836)** : file locale vide → le superviseur lit les issues **ouvertes**
  labellisées `agent:auto` et arme la plus prioritaire (`priority:P0` → `P3`, puis numéro le plus
  ancien), une seule à la fois. Écartées, avec leur motif visible dans `lqorch auto` :
  - sans `agent:auto`, sans label `priority:P*` ou avec plusieurs ; labels `blocked`, `in-progress`,
    `duplicate`, `invalid`, `wontfix` ;
  - déjà dans la file locale (READY / RUNNING / TESTING : ses propres règles s'appliquent) ;
    FAILED (relance manuelle `lqorch add`) ; retenues par `lqorch auto hold` ;
  - **déjà traitées ici** (DONE / WAITING_OWNER / BLOCKED) **sans activité nouvelle du
    propriétaire** : seul un commentaire GitHub postérieur à la dernière prise en compte, posté hors
    de toute fenêtre de session (+5 min) et sans le marqueur `<!-- lqorch`, réarme le ticket. Les
    rapports des sessions ne relancent donc jamais leur propre ticket (pas de boucle) ;
  - PR ouverte sur une branche `…/N-…` (travail concurrent) ;
  - dépendances ouvertes : colonne `deps` locale + lignes « Dépend de / Bloqué par / Prérequis /
    Depends on #N » du ticket (levée si fermée, ou DONE / WAITING_OWNER ici).
  La prise en charge est tracée (événement `auto_select`, `next_action` « Sélection automatique… ») et
  annoncée par un commentaire sur le ticket. **Le label rend éligible, il n'autorise rien** :
  déploiement, appel payant ou opération réelle restent soumis au ticket et au prompt de session.
  Une édition du corps du ticket ou un changement de label ne réarme pas un ticket déjà traité :
  écrire un commentaire, ou `lqorch add N`.
- **Fin de session** : la session écrit un rapport JSON (statut, branche, PR, tests, blocages,
  prochaine action). Sans rapport : nouvelle tentative avec délai croissant, abandon (FAILED) après
  3 tentatives — jamais de boucle.
- **Limite du fournisseur** : lue dans les événements `rate_limit_event` de Claude Code ; aucune
  session n'est lancée avant l'heure de remise à zéro annoncée (+2 min). La tentative ne compte pas.
- **Surveillance** : session coupée après 75 min sans sortie ou 4 h au total (réglables).
- **Disjoncteur** : après 4 sessions consécutives sans rapport (crash, erreur, délai dépassé), tous
  tickets confondus, le superviseur se met en PAUSE avec le motif ; `lqorch resume` le réarme.
- **Redémarrage du superviseur** : une session orpheline est arrêtée et notée INTERRUPTED ; son
  ticket est repris en premier, depuis l'état réel (Git, GitHub, DEV).
- **Verrou** : un seul superviseur à la fois (`flock`), conservé à travers `lqorch reload`.
- **STOP** est prioritaire : effet en moins de 2 s, aucune nouvelle session, relance du service
  refusée tant que `lqorch resume` n'a pas été demandé.

## Ce que les sessions ont le droit de faire

Voir `session_prompt.md` : règles de `CLAUDE.md`, copie de travail dédiée, vérification de l'état
réel avant toute action, merge et déploiement **DEV** seulement, aucune migration de base réelle,
aucune donnée joueur modifiée, aucun secret affiché, aucune validation en jeu inventée.

## Garde-fous et quotas (#380)

### D'où venait « 24 sessions par jour »

D'une constante du superviseur lui-même, choisie à sa création (#338) comme garde-fou prudent :
`MAX_SESSIONS_PER_DAY = int(os.environ.get("LQ_MAX_SESSIONS_PER_DAY", 24))`. Ni l'unité systemd
(aucune variable `Environment=LQ_…`), ni Claude Code, ni le fournisseur n'imposent ce nombre. Le
compteur porte sur les sessions **démarrées** pendant le jour **UTC** (remise à zéro à 00:00 UTC,
soit 02:00 heure d'été de Bruxelles).

### Deux familles de limites

| Limite | Nature | Lue comment | Effet |
|---|---|---|---|
| `max_sessions_per_day` | locale | réglage | plus de démarrage jusqu'à 00:00 UTC |
| `max_cost_usd_per_day` | locale | somme des `total_cost_usd` du jour | idem ; c'est une **estimation au prix catalogue** (`costBasis: list`) calculée par Claude Code, pas une facture |
| `min_seconds_between_sessions` | locale | réglage | anti-boucle : au moins 60 s entre deux démarrages |
| `max_consecutive_failures` | locale | issues de session | pause globale (disjoncteur) |
| `max_attempts`, `session_max_seconds`, `session_idle_seconds` | locales | réglages | par ticket / par session |
| Limite fournisseur atteinte | **fournisseur** | `rate_limit_event` (statut ≠ `allowed`) ou erreur 429 | aucune session avant `resetsAt` + 2 min, jamais contourné |
| Fenêtres 5 h / 7 jours | **fournisseur** | `rate_limit_info.unifiedWindows.*.utilization` émis par Claude Code dans chaque session | aucun démarrage au-delà de `max_five_hour_utilization` (0,9) / `max_seven_day_utilization` (0,8), jusqu'à la remise à zéro annoncée |

Ce qui **peut** être lu automatiquement : uniquement ce que Claude Code écrit dans son flux
`stream-json` pendant une session (taux d'utilisation des fenêtres 5 h et 7 jours, heure de
remise à zéro, statut, `overageStatus` — aujourd'hui `rejected` / `org_level_disabled` : aucun
dépassement payant n'est possible). Ce qui **ne peut pas** l'être : les pourcentages affichés par
l'interface claude.ai, ni l'usage interactif du propriétaire entre deux sessions. `lqorch status`
affiche donc le dernier instantané **avec son heure**, et ne prétend jamais connaître le quota
restant.

### Ordre des décisions avant chaque session

`PAUSED` (ou disjoncteur) → limite fournisseur annoncée → seuils d'utilisation fournisseur →
plafond de sessions → budget estimé → écart minimal. Rehausser le plafond local ne lève donc jamais
une limite fournisseur. File vide → attente (tri au plus toutes les 6 h). Délai dépassé → ticket
repris à la session suivante. STOP reste prioritaire sur tout.

### Régler

Priorité : `lqorch config set` (stocké dans `state.db`) > variable d'environnement `LQ_…` (unité
systemd) > défaut du code. Valeurs typées et bornées ; une valeur hors bornes est refusée.
**Sans plafond** (`max_sessions_per_day 0`) n'est accepté que si un autre garde-fou existe
(`max_cost_usd_per_day > 0` ou un seuil d'utilisation < 1) ; retirer ensuite ce dernier garde-fou
est refusé. Les réglages sont relus à chaque tour de boucle : ils s'appliquent **avant la session
suivante**, jamais pendant une session en cours, sans redémarrage.

### Valeurs recommandées (mesures du 13–14/10/2026)

Sur 22 sessions, la fenêtre 7 jours est passée de 36 % à 45 % (≈ 0,4 point par session ; 24
sessions ≈ 10 points par jour) et la fenêtre 5 h n'a jamais dépassé 40 %. Coût estimé : ≈ 5 USD
par session au prix catalogue.

- `max_sessions_per_day 40` : ≈ 16 points de la semaine par jour ; le seuil 7 jours à 80 % laisse
  toujours 20 % à l'usage interactif et arrête le superviseur avant l'épuisement.
- Garder `max_seven_day_utilization 0.8` et `max_five_hour_utilization 0.9`. Les baisser si
  l'usage interactif doit primer.
- Sans plafond seulement en connaissance de cause, avec ces seuils ; `max_cost_usd_per_day`
  n'est utile qu'en facturation à l'usage (API) : sur abonnement, c'est un indicateur.

### Mettre à jour le superviseur sans tuer une session

1. `scripts/orchestrator/install.sh` (copie les fichiers ; le processus en cours n'est pas touché).
2. `lqorch reload` : le superviseur se ré-exécute (`os.execv`, même PID, verrou repris) à la fin de
   la session en cours, ou dans les 2 s s'il est inactif. `lqorch status` signale un code installé
   différent de celui en cours.

**Une seule fois**, pour passer d'un superviseur antérieur à #380 (qui ne connaît pas `reload`) :
`lqorch stop` (attend la fin de la session en cours ; `lqorch status` → « superviseur arrêté »),
puis `lqorch resume && sudo systemctl start lodyquests-orch`. Ne jamais faire
`systemctl restart` pendant une session : `KillMode=control-group` la tuerait.
