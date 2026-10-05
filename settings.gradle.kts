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

// Synchronisation du forum communautaire Discord avec les issues GitHub (issue #202) :
// service autonome, indépendant du plugin Paper, du module web-api et du Control Panel.
// Aucune dépendance vers Paper ni vers data.db. Voir docs/discord-sync/.
include("discord-sync")
