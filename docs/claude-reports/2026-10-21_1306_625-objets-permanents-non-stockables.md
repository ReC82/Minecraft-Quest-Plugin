# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-21
* Heure : 13:06
* Sujet : #625 — empêcher le stockage des objets RPGQuest permanents dans les coffres
* Statut : DONE (livré et déployé sur DEV ; validation en jeu PENDING MANUAL VALIDATION, TC-390)
* Branche Git : `fix/625-objets-permanents-coffres` (PR #696, fusionnée dans `chore/257-ci-github-actions`, merge `2864105d`) ; rapport : `docs/625-rapport`
* Commit actuel si disponible : `2864105d`
* Début de la tâche : 2026-10-21 12:35:21
* Fin de la tâche : 2026-10-21 13:07:30
* Durée totale : 00:32:09

## Demande
Ticket #625 (session autonome lqorch) : un joueur peut déposer son Journal des quêtes et sa Pierre de retour dans
un coffre. Décision propriétaire du 20/10 : interdire le stockage des **objets permanents liés au joueur** dans
**tous les mondes** et tous les transferts pertinents, par identité PDC ; Rune de rappel consommable et objets
ordinaires inchangés ; aucun scan / retrait / purge de coffres, aucune modification d'inventaire réel.

## Analyse
- `item.SoulboundItemService` est déjà le registre central des objets permanents (Acte, Pierre, Journal, Rune) avec
  un écouteur anti-perte (drop volontaire, mort). Aucune règle de stockage n'existait : seul `BackpackListener`
  filtrait les objets interdits à l'entrée d'un sac, avec son propre `switch` (sans la touche F).
- Audit des objets permanents : **Journal** et **Pierre** (cités par la décision) ; **Acte de propriété** —
  soulbound, jamais consommé (outil de visualisation après le premier claim), redonné par Jo → classé objet
  permanent lié au joueur, même politique ; **Rune de rappel** — consommable à cooldown, exclue explicitement par
  la décision → reste seulement soulbound.
- Le Journal est un `BOOK` : une bibliothèque sculptée l'accepte par clic droit ; les pots décorés et étagères
  (1.21.x) prennent aussi l'objet en main ; les bundles permettent un rangement indirect puis un stockage.

## Travail effectué
- `SoulboundItemService.registerStorageLocked(id)` (implique soulbound) + `isStorageLocked(stack)` +
  `storageListener()` ; bootstrap : Acte, Pierre, Journal en `registerStorageLocked`, Rune en `register`.
- Nouvel écouteur `item.StorageLockedItemListener` (priorité LOWEST, `ignoreCancelled`) — annule l'évènement, ne
  retire ni ne recrée jamais l'objet (pas de perte ni de duplication) :
  - clic vers l'inventaire du haut de **toute** vue (coffres, Ender, shulkers, tonneaux, wagonnets, animaux de bât,
    fours, établis, GUI du plugin dont coffres personnels et sacs) : clic gauche/droit, swap curseur, shift-click
    depuis le bas, touche numérique, touche F ; la grille 2×2 du joueur aussi ; le shift-click interne à
    l'inventaire du joueur reste libre ;
  - glisser-déposer touchant une case du haut ;
  - bundles (4 actions `*_INTO_BUNDLE`) ;
  - clic droit sur bibliothèque sculptée, pot décoré, étagères (`*_SHELF`) ; cadres, allays, porte-armures ;
  - `InventoryMoveItemEvent` (hopper/dropper) et `InventoryPickupItemEvent` (aspiration) ;
  - message « Cet objet doit rester dans ton inventaire. » au plus une fois toutes les 2 s par joueur (nettoyé à
    la déconnexion).
- Retirer un exemplaire déjà stocké reste permis (récupération manuelle) ; aucun scan ni purge.
- `item.InventoryTransfers.incomingToTop` : lecture partagée « quel objet ce clic dépose-t-il en haut », utilisée
  par `StorageLockedItemListener` **et** `BackpackListener` (remplace son `switch` privé ; ajoute la touche F).

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/item/StorageLockedItemListener.java`
- `src/main/java/com/lodygames/rpgquest/item/InventoryTransfers.java`
- `src/test/java/com/lodygames/rpgquest/item/StorageLockedItemListenerTest.java`
- ce rapport

## Fichiers modifiés
- `src/main/java/com/lodygames/rpgquest/item/SoulboundItemService.java`
- `src/main/java/com/lodygames/rpgquest/backpack/BackpackListener.java`
- `src/main/java/com/lodygames/rpgquest/bootstrap/RPGQuestBootstrap.java`
- `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md` (TC-390) — PR #696
- `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/deployment/SERVER_CHANGELOG.md`, `docs/claude-reports/README.md` — PR de rapport

## Base de données / migrations
Aucune.

## Configuration / données
Aucune clé de configuration, aucun fichier de contenu. Aucun coffre, inventaire, monde ni joueur DEV lu ou modifié.

## Tests automatiques
- Local (heap 768m) : `:test --tests StorageLockedItemListenerTest` vert ; `item.*`, `backpack.*`, `storage.*` :
  144 tests, 0 échec.
- `StorageLockedItemListenerTest` (12 tests) : dépôt au curseur refusé pour les 3 objets ; clic droit et swap ;
  shift-click ; touche numérique et touche F ; glisser vers le coffre refusé / dans l'inventaire permis ; Rune et
  diamant stockables sans message ; retrait d'un exemplaire déjà stocké permis ; shift-click interne permis ;
  bundle refusé ; hopper refusé / cobblestone déplacé ; anti-spam 2 s ; blocs à clic droit.
- CI GitHub verte sur la PR #696 et sur le commit de fusion `2864105d` (Tests plugin, control-panel, web-api,
  Scripts, Artefacts).

## Tests manuels à effectuer
TC-390 — **PENDING MANUAL VALIDATION** (aucun test en jeu par Claude). Résumé : sur son propre coffre de test,
tenter de déposer Journal et Pierre par tous les gestes (Wild, Hub, Claims, coffre personnel, shulker, Ender,
hopper, bundle, cadre, pot, étagère) → refus + objet conservé ; Rune et blocs ordinaires → permis.

## Résultat attendu
Les trois objets permanents restent toujours dans l'inventaire du joueur ; message bref ; aucun autre objet affecté.

## Reset / retour à l'état initial
Rollback JAR (ci-dessous). Aucune donnée créée.

## Déploiement VeryGames
- `scripts/deploy-verygames.sh -y` depuis `2864105d` (« Validation : CI verte sur 2864105 », assemblage seul).
- JAR **seul** : 4 045 101 octets, SHA-256 `db5fb794118d0b990dd69869519b9eb3d5fa4f2ecc9b4f8c1a4f8b992baf3656`,
  retéléchargé par FTP et identique. PlugAdmin non concerné (aucun fichier `control-panel/`).
- Sauvegarde : `rpgquest-20261021T110516Z-predeploy.jar` (SHA-256 `eda37e2b…`).
- DEV était **arrêté** (RCON : connexion refusée) : pas de redémarrage par Claude ; le JAR est chargé au prochain
  démarrage par le propriétaire. Pas de migration.
- À ne pas altérer : `data.db`, mondes, coffres, configuration.

## Rollback
`scripts/rollback-verygames.sh --latest` puis redémarrage DEV ; aucune donnée à restaurer.

## Logs / diagnostic
Journal du déploiement : `~/.local/state/lodyquests-orch/logs/deploy-625.log`.

## Documentation mise à jour
BIBLE (règle + récupération manuelle), current_state, MANUAL_TEST_PLAN (TC-390), SUIVI_VALIDATIONS_DEV (ligne 6f +
décision Acte), SERVER_CHANGELOG.

## Limitations / travail restant
- **Acte de propriété** inclus par audit (objet permanent soulbound) — à confirmer par le propriétaire ; retirer
  l'Acte = remplacer `registerStorageLocked` par `register` dans le bootstrap.
- Mode créatif : non testé spécifiquement (les créatifs peuvent de toute façon générer des objets).
- Fermeture d'inventaire avec l'objet au curseur et inventaire plein : comportement anti-perte existant
  (`SoulboundItemListener`), non modifié ici.
- Autres plugins de stockage qui écrivent directement dans un inventaire sans évènement Bukkit : non couverts
  (aucune API pour les intercepter).

## Prochaine étape suggérée
Le propriétaire démarre DEV et exécute TC-390 ; ticket #625 laissé ouvert jusqu'à sa validation.
