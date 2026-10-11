# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-11
* Heure : 22:55
* Sujet : Validation complète et déploiement DEV des lots #197 et #94/#213, après libération de mémoire sur l'hôte de build
* Statut : DONE — suite complète verte, JAR et panneau déployés, serveur redémarré, chaîne vérifiée de bout en bout. Reste `PENDING MANUAL VALIDATION` : la génération IA elle-même et le rendu en jeu.
* Branche Git : `feature/238-guard-dialogue-ux` (ligne d'intégration), poussée
* Commit actuel si disponible : `71ff172`
* Début de la tâche : 2026-10-11 21:58:46
* Fin de la tâche : 2026-10-11 22:55:10
* Durée totale : 00:56:24

## Demande

Reprendre la finalisation de #197 et #94/#213 après nettoyage de l'EC2 (sessions tmux fermées,
1,6 Gio disponibles). Diagnostiquer d'abord les processus et la mémoire réellement disponibles,
adapter Gradle (un seul build, workers limités, heap raisonnable), ne pas lancer plusieurs tests
lourds en parallèle, **vérifier les résultats complets avant push, merge ou déploiement**, préserver
les commits locaux et les fichiers non suivis. Expliquer le blocage plutôt que de contourner les
contrôles si les tests restent impossibles. Puis poursuivre l'intégration et le déploiement DEV, en
demandant une autorisation explicite si un contournement TLS devient indispensable. Objectif final :
pouvoir tester la génération IA de la Bibliothèque du Hub depuis PlugAdmin.

## Analyse

### Le diagnostic a trouvé la cause, pas seulement le symptôme

Trois processus Java tournaient. Le plus gros, PID 883381 à **608 Mio**, était un **daemon Gradle
périmé** laissé par mes exécutions précédentes (reconnaissable à ses `--add-opens`). Les deux autres
sont des services du propriétaire : PlugAdmin (284 Mio) et le Discord sync (89 Mio), que je n'ai pas
touchés.

`./gradlew --stop` a fait passer la mémoire disponible de **1 653 à 2 156 Mio**.

Mais le vrai défaut était ailleurs, et c'est lui qui explique les deux interruptions précédentes :
**le module `control-panel` n'avait ni `maxParallelForks = 1`, ni borne de tas**, là où le module
plugin respecte les deux depuis longtemps. Sa JVM de test prenait donc son défaut — le quart de la
mémoire physique, soit ~958 Mio sur cet hôte de 3,8 Gio — et pouvait forker en parallèle. Or ces
tests démarrent de **vrais serveurs HTTP** sur ports éphémères, avec leurs bases SQLite temporaires.
Corrigé (`bf3e16a`), même mécanisme `RPGQUEST_TEST_MAX_HEAP` que le plugin, donc sans effet sur une
machine qui ne définit pas la variable.

La configuration utilisateur `~/.gradle/gradle.properties` était déjà saine (daemon à 256 Mio,
`workers.max=1`, `parallel=false`) : je ne l'ai pas modifiée.

### Décision : valider le résultat fusionné, pas chaque branche

Les deux lots partent de la même ligne d'intégration et touchent des fichiers communs
(`AgentActions`, `AgentActionExecutor`, `AgentActionType`, `BukkitAgentActions`, `AgentPages`,
`AgentActionCatalog` et leurs doubles de test). Valider chaque branche isolément aurait validé deux
états qui ne seront **jamais** déployés. J'ai donc fusionné d'abord, puis validé la fusion — c'est
exactement ce qui part sur DEV.

**Tout le code a fusionné automatiquement.** Seuls trois fichiers de documentation conflictaient,
parce que les deux lots avaient écrit au même endroit : `docs/claude-reports/README.md`,
`docs/current_state.md` et `docs/deployment/SERVER_CHANGELOG.md`. Résolus en gardant **les deux**
entrées, dans l'ordre chronologique (#197 à 20:15, #94 à 21:47). Vérifié ensuite : les deux entrées
de changelog et les deux lignes d'index sont bien présentes.

### Le garde-fou du script de déploiement, respecté et non contourné

`deploy-verygames.sh` exécute lui-même `./gradlew test` (3/8) et `build` (4/8), et son option
`--skip-build` **n'existe qu'avec `--dry-run`** : il refuse de transférer un JAR qu'il n'a pas
validé. La consigne était claire — ne pas contourner les contrôles. J'ai donc lancé le script
**depuis le worktree que je venais de bâtir**, pour que Gradle trouve ses tâches à jour. Le contrôle
s'exécute, il est simplement instantané au lieu de refaire 44 minutes.

### Le contournement TLS : mieux que prévu

J'avais annoncé une ligne temporaire dans le worktree de build. En lisant `verygames-common.sh`,
j'ai trouvé un mécanisme déjà prévu : `VERYGAMES_FTP_EXTRA_CURL_ARGS`. J'ai vérifié **deux** points
avant de m'en servir :

1. aucun garde-fou ne filtre cette variable ;
2. **l'environnement du shell appelant gagne sur le fichier de configuration** — le script
   sauvegarde explicitement les clés déjà présentes (`VG_ENVSAVE_*`), source le fichier, puis les
   restaure.

Le contournement a donc été une variable passée **en ligne sur une seule commande**. Rien écrit dans
le dépôt, rien dans le worktree, rien dans `~/.config/rpgquest/verygames.env`. Il n'y avait même pas
de ligne à retirer.

### Un risque réel trouvé APRÈS le déploiement, et corrigé avant de livrer

En vérifiant la configuration IA du panneau, j'ai lu `max_output_tokens = 4000`. Or une instruction
de plan en YAML coûte environ 30 jetons : mon prompt, qui invitait à « 40 à 200 instructions »,
pouvait produire une réponse **tronquée**. Et un plan coupé en plein milieu n'est pas un plan
partiel exploitable — il est inutilisable.

Le propriétaire l'aurait découvert sur son **premier essai**, sous la forme d'un « aucun plan
exploitable dans la réponse » incompréhensible. Corrigé (`71ff172`) : fourchette ramenée à 30-80
instructions, consigne explicite au modèle qu'un bâtiment sobre et **complet** vaut mieux qu'un
bâtiment détaillé et coupé, et l'arithmétique affichée à l'écran avec un lien vers la page des
fournisseurs pour relever la limite. **Je n'ai pas touché au réglage lui-même** : c'est la
configuration du propriétaire, modifiable depuis l'interface.

## Travail effectué

1. Diagnostic mémoire et processus ; arrêt du daemon Gradle périmé.
2. `control-panel/build.gradle.kts` : `maxParallelForks = 1` + borne de tas (`bf3e16a`).
3. Fusion de `feature/197-npc-identity-appearance` dans la ligne d'intégration (`67708eb`).
4. Fusion de `feature/94-brief-et-generation-ia` (`14f9911`), trois conflits de documentation
   résolus en conservant les deux côtés.
5. Compilation des quatre tâches (main + test, deux modules) sur la fusion : sans erreur.
6. **`./gradlew build` complet** depuis le worktree propre, un seul worker, tas de test à 640 Mio,
   sous surveillance mémoire.
7. `:control-panel:installDist` puis déploiement du panneau, et **vérification de ce qui est servi**.
8. Déploiement du JAR avec le contournement TLS autorisé ; vérification de l'absence de toute trace.
9. Contrôle des joueurs connectés, redémarrage par RCON, vérification du retour en ligne.
10. **Vérification de bout en bout** de la chaîne : heartbeat, migration, et traversée du champ
    `aiBrief` dans le relevé réel.
11. Correction de la calibration du prompt (`71ff172`) et redéploiement du panneau.
12. Push des deux branches de lot et de la ligne d'intégration.

## Fichiers créés

* `docs/claude-reports/2026-10-11_2255_fusion-validation-et-deploiement-197-94.md` (ce rapport)

## Fichiers modifiés

* `control-panel/build.gradle.kts` — borne de la JVM de test
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/ai/BuildingPromptBuilder.java` — calibration
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/BuildingPlanPages.java` — budget de jetons affiché
* `docs/claude-reports/README.md`, `docs/current_state.md`, `docs/deployment/SERVER_CHANGELOG.md` —
  résolution de conflits (les deux entrées conservées)

## Base de données / migrations

**Migration V31 appliquée en production DEV**, et vérifiée non par les logs mais par son **effet
observable** : la clé `aiBrief` est présente dans le relevé `building.site.list` renvoyé par le
serveur. Sans la colonne, la façade n'aurait rien eu à sérialiser.

Les cinq emplacements remontent `aiBrief` **vide** — exactement le comportement voulu : la migration
ne recopie pas la description. Et les notes existantes sont **intactes** : 401, 488, 385, 490
caractères pour `buildsite_0006/0009/0010/0011`.

## Configuration / données

Aucun changement de configuration. Le fichier `~/.config/rpgquest/verygames.env` du propriétaire est
inchangé (vérifié : zéro occurrence de `insecure`). `~/.gradle/gradle.properties` non modifié.

### Fichiers du propriétaire — un point à consigner

Trois fichiers de contenu non suivis ont disparu du dépôt pendant la session :
`quests/tc265_ai_securiser_environs.yml`, `quests/test_remise.yml`, `quests/test_smelt_item.yml`.

Je l'ai remarqué en comparant l'état Git de fin à celui de début, et j'ai enquêté avant de conclure.
**Le journal d'audit de PlugAdmin est sans ambiguïté** : `actor=owner`,
`action=quests.content.delete`, `result=OK`, `details=DELETED`, aux horodatages 20:22:00, 20:24:37 et
20:24:58 UTC. Le propriétaire a confirmé : nettoyage volontaire depuis le panneau, pendant que je
travaillais.

Aucune de mes opérations ne peut les avoir supprimés — ni `checkout`, ni `merge`, ni `clean` (jamais
exécuté) ne touchent un fichier non suivi. Rien à restaurer. Les cinq autres fichiers non suivis et
`crystal_hunt.yml` sont intacts et dans aucun commit.

## Tests automatiques

### La suite complète, sur la fusion

`./gradlew build --max-workers=1` avec `RPGQUEST_TEST_MAX_HEAP=640m`, depuis
`/srv/rpgquest/worktree-nuit` au commit `14f9911`, arbre propre.

**`BUILD SUCCESSFUL in 44m 3s`, code de sortie 0.**

| Module | Classes | Tests | Échecs | Erreurs | Ignorés |
|---|---|---|---|---|---|
| Plugin | 218 | **2 296** | **0** | **0** | 37 |
| Panneau | 100 | **1 302** | **0** | **0** | 1 |
| **Total** | **318** | **3 598** | **0** | **0** | 38 |

Comptés sur les XML JUnit **postérieurs au début du run**, pas sur le résumé textuel — c'est la
leçon d'une session précédente où des rapports périmés m'avaient fait annoncer un faux résultat.

Mémoire pendant le run : jamais descendue sous 250 Mio disponibles (la surveillance armée à ce seuil
n'a jamais déclenché), et le **swap est resté à 723 Mio, inchangé du début à la fin** — donc aucun
nouvel échange provoqué par le build.

Après la calibration du prompt : `:control-panel:test --tests '*BuildingSitesPageTest*'` →
BUILD SUCCESSFUL.

### Ce qui reste non prouvé par les tests

Aucun test ne peut juger qu'un bâtiment généré est beau ou praticable, ni qu'un modèle de langue
respecte le contrat du prompt. Voir « Tests manuels ».

## Tests manuels à effectuer

### Vérifications que j'ai déjà faites — et ce qu'elles prouvent

| Vérification | Résultat |
|---|---|
| Routes réellement servies par le panneau | `/buildings/plan`, `/buildings/sites`, `/npcs` → **303 vers `/login`** (enregistrées ; un 404 aurait signalé un panneau périmé) |
| Classes neuves dans le JAR servi | `BuildingPlanPages`, `AiBuildingStudio`, `BuildingPromptBuilder`, `NpcAppearance` → présentes |
| Transfert du JAR | `DEPLOY_EXIT=0`, SHA-256 `e7c04ef9…`, 2 260 049 o |
| Backup pré-déploiement | `rpgquest-20261011T204728Z-predeploy.jar`, 2 219 397 o — **plus petit** que le nouveau, donc sens correct |
| Redémarrage | `RESTART_EXIT=0`, serveur de nouveau ONLINE |
| Plugin actif | `version RPGQuest` répond ; les commandes `rpgadmin` sont enregistrées |
| Agent reconnecté | heartbeat à 20:49:12 UTC, après le redémarrage |
| Migration V31 effective | clé `aiBrief` présente dans le relevé réel |
| Briefs non écrasés | les cinq emplacements ont `aiBrief` vide, notes intactes |
| Contournement TLS | absent du dépôt, du worktree, de l'historique et de la config du propriétaire |
| Calibration du prompt servie | « entre 30 et 80 instructions » présent dans le JAR déployé, l'ancien texte absent |

### Protocole — la Bibliothèque du Hub

Le fournisseur **OpenAI est configuré et actif** (clé présente, timeout 90 s,
`max_output_tokens = 4000`).

1. **Bâtiments → Emplacements** → bouton **« Emplacements »** pour rafraîchir.
2. Ouvrir `buildsite_0009`. Deux champs distincts : « Note d'administrateur » (488 caractères
   actuels) et « Brief architectural » (vide), chacun avec son compteur.
3. Un bandeau signale que la note est peut-être un brief, **sans l'avoir déplacée**.
4. Copier la note dans le **brief**, et l'étoffer bien au-delà de 500 caractères. **Attendu** : rien
   n'est coupé, le compteur suit.
5. Enregistrer, rafraîchir : le brief revient entier, replié avec sa taille.
6. **« Générer un bâtiment avec l'IA »** → la page montre les faits transmis (nom, monde,
   `784 / 67 / -693`, façade **ouest**) et le brief.
7. Emprise 19, hauteur 14 → **« Demander un plan »**.
8. Relire le plan : `front: WEST`, taille dans les bornes, instructions crédibles.
9. **« Générer le bâtiment »** (« Remplacer » décoché).
10. **Bâtiments → Bibliothèque** → rafraîchir : le bâtiment apparaît, **sans** « schematic absent ».
11. Depuis la fiche de l'emplacement : aperçu, puis **pose**.
12. **Aller voir en jeu** — le seul juge de ce qu'aucun test ne couvre : intérieur praticable, porte
    franchissable, étage accessible, toit correct, orientation juste.

En cas de refus du serveur, le message **nomme l'instruction fautive** : corriger dans le champ, ou
**« Demander un plan corrigé »** pour renvoyer le motif au modèle.

### Pour #197, dans la même session

13. Page **PNJ** → **« Citizens »** pour rafraîchir le relevé.
14. Chaque tuile de PNJ lié porte une pastille d'apparence ; le filtre « Apparence à configurer »
    liste ceux dont la source est inconnue.
15. Ouvrir le Guide : six sections, **un seul** champ « Nom du PNJ », et l'avertissement de refus
    **au-dessus** du formulaire tant que son apparence n'est pas définie.
16. Définir l'apparence, puis renommer, et **constater en jeu** que le visage est inchangé et que le
    nom affiché suit le catalogue.

## Résultat attendu

Le propriétaire peut, depuis PlugAdmin : écrire un brief d'architecte aussi long qu'il le faut sans
rien perdre, demander un plan à l'IA, le relire, générer un bâtiment réellement présent dans la
bibliothèque — et séparément, renommer un PNJ d'un seul geste avec un avertissement avant le clic
quand son apparence bloquerait l'opération.

## Reset / retour à l'état initial

* **Un brief** : vider le champ, enregistrer.
* **Un bâtiment généré** : supprimer `buildings/<id>.yml` et `schematics/<id>.schem`, rafraîchir la
  bibliothèque. Rien n'y fait référence tant qu'il n'est pas posé.
* **Un bâtiment posé** : restauration depuis la fiche (flux #234, inchangé).

## Déploiement VeryGames

### À transférer

Fait. JAR `e7c04ef9…` (2 260 049 o) transféré par FTPS, panneau déployé deux fois (la seconde pour
la calibration du prompt).

### Ne PAS transférer/altérer

Respecté : `data.db`, `config.yml`, `messages.yml`, `spawn.yml`, les mondes, Citizens et tout autre
plugin n'ont pas été touchés — le script l'affiche explicitement et n'effectue aucune synchro de
dossier.

### Redémarrage requis

Fait, par RCON, après avoir vérifié **deux fois** qu'aucun joueur n'était connecté (condition posée
par le propriétaire : redémarrage autorisé si seul `LoDyMcFly` est présent, refusé si un autre
joueur l'est — il y avait 0 joueur).

### Migration automatique

V31, appliquée et vérifiée par son effet observable.

## Rollback

* **Plugin** : `scripts/rollback-verygames.sh --latest` restaure
  `rpgquest-20261011T204728Z-predeploy.jar`, puis redémarrer. **La colonne `ai_brief` reste** — une
  colonne en trop est inerte pour un plugin qui l'ignore.
* **Panneau** : `scripts/plugadmin/rollback.sh app` puis `systemctl restart plugadmin`.
* **Contenu** : sans objet, aucune ressource publiée par ce lot.

## Logs / diagnostic

* Build : `scratchpad/build-fusion.log`. Déploiement : `scratchpad/deploy-jar-fusion.log`.
* Génération : `[building] bâtiment généré : <id> (…)` dans la console serveur ; détail complet dans
  le résultat de l'action, page **Actions** du panneau.
* Appel IA : audit du panneau, action `building.plan.generate` — fournisseur, modèle, consommation,
  verdict. Jamais la clé, jamais le brief complet.

## Documentation mise à jour

Aucune nouvelle section : la documentation des deux lots avait été écrite dans leurs sessions
respectives et a survécu à la fusion intacte (vérifié). Ce rapport et son entrée d'index sont les
seuls ajouts.

## Limitations / travail restant

1. **La génération IA n'a jamais été exécutée contre le vrai fournisseur.** C'est désormais possible
   et c'est l'objet du protocole ci-dessus. Je m'attends à des refus du serveur sur les premiers
   plans — le bouton de reprise existe pour ça.
2. **`max_output_tokens = 4000` reste un plafond serré** pour un bâtiment très détaillé. J'ai
   calibré le prompt en conséquence et affiché l'arithmétique, mais la décision de relever la limite
   appartient au propriétaire.
3. **Le certificat FTPS VeryGames reste expiré** (`*.dg.vg`, Let's Encrypt, expiré le 2026-10-11 à
   05:12 UTC). Le contournement était ponctuel et n'a rien laissé : toute prochaine livraison de JAR
   nécessitera une nouvelle autorisation explicite. Le vrai correctif est côté VeryGames.
4. **#197 et #94 ne doivent pas être fermées.** Pour #197 : création guidée de l'apparence,
   propriétés visuelles des PNJ non joueurs, et constat visuel en jeu. Pour #94 : c'est un EPIC, et
   ce lot n'en livre que le premier prototype. #213 non plus : l'objet temporaire « Nouvel
   emplacement » reste dans l'inventaire après création.
5. **Pas d'aperçu image du skin** (#197) ni **du bâtiment** (#94) avant la pose : deux décisions
   documentées, pas des oublis.

## Prochaine étape suggérée

1. Dérouler le protocole de la Bibliothèque du Hub. L'étape 1 (le brief) se valide sans dépenser un
   seul jeton d'IA.
2. Selon le résultat, affiner le **prompt** — un fichier de texte, itérable sans toucher au moteur,
   qui est testé et déterministe.
3. Relever `max_output_tokens` si les plans reviennent trop sobres.
4. Signaler le certificat expiré à VeryGames.
5. Décider du sort de `kit_tier2` (constat de l'audit du 2026-10-11 : simultanément sur la liste de
   suppression et déclaré comme `unlock-quest` du palier 2 dans `config.yml`).
