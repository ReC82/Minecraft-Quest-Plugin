# Bâtiments dans PlugAdmin — parcours avant / après (#265)

## Avant (jusqu'au 12/10/2026)

1. Aller en jeu, poser un **repère d'emplacement** (obligatoire, même pour simplement « essayer » un bâtiment).
2. Dans **Bâtiments → Emplacements**, ouvrir la fiche du repère et remplir le **« Brief architectural »**
   — à côté d'une « Note d'administrateur » au rôle voisin.
3. Cliquer « Générer un bâtiment avec l'IA » : une page unique mêlant plan YAML géant, audit, 14 coupes
   ASCII, versions, récupération, nouvelle proposition et « Remplacer ».
4. « Auditer » faisait parfois quitter la page ; le résultat était à chercher dans **Historique**, dont
   les lignes, sur PC, ne s'ouvraient pas.
5. Corriger exigeait de lire `PLAN_INVALID — Instruction 16` et d'éditer des coordonnées.
6. Générer, puis revenir à la fiche de l'emplacement pour poser.
7. Pour retirer un bâtiment : une action cachée au 5ᵉ rang de 15 sections, à côté de « Supprimer
   l'emplacement ».

## Après

**Trois modules distincts**, dans l'ordre du parcours :

| Module | Rôle | Ce qu'on n'y trouve plus |
|---|---|---|
| **Atelier de création** | décrire, faire proposer un plan, le vérifier, l'améliorer, l'enregistrer | aucun emplacement requis |
| **Bibliothèque** | les bâtiments prêts à poser | aucun brouillon |
| **Emplacements** | les lieux du monde où poser un bâtiment de la bibliothèque | (lot C) plus de description de bâtiment |

Parcours :

1. **Atelier → Nouveau projet** : nom, « Décrivez le bâtiment souhaité », côté de l'entrée.
2. **Créer un plan avec l'IA** (appel facturé, annoncé, protégé du double clic).
3. **Vérifier le bâtiment** (gratuit) : le résultat s'affiche dans le projet, en français :
   « Le bâtiment n'a pas de porte utilisable du côté de l'entrée », avec la piste de correction.
4. **Améliorer avec l'IA** (facturé) : le motif est prérempli avec ce que la vérification a trouvé ;
   la réponse est une nouvelle version, l'ancienne reste.
5. **Enregistrer dans la bibliothèque** : jamais de remplacement implicite ; un nom déjà pris est signalé.
6. **Emplacements** : choisir le bâtiment, prévisualiser la pose, confirmer (#213/#234).
7. Retirer un bâtiment : **« Retirer le bâtiment et restaurer le terrain »**, en tête de la fiche d'un
   emplacement occupé, distinct de « Supprimer l'emplacement (le repère seulement) » (#239).

Toujours disponibles mais **repliés** dans « Détails techniques » : plan brut modifiable, codes et mesures
de la vérification, coupes en caractères (diagnostic — ce n'est pas un aperçu, cf. #273), anciennes
versions, réponse du modèle, reprise depuis l'historique, **« Copier le diagnostic »**.

## Ce que l'ancien parcours devient

- `/buildings/plan?site=…` mène à l'Atelier (au projet repris, ou à la liste avec la reprise proposée).
- Le travail commencé sur un emplacement — description et brouillons — se **reprend** depuis l'Atelier :
  la description est **copiée**, les brouillons rattachés, la fiche de l'emplacement **n'est pas modifiée**.
- L'historique ouvre chaque action depuis sa ligne, et « Retour à ce brouillon » ramène au projet.

## Limites connues de cette version

- Pas encore d'aperçu visuel fidèle (#273) : les coupes en caractères restent un outil de diagnostic.
- Les emplacements portent encore l'ancien champ « Brief architectural » jusqu'au lot C (#268).
