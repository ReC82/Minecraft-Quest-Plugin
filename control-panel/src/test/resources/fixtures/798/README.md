# Fixtures #798 — versions réelles des contenus

Copies **figées** de contenus réels, pour rejouer en test la divergence constatée le 23/10/2026
entre la source de PlugAdmin et DEV (issue #798). Ne pas les « mettre à jour » : elles valent
justement parce qu'elles sont anciennes.

| Fichier | Origine |
|---|---|
| `guide-249.yml` | dialogue d'Elias de l'arbre source de PlugAdmin (branche `feature/249-audit-architectural`, `5c74b7f8`) |
| `guide-475.yml` | dialogue d'Elias après #475 (`7fcfe1cc`), avant #778 |
| `guard-plugadmin-avant-778.yml` | dialogue du Garde enregistré par PlugAdmin avant #778 (arbre source du propriétaire) |
| `guard_tier1-249.yml`, `woodcutters_request-249.yml` | quêtes de Gauthier et de Bob, version `5c74b7f8` |
| `first_steps-249.yml`, `crystal_hunt-249.yml` | quêtes retirées définitivement par #778, version `5c74b7f8` |

Les versions « DEV » des tests sont les ressources du dépôt (`src/main/resources/…`), identiques
à DEV au 23/10/2026 (empreintes vérifiées dans le rapport #798).
