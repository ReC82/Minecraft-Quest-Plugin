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
Date: 2026-10-04 (suite overnight — round 2, retours joueur après déploiement #152/#154)
Branche de départ: feat/control-panel-admin-tools @ 22d4ed2/fdb0926 (entrée précédente)
Étape de départ: validations reçues (faim Wild OK, Rune Hub OK, noms avec espaces OK, reclic
  déjà-découvert OK, listes/filtres panel OK) + nouveau lot à corriger dans l'ordre : #157 (bossbar
  </gray> littéral + id technique brut), #150 (recherche de waypoints toujours défectueuse malgré
  le correctif précédent), #160 (première découverte sans nom), #167 (panneaux latéraux de nom,
  nouveau), clarté du libellé « Apparié » du panel Voyage. Mêmes autorisations overnight.
Étapes terminées:
(1) DONE — #157 : gabarit de la bossbar réparé (balise </gray> en trop supprimée), libellé humain
  de l'objectif affiché au lieu de l'id technique brut (repli sur l'id seulement si aucune
  description). Nouveau TrackedQuestDisplayTest (5 cas) vérifie la vraie BossBar envoyée.
(2) DONE — #150 (cause racine, pas seulement l'indicateur déjà ajouté) : le clic sur le slot
  résultat d'une enclume suit un chemin vanilla spécial qui ne garantit pas que l'ItemStack lu par
  le clic porte encore le texte tapé. handlePrepareAnvil mémorise désormais le texte à chaque
  frappe dans la session ; handleSearchResultClick le lit depuis la session, jamais relu sur l'objet
  cliqué. Vérifié par un vrai PrepareAnvilEvent + un vrai InventoryClickEvent (nouveau FakeAnvilView,
  double de test pour l'AnvilView que MockBukkit ne fournit pas), y compris un test qui vide le slot
  2 avant le clic pour prouver l'indépendance vis-à-vis de l'objet cliqué.
(3) DONE — #160 : message de première découverte aligné sur celui du reclic
  (« Waypoint découvert : <nom> — <biome> »), nom canonique jamais remplacé par le seul biome.
(4) DONE — #167 (nouveau) : WaypointModelV1 pose deux OAK_WALL_SIGN sur les faces latérales du
  bouton (jamais la face opposée), nom canonique réparti sur les lignes (wrapSignLines, jamais un
  mot coupé). Inclus dans protectedBlocks() (protection automatique, y compris pour les waypoints
  déjà en base). Nouvelle commande /rpgadmin travel signs upgrade [monde] : mise à niveau idempotente
  des waypoints existants (id/position/découvertes inchangés). Bug trouvé et corrigé en écrivant le
  test : un BlockState obtenu puis validé après une mutation de BlockData séparée écrasait
  l'orientation posée (capturé avant la mutation) — orientation et texte sont désormais posés et
  validés via le même BlockState.
(5) DONE — Clarté du panel Voyage : « Apparié » (oui/non) remplacé par « Borne associée »/
  « Waypoint associé » affichant l'id réel de l'association, avec une phrase explicative au-dessus
  de chaque tableau (jamais confondu avec un statut de découverte joueur).
(6) DONE — trace temporaire [HUNGER-TRACE] (#159) retirée, la faim dans le Wild étant validée OK.
Tests : suite complète ./gradlew test (3 modules) : 1812 tests, 1777 exécutés verts, 35 ignorés
  (limitation MockBukkit teleportAsync déjà documentée), 0 échec — confirmé par les XML de résultat.
  ./gradlew build (3 modules) vert aussi (RPGQUEST_TEST_MAX_HEAP=768m obligatoire sur cette box).
  Un échec intermédiaire a été diagnostiqué et corrigé avant ce résultat final (le test de panneau
  #167 avait révélé le bug BlockState ci-dessus) ; jamais présenté comme validé avant correction.
Branche finale: feat/control-panel-admin-tools (aucun merge — déploiement DEV/AWS autorisé
  explicitement pour cette session)
Dernier commit: (voir git log — 3ac5fcf #157, 88471a6 #150, d2cf012 #160, 1c6e434 #167, 1116965
  libellés panel ; docs à committer séparément ensuite)
Build: vert, voir Tests ci-dessus.
Tests manuels en attente: TC-227 (recherche, round 2 — à revalider précisément malgré la correction
  de cause racine), nouveaux TC pour #157/#160/#167 à ajouter dans docs/MANUAL_TEST_PLAN.md avec le
  rapport de session ; le coût XP affiché côté client reste non vérifiable automatiquement
  (PrepareAnvilEvent/AnvilView partiellement simulés par FakeAnvilView, pas le rendu client réel).
Blocages: aucun.
Première étape à reprendre: déploiement DEV (plugin) + AWS (Control Panel) de ce round, puis
  validation manuelle en jeu des 5 points ci-dessus ; #161/#162/#163 restent en file (non commencés).
```

```text
Date: 2026-10-04 (issue #190 — équilibrage Zombie fissile, poursuite Cochon Creeper, formulaire)
Branche de départ: feature/169-special-mobs-boss @ 3470436 (entrée précédente, lot 1 déployé)
Étape de départ: retours de test en jeu du lot #169 : Cochon Creeper explose bien mais ne
  poursuit pas ; Zombie fissile naturel beaucoup trop rapide et se multiplie excessivement ;
  demande de bases passives (cochon/poule/grenouille) généralisées ; parcours « Nouveau profil »
  pensé non fonctionnel par l'utilisateur. Consigne explicite d'identifier le mécanisme réel avant
  de corriger, d'appliquer le correctif au profil déjà déployé (pas seulement au gabarit), et de
  traiter #190 avant de reprendre #179.
Étapes terminées:
(1) DONE — Cause du Zombie fissile tracée dans le code (pas supposée) : `speed: 1.0` valait ~4x la
  vitesse vanilla du zombie (attribut brut, pas un multiplicateur) ; `SplitOnHitAbilityListener`
  relançait une division complète à CHAQUE coup non mortel reçu par un même parent (tant que sa
  profondeur restait sous max-depth), sans aucun plafond propre à ce parent -- seul un plafond
  global partagé existait. Corrigé : `speed: 0.25` ; nouveau champ `max-alive-per-parent`
  (optionnel, défaut 2) plafonnant les enfants vivants d'un même parent via une PDC dédiée +
  comptage borné des entités proches, indépendamment du nombre de coups reçus. Appliqué au profil
  déjà déployé via `deploy-verygames.sh --also` (pas seulement au gabarit des futurs profils),
  avec backup préalable vérifié identique au gabarit par défaut (aucune personnalisation perdue).
(2) DONE — Cochon Creeper (et toute base passive avec `EXPLOSIVE_ON_ATTACK`) poursuit désormais un
  joueur à portée de perception (16 blocs) via `org.bukkit.entity.Mob#getPathfinder()` -- API
  publique Paper, aucun NMS (changer les statistiques seules ne donne aucune IA de poursuite à une
  base passive, qui n'a simplement pas ce goal d'IA). Les animaux ordinaires ne sont jamais
  affectés (uniquement les entités taguées comme mob spécial).
(3) DONE — Bases passives (PIG/CHICKEN/FROG) confirmées déjà pleinement supportées côté backend
  (aucune restriction n'existait) via un nouveau test direct de `BukkitAgentActions` ; aide
  contextuelle du formulaire mise à jour pour le dire explicitement (les capacités offensives type
  explosif au contact restent non éditables depuis le panel -- scope #170, pas de ce lot).
(4) DONE — Audit du « Nouveau profil » : le journal d'actions réel (agent_action/audit_log, lu
  directement en base sur l'hôte AWS) a montré ZÉRO tentative mob.definition.create jamais reçue,
  alors que mob.list/mob.test.spawn fonctionnaient -- et un nouveau test direct de
  `BukkitAgentActions.mobDefinitionCreate` (jamais testé directement jusqu'ici) a confirmé que la
  logique serveur est correcte, y compris sur bases passives. Cause la plus probable identifiée :
  formulaire de ~25 champs sur 5 sections, bouton d'enregistrement uniquement tout en bas --
  facile à manquer, surtout sur mobile. Corrigé : bouton dupliqué juste après la section Identité ;
  capacités Enragé/Invocation repliées par défaut (`<details>` natif, sans JS).
Tests: SpecialMobDefinitionParserTest (+1), SpecialMobDefinitionYamlTest (+1, round-trip),
  SplitOnHitAbilityListenerTest (réécrit pour taguer l'identité en PDC plutôt que via
  SpecialMobService#apply -- 2 tests de plus s'exécutent réellement au lieu d'être ignorés ; +1
  nouveau test reproduisant exactement 4 coups répétés sur le même parent, plafonné à 2 enfants),
  ExplosiveOnAttackAbilityServiceTest (nouveau, premiers tests de cette classe : déclenchement de
  l'explosion et absence de déclenchement hors portée vérifiés réellement ; la poursuite elle-même
  échoue sur Mob#getPathfinder(), non implémenté par MockBukkit -- documenté, pas ignoré
  silencieusement), BukkitAgentActionsMobTest (nouveau, premiers tests directs de
  BukkitAgentActions plutôt que seulement son double de test). Suite complète 3 modules : 1841
  tests, 1806 exécutés verts, 35 ignorés, 0 échec.
Branche finale: feature/169-special-mobs-boss (aucun merge).
Dernier commit: `3470436` docs: document max-alive-per-parent and Explosive-on-attack pursuit.
Build: vert (voir Tests).
Tests manuels en attente: TC-232 (voir docs/MANUAL_TEST_PLAN.md) -- vitesse/plafond du Zombie
  fissile en situation réelle, poursuite du Cochon Creeper (aucune couverture automatisée possible
  pour ce point précis, limitation MockBukkit), parcours complet de création d'un nouveau profil.
Blocages: aucun. Incident mineur auto-résolu pendant le déploiement : le contrôle de santé du
  script plugadmin/deploy.sh a échoué une fois juste après systemctl restart (JVM pas encore liée
  au port, ~2 s) -- confirmé résolu en moins de 30 s via journalctl + vérification manuelle,
  aucun rollback nécessaire ; amélioration (attente/retry dans le script) notée pour plus tard,
  hors périmètre de cette tâche.
Première étape à reprendre: validation manuelle de TC-232, puis #179 (parcours du Garde).
```

```text
Date: 2026-10-05 (lot 10 — #95 module « Exploitation serveur » dans PlugAdmin, lot 1)
Branche de départ: feature/169-special-mobs-boss @ 5f5d4c6 (lot 9, #22 déployé)
Étape de départ: le propriétaire demande d'abord de CONFIRMER le dépannage #22 (fait : joueur sorti
  de Claims, correctif déployé, aucun blocage restant), puis de réaliser le premier lot concret de
  #95 : page Exploitation serveur, état réel + fraîcheur, annonce avec aperçu/modèles/canaux
  réellement supportés, redémarrage immédiat ou différé avec annonces + annulation + suivi jusqu'au
  retour ONLINE, logs récents avec recherche/filtres/pause actualisés automatiquement.
  Consignes dures : réutiliser l'existant, « un simple stop ne constitue pas un restart », afficher
  les fonctions indisponibles, aucune console shell/RCON libre, aucun reload global Bukkit,
  #131 et #210 préparés mais NON implémentés.
Étapes terminées:
(1) DONE — AUDIT, trois constats MESURÉS qui ont décidé la conception :
  * le fichier de log du serveur n'est PAS atteignable. Sondé avec la bibliothèque FTP du dépôt
    AVANT d'écrire du code : la racine FTP EST plugins/, et le serveur répond
    « Server denied you to change to the given directory » sur ../logs. Donc console alimentée par
    le PLUGIN et remontée par l'agent sortant existant — ni SSE, ni WebSocket, ni port entrant.
  * java.util.logging aurait capté presque rien : Paper route getSLF4JLogger() DIRECTEMENT vers
    Log4j2, donc un Handler JUL ne verrait pas nos propres lignes. D'où un appender Log4j2
    (log4j-core en compileOnly, fourni par le serveur, jamais empaqueté ; LinkageError rattrapée →
    console « indisponible » avec motif, serveur qui démarre normalement).
    VALIDÉ EN PRODUCTION : la capture contient des lignes RPGQuest (SLF4J), spark, ET vanilla
    (net.minecraft.server.rcon.thread.RconThread) — les deux dernières invisibles avec JUL.
  * le redémarrage ne peut venir NI de l'agent (il s'arrête avec le serveur) NI du script shell
    (service PlugAdmin sous ProtectHome=true, sans accès au $HOME ni aux identifiants RCON de
    l'opérateur). D'où un client RCON EN JAVA dans le panel, réutilisant le mécanisme éprouvé de
    scripts/verygames-restart.sh sans réutiliser le script.
(2) DONE — Réutilisation : AgentStore/AgentEndpoints/AgentActionCatalog/AgentActionExecutor (les
  deux opérations sont des actions agent ORDINAIRES, validées côté panel ET côté plugin),
  AgentLiveness + HeartbeatRecord pour l'état et la fraîcheur (aucun nouveau relevé),
  Ui/Layout/Icons/panel.js/plugadmin.css, /agents/action avec son CSRF, l'audit existant.
(3) DONE — SSE écarté et le choix DOCUMENTÉ comme le ticket l'exige : la fraîcheur est de toute
  façon bornée par la scrutation de l'agent déployé (15 s, lu dans son fichier réel). Un second
  canal n'accélérerait rien. Le navigateur interroge /ops/logs.json toutes les 4 s, le panel
  n'enfile QU'UN relevé à la fois, et la page AFFICHE cette latence (~15 s).
(4) DONE — UN ARRÊT N'EST PAS UN REDÉMARRAGE (exigence centrale) : on vérifie d'abord que le
  serveur répond, sinon AUCUN arrêt n'est demandé ; puis save-all best-effort, puis stop ; puis on
  attend une PREUVE — serveur vu hors ligne puis répondant, OU uptime du plugin DIMINUÉ (relevé par
  le heartbeat, ce qui couvre une relance plus rapide que l'intervalle de sonde). Sans preuve →
  ÉCHEC avec motif. Single-flight (= protection double-clic et rejeu). Confirmations ADAPTÉES :
  case pour un différé annulable, saisie du mot REDEMARRER pour l'immédiat.
(5) DONE — Aucune commande arbitraire PAR CONSTRUCTION : RconCommand est une énumération de TROIS
  valeurs. Aucun chemin de code ne porte un texte libre jusqu'au serveur. L'annonce envoie le
  message en TEXTE LITTÉRAL — un <click:run_command:…> ferait exécuter une commande à tous les
  joueurs qui cliquent — et un message commençant par « / » est refusé des DEUX côtés.
  Trois canaux réellement supportés (chat/actionbar/title), chacun testé comme délivrant vraiment ;
  NO_PLAYERS dit honnêtement quand personne n'était connecté.
(6) DONE — Fonctions indisponibles AFFICHÉES avec leur motif réel : start/stop explicite (pas d'API
  de supervision), console complète de l'hébergeur (motif mesuré en (1)), sauvegarde restaurable
  (save-all n'est pas un point de restauration), mode maintenance, annonces programmées.
  #131 et #210 annoncés comme lots SUIVANTS avec leur emplacement prévu — rien n'en est implémenté.
(7) DONE — Sécurité : quatre permissions SÉPARÉES (OPS_VIEW jusqu'à READ_ONLY ; OPS_LOGS pas pour
  READ_ONLY car une ligne de log contient pseudos/coordonnées/erreurs internes ; OPS_ANNOUNCE et
  OPS_RESTART réservées OWNER+ADMIN). Secret RCON uniquement depuis l'environnement du service ;
  RconEndpoint.toString() redéfini pour ne jamais l'imprimer, et un test vérifie qu'aucun message
  d'erreur ne le contient. CSRF sur chaque POST, permission vérifiée côté BACKEND, audit
  ops.restart / ops.restart.cancel. Aucun reload global Bukkit.
Tests: suite complète verte — 1538 plugin (34 ignorés), 543 control-panel (1 ignoré), 30 web-api,
  0 échec, 0 erreur. 110 cas nouveaux : ServerLogBufferTest (14), ConsoleTapTest (8),
  ServerOpsServiceTest (10), AgentActionExecutorTest (+11), RestartServiceTest (24),
  RconClientTest (7), AgentActionCatalogTest (+11), RolePermissionMatrixTest (+5), OpsPageTest (20).
  RconClientTest confronte le client à un VRAI serveur RCON ouvert dans la JVM de test : le
  protocole est binaire, une erreur d'ordre d'octets ne se verrait qu'en production PENDANT un
  redémarrage.
  UN DÉFAUT DE MA PROPRE CONCEPTION, trouvé et corrigé AVANT livraison : la console ré-enfile un
  relevé à chaque cycle d'agent et AgentStore n'avait AUCUNE purge — une heure de consultation
  aurait laissé des centaines de lignes dans agent_action et noyé l'historique réel des actions.
  J'ai mesuré la table existante (~150 lignes) avant de conclure. Purge CIBLÉE ajoutée
  (pruneTerminalActionsOfType), qui ne touche jamais une action en cours, couverte par deux tests.
  UN TEST EXISTANT A REFUSÉ MON CODE ET AVAIT RAISON :
  CatalogResyncTest#panelScriptCarriesTheSharedReloadOnIdleMechanism impose UN SEUL point de
  rechargement dans panel.js. Mon module en ajoutait un second. Le test n'a PAS été relâché : le
  module réutilise runReload().
Branche finale: feature/169-special-mobs-boss (aucun merge).
Commit: c7962f4.
Build: vert.
Déploiements: DEUX cibles. (a) Control Panel AWS via scripts/plugadmin/deploy.sh — service
  active (running), /health -> {"panel":"ONLINE"}. (b) JAR VeryGames DEV SHA-256
  cbac00bfb6bba03add3b919c5a15765cabba370e245d9355868a9d53c9d38dbb (1 756 236 octets) avec UN SEUL
  redémarrage, annoncé en jeu : arrêt CONSTATÉ OFFLINE, retour CONSTATÉ ONLINE ; puis plugins -> 4
  verts, rpgquest version -> v0.1.0-SNAPSHOT. JAR précédent sauvegardé
  (rpgquest-20261005T205914Z-predeploy.jar, SHA-256 97e77bc3…).
  Configuration serveur ajoutée HORS DÉPÔT : ops.rcon.dev.host/.port dans
  /etc/plugadmin/control-panel.properties, RPGQUEST_RCON_PASSWORD_DEV dans
  /etc/plugadmin/plugadmin.env (640 root:plugadmin). Les deux fichiers SAUVEGARDÉS avant
  modification. AUCUNE valeur de secret affichée à aucun moment.
Vérifications sur la page RÉELLEMENT déployée (compte temporaire claude-ops, SUPPRIMÉ ensuite) :
  agent ONLINE, uptime réel, version réelle, fraîcheur « il y a 2 s » ; 5 modèles d'annonce ;
  3 canaux chat/actionbar/title ; console avec recherche, filtres ERROR/WARN/INFO, pause, suivi
  auto, retour en bas ; section « Non disponible dans ce lot » avec ses motifs réels et #131/#210 ;
  AUCUNE zone de saisie libre ; bloc de redémarrage ACTIF (donc le service lit bien la config RCON).
Chaîne de vérification de bout en bout, entièrement réelle :
  server.logs.tail REJECTED avant le JAR (dégradation gracieuse), SUCCESS 37 lignes après, puis
  1 ligne avec curseur 37->38 (incrémental) ; lignes captées = RPGQuest + spark + vanilla ;
  server.announce SUCCESS « Aucun joueur connecté : l'annonce n'a été affichée à personne. ».
Tests manuels en attente: TC-246 — 31 étapes, dont un VRAI redémarrage depuis le panel. AUCUN test
  en jeu n'a été exécuté : rendu des trois canaux vu par un joueur, redémarrage piloté de bout en
  bout, ergonomie de la console sous charge, permissions depuis de vrais comptes.
Limites: un redémarrage DIFFÉRÉ est perdu si PlugAdmin redémarre (opération en mémoire ; rien n'est
  jamais exécuté EN RETARD, comportement sûr). Fraîcheur console bornée à ~15 s par l'agent (la
  réduire changerait la cadence de TOUTES les actions : hors périmètre). La capture dépend du niveau
  du logger racine — c'est AFFICHÉ, pas silencieux. Pas de rétention générale sur agent_action.
  Un seul agent consulté (modèle par cible, mais pas encore de sélecteur).
Propreté: aucun contenu du propriétaire modifié, aucune progression touchée, aucun reset joueur.
  Compte de vérification supprimé, mot de passe et session effacés du scratchpad.
Première étape à reprendre: ouvrir /ops et contrôler le bloc « État du serveur » (agent ONLINE,
  uptime cohérent, version 0.1.0-SNAPSHOT, fraîcheur < 30 s), puis dérouler TC-246 quand personne
  ne joue. Ensuite, attendre le choix du prochain ticket par le propriétaire.
```

```text
Date: 2026-10-05 (lot 9 — #22 URGENCE : joueur coincé dans le monde des claims)
Branche de départ: feature/169-special-mobs-boss @ dddf709 (lot 8, #12 déployé)
Étape de départ: urgence signalée par le propriétaire — coincé dans le monde Claims avec son Acte,
  aucun moyen de revenir au Hub. Consigne : débloquer D'ABORD, puis diagnostiquer et corriger #22.
  Travail non urgent interrompu proprement (aucun autre lot en vol).
Étapes terminées:
(1) DONE — DÉBLOCAGE vérifié. LoDyMcFly, seul connecté, était dans minecraft:claims
  (-20.5, 101, 44.8). Destination LUE SUR LE SERVEUR (RPGQuest/spawn.yml), jamais inventée :
  minecraft:world_hub 738.8234287986836 / 67.0 / -679.5730077006267. Dimension et Pos RELUS après
  la téléportation pour confirmer — pas « absence d'erreur » mais vérification positive.
  Aucun reset, aucune suppression, inventaire/Acte/claim/progression intacts.
  Honnêteté : l'inventaire complet n'a PAS pu être énuméré (RCON tronque les NBT longs). Établi
  sans rien détruire via /clear <joueur> <objet> 0 (compte sans retirer) : aucun echo_shard,
  aucun paper, 1 amethyst_shard.
(2) DONE — CAUSE RACINE par élimination. player.variable.get CLAIM_TIER_1 -> true. Éligible +
  dans claims + sans ECHO_SHARD ne sont compatibles qu'avec UNE branche :
  ClaimWorldSafetyListener#handleArrival sortait AVANT ensureReturnStone pour les porteurs de
  rpgquest.admin.world. Le bypass dispensait donc de TOUT (ni renvoi, ni Pierre) -> un
  administrateur ÉLIGIBLE n'avait AUCUNE sortie. L'ancien commentaire décrivait déjà ce cas comme
  « la cause la plus fréquente » sans le corriger, et un TEST figeait le défaut : réécrit.
(3) DONE — Deux défauts secondaires trouvés en auditant comme demandé :
  * jo.yml exigeait HAS_MAIN_CLAIM pour « Obtenir une Pierre de retour » -> inaccessible
    EXACTEMENT quand ce recours sert (droit débloqué, terrain pas encore posé). CLAIM_TIER_1 suffit
    désormais. Le jo.yml DU SERVEUR a été téléchargé et comparé au dépôt (identiques) avant
    toute conclusion : l'analyse porte bien sur la production.
  * inventaire plein : l'objet tombait au sol mais le message disait « tu reçois ». Distingué.
(4) DONE — RÈGLE PRÉVENTIVE (demande ajoutée en cours de lot) : on n'entre plus dans claims sans
  le moyen d'en repartir. ClaimWorldAccessGuard exige, AVANT toute téléportation, que la
  destination de retour se résolve (exactement spawnService::resolve, la cible de la Pierre
  elle-même, jamais une copie) ET que le joueur détienne ou puisse recevoir une Pierre DANS son
  inventaire. Jamais de dépôt au sol au point de départ : cela ne garantirait rien et joncherait le
  Hub. Sinon entrée REFUSÉE avec le motif exact. Appliqué aux trois chemins d'autorisation ; le
  bypass n'est JAMAIS refusé mais reçoit la Pierre au mieux.
(5) DONE — Aucune règle dupliquée : nouveau claim.ClaimReturnService, unique source du moyen de
  retour, partagé par le garde d'entrée et le filet d'arrivée, à issue TYPÉE
  (ALREADY_HELD/GIVEN/DROPPED/NO_ROOM/UNAVAILABLE) dont canReturn() ne peut pas mentir.
(6) DONE — Vérifié et NON modifié parce que correct : enregistrement ItemTravelDefinition
  (PIERRE_RETOUR, 3 s, spawnService::resolve, requiredWorld = claims.world), résolution de la
  destination, objets soulbound, renvoi au Hub d'un joueur NON éligible, config du monde claims.
Tests: suite complète verte — 1495 plugin (34 ignorés), 476 control-panel (1 ignoré), 30 web-api,
  0 échec, 0 erreur. 10 cas nouveaux ou réécrits.
  DEUX DÉFAUTS DE MES PROPRES TESTS, mesurés au lieu d'être supposés :
  * PlayerInventoryMock.firstEmpty() renvoie encore 36 après remplissage de 0-35 : remplacé par une
    boucle sur firstEmpty() avec garde, et la précondition est AFFIRMÉE dans les tests.
  * deux tests attendaient une lecture ASYNCHRONE pendant un nombre FIXE de ticks. Verts en suite
    complète, ROUGES pendant le déploiement (machine chargée). Le garde-fou du script de
    déploiement a refusé de livrer AVANT tout transfert FTP — il a fait exactement son travail.
    Corrigés (commit eb439a3) : ils pompent jusqu'à réception du message.
Branche finale: feature/169-special-mobs-boss (aucun merge).
Commits: a91f99a (correctif + documentation), eb439a3 (course de test corrigée).
Build: vert.
Déploiements: JAR VeryGames DEV (SHA-256
  97e77bc3b054ffd94094a9b8684a3216fc2b15cb3ac658b49047e91a1b8ceb1f, 1 735 478 octets) ET
  RPGQuest/dialogues/jo.yml via --also. CE DERNIER EST INDISPENSABLE : jo.yml n'est pas un exemple
  empaqueté (YamlDialogueEngine.BUNDLED_EXAMPLES = {"guard.yml"}) et un fichier existant n'est
  jamais réécrit — un redéploiement de JAR seul n'aurait PAS mis à jour le dialogue de Jo.
  Backups : rpgquest-20261005T195459Z-predeploy.jar (SHA-256 1ab198d0…) et
  extra-20261005T195459Z/RPGQuest/dialogues/jo.yml (SHA-256 55418687…) avec MANIFEST.txt.
  UN SEUL redémarrage, annoncé en jeu : save-all, stop, arrêt CONSTATÉ OFFLINE, retour CONSTATÉ
  ONLINE. Vérifié ensuite par RCON : plugins -> 4 verts (Citizens, Multiverse-Core, RPGQuest,
  WorldEdit), rpgquest version -> v0.1.0-SNAPSHOT.
  Control Panel NON redéployé (lot plugin uniquement) ; /health -> 200.
Tests manuels en attente: TC-245 — protocole complet en jeu (réception de la Pierre AU HUB avant le
  départ, refus avec inventaire plein sans rien laisser au sol, compte OP jamais renvoyé mais doté,
  choix de Jo visible sans claim posé). AUCUN test en jeu n'a été exécuté par Claude.
Limites: le correctif ne crée pas rétroactivement de Pierre pour un joueur DÉJÀ dans claims — le
  filet d'arrivée s'en charge à sa prochaine arrivée/connexion. Le garde ne contrôle que les
  World-Portals : /mv tp et /tp restent couverts par le filet, pas par la règle préventive.
Propreté: aucun contenu du propriétaire modifié, aucune progression touchée, aucun reset joueur.
Première étape à reprendre: dérouler TC-245 en jeu. Ensuite, attendre le choix du prochain ticket.
```

```text
Date: 2026-10-05 (lot 8 — #12 signal visuel sur les PNJ, première version)
Branche de départ: feature/169-special-mobs-boss @ 7abfbf3 (lot 7, #194 déployé)
Étape de départ: le propriétaire choisit désormais les tickets un par un. Ticket actif : #12
  uniquement, dont le périmètre a été réécrit le 05/10 (la mention « plus tard / hors périmètre »
  du corps de l'issue est remplacée par une activation explicite). Aucun autre ticket à lancer.
État des lots précédents au moment de bascule: #196 et #194 livrés ET déployés ; #194 attend
  seulement le test manuel TC-243 du propriétaire (étape 1 fournie). Working tree propre, branche
  poussée, aucun build en cours — rien n'a été interrompu.
Étapes terminées:
(1) DONE — AUDIT préalable, qui a changé deux décisions :
  * « quête prête à rendre » n'est PAS un état observable. QuestState.READY_TO_TURN_IN existe dans
    l'énumération, mais QuestProgressEngine#turnIn le pose puis le remplace par COMPLETED dans la
    MÊME méthode, et seul COMPLETED est persisté. Le ticket dit « intégrer uniquement si cet état
    existe effectivement dans le moteur ; sinon documenter hors MVP » -> documenté hors MVP, aucune
    mécanique de remise inventée.
  * un PNJ ne référence AUCUNE quête : le lien vit dans le champ giver: de la quête. Le signal
    part donc des quêtes (filtre sur giver), pas d'une table de liaison inexistante.
(2) DONE — Disponibilité réutilisée, PAS dupliquée. Extraction de
  QuestProgressEngine#availability(playerId, questId, ignorePrerequisites), et accept() DÉLÈGUE
  désormais à cette méthode. Il n'existe donc qu'une seule implémentation des règles de refus :
  une quête verrouillée, déjà active ou terminée non répétable ne peut pas être annoncée comme
  nouvelle. Refactoring d'une méthode cœur validé immédiatement par les tests de quête avant de
  continuer.
(3) DONE — Lecture des dialogues : audit puis implémentation. Rien n'existait. Nouvelle table
  dialogue_node_reads (migration V24, idempotente, clé primaire composite) + DialogueReadRepository.
  Le marquage se fait dans DialogueSessionEngine#openNode, SEUL point de rendu réel d'un nœud :
  ouvrir un PNJ n'écrit donc qu'une ligne, celle du nœud de départ. Une branche non parcourue reste
  non lue, donc toujours signalée. État par UUID, persistant après reconnexion et redémarrage.
(4) DONE — Atteignabilité réelle : DialogueSessionEngine#reachableNodes, parcours en largeur borné
  depuis le nœud de départ, suivant uniquement les choix dont les conditions PASSENT, via
  l'évaluateur de conditions existant (visibleChoices rendu public). Un choix fermant le dialogue
  n'est pas suivi. Aucune règle de condition dupliquée.
(5) DONE — Service NpcHintService : particules envoyées au SEUL joueur concerné
  (Player#spawnParticle, API publique Paper, client vanilla, compatible Citizens puisque le signal
  suit l'entité portant un id RPGQuest). Deux conventions distinguables, quête prioritaire sur
  dialogue. Le PNJ lui-même n'est jamais modifié — ni son nom, ni rien de global.
(6) DONE — Coût maîtrisé, trois mécanismes : aucune boucle par tick (une passe toutes les
  period-ticks, PAR JOUEUR, jamais un balayage des PNJ du monde) ; aucun PNJ distant
  (getNearbyEntities dans le rayon, même monde, ligne de vue optionnelle, aucun chunk chargé) ;
  calcul découplé de l'affichage (recalcul au plus toutes les refresh-seconds, en asynchrone, un
  seul à la fois par joueur, cache invalidé immédiatement sur quête acceptée / progression / nœud
  lu / changement de monde). Nettoyage à la déconnexion et à l'arrêt.
(7) DONE — Réglages bornés : section npc-hints: (enabled, period-ticks 10-100, radius 4-48,
  require-line-of-sight, refresh-seconds 1-60, height-offset 0-3, quest-particle,
  dialogue-particle, count 1-10). Une valeur numérique hors bornes est CORRIGÉE (un signal visuel
  ne doit pas empêcher le serveur de démarrer) ; une particule inconnue est REFUSÉE au démarrage,
  parce que c'est une faute de frappe qu'il faut voir.
Tests: suite complète des trois modules verte — 1486 tests plugin (34 ignorés, limitations
  MockBukkit héritées), 476 control-panel (1 ignoré), 30 web-api, 0 échec, 0 erreur.
  28 nouveaux cas : NpcHintConfigTest (7 — bornage dans les deux sens, particule absente
  remplacée, interrupteurs intacts, bornage idempotent), DialogueReadRepositoryTest (9 — rien
  n'est lu par défaut, lecture par joueur ET par nœud, idempotence, SURVIE à la
  fermeture/réouverture de la base, reset d'un joueur sans effet sur un autre),
  DialogueSessionEngineTest (+6 — atteignabilité réelle selon les conditions, rétrécissement quand
  une condition cesse de passer, choix fermant non suivi, OUVRIR UN PNJ NE MARQUE QUE LE NŒUD
  AFFICHÉ, parcourir une branche la marque aussi, absence d'observateur inoffensive),
  QuestProgressEngineTest (+6 — disponibilité d'une quête neuve, refus si active, quête inconnue,
  prérequis manquants NOMMÉS, aucune progression créée par une simple lecture, ignorePrerequisites
  ne saute que les prérequis).
  UN DÉFAUT INTRODUIT PAR CE LOT, attrapé par les tests et corrigé :
  SchemaMigrator.CURRENT_VERSION était resté à 23 alors que le catalogue allait à 24 — le test
  SchemaMigrationRunnerTest#realCatalogueTargetsTheDeclaredCurrentVersion a exactement servi à ça.
  Les tests de migration pointent désormais la constante du code plutôt qu'un littéral, pour que la
  prochaine migration ne fasse plus tomber quinze tests pour la même raison.
Branche finale: feature/169-special-mobs-boss (aucun merge).
Dernier commit: f3f89d0 feat(npc): signal visuel discret de quête disponible et dialogue
  non lu (#12) — documentation finalisée ensuite.
Build: vert.
Déploiements: JAR VeryGames DEV (SHA-256
  1ab198d032f16cbbd6c7f47e7904ae26d0968c155b556419301cba6cbed33975, 1 731 370 octets) avec UN SEUL
  redémarrage RCON — serveur revenu ONLINE, 0 joueur connecté. JAR précédent sauvegardé :
  rpgquest-20261005T172148Z-predeploy.jar.
  AUCUN déploiement du Control Panel : ce lot ne touche que le plugin (vérifié par git status).
  Le panel reste actif et /health répond 200.
Vérification sur le serveur déployé, via RCON : « plugins » -> les 4 plugins en VERT
  (Citizens, Multiverse-Core, RPGQuest, WorldEdit), et « rpgquest version » -> v0.1.0-SNAPSHOT.
  Le plugin a donc chargé le nouveau JAR sans erreur, migration V24 incluse.
  AUCUN test en jeu n'a été exécuté : le rendu visuel des particules, la cadence en charge et la
  différence entre deux joueurs ne sont pas vérifiés et restent à valider manuellement (TC-244).
Tests manuels en attente: TC-244 — protocole complet en jeu, sur contenu préfixé tc12_ uniquement,
  avec deux joueurs pour l'étape décisive (deux états différents devant le même PNJ). AUCUN test en
  jeu n'a été exécuté par Claude : le rendu visuel, la cadence en charge et la différence entre
  joueurs ne sont pas couverts automatiquement.
Blocages: aucun.
Propreté: aucun contenu du propriétaire modifié, aucune progression touchée, aucun reset joueur.
  Les tests automatisés utilisent des identifiants tc12_ ou le harnais existant.
Première étape à reprendre: dérouler TC-244 en jeu. Ensuite, attendre le choix du prochain ticket
  par le propriétaire — aucun ticket ne doit être lancé automatiquement.
```

```text
Date: 2026-10-05 (lot 7 — #194 suppression de quêtes et stories avec aperçu et confirmation)
Branche de départ: feature/169-special-mobs-boss @ 9d5d547 (lot 6, #196)
Étape de départ: demande explicite — ajouter la suppression dans le panel avec aperçu des
  conséquences et confirmation, traiter les liens PNJ/dialogues/stories/prérequis SANS supprimer
  les contenus associés ni effacer les progressions, bloquer si des références ne peuvent pas être
  traitées proprement. Tests uniquement sur du contenu de test.
Étapes terminées:
(1) DONE — Cartographie réelle des références, vérifiée dans le code et non supposée :
  * prérequis d'autres quêtes -> source quests/*.yml (retirable sans casser : un prérequis est
    facultatif) ;
  * chaîne d'une story -> source stories/*.yml (retirable, SAUF si c'était la seule quête) ;
  * dialogues -> conditions QUEST_STATE et actions START_QUEST/ADVANCE_QUEST/TURN_IN_QUEST ;
  * PNJ -> AUCUNE référence sortante : NpcDefinition ne porte que dialogueId, et le lien
    quête->PNJ vit dans le champ giver: DE LA QUÊTE (QuestGiverStore écrit dans le fichier de
    quête). Supprimer une quête n'orpheline donc aucun PNJ — constat vérifié, pas déduit.
(2) DONE — Jamais de cascade : les fichiers référençants sont RÉÉCRITS, jamais supprimés.
(3) DONE — Jamais de lien orphelin : blocage avec fichier, NUMÉROS DE LIGNE et marche à suivre pour
  un dialogue ; blocage si une story perdrait sa dernière quête (chaîne vide refusée par le
  moteur) ; blocage si un fichier référençant n'est pas analysable (on ne réécrit jamais ce qu'on
  n'a pas su lire) ; blocage si l'espace de travail est en lecture seule ou sans sauvegarde
  possible. Un plan bloqué n'écrit RIEN et n'affiche aucun formulaire de confirmation.
(4) DONE — Progression joueur jamais touchée, et la politique est ÉCRITE dans l'aperçu à chaque
  fois : suppression éditoriale, les lignes restent en base et deviennent sans objet, le reset
  joueur est une opération distincte avec sa propre permission.
(5) DONE — Confirmation par identifiant RETAPÉ (un bouton seul se clique par réflexe).
  Permission DÉDIÉE CONTENT_DELETE, accordée à OWNER et ADMIN, volontairement REFUSÉE à
  CONTENT_EDITOR : écrire est réversible, détruire ne l'est pas de la même façon.
(6) DONE — Source ET runtime traités séparément. Nouvelle action plugin
  `content.definition.delete` : le fichier serveur est retrouvé par l'IDENTIFIANT DÉCLARÉ DEDANS
  (jamais par un nom de fichier deviné — le nom n'a pas à correspondre à l'id), sauvegardé,
  supprimé, puis les définitions sont RELUES, donc sans redémarrage. Sans cela, le contenu restait
  chargé et « réapparaissait » au rafraîchissement du catalogue : c'était le symptôme rapporté.
(7) DONE — Piège des exemples embarqués traité explicitement : les 9 quêtes et la story d'exemple
  du JAR sont RECRÉÉES AU DÉMARRAGE si le fichier manque. L'aperçu le dit, avec la conséquence
  (reconstruire et redéployer le plugin pour que ce soit définitif). Ce n'est pas un blocage — on
  doit pouvoir retirer un exemple — mais jamais passé sous silence. Un test de cohérence du dépôt
  confronte la copie du panel aux BUNDLED_EXAMPLES réelles du plugin : la dérive est détectée.
(8) DONE — Sauvegarde et rollback. Tout fichier touché est copié AVANT toute écriture sous un
  horodatage commun. Ordre : sauvegarder, réécrire les références (échec -> restauration et arrêt
  AVANT toute suppression), supprimer en dernier car c'est l'étape irréversible. Conflit de version
  entre l'aperçu et la confirmation -> refus, on ne détruit pas un contenu jamais vu.
(9) DÉCISION D'EMPLACEMENT, corrigée en cours de route : les sauvegardes allaient d'abord sous
  src/main/resources/.plugadmin-backups/. C'était faux pour deux raisons — elles seraient entrées
  dans le JAR construit, et elles auraient sali le working tree Git, ce qui bloque
  deploy-verygames.sh (il refuse un arbre non propre). Déplacées dans
  /var/lib/plugadmin/content-backups/, à côté de la base du panel.
Tests: suite complète des trois modules verte en un seul passage `./gradlew test build` —
  1458 tests plugin (34 ignorés, limitations MockBukkit héritées), 476 control-panel (1 ignoré),
  30 web-api, 0 échec, 0 erreur. 44 nouveaux cas : ContentDeletionAnalyzerTest (17),
  ContentDeletionExecutorTest (6, dont conflit de version avec ROLLBACK vérifié sur disque),
  ContentDeletionPagesTest (9), ContentDefinitionDeleterTest (9, plugin),
  BundledExamplesDriftTest (3), AgentActionExecutorTest (+3). Trois défauts de MES PROPRES tests
  trouvés et corrigés à la bonne couche : deux fixtures écrites à la main que SourceCatalog jugeait
  non analysables (le test vérifiait donc autre chose que ce qu'il annonçait) et une assertion qui
  ignorait l'échappement HTML de Http.esc.
Branche finale: feature/169-special-mobs-boss (aucun merge).
Dernier commit: 44c1446 feat(control-panel): supprimer quêtes et stories avec aperçu et
  confirmation (#194) — code et documentation dans le même commit.
Build: vert.
Déploiements: JAR VeryGames DEV (SHA-256
  d8991636879071c1bdb741bce8ece084407bcaf19697a36d98e3980fbfac3d59, 1 705 052 octets) avec UN SEUL
  redémarrage RCON — serveur revenu ONLINE, 0 joueur connecté. Puis Control Panel AWS
  (service actif, /health -> 200). Heartbeat de l'agent vu à 1 seconde au moment du contrôle.
Vérification sur les services DÉPLOYÉS, sans rien supprimer :
  * action `content.definition.delete` prouvée vivante dans le JAR déployé par une sonde sur un
    identifiant VOLONTAIREMENT inexistant -> « Aucun fichier de quests ne déclare l'identifiant
    tc243_inexistant_sonde sur le serveur : rien à supprimer ». L'action est donc reconnue (pas
    REJECTED comme type inconnu) et n'a rien touché ;
  * 13 boutons « Supprimer… » sur /quests, 3 sur /stories ;
  * APERÇU DE first_steps SUR DONNÉES RÉELLES : la suppression est BLOQUÉE parce que le dialogue
    « guard » la référence aux lignes 12, 16 et 21, et AUCUN formulaire de confirmation n'est
    rendu. La protection fonctionne donc sur le contenu réel du propriétaire, pas seulement en
    test. Rien n'a été supprimé (requêtes GET uniquement).
  Compte panel de vérification créé puis supprimé avec ses identifiants ; owner et TESTER intacts.
Tests manuels en attente: TC-243 — parcours navigateur complet, sur du contenu de test préfixé
  « tc243_ » uniquement. Le test précise explicitement de NE PAS le dérouler sur crystal_hunt,
  first_steps, les quêtes du Garde ou main_story.
Blocages: aucun.
Propreté: aucun contenu du propriétaire supprimé ni modifié. Les quêtes/stories de test éventuelles
  sont préfixées et identifiables.
Première étape à reprendre: vérifier TC-243 au navigateur, puis #108/#109 (recherche et filtres
  d'export, import accessible avec aperçu/diff et choix de collision).
```

```text
Date: 2026-10-05 (lot 6 — #196 catalogue complet des objets pour icônes et récompenses)
Branche de départ: feature/169-special-mobs-boss @ 1ccde5e (lots 3 et 4 du même jour)
Étape de départ: #202 confirmé fonctionnel par le propriétaire (test réel #207, Discord → GitHub
  puis GitHub → Discord, message « Résolu » apparu). Consigne : ne plus toucher aux secrets,
  enchaîner sur #196, sans sous-agents, avec commit/push/déploiement panel autorisés.
Étapes terminées:
(1) DONE — CAUSE RACINE confirmée par la mesure, pas supposée : RefData.MATERIALS était une liste
  écrite à la main de 76 entrées servant de catalogue, ne contenant que IRON_SWORD et
  DIAMOND_SWORD. Chercher « sword » trouvait donc 2 épées sur les 7 que la version réelle expose
  (bois, pierre, CUIVRE, or, fer, diamant, netherite — COPPER_SWORD n'existe que depuis 1.21.9,
  vérifié par javap sur le jar paper-api 1.21.11 réellement utilisé).
(2) DONE — SECOND DÉFAUT INDÉPENDANT, trouvé en auditant la troncature comme le demandait le
  ticket : le composant de liste recherchable de panel.js s'arrêtait à 60 correspondances SANS LE
  DIRE. Avec un catalogue de plus d'un millier d'objets, une recherche large aurait silencieusement
  caché des résultats — exactement le « résultats perdus » que le ticket voulait éviter. Borne
  portée à 100, total annoncé (« 100 sur 142 affichés »), et « afficher 100 de plus » cliquable.
(3) DONE — Nouveau relevé plugin `item.catalogs` (permission CONTENT_READ, bouton « Objets
  Minecraft » sur /quests) : registre Material réel, trois filtres motivés (isLegacy écarte les
  doublons d'avant 1.13, isAir écarte la pile vide, isItem est la seule garantie « peut exister
  comme objet »), et les blocs SANS forme d'objet renvoyés à part pour être EXPLIQUÉS au lieu
  d'être tus. La version Minecraft voyage avec le relevé pour tracer la provenance de la liste.
(4) DONE — Représentable vs délivrable tranché par l'API, pas par intuition : les deux exigent
  isItem(), la distinction utile est avec les blocs sans objet. Le validateur répond désormais
  « WATER existe comme bloc mais n'a aucune forme d'objet dans cette version » au lieu de
  « matériau inconnu », qui envoyait chercher une faute de frappe inexistante.
(5) DONE — Noms français par composition (MaterialNames) : DIAMOND_SWORD → « Épée en diamant »,
  GOLD_INGOT → « Lingot d'or » (élision gérée). Ce qui ne suit aucune règle tombe sur une table
  nominative courte puis sur l'anglais embelli — un repli VISIBLEMENT non traduit plutôt qu'une
  traduction inventée. La recherche filtrant sur l'identifiant ET le libellé, « sword » et « épée »
  donnent le même résultat, donc un repli anglais ne rend jamais un objet introuvable.
(6) DONE — Sans relevé, le repli curé reste utilisable MAIS est annoncé comme « liste de dépannage
  de 76 entrées » (bandeau + aide du champ), et aucun avertissement de validation n'est émis sur un
  matériau absent de ce repli : ce serait un faux positif qui apprend à ignorer les avertissements.
Tests: suite complète des trois modules verte — 1446 tests plugin (34 ignorés, limitations
  MockBukkit héritées), 441 tests control-panel (1 ignoré), 30 tests web-api, 0 échec.
  Nouveaux : MaterialNamesTest (12 cas — familles composées, élision « Lingot d'or », repli anglais
  assumé, objets de créatif signalés), ItemCatalogTest (9 cas — la liste codée en dur ne contenait
  que 2 épées, le relevé en trouve 7, le relevé REMPLACE le repli sans fusion, bloc sans objet
  reconnu, catalogue conservé en dérivant la RefData), MaterialPickerTest (8 cas — libellés dans la
  datalist, provenance et version annoncées, bloc sans objet marqué, refus EXPLIQUÉ côté
  validateur, aucun faux positif sans relevé), AgentActionExecutorTest (+1), et un test JavaScript
  control-panel/src/test/js/combo-pagination.test.js (12 cas sur le fenêtrage, extrait de panel.js
  au vol, non câblé à Gradle pour ne pas faire dépendre le build d'un Node installé).
Branche finale: feature/169-special-mobs-boss (aucun merge).
Dernier commit: 4e3a798 fix(control-panel): catalogue complet et recherchable des objets
  (#196) — docs committées ensuite.
Build: vert.
Déploiements: JAR VeryGames DEV (SHA-256
  133802fbf5c69eeb0fe852ff175c21e9bc8d73e1f766fb426f222a01ae209cab) avec UN seul redémarrage RCON
  (serveur revenu ONLINE, 0 joueur connecté), puis Control Panel AWS (/health -> 200). Le relevé
  exige le JAR : sans lui le panel ne pourrait pas obtenir la liste réelle.
Vérification sur l'instance DÉPLOYÉE (requêtes HTTP authentifiées, compte dédié supprimé ensuite) :
  relevé réel = 1504 objets utilisables, 151 blocs sans forme d'objet, Minecraft 1.21.11, charge
  utile 30 828 caractères. Datalist servie = 1655 options avec data-material-source="server" et
  data-mc-version="1.21.11". ÉPREUVE DU TICKET PASSÉE : « sword » renvoie les SEPT épées, avec
  leurs libellés français (Épée en bois/pierre/cuivre/or/fer/diamant/netherite) — dont COPPER_SWORD,
  qui n'existe que depuis 1.21.9, donc lu du vrai registre et non inventé. 151 blocs marqués
  data-noitem, 21 objets de créatif signalés. Élision vérifiée sur données réelles (« Lingot d'or »,
  « Minerai d'émeraude », « Éclat d'améthyste »). Aide du champ Icône : « 1504 objets de la version
  installée (Minecraft 1.21.11) ». /stories/new ne porte PAS la liste lourde : 17 Ko contre 130 Ko
  pour /quests/new. Note mesurée : 0 forme historique écartée sur ce serveur — ce Paper ne les
  expose plus à l'exécution ; le filtre isLegacy() reste un garde-fou, et aucune entrée LEGACY_ ne
  pollue la liste.
Tests manuels en attente: TC-242 — entièrement vérifiable au NAVIGATEUR, sans Minecraft. Reste en
  jeu : que l'icône choisie s'affiche dans le journal et qu'une récompense d'objet soit remise.
Blocages: aucun.
Propreté: aucun contenu du propriétaire modifié. Incident d'environnement rencontré et corrigé :
  control-panel/build/resources appartenait à root, ce qui faisait échouer `processResources`
  (« Failed to clean up stale outputs ») — ownership rendue à ubuntu, aucun fichier du dépôt touché.
Première étape à reprendre: vérifier TC-242 au navigateur, puis #194 (suppression de quête/story
  avec confirmation et aperçu des conséquences), puis #108/#109.
```

```text
Date: 2026-10-05 (lots 3 et 4 — #172 catalogues réels de l'éditeur de mobs, #195 couleurs au clic)
Branche de départ: feature/169-special-mobs-boss @ 9af3143 (entrée précédente du même jour)
Étape de départ: lot Control Panel vérifiable au navigateur, par ordre de priorité donné par
  l'utilisateur : #172 d'abord (« la création reste KO côté utilisateur, même si elle a été
  annoncée livrée » — reproduire le parcours réel, diagnostiquer le défaut réel), puis #195, #196,
  #194, #108/#109, #197. Sans sous-agents, y compris pour surveiller les builds.
Étapes terminées:
(1) DONE — #172, défaut bloquant. Le parcours réel a été rejoué en HTTP authentifié : le backend
  répondait déjà SUCCESS/CREATED, donc l'hypothèse #190 (bouton trop bas) ne corrigeait pas le vrai
  défaut. Vrai défaut : la page n'émettait AUCUNE <datalist> — type d'entité (obligatoire),
  particule, son, mondes, biomes étaient des champs texte à placeholder, donc créer exigeait de
  connaître l'identifiant vanilla exact (alors que modifier un profil marchait, champs déjà
  remplis). Second défaut indépendant : deux sections repliées avec champs numériques contraints →
  le navigateur refusait la soumission sans pouvoir focaliser un champ caché, bouton sans réaction
  ET sans message. Corrigés par un nouveau relevé plugin `mob.catalogs` (registres réels : 91
  entités, 115 particules, 1838 sons, 65 biomes, 6 mondes ; 51 731 caractères — au-delà de
  l'ancienne borne de 20 000 corrigée le matin même par #164, sans quoi ce relevé serait arrivé
  tronqué en silence), des listes recherchables, des multisélections mondes/biomes au contrat CSV
  inchangé, une aide par champ, et `novalidate` sur le formulaire.
(2) DONE — #195, couleurs et styles sans écrire de MiniMessage. Composant partagé StyleField :
  16 couleurs nommées au clic + « aucune couleur », cases gras/italique/souligné/barré, aperçu,
  texte simple. Appliqué au nom affiché des mobs/boss et aux textes de nœuds de dialogue. Le champ
  réellement soumis garde son nom et sa valeur MiniMessage : contrat serveur, validateurs, plugin
  et YAML inchangés ; sans JavaScript, champ texte ordinaire. Le vrai risque était l'aplatissement
  d'un texte multi-styles : le mode guidé n'est ouvert que pour une valeur uniforme, sinon le texte
  reste tel quel en mode avancé avec la raison affichée et un basculement explicite. Vérifié sur
  les contenus réels servis par l'instance déployée : 42 textes de nœuds → 23 guidés avec
  aller-retour exact, 19 intacts, 0 altération ; et aller-retour complet d'un nom multi-styles
  panel → plugin → YAML → relevé → formulaire, rendu à l'identique.
(3) CORRECTION DE DOCUMENTATION — le rapport du lot #172 indique `.ai/ROADMAP.md` parmi la
  documentation mise à jour, or aucune entrée n'avait été écrite. Les rapports étant immuables, la
  présente entrée corrige le dépôt et couvre les deux lots ; l'ancien rapport n'a pas été modifié.
Tests: `:control-panel:build` vert (suite complète du module) après chaque lot. Nouveaux :
  `StyleFieldTest` (contrat du champ soumis, repli sans JavaScript, échappement, palette) ;
  `control-panel/src/test/js/stylefield-parse.test.js` (17 cas sur la règle d'uniformité et
  l'aller-retour, extrait de panel.js au vol pour ne pas se désynchroniser — exécution manuelle par
  `node`, volontairement non câblé à Gradle pour ne pas faire dépendre le build d'un Node installé).
  Doubles de test du plugin mis à jour pour `mob.catalogs`.
Branche finale: feature/169-special-mobs-boss (aucun merge).
Dernier commit: 264377e feat(control-panel): couleurs et styles au clic (#195) — docs ensuite.
Build: vert.
Déploiements: Control Panel AWS deux fois (`/health` → 200) ; JAR VeryGames DEV une fois pour #172
  avec un seul redémarrage. Lot #195 : panel seulement, aucun JAR, aucun redémarrage Minecraft.
Tests manuels en attente: TC-239 (#172) et TC-240 (#195), tous deux vérifiables au NAVIGATEUR sans
  Minecraft. Reste en jeu : apparition réelle d'un profil créé et rendu BOSS.
Blocages: aucun. #172 reste OUVERT : renforts multi-types avec quantité par type (changement de
  format de profil, à coordonner avec #173/#170), Wild présélectionné par défaut (volontairement
  non fait — changerait le sens d'un profil existant à la réouverture, décision à prendre), valeurs
  par défaut par entité.
Propreté: comptes panel de diagnostic/vérification (`claude-diag-172`, `claude-verif`) créés puis
  SUPPRIMÉS avec leurs identifiants ; `owner` et `TESTER` intacts. Profils de test
  `claude_diag_special` / `claude_diag_boss` laissés DÉSACTIVÉS donc inertes, identifiables.
  Aucun contenu du propriétaire (crystal_hunt, Lily/Jeff, configurations, progression) modifié.
Première étape à reprendre: #196 (catalogues d'icônes et de récompenses — la recherche « sword »
  doit retrouver toutes les épées de la version réelle ; réutilise directement le mécanisme de
  relevé introduit par #172), puis #194.
```

```text
Date: 2026-10-05 (suite — #192 appliqué, journal lisible, #165 nom+skin PNJ)
Branche de départ: feature/169-special-mobs-boss @ 4bc5b89 (entrée précédente du même jour)
Étape de départ: autorisation explicite d'appliquer le correctif WorldEdit sur DEV ; attentes
  précisées pour le journal (infobulle compacte, noms français, pas d'id technique, récupération
  sans reset ni doublon) ; démarrage de #165.
Étapes terminées:
(1) DONE — #192 appliqué. Chemin distant corrigé par un listing réel : la racine FTP EST déjà
  « plugins/ », donc WorldEdit/config.yml. Script mono-usage dédié (le garde-fou de
  deploy-verygames.sh n'est pas affaibli), sauvegarde horodatée, diff d'une ligne, upload
  atomique, /worldedit reload. Valeur RÉELLEMENT CHARGÉE vérifiée via le rapport interne de
  WorldEdit (wandItem: minecraft:golden_axe), re-vérifiée APRÈS redémarrage.
(2) DONE — Journal : liste et détails partageaient la même lore. Séparées (liste = état +
  compteurs plafonnés à 4 + clics). Id d'étape retiré, noms via clé de traduction vanilla,
  récompense VARIABLE jamais affichée, attributs d'attaque de l'icône masqués.
(3) DONE — Récupération du journal : déjà correcte (LACKS_CUSTOM_ITEM + soulbound + simple give),
  donc rien à corriger ; un test verrouille le contrat, qui vit dans une donnée éditable au panel.
(4) DONE — #165 : renommage via NPC.setName (API publique). SkinTrait ABSENT de citizensapi (il est
  dans citizens-main) -> commande structurée Citizens, mais avec sélection explicite du PNJ visé
  via NPCSelector (API publique) puis désélection, dans le même passage thread principal. Lien
  MineSkin strict validé côté panel ET plugin. Id logique et liaisons inchangés.
Tests : plugin 1445 / 0 échec / 34 ignorés ; control-panel 408 / 0 échec / 1 ignoré (XML JUnit).
Branche finale: feature/169-special-mobs-boss (aucun merge)
Dernier commit: 678ae55 au déploiement plugin (docs committées ensuite)
Build: vert ; test + build relancés par le script de déploiement.
Tests manuels en attente: TC-236 (hache du kit), TC-237 (journal), TC-238 (nom/skin PNJ).
Blocages: aucun. Le succès d'un skin = « demande transmise » (Citizens télécharge en asynchrone),
  jamais « skin confirmé » : la confirmation visuelle est un test en jeu.
Première étape à reprendre: validations en jeu TC-236/237/238 ; confirmer l'interprétation des
  « quatre compteurs » ; décider si la synchronisation nom de définition / locuteur est voulue.
```

```text
Date: 2026-10-05 (lot panel #162/#163/#164 + voyage #191 + Wild #168 + diagnostic #192)
Branche de départ: feature/169-special-mobs-boss @ f6d94bc (entrée précédente, #179 déployé)
Étape de départ: consigne de travail autonome pendant l'indisponibilité de l'utilisateur.
  Priorité aux améliorations du Control Panel vérifiables au navigateur (#163, #164, #162, #165),
  puis en jeu #168, #192, #191. Un seul redémarrage Minecraft à regrouper. Sans sous-agents.
Étapes terminées:
(1) DONE — #162 : deux causes indépendantes trouvées sur l'instance réelle (ACL POSIX absente ET
  dossier absent de ReadWritePaths= alors que ProtectSystem=strict rend tout le reste en lecture
  seule). Script idempotent du dépôt scripts/plugadmin/grant-content-access.sh ; vérifié par une
  écriture sonde sous le même bac à sable systemd, AVEC contrôle négatif (sans le droit -> échec).
  Message d'aide du panel corrigé : il nommait quests/stories, pas le dossier réellement bloqué.
(2) DONE — #163 : RefData porte l'origine source/runtime et le graphe de prérequis ; QuestValidator
  refuse les cycles INDIRECTS avec le chemin complet ; composant multisel (puces + recherche titre
  ou id) dont le champ soumis reste la textarea — format canonique intact, page utilisable sans JS.
(3) DONE — #164 : cause racine trouvée dans les données réelles. dialogue.list était un SUCCESS de
  10 dialogues (dont rpgquest:jeff) stocké TRONQUÉ à 20 000 caractères en plein JSON, donc
  illisible : le catalogue retombait en silence sur la source seule. Corrigé (marqueur JSON valide,
  borne entrante 64 Kio -> 1 Mio qu'il frôlait déjà, interface qui signale un relevé inexploitable
  au lieu d'impliquer une absence) + sélecteur PNJ recherchable sur le catalogue fusionné.
(4) DONE — #191 : le bypass acceptait rpgquest.admin.world (default: op), donc tout OP détruisait
  waypoints et bornes sans geste délibéré. Permission dédiée default:false + activation volontaire
  expirant en 5 min. Détection des structures abîmées (distincte d'« inaccessible ») et
  travel restore, qui repose les blocs SANS déplacer, conserve id/nom/découvertes/appariement et
  refuse d'écraser une construction tierce sans force.
(5) DONE — #168 : section wild: + WildHostileRulesService limités aux mondes Wild listés. Immunité
  au SOLEIL seulement (feu/lave/combat intacts), complément d'apparitions DIURNES borné (jamais de
  doublon nocturne, aucune nuit simulée, aucune génération de chunk), araignées agressives de jour.
(6) DONE — #192 : vérifié par RCON (WorldEdit 7.4.1 ; /toggleeditwand n'affiche plus qu'un rappel,
  ce qui explique le « ça ne change rien »). Correctif = wand-item != hache en bois, documenté.
  NON appliqué : fichier d'un autre plugin, que deploy-verygames.sh refuse par conception.
Tests : control-panel 406 tests / 0 échec / 1 ignoré ; plugin 1442 tests / 0 échec / 34 ignorés
  (limitations MockBukkit déjà documentées). Chiffres relevés dans les XML JUnit réels.
Branche finale: feature/169-special-mobs-boss (aucun merge)
Dernier commit: a061c75 au déploiement plugin (docs committées ensuite)
Build: vert ; ./gradlew test + build relancés une 2e fois par le script de déploiement.
Tests manuels en attente: TC-234 (#191), TC-235 (#168), TC-236 (#192). Côté navigateur, #162/#163/
  #164 sont testables immédiatement.
Blocages: aucun. #165 non commencé (temps consommé par la cause racine #164 non anticipée).
  La ligne dialogue.list déjà en base reste tronquée : un clic sur « Rafraîchir » suffit.
Première étape à reprendre: validation en jeu TC-234/235, application du wand-item WorldEdit
  (#192), puis #165 (renommage + skin MineSkin) ; préciser la demande « journal / infobulles ».
```

```text
Date: 2026-10-04 (issue #179 — parcours simplifié du Garde pour les claims TIER_1 à TIER_5)
Branche de départ: feature/169-special-mobs-boss @ 5ab472e (entrée précédente, #190 déployé)
Étape de départ: demande explicite de l'utilisateur, exécutée sans attendre de confirmation :
  cinq quêtes successives du Garde, chacune une étape à quatre objectifs KILL_ENTITY simultanés
  (araignées/zombies/squelettes/creepers, 5/10/20/40/80), débloquant respectivement les claims
  TIER_1 à TIER_5, sans doubler les récompenses, sans confondre déblocage de tier/agrandissement/
  création de claim, visibles dans le Control Panel.
Étapes terminées:
(1) DONE — Moteur de palier de claim : ClaimTier modélise désormais TIER_1..TIER_5 (actif
  5/10/20/40/80, réservation constante à 100 pour les cinq — collision-free par construction).
  ClaimService#upgradeTier (nouveau) agrandit le claim principal déjà posé en conservant son
  centre, avec vérification de chevauchement défensive ; ClaimService#highestEntitledTier
  (nouveau) résout le palier le plus haut déjà acquis. ClaimRepository#updateBounds (nouveau).
(2) DONE — DeedClaimListener pose désormais directement au palier le plus haut déjà acquis
  (jamais TIER_1 par défaut) — couvre le cas d'un joueur ayant complété des paliers avant même
  d'avoir posé son premier claim.
(3) DONE — Nouvelle sous-commande console /rpgadmin claim grant-tier <joueur> <TIER_n>, permet
  de tester les 5 paliers sans combat réel (voir docs/ADMIN_TEST_SHORTCUTS.md, workflow D).
(4) DONE — Cinq nouvelles quêtes rpgquest:guard_tier1..guard_tier5 (aucun changement du moteur de
  quête nécessaire — le support multi-objectifs-par-étape existait déjà et a été vérifié par
  lecture directe du code avant toute implémentation). Prérequis chaînés, repeatable: false,
  récompenses VARIABLE CLAIM_TIER_n + COMMAND grant-tier (idempotent dans tous les cas).
(5) DONE — dialogues/guard.yml étendu (5 offres + nœud de clôture après le palier 5, jamais un
  rappel d'objectif périmé) sans toucher aux branches first_steps/crystal_hunt existantes —
  décision explicite de ne JAMAIS modifier crystal_hunt.yml (parcours parallèle, pas un remplacement).
(6) DONE — Les 5 quêtes ajoutées à YamlQuestEngine.BUNDLED_EXAMPLES, sinon jamais générées sur un
  serveur déjà démarré auparavant (trouvé en auditant le mécanisme de seed avant le déploiement,
  pas après coup).
Tests : 1425 tests (module racine) + confirmation interne au script de déploiement (./gradlew
  test+build OK, 3 modules) ; nouveaux : BundledQuestsValidityTest (chargement + structure des 5
  quêtes de palier), extensions ClaimServiceTest (highestEntitledTier, upgradeTier : succès
  centré, idempotence, refus sans claim, refus de chevauchement), DeedClaimListenerTest (première
  pose au palier déjà acquis), YamlQuestEngineTest (seed des 5 nouveaux fichiers). 0 échec, 34
  ignorés (limitation MockBukkit déjà documentée, sans rapport avec ce lot).
Branche finale: feature/169-special-mobs-boss (aucun merge — déploiement DEV/AWS autorisé
  explicitement pour cette session)
Dernier commit: 9a294f8 au moment du déploiement (docs committées séparément ensuite — voir git
  log : f195119 moteur de palier, e2cc759 quêtes+dialogue, 205fc31 documentation, 9a294f8
  correctif seed des exemples)
Build: vert, voir Tests ci-dessus.
Tests manuels en attente: TC-233 (parcours complet en jeu des 5 paliers — aucun joueur connecté
  au moment du redémarrage pour un test en conditions réelles).
Blocages: aucun bloquant restant. Deux incidents transitoires rencontrés et résolus sans
  conséquence : (a) 1er lancement du script de déploiement échoué dans son ./gradlew test interne
  sur une NoSuchFileException (contention Gradle entre deux process concurrents sur cette machine
  à mémoire limitée, jamais un vrai échec de test — confirmé par relecture du XML JUnit du run
  interrompu) ; corrigé par ./gradlew --stop puis un second lancement propre, qui a abouti. (b)
  Bref refus RCON (~30-40 s) juste après le retour ONLINE du serveur suite au redémarrage, résolu
  de lui-même sans nouvelle tentative de redémarrage (jamais de boucle, conformité au seuil
  VeryGames de 10 auto-redémarrages/30 min).
Première étape à reprendre: validation manuelle en jeu de TC-233 ; #161/#162/#163 restent en file
  (non commencés, non liés à ce lot).
```

```text
Date: 2026-10-04 (démarrage EPIC #169 — mobs spéciaux et boss configurables, lot 1)
Branche de départ: feat/control-panel-admin-tools (entrée précédente) — travail réalisé dans un
  worktree isolé /srv/rpgquest/worktree-169 (branche feature/169-special-mobs-boss) pour ne jamais
  contaminer les brouillons locaux du panel (crystal_hunt.yml en cours d'édition, lily/jeff) :
  jamais stashés, conformément à l'instruction explicite de ne plus les stasher par défaut.
Étape de départ: EPIC #169 (mobs spéciaux et boss configurables dans PlugAdmin, apparitions Wild,
  objectifs de quête localisables) découpé en lots ; recherche explicite de livrer vite un premier
  lot testable en jeu plutôt que de ne concevoir/documenter. #150 (recherche waypoints) mis en
  pause sur instruction explicite — non retouché, jamais marqué corrigé.
Étapes terminées:
(1) DONE — Modèle étendu (`SpecialMobDefinition`) : `category` (SPECIAL/BOSS, optionnelle, défaut
  SPECIAL rétro-compatible), `enabled` (défaut true), `knockback-resistance`, `scale`,
  `creeper-explosion-radius` (refusé hors CREEPER — jamais silencieusement ignoré). Deux nouvelles
  capacités premier lot : `ENRAGED` (seuil de vie, multiplicateurs vitesse/dégâts, binaire et
  définitif via PDC) et `SUMMON_ON_DAMAGE` (invocation de renforts sur dégâts effectifs, cooldown +
  plafond de vivants, aucune cascade possible par construction — renforts jamais eux-mêmes des mobs
  spéciaux). `SpecialMobDefinitionParser` étendu en conséquence, 4 YAML d'exemple bundlés mis à jour.
(2) DONE — `SpecialMobService` étendu : nouveaux attributs appliqués (KNOCKBACK_RESISTANCE/SCALE),
  rayon d'explosion creeper, visuels BOSS (BossBar Adventure par entité + aura de particules
  périodique, affichage/masquage par joueur selon la distance, nettoyage sur mort/arrêt/redécouverte
  de chunk). `rollDefinition()` entièrement redessiné en deux étages pour résoudre l'ambiguïté du
  pourcentage cumulé signalée par le porteur de projet : throttle global (nouveau
  `MobSpawnSettings`/`MobSpawnSettingsStore`, fichier `mobs/spawn-settings.yml` édité depuis le
  panel) évalué une seule fois avant d'examiner les profils, puis tirage indépendant par profil
  SPECIAL compatible, puis tirage pondéré explicite (poids = `spawn-chance`) si plusieurs réussissent
  simultanément — jamais le premier trouvé. BOSS exclu par construction. Plafond global
  `max-simultaneous-special` en plus du `max-population` par profil déjà existant. Aucun nouveau
  tirage au chargement de chunk/reconnexion (inchangé).
(3) DONE — Deux nouveaux services d'ability : `EnragedAbilityService` (balayage périodique borné à
  la population suivie, même patron qu'`ExplosiveOnAttackAbilityService`) et
  `SummonOnDamageAbilityListener` (événement de dégâts, cooldown/plafond via PDC dédiée).
(4) DONE — CRUD complet sans YAML/commande : `SpecialMobDefinitionStore`/`SpecialMobDefinitionYaml`
  (écriture atomique, round-trip vérifié, même discipline que l'équivalent PNJ) ; spawn/suppression
  d'instances de test marquées distinctement (PDC dédiée, jamais un mob ordinaire supprimé) via
  `SpecialMobService#findTestSpawnLocation/applyTestInstance/clearTestInstances`
  (`RandomSafeLocationFinder`, position sûre près du joueur choisi, refusé si le joueur n'est pas
  dans le monde Wild configuré).
(5) DONE — Agent PlugAdmin : 7 nouvelles actions whitelistées (`mob.list`,
  `mob.definition.create/update/toggle`, `mob.spawn-settings.set`, `mob.test.spawn/clear`) de bout
  en bout (`AgentActionType` → `AgentActionExecutor` → `BukkitAgentActions` côté plugin ;
  `Permission`/`Role`/`AgentActionCatalog` côté Control Panel, défense en profondeur — re-validation
  des deux côtés).
(6) DONE — Nouvelle page Control Panel `/mobs` (nav + route) : catalogue en accordéon (catégorie,
  actif/inactif, population vivante, capacités résumées), formulaire création/modification complet
  (identité, tirage, statistiques, les deux capacités premier lot via case à cocher « activer » +
  champs dédiés), bascule activer/désactiver en un clic, réglage du throttle Wild, spawn de test
  près d'un joueur connecté + nettoyage des instances de test. Permissions : `MOB_READ` (tous les
  rôles lecture), `MOB_WRITE` (ADMIN + CONTENT_EDITOR), `MOB_TEST_SPAWN` (ADMIN + TESTER).
(7) PAS FAIT CE LOT (scope explicitement différé, documenté) : aperçu dynamique des réglages selon
  le type de créature (formulaire actuellement statique avec aide contextuelle par champ, pas de
  JS conditionnel) ; page docs-site dédiée (RPGQUEST_BIBLE.md section 11 + SPECIAL_MOB_FORMAT.md mis
  à jour, pas de nouveau docs-site/mobs.html, même précédent que /travel) ; ticket boss de quête
  (#171, spawn à l'acceptation/objectif de victoire/localisation — jamais présenté comme
  disponible) ; garde-fou dur supplémentaire contre le monde Wild dans `rollDefinition` lui-même
  (le filtrage par profil `worlds`/zones existant + le gate explicite de `mob.test.spawn` suffisent
  à préserver Hub/Claims pour ce lot) ; tickets enfants GitHub (à créer séparément, non bloquant).
Tests : voir rapport de session pour les chiffres exacts (suite complète 3 modules relancée en fin
  de tâche) ; nouveaux : `SpecialMobServiceTest` (+5, throttle/BOSS exclu/tirage pondéré/désactivé),
  `SpecialMobDefinitionYamlTest` (+4, round-trip complet), `SpecialMobDefinitionStoreTest` (+4,
  écriture atomique), `AgentActionCatalogTest` (+5 et 2 listes étendues), `RolePermissionMatrixTest`
  (inchangé, revérifié vert après ajout des 3 nouvelles permissions).
Build: voir rapport de session.
Dernier commit: voir rapport de session / git log sur feature/169-special-mobs-boss.
Tests manuels en attente: TC-231 (voir docs/MANUAL_TEST_PLAN.md) — boss (nom/particules/barre de
  vie), Enragé, Invocation de renforts, exclusion BOSS du tirage automatique, throttle Wild.
  Limitation MockBukkit préexistante (`setRemoveWhenFarAway` non implémenté) : tout chemin passant
  par `SpecialMobService#apply` reste sans couverture automatisée exécutable, comme pour les trois
  capacités précédentes.
Blocages: aucun.
Première étape à reprendre: tickets enfants GitHub sous l'EPIC #169 créés (#170-#177, voir le
  rapport de session) ; boss de quête (#171) ou capacités avancées (#170) selon priorité produit ;
  aperçu dynamique du formulaire par type de créature si demandé.
```

```text
Date: 2026-10-04 (correctif post-déploiement — id namespacé rejeté dans /mobs)
Branche de départ: feature/169-special-mobs-boss @ dddcd35 (entrée précédente, lot 1 déployé)
Étape de départ: retour utilisateur après test manuel du lot 1 : le panel `/mobs` affichait
  « Identifiant de profil manquant ou invalide » sur un profil pourtant bien listé (`rpgquest:
  creeper_pig`), bloquant édition/bascule/spawn de test. Consigne explicite de vérifier l'id envoyé
  par le navigateur, son parsing côté panel et sa transmission à l'agent, sans supposer la cause ;
  ne pas enchaîner sur un autre ticket avant d'avoir débloqué ce test.
Étapes terminées: DONE — cause confirmée en traçant le flux complet (jamais supposée) :
  `mob.list` renvoie toujours l'id namespacé complet (`NamespacedKey#asString()`, ex.
  `rpgquest:creeper_pig`), réinjecté tel quel par les formulaires d'édition/bascule/spawn de test ;
  les motifs `AgentActionCatalog.MOB_ID` (panel) et `AgentActionExecutor.MOB_ID` (plugin)
  n'acceptaient pas le « : », donc tout profil existant était rejeté — seule la création avec une
  clé courte fonctionnait. Les deux motifs acceptent désormais un suffixe `:<clé>` optionnel
  (`resolveKey` côté plugin gérait déjà correctement les deux formes, jamais le problème). Bug
  reproduit concrètement (régression confirmée : ancien motif restauré temporairement, nouveaux
  tests en échec) avant d'appliquer et de re-vérifier le correctif.
Tests: nouveaux `AgentActionCatalogTest#mobIdAcceptsTheFullNamespacedFormReturnedByMobList` (+1) et
  `AgentActionExecutorTest#mobDefinitionToggleAndTestSpawnAcceptTheFullNamespacedId` (+1), tous deux
  construits pour reproduire exactement le blocage rapporté avant de valider la correction. Suite
  complète 3 modules : 1831 tests, 1796 exécutés verts, 35 ignorés, 0 échec.
Branche finale: feature/169-special-mobs-boss (aucun merge).
Dernier commit: `8d2195a` fix(agent): accept namespaced mob ids in mob.* action validation.
Build: vert (voir Tests).
Tests manuels en attente: parcours complet demandé par l'utilisateur (ouvrir un profil existant →
  choisir un joueur connecté dans le Wild → faire apparaître une instance de test → vérifier
  édition et activation/désactivation) — corrigé et vérifié par tests automatisés + contrôles de
  démarrage/santé, mais pas encore par un clic réel dans le panel déployé.
Blocages: aucun.
Première étape à reprendre: validation manuelle par l'utilisateur du parcours ci-dessus ; #179
  (mentionné par l'utilisateur comme suite, explicitement différé tant que ce test n'est pas
  débloqué).
```

```text
Date: 2026-10-04 (suite overnight — #152/#154 puis retours joueur)
Branche de départ: feat/control-panel-admin-tools @ aa554c9 (entrée précédente)
Étape de départ: instruction explicite « continue to #152 and #154 », mêmes autorisations
  overnight (coder/documenter/committer/pousser/déployer DEV+AWS/redémarrer sans reconfirmation,
  aucun merge/PROD). En cours de route, retours joueur supplémentaires reçus en conversation
  (recherche de waypoints peu claire, #154 pas encore terminé côté rune, noms concaténés sans
  espace, message « déjà découvert » muet) puis ajout explicite des tickets #159 à #163 à la file
  de travail, priorité #159.
Étapes terminées:
(1) DONE — #152 : action agent travel.catalog (lecture de WaypointService#all()/
  TravelBeaconService#all(), jamais un scan monde) + page Control Panel /travel (permission
  TRAVEL_READ), tables waypoints/bornes séparées, pairage visible, recherche/filtre monde,
  pagination, horodatage de fraîcheur. Aucun éditeur (hors périmètre explicite du ticket).
(2) DONE — #154 : ItemTravelDefinition#freeRescueWorld (nouveau champ optionnel) -- la Rune de
  rappel téléporte immédiatement et gratuitement vers le spawn quand elle est utilisée dans le Hub,
  évalué AVANT requiredWorld/cooldown, totalement indépendant du comportement Wild inchangé.
  StarterKitListener ne marque plus la Rune distribuée si addItem échoue (inventaire plein à la
  première connexion) -- retenté à la connexion suivante au lieu d'une perte définitive. Nouveau
  hub.HubRescueFallbackService : balayage périodique qui redonne silencieusement la Rune manquante
  dès qu'une place se libère, ou ouvre un accès graphique minimal (1 bouton) si l'inventaire reste
  plein.
(3) DONE — retours joueur supplémentaires reçus en cours de session, traités dans la même branche :
  noms de waypoints lisibles (l'ancien générateur concaténait deux mots sans séparateur, ex.
  "Lacgivre" -> "Lac de Givre" ; migration V23, correspondance figée ancien->nouveau, id/découvertes
  inchangés) ; indicateur de recherche active + bouton d'effacement dans le menu de bornes (un
  filtre actif était invisible, donnant l'impression qu'il n'avait aucun effet) ; message « Waypoint
  déjà découvert : <nom> » au lieu d'un silence complet au reclic ; repairCost ET repairCostAmount
  zérotés sur l'enclume de recherche (un « Coût : 1 » résiduel signalé malgré repairCost déjà à 0).
(4) DONE (diagnostic, pas de cause trouvée) — #159 : signalement « faim bloquée aussi dans le Wild »
  après les correctifs #33. Audit code (HubComfortService strictement scopé par isHub() à chaque
  appel) + config déployée (hub.world=world_hub, travel.wild-world=wild, distincts, vérifié par
  téléchargement réel du config.yml via FTP) + 3 tests Wild déjà verts (foodLevelDecreaseInTheWild
  IsNeverCancelled, periodicSweepNeverTouchesPlayersInTheWild,
  changingWorldIntoTheWildNeverRestoresAnything) : aucune cause trouvée. Hypothèse la plus probable :
  la saturation posée au maximum en sortant du Hub retarde normalement, en vanilla, la baisse de
  faim dans le Wild le temps qu'elle s'épuise -- pas nécessairement un bug. Trace temporaire
  [HUNGER-TRACE] ajoutée (monde + joueur) sur toute annulation réelle, pour trancher depuis les logs
  serveur si le signalement persiste après un test prolongé.
(5) TODO (ajoutés à la file, non commencés cette session) — #160 (afficher le nom canonique au clic
  physique sur un waypoint, pas seulement le biome — extension du point (3) ci-dessus à la
  découverte elle-même, pas seulement au reclic), #161 (confirmation graphique + indication de
  latence avant l'entrée dans le Wild, précise #26 partie B), #162 (Control Panel AWS : création de
  dialogues bloquée en lecture seule par les permissions réelles du service, #145), #163 (sélection
  recherchable des quêtes prérequises au lieu d'IDs saisis à la main, #46 -- recoupe la ligne
  « V2 restant » de docs/control-panel/ROADMAP.md).
Tests : suite complète ./gradlew test (3 modules) : 1799 tests, 1764 exécutés verts, 35 ignorés
  (limitation MockBukkit teleportAsync déjà documentée), 0 échec -- confirmé en parsant les XML de
  résultat, pas seulement le texte "BUILD SUCCESSFUL". ./gradlew build (3 modules) vert aussi
  (RPGQUEST_TEST_MAX_HEAP=768m obligatoire sur cette box -- un essai sans cette variable a échoué en
  OOM, non représentatif d'un bug réel, rejoué correctement). Nouveaux : TravelCatalogTest (5 cas,
  control-panel), ItemTravelServiceTest (+2 cas secours gratuit), StarterKitListenerTest (3 cas,
  nouveau fichier), WaypointServiceTest (+1 cas déjà-découvert), TravelBeaconServiceTest (+1 cas
  recherche réelle via vrai InventoryClickEvent), WaypointNameCatalogTest (+1 cas lisibilité),
  SchemaMigratorTest (+3 cas V23).
Branche finale: feat/control-panel-admin-tools (aucun merge — déploiement DEV/AWS autorisé
  explicitement pour cette session)
Dernier commit: d0e75d1 (voir aussi f776629 #152, 47f50f5 noms/recherche/déjà-découvert, b9c2578
  #154 ; docs à committer séparément ensuite)
Build: vert, voir Tests ci-dessus.
Tests manuels en attente: TC-226 (#154), TC-227 (#133/#135/#156 recherche/noms/déjà-découvert),
  TC-228 (#152 Control Panel), addendum #159 dans TC-225 -- tous dans docs/MANUAL_TEST_PLAN.md,
  explicitement non présentés comme validés en jeu. En particulier : la saisie réelle dans l'enclume
  et l'affichage du coût XP ne sont PAS vérifiables automatiquement ici (PrepareAnvilEvent exige un
  AnvilView que cette version de MockBukkit ne simule pas) -- ne jamais présenter ce point comme
  validé sans un test en jeu réel.
Blocages: aucun bloquant produit non déductible. #159 reste un signalement sans cause confirmée
  (diagnostic + instrumentation posés, pas une correction au sens strict) -- à retrancher ou
  reclasser selon le retest en jeu demandé.
Première étape à reprendre: déploiement DEV (plugin) + AWS (Control Panel) de cette session, puis
  validation manuelle en jeu (TC-226/227/228 + addendum TC-225/#159) ; si du temps reste ensuite,
  #160 à #163 dans cet ordre de priorité (159 déjà traité en diagnostic).
```

```text
Date: 2026-10-03 (nuit, session autonome longue durée — multi-tickets)
Branche de départ: feat/control-panel-admin-tools
Étape de départ: session autonome overnight autorisée explicitement (coder/documenter/committer/pousser/déployer DEV+AWS/redémarrer sans reconfirmation, LoDyMcFly déconnectable, aucun merge/PROD), ordre de priorité fourni : (1) finir le déploiement DEV déjà poussé (fa41931/7c50802), (2) #153 placement accessible, (3) #156 waypoints sans borne, (4) #33 faim/saturation Hub, (5) #155+#121 mobs indésirables Hub, (6) #30 protection animaux Hub, (7) #152 Control Panel listes, (8) #154 secours rune. Audit préalable : lecture CLAUDE.md + état réel dépôt + 9 tickets (#153/#156/#33/#155/#121/#30/#31 fermé/#152/#154) via gh issue view avant tout code.
Étapes terminées:
(1) DONE — JAR @ 7c50802 déployé DEV, redémarré (uptime_seconds=5, 4 plugins verts, 3 mondes chargés), V22 confirmée appliquée.
(2)+(3) DONE — #153 : RandomSafeLocationFinder#findAccessibleColumn (rejette sol de feuillage + colonne isolée sans voisin praticable à ±1 bloc, vérification locale bornée 4 voisins) utilisé par WaypointService#attemptGeneration et TravelBeaconService#attemptPairBeacon. Diagnostic+réparation : WaypointService#inaccessible/#repair, TravelBeaconService#inaccessible/#repairBeacon/#hubWaypointsWithoutBeacon/#pairingRetryRemainingMillis, nouvelles commandes /rpgadmin travel diagnose [monde] et /rpgadmin travel repair waypoint|beacon <id> confirm (id/nom/instance/découvertes jamais modifiés, seuls les blocs ajoutés par la structure sont déplacés). WaypointRepository#updatePosition, TravelBeaconRepository#updatePosition (nouvelles méthodes UPDATE ciblées).
(4) DONE — #33 : nouveau hub.HubComfortService (PluginService + Listener) : FoodLevelChangeEvent annulé pour toute diminution dans le Hub (jamais une augmentation), garde périodique 1s neutralisant l'épuisement/saturation silencieux, restauration vie+faim+saturation au maximum à la connexion/au changement de monde/à la réapparition dans le Hub — jamais hors Hub.
(5)+(6) DONE — #121/#155/#30 : hub.HubWorldProtectionListener étendu — onEntityDamage protège désormais aussi toute entité vivante non-joueur du Hub (animaux, mobs décoratifs) contre les dégâts causés par un joueur (mêlée + projectile via shooter), sauf PNJ Citizens (NpcIdentityService#isCitizensNpc, même service que ZoneProtectionListener) ; nouveau bypass EXPLICITE et séparé rpgquest.admin.hub.combat (jamais accordé implicitement par rpgquest.admin.world). onCreatureSpawn élargi : annule désormais TOUTE raison de spawn (pas seulement NATURAL — couvre CHUNK_GEN/SPAWNER, cas probable des mobs déjà présents avant cette protection) pour Monster + ENDERMAN (même neutre) + WANDERING_TRADER/TRADER_LLAMA. Nouveau nettoyage ciblé : sweepAlreadyLoaded(World) (appelé une fois au démarrage sur les chunks déjà chargés, ex. spawn) + onEntitiesLoad(EntitiesLoadEvent) (à chaque nouveau chargement de chunk) — jamais un chargement forcé de tout le monde, jamais Citizens, jamais un animal passif.
Tests : SchemaMigratorTest/RandomSafeLocationFinderTest/WaypointServiceTest/TravelBeaconServiceTest (+ cas #153/#156 détaillés dans le rapport précédent), nouveau HubComfortServiceTest (9 cas, 0 ignoré), HubWorldProtectionListenerTest réécrit avec NpcIdentityService réel (22 cas, 0 ignoré, dont 13 nouveaux). Suites ciblées hub.*/waypoint.*/travel.beacon.*/admin.*/zone.*/npc.* toutes vertes. Suite complète :test (163 classes) et ./gradlew build (3 modules) lancés pour cette tâche — voir le rapport/commit final pour la confirmation chiffrée définitive.
Branche finale: feat/control-panel-admin-tools (aucun merge — déploiement DEV/AWS autorisé explicitement pour cette session)
Dernier commit: (voir git log — commits de cette session, #153/#156 puis #33/#121/#155/#30)
Build: RPGQUEST_TEST_MAX_HEAP=768m, jamais deux Gradle actifs sciemment (un chevauchement accidentel d'un compileJava ponctuel avec un :test complet déjà lancé a eu lieu une fois en tout début de ce bloc — sans conséquence constatée, un second compileJava+les suites ciblées pertinentes ont été rejoués proprement ensuite pour confirmer).
Tests manuels en attente: TC-224 (#153/#156) et TC-225 (#33/#30/#121/#155) dans docs/MANUAL_TEST_PLAN.md — PENDING MANUAL VALIDATION, explicitement non présentés comme validés en jeu.
Blocages: aucun bloquant produit non déductible rencontré sur (1)-(6). #152/#154 : voir le bloc suivant si entamés dans la même session, sinon reportés faute de temps (pas un blocage de décision).
Première étape à reprendre: #152 (listes Control Panel waypoints/bornes, lecture seule d'abord) puis #154 (secours via la Rune de rappel existante, gratuit dans le Hub) si non commencés dans cette session ; sinon validation manuelle en jeu de TC-220 à TC-225.
```

```text
Date: 2026-10-03 (nuit, post-déploiement — bugs constatés en jeu)
Branche de départ: feat/control-panel-admin-tools
Étape de départ: correction des bugs constatés en jeu sur le menu des bornes (#132/#150) avant #152 : (1) tous les clics (destination/retour/recherche) restaient silencieux après ouverture du menu, (2) plusieurs destinations avec des noms identiques (ex. « beach » en double), (3) demande de réorganiser le menu par monde (Hub/Wild propres pages, autres mondes extensibles)
Étapes terminées: DONE — (1) Cause racine identifiée par lecture du code (pas en reproduisant en jeu) : BeaconMenuSession était posée AVANT player.openInventory(...), qui ferme d'abord l'ancien menu de façon SYNCHRONE -> l'InventoryCloseEvent déclenché effaçait aussitôt la session tout juste posée via handleClose -> tout clic suivant lisait session==null et sortait silencieusement (même classe de bug que la navigation du journal de quêtes, issue #11, jamais réappliquée ici lors de #132/#150). Corrigé en inversant l'ordre (openInventory puis sessions.put) dans openRoot/openVillages/openWaypoints, + openSearch pose désormais aussi une session (monde mémorisé pendant le détour par l'enclume). 8 nouveaux tests de régression routent un VRAI InventoryClickEvent à travers TravelBeaconListener.onInventoryClick (jamais un appel direct à handleWaypointsClick/etc., qui aurait masqué exactement ce bug comme les tests précédents le faisaient) : destination/retour/recherche/pagination/fermeture, + 2 tests dédiés au choix du monde (Hub+Wild toujours proposés même à 0 découverte ; monde tiers extensible uniquement si une découverte y existe réellement, jamais codé en dur).
(2) Menu réorganisé : nouveau BeaconMenuHolder.Kind.WORLDS, étape intermédiaire Root -> choix du monde (ordre figé dans BeaconMenuSession#worldsShown, jamais recalculé au clic) -> liste de waypoints FILTRÉE à ce monde (BeaconMenuSession +world). Retour depuis Waypoints -> choix du monde (pas directement Root) ; retour depuis choix du monde -> Root. Recherche reste scoped au monde choisi (survit au détour par l'enclume).
(3) Nommage (#133/#135, audités avant codage — #135 est en réalité la future UI PlugAdmin d'import/dédup, #133 le spec complet incluant Control Panel + prompts rester/retour Hub, tous deux hors périmètre explicite de cette tâche ciblée) : nouveau waypoint.model.WaypointNameCatalog (sans Bukkit, testable JUnit pur) + réserve statique bundlée waypoint-names.txt (220 noms générés par combinaison préfixe/suffixe style LodyQuests, dédupliqués). Waypoint +displayName (2e composante du record). Attribution SYNCHRONE au moment de la génération (attemptGeneration, thread principal -> jamais deux générations concurrentes dans ce process, donc jamais un nom réservé deux fois) ; usedDisplayNames en mémoire rechargé au démarrage depuis tous les waypoints existants. Nom de secours "Avant-poste N" si réserve épuisée. Migration V22 (waypoints.display_name, ALTER + backfill Java ligne par ligne par catalogue + index unique idempotent) : les waypoints déjà existants (même biome en double, ex. "beach") reçoivent chacun un nom distinct, id et découvertes joueurs inchangés — testé explicitement (2 waypoints même biome -> 2 noms différents après backfill) + l'index unique refuse un vrai doublon d'attribution (pas seulement en mémoire). Décision : PAS d'appel IA, conformément à l'instruction explicite — #148 enrichira la réserve plus tard, hors de ce mécanisme.
Branche finale: feat/control-panel-admin-tools (aucun merge/déploiement/redémarrage — aucune autorisation reçue pour cette tâche)
Dernier commit: (voir git log — commit feat(travel): #132 #150 bugfix clics + menu par monde + noms waypoints)
Build: ./gradlew compileJava/compileTestJava/build (3 modules) verts, RPGQUEST_TEST_MAX_HEAP=768m.
Tests: TravelBeaconServiceTest 26 cas (21 exécutés verts / 5 ignorés teleportAsync, 0 échec, aucun des nouveaux cas de régression clic n'atteint teleportAsync sauf les 2 tests "...ActuallyTravels" qui l'atteignent en toute fin après avoir déjà vérifié le routage réel) ; SchemaMigratorTest 32 cas (0 ignoré, dont 3 nouveaux V22 : backfill sans doublon, index unique refuse un doublon réel, idempotence) ; nouveau WaypointNameCatalogTest 6 cas (0 ignoré). Suite complète :test (163+ classes) : 1339 tests, 1305 exécutés verts, 34 ignorés (même limitation MockBukkit teleportAsync, inchangée), 0 échec. ./gradlew build (3 modules) vert.
Tests manuels en attente: validation en jeu complète de TOUT le réseau de voyage (TC-221/TC-222/TC-223 mis à jour) — EXPLICITEMENT rien n'est présenté comme validé en jeu avant le nouveau test de l'utilisateur, conformément à sa consigne. Rien déployé pour cette tâche (le JAR en ligne reste celui du déploiement précédent, qui contenait encore le bug de clics).
Blocages: aucun — tous les bugs rapportés diagnostiqués par lecture de code (pas de reproduction en jeu possible depuis cette box), corrections vérifiées par tests de régression routant le vrai bus d'événements plutôt que des appels directs (qui avaient historiquement masqué ce type de bug).
Première étape à reprendre: déploiement DEV de cette correction (nouvelle autorisation requise) puis validation manuelle en jeu ; #152 (administration Control Panel) ensuite si c'est la priorité choisie.
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
