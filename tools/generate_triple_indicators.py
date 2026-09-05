"""Build the two triple-route indicators from the existing authored colour-light parts.

Run from anywhere: python tools/generate_triple_indicators.py
Per-indicator silhouette textures are generated alongside the JSON models.
The 1-4 lamp positions, horizontal 1-2 spacing and hood depths remain unchanged.
"""
import copy
import itertools
import json
import math
from pathlib import Path

from generate_indicator_rotations import rotate_model
from indicator_textures import write_indicator_textures

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/mtr_brsignal_addon"
MODELS = ASSETS / "models/block"
COMBINATIONS = {"indicator_1-2-4": (1, 2, 4), "indicator_1-4-5": (1, 4, 5)}
FACES = ("north", "south", "east", "west", "up", "down")


def read_model(name):
    return json.loads((MODELS / f"{name}.json").read_text(encoding="utf-8-sig"))


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def bounds(element, dx=0):
    a, b = element["from"], element["to"]
    return (a[0] + dx, a[1], a[2], b[0] + dx, b[1], b[2])


def union_boxes(boxes):
    """Union quarter-unit source cuboids, merging cells into disjoint cuboids.

    This removes the duplicated centre hood and overlapping backplates instead
    of stacking coplanar faces from the two source models.
    """
    occupied = set()
    for box in boxes:
        lo, hi = [round(v * 4) for v in box[:3]], [round(v * 4) for v in box[3:]]
        occupied.update(itertools.product(*(range(a, b) for a, b in zip(lo, hi))))
    result = []
    while occupied:
        x, y, z = min(occupied)
        x2, y2, z2 = x + 1, y + 1, z + 1
        while (x2, y, z) in occupied:
            x2 += 1
        while all((i, y2, z) in occupied for i in range(x, x2)):
            y2 += 1
        while all((i, j, z2) in occupied for i in range(x, x2) for j in range(y, y2)):
            z2 += 1
        occupied.difference_update(itertools.product(range(x, x2), range(y, y2), range(z, z2)))
        result.append(tuple(v / 4 for v in (x, y, z, x2, y2, z2)))
    return result


def mirror(box):
    return (16 - box[3], box[1], box[2], 16 - box[0], box[4], box[5])


def element(name, box, material):
    # All three source textures use one flat opaque colour. Inset UVs avoid
    # sampling the transparent edge of the chosen texel at atlas boundaries.
    uv = {"back": [2.125, 4.625, 2.375, 4.875],
          "hood": [1.625, 5.625, 1.875, 5.875],
          "strip": [2.625, 7.125, 2.875, 7.375]}.get(material, [0, 0, 16, 16])
    return {"name": name, "from": list(box[:3]), "to": list(box[3:]),
            "rotation": {"angle": 0, "axis": "y", "origin": [8, 0, 8]},
            "faces": {face: {"uv": uv[:], "texture": "#" + material} for face in FACES}}


def join_strip_center(boxes, cy, routes):
    # User's 12456 strip edit fills x=7.5..8.5, y=cy+.5..cy+1.5.
    # Extend through cy as well when no horizontal rib covers the bottom half.
    # Restrict the change to this central 1x1.5 area; keep all outer arms intact.
    if 1 in routes or 4 in routes:
        boxes = list(boxes) + [(7.5,cy,6,8.5,cy+1.5,7)]
    return union_boxes(boxes)


def source_geometry():
    diagonal, horizontal = read_model("indicator_1-4_null"), read_model("indicator_1-2_null")
    groups = {"base": [], "back": [], "hood": [], "strip": []}
    for e in diagonal["elements"]:
        name = e.get("name", "")
        group = "base" if not name else "back" if "_back_" in name else "strip" if "_strip_" in name else "hood"
        groups[group].append(bounds(e))
    for e in horizontal["elements"]:
        name = e.get("name", "")
        if "_back_" in name:
            # Keep the complete 1-2 backplate, including the filled side
            # between the diagonal and horizontal lamps. Clipping it to the
            # horizontal row creates an unwanted notch / projecting branch.
            groups["back"].append(bounds(e, -1.5))
        elif name and "_strip_" not in name and e["from"][2] == 9 and e["to"][1] <= 4.5:
            groups["hood"].append(bounds(e, -1.5))
    # Join the horizontal rear brace to the unchanged central upright.
    groups["strip"].append((-0.5, 2.5, 6, 8, 4, 7))
    groups["strip"] = join_strip_center(groups["strip"], 3.5, (1,2,4))
    return {key: union_boxes(value) for key, value in groups.items()}


def route_geometry():
    lights = {}
    for route, source, dx in ((1, "indicator_1-4", 0), (2, "indicator_1-2", -1.5), (4, "indicator_1-4", 0)):
        lights[route] = [bounds(e, dx) for e in read_model(f"{source}_{route}")["elements"]
                         if e.get("name", "").startswith(f"{source}_route_{route}_")]
        assert len(lights[route]) == 5
    return lights


def build_base_models():
    groups, routes = source_geometry(), route_geometry()
    for prefix in COMBINATIONS:
        mirrored = prefix == "indicator_1-4-5"
        transform = mirror if mirrored else lambda box: box
        shell = {"credit": "Derived from the existing MTR BR colour-light indicator models",
                 "textures": {"base": "mtr_brsignal_addon:block/grey",
                              "back": "mtr_brsignal_addon:block/indicator/indicator_1-4_back",
                              "hood": "mtr_brsignal_addon:block/indicator/indicator_1-4",
                              "strip": "mtr_brsignal_addon:block/indicator/indicator_1-4_strip",
                              "particle": "mtr_brsignal_addon:block/grey"},
                 "elements": [element(f"{prefix}_{group}_{i}", transform(box), group)
                              for group, boxes in groups.items() for i, box in enumerate(boxes)]}
        write_json(MODELS / f"{prefix}_null.json", shell)
        write_json(ASSETS / f"models/item/{prefix}.json", {"parent": f"mtr_brsignal_addon:block/{prefix}_null"})
        for source_route, lights in routes.items():
            route = {1: 4, 2: 5, 4: 1}[source_route] if mirrored else source_route
            model = copy.deepcopy(shell)
            model["textures"]["light"] = "mtr_brsignal_addon:block/white"
            model["elements"].extend(element(f"{prefix}_route_{route}_{i}", transform(box), "light")
                                     for i, box in enumerate(lights))
            write_json(MODELS / f"{prefix}_{route}.json", model)

    for prefix, routes in COMBINATIONS.items():
        write_indicator_textures(ASSETS, prefix, routes)


def rotate(point, angle):
    x, y, z = point[0] - 8, point[1], point[2] - 8
    c, s = math.cos(math.radians(angle)), math.sin(math.radians(angle))
    return (8 + x * c + z * s, y, 8 - x * s + z * c)


def build_rotations_and_shapes(combinations=COMBINATIONS, shape_class="TripleIndicatorShapes",
                               generator="generate_triple_indicators.py"):
    java = ["package org.mtrbr.block;", "", "import net.minecraft.world.level.block.Block;",
            "import net.minecraft.world.phys.shapes.VoxelShape;", "",
            f"/** Generated by tools/{generator} from the model geometry. */",
            f"public final class {shape_class} {{", f"\tprivate {shape_class}() {{}}"]
    for prefix in combinations:
        for angle, suffix in ((-22.5, "22_5"), (-45, "45"), (22.5, "67_5")):
            rotate_model(MODELS / f"{prefix}_null.json", MODELS / f"{prefix}_null_{suffix}.json", angle)
        variants = {}
        java.extend(["", f"\tpublic static final VoxelShape[] ROUTES_{prefix[10:].replace('-', '_')} = {{"])
        for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            java.append("\t\t// " + facing.upper())
            for index, suffix in enumerate(("", "_22_5", "_45", "_67_5")):
                model_name = f"{prefix}_null{suffix}"
                rotation = (y + (90 if index == 3 else 0)) % 360
                # Every enum value resolves to the dark shell. The authorized
                # route is drawn by ColorLightIndicatorRenderer, never baked lit.
                for route in ("off", "1", "2", "3", "4", "5", "6"):
                    key = f"facing={facing},is_22_5={str(bool(index & 1)).lower()},is_45={str(bool(index & 2)).lower()},route={route}"
                    variants[key] = {"model": f"mtr_brsignal_addon:block/{model_name}", "y": rotation}
                points = [rotate(p, e["rotation"]["angle"] - rotation)
                          for e in read_model(model_name)["elements"]
                          for p in itertools.product(*zip(e["from"], e["to"]))]
                box = tuple(min(p[i] for p in points) for i in range(3)) + tuple(max(p[i] for p in points) for i in range(3))
                java.append("\t\tBlock.box(" + ", ".join(f"{0 if abs(v) < 0.00005 else v:.4f}" for v in box) + "),")

        java.append("\t};")
        write_json(ASSETS / f"blockstates/{prefix}.json", {"variants": variants})
    java.append("}")
    (ROOT / f"src/main/java/org/mtrbr/block/{shape_class}.java").write_text("\n".join(java) + "\n", encoding="utf-8")


if __name__ == "__main__":
    build_base_models()
    build_rotations_and_shapes()
    from generate_signal_mounts import generate as generate_mounts
    generate_mounts()
    print("Generated 1-2-4 and 1-4-5 shells, six route models, rotation variants and collision bounds.")
