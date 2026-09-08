"""Check visual invariants and resource links without starting Minecraft."""
import json
import math
from pathlib import Path

from PIL import Image, ImageChops, ImageFont

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/mtr_brsignal_addon"
OUT = ASSETS / "textures/block/speed_sign"


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def run():
    art = read(ASSETS / "models/speed_sign_artwork.json")
    assert set(art["contours"]) == {"circle", "triangle", "arrow"}, "All warning speed plates use the same triangle"
    circle = art["contours"]["circle"]
    assert len(circle) >= 128
    assert abs(max(p[0] for p in circle)-min(p[0] for p in circle)-0.75) < 1e-8
    assert all(abs(math.hypot(x-.5, y-.625)-.375) < 1e-8 for x, y in circle)
    assert min(p[1] for p in circle) == .25 and max(p[1] for p in circle) == 1
    assert all(.125-1e-9 <= p[0] <= .875+1e-9 for p in art["contours"]["arrow"])
    assert ImageFont.truetype(str(ASSETS / "font/alte_din_1451_mittelschrift_regular.ttf"), 120).getname() == ("Alte DIN 1451 Mittelschrift", "Regular")
    atlas = Image.open(OUT / "glyphs.png")
    for char, glyph in art["glyphs"].items():
        assert 0 < glyph["w"] < 116 and 0 < glyph["h"] < 116, (char, glyph)
        x, y = round(glyph["u"]*1024), round(glyph["v"]*1024)
        assert atlas.crop((x, y, x+glyph["w"], y+glyph["h"])).getbbox() is not None, char
    for color in ("red", "yellow"):
        pair = Image.open(OUT / f"arrow_{color}_both.png")
        blank = Image.open(OUT / f"arrow_{color}.png")
        left = Image.open(OUT / f"arrow_{color}_left.png")
        right = Image.open(OUT / f"arrow_{color}_right.png")
        # Compare every channel: standalone arrows occupy the unchanged paired-arrow half.
        assert ImageChops.difference(left.crop((0, 0, 256, 512)), pair.crop((0, 0, 256, 512))).getbbox(alpha_only=False) is None
        assert ImageChops.difference(right.crop((256, 0, 512, 512)), pair.crop((256, 0, 512, 512))).getbbox(alpha_only=False) is None
        assert all(sum(left.getpixel((x, 448))[:3]) > 700 for x in range(270, 430))
        assert all(sum(right.getpixel((x, 448))[:3]) > 700 for x in range(82, 242))
        assert pair.getbbox() == left.getbbox() == right.getbbox()
        assert all(abs(a-b) <= 2 for a, b in zip(pair.getbbox(), blank.getbbox())), "Only the resampling fringe may differ"
    names = []
    for prefix in ("permanent_speed", "advance_warning"):
        names.extend([prefix+"_sign", prefix+"_sign_double"])
        names.extend(prefix+"_arrow_"+direction for direction in ("left", "both", "right"))
    for name in names:
        blockstate = read(ASSETS / f"blockstates/{name}.json")
        assert blockstate["variants"][""]["model"] == f"mtr_brsignal_addon:block/{name}"
        model = read(ASSETS / f"models/block/{name}.json")
        assert model["elements"] == [], "The smooth plate is rendered by its block entity"
        for value in model["textures"].values():
            assert (ASSETS / ("textures/"+value.split(":")[1]+".png")).exists(), value
        item = read(ASSETS / f"models/item/{name}.json")
        assert (ASSETS / ("textures/"+item["textures"]["layer0"].split(":")[1]+".png")).exists()
        assert read(ROOT / f"src/main/resources/data/mtr_brsignal_addon/loot_tables/blocks/{name}.json")["type"] == "minecraft:block"
        for language in ("en_us", "zh_cn"):
            assert "block.mtr_brsignal_addon."+name in read(ASSETS / f"lang/{language}.json")
    assert (OUT / "mtr_signal_pole.png").read_bytes() == (ROOT.parent / "MTR/fabric/src/main/resources/assets/mtr/textures/block/metal.png").read_bytes()
    print("Speed sign resources passed: 10 blocks, exact paired-arrow halves, font glyphs, smooth contours, pole texture, item/loot/lang links")


if __name__ == "__main__":
    run()
