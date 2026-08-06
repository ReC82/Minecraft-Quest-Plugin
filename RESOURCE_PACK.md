# Resource pack

Optionnel : **le plugin fonctionne normalement (apparence vanilla) sans
lui**, désactivé par défaut (`config.yml` → `resource-pack.enabled: false`).

## Contenu (`resource-pack/` à la racine du dépôt)

```
resource-pack/
├── pack.mcmeta
└── assets/rpgquest/models/item/
    ├── forest_blade.json
    ├── miner_pickaxe.json
    ├── spider_fang.json
    └── refined_crystal.json
```

Chaque modèle référence un `item-model:` déclaré dans le YAML de l'objet
correspondant (voir [CUSTOM_ITEMS.md](CUSTOM_ITEMS.md)) et réutilise pour
l'instant une **texture vanilla existante** (ex. `minecraft:item/bone` pour
`spider_fang`) comme placeholder temporaire simple — aucune texture propre
au projet n'est encore fournie, seuls les modèles JSON existent. Remplacer
`textures.layer0` par une texture originale dans le pack quand des assets
définitifs seront disponibles ne nécessite aucun changement côté plugin.

## Construction (zip + SHA-1)

```
gradlew.bat resourcePackSha1     # Windows
./gradlew resourcePackSha1       # Linux/macOS
```

Produit `build/resource-pack/RPGQuest-resource-pack.zip` et
`RPGQuest-resource-pack.zip.sha1` (contenu du fichier = le hash hexadécimal
attendu par `config.yml`). Le zip est reproductible (pas d'horodatage,
ordre des fichiers stable) : un contenu inchangé produit toujours le même
SHA-1.

## Configuration (`config.yml` → `resource-pack`)

```yaml
resource-pack:
  enabled: true
  url: "https://example.com/RPGQuest-resource-pack.zip"   # http(s) uniquement
  sha1: "<40 caractères hexadécimaux, voir .sha1 ci-dessus>"
  required: false   # true = message d'avertissement insistant en cas de refus/échec (jamais de kick automatique)
```

Validée au démarrage et à `/rpgquest reload` : `url`/`sha1` obligatoires et
strictement formés uniquement quand `enabled: true` ; un `config.yml`
existant sans section `resource-pack` continue de fonctionner (section
absente = désactivé).

## Hébergement

Le zip doit être accessible en HTTP(S) par les clients des joueurs — un
simple hébergement statique (release GitHub, S3, etc.) suffit ; aucun
serveur applicatif dédié n'est requis par le plugin.

## Comportement en jeu

Envoyé à la connexion si activé (`Player#setResourcePack`, avec un message
d'invite MiniMessage). `PlayerResourcePackStatusEvent` est géré : chargement
réussi (log en mode `debug`), refus/échec de téléchargement/URL
invalide/échec de rechargement (message d'avertissement au joueur
uniquement si `required: true`). Dans tous les cas, aucune déconnexion
automatique — un joueur sans le pack garde l'apparence vanilla des objets
personnalisés.
