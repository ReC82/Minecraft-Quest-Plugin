"""Construit les pages de prototype #428 (phase 1) à partir de VRAIES pages capturées.

    python3 -I docs/design/428-icones/tools/build_prototypes.py

- sources/plugadmin-quetes-shell.html : page « Quêtes » rendue par un PlugAdmin local
  (habillage réel : barre du haut, menu, CSS) ; le contenu principal est remplacé par une
  maquette de la fiche « La chasse aux cristaux » (données de quests/crystal_hunt.yml).
- sources/portail-accueil.html : accueil rendu par le web-api local, état « inconnu ».

Sorties (dans docs/design/428-icones/) : index.html, palette.html, plugadmin-a.html,
plugadmin-b.html, portail-a.html, portail-b.html. Le sprite est inclus en ligne dans chaque
page pour qu'elles s'ouvrent directement depuis le disque ; en production il sera servi en
local (/assets/…) — voir DESIGN_SYSTEM.md.
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from icons import ICONS  # noqa: E402

PANEL_ASSETS = "../../../control-panel/src/main/resources/assets/"
SPRITE = (ROOT / "assets" / "lq-icons.svg").read_text(encoding="utf-8")
SPRITE_INLINE = SPRITE.replace('<svg xmlns="http://www.w3.org/2000/svg"',
                               '<svg xmlns="http://www.w3.org/2000/svg" class="lq-sprite" aria-hidden="true"', 1)
MATERIALS = json.loads((ROOT / "assets" / "material-map.json").read_text(encoding="utf-8"))

VARIANTS = {
    "a": "Variante A — Minecraft modernisé, sobre",
    "b": "Variante B — RPG fantasy voxel",
}


def ico(name, size=16, label=None, tile=False):
    """Icône du sprite ; décorative (aria-hidden) sauf si un libellé est donné."""
    a11y = f'role="img" aria-label="{label}"' if label else 'aria-hidden="true" focusable="false"'
    svg = (f'<svg class="lq-ico lq-{size}" width="{size}" height="{size}" {a11y}>'
           f'<use href="#lq-{name}"/></svg>')
    return f'<span class="lq-tile">{svg}</span>' if tile else svg


def icon_for(material):
    """Résolution identique à celle prévue en phase 2 : exact, puis suffixe, puis repli."""
    m = material.upper()
    if m in MATERIALS["exact"]:
        return MATERIALS["exact"][m]
    for suffix, name in MATERIALS["suffix"].items():
        if m.endswith(suffix):
            return name
    return MATERIALS["fallback"]


def variant_switch(page, current):
    links = []
    for key, label in VARIANTS.items():
        attr = ' aria-current="page"' if key == current else ""
        links.append(f'<a href="{page}-{key}.html"{attr}>{label}</a>')
    return ('<div class="proto-bar" role="navigation" aria-label="Prototype #428">'
            '<strong>Prototype #428</strong> ' + " · ".join(links) +
            ' · <a href="index.html">Sommaire</a></div>')


# ---------------------------------------------------------------- PlugAdmin

QUEST_ROWS = [
    ("first_steps", "Premiers pas", "Exploration", "map"),
    ("crystal_hunt", "La chasse aux cristaux", "Artisanat", icon_for("AMETHYST_SHARD")),
    ("lily_tarte", "La tarte de Lily", "Cuisine", icon_for("PUMPKIN_PIE")),
]

OBJECTIVES = [
    ("spider", "Vaincre 5 araignées", "SPIDER", "hunt_spiders"),
    (icon_for("AMETHYST_SHARD"), "Récolter 2 éclats d'améthyste", "AMETHYST_SHARD", "gather_crystals"),
    (icon_for("DIAMOND_SWORD"), "Fabriquer 1 épée en diamant", "DIAMOND_SWORD", "forge_blade"),
    ("talk", "Parler au Garde", "guard", "report_to_guard"),
]

REWARDS = [
    ("xp", "100 points d'expérience", "EXPERIENCE 100"),
    (icon_for("IRON_PICKAXE"), "Pioche du mineur ×1", "rpgquest:miner_pickaxe"),
]


def tid(value):
    return f'<code class="tid">{value}</code>'


def panel_main():
    rows = []
    for qid, title, cat, icon in QUEST_ROWS:
        rows.append(
            f'<li class="lq-qrow"><span class="lq-tile">{ico(icon, 16)}</span>'
            f'<span class="lq-qrow-t">{title}</span>{tid(qid)}'
            f'<span class="badge text-bg-secondary">{cat}</span></li>')
    objectives = "".join(
        f'<li>{ico(icon, 16, tile=True)}<span class="obj-text">{label}</span>'
        f'<code class="tid tid--wrap">{raw}</code>{tid(step)}</li>'
        for icon, label, raw, step in OBJECTIVES)
    rewards = "".join(
        f'<li>{ico(icon, 16, tile=True)}<span class="obj-text">{label}</span>'
        f'<code class="tid tid--wrap">{raw}</code></li>'
        for icon, label, raw in REWARDS)
    return f"""
<main class="main"><div class="pagehead"><div class="ph-l"><div class="ph-title"><i class="bi bi-journal-check ic" aria-hidden="true"></i><h1>Quêtes</h1></div><p class="sub">Catalogue des quêtes, état d'un joueur, et raccourcis d'administration (démarrer / compléter / réinitialiser).</p></div><div class="ph-actions"><a class="btn" href="#"><i class="bi bi-plus-lg ic" aria-hidden="true"></i>Créer une quête</a><a class="btn secondary" href="#"><i class="bi bi-book ic" aria-hidden="true"></i>Catalogues de quêtes</a></div></div>
<p class="lq-proto-note">Maquette : habillage réel de PlugAdmin, contenu reconstitué d'après <code>quests/crystal_hunt.yml</code>. Les boutons d'action gardent leurs icônes actuelles.</p>
<ul class="lq-qlist" aria-label="Quêtes">{"".join(rows)}</ul>
<section class="lq-qcard" aria-labelledby="q-title">
<div class="lq-qhead">{ico(icon_for("AMETHYST_SHARD"), 32, tile=True)}<div><h2 id="q-title" class="lq-qname">La chasse aux cristaux</h2>{tid("rpgquest:crystal_hunt")}
<span class="badge text-bg-secondary">Artisanat</span><span class="badge text-bg-success">OK</span></div></div>
<p class="npc-section"><i class="bi bi-book ic" aria-hidden="true"></i>Général</p>
<dl class="npc-dl"><div class="npc-dl-row"><dt>Répétable</dt><dd>non</dd></div><div class="npc-dl-row"><dt>Prérequis</dt><dd>Premiers pas {tid("rpgquest:first_steps")}</dd></div></dl>
<p class="npc-section"><i class="bi bi-person-badge ic" aria-hidden="true"></i>Donneur</p>
<p class="lq-giver">{ico("npc", 16, tile=True)}<span>Garde</span>{tid("guard")}</p>
<p class="npc-section"><i class="bi bi-bullseye ic" aria-hidden="true"></i>Objectifs</p>
<ol class="obj-list lq-list">{objectives}</ol>
<p class="npc-section"><i class="bi bi-gift ic" aria-hidden="true"></i>Récompenses</p>
<ul class="reward-list lq-list">{rewards}</ul>
<p class="lq-actions"><a class="btn" href="#"><i class="bi bi-pencil ic" aria-hidden="true"></i>Modifier</a><a class="btn secondary" href="#"><i class="bi bi-trash ic" aria-hidden="true"></i>Supprimer</a></p>
</section>
</main>"""


def build_panel(variant):
    html = (ROOT / "sources" / "plugadmin-quetes-shell.html").read_text(encoding="utf-8")
    html = re.sub(r'href="/assets/([^"?]+)(\?[^"]*)?"', lambda m: f'href="{PANEL_ASSETS}{m.group(1)}"', html)
    html = re.sub(r'src="/assets/([^"?]+)(\?[^"]*)?"', lambda m: f'src="{PANEL_ASSETS}{m.group(1)}"', html)
    html, n = re.subn(r"<main class=\"main\">.*?</main>", lambda _m: panel_main(), html, flags=re.S)
    assert n == 1, "zone <main> introuvable dans la capture PlugAdmin"
    css = (f'<link rel="stylesheet" href="assets/lq-icons.css">\n'
           f'<link rel="stylesheet" href="assets/plugadmin-{variant}.css">\n</head>')
    html = html.replace("</head>", css, 1)
    html = re.sub(r"<body([^>]*)>", lambda m: f"<body{m.group(1)}>\n{SPRITE_INLINE}\n"
                  + variant_switch("plugadmin", variant), html, count=1)
    html = html.replace("<title>", f"<title>[{VARIANTS[variant]}] ", 1)
    return html


# ---------------------------------------------------------------- Portail

PORTAL_NAV_ICONS = {"/": "grass", "/actualites": "scroll", "/classements": "xp",
                    "/joueurs": "npc", "/infos": "map", "/compte": "chest"}


def build_portal(variant):
    html = (ROOT / "sources" / "portail-accueil.html").read_text(encoding="utf-8")
    replacements = [
        ('<a class="brand" href="/">LodyQuests</a>',
         f'<a class="brand" href="/">{ico("gem", 32)}<span>LodyQuests</span></a>'),
        ("<h2>Le serveur</h2>", f'<h2>{ico("waypoint", 32, tile=True)}Le serveur</h2>'),
        ("<h2>Saison et événements</h2>", f'<h2>{ico("boss", 32, tile=True)}Saison et événements</h2>'),
        ("<h2>Dernières actualités</h2>", f'<h2>{ico("scroll", 32, tile=True)}Dernières actualités</h2>'),
        ("<h2>Classements</h2>", f'<h2>{ico("xp", 32, tile=True)}Classements</h2>'),
        ('<p class="note">Aucune actualité publiée pour le moment.</p>',
         f'<p class="note lq-empty">{ico("scroll", 48)}<span>Aucune actualité publiée pour le moment.</span></p>'),
        ("<strong>LodyQuests</strong> est un serveur",
         f'<span class="lq-hero-icons">{ico("sword", 32)}{ico("pickaxe", 32)}{ico("bread", 32)}'
         f'{ico("flower", 32)}{ico("map", 32)}</span><strong>LodyQuests</strong> est un serveur'),
    ]
    for old, new in replacements:
        assert old in html, f"fragment absent de la capture portail : {old}"
        html = html.replace(old, new, 1)
    for path, name in PORTAL_NAV_ICONS.items():
        pattern = f'<li><a href="{path}"'
        assert pattern in html, pattern
        html = re.sub(rf'(<li><a href="{re.escape(path)}"[^>]*>)', lambda m, n=name: m.group(1) + ico(n, 16), html, count=1)
    css = (f'<link rel="stylesheet" href="assets/lq-icons.css">\n'
           f'<link rel="stylesheet" href="assets/portail-{variant}.css">\n</head>')
    html = html.replace("</head>", css, 1)
    html = html.replace("<body>", "<body>\n" + SPRITE_INLINE + "\n" + variant_switch("portail", variant), 1)
    html = html.replace("<title>", f"<title>[{VARIANTS[variant]}] ", 1)
    return html


# ---------------------------------------------------------------- Palette

def build_palette():
    cards = []
    for name, (label, cat, _g) in ICONS.items():
        cards.append(
            f'<li class="pal-card"><div class="pal-row">{ico(name, 16)}{ico(name, 32)}{ico(name, 48)}'
            f'{ico(name, 16, tile=True)}</div><strong>{label}</strong><code>lq-{name}</code>'
            f'<span class="pal-cat">{cat}</span></li>')
    grid = "".join(cards)
    mapping_rows = "".join(
        f"<tr><td><code>{m}</code></td><td>{ico(icon_for(m), 16)} <code>lq-{icon_for(m)}</code></td></tr>"
        for m in ["DIAMOND_SWORD", "IRON_SWORD", "NETHERITE_PICKAXE", "BREAD", "PUMPKIN_PIE", "POPPY",
                  "APPLE", "AMETHYST_SHARD", "POTION", "CHEST", "GRASS_BLOCK", "IRON_ORE",
                  "DEEPSLATE_DIAMOND_ORE", "OAK_LOG", "ZOMBIE", "SKELETON", "SPIDER", "SLIME",
                  "ELYTRA", "TOTEM_OF_UNDYING"])
    return f"""<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Palette d'icônes LodyQuests — #428</title>
<link rel="stylesheet" href="assets/lq-icons.css"><link rel="stylesheet" href="assets/palette.css"></head>
<body>{SPRITE_INLINE}
<div class="proto-bar" role="navigation" aria-label="Prototype #428"><strong>Prototype #428</strong> <a href="index.html">Sommaire</a></div>
<main>
<h1>Palette d'icônes LodyQuests ({len(ICONS)} icônes)</h1>
<p>Pixel art <strong>original</strong> 16×16 dessiné pour le projet — aucune texture Mojang. Chaque icône est
montrée à 16, 32 et 48 px (multiples entiers : pixels nets) puis dans sa tuile de 24 px (icône 16 + marge 4).</p>
<section class="pal-theme light" aria-label="Fond clair"><h2>Fond clair</h2><ul class="pal-grid">{grid}</ul></section>
<section class="pal-theme dark" aria-label="Fond sombre"><h2>Fond sombre</h2><ul class="pal-grid">{grid}</ul></section>
<section class="pal-theme light"><h2>Correspondance material → icône (règles phase 2)</h2>
<p>Résolution : nom exact, puis suffixe (<code>_SWORD</code>, <code>_ORE</code>, <code>_LOG</code>…), puis
repli <code>lq-unknown</code>. Les deux dernières lignes montrent le repli.</p>
<table class="pal-map"><thead><tr><th>Material Paper</th><th>Icône</th></tr></thead><tbody>{mapping_rows}</tbody></table>
</section></main></body></html>
"""


def build_index():
    return """<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Prototype #428 — identité visuelle</title><link rel="stylesheet" href="assets/palette.css"></head>
<body><main>
<h1>Prototype #428 — iconographie et identité visuelle</h1>
<p>Phase 1 : à comparer, rien n'est intégré à PlugAdmin ni au portail. Ouvrir ces fichiers depuis une copie du dépôt
(les pages PlugAdmin chargent le CSS réel du panel par chemin relatif).</p>
<ul>
<li><a href="palette.html">Palette des icônes (clair / sombre) et correspondance des materials</a></li>
<li>PlugAdmin — fiche quête : <a href="plugadmin-a.html">variante A</a> · <a href="plugadmin-b.html">variante B</a></li>
<li>Portail public — accueil : <a href="portail-a.html">variante A</a> · <a href="portail-b.html">variante B</a></li>
<li>Captures 1280 px et 390 px : dossier <code>captures/</code></li>
<li>Audit des licences : <code>AUDIT_LICENCES.md</code> · système de design : <code>DESIGN_SYSTEM.md</code></li>
</ul></main></body></html>
"""


def main():
    out = {
        "index.html": build_index(),
        "palette.html": build_palette(),
        "plugadmin-a.html": build_panel("a"),
        "plugadmin-b.html": build_panel("b"),
        "portail-a.html": build_portal("a"),
        "portail-b.html": build_portal("b"),
    }
    for name, html in out.items():
        (ROOT / name).write_text(html, encoding="utf-8")
        print(name, len(html.encode()), "octets")


main()
