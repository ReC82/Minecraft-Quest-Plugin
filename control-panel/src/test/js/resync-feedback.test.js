/*
 * #128 — état d'attente visible pendant la resynchronisation automatique après une mutation.
 * La logique vit dans panel.js : un test Java ne peut pas la vérifier. Ce test exécute le VRAI
 * panel.js dans un DOM minimal simulé (aucune dépendance npm) et rejoue le cycle
 * pending -> succès -> « mise à jour de l'affichage en cours » -> rechargement -> toast final,
 * ainsi que les chemins délai dépassé et panel injoignable.
 *
 * Volontairement non câblé à Gradle, comme les autres tests de ce dossier : le build ne doit pas
 * dépendre d'un Node installé. Exécution manuelle, depuis la racine du dépôt :
 *
 *     node control-panel/src/test/js/resync-feedback.test.js
 */
"use strict";
const fs = require("fs");
const path = require("path");
const vm = require("vm");
const PANEL_JS = path.join(__dirname, "..", "..", "main", "resources", "assets", "panel.js");
const src = fs.readFileSync(PANEL_JS, "utf8");

let fail = 0;
function check(name, cond, detail) {
  if (cond) { console.log("ok   : " + name); }
  else { fail++; console.log("ECHEC: " + name + (detail ? " -> " + detail : "")); }
}

/* ---- DOM minimal ---------------------------------------------------------------------- */

class FakeNode {
  constructor(tag) {
    this.tagName = String(tag).toUpperCase();
    this.attrs = new Map();
    this.children = [];
    this.parentNode = null;
    this.text = "";
    this.listeners = {};
    this.style = {};
    const self = this;
    this.classList = {
      add(c) { const s = self.classes(); if (!s.includes(c)) { s.push(c); self.className = s.join(" "); } },
      remove(c) { self.className = self.classes().filter(x => x !== c).join(" "); },
      contains(c) { return self.classes().includes(c); },
    };
  }
  classes() { return (this.attrs.get("class") || "").split(/\s+/).filter(Boolean); }
  get className() { return this.attrs.get("class") || ""; }
  set className(v) { this.attrs.set("class", String(v)); }
  get id() { return this.attrs.get("id") || ""; }
  getAttribute(n) { return this.attrs.has(n) ? this.attrs.get(n) : null; }
  setAttribute(n, v) { this.attrs.set(n, String(v)); }
  hasAttribute(n) { return this.attrs.has(n); }
  removeAttribute(n) { this.attrs.delete(n); }
  appendChild(c) {
    if (c.parentNode) { c.parentNode.children = c.parentNode.children.filter(x => x !== c); }
    c.parentNode = this; this.children.push(c); return c;
  }
  get textContent() { return this.text + this.children.map(c => c.textContent).join(""); }
  set textContent(v) { this.children = []; this.text = String(v); }
  set innerHTML(v) { this.children = []; this.text = String(v); }
  get innerHTML() { return this.text; }
  addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); }
  click() { (this.listeners.click || []).forEach(fn => fn({ currentTarget: this, target: this })); }
  all() { const out = []; for (const c of this.children) { out.push(c, ...c.all()); } return out; }
  querySelectorAll(sel) { return this.all().filter(n => matches(n, sel)); }
  querySelector(sel) { return this.querySelectorAll(sel)[0] || null; }
}

/** Sélecteur composé simple (tag, .classe, #id, [attr], [attr="v"]) et descendants séparés par des espaces. */
function matchesCompound(node, sel) {
  if (/[:>+~,]/.test(sel)) { return false; }
  const m = /^([a-zA-Z][\w-]*)?/.exec(sel);
  if (m[1] && node.tagName !== m[1].toUpperCase()) { return false; }
  let rest = sel.slice(m[0].length);
  const re = /\.([\w-]+)|#([\w-]+)|\[([\w-]+)(?:="?([^"\]]*)"?)?\]/y;
  while (rest.length) {
    re.lastIndex = 0;
    const t = re.exec(rest);
    if (!t) { return false; }
    if (t[1] && !node.classes().includes(t[1])) { return false; }
    if (t[2] && node.id !== t[2]) { return false; }
    if (t[3]) {
      if (!node.hasAttribute(t[3])) { return false; }
      if (t[4] !== undefined && node.getAttribute(t[3]) !== t[4]) { return false; }
    }
    rest = rest.slice(t[0].length);
  }
  return true;
}
function matches(node, sel) {
  const parts = sel.trim().split(/\s+/);
  if (!matchesCompound(node, parts[parts.length - 1])) { return false; }
  let cur = node.parentNode;
  for (let i = parts.length - 2; i >= 0; i--) {
    while (cur && !matchesCompound(cur, parts[i])) { cur = cur.parentNode; }
    if (!cur) { return false; }
    cur = cur.parentNode;
  }
  return true;
}

function h(tag, attrs, ...kids) {
  const n = new FakeNode(tag);
  for (const [k, v] of Object.entries(attrs || {})) { n.setAttribute(k, v); }
  for (const k of kids) { if (typeof k === "string") { n.text += k; } else { n.appendChild(k); } }
  return n;
}

/** Toast « en cours » tel que le rend ActionView.toastHtml. */
function pendingToast(actionId) {
  return h("div", { class: "toast pa-toast", "data-toast-action": actionId, "data-toast-group": "pending",
    "data-bs-autohide": "false" },
  h("div", { class: "toast-header" }, h("span", { class: "toast-ic text-primary" }, h("i", { class: "bi bi-clock" }))),
  h("div", { class: "toast-body" }, h("span", { "data-toast-body": "" }, "En attente de confirmation de l'agent.")));
}

/* ---- Page simulée --------------------------------------------------------------------- */

function page(session, opts) {
  opts = opts || {};
  const html = h("html", {});
  const body = html.appendChild(h("body", {}));
  const root = body.appendChild(h("div", { id: "toast-root" }));
  body.appendChild(h("div", { id: "notif-center", "data-notif-agent": "agent-1" }));
  if (opts.selfRefresh) { body.appendChild(h("div", { "data-pa-selfrefresh": "" })); }
  if (opts.toastFor) { body.appendChild(pendingToast(opts.toastFor)); }
  const document = {
    readyState: "complete",
    documentElement: html, body,
    getElementById: id => html.all().find(n => n.id === id) || null,
    querySelector: s => html.querySelector(s),
    querySelectorAll: s => html.querySelectorAll(s),
    createElement: t => new FakeNode(t),
    addEventListener() {},
  };
  const state = { now: 1000000, timers: [], seq: 0, reloads: 0, responses: opts.responses || [], fetches: 0, warns: [] };
  const ctx = {
    document,
    console: { log() {}, warn: (...a) => state.warns.push(a.join(" ")), error() {} },
    sessionStorage: session,
    location: { pathname: "/npcs", search: "", hash: "", href: "/npcs", reload() { state.reloads++; } },
    history: { replaceState() {} },
    URLSearchParams,
    Event: class { constructor(t) { this.type = t; } },
    setTimeout(fn, ms) { const id = ++state.seq; state.timers.push({ id, fn, at: state.now + (ms || 0) }); return id; },
    clearTimeout(id) { state.timers = state.timers.filter(t => t.id !== id); },
    setInterval() { return 0; }, clearInterval() {}, addEventListener() {},
    fetch() {
      state.fetches++;
      const next = state.responses.length ? state.responses.shift() : { pending: 0, actions: [] };
      if (next === "network-error") { return Promise.reject(new Error("réseau")); }
      return Promise.resolve({ status: 200, ok: true, json: () => Promise.resolve(next) });
    },
    Date: { now: () => state.now },
  };
  ctx.window = ctx;
  vm.createContext(ctx);
  vm.runInContext(src, ctx, { filename: "panel.js" });
  return { root, body, state, document };
}

function session() {
  const m = new Map();
  return { getItem: k => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: k => m.delete(k), m };
}

async function flush() { for (let i = 0; i < 6; i++) { await new Promise(r => setImmediate(r)); } }

/** Avance jusqu'au prochain relevé de la file (fetch), en exécutant les minuteries intermédiaires. */
async function nextPoll(p) {
  await flush();
  const before = p.state.fetches;
  while (p.state.fetches === before && p.state.timers.length) {
    p.state.timers.sort((a, b) => a.at - b.at);
    const t = p.state.timers.shift();
    p.state.now = Math.max(p.state.now, t.at);
    t.fn();
    await flush();
  }
}

function toastOf(p, id) { return p.document.querySelector('[data-toast-action="' + id + '"]'); }
function syncLine(p, id) { const t = toastOf(p, id); return t ? t.querySelector("[data-toast-sync-line]") : null; }
const PENDING = id => ({ pending: 2, actions: [{ idFull: id, group: "pending", type: "npc.definition.create" }] });
const SUCCESS = (id, pending) => ({ pending, actions: [{ idFull: id, group: "success",
  type: "npc.definition.create", target: "Mira la Cartographe", resultShort: "définition créée" }] });

(async () => {
  /* 1. Chemin normal : pending -> succès -> synchronisation visible -> rechargement -> « Affichage à jour ». */
  {
    const s = session();
    const p = page(s, { toastFor: "act-1", responses: [PENDING("act-1"), SUCCESS("act-1", 1), SUCCESS("act-1", 0)] });
    await flush(); // relevé 1 : pending
    check("cycle : aucun rechargement tant que l'action est en cours", p.state.reloads === 0);
    await nextPoll(p); // relevé 2 : succès, relevés automatiques encore en file
    const t = toastOf(p, "act-1");
    const line = syncLine(p, "act-1");
    check("succès : le toast de l'action annonce la mise à jour en cours", t.getAttribute("data-toast-sync") === "running"
      && line && /Mise à jour de l'affichage en cours/.test(line.textContent), line && line.textContent);
    check("succès : le toast dit que la modification est enregistrée", /Modification enregistrée/.test(line.textContent));
    check("succès : le résultat métier reste affiché", /Mira la Cartographe — définition créée/.test(t.textContent), t.textContent);
    check("succès : un indicateur d'attente est visible", !!line.querySelector(".spinner-border"));
    check("succès : un seul toast (pas de toast redondant)", p.root.querySelectorAll(".pa-toast").length === 1);
    check("succès : pas encore rechargé (file pas au repos)", p.state.reloads === 0);
    await nextPoll(p); // relevé 3 : file au repos
    // le toast n'a pas été fermé par la minuterie de 5 s du succès simple
    check("succès : le toast d'attente n'est pas refermé automatiquement",
      t.classList.contains("show"), t.className);
    check("repos : la page se recharge seule (pas de F5)", p.state.reloads === 1, String(p.state.reloads));
    check("repos : issue « ok » transmise à la page rechargée", s.getItem("pa-sync-outcome") === "ok");

    const after = page(s, {});
    await flush();
    const finals = after.root.querySelectorAll(".pa-toast");
    check("page rechargée : un seul toast final", finals.length === 1, String(finals.length));
    check("page rechargée : « Affichage à jour »", finals[0] && /Affichage à jour/.test(finals[0].textContent)
      && finals[0].getAttribute("data-toast-sync") === "done");
    check("page rechargée : le toast final se ferme seul", finals[0] && finals[0].getAttribute("data-bs-autohide") === "true");
    check("page rechargée : pas de bouton Réessayer quand tout va bien", !after.root.querySelector("[data-pa-sync-retry]"));
    check("page rechargée : l'issue est consommée (usage unique)", s.getItem("pa-sync-outcome") === null);
    check("page rechargée : pas de nouveau rechargement", after.state.reloads === 0);
    const again = page(s, {});
    await flush();
    check("rechargement suivant : aucun toast final rejoué", again.root.querySelectorAll(".pa-toast").length === 0);
    check("aucune panne de module", p.state.warns.length === 0 && after.state.warns.length === 0, p.state.warns.join(" | "));
  }

  /* 2. Délai dépassé : la file ne retombe jamais au repos. */
  {
    const s = session();
    const responses = [PENDING("act-2"), SUCCESS("act-2", 1)];
    for (let i = 0; i < 40; i++) { responses.push(SUCCESS("act-2", 1)); }
    const p = page(s, { toastFor: "act-2", responses });
    await flush();
    await nextPoll(p);
    check("délai : synchronisation annoncée", toastOf(p, "act-2").getAttribute("data-toast-sync") === "running");
    let guard = 0;
    while (p.state.reloads === 0 && guard++ < 30) { await nextPoll(p); }
    check("délai : la page se recharge quand même (meilleur effort)", p.state.reloads === 1);
    check("délai : issue « late » transmise", s.getItem("pa-sync-outcome") === "late");
    const after = page(s, {});
    await flush();
    const fin = after.root.querySelector(".pa-toast");
    check("délai : toast final explicite", fin && /plus longue que prévu/.test(fin.textContent)
      && fin.getAttribute("data-toast-sync") === "late");
    check("délai : le toast final reste affiché", fin && fin.getAttribute("data-bs-autohide") === "false");
    const retry = fin && fin.querySelector("[data-pa-sync-retry]");
    check("délai : bouton « Réessayer » présent", !!retry);
    retry.click();
    check("délai : « Réessayer » recharge la page", after.state.reloads === 1);
    check("délai : « Réessayer » ne programme pas de nouveau toast final", s.getItem("pa-sync-outcome") === null);
  }

  /* 3. Panel injoignable pendant la synchronisation. */
  {
    const s = session();
    const responses = [PENDING("act-3"), SUCCESS("act-3", 1)];
    for (let i = 0; i < 40; i++) { responses.push("network-error"); }
    const p = page(s, { toastFor: "act-3", responses });
    await flush();
    await nextPoll(p);
    await nextPoll(p); // première panne : on réessaie vite
    check("panne : pas d'échec annoncé avant le délai", toastOf(p, "act-3").getAttribute("data-toast-sync") === "running");
    let guard = 0;
    while (toastOf(p, "act-3").getAttribute("data-toast-sync") === "running" && guard++ < 30) { await nextPoll(p); }
    const line = syncLine(p, "act-3");
    check("panne : échec explicite dans le toast de l'action", toastOf(p, "act-3").getAttribute("data-toast-sync") === "failed"
      && /interrompue/.test(line.textContent) && /reste enregistrée/.test(line.textContent), line && line.textContent);
    check("panne : pas de rechargement aveugle", p.state.reloads === 0);
    check("panne : un seul toast", p.root.querySelectorAll(".pa-toast").length === 1);
    const retry = line.querySelector("[data-pa-sync-retry]");
    check("panne : bouton « Réessayer »", !!retry);
    retry.click();
    check("panne : « Réessayer » recharge la page", p.state.reloads === 1);
  }

  /* 4. Vue qui se réconcilie seule (/ops) : ni rechargement ni annonce de synchronisation. */
  {
    const s = session();
    const p = page(s, { toastFor: "act-4", selfRefresh: true, responses: [PENDING("act-4"), SUCCESS("act-4", 0)] });
    await flush();
    await nextPoll(p);
    check("/ops : aucune annonce de synchronisation", !toastOf(p, "act-4").hasAttribute("data-toast-sync"));
    await nextPoll(p);
    check("/ops : aucun rechargement", p.state.reloads === 0 && s.getItem("pa-sync-outcome") === null);
  }

  /* 5. Les relevés techniques created_by=auto restent hors de la cloche. */
  check("la cloche filtre toujours les relevés automatiques", /filter\(function \(a\) \{ return !a\.auto; \}\)/.test(src));

  console.log(fail === 0 ? "\nTOUS LES CONTRÔLES PASSENT" : "\n" + fail + " contrôle(s) en échec");
  process.exit(fail === 0 ? 0 : 1);
})().catch(e => { console.error(e); process.exit(2); });
