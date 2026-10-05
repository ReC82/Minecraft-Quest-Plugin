/*
 * #196 — garde-fou sur la pagination de la liste recherchable. La logique vit dans panel.js : un
 * test Java ne peut pas la vérifier. Le défaut corrigé était une borne de 60 résultats MUETTE,
 * qui faisait disparaître des objets sans le dire.
 *
 * Volontairement non câblé à Gradle, comme stylefield-parse.test.js : le build ne doit pas
 * dépendre d'un Node installé. Exécution manuelle, depuis la racine du dépôt :
 *
 *     node control-panel/src/test/js/combo-pagination.test.js
 */
"use strict";
const fs = require("fs");
const path = require("path");
const PANEL_JS = path.join(__dirname, "..", "..", "main", "resources", "assets", "panel.js");
const src = fs.readFileSync(PANEL_JS, "utf8");

// On rejoue la logique de fenêtrage telle qu'elle est écrite, en isolant ses constantes.
const pageMatch = /var PAGE = (\d+);/.exec(src);
if (!pageMatch) { console.error("PAGE introuvable dans panel.js"); process.exit(2); }
const PAGE = parseInt(pageMatch[1], 10);

let fail = 0;
function check(name, cond, detail) {
  if (cond) { console.log("ok   : " + name); }
  else { fail++; console.log("ECHEC: " + name + (detail ? " -> " + detail : "")); }
}

function windowOf(options, query, shown) {
  const q = (query || "").trim().toLowerCase();
  const visible = [];
  let total = 0;
  for (const o of options) {
    const m = !q || o.value.toLowerCase().indexOf(q) !== -1
      || (o.label && o.label.toLowerCase().indexOf(q) !== -1);
    if (!m) { continue; }
    total++;
    if (visible.length < shown) { visible.push(o); }
  }
  return { visible, total };
}

const swords = ["WOODEN", "STONE", "COPPER", "GOLDEN", "IRON", "DIAMOND", "NETHERITE"]
  .map(m => ({ value: m + "_SWORD", label: "Épée en " + m.toLowerCase() }));
const filler = Array.from({ length: 1400 }, (_, i) => ({ value: "ITEM_" + i, label: "Objet " + i }));
const all = swords.concat(filler);

let r = windowOf(all, "sword", PAGE);
check("« sword » trouve les 7 épées", r.total === 7 && r.visible.length === 7, JSON.stringify(r.total));

r = windowOf(all, "épée", PAGE);
check("« épée » trouve les 7 épées (recherche par libellé)", r.total === 7, String(r.total));

r = windowOf(all, "SWORD", PAGE);
check("la casse de la recherche est ignorée", r.total === 7, String(r.total));

r = windowOf(all, "", PAGE);
check("une recherche vide annonce le TOTAL réel", r.total === all.length, String(r.total));
check("une recherche vide n'affiche que la première page", r.visible.length === PAGE, String(r.visible.length));
check("la borne est de 100, pas de 60 (défaut corrigé)", PAGE === 100, String(PAGE));

r = windowOf(all, "", PAGE * 2);
check("« afficher plus » étend réellement la fenêtre", r.visible.length === PAGE * 2, String(r.visible.length));

r = windowOf(all, "", all.length + 500);
check("aucun résultat perdu quand la fenêtre dépasse le total",
  r.visible.length === all.length && r.total === all.length, String(r.visible.length));

r = windowOf(all, "zzz-introuvable", PAGE);
check("aucune correspondance renvoie un total nul", r.total === 0, String(r.total));

// Le marqueur de troncature doit être émis dès qu'il reste des résultats.
check("panel.js annonce la troncature", src.indexOf("affichés — afficher") !== -1);
check("panel.js expose un « afficher plus » cliquable", src.indexOf("data-combo-more") !== -1);
check("la fenêtre est réinitialisée à chaque frappe", /shown = PAGE;\s*\n\s*render\(\);/.test(src));

console.log(fail === 0 ? "\nTous les tests passent." : "\n" + fail + " échec(s).");
process.exit(fail === 0 ? 0 : 1);
