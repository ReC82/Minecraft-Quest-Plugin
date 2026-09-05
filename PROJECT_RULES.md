# PROJECT_RULES

## Règles

-   Utiliser uniquement l'API publique Paper.
-   Aucun NMS.
-   Java 21.
-   Adventure/MiniMessage.
-   PersistentDataContainer pour tous les objets custom.
-   SQLite asynchrone.
-   Tests obligatoires à chaque étape.
-   Documentation mise à jour après chaque fonctionnalité.
-   Une branche Git par fonctionnalité.
-   Commits Conventional Commits.
-   Gradle Wrapper (Kotlin DSL) obligatoire, jamais de Gradle système dans les scripts CI.
-   Pas de Lombok.
-   Pas de dépendance obligatoire à Citizens, Vault, ItemsAdder ou Oraxen ;
    toute intégration externe doit être optionnelle et isolée.
-   Le plugin doit démarrer proprement si le resource pack est absent.
-   Aucun accès disque (SQLite) sur le thread principal.

## Déploiement VeryGames

Toute modification qui nécessite une action sur le serveur de production
VeryGames doit ajouter une entrée dans
[docs/deployment/SERVER_CHANGELOG.md](docs/deployment/SERVER_CHANGELOG.md),
**dans la même branche/PR** que le changement de code correspondant.
Concerne notamment :

-   nouveau plugin externe ;
-   nouvelle version de plugin externe ;
-   nouvelle version de Java ;
-   modification de configuration (`config.yml`, `messages.yml`,
    `plugins/Multiverse-Core/worlds.yml`, etc.) ;
-   migration de base de données ;
-   nouvelle donnée persistante ;
-   nouveau monde ;
-   modification de procédure de migration ;
-   resource pack ;
-   commande manuelle nécessaire en production.

Même si la seule action requise est de remplacer le JAR RPGQuest, cela doit
être indiqué explicitement dans l'entrée (ne jamais omettre l'entrée sous
prétexte qu'"il n'y a rien de spécial à faire"). Voir aussi
[docs/deployment/VERYGAMES.md](docs/deployment/VERYGAMES.md) pour les
procédures de déploiement complètes.

## Documentation de référence (`RPGQUEST_BIBLE.md`)

Toute nouvelle commande, modification de syntaxe, nouveau fichier de
configuration, nouvelle procédure serveur ou nouveau système administrable
doit mettre à jour [docs/RPGQUEST_BIBLE.md](docs/RPGQUEST_BIBLE.md) **dans
la même branche/PR** que le changement de code. Si le changement concerne
une page existante de `docs-site/`, cette page doit être mise à jour dans
la même branche/PR — la bible et le docs-site ne doivent pas diverger
volontairement.
