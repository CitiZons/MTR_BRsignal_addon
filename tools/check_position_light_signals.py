"""Validate shipped models and draw a preview directly from their cuboid faces."""
import json
import copy
import math
from pathlib import Path
from PIL import Image, ImageDraw
import numpy as np
from generate_position_light_signals import ASSETS, ROOT, COLORS, bounds


def load(name):
    return json.loads((ASSETS / "models/block" / (name + ".json")).read_text(encoding="utf-8"))


def validate():
    checked = 0
    source = load("position_light_ground_dark")
    def authored_geometry(elements):
        result = copy.deepcopy(elements)
        for element in result:
            element.pop("rotation", None)
            if element["name"].endswith("_lens"):
                element["faces"]["north"]["texture"] = "#glass"
        return result
    for block in ("position_light_signal", "yellow_position_light_signal"):
        variants = json.loads((ASSETS / "blockstates" / (block + ".json")).read_text())["variants"]
        assert len(variants) == 64
        for state, variant in variants.items():
            model = load(variant["model"].split("block/")[1])
            properties = dict(pair.split("=") for pair in state.split(","))
            facing = {"north": 0, "east": 90, "south": 180, "west": 270}[properties["facing"]]
            diagonal = properties["is_22_5"] == properties["is_45"] == "true"
            assert variant.get("y", 0) == (facing + 180 + (90 if diagonal else 0)) % 360
            assert model["texture_size"] == [32, 32]
            colors = {e["name"].split("_")[0]: e["faces"]["north"]["texture"][1:]
                      for e in model["elements"] if e["name"].endswith("_lens")}
            pole, proceed = "hanging=true" in state, "proceed=true" in state
            off = "yellow" if block.startswith("yellow") else "red"
            assert colors == ({"upper": "white", "left": "glass", "right": "white"} if proceed else
                              {"upper": "glass", "left": "glass" if pole else off, "right": "glass" if pole else off})
            assert min(e["from"][1] for e in model["elements"]) == 0
            assert min(e["from"][0] for e in model["elements"]) == 2
            assert max(e["to"][0] for e in model["elements"]) == 14
            assert max(e["to"][1] for e in model["elements"] if e["name"] != "mtr_pole") == 12
            if pole:
                post = next(e for e in model["elements"] if e["name"] == "mtr_pole")
                assert post["from"] == [6,0,6] and post["to"] == [10,16,10]
            else:
                post = next(e for e in model["elements"] if e["name"] == "ground_post")
                assert post["from"] == [7,0.5,5] and post["to"] == [9,2,7]
                assert all(face["texture"] == "#steel" for face in post["faces"].values())
            expected = [e for e in source["elements"] if not pole or e["name"] not in ("ground_foot", "ground_post")]
            actual = [e for e in model["elements"] if e["name"] not in ("mtr_pole", "pole_clamp")]
            assert authored_geometry(actual) == authored_geometry(expected), state
            for element in model["elements"]:
                assert all(-16 <= value <= 32 for key in ("from", "to") for value in element[key])
                assert all(a <= b for a, b in zip(element["from"], element["to"]))
                if "rotation" in element:
                    assert element["rotation"]["angle"] in (-45, -22.5, 0, 22.5, 45)
                assert not any(word in element["name"] for word in ("arrow", "label", "plate", "text"))
            housing = [e for e in model["elements"] if e["name"] == "housing"]
            assert len(housing) == 20
            assert min(e["from"][1] for e in housing) == 2
            assert all(e["to"][1]-e["from"][1] == 0.5 for e in housing)
            for texture in model["textures"].values():
                assert texture.startswith("mtr_brsignal_addon:"), texture
                assert (ASSETS / "textures" / (texture.split(":")[1] + ".png")).is_file()
                expected_size = (16, 16) if texture.endswith("/mtr_signal_pole") else (32, 32)
                assert Image.open(ASSETS / "textures" / (texture.split(":")[1] + ".png")).size == expected_size
            checked += 1
    geometry = (ROOT / "src/main/java/org/mtrbr/block/PositionLightSignalGeometry.java").read_text()
    import re
    boxes = [[float(v) for v in row.split(",")] for row in re.findall(r"Block.box\(([^)]+)\)", geometry)]
    assert len(boxes) == 32
    for index, box in enumerate(boxes):
        expected = bounds(load("position_light_" + ("pole" if index >= 16 else "ground") + "_dark"), -(index % 16)*22.5-180)
        assert all(abs(a-b) < 1e-5 for a, b in zip(box, expected))
    for state, variant in variants.items():
        properties = dict(pair.split("=") for pair in state.split(","))
        orientation = {"north": 0, "east": 4, "south": 8, "west": 12}[properties["facing"]]
        orientation += (properties["is_22_5"] == "true") + 2*(properties["is_45"] == "true")
        shape = boxes[orientation + (16 if properties["hanging"] == "true" else 0)]
        model = load(variant["model"].split("block/")[1])
        transformed = [bounds({"elements": [e]}, e.get("rotation", {}).get("angle", 0)-variant.get("y", 0)) for e in model["elements"]]
        expected = [min(b[i] for b in transformed) for i in range(3)] + [max(b[i] for b in transformed) for i in range(3, 6)]
        assert all(abs(a-b) < 1e-5 for a, b in zip(shape, expected)), state
    print(f"Position light resources: {checked} states match authored geometry/UVs, 32 collision bounds and lamp displays passed.")


def preview():
    scale = 3
    image = Image.new("RGB", (1000*scale, 720*scale), (231, 235, 237))
    pixels = np.asarray(image).copy()
    depths = np.full(pixels.shape[:2], np.inf)
    labels = []
    entries = [("ground_red", "Ground / stop"), ("ground_yellow", "Ground / yellow"), ("ground_proceed", "Ground / proceed"),
               ("pole_dark", "Pole / unlit"), ("pole_proceed", "Pole / proceed"), ("ground_red", "Ground / side")]
    for index, (name, label) in enumerate(entries):
        ox, oy = (index % 3)*333+166, (index//3)*350+260
        angle = math.radians(38 if index == 5 else 12)
        c, s = math.cos(angle), math.sin(angle)
        def project(x, y, z):
            x, z = x-8, z-8
            a, depth = -x*c + z*s, x*s + z*c
            return ((ox + 16*a)*scale, (oy - 16*y - 4*depth)*scale, depth - .25*y)
        model = load("position_light_" + name)
        polygons = []
        for element in model["elements"]:
            x,y,z = element["from"]
            X,Y,Z = element["to"]
            faces = {"north": [(x,y,z),(X,y,z),(X,Y,z),(x,Y,z)],
                     "west": [(x,y,z),(x,y,Z),(x,Y,Z),(x,Y,z)],
                     "up": [(x,Y,z),(X,Y,z),(X,Y,Z),(x,Y,Z)]}
            texture_key = next(iter(element["faces"].values()))["texture"][1:]
            color = COLORS.get(texture_key, (160,163,165))
            for face, vertices in faces.items():
                if face not in element["faces"]: continue
                depth = sum((a-8)*s + (d-8)*c for a,b,d in vertices)/4
                tint = 1 if face == "north" else .72 if face == "west" else 1.12
                rgb = tuple(min(255, int(v*tint)) for v in color)
                sprite = None
                if element["name"].endswith("_lens") or texture_key == "pole":
                    texture = model["textures"][texture_key].split(":")[1]
                    sprite = np.asarray(Image.open(ASSETS / "textures" / (texture + ".png")).convert("RGBA")).copy()
                    sprite[:,:,:3] = np.clip(sprite[:,:,:3].astype(float) * tint, 0, 255).astype(np.uint8)
                uv = [value / 16 for value in element["faces"][face]["uv"]]
                polygons.append((depth, [project(*p) for p in vertices], rgb, sprite, uv))
        for _, poly, color, sprite, face_uv in polygons:
            for indices in ((0, 1, 2), (0, 2, 3)):
                a, b, c = [poly[i] for i in indices]
                x0, x1 = max(0, int(min(p[0] for p in (a,b,c)))), min(pixels.shape[1]-1, math.ceil(max(p[0] for p in (a,b,c))))
                y0, y1 = max(0, int(min(p[1] for p in (a,b,c)))), min(pixels.shape[0]-1, math.ceil(max(p[1] for p in (a,b,c))))
                denominator = (b[1]-c[1])*(a[0]-c[0]) + (c[0]-b[0])*(a[1]-c[1])
                if abs(denominator) < 1e-8: continue
                yy, xx = np.mgrid[y0:y1+1, x0:x1+1]
                u = ((b[1]-c[1])*(xx+.5-c[0]) + (c[0]-b[0])*(yy+.5-c[1]))/denominator
                v = ((c[1]-a[1])*(xx+.5-c[0]) + (a[0]-c[0])*(yy+.5-c[1]))/denominator
                w = 1-u-v
                depth = u*a[2]+v*b[2]+w*c[2]
                current = depths[y0:y1+1, x0:x1+1]
                mask = (u >= -1e-6) & (v >= -1e-6) & (w >= -1e-6) & (depth < current)
                if sprite is not None:
                    U,V,u1,v1 = face_uv
                    uv = [(U,v1), (u1,v1), (u1,V), (U,V)]
                    tx = np.clip(((u*uv[indices[0]][0]+v*uv[indices[1]][0]+w*uv[indices[2]][0])*sprite.shape[1]).astype(int), 0, sprite.shape[1]-1)
                    ty = np.clip(((u*uv[indices[0]][1]+v*uv[indices[1]][1]+w*uv[indices[2]][1])*sprite.shape[0]).astype(int), 0, sprite.shape[0]-1)
                    sampled = sprite[ty,tx]
                    mask &= sampled[:,:,3] > 0
                current[mask] = depth[mask]
                pixels[y0:y1+1, x0:x1+1][mask] = sampled[:,:,:3][mask] if sprite is not None else color
        labels.append((((ox-80)*scale, (oy+36)*scale), label))
    image = Image.fromarray(pixels)
    draw = ImageDraw.Draw(image)
    for point, label in labels:
        draw.text(point, label, fill=(35,40,44), font_size=16*scale)
    output = ROOT / "build/position-light-preview.png"
    output.parent.mkdir(exist_ok=True)
    image.resize((1000,720), Image.Resampling.LANCZOS).save(output)
    print(output)


if __name__ == "__main__":
    validate()
    preview()
