# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-14
* Heure : 10:50
* Sujet : Issue #43 — base de données propre au panel PlugAdmin, sans dupliquer les données métier RPGQuest — **préparation uniquement**
* Statut : DONE (préparation livrée et déployée sur le panel ; aucune migration ni bascule, consigne propriétaire)
* Branche Git : `feature/43-panel-database-target` (PR #361, fusionnée `de5ced5`) → `chore/257-ci-github-actions` ; rapport : `docs/43-rapport`
* Commit actuel si disponible : `de5ced5`
* Début de la tâche : 2026-10-14 10:17:50
* Fin de la tâche : 2026-10-14 10:50:30
* Durée totale : 00:32:40

## Demande
Ticket #43, traité par une session autonome `lqorch`. La consigne propriétaire du 13/10/2026 prime :
- **conserver `control-panel.db`** comme source actuelle, séparation stricte d'avec le gameplay, aucun
  transfert obligatoire vers MariaDB ;
- préparer dans PlugAdmin l'administration d'un backend SQL propre au panel : formulaire (serveur,
  port, base, compte, mot de passe masqué), test de connexion non destructif ;
- secret jamais réaffiché (champ vide = conserver, rotation explicite), masqué partout, droits
  administrateur, audit sans révélation, CSRF ;
- aucune connexion du panel à la base du plugin ; environnement actif distinct d'une configuration
  enregistrée **non utilisée** ;
- plan de migration, tests avec base de test, rollback, documentation ; **ne rien migrer ni basculer**.

## Analyse
- **Existant (#42).** La page `/database` gérait déjà une cible MariaDB pour les données RPGQuest :
  `DbTargetStore` (table `db_migration_target`), `SecretBox` (AES-256-GCM, clé dérivée de
  `RPGQUEST_PANEL_SECRET`), `MariaDbConnectionTester` en lecture seule. On étend ces abstractions
  plutôt que de les dupliquer.
- **Persistance actuelle du panel.** 20 tables dans `control-panel.db`, toutes propres au panel. La
  seule qui reflète le jeu, `agent_heartbeat`, ne garde que le **dernier** heartbeat par agent
  (upsert) : c'est un cache non autoritatif. Les sessions vivent en mémoire. Aucune table métier
  RPGQuest n'est dupliquée, et le panel n'ouvre jamais `data.db` : il passe par l'agent et le pont.
- **Ce qui empêche aujourd'hui de migrer le panel** (documenté, non réalisé) :
  - chaque store ouvre lui-même `jdbc:sqlite:` et crée ses tables (26 `CREATE TABLE IF NOT EXISTS`) ;
  - il n'existe pas d'historique de migrations propre au panel ;
  - le SQL est spécifique à SQLite : `INSERT OR IGNORE` ×8, `ON CONFLICT` ×4, `PRAGMA` ×9, `BLOB`.
- **Décisions :**
  - un enum `DbTargetKind` (MIGRATION / PANEL), où chaque cible a sa propre table et son propre usage
    de chiffrement ;
  - un contrôle d'isolement fondé sur `information_schema`, qui ne liste que les objets sur lesquels
    le compte a un droit ;
  - le refus d'une base identique à l'autre cible.

## Travail effectué
- **`DbTargetKind`** : table et usage `SecretBox` par cible. `DbTargetStore` est paramétré par la
  cible ; le constructeur d'origine reste celui de MIGRATION, donc #42 est inchangé. Un chiffré
  scellé pour une cible ne s'ouvre pas pour l'autre.
- **`MariaDbConnectionTester.forPanel()`** : même session en lecture seule, plus le contrôle
  d'isolement.
  - **Échec** si le compte voit une base contenant `rpgquest_schema_migrations`.
  - Les autres bases visibles sont signalées, sans bloquer le test.
- **`PanelDbInventory`** : liste les tables de `control-panel.db` avec leur nombre de lignes.
  Ouverture en `open_mode=1` : la base n'est jamais créée ni modifiée.
- **`/database`, section « Base propre au panel »** :
  - **Stockage actif** : « SQLite — control-panel.db » (badge actif), avec l'inventaire.
  - **Base MariaDB du panel** : badge « enregistrée — non utilisée ». Formulaire avec le champ caché
    `scope=panel` et les mêmes règles de mot de passe que #42 : jamais réaffiché, vide = conserver,
    effacement séparé avec confirmation.
  - **Contrôles** : une base identique à la cible RPGQuest (même hôte, port et nom) est refusée, dans
    les deux sens. Un compte identique est seulement signalé.
  - **Plan de migration** en 5 étapes. L'étape « Basculer le panel sur MariaDB » est un bouton
    désactivé ; aucune route ne l'effectue.
- **`PanelApp`** :
  - même handler pour les deux cibles, avec un store et un testeur choisis selon `scope` ;
  - audit `database.panelTarget.save|test|clearPassword`, sans hôte ni secret ;
  - CSRF et permission `DATABASE_CONFIGURE` (propriétaire) inchangés.
- **Documentation** : `docs/deployment/PANEL_DATABASE.md` (nouveau) couvre :
  - la frontière de propriété ;
  - l'inventaire classé ;
  - le choix d'hébergement (VeryGames ou AWS) ;
  - les prérequis de code ;
  - le runbook (sauvegarde `.backup` en ligne, répétition, fenêtre, go/no-go) ;
  - le rollback et ses limites ;
  - les tests.
- **Contrôle visuel local** : instance jetable du panel (port 8097, base temporaire) et Playwright, en
  1280 et 390 px. Rendu correct ; aucun des deux mots de passe saisis n'apparaît dans le HTML ;
  l'avertissement de compte partagé et l'échec expurgé (`<hôte>`) s'affichent. Instance arrêtée et
  fichiers supprimés.

## Fichiers créés
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/dbtarget/DbTargetKind.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/dbtarget/PanelDbInventory.java`
- `docs/deployment/PANEL_DATABASE.md`

## Fichiers modifiés
- **Code du panel** (`panel/dbtarget/`, `panel/web/`) :
  - `DbTargetStore.java`, `MariaDbConnectionTester.java`, `DbTargetPages.java`
  - `PanelApp.java`
- **Tests du panel** :
  - `DbTargetStoreTest.java` (+2), `MariaDbConnectionTesterTest.java` (+1, optionnel avec une vraie
    MariaDB)
  - `DatabaseTargetPageTest.java` (+4)
- **Fiche d'aide du panel** : `control-panel/src/main/resources/docs/base-de-donnees.md`
- **Documentation** : `docs/PERSISTENCE.md` (§14), `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`,
  `docs/deployment/SERVER_CHANGELOG.md`

## Base de données / migrations
- **Plugin** : aucune migration, schéma V35 inchangé, `data.db` non touché.
- **Panel** : nouvelle table `panel_db_target`, créée au démarrage (`CREATE TABLE IF NOT EXISTS` + une
  ligne vide). Vérifié sur l'hôte : 1 ligne, hôte vide.
- **Aucune migration ni bascule** du panel ou du jeu. Aucune base VeryGames contactée.

## Configuration / données
- Aucune nouvelle clé de configuration. Le chiffrement réutilise `RPGQUEST_PANEL_SECRET`, avec l'usage
  `panel-db-target-password`.
- Aucun secret dans Git, les logs ou ce rapport. Les mots de passe de la MariaDB jetable ont été
  générés aléatoirement dans un fichier en mode 600, puis supprimés.

## Tests automatiques
- **Ciblés, en local** (`:control-panel:test`) :
  - `DbTargetStoreTest` 8/8, `DatabaseTargetPageTest` 13/13 ;
  - `DocsPagesTest` 8/8, `DiagnosticHelpTest` 7/7.
- **`MariaDbConnectionTesterTest` 3/3 contre une MariaDB 10.11.14 jetable locale** (espace
  utilisateur, port 33307 en loopback, détruite ensuite) :
  - compte limité à la base du panel → OK, « isolement vérifié » ;
  - compte de la base RPGQuest → échec, « Isolement non respecté ».
- **CI GitHub sur la PR #361** : plugin, control-panel, web-api, scripts et artefacts **verts**.
- **Build** : `:control-panel:build` vert en local ; build complet dans la CI.

## Tests manuels à effectuer
`PENDING MANUAL VALIDATION` — **PlugAdmin DEV > Base de données**, section « Base propre au panel » :
1. Vérifier que le stockage actif affiche « SQLite — control-panel.db » et que l'inventaire liste
   environ 21 tables.
2. Si une base dédiée au panel existe (VeryGames ou AWS), saisir ses paramètres, puis « Enregistrer et
   tester ». Attendu : « isolement vérifié ».
3. Contre-épreuve : avec le compte RPGQuest, attendu « Isolement non respecté ».
4. Vérifier que le mot de passe n'est jamais réaffiché et qu'un champ vide le conserve.
5. Vérifier que « Basculer le panel sur MariaDB » est grisé.

Aucun test Minecraft n'est requis : aucun changement de gameplay.

## Résultat attendu
- La frontière de propriété des données est documentée et visible dans le panel.
- Le panel garde sa propre persistance et une base dédiée peut être préparée et testée.
- Aucune migration n'est possible depuis le panel sans un nouveau développement et une autorisation
  du propriétaire.

## Reset / retour à l'état initial
Effacer le mot de passe de la base du panel depuis `/database`. La table `panel_db_target` est inerte.

## Déploiement VeryGames
**Aucun déploiement VeryGames** : le JAR RPGQuest ne change pas.

**Panel déployé** sur l'hôte AWS à 10:49 avec `scripts/plugadmin/deploy.sh --wait-ci` (CI verte sur
`de5ced5`) :
- l'ancienne application est sauvegardée sous `/opt/plugadmin/releases/20261014-104221` ;
- `/health` OK ;
- `DEPLOYED` indique `commit=de5ced5` ;
- le JAR contient `DbTargetKind`, `PanelDbInventory` et la fiche mise à jour ;
- `/database` répond 303 → `/login`.

### À transférer
Rien.

### Ne PAS transférer/altérer
`control-panel.db` (hors création automatique de la table), `data.db`, `config.yml`, `database.type`,
toute base MariaDB.

### Redémarrage requis
Panel seulement, fait par `deploy.sh`. Serveur Minecraft : non.

### Migration automatique
Création de `panel_db_target` au démarrage du panel.

## Rollback
`scripts/plugadmin/rollback.sh app` restaure `/opt/plugadmin/releases/20261014-104221` (= `048e0f2`).
La table `panel_db_target` est ignorée par cette version.

## Logs / diagnostic
- `~/.local/state/rpgquest-logs/panel-43.log` ;
- audit du panel `database.panelTarget.*` ;
- `journalctl -u plugadmin`.

## Documentation mise à jour
- `docs/deployment/PANEL_DATABASE.md` (nouveau)
- `PERSISTENCE.md` §14, `RPGQUEST_BIBLE.md`, `current_state.md`, `SERVER_CHANGELOG.md`
- fiche du panel `base-de-donnees.md`

## Suites possibles (non faites, décision du propriétaire)
- Historique de migrations propre au panel et couche SQL portable (prérequis §5 du runbook).
- Outil de copie de `control-panel.db` vers MariaDB.
- Sauvegarde automatique datée de `control-panel.db` dans `deploy.sh` : aujourd'hui seule
  l'application est sauvegardée.
