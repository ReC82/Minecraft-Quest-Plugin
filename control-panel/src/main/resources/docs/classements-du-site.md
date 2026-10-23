---
title: Classements du site
category: Site public
tags: [classements, leaderboards, portail, site, saison, événement, accueil]
order: 0
---

# Classements du site

**PlugAdmin > Site public > Classements** décide de ce que le site public LodyQuests affiche comme
classements : quoi mesurer, sur quelle période, dans quel ordre, et lesquels sont mis en avant sur
l'accueil. Rien ne s'édite à la main : le serveur écrit lui-même `plugins/RPGQuest/leaderboards.yml`,
séparé des saisons, des événements et de toute donnée de jeu.

« Rafraîchir les classements » relit l'état du serveur. Tant qu'il n'est pas chargé, rien n'est
modifiable.

## Les statuts

- **Brouillon** : en préparation. Ni calculé, ni publié. Tout nouveau classement naît brouillon.
- **Actif** : calculé, figé à la fin de chaque période (hier, la semaine dernière…), publié s'il est
  visible.
- **En pause** : suspendu ; ses réglages et ses résultats déjà figés sont conservés.
- **Archivé** : retiré du site (case « Je confirme l'archivage »). Ses résultats figés restent en base ;
  « Remettre en brouillon » le fait revenir.

**Cacher du site** : le classement reste calculé et figé, mais personne ne le voit (utile pour le
préparer avant de l'annoncer). **Mettre à l'accueil** : jusqu'à 3 classements, dans l'ordre de la liste.

## Créer un classement

1. **Que mesurer ?** Seules les mesures que le serveur enregistre vraiment sont proposées, avec la date
   depuis laquelle elles sont suivies. Aucun score n'est inventé.
2. **Quelles cibles ?** Cocher des familles (zombies, minerais…) ou écrire des noms exacts
   (`ZOMBIE`, `DIAMOND_ORE`, `COD`). Vide : tout compte. Les familles de monstres ne vont qu'avec
   « Monstres et créatures vaincus », celles de blocs qu'avec « Blocs cassés ».
3. **Sur quelle période ?** Chaque jour, semaine (lundi minuit), mois, depuis toujours, la saison en
   cours, un événement de `statistics.yml`, ou des dates personnalisées en heure de Bruxelles
   (changement d'heure compris).
4. **Présentation** : titre, phrase pour les joueurs, catégorie, nombre de joueurs affichés (1 à 50),
   icône.

Puis **Prévisualiser** : période en cours et ses dates exactes, période précédente figée ou non,
nombre de participants et total (comptes agrégés, aucun pseudo), avertissements (mesure jamais
collectée, moins de 3 joueurs, export web désactivé) et un podium **fictif** pour se représenter le
rendu. Enfin **Activer**.

## Bon à savoir

- Le site public n'affiche aucun classement réel tant que l'export web est désactivé (décision #55).
  Les réglages sont prêts et s'appliquent dès l'ouverture.
- Si `statistics.yml` contient sa propre liste `leaderboards:`, elle reste prioritaire et la page est en
  lecture seule.
- Un refus du serveur (cible inconnue, événement absent, formulaire périmé) n'écrit rien : le message
  dit quoi corriger.
- Droits : lecture `LEADERBOARD_READ`, gestion `LEADERBOARD_WRITE` (propriétaire et administrateurs).
