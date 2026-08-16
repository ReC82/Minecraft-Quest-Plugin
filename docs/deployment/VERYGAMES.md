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

-   **Protocole/port :** FTP, port `21`.
-   **Host / utilisateur / mot de passe :** récupérés depuis le panel
    VeryGames (Gameserver → onglet FTP), propres à chaque instance de
    serveur.
-   **Ne jamais stocker le mot de passe FTP dans Git**, ni dans ce
    document, ni dans un fichier de configuration versionné. Le conserver
    uniquement dans un gestionnaire de mots de passe ou les variables
    d'environnement locales de la personne qui déploie.
-   **Arborescence côté serveur :**
    -   les plugins se déposent dans `/plugins/` ;
    -   les mondes (`world`, `world_nether`, `world_the_end`, `world_hub`,
        ...) se trouvent **à la racine** du serveur, pas dans `/plugins/`.

Un client FTP classique (FileZilla, WinSCP...) ou `sftp`/`ftp` en ligne de
commande convient. Toutes les étapes de transfert ci-dessous supposent une
connexion FTP déjà établie vers la racine du serveur VeryGames.

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

1.  Compiler la nouvelle version (`./gradlew clean build`).
2.  Arrêter le serveur (`stop`, arrêt propre).
3.  Sauvegarder l'ancien `rpgquest-<version>.jar` et `plugins/RPGQuest/data.db`
    (voir [Rollback](#rollback)) avant tout remplacement.
4.  Remplacer uniquement `plugins/RPGQuest-*.jar` par le nouveau JAR (FTP).
    Ne toucher à aucun autre fichier de `plugins/RPGQuest/` (config,
    quêtes, `data.db`...) : ils persistent tels quels, RPGQuest ne
    régénère jamais un fichier déjà présent (voir
    [docs/LOCAL_SERVER.md](../LOCAL_SERVER.md)).
5.  Redémarrer.
6.  Vérifier `/rpgquest version` (nouvelle version affichée), l'absence
    d'exception au démarrage, et que `world_hub`/les PNJ/les quêtes sont
    toujours intacts (sous-ensemble pertinent de la
    [checklist finale](#checklist-finale)).

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
