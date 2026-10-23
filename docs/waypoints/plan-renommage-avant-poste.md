# Plan de renommage PROPOSÉ des waypoints « Avant-poste N » (issue #135)

> **Statut : PROPOSITION — rien n'est appliqué.** Aucun waypoint n'a été renommé, aucun nom n'a
> été importé ni réservé. Ce tableau attend une validation du propriétaire, **ligne par ligne ou
> par lot explicite** (voir `preparation-import-lot1.md`, section 5).

## Source de l'inventaire (lecture seule)

- Action agent `travel.catalog` (lecture seule, la même que le bouton « Rafraîchir » de
  PlugAdmin) exécutée sur DEV le **23/10/2026 à 06:53** (heure de Paris).
- **276 waypoints** : 220 noms de la réserve (tous pris), 1 nom renommé (« Allée du Vent », #134),
  **55 « Avant-poste 1 » à « Avant-poste 55 »** (suite continue, aucun doublon).
- Le diagnostic du 21/10 en comptait 26 : **29 de plus en deux jours** (16 le 21/10, 13 le
  22/10). Tant que la réserve n'est pas rallongée, chaque nouveau waypoint reçoit ce nom.
- 53 sont dans `wild`, 2 dans `world_hub` (Avant-poste 12 et 43, appariés à une borne).

## Ce que le renommage change — et ne change pas

Le renommage proposé utilise exactement le mécanisme de #134 (`waypoint.rename`) : **seul le nom
affiché change** (panneaux, menu de voyage, PlugAdmin). Restent identiques : l'identifiant
`wp_…` (clé des découvertes, des voyages, des bornes appariées), les coordonnées, la structure,
le biome, les découvertes des joueurs. Un renommage est réversible (on peut renommer à nouveau ;
l'ancien nom « Avant-poste N » redevient libre).

## Méthode de choix des noms proposés

Noms pris **uniquement dans le lot candidat** `waypoint-names-proposition-lot1.txt` (600 noms),
un nom par waypoint, jamais deux fois. Pour coller au paysage, chaque biome reçoit en priorité
des noms dont le premier mot évoque ce biome, en tournant entre plusieurs mots pour éviter les
répétitions voisines :

| Biomes | Premiers mots privilégiés |
|---|---|
| forêts (forest, birch, old_growth_birch, flower_forest) | Futaie, Chênaie, Bosquet, Sous-Bois, Lisière, Fourré, Charme, Houx, Sorbier, Viorne |
| taïgas | Sapinière, Futaie, Crête, Lisière |
| plaines, prairies (plains, sunflower_plains, meadow) | Plaine, Pâtis, Ferme, Bergerie, Mas, Halte, Carrefour, Trèfle, Draille, Vallon, Vallonnet |
| savane | Plateau, Figuier, Halte, Draille, Menhir |
| désert | Dune, Arche, Menhir, Dolmen, Gîte |
| plages, côtes rocheuses | Anse, Phare, Môle, Havre, Lagune, Corniche |
| rivières | Gué, Berge, Méandre, Ruisseau, Saulaie, Roseau |
| badlands | Escarpement, Ravin, Gouffre, Pic, Corniche, Crête |
| jungles | Fourré, Cascade, Lagune, Ravine |

Le complément (« d'Orge », « de Merle »…) vient tel quel du lot : certaines associations sont
fantaisistes (« Plateau de Pomme ») — **c'est l'endroit à corriger en priorité** à la relecture.
Remplacer un nom par n'importe quel autre nom du lot (ou un nom libre valide) est sans risque.

Le test `WaypointNameImportPreviewTest` vérifie à chaque build que ce tableau compte 55 lignes,
un identifiant distinct par ligne, et que chaque nom proposé est un nom accepté du lot, unique.

## Tableau de correspondance proposé

| N° | Nom actuel | Identifiant stable (inchangé) | Monde | Biome | Position (x, y, z) | Créé (UTC) | Nom proposé |
|---|---|---|---|---|---|---|---|
| 1 | Avant-poste 1 | `wp_wild_forest_7_16` | wild | forest | 1868, 72, 4206 | 2026-10-20 13:35 | Futaie d'Abeille |
| 2 | Avant-poste 2 | `wp_wild_savanna_9_-3` | wild | savanna | 2503, 79, -576 | 2026-10-20 13:46 | Plateau de Pomme |
| 3 | Avant-poste 3 | `wp_wild_savanna_10_-3` | wild | savanna | 2606, 64, -561 | 2026-10-20 13:46 | Figuier d'Ambre |
| 4 | Avant-poste 4 | `wp_wild_plains_-11_-7` | wild | plains | -2696, 74, -1721 | 2026-10-20 14:28 | Plaine d'Orage |
| 5 | Avant-poste 5 | `wp_wild_sunflower_plains_-10_-7` | wild | sunflower_plains | -2501, 64, -1740 | 2026-10-20 14:29 | Pâtis d'Orage |
| 6 | Avant-poste 6 | `wp_wild_jungle_-3_2` | wild | jungle | -580, 102, 571 | 2026-10-20 16:01 | Fourré de Craie |
| 7 | Avant-poste 7 | `wp_wild_plains_-4_3` | wild | plains | -828, 64, 796 | 2026-10-20 16:04 | Ferme de Brise |
| 8 | Avant-poste 8 | `wp_wild_savanna_-2_-2` | wild | savanna | -420, 71, -492 | 2026-10-20 16:12 | Halte d'Argent |
| 9 | Avant-poste 9 | `wp_wild_beach_-1_-8` | wild | beach | -226, 64, -1908 | 2026-10-20 16:14 | Anse de Corbeau |
| 10 | Avant-poste 10 | `wp_wild_plains_-2_-8` | wild | plains | -292, 64, -1862 | 2026-10-20 16:15 | Bergerie de Merle |
| 11 | Avant-poste 11 | `wp_wild_desert_7_-3` | wild | desert | 2023, 76, -748 | 2026-10-20 16:17 | Dune de Genièvre |
| 12 | Avant-poste 12 | `wp_world_hub_beach_3_-3` | world_hub | beach | 824, 67, -681 | 2026-10-20 18:13 | Phare de Cendre |
| 13 | Avant-poste 13 | `wp_wild_eroded_badlands_-1_2` | wild | eroded_badlands | -64, 153, 713 | 2026-10-20 19:24 | Escarpement de Martinet |
| 14 | Avant-poste 14 | `wp_wild_eroded_badlands_-1_3` | wild | eroded_badlands | -135, 105, 775 | 2026-10-20 19:24 | Ravin d'Aube |
| 15 | Avant-poste 15 | `wp_wild_stony_shore_-1_3` | wild | stony_shore | -137, 72, 886 | 2026-10-20 19:25 | Môle de Lièvre |
| 16 | Avant-poste 16 | `wp_wild_desert_0_3` | wild | desert | 32, 74, 995 | 2026-10-20 19:26 | Arche d'Orge |
| 17 | Avant-poste 17 | `wp_wild_desert_-1_4` | wild | desert | -21, 70, 1071 | 2026-10-20 19:26 | Menhir Caché |
| 18 | Avant-poste 18 | `wp_wild_old_growth_birch_forest_7_9` | wild | old_growth_birch_forest | 1916, 61, 2448 | 2026-10-20 19:32 | Chênaie d'Orge |
| 19 | Avant-poste 19 | `wp_wild_forest_8_9` | wild | forest | 2082, 66, 2440 | 2026-10-20 19:32 | Bosquet Ancien |
| 20 | Avant-poste 20 | `wp_wild_old_growth_birch_forest_8_9` | wild | old_growth_birch_forest | 2067, 64, 2347 | 2026-10-20 19:34 | Sous-Bois Argenté |
| 21 | Avant-poste 21 | `wp_wild_old_growth_birch_forest_7_8` | wild | old_growth_birch_forest | 2016, 71, 2249 | 2026-10-20 19:35 | Lisière Chantante |
| 22 | Avant-poste 22 | `wp_wild_forest_7_7` | wild | forest | 2024, 67, 1978 | 2026-10-20 19:36 | Fourré de Laine |
| 23 | Avant-poste 23 | `wp_wild_birch_forest_-11_-15` | wild | birch_forest | -2717, 73, -3809 | 2026-10-20 19:40 | Charme de Fougère |
| 24 | Avant-poste 24 | `wp_wild_stony_shore_-11_-15` | wild | stony_shore | -2814, 69, -3742 | 2026-10-20 19:41 | Havre d'Étoile |
| 25 | Avant-poste 25 | `wp_wild_plains_-8_5` | wild | plains | -1904, 66, 1399 | 2026-10-20 19:42 | Mas Brumeux |
| 26 | Avant-poste 26 | `wp_wild_beach_-8_5` | wild | beach | -2048, 64, 1413 | 2026-10-20 19:43 | Lagune de Craie |
| 27 | Avant-poste 27 | `wp_wild_plains_4_-18` | wild | plains | 1245, 104, -4564 | 2026-10-21 15:53 | Halte de Murmure |
| 28 | Avant-poste 28 | `wp_wild_forest_5_-18` | wild | forest | 1336, 81, -4534 | 2026-10-21 15:53 | Houx de Houblon |
| 29 | Avant-poste 29 | `wp_wild_birch_forest_5_-18` | wild | birch_forest | 1369, 67, -4449 | 2026-10-21 15:54 | Sorbier de Châtaigne |
| 30 | Avant-poste 30 | `wp_wild_old_growth_birch_forest_5_-17` | wild | old_growth_birch_forest | 1337, 72, -4333 | 2026-10-21 15:55 | Viorne Cachée |
| 31 | Avant-poste 31 | `wp_wild_plains_9_-13` | wild | plains | 2420, 71, -3296 | 2026-10-21 15:56 | Carrefour de Castor |
| 32 | Avant-poste 32 | `wp_wild_plains_9_-14` | wild | plains | 2368, 66, -3391 | 2026-10-21 15:56 | Trèfle Caché |
| 33 | Avant-poste 33 | `wp_wild_flower_forest_-12_9` | wild | flower_forest | -2957, 70, 2339 | 2026-10-21 16:01 | Futaie de Brise |
| 34 | Avant-poste 34 | `wp_wild_forest_1_-16` | wild | forest | 394, 97, -3958 | 2026-10-21 16:04 | Chênaie de Cerise |
| 35 | Avant-poste 35 | `wp_wild_plains_1_-16` | wild | plains | 289, 76, -3975 | 2026-10-21 16:04 | Draille d'Ombre |
| 36 | Avant-poste 36 | `wp_wild_old_growth_pine_taiga_-7_-10` | wild | old_growth_pine_taiga | -1742, 65, -2488 | 2026-10-21 16:12 | Sapinière de Pinson |
| 37 | Avant-poste 37 | `wp_wild_plains_-7_-15` | wild | plains | -1584, 75, -3669 | 2026-10-21 16:14 | Vallon Brumeux |
| 38 | Avant-poste 38 | `wp_wild_plains_-6_-15` | wild | plains | -1476, 66, -3669 | 2026-10-21 16:15 | Vallonnet de Merle |
| 39 | Avant-poste 39 | `wp_wild_forest_-6_-15` | wild | forest | -1397, 63, -3753 | 2026-10-21 16:16 | Bosquet d'Alouette |
| 40 | Avant-poste 40 | `wp_wild_plains_-11_2` | wild | plains | -2772, 65, 712 | 2026-10-21 16:23 | Plaine de Givre |
| 41 | Avant-poste 41 | `wp_wild_forest_-10_4` | wild | forest | -2560, 66, 1228 | 2026-10-21 16:28 | Sous-Bois de Frimas |
| 42 | Avant-poste 42 | `wp_wild_old_growth_birch_forest_-12_4` | wild | old_growth_birch_forest | -2837, 64, 1145 | 2026-10-21 16:34 | Lisière d'Avoine |
| 43 | Avant-poste 43 | `wp_world_hub_plains_3_-4` | world_hub | plains | 810, 63, -856 | 2026-10-22 07:58 | Pâtis des Chênes |
| 44 | Avant-poste 44 | `wp_wild_taiga_13_2` | wild | taiga | 3413, 68, 622 | 2026-10-22 10:08 | Futaie de Printemps |
| 45 | Avant-poste 45 | `wp_wild_forest_13_3` | wild | forest | 3396, 67, 825 | 2026-10-22 10:08 | Fourré de Lin |
| 46 | Avant-poste 46 | `wp_wild_plains_13_3` | wild | plains | 3397, 72, 1006 | 2026-10-22 10:10 | Ferme des Corbeaux |
| 47 | Avant-poste 47 | `wp_wild_plains_13_4` | wild | plains | 3470, 71, 1078 | 2026-10-22 10:10 | Bergerie de Miel |
| 48 | Avant-poste 48 | `wp_wild_river_12_4` | wild | river | 3277, 64, 1088 | 2026-10-22 10:10 | Gué Argenté |
| 49 | Avant-poste 49 | `wp_wild_bamboo_jungle_3_-12` | wild | bamboo_jungle | 880, 77, -2841 | 2026-10-22 13:39 | Cascade d'Hiver |
| 50 | Avant-poste 50 | `wp_wild_old_growth_birch_forest_3_-12` | wild | old_growth_birch_forest | 801, 64, -2998 | 2026-10-22 13:41 | Charme de Lune |
| 51 | Avant-poste 51 | `wp_wild_meadow_-3_-7` | wild | meadow | -755, 110, -1737 | 2026-10-22 13:41 | Mas de Grive |
| 52 | Avant-poste 52 | `wp_wild_meadow_-3_-8` | wild | meadow | -724, 110, -1851 | 2026-10-22 13:42 | Halte de Silence |
| 53 | Avant-poste 53 | `wp_wild_plains_-3_-7` | wild | plains | -526, 69, -1623 | 2026-10-22 13:45 | Carrefour de Cendre |
| 54 | Avant-poste 54 | `wp_wild_forest_-13_3` | wild | forest | -3175, 76, 953 | 2026-10-22 13:46 | Houx de Martinet |
| 55 | Avant-poste 55 | `wp_wild_river_-13_3` | wild | river | -3087, 66, 992 | 2026-10-22 13:47 | Berge d'Orge |
