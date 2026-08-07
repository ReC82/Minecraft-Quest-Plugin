# Mod client (prototype, séparé du plugin Paper)

Un prototype de mod client optionnel, créé seulement maintenant que le
serveur (plugin Paper) est stabilisé (mission étape 23). Il ajoute du vrai
contenu client (bloc, objet, indicateurs cosmétiques) mais **ne contient
aucune logique de jeu critique** : le plugin Paper reste, sans exception,
l'autorité pour la progression, les drops, l'économie, les droits et les
achats (voir [docs/STORE.md](STORE.md), [docs/PROGRESSION.md](PROGRESSION.md),
[docs/CLAIMS.md](CLAIMS.md)).

## Séparation (mission points 1-2)

Le mod vit dans `client-mod/`, à la racine du dépôt, en **projet Gradle
entièrement indépendant** — pas un sous-projet de `settings.gradle.kts`
racine (contrairement à `web-api/`, qui reste un sous-module du même
build). `client-mod/` a son propre wrapper Gradle
(`client-mod/gradlew(.bat)`, `client-mod/gradle/wrapper/`) et son propre
`settings.gradle.kts`. Conséquence directe et voulue (mission, validation
« le plugin Paper reste compilable et testable indépendamment ») :
`gradlew.bat clean build` à la racine du dépôt ne touche jamais à
`client-mod/`, ne le compile jamais, et un problème de toolchain Fabric
(téléchargement du jar client Minecraft, mappings, remapping) ne peut
jamais faire échouer la validation du plugin. Le mod n'est **jamais**
empaqueté dans le jar du plugin.

## Choix du toolchain : Fabric (mission point 3)

Vérifié empiriquement (API officielles, `2026-08-07`) plutôt que supposé :

| | Fabric | NeoForge |
|---|---|---|
| Version Minecraft du serveur (`1.21.11`) supportée | ✅ `1.21.11` listée stable (`meta.fabricmc.net/v2/versions/game`) | ✅ ligne `21.11.x` publiée (`maven.neoforged.net`) |
| Mappings/API disponibles pour cette version | ✅ Yarn `1.21.11+build.6`, Fabric API `0.141.6+1.21.11` | ✅ également disponible |
| Outillage Gradle | Fabric Loom `1.17.19`, setup minimal, un seul plugin | ForgeGradle/NeoGradle, setup plus lourd |
| Empreinte pour un prototype | API modulaire (n'importer que ce qui sert) | API plus large, moins adaptée à un prototype minimal |

Les deux plateformes supportent la version du serveur. **Fabric est
choisi** pour un outillage plus léger et plus rapide à mettre en place pour
un prototype, et un historique de support jour-J des nouvelles versions
généralement plus rapide. Rien dans l'architecture (protocole plugin
messaging, séparation des dépôts) n'est spécifique à Fabric : un futur
portage NeoForge n'affecterait que `client-mod/`, jamais le plugin.

## Contenu du prototype (mission point 5)

- **Un vrai bloc et un objet associé** — `rpgquest_client:crystal_display`
  (`ModContent`), un vrai `Block`/`BlockItem` Fabric enregistré et
  fonctionnel dans le jeu du joueur (onglet créatif, modèle, texture).
  **Limite assumée et documentée** : un serveur Paper vanilla-compatible
  (sans NMS, voir PROJECT_RULES.md) ne peut pas synchroniser un nouvel
  identifiant de bloc/objet vers les clients — la synchronisation des
  palettes de blocs exige que client ET serveur partagent le même
  registre. Ce bloc/objet n'est donc jamais posé ni donné par le serveur ;
  il démontre l'outillage de modding, pas un contenu livré en jeu. Un vrai
  contenu serveur→client nécessiterait soit un serveur Fabric/NeoForge
  compagnon, soit un système de correspondance d'identifiants — hors
  périmètre d'un prototype.
- **Une représentation visuelle d'une variante de mob** — canal cosmétique
  `rpgquest:mob_variant_tag` (serveur → client uniquement) : le serveur
  identifie une entité déjà visible du client (id réseau vanilla, déjà
  synchronisé par le protocole standard) comme portant une variante
  spéciale ; le mod affiche un message d'action bar « ⚡ Variante détectée
  : *nom* ». Simplification assumée : un rendu 3D en superposition
  (glow/outline personnalisé) demanderait des mixins fragiles et
  invérifiables sans lancer un vrai client — hors périmètre.
- **Une petite indication client** — `ModHud` affiche en permanence en haut
  à gauche l'état de la détection de compatibilité (« RPGQuest : connecté
  » / « version incompatible » / « serveur non détecté »).

## Protocole (mission point 4 : vérification de compatibilité plugin ↔ mod)

Deux canaux de plugin messaging (`Bukkit.getMessenger()` côté plugin,
`ClientPlayNetworking`/`PayloadTypeRegistry` côté mod — deux
implémentations indépendantes du même format binaire, jamais de code
partagé entre les deux projets, même principe que web-api/plugin depuis
l'étape 21) :

### `rpgquest:handshake_hello` (bidirectionnel)

À la connexion du joueur, le serveur envoie **5 octets** :

| Octets | Champ | Valeur |
|---|---|---|
| 0-3 | `magic` (int, big-endian) | `0x52504751` (ASCII "RPGQ") |
| 4 | `serverProtocolVersion` (byte) | `1` |

Le mod répond avec exactement le même format (magic + son propre
`clientProtocolVersion`). **Volontairement limité à magic+byte, jamais de
chaîne** : évite tout risque de désaccord entre l'encodage VarInt+UTF-8 de
Minecraft (`PacketCodecs.STRING`) et un décodage manuel côté plugin (qui
n'a pas accès à `PacketByteBuf`).

### `rpgquest:mob_variant_tag` (serveur → client uniquement)

| Octets | Champ |
|---|---|
| 0-3 | `entityNetworkId` (int, big-endian) |
| suite | `variantDisplayName` : VarInt (longueur en octets UTF-8) puis les octets UTF-8 — format `PacketByteBuf#writeString` de Minecraft |

Le VarInt est le format standard du protocole Minecraft (7 bits utiles par
octet, bit de poids fort = « il reste un octet »). Testé par
`HandshakeProtocolTest` (encodage côté plugin, décodage indépendant côté
test, y compris avec des caractères Unicode).

## Le serveur reste l'autorité (mission points 6-7)

`ModCompatService` ne dépend d'aucun service de jeu (`EconomyService`,
`EntitlementService`, `QuestProgressEngine`...) — structurellement
impossible d'accorder quoi que ce soit depuis ce canal. Le seul contenu
jamais lu depuis un message client est `magic` + `clientProtocolVersion` :
aucun champ ne permet de déclarer un objet possédé ou une action terminée.
Un paquet malformé, trop court, ou au contenu arbitraire est simplement
classé `NO_MOD`, jamais interprété, jamais une exception.

## Détection et politique (mission points 8-9)

`config.yml` → `client-mod:` :

```yaml
client-mod:
  require-mod: false        # false (par défaut) : client vanilla autorisé avec repli
  handshake-timeout-ticks: 60
```

`ModCompatService` classe chaque joueur, à chaque connexion (jamais d'état
hérité d'une reconnexion précédente) :

| État | Condition |
|---|---|
| `COMPATIBLE` | réponse reçue, magic valide, version = celle du serveur |
| `WRONG_VERSION` | réponse reçue, magic valide, version différente |
| `NO_MOD` | pas de réponse avant `handshake-timeout-ticks`, ou paquet invalide |

Politique :

- `require-mod: false` (par défaut) — **client vanilla autorisé avec
  repli** : `WRONG_VERSION`/`NO_MOD` ne bloquent jamais la connexion, le
  joueur joue normalement sans le contenu cosmétique du mod.
- `require-mod: true` — **mod obligatoire**, seulement si activé
  explicitement : `WRONG_VERSION`/`NO_MOD` entraînent une exclusion
  (`Player#kick`) avec un message explicite.

## Installation, mise à jour, compatibilité (mission point 10)

**Joueur** : construire le mod (`cd client-mod && gradlew.bat build`),
récupérer `client-mod/build/libs/rpgquest-client-mod-<version>.jar`
(le jar remappé, pas celui suffixé `-dev`), le placer dans le dossier
`mods/` d'une installation Fabric (Fabric Loader `0.19.3`+, Fabric API
correspondant à `1.21.11`, voir `client-mod/gradle.properties`).

**Mise à jour** : `minecraft_version`/`yarn_mappings`/`fabric_version`
dans `client-mod/gradle.properties` doivent rester synchronisés avec
`minecraftVersion(...)`/`paper-api` du `build.gradle.kts` racine. Un
changement de version Minecraft du serveur nécessite une recompilation du
mod avec les coordonnées correspondantes — jamais l'inverse (le serveur ne
change jamais de version pour s'adapter au mod).

**Compatibilité** : `SERVER_PROTOCOL_VERSION`
(`ModCompatService`/`HandshakeProtocol`) et `CLIENT_PROTOCOL_VERSION`
(`ModNetworking`, mod) sont incrémentés indépendamment de la version
sémantique du mod à chaque changement du protocole — c'est cette valeur,
pas le numéro de version Minecraft ni la version du mod, qui détermine
`COMPATIBLE` vs `WRONG_VERSION`.

## Tests

- **Plugin** (`HandshakeProtocolTest`, JUnit pur — encodage/décodage,
  robustesse aux paquets invalides ; `ModCompatServiceTest`, MockBukkit —
  client compatible, version incorrecte avec/sans mod obligatoire, client
  vanilla après délai, paquet réseau invalide, reconnexion, tentative de
  falsification, diffusion cosmétique conditionnelle).
- **Mod** : `client-mod/gradlew.bat build` compile et remape contre le jar
  client Minecraft `1.21.11` réel (Fabric Loom) — validé dans cette
  session. Aucun test automatisé côté mod à ce stade (nécessiterait un
  client Minecraft lancé, hors de portée d'une CI plugin) ; voir tests
  manuels ci-dessous.

## Tests manuels restants

- Connexion avec le mod installé (version correcte) — vérifier le HUD
  « connecté » et l'absence de kick.
- Connexion avec un client vanilla — vérifier qu'il peut jouer normalement
  (`require-mod: false`) ou qu'il est exclu avec un message clair
  (`require-mod: true`).
- Forcer une version de protocole différente côté mod puis se connecter —
  vérifier `WRONG_VERSION` et le comportement selon la politique.
- Vérifier en jeu le bloc/objet `crystal_display` (onglet créatif, modèle,
  texture).
- Redémarrer le serveur et reconnecter le même client — vérifier qu'un
  nouveau handshake complet a bien lieu (pas d'état résiduel).

## Limites connues

- Le bloc/objet du prototype n'est jamais livré par le serveur (voir
  "Contenu du prototype" ci-dessus) — limite architecturale, pas un oubli.
- La représentation de variante de mob est un message d'action bar, pas un
  rendu 3D en jeu.
- Aucun test automatisé ne peut exercer le vrai client Minecraft dans cet
  environnement ; le protocole est validé par ses deux moitiés compilant
  et testant indépendamment (`HandshakeProtocolTest` + build Fabric Loom
  réussi), jamais par un aller-retour réseau réel.
- Pas de mixins : toute limitation d'API Fabric publique (ex. reskin
  d'entité vanilla) est contournée par simplification plutôt que par un
  hook non officiel.
