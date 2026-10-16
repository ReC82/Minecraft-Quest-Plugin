"""Génère le sprite SVG des icônes LodyQuests (#428) à partir des grilles de icons.py.

    python3 -I build_sprite.py <dossier icons.py> <sortie.svg>

Chaque icône devient un <symbol id="lq-NOM" viewBox="0 0 16 16"> composé de rectangles
fusionnés par ligne, rendu net (crispEdges) à 16, 32 et 48 px (multiples entiers).
"""
import sys

sys.path.insert(0, sys.argv[1])
from icons import ICONS, PALETTE  # noqa: E402


def runs(grid):
    for y, row in enumerate(grid):
        assert len(row) == 16, (y, row)
        x = 0
        while x < 16:
            c = row[x]
            if c == ".":
                x += 1
                continue
            start = x
            while x < 16 and row[x] == c:
                x += 1
            yield c, start, y, x - start


def symbol(name, label, grid):
    by_color = {}
    for c, x, y, w in runs(grid):
        by_color.setdefault(PALETTE[c], []).append(f"M{x} {y}h{w}v1h-{w}z")
    paths = "".join(f'<path fill="{col}" d="{"".join(d)}"/>' for col, d in by_color.items())
    return f'<symbol id="lq-{name}" viewBox="0 0 16 16"><title>{label}</title>{paths}</symbol>'


def main():
    assert len(sys.argv) == 3
    body = "\n".join(symbol(n, l, g) for n, (l, _c, g) in ICONS.items())
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" shape-rendering="crispEdges">\n'
           "<!-- Icônes LodyQuests (#428) : pixel art original du projet, aucune texture Mojang. -->\n"
           f"{body}\n</svg>\n")
    with open(sys.argv[2], "w", encoding="utf-8") as out:
        out.write(svg)
    print(f"{len(ICONS)} icônes, {len(svg.encode())} octets")


main()
