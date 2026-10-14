---
title: Créer et configurer un PNJ (Citizens + RPGQuest)
category: PNJ / Citizens
tags: [citizens, npc, pnj, tag, skin, rpgadmin, binding, dialogue, giver]
order: 1
---

# Créer et configurer un PNJ

## Le parcours simple dans PlugAdmin

Page **PNJ** → bouton **« Créer un PNJ »**. Trois choses à remplir :

1. **Nom** (48 caractères au plus — le compteur l'affiche pendant la saisie) ;
2. **Emplacement** : « À côté du Guide, au village » (automatique), ou « À une position précise »
   (monde + X / Y / Z — la touche F3 en jeu donne votre position) ;
3. **Apparence** : l'apparence par défaut, ou un lien MineSkin, ou un pseudo Minecraft.

Puis **« Créer le PNJ »**. PlugAdmin crée en une fois tout ce qu'il faut : le personnage dans le jeu,
sa fiche RPGQuest, son apparence, et **un dialogue à lui** qui dit « Salut ! » (aucune quête n'y est
proposée). L'identifiant est déduit du nom : « Lucien » donne `lucien` ; un deuxième « Lucien »
donne `lucien_2`. Un double clic ne crée jamais deux PNJ.

Si quelque chose échoue, la page affiche ce qui est fait, ce qui reste, et un bouton
**« Reprendre »** qui ne renvoie que l'étape manquante.

Ensuite, cliquez sur le PNJ dans la liste. Sa fiche montre son nom, son apparence, son emplacement,
et des boutons — un seul formulaire s'ouvre à la fois :

| Bouton | Ce qu'il fait |
|---|---|
| **Modifier le nom** | change le nom partout (fiche et jeu) |
| **Apparence** | lien MineSkin (voir le [catalogue MineSkin](https://mineskin.org/skins)) ou pseudo Minecraft |
| **Déplacer** | monde, X / Y / Z, direction du regard en mots ; « Sans changement » garde l'orientation |
| **Promenade** | rayon et amplitude verticale autour de sa position de départ ; quand elle tourne : « Arrêter la promenade et revenir au départ » |
| **Regarder les joueurs** | la case dit l'état voulu ; portée de 1 à 64 blocs |
| **Dialogue et quêtes** | « Modifier le dialogue », « Créer une quête qu'il propose » |
| **Supprimer** | page courte : ce qui part, ce qui reste, les quêtes à corriger d'abord |

**« Actualiser les PNJ »** relit en une fois la liste RPGQuest et les personnages du jeu. Le
bouton **« Diagnostic »** ouvre la page technique (compteurs, identifiants, messages du serveur).

Un personnage présent en jeu mais inconnu de RPGQuest (créé à la main avec Citizens) apparaît
« à rattacher » : **« Rattacher à RPGQuest »** lui crée sa fiche et la liaison, sans le recréer.
Ses dialogues et quêtes déjà écrits sont conservés ; son apparence n'est pas modifiée.

### Apparence par défaut

`https://minesk.in/f534ead9d5a3413e9bb51bce9b211995`. Elle est posée sur un nouveau PNJ si vous
n'en choisissez pas d'autre, et sur un ancien PNJ de type joueur **quand vous le renommez** si son
apparence n'avait jamais été enregistrée (avant, ce renommage était refusé). Les anciens PNJ ne
sont jamais modifiés tant qu'on ne les renomme pas, et les PNJ qui ne sont pas des joueurs
(villageois…) n'ont pas de skin.

### Peut-on changer l'identifiant d'un PNJ ?

Non. L'identifiant (`lucien`, visible dans « Informations avancées ») est ce que les quêtes et les
dialogues utilisent pour désigner le PNJ : le changer casserait ces références. Pour en changer
quand même :

1. créer un nouveau PNJ avec le bon nom (il reçoit un nouvel identifiant) ;
2. adapter les références à l'ancien identifiant : champ **« PNJ donneur »** des quêtes, objectifs
   **« Parler à un PNJ »** et **« Remettre des objets à un PNJ »**, dialogues qui ouvrent le sien,
   et son propre dialogue (`rpgquest:<identifiant>`) à recopier ;
3. supprimer l'ancien PNJ — la page de suppression liste ce qui le cite encore.

---

Les sections suivantes décrivent le fonctionnement interne et les commandes Citizens en jeu, pour
les cas que PlugAdmin ne couvre pas.

## 1. Créer le PNJ Citizens

En jeu, visé sur l'endroit où le placer :

```
/npc create Garde --type player
```

- `Garde` est le **nom affiché** (cosmétique, libre, accents et majuscules autorisés).
- `--type player` donne une apparence de joueur (skin possible). Sans option, c'est un villageois.
- La commande crée le PNJ **et le sélectionne** automatiquement (les commandes suivantes agissent
  dessus).

## 2. Sélectionner un PNJ existant

```
/npc select <id numérique>
```

Le PNJ le plus proche est aussi sélectionné en le regardant (selon la config Citizens). L'id
numérique Citizens (`#3`, `#4`…) est visible avec `/npc list` ou au survol.

## 3. Taguer côté RPGQuest — l'étape qui relie au contenu

Se placer à moins de 6 blocs, **regarder le PNJ**, puis :

| Commande | Effet |
|---|---|
| `/rpgadmin npc tag <id>` | Identifie le PNJ visé avec **l'id de votre choix** (voir règles ci-dessous). |
| `/rpgadmin npc tag` | Identifie avec un id **auto-généré** `npc_<n>` (séquentiel, jamais réutilisé). |
| `/rpgadmin npc untag` | Retire l'identifiant RPGQuest. Le PNJ Citizens n'est **pas** supprimé, seul le mapping l'est. |
| `/rpgadmin npc info` | Affiche l'id RPGQuest actuel du PNJ visé + son id Citizens entre parenthèses (`guard (Citizens NPC #3)`). |

Règles de l'id :

- minuscules, chiffres, `.` `_` `-` uniquement ;
- `tag` est **idempotent** : si le PNJ est déjà identifié, la commande ne change rien et rappelle
  l'id existant — il faut d'abord `untag` pour ré-identifier ;
- le mapping est persisté dans `data.db` (`npc_citizens_bindings`) et **survit aux redémarrages**
  (RPGQuest suit l'UUID interne Citizens, pas le nom ni l'id numérique).

### Nom affiché vs identifiant technique

Ce sont **deux choses différentes** :

| | Exemple | Rôle |
|---|---|---|
| Nom affiché | `Garde` | cosmétique, au-dessus de la tête, peut changer à tout moment |
| Identifiant RPGQuest | `guard` | **technique**, invariable, c'est lui que le contenu référence |

L'**identifiant canonique** attendu par un contenu donné est celui écrit dans le YAML
(`giver:`, objectif `TALK_TO_NPC` → `npc:`, convention de dialogue `rpgquest:<id>`). Il faut
taguer le PNJ **exactement** avec cet id.

> [!NOTE]
> **Deux PNJ peuvent porter le même nom affiché** (deux « Robert », `robert_writer` et
> `iron_specialist`) : rien n'est bloqué, mais la page **PNJ** les marque « nom en double », cite
> l'identifiant de l'autre, et la page **Diagnostic** liste les identifiants en conflit. À la
> création et au renommage, PlugAdmin prévient pendant la saisie si le nom est déjà porté. Voir
> [Nom affiché partagé par plusieurs PNJ](/docs/pnj-depannage#nom-affiche-partage-par-plusieurs-pnj).
> Deux PNJ pour le **même identifiant**, eux, restent une vraie erreur.

> [!WARNING]
> Un mauvais tag = contenu cassé silencieusement. Si le YAML attend `guard` et que le PNJ est
> tagué `garde` (ou `Garde`, refusé car majuscule), le dialogue ne s'ouvre pas et l'objectif
> `TALK_TO_NPC` ne valide jamais. Vérifier avec `/rpgadmin npc info` et la page **PNJ** du
> Control Panel avant de conclure « ça marche ».

## 3 bis. Renommer un PNJ (depuis PlugAdmin, chemin recommandé)

Fiche du PNJ → **« Modifier le nom »** → **« Enregistrer le nom »**. Un seul champ, un seul bouton.

Il n'y a **qu'un nom à gérer**. PlugAdmin met à jour en une opération la fiche RPGQuest *et* le nom
affiché au-dessus du PNJ en jeu, puis **relit les deux** et dit lequel a changé. Si une seule couche
a suivi, c'est annoncé comme un **échec** — pas comme un succès partiel — et relancer l'opération
est sans risque : elle reprend la couche en retard et laisse l'autre telle quelle.

Ce qui ne change **jamais** : l'**identifiant technique** (`guard`), l'identité Citizens (UUID,
numéro), et les liaisons quêtes / dialogues / stories.

Les **textes de dialogue ne sont pas réécrits**. Un locuteur (`speaker`) peut être partagé entre
plusieurs personnages : le renommage **liste** les dialogues qui nomment encore l'ancien nom, et
c'est à vous de décider lesquels corriger, depuis l'éditeur de dialogues.


## 4. Mettre un skin

### Depuis PlugAdmin (recommandé) : « Définir une apparence »

Fiche du PNJ → section **Apparence**. L'état y est affiché, et repris sur la tuile du catalogue :

| État | Ce que cela veut dire | Renommage possible ? |
|---|---|---|
| **configurée** | PNJ joueur, et RPGQuest connaît la source de son apparence | oui — elle est réappliquée à l'identique |
| **d'origine** | PNJ joueur, source **inconnue** de RPGQuest | oui — il prend l'apparence par défaut (#325) |
| **en cours** | apparence demandée, pas encore confirmée par un relevé Citizens | — |
| **erreur** | la dernière tentative a échoué ; le PNJ garde son apparence | — |
| **non applicable** | PNJ non joueur (villageois, zombie…), ou aucun PNJ Citizens lié | oui |

> [!NOTE]
> **« D'origine » ne veut pas dire « sans skin ».** RPGQuest ignore seulement d'où il vient. Depuis l'issue #325, renommer un tel PNJ
> lui pose l'apparence par défaut (au lieu de refuser) ; choisissez-lui une apparence avant si vous
> voulez en garder une autre.

Deux sources, et seulement deux : un **lien MineSkin** (`https://minesk.in/…`) ou un **pseudo
Minecraft**.

- **Lien MineSkin.** RPGQuest demande à MineSkin le skin exact de ce lien, le pose sur le personnage,
  puis relit ce qu'il porte. « Apparence posée et vérifiée » veut donc dire que le personnage porte
  bien cette image. Si MineSkin ne répond pas, ou ne connaît pas le lien, le message le dit et le
  personnage garde son apparence actuelle. L'apparence par défaut est intégrée au plugin : elle
  ne dépend pas de MineSkin.
- **Pseudo.** Citizens télécharge lui-même le skin de ce compte, quelques secondes plus tard.

Si la fiche indique **« Il ne porte pas son apparence en jeu »**, l'apparence enregistrée n'est pas
celle que porte le personnage : le bouton **Réessayer** la repose. Un lien qui ne commence pas par
`https://`, ou qui n'est pas un lien MineSkin, est refusé avec un message simple.

> [!NOTE]
> Pourquoi plus `/npc skin --url https://minesk.in/…` ? Avec cette commande, Citizens fabrique le
> skin en tâche de fond, sans pouvoir dire si cela a marché. Sur DEV, cela a marché pour Elias mais
> pas pour Lucien, qui a gardé le skin du compte Minecraft « Lucien » alors que l'action avait
> répondu « réussi ». Poser la texture exacte puis la relire supprime ce doute.

### En jeu, par commande Citizens

Le PNJ doit être `--type player`. Sélectionné, puis :

```
/npc skin <pseudo>
```

Applique le skin du compte Minecraft `<pseudo>` (le plus simple et le plus fiable). Le skin est
mémorisé par Citizens et persiste.

- `/npc skin -c <pseudo>` : idem mais force le rafraîchissement du cache de skin.
- Skin par **URL de texture** : `/npc skin -t <url>` — ne fonctionne qu'avec une vraie URL de
  texture `textures.minecraft.net` **et** selon la configuration/version de Citizens installée
  (Citizens `2.0.43` sur ce serveur). En cas de doute, préférer le skin par pseudo.

## 5. Déplacer / repositionner

- `/npc move` : déplace le PNJ sélectionné vers votre position (validez avec `/npc move` à
  nouveau, ou `/npc move --stop` pour annuler selon la config).
- `/npc tp` : vous téléporte au PNJ. `/npc tphere` : amène le PNJ à vous.
- Pour un placement fin, se mettre exactement où on veut le PNJ puis `/npc tphere` puis ajuster
  l'orientation avec `/npc rotate`.

## 6. Renommer / supprimer (en jeu)

> [!WARNING]
> `/npc rename` ne change que le nom **Citizens**. La fiche RPGQuest garde l'ancien nom, et le
> catalogue du panel affichera une **divergence**. Pour renommer un personnage, préférer
> « Renommer le PNJ » depuis PlugAdmin (section 3 bis), qui met les deux couches d'accord.

- `/npc rename <nouveau nom>` : change le **nom affiché** uniquement (l'id RPGQuest ne bouge pas).
- `/npc remove` : **supprime définitivement** le PNJ Citizens sélectionné. À n'utiliser que si on
  est sûr. Faire d'abord `/rpgadmin npc untag` pour retirer proprement le mapping RPGQuest.
- `/npc remove all` : supprime **tous** les PNJ — jamais sur un serveur avec du contenu.

## 7. Lier au contenu RPGQuest

Une fois le PNJ tagué `guard`, le contenu s'y rattache **par cet id** :

| Lien | Où | Ce qui se passe |
|---|---|---|
| **Dialogue** | fichier `dialogues/guard.yml` avec `id: rpgquest:guard` (convention `rpgquest:<id>`) | cliquer sur le PNJ tagué ouvre ce dialogue automatiquement |
| **Giver de quête** | `giver: guard` dans le YAML de la quête | la quête est « donnée » par ce PNJ (affiché dans le journal / la page Quêtes) |
| **Objectif `TALK_TO_NPC`** | `npc: guard` dans un `objective` de quête | parler au PNJ tagué `guard` valide l'objectif |
| **NpcDefinition** | `npcs/guard.yml` (`display_name`, `dialogue`, `role`, `enabled`) | définition **logique** du PNJ, indépendante de Citizens — pilotable depuis la page PNJ |
| **Binding Citizens** | table `npc_citizens_bindings` (posée par `/rpgadmin npc tag` sur un PNJ Citizens) | rattache la définition logique au PNJ physique Citizens |

Depuis le Control Panel : la page **PNJ** liste définitions + bindings + anomalies, et permet
`npc.definition.create/update`, `npc.citizens.link`, `npc.citizens.create`, `quest.giver.set`.

## 8. Diagnostic

- En jeu : `/rpgadmin npc info` en visant le PNJ (id RPGQuest + id Citizens).
- Control Panel → page **PNJ** (`/npcs`) : rafraîchir le catalogue et lire les warnings —

| Warning | Signification | Action |
|---|---|---|
| `NO_DEFINITION` / `BINDING_NO_DEFINITION` | un binding Citizens existe mais aucune `npcs/<id>.yml` | créer la définition (`npc.definition.create`) |
| `DIALOGUE_MISSING` | la définition déclare un `dialogue:` qui n'est pas chargé | créer/corriger le dialogue, ou retirer le champ |
| `DIALOGUE_WITHOUT_NPC` | un dialogue que personne ne porte, déduit en « PNJ » par le catalogue | rattacher le dialogue à un PNJ existant (champ **Dialogue** de sa fiche), ou créer la fiche de ce nom |
| `DUPLICATE_BINDING` | deux PNJ Citizens liés au même id RPGQuest | `untag` l'un des deux |
| `NOT_LINKED` | définition présente mais aucun PNJ Citizens tagué avec cet id | taguer le bon PNJ en jeu (`/rpgadmin npc tag <id>`) |
| `QUEST_REF_NO_NPC` | un YAML référence un id de PNJ qui n'existe nulle part | taguer un PNJ, ou corriger l'id dans le YAML |

- Vérifier l'état réel côté serveur : table `npc_citizens_bindings` de `data.db` (une ligne par
  PNJ lié).

## Séquence type (nouveau PNJ « guard »)

```
/npc create Garde --type player      # crée + sélectionne le PNJ Citizens
/npc skin Notch                      # optionnel, cosmétique
/rpgadmin npc info                    # -> "Cette entité n'est pas identifiée."
/rpgadmin npc tag guard              # -> "Entité identifiée : guard (Citizens NPC #N)"
/rpgadmin npc info                    # -> "Identifiant : guard (Citizens NPC #N)"
/npc tphere                           # placer le PNJ précisément
```

Puis, si besoin, depuis la page PNJ du panel : créer `npcs/guard.yml`, poser le `giver:` d'une
quête, etc.

## 9. Quel dialogue un PNJ porte-t-il vraiment ?

Le rattachement PNJ → dialogue se lit dans **cet ordre** :

1. le dialogue que la fiche **déclare** dans son champ `dialogue:` ;
2. à défaut seulement, le dialogue qui porte le **nom** du PNJ.

La convention « le dialogue s'appelle comme le PNJ » n'est donc que le défaut. Un PNJ peut
parfaitement porter un dialogue nommé autrement — `mira_cartographer` déclare
`rpgquest:mira_first_map` — et c'est **la fiche qui fait autorité**.

Conséquence pratique dans l'atelier IA : quand on demande un dialogue pour un PNJ qui en a
déjà un sous un autre nom, le panel **s'arrête et pose la question** avant d'appeler l'IA :

- **modifier le dialogue existant** — la proposition portera son identifiant, aucun second
  dialogue n'est créé ;
- **créer un nouveau dialogue et remplacer le lien** — l'ancien n'est ni supprimé ni délié,
  et il faut repointer la fiche du PNJ ensuite, sinon les joueurs continuent d'entendre
  l'ancien.

Il n'existe pas de troisième possibilité : un dialogue « supplémentaire mais non lié » ne
serait joignable par personne, et réapparaîtrait lui-même dans `/npcs` comme une entrée
sans définition.

## 10. Supprimer un PNJ (issue #226)

Depuis la fiche d'un PNJ, **« Supprimer »** ouvre une page courte (issue #325) : ce qui sera
supprimé (le personnage en jeu, sa fiche), ce qui est conservé (son dialogue, la progression des
joueurs), les quêtes qui ont encore besoin de lui — avec un lien vers chacune — puis
**« Supprimer le PNJ »** (= nettoyage complet D ci-dessous) et **« Annuler »**. Si son dialogue
n'est utilisé par aucun autre PNJ, la page de résultat propose de le supprimer aussi, par le
parcours de suppression des dialogues.

« Autres choix » et « Détails techniques » (les cinq couches, présentes *et* absentes) sont
repliés. Les quatre opérations, de la moins à la plus destructrice :

| | Opération | Ce qu'elle fait | Ce qu'elle ne fait pas |
|---|---|---|---|
| **A** | Supprimer la définition logique seulement | supprime `npcs/<id>.yml`, après sauvegarde serveur dans `npc-backups/` | ne touche ni au PNJ Citizens, ni à sa liaison, ni au dialogue |
| **B** | Délier le PNJ Citizens | retire la liaison (**réversible** : on peut relier) | ne supprime ni l'entité, ni la fiche, ni le dialogue |
| **C** | Supprimer le PNJ Citizens physique | détruit l'entité et retire sa liaison | ne supprime ni la fiche, ni le dialogue |
| **D** | Nettoyage complet | définition + liaison + entité | **jamais le dialogue** |

### Ce qui bloque

Une quête qui désigne encore le PNJ — donneur, cible d'un « parler à », ou **destinataire
d'une remise** — bloque la suppression de sa définition, en nommant les quêtes. Il faut
corriger ces références d'abord : retirer un donneur ou réaffecter un objectif est une
décision éditoriale, pas un nettoyage mécanique.

Une opération bloquée **n'a pas de bouton** : il n'y a rien à cliquer.

### Garde-fous

- **Permission dédiée** : supprimer un PNJ n'est pas accordé par le droit d'écriture.
  Détruire l'entité Citizens exige en plus le droit d'apparition de PNJ.
- **Confirmation tapée** : il faut retaper l'identifiant du PNJ. L'identifiant numérique
  Citizens attendu voyage avec le formulaire et doit correspondre au relevé au moment de l'envoi
  (une page périmée est refusée).
- **Jamais le mauvais Citizens** : le serveur confronte l'identifiant numérique à la
  liaison réelle, puis l'UUID de l'entité, avant de détruire quoi que ce soit.
- **Aucun dialogue n'est supprimé avec le PNJ.** Un dialogue partagé est signalé ; un dialogue qui
  n'est qu'à lui peut ensuite être supprimé par son propre parcours, qui a ses vérifications.
- **Sans relevé `npc.list`, aucune opération n'est proposée** : une liste de dépendances
  vide ne veut pas dire « aucune dépendance » quand on n'a pas regardé. Rafraîchir le
  catalogue PNJ d'abord.
- **Asynchrone** : la demande part à l'agent, qui **revérifie les dépendances** au moment
  d'exécuter et peut refuser même si l'aperçu proposait l'opération. Le verdict se lit dans
  le journal d'actions.
- Rejouer la même opération est sans danger : une définition déjà absente ou une liaison
  déjà retirée renvoient « rien à faire » au lieu d'une erreur.

### Une entrée « sans définition »

Elle n'est pas un PNJ cassé : c'est la **trace d'une référence**. L'aperçu en dit la
provenance (un dialogue du même nom, un PNJ Citizens tagué, une quête qui le cite) et les
remèdes possibles. On ne supprime pas une entrée de catalogue — on supprime ou on corrige
*ce qui la crée*. Voir « Dialogue sans PNJ porteur » dans *Résoudre les problèmes de PNJ*.
