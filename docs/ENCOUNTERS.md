# PNJ récurrents par biome (issue #13)

Des PNJ « de rencontre » apparaissent automatiquement dans le Wild, un par **instance de biome**
explorée, et proposent des quêtes secondaires d'exploration. Un même **modèle** (brigand, marchand
égaré, forgeron perdu…) se retrouve dans de nombreuses instances : c'est un type de PNJ
*récurrent*, jamais un spawn infini.

> Statut : **étapes 1 à 4 livrées** (moteur, modèles, persistance, apparition sûre, réparation ;
> page PlugAdmin « PNJ récurrents » et actions d'administration ; édition des modèles depuis le
> panel et apparence `skin_url` ; quêtes secondaires renouvelables avec délai anti-farm et filtre
> PlugAdmin « Secondaires / Rencontres »). À venir : journal joueur des rencontres.

## Instance de biome

Le découpage est **celui des waypoints** (`waypoint.model.BiomeInstanceKey`) :
`(monde, biome, tuile de travel.waypoint.region-size blocs)`. Deux forêts éloignées sont deux
instances ; une forêt et une plaine dans la même tuile aussi. Les deux systèmes partagent ce
découpage mais **pas leurs états** : une instance peut avoir un waypoint et une rencontre, l'un
n'attend jamais l'autre, et rien n'est modifié dans la génération des waypoints. Compromis hérité :
une très grande forêt à cheval sur deux tuiles peut accueillir deux rencontres.

## Modèles — `plugins/RPGQuest/encounters/*.yml`

Un fichier par modèle. Trois exemples sont copiés au premier démarrage, jamais réécrits ensuite.

| Champ | Obligatoire | Rôle |
| --- | --- | --- |
| `id` | oui | identifiant du modèle (`[a-z0-9._-]`) |
| `display_name` | oui | nom affiché au-dessus du PNJ (texte simple, 64 car., sans balise) |
| `biomes` | oui | biomes éligibles ; `forest` = `minecraft:forest` |
| `npc_id` | non (défaut `id`) | identité RPGQuest de tous les PNJ du modèle : dialogue `rpgquest:<npc_id>`, objectifs « parler à » |
| `description`, `role`, `family` | non | libellés pour l'administration, sans effet mécanique |
| `worlds` | non (vide) | restreint à certains mondes autorisés |
| `weight` | non (10) | poids du tirage si plusieurs modèles acceptent le même biome (1–1000) |
| `max_sites` | non (0) | plafond d'occurrences du modèle ; 0 = pas de plafond propre |
| `skin_url` | non | lien MineSkin `https://minesk.in/…` de l'apparence ; absent = apparence par défaut |
| `enabled` | non (`true`) | `false` = plus aucune nouvelle occurrence |
| `quest_catalogs` | non (vide) | catalogues de quêtes proposés par `OFFER_CATALOG_QUEST` (issue #369, voir plus bas) |

Livrés : `forest_bandit` (forêts → `encounter_bandit`), `desert_merchant` (désert et badlands →
`encounter_merchant`), `mountain_smith` (collines, prairies, pics → `encounter_smith`), chacun
avec un dialogue d'accueil `dialogues/encounter_*.yml`, partagé par toutes ses occurrences.

Un fichier invalide est rejeté seul (les autres chargent) ; un biome inconnu du serveur est signalé
dans les logs. Les modèles sont relus au démarrage, ou à chaud par « Relire les modèles » dans
PlugAdmin (aucun PNJ déjà apparu n'est touché). Ils se créent et se modifient aussi depuis
PlugAdmin (voir « Édition des modèles ») : le fichier est alors **réécrit** par le plugin
(commentaires perdus, champs dans un ordre fixe).

## Apparition

Quand un joueur **entre** dans une instance (mouvement throttlé, comme les waypoints) :

1. le monde doit être autorisé (`encounters.worlds`, vide = `travel.wild-world`) ; Hub et monde des
   claims sont **toujours** exclus ;
2. Citizens doit être actif, `encounters.enabled` vrai, le plafond `max-per-world` non atteint ;
3. un modèle actif, autorisé dans ce monde et ce biome, sous son plafond, est tiré (pondéré,
   **déterministe** par instance). Aucun modèle → rien, jamais un modèle d'un autre biome ;
4. jusqu'à `candidate-attempts` points sont testés entre `min-distance` et `max-distance` du joueur :
   chunk **déjà chargé** (aucun chargement forcé), même instance, sol accessible
   (`RandomSafeLocationFinder.findAccessibleColumn` : pas de canopée, pas d'eau ni de lave, deux
   blocs d'air), hors claim, à plus de `waypoint-clearance` d'un waypoint et de `minimum-spacing`
   d'une autre rencontre ;
5. la ligne `encounter_sites` est écrite **d'abord** (index unique `(world, biome_instance)`), puis
   le PNJ Citizens est créé, marqué `rpgquest-encounter = <id d'occurrence>`, regard vers les joueurs.

Pas d'emplacement : nouvel essai après 1 min, 5 min, 30 min puis 1 h (en mémoire, remis à zéro au
redémarrage), journalisé `NO_SAFE_SPOT`. Rien n'est généré au démarrage.

## Stabilité et réparation

- Position mémorisée : le PNJ réapparaît au même endroit, pour tous les joueurs.
- PNJ supprimé à la main (`/npc remove`) : recréé à la prochaine entrée dans l'instance ou au
  prochain chargement de son chunk — jamais deux.
- Arrêt brutal entre l'écriture et l'enregistrement du PNJ : le PNJ marqué est **réadopté**.
- Politique MVP : **présence stable**, aucune disparition aléatoire. Désactiver un modèle empêche
  les nouvelles occurrences sans retirer les existantes ; `encounters.enabled: false` arrête
  apparitions et réparations sans retirer les PNJ déjà présents.
- Une occurrence désactivée par un administrateur perd son PNJ et ne génère plus rien ; une
  occurrence retirée libère l'instance (nouvelle rencontre possible à la visite suivante). Ces
  actions sont exposées dans PlugAdmin (voir ci-dessous).

## Administration — PlugAdmin « PNJ récurrents » (étape 2)

Page `/npcs/encounters`, bouton « PNJ récurrents » de la page PNJ. Elle lit l'action agent
`encounter.list` (bouton « Actualiser ») :

- **Vue d'ensemble** : apparitions activées ou non, mondes réellement concernés (Hub et claims
  retirés), plafond par monde, nombre de modèles et d'occurrences, fichiers de modèle refusés au
  dernier chargement avec leur motif ;
- **Modèles** : nom, `npc_id`, rôle/famille, biomes, mondes, poids, occurrences / plafond, statut
  (actif / désactivé). Une ligne compacte par modèle ; les biomes sont résumés par famille
  (« Forêts — 5 biomes », liste complète en dépliant le résumé) ;
- **Occurrences par monde** : identifiant, biome, position (avec la commande `/tp @s x y z` pour
  la localiser), PNJ Citizens (`présent`, `disparu` = enregistré mais absent du registre Citizens,
  `inconnu` = Citizens indisponible), état, dernière erreur ;
- **Journal récent** : créations, échecs, réparations, désactivations — en mémoire, vidé au
  redémarrage.

Affichage (#360) : modèles et occurrences sont des listes en grille, pas des tableaux. Le panneau
« Gérer » se déplie sur toute la largeur sous sa ligne. Quand la zone de contenu fait moins de
820 px (mobile, fenêtre étroite), chaque ligne devient une carte avec ses libellés, sans défilement
horizontal. À partir de 8 modèles (ou 8 occurrences dans un monde), un champ de filtre (nom,
identifiant, PNJ, biome) apparaît au-dessus de la liste. À l'impression, la barre latérale et les
actions sont masquées.

| Action | Permission | Effet |
|---|---|---|
| `encounter.list` | `NPC_READ` | lecture seule |
| `encounter.retry` | `NPC_SPAWN_WRITE`, confirmation | réactive l'occurrence, efface son délai, fait apparaître le PNJ si sa zone est chargée |
| `encounter.disable` | `NPC_SPAWN_WRITE`, confirmation | retire le PNJ, l'instance ne génère plus rien |
| `encounter.remove` | `NPC_SPAWN_WRITE`, confirmation | retire PNJ et occurrence ; la zone pourra en accueillir une nouvelle |
| `encounter.templates.reload` | `NPC_WRITE` | relit `encounters/*.yml` |
| `encounter.backoffs.clear` | `NPC_WRITE` | efface les délais d'attente après échec |

Chaque action vise **une** occurrence nommée (`enc_…`), jamais un lot.

## Édition des modèles — PlugAdmin (étape 3)

Sur `/npcs/encounters`, section **Modèles** : bouton **Nouveau modèle**, et pour chaque modèle
« Gérer » → **Désactiver / Activer le modèle**, **Modifier**, **Dupliquer** (formulaire pré-rempli,
nouvel identifiant à saisir), **Supprimer**. Champs courants : identifiant (fixe après création :
c'est le nom du fichier), nom affiché, `npc_id`, rôle, famille, biomes (un par ligne ou séparés par
des virgules ; `forest` = `minecraft:forest`), actif. Repliés sous « Paramètres avancés » :
description, mondes, poids, plafond d'occurrences, lien MineSkin.

| Action | Permission | Effet |
|---|---|---|
| `encounter.template.create` | `NPC_WRITE` | crée `encounters/<id>.yml` ; refusé si l'id existe déjà (dans n'importe quel fichier) |
| `encounter.template.update` | `NPC_WRITE` | réécrit le fichier du modèle (atomique) |
| `encounter.template.toggle` | `NPC_WRITE` | `enabled: true/false` ; désactivé = plus aucune nouvelle occurrence, les existantes restent |
| `encounter.template.delete` | `NPC_DELETE`, confirmation | copie dans `plugins/RPGQuest/encounter-backups/<date>-<fichier>` puis supprime le fichier |

- Le panel n'envoie jamais de YAML : les champs sont validés par **le même parseur que les
  fichiers** (`EncounterTemplateParser`), le plugin produit le texte, l'écrit, le relit, puis
  recharge les modèles — effet immédiat pour les **prochaines** apparitions.
- Avertissements renvoyés (sans bloquer) : biome inconnu du serveur, dialogue
  `rpgquest:<npc_id>` absent, occurrences existantes qui gardent un ancien `npc_id`.
- **Ce qui ne change pas** pour les PNJ déjà apparus : leur position, leur identité (`npc_id`
  enregistré à l'apparition) et leur nom. Le nom et l'apparence du modèle s'appliquent à la
  prochaine création ou réparation ; « Retenter » sur un PNJ présent réapplique l'apparence.
- Supprimer un modèle qui a encore des occurrences est **refusé** sauf accord explicite (case
  « Laisser ces occurrences en place ») : elles gardent alors leur PNJ et leur dialogue mais ne
  sont plus rattachées à un modèle chargé. Pour les faire disparaître, les « Retirer » d'abord.

## Apparence (étape 3)

À chaque création, réadoption ou réparation d'un PNJ de rencontre de type joueur, RPGQuest pose
la texture du `skin_url` du modèle, résolue par `MineSkinResolver` (API publique MineSkin,
asynchrone, mise en cache) puis appliquée sur le thread principal, seulement si l'occurrence
désigne encore ce PNJ. Sans `skin_url`, l'**apparence par défaut** embarquée est posée sans aucun
appel réseau — sinon Citizens donnerait au PNJ le skin du compte Minecraft qui porte son nom.
Un échec (lien inconnu, MineSkin injoignable) est journalisé `SKIN_FAILED`, jamais remplacé par
une autre apparence. La colonne « PNJ en jeu » de la page affiche l'état depuis le démarrage du
serveur : *apparence du modèle*, *apparence par défaut*, *en cours*, *en échec : motif* (vide =
rien tenté depuis le démarrage). Les PNJ apparus avant l'étape 3 gardent leur apparence actuelle
jusqu'à un « Retenter » ou une réparation. Le roster
`npc.citizens.list` porte `encounterSiteId` : un PNJ de rencontre n'apparaît plus parmi les PNJ
« à rattacher » de la page PNJ, ni comme `CITIZENS_ONLY` dans les diagnostics.

## Identité, dialogue, quêtes

Les PNJ de rencontre **ne passent pas** par `npc_citizens_bindings` (un `npc_id` n'y est lié qu'à
un seul PNJ Citizens). Leur identité vient de `encounter_sites` : `NpcIdentityService.currentId`
consulte d'abord les liaisons classiques, puis le registre des rencontres. Le clic droit ouvre donc
`rpgquest:<npc_id>` comme pour tout PNJ ; dialogue et progression restent personnels à chaque
joueur, le PNJ physique est partagé. Une occurrence désactivée ne porte plus d'identité.

## Quêtes secondaires renouvelables (étape 4)

Chaque modèle livré donne **une** quête secondaire modeste, partagée par toutes ses occurrences
(le PNJ est commun, la progression propre à chaque joueur) :

| PNJ (`npc_id`) | Quête | Objectif | Récompense | Délai |
| --- | --- | --- | --- | --- |
| Brigand des bois (`encounter_bandit`) | `rpgquest:encounter_bandit_spiders` « Les toiles du sous-bois » | tuer 6 araignées, puis parler à un Brigand des bois | 25 XP + 16 flèches | 20 h |
| Marchand égaré (`encounter_merchant`) | `rpgquest:encounter_merchant_cactus` « Marchandise du désert » | remettre 12 cactus (plusieurs fois possible) | 20 XP + 6 pains | 20 h |
| Forgeron perdu (`encounter_smith`) | `rpgquest:encounter_smith_ore` « Du minerai pour la forge » | remettre 6 fer brut + 8 charbons | 30 XP + 2 lingots de fer | 20 h |

Règles :

- **Aucune quête sans acceptation** : le dialogue `encounter_*.yml` montre l'offre (nom, but,
  objectif, récompense) puis **Commencer (j'accepte)** (seul porteur du `START_QUEST`), **Plus tard** (retour à
  l'accueil) ou **Refuser** (rien n'est démarré, l'offre reste).
- L'offre n'est affichée que si la quête est **acceptable maintenant** — condition de dialogue
  `QUEST_AVAILABLE`, qui réutilise la règle d'acceptation du moteur (`availability`) : ni active,
  prérequis remplis, délai écoulé.
- **Anti-farm** : `cooldown: 20h` dans le fichier de quête. La date de complétion est
  l'`updated_at` de la ligne `COMPLETED` de `quest_progress` (aucune migration). Pendant le délai,
  le PNJ répond « Encore du travail ? → repasse d'ici un jour » ; une tentative directe
  (`/quest accept`) répond « Rien de nouveau pour l'instant… reviens dans 3 h 20 min »
  (`messages.yml` → `quest.on-cooldown`). Un abandon ne déclenche pas de délai (il ne rapporte rien).
- Remises (`DELIVER_ITEM_TO_NPC`) : moteur #123 (retrait exact, dépôt partiel persistant, anti
  double-clic), via « J'ai des objets » dans le dialogue.
- Récompenses payées une fois par complétion (`quest_reward_grants`, occurrence par reprise).
- PlugAdmin `/quests` : second groupe de filtres **Tous types / Principales / Secondaires /
  Rencontres** (catégorie `encounter` ou donneur `encounter_*`), badges « rencontre » et
  « recharge 20 h » ; l'éditeur de quête a un champ « Délai avant de pouvoir la refaire ».

Fichiers : `quests/encounter_*.yml` (seedés s'ils manquent, `BUNDLED_EXAMPLES`) et
`dialogues/encounter_*.yml` (fichiers existants sur le serveur : à remplacer explicitement au
déploiement, `--also`).

## Catalogues de quêtes secondaires (issue #369)

Un modèle n'est plus limité à la quête écrite dans son dialogue : il peut proposer les quêtes d'un
ou plusieurs **catalogues**, tirées au sort pour chaque joueur.

### Fichiers — `plugins/RPGQuest/quest-catalogs/*.yml`

Un fichier par catalogue. Un catalogue ne **copie** aucune quête : il référence des quêtes
existantes, qui gardent leurs objectifs, prérequis, récompenses et `cooldown:`.

```yaml
id: bandit_contracts            # [a-z0-9._-], = nom du fichier
display_name: "Contrats du brigand"
description: "Facultatif, pour les administrateurs"
enabled: true                   # false = ne propose plus rien (les quêtes acceptées continuent)
avoid_recent: 1                 # 0–10 : dernières quêtes terminées écartées tant qu'une autre est possible
entries:                        # ordre d'affichage ; une quête au plus une fois ; 100 au plus
  - quest: rpgquest:encounter_bandit_spiders   # sans « : » = rpgquest:…
    weight: 10                  # 1–1000, poids relatif du tirage (défaut 10)
    enabled: true               # false = gardée dans la liste, jamais tirée
```

Exemple livré une fois : `encounter_side_quests.yml` (les trois quêtes de rencontre), rattaché à
**aucun** modèle. Un fichier invalide est rejeté seul ; un `id` présent dans deux fichiers est
rejeté dans les deux.

### Attribution — champ `quest_catalogs` du modèle

```yaml
# encounters/forest_bandit.yml
quest_catalogs: [bandit_contracts]
```

Absent = aucun catalogue (comportement antérieur inchangé). Plusieurs modèles peuvent partager un
catalogue ; plusieurs catalogues sur un même modèle sont fusionnés (une quête partagée garde son
plus fort poids). Le champ est lu à chaque conversation : **toutes** les rencontres du modèle,
existantes comprises, l'appliquent aussitôt, sans être recréées.

### Dialogue — action `OFFER_CATALOG_QUEST`

```yaml
- text: "Du travail ?"
  actions:
    - type: OFFER_CATALOG_QUEST   # catalog: <id> facultatif ; absent = catalogues des modèles du PNJ
```

L'action affiche un écran généré (le locuteur est celui du nœud de départ du dialogue) :

| Situation du joueur | Écran | Boutons |
|---|---|---|
| une quête est proposable | « J'aurais une tâche pour toi : *titre* », description, cadence de renouvellement | **Commencer (j'accepte)** · **Plus tard** (retour à l'accueil) · **Refuser** (rien n'est attribué) |
| une quête du catalogue est en cours | « Termine d'abord *titre* » | Retour · Au revoir |
| rien de possible | « Rien pour le moment. Repasse d'ici 3 h 20 min. » (ou sans délai si aucune ne reviendra) | Retour · Au revoir |
| PNJ sans catalogue actif | « Je n'ai aucun travail à confier pour l'instant. » | Retour · Au revoir |

Règles du tirage (`CatalogOfferSelector`, fonction pure) :

- **Éligibilité** : la règle d'acceptation du moteur (`QuestProgressEngine#availability`) —
  prérequis, état, non répétable déjà faite, `cooldown:` en cours. Une quête absente (supprimée,
  non publiée) n'est simplement jamais proposée : ni erreur, ni progression touchée.
- **Une à la fois** : tant qu'une quête des catalogues du PNJ est en cours, il la rappelle.
- **Anti-répétition** : les `avoid_recent` quêtes terminées le plus récemment sont écartées tant
  qu'une autre est possible.
- **Stabilité sans stockage** : tirage pondéré déterministe, graine = (joueur, `npc_id`, date de
  la dernière complétion d'une quête de ces catalogues). Fermer/rouvrir, changer d'occurrence du
  même modèle, se reconnecter ou redémarrer le serveur redonne **la même** offre ; seule une
  quête terminée fait tourner la sélection. Retirer ou ajouter une *autre* quête ne change pas
  l'offre (sauf si la nouvelle l'emporte au tirage). Aucune migration, aucune table.
- **Acceptation** : « Commencer » accepte exactement la quête affichée, via `accept` du moteur
  (anti-double-clic, revérification). Une offre devenue impossible entre-temps répond « Cette
  proposition n'est plus disponible ». Récompenses et délai restent ceux de la quête.
- Progression et offre sont propres à chaque joueur ; le PNJ est commun.
- Limite connue : abandonner une quête **renouvelable déjà terminée puis reprise** efface sa date
  de complétion (la ligne repasse `ABANDONED`) ; la graine revient alors à la complétion
  précédente et l'offre peut changer. Aucun gain possible : l'abandon ne rapporte rien.

### PlugAdmin — Quêtes › **Catalogues de quêtes** (`/quests/catalogs`)

Lecture : `quest.catalog.list` (`NPC_READ`). Pour chaque catalogue : état, quêtes proposables /
total, modèles qui l'utilisent, entrées (titre, identifiant, catégorie, étapes, recharge ou « une
seule fois », poids, état) et contrôles : quête absente, quête non renouvelable, catalogue vide ou
désactivé, attribué à aucun modèle. Contrôles transverses en tête de page : modèle référençant un
catalogue inconnu, modèle avec catalogues dont le dialogue n'a pas d'`OFFER_CATALOG_QUEST`,
modèle actif sans catalogue.

| Action | Permission | Effet |
|---|---|---|
| `quest.catalog.create` | `QUEST_CONTENT_WRITE` | crée `quest-catalogs/<id>.yml` ; refusé si l'id existe |
| `quest.catalog.update` | `QUEST_CONTENT_WRITE` | réécrit le fichier (atomique), relu avant succès |
| `quest.catalog.delete` | `QUEST_CONTENT_WRITE`, confirmation | copie dans `quest-catalog-backups/<date>-<fichier>` puis supprime ; refusé s'il est attribué, sauf case « Retirer aussi le catalogue de ces modèles » (`detach=true`) |

Formulaire : une ligne par quête (numéro d'ordre, quête cherchée par **titre ou identifiant** dans
la liste proposée, poids, active, retirer) et trois lignes vides pour en ajouter ; *Dupliquer*
pré-remplit un nouveau catalogue. Comme pour les modèles, le panel n'envoie jamais de YAML : le
plugin valide avec le parseur des fichiers. Effet immédiat en jeu, sans publication. Le formulaire
des modèles (`/npcs/encounters`) a un champ **Catalogues de quêtes** (identifiants séparés par des
virgules) ; à l'enregistrement, un catalogue inconnu ou un dialogue sans offre est signalé.

Non fait dans ce lot : mode « choix parmi N », conditions de monde/biome/niveau par entrée,
protection de la suppression d'une **quête** encore référencée par un catalogue (elle devient
« quête absente », signalée, sans erreur en jeu), et les dialogues livrés `encounter_*.yml`
restent sur leur quête fixe — l'administrateur choisit quand basculer un PNJ sur un catalogue.

## Journal du joueur (étape 5)

Le journal `/quests` (ou l'item Journal des quêtes) montre les quêtes de rencontre sans nouvel
onglet ni nouvelle table :

- toute quête de rencontre (catégorie `encounter` ou donneur `encounter_*`, même règle que le
  panel) porte la ligne **« Rencontre — quête secondaire »** ;
- dans **Quêtes terminées**, une quête répétable affiche **« À refaire dans 19 h 40 min »** pendant
  son `cooldown:`, puis **« Peut être refaite auprès d'un PNJ de rencontre »** ; la vue détail
  ajoute **« Renouvelable : toutes les 20 h »** ;
- le temps restant vient de `QuestProgressEngine#remainingCooldowns` (une lecture asynchrone de
  `quest_progress`, mêmes règles que le refus `ON_COOLDOWN`). En cas d'erreur de lecture, ces
  lignes sont omises plutôt que d'annoncer une quête disponible à tort ;
- une quête reprise repasse dans **Quêtes en cours** (état `ACTIVE`), comme toute quête.

Non fait volontairement : un registre des PNJ de rencontre « croisés » par joueur (il faudrait
une nouvelle table — décision de schéma non prise). Le content pack exporte désormais `cooldown`
(`20h`, sans arrondi) et le schéma du panel le déclare.

## Configuration — `config.yml`, section `encounters`

| Clé | Défaut | Bornes |
| --- | --- | --- |
| `enabled` | `true` | |
| `worlds` | `[]` (= `travel.wild-world`) | Hub/claims toujours exclus |
| `max-per-world` | 200 | 0–5000 |
| `min-distance` / `max-distance` | 24 / 64 | 8–128 / min–160 |
| `candidate-attempts` | 12 | 1–32 |
| `move-throttle-millis` | 2000 | 250–60000 |
| `minimum-spacing` | 96 | 0–1024 |
| `waypoint-clearance` | 12 | 0–64 |

## Persistance

Migration **V34** : table `encounter_sites` (id, world, biome_instance, biome_key, region_x/z,
template_id, npc_id, x/y/z, citizens_uuid, citizens_id, status `ACTIVE|DISABLED`, last_error,
created_at, updated_at), index unique `(world, biome_instance)`. Additive, automatique.

## Validation en jeu

`PENDING MANUAL VALIDATION` — `docs/MANUAL_TEST_PLAN.md`, TC-303 (jeu), TC-304 (PlugAdmin),
TC-305 (modèles/apparence), TC-306 (quêtes secondaires renouvelables) et TC-314 (catalogues, #369).
