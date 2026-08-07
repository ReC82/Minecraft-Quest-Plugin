rootProject.name = "rpgquest"

// Module web séparé du gameplay (mission étape 21, point 1) : aucune
// dépendance vers le plugin Paper ni accès direct à data.db, voir
// docs/WEB_API.md.
include("web-api")
