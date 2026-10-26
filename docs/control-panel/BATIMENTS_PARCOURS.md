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
| **Bibliothèque** | les bâtiments prêts à poser, « Placer sur un emplacement » | aucun brouillon |
| **Emplacements** | les lieux du monde (« Description du lieu ») où poser un bâtiment de la bibliothèque | ni description de bâtiment ni génération IA (#268) |

Parcours :

1. **Atelier → Nouveau projet** : nom, « Décrivez le bâtiment souhaité », côté de l'entrée ; sous « Taille
   et nom technique », trois limites distinctes : largeur X, longueur Z, hauteur Y (#305 — par ex.
   13 × 9 × 7).
2. **Créer un plan avec l'IA** (appel facturé, annoncé, protégé du double clic). Depuis #747, sans conception
   détaillée, l'Atelier la fait d'abord rédiger (un appel de plus) : volumes nommés et reliés, éléments rattachés ;
   « Structure lue par le panel » montre ce que le plan devra tenir (contrat de volumes) et ses lacunes.
3. **Vérifier le bâtiment** (gratuit) : le résultat s'affiche dans le projet, en français, avec
   l'**aperçu du bâtiment en 3D** (tourner autour, couper au-dessus d'un niveau pour voir l'intérieur) :
   « Le bâtiment n'a pas de porte utilisable du côté de l'entrée », avec la piste de correction.
4. **Améliorer avec l'IA** (facturé) : le motif est prérempli avec ce que la vérification a trouvé ;
   la réponse est une nouvelle version, l'ancienne reste. Depuis #747, la mémoire des tentatives du
   projet (erreurs résolues, régressions, stagnation) et la conformité au brief (✓ / ✗ / ◌) partent
   aussi ; « Nature de la reprise » choisit entre réparation technique et amélioration architecturale
   (« Automatique » suit la suggestion affichée). Tour, cloche dans la tour, allée et volumes de la conception
   sont **mesurés** sur l'aperçu compilé ; un meuble non demandé (lit…) est signalé « ⚠ ajout non demandé ».
   Si l'IA répond sans plan (refus, réponse vide, coupée, panne), **aucune version n'est créée** : la
   carte rouge « Le dernier appel à l'IA a échoué » dit pourquoi et propose « Réessayer avec le même
   contexte » ; « Repartir de zéro » est à part, à cocher. Après une stagnation, choisir une stratégie :
   reprendre depuis la meilleure version, ou « Nouvelle conception avec les enseignements ».
   Depuis le 24/10 (#747), ce que vous écrivez dans « Améliorer » devient une **exigence actualisée** : elle
   repart avec tous les appels suivants et prime sur la description et la conception (« pièce vide » puis
   « ajoute un lit » : le lit l'emporte). Modifier la description les remplace. Le volet replié « Essai avec un
   autre modèle » choisit un modèle et un plafond de sortie **pour cet appel seulement**.
5. **Enregistrer dans la bibliothèque** : jamais de remplacement implicite ; un nom déjà pris est signalé.
   Une version refusée uniquement pour des défauts contournables (lanterne sans support, zone sombre, fenêtres,
   accès…) propose, dans « Vérifier », **« Enregistrer malgré les avertissements »** : défauts et effets en jeu
   listés, case à cocher, aucun appel IA. Le serveur revérifie les défauts ; la version reste « Enregistré avec
   avertissements » et la pose le rappelle. Un plan illisible, un bloc inconnu, une zone hors volume ou un lit /
   une porte incomplets restent bloqués.
6. **Choisir un emplacement** (depuis l'Atelier) ou **Placer sur un emplacement** (depuis la Bibliothèque) :
   les Emplacements s'ouvrent avec le bâtiment déjà choisi ; prévisualiser la pose, confirmer (#213/#234/#268).
7. Retirer un bâtiment : **« Retirer le bâtiment et restaurer le terrain »**, en tête de la fiche d'un
   emplacement occupé, distinct de « Supprimer l'emplacement (le repère seulement) » (#239).

Toujours disponibles mais **repliés** dans « Détails techniques » : plan brut modifiable, codes et mesures
de la vérification, coupes en caractères (diagnostic — ce n'est pas un aperçu, cf. #273), anciennes
versions, réponse du modèle, **« Copier le diagnostic »**.

Sous la fiche, deux sections distinctes (#308) : **« Historique de ce projet »** (ses seules versions ;
« il est neuf » s'il n'en a aucune ; depuis #828 : identifiant court copiable `V-xxxxxxxx`, statut réel
Conforme / Avertissements acceptables / Bloqué / Non vérifiée avec ses codes, version affichée encadrée,
« Reprendre V-… », « Copier le lien », « Aller à une version » par identifiant, et lien direct
`/buildings/workshop/project?draft=V-xxxxxxxx`) et, repliée, **« Importer un plan d'un autre projet ou de l'historique
global »** — recherche, « Examiner avant import » (provenance, dimensions, entrée, incompatibilités, rien
d'écrit), puis « Importer comme nouvelle version de ce projet » : une copie, source inchangée, sans IA.

**Retrouver et nettoyer ses projets (#881).** La liste de l'Atelier a une recherche (nom, nom technique,
description — sans casse ni accents), des filtres (Tous, Brouillons, En bibliothèque, Utilisés en jeu, Non
utilisés, Utilisation à vérifier, À corriger) et un tri (récents, anciens, nom). Chaque tuile dit **Utilisé en
jeu** (avec « Voir où utilisé »), **Non utilisé** ou **Utilisation à vérifier**. En bas de la fiche d'un projet,
**« Supprimer le projet »** efface seulement ce que le panel garde de la conception, et seulement si le
bâtiment n'est ni posé, ni lié à un portail, ni publié dans la bibliothèque (pour un bâtiment publié, passer
par la Bibliothèque, #644), et si l'usage est vérifiable. Aucun bloc du monde n'est touché.

## Ce que l'ancien parcours devient

- `/buildings/plan?site=…` mène à l'Atelier (au projet repris, ou à la liste avec la reprise proposée).
- Le travail commencé sur un emplacement — description et brouillons — se **reprend** depuis l'Atelier :
  la description est **copiée**, les brouillons rattachés, la fiche de l'emplacement **n'est pas modifiée**.
- La fiche d'un emplacement n'a plus de « Brief architectural » ni de « Générer un bâtiment avec l'IA ».
  L'ancien brief reste lisible (replié, lecture seule) avec le lien vers son projet, ou « Reprendre dans
  l'Atelier ». « Note d'administrateur » s'appelle **« Description du lieu »** (même donnée).
- L'historique ouvre chaque action depuis sa ligne, et « Retour à ce brouillon » ramène au projet.

## Limites connues de cette version

- Aperçu en 3D (#273) : couleurs par matériau, pas de textures ; portes, vitres, escaliers et mobilier
  dessinés en cubes. Nécessite le plugin de #273 (sinon l'écran le dit). Les coupes en caractères restent
  un outil de diagnostic.
- Le brief reste stocké par le plugin sur l'emplacement (non destructif) ; il n'est plus modifiable depuis la fiche.

## Garde-fous de non-régression (#270)

| Exigence de #265 | Test automatique |
|---|---|
| Brouillons existants récupérés (Bibliothèque du Hub, `buildsite_0009`) | `WorkshopFlowTest.aSiteBriefAndItsDraftsAreImportedWithoutTouchingTheSite`, `BuildingProjectStoreTest` (migration d'une base #256) |
| Reprise depuis l'ancien historique, sans IA | `WorkshopFlowTest.aPlanIsRecoveredFromAnActionIntoAProjectWithoutCallingTheAi` |
| Historique du projet isolé, import explicite et copie (#308) | `WorkshopFlowTest.aNewProjectHasAnEmptyHistoryAndOrphanPlansStayInTheSeparateImport`, `…theImportPreviewShowsProvenanceAndConflictsWithoutWritingAnything`, `…importingFromAnotherProjectCopiesAndLeavesTheSourceUntouched`, `…aForeignAgentsPlanIsRefusedServerSide`, `PlanImportPreviewTest` |
| Même bâtiment sur plusieurs emplacements, retraits indépendants | `BuildingPlacementServiceTest.theSameBuildingOnTwoSitesGivesTwoIndependentPlacements` |
| Aucun appel facturé en naviguant, en tournant ou en coupant l'aperçu | `WorkshopFlowTest.aPaidPlanBecomesAVersionAndNoNavigationCallsTheAiAgain`, `…aVerificationAlsoDrawsTheBuildingFromTheServerGrid` |
| Aucun écrasement en bibliothèque | `WorkshopFlowTest.savingInTheLibraryNeverReplacesAnything`, `…aTakenLibraryNameBlocksSavingAndSaysWhatToDo` |
| Anciennes adresses | `WorkshopFlowTest.oldGeneratorAddressesLeadToTheWorkshop`, `BuildingSitesPageTest.theOldGenerationAddressLeadsToTheWorkshop` |
| Emplacement occupé : jamais supprimé | `BuildingSiteServiceTest.anOccupiedSiteIsNeverDeleted` (serveur), `BuildingLifecyclePageTest` (panel) |
| L'ancien brief d'un emplacement n'est pas détruit | `BuildingSitesPageTest.onceTakenToTheWorkshopTheSiteLinksToItsProjectAndSendsNothingToTheServer` |

Tests manuels : TC-284 à TC-289.
