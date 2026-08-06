plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "be.lloyd.rpgquest"
version = "0.1.0-SNAPSHOT"
description = "RPGQuest - plugin RPG pour Paper"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

configurations {
    testCompileOnly.get().extendsFrom(compileOnly.get())
    testRuntimeOnly.get().extendsFrom(compileOnly.get())
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.110.0")

    // Le driver JDBC n'est pas empaqueté : il est déclaré dans plugin.yml
    // (libraries:) et résolu à l'exécution par le LibraryLoader de Paper.
    // Il n'est utilisé ici que pour exécuter les tests JUnit en JVM nue.
    testImplementation("org.xerial:sqlite-jdbc:3.53.2.1")
}

tasks {
    test {
        useJUnitPlatform()
    }

    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    javadoc {
        options.encoding = "UTF-8"
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        expand(props)
    }

    runServer {
        minecraftVersion("1.21.11")
    }
}
