# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-11
* Heure : 12:37 → (voir « Fin de la tâche »)
* Sujet : finalisation de #156 (TC-275/TC-276 validés), **#236** filtre des instances du Hub, et
  **audit #153** — pourquoi un waypoint peut se retrouver dans les arbres
* Statut : DONE — avec une conclusion explicitement négative sur la clôture de #156
* Branche Git : `feature/236-hub-instances-filter` (créée depuis la ligne réellement déployée,
  `feature/156-travel-network-audit` @ `92a9686`)
* Commit actuel si disponible : `1f1eb5e`
* Début de la tâche : 2026-10-11 12:37:52
* Fin de la tâche : 2026-10-11 14:02:30
* Durée totale : 01:24:38

---

## Demande

Trois lots liés au test réel de #156 :

1. consigner la validation manuelle de TC-275/TC-276, commenter #156, et **proposer sa clôture si et
   seulement si** les critères sont objectivement satisfaits — « ne ferme pas #156 s'il reste encore
   un critère fonctionnel non couvert » ;
2. **#236** — filtrer la liste des instances du Hub (Toutes / Avec borne / Sans borne), sans que les
   métriques globales soient recalculées, sans JS, mobile-friendly ;
3. **audit #153** — un waypoint physiquement dans les arbres a été croisé en jeu ; comprendre
   pourquoi, proposer une règle plus robuste, et ne corriger que si le comportement actuel est
   **clairement contraire** à un critère déjà décidé dans #153.

---

## Réponses courtes

| Question | Réponse |
|---|---|
| **#156 peut-il être fermé ?** | **Non.** 8 critères sur 9 sont satisfaits ; le parcours « mort dans le Wild → Hub → borne → waypoint découvert » n'a jamais été éprouvé, et c'est l'objectif gameplay que le ticket désigne comme principal. TC-277 créé pour cela. |
| **#236 est-il livré ?** | **Oui**, panel seul, 14 tests. |
| **Pourquoi le waypoint pouvait-il être dans les arbres ?** | Deux causes : **(1)** la règle n'est pas rétroactive et **36 waypoints sur 143** lui sont antérieurs — explication dominante ; **(2)** un vrai défaut, le « sommet de tronc » que #153 décide de rejeter ne l'était pas. |
| **#153 nécessite-t-il un correctif ?** | **Oui, et il est fait** pour le sommet de tronc. Le reste (espace devant le bouton, pente) n'est **pas** une correction évidente → **#237**. |
| **Quels tests manuels restent ?** | **TC-277** (dernier critère de #156), plus TC-273 (#235), TC-274 (#234), TC-257 (#123), TC-271/TC-272 (#47). |

---

## Lot 1 — #156 : validation consignée, et pourquoi je ne propose pas la clôture

### Ce qui a été validé

TC-275 et TC-276 passent, déroulés par le propriétaire sur DEV. Les résultats réels sont consignés
dans `docs/MANUAL_TEST_PLAN.md` (les deux fiches portent désormais « ✅ VALIDÉ 2026-10-11 ») et dans
un commentaire sur #156.

TC-276 a produit un constat que le propriétaire a explicitement jugé correct : **une fois l'instance
appariée, l'action « Apparier cette instance » n'est même plus proposée.** C'est le comportement
voulu, et c'est une conséquence directe de la conception — le formulaire n'est rendu que dans la
branche « instance sans borne » de la fiche. Le refus côté serveur (« Cette instance a déjà une borne
appariée ») reste la seconde barrière, pour un formulaire resoumis depuis une page périmée.

### L'état objectif des 9 critères d'acceptation

| Critère | État |
|---|---|
| Chaque instance Hub a une borne **ou** un état d'échec explicite et réessayable | ✅ |
| Plusieurs instances/biomes testés, waypoints préexistants et redémarrage inclus | ✅ |
| Bornes réellement présentes et accessibles, distances cohérentes | ✅ |
| **Parcours mort dans le Wild → Hub → borne → waypoint découvert, praticable en conditions réelles** | ❌ |
| Zones sans couverture raisonnable identifiées et quantifiées | ✅ |
| Aucun doublon, aucune régression des découvertes / waypoints Wild | ✅ |
| Politique de génération Hub/Wild documentée séparément | ✅ |
| Réglage configurable proposé avec mesures, sans activation arbitraire | ✅ |
| Tests, validation Minecraft, rapport et documentation | ✅ |

### Pourquoi ce critère compte

Ce n'est pas un détail de procédure : c'est l'objectif que le ticket désigne lui-même comme principal
— « après une mort dans le Wild, le joueur doit pouvoir retrouver rapidement une zone déjà explorée
sans devoir recommencer une longue traversée dangereuse depuis zéro ».

J'ai vérifié que le parcours est **mécaniquement** en place : `TravelBeaconService#openWorlds` liste
les waypoints `discoveredBy(joueur)` groupés par monde, et le Wild compte **44 découvertes**. Donc une
borne du Hub propose bien de voyager vers un waypoint du Wild déjà découvert. Mais « praticable en
conditions réelles » ne se déduit pas d'une lecture de code — il faut mourir dans le Wild une fois.

**TC-277** est créé pour cela : 10 minutes, et il demande de **mesurer le temps de reprise**, puisque
c'est ce chiffre qui dira si l'équilibrage suffit. À noter pour ce test : avec **0 Waystone
découverte**, la reprise dépend aujourd'hui entièrement des waypoints du Wild découverts et des bornes
du Hub.

---

## Lot 2 — #236 : le filtre des instances du Hub

### Ce qui est livré

Sur la section « Instances connues du Hub » :

* **trois états** — Toutes / Avec borne / Sans borne — chacun avec son compteur ;
* **recherche libre** sur le biome, la clé d'instance, le waypoint, la borne et les **coordonnées**,
  y compris sous la forme affichée par l'écran (« 161,27 » ou « 161 27 ») : on doit pouvoir recopier
  ce qu'on lit ;
* un bouton « Effacer » quand une recherche est active, et l'état conservé quand on change de filtre ;
* un compteur **« affichées/total »** — un « (6) » seul se lirait « le Hub n'a que 6 instances ».

### Les trois contraintes du ticket, et comment elles sont tenues

**« Les métriques globales restent calculées sur le réseau complet »** — c'est le point important, et
il est garanti structurellement : le filtre porte sur une **liste**, pas sur les données d'où
viennent les statistiques. Un test compare le rendu filtré et le rendu non filtré et vérifie que les
sections « Réseau Hub — diagnostic », « Couverture du Hub », « fiche par borne » et « Réseau du Wild »
sont **identiques**.

C'est aussi pourquoi les paramètres s'appellent `hi`/`hq` et non `q`/`world` : ces derniers masquent
tout le diagnostic, précisément parce qu'ils filtrent les tableaux dont les métriques sont tirées.
Le nouveau filtre ne masque rien — et un test vérifie qu'il ne déclenche **pas** le message « Filtre
actif ». L'état vide rappelle d'ailleurs explicitement que « les statistiques de couverture ci-dessus
portent sur le réseau complet, pas sur cette sélection ».

**« Pas de JS inline si CSP »** — trois liens GET et un `<input type="search">` dans un formulaire
GET. Un test vérifie l'absence de `onclick` et de `<script>`. Un lien est de toute façon la bonne
primitive ici : partageable, rejouable, utilisable au clavier comme au doigt.

**« Mobile-friendly »** — une ancre `#instances-hub` ramène à la section, pour ne pas renvoyer
l'écran en haut de page à chaque clic : sur téléphone, la liste est loin sous les statistiques.

### Trouvé en chemin

La classe CSS `npc-searchbar` était utilisée **sans aucune règle** : chaque champ s'étalait donc sur
toute la largeur, y compris sur grand écran. Elle passe en flex qui revient à la ligne — correcte au
bureau comme sur téléphone. Les deux seuls usages de la classe sont sur `/travel`, donc le risque de
régression ailleurs est nul (vérifié).

---

## Lot 3 — Audit #153 : pourquoi un waypoint dans les arbres ?

### Les questions posées, et les réponses mesurées

| Question | Réponse |
|---|---|
| Quelles règles déterminent la surface ? | `RandomSafeLocationFinder#findAccessibleColumn`, partagée par **waypoints et bornes**. Surface = `getHighestBlockYAt`, qui doit être un bloc **solide non dangereux**, non-arbre, avec au moins un des 4 voisins solide non-arbre à ±1 bloc. |
| Les feuilles sont-elles une surface valide ? | **Non**, rejetées depuis le 2026-10-04. |
| Le haut d'une canopée peut-il être sélectionné ? | **Non** — le bloc le plus haut est une feuille, donc refusé. |
| Une colonne **sous** une canopée ? | **Non** non plus, pour la même raison : son bloc le plus haut est la feuille. |
| L'espace d'accès du joueur est-il vérifié ? | **Partiellement.** Deux blocs libres au-dessus de la surface, et un voisin praticable. Mais **pas** la colonne où le joueur se tient pour cliquer le bouton. |
| Le bouton peut-il être difficilement accessible ? | **Oui**, c'est la limite ci-dessus → #237. |
| Eau, lave ? | **Refusées** (sol non solide ; lave aussi classée dangereuse). |
| Troncs ? | **C'était le défaut.** Voir ci-dessous. |
| Pente ? | Seul garde-fou : un voisin à ±1 bloc. Pas de limite de pente → #237. |

### Cause 1 (dominante) — la règle n'est pas rétroactive

La règle est entrée dans le dépôt le **2026-10-04 00:00** (commit `aa554c9`). Rien ne re-contrôle un
waypoint déjà posé. Mesuré sur une copie **en lecture seule** de la base DEV :

| Monde | Waypoints | Posés **avant** la règle | Après |
|---|---:|---:|---:|
| `world_hub` | 23 | **15** | 8 |
| `wild` | 120 | **21** | 99 |
| bornes | 16 | 7 | 9 |

**36 waypoints sur 143 ont été placés par l'ancienne règle**, dont 15 des 23 du Hub. C'est de loin
l'explication la plus probable de ce qui a été vu en jeu.

### Cause 2 (vrai défaut, corrigé) — le sommet de tronc n'était pas rejeté

#153 décide explicitement de rejeter « feuillage/canopée, **sommet de tronc inaccessible** ». Seul le
feuillage l'était : `isLeafCanopy` ne testait que `*_LEAVES`. Une colonne dont le bloc le plus haut
est un **rondin** — tronc étêté, tronc dépassant la canopée, racines de mangrove, rondin couché —
passait le contrôle et pouvait recevoir une structure, exactement aussi inatteignable à pied que
celle au sommet des feuilles qui a motivé le ticket. Le Hub interdisant la casse et la pose, le joueur
n'a alors **aucun** moyen de l'atteindre.

C'est le seul cas où le comportement était **clairement contraire à un critère déjà décidé**, donc le
seul que j'ai corrigé. La surface refusée couvre désormais `*_LEAVES`, `*_LOG`, `*_WOOD`, `*_STEM`,
`*_HYPHAE`, `*_ROOTS` — par suffixe plutôt qu'une liste de matériaux à maintenir à chaque version de
Minecraft — pour la colonne cible **et** pour les voisins servant d'approche : un sommet de tronc
n'est pas une approche. Un arbre **adjacent** reste permis : c'est la surface qu'on refuse, pas le
voisinage.

**Effet de bord utile** : `inaccessible()` partage la règle, donc les structures **déjà** posées sur
un tronc sont désormais **détectées** par `/rpgadmin travel diagnose`. Aucune n'est déplacée d'office.

### Ce que je n'ai pas corrigé, et pourquoi

Ouvert en **#237** :

1. **L'espace devant le bouton.** #153 le décide. L'emprise contrôlée couvre le bouton lui-même et
   deux blocs de dégagement au-dessus, mais pas la colonne `(f, 0)` où un joueur se tient pour
   cliquer, ni son support. Ce n'est pas local : ajouter cette contrainte **change les conditions
   d'acceptation** du placement, donc la densité des waypoints — ce qui demande une mesure et une
   validation en jeu, pas un patch de confiance.
2. **La limite de pente.** Critère supplémentaire, pas un critère décidé non tenu.
3. **Le dégagement corps/tête est structurellement inatteignable.** La surface venant de
   `getHighestBlockYAt`, les deux blocs au-dessus sont libres **par définition** ; le contrôle ne peut
   mordre qu'à la limite de hauteur du monde. Ce n'est pas un bug — la condition est déjà garantie par
   la façon dont la surface est choisie — mais il valait mieux l'écrire que de croire le contrôle
   actif. Un test le documente.

### Diagnostic panel (point 5 de la demande) : écarté, avec sa raison

Exposer un statut d'accessibilité par waypoint dans PlugAdmin **n'est pas petit**, et surtout c'est
dangereux : `WaypointService#inaccessible()` lit les blocs de chaque waypoint, et `World#getBlockAt`
**charge le chunk** s'il ne l'est pas. Avec 143 waypoints répartis sur ~9 570 × 9 550 blocs, un simple
rafraîchissement du catalogue forcerait le chargement d'une **centaine de chunks** — exactement ce que
les contraintes du projet interdisent, et ce que la consigne « ne pré-génère aucun chunk » exclut.

Conception sûre, proposée dans #237 : ne contrôler que les waypoints dont le chunk est **déjà chargé**
(`World#isChunkLoaded`) et afficher « non vérifié » pour les autres — en le disant, plutôt qu'en
laissant croire à une accessibilité confirmée.

**En attendant, l'outil existe déjà** : `/rpgadmin travel diagnose` liste les structures
inaccessibles — et les détecte désormais aussi sur les troncs — et
`/rpgadmin travel repair waypoint <id> confirm` les déplace en conservant id, nom, découvertes et
borne appariée. C'est le chemin de rattrapage que #153 a lui-même défini, et il reste **sur action
administrateur explicite**.

---

## Fichiers créés

| Fichier | Rôle |
|---|---|
| `docs/claude-reports/2026-10-11_1237_filtre-instances-et-audit-placement.md` | ce rapport |

## Fichiers modifiés

| Fichier | Changement |
|---|---|
| `control-panel/.../panel/travel/TravelNetworkDiagnostic.java` | `InstanceFilter` (+ `ofWire` tolérant) et `instancesMatching(filtre, recherche)` — pur |
| `control-panel/.../panel/web/AgentPages.java` | barre de filtre en liens GET, recherche, ancre, compteur « affichées/total », état vide explicite |
| `control-panel/src/main/resources/assets/plugadmin.css` | `.npc-searchbar` — la classe était utilisée sans règle |
| `src/main/java/com/lodygames/rpgquest/travel/RandomSafeLocationFinder.java` | `isLeafCanopy` → `isTreeSurface` : troncs, bois, racines rejetés comme surface **et** comme approche |
| `docs/WAYPOINTS.md` | la doc citait `findAtColumn` là où le code utilise `findAccessibleColumn` — **doc fausse corrigée** ; règle d'accessibilité écrite au complet |
| `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md` | #236 et la règle #153 |
| `docs/MANUAL_TEST_PLAN.md` | TC-275/TC-276 validés avec leurs résultats réels ; **TC-277** ajouté |
| tests : `TravelNetworkDiagnosticTest`, `TravelCatalogTest`, `RandomSafeLocationFinderTest` | voir ci-dessous |

## Base de données / migrations

**Aucune.** Rien de ce lot ne touche au schéma ni aux données.

## Configuration / données

**Aucun réglage modifié.** Aucune politique de densité, aucune distance, aucune probabilité touchée.

## Tests automatiques

| Suite | Résultat |
|---|---|
| `TravelNetworkDiagnosticTest` | **21 tests** (+5) — filtrage par état, recherche par biome/identifiants/**coordonnées collées**, combinaison état+recherche, valeur de filtre inconnue, et surtout : **les statistiques du réseau sont inchangées quel que soit le filtre** |
| `TravelCatalogTest` | **22 tests** (+7) — les trois états en liens GET avec compteurs, ancre, « affichées/total », absence de script en ligne, **comparaison des métriques filtré/non filtré**, recherche, conservation et effacement, valeur inconnue |
| `RandomSafeLocationFinderTest` | **23 tests** (+6) — sommet de tronc des **six** familles, sol normal au pied d'un arbre, voisin unique qui est un tronc, eau, lave, plafond du monde, pente de 4 blocs |
| `:test --tests 'waypoint.*' 'travel.*'` | **VERT** — waypoints et bornes partagent la règle modifiée |
| `./gradlew test` (worktree propre) | voir « Résultat des suites » |
| `./gradlew build` (worktree propre) | voir « Résultat des suites » |

### Résultat des suites

* `./gradlew test` : **à compléter**
* `./gradlew build` : **à compléter**

## Tests manuels à effectuer

* **TC-277** — reprise après mort dans le Wild. C'est le **dernier critère de #156**.
* **TC-278** — vérification du filtre #236 en conditions réelles (fiche ci-dessous), 3 minutes.
* Inchangés et intacts : TC-273 (#235), TC-274 (#234), TC-257 (#123), TC-271/TC-272 (#47).

### TC-278 — filtre des instances du Hub

1. `/travel` → Rafraîchir → section « Instances connues du Hub ».
2. Cliquer **Sans borne** : la liste ne doit garder que les instances en manque, le compteur passer à
   « 6/23 », et les sections de statistiques au-dessus rester **identiques**.
3. Cliquer **Avec borne** : « 17/23 », aucune ligne « sans borne ».
4. Taper `swamp` puis Filtrer ; vérifier que changer d'état conserve la recherche, et que
   « Effacer » la retire.
5. Taper des coordonnées lues à l'écran, par exemple `161,27`.
6. Sur téléphone : vérifier que la barre de filtre et la recherche passent à la ligne proprement, et
   qu'un clic sur un état revient bien **à la section**, pas en haut de page.

## Résultat attendu

Un administrateur ouvre `/travel`, clique « Sans borne », et voit immédiatement les seules instances
sur lesquelles agir — sans que les chiffres du réseau changent sous ses yeux. Et plus aucun waypoint
ni borne ne sera désormais posé sur un sommet de tronc.

## Reset / retour à l'état initial

Rien à réinitialiser : aucune donnée de jeu modifiée, aucune structure déplacée.

## Déploiement VeryGames

### À transférer

1. **Panel** (`scripts/plugadmin/deploy.sh`) — le filtre #236 et la règle CSS.
2. **JAR du plugin** — la règle de placement #153.

Les deux, car les deux modules changent. À compléter après exécution.

### Ne PAS transférer/altérer

`data.db`, `config.yml` (**aucun réglage ne change**), `messages.yml`, `spawn.yml`, les mondes,
`plugins/Citizens/`, et les quêtes/dialogues/stories du serveur.

### Redémarrage requis

Oui pour le JAR. Le panel redémarre seul par systemd.

### Migration automatique

Aucune.

## Rollback

* **Plugin** : `scripts/rollback-verygames.sh --latest`, puis redémarrer. Rien à défaire en base.
* **Panel** : `scripts/plugadmin/rollback.sh app` puis `systemctl restart plugadmin`.
* La règle de placement ne concerne que les **futurs** placements : un rollback ne déplace rien.

## Logs / diagnostic

* `/rpgadmin travel diagnose` liste les structures inaccessibles — désormais y compris celles posées
  sur un tronc.
* `Aucun emplacement de waypoint trouvé pour … (essai N), nouvel essai dans N s.` si un candidat est
  refusé.

## Documentation mise à jour

`docs/WAYPOINTS.md` (règle d'accessibilité complète + **correction d'une doc fausse**),
`docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md`,
`docs/deployment/SERVER_CHANGELOG.md`, `docs/claude-reports/README.md`.

## Limitations / travail restant

* **#156 reste ouverte** — TC-277 est le seul critère manquant.
* **#153 reste ouverte** — son test manuel « un joueur non OP en survie peut approcher et cliquer
  chaque structure Hub » n'a pas été déroulé, et c'est précisément ce que l'observation du jour remet
  en question pour les 36 structures antérieures à la règle.
* **#237 ouvert** — espace devant le bouton, pente, et diagnostic panel borné aux chunks chargés.
* Les 36 waypoints antérieurs à la règle **restent en place** : aucun n'a été déplacé.
* **6 instances du Hub** restent sans borne (7 avant TC-276).

## Prochaine étape suggérée

1. **TC-277** (10 min) — puis #156 peut être fermée.
2. TC-278 (3 min) pour le filtre.
3. Décider du sort des 36 waypoints antérieurs à la règle : `/rpgadmin travel diagnose` dira combien
   sont réellement inaccessibles aujourd'hui, et `repair` les traite un par un.

## Commits

| Commit | Objet |
|---|---|
| `d9de6b9` | `feat(panel)` — filtrer les instances du Hub par état et par recherche (#236) |
| `4ea8f52` | `fix(travel)` — refuser un sommet de tronc comme surface de structure (#153) |
| `1f1eb5e` | `docs` — TC-275/TC-276 validés, TC-277, filtre #236 et règle de placement #153 |

Aucun merge. Les 7 fichiers de contenu non suivis du propriétaire et `crystal_hunt.yml` ont été
vérifiés avant **chaque** commit et n'ont jamais été ajoutés au suivi — chaque commit composé par
énumération explicite, jamais par `git add -A`.
