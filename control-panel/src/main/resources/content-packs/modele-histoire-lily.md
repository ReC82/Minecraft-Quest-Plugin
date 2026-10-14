# Fiche narrative — « Les souvenirs de Lily »

> **Ce fichier n'est PAS importable.** C'est une fiche d'auteur, en français courant, à donner
> à une IA externe (ChatGPT, Claude, Le Chat…) avec le guide `guide-ia-lodyquests.md`.
> L'IA en tire **un seul fichier JSON** (`.json`) — c'est **ce JSON-là**, et lui seul, qui se
> dépose sur la page **Import contenu** de PlugAdmin.
>
> Exemple du résultat attendu : `exemple-pack-lily.json` (déjà validé par PlugAdmin).

Remplacez librement le texte après chaque titre. Écrivez comme vous raconteriez l'histoire à
quelqu'un : l'IA se charge de la traduire en quêtes, dialogues et liens. Ce que vous ne savez pas
encore, écrivez-le « à définir » : l'IA doit le signaler, jamais l'inventer.

---

## 1. Titre de l'histoire

Les souvenirs de Lily

## 2. Résumé (le récit brut)

Pendant la guerre contre les monstres et les zombies, Lily et ses parents ont fui leur village.
Dans la panique, Lily a perdu leur trace. Elle est maintenant seule au village central.

Le joueur l'aide : il lui apporte de quoi manger, puis des fleurs qui lui rappellent le jardin de
sa mère, puis les ingrédients d'une vieille recette de tarte à la citrouille que sa mère lui a
laissée. En partageant la tarte, Lily se souvient de Julius, le meilleur ami de son père : il
aurait survécu et vivrait dans une autre ville, près de l'ancien village.

La dernière quête amène le joueur à **parler à Julius** pour suivre la piste des parents.
**On ne dit pas ce que sont devenus les parents** : l'histoire reste ouverte pour une suite.

## 3. Ambiance

Douce, mélancolique, pleine d'espoir. Lily est jeune, polie, un peu timide. Julius est un adulte
bourru mais chaleureux. Phrases courtes, vocabulaire simple, tutoiement.

## 4. Personnages

| Personnage | Qui est-ce ? | Où se trouve-t-il ? | Identifiant souhaité |
|---|---|---|---|
| Lily | Jeune fille séparée de ses parents | Village central | `lily` |
| Julius | Meilleur ami du père de Lily | Une **autre ville**, près de l'ancien village | `julius` |

**Placement** : aucun des deux n'a de coordonnées dans cette fiche. Après l'import, on les relie à
un PNJ Citizens depuis la page **PNJ** de PlugAdmin (ou on garde le PNJ déjà existant s'il porte
le même identifiant). L'IA ne doit **jamais** inventer de coordonnées.

## 5. Lieux

- **Village central** — là où se trouve Lily. Aucune coordonnée connue.
- **Autre ville, près de l'ancien village** — là où vit Julius. Aucune coordonnée connue.

Pas d'objectif « atteindre une zone » dans cette histoire : sans coordonnées réelles, l'objectif
final est simplement de **parler à Julius**.

## 6. Les quêtes, dans l'ordre

Chaque quête ne se débloque qu'après la précédente.

1. **Un repas pour Lily** — Lily a faim. Le joueur lui **remet** 3 pains et 4 pommes.
   Récompense : 20 points d'expérience.
2. **Des fleurs pour Lily** — Le jardin de sa mère. Le joueur lui **remet** 3 coquelicots et
   3 pissenlits. Récompense : 25 points d'expérience.
3. **La vieille recette** — Le joueur **remet** les ingrédients : 1 citrouille, 2 sucres, 2 œufs,
   3 blés (pour la pâte). Récompense : 40 points d'expérience.
4. **La tarte de maman** — Lily cuisine elle-même. Le joueur **remet** 4 charbons pour chauffer son
   four de fortune, puis **reparle** à Lily pour goûter la tarte : elle se souvient de Julius.
   Récompense : 40 points d'expérience et 2 tartes à la citrouille.
5. **Sur la piste de Julius** — Le joueur va **parler à Julius** dans l'autre ville. Julius
   raconte qu'il a vu les parents fuir vers les collines, sans savoir ce qu'ils sont devenus.
   Récompense : 60 points d'expérience, et un repère « Julius retrouvé » pour une suite future.

> **À propos de la tarte** : RPGQuest ne crée **aucune recette personnalisée**. Ici, c'est Lily
> qui cuisine, dans le récit ; le joueur fournit les ingrédients et le charbon. Si vous préférez
> que le joueur fabrique la tarte lui-même, demandez un objectif « fabriquer » `PUMPKIN_PIE` :
> c'est la recette **vanilla** de Minecraft (citrouille + sucre + œuf), qui existe déjà.

## 7. Dialogues souhaités

- **Lily** : présentation ; proposition de chaque quête au bon moment (jamais une quête déjà
  terminée ou pas encore débloquée) ; accepter / « pas maintenant » ; remise des objets ;
  moment où l'on goûte la tarte ; rappel pendant la recherche de Julius ; conclusion pleine
  d'espoir une fois Julius trouvé.
- **Julius** : méfiant tant qu'on ne vient pas de la part de Lily ; puis récit de ce qu'il a vu ;
  ouverture vers une suite (« reviens me voir un jour »).

## 8. Récompenses souhaitées

Modestes : de l'expérience, une petite part de nourriture. Pas d'argent, pas d'objet rare.
Les quantités se modifient ensuite dans les éditeurs de PlugAdmin.

## 9. Ce qui reste à définir (à signaler, pas à inventer)

- Position exacte de Lily au village central.
- Ville exacte de Julius et sa position.
- Suite de l'histoire (que sont devenus les parents ?).
