# Groupes et droits effectifs

Un **groupe** est un ensemble de permissions PlugAdmin attribuable à plusieurs comptes. Un compte
peut appartenir à **plusieurs** groupes.

Ces groupes sont propres à PlugAdmin : ils n'ont **aucun** rapport avec OP Minecraft, avec les
droits de construction en jeu ou avec LuckPerms.

## La règle à connaître : l'union

Un compte a un droit s'il le tient de son **rôle** *ou* d'au moins un de ses **groupes**.

Il n'existe **aucun refus explicite**. Autrement dit : **un groupe ne peut jamais retirer un droit
que le rôle accorde.** Pour retirer un droit, deux gestes possibles, et un seul suffit :

- retirer la permission du groupe (effet sur **tous** ses membres) ;
- retirer le compte du groupe (effet sur **ce** compte).

## Effet immédiat

Retirer un groupe, vider ses permissions ou le supprimer agit sur les **sessions déjà ouvertes**,
dès la requête suivante. La personne n'a pas besoin de se déconnecter, et il n'y a aucun cache à
vider.

## Ce que vous ne pouvez pas faire, et pourquoi

**Vous ne pouvez ni accorder ni retirer un droit que vous ne détenez pas vous-même.** Les cases
correspondantes apparaissent désactivées avec la mention « vous ne détenez pas ce droit » — elles
sont montrées plutôt que cachées, parce que le refus est une décision, pas une absence.

C'est ce qui empêche de se fabriquer indirectement un droit réservé au propriétaire, par exemple
l'élévation OP (`PLAYER_OP_WRITE`). Même logique pour **modifier ou supprimer** un groupe qui
accorde des droits que vous n'avez pas : il reste consultable, mais pas modifiable.

## D'où vient un droit ?

Fiche d'un compte (`/users/<compte>`) → **Droits effectifs**. Chaque permission accordée est listée
avec **toutes** ses provenances : « rôle ADMIN », « groupe « Console » »…

C'est l'information utile le jour où un compte a un droit qu'on ne lui voulait pas : la question
n'est pas *s'il* l'a, mais *par où* il arrive. Un droit peut venir de plusieurs endroits à la fois —
dans ce cas, il faut le retirer de **chacun**.

## Supprimer un groupe

La suppression demande de **retaper le nom** du groupe. Elle retire le groupe à tous ses membres :
leurs rôles ne changent pas, ils perdent uniquement ce que le groupe ajoutait.

## Migration depuis les rôles seuls

Les rôles restent la **base** de chaque compte et n'ont pas été convertis en groupes : c'est
volontaire, pour qu'aucun compte existant ne change de droits. Une installation qui n'a jamais créé
de groupe a donc exactement les droits de ses rôles.

## Audit

Toute création, modification, suppression de groupe et tout changement d'appartenance est
journalisé, **y compris les refus**. Page « Actions / Audit ».
