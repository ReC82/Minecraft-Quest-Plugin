rootProject.name = "rpgquest"

// Module web séparé du gameplay (mission étape 21, point 1) : aucune
// dépendance vers le plugin Paper ni accès direct à data.db, voir
// docs/WEB_API.md.
include("web-api")

// RPGQuest Control Panel (issue #37) : application d'administration distincte,
// authentifiée, qui parle au plugin uniquement via le bridge HTTP versionné
// (com.lodygames.rpgquest.web.admin) — jamais data.db. Aucune dépendance vers
// Paper ni vers le module web-api. Voir docs/control-panel/.
include("control-panel")
