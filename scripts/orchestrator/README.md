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
lqorch status                 # mode, session en cours, dernière activité, quota, compteurs
lqorch queue                  # file : statut, priorité, dépendances, prochaine action
lqorch pause                  # ne plus démarrer de session (la session en cours finit)
lqorch resume                 # reprendre (et autoriser un redémarrage du service)
lqorch stop                   # arrêter après la session en cours ; le service ne repart pas tout seul
lqorch stop --now             # arrêter tout de suite (la session en cours est coupée, reprise plus tard)
lqorch skip 13 "raison"       # mettre un ticket de côté (BLOCKED) et passer au suivant
lqorch add 13 --prio 30 --deps 35,321 --note "…"   # ajouter / remettre en file
lqorch set 13 next_action "…" # corriger un champ
lqorch log [13] / lqorch sessions
sudo systemctl start|stop|status lodyquests-orch
journalctl -u lodyquests-orch
```

## Comportement

- **Sélection** : d'abord un ticket interrompu (RUNNING/TESTING), puis le READY le plus prioritaire
  dont les dépendances sont fermées sur GitHub ou traitées ici. Un ticket fermé sur GitHub sort de
  la file. File vide → une session de **tri** (#0) relit le backlog et propose jusqu'à 6 tickets
  (au plus une fois toutes les 6 h).
- **Fin de session** : la session écrit un rapport JSON (statut, branche, PR, tests, blocages,
  prochaine action). Sans rapport : nouvelle tentative avec délai croissant, abandon (FAILED) après
  3 tentatives — jamais de boucle.
- **Limite du fournisseur** : lue dans les événements `rate_limit_event` de Claude Code ; aucune
  session n'est lancée avant l'heure de remise à zéro annoncée (+2 min). La tentative ne compte pas.
- **Surveillance** : session coupée après 75 min sans sortie ou 4 h au total.
- **Redémarrage du superviseur** : une session orpheline est arrêtée et notée INTERRUPTED ; son
  ticket est repris en premier, depuis l'état réel (Git, GitHub, DEV).
- **Verrou** : un seul superviseur à la fois (`flock`). Au plus 24 sessions par jour.
- **STOP** est prioritaire : effet en moins de 2 s, aucune nouvelle session, relance du service
  refusée tant que `lqorch resume` n'a pas été demandé.

## Ce que les sessions ont le droit de faire

Voir `session_prompt.md` : règles de `CLAUDE.md`, copie de travail dédiée, vérification de l'état
réel avant toute action, merge et déploiement **DEV** seulement, aucune migration de base réelle,
aucune donnée joueur modifiée, aucun secret affiché, aucune validation en jeu inventée.
