# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-09
* Heure : 22:15 (heure locale de la machine de build)
* Sujet : Issue #108 — export versionné des contenus déclaratifs (phase 1 du pipeline de contenus LodyQuests)
* Statut : DONE (moteur + UI + tests + docs) — validation navigateur `PENDING MANUAL VALIDATION` ; aucun déploiement
* Branche Git : `feat/control-panel-admin-tools` (consigne : rester sur la branche courante, ne rien merger)
* Commit au démarrage : `ff35b70`
* Début de la tâche : 2026-09-09 21:38:00 (après la fin du déploiement #124 ; horodatage de la 1re commande de la tâche)
* Fin de la tâche : 2026-09-09 22:24:04
* Durée totale : 00:46:04 (dont ~00:23:00 de `./gradlew build` sur la box contrainte, en deux passes)

## Demande

Implémenter la **phase 1** du pipeline de contenus LodyQuests : **export versionné** des contenus
déclaratifs (quêtes, stories, dialogues, PNJ logiques). Créer un **contrat d'export stable** qui
servira ensuite à l'import #109 puis à la génération massive par IA #110 — donc **pas** une copie
fragile des structures internes. Format public versionné (`format` + `schemaVersion`), lisible,
générable par IA, déterministe, indépendant des chemins physiques, références conservées,
extensible sans casser `schemaVersion 1`. Couche/DTO d'export dédiée, jamais de sérialisation
runtime directe. UX Control Panel (exporter cet élément / une famille / tout ; nom de fichier
explicite ; desktop + mobile). Export global dans un seul paquet avec les références. Sécurité :
aucun secret / donnée joueur / log / chemin système. Tests significatifs. Documentation du format.
**Ne pas faire #109** (import) — mais préparer l'architecture. Fin : tests, build, docs, rapport,
commit, push, **pas de merge, pas de déploiement**, mail de fin.

## Analyse

### Audit de l'existant

- **Source de vérité du contenu** : fichiers YAML sous `plugins/RPGQuest/{quests,stories,dialogues,npcs}/`,
  chargés au démarrage par les parseurs en modèles immuables tenus par les moteurs :
  `YamlQuestEngine.quests()`, `StoryService.stories()`, `YamlDialogueEngine.dialogues()`,
  `YamlNpcEngine.definitions()`.
- **Le Control Panel n'a aucun accès disque** au contenu du plugin : il ne parle au plugin que via
  l'**agent sortant** (`AgentActions` façade, `AgentActionType` whitelistée, pipeline poll →
  résultat). Les relevés existants (`quest.list`, `story.list`, `dialogue.list`, `npc.list`) sont
  des **résumés d'affichage** (chaînes pré-formatées, champs `raw`, pas de `description`/`icon`/
  `secret`/`variables` sur les quêtes) → **inexploitables pour un export fidèle**.
- Précédent de « YAML canonique écrit à la main » dans le dépôt : `DialogueDefinitionWriter`,
  `NpcDefinitionYaml` (StringBuilder + `quote()`), jamais un `Yaml.dump`.
- **Contrainte de transport** : `POST /agent/v1/actions/{id}/result` plafonné à **64 Kio** côté
  panel (413 au-delà). Un pack embarqué dans le résultat doit rester < ~56 Kio.
- L'éditeur #46 est essentiellement en lecture (`quest.list`) : il n'existe **pas** d'action
  d'écriture de quête. #108 est donc bien un système neuf, pas une extension de l'éditeur.

### Décisions

1. **Couche d'export dans le plugin** (`com.lodygames.rpgquest.content.pack`), pas dans le Control
   Panel : le plugin détient les modèles réels + parseurs (contrat que #109 devra relire). Le
   Control Panel reste une UI + un passe-plat de téléchargement.
2. **Format YAML** (pas JSON) : tout le contenu RPGQuest est écrit en YAML, la BIBLE parle YAML,
   l'exemple de #108 est en YAML, c'est le plus lisible/générable à la main et par IA. Un futur
   JSON Schema (#110) décrira la même structure (YAML ⊃ modèle de données JSON).
3. **Vocabulaire du pack = le schéma YAML RPGQuest existant** (documenté, stable, versionné par
   `schemaVersion`), pas une 2ᵉ représentation métier (ce que #108 interdit explicitement). Une
   entrée de pack est donc un document de définition valide sans l'enveloppe fichier → round-trip
   trivial pour #109, familier pour l'owner et pour une IA.
4. **DTO dédiés sans Bukkit** + mappers explicites (jamais `record` runtime sérialisé) + serializer
   canonique à la main (déterminisme indépendant d'une lib) + assembleur pur (sélection / ordre /
   manifest).
5. **`secret: true` est exporté** (contenu éditorial ; un backup ne doit pas perdre de contenu),
   avec le flag conservé pour un round-trip fidèle. Documenté.
6. **Familles MVP** : `quests`, `stories`, `dialogues`, `npcs`. Items/recettes/marchands = modèles
   plus larges → ajoutables sans casser `schemaVersion 1` (une constante `ContentFamily` + un
   `*PackEntry` + un cas de mapper/serializer + un `Supplier`). Waypoints (#124) = runtime, pas
   déclaratif → hors périmètre par nature.
7. **UX Control Panel** : une **page dédiée `/content/export`** (exporter tout / par famille /
   sélection d'ids) plutôt que 4 boutons ajoutés sur 4 grandes pages métier — couvre tous les
   critères d'acceptation #108 sans surcharger les écrans. Les boutons par ligne sur `/quests`
   etc. restent un ajout ultérieur (documenté).
8. **Limite de transport** : au-delà de ~56 Kio le pack est **refusé proprement** (jamais tronqué)
   → exporter par famille ou par élément. Le découpage/compression est lié à #109/#110. Documenté.

## Travail effectué

### Plugin — `com.lodygames.rpgquest.content.pack`

- **DTO sans Bukkit** : `ContentPack` (+ `Manifest`), `QuestPackEntry` (+ `Step` / `Objective` /
  `Reward`), `StoryPackEntry`, `DialoguePackEntry` (+ `Node` / `Choice` / `Action` / `Condition`),
  `NpcPackEntry`, `PackText` (texte localisable).
- **`ContentFamily`** : énum `QUESTS`/`STORIES`/`DIALOGUES`/`NPCS` + clé de fil ; seul point qui
  connaît la liste des familles.
- **`ContentPackMapper`** (Bukkit) : `QuestDefinition`/`StoryDefinition`/`DialogueDefinition`/
  `NpcDefinition` → DTO. Recopie champ par champ ; déplie `NegatedCondition` en `negate: true` ;
  couvre les 7 types d'objectif, 4 de récompense, 10 d'action et 7 de condition. Aucun accès
  disque, aucun état runtime.
- **`ContentPackSerializer`** : YAML canonique écrit à la main — ordre de clés fixe, familles
  triées par id, nœuds triés (départ d'abord), textes toujours entre guillemets doubles (échappe
  `\ " \n \r \t`), champs nuls/vides omis, `content` garde toujours ses 4 clés (`[]` si vide),
  indentation 2 espaces, `\n`. Reproductible octet-pour-octet.
- **`ContentPackAssembler`** (pur, `Supplier` injectés) : `exportAll` / `exportFamily` /
  `exportElement` / `exportSelection` + cas général `export(Set<ContentFamily>, Map<famille,Set<id>>,
  Instant)`. Manifest (`format`, `schemaVersion`, `exportedAt` injecté, `pluginVersion`, `generator`,
  `families`, `counts`). Tri déterministe par id ; `pluginVersion` défaillant → `"unknown"`.

### Plugin — action agent

- `AgentActionType.CONTENT_EXPORT` (`content.export`) — **lecture seule**.
- `AgentActions.exportContent(String family, List<String> ids)` → `ContentExportResult`
  (format, schemaVersion, family, counts, elements, yaml).
- `BukkitAgentActions.exportContent(...)` : construit l'assembleur depuis ses moteurs existants
  (`questEngine`, `storyService`, `dialogueEngine`, `npcEngine`) + `plugin.getPluginMeta().getVersion()`,
  sérialise, renvoie.
- `AgentActionExecutor.contentExport(action)` : valide `family` (whitelist), `ids` (motif
  `RESOURCE_ID`, ≤ 500, refusé avec `family=all`), enforce la borne **56 Kio** (`MAX_EXPORT_BYTES`)
  → `FAILED` lisible sinon. Résultat sous `details.{format,schemaVersion,family,elements,counts,bytes,pack}`.

### Control Panel

- `Permission.CONTENT_EXPORT` (+ accordée à OWNER via `allOf`, TESTER, CONTENT_EDITOR, READ_ONLY —
  miroir de `CONTENT_READ`).
- `AgentActionCatalog` : spec `content.export` (lecture, `CONTENT_EXPORT`, non sensible, pas de
  `refreshTypes`) + validation `family` (défaut `all`) / `ids` (séparateurs `,` ou espaces,
  normalisés en `a,b`, refusés avec `all`, motif validé, ≤ 500).
- `Http.attachment(exchange, filename, contentType, bytes)` — `Content-Disposition: attachment`,
  nom fixé côté serveur (jamais de saisie navigateur libre), en-têtes de sécurité.
- `ContentExportName.forFamily(family[, day])` → `lodyquests-<famille>-<AAAA-MM-JJ>.yaml`
  (famille inconnue → `content`).
- `ContentExportPages` : page `/content/export` — « Exporter tout le contenu », 4 boutons famille,
  formulaire sélection (famille + ids), « Exports récents » (table filtrée sur `content.export`
  avec état + éléments + taille + lien **Télécharger** pour les SUCCESS), fiche « à propos du
  format ». Formulaires POST vers le point d'entrée générique `/agents/action` (mêmes CSRF /
  validation / audit que toute action).
- `PanelApp` : routes `GET /content/export` (page) et `GET /content/export/download?action=<id>`
  (lit `result_json` de l'action `content.export` SUCCESS, extrait `details.pack`, stream en
  pièce jointe ; audit à l'enfilement **et** au téléchargement). `/content/export` ajouté à
  `safeReturnPath`.
- `Layout` : entrée de menu « Export contenu » (groupe RPGQuest) ; `Icons` : clé `export`.
- `plugadmin.css` : petit bloc `.export-*` (layout flex + responsive mobile). CSP inchangée.

### Sécurité

Par construction : les modèles source (`*Definition`) ne portent que du contenu éditorial — aucun
UUID joueur, aucune progression, aucun secret, aucun chemin système, aucun état runtime, aucun
binding Citizens, aucune position. L'export est bâti depuis les **registres en mémoire**, jamais
depuis des chemins de fichiers. Vérifié par test (`exportNeverContainsSecretsOrRuntimeOrSystemPaths` :
le YAML ne contient jamais `rcon_password`, `smtp_password`, `verygames_ftp`, `bearer `,
`/opt/plugadmin`, `/home/ubuntu`, `jdbc:mysql`, `authorization:`, `x-agent-token`, `player_uuid`,
`data.db`).

## Fichiers créés

Plugin :
* `content/pack/ContentPack.java`, `ContentFamily.java`, `PackText.java`
* `content/pack/QuestPackEntry.java`, `StoryPackEntry.java`, `DialoguePackEntry.java`, `NpcPackEntry.java`
* `content/pack/ContentPackMapper.java`, `ContentPackSerializer.java`, `ContentPackAssembler.java`
* `src/test/java/com/lodygames/rpgquest/content/pack/ContentPackSerializerTest.java` (11)
* `src/test/java/com/lodygames/rpgquest/content/pack/ContentPackAssemblerTest.java` (7)
* `src/test/java/com/lodygames/rpgquest/content/pack/ContentPackMapperTest.java` (4)

Control Panel :
* `panel/content/ContentExportName.java`
* `panel/web/ContentExportPages.java`
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/content/ContentExportNameTest.java` (3)

Docs :
* `docs/CONTENT_PACK.md` (format complet + exemple + stratégie schemaVersion + lien #109/#110)

## Fichiers modifiés

Plugin :
* `web/agent/AgentActionType.java` — `CONTENT_EXPORT`
* `web/agent/AgentActions.java` — `ContentExportResult` + `exportContent(...)`
* `web/agent/BukkitAgentActions.java` — `exportContent` (assembleur depuis les moteurs)
* `web/agent/AgentActionExecutor.java` — `case CONTENT_EXPORT` + `contentExport(...)` + `MAX_EXPORT_BYTES`
* `src/test/java/.../web/agent/StubAgentActions.java` + `AgentActionExecutorTest.java` (+6 tests)

Control Panel :
* `panel/authz/Permission.java` — `CONTENT_EXPORT`
* `panel/authz/Role.java` — accordée aux 4 rôles
* `panel/agent/AgentActionCatalog.java` — spec + validation `content.export`
* `panel/http/Http.java` — `attachment(...)`
* `panel/web/PanelApp.java` — routes `/content/export` + `/content/export/download`, `safeReturnPath`
* `panel/web/Layout.java` — entrée de menu ; `panel/web/Icons.java` — clé `export`
* `control-panel/src/main/resources/assets/plugadmin.css` — bloc `.export-*`
* `control-panel/src/test/java/.../panel/agent/AgentActionCatalogTest.java` (+2)
* `control-panel/src/test/java/.../panel/web/AuthenticatedSmokeTest.java` (+`/content/export`)

Docs : `docs/current_state.md`, `docs/RPGQUEST_BIBLE.md` (§5), `.ai/ROADMAP.md`,
`docs/deployment/SERVER_CHANGELOG.md`.

## Base de données / migrations

**Aucune.** Aucune table, aucune migration, aucune donnée persistante nouvelle côté plugin.
`control-panel.db` non touché (aucune migration de schéma agent — la nouvelle action réutilise la
table `agent_action` existante).

## Configuration / données

Aucun changement `config.yml` / `messages.yml`. Une nouvelle permission Control Panel
(`CONTENT_EXPORT`) accordée par défaut aux rôles en lecture — sans effet tant que le seul compte
réel est `OWNER` (qui a tout).

## Tests automatiques

- **Ciblés** (box contrainte, `-Xmx700m`) : `content.pack.*` (22), `AgentActionExecutorTest` (46,
  dont 6 nouveaux `content.export`), control-panel `AgentActionCatalogTest`, `ContentExportNameTest`
  (3), `AuthenticatedSmokeTest`, `authz.*` → **tous verts**.
- **Build complet** : `./gradlew build` → **BUILD SUCCESSFUL en 11 min 23 s**.
  - root `:test` : **1282 tests, 0 échec, 0 erreur**, 29 skipped (intégration MariaDB gated, pré-existants) — +28 vs avant.
  - `:control-panel:test` : **283 tests, 0 échec**, 1 skipped (pré-existant) — +4 vs avant.
  - `:web-api:test` : **30 tests, 0 échec** (inchangé).

Couverture des tests demandés par #108 :

| Attendu #108 | Test |
|---|---|
| export quête seule | `ContentPackAssemblerTest.exportElementFiltersToASingleId` |
| export story seule | `exportFamilyLeavesOtherSectionsEmpty` / `exportElement…` |
| export dialogue / PNJ | `ContentPackMapperTest`, `ContentPackSerializerTest` |
| export global | `exportAllIncludesEveryFamilyAndCounts` |
| références conservées | `storyMappingKeepsOrderedQuestReferences`, `storyAndNpcReferencesArePreserved`, `dialogueMappingUnwrapsNegatedConditions…` |
| `schemaVersion` + `format` présents | `formatAndSchemaVersionAreAlwaysPresent`, `emptyPackKeepsAStableShape` |
| sortie déterministe | `outputIsByteForByteDeterministic`, `serializationIsDeterministicForAFixedExportInstant` |
| Unicode / MiniMessage | `unicodeAndMiniMessageTextIsQuotedAndPreserved` |
| contenu vide | `emptyPackKeepsAStableShape` |
| IDs spéciaux valides | `unusualButValidIdsFallBackToQuotingWhenNeeded` |
| aucune donnée runtime/joueur/secrète | `exportNeverContainsSecretsOrRuntimeOrSystemPaths` |
| compat fixtures YAML | vocabulaire = schéma parseur (`ContentPackMapperTest` construit des modèles réels) |
| action agent : validation + bornes | `AgentActionExecutorTest` (famille inconnue, `ids` avec `all`, id malformé, dépassement 56 Kio) |
| catalogue panel : permission + params | `AgentActionCatalogTest` (`CONTENT_EXPORT`, non sensible, famille/sélection) |
| page rendue + téléchargement | `AuthenticatedSmokeTest` (`/content/export` 200 + bouton + formulaire) |

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — après déploiement AWS du Control Panel (mot de passe owner non
détenu) :

1. `/content/export` : rendu desktop **et** mobile ; entrée de menu « Export contenu ».
2. « Exporter tout le contenu » → l'action apparaît dans « Exports récents », passe SUCCESS après
   la réponse de l'agent, un lien **Télécharger** apparaît.
3. Le fichier téléchargé s'appelle `lodyquests-all-<date>.yaml`, commence par
   `format: lodyquests-content-pack` / `schemaVersion: 1`, contient `content:` avec les 4 familles.
4. « Exporter les quêtes » → `lodyquests-quests-<date>.yaml`, uniquement des quêtes.
5. Sélection : famille `quests` + `ids` = deux ids réels → seules ces quêtes dans le pack.
6. Un `family` invalide ou un `ids` malformé → message d'erreur clair, aucune action créée.
7. Ré-ouvrir un pack exporté dans un éditeur : vérifier lisibilité + cohérence des références
   (story → quêtes, quête → giver, PNJ → dialogue).
8. (si le contenu DEV est volumineux) « Exporter tout » qui dépasse ~56 Kio → échec lisible, pas de
   fichier tronqué ; export par famille OK.

## Résultat attendu

L'owner peut, depuis PlugAdmin, télécharger tout ou partie du contenu déclaratif du serveur dans un
`lodyquests-content-pack` YAML stable, versionné, sans secret, avec les références préservées —
prêt à être archivé, partagé, donné à une IA, et réimporté par le futur #109.

## Reset / retour à l'état initial

Rien à défaire : l'export est **lecture seule** et additif. Retirer la fonctionnalité = revenir au
commit précédent (`ff35b70`). Le contenu du serveur n'est jamais modifié par un export.

## Déploiement VeryGames

### À transférer

**Rien pour l'instant** (consigne : pas de déploiement). Au prochain déploiement de la branche :
Control Panel AWS (`scripts/plugadmin/deploy.sh`) pour activer `/content/export` ; le JAR RPGQuest
DEV seulement si on veut exporter depuis DEV (il embarque l'action `content.export`).

### Ne PAS transférer/altérer

`data.db`, mondes, Citizens, `config.yml`. VeryGames/Minecraft non concernés par ce changement.

### Redémarrage requis

Control Panel : `systemctl restart plugadmin` (via `deploy.sh`). Plugin : redémarrage si on
remplace le JAR DEV. Aucune migration.

### Déploiement effectué ?

**Non.** Développement seul. Voir `docs/deployment/SERVER_CHANGELOG.md` (entrée 2026-09-09).

## Rollback

Control Panel : `scripts/plugadmin/rollback.sh app`. Plugin : ancien JAR + restart. Aucun état à
défaire (action lecture seule, additive).

## Logs / diagnostic

- Action agent : ligne `agent.action.create type=content.export` dans l'audit PlugAdmin ;
  `content.export.download` à chaque téléchargement.
- Échec de dépassement de taille : message d'action `Pack trop volumineux pour le transport actuel
  (N o > 57344 o)`.

## Documentation mise à jour

`docs/CONTENT_PACK.md` (nouveau — format, familles, exemple complet multi-éléments, limites,
stratégie `schemaVersion`, lien #109/#110, emplacement dans le code), `docs/RPGQUEST_BIBLE.md`
(§5 sous-section « Export versionné du contenu »), `docs/current_state.md`, `.ai/ROADMAP.md`
(journal), `docs/deployment/SERVER_CHANGELOG.md`.

## Limitations / travail restant

- **Familles** : quêtes/stories/dialogues/PNJ. Items, recettes, marchands : ajoutables sans casser
  `schemaVersion 1`, non faits (modèles plus larges, hors MVP).
- **Transport 56 Kio** : « exporter tout » sur un très gros serveur est refusé proprement →
  exporter par famille/élément. Découpage/compression = évolution liée à #109/#110.
- **Boutons d'export par ligne** sur `/quests` `/stories` `/npcs` `/dialogues` : non faits ; la
  page dédiée `/content/export` couvre tous les critères #108 (dont l'export d'un élément via la
  sélection d'ids).
- **Import #109** : non fait (hors périmètre). L'architecture est prête : le pack est le contrat,
  le mapping DTO → modèle → parseur existant permettra le round-trip.
- **Validation navigateur** : `PENDING MANUAL VALIDATION` (mot de passe owner non détenu).
- **Non déployé.**

## Prochaine étape suggérée

**Issue #109** — import sécurisé d'un `lodyquests-content-pack` : upload → analyse → validation
(réutiliser `QuestDefinitionParser` / `StoryDefinitionParser` / `DialogueDefinitionParser` /
`NpcDefinitionParser` réels) → diff par élément → brouillon (#46) → confirmation. Permission
`CONTENT_IMPORT` dédiée (OWNER + CONTENT_EDITOR). Puis #110 (schéma officiel + doc pour IA +
dépendances déclarées).
