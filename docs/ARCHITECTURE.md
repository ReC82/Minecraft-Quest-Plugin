# Architecture

## Arborescence des packages (prévue)

    be.lloyd.rpgquest
    ├── RPGQuestPlugin       (point d'entrée)
    ├── bootstrap
    ├── command
    ├── config
    ├── database
    ├── dialogue
    ├── item
    ├── quest
    ├── resource
    ├── ui
    └── util

Seuls `RPGQuestPlugin` et `command` existent à ce stade (étape d'initialisation).
Les autres packages seront créés au fur et à mesure des étapes du TODO.

## Flux principal (cible)

Dialogue → Acceptation de quête → Progression → Récolte / Combat →
Fabrication → Remise → Récompense

## Décisions techniques

-   **Java 21** (toolchain Gradle), imposé par Paper 1.21.11.
-   **Paper 1.21.11** (`io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`).
    Paper est passé à un nouveau schéma de version (CalVer, ex. `26.2.build.98-stable`)
    à partir des versions Minecraft 26.1+. La version 1.21.11 reste disponible sous
    l'ancien format `{VERSION}-R0.1-SNAPSHOT` sur le dépôt Maven officiel
    (`https://repo.papermc.io/repository/maven-public/`) : aucune incompatibilité
    documentée, donc aucun changement de version n'a été effectué.
-   **Gradle 9.6.1** avec Kotlin DSL, wrapper committé.
-   **Plugin `xyz.jpenilla.run-paper` 3.0.2** pour la tâche `runServer`.
-   **Descripteur de plugin : `plugin.yml`** (et non le manifeste `paper-plugin.yml`).
    La documentation officielle PaperMC indique explicitement que le nouveau
    manifeste est encore expérimental et « not recommended » ; `plugin.yml`
    reste donc le format recommandé actuellement.
-   **Tests : JUnit 5 (`junit-jupiter` 5.14.4) + MockBukkit (`mockbukkit-v1.21` 4.110.0)**.
    `mockbukkit-v1.21` cible la branche 1.21.x et n'est pas versionné par patch ;
    aucune incompatibilité connue avec 1.21.11 à ce jour.
    MockBukkit instancie le plugin via un sous-classement dynamique (ByteBuddy) :
    la classe principale du plugin ne doit donc pas être `final`.
    `paper-api` étant en `compileOnly`, les configurations `testCompileOnly` et
    `testRuntimeOnly` héritent explicitement de `compileOnly` pour exposer les
    types Bukkit/Paper au classpath de test.
-   **Adventure / MiniMessage** pour tous les textes envoyés aux joueurs.
-   **PersistentDataContainer** prévu pour tous les objets personnalisés (aucun
    objet créé à ce stade).
-   **SQLite asynchrone** : non implémenté à cette étape (prévu au TODO).

## Limites connues de cette étape

-   Aucune fonctionnalité de jeu (quêtes, dialogues, objets, SQLite) n'est
    encore implémentée : uniquement le squelette du plugin et les commandes
    `/rpgquest version` et `/rpgquest help`.
-   Un seul test unitaire (chargement du plugin + enregistrement de la
    commande) sert de garde-fou pour la configuration MockBukkit/JUnit5.
-   Sur la console Windows, les caractères accentués des logs (SLF4J) peuvent
    s'afficher incorrectement (`�`) selon la page de code active du terminal.
    Le contenu réel des chaînes est en UTF-8 et n'est pas affecté ; c'est un
    problème d'affichage console, pas un bug du plugin.
