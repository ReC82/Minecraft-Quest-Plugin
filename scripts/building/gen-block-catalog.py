#!/usr/bin/env python3
"""Régénère src/main/resources/building/blocks-<version>.txt depuis le registre officiel (#288-292).

Le catalogue des blocs n'est PAS écrit à la main : il vient du rapport que le serveur vanilla
produit lui-même, et que WorldEdit reflète sur Paper (même identifiants, mêmes propriétés).

    curl -O <url du server.jar de la version, lue dans le manifeste Mojang>
    java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports --output gen
    scripts/building/gen-block-catalog.py gen/reports/blocks.json 1.21.11

Une ligne par bloc :  identifiant|propriété=v1,v2;…|propriété=défaut,…
"""
import json
import sys

blocks = json.load(open(sys.argv[1], encoding="utf-8"))
version = sys.argv[2]
lines = [f"# Registre des blocs Minecraft {version}, généré par scripts/building/gen-block-catalog.py"]
for key, value in sorted(blocks.items()):
    props = value.get("properties", {})
    default = next(s for s in value["states"] if s.get("default")).get("properties", {})
    p = ";".join(f"{n}={','.join(vals)}" for n, vals in props.items())
    d = ",".join(f"{n}={v}" for n, v in default.items())
    lines.append(f"{key.removeprefix('minecraft:')}|{p}|{d}")
out = f"src/main/resources/building/blocks-{version}.txt"
open(out, "w", encoding="utf-8").write("\n".join(lines) + "\n")
print(out, len(lines) - 1, "blocs")
