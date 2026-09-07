# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-07
* Heure : 21:11
* Sujet : Issue #41 — backend MySQL/MariaDB pour RPGQuest sur VeryGames
* Statut : DONE
* Branche Git : `feat/41-mariadb-backend`
* Commit actuel si disponible : `19b1bbe`
* Début de la tâche : non mesurable
* Fin de la tâche : 2026-09-07 21:11:00
* Durée totale : non mesurable

## Demande
Ajouter un backend MySQL/MariaDB robuste à RPGQuest pour VeryGames, sans supprimer SQLite, avec configuration, pool de connexions, migrations, compatibilité SQL, tests réels MariaDB et documentation. La migration des données SQLite existantes et la bascule production restent hors périmètre et sont prévues dans un ticket séparé.

## Analyse
Le travail s'appuie sur l'abstraction de persistance réalisée dans l'issue #40. Le backend distant nécessite un pool de connexions et un dialecte SQL distinct afin de conserver les mêmes repositories et le même système de migrations sur SQLite et MariaDB.

Une incompatibilité de classpath SLF4J liée aux dépendances HikariCP/MariaDB a été identifiée pendant les tests MockBukkit. Les dépendances de test/runtime ont été ajustées afin de ne plus injecter cette implémentation transitive conflictuelle.

Les premiers essais de suite complète sur une instance AWS t3.micro ont également montré une forte saturation mémoire/swap et des timeouts non représentatifs. La validation finale a été refaite sur une t3.medium (4 Gio), sans swap utilisé au démarrage des tests.

## Travail effectué
- Implémentation réelle de `MySqlDatabaseEngine` avec HikariCP.
- Ajout/extension de la configuration MySQL/MariaDB dans `DatabaseSettings` et validation associée.
- Adaptation de `DatabaseManager` au cycle de vie des connexions poolées.
- Extension de `SqlDialect` et `MySqlDialect` pour traduire les constructions SQLite utilisées par RPGQuest.
- Adaptation des repositories persistants afin de passer par le dialecte.
- Compatibilité des migrations V1 à V17 avec MariaDB.
- Conservation du backend SQLite et de son comportement existant.
- Ajout de tests unitaires et d'intégration MariaDB.
- Documentation architecture, persistance, état courant et déploiement VeryGames mise à jour.
- Ajustement des dépendances Gradle pour éviter le conflit SLF4J avec MockBukkit.

## Fichiers créés
- `src/test/java/com/lodygames/rpgquest/database/MariaDbRepositoryIntegrationTest.java`
- `src/test/java/com/lodygames/rpgquest/database/MariaDbSchemaIntegrationTest.java`
- `src/test/java/com/lodygames/rpgquest/database/MariaDbTestSupport.java`
- `src/test/java/com/lodygames/rpgquest/database/MySqlDialectTranslationTest.java`

## Fichiers modifiés
41 fichiers au total dans le commit `19b1bbe`, notamment :
- `build.gradle.kts`
- `src/main/java/com/lodygames/rpgquest/database/*`
- plusieurs repositories persistants
- `src/main/resources/config.yml`
- `src/main/resources/plugin.yml`
- tests database/config
- `docs/ARCHITECTURE.md`
- `docs/PERSISTENCE.md`
- `docs/RPGQUEST_BIBLE.md`
- `docs/current_state.md`
- `docs/deployment/VERYGAMES.md`

## Base de données / migrations
- Validation sur une base MariaDB VeryGames réelle et initialement vide.
- Schéma RPGQuest créé automatiquement.
- Historique de migrations V1 à V17 validé.
- Les principaux repositories ont été exercés sur MariaDB, notamment progression, économie/wallet, clés générées et données binaires.
- La base SQLite de production n'a pas été migrée ni modifiée.

## Configuration / données
Les secrets MariaDB restent hors Git. Les identifiants réels VeryGames ne sont pas documentés dans le dépôt.

Le backend peut être sélectionné par configuration, SQLite restant supporté. Le pool HikariCP et les paramètres de connexion/timeouts sont configurables selon le format documenté.

## Tests automatiques
Validation finale après redimensionnement AWS :

- tests ciblés MockBukkit + database + config : `BUILD SUCCESSFUL` en 1 min 42 s ;
- suite complète : `./gradlew test` → `BUILD SUCCESSFUL` en 8 min 23 s ;
- build complet : `./gradlew build` → `BUILD SUCCESSFUL` en 4 s (tâches en grande partie à jour).

Validation MariaDB réelle effectuée auparavant :
- `MariaDbSchemaIntegrationTest` : 4/4 ;
- `MariaDbRepositoryIntegrationTest` : 8/8 ;
- `MySqlDialectTranslationTest` : 12/12 ;
- `DatabaseEngineTest` : 6/6.

## Tests manuels à effectuer
La validation de connexion et de création de schéma sur la base MariaDB VeryGames a déjà été effectuée. La bascule d'un serveur RPGQuest contenant les données SQLite existantes ne doit pas être réalisée dans ce ticket.

## Résultat attendu
RPGQuest dispose maintenant d'un backend MariaDB utilisable en parallèle du backend SQLite, avec pool de connexions, migrations versionnées et compatibilité des principaux domaines persistants.

## Reset / retour à l'état initial
Pour rester sur le comportement historique, conserver `database.type` sur SQLite. Aucun changement des données SQLite existantes n'est nécessaire pour revenir à ce mode.

## Déploiement VeryGames
### À transférer
Le JAR construit à partir de la branche contenant #41 ainsi que la configuration adaptée au backend choisi.

### Ne PAS transférer/altérer
- Ne pas versionner ou copier les secrets MariaDB dans Git.
- Ne pas supprimer ou remplacer la base SQLite de production dans le cadre de #41.

### Redémarrage requis
Oui, après changement de backend/configuration ou remplacement du JAR.

### Migration automatique
Le schéma MariaDB vide est créé/migré automatiquement jusqu'à la version attendue. Cela ne constitue pas une migration des données métier depuis SQLite.

## Rollback
Revenir au JAR précédent et conserver/reconfigurer le backend SQLite. Comme #41 ne migre ni ne supprime la base SQLite existante, celle-ci reste la source de rollback pour la production actuelle.

## Logs / diagnostic
Les erreurs de connexion/migration doivent rester synthétiques et ne jamais exposer de mot de passe ou de chaîne JDBC contenant des secrets.

## Documentation mise à jour
- architecture ;
- persistance ;
- bible RPGQuest ;
- état courant ;
- guide de déploiement VeryGames ;
- configuration du plugin.

## Limitations / travail restant
- La migration des données SQLite existantes vers MariaDB n'est pas incluse dans #41.
- La bascule définitive de production n'est pas incluse.
- Le test de perte/reconnexion réseau n'a pas été retenu comme prérequis bloquant pour cette livraison.
- La migration réelle des données et sa validation doivent être traitées dans l'issue dédiée suivante.

## Prochaine étape suggérée
Traiter l'issue #42 : concevoir et exécuter une migration contrôlée SQLite → MariaDB, avec sauvegarde, vérification des volumes/contraintes et plan de rollback avant toute bascule production.
