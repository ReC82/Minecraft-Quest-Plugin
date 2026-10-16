# #428 — Prototype d'iconographie et d'identité visuelle (phase 1)

À comparer avant toute intégration. Ouvrir `index.html` depuis une copie du dépôt (les pages
PlugAdmin chargent le vrai CSS du panel par chemin relatif).

| Fichier | Contenu |
|---|---|
| `AUDIT_LICENCES.md` | Audit des 3 sources candidates + règles Mojang ; source retenue et décision attendue |
| `DESIGN_SYSTEM.md` | Inventaire de l'existant, règles d'usage, correspondance des materials, plan phases 2-3 |
| `palette.html` | Les 25 icônes à 16/32/48 px et en tuile, fond clair et fond sombre ; table material → icône |
| `plugadmin-a.html`, `plugadmin-b.html` | Fiche quête « La chasse aux cristaux » dans l'habillage réel de PlugAdmin, variantes A et B |
| `portail-a.html`, `portail-b.html` | Accueil réel du portail (capture du web-api local) habillé, variantes A et B |
| `captures/` | Captures 1280 px et 390 px de toutes les pages |
| `tools/` | `icons.py` (grilles pixel art), `build_sprite.py`, `build_prototypes.py` |
| `sources/` | Pages réelles capturées sur des instances locales (PlugAdmin, web-api) |

Régénérer :

```bash
python3 -I docs/design/428-icones/tools/build_sprite.py docs/design/428-icones/tools docs/design/428-icones/assets/lq-icons.svg
python3 -I docs/design/428-icones/tools/build_prototypes.py
```

- **Variante A — Minecraft modernisé, sobre** : charte actuelle conservée, les icônes apportent
  seules l'univers (tuiles neutres).
- **Variante B — RPG fantasy voxel** : parchemin, cadres bois et or, emplacements biseautés, titres serif.
