# Index de la documentation

Point d'entrée vers les documents de `docs/`. Pour une vue d'ensemble complète du projet (systèmes,
commandes, décisions techniques), voir en premier lieu **`RPGQUEST_BIBLE.md`** — les autres
documents ci-dessous détaillent chacun un sous-système spécifique.

| Document | Contenu |
|---|---|
| [RPGQUEST_BIBLE.md](RPGQUEST_BIBLE.md) | Référence complète du projet : tous les systèmes, toutes les commandes, décisions techniques. |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Décisions d'ingénierie détaillées par sous-système (pourquoi, pas seulement quoi). |
| [current_state.md](current_state.md) | Snapshot de ce qui est actuellement implémenté — mis à jour à chaque étape livrée. |
| [storylines.md](storylines.md) | Moteur de Storyline : conteneur logique de quêtes, progression par joueur, commandes admin. |
| [NPC_DIALOGUES_QUESTS_GUIDE.md](NPC_DIALOGUES_QUESTS_GUIDE.md) | Guide PNJ, dialogues et quêtes (format YAML, exemples). |
| [HUB_GUIDE.md](HUB_GUIDE.md) | Guide « centre d'aide » d'un Hub, journal du Libraire, structure multi-Hub (`hub-guides/`). |
| [SAFE_ZONE.md](SAFE_ZONE.md) | Zones protégées (village central / safe zone). |
| [TRAVEL.md](TRAVEL.md) | Portails (`/rpgadmin portal`), destinations et portails simples (`/rpgadmin worldportal`). |
| [CLAIMS.md](CLAIMS.md) | Claims de terrain joueurs. |
| [ECONOMY.md](ECONOMY.md) | Économie, portefeuille, marché, marchands. |
| [PROGRESSION.md](PROGRESSION.md) | Compétences, XP, niveaux. |
| [BACKPACKS.md](BACKPACKS.md) | Backpacks et avantages (entitlements). |
| [STORE.md](STORE.md) | Boutique web-api et livraisons en jeu. |
| [WEB_API.md](WEB_API.md) | API web (endpoints, authentification, snapshot). |
| [CLIENT_MOD.md](CLIENT_MOD.md) | Compatibilité avec le mod client. |
| [PERMISSIONS.md](PERMISSIONS.md) | Permissions granulaires par rôle / monde / action (build par Hub, `/rpgadmin` par action, séparation claims). |
| [ADMIN_FLATTEN.md](ADMIN_FLATTEN.md) | Aplatissement de terrain admin (`/rpgadmin flatten`). |
| [ADMIN_PLAYER_RESET.md](ADMIN_PLAYER_RESET.md) | Reset admin « nouveau joueur » (`/rpgadmin player resetnew <joueur> confirm`). |
| [LOCAL_SERVER.md](LOCAL_SERVER.md) | Lancer un serveur local de développement. |
| [MANUAL_TEST_PLAN.md](MANUAL_TEST_PLAN.md) | Plan de test manuel complet, étape par étape. |
| [deployment/VERYGAMES.md](deployment/VERYGAMES.md) | Procédure de déploiement sur l'hébergement VeryGames. |
| [deployment/SERVER_CHANGELOG.md](deployment/SERVER_CHANGELOG.md) | Historique des déploiements effectués sur le serveur de production. |

Ce fichier est un index, pas une source de vérité : en cas de divergence avec le contenu d'un
document listé ci-dessus, ce dernier fait foi.
