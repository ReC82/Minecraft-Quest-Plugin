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
