# #428 — Audit des licences des bibliothèques d'icônes

Audit réalisé le 2026-10-16 à partir des pages publiques (dépôts, fichiers LICENCE, Usage
Guidelines et CLUF Minecraft). Analyse factuelle des textes, **pas un avis juridique**. Aucune
image issue de ces sources n'a été téléchargée ni intégrée au dépôt.

## Synthèse

| Source | Licence déclarée | Ce qu'elle couvre réellement | Provenance des images | Risque | Verdict |
|---|---|---|---|---|---|
| [webisso/minecraft-item-icons](https://github.com/webisso/minecraft-item-icons) | MIT (« Copyright (c) 2026 WebissoLLC ») | Le code (React/Vite, `api.json`) ; WebissoLLC ne peut pas licencier des visuels Mojang | ~3 800 PNG nommés comme sur le Minecraft Wiki, récupérés de « source mirrors » non nommés ; inclut des **logos officiels** (Java Edition, Bedrock, Marketplace) | **Élevé** | **Exclu** |
| [themuhamed/mcicons](https://github.com/themuhamed/mcicons) (mcicons.dev) | « MIT » annoncé dans le README, **aucun fichier LICENSE** (API GitHub : aucune licence détectée) ; le domaine mcicons.dev ne répond plus (DNS) | Incertain | 1 000 PNG nommés d'après les identifiants vanilla (`minecraft_acacia_boat.png`…), aucune source citée, aucune mention Mojang | **Élevé** | **Exclu** |
| [aternosorg/renderchest](https://github.com/aternosorg/renderchest) | MIT | L'**outil** de rendu PHP | Ne fournit aucune texture : « a valid Minecraft assets directory is required. It can be extracted from a Minecraft client jar file » | Outil : faible ; icônes produites depuis le jar vanilla : **moyen** (dérivés de textures Mojang) ; depuis un pack de ressources sous licence libre : faible | Seule piste technique acceptable, **sous condition** (voir plus bas) |

## Règles Mojang / Microsoft applicables

Sources : <https://www.minecraft.net/en-us/usage-guidelines> et <https://www.minecraft.net/en-us/eula>.

- Les **textures** font partie à la fois de la « marque » (« names, related logos, fonts, textures »)
  et des « actifs » (« graphics, textures, images, models… ») de Mojang.
- Un serveur ou un site de fans, **même non commercial**, relève de la section « Commercial use » des
  Guidelines : « This applies, for example, if you want to set up and run any non-commercial blogs,
  servers, community forums, fan sites ».
- Interdits pertinents : laisser croire à un caractère officiel ; « redistribute our games or any
  alterations of our games or game files » ; utiliser un élément de la marque ou des actifs « as a logo
  or part of a logo ». CLUF : « You must not distribute anything we've made unless we specifically
  agree to it » ; « a single Minecraft block (including its textures and its "look and feel") - we own that ».
- Mention exigée sur un site : « NOT AN OFFICIAL MINECRAFT [PRODUCT/SERVICE/EVENT/etc.]. NOT APPROVED
  BY OR ASSOCIATED WITH MOJANG OR MICROSOFT ». Le pied de page actuel du portail dit « Site non
  officiel, sans lien avec Mojang ou Microsoft » : à rapprocher de la formule exacte en phase 2.
- La **police Minecraft** fait partie de la marque : exclue.
- Les captures d'écran sont autorisées ; aucune clause n'autorise explicitement des icônes de textures
  sur le web. **Zone grise** : c'est précisément ce que le ticket demande de ne pas trancher seul.
- La boutique et les dons restent possibles selon « Servers and hosting », mais aucune texture Mojang
  ne doit figurer dans ce qui est vendu, ni servir de logo ou d'identité visuelle.

## Alternatives sous licence explicite (non intégrées)

| Set | Licence | Crédit | URL |
|---|---|---|---|
| Kenney (packs icônes, voxel, RPG) | CC0 (« Attribution is not required ») | facultatif | <https://kenney.nl/support> |
| game-icons.net (SVG monochromes) | CC BY 3.0 | « Icons made by {author}. Available on https://game-icons.net » | <https://game-icons.net/about.html> |
| Shikashi's Fantasy Icons Pack (pixel art RPG) | CC BY 4.0 | Matt Firth (shikashipx) + game-icons.net — **à confirmer dans le fichier de licence du zip** (sources contradictoires) | <https://shikashipx.itch.io/shikashis-fantasy-icons-pack> |

## Conclusion et décision attendue du propriétaire

1. **webisso et mcicons sont exclus** : redistribution d'actifs Mojang sous une étiquette MIT qui ne
   les couvre pas (et logos officiels chez webisso).
2. **Retenu pour le prototype : un jeu d'icônes pixel art original**, dessiné pour LodyQuests
   (`tools/icons.py`, 25 icônes 16×16). Droits clairs (création du projet), aucune texture copiée,
   style « inspiré de » sans imiter l'interface ni le logo Minecraft.
3. **À décider par le propriétaire** (rien n'est fait sans son accord) :
   - (a) continuer avec les icônes originales (recommandé : aucun risque, extensibles au besoin) ;
   - (b) compléter par Kenney CC0 (sans crédit) ou Shikashi CC BY 4.0 (avec crédits sur le portail) ;
   - (c) générer avec renderchest des icônes **réservées à PlugAdmin** (privé, non redistribué) à partir
     du jar client — risque résiduel « moyen », à éviter sur le portail public.
