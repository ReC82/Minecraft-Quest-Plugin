# Préparation de l'ajout des 600 noms de waypoints et du renommage des « Avant-poste » (issue #135)

> **Statut : PRÉPARATION — rien n'est appliqué.** Conformément à l'autorisation du propriétaire du
> 22/10/2026 (registre `docs/SUIVI_VALIDATIONS_DEV.md`, « Autorisation propriétaire #135 »), aucun
> nom n'est importé ni activé, aucun waypoint n'est renommé, aucun déploiement ne change le nommage
> réel. Seuls ont été produits : ce dossier, le plan `plan-renommage-avant-poste.md`, du code
> **inerte** (aucun appelant) et des tests sur fixtures isolées.

Relevé DEV utilisé : action agent `travel.catalog` en **lecture seule**, 23/10/2026 06:53 (Paris).

## 1. Le fichier candidat

| Point | Réponse |
|---|---|
| Fichier | `docs/waypoints/waypoint-names-proposition-lot1.txt` (créé par le diagnostic #135 du 21/10, PR #698) |
| Format | texte UTF-8, **un nom par ligne**, lignes `#` = commentaires — exactement le format de la réserve actuelle `src/main/resources/waypoint-names.txt`, lisible tel quel par `WaypointNameCatalog#load` |
| Provenance | généré hors ligne, **sans IA**, par la grammaire de la réserve actuelle (`<nom> de <nom>`, `<nom> des <pluriel>`, `<nom> <adjectif accordé>`) |
| 600 nouveaux ou 600 au total ? | **600 noms NOUVEAUX**, à **ajouter** aux 220 existants : réserve totale **820** |
| Doublons internes | **0** (comparaison insensible à la casse, aux accents et aux espaces de bord) |
| Collisions avec la réserve actuelle (220) | **0** |
| Collisions avec les 276 noms attribués sur DEV | **0** (dont « Allée du Vent » et les 55 « Avant-poste ») |
| Forme « Avant-poste N » (nom de secours) | **0** |
| Longueur | 8 à 24 caractères (32 noms dépassent 20) ; limite des noms saisis à la main : 40 (#134) |
| Mots | 203 noms en 2 mots, 397 en 3 mots |
| Caractères | lettres accentuées, espaces, apostrophe droite `'` (48 noms, comme la réserve actuelle) ; **aucun** chevron, balise MiniMessage, chiffre ni symbole — tous passent `WaypointNameCatalog#validateDisplayName` |
| Biomes | **aucune étiquette de biome** dans le fichier (comme la réserve actuelle) : un nom peut tomber sur n'importe quel biome. Le biome reste affiché à côté du nom (« — désert »). Le plan de renommage choisit, lui, des noms adaptés au biome (section 3) |

**Règles de déduplication** (inchangées, déjà dans le plugin) : espaces de bord retirés, espaces
intérieurs réduits à un seul, comparaison sans casse ni accents (`WaypointNameCatalog#normalize`) ;
première occurrence gardée. L'index SQL unique `idx_waypoints_display_name` reste la défense
finale contre un doublon attribué.

## 2. Import en mode aperçu (simulation)

### Code préparé, non branché

`waypoint.model.WaypointNameImportPreview#preview(lignes, réserveActive, nomsAttribués)` classe
chaque ligne sans rien écrire :

| Motif | Signification |
|---|---|
| accepté | nom qui serait ajouté |
| `INVALID_FORMAT` | trop court, trop long, caractère interdit (même règle que #134) |
| `FALLBACK_NAME` | forme « Avant-poste N », réservée aux noms de secours |
| `DUPLICATE_IN_FILE` | déjà plus haut dans le même fichier |
| `ALREADY_IN_RESERVE` | déjà dans la réserve active |
| `ALREADY_ASSIGNED` | déjà porté par un waypoint (ex. un nom saisi via #134) |

Il renvoie aussi le stock libre avant/après et si l'import ferait repasser au-dessus du seuil
d'alerte. **Aucune classe ne l'appelle** : pas d'action agent, pas de page PlugAdmin, aucun effet
sur DEV même après un déploiement.

### Résultat de l'aperçu sur le lot réel (test `WaypointNameImportPreviewTest`)

Réserve bundlée (220) + état DEV du 23/10 (220 attribués + « Allée du Vent » + 55 « Avant-poste ») :
**600 valides, 0 rejeté, 0 déjà présent** ; stock libre **0 → 600**.

### Deux façons d'appliquer — décision du propriétaire

| | (A) Rallonger la réserve du JAR | (B) Import depuis PlugAdmin |
|---|---|---|
| Ce qui change | les 600 noms sont ajoutés à `waypoint-names.txt` | nouvelle action agent + page « Importer des noms » utilisant l'aperçu ci-dessus, puis écriture |
| Stockage des noms | dans le JAR, aucun schéma | **à décider** : fichier `plugins/RPGQuest/waypoint-names-extra.txt` (sans migration) ou table SQL (migration) — décision de schéma |
| Délai | un lot court + déploiement JAR | plusieurs lots (agent, panel, droits, tests) |
| Retour arrière | redéployer le JAR précédent (sauvegarde datée du script officiel) | retirer le fichier/les lignes importées |
| Recommandation | **oui, maintenant** : arrête l'apparition de nouveaux « Avant-poste » | plus tard, si des imports réguliers sont voulus (#148) |

**Rollback dans les deux cas** : les noms **déjà attribués** depuis le lot restent en base (c'est
le nom du waypoint, persisté) ; une ancienne version les traite comme des noms hors catalogue,
toujours uniques (index SQL + ensemble des noms utilisés rechargé au démarrage). Rien d'autre à
annuler : aucune migration, aucun waypoint existant touché. Rallonger la réserve change l'ordre de
tirage des **futurs** noms uniquement.

## 3. Les « Avant-poste » : inventaire et correspondance proposée

Inventaire complet (identifiant stable, monde, biome, position, date de création) et tableau de
correspondance proposé : **`docs/waypoints/plan-renommage-avant-poste.md`** — **55** waypoints
« Avant-poste 1…55 » au 23/10 (26 au 21/10), 53 dans `wild`, 2 dans `world_hub`.

Application après accord, **sans nouveau code** : l'action existante `waypoint.rename` (#134,
PlugAdmin → Réseau de voyage → fiche du lieu → « Renommer ce lieu »), un waypoint à la fois. Elle ne
modifie que le nom affiché : identifiant `wp_…`, coordonnées, structure, borne appariée,
découvertes et voyages restent intacts ; un nom déjà pris est refusé (`DUPLICATE`) sans rien
écrire. Un renommage par lot (55 en une action) serait un développement séparé, non autorisé.

Ordre conseillé : renommer **avant** d'activer la réserve rallongée, pour que les 55 noms choisis
ne puissent pas être tirés entre-temps par un nouveau waypoint (sinon le renommage concerné est
simplement refusé et on choisit un autre nom).

## 4. Règle pour les futurs waypoints et seuil d'alerte (non activés)

- **Règle proposée** : inchangée dans son principe — tirage sans doublon dans la réserve
  (désormais 820 noms), nom de secours « Avant-poste N » conservé **uniquement comme filet de
  sécurité** (une génération ne doit jamais échouer).
- **Seuil d'alerte proposé : moins de 50 noms libres** (`PROPOSED_LOW_STOCK_THRESHOLD`, constante
  préparée, non branchée). Rythme observé : 47 waypoints le 20/10, 16 le 21/10, 13 le 22/10 —
  50 noms ≈ un à quatre jours d'exploration. Branchement proposé : une ligne d'avertissement au
  démarrage et à chaque nom tiré sous le seuil, et une entrée dans la page Diagnostics de PlugAdmin
  (#38). Avec 820 noms et 55 renommages, il resterait **545** noms libres.
- Rien de cela n'est actif : `WaypointNameCatalog` et `WaypointService` sont **inchangés**.

## 5. Chemin de validation propriétaire

1. **Relire** `waypoint-names-proposition-lot1.txt` : barrer/modifier les noms qui ne plaisent
   pas (tout nom modifié reste vérifié par les tests).
2. **Choisir** (A) réserve du JAR ou (B) import PlugAdmin (et, pour B, le stockage).
3. **Valider le plan de renommage** `plan-renommage-avant-poste.md` : « lot entier », ou liste
   des lignes acceptées / corrigées.
4. **Valider le seuil** de 50 noms libres (ou en donner un autre).
5. Répondre sur #135 ; la session suivante appliquera **uniquement** ce qui est accordé.

**Contrôles de non-régression prévus à l'application** : `WaypointNameImportPreviewTest` et
`WaypointNameExhaustionDiagnosticTest` (lot valide, disjoint, plan cohérent), `WaypointServiceTest`
(renommage, doublon refusé) ; après déploiement, relevé `travel.catalog` en lecture seule : nombre
de waypoints identique, identifiants et coordonnées identiques, plus aucun nouveau
« Avant-poste ». En jeu (**PENDING MANUAL VALIDATION**) : un waypoint renommé affiche son nouveau
nom sur les panneaux et dans le menu de voyage, et le voyage vers lui fonctionne toujours.
