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
 *   - initOpsAnnounce()   : compteur, aperçu et modèles rapides de l'annonce globale (#95) ;
 *   - initOpsConsole()    : console de logs en lecture seule — recherche, filtres de niveau,
 *                           pause/reprise, suivi automatique, retour en bas (#95) ;
 *   - initOpsState()      : rafraîchit l'état du serveur et suit une opération de redémarrage
 *                           jusqu'à son terme, bloc par bloc, sans jamais recharger la page (#95) ;
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

  var pendingToasts = []; // { el, actionId, sawPending }

  /**
   * Réconciliation mutualisée après mutation (issues #112 / #115 / #116 / #119 / #120).
   * Quand une action suivie qu'on a VUE s'exécuter (pending -> succès) pendant la vie de la page
   * se termine, la vue métier rendue par le serveur peut être périmée (catalogue PNJ / dialogues).
   * On programme alors UN rechargement, déclenché dès que la file d'actions est retombée au repos
   * (les relevés `*.list` ré-enfilés côté serveur sont donc pris en compte). Aucun `location.reload()`
   * dispersé par bouton : un seul mécanisme, pour toutes les pages métier.
   */
  var reloadPlan = { armed: false, deadline: 0, ids: [] };

  function reloadedActionIds() {
    try { return (window.sessionStorage.getItem("pa-reloaded-actions") || "").split(",").filter(Boolean); }
    catch (e) { return []; }
  }
  function markReloadedFor(ids) {
    try {
      var arr = reloadedActionIds();
      for (var i = 0; i < ids.length; i++) { if (arr.indexOf(ids[i]) === -1) { arr.push(ids[i]); } }
      while (arr.length > 30) { arr.shift(); }
      window.sessionStorage.setItem("pa-reloaded-actions", arr.join(","));
    } catch (e) { /* pas de sessionStorage : le garde `sawPending` suffit à éviter une boucle */ }
  }
  function alreadyReloadedFor(id) {
    return reloadedActionIds().indexOf(id) !== -1;
  }
  /**
   * Une vue qui se réconcilie TOUTE SEULE ne doit jamais être rechargée : la recharger
   * détruirait une saisie en cours. C'est le cas de /ops (correctif #95), dont chaque bloc —
   * état, console, carte d'opération, résultats d'action — a déjà son propre rafraîchissement
   * ciblé. Le marqueur est posé par le serveur sur la page.
   */
  function selfRefreshingView() {
    return !!document.querySelector("[data-pa-selfrefresh]");
  }

  function runReload() {
    // Les identifiants sont marqués AVANT toute sortie : sans cela, la vue se réarmerait au
    // prochain cycle et on retomberait dans une boucle.
    if (reloadPlan.ids.length) { markReloadedFor(reloadPlan.ids); }
    reloadPlan.armed = false;
    if (selfRefreshingView()) { return; }
    try { window.location.reload(); } catch (e) { window.location.href = window.location.href; }
  }

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
        pendingToasts.push({ el: el, actionId: actionId, sawPending: false });
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

  /**
   * Drapeaux de résultat à USAGE UNIQUE portés par l'URL après une redirection POST :
   * `ok` / `err` (résultat synchrone) et `toast` (action agent à suivre).
   */
  var ONE_SHOT_FLAGS = ["ok", "err", "toast"];

  /**
   * Les retire de l'URL une fois le toast rendu (correctif #95).
   *
   * <p>Ces drapeaux décrivent « ce qui vient de se passer ». Les laisser dans l'URL les rend
   * éternels : tout rechargement — F5, retour arrière, ou le rechargement automatique qui vient
   * d'être supprimé — réaffiche le même résultat comme s'il venait d'arriver. On remplace donc
   * l'entrée d'historique SANS recharger : le toast déjà rendu reste à l'écran et continue d'être
   * suivi par son identifiant d'action, mais il ne peut plus ressusciter. Une nouvelle action
   * produit sa propre redirection, donc son propre drapeau, donc sa propre notification.</p>
   *
   * <p>Ce n'est pas une protection contre un rejeu : aucune soumission n'est renvoyée ici. Le
   * drapeau ne fait que décrire un POST déjà traité ; le retirer supprime la répétition
   * <em>visuelle</em>, et l'historique des actions reste la preuve qu'une seule action a bien été
   * exécutée.</p>
   */
  function dropOneShotResultFlags() {
    try {
      if (!window.history || !window.history.replaceState || !window.location.search) { return; }
      var params = new URLSearchParams(window.location.search);
      var found = false;
      for (var i = 0; i < ONE_SHOT_FLAGS.length; i++) {
        if (params.has(ONE_SHOT_FLAGS[i])) { params.delete(ONE_SHOT_FLAGS[i]); found = true; }
      }
      if (!found) { return; }
      var query = params.toString();
      window.history.replaceState(null, "",
        window.location.pathname + (query ? "?" + query : "") + window.location.hash);
    } catch (e) { /* URL non manipulable : le toast reste, sans plus de dégât */ }
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
      list.innerHTML = actions.filter(function (a) { return !a.auto; }).slice(0, 8).map(function (a) {
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
          if (match && match.group === "pending") {
            entry.sawPending = true;
            stillPending = true;
          } else if (match) {
            updateToast(entry, match);
            // Vue pending -> succès pendant cette page : la vue serveur peut être périmée.
            // Sauf sur une vue qui se réconcilie seule (/ops) : y armer un rechargement
            // détruirait la saisie en cours, et ses blocs se remettent déjà à jour tout seuls.
            if (entry.sawPending && match.group === "success" && !selfRefreshingView()
                && !alreadyReloadedFor(entry.actionId)) {
              reloadPlan.armed = true;
              reloadPlan.deadline = Date.now() + 30000;
              if (reloadPlan.ids.indexOf(entry.actionId) === -1) { reloadPlan.ids.push(entry.actionId); }
            }
            pendingToasts.splice(i, 1);
          }
        }

        if (reloadPlan.armed) {
          var idle = typeof data.pending === "number" ? data.pending === 0 : !stillPending;
          if (idle || Date.now() > reloadPlan.deadline) { runReload(); return; }
          schedule(FAST_MS);
          return;
        }
        schedule(stillPending ? FAST_MS : SLOW_MS);
      }).catch(function () {
        schedule(SLOW_MS);
      });
    }

    tick();
  }

  /* ---- Exploitation serveur : annonce (#95) ------------------------------------------ */

  /**
   * Compteur de caractères, aperçu fidèle et modèles rapides. L'aperçu rend le message en
   * TEXTE : c'est exactement ce que le serveur enverra (aucune mise en forme interprétée),
   * donc l'aperçu ne peut pas promettre un rendu que le jeu n'affichera pas.
   */
  function initOpsAnnounce() {
    var form = document.querySelector("[data-ops-announce]");
    if (!form) { return; }
    var input = form.querySelector("[data-ops-message]");
    var count = form.querySelector("[data-ops-count]");
    var body = form.querySelector("[data-ops-preview-body]");
    var preview = form.querySelector("[data-ops-preview]");
    if (!input) { return; }

    function refresh() {
      var value = input.value || "";
      if (count) { count.textContent = String(value.length); }
      if (body) { body.textContent = value.length ? value : "…"; }
      if (preview) {
        var channel = form.querySelector("[data-ops-channel]:checked");
        preview.setAttribute("data-channel", channel ? channel.value : "chat");
      }
    }

    input.addEventListener("input", refresh);
    var channels = form.querySelectorAll("[data-ops-channel]");
    for (var c = 0; c < channels.length; c++) {
      channels[c].addEventListener("change", refresh);
    }
    var templates = form.querySelectorAll("[data-ops-template]");
    for (var t = 0; t < templates.length; t++) {
      templates[t].addEventListener("click", function (event) {
        input.value = event.currentTarget.getAttribute("data-ops-template") || "";
        refresh();
        input.focus();
      });
    }
    // Anti double-clic : le bouton se désarme à la soumission. Le serveur refuse déjà une
    // seconde annonce trop rapprochée, mais l'utilisateur ne doit pas avoir à le découvrir.
    form.addEventListener("submit", function () {
      var button = form.querySelector("button[type=submit]");
      if (button) {
        button.disabled = true;
        button.textContent = "Envoi…";
      }
    });
    refresh();
  }

  /* ---- Exploitation serveur : console de logs (#95) ---------------------------------- */

  /**
   * Console en lecture seule. Le flux vient de /ops/logs.json, alimenté par l'agent sortant :
   * aucun WebSocket, aucun SSE, aucun port entrant côté serveur de jeu. Recherche et filtres de
   * niveau s'appliquent à ce qui est déjà reçu (filtrage local, immédiat, sans requête).
   */
  function initOpsConsole() {
    var box = document.querySelector("[data-ops-console]");
    if (!box) { return; }
    var status = document.querySelector("[data-ops-console-status]");
    var search = document.querySelector("[data-ops-search]");
    var pauseBtn = document.querySelector("[data-ops-pause]");
    var autoBtn = document.querySelector("[data-ops-autoscroll]");
    var bottomBtn = document.querySelector("[data-ops-bottom]");
    var levelBtns = document.querySelectorAll("[data-ops-level]");

    var MAX_LINES = 1000;
    var POLL_MS = 4000;
    var lines = [];            // { seq, time, level, source, message }
    var cursor = 0;
    var paused = false;
    var autoscroll = true;
    var levels = { ERROR: true, WARN: true, INFO: true, DEBUG: false };
    var timer = null;
    var ticks = 0;

    function matches(line) {
      if (!levels[line.level]) { return false; }
      var needle = search && search.value ? search.value.toLowerCase() : "";
      if (!needle) { return true; }
      return (line.message + " " + line.source).toLowerCase().indexOf(needle) >= 0;
    }

    function render() {
      var out = [];
      for (var i = 0; i < lines.length; i++) {
        if (!matches(lines[i])) { continue; }
        out.push('<span class="ops-l ops-l--' + esc(lines[i].level) + '">'
          + '<span class="ops-t">' + esc(lines[i].time) + "</span> "
          + '<span class="ops-lv">' + esc(lines[i].level) + "</span> "
          + '<span class="ops-src">' + esc(lines[i].source) + "</span> "
          + esc(lines[i].message) + "</span>");
      }
      box.innerHTML = out.join("\n");
      if (autoscroll && !paused) { box.scrollTop = box.scrollHeight; }
    }

    function setStatus(text, kind) {
      if (!status) { return; }
      status.textContent = text;
      status.className = "muted mb-1" + (kind ? " " + kind : "");
    }

    function hhmmss(epochMs) {
      var d = new Date(epochMs);
      function two(n) { return (n < 10 ? "0" : "") + n; }
      return two(d.getHours()) + ":" + two(d.getMinutes()) + ":" + two(d.getSeconds());
    }

    function tick() {
      ticks += 1;
      if (ticks > 900) { setStatus("Flux arrêté (durée maximale atteinte) — recharger la page."); return; }
      if (paused) { timer = window.setTimeout(tick, POLL_MS); return; }
      fetch("/ops/logs.json?after=" + encodeURIComponent(cursor), {
        headers: { "Accept": "application/json" }, credentials: "same-origin"
      }).then(function (res) {
        if (res.status === 401 || res.status === 403) { return null; }
        if (!res.ok) { throw new Error("HTTP " + res.status); }
        return res.json();
      }).then(function (data) {
        if (!data) { setStatus("Console non autorisée pour ce rôle."); return; }
        if (data.unavailable) {
          setStatus(data.unavailable, "text-warning");
        } else {
          var added = 0;
          for (var i = 0; data.lines && i < data.lines.length; i++) {
            var raw = data.lines[i];
            lines.push({
              seq: raw.seq, time: hhmmss(raw.at), level: String(raw.level || "INFO"),
              source: String(raw.source || ""), message: String(raw.message || "")
            });
            if (raw.seq > cursor) { cursor = raw.seq; }
            added += 1;
          }
          if (lines.length > MAX_LINES) { lines = lines.slice(lines.length - MAX_LINES); }
          if (added > 0) { render(); }
          var parts = [];
          parts.push(lines.length + " ligne(s) en mémoire");
          if (data.age) { parts.push("relevé " + data.age); }
          if (data.gap) { parts.push("⚠ des lignes ont été perdues entre deux relevés"); }
          if (data.pending) { parts.push("relevé suivant demandé…"); }
          setStatus(parts.join(" · "), data.gap ? "text-warning" : "");
        }
        timer = window.setTimeout(tick, POLL_MS);
      }).catch(function () {
        setStatus("Console momentanément injoignable — nouvelle tentative…", "text-warning");
        timer = window.setTimeout(tick, POLL_MS * 2);
      });
    }

    if (search) { search.addEventListener("input", render); }
    for (var l = 0; l < levelBtns.length; l++) {
      levelBtns[l].addEventListener("click", function (event) {
        var btn = event.currentTarget;
        var level = btn.getAttribute("data-ops-level");
        levels[level] = !levels[level];
        btn.classList.toggle("on", levels[level]);
        render();
      });
    }
    if (pauseBtn) {
      pauseBtn.addEventListener("click", function () {
        paused = !paused;
        pauseBtn.classList.toggle("on", paused);
        pauseBtn.textContent = paused ? "Reprendre" : "Pause";
        setStatus(paused ? "Flux en pause — les lignes continuent de s'accumuler côté serveur."
          : "Flux repris.");
      });
    }
    if (autoBtn) {
      autoBtn.addEventListener("click", function () {
        autoscroll = !autoscroll;
        autoBtn.classList.toggle("on", autoscroll);
      });
    }
    if (bottomBtn) {
      bottomBtn.addEventListener("click", function () {
        autoscroll = true;
        if (autoBtn) { autoBtn.classList.add("on"); }
        box.scrollTop = box.scrollHeight;
      });
    }
    // Scroller vers le haut à la main coupe le suivi : sinon la lecture est impossible.
    box.addEventListener("scroll", function () {
      var atBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 24;
      if (!atBottom && autoscroll) {
        autoscroll = false;
        if (autoBtn) { autoBtn.classList.remove("on"); }
      }
    });
    tick();
  }

  /* ---- Exploitation serveur : état et suivi d'opération (#95) ------------------------- */

  /**
   * Rafraîchit EN PLACE le bloc « État du serveur » et, quand une opération de redémarrage est
   * en cours, sa carte de suivi — l'administrateur voit l'arrêt puis le retour en ligne sans F5.
   *
   * <p>CORRECTIF #95 — les deux bugs reproduits avaient la MÊME cause : cette fonction
   * rechargeait TOUTE la page dès que le serveur répondait {@code terminal: true}. Elle démarrait
   * aussi quand l'opération était déjà terminale au chargement (DONE / FAILED / CANCELLED /
   * IDLE) : état demandé → « terminal » → rechargement → la page revenait avec la même carte
   * terminale → rechargement, toutes les 3 secondes, sans fin. D'où (1) le texte d'annonce vidé
   * et les cases décochées en pleine saisie et (2) la notification de résultat qui réapparaissait
   * indéfiniment — c'était ce rechargement qui la re-rendait depuis {@code ?ok=} dans l'URL.</p>
   *
   * <p>Règles qui tiennent maintenant :</p>
   * <ul>
   *   <li><strong>aucun rechargement de page</strong> ici, dans aucune branche : seuls des
   *       conteneurs SANS formulaire sont remplacés, donc message, cases, canal, focus et aperçu
   *       survivent à autant de cycles que nécessaire ;</li>
   *   <li>cadence lente par défaut (le bloc d'état décrit un relevé reçu toutes les ~20 s),
   *       rapide seulement pendant une opération active, où chaque seconde compte ;</li>
   *   <li>la carte de suivi n'est remplacée que si le serveur en renvoie une : une opération
   *       retombée à IDLE ne laisse pas un conteneur vide à la place du dernier état connu.</li>
   * </ul>
   */
  function initOpsState() {
    var stateBox = document.querySelector("[data-ops-state]");
    var card = document.querySelector("[data-ops-operation]");
    if (!stateBox && !card) { return; }

    var FAST = 3000;
    var SLOW = 20000;
    var MAX = 900;
    var ticks = 0;

    // « true » explicite : un attribut absent (page servie par une version antérieure) est traité
    // comme NON terminal — on surveille, ce qui reste sans danger puisque plus rien ne recharge.
    function cardActive() {
      var el = document.querySelector("[data-ops-operation]");
      return !!el && el.getAttribute("data-ops-terminal") !== "true";
    }

    function tick() {
      ticks += 1;
      if (ticks > MAX) { return; }
      fetch("/ops/state.json" + (window.location.search || ""), {
        headers: { "Accept": "application/json" }, credentials: "same-origin"
      }).then(function (res) { return res.ok ? res.json() : null; })
        .then(function (data) {
          if (!data) { return; }
          if (stateBox && typeof data.stateHtml === "string" && data.stateHtml) {
            stateBox.innerHTML = data.stateHtml;
          }
          var current = document.querySelector("[data-ops-operation]");
          if (current && data.html) { current.outerHTML = data.html; }
          window.setTimeout(tick, cardActive() ? FAST : SLOW);
        }).catch(function () { window.setTimeout(tick, SLOW); });
    }

    window.setTimeout(tick, cardActive() ? FAST : SLOW);
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
      options.push({
        value: v,
        label: lbl && lbl !== v ? lbl : "",
        // #196 : marques portées par l'option elle-même. « noitem » = existe comme bloc mais pas
        // comme objet (ni icône, ni récompense possible) ; « creative » = objet réel mais non
        // obtenable en jeu normal, donc douteux en récompense. Ce sont des avertissements : rien
        // n'est retiré de la liste.
        noItem: opts[k].getAttribute("data-noitem") === "1",
        creative: opts[k].getAttribute("data-creative") === "1"
      });
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

    // Nombre d'entrées rendues d'un coup. Borné pour ne pas construire mille lignes de DOM à
    // chaque frappe — mais la borne est DITE et extensible (voir « shown »), sinon des résultats
    // disparaissent en silence. C'était le cas avant #196 : la liste s'arrêtait à 60 sans le
    // signaler, donc une recherche large semblait n'avoir que 60 réponses.
    var PAGE = 100;
    var shown = PAGE;

    function matches(o, q) {
      if (!q) { return true; }
      // Recherche sur l'identifiant ET sur le libellé : « sword » comme « épée ».
      return o.value.toLowerCase().indexOf(q) !== -1
        || (o.label && o.label.toLowerCase().indexOf(q) !== -1);
    }

    function render() {
      var q = (input.value || "").trim().toLowerCase();
      visible = [];
      var total = 0;
      for (var n = 0; n < options.length; n++) {
        if (!matches(options[n], q)) { continue; }
        total++;
        if (visible.length < shown) { visible.push(options[n]); }
      }
      if (total === 0) {
        menu.innerHTML = '<li class="combo-empty" aria-disabled="true">Aucune correspondance</li>';
      } else {
        var html = "";
        for (var m = 0; m < visible.length; m++) {
          var o = visible[m];
          var note = o.noItem ? "aucune forme d'objet" : (o.creative ? "créatif / technique" : "");
          html += '<li role="option" data-idx="' + m + '"'
            + (o.noItem ? ' class="combo-noitem"' : "")
            + '><span class="combo-v">' + esc(o.value) + "</span>"
            + (o.label ? '<span class="combo-l">' + esc(o.label) + "</span>" : "")
            + (note ? '<span class="combo-note">' + esc(note) + "</span>" : "")
            + "</li>";
        }
        if (total > visible.length) {
          // Résultats restants annoncés et atteignables : jamais perdus en silence.
          html += '<li class="combo-more" data-combo-more="1">'
            + visible.length + " sur " + total + " affichés — afficher "
            + Math.min(PAGE, total - visible.length) + " de plus</li>";
        } else if (total > PAGE) {
          html += '<li class="combo-count" aria-disabled="true">' + total + " résultats</li>";
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

    function showMore() {
      shown += PAGE;
      render();
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
    input.addEventListener("input", function () {
      // Nouvelle recherche = nouvelle fenêtre : sinon un « afficher plus » resterait étendu et
      // rendrait inutilement mille lignes à la frappe suivante.
      shown = PAGE;
      render();
    });
    input.addEventListener("keydown", function (ev) {
      if (menu.hidden && (ev.key === "ArrowDown" || ev.key === "ArrowUp")) { render(); return; }
      if (ev.key === "ArrowDown") { ev.preventDefault(); highlight(active + 1); }
      else if (ev.key === "ArrowUp") { ev.preventDefault(); highlight(active - 1); }
      else if (ev.key === "Enter" && active >= 0) { ev.preventDefault(); choose(active); }
      else if (ev.key === "Escape") { close(); }
    });
    menu.addEventListener("mousedown", function (ev) {
      var more = ev.target.closest ? ev.target.closest("[data-combo-more]") : null;
      if (more) {
        // Étendre la fenêtre, sans fermer le menu ni perdre le focus du champ.
        ev.preventDefault();
        showMore();
        return;
      }
      var li = ev.target.closest ? ev.target.closest("li[role=option]") : null;
      if (li) { ev.preventDefault(); choose(parseInt(li.getAttribute("data-idx"), 10)); }
    });
    input.addEventListener("blur", function () { window.setTimeout(close, 120); });
  }

  /* ---- Sélection MULTIPLE recherchable (#163) --------------------------------------- */

  /**
   * Transforme un champ `[data-multisel]` (prérequis de quête, …) en liste de puces
   * supprimables + champ de recherche, alimenté par la `<datalist>` désignée par
   * `data-multisel-list`.
   *
   * Contrat volontairement conservateur :
   *   - la <textarea> `[data-multisel-store]` reste LE champ soumis (une valeur par ligne) :
   *     le format attendu par le serveur ne change pas, et sans JavaScript la page reste
   *     utilisable telle quelle (la textarea s'affiche simplement normalement) ;
   *   - une valeur absente du catalogue n'est JAMAIS supprimée : elle devient une puce
   *     signalée « inconnue », pour que l'admin la voie au lieu de la perdre ;
   *   - aucun doublon : comparaison sur l'id « nu » (préfixe `rpgquest:` ignoré), comme le
   *     fait le validateur côté serveur.
   */
  function initMultiSel() {
    var boxes = document.querySelectorAll("[data-multisel]");
    for (var i = 0; i < boxes.length; i++) { setupMultiSel(boxes[i]); }
  }

  function plainId(v) {
    var s = String(v == null ? "" : v).trim();
    var c = s.indexOf(":");
    return (c >= 0 ? s.substring(c + 1) : s).toLowerCase();
  }

  function setupMultiSel(box) {
    var store = box.querySelector("textarea[data-multisel-store]");
    if (!store || store.getAttribute("data-multisel-ready") === "1") { return; }
    var dl = document.getElementById(box.getAttribute("data-multisel-list") || "");
    if (!dl) { return; } // pas de catalogue : on laisse la textarea brute, jamais de régression
    store.setAttribute("data-multisel-ready", "1");

    var options = [];
    var byPlain = {};
    var opts = dl.querySelectorAll("option");
    for (var k = 0; k < opts.length; k++) {
      var v = opts[k].getAttribute("value") || "";
      if (!v) { continue; }
      var lbl = opts[k].getAttribute("label") || "";
      var o = {
        value: v,
        label: lbl && lbl !== v ? lbl : "",
        origin: opts[k].getAttribute("data-origin") || ""
      };
      options.push(o);
      byPlain[plainId(v)] = o;
    }

    // Le séparateur dépend du contrat de l'action cible : « \n » pour les prérequis de quête
    // (#163), « , » pour les mondes/biomes d'un profil de mob (#172). On ne change jamais le
    // format attendu par le serveur.
    var sep = box.getAttribute("data-multisel-separator") || "\n";
    var chosen = [];
    var lines = (store.value || "").split(sep === "," ? /[,\n]/ : "\n");
    for (var l = 0; l < lines.length; l++) {
      var t = lines[l].trim();
      if (t && chosen.indexOf(t) === -1) { chosen.push(t); }
    }

    // La textarea reste dans le DOM (c'est elle qui est soumise) mais sort du flux visuel
    // et du parcours clavier : deux champs concurrents pour la même donnée seraient pires
    // que pas de widget du tout.
    store.classList.add("multisel-store");
    store.setAttribute("tabindex", "-1");
    store.setAttribute("aria-hidden", "true");

    var chips = document.createElement("div");
    chips.className = "multisel-chips";
    var combo = document.createElement("div");
    combo.className = "combo multisel-combo";
    var input = document.createElement("input");
    input.type = "text";
    input.className = "combo-input";
    input.setAttribute("placeholder", box.getAttribute("data-multisel-add") || "Ajouter…");
    input.setAttribute("role", "combobox");
    input.setAttribute("aria-expanded", "false");
    input.setAttribute("aria-autocomplete", "list");
    var lbl2 = box.querySelector("label");
    if (lbl2) {
      // Le <label for> pointe sur la textarea masquée ; on rattache le champ visible au même
      // intitulé pour que le lecteur d'écran annonce bien « Quêtes prérequises ».
      input.setAttribute("aria-label", (lbl2.textContent || "").trim());
    }
    var menu = document.createElement("ul");
    menu.className = "combo-menu";
    menu.setAttribute("role", "listbox");
    menu.hidden = true;
    combo.appendChild(input);
    combo.appendChild(menu);
    store.parentNode.insertBefore(chips, store);
    store.parentNode.insertBefore(combo, store.nextSibling);

    var active = -1;
    var visible = [];

    function sync() {
      store.value = chosen.join(sep === "," ? ", " : "\n");
      try { store.dispatchEvent(new Event("change", { bubbles: true })); } catch (e) { /* ignore */ }
    }

    function renderChips() {
      if (chosen.length === 0) {
        chips.innerHTML = '<span class="multisel-none">'
          + (box.getAttribute("data-multisel-empty") || "Aucune valeur sélectionnée.") + "</span>";
        return;
      }
      var html = "";
      for (var c = 0; c < chosen.length; c++) {
        var known = byPlain[plainId(chosen[c])];
        var title = known && known.label ? known.label : chosen[c];
        html += '<span class="chip' + (known ? "" : " chip-unknown") + '">'
          + '<span class="chip-t">' + esc(title) + "</span>"
          + '<code class="chip-id">' + esc(chosen[c]) + "</code>"
          + (known && known.origin ? '<span class="chip-o">' + esc(known.origin) + "</span>" : "")
          + (known ? "" : '<span class="chip-o">inconnue du catalogue</span>')
          + '<button type="button" class="chip-x" data-multisel-del="' + esc(chosen[c])
          + '" aria-label="Retirer ' + esc(chosen[c]) + '">&times;</button>'
          + "</span>";
      }
      chips.innerHTML = html;
    }

    function render() {
      var q = (input.value || "").trim().toLowerCase();
      visible = [];
      for (var n = 0; n < options.length && visible.length < 60; n++) {
        var o = options[n];
        if (chosen.some(function (ch) { return plainId(ch) === plainId(o.value); })) { continue; }
        if (!q || o.value.toLowerCase().indexOf(q) !== -1
            || (o.label && o.label.toLowerCase().indexOf(q) !== -1)) {
          visible.push(o);
        }
      }
      if (visible.length === 0) {
        menu.innerHTML = '<li class="combo-empty" aria-disabled="true">'
          + (q ? "Aucune correspondance" : "Toutes les quêtes connues sont déjà sélectionnées")
          + "</li>";
      } else {
        var html = "";
        for (var m = 0; m < visible.length; m++) {
          html += '<li role="option" data-idx="' + m + '">'
            + (visible[m].label ? '<span class="combo-l">' + esc(visible[m].label) + "</span>" : "")
            + '<span class="combo-v">' + esc(visible[m].value) + "</span>"
            + (visible[m].origin ? '<span class="combo-o">' + esc(visible[m].origin) + "</span>" : "")
            + "</li>";
        }
        menu.innerHTML = html;
      }
      active = -1;
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

    function add(value) {
      var v = String(value || "").trim();
      if (!v) { return; }
      if (chosen.some(function (ch) { return plainId(ch) === plainId(v); })) { return; }
      chosen.push(v);
      sync();
      renderChips();
      input.value = "";
      render();
    }

    function highlight(next) {
      var lis = menu.querySelectorAll("li[role=option]");
      if (lis.length === 0) { return; }
      active = (next + lis.length) % lis.length;
      for (var a = 0; a < lis.length; a++) { lis[a].classList.toggle("on", a === active); }
      lis[active].scrollIntoView({ block: "nearest" });
    }

    input.addEventListener("focus", render);
    input.addEventListener("input", function () {
      // Nouvelle recherche = nouvelle fenêtre : sinon un « afficher plus » resterait étendu et
      // rendrait inutilement mille lignes à la frappe suivante.
      shown = PAGE;
      render();
    });
    input.addEventListener("keydown", function (ev) {
      if (menu.hidden && (ev.key === "ArrowDown" || ev.key === "ArrowUp")) { render(); return; }
      if (ev.key === "ArrowDown") { ev.preventDefault(); highlight(active + 1); }
      else if (ev.key === "ArrowUp") { ev.preventDefault(); highlight(active - 1); }
      else if (ev.key === "Enter") {
        // Jamais de soumission du formulaire depuis ce champ : on ajoute la valeur visée.
        ev.preventDefault();
        if (active >= 0 && active < visible.length) { add(visible[active].value); }
        else if (input.value.trim()) { add(input.value); }
      } else if (ev.key === "Escape") { close(); }
    });
    menu.addEventListener("mousedown", function (ev) {
      var li = ev.target.closest ? ev.target.closest("li[role=option]") : null;
      if (!li) { return; }
      ev.preventDefault();
      var idx = parseInt(li.getAttribute("data-idx"), 10);
      if (idx >= 0 && idx < visible.length) { add(visible[idx].value); }
    });
    input.addEventListener("blur", function () { window.setTimeout(close, 120); });
    chips.addEventListener("click", function (ev) {
      var btn = ev.target.closest ? ev.target.closest("[data-multisel-del]") : null;
      if (!btn) { return; }
      ev.preventDefault();
      var del = btn.getAttribute("data-multisel-del");
      chosen = chosen.filter(function (ch) { return ch !== del; });
      sync();
      renderChips();
      render();
      input.focus();
    });

    renderChips();
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
        // Sync défensive : aligne l'affichage sur la valeur courante dès le chargement, puis à
        // chaque changement — l'utilisateur voit immédiatement les bons champs (#46, §5/§17).
        applyType(sel);
        sel.addEventListener("change", function () { applyType(sel); });
      })(selects[i]);
    }

    var form = document.querySelector("form.editor");
    if (!form) { return; }

    // Conservation de la position après une action de brouillon (#46, §9). Le POST renvoie du HTML
    // 200 : la plupart des navigateurs n'appliquent PAS le fragment d'un formaction dans ce cas, et
    // l'ancre disparue (suppression) laisserait la page en haut. On restaure donc explicitement,
    // au chargement : d'abord la cible du hash si elle existe, sinon la position mémorisée.
    var key = "pa-editor-scroll:" + window.location.pathname;
    form.addEventListener("submit", function () {
      try { window.sessionStorage.setItem(key, String(window.scrollY)); } catch (e) { /* ignore */ }
    });

    var restored = false;
    var hash = (window.location.hash || "").replace(/^#/, "");
    if (hash) {
      var target = null;
      try { target = document.getElementById(hash); } catch (e) { target = null; }
      if (target) {
        try { target.scrollIntoView({ block: "center" }); } catch (e) { target.scrollIntoView(); }
        var focusable = target.querySelector
          ? target.querySelector("input:not([type=hidden]):not([disabled]), select:not([disabled]), textarea:not([disabled])")
          : null;
        if (focusable) { try { focusable.focus({ preventScroll: true }); } catch (e) { /* ignore */ } }
        restored = true;
      }
    }
    if (!restored) {
      try {
        var y = window.sessionStorage.getItem(key);
        if (y !== null) { window.scrollTo(0, parseInt(y, 10) || 0); }
      } catch (e) { /* ignore */ }
    }
    try { window.sessionStorage.removeItem(key); } catch (e) { /* ignore */ }
  }

  /* ---- Palette de couleurs MiniMessage + aperçu (formulaire de dialogue, #118) ------ */


  /* ---- Champ de texte stylé, partagé (#195) ----------------------------------------- */

  var SF_COLORS = {
    black: "#3b3b3b", dark_blue: "#3b5bd6", dark_green: "#2f9e44", dark_aqua: "#22a5a5",
    dark_red: "#c0392b", dark_purple: "#9b59b6", gold: "#d4a017", gray: "#aab1bd",
    dark_gray: "#8a929e", blue: "#5b8dff", green: "#43c463", aqua: "#4bd6d6",
    red: "#f06663", light_purple: "#e06bd6", yellow: "#e3c33b", white: "#e6e8ec"
  };
  var SF_DECO_ALIAS = {
    b: "bold", bold: "bold", i: "italic", italic: "italic", em: "italic",
    u: "underlined", underlined: "underlined",
    st: "strikethrough", s: "strikethrough", strikethrough: "strikethrough"
  };

  /**
   * Analyse une valeur MiniMessage. Renvoie { uniform, text, color, decos } si la valeur est
   * « uniforme » — au plus UNE couleur et des décorations qui englobent tout le texte, sans
   * aucune balise au milieu — sinon { uniform:false }.
   *
   * Cette prudence est volontaire : un texte comme « <red>Roi</red> <gold>des Marais</gold> »
   * ne peut pas être représenté par « une couleur + des cases » sans être aplati. On préfère
   * refuser le mode guidé plutôt que détruire du contenu existant.
   */
  function sfParse(raw) {
    var value = String(raw == null ? "" : raw);
    if (value === "") { return { uniform: true, text: "", color: "", decos: {} }; }
    var color = "";
    var decos = {};
    var rest = value;
    var guard = 0;
    // Balises ouvrantes en tête.
    while (guard++ < 12) {
      var open = /^<([a-zA-Z_#][a-zA-Z0-9_#]*)>/.exec(rest);
      if (!open) { break; }
      var tag = open[1].toLowerCase();
      if (SF_DECO_ALIAS[tag]) {
        decos[SF_DECO_ALIAS[tag]] = true;
      } else if (SF_COLORS[tag]) {
        if (color) { return { uniform: false }; }  // deux couleurs : non uniforme
        color = tag;
      } else {
        return { uniform: false };                 // balise non gérée (hover, gradient, #hex…)
      }
      rest = rest.substring(open[0].length);
    }
    // Balises fermantes en queue (on ne vérifie pas l'appariement exact : toute balise
    // résiduelle au milieu fera échouer le test ci-dessous, ce qui suffit).
    guard = 0;
    while (guard++ < 12) {
      var close = /<\/([a-zA-Z_#][a-zA-Z0-9_#]*)>$/.exec(rest);
      if (!close) { break; }
      rest = rest.substring(0, rest.length - close[0].length);
    }
    if (rest.indexOf("<") !== -1 || rest.indexOf(">") !== -1) {
      return { uniform: false };                   // du balisage subsiste au milieu du texte
    }
    return { uniform: true, text: rest, color: color, decos: decos };
  }

  /** Recompose une valeur MiniMessage à partir du mode guidé. */
  function sfCompose(text, color, decos) {
    if (!text) { return ""; }
    var open = "";
    var close = "";
    if (color) { open += "<" + color + ">"; close = "</" + color + ">" + close; }
    var order = ["bold", "italic", "underlined", "strikethrough"];
    for (var i = 0; i < order.length; i++) {
      if (decos[order[i]]) {
        open += "<" + order[i] + ">";
        close = "</" + order[i] + ">" + close;
      }
    }
    return open + text + close;
  }

  function initStyleFields() {
    var fields = document.querySelectorAll("[data-stylefield]");
    for (var i = 0; i < fields.length; i++) { setupStyleField(fields[i]); }
  }

  function setupStyleField(box) {
    var store = box.querySelector("[data-sf-store]");
    if (!store || store.getAttribute("data-sf-ready") === "1") { return; }
    store.setAttribute("data-sf-ready", "1");

    var guided = box.querySelector("[data-sf-guided]");
    var textInput = box.querySelector("[data-sf-text]");
    var palette = box.querySelector("[data-sf-palette]");
    var preview = box.querySelector("[data-sf-preview]");
    var warn = box.querySelector("[data-sf-warn]");
    var toggle = box.querySelector("[data-sf-toggle]");
    var decoBoxes = box.querySelectorAll("[data-sf-deco]");
    if (!guided || !textInput || !palette) { return; }

    var state = { color: "", decos: {} };
    var mode = "guided";

    function paint() {
      var swatches = palette.querySelectorAll("[data-sf-color]");
      for (var s = 0; s < swatches.length; s++) {
        swatches[s].classList.toggle("on", (swatches[s].getAttribute("data-sf-color") || "") === state.color);
      }
      if (!preview) { return; }
      var shown = mode === "guided" ? textInput.value : String(store.value || "").replace(/<[^>]*>/g, "");
      preview.textContent = shown || "— aperçu —";
      preview.style.color = mode === "guided" && state.color ? (SF_COLORS[state.color] || "") : "";
      preview.style.fontWeight = state.decos.bold && mode === "guided" ? "700" : "";
      preview.style.fontStyle = state.decos.italic && mode === "guided" ? "italic" : "";
      var deco = [];
      if (state.decos.underlined) { deco.push("underline"); }
      if (state.decos.strikethrough) { deco.push("line-through"); }
      preview.style.textDecoration = mode === "guided" && deco.length ? deco.join(" ") : "";
    }

    function sync() {
      if (mode !== "guided") { return; }
      store.value = sfCompose(textInput.value, state.color, state.decos);
      paint();
    }

    function enterGuided(initial) {
      mode = "guided";
      guided.hidden = false;
      store.classList.add("sf-store-hidden");
      if (warn) { warn.hidden = true; }
      if (toggle) {
        toggle.hidden = false;
        toggle.textContent = "Modifier le code MiniMessage";
      }
      if (initial) {
        textInput.value = initial.text || "";
        state.color = initial.color || "";
        state.decos = initial.decos || {};
        for (var d = 0; d < decoBoxes.length; d++) {
          decoBoxes[d].checked = !!state.decos[decoBoxes[d].getAttribute("data-sf-deco")];
        }
      }
      sync();
    }

    function enterAdvanced(reason) {
      mode = "advanced";
      guided.hidden = true;
      store.classList.remove("sf-store-hidden");
      if (warn) {
        warn.hidden = !reason;
        warn.textContent = reason || "";
      }
      if (toggle) {
        toggle.hidden = false;
        toggle.textContent = "Passer à l'éditeur guidé (simplifiera les styles)";
      }
      paint();
    }

    var parsed = sfParse(store.value);
    if (parsed.uniform) {
      enterGuided(parsed);
    } else {
      // Contenu multi-styles : on ne le touche pas, et on dit pourquoi.
      enterAdvanced("Ce texte combine plusieurs styles ou une balise avancée : l'éditeur guidé le "
        + "simplifierait. Il est donc laissé tel quel — modifiable ci-dessus, ou basculez "
        + "explicitement en mode guidé.");
    }

    textInput.addEventListener("input", sync);
    palette.addEventListener("click", function (ev) {
      var sw = ev.target.closest ? ev.target.closest("[data-sf-color]") : null;
      if (!sw) { return; }
      ev.preventDefault();
      state.color = sw.getAttribute("data-sf-color") || "";
      sync();
    });
    for (var d2 = 0; d2 < decoBoxes.length; d2++) {
      decoBoxes[d2].addEventListener("change", function (ev) {
        state.decos[ev.target.getAttribute("data-sf-deco")] = ev.target.checked;
        sync();
      });
    }
    store.addEventListener("input", function () { if (mode === "advanced") { paint(); } });
    if (toggle) {
      toggle.addEventListener("click", function (ev) {
        ev.preventDefault();
        if (mode === "guided") {
          enterAdvanced("");
        } else {
          // Bascule explicite : on repart du texte débarrassé de son balisage.
          var plain = String(store.value || "").replace(/<[^>]*>/g, "");
          enterGuided({ text: plain, color: state.color, decos: state.decos });
        }
      });
    }
  }

  function initColorPalette() {
    var palettes = document.querySelectorAll("[data-dlg-palette]");
    for (var i = 0; i < palettes.length; i++) {
      (function (palette) {
        var wrap = palette.closest ? palette.closest("form") : null;
        var hidden = wrap ? wrap.querySelector("[data-dlg-color]") : null;
        var preview = wrap ? wrap.querySelector("[data-dlg-preview]") : null;
        var textInput = wrap ? wrap.querySelector("[data-dlg-text]") : null;
        var speakerInput = wrap ? wrap.querySelector("[name='speaker']") : null;
        var swatches = palette.querySelectorAll(".dlg-swatch");
        var current = "";
        var currentHex = swatchHex(swatches[0]);

        function swatchHex(sw) {
          if (!sw) { return ""; }
          var m = (sw.getAttribute("style") || "").match(/--sw:\s*([^;]+)/);
          return m ? m[1].trim() : "";
        }
        function refreshPreview() {
          if (!preview) { return; }
          var speaker = speakerInput && speakerInput.value ? speakerInput.value.trim() : "";
          var text = textInput && textInput.value ? textInput.value : "";
          // Un aperçu simple : on retire les balises MiniMessage pour n'afficher que le texte.
          var plain = text.replace(/<[^>]*>/g, "");
          preview.textContent = (speaker ? speaker + " : " : "") + (plain || "—");
          preview.style.color = currentHex || "";
        }
        function select(sw) {
          current = sw.getAttribute("data-color") || "";
          currentHex = swatchHex(sw);
          if (hidden) { hidden.value = current; }
          for (var k = 0; k < swatches.length; k++) {
            var on = swatches[k] === sw;
            swatches[k].classList.toggle("on", on);
            swatches[k].setAttribute("aria-pressed", on ? "true" : "false");
          }
          refreshPreview();
        }
        for (var s = 0; s < swatches.length; s++) {
          (function (sw) { sw.addEventListener("click", function () { select(sw); }); })(swatches[s]);
        }
        if (textInput) { textInput.addEventListener("input", refreshPreview); }
        if (speakerInput) { speakerInput.addEventListener("input", refreshPreview); }
        refreshPreview();
      })(palettes[i]);
    }
  }

  /* ---- Garde anti double-soumission (#165) ------------------------------------------- */

  /*
   * Un double clic sur « Créer le PNJ » envoyait deux requêtes, donc deux actions distinctes.
   * Le serveur les arbitre déjà (création de définition atomique, la seconde est refusée), mais
   * autant ne pas la provoquer : le bouton se désactive dès la première soumission, avec un
   * libellé qui dit ce qui se passe. Sans JavaScript, la garde serveur reste la seule — et elle
   * suffit.
   */
  function initSubmitOnce() {
    var forms = document.querySelectorAll("[data-submit-once]");
    for (var i = 0; i < forms.length; i++) {
      (function (form) {
        form.addEventListener("submit", function () {
          var buttons = form.querySelectorAll("button[type='submit']");
          for (var b = 0; b < buttons.length; b++) {
            var btn = buttons[b];
            if (btn.dataset.busyLabel) { btn.textContent = btn.dataset.busyLabel; }
            btn.disabled = true;
          }
        });
      })(forms[i]);
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

  /** Exécute un module en isolant sa panne : un module qui échoue n'empêche pas les autres. */
  function run(name, fn) {
    try { fn(); } catch (e) {
      if (window.console && window.console.warn) { window.console.warn("panel.js: " + name + " a échoué", e); }
    }
  }

  function init() {
    run("initToasts", initToasts);       // affiche les toasts (repli manuel si Bootstrap JS pas encore là)
    // APRÈS initToasts : la bannière et le toast ont été rendus, on peut retirer le drapeau
    // à usage unique de l'URL pour qu'aucun rechargement ne les fasse revivre (#95).
    run("dropOneShotResultFlags", dropOneShotResultFlags);
    run("initNotifications", initNotifications);
    run("initCopy", initCopy);
    run("initFilters", initFilters);
    run("initFocus", initFocus);
    run("initCombo", initCombo);
    run("initMultiSel", initMultiSel);
    run("initEditorForms", initEditorForms);
    run("initStyleFields", initStyleFields);
    run("initSubmitOnce", initSubmitOnce);
    run("initColorPalette", initColorPalette);
    run("initOpsAnnounce", initOpsAnnounce);
    run("initOpsConsole", initOpsConsole);
    run("initOpsState", initOpsState);
    run("initDrawer", initDrawer);
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
