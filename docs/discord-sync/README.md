# Synchronisation du forum communautaire Discord avec GitHub (issue #202)

Un membre ouvre un sujet dans le forum **bugs-et-suggestions** ; le service crée l'issue GitHub
correspondante, **répond dans le sujet avec le lien**, et y annonce ensuite les changements de
statut. Les membres suivent leur signalement **sans compte GitHub**.

Service **autonome** : module Gradle `discord-sync`, unité systemd dédiée, base SQLite propre.
Il ne dépend ni du plugin Paper, ni du module `web-api`, ni du Control Panel, et **ne provoque
jamais de redémarrage Minecraft**. Les annonces `#news` / `#soon` (issues #186 / #188) ne sont ni
lues ni modifiées.

---

## 1. Ce que le service fait, et ce qu'il ne fait pas

| Il fait | Il ne fait pas |
|---|---|
| Créer une issue par **nouveau** sujet du forum surveillé | Importer en masse les anciens sujets |
| Recopier **titre** et **premier message** | Recopier les messages suivants de la discussion |
| Étiqueter `source:discord` + `type:bug` / `type:request` + `triage` | Toucher aux étiquettes `#news` / `#soon` |
| Répondre dans le sujet avec le lien de l'issue | Relayer intégralement les commentaires GitHub |
| Annoncer les changements de statut dans le sujet | Fermer ou rouvrir une issue (GitHub fait autorité) |
| Poser un tag de statut s'il existe | Retirer les tags Bug / Suggestion du membre |
| Mettre à jour l'issue si le titre ou le premier message change | Écraser les notes de triage écrites sur GitHub |
| Retoucher **uniquement** les issues qu'il a créées | Modifier rétroactivement une issue d'origine inconnue |

---

## 2. Correspondance des statuts

**GitHub fait autorité.** Le service lit l'état des issues ; il n'en change jamais.

| État GitHub | Statut annoncé | Message publié dans le sujet |
|---|---|---|
| ouverte, étiquette `status:needs-testing` | **À tester** | Un correctif existe et attend une vérification. |
| ouverte, étiquette `status:in-progress` | **En cours** | Le travail a commencé. |
| ouverte, sinon (y compris `triage`) | **À trier** | Pris en compte, en attente de tri. |
| fermée, raison `completed` | **Résolu** | Marqué comme réglé côté développement. **Cela ne veut pas dire que c'est en ligne.** |
| fermée, raison `not_planned` | **Refusé** | Ne sera pas réalisé. La discussion reste ouverte. |
| fermée, raison `duplicate` | **Doublon** | Le suivi continue sur l'autre ticket. |
| fermée, sans raison | **Fermé** | Fermé sans raison précisée. |

Deux choix délibérés :

- **« Résolu » ≠ « déployé ».** Une clôture GitHub ne garantit aucun déploiement sur le serveur.
  Le message le dit explicitement, à chaque fois.
- **« Résolu » et « Refusé » sont distincts.** GitHub sépare « fermée parce que faite » et
  « fermée parce qu'on ne la fera pas ». Les confondre ferait croire à un membre que sa demande
  est réglée alors qu'elle est écartée.

Si le salon possède des tags portant ces noms (*À trier*, *En cours*, *À tester*, *Résolu*,
*Refusé*, *Doublon*), le service les applique **en plus** des tags du membre. Leur absence n'est
pas une erreur : le statut est alors annoncé par message seulement.

---

## 3. « Un sujet = une issue », même quand tout va mal

C'est l'invariant qui compte, et il doit tenir après un redémarrage, une reconnexion, un
événement dupliqué ou **une réponse HTTP perdue**.

### 3.1 L'ordre des écritures

L'**intention** de créer est écrite et validée dans SQLite **avant** l'appel réseau. Si le
processus meurt à cet instant précis, il reste au redémarrage une ligne `CREATING` avec
`attempts > 0` : le service sait qu'une création a *peut-être* abouti, et qu'il doit
**réconcilier avant de recréer**. Il ne rejoue jamais une création à l'aveugle.

### 3.2 Un constat mesuré sur le vrai dépôt

La réconciliation consiste à relire les issues et à reconnaître la sienne au marqueur inscrit
dans le corps. Encore faut-il pouvoir la relire. **Mesure faite sur
`ReC82/Minecraft-Quest-Plugin` le 5 octobre 2026** :

| Requête | Issue créée quelques secondes plus tôt |
|---|---|
| `GET /issues?labels=source:discord&sort=updated` | **absente** |
| `GET /issues?labels=source:discord&sort=created` | **absente** |
| `GET /issues?sort=created&direction=desc` | **absente** |
| `GET /issues?state=open` | **absente** |
| `GET /issues?...&since=...` | **absente** |
| `GET /issues/{numéro}` | **présente immédiatement** |

Cinq lectures consécutives de la liste ont manqué l'issue ; l'accès unitaire la renvoyait déjà.
Lors du parcours de validation, elle est apparue dans la liste au bout d'une dizaine de secondes.

**Conséquence directe :** se fier à la liste seule reviendrait à conclure « aucune issue n'existe »
juste après une création dont la réponse a été perdue — et à produire exactement le doublon qu'on
veut éviter. La recherche plein texte de GitHub est encore plus décalée : elle est écartée pour
la même raison.

### 3.3 Les trois garde-fous

1. **Relevé par étiquette** — un appel, retrouve les cas anciens.
2. **Balayage borné des numéros voisins** — par `GET /issues/{numéro}`, le seul accès dont la
   fraîcheur est garantie. Le point de départ est **figé au moment de la tentative** (colonne
   `scan_from`) : un relevé postérieur a pu intégrer l'issue cherchée, et repartir de lui la
   sauterait.
3. **Délai de prudence de 3 minutes** — si les deux échouent, le service **attend** au lieu de
   recréer. Au pire le signalement arrive quelques minutes plus tard ; jamais en double.

---

## 4. Contenu des membres : une donnée, jamais une commande

Tout ce qui vient du forum est assaini avant d'être écrit où que ce soit :

- les séquences `<!--` et `-->` sont réécrites : un membre ne peut pas forger ni casser les
  marqueurs qui délimitent la zone gérée du corps d'issue ;
- `@pseudo` et `#123` sont placés entre accents graves : **aucune notification** et aucune
  référence croisée parasite sur GitHub ;
- le message est rendu en **bloc de citation** : un titre Markdown écrit par un membre ne peut pas
  se faire passer pour une section du service ;
- la taille est **bornée** (6 000 caractères), avec mention de troncature et renvoi vers le sujet ;
- les noms de pièces jointes sont réduits au nom de fichier : jamais traités comme un chemin ;
- les messages publiés sur Discord le sont avec `allowed_mentions` vide : **aucun ping possible**,
  quel que soit le texte.

Les **pièces jointes ne sont pas réhébergées** : seuls le nom, la taille et le lien sont repris, et
l'issue signale que les liens Discord **expirent**.

---

## 5. Zone gérée et notes de triage

Le corps de l'issue est découpé par deux commentaires HTML invisibles :

```
<!-- lodyquests:debut v1 thread=1556660509883760640 -->
   … contenu généré : type, auteur, lien du sujet, message initial, pièces jointes …
<!-- lodyquests:fin -->

   … notes de triage libres : JAMAIS touchées …
```

Le service ne réécrit **que** l'intérieur de la zone. Tout ce qui est écrit avant ou après est
conservé mot pour mot. Si les marqueurs ont disparu — issue réécrite à la main —, le service
**refuse de réécrire le corps** et le signale dans le journal, plutôt que de deviner. Le titre,
lui, reste synchronisé.

Le marqueur d'ouverture porte l'identifiant du sujet : il sert aussi de clé de réconciliation.

---

## 6. Aucun import massif

À son premier démarrage, le service pose un **repère temporel** (un identifiant Discord, qui
encode sa date). Seuls les sujets créés **après** ce repère sont traités spontanément.

Un sujet antérieur n'entre que par une **adoption explicite** :

```bash
/opt/lodyquests-discord/app/bin/discord-sync adopt <idDuSujet> "sujet TEST"
```

C'est le geste prévu pour le sujet « TEST — synchronisation GitHub ». Un sujet adopté est relu
même s'il est archivé et n'apparaît plus dans les listes.

---

## 7. Exploitation

### Installation / mise à jour

```bash
scripts/lodyquests-discord/install.sh            # build + installe + active + démarre
scripts/lodyquests-discord/install.sh --no-build # distribution déjà bâtie
```

Idempotent. L'ancienne application est sauvegardée sous
`/opt/lodyquests-discord/releases/<horodatage>` (5 conservées). Le script **ne crée ni ne modifie
jamais** le fichier de secrets.

### Commandes

| Commande | Effet |
|---|---|
| `discord-sync run` | boucle de synchronisation (ce que lance systemd) |
| `discord-sync once` | un seul tour, puis sortie |
| `discord-sync check` | diagnostic **en lecture seule** — n'écrit **rien** |
| `discord-sync adopt <id> [raison]` | prise en charge explicite d'un sujet existant |
| `discord-sync notice` | texte à coller dans les consignes du forum |
| `discord-sync status` | état local : repère, sujets adoptés, appariements |

`LODYQUESTS_DRY_RUN=true` fait journaliser ce que le service *ferait*, sans rien écrire.

### Journal

```bash
journalctl -u lodyquests-discord -f
```

### Redémarrage supervisé

`Restart=always`, `RestartSec=120`, `StartLimitIntervalSec=0`. Une configuration refusée
(code 78) n'immobilise donc pas l'unité : dès que le fichier de secrets est corrigé, le service
repart **de lui-même** en moins de deux minutes, sans commande supplémentaire.

---

## 8. Configuration et secrets

Fichier **hors dépôt**, jamais versionné : `~/.config/lodyquests-discord/bot.env`, `chmod 600`.
Modèle commenté : [`scripts/lodyquests-discord/bot.env.example`](../../scripts/lodyquests-discord/bot.env.example).

| Clé | Rôle |
|---|---|
| `DISCORD_GUILD_ID` | serveur surveillé (un seul) |
| `DISCORD_FORUM_CHANNEL_ID` | salon de forum surveillé (un seul) |
| `DISCORD_BOT_TOKEN` | jeton du **bot** Discord |
| `GITHUB_REPOSITORY` | `propriétaire/dépôt` |
| `GITHUB_TOKEN` | jeton **à permissions fines**, `Issues: Read and write`, ce dépôt seulement |

systemd lit ce fichier en tant que `root` **avant** de restreindre le service : il n'a donc pas
besoin d'être lisible par d'autres comptes.

### Les deux jetons ne se ressemblent que de loin

Un jeton de bot Discord est fait de **trois parties séparées par des points**. Un jeton GitHub
porte un préfixe reconnaissable (`ghp_`, `github_pat_`…). La forme des deux est vérifiée
**avant tout appel réseau**, parce qu'une inversion ne produit sinon qu'un « 401 Unauthorized »
qui ne dit pas lequel est en cause :

```
Configuration refusée — DISCORD_BOT_TOKEN contient ce qui ressemble à un jeton GitHub, pas à un
jeton de bot Discord. Les deux valeurs semblent inversées ou mal placées dans
/home/ubuntu/.config/lodyquests-discord/bot.env […]. Aucune valeur n'est affichée ici.
```

**Aucun message, aucun journal, aucun rapport ne contient jamais la valeur d'un secret**, et
l'affichage de la configuration les masque (`discordBotToken=<masqué>`).

### Permissions Discord attendues

Voir le salon, lire l'historique des messages, envoyer des messages dans les fils, et —
facultatif, pour les tags de statut — gérer les fils. **Jamais Administrateur** : `check` le
signale comme un défaut à corriger si la permission est accordée.

---

## 9. Consignes du forum

`discord-sync notice` imprime le texte à coller, **adapté à la visibilité réelle du dépôt** lue
sur l'API. Le dépôt `ReC82/Minecraft-Quest-Plugin` étant **public**, le texte prévient que le
message sera visible de tous ; sur un dépôt privé, il dirait l'inverse. Le service ne publie pas
ce texte lui-même : les consignes d'un forum appartiennent à son propriétaire.

---

## 10. Pourquoi de la scrutation et pas la passerelle temps réel

Un bot Discord peut écouter une connexion WebSocket permanente. La scrutation REST a été préférée :
elle évite l'identification, les battements de cœur, la reprise de session et les tempêtes de
reconnexion ; elle rend le redémarrage trivialement sûr — on relit un état, on ne rejoue pas un
flux d'événements ; et un forum communautaire n'a pas besoin de mieux qu'une minute de latence.
Le coût assumé est cette latence. La passerelle reste possible plus tard **sans changer
l'interface** `DiscordApi`.

Côté GitHub, le relevé est fait en requête conditionnelle (`If-None-Match`) : un `304` ne consomme
pas de quota, donc scruter chaque minute reste très en dessous des limites, sans exposer de
webhook ni d'endpoint public.

---

## 11. Tests

```bash
./gradlew :discord-sync:test          # 74 tests, aucun accès réseau
```

Le test d'intégration **réel** est désactivé par défaut et ne s'exécute que sur demande :

```bash
GITHUB_TOKEN=… ./gradlew :discord-sync:test --tests '*LiveGitHubSyncIT' \
    -DdiscordSyncLiveGitHub=true
```

Il n'agit que sur une issue qu'il crée lui-même, titrée `TEST — …`, et la referme à la fin.

---

## 12. Limites de la V1

- Seuls le **titre** et le **premier message** sont synchronisés ; les messages suivants restent
  sur Discord.
- Les **commentaires GitHub ne sont pas relayés** : ce sont des notes techniques internes.
- Les **pièces jointes ne sont pas réhébergées** et leurs liens Discord expirent.
- Latence d'au plus un intervalle de scrutation (60 s par défaut) dans les deux sens.
- Pas de vue des demandes communautaires dans le Control Panel : explicitement hors V1.
