---
title: Base de données (cible MariaDB)
category: Administration
tags: [base de données, mariadb, mysql, sqlite, migration, mot de passe, connexion, bascule, panel, isolement]
order: 0
---

# Base de données

**PlugAdmin > Base de données** (propriétaire uniquement, permission `DATABASE_CONFIGURE`) prépare
une **future** migration de SQLite vers MariaDB (issue #42). **Elle ne migre rien et ne change pas le
backend du serveur.**

## Backend actif réel

Le cadre du haut affiche le moteur avec lequel RPGQuest tourne **réellement** (SQLite ou MariaDB),
d'après le dernier heartbeat du plugin, ainsi que la version de schéma. « inconnu » signifie qu'aucun
heartbeat récent n'est arrivé ou que le plugin déployé est antérieur à #42.

## Base MariaDB cible

- Saisir l'hôte, le port, la base (dédiée à RPGQuest, vide), le compte (dédié, jamais
  l'administrateur global), le mode TLS et le mot de passe.
- Le mot de passe est **chiffré** dans la base du panel (AES-256-GCM, clé dérivée du secret du
  panel). Il n'est **jamais réaffiché** : le champ reste vide. Le laisser vide **conserve** le mot de
  passe enregistré.
- **Effacer le mot de passe** est une action séparée, à confirmer par la case à cocher.
- « mot de passe illisible » : le secret du panel a changé ; ressaisir le mot de passe.
- Le journal d'audit trace qui a enregistré, testé ou effacé, sans jamais l'hôte ni le mot de passe.

## Tester la connexion

« Enregistrer et tester la connexion » ouvre une session **en lecture seule** (délai borné à quelques
secondes) et ne lit que des métadonnées : version du serveur, TLS actif ou non, nombre de tables,
présence d'un schéma RPGQuest. **Connexion testée ≠ serveur migré.** Modifier l'hôte, la base, le
compte, le TLS ou le mot de passe efface le dernier résultat : un « OK » ne vaut que pour la cible
testée.

## Assistant de migration

Étapes 1 à 3 suivies automatiquement (état, cible enregistrée, connexion testée). Les étapes
« Simulation » et « Comparaisons » se font **hors panel**, avec l'outil
`rpgquest-db-migration.jar` sur une copie de `data.db` (runbook
`docs/deployment/MIGRATION_SQLITE_MARIADB.md`). Le bouton « Basculer » est **désactivé** : aucune
route du panel n'effectue de bascule, qui exige une autorisation écrite du propriétaire.

## Base propre au panel

La section du bas concerne la base **du panel lui-même** (issue #43), pas celle du jeu.

- **Stockage actif** : toujours « SQLite — control-panel.db ». L'inventaire (lecture seule) liste ses
  tables : comptes, groupes, audit, file d'actions de l'agent, brouillons, réglages. Aucune donnée de
  jeu n'y est la référence : le panel lit et modifie le jeu via l'agent, jamais en SQL.
- **Base MariaDB du panel** : badge « enregistrée — non utilisée ». Mêmes règles de mot de passe que
  ci-dessus (chiffré, jamais réaffiché, vide = conserver, effacement confirmé). Utiliser une base
  **et** un compte dédiés : une base identique à la cible RPGQuest est refusée, un compte identique
  est signalé.
- **Tester la connexion** vérifie aussi l'**isolement** : le test échoue si le compte voit une base
  RPGQuest (« Isolement non respecté »). Retirer alors à ce compte tout droit sur la base du jeu.
- **Basculer le panel sur MariaDB** est désactivé : le panel continue sur `control-panel.db`. Plan,
  prérequis et rollback : `docs/deployment/PANEL_DATABASE.md`.

## Dépannage

- **Dry-run refusé : « schéma source V34 ≠ schéma de l'outil V35 »** : la copie de `data.db` vient
  d'un plugin plus ancien que l'outil. Depuis #354 l'outil est en V35 (textes libres en `TEXT` côté
  MariaDB) : reprendre une copie faite après le démarrage d'un plugin V35, ou porter une seconde
  copie au schéma courant comme l'indique le runbook.
- **Échec de connexion : Access denied** : compte ou mot de passe incorrect, ou le compte n'a pas le
  droit d'accès depuis l'adresse d'AWS.
- **Délai dépassé / connexion refusée** : hôte ou port erroné, pare-feu, ou serveur arrêté.
- **TLS** : avec `verify-ca` / `verify-full`, le certificat du serveur doit être valide ; ne jamais
  contourner une erreur de certificat, revenir vers l'hébergeur.
