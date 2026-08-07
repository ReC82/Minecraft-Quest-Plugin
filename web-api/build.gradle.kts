plugins {
    java
    application
}

group = "be.lloyd.rpgquest"
version = "0.1.0-SNAPSHOT"
description = "RPGQuest web-api - portail web read-only, séparé du plugin Paper"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
}

// Aucune dépendance vers le plugin Paper ni vers io.papermc : ce module ne
// doit jamais pouvoir toucher data.db ou l'API Bukkit (mission étape 21,
// points 1-2). Zéro dépendance externe obligatoire : HTTP (JDK
// com.sun.net.httpserver) et JSON (codec maison) sont tous deux fournis par
// le JDK ou écrits ici, voir docs/WEB_API.md.
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("be.lloyd.rpgquest.webapi.WebApiMain")
}

tasks {
    test {
        useJUnitPlatform()
    }

    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}
