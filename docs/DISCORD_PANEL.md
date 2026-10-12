# Gérer les messages Discord depuis PlugAdmin — analyse (issue #296)

**Statut : analyse uniquement.** Aucune connexion à Discord, aucun appel d'API ni aucun message
n'a été fait pour l'écrire. Nuit du 12 au 13/10/2026.

## 1. Ce qui existe déjà

| Intégration | Où | Ce qu'elle fait | Secret |
| --- | --- | --- | --- |
| **Annonces joueurs** (#186) | `.github/workflows/discord-news.yml`, `discord-soon.yml` (branche par défaut) | Un ticket du propriétaire portant `annonce-joueurs` + `discord-publish` est publié dans **#news** par le webhook « NewsRoom » ; une modification du ticket **édite** le message publié (identifiant mémorisé dans un commentaire marqueur du ticket). | `DISCORD_CHANGELOG_WEBHOOK_URL`, secret GitHub Actions — jamais sur AWS |
| **Bot LodyQuests** (#202) | module `discord-sync/` (branche `feature/202-discord-forum-sync`, pas sur la ligne d'intégration), installé dans `/opt/lodyquests-discord/app`, service `lodyquests-discord.service` (actif) | Forum « bugs-et-suggestions » ↔ issues GitHub : création d'issue, lien en retour, statut synchronisé. | `~/.config/lodyquests-discord/bot.env` (jeton du bot, jeton GitHub) — protégé, jamais versionné |

Aucune des deux n'est reliée à PlugAdmin aujourd'hui, et le panel ne contient aucun code Discord.

## 2. Webhook ou bot ?

**Recommandation pour le MVP : des webhooks, un par salon de destination, gérés par le panel.**

- un webhook suffit pour tout le MVP : publier (`POST …?wait=true` renvoie l'identifiant du
  message), **modifier** et **supprimer ses propres messages** (`PATCH`/`DELETE
  /webhooks/{id}/{token}/messages/{message_id}`), texte et embeds ;
- aucune connexion permanente, aucun intent, aucun droit sur le reste du serveur Discord : une fuite
  du secret ne permet que d'écrire dans **un** salon ;
- pas de couplage avec le bot du forum : un incident de l'un n'arrête pas l'autre.

Le bot ne deviendrait utile que pour ce que le webhook ne sait pas faire : lister les salons du
serveur, lire des messages de membres, modérer. Tout cela est **hors périmètre** du ticket (la
modération des membres en est explicitement exclue).

Limite à annoncer dans l'écran : un webhook ne peut modifier ou supprimer **que les messages qu'il a
lui-même publiés** — jamais ceux d'un membre, ni ceux du bot, ni ceux du webhook NewsRoom de GitHub.

## 3. MVP proposé

1. **Destinations** (`/discord/destinations`) : nom lisible + URL de webhook. L'URL est un secret :
   stockée côté serveur dans la base du panel (chiffrée au repos avec une clé hors Git, comme les
   clés de fournisseurs IA), jamais renvoyée au navigateur (affichée `…/webhooks/1234…/••••`),
   jamais journalisée. Validation stricte : `https://discord.com/api/webhooks/<id>/<jeton>` (ou
   `discordapp.com`), rien d'autre. « Tester » = un `GET` du webhook (lit son nom et son salon,
   **n'envoie rien**).
2. **Rédaction** (`/discord/compose`) : destination, texte (≤ 2 000 caractères), embed facultatif
   (titre, description ≤ 4 096, couleur, lien), aperçu rendu dans le panel. Modèles : annonce,
   maintenance, mise à jour, événement. Brouillons enregistrés et modifiables.
3. **Envoi** : bouton distinct, confirmation, `allowed_mentions: {"parse": []}` par défaut
   (`@everyone`, `@here` et rôles **désactivés** ; case explicite et permission dédiée pour les
   autoriser).
4. **Historique** (`/discord/history`) : auteur, date, destination, contenu, résultat, identifiant
   du message ; consultation sans réenvoi ; « Modifier » et « Supprimer » (confirmation) pour nos
   messages.

## 4. Sûreté

- **Idempotence** : chaque envoi est une ligne `discord_message` créée `PENDING` avec une clé
  d'idempotence (brouillon + version) **avant** l'appel ; un double clic ou une reprise trouve la
  ligne et ne renvoie pas. Réponse reçue → `SENT` + identifiant ; échec → `FAILED` + diagnostic.
  Une ligne `PENDING` sans réponse (coupure réseau) est marquée « état inconnu » : le panel propose
  de **vérifier** (relecture du message par son identifiant si connu) avant tout renvoi manuel.
- **Débit** : respecter `429` + `retry_after` ; une file par destination ; jamais de boucle de
  réessai automatique au-delà d'une tentative différée.
- **Permissions** du panel : `DISCORD_READ` (historique), `DISCORD_CONFIG` (destinations),
  `DISCORD_SEND` (envoyer, modifier), `DISCORD_DELETE` (supprimer), `DISCORD_MENTION`
  (autoriser les mentions de masse). Toutes les actions sont auditées.
- **Erreurs** intelligibles : webhook supprimé (`404` → destination marquée invalide), jeton
  révoqué (`401`), contenu trop long, embed invalide — affichées telles quelles, sans le secret.

## 5. Tests prévus (sans Discord)

Un faux serveur HTTP local qui imite l'API des webhooks : contrôle des permissions, validation des
URL, payload exact (`allowed_mentions` vide par défaut), limites de longueur, `429` avec
`retry_after`, double clic → un seul envoi, coupure après envoi → « état inconnu » et aucun renvoi
automatique, édition et suppression de nos seuls messages. Un test réel n'aura lieu que sur un salon
d'essai, sur instruction explicite du propriétaire.

## 6. Découpage proposé

| Lot | Contenu |
| --- | --- |
| 1 | Destinations (secret chiffré, test sans envoi), permissions, audit |
| 2 | Rédaction, aperçu, envoi idempotent, historique |
| 3 | Modèles, brouillons, modification/suppression de nos messages |
| 4 (plus tard) | Messages programmés, notifications automatiques (redémarrage, boss, événements) |

Décision attendue du propriétaire avant le lot 1 : confirmer le choix « webhooks gérés par le
panel » (recommandé) plutôt que la réutilisation du jeton du bot #202, et nommer le salon d'essai.
