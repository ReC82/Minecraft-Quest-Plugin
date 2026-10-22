# #428 — Système d'icônes partagé PlugAdmin / portail (proposition phase 1)

Statut : **prototype à valider par le propriétaire**. Rien n'est intégré à PlugAdmin ni au portail.

## Inventaire de l'existant

| Surface | Iconographie actuelle | Contraintes |
|---|---|---|
| PlugAdmin (`control-panel`) | Bootstrap Icons servies en local (`/assets/bootstrap-icons/`), via `Icons.icon(...)` ; boutons d'action (modifier, supprimer, enregistrer) déjà iconés ; aucun visuel d'objet, de mob ou de bloc | CSP `default-src 'self'` (pas de CDN, pas de style en ligne) ; assets embarqués servis par `PanelApp#handleAsset` (ETag, cache 1 h, `image/svg+xml` déjà géré) |
| Portail (`web-api`, `PageLayout`) | Aucune icône ; style en ligne dans `PageLayout.STYLE`, thème sombre | CSP nginx `default-src 'self'; img-src 'self' data:` ; aucune route d'assets statiques aujourd'hui ; aucun script |

## Le jeu d'icônes

- Pixel art **original** 16×16 (`tools/icons.py`), 25 icônes : objets (épée, pioche, cristal, potion,
  pièces), nourriture (pain, tarte, pomme), nature/blocs (fleur, terre herbeuse, minerai, bois, coffre),
  créatures (mort-vivant, araignée, gelée, boss), personnages et actions (personnage, parler), quête
  et voyage (quête, carte, borne de voyage), statut (cœur de vie, expérience) et un repli `unknown`.
- Une seule source → un **sprite SVG** (`assets/lq-icons.svg`, ~27 Ko non compressé, quelques Ko en
  gzip) généré par `tools/build_sprite.py`. Rectangles fusionnés, `shape-rendering="crispEdges"`.
- Chaque `<symbol>` porte un `<title>` (libellé français).

## Règles d'usage

- **Tailles** : 16, 32, 48 px = multiples entiers de la grille, toujours nettes. 20 et 24 px = **tuile**
  (`.lq-tile`) contenant une icône de 16 px ; 44 px = tuile d'une icône de 32 px. Jamais de mise à
  l'échelle non entière d'une icône pixel art.
- **Accessibilité** : icône à côté d'un texte → `aria-hidden="true" focusable="false"` (le texte suffit) ;
  icône seule → `role="img" aria-label="…"`. Une icône ne remplace jamais un libellé ni une valeur
  technique (les identifiants restent affichés à côté).
- **Contraste** : chaque icône a un contour sombre ; sur fond sombre elle est posée dans une tuile
  (`--lq-tile-bg`, `--lq-tile-line` par thème) pour rester lisible (vérifié sur la palette clair/sombre).
- **Repli** : toute ressource inconnue → `lq-unknown`, jamais d'image cassée.
- **Texte** : aucune police pixel ni police Minecraft (marque Mojang) ; les textes restent en police système.
- **Actions** : les icônes d'action Bootstrap (modifier, supprimer, enregistrer…) sont conservées.

## Correspondance material / entité → icône

`assets/material-map.json` : nom exact (`BREAD`, `PUMPKIN_PIE`, `POPPY`, `SPIDER`…), puis suffixe
(`_SWORD`, `_PICKAXE`, `_ORE`, `_LOG`, `_WOOD`, `_PLANKS`, `_TULIP`), puis repli `unknown`.
La table est montrée dans `palette.html`.

## Décision et phase 2 — portail (23/10/2026)

La demande du propriétaire du 22/10 (#428) autorise à retenir la meilleure direction sans attendre.
**Variante B « RPG fantasy voxel » retenue** : c'est l'identité demandée (« fantasy / RPG / voxel
moderne »), elle garde les contrastes de la variante A et ne coûte rien de plus. Source : **icônes
originales** (option a) ; Kenney / Shikashi / renderchest non utilisés. Trois icônes ajoutées
(poisson, trophée, bouclier) : 28 au total.

Intégré au portail public (`web-api`) : sprite servi en local (`/assets/lq-icons.svg`), résolveur
`PortalIcons` (material, métrique), habillage B dans `PageLayout`, accueil, classements avec podium,
infos, statut, wiki, actualités, états vides. Canonique désormais :
`web-api/src/main/resources/portal/lq-icons.svg` et `material-map.json` (copies ici pour les
prototypes). Captures : `captures/phase2-portail/`. Test manuel : TC-411. Reste : PlugAdmin (phase 2b).

**Phase 2b — PlugAdmin (fiches de quête).** Le panel sert le **même** sprite et la **même** table
sous `/assets/lq/` : `control-panel/build.gradle.kts` les copie depuis `web-api/…/portal` au
`processResources` (aucune copie manuelle ; `PixelIconsTest` vérifie l'égalité octet par octet).
Résolveur `PixelIcons` : material exact → suffixe → icône du type d'objectif / de récompense →
`unknown`. Chaque ligne d'objectif et de récompense structurée de la page **Quêtes** porte une tuile
bois 24 px (`.lq-tile`, décorative). La table a été complétée (CREEPER, LEATHER, STICK, COBBLESTONE,
WHEAT, CACTUS, ARROW, armures `_HELMET`… , outils `_AXE`…) : tous les materials des quêtes livrées
ont une vraie icône (test de couverture). Captures : `captures/phase2b-plugadmin/`. Reste :
catalogues, PNJ, Voyages, Mobs.

## Intégration prévue (phases 2-3, après accord)

1. Sprite et `lq-icons.css` versionnés **une seule fois** et servis en local par les deux applications
   (PlugAdmin : `/assets/lq/…` déjà couvert par `handleAsset` ; portail : petite route d'assets
   statiques en lecture seule, ou sprite inclus en ligne — les deux respectent les CSP actuelles).
2. Un résolveur Java `material → icône` (même règles que le JSON) avec **test de couverture** : chaque
   icône référencée existe dans le sprite, chaque material des quêtes livrées obtient une icône ou le
   repli, et le repli est toujours présent.
3. Pages prioritaires PlugAdmin : fiches de quête (objectifs, récompenses), catalogues, PNJ, Voyages,
   Mobs spéciaux & boss. Portail : en-tête, menu, cartes, états vides. Captures 1280/390 px avant
   chaque déploiement, aucun défilement horizontal.
4. Aucun changement métier, aucune migration, aucune donnée privée publiée.
