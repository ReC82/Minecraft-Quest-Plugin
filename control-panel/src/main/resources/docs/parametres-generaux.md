---
title: Paramètres généraux
category: Serveur
tags: [paramètres, réglages, monnaie, lodycoins, bêta, avertissement, connexion, bypass]
order: 0
related: [exploitation-serveur, groupes]
---

# Paramètres généraux

**PlugAdmin > Paramètres généraux** règle le jeu pour tous les joueurs, sans éditer de fichier. Les
valeurs sont enregistrées sur le serveur (`plugins/RPGQuest/settings.yml`, écrit par le plugin) ;
la page affiche ce que le serveur a **relu**, avec l'auteur et la date de la dernière modification.

« Relire » interroge le serveur. Tant que les valeurs ne sont pas connues, rien n'est modifiable.
Après « Enregistrer », le bandeau dit « Enregistrement en cours… » jusqu'à la réponse, puis affiche
les valeurs relues — ou le motif du refus (rien n'a changé dans ce cas).

## Économie

**Nom affiché de la monnaie** (par défaut *LodyCoins*) : remplace « pièces » dans la bourse, les
marchands, le marché, les récompenses de quête et PlugAdmin. Les soldes, les achats et les
identifiants internes ne changent pas. 1 à 24 lettres, chiffres ou espaces.

## Connexion

**Avertissement « version bêta »** : une fenêtre à chaque connexion, avec « OK » (il reviendra la
fois suivante) et « OK et ne plus afficher » (choix gardé pour ce joueur, même après un
redémarrage). Le désactiver ici ne supprime pas ces choix. Un reset « nouveau joueur » efface les
variables du joueur : l'avertissement revient pour lui. Texte : 500 caractères, sans mise en forme.

## Administration

**Mode bypass administrateur** (#35). Un OP joue comme un joueur normal : les protections du monde
Claims, des claims, des zones et du Hub s'appliquent à lui. Pour intervenir, un administrateur
autorisé (`rpgquest.admin.bypass`, par défaut les OP) tape lui-même `/rpgadmin bypass on` en jeu ;
il voit alors en permanence « ⚠ MODE ADMIN / BYPASS ACTIF », et le mode se coupe avec
`/rpgadmin bypass off`, à sa déconnexion ou au redémarrage.

- **Mode bypass sur ce serveur** : « Proposé aux administrateurs autorisés » (défaut) ou
  « Désactivé pour tout le monde ». Désactiver coupe aussitôt les sessions en cours (l'administrateur
  est prévenu) et empêche d'en ouvrir. « Proposé » n'active le mode pour personne.
- **Durée maximale d'une activation** : 0 (défaut) = jusqu'à la déconnexion ; sinon 1 à 480 minutes,
  puis le mode se coupe tout seul.

L'indicateur en jeu n'est pas réglable. Aucun réglage de cette page n'accorde un droit : qui peut
activer le mode se règle par les permissions, et ce qu'il contourne dépend des nœuds de bypass du
compte. Voir [Permissions](https://github.com/ReC82/Minecraft-Quest-Plugin/blob/main/docs/PERMISSIONS.md).
Si la rubrique indique que le plugin ne connaît pas ces réglages, la version installée sur le serveur
est antérieure à #35.

## Rôles

Lecture : `SETTINGS_READ`. Modification : `SETTINGS_WRITE` (propriétaire et administrateurs).
Chaque modification est journalisée (auteur, rubrique) dans PlugAdmin et dans `settings.yml`.
