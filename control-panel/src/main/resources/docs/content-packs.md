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

## Faire générer un pack par une IA

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
  `questIds` d'une story doivent désigner des éléments **fournis par le pack** ou **déjà présents
  sur le serveur**. Sinon, c'est une dépendance manquante — elle doit figurer dans `dependencies`.
- **Types d'objectifs et de récompenses** : seuls ceux listés dans le contrat existent. Une IA peut
  inventer un type plausible mais inexistant (« utiliser un objet », par exemple) ; le schéma le
  refuse, et le serveur aussi.
- **Équilibrage** : rien ne plafonne une récompense côté moteur. C'est une décision éditoriale, à
  relire comme telle.

## Limites actuelles, à connaître

- **Import** : il n'existe pas encore d'import de pack dans le Control Panel. Un pack généré se relit
  et se recopie aujourd'hui élément par élément dans les éditeurs de quêtes, stories et dialogues.
- **Dialogues** : le schéma ne contraint que le squelette d'un dialogue (identifiant, nœud de départ,
  nœuds, choix). Le vocabulaire complet de ses **actions** et **conditions** n'est pas encore décrit
  par le contrat : s'en tenir à ce que montre l'exemple complet, ou demander la liste à jour.
- **Familles** : seules quêtes, stories, dialogues et PNJ logiques sont couverts. Les objets
  personnalisés, recettes et waypoints ne font pas partie du format pour l'instant.

## Après un essai

Les exemples du contrat utilisent le préfixe d'identifiant `tc110_`, exprès : il les rend faciles à
retrouver et à supprimer, et impossibles à confondre avec du contenu de production. Garder cette
habitude pour tout contenu d'essai.
