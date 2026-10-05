plugins {
    java
    application
}

group = "com.lodygames.rpgquest"
version = "0.1.0-SNAPSHOT"
description = "Synchronisation du forum communautaire Discord avec les issues GitHub (issue #202)"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
}

// Aucune dépendance vers Paper / io.papermc, ni vers le plugin, ni vers :web-api, ni vers
// :control-panel. Ce service ne touche ni data.db, ni store.db, ni control-panel.db.
//   - HTTP client  : java.net.http (JDK) — Discord REST v10 et GitHub REST v3
//   - JSON         : codec maison (discord.json.Json), même décision d'isolation que les
//                    trois autres modules du dépôt (chacun porte sa copie minimale)
//   - Persistance  : sync.db, SQLite, TOTALEMENT séparée des autres bases du projet
dependencies {
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.lodygames.rpgquest.discord.DiscordSyncMain")
}

tasks {
    test {
        useJUnitPlatform()
        // Le test d'intégration réel (LiveGitHubSyncIT) est désactivé sauf demande explicite :
        // `./gradlew :discord-sync:test -DdiscordSyncLiveGitHub=true`. Gradle ne propage pas les
        // propriétés système au worker de test, d'où ce relais (même mécanisme que
        // :control-panel avec panelProdDbCopy). Sans cette propriété, aucun test ne touche au
        // réseau et aucune issue ne peut être créée par accident.
        listOf("discordSyncLiveGitHub").forEach { key ->
            System.getProperty(key)?.let { systemProperty(key, it) }
        }
    }

    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    jar {
        manifest {
            attributes("Main-Class" to "com.lodygames.rpgquest.discord.DiscordSyncMain")
        }
    }
}

// Livraison autonome : `./gradlew :discord-sync:installDist` -> build/install/discord-sync/
// (script bin/discord-sync + lib/ avec sqlite-jdbc). `java -jar` sur le jar nu ne suffit pas.
