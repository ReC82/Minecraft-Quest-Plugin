---
title: Paramètres généraux
category: Serveur
tags: [paramètres, réglages, monnaie, lodycoins, bêta, avertissement, connexion, bypass]
order: 0
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

Le **mode bypass administrateur** (#35) n'existe pas encore : aucun réglage n'est proposé, et aucun
réglage de cette page n'accorde un droit. Voir [Permissions](https://github.com/ReC82/Minecraft-Quest-Plugin/blob/main/docs/PERMISSIONS.md)
pour l'état actuel des droits OP.

## Rôles

Lecture : `SETTINGS_READ`. Modification : `SETTINGS_WRITE` (propriétaire et administrateurs).
Chaque modification est journalisée (auteur, rubrique) dans PlugAdmin et dans `settings.yml`.
