# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-12
* Heure : 16:44 (heure locale, CEST)
* Sujet : déploiement DEV de la refonte #265 et de ses dépendances (JAR + PlugAdmin)
* Statut : **PARTIAL** — PlugAdmin déployé et vérifié ; **JAR non transféré** : certificat FTPS de
  VeryGames expiré, aucun contournement autorisé
* Branche Git : `chore/257-ci-github-actions`
* Commit actuel si disponible : `c5da469` (code `63f7b19`, CI verte, run 38264168002)
* Début de la tâche : 2026-10-12 16:40:31
* Fin de la tâche : 2026-10-12 16:44:25
* Durée totale : 00:03:54

## Demande

Déployer le JAR RPGQuest et PlugAdmin depuis le commit d'intégration validé par GitHub Actions :
vérifier la CI, sauvegarder, vérifier l'absence de joueurs avant redémarrage, ne modifier aucun
contenu ; après : empreintes, ONLINE, accès Atelier / Bibliothèque / Emplacements, moteur 3D,
préparer TC-284 à TC-289. Aucun appel OpenAI, aucune restauration de `buildsite_0012`. Pas de
contournement TLS : signaler avant de poursuivre si le certificat bloque.

## Analyse

- Arbre propre (worktree dédiée ; le dépôt principal porte des fichiers non commités du propriétaire).
- Sur-ensemble : JAR déployé `1fb05da` et panel déployé `f5f8eaf` sont ancêtres de `c5da469`.
- CI : `c5da469` ne contient que le rapport #265 (docs, pas de run) ; le code est `63f7b19`, run push
  **vert** (le script de déploiement a attendu sa fin).
- **Certificat VeryGames** : `CN = *.dg.vg`, `notAfter = 2026-10-11 05:12:03 UTC`, curl :
  `(60) certificate has expired`. Le certificat du serveur lui-même est expiré (pas notre chaîne) :
  rien à corriger de notre côté.

## Travail effectué

1. Sauvegarde de `control-panel.db` (`.backup` SQLite, `integrity_check` = ok) :
   `/opt/plugadmin/backups/control-panel-20261012-164117-pre265.db`.
2. `scripts/plugadmin/deploy.sh --wait-ci` : ancienne app sauvegardée
   (`/opt/plugadmin/releases/20261012-164124`), nouvelle app installée, service redémarré à 16:43:05.
   Le `/health KO` du script est la course connue (sondage 2 s après le redémarrage) ; revérifié :
   `{"panel":"ONLINE"}`, et l'agent du plugin interroge de nouveau le panel.
3. Vérification de ce qui est **servi** : JAR installé = JAR construit
   (`335dc7e1ecd9fb01eab363efdce1259962cc9776ce5470f6929a621ab3cd1215`) ; classes `WorkshopPages`,
   `IsometricView`, `BuildingVoxels`, `AnchorMeaning`, `BuildingProjectStore` présentes ;
   `/buildings/workshop`, `/buildings/library`, `/buildings/sites`, `/buildings/plan` → 303 vers
   `/login` (routes enregistrées ; une route inexistante → 404). Migration additive appliquée :
   table `building_project`, colonne `project_id` ; les 2 brouillons existants sont intacts.
4. JAR construit depuis le même commit, **non transféré** : 2 349 597 octets,
   `af0a911890d0f4a2571eaf15f1840b209758caeadf9df34a9ed3d33aedb08016` (classes de l'aperçu 3D et du
   refus serveur vérifiées dans l'archive).
5. Minecraft : RCON `list` → en ligne, **1 joueur (LoDyMcFly)**. **Aucun redémarrage** (aucun JAR
   à charger). Une sonde `--help` envoyée par erreur à la console via RCON a été refusée comme
   commande inconnue — sans effet.

Aucun contenu modifié, aucun appel IA, aucune restauration.

## Fichiers créés

- ce rapport

## Fichiers modifiés

- `docs/deployment/SERVER_CHANGELOG.md` (déploiement PARTIEL, procédure de reprise)
- `docs/claude-reports/README.md`

## Base de données / migrations

`control-panel.db` : migration additive au démarrage du panel (voir plus haut). `data.db` : intacte.

## Configuration / données

Aucune.

## Tests automatiques

Aucun relancé : CI verte sur `63f7b19` (validation du script). Contrôles post-déploiement ci-dessus.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — faisables **maintenant** (panel seul) :

1. **TC-286** (à faire en premier) : Atelier → « Reprendre dans l'Atelier » `buildsite_0009`.
2. **TC-284** historique, **TC-285** (sans restauration réelle), **TC-287** Emplacements /
   « Placer sur un emplacement », **TC-289** ancre.

**Bloqués jusqu'au JAR** : **TC-288** (aperçu 3D — d'ici là l'écran dit qu'il faut une version plus
récente du plugin), le refus **serveur** de supprimer un emplacement occupé (le panel le refuse déjà),
et les correctifs plugin #263, #248, #216.

Le contrôle des pages derrière la connexion n'a pas été fait par moi (pas de compte de vérification
créé) : leur rendu est couvert par les tests HTTP, l'accès réel est l'étape 1 de TC-286.

## Résultat attendu

Atelier, Bibliothèque et Emplacements nouveaux, utilisables dès maintenant dans PlugAdmin ; aperçu 3D
et refus serveur au déploiement du JAR.

## Reset / retour à l'état initial

Panel : `scripts/plugadmin/rollback.sh app` (release `20261012-164124`) ; base :
`control-panel-20261012-164117-pre265.db`.

## Déploiement VeryGames

### À transférer

Le JAR `af0a9118…` (même commit), dès que VeryGames aura renouvelé son certificat.

### Ne PAS transférer/altérer

`data.db`, `config.yml`, mondes, emplacements, bâtiments, PNJ, dialogues, quêtes.

### Redémarrage requis

Oui, après le transfert du JAR, avec **0 joueur** en ligne.

### Migration automatique

Aucune côté plugin.

## Rollback

Panel : voir plus haut. JAR : inchangé sur le serveur (`1fb05da`).

## Logs / diagnostic

`~/.local/share/rpgquest/logs/panel-deploy-265.log` ; `journalctl -u plugadmin`.

## Documentation mise à jour

`SERVER_CHANGELOG.md`.

## Limitations / travail restant

JAR bloqué par le certificat expiré de VeryGames ; reprise décrite dans `SERVER_CHANGELOG.md`.

## Prochaine étape suggérée

TC-286 tout de suite ; signaler le certificat expiré à VeryGames ; transférer le JAR à son
renouvellement (nouveau feu vert si vous le souhaitez), puis TC-288.
