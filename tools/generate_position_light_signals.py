"""Java 1.20.1 / Blockbench cuboid models; current three-lens position lights."""
import copy
import json
import math
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/mtr_brsignal_addon"
NS = "mtr_brsignal_addon:"
SOURCE = ASSETS / "models/block/position_light_ground_dark.json"
COLORS = {"case": (43, 45, 46), "rim": (22, 23, 24), "glass": (58, 61, 62),
          "steel": (112, 117, 120), "red": (240, 48, 44), "yellow": (250, 179, 65), "white": (243, 246, 242)}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def png(path, rgb, lens=False):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    path.parent.mkdir(parents=True, exist_ok=True)
    rows = bytearray()
    size = 32
    for y in range(size):
        rows.append(0)
        for x in range(size):
            inside = (x-15.5)**2 + (y-15.5)**2 <= 12**2
            half = (1, 1.5, 1.5, 1.5, 1.5, 1)[y * 6 // size] * size / 3
            alpha = 0 if lens and not 16-half <= x+0.5 < 16+half else 255
            color = COLORS["rim"] if lens and not inside else rgb
            rows.extend((*color, alpha))
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(bytes(rows))) + chunk(b"IEND", b""))


def box(name, a, b, texture):
    # A 32px texture spans one block: each model unit uses two texels.
    x, y, z = a
    X, Y, Z = b
    uvs = {"north": [16-X, 16-Y, 16-x, 16-y], "south": [x, 16-Y, X, 16-y],
           "east": [16-Z, 16-Y, 16-z, 16-y], "west": [z, 16-Y, Z, 16-y],
           "up": [x, z, X, Z], "down": [x, 16-Z, X, 16-z]}
    return {"name": name, "from": a, "to": b, "faces": {
        face: {"uv": uvs[face], "texture": "#" + texture}
        for face in ("north", "south", "east", "west", "up", "down")}}


def model(pole, display):
    result = json.loads(SOURCE.read_text(encoding="utf-8-sig"))
    result.pop("format_version", None)
    elements = result["elements"]
    if any(e.get("rotation", {}).get("angle", 0) != 0 for e in elements):
        raise ValueError("The authored base must use unrotated cuboids for Java 1.20.1 direction variants")
    if pole:
        elements = [e for e in elements if e["name"] not in ("ground_foot", "ground_post")]
        elements += [box("mtr_pole", [6, 0, 6], [10, 16, 10], "pole"),
                     box("pole_clamp", [5, 3, 6], [11, 4, 11], "steel"),
                     box("pole_clamp", [5, 8, 6], [11, 9, 11], "steel")]
        result["textures"]["pole"] = NS + "block/position_light/mtr_signal_pole"
    for element in elements:
        if not element["name"].endswith("_lens"): continue
        name = element["name"].removesuffix("_lens")
        lit = (display == "proceed" and name in ("upper", "right")) or (display in ("red", "yellow") and name != "upper")
        if lit:
            texture = "white" if display == "proceed" else display
            element["faces"]["north"]["texture"] = "#" + texture
            result["textures"][texture] = NS + "block/position_light/" + texture
    result["elements"] = elements
    return result


def bounds(model, rotation):
    c, s = math.cos(math.radians(rotation)), math.sin(math.radians(rotation))
    points = []
    for e in model["elements"]:
        c, s = (1, 0) if e["name"] == "mtr_pole" else (math.cos(math.radians(rotation)), math.sin(math.radians(rotation)))
        for x in (e["from"][0], e["to"][0]):
            for z in (e["from"][2], e["to"][2]):
                for y in (e["from"][1], e["to"][1]):
                    points.append((8 + (x-8)*c + (z-8)*s, y, 8 - (x-8)*s + (z-8)*c))
    return [min(p[i] for p in points) for i in range(3)] + [max(p[i] for p in points) for i in range(3)]


def main():
    for color, rgb in COLORS.items():
        path = ASSETS / "textures/block/position_light" / (color + ".png")
        if not path.exists():
            png(path, rgb, color in ("glass", "red", "yellow", "white"))
    for pole in (False, True):
        mount = "pole" if pole else "ground"
        for display in ("dark", "red", "yellow", "proceed"):
            base = model(pole, display)
            for suffix, angle in (("", 0), ("_22_5", -22.5), ("_45", -45), ("_67_5", 22.5)):
                if not pole and display == "dark" and not suffix: continue
                variant = copy.deepcopy(base)
                if angle:
                    for e in variant["elements"]:
                        if e["name"] == "mtr_pole": continue
                        e["rotation"] = {"angle": angle, "axis": "y", "origin": [8, 0, 8]}
                write_json(ASSETS / "models/block" / f"position_light_{mount}_{display}{suffix}.json", variant)
    for block, color in (("position_light_signal", "red"), ("yellow_position_light_signal", "yellow")):
        variants = {}
        for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            for angle, suffix in enumerate(("", "_22_5", "_45", "_67_5")):
                for pole in (False, True):
                    for proceed in (False, True):
                        key = f"facing={facing},is_22_5={str(bool(angle & 1)).lower()},is_45={str(bool(angle & 2)).lower()},hanging={str(pole).lower()},proceed={str(proceed).lower()}"
                        display = "proceed" if proceed else "dark" if pole else color
                        mount = "pole" if pole else "ground"
                        entry = {"model": NS + f"block/position_light_{mount}_{display}{suffix}"}
                        rotation = (y + 180 + (90 if angle == 3 else 0)) % 360
                        if rotation: entry["y"] = rotation
                        variants[key] = entry
        write_json(ASSETS / "blockstates" / (block + ".json"), {"variants": variants})
        write_json(ASSETS / "models/item" / (block + ".json"), {"parent": NS + f"block/position_light_ground_{color}"})
        write_json(ROOT / "src/main/resources/data/mtr_brsignal_addon/loot_tables/blocks" / (block + ".json"),
                   {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": NS + block}],
                     "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    lines = ["package org.mtrbr.block;", "", "import net.minecraft.world.level.block.Block;",
             "import net.minecraft.world.phys.shapes.VoxelShape;", "", "/** Generated model bounds, including mounting and all 16 orientations. */",
             "public final class PositionLightSignalGeometry {", "    private PositionLightSignalGeometry() {}",
             "    public static VoxelShape shape(int orientation, boolean pole) { return SHAPES[(pole ? 16 : 0) + orientation]; }",
             "    private static final VoxelShape[] SHAPES = {"]
    for pole in (False, True):
        for i in range(16):
            lines.append("        Block.box(" + ", ".join(f"{v:.6f}" for v in bounds(model(pole, "dark"), -i*22.5-180)) + "),")
    lines += ["    };", "}"]
    (ROOT / "src/main/java/org/mtrbr/block/PositionLightSignalGeometry.java").write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
