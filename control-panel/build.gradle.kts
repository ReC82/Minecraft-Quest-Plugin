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
dependencies {
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.lodygames.rpgquest.panel.PanelMain")
}

tasks {
    test {
        useJUnitPlatform()
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
