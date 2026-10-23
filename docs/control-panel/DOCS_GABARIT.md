# Gabarit des fiches du centre de documentation PlugAdmin (#780)

Les fiches de `/docs` sont des fichiers Markdown versionnés dans
`control-panel/src/main/resources/docs/`, listés dans `_index.txt` (liste blanche). Elles sont
rendues par le moteur maison `panel.docs.Markdown` (sûr : tout est échappé, aucun HTML brut).
Ce gabarit est **vérifié par les tests** : une fiche qui ne le respecte pas fait échouer la CI.

## Tests qui gardent le gabarit

| Test | Ce qu'il refuse |
|---|---|
| `DocTemplateTest.everySheetHasAStructureTheEngineRenders` | tout problème signalé par `Markdown.lint` (voir plus bas) |
| `DocTemplateTest.everySheetFollowsTheTemplate` | front matter incomplet, fiche liée inconnue, pas d'introduction, second `<h1>` |
| `DocTemplateTest.everyInternalLinkPointsToAnExistingSheetAndSection` | lien vers une fiche ou une section qui n'existe pas |
| `DocTemplateTest.everyAnchorThatExistedBeforeTheRedesignStillExists` | disparition d'une ancre listée dans `control-panel/src/test/resources/docs-guard/anchors-780.txt` |
| `DocsHelpLinksTest` | lien d'aide du code Java (`"/docs/…#…"`, `DiagnosticHelp`, éditeur de dialogues) qui ne mène à aucune section |
| `DocsPagesTest.sheetShowsTocFirstWithRealAnchorsRelatedSheetsAndPagerIssue780` | entrée de sommaire sans ancre, fiche sans « Fiches liées » ni précédent/suivant |

## Modèle d'une fiche

```markdown
---
title: Faire parler un personnage
category: Contenu
tags: [dialogue, réplique, publier]
related: [dialogues-depannage, pnj-citizens]
order: 0
---

# Faire parler un personnage

Une ou deux phrases : à quoi sert la page, ce qu'on y fait.

**En bref**, dans l'ordre :

1. [Créer](#creer-un-dialogue).
2. [Modifier](#modifier-un-dialogue).

## Créer un dialogue

### Une sous-étape
```

- **Front matter obligatoire** : `title`, `category`, `tags` (pour la recherche) et `related`
  (identifiants des fiches liées, affichées en fin de fiche et dans la colonne du sommaire). Une
  fiche citée dans `related` d'une autre la voit aussi apparaître dans ses fiches liées.
- **Un paragraphe d'introduction** avant le premier `##`.
- **Les grandes étapes en `##`** (créer, modifier, enregistrer/publier, tester, dépanner, puis
  « Pour aller plus loin » pour les options avancées) ; le détail en `###`, au besoin en `####`.
  Le sommaire montre les `##` et `###` (sur téléphone : les `##` seulement, en tête d'article).
- **Ne pas sauter de niveau** de titre (`##` puis `####` est refusé).

## Ancres : ne jamais casser un lien existant

L'ancre d'une section est calculée depuis son titre (`Markdown.slug`) : minuscules, sans accents,
apostrophes supprimées (`Une réplique que personne n'atteint` → `une-replique-que-personne-natteint`),
autres signes remplacés par `-`. Un deuxième titre identique dans la même fiche reçoit `-2`.

- **Renommer un titre change son ancre.** Les messages d'erreur, `DiagnosticHelp` (ancre =
  `slug(title)`) et les favoris pointent vers ces ancres : on peut **déplacer** une section ou
  changer son niveau (`##` → `###`) sans risque, mais pas la renommer.
- Si un renommage est vraiment nécessaire : mettre à jour les liens du code **et** la ligne de
  `anchors-780.txt` dans la même PR, en le justifiant dans le rapport.
- Une nouvelle section n'a rien à déclarer : seules les ancres déjà publiées sont figées.

## Listes

- Puce : `- `. Étapes numérotées : `1. `, `2. `… (une action par étape).
- **Sous-liste** : indenter la puce sous le **texte** de l'élément parent (3 espaces sous `1. `,
  2 espaces sous `- `). Profondeur maximale : **3 niveaux** — au-delà, découper en sous-section.
- **Phrase sur plusieurs lignes** : indenter la suite sous le texte de l'élément (comme la
  sous-liste). Un lien peut être sur une ligne de suite.
- Un paragraphe de suite dans un élément : ligne vide, puis texte indenté.
- Pas de bloc de code ` ``` ` dans une liste : le sortir, sans indentation.

## Texte

- Écrire pour un lecteur de 13 ans : phrases courtes, vocabulaire courant, une action par étape.
- Nommer les boutons **exactement** comme dans PlugAdmin, en gras : **Enregistrer et mettre en jeu**.
- Encadrés seulement s'ils sont utiles : `> [!NOTE]` (« À savoir ») ou `> [!WARNING]`
  (« Attention »). Leur contenu peut contenir une liste.
- Tableaux : une ligne d'en-tête, même nombre de cellules sur chaque ligne (pas de `|` dans le
  texte d'une cellule).
- Pas de balise HTML (`<br>`, `<b>`…) hors `code` : elle serait affichée telle quelle. Un
  paramètre comme `<joueur>` est accepté.
- Liens : `/docs/fiche`, `fiche` ou `fiche#section` (une autre fiche), `#section` (même fiche),
  ou `https://…`. Tout autre lien est rendu sans lien et refusé par le test.
- Ne rien supprimer d'utile : un détail technique se **déplace** vers « Pour aller plus loin » ou
  vers la fiche de dépannage.

## Vérifier une fiche localement

```
RPGQUEST_TEST_MAX_HEAP=768m ./gradlew :control-panel:test --tests 'com.lodygames.rpgquest.panel.docs.*'
```

Chaque problème est donné avec son numéro de ligne (ligne du corps Markdown, front matter exclu).
