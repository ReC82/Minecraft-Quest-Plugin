# Base de données propre au panel (PlugAdmin) — issue #43

> **Statut : préparation uniquement** (consigne propriétaire du 13/10/2026).
> `control-panel.db` (SQLite) reste la **source actuelle** des données du panel. Aucune migration
> ni bascule du panel n'a eu lieu ; elle exige une **nouvelle autorisation écrite** du propriétaire.
> Coordination : #42 (outil de migration des données RPGQuest), #335 (paramètres du jeu), #58.

## 1. Frontière de propriété des données

Deux domaines de persistance, deux propriétaires, aucune table partagée :

| Domaine | Propriétaire | Stockage actuel | Contenu |
|---|---|---|---|
| **RPGQuest** | plugin | `data.db` (SQLite) sur le serveur Minecraft ; MariaDB possible (#40/#41/#42) | joueurs, quêtes, stories, variables, claims, économie, progression, bindings PNJ… |
| **Panel** | backend PlugAdmin | `/var/lib/plugadmin/control-panel.db` (SQLite, mode 600, hôte AWS) | comptes et groupes du panel, audit, file d'actions de l'agent, brouillons, réglages |

Règles :

- Le panel **n'ouvre jamais** la base du plugin. Il lit l'état du jeu via le heartbeat de l'agent
  et l'API d'administration (pont `BridgeClient`), et le modifie **uniquement** par des actions
  métier explicites (`AgentActionType` / `AgentActionCatalog`), exécutées par le plugin qui garde
  récompenses, invariants, événements et audit.
- Le panel **ne reçoit pas** les identifiants SQL de RPGQuest pour son fonctionnement. La cible de
  migration de #42 (page `/database`) n'est qu'un **test de connexion** préparatoire, jamais utilisé
  pour lire ou écrire des données de jeu.
- Une API RPGQuest indisponible s'affiche comme telle (heartbeat ancien, agent hors ligne) : **aucun
  repli SQL caché**.
- Aucune table du panel ne constitue une seconde source de vérité d'une donnée de jeu (inventaire
  ci-dessous). Un cache éventuel reste explicitement non autoritatif.
- Historiques de migration séparés : le plugin a `rpgquest_schema_migrations` ; le panel n'en
  partagera jamais l'usage.

## 2. Inventaire de `control-panel.db`

Visible en lecture seule dans **PlugAdmin > Base de données > Base propre au panel**
(`PanelDbInventory`, ouverture `open_mode=1`).

| Table | Rôle | Nature |
|---|---|---|
| `panel_user`, `panel_group`, `panel_group_permission`, `panel_user_group` | comptes, groupes et droits du panel | propre au panel |
| `panel_user_minecraft`, `panel_group_mc_node` | lien compte panel ↔ joueur, nœuds de permission voulus | propre au panel (l'application en jeu passe par l'agent) |
| `audit_log` | journal d'audit du panel | propre au panel |
| `notification_read` | notifications lues | propre au panel |
| `agent_action` | file des actions envoyées à l'agent et leur résultat | propre au panel (demandes, pas l'état du jeu) |
| `agent_heartbeat` | **dernier** heartbeat par agent (upsert) | **cache non autoritatif** : la vérité reste le plugin |
| `ai_provider` | réglages des fournisseurs d'IA (clé API) | propre au panel, secret |
| `building_project`, `building_project_image`, `building_design_doc`, `building_design_run`, `building_plan_draft`, `building_plan_draft_revision`, `building_plan_draft_action`, `building_plan_request` | ateliers de bâtiment (brouillons, images, exécutions d'IA) | propre au panel ; un plan ne devient contenu de jeu que par publication explicite |
| `db_migration_target` | cible MariaDB de la migration RPGQuest (#42) | paramètres, mot de passe chiffré |
| `panel_db_target` | base MariaDB **propre au panel** (#43) | paramètres, mot de passe chiffré — **non utilisée** |

Hors base : les sessions du panel vivent en mémoire (`SessionStore`, perdues au redémarrage — choix
V1) ; les états du superviseur `lqorch` vivent dans son propre répertoire d'état. Les sauvegardes de
contenu (`content-backups/`) sont des fichiers à côté de la base.

## 3. Base MariaDB du panel — configuration préparée

Page **`/database`** (propriétaire, permission `DATABASE_CONFIGURE`), section « Base propre au
panel » :

- **Stockage actif** : toujours « SQLite — control-panel.db (actif) », avec l'inventaire.
- **Base MariaDB du panel** : badge « enregistrée — non utilisée ». Hôte, port, base, compte, TLS,
  mot de passe. Mêmes garanties que la cible #42 :
  - mot de passe chiffré AES-256-GCM, clé dérivée de `RPGQUEST_PANEL_SECRET` (hors base) avec un
    **usage distinct** (`panel-db-target-password`) : un chiffré de la cible RPGQuest ne s'ouvre pas
    pour celle du panel ;
  - jamais réaffiché (champ vide, `autocomplete="new-password"`) ; champ vide = conserver ;
    effacement séparé avec confirmation ;
  - CSRF, session, permission propriétaire ; audit `database.panelTarget.save|test|clearPassword`
    sans hôte ni secret ; messages d'erreur du pilote expurgés.
- **Refus** d'une base identique (même hôte, port et nom) à la cible RPGQuest, dans les deux sens.
- **Avertissement** si le compte SQL est le même que celui de la cible RPGQuest.
- **Test de connexion + isolement** (`MariaDbConnectionTester.forPanel()`) : session en lecture
  seule, délais bornés, métadonnées seulement. Échoue si le compte voit **une** base contenant
  `rpgquest_schema_migrations` (`information_schema` ne liste que ce sur quoi le compte a un droit) ;
  signale les autres bases visibles. **Connexion testée ≠ panel migré.**
- **Plan de migration** en 5 étapes ; la dernière (« Basculer le panel sur MariaDB ») est un bouton
  **désactivé** sans route serveur.

## 4. Où héberger la base du panel

La séparation des responsabilités est obligatoire ; l'emplacement ne l'est pas.

| Critère à vérifier | VeryGames MariaDB | AWS (local à PlugAdmin) |
|---|---|---|
| Accès depuis AWS | à vérifier (filtrage IP, hôte autorisé) | natif |
| TLS | à vérifier (`verify-full` souhaité) | loopback, inutile |
| Latence | aller-retour Internet à chaque requête | locale |
| Sauvegardes | celles de l'hébergeur + les nôtres | à notre charge |
| Quotas / connexions | limites de l'offre | celles de l'instance (3,8 Go de RAM) |

Le test de connexion de la page `/database` répond aux trois premières questions sans risque. Tant
que rien ne justifie la migration, **rester sur SQLite** : un seul processus écrit dans
`control-panel.db`, le volume est faible (~10 Mo, surtout des images de référence), et la base est
déjà hors Git, en mode 600, sur l'hôte du panel.

## 5. Prérequis de code avant toute migration du panel (non réalisés)

1. **Historique de migrations propre au panel** : une table `panel_schema_migrations` et un
   migrateur séquentiel ; aujourd'hui chaque store crée ses tables (`CREATE TABLE IF NOT EXISTS`,
   26 instructions) et ajoute des colonnes après `PRAGMA table_info` (3 cas).
2. **Accès SQL portable** : les stores ouvrent `jdbc:sqlite:` eux-mêmes. Il faut une source de
   connexions unique (pool) et un petit dialecte pour les écarts relevés : `INSERT OR IGNORE` (8),
   `ON CONFLICT … DO UPDATE` (4), `PRAGMA busy_timeout` (6), `PRAGMA table_info` (3), `BLOB`
   (images), clés `TEXT` (à borner en `VARCHAR` pour les index MariaDB, cf. #354).
3. **Outil de copie** `control-panel.db` → MariaDB : même principe que `rpgquest-db-migration.jar`
   (#42) — source en lecture seule, cible vide obligatoire, une transaction, comptages + empreintes
   par table, jamais de `DROP`/`DELETE`.
4. **Configuration** : `panel.db.type=sqlite|mariadb` dans `control-panel.properties`, mot de passe lu
   depuis la cible chiffrée ou l'environnement ; défaut `sqlite`.

Chacun de ces points est un ticket à part ; aucun n'est à faire sans décision du propriétaire.

## 6. Procédure de migration future (runbook)

Pré-requis : prérequis §5 livrés, test de connexion + isolement « OK » sur la base cible,
autorisation écrite du propriétaire.

1. **Sauvegarde** (service en marche, sauvegarde en ligne cohérente) :
   `sudo -u plugadmin sqlite3 /var/lib/plugadmin/control-panel.db ".backup '/var/lib/plugadmin/control-panel.db.bak-43-<horodatage>'"`.
   Ne jamais écraser une sauvegarde existante.
2. **Répétition** sur une copie et une MariaDB jetable (cf. mémoire « MariaDB locale jetable ») :
   copie, comparaison des comptages/empreintes, démarrage d'une instance locale du panel sur la base
   copiée, connexion, lecture de l'audit et des brouillons.
3. **Fenêtre** : arrêter `plugadmin` (le jeu n'est pas affecté ; l'agent met ses heartbeats en échec
   le temps de l'arrêt), recopier, valider (go/no-go = toutes les tables « OK »).
4. **Bascule** : `panel.db.type=mariadb`, redémarrer, vérifier `/health`, connexion, audit, `/database`.
5. **Surveillance** 24 h : erreurs SQL dans `journalctl -u plugadmin`.

Interruption estimée : quelques minutes du panel seul ; aucune pour le serveur Minecraft.

## 7. Rollback

- Avant bascule : rien à défaire (la cible enregistrée est inerte ; effacer son mot de passe depuis
  `/database` si besoin).
- Après bascule : remettre `panel.db.type=sqlite` et redémarrer : `control-panel.db` n'a pas été
  modifié par la copie. **Limite** : ce qui a été écrit dans MariaDB après la bascule (audit,
  brouillons, actions) n'existe pas dans le fichier SQLite ; l'exporter avant si nécessaire.
- Restaurer la sauvegarde §6.1 si le fichier a été altéré.

## 8. Tests

Automatiques (`./gradlew :control-panel:test`) :

- `DbTargetStoreTest` — cibles indépendantes (tables, usages de chiffrement), inventaire en lecture
  seule qui ne crée jamais de fichier.
- `DatabaseTargetPageTest` — enregistrement et test séparés, mot de passe jamais renvoyé, ni en
  clair dans la base ni dans l'audit, base identique à RPGQuest refusée, compte partagé signalé,
  effacement séparé, bascule du panel désactivée.
- `MariaDbConnectionTesterTest#panelIsolationIsCheckedOnRealServer` — **optionnel**, contre une vraie
  MariaDB (`RPGQUEST_PANEL_DB_*` et `RPGQUEST_DB_*`) : un compte limité à la base du panel passe ; le
  compte de la base RPGQuest échoue (« Isolement non respecté »).

Manuels : voir le rapport de session (`PENDING MANUAL VALIDATION`).
