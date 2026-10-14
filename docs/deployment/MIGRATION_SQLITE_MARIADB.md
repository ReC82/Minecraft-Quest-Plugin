# Migration contrôlée SQLite → MariaDB (issue #42)

> **Statut : PRÉPARATION UNIQUEMENT.** Consigne propriétaire du 13/10/2026 : aucune migration
> réelle, aucune bascule de la base active (DEV ou production), aucune modification de
> `database.type`, aucun import des données réelles dans une MariaDB active, sans consigne
> explicite ultérieure. **#58 reste en attente.** Ce document décrit l'outil, la répétition sur
> copie isolée et le runbook *futur* ; la partie « Exécution » n'est **pas autorisée** aujourd'hui.

Voir aussi : [PERSISTENCE.md](../PERSISTENCE.md) (moteurs, dialecte, migrations de schéma),
[VERYGAMES.md](VERYGAMES.md) § « Base MySQL/MariaDB VeryGames » (création de la base et du compte).

---

## 1. Audit du schéma (état réel, schéma V34)

Audit fait sur une **copie** de la sauvegarde DEV du 13/10/2026 (`20261013-predeploy-325`, schéma
V33, recopiée puis portée en V34 par le catalogue du plugin sur une seconde copie — la sauvegarde
elle-même n'est jamais ouverte en écriture).

| Élément | Constat | Traitement par l'outil |
|---|---|---|
| Tables | 39 tables utilisateur + `sqlite_sequence` | lues dans `sqlite_master` : aucune liste codée en dur |
| Clés primaires | toutes les tables en ont une (simple ou composée) | ordre de lecture stable ; contrôle d'unicité « PAD SPACE » côté MariaDB |
| Clés étrangères | toutes vers `player_profiles(uuid)`, plus `claim_members → claims(id)` | import par **tri topologique** (parents d'abord) ; `PRAGMA foreign_key_check` à la source ; recherche d'orphelins dans la cible |
| Index | index simples et `UNIQUE` (waystones, waypoints, encounter_sites…) | recréés par le catalogue de migrations du plugin, pas par l'outil |
| `AUTOINCREMENT` | 8 tables (`transactions`, `market_listings`, `npc_ids`, `building_site_ids`, `backpack_*`, `building_baselines`, `building_placement_history`) | identifiants copiés **tels quels** ; séquence alignée (`sqlite_sequence.seq + 1` → `AUTO_INCREMENT`) pour qu'un identifiant supprimé ne soit **jamais réattribué** |
| Types réels (`typeof`) | conformes aux types déclarés sur la copie DEV | contrôle colonne par colonne : SQLite accepte n'importe quoi, MariaDB strict non |
| UUID | texte canonique 36 caractères | copiés à l'identique ; valeur non-UUID signalée en avertissement |
| Horodatages | chaînes ISO-8601 (`TEXT`) | aucune conversion (pas de `DATETIME`) — aucun risque de fuseau |
| JSON / texte libre | `quest_progress.progress_data`, descriptions, briefs IA | copiés à l'identique ; **longueur contrôlée contre la taille de colonne cible** |
| Réels | `village_centers` (`REAL`) | `REAL` = `DOUBLE` MariaDB ; empreinte par valeur décimale |
| Binaire | `backpacks.contents`, `market_listings.item_data` (`BLOB`) | `LONGBLOB` ; empreinte sur les octets |
| Orphelins existants | aucun sur la copie DEV | un orphelin = constat critique (MariaDB le refuserait) |

### Transformations réellement nécessaires SQLite → MariaDB

Aucune transformation de **valeur** : l'outil copie chaque valeur telle quelle, les différences de
types sont portées par le schéma que crée le dialecte `MySqlDialect` (`INTEGER` → `BIGINT`,
`BLOB` → `LONGBLOB`, `TEXT` clé/`NOT NULL` → `VARCHAR(191)`, `utf8mb4_bin`). Les seuls ajustements
sont **structurels** : séquences `AUTOINCREMENT` et contrôle des contraintes que MariaDB applique
plus strictement que SQLite (longueur, type réel, collation PAD SPACE).

### ⚠️ Constat bloquant (NO-GO actuel)

`MySqlDialect` traduit **tout** `TEXT NOT NULL` déclaré dans un `CREATE TABLE` en `VARCHAR(191)`.
Or des colonnes de **texte libre** sont déclarées ainsi — `building_sites.description` contient
déjà **490 caractères** sur la copie DEV. Le dry-run MariaDB s'arrête donc en pré-contrôle :

```
[CRITICAL] building_sites.description — valeur de 490 caractères, la colonne cible est VARCHAR(191) :
elle serait refusée (mode strict). Correction de schéma nécessaire avant migration
```

Ce n'est pas un défaut de l'outil : c'est exactement ce qu'il doit attraper. Le même défaut
toucherait le plugin lui-même s'il tournait sur MariaDB (description > 191 caractères refusée à
l'écriture). Correction de schéma MariaDB à décider et faire **avant** toute migration réelle —
ticket dédié **#354**. En attendant, la répétition sur MariaDB d'une copie
DEV n'est concluante qu'avec cette colonne tronquée dans une copie jetable (essai technique, §5).

---

## 2. L'outil

Outil Java **hors serveur Minecraft** (jamais une commande en jeu) : paquet
`com.lodygames.rpgquest.database.migration`, JAR autonome avec ses pilotes.

```bash
./gradlew dbMigrationToolJar          # → build/tools/rpgquest-db-migration.jar
```

Il réutilise **le même catalogue de migrations** (`SchemaMigrator.ALL`), **le même dialecte** et
**le même moteur** (`MySqlDatabaseEngine`, mode SQL strict) que le plugin : la cible obtenue est
exactement celle que le plugin créerait au démarrage.

### Commandes

```bash
J=build/tools/rpgquest-db-migration.jar
# 1. Contrôles en lecture seule (source ; cible si fournie : joignable, dédiée, vide, version)
java -jar $J audit   --sqlite-file data.db [--target-mysql cible.properties]
# 2. Répétition complète, transaction TOUJOURS annulée (mode par défaut)
java -jar $J dry-run --sqlite-file data.db --target-mysql cible.properties
java -jar $J dry-run --sqlite-file data.db --target-sqlite repetition.db
# 3. Migration réelle — NON AUTORISÉE aujourd'hui
java -jar $J migrate --sqlite-file data.db --target-mysql cible.properties --commit
```

Option `--report-dir <dossier>` (défaut `migration-reports/`) : rapport `.txt` et `.json` horodatés.

Codes de sortie : `0` SUCCESS · `2` FAILED · `1` usage invalide.

### Fichier cible (`cible.properties`) — sans secret

```properties
host=<hôte MariaDB>
port=3306
database=rpgquest
username=<compte dédié>
password-env=RPGQUEST_DB_PASSWORD   # NOM de la variable d'environnement, jamais la valeur
ssl-mode=disable                    # disable | trust | verify-ca | verify-full
connection-timeout-ms=10000
```

- Une clé `password` (ou `pass`, `passwd`, `pwd`) est **refusée**.
- Le mot de passe vient de la variable d'environnement nommée, chargée depuis un fichier local
  `chmod 600` hors dépôt (`set -a; . ~/fichier.env; set +a`).
- Le rapport ne cite **ni l'hôte, ni le compte, ni le mot de passe** : seulement le moteur et le
  nom de base (`mariadb …/rpgquest`). Les messages d'erreur du pilote sont expurgés de l'hôte et du
  compte avant d'y être écrits.

### Déroulé et garanties

1. **Source** ouverte en **lecture seule** (`SQLITE_OPEN_READONLY` + `PRAGMA query_only`) ;
   empreinte SHA-256 du fichier avant/après — différence ⇒ FAILED. Journal `-journal`/`-wal` non
   vide à côté ⇒ FAILED (copie prise serveur démarré).
2. **Version** : la source doit être exactement au schéma de l'outil (`SchemaMigrator.CURRENT_VERSION`).
3. **Intégrité** `PRAGMA integrity_check`, **orphelins** `PRAGMA foreign_key_check`, **types réels**
   par colonne, **UUID** (avertissement).
4. **Cible** : tables étrangères à RPGQuest ⇒ refus ; **une seule ligne dans une table RPGQuest ⇒
   refus** ; schéma cible plus récent que l'outil ⇒ refus. Ces contrôles précèdent toute écriture.
   L'outil ne fait **jamais** `DROP`, `DELETE` ni `TRUNCATE`.
5. **Schéma** créé par le catalogue du plugin, puis contrôle des **longueurs** (texte source vs
   `VARCHAR(n)` cible) et des collisions **PAD SPACE** sur les clés uniques.
6. **Copie** de toutes les tables dans l'ordre des clés étrangères, **dans une seule transaction**,
   par lots de 500. Une erreur ⇒ annulation complète, la cible reste vide de données.
7. **Validation** dans la transaction : nombre de lignes **et empreinte logique** par table,
   orphelins dans la cible. L'empreinte (somme modulo 2²⁵⁶ des SHA-256 de lignes canonisées) est
   indépendante du moteur et de l'ordre de lecture, distingue `NULL` de `''`, compte les doublons.
8. `dry-run` : annulation. `migrate --commit` : validation **seulement si aucun constat critique**,
   puis alignement des séquences `AUTOINCREMENT` et recomptage sur lectures neuves.

**Limites assumées** : sur MariaDB, le DDL n'est pas transactionnel — après un `dry-run`, la cible
garde ses **tables vides** (et des compteurs `AUTO_INCREMENT` éventuellement avancés, sans effet :
les identifiants copiés sont explicites et l'outil n'abaisse jamais une séquence). Une cible
« schéma présent, aucune ligne » reste acceptée par les exécutions suivantes.

---

## 3. Ce qui est autorisé aujourd'hui : répétition sur copie isolée

**PlugAdmin > Base de données** (`/database`, propriétaire) affiche en permanence le backend
**réellement actif** (heartbeat du plugin), enregistre la base MariaDB cible (mot de passe chiffré,
jamais réaffiché, champ vide = conserver, effacement séparé et confirmé), **teste la connexion** en
lecture seule et déroule l'assistant (état / cible / test / simulation / comparaisons / bloquants) ;
le bouton de bascule y est **désactivé** et aucune route ne l'effectue. Fiche :
`control-panel/src/main/resources/docs/base-de-donnees.md`.

- Copier une **sauvegarde** de `data.db` (jamais le fichier du serveur en service) dans un dossier
  de travail, `chmod a-w`.
- Si son schéma est plus ancien que l'outil : en faire une **seconde copie** et la porter au schéma
  courant (démarrer le plugin de cette version sur une instance jetable, ou appeler
  `SchemaMigrator.migrate` sur la copie).
- `audit`, puis `dry-run --target-sqlite` (aucune infrastructure), puis `dry-run --target-mysql`
  vers une **MariaDB jetable** (instance locale, base dédiée vide) — jamais la base VeryGames
  active ni celle de production.
- Conserver les rapports hors dépôt ; n'en citer que des extraits sans hôte ni compte.

Essais réalisés le 14/10/2026 (MariaDB 10.11.14 locale jetable, même branche majeure que
VeryGames 10.11.6) — voir le rapport de session #42 :

| Essai | Résultat |
|---|---|
| `audit` copie DEV V34 | SUCCESS — 39 tables, intégrité/FK/types/UUID OK, source inchangée |
| `dry-run` → SQLite | SUCCESS — 39/39 tables identiques (3 702 lignes), 8 séquences OK |
| `dry-run` → MariaDB | **FAILED (attendu)** — `building_sites.description` 490 > `VARCHAR(191)` |
| `dry-run` → MariaDB, copie jetable avec cette colonne tronquée | SUCCESS — 39/39 tables identiques |
| `migrate --commit` → MariaDB jetable, puis seconde exécution | SUCCESS puis **refus** (cible non vide), rien dupliqué ; `AUTO_INCREMENT` alignés |

---

## 4. Exécution réelle — runbook FUTUR (non autorisé sans consigne explicite)

### Prérequis go/no-go (tous obligatoires)

- [ ] Autorisation **écrite** du propriétaire pour la date et la cible.
- [ ] Constat bloquant §1 corrigé (#354, schéma MariaDB des textes libres) et revalidé.
- [ ] Base MariaDB **dédiée et vide**, compte dédié limité à cette base (privilèges §VERYGAMES).
- [ ] Plugin de la version à déployer validé une fois sur une base MariaDB **de test** (démarrage, heartbeat,
      création de données).
- [ ] Dernier `dry-run` sur une copie récente : **SUCCESS**, durée mesurée (base de l'estimation).
- [ ] Même JAR plugin et même JAR outil (même `SchemaMigrator.CURRENT_VERSION`).
- [ ] Fenêtre de maintenance annoncée ; personne en ligne.

### Estimation d'interruption

Interruption = arrêt serveur + copie FTP de `data.db` + **durée du dernier dry-run** (lue dans le
rapport ; ~2–4 s pour 3 700 lignes en local, à remesurer depuis AWS vers VeryGames, latence réseau
comprise) + bascule de configuration + redémarrage + smoke tests. Prévoir **30 minutes**, dont la
majeure partie en contrôles manuels.

### Étapes

1. **Arrêter les écritures** : prévenir, puis arrêter le serveur (RCON `stop` — jamais de boucle
   de redémarrage, voir la mémoire VeryGames).
2. **Sauvegarde datée** : `data.db` + `config.yml` + JAR courant dans `/srv/rpgquest/backups/<date>-pre-mariadb/`
   (ne jamais écraser une sauvegarde existante) ; noter le SHA-256 de `data.db`.
3. **Copie de travail** : `cp` de la sauvegarde vers un dossier de travail, `chmod a-w`.
4. **Audit** : `java -jar $J audit --sqlite-file <copie> --target-mysql cible.properties` → SUCCESS
   obligatoire (intégrité, cible joignable et vide).
5. **Dry-run** sur la même copie → SUCCESS obligatoire.
6. **Migration** : `java -jar $J migrate --commit --sqlite-file <copie> --target-mysql cible.properties`
   → SUCCESS obligatoire ; conserver le rapport `.txt`/`.json` (hors dépôt).
7. **Contrôle croisé** : SHA-256 de la copie = celui noté à l'étape 2 (« Source SQLite inchangée : OK »).
8. **Bascule** : `database.type: mysql` + section `database.mysql` (via le mécanisme de config
   prévu, jamais un `config.yml` téléversé à la main) ; variable du mot de passe disponible pour le
   processus serveur.
9. **Démarrage** : log `moteur mariadb …`, aucune migration de schéma appliquée (déjà à jour),
   serveur vu en ligne par le Control Panel (heartbeat) et backend affiché « mariadb ».
10. **Smoke tests** : checklist §6.
11. **Conserver `data.db` intact** sur le serveur et dans la sauvegarde pendant toute la période de
    validation. **Aucune suppression** sans validation explicite du propriétaire.

En cas d'échec à une étape 4 à 7 : ne pas basculer ; redémarrer sur SQLite (inchangé). La base
MariaDB partiellement créée reste **vide de données** (transaction annulée) ; la vider ou la
recréer est une opération manuelle du propriétaire — l'outil ne supprime jamais rien.

---

## 5. Rollback

Tant que la validation finale n'est pas prononcée :

1. arrêter RPGQuest ;
2. remettre `database.type: sqlite` ;
3. `data.db` est celui d'avant la bascule (jamais modifié par l'outil — sinon restaurer la
   sauvegarde de l'étape 2) ;
4. redémarrer ;
5. vérifier le heartbeat du Control Panel (backend « sqlite »), le journal de quêtes et la progression d'un joueur connu.

> **Limite à connaître** : toute écriture faite **uniquement dans MariaDB** après la bascule
> (progression, achats, claims…) est **perdue** en revenant à l'ancien `data.db`. Il n'y a **pas**
> de réplication ni de synchronisation bidirectionnelle (hors périmètre). Plus le retour arrière
> est tardif, plus la perte est grande : la fenêtre de décision doit être courte et annoncée.

---

## 6. Checklist de validation manuelle (après une future bascule) — `PENDING MANUAL VALIDATION`

Avec **plusieurs profils** (au moins un joueur ancien et un nouveau) :

- [ ] connexion d'un joueur existant : profil, nom, inventaire de sac à dos ;
- [ ] journal de quêtes : quêtes actives et terminées, objectifs en cours ;
- [ ] stories : étape courante ;
- [ ] variables / déverrouillages (accès conditionnés) ;
- [ ] claims : propriétaire, membres, protections ;
- [ ] économie si active : solde, historique, marché ;
- [ ] PNJ / dialogues dépendant de la progression ;
- [ ] voyage : waypoints/waystones découverts, cooldowns ;
- [ ] déconnexion / reconnexion ;
- [ ] redémarrage serveur puis reconnexion ;
- [ ] création de nouvelles données après bascule (nouvelle quête, nouveau PNJ : identifiant
      `npc_<n>` jamais réutilisé).

---

## 7. Tests automatisés

- `SqliteMigrationToolTest`, `LogicalChecksumTest`, `MigrationCliTest` (JUnit pur, cible SQLite —
  toujours exécutés) : base vide, jeu réaliste, UUID, quêtes/stories, variables, claims, économie,
  BLOB, réels, séquence avec trou, cible non vide refusée, table étrangère refusée, panne en cours de
  copie annulée, version de source refusée, orphelins, type non conforme, journal chaud, rapport
  texte/JSON, codes de sortie, mot de passe en clair refusé, aucun hôte/compte/secret dans la sortie.
- `MariaDbMigrationIntegrationTest` (optionnel, variables `RPGQUEST_DB_*`) : même chaîne contre un
  vrai MariaDB, relecture des données migrées **par les repositories du plugin**, séquence
  respectée, refus d'une seconde exécution, texte trop long détecté avant écriture. Chaque test crée
  puis supprime sa propre base jetable (`<RPGQUEST_DB_NAME>_mig_…`) : le compte de test doit avoir
  le droit `CREATE`/`DROP` sur ce motif — **jamais** un compte de production.
