"use strict";
/*
 * RPGQuest Control Panel — rafraîchissement automatique des actions agent (issue #65).
 *
 * Chargé en `<script src="/assets/panel.js" defer>` (même origine : conforme à la CSP
 * `default-src 'self'`, aucun script inline). Aucune dépendance externe.
 *
 * Principe :
 *   - après soumission d'une action, la page est rechargée (POST -> 303) et la nouvelle
 *     action apparaît immédiatement en PENDING dans le tableau rendu côté serveur ;
 *   - ce script repère les blocs `[data-actions-agent]` et, s'il reste au moins une action
 *     non terminale, interroge `/agents/actions.json?agent=<id>` toutes les 2 s ;
 *   - à chaque réponse il reconstruit le corps du tableau ;
 *   - dès qu'aucune action n'est plus PENDING/DELIVERED, le polling s'arrête ;
 *   - garde-fou : arrêt inconditionnel après 5 minutes.
 */
(function () {
  var INTERVAL_MS = 2000;
  var MAX_POLLS = 150; // 150 * 2 s = 5 min

  function esc(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  }

  function renderRows(actions) {
    if (!actions || actions.length === 0) {
      return '<tr><td colspan="7" class="muted">Aucune action.</td></tr>';
    }
    return actions.map(function (a) {
      return '<tr>'
        + '<td><code>' + esc(a.id) + '</code></td>'
        + '<td>' + esc(a.type) + '</td>'
        + '<td class="muted">' + esc(a.params) + '</td>'
        + '<td><span class="pill ' + esc(a.pill) + '">' + esc(a.status) + '</span></td>'
        + '<td>' + esc(a.deliverCount) + '</td>'
        + '<td>' + esc(a.result) + '</td>'
        + '<td class="muted">' + esc(a.createdAt) + '</td>'
        + '</tr>';
    }).join("");
  }

  function attach(block) {
    var agent = block.getAttribute("data-actions-agent");
    if (!agent) {
      return;
    }
    var body = block.querySelector("tbody");
    var statusLine = block.querySelector(".poll-status");
    var pendingAttr = parseInt(block.getAttribute("data-actions-pending") || "0", 10);
    if (!body || !(pendingAttr > 0)) {
      return; // rien de PENDING au chargement : ne pas poller en permanence
    }

    var polls = 0;
    var timer = null;

    function stop(message) {
      if (timer) {
        window.clearTimeout(timer);
        timer = null;
      }
      if (statusLine) {
        if (message) {
          statusLine.textContent = message;
          statusLine.hidden = false;
        } else {
          statusLine.hidden = true;
        }
      }
    }

    function schedule() {
      timer = window.setTimeout(tick, INTERVAL_MS);
    }

    function tick() {
      polls += 1;
      if (polls > MAX_POLLS) {
        stop("Rafraîchissement automatique interrompu (délai dépassé). Recharger la page pour reprendre.");
        return;
      }
      fetch("/agents/actions.json?agent=" + encodeURIComponent(agent), {
        headers: { "Accept": "application/json" },
        credentials: "same-origin"
      }).then(function (res) {
        if (res.status === 401 || res.status === 403) {
          stop("Session expirée — recharger la page.");
          return null;
        }
        if (!res.ok) {
          throw new Error("HTTP " + res.status);
        }
        return res.json();
      }).then(function (data) {
        if (!data) {
          return;
        }
        if (data.actions) {
          body.innerHTML = renderRows(data.actions);
        }
        if (data.pending > 0) {
          if (statusLine) {
            statusLine.textContent = "Rafraîchissement automatique… (" + data.pending + " action(s) en cours)";
            statusLine.hidden = false;
          }
          schedule();
        } else {
          stop(null);
        }
      }).catch(function () {
        // Erreur réseau transitoire : on retente au prochain intervalle.
        schedule();
      });
    }

    if (statusLine) {
      statusLine.textContent = "Rafraîchissement automatique…";
      statusLine.hidden = false;
    }
    schedule();
  }

  function init() {
    var blocks = document.querySelectorAll("[data-actions-agent]");
    for (var i = 0; i < blocks.length; i++) {
      attach(blocks[i]);
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
