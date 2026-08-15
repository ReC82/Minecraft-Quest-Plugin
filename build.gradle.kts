import java.security.MessageDigest

plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "com.lodygames.rpgquest"
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
    // CitizensAPI uniquement (pas le plugin complet citizens-main) : l'implémentation réelle est
    // fournie à l'exécution par le plugin Citizens lui-même, s'il est installé (compileOnly,
    // soft-dependency déclarée dans plugin.yml — voir com.lodygames.rpgquest.npc.NpcIdentityService).
    maven("https://maven.citizensnpcs.co/repo") {
        name = "citizensnpcs"
    }
}

configurations {
    testCompileOnly.get().extendsFrom(compileOnly.get())
    testRuntimeOnly.get().extendsFrom(compileOnly.get())
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.citizensnpcs:citizensapi:2.0.43-SNAPSHOT")

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
    val buildResourcePack = register<Zip>("buildResourcePack") {
        group = "rpgquest"
        description = "Empaquette resource-pack/ en un zip distribuable (build/resource-pack/)."
        from("resource-pack")
        archiveFileName.set("RPGQuest-resource-pack.zip")
        destinationDirectory.set(layout.buildDirectory.dir("resource-pack"))
        // Horodatage désactivé : un zip reproductible produit toujours le même SHA-1
        // pour un contenu inchangé, comme attendu par resource-pack.sha1 dans config.yml.
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    register("resourcePackSha1") {
        group = "rpgquest"
        description = "Calcule le SHA-1 du zip produit par buildResourcePack et l'écrit à côté (.sha1)."
        dependsOn(buildResourcePack)
        doLast {
            val zip = buildResourcePack.get().archiveFile.get().asFile
            val sha1 = MessageDigest.getInstance("SHA-1")
                .digest(zip.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) }
            zip.resolveSibling(zip.name + ".sha1").writeText(sha1)
            logger.lifecycle("Resource pack : {} (sha1={})", zip, sha1)
        }
    }

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
