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
    // Citizens : API et plugin, tous deux en compileOnly. L'implémentation réelle est fournie à
    // l'exécution par le plugin Citizens lui-même, s'il est installé (soft-dependency déclarée dans
    // plugin.yml — voir com.lodygames.rpgquest.npc.NpcIdentityService).
    maven("https://maven.citizensnpcs.co/repo") {
        name = "citizensnpcs"
    }
    // WorldEdit : API seule, en compileOnly. Le moteur réel est fourni à l'exécution par le plugin
    // WorldEdit installé (soft-dependency dans plugin.yml) — voir
    // com.lodygames.rpgquest.building.worldedit.WorldEditSchematicGateway.
    maven("https://maven.enginehub.org/repo/") {
        name = "enginehub"
    }
}

configurations {
    testCompileOnly.get().extendsFrom(compileOnly.get())
    testRuntimeOnly.get().extendsFrom(compileOnly.get())
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.citizensnpcs:citizensapi:2.0.43-SNAPSHOT")
    // Issue #165 — « regarder les joueurs » (LookClose) et « promenade » (fournisseur `wander` du
    // trait `waypoints`) vivent dans le PLUGIN Citizens, pas dans citizensapi. Les piloter exige
    // donc `citizens-main`, en compileOnly STRICT : jamais empaqueté dans notre JAR, fourni à
    // l'exécution par le plugin installé — exactement comme citizensapi, paper-api ou log4j-core.
    //
    // Pourquoi une dépendance plutôt que de la réflexion : les accesseurs nécessaires sont publics
    // et typés (LookClose#lookClose(boolean)/setRange(double), WanderWaypointProvider#setXYRange/
    // addRegionCentre, Waypoints#setWaypointProvider). Les appeler par réflexion ou par les clés de
    // persistance internes coûterait la vérification du compilateur sans rien gagner.
    //
    // Pas de dépendance implicite ajoutée : dans le POM de citizens-main, WorldGuard, Denizen,
    // PlaceholderAPI, Vault, Spigot et packetevents sont tous en scope `provided`, que Gradle ne
    // résout pas transitivement. Seul `citizensapi` (scope `compile`) remonte — et il est déjà là.
    //
    // Compatibilité : toute la surface Citizens « plugin » est confinée à
    // com.lodygames.rpgquest.npc.CitizensBehaviourBridge, qui intercepte LinkageError et renvoie un
    // refus nommé si la build installée ne correspond pas. Voir docs/deployment/CITIZENS.md.
    //
    // `isTransitive = false` : la seule dépendance que Gradle remonterait est `libby-bukkit`, le
    // chargeur de bibliothèques d'exécution de Citizens — absent de nos dépôts et sans aucun rôle à
    // la compilation. `citizensapi` est déclaré explicitement juste au-dessus, donc rien n'est perdu.
    compileOnly("net.citizensnpcs:citizens-main:2.0.43-SNAPSHOT") {
        isTransitive = false
    }
    // LuckPerms : API SEULE, fournie à l'exécution par le plugin LuckPerms s'il est installé
    // (compileOnly, soft-dependency dans plugin.yml — voir
    // com.lodygames.rpgquest.permission.LuckPermsBridge). Même conception que CitizensAPI : le
    // plugin démarre et fonctionne normalement SANS LuckPerms, et le pont se déclare alors
    // indisponible avec son motif plutôt que d'échouer.
    compileOnly("net.luckperms:api:5.4")

    // WorldEdit (issue #213, lot « placement ») : moteur de schematics, FOURNI PAR LE SERVEUR.
    // Version alignée sur celle réellement installée en DEV (7.4.1, vérifiée par `/version
    // WorldEdit`). compileOnly STRICT : jamais empaqueté dans notre JAR.
    //
    // Pourquoi une dépendance plutôt que de la réflexion : lire un `.schem`, appliquer une rotation
    // et coller sont des appels publics et typés (ClipboardFormats, ClipboardHolder,
    // AffineTransform, EditSession). Les faire par réflexion coûterait la vérification du
    // compilateur sans rien gagner, sur du code qui écrit dans le monde — le dernier endroit où
    // l'on veut deviner.
    //
    // `worldedit-bukkit` et non `worldedit-core` : c'est lui qui apporte BukkitAdapter, seul point
    // de traduction entre un World Bukkit et un World WorldEdit. `isTransitive = false` : la seule
    // chose dont nous avons besoin en plus est worldedit-core, déclaré juste en dessous ; le reste
    // du POM (Spigot, bstats, Paper-lib…) n'a aucun rôle à la compilation et Gradle ne doit pas
    // aller le chercher.
    //
    // Compatibilité : toute la surface WorldEdit est confinée à
    // com.lodygames.rpgquest.building.worldedit.WorldEditSchematicGateway, qui intercepte
    // LinkageError et se déclare INDISPONIBLE avec son motif si la build installée ne correspond
    // pas. Le plugin démarre et fonctionne normalement SANS WorldEdit — seule la pose de bâtiments
    // est alors refusée, en le disant.
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.1") {
        isTransitive = false
    }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.4.1")

    // Issue #95 — capture de la console serveur pour la page « Exploitation serveur » du Control
    // Panel. `log4j-api` vient déjà de paper-api, mais attacher un appender exige `log4j-core`,
    // FOURNI PAR LE SERVEUR : donc compileOnly, jamais empaqueté. Ce n'est ni du NMS ni de la
    // réflexion CraftBukkit — c'est une bibliothèque de journalisation tierce.
    //
    // Pourquoi Log4j et pas java.util.logging : Paper route `getSLF4JLogger()` directement vers
    // Log4j2, donc un Handler JUL ne verrait PAS nos propres lignes (la quasi-totalité de nos logs).
    // `ops.ConsoleTap` capture `LinkageError` : si la bibliothèque est absente ou incompatible, la
    // console du panel s'affiche « indisponible » et le serveur démarre normalement.
    compileOnly("org.apache.logging.log4j:log4j-core:2.24.1")
    testImplementation("org.apache.logging.log4j:log4j-core:2.24.1")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.110.0")

    // Persistance : ni le driver JDBC ni le pool ne sont empaquetés. Ils sont déclarés dans
    // plugin.yml (libraries:) et résolus à l'exécution par le LibraryLoader de Paper. Ici :
    //   - sqlite-jdbc         : uniquement pour les tests JUnit en JVM nue (jamais importé côté main) ;
    //   - HikariCP            : référencé côté main (com.lodygames.rpgquest.database.MySqlDatabaseEngine)
    //                           -> compileOnly (comme paper-api) ; les configs test héritent de compileOnly ;
    //   - mariadb-java-client : chargé par URL JDBC, jamais importé -> runtime de test seulement.
    testImplementation("org.xerial:sqlite-jdbc:3.53.2.1")
    compileOnly("com.zaxxer:HikariCP:5.1.0")
    testRuntimeOnly("org.mariadb.jdbc:mariadb-java-client:3.4.1")
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
        maxParallelForks = 1
        // Le tas du worker de test est ajustable par variable d'environnement pour les machines de
        // build très contraintes (défaut : laissé à la JVM). Ne pas utiliser forkEvery : il force
        // la ré-initialisation du registre Material de Bukkit à chaque nouveau fork, ce qui touche
        // un chemin non implémenté de MockBukkit (UnsafeValues#fromLegacy).
        System.getenv("RPGQUEST_TEST_MAX_HEAP")?.let { maxHeapSize = it }
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
