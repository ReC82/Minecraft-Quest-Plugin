# Format d'un dialogue

``` yaml
id: guard_intro
speaker: Gauthier

text: >
  Bienvenue.

choices:
  - text: J'accepte.
    action: START_QUEST:guard_tier1

  - text: Plus tard.
    action: CLOSE
```
