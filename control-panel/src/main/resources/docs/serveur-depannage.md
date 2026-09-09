---
title: Résoudre les problèmes de serveur et d'agent
category: Serveur
tags: [serveur, agent, heartbeat, offline, online, monde, world, diagnostic, depannage, plugadmin]
order: 2
---

# Résoudre les problèmes de serveur et d'agent

Ces diagnostics viennent du **dernier heartbeat** envoyé par le plugin RPGQuest à PlugAdmin
(canal agent sortant). Ce n'est pas du monitoring : uniquement ce qui empêche d'administrer le
serveur ou fausse l'état affiché.

---

## Aucun agent distant configuré

### Ce que cela signifie

Aucune cible RPGQuest n'a d'agent : PlugAdmin ne reçoit ni heartbeat, ni catalogue (PNJ,
dialogues, quêtes, stories).

### Pourquoi il faut corriger

Sans agent, la page Diagnostics ne peut rien calculer et aucune action à distance n'est
possible.

### Comment corriger

1. Sur le serveur RPGQuest, configurer l'**agent sortant** (`plugadmin-agent.properties` :
   URL de PlugAdmin, identifiant d'agent, jeton).
2. Redémarrer le serveur (ou recharger la configuration si supporté).
3. Vérifier que PlugAdmin reçoit un heartbeat : page **Agents**.

### Vérification

La page **Agents** affiche un heartbeat récent et l'état passe à **ONLINE**.

### Référence technique

`AGENT_NOT_CONFIGURED`

---

## Aucun signal de l'agent

### Ce que cela signifie

Un agent est configuré, mais il n'a **jamais** contacté PlugAdmin.

### Pourquoi il faut corriger

Impossible de connaître l'état du serveur ou d'exécuter la moindre action à distance.

### Comment corriger

1. Vérifier que le plugin RPGQuest est bien démarré (console du serveur).
2. Vérifier que le serveur peut joindre PlugAdmin en **HTTPS sortant** (pare-feu, DNS,
   certificat).
3. Vérifier l'URL et le jeton dans `plugadmin-agent.properties`.
4. Consulter les logs du serveur : chercher les lignes de l'agent RPGQuest.

### Vérification

Un heartbeat apparaît sur la page **Agents**.

### Référence technique

`AGENT_NO_HEARTBEAT`

---

## Agent hors ligne

### Ce que cela signifie

Le dernier heartbeat de l'agent est **trop ancien** (au-delà du seuil hors ligne).

### Pourquoi il faut corriger

L'état affiché n'est plus fiable et aucune action à distance n'aboutira.

### Comment corriger

1. Vérifier que le serveur RPGQuest tourne toujours.
2. Vérifier la connectivité sortante du serveur vers PlugAdmin.
3. Si le serveur a été redémarré, attendre le prochain heartbeat (quelques secondes).

### Vérification

L'âge du heartbeat repasse sous le seuil ; l'état redevient **ONLINE**.

### Référence technique

`AGENT_OFFLINE`

---

## Signal de l'agent vieillissant

### Ce que cela signifie

Le dernier heartbeat commence à dater, sans avoir encore franchi le seuil hors ligne.

### Pourquoi il faut corriger

Ce n'est pas bloquant, mais les informations affichées peuvent être légèrement en retard.

### Comment corriger

Surveiller : si le retard s'aggrave, traiter comme **Agent hors ligne** (connectivité
sortante du serveur).

### Vérification

L'âge du heartbeat redescend et se stabilise.

### Référence technique

`AGENT_HEARTBEAT_STALE`

---

## Serveur non ONLINE

### Ce que cela signifie

L'agent répond, mais le serveur RPGQuest s'annonce dans un autre état (`STARTING`,
`STOPPING`, ou une erreur au chargement).

### Pourquoi il faut corriger

Les joueurs peuvent ne pas pouvoir se connecter ou jouer normalement.

### Comment corriger

1. Ouvrir la console du serveur.
2. Identifier : démarrage en cours, arrêt demandé, ou erreur au chargement d'un plugin / d'un
   monde.
3. Corriger la cause puis redémarrer si nécessaire.

### Vérification

Le heartbeat annonce de nouveau `ONLINE`.

### Référence technique

`SERVER_NOT_ONLINE`

---

## Version du plugin inconnue

### Ce que cela signifie

Le heartbeat n'indique pas la version du plugin RPGQuest.

### Pourquoi il faut corriger

Difficile de savoir si le serveur tourne bien la version attendue ; un heartbeat sans version
peut aussi signaler un **agent trop ancien**.

### Comment corriger

1. Vérifier la version du JAR RPGQuest déployé sur le serveur.
2. Mettre à jour l'agent / le plugin si la version d'agent est ancienne.

### Vérification

La version apparaît sur le **Dashboard** et la page **Agents**.

### Référence technique

`PLUGIN_VERSION_UNKNOWN`

---

## Monde essentiel non chargé

### Ce que cela signifie

Un monde essentiel (rôle `hub` / `claims` / `wild`…) est **configuré** mais **n'est pas
chargé** sur le serveur.

### Pourquoi il faut corriger

Les portails, spawns et contenus qui dépendent de ce monde ne fonctionneront pas (parcours
d'onboarding cassé).

### Comment corriger

1. En jeu : `/rpgadmin world list` pour confirmer quels mondes sont chargés.
2. Créer / recharger le monde manquant : `/rpgadmin world create <nom>`.
3. Vérifier les logs de démarrage : un monde peut échouer à charger (dossier corrompu,
   version incompatible).

### Vérification

Le monde apparaît « chargé » dans le tableau des mondes du **Dashboard**.

### Référence technique

`WORLD_NOT_LOADED`
