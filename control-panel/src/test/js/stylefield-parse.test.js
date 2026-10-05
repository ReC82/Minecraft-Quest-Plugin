/*
 * Issue #195 — garde-fou sur l'analyse MiniMessage du champ de texte stylé.
 *
 * Pourquoi un test JavaScript séparé : la règle vraiment risquée de #195 est côté client. Un
 * éditeur « une couleur + des cases » ne peut pas représenter « <red>Roi</red> <gold>des
 * Marais</gold> » sans l'aplatir. sfParse() doit donc REFUSER le mode guidé pour tout texte
 * multi-styles, afin que le contenu existant ne soit jamais simplifié en silence. Un test Java
 * ne peut pas le vérifier : la logique vit dans assets/panel.js.
 *
 * Volontairement non câblé à Gradle : le build ne doit pas dépendre d'un Node installé (règle
 * « éviter les dépendances inutiles »). Exécution manuelle, depuis la racine du dépôt :
 *
 *     node control-panel/src/test/js/stylefield-parse.test.js
 *
 * Les fonctions sont extraites de panel.js au vol : le test ne peut pas se désynchroniser d'une
 * copie périmée.
 */
"use strict";

const fs = require("fs");
const path = require("path");

const PANEL_JS = path.join(__dirname, "..", "..", "main", "resources", "assets", "panel.js");
const source = fs.readFileSync(PANEL_JS, "utf8");

const from = source.indexOf("var SF_COLORS");
const to = source.indexOf("function initStyleFields");
if (from < 0 || to < 0 || to <= from) {
  console.error("Bloc du champ stylé introuvable dans panel.js (SF_COLORS .. initStyleFields).");
  console.error("Si le code a été renommé, mettre ce test à jour plutôt que le contourner.");
  process.exit(2);
}
const { sfParse, sfCompose } = new Function(
  source.slice(from, to) + "\nreturn { sfParse: sfParse, sfCompose: sfCompose };")();

let failures = 0;
function check(name, condition, detail) {
  if (condition) {
    console.log("ok   : " + name);
  } else {
    failures++;
    console.log("ECHEC: " + name + (detail ? " -> " + detail : ""));
  }
}

// --- Valeurs uniformes : le mode guidé doit être proposé, prérempli correctement.
let p = sfParse("<red>Roi des Marais</red>");
check("couleur simple", p.uniform && p.text === "Roi des Marais" && p.color === "red", JSON.stringify(p));
p = sfParse("<gold><bold>Boss</bold></gold>");
check("couleur + gras", p.uniform && p.text === "Boss" && p.color === "gold" && p.decos.bold === true, JSON.stringify(p));
p = sfParse("Texte nu");
check("texte sans balise", p.uniform && p.text === "Texte nu" && p.color === "", JSON.stringify(p));
p = sfParse("");
check("valeur vide", p.uniform && p.text === "", JSON.stringify(p));
p = sfParse("<b><i>Deux styles</i></b>");
check("deux décorations sans couleur", p.uniform && p.decos.bold && p.decos.italic, JSON.stringify(p));

// --- Valeurs NON uniformes : mode guidé refusé, donc contenu préservé tel quel.
[
  ["multi-couleurs", "<red>Roi</red> <gold>des Marais</gold>"],
  ["balise au milieu du texte", "Bonjour <red>joueur</red>"],
  ["balise avancée (gradient)", "<gradient:red:blue>Dégradé</gradient>"],
  ["couleur hexadécimale", "<#ff00ff>Hex</#ff00ff>"],
  ["balise hover", "<hover:show_text:'x'>T</hover>"]
].forEach(([name, value]) => {
  check(name + " : mode guidé refusé", sfParse(value).uniform === false, JSON.stringify(sfParse(value)));
});

// --- Recomposition.
check("compose couleur + gras",
  sfCompose("Boss", "gold", { bold: true }) === "<gold><bold>Boss</bold></gold>",
  sfCompose("Boss", "gold", { bold: true }));
check("compose sans couleur ni style", sfCompose("Nu", "", {}) === "Nu", sfCompose("Nu", "", {}));
check("texte vide donne une valeur vide", sfCompose("", "red", { bold: true }) === "",
  JSON.stringify(sfCompose("", "red", { bold: true })));

// --- Aller-retour : ouvrir puis réenregistrer sans rien toucher ne doit rien changer.
["<red>A</red>", "<gold><bold>B</bold></gold>", "C", "<blue><italic>D</italic></blue>"].forEach((value) => {
  const q = sfParse(value);
  check("aller-retour " + value,
    q.uniform && sfCompose(q.text, q.color, q.decos) === value,
    sfCompose(q.text, q.color, q.decos));
});

console.log(failures === 0 ? "\nTous les tests passent." : "\n" + failures + " échec(s).");
process.exit(failures === 0 ? 0 : 1);
