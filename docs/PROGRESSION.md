# Progression RPG (XP multi-compétences)

Une progression RPG indépendante de l'XP/niveau vanilla (mission étape 19).
Voir [docs/ARCHITECTURE.md](ARCHITECTURE.md) (section `progression`) pour le
détail d'implémentation.

## Pistes de progression

Six pistes indépendantes : `GLOBAL`, `COMBAT`, `MINING`, `FARMING`,
`FISHING`, `EXPLORATION`. Chaque piste a son propre niveau/XP. `GLOBAL`
agrège automatiquement les cinq autres : accorder de l'XP à une piste
spécifique en mirroir toujours une partie sur `GLOBAL`
(`progression.global-mirror-ratio`, 50% par défaut) — jamais besoin
d'accorder les deux explicitement.

## Modèle d'équilibrage (mission point 12)

### Courbe de niveaux

Le coût en XP pour passer du niveau `n` au niveau `n + 1` suit une
croissance géométrique :

```
coût(n) = round(base-xp × growth-factor ^ (n - 1))
```

Valeurs par défaut : `base-xp: 100`, `growth-factor: 1.15`, `max-level:
100`. Concrètement : niveau 1→2 coûte 100 XP, 2→3 coûte 115, 3→4 coûte 132,
et ainsi de suite — une progression douce en début de jeu qui ralentit
franchement passé le niveau 30-40 (à growth-factor 1.15, le coût double
tous les ~5 niveaux). `growth-factor` doit rester ≥ 1.0 (jamais une courbe
qui redescend) ; `max-level` borne aussi bien le niveau que l'XP totale
exploitable (`ProgressionCurve#maxTotalXp`) — au-delà, tout octroi
supplémentaire est silencieusement écrêté, jamais un débordement.

Le niveau n'est **jamais stocké séparément de l'XP totale** en base :
toujours recalculé depuis `total_xp` via la courbe. Aucun état persisté ne
peut donc diverger entre XP et niveau affiché.

### Montants par source (`progression.sources`)

| Source | XP par action | Justification |
|---|---|---|
| Combat (`combat-kill-xp`) | 15 | Une action ponctuelle et risquée (le joueur peut mourir) ; valeur plate, pas de scaling par mob pour rester simple à équilibrer manuellement. |
| Minage (`mining-block-xp`) | 5 | Répétitif et sans risque : volontairement plus faible que le combat pour que le farming passif de minage ne dépasse jamais le rythme du combat/de l'exploration. |
| Agriculture (`farming-harvest-xp`) | 4 | Similaire au minage (répétitif, sans risque), légèrement inférieur car une culture peut être automatisée plus facilement (fermes à pistons). |
| Pêche (`fishing-catch-xp`) | 10 | Plus lente qu'une action de minage (temps d'attente de la touche), donc mieux rémunérée par action pour un rythme d'XP/minute comparable. |
| Exploration (`exploration-zone-xp`) | 100 | Récompense **unique par zone** (jamais répétée, voir plus bas) : un montant élevé compense le fait qu'une carte n'a qu'un nombre fini de zones à découvrir. |
| Quête (`quest-completion-xp`) | 50 | Bonus RPG en plus de l'éventuelle récompense `experience` (vanilla) déjà configurée dans la quête elle-même — les deux systèmes restent indépendants (mission point 10). |

Tous les montants sont des entiers plats configurables dans `config.yml`,
volontairement **non scalés par matériau/mob/culture** : garder un seul
nombre par catégorie plutôt qu'une table de valeurs par bloc/entité rend
l'équilibrage lisible et modifiable par un administrateur sans recompiler,
au prix d'un peu de granularité (un diamant et une pierre valent la même
XP de minage). Documenté ici comme une simplification assumée, pas un
oubli.

### Pourquoi ces montants relatifs entre eux

Le ratio approximatif est *exploration (découverte, une fois) ≫ combat >
pêche > minage ≈ agriculture*, pensé pour qu'aucune piste ne devienne
strictement dominante :

-   Minage/agriculture sont accessibles dès le début, répétables à
    volonté, donc plafonnés bas par action — mais leur répétabilité illimitée
    compense sur la durée.
-   Le combat comporte un risque réel (mort, perte d'objets) : mieux
    rémunéré par action pour compenser.
-   L'exploration est un gain ponctuel élevé mais strictement fini (autant
    de gains que de zones nommées existantes) : ne peut jamais devenir une
    source de farm infinie.

## Anti-farm (mission point 7)

-   **Blocs posés par un joueur** — jamais de minage. Position suivie en
    mémoire (persistée pour survivre à un redémarrage), effacée dès que le
    bloc est recassé (qu'il ait ou non accordé d'XP).
-   **Mobs de spawner** (`CreatureSpawnEvent.SpawnReason.SPAWNER`) — jamais
    de combat. Un spawner ne peut jamais devenir une ferme d'XP RPG passive.
-   **Descendants de division** (`mob.SpecialMobDefinition` avec
    `SPLIT_ON_HIT`, étape 18) — jamais de combat : sans cette exclusion, une
    seule frappe créerait une chaîne d'XP en plus d'une chaîne de mobs.
-   **Cultures non mûres** — jamais d'agriculture (`Ageable#getAge() <
    getMaximumAge()`) : empêche planter/re-casser en boucle.
-   **Répétition excessive** — `progression.max-grants-per-minute` (60 par
    défaut) borne le nombre d'octrois par (joueur, compétence) et par
    minute, toutes sources confondues sur cette compétence ; au-delà, les
    octrois supplémentaires sont silencieusement ignorés jusqu'à la fenêtre
    suivante. Protège contre un macro/client modifié qui déclencherait des
    actions plus vite qu'un joueur humain.
-   **Déduplication d'événement** (mission point 5) — chaque octroi porte
    un identifiant d'événement ; le même (joueur, compétence, identifiant)
    ne peut jamais récompenser deux fois. Pour l'exploration et les
    quêtes, cet identifiant est stable (id de zone / id de quête) : c'est
    ce mécanisme, pas un état séparé, qui garantit qu'une récompense « une
    fois pour toutes » ne l'est vraiment qu'une fois — y compris après un
    redémarrage.

## Commandes (`rpgquest.progression`, ouvert à tout joueur)

-   `/profile` — résumé compact (une ligne par piste : nom + niveau).
-   `/skills` — détail par piste (niveau + XP dans le niveau courant / XP
    requise pour le suivant).
-   `/skills admin grant|set <joueur> <compétence> <montant>`
    (`rpgquest.admin`) — outil de test : `grant` passe par le pipeline
    normal (dédup/mirroir GLOBAL/affichage), `set` fixe directement l'XP
    totale sans dédup ni mirroir (utile pour positionner un joueur à un
    niveau précis avant un test).

Sans commande (issue #183, retour du 20/10) : dans le **journal de quêtes**, la bourse affiche le
niveau RPG global et sa jauge ; un clic ouvre « Ma progression RPG », une jauge par piste (niveau,
XP courante / requise, pourcentage, niveau maximal). Lecture seule, mêmes calculs que `/skills`
(`ui.ProgressionGauge` sur `ProgressionService#curve()`).

## Affichage (mission point 9)

`progression.display-mode` : `action_bar` (défaut), `boss_bar` ou `off`.
En mode `boss_bar`, la barre affiche la progression dans le niveau courant
et disparaît automatiquement 3 secondes après le dernier gain.

## XP vanilla — suppression de la progression de niveaux Minecraft (issue #183)

Décision propriétaire du 04/10/2026 : la seule progression du joueur est
l'XP RPG. `progression.vanilla-xp-progression: false` est le **défaut**
(clé ajoutée au `config.yml` du serveur par la complétion au démarrage).
L'ancienne clé `progression.keep-vanilla-xp` (défaut `true`) n'est plus lue :
elle est retirée du fichier au démarrage, avec une ligne de journal. Écrire
`vanilla-xp-progression: true` rétablit l'ancien comportement ; ce choix
n'est jamais rebasculé.

Avec `false`, `progression.listener.VanillaXpBlockListener` (priorité
`HIGHEST`, aucune tâche répétitive, aucun scan d'entités) :

| Source vanilla | Effet |
|---|---|
| Mort d'un mob **ou d'un joueur** (`EntityDeathEvent`, y compris profil de mob avec `xp-reward`) | aucune orbe |
| Casse de bloc (minerais, sculk…) | aucune orbe |
| Retrait d'un four / haut fourneau / fumoir (`FurnaceExtractEvent`) | aucune orbe |
| Pêche | aucune orbe |
| Reproduction d'animaux | aucune orbe |
| Fiole d'expérience lancée | aucune orbe |
| Toute autre orbe ramassée (commerce villageois, meule, autre plugin…) — `PlayerExpChangeEvent` | gain ramené à 0 |
| Commandes admin `/xp`, `/experience` | **hors périmètre, volontairement** (outil explicite d'un administrateur) |

Récompense de quête `EXPERIENCE` : devient de l'**XP RPG** (piste
`GLOBAL`, `QuestExperienceRewards`), dédupliquée par jeton de complétion
(`quest_reward:<jeton>#<rang>`) — une remise rejouée ne paie jamais deux fois.
Le chat annonce `+ N XP RPG`. Elle s'ajoute à `quest-completion-xp`
(inchangé). Avec `vanilla-xp-progression: true`, elle redevient de l'XP vanilla.

Conditions `required-level` des portails et des offres marchandes (issue #532) :
elles comparent le **niveau RPG `GLOBAL`** (cache mémoire de `ProgressionService`,
aucun accès SQL), via la règle unique `RequiredLevelRule`. Avec
`vanilla-xp-progression: true`, elles relisent le niveau vanilla. Le niveau RPG
commence à 1 : `required-level: 1` (ou moins) est toujours satisfait.

Les messages de gain de l'action bar disent `+N XP RPG (Combat)`.

### Politique de migration des joueurs existants

- Les niveaux/XP vanilla déjà acquis ne sont **ni convertis en XP RPG ni
  effacés** : aucune donnée joueur n'est modifiée. Ils restent utilisables
  (enchantement, enclume) jusqu'à épuisement, et se perdent à la mort comme
  en vanilla (aucune orbe n'est alors lâchée).
- L'XP RPG existante (`player_skills`) est inchangée.
- Effacer les anciens niveaux, ou les convertir, est une **décision du
  propriétaire** non prise : rien n'est implémenté.

### Enchantements, enclume, raccommodage — comportement réellement affecté

Aucun de ces blocs n'est désactivé ni rendu gratuit (pas de système de
remplacement, issue #183) ; ce qui change est qu'aucun niveau ne se gagne :

- **Table d'enchantement** : coûte des niveaux vanilla (1 à 3 + lapis) →
  utilisable seulement avec d'anciens niveaux.
- **Enclume** (réparation, fusion, enchantement par livre) : coûte des
  niveaux → idem. Renommer coûte aussi 1 niveau en vanilla ; les outils de
  RPGQuest qui passent par une enclume (nom de balise de voyage, de
  chantier, outil de ville) fixent déjà leur coût à 0 et ne sont pas touchés.
- **Meule** : retire les enchantements, ne coûte rien ; ses orbes ne donnent
  plus rien.
- **Raccommodage (Mending)** : répare avec des orbes → inerte, plus aucune
  orbe n'apparaissant.
- Le **niveau RPG** ne sert jamais de monnaie d'enchantement. Un futur coût
  d'enchantement (autres points, métiers #182) reste à décider.

L'affichage natif de la barre d'XP n'est **pas** réutilisé pour le RPG
(elle montre les anciens niveaux vanilla figés) ; le niveau RPG est affiché
par `/hud`, `/skills` et l'action bar (#184).

## Hooks de déblocage (mission point 11)

`ProgressionService#hasLevel(UUID, SkillType, int)` est le point d'entrée
générique : retourne si un joueur a atteint un niveau donné sur une piste.
Câblé concrètement dans `claim.ClaimService#effectiveMaxClaims` (+1 claim
tous les 10 niveaux de la piste `GLOBAL`, en plus de la limite de
`config.yml`) — voir [docs/CLAIMS.md](CLAIMS.md). Portails, recettes et
quêtes n'ont pas encore de champ dédié dans leur format YAML ; un futur
ajout peut réutiliser `hasLevel` directement (même seam déjà préparée en
étape 17 pour les claims, remplie ici comme démonstration concrète).

## Persistance et redémarrage

`total_xp` par (joueur, compétence) et l'historique de déduplication
(`xp_grants`) vivent en SQLite (`player_skills`/`xp_grants`, migration V8).
L'XP est chargée en mémoire à la connexion, retirée à la déconnexion —
aucune requête disque au moment d'un calcul de niveau. Un redémarrage ne
perd jamais d'XP ni ne permet de re-déclencher une récompense « une fois »
déjà accordée (la table `xp_grants` survit au redémarrage).
