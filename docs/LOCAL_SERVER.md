# Serveur Paper local (workspace)

Le plugin peut être compilé et testé sur un vrai serveur Paper directement
depuis le workspace, sans copier manuellement de jar — via le plugin Gradle
[`xyz.jpenilla.run-paper`](https://github.com/jpenilla/run-task) déjà
déclaré dans `build.gradle.kts` (`runServer { minecraftVersion("1.21.11") }`,
la même version que `paper-api` en dépendance : les deux doivent toujours
rester synchronisées).

## Premier lancement

```
gradlew.bat runServer     # Windows
./gradlew runServer       # Linux/macOS
```

(ou tâche VS Code **Gradle: runServer (Paper local, run/)**, voir plus bas.)

1.  Le jar Paper 1.21.11 correspondant est téléchargé automatiquement et mis
    en cache par le plugin Gradle (pas de téléchargement manuel, pas de
    nouveau téléchargement aux lancements suivants).
2.  Le plugin RPGQuest est compilé et copié dans `run/plugins/` à chaque
    lancement (toujours la version courante du code, jamais une copie
    obsolète).
3.  Le serveur s'arrête immédiatement avec un message demandant d'accepter
    l'EULA Mojang.
4.  Ouvrir `run/eula.txt`, remplacer `eula=false` par `eula=true`
    (uniquement pour un usage de test local — ne jamais committer cette
    acceptation ailleurs).
5.  Relancer `runServer` : le serveur démarre pour de bon, génère un monde
    dans `run/world*`, et charge RPGQuest (`plugins/RPGQuest/` — config,
    quêtes, dialogues, objets, recettes, nœuds de ressource, `data.db`).

## Arrêter le serveur

Taper `stop` dans la console du serveur (recommandé — arrêt propre,
`onDisable()` s'exécute, `data.db` est fermée correctement) ou `Ctrl+C`
depuis le terminal qui exécute `runServer`.

**Aucun `/reload` Bukkit** (commande vanilla, pas `/rpgquest reload`) :
`/reload` recharge tous les plugins du serveur de façon non fiable et
connue pour corrompre l'état de nombreux plugins — toujours arrêter puis
relancer `runServer` pour tester un changement de code. `/rpgquest reload`
(recharge uniquement `config.yml`) reste sûr et utilisable normalement.

## Cycle de développement

1.  Modifier le code.
2.  Arrêter le serveur s'il tourne (`stop`).
3.  Relancer `gradlew.bat runServer` (ou la tâche VS Code) : recompile et
    redémarre automatiquement avec le code à jour.
4.  `run/plugins/RPGQuest/` (config, quêtes, dialogues, objets, recettes,
    nœuds, `data.db`) **persiste** entre les redémarrages (pas régénéré,
    sauf les fichiers d'exemple absents) — modifier `messages.yml` ou tout
    autre fichier YAML, redémarrer, et constater que le changement est
    toujours là confirme la persistance.
5.  Pour repartir d'un état totalement propre (nouveau monde, nouvelle
    base de données) : supprimer le dossier `run/` en entier, puis relancer
    `runServer` (re-télécharge Paper si besoin, régénère tout).

## `run/` n'est jamais commité

`.gitignore` ignore `run/` dans son intégralité (monde, logs, `data.db`
locale, `eula.txt`, fichiers temporaires du serveur) : aucun état de test
local ne peut atterrir accidentellement dans un commit.

## Intégration VS Code

`.vscode/tasks.json` (committé, contrairement au reste de `.vscode/*`)
fournit trois tâches :

-   **Gradle: clean build** (tâche de build par défaut, `Ctrl+Shift+B`) —
    `gradlew clean build`.
-   **Gradle: test** (tâche de test par défaut) — `gradlew test`.
-   **Gradle: runServer (Paper local, run/)** — lance le serveur local dans
    un panneau de terminal dédié, en arrière-plan.

## Tests

Automatisés : couverts par la suite JUnit existante (`gradlew test`), ce
document ne concerne que le cycle manuel autour du serveur réel.

**Vérifié réellement pendant cette étape** (`gradlew runServer` lancé deux
fois dans cet environnement, sans client Minecraft — seule la console
serveur était observable, pas de RCON/stdin interactif disponible ici) :

-   démarrage complet sans stack trace : `Done (21.787s)! For help, type
    "help"` ;
-   `RPGQuest 0.1.0-SNAPSHOT activé` — tous les services démarrent dans
    l'ordre attendu (config, base de données, moteur de quêtes, objets,
    recettes, nœuds de ressource, dialogues, journal) ;
-   chargement réel des ressources embarquées sur un vrai serveur Paper (pas
    seulement MockBukkit) : 3 quêtes, 4 objets, 3 recettes, 1 type de nœud,
    1 dialogue, `0 nœud de ressource` restauré (base neuve) ;
-   un fichier de quête volontairement invalide laissé dans
    `run/plugins/RPGQuest/quests/` (test manuel antérieur) a été rejeté
    proprement (avertissements détaillés) sans empêcher le chargement des
    3 quêtes valides — confirme en conditions réelles le comportement déjà
    couvert par les tests automatisés (`QuestLoaderTest`) ;
-   un verrou de session orphelin (`run/world/session.lock`, laissé par un
    précédent processus `runServer` mal arrêté) a été rencontré puis résolu
    en identifiant et terminant le processus Java concerné — à surveiller
    si `runServer` échoue avec `DirectoryLock`/`IOException` : toujours
    arrêter proprement (`stop`) plutôt que tuer le processus quand c'est
    possible, précisément pour éviter ce genre de verrou résiduel.

**Reste `PENDING MANUAL VALIDATION`** (nécessite un vrai client Minecraft
et/ou une console interactive, indisponibles ici) :

-   suppression complète de `run/` puis `runServer` (re-téléchargement
    propre de Paper depuis zéro).
-   `/plugins` liste RPGQuest comme activé (vu en jeu/console interactive).
-   `/rpgquest version` répond correctement.
-   modifier un message dans `messages.yml`, redémarrer, constater la
    persistance du changement.
-   redémarrer une seconde fois, constater que l'état (config, `data.db`)
    est toujours celui attendu (pas de régénération intempestive).
-   arrêt propre via la commande `stop` tapée en console (non exercé ici,
    faute de console interactive — seul un arrêt par terminaison de
    processus a été possible).
