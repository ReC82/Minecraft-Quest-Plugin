# RPGQuest Control Panel — vision & index

> Socle architectural de l'issue **#37**. Cette première étape pose une **architecture propre,
> sécurisée et extensible** ; elle n'implémente pas tous les modules. Statut : **architecture
> documentée, code à venir** (issue/branche `feat/37-control-panel`).

## Ce que c'est

Un **outil d'administration séparé du jeu**, pour le propriétaire/développeur du serveur.
**Pas** une fonctionnalité de gameplay, **pas** un site public (ça, c'est déjà `web-api/`, voir
[docs/WEB_API.md](../WEB_API.md)).

À terme, point d'entrée unique pour : état réel du serveur, joueurs & progression, PNJ (et ceux
qui manquent), quêtes/stories/dépendances, actions admin sûres (dont les commandes de test #36),
préparation d'états de test, comparaison contenu dépôt/serveur, suivi des validations manuelles,
et — plus tard — pilotage Claude/GitHub (#29).

## Principes non négociables

1. **SQLite n'est pas l'API du Control Panel.** Le panel ne lit/écrit jamais `data.db` en direct
   comme mécanisme d'intégration. Le **plugin reste la source de vérité métier**.
2. **Le web ne réimplémente pas les règles métier.** Il appelle des opérations explicites du
   plugin (bridge), qui délèguent aux services existants (`QuestProgressEngine`, `StoryService`,
   `PlayerResetService`, `ClaimService`, `NpcIdentityService`…).
3. **Aucun shell arbitraire** exposé depuis le navigateur. Actions **déclaratives et
   whitelistées** uniquement.
4. **Multi-environnement dès le design.** Une cible = `{env, bridgeUrl, token}` — jamais de
   `localhost` / `world_hub` / `claims` codés en dur.
5. **Aucun couplage FTP.** VeryGames/FTP est un détail de déploiement, pas une dépendance du
   cœur.
6. **Sécurité de base dès la V1** : auth obligatoire, secrets hors Git, session sûre, CSRF,
   validation stricte, audit log, HTTPS documenté pour la prod, kill-switch d'accès.

## Documents

| Fichier | Contenu |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | modules, frontières, flux, stack, structure de code, démarrage local, déploiement AWS |
| [SECURITY.md](SECURITY.md) | auth, sessions, CSRF, secrets, rate limiting, audit log, HTTPS, kill-switch, modèle de menace |
| [CONFIGURATION.md](CONFIGURATION.md) | configuration hors code, par environnement, secrets, clés attendues |
| [RPGQUEST_BRIDGE.md](RPGQUEST_BRIDGE.md) | contrat de communication Control Panel ↔ plugin (endpoints, versionnage, erreurs, actions whitelistées) |
| [ROADMAP.md](ROADMAP.md) | découpage modulaire, ordre d'implémentation, ce qui est V1 / plus tard |
| [DECISIONS.md](DECISIONS.md) | ADR — décisions d'architecture datées et justifiées |

## Relation avec les autres issues

- **#36** (commandes admin de test) : deviennent des *actions du bridge*. Logique **dans le
  plugin**, jamais dupliquée côté web. Voir [docs/ADMIN_TEST_SHORTCUTS.md](../ADMIN_TEST_SHORTCUTS.md).
- **#29** (pilotage Claude/mobile) : futur **module « Développement »** du Control Panel, **même
  socle** auth/backend — pas une 2e application web.
- **`web-api/`** (portail public + boutique) : reste séparé. Le Control Panel peut partager du
  code *infrastructure* (HTTP, JSON, pipeline) via un module commun, jamais la posture de
  sécurité (le portail est anonyme, le panel est authentifié et a des pouvoirs admin).
