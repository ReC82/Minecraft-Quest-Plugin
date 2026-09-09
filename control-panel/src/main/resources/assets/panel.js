"use strict";
/*
 * RPGQuest Control Panel — script progressif (même origine, conforme CSP `default-src 'self'`,
 * aucune dépendance externe autre que Bootstrap déjà chargé).
 *
 * Modules :
 *   - initToasts()        : affiche les toasts Bootstrap (feedback d'action) et met à jour
 *                           en place un toast d'action « en cours » quand elle se résout ;
 *   - initNotifications() : rafraîchit la cloche de la topbar (badge + liste) par un polling
 *                           léger de /agents/actions.json — accéléré tant qu'une action est en cours ;
 *   - initCopy()          : copie d'un identifiant technique ([data-copy]) ;
 *   - initFilters()       : filtrage progressif des listes de cartes ;
 *   - initDrawer()        : ferme le tiroir mobile au clic sur un lien.
 *
 * Pas de WebSocket (MVP #93). Le polling s'arrête tout seul (garde-fou de durée).
 */
(function () {
  var SLOW_MS = 20000;   // rythme de repos du centre de notifications
  var FAST_MS = 3000;    // rythme tant qu'une action est « en cours »
  var MAX_TICKS = 400;   // garde-fou : ~ 2 h au rythme lent

  function esc(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  }

  /* ---- Toasts ------------------------------------------------------------------------- */

  var pendingToasts = []; // { el, actionId }

  /**
   * Affiche un toast. Utilise le composant Bootstrap Toast s'il est disponible ; sinon, repli
   * manuel (classe .show + auto-dismiss) pour ne jamais avaler le feedback (fix #93 bug 2).
   */
  function showToast(el) {
    var autohide = el.getAttribute("data-bs-autohide") === "true";
    var delay = parseInt(el.getAttribute("data-bs-delay") || "6000", 10);
    if (window.bootstrap && window.bootstrap.Toast) {
      var t = window.bootstrap.Toast.getOrCreateInstance(el, { autohide: autohide, delay: delay });
      t.show();
      return;
    }
    // Repli sans Bootstrap JS.
    el.classList.add("show");
    el.style.opacity = "1";
    if (autohide) {
      window.setTimeout(function () { el.classList.remove("show"); }, delay);
    }
  }

  function initToasts() {
    var root = document.getElementById("toast-root");
    if (!root) { return; }
    var seeds = document.querySelectorAll(".pa-toast");
    for (var i = 0; i < seeds.length; i++) {
      var el = seeds[i];
      if (el.getAttribute("data-toast-ready") === "1") { continue; }
      el.setAttribute("data-toast-ready", "1");
      if (el.parentNode !== root) { root.appendChild(el); }
      showToast(el);
      var actionId = el.getAttribute("data-toast-action");
      if (actionId && el.getAttribute("data-toast-group") === "pending") {
        pendingToasts.push({ el: el, actionId: actionId });
      }
    }
  }

  function updateToast(entry, item) {
    var el = entry.el;
    el.setAttribute("data-toast-group", item.group);
    var icon = el.querySelector(".toast-ic");
    if (icon) {
      icon.className = "toast-ic " + (item.group === "success" ? "text-success"
        : item.group === "failed" ? "text-danger" : "text-primary");
      var use = biClass(item.group);
      var i = icon.querySelector("i.bi");
      if (i) { i.className = "bi " + use; }
    }
    var body = el.querySelector("[data-toast-body]");
    if (body) {
      var txt = item.resultShort || (item.group === "success" ? "Action terminée." : "Action en échec.");
      if (item.target) { txt = item.target + " — " + txt; }
      body.textContent = txt;
    }
    var time = el.querySelector("[data-toast-time]");
    if (time && item.age) { time.textContent = item.age; }
    // Une fois résolue : succès -> auto-fermeture douce ; échec -> reste jusqu'à fermeture.
    if (item.group === "success") {
      window.setTimeout(function () {
        var t = window.bootstrap && window.bootstrap.Toast ? window.bootstrap.Toast.getOrCreateInstance(el) : null;
        if (t) { t.hide(); }
      }, 5000);
    }
  }

  function biClass(group) {
    return group === "success" ? "bi-check-circle"
      : group === "failed" ? "bi-x-circle" : "bi-clock";
  }

  /* ---- Centre de notifications ------------------------------------------------------- */

  function initNotifications() {
    var center = document.getElementById("notif-center");
    var agent = center ? center.getAttribute("data-notif-agent") : null;
    if (!agent && pendingToasts.length === 0) { return; }
    if (!agent && center) { agent = center.getAttribute("data-notif-agent"); }
    if (!agent) { return; }

    var ticks = 0;
    var timer = null;

    function schedule(ms) {
      if (timer) { window.clearTimeout(timer); }
      timer = window.setTimeout(tick, ms);
    }

    function applyBadge(n) {
      var badge = document.querySelector("[data-notif-badge]");
      if (!badge) { return; }
      badge.textContent = String(n);
      badge.hidden = !(n > 0);
    }

    function applyList(actions) {
      var list = document.querySelector("[data-notif-list]");
      if (!list || !actions) { return; }
      if (actions.length === 0) {
        list.innerHTML = '<p class="notif-empty">Aucune action pour le moment.</p>';
        return;
      }
      list.innerHTML = actions.slice(0, 8).map(function (a) {
        return a.notifHtml || ('<span class="notif-item">' + esc(a.label || a.type) + "</span>");
      }).join("");
    }

    function tick() {
      ticks += 1;
      if (ticks > MAX_TICKS) { return; }
      fetch("/agents/actions.json?agent=" + encodeURIComponent(agent), {
        headers: { "Accept": "application/json" }, credentials: "same-origin"
      }).then(function (res) {
        if (res.status === 401 || res.status === 403) { return null; }
        if (!res.ok) { throw new Error("HTTP " + res.status); }
        return res.json();
      }).then(function (data) {
        if (!data) { return; }
        if (typeof data.badge === "number") { applyBadge(data.badge); }
        if (data.actions) { applyList(data.actions); }

        var stillPending = false;
        for (var i = pendingToasts.length - 1; i >= 0; i--) {
          var entry = pendingToasts[i];
          var match = null;
          for (var k = 0; data.actions && k < data.actions.length; k++) {
            if (data.actions[k].idFull === entry.actionId) { match = data.actions[k]; break; }
          }
          if (match && match.group !== "pending") {
            updateToast(entry, match);
            pendingToasts.splice(i, 1);
          } else if (match) {
            stillPending = true;
          }
        }
        schedule(stillPending ? FAST_MS : SLOW_MS);
      }).catch(function () {
        schedule(SLOW_MS);
      });
    }

    tick();
  }

  /* ---- Copie d'un identifiant technique -------------------------------------------- */

  function flashCopied(el) {
    el.classList.add("copied");
    window.setTimeout(function () { el.classList.remove("copied"); }, 1200);
  }

  function copyText(text, el) {
    var done = function () { flashCopied(el); };
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(done, function () { legacyCopy(text, done); });
        return;
      }
    } catch (e) { /* repli */ }
    legacyCopy(text, done);
  }

  function legacyCopy(text, done) {
    try {
      var ta = document.createElement("textarea");
      ta.value = text;
      ta.setAttribute("readonly", "");
      ta.style.position = "fixed";
      ta.style.opacity = "0";
      document.body.appendChild(ta);
      ta.select();
      document.execCommand("copy");
      document.body.removeChild(ta);
      done();
    } catch (e) { /* l'infobulle title reste dispo */ }
  }

  function initCopy() {
    document.addEventListener("click", function (ev) {
      var el = ev.target.closest ? ev.target.closest("[data-copy]") : null;
      if (el) { copyText(el.getAttribute("data-copy") || el.textContent, el); }
    });
    document.addEventListener("keydown", function (ev) {
      if (ev.key !== "Enter" && ev.key !== " " && ev.key !== "Spacebar") { return; }
      var el = ev.target && ev.target.matches && ev.target.matches("[data-copy]") ? ev.target : null;
      if (el) { ev.preventDefault(); copyText(el.getAttribute("data-copy") || el.textContent, el); }
    });
  }

  /* ---- Filtrage progressif des listes de cartes ---------------------------------- */

  function initFilters() {
    var inputs = document.querySelectorAll("[data-filter-input]");
    for (var i = 0; i < inputs.length; i++) {
      (function (input) {
        var scope = input.getAttribute("data-filter-input");
        var items = document.querySelectorAll('[data-filter-item="' + cssEsc(scope) + '"]');
        // Plusieurs groupes de puces possibles pour un même scope (ex. gravité + domaine),
        // combinés en ET. Rétro-compatible : un seul groupe = comportement historique.
        var chipBoxes = document.querySelectorAll('[data-filter-chips="' + cssEsc(scope) + '"]');
        var note = document.querySelector("[data-count-note]");
        var active = [];

        function apply() {
          var q = (input.value || "").trim().toLowerCase();
          var shown = 0;
          for (var k = 0; k < items.length; k++) {
            var el = items[k];
            var hay = (el.getAttribute("data-filter-text") || el.textContent || "").toLowerCase();
            var cats = (" " + (el.getAttribute("data-filter-cat") || "") + " ");
            var okText = !q || hay.indexOf(q) !== -1;
            var okCat = true;
            for (var a = 0; a < active.length; a++) {
              if (active[a] && cats.indexOf(" " + active[a] + " ") === -1) { okCat = false; break; }
            }
            var show = okText && okCat;
            el.hidden = !show;
            if (show) { shown++; }
          }
          if (note) {
            var noun = note.getAttribute("data-noun") || "résultat";
            note.textContent = shown + " " + noun + (shown > 1 ? "s" : "");
          }
        }
        input.addEventListener("input", apply);
        for (var b = 0; b < chipBoxes.length; b++) {
          (function (box, bi) {
            active[bi] = null;
            box.addEventListener("click", function (ev) {
              var chip = ev.target.closest ? ev.target.closest("[data-filter-chip]") : null;
              if (!chip) { return; }
              var val = chip.getAttribute("data-filter-chip");
              var chips = box.querySelectorAll("[data-filter-chip]");
              if (active[bi] === val || val === "") {
                active[bi] = null;
                for (var c = 0; c < chips.length; c++) {
                  chips[c].classList.toggle("on", chips[c].getAttribute("data-filter-chip") === "");
                }
              } else {
                active[bi] = val;
                for (var c2 = 0; c2 < chips.length; c2++) { chips[c2].classList.toggle("on", chips[c2] === chip); }
              }
              apply();
            });
          })(chipBoxes[b], b);
        }
        apply();
      })(inputs[i]);
    }
  }

  function cssEsc(s) {
    return String(s).replace(/["\\\]]/g, "\\$&");
  }

  function initDrawer() {
    var toggle = document.getElementById("nav-toggle");
    if (!toggle) { return; }
    var links = document.querySelectorAll(".side .navlink");
    for (var i = 0; i < links.length; i++) {
      links[i].addEventListener("click", function () { toggle.checked = false; });
    }
  }

  /* ---- Ouverture ciblée depuis /diagnostics (?focus=<id>&fix=<create|edit|link>) ---- */
  function initFocus() {
    var params;
    try { params = new URLSearchParams(window.location.search); } catch (e) { return; }
    var focus = params.get("focus");
    if (!focus) { return; }
    var item = document.querySelector('[data-res-id="' + cssEsc(focus) + '"]');
    if (!item) { return; }
    var body = item.querySelector(".accordion-collapse");
    if (body) { body.classList.add("show"); }
    var head = item.querySelector(".accordion-button");
    if (head) { head.classList.remove("collapsed"); head.setAttribute("aria-expanded", "true"); }
    var fix = params.get("fix");
    if (fix) {
      var form = item.querySelector('.collapse[id$="-f-' + fix.replace(/[^a-z]/gi, "") + '"]');
      if (form) { form.classList.add("show"); }
    }
    if (item.scrollIntoView) { item.scrollIntoView({ block: "center" }); }
    item.classList.add("res-focused");
  }

  /* ---- Éditeur guidé de quêtes / stories (#46) ------------------------------------- */

  /**
   * Liste déroulante RECHERCHABLE (progressive) pour les champs à <datalist> de l'éditeur.
   * Sans JavaScript, l'<input list> natif reste utilisable ; avec, on remplace la datalist
   * native par un panneau filtré au clavier / à la souris, largeur alignée sur le champ,
   * hauteur bornée, repli au-dessus si le bas du viewport manque de place.
   */
  function initCombo() {
    var combos = document.querySelectorAll(".combo[data-combo]");
    for (var i = 0; i < combos.length; i++) {
      setupCombo(combos[i]);
    }
  }

  function setupCombo(box) {
    var input = box.querySelector("input.combo-input");
    if (!input || input.getAttribute("data-combo-ready") === "1") { return; }
    var listId = input.getAttribute("list");
    var dl = listId ? document.getElementById(listId) : null;
    if (!dl) { return; }
    input.setAttribute("data-combo-ready", "1");

    var options = [];
    var opts = dl.querySelectorAll("option");
    for (var k = 0; k < opts.length; k++) {
      var v = opts[k].getAttribute("value") || "";
      if (!v) { continue; }
      var lbl = opts[k].getAttribute("label") || opts[k].textContent || "";
      options.push({ value: v, label: lbl && lbl !== v ? lbl : "" });
    }
    // On retire la datalist native pour ne pas cumuler deux menus ; les données restent ici.
    input.removeAttribute("list");

    var menu = document.createElement("ul");
    menu.className = "combo-menu";
    menu.setAttribute("role", "listbox");
    menu.hidden = true;
    box.appendChild(menu);

    var active = -1;
    var visible = [];

    function render() {
      var q = (input.value || "").trim().toLowerCase();
      visible = [];
      for (var n = 0; n < options.length && visible.length < 60; n++) {
        var o = options[n];
        if (!q || o.value.toLowerCase().indexOf(q) !== -1
            || (o.label && o.label.toLowerCase().indexOf(q) !== -1)) {
          visible.push(o);
        }
      }
      if (visible.length === 0) {
        menu.innerHTML = '<li class="combo-empty" aria-disabled="true">Aucune correspondance</li>';
      } else {
        var html = "";
        for (var m = 0; m < visible.length; m++) {
          html += '<li role="option" data-idx="' + m + '"><span class="combo-v">'
            + esc(visible[m].value) + "</span>"
            + (visible[m].label ? '<span class="combo-l">' + esc(visible[m].label) + "</span>" : "")
            + "</li>";
        }
        menu.innerHTML = html;
      }
      active = -1;
      place();
    }

    function place() {
      menu.hidden = false;
      menu.classList.remove("up");
      var r = input.getBoundingClientRect();
      var below = window.innerHeight - r.bottom;
      if (below < 240 && r.top > below) { menu.classList.add("up"); }
      input.setAttribute("aria-expanded", "true");
    }

    function close() {
      menu.hidden = true;
      active = -1;
      input.setAttribute("aria-expanded", "false");
    }

    function choose(idx) {
      if (idx < 0 || idx >= visible.length) { return; }
      input.value = visible[idx].value;
      // « change » d'abord (aucun écouteur ne rouvre le menu), puis fermeture — on ne redéclenche
      // pas « input », qui relancerait render() et rouvrirait la liste juste après la sélection.
      try { input.dispatchEvent(new Event("change", { bubbles: true })); } catch (e) { /* ignore */ }
      close();
    }

    function highlight(next) {
      var lis = menu.querySelectorAll("li[role=option]");
      if (lis.length === 0) { return; }
      active = (next + lis.length) % lis.length;
      for (var a = 0; a < lis.length; a++) { lis[a].classList.toggle("on", a === active); }
      lis[active].scrollIntoView({ block: "nearest" });
    }

    input.addEventListener("focus", render);
    input.addEventListener("input", render);
    input.addEventListener("keydown", function (ev) {
      if (menu.hidden && (ev.key === "ArrowDown" || ev.key === "ArrowUp")) { render(); return; }
      if (ev.key === "ArrowDown") { ev.preventDefault(); highlight(active + 1); }
      else if (ev.key === "ArrowUp") { ev.preventDefault(); highlight(active - 1); }
      else if (ev.key === "Enter" && active >= 0) { ev.preventDefault(); choose(active); }
      else if (ev.key === "Escape") { close(); }
    });
    menu.addEventListener("mousedown", function (ev) {
      var li = ev.target.closest ? ev.target.closest("li[role=option]") : null;
      if (li) { ev.preventDefault(); choose(parseInt(li.getAttribute("data-idx"), 10)); }
    });
    input.addEventListener("blur", function () { window.setTimeout(close, 120); });
  }

  /**
   * Un type d'objectif / récompense = un seul jeu de champs visible. Le serveur émet tous les
   * jeux (chacun issu du même descripteur) ; ici on bascule au changement de <select>, sans
   * recharger la page. Les champs masqués sont désactivés (jamais soumis) et vidés (jamais de
   * valeur d'un type précédent conservée en douce).
   */
  function initEditorForms() {
    var selects = document.querySelectorAll("select[data-type-select]");
    for (var i = 0; i < selects.length; i++) {
      (function (sel) {
        sel.addEventListener("change", function () { applyType(sel); });
      })(selects[i]);
    }
    // Filet anti « retour en haut de page » : si l'ancre de destination a disparu (suppression),
    // on restaure au moins la position de défilement précédente.
    var form = document.querySelector("form.editor");
    if (form) {
      var key = "pa-editor-scroll:" + window.location.pathname;
      form.addEventListener("submit", function () {
        try { window.sessionStorage.setItem(key, String(window.scrollY)); } catch (e) { /* ignore */ }
      });
      if (!window.location.hash) {
        try {
          var y = window.sessionStorage.getItem(key);
          if (y !== null) { window.scrollTo(0, parseInt(y, 10) || 0); }
        } catch (e) { /* ignore */ }
      }
      try { window.sessionStorage.removeItem(key); } catch (e) { /* ignore */ }
    }
  }

  function applyType(sel) {
    var row = sel.closest ? sel.closest(".rowitem") : null;
    if (!row) { return; }
    var chosen = sel.value;
    var groups = row.querySelectorAll(".type-fields[data-kind]");
    for (var g = 0; g < groups.length; g++) {
      var grp = groups[g];
      var on = grp.getAttribute("data-kind") === chosen;
      grp.hidden = !on;
      var fields = grp.querySelectorAll("input, select, textarea");
      for (var f = 0; f < fields.length; f++) {
        fields[f].disabled = !on;
        if (!on) { fields[f].value = ""; }
      }
    }
    var was = row.querySelector("input[data-was]");
    if (was) { was.value = chosen; }
  }

  function init() {
    initToasts();       // affiche les toasts (repli manuel si Bootstrap JS pas encore là)
    initNotifications();
    initCopy();
    initFilters();
    initFocus();
    initCombo();
    initEditorForms();
    initDrawer();
    // Filet de sécurité : au cas où Bootstrap JS finirait de charger après nous, on
    // « promeut » les toasts encore affichés manuellement en vraies instances Bootstrap.
    window.addEventListener("load", upgradeToasts);
  }

  function upgradeToasts() {
    if (!(window.bootstrap && window.bootstrap.Toast)) { return; }
    var root = document.getElementById("toast-root");
    if (!root) { return; }
    var live = root.querySelectorAll(".pa-toast.show");
    for (var i = 0; i < live.length; i++) {
      window.bootstrap.Toast.getOrCreateInstance(live[i]);
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
