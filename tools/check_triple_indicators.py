"""Resource/geometry regression checks for the two triple-route indicators.
Run: python tools/check_triple_indicators.py
"""
import itertools
import json
import math
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/mtr_brsignal_addon"
JAVA = ROOT / "src/main/java/org/mtrbr"
COMBINATIONS = {"indicator_1-2-4": (1, 2, 4), "indicator_1-4-5": (1, 4, 5)}


def model(name):
    return json.loads((ASSETS / f"models/block/{name}.json").read_text(encoding="utf-8"))


def box(e):
    return tuple(e["from"] + e["to"])


def reflect(b):
    return (16-b[3], b[1], b[2], 16-b[0], b[4], b[5])


def lights(prefix, route):
    return [e for e in model(f"{prefix}_{route}")["elements"]
            if e.get("name", "").startswith(f"{prefix}_route_{route}_")]


def rotate(p, degrees):
    c, s = math.cos(math.radians(degrees)), math.sin(math.radians(degrees))
    x, z = p[0]-8, p[2]-8
    return (8+x*c+z*s, p[1], 8-x*s+z*c)


class TripleIndicators(unittest.TestCase):
    def test_registration_and_translations(self):
        registry = (JAVA / "MTRBR.java").read_text(encoding="utf-8")
        client = (JAVA / "client/ClientSetup.java").read_text(encoding="utf-8")
        entity = (JAVA / "block/ColorLightIndicatorBlockEntity.java").read_text(encoding="utf-8")
        for prefix in COMBINATIONS:
            suffix = prefix.removeprefix("indicator_").replace("-", "_")
            constant = "COLOR_LIGHT_INDICATOR_" + suffix
            for kind in ("BLOCKS", "ITEMS", "BLOCK_ENTITIES"):
                self.assertIn(f'{kind}.register("{prefix}"', registry)
            self.assertIn(f"output.accept(new ItemStack({constant}_ITEM.get()))", registry)
            self.assertIn(f"TripleIndicatorShapes.ROUTES_{suffix}", registry)
            self.assertIn(f"MTRBR.{constant}_BLOCK.get(), RenderType.cutout()", client)
            self.assertIn(f"MTRBR.{constant}_BLOCK_ENTITY.get(), ColorLightIndicatorRenderer::new", client)
            self.assertIn(f"if (state.is(MTRBR.{constant}_BLOCK.get())) return MTRBR.{constant}_BLOCK_ENTITY.get()", entity)
            item = json.loads((ASSETS / f"models/item/{prefix}.json").read_text())
            self.assertEqual(item["parent"], f"mtr_brsignal_addon:block/{prefix}_null")
            for language in ("en_us", "zh_cn"):
                entries = json.loads((ASSETS / f"lang/{language}.json").read_text(encoding="utf-8"))
                self.assertIn(f"block.mtr_brsignal_addon.{prefix}", entries)

    def test_routes_have_five_unobstructed_lights_and_dark_shell(self):
        for prefix, routes in COMBINATIONS.items():
            shell = model(f"{prefix}_null")["elements"]
            self.assertFalse(any("_route_" in e["name"] for e in shell))
            common = None
            for route in routes:
                route_model = model(f"{prefix}_{route}")
                self.assertEqual(route_model["elements"][:len(shell)], shell)
                route_lights = lights(prefix, route)
                self.assertEqual(len(route_lights), 5)
                self.assertEqual(len(route_model["elements"]), len(shell)+5)
                positions = {box(e) for e in route_lights}
                common = positions if common is None else common & positions
                for lamp in route_lights:
                    a, b = lamp["from"], lamp["to"]
                    self.assertEqual([b[i]-a[i] for i in range(3)], [1, 1, 0.5])
                    for x, y in itertools.product((a[0]+0.1, b[0]-0.1), (a[1]+0.1, b[1]-0.1)):
                        self.assertFalse(any(e["from"][0] < x < e["to"][0] and e["from"][1] < y < e["to"][1]
                                             and e["to"][2] > b[2] for e in shell), (prefix, route, x, y))
            self.assertEqual(common, {(7.5, 3, 9, 8.5, 4, 9.5)})
            for e in model(f"{prefix}_1")["elements"]:
                self.assertTrue(all(-16 <= v <= 32 for v in box(e)))
                for face in e["faces"].values():
                    self.assertTrue(all(0 <= v <= 16 for v in face["uv"]))
                    texture = model(f"{prefix}_1")["textures"][face["texture"][1:]]
                    self.assertTrue((ASSETS / "textures" / (texture.split(":")[1]+".png")).is_file(), texture)

    def test_existing_diagonals_and_horizontal_spacing_preserved(self):
        for route in (1, 4):
            self.assertEqual({box(e) for e in lights("indicator_1-2-4", route)},
                             {box(e) for e in lights("indicator_1-4", route)})
        shifted = {(b[0]-1.5, b[1], b[2], b[3]-1.5, b[4], b[5]) for b in map(box, lights("indicator_1-2", 2))}
        self.assertEqual(shifted, {box(e) for e in lights("indicator_1-2-4", 2)})

    def test_filled_side_matches_two_route_backplate(self):
        # Every half-unit cell of the original 1-2 side panel must survive
        # translation to the shared centre lamp, and also survive mirroring.
        source = [e for e in model("indicator_1-2_null")["elements"] if "_back_" in e.get("name", "")]
        panels = {prefix: [e for e in model(f"{prefix}_null")["elements"] if "_back_" in e["name"]]
                  for prefix in COMBINATIONS}
        for e in source:
            for ix in range(round(e["from"][0]*2), round(e["to"][0]*2)):
                for iy in range(round(e["from"][1]*2), round(e["to"][1]*2)):
                    x, y = ix/2 + 0.25 - 1.5, iy/2 + 0.25
                    for prefix, px in (("indicator_1-2-4", x), ("indicator_1-4-5", 16-x)):
                        self.assertTrue(any(p["from"][0] <= px <= p["to"][0]
                                            and p["from"][1] <= y <= p["to"][1]
                                            and p["from"][2] == 7 and p["to"][2] == 9
                                            for p in panels[prefix]), (prefix, px, y))

    def test_mirror_and_no_duplicate_solids(self):
        left, right = (model(f"{p}_null")["elements"] for p in COMBINATIONS)
        self.assertEqual({reflect(box(e)) for e in left}, {box(e) for e in right})
        for a, b in ((1, 4), (2, 5), (4, 1)):
            self.assertEqual({reflect(box(e)) for e in lights("indicator_1-2-4", a)},
                             {box(e) for e in lights("indicator_1-4-5", b)})
        for a, b in itertools.combinations(left, 2):
            overlap = all(max(a["from"][i], b["from"][i]) < min(a["to"][i], b["to"][i]) for i in range(3))
            self.assertFalse(overlap, (a["name"], b["name"]))

    def test_all_blockstates_rotations_and_collision_bounds(self):
        shape_source = (JAVA / "block/TripleIndicatorShapes.java").read_text()
        renderer = (JAVA / "render/ColorLightIndicatorRenderer.java").read_text(encoding="utf-8")
        self.assertIn("QueuedRenderLayer.LIGHT", renderer)
        self.assertIn("rotateYDegrees(-(angle + 180))", renderer)
        for prefix in COMBINATIONS:
            suffix = prefix.removeprefix("indicator_").replace("-", "_")
            source = re.search(r"ROUTES_"+suffix+r" = \{(.*?)\n\t};", shape_source, re.S).group(1)
            shapes = [tuple(map(float, b.split(","))) for b in re.findall(r"Block.box\(([^)]+)\)", source)]
            self.assertEqual(len(shapes), 16)
            states = json.loads((ASSETS / f"blockstates/{prefix}.json").read_text())["variants"]
            states = {k.replace(",hanging=false", ""): v for k, v in states.items() if "hanging=true" not in k}
            self.assertEqual(len(states), 112)  # 16 orientations x all 7 enum values
            for fi, facing in enumerate(("north", "east", "south", "west")):
                for variant in range(4):
                    angle = {"north": 180, "east": 270, "south": 0, "west": 90}[facing]+variant*22.5
                    for route in ("off", "1", "2", "3", "4", "5", "6"):
                        key = f"facing={facing},is_22_5={str(bool(variant&1)).lower()},is_45={str(bool(variant&2)).lower()},route={route}"
                        entry = states[key]
                        self.assertIn("_null", entry["model"])
                        rotated = model(entry["model"].split("/")[-1])
                        points = []
                        for e in rotated["elements"]:
                            transform = e["rotation"]["angle"] - entry.get("y", 0)
                            self.assertAlmostEqual((transform+angle+180) % 360, 0)
                            self.assertEqual(e["rotation"]["origin"], [8, 0, 8])
                            for p in itertools.product(*zip(e["from"], e["to"])):
                                points.append(rotate(p, transform))
                        actual = tuple(min(p[i] for p in points) for i in range(3))+tuple(max(p[i] for p in points) for i in range(3))
                        for a, b in zip(actual, shapes[fi*4+variant]):
                            self.assertAlmostEqual(a, b, delta=0.000051)


if __name__ == "__main__":
    unittest.main(verbosity=2)
