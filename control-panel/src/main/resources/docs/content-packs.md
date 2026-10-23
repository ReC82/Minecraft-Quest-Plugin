---
title: Content packs — exporter, et faire générer du contenu par une IA
category: Contenu
tags: [content-pack, export, import, schema, json-schema, ia, ai, gabarit, template, contrat]
order: 1
related: [contenu-publier-sur-dev, quetes, dialogues]
---

# Content packs

Un **content pack** est un fichier YAML qui contient du contenu déclaratif LodyQuests — quêtes,
stories, dialogues, PNJ logiques — dans un format public et versionné :

```yaml
format: lodyquests-content-pack
schemaVersion: 1
content:
  quests: []
  stories: []
  dialogues: []
  npcs: []
```

Un pack ne contient **jamais** de donnée joueur, de progression, ni de secret.

## Exporter

Page **Export de contenu** (`/content/export`). Trois granularités : tout le contenu, une famille
entière, ou une sélection d'identifiants d'une même famille. Le pack est produit par le serveur et
téléchargé depuis la liste « Exports récents ».

Un export qui dépasse environ 56 Kio est refusé proprement (limite du transport vers l'agent) :
exporter alors famille par famille, ou élément par élément.

Le contenu marqué `secret: true` **est** exporté, avec son flag — une sauvegarde ne doit pas perdre
de contenu.

## Importer

Page **Import contenu** (`/content/import`). Coller le pack, puis suivre les trois étapes affichées :

1. **Analyser** — le pack est lu, validé par les validateurs réels, et comparé à la source. **Rien
   n'est écrit à cette étape.** Chaque élément reçoit un état : *nouveau*, *modifié*, *inchangé*,
   *conflit*, *ignoré* ou *inexploitable*.
2. **Trancher les collisions** — un identifiant qui existe déjà et diffère devient un **conflit** qui
   bloque l'import. Il faut choisir explicitement « Remplacer l'existant » ou « Garder l'existant » :
   rien n'est jamais écrasé sans décision. Un diff par élément est disponible, replié.
3. **Confirmer** — seul ce bouton écrit, et il n'apparaît que si l'analyse est confirmable (aucune
   erreur, aucun conflit en attente, au moins un élément à écrire).

Ce qui est enregistré est **ré-émis** par le panel dans sa forme canonique, pas copié depuis le
fichier : ce qui atterrit dans la source est donc toujours relisible par le serveur. Si un fichier a
été modifié par quelqu'un d'autre entre l'analyse et la confirmation, le remplacement est **refusé**
plutôt qu'écrasé.

Un pack dont la `schemaVersion` n'est pas celle supportée est refusé avec la version attendue — jamais
interprété approximativement.

## Créer du contenu avec une IA, depuis le panel

Page **Créer avec une IA** (`/ai/studio`). C'est la voie la plus courte : vous décrivez ce que vous
voulez en français, le panel joint automatiquement le contrat, le schéma, les types réellement
supportés et les références réellement existantes, puis affiche la proposition déjà validée.

Commencez par choisir **ce que vous voulez créer** : une quête, un dialogue ou une story. Un seul
élément par demande — c'est voulu. Demander une quête, son dialogue et une story en un appel donne
un résultat dont une partie est bonne et l'autre refusée, sans moyen simple de ne corriger que la
mauvaise. Pour une quête **et** son dialogue, faites deux demandes : la seconde pourra citer la
première, qui existera déjà.

### Pour un dialogue

Décrivez aussi **les cas de figure** : « s'il a déjà terminé la quête… », « s'il n'a pas assez
de… ». Ce sont eux qui deviennent des conditions, et c'est ce qu'on oublie le plus souvent de
demander. Attention à un piège du moteur : **un choix dont une condition est fausse n'est pas
affiché du tout** au joueur. Pour une branche visible mais refusée, demandez explicitement qu'elle
soit sans condition et que le refus soit expliqué dans la réplique suivante.

Indiquez le **PNJ porteur** : l'identifiant du dialogue est celui du PNJ, et l'IA ne peut pas le
deviner. Si la conversation doit proposer ou valider une quête, cette quête doit **déjà exister** —
un dialogue n'en crée pas.

### Pour une story

Une story est un **ordre de quêtes existantes**. Elle ne crée ni objectif ni récompense. Listez les
quêtes à enchaîner (une par ligne ou séparées par des virgules) ; les quêtes réellement disponibles
sont rappelées sous le champ. Si une quête manque, elle sera signalée comme **dépendance
manquante** plutôt qu'inventée.

### Le parcours, dans les trois cas

1. **Décrire** — un seul champ est nécessaire : ce que l'élément doit raconter. Les autres (titre,
   identifiant, PNJ donneur, difficulté, durée, nombre d'étapes, récompense, contraintes) affinent
   la demande. Les textes vus par le joueur — titre de quête, titre de story, nom du locuteur — se
   mettent en forme avec la palette habituelle : aucune balise à écrire.
2. **Demander une proposition** — l'appel part du **serveur du panel**, jamais de votre navigateur.
   Il peut prendre une minute.
3. **Relire** — la proposition passe par les **mêmes validateurs** que l'éditeur guidé. Chaque
   problème est affiché. Si la proposition est refusée, un bouton **« Demander une correction »**
   renvoie à l'IA sa propre sortie *et* les erreurs exactes.
4. **Enregistrer** — le bouton ouvre la page d'import, où vous confirmez. **L'atelier n'écrit
   jamais** : il n'a aucun chemin vers le disque.

Un échec, un délai dépassé ou une réponse illisible ne modifient rien.

### Avant de pouvoir l'utiliser

Page **Fournisseurs d'IA** (`/ai/providers`), réservée aux administrateurs : activer un fournisseur
(Anthropic, OpenAI ou compatible, Google Gemini), y coller une clé API, puis **tester la
connexion** — « enregistré » ne prouve pas qu'une clé fonctionne.

La clé reste sur le serveur du panel : elle n'est jamais renvoyée au navigateur, jamais journalisée,
jamais dans Git. L'écran n'affiche que sa longueur et une empreinte, de quoi reconnaître quelle clé
est en place sans en révéler un caractère. Un champ clé laissé vide signifie « ne pas y toucher » ;
effacer est un bouton distinct.

## Raconter une histoire et la faire écrire par une IA (modèle d'histoire)

Sur **Import contenu**, la carte **« Créer une histoire avec une IA externe »** propose le bouton
**Télécharger un modèle d'histoire** : une archive `.zip` (un seul téléchargement, aussi sur
téléphone) qui contient tout ce qu'il faut donner à ChatGPT, Claude ou une autre IA. Chaque fichier
est aussi téléchargeable seul.

| Fichier | Pour qui | Importable ? |
|---|---|---|
| `modele-histoire-lily.md` — fiche narrative remplie (« Les souvenirs de Lily ») | l'auteur et l'IA | **non** |
| `modele-histoire-vierge.md` — fiche narrative à remplir | l'auteur | **non** |
| `exemple-pack-lily.json` — 2 personnages, 1 story, 5 quêtes, 2 dialogues | l'IA (modèle de forme) | **oui** |
| `modele-pack-vierge.json` — squelette valide « À REMPLIR » | l'IA | oui (inutile tel quel) |
| `prompt-ia.txt` — consigne prête à coller (aussi affichée sur la page) | l'IA | non |
| `guide-ia-lodyquests.md` — guide pédagogique + contrat **généré** du moteur | l'IA | non |

**La différence est essentielle** : une fiche `.md` est du texte libre pour l'auteur ; **seul un
fichier `.json`** se dépose dans l'import. Un texte libre n'est jamais importable.

Le parcours :

1. **Télécharger** le modèle.
2. **Remplir** la fiche vierge avec son histoire (ou garder la fiche Lily pour un essai).
3. Dans l'IA : joindre la fiche, le guide et l'exemple Lily, puis coller le **prompt**. L'IA rend
   un seul fichier JSON.
4. **Déposer** le `.json` dans « 1. Le pack à importer » → **Analyser**. L'**aperçu de l'histoire**
   montre la story dans l'ordre, chaque quête (donneur, déblocage, objectifs avec quantités,
   récompenses), les personnages et les dialogues — **avant** toute écriture.
5. **Enregistrer dans la source** : une seule confirmation crée tout, ou rien.
6. **Publier sur DEV** depuis **Changements en attente**, puis lier chaque personnage à un PNJ
   Citizens depuis la page **PNJ**.

Le bouton **Analyser l'exemple Lily** lance directement l'analyse du pack Lily (sans rien écrire),
pour voir le résultat attendu sans manipuler de fichier.

**Personnages** : un personnage du pack **inconnu du serveur** est créé à la confirmation comme PNJ
logique, **sans placement** ni liaison Citizens. Un personnage **déjà connu** (par exemple `lily`
et `julius` sur DEV, déjà liés à Citizens) est **réutilisé tel quel** : l'import ne le modifie
pas. Son dialogue `rpgquest:<id>` s'y rattache par convention dès la publication — ce qui
**remplace ce que ce PNJ faisait jusque-là au clic**. Le guide interdit à l'IA d'inventer des
coordonnées ; un objectif « atteindre une zone » n'est écrit qu'avec de vraies coordonnées.

**La tarte de Lily** : RPGQuest n'a pas de recette personnalisée. Dans l'exemple, Lily cuisine dans
le récit ; le joueur **remet** les ingrédients puis du charbon, et **reparle** à Lily (objectif
« parler à »). Pour que le joueur fabrique lui-même la tarte, utiliser `CRAFT_ITEM` `PUMPKIN_PIE`
(recette vanilla).

## Faire générer un pack par une IA, à la main

La même page propose trois documents à télécharger. Ils décrivent le format de façon exploitable
**sans accès au code ni au serveur**, et ils sont **générés** à partir des descripteurs réels du
moteur : ils ne peuvent décrire que des champs réellement supportés, et suivent automatiquement
l'ajout d'un nouveau type d'objectif ou de récompense.

| Document | Quand l'utiliser |
|---|---|
| **Contrat rédigé** (Markdown) | à coller tel quel dans un prompt ChatGPT / Claude / Gemini |
| **Schéma officiel** (JSON Schema) | pour valider automatiquement un pack produit par un outil |
| **Gabarit** (YAML commenté) | pour rédiger un pack à la main, avec la liste des types en commentaire |

Marche à suivre typique :

1. télécharger le **contrat rédigé** ;
2. le coller dans la conversation avec l'IA, puis décrire ce que l'on veut (par exemple : « une
   mini-campagne de 8 quêtes autour d'un village minier abandonné, avec 2 PNJ, plusieurs dialogues,
   une progression cohérente et des récompenses modestes ») ;
3. demander **uniquement** le fichier YAML, sans commentaire autour ;
4. relire le résultat — en particulier les identifiants et les références.

## À vérifier dans un pack reçu

- **Identifiants de quête** : forme `namespace:clé`, en minuscules, chiffres et `_`
  (ex. `rpgquest:mines_oubliees`). Jamais un chemin de fichier, jamais un titre.
- **Références** : le `giver` d'une quête, le `npc` d'un objectif, le `dialogue` d'un PNJ et les
  `quests` d'une story doivent désigner des éléments **fournis par le pack** ou **déjà présents
  sur le serveur**. Sinon, c'est une dépendance manquante — elle doit figurer dans `dependencies`.
- **Types d'objectifs et de récompenses** : seuls ceux listés dans le contrat existent. Une IA peut
  inventer un type plausible mais inexistant (« utiliser un objet », par exemple) ; le schéma le
  refuse, et le serveur aussi.
- **Équilibrage** : rien ne plafonne une récompense côté moteur. C'est une décision éditoriale, à
  relire comme telle.

## Limites actuelles, à connaître

- **Import** : l'import écrit dans la **source**, pas sur le serveur Minecraft. Activer le contenu
  importé reste une opération distincte (rechargement ou déploiement).
- **Familles importables** : quêtes, stories et dialogues sont écrits dans la source. Un **PNJ
  logique** inconnu du serveur est créé après l'écriture complète (sans placement) ; un PNJ déjà
  connu n'est jamais modifié par l'import.
- **Collisions** : un identifiant existant et différent bloque l'import jusqu'à décision explicite ;
  réimporter le même pack ne crée rien en double (tout « inchangé »).
- **Familles** : seules quêtes, stories, dialogues et PNJ logiques sont couverts. Les objets
  personnalisés, recettes et waypoints ne font pas partie du format pour l'instant.

## Après un essai

Les exemples du contrat utilisent le préfixe d'identifiant `tc110_`, exprès : il les rend faciles à
retrouver et à supprimer, et impossibles à confondre avec du contenu de production. Garder cette
habitude pour tout contenu d'essai.
