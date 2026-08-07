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
//
// Exception documentée (mission étape 22) : org.xerial:sqlite-jdbc, le même
// driver que le plugin, mais empaqueté ici en vraie dépendance de runtime —
// contrairement au plugin, web-api est un simple processus JVM sans
// LibraryLoader Paper pour le résoudre à l'exécution. Une boutique gérant
// des commandes/livraisons idempotentes a structurellement besoin d'un
// stockage transactionnel propre (`store.db`, entièrement séparé de
// data.db) ; un fichier JSON n'offre aucune garantie de cohérence sous
// écritures concurrentes. Voir docs/STORE.md.
dependencies {
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")

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
