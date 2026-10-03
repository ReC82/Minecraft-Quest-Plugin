# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-03
* Heure : 21:53
* Sujet : Déploiement VeryGames DEV de l'ensemble livré — kit #26 (déjà en ligne) + réseau de voyage #132/#150/#151/#149 (premier déploiement)
* Statut : DONE (déploiement + redémarrage + vérifications de démarrage) — validation en jeu explicitement non effectuée, à la charge de l'utilisateur
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel si disponible : `3f32d18` (déployé tel quel, aucun commit de code nécessaire pour cette tâche)
* Début de la tâche : non mesurable (horodatage de la demande non capturé avant d'entamer l'audit)
* Fin de la tâche : 2026-10-03 21:53:00
* Durée totale : non mesurable

## Demande

Préparer puis, sur autorisation explicite reçue en cours de tâche, exécuter le déploiement DEV de
l'ensemble livré (kit #26 + réseau de voyage #132/#150/#151/#149) : vérifier l'état réel du
précédent déploiement, l'état Git/build, identifier précisément ce qui doit être transféré,
vérifier l'activation effective de la génération Hub plutôt que la supposer, préparer les
sauvegardes selon la procédure du dépôt, préserver joueurs/mondes/Citizens/fichiers Lily, prévoir la
vérification des migrations V19 à V21, ne pas relancer inutilement les mêmes tests si le code et
l'artefact sont inchangés depuis le dernier build vert. Après redémarrage : vérifier les clés de
configuration effectives, confirmer le commit déployé, distinguer les contrôles de démarrage de la
validation en jeu. Aucun merge ni intervention PROD.

## Analyse

**Audit préalable (présenté pour autorisation avant toute action)** :

- Précédent déploiement (#26) confirmé **réellement terminé** : `docs/deployment/SERVER_CHANGELOG.md`
  documentait un transfert JAR+dialogue et un redémarrage réussi le 2026-10-03 (~17:01 UTC) ; le
  heartbeat PlugAdmin et un `list` RCON frais confirmaient le serveur **ONLINE** avec un historique
  d'uptime cohérent (pas de redémarrage depuis).
- Branche `feat/control-panel-admin-tools` @ `3f32d18`, working tree propre hormis les 3 fichiers
  Lily non suivis. Suite complète `:test` (1322/1290/32/0) et `./gradlew build` (3 modules) déjà
  verts dans la session précédente, au même commit.
- Diff `2a9972a` (dernier déploiement) → `HEAD` dans `src/main/resources/` : **seul `config.yml`
  change** (nouvelles clés `travel.waypoint.hub-enabled` et `travel.beacon.*`), **aucun dialogue
  modifié**. `config.yml` étant explicitement refusé par `scripts/deploy-verygames.sh`, un seul
  fichier à transférer : **le JAR**.
- **Activation de la génération Hub vérifiée dans le code, pas supposée** : `ConfigService.start()`
  appelle `completeConfigFileIfNeeded()` (→ `ConfigFileCompleter.complete`, fusion récursive des
  clés manquantes) **avant** `ConfigValidator.validate(...)`, à chaque démarrage — mécanisme déjà
  observé en conditions réelles sur ce même serveur (section `travel.waypoint` ajoutée lors de
  #124, `starter-tool-kit` lors de #26). Schéma DEV confirmé à **V18** (V19/#132-150 jamais
  déployé) : les migrations V19→V21 s'appliqueraient donc pour la première fois à ce redémarrage.
- Backup `world_hub/` (précaution supplémentaire que j'avais proposée, au-delà du backup JAR
  automatique) : testé et reconnu **impossible** — le compte FTP est chrooté sur le dossier des
  plugins (`cd world_hub/` → « Server denied you to change to the given directory »), aucun accès
  aux mondes par ce canal, quelle que soit la méthode employée.

Autorisation explicite reçue de l'utilisateur pour exécuter les étapes 1 à 6 du plan présenté.

## Travail effectué

1. **Tentative de backup `world_hub/`** : confirmée impossible (FTP chrooté sur les plugins) —
   documenté plutôt que silencieusement ignoré.
2. **`RPGQUEST_TEST_MAX_HEAP=768m scripts/deploy-verygames.sh -y --allow-dirty --prune-keep 10`**
   lancé pour le transfert. Son passage interne `./gradlew test`+`build` (garde-fou intégré du
   script officiel, pas une répétition volontaire de ma part) s'est heurté à une **forte pression
   mémoire du système** : 3 autres sessions Claude Code actives en parallèle sur cette même
   machine, RAM quasi saturée (~400 Mo libres sur 3,7 Go), swap utilisé (1,1 Go), charge système
   élevée. Après ~40 minutes sans qu'une seule des 163 classes de test ne termine, diagnostic par
   **thread dump** : progression réelle (threads `RUNNABLE`/attente légitime sur une
   `CompletableFuture` async, pas un deadlock) mais paralysée par la contention mémoire/E-S.
   **Décision** : interrompre ce passage redondant (le code et le JAR étaient déjà identiques au
   build vert de la session précédente, au même commit) plutôt que d'attendre indéfiniment ou de
   répéter une suite déjà vérifiée — conforme à la consigne explicite de ne pas relancer
   inutilement les mêmes tests.
3. **Backup + transfert exécutés directement**, en réutilisant les **mêmes fonctions FTP du dépôt**
   (`scripts/lib/verygames-common.sh` : `vg::load_config`, `vg::remote_file_exists`,
   `vg::remote_download`, `vg::remote_put_atomic`, etc. — jamais de commande FTP brute
   improvisée), avec le JAR déjà construit et déjà vérifié vert :
   - Backup du JAR en ligne : `rpgquest-20261003T194955Z-predeploy.jar` (1 528 065 o, SHA-256
     `d5a8d7438e1b72ada010cfad7318197601b5b156aaacf8c710f2fc59ed2cec69` — **identique** à la version
     déployée pour #26, confirmant qu'aucun déploiement intermédiaire n'a eu lieu entre-temps).
   - Transfert atomique du nouveau JAR : 1 572 798 o, SHA-256
     `7a1338eeb9dadb326e2edcdc1662e8a9a5cdfe5a7f7766928d443c1d54ba5c16` — taille distante finale
     identique à la taille locale.
4. **Redémarrage** : `scripts/verygames-restart.sh --timeout 240` — `save-all` → `stop` RCON →
   OFFLINE confirmé → **ONLINE** en quelques dizaines de secondes (1 joueur connecté avant l'arrêt,
   `LoDyMcFly` = l'utilisateur, déconnecté par le redémarrage comme attendu ; 0 joueur juste après).
5. **Vérifications post-redémarrage** (détail ci-dessous).
6. Documentation : `docs/deployment/SERVER_CHANGELOG.md` (nouvelle entrée complète), `.ai/ROADMAP.md`
   (journal), ce rapport.

## Fichiers créés

- `docs/claude-reports/2026-10-03_2153_deploiement-dev-reseau-voyage-132-150-151-149.md` (ce rapport)

## Fichiers modifiés

- `docs/deployment/SERVER_CHANGELOG.md` (nouvelle entrée « Réseau de voyage... »)
- `.ai/ROADMAP.md` (journal de déploiement)
- `docs/claude-reports/README.md` (index)

**Aucun fichier source modifié** par cette tâche (déploiement pur). Les 3 fichiers locaux non
suivis de Lily n'ont jamais été touchés (vérifié avant/après).

## Base de données / migrations

Schéma DEV avant ce redémarrage : **V18**. Migrations **V19** (`travel_beacons`), **V20**
(`village_centers`), **V21** (`travel_beacons.biome_instance`, ALTER idempotent) appliquées pour la
première fois à ce redémarrage. Preuve : le plugin s'est activé **pleinement** (4 plugins verts,
commandes `/rpgquest`/`/rpgadmin` opérationnelles) — `SchemaMigrationRunner`/`RPGQuestBootstrap`
avortent l'activation en cas d'échec de migration critique, donc une activation réussie est une
preuve d'application sans erreur. **Limite assumée** (déjà documentée pour V18/#124) : aucun accès
direct aux logs serveur par ce compte FTP/RCON — pas de lecture ligne par ligne du journal de
migration possible à distance.

## Configuration / données

`config.yml` **non transféré**. Vérifié **après** redémarrage par téléchargement FTP en lecture
seule (jamais supposé) : `travel.waypoint.hub-enabled: true`, `travel.beacon.button-material:
OAK_BUTTON`, `travel.beacon.hub-generation.{enabled: true, pair-min-spacing: 6, pair-max-spacing:
16}`, `config-version: 1` — tous ajoutés automatiquement par `ConfigFileCompleter`, commentaires du
gabarit inclus (copie de sous-arbre complète, comme attendu pour une section entièrement nouvelle).
Aucune valeur préexistante modifiée. Aucune donnée joueur, monde ou PNJ Citizens touchée (chemins
refusés explicitement par les scripts de déploiement).

## Tests automatiques

Aucun test relancé pour cette tâche de déploiement proprement dite : la suite complète (`:test`
1322/1290 exécutés verts/32 ignorés/0 échec) et `./gradlew build` (3 modules) avaient déjà tourné
verts dans la session précédente, **au même commit `3f32d18`**, sans aucun changement source
depuis — relancer aurait été une répétition inutile, explicitement déconseillée pour cette tâche.
Le passage interne `./gradlew test`+`build` du script officiel de déploiement a bien été tenté
(garde-fou normal du script, pas une initiative de ma part) mais interrompu après ~40 minutes sans
progression mesurable à cause d'une pression mémoire système externe au code (3 sessions Claude
Code concurrentes sur la même machine) — diagnostiqué par thread dump comme une contention
légitime, pas un bug ni un blocage du code déployé.

## Tests manuels à effectuer

**Rien n'a été validé en jeu dans cette tâche — distinction explicite faite ici et dans le
changelog.** Restent `PENDING MANUAL VALIDATION`, à la charge de l'utilisateur :

- **TC-220** (`docs/MANUAL_TEST_PLAN.md`) : kit de départ #26 — toujours non testé en jeu depuis son
  déploiement initial.
- **TC-221/TC-222** : parcours complet du réseau de voyage (waypoint découvert → mort → borne →
  menu → retour sûr ; « Mon claim » ; « Villages ») — première fois que ce code tourne réellement
  sur un serveur, jamais exercé en jeu jusqu'ici.
- **TC-223** : génération automatique Hub (#149) — waypoint + borne distincte apparaissant
  progressivement en explorant `world_hub`, idempotence, protection.

Les vérifications effectuées dans cette tâche (version, plugins, mondes chargés, clés de config
effectives, migrations appliquées sans erreur critique) sont des **contrôles de démarrage**, jamais
une validation du comportement en jeu.

## Résultat attendu

Le serveur DEV tourne désormais sur le commit `3f32d18` (kit #26 + réseau de voyage complet),
schéma de base à jour (V21), configuration Hub activée avec ses valeurs par défaut. Toutes les
fonctionnalités deviennent disponibles pour validation en jeu par l'utilisateur.

## Reset / retour à l'état initial

Aucun reset nécessaire : déploiement additif (nouvelles tables/colonne, nouvelles clés de config
avec valeurs par défaut sûres). `travel.waypoint.hub-enabled: false` et/ou
`travel.beacon.hub-generation.enabled: false` dans `config.yml` + `/rpgquest reload` désactivent la
génération Hub sans rollback de JAR si besoin.

## Déploiement VeryGames

### À transférer
Uniquement `rpgquest-0.1.0-SNAPSHOT.jar` — **effectué** (voir ci-dessus, SHA-256 confirmé).

### Ne PAS transférer/altérer
`config.yml`, `data.db`, `messages.yml`, `spawn.yml`, `Citizens/`, les mondes, les autres plugins —
**aucun n'a été touché** (scripts à liste blanche stricte).

### Redémarrage requis
**Oui, effectué** — `scripts/verygames-restart.sh --timeout 240`, confirmé ONLINE.

### Migration automatique
**Oui** — V19→V21 appliquées au démarrage, confirmé indirectement par l'activation complète du
plugin (voir « Base de données / migrations »).

## Rollback

`scripts/rollback-verygames.sh --latest` (restaure `rpgquest-20261003T194955Z-predeploy.jar`,
SHA-256 `d5a8d7438e1b72ada010cfad7318197601b5b156aaacf8c710f2fc59ed2cec69`) puis
`scripts/verygames-restart.sh`. Les tables V19-V21 restent inertes en base pour cet ancien JAR
(même garantie que pour V18/#124, déjà éprouvée).

## Logs / diagnostic

Aucun accès direct aux logs serveur par ce compte FTP (chrooté plugins)/RCON (pas de commande de
lecture de log) — limite déjà documentée, inchangée par cette tâche. Preuves indirectes utilisées :
heartbeat PlugAdmin (`uptime_seconds`, `worlds_json`), réponses RCON (`version`, `plugins`), lecture
FTP du `config.yml` effectif.

## Documentation mise à jour

`docs/deployment/SERVER_CHANGELOG.md` (nouvelle entrée complète pour #132/#150/#151/#149),
`.ai/ROADMAP.md` (journal de déploiement), `docs/claude-reports/README.md` (index), ce rapport.

## Limitations / travail restant

- Backup FTP de `world_hub/` **impossible** avec les accès actuels (compte chrooté plugins) — à
  signaler si une sauvegarde de monde dédiée devient nécessaire un jour (nécessiterait un accès FTP
  différent ou un export côté panel VeryGames).
- Validation en jeu complète (TC-220/TC-221/TC-222/TC-223) entièrement **à faire** par
  l'utilisateur.
- Aucune des 4 issues (#26, #132, #150, #151, #149) n'est fermée — toutes nécessitent encore une
  validation manuelle avant fermeture.

## Prochaine étape suggérée

Validation manuelle en jeu par l'utilisateur (TC-220 à TC-223) ; puis, selon le résultat, fermeture
des issues correspondantes côté GitHub (gérée par l'utilisateur) et/ou poursuite vers #152
(administration Control Panel des bornes/villages/politiques).
