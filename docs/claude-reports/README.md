# Rapports Claude

Historique chronologique, **immuable**, de chaque session de développement effectuée par Claude
dans ce dépôt — voir `CLAUDE.md`, section « Session Reports », pour la règle qui impose leur
création. Un rapport est créé après **chaque** demande (développement, correction de bug,
diagnostic, refactoring, migration, ou modification de configuration/documentation), même si la
tâche est petite, partielle, bloquée, ou n'a finalement modifié aucun code.

## Pourquoi ce dossier existe

Ces rapports ne remplacent **jamais** la documentation fonctionnelle du projet
(`docs/current_state.md`, `docs/ARCHITECTURE.md`, `docs/RPGQUEST_BIBLE.md`, etc.), qui continue
d'être maintenue à jour normalement. Ils servent un besoin différent : pouvoir être envoyés
**tels quels** à un autre assistant (ou humain) qui n'a suivi aucune des sessions précédentes, et
qui doit pouvoir comprendre, sans contexte supplémentaire :

- ce qui était demandé ;
- ce qui existait avant l'intervention ;
- ce qui a changé, et pourquoi (décisions techniques incluses) ;
- comment cela a été testé ;
- comment le déployer ;
- ce qui reste éventuellement problématique ou non traité.

## Règles

- **Un fichier par demande, jamais réutilisé** — un rapport n'écrase jamais un rapport précédent.
- **Immuable après création** — ne jamais modifier un ancien rapport pour refléter l'état actuel
  du projet. Un rapport documente l'état des choses *au moment où il a été écrit*, pas l'état
  actuel (voir `docs/current_state.md` pour ça).
- **Toujours créé, même en échec** — un statut `PARTIAL`/`BLOCKED`, des tests qui échouent, ou une
  demande purement diagnostique n'exemptent jamais de la création du rapport.
- **Reflète la réalité, pas la demande initiale** — le rapport décrit ce qui a été *réellement*
  fait, qui peut différer de ce qui était prévu au départ.
- **Mesure du temps réel, jamais inventée** — voir `CLAUDE.md`, section « Session Reports — Time
  Tracking » : début/fin/durée réels de la tâche, ou `non mesurable` explicitement si le début
  n'a pas pu être capturé.

## Nom de fichier

```
YYYY-MM-DD_HHMM_<description-courte>.md
```

Date et heure **locales réelles** au moment de la création du rapport (pas celles de la tâche
elle-même si le rapport est rédigé après coup). Exemples :

- `2026-08-21_2006_worldportal-debug-tools.md`
- `2026-08-21_2142_fix-hub-auto-teleport.md`
- `2026-08-22_1015_story-npc-integration.md`

## Gabarit obligatoire

Chaque rapport contient au minimum les sections suivantes, dans cet ordre :

```markdown
# RPGQuest — Rapport Claude

## Informations
* Date :
* Heure :
* Sujet :
* Statut : DONE / PARTIAL / BLOCKED
* Branche Git :
* Commit actuel si disponible :
* Début de la tâche : YYYY-MM-DD HH:MM:SS (heure locale réelle, ou « non mesurable »)
* Fin de la tâche : YYYY-MM-DD HH:MM:SS (heure locale réelle)
* Durée totale : HH:MM:SS (calculée entre les deux, jamais estimée)

## Demande
## Analyse
## Travail effectué
## Fichiers créés
## Fichiers modifiés
## Base de données / migrations
## Configuration / données
## Tests automatiques
## Tests manuels à effectuer
## Résultat attendu
## Reset / retour à l'état initial
## Déploiement VeryGames
### À transférer
### Ne PAS transférer/altérer
### Redémarrage requis
### Migration automatique
## Rollback
## Logs / diagnostic
## Documentation mise à jour
## Limitations / travail restant
## Prochaine étape suggérée
```

Voir n'importe quel rapport existant ci-dessous pour un exemple rempli.

## Index chronologique

| Date | Sujet | Statut | Fichier |
|---|---|---|---|
| 2026-08-21 | Outils de diagnostic WorldPortal (`here`/`debug`, TP-TRACE) | DONE | [2026-08-21_2009_worldportal-debug-tools.md](2026-08-21_2009_worldportal-debug-tools.md) |
| 2026-08-21 | Progression automatique de Storyline (connexion au moteur de quête) | DONE | [2026-08-21_2135_story-automatic-progression.md](2026-08-21_2135_story-automatic-progression.md) |
| 2026-08-23 | Investigation BREAK_BLOCK ne progresse pas dans `wild` (story_test) | BLOCKED | [2026-08-23_1234_break-block-wild-investigation.md](2026-08-23_1234_break-block-wild-investigation.md) |
| 2026-08-23 | Instrumentation `[QUEST-TRACE]` pour diagnostiquer la chaîne BREAK_BLOCK | DONE | [2026-08-23_1256_quest-trace-break-block-instrumentation.md](2026-08-23_1256_quest-trace-break-block-instrumentation.md) |
| 2026-08-23 | Investigation reset progression Story/quête (death/respawn, reconnexion) + correctif `premiers_pas.yml` manquant | PARTIAL | [2026-08-23_1643_quest-story-progress-persistence-investigation.md](2026-08-23_1643_quest-story-progress-persistence-investigation.md) |
| 2026-08-23 | Premier claim joueur 5×5 débloqué par une Story (CLAIM_TIER_1 → PNJ Jo → Acte de propriété → pose sans commande) | DONE | [2026-08-23_1758_premier-claim-5x5-story-deed.md](2026-08-23_1758_premier-claim-5x5-story-deed.md) |
| 2026-08-23 | 3 améliorations Claim : Jo « retourner à son claim », `/claim admin tp`, monde `claims` réellement pacifique | DONE | [2026-08-23_1857_claim-gohome-mobs-pacifiques.md](2026-08-23_1857_claim-gohome-mobs-pacifiques.md) |
| 2026-08-23 | MVP monde `claims` : frontière par particules, zone résidentielle safe, Pierre de retour, blocage Nether, complétion config.yml | DONE | [2026-08-23_2003_claims-mvp-frontiere-pacifique-pierre-retour.md](2026-08-23_2003_claims-mvp-frontiere-pacifique-pierre-retour.md) |
| 2026-09-05 | 4 corrections UX post-validation VeryGames : indicateur de canalisation, Pierre de retour limitée à `claims` + anti-perte, faisceau de retrouvaille du claim à distance | DONE | [2026-09-05_1729_claims-mvp-corrections-ux-pierre-retour-faisceau.md](2026-09-05_1729_claims-mvp-corrections-ux-pierre-retour-faisceau.md) |
| 2026-09-05 | Boucle joueur Hub ↔ Wild : système soulbound générique, item Journal des quêtes, Rune de rappel (cooldown persistant), avertissement compact avant entrée Wild, Waystones génératives, config `travel.*`, migrations V16/V17, `/rpgadmin waystone` | DONE | [2026-09-05_1837_boucle-joueur-hub-wild-soulbound-journal-rune-waystones.md](2026-09-05_1837_boucle-joueur-hub-wild-soulbound-journal-rune-waystones.md) |
| 2026-09-05 | Reset admin « nouveau joueur » : `/rpgadmin player resetnew <joueur> confirm` — remet l'état RPGQuest d'un seul joueur (online/offline) à l'équivalent « jamais joué » (quêtes, Stories, variables/unlocks, progression RPG, découvertes Waystones, cooldowns, claim principal, objets RPGQuest de l'inventaire). Voir la section « Reset complet "nouveau joueur" » du rapport et [docs/ADMIN_PLAYER_RESET.md](../ADMIN_PLAYER_RESET.md). | DONE | [2026-09-05_2002_reset-admin-nouveau-joueur.md](2026-09-05_2002_reset-admin-nouveau-joueur.md) |
| 2026-09-06 | Issue #10 — scripts de déploiement / rollback VeryGames : `scripts/deploy-verygames.sh` (working tree propre, `./gradlew test`+`build`, backup daté de la version en ligne, transfert FTP atomique du seul JAR via `curl`, jamais `data.db`/configs/mondes), `scripts/rollback-verygames.sh` (restaure un backup, se sauvegarde d'abord, refuse sans backup valide), `scripts/lib/verygames-common.sh`, `scripts/verygames.env.example`. Fichier d'identifiants **hors Git** (`~/.config/rpgquest/verygames.env`, mode 600) ; mot de passe jamais sur la ligne de commande. `--dry-run` / `--check` sans connexion. Arrêt/redémarrage serveur restent manuels (pas d'API/RCON VeryGames). Aucun déploiement effectué. | DONE (transfert réel PENDING MANUAL VALIDATION — hôte/mot de passe non fournis) | [2026-09-06_0756_verygames-deploy-scripts-issue-10.md](2026-09-06_0756_verygames-deploy-scripts-issue-10.md) |
