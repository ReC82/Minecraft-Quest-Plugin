"use strict";
/*
 * RPGQuest Control Panel — script progressif (même origine, conforme CSP `default-src 'self'`,
 * aucune dépendance externe autre que Bootstrap déjà chargé).
 *
 * Modules :
 *   - initToasts()        : affiche les toasts Bootstrap (feedback d'action) et met à jour
 *                           en place un toast d'action « en cours » quand elle se résout, puis
 *                           annonce la mise à jour automatique de l'affichage (#128) ;
 *   - initNotifications() : rafraîchit la cloche de la topbar (badge + liste) par un polling
 *                           léger de /agents/actions.json — accéléré tant qu'une action est en cours ;
 *   - initCopy()          : copie d'un identifiant technique ([data-copy]) ;
 *   - initCopyBlock()     : copie d'un bloc brut ([data-copy-from]) avec retour visible (#583) ;
 *   - initXyzPaste()      : coller « x y z » (écran F3) dans un champ X/Y/Z remplit les trois (#81) ;
 *   - initFilters()       : filtrage progressif des listes de cartes ;
 *   - initOpsAnnounce()   : compteur, aperçu et modèles rapides de l'annonce globale (#95) ;
 *   - initOpsConsole()    : console de logs en lecture seule — recherche, filtres de niveau,
 *                           pause/reprise, suivi automatique, retour en bas (#95) ;
 *   - initOpsState()      : rafraîchit l'état du serveur et suit une opération de redémarrage
 *                           jusqu'à son terme, bloc par bloc, sans jamais recharger la page (#95) ;
 *   - initDrawer()        : ferme le tiroir mobile au clic sur un lien.
 *   - initAbilityCards()  : cartes de capacités d'un profil de mob — monter/descendre, préréglages (#543).
 *
 * Pas de WebSocket (MVP #93). Le polling s'arrête tout seul (garde-fou de durée).
 */
(function () {
  var SLOW_MS = 20000;   // rythme de repos du centre de notifications
  var FAST_MS = 3000;    // rythme tant qu'une action est « en cours »
  var MAX_TICKS = 400;   // garde-fou : ~ 2 h au rythme lent
  var RESYNC_MS = 30000; // délai max d'attente de la resynchronisation avant rechargement (#128 : signalé)

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
  var reloadPlan = { armed: false, deadline: 0, ids: [], els: [] };

  /** Issue #128 : issue de la resynchronisation, transmise à la page rechargée (un seul toast final). */
  var SYNC_OUTCOME_KEY = "pa-sync-outcome";

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

  function reopenWithRefusedForm(actionId) {
    try {
      var params = new URLSearchParams(window.location.search);
      params.set("refused", actionId);
      window.location.href = window.location.pathname + "?" + params.toString();
    } catch (e) { /* URL non manipulable : le refus reste affiché dans le toast */ }
  }

  /**
   * @param outcome issue annoncée après rechargement (issue #128) : "ok" (file au repos, affichage à
   *                jour), "late" (délai dépassé, l'affichage peut être en retard) ; absent = aucun
   *                toast final (rechargement demandé par l'utilisateur ou attente d'un résultat précis).
   */
  function runReload(outcome) {
    // Les identifiants sont marqués AVANT toute sortie : sans cela, la vue se réarmerait au
    // prochain cycle et on retomberait dans une boucle.
    if (reloadPlan.ids.length) { markReloadedFor(reloadPlan.ids); }
    reloadPlan.armed = false;
    if (selfRefreshingView()) { return; }
    if (outcome) {
      try { window.sessionStorage.setItem(SYNC_OUTCOME_KEY, outcome); }
      catch (e) { /* pas de sessionStorage : la page rechargée reste juste, sans toast final */ }
    }
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

  /* ---- Resynchronisation visible (issue #128) ------------------------------------------ */

  /**
   * Après une mutation réussie, la page attend que le serveur ait relu ses catalogues avant de se
   * recharger. Pendant cette attente, le toast DE L'ACTION (jamais un second) le dit : la modification
   * est enregistrée, l'affichage se met à jour. Après le rechargement, un unique toast final confirme
   * « Affichage à jour » ou signale un délai dépassé, avec « Réessayer ». Les relevés techniques
   * (`created_by=auto`) restent invisibles : on ne parle que de la modification de l'utilisateur.
   */
  function mkEl(tag, cls, text) {
    var node = document.createElement(tag);
    if (cls) { node.className = cls; }
    if (text != null) { node.textContent = text; }
    return node;
  }

  /** Bouton « Réessayer », sur sa propre ligne sous le message. */
  function retryButton() {
    var row = mkEl("div", "pa-sync-actions");
    var button = mkEl("button", "btn secondary sm pa-sync-retry", "Réessayer");
    button.setAttribute("type", "button");
    button.setAttribute("data-pa-sync-retry", "");
    button.addEventListener("click", function () { runReload(); });
    row.appendChild(button);
    return row;
  }

  /** Ligne d'état ajoutée sous le résultat d'un toast d'action : "running" ou "failed". */
  function setSyncState(toast, state) {
    var body = toast.querySelector ? toast.querySelector(".toast-body") : null;
    if (!body) { return; } // bannière « Opération en cours » : elle dit déjà que la page se met à jour
    toast.setAttribute("data-toast-sync", state);
    var line = body.querySelector("[data-toast-sync-line]");
    if (!line) {
      line = mkEl("div", "pa-sync");
      line.setAttribute("data-toast-sync-line", "");
      line.setAttribute("role", "status");
      body.appendChild(line);
    }
    line.innerHTML = "";
    if (state === "running") {
      var spin = mkEl("span", "spinner-border spinner-border-sm");
      spin.setAttribute("aria-hidden", "true");
      line.appendChild(spin);
      line.appendChild(mkEl("span", "pa-sync-text", "Modification enregistrée. Mise à jour de l'affichage en cours…"));
    } else {
      line.appendChild(mkEl("span", "pa-sync-text",
        "Mise à jour de l'affichage interrompue : le panel ne répond plus. La modification reste enregistrée."));
      line.appendChild(retryButton());
    }
  }

  /** Toast final après le rechargement automatique : "ok" ou "late". */
  function syncOutcomeToast(outcome) {
    var ok = outcome === "ok";
    var toast = mkEl("div", "toast pa-toast");
    toast.setAttribute("role", ok ? "status" : "alert");
    toast.setAttribute("aria-live", ok ? "polite" : "assertive");
    toast.setAttribute("aria-atomic", "true");
    toast.setAttribute("data-toast-group", ok ? "success" : "warning");
    toast.setAttribute("data-toast-sync", ok ? "done" : "late");
    toast.setAttribute("data-bs-autohide", ok ? "true" : "false");
    toast.setAttribute("data-bs-delay", "4000");
    var header = mkEl("div", "toast-header");
    var ic = mkEl("span", "toast-ic " + (ok ? "text-success" : "text-warning"));
    var i = mkEl("i", "bi " + (ok ? "bi-check-circle" : "bi-exclamation-triangle"));
    i.setAttribute("aria-hidden", "true");
    ic.appendChild(i);
    header.appendChild(ic);
    header.appendChild(mkEl("strong", "me-auto", ok ? "Affichage à jour" : "Mise à jour plus longue que prévu"));
    var close = mkEl("button", "btn-close");
    close.setAttribute("type", "button");
    close.setAttribute("data-bs-dismiss", "toast");
    close.setAttribute("aria-label", "Fermer");
    header.appendChild(close);
    toast.appendChild(header);
    var body = mkEl("div", "toast-body");
    var text = mkEl("span", null, ok ? "La page montre la modification enregistrée."
      : "La modification est enregistrée, mais le serveur n'a pas fini de relire ses données : "
        + "l'affichage peut encore être en retard.");
    text.setAttribute("data-toast-body", "");
    body.appendChild(text);
    if (!ok) { body.appendChild(retryButton()); }
    toast.appendChild(body);
    return toast;
  }

  function showSyncOutcome(root) {
    var outcome = null;
    try {
      outcome = window.sessionStorage.getItem(SYNC_OUTCOME_KEY);
      if (outcome) { window.sessionStorage.removeItem(SYNC_OUTCOME_KEY); }
    } catch (e) { return; }
    if (outcome !== "ok" && outcome !== "late") { return; }
    var toast = syncOutcomeToast(outcome);
    toast.setAttribute("data-toast-ready", "1");
    root.appendChild(toast);
    showToast(toast);
  }

  function initToasts() {
    var root = document.getElementById("toast-root");
    if (!root) { return; }
    showSyncOutcome(root);
    var seeds = document.querySelectorAll(".pa-toast");
    for (var i = 0; i < seeds.length; i++) {
      var el = seeds[i];
      if (el.getAttribute("data-toast-ready") === "1") { continue; }
      el.setAttribute("data-toast-ready", "1");
      if (el.parentNode !== root) { root.appendChild(el); }
      showToast(el);
      var actionId = el.getAttribute("data-toast-action");
      if (actionId && el.getAttribute("data-toast-group") === "pending") {
        // Rendu « en cours » par le serveur : la vue de cette page précède donc le résultat. Une
        // action rapide terminée avant le premier relevé doit aussi recharger la vue (issue #474 :
        // récompense créée mais visible seulement après « Rafraîchir »). Le garde
        // alreadyReloadedFor empêche toute boucle.
        pendingToasts.push({ el: el, actionId: actionId, sawPending: true });
      }
    }
    // Issue #474 : opérations en cours annoncées par la page (bannière « Opération en cours ») —
    // suivies comme un toast, la page se recharge dès leur confirmation.
    var tracked = document.querySelectorAll("[data-pa-track-action]");
    for (var t = 0; t < tracked.length; t++) {
      var trackedId = tracked[t].getAttribute("data-pa-track-action");
      // Issue #214 : data-pa-track-reload="terminal" — la vue attend CE résultat (vérification d'un
      // import) : rechargement immédiat dès la fin, succès ou échec, sans attendre que la file soit au repos.
      var onTerminal = tracked[t].getAttribute("data-pa-track-reload") === "terminal";
      var known = false;
      for (var p = 0; p < pendingToasts.length; p++) {
        if (pendingToasts[p].actionId === trackedId) {
          // Issue #27 : un toast suit déjà l'action — il hérite de l'attente du bloc, sinon un échec
          // mettait à jour le toast seul et le bloc de la page restait « en cours ».
          if (onTerminal) { pendingToasts[p].reloadOnTerminal = true; }
          known = true;
          break;
        }
      }
      if (trackedId && !known) {
        pendingToasts.push({ el: tracked[t], actionId: trackedId, sawPending: true, reloadOnTerminal: onTerminal });
      }
    }
  }

  function updateToast(entry, item, syncing) {
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
    // Succès suivi d'une resynchronisation (#128) : le toast reste et annonce la mise à jour.
    if (syncing) {
      setSyncState(el, "running");
    } else if (item.group === "success") {
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
  var ONE_SHOT_FLAGS = ["ok", "err", "toast", "refused"];

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
          if (entry.reloadOnTerminal && (!match || match.group !== "pending")) {
            // Absente des dernières actions relevées : elle est forcément plus ancienne que celles-ci,
            // donc terminée — la page serveur dira dans quel état. Un seul rechargement par action.
            if (alreadyReloadedFor(entry.actionId)) { pendingToasts.splice(i, 1); continue; }
            reloadPlan.ids.push(entry.actionId);
            runReload();
            return;
          }
          if (match && match.group === "pending") {
            entry.sawPending = true;
            stillPending = true;
          } else if (match) {
            // Vue pending -> succès pendant cette page : la vue serveur peut être périmée.
            // Sauf sur une vue qui se réconcilie seule (/ops) : y armer un rechargement
            // détruirait la saisie en cours, et ses blocs se remettent déjà à jour tout seuls.
            var willResync = entry.sawPending && match.group === "success" && !selfRefreshingView()
                && !alreadyReloadedFor(entry.actionId);
            updateToast(entry, match, willResync);
            // Issue #177 : une table refusée par le serveur revient avec le formulaire tel qu'il a été
            // envoyé — la page est rouverte avec ?refused=<action>, le serveur relit l'envoi refusé.
            // Issue #474 : même chose pour une récompense du catalogue.
            var refusedForm = match.type === "reward.table.save" ? "form[data-chance-form]"
              : match.type === "reward.definition.save" ? "form[data-reward-def-form]" : null;
            if (match.group === "failed" && refusedForm
                && document.querySelector(refusedForm) && !alreadyReloadedFor(entry.actionId)) {
              markReloadedFor([entry.actionId]);
              reopenWithRefusedForm(entry.actionId);
              return;
            }
            if (willResync) {
              reloadPlan.armed = true;
              reloadPlan.deadline = Date.now() + RESYNC_MS;
              if (reloadPlan.ids.indexOf(entry.actionId) === -1) { reloadPlan.ids.push(entry.actionId); }
              reloadPlan.els.push(entry.el);
            }
            pendingToasts.splice(i, 1);
          }
        }

        if (reloadPlan.armed) {
          var idle = typeof data.pending === "number" ? data.pending === 0 : !stillPending;
          if (idle) { runReload("ok"); return; }
          if (Date.now() > reloadPlan.deadline) { runReload("late"); return; }
          schedule(FAST_MS);
          return;
        }
        schedule(stillPending ? FAST_MS : SLOW_MS);
      }).catch(function () {
        // Issue #128 : panel injoignable pendant une resynchronisation — on réessaie vite jusqu'au
        // délai, puis on le dit dans le toast de l'action, avec « Réessayer » (jamais en silence).
        if (reloadPlan.armed) {
          if (Date.now() <= reloadPlan.deadline) { schedule(FAST_MS); return; }
          reloadPlan.armed = false;
          for (var f = 0; f < reloadPlan.els.length; f++) { setSyncState(reloadPlan.els[f], "failed"); }
        }
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

  /* ---- Coordonnées F3 collées d'un coup (#81) -------------------------------------- */

  /*
   * Dans un bloc [data-xyz], coller un texte qui contient exactement trois nombres (« 743.222 67
   * -713.699 », « XYZ: 743.222 / 67.00000 / -713.699 ») remplit X, Y et Z. Autre texte : collage
   * normal. Purement local ; le serveur revalide toujours les trois valeurs.
   */
  function xyzFromText(text) {
    var nums = String(text || "").match(/-?\d+(?:\.\d+)?/g);
    return nums && nums.length === 3 ? nums : null;
  }

  function initXyzPaste() {
    document.addEventListener("paste", function (ev) {
      var input = ev.target;
      var box = input && input.closest ? input.closest("[data-xyz]") : null;
      if (!box) { return; }
      var data = ev.clipboardData || window.clipboardData;
      var nums = xyzFromText(data ? data.getData("text") : "");
      if (!nums) { return; }
      var fields = ["x", "y", "z"].map(function (n) { return box.querySelector('input[name="' + n + '"]'); });
      if (fields.some(function (f) { return !f || f.disabled; })) { return; }
      ev.preventDefault();
      fields.forEach(function (f, i) {
        f.value = nums[i];
        try { f.dispatchEvent(new Event("input", { bubbles: true })); } catch (e) { /* ignore */ }
      });
    });
  }

  /* ---- Copie d'un bloc de texte brut (#583) ---------------------------------------- */

  /*
   * [data-copy-from="<id>"] copie le textContent exact de l'élément <id> (pas de balisage, pas de
   * troncature) et annonce le résultat dans [data-copy-status]. Purement local : aucune requête.
   * Presse-papiers refusé ou absent : le texte est sélectionné pour une copie manuelle.
   */
  function initCopyBlock() {
    document.addEventListener("click", function (ev) {
      var btn = ev.target.closest ? ev.target.closest("[data-copy-from]") : null;
      if (!btn) { return; }
      var src = document.getElementById(btn.getAttribute("data-copy-from"));
      var status = document.getElementById(btn.getAttribute("data-copy-status") || "");
      if (!src) { return; }
      var text = src.textContent;
      var ok = function () { copyBlockStatus(status, true, "Copié — " + text.length + " caractères."); };
      var ko = function () {
        selectContents(src);
        copyBlockStatus(status, false, "Copie automatique impossible (presse-papiers indisponible ou refusé). "
          + "Le texte est sélectionné : Ctrl+C, ou appui long puis « Copier » sur téléphone.");
      };
      try {
        if (navigator.clipboard && navigator.clipboard.writeText && window.isSecureContext) {
          navigator.clipboard.writeText(text).then(ok, function () { execCopy(text) ? ok() : ko(); });
          return;
        }
      } catch (e) { /* repli */ }
      if (execCopy(text)) { ok(); } else { ko(); }
    });
  }

  function execCopy(text) {
    var ta = document.createElement("textarea");
    try {
      ta.value = text;
      ta.setAttribute("readonly", "");
      ta.style.position = "fixed";
      ta.style.opacity = "0";
      document.body.appendChild(ta);
      ta.select();
      return document.execCommand("copy") === true;
    } catch (e) {
      return false;
    } finally {
      if (ta.parentNode) { ta.parentNode.removeChild(ta); }
    }
  }

  function selectContents(el) {
    try {
      el.focus();
      var range = document.createRange();
      range.selectNodeContents(el);
      var sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
    } catch (e) { /* la sélection manuelle reste possible */ }
  }

  function copyBlockStatus(status, ok, message) {
    if (!status) { return; }
    status.textContent = message;
    status.classList.toggle("ok", ok);
    status.classList.toggle("err", !ok);
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
        // Message « aucun résultat » facultatif (#564) : base = data-empty de la puce active, sinon
        // celle du bloc ; la recherche ajoute « ne correspond à « … » ». textContent : jamais de HTML.
        var emptyBox = document.querySelector('[data-filter-empty="' + cssEsc(scope) + '"]');
        var active = [];
        var activeEmpty = [];

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
          if (emptyBox) {
            var base = emptyBox.getAttribute("data-empty") || "Aucun résultat";
            for (var e = 0; e < activeEmpty.length; e++) {
              if (activeEmpty[e]) { base = activeEmpty[e]; break; }
            }
            emptyBox.textContent = base + (q ? " ne correspond à « " + (input.value || "").trim() + " »." : ".");
            emptyBox.hidden = !(items.length > 0 && shown === 0);
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
                activeEmpty[bi] = null;
                for (var c = 0; c < chips.length; c++) {
                  chips[c].classList.toggle("on", chips[c].getAttribute("data-filter-chip") === "");
                }
              } else {
                active[bi] = val;
                activeEmpty[bi] = chip.getAttribute("data-empty");
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
    // #142 : objectif BREAK_BLOCK / PLACE_BLOCK — un bloc sans forme d'objet (CARROTS…) y est
    // une valeur normale, pas une option à signaler.
    var acceptsBlocks = box.hasAttribute("data-combo-blocks");
    var opts = dl.querySelectorAll("option");
    for (var k = 0; k < opts.length; k++) {
      var v = opts[k].getAttribute("value") || "";
      if (!v) { continue; }
      var lbl = opts[k].getAttribute("label") || opts[k].textContent || "";
      if (acceptsBlocks) { lbl = lbl.replace(/ — aucun objet$/, ""); }
      options.push({
        value: v,
        label: lbl && lbl !== v ? lbl : "",
        // #196 : marques portées par l'option elle-même. « noitem » = existe comme bloc mais pas
        // comme objet (ni icône, ni récompense possible) ; « creative » = objet réel mais non
        // obtenable en jeu normal, donc douteux en récompense. Ce sont des avertissements : rien
        // n'est retiré de la liste.
        noItem: !acceptsBlocks && opts[k].getAttribute("data-noitem") === "1",
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
    // Issue #177 : data-multisel-exact = comparer les valeurs telles quelles (« table:butin » et
    // « butin » sont deux choses différentes), au lieu de l'id sans espace de noms des quêtes.
    var keyOf = box.hasAttribute("data-multisel-exact")
      ? function (v) { return String(v == null ? "" : v).trim().toLowerCase(); }
      : plainId;
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
      byPlain[keyOf(v)] = o;
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
        var known = byPlain[keyOf(chosen[c])];
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
        if (chosen.some(function (ch) { return keyOf(ch) === keyOf(o.value); })) { continue; }
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
      if (chosen.some(function (ch) { return keyOf(ch) === keyOf(v); })) { return; }
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
  /**
   * Issue #728 — retours à la ligne. En jeu, un vrai saut de ligne et les balises <br>, <br/>,
   * <newline> donnent exactement le même résultat ; « </br> » s'affiche tel quel. Le mode guidé
   * les montre tous comme un passage à la ligne (touche Entrée) et réécrit, à l'enregistrement,
   * la forme que le texte utilisait déjà : un ancien texte n'est jamais modifié d'office.
   */
  var SF_BREAK_TAG = /<(?:br|newline)\/?>/gi;

  function sfBreakToken(value) {
    var m = /<(?:br|newline)\/?>/i.exec(value);
    return m ? m[0] : "\n";
  }

  function sfParse(raw) {
    var value = String(raw == null ? "" : raw).replace(/\r\n?/g, "\n");
    if (value === "") { return { uniform: true, text: "", color: "", decos: {}, br: "\n" }; }
    var br = sfBreakToken(value);
    value = value.replace(SF_BREAK_TAG, "\n");
    // « </br> » n'est pas une balise pour le jeu : c'est du texte, affiché tel quel. On le met à
    // l'abri le temps de l'analyse, puis on le rend à l'identique (l'avertissement du champ l'explique).
    var literals = [];
    value = value.replace(/<\/(?:br|newline)>/gi, function (m) { literals.push(m); return "\uE000"; });
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
    rest = rest.replace(/\uE000/g, function () { return literals.shift(); });
    return { uniform: true, text: rest, color: color, decos: decos, br: br };
  }

  /** Recompose une valeur MiniMessage à partir du mode guidé. */
  function sfCompose(text, color, decos, br) {
    if (!text) { return ""; }
    if (br && br !== "\n") { text = text.replace(/\r\n?|\n/g, br); }
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

    var state = { color: "", decos: {}, br: "\n" };
    var mode = "guided";

    function paint() {
      var swatches = palette.querySelectorAll("[data-sf-color]");
      for (var s = 0; s < swatches.length; s++) {
        swatches[s].classList.toggle("on", (swatches[s].getAttribute("data-sf-color") || "") === state.color);
      }
      if (!preview) { return; }
      // Aperçu fidèle au jeu (#728) : retours à la ligne visibles, « </br> » laissé tel quel.
      var shown = mode === "guided" ? textInput.value : String(store.value || "")
        .replace(SF_BREAK_TAG, "\n").replace(/<(?!\/(?:br|newline)>)[^>]*>/gi, "");
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
      store.value = sfCompose(textInput.value, state.color, state.decos, state.br);
      paint();
    }

    function enterGuided(initial) {
      mode = "guided";
      guided.hidden = false;
      store.classList.add("sf-store-hidden");
      if (warn) { warn.hidden = true; }
      if (toggle) {
        toggle.hidden = false;
        toggle.textContent = "Mode avancé : balises de mise en forme";
      }
      if (initial) {
        textInput.value = initial.text || "";
        state.color = initial.color || "";
        state.decos = initial.decos || {};
        state.br = initial.br || "\n";
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
          var raw = String(store.value || "");
          var plain = raw.replace(SF_BREAK_TAG, "\n").replace(/<[^>]*>/g, "");
          enterGuided({ text: plain, color: state.color, decos: state.decos, br: sfBreakToken(raw) });
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

  /**
   * Compteur de caractères des champs longs (issue #94).
   *
   * Le compteur est déjà juste au chargement : le serveur l'a rendu avec la longueur réelle de la
   * valeur enregistrée. Ce module ne fait que le tenir à jour pendant la frappe — si panel.js ne
   * charge pas, l'administrateur voit toujours un compteur correct, simplement figé.
   *
   * Aucun maxlength n'est posé ici, et c'est délibéré : maxlength coupe un collage sans rien dire,
   * ce qui est exactement le défaut que ce lot corrige. On avertit, le serveur refuse.
   */
  /*
   * Issue #125 — « ce nom est déjà porté par un autre PNJ ». Le champ porte data-name-taken :
   * l'index { nom comparé : [identifiants] } calculé par le serveur ; data-name-taken-note désigne
   * la note à montrer. La comparaison est celle du serveur (NpcDisplayNames.key) : balises retirées,
   * espaces resserrés, minuscules. Un avertissement seulement : le formulaire reste envoyable.
   */
  function initNameTaken() {
    var fields = document.querySelectorAll("input[data-name-taken]");
    for (var i = 0; i < fields.length; i++) {
      bindNameTaken(fields[i]);
    }
  }

  function nameKey(raw) {
    return String(raw || "").replace(/<[^>]*>/g, "").replace(/\s+/g, " ").trim().toLowerCase();
  }

  function bindNameTaken(field) {
    var index;
    try {
      index = JSON.parse(field.getAttribute("data-name-taken") || "{}");
    } catch (e) {
      return;
    }
    var note = document.getElementById(field.getAttribute("data-name-taken-note") || "");
    if (!note) { return; }
    var text = note.querySelector(".name-taken-text");

    function render() {
      var key = nameKey(field.value);
      var ids = key && Object.prototype.hasOwnProperty.call(index, key) ? index[key] : null;
      if (ids && ids.length) {
        if (text) { text.textContent = "Nom affiché déjà utilisé par : " + ids.join(", ") + "."; }
        note.hidden = false;
      } else {
        note.hidden = true;
      }
    }

    field.addEventListener("input", render);
    render();
  }

  function initCharCount() {
    var fields = document.querySelectorAll("textarea[data-charcount], input[data-charcount]");
    for (var i = 0; i < fields.length; i++) {
      bindCharCount(fields[i]);
    }
  }

  function bindCharCount(field) {
    var id = field.getAttribute("id");
    if (!id) { return; }
    var note = document.querySelector('[data-charcount-for="' + cssEsc(id) + '"]');
    if (!note) { return; }
    var max = parseInt(note.getAttribute("data-charcount-max"), 10);
    if (!(max > 0)) { return; }

    function render() {
      var n = field.value.length;
      var over = n - max;
      var cell = note.querySelector(".cc-n");
      if (cell) { cell.textContent = String(n); }
      note.classList.toggle("charcount-over", over > 0);
      var tail = note.querySelector(".cc-tail");
      if (over > 0) {
        if (!tail) {
          tail = document.createElement("span");
          tail.className = "cc-tail";
          note.appendChild(tail);
        }
        tail.innerHTML = " — <strong>dépassement de " + over
          + "</strong> : l'enregistrement sera refusé.";
      } else if (tail) {
        tail.parentNode.removeChild(tail);
      }
    }

    field.addEventListener("input", render);
    render();
  }

  /* ---- Fiche PNJ : un seul formulaire ouvert à la fois (#325) ---------------------------- */

  /*
   * Les formulaires d'une fiche (« Déplacer », « Promenade »…) sont des volets repliables.
   * Bootstrap ne sait pas les grouper ici (ils sont eux-mêmes dans un volet d'accordéon), donc on
   * referme les autres volets du même groupe [data-one-form] à l'ouverture de l'un d'eux.
   */
  function initOneForm() {
    document.addEventListener("show.bs.collapse", function (ev) {
      var el = ev.target;
      if (!el || !el.classList || !el.classList.contains("npc-form-collapse")) { return; }
      var group = el.closest("[data-one-form]");
      if (!group) { return; }
      var open = group.querySelectorAll(".npc-form-collapse.show, .npc-form-collapse.collapsing");
      for (var i = 0; i < open.length; i++) {
        var other = open[i];
        if (other === el || other.contains(el) || el.contains(other)) { continue; }
        if (window.bootstrap && window.bootstrap.Collapse) {
          window.bootstrap.Collapse.getOrCreateInstance(other, { toggle: false }).hide();
        } else {
          other.classList.remove("show");
        }
      }
    });
  }

  /* ---- Champs conditionnels : <select data-switch="k"> montre [data-switch-for="k"] (#325) -- */

  /*
   * Seul le champ du choix actif est visible ET envoyé : les champs masqués sont désactivés, pour
   * qu'un « required » invisible ne bloque jamais l'envoi et qu'aucune valeur cachée ne parte.
   */
  function initSwitch() {
    var selects = document.querySelectorAll("select[data-switch]");
    for (var i = 0; i < selects.length; i++) {
      (function (sel) {
        var key = sel.getAttribute("data-switch");
        var scope = sel.form || document;
        function apply() {
          var blocks = scope.querySelectorAll('[data-switch-for="' + cssEsc(key) + '"]');
          for (var b = 0; b < blocks.length; b++) {
            // Une ou plusieurs valeurs, séparées par des espaces (issue #332 : trois effets de quête).
            var on = (" " + (blocks[b].getAttribute("data-switch-value") || "") + " ")
              .indexOf(" " + sel.value + " ") !== -1 && sel.value !== "";
            blocks[b].hidden = !on;
            var fields = blocks[b].querySelectorAll("input, select, textarea");
            for (var f = 0; f < fields.length; f++) { fields[f].disabled = !on; }
          }
        }
        sel.addEventListener("change", apply);
        apply();
      })(selects[i]);
    }
  }

  // Issue #332 — une liste déroulante filtrée par une recherche (« Chercher une quête… »). Les
  // options sont retirées puis remises, jamais seulement masquées : iOS ignore « hidden » sur une
  // option. L'option choisie reste toujours présente.
  function initSelectFilter() {
    var inputs = document.querySelectorAll("input[data-select-filter]");
    for (var i = 0; i < inputs.length; i++) {
      (function (input) {
        var sel = document.getElementById(input.getAttribute("data-select-filter"));
        if (!sel) { return; }
        var all = [];
        for (var k = 0; k < sel.options.length; k++) { all.push(sel.options[k]); }
        input.addEventListener("input", function () {
          var q = (input.value || "").trim().toLowerCase();
          var current = sel.value;
          while (sel.options.length) { sel.remove(0); }
          for (var m = 0; m < all.length; m++) {
            var o = all[m];
            if (!q || !o.value || o.value === current || o.text.toLowerCase().indexOf(q) !== -1) { sel.add(o); }
          }
          sel.value = current;
        });
      })(inputs[i]);
    }
  }

  // Issue #332 — « Modifications non enregistrées » : affiché dès la première saisie, et une
  // confirmation du navigateur avant de quitter la page sans enregistrer.
  function initDirtyForms() {
    var forms = document.querySelectorAll("form[data-dirty-form]");
    for (var i = 0; i < forms.length; i++) {
      (function (form) {
        var dirty = form.getAttribute("data-dirty") === "1";
        var note = form.querySelector("[data-dirty-note]");
        function mark() {
          dirty = true;
          if (note) { note.hidden = false; }
        }
        form.addEventListener("input", mark);
        form.addEventListener("change", mark);
        form.addEventListener("submit", function () { dirty = false; });
        window.addEventListener("beforeunload", function (ev) {
          if (!dirty) { return; }
          ev.preventDefault();
          ev.returnValue = "";
        });
      })(forms[i]);
    }
  }

  // Issue #332 — l'aperçu d'un dialogue, une réplique à la fois. Sans JavaScript, tout est affiché
  // et les réponses sont des ancres ; ici, cliquer une réponse montre la réplique suivante.
  function initDialoguePreview() {
    var boxes = document.querySelectorAll("[data-dlg-preview]");
    for (var i = 0; i < boxes.length; i++) {
      (function (box) {
        var nodes = box.querySelectorAll("[data-pv-node]");
        if (!nodes.length) { return; }
        var restart = box.querySelector("[data-dlg-restart]");
        var first = nodes[0].getAttribute("data-pv-node");
        function show(id) {
          for (var n = 0; n < nodes.length; n++) { nodes[n].hidden = nodes[n].getAttribute("data-pv-node") !== id; }
          if (restart) { restart.hidden = id === first; }
        }
        box.addEventListener("click", function (ev) {
          var link = ev.target.closest ? ev.target.closest("[data-pv-go]") : null;
          if (!link || !box.contains(link)) { return; }
          ev.preventDefault();
          show(link.getAttribute("data-pv-go"));
        });
        if (restart) { restart.addEventListener("click", function () { show(first); }); }
        box.classList.add("is-stepped");
        show(first);
      })(boxes[i]);
    }
  }

  // Issue #328 — dates à l'heure locale du navigateur, la date complète en infobulle. Le serveur
  // écrit l'heure UTC (« … UTC ») ; sans JavaScript, c'est elle qui reste affichée.
  function initLocalTimes() {
    var els = document.querySelectorAll("time[data-local-time]");
    for (var i = 0; i < els.length; i++) {
      var d = new Date(els[i].getAttribute("datetime"));
      if (isNaN(d.getTime())) { continue; }
      var full = d.toLocaleString("fr-FR", { dateStyle: "medium", timeStyle: "short" });
      els[i].title = full;
      // Une date relative (« il y a 3 h ») reste relative ; une date absolue passe à l'heure locale.
      if (!els[i].hasAttribute("data-relative") || /UTC$/.test(els[i].textContent)) { els[i].textContent = full; }
    }
  }

  // Issue #328 — revenir d'une fiche joueur vers la liste telle qu'on l'a quittée (recherche,
  // filtre, tri, page). Simple confort par navigateur : sans stockage, le lien mène à la liste.
  function initPlayersBack() {
    var key = "plugadmin.players.list";
    try {
      if (document.querySelector("[data-players-list]")) {
        window.sessionStorage.setItem(key, window.location.pathname + window.location.search);
      }
      var back = document.querySelector("a[data-players-back]");
      var saved = window.sessionStorage.getItem(key);
      if (back && saved && saved.indexOf("/players") === 0) { back.setAttribute("href", saved); }
    } catch (e) { /* stockage indisponible : le lien par défaut suffit */ }
  }

  // Issue #414 — formulaire de catalogue de quêtes. Sans JavaScript tout fonctionne (cases à cocher,
  // ordre d'origine) ; ici : monter / descendre une quête, compteur des quêtes cochées et résumé
  // de ce qui sera enregistré, recalculé à chaque changement. Retour propriétaire : le nombre de
  // quêtes adapte la question « Comment choisir la prochaine quête ? » (data-qc-count) et, en mode
  // « certaines plus souvent », chaque quête affiche sa part approximative.
  function initQuestCatalogs() {
    var form = document.querySelector("form[data-qc-form]");
    if (!form) { return; }
    var list = form.querySelector("[data-qc-items]");

    function renumber() {
      if (!list) { return; }
      var items = list.querySelectorAll("[data-qc-item]");
      for (var i = 0; i < items.length; i++) {
        var order = items[i].querySelector("[data-qc-order]");
        if (order) { order.value = String((i + 1) * 10); }
      }
    }

    if (list) {
      list.addEventListener("click", function (ev) {
        var btn = ev.target.closest ? ev.target.closest("[data-qc-move]") : null;
        if (!btn || !list.contains(btn)) { return; }
        var item = btn.closest("[data-qc-item]");
        if (!item) { return; }
        var up = btn.getAttribute("data-qc-move") === "up";
        var other = up ? item.previousElementSibling : item.nextElementSibling;
        if (!other) { return; }
        if (up) { list.insertBefore(item, other); } else { list.insertBefore(other, item); }
        renumber();
        btn.focus();
        form.dispatchEvent(new Event("change", { bubbles: true }));
      });
    }

    var count = form.querySelector("[data-qc-addcount]");
    var summary = form.querySelector("[data-qc-summary]");
    var nameInput = form.querySelector("[data-qc-name]");

    function plural(n, one, many) { return n + " " + (n > 1 ? many : one); }

    function weightOf(row) {
      var exact = row.querySelector("[data-qc-exact]");
      if (exact && exact.value.trim()) { return parseInt(exact.value, 10) || 0; }
      var sel = row.querySelector("[data-qc-weight]");
      return sel ? parseInt(sel.value, 10) || 0 : 0;
    }

    function chances(rows) {
      var weighted = form.querySelector("input[name=pick_mode][value=weighted]:checked");
      var counted = [];
      var total = 0;
      for (var i = 0; i < rows.length; i++) {
        var on = rows[i].querySelector("[data-qc-enabled]");
        if (on && !on.checked) { continue; }
        var w = weightOf(rows[i]);
        counted.push([rows[i], w]);
        total += w;
      }
      var all = form.querySelectorAll("[data-qc-pct]");
      for (var a = 0; a < all.length; a++) { all[a].textContent = ""; }
      if (!weighted || counted.length < 2 || total <= 0) { return; }
      for (var c = 0; c < counted.length; c++) {
        var pct = counted[c][0].querySelector("[data-qc-pct]");
        if (pct) { pct.textContent = "environ " + Math.round(100 * counted[c][1] / total) + " %"; }
      }
    }

    function refresh() {
      var picked = form.querySelectorAll("[data-qc-cand]:checked").length;
      var rows = [];
      var itemRows = form.querySelectorAll("[data-qc-item]");
      for (var r = 0; r < itemRows.length; r++) {
        var gone = itemRows[r].querySelector("[data-qc-remove]");
        if (!gone || !gone.checked) { rows.push(itemRows[r]); }
      }
      var cands = form.querySelectorAll("[data-qc-cand]:checked");
      for (var k = 0; k < cands.length; k++) { rows.push(cands[k].closest(".qc-cand")); }
      form.setAttribute("data-qc-count", rows.length === 0 ? "none" : rows.length === 1 ? "one" : "many");
      chances(rows);
      if (count) {
        count.textContent = picked ? plural(picked, "quête cochée sera ajoutée", "quêtes cochées seront ajoutées")
          + " à l'enregistrement." : "Coche les quêtes à ajouter.";
      }
      if (!summary) { return; }
      var kept = 0;
      var items = form.querySelectorAll("[data-qc-item]");
      for (var i = 0; i < items.length; i++) {
        var rm = items[i].querySelector("[data-qc-remove]");
        if (!rm || !rm.checked) { kept++; }
      }
      var removed = items.length - kept;
      var npcs = [];
      var tpls = form.querySelectorAll("[data-qc-tpl]:checked");
      for (var t = 0; t < tpls.length; t++) { npcs.push(tpls[t].getAttribute("data-name") || tpls[t].name); }
      var name = nameInput && nameInput.value.trim() ? "« " + nameInput.value.trim() + " »" : "Ce catalogue";
      var text = name + " contiendra " + plural(kept + picked, "quête", "quêtes");
      if (picked || removed) {
        var parts = [];
        if (picked) { parts.push(plural(picked, "ajoutée", "ajoutées")); }
        if (removed) { parts.push(plural(removed, "retirée", "retirées")); }
        text += " (" + parts.join(", ") + ")";
      }
      if (kept + picked > 1) {
        text += form.querySelector("input[name=pick_mode][value=weighted]:checked")
          ? ", choisies au hasard (certaines plus souvent)" : ", choisies au hasard (même chance pour toutes)";
      }
      text += npcs.length ? ", proposées par " + npcs.join(", ") + "." : ". Aucun PNJ ne les proposera pour l'instant.";
      summary.textContent = text;
    }

    form.addEventListener("change", refresh);
    form.addEventListener("input", function (ev) {
      if (ev.target === nameInput || ev.target.hasAttribute("data-qc-exact")) { refresh(); }
    });
    refresh();
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
    run("initCopyBlock", initCopyBlock);
    run("initXyzPaste", initXyzPaste);
    run("initFilters", initFilters);
    run("initFocus", initFocus);
    run("initCombo", initCombo);
    run("initMultiSel", initMultiSel);
    run("initChanceTotals", initChanceTotals);
    run("initEditorForms", initEditorForms);
    run("initStyleFields", initStyleFields);
    run("initSubmitOnce", initSubmitOnce);
    run("initCharCount", initCharCount);
    run("initNameTaken", initNameTaken);
    run("initOneForm", initOneForm);
    run("initSwitch", initSwitch);
    run("initSelectFilter", initSelectFilter);
    run("initDirtyForms", initDirtyForms);
    run("initDialoguePreview", initDialoguePreview);
    run("initLocalTimes", initLocalTimes);
    run("initPlayersBack", initPlayersBack);
    run("initQuestCatalogs", initQuestCatalogs);
    run("initColorPalette", initColorPalette);
    run("initOpsAnnounce", initOpsAnnounce);
    run("initOpsConsole", initOpsConsole);
    run("initOpsState", initOpsState);
    run("initDrawer", initDrawer);
    run("initRowLinks", initRowLinks);
    run("initSubmitOnce", initSubmitOnce);
    run("initRewardItems", initRewardItems);
    run("initAbilityCards", initAbilityCards);
    // Filet de sécurité : au cas où Bootstrap JS finirait de charger après nous, on
    // « promeut » les toasts encore affichés manuellement en vraies instances Bootstrap.
    window.addEventListener("load", upgradeToasts);
  }

  // Issue #543 — cartes de capacités d'un profil de mob. Sans JavaScript, l'ordre se règle avec le
  // champ « Ordre » et les préréglages restent masqués (les champs sont déjà remplis). Ici : boutons
  // monter/descendre qui déplacent la carte et renumérotent, et préréglages qui COPIENT leurs valeurs
  // dans les champs de la carte (rien n'est partagé entre profils). Le serveur revalide tout.
  function initAbilityCards() {
    var lists = document.querySelectorAll("[data-ab-list]");
    for (var i = 0; i < lists.length; i++) { setupAbilityList(lists[i]); }
    var presetBoxes = document.querySelectorAll("[data-ab-presets]");
    for (var j = 0; j < presetBoxes.length; j++) { presetBoxes[j].hidden = false; }
    document.addEventListener("click", function (ev) {
      var btn = ev.target.closest ? ev.target.closest("[data-ab-preset]") : null;
      if (!btn) { return; }
      var values;
      try { values = JSON.parse(btn.getAttribute("data-ab-preset")); } catch (e) { return; }
      var form = btn.form || btn.closest("form");
      var prefix = btn.getAttribute("data-ab-prefix");
      if (!form || !prefix) { return; }
      Object.keys(values).forEach(function (key) {
        var field = form.elements.namedItem(prefix + key);
        if (!field || field.length !== undefined && !field.tagName) { return; }
        field.value = String(values[key]);
        try { field.dispatchEvent(new Event("change", { bubbles: true })); } catch (e) { /* ignore */ }
      });
      var chips = btn.parentNode.querySelectorAll("[data-ab-preset]");
      for (var k = 0; k < chips.length; k++) { chips[k].classList.toggle("on", chips[k] === btn); }
    });
  }

  function setupAbilityList(list) {
    function cards() { return list.querySelectorAll(":scope > [data-ab-card]"); }
    function renumber() {
      var all = cards();
      for (var i = 0; i < all.length; i++) {
        var pos = all[i].querySelector("[data-ab-pos]");
        var ord = all[i].querySelector("[data-ab-ord]");
        if (pos) { pos.value = String(i + 1); }
        if (ord) { ord.textContent = String(i + 1); }
        var up = all[i].querySelector("[data-ab-move=up]");
        var down = all[i].querySelector("[data-ab-move=down]");
        if (up) { up.hidden = false; up.disabled = i === 0; }
        if (down) { down.hidden = false; down.disabled = i === all.length - 1; }
        var posLabel = pos ? pos.closest(".ab-pos") : null;
        if (posLabel) { posLabel.hidden = true; }
      }
    }
    list.addEventListener("click", function (ev) {
      var btn = ev.target.closest ? ev.target.closest("[data-ab-move]") : null;
      if (!btn) { return; }
      var card = btn.closest("[data-ab-card]");
      if (!card) { return; }
      if (btn.getAttribute("data-ab-move") === "up" && card.previousElementSibling) {
        list.insertBefore(card, card.previousElementSibling);
      } else if (btn.getAttribute("data-ab-move") === "down" && card.nextElementSibling) {
        list.insertBefore(card.nextElementSibling, card);
      }
      renumber();
      btn.focus();
      try { list.dispatchEvent(new Event("change", { bubbles: true })); } catch (e) { /* ignore */ }
    });
    list.addEventListener("change", function (ev) {
      var t = ev.target;
      if (t && t.name && /^ab_\d+_enabled$/.test(t.name)) {
        var card = t.closest("[data-ab-card]");
        if (card) { card.classList.toggle("off", !t.checked); }
      }
    });
    renumber();
  }

  // Issue #177 — éditeur de tables de récompenses : total des chances recalculé à la saisie, avec
  // le même texte que le serveur (AgentPages#chanceTotalText). Le serveur revalide tout.
  function initChanceTotals() {
    var forms = document.querySelectorAll("form[data-chance-form]");
    for (var i = 0; i < forms.length; i++) {
      setupChanceTotal(forms[i]);
    }
  }

  function hundredths(raw) {
    var s = String(raw == null ? "" : raw).trim();
    if (!/^\d{1,3}([.,]\d{1,2})?$/.test(s)) { return null; }
    var parts = s.replace(",", ".").split(".");
    var frac = (parts[1] || "") + "00";
    var value = parseInt(parts[0], 10) * 100 + parseInt(frac.substring(0, 2), 10);
    return value >= 1 && value <= 10000 ? value : null;
  }

  function percentText(h) {
    var whole = Math.floor(h / 100);
    var rest = h % 100;
    var text = rest === 0 ? String(whole) : whole + "," + (rest < 10 ? "0" + rest : String(rest)).replace(/0$/, "");
    return text + " %";
  }

  var RESERVED_TABLE_IDS = ["y", "n", "yes", "no", "on", "off", "true", "false", "null"];

  /** Même règle que RewardTableForm#slug : « Butin de l'Ogre ! » → « butin_de_l_ogre ». */
  function tableSlug(label) {
    var plain = String(label || "");
    try { plain = plain.normalize("NFD").replace(/[\u0300-\u036f]/g, ""); } catch (e) { /* ignore */ }
    plain = plain.toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "");
    return plain.length > 32 ? plain.substring(0, 32).replace(/_+$/, "") : plain;
  }

  /** Catalogue des récompenses (clé → nom et quantités), lu dans la datalist rendue par le serveur. */
  function rewardCatalog() {
    var map = {};
    var dl = document.getElementById("dl-reward-catalog");
    var opts = dl ? dl.querySelectorAll("option") : [];
    for (var i = 0; i < opts.length; i++) {
      var key = opts[i].getAttribute("value");
      if (key) { map[key] = opts[i].getAttribute("label") || key; }
    }
    return map;
  }

  /** Même rendu que AgentPages#rewardPickedHtml : nom et quantité d'abord, identifiant ensuite. */
  function pickedHtml(key, catalog) {
    var k = String(key || "").trim().toLowerCase();
    if (!k) { return ""; }
    if (!Object.prototype.hasOwnProperty.call(catalog, k)) {
      return '<span class="text-danger">Récompense absente du catalogue</span> · <code>' + esc(key) + "</code>";
    }
    return "<strong>" + esc(catalog[k]) + "</strong> · <code>" + esc(key) + "</code>";
  }

  /**
   * Première raison pour laquelle le serveur refuserait la table (mêmes règles que
   * RewardTableForm#fill), ou "" si elle est envoyable. Le serveur revalide tout.
   */
  function tableProblem(form, catalog) {
    var labelInput = form.querySelector("[data-rt-label]");
    var label = labelInput ? labelInput.value.trim() : "";
    if (!label) { return "Donner un nom à la table."; }
    if (label.length > 120) { return "Nom de la table : 120 caractères au plus."; }
    var idInput = form.querySelector("[data-rt-id]");
    if (idInput) {
      var id = idInput.value.trim().toLowerCase() || tableSlug(label);
      if (!id) { return "Impossible de tirer un identifiant de ce nom : en saisir un (ex. butin_ogre)."; }
      if (!/^[a-z0-9_-]{1,32}$/.test(id)) {
        return "Identifiant : minuscules, chiffres, _ ou - (32 caractères au plus), ex. butin_ogre.";
      }
      if (RESERVED_TABLE_IDS.indexOf(id) !== -1) {
        return "« " + id + " » ne peut pas servir d'identifiant : choisir par exemple « " + id + "_coffre ».";
      }
    }
    var rows = form.querySelectorAll("[data-chance-row]");
    var count = 0;
    var total = 0;
    for (var i = 0; i < rows.length; i++) {
      var rewardInput = rows[i].querySelector("[data-rt-reward]");
      var chanceInput = rows[i].querySelector("input[data-chance]");
      var reward = rewardInput ? rewardInput.value.trim().toLowerCase() : "";
      var raw = chanceInput ? chanceInput.value.trim() : "";
      if (!reward && !raw) { continue; }
      var line = "Ligne " + (i + 1) + " : ";
      if (!reward) { return line + "choisir une récompense (ou vider aussi sa chance pour retirer la ligne)."; }
      if (!Object.prototype.hasOwnProperty.call(catalog, reward)) {
        return line + "« " + reward + " » n'est pas dans le catalogue, la choisir dans la liste.";
      }
      if (!raw) { return line + "indiquer la chance en %."; }
      var h = hundredths(raw);
      if (h === null) { return line + "la chance doit être entre 0,01 et 100 %, deux décimales au plus (ex. 12,5)."; }
      count++;
      total += h;
    }
    if (count === 0) { return "Ajouter au moins une récompense."; }
    var mode = form.querySelector("input[data-chance-mode]:checked");
    if (!mode) { return "Choisir comment la table est tirée."; }
    if (mode.value === "single" && total > 10000) {
      return "« Une récompense au plus » : le total fait " + percentText(total)
        + ", 100 % au plus. Baisser des chances, ou choisir « Chaque récompense tentée séparément ».";
    }
    return "";
  }

  function setupChanceTotal(form) {
    var out = form.querySelector("[data-chance-total]");
    if (!out) { return; }
    var catalog = rewardCatalog();
    var submit = form.querySelector("[data-rt-submit]");
    var why = form.querySelector("[data-rt-why]");
    if (submit && why && why.id) { submit.setAttribute("aria-describedby", why.id); }
    function update() {
      var total = 0;
      var inputs = form.querySelectorAll("input[data-chance]");
      for (var k = 0; k < inputs.length; k++) {
        var h = hundredths(inputs[k].value);
        if (h !== null) { total += h; }
      }
      var mode = form.querySelector("input[data-chance-mode]:checked");
      var single = mode && mode.value === "single";
      if (single) {
        out.textContent = total > 10000
          ? "Total : " + percentText(total) + " — trop élevé, 100 % au plus pour « une récompense au plus »."
          : "Total : " + percentText(total) + " · chance de ne rien gagner : " + percentText(10000 - total) + ".";
      } else {
        out.textContent = "Total : " + percentText(total)
          + " (chaque ligne est tirée à part ; le total peut dépasser 100 %).";
      }
      out.classList.toggle("text-danger", single && total > 10000);
      // Bouton grisé tant que le serveur refuserait, et la raison écrite juste à côté.
      if (submit) {
        var problem = tableProblem(form, catalog);
        submit.disabled = problem !== "";
        if (why) { why.textContent = problem; }
      }
    }
    function showPicked(ev, always) {
      var input = ev.target;
      if (!input || !input.hasAttribute || !input.hasAttribute("data-rt-reward")) { return; }
      var col = input.closest ? input.closest("[data-chance-row]") : null;
      var picked = col ? col.querySelector("[data-rt-picked]") : null;
      if (!picked) { return; }
      var key = input.value.trim().toLowerCase();
      // Pendant la recherche, on n'affiche que ce qui est reconnu ; à la sélection, toujours.
      if (always || !key || Object.prototype.hasOwnProperty.call(catalog, key)) {
        picked.innerHTML = pickedHtml(input.value, catalog);
      }
    }
    form.addEventListener("input", function (ev) { showPicked(ev, false); update(); });
    form.addEventListener("change", function (ev) { showPicked(ev, true); update(); });
    update();
  }

  // Issue #266 — une ligne de tableau marquée data-href ouvre sa fiche au clic. Le vrai lien de la
  // ligne reste là pour le clavier et les lecteurs d'écran ; on ignore le clic sur un lien, un bouton
  // ou une sélection de texte, et Ctrl/Cmd/clic du milieu ouvrent un nouvel onglet comme un lien.
  function initRowLinks() {
    var rows = document.querySelectorAll("tr[data-href]");
    for (var i = 0; i < rows.length; i++) {
      rows[i].addEventListener("click", function (ev) {
        if (ev.target.closest("a, button, input, select, label, textarea")) { return; }
        var sel = window.getSelection && window.getSelection();
        if (sel && String(sel).length > 0) { return; }
        var href = this.getAttribute("data-href");
        if (!href || href.charAt(0) !== "/") { return; }
        if (ev.ctrlKey || ev.metaKey || ev.button === 1) { window.open(href, "_blank"); return; }
        window.location.href = href;
      });
    }
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

  // Issue #474 — un formulaire porteur d'un jeton (submit_token) ne part qu'une fois : bouton grisé,
  // libellé « Envoi en cours… ». Le serveur refuse de toute façon un jeton déjà utilisé.
  function initSubmitOnce() {
    var tokens = document.querySelectorAll("form input[name=submit_token]");
    for (var i = 0; i < tokens.length; i++) {
      (function (form) {
        if (form.getAttribute("data-once-ready") === "1") { return; }
        form.setAttribute("data-once-ready", "1");
        form.addEventListener("submit", function (ev) {
          if (form.getAttribute("data-sent") === "1") { ev.preventDefault(); return; }
          form.setAttribute("data-sent", "1");
          var buttons = form.querySelectorAll("button[type=submit]");
          window.setTimeout(function () {
            for (var b = 0; b < buttons.length; b++) {
              buttons[b].setAttribute("data-once-label", "1");
              buttons[b].paOnceHtml = buttons[b].innerHTML;
              buttons[b].disabled = true;
              buttons[b].textContent = buttons[b].getAttribute("data-submit-label") || "Envoi en cours…";
            }
          }, 0);
        });
      })(tokens[i].form);
    }
    if (tokens.length === 0) { return; }
    // Retour arrière (cache de page) : boutons réactivés. Le jeton, lui, reste consommé : un renvoi
    // du même formulaire retombe côté serveur sur l'action déjà créée, jamais sur un doublon.
    window.addEventListener("pageshow", function (ev) {
      if (!ev.persisted) { return; }
      var sent = document.querySelectorAll("form[data-sent]");
      for (var f = 0; f < sent.length; f++) {
        sent[f].removeAttribute("data-sent");
        var buttons = sent[f].querySelectorAll("button[type=submit][data-once-label]");
        for (var b = 0; b < buttons.length; b++) {
          buttons[b].disabled = false;
          if (buttons[b].paOnceHtml) { buttons[b].innerHTML = buttons[b].paOnceHtml; }
        }
      }
    });
  }

  /** Objets connus (identifiant → nom français), lus dans la datalist rendue par le serveur. */
  function rewardItemNames() {
    var map = {};
    var dl = document.getElementById("dl-reward-items");
    var opts = dl ? dl.querySelectorAll("option") : [];
    for (var i = 0; i < opts.length; i++) {
      var v = opts[i].getAttribute("value");
      if (v) { map[v.toUpperCase()] = opts[i].getAttribute("label") || v; }
    }
    return map;
  }

  /** Enchantements du serveur : nom français, niveau max, objets acceptés, exclusivités. */
  function enchantInfo() {
    var map = {};
    var dl = document.getElementById("dl-reward-enchants");
    var opts = dl ? dl.querySelectorAll("option") : [];
    for (var i = 0; i < opts.length; i++) {
      var key = opts[i].getAttribute("value");
      if (!key) { continue; }
      var items = opts[i].getAttribute("data-items") || "";
      var conflicts = opts[i].getAttribute("data-conflicts") || "";
      map[key] = {
        name: opts[i].getAttribute("data-name") || key,
        max: parseInt(opts[i].getAttribute("data-max") || "0", 10) || 0,
        items: items ? items.split(",") : [],
        conflicts: conflicts ? conflicts.split(",") : []
      };
    }
    return map;
  }

  // Pas de `var` de module ici : ce bloc suit l'appel synchrone d'init(), une variable n'y serait
  // pas encore affectée. Les fonctions, elles, sont remontées.
  function roman(n) {
    var r = ["", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"];
    return n >= 1 && n < r.length ? r[n] : String(n);
  }

  /** « sharpness 3, unbreaking 1 » → [{key, level}] (même lecture que RewardDefinitionForm). */
  function parseEnchants(raw) {
    var out = [];
    var parts = String(raw || "").toLowerCase().split(/[,;]/);
    for (var i = 0; i < parts.length; i++) {
      var m = /^\s*(?:minecraft:)?([a-z0-9_]{1,64})\s*[ :=]\s*(\d{1,2})\s*$/.exec(parts[i]);
      if (m) { out.push({ key: m[1], level: parseInt(m[2], 10) }); }
      else if (parts[i].trim()) { out.push({ key: parts[i].trim(), level: 0, raw: true }); }
    }
    return out;
  }

  function writeEnchants(list) {
    return list.map(function (e) { return e.raw ? e.key : e.key + " " + e.level; }).join(", ");
  }

  /**
   * Ligne d'objet du catalogue des récompenses (issue #474) : nom français de l'objet choisi sous son
   * champ, et sélecteur d'enchantements guidé — recherche par nom français ou identifiant, niveau 1
   * à max réel du serveur, objets acceptés et incompatibilités. Le sélecteur écrit dans le champ
   * texte soumis (item_ench_<i>) ; le serveur revalide tout.
   */
  function initRewardItems() {
    var rows = document.querySelectorAll("[data-reward-item-row]");
    if (rows.length === 0) { return; }
    var names = rewardItemNames();
    var info = enchantInfo();
    for (var i = 0; i < rows.length; i++) { setupRewardItemRow(rows[i], names, info); }
  }

  function itemPickedHtml(id, names) {
    var v = String(id || "").trim();
    if (!v) { return ""; }
    if (v.indexOf(":") !== -1) { return "<strong>Objet RPGQuest</strong> · <code>" + esc(v) + "</code>"; }
    var name = names[v.toUpperCase()];
    if (!name) { return '<span class="text-danger">Absent de la liste des objets du serveur</span> · <code>' + esc(v) + "</code>"; }
    return "<strong>" + esc(name) + "</strong> · <code>" + esc(v) + "</code>";
  }

  function setupRewardItemRow(row, names, info) {
    var itemInput = row.querySelector("[data-reward-item]");
    var picked = row.querySelector("[data-item-picked]");
    var box = row.querySelector("[data-ench-box]");
    var store = box ? box.querySelector("[data-ench-store]") : null;
    var chips = box ? box.querySelector("[data-ench-chips]") : null;
    var pickBox = box ? box.querySelector("[data-ench-pick-box]") : null;
    var pick = box ? box.querySelector("[data-ench-pick]") : null;
    var level = box ? box.querySelector("[data-ench-level]") : null;
    var add = box ? box.querySelector("[data-ench-add]") : null;
    var out = box ? box.querySelector("[data-ench-info]") : null;
    var guided = store && chips && pickBox && pick && level && add && Object.keys(info).length > 0;

    function item() { return itemInput ? itemInput.value.trim().toUpperCase() : ""; }
    function itemName() { var it = item(); return names[it] || it; }
    function isBook() { return item() === "ENCHANTED_BOOK" || item() === "BOOK"; }
    function applies(e) {
      var it = item();
      if (!it || it.indexOf(":") !== -1 || isBook() || !e || e.items.length === 0) { return null; }
      return e.items.indexOf(it) !== -1;
    }
    function nameOf(key) { return info[key] ? info[key].name : key; }

    function showItem(always) {
      if (!picked || !itemInput) { return; }
      var v = itemInput.value.trim();
      if (always || !v || names[v.toUpperCase()] || v.indexOf(":") !== -1) { picked.innerHTML = itemPickedHtml(v, names); }
    }

    function renderChips() {
      if (!guided) { return; }
      var list = parseEnchants(store.value);
      var html = "";
      for (var i = 0; i < list.length; i++) {
        var e = list[i];
        var meta = info[e.key];
        var notes = [];
        if (e.raw || !meta) { notes.push("inconnu de ce serveur"); }
        if (meta && meta.max && e.level > meta.max) { notes.push("au-delà du niveau normal (max " + roman(meta.max) + ")"); }
        if (meta && applies(meta) === false) { notes.push("ne s'applique pas à " + itemName()); }
        for (var j = 0; j < list.length; j++) {
          if (j !== i && meta && meta.conflicts.indexOf(list[j].key) !== -1) { notes.push("incompatible avec " + nameOf(list[j].key)); }
        }
        html += '<span class="badge ' + (notes.length ? "text-bg-warning" : "text-bg-light border") + ' ench-chip">'
          + esc(e.raw ? e.key : nameOf(e.key) + " " + roman(e.level))
          + (e.raw ? "" : ' <code class="small">' + esc(e.key) + "</code>")
          + (notes.length ? ' <span class="small">⚠ ' + esc(notes.join(" · ")) + "</span>" : "")
          + ' <button type="button" class="btn-close btn-close-sm ms-1" data-ench-del="' + i + '" aria-label="Retirer '
          + esc(e.raw ? e.key : nameOf(e.key)) + '"></button></span>';
      }
      chips.innerHTML = html;
    }

    function chosenKey() {
      var v = pick.value.trim().toLowerCase();
      if (info[v]) { return v; }
      for (var k in info) {
        if (Object.prototype.hasOwnProperty.call(info, k) && info[k].name.toLowerCase() === v) { return k; }
      }
      return "";
    }

    var shownKey = "";
    function describe() {
      var key = chosenKey();
      var e = info[key];
      level.disabled = !e;
      add.disabled = !e;
      if (!e) {
        shownKey = "";
        level.innerHTML = '<option value="">Niveau</option>';
        out.textContent = pick.value.trim() ? "Choisir un enchantement dans la liste (nom français ou identifiant)." : "";
        return;
      }
      // Les niveaux ne sont reconstruits qu'au changement d'enchantement : le « change » natif émis
      // à la sortie du champ ne doit pas remettre à I un niveau déjà choisi.
      if (key !== shownKey) {
        shownKey = key;
        level.innerHTML = '<option value="">Niveau</option>';
        var max = e.max > 0 ? e.max : 10;
        for (var l = 1; l <= max; l++) {
          level.insertAdjacentHTML("beforeend", '<option value="' + l + '">' + roman(l) + (l === max && e.max ? " (max)" : "") + "</option>");
        }
        level.value = "1";
      }
      var parts = [e.name + " — niveau " + (e.max === 1 ? "I seulement" : e.max ? "I à " + roman(e.max) : "1 à 10")];
      var ok = applies(e);
      if (isBook()) { parts.push("stocké dans le livre (s'applique ensuite à l'objet de son choix)"); }
      else if (ok === true) { parts.push("✓ s'applique à " + itemName()); }
      else if (ok === false) { parts.push("⚠ ne s'applique pas à " + itemName() + " : accepté, mais sans effet en jeu — ou choisir un livre enchanté"); }
      if (e.items.length) {
        var sample = e.items.slice(0, 4).map(function (it) { return names[it] || it; });
        parts.push("objets acceptés : " + sample.join(", ") + (e.items.length > 4 ? "… (" + e.items.length + " au total)" : ""));
      }
      if (e.conflicts.length) { parts.push("incompatible avec : " + e.conflicts.map(nameOf).join(", ")); }
      var current = parseEnchants(store.value);
      for (var c = 0; c < current.length; c++) {
        if (e.conflicts.indexOf(current[c].key) !== -1) { parts.push("⚠ déjà choisi : " + nameOf(current[c].key) + ", incompatible"); }
      }
      out.textContent = parts.join(" · ");
    }

    function addChosen() {
      var key = chosenKey();
      var lvl = parseInt(level.value || "1", 10);
      if (!key || !lvl) { return; }
      var list = parseEnchants(store.value).filter(function (e) { return e.key !== key; });
      list.push({ key: key, level: lvl });
      store.value = writeEnchants(list);
      pick.value = "";
      describe();
      renderChips();
      try { store.dispatchEvent(new Event("change", { bubbles: true })); } catch (e) { /* ignore */ }
      pick.focus();
    }

    if (itemInput) {
      itemInput.addEventListener("input", function () { showItem(false); renderChips(); if (guided) { describe(); } });
      itemInput.addEventListener("change", function () { showItem(true); renderChips(); if (guided) { describe(); } });
    }
    if (!guided) { return; }
    // Sélecteur guidé : le champ texte reste le champ soumis, masqué derrière les puces.
    store.hidden = true;
    pickBox.hidden = false;
    pick.addEventListener("input", describe);
    pick.addEventListener("change", describe);
    pick.addEventListener("keydown", function (ev) {
      if (ev.key === "Enter" && chosenKey()) { ev.preventDefault(); addChosen(); }
    });
    add.addEventListener("click", addChosen);
    chips.addEventListener("click", function (ev) {
      var del = ev.target.closest ? ev.target.closest("[data-ench-del]") : null;
      if (!del) { return; }
      var list = parseEnchants(store.value);
      list.splice(parseInt(del.getAttribute("data-ench-del"), 10), 1);
      store.value = writeEnchants(list);
      renderChips();
      describe();
    });
    renderChips();
  }
})();
