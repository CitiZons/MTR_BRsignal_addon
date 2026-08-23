import json
import math
import sys
from pathlib import Path


def rotate_model(source, target, angle):
    model = json.loads(source.read_text(encoding="utf-8-sig"))
    for element in model.get("elements", []):
        element["rotation"] = {"angle": angle, "axis": "y", "origin": [8, 0, 8]}
    target.write_text(json.dumps(model, ensure_ascii=False, indent="\t") + "\n", encoding="utf-8")


def write_blockstate(root, prefix, routes):
    variants = {}
    facing_rotations = {"north": 0, "east": 90, "south": 180, "west": 270}
    angle_models = {(False, False): "null", (True, False): "22_5", (False, True): "45", (True, True): "67_5"}
    for facing, rotation in facing_rotations.items():
        for (is_22_5, is_45), suffix in angle_models.items():
            for route in routes:
                key = f"facing={facing},is_22_5={str(is_22_5).lower()},is_45={str(is_45).lower()},route={route}"
                if route == "off":
                    model = f"mtr_brsignal_addon:block/{prefix}_null" if suffix == "null" else f"mtr_brsignal_addon:block/{prefix}_{suffix}"
                elif prefix == "indicator_1-4":
                    model = f"mtr_brsignal_addon:block/{prefix}_{route}_{suffix}" if suffix != "null" else f"mtr_brsignal_addon:block/{prefix}_{route}"
                elif prefix == "indicator_1-2":
                    model = f"mtr_brsignal_addon:block/{prefix}_{route}_{suffix}" if suffix != "null" else f"mtr_brsignal_addon:block/{prefix}_{route}"
                elif prefix == "indicator_1":
                    model = f"mtr_brsignal_addon:block/{prefix}_1_{suffix}" if suffix != "null" else f"mtr_brsignal_addon:block/{prefix}_1"
                elif prefix == "indicator_4-5":
                    model = f"mtr_brsignal_addon:block/{prefix}_{route}_{suffix}" if suffix != "null" else f"mtr_brsignal_addon:block/{prefix}_{route}"
                else:
                    model = f"mtr_brsignal_addon:block/{prefix}_{suffix}" if suffix != "null" else f"mtr_brsignal_addon:block/{prefix}_1"
                entry = {"model": model}
                if rotation or (is_22_5 and is_45):
                    entry["y"] = (rotation + (90 if is_22_5 and is_45 else 0)) % 360
                variants[key] = entry
    (Path(root) / "blockstates" / f"{prefix}.json").write_text(json.dumps({"variants": variants}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main(root):
    block = Path(root) / "models" / "block"
    sources = {
        "indicator_1": ("1",),
        "indicator_1-2": ("1", "2"),
        "indicator_1-4": ("1", "4"),
        "indicator_4": ("1",),
        "indicator_4-5": ("4", "5"),
    }
    for prefix, variants in sources.items():
        base = block / f"{prefix}_null.json"
        if not base.exists():
            raise FileNotFoundError(base)
        for angle, suffix in ((-22.5, "22_5"), (-45, "45"), (22.5, "67_5")):
            rotate_model(base, block / f"{prefix}_{suffix}.json", angle)
        for variant in variants:
            source = block / f"{prefix}_{variant}.json"
            if not source.exists():
                raise FileNotFoundError(source)
            for angle, suffix in ((-22.5, "22_5"), (-45, "45"), (22.5, "67_5")):
                rotate_model(source, block / f"{prefix}_{variant}_{suffix}.json", angle)
    write_blockstate(root, "indicator_1", ("off", "1", "4"))
    write_blockstate(root, "indicator_1-2", ("off", "1", "2"))
    write_blockstate(root, "indicator_1-4", ("off", "1", "4"))
    write_blockstate(root, "indicator_4", ("off", "1", "4"))
    write_blockstate(root, "indicator_4-5", ("off", "4", "5"))


if __name__ == "__main__":
    main(sys.argv[1])
