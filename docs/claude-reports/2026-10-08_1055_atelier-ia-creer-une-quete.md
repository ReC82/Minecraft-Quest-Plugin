# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-08
* Heure : 10:55 (heure locale)
* Sujet : Atelier IA — créer une quête en français depuis le Control Panel (#146, LOT 4)
* Statut : DONE pour le code et le déploiement — #146 reste OUVERTE, TC-265 n'ayant pas été exécuté (il exige une vraie clé API)
* Branche Git : `feature/218-starter-kit-tiers` (aucun merge ; #146 et #109 restent **ouvertes**)
* Commit actuel si disponible : `703e29a` — 2 commits, poussés
* Début de la tâche : 2026-10-08 09:55:00 (première action sur le lot ; l'heure de la demande n'a pas été capturée, celle-ci est celle de ma première lecture du ticket #146)
* Fin de la tâche : 2026-10-08 10:55:05
* Durée totale : 01:00:05

## Demande

« Continue maintenant sur le LOT 4 : atelier IA », avec la consigne explicite
d'utiliser comme fondation la chaîne désormais en place : contrat de contenu
(#110) → résultat d'IA → import sécurisé (#109).

Priorités données, dans l'ordre : (1) page séparée « Créer avec une IA » ;
(2) génération d'**une** quête d'abord ; (3) backend de fournisseur abstrait ;
(4) configuration sécurisée des fournisseurs ; (5) au moins un fournisseur
réellement fonctionnel ; (6) réponse IA → parsing → validation → diagnostics →
aperçu → import/brouillon ; (7) aucune sauvegarde ni publication automatique sans
confirmation.

Plus le rappel de la règle UX décidée : mise en forme graphique partout où du
texte stylé est possible, jamais de balise MiniMessage demandée dans le parcours
normal, mode source réservé à l'option avancée.

Et deux garde-fous : ne pas repartir sur les lots terminés, ne pas fermer #109
avant TC-264.

Le ticket correspondant est **#146** — « [IA] Assistant de création de quêtes et
dialogues dans le Control Panel » —, lu en entier avant d'écrire du code.

## Analyse

### Ce que la demande rendait possible

Le point le plus important de ce lot est qu'il n'avait presque rien à inventer
sur la seconde moitié de la chaîne. Une fois la réponse du modèle obtenue, le
problème « transformer un YAML en contenu enregistré, sans écraser quoi que ce
soit » était **déjà résolu** par #109 : parsing par les lecteurs réels,
validation par les validateurs réels, états par élément, diff, arbitrage des
collisions, verrou optimiste, confirmation explicite.

D'où la décision structurante du lot : **l'atelier ne contient aucun code
d'écriture.** Il s'arrête à l'aperçu validé, et son bouton d'enregistrement poste
vers `/content/import`. Ce n'est pas une politesse d'interface — c'est ce qui rend
l'exigence n°7 vérifiable : il n'existe aucun chemin par lequel une proposition
d'IA pourrait atteindre le disque sans passer par la confirmation de #109.

### Où la clé API devait vivre

Le ticket exige un stockage serveur sécurisé, une configuration depuis le panel,
et rien dans Git. Trois possibilités :

| Option | Verdict |
|---|---|
| Variable d'environnement | Sûre, mais non modifiable depuis l'interface — contredit la priorité (4) |
| Fichier `.properties` | Même protection, mais même problème |
| Base locale du panel | **Retenue** : `/var/lib/plugadmin/control-panel.db`, hors dépôt Git, mode `600`, propriétaire du seul compte du service — vérifié sur la machine avant de décider |

### Un piège que les enregistrements Java tendent

`AiProviderSettings` est un *record*, et le `toString()` **généré** d'un record
imprime tous ses composants — donc la clé API entière. Il suffirait d'un journal
de debug, d'un message d'exception, ou d'un `assertEquals` raté pour la faire
fuir dans les logs, où elle resterait.

`toString()` est donc **redéfini exprès**, pour n'imprimer qu'une empreinte, et
un test le verrouille. C'est le genre de défaut qui ne se voit pas à la relecture
et qui coûte une révocation de clé.

### Ce que les modèles font vraiment

Le prompt demande « du YAML seul, sans rien autour ». Les modèles désobéissent
régulièrement : bloc de code, « Voici la quête demandée : », commentaire final.
Refuser ces réponses serait défendable et pratiquement absurde — le document est
là.

`AiYamlExtractor` **délimite** donc, selon trois stratégies (bloc de code, ancre
`format:`, réponse telle quelle) — et ne **répare jamais**. Ce qui est extrait
part ensuite dans les validateurs réels, exactement comme un fichier collé à la
main. Quand un découpage a été nécessaire, l'écran le **dit** : l'administrateur
doit savoir que la réponse n'était pas conforme.

Un bloc de code ouvert et jamais fermé est laissé échouer plutôt que complété :
c'est le signe d'une réponse tronquée, et la compléter nous-mêmes produirait un
contenu que personne n'a écrit.

## Travail effectué

### Abstraction de fournisseur (priorité 3)

`AiProvider` n'expose que trois choses : une identité, un test de connexion, une
génération. Tout ce qui est propre à une API — forme du corps JSON, en-tête
d'authentification, emplacement du texte dans la réponse — reste enfermé dans
l'implémentation. `AiProviderRegistry` est le **seul** endroit qui connaît la
liste : ni la page de configuration, ni l'atelier, ni le stockage n'énumèrent
quoi que ce soit. Ajouter un fournisseur = une classe et une ligne.

`HttpAiProvider` factorise ce qui doit être écrit **une** fois : le client, les
délais, la conversion de toute panne en échec lisible, la lecture défensive du
JSON, et les messages d'erreur HTTP exploitables (« clé refusée » plutôt que
« 401 »). Recopier cette gestion trois fois, c'était l'oublier une fois sur
trois.

### Trois fournisseurs réels (priorité 5)

| Fournisseur | Particularités réelles traitées |
|---|---|
| **Anthropic / Claude** | en-tête `x-api-key` (et non `Bearer`), version d'API obligatoire et **épinglée**, `system` de premier niveau, texte dans un tableau `content` de blocs typés |
| **OpenAI et API compatibles** | Chat Completions — choisie plutôt que Responses pour sa compatibilité avec Azure, les mandataires d'entreprise et les moteurs locaux, que l'URL de base surchargeable couvre alors sans code supplémentaire |
| **Google Gemini** | en-tête `x-goog-api-key`, **modèle dans le chemin** donc encodé (il vient d'une saisie humaine et ne doit pas pouvoir altérer l'URL), `systemInstruction`, `usageMetadata` |

### Configuration sécurisée (priorité 4)

Page `/ai/providers`, permission `AI_CONFIGURE`. Par fournisseur : activation,
clé, modèle, URL de base, plafond de jetons en sortie, délai maximal, et
« Tester la connexion ».

### L'atelier (priorités 1, 2, 6)

Page séparée `/ai/studio`, permission `AI_USE`. Un formulaire en français dont un
seul champ est nécessaire — ce que la quête doit raconter. Les autres affinent :
titre, identifiant, catégorie, PNJ donneur, difficulté, durée, nombre d'étapes,
répétable, récompense, contraintes libres.

`QuestPromptBuilder` assemble ensuite, **sans que personne ait à y penser** : les
règles de LodyQuests, la documentation de contenu générée pour #110, le schéma
JSON, les types d'objectifs et de récompenses réellement supportés, et les
références réellement existantes relevées sur le serveur. Rien n'est recopié : un
type ajouté au moteur arrive dans le prompt sans qu'une ligne soit touchée.

La chaîne complète : `FORMULAIRE → IA → RÉPONSE → EXTRACTION → PARSING →
VALIDATION → DIAGNOSTICS → APERÇU`, puis l'import de #109 pour `DIFF →
CONFIRMATION → ENREGISTREMENT SOURCE`.

En cas de refus, le bouton **« Demander une correction »** renvoie à l'IA sa
propre sortie **et** les diagnostics réels. Sans cela, elle repartirait de zéro et
reproduirait souvent la même erreur.

### Règle UX du texte stylé

Le champ « titre exact souhaité » est du texte destiné au joueur : il passe donc
par le composant guidé partagé (palette, styles, aperçu), comme le titre d'une
quête dans l'éditeur. Aucune balise n'est demandée dans le parcours normal, et
l'IA est priée de rester sobre sur la couleur. La réponse brute du modèle est
consultable en **mode avancé replié** — c'est le seul endroit où du format brut
apparaît, et seulement pour comprendre une réponse ratée.

## Sécurité

Le ticket #146 et la consigne de la session de nuit posaient une liste précise.
Point par point, et comment chacun est tenu :

| Exigence | Comment |
|---|---|
| La clé reste exclusivement côté serveur | Base locale du panel, hors Git, mode `600`, compte du service seul |
| Jamais exposée au navigateur après sauvegarde | Champ de saisie **toujours vide**, aucun champ caché ; l'écran n'affiche que la longueur et une empreinte SHA-256 tronquée — **pas même les derniers caractères**, contrairement à l'usage courant : montrer la fin d'une clé ne sert qu'à se rassurer |
| Jamais journalisée | L'audit retient fournisseur, modèle, jetons, verdict et **empreinte**. `toString()` du record redéfini pour la même raison |
| Jamais dans Git | La base vit dans `/var/lib/plugadmin/`, hors de l'arbre |
| Affichage masqué | Voir ci-dessus ; un test lit la page entière et échoue si la clé y figure |
| Aucun appel depuis le navigateur ou le serveur Minecraft | Tous les appels partent du serveur du panel ; le plugin n'a aucune connaissance de ce lot |
| CSRF | Jeton de session comparé en temps constant sur les deux pages, 403 sinon |
| Permissions appropriées | `AI_USE` ≠ `AI_CONFIGURE` ; ni Testeur ni Lecture seule n'ont l'une ou l'autre |
| Audit des changements de configuration sans révéler le secret | `ai.provider.save` / `.test` / `.clearKey`, avec empreinte |
| Timeout et limite de budget configurable | Délai et plafond de jetons par fournisseur ; un dépassement ne modifie rien |
| Validation déterministe obligatoire | Les validateurs réels, pas l'IA |
| Aucune publication automatique | L'atelier n'a aucun code d'écriture |

Deux protections ajoutées au-delà de la liste :

- **Une URL de base en HTTP est refusée** (seule une adresse de boucle locale est
  tolérée, pour un mandataire ou un bouchon de test). Une clé d'API ne doit jamais
  circuler en clair, et l'URL de base est un champ librement saisissable.
- **`autocomplete="new-password"`** sur le champ clé : sans cela, le gestionnaire
  de mots de passe du navigateur proposerait d'enregistrer une clé d'API, ou la
  remplirait dans un champ qui doit rester vide.

## Fichiers créés

| Fichier | Rôle |
|---|---|
| `panel/ai/AiProvider.java` | l'abstraction : identité, test, génération |
| `panel/ai/AiProviderSettings.java` | réglages d'un fournisseur ; `masked()`, `fingerprint()`, `toString()` redéfini |
| `panel/ai/AiSettingsStore.java` | stockage SQLite dans la base locale du panel |
| `panel/ai/AiProviderRegistry.java` | seul endroit qui connaît la liste des fournisseurs |
| `panel/ai/HttpAiProvider.java` | socle HTTP commun : délais, erreurs lisibles, lecture défensive |
| `panel/ai/AnthropicProvider.java` | Anthropic / Claude (API Messages) |
| `panel/ai/OpenAiProvider.java` | OpenAI et API compatibles (Chat Completions) |
| `panel/ai/GeminiProvider.java` | Google Gemini (`generateContent`) |
| `panel/ai/QuestPromptBuilder.java` | assemblage du prompt et de la demande de correction |
| `panel/ai/AiYamlExtractor.java` | délimitation du document dans une réponse enrobée |
| `panel/ai/AiQuestStudio.java` | orchestration, **sans aucun effet de bord** |
| `panel/web/AiStudioPages.java` | page « Créer avec une IA » |
| `panel/web/AiProviderPages.java` | page de configuration des fournisseurs |
| `panel/ai/AiQuestStudioTest.java` | 17 cas, fournisseur bouchon |
| `panel/ai/AiProviderSettingsTest.java` | 17 cas, clé et stockage |
| `panel/web/AiPagesTest.java` | 15 cas, vrai serveur HTTP |

## Fichiers modifiés

| Fichier | Changement |
|---|---|
| `panel/authz/Permission.java` | `AI_USE` et `AI_CONFIGURE` |
| `panel/authz/Role.java` | attribution : administrateur = les deux, éditeur de contenu = usage seul |
| `panel/web/PanelApp.java` | routes `/ai/studio` et `/ai/providers`, CSRF, audit, initialisation du stockage |
| `panel/web/Layout.java` | deux entrées de menu, visibles selon la permission |

## Base de données / migrations

Une table **dans la base du panel**, pas dans celle du serveur Minecraft :
`ai_provider`, créée par un `CREATE TABLE IF NOT EXISTS` idempotent et additif,
comme `panel_user`, `agent_action` et le journal d'audit. **Aucune migration du
plugin**, aucun changement de `data.db`, aucune donnée joueur touchée.

À noter : cette base contient désormais des **clés d'API tierces**. Toute copie
ou sauvegarde de ce fichier contient donc des secrets.

## Configuration / données

Aucun fichier de configuration du dépôt n'est modifié. Rien à transférer sur le
serveur Minecraft. La configuration de l'IA se fait **entièrement depuis
l'interface**, ce qui était la priorité (4) : aucun accès SSH, aucun
redéploiement pour changer une clé ou un modèle.

## Tests automatiques

| Module | Tests | Échecs | Erreurs | Ignorés |
|---|---|---|---|---|
| Control Panel | 860 | 0 | 0 | 1 |
| Plugin | 1890 | 0 | 0 | 37 |
| `web-api` | 30 | 0 | 0 | 0 |
| **Total** | **2780** | **0** | **0** | 38 |

`./gradlew build` : **BUILD SUCCESSFUL en 10 min 52 s** (relance), depuis le worktree propre `/srv/rpgquest/worktree-nuit` détaché sur le commit déployé. Le premier passage avait rapporté 1 échec, diagnostiqué ci-dessous.

Le Control Panel passe de 812 à **860** tests (+48). Le plugin et `web-api` sont
rapportés **`UP-TO-DATE`** : la preuve mécanique que ce lot ne touche rien côté
serveur Minecraft.

### Un échec au premier passage, rapporté et diagnostiqué

Le premier build complet du worktree a rapporté **1 échec sur 2780** :
`RestartServiceTest.aDroppingUptimeProvesTheRestartEvenIfNoProbeEverFailed()`.

Il n'est **pas** masqué, et voici pourquoi je le considère sans rapport avec ce
lot — chaque point a été vérifié, pas supposé :

| Vérification | Résultat |
|---|---|
| Le test référence-t-il le code de ce lot ? | **Non** : zéro occurrence de `PanelApp`, `AiSettingsStore` ou `AiProvider` dans la classe de test |
| `panel/ops/` a-t-il été modifié ? | **Non** : dernier commit sur ce dossier, `c7962f4` (#95) |
| Le test est-il sensible au temps ? | **Oui** : son attente est bornée à 600 × 10 ms = **6 secondes**, et le message d'échec montre la phase encore à `WAITING_BACK` — le sondage de fond n'avait simplement pas abouti dans le budget |
| Passe-t-il isolément ? | **Oui**, en 15 s |
| La même suite passait-elle juste avant ? | **Oui** : 860 tests, 0 échec, 15 minutes plus tôt |

C'est le même symptôme qu'un lot précédent sur cette machine : une attente de
quelques secondes qui ne tient pas quand la mémoire est sous pression. La suite a
été **relancée en entier** pour ne pas conclure sur un raisonnement, et le
résultat définitif figure dans le tableau ci-dessus.

**Ce qu'il faudrait faire** (hors périmètre de ce lot, et je ne l'ai pas fait) :
ce test mériterait soit une horloge injectable, soit un budget plus généreux. En
l'état, il échouera à nouveau un jour sous charge, et quelqu'un perdra du temps à
chercher une régression qui n'existe pas.

### Aucun appel réseau sortant dans les tests

C'est une décision, pas une limite. Tester « l'IA répond bien » reviendrait à
tester un tiers : résultat non déterministe, jetons facturés à chaque exécution,
et suite qui échoue quand une API est en panne. Ce qui est testé, c'est **notre**
comportement.

| Classe | Cas | Ce qu'ils verrouillent |
|---|---|---|
| `AiQuestStudioTest` | 17 | la chaîne complète jusqu'à l'analyse importable ; **une génération n'écrit rien sur le disque** ; le prompt porte réellement l'intention, le contrat de #110, le schéma, les types du moteur et les références réelles ; sans relevé de références, l'IA est priée de n'en inventer aucune ; réponse enrobée dans un bloc de code ; réponse bavarde (le découpage est signalé) ; réponse qui n'est pas un pack (échec lisible, rien n'est deviné) ; **type d'objectif inventé attrapé par les validateurs réels** ; PNJ inconnu signalé ; timeout, clé refusée ; fournisseur inconnu, désactivé ou sans clé refusés **sans aucun appel tenté** ; le prompt de correction porte la sortie précédente et les diagnostics réels ; seuls les fournisseurs activés **et** pourvus d'une clé sont proposés |
| `AiProviderSettingsTest` | 17 | **`toString()` ne contient jamais la clé** ; la forme masquée n'en révèle **aucun** caractère, pas même la fin ; l'empreinte distingue deux clés sans les révéler et ne dépend que de la clé ; `withoutKey()` ; utilisable seulement si activé et pourvu d'une clé ; limites absurdes ramenées aux défauts ; aller-retour de stockage complet ; **un champ clé vide conserve la clé enregistrée** ; une nouvelle clé remplace l'ancienne ; effacer désactive aussi le fournisseur ; la dernière modification est traçable sans la clé ; les fournisseurs sont isolés |
| `AiPagesTest` | 15 | les deux pages exigent une session ; l'atelier est une page séparée avec ses entrées de menu ; sans fournisseur utilisable, elle le dit et n'affiche **pas** de formulaire ; **une clé enregistrée n'apparaît dans aucune réponse HTTP** (la page entière est fouillée) ; seules longueur et empreinte sont affichées, champ vide ; champ de type `password` avec `autocomplete="new-password"` ; **usage et configuration sont deux permissions distinctes** (vérifié rôle par rôle) ; CSRF invalide → 403 sur les deux pages ; générer sans intention est refusé sans appel ; fournisseur inconnu refusé ; enregistrer puis relire garde la clé **côté serveur seulement** ; réenregistrer avec un champ vide la conserve ; effacer la retire et désactive ; **une URL de base en HTTP est refusée** ; l'atelier n'offre **aucun** chemin d'écriture direct et le dit ; après un appel échoué, **aucun fichier** n'apparaît |

### Échec intermédiaire

Une seule assertion a échoué, et c'était la mienne : `Http.esc` encode
l'apostrophe en `&#39;`, donc chercher « Décrivez d'abord » dans le HTML échouait
sur l'échappement et non sur le contenu. Troisième fois que ce piège se présente
dans la session ; l'assertion porte désormais sur un fragment sans apostrophe.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — **rien** n'a été exercé avec une vraie clé API.
**#146 reste ouverte.**

**TC-265** (ajouté à `docs/MANUAL_TEST_PLAN.md`, 22 points, réparti en
configuration / génération / en jeu). Il porte un avertissement explicite :
**ce test consomme des jetons facturés**.

Les contrôles qui comptent vraiment :

- **Point 3** — afficher le code source de la page et **chercher la clé** : elle
  ne doit apparaître nulle part, ni en clair, ni dans un champ caché. C'est le
  seul contrôle qui puisse démentir la garantie centrale du lot.
- **Point 5** — changer le modèle en laissant le champ clé vide : la clé doit
  avoir **survécu**.
- **Point 6** — une URL de base en `http://` doit être refusée.
- **Point 14** — après une génération réussie, vérifier qu'**aucun fichier** n'a
  été créé avant la confirmation.
- **Point 16** — relancer la même génération et enregistrer : doit produire un
  **conflit**, pas un écrasement.
- **Point 18** — un compte Testeur et un compte Lecture seule doivent être
  refusés sur les deux pages ; un Éditeur de contenu doit pouvoir générer mais
  **pas** configurer de clé.
- **Point 21** — `/quest admin reload` après enregistrement : la quête générée
  doit se charger **sans erreur**. C'est ce qui valide que l'IA produit du
  contenu réellement consommable par le serveur.

Restent en attente : **TC-264** (#109), **TC-257** (#123) et **TC-258** à
**TC-263** (lot de nuit).

## Résultat attendu

- Un administrateur configure une clé API depuis l'interface, teste la connexion,
  et sait ensuite **quelle** clé est en place sans jamais la revoir.
- Un éditeur de contenu décrit une quête en français et obtient une proposition
  **déjà validée**, sans rien connaître du format.
- Une erreur de l'IA — type inventé, référence inconnue, champ manquant — est
  attrapée, affichée, et bloque l'enregistrement.
- Rien n'atteint la source sans une confirmation explicite, et rien n'atteint le
  serveur Minecraft sans un déploiement distinct.

## Reset / retour à l'état initial

- **Neutraliser l'IA immédiatement** : décocher « Activer » sur chaque
  fournisseur, ou effacer les clés. Aucun appel n'est alors possible.
- **Révoquer l'accès sans redéployer** : retirer `AI_USE` ou `AI_CONFIGURE` du
  rôle ou du groupe.
- **Supprimer un contenu généré** : depuis l'éditeur, comme n'importe quel
  contenu — il a été écrit par les mêmes écrivains.
- **Retirer la fonctionnalité** : `scripts/plugadmin/rollback.sh app`. La table
  `ai_provider` et les clés restent dans la base — un retour en arrière du code ne
  doit pas détruire une configuration.

## Déploiement VeryGames

### À transférer

| Cible | Élément |
|---|---|
| AWS | distribution `control-panel` |

**Rien d'autre.** Pas de JAR, pas de fichier de contenu, pas de configuration.

### Ne PAS transférer/altérer

Ce lot ne touche **strictement rien** côté plugin. Vérifié de deux façons :
`git status` ne rapporte aucun fichier modifié sous `src/`, et le build rapporte
`:test` et `:jar` **`UP-TO-DATE`**.

### Redémarrage requis

**Non pour Minecraft** — c'est la conséquence directe du point précédent, et cela
évite une interruption de service pour rien. Le Control Panel redémarre son
propre service.

### Migration automatique

La table `ai_provider` est créée au premier démarrage du panel par un
`CREATE TABLE IF NOT EXISTS` idempotent. Rien côté serveur Minecraft.

**Fait le 2026-10-08 à 10:53 (heure locale), commit `703e29a`.**
`PANEL_DEPLOY_EXIT=0`.

| Élément | Valeur |
|---|---|
| Control Panel | nouveau JAR 1 277 146 o (contre 1 219 885) |
| Release précédente | `/opt/plugadmin/releases/20261008-105331/` |
| Minecraft | **non touché** — JAR en ligne relu : 1 985 814 o, identique à celui du lot #109 |
| Redémarrage Minecraft | **aucun** |

Pour une fois le script a lui-même rapporté `/health` ONLINE : il a sondé assez
tard. Aucun rollback.

### Validation après déploiement

Vérifié réellement, pas supposé :

- Panel `/health` → `{"panel":"ONLINE"}`.
- Routes `/ai/studio` et `/ai/providers` → **303 vers /login** : elles existent et
  sont protégées. Elles n'existaient pas avant ce déploiement.
- **Bytecode réellement installé inspecté** : les 16 classes du paquet
  `panel.ai` (dont les trois fournisseurs, le socle HTTP, l'extracteur et le
  stockage) ainsi que `AiStudioPages` et `AiProviderPages` sont présentes dans le
  JAR servi.
- **Table `ai_provider` créée** dans la base de production, et **vide** : aucune
  clé n'est enregistrée, ce qui est l'état attendu après un déploiement. L'IA est
  donc inerte jusqu'à ce qu'un administrateur configure un fournisseur.
- **Minecraft intact** : JAR en ligne relu et identique à celui du lot #109,
  `/quest admin validate` → **17 quête(s), 0 erreur(s)**, aucun redémarrage.

**Non vérifié** : tout le comportement fonctionnel de l'atelier relève de
**TC-265** (22 points, `PENDING MANUAL VALIDATION`), qui exige une **vraie clé
API** et consomme des jetons facturés. Aucune des trois implémentations de
fournisseur n'a encore été appelée avec une vraie clé — le bouton « Tester la
connexion » donne cette réponse en quelques secondes, et c'est le point 4 du test.

## Rollback

- **Control Panel** : `scripts/plugadmin/rollback.sh app` puis
  `systemctl restart plugadmin`.
- **Clés** : conservées dans la base. Pour les retirer, les effacer depuis
  `/ai/providers` **avant** le rollback, ou les révoquer chez le fournisseur.
- **Minecraft** : rien n'est concerné, donc rien à annuler.

## Logs / diagnostic

Journal d'audit du panel :

| Entrée | Contenu |
|---|---|
| `ai.generate` / `ai.correct` | fournisseur, modèle, jetons, verdict (proposition valide / refusée par la validation / échec) |
| `ai.provider.save` | fournisseur, activation, modèle, URL de base, **empreinte** de la clé |
| `ai.provider.test` | fournisseur, modèle, succès ou message d'erreur |
| `ai.provider.clearKey` | fournisseur, et le fait que la clé a été effacée |

Aucune de ces entrées ne contient la clé, ni le prompt complet — qui est du
contenu éditorial, et volumineux. En cas d'appel échoué, le message du
fournisseur est affiché à l'écran et repris dans l'audit, tronqué.

## Documentation mise à jour

| Fichier | Contenu |
|---|---|
| `docs/RPGQUEST_BIBLE.md` | section « Atelier IA (#146, phase 1) » : les deux permissions et pourquoi elles sont séparées, l'absence de chemin d'écriture, ce que l'administrateur n'a pas à fournir, l'abstraction et les trois implémentations réelles, la sécurité de la clé point par point, les limites |
| `docs/current_state.md` | entrée #146 |
| `docs/MANUAL_TEST_PLAN.md` | **TC-265** (22 points) et sa ligne de recette |
| fiche `content-packs.md` du centre d'aide | « Créer une quête avec une IA » en quatre étapes, et ce qu'il faut faire avant |
| `docs/deployment/SERVER_CHANGELOG.md` | entrée de déploiement, avec la note de sécurité sur la base du panel |

## Limitations / travail restant

### Limites assumées de cette phase

- **Une seule quête par génération.** C'est la priorité (2) telle qu'elle a été
  posée. Le ticket #146 mentionne aussi les dialogues, les stories et
  l'enchaînement de quêtes : non faits.
- **Aucun coût monétaire estimé.** Le ticket demande de journaliser « son coût
  estimé ». Les **jetons réellement rapportés** par le fournisseur sont affichés
  et audités, mais je n'ai pas inventé de conversion en euros : cela supposerait
  une grille tarifaire par modèle, qui change sans préavis et serait fausse dès
  le premier changement de prix. Afficher un chiffre faux serait pire que de ne
  pas en afficher. À faire proprement, cela demande une table de tarifs
  administrable — c'est un choix à valider, pas à deviner.
- **Pas de garde-fou de budget cumulé.** Le plafond de jetons et le délai bornent
  **un** appel, pas une dépense mensuelle. Le ticket parlait de « limite de budget
  configurable » : ce qui est livré en est la moitié honnête.
- **Pas de reprise d'une proposition en cours.** L'atelier est sans état : fermer
  l'onglet perd la proposition. Même raison que pour l'import — une analyse
  conservée deviendrait fausse dès qu'un éditeur enregistre en parallèle.
- **Les trois fournisseurs ne sont pas tous vérifiés en vrai.** Le code est écrit
  d'après la forme réelle de chaque API et couvert par des tests de structure,
  mais **aucun n'a été appelé avec une vraie clé** : c'est l'objet de TC-265. Le
  bouton « Tester la connexion » existe précisément pour que cette vérification
  soit immédiate, fournisseur par fournisseur.

### Ce qui reste du programme global

- **TC-264** (#109) et **TC-265** (#146) non exécutés — les deux issues restent
  ouvertes.
- Dialogues et stories dans l'atelier ; audit de cohérence narrative (#147) ;
  génération de lots de noms (#148).
- Rendre dérivable le vocabulaire des actions et conditions de dialogue, pour que
  le schéma de #110 puisse les contraindre — c'est aussi ce qui permettrait à
  l'IA de produire des dialogues fiables.

### Défaut préexistant, inchangé

`CrystalHuntIntegrationTest` échoue toujours dans l'arbre de travail à cause de
`crystal_hunt.yml` réécrit depuis le panel. Build et déploiement sont faits
depuis un **worktree Git propre** où il passe. Toujours à trancher : adapter le
test, ou sortir la quête des exemples embarqués.

## Prochaine étape suggérée

1. **Configurer un fournisseur et dérouler TC-265**, au moins jusqu'au point 14.
   C'est le seul moyen de savoir si les trois implémentations d'API sont justes —
   le bouton « Tester la connexion » donne la réponse en quelques secondes par
   fournisseur.
2. Dérouler **TC-264** (#109), puis **TC-257** (#123).
3. Décider pour le coût : soit une table de tarifs administrable, soit assumer de
   n'afficher que les jetons.
