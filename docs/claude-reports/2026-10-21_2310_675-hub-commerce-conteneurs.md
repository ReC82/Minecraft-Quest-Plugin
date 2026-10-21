# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-21
* Heure : 23:10
* Sujet : #675 — Hub : bloquer le commerce vanilla et l'accès aux ressources des conteneurs
* Statut : DONE (livré et déployé sur DEV ; TC-397 PENDING MANUAL VALIDATION)
* Branche Git : `feature/675-hub-commerce-conteneurs` (PR #713, fusionnée dans `chore/257-ci-github-actions`), rapport `docs/675-rapport`
* Commit actuel si disponible : merge `1549c345` (code `dfd30134`, `1b9ba9e0`, docs `docs(hub)…`)
* Début de la tâche : 2026-10-21 22:20:42
* Fin de la tâche : 2026-10-21 23:09:01
* Durée totale : 00:48:19

## Demande

Ticket #675 (rédigé par le propriétaire) : dans le Hub, empêcher le commerce avec les villageois vanilla (et les
marchands ambulants) et empêcher de prendre ou déposer des objets dans les conteneurs vanilla (doubles coffres,
tonneaux…), y compris via entonnoirs. Règle de monde, pas de liste d'entités/coordonnées. Verrouiller, ne rien vider,
ne toucher aucun villageois ni bâtiment ; Wild et Claims inchangés ; PNJ/marchands RPGQuest et coffres personnels
(#105) conservés ; exemption administrateur possible ; persistance après redémarrage.

## Analyse

Audit des protections Hub existantes :

- `hub.HubWorldProtectionListener` : dégâts, casse/pose, seaux, feu, explosions, spawns (dont `WANDERING_TRADER` /
  `TRADER_LLAMA`, déjà annulés et nettoyés — #121/#155). **Aucune** règle sur le commerce ni sur les conteneurs : le
  clic droit est volontairement libre (#107, établis utilisables).
- `zone.ZoneProtectionListener` : drapeau `public-containers` limité à une zone cuboïde, pas au monde entier.
- `storage.VaultAccessListener` (#105) : à `LOWEST`, annule l'interaction sur un coffre lié et ouvre un inventaire du
  plugin (`PersonalVaultHolder`) — l'ouverture vanilla du bloc n'a donc jamais lieu, et un verrou sur
  `InventoryOpenEvent` des blocs ne le concerne pas.
- `item.StorageLockedItemListener` (#625) : déjà une détection des blocs qui prennent un objet au clic (pot décoré,
  bibliothèque sculptée, étagères) — à partager plutôt que recopier.
- Les marchands/ateliers RPGQuest s'ouvrent depuis des PNJ Citizens et des inventaires du plugin, sans villageois
  vanilla derrière.

Décision de périmètre (mineure, réversible) : verrouiller les conteneurs **de stockage** ; laisser libres les blocs
**utilitaires** (fours, fumoirs, hauts fourneaux, alambics, établis) des salles de préparation (#107) et le coffre de
l'Ender (stockage personnel, pas une ressource du Hub). Notée dans le registre pour arbitrage éventuel.

## Travail effectué

- Nouveau `hub.HubResourceLockListener` (priorité `LOW`, `ignoreCancelled`), scopé par `hub.world` :
  - `PlayerInteractEntityEvent` sur un `AbstractVillager` non Citizens → annulé (deux mains), message
    « Le commerce n'est pas disponible dans le Hub. » limité à un toutes les 2 s ;
  - `InventoryOpenEvent` : interface marchande d'un villageois non Citizens refusée (filet), conteneur de stockage
    refusé (`Chest`, `DoubleChest`, `Barrel`, `ShulkerBox`, `Hopper`, `Dispenser`, `Dropper`, `Crafter`,
    `StorageMinecart`, `HopperMinecart`, `ChestBoat`) avec « Les coffres du Hub sont fermés. » ;
  - `PlayerInteractEvent` sur pot décoré / bibliothèque sculptée / étagère → annulé ;
  - `InventoryMoveItemEvent` dont la source ou la destination est dans le Hub, `InventoryPickupItemEvent` d'un hopper
    du Hub → annulés.
  - Bypass : `rpgquest.admin.hub.resources` (défaut `op`) via `AdminBypassMode.allowsExact` — seulement pendant une
    session `/rpgadmin bypass on` (#35).
- Refactor séparé : `InventoryTransfers.takesItemsOnClick(Material)` remplace `StorageLockedItemListener.acceptsItems`.
- Enregistrement dans `RPGQuestBootstrap` à côté de `HubWorldProtectionListener`, prédicat Citizens
  `npcIdentityService::isCitizensNpc`.
- Rien n'est jamais retiré, vidé, déplacé ni modifié : uniquement des annulations d'évènements, sans état → persiste
  après redémarrage.

## Fichiers créés

- `src/main/java/com/lodygames/rpgquest/hub/HubResourceLockListener.java`
- `src/test/java/com/lodygames/rpgquest/hub/HubResourceLockListenerTest.java`
- `docs/claude-reports/2026-10-21_2310_675-hub-commerce-conteneurs.md`

## Fichiers modifiés

- `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java`
- `src/main/java/com/lodygames/rpgquest/item/InventoryTransfers.java`, `item/StorageLockedItemListener.java`
- `src/main/resources/plugin.yml` (nœud `rpgquest.admin.hub.resources`)
- `src/test/java/com/lodygames/rpgquest/item/StorageLockedItemListenerTest.java`,
  `permission/RpgPermissionsMatrixTest.java`
- Docs : `docs/RPGQUEST_BIBLE.md`, `docs/PERMISSIONS.md`, `docs/HUB_SERVICES.md`, `docs/current_state.md`,
  `docs/MANUAL_TEST_PLAN.md` (TC-397), `docs-site/hub-safe-zone.html`, `docs/deployment/SERVER_CHANGELOG.md`,
  `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/claude-reports/README.md`

## Base de données / migrations

Aucune.

## Configuration / données

Aucune clé de configuration. Aucun fichier de contenu. Aucun coffre, villageois ni bloc touché sur DEV ; aucun droit
`rpgquest.admin.hub.resources` attribué à qui que ce soit (défaut `op`, inerte hors session bypass).

## Tests automatiques

- `./gradlew :test --tests 'com.lodygames.rpgquest.hub.*' --tests '…storage.*' --tests '…item.*' --tests
  '…permission.*'` (`RPGQUEST_TEST_MAX_HEAP=768m`) : **237 tests, 0 échec**, 2 ignorés préexistants.
- `HubResourceLockListenerTest` 9/9 : commerce refusé (villageois, marchand ambulant, deux mains, un seul message) ;
  PNJ Citizens et Wild non concernés ; bypass seulement avec session ; coffre/tonneau refusés sans perte de contenu ;
  liste des blocs verrouillés vs utilitaires ; inventaire du plugin libre ; pots/étagères ; transferts de hopper.
- Limites MockBukkit rencontrées : tous les inventaires de blocs y ont la même position fictive (la portée par monde
  des transferts est donc vérifiée en changeant le monde Hub configuré), `Crafter` n'a pas d'état mocké (non testé
  unitairement). Le monde de test s'appelle `hub_675` pour ne pas croiser l'écouteur réel enregistré par le plugin.
- CI GitHub : PR #713 verte (plugin, control-panel, web-api, scripts, artefacts), CI du commit de fusion `1549c345` verte.

## Tests manuels à effectuer

TC-397 — **PENDING MANUAL VALIDATION** (aucun test en jeu effectué par Claude).

## Résultat attendu

Au Hub, un joueur normal ne peut ni commercer avec un villageois, ni ouvrir un coffre/tonneau/hopper vanilla ; coffres
personnels, établis, fours, PNJ, boutiques et ateliers RPGQuest fonctionnent ; Wild et Claims inchangés.

## Reset / retour à l'état initial

Rien à réinitialiser : aucune donnée écrite. Retour arrière = JAR précédent (ci-dessous).

## Déploiement VeryGames

- `scripts/deploy-verygames.sh -y` (CI verte reconnue sur `1549c34`, assemblage seul) : JAR en ligne 4182486 octets,
  SHA-256 `c4f200862d08ed317f889760eb8eb40694bc4ffe2f9050a79324c08b5adf14a2`, **retéléchargé par FTP et identique**.
- Sauvegarde : `rpgquest-20261021T210601Z-predeploy.jar` (4178087 o, SHA-256 `79fb4faa…` = JAR #568).
- `scripts/verygames-restart.sh --timeout 300` (0 joueur) : DEV de nouveau ONLINE ; `plugins` → RPGQuest actif.
- PlugAdmin non redéployé (aucun changement `control-panel/`), toujours `f35c9538`.
- À ne pas altérer : `data.db`, `config.yml`, coffres et villageois du Hub. Pas de migration. Redémarrage requis (fait).

## Rollback

Remettre `rpgquest-20261021T210601Z-predeploy.jar` comme JAR du plugin et redémarrer. Aucune donnée à restaurer.

## Logs / diagnostic

Journaux : `~/deploy-logs/675-deploy.log`, `~/deploy-logs/675-restart.log` (machine de build).

## Documentation mise à jour

Bible (monde Hub + mode bypass), PERMISSIONS, HUB_SERVICES, current_state, MANUAL_TEST_PLAN (TC-397), docs-site
`hub-safe-zone.html`, SERVER_CHANGELOG, registre `SUIVI_VALIDATIONS_DEV.md` (ligne 0i + dépendance #675).

## Limitations / travail restant

- Fours, fumoirs, hauts fourneaux, alambics et coffre de l'Ender restent ouvrables dans le Hub (choix #107) : à
  fermer aussi si le propriétaire le décide.
- Un constructeur du Hub n'ouvre plus les coffres de décor sans session bypass + nœud dédié.
- Lutrins (livre retirable) et animaux de bât non couverts (absents d'un village vanilla du Hub à notre connaissance).
- Le Hub est le seul monde `hub.world` configuré : la règle suivra automatiquement si le modèle multi-Hub évolue vers
  plusieurs mondes, à condition que `isHub` le reflète.

## Prochaine étape suggérée

Le propriétaire déroule TC-397 (étapes 1, 2, 4–7 d'abord) et consigne le résultat dans le registre.
