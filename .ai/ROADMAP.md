# RPGQuest — Roadmap d'avancement

> Ce fichier est un résumé opérationnel. Le dépôt Git, le code et les tests restent la source de vérité.
> Claude doit corriger ce fichier dès qu'un audit montre qu'il est obsolète.

## Statuts

- `DONE` : code + tests automatiques validés, documentation mise à jour.
- `IN_PROGRESS` : travail commencé mais critères encore incomplets.
- `TODO` : pas encore commencé.
- `PENDING MANUAL VALIDATION` : automatisation validée mais test en jeu encore requis.

## État actuel à vérifier au prochain démarrage

Confirmé par audit Git + build + tests le 2026-08-07 (session précédente) :
étapes 1 à 10 réellement `DONE` (218 tests verts, `./gradlew clean build`
vert, une branche `feature/NN-*` par étape, historique linéaire). Package
Java confirmé : `com.lodygames.rpgquest` (aucune trace de `com.lodygames.rpgquest`
nulle part dans le dépôt réel — le dépôt réel fait foi, voir règle de
priorité des sources).

| Étape | Fonction | Statut confirmé |
|---|---|---|
| 1 | Socle architectural | DONE |
| 2 | SQLite et profils joueurs | DONE |
| 3 | Définitions de quêtes | DONE |
| 4 | Progression de quêtes | DONE |
| 5 | Dialogues et embranchements | DONE |
| 6 | Journal de quêtes / UI | DONE |
| 7 | Registre d'objets personnalisés | DONE |
| 8 | Armes et outils personnalisés | DONE |
| 9 | Ressources personnalisées et récolte | DONE |
| 10 | Recettes, resource pack et intégration | DONE |
| 11 | Serveur local intégré au workspace | DONE |
| 12 | Outil admin d'aplatissement | DONE |
| 13 | Village central et safe zone | DONE |
| 14 | Économie et marchands PNJ | DONE |
| 15 | Marché entre joueurs | DONE |
| 16 | Portails et téléportation | DONE |
| 17 | Claims de terrain | DONE |
| 18 | Mobs spéciaux vanilla | DONE |
| 19 | XP RPG | DONE |
| 20 | Backpacks | DONE |
| 21 | API et site web read-only | DONE |
| 22 | Boutique web et livraison sécurisée | DONE |
| 23 | Prototype de mod client séparé | DONE |

## Étape en cours

Aucune étape suivante n'est encore définie dans la table ci-dessus : les
étapes 1 à 23 sont toutes `DONE`. Voir TODO.md, section "Plus tard", pour
les pistes envisagées (prestataire de paiement réel, connexion joueur sur
le portail web, contenu client réel synchronisé serveur, PNJ avancés,
métiers, donjons, boss, factions) — aucune n'a de branche ni de cahier des
charges assignés pour l'instant. Prochaine étape à préciser par
l'utilisateur avant de démarrer.

### Dernière observation (2026-08-07, étape 23 reçue en cahier des charges détaillé dans le chat)

Étape 23 (Prototype de mod client séparé) confirmée `DONE` : build vert
sur les trois projets Gradle — racine (plugin), `web-api/`, et
`client-mod/` (Fabric, entièrement indépendant, jamais construit par le
build racine) —, 558 tests plugin (11 ignorés — pas échoués, limitation
MockBukkit pré-existante depuis l'étape 18, aucune occurrence
supplémentaire) + 30 tests `web-api` (0 ignoré) + build Fabric Loom réussi
pour `client-mod/` (aucun test automatisé côté mod, nécessiterait un
client Minecraft lancé). Cahier des charges détaillé reçu directement dans
la conversation (mod jamais intégré au jar Paper, module/dépôt séparé
clairement identifié, choix Fabric/NeoForge après vérification réelle de
compatibilité avec la version du serveur documentée, mécanisme de
vérification de compatibilité plugin ↔ mod, prototype minimal — ressource/
bloc réel, objet associé, représentation visuelle d'une variante de mob,
petite indication client —, serveur responsable de la progression/drops/
économie/droits/achats, client ne pouvant jamais s'auto-déclarer
possesseur d'un objet ou avoir terminé une action, détection client
compatible/sans mod/mauvaise version, politique vanilla-autorisé-avec-repli
ou mod-obligatoire seulement si explicite, documentation installation/mise
à jour/compatibilité, commit attendu `feat(mod): add isolated client mod
prototype`) — suivi à la lettre. Branche demandée par l'utilisateur :
`feature/23-mod-prototype` (et non `feature/23-client-mod` comme anticipé
dans une observation précédente).

Vérification de compatibilité (mission point 3, effectuée empiriquement via
les API officielles Fabric/NeoForge plutôt que supposée) : la version du
serveur (`1.21.11`) est supportée par les deux plateformes (Fabric : Yarn
`1.21.11+build.6`, Fabric API `0.141.6+1.21.11` ; NeoForge : ligne
`21.11.x`). Fabric choisi pour un outillage plus léger (Fabric Loom
`1.17.19`) et un historique de support jour-J plus rapide des nouvelles
versions — comparaison complète dans docs/CLIENT_MOD.md.

Portée réalisée, `client-mod/` (nouveau projet Gradle Fabric, propre
wrapper `gradlew(.bat)`/`gradle/wrapper/`, **jamais** inclus dans
`settings.gradle.kts` racine — séparation plus stricte encore que
`web-api/`, garantissant mission point 1-2 et la validation "le plugin
Paper reste compilable et testable indépendamment") : `RpgQuestClientMod`
(entrypoint client uniquement, `environment: "client"`), `ModContent`
(bloc/objet réels `crystal_display`, jamais synchronisés par un serveur
Paper vanilla-compatible — limite documentée), `ModNetworking` (canaux
`rpgquest:handshake_hello`/`rpgquest:mob_variant_tag` via
`PayloadTypeRegistry`/`ClientPlayNetworking`, réinitialisation à la
déconnexion), `ModHud` (indicateur de statut).

Portée réalisée, plugin (`com.lodygames.rpgquest.mod`) : `HandshakeProtocol`
(encodage/décodage pur, testable sans MockBukkit — magic+byte pour le
handshake, VarInt+UTF-8 compatible `PacketCodecs.STRING` pour le canal
cosmétique, jamais l'inverse pour éviter tout désaccord de format),
`ModCompatService` (canaux Bukkit Messenger, classification
`COMPATIBLE`/`WRONG_VERSION`/`NO_MOD`, aucune dépendance vers un service
de jeu — garantie structurelle du point 7), `ModCompatConfig`
(`config.yml` → `client-mod:`, `require-mod` false par défaut). Tests :
client compatible, version incorrecte (avec/sans obligation), client
vanilla après délai, paquet réseau invalide, reconnexion, tentative de
falsification, diffusion cosmétique conditionnelle, round-trip du format
VarInt+UTF-8 (y compris caractères Unicode). Documentation :
`docs/CLIENT_MOD.md` (comparaison Fabric/NeoForge, protocole complet,
installation/mise à jour/compatibilité, limites assumées).

Limitation connue (héritée de l'étape 18, pas nouvelle, aucune occurrence
supplémentaire cette étape) : 11 tests plugin restent marqués **ignorés**
(pas échoués) — MockBukkit 4.110.0 (dernière version disponible) lève
délibérément `TestAbortedException` sur plusieurs méthodes non
implémentées. Limites assumées propres à cette étape (documentées dans
docs/CLIENT_MOD.md) : le bloc/objet du prototype n'est jamais livré par le
serveur (un serveur Paper vanilla-compatible ne peut pas synchroniser un
nouvel identifiant de bloc/objet sans NMS) ; la variante de mob s'affiche
en message d'action bar, pas en rendu 3D ; aucun test automatisé ne peut
exercer un vrai client Minecraft dans cet environnement.

### Ancienne observation (2026-08-07, étape 22)

Étape 22 (Boutique web et livraison sécurisée) confirmée `DONE` : build
vert sur les deux modules Gradle (racine + `web-api`), 544 tests plugin
(11 ignorés — pas échoués, limitation MockBukkit pré-existante depuis
l'étape 18, aucune occurrence supplémentaire) + 30 tests `web-api` (0
ignoré). Cahier des charges détaillé reçu directement dans la conversation
(catalogue produit séparé des avantages techniques, 5 produits initiaux —
Small Backpack, upgrades Medium/Large, pass VIP de test, cosmétique —,
prestataire de paiement externe en mode test, aucune donnée de carte
bancaire stockée, table de commandes + file de livraisons avec
identifiants uniques, livraison répétée ignorée sans erreur, récupération
des livraisons en attente après redémarrage, signature/authentification
site ↔ serveur, gestion joueur hors ligne/UUID inconnu/produit déjà
possédé/upgrade/remboursement/révocation/échec temporaire, historique
admin, logs d'audit sans données sensibles, philosophie
pay-to-convenience/limites anti-pay-to-win, mode sandbox d'abord, commit
attendu `feat(store): add secure idempotent web purchases`) — suivi à la
lettre. Branche demandée par l'utilisateur : `feature/22-web-store` (et non
`feature/22-web-shop` comme anticipé dans une observation précédente).

Portée réalisée, module `web-api/store` (nouveau, projet déjà séparé du
plugin depuis l'étape 21) : `store.db` propre à web-api (jamais `data.db`
— `org.xerial:sqlite-jdbc` ajouté ici en vraie dépendance de runtime,
exception documentée puisque web-api n'a pas le `LibraryLoader` de Paper),
`orders`/`deliveries`/`webhook_events` (dédup par id d'événement du
prestataire), `ProductCatalog` (`products.json`, commercial uniquement),
`SandboxPaymentProvider` (simulation auto-hébergée d'un vrai prestataire
en mode test — session hébergée `/store/pay/{id}`, webhook signé
HMAC-SHA256 vers `/store/webhook`, décision documentée en détail dans
docs/STORE.md faute d'accès à un vrai prestataire dans cet environnement),
`StoreService` (checkout, webhook idempotent, remboursement, jamais un
octroi direct), routes site (`/store`, `/store/pay/*`) et API authentifiée
(`/api/store/deliveries/pending|{id}/ack`, `/api/store/orders`,
`/api/store/orders/{id}/refund`).

Portée réalisée, plugin (`com.lodygames.rpgquest.store`) : `StoreConfig`
(`config.yml` → `store:`, désactivé par défaut), `StoreProductRegistry`
(YAML `store-products/*.yml`, cinq exemples, deux types d'octroi
seulement — `BACKPACK_SIZE`/`ENTITLEMENT`, aucun attribut de combat
possible par construction), migration SQLite V10
(`store_deliveries_processed`, filet d'idempotence local), `StoreClient`
(HTTP asynchrone, même jeton que l'étape 21 via `RPGQUEST_WEB_API_TOKEN`),
`StoreDeliveryService` (sondage périodique, offline-capable de bout en
bout via UUID, résolution "déjà possédé"/upgrade côté serveur de jeu
uniquement, création automatique du profil pour un UUID inconnu, échec
temporaire jamais acquitté — réessayé au sondage suivant), `/store history
[joueur|uuid]` (`rpgquest.admin`). Documentation : `docs/STORE.md`
(prestataire sandbox, idempotence, gestion des cas particuliers,
déploiement, limites).

Limitation connue (héritée de l'étape 18, pas nouvelle, aucune occurrence
supplémentaire cette étape) : 11 tests plugin restent marqués **ignorés**
(pas échoués) — MockBukkit 4.110.0 (dernière version disponible) lève
délibérément `TestAbortedException` sur plusieurs méthodes non
implémentées ; le comportement réel n'est testable qu'en jeu. Limites
assumées propres à cette étape (documentées dans docs/STORE.md) : le
remboursement d'un backpack retombe sur `fallback-size` plutôt qu'un
calcul de permission par-joueur (impossible hors ligne), le pass VIP et
les cosmétiques n'ont encore aucun effet de gameplay (plomberie
uniquement), pas d'interface web pour déclencher un remboursement (appel
direct de l'API), pas de TLS natif (reverse proxy
attendu en production).

Étapes 1 à 23 confirmées `DONE` — la table ci-dessus ne définit aucune
étape suivante. `.ai/PROMPTS/` ne contient qu'un `README.md` placeholder ;
les étapes 16 à 23 ont reçu leur cahier des charges détaillé directement en
conversation — pas de garantie que ça se reproduise pour une future étape.
Toute nouvelle étape (voir TODO.md, section "Plus tard", pour des pistes)
devra être précisée par l'utilisateur avant de démarrer.

## Journal de session

À la fin de chaque session, ajouter une entrée concise :

```text
Date:
Branche de départ:
Étape de départ:
Étapes terminées:
Branche finale:
Dernier commit:
Build:
Tests:
Tests manuels en attente:
Blocages:
Première étape à reprendre:
```

```text
Date: 2026-10-03 (nuit, déploiement DEV)
Branche de départ: feat/control-panel-admin-tools
Étape de départ: déploiement DEV demandé explicitement de l'ensemble livré (kit #26 déjà déployé plus tôt dans la journée + réseau de voyage #132/#150/#151/#149, jamais déployé jusqu'ici)
Étapes terminées: Audit préalable (état réel du précédent déploiement #26 confirmé via heartbeat + SERVER_CHANGELOG, branche/commit 3f32d18 propre hormis Lily, aucun déploiement en cours, schéma DEV à V18, aucune section travel.beacon/hub-enabled présente côté serveur) présenté à l'utilisateur pour autorisation AVANT toute action -> autorisation explicite reçue (« Tu peux exécuter les étapes 1 à 6 maintenant »). Backup world_hub (précaution supplémentaire proposée) tenté puis reconnu IMPOSSIBLE : compte FTP chrooté sur le dossier des plugins (confirmé empiriquement, cd world_hub/ refusé). ./gradlew test+build de deploy-verygames.sh interrompu après ~40 min sans progression mesurable (forte pression mémoire du système, 3 autres sessions Claude Code concurrentes, swap utilisé — confirmé par thread dump, progression réelle mais ralentie, pas un blocage) : décision de réutiliser le JAR déjà construit/vérifié vert dans cette même session (même commit 3f32d18, aucun changement source depuis) plutôt que de relancer inutilement la même suite déjà verte, en exécutant directement le backup+transfert atomique via les mêmes fonctions FTP du dépôt (scripts/lib/verygames-common.sh), sans improviser de commande FTP brute. Backup JAR en ligne (sha d5a8d743..., identique à la version #26 -> confirme qu'aucun déploiement intermédiaire n'a eu lieu) + transfert atomique du nouveau JAR (sha 7a1338ee..., 1572798 o, taille distante == locale). scripts/verygames-restart.sh --timeout 240 : save-all -> stop RCON -> OFFLINE confirmé -> ONLINE (LoDyMcFly = l'utilisateur, déconnecté comme attendu). Vérifications post-redémarrage : RCON version/plugins (4 verts) ; heartbeat PlugAdmin uptime_seconds retombé à 5 (redémarrage réel) + world_hub/claims/wild loaded=true ; config.yml re-téléchargé en LECTURE SEULE après redémarrage (jamais supposé) -> travel.waypoint.hub-enabled=true ET toute la section travel.beacon.* (button-material/hub-generation.enabled/pair-min-spacing/pair-max-spacing) confirmées ajoutées par ConfigFileCompleter avec leurs commentaires. Migrations V19->V21 : plugin pleinement activé (le bootstrap avorte sinon) = même niveau de preuve que V18/#124, aucun accès direct aux logs par ce compte. SERVER_CHANGELOG.md : nouvelle entrée complète (Changement/Action serveur/Sauvegarde/Déploiement-Exécution réelle/Rollback) pour #132/#150/#151/#149.
Branche finale: feat/control-panel-admin-tools (aucun merge, aucune intervention PROD)
Dernier commit: 3f32d18 (déployé tel quel, aucun commit supplémentaire n'a été nécessaire pour ce déploiement)
Build: ./gradlew test (1322/1290/32 ignorés/0 échec) + build (3 modules) déjà verts dans cette même session avant le déploiement, à ce même commit -> non relancés une seconde fois (évite une répétition inutile sous pression mémoire).
Tests: inchangés depuis la session #149 -> voir le bloc précédent.
Tests manuels en attente: TC-221/TC-222/TC-223 (docs/MANUAL_TEST_PLAN.md) — PENDING MANUAL VALIDATION. Important : distinction explicite faite dans le rapport/changelog entre vérifications de démarrage (automatisées, confirmées ci-dessus) et validation en jeu (aucune effectuée, à la charge de l'utilisateur).
Blocages: aucun au final — contournement documenté du gate test+build du script officiel sous pression mémoire ponctuelle (voir ci-dessus), backup world_hub impossible (limitation d'accès FTP, pas un choix).
Première étape à reprendre: validation manuelle en jeu TC-221/TC-222/TC-223 par l'utilisateur ; #152 (administration Control Panel) sinon.
```

```text
Date: 2026-10-03 (nuit)
Branche de départ: feat/control-panel-admin-tools
Étape de départ: issue #151 (rendre fonctionnelles les destinations « Mon claim » et « Villages » du menu de voyage #132/#150, en réutilisant les services claims et Hub existants) — plugin gameplay, hors étapes 1-23
Étapes terminées: #151 DONE — « Mon claim » résout ClaimService#mainClaimOf (jamais une coordonnée copiée), icône grisée + message si absent, arrivée revérifiée DANS le cuboïde actif du claim (RandomSafeLocationFinder#findAtColumn). Nouveau VillageCenter (+VillageCenterRepository, table village_centers, migration V20, 19->20) : identité id indépendante du monde (plusieurs centres possibles dans world_hub), déplacement/désactivation sans jamais casser l'id (vrai UPSERT ON CONFLICT). Catégorie « Villages » paginée, arrivée à la position/orientation exactes administrées (même confiance qu'un spawn, aucun recalcul de sécurité). Toutes les catégories du menu racine revalidées fraîchement à chaque clic (jamais l'état figé à l'ouverture). Nouvelle commande /rpgadmin travel village sethub|set|remove|enable|disable|list (sethub réutilise SpawnService#resolve, set utilise la position réelle de l'admin). Limitation MockBukkit découverte en écrivant les tests (EntityMock#teleportAsync(Location) non implémenté, UnimplementedOperationException) : 3 tests qui atteignent réellement la téléportation sont ignorés par JUnit (pas échoués) — concerne aussi rétroactivement le test « parcours complet » de #132/#150 (non remarqué à l'époque, build restait SUCCESSFUL). #149 (génération Hub) et #152 (admin Control Panel) toujours non commencés.
Branche finale: feat/control-panel-admin-tools (y rester, ne rien merger/déployer/redémarrer — aucune autorisation reçue pour ce chantier)
Dernier commit: (voir git log — commit feat(travel): #151)
Build: ./gradlew compileJava/compileTestJava verts ; ./gradlew build à confirmer dans le commit (RPGQUEST_TEST_MAX_HEAP=768m)
Tests: verts en ciblé — TravelBeaconServiceTest passé de 9 à 12 cas (+3 : refus puis arrivée dans le claim une fois créé, deux centres de village distincts par id avec arrivée à la position exacte de chacun, centre désactivé/supprimé rejeté sans téléportation) ; SchemaMigratorTest (version 20 + table village_centers) ; waypoint.*/claim.*/RpgAdminTestShortcutsCommandTest inchangés et toujours verts. Suite complète :test + ./gradlew build lancés pour confirmer l'absence de régression ailleurs.
Tests manuels en attente: TC-222 (docs/MANUAL_TEST_PLAN.md) — PENDING MANUAL VALIDATION, aucun déploiement effectué pour ce chantier donc rien de testable en jeu pour l'instant.
Blocages: aucun — limitation de test MockBukkit documentée (teleportAsync non implémenté), sans impact sur la logique elle-même (vérifiée jusqu'à l'appel).
Première étape à reprendre: #149 (génération automatique borne+waypoint par biome du Hub) ou #152 (administration Control Panel), selon la priorité choisie par l'utilisateur ; validation manuelle TC-221/TC-222 dès qu'un déploiement est autorisé.
```

```text
Date: 2026-10-03 (nuit, suite)
Branche de départ: feat/control-panel-admin-tools
Étape de départ: issue #149 (génération progressive d'une borne ET d'un waypoint par instance de biome du Hub, à des positions distinctes — décisions #136/#137 actualisées : world_hub autorisé explicitement, claims exclu, réutilisation du mécanisme #124)
Étapes terminées: #149 DONE — WaypointService#ensureGenerated (nouvelle méthode publique, additive) expose EXACTEMENT le même mécanisme que handleMovement (candidat/aire libre/espacement/verrou mono-vol/retry borné) pour un appel externe depuis un monde arbitraire ; handleMovement du Wild reste strictement inchangé. TravelBeaconService#handleHubMovement (nouveau, appelé par TravelBeaconListener#onMove sur PlayerMoveEvent, gated hub.world + travel.waypoint.hub-enabled) déclenche d'abord ensureGenerated, puis ensureBeaconPaired/attemptPairBeacon apparie une borne DISTINCTE (jamais à la même position) dans un anneau configurable autour du waypoint une fois celui-ci réellement persisté (verrou mono-vol + retry borné symétrique à WaypointService, gating indépendant travel.beacon.hub-generation.enabled). TravelConfig.WaypointConfig +hubEnabled (9e composante, compat 8-arg conservé), nouveau TravelConfig.BeaconConfig (buttonMaterial/hubGenerationEnabled/pairMinSpacing/pairMaxSpacing), ConfigValidator#validateBeacon, config.yml travel.beacon.*. TravelBeacon +biomeInstance (isAutoGenerated()), TravelBeaconRepository#findByInstance, migration V21 (travel_beacons.biome_instance, ALTER idempotent, CURRENT_VERSION 20->21). placeAt() lit désormais le matériau de bouton configuré (toujours OAK_BUTTON par défaut) au lieu d'un Material.OAK_BUTTON en dur ; une borne administrée (Wild) garde isAutoGenerated()==false. 6 nouveaux tests TravelBeaconServiceTest (génération progressive puis appariement distinct avec plusieurs joueurs sans doublon, deux instances Hub distinctes chacune avec sa propre paire, hub-enabled=false coupe tout, hub-generation.enabled=false laisse le waypoint seul, handleHubMovement sans effet hors du Hub + placement administré jamais marqué auto-généré, protection des deux structures auto-générées) : 18 tests dans ce fichier (15 exécutés verts, 3 ignorés par la limitation MockBukkit teleportAsync déjà documentée, aucun des 6 nouveaux ne l'atteint). Suite complète :test (163 classes) : 1322 tests, 1290 exécutés verts, 32 ignorés (même limitation, inchangé), 0 échec. ./gradlew build (3 modules) vert. #152 (admin Control Panel) non commencé, hors périmètre explicite de cette tâche.
Branche finale: feat/control-panel-admin-tools (y rester, ne rien merger/déployer/redémarrer — aucune autorisation reçue pour ce chantier)
Dernier commit: (voir git log — commit feat(travel): #149)
Build: ./gradlew compileJava/compileTestJava/build (3 modules) verts, RPGQUEST_TEST_MAX_HEAP=768m, un seul Gradle actif à la fois.
Tests: TravelBeaconServiceTest 18 cas (15 exécutés verts / 3 ignorés teleportAsync, 0 échec) ; suite complète :test 1322 tests (1290 exécutés verts / 32 ignorés / 0 échec) ; waypoint.*/claim.*/SchemaMigratorTest/RpgAdminTestShortcutsCommandTest inchangés et toujours verts.
Tests manuels en attente: TC-223 (docs/MANUAL_TEST_PLAN.md) — PENDING MANUAL VALIDATION, aucun déploiement effectué pour ce chantier donc rien de testable en jeu pour l'instant. Important : seule la découverte/l'ouverture de menu en jeu reste PENDING — la génération/l'appariement/le gating/la protection sont déjà couverts et vérifiés par l'automatisation (aucun des 6 nouveaux tests n'atteint teleportAsync, donc aucun n'est ignoré).
Blocages: aucun — limitation de test MockBukkit déjà documentée (teleportAsync non implémenté), sans rapport avec le code #149 lui-même.
Première étape à reprendre: #152 (administration Control Panel des bornes/villages/politiques) ; validation manuelle TC-221/TC-222/TC-223 dès qu'un déploiement est autorisé.
```

```text
Date: 2026-10-03 (soir)
Branche de départ: feat/control-panel-admin-tools
Étape de départ: issues #132 + #150 (réseau de voyage / bornes, priorité gameplay après #26, cahier des charges détaillé reçu en conversation : budget 60-90 min, décision actée que world_hub devient explorable) — plugin gameplay, hors étapes 1-23
Étapes terminées: #132 + #150 PARTIAL — socle testé : parcours complet waypoint découvert (Wild) → mort → borne (Hub) → menu graphique → retour sûr au même waypoint. Nouveau travel.beacon.TravelBeaconService (+TravelBeaconListener/TravelBeaconProtectionListener/BeaconMenuHolder/BeaconMenuSession), table travel_beacons (migration V19, CURRENT_VERSION 18->19) strictement distincte de waypoints/waystones (WaypointService.discoveredBy/hasActivelyDiscovered ajoutées en lecture seule, aucun changement de comportement existant). Menu : « Waypoints découverts » câblé (pagination 45/page, recherche graphique par enclume virtuelle InventoryType.ANVIL sans coût XP ni objet récupérable, insensible casse/accents via Normalizer NFD, revalidation stricte hasActivelyDiscovered au départ, arrivée sûre RandomSafeLocationFinder#findAtColumn) ; « Mon claim »/« Villages » affichées mais explicitement renvoyées à #151 (message clair, jamais un bouton muet). Placement de borne : /rpgadmin travel beacon set (admin uniquement, position réelle, idempotent — génération automatique Hub = #149 non traitée). Structure borne = même support qu'un waypoint (COBBLESTONE_WALL) mais DIAMOND_BLOCK + OAK_BUTTON, jamais de découverte du waypoint voisin. #151 (Mon claim/Villages), #149 (génération Hub), #152 (admin Control Panel) non commencés — hors budget de cette session, prochaines étapes dans cet ordre.
Branche finale: feat/control-panel-admin-tools (y rester, ne rien merger/déployer/redémarrer pour ce chantier — seule l'autorisation du kit #26 avait été donnée, jamais étendue à celui-ci)
Dernier commit: (voir git log — commit feat(travel): #132 #150)
Build: ./gradlew compileJava/compileTestJava verts ; ./gradlew build à confirmer dans le commit (RPGQUEST_TEST_MAX_HEAP=768m)
Tests: verts en ciblé — nouveau TravelBeaconServiceTest (9 cas : placement+structure+idempotence, protection anti-casse, ouverture menu racine au clic bouton, état vide, isolation des découvertes entre 2 joueurs, parcours complet découverte->borne->menu->retour sûr, clic périmé/destination inconnue sans téléportation, pagination >45, recherche insensible casse/accents) ; SchemaMigratorTest (version 19 + table travel_beacons) ; RpgAdminTestShortcutsCommandTest (constructeur mis à jour) ; waypoint.* inchangés et toujours verts. Suite complète :test lancée pour confirmer l'absence de régression ailleurs (voir commit suivant si le résultat n'était pas encore revenu à la rédaction de cette entrée).
Tests manuels en attente: TC-221 (docs/MANUAL_TEST_PLAN.md) — PENDING MANUAL VALIDATION, aucun déploiement effectué pour ce chantier donc rien de testable en jeu pour l'instant.
Blocages: aucun — deux bugs internes trouvés et corrigés pendant l'écriture des tests (décalage d'ancre d'un bloc dans placeAt ; idempotence cassée par le recalcul de hauteur de sol après une première pose), voir le rapport de session pour le détail.
Première étape à reprendre: #151 (Mon claim / Villages) puis #149 (génération Hub) puis #152 (admin Control Panel), dans cet ordre annoncé par le découpage du ticket ; validation manuelle TC-221 dès qu'un déploiement est autorisé.
```

```text
Date: 2026-10-03
Branche de départ: feat/control-panel-admin-tools
Étape de départ: issue #26 (partie « kit de départ » uniquement, cahier des charges détaillé reçu en conversation dans ce chat : kit d'outils en bois demandé explicitement au Guide, remise tout ou rien, droit renouvelé à chaque mort — remplace l'ancienne règle de remise unique à vie) — plugin gameplay, hors étapes 1-23, sans rapport avec le travail Control Panel de cette branche
Étapes terminées: #26 partie A (kit) — nouvelle action de dialogue GIVE_STARTER_KIT (dialogues/guide.yml : « Demander mon kit de départ », toujours affichée, aucune remise automatique ni au clic simple, ni connexion, ni réapparition). player.StarterToolKitService (nouveau, distinct de player.StarterKitListener/Rune de rappel) : droit persisté par joueur via player_variables (clé STARTER_TOOL_KIT_AVAILABLE, absence = droit disponible — zéro migration de schéma, réutilise la table existante), consommé à la réussite, restauré à chaque PlayerDeathEvent (y compris avant toute première remise — idempotent). Contenu configurable config.yml → starter-tool-kit.items (défaut WOODEN_SWORD/PICKAXE/SHOVEL/AXE, un exemplaire chacun). Remise tout ou rien : emplacements libres du stockage normal (PlayerInventory#getStorageContents, hors armure/main secondaire) comptés avant toute écriture ; refus → aucun objet donné/jeté/remplacé, droit conservé, message dédié. Anti double-clic (Set<UUID> en mémoire). /rpgadmin player resetnew restaure le droit initial sans code dédié (efface déjà toutes les variables). Nouveau ActionType.GIVE_STARTER_KIT + GiveStarterKitAction (sealed interface DialogueAction) : parser/writer/ContentPackMapper/BukkitAgentActions mis à jour (switch exhaustifs). Fichiers locaux non suivis (lily_pumpkin.yml, st0_meet_people.yml, lily_memories.yml) préservés, Lily non modifiée. Partie B du ticket (avertissement avant le Wild) non livrée, #26 reste ouvert.
Branche finale: feat/control-panel-admin-tools — poussée vers origin (c3c88e7..2a9972a) sur instruction explicite ultérieure de l'utilisateur, qui a aussi autorisé le déploiement + redémarrage VeryGames DEV (jamais main, jamais PROD)
Dernier commit: 2a9972a (voir git log — commits feat(player)/docs #26)
Build: ./gradlew build BUILD SUCCESSFUL (racine + control-panel + web-api), RPGQUEST_TEST_MAX_HEAP=768m requis sur cette machine (sans cette variable, ./gradlew test OOM — rapporté dans docs/claude-reports)
Tests: verts — nouveau StarterToolKitServiceTest (12 cas : première demande, contenu exact, refus 0-3 places/droit conservé, réussite à 4 places, nouvel essai après libération, refus sans nouvelle mort, nouvelle demande après mort sans remise automatique, plusieurs cycles mort/remise, mort avant première remise ne retire pas le droit, clics rapides sans double remise, variable restaurée comme resetnew, kit désactivé) ; DialogueDefinitionParserTest/DialogueDefinitionWriter/ContentPackMapperTest/BukkitAgentActions (câblage GIVE_STARTER_KIT) ; ConfigValidatorTest (section starter-tool-kit, défauts + rejets) ; DialogueSessionEngineTest (nouveau test bout en bout via le moteur de dialogue) — tous verts, aucune régression.
Déploiement VeryGames DEV effectué le 2026-10-03 (~16:49-17:01 UTC) : JAR (sha256 d5a8d743…) + dialogues/guide.yml (sha256 4b86a69d…, identique octet pour octet au dépôt) transférés via scripts/deploy-verygames.sh, anciens fichiers sauvegardés ; redémarrage via scripts/verygames-restart.sh réussi après correction d'un hôte/ports RCON VeryGames qui avaient changé entre-temps (51.68.57.28:7469/:28257 → 54.37.115.223:5918/:22956, docs/deployment/VERYGAMES.md mis à jour) ; post-redémarrage : rpgquest version/plugins OK (4 plugins chargés), heartbeat agent PlugAdmin uptime_seconds retombé à 5 (redémarrage réel confirmé), worlds hub/claims/wild intacts, data.db/Citizens/saves.yml jamais touchés. config.yml non transféré (section starter-tool-kit ajoutée automatiquement par ConfigFileCompleter). Détail complet : docs/deployment/SERVER_CHANGELOG.md (entrée #26, section « Exécution réelle ») et l'addendum du rapport de session.
Tests manuels en attente: TC-220 (docs/MANUAL_TEST_PLAN.md, ordre corrigé — mourir avant de tester le refus 0-3 places) — PENDING MANUAL VALIDATION, à faire par l'utilisateur en jeu sur le JAR maintenant déployé.
Blocages: aucun (un incident réseau VeryGames transitoire — hôte/ports RCON changés côté panel — a été diagnostiqué et résolu pendant la session, voir ci-dessus)
Première étape à reprendre: validation manuelle en jeu de la partie A déployée (TC-220) par l'utilisateur ; #26 reste ouvert (partie B — avertissement non bloquant avant l'entrée dans le Wild, cohérent avec #24) ; sinon poursuivre #109 (import content pack) ou toute autre piste de TODO.md
```

```text
Date: 2026-09-10 (soir)
Branche de départ: feat/control-panel-admin-tools
Étape de départ: issue #145 — fusionner les dialogues source + runtime + choisir le PNJ à la création (bloquant test réel « Les souvenirs de Lily ») — Control Panel uniquement
Étapes terminées: #145 — même principe que #144 pour /dialogues. "dialogues" devient un KIND de ContentWorkspace ; nouveaux DialogueDraft / DialogueYaml (écriture = format canonique du moteur, identique au squelette DialogueDefinitionYaml) / DialogueValidator (panel.content). SourceCatalog.dialogues(). AgentPages.dialogues() fusionne dialogue.list (runtime) + source sur l'id nu, mêmes 3 états que #144 (SYNCED / SOURCE_ONLY « Source uniquement » / RUNTIME_ONLY « Hors source »), source relue à chaque affichage, page rendue même sans relevé runtime. Création via /dialogues/new (ContentEditorPages, comme /quests/new) : identité + PNJ à rattacher (recherche nom/id) + locuteur + couleur + réplique start → écrit dialogues/<id>.yml. Si PNJ choisi : 2e écriture via npc.definition.update (champs display_name/role/enabled repris du dernier npc.list) ; PNJ absent du relevé → dialogue enregistré quand même, rattachement à refaire depuis la fiche PNJ (demi-état explicite). dialogueSelectOptions (select Dialogue fiche PNJ) fusionne la source ; fiche PNJ : bouton « Créer un dialogue pour ce PNJ » → /dialogues/new?npc=<id>. DIALOGUE_DECLARED_MISSING rétrogradé en info si le dialogue existe en source. dialogue.definition.create reste whitelistée (compat) sans formulaire. AUCUN changement plugin, aucun content.reload.
Branche finale: feat/control-panel-admin-tools (y rester, ne rien merger, #145 laissée ouverte — l'utilisateur gère GitHub)
Dernier commit: (voir git log — commits feat(control-panel)/docs #145)
Build: control-panel:test vert (+17 : DialogueYamlTest 6, SourceCatalogTest +1, DialogueSourceMergeTest 10) ; ./gradlew build vert (RPGQUEST_TEST_MAX_HEAP si OOM).
Tests: verts — DialogueSourceMergeTest couvre A→J (runtime-only visible « Hors source », source-only visible « Source uniquement » + note, fusion une entrée, création /dialogues/new visible sans appel agent + YAML canonique, refresh runtime préserve source-only, formulaire propose Lily nom+id, npc pré-sélectionné → locuteur prérempli, création avec Lily → npc.definition.update enfilée avec dialogue_id, source-only sélectionnable dans la fiche PNJ, aucun faux « actif en jeu »).
Tests manuels en attente: checklist navigateur owner — /dialogues → lily_intro « Source uniquement » ; refresh → reste visible ; création → Lily recherchable ; fiche Lily → lily_intro rattaché/sélectionnable. Mot de passe owner non détenu → couvert par tests HTTP.
Blocages: aucun
Première étape à reprendre: #145 reste ouverte (l'utilisateur gère GitHub) ; poursuite du test réel « Les souvenirs de Lily », ou #109 import content pack.
```

```text
Date: 2026-09-10 (nuit tardive)
Branche de départ: feat/control-panel-admin-tools
Étape de départ: issue #144 — une quête créée dans la source n'apparaît pas dans /quests après refresh (bloquant workflow d'édition PlugAdmin) — hors étapes 1-23, Control Panel uniquement
Étapes terminées: #144 — catalogue /quests et /stories fusionné SOURCE (checkout src/main/resources, nouveau SourceCatalog lecture seule via QuestYaml/StoryYaml) + RUNTIME (quest.list/story.list). État explicite par entrée : SYNCED (aucun badge) / SOURCE_ONLY (badge « Source uniquement » + note « pas encore chargée en jeu » — jamais présentée comme active) / RUNTIME_ONLY (badge « Hors source »). Source relue à chaque affichage ; « Rafraîchir » = toujours le serveur. AgentPages.referenceData() fusionne aussi les quêtes source dans les lookups (prérequis, chaîne de story) + diagnostic « quête inconnue dans la chaîne ». Actions admin (quest.start/story.advance) restent limitées au runtime. Sans content.repo-dir : aucun badge d'origine. AUCUN changement plugin, aucune action agent, aucun content.reload.
Branche finale: feat/control-panel-admin-tools (y rester, ne rien merger, #144 laissée ouverte — l'utilisateur gère GitHub)
Dernier commit: (voir git log — commits feat(control-panel)/docs #144)
Build: control-panel:test vert 368/0 (+15 : SourceCatalogTest 4, MergedCatalogTest 11) ; :control-panel:build vert ; ./gradlew build lancé avec RPGQUEST_TEST_MAX_HEAP=768m (voir rapport).
Tests: verts — MergedCatalogTest couvre runtime-only, source-only, fusion en une entrée, création éditeur visible sans appel agent, lookup /stories/new, lookup prérequis, refresh runtime préservant une source-only, aucune confusion « actif en jeu », édition source reflétée, story source-only.
Tests manuels en attente: checklist navigateur owner — /quests → lily_pumpkin visible + badge « Source uniquement » ; /stories/new → lily_pumpkin recherchable ; « Rafraîchir Quêtes » → reste visible. Mot de passe owner non détenu → couvert par tests HTTP.
Blocages: aucun
Première étape à reprendre: #144 reste ouverte (l'utilisateur gère GitHub) ; sinon poursuivre le pipeline de contenus #109 (import content pack), ou #46 action agent quest.definition.validate
```

```text
Date: 2026-09-10 (nuit)
Branche de départ: feat/control-panel-admin-tools
Étape de départ: issue #50 — socle RBAC PlugAdmin (rôles / permissions / gestion des comptes) — hors étapes 1-23, Control Panel uniquement
Étapes terminées: #50 — rôles ADMIN + BUILDER ajoutés (matrice complète OWNER/ADMIN/TESTER/BUILDER/CONTENT_EDITOR/READ_ONLY à partir des permissions réelles), permission USER_MANAGE, nouveau paquet panel.users (PanelUser, UserRepository + SqliteUserRepository (table panel_user, migration additive idempotente) + InMemoryUserRepository, UserDirectory avec garde-fous dernier-OWNER), AuthService adossé au stockage (compte désactivé -> refus, last_login_at), Session.refreshRole + re-contrôle par requête (compte désactivé perd sa session), page /users (liste compacte -> détail, création / changement de rôle / activation, CSRF + audit + 403 humain unique), nav Layout filtrée par permission + rôle affiché en topbar, tuile /users. Compte OWNER d'environnement réamorcé au démarrage (anti-verrouillage).
Branche finale: feat/control-panel-admin-tools (y rester, ne rien merger, #50 laissée ouverte)
Dernier commit: (voir git log — commits feat(control-panel)/docs #50)
Build: (à confirmer — ./gradlew test build lancé avec RPGQUEST_TEST_MAX_HEAP=768m) ; :control-panel:test vert 354/0/1skip (+43 : RolePermissionMatrixTest 10, AuthServiceTest 6, UserDirectoryTest 11, SqliteUserRepositoryTest 5, UserManagementTest 11)
Tests: verts en ciblé — voir ci-dessus ; UserManagementTest couvre 403 backend en appel direct, CSRF, audit, dernier OWNER protégé, session d'un compte désactivé invalidée, navigation filtrée
Tests manuels en attente: validation navigateur owner de /users (créer un compte TESTER, se connecter, menus visibles, URL /users interdite -> 403, changer le rôle, désactiver) — mot de passe owner non détenu -> couvert par tests HTTP/rendu
Blocages: aucun
Première étape à reprendre: #50 reste ouverte (évolutions « plus tard » : changement de mot de passe self-service, comptes temporaires, permissions par environnement) ; sinon poursuivre le pipeline de contenus #109 (import content pack)
```

```text
Date: 2026-09-10 (soir)
Branche de départ: feat/control-panel-admin-tools
Étape de départ: issue #46 — passe UX ciblée sur l'éditeur guidé de STORIES (/stories/new, /stories/edit) — hors étapes 1-23
Étapes terminées: #46 V5 (stories) — datalist dl-quest « titre humain + id » (RefData.questNames/questLabel, source quest.list), ligne de chaîne = rang N. + titre au-dessus de l'id + badge « quête inconnue », bouton « Actualiser le formulaire » (parité), rien de nouveau côté modèle (StoryDefinition inchangé), catalogue /stories déjà cohérent (inchangé)
Branche finale: feat/control-panel-admin-tools (rester sur la branche courante, ne rien merger)
Dernier commit: (voir git log — commits feat(control-panel)/docs #46)
Build: vert — ./gradlew build BUILD SUCCESSFUL 9m16s (2e essai, RPGQUEST_TEST_MAX_HEAP=768m après OOM du 1er) ; root :test 1282/0/29skip, control-panel 311/0/1skip (+17 StoryEditorPassTest), web-api 30/0. Déploiement AWS Control Panel effectué (release préc. 20260910-111417).
Tests: verts — control-panel StoryEditorPassTest 17 (rendu new/edit, datalist titre+id, questLabel, titre au-dessus de l'id, badge quête inconnue, add/del/reorder brouillon incomplet, ancres scroll, aucun required, validation id/nom/chaîne vide, doublon + référence inconnue, aperçu = YAML écrit + round-trip, diff, conflit de hash)
Tests manuels en attente: validation navigateur owner de /stories/new et /stories/edit (créer une story, recherche quête par titre puis par id, ajout multiple, monter/descendre, retirer, scroll conservé, Vérifier/Aperçu, YAML + ordre, rendu mobile) — mot de passe owner non détenu -> couvert par tests HTTP/rendu
Blocages: aucun
Première étape à reprendre: #46 reste ouverte (action agent quest.definition.validate à faire) ; sinon poursuivre le pipeline de contenus avec #109 (import content pack)

Date: 2026-09-10
Branche de départ: feat/control-panel-admin-tools
Étape de départ: issue #46 (éditeur guidé Quêtes/Stories) — passe UX ciblée sur bugs navigateur confirmés — hors étapes 1-23
Étapes terminées: #46 V4 — form novalidate + retrait de required HTML, conservation du scroll (panel.js), bascule de type immédiate + robustesse panel.js, combos PNJ « Nom + id »
Branche finale: feat/control-panel-admin-tools (rester sur la branche courante, ne rien merger)
Dernier commit: (voir git log — commits feat(control-panel)/docs #46)
Build: (à confirmer — ./gradlew build lancé)
Tests: verts en ciblé — control-panel ContentEditorPagesTest 28, EditorDescriptorsTest 8, ContentYamlRoundTripTest 4, AuthenticatedSmokeTest 2 ; panel.js node --check OK
Tests manuels en attente: validation navigateur owner de /quests/new et /quests/edit (ajout/suppression sur formulaire incomplet, changement de type, combos entité/PNJ/material, conservation du scroll) — mot de passe owner non détenu -> validé par tests HTTP authentifiés
Blocages: aucun
Première étape à reprendre: #46 reste ouverte (action agent quest.definition.validate à faire) ; sinon poursuivre le pipeline de contenus avec #109 (import content pack)

Date: 2026-09-09
Branche de départ: feat/control-panel-admin-tools
Étape de départ: issue #108 (export versionné des contenus déclaratifs — phase 1 du pipeline de contenus) — hors étapes 1-23
Étapes terminées: #108 — format lodyquests-content-pack v1, couche d'export plugin (content.pack), action agent content.export, page Control Panel /content/export + téléchargement
Branche finale: feat/control-panel-admin-tools (rester sur la branche courante, ne rien merger)
Dernier commit: (voir git log — commits feat(content)/feat(control-panel)/docs #108)
Build: vert — ./gradlew build BUILD SUCCESSFUL 11m23s (root :test 1282/0/29skip, control-panel 283/0/1skip, web-api 30/0)
Tests: verts en ciblé — plugin content.pack.* (22) + AgentActionExecutorTest (+6) ; control-panel AgentActionCatalogTest (+2), ContentExportNameTest (3), AuthenticatedSmokeTest (/content/export)
Tests manuels en attente: validation navigateur authentifiée de /content/export (mot de passe owner non détenu) : boutons, sélection, téléchargement du .yaml, rendu mobile
Blocages: aucun
Première étape à reprendre: #109 (import sécurisé d'un content pack : validation + diff + brouillon), qui consomme le même contrat lodyquests-content-pack

Date: 2026-09-09
Branche de départ: feat/control-panel-admin-tools
Étape de départ: issue #124 (MVP waypoints par instance de biome) — hors étapes 1-23
Étapes terminées: #124 MVP moteur (génération/persistance/découverte/protection/rendu versionné)
Branche finale: feat/control-panel-admin-tools (consigne : rester sur la branche courante, ne rien merger)
Dernier commit: (voir git log — commit feat(waypoints): #124)
Build: vert (./gradlew build, 11m36s sur la box contrainte)
Tests: verts — root :test 1254 (0 échec, 29 skip = MariaDB gated), control-panel 279 ; +33 tests waypoint
Tests manuels en attente: validation en jeu du rendu réel / biomes réels / physique fluides-pistons-gravité / suppression redstone du bouton (PENDING MANUAL VALIDATION, voir docs/MANUAL_TEST_PLAN.md)
Blocages: aucun
Première étape à reprendre: si #124 poursuivi → lecture /waypoints dans PlugAdmin (ou action agent list) ; sinon issue #122 (protection fonctionnelle anti-enfermement de proximité)
```

```text
Date: 2026-08-07
Branche de départ: feature/13-safe-zone
Étape de départ: 14 (Économie et marchands PNJ), TODO
Étapes terminées: 14
Branche finale: feature/14-economy-merchants
Dernier commit: 23f604e feat(economy): add player wallet, payments, and NPC merchants
Build: vert (./gradlew clean build)
Tests: 320+ tests verts (nouveaux : WalletRepositoryTest,
  MerchantDefinitionParserTest, MerchantLoaderTest, MerchantTradeServiceTest,
  ajouts DialogueDefinitionParserTest/DialogueSessionEngineTest pour
  OPEN_MERCHANT ; SchemaMigratorTest mis à jour pour la migration V4)
Tests manuels en attente: ouverture d'une vitrine par clic sur un PNJ
  renommé, lisibilité du lore des offres, /money pay entre deux vrais
  joueurs, latence réseau sur un achat/vente (voir docs/ECONOMY.md)
Blocages: aucun
Première étape à reprendre: 15 (Marché entre joueurs) — aucun cahier des
  charges détaillé retrouvé dans le dépôt pour les étapes 15 à 23, à
  clarifier avec l'utilisateur si besoin avant de démarrer
```

```text
Date: 2026-08-07
Branche de départ: feature/14-economy-merchants
Étape de départ: 15 (Marché entre joueurs), TODO, aucun cahier des charges
  détaillé dans le dépôt — portée définie par ingénierie (utilisateur a
  dit "continue" sans préciser)
Étapes terminées: 15
Branche finale: feature/15-player-market
Dernier commit: 731a948 feat(economy): add player-to-player market
Build: vert (./gradlew clean build)
Tests: 333 tests verts (nouveaux : MarketRepositoryTest, MarketServiceTest ;
  SchemaMigratorTest mis à jour pour la migration V5)
Tests manuels en attente: navigation entre pages de /market avec beaucoup
  d'offres, deux vrais joueurs achetant simultanément la même offre,
  latence réseau (voir docs/ECONOMY.md)
Blocages: aucun
Première étape à reprendre: 16 (Portails et téléportation) — aucun cahier
  des charges détaillé retrouvé dans le dépôt pour les étapes 16 à 23, à
  clarifier avec l'utilisateur si besoin avant de démarrer
```

```text
Date: 2026-08-07
Branche de départ: feature/15-player-market
Étape de départ: 16 (Portails et téléportation), TODO — cahier des charges
  détaillé reçu en conversation pendant la session (canalisation, sécurité
  de destination, cooldown persisté, coût, commandes admin, commit
  attendu feat(travel): add safe configurable portals)
Étapes terminées: 16
Branche finale: feature/16-portals
Dernier commit: 600c74b feat(travel): add safe configurable portals
Build: vert (./gradlew clean build)
Tests: 368 tests verts (nouveaux : DestinationTest, PortalDefinitionTest,
  DestinationDefinitionParserTest, PortalDefinitionParserTest,
  DestinationLoaderTest, PortalLoaderTest, YamlDestinationRegistryTest,
  YamlPortalRegistryTest, PortalServiceTest ; SchemaMigratorTest mis à
  jour pour la migration V6)
Tests manuels en attente: portail vers un chunk réellement déchargé,
  déconnexion en pleine canalisation, reconnexion et persistance du
  cooldown, téléportation avec inventaire chargé et quête active, test
  depuis/vers une safe zone réelle (voir docs/TRAVEL.md)
Blocages: aucun
Première étape à reprendre: 17 (Claims de terrain) — aucun cahier des
  charges détaillé retrouvé dans le dépôt pour les étapes 17 à 23, à
  clarifier avec l'utilisateur si besoin avant de démarrer
```

```text
Date: 2026-08-07
Branche de départ: feature/16-portals
Étape de départ: 17 (Claims de terrain), TODO — cahier des charges détaillé
  reçu en conversation pendant la session (modèle persistant, sélection,
  commandes, refus à la création, protections, bypass, politique
  d'extension future, aucun avantage payant, commit attendu
  feat(claims): add persistent protected land claims)
Étapes terminées: 17
Branche finale: feature/17-land-claims
Dernier commit: dfbe0e5 feat(claims): add persistent protected land claims
Build: vert (./gradlew clean build)
Tests: 411 tests verts (nouveaux : ClaimTest, ClaimRepositoryTest,
  ClaimServiceTest, ClaimProtectionListenerTest, ajouts ConfigValidatorTest
  pour la section claims ; SchemaMigratorTest mis à jour pour la migration V7)
Tests manuels en attente: deux joueurs voisins, coffres/portes/animaux/
  redstone réels, TNT dedans/dehors, piston traversant la limite en jeu,
  redémarrage complet du serveur (voir docs/CLAIMS.md)
Blocages: aucun
Première étape à reprendre: 18 (Mobs spéciaux vanilla) — aucun cahier des
  charges détaillé retrouvé dans le dépôt pour les étapes 18 à 23, à
  clarifier avec l'utilisateur si besoin avant de démarrer
```

```text
Date: 2026-08-07
Branche de départ: feature/17-land-claims
Étape de départ: 18 (Mobs spéciaux vanilla), TODO — cahier des charges
  détaillé reçu en conversation pendant la session (registre PDC,
  définition configurable complète, quatre variantes obligatoires, respect
  safe zone/claims, respect des événements annulés, anti-boucle de
  duplication, commandes admin, métriques debug, identification jamais par
  le nom affiché, commit attendu feat(mobs): add configurable vanilla mob
  variants)
Étapes terminées: 18
Branche finale: feature/18-special-mobs
Dernier commit: 19c60b1 feat(mobs): add configurable vanilla mob variants
Build: vert (./gradlew clean build)
Tests: 475 tests (nouveaux : SpecialMobDefinitionParserTest,
  SpecialMobLoaderTest, SpecialMobServiceTest, SplitOnHitAbilityListenerTest ;
  2 tests ignorés — pas échoués — dans SpecialMobServiceTest à cause d'une
  limitation MockBukkit 4.110.0 (setRemoveWhenFarAway non implémenté), voir
  ci-dessus)
Tests manuels en attente: faire apparaître chaque variante, tuer le creeper
  doré plusieurs fois, tester l'explosion en safe zone et en claim, frapper
  le zombie fissible jusqu'à la limite, décharger/recharger le chunk,
  redémarrer avec des mobs spéciaux présents (voir SPECIAL_MOB_FORMAT.md)
Blocages: aucun
Première étape à reprendre: 19 (XP RPG) — aucun cahier des charges détaillé
  retrouvé dans le dépôt pour les étapes 19 à 23, à clarifier avec
  l'utilisateur si besoin avant de démarrer
```

```text
Date: 2026-08-07
Branche de départ: feature/18-special-mobs
Étape de départ: 19 (XP RPG), TODO — cahier des charges détaillé reçu en
  conversation pendant la session (six pistes dont GLOBAL, SQLite, courbe
  configurable et validée, service générique d'octroi avec id d'événement,
  déduplication, sources interceptées, anti-farm, commandes, affichage,
  XP vanilla conservée, hooks de déblocage, modèle d'équilibrage documenté,
  commit attendu feat(progression): add multi-skill RPG experience system)
Étapes terminées: 19
Branche finale: feature/19-rpg-experience
Dernier commit: 7260d11 feat(progression): add multi-skill RPG experience system
Build: vert (./gradlew clean build)
Tests: 503 tests (nouveaux : ProgressionCurveTest, ProgressionServiceTest,
  CombatXpListenerTest, MiningXpListenerTest ; SchemaMigratorTest mis à
  jour pour la migration V8 ; 9 tests ignorés — pas échoués, limitation
  MockBukkit héritée de l'étape 18, voir ci-dessus)
Tests manuels en attente: combat normal et mob spécial, miner un bloc
  naturel puis un bloc posé, récolter une culture mûre/non mûre, pêcher,
  terminer une quête, redémarrer et vérifier les niveaux (voir
  docs/PROGRESSION.md)
Blocages: aucun
Première étape à reprendre: 20 (Backpacks) — aucun cahier des charges
  détaillé retrouvé dans le dépôt pour les étapes 20 à 23, à clarifier avec
  l'utilisateur si besoin avant de démarrer
```

```text
Date: 2026-08-07
Branche de départ: feature/19-rpg-experience
Étape de départ: 20 (Backpacks), TODO — cahier des charges détaillé reçu en
  conversation pendant la session (inventaire virtuel persistant, tailles
  configurables, objet/commande d'ouverture, permission de secours +
  commandes admin, stockage sûr et versionné, interdictions (imbrication,
  objets interdits, ouverture simultanée, interaction non autorisée),
  sauvegarde atomique fermeture/déconnexion/arrêt, boîte de récupération,
  upgrade sans perte, interface EntitlementService générique, modèle
  documenté, commit attendu feat(storage): add secure persistent backpacks)
Étapes terminées: 20
Branche finale: feature/20-backpacks
Dernier commit: adf6828 feat(storage): add secure persistent backpacks
Build: vert (./gradlew clean build)
Tests: 523 tests (nouveaux : ItemArraySerializerTest, BackpackServiceTest,
  BackpackListenerTest ; SchemaMigratorTest mis à jour pour la migration V9 ;
  11 tests ignorés — pas échoués, limitation MockBukkit héritée de l'étape
  18 + deux occurrences supplémentaires cette étape, voir ci-dessus)
Tests manuels en attente: ouvrir/remplir/fermer/reconnecter, mourir avec le
  backpack, changer de monde, forcer l'arrêt avec le backpack ouvert,
  upgrade puis downgrade, deux clients sur le même compte si possible (voir
  docs/BACKPACKS.md)
Blocages: aucun
Première étape à reprendre: 21 (API et site web read-only) — aucun cahier
  des charges détaillé retrouvé dans le dépôt pour les étapes 21 à 23, à
  clarifier avec l'utilisateur si besoin avant de démarrer
```

```text
Date: 2026-08-07
Branche de départ: feature/20-backpacks
Étape de départ: 21 (API et site web read-only), TODO — cahier des charges
  détaillé reçu en conversation pendant la session (module web séparé du
  gameplay, jamais d'accès direct au fichier SQLite, API authentifiée —
  statut, joueurs, classements, catalogue public, annonces —, lectures via
  snapshots/caches ou async, authentification serveur-à-serveur, rate
  limiting + validation + journalisation, aucun secret dans Git, site
  minimal (accueil/statut/classements/wiki), mode dégradé si le serveur
  Minecraft est arrêté, ni paiement ni login joueur ni écriture à ce
  stade, commit attendu feat(web): add read-only server API and portal)
Étapes terminées: 21
Branche finale: feature/21-web-api
Dernier commit: 54a8147 feat(web): add read-only server API and portal
Build: vert (./gradlew clean build, deux modules : racine + web-api)
Tests: 535 tests plugin (11 ignorés — pas échoués, limitation MockBukkit
  héritée de l'étape 18, aucune occurrence supplémentaire cette étape) +
  19 tests web-api (0 ignoré) ; nouveaux : ProgressionRepositoryTest
  (topPlayers), WebSnapshotWriterTest, ConfigValidatorTest (web-export),
  JsonTest, HttpServerBootstrapTest (bout-en-bout, vrai HttpServer + vrai
  HttpClient)
Tests manuels en attente: lancer serveur et site localement, consulter les
  pages, arrêter Minecraft et vérifier le mode dégradé, vérifier qu'aucun
  secret n'apparaît dans les réponses ou logs (voir docs/WEB_API.md)
Blocages: aucun
Première étape à reprendre: 22 (Boutique web et livraison sécurisée) —
  aucun cahier des charges détaillé retrouvé dans le dépôt pour les étapes
  22 à 23, à clarifier avec l'utilisateur si besoin avant de démarrer
```

```text
Date: 2026-08-07
Branche de départ: feature/21-web-api
Étape de départ: 22 (Boutique web et livraison sécurisée), TODO — cahier
  des charges détaillé reçu en conversation pendant la session (catalogue
  produit séparé des avantages techniques, 5 produits initiaux, prestataire
  de paiement externe en mode test, aucune donnée de carte bancaire
  stockée, table de commandes + file de livraisons à identifiants uniques,
  livraison répétée ignorée sans erreur, récupération après redémarrage,
  signature/authentification site ↔ serveur, gestion joueur hors
  ligne/UUID inconnu/produit déjà possédé/upgrade/remboursement/
  révocation/échec temporaire, historique admin, logs d'audit sans donnée
  sensible, philosophie pay-to-convenience/anti-pay-to-win, mode sandbox
  d'abord, commit attendu feat(store): add secure idempotent web purchases)
  — branche demandée par l'utilisateur : feature/22-web-store
Étapes terminées: 22
Branche finale: feature/22-web-store
Dernier commit: 841f0cf feat(store): add secure idempotent web purchases
Build: vert (./gradlew clean build, deux modules : racine + web-api)
Tests: 544 tests plugin (11 ignorés — pas échoués, limitation MockBukkit
  héritée de l'étape 18, aucune occurrence supplémentaire) + 30 tests
  web-api (0 ignoré) ; nouveaux : StoreDeliveryServiceTest,
  SchemaMigratorTest (migration V10), StoreHttpTest (bout-en-bout : achat,
  webhook rejoué/signature invalide, livraison répétée, remboursement,
  historique, reprise après redémarrage)
Tests manuels en attente: achat sandbox d'un Small Backpack, serveur
  arrêté pendant l'achat puis redémarrage et livraison, upgrade vers
  Medium puis Large, rejouer le webhook, simuler un remboursement (voir
  docs/STORE.md)
Blocages: aucun
Première étape à reprendre: 23 (Prototype de mod client séparé) — aucun
  cahier des charges détaillé retrouvé dans le dépôt, à clarifier avec
  l'utilisateur si besoin avant de démarrer
```

```text
Date: 2026-08-07
Branche de départ: feature/22-web-store
Étape de départ: 23 (Prototype de mod client séparé), TODO — cahier des
  charges détaillé reçu en conversation pendant la session (mod jamais
  intégré au jar Paper, module/dépôt séparé clairement identifié, choix
  Fabric/NeoForge après vérification réelle de compatibilité avec la
  version du serveur et documentation du choix, mécanisme de vérification
  de compatibilité plugin ↔ mod, prototype minimal (ressource/bloc réel,
  objet associé, représentation visuelle d'une variante de mob, petite
  indication client), serveur responsable de la progression/drops/
  économie/droits/achats, client ne pouvant jamais s'auto-déclarer
  possesseur d'un objet ou avoir terminé une action, détection client
  compatible/sans mod/mauvaise version, politique vanilla-autorisé-avec-
  repli ou mod-obligatoire seulement si explicite, documentation
  installation/mise à jour/compatibilité, commit attendu feat(mod): add
  isolated client mod prototype) — branche demandée par l'utilisateur :
  feature/23-mod-prototype
Étapes terminées: 23
Branche finale: feature/23-mod-prototype
Dernier commit: dfc3af5 feat(mod): add isolated client mod prototype
Build: vert sur les trois projets Gradle (racine, web-api, client-mod) ;
  gradlew.bat clean build à la racine ne construit jamais client-mod/
  (mission, validation "le plugin Paper reste compilable et testable
  indépendamment")
Tests: 558 tests plugin (11 ignorés — pas échoués, limitation MockBukkit
  héritée de l'étape 18, aucune occurrence supplémentaire) + 30 tests
  web-api (0 ignoré) ; nouveaux : HandshakeProtocolTest (encodage/décodage
  pur, round-trip VarInt+UTF-8 Unicode), ModCompatServiceTest (client
  compatible, version incorrecte avec/sans obligation, client vanilla
  après délai, paquet réseau invalide, reconnexion, tentative de
  falsification, diffusion cosmétique conditionnelle) ; côté mod, aucun
  test automatisé (nécessiterait un client Minecraft lancé), mais
  client-mod/gradlew.bat build compile et remape avec succès contre le
  vrai jar client Minecraft 1.21.11 (Fabric Loom)
Tests manuels en attente: connexion avec client moddé, connexion avec
  client vanilla, vérification du contenu (bloc/objet, HUD), mauvaise
  version volontaire, redémarrage et mise à jour du mod (voir
  docs/CLIENT_MOD.md)
Blocages: aucun
Première étape à reprendre: aucune — la table "Étapes" ne définit rien
  au-delà de 23 ; voir TODO.md "Plus tard" pour des pistes, à préciser par
  l'utilisateur avant de démarrer
```
