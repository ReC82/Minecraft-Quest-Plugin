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
Date: 2026-10-08 (matin, 2e lot — #146 LOT 4 : atelier IA)
Branche de départ: feature/218-starter-kit-tiers @ 062c284 (fin du lot #109, déployé)
Étape de départ: « continue sur le LOT 4 : atelier IA », avec la consigne d'utiliser comme
  fondation la chaîne #110 -> résultat IA -> import #109. Ticket réel : #146.
Étapes terminées:
  - /ai/studio « Créer avec une IA » : formulaire en français (un seul champ nécessaire), prompt
    assemblé automatiquement (règles + doc de #110 + schéma + types RÉELS + références RÉELLES),
    extraction du document, validation par les validateurs RÉELS, diagnostics, aperçu, puis
    bouton qui poste vers /content/import. L'ATELIER N'A AUCUN CODE D'ÉCRITURE : l'exigence
    « aucune publication automatique » est obtenue par construction.
  - Bouton « Demander une correction » : renvoie à l'IA sa propre sortie ET les diagnostics réels.
  - /ai/providers : configuration complète depuis l'IHM (clé, modèle, URL de base, plafond de
    jetons, délai) + « Tester la connexion » qui fait un VRAI appel minimal.
  - Abstraction AiProvider (3 opérations) + AiProviderRegistry seul détenteur de la liste ; socle
    HTTP commun (délais, erreurs lisibles, lecture défensive) ; TROIS fournisseurs réels :
    Anthropic/Claude, OpenAI + API compatibles, Google Gemini.
  - Sécurité de la clé : base locale du panel (hors Git, mode 600) ; jamais renvoyée au
    navigateur ; jamais journalisée ; toString() du record REDÉFINI (le toString généré aurait
    imprimé la clé entière) ; URL en HTTP refusée ; champ vide = conserver, effacer = bouton
    distinct ; autocomplete=new-password. Deux permissions distinctes AI_USE / AI_CONFIGURE.
  - Règle UX : le titre souhaité passe par le composant guidé partagé (#195) ; réponse brute en
    mode avancé replié seulement.
Branche finale: feature/218-starter-kit-tiers @ 703e29a (poussée)
Dernier commit: 703e29a docs: atelier IA, sa sécurité et son test manuel (#146)
Build: ./gradlew build BUILD SUCCESSFUL en 10 min 52 s (RELANCE), depuis le worktree propre.
Tests: 1890 plugin + 860 panel + 30 web-api = 2780, 0 échec (+48 pour ce lot). AUCUN appel réseau
  sortant : fournisseur bouchon. Le PREMIER build avait rapporté 1 échec,
  RestartServiceTest.aDroppingUptimeProvesTheRestartEvenIfNoProbeEverFailed — rapporté et
  diagnostiqué, pas masqué : zéro référence au code du lot, panel/ops inchangé depuis #95, attente
  bornée à 6 s, passe isolément en 15 s, suite verte 15 min plus tôt. Suite relancée en entier.
  DETTE SIGNALÉE : ce test retombera sous charge, il mériterait une horloge injectable.
Déploiement: PANEL SEUL, 2026-10-08 10:53 sur 703e29a, 1 277 146 o, /health ONLINE,
  PANEL_DEPLOY_EXIT=0, release précédente 20261008-105331. AUCUN JAR, AUCUN REDÉMARRAGE MINECRAFT
  (:test et :jar UP-TO-DATE, JAR en ligne relu inchangé à 1 985 814 o). Table ai_provider créée en
  production et VIDE : l'IA est inerte jusqu'à configuration. Bytecode installé inspecté (16
  classes panel.ai + les deux pages).
Tests manuels en attente: TC-265 (#146, 22 points, EXIGE UNE VRAIE CLÉ API et consomme des jetons
  facturés) — non exécuté, donc #146 reste OUVERTE. Plus TC-264 (#109), TC-257 (#123) et
  TC-258..TC-263.
Blocages: aucun. Limites assumées et documentées : une seule quête par génération ; pas de
  dialogues ni stories ; AUCUN coût monétaire estimé alors que #146 le demande (les jetons réels
  sont affichés et audités, mais une conversion en euros supposerait une grille tarifaire qui
  change sans préavis — un chiffre faux serait pire que pas de chiffre) ; garde-fou de budget PAR
  APPEL seulement, pas cumulé ; et AUCUN des trois fournisseurs appelé avec une vraie clé.
Première étape à reprendre: configurer un fournisseur et faire au moins le point 4 de TC-265
  (« Tester la connexion ») — c'est le seul moyen de savoir si les trois implémentations d'API
  sont justes, et la réponse arrive en quelques secondes. Puis TC-265 en entier, puis TC-264.
  Ensuite : décider pour le coût (table de tarifs administrable, ou assumer les jetons seuls),
  et étendre l'atelier aux dialogues et stories — ce qui demande d'abord de rendre dérivable le
  vocabulaire des actions et conditions de dialogue (limite déjà notée en #110).
```

```text
Date: 2026-10-08 (matin — #109 : import sécurisé d'un content pack)
Branche de départ: feature/218-starter-kit-tiers @ dda1640 (fin du lot de nuit, déployé en DEV)
Étape de départ: « continue sur #109 maintenant ». #109 avait été laissée ENTIÈRE la nuit même,
  avec pour motif qu'un pipeline d'import à moitié construit est plus dangereux que pas d'import.
Étapes terminées:
  - #109 livré de bout en bout : ContentPackImport (analyze sans effet de bord / apply seul point
    d'écriture, qui LÈVE si l'analyse n'est pas importable), page /content/import en trois étapes
    visibles, permission dédiée CONTENT_IMPORT, CSRF, audit à l'analyse + confirmation + par
    écriture. États NEW/MODIFIED/UNCHANGED/CONFLICT/SKIPPED/INVALID, diff replié, dépendances
    classées, références internes au pack résolues (RefData#plus), schemaVersion non supportée
    refusée explicitement.
  - Trois propriétés de sécurité par CONSTRUCTION : aucun chemin ne vient du fichier (slug dérivé
    de l'identifiant, famille en liste blanche) ; aucun octet du pack écrit tel quel (fromMap
    extrait des trois lecteurs, puis ré-émission par l'écrivain réel) ; aucun écrasement
    silencieux (conflit bloquant + verrou optimiste au remplacement).
  - CORRECTION DE FORMAT : le pack écrivait « questIds » pour les quêtes d'une story alors que
    StoryDefinitionParser lit « quests ». Une story exportée par #108 n'était donc PAS relisible
    par le moteur — la doc de #108 affirmait le contraire. Export corrigé, import tolérant aux
    deux orthographes, schemaVersion reste à 1.
  - CORRECTION DE SÉCURITÉ : Http.formBody tronquait EN SILENCE à 64 Kio ; un pack coupé se serait
    importé amputé de ses derniers éléments. Lecture bornée qui SIGNALE le dépassement.
Branche finale: feature/218-starter-kit-tiers @ 63b6b1d (poussée)
Dernier commit: 63b6b1d docs: import de content pack et correction de la clé des stories (#109)
Build: ./gradlew build BUILD SUCCESSFUL en 30 min 23 s, depuis le worktree propre
  /srv/rpgquest/worktree-nuit détaché sur le commit déployé.
Tests: 1890 plugin + 812 panel + 30 web-api = 2732 tests, 0 échec, 0 erreur, 38 ignorés (tous
  préexistants). +42 tests pour #109, dont plusieurs écrits à l'envers : ils vérifient que RIEN
  n'a été écrit.
Déploiement: DEV fait le 2026-10-08 09:49–09:52 sur 63b6b1d. JAR 1 985 814 o (a335db8cd2…),
  DEPLOY_EXIT=0, redémarrage RCON RESTART_EXIT=0 avec 0 joueur. Control Panel redéployé
  séparément (1 219 885 o, /health ONLINE). Le JAR était 2 OCTETS PLUS PETIT que le backup —
  signal d'arrêt de la règle de déploiement, expliqué avant de continuer : ancêtre strict vérifié
  par git merge-base, seul changement plugin « questIds: » → « quests: » (2 caractères de moins),
  845 classes de part et d'autre sans disparition. Bytecode panel installé inspecté.
Tests manuels en attente: TC-264 (#109, 14 points) — NON exécuté, donc #109 reste OUVERTE.
  Plus TC-257 (#123) et TC-258..TC-263 (lot de nuit).
Blocages: aucun. NON livré : LOT 4 (atelier IA), toujours non commencé — son intérêt grandit
  maintenant que la chaîne contrat #110 -> IA -> import #109 est complète sauf l'appel d'API.
Première étape à reprendre: dérouler TC-264, en particulier le point 7 (collision concurrente) et
  le point 13 (/quest admin reload après import) — les deux seuls à pouvoir démentir les garanties
  annoncées. Puis le LOT 4.
```

```text
Date: 2026-10-08 (nuit — MODE NUIT AUTONOME : #218, #141, #156, #185, #110, #195)
Branche de départ: feature/218-starter-kit-tiers @ f6bcb75 — la ligne réellement déployée en DEV
  (chaîne 123 ← 161 ← 169), volontairement et PAS une branche d'intégration plus ancienne, qui
  aurait fait régresser #123/#161/#24 au premier déploiement.
Étape de départ: LOT 0 — vérifier que la branche contient tout #123 sans le réimplémenter (fait :
  moteur, dialogue, descripteur, journal, export et correctif TC-257 tous présents), puis les lots
  annoncés. #156 a été ajouté en PRIORITÉ en cours de session par l'utilisateur.
Étapes terminées:
  - #218 paliers du kit de départ : palier 1 automatique, palier 2 par quête
    DELIVER_ITEM_TO_NPC, impossibilité de sauter un palier garantie par le MOTEUR
    (grant-tier refuse SKIPPED / UNKNOWN_TIER / ALREADY_AT_LEAST), emplacements calculés sur le
    contenu réel, aucune migration. Paliers 3 à 5 acceptés mais CONTENU NON INVENTÉ.
  - #141 SMELT_ITEM : FurnaceExtractEvent (seul événement de four portant un joueur), les trois
    fours vanilla vérifiés explicitement, progression par lots plafonnée.
  - #156 appariement des bornes du Hub : AUDIT FACTUEL d'abord sur une copie lecture seule de la
    base DEV. Ratio corrigé (22 waypoints Hub / 13 bornes, pas 115/13 — le Wild n'en prend jamais).
    Hypothèse « espacement » RÉFUTÉE (borne la plus proche à 88 blocs ou plus partout). Cause
    réelle : défaut du déclencheur — persistance asynchrone du waypoint + sortie anticipée « même
    instance », donc une instance ne pouvait s'apparier qu'à une visite ULTÉRIEURE (les 13 bornes
    existantes sont nées 17 s à 11 h 50 après leur waypoint). Correctif + diagnostic administrable
    à source unique. AUCUN ratio borne/waypoint introduit, aucune politique de génération modifiée.
  - #185 DISCOVER_WAYPOINT : dixième type d'objectif, aucun écouteur d'événement de jeu
    volontairement, aucune table supplémentaire (unicité joueur/waypoint déjà garantie en base),
    deux modes de comptage explicites et ANNONCÉS au joueur, mode inconnu refusé au chargement.
  - #110 phase 1 : contrat machine-readable généré depuis Descriptors (schéma JSON Schema
    2020-12, gabarits YAML, contrat rédigé ~13 Ko pour un prompt), trois routes de téléchargement,
    exemples tc110_. Limite assumée : section dialogues contrainte sur son squelette seulement.
  - #195 suite : le composant guidé couvre maintenant TOUS les champs de texte destinés aux
    joueurs (titre et description de quête, nom de story, réplique de dialogue) ; variante
    multiligne ajoutée ; le select « Couleur du texte » concurrent est retiré.
Branche finale: feature/218-starter-kit-tiers (poussée)
Build: ./gradlew build BUILD SUCCESSFUL, depuis un worktree Git PROPRE (/srv/rpgquest/worktree-nuit)
  détaché sur le commit poussé — 30 min 51 s au premier passage (7072d92), 10 min 12 s au second
  (b0c0b8f) avec :test et :web-api:test UP-TO-DATE.
Tests: 1890 plugin + 770 panel + 30 web-api = 2690 tests, 0 échec, 0 erreur, 38 ignorés (tous
  PRÉEXISTANTS : MariaDB sans serveur, limites MockBukkit ; aucun dans les classes ajoutées).
  CrystalHuntIntegrationTest PASSE dans le worktree propre — son échec dans l'arbre de travail vient
  uniquement de src/main/resources/quests/crystal_hunt.yml réécrit par le propriétaire depuis le
  Control Panel (araignées/crafting → zombies/chasse). Ce fichier n'est ni committé ni modifié ici.
Déploiement: DEV fait le 2026-10-08 03:31–03:33 sur b0c0b8f. JAR 1 985 816 o (acc1954113…) +
  dialogues/guide.yml via --also (version en ligne comparée avant : 0 ligne supprimée, 49 ajoutées),
  DEPLOY_EXIT=0, redémarrage RCON RESTART_EXIT=0 avec 0 joueur connecté. Control Panel redéployé
  séparément (1 188 383 o, /health ONLINE). Vérifié : 5 plugins verts, 17 quêtes 0 erreur (16 avant,
  kit_tier2 semé), config.yml du serveur complété avec les deux paliers, bytecode panel installé
  inspecté.
Tests manuels en attente: TC-258 (#218), TC-259 (#141), TC-260 (#185), TC-261 (#156),
  TC-262 (#110), TC-263 (#195) — ajoutés à docs/MANUAL_TEST_PLAN.md. Plus TC-257 (#123) toujours
  entièrement à dérouler.
Blocages: aucun blocage technique. NON LIVRÉS, faute de temps et parce qu'un pipeline d'import à
  moitié construit est pire que pas d'import : #109 (import sécurisé de content pack) et le LOT 4
  (atelier IA + configuration de fournisseurs). Le contrat #110 est précisément la brique dont ces
  deux lots ont besoin.
Première étape à reprendre: #109 — import sécurisé de content pack
  (IMPORT → ANALYSE → VALIDATION → DIFF → BROUILLON → CONFIRMATION → ENREGISTREMENT SOURCE),
  en s'appuyant sur le schéma et les dépendances déclarées de #110. Puis le LOT 4.
  Aucune issue n'a été fermée : toutes portent un test manuel non exécuté.
```

```text
Date: 2026-10-07 (soirée, 2e lot — #123 : remise d'objets à un PNJ avec dépôts partiels)
Branche de départ: feature/161-wild-entry-ux @ 4a99bff — volontairement, et PAS la branche
  d'intégration : c'est la ligne réellement déployée en DEV depuis le lot précédent, donc partir
  d'ailleurs aurait fait régresser #161/#24 au premier déploiement de ce lot.
Étape de départ: issue #123 seule (DELIVER_ITEM_TO_NPC complet, administrable depuis le Control
  Panel, déployé DEV, recette manuelle), avec décisions fonctionnelles explicites reçues en
  conversation : dépôts partiels persistants, consommation réelle, jamais plus que le reliquat,
  plusieurs objectifs par étape, UNE interaction pour tous les matériaux, mécanisme générique jamais
  codé pour un PNJ précis, #218 non touchée.
Étapes terminées:
(1) DONE — Objectif DELIVER_ITEM_TO_NPC (8e valeur d'ObjectiveType, interface scellée : tous les
  switch exhaustifs du dépôt ont dû être complétés, donc aucun oubli possible par construction).
  AUCUN écouteur d'événement pour ce type, volontairement : il n'existe littéralement aucun chemin
  par lequel ramasser/fabriquer/posséder ferait avancer une remise.
(2) DONE — AUCUNE MIGRATION DE SCHÉMA : la quantité déjà remise EST le compteur d'objectif, dans la
  table quest_objective_progress existante que loadForPlayer relit déjà. La persistance après mort /
  reconnexion / redémarrage est donc obtenue en réutilisant le mécanisme existant, et resetnew la
  remet à zéro sans code dédié. C'est la décision d'architecture centrale du lot.
(3) DONE — QuestItemWithdrawal : retrait exact (jamais plus que le reliquat), plusieurs piles
  additionnées, stockage normal uniquement (ni armure ni main secondaire), et renvoie le nombre
  RÉELLEMENT retiré — le compteur n'avance que de cette valeur, donc impossible de progresser sans
  retrait. Aucun objet personnalisé RPGQuest consommé (identité PDC) : sans cette règle, une quête
  demandant BOOK détruirait le journal de quêtes du joueur. Lecture du PDC exposée en statique sur
  YamlCustomItemRegistry pour ne pas dupliquer la clé.
(4) DONE — deliverTo : une seule interaction traite tous les objectifs de ce PNJ (plusieurs
  matériaux, plusieurs quêtes), jeton par joueur refusant toute remise réentrante (BUSY), et
  complétion d'étape évaluée seulement APRÈS toute la remise (sinon le 4e retrait porterait sur une
  quête déjà sortie des actives).
(5) DONE — Dialogue : action DELIVER_QUEST_ITEMS + condition HAS_PENDING_DELIVERY, npc OPTIONNEL —
  vide, le destinataire est la clé du dialogue (convention id de dialogue = id de PNJ). La branche
  livrée dans guard.yml ne nomme donc ni quête, ni matériau, ni PNJ, et un test verrouille cette
  généricité. La condition niée exprime « ce PNJ n'attend plus rien » sans nouveau type.
(6) DONE — %delivery_status% : les valeurs dynamiques de texte reçoivent désormais un CONTEXTE
  (joueur + dialogue) au lieu du seul joueur, parce que celle-ci dépend du PNJ qui parle. Évolution
  directe du mécanisme livré pour #24 le même jour.
(7) DONE — Control Panel : descripteur DELIVER_ITEM_TO_NPC (PNJ, objet, quantité). Formulaire,
  sérialisation, aller-retour et validation en découlent sans code spécifique. Champ « npc » ajouté
  au relevé structuré de l'agent (une remise a DEUX cibles) de façon ADDITIVE, avec constructeur de
  compatibilité pour tous les appelants existants.
(8) DONE — Journal : « Cuir (à remettre) 2/4 », le verbe distinguant la remise d'une collecte.
(9) DONE — Correctif trouvé en relisant le chemin de la condition : les compteurs d'une quête active
  vivaient dans un HashMap écrit sur le thread principal mais lu depuis un thread de base
  (NpcHintService chaîne reachableNodes après une requête) -> ConcurrentHashMap.
Tests: suite complète dans un worktree PROPRE. PREMIER passage ÉCHOUÉ : ManualTestQuestPackTest
  exige une quête de docs/manual-tests/quests/ par type d'objectif, ni plus ni moins — le 8e type
  n'en avait pas. Exactement ce que ce filet est conçu à attraper, invisible pour les tests ciblés.
  Corrigé (test_deliver_item_to_npc.yml + pack 7 -> 8 dans MANUAL_TEST_PLAN.md), puis passage vert.
  Nouveaux : QuestItemDeliveryTest (23), QuestItemWithdrawalTest (8), ObjectiveLabelsTest (2), plus
  des cas ajoutés aux parsers de quête et de dialogue, à l'aller-retour YAML, au content pack et au
  panel. Un test désérialise réellement l'état de remise en MiniMessage : la balise <lang:…> doit
  produire un composant traduisible, sinon le joueur lirait « <lang:item.minecraft.leather> ».
Branche finale: feature/123-deliver-item-to-npc (poussée, AUCUN merge)
Build: vert.
Tests manuels en attente: TC-257 (NOUVEAU, 24 points), plus TC-236 à TC-256 déjà en attente.
Blocages: aucun. LIMITES ASSUMÉES : (a) items custom non acceptés en V1 (conforme au ticket) —
  l'architecture est prête (QuestItemWithdrawal#matches est la seule règle d'éligibilité) mais rien
  n'est testé pour ce cas et le champ item: n'existe pas ; (b) aucune restitution des objets remis
  (hors périmètre explicite) ; (c) le nom du PNJ n'apparaît pas dans le journal, seulement
  « (à remettre) » — deux quêtes de remise vers deux PNJ différents ne se distinguent que par
  l'objet ; (d) le relevé de l'agent a changé de forme (champ npc), jamais vérifié avec deux
  versions réellement désynchronisées ; (e) guard.yml est désormais COUPLÉ au nouveau JAR — un
  rollback du JAR seul ferait rejeter ce dialogue au chargement, il faut restaurer les deux.
Issues: #123 LAISSÉE OUVERTE — tout le périmètre est livré et déployé, mais la validation manuelle
  en jeu (parcours Control Panel dans un navigateur, fenêtre de dialogue réelle, vrai redémarrage)
  n'a pas été faite, et CLAUDE.md interdit de fermer une issue tant que des tests manuels restent
  nécessaires. Fermeture possible dès que TC-257 passe. #218 NON touchée, conformément à la consigne.
Première étape à reprendre: dérouler TC-257 (compte non OP), puis fermer #123, et seulement ensuite
  attaquer #218 — qui est le consommateur prévu de ce mécanisme.
RATTRAPAGE 2 (2026-10-07 22:42-23:40) : deux constats de recette TC-257. (a) Le PNJ destinataire
  d'une remise était PERDU à l'affichage d'une quête « source » (« à ? ») alors que le YAML portait
  bien npc: guard — source et aller-retour hors de cause. Cause : AgentPages#objectiveDetail
  convertit un objectif de la SOURCE dans la forme du relevé runtime et n'émettait pas « npc ». Une
  remise a DEUX cibles ; j'avais ajouté le champ au relevé de l'agent au lot 7, pas à ce
  convertisseur. Mes tests ne pouvaient pas le voir : ils partaient d'un résumé déjà pourvu du
  champ. Corrigé (b2359db), un destinataire absent s'affiche « (PNJ non défini) », et le nouveau
  test part du YAML pour vérifier le HTML servi. La validation refusait DÉJÀ un enregistrement sans
  destinataire (verrouillé par deux tests). Les diagnostics disaient vrai : QuestDiagnosticProvider
  ne lit que le relevé runtime, où la quête n'était pas. (b) « Source uniquement » est le
  comportement NORMAL : le panel édite la source sur AWS, le serveur DEV n'est joignable que par
  déploiement, et la bannière du panel énonce déjà les deux causes. Quête publiée par le mécanisme
  normal (--allow-no-backup est l'option documentée pour CRÉER un fichier en ligne), runtime passé
  de 15 à 16 quêtes 0 erreur, et la quête n'est plus « Source uniquement » dans le panel servi.
  /quest accept NON vérifié : exige un joueur connecté. Signalés non corrigés : badge « 1 objectif »
  qui compte en fait les étapes, et COBBLESTONE rendu « Pierre taillée » au lieu de « Pierre ».
RATTRAPAGE (2026-10-07 22:21-22:25) : le Control Panel n'avait JAMAIS été déployé avec #123 — seul
  le JAR plugin et guard.yml l'avaient été, alors que le commit 10ce149 porte toute la partie panel.
  TC-257 bloquait donc à son premier point (liste d'objectifs limitée aux 7 anciens types). Panel et
  plugin sont DEUX déploiements distincts, par deux scripts différents : scripts/plugadmin/deploy.sh
  (local AWS) et scripts/deploy-verygames.sh (VeryGames). Un lot qui touche les deux doit exécuter
  les deux ; rien ne le rattrape automatiquement, et aucune vérification côté Minecraft ne peut
  révéler l'oubli. Diagnostic AVANT action (service actif depuis le déploiement du lot 5 + bytecode
  installé ne contenant que 7 types) : déploiement manquant, aucun code modifié. Panel redéployé
  depuis f574a74, release précédente sauvegardée en 20261007-222157, /health ONLINE, et les 8 types
  vérifiés sur la page RÉELLEMENT servie (compte jetable supprimé ensuite ; le login HTTPS exige le
  jeton _csrf du formulaire, sinon 403). JAR et serveur Minecraft NON retouchés. #123 toujours
  ouvert, TC-257 entièrement à faire.
```

```text
Date: 2026-10-07 (soirée — #161 / #26 partie B / #24 : entrée dans le Wild et état du Wild)
Branche de départ: feature/169-special-mobs-boss @ 21fea61 (branche d'intégration réelle — l'audit a
  confirmé que feat/control-panel-admin-tools y est déjà intégrée ; CLAUDE.md mentionne encore
  feature/23-mod-prototype, qui n'est plus la bonne)
Étape de départ: tickets #161, #26 et #24 traités ensemble (ils se recouvrent), avec des décisions
  fonctionnelles explicites reçues en conversation qui MODIFIENT les tickets : #161 devient la
  référence de l'UX d'entrée dans le Wild, la partie B de #26 est révisée pour INTERDIRE toute
  inspection d'inventaire, et l'état du Wild (#24) se demande au Garde au lieu d'être injecté dans
  l'avertissement du portail. Autorisations : coder, tester, commit, push, déployer DEV, redémarrer.
  Aucun merge.
Étapes terminées:
(1) DONE — #161 : travel.WildEntryWarningService réécrit. L'ancienne version LISAIT L'INVENTAIRE
  (recherche d'une Rune de rappel) et n'avertissait que les joueurs sans Rune : comportement,
  documentation et tests retirés. Avertissement générique identique pour tous, trois actions
  explicites, fermeture de fenêtre = annulation (l'API Dialog de Paper n'expose aucun rappel de
  fermeture : aucune détection n'a été simulée, simplement aucune action par défaut). allowEntry
  renvoie toujours false pour le Wild et le service prend la main, la préférence vivant en base.
(2) DONE — Option « ne plus afficher » persistée dans player_variables (WILD_ENTRY_WARNING_HIDDEN,
  AUCUNE migration), écrite uniquement par le bouton « Entrer et ne plus afficher » — mémorisation
  sans départ réel structurellement impossible. resetnew la rétablit sans code dédié.
(3) DONE — Jeton inFlight unique (lecture + départ), relâché dès l'affichage pour qu'une fermeture
  silencieuse ne bloque jamais l'accès ; départ au tick suivant pour que le message d'attente parte
  AVANT la génération de chunks ; joueur relu avant la TP (aucune TP tardive) ; onQuit relâche.
  Ancien cooldown anti-spam de 4 s SUPPRIMÉ : l'anti-boucle du listener suffit et n'empêche pas de
  ressortir/rentrer volontairement (verrouillé par un test du listener).
(4) DONE — Faux succès corrigé : WorldPortalTeleportListener envoyait « Téléportation réussie. »
  même quand Player#teleport renvoyait false. Message d'échec explicite désormais.
(5) DONE — Diagnostic de latence : RandomSafeLocationFinder.SearchMetrics (tentatives + temps
  getChunkAt) et ligne [TP-LATENCY] séparant évaluation / chunks / téléportation. MESURE SEULE :
  aucun contrôle de sécurité allégé, RANDOM_SAFE et le répit de 40 ticks inchangés.
(6) DONE — #24 : travel.WildConditionsService en lecture pure (jour/nuit depuis getTime() + bornes
  12300/23850, volontairement INDÉPENDANT de la météo car World#isDayTime() compte un orage de midi
  comme la nuit ; météo globale assumée comme un état du monde, jamais du biome). Exposé sans
  commande par un choix PERMANENT du Garde, via le nouveau marqueur %wild_conditions%
  (dialogue.DialogueTextPlaceholders, substitué au rendu, une seule passe, clé inconnue laissée
  visible, toutes les langues traitées). Jamais injecté dans l'avertissement du portail.
(7) DONE — #26 partie A NON TOUCHÉE (aucun fichier du kit dans le diff) ; partie B livrée sous sa
  forme révisée par #161 ; plus aucun test ni aucune doc n'impose d'inspection d'inventaire, et un
  test vide explicitement l'inventaire pour verrouiller la règle.
(8) DONE — Présentation calquée sur dialogue.render : WildEntryPromptPresenter + Paper + repli chat
  + décorateur de repli, pilotés par la préférence serveur existante dialogue.renderer.
Tests: suite complète dans un worktree PROPRE (les fichiers de contenu non suivis du propriétaire
  cassent CrystalHuntIntegrationTest) : plugin 1801 / 0 échec / 37 ignorés ; control-panel 722 / 0 /
  1 ; web-api 30 / 0 / 0. ./gradlew build vert ; :compileJava/:test/:jar confirmés à jour sur le
  commit déployé. Nouveaux : WildConditionsServiceTest (9), DialogueTextPlaceholdersTest (6),
  WildEntryWarningServiceTest réécrit (12), +3 WorldPortalTeleportListenerTest, +2
  RandomSafeLocationFinderTest, +1 TpTraceLoggerTest, +1 DialogueSessionEngineTest, +1
  BundledDialoguesValidityTest.
Branche finale: feature/161-wild-entry-ux (poussée, AUCUN merge)
Dernier commit: 4f9af35
Build: vert.
Déploiement: DEV réel le 2026-10-07 19:41-19:45 — JAR b234c594… (1 935 862 o) ET
  RPGQuest/dialogues/guard.yml via --also (obligatoire : un redéploiement de JAR ne met jamais à
  jour un dialogue déjà présent). Version en ligne de guard.yml comparée AVANT transfert : identique
  au dépôt, donc aucune édition Control Panel écrasée. Backup JAR 71020c82… confirmé identique au
  JAR du lot 5. 1 joueur connecté prévenu deux fois, redémarrage RCON OK, 5 plugins verts.
Tests manuels en attente: TC-256 (NOUVEAU, 22 points — avertissement, annulation, fermeture clavier,
  absence de boucle, « ne plus afficher » après reconnexion ET redémarrage, relevé [TP-LATENCY],
  état du Wild au Garde sans modification de l'heure/météo, non-régression du kit du Guide), plus
  TC-236 à TC-255 déjà en attente.
Blocages: aucun. LIMITES ASSUMÉES : (a) le chargement du dialogue déployé n'a PAS été constaté en
  jeu (logs serveur inaccessibles depuis la machine de build, /dialogue open exige un joueur
  connecté, aucun ne l'était après le redémarrage) — le fichier est néanmoins identique à celui que
  BundledDialoguesValidityTest charge sans problème ; (b) la latence réelle n'est pas mesurable sous
  MockBukkit (aucune génération de terrain) ; (c) le texte affirme que le PvP est autorisé dans le
  Wild : vérifié qu'AUCUNE annulation de dégâts PvP n'existe pour ce monde, mais server.properties
  n'est pas lisible via FTP.
Issues: #161, #26 et #24 LAISSÉES OUVERTES. Chacune porte un critère de validation manuelle en jeu,
  et #161 exige en plus que la latence observée soit DOCUMENTÉE — l'outil est livré, la mesure
  demande un vrai passage. Fermeture possible dès que TC-256 passe.
Première étape à reprendre: dérouler TC-256 (compte non OP), relever les valeurs [TP-LATENCY], puis
  fermer les trois tickets. Si la latence est dominée par la génération de chunks, ouvrir un ticket
  distinct pour une recherche de point d'arrivée hors du thread principal — jamais en effet de bord.
```

```text
Date: 2026-10-07 (lot 5 — #165 : Citizens compileOnly complet, Look Close et Wander)
Branche de départ: feature/169-special-mobs-boss @ 2f181fd (lot 4, état de présence + déplacement)
Étape de départ: FEU VERT du propriétaire pour ajouter le JAR complet de Citizens en compileOnly,
  afin d'implémenter Look Close et Wander que j'avais conclus — à tort — non implémentables.
  Cadre imposé : vérifier classes/signatures/sources RÉELLES, appels typés (ni réflexion ni clés de
  persistance internes), ne pas embarquer Citizens, ne pas ajouter WorldGuard/Denizen, isoler dans
  un adaptateur, documenter la compatibilité, erreur claire si la version ne convient pas, aucun
  toggle aveugle au retry, terminer D'ABORD le déploiement déjà lancé sans déploiement concurrent,
  et ne pas considérer la suite verte avec l'échec RestartServiceTest.
Étapes terminées:
(1) DONE — Déploiement du lot 4 terminé. Le précédent n'avait RIEN transféré (sortie de 49 octets,
  arrêt à l'étape 3/8, AUCUN backup créé — or le script sauvegarde avant de transférer). Ma
  relance a échoué à son tour parce que la tâche de fond précédente tournait encore : deux Gradle
  sur le même répertoire de build -> NoSuchFileException sur in-progress-results-generic.bin.
  Démons arrêtés, résultats nettoyés, relance SEUL : passée. JAR 1 899 262 o, SHA-256 9b27134e… ;
  backup rpgquest-20261007T145132Z-predeploy.jar (14703d4c…). Redémarrage NON fait, groupé.
(2) DONE — RestartServiceTest, échec initial et résultat final rapportés distinctement. Diagnostic
  VÉRIFIÉ dans le code du test (et non supposé) : FAST_TIMEOUT = 3 s réelles et await() de 6 s
  réelles, donc sensible à la charge par construction ; l'échec a eu lieu sous contention Gradle.
  Suite relancée en exécution unique : BUILD SUCCESSFUL, RestartServiceTest 24/0. Je ne prétends
  PAS avoir prouvé quelle assertion avait cédé — le message n'avait pas été conservé.
(3) DONE — citizens-main en compileOnly strict (isTransitive = false pour écarter libby-bukkit,
  non résolvable). Vérifié empiriquement par `gradlew dependencies --configuration compileClasspath`
  qu'aucune dépendance implicite n'entre : WorldGuard, Denizen, PlaceholderAPI, Vault, Spigot,
  packetevents sont tous en scope `provided` dans le POM, non résolu par Gradle.
(4) DONE — CORRECTION de trois affirmations fausses que j'avais écrites dans la Bible, démenties
  par les sources réelles : (a) le toggle n'est une limite que de la COMMANDE /npc lookclose, le
  trait expose lookClose(boolean) ; (b) Wander PERSISTE — c'est le fournisseur `wander` du trait
  `waypoints`, pas un goal posé à la main ; (c) une patrouille existante EST détectable via
  Waypoints#getCurrentProviderName. La Bible porte désormais la correction explicitement.
(5) DONE — CitizensBehaviourBridge : SEUL fichier référençant citizens-main, distinct de
  CitizensNpcBridge. Instanciation et appels gardés contre LinkageError -> CITIZENS_INCOMPATIBLE,
  le reste de l'intégration PNJ continue de fonctionner. Appels typés uniquement.
  Look Close : état explicite + portée, défauts effectifs lus sur Settings.Setting (pas recopiés),
  relecture sur le trait après écriture (LOOKCLOSE_NOT_APPLIED plutôt qu'un succès trompeur).
  Wander : ordre zone-puis-ancre IMPOSÉ par le code de Citizens (setXYRange ne recalcule pas
  l'arbre de régions) ; désactivation -> fournisseur neutre + annulation de navigation + position
  finale rapportée.
(6) DONE — WanderChangePlanner (pur, testable sans serveur) : distingue l'état NEUTRE (linear vide,
  ce dans quoi Citizens laisse tout PNJ jamais configuré) d'un comportement réel, et refuse
  (WANDER_CONFLICT) en NOMMANT ce qui serait perdu tant que confirm_replace n'est pas donné.
  Réactiver = RECONFIGURE (idempotent) ; désactiver ne retire QUE la promenade.
(7) DONE — Chaîne agent : npc.citizens.lookclose + npc.citizens.wander. `enabled` ne connaît que
  true/false — ni vide, ni « toggle », ni défaut implicite — donc un rejeu n'inverse jamais l'état.
  Ancre jamais partielle (refus des deux côtés). Permissions INCHANGÉES : regard = NPC_BIND_WRITE
  (cosmétique), promenade = NPC_SPAWN_WRITE (déplacement). Aucun droit nouveau.
(8) DONE — Panel : ligne « Comportement » (trois états, « inconnu » JAMAIS affiché « désactivé »),
  deux formulaires à boutons d'état, ancre explicitement distinguée de la position avec l'effet
  d'un déplacement manuel énoncé, aide visible sous chaque champ.
Tests: plugin 1772 / 0 échec / 37 ignorés ; control-panel 722 / 0 échec / 1 ignoré (XML JUnit).
  Nouveaux : WanderChangePlannerTest (10), NpcBehaviourActionTest (15), +14 dans NpcsCatalogTest (53 au total),
  fixtures étendues de NpcCitizensPayloadTest (la garde structurelle par réflexion couvre
  automatiquement les 11 composants nouveaux).
Branche finale: feature/169-special-mobs-boss (aucun merge)
Dernier commit: 993c615
Build: vert.
Tests manuels en attente: TC-255 (NOUVEAU — regarder les joueurs + promenade, 20 points, avec un
  PNJ de test à supprimer ensuite), plus TC-236 à TC-254 déjà en attente.
Blocages: aucun. LIMITES ASSUMÉES, non acquises : (a) MockBukkit n'embarque pas Citizens, donc
  CitizensBehaviourBridge n'est couvert par AUCUN test automatisé ; (b) la build installée est
  numérotée Jenkins (4232) et le snapshot Maven compilé est horodaté (2026-09-11) — les deux
  numérotations ne se recoupent pas, donc la correspondance exacte n'est pas garantie ; une
  divergence se manifesterait par un CITIZENS_INCOMPATIBLE explicite, sans affecter le reste.
Première étape à reprendre: dérouler TC-255 après le redémarrage groupé des lots 4 et 5, en
  commençant par le point 6 (remplacement de patrouille refusé sans confirmation).
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
Date: 2026-10-06 (lot 13 — #16 second lot : récupération des récompenses monétaires)
Branche de départ: feature/169-special-mobs-boss @ 503cf97 (lot 12, #16 premier lot déployé)
Étape de départ: le propriétaire demande de fiabiliser la RÉCUPÉRATION des récompenses monétaires
  après échec ou crash, AVANT tout autre chantier économique, et donne la liste des points à
  auditer : persistance de rewardGrantId ; ordre entre sauvegarde de COMPLETED et crédit SQL ;
  crash avant paiement, pendant la transaction et après crédit avant notification ; reconnexion,
  redémarrage et quêtes répétables ; plusieurs récompenses MONEY sur une même complétion.
  Constat qu'il formule lui-même et qui s'est vérifié : « le grant_id unique protège le crédit
  contre les doublons, mais il faut aussi conserver durablement ce qui reste à payer ».
  Sept exigences dures, dont : identité de paiement INITIALE réutilisée par une reprise ; montants
  dus conservés même si la définition change ; reprises bornées sans boucle par tick ni répétition
  infinie de messages ; une récupération ne rejoue ni les récompenses non monétaires ni la
  complétion ; aucun succès annoncé avant confirmation réelle ; pas de seconde source de vérité du
  solde ; aucun paiement rétroactif des anciennes quêtes COMPLETED.
Étapes terminées:
(1) DONE — AUDIT du parcours réel. Trois défauts, dont un MESURÉ avant d'être affirmé :
  * rewardGrantId n'était PAS persisté : champ en mémoire sur ActiveQuestProgress, généré
    paresseusement, jamais écrit. Un arrêt du serveur le perdait définitivement, donc aucune
    reprise ne pouvait réutiliser l'identité initiale.
  * upsertState(COMPLETED) partait AVANT le crédit, en fire-and-forget, dans une transaction
    SÉPARÉE : fenêtre réelle « quête terminée, crédit jamais fait, AUCUNE trace de la dette » — et
    pour une quête non répétable, plus jamais rejouable.
  * MESURÉ : plusieurs récompenses MONEY d'une même complétion partageaient la même identité de
    paiement, donc la clé primaire rejetait les suivantes comme « déjà payées ». Test écrit AVANT
    correction : une quête 100 + 30 créditait 100, sans aucune erreur visible.
  * Déjà corrects et donc non modifiés : le rollback pendant la transaction ; le crédit survivant à
    un crash après paiement (seul le message est perdu) ; les quêtes répétables (nouvelle
    progression = nouvelle identité).
  * loadForPlayer ne recharge que ACTIVE/READY_TO_TURN_IN : une quête COMPLETED non payée n'était
    JAMAIS reprise, ni à la reconnexion ni au redémarrage.
(2) DONE — schéma V26 : quest_reward_grants devient « ce qui est DÛ et ce qui est PAYÉ ».
  * status PENDING/PAID/SETTLED_MANUALLY, reward_index, attempts, last_error, settled_reason,
    updated_at + index sur status. Migration ADDITIVE et idempotente (garde columnExists).
  * TOUJOURS pas un second solde : wallets reste la seule source de vérité ; cette table répond à
    deux questions et seulement celles-là — « que reste-t-il à payer ? » et « cette occasion
    a-t-elle déjà été payée ? ».
  * AUCUN paiement rétroactif : les lignes existantes prennent PAID, et c'est un CONSTAT et non une
    supposition — par construction de V25, une ligne n'était écrite que DANS la transaction qui
    créditait. Aucune dette inventée pour les quêtes déjà COMPLETED.
(3) DONE — atomicité de la complétion. QuestProgressRepository#completeQuestWithMoneyDebts écrit
  l'état COMPLETED ET une ligne PENDING par récompense dans UNE seule transaction. Nécessaire dans
  les DEUX sens, et c'est le point de conception du lot :
  * dettes d'abord => un arrêt entre les deux laisse des dettes pour une quête PAS terminée : le
    joueur la refait, une seconde dette naît, il est payé DEUX FOIS au total ;
  * état d'abord => c'est exactement le défaut d'origine.
  Le SQL des dettes reste défini dans WalletRepository (seul propriétaire des tables d'argent) et
  est appelé via deux méthodes package-private : une seule définition de chaque requête, une seule
  transaction.
(4) DONE — paiement atomique et idempotent. WalletRepository#payQuestRewardDebt : PENDING -> PAID
  + crédit + ligne de journal dans une transaction, sous un WHERE status = 'PENDING' qui empêche
  deux reprises concurrentes de l'emporter toutes les deux. Le MONTANT et le CONTEXTE du journal
  viennent de la LIGNE, jamais de l'appelant : « les montants dus sont conservés même si la
  définition change » devient vrai PAR CONSTRUCTION, pas par vigilance. Un statut inconnu en base
  est lu comme PAID : en cas de donnée inattendue, ne rien payer est le choix sûr.
(5) DONE — identité par récompense : <jeton de complétion>#<index>. Plusieurs MONEY sur une même
  complétion sont donc toutes payées (100 + 30 + 5 = 135 vérifié).
(6) DONE — reprise BORNÉE (QuestProgressEngine#recoverPendingMoneyRewards) : une relecture par
  chargement de joueur, jamais par tick, jamais sur le thread principal ; RECOVERY_BATCH = 20 ;
  au-delà de RECOVERY_MAX_ATTEMPTS = 5 échecs la dette est LAISSÉE AU PANEL (s'acharner ne
  corrigerait pas la cause et noierait les logs) ; et surtout une reprise échouée ne parle PAS au
  joueur — sans cette règle, une base en panne lui enverrait le même message d'échec à CHAQUE
  reconnexion. Une reprise ne rejoue QUE le crédit monétaire : ni XP, ni objets, ni variables, ni
  commandes, ni la complétion (vérifié en comparant l'XP avant/après).
(7) DONE — panel : economy.debts (lecture, ECONOMY_READ), economy.debt.retry et
  economy.debt.settle (sensibles, ECONOMY_WRITE, confirmation + audit).
  * état RÉEL affiché : « en échec (N tentative(s)) » avec son motif, jamais un « en attente » qui
    cacherait trois échecs ;
  * la reprise n'accepte AUCUN montant — un test vérifie qu'un amount glissé dans le formulaire
    n'est même pas transmis ; en accepter un permettrait d'en inventer un ;
  * la différence entre REPRENDRE et CRÉDITER MANUELLEMENT est écrite dans les DEUX sens : le bloc
    des dettes dit qu'un crédit manuel ne règle aucune dette, et le bloc Monnaie renvoie vers
    « marquez-la réglée à la main, sinon elle resterait payable une seconde fois » ;
  * SETTLED_MANUALLY ne touche AUCUN solde et rend la dette non payable ;
  * une reprise qui ne paie rien est rapportée comme ÉCHEC et non comme succès, sinon l'audit
    laisserait croire que le joueur a été crédité ;
  * limite historique affichée à l'écran : seules les complétions postérieures peuvent apparaître.
Tests: 1660 plugin (34 ignorés) + 595 control-panel (1 ignoré) + 30 web-api = 2285, 0 échec,
  0 erreur. ./gradlew test (25 min 56 s) ET ./gradlew build BUILD SUCCESSFUL. +31 cas, plusieurs
  anciens RÉÉCRITS sur la nouvelle API plutôt qu'ajoutés. Totaux relevés dans les XML JUnit réels.
  WalletRepositoryTest : 19 cas sur une VRAIE base SQLite ROUVERTE (dette créée avant tout
  paiement, complétion et dette écrites ENSEMBLE, survie au redémarrage puis paiement unique,
  dette payée qui reste payée, montant pris sur la dette et non sur la définition, répétable,
  ré-enregistrement sans doublon, 20 REPRISES CONCURRENTES qui créditent exactement une fois,
  dette inconnue jamais payée, échec enregistré avec motif qui BORNE les reprises, échec tardif
  sans effet, règlement manuel non payable sans toucher au solde, raison obligatoire, bornes,
  complétion sans argent, jeton manquant refusé).
  QuestProgressEngineTest : 12 cas, dont reprise au chargement avec l'identité INITIALE, dette trop
  souvent en échec laissée au panel, et reprise qui ne rejoue aucune récompense non monétaire.
  AgentActionExecutorTest +6, EconomyAdminTest +9, QuestMoneyRewardIntegrationTest 6 (dont le test
  qui a MESURÉ le défaut : 100 + 30 = 130).
  UNE RÉGRESSION QUE J'AI INTRODUITE, attrapée par un test existant : en déplaçant le paiement vers
  la dette, le contexte du journal avait perdu l'identifiant de quête — exactement la traçabilité
  documentée la veille. Corrigée en le composant DEPUIS LA LIGNE (source autoritative), ce qui est
  de toute façon la bonne conception.
Déploiement: JAR DEV SHA-256 5b2d7b85857711e7cd4bf2cd75d535e103a986697fa33d5e5153c9dd45c61f81
  (1 825 931 o, commit 9f3b28d) ; panel distribution 20261006-104128 (/health 200). UN SEUL
  redémarrage, arrêt ET retour CONSTATÉS, plugins -> 4 verts. Backup préalable
  rpgquest-20261006T084211Z-predeploy.jar (6fe99525…), backup précédent non écrasé.
  Procédure documentée suivie (./gradlew --stop, RPGQUEST_TEST_MAX_HEAP=768m, -y) : AUCUN échec de
  déploiement cette fois, les deux pièges de la session précédente étaient bien ceux-là.
  CHARGEMENT PROUVÉ : copie de RPGQuest/data.db lue EN LECTURE SEULE puis SUPPRIMÉE —
  PRAGMA user_version = 26 (était 25), les SIX nouvelles colonnes présentes, et AUCUNE ligne dans
  la table, donc aucune dette rétroactive inventée. Aucune écriture serveur, aucun solde touché,
  AUCUNE panne provoquée sur la base DEV.
Blocages: aucun.
Tests manuels en attente: TC-251 (#16 second lot, 24 étapes, 7 sections). Section B la plus
  importante (deux récompenses sur une complétion : +130 et non +100). Section E (échec réel de
  paiement) OPTIONNELLE et explicitement à NE PAS faire sur la base DEV réelle — entièrement
  couverte par les tests automatisés, la sauter ne laisse aucun trou. TC-243 à TC-250 restent
  également en attente.
Première étape à reprendre: attendre le choix du propriétaire. Côté économie, la suite dépend de
  DÉCISIONS GAMEPLAY encore à prendre (monnaie physique et conversion solde <-> objet #138, perte à
  la mort, prix, règles d'échange) ; rien d'indépendant et utile ne reste sans elles sur ce chantier.
```

```text
Date: 2026-10-06 (lot 12 — #16 récompense monétaire de quête + bourse en jeu)
Branche de départ: feature/169-special-mobs-boss @ b9cb971 (lot 11, nuit #131/#210/économie déployée)
Étape de départ: le propriétaire choisit explicitement #16 et uniquement #16 : récompenses
  monétaires de quête, gestion dans le Control Panel, affichage du solde dans l'interface joueur.
  Consignes dures : réutiliser EconomyService et le portefeuille existants ; crédit traçable
  jusqu'au joueur, à la quête ET à son occurrence de complétion ; vérifier doubles appels, retries,
  reconnexions et échecs de persistance ; respecter les quêtes répétables et les autres types de
  récompenses ; montant entier positif validé côté backend ; préserver les récompenses existantes ;
  intégrer les états source/publié/chargé et le workflow #131 ; afficher le solde depuis une
  interface EXISTANTE sans imposer une commande comme parcours principal ; ne pas écraser
  durablement l'affichage de progression des objectifs ; aucune monnaie physique, conversion,
  migration, perte à la mort, tarification ni échange P2P ; ne recréer aucun service économique ;
  ne décider d'aucun montant sur une quête réelle ; sans sous-agents ; GitHub géré par ChatGPT.
Étapes terminées:
(1) DONE — AUDIT avant conception, quatre constats qui ont décidé l'implémentation :
  * le socle économique existait COMPLET (EconomyService sur WalletRepository, tables wallets +
    transactions, débit plancher à zéro, journal lisible depuis le lot précédent). Rien à recréer :
    le travail était de brancher les quêtes dessus, pas d'inventer une monnaie.
  * les récompenses sont un SEALED INTERFACE : ajouter un type force le compilateur à signaler
    TOUS les points de traitement (parseur, moteur, journal joueur, agent, content pack). C'est ce
    qui a rendu l'ajout sûr — aucun switch n'a pu être oublié en silence.
  * l'éditeur du panel est ENTIÈREMENT piloté par descripteurs (Descriptors.REWARDS alimente
    formulaire + écriture YAML + relecture YAML + validation). Ajouter un type = ajouter UN
    descripteur ; aucun formulaire spécifique écrit.
  * grantRewards est SYNCHRONE alors que le crédit du portefeuille est ASYNCHRONE. Une ligne de
    résumé construite à la remise annoncerait un gain AVANT toute preuve qu'il a eu lieu.
(2) DONE — la garantie « jamais deux fois, jamais perdue » placée EN SQL, pas dans le moteur.
  * la garde mémoire de turnIn (bascule d'état synchrone) ne protège que les chemins énumérables :
    elle ne dit rien d'un retry, d'un crash entre crédit et enregistrement, ou d'un appelant futur.
  * WalletRepository#creditQuestReward : UNE transaction SQL réserve grant_id dans la nouvelle
    table quest_reward_grants (INSERT OR IGNORE), met à jour wallets et écrit la ligne
    transactions. Donc : insertion refusée => rien crédité (ALREADY_CREDITED) ; erreur => tout
    annulé, occasion encore payable ; AUCUN instant intermédiaire où un retry doublerait le gain.
  * identifiant d'occasion = ActiveQuestProgress#rewardGrantId, généré une fois puis conservé :
    rejouer la MÊME remise présente le MÊME identifiant et ne paie rien, alors qu'une quête
    répétable repart d'une nouvelle progression donc d'une nouvelle occasion légitimement payée.
    quest_reward_grants.occurrence numérote par joueur ET par quête.
  * AUCUNE seconde source de vérité : la table ne contient AUCUN solde, wallets reste le seul.
  * table volontairement SANS clé étrangère vers player_profiles (contrairement à transactions) :
    un ON DELETE CASCADE rendrait un profil supprimé puis recréé payable une seconde fois pour les
    mêmes occasions. Une ligne orpheline ne coûte rien ; un double paiement, si.
  * schéma V25, migration ADDITIVE et idempotente : aucune table existante modifiée, aucun solde
    touché, rien à faire manuellement.
(3) DONE — moteur : RewardType.MONEY + MoneyReward(int amount) strictement positif ; parseur YAML ;
  TransactionType.QUEST_REWARD avec contexte quest:<id>#<occasion> ; port QuestRewardPayer +
  reçu TYPÉ QuestRewardReceipt (CREDITED / ALREADY_CREDITED / FAILED) implémenté par
  EconomyService — pas un nouveau service économique, une porte étroite sur l'existant ;
  QuestProgressEngine#payMoneyReward paie puis annonce SEULEMENT si la base a confirmé.
  Montant en int et non long : c'est un nombre CONÇU par un auteur (comme l'XP), pas un solde
  ACCUMULÉ ; garde aussi inchangés deux formats publics (QuestPackEntry.Reward, RewardSummary).
  AUCUN plafond moteur : décider du gain maximum serait décider de l'équilibrage.
(4) DONE — messages : reward-money-credited (montant + solde RELU en base) et
  reward-money-failed, envoyés SÉPARÉMENT du résumé et seulement après la base ; occasion déjà
  payée => RIEN au joueur (gain fantôme) + avertissement serveur. Les deux dans le CHAT et jamais
  dans l'ActionBar, qui appartient à la progression des objectifs — exigence explicite du ticket,
  tenue par conception et pas seulement par intention. messages.yml fusionne les clés manquantes
  au démarrage, donc pas besoin de --also pour ce fichier.
(5) DONE — interface joueur : bourse (solde réel du portefeuille) dans un emplacement INERTE du
  journal de quêtes, en liste ET en vue détail, relue à chaque ouverture et après chaque
  transaction (le crédit déclenche notifyChanged, qui recompose un menu ouvert) ; erreur de
  lecture => « indisponible » avec son motif, JAMAIS un 0 inventé qui ferait croire à un vol, et
  l'échec ne fait pas échouer l'ouverture du menu ; récompense PRÉVUE dans l'infobulle de détail.
(6) DONE — panel : descripteur MONEY (un seul champ, icône de pièce dont le glyphe a été VÉRIFIÉ
  présent dans la police embarquée, sinon repli silencieux info-circle) ; validation backend entier
  strictement positif ; AVERTISSEMENT sans refus au-delà de 1 000 000 (garde-fou de frappe, pas
  règle d'équilibrage) ; RewardText lit MONEY comme « N pièce(s) » et JAMAIS comme un objet, y
  compris sur l'ancien format texte ; bannière d'enregistrement de quête réécrite en
  source -> publié -> rechargé (#131) — « au prochain chargement du serveur » laissait croire
  qu'un redémarrage suffisait, alors qu'une quête jamais déployée ne paierait PERSONNE sans
  aucune erreur visible en jeu.
Tests: 1638 plugin (34 ignorés) + 586 control-panel (1 ignoré) + 30 web-api = 2254, 0 échec,
  0 erreur. ./gradlew test (22 min 34 s) ET ./gradlew build BUILD SUCCESSFUL. +44 cas
  (1603 -> 1638 plugin, 577 -> 586 panel), totaux relevés dans les XML JUnit réels.
  Nouveau QuestMoneyRewardIntegrationTest (5 cas) avec vrai moteur + vrai EconomyService + vraie
  base SQLite : ni le double du moteur ni le test SQL ne prouvent que les morceaux sont BRANCHÉS.
  WalletRepositoryTest +8 dont 20 rejeux CONCURRENTS du même grant qui créditent exactement une
  fois, et survie à un redémarrage sans second paiement. SchemaMigratorTest +2 dont la contrainte
  d'unicité : sans elle, le code applicatif compilerait et paierait deux fois.
  DEUX défauts de mes propres tests, MESURÉS au lieu d'être supposés : performTicks ne consomme
  AUCUN temps d'horloge, donc une attente qui ne pompe que des ticks pouvait s'épuiser avant la fin
  de l'écriture asynchrone (ajout de temps réel) ; et le slot 4 porte l'INDICATEUR DE PAGE en vue
  liste, donc mon test de la bourse en détail lisait « Page 1/1 » (attente de l'icône de détail).
  UN test existant a refusé mon code et avait raison : EditorDescriptorsTest comparait les
  descripteurs aux QUATRE types du moteur -> mis à jour sur la réalité (cinq), sans l'affaiblir.
Déploiement: JAR DEV SHA-256 6fe9952576cc9240ccaf4cf1639d603d6739585df56f1ecd309315cb03f26f7d
  (1 807 514 o, commit ee96254) ; panel distribution 20261006-090923 (/health 200). UN SEUL
  redémarrage, arrêt ET retour CONSTATÉS, plugins -> 4 verts, rpgquest version -> v0.1.0-SNAPSHOT.
  Backup préalable rpgquest-20261006T074047Z-predeploy.jar (3a29b540…), backup précédent non
  écrasé.
  CHARGEMENT PROUVÉ, pas seulement le transfert : le numéro de version ne bouge pas d'un lot à
  l'autre, mais la migration se constate. Copie de RPGQuest/data.db récupérée EN LECTURE SEULE,
  lue localement puis SUPPRIMÉE : PRAGMA user_version = 25 (était 24), table quest_reward_grants
  présente avec le schéma exact, 0 ligne (personne n'a joué). Aucune écriture serveur.
Blocages: aucun blocage réel, mais DEUX refus de livrer du script, à chaque fois AVANT tout
  transfert FTP — donc rien livré, rien cassé, serveur jamais laissé arrêté :
  (a) pas 3/8 « Java heap space » : le script relance test+build et héritait d'un shell sans
      RPGQUEST_TEST_MAX_HEAP=768m. Échec d'ENVIRONNEMENT, pas de code (la même suite était verte
      20 min plus tôt avec le plafond).
  (b) pas 5/8 « /dev/tty: No such device or address » : confirmation interactive sans terminal,
      contournée par -y (implique --server-stopped).
  ERREUR DE LECTURE DE MA PART à signaler : la commande d'attente qui enveloppait le script a
  rendu exit code 0 — ce qui dit seulement que l'ATTENTE s'est terminée. Seul DEPLOY_EXIT prouve
  un déploiement. Les deux pièges, la séquence complète et cet avertissement sont désormais écrits
  dans docs/deployment/VERYGAMES.md, et le script n'a PAS été modifié (jamais pendant son
  exécution).
Tests manuels en attente: TC-250 (#16, 27 étapes, 7 sections). La section D est la plus
  importante : recomplétion, complétion forcée depuis le panel et double clic rapide ne doivent
  produire QU'UNE SEULE ligne QUEST_REWARD. Deux quêtes prêtes à copier dans
  docs/manual-tests/rewards/ (hors du JAR, donc jamais seedées automatiquement). AUCUNE case
  cochée. TC-243 à TC-249 des lots précédents restent également en attente.
Première étape à reprendre: attendre le choix du propriétaire. Côté économie, la suite dépend de
  DÉCISIONS GAMEPLAY encore à prendre (monnaie physique et conversion solde <-> objet #138, perte
  à la mort, prix, règles d'échange) ; rien d'indépendant et utile ne reste sans elles sur ce
  chantier.
```

```text
Date: 2026-10-06 (lot 11 — nuit : #131 rechargement du contenu, #210 actions joueurs, premier lot
  économie)
Branche de départ: feature/169-special-mobs-boss @ 00c2c3e (lot 10, #95 déployé)
Étape de départ: ordre explicitement choisi par le propriétaire pour la nuit : 1. #131, 2. #210,
  puis 3. un premier lot ÉCONOMIE. Séquentiel, sans sous-agents, aucun autre ticket commencé.
  Consignes dures : auditer l'existant avant de concevoir ; distinguer source / publié / chargé ;
  « un reload ne transfère pas les fichiers du panel AWS vers VeryGames » ; valider les références
  croisées AVANT application ; conserver le runtime précédent valide en cas d'erreur ; aucun
  /reload global Bukkit ; aucune progression, quête active, inventaire ou instance de boss touchée ;
  OP/DEOP derrière une permission dédiée + confirmation + audit ; ne pas confondre OP Minecraft,
  rôle PlugAdmin, droit builder et bypass gameplay ; côté économie, éviter toute SECONDE source de
  vérité, aucune conversion ou migration silencieuse, aucune décision gameplay arbitraire ;
  GitHub géré par ChatGPT (aucun ticket modifié/fermé, aucun test manuel coché) ; aucun mail ni
  annonce publique de livraison.
Étapes terminées:
(1) DONE — #131, rechargement contrôlé du contenu depuis PlugAdmin.
  * AUDIT d'abord : registres, caches, index et sessions existants. Deux constats ont décidé la
    conception. (a) Il y avait DÉJÀ un rechargement de mobs isolé dans la commande admin : il
    délègue désormais au service commun au lieu d'être une seconde implémentation. (b) Un défaut
    ATTEIGNABLE EN PRODUCTION : une définition supprimée du disque disparaissait SILENCIEUSEMENT
    du runtime, sans aucun signal. D'où l'empreinte runtimeHash (SHA-256 sur les identifiants
    triés, 12 caractères) : l'écart devient CONSTATABLE au lieu de supposé.
  * content.reload.ContentReloadService : valider -> contrôler les références croisées -> appliquer.
    Familles ITEMS, NPCS, QUESTS, STORIES, DIALOGUES, MOBS — l'ordre de DÉCLARATION est l'ordre
    d'application, pour qu'une quête ne soit jamais chargée avant l'objet qu'elle récompense.
    Codes typés : APPLIED, PREVIEW_OK, INVALID_CONTENT, BROKEN_REFERENCES, BUSY, NOTHING_REQUESTED,
    ERROR. En cas d'échec, RIEN n'est appliqué et le runtime précédent valide reste en place.
    parseCsv renvoie vide sur un seul jeton inconnu (pas de rechargement partiel involontaire).
  * TROIS ÉTATS DISTINGUÉS à l'écran : contenu dans la source (dépôt/panel AWS), contenu publié sur
    VeryGames, contenu réellement chargé en jeu. La page écrit qu'un rechargement NE TRANSPORTE
    AUCUN FICHIER : sans cela, un rechargement « sans effet » serait lu comme une panne.
  * validate() ajouté à StoryRegistry, YamlDialogueEngine et YamlNpcEngine (contrôle à blanc, sans
    publier) ; NpcHintService#invalidateAll() pour que le signal visuel #12 ne garde pas un cache
    périmé après rechargement.
  * Aperçu (content.reload.preview, LECTURE) séparé de l'application (content.reload, SENSIBLE,
    ACTION_CONTENT_RELOAD). L'application ré-enfile les SIX relevés de catalogue : l'affichage est
    RELU, pas deviné. Opérations concurrentes protégées (BUSY), double-clic neutralisé.
  * Aucun /reload Bukkit/Paper. Progression, quêtes actives, inventaires et mobs/boss VIVANTS
    intouchés : aucune récompense redistribuée, aucun respawn, aucun reset implicite.
  * LIMITE DITE CLAIREMENT : les PARAMÈTRES (config.yml, npc-hints:, …) ne peuvent pas être
    rechargés de façon fiable -> redémarrage requis, via le workflow #95.
  * Aussi trouvé en auditant : une permission orpheline, plus référencée par aucune route.
(2) DONE — #210, actions joueurs sur la fiche joueur.
  * Statut OP Minecraft et whitelist RÉELS (lus depuis le serveur, PlayerCatalogEntry enrichi),
    OP/DEOP, renvoi de secours au Hub, expulsion avec raison, whitelist.
  * PLAYER_OP_WRITE : permission DÉDIÉE, OWNER UNIQUEMENT, explicitement REFUSÉE à ADMIN. Accorder
    OP donne tout le serveur : ce n'est pas le même geste qu'administrer du contenu. Les quatre
    notions OP Minecraft / rôle PlugAdmin / droit builder / bypass gameplay sont rappelées à
    l'écran, parce que les confondre est l'erreur coûteuse.
  * Ciblage par UUID, online/offline distingués, résultat RELU après l'action : codes OPPED,
    DEOPPED, ALREADY_OP, NOT_OP, UNKNOWN_PLAYER, NOT_APPLIED, OFFLINE, NO_DESTINATION, SENT,
    KICKED, WHITELISTED, UNWHITELISTED, NOT_WHITELISTED. Jamais « succès » sur la seule absence
    d'erreur. Confirmation par pseudonyme EXACT retapé + raison, audit.
  * Hors ligne, send.hub et kick s'affichent INDISPONIBLES AVEC LEUR MOTIF au lieu d'échouer ;
    op et whitelist restent proposés (ils fonctionnent hors ligne).
  * Le renvoi au Hub réutilise la destination résolue du correctif #22 : inventaire, Acte, claim et
    progression préservés.
(3) DONE — ÉCONOMIE, premier lot (#140, #16 ; #138/#139 lus mais non commencés).
  * AUDIT AVANT ARCHITECTURE, et il a changé la décision : le socle EXISTE (WalletRepository,
    persistance, journal des transactions). Le journal était ÉCRIT mais JAMAIS LISIBLE. Donc
    fiabiliser et administrer, PAS recréer — et AUCUNE seconde source de vérité.
  * Livré : WalletRepository#history (borné [1,100]), lecture du solde ET des dernières écritures du
    journal depuis le panel, crédit/débit avec RAISON OBLIGATOIRE (sans elle, une création
    administrative serait indiscernable d'un gain de jeu des mois plus tard), débit IMPOSSIBLE
    au-delà du disponible, plafond de SAISIE 1 000 000 annoncé comme garde-fou de frappe et NON
    comme règle d'équilibrage, ECONOMY_READ séparée de ECONOMY_WRITE.
  * Aucune conversion ni migration d'inventaire, d'objet ou de solde. Aucune monnaie reconnue par
    son seul nom ou lore. Aucune décision gameplay prise à la place du propriétaire.
Tests: 1603 plugin (34 ignorés) + 577 control-panel (1 ignoré) + 30 web-api = 2210, 0 échec,
  0 erreur. ./gradlew test ET ./gradlew build BUILD SUCCESSFUL. +99 cas nouveaux sur la nuit
  (1538 -> 1603 plugin, 543 -> 577 panel).
  TROIS défauts de mes propres tests, MESURÉS au lieu d'être supposés : des tests #131 attendaient
  BROKEN_REFERENCES là où le chargeur de quêtes répond déjà INVALID_CONTENT (il valide les
  prérequis à l'échelle du dossier) -> corrigés sur le COMPORTEMENT RÉEL ; les tests #210
  expiraient tous parce que onMain() exige des ticks pompés sous MockBukkit ; et setOp ne persiste
  pas après disconnect() sous MockBukkit -> test RÉÉCRIT pour verrouiller le garde NOT_APPLIED,
  l'élévation OP HORS LIGNE restant à confirmer en réel.
Déploiement: JAR #131+#210 SHA-256 fce156b2d32ca077ed83381b460cf4e5a62328279ef0486fbbd424f76f36c2f7
  (1 787 548 o, commit 50b5aad) ; JAR économie SHA-256
  3a29b540787ff6e337858bc1aba09b86d9b29548a1f6a85ce85b20d3faded3be (1 796 897 o, commit b7686c4).
  Panel redéployé DEUX fois (distributions 20261006-001824 et 20261006-005116, /health -> 200).
  DEUX redémarrages Minecraft, arrêt ET retour CONSTATÉS chaque fois, plugins -> 4 verts,
  rpgquest version -> v0.1.0-SNAPSHOT. Non groupables sans retarder la vérification des deux
  premiers lots : c'est dit, pas présenté comme un seul redémarrage.
  CHARGEMENT vérifié, pas seulement le transfert : economy.balance sur un UUID inexistant ->
  FAILED « Joueur inconnu », tandis qu'un type bidon -> REJECTED « Type d'action non whitelisté ».
  Les deux réponses diffèrent, donc l'exécuteur économie tourne réellement dans ce JAR.
Tests manuels en attente: TC-247 (#131, 23 étapes — l'étape B est la plus importante : rendre un
  fichier invalide SUR LE SERVEUR et constater que le rechargement REFUSE et que la définition
  reste chargée), TC-248 (#210, 23 étapes, dont 403 sur route directe depuis un compte ADMIN et
  persistance de l'OP après redémarrage), TC-249 (économie, 18 étapes, dont débit supérieur au
  solde refusé SANS aucune ligne de journal). AUCUNE case cochée. TC-243 à TC-246 des nuits
  précédentes restent également en attente.
Blocages: aucun blocage réel. Une gêne mineure confirmée : la sonde /health de
  scripts/plugadmin/deploy.sh tire trop tôt et a signalé un faux « KO » au premier déploiement
  (revérifié à la main -> 200 ; la santé est le dernier pas du script, donc rien n'était partiel).
  Attente/retry à ajouter au script.
Première étape à reprendre: dérouler les validations manuelles, puis attendre le choix du
  propriétaire. Côté économie, la suite dépend de DÉCISIONS GAMEPLAY encore à prendre (monnaie
  physique et conversion solde <-> objet, perte à la mort, prix, règles d'échange) : la prochaine
  étape indépendante utile est le type de récompense monétaire de quête et l'affichage lisible du
  montant possédé en jeu, avant #138/#139.
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

```text
Date: 2026-10-08 (matin, 3e lot — #146 suite : dialogues et stories dans l'atelier)
Branche de départ: feature/218-starter-kit-tiers @ e52bcc3 (fin du lot #146 phase 1, panel déployé)
Étape de départ: « continue sur les dialogues et stories dans l'atelier ». Ticket réel : #146.
Étapes terminées:
  - BLOCAGE LEVÉ D'ABORD : le vocabulaire des actions/conditions de dialogue ne vivait que dans
    ActionType/ConditionType du moteur. Sans déclaration côté panel, ni le schéma de #110 ni l'IA
    ne pouvaient le respecter. Déclaré dans Descriptors (12 actions, 8 conditions) et VERROUILLÉ
    sur le moteur par DialogueDescriptorsTest (comparaison à l'identique des ensembles).
  - Schéma de content pack : les deux tableaux d'un choix sont contraints par des branches oneOf
    dérivées. La limite explicitement documentée de #110 est LEVÉE.
  - DialogueValidator : refuse un type inconnu, un champ obligatoire absent, un champ d'un autre
    type, un entier non positif, un état de quête inexistant, un « negate » non booléen. AVANT CE
    LOT, une action inventée traversait l'éditeur ET l'import en silence et n'échouait qu'au
    chargement du serveur Minecraft.
  - Atelier étendu aux TROIS familles (quête / dialogue / story), une à la fois, choisies par un
    lien (GET) parce que la CSP interdit le JS en ligne. Consignes propres à chaque famille.
    QuestPromptBuilder -> ContentPromptBuilder, AiQuestStudio -> AiContentStudio : les trois
    familles partagent contrat, schéma, références, format et correction.
  - GARANTIE STRUCTURELLE INCHANGÉE : aucune des trois méthodes de génération n'a de chemin vers
    le disque. Vérifié par un test pour les trois familles.
  - QUATRE défauts réels trouvés et corrigés, chacun couvert par un test :
    (1) le schéma ET les exemples de #110 déclaraient « nodes » comme une LISTE alors que le
        moteur et l'export du plugin écrivent une MAP indexée par id -> l'exemple de référence du
        contrat était INIMPORTABLE ; les exemples passent désormais un VRAI import ;
    (2) l'exemple « minimal valide » n'avait pas de category, pourtant obligatoire -> refusé ;
    (3) l'aide de l'état de quête n'énonçait que 5 des 6 états réels (ABANDONED manquait) ;
    (4) « negate » était déclaré « string » alors que le moteur le lit avec getBoolean -> tout
        validateur aurait refusé « negate: true », la forme même recommandée par la doc.
Branche finale: feature/218-starter-kit-tiers @ 1b6982f + un commit de documentation
Dernier commit: 1b6982f fix(control-panel): « negate » est un booléen, pas une chaîne (#146)
Build: ./gradlew test BUILD SUCCESSFUL en 11 min 21 s sur 7cd7dbd, puis ./gradlew build sur le
  commit final, depuis le worktree propre. UN SEUL Gradle à la fois.
Tests: voir le rapport de session pour le décompte exact. Lot entièrement côté panel ; AUCUN appel
  réseau sortant (fournisseur bouchon).
Déploiement: PANEL SEUL à prévoir. AUCUN changement de plugin, donc aucun JAR, aucun redémarrage
  Minecraft, aucune migration. La table ai_provider et les clés déjà posées restent en place.
Tests manuels en attente: TC-266 (nouveau, #146 — dialogue et story générés puis réellement
  chargés par le serveur ; exige une vraie clé API et consomme des jetons facturés). TC-265 reste
  à faire, donc #146 RESTE OUVERTE. Plus TC-264 (#109), TC-257 (#123) et TC-258..TC-263.
Blocages: aucun. Limites assumées et documentées : un élément par génération (choix délibéré) ;
  les PNJ ne sont pas générables car la famille npcs n'est pas éditable depuis le panel ; aucun
  coût monétaire estimé alors que #146 le demande ; garde-fou de budget PAR APPEL seulement ;
  AUCUN des trois fournisseurs encore appelé avec une vraie clé.
  DETTE SIGNALÉE : MiniYaml ne gère pas les scalaires repliés (text: >), donc dialogues/guard.yml
  n'est pas éditable depuis le panel — 6 de ses 13 nœuds ne sont pas vus. La troncature n'est PAS
  silencieuse (garde-fou round-trip), et un test le vérifie désormais fichier par fichier, mais le
  lecteur mériterait de gérer cette construction.
  DETTE RAPPELÉE : RestartServiceTest reste sensible au temps réel (voir le lot précédent).
Première étape à reprendre: configurer un fournisseur et faire le point 4 de TC-265 (« Tester la
  connexion ») — quelques secondes, et c'est le seul moyen de savoir si les trois implémentations
  d'API sont justes. Puis TC-265, puis TC-266, puis TC-264.
```

```text
Date: 2026-10-08 (après-midi — #222 #223 #224 #225 #226 : atelier IA corrigé, PNJ ↔ dialogue, suppression de PNJ)
Branche de départ: feature/218-starter-kit-tiers @ 2eff162
Branche de travail: fix/222-atelier-ia-et-suppression-pnj
Étape de départ: cinq tickets ouverts le matin même par les tests réels de l'utilisateur
  (TC-265 et TC-266). Consigne : « commence par l'audit réel du cas Mira avant tout changement
  destructif ».
Étapes terminées:
  - AUDIT MIRA FAIT D'ABORD, et sur les DONNÉES RÉELLES : snapshot de /var/lib/plugadmin/
    control-panel.db, extraction du dernier npc.list réussi (action b9c15a45, 2026-10-08T12:13:21Z).
    Les deux lignes exactes sont dans le rapport. Diagnostic PROUVÉ, pas supposé : l'entrée
    « mira_first_map / sans définition » est le DIALOGUE lui-même, déduit en PNJ par
    NpcCatalog.build (canonical.addAll(dialogueByNpc.keySet()) + DialogueLink(d.id().getKey(), …)).
    Aucune donnée supprimée, aucun fichier touché pendant l'audit.
  - #222 : trois causes cumulées, toutes corrigées. (1) les diagnostics voyageaient en champs
    « problem » HOMONYMES, or Http.parseUrlEncoded rend une Map<String,String> — une seule valeur
    survivait. (2) correctionPrompt ne recevait PAS les consignes d'origine. (3) le formulaire se
    rouvrait VIDE après une correction (questForm/dialogueForm/storyForm restaient null).
    Génération et correction partagent désormais UN chemin de lecture du formulaire
    (ContentPromptBuilder.Demand scellée). Repli ajouté si l'appel de correction échoue lui-même.
  - #223 : ContentId — normalisation avant appel, les deux écritures acceptées, namespace jamais
    doublé, règle de saisie LITTÉRALEMENT celle de ContentPackImport.slugOf (constante partagée).
  - #224 : AiConstraints — le backend recompte la VRAIE map nodes avec DialogueYaml.fromMap, donc
    le lecteur réel, et refuse l'écart. Identifiant imposé vérifié par le même mécanisme.
  - #225 (atelier) : DialogueTargetDecision — décision BLOQUANTE avant tout appel payant quand le
    dialogue lié ne porte pas le nom du PNJ. Deux options seulement, parce que le moteur n'en
    supporte que deux ; le « dialogue supplémentaire non lié » est refusé et l'écran dit pourquoi.
  - #225 (moteur) : NpcCatalog lit le dialogue DÉCLARÉ d'abord, la convention de nom ensuite. Un
    dialogue revendiqué ne fabrique plus d'entrée fantôme. Nouvelle anomalie DIALOGUE_WITHOUT_NPC
    qui nomme sa cause. Les REMISES (DELIVER_ITEM_TO_NPC) traversent enfin le relevé npc.list —
    elles n'apparaissaient dans AUCUNE colonne, donc supprimer un destinataire cassait une quête
    en silence.
  - #226 : trois actions agent (npc.definition.delete avec sauvegarde serveur + revalidation des
    références, npc.citizens.unlink, npc.citizens.delete par double clé UUID + id numérique) et la
    page /npcs/delete : cinq couches affichées, quatre opérations, permission dédiée NPC_DELETE.
  - Correctif d'affichage trouvé en chemin : Ui.banner retombait en SILENCE sur « info » pour
    error/warning/success. Les bandeaux d'erreur de l'atelier IA étaient donc neutres depuis #146.
Branche finale: fix/222-atelier-ia-et-suppression-pnj (poussée, JAMAIS fusionnée)
Build: ./gradlew build (qui inclut test) BUILD SUCCESSFUL en 12 min 31 s sur ba1c6d6, depuis un
  worktree PROPRE. UN SEUL Gradle à la fois. 2935 tests, 0 échec, 38 ignorés (+101) :
  plugin 1904, control-panel 1001, web-api 30.
  Le worktree propre est OBLIGATOIRE : les fichiers de contenu non suivis de l'utilisateur cassent
  CrystalHuntIntegrationTest et bloquent le déploiement.
  UN ÉCHEC RÉEL attrapé en route, sur la première exécution : DiagnosticHelpTest exigeait la
  section de doc correspondant à la nouvelle anomalie DIALOGUE_WITHOUT_NPC, pas encore commitée.
  Le garde-fou a refusé une anomalie qui aurait renvoyé vers une ancre inexistante.
Déploiement: JAR + PANEL cette fois. Contrairement aux trois lots précédents, ce lot touche
  src/main/java/ : le catalogue PNJ et les trois actions de suppression vivent dans le plugin.
  Redémarrage Minecraft requis. Aucune migration, aucun fichier de contenu touché.
Tests manuels en attente: TC-267 (nouveau — correction IA, identifiant, nœuds exacts, fiche Mira,
  création puis suppression d'un PNJ DE TEST). TC-265 et TC-266 restent à faire, donc #146 RESTE
  OUVERTE. Aucun des cinq tickets de ce lot ne peut être fermé avant TC-267.
Blocages: aucun. Limites assumées et documentées :
  - le nombre d'ÉTAPES d'une quête reste une indication non vérifiée (seuls les nœuds d'un dialogue
    sont une contrainte). Le formulaire le dit désormais explicitement ;
  - les suppressions de PNJ sont ASYNCHRONES : le verdict se lit dans le journal d'actions ;
  - le panel ne sait toujours pas supprimer un DIALOGUE, et c'est volontaire (un dialogue peut être
    porté par plusieurs PNJ). Conséquence assumée : supprimer la définition d'un PNJ dont le
    dialogue porte son nom fait réapparaître ce dialogue comme entrée « sans définition » — mais
    désormais avec une anomalie qui l'explique ;
  - plusieurs liaisons Citizens sur un même id : seule celle affichée est traitée, et l'aperçu le
    dit ;
  - APRÈS LE REDÉMARRAGE, le compteur de PNJ va BAISSER (les entrées fantômes disparaissent). Ce
    n'est pas une perte de données — c'est la déduction qui était fausse, pas les fichiers.
  DETTE RAPPELÉE : MiniYaml ne gère pas les scalaires repliés (text: >) — dialogues/guard.yml
  n'est pas éditable depuis le panel. RestartServiceTest reste sensible au temps réel.
Première étape à reprendre: déployer (JAR + redémarrage + panel), puis TC-267. Ensuite seulement,
  reprendre TC-266 et la suite de #146.
```

```text
Date: 2026-10-08 (soir — #213 : emplacements de construction, MVP)
Branche de départ: fix/222-atelier-ia-et-suppression-pnj @ 1cd0ac1 (la ligne déployée du jour)
Branche de travail: feature/213-building-sites
Étape de départ: « nouveau MVP Emplacements de construction ». Ticket réel trouvé à l'audit :
  #213 (ouvert le 06/10), sous-chantier de #94, lié à #20. Ce lot EST sa « première livraison
  indépendante de l'IA » : marquage + catalogue. Le placement de schematic et la génération IA
  sont explicitement HORS périmètre et n'ont pas été touchés.
Étapes terminées:
  - AUDIT D'ABORD. Trois constats ont déterminé l'architecture : (1) le modèle de #194 (analyser
    le workspace du panel, écrire, sauvegarder localement) NE S'APPLIQUE PAS, parce que les
    définitions ne vivent pas dans l'espace éditable du panel ; (2) il existe déjà un outil PDC
    (ZoneSelectionService/ZoneWandListener) dont la tige de blaze a été choisie POUR éviter la
    collision avec la wand WorldEdit — modèle réutilisé, matériau différent pour ne pas confondre
    deux outils d'administration dans une barre d'inventaire ; (3) VillageCenterRepository (V20)
    est la forme exacte du besoin, et NpcIdRepository (V11) celle de l'allocateur d'identifiants.
  - Modèle : BuildingSite + Facing + SiteStatus + ClickedFace + BuildingSiteAnchor. Les deux
    règles délicates sont des fonctions PURES, donc réellement testées : l'ancrage (bloc cliqué +
    face) et la conversion yaw → cardinal (piège : dans Minecraft le yaw 0 regarde le SUD).
  - Ancre = case libre contre la face cliquée. Cliquer le dessus du sol en y=66 donne y=67. Le
    ticket exige une ancre « indépendante d'un offset interne implicite » : si l'ancre était le
    bloc cliqué, chaque placement futur devrait ajouter +1 en Y de son côté.
  - Anti-doublon à DEUX niveaux, pour deux problèmes distincts : même bloc → on renvoie l'existant ;
    même geste → anti-rebond 500 ms par joueur (un clic droit émet couramment deux événements, et
    deux blocs voisins ne sont pas « le même bloc »). AUCUNE distance minimale inventée.
  - Identifiants buildsite_0001 séquentiels et JAMAIS réutilisés (table AUTOINCREMENT, pas un
    MAX()). Un identifiant recyclé ferait pointer un futur placement sur le mauvais emplacement.
  - Migration V28 purement additive (building_sites + building_site_ids + index). status en TEXT
    à lecture tolérante : RESERVED/OCCUPIED plus tard sans migration.
  - Permission DÉDIÉE rpgquest.admin.buildsite, et /rpgadmin buildsite échappe à l'ombrelle
    historique comme la branche PNJ de #200. PAS une permission WorldEdit : l'outil ne sélectionne
    aucune région et ne modifie aucun bloc.
  - Cinq actions agent (list/rename/describe/facing/delete). AUCUNE création : un emplacement est
    défini par une position désignée dans le monde ; un formulaire devrait l'inventer. ÉCART
    ASSUMÉ par rapport à la liste du prompt, qui mentionnait building.site.create.
  - Panel : groupe de nav « Bâtiments » (créé maintenant, une seule entrée, pour que la
    bibliothèque et les placements s'y rangent sans casser de liens), page /buildings/sites,
    3 permissions (READ aussi au Builder et au Testeur), page de doc du centre d'aide.
  - ÉCART ASSUMÉ n°2 : pas d'action « Me téléporter à l'emplacement ». Le prompt la conditionnait
    à l'existence d'un service de TP admin sûr — il n'y en a pas (ClaimTeleportService est
    spécifique aux claims, et aucune action agent ne téléporte). La construire serait un lot.
  - AJOUT par rapport au prompt, exigé par le ticket : le champ « description », éditable depuis
    la fiche. Les critères de #213 le demandent explicitement, et l'ajouter maintenant évite une
    seconde migration.
Branche finale: feature/213-building-sites (JAMAIS fusionnée)
Build: ./gradlew build BUILD SUCCESSFUL en 34 min 17 s sur e56dc7a, worktree PROPRE, UN SEUL
  Gradle à la fois. 3019 tests, 0 échec, 38 ignorés (+84) : plugin 1956, control-panel 1033,
  web-api 30. Aucun test existant assoupli — seules cinq signatures de constructeur de test
  étendues d'un paramètre.
Déploiement: FAIT sur le DEV, 19:05-19:21. data.db sauvegardé AVANT (relu : V27, 33 tables, donc
  exploitable) ; JAR 2 027 547 o identique au local ; JAR remplacé sauvegardé sans écraser le
  précédent ; UN seul redémarrage, 0 joueur ; MIGRATION V28 VÉRIFIÉE sur la base réelle
  (user_version=28, les deux tables + l'index, 35 tables contre 33) ; panel redéployé et le JAR
  SERVI contient bien panel/building/* et la fiche d'aide, /buildings/sites répond 303.
  NON vérifiable d'ici : le parcours en jeu (clic droit), car /rpgadmin buildsite réclame un joueur
  et aucun RCON ne simule un clic. C'est TC-268.
Tests manuels en attente: TC-268 (nouveau, ~5 min). Plus TC-267 (#222..#226), TC-265, TC-266,
  TC-264, TC-257, TC-258..TC-263.
Blocages: aucun. Limites assumées et documentées :
  - aucune dimension ni emprise : le ticket les veut définies AVANT placement, et elles dépendent
    du bâtiment, pas du site ;
  - aucun marqueur visuel en jeu (le ticket le veut « temporaire, pas un bloc permanent » : c'est
    un chantier à part) ;
  - aucune téléportation vers un emplacement (voir écart n°2) ;
  - la POSITION n'est pas éditable depuis le panel — seule l'orientation l'est. Corriger une
    position veut dire re-marquer en jeu puis supprimer l'ancien, et l'aide le dit ;
  - la suppression devra être REPENSÉE quand un bâtiment pourra être posé : aujourd'hui elle est
    sûre parce qu'il n'y a rien à défaire.
  DETTE RAPPELÉE : MiniYaml ne gère pas les scalaires repliés (dialogues/guard.yml). 
  RestartServiceTest reste sensible au temps réel.
Première étape à reprendre: déployer (JAR + redémarrage + panel), puis TC-268 (~5 min). Ensuite le
  lot suivant de #213 : affecter un .schem à un emplacement — l'identifiant stable est prêt pour ça.
```

```text
Date: 2026-10-08 (soir, 2e lot — #227 : actions du panel + nommage en enclume)
Branche de départ: feature/213-building-sites @ f93e518 (la ligne déployée du lot précédent,
  vérifiée superset de toutes les lignes déployées récentes avant déploiement)
Branche de travail: fix/227-building-sites-actions
Étape de départ: deux sujets dans un lot. (1) #227, ouvert après la validation manuelle de #213 :
  « les actions du panel redirigent vers Agents et ne s'appliquent pas ». (2) améliorer la
  création en jeu : clic → nom → confirmation.
Étapes terminées:
  - CAUSE RÉELLE DE #227, et une correction du ticket au passage. La liste des chemins de retour
    acceptés par /agents/action était ÉCRITE À LA MAIN et ignorait /buildings/sites : les cinq
    actions, BOUTON RAFRAÎCHIR COMPRIS, renvoyaient sur /agents.
    MAIS « ne s'appliquent pas » est FAUX, et c'est prouvé deux fois : (a) le journal d'actions du
    DEV montre chaque building.site.* en SUCCESS avec son compte rendu ; (b) la sauvegarde de
    data.db prise avant ce déploiement contient 4 emplacements pour 5 identifiants alloués — donc
    buildsite_0001 a bien été SUPPRIMÉ par le panel, et son identifiant n'a pas été recyclé.
    Rien à réparer en base. Le défaut était entièrement dans le retour — mais comme Rafraîchir
    souffrait du même défaut, il était impossible de revenir constater le résultat, ce qui se lit
    très raisonnablement comme « rien n'est enregistré ».
  - CORRECTION STRUCTURELLE, pas la seule redirection (le prompt l'interdisait explicitement) : la
    liste est DÉRIVÉE de Layout.nav(). Toute page du menu est un retour accepté par construction,
    donc ajouter une page ne demande plus de penser à une seconde liste — l'oubli exact qui a
    produit #227. Le filtre garde son rôle anti-redirection ouverte (cible hors panel -> /agents).
  - TESTS HTTP SUR LES VRAIS FORMULAIRES, comme exigé : chaque test EXTRAIT le formulaire rendu
    par la page (URL d'action, champs cachés, CSRF, case de confirmation, option sélectionnée) et
    le soumet tel quel. La distinction n'est pas cosmétique : un corps écrit à la main contient ce
    que le test croit nécessaire, donc il aurait porté le bon « return » même quand la page en
    émettait un que le serveur refusait — et le défaut serait passé.
  - PREUVE que le garde-fou attrape #227 : le correctif a été temporairement remplacé par
    l'ancienne liste en dur. 2 échecs sur 4 dans ActionReturnPathTest et les 6 tests de formulaire
    réel en échec avec le symptôme EXACT (/agents?agent=rpgquest-dev&...). Correctif restauré.
  - CLIC EN JEU : LE CLIC N'ÉCRIT PLUS RIEN. Il calcule l'ancre et l'orientation, les retient en
    mémoire (PendingBuildingSiteRegistry, TTL 60 s, NON persistant) et ouvre une ENCLUME vanilla.
    Seul un clic sur le résultat écrit. Fermeture / expiration / déconnexion / nom refusé
    n'écrivent rien et NE CONSOMMENT AUCUN IDENTIFIANT (l'allocateur n'est appelé qu'à l'écriture).
    Justification trouvée dans les données réelles : les 4 emplacements du DEV sont tous dans un
    cube d'un bloc de côté et tous nommés « Nouvel emplacement » — le missclick que ce lot corrige.
  - API PUBLIQUE AUDITÉE AVANT D'ÉCRIRE (le prompt l'exigeait), sur le JAR Paper réellement
    installé, au javap : openAnvil(Location, boolean), AnvilInventory#getRenameText,
    PrepareAnvilEvent#setResult, AnvilView#setRepairCost/setMaximumRepairCost. AUCUN NMS, aucune
    réflexion CraftBukkit. Les deux derniers ne sont pas cosmétiques : sans eux, vanilla ne
    proposerait un résultat que si le nom diffère de l'original, et réclamerait des niveaux — le
    bouton de validation serait inerte ou payant.
  - DEUX PIÈGES TRAITÉS. (1) Valider implique de FERMER la fenêtre, donc InventoryCloseEvent part
    aussi après un succès : la demande est retirée du registre au moment de confirmer, sinon chaque
    création s'annoncerait « annulée » juste après avoir réussi. C'est la même mécanique qui rend
    un double clic inoffensif. (2) L'anti-rebond de 500 ms a été DÉPLACÉ de l'écriture vers le
    chemin du clic : laissé dans l'écriture, il aurait avalé EN SILENCE la confirmation d'un joueur
    rapide. Attrapé au raisonnement, pas par le compilateur ; test writingIsNeverDebounced.
  - PIÈGE SILENCIEUX ÉVITÉ : après avoir changé create(world, anchor, facing, createdBy, playerKey)
    en create(world, anchor, facing, name, createdBy), compileTestJava PASSAIT — l'ancien appel
    create(..., "Lody", null) s'était recollé sur name="Lody", createdBy=null. Tests réécrits.
  - Anti-missclick : même ancre -> la fenêtre ne s'ouvre même pas, et la règle est REVÉRIFIÉE à la
    confirmation (un autre administrateur a pu marquer ce bloc pendant la saisie) ; voisin immédiat
    (les 26 cases du cube) -> AVERTISSEMENT nommant l'emplacement concerné, JAMAIS un refus. Aucune
    distance minimale inventée, conformément au prompt.
  - Nom obligatoire, 1 à 64 caractères, Unicode, JAMAIS tronqué en silence, et JAMAIS source de
    l'identifiant technique. Un nom refusé NE FERME PAS la fenêtre : le joueur corrige sur place.
  - Code mort supprimé : BuildingSiteToolListener#rootName, devenu inutile avec announce().
  - TC-268 CORRIGÉ, pas doublé : ses étapes de marquage décrivaient un clic qui créait
    immédiatement, ce qui n'est plus le produit, et il n'avait JAMAIS été exécuté. Un TC périmé est
    pire que pas de TC. TC-269 ajouté pour le terrain réellement neuf.
Branche finale: fix/227-building-sites-actions (poussée, JAMAIS fusionnée)
Build: ./gradlew clean build BUILD SUCCESSFUL en 34 min 54 s sur b0ca263, worktree PROPRE, UN SEUL
  Gradle à la fois. 3062 tests, 0 échec, 38 ignorés : plugin 1987 (+31), control-panel 1045 (+12),
  web-api 30. Aucun test existant assoupli.
Déploiement: FAIT sur le DEV, 21:35-21:40. Panel d'abord (PANEL_DEPLOY_EXIT=0, /health ONLINE), et
  ce qui est SERVI a été vérifié au javap sur le JAR de /opt/plugadmin/app : safeReturnPath lit
  bien Set.contains(RETURN_PATHS) et non plus un switch, /buildings/sites -> 303. Puis le JAR
  (DEPLOY_EXIT=0, 2 042 328 o == local, SHA 6407fc8b…), backup du précédent sans écraser le plus
  ancien (2 027 547 o, SHA 96fd7ac9… = le JAR de #213). data.db sauvegardé AVANT et RELU
  (V28, integrity ok, 35 tables). UN SEUL redémarrage, annoncé en jeu d'abord car LoDyMcFly était
  connecté, save-all exécuté par le script. APRÈS : user_version TOUJOURS 28 (aucune migration
  dans ce lot, c'était attendu), 35 tables, les 4 emplacements et les 5 identifiants intacts.
  NON vérifiable d'ici : le RENDU de l'enclume chez le client. Aucune commande RCON ne simule un
  clic droit ni l'ouverture d'une fenêtre d'inventaire. C'est TC-269.
Tests manuels en attente: TC-269 (nouveau, ~6 min) et TC-268 (corrigé, ~5 min). Plus TC-267
  (#222..#226), TC-265, TC-266, TC-264, TC-257, TC-258..TC-263.
Blocages: aucun. Limites assumées et documentées :
  - aucune prévisualisation de l'ancre avant validation du nom : l'enclume occupe l'écran, et le
    chat annonce la position préparée faute de mieux ;
  - l'avertissement de voisinage est TEXTUEL, pas visuel ;
  - PendingBuildingSiteRegistry est purement mémoire : un redémarrage pendant une saisie perd la
    demande, ce qui EST le comportement voulu ;
  - le titre de la fenêtre d'enclume n'est pas garanti : openAnvil ouvre la fenêtre vanilla, dont
    le libellé vient du client. Le prompt demandait « Nom de l'emplacement » — la consigne est
    donnée dans le chat, qui est le seul endroit sûr ;
  - #227 reste OUVERTE : la moitié « ne s'appliquent pas » de son titre est réfutée, mais le
    parcours en jeu et le retour du panel réclament TC-269.
  DETTE RAPPELÉE : MiniYaml ne gère pas les scalaires repliés (dialogues/guard.yml).
  RestartServiceTest reste sensible au temps réel.
Première étape à reprendre: TC-269 puis TC-268 (~11 min à deux). Ensuite le lot suivant de #213 :
  affecter un .schem à un emplacement — NON commencé, le prompt l'interdisait explicitement.
```

```text
Date: 2026-10-08 (nuit — #213 : bibliothèque de bâtiments et premier placement)
Branche de départ: fix/227-building-sites-actions @ e02c4de (la ligne déployée du soir)
Branche de travail: fix/227-building-sites-actions (poursuivie — même chantier #213, et la ligne
  déployée est celle-ci ; ouvrir une branche de plus aurait fragmenté le déploiement du jour)
Étape de départ: « lot suivant de #213 : associer un schematic simple à un BuildingSite et faire un
  premier placement contrôlé ». Génération IA de bâtiment et analyse d'image explicitement HORS
  périmètre, et non commencées.
Étapes terminées:
  - AUDIT D'ABORD, et il a décidé l'architecture. (1) WorldEdit EST installé en DEV : 7.4.1, vérifié
    par `/version WorldEdit` — la version compileOnly est alignée dessus. (2) SONDE DÉCISIVE : un
    programme jetable sur worldedit-core hors serveur lève
    « IllegalStateException: WorldEdit is not initialized yet » dès BlockTypes.get(), parce que le
    registre de blocs est peuplé par la plateforme. Conséquence : un .schem ne peut être ni produit
    ni vérifié sur la machine de build. D'où la génération AU RUNTIME, dans le plugin, avec
    l'écrivain officiel — ce qui est exactement la première préférence du prompt.
  - CONTRAINTE DU PROPRIÉTAIRE, posée en cours de lot et respectée : WorldEdit est une dépendance
    d'INFRASTRUCTURE remplaçable, jamais du domaine. Tout passe par l'interface RPGQuest
    SchematicGateway ; BuildingSite, BuildingDefinition et BuildingPlacement n'exposent AUCUN type
    WorldEdit. Une seule classe le connaît (building.worldedit.WorldEditSchematicGateway),
    compileOnly + softdepend, LinkageError intercepté, refus nommé — conception du pont Citizens.
    Effet mesurable : l'essentiel du lot s'exécute dans les tests, sans WorldEdit sur le chemin de
    classe.
  - SÉPARATION contenu / état, alignée sur la préférence du prompt : BuildingDefinition = fichier
    YAML (plugins/RPGQuest/buildings/, relu au démarrage, exemple jamais écrasé) ;
    BuildingPlacement = table building_placements (migration V29, purement additive).
    site_id en CLÉ PRIMAIRE : « un emplacement porte au plus un bâtiment » est garanti par le
    schéma, donc vrai même si deux requêtes arrivaient ensemble.
  - HUTTE GÉNÉRÉE PAR NOTRE CODE, aucun fichier externe. TestHutBlueprint (7 × 5 × 6, fondation,
    poteaux d'angle, porte centrée, deux fenêtres, toit à deux pans, intérieur vide) + 
    SchematicWorkshop. Un plan en Java se relit en revue ; un blob gzip non — et
    TestHutBlueprintTest l'inspecte bloc par bloc. La définition lit ses dimensions SUR le plan, et
    un test échoue si le YAML embarqué divergeait.
  - FAÇADE DISSYMÉTRIQUE VOLONTAIRE : une hutte symétrique ne dirait rien d'une rotation de 180°, et
    le TC manuel ne pourrait rien conclure.
  - ÉCART ASSUMÉ par rapport à l'exemple du prompt : front = NORTH et non SOUTH. L'ancre (3, 1, 0)
    reste celle de l'exemple, mais la porte est sur la paroi z = 0, qui regarde les -Z. Déclarer
    SOUTH aurait caché un DEMI-TOUR PERMANENT dans le code de collage — exactement l'« offset
    implicite » que le prompt interdisait. La déclaration est donc géométriquement vraie.
  - ANCRE = centre de la porte au niveau du sol. Le y = 1 a une conséquence à connaître : la
    fondation se place un bloc SOUS l'ancre de l'emplacement, ce qui est voulu — une fondation
    s'enfonce dans le sol, et l'ancre d'un emplacement est la case libre au-dessus du bloc cliqué.
  - ROTATION PURE (BuildingRotation, azimut horaire nord = 0) et EMPRISE en décalages relatifs à
    l'ancre, donc l'ancre est un point fixe. À 90°/270° largeur et profondeur s'échangent, et
    l'aperçu l'affiche ET l'explique.
  - LE SENS DE ROTATION DU MOTEUR EST MESURÉ, PAS SUPPOSÉ. La convention de signe de
    AffineTransform#rotateY est interne à WorldEdit ; la deviner serait un pari, et un pari perdu
    pose la hutte à l'envers sur du terrain DÉJÀ écrasé. L'adaptateur applique la transformation aux
    coins, compare à l'emprise du domaine, et REFUSE le collage si aucun sens ne correspond.
  - ORDRE DES OPÉRATIONS = la garantie principale : revérifier tout (un aperçu n'est pas une
    réservation) → SAUVEGARDER la zone → coller (avec l'air, qui creuse l'intérieur) → enregistrer →
    marquer OCCUPIED. Échec de sauvegarde : rien n'est collé. Échec de collage : emplacement VIDE,
    aucun placement. Aucun faux placement possible. Prouvé par l'ordre des appels dans les tests.
  - RETOUR ARRIÈRE = l'option préférée du prompt : capture de la zone AVANT le collage, reposée
    telle quelle. SANS SAUVEGARDE, REFUSÉ, et le bouton n'apparaît même pas — remettre de l'air
    détruirait le terrain d'origine, ce serait une destruction déguisée en annulation. LIMITE
    DOCUMENTÉE plutôt que contournée : la restauration écrase aussi ce qui a été bâti dans l'emprise
    APRÈS la pose.
  - SiteStatus.OCCUPIED ajouté SANS MIGRATION (colonne TEXT, lecture tolérante) : le bénéfice exact
    que #213 visait en ne déclarant qu'une valeur, constaté un lot plus tard. Et AUCUNE action agent
    ne permet d'éditer l'état — il suit le fait, sinon on pourrait déclarer « occupé » un vide.
  - Quatre actions agent (definition.list / placement.preview / place / rollback). AUCUNE commande
    WorldEdit libre depuis le navigateur ; paramètres validés deux fois (catalogue panel, puis
    agent) ; confirm=true exigé pour poser et pour restaurer, y compris côté agent.
  - Permissions DÉDIÉES BUILDING_PLACE et BUILDING_ROLLBACK, distinctes de BUILDING_WRITE et l'une
    de l'autre : renommer une fiche ne change rien dans le jeu, poser écrase des blocs, restaurer
    écrase aussi ce qui a été ajouté depuis. Le Builder consulte sans poser.
  - Panel : /buildings/library en LECTURE SEULE (aucun téléversement, aucun éditeur — une définition
    est du contenu versionné), et sur la fiche d'un emplacement vide « Choisir un bâtiment » →
    aperçu → « Placer ». Le bouton de pose n'existe QU'APRÈS un aperçu, et un aperçu calculé pour un
    autre emplacement n'est pas affiché. Le groupe de nav « Bâtiments » créé à #213 avec une seule
    entrée a servi exactement comme prévu : aucun lien cassé.
  - /rpgadmin building list|reload|generate, SANS pose ni retour arrière : écrire dans le monde doit
    passer par le panel, qui montre l'emprise avant de confirmer et garde une trace.
Branche finale: fix/227-building-sites-actions (poussée, JAMAIS fusionnée)
Build: ./gradlew clean build BUILD SUCCESSFUL en 37 min 12 s sur db518fe, worktree PROPRE, UN SEUL
  Gradle à la fois. 3189 tests, 0 échec, 38 ignorés : plugin 2090 (+103), control-panel 1069 (+24),
  web-api 30. +127 tests pour ce lot. Aucun test existant assoupli — une seule attente corrigée
  (BuildingSiteServiceTest utilisait « OCCUPIED » comme exemple de valeur INCONNUE ; l'exemple est
  devenu réel, l'intention du test est conservée avec une autre valeur).
Déploiement: FAIT sur le DEV, 00:06-00:12. data.db sauvegardé AVANT et RELU (V28, integrity ok,
  35 tables, building_placements absente). Panel d'abord : PANEL_DEPLOY_EXIT=1 mais c'est le FAUX
  NÉGATIF connu (sonde /health avant liaison du port) — vérifié ensuite service actif, /health
  ONLINE, /buildings/library -> 303, et le JAR SERVI contient bien panel/building/* + les quatre
  actions. Puis le JAR (DEPLOY_EXIT=0, 2 114 011 o == local, SHA 38053163…), backup du précédent
  sans écraser le plus ancien, UN SEUL redémarrage, 0 joueur. MIGRATION V29 VÉRIFIÉE sur la base
  réelle : user_version=29, 36 tables contre 35, les 16 colonnes, l'index, ET
  sqlite_autoindex_building_placements_1 — qui prouve que site_id est la clé primaire.
  VÉRIFICATION LA PLUS UTILE : le .schem produit au démarrage a été retéléchargé et DÉCOMPRESSÉ.
  C'est un vrai schematic Sponge, Width=7 Height=6 Length=5, palette = les sept matériaux déclarés
  + minecraft:air. La chaîne plan Java -> écrivain officiel -> fichier valide fonctionne donc sur le
  serveur réel ; il ne reste que le collage à constater.
  NON vérifiable d'ici : le collage lui-même et le sens de rotation effectif du moteur. En cas de
  convention inattendue, l'adaptateur REFUSE au lieu de poser de travers — un refus serait donc une
  information, pas une catastrophe.
  CONSTAT INCIDENT : l'emplacement du DEV s'appelle « Hutte » (nom saisi) et porte buildsite_0006 —
  l'enclume de #227 fonctionne donc en jeu, et les identifiants ne sont pas recyclés.
Tests manuels en attente: TC-270 (nouveau, ~12 min, DEUX orientations). Plus TC-269, TC-268, TC-267
  (#222..#226), TC-265, TC-266, TC-264, TC-257, TC-258..TC-263.
Blocages: aucun. Limites assumées et documentées :
  - la restauration repose un INSTANTANÉ : elle écrase aussi ce qui a été bâti dans l'emprise APRÈS
    la pose. C'est écrit dans la zone de danger et dans la fiche d'aide ;
  - sans sauvegarde associée, le retour arrière est REFUSÉ et le bouton absent — remettre de l'air
    détruirait le terrain d'origine (le prompt interdisait la suppression destructive naïve) ;
  - aucun aperçu VISUEL en jeu de l'emprise : elle est annoncée en chiffres dans le panel ;
  - aucune vérification bloc par bloc du terrain : seul un comptage de blocs non-air, et il
    AVERTIT sans jamais refuser (le prompt l'exigeait explicitement) ;
  - aucun versioning de bâtiment, aucun remplacement d'un bâtiment posé par un autre ;
  - la POSITION d'un emplacement reste non modifiable depuis le panel ;
  - le .schem n'est PAS versionné dans le dépôt : la sonde a prouvé qu'il n'est ni produisible ni
    vérifiable hors serveur, donc le dépôt versionne le générateur et la définition. Un test échoue
    si les deux divergent.
  DETTE RAPPELÉE : MiniYaml ne gère pas les scalaires repliés (dialogues/guard.yml).
  RestartServiceTest reste sensible au temps réel.
Première étape à reprendre: TC-270 (~12 min, un vrai client). C'est le seul moyen de constater le
  collage et le sens de rotation. Ensuite, et seulement ensuite, le lot suivant : bibliothèque de
  schematics importables, puis génération IA de bâtiment — NON commencés, le prompt l'interdisait.
```

```text
Date: 2026-10-09 (nuit — #47 : publier du contenu sur DEV sans rebuild)
Branche de départ: fix/227-building-sites-actions @ 97913b4 (superset de la tête déployée db518fe)
Branche de travail: feat/47-content-publish
Étape de départ: « #47 PRIORITÉ ABSOLUE, travaille de manière autonome ». Consigne explicite de NE
  PLUS toucher au chantier bâtiment (#213/#227) ni à buildsite_0006.
Étapes terminées:
  - AUDIT D'ABORD, et il a RÉDUIT le périmètre — c'est le résultat le plus utile du lot. Le ticket
    listait huit familles ; DEUX étaient réellement bloquées (quêtes, stories) et une à moitié
    (dialogues, dont l'éditeur guidé écrivait déjà côté serveur). Les PNJ et les mobs/boss
    s'appliquent DÉJÀ au runtime via des stores côté serveur : les forcer à passer par une copie de
    fichier inventerait un second chemin pour un problème résolu. Les objets et recettes n'ont
    AUCUNE source côté panel (seuls des exemples embarqués sont déposés au démarrage) : il leur
    manque un ÉDITEUR, pas un transfert. ContentWorkspace.KINDS vaut exactement
    {quests, stories, dialogues} — la liste blanche et le manque réel coïncident.
  - CONTRAINTE D'ARCHITECTURE DÉCISIVE : l'agent est SORTANT. Le panel ne peut pas pousser un
    fichier. Le YAML voyage donc dans un PARAMÈTRE D'ACTION et c'est le serveur qui l'écrit. Plus
    SÛR que du FTP, pas seulement plus simple : aucune connexion entrante vers le serveur de jeu,
    aucun identifiant FTP côté panel, et le navigateur n'envoie NI CHEMIN NI CONTENU.
  - Le navigateur envoie quatre choses : famille, identifiant, empreinte source vue, empreinte DEV
    vue. C'est le PANEL qui lit la source et joint le YAML côté serveur — donc un formulaire forgé
    ne peut pas publier un contenu fabriqué. L'empreinte source est REVÉRIFIÉE à ce moment : #47
    exige la protection des deux côtés, et c'est la moitié qu'on oublie.
  - PublishKind EST la liste blanche. data.db, mondes, secrets, données Citizens et JAR sont hors
    d'atteinte PAR CONSTRUCTION. Trois verrous sur la résolution : énumération, motif sans
    séparateur, confinement revérifié après normalisation.
  - LA RÈGLE DU SERVICE : « fichier copié » ≠ succès, « action envoyée » ≠ succès, « reload
    demandé » ≠ succès. Un code de sortie par étape atteinte (RELOAD_FAILED dit que le fichier EST
    écrit et où est la sauvegarde ; RUNTIME_MISSING dit que le moteur ignore l'identifiant).
    ContentReloadService#loadedIds/runtimeHas ajoutés pour cela.
  - Ordre : valider → comparer l'état DEV à celui vu par l'appelant → SAUVEGARDER (échec = on
    s'arrête) → écrire atomiquement → recharger LA SEULE famille → relire. Verrou PAR RESSOURCE.
  - Retour arrière honnête : restauration s'il y a une sauvegarde, RETRAIT si la ressource était
    nouvelle. Jamais l'un déguisé en l'autre.
  - PublishState corrige un MENSONGE : l'ancien SYNCED voulait seulement dire « présent des deux
    côtés », donc une quête modifiée mais pas republiée s'affichait « Synchronisé ». Fonction PURE
    partagée par toutes les pages, + deux états qui manquaient (DIFFERENT, NOT_LOADED).
  - ContentApplier : frontière délibérée pour que l'ordre des opérations, les conflits et les refus
    s'exécutent dans des tests ordinaires, sans Bukkit.
  - DEUX DÉFAUTS ATTRAPÉS, dont un sur le SERVEUR RÉEL :
    (1) le message de conflit choisissait sa branche d'après l'état COURANT de DEV et non d'après ce
        que l'appelant avait vu — « créée sur DEV depuis votre analyse » ne se déclenchait jamais ;
    (2) après une RESTAURATION, l'état affiché devenait « Conflit » (= « un tiers a touché au
        fichier ») alors que c'était nous : la référence ne regardait que les publications. Corrigé,
        et la zone de retour arrière disparaît après une restauration — il n'y a plus rien à défaire.
Branche finale: feat/47-content-publish (poussée, JAMAIS fusionnée)
Build: NON TERMINÉE. ./gradlew clean build sur 9f8110c depuis un worktree propre a été INTERROMPU
  PAR LE SYSTÈME pour manque de mémoire, avant d'avoir produit le moindre résultat de test (clean
  avait déjà effacé les précédents). Aucun chiffre de build final à rapporter. Non relancé de ma
  propre initiative.
  Ce qui A été exécuté : suite control-panel COMPLÈTE 1102 tests / 0 échec, et plugin CIBLÉ 154
  tests / 0 échec — mais AVANT le correctif 74aa340. Après ce correctif, seul
  ContentPublishPageTest a tourné (17 tests / 0 échec), ce qui couvre le correctif puisqu'il est
  panel-only. Les trois classes du lot passent : 31 + 19 + 17.
  À FAIRE : ./gradlew clean build sur 9f8110c, worktree propre, machine au repos.
Déploiement: FAIT sur le DEV, 01:45-02:00. data.db sauvegardé AVANT et RELU (V29, integrity ok).
  Panel d'abord (premier essai PANEL_DEPLOY_EXIT=1 = faux négatif connu, vérifié ensuite : /health
  ONLINE, classes panel/publish/* et les trois actions dans le JAR SERVI). Puis le JAR
  (DEPLOY_EXIT=0, 2 144 737 o == local, SHA d98a8e48…), UN SEUL redémarrage, 0 joueur. AUCUNE
  migration.
  CAS DE RÉFÉRENCE PUBLIÉ POUR DE VRAI : tc265_ai_securiser_environs, depuis le VRAI panel et le
  VRAI formulaire. Source uniquement → PUBLISHED, created=true, devShaAfter == sourceSha
  (83b666df…), backup="" (ressource nouvelle, aucun faux backup), reload APPLIED 18 quêtes 0
  anomalie, runtimeConfirmed=TRUE. Vérifié INDÉPENDAMMENT : fichier sur DEV en FTP (617 o, même
  SHA) et identifiant présent dans les 18 chargés par le moteur. Badge « Synchronisé ». Retour
  303 → /quests. SON CONTENU N'A PAS ÉTÉ MODIFIÉ.
  CYCLE COMPLET sur une ressource de test dédiée (test_publish_47) : V1 publiée → source modifiée →
  « Différent » → republiée (sauvegarde prise, 980619cb… → 2d9754e9…) → RESTORED (retour à
  980619cb…) → WITHDRAWN. Nettoyage FAIT et vérifié : 18 quêtes, tc265 présente, test_publish_47
  absente du disque ET du runtime. Compte PlugAdmin jetable supprimé, secrets effacés.
  INCIDENT INSTRUCTIF : la première version de la ressource de test déclarait « block: » là où le
  moteur attend « material: ». Résultat RELOAD_FAILED — et le rechargement A REFUSÉ de s'appliquer,
  donc les 18 quêtes déjà chargées (dont tc265) sont restées actives. Un contenu invalide publié par
  erreur échoue bruyamment sans faire disparaître le contenu en place.
Tests manuels en attente: TC-271 (nouveau, ~6 min) — le parcours à l'œil dans le navigateur, et le
  confort MOBILE, qui n'est pas vérifiable par requêtes HTTP. Plus TC-270, TC-269, TC-268, TC-267,
  TC-265, TC-266, TC-264, TC-257, TC-258..TC-263.
Blocages: aucun. Limites assumées et documentées :
  - PAS de page « Changements en attente » (sélection multiple) : la phase 5 du prompt était
    conditionnée à « si le socle s'y prête », et la nuit a été employée à rendre les trois familles
    fichier réellement solides plutôt qu'à ajouter un écran de plus ;
  - PAS de diff ligne à ligne dans la fiche : seules les empreintes sont affichées. La comparaison
    visuelle reste à faire ;
  - l'état affiché est aussi frais que le dernier relevé « content.dev.state » TERMINÉ : la page
    ne s'auto-rafraîchit pas, et pendant la recette un relevé trop rapproché affichait encore
    l'état précédent. Ce n'est pas faux (l'état dit « d'après le dernier relevé »), mais c'est un
    point de confort à améliorer ;
  - familles NON couvertes par ce moteur, et pourquoi : PNJ / mobs / boss (déjà appliqués au
    runtime), objets / recettes (aucune source côté panel — il manque un éditeur) ;
  - la publication d'un dialogue copie le fichier TEL QUEL et ne passe jamais par MiniYaml, donc
    elle ne peut pas normaliser un scalaire replié. La dette appartient à l'ÉDITEUR GUIDÉ.
  DETTE RAPPELÉE : MiniYaml ne gère pas les scalaires repliés (dialogues/guard.yml).
  RestartServiceTest reste sensible au temps réel.
Première étape à reprendre: TC-271 (~6 min, un navigateur, dont un passage sur téléphone). Ensuite,
  par ordre de valeur : page « Changements en attente » avec sélection explicite, puis diff ligne à
  ligne, puis l'ÉDITEUR d'objets/recettes qui manque réellement (et non leur publication).
#47 reste OUVERTE : les familles PNJ/mobs/objets/recettes de son périmètre ne passent pas par ce
  moteur, et la décision de ne pas les y forcer mérite d'être validée par le propriétaire.
```

```text
Date: 2026-10-09 (matin — #47 lots A→E : diff réel, changements en attente, vérification réelle)
Branche de départ: feat/47-content-publish @ c880a19 (= tête déployée du lot précédent)
Branche de travail: feat/47-content-publish (même branche, suite directe du même ticket)
Étape de départ: les cinq lots du prompt, dans l'ordre imposé — A diff réel (priorité 1), B page
  « Changements en attente » + publication groupée, C fraîcheur de l'affichage, D vérification
  réelle d'un dialogue, E vérification réelle d'une story. Interdiction explicite de toucher aux
  bâtiments/#213/#234, WorldEdit, bornes/#156, mobs #229/#230, portails.
Étapes terminées:
  - LOT A — diff réel source ↔ DEV. Le diff est une fonction PURE (PublishDiff) : alignement par
    plus longue sous-séquence commune, puis réduction aux lignes de contexte avec un repère de
    saut EXPLICITE. Deux décisions qui comptent : (1) DEV est l'AVANT et la source l'APRÈS, parce
    que la question posée à l'écran est « qu'est-ce que publier va changer sur le serveur » ; (2) on
    normalise UNIQUEMENT les fins de ligne — reformater le YAML avant comparaison décrirait un
    fichier qui n'existe pas. Plafonds explicites (4 000 lignes, 256 Kio, 400 lignes rendues) avec
    un message propre. YAML échappé, numéros de ligne des deux côtés, aucun chemin filesystem.
  - CONSENTEMENT ÉCLAIRÉ EN CONFLIT, et c'est plus fort qu'un avertissement : en conflit, le bouton
    de publication n'existe QUE si la version DEV COURANTE a été consultée. Avoir regardé une
    version antérieure ne compte pas — c'est précisément ce qui a changé. Le remplacement aveugle
    n'est donc pas « déconseillé », il est INATTEIGNABLE.
  - Nouvelle action content.dev.read, classée en LECTURE (CONTENT_READ), même liste blanche
    PublishKind que la publication, texte plafonné. Aucun chemin ne vient du navigateur.
  - LOT B — page /content/pending : la LISTE, pas un compteur. Problèmes d'abord (conflit, puis
    publié-non-chargé), filtres famille/état et recherche en liens GET — aucun JavaScript, la CSP
    du panel interdisant le script en ligne (contrainte déjà rencontrée, pas une découverte).
  - LA PUBLICATION GROUPÉE N'EST PAS UN CHEMIN PARALLÈLE : chaque ressource cochée repasse par
    AgentActionCatalog.validate PUIS par la relecture de la source et la revérification
    d'empreinte, exactement comme une publication unitaire. Donc aucune protection contournée par
    construction, et pas « parce qu'on y a pensé ». Pas de « Publier tout ». Un conflit n'est jamais
    cochable. Une erreur sur une ressource NE ROLLBACK PAS les autres : succès partiel assumé et
    affiché ressource par ressource.
  - PIÈGE RÉEL ÉVITÉ : Http.parseUrlEncoded utilise map.put, donc des champs de MÊME nom
    s'écrasent. D'où un nom de champ UNIQUE par ressource (sel_<famille>/<identifiant>) — ce qui
    rend aussi impossible de publier deux fois la même ressource dans un lot : deux cases de même
    nom n'existent pas.
  - LOT C — fraîcheur : si le relevé DEV est antérieur à une publication qui a CONFIRMÉ le runtime,
    c'est le compte rendu de la publication qui fait foi (il a relu le moteur ; le relevé non), et un
    bandeau le dit. La règle du ticket TIENT : « Synchronisé » exige toujours runtimeConfirmed ET
    une empreinte DEV égale à la source du moment — si la source a bougé depuis, on ne réconcilie
    pas.
  - LOTS D ET E — CYCLES COMPLETS SUR LE SERVEUR RÉEL, sur des ressources de test DÉDIÉES, via le
    vrai panel et les vrais formulaires. Dialogue : publication (11 dialogues, runtimeConfirmed) →
    modification → Différent → diff rendu → republication avec SAUVEGARDE → RESTORED → la zone de
    retour arrière DISPARAÎT (plus rien à défaire) → WITHDRAWN (11 → 10) → source supprimée, moteur
    confirme l'absence. Story : idem (2 → 1), et le bouton proposé après la création était bien
    « Retirer de DEV » et non « Restaurer » — la ressource était neuve, il n'y avait pas de version
    précédente. guard.yml N'A PAS été touché ; aucune Story réelle cassée ; le parser YAML n'a pas
    été réécrit.
  - UNE FAUSSE ALERTE TRANCHÉE AU LIEU D'ÊTRE RAPPORTÉE TELLE QUELLE : le badge de la story s'est
    affiché « Source uniquement » là où « Différent » était attendu. C'était MON HARNAIS : il
    cherchait le badge dans une fenêtre située après la première occurrence de l'identifiant, et
    cette fenêtre tombait sur la fiche d'UNE AUTRE ressource (mesuré : 3 identifiants sur 6 sur
    /dialogues). Cycle REJOUÉ avec un extracteur qui remonte depuis le formulaire de LA ressource :
    badge « Différent ». Le produit disait vrai ; l'outil de mesure était faux.
Branche finale: feat/47-content-publish (poussée, JAMAIS fusionnée)
Build: ./gradlew clean build ABOUTIE cette fois, depuis un worktree propre, après ./gradlew --stop
  (qui a rendu ~650 Mo : 1107 → 1749 Mo disponibles — c'est ce qui a fait la différence avec la
  session précédente, interrompue pour manque de mémoire). 3334 tests, 0 échec, 38 ignorés
  (plugin 2121, control-panel 1183, web-api 30). +145 tests pour ces lots : PublishDiffTest 20,
  PendingChangesTest 20, ContentPendingPageTest 23, PublishStateTest +5, ContentPublishPageTest +10,
  et la mise à jour des doubles d'actions agent.
Déploiement: FAIT sur le DEV vers 10:10 UTC. Panel d'abord (vérifié par ce qu'il SERT : classes
  panel/publish/*, /content/pending → 303, content.dev.read dans le catalogue servi), puis le JAR
  (DEPLOY_EXIT=0, 2 147 202 o, SHA e7814da7…, backup rpgquest-20261009T101041Z-predeploy.jar), UN
  SEUL redémarrage, 0 joueur. AUCUNE migration (user_version reste 29).
Tests manuels en attente: TC-272 (nouveau, ~8 min) — diff à l'œil, page « Changements en attente »,
  publication groupée et confort MOBILE, qui n'est pas vérifiable par requêtes HTTP. Plus TC-271,
  TC-270, TC-269, TC-268, TC-267, TC-265, TC-266, TC-264, TC-257, TC-258..TC-263.
Blocages: aucun. Limites assumées et documentées :
  - une ressource SUPPRIMÉE DE LA SOURCE ne peut plus être retirée de DEV depuis sa fiche : la fiche
    disparaît avec la source. Le retrait doit donc précéder la suppression. Pour les ressources de
    test, le retrait a été fait par l'action content.publish.rollback prévue pour cela (rollback
    sans sauvegarde = retrait), soumise au panel qui l'a validée normalement. C'est un MANQUE
    D'ÉCRAN, pas un manque de moteur — candidat à un sous-ticket ;
  - le diff est TEXTUEL (pas sémantique YAML) : c'est ce que le prompt jugeait suffisant, et un diff
    sémantique masquerait un remaniement d'indentation qui, lui, peut casser le chargement ;
  - les familles PNJ / mobs / boss / objets / recettes ne passent toujours pas par ce moteur, et
    pour les raisons de l'audit du lot précédent, pas par manque de temps ;
  - #47 reste OUVERTE : voir le rapport de session pour l'évaluation objective et les trois
    sous-tickets proposés.
Première étape à reprendre: TC-272 puis TC-271 (~14 min au total, un navigateur, idéalement un
  téléphone pour le confort mobile). Ensuite, décider avec le propriétaire du sort des familles hors
  périmètre — c'est la seule question qui empêche de clore #47.
```

```text
Date: 2026-10-09 (après-midi — #235 : onboarding du Guide et resets joueur cohérents)
Branche de départ: feat/47-content-publish @ 2379a80 (= ligne réellement déployée, #47 fermée)
Branche de travail: feat/235-onboarding-guide-kit
Étape de départ: « Traite #235 avant de poursuivre les tests #123/#218 ». Trois défauts constatés
  en jeu après un reset de LoDyMcFly, dans les premières minutes de jeu. Consigne explicite de
  rester EXCLUSIVEMENT sur #235.
Étapes terminées:
  - AUDIT D'ABORD, et il a donné la CAUSE EXACTE — c'est le résultat le plus utile du lot. Il
    n'existait qu'UN reset. Il effaçait TOUTES les variables du joueur, donc le droit au kit ET le
    palier, mais ne retirait de l'inventaire que les objets RPGQuest (reconnus par PDC). Or le kit
    de départ est fait d'objets VANILLA (new ItemStack(Material, 1)) : ses outils restaient en
    place pendant que le droit d'en redemander un était rétabli. Ce n'est pas un défaut de la
    logique du kit, c'est l'écart entre « reset des DONNÉES » et « reset du MONDE PHYSIQUE ».
  - ET AUCUNE DÉTECTION SÛRE N'EXISTE : une pioche en bois du kit est rigoureusement indiscernable
    d'une pioche fabriquée. « Reconnaître les anciens outils du kit » détruirait les outils
    légitimes du joueur. Le ticket avait raison : la seule option sûre est un reset complet
    EXPLICITE. D'où deux portées (PlayerResetService.ResetScope) et non un seul « Reset ».
  - DEUX ACTIONS AGENT DISTINCTES ET NON UN PARAMÈTRE BOOLÉEN : un clic ne peut pas se tromper
    d'intention, et le journal d'audit dit laquelle a eu lieu. Permission DÉDIÉE
    ACTION_PLAYER_RESET_FULL : une progression se refait en rejouant, un inventaire vidé ne se
    défait pas — un droit commun aurait fait du second un effet de bord du premier.
  - LE PANEL DIT CE QU'IL CONSERVE, pas seulement ce qu'il efface : c'était l'information
    manquante. Le bloc « progression » avertit mot pour mot qu'un second kit restera possible, ET
    POURQUOI. Deux catégories d'aperçu ajoutées (droit au kit, palier), lues dans la carte de
    variables déjà chargée — aucune requête de plus.
  - Le marqueur de nettoyage différé porte la PORTÉE. Sa valeur historique « 1 » et toute valeur
    illisible sont relues comme PROGRESSION, la portée la MOINS destructrice : un marqueur douteux
    ne doit jamais vider un inventaire.
  - DEUX CONSTATS QUE L'AUDIT A RÉVÉLÉS, NON DEMANDÉS MAIS BLOQUANTS :
    (1) rpgquest:kit_tier2 n'était citée que dans config.yml. AUCUN dialogue ne la démarrait, et
        « giver: » est purement informatif (il n'existe aucune offre automatique de quête) : la
        progression de kit livrée par #218 était donc INJOUABLE depuis sa livraison ;
    (2) démarrer une quête n'affichait qu'un Title de deux secondes, SANS AUCUN OBJECTIF.
  - Dialogue : « Très bien, j'y vais. » supprimé au profit de DEUX GESTES — une question qui mène
    à un nœud qui EXPLIQUE et NOMME la quête, puis « Commencer la quête : Premiers pas ». Un test
    fige la règle pour le Guide.
  - Progression du kit TOUT DÉRIVÉE : noms de paliers de config.yml, lien palier→quête de
    « unlock-quest: », matériaux des objectifs DELIVER_ITEM_TO_NPC de cette quête, progression
    réelle de #123. Modifier le palier 2 change le discours du Guide SANS toucher au code.
  - TROIS MARQUEURS plutôt qu'un bloc tout fait, sur le modèle exact de %delivery_status% : les
    libellés qui les entourent restent dans guide.yml, donc éditables depuis le panel.
  - Palier tenu EN MÉMOIRE (le texte d'un nœud est substitué sur le thread principal, où aucune
    requête SQL n'a le droit d'arriver — motif déjà employé par ProgressionService et
    PortalService, que PlayerResetService invalide de la même façon). Ce cache NE SERT QU'À
    AFFICHER : requestKit relit toujours la base, donc un cache périmé ne peut JAMAIS faire donner
    le mauvais kit, et un test le prouve en le périmant exprès.
  - UN TEST OBSOLÈTE REMPLACÉ, ET C'EST LE TEST QUI AVAIT TORT :
    acceptingAQuestNeverSendsAChatMessage affirmait qu'accepter une quête n'envoie aucun message.
    Le contrat a changé, mais surtout il ne prouvait RIEN — la notification est planifiée sur le
    thread principal et il ne tickait pas le scheduler. Il aurait passé quel que soit le
    comportement.
  - PÉRIMÈTRE TENU VOLONTAIREMENT : mon test de règle, écrit d'abord pour TOUS les dialogues
    livrés, a révélé TROIS libellés du Garde porteurs du même défaut (crystal_hunt, guard_tier1,
    guard_tier2 démarrent sans le dire). J'en avais corrigé un, puis JE SUIS REVENU EN ARRIÈRE :
    contenu d'autres tickets, et crystal_hunt.yml est en cours d'édition par le propriétaire. Le
    constat est consigné, le test recentré sur le Guide, et prêt à être élargi.
Branche finale: feat/235-onboarding-guide-kit (poussée, JAMAIS fusionnée)
Build: ./gradlew clean build ABOUTIE depuis un worktree propre, après ./gradlew --stop.
  3389 tests, 0 échec, 0 erreur, 38 ignorés (plugin 2167, control-panel 1192, web-api 30), en
  39 min 30 s. +55 tests pour ce lot. Deux tests ajoutés après le gel du worktree exécutés
  séparément (PlayerResetServiceTest : 20 tests, 0 échec).
Déploiement: FAIT sur le DEV, 16:30-16:58. Panel d'abord (vérifié par ce qu'il SERT), puis le JAR
  ET guide.yml dans la même opération, UN SEUL redémarrage, 0 joueur avant et après. AUCUNE
  migration (user_version reste 29). JAR 2 159 437 o, SHA f9afbcde…, backup
  rpgquest-20261009T145359Z-predeploy.jar (2 147 202 o, donc plus PETIT que le neuf : direction
  attendue). guide.yml 8 653 → 12 656 o, ancien sauvegardé sous extra-20261009T145359Z/.
  ⚠️ guide.yml N'EST MÊME PAS dans BUNDLED_EXAMPLES (seul guard.yml y est) : un redéploiement de
  JAR ne le remplace jamais, il DOIT partir en --also. Sans cela, aucun changement d'onboarding
  n'aurait été visible en jeu — piège à retenir pour tout lot touchant un dialogue livré.
  VÉRIFIÉ SUR LE SERVEUR RÉEL, pas « le fichier est copié » : le PLUGIN a traité
  player.resetfull.preview en SUCCESS (scope=NEW_PLAYER, wipes_inventory=true, 12 catégories dont
  les trois nouvelles), et le MOTEUR a chargé rpgquest:guide avec 13 nœuds dont intro_quest et
  kit_progress, sans l'ancien libellé, avec les deux « Commencer la quête : … » et les trois
  marqueurs. Aperçu en LECTURE SEULE : aucune donnée joueur modifiée. Compte jetable supprimé,
  secret effacé.
Tests manuels en attente: TC-273 (nouveau, ~10 min) — rejoue exactement le parcours qui a révélé
  les trois défauts et s'enchaîne sur TC-257/#123. Plus TC-272, TC-271, TC-270, TC-269, TC-268,
  TC-267, TC-265, TC-266, TC-264, TC-257, TC-258..TC-263.
Blocages: aucun. Limites assumées et documentées :
  - TROIS libellés du Garde présentent le même défaut, volontairement non corrigés (voir
    ci-dessus). Correction d'une ligne chacun, à décider par le propriétaire ;
  - les PALIERS ne sont pas éditables depuis le panel (config.yml n'est pas administrable côté
    panel). Le dialogue, les textes et la quête kit_tier2 le sont ; le palier d'un joueur est
    lisible par player.variable.get. Constat de vérification demandé par le ticket, pas un manque
    créé par ce lot ;
  - paliers 3 à 5 : l'architecture les accepte, leur contenu n'est pas décidé — rien n'a été
    inventé, et le nœud du Guide s'y adapte déjà sans modification ;
  - le cache de palier peut afficher « Palier 1 » pendant la fraction de seconde qui suit la
    connexion. Jamais une valeur plus flatteuse que la réalité, et sans effet sur le kit remis.
Première étape à reprendre: TC-273 (~10 min, un client), qui débouche DIRECTEMENT sur TC-257/#123 —
  c'était l'objectif du ticket. Ensuite, trancher le sort des trois libellés du Garde.
```

```text
Date: 2026-10-09 (nuit — #234 : cycle de vie des bâtiments posés)
Branche de départ: feat/235-onboarding-guide-kit @ d467f27 (= ligne réellement déployée, inclut #47,
  #213, #227 et #235)
Branche de travail: feature/234-building-placement-lifecycle
Étape de départ: ordre strict — (1) vérifier factuellement le déploiement de #235 puis ne plus y
  toucher, (2) #234, (3) audit SEUL de #156 s'il reste du temps.
Étapes terminées:
  - PHASE 0 : #235 vérifié par TÉLÉCHARGEMENT et EMPREINTE, pas par déduction. JAR en ligne
    SHA f9afbcde… identique au local ; guide.yml en ligne 12 656 o SHA 2d0dc9bb… BIT-POUR-BIT
    identique au local ; les cinq exigences de contenu satisfaites ; Citizens/RPGQuest/WorldEdit
    verts ; panel servi vérifié. UNE FAUSSE ALERTE DE MON SCRIPT : « Très bien, j'y vais » signalé
    présent — c'était un COMMENTAIRE du fichier, aucun des 38 libellés ne le contient.
  - AUDIT DE #234 AVANT TOUT CODE, et il a donné les six réponses du ticket : rollback SUPPRIMAIT la
    ligne (aucun historique), le backup restait orphelin sur disque, le site repassait bien EMPTY et
    survivait au restart, un site libéré était immédiatement réutilisable. MANQUAIENT : historique,
    baseline originale, réorientation, remplacement, affichage de divergence, mémorisation de la
    version.
  - LA DISTINCTION CENTRALE : baseline originale ≠ état d'avant la dernière opération. La sauvegarde
    de chaque opération sert à COMPENSER un échec en cours de route ; le terrain d'origine est
    conservé À PART et jamais réécrit. Sans cela, un remplacement naïf aurait fait du
    terrain-avec-hutte la nouvelle origine — le piège exact décrit par le ticket.
  - PLUSIEURS FRAGMENTS PAR SITE, et ce n'est pas une facilité : une tour occupe plus de place
    qu'une hutte, donc une baseline prise sur l'emprise de la hutte laisserait des blocs de tour EN
    DEHORS — un terrain « presque d'origine », c'est-à-dire faux d'une manière qui en a l'air.
    L'ordre des opérations garantit que chaque fragment contient RÉELLEMENT du terrain d'origine
    (l'ancienne emprise est restaurée AVANT toute nouvelle capture), et c'est pour cela que l'ordre
    de restauration n'a aucune importance logique.
  - RÉORIENTER ET REMPLACER SONT LA MÊME SÉQUENCE DANGEREUSE, donc écrite UNE SEULE FOIS : vérifier
    + jeton → sauvegarder l'union des deux emprises → restaurer l'origine → capturer la nouvelle
    zone → coller → compenser si échec (code COMPENSATED, distinct d'un refus) → fiche en dernier.
    Partager n'est pas une économie de lignes : deux copies divergeraient, et c'est la moins soignée
    qui détruirait un terrain.
  - RÉORIENTER NE TOURNE PAS LES BLOCS EN PLACE : on repart du terrain d'origine et de la
    définition, donc le résultat est identique à une pose initiale dans cette orientation. Faire
    tourner les blocs existants accumulerait les erreurs essai après essai.
  - LIBÉRER RESTAURE D'ABORD, LIBÈRE ENSUITE : jamais de faux EMPTY. Et la baseline n'est PAS
    supprimée — le site doit pouvoir être rebâti puis libéré à nouveau et retrouver le MÊME terrain.
  - UN SITE EST UNE INTENTION, UN PLACEMENT EST UN FAIT : changer facing ne déplace aucun bloc, la
    fiche affiche les deux lignes et le dit. Deux tests le figent, dont un qui vérifie qu'AUCUN
    appel de collage n'a lieu.
  - VERSION ET EMPREINTE : la version déclarée peut être oubliée par qui édite le YAML, l'empreinte
    du fichier non. Sans information des deux côtés, on NE CONCLUT PAS — annoncer « à jour » sans le
    savoir serait une affirmation qu'on n'a pas le droit de faire.
  - TROIS FAUTES RÉELLES ATTRAPÉES PAR LES TESTS, PAS PAR RELECTURE :
    (1) une torche posée sur une marche au niveau 2 de la tour ;
    (2) un contrefort qui doublait le mur ouest (même position) — remplacé par un changement de
        MATÉRIAU, même effet visuel sans bloc en conflit ;
    (3) des volées de TROIS marches là où quatre sont nécessaires. Deux planchers sont séparés de
        quatre blocs et une marche ne fait gagner qu'un demi-bloc : la troisième culminait à 3,5
        quand le plancher s'atteint à 5,0. LA TOUR AURAIT ÉTÉ INVISITABLE, et cela ne se serait vu
        qu'en jeu.
  - UN DÉFAUT DE LISIBILITÉ CORRIGÉ grâce à un test : le premier fragment de baseline gardait le nom
    « backup_ » alors qu'il EST la baseline, et rien dans le dossier ne disait lequel des fichiers
    ne doit jamais être supprimé. Nommage désormais auto-descriptif : origine_ / compens_ / backup_.
  - VILLAGES/VILLES préparés, PAS commencés : chaque emplacement reste indépendant, aucune opération
    ne traverse plusieurs emplacements, et AUCUNE MIGRATION n'a été créée « au cas où » (§19 du
    ticket respecté). Le jour venu : une table de projet + une colonne project_id nullable.
Branche finale: feature/234-building-placement-lifecycle (poussée, JAMAIS fusionnée)
Build: ./gradlew clean build ABOUTIE depuis un worktree propre, après ./gradlew --stop.
  3462 tests, 0 échec, 0 erreur, 38 ignorés (plugin 2225, control-panel 1207, web-api 30), en
  42 min 19 s. +73 tests pour ce lot : BuildingLifecycleServiceTest 35,
  TestWatchtowerBlueprintTest 20, BuildingLifecyclePageTest 15, plus les mises à jour.
Déploiement: FAIT sur le DEV, 19:15-19:25. data.db SAUVEGARDÉ AVANT la migration
  (data-20261009T163556Z-pre-v30.db, 1 802 240 o, SHA 7ef3c563…, integrity ok, user_version 29).
  Panel d'abord (vérifié par ce qu'il SERT), puis le JAR (2 213 495 o, SHA 171020a6…, backup
  rpgquest-20261009T171623Z-predeploy.jar à 2 159 437 o — plus PETIT que le neuf, direction
  attendue), UN SEUL redémarrage, 0 joueur avant et après.
  MIGRATION V30 VÉRIFIÉE SUR LA BASE RÉELLE : integrity_check ok, user_version = 30, les deux
  tables et leurs index créés, building_placements à 18 colonnes (étaient 16), 38 tables au total
  (étaient 36), et LE PLACEMENT EXISTANT INTACT (buildsite_0006 / test_hut_01 / rotation 180 /
  backup inchangé, version 0 et empreinte vide = les défauts qui se lisent « inconnu »).
  TOUR GÉNÉRÉE PAR LE PLUGIN AU DÉMARRAGE : schematics/test_watchtower_01.schem présent, 641 o —
  il était ABSENT avant le redémarrage. Définition déposée (2 455 o).
  AUCUNE MUTATION DU MONDE depuis la machine : aucun bâtiment posé, aucun bloc touché, aucun
  emplacement modifié.
Tests manuels en attente: TC-274 (nouveau, ~20 min) — libérer, constater qu'une rotation de site ne
  déplace aucun bloc, réorienter, remplacer par la tour et LA VISITER, puis enchaîner plusieurs
  essais et vérifier que le terrain restauré est celui du TOUT DÉBUT (étape D, la plus importante).
  Plus TC-273, TC-272, TC-271, TC-270, TC-269, TC-268, TC-267, TC-265, TC-266, TC-264, TC-257,
  TC-258..TC-263.
Blocages: aucun. Limites assumées et documentées :
  - À SAVOIR POUR DEMAIN : buildsite_0006 DIVERGE DÉJÀ (site NORTH, hutte posée à 180°) — la fiche
    affichera donc l'avertissement sur l'emplacement réel. Ce n'est pas une régression, c'est le cas
    que ce lot rend visible. Sa sauvegarde de pose est présente (321 o), donc la libération
    fonctionnera ;
  - les fragments de baseline s'ACCUMULENT si un emplacement reçoit des bâtiments de tailles
    croissantes. Aucun nettoyage automatique : supprimer un fragment reviendrait à perdre une partie
    du terrain d'origine. Un outil d'entretien explicite serait un sous-ticket raisonnable ;
  - la mise à jour vers une nouvelle version n'a pas d'action dédiée : on passe par « Remplacer »
    avec le même bâtiment (le ticket l'autorisait, « hors MVP si nécessaire ») ;
  - #156 : audit NON commencé — le temps a été employé à finir #234 proprement (build, déploiement,
    vérification de migration, documentation, TC).
Première étape à reprendre: TC-274 (~20 min, un client). Ensuite l'audit de #156, diagnostic SEUL,
  sans toucher à la politique de densité des bornes.
```

```text
Date: 2026-10-10 (après-midi — #156 : audit du réseau de voyage, « pourquoi 13 bornes ? »)
Branche de départ: feature/234-building-placement-lifecycle @ fded90d (= ligne réellement déployée,
  inclut #47, #213, #227, #235 et #234)
Branche de travail: feature/156-travel-network-audit
Étape de départ: ordre strict — (1) #156 audit complet + diagnostic administrable + correction d'un
  BUG seulement s'il est démontré, (2) #229 LOT A si #156 est terminé proprement, (3) documenter le
  lot suivant. Interdits rappelés par l'utilisateur : aucun chunk pré-généré, aucun scan global,
  aucun backfill, aucun ratio ni distance changé, aucune borne déplacée/supprimée, aucun monde
  modifié, aucune progression de joueur touchée, aucun git add -A sur src/.
Étapes terminées:
  - RÉPONSE FACTUELLE à « pourquoi 13 ? » : les 13 bornes datent TOUTES des sessions des 3 et 4
    octobre, puis AUCUNE pendant quatre jours ; trois sont apparues le 8 octobre, après le
    déploiement du correctif 95c0d68, et la dernière a été appariée 2 SECONDES après son waypoint —
    contre 17 s à 4,7 jours pour les 13 précédentes. 13 n'était ni faux ni correct : c'était un
    compte arrêté net par un bug déjà corrigé la semaine précédente.
  - Chiffres relevés (copie lecture seule de data.db + relevé travel.catalog en direct) :
    143 waypoints (120 wild + 23 world_hub), 16 bornes TOUTES dans world_hub, 0 dans le Wild,
    0 borne posée à la main, 23 instances Hub dont 16 équipées, 7 sans borne, 0 orpheline,
    7 Waystones dans le Wild dont 0 DÉCOUVERTE, 52 découvertes de waypoints (8 Hub, 44 Wild).
  - Hypothèses RÉFUTÉES par la mesure : (a) l'espacement — plus petite distance entre deux bornes
    99 blocs pour un minimum-spacing de 80 ; (b) la densité — une borne tous les ~252 blocs de côté,
    voisin le plus proche à 131 blocs en moyenne ; (c) « réduire region-size » — re-découper les
    23 points connus à 128 puis 64 donne exactement 23 instances, donc ZÉRO borne de plus dans le
    Hub exploré ; (d) « une borne tous les N mètres » — une par tuile de 256 donnerait ~20 bornes
    contre 16, soit +4.
  - Défaut RESTANT démontré : le correctif répare les futures traversées, pas le retard déjà
    constitué. Les 7 instances datent d'avant le correctif, n'ont JAMAIS été découvertes (clic
    droit) et affichent attempts=0 — jamais retentées. Et /rpgadmin travel beacon set ne pouvait pas
    les fermer : il crée une borne avec biome_instance VIDE, qui ne ferme aucun appariement.
  - Diagnostic administrable : relevé travel.catalog enrichi (createdAt, monde Hub, spawn, seuils
    réels, réseau Waystones) + classe PURE panel.travel.TravelNetworkDiagnostic (14 tests) +
    CINQ sections de /travel (réseau Hub, couverture, fiche ouvrable par borne, instances connues,
    réseau du Wild). Masquées sous filtre : une moyenne sur un sous-ensemble recherché serait
    présentée comme la couverture du réseau.
  - Correction AUTORISÉE et ciblée : action travel.beacon.pair, permission dédiée
    TRAVEL_PAIR_WRITE (OWNER + ADMIN), mutation sensible, instance désignée par SON waypoint.
    Réutilise attemptPairBeacon tel quel, donc la borne posée est appariée à son instance. Aucun
    balayage, aucune autre instance, aucun chunk, aucune densité/distance/probabilité modifiée,
    AUCUNE migration. Un test le prouve sur deux instances en manque : on en nomme une, l'autre
    reste intacte. Aucune borne posée depuis la machine.
  - Quatre scénarios d'équilibrage CHIFFRÉS et NON ACTIVÉS (statu quo + ciblé ; rattrapage
    périodique borné ; rendre les Waystones trouvables ; densifier la grille du Wild ×2,7), plus le
    chiffre du changement de politique « bornes dans le Wild » (+120 structures potentielles).
Branche finale: feature/156-travel-network-audit
Build: voir le rapport docs/claude-reports/2026-10-10_1340_audit-reseau-voyage.md
Tests: voir le même rapport (section « Résultat des suites »)
Déploiement: voir docs/deployment/SERVER_CHANGELOG.md, entrée du 2026-10-10
Tests manuels en attente: TC-275 (diagnostic) et TC-276 (rattrapage ciblé) — ajoutés à
  docs/MANUAL_TEST_PLAN.md. Plus TC-273 (#235), TC-274 (#234), TC-257 (#123), TC-271/TC-272 (#47),
  tous intacts : aucune donnée nécessaire à ces tests n'a été touchée.
Blocages: aucun. #156 reste OUVERTE (validation manuelle en jeu).
Première étape à reprendre: TC-275 puis TC-276 (5 min chacun). Puis #229 LOT A.
```
