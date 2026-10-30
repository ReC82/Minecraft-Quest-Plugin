# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-30
* Heure : 14:32
* Sujet : #939 — Hub : sécuriser l'éjection redstone et les véhicules à conteneur (suite de #675)
* Statut : DONE (livraison technique ; validation en jeu PENDING MANUAL VALIDATION TC-449, non déployé)
* Branche Git : `feature/939-hub-ejection-vehicules` (PR #943), fusionnée dans `chore/257-ci-github-actions` (merge `6d0753fd`)
* Commit actuel si disponible : `d77f31bb` (code + doc), merge `6d0753fd`
* Début de la tâche : 2026-10-30 14:32:46
* Fin de la tâche : 2026-10-30 15:14:00
* Durée totale : 00:41:14

## Demande
Ticket #939 (session lqorch autonome) : L1 — `BlockDispenseEvent` au Hub sans désactiver les distributeurs
décoratifs ; L2 — `VehicleDamageEvent`/`VehicleDestroyEvent` pour les wagonnets et bateaux à coffre au Hub
seulement. Étendre `hub/HubResourceLockListener`, tests Hub/Wild/Claims avec et sans bypass, PR + CI.
Pas de déploiement DEV sans OK écrit du propriétaire ; aucune manipulation d'entité, d'inventaire ou de monde réel.

## Analyse
- L1 confirmé par lecture du code : aucun gestionnaire `BlockDispenseEvent` ; seuls `InventoryMoveItemEvent`
  (dropper → conteneur) et `InventoryPickupItemEvent` étaient bloqués. Un dropper ou distributeur alimenté
  par la redstone éjectait donc son contenu dans le monde. Même trou pour un **crafter** (`CrafterCraftEvent`,
  résultat éjecté) : ajouté au même lot, c'est la même fuite.
- L2 confirmé : aucun gestionnaire de véhicules sous `hub/` ; `HubWorldProtectionListener.onEntityDamage`
  ne vise que les entités vivantes et les cadres. Casser un wagonnet/bateau à coffre fait tomber le véhicule
  et son contenu (comportement vanilla).
- Distributeurs décoratifs : liste fermée d'objets sans objet ramassable produit — feu d'artifice, seau d'eau,
  seau vide (fontaines à bascule), boule de neige, boule de feu. Exclus volontairement : flèches (ramassables),
  armures (équipées sur un joueur), cisailles (laine), os (pousse), seau de lave, wagonnets/bateaux (créent un
  véhicule cassable), objets ordinaires. Un dropper n'a pas d'usage décoratif : toujours bloqué.
- La redstone n'a pas d'auteur : aucun bypass possible pour L1. Pour L2, bypass seulement si le coup vient
  d'un admin en session `/rpgadmin bypass on` avec `rpgquest.admin.hub.resources` ; une destruction sans auteur
  (explosion, environnement) reste refusée.

## Travail effectué
- `HubResourceLockListener` : `onDispense`, `onCrafterCraft`, `onVehicleDamage`, `onVehicleDestroy`,
  constante `DECORATIVE_DISPENSES`, `isDecorativeDispense`, `isProtectedVehicle` (réutilise `isLockedStorage`
  pour reconnaître `StorageMinecart`/`HopperMinecart`/`ChestBoat`). `refuse` tolère un joueur absent.
- `HubWorldProtectionListener.playerBehind` rendu package-private (réutilisé : joueur ou tireur du projectile).
- 6 tests ajoutés dans `HubResourceLockListenerTest`.
- PR #943 ouverte puis fusionnée.

**Écart de procédure à signaler** : la PR #943 a été fusionnée **avant** la fin de la CI (commande `merge`
enchaînée par erreur après un contrôle de `mergeable`). Constat a posteriori : run `push` du commit
`d77f31bb` vert ; run `pull_request` du même commit rouge sur un seul test sans lien,
`WaypointServiceTest.firstDiscoveryShowsTheCanonicalNameNotOnlyTheBiome` (course asynchrone : message attendu
après `performTicks(5)`), passé dans l'autre run ; CI du commit de fusion `6d0753fd` **verte** (tous jobs).
Aucun déploiement, branche `main` non touchée.

## Fichiers créés
- `docs/claude-reports/2026-10-30_1432_939-hub-ejection-vehicules.md`

## Fichiers modifiés
- `src/main/java/com/lodygames/rpgquest/hub/HubResourceLockListener.java`
- `src/main/java/com/lodygames/rpgquest/hub/HubWorldProtectionListener.java` (visibilité d'un utilitaire)
- `src/test/java/com/lodygames/rpgquest/hub/HubResourceLockListenerTest.java`
- `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs-site/hub-safe-zone.html`,
  `docs/MANUAL_TEST_PLAN.md` (TC-449), `docs/deployment/SERVER_CHANGELOG.md` (NON DÉPLOYÉ)
- `docs/SUIVI_VALIDATIONS_DEV.md` (ligne #939), `docs/claude-reports/README.md` (index)

## Base de données / migrations
Aucune.

## Configuration / données
Aucune clé de configuration nouvelle ; liste décorative en constante de code. Aucune donnée joueur, entité,
bloc ou inventaire réel manipulé.

## Tests automatiques
- Local (`RPGQUEST_TEST_MAX_HEAP=768m`) : `HubResourceLockListenerTest` 17/17 (dont 6 nouveaux : droppers et
  crafters refusés au Hub ; distributeurs décoratifs conservés et 7 objets ressources refusés ; Wild/Claims
  libres ; 3 véhicules à conteneur protégés des coups/destructions avec et sans auteur, contenu conservé, un seul
  message ; véhicules simples non concernés ; bypass en session seulement, jamais sans auteur),
  `HubWorldProtectionListenerTest` 46/46.
- CI GitHub : voir l'écart ci-dessus — merge `6d0753fd` vert (Tests plugin, control-panel, web-api, Scripts, Artefacts).

## Tests manuels à effectuer
TC-449 — PENDING MANUAL VALIDATION (seulement si le Hub contient un tel montage ou si le propriétaire en pose un
en bypass). Aucun test Minecraft n'a été effectué.

## Résultat attendu
Au Hub : dropper/crafter actionné → rien n'est éjecté ; distributeur → feu d'artifice/eau OK, flèche ou objet
ordinaire reste dedans ; wagonnet/bateau à coffre incassable, message discret. Wild/Claims inchangés.

## Reset / retour à l'état initial
Revert du merge `6d0753fd` (aucun état persistant créé).

## Déploiement VeryGames
### À transférer
JAR RPGQuest seulement, **sur OK écrit du propriétaire** ; un JAR construit depuis la tête embarque aussi
#817 rév. 2 et #747 (déploiement suspendu, choix A/B en attente).
### Ne PAS transférer/altérer
`config.yml`, `data.db`, mondes, contenus.
### Redémarrage requis
Oui (remplacement du JAR).
### Migration automatique
Aucune.

## Rollback
Remettre le JAR précédent (sauvegarde datée du script officiel).

## Logs / diagnostic
Échec CI isolé : job « Tests plugin » du run 41066279936 — `WaypointServiceTest.java:593`.

## Documentation mise à jour
Bible (zone Hub), `current_state.md`, `docs-site/hub-safe-zone.html`, TC-449, SERVER_CHANGELOG, registre DEV.

## Limitations / travail restant
- Fontaine avec une pile de seaux vides : un seau d'eau rempli sans place dans le distributeur serait éjecté
  (cas vanilla marginal, joueurs ne pouvant rien y mettre au Hub).
- `/kill` ou commandes admin non concernés.
- `WaypointServiceTest` instable : candidat à un ticket de fiabilisation (famille #506).

## Prochaine étape suggérée
OK de déploiement du propriétaire (lot groupé avec #817/#747), puis TC-449 si un montage existe au Hub.
