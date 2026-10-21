# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-21
* Heure : 12:35
* Sujet : #18 — publication de la préparation TC-345 : 3 prix démo de fabrication + Lucien « Ouvrir l'atelier » → `artisan_demo`
* Statut : PARTIAL (publié et vérifié sur fichier ; relecture par le plugin impossible, DEV arrêté ; TC-345 PENDING MANUAL VALIDATION)
* Branche Git : rapport `docs/18-atelier-publication` ; aucun code modifié
* Commit actuel si disponible : `a42c8126` (base déployée, `chore/257-ci-github-actions`)
* Début de la tâche : 2026-10-21 12:28:11
* Fin de la tâche : 2026-10-21 12:36:30
* Durée totale : 00:08:19

## Demande
Session autonome lqorch. Appliquer l'autorisation propriétaire du 20/10 (« Autorisation #18 » du registre) :
créer **exactement** les 3 prix démo proposés (`demo_recette_ingredients`, `demo_recette_pieces`, `demo_recette_mixte`),
raccorder Lucien (Citizens n°14) à `rpgquest:artisan_demo` par « Ouvrir l'atelier » en préservant son dialogue,
avec sauvegarde, comparaison, écriture ciblée, relecture et rollback prêt. Aucun test d'artisanat réel.

## Analyse
- Aucun commentaire du propriétaire sur #18 depuis la proposition ; l'accord est celui du registre (« Autorisation #18 »).
- **DEV arrêté** : dernier signal agent le 20/10 à 23:02 UTC (`agent_heartbeat`). L'action `price.save` (Prix et coûts)
  et la publication de dialogue PlugAdmin exigent le plugin en ligne : impossibles. Même situation que #17 ce matin.
- Lecture FTP de DEV (avant toute écriture) : `RPGQuest/prices.yml` **absent** (aucune collision possible) ;
  `dialogues/lucien.yml` = squelette « Salut ! » / « Au revoir » (SHA-256 `f9055ebe…`) ; `merchants/artisan_demo.yml`
  cite déjà les 3 clés. Constats du 20/10 toujours exacts.
- Voie retenue (identique à #17) : script officiel `deploy-verygames.sh --also` — sauvegarde individuelle, manifeste,
  rollback outillé. Pour rester fidèle à « via Prix et coûts », `prices.yml` a été **produit par le vrai éditeur**
  `PriceCatalogEditor.savePrice` (trois créations successives, comme trois enregistrements du formulaire), puis relu
  par `PriceCatalogLoader`. Le fichier est donc celui que PlugAdmin aurait écrit (en-tête compris).
- Lucien : la version DEV est reprise telle quelle, avec un seul ajout (le choix proposé, avant « Au revoir »).
  Le fichier n'est **pas** ajouté au dépôt (le propriétaire a un `lucien.yml` non suivi dans son arbre : l'ajouter
  au dépôt créerait un conflit chez lui).
- JAR : le script retransfère toujours le JAR ; `a42c8126` n'ajoute que de la documentation à `2c093d5e` (CI verte),
  le JAR construit a le même SHA-256 que celui en ligne (`eda37e2b…`).

## Travail effectué
1. Téléchargement FTP en lecture : liste `RPGQuest/`, `dialogues/`, `merchants/`, `lucien.yml`, `artisan_demo.yml`.
2. Fichiers cibles dans `~/rpgquest-build-logs/18-dl/out/` :
   - `prices.yml` généré par un test JUnit **temporaire** (`TmpApply18Test`, supprimé, jamais commité) via
     `PriceCatalogService.editor().savePrice(...)` ; rechargement : 0 erreur, 1 avertissement « 3 prix démonstration » ;
     3 prix utilisables : « 1 × BREAD fabriqué contre 3 × WHEAT », « 1 × LANTERN fabriqué contre 12 pièce(s) »,
     « 1 × IRON_SWORD fabriqué contre 2 × IRON_INGOT + 1 × STICK + 5 pièce(s) » ;
   - `lucien.yml` = DEV + diff ci-dessous ; `DialogueLoader` : 1 chargé, 0 problème.
3. `RPGQUEST_TEST_MAX_HEAP=768m scripts/deploy-verygames.sh -y --allow-no-backup --also …/prices.yml:RPGQuest/prices.yml --also …/lucien.yml:RPGQuest/dialogues/lucien.yml`
   → `DEPLOY_EXIT=0`, « Déploiement terminé ». `--allow-no-backup` seulement parce que `prices.yml` n'existait pas.
4. Relecture FTP après transfert : `prices.yml` `9f17832e…`, `lucien.yml` `5b5a7c2e…` = local ; JAR `eda37e2b…` = construit ;
   `artisan_demo.yml` octet pour octet inchangé.
5. Pas de redémarrage : DEV déjà arrêté ; rien lancé d'autre.

Diff du dialogue :
```diff
     choices:
+      - text: "Ouvrir l'atelier"
+        actions:
+          - type: OPEN_MERCHANT
+            merchant: rpgquest:artisan_demo
       - text: "Au revoir"
```

## Fichiers créés
- `docs/claude-reports/2026-10-21_1235_18-atelier-lucien-publication.md`

## Fichiers modifiés
- `docs/SUIVI_VALIDATIONS_DEV.md` (ligne 11b #18 → « À tester »)
- `docs/deployment/SERVER_CHANGELOG.md`
- `docs/MANUAL_TEST_PLAN.md` (TC-345 : préparation appliquée)
- `docs/claude-reports/README.md` (index)

## Base de données / migrations
Aucune.

## Configuration / données
Fichiers DEV écrits : `plugins/RPGQuest/prices.yml` (créé), `plugins/RPGQuest/dialogues/lucien.yml`. Aucun catalogue
(`merchants/*`), autre prix, offre, PNJ, solde, progression, inventaire ni autre dialogue touché.

## Tests automatiques
- Test temporaire `TmpApply18Test` (`./gradlew :test --tests …`, `RPGQUEST_TEST_MAX_HEAP=768m`) : vert, puis supprimé.
- Aucun code modifié ; PR documentation seule (aucune CI déclenchée). JAR déployé couvert par la CI verte de `2c093d5e`.

## Tests manuels à effectuer
**PENDING MANUAL VALIDATION — TC-345**, après démarrage de DEV par le propriétaire :
1. PlugAdmin → Prix et coûts : 3 prix `demo_recette_*` (démonstration, actifs) ; Marchands → Rafraîchir :
   `artisan_demo` sans prix indisponible, « Ouvert par : dialogue « rpgquest:lucien » ».
2. `world_hub` ≈ 722 66 -719, Lucien → « Ouvrir l'atelier » → « Atelier de l'artisan », 3 recettes.
3. Épée avec 1 seul lingot → refus, rien pris ; pain → −3 blés, +1 pain, solde inchangé ; lanterne en double clic →
   une seule, −12 ; épée → −2 lingots −1 bâton, +1 épée, −5 ; inventaire plein + lanterne → refus, rien pris.
   Parcours complet : commentaire #18 du 20/10.

## Résultat attendu
Lucien propose « Ouvrir l'atelier » et « Au revoir » ; l'atelier fabrique selon les trois prix, sans duplication.

## Reset / retour à l'état initial
Désactiver les 3 prix dans PlugAdmin → Prix et coûts ; retirer le choix de Lucien (rollback ci-dessous).

## Déploiement VeryGames
### À transférer
Fait : JAR (même code) + `RPGQuest/prices.yml` + `RPGQuest/dialogues/lucien.yml`.
### Ne PAS transférer/altérer
`merchants/*.yml`, définitions PNJ, `data.db`, autres dialogues, `config.yml`.
### Redémarrage requis
Oui, pour charger prix et dialogue : DEV est arrêté, il les chargera à son prochain démarrage (par le propriétaire).
### Migration automatique
Aucune.

## Rollback
- Prix : désactivation dans PlugAdmin, ou suppression de `RPGQuest/prices.yml` (absent avant ce lot).
- Dialogue : `scripts/rollback-verygames.sh --also ~/.local/share/rpgquest/verygames-backups/extra-20261021T103112Z/RPGQuest/dialogues/lucien.yml:RPGQuest/dialogues/lucien.yml` puis redémarrage.
- JAR (inutile, même code) : `scripts/rollback-verygames.sh --latest` (`rpgquest-20261021T103112Z-predeploy.jar`).

## Logs / diagnostic
`~/rpgquest-build-logs/deploy-18-20261021.log` ; manifeste `extra-20261021T103112Z/MANIFEST.txt` ;
fichiers : `~/rpgquest-build-logs/18-dl/` (`lucien.dev-backup.yml`, `out/`, `verify/`).

## Documentation mise à jour
Registre DEV, changelog serveur, TC-345, index. Bible / docs-site : aucune nouvelle commande ni syntaxe.

## Limitations / travail restant
- Relecture par le plugin (`price.catalog.status`, `merchant.catalog.status`) à faire dès que DEV est en ligne.
- Ticket #18 laissé ouvert jusqu'à TC-345 ; décisions toujours ouvertes (lien `recipes/*.yml`, conditions, vraies valeurs).

## Prochaine étape suggérée
Propriétaire : démarrer DEV puis TC-345. Session suivante : relecture agent quand DEV répond.
