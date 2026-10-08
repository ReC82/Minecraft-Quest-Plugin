---
title: Content packs — exporter, et faire générer du contenu par une IA
category: Contenu
tags: [content-pack, export, import, schema, json-schema, ia, ai, gabarit, template, contrat]
order: 1
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

## Créer une quête avec une IA, depuis le panel

Page **Créer avec une IA** (`/ai/studio`). C'est la voie la plus courte : vous décrivez la quête en
français, le panel joint automatiquement le contrat, le schéma, les types réellement supportés et
les références réellement existantes, puis affiche la proposition déjà validée.

1. **Décrire** — un seul champ est nécessaire : ce que la quête doit raconter. Les autres (titre,
   identifiant, PNJ donneur, difficulté, durée, nombre d'étapes, récompense, contraintes) affinent
   la demande. Le **titre** se met en forme avec la palette habituelle : aucune balise à écrire.
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
- **Familles importables** : quêtes, stories et dialogues. Les **PNJ** font partie du format de pack
  mais ne sont pas éditables depuis le panel : ils sont listés comme « ignorés », avec leur motif,
  jamais écrits ni perdus en silence.
- **Dialogues** : le schéma ne contraint que le squelette d'un dialogue (identifiant, nœud de départ,
  nœuds, choix). Le vocabulaire complet de ses **actions** et **conditions** n'est pas encore décrit
  par le contrat : s'en tenir à ce que montre l'exemple complet, ou demander la liste à jour.
- **Familles** : seules quêtes, stories, dialogues et PNJ logiques sont couverts. Les objets
  personnalisés, recettes et waypoints ne font pas partie du format pour l'instant.

## Après un essai

Les exemples du contrat utilisent le préfixe d'identifiant `tc110_`, exprès : il les rend faciles à
retrouver et à supprimer, et impossibles à confondre avec du contenu de production. Garder cette
habitude pour tout contenu d'essai.
