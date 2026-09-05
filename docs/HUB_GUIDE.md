# Guides de Hub — aide en jeu et orientation (issue #11)

Le **Guide** est le PNJ « centre d'aide » d'un Hub : il explique les
mécaniques du jeu et oriente le joueur vers les bons PNJ. Cette page décrit
la V1 et sa structure d'extensibilité multi-Hub.

## Deux morceaux distincts

| Morceau | Où | Rôle |
|---|---|---|
| **Contenu du menu d'aide** | `plugins/RPGQuest/dialogues/guide.yml` (dialogue RPGQuest) | Les textes d'aide, sujet par sujet. C'est un dialogue ordinaire : nœud `help_menu` + un nœud `help_*` par mécanique, chacun ramenant au menu (`next: help_menu`) ou fermant. |
| **Mapping Hub → Guide** | `plugins/RPGQuest/hub-guides/<hub>.yml` (registre `hub.HubGuideRegistry`) | Quel dialogue d'aide pour quel Hub, + accueil, spécialité locale et orientations vers les PNJ du Hub. |

Séparer les deux permet d'ajouter un Hub **sans code** : un nouveau
`hub-guides/<autre_hub>.yml` + un nouveau `dialogues/guide_<autre_hub>.yml`.

## Format `hub-guides/<hub>.yml`

```yaml
hub-id: hub_depart              # obligatoire — minuscules, chiffres, . _ -
worlds:                         # optionnel — mondes servis par ce Guide
  - world_hub
guide-dialogue: rpgquest:guide  # obligatoire — dialogue portant le menu d'aide
help-node: help_menu            # optionnel — nœud du dialogue (défaut : help_menu)
welcome: "Bienvenue au village de départ !"      # optionnel
specialty: "Ici, on apprend les bases : quêtes, journal, premier terrain."  # optionnel
referrals:                      # optionnel — orientations TEXTUELLES
  - role: "Journal & quêtes"    # role + npc obligatoires ; note optionnelle
    npc: "le Libraire"
    note: "Il remet le journal des quêtes."
  - role: "Claims (terrains)"
    npc: "Jo"
    note: "Va le voir pour ton premier acte de propriété."
```

Validation au chargement (`HubGuideLoader`, même esprit que `ZoneLoader`) :

- `hub-id` et `guide-dialogue` obligatoires, `hub-id` au bon format ;
- un fichier invalide n'empêche pas le chargement des autres ;
- **`hub-id` dupliqué** entre fichiers → les deux sont rejetés ;
- **un monde revendiqué par deux Hubs** → le second est rejeté (un monde ↦
  un seul Guide).

`hub_depart.yml` est **généré automatiquement** au premier démarrage
(comme `zones/central_village.yml`).

## Diagnostic admin

Lecture seule, aucune modification, utilisable depuis la console :

| Commande | Effet |
|---|---|
| `/rpgadmin guide list` | Liste les Guides de Hub chargés (hub-id, mondes, dialogue + nœud). |
| `/rpgadmin guide info <hub>` | Détail d'un Hub : mondes, dialogue d'aide, accueil, spécialité, orientations. |

Permission : `rpgquest.admin.world` (comme tout `/rpgadmin`).

## Limites V1 (hors périmètre)

- Pas de waypoint / halo / particules / navigation automatique vers le PNJ
  recommandé — l'orientation reste **textuelle**.
- Pas de système multi-Hub complet : la V1 ne configure qu'un seul Hub réel
  (`hub_depart`). La structure est simplement prête à en accueillir
  d'autres, y compris des mécaniques débloquées après découverte d'un Hub.
- Le registre ne résout pas le dialogue : il n'en connaît que
  l'identifiant. Le contenu d'aide reste dans le fichier de dialogue.
