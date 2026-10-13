---
title: Villes
category: Réseau de voyage
tags: [ville, villes, centre, perimetre, decouverte, bienvenue, outil, DISCOVER_CITY]
order: 2
---

# Villes

Une **ville** est un lieu nommé du monde, défini par un **centre** et une **distance de détection**.
La première fois qu'un joueur y entre, il voit en plein écran **« Bienvenue à [nom] »** et la ville
est enregistrée comme **découverte**. Une ville sert aussi de destination dans le menu de voyage
« Villages » (c'est le même objet que les anciens « centres de village »).

---

## 1. Créer une ville : en jeu, avec l'outil

1. En jeu : `/rpgadmin ville outil` (permission `rpgquest.admin.city`). Vous recevez une
   **pelle en or « Outil de ville »**.
2. Placez-vous au cœur de la future ville et faites **clic droit sur un bloc** : ce bloc devient le
   centre. Un seul clic, pas de second coin.
3. Une **enclume** s'ouvre : tapez le nom, puis cliquez l'objet de résultat. Fermer la fenêtre
   annule, et rien n'est créé.
4. Le message de confirmation donne le nom, l'identifiant (par exemple `ville_port_lily`), le
   centre et la taille.

Aucun bâtiment ni PNJ n'est posé : le centre est un repère géographique.

## 2. Gérer les villes : PlugAdmin › Villes

Cliquez **Rafraîchir les villes**. Pour chaque ville, la fiche affiche :

- le nom, l'identifiant (qui ne change jamais), le monde et le centre ;
- la règle : **« La ville s'étend de 50 blocs dans chaque direction depuis son centre »**, avec les
  dimensions (101 × 101 blocs) et les limites X/Z exactes ;
- l'état (active / inactive), le nombre de joueurs qui l'ont découverte et les villes qui la
  chevauchent ;
- un aperçu du carré (nord en haut).

Actions disponibles :

| Action | Effet |
|---|---|
| **Renommer** | Change le nom du titre et du menu de voyage. Identifiant, découvertes et quêtes inchangés. |
| **Appliquer la taille** | Distance de 5 à 500 blocs dans chaque direction (défaut 50). |
| **Désactiver / Activer** | Inactive : plus de découverte ni d'entrée dans le menu « Villages ». Les découvertes déjà faites restent acquises. |
| **Préparer le déplacement du centre** | Le joueur choisi (connecté, avec la permission) a 3 minutes pour cliquer avec l'outil au nouvel endroit. Rafraîchissez ensuite : le nouveau centre et les limites s'affichent. Équivalent en jeu : `/rpgadmin ville deplacer <id>`. |
| **Montrer le périmètre en jeu** | Contour en particules orange, avec des colonnes aux coins, montré au seul joueur choisi pendant 15 s. Rien n'est modifié. |
| **Supprimer la ville** | Définitif : supprime la ville **et ses découvertes**. Les quêtes qui la citent ne progressent plus. Préférez la désactivation. |

Droits PlugAdmin : lecture avec *Réseau de voyage* (`TRAVEL_READ`), modifications avec
`CITY_WRITE`, suppression avec `CITY_DELETE` (rôles Propriétaire et Administrateur).

## 3. Le périmètre : un carré, pas un cercle

Avec un centre en X = cx, Z = cz et une distance d, un joueur est dans la ville si **X est entre
cx − d et cx + d** et **Z est entre cz − d et cz + d**, dans le même monde. L'altitude est ignorée
et les coins sont inclus. 50 signifie 50 blocs vers le nord, le sud, l'est et l'ouest : un carré de
101 × 101 blocs, bloc central compris.

**Villes qui se chevauchent** : une seule est retenue à un endroit donné. C'est celle dont le
centre est le plus proche ; à égalité, la plus petite, puis l'identifiant. Il n'y a jamais deux
titres en même temps. Les autres villes se découvrent en s'approchant de leur centre.

## 4. Découverte

- Titre au **premier** passage seulement, à pied, par téléportation ou à la connexion.
- Sortir puis revenir, mourir, se reconnecter ou redémarrer le serveur ne répète rien et ne crée
  pas de seconde découverte.
- Une ville inactive ne déclenche rien. Ouvrir cette page ne découvre rien pour personne.
- Durées du titre : `config.yml` › `cities.discovery.title`. Textes : `messages.yml` ›
  `city.welcome-title` et `city.welcome-subtitle`.

## 5. Objectif de quête « Découvrir des villes »

Dans l'éditeur de quête, ajoutez un objectif **Découvrir des villes** (`DISCOVER_CITY`) :

- **Une ville précise** : choisissez-la par son nom dans « Ville précise ». Laissez la quantité
  vide.
- **X villes distinctes** : laissez la ville vide et indiquez le nombre.
- **Villes déjà découvertes avant d'accepter la quête** : `NEW_ONLY` (défaut) ne compte que les
  nouvelles ; `INCLUDE_EXISTING` compte aussi celles déjà découvertes. La règle est affichée au
  joueur.

Une ville inconnue, ou une quantité avec une ville précise, est refusée à l'enregistrement.
Publiez la quête comme d'habitude : elle progresse à chaud, sans redémarrage.
