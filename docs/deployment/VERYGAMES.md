# Déploiement VeryGames — RPGQuest

Procédure de déploiement **réellement reproductible**, à partir d'une
installation VeryGames vide, jusqu'à un serveur RPGQuest fonctionnel. Ce
document ne couvre que l'exploitation (hébergement, transfert de fichiers,
migration de données) — pour le fonctionnement du plugin lui-même, voir
[docs/ARCHITECTURE.md](../ARCHITECTURE.md) et les autres documents de
`docs/`. Aucune modification du code du plugin n'est nécessaire ni décrite
ici.

## Environnement réellement validé

| Composant       | Version                              |
|-----------------|---------------------------------------|
| Hébergeur       | VeryGames                             |
| Serveur         | PaperMC `1.21.11-132`                 |
| Java            | Java 21 / Temurin 21                  |
| WorldEdit       | `7.4.1`                               |
| Citizens        | `2.0.43` build `4232`                 |
| Multiverse-Core | `5.7.3`                               |
| RPGQuest        | `0.1.0-SNAPSHOT`                      |

JAR utilisés (noms exacts, à retrouver tels quels dans `/plugins/`) :

-   `worldedit-bukkit-7.4.1.jar`
-   `Citizens-2.0.43-b4232.jar`
-   `multiverse-core-5.7.3.jar`
-   `rpgquest-0.1.0-SNAPSHOT.jar`

RPGQuest ne déclare une dépendance dure sur aucun des trois autres plugins
(`plugin.yml` → `softdepend: [Citizens]` uniquement) : Citizens, s'il est
présent, devient le système de PNJ prioritaire, mais son absence ne bloque
pas le démarrage. WorldEdit et Multiverse-Core ne sont pas requis par le
code pour démarrer ; ils sont néanmoins utilisés dans cet environnement
(WorldEdit pour la préparation manuelle du terrain, Multiverse-Core pour la
gestion du monde Hub `world_hub`), d'où l'ordre d'installation validé
ci-dessous.

## Les trois scénarios couverts par ce document

1.  **Installation neuve** — aucun serveur VeryGames existant, on part de
    zéro. Voir [Procédure — installation neuve](#procédure--installation-neuve-ordre-validé).
2.  **Mise à jour du seul JAR RPGQuest** — un serveur RPGQuest tourne déjà
    en production, seule une nouvelle version du plugin doit être posée.
    Voir [Mise à jour du seul JAR RPGQuest](#mise-à-jour-du-seul-jar-rpgquest-scénario-2).
3.  **Migration complète d'un serveur existant** (ex. environnement de
    développement/recette vers VeryGames, ou VeryGames vers VeryGames) —
    inclut la migration des données de jeu (quêtes, PNJ, monde Hub...). Voir
    [Migration des données](#migration-des-données-scénario-3).

Ne pas mélanger ces procédures : un scénario 2 traité comme un scénario 3
(ou l'inverse) est la source d'erreur la plus probable.

---

## Accès FTP VeryGames

-   **Protocole/port :** FTP sur le port `21`, **avec AUTH TLS explicite
    obligatoire** (FTPS explicite). **VeryGames ne fournit pas de SFTP** et
    **refuse toute connexion FTP en clair** (réponse `530` — vérifié le
    2026-09-06). Le chiffrement n'est donc pas optionnel : mode
    `VERYGAMES_FTP_TLS=require`.
-   **Chaîne de certificat incomplète (côté serveur).** Le certificat est
    un Let's Encrypt valide (`*.dg.vg`), mais ProFTPD ne renvoie **que** le
    certificat feuille — les intermédiaires (`YE1`, puis `Root YE`
    cross-signé par `ISRG Root X2`, racine déjà de confiance) ne sont pas
    envoyés. `curl`/`openssl` ne peuvent donc pas construire la chaîne :
    erreur *« unable to get local issuer certificate »*. **Solution — sans
    jamais `--insecure` :** générer les intermédiaires manquants une fois
    avec `scripts/verygames-fetch-ca.sh` (il suit les URL AIA de Let's
    Encrypt, vérifie la chaîne complète contre le magasin système, écrit un
    PEM public), puis renseigner `VERYGAMES_FTP_CA_EXTRA` dans le fichier
    d'identifiants. Le script `deploy-verygames.sh` fusionne alors ce PEM au
    magasin système (`curl --cacert`) et la vérification passe proprement.
-   **Host / login / mot de passe :** récupérés depuis le panel VeryGames
    (Gameserver → onglet FTP), propres à chaque instance de serveur.
-   **Le login FTP est préfixé par l'identifiant du slot.** Le serveur
    multi-clients attend `<slot>.<sous-compte>` — p.ex. `si-16041.awsplugin`
    (le nom `awsplugin` **seul** est refusé par un `530`). Reprendre la
    valeur EXACTE du panel.
-   **Sous-compte `awsplugin` restreint au dossier `plugins/`** : après
    connexion, la racine FTP *est* `plugins/` (le JAR se dépose directement
    à la racine de session, pas dans un sous-dossier `plugins/`).
-   **Ne jamais stocker le mot de passe FTP dans Git**, ni dans ce
    document, ni dans un fichier de configuration versionné. Le conserver
    uniquement dans un gestionnaire de mots de passe ou dans le fichier
    local d'identifiants décrit ci-dessous (hors dépôt).
-   **Arborescence côté serveur (compte non restreint uniquement) :**
    -   les plugins se déposent dans `/plugins/` ;
    -   les mondes (`world`, `world_nether`, `world_the_end`, `world_hub`,
        ...) se trouvent **à la racine** du serveur, pas dans `/plugins/`.

Un client FTP classique (FileZilla, WinSCP...) ou `ftp`/`curl` en ligne de
commande convient. Pour la mise à jour du seul JAR (scénario 2), utiliser
de préférence le script automatisé ci-dessous.

---

## Déploiement automatisé (`scripts/deploy-verygames.sh`)

**Recommandé pour le scénario 2 (mise à jour du seul JAR).** Le script
automatise l'*exécution* d'un déploiement ; son *déclenchement* reste
manuel, et l'**arrêt / redémarrage du serveur VeryGames reste manuel**
(VeryGames n'expose ni API ni RCON exploitable — FTP port 21 uniquement).

Fichiers (versionnés, sans secret) :

-   `scripts/deploy-verygames.sh` — build vérifié + backup daté + transfert ;
-   `scripts/rollback-verygames.sh` — restauration d'un backup ;
-   `scripts/lib/verygames-common.sh` — fonctions partagées ;
-   `scripts/verygames.env.example` — modèle du fichier d'identifiants.

### 1. Créer le fichier local d'identifiants (hors Git)

Le fichier **réel** vit **hors du dépôt**. Emplacement par défaut :

```
~/.config/rpgquest/verygames.env
```

(surchargeable via la variable d'environnement `RPGQUEST_VERYGAMES_ENV`).

Commandes exactes à exécuter sur AWS :

```bash
mkdir -p ~/.config/rpgquest
chmod 700 ~/.config/rpgquest
cp scripts/verygames.env.example ~/.config/rpgquest/verygames.env
chmod 600 ~/.config/rpgquest/verygames.env
${EDITOR:-nano} ~/.config/rpgquest/verygames.env
```

Format : `CLE=valeur`, une par ligne (fichier sourcé par bash, pas de
`export`). Mettre les valeurs contenant des espaces ou des caractères
spéciaux entre **guillemets simples** — en particulier le mot de passe :

```sh
# ~/.config/rpgquest/verygames.env
VERYGAMES_FTP_HOST=si-16041.dg.vg               # hôte du panel VeryGames (onglet FTP)
VERYGAMES_FTP_PORT=21
VERYGAMES_FTP_USER=si-16041.awsplugin          # login EXACT du panel : <slot>.<sous-compte>
VERYGAMES_FTP_PASS='le-mot-de-passe-du-panel'
VERYGAMES_FTP_REMOTE_DIR=/                       # awsplugin est chrooté sur plugins/
VERYGAMES_PLUGIN_JAR_NAME=rpgquest-0.1.0-SNAPSHOT.jar
VERYGAMES_FTP_TLS=require                        # VeryGames impose AUTH TLS (le clair est refusé)
VERYGAMES_FTP_CA_EXTRA=~/.config/rpgquest/verygames-ca.pem   # généré par verygames-fetch-ca.sh
```

> Avant le premier `--check`, générer les intermédiaires TLS manquants
> (chaîne incomplète côté VeryGames — voir « Accès FTP » plus haut) :
>
> ```bash
> scripts/verygames-fetch-ca.sh          # -> ~/.config/rpgquest/verygames-ca.pem (public, sans secret)
> ```

Variables reconnues (voir `scripts/verygames.env.example` pour la liste
complète et les optionnelles `VERYGAMES_BACKUP_DIR`,
`VERYGAMES_BACKUP_KEEP`, `VERYGAMES_CURL_*`,
`VERYGAMES_FTP_EXTRA_CURL_ARGS`, `VERYGAMES_FTP_CACERT`) :

| Variable | Obligatoire | Défaut | Rôle |
|---|---|---|---|
| `VERYGAMES_FTP_HOST` | oui | — | hôte FTP VeryGames, **sans** `ftp://`, sans port |
| `VERYGAMES_FTP_PASS` | oui | — | mot de passe du compte FTP |
| `VERYGAMES_FTP_USER` | oui (en pratique) | `awsplugin` | **login EXACT du panel**, préfixé : `<slot>.<sous-compte>` (ex. `si-16041.awsplugin`) — le défaut nu ne fonctionne pas |
| `VERYGAMES_FTP_CA_EXTRA` | oui (VeryGames) | — | PEM d'intermédiaires TLS manquants, généré par `scripts/verygames-fetch-ca.sh` ; fusionné au magasin système |
| `VERYGAMES_FTP_TLS` | non | `require` | `require` (mode VeryGames, cert vérifié) \| `auto` (= `require`) \| `none` (clair, inutilisable chez VeryGames) |
| `VERYGAMES_FTP_PORT` | non | `21` | port FTP |
| `VERYGAMES_FTP_REMOTE_DIR` | non | `/` | dossier distant du JAR (racine de session pour `awsplugin`) |
| `VERYGAMES_PLUGIN_JAR_NAME` | non | `rpgquest-0.1.0-SNAPSHOT.jar` | nom exact du JAR à remplacer |
| `VERYGAMES_FTP_CACERT` | non | — | magasin CA de remplacement **total** (usage avancé) |
| `VERYGAMES_BACKUP_DIR` | non | `~/.local/share/rpgquest/verygames-backups` | où sont téléchargés les backups datés |

Précédence : une variable déjà **exportée dans l'environnement** du shell
l'emporte sur la valeur du fichier (utile pour un secret injecté par un
gestionnaire externe). Le script **refuse** le fichier s'il n'est pas en
mode `600` (ou `400`).

Vérifier qu'il n'est **jamais** suivi par Git :

```bash
# Le fichier réel est hors du dépôt : Git ne peut pas le voir.
# Filet de sécurité si quelqu'un le place quand même sous scripts/ :
git check-ignore -v scripts/verygames.env          # -> une règle de .gitignore
git status --porcelain | grep -i 'verygames\.env'  # -> aucune sortie (hors .example)
git ls-files | grep -i 'verygames\.env'            # -> uniquement scripts/verygames.env.example
```

### 2. Préparer / valider sans rien transférer

```bash
# Charger + valider la config (et, si les identifiants sont là, tester la connexion) :
scripts/deploy-verygames.sh --check

# Simulation complète : git propre + ./gradlew test + build + JAR présent,
# puis affichage des actions FTP qui SERAIENT faites (aucune connexion) :
scripts/deploy-verygames.sh --dry-run

# Idem sans relancer Gradle (itération rapide sur la logique FTP) :
scripts/deploy-verygames.sh --dry-run --skip-build
```

`--dry-run` réussit (sortie 0) **même si l'hôte et le mot de passe ne sont
pas encore renseignés** : il liste alors ce qu'il reste à configurer.

### 3. Déployer (serveur arrêté)

```bash
# 1. Arrêter le serveur depuis le panel VeryGames (manuel).
# 2. Puis, depuis la racine du dépôt sur AWS :
scripts/deploy-verygames.sh
# 3. Redémarrer le serveur depuis le panel (manuel).
# 4. Vérifier : /rpgquest version, /plugins, absence d'ERROR au démarrage.
```

Ce que fait le script, dans l'ordre (échec immédiat à la moindre erreur —
`set -euo pipefail`) :

1.  vérifie que le working tree Git est **propre** (sinon `--allow-dirty`) ;
2.  affiche **branche + commit** déployés ;
3.  exécute `./gradlew test` ;
4.  exécute `./gradlew build` ;
5.  vérifie la présence de `build/libs/<VERYGAMES_PLUGIN_JAR_NAME>` et
    calcule sa taille + SHA-256 ;
6.  charge la config d'accès, teste la connexion FTP (listing du dossier
    distant) — **abandon avant toute écriture** si la connexion échoue ;
7.  **télécharge la version actuellement en ligne** vers
    `…/verygames-backups/rpgquest-<UTC>Z-predeploy.jar` (+ un `.meta` :
    SHA-256, taille, commit qui l'a remplacée, opérateur, date) ;
8.  téléverse le nouveau JAR sous un nom **temporaire**
    (`<jar>.part-<UTC>Z`), vérifie la taille distante, puis le **renomme**
    (`RNFR`/`RNTO`) sur le nom final — le JAR final n'est jamais un fichier
    à moitié transféré (repli sur téléversement direct si le serveur refuse
    le renommage) ;
9.  **ne touche à rien d'autre** : `data.db`, `config.yml`, `messages.yml`,
    `spawn.yml`, dossiers de contenu, mondes, autres plugins — le script
    n'adresse qu'un seul chemin distant, celui du JAR ;
10. conserve **tous** les backups (noms horodatés, jamais écrasés) ;
    `--prune-keep N` (ou `VERYGAMES_BACKUP_KEEP`) permet de ne garder que
    les N `predeploy` les plus récents, **jamais** le plus récent ;
11. affiche les **actions manuelles restantes** (démarrage + vérifications
    panel/console).

Options utiles : `--server-stopped` (saute la question interactive
« serveur arrêté ? »), `-y` (aucune question), `--allow-no-backup`
(premier déploiement, rien à sauvegarder), `--jar PATH` (JAR explicite).
`scripts/deploy-verygames.sh --help` pour la liste complète.

#### Fichiers RPGQuest supplémentaires : `--also LOCAL:REMOTE`

Certaines évolutions demandent, en plus du JAR, le remplacement de quelques
fichiers de contenu **déjà présents** que le plugin ne réécrit jamais tout
seul (p. ex. `dialogues/guide.yml` pour l'issue #11 — voir l'entrée
correspondante de `SERVER_CHANGELOG.md`). L'option `--also` prend une
**liste blanche explicite**, répétable :

```bash
scripts/deploy-verygames.sh --server-stopped \
  --also src/main/resources/dialogues/guide.yml:RPGQuest/dialogues/guide.yml \
  --also src/main/resources/dialogues/libraire.yml:RPGQuest/dialogues/libraire.yml
```

- `LOCAL` = chemin relatif au dépôt (ou absolu) ; `REMOTE` = chemin distant
  (relatif à `VERYGAMES_FTP_REMOTE_DIR`) qui **doit commencer par
  `RPGQuest/`**.
- Chaque fichier est **téléchargé (backup) avant remplacement** dans
  `…/verygames-backups/extra-<UTC>Z/<REMOTE>` + un `MANIFEST.txt`
  (sha256 avant/après). Transfert atomique (`.part` → `RNFR`/`RNTO`).
- Le script **refuse** toute cible hors `RPGQuest/`, toute traversée
  (`..`), et — quel que soit l'emplacement — `data.db`, `config.yml`,
  `messages.yml`, `spawn.yml`, `RPGQuest/Citizens/`. **Aucune
  synchronisation de dossier**, jamais.
- Restauration : `scripts/rollback-verygames.sh --also
  <backup>:<REMOTE>` (répétable, utilisable seul), qui sauvegarde d'abord
  la version en ligne avant d'écraser.

### 4. Rollback (`scripts/rollback-verygames.sh`)

```bash
scripts/rollback-verygames.sh --list               # backups disponibles + méta
scripts/rollback-verygames.sh --latest --dry-run   # simulation
# Serveur arrêté (panel), puis :
scripts/rollback-verygames.sh --latest             # restaure la version d'avant le dernier déploiement
scripts/rollback-verygames.sh --backup <chemin>    # restaure un backup précis
# Redémarrer le serveur (panel) + vérifier /rpgquest version.
```

Le rollback :

1.  refuse d'agir si **aucun backup valide** n'est disponible (fichier
    présent, non vide, signature ZIP, SHA-256 conforme au `.meta`) ;
2.  crée d'abord un **backup de sécurité** de la version actuellement en
    ligne (`rpgquest-<UTC>Z-prerollback.jar`) — le rollback est lui-même
    réversible ;
3.  restaure via le même transfert atomique (nom temporaire puis renommage) ;
4.  **ne modifie aucune donnée persistante** ;
5.  affiche les instructions de redémarrage et de vérification.

### Limites assumées (contraintes VeryGames)

-   **Arrêt et redémarrage du serveur : manuels** (panel VeryGames). Aucune
    API ni RCON exploitable. Le script demande confirmation que le serveur
    est arrêté avant d'écrire, et rappelle les étapes de redémarrage à la
    fin.
-   **Pas de vérification post-redémarrage automatique** (`/rpgquest
    version` se fait à la main, console/jeu).
-   **AUTH TLS obligatoire et certificat vérifié** (`VERYGAMES_FTP_TLS=require`,
    défaut) : jamais `--insecure`. La chaîne incomplète servie par VeryGames
    est compensée par `VERYGAMES_FTP_CA_EXTRA` (voir « Accès FTP »). Si
    `scripts/verygames-fetch-ca.sh` ne parvient plus à reconstruire une
    chaîne vérifiable (rotation d'intermédiaire Let's Encrypt), c'est à
    VeryGames de corriger sa configuration TLS ProFTPD (chaîne complète) —
    ne pas désactiver la vérification.
-   Le script ne gère **que le JAR**. Migration de mondes / `data.db` /
    configs : hors périmètre, voir les scénarios 1 et 3 ci-dessous.

---

## Compiler RPGQuest

Depuis la racine du dépôt :

```
./gradlew clean build
```

(`gradlew.bat clean build` sous Windows). Le build compile le plugin **et**
`web-api`, exécute les suites JUnit des deux modules ; il doit se terminer
`BUILD SUCCESSFUL` avant tout déploiement.

JAR produit :

```
build/libs/rpgquest-0.1.0-SNAPSHOT.jar
```

C'est ce fichier — et uniquement celui-ci — qui doit être transféré vers
`/plugins/` sur VeryGames (jamais un jar depuis `run/plugins/`, qui est une
copie de travail locale, cf. [docs/LOCAL_SERVER.md](../LOCAL_SERVER.md)).

---

## Procédure — installation neuve (ordre validé)

Ordre strict, chaque étape doit être testée avant de passer à la suivante
(un plugin qui échoue silencieusement à l'étape N complique le diagnostic à
l'étape N+2).

### 1. Créer l'instance Paper `1.21.11-132`

Depuis le panel VeryGames, créer l'instance de jeu et sélectionner la
version Paper `1.21.11-132`. Ne rien installer d'autre à ce stade.

### 2. Premier démarrage

Démarrer le serveur une première fois. Objectif : laisser Paper générer sa
structure de base (`server.properties`, `eula.txt`, monde `world` par
défaut...).

### 3. Vérifier Java 21

Dans les logs de démarrage (console VeryGames), confirmer que le serveur
tourne bien sous Java 21 / Temurin 21 (ligne de version JVM au tout début
du log). RPGQuest est compilé avec `options.release.set(21)` — un Java plus
ancien empêche le plugin de charger.

### 4. Arrêter le serveur

Arrêt propre (`stop` en console, ou bouton "Arrêter" du panel).

### 5. Installer WorldEdit et tester

-   Transférer `worldedit-bukkit-7.4.1.jar` dans `/plugins/` (FTP).
-   Redémarrer.
-   Tester : `//wand` (ou `/worldedit version`) en jeu/console doit
    répondre sans erreur, `/plugins` doit lister WorldEdit en vert.

### 6. Installer Citizens et tester

-   Transférer `Citizens-2.0.43-b4232.jar` dans `/plugins/`.
-   Redémarrer.
-   Tester : `/npc create test_citizens` doit créer un PNJ visible, puis le
    supprimer (`/npc remove` en le ciblant) — ce PNJ de test ne doit jamais
    rester en production.

### 7. Installer Multiverse-Core et tester

-   Transférer `multiverse-core-5.7.3.jar` dans `/plugins/`.
-   Redémarrer.
-   Tester : `/mv list` doit répondre sans erreur et lister au moins le
    monde `world` par défaut.

### 8. Installer RPGQuest et tester

-   Transférer `build/libs/rpgquest-0.1.0-SNAPSHOT.jar` dans `/plugins/`.
-   Redémarrer.
-   Tester : `/rpgquest version` répond `RPGQuest v0.1.0-SNAPSHOT` ;
    `/plugins` liste RPGQuest en vert ; aucune exception dans les logs au
    démarrage (services démarrés dans l'ordre : config, base de données,
    moteur de quêtes, objets, recettes, nœuds de ressource, dialogues,
    journal — voir [docs/LOCAL_SERVER.md](../LOCAL_SERVER.md)).
-   À ce stade, `run/plugins/RPGQuest/` (équivalent `/plugins/RPGQuest/`
    côté VeryGames) ne contient que les exemples générés par défaut
    (`first_steps`, `crystal_hunt`, `central_village`...), pas encore les
    données réelles du monde Hub.

### 9. Arrêter le serveur

Arrêt propre, avant toute manipulation de fichiers de données.

### 10. Migrer données/configurations/mondes

Voir la section détaillée [Migration des données](#migration-des-données-scénario-3)
ci-dessous — c'est l'étape la plus sensible de la procédure.

### 11. Redémarrer

Redémarrage complet après la migration.

### 12. Tests finaux

Dérouler la [checklist finale](#checklist-finale) en intégralité avant de
considérer le déploiement terminé.

---

## Migration des données (scénario 3)

### Règle d'or : `saves.yml` + `data.db` sont une seule unité

-   Citizens : `plugins/Citizens/saves.yml` (les PNJ).
-   RPGQuest : `plugins/RPGQuest/data.db` (profils joueurs, progression des
    quêtes, économie, claims, cooldowns de portails...).

Ces deux fichiers doivent être considérés comme **une seule unité
indissociable de migration**. RPGQuest identifie les PNJ Citizens par leur
id numérique Citizens (voir `NpcIdentityService`/`CitizensNpcBridge`), et
certaines données RPGQuest (dialogues liés à un PNJ, par exemple) supposent
la correspondance exacte avec les entrées de `saves.yml`. **Ne jamais
migrer `saves.yml` sans le `data.db` correspondant, et inversement** — les
deux doivent provenir du même instantané temporel et rester cohérents entre
eux sur les deux installations.

**Symptôme concret d'une migration désynchronisée :** un `saves.yml` migré
avec un `data.db` neuf (ou d'un instantané différent) recrée bien les PNJ
Citizens visuellement — ils apparaissent en jeu, avec leur id numérique
Citizens habituel — mais les tables `npc_ids`/`npc_citizens_bindings` de ce
`data.db` neuf ne contiennent alors **aucun** mapping vers ces PNJ : les
dialogues associés (clic droit sur le PNJ) et les objectifs de quête
`TALK_TO_NPC` qui en dépendent cessent de fonctionner, sans erreur explicite
au démarrage — seule une vérification manuelle (`/rpgadmin npc info` sur
chaque PNJ) révèle le problème. Voir aussi
[docs/RPGQUEST_BIBLE.md § Dépannage](../RPGQUEST_BIBLE.md#18-dépannage).

### Contenu RPGQuest à migrer (selon ce qui existe sur le serveur source)

Dans `plugins/RPGQuest/` :

-   `destinations/`
-   `dialogues/`
-   `items/`
-   `merchants/`
-   `mobs/`
-   `portals/`
-   `quests/`
-   `recipes/`
-   `resource-nodes/`
-   `store-products/`
-   `world-portals/`
-   `zones/`
-   `config.yml`
-   `messages.yml`
-   `spawn.yml`
-   `data.db` (avec `saves.yml`, voir ci-dessus)

### ⚠️ Contrôle avant migration — exclure les fixtures de test manuel

**Ne pas copier aveuglément tout `run/plugins/RPGQuest/` en production.**
`run/` est un environnement de développement/test (voir
[docs/LOCAL_SERVER.md](../LOCAL_SERVER.md), jamais commité) et peut
contenir des artefacts de tests manuels.

Exemple réel : `run/plugins/RPGQuest/quests/broken_quest.yml`. Ce fichier a
été créé volontairement selon [docs/MANUAL_TEST_PLAN.md](../MANUAL_TEST_PLAN.md)
(section TC-011) pour tester le rejet d'une quête invalide au chargement.
**Il ne doit jamais être transféré en production.**

**Étape de contrôle obligatoire, avant toute copie de fichiers YAML vers
VeryGames :**

1.  Lister le contenu de chaque dossier à migrer (`quests/`, `dialogues/`,
    `items/`, etc.) et comparer à ce qui est réellement attendu en
    production (contenu conçu pour les joueurs, pas pour valider le
    comportement du chargeur).
2.  Écarter tout fichier connu comme fixture de test (ex.
    `broken_quest.yml`), tout fichier au nom explicitement « test »,
    « debug », « tmp », ou créé pour reproduire un bug plutôt que pour le
    jeu.
3.  En cas de doute sur un fichier, l'ouvrir et vérifier qu'il définit un
    contenu jouable cohérent (id, nom, description, récompenses sensées) —
    pas une entrée volontairement invalide ou un doublon de test.
4.  Ne transférer que les fichiers ayant passé ce contrôle.

### Migration du monde Hub

Le Hub réel est `run/world_hub/` (côté développement) et doit être
transféré à la **racine** VeryGames sous `/world_hub/` (même niveau que
`world/`, `world_nether/`, `world_the_end/`) :

-   **Ne pas** le renommer en `world`.
-   **Ne pas** remplacer automatiquement les mondes par défaut `world/`,
    `world_nether/`, `world_the_end/` — ce sont des mondes distincts, non
    gérés par RPGQuest, qui doivent rester en place tels quels.

Après le transfert FTP du dossier `world_hub/` à la racine, en
console/RCON VeryGames :

```
/mv import world_hub normal
/mv list
```

`/mv import` déclare le monde auprès de Multiverse-Core (dossier déjà
présent, chargement en environnement `normal`) ; `/mv list` sert de
vérification immédiate que `world_hub` apparaît bien comme monde chargé.

### Spawn RPGQuest vs spawn Multiverse

**Le spawn Multiverse n'est pas la référence gameplay.** Après import du
monde, ne pas s'appuyer sur `/mv setspawn` ou le spawn Multiverse par
défaut. Le spawn RPGQuest doit être défini explicitement :

```
/rpgadmin spawn set
```

(capture la position exacte de l'administrateur, remplace le spawn existant
s'il y en avait un — voir `RpgAdminCommand`/`SpawnService`), puis testé avec :

```
/rpgadmin spawn tp
```

qui doit téléporter à la position tout juste définie.

### Règles du monde Hub

Une fois `world_hub` chargé, RPGQuest applique **automatiquement** les
règles suivantes (code, jamais de `/gamerule` manuel — voir
`HubWorldRulesService`/`HubWorldProtectionListener`) :

-   jour permanent (heure figée à midi, `ADVANCE_TIME` désactivé) ;
-   météo permanente (pas de pluie/orage, `ADVANCE_WEATHER` désactivé) ;
-   protections Hub :
    -   dégâts joueurs : **toujours annulés**, quelle que soit la cause
        (PvP, mob, chute, explosion, environnement) — aucune exception,
        même pour un administrateur ;
    -   PvP : bloqué (conséquence directe de l'annulation totale des
        dégâts ci-dessus) ;
    -   casse/pose de bloc : bloquées pour tout le monde, **sauf** bypass
        admin (`rpgquest.admin.world`) ;
    -   explosions : les blocs ne sont jamais détruits (`blockList()`
        vidée), que ce soit une explosion d'entité (creeper, TNT) ou de
        bloc ;
    -   mobs hostiles : aucun spawn naturel (`CreatureSpawnEvent` de raison
        `NATURAL` annulé pour tout `Monster`) ;
    -   claims : interdits dans le Hub (le Hub est un monde entièrement
        géré par le plugin, pas un terrain à s'approprier).

Au démarrage (ou au (re)chargement tardif du monde, par exemple par
Multiverse-Core après RPGQuest), la ligne attendue en console est :

```
Règles du monde Hub appliquées : world_hub (jour et météo permanents).
```

L'absence de cette ligne après le redémarrage final signale que
`world_hub` n'a pas été détecté par RPGQuest (nom de monde incorrect en
transfert, ou `hub.world` mal configuré dans `config.yml`).

### Citizens : vérifier le chargement des PNJ

Les PNJ sont stockés dans `plugins/Citizens/saves.yml`. Après migration,
vérifier dans les logs de démarrage le nombre de PNJ chargés par Citizens
(ligne de log Citizens au démarrage, ex. « Loaded N NPCs »).

État connu au moment de la rédaction (environnement de développement,
`world_hub`) :

-   ID `0` — Guide
-   ID `1` — Libraire

Si le nombre de PNJ chargés ne correspond pas à ce qui est attendu pour le
serveur source, ou si l'un de ces PNJ n'apparaît pas en jeu à son
emplacement habituel, suspecter un `saves.yml` désynchronisé du `data.db`
migré (cf. [règle d'or](#règle-dor--savesyml--datadb-sont-une-seule-unité)).

---

## Mise à jour du seul JAR RPGQuest (scénario 2)

À utiliser quand un serveur RPGQuest tourne déjà en production sur
VeryGames et que seule une nouvelle version compilée du plugin doit être
déployée — **aucune donnée de jeu, aucun monde, aucune configuration
d'autre plugin n'est touché**.

> **Chemin recommandé : le script automatisé.** Les étapes 1, 3 et 4
> ci-dessous (build, backup daté, remplacement du seul JAR) sont exactement
> ce que fait `scripts/deploy-verygames.sh` — voir
> [Déploiement automatisé](#déploiement-automatisé-scriptsdeploy-verygamessh).
> Seuls l'arrêt (étape 2) et le redémarrage/vérification (étapes 5-6)
> restent manuels. La procédure manuelle ci-dessous reste la référence si
> le script n'est pas utilisable.

1.  Compiler la nouvelle version (`./gradlew clean build`).
2.  Arrêter le serveur (`stop`, arrêt propre).
3.  Sauvegarder l'ancien `rpgquest-<version>.jar` et `plugins/RPGQuest/data.db`
    (voir [Rollback](#rollback)) avant tout remplacement.
4.  Remplacer uniquement `plugins/RPGQuest-*.jar` par le nouveau JAR (FTP).
    Ne toucher à aucun autre fichier de `plugins/RPGQuest/` (config,
    quêtes, `data.db`...) : ils persistent tels quels, RPGQuest ne
    régénère jamais un fichier déjà présent (voir
    [docs/LOCAL_SERVER.md](../LOCAL_SERVER.md)).

    **Exception volontaire depuis la mission « cohérence du config.yml »** :
    `config.yml` (s'il existe déjà) est **complété** au premier démarrage sur le nouveau JAR — les
    clés apparues depuis (ex. `dialogue`, `claims`, `progression`...) sont ajoutées avec leurs
    valeurs par défaut actuelles, jamais une valeur déjà présente n'est modifiée, et une sauvegarde
    `config.yml.bak` est créée automatiquement avant toute écriture réelle (voir
    `config.ConfigFileCompleter`, section « Configuration » de
    [docs/CLAIMS.md](../CLAIMS.md) pour le détail). Un redémarrage sur un `config.yml` déjà complet
    n'écrit jamais rien (idempotent).
5.  Redémarrer.
6.  Vérifier `/rpgquest version` (nouvelle version affichée), l'absence
    d'exception au démarrage, et que `world_hub`/les PNJ/les quêtes sont
    toujours intacts (sous-ensemble pertinent de la
    [checklist finale](#checklist-finale)).

**Cas particulier — étape de diagnostic WorldPortal** (outils `/rpgadmin worldportal here`/`debug`,
logs `[TP-TRACE]`, voir [docs/TRAVEL.md](../TRAVEL.md)) : ce scénario 2 s'applique tel quel, sans
aucune étape supplémentaire — `data.db`, `world_hub`, les autres mondes, `Citizens/saves.yml` et
tout autre fichier runtime existant ne doivent **jamais** être remplacés pour cette mise à jour.
La table de progression story déjà présente en base et les YAML de portails simples ne sont pas
touchés par cette étape (aucune nouvelle migration de schéma).

---

## Checklist finale

À dérouler intégralement après tout redémarrage complet (installation
neuve ou migration complète) :

-   [ ] Paper démarre sans `ERROR` dans les logs.
-   [ ] Java 21 confirmé dans les logs de démarrage.
-   [ ] Les 4 plugins (WorldEdit, Citizens, Multiverse-Core, RPGQuest)
    apparaissent chargés (`/plugins`, tous en vert).
-   [ ] `world_hub` chargé (`/mv list`).
-   [ ] Règles du monde Hub appliquées (ligne de log `Règles du monde Hub
    appliquées : world_hub`).
-   [ ] PNJ Guide présent en jeu.
-   [ ] PNJ Libraire présent en jeu.
-   [ ] Dialogues fonctionnels (clic droit sur Guide/Libraire ouvre bien un
    dialogue).
-   [ ] Spawn RPGQuest correct (`/rpgadmin spawn tp` arrive au bon endroit).
-   [ ] Jour fixe dans `world_hub` (l'heure ne progresse pas).
-   [ ] Météo claire en permanence dans `world_hub`.
-   [ ] Un joueur **non-OP** ne peut pas casser/poser de bloc dans
    `world_hub`.
-   [ ] Un joueur **OP** (`rpgquest.admin.world`) peut construire dans
    `world_hub`.
-   [ ] Aucun dégât subi par un joueur dans `world_hub` (chute, faim, feu,
    etc.).
-   [ ] PvP bloqué dans `world_hub`.
-   [ ] Claims interdits dans `world_hub`.
-   [ ] Portails fonctionnels (canalisation puis téléportation).
-   [ ] Quêtes chargées sans erreur (`/quest admin validate` → `0
    erreur(s)`, en particulier aucune fixture de test comme
    `broken_quest.yml` n'a été transférée).
-   [ ] Redémarrage complet effectué (pas seulement un `/rpgquest reload`)
    pour valider ce qui précède dans les conditions réelles du démarrage.
-   [ ] Persistance vérifiée après ce redémarrage (config, `data.db`,
    positions des PNJ inchangées).

---

## Rollback

> **Rollback du seul JAR (scénario 2) :** utiliser
> `scripts/rollback-verygames.sh --latest` — il restaure le backup daté
> créé automatiquement par `deploy-verygames.sh` lors du dernier
> déploiement, après avoir sauvegardé la version en ligne. Voir
> [Déploiement automatisé § 4](#4-rollback-scriptsrollback-verygamessh).
> La procédure ci-dessous couvre le rollback **complet** (JAR + données +
> mondes), pour une migration (scénario 3) qui aurait mal tourné.

Avant toute opération de mise à jour ou de migration, sauvegarder (copie
FTP vers un poste local ou un stockage externe, jamais uniquement sur le
serveur VeryGames lui-même) :

-   l'ancien JAR RPGQuest (`plugins/RPGQuest-<ancienne_version>.jar`) ;
-   le dossier `world_hub/` complet (à la racine du serveur) ;
-   `plugins/Citizens/saves.yml` **et** `plugins/RPGQuest/data.db`
    **ensemble**, dans la même sauvegarde, jamais l'un sans l'autre (cf.
    [règle d'or](#règle-dor--savesyml--datadb-sont-une-seule-unité)) ;
-   les fichiers de configuration : `plugins/RPGQuest/config.yml`,
    `messages.yml`, `spawn.yml`, ainsi que les dossiers de contenu
    (`quests/`, `dialogues/`, `items/`, `merchants/`, `mobs/`, `portals/`,
    `destinations/`, `recipes/`, `resource-nodes/`, `store-products/`,
    `world-portals/`, `zones/`), et `plugins/Multiverse-Core/worlds.yml`.

### Procédure de restauration

1.  Arrêter le serveur.
2.  Remettre en place l'ancien JAR RPGQuest (supprimer/remplacer le
    nouveau).
3.  Remettre en place `world_hub/` sauvegardé (écraser la version en
    échec).
4.  Remettre en place `saves.yml` et `data.db` **ensemble**, depuis la même
    sauvegarde.
5.  Remettre en place les fichiers/dossiers de configuration sauvegardés.
6.  Redémarrer et dérouler la [checklist finale](#checklist-finale) pour
    confirmer le retour à l'état stable précédent.
