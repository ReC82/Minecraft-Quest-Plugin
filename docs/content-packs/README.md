# Content packs d'exemple

- `tom_reprend_la_route.json` — Tom, 1 personnage, story de 3 quêtes : test minimal de
  non-régression de l'import (#211, TC-298).
- **Les souvenirs de Lily** (test d'acceptation #211, TC-312) et le **modèle d'histoire** pour IA
  externe vivent dans `control-panel/src/main/resources/content-packs/`, d'où PlugAdmin les sert
  (`/content/story-kit`). Une seule copie, pour que le fichier téléchargé soit exactement celui que
  les tests valident.
