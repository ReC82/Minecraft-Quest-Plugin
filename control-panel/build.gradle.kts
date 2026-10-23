plugins {
    java
    application
}

group = "com.lodygames.rpgquest"
version = "0.1.0-SNAPSHOT"
description = "RPGQuest Control Panel - application d'administration, distincte du plugin et du portail web-api"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
}

// Aucune dépendance vers Paper / io.papermc, ni vers le module :web-api, ni
// vers le driver JDBC de data.db. Le Control Panel ne parle au plugin que par
// le bridge HTTP versionné (voir docs/control-panel/RPGQUEST_BRIDGE.md).
//   - HTTP serveur : com.sun.net.httpserver (JDK)
//   - HTTP client  : java.net.http (JDK)
//   - JSON         : codec maison (panel.json.Json)
//   - Persistance panel (audit log, futurs users/roles) : control-panel.db,
//     SQLite, TOTALEMENT séparée de data.db et de store.db.
//
// Exception unique et délibérée (issue #42) : le pilote MariaDB, pour TESTER la connexion à la base
// cible d'une future migration (page /database, session en lecture seule, métadonnées seulement).
// Le panel n'ouvre toujours ni data.db ni aucune donnée métier du plugin.
dependencies {
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.4.1")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Issue #373 : oracle de test — le moteur lit les dialogues avec SnakeYAML ; MiniYaml doit en
    // donner exactement la même lecture pour chaque fichier du dépôt. Jamais embarqué dans le panel.
    testImplementation("org.yaml:snakeyaml:2.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.lodygames.rpgquest.panel.PanelMain")
}

tasks {
    test {
        useJUnitPlatform()
        // Un seul worker de test, comme le module plugin. Les tests de ce module démarrent de
        // VRAIS serveurs HTTP sur des ports éphémères : les paralléliser multiplie les JVM, les
        // serveurs et les bases SQLite temporaires simultanés, ce qui sature une machine de build
        // contrainte bien avant d'aller plus vite.
        maxParallelForks = 1
        // Même borne ajustable que le module plugin (RPGQUEST_TEST_MAX_HEAP). Sans elle, la JVM de
        // test prend son défaut — le quart de la mémoire physique — et sur un hôte de 3,8 Gio cela
        // suffit à faire basculer la machine dans le swap pendant la suite.
        System.getenv("RPGQUEST_TEST_MAX_HEAP")?.let { maxHeapSize = it }
        // Laisse passer une poignée de propriétés d'intégration optionnelles vers le worker de
        // test (Gradle ne les propage pas par défaut). Ex. : -DpanelProdDbCopy=/chemin/copie.db
        // pour un smoke authentifié contre une copie de la vraie base (régression #103).
        listOf("panelProdDbCopy").forEach { key ->
            System.getProperty(key)?.let { systemProperty(key, it) }
        }
    }

    // Issue #428 : le sprite d'icônes pixel art et sa table material -> icône sont ceux du portail
    // public. Une seule source (web-api/src/main/resources/portal), copiée dans le classpath du panel
    // sous assets/lq/ : jamais de copie manuelle qui divergerait. Copie de fichiers seulement, aucune
    // dépendance de code vers :web-api.
    processResources {
        from(rootProject.file("web-api/src/main/resources/portal")) {
            include("lq-icons.svg", "material-map.json")
            into("assets/lq")
        }
        // Issue #747 : le registre des blocs de la version servie, celui que le plugin vérifie. Le
        // panel y prend la palette d'identifiants valides donnée à l'IA et relève d'un coup tous les
        // identifiants inventés d'un plan — jamais une liste recopiée à la main.
        from(rootProject.file("src/main/resources/building")) {
            include("blocks-*.txt")
            into("building")
        }
    }

    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    jar {
        manifest {
            attributes("Main-Class" to "com.lodygames.rpgquest.panel.PanelMain")
        }
    }
}

// Livraison autonome : `./gradlew :control-panel:installDist` -> build/install/control-panel/
// (script bin/control-panel + lib/ avec sqlite-jdbc). `java -jar` sur le jar nu ne suffit pas
// (dépendance runtime non embarquée) ; utiliser la distribution ou `:run` en local.
