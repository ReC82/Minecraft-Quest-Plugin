# RPGQuest — Contexte technique durable

Ce fichier conserve les décisions techniques qu'une nouvelle session Claude doit connaître sans les redécouvrir.

## Projet

RPGQuest est un plugin RPG serveur pour Paper.

Objectifs principaux :

- moteur de quêtes YAML ;
- dialogues et embranchements ;
- journal de quêtes ;
- objets, armes, outils et ressources personnalisés ;
- crafting ;
- resource pack optionnel ;
- SQLite ;
- économie ;
- marché joueur ;
- zones, portails et claims ;
- progression RPG ;
- extensions futures web et mod client.

## Contraintes fondamentales

- Java 21.
- Paper API publique uniquement.
- Gradle Wrapper avec Kotlin DSL.
- Adventure Components / MiniMessage pour les textes.
- PersistentDataContainer pour l'identité des objets et entités custom.
- SQLite avec opérations asynchrones.
- Aucun accès disque ou SQL bloquant sur le thread principal.
- YAML validé au chargement.
- Pas de NMS.
- Pas de réflexion CraftBukkit.
- Pas de dépendance externe obligatoire lorsqu'une intégration optionnelle suffit.

## Package Java

Le package historique `be.lloyd.rpgquest` est obsolète.

La cible du projet est :

`com.lodygames.rpgquest`

Avant toute migration supplémentaire, vérifier l'état réel du dépôt. Ne jamais réintroduire l'ancien package depuis un ancien PDF.

## Git

Branches principales attendues :

- `main` : stable ;
- `develop` : intégration ;
- `feature/XX-description` : une étape par branche.

Ne jamais :

- `git reset --hard` sans ordre explicite ;
- force-push ;
- réécrire l'historique ;
- pousser vers le remote sans instruction explicite.

## Custom items

Un objet personnalisé doit être reconnu via PDC.

Ne jamais reconnaître un objet uniquement par :

- son nom ;
- son lore ;
- son Material.

Toutes les créations et validations doivent passer par le registre/service central prévu par le projet.

## Paper / événements

Toujours respecter :

- les événements déjà annulés ;
- la main utilisée ;
- les interactions concurrentes ;
- les déconnexions ;
- les reloads/config reloads ;
- les redémarrages ;
- les chunks déchargés.

Éviter :

- scans globaux par événement ;
- tâches répétitives coûteuses ;
- chargement forcé permanent de chunks.

## SQLite

Toujours privilégier :

- prepared statements ;
- transactions atomiques ;
- migrations idempotentes ;
- schéma versionné ;
- UUID comme identité joueur ;
- callbacks Bukkit remis sur le thread principal lorsqu'ils manipulent l'API Paper.

## Anti-duplication / concurrence

Pour crafting, économie, marché, backpacks, drops et récompenses :

- prévoir double clic ;
- shift-click ;
- retry ;
- deux joueurs simultanés ;
- crash ;
- redémarrage ;
- inventaire plein ;
- livraison répétée ;
- idempotence ;
- objets contrefaits.

## Documentation

La documentation doit décrire le code réel.

Ne jamais mettre ROADMAP.md à DONE uniquement parce qu'un prompt a été exécuté.
