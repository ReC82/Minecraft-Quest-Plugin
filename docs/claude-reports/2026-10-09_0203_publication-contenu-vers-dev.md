# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-09
* Heure : 02:03 (CEST)
* Sujet : #47 — publier/appliquer le contenu vers DEV depuis le Control Panel, sans rebuild ni redémarrage
* Statut : PARTIAL — fonctionnalité **livrée et vérifiée en production DEV** pour les trois familles fichier, mais la **build complète finale n'a pas pu s'exécuter** (interrompue pour manque de mémoire, voir §15). **#47 reste OUVERTE.**
* Branche Git : `feat/47-content-publish` (poussée, **jamais fusionnée**)
* Commit actuel si disponible : `9f8110c`
* Début de la tâche : 2026-10-09 00:23:31
* Fin de la tâche : 2026-10-09 02:03:10
* Durée totale : 01:39:39

---

## 1. Demande

Travailler **exclusivement** sur #47, en autonomie de nuit : audit, architecture, développement,
tests, commits, push, déploiement DEV, vérifications, documentation, rapport. **Ne rien fusionner.**

Objectif produit : PlugAdmin doit devenir autonome pour le cycle **créer/modifier → enregistrer
source → publier sur DEV → reload ciblé → vérifier le runtime → synchronisé**, sans build Gradle,
sans rebuild du JAR, sans intervention shell, sans transfert manuel, et sans redémarrage Minecraft
quand le moteur sait recharger.

Cas de référence imposé : **`rpgquest:tc265_ai_securiser_environs`**, avec la consigne explicite de
**ne pas modifier son contenu pour provoquer artificiellement un succès**.

Interdits de la nuit, respectés : aucun travail sur le chantier bâtiment après `db518fe`, aucune
hutte posée, `buildsite_0006` « Hutte » non modifié, aucune validation du sens de collage WorldEdit
à la place du propriétaire.

---

## 2. Audit des familles (Phase 0) — et il a réduit le périmètre

C'est le résultat le plus utile du lot. Le ticket énumère huit familles ; **deux** étaient réellement
bloquées.

| Famille | Source de vérité | Format | Source côté panel | Écriture serveur **avant** #47 | Reload existant | Verdict |
|---|---|---|---|---|---|---|
| **Quêtes** | fichier | YAML | `src/main/resources/quests/` | **aucune** | `QUESTS` | **vrai manque** → traité |
| **Stories** | fichier | YAML | `src/main/resources/stories/` | **aucune** | `STORIES` | **vrai manque** → traité |
| **Dialogues** | fichier | YAML | `src/main/resources/dialogues/` | partielle (`DialogueDefinitionStore`, éditeur guidé) | `DIALOGUES` | chemin « fichier » manquant → traité |
| **PNJ** | runtime + fichier serveur | YAML | — | **oui** (`NpcDefinitionStore`) | `NPCS` | **déjà appliqué au runtime** |
| **Mob spécial / Boss** | runtime + fichier serveur | YAML | — | **oui** (`SpecialMobDefinitionStore`) | `MOBS` | **déjà appliqué au runtime** |
| **Objets** | fichier serveur | YAML | — | non — seulement le **dépôt d'exemples embarqués** | `ITEMS` | **rien ne les crée depuis le panel** |
| **Recettes** | fichier serveur | YAML | — | non — idem | (via `ITEMS`) | **rien ne les crée depuis le panel** |

Deux constats qui ont décidé de l'architecture :

1. **`ContentWorkspace.KINDS` vaut exactement `{quests, stories, dialogues}`.** Ce n'est pas une
   coïncidence : ce sont les seules familles que le panel écrit comme fichiers. La liste blanche de
   publication et le manque réel **coïncident**.
2. **Les PNJ et les mobs s'appliquent déjà au runtime** par des stores côté serveur, pilotés par des
   actions agent. Les forcer à passer par une copie de fichier inventerait un **second chemin** pour
   un problème résolu. Et pour les objets/recettes, il n'y a **rien à publier** : il manque un
   *éditeur*, pas un transfert.

**Ce que je n'ai donc pas fait, et pourquoi** : les phases 6 (NPC/Citizens) et 7 (mobs/boss) du
prompt sont sans objet en l'état, et items/recettes ne peuvent pas être traités sans d'abord écrire
l'éditeur qui leur manque. Cette décision mérite votre validation — voir « Décision à confirmer ».

### Capacités existantes réutilisées, et non réécrites

- `ContentReloadService` savait déjà recharger **six familles séparément**, avec un garde-fou
  `BUSY` et une empreinte de runtime. J'ai seulement ajouté `loadedIds(family)` et
  `runtimeHas(family, id)` — la primitive qui manquait pour *vérifier*.
- `ContentWorkspace` savait déjà lire/écrire/sauvegarder la source avec SHA-256 **et verrou
  optimiste** (`expectedSha`). La moitié « source » du problème était donc déjà résolue.

---

## 3. Architecture

### La contrainte qui a tout décidé : l'agent est **sortant**

Le plugin **interroge** le panel ; le panel ne peut donc pas pousser un fichier vers le serveur de
jeu. Deux options :

| Option | Verdict |
|---|---|
| FTP depuis le panel | demanderait des identifiants FTP **côté panel**, donc un secret de plus et une surface d'attaque de plus |
| **le YAML voyage dans un paramètre d'action** | **retenu** |

Le choix n'est pas seulement plus simple, il est **plus sûr** : aucune connexion entrante vers le
serveur de jeu, **aucun identifiant FTP côté panel**, et le serveur écrit lui-même dans un dossier
qu'il résout seul. La borne de 1 Mio du corps de réponse de l'agent rend un YAML de quelques
kilo-octets trivialement transportable.

### Le modèle source / DEV / runtime

Trois faits distincts, et les confondre était le défaut de fond :

| Fait | Qui le connaît | Comment |
|---|---|---|
| **source** | le panel | `ContentWorkspace` + SHA-256 |
| **fichier DEV** | le serveur | `ContentPublishStore` + SHA-256 (même fonction, UTF-8 fixé des deux côtés) |
| **chargé par le moteur** | le serveur | `ContentReloadService#loadedIds` |

Un fichier peut être présent **sans** être chargé. C'est l'état `NOT_LOADED`, que l'ancien modèle ne
savait pas exprimer.

### Le socle

| Classe | Rôle |
|---|---|
| `content.publish.PublishKind` | **la liste blanche** — trois familles, et rien d'autre |
| `content.publish.ContentPublishStore` | résolution de chemin, empreintes, sauvegarde, écriture atomique |
| `content.publish.ContentApplier` | frontière vers le moteur de rechargement |
| `content.publish.ReloadServiceApplier` | l'implémentation réelle, sans aucune logique |
| `content.publish.ContentPublishService` | l'orchestration, et la seule à accorder un succès |
| `panel.publish.PublishState` | le vocabulaire d'état, fonction **pure** |
| `panel.publish.DevContentIndex` | l'état DEV tel que le panel le lit |

`ContentApplier` est une frontière **délibérée** : le rechargement réel exige tous les moteurs du
plugin, donc Bukkit. Derrière l'interface, l'ordre des opérations, la détection de conflit, la
sauvegarde avant écriture et les refus s'exécutent dans des **tests ordinaires**. C'est ce qui a
permis de vérifier 31 cas, dont ceux où l'on échoue volontairement à la dernière étape.

---

## 4. Sécurité

### Le navigateur n'envoie **ni chemin ni contenu**

Quatre champs seulement : `kind`, `id`, `expected_source_sha`, `expected_dev_sha` (+ CSRF +
confirmation). C'est le **panel** qui lit la source et joint le YAML, côté serveur, au moment
d'enfiler l'action.

Conséquence : un formulaire forgé ne peut **ni** désigner un fichier, **ni** publier un contenu
qu'il aurait fabriqué. Un test le vérifie sur le HTML réellement produit — y compris l'absence du
champ `yaml`.

### Trois verrous successifs sur la résolution de chemin

1. la famille vient d'une **énumération** ;
2. l'identifiant doit matcher `[a-z0-9][a-z0-9_-]{0,63}` — **aucun séparateur** ;
3. le chemin obtenu doit **rester confiné** dans le dossier de sa famille, après normalisation.

Le troisième ne devrait jamais servir, et c'est exactement pour cela qu'il est là.

### Ce qui est hors d'atteinte **par construction**

`data.db`, les mondes, les secrets, les données Citizens brutes, le JAR du plugin, toute
configuration inconnue : ils n'ont simplement **aucune entrée** dans `PublishKind`. Ce n'est pas une
liste d'interdits qu'on aurait pu oublier de compléter.

Vérifié par test contre : `../../etc/passwd`, `a/b`, `/absolu`, `data.db`, `..`, `.`,
`quests/../../data`, majuscules, espaces, 80 caractères — et côté famille contre `npcs`, `items`,
`mobs`, `worlds`, `config`, `../quests`.

Le **chemin de sauvegarde** transmis au retour arrière n'est jamais libre : il doit matcher la forme
exacte que le serveur fabrique, **et** le serveur revérifie qu'il est confiné à son dossier.

---

## 5. Gestion des conflits

| Cas | Réponse |
|---|---|
| DEV absent, attendu absent | **création** contrôlée |
| DEV identique à la source | **`UNCHANGED`** — rien n'est réécrit |
| DEV identique mais **non chargé** | rechargement **sans** réécriture (fichier déposé à la main, ou publication dont le reload avait échoué) |
| DEV ≠ ce que l'appelant avait vu | **`CONFLICT`** — rien n'est écrit, les deux empreintes sont rendues |
| source ≠ ce que l'appelant avait vu | refusé **côté panel**, avant d'enfiler l'action |

La protection joue donc **des deux côtés**. Celle sur la source est la moitié qu'on oublie : sans
elle, on publierait une version que l'administrateur n'a pas vue.

Le message distingue trois situations, parce qu'elles amènent à vérifier trois choses différentes :
« créée sur DEV depuis votre analyse », « a disparu depuis votre analyse », « a changé ».

---

## 6. Backups

Avant **tout remplacement** d'un fichier existant. Sous
`plugins/RPGQuest/content-backups/<horodatage>/<famille>/`, **hors** des dossiers de contenu :
déposées dans `quests/`, les sauvegardes seraient relues comme des définitions au prochain
rechargement et produiraient des **doublons d'identifiants** (règle déjà posée par #194).

**Si la sauvegarde échoue, rien n'est publié.** Publier sans pouvoir revenir en arrière n'est pas
acceptable.

Pour une ressource **nouvelle** : **aucun faux backup n'est fabriqué**, et le dossier n'est même pas
créé. Fabriquer un fichier vide donnerait l'illusion qu'un retour arrière est possible.

---

## 7. Rollback

| Situation | Code | Effet |
|---|---|---|
| une sauvegarde existe | `RESTORED` | la version précédente est reposée, puis rechargée |
| la ressource était **nouvelle** | `WITHDRAWN` | le fichier est **retiré**, puis la famille rechargée |
| sauvegarde introuvable | `BACKUP_MISSING` | **refusé**, rien n'est touché |
| le moteur garde la ressource après retrait | `RUNTIME_STILL_PRESENT` | signalé comme un échec |

On ne prétend **jamais** « restaurer » quand on supprime, ni l'inverse. Et après une restauration, la
zone de retour arrière **disparaît** : il n'y a plus rien à défaire, et un bouton qui ne fait rien
est un bouton qui ment sur ce qu'il peut faire.

---

## 8. Permissions

| Permission | Qui l'a | Portée |
|---|---|---|
| `CONTENT_READ` | large | voir l'état |
| **`CONTENT_PUBLISH`** | Propriétaire, Administrateur | écrire sur DEV |
| **`CONTENT_ROLLBACK`** | Propriétaire, Administrateur | réécrire l'état de DEV |

L'**Éditeur de contenu ne les a pas**, et c'était explicitement demandé : enregistrer dans la source
est réversible et sans effet sur le jeu ; changer ce qui tourne sur le serveur de test ne l'est pas
de la même façon. La documentation du rôle disait encore « aucune permission de déploiement n'existe
encore » — corrigée.

La confirmation (`confirm=true`) est exigée **deux fois** : par le catalogue du panel, puis par
l'agent. La dernière barrière est du côté qui écrit.

---

## 9. UX

**Enregistrer ≠ publier**, et l'écran le montre. Chaque fiche de quête, story et dialogue porte une
section **« Publication sur DEV »** avec l'état, les deux empreintes, l'indication « chargée par le
moteur », l'heure de la dernière publication, et l'action qui convient.

| État | Libellé | Action proposée |
|---|---|---|
| `SYNCED` | Synchronisé | — |
| `SOURCE_ONLY` | Source uniquement | Publier sur DEV |
| `DIFFERENT` | Différent | Republier |
| `NOT_LOADED` | Publié, non chargé | Republier |
| `CONFLICT` | Conflit | Voir les différences |
| `RUNTIME_ONLY` | Hors source | — |
| `UNKNOWN` | État inconnu | Analyser |

Chaque état porte **son code stable, son libellé, son explication et son action** : un état sans
action laisse l'utilisateur devant un badge, sans savoir quoi faire.

Sans relevé DEV, l'état est `UNKNOWN` et **aucun bouton Publier n'apparaît** — avec un bandeau qui
explique pourquoi et un bouton « État DEV ». Toutes les actions reviennent **sur leur page métier**
(non-régression #227, vérifiée en vrai : `303 → /quests`).

---

## 10. Quêtes — le cas de référence, publié pour de vrai

**`rpgquest:tc265_ai_securiser_environs`**, depuis le **vrai panel** et le **vrai formulaire**, sans
build et sans redémarrage. Son contenu **n'a pas été modifié**.

| Preuve | Valeur |
|---|---|
| état avant | **Source uniquement** (empreinte DEV attendue : vide) |
| champs du formulaire réellement rendu | `kind`, `id`, `expected_source_sha`, `expected_dev_sha`, `confirm`, `_csrf`, `agent`, `return`, `type` — **et pas de `yaml`** |
| code du résultat | **`PUBLISHED`**, `created = true` |
| empreintes | `devShaBefore = ""` → `devShaAfter = 83b666df69390d79…` **== `sourceSha`** |
| sauvegarde | `""` — ressource nouvelle, **aucun faux backup** |
| rechargement | `APPLIED`, **18 quêtes chargées, 0 anomalie** |
| **vérification runtime** | **`runtimeConfirmed = true`** à `2026-10-08T23:48:14.996Z` |
| contrôle indépendant 1 (FTP) | fichier sur DEV, **617 o**, SHA-256 **identique** à la source |
| contrôle indépendant 2 (moteur) | `rpgquest:tc265_ai_securiser_environs` figure dans les **18** identifiants réellement chargés |
| badge affiché | **Synchronisé**, « le moteur la voit en jeu » |
| retour | `303 → /quests` |

### Cycle complet sur une ressource de test dédiée

`test_publish_47`, créée pour l'occasion puis **entièrement nettoyée** :

| Étape | Résultat réel |
|---|---|
| V1 publiée | `PUBLISHED`, `runtimeConfirmed = true` |
| source modifiée | état → **Différent** |
| republiée | `PUBLISHED`, `created = false`, `980619cb…` → `2d9754e9…`, **sauvegarde prise** |
| restaurée | **`RESTORED`**, `2d9754e9…` → `980619cb…` |
| état après restauration | **Différent** (DEV porte volontairement l'ancienne version) |
| retirée | **`WITHDRAWN`**, « n'est plus chargée », `runtimeConfirmed = false` |

**Nettoyage** : fichier source supprimé, fichier DEV retiré par le chemin « retrait » prévu, compte
PlugAdmin jetable supprimé, secrets de test effacés. Vérification finale : **18 quêtes chargées,
`tc265` présente, `test_publish_47` absente du disque et du runtime**. Les fichiers de sauvegarde de
la ressource de test subsistent sous `content-backups/` — hors des dossiers de contenu, donc jamais
relus, et ils constituent la trace d'audit.

### Un incident instructif, et pourquoi il rassure

La première version de la ressource de test déclarait `block: DIRT` là où le parser attend
`material:`. Résultat : **`RELOAD_FAILED`**, `runtimeConfirmed = false`, et un message disant que le
fichier *est* écrit.

Surtout : le rechargement **a refusé de s'appliquer**, donc les **18 quêtes déjà chargées — dont
`tc265` — sont restées actives**. Un contenu invalide publié par erreur échoue **bruyamment** sans
faire disparaître le contenu en place.

---

## 11. Dialogues

Même moteur, même liste blanche, section de publication sur la fiche, bouton « État DEV » sur
`/dialogues`.

**Point important sur la dette MiniYaml** : la publication copie le fichier **tel quel** et ne passe
**jamais** par `MiniYaml`. Elle ne peut donc pas normaliser un scalaire replié au passage. La dette
connue (`guard.yml`) appartient à l'**éditeur guidé**, qui réécrit le YAML — pas au transfert.
Publier un dialogue est donc sûr ; l'éditer depuis le panel puis le publier peut en revanche le
reformater, et c'est écrit dans la fiche d'aide.

**Non vérifié en vrai cette nuit** : aucune publication de dialogue n'a été effectuée sur le serveur.
Le chemin est le même que pour les quêtes et il est couvert par les tests, mais je ne le présente pas
comme constaté.

## 12. Stories

Identique : même moteur, section de publication, bouton « État DEV » sur `/stories`.
**Non vérifié en vrai** non plus, pour la même raison.

## 13. Autres familles

Non couvertes, et c'est une décision documentée — voir l'audit en section 2. Rien n'a été
« commencé à moitié » : il n'y a pas de code mort pour les PNJ, les mobs, les objets ou les recettes.

---

## 14. Tests ciblés

**97 tests ajoutés**, tous verts.

| Classe | Tests | Ce qu'elle verrouille |
|---|---|---|
| `ContentPublishServiceTest` | 31 | **l'ordre des appels prouvé** (le fichier existe avant le rechargement) ; une seule famille rechargée ; conflits dans les trois sens ; `UNCHANGED` sans réécriture ; **échecs volontaires** à chaque étape (sauvegarde, écriture, rechargement, runtime) ; verrou par ressource vérifié par **ré-entrance** ; deux ressources ne se bloquent pas ; identifiants forgés ; confinement ; les quatre cas de retour arrière |
| `PublishStateTest` | 19 | **toutes** les combinaisons, dont celles que l'ancien modèle affichait faussement « Synchronisé » ; conflit vs différent ; aucune affirmation sans relevé ; contrat de l'énumération (code, libellé, explication, action, libellés uniques) |
| `ContentPublishPageTest` | 17 | **formulaires réellement rendus** et soumis tels quels ; absence de `yaml` et de tout chemin dans le formulaire ; YAML injecté **côté serveur** ; source changée entre aperçu et envoi ; empreinte forgée ; identifiant forgé ; famille hors liste blanche ; CSRF ; double soumission ; **état et bouton après une restauration** |

**Non-régression** : suite `control-panel` complète **1102 tests, 0 échec** ; tests ciblés plugin
**154, 0 échec**. Aucun test existant assoupli.

### Deux défauts attrapés, dont un sur le serveur réel

1. **Message de conflit** (attrapé par les tests) : il choisissait sa branche d'après l'état
   **courant** de DEV et non d'après ce que l'appelant avait vu, donc « créée sur DEV depuis votre
   analyse » ne se déclenchait **jamais**.
2. **État après restauration** (attrapé **sur le serveur réel**, pendant la recette) : la référence
   « ce que nous avons mis sur DEV » ne regardait que les *publications*. Après une restauration,
   DEV ne portait ni la source ni la dernière publication, et l'écran annonçait **« Conflit »** —
   c'est-à-dire « un tiers a touché au fichier » — alors que c'était **nous**, à la demande de
   l'utilisateur. Corrigé, redéployé, **revérifié en vrai** (« Différent », et plus de bouton de
   restauration).

---

## 15. Build complet — **NON TERMINÉE**, et il faut le dire

La build complète finale (`./gradlew clean build`, worktree propre sur `9f8110c`) a été
**interrompue par le système pour manque de mémoire** avant d'avoir produit le moindre résultat de
test. `clean` avait déjà effacé les résultats précédents, donc **je n'ai aucun chiffre de build final
à rapporter**.

La machine dispose de 3,8 Gio et il en restait environ 1,1 Gio disponible : c'est la limite connue de
cet environnement. Je n'ai **pas** relancé la build de ma propre initiative.

### Ce qui a réellement été exécuté, et à quel commit

| Suite | Résultat | Commit |
|---|---|---|
| `control-panel` **complète** | **1102 tests, 0 échec** | avant `74aa340` (donc sans le correctif de restauration) |
| plugin, **ciblée** (`ContentPublish*`, `ContentReload*`, `AgentActionExecutor*`, `ReloadFamily*`) | **154 tests, 0 échec** | avant `74aa340` |
| `ContentPublishServiceTest` | 31 tests, 0 échec | — |
| `PublishStateTest` | 19 tests, 0 échec | — |
| `ContentPublishPageTest` | **17 tests, 0 échec** | **après** `74aa340` (couvre le correctif) |

Autrement dit : chaque test de ce lot a été exécuté et passe, et le correctif de `74aa340` est
couvert par ses trois tests — mais **aucune exécution complète des trois modules n'existe au commit
final**. Le risque résiduel est faible (le correctif est panel-only et ses tests passent) mais il
n'est pas nul, et je ne le présente pas comme vérifié.

**À faire quand vous voudrez** : `./gradlew clean build` depuis un worktree propre sur `9f8110c`,
idéalement sans autre charge sur la machine.

## 16. Commits

| Commit | Objet |
|---|---|
| `1f3c242` | moteur de publication côté plugin + actions agent + 31 tests |
| `983f00d` | panel : `PublishState`, `DevContentIndex`, permissions, sections de publication + 33 tests |
| `74aa340` | correctif « Différent et non Conflit » après restauration + 3 tests |
| `9f8110c` | documentation : Bible, `current_state`, changelog, ROADMAP, fiche d'aide, TC-271 |

**Rien n'a été fusionné.** Les 8 fichiers de contenu non suivis du propriétaire sont intacts.

## 17. Déploiement

Effectué sur le **DEV** de 01:45 à 02:00, depuis un worktree propre, branche vérifiée **superset** de
la tête déployée `db518fe`.

| Étape | Résultat |
|---|---|
| `data.db` sauvegardé **et relu** | V29, `integrity_check = ok` |
| Control Panel | `/health` → `ONLINE` ; `panel/publish/*` et les **trois** actions dans le JAR servi. ⚠️ premier essai `PANEL_DEPLOY_EXIT=1` = **faux négatif connu** (sonde avant liaison du port), vérifié ensuite ; le second déploiement a renvoyé 0 |
| JAR plugin | `DEPLOY_EXIT=0`, **2 144 737 o == local** (SHA-256 `d98a8e48…`), backup `rpgquest-20261008T234533Z-predeploy.jar` (2 114 011 o) **sans écraser le plus ancien** |
| Redémarrage | **un seul**, **0 joueur** |
| Migration | **aucune** |

> **Le redémarrage installe le moteur ; il ne fait pas partie du workflow utilisateur.** C'est tout
> l'intérêt du lot : ensuite, publier du contenu n'en demande plus.

## 18. Preuves réelles DEV

Voir section 10 : empreintes, codes, et **deux vérifications indépendantes du panel** (téléchargement
FTP du fichier publié, et liste des identifiants réellement chargés par le moteur).

## 19. TC manuel pour demain

**TC-271** (~6 min) : état de départ, publication, **preuve en jeu**, modification → « Différent » →
republication, restauration, et les refus (publication concurrente, Éditeur de contenu sans bouton).
Il insiste sur ce qu'aucun test automatisé ne peut établir : le parcours **à l'œil** et le confort
**mobile**.

---

## 20. Limitations / travail restant

- **Pas de page « Changements en attente »** (sélection multiple). La phase 5 du prompt était
  conditionnée à « si le socle s'y prête » ; j'ai préféré rendre les trois familles fichier
  réellement solides et vérifiées en vrai plutôt qu'ajouter un écran de plus.
- **Pas de diff ligne à ligne** dans la fiche : seules les empreintes sont affichées. « Voir les
  différences » reste donc une invitation à comparer, pas un affichage.
- **L'état est aussi frais que le dernier relevé terminé.** La page ne s'auto-rafraîchit pas ;
  pendant la recette, un relevé trop rapproché affichait encore l'état précédent. Ce n'est pas faux
  (l'état est « d'après le dernier relevé »), mais c'est un inconfort réel.
- **Dialogues et stories : non publiés en vrai cette nuit.** Le chemin est identique à celui des
  quêtes et il est couvert par les tests, mais je ne le présente pas comme constaté.
- **Familles non couvertes** : PNJ, mobs/boss (déjà appliqués au runtime), objets/recettes (aucune
  source côté panel). Voir l'audit.
- **Mobile non vérifié** : les sections sont en `<dl>` et en boutons, sans grande table horizontale,
  mais personne ne l'a regardé sur un téléphone.
- **La build complète finale n'a pas abouti** (manque de mémoire). Voir §15 : tous les tests du lot
  ont été exécutés et passent, mais pas en une seule exécution au commit final.
- **#47 reste OUVERTE.**

### Décision à vous demander

L'audit conclut que **PNJ, mobs/boss, objets et recettes ne doivent pas passer par ce moteur de
publication de fichiers** — les premiers parce qu'ils s'appliquent déjà au runtime, les seconds parce
qu'il leur manque un éditeur. C'est une lecture du périmètre de #47, pas une omission. **Si vous
voulez tout de même une publication « fichier » pour ces familles, dites-le** : ce serait un lot à
part, et je ne l'ai pas commencé.

## 21. Prochaine étape suggérée

1. **TC-271** (~6 min, navigateur + un passage sur téléphone).
2. **Page « Changements en attente »** avec sélection explicite — c'est ce qui manque le plus pour
   travailler à plusieurs ressources.
3. **Diff ligne à ligne** dans la fiche.
4. **L'éditeur d'objets et de recettes** qui manque réellement — et non leur publication.
